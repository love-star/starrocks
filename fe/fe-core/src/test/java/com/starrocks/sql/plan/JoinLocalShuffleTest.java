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

package com.starrocks.sql.plan;

import com.starrocks.common.FeConstants;
import com.starrocks.qe.SessionVariable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

public class JoinLocalShuffleTest extends PlanTestBase {

    @BeforeAll
    public static void beforeClass() throws Exception {
        PlanTestBase.beforeClass();
        FeConstants.showJoinLocalShuffleInExplain = true;
    }

    @AfterAll
    public static void afterClass() {
        PlanTestBase.afterClass();
        FeConstants.showJoinLocalShuffleInExplain = false;
    }

    // @Test
    public void joinWithAgg() throws Exception {
        SessionVariable sv = connectContext.getSessionVariable();
        String sql = "select sum(v1), sum(v2), sum(v4), v5 from t0 join t1 on t0.v3 = t1.v6 group by v5";
        {
            sv.setNewPlanerAggStage(1);
            String plan = getVerboseExplain(sql);
            assertContains(plan, "  |  can local shuffle: false");
        }
        {
            sv.setNewPlanerAggStage(2);
            String plan = getVerboseExplain(sql);
            assertContains(plan, "  |  can local shuffle: true");
        }
        sv.setNewPlanerAggStage(0);
    }

    @Test
    public void joinUnderExchange() throws Exception {
        SessionVariable sv = connectContext.getSessionVariable();
        sv.setInterpolatePassthrough(true);
        String sql = "select l.* from t0 l join [shuffle] t1 on upper(v1) = v5 join [shuffle] t2 on lower(v1) = v9";
        String plan = getVerboseExplain(sql);
        assertContains(plan, "can local shuffle: true");
    }
}
