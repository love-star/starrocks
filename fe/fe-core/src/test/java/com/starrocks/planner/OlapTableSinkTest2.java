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

import com.starrocks.catalog.Database;
import com.starrocks.catalog.OlapTable;
import com.starrocks.catalog.Partition;
import com.starrocks.catalog.PartitionInfo;
import com.starrocks.catalog.PhysicalPartition;
import com.starrocks.common.StarRocksException;
import com.starrocks.qe.ConnectContext;
import com.starrocks.server.GlobalStateMgr;
import com.starrocks.sql.ast.UserIdentity;
import com.starrocks.thrift.TOlapTablePartition;
import com.starrocks.thrift.TOlapTablePartitionParam;
import com.starrocks.thrift.TWriteQuorumType;
import com.starrocks.utframe.StarRocksAssert;
import com.starrocks.utframe.UtFrameUtils;
import mockit.Mock;
import mockit.MockUp;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

public class OlapTableSinkTest2 {
    private static StarRocksAssert starRocksAssert;
    private static ConnectContext connectContext;

    @BeforeAll
    public static void beforeClass() throws Exception {
        UtFrameUtils.createMinStarRocksCluster();
        String createTblStmtStr = "create table db2.tbl1(k1 varchar(32), k2 varchar(32), k3 varchar(32), k4 int) " +
                "AGGREGATE KEY(k1, k2, k3, k4) distributed by hash(k1) buckets 3 properties('replication_num' = '1');";
        connectContext = UtFrameUtils.initCtxForNewPrivilege(UserIdentity.ROOT);
        starRocksAssert = new StarRocksAssert(connectContext);
        starRocksAssert.withDatabase("db2");
        starRocksAssert.withTable(createTblStmtStr);
    }

    @Test
    public void testCreateLocationException() {
        new MockUp<PartitionInfo>() {
            @Mock
            public int getQuorumNum(long partitionId, TWriteQuorumType writeQuorum) {
                return 3;
            }
        };

        Database db = GlobalStateMgr.getCurrentState().getLocalMetastore().getDb("db2");
        OlapTable olapTable = (OlapTable) GlobalStateMgr.getCurrentState().getLocalMetastore().getTable(db.getFullName(), "tbl1");

        TOlapTablePartitionParam partitionParam = new TOlapTablePartitionParam();
        TOlapTablePartition tPartition = new TOlapTablePartition();
        for (Partition partition : olapTable.getPartitions()) {
            for (PhysicalPartition physicalPartition : partition.getSubPartitions()) {
                tPartition.setId(physicalPartition.getId());
                partitionParam.addToPartitions(tPartition);
            }
        }

        try {
            OlapTableSink.createLocation(olapTable, partitionParam, false);
        } catch (StarRocksException e) {
            System.out.println(e.getMessage());
            Assertions.assertTrue(e.getMessage().contains("replicas: 10001:1/-1/1/0:NORMAL:ALIVE"));
            return;
        }
        Assertions.fail("must throw UserException");
    }
}
