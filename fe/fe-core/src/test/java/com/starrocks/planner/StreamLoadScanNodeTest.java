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

// This file is based on code available under the Apache license here:
//   https://github.com/apache/incubator-doris/blob/master/fe/fe-core/src/test/java/org/apache/doris/planner/StreamLoadScanNodeTest.java

// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package com.starrocks.planner;

import com.google.common.collect.Lists;
import com.starrocks.analysis.Analyzer;
import com.starrocks.analysis.DescriptorTable;
import com.starrocks.analysis.FunctionCallExpr;
import com.starrocks.analysis.FunctionName;
import com.starrocks.analysis.SlotDescriptor;
import com.starrocks.analysis.TupleDescriptor;
import com.starrocks.catalog.AggregateType;
import com.starrocks.catalog.Column;
import com.starrocks.catalog.Function;
import com.starrocks.catalog.FunctionSet;
import com.starrocks.catalog.OlapTable;
import com.starrocks.catalog.ScalarFunction;
import com.starrocks.catalog.ScalarType;
import com.starrocks.catalog.Table;
import com.starrocks.catalog.Table.TableType;
import com.starrocks.catalog.Type;
import com.starrocks.common.AnalysisException;
import com.starrocks.common.DdlException;
import com.starrocks.common.StarRocksException;
import com.starrocks.load.Load;
import com.starrocks.load.streamload.StreamLoadInfo;
import com.starrocks.qe.ConnectContext;
import com.starrocks.server.GlobalStateMgr;
import com.starrocks.sql.ast.ImportColumnDesc;
import com.starrocks.sql.parser.AstBuilder;
import com.starrocks.sql.parser.ParsingException;
import com.starrocks.sql.parser.SqlParser;
import com.starrocks.thrift.TDescriptorTable;
import com.starrocks.thrift.TExplainLevel;
import com.starrocks.thrift.TFileFormatType;
import com.starrocks.thrift.TFileType;
import com.starrocks.thrift.TPlanNode;
import com.starrocks.thrift.TPrimitiveType;
import com.starrocks.thrift.TSlotDescriptor;
import com.starrocks.thrift.TStreamLoadPutRequest;
import com.starrocks.thrift.TTypeNode;
import mockit.Expectations;
import mockit.Injectable;
import mockit.Mock;
import mockit.MockUp;
import mockit.Mocked;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;

public class StreamLoadScanNodeTest {
    @Mocked
    GlobalStateMgr globalStateMgr;

    @Injectable
    ConnectContext connectContext;

    @Injectable
    OlapTable dstTable;

    @BeforeEach
    public void setUp() {
        SqlParser sqlParser = new SqlParser(AstBuilder.getInstance());
        new MockUp<GlobalStateMgr>() {
            @Mock
            public SqlParser getSqlParser() {
                return sqlParser;
            }
        };
    }

    TStreamLoadPutRequest getBaseRequest() {
        TStreamLoadPutRequest request = new TStreamLoadPutRequest();
        request.setFileType(TFileType.FILE_STREAM);
        request.setFormatType(TFileFormatType.FORMAT_CSV_PLAIN);
        request.setColumnSeparator(",");
        request.setRowDelimiter("\n");
        return request;
    }

    List<Column> getBaseSchema() {
        List<Column> columns = Lists.newArrayList();

        Column k1 = new Column("k1", Type.BIGINT);
        k1.setIsKey(true);
        k1.setIsAllowNull(false);
        columns.add(k1);

        Column k2 = new Column("k2", ScalarType.createVarchar(25));
        k2.setIsKey(true);
        k2.setIsAllowNull(true);
        columns.add(k2);

        Column v1 = new Column("v1", Type.BIGINT);
        v1.setIsKey(false);
        v1.setIsAllowNull(true);
        v1.setAggregationType(AggregateType.SUM, false);

        columns.add(v1);

        Column v2 = new Column("v2", ScalarType.createVarchar(25));
        v2.setIsKey(false);
        v2.setAggregationType(AggregateType.REPLACE, false);
        v2.setIsAllowNull(false);
        columns.add(v2);

        return columns;
    }

    List<Column> getHllSchema() {
        List<Column> columns = Lists.newArrayList();

        Column k1 = new Column("k1", Type.BIGINT);
        k1.setIsKey(true);
        k1.setIsAllowNull(false);
        columns.add(k1);

        Column v1 = new Column("v1", Type.HLL);
        v1.setIsKey(false);
        v1.setIsAllowNull(true);
        v1.setAggregationType(AggregateType.HLL_UNION, false);

        columns.add(v1);

        return columns;
    }

    List<Column> getDecimalSchema() {
        List<Column> columns = Lists.newArrayList();

        Column c0 = new Column("c0", Type.DEFAULT_DECIMAL32);
        c0.setIsKey(false);
        columns.add(c0);

        Column c1 = new Column("c1", Type.DEFAULT_DECIMAL64);
        c0.setIsKey(false);
        columns.add(c1);

        Column c2 = new Column("c2", Type.DEFAULT_DECIMAL128);
        c0.setIsKey(false);
        columns.add(c2);

        return columns;
    }

    private StreamLoadScanNode getStreamLoadScanNode(TupleDescriptor dstDesc, TStreamLoadPutRequest request)
            throws StarRocksException {
        StreamLoadInfo streamLoadInfo = StreamLoadInfo.fromTStreamLoadPutRequest(request, null);
        StreamLoadScanNode scanNode =
                new StreamLoadScanNode(streamLoadInfo.getId(), new PlanNodeId(1), dstDesc, dstTable, streamLoadInfo);
        return scanNode;
    }

    @Test
    public void testNormal() throws StarRocksException {
        Analyzer analyzer = new Analyzer(globalStateMgr, connectContext);
        DescriptorTable descTbl = analyzer.getDescTbl();

        List<Column> columns = getBaseSchema();
        TupleDescriptor dstDesc = descTbl.createTupleDescriptor("DstTableDesc");
        for (Column column : columns) {
            SlotDescriptor slot = descTbl.addSlotDescriptor(dstDesc);
            slot.setColumn(column);
            slot.setIsMaterialized(true);
            if (column.isAllowNull()) {
                slot.setIsNullable(true);
            } else {
                slot.setIsNullable(false);
            }
        }

        TStreamLoadPutRequest request = getBaseRequest();
        StreamLoadScanNode scanNode = getStreamLoadScanNode(dstDesc, request);
        new Expectations() {{
            dstTable.getBaseSchema();
            result = columns;
            dstTable.getFullSchema();
            result = columns;
            dstTable.getColumn("k1");
            result = columns.get(0);
            dstTable.getColumn("k2");
            result = columns.get(1);
            dstTable.getColumn("v1");
            result = columns.get(2);
            dstTable.getColumn("v2");
            result = columns.get(3);
        }};
        scanNode.init(analyzer);
        scanNode.finalizeStats();
        scanNode.getNodeExplainString("", TExplainLevel.NORMAL);
        TPlanNode planNode = new TPlanNode();
        scanNode.toThrift(planNode);
        Assertions.assertEquals(1, scanNode.getScanRangeLocations(0).size());
    }

    @Test
    public void testLostV2() {
        assertThrows(AnalysisException.class, () -> {
            Analyzer analyzer = new Analyzer(globalStateMgr, connectContext);
            DescriptorTable descTbl = analyzer.getDescTbl();

            List<Column> columns = getBaseSchema();
            TupleDescriptor dstDesc = descTbl.createTupleDescriptor("DstTableDesc");
            for (Column column : columns) {
                SlotDescriptor slot = descTbl.addSlotDescriptor(dstDesc);
                slot.setColumn(column);
                slot.setIsMaterialized(true);
                if (column.isAllowNull()) {
                    slot.setIsNullable(true);
                } else {
                    slot.setIsNullable(false);
                }
            }

            TStreamLoadPutRequest request = getBaseRequest();
            request.setColumns("k1, k2, v1");
            StreamLoadInfo streamLoadInfo = StreamLoadInfo.fromTStreamLoadPutRequest(request, null);
            StreamLoadScanNode scanNode = getStreamLoadScanNode(dstDesc, request);

            scanNode.init(analyzer);
            scanNode.finalizeStats();
            scanNode.getNodeExplainString("", TExplainLevel.NORMAL);
            TPlanNode planNode = new TPlanNode();
            scanNode.toThrift(planNode);
        });
    }

    @Test
    public void testBadColumns(@Mocked GlobalStateMgr globalStateMgr) {
        assertThrows(ParsingException.class, () -> {
            Analyzer analyzer = new Analyzer(globalStateMgr, connectContext);
            DescriptorTable descTbl = analyzer.getDescTbl();

            List<Column> columns = getBaseSchema();
            TupleDescriptor dstDesc = descTbl.createTupleDescriptor("DstTableDesc");
            for (Column column : columns) {
                SlotDescriptor slot = descTbl.addSlotDescriptor(dstDesc);
                slot.setColumn(column);
                slot.setIsMaterialized(true);
                if (column.isAllowNull()) {
                    slot.setIsNullable(true);
                } else {
                    slot.setIsNullable(false);
                }
            }

            TStreamLoadPutRequest request = getBaseRequest();
            request.setColumns("k1 k2 v1");
            StreamLoadInfo streamLoadInfo = StreamLoadInfo.fromTStreamLoadPutRequest(request, null);
            StreamLoadScanNode scanNode = getStreamLoadScanNode(dstDesc, request);

            scanNode.init(analyzer);
            scanNode.finalizeStats();
            scanNode.getNodeExplainString("", TExplainLevel.NORMAL);
            TPlanNode planNode = new TPlanNode();
            scanNode.toThrift(planNode);
        });
    }

    @Test
    public void testColumnsNormal() throws StarRocksException, StarRocksException {
        Analyzer analyzer = new Analyzer(globalStateMgr, connectContext);
        DescriptorTable descTbl = analyzer.getDescTbl();

        List<Column> columns = getBaseSchema();
        TupleDescriptor dstDesc = descTbl.createTupleDescriptor("DstTableDesc");
        for (Column column : columns) {
            SlotDescriptor slot = descTbl.addSlotDescriptor(dstDesc);
            slot.setColumn(column);
            slot.setIsMaterialized(true);
            if (column.isAllowNull()) {
                slot.setIsNullable(true);
            } else {
                slot.setIsNullable(false);
            }
        }

        new Expectations() {
            {
                dstTable.getColumn("k1");
                result = columns.stream().filter(c -> c.getName().equals("k1")).findFirst().get();

                dstTable.getColumn("k2");
                result = columns.stream().filter(c -> c.getName().equals("k2")).findFirst().get();

                dstTable.getColumn("v1");
                result = columns.stream().filter(c -> c.getName().equals("v1")).findFirst().get();

                dstTable.getColumn("v2");
                result = columns.stream().filter(c -> c.getName().equals("v2")).findFirst().get();
            }
        };

        TStreamLoadPutRequest request = getBaseRequest();
        request.setColumns("k1,k2,v1, v2=k2");
        StreamLoadInfo streamLoadInfo = StreamLoadInfo.fromTStreamLoadPutRequest(request, null);
        StreamLoadScanNode scanNode = getStreamLoadScanNode(dstDesc, request);
        scanNode.init(analyzer);
        scanNode.finalizeStats();
        scanNode.getNodeExplainString("", TExplainLevel.NORMAL);
        TPlanNode planNode = new TPlanNode();
        scanNode.toThrift(planNode);
    }

    @Test
    public void testSetColumnOfDecimal() {
        Analyzer analyzer = new Analyzer(globalStateMgr, connectContext);
        DescriptorTable descTbl = analyzer.getDescTbl();
        List<Column> columns = getDecimalSchema();

        TupleDescriptor dstDesc = descTbl.createTupleDescriptor("DstTableDesc");
        for (Column column : columns) {
            SlotDescriptor slot = descTbl.addSlotDescriptor(dstDesc);
            slot.setColumn(column);
            slot.setIsMaterialized(true);
            if (column.isAllowNull()) {
                slot.setIsNullable(true);
            } else {
                slot.setIsNullable(false);
            }
        }
        TDescriptorTable tableDesc = descTbl.toThrift();
        TSlotDescriptor slotDesc = tableDesc.getSlotDescriptors().get(2);
        TTypeNode typeNode = slotDesc.slotType.getTypes().get(0);
        Assertions.assertTrue(typeNode.isSetScalar_type());
        Assertions.assertEquals(typeNode.scalar_type.type, TPrimitiveType.DECIMAL128);
        Assertions.assertEquals(typeNode.scalar_type.precision, 38);
        Assertions.assertEquals(typeNode.scalar_type.scale, 9);
    }

    @Test
    public void testHllColumnsNormal() throws StarRocksException {
        Analyzer analyzer = new Analyzer(globalStateMgr, connectContext);
        DescriptorTable descTbl = analyzer.getDescTbl();

        List<Column> columns = getHllSchema();
        TupleDescriptor dstDesc = descTbl.createTupleDescriptor("DstTableDesc");
        for (Column column : columns) {
            SlotDescriptor slot = descTbl.addSlotDescriptor(dstDesc);
            slot.setColumn(column);
            slot.setIsMaterialized(true);
            if (column.isAllowNull()) {
                slot.setIsNullable(true);
            } else {
                slot.setIsNullable(false);
            }
        }

        new Expectations() {{
            globalStateMgr.getFunction((Function) any, (Function.CompareMode) any);
            result = new ScalarFunction(new FunctionName(FunctionSet.HLL_HASH), Lists.newArrayList(), Type.BIGINT,
                    false);
        }};

        new Expectations() {
            {
                dstTable.getColumn("k1");
                result = columns.stream().filter(c -> c.getName().equals("k1")).findFirst().get();

                dstTable.getColumn("k2");
                result = null;

                dstTable.getColumn("v1");
                result = columns.stream().filter(c -> c.getName().equals("v1")).findFirst().get();
            }
        };

        TStreamLoadPutRequest request = getBaseRequest();
        request.setFileType(TFileType.FILE_STREAM);
        request.setColumns("k1,k2, v1=" + FunctionSet.HLL_HASH + "(k2)");
        StreamLoadInfo streamLoadInfo = StreamLoadInfo.fromTStreamLoadPutRequest(request, null);
        StreamLoadScanNode scanNode = getStreamLoadScanNode(dstDesc, request);

        scanNode.init(analyzer);
        scanNode.finalizeStats();
        scanNode.getNodeExplainString("", TExplainLevel.NORMAL);
        TPlanNode planNode = new TPlanNode();
        scanNode.toThrift(planNode);
    }

    @Test
    public void testHllColumnsNoHllHash() {
        assertThrows(StarRocksException.class, () -> {
            Analyzer analyzer = new Analyzer(globalStateMgr, connectContext);
            DescriptorTable descTbl = analyzer.getDescTbl();

            List<Column> columns = getHllSchema();
            TupleDescriptor dstDesc = descTbl.createTupleDescriptor("DstTableDesc");
            for (Column column : columns) {
                SlotDescriptor slot = descTbl.addSlotDescriptor(dstDesc);
                slot.setColumn(column);
                slot.setIsMaterialized(true);
                if (column.isAllowNull()) {
                    slot.setIsNullable(true);
                } else {
                    slot.setIsNullable(false);
                }
            }

            new Expectations() {
                {
                    globalStateMgr.getFunction((Function) any, (Function.CompareMode) any);
                    result = new ScalarFunction(new FunctionName("hll_hash1"), Lists.newArrayList(), Type.BIGINT, false);
                    minTimes = 0;
                }
            };

            new Expectations() {
                {
                    dstTable.getColumn("k1");
                    result = columns.stream().filter(c -> c.getName().equals("k1")).findFirst().get();
                    minTimes = 0;

                    dstTable.getColumn("k2");
                    result = null;
                    minTimes = 0;

                    dstTable.getColumn("v1");
                    result = columns.stream().filter(c -> c.getName().equals("v1")).findFirst().get();
                    minTimes = 0;
                }
            };

            TStreamLoadPutRequest request = getBaseRequest();
            request.setFileType(TFileType.FILE_LOCAL);
            request.setColumns("k1,k2, v1=hll_hash1(k2)");
            StreamLoadInfo streamLoadInfo = StreamLoadInfo.fromTStreamLoadPutRequest(request, null);
            StreamLoadScanNode scanNode = getStreamLoadScanNode(dstDesc, request);

            scanNode.init(analyzer);
            scanNode.finalizeStats();
            scanNode.getNodeExplainString("", TExplainLevel.NORMAL);
            TPlanNode planNode = new TPlanNode();
            scanNode.toThrift(planNode);
        });
    }

    @Test
    public void testHllColumnsFail() {
        assertThrows(StarRocksException.class, () -> {
            Analyzer analyzer = new Analyzer(globalStateMgr, connectContext);
            DescriptorTable descTbl = analyzer.getDescTbl();

            List<Column> columns = getHllSchema();
            TupleDescriptor dstDesc = descTbl.createTupleDescriptor("DstTableDesc");
            for (Column column : columns) {
                SlotDescriptor slot = descTbl.addSlotDescriptor(dstDesc);
                slot.setColumn(column);
                slot.setIsMaterialized(true);
                if (column.isAllowNull()) {
                    slot.setIsNullable(true);
                } else {
                    slot.setIsNullable(false);
                }
            }

            TStreamLoadPutRequest request = getBaseRequest();
            request.setFileType(TFileType.FILE_LOCAL);
            request.setColumns("k1,k2, v1=k2");
            StreamLoadScanNode scanNode = getStreamLoadScanNode(dstDesc, request);

            scanNode.init(analyzer);
            scanNode.finalizeStats();
            scanNode.getNodeExplainString("", TExplainLevel.NORMAL);
            TPlanNode planNode = new TPlanNode();
            scanNode.toThrift(planNode);
        });
    }

    @Test
    public void testUnsupportedFType() {
        assertThrows(StarRocksException.class, () -> {
            Analyzer analyzer = new Analyzer(globalStateMgr, connectContext);
            DescriptorTable descTbl = analyzer.getDescTbl();

            List<Column> columns = getBaseSchema();
            TupleDescriptor dstDesc = descTbl.createTupleDescriptor("DstTableDesc");
            for (Column column : columns) {
                SlotDescriptor slot = descTbl.addSlotDescriptor(dstDesc);
                slot.setColumn(column);
                slot.setIsMaterialized(true);
                if (column.isAllowNull()) {
                    slot.setIsNullable(true);
                } else {
                    slot.setIsNullable(false);
                }
            }

            TStreamLoadPutRequest request = getBaseRequest();
            request.setFileType(TFileType.FILE_BROKER);
            request.setColumns("k1,k2,v1, v2=k2");
            StreamLoadScanNode scanNode = getStreamLoadScanNode(dstDesc, request);

            scanNode.init(analyzer);
            scanNode.finalizeStats();
            scanNode.getNodeExplainString("", TExplainLevel.NORMAL);
            TPlanNode planNode = new TPlanNode();
            scanNode.toThrift(planNode);
        });
    }

    @Test
    public void testColumnsUnknownRef() {
        assertThrows(StarRocksException.class, () -> {
            Analyzer analyzer = new Analyzer(globalStateMgr, connectContext);
            DescriptorTable descTbl = analyzer.getDescTbl();

            List<Column> columns = getBaseSchema();
            TupleDescriptor dstDesc = descTbl.createTupleDescriptor("DstTableDesc");
            for (Column column : columns) {
                SlotDescriptor slot = descTbl.addSlotDescriptor(dstDesc);
                slot.setColumn(column);
                slot.setIsMaterialized(true);
                if (column.isAllowNull()) {
                    slot.setIsNullable(true);
                } else {
                    slot.setIsNullable(false);
                }
            }

            new Expectations() {
                {
                    dstTable.getColumn("k1");
                    result = columns.stream().filter(c -> c.getName().equals("k1")).findFirst().get();
                    minTimes = 0;

                    dstTable.getColumn("k2");
                    result = columns.stream().filter(c -> c.getName().equals("k2")).findFirst().get();
                    minTimes = 0;

                    dstTable.getColumn("v1");
                    result = columns.stream().filter(c -> c.getName().equals("v1")).findFirst().get();
                    minTimes = 0;

                    dstTable.getColumn("v2");
                    result = columns.stream().filter(c -> c.getName().equals("v2")).findFirst().get();
                    minTimes = 0;
                }
            };

            TStreamLoadPutRequest request = getBaseRequest();
            request.setColumns("k1,k2,v1, v2=k3");
            StreamLoadScanNode scanNode = getStreamLoadScanNode(dstDesc, request);

            scanNode.init(analyzer);
            scanNode.finalizeStats();
            scanNode.getNodeExplainString("", TExplainLevel.NORMAL);
            TPlanNode planNode = new TPlanNode();
            scanNode.toThrift(planNode);
        });
    }

    @Test
    public void testWhereNormal() throws StarRocksException, StarRocksException {
        Analyzer analyzer = new Analyzer(globalStateMgr, connectContext);
        DescriptorTable descTbl = analyzer.getDescTbl();

        List<Column> columns = getBaseSchema();
        TupleDescriptor dstDesc = descTbl.createTupleDescriptor("DstTableDesc");
        for (Column column : columns) {
            SlotDescriptor slot = descTbl.addSlotDescriptor(dstDesc);
            slot.setColumn(column);
            slot.setIsMaterialized(true);
            if (column.isAllowNull()) {
                slot.setIsNullable(true);
            } else {
                slot.setIsNullable(false);
            }
        }

        new Expectations() {
            {
                dstTable.getColumn("k1");
                result = columns.stream().filter(c -> c.getName().equals("k1")).findFirst().get();
                minTimes = 0;

                dstTable.getColumn("k2");
                result = columns.stream().filter(c -> c.getName().equals("k2")).findFirst().get();
                minTimes = 0;

                dstTable.getColumn("v1");
                result = columns.stream().filter(c -> c.getName().equals("v1")).findFirst().get();
                minTimes = 0;

                dstTable.getColumn("v2");
                result = columns.stream().filter(c -> c.getName().equals("v2")).findFirst().get();
                minTimes = 0;
            }
        };

        TStreamLoadPutRequest request = getBaseRequest();
        request.setColumns("k1,k2,v1, v2=k1");
        request.setWhere("k1 = 1");
        StreamLoadScanNode scanNode = getStreamLoadScanNode(dstDesc, request);

        scanNode.init(analyzer);
        scanNode.finalizeStats();
        scanNode.getNodeExplainString("", TExplainLevel.NORMAL);
        TPlanNode planNode = new TPlanNode();
        scanNode.toThrift(planNode);
    }

    @Test
    public void testWhereBad() {
        assertThrows(ParsingException.class, () -> {
            Analyzer analyzer = new Analyzer(globalStateMgr, connectContext);
            DescriptorTable descTbl = analyzer.getDescTbl();

            List<Column> columns = getBaseSchema();
            TupleDescriptor dstDesc = descTbl.createTupleDescriptor("DstTableDesc");
            for (Column column : columns) {
                SlotDescriptor slot = descTbl.addSlotDescriptor(dstDesc);
                slot.setColumn(column);
                slot.setIsMaterialized(true);
                if (column.isAllowNull()) {
                    slot.setIsNullable(true);
                } else {
                    slot.setIsNullable(false);
                }
            }

            new Expectations() {
                {
                    dstTable.getColumn("k1");
                    result = columns.stream().filter(c -> c.getName().equals("k1")).findFirst().get();
                    minTimes = 0;

                    dstTable.getColumn("k2");
                    result = columns.stream().filter(c -> c.getName().equals("k2")).findFirst().get();
                    minTimes = 0;

                    dstTable.getColumn("v1");
                    result = columns.stream().filter(c -> c.getName().equals("v1")).findFirst().get();
                    minTimes = 0;

                    dstTable.getColumn("v2");
                    result = columns.stream().filter(c -> c.getName().equals("v2")).findFirst().get();
                    minTimes = 0;
                }
            };

            TStreamLoadPutRequest request = getBaseRequest();
            request.setColumns("k1,k2,v1, v2=k2");
            request.setWhere("k1   1");
            StreamLoadInfo streamLoadInfo = StreamLoadInfo.fromTStreamLoadPutRequest(request, null);
            StreamLoadScanNode scanNode =
                    new StreamLoadScanNode(streamLoadInfo.getId(), new PlanNodeId(1), dstDesc, dstTable,
                            streamLoadInfo);

            scanNode.init(analyzer);
            scanNode.finalizeStats();
            scanNode.getNodeExplainString("", TExplainLevel.NORMAL);
            TPlanNode planNode = new TPlanNode();
            scanNode.toThrift(planNode);
        });
    }

    @Test
    public void testWhereUnknownRef() {
        assertThrows(StarRocksException.class, () -> {
            Analyzer analyzer = new Analyzer(globalStateMgr, connectContext);
            DescriptorTable descTbl = analyzer.getDescTbl();

            List<Column> columns = getBaseSchema();
            TupleDescriptor dstDesc = descTbl.createTupleDescriptor("DstTableDesc");
            for (Column column : columns) {
                SlotDescriptor slot = descTbl.addSlotDescriptor(dstDesc);
                slot.setColumn(column);
                slot.setIsMaterialized(true);
                if (column.isAllowNull()) {
                    slot.setIsNullable(true);
                } else {
                    slot.setIsNullable(false);
                }
            }

            new Expectations() {
                {
                    dstTable.getColumn("k1");
                    result = columns.stream().filter(c -> c.getName().equals("k1")).findFirst().get();
                    minTimes = 0;

                    dstTable.getColumn("k2");
                    result = columns.stream().filter(c -> c.getName().equals("k2")).findFirst().get();
                    minTimes = 0;

                    dstTable.getColumn("v1");
                    result = columns.stream().filter(c -> c.getName().equals("v1")).findFirst().get();
                    minTimes = 0;

                    dstTable.getColumn("v2");
                    result = columns.stream().filter(c -> c.getName().equals("v2")).findFirst().get();
                    minTimes = 0;
                }
            };

            TStreamLoadPutRequest request = getBaseRequest();
            request.setColumns("k1,k2,v1, v2=k1");
            request.setWhere("k5 = 1");
            StreamLoadScanNode scanNode = getStreamLoadScanNode(dstDesc, request);

            scanNode.init(analyzer);
            scanNode.finalizeStats();
            scanNode.getNodeExplainString("", TExplainLevel.NORMAL);
            TPlanNode planNode = new TPlanNode();
            scanNode.toThrift(planNode);
        });
    }

    @Test
    public void testWhereNotBool() {
        assertThrows(StarRocksException.class, () -> {
            Analyzer analyzer = new Analyzer(globalStateMgr, connectContext);
            DescriptorTable descTbl = analyzer.getDescTbl();

            List<Column> columns = getBaseSchema();
            TupleDescriptor dstDesc = descTbl.createTupleDescriptor("DstTableDesc");
            for (Column column : columns) {
                SlotDescriptor slot = descTbl.addSlotDescriptor(dstDesc);
                slot.setColumn(column);
                slot.setIsMaterialized(true);
                if (column.isAllowNull()) {
                    slot.setIsNullable(true);
                } else {
                    slot.setIsNullable(false);
                }
            }

            TStreamLoadPutRequest request = getBaseRequest();
            request.setColumns("k1,k2,v1,v2");
            request.setWhere("k1 + v1");
            StreamLoadScanNode scanNode = getStreamLoadScanNode(dstDesc, request);

            new Expectations() {
                {
                    dstTable.getBaseSchema();
                    result = columns;
                    dstTable.getFullSchema();
                    result = columns;
                    dstTable.getColumn("k1");
                    result = columns.get(0);
                    dstTable.getColumn("k2");
                    result = columns.get(1);
                    dstTable.getColumn("v1");
                    result = columns.get(2);
                    dstTable.getColumn("v2");
                    result = columns.get(3);
                }
            };

            new Expectations() {
                {
                    globalStateMgr.getFunction((Function) any, (Function.CompareMode) any);
                    result = new ScalarFunction(new FunctionName(FunctionSet.ADD), Lists.newArrayList(), Type.BIGINT,
                            false);
                }
            };

            scanNode.init(analyzer);
            scanNode.finalizeStats();
            scanNode.getNodeExplainString("", TExplainLevel.NORMAL);
            TPlanNode planNode = new TPlanNode();
            scanNode.toThrift(planNode);
        });
    }

    @Test
    public void testLoadInitColumnsMappingColumnNotExist() {
        assertThrows(DdlException.class, () -> {
            List<Column> columns = Lists.newArrayList();
            columns.add(new Column("c1", Type.INT, true, null, false, null, ""));
            columns.add(new Column("c2", ScalarType.createVarchar(10), true, null, false, null, ""));
            Table table = new Table(1L, "table0", TableType.OLAP, columns);
            List<ImportColumnDesc> columnExprs = Lists.newArrayList();
            columnExprs.add(new ImportColumnDesc("c3", new FunctionCallExpr("func", Lists.newArrayList())));
            Load.initColumns(table, columnExprs, null, null, null, null, null, null, true, false, Lists.newArrayList());
        });
    }
}
