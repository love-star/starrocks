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

package com.starrocks.pseudocluster;

import com.staros.proto.AwsCredentialInfo;
import com.staros.proto.AwsDefaultCredentialInfo;
import com.staros.proto.FilePathInfo;
import com.staros.proto.FileStoreInfo;
import com.staros.proto.FileStoreType;
import com.staros.proto.S3FileStoreInfo;
import com.starrocks.catalog.Database;
import com.starrocks.catalog.OlapTable;
import com.starrocks.catalog.PhysicalPartition;
import com.starrocks.common.AnalysisException;
import com.starrocks.common.Config;
import com.starrocks.common.DdlException;
import com.starrocks.common.ErrorCode;
import com.starrocks.common.StarRocksException;
import com.starrocks.lake.StarOSAgent;
import com.starrocks.server.GlobalStateMgr;
import com.starrocks.server.LocalMetastore;
import com.starrocks.server.RunMode;
import com.starrocks.server.SharedNothingStorageVolumeMgr;
import com.starrocks.storagevolume.StorageVolume;
import mockit.Mock;
import mockit.MockUp;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class PseudoClusterTest {
    @BeforeAll
    public static void setUp() throws Exception {
        PseudoCluster.getOrCreateWithRandomPort(true, 3);
    }

    @AfterAll
    public static void tearDown() throws Exception {
        PseudoCluster.getInstance().shutdown(true);
    }

    @Test
    public void testCreateTabletAndInsert() throws Exception {
        Connection connection = PseudoCluster.getInstance().getQueryConnection();
        Statement stmt = connection.createStatement();
        try {
            stmt.execute("create database test");
            stmt.execute("use test");
            stmt.execute("create table test ( pk bigint NOT NULL, v0 string not null, v1 int not null ) " +
                    "primary KEY (pk) DISTRIBUTED BY HASH(pk) BUCKETS 3 " +
                    "PROPERTIES(\"replication_num\" = \"3\", \"storage_medium\" = \"SSD\")");
            Assertions.assertFalse(stmt.execute("insert into test values (1,\"1\", 1), (2,\"2\",2), (3,\"3\",3)"));
            stmt.execute("select * from test");
        } finally {
            stmt.close();
            connection.close();
        }
    }

    @Test
    public void testCreateColumnWithRowTable() throws Exception {
        Config.enable_experimental_rowstore = true;
        Connection connection = PseudoCluster.getInstance().getQueryConnection();
        Statement stmt = connection.createStatement();
        try {
            stmt.execute("create database test_column_with_row");
            stmt.execute("use test_column_with_row");
            try {
                stmt.execute("create table test1 ( pk bigint NOT NULL) " +
                        "primary KEY (pk) DISTRIBUTED BY HASH(pk) BUCKETS 7 " +
                        "PROPERTIES(\"replication_num\" = \"3\", \"storage_medium\" = \"SSD\", " +
                        "\"storage_type\" = \"column_with_row\")");
                Assertions.fail("should throw exception");
            } catch (Exception e) {
                Assertions.assertTrue(e.getMessage().contains("column_with_row storage type must have some non-key columns"));
            }
            stmt.execute("create table test2 ( pk bigint NOT NULL, v1 array<int> NOT NULL, v2 bitmap NOT NULL) " +
                    "primary KEY (pk) DISTRIBUTED BY HASH(pk) BUCKETS 7 " +
                    "PROPERTIES(\"replication_num\" = \"3\", \"storage_medium\" = \"SSD\", " +
                    "\"storage_type\" = \"column_with_row\")");
            stmt.execute("create table test3 ( pk bigint NOT NULL, v int NOT NULL) " +
                    "primary KEY (pk) DISTRIBUTED BY HASH(pk) BUCKETS 7 " +
                    "PROPERTIES(\"replication_num\" = \"3\", \"storage_medium\" = \"SSD\", " +
                    "\"storage_type\" = \"column_with_row\")");
        } finally {
            stmt.close();
            connection.close();
        }
    }

    @Test
    public void testPreparedStatement() throws Exception {
        Connection connection = PseudoCluster.getInstance().getQueryConnection();
        Statement stmt = connection.createStatement();
        try {
            stmt.execute("create database if not exists test_prepared_stmt");
            stmt.execute("use test_prepared_stmt");
            stmt.execute("create table if not exists test ( pk bigint NOT NULL, v1 int not null ) " +
                    "primary KEY (pk) DISTRIBUTED BY HASH(pk) BUCKETS 1 " +
                    "PROPERTIES(\"replication_num\" = \"3\", \"storage_medium\" = \"SSD\")");
            stmt.execute("prepare stmt1 from select * from test where pk = ?");
            stmt.execute("prepare stmt3 from select 1");
            stmt.execute("set @i = 1");
            stmt.execute("execute stmt1 using @i");
            stmt.execute("execute stmt1 using @i");
            stmt.execute("execute stmt3");
            stmt.execute("select * from test where pk = ?", 1);

            {
                SQLException e = Assertions.assertThrows(SQLException.class,
                        () -> stmt.execute("prepare stmt2 from insert overwrite test values (1,2)"));
                Assertions.assertTrue(e.getMessage().contains("Getting analyzing error. Detail message: This command is not " +
                        "supported in the prepared statement protocol yet."));
                Assertions.assertEquals(ErrorCode.ERR_UNSUPPORTED_PS.getCode(), e.getErrorCode());
                Assertions.assertTrue(e.getMessage().contains(ErrorCode.ERR_UNSUPPORTED_PS.formatErrorMsg()));
            }
            {
                SQLException e = Assertions.assertThrows(SQLException.class,
                        () -> stmt.execute("prepare stmt2 from ALTER USER 'root'@'%' IDENTIFIED BY 'XXXXX' "));
                Assertions.assertTrue(e.getMessage().contains("Getting analyzing error. Detail message: This command is not " +
                        "supported in the prepared statement protocol yet."));
                Assertions.assertEquals(ErrorCode.ERR_UNSUPPORTED_PS.getCode(), e.getErrorCode());
                Assertions.assertTrue(e.getMessage().contains(ErrorCode.ERR_UNSUPPORTED_PS.formatErrorMsg()));
            }

            // client prepared stmt
            PreparedStatement pstmt = connection.prepareStatement("select * from test where pk = ?");
            pstmt.setInt(1, 1);
            ResultSet rs = pstmt.executeQuery();
            pstmt.close();
        } finally {
            stmt.close();
            connection.close();
        }
    }

    @Test
    public void testPreparedStatementServer() throws Exception {
        Connection connection = PseudoCluster.getInstance().getQueryConnection();
        Statement stmt = connection.createStatement();
        try {
            stmt.execute("create database if not exists test_prepared_stmt");
            stmt.execute("use test_prepared_stmt");
            stmt.execute("create table if not exists test ( pk bigint NOT NULL, v1 int not null ) " +
                    "primary KEY (pk) DISTRIBUTED BY HASH(pk) BUCKETS 1 " +
                    "PROPERTIES(\"replication_num\" = \"3\", \"storage_medium\" = \"SSD\")");
            stmt.execute("prepare stmt1 from select * from test where pk = ?");
            stmt.execute("prepare stmt3 from select 1");
            stmt.execute("set @i = 1");
            stmt.execute("execute stmt1 using @i");
            stmt.execute("execute stmt1 using @i");
            stmt.execute("execute stmt3");
            stmt.execute("select * from test where pk = ?", 1);
            try {
                stmt.execute("prepare stmt2 from insert overwrite test values (1,2)");
                Assertions.fail("expected exception was not occured.");
            } catch (SQLException e) {
                Assertions.assertEquals(ErrorCode.ERR_UNSUPPORTED_PS.getCode(), e.getErrorCode());
                Assertions.assertTrue(e.getMessage().contains(ErrorCode.ERR_UNSUPPORTED_PS.formatErrorMsg()), e.toString());
            }

            // client prepared stmt
            PreparedStatement pstmt = connection.prepareStatement("select * from test where pk = ?");
            pstmt.setInt(1, 1);
            pstmt.executeQuery();
            pstmt.close();
        } finally {
            stmt.close();
            connection.close();
        }
    }

    @Test
    public void testPreparedStatementWitchServer() throws Exception {
        PseudoCluster.getInstance().setServerPrepareStatement();
        Connection connection = PseudoCluster.getInstance().getQueryConnection();
        Statement stmt = connection.createStatement();
        try {
            stmt.execute("create database if not exists test_prepared_stmt");
            stmt.execute("use test_prepared_stmt");
            stmt.execute("create table if not exists test ( pk bigint NOT NULL, v1 int not null ) " +
                    "primary KEY (pk) DISTRIBUTED BY HASH(pk) BUCKETS 1 " +
                    "PROPERTIES(\"replication_num\" = \"3\", \"storage_medium\" = \"SSD\")");
            // client prepared stmt
            PreparedStatement pstmt = connection.prepareStatement("select * from test where pk = ?");
            pstmt.setInt(1, 1);
            pstmt.executeQuery();
            pstmt.close();
        } finally {
            stmt.close();
            connection.close();
        }
    }

    @Test
    public void testCreateLakeTable() throws Exception {
        new MockUp<RunMode>() {
            @Mock
            public RunMode getCurrentRunMode() {
                return RunMode.SHARED_DATA;
            }
        };

        FilePathInfo.Builder builder = FilePathInfo.newBuilder();
        FileStoreInfo.Builder fsBuilder = builder.getFsInfoBuilder();

        S3FileStoreInfo.Builder s3FsBuilder = fsBuilder.getS3FsInfoBuilder();
        s3FsBuilder.setBucket("test-bucket");
        s3FsBuilder.setRegion("test-region");
        s3FsBuilder.setCredential(AwsCredentialInfo.newBuilder()
                .setDefaultCredential(AwsDefaultCredentialInfo.newBuilder().build()));
        S3FileStoreInfo s3FsInfo = s3FsBuilder.build();

        fsBuilder.setFsType(FileStoreType.S3);
        fsBuilder.setFsKey("test-bucket");
        fsBuilder.setFsName("test-fsname");
        fsBuilder.setS3FsInfo(s3FsInfo);
        FileStoreInfo fsInfo = fsBuilder.build();

        builder.setFsInfo(fsInfo);
        builder.setFullPath("s3://test-bucket/1/");
        FilePathInfo pathInfo = builder.build();

        new MockUp<StarOSAgent>() {
            @Mock
            public long getPrimaryComputeNodeIdByShard(long shardId) throws StarRocksException {
                return GlobalStateMgr.getCurrentState().getNodeMgr().getClusterInfo().getBackendIds(true).get(0);
            }

            @Mock
            public long getPrimaryComputeNodeIdByShard(long shardId, long workerGroupId) throws StarRocksException {
                return GlobalStateMgr.getCurrentState().getNodeMgr().getClusterInfo().getBackendIds(true).get(0);
            }

            @Mock
            public List<Long> getAllNodeIdsByShard(long shardId, long workerGroupId) {
                return GlobalStateMgr.getCurrentState().getNodeMgr().getClusterInfo().getBackendIds(true);
            }

            @Mock
            public FilePathInfo allocateFilePath(String storageVolumeId, long dbId, long tableId) throws DdlException {
                return pathInfo;
            }

            @Mock
            public List<Long> getWorkersByWorkerGroup(long workerGroupId) throws StarRocksException {
                // the worker id is a random number
                return new ArrayList<>(Arrays.asList(10001L));
            }
        };

        new MockUp<SharedNothingStorageVolumeMgr>() {
            @Mock
            public StorageVolume getStorageVolume(String svKey) throws AnalysisException, DdlException {
                return StorageVolume.fromFileStoreInfo(fsInfo);
            }

            @Mock
            public String getStorageVolumeIdOfTable(long tableId) {
                return fsInfo.getFsKey();
            }
        };

        new MockUp<LocalMetastore>() {
            @Mock
            void buildPartitions(Database db, OlapTable table, List<PhysicalPartition> partitions, long warehouseId)
                    throws DdlException {
                return;
            }
        };

        Connection connection = PseudoCluster.getInstance().getQueryConnection();
        Statement stmt = connection.createStatement();
        try {
            stmt.execute("create database db_test_lake");
            stmt.execute("use db_test_lake");
            stmt.execute("create table test (k0 bigint NOT NULL, v0 string not null, v1 int not null ) " +
                    "duplicate KEY(k0) DISTRIBUTED BY HASH(k0) BUCKETS 7");
        } finally {
            stmt.close();
            connection.close();
        }
    }
}
