/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hudi.metadata;

import org.apache.hudi.avro.HoodieAvroUtils;
import org.apache.hudi.avro.model.HoodieMetadataRecord;
import org.apache.hudi.avro.model.HoodieRestoreMetadata;
import org.apache.hudi.avro.model.HoodieRollbackMetadata;
import org.apache.hudi.common.config.HoodieCommonConfig;
import org.apache.hudi.common.config.HoodieMetadataConfig;
import org.apache.hudi.common.data.HoodieData;
import org.apache.hudi.common.data.HoodieListData;
import org.apache.hudi.common.engine.HoodieEngineContext;
import org.apache.hudi.common.function.SerializableFunction;
import org.apache.hudi.common.model.FileSlice;
import org.apache.hudi.common.model.HoodieAvroRecord;
import org.apache.hudi.common.model.HoodieBaseFile;
import org.apache.hudi.common.model.HoodieLogFile;
import org.apache.hudi.common.model.HoodieRecord;
import org.apache.hudi.common.model.HoodieRecord.HoodieRecordType;
import org.apache.hudi.common.table.HoodieTableConfig;
import org.apache.hudi.common.table.HoodieTableMetaClient;
import org.apache.hudi.common.table.timeline.HoodieActiveTimeline;
import org.apache.hudi.common.table.timeline.HoodieInstant;
import org.apache.hudi.common.table.timeline.HoodieTimeline;
import org.apache.hudi.common.table.timeline.TimelineMetadataUtils;
import org.apache.hudi.common.table.view.HoodieTableFileSystemView;
import org.apache.hudi.common.util.ClosableIterator;
import org.apache.hudi.common.util.CollectionUtils;
import org.apache.hudi.common.util.HoodieTimer;
import org.apache.hudi.common.util.Option;
import org.apache.hudi.common.util.SpillableMapUtils;
import org.apache.hudi.common.util.StringUtils;
import org.apache.hudi.common.util.collection.Pair;
import org.apache.hudi.exception.HoodieException;
import org.apache.hudi.exception.HoodieIOException;
import org.apache.hudi.exception.HoodieMetadataException;
import org.apache.hudi.exception.TableNotFoundException;
import org.apache.hudi.util.Transient;
import org.apache.hudi.io.storage.HoodieFileReaderFactory;
import org.apache.hudi.io.storage.HoodieSeekingFileReader;

import org.apache.avro.Schema;
import org.apache.avro.generic.GenericRecord;
import org.apache.hadoop.fs.Path;
import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.apache.hudi.common.util.CollectionUtils.isNullOrEmpty;
import static org.apache.hudi.common.util.CollectionUtils.toStream;
import static org.apache.hudi.metadata.HoodieTableMetadataUtil.PARTITION_NAME_BLOOM_FILTERS;
import static org.apache.hudi.metadata.HoodieTableMetadataUtil.PARTITION_NAME_COLUMN_STATS;
import static org.apache.hudi.metadata.HoodieTableMetadataUtil.PARTITION_NAME_FILES;
import static org.apache.hudi.metadata.HoodieTableMetadataUtil.getFileSystemView;

/**
 * Table metadata provided by an internal DFS backed Hudi metadata table.
 */
public class HoodieBackedTableMetadata extends BaseTableMetadata {

  private static final Logger LOG = LogManager.getLogger(HoodieBackedTableMetadata.class);

  private final String metadataBasePath;

  private HoodieTableMetaClient metadataMetaClient;
  private HoodieTableConfig metadataTableConfig;

  private HoodieTableFileSystemView metadataFileSystemView;
  // should we reuse the open file handles, across calls
  private final boolean reuse;

  // Readers for the latest file slice corresponding to file groups in the metadata partition
  private final Transient<Map<Pair<String, String>, Pair<HoodieSeekingFileReader<?>, HoodieMetadataLogRecordReader>>> partitionReaders =
      Transient.lazy(ConcurrentHashMap::new);

  public HoodieBackedTableMetadata(HoodieEngineContext engineContext, HoodieMetadataConfig metadataConfig,
                                   String datasetBasePath, String spillableMapDirectory) {
    this(engineContext, metadataConfig, datasetBasePath, spillableMapDirectory, false);
  }

  public HoodieBackedTableMetadata(HoodieEngineContext engineContext, HoodieMetadataConfig metadataConfig,
                                   String datasetBasePath, String spillableMapDirectory, boolean reuse) {
    super(engineContext, metadataConfig, datasetBasePath, spillableMapDirectory);
    this.reuse = reuse;
    this.metadataBasePath = HoodieTableMetadata.getMetadataTableBasePath(dataBasePath.toString());

    initIfNeeded();
  }

  private void initIfNeeded() {
    if (!isMetadataTableEnabled) {
      if (!HoodieTableMetadata.isMetadataTable(metadataBasePath)) {
        LOG.info("Metadata table is disabled.");
      }
    } else if (this.metadataMetaClient == null) {
      try {
        this.metadataMetaClient = HoodieTableMetaClient.builder().setConf(getHadoopConf()).setBasePath(metadataBasePath).build();
        this.metadataFileSystemView = getFileSystemView(metadataMetaClient);
        this.metadataTableConfig = metadataMetaClient.getTableConfig();
        this.isBloomFilterIndexEnabled = metadataConfig.isBloomFilterIndexEnabled();
        this.isColumnStatsIndexEnabled = metadataConfig.isColumnStatsIndexEnabled();
      } catch (TableNotFoundException e) {
        LOG.warn("Metadata table was not found at path " + metadataBasePath);
        this.isMetadataTableEnabled = false;
        this.metadataMetaClient = null;
        this.metadataFileSystemView = null;
        this.metadataTableConfig = null;
      } catch (Exception e) {
        LOG.error("Failed to initialize metadata table at path " + metadataBasePath, e);
        this.isMetadataTableEnabled = false;
        this.metadataMetaClient = null;
        this.metadataFileSystemView = null;
        this.metadataTableConfig = null;
      }
    }
  }

  @Override
  protected Option<HoodieRecord<HoodieMetadataPayload>> getRecordByKey(String key, String partitionName) {
    List<Pair<String, Option<HoodieRecord<HoodieMetadataPayload>>>> recordsByKeys = getRecordsByKeys(Collections.singletonList(key), partitionName);
    return recordsByKeys.size() == 0 ? Option.empty() : recordsByKeys.get(0).getValue();
  }

  @Override
  public List<String> getPartitionPathWithPathPrefixes(List<String> relativePathPrefixes) throws IOException {
    // TODO: consider skipping this method for non-partitioned table and simplify the checks
    return getAllPartitionPaths().stream()
        .filter(p -> relativePathPrefixes.stream().anyMatch(relativePathPrefix ->
            // Partition paths stored in metadata table do not have the slash at the end.
            // If the relativePathPrefix is empty, return all partition paths;
            // else if the relative path prefix is the same as the path, this is an exact match;
            // else, we need to make sure the path is a sub-directory of relativePathPrefix, by
            // checking if the path starts with relativePathPrefix appended by a slash ("/").
            StringUtils.isNullOrEmpty(relativePathPrefix)
                || p.equals(relativePathPrefix) || p.startsWith(relativePathPrefix + "/")))
        .collect(Collectors.toList());
  }

  @Override
  public HoodieData<HoodieRecord<HoodieMetadataPayload>> getRecordsByKeyPrefixes(List<String> keyPrefixes,
                                                                                 String partitionName,
                                                                                 boolean shouldLoadInMemory) {
    // Sort the columns so that keys are looked up in order
    List<String> sortedKeyPrefixes = new ArrayList<>(keyPrefixes);
    Collections.sort(sortedKeyPrefixes);

    // NOTE: Since we partition records to a particular file-group by full key, we will have
    //       to scan all file-groups for all key-prefixes as each of these might contain some
    //       records matching the key-prefix
    List<FileSlice> partitionFileSlices =
        HoodieTableMetadataUtil.getPartitionLatestMergedFileSlices(
            metadataMetaClient, metadataFileSystemView, partitionName);

    return (shouldLoadInMemory ? HoodieListData.lazy(partitionFileSlices) :
        engineContext.parallelize(partitionFileSlices))
        .flatMap(
            (SerializableFunction<FileSlice, Iterator<HoodieRecord<HoodieMetadataPayload>>>) fileSlice -> {
              // NOTE: Since this will be executed by executors, we can't access previously cached
              //       readers, and therefore have to always open new ones
              Pair<HoodieSeekingFileReader<?>, HoodieMetadataLogRecordReader> readers =
                  openReaders(partitionName, fileSlice);
              try {
                List<Long> timings = new ArrayList<>();

                HoodieSeekingFileReader<?> baseFileReader = readers.getKey();
                HoodieMetadataLogRecordReader logRecordScanner = readers.getRight();

                if (baseFileReader == null && logRecordScanner == null) {
                  // TODO: what do we do if both does not exist? should we throw an exception and let caller do the fallback ?
                  return Collections.emptyIterator();
                }

              boolean fullKeys = false;

              Map<String, Option<HoodieRecord<HoodieMetadataPayload>>> logRecords =
                  readLogRecords(logRecordScanner, sortedKeyPrefixes, fullKeys, timings);

              List<Pair<String, Option<HoodieRecord<HoodieMetadataPayload>>>> mergedRecords =
                  readFromBaseAndMergeWithLogRecords(baseFileReader, sortedKeyPrefixes, fullKeys, logRecords, timings, partitionName);

              LOG.debug(String.format("Metadata read for %s keys took [baseFileRead, logMerge] %s ms",
                  sortedKeyPrefixes.size(), timings));

              return mergedRecords.stream()
                .map(keyRecordPair -> keyRecordPair.getValue().orElse(null))
                .filter(Objects::nonNull)
                .iterator();
            } catch (IOException ioe) {
              throw new HoodieIOException("Error merging records from metadata table for  " + sortedKeyPrefixes.size() + " key : ", ioe);
            } finally {
              closeReader(readers);
            }
          });
  }

  @Override
  public List<Pair<String, Option<HoodieRecord<HoodieMetadataPayload>>>> getRecordsByKeys(List<String> keys,
                                                                                          String partitionName) {
    // Sort the columns so that keys are looked up in order
    List<String> sortedKeys = new ArrayList<>(keys);
    Collections.sort(sortedKeys);
    Map<Pair<String, FileSlice>, List<String>> partitionFileSliceToKeysMap = getPartitionFileSliceToKeysMapping(partitionName, sortedKeys);
    List<Pair<String, Option<HoodieRecord<HoodieMetadataPayload>>>> result = new ArrayList<>();
    AtomicInteger fileSlicesKeysCount = new AtomicInteger();
    partitionFileSliceToKeysMap.forEach((partitionFileSlicePair, fileSliceKeys) -> {
      Pair<HoodieSeekingFileReader<?>, HoodieMetadataLogRecordReader> readers =
          getOrCreateReaders(partitionName, partitionFileSlicePair.getRight());
      try {
        List<Long> timings = new ArrayList<>();
        HoodieSeekingFileReader<?> baseFileReader = readers.getKey();
        HoodieMetadataLogRecordReader logRecordScanner = readers.getRight();
        if (baseFileReader == null && logRecordScanner == null) {
          return;
        }

        boolean fullKeys = true;
        Map<String, Option<HoodieRecord<HoodieMetadataPayload>>> logRecords =
            readLogRecords(logRecordScanner, fileSliceKeys, fullKeys, timings);

        result.addAll(readFromBaseAndMergeWithLogRecords(baseFileReader, fileSliceKeys, fullKeys, logRecords,
            timings, partitionName));

        LOG.debug(String.format("Metadata read for %s keys took [baseFileRead, logMerge] %s ms",
            fileSliceKeys.size(), timings));
        fileSlicesKeysCount.addAndGet(fileSliceKeys.size());
      } catch (IOException ioe) {
        throw new HoodieIOException("Error merging records from metadata table for  " + sortedKeys.size() + " key : ", ioe);
      } finally {
        if (!reuse) {
          closeReader(readers);
        }
      }
    });

    return result;
  }

  private Map<String, Option<HoodieRecord<HoodieMetadataPayload>>> readLogRecords(HoodieMetadataLogRecordReader logRecordReader,
                                                                                  List<String> keys,
                                                                                  boolean fullKey,
                                                                                  List<Long> timings) {
    HoodieTimer timer = HoodieTimer.start();

    if (logRecordReader == null) {
      timings.add(timer.endTimer());
      return Collections.emptyMap();
    }

    Map<String, Option<HoodieRecord<HoodieMetadataPayload>>> logRecords = new HashMap<>(keys.size());

    // First, fetch the keys being looked up
    List<Pair<String, Option<HoodieRecord<HoodieMetadataPayload>>>> logRecordsList =
        (fullKey ? logRecordReader.getRecordsByKeys(keys) : logRecordReader.getRecordsByKeyPrefixes(keys))
                .stream()
                .map(record -> Pair.of(record.getRecordKey(), Option.of(record)))
                .collect(Collectors.toList());

    // Second, back-fill keys not present in the log-blocks (such that map holds
    // a record for every key being looked up)
    List<String> missingKeys = CollectionUtils.diff(keys, logRecords.keySet());
    for (String key : missingKeys) {
      logRecords.put(key, Option.empty());
    }

    for (Pair<String, Option<HoodieRecord<HoodieMetadataPayload>>> entry : logRecordsList) {
      logRecords.put(entry.getKey(), entry.getValue());
    }

    timings.add(timer.endTimer());
    return logRecords;
  }

  private List<Pair<String, Option<HoodieRecord<HoodieMetadataPayload>>>> readFromBaseAndMergeWithLogRecords(HoodieSeekingFileReader<?> reader,
                                                                                                             List<String> keys,
                                                                                                             boolean fullKeys,
                                                                                                             Map<String, Option<HoodieRecord<HoodieMetadataPayload>>> logRecords,
                                                                                                             List<Long> timings,
                                                                                                             String partitionName) throws IOException {
    HoodieTimer timer = HoodieTimer.start();

    if (reader == null) {
      // No base file at all
      timings.add(timer.endTimer());
      if (fullKeys) {
        // In case full-keys (not key-prefixes) were provided, it's expected that the list of
        // records will contain an (optional) entry for each corresponding key
        return keys.stream()
            .map(key -> Pair.of(key, logRecords.getOrDefault(key, Option.empty())))
            .collect(Collectors.toList());
      } else {
        return logRecords.entrySet().stream()
            .map(entry -> Pair.of(entry.getKey(), entry.getValue()))
            .collect(Collectors.toList());
      }
    }

    HoodieTimer readTimer = HoodieTimer.start();

    Map<String, HoodieRecord<HoodieMetadataPayload>> records =
        fetchBaseFileRecordsByKeys(reader, keys, fullKeys, partitionName);

    metrics.ifPresent(m -> m.updateMetrics(HoodieMetadataMetrics.BASEFILE_READ_STR, readTimer.endTimer()));

    // Iterate over all provided log-records, merging them into existing records
    for (Option<HoodieRecord<HoodieMetadataPayload>> logRecordOpt : logRecords.values()) {
      if (logRecordOpt.isPresent()) {
        HoodieRecord<HoodieMetadataPayload> logRecord = logRecordOpt.get();
        records.merge(
            logRecord.getRecordKey(),
            logRecord,
            (oldRecord, newRecord) ->
                new HoodieAvroRecord<>(oldRecord.getKey(), newRecord.getData().preCombine(oldRecord.getData()))
        );
      }
    }

    timings.add(timer.endTimer());

    if (fullKeys) {
      // In case full-keys (not key-prefixes) were provided, it's expected that the list of
      // records will contain an (optional) entry for each corresponding key
      return keys.stream()
          .map(key -> Pair.of(key, Option.ofNullable(records.get(key))))
          .collect(Collectors.toList());
    } else {
      return records.values().stream()
          .map(record -> Pair.of(record.getRecordKey(), Option.of(record)))
          .collect(Collectors.toList());
    }
  }

  @SuppressWarnings("unchecked")
  private Map<String, HoodieRecord<HoodieMetadataPayload>> fetchBaseFileRecordsByKeys(HoodieSeekingFileReader reader,
                                                                                      List<String> keys,
                                                                                      boolean fullKeys,
                                                                                      String partitionName) throws IOException {
    ClosableIterator<HoodieRecord<?>> records = fullKeys
        ? reader.getRecordsByKeysIterator(keys)
        : reader.getRecordsByKeyPrefixIterator(keys);

    return toStream(records)
        .map(record -> {
          GenericRecord data = (GenericRecord) record.getData();
          return Pair.of(
              (String) (data).get(HoodieMetadataPayload.KEY_FIELD_NAME),
              composeRecord(data, partitionName));
        })
        .collect(Collectors.toMap(Pair::getKey, Pair::getValue));
  }

  private HoodieRecord<HoodieMetadataPayload> composeRecord(GenericRecord avroRecord, String partitionName) {
    if (metadataTableConfig.populateMetaFields()) {
      return SpillableMapUtils.convertToHoodieRecordPayload(avroRecord,
          metadataTableConfig.getPayloadClass(), metadataTableConfig.getPreCombineField(), false);
    }
    return SpillableMapUtils.convertToHoodieRecordPayload(avroRecord,
        metadataTableConfig.getPayloadClass(), metadataTableConfig.getPreCombineField(),
        Pair.of(metadataTableConfig.getRecordKeyFieldProp(), metadataTableConfig.getPartitionFieldProp()),
        false, Option.of(partitionName));
  }

  /**
   * Get the latest file slices for the interested keys in a given partition.
   *
   * @param partitionName - Partition to get the file slices from
   * @param keys          - Interested keys
   * @return FileSlices for the keys
   */
  private Map<Pair<String, FileSlice>, List<String>> getPartitionFileSliceToKeysMapping(final String partitionName, final List<String> keys) {
    // Metadata is in sync till the latest completed instant on the dataset
    List<FileSlice> latestFileSlices =
        HoodieTableMetadataUtil.getPartitionLatestMergedFileSlices(
            metadataMetaClient, metadataFileSystemView, partitionName);

    Map<Pair<String, FileSlice>, List<String>> partitionFileSliceToKeysMap = new HashMap<>();
    for (String key : keys) {
      if (!isNullOrEmpty(latestFileSlices)) {
        final FileSlice slice = latestFileSlices.get(HoodieTableMetadataUtil.mapRecordKeyToFileGroupIndex(key,
            latestFileSlices.size()));
        final Pair<String, FileSlice> partitionNameFileSlicePair = Pair.of(partitionName, slice);
        partitionFileSliceToKeysMap.computeIfAbsent(partitionNameFileSlicePair, k -> new ArrayList<>()).add(key);
      }
    }
    return partitionFileSliceToKeysMap;
  }

  /**
   * Create a file reader and the record scanner for a given partition and file slice
   * if readers are not already available.
   *
   * @param partitionName    - Partition name
   * @param slice            - The file slice to open readers for
   * @return File reader and the record scanner pair for the requested file slice
   */
  private Pair<HoodieSeekingFileReader<?>, HoodieMetadataLogRecordReader> getOrCreateReaders(String partitionName, FileSlice slice) {
    if (reuse) {
      Pair<String, String> key = Pair.of(partitionName, slice.getFileId());
      return partitionReaders.get().computeIfAbsent(key, ignored -> openReaders(partitionName, slice));
    } else {
      return openReaders(partitionName, slice);
    }
  }

  private Pair<HoodieSeekingFileReader<?>, HoodieMetadataLogRecordReader> openReaders(String partitionName, FileSlice slice) {
    try {
      HoodieTimer timer = HoodieTimer.start();
      // Open base file reader
      Pair<HoodieSeekingFileReader<?>, Long> baseFileReaderOpenTimePair = getBaseFileReader(slice, timer);
      HoodieSeekingFileReader<?> baseFileReader = baseFileReaderOpenTimePair.getKey();
      final long baseFileOpenMs = baseFileReaderOpenTimePair.getValue();

      // Open the log record scanner using the log files from the latest file slice
      List<HoodieLogFile> logFiles = slice.getLogFiles().collect(Collectors.toList());
      Pair<HoodieMetadataLogRecordReader, Long> logRecordScannerOpenTimePair =
          getLogRecordScanner(logFiles, partitionName, Option.empty());
      HoodieMetadataLogRecordReader logRecordScanner = logRecordScannerOpenTimePair.getKey();
      final long logScannerOpenMs = logRecordScannerOpenTimePair.getValue();

      metrics.ifPresent(metrics -> metrics.updateMetrics(HoodieMetadataMetrics.SCAN_STR,
          +baseFileOpenMs + logScannerOpenMs));
      return Pair.of(baseFileReader, logRecordScanner);
    } catch (IOException e) {
      throw new HoodieIOException("Error opening readers for metadata table partition " + partitionName, e);
    }
  }

  private Pair<HoodieSeekingFileReader<?>, Long> getBaseFileReader(FileSlice slice, HoodieTimer timer) throws IOException {
    HoodieSeekingFileReader<?> baseFileReader;
    Long baseFileOpenMs;
    // If the base file is present then create a reader
    Option<HoodieBaseFile> basefile = slice.getBaseFile();
    if (basefile.isPresent()) {
      String baseFilePath = basefile.get().getPath();
      baseFileReader = (HoodieSeekingFileReader<?>) HoodieFileReaderFactory.getReaderFactory(HoodieRecordType.AVRO)
          .getFileReader(getHadoopConf(), new Path(baseFilePath));
      baseFileOpenMs = timer.endTimer();
      LOG.info(String.format("Opened metadata base file from %s at instant %s in %d ms", baseFilePath,
          basefile.get().getCommitTime(), baseFileOpenMs));
    } else {
      baseFileReader = null;
      baseFileOpenMs = 0L;
      timer.endTimer();
    }
    return Pair.of(baseFileReader, baseFileOpenMs);
  }

  private Set<String> getValidInstantTimestamps() {
    // Only those log files which have a corresponding completed instant on the dataset should be read
    // This is because the metadata table is updated before the dataset instants are committed.
    HoodieActiveTimeline datasetTimeline = dataMetaClient.getActiveTimeline();
    Set<String> validInstantTimestamps = datasetTimeline.filterCompletedInstants().getInstantsAsStream()
        .map(HoodieInstant::getTimestamp).collect(Collectors.toSet());

    // For any rollbacks and restores, we cannot neglect the instants that they are rolling back.
    // The rollback instant should be more recent than the start of the timeline for it to have rolled back any
    // instant which we have a log block for.
    final String earliestInstantTime = validInstantTimestamps.isEmpty() ? SOLO_COMMIT_TIMESTAMP : Collections.min(validInstantTimestamps);
    datasetTimeline.getRollbackAndRestoreTimeline().filterCompletedInstants().getInstantsAsStream()
        .filter(instant -> HoodieTimeline.compareTimestamps(instant.getTimestamp(), HoodieTimeline.GREATER_THAN, earliestInstantTime))
        .forEach(instant -> {
          validInstantTimestamps.addAll(getRollbackedCommits(instant, datasetTimeline));
        });

    // SOLO_COMMIT_TIMESTAMP is used during bootstrap so it is a valid timestamp
    validInstantTimestamps.add(SOLO_COMMIT_TIMESTAMP);
    return validInstantTimestamps;
  }

  public Pair<HoodieMetadataLogRecordReader, Long> getLogRecordScanner(List<HoodieLogFile> logFiles,
                                                                       String partitionName,
                                                                       Option<Boolean> allowFullScanOverride) {
    HoodieTimer timer = HoodieTimer.start();
    List<String> sortedLogFilePaths = logFiles.stream()
        .sorted(HoodieLogFile.getLogFileComparator())
        .map(o -> o.getPath().toString())
        .collect(Collectors.toList());

    // Only those log files which have a corresponding completed instant on the dataset should be read
    // This is because the metadata table is updated before the dataset instants are committed.
    Set<String> validInstantTimestamps = getValidInstantTimestamps();

    Option<HoodieInstant> latestMetadataInstant = metadataMetaClient.getActiveTimeline().filterCompletedInstants().lastInstant();
    String latestMetadataInstantTime = latestMetadataInstant.map(HoodieInstant::getTimestamp).orElse(SOLO_COMMIT_TIMESTAMP);

    boolean allowFullScan = allowFullScanOverride.orElseGet(() -> isFullScanAllowedForPartition(partitionName));

    // Load the schema
    Schema schema = HoodieAvroUtils.addMetadataFields(HoodieMetadataRecord.getClassSchema());
    HoodieCommonConfig commonConfig = HoodieCommonConfig.newBuilder().fromProperties(metadataConfig.getProps()).build();
    HoodieMetadataLogRecordReader logRecordScanner = HoodieMetadataLogRecordReader.newBuilder()
        .withFileSystem(metadataMetaClient.getFs())
        .withBasePath(metadataBasePath)
        .withLogFilePaths(sortedLogFilePaths)
        .withReaderSchema(schema)
        .withLatestInstantTime(latestMetadataInstantTime)
        .withMaxMemorySizeInBytes(MAX_MEMORY_SIZE_IN_BYTES)
        .withBufferSize(BUFFER_SIZE)
        .withSpillableMapBasePath(spillableMapDirectory)
        .withDiskMapType(commonConfig.getSpillableDiskMapType())
        .withBitCaskDiskMapCompressionEnabled(commonConfig.isBitCaskDiskMapCompressionEnabled())
        .withLogBlockTimestamps(validInstantTimestamps)
        .enableFullScan(allowFullScan)
        .withPartition(partitionName)
        .withUseScanV2(metadataConfig.getUseLogRecordReaderScanV2())
        .build();

    Long logScannerOpenMs = timer.endTimer();
    LOG.info(String.format("Opened %d metadata log files (dataset instant=%s, metadata instant=%s) in %d ms",
        sortedLogFilePaths.size(), getLatestDataInstantTime(), latestMetadataInstantTime, logScannerOpenMs));
    return Pair.of(logRecordScanner, logScannerOpenMs);
  }

  // NOTE: We're allowing eager full-scan of the log-files only for "files" partition.
  //       Other partitions (like "column_stats", "bloom_filters") will have to be fetched
  //       t/h point-lookups
  private boolean isFullScanAllowedForPartition(String partitionName) {
    switch (partitionName) {
      case PARTITION_NAME_FILES:
        return HoodieMetadataConfig.ENABLE_FULL_SCAN_LOG_FILES.defaultValue();

      case PARTITION_NAME_COLUMN_STATS:
      case PARTITION_NAME_BLOOM_FILTERS:
      default:
        return false;
    }
  }

  /**
   * Returns a list of commits which were rolled back as part of a Rollback or Restore operation.
   *
   * @param instant  The Rollback operation to read
   * @param timeline instant of timeline from dataset.
   */
  private List<String> getRollbackedCommits(HoodieInstant instant, HoodieActiveTimeline timeline) {
    try {
      if (instant.getAction().equals(HoodieTimeline.ROLLBACK_ACTION)) {
        HoodieRollbackMetadata rollbackMetadata = TimelineMetadataUtils.deserializeHoodieRollbackMetadata(
            timeline.getInstantDetails(instant).get());
        return rollbackMetadata.getCommitsRollback();
      }

      List<String> rollbackedCommits = new LinkedList<>();
      if (instant.getAction().equals(HoodieTimeline.RESTORE_ACTION)) {
        // Restore is made up of several rollbacks
        HoodieRestoreMetadata restoreMetadata = TimelineMetadataUtils.deserializeHoodieRestoreMetadata(
            timeline.getInstantDetails(instant).get());
        restoreMetadata.getHoodieRestoreMetadata().values().forEach(rms -> {
          rms.forEach(rm -> rollbackedCommits.addAll(rm.getCommitsRollback()));
        });
      }
      return rollbackedCommits;
    } catch (IOException e) {
      throw new HoodieMetadataException("Error retrieving rollback commits for instant " + instant, e);
    }
  }

  @Override
  public void close() {
    closePartitionReaders();
  }

  /**
   * Close the file reader and the record scanner for the given file slice.
   *
   * @param partitionFileSlicePair - Partition and FileSlice
   */
  private synchronized void close(Pair<String, String> partitionFileSlicePair) {
    Pair<HoodieSeekingFileReader<?>, HoodieMetadataLogRecordReader> readers =
        partitionReaders.get().remove(partitionFileSlicePair);
    closeReader(readers);
  }

  /**
   * Close and clear all the partitions readers.
   */
  private void closePartitionReaders() {
    for (Pair<String, String> partitionFileSlicePair : partitionReaders.get().keySet()) {
      close(partitionFileSlicePair);
    }
    partitionReaders.get().clear();
  }

  private void closeReader(Pair<HoodieSeekingFileReader<?>, HoodieMetadataLogRecordReader> readers) {
    if (readers != null) {
      try {
        if (readers.getKey() != null) {
          readers.getKey().close();
        }
        if (readers.getValue() != null) {
          readers.getValue().close();
        }
      } catch (Exception e) {
        throw new HoodieException("Error closing resources during metadata table merge", e);
      }
    }
  }

  public boolean enabled() {
    return isMetadataTableEnabled;
  }

  public HoodieTableMetaClient getMetadataMetaClient() {
    return metadataMetaClient;
  }

  public Map<String, String> stats() {
    return metrics.map(m -> m.getStats(true, metadataMetaClient, this)).orElse(new HashMap<>());
  }

  @Override
  public Option<String> getSyncedInstantTime() {
    if (metadataMetaClient != null) {
      Option<HoodieInstant> latestInstant = metadataMetaClient.getActiveTimeline().getDeltaCommitTimeline().filterCompletedInstants().lastInstant();
      if (latestInstant.isPresent()) {
        return Option.of(latestInstant.get().getTimestamp());
      }
    }
    return Option.empty();
  }

  @Override
  public Option<String> getLatestCompactionTime() {
    if (metadataMetaClient != null) {
      Option<HoodieInstant> latestCompaction = metadataMetaClient.getActiveTimeline().getCommitTimeline().filterCompletedInstants().lastInstant();
      if (latestCompaction.isPresent()) {
        return Option.of(latestCompaction.get().getTimestamp());
      }
    }
    return Option.empty();
  }

  @Override
  public void reset() {
    initIfNeeded();
    dataMetaClient.reloadActiveTimeline();
    if (metadataMetaClient != null) {
      metadataMetaClient.reloadActiveTimeline();
      metadataFileSystemView = getFileSystemView(metadataMetaClient);
    }
    // the cached reader has max instant time restriction, they should be cleared
    // because the metadata timeline may have changed.
    closePartitionReaders();
  }
}
