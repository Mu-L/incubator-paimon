/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.paimon.table.source;

import org.apache.paimon.CoreOptions;
import org.apache.paimon.CoreOptions.StreamScanMode;
import org.apache.paimon.Snapshot;
import org.apache.paimon.consumer.Consumer;
import org.apache.paimon.manifest.PartitionEntry;
import org.apache.paimon.predicate.Predicate;
import org.apache.paimon.schema.TableSchema;
import org.apache.paimon.table.BucketMode;
import org.apache.paimon.table.source.snapshot.AllDeltaFollowUpScanner;
import org.apache.paimon.table.source.snapshot.BoundedChecker;
import org.apache.paimon.table.source.snapshot.ChangelogFollowUpScanner;
import org.apache.paimon.table.source.snapshot.DeltaFollowUpScanner;
import org.apache.paimon.table.source.snapshot.FollowUpScanner;
import org.apache.paimon.table.source.snapshot.SnapshotReader;
import org.apache.paimon.table.source.snapshot.StartingContext;
import org.apache.paimon.table.source.snapshot.StartingScanner;
import org.apache.paimon.table.source.snapshot.StartingScanner.ScannedResult;
import org.apache.paimon.table.source.snapshot.StaticFromSnapshotStartingScanner;
import org.apache.paimon.utils.ChangelogManager;
import org.apache.paimon.utils.Filter;
import org.apache.paimon.utils.NextSnapshotFetcher;
import org.apache.paimon.utils.SnapshotManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;

import java.util.List;

import static org.apache.paimon.CoreOptions.ChangelogProducer.FULL_COMPACTION;
import static org.apache.paimon.CoreOptions.ChangelogProducer.LOOKUP;
import static org.apache.paimon.CoreOptions.StreamScanMode.FILE_MONITOR;

/** {@link StreamTableScan} implementation for streaming planning. */
public class DataTableStreamScan extends AbstractDataTableScan implements StreamDataTableScan {

    private static final Logger LOG = LoggerFactory.getLogger(DataTableStreamScan.class);

    private final CoreOptions options;
    private final StreamScanMode scanMode;
    private final SnapshotManager snapshotManager;
    private final boolean supportStreamingReadOverwrite;
    private final NextSnapshotFetcher nextSnapshotProvider;
    private final boolean hasPk;

    private boolean initialized = false;
    private StartingScanner startingScanner;
    private FollowUpScanner followUpScanner;
    private BoundedChecker boundedChecker;

    private boolean isFullPhaseEnd = false;
    @Nullable private Long currentWatermark;
    @Nullable private Long nextSnapshotId;

    @Nullable private Long scanDelayMillis;

    public DataTableStreamScan(
            TableSchema schema,
            CoreOptions options,
            SnapshotReader snapshotReader,
            SnapshotManager snapshotManager,
            ChangelogManager changelogManager,
            boolean supportStreamingReadOverwrite,
            TableQueryAuth queryAuth,
            boolean hasPk) {
        super(schema, options, snapshotReader, queryAuth);

        this.options = options;
        this.scanMode = options.toConfiguration().get(CoreOptions.STREAM_SCAN_MODE);
        this.snapshotManager = snapshotManager;
        this.supportStreamingReadOverwrite = supportStreamingReadOverwrite;
        this.nextSnapshotProvider =
                new NextSnapshotFetcher(
                        snapshotManager, changelogManager, options.changelogLifecycleDecoupled());
        this.hasPk = hasPk;

        if (options.bucket() == BucketMode.POSTPONE_BUCKET
                && options.changelogProducer() != CoreOptions.ChangelogProducer.NONE) {
            snapshotReader.onlyReadRealBuckets();
        }
    }

    @Override
    public DataTableStreamScan withFilter(Predicate predicate) {
        super.withFilter(predicate);
        snapshotReader.withFilter(predicate);
        return this;
    }

    @Override
    public StartingContext startingContext() {
        if (!initialized) {
            initScanner();
        }
        return startingScanner.startingContext();
    }

    @Override
    public Plan plan() {
        authQuery();

        if (!initialized) {
            initScanner();
        }

        if (nextSnapshotId == null) {
            return tryFirstPlan();
        } else {
            return nextPlan();
        }
    }

    @Override
    public List<PartitionEntry> listPartitionEntries() {
        throw new UnsupportedOperationException(
                "List Partition Entries is not supported in Stream Scan.");
    }

    private void initScanner() {
        if (startingScanner == null) {
            startingScanner = createStartingScanner(true);
        }
        if (followUpScanner == null) {
            followUpScanner = createFollowUpScanner();
        }
        if (boundedChecker == null) {
            boundedChecker = createBoundedChecker();
        }
        if (scanDelayMillis == null) {
            scanDelayMillis = getScanDelayMillis();
        }
        initialized = true;
    }

    private Plan tryFirstPlan() {
        StartingScanner.Result result;
        if (scanMode == FILE_MONITOR) {
            result = startingScanner.scan(snapshotReader);
        } else if (options.changelogProducer().equals(LOOKUP)) {
            // level0 data will be compacted to produce changelog in the future
            result = startingScanner.scan(snapshotReader.withLevelFilter(level -> level > 0));
            snapshotReader.withLevelFilter(Filter.alwaysTrue());
        } else if (options.changelogProducer().equals(FULL_COMPACTION)) {
            result =
                    startingScanner.scan(
                            snapshotReader.withLevelFilter(
                                    level -> level == options.numLevels() - 1));
            snapshotReader.withLevelFilter(Filter.alwaysTrue());
        } else {
            result = startingScanner.scan(snapshotReader);
        }

        if (result instanceof ScannedResult) {
            ScannedResult scannedResult = (ScannedResult) result;
            currentWatermark = scannedResult.currentWatermark();
            long currentSnapshotId = scannedResult.currentSnapshotId();
            nextSnapshotId = currentSnapshotId + 1;
            isFullPhaseEnd =
                    boundedChecker.shouldEndInput(snapshotManager.snapshot(currentSnapshotId));
            LOG.debug(
                    "Starting snapshot is {}, next snapshot will be {}.",
                    scannedResult.plan().snapshotId(),
                    nextSnapshotId);
            return scannedResult.plan();
        } else if (result instanceof StartingScanner.NextSnapshot) {
            nextSnapshotId = ((StartingScanner.NextSnapshot) result).nextSnapshotId();
            isFullPhaseEnd =
                    snapshotManager.snapshotExists(nextSnapshotId - 1)
                            && boundedChecker.shouldEndInput(
                                    snapshotManager.snapshot(nextSnapshotId - 1));
            LOG.debug("There is no starting snapshot. Next snapshot will be {}.", nextSnapshotId);
        } else if (result instanceof StartingScanner.NoSnapshot) {
            LOG.debug("There is no starting snapshot and currently there is no next snapshot.");
        }
        return SnapshotNotExistPlan.INSTANCE;
    }

    private Plan nextPlan() {
        while (true) {
            if (isFullPhaseEnd) {
                throw new EndOfScanException();
            }

            Snapshot snapshot = nextSnapshotProvider.getNextSnapshot(nextSnapshotId);
            if (snapshot == null) {
                return SnapshotNotExistPlan.INSTANCE;
            }

            if (boundedChecker.shouldEndInput(snapshot)) {
                throw new EndOfScanException();
            }

            if (shouldDelaySnapshot(snapshot)) {
                return SnapshotNotExistPlan.INSTANCE;
            }

            // first try to get overwrite changes
            if (snapshot.commitKind() == Snapshot.CommitKind.OVERWRITE) {
                SnapshotReader.Plan overwritePlan = handleOverwriteSnapshot(snapshot);
                if (overwritePlan != null) {
                    nextSnapshotId++;
                    if (overwritePlan.splits().isEmpty()) {
                        continue;
                    }
                    return overwritePlan;
                }
            }

            if (followUpScanner.shouldScanSnapshot(snapshot)) {
                LOG.debug("Find snapshot id {}.", nextSnapshotId);
                SnapshotReader.Plan plan = followUpScanner.scan(snapshot, snapshotReader);
                currentWatermark = plan.watermark();
                nextSnapshotId++;
                if (plan.splits().isEmpty()) {
                    continue;
                }
                return plan;
            } else {
                nextSnapshotId++;
            }
        }
    }

    private boolean shouldDelaySnapshot(Snapshot snapshot) {
        if (scanDelayMillis == null) {
            return false;
        }

        long snapshotMills = System.currentTimeMillis() - scanDelayMillis;
        return snapshot.timeMillis() > snapshotMills;
    }

    @Nullable
    protected SnapshotReader.Plan handleOverwriteSnapshot(Snapshot snapshot) {
        if (supportStreamingReadOverwrite) {
            LOG.debug("Find overwrite snapshot id {}.", nextSnapshotId);
            SnapshotReader.Plan overwritePlan =
                    followUpScanner.getOverwriteChangesPlan(snapshot, snapshotReader, !hasPk);
            currentWatermark = overwritePlan.watermark();
            return overwritePlan;
        }
        return null;
    }

    protected FollowUpScanner createFollowUpScanner() {
        switch (scanMode) {
            case COMPACT_BUCKET_TABLE:
                return new DeltaFollowUpScanner();
            case FILE_MONITOR:
                return new AllDeltaFollowUpScanner();
        }

        CoreOptions.ChangelogProducer changelogProducer = options.changelogProducer();
        FollowUpScanner followUpScanner;
        switch (changelogProducer) {
            case NONE:
                followUpScanner = new DeltaFollowUpScanner();
                break;
            case INPUT:
            case FULL_COMPACTION:
            case LOOKUP:
                followUpScanner = new ChangelogFollowUpScanner();
                break;
            default:
                throw new UnsupportedOperationException(
                        "Unknown changelog producer " + changelogProducer.name());
        }
        return followUpScanner;
    }

    protected BoundedChecker createBoundedChecker() {
        Long boundedWatermark = options.scanBoundedWatermark();
        return boundedWatermark != null
                ? BoundedChecker.watermark(boundedWatermark)
                : BoundedChecker.neverEnd();
    }

    private Long getScanDelayMillis() {
        return options.streamingReadDelay() == null
                ? null
                : options.streamingReadDelay().toMillis();
    }

    @Nullable
    @Override
    public Long checkpoint() {
        return nextSnapshotId;
    }

    @Nullable
    @Override
    public Long watermark() {
        return currentWatermark;
    }

    @Override
    public void restore(@Nullable Long nextSnapshotId) {
        this.nextSnapshotId = nextSnapshotId;
    }

    @Override
    public void restore(@Nullable Long nextSnapshotId, boolean scanAllSnapshot) {
        if (nextSnapshotId != null && scanAllSnapshot) {
            startingScanner =
                    new StaticFromSnapshotStartingScanner(snapshotManager, nextSnapshotId);
            restore(null);
        } else {
            restore(nextSnapshotId);
        }
    }

    @Override
    public void notifyCheckpointComplete(@Nullable Long nextSnapshot) {
        if (nextSnapshot == null) {
            return;
        }

        String consumerId = options.consumerId();
        if (consumerId != null) {
            snapshotReader.consumerManager().resetConsumer(consumerId, new Consumer(nextSnapshot));
        }
    }

    @Override
    public DataTableScan withShard(int indexOfThisSubtask, int numberOfParallelSubtasks) {
        snapshotReader.withShard(indexOfThisSubtask, numberOfParallelSubtasks);
        return this;
    }
}
