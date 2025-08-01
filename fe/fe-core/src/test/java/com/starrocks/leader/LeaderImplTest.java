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


package com.starrocks.leader;

import com.google.common.collect.Sets;
import com.starrocks.catalog.LocalTablet;
import com.starrocks.catalog.MaterializedIndex;
import com.starrocks.catalog.OlapTable;
import com.starrocks.catalog.PhysicalPartition;
import com.starrocks.catalog.Replica;
import com.starrocks.common.jmockit.Deencapsulation;
import com.starrocks.lake.LakeTable;
import com.starrocks.lake.LakeTablet;
import mockit.Expectations;
import mockit.Mock;
import mockit.MockUp;
import mockit.Mocked;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static com.starrocks.catalog.Replica.ReplicaState.NORMAL;

public class LeaderImplTest {

    private long dbId;
    private String dbName;
    private String tableName;
    private long tableId;
    private long partitionId;
    private long indexId;
    private long tabletId;
    private long replicaId;
    private long backendId;

    private final LeaderImpl leader = new LeaderImpl();

    @BeforeEach
    public void setUp() {
        dbId = 1L;
        dbName = "database0";
        tableName = "table0";
        tableId = 10L;
        partitionId = 11L;
        indexId = 12L;
        tabletId = 13L;
        replicaId = 14L;
        backendId = 15L;
    }

    @Test
    public void testFindRelatedReplica(@Mocked OlapTable olapTable, @Mocked LakeTable lakeTable,
                                       @Mocked PhysicalPartition physicalPartition, @Mocked MaterializedIndex index
                                       ) throws Exception {

        // olap table
        new Expectations() {
            {
                physicalPartition.getIndex(indexId);
                result = index;
                index.getTablet(tabletId);
                result = new LocalTablet(tabletId);
            }
        };
        
        Assertions.assertNull(Deencapsulation.invoke(leader, "findRelatedReplica",
                olapTable, physicalPartition, backendId, tabletId, indexId));
        // lake table
        new MockUp<LakeTablet>() {
            @Mock
            public Set<Long> getBackendIds() {
                return Sets.newHashSet();
            }
        };

        new Expectations() {
            {
                physicalPartition.getIndex(indexId);
                result = index;
                index.getTablet(tabletId);
                result = new LakeTablet(tabletId);
            }
        };

        Assertions.assertEquals(new Replica(tabletId, backendId, -1, NORMAL), Deencapsulation.invoke(leader, "findRelatedReplica",
                olapTable, physicalPartition, backendId, tabletId, indexId));
    }
}
