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

package org.apache.paimon;

import org.apache.paimon.codegen.RecordEqualiser;
import org.apache.paimon.data.InternalRow;
import org.apache.paimon.deletionvectors.DeletionVectorsMaintainer;
import org.apache.paimon.format.FileFormatDiscover;
import org.apache.paimon.fs.FileIO;
import org.apache.paimon.index.DynamicBucketIndexMaintainer;
import org.apache.paimon.io.KeyValueFileReaderFactory;
import org.apache.paimon.mergetree.compact.MergeFunctionFactory;
import org.apache.paimon.operation.AbstractFileStoreWrite;
import org.apache.paimon.operation.BucketSelectConverter;
import org.apache.paimon.operation.KeyValueFileStoreScan;
import org.apache.paimon.operation.KeyValueFileStoreWrite;
import org.apache.paimon.operation.MergeFileSplitRead;
import org.apache.paimon.operation.RawFileSplitRead;
import org.apache.paimon.postpone.PostponeBucketFileStoreWrite;
import org.apache.paimon.predicate.Predicate;
import org.apache.paimon.schema.KeyValueFieldsExtractor;
import org.apache.paimon.schema.SchemaManager;
import org.apache.paimon.schema.TableSchema;
import org.apache.paimon.table.BucketMode;
import org.apache.paimon.table.CatalogEnvironment;
import org.apache.paimon.types.RowType;
import org.apache.paimon.utils.KeyComparatorSupplier;
import org.apache.paimon.utils.UserDefinedSeqComparator;
import org.apache.paimon.utils.ValueEqualiserSupplier;

import javax.annotation.Nullable;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import static org.apache.paimon.predicate.PredicateBuilder.and;
import static org.apache.paimon.predicate.PredicateBuilder.pickTransformFieldMapping;
import static org.apache.paimon.predicate.PredicateBuilder.splitAnd;
import static org.apache.paimon.utils.Preconditions.checkArgument;

/** {@link FileStore} for querying and updating {@link KeyValue}s. */
public class KeyValueFileStore extends AbstractFileStore<KeyValue> {

    private final boolean crossPartitionUpdate;
    private final RowType bucketKeyType;
    private final RowType keyType;
    private final RowType valueType;
    private final KeyValueFieldsExtractor keyValueFieldsExtractor;
    private final Supplier<Comparator<InternalRow>> keyComparatorSupplier;
    private final Supplier<RecordEqualiser> logDedupEqualSupplier;
    private final MergeFunctionFactory<KeyValue> mfFactory;

    public KeyValueFileStore(
            FileIO fileIO,
            SchemaManager schemaManager,
            TableSchema schema,
            boolean crossPartitionUpdate,
            CoreOptions options,
            RowType partitionType,
            RowType bucketKeyType,
            RowType keyType,
            RowType valueType,
            KeyValueFieldsExtractor keyValueFieldsExtractor,
            MergeFunctionFactory<KeyValue> mfFactory,
            String tableName,
            CatalogEnvironment catalogEnvironment) {
        super(fileIO, schemaManager, schema, tableName, options, partitionType, catalogEnvironment);
        this.crossPartitionUpdate = crossPartitionUpdate;
        this.bucketKeyType = bucketKeyType;
        this.keyType = keyType;
        this.valueType = valueType;
        this.keyValueFieldsExtractor = keyValueFieldsExtractor;
        this.mfFactory = mfFactory;
        this.keyComparatorSupplier = new KeyComparatorSupplier(keyType);
        List<String> logDedupIgnoreFields = options.changelogRowDeduplicateIgnoreFields();
        this.logDedupEqualSupplier =
                options.changelogRowDeduplicate()
                        ? ValueEqualiserSupplier.fromIgnoreFields(valueType, logDedupIgnoreFields)
                        : () -> null;
    }

    @Override
    public BucketMode bucketMode() {
        int bucket = options.bucket();
        switch (bucket) {
            case -2:
                return BucketMode.POSTPONE_MODE;
            case -1:
                return crossPartitionUpdate ? BucketMode.CROSS_PARTITION : BucketMode.HASH_DYNAMIC;
            default:
                checkArgument(!crossPartitionUpdate);
                return BucketMode.HASH_FIXED;
        }
    }

    @Override
    public MergeFileSplitRead newRead() {
        return new MergeFileSplitRead(
                options,
                schema,
                keyType,
                valueType,
                newKeyComparator(),
                mfFactory,
                newReaderFactoryBuilder());
    }

    public RawFileSplitRead newBatchRawFileRead() {
        return new RawFileSplitRead(
                fileIO,
                schemaManager,
                schema,
                valueType,
                FileFormatDiscover.of(options),
                pathFactory(),
                options.fileIndexReadEnabled(),
                false);
    }

    public KeyValueFileReaderFactory.Builder newReaderFactoryBuilder() {
        return KeyValueFileReaderFactory.builder(
                fileIO,
                schemaManager,
                schema,
                keyType,
                valueType,
                FileFormatDiscover.of(options),
                pathFactory(),
                keyValueFieldsExtractor,
                options);
    }

    @Override
    public AbstractFileStoreWrite<KeyValue> newWrite(String commitUser) {
        return newWrite(commitUser, null);
    }

    @Override
    public AbstractFileStoreWrite<KeyValue> newWrite(String commitUser, @Nullable Integer writeId) {
        if (options.bucket() == BucketMode.POSTPONE_BUCKET) {
            return new PostponeBucketFileStoreWrite(
                    fileIO,
                    pathFactory(),
                    schema,
                    commitUser,
                    partitionType,
                    keyType,
                    valueType,
                    mfFactory,
                    this::pathFactory,
                    newReaderFactoryBuilder(),
                    snapshotManager(),
                    newScan(),
                    options,
                    tableName,
                    writeId);
        }
        DynamicBucketIndexMaintainer.Factory indexFactory = null;
        if (bucketMode() == BucketMode.HASH_DYNAMIC) {
            indexFactory = new DynamicBucketIndexMaintainer.Factory(newIndexFileHandler());
        }
        DeletionVectorsMaintainer.Factory deletionVectorsMaintainerFactory = null;
        if (options.deletionVectorsEnabled()) {
            deletionVectorsMaintainerFactory =
                    new DeletionVectorsMaintainer.Factory(newIndexFileHandler());
        }
        return new KeyValueFileStoreWrite(
                fileIO,
                schemaManager,
                schema,
                commitUser,
                partitionType,
                keyType,
                valueType,
                keyComparatorSupplier,
                () -> UserDefinedSeqComparator.create(valueType, options),
                logDedupEqualSupplier,
                mfFactory,
                pathFactory(),
                this::pathFactory,
                snapshotManager(),
                newScan(),
                indexFactory,
                deletionVectorsMaintainerFactory,
                options,
                keyValueFieldsExtractor,
                tableName);
    }

    @Override
    public KeyValueFileStoreScan newScan() {
        BucketMode bucketMode = bucketMode();
        BucketSelectConverter bucketSelectConverter =
                keyFilter -> {
                    if (bucketMode != BucketMode.HASH_FIXED
                            && bucketMode != BucketMode.POSTPONE_MODE) {
                        return Optional.empty();
                    }

                    List<Predicate> bucketFilters =
                            pickTransformFieldMapping(
                                    splitAnd(keyFilter),
                                    keyType.getFieldNames(),
                                    bucketKeyType.getFieldNames());
                    if (!bucketFilters.isEmpty()) {
                        return BucketSelectConverter.create(
                                and(bucketFilters), bucketKeyType, options.bucketFunctionType());
                    }
                    return Optional.empty();
                };

        return new KeyValueFileStoreScan(
                newManifestsReader(),
                bucketSelectConverter,
                snapshotManager(),
                schemaManager,
                schema,
                keyValueFieldsExtractor,
                manifestFileFactory(),
                options.scanManifestParallelism(),
                options.deletionVectorsEnabled(),
                options.mergeEngine(),
                options.changelogProducer(),
                options.fileIndexReadEnabled() && options.deletionVectorsEnabled());
    }

    @Override
    public Comparator<InternalRow> newKeyComparator() {
        return keyComparatorSupplier.get();
    }
}
