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

package org.apache.paimon.spark.procedure;

import org.apache.paimon.FileStore;
import org.apache.paimon.Snapshot;
import org.apache.paimon.table.FileStoreTable;
import org.apache.paimon.utils.Preconditions;
import org.apache.paimon.utils.StringUtils;

import org.apache.spark.sql.catalyst.InternalRow;
import org.apache.spark.sql.connector.catalog.Identifier;
import org.apache.spark.sql.connector.catalog.TableCatalog;
import org.apache.spark.sql.types.DataTypes;
import org.apache.spark.sql.types.Metadata;
import org.apache.spark.sql.types.StructField;
import org.apache.spark.sql.types.StructType;

import static org.apache.spark.sql.types.DataTypes.LongType;
import static org.apache.spark.sql.types.DataTypes.StringType;

/** A procedure to rollback to a snapshot or a tag. */
public class RollbackProcedure extends BaseProcedure {

    private static final ProcedureParameter[] PARAMETERS =
            new ProcedureParameter[] {
                ProcedureParameter.required("table", StringType),
                // snapshot id or tag name
                ProcedureParameter.optional("version", StringType),
                ProcedureParameter.optional("snapshot", LongType),
                ProcedureParameter.optional("tag", StringType)
            };

    private static final StructType OUTPUT_TYPE =
            new StructType(
                    new StructField[] {
                        new StructField(
                                "previous_snapshot_id",
                                DataTypes.LongType,
                                false,
                                Metadata.empty()),
                        new StructField(
                                "current_snapshot_id", DataTypes.LongType, false, Metadata.empty())
                    });

    private RollbackProcedure(TableCatalog tableCatalog) {
        super(tableCatalog);
    }

    @Override
    public ProcedureParameter[] parameters() {
        return PARAMETERS;
    }

    @Override
    public StructType outputType() {
        return OUTPUT_TYPE;
    }

    @Override
    public InternalRow[] call(InternalRow args) {
        Identifier tableIdent = toIdentifier(args.getString(0), PARAMETERS[0].name());
        String version = args.isNullAt(1) ? null : args.getString(1);

        return modifyPaimonTable(
                tableIdent,
                table -> {
                    Long snapshot = null;
                    String tag = null;
                    if (!StringUtils.isNullOrWhitespaceOnly(version)) {
                        Preconditions.checkState(
                                args.isNullAt(2) && args.isNullAt(3),
                                "only can set one of version/snapshot/tag in RollbackProcedure.");
                        if (version.chars().allMatch(Character::isDigit)) {
                            snapshot = Long.parseLong(version);
                        } else {
                            tag = version;
                        }
                    } else {
                        Preconditions.checkState(
                                (args.isNullAt(2) && !args.isNullAt(3)
                                        || !args.isNullAt(2) && args.isNullAt(3)),
                                "only can set one of version/snapshot/tag in RollbackProcedure.");
                        snapshot = args.isNullAt(2) ? null : args.getLong(2);
                        tag = args.isNullAt(3) ? null : args.getString(3);
                    }

                    FileStore<?> store = ((FileStoreTable) table).store();
                    Snapshot latestSnapshot = store.snapshotManager().latestSnapshot();
                    Preconditions.checkNotNull(
                            latestSnapshot, "Latest snapshot is null, can not rollback.");

                    long currentSnapshotId;
                    if (snapshot != null) {
                        table.rollbackTo(snapshot);
                        currentSnapshotId = snapshot;
                    } else {
                        table.rollbackTo(tag);
                        currentSnapshotId =
                                store.newTagManager().getOrThrow(tag).trimToSnapshot().id();
                    }
                    InternalRow outputRow = newInternalRow(latestSnapshot.id(), currentSnapshotId);
                    return new InternalRow[] {outputRow};
                });
    }

    public static ProcedureBuilder builder() {
        return new BaseProcedure.Builder<RollbackProcedure>() {
            @Override
            public RollbackProcedure doBuild() {
                return new RollbackProcedure(tableCatalog());
            }
        };
    }

    @Override
    public String description() {
        return "RollbackProcedure";
    }
}
