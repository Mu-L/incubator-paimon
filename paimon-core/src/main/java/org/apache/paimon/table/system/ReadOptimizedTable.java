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

package org.apache.paimon.table.system;

import org.apache.paimon.CoreOptions;
import org.apache.paimon.Snapshot;
import org.apache.paimon.consumer.ConsumerManager;
import org.apache.paimon.fs.FileIO;
import org.apache.paimon.fs.Path;
import org.apache.paimon.manifest.IndexManifestEntry;
import org.apache.paimon.manifest.ManifestEntry;
import org.apache.paimon.manifest.ManifestFileMeta;
import org.apache.paimon.schema.SchemaManager;
import org.apache.paimon.table.DataTable;
import org.apache.paimon.table.FallbackReadFileStoreTable;
import org.apache.paimon.table.FallbackReadFileStoreTable.FallbackReadScan;
import org.apache.paimon.table.FileStoreTable;
import org.apache.paimon.table.ReadonlyTable;
import org.apache.paimon.table.Table;
import org.apache.paimon.table.source.DataTableBatchScan;
import org.apache.paimon.table.source.DataTableScan;
import org.apache.paimon.table.source.DataTableStreamScan;
import org.apache.paimon.table.source.InnerTableRead;
import org.apache.paimon.table.source.StreamDataTableScan;
import org.apache.paimon.table.source.snapshot.SnapshotReader;
import org.apache.paimon.types.RowType;
import org.apache.paimon.utils.BranchManager;
import org.apache.paimon.utils.ChangelogManager;
import org.apache.paimon.utils.SimpleFileReader;
import org.apache.paimon.utils.SnapshotManager;
import org.apache.paimon.utils.TagManager;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.apache.paimon.catalog.Identifier.SYSTEM_TABLE_SPLITTER;

/**
 * A {@link Table} optimized for reading by avoiding merging files.
 *
 * <ul>
 *   <li>For primary key tables, this system table only scans files on top level.
 *   <li>For append only tables, as all files can be read without merging, this system table does
 *       nothing special.
 * </ul>
 */
public class ReadOptimizedTable implements DataTable, ReadonlyTable {

    public static final String READ_OPTIMIZED = "ro";

    private final FileStoreTable wrapped;

    public ReadOptimizedTable(FileStoreTable wrapped) {
        this.wrapped = wrapped;
    }

    @Override
    public Optional<Snapshot> latestSnapshot() {
        return wrapped.latestSnapshot();
    }

    @Override
    public Snapshot snapshot(long snapshotId) {
        return wrapped.snapshot(snapshotId);
    }

    @Override
    public SimpleFileReader<ManifestFileMeta> manifestListReader() {
        return wrapped.manifestListReader();
    }

    @Override
    public SimpleFileReader<ManifestEntry> manifestFileReader() {
        return wrapped.manifestFileReader();
    }

    @Override
    public SimpleFileReader<IndexManifestEntry> indexManifestFileReader() {
        return wrapped.indexManifestFileReader();
    }

    @Override
    public String name() {
        return wrapped.name() + SYSTEM_TABLE_SPLITTER + READ_OPTIMIZED;
    }

    @Override
    public RowType rowType() {
        return wrapped.rowType();
    }

    @Override
    public List<String> partitionKeys() {
        return wrapped.partitionKeys();
    }

    @Override
    public Map<String, String> options() {
        return wrapped.options();
    }

    @Override
    public List<String> primaryKeys() {
        return wrapped.primaryKeys();
    }

    @Override
    public SnapshotReader newSnapshotReader() {
        return newSnapshotReader(wrapped);
    }

    private SnapshotReader newSnapshotReader(FileStoreTable wrapped) {
        if (!wrapped.schema().primaryKeys().isEmpty()) {
            return wrapped.newSnapshotReader()
                    .withLevel(coreOptions().numLevels() - 1)
                    .enableValueFilter();
        } else {
            return wrapped.newSnapshotReader();
        }
    }

    @Override
    public DataTableScan newScan() {
        if (wrapped instanceof FallbackReadFileStoreTable) {
            FallbackReadFileStoreTable table = (FallbackReadFileStoreTable) wrapped;
            return new FallbackReadScan(newScan(table.wrapped()), newScan(table.fallback()));
        }
        return newScan(wrapped);
    }

    private DataTableScan newScan(FileStoreTable wrapped) {
        CoreOptions options = wrapped.coreOptions();
        return new DataTableBatchScan(
                wrapped.schema(),
                options,
                newSnapshotReader(wrapped),
                wrapped.catalogEnvironment().tableQueryAuth(options));
    }

    @Override
    public StreamDataTableScan newStreamScan() {
        if (!wrapped.schema().primaryKeys().isEmpty()) {
            throw new UnsupportedOperationException(
                    "Unsupported streaming scan for read optimized table");
        }
        return new DataTableStreamScan(
                wrapped.schema(),
                coreOptions(),
                newSnapshotReader(),
                snapshotManager(),
                changelogManager(),
                wrapped.supportStreamingReadOverwrite(),
                wrapped.catalogEnvironment().tableQueryAuth(coreOptions()),
                !wrapped.schema().primaryKeys().isEmpty());
    }

    @Override
    public CoreOptions coreOptions() {
        return wrapped.coreOptions();
    }

    @Override
    public Path location() {
        return wrapped.location();
    }

    @Override
    public SnapshotManager snapshotManager() {
        return wrapped.snapshotManager();
    }

    @Override
    public ChangelogManager changelogManager() {
        return wrapped.changelogManager();
    }

    @Override
    public ConsumerManager consumerManager() {
        return wrapped.consumerManager();
    }

    @Override
    public SchemaManager schemaManager() {
        return wrapped.schemaManager();
    }

    @Override
    public TagManager tagManager() {
        return wrapped.tagManager();
    }

    @Override
    public BranchManager branchManager() {
        return wrapped.branchManager();
    }

    @Override
    public DataTable switchToBranch(String branchName) {
        return new ReadOptimizedTable(wrapped.switchToBranch(branchName));
    }

    @Override
    public InnerTableRead newRead() {
        return wrapped.newRead();
    }

    @Override
    public Table copy(Map<String, String> dynamicOptions) {
        return new ReadOptimizedTable(wrapped.copy(dynamicOptions));
    }

    @Override
    public FileIO fileIO() {
        return wrapped.fileIO();
    }
}
