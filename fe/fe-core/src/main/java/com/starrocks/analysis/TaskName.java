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


package com.starrocks.analysis;

import com.starrocks.sql.parser.NodePosition;
import static com.starrocks.common.util.Util.normalizeName;

public class TaskName {

    private String dbName;

    private String name;

    private final NodePosition pos;

    public TaskName(String dbName, String name, NodePosition pos) {
        this.dbName = normalizeName(dbName);
        this.name = name;
        this.pos = pos;
    }

    public String getDbName() {
        return dbName;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }
}
