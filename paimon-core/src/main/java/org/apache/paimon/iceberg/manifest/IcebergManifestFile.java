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

package org.apache.paimon.iceberg.manifest;

import org.apache.paimon.annotation.VisibleForTesting;
import org.apache.paimon.data.InternalRow;
import org.apache.paimon.format.FileFormat;
import org.apache.paimon.format.FormatReaderFactory;
import org.apache.paimon.format.FormatWriterFactory;
import org.apache.paimon.format.SimpleColStats;
import org.apache.paimon.format.SimpleStatsCollector;
import org.apache.paimon.fs.FileIO;
import org.apache.paimon.fs.Path;
import org.apache.paimon.iceberg.IcebergOptions;
import org.apache.paimon.iceberg.IcebergPathFactory;
import org.apache.paimon.iceberg.manifest.IcebergManifestFileMeta.Content;
import org.apache.paimon.iceberg.metadata.IcebergPartitionSpec;
import org.apache.paimon.io.RollingFileWriter;
import org.apache.paimon.io.SingleFileWriter;
import org.apache.paimon.manifest.ManifestEntry;
import org.apache.paimon.options.MemorySize;
import org.apache.paimon.options.Options;
import org.apache.paimon.table.FileStoreTable;
import org.apache.paimon.types.DataType;
import org.apache.paimon.types.RowType;
import org.apache.paimon.utils.CloseableIterator;
import org.apache.paimon.utils.FileUtils;
import org.apache.paimon.utils.Filter;
import org.apache.paimon.utils.ObjectsFile;
import org.apache.paimon.utils.PathFactory;

import javax.annotation.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;

import static org.apache.paimon.iceberg.manifest.IcebergConversions.toByteBuffer;

/**
 * This file includes several Iceberg {@link ManifestEntry}s, representing the additional changes
 * since last snapshot.
 */
public class IcebergManifestFile extends ObjectsFile<IcebergManifestEntry> {

    private static final long UNASSIGNED_SEQ = -1L;

    private final RowType partitionType;
    private final FormatWriterFactory writerFactory;
    private final MemorySize targetFileSize;

    public IcebergManifestFile(
            FileIO fileIO,
            RowType partitionType,
            FormatReaderFactory readerFactory,
            FormatWriterFactory writerFactory,
            String compression,
            PathFactory pathFactory,
            MemorySize targetFileSize) {
        super(
                fileIO,
                new IcebergManifestEntrySerializer(partitionType),
                IcebergManifestEntry.schema(partitionType),
                readerFactory,
                writerFactory,
                compression,
                pathFactory,
                null);
        this.partitionType = partitionType;
        this.writerFactory = writerFactory;
        this.targetFileSize = targetFileSize;
    }

    @VisibleForTesting
    public String compression() {
        return compression;
    }

    public static IcebergManifestFile create(FileStoreTable table, IcebergPathFactory pathFactory) {
        RowType partitionType = table.schema().logicalPartitionType();
        RowType entryType = IcebergManifestEntry.schema(partitionType);
        Options avroOptions = Options.fromMap(table.options());
        // https://github.com/apache/iceberg/blob/main/core/src/main/java/org/apache/iceberg/ManifestReader.java
        avroOptions.set(
                "avro.row-name-mapping",
                "org.apache.paimon.avro.generated.record:manifest_entry,"
                        + "iceberg:true,"
                        + "manifest_entry_data_file:r2,"
                        + "r2_partition:r102,"
                        + "kv_name_r2_null_value_counts:k121_v122,"
                        + "k_id_k121_v122:121,"
                        + "v_id_k121_v122:122,"
                        + "kv_name_r2_lower_bounds:k126_v127,"
                        + "k_id_k126_v127:126,"
                        + "v_id_k126_v127:127,"
                        + "kv_name_r2_upper_bounds:k129_v130,"
                        + "k_id_k129_v130:129,"
                        + "v_id_k129_v130:130");
        FileFormat manifestFileAvro = FileFormat.fromIdentifier("avro", avroOptions);
        return new IcebergManifestFile(
                table.fileIO(),
                partitionType,
                manifestFileAvro.createReaderFactory(entryType),
                manifestFileAvro.createWriterFactory(entryType),
                avroOptions.get(IcebergOptions.MANIFEST_COMPRESSION),
                pathFactory.manifestFileFactory(),
                table.coreOptions().manifestTargetSize());
    }

    public List<IcebergManifestEntry> read(IcebergManifestFileMeta meta) {
        return read(meta, null);
    }

    public List<IcebergManifestEntry> read(IcebergManifestFileMeta meta, @Nullable Long fileSize) {
        String fileName = new Path(meta.manifestPath()).getName();
        try {
            Path path = pathFactory.toPath(fileName);

            return readFromIterator(
                    meta,
                    createIterator(path, fileSize),
                    (IcebergManifestEntrySerializer) serializer,
                    Filter.alwaysTrue());
        } catch (IOException e) {
            throw new RuntimeException("Failed to read " + fileName, e);
        }
    }

    private CloseableIterator<InternalRow> createIterator(Path file, @Nullable Long fileSize)
            throws IOException {
        return FileUtils.createFormatReader(fileIO, readerFactory, file, fileSize)
                .toCloseableIterator();
    }

    private static List<IcebergManifestEntry> readFromIterator(
            IcebergManifestFileMeta meta,
            CloseableIterator<InternalRow> inputIterator,
            IcebergManifestEntrySerializer serializer,
            Filter<InternalRow> readFilter) {
        try (CloseableIterator<InternalRow> iterator = inputIterator) {
            List<IcebergManifestEntry> result = new ArrayList<>();
            while (iterator.hasNext()) {
                InternalRow row = iterator.next();
                if (readFilter.test(row)) {
                    result.add(serializer.fromRow(row, meta));
                }
            }
            return result;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public List<IcebergManifestFileMeta> rollingWrite(
            Iterator<IcebergManifestEntry> entries, long sequenceNumber) {

        return rollingWrite(entries, sequenceNumber, Content.DATA);
    }

    public List<IcebergManifestFileMeta> rollingWrite(
            Iterator<IcebergManifestEntry> entries, long sequenceNumber, Content content) {
        RollingFileWriter<IcebergManifestEntry, IcebergManifestFileMeta> writer =
                new RollingFileWriter<>(
                        () -> createWriter(sequenceNumber, content), targetFileSize.getBytes());
        try {
            writer.write(entries);
            writer.close();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return writer.result();
    }

    public SingleFileWriter<IcebergManifestEntry, IcebergManifestFileMeta> createWriter(
            long sequenceNumber, Content content) {
        return new IcebergManifestEntryWriter(
                writerFactory, pathFactory.newPath(), compression, sequenceNumber, content);
    }

    private class IcebergManifestEntryWriter
            extends SingleFileWriter<IcebergManifestEntry, IcebergManifestFileMeta> {

        private final SimpleStatsCollector partitionStatsCollector;
        private final long sequenceNumber;

        private int addedFilesCount = 0;
        private int existingFilesCount = 0;
        private int deletedFilesCount = 0;
        private long addedRowsCount = 0;
        private long existingRowsCount = 0;
        private long deletedRowsCount = 0;
        private Long minSequenceNumber = null;

        private final Content content;

        IcebergManifestEntryWriter(
                FormatWriterFactory factory,
                Path path,
                String fileCompression,
                long sequenceNumber,
                Content content) {
            super(
                    IcebergManifestFile.this.fileIO,
                    factory,
                    path,
                    serializer::toRow,
                    fileCompression,
                    false);
            this.partitionStatsCollector = new SimpleStatsCollector(partitionType);
            this.sequenceNumber = sequenceNumber;
            this.content = content;
        }

        @Override
        public void write(IcebergManifestEntry entry) throws IOException {
            super.write(entry);

            switch (entry.status()) {
                case ADDED:
                    addedFilesCount += 1;
                    addedRowsCount += entry.file().recordCount();
                    break;
                case EXISTING:
                    existingFilesCount += 1;
                    existingRowsCount += entry.file().recordCount();
                    break;
                case DELETED:
                    deletedFilesCount += 1;
                    deletedRowsCount += entry.file().recordCount();
                    break;
            }

            if (minSequenceNumber == null || minSequenceNumber > entry.sequenceNumber()) {
                minSequenceNumber = entry.sequenceNumber();
            }

            partitionStatsCollector.collect(entry.file().partition());
        }

        @Override
        public IcebergManifestFileMeta result() {
            SimpleColStats[] stats = partitionStatsCollector.extract();
            List<IcebergPartitionSummary> partitionSummaries = new ArrayList<>();
            for (int i = 0; i < stats.length; i++) {
                SimpleColStats fieldStats = stats[i];
                DataType type = partitionType.getTypeAt(i);
                partitionSummaries.add(
                        new IcebergPartitionSummary(
                                Objects.requireNonNull(fieldStats.nullCount()) > 0,
                                false, // TODO correct it?
                                toByteBuffer(type, fieldStats.min()).array(),
                                toByteBuffer(type, fieldStats.max()).array()));
            }
            return new IcebergManifestFileMeta(
                    path.toString(),
                    outputBytes,
                    IcebergPartitionSpec.SPEC_ID,
                    content,
                    sequenceNumber,
                    minSequenceNumber != null ? minSequenceNumber : UNASSIGNED_SEQ,
                    sequenceNumber,
                    addedFilesCount,
                    existingFilesCount,
                    deletedFilesCount,
                    addedRowsCount,
                    existingRowsCount,
                    deletedRowsCount,
                    partitionSummaries);
        }
    }
}
