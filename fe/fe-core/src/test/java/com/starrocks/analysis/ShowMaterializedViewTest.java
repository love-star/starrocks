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
//   https://github.com/apache/incubator-doris/blob/master/fe/fe-core/src/test/java/org/apache/doris/analysis/ShowMaterializedViewTest.java

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

package com.starrocks.analysis;

import com.starrocks.catalog.Column;
import com.starrocks.catalog.Table;
import com.starrocks.catalog.system.information.MaterializedViewsSystemTable;
import com.starrocks.qe.ConnectContext;
import com.starrocks.sql.analyzer.AstToStringBuilder;
import com.starrocks.sql.analyzer.SemanticException;
import com.starrocks.sql.ast.ShowMaterializedViewsStmt;
import com.starrocks.utframe.StarRocksAssert;
import com.starrocks.utframe.UtFrameUtils;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.platform.commons.util.Preconditions;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;

public class ShowMaterializedViewTest {

    private static final String TEST_DB_NAME = "db_show_materialized_view";
    private static ConnectContext ctx;
    private static StarRocksAssert starRocksAssert;

    @BeforeAll
    public static void setUp() throws Exception {
        UtFrameUtils.createMinStarRocksCluster();
        ctx = UtFrameUtils.createDefaultCtx();
        starRocksAssert = new StarRocksAssert(ctx);
    }

    @Test
    public void testNormal() throws Exception {
        ctx.setDatabase("testDb");

        ShowMaterializedViewsStmt stmt = new ShowMaterializedViewsStmt(null, "");

        com.starrocks.sql.analyzer.Analyzer.analyze(stmt, ctx);
        Assertions.assertEquals("testDb", stmt.getDb());
        checkShowMaterializedViewsStmt(stmt);

        stmt = new ShowMaterializedViewsStmt(null, "abc", (String) null);
        com.starrocks.sql.analyzer.Analyzer.analyze(stmt, ctx);
        Assertions.assertEquals("abc", stmt.getDb());
        checkShowMaterializedViewsStmt(stmt);

        stmt = new ShowMaterializedViewsStmt(null, "abc", "bcd");
        com.starrocks.sql.analyzer.Analyzer.analyze(stmt, ctx);
        Assertions.assertEquals("bcd", stmt.getPattern());
        Assertions.assertEquals("abc", stmt.getDb());
        checkShowMaterializedViewsStmt(stmt);

        stmt = (ShowMaterializedViewsStmt) UtFrameUtils.parseStmtWithNewParser(
                "SHOW MATERIALIZED VIEWS FROM abc where name = 'mv1';", ctx);
        Preconditions.notNull(stmt.toSelectStmt().getOrigStmt(), "stmt's original stmt should not be null");

        Assertions.assertEquals("abc", stmt.getDb());
        Assertions.assertEquals(
                "SELECT information_schema.materialized_views.MATERIALIZED_VIEW_ID AS id, " +
                        "information_schema.materialized_views.TABLE_SCHEMA AS database_name, " +
                        "information_schema.materialized_views.TABLE_NAME AS name, " +
                        "information_schema.materialized_views.refresh_type AS refresh_type, " +
                        "information_schema.materialized_views.is_active AS is_active, " +
                        "information_schema.materialized_views.inactive_reason AS inactive_reason, " +
                        "information_schema.materialized_views.partition_type AS partition_type, " +
                        "information_schema.materialized_views.task_id AS task_id, " +
                        "information_schema.materialized_views.task_name AS task_name, " +
                        "information_schema.materialized_views.last_refresh_start_time AS last_refresh_start_time, " +
                        "information_schema.materialized_views.last_refresh_finished_time AS last_refresh_finished_time, " +
                        "information_schema.materialized_views.last_refresh_duration AS last_refresh_duration, " +
                        "information_schema.materialized_views.last_refresh_state AS last_refresh_state, " +
                        "information_schema.materialized_views.last_refresh_force_refresh AS last_refresh_force_refresh, " +
                        "information_schema.materialized_views.last_refresh_start_partition AS last_refresh_start_partition, " +
                        "information_schema.materialized_views.last_refresh_end_partition AS last_refresh_end_partition, " +
                        "information_schema.materialized_views.last_refresh_base_refresh_partitions " +
                        "AS last_refresh_base_refresh_partitions, " +
                        "information_schema.materialized_views.last_refresh_mv_refresh_partitions " +
                        "AS last_refresh_mv_refresh_partitions, " +
                        "information_schema.materialized_views.last_refresh_error_code AS last_refresh_error_code, " +
                        "information_schema.materialized_views.last_refresh_error_message AS last_refresh_error_message, " +
                        "information_schema.materialized_views.TABLE_ROWS AS rows, " +
                        "information_schema.materialized_views.MATERIALIZED_VIEW_DEFINITION AS text, " +
                        "information_schema.materialized_views.extra_message AS extra_message, " +
                        "information_schema.materialized_views.query_rewrite_status AS query_rewrite_status, " +
                        "information_schema.materialized_views.creator AS creator, " +
                        "information_schema.materialized_views.last_refresh_process_time AS last_refresh_process_time, " +
                        "information_schema.materialized_views.last_refresh_job_id AS last_refresh_job_id" +
                        " FROM " +
                        "information_schema.materialized_views " +
                        "WHERE (information_schema.materialized_views.TABLE_SCHEMA = 'abc') AND " +
                        "(information_schema.materialized_views.TABLE_NAME = 'mv1')",
                AstToStringBuilder.toString(stmt.toSelectStmt()));
        checkShowMaterializedViewsStmt(stmt);
    }

    private void checkShowMaterializedViewsStmt(ShowMaterializedViewsStmt stmt) {
        Table schemaMVTable = MaterializedViewsSystemTable.create();
        Assertions.assertEquals(schemaMVTable.getBaseSchema().size(), stmt.getMetaData().getColumnCount());

        List<Column> schemaCols = schemaMVTable.getFullSchema();
        for (int i = 0; i < schemaCols.size(); i++) {
            if (schemaCols.get(i).getName().equalsIgnoreCase("MATERIALIZED_VIEW_ID")) {
                Assertions.assertEquals("id", stmt.getMetaData().getColumn(i).getName());
            } else if (schemaCols.get(i).getName().equalsIgnoreCase("TABLE_SCHEMA")) {
                Assertions.assertEquals("database_name", stmt.getMetaData().getColumn(i).getName());
            } else if (schemaCols.get(i).getName().equalsIgnoreCase("TABLE_NAME")) {
                Assertions.assertEquals("name", stmt.getMetaData().getColumn(i).getName());
            } else if (schemaCols.get(i).getName().equalsIgnoreCase("MATERIALIZED_VIEW_DEFINITION")) {
                Assertions.assertEquals("text", stmt.getMetaData().getColumn(i).getName());
            } else if (schemaCols.get(i).getName().equalsIgnoreCase("TABLE_ROWS")) {
                Assertions.assertEquals("rows", stmt.getMetaData().getColumn(i).getName());
            } else {
                Assertions.assertEquals(schemaCols.get(i).getName().toLowerCase(), stmt.getMetaData().getColumn(i).getName());
            }
        }
    }

    @Test
    public void testNoDb() {
        assertThrows(SemanticException.class, () -> {
            ctx = UtFrameUtils.createDefaultCtx();
            ShowMaterializedViewsStmt stmt = new ShowMaterializedViewsStmt(null, "");
            com.starrocks.sql.analyzer.Analyzer.analyze(stmt, ctx);
            Assertions.fail("No exception throws");
        });
    }

    @Test
    public void testCatalogName() throws Exception {
        ShowMaterializedViewsStmt stmt = new ShowMaterializedViewsStmt("default_catalog", "testDb");

        com.starrocks.sql.analyzer.Analyzer.analyze(stmt, ctx);
        Assertions.assertEquals("testDb", stmt.getDb());
        Assertions.assertEquals("default_catalog", stmt.getCatalogName());
        checkShowMaterializedViewsStmt(stmt);
        stmt = (ShowMaterializedViewsStmt) UtFrameUtils.parseStmtWithNewParser(
                "SHOW MATERIALIZED VIEWS FROM default_catalog.testDb;", ctx);

        Assertions.assertEquals("testDb", stmt.getDb());
        Assertions.assertEquals("default_catalog", stmt.getCatalogName());
    }

    @Test
    public void testUnknownCatalog() {
        assertThrows(SemanticException.class, () -> {
            ctx = UtFrameUtils.createDefaultCtx();
            ShowMaterializedViewsStmt stmt = new ShowMaterializedViewsStmt("unknown_catalog", "testDb");
            com.starrocks.sql.analyzer.Analyzer.analyze(stmt, ctx);
            Assertions.fail("No exception throws");
        });
    }
}
