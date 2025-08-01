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

package com.starrocks.planner;

import com.google.common.collect.Lists;
import com.starrocks.analysis.TupleDescriptor;
import com.starrocks.analysis.TupleId;
import com.starrocks.catalog.Column;
import com.starrocks.catalog.HiveTable;
import com.starrocks.catalog.Type;
import com.starrocks.common.ExceptionChecker;
import com.starrocks.connector.CatalogConnector;
import com.starrocks.connector.ConnectorMgr;
import com.starrocks.connector.ConnectorTableId;
import com.starrocks.connector.exception.StarRocksConnectorException;
import com.starrocks.connector.hive.HiveStorageFormat;
import com.starrocks.credential.CloudConfiguration;
import com.starrocks.credential.CloudConfigurationFactory;
import com.starrocks.qe.SessionVariable;
import com.starrocks.sql.analyzer.AnalyzeTestUtil;
import com.starrocks.thrift.TCloudType;
import com.starrocks.thrift.TCompressionType;
import com.starrocks.thrift.TDataSink;
import com.starrocks.thrift.TDataSinkType;
import com.starrocks.thrift.TExplainLevel;
import com.starrocks.thrift.THiveTableSink;
import com.starrocks.utframe.StarRocksAssert;
import com.starrocks.utframe.UtFrameUtils;
import mockit.Expectations;
import mockit.Mocked;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashMap;

import static com.starrocks.server.CatalogMgr.ResourceMappingCatalog.toResourceName;
import static com.starrocks.sql.analyzer.AnalyzeTestUtil.getStarRocksAssert;

public class HiveTableSinkTest {
    @BeforeAll
    public static void beforeClass() throws Exception {
        UtFrameUtils.createMinStarRocksCluster();
        AnalyzeTestUtil.init();
        StarRocksAssert starRocksAssert = getStarRocksAssert();
        String createHiveCatalogStmt = "create external catalog hive_catalog properties (\"type\"=\"hive\", " +
                "\"hive.metastore.uris\"=\"thrift://hms:9083\")";
        starRocksAssert.withCatalog(createHiveCatalogStmt);
    }

    @Test
    public void testHiveTableSink(@Mocked CatalogConnector hiveConnector) {
        HiveTable.Builder builder = HiveTable.builder()
                .setId(ConnectorTableId.CONNECTOR_ID_GENERATOR.getNextId().asInt())
                .setTableName("hive_table")
                .setCatalogName("hive_catalog")
                .setResourceName(toResourceName("hive_catalog", "hive"))
                .setHiveDbName("hive_db")
                .setHiveTableName("hive_table")
                .setPartitionColumnNames(Lists.newArrayList("p1"))
                .setDataColumnNames(Lists.newArrayList("c1"))
                .setFullSchema(Lists.newArrayList(new Column("c1", Type.INT), new Column("p1", Type.INT)))
                .setTableLocation("hdfs://hadoop01:9000/tableLocation")
                .setProperties(new HashMap<>())
                .setStorageFormat(HiveStorageFormat.PARQUET)
                .setCreateTime(System.currentTimeMillis());

        new Expectations() {
            {
                CloudConfiguration cloudConfig = new CloudConfiguration();
                cloudConfig.loadCommonFields(new HashMap<>());
                hiveConnector.getMetadata().getCloudConfiguration();
                result = cloudConfig;
                minTimes = 1;
            }
        };

        ConnectorMgr connectorMgr = AnalyzeTestUtil.getConnectContext().getGlobalStateMgr().getConnectorMgr();
        new Expectations(connectorMgr) {
            {
                connectorMgr.getConnector("hive_catalog");
                result = hiveConnector;
                minTimes = 1;
            }
        };

        TupleDescriptor desc = new TupleDescriptor(new TupleId(0));
        HiveTableSink sink = new HiveTableSink(builder.build(), desc, true, new SessionVariable());
        Assertions.assertNull(sink.getExchNodeId());
        Assertions.assertNull(sink.getOutputPartition());
        Assertions.assertNull(sink.getOutputPartition());
        Assertions.assertTrue(sink.canUsePipeLine());
        Assertions.assertTrue(sink.canUseRuntimeAdaptiveDop());
        Assertions.assertTrue(sink.getStagingDir().contains("/tmp/starrocks"));
        Assertions.assertTrue(sink.getExplainString("SINK", TExplainLevel.NORMAL).contains(
                "SINKHive TABLE SINK\n" +
                        "SINK  TABLE: hive_catalog.hive_db.hive_table"));
        TDataSink tDataSink = sink.toThrift();
        Assertions.assertEquals(TDataSinkType.HIVE_TABLE_SINK, tDataSink.getType());
        THiveTableSink tHiveTableSink = tDataSink.getHive_table_sink();
        Assertions.assertTrue(tHiveTableSink.getStaging_dir().startsWith("hdfs://hadoop01:9000/tmp/starrocks"));
        Assertions.assertEquals("parquet", tHiveTableSink.getFile_format());
        Assertions.assertEquals("c1", tHiveTableSink.getData_column_names().get(0));
        Assertions.assertEquals("p1", tHiveTableSink.getPartition_column_names().get(0));
        Assertions.assertEquals(TCompressionType.NO_COMPRESSION, tHiveTableSink.getCompression_type());
        Assertions.assertTrue(tHiveTableSink.is_static_partition_sink);
        Assertions.assertEquals(TCloudType.DEFAULT, tHiveTableSink.getCloud_configuration().cloud_type);

        builder.setStorageFormat(HiveStorageFormat.AVRO);
        ExceptionChecker.expectThrowsWithMsg(StarRocksConnectorException.class,
                "Writing to hive table in [AVRO] format is not supported",
                () ->new HiveTableSink(builder.build(), desc, true, new SessionVariable()));
    }
}
