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
package com.starrocks.catalog.system.information;

import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.starrocks.authorization.AccessDeniedException;
import com.starrocks.catalog.Column;
import com.starrocks.catalog.InternalCatalog;
import com.starrocks.catalog.PrimitiveType;
import com.starrocks.catalog.ScalarType;
import com.starrocks.catalog.Table;
import com.starrocks.catalog.Type;
import com.starrocks.catalog.system.SystemId;
import com.starrocks.catalog.system.SystemTable;
import com.starrocks.cluster.ClusterNamespace;
import com.starrocks.common.Config;
import com.starrocks.qe.ConnectContext;
import com.starrocks.scheduler.Task;
import com.starrocks.scheduler.TaskManager;
import com.starrocks.scheduler.persist.TaskRunStatus;
import com.starrocks.server.GlobalStateMgr;
import com.starrocks.sql.analyzer.Authorizer;
import com.starrocks.sql.ast.UserIdentity;
import com.starrocks.sql.optimizer.Utils;
import com.starrocks.sql.optimizer.operator.scalar.BinaryPredicateOperator;
import com.starrocks.sql.optimizer.operator.scalar.ColumnRefOperator;
import com.starrocks.sql.optimizer.operator.scalar.ConstantOperator;
import com.starrocks.sql.optimizer.operator.scalar.ScalarOperator;
import com.starrocks.thrift.TGetTaskRunInfoResult;
import com.starrocks.thrift.TGetTasksParams;
import com.starrocks.thrift.TSchemaTableType;
import com.starrocks.thrift.TTaskRunInfo;
import com.starrocks.thrift.TUserIdentity;
import org.apache.commons.lang3.NotImplementedException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.util.Strings;
import org.apache.thrift.meta_data.FieldValueMetaData;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

public class TaskRunsSystemTable extends SystemTable {
    private static final Logger LOG = LogManager.getLogger(SystemTable.class);

    private static final SystemTable TABLE = new TaskRunsSystemTable();

    public static final String NAME = "task_runs";

    public static SystemTable getInstance() {
        return TABLE;
    }

    public TaskRunsSystemTable() {
        super(SystemId.TASK_RUNS_ID,
                NAME,
                Table.TableType.SCHEMA,
                builder()
                        .column("QUERY_ID", ScalarType.createVarchar(64))
                        .column("TASK_NAME", ScalarType.createVarchar(64))
                        .column("CREATE_TIME", ScalarType.createType(PrimitiveType.DATETIME))
                        .column("FINISH_TIME", ScalarType.createType(PrimitiveType.DATETIME))
                        .column("STATE", ScalarType.createVarchar(16))
                        .column("CATALOG", ScalarType.createVarchar(64))
                        .column("DATABASE", ScalarType.createVarchar(64))
                        .column("DEFINITION", ScalarType.createVarchar(MAX_FIELD_VARCHAR_LENGTH))
                        .column("EXPIRE_TIME", ScalarType.createType(PrimitiveType.DATETIME))
                        .column("ERROR_CODE", ScalarType.createType(PrimitiveType.BIGINT))
                        .column("ERROR_MESSAGE", ScalarType.createVarchar(MAX_FIELD_VARCHAR_LENGTH))
                        .column("PROGRESS", ScalarType.createVarchar(64))
                        .column("EXTRA_MESSAGE", ScalarType.createVarchar(8192))
                        .column("PROPERTIES", ScalarType.createVarcharType(512))
                        .column("JOB_ID", ScalarType.createVarcharType(64))
                        .column("PROCESS_TIME", ScalarType.createType(PrimitiveType.DATETIME))
                        .build(), TSchemaTableType.SCH_TASK_RUNS);
    }

    private static final Set<String> SUPPORTED_EQUAL_COLUMNS =
            Collections.unmodifiableSet(new TreeSet<>(String.CASE_INSENSITIVE_ORDER) {
                {
                    add("QUERY_ID");
                    add("TASK_NAME");
                }
            });

    @Override
    public boolean supportFeEvaluation(ScalarOperator predicate) {
        if (!Config.enable_task_run_fe_evaluation) {
            return false;
        }
        final List<ScalarOperator> conjuncts = Utils.extractConjuncts(predicate);
        if (!isOnlyEqualConstantOps(conjuncts)) {
            return false;
        }
        // only support QUERY_ID and TASK_NAME
        return isSupportedEqualPredicateColumn(conjuncts, SUPPORTED_EQUAL_COLUMNS);
    }

    @Override
    public List<List<ScalarOperator>> evaluate(ScalarOperator predicate) {
        return evaluate(Utils.extractConjuncts(predicate));
    }

    public static List<List<ScalarOperator>> evaluate(List<ScalarOperator> conjuncts) {
        // Build a Params
        TGetTasksParams params = new TGetTasksParams();
        for (ScalarOperator conjunct : conjuncts) {
            BinaryPredicateOperator binary = (BinaryPredicateOperator) conjunct;
            ColumnRefOperator columnRef = binary.getChild(0).cast();
            String name = columnRef.getName();
            ConstantOperator value = binary.getChild(1).cast();
            switch (name.toUpperCase()) {
                case "QUERY_ID":
                    params.setQuery_id(value.getVarchar());
                    break;
                case "TASK_NAME":
                    params.setTask_name(value.getVarchar());
                    break;
                default:
                    throw new NotImplementedException("unsupported column: " + name);
            }
        }

        ConnectContext context = Preconditions.checkNotNull(ConnectContext.get(), "not a valid connection");
        TUserIdentity userIdentity = context.getCurrentUserIdentity().toThrift();
        params.setCurrent_user_ident(userIdentity);
        // Evaluate result
        TGetTaskRunInfoResult info = query(params);
        return info.getTask_runs().stream().map(TaskRunsSystemTable::infoToScalar).collect(Collectors.toList());
    }

    private static List<ScalarOperator> infoToScalar(TTaskRunInfo info) {
        List<ScalarOperator> result = Lists.newArrayList();
        for (Column column : TABLE.getBaseSchema()) {
            String name = column.getName().toLowerCase();
            TTaskRunInfo._Fields field = TTaskRunInfo._Fields.findByName(name);
            FieldValueMetaData meta = TTaskRunInfo.metaDataMap.get(field).valueMetaData;
            Object obj = info.getFieldValue(field);
            Type valueType = thriftToScalarType(meta.type);
            ConstantOperator scalar = ConstantOperator.createNullableObject(obj, valueType);
            scalar = mayCast(scalar, column.getType());
            result.add(scalar);
        }
        return result;
    }

    public static TGetTaskRunInfoResult query(TGetTasksParams params) {
        TGetTaskRunInfoResult result = new TGetTaskRunInfoResult();
        List<TTaskRunInfo> tasksResult = Lists.newArrayList();
        result.setTask_runs(tasksResult);

        UserIdentity currentUser = null;
        if (params.isSetCurrent_user_ident()) {
            currentUser = UserIdentity.fromThrift(params.current_user_ident);
        }
        GlobalStateMgr globalStateMgr = GlobalStateMgr.getCurrentState();
        TaskManager taskManager = globalStateMgr.getTaskManager();
        List<TaskRunStatus> taskRunList = taskManager.getMatchedTaskRunStatus(params);

        for (TaskRunStatus status : taskRunList) {
            if (status.getDbName() == null) {
                LOG.warn("Ignore the task status because db information is incorrect: " + status);
                continue;
            }

            try {
                ConnectContext context = new ConnectContext();
                context.setCurrentUserIdentity(currentUser);
                context.setCurrentRoleIds(currentUser);
                Authorizer.checkAnyActionOnOrInDb(context, InternalCatalog.DEFAULT_INTERNAL_CATALOG_NAME,
                        status.getDbName());
            } catch (AccessDeniedException e) {
                continue;
            }

            String taskName = status.getTaskName();
            TTaskRunInfo info = new TTaskRunInfo();
            info.setQuery_id(status.getQueryId());
            info.setTask_name(taskName);
            info.setCreate_time(status.getCreateTime() / 1000);
            info.setFinish_time(status.getFinishTime() / 1000);
            info.setState(status.getState().toString());
            info.setCatalog(status.getCatalogName());
            info.setDatabase(ClusterNamespace.getNameFromFullName(status.getDbName()));
            if (!Strings.isEmpty(status.getDefinition())) {
                info.setDefinition(status.getDefinition());
            } else {
                try {
                    // NOTE: use task's definition to display task-run's definition here
                    Task task = taskManager.getTaskWithoutLock(taskName);
                    if (task != null) {
                        info.setDefinition(task.getDefinition());
                    }
                } catch (Exception e) {
                    LOG.warn("Get taskName {} definition failed: {}", taskName, e);
                }
            }
            info.setError_code(status.getErrorCode());
            info.setError_message(status.getErrorMessage());
            info.setExpire_time(status.getExpireTime() / 1000);
            info.setProgress(status.getProgress() + "%");
            info.setExtra_message(status.getExtraMessage());
            info.setProperties(status.getPropertiesJson());
            info.setProcess_time(status.getProcessStartTime() / 1000);
            info.setJob_id(status.getStartTaskRunId());
            tasksResult.add(info);
        }
        return result;
    }
}
