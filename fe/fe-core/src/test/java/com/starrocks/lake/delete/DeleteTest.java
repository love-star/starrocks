// Copyright 2021-present StarRocks, Inc. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     https://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.


package com.starrocks.lake.delete;

import com.google.common.collect.Lists;
import com.starrocks.analysis.BinaryPredicate;
import com.starrocks.analysis.BinaryType;
import com.starrocks.analysis.IntLiteral;
import com.starrocks.analysis.IsNullPredicate;
import com.starrocks.analysis.SlotRef;
import com.starrocks.analysis.StringLiteral;
import com.starrocks.analysis.TableName;
import com.starrocks.catalog.Column;
import com.starrocks.catalog.Database;
import com.starrocks.catalog.DistributionInfo;
import com.starrocks.catalog.HashDistributionInfo;
import com.starrocks.catalog.KeysType;
import com.starrocks.catalog.MaterializedIndex;
import com.starrocks.catalog.Partition;
import com.starrocks.catalog.PartitionInfo;
import com.starrocks.catalog.SinglePartitionInfo;
import com.starrocks.catalog.Tablet;
import com.starrocks.catalog.TabletMeta;
import com.starrocks.catalog.Type;
import com.starrocks.common.DdlException;
import com.starrocks.common.StarRocksException;
import com.starrocks.common.jmockit.Deencapsulation;
import com.starrocks.lake.LakeTable;
import com.starrocks.lake.LakeTablet;
import com.starrocks.load.DeleteJob;
import com.starrocks.load.DeleteMgr;
import com.starrocks.persist.EditLog;
import com.starrocks.proto.DeleteDataRequest;
import com.starrocks.proto.DeleteDataResponse;
import com.starrocks.qe.ConnectContext;
import com.starrocks.qe.QueryStateException;
import com.starrocks.qe.VariableMgr;
import com.starrocks.rpc.BrpcProxy;
import com.starrocks.rpc.LakeService;
import com.starrocks.rpc.RpcException;
import com.starrocks.server.GlobalStateMgr;
import com.starrocks.sql.analyzer.Analyzer;
import com.starrocks.sql.ast.DeleteStmt;
import com.starrocks.sql.ast.PartitionNames;
import com.starrocks.system.Backend;
import com.starrocks.system.SystemInfoService;
import com.starrocks.thrift.TStorageMedium;
import com.starrocks.thrift.TStorageType;
import com.starrocks.transaction.GlobalTransactionMgr;
import com.starrocks.transaction.TransactionState;
import com.starrocks.transaction.TransactionStatus;
import mockit.Expectations;
import mockit.Mock;
import mockit.MockUp;
import mockit.Mocked;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertThrows;

public class DeleteTest {
    private final long dbId = 1L;
    private final long tableId = 2L;
    private final long partitionId = 3L;
    private final long indexId = 4L;
    private final long physicalPartitionId = 6L;
    private final long tablet1Id = 10L;
    private final long tablet2Id = 11L;
    private final long backendId = 20L;
    private final String dbName = "db1";
    private final String tableName = "t1";
    private final String partitionName = "p1";

    @Mocked
    private GlobalStateMgr globalStateMgr;
    @Mocked
    private GlobalTransactionMgr globalTransactionMgr;
    @Mocked
    private EditLog editLog;
    @Mocked
    private SystemInfoService systemInfoService;
    @Mocked
    private LakeService lakeService;

    private Database db;
    private ConnectContext connectContext = new ConnectContext();
    private DeleteMgr deleteHandler;
    private VariableMgr variableMgr = new VariableMgr();

    private Database createDb() {
        // Schema
        List<Column> columns = Lists.newArrayList();
        Column k1 = new Column("k1", Type.INT, true, null, "", "");
        columns.add(k1);
        columns.add(new Column("k2", Type.BIGINT, true, null, "", ""));
        columns.add(new Column("v", Type.BIGINT, false, null, "0", ""));
        columns.add(new Column("v1", Type.ARRAY_BIGINT, false, null, "0", ""));

        // Tablet
        Tablet tablet1 = new LakeTablet(tablet1Id);
        Tablet tablet2 = new LakeTablet(tablet2Id);

        // Index
        MaterializedIndex index = new MaterializedIndex(indexId, MaterializedIndex.IndexState.NORMAL);
        TabletMeta tabletMeta = new TabletMeta(dbId, tableId, partitionId, indexId, TStorageMedium.HDD, true);
        index.addTablet(tablet1, tabletMeta);
        index.addTablet(tablet2, tabletMeta);

        // Partition
        DistributionInfo distributionInfo = new HashDistributionInfo(10, Lists.newArrayList(k1));
        PartitionInfo partitionInfo = new SinglePartitionInfo();
        partitionInfo.setReplicationNum(partitionId, (short) 3);
        Partition partition = new Partition(partitionId, physicalPartitionId, partitionName, index, distributionInfo);

        // Lake table
        LakeTable table = new LakeTable(tableId, tableName, columns, KeysType.DUP_KEYS, partitionInfo, distributionInfo);
        Deencapsulation.setField(table, "baseIndexId", indexId);
        table.addPartition(partition);
        table.setIndexMeta(indexId, "t1", columns, 0, 0, (short) 3, TStorageType.COLUMN, KeysType.AGG_KEYS);

        Database db = new Database(dbId, dbName);
        db.registerTableUnlocked(table);
        return db;
    }

    public void setUpExpectation() {
        Backend backend = new Backend(backendId, "127.0.0.1", 1234);

        new Expectations() {
            {
                GlobalStateMgr.getCurrentState();
                result = globalStateMgr;

                globalStateMgr.getLocalMetastore().getDb(anyString);
                result = db;

                globalStateMgr.getLocalMetastore().getTable(anyString, anyString);
                result = db.getTable(tableId);

                GlobalStateMgr.getCurrentState().getGlobalTransactionMgr();
                result = globalTransactionMgr;

                GlobalStateMgr.getCurrentState().getNodeMgr().getClusterInfo();
                result = systemInfoService;

                systemInfoService.getBackendOrComputeNode(anyLong);
                result = backend;
            }
        };
    }

    @BeforeEach
    public void setUp() {
        connectContext.setGlobalStateMgr(globalStateMgr);
        connectContext.setSessionVariable(variableMgr.newSessionVariable());
        deleteHandler = new DeleteMgr();
        db = createDb();
    }

    @Test
    public void testNormal() throws StarRocksException, RpcException {
        setUpExpectation();
        TransactionState transactionState = new TransactionState();
        transactionState.setTransactionStatus(TransactionStatus.VISIBLE);

        new MockUp<BrpcProxy>() {
            @Mock
            public LakeService getLakeService(String host, int port) {
                return lakeService;
            }
        };
        new Expectations() {
            {
                lakeService.deleteData((DeleteDataRequest) any);
                result = new Future<DeleteDataResponse>() {
                    @Override
                    public boolean cancel(boolean mayInterruptIfRunning) {
                        return false;
                    }

                    @Override
                    public boolean isCancelled() {
                        return false;
                    }

                    @Override
                    public boolean isDone() {
                        return false;
                    }

                    @Override
                    public DeleteDataResponse get() throws InterruptedException, ExecutionException {
                        return null;
                    }

                    @Override
                    public DeleteDataResponse get(long timeout, @NotNull TimeUnit unit)
                            throws InterruptedException, ExecutionException, TimeoutException {
                        return null;
                    }
                };

                globalTransactionMgr.commitAndPublishTransaction(db, anyLong, (List) any, (List) any, anyLong);
                result = true;

                globalTransactionMgr.getTransactionState(anyLong, anyLong);
                result = transactionState;
            }
        };

        BinaryPredicate binaryPredicate = new BinaryPredicate(BinaryType.GT, new SlotRef(null, "k1"),
                new IntLiteral(3));

        DeleteStmt deleteStmt = new DeleteStmt(new TableName(dbName, tableName),
                new PartitionNames(false, Lists.newArrayList(partitionName)), binaryPredicate);

        try {
            Analyzer analyzer = new Analyzer(Analyzer.AnalyzerVisitor.getInstance());
            new Expectations() {
                {
                    globalStateMgr.getAnalyzer();
                    result = analyzer;
                }
            };
            com.starrocks.sql.analyzer.Analyzer.analyze(deleteStmt, connectContext);
        } catch (Exception e) {
            Assertions.fail();
        }

        try {
            deleteHandler.process(deleteStmt);
        } catch (QueryStateException e) {
        }

        Map<Long, DeleteJob> idToDeleteJob = Deencapsulation.getField(deleteHandler, "idToDeleteJob");
        Collection<DeleteJob> jobs = idToDeleteJob.values();
        Assertions.assertEquals(0, jobs.size());
    }

    @Test
    public void testBeDeleteFail() {
        assertThrows(DdlException.class, () -> {
            setUpExpectation();
            new MockUp<BrpcProxy>() {
                @Mock
                public LakeService getLakeService(String host, int port) {
                    return lakeService;
                }
            };
            new Expectations() {
                {
                    lakeService.deleteData((DeleteDataRequest) any);
                    result = new Future<DeleteDataResponse>() {
                        @Override
                        public boolean cancel(boolean mayInterruptIfRunning) {
                            return false;
                        }

                        @Override
                        public boolean isCancelled() {
                            return false;
                        }

                        @Override
                        public boolean isDone() {
                            return false;
                        }

                        @Override
                        public DeleteDataResponse get() throws InterruptedException, ExecutionException {
                            DeleteDataResponse response = new DeleteDataResponse();
                            response.failedTablets = Lists.newArrayList(tablet1Id);
                            return response;
                        }

                        @Override
                        public DeleteDataResponse get(long timeout, @NotNull TimeUnit unit)
                                throws InterruptedException, ExecutionException, TimeoutException {
                            return null;
                        }
                    };
                }
            };

            BinaryPredicate binaryPredicate = new BinaryPredicate(BinaryType.GT, new SlotRef(null, "k1"),
                    new IntLiteral(3));

            DeleteStmt deleteStmt = new DeleteStmt(new TableName(dbName, tableName),
                    new PartitionNames(false, Lists.newArrayList(partitionName)), binaryPredicate);

            try {
                Analyzer analyzer = new Analyzer(Analyzer.AnalyzerVisitor.getInstance());
                new Expectations() {
                    {
                        globalStateMgr.getAnalyzer();
                        result = analyzer;
                    }
                };
                com.starrocks.sql.analyzer.Analyzer.analyze(deleteStmt, connectContext);
            } catch (Exception e) {
                Assertions.fail();
            }

            deleteHandler.process(deleteStmt);
        });
    }

    public void setUpExpectationWithoutExec() {

        new Expectations() {
            {
                GlobalStateMgr.getCurrentState();
                result = globalStateMgr;

                globalStateMgr.getLocalMetastore().getDb(anyString);
                result = db;

                globalStateMgr.getLocalMetastore().getTable(anyString, anyString);
                result = db.getTable(tableId);

                GlobalStateMgr.getCurrentState().getGlobalTransactionMgr();
                result = globalTransactionMgr;
            }
        };
    }

    @Test
    public void testBeDeleteArrayType() throws StarRocksException {
        setUpExpectationWithoutExec();
        new MockUp<BrpcProxy>() {
            @Mock
            public LakeService getLakeService(String host, int port) {
                return lakeService;
            }
        };

        // Not supported type
        BinaryPredicate binaryPredicate = new BinaryPredicate(BinaryType.GT, new SlotRef(null, "v1"),
                new StringLiteral("[]"));
        DeleteStmt deleteStmt = new DeleteStmt(new TableName(dbName, tableName),
                new PartitionNames(false, Lists.newArrayList(partitionName)), binaryPredicate);

        Analyzer analyzer = new Analyzer(Analyzer.AnalyzerVisitor.getInstance());
        new Expectations() {
            {
                globalStateMgr.getAnalyzer();
                result = analyzer;
            }
        };
        com.starrocks.sql.analyzer.Analyzer.analyze(deleteStmt, connectContext);
        try {
            deleteHandler.process(deleteStmt);
        } catch (DdlException e) {
            Assertions.assertTrue(e.getMessage().contains("unsupported delete condition on Array/Map/Struct type column"));
        }

        // Not supported type
        IsNullPredicate isNull = new IsNullPredicate(new SlotRef(null, "v1"), true);
        deleteStmt = new DeleteStmt(new TableName(dbName, tableName),
                new PartitionNames(false, Lists.newArrayList(partitionName)), isNull);

        com.starrocks.sql.analyzer.Analyzer.analyze(deleteStmt, connectContext);
        try {
            deleteHandler.process(deleteStmt);
        } catch (DdlException e) {
            Assertions.assertTrue(e.getMessage().contains("unsupported delete condition on Array/Map/Struct type"));
        }
    }
}
