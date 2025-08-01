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

package com.starrocks.sql.parser;

import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.starrocks.analysis.AnalyticExpr;
import com.starrocks.analysis.AnalyticWindow;
import com.starrocks.analysis.ArithmeticExpr;
import com.starrocks.analysis.ArrowExpr;
import com.starrocks.analysis.BetweenPredicate;
import com.starrocks.analysis.BinaryPredicate;
import com.starrocks.analysis.BinaryType;
import com.starrocks.analysis.BoolLiteral;
import com.starrocks.analysis.BrokerDesc;
import com.starrocks.analysis.CaseExpr;
import com.starrocks.analysis.CaseWhenClause;
import com.starrocks.analysis.CastExpr;
import com.starrocks.analysis.CollectionElementExpr;
import com.starrocks.analysis.ColumnPosition;
import com.starrocks.analysis.CompoundPredicate;
import com.starrocks.analysis.DateLiteral;
import com.starrocks.analysis.DecimalLiteral;
import com.starrocks.analysis.DictQueryExpr;
import com.starrocks.analysis.ExistsPredicate;
import com.starrocks.analysis.Expr;
import com.starrocks.analysis.FloatLiteral;
import com.starrocks.analysis.FunctionCallExpr;
import com.starrocks.analysis.FunctionName;
import com.starrocks.analysis.FunctionParams;
import com.starrocks.analysis.GroupByClause;
import com.starrocks.analysis.GroupingFunctionCallExpr;
import com.starrocks.analysis.HintNode;
import com.starrocks.analysis.InPredicate;
import com.starrocks.analysis.InformationFunction;
import com.starrocks.analysis.IntLiteral;
import com.starrocks.analysis.IsNullPredicate;
import com.starrocks.analysis.JoinOperator;
import com.starrocks.analysis.LabelName;
import com.starrocks.analysis.LargeIntLiteral;
import com.starrocks.analysis.LikePredicate;
import com.starrocks.analysis.LimitElement;
import com.starrocks.analysis.LiteralExpr;
import com.starrocks.analysis.MatchExpr;
import com.starrocks.analysis.MultiInPredicate;
import com.starrocks.analysis.NamedArgument;
import com.starrocks.analysis.NullLiteral;
import com.starrocks.analysis.OdbcScalarFunctionCall;
import com.starrocks.analysis.OrderByElement;
import com.starrocks.analysis.OutFileClause;
import com.starrocks.analysis.Parameter;
import com.starrocks.analysis.ParseNode;
import com.starrocks.analysis.Predicate;
import com.starrocks.analysis.RoutineLoadDataSourceProperties;
import com.starrocks.analysis.SetVarHint;
import com.starrocks.analysis.SlotRef;
import com.starrocks.analysis.StringLiteral;
import com.starrocks.analysis.SubfieldExpr;
import com.starrocks.analysis.Subquery;
import com.starrocks.analysis.TableName;
import com.starrocks.analysis.TableRef;
import com.starrocks.analysis.TaskName;
import com.starrocks.analysis.TimestampArithmeticExpr;
import com.starrocks.analysis.TypeDef;
import com.starrocks.analysis.UserVariableExpr;
import com.starrocks.analysis.VarBinaryLiteral;
import com.starrocks.analysis.VariableExpr;
import com.starrocks.authentication.UserProperty;
import com.starrocks.authorization.GrantType;
import com.starrocks.catalog.AggregateFunction;
import com.starrocks.catalog.AggregateType;
import com.starrocks.catalog.ArrayType;
import com.starrocks.catalog.Function;
import com.starrocks.catalog.FunctionSet;
import com.starrocks.catalog.KeysType;
import com.starrocks.catalog.MapType;
import com.starrocks.catalog.MaterializedView;
import com.starrocks.catalog.PrimitiveType;
import com.starrocks.catalog.ScalarType;
import com.starrocks.catalog.StructField;
import com.starrocks.catalog.StructType;
import com.starrocks.catalog.Type;
import com.starrocks.catalog.combinator.AggStateDesc;
import com.starrocks.catalog.combinator.AggStateUtils;
import com.starrocks.common.AnalysisException;
import com.starrocks.common.Config;
import com.starrocks.common.CsvFormat;
import com.starrocks.common.DdlException;
import com.starrocks.common.NotImplementedException;
import com.starrocks.common.Pair;
import com.starrocks.common.profile.Tracers;
import com.starrocks.common.util.DateUtils;
import com.starrocks.common.util.TimeUtils;
import com.starrocks.connector.BranchOptions;
import com.starrocks.connector.TagOptions;
import com.starrocks.mysql.MysqlPassword;
import com.starrocks.mysql.privilege.AuthPlugin;
import com.starrocks.qe.ConnectContext;
import com.starrocks.qe.OriginStatement;
import com.starrocks.qe.SqlModeHelper;
import com.starrocks.scheduler.persist.TaskSchedule;
import com.starrocks.server.StorageVolumeMgr;
import com.starrocks.server.WarehouseManager;
import com.starrocks.sql.ShowTemporaryTableStmt;
import com.starrocks.sql.analyzer.AnalyzerUtils;
import com.starrocks.sql.analyzer.FunctionAnalyzer;
import com.starrocks.sql.analyzer.RelationId;
import com.starrocks.sql.analyzer.SemanticException;
import com.starrocks.sql.ast.AbstractBackupStmt;
import com.starrocks.sql.ast.AbstractBackupStmt.BackupObjectType;
import com.starrocks.sql.ast.AddBackendBlackListStmt;
import com.starrocks.sql.ast.AddBackendClause;
import com.starrocks.sql.ast.AddColumnClause;
import com.starrocks.sql.ast.AddColumnsClause;
import com.starrocks.sql.ast.AddComputeNodeBlackListStmt;
import com.starrocks.sql.ast.AddComputeNodeClause;
import com.starrocks.sql.ast.AddFieldClause;
import com.starrocks.sql.ast.AddFollowerClause;
import com.starrocks.sql.ast.AddObserverClause;
import com.starrocks.sql.ast.AddPartitionClause;
import com.starrocks.sql.ast.AddRollupClause;
import com.starrocks.sql.ast.AddSqlBlackListStmt;
import com.starrocks.sql.ast.AdminCancelRepairTableStmt;
import com.starrocks.sql.ast.AdminCheckTabletsStmt;
import com.starrocks.sql.ast.AdminRepairTableStmt;
import com.starrocks.sql.ast.AdminSetAutomatedSnapshotOffStmt;
import com.starrocks.sql.ast.AdminSetAutomatedSnapshotOnStmt;
import com.starrocks.sql.ast.AdminSetConfigStmt;
import com.starrocks.sql.ast.AdminSetPartitionVersionStmt;
import com.starrocks.sql.ast.AdminSetReplicaStatusStmt;
import com.starrocks.sql.ast.AdminShowConfigStmt;
import com.starrocks.sql.ast.AdminShowReplicaDistributionStmt;
import com.starrocks.sql.ast.AdminShowReplicaStatusStmt;
import com.starrocks.sql.ast.AlterCatalogStmt;
import com.starrocks.sql.ast.AlterClause;
import com.starrocks.sql.ast.AlterDatabaseQuotaStmt;
import com.starrocks.sql.ast.AlterDatabaseRenameStatement;
import com.starrocks.sql.ast.AlterLoadErrorUrlClause;
import com.starrocks.sql.ast.AlterLoadStmt;
import com.starrocks.sql.ast.AlterMaterializedViewStatusClause;
import com.starrocks.sql.ast.AlterMaterializedViewStmt;
import com.starrocks.sql.ast.AlterResourceGroupStmt;
import com.starrocks.sql.ast.AlterResourceStmt;
import com.starrocks.sql.ast.AlterRoleStmt;
import com.starrocks.sql.ast.AlterRoutineLoadStmt;
import com.starrocks.sql.ast.AlterStorageVolumeClause;
import com.starrocks.sql.ast.AlterStorageVolumeCommentClause;
import com.starrocks.sql.ast.AlterStorageVolumeStmt;
import com.starrocks.sql.ast.AlterSystemStmt;
import com.starrocks.sql.ast.AlterTableClause;
import com.starrocks.sql.ast.AlterTableCommentClause;
import com.starrocks.sql.ast.AlterTableOperationClause;
import com.starrocks.sql.ast.AlterTableStmt;
import com.starrocks.sql.ast.AlterUserStmt;
import com.starrocks.sql.ast.AlterViewClause;
import com.starrocks.sql.ast.AlterViewStmt;
import com.starrocks.sql.ast.AnalyzeBasicDesc;
import com.starrocks.sql.ast.AnalyzeHistogramDesc;
import com.starrocks.sql.ast.AnalyzeMultiColumnDesc;
import com.starrocks.sql.ast.AnalyzeProfileStmt;
import com.starrocks.sql.ast.AnalyzeStmt;
import com.starrocks.sql.ast.AnalyzeTypeDesc;
import com.starrocks.sql.ast.ArrayExpr;
import com.starrocks.sql.ast.AsyncRefreshSchemeDesc;
import com.starrocks.sql.ast.BackupStmt;
import com.starrocks.sql.ast.CTERelation;
import com.starrocks.sql.ast.CancelAlterSystemStmt;
import com.starrocks.sql.ast.CancelAlterTableStmt;
import com.starrocks.sql.ast.CancelBackupStmt;
import com.starrocks.sql.ast.CancelCompactionStmt;
import com.starrocks.sql.ast.CancelExportStmt;
import com.starrocks.sql.ast.CancelLoadStmt;
import com.starrocks.sql.ast.CancelRefreshDictionaryStmt;
import com.starrocks.sql.ast.CancelRefreshMaterializedViewStmt;
import com.starrocks.sql.ast.CatalogRef;
import com.starrocks.sql.ast.CleanTabletSchedQClause;
import com.starrocks.sql.ast.CleanTemporaryTableStmt;
import com.starrocks.sql.ast.ClearDataCacheRulesStmt;
import com.starrocks.sql.ast.ColWithComment;
import com.starrocks.sql.ast.ColumnAssignment;
import com.starrocks.sql.ast.ColumnDef;
import com.starrocks.sql.ast.ColumnRenameClause;
import com.starrocks.sql.ast.ColumnSeparator;
import com.starrocks.sql.ast.CompactionClause;
import com.starrocks.sql.ast.CreateAnalyzeJobStmt;
import com.starrocks.sql.ast.CreateCatalogStmt;
import com.starrocks.sql.ast.CreateDataCacheRuleStmt;
import com.starrocks.sql.ast.CreateDbStmt;
import com.starrocks.sql.ast.CreateDictionaryStmt;
import com.starrocks.sql.ast.CreateFileStmt;
import com.starrocks.sql.ast.CreateFunctionStmt;
import com.starrocks.sql.ast.CreateImageClause;
import com.starrocks.sql.ast.CreateIndexClause;
import com.starrocks.sql.ast.CreateMaterializedViewStatement;
import com.starrocks.sql.ast.CreateMaterializedViewStmt;
import com.starrocks.sql.ast.CreateOrReplaceBranchClause;
import com.starrocks.sql.ast.CreateOrReplaceTagClause;
import com.starrocks.sql.ast.CreateRepositoryStmt;
import com.starrocks.sql.ast.CreateResourceGroupStmt;
import com.starrocks.sql.ast.CreateResourceStmt;
import com.starrocks.sql.ast.CreateRoleStmt;
import com.starrocks.sql.ast.CreateRoutineLoadStmt;
import com.starrocks.sql.ast.CreateStorageVolumeStmt;
import com.starrocks.sql.ast.CreateTableAsSelectStmt;
import com.starrocks.sql.ast.CreateTableLikeStmt;
import com.starrocks.sql.ast.CreateTableStmt;
import com.starrocks.sql.ast.CreateTemporaryTableAsSelectStmt;
import com.starrocks.sql.ast.CreateTemporaryTableLikeStmt;
import com.starrocks.sql.ast.CreateTemporaryTableStmt;
import com.starrocks.sql.ast.CreateUserStmt;
import com.starrocks.sql.ast.CreateViewStmt;
import com.starrocks.sql.ast.DataCacheSelectStatement;
import com.starrocks.sql.ast.DataDescription;
import com.starrocks.sql.ast.DeallocateStmt;
import com.starrocks.sql.ast.DecommissionBackendClause;
import com.starrocks.sql.ast.DefaultValueExpr;
import com.starrocks.sql.ast.DelBackendBlackListStmt;
import com.starrocks.sql.ast.DelComputeNodeBlackListStmt;
import com.starrocks.sql.ast.DelSqlBlackListStmt;
import com.starrocks.sql.ast.DeleteStmt;
import com.starrocks.sql.ast.DescStorageVolumeStmt;
import com.starrocks.sql.ast.DescribeStmt;
import com.starrocks.sql.ast.DictionaryGetExpr;
import com.starrocks.sql.ast.DistributionDesc;
import com.starrocks.sql.ast.DropAnalyzeJobStmt;
import com.starrocks.sql.ast.DropBackendClause;
import com.starrocks.sql.ast.DropBranchClause;
import com.starrocks.sql.ast.DropCatalogStmt;
import com.starrocks.sql.ast.DropColumnClause;
import com.starrocks.sql.ast.DropComputeNodeClause;
import com.starrocks.sql.ast.DropDataCacheRuleStmt;
import com.starrocks.sql.ast.DropDbStmt;
import com.starrocks.sql.ast.DropDictionaryStmt;
import com.starrocks.sql.ast.DropFieldClause;
import com.starrocks.sql.ast.DropFileStmt;
import com.starrocks.sql.ast.DropFollowerClause;
import com.starrocks.sql.ast.DropFunctionStmt;
import com.starrocks.sql.ast.DropHistogramStmt;
import com.starrocks.sql.ast.DropIndexClause;
import com.starrocks.sql.ast.DropMaterializedViewStmt;
import com.starrocks.sql.ast.DropObserverClause;
import com.starrocks.sql.ast.DropPartitionClause;
import com.starrocks.sql.ast.DropPersistentIndexClause;
import com.starrocks.sql.ast.DropRepositoryStmt;
import com.starrocks.sql.ast.DropResourceGroupStmt;
import com.starrocks.sql.ast.DropResourceStmt;
import com.starrocks.sql.ast.DropRoleStmt;
import com.starrocks.sql.ast.DropRollupClause;
import com.starrocks.sql.ast.DropStatsStmt;
import com.starrocks.sql.ast.DropStorageVolumeStmt;
import com.starrocks.sql.ast.DropTableStmt;
import com.starrocks.sql.ast.DropTagClause;
import com.starrocks.sql.ast.DropTaskStmt;
import com.starrocks.sql.ast.DropTemporaryTableStmt;
import com.starrocks.sql.ast.DropUserStmt;
import com.starrocks.sql.ast.EmptyStmt;
import com.starrocks.sql.ast.ExceptRelation;
import com.starrocks.sql.ast.ExecuteAsStmt;
import com.starrocks.sql.ast.ExecuteScriptStmt;
import com.starrocks.sql.ast.ExecuteStmt;
import com.starrocks.sql.ast.ExportStmt;
import com.starrocks.sql.ast.ExpressionPartitionDesc;
import com.starrocks.sql.ast.FileTableFunctionRelation;
import com.starrocks.sql.ast.FunctionArgsDef;
import com.starrocks.sql.ast.FunctionRef;
import com.starrocks.sql.ast.GrantPrivilegeStmt;
import com.starrocks.sql.ast.GrantRevokeClause;
import com.starrocks.sql.ast.GrantRevokePrivilegeObjects;
import com.starrocks.sql.ast.GrantRoleStmt;
import com.starrocks.sql.ast.HashDistributionDesc;
import com.starrocks.sql.ast.HelpStmt;
import com.starrocks.sql.ast.Identifier;
import com.starrocks.sql.ast.ImportColumnDesc;
import com.starrocks.sql.ast.ImportColumnsStmt;
import com.starrocks.sql.ast.ImportWhereStmt;
import com.starrocks.sql.ast.IncrementalRefreshSchemeDesc;
import com.starrocks.sql.ast.IndexDef;
import com.starrocks.sql.ast.InsertStmt;
import com.starrocks.sql.ast.InstallPluginStmt;
import com.starrocks.sql.ast.IntersectRelation;
import com.starrocks.sql.ast.IntervalLiteral;
import com.starrocks.sql.ast.JoinRelation;
import com.starrocks.sql.ast.KeysDesc;
import com.starrocks.sql.ast.KillAnalyzeStmt;
import com.starrocks.sql.ast.KillStmt;
import com.starrocks.sql.ast.LambdaArgument;
import com.starrocks.sql.ast.LambdaFunctionExpr;
import com.starrocks.sql.ast.ListPartitionDesc;
import com.starrocks.sql.ast.LoadStmt;
import com.starrocks.sql.ast.ManualRefreshSchemeDesc;
import com.starrocks.sql.ast.MapExpr;
import com.starrocks.sql.ast.ModifyBackendClause;
import com.starrocks.sql.ast.ModifyBrokerClause;
import com.starrocks.sql.ast.ModifyColumnClause;
import com.starrocks.sql.ast.ModifyColumnCommentClause;
import com.starrocks.sql.ast.ModifyFrontendAddressClause;
import com.starrocks.sql.ast.ModifyPartitionClause;
import com.starrocks.sql.ast.ModifyStorageVolumePropertiesClause;
import com.starrocks.sql.ast.ModifyTablePropertiesClause;
import com.starrocks.sql.ast.MultiItemListPartitionDesc;
import com.starrocks.sql.ast.MultiRangePartitionDesc;
import com.starrocks.sql.ast.NormalizedTableFunctionRelation;
import com.starrocks.sql.ast.OptimizeClause;
import com.starrocks.sql.ast.OptimizeRange;
import com.starrocks.sql.ast.PartitionDesc;
import com.starrocks.sql.ast.PartitionKeyDesc;
import com.starrocks.sql.ast.PartitionNames;
import com.starrocks.sql.ast.PartitionRangeDesc;
import com.starrocks.sql.ast.PartitionRenameClause;
import com.starrocks.sql.ast.PartitionValue;
import com.starrocks.sql.ast.PauseRoutineLoadStmt;
import com.starrocks.sql.ast.PivotAggregation;
import com.starrocks.sql.ast.PivotRelation;
import com.starrocks.sql.ast.PivotValue;
import com.starrocks.sql.ast.PrepareStmt;
import com.starrocks.sql.ast.Property;
import com.starrocks.sql.ast.PropertySet;
import com.starrocks.sql.ast.QualifiedName;
import com.starrocks.sql.ast.QueryPeriod;
import com.starrocks.sql.ast.QueryRelation;
import com.starrocks.sql.ast.QueryStatement;
import com.starrocks.sql.ast.RandomDistributionDesc;
import com.starrocks.sql.ast.RangePartitionDesc;
import com.starrocks.sql.ast.RecoverDbStmt;
import com.starrocks.sql.ast.RecoverPartitionStmt;
import com.starrocks.sql.ast.RecoverTableStmt;
import com.starrocks.sql.ast.RefreshDictionaryStmt;
import com.starrocks.sql.ast.RefreshMaterializedViewStatement;
import com.starrocks.sql.ast.RefreshSchemeClause;
import com.starrocks.sql.ast.RefreshTableStmt;
import com.starrocks.sql.ast.Relation;
import com.starrocks.sql.ast.ReorderColumnsClause;
import com.starrocks.sql.ast.ReplacePartitionClause;
import com.starrocks.sql.ast.ResourceDesc;
import com.starrocks.sql.ast.RestoreStmt;
import com.starrocks.sql.ast.ResumeRoutineLoadStmt;
import com.starrocks.sql.ast.RevokePrivilegeStmt;
import com.starrocks.sql.ast.RevokeRoleStmt;
import com.starrocks.sql.ast.RollupRenameClause;
import com.starrocks.sql.ast.RowDelimiter;
import com.starrocks.sql.ast.SelectList;
import com.starrocks.sql.ast.SelectListItem;
import com.starrocks.sql.ast.SelectRelation;
import com.starrocks.sql.ast.SetCatalogStmt;
import com.starrocks.sql.ast.SetDefaultRoleStmt;
import com.starrocks.sql.ast.SetDefaultStorageVolumeStmt;
import com.starrocks.sql.ast.SetListItem;
import com.starrocks.sql.ast.SetNamesVar;
import com.starrocks.sql.ast.SetPassVar;
import com.starrocks.sql.ast.SetQualifier;
import com.starrocks.sql.ast.SetRoleStmt;
import com.starrocks.sql.ast.SetRoleType;
import com.starrocks.sql.ast.SetStmt;
import com.starrocks.sql.ast.SetTransaction;
import com.starrocks.sql.ast.SetType;
import com.starrocks.sql.ast.SetUserPropertyStmt;
import com.starrocks.sql.ast.SetUserPropertyVar;
import com.starrocks.sql.ast.ShowAlterStmt;
import com.starrocks.sql.ast.ShowAnalyzeJobStmt;
import com.starrocks.sql.ast.ShowAnalyzeStatusStmt;
import com.starrocks.sql.ast.ShowAuthenticationStmt;
import com.starrocks.sql.ast.ShowAuthorStmt;
import com.starrocks.sql.ast.ShowBackendBlackListStmt;
import com.starrocks.sql.ast.ShowBackendsStmt;
import com.starrocks.sql.ast.ShowBackupStmt;
import com.starrocks.sql.ast.ShowBasicStatsMetaStmt;
import com.starrocks.sql.ast.ShowBrokerStmt;
import com.starrocks.sql.ast.ShowCatalogsStmt;
import com.starrocks.sql.ast.ShowCharsetStmt;
import com.starrocks.sql.ast.ShowCollationStmt;
import com.starrocks.sql.ast.ShowColumnStmt;
import com.starrocks.sql.ast.ShowComputeNodeBlackListStmt;
import com.starrocks.sql.ast.ShowComputeNodesStmt;
import com.starrocks.sql.ast.ShowCreateDbStmt;
import com.starrocks.sql.ast.ShowCreateExternalCatalogStmt;
import com.starrocks.sql.ast.ShowCreateRoutineLoadStmt;
import com.starrocks.sql.ast.ShowCreateTableStmt;
import com.starrocks.sql.ast.ShowDataCacheRulesStmt;
import com.starrocks.sql.ast.ShowDataDistributionStmt;
import com.starrocks.sql.ast.ShowDataStmt;
import com.starrocks.sql.ast.ShowDbStmt;
import com.starrocks.sql.ast.ShowDeleteStmt;
import com.starrocks.sql.ast.ShowDictionaryStmt;
import com.starrocks.sql.ast.ShowDynamicPartitionStmt;
import com.starrocks.sql.ast.ShowEnginesStmt;
import com.starrocks.sql.ast.ShowEventsStmt;
import com.starrocks.sql.ast.ShowExportStmt;
import com.starrocks.sql.ast.ShowFailPointStatement;
import com.starrocks.sql.ast.ShowFrontendsStmt;
import com.starrocks.sql.ast.ShowFunctionsStmt;
import com.starrocks.sql.ast.ShowGrantsStmt;
import com.starrocks.sql.ast.ShowHistogramStatsMetaStmt;
import com.starrocks.sql.ast.ShowIndexStmt;
import com.starrocks.sql.ast.ShowLoadStmt;
import com.starrocks.sql.ast.ShowLoadWarningsStmt;
import com.starrocks.sql.ast.ShowMaterializedViewsStmt;
import com.starrocks.sql.ast.ShowMultiColumnStatsMetaStmt;
import com.starrocks.sql.ast.ShowOpenTableStmt;
import com.starrocks.sql.ast.ShowPartitionsStmt;
import com.starrocks.sql.ast.ShowPluginsStmt;
import com.starrocks.sql.ast.ShowPrivilegesStmt;
import com.starrocks.sql.ast.ShowProcStmt;
import com.starrocks.sql.ast.ShowProcedureStmt;
import com.starrocks.sql.ast.ShowProcesslistStmt;
import com.starrocks.sql.ast.ShowProfilelistStmt;
import com.starrocks.sql.ast.ShowRepositoriesStmt;
import com.starrocks.sql.ast.ShowResourceGroupStmt;
import com.starrocks.sql.ast.ShowResourceGroupUsageStmt;
import com.starrocks.sql.ast.ShowResourcesStmt;
import com.starrocks.sql.ast.ShowRestoreStmt;
import com.starrocks.sql.ast.ShowRolesStmt;
import com.starrocks.sql.ast.ShowRoutineLoadStmt;
import com.starrocks.sql.ast.ShowRoutineLoadTaskStmt;
import com.starrocks.sql.ast.ShowRunningQueriesStmt;
import com.starrocks.sql.ast.ShowSmallFilesStmt;
import com.starrocks.sql.ast.ShowSnapshotStmt;
import com.starrocks.sql.ast.ShowSqlBlackListStmt;
import com.starrocks.sql.ast.ShowStatusStmt;
import com.starrocks.sql.ast.ShowStorageVolumesStmt;
import com.starrocks.sql.ast.ShowStreamLoadStmt;
import com.starrocks.sql.ast.ShowTableStatusStmt;
import com.starrocks.sql.ast.ShowTableStmt;
import com.starrocks.sql.ast.ShowTabletStmt;
import com.starrocks.sql.ast.ShowTransactionStmt;
import com.starrocks.sql.ast.ShowTriggersStmt;
import com.starrocks.sql.ast.ShowUserPropertyStmt;
import com.starrocks.sql.ast.ShowUserStmt;
import com.starrocks.sql.ast.ShowVariablesStmt;
import com.starrocks.sql.ast.ShowWarningStmt;
import com.starrocks.sql.ast.ShowWhiteListStmt;
import com.starrocks.sql.ast.SingleItemListPartitionDesc;
import com.starrocks.sql.ast.SingleRangePartitionDesc;
import com.starrocks.sql.ast.SplitTabletClause;
import com.starrocks.sql.ast.StatementBase;
import com.starrocks.sql.ast.StopRoutineLoadStmt;
import com.starrocks.sql.ast.StructFieldDesc;
import com.starrocks.sql.ast.SubmitTaskStmt;
import com.starrocks.sql.ast.SubqueryRelation;
import com.starrocks.sql.ast.SwapTableClause;
import com.starrocks.sql.ast.SyncRefreshSchemeDesc;
import com.starrocks.sql.ast.SyncStmt;
import com.starrocks.sql.ast.SystemVariable;
import com.starrocks.sql.ast.TableFunctionRelation;
import com.starrocks.sql.ast.TableRelation;
import com.starrocks.sql.ast.TableRenameClause;
import com.starrocks.sql.ast.TableSampleClause;
import com.starrocks.sql.ast.TabletList;
import com.starrocks.sql.ast.TruncatePartitionClause;
import com.starrocks.sql.ast.TruncateTableStmt;
import com.starrocks.sql.ast.UninstallPluginStmt;
import com.starrocks.sql.ast.UnionRelation;
import com.starrocks.sql.ast.UnitBoundary;
import com.starrocks.sql.ast.UnitIdentifier;
import com.starrocks.sql.ast.UnsupportedStmt;
import com.starrocks.sql.ast.UpdateFailPointStatusStatement;
import com.starrocks.sql.ast.UpdateStmt;
import com.starrocks.sql.ast.UseCatalogStmt;
import com.starrocks.sql.ast.UseDbStmt;
import com.starrocks.sql.ast.UserAuthOption;
import com.starrocks.sql.ast.UserIdentity;
import com.starrocks.sql.ast.UserVariable;
import com.starrocks.sql.ast.ValueList;
import com.starrocks.sql.ast.ValuesRelation;
import com.starrocks.sql.ast.feedback.AddPlanAdvisorStmt;
import com.starrocks.sql.ast.feedback.ClearPlanAdvisorStmt;
import com.starrocks.sql.ast.feedback.DelPlanAdvisorStmt;
import com.starrocks.sql.ast.feedback.ShowPlanAdvisorStmt;
import com.starrocks.sql.ast.group.CreateGroupProviderStmt;
import com.starrocks.sql.ast.group.DropGroupProviderStmt;
import com.starrocks.sql.ast.group.ShowCreateGroupProviderStmt;
import com.starrocks.sql.ast.group.ShowGroupProvidersStmt;
import com.starrocks.sql.ast.integration.AlterSecurityIntegrationStatement;
import com.starrocks.sql.ast.integration.CreateSecurityIntegrationStatement;
import com.starrocks.sql.ast.integration.DropSecurityIntegrationStatement;
import com.starrocks.sql.ast.integration.ShowCreateSecurityIntegrationStatement;
import com.starrocks.sql.ast.integration.ShowSecurityIntegrationStatement;
import com.starrocks.sql.ast.pipe.AlterPipeClause;
import com.starrocks.sql.ast.pipe.AlterPipeClauseRetry;
import com.starrocks.sql.ast.pipe.AlterPipePauseResume;
import com.starrocks.sql.ast.pipe.AlterPipeSetProperty;
import com.starrocks.sql.ast.pipe.AlterPipeStmt;
import com.starrocks.sql.ast.pipe.CreatePipeStmt;
import com.starrocks.sql.ast.pipe.DescPipeStmt;
import com.starrocks.sql.ast.pipe.DropPipeStmt;
import com.starrocks.sql.ast.pipe.PipeName;
import com.starrocks.sql.ast.pipe.ShowPipeStmt;
import com.starrocks.sql.ast.spm.ControlBaselinePlanStmt;
import com.starrocks.sql.ast.spm.CreateBaselinePlanStmt;
import com.starrocks.sql.ast.spm.DropBaselinePlanStmt;
import com.starrocks.sql.ast.spm.ShowBaselinePlanStmt;
import com.starrocks.sql.ast.translate.TranslateStmt;
import com.starrocks.sql.ast.txn.BeginStmt;
import com.starrocks.sql.ast.txn.CommitStmt;
import com.starrocks.sql.ast.txn.RollbackStmt;
import com.starrocks.sql.ast.warehouse.AlterWarehouseStmt;
import com.starrocks.sql.ast.warehouse.CreateWarehouseStmt;
import com.starrocks.sql.ast.warehouse.DropWarehouseStmt;
import com.starrocks.sql.ast.warehouse.ResumeWarehouseStmt;
import com.starrocks.sql.ast.warehouse.SetWarehouseStmt;
import com.starrocks.sql.ast.warehouse.ShowClustersStmt;
import com.starrocks.sql.ast.warehouse.ShowNodesStmt;
import com.starrocks.sql.ast.warehouse.ShowWarehousesStmt;
import com.starrocks.sql.ast.warehouse.SuspendWarehouseStmt;
import com.starrocks.sql.ast.warehouse.cngroup.AlterCnGroupStmt;
import com.starrocks.sql.ast.warehouse.cngroup.CreateCnGroupStmt;
import com.starrocks.sql.ast.warehouse.cngroup.DropCnGroupStmt;
import com.starrocks.sql.ast.warehouse.cngroup.EnableDisableCnGroupStmt;
import com.starrocks.sql.common.PListCell;
import com.starrocks.sql.util.EitherOr;
import com.starrocks.statistic.StatsConstants;
import com.starrocks.transaction.GtidGenerator;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.RuleContext;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.misc.Interval;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.TerminalNode;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.ListUtils;
import org.apache.commons.collections4.MapUtils;
import org.apache.commons.lang3.StringUtils;

import java.io.StringWriter;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.starrocks.catalog.FunctionSet.ARRAY_AGG_DISTINCT;
import static com.starrocks.sql.ast.IndexDef.IndexType.getIndexType;
import static com.starrocks.sql.common.ErrorMsgProxy.PARSER_ERROR_MSG;
import static java.lang.String.format;
import static java.util.stream.Collectors.toList;

public class AstBuilder extends StarRocksBaseVisitor<ParseNode> {
    private final long sqlMode;

    private final IdentityHashMap<ParserRuleContext, List<HintNode>> hintMap;

    private int placeHolderSlotId = 0;

    private List<Parameter> parameters;

    private static final BigInteger LONG_MAX = new BigInteger("9223372036854775807"); // 2^63 - 1

    private static final BigInteger LARGEINT_MAX_ABS =
            new BigInteger("170141183460469231731687303715884105728"); // 2^127

    private static final BigInteger INT256_MAX_ABS =
            new BigInteger("57896044618658097711785492504343953926634992332820282019728792003956564819968"); // 2^255

    private static final List<String> DATE_FUNCTIONS =
            Lists.newArrayList(
                    FunctionSet.DATE_ADD,
                    FunctionSet.ADDDATE,
                    FunctionSet.DATE_SUB,
                    FunctionSet.SUBDATE,
                    FunctionSet.DAYS_SUB);

    private static final List<String> PARTITION_FUNCTIONS =
            Lists.newArrayList(FunctionSet.SUBSTR, FunctionSet.SUBSTRING,
                    FunctionSet.FROM_UNIXTIME, FunctionSet.FROM_UNIXTIME_MS,
                    FunctionSet.STR2DATE);

    protected AstBuilder(long sqlMode) {
        this(sqlMode, new IdentityHashMap<>());
    }

    protected AstBuilder(long sqlMode, IdentityHashMap<ParserRuleContext, List<HintNode>> hintMap) {
        this.hintMap = hintMap;
        long hintSqlMode = 0L;
        for (Map.Entry<ParserRuleContext, List<HintNode>> entry : hintMap.entrySet()) {
            for (HintNode hint : entry.getValue()) {
                if (hint instanceof SetVarHint) {
                    SetVarHint setVarHint = (SetVarHint) hint;
                    hintSqlMode = setVarHint.getSqlModeHintValue();
                }
            }
        }
        this.sqlMode = sqlMode | hintSqlMode;
    }

    private static final AstBuilder.AstBuilderFactory INSTANCE = new AstBuilder.AstBuilderFactory();

    public static AstBuilder.AstBuilderFactory getInstance() {
        return INSTANCE;
    }

    public static class AstBuilderFactory {
        protected AstBuilderFactory() {
        }

        public AstBuilder create(long sqlMode) {
            return new AstBuilder(sqlMode, new IdentityHashMap<>());
        }

        public AstBuilder create(long sqlMode, IdentityHashMap<ParserRuleContext, List<HintNode>> hintMap) {
            return new AstBuilder(sqlMode, hintMap);
        }
    }

    public List<Parameter> getParameters() {
        return parameters;
    }

    @Override
    public ParseNode visitSingleStatement(StarRocksParser.SingleStatementContext context) {
        if (context.statement() != null) {
            StatementBase stmt = (StatementBase) visit(context.statement());
            if (MapUtils.isNotEmpty(hintMap)) {
                stmt.setAllQueryScopeHints(extractQueryScopeHintNode());
                hintMap.clear();
            }
            return stmt;
        } else {
            return visit(context.emptyStatement());
        }
    }

    @Override
    public ParseNode visitEmptyStatement(StarRocksParser.EmptyStatementContext context) {
        return new EmptyStmt();
    }

    // ---------------------------------------- Database Statement -----------------------------------------------------

    @Override
    public ParseNode visitUseDatabaseStatement(StarRocksParser.UseDatabaseStatementContext context) {
        NodePosition pos = createPos(context);
        QualifiedName qualifiedName = getQualifiedName(context.qualifiedName());
        List<String> parts = qualifiedName.getParts();
        if (parts.size() == 1) {
            return new UseDbStmt(null, parts.get(0), pos);
        } else if (parts.size() == 2) {
            return new UseDbStmt(parts.get(0), parts.get(1), pos);
        } else {
            throw new ParsingException(PARSER_ERROR_MSG.invalidDbFormat(qualifiedName.toString()),
                    qualifiedName.getPos());
        }
    }

    @Override
    public ParseNode visitUseCatalogStatement(StarRocksParser.UseCatalogStatementContext context) {
        StringLiteral literal = (StringLiteral) visit(context.string());
        return new UseCatalogStmt(literal.getValue(), createPos(context));
    }

    @Override
    public ParseNode visitSetCatalogStatement(StarRocksParser.SetCatalogStatementContext context) {
        Identifier identifier = (Identifier) visit(context.identifierOrString());
        String catalogName = identifier.getValue();
        return new SetCatalogStmt(catalogName, createPos(context));
    }

    @Override
    public ParseNode visitShowDatabasesStatement(StarRocksParser.ShowDatabasesStatementContext context) {
        String catalog = null;
        NodePosition pos = createPos(context);
        if (context.catalog != null) {
            QualifiedName dbName = getQualifiedName(context.catalog);
            catalog = dbName.toString();
        }

        if (context.pattern != null) {
            StringLiteral stringLiteral = (StringLiteral) visit(context.pattern);
            return new ShowDbStmt(stringLiteral.getValue(), null, catalog, pos);
        } else if (context.expression() != null) {
            return new ShowDbStmt(null, (Expr) visit(context.expression()), catalog, pos);
        } else {
            return new ShowDbStmt(null, null, catalog, pos);
        }
    }

    @Override
    public ParseNode visitAlterDbQuotaStatement(StarRocksParser.AlterDbQuotaStatementContext context) {
        String dbName = ((Identifier) visit(context.identifier(0))).getValue();
        NodePosition pos = createPos(context);
        if (context.DATA() != null) {
            String quotaValue = ((Identifier) visit(context.identifier(1))).getValue();
            return new AlterDatabaseQuotaStmt(dbName,
                    AlterDatabaseQuotaStmt.QuotaType.DATA,
                    quotaValue, pos);
        } else {
            String quotaValue = context.INTEGER_VALUE().getText();
            return new AlterDatabaseQuotaStmt(dbName,
                    AlterDatabaseQuotaStmt.QuotaType.REPLICA,
                    quotaValue, pos);
        }
    }

    @Override
    public ParseNode visitCreateDbStatement(StarRocksParser.CreateDbStatementContext context) {
        String catalogName = "";
        if (context.catalog != null) {
            catalogName = getIdentifierName(context.catalog);
        }

        QualifiedName dbName = getQualifiedName(context.database);

        Map<String, String> properties = new HashMap<>();
        if (context.properties() != null) {
            List<Property> propertyList = visit(context.properties().property(), Property.class);
            for (Property property : propertyList) {
                properties.put(property.getKey(), property.getValue());
            }
        }
        return new CreateDbStmt(context.IF() != null, catalogName, dbName.toString(), properties, createPos(context));
    }

    @Override
    public ParseNode visitDropDbStatement(StarRocksParser.DropDbStatementContext context) {
        String catalogName = "";
        if (context.catalog != null) {
            catalogName = getIdentifierName(context.catalog);
        }

        QualifiedName dbName = getQualifiedName(context.database);
        return new DropDbStmt(context.IF() != null, catalogName, dbName.toString(), context.FORCE() != null,
                createPos(context));
    }

    @Override
    public ParseNode visitShowCreateDbStatement(StarRocksParser.ShowCreateDbStatementContext context) {
        String dbName = ((Identifier) visit(context.identifier())).getValue();
        return new ShowCreateDbStmt(dbName, createPos(context));
    }

    @Override
    public ParseNode visitAlterDatabaseRenameStatement(StarRocksParser.AlterDatabaseRenameStatementContext context) {
        String dbName = ((Identifier) visit(context.identifier(0))).getValue();
        String newName = ((Identifier) visit(context.identifier(1))).getValue();
        return new AlterDatabaseRenameStatement(dbName, newName, createPos(context));
    }

    @Override
    public ParseNode visitRecoverDbStmt(StarRocksParser.RecoverDbStmtContext context) {
        String dbName = ((Identifier) visit(context.identifier())).getValue();
        return new RecoverDbStmt(dbName, createPos(context));
    }

    @Override
    public ParseNode visitShowDataStmt(StarRocksParser.ShowDataStmtContext context) {
        NodePosition pos = createPos(context);
        if (context.FROM() != null) {
            QualifiedName qualifiedName = getQualifiedName(context.qualifiedName());
            TableName targetTableName = qualifiedNameToTableName(qualifiedName);
            return new ShowDataStmt(targetTableName.getDb(), targetTableName.getTbl(), pos);
        } else {
            return new ShowDataStmt(null, null, pos);
        }
    }

    @Override
    public ParseNode visitShowDataDistributionStmt(StarRocksParser.ShowDataDistributionStmtContext context) {
        Token start = context.qualifiedName().start;
        Token stop = context.qualifiedName().stop;
        QualifiedName qualifiedName = getQualifiedName(context.qualifiedName());
        TableName targetTableName = qualifiedNameToTableName(qualifiedName);
        PartitionNames partitionNames = null;
        if (context.partitionNames() != null) {
            stop = context.partitionNames().stop;
            partitionNames = (PartitionNames) visit(context.partitionNames());
        }
        return new ShowDataDistributionStmt(new TableRef(targetTableName, null,
                partitionNames, createPos(start, stop)),
                createPos(context));
    }

    // ------------------------------------------- Table Statement -----------------------------------------------------

    @Override
    public ParseNode visitCreateTableStatement(StarRocksParser.CreateTableStatementContext context) {
        Map<String, String> properties = null;
        if (context.properties() != null) {
            properties = new HashMap<>();
            List<Property> propertyList = visit(context.properties().property(), Property.class);
            for (Property property : propertyList) {
                properties.put(property.getKey(), property.getValue());
            }
        }
        Map<String, String> extProperties = null;
        if (context.extProperties() != null) {
            extProperties = new HashMap<>();
            List<Property> propertyList = visit(context.extProperties().properties().property(), Property.class);
            for (Property property : propertyList) {
                extProperties.put(property.getKey(), property.getValue());
            }
        }
        TableName tableName = qualifiedNameToTableName(getQualifiedName(context.qualifiedName()));

        List<ColumnDef> columnDefs = null;
        if (context.columnDesc() != null) {
            columnDefs = getColumnDefs(context.columnDesc());
        }
        if (context.TEMPORARY() != null) {
            if (!Config.enable_experimental_temporary_table) {
                throw new ParsingException(
                        PARSER_ERROR_MSG.feConfigDisable("enable_experimental_temporary_table"), NodePosition.ZERO);
            }
            return new CreateTemporaryTableStmt(
                    context.IF() != null,
                    false,
                    tableName,
                    columnDefs,
                    context.indexDesc() == null ? null : getIndexDefs(context.indexDesc()),
                    context.engineDesc() == null ? "" :
                            ((Identifier) visit(context.engineDesc().identifier())).getValue(),
                    context.charsetDesc() == null ? null :
                            ((Identifier) visit(context.charsetDesc().identifierOrString())).getValue(),
                    context.keyDesc() == null ? null : getKeysDesc(context.keyDesc()),
                    context.partitionDesc() == null ? null : getPartitionDesc(context.partitionDesc(), columnDefs),
                    context.distributionDesc() == null ? null : (DistributionDesc) visit(context.distributionDesc()),
                    properties,
                    extProperties,
                    context.comment() == null ? null :
                            ((StringLiteral) visit(context.comment().string())).getStringValue(),
                    context.rollupDesc() == null ?
                            null : context.rollupDesc().rollupItem().stream().map(this::getRollup).collect(toList()),
                    context.orderByDesc() == null ? null :
                            visit(context.orderByDesc().identifierList().identifier(), Identifier.class)
                                    .stream().map(Identifier::getValue).collect(toList()),
                    NodePosition.ZERO);

        }

        return new CreateTableStmt(
                context.IF() != null,
                context.EXTERNAL() != null,
                tableName,
                columnDefs,
                context.indexDesc() == null ? null : getIndexDefs(context.indexDesc()),
                context.engineDesc() == null ? "" :
                        ((Identifier) visit(context.engineDesc().identifier())).getValue(),
                context.charsetDesc() == null ? null :
                        ((Identifier) visit(context.charsetDesc().identifierOrString())).getValue(),
                context.keyDesc() == null ? null : getKeysDesc(context.keyDesc()),
                context.partitionDesc() == null ? null : getPartitionDesc(context.partitionDesc(), columnDefs),
                context.distributionDesc() == null ? null : (DistributionDesc) visit(context.distributionDesc()),
                properties,
                extProperties,
                context.comment() == null ? null : ((StringLiteral) visit(context.comment().string())).getStringValue(),
                context.rollupDesc() == null ?
                        null : context.rollupDesc().rollupItem().stream().map(this::getRollup).collect(toList()),
                context.orderByDesc() == null ? null :
                        visit(context.orderByDesc().identifierList().identifier(), Identifier.class)
                                .stream().map(Identifier::getValue).collect(toList()));
    }

    private PartitionDesc generateMulitListPartitionDesc(StarRocksParser.PartitionDescContext context,
                                                         List<ParseNode> multiDescList) {
        ListPartitionDesc listPartitionDesc = new ListPartitionDesc(multiDescList, createPos(context));
        listPartitionDesc.setAutoPartitionTable(true);
        return listPartitionDesc;
    }

    private PartitionDesc getPartitionDesc(StarRocksParser.PartitionDescContext context,
                                           List<ColumnDef> columnDefs) {
        List<PartitionDesc> partitionDescList = new ArrayList<>();
        // for automatic partition
        if (context.functionCall() != null) {
            FunctionCallExpr functionCallExpr = (FunctionCallExpr) visit(context.functionCall());
            String functionName = functionCallExpr.getFnName().getFunction();
            // except date_trunc, time_slice use generated column as partition column
            if (!FunctionSet.DATE_TRUNC.equals(functionName) && !FunctionSet.TIME_SLICE.equals(functionName)
                    && !FunctionSet.STR2DATE.equals(functionName)) {
                return generateMulitListPartitionDesc(context, Lists.newArrayList(functionCallExpr));
            }
            // If simple single expression partitioning is not supported,
            // use the more general expression partitioning based on generated columns.
            List<String> columnList = null;
            try {
                columnList = AnalyzerUtils.checkAndExtractPartitionCol(functionCallExpr, columnDefs);
            } catch (Exception e) {
                return generateMulitListPartitionDesc(context, Lists.newArrayList(functionCallExpr));
            }
            String currentGranularity = null;
            for (StarRocksParser.RangePartitionDescContext rangePartitionDescContext : context.rangePartitionDesc()) {
                final PartitionDesc rangePartitionDesc = (PartitionDesc) visit(rangePartitionDescContext);
                if (!(rangePartitionDesc instanceof MultiRangePartitionDesc)) {
                    throw new ParsingException("Automatic partition table creation only supports " +
                            "batch create partition syntax", rangePartitionDesc.getPos());
                }
                MultiRangePartitionDesc multiRangePartitionDesc = (MultiRangePartitionDesc) rangePartitionDesc;
                String descGranularity = multiRangePartitionDesc.getTimeUnit().toLowerCase();
                if (currentGranularity == null) {
                    currentGranularity = descGranularity;
                } else if (!currentGranularity.equals(descGranularity)) {
                    throw new ParsingException("The partition granularity of automatic partition table " +
                            "batch creation in advance should be consistent", rangePartitionDesc.getPos());
                }
                partitionDescList.add(rangePartitionDesc);
            }
            AnalyzerUtils.checkAutoPartitionTableLimit(functionCallExpr, currentGranularity);
            RangePartitionDesc rangePartitionDesc = new RangePartitionDesc(columnList, partitionDescList);
            rangePartitionDesc.setAutoPartitionTable(true);
            return new ExpressionPartitionDesc(rangePartitionDesc, functionCallExpr);
        }
        // for partition by range expression
        StarRocksParser.PrimaryExpressionContext primaryExpressionContext = context.primaryExpression();
        if (primaryExpressionContext != null) {
            Expr primaryExpression = (Expr) visit(primaryExpressionContext);
            if (context.RANGE() != null) {
                for (StarRocksParser.RangePartitionDescContext rangePartitionDescContext : context.rangePartitionDesc()) {
                    final PartitionDesc rangePartitionDesc = (PartitionDesc) visit(rangePartitionDescContext);
                    partitionDescList.add(rangePartitionDesc);
                }
            }
            List<String> columnList = checkAndExtractPartitionColForRange(primaryExpression, false);
            RangePartitionDesc rangePartitionDesc = new RangePartitionDesc(columnList, partitionDescList);
            if (primaryExpression instanceof FunctionCallExpr) {
                FunctionCallExpr functionCallExpr = (FunctionCallExpr) primaryExpression;
                String functionName = functionCallExpr.getFnName().getFunction();
                if (FunctionSet.FROM_UNIXTIME.equals(functionName)
                        || FunctionSet.FROM_UNIXTIME_MS.equals(functionName)) {
                    primaryExpression = new CastExpr(TypeDef.create(PrimitiveType.DATETIME), primaryExpression);
                }
            }
            return new ExpressionPartitionDesc(rangePartitionDesc, primaryExpression);
        }
        if (context.identifierList() == null) {
            if (context.partitionExpr() != null) {
                List<ParseNode> multiDescList = Lists.newArrayList();
                for (StarRocksParser.PartitionExprContext partitionExpr : context.partitionExpr()) {
                    if (partitionExpr.identifier() != null) {
                        Identifier identifier = (Identifier) visit(partitionExpr.identifier());
                        multiDescList.add(identifier);
                    } else if (partitionExpr.functionCall() != null) {
                        FunctionCallExpr expr = (FunctionCallExpr) visit(partitionExpr.functionCall());
                        multiDescList.add(expr);
                    } else {
                        throw new ParsingException("Partition column list is empty", createPos(context));
                    }
                }
                return generateMulitListPartitionDesc(context, multiDescList);
            }
        }
        List<Identifier> identifierList = visit(context.identifierList().identifier(), Identifier.class);
        List<String> columnList = identifierList.stream().map(Identifier::getValue).collect(toList());
        if (context.RANGE() != null) {
            for (StarRocksParser.RangePartitionDescContext rangePartitionDescContext : context.rangePartitionDesc()) {
                final PartitionDesc rangePartitionDesc = (PartitionDesc) visit(rangePartitionDescContext);
                partitionDescList.add(rangePartitionDesc);
            }
            return new RangePartitionDesc(columnList, partitionDescList);
        } else if (context.LIST() != null) {
            for (StarRocksParser.ListPartitionDescContext listPartitionDescContext : context.listPartitionDesc()) {
                final PartitionDesc listPartitionDesc = (PartitionDesc) visit(listPartitionDescContext);
                partitionDescList.add(listPartitionDesc);
            }
            return new ListPartitionDesc(columnList, partitionDescList);
        } else {
            if (context.listPartitionDesc().size() > 0) {
                throw new ParsingException("Does not support creating partitions in advance");
            }
            // For hive/iceberg/hudi partition & automatic partition
            ListPartitionDesc listPartitionDesc = new ListPartitionDesc(columnList, partitionDescList);
            listPartitionDesc.setAutoPartitionTable(true);
            return listPartitionDesc;
        }
    }

    private List<String> checkAndExtractPartitionColForRange(Expr expr, boolean hasCast) {
        if (expr instanceof CastExpr) {
            CastExpr castExpr = (CastExpr) expr;
            return checkAndExtractPartitionColForRange(castExpr.getChild(0), true);
        }
        NodePosition pos = expr.getPos();
        List<String> columnList = new ArrayList<>();
        if (expr instanceof FunctionCallExpr) {
            FunctionCallExpr functionCallExpr = (FunctionCallExpr) expr;
            String functionName = functionCallExpr.getFnName().getFunction().toLowerCase();
            List<Expr> paramsExpr = functionCallExpr.getParams().exprs();
            if (PARTITION_FUNCTIONS.contains(functionName)) {
                Expr firstExpr = paramsExpr.get(0);
                if (firstExpr instanceof SlotRef) {
                    columnList.add(((SlotRef) firstExpr).getColumnName());
                } else {
                    throw new ParsingException(PARSER_ERROR_MSG.unsupportedExprWithInfo(expr.toSql(), "PARTITION BY"),
                            pos);
                }
            } else {
                throw new ParsingException(PARSER_ERROR_MSG.unsupportedExprWithInfo(expr.toSql(), "PARTITION BY"), pos);
            }
            if (functionName.equals(FunctionSet.FROM_UNIXTIME) || functionName.equals(FunctionSet.FROM_UNIXTIME_MS)) {
                if (hasCast || paramsExpr.size() > 1) {
                    throw new ParsingException(PARSER_ERROR_MSG.unsupportedExprWithInfo(expr.toSql(), "PARTITION BY"),
                            pos);
                }
            }
        }
        return columnList;
    }

    private AlterClause getRollup(StarRocksParser.RollupItemContext rollupItemContext) {
        String rollupName = ((Identifier) visit(rollupItemContext.identifier())).getValue();
        List<Identifier> columnList =
                visit(rollupItemContext.identifierList().identifier(), Identifier.class);
        List<String> dupKeys = null;
        if (rollupItemContext.dupKeys() != null) {
            final List<Identifier> identifierList =
                    visit(rollupItemContext.dupKeys().identifierList().identifier(), Identifier.class);
            dupKeys = identifierList.stream().map(Identifier::getValue).collect(toList());
        }
        String baseRollupName = rollupItemContext.fromRollup() != null ?
                ((Identifier) visit(rollupItemContext.fromRollup().identifier())).getValue() : null;
        Map<String, String> properties = null;
        if (rollupItemContext.properties() != null) {
            properties = new HashMap<>();
            List<Property> propertyList = visit(rollupItemContext.properties().property(), Property.class);
            for (Property property : propertyList) {
                properties.put(property.getKey(), property.getValue());
            }
        }
        return new AddRollupClause(rollupName, columnList.stream().map(Identifier::getValue).collect(toList()),
                dupKeys, baseRollupName,
                properties, createPos(rollupItemContext));
    }

    private KeysDesc getKeysDesc(StarRocksParser.KeyDescContext context) {
        KeysType keysType = null;
        if (null != context.PRIMARY()) {
            keysType = KeysType.PRIMARY_KEYS;
        } else if (null != context.DUPLICATE()) {
            keysType = KeysType.DUP_KEYS;
        } else if (null != context.AGGREGATE()) {
            keysType = KeysType.AGG_KEYS;
        } else if (null != context.UNIQUE()) {
            keysType = KeysType.UNIQUE_KEYS;
        }
        List<Identifier> columnList = visit(context.identifierList().identifier(), Identifier.class);
        return new KeysDesc(keysType, columnList.stream().map(Identifier::getValue).collect(toList()),
                createPos(context));
    }

    private List<IndexDef> getIndexDefs(List<StarRocksParser.IndexDescContext> indexDesc) {
        List<IndexDef> indexDefList = new ArrayList<>();
        for (StarRocksParser.IndexDescContext context : indexDesc) {
            String indexName = ((Identifier) visit(context.identifier())).getValue();
            List<Identifier> columnList = visit(context.identifierList().identifier(), Identifier.class);
            String comment =
                    context.comment() != null ? ((StringLiteral) visit(context.comment())).getStringValue() : null;

            final IndexDef indexDef =
                    new IndexDef(indexName, columnList.stream().map(Identifier::getValue).collect(toList()),
                            getIndexType(context.indexType()), comment, getPropertyList(context.propertyList()),
                            createPos(context));
            indexDefList.add(indexDef);
        }
        return indexDefList;
    }

    private List<ColumnDef> getColumnDefs(List<StarRocksParser.ColumnDescContext> columnDesc) {
        return columnDesc.stream().map(context -> getColumnDef(context)).collect(toList());
    }

    private ColumnDef getColumnDef(StarRocksParser.ColumnDescContext context) {
        Identifier colIdentifier = (Identifier) visit(context.identifier());
        String columnName = colIdentifier.getValue();
        Pair<Type, AggStateDesc> typeWithAggStateDesc = getAggStateDesc(context.aggDesc());

        // get column's type
        Type columnType = null;
        if (context.type() == null) {
            // This can only happen when aggStateDesc is not null.
            if (typeWithAggStateDesc == null) {
                throw new ParsingException(PARSER_ERROR_MSG.unsupportedType("Column's type is null and it's not agg " +
                        "state column" + columnName), colIdentifier.getPos());
            } else {
                columnType = typeWithAggStateDesc.first;
            }
        } else {
            columnType = getType(context.type());
        }

        // get column's agg state desc
        AggStateDesc aggStateDesc = typeWithAggStateDesc == null ? null : typeWithAggStateDesc.second;
        NodePosition pos = context.type() == null ? NodePosition.ZERO : createPos(context.type());
        TypeDef typeDef = new TypeDef(columnType, pos);
        String charsetName = context.charsetName() != null ?
                ((Identifier) visit(context.charsetName().identifier())).getValue() : null;

        // get column's aggregate type
        AggregateType aggregateType = null;
        if (typeWithAggStateDesc != null) {
            aggregateType = AggregateType.AGG_STATE_UNION;
        } else {
            aggregateType = context.aggDesc() != null ?
                    AggregateType.valueOf(context.aggDesc().getText().toUpperCase()) : null;
        }

        // get column's nullable
        Boolean isAllowNull = null;
        if (context.columnNullable() != null) {
            if (context.columnNullable().NOT() != null) {
                isAllowNull = false;
            } else if (context.columnNullable().NULL() != null) {
                isAllowNull = true;
            }
        }
        // AGG_STATE_UNION can only be nullable for now, optimize it later.
        if (aggregateType != null && aggregateType.equals(AggregateType.AGG_STATE_UNION)) {
            if (isAllowNull != null && !isAllowNull) {
                throw new ParsingException(PARSER_ERROR_MSG.foundNotNull("Agg state column " + columnName),
                        colIdentifier.getPos());
            }
            if (aggStateDesc == null) {
                throw new ParsingException(PARSER_ERROR_MSG.invalidColumnDef(columnName), colIdentifier.getPos());
            }
            // use agg state column's nullable
            isAllowNull = aggStateDesc.getResultNullable();
        }

        boolean isKey = context.KEY() != null;
        Boolean isAutoIncrement = null;
        if (context.AUTO_INCREMENT() != null) {
            isAutoIncrement = true;
        }
        if (isAutoIncrement != null && isAllowNull != null && isAllowNull) {
            throw new ParsingException(PARSER_ERROR_MSG.nullColFoundInPK(columnName), colIdentifier.getPos());
        }
        if (isAutoIncrement != null) {
            isAllowNull = false;
        }
        ColumnDef.DefaultValueDef defaultValueDef = ColumnDef.DefaultValueDef.NOT_SET;
        final StarRocksParser.DefaultDescContext defaultDescContext = context.defaultDesc();
        if (defaultDescContext != null) {
            if (defaultDescContext.string() != null) {
                String value = ((StringLiteral) visit(defaultDescContext.string())).getStringValue();
                defaultValueDef = new ColumnDef.DefaultValueDef(true, new StringLiteral(value));
            } else if (defaultDescContext.NULL() != null) {
                defaultValueDef = ColumnDef.DefaultValueDef.NULL_DEFAULT_VALUE;
            } else if (defaultDescContext.CURRENT_TIMESTAMP() != null) {
                List<Expr> expr = Lists.newArrayList();
                if (defaultDescContext.INTEGER_VALUE() != null) {
                    expr.add(new IntLiteral(Long.parseLong(defaultDescContext.INTEGER_VALUE().getText()), Type.INT));
                }
                defaultValueDef = new ColumnDef.DefaultValueDef(true, (expr.size() == 1),
                        new FunctionCallExpr("current_timestamp", expr));
            } else if (defaultDescContext.qualifiedName() != null) {
                String functionName = defaultDescContext.qualifiedName().getText().toLowerCase();
                defaultValueDef = new ColumnDef.DefaultValueDef(true,
                        new FunctionCallExpr(functionName, new ArrayList<>()));
            }
        }
        final StarRocksParser.GeneratedColumnDescContext generatedColumnDescContext =
                context.generatedColumnDesc();
        Expr expr = null;
        if (generatedColumnDescContext != null) {
            if (isAllowNull != null && isAllowNull == false) {
                throw new ParsingException(PARSER_ERROR_MSG.foundNotNull("Generated Column"));
            }
            if (isKey) {
                throw new ParsingException(PARSER_ERROR_MSG.isKey("Generated Column"));
            }

            expr = (Expr) visit(generatedColumnDescContext.expression());
        }
        String comment = context.comment() == null ? "" :
                ((StringLiteral) visit(context.comment().string())).getStringValue();
        return new ColumnDef(columnName, typeDef, charsetName, isKey, aggregateType, aggStateDesc, isAllowNull, defaultValueDef,
                isAutoIncrement, expr, comment, createPos(context));
    }

    @Override
    public ParseNode visitCreateTableAsSelectStatement(StarRocksParser.CreateTableAsSelectStatementContext context) {
        Map<String, String> properties = new HashMap<>();
        if (context.properties() != null) {
            List<Property> propertyList = visit(context.properties().property(), Property.class);
            for (Property property : propertyList) {
                properties.put(property.getKey(), property.getValue());
            }
        }

        PartitionDesc partitionDesc = null;
        if (context.partitionDesc() != null) {
            partitionDesc = (PartitionDesc) visit(context.partitionDesc());
            if (partitionDesc instanceof ListPartitionDesc && context.partitionDesc().LIST() == null) {
                ((ListPartitionDesc) partitionDesc).setAutoPartitionTable(true);
            }
        }

        if (context.TEMPORARY() != null) {
            if (!Config.enable_experimental_temporary_table) {
                throw new ParsingException(
                        PARSER_ERROR_MSG.feConfigDisable("enable_experimental_temporary_table"), NodePosition.ZERO);
            }
            CreateTemporaryTableStmt createTemporaryTableStmt = new CreateTemporaryTableStmt(
                    context.IF() != null,
                    false,
                    qualifiedNameToTableName(getQualifiedName(context.qualifiedName())),
                    null,
                    context.indexDesc() == null ? null : getIndexDefs(context.indexDesc()),
                    "",
                    null,
                    context.keyDesc() == null ? null : getKeysDesc(context.keyDesc()),
                    partitionDesc,
                    context.distributionDesc() == null ? null : (DistributionDesc) visit(context.distributionDesc()),
                    properties,
                    null,
                    context.comment() == null ? null :
                            ((StringLiteral) visit(context.comment().string())).getStringValue(),
                    null,
                    context.orderByDesc() == null ? null :
                            visit(context.orderByDesc().identifierList().identifier(), Identifier.class)
                                    .stream().map(Identifier::getValue).collect(toList())
            );

            List<Identifier> columns = visitIfPresent(context.identifier(), Identifier.class);
            return new CreateTemporaryTableAsSelectStmt(
                    createTemporaryTableStmt,
                    columns == null ? null : columns.stream().map(Identifier::getValue).collect(toList()),
                    (QueryStatement) visit(context.queryStatement()),
                    createPos(context));
        }

        CreateTableStmt createTableStmt = new CreateTableStmt(
                context.IF() != null,
                false,
                qualifiedNameToTableName(getQualifiedName(context.qualifiedName())),
                null,
                context.indexDesc() == null ? null : getIndexDefs(context.indexDesc()),
                "",
                null,
                context.keyDesc() == null ? null : getKeysDesc(context.keyDesc()),
                partitionDesc,
                context.distributionDesc() == null ? null : (DistributionDesc) visit(context.distributionDesc()),
                properties,
                null,
                context.comment() == null ? null :
                        ((StringLiteral) visit(context.comment().string())).getStringValue(),
                null,
                context.orderByDesc() == null ? null :
                        visit(context.orderByDesc().identifierList().identifier(), Identifier.class)
                                .stream().map(Identifier::getValue).collect(toList())
        );

        List<Identifier> columns = visitIfPresent(context.identifier(), Identifier.class);
        return new CreateTableAsSelectStmt(
                createTableStmt,
                columns == null ? null : columns.stream().map(Identifier::getValue).collect(toList()),
                (QueryStatement) visit(context.queryStatement()),
                createPos(context));
    }

    @Override
    public ParseNode visitCreateTableLikeStatement(StarRocksParser.CreateTableLikeStatementContext context) {
        PartitionDesc partitionDesc = context.partitionDesc() == null ? null :
                (PartitionDesc) visit(context.partitionDesc());
        DistributionDesc distributionDesc = context.distributionDesc() == null ? null :
                (DistributionDesc) visit(context.distributionDesc());
        Map<String, String> properties = getProperties(context.properties());

        if (context.TEMPORARY() != null) {
            if (!Config.enable_experimental_temporary_table) {
                throw new ParsingException(
                        PARSER_ERROR_MSG.feConfigDisable("enable_experimental_temporary_table"), NodePosition.ZERO);
            }
            return new CreateTemporaryTableLikeStmt(context.IF() != null,
                    qualifiedNameToTableName(getQualifiedName(context.qualifiedName(0))),
                    qualifiedNameToTableName(getQualifiedName(context.qualifiedName(1))),
                    partitionDesc, distributionDesc, properties,
                    createPos(context));
        }

        return new CreateTableLikeStmt(context.IF() != null,
                qualifiedNameToTableName(getQualifiedName(context.qualifiedName(0))),
                qualifiedNameToTableName(getQualifiedName(context.qualifiedName(1))),
                partitionDesc, distributionDesc, properties,
                createPos(context));
    }

    @Override
    public ParseNode visitShowCreateTableStatement(StarRocksParser.ShowCreateTableStatementContext context) {
        QualifiedName qualifiedName = getQualifiedName(context.qualifiedName());
        TableName targetTableName = qualifiedNameToTableName(qualifiedName);
        NodePosition pos = createPos(context);
        if (context.MATERIALIZED() != null && context.VIEW() != null) {
            return new ShowCreateTableStmt(targetTableName, ShowCreateTableStmt.CreateTableType.MATERIALIZED_VIEW, pos);
        }
        if (context.VIEW() != null) {
            return new ShowCreateTableStmt(targetTableName, ShowCreateTableStmt.CreateTableType.VIEW, pos);
        }
        return new ShowCreateTableStmt(targetTableName, ShowCreateTableStmt.CreateTableType.TABLE, pos);
    }

    @Override
    public ParseNode visitDropTableStatement(StarRocksParser.DropTableStatementContext context) {
        boolean ifExists = context.IF() != null && context.EXISTS() != null;
        boolean isTemporary = context.TEMPORARY() != null;
        boolean force = context.FORCE() != null;
        QualifiedName qualifiedName = getQualifiedName(context.qualifiedName());
        TableName targetTableName = qualifiedNameToTableName(qualifiedName);
        if (isTemporary) {
            return new DropTemporaryTableStmt(ifExists, targetTableName, force);
        }
        return new DropTableStmt(ifExists, targetTableName, false, force, createPos(context));
    }

    @Override
    public ParseNode visitCleanTemporaryTableStatement(StarRocksParser.CleanTemporaryTableStatementContext context) {
        String sessionId = ((StringLiteral) visit(context.string())).getStringValue();
        try {
            return new CleanTemporaryTableStmt(UUID.fromString(sessionId));
        } catch (Throwable e) {
            throw new ParsingException("invalid session id format");
        }
    }

    @Override
    public ParseNode visitRecoverTableStatement(StarRocksParser.RecoverTableStatementContext context) {
        QualifiedName qualifiedName = getQualifiedName(context.qualifiedName());
        TableName tableName = qualifiedNameToTableName(qualifiedName);
        return new RecoverTableStmt(tableName, createPos(context));
    }

    @Override
    public ParseNode visitTruncateTableStatement(StarRocksParser.TruncateTableStatementContext context) {
        QualifiedName qualifiedName = getQualifiedName(context.qualifiedName());
        TableName targetTableName = qualifiedNameToTableName(qualifiedName);
        Token start = context.start;
        Token stop = context.stop;
        PartitionNames partitionNames = null;
        if (context.partitionNames() != null) {
            stop = context.partitionNames().stop;
            partitionNames = (PartitionNames) visit(context.partitionNames());
        }
        NodePosition pos = createPos(start, stop);
        return new TruncateTableStmt(new TableRef(targetTableName, null, partitionNames, pos));
    }

    @Override
    public ParseNode visitShowTableStatement(StarRocksParser.ShowTableStatementContext context) {
        boolean isVerbose = context.FULL() != null;
        String database = null;
        String catalog = null;
        // catalog.db
        if (context.qualifiedName() != null) {
            QualifiedName qualifiedName = getQualifiedName(context.qualifiedName());
            List<String> parts = qualifiedName.getParts();
            if (parts.size() == 2) {
                catalog = qualifiedName.getParts().get(0);
                database = qualifiedName.getParts().get(1);
            } else if (parts.size() == 1) {
                database = qualifiedName.getParts().get(0);
            }
        }

        NodePosition pos = createPos(context);

        if (context.pattern != null) {
            StringLiteral stringLiteral = (StringLiteral) visit(context.pattern);
            return new ShowTableStmt(database, isVerbose, stringLiteral.getValue(), null, catalog, pos);
        } else if (context.expression() != null) {
            return new ShowTableStmt(database, isVerbose, null, (Expr) visit(context.expression()), catalog, pos);
        } else {
            return new ShowTableStmt(database, isVerbose, null, null, catalog, pos);
        }
    }

    @Override
    public ParseNode visitShowTemporaryTablesStatement(StarRocksParser.ShowTemporaryTablesStatementContext context) {
        String database = null;
        String catalog = null;
        // catalog.db
        if (context.qualifiedName() != null) {
            QualifiedName qualifiedName = getQualifiedName(context.qualifiedName());
            List<String> parts = qualifiedName.getParts();
            if (parts.size() == 2) {
                catalog = qualifiedName.getParts().get(0);
                database = qualifiedName.getParts().get(1);
            } else if (parts.size() == 1) {
                database = qualifiedName.getParts().get(0);
            }
        }

        NodePosition pos = createPos(context);

        if (context.pattern != null) {
            StringLiteral stringLiteral = (StringLiteral) visit(context.pattern);
            return new ShowTemporaryTableStmt(database, stringLiteral.getValue(), null, catalog, pos);
        } else if (context.expression() != null) {
            return new ShowTemporaryTableStmt(database, null, (Expr) visit(context.expression()), catalog, pos);
        } else {
            return new ShowTemporaryTableStmt(database, null, null, catalog, pos);
        }
    }

    @Override
    public ParseNode visitDescTableStatement(StarRocksParser.DescTableStatementContext context) {
        if (context.qualifiedName() != null) {
            QualifiedName qualifiedName = getQualifiedName(context.qualifiedName());
            TableName targetTableName = qualifiedNameToTableName(qualifiedName);
            return new DescribeStmt(targetTableName, context.ALL() != null, createPos(context));
        }

        Map<String, String> tableFunctionProperties = getPropertyList(context.propertyList());
        return new DescribeStmt(tableFunctionProperties, createPos(context));
    }

    @Override
    public ParseNode visitShowTableStatusStatement(StarRocksParser.ShowTableStatusStatementContext context) {
        QualifiedName dbName = null;
        if (context.qualifiedName() != null) {
            dbName = getQualifiedName(context.db);
        }

        String pattern = null;
        if (context.pattern != null) {
            StringLiteral stringLiteral = (StringLiteral) visit(context.pattern);
            pattern = stringLiteral.getValue();
        }

        Expr where = null;
        if (context.expression() != null) {
            where = (Expr) visit(context.expression());
        }

        return new ShowTableStatusStmt(dbName == null ? null : dbName.toString(), pattern, where,
                createPos(context));
    }

    @Override
    public ParseNode visitShowColumnStatement(StarRocksParser.ShowColumnStatementContext context) {
        QualifiedName tableName = getQualifiedName(context.table);

        QualifiedName dbName = null;
        if (context.db != null) {
            dbName = getQualifiedName(context.db);
        }

        String pattern = null;
        if (context.pattern != null) {
            StringLiteral stringLiteral = (StringLiteral) visit(context.pattern);
            pattern = stringLiteral.getValue();
        }

        Expr where = null;
        if (context.expression() != null) {
            where = (Expr) visit(context.expression());
        }

        return new ShowColumnStmt(qualifiedNameToTableName(tableName),
                dbName == null ? null : dbName.toString(),
                pattern,
                context.FULL() != null,
                where, createPos(context));
    }

    @Override
    public ParseNode visitRefreshTableStatement(StarRocksParser.RefreshTableStatementContext context) {
        QualifiedName qualifiedName = getQualifiedName(context.qualifiedName());
        TableName targetTableName = qualifiedNameToTableName(qualifiedName);
        List<String> partitionNames = null;
        if (context.string() != null) {
            partitionNames = context.string().stream()
                    .map(c -> ((StringLiteral) visit(c)).getStringValue()).collect(toList());
        }
        return new RefreshTableStmt(targetTableName, partitionNames, createPos(context));
    }

    @Override
    public ParseNode visitAlterTableStatement(StarRocksParser.AlterTableStatementContext context) {
        QualifiedName qualifiedName = getQualifiedName(context.qualifiedName());
        TableName targetTableName = qualifiedNameToTableName(qualifiedName);
        NodePosition pos = createPos(context);
        if (context.ROLLUP() != null) {
            if (context.ADD() != null) {
                List<AlterClause> clauses = context.rollupItem().stream().map(this::getRollup).collect(toList());
                return new AlterTableStmt(targetTableName, clauses, pos);
            } else {
                List<Identifier> rollupList = visit(context.identifier(), Identifier.class);
                List<AlterClause> clauses = new ArrayList<>();
                for (Identifier rollupName : rollupList) {
                    clauses.add(new DropRollupClause(rollupName.getValue(), null, rollupName.getPos()));
                }
                return new AlterTableStmt(targetTableName, clauses, pos);
            }
        } else {
            List<AlterClause> alterClauses = visit(context.alterClause(), AlterClause.class);
            return new AlterTableStmt(targetTableName, alterClauses, pos);
        }
    }

    @Override
    public ParseNode visitCreateOrReplaceBranchClause(StarRocksParser.CreateOrReplaceBranchClauseContext context) {
        String branchName = getIdentifierName(context.identifier());

        BranchOptions branchOptions = BranchOptions.empty();
        if (context.branchOptions() != null) {
            StarRocksParser.BranchOptionsContext branchOptionsContext = context.branchOptions();
            Optional<Long> snapshotId = Optional.ofNullable(branchOptionsContext.snapshotId())
                    .map(id -> safeParseLong("snapshotId", id.number().getText()));

            Optional<Integer> minSnapshotsToKeep = Optional.empty();
            Optional<Long> maxSnapshotAgeMs = Optional.empty();
            StarRocksParser.SnapshotRetentionContext snapshotRetentionContext = branchOptionsContext.snapshotRetention();

            if (snapshotRetentionContext != null) {
                minSnapshotsToKeep = Optional.ofNullable(snapshotRetentionContext.minSnapshotsToKeep())
                        .map(minSnapshots -> safeParseInteger("minSnapshotsToKeep", minSnapshots.number().getText()));

                maxSnapshotAgeMs = Optional.ofNullable(snapshotRetentionContext.maxSnapshotAge())
                        .map(retain -> TimeUnit.valueOf(retain.timeUnit().getText().toUpperCase(Locale.ROOT))
                                .toMillis(safeParseInteger("maxSnapshotAgeMs", retain.number().getText())));
            }

            Optional<Long> branchRefAgeMs = Optional.ofNullable(branchOptionsContext.refRetain())
                    .map(retain -> TimeUnit.valueOf(retain.timeUnit().getText().toUpperCase(Locale.ROOT))
                            .toMillis(safeParseLong("branchRefAgeMs", retain.number().getText())));

            branchOptions = new BranchOptions(snapshotId, minSnapshotsToKeep, maxSnapshotAgeMs, branchRefAgeMs);
        }

        boolean create = context.CREATE() != null;
        boolean replace = context.REPLACE() != null;
        boolean ifNotExists = context.EXISTS() != null;

        return new CreateOrReplaceBranchClause(createPos(context), branchName, branchOptions, create, replace, ifNotExists);
    }

    @Override
    public ParseNode visitDropBranchClause(StarRocksParser.DropBranchClauseContext context) {
        String branchName = getIdentifierName(context.identifier());
        return new DropBranchClause(createPos(context), branchName, context.EXISTS() != null);
    }

    @Override
    public ParseNode visitDropTagClause(StarRocksParser.DropTagClauseContext context) {
        String branchName = getIdentifierName(context.identifier());
        return new DropTagClause(createPos(context), branchName, context.EXISTS() != null);
    }

    private Long safeParseLong(String name, String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new ParsingException("invalid %s value: %s. msg: %s", name, value, e.getMessage());
        }
    }

    private Integer safeParseInteger(String name, String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new ParsingException("invalid %s value: %s. msg: %s", name, value, e.getMessage());
        }
    }

    @Override
    public ParseNode visitCreateOrReplaceTagClause(StarRocksParser.CreateOrReplaceTagClauseContext context) {
        String tagName = getIdentifierName(context.identifier());

        StarRocksParser.TagOptionsContext tagOptionsContext = context.tagOptions();
        Optional<Long> snapshotId = Optional.ofNullable(tagOptionsContext.snapshotId())
                .map(id -> safeParseLong("snapshotId", id.number().getText()));

        Optional<Long> tagRefAgeMs = Optional.ofNullable(tagOptionsContext.refRetain())
                .map(retain -> TimeUnit.valueOf(retain.timeUnit().getText().toUpperCase(Locale.ROOT))
                        .toMillis(safeParseLong("tagRefAgeMs", retain.number().getText())));
        TagOptions tagOptions = new TagOptions(snapshotId, tagRefAgeMs);

        boolean create = context.CREATE() != null;
        boolean replace = context.REPLACE() != null;
        boolean ifNotExists = context.EXISTS() != null;

        return new CreateOrReplaceTagClause(createPos(context), tagName, tagOptions, create, replace, ifNotExists);
    }

    @Override
    public ParseNode visitTableOperationClause(StarRocksParser.TableOperationClauseContext context) {
        FunctionCallExpr fun = (FunctionCallExpr) visit(context.functionCall());
        return new AlterTableOperationClause(createPos(context), fun.getFnName().getFunction(), fun.getParams().exprs());
    }

    @Override
    public ParseNode visitCancelAlterTableStatement(StarRocksParser.CancelAlterTableStatementContext context) {
        ShowAlterStmt.AlterType alterType;
        if (context.ROLLUP() != null) {
            alterType = ShowAlterStmt.AlterType.ROLLUP;
        } else if (context.MATERIALIZED() != null && context.VIEW() != null) {
            alterType = ShowAlterStmt.AlterType.MATERIALIZED_VIEW;
        } else if (context.OPTIMIZE() != null) {
            alterType = ShowAlterStmt.AlterType.OPTIMIZE;
        } else {
            alterType = ShowAlterStmt.AlterType.COLUMN;
        }

        QualifiedName qualifiedName = getQualifiedName(context.qualifiedName());
        TableName dbTableName = qualifiedNameToTableName(qualifiedName);

        List<Long> alterJobIdList = null;
        if (context.INTEGER_VALUE() != null) {
            alterJobIdList = context.INTEGER_VALUE()
                    .stream().map(ParseTree::getText).map(Long::parseLong).collect(toList());
        }
        return new CancelAlterTableStmt(alterType, dbTableName, alterJobIdList, createPos(context));
    }

    @Override
    public ParseNode visitShowAlterStatement(StarRocksParser.ShowAlterStatementContext context) {
        QualifiedName dbName = null;
        if (context.db != null) {
            dbName = getQualifiedName(context.db);
        }
        Expr where = null;
        if (context.expression() != null) {
            where = (Expr) visit(context.expression());
        }
        ShowAlterStmt.AlterType alterType;
        if (context.ROLLUP() != null) {
            alterType = ShowAlterStmt.AlterType.ROLLUP;
        } else if (context.MATERIALIZED() != null && context.VIEW() != null) {
            alterType = ShowAlterStmt.AlterType.MATERIALIZED_VIEW;
        } else if (context.OPTIMIZE() != null) {
            alterType = ShowAlterStmt.AlterType.OPTIMIZE;
        } else {
            alterType = ShowAlterStmt.AlterType.COLUMN;
        }
        List<OrderByElement> orderByElements = null;
        if (context.ORDER() != null) {
            orderByElements = new ArrayList<>();
            orderByElements.addAll(visit(context.sortItem(), OrderByElement.class));
        }
        LimitElement limitElement = null;
        if (context.limitElement() != null) {
            limitElement = (LimitElement) visit(context.limitElement());
        }
        return new ShowAlterStmt(alterType, dbName == null ? null : dbName.toString(), where, orderByElements,
                limitElement, createPos(context));
    }

    // ------------------------------------------- View Statement ------------------------------------------------------

    @Override
    public ParseNode visitCreateViewStatement(StarRocksParser.CreateViewStatementContext context) {
        QualifiedName qualifiedName = getQualifiedName(context.qualifiedName());
        TableName targetTableName = qualifiedNameToTableName(qualifiedName);

        List<ColWithComment> colWithComments = null;
        if (context.columnNameWithComment().size() > 0) {
            colWithComments = visit(context.columnNameWithComment(), ColWithComment.class);
        }
        if (context.IF() != null && context.REPLACE() != null) {
            throw new ParsingException(PARSER_ERROR_MSG.conflictedOptions("if not exists", "or replace"),
                    createPos(context));
        }

        boolean isSecurity = false;
        if (context.SECURITY() != null) {
            if (context.NONE() != null) {
                isSecurity = false;
            } else if (context.INVOKER() != null) {
                isSecurity = true;
            }
        }

        return new CreateViewStmt(
                context.IF() != null,
                context.REPLACE() != null,
                targetTableName,
                colWithComments,
                context.comment() == null ? null : ((StringLiteral) visit(context.comment())).getStringValue(),
                isSecurity,
                (QueryStatement) visit(context.queryStatement()), createPos(context));
    }

    @Override
    public ParseNode visitAlterViewStatement(StarRocksParser.AlterViewStatementContext context) {
        QualifiedName qualifiedName = getQualifiedName(context.qualifiedName());
        TableName targetTableName = qualifiedNameToTableName(qualifiedName);

        List<ColWithComment> colWithComments = null;
        if (!context.columnNameWithComment().isEmpty()) {
            colWithComments = visit(context.columnNameWithComment(), ColWithComment.class);
        }

        boolean isSecurity = false;
        Map<String, String> properties = new HashMap<>();
        if (context.SECURITY() != null) {
            if (context.NONE() != null) {
                isSecurity = false;
            } else if (context.INVOKER() != null) {
                isSecurity = true;
            }

            return new AlterViewStmt(targetTableName, isSecurity, AlterViewStmt.AlterDialectType.NONE, properties,
                    null, createPos(context));
        } else if (context.properties() != null) {
            List<Property> propertyList = visit(context.properties().property(), Property.class);
            for (Property property : propertyList) {
                properties.put(property.getKey(), property.getValue());
            }
            return new AlterViewStmt(targetTableName, isSecurity, AlterViewStmt.AlterDialectType.NONE, properties,
                    null, createPos(context));
        } else {
            AlterViewStmt.AlterDialectType alterDialectType = context.ADD() != null ? AlterViewStmt.AlterDialectType.ADD :
                    context.MODIFY() != null ? AlterViewStmt.AlterDialectType.MODIFY : AlterViewStmt.AlterDialectType.NONE;
            QueryStatement queryStatement = (QueryStatement) visit(context.queryStatement());
            AlterViewClause alterClause = new AlterViewClause(colWithComments, queryStatement, createPos(context));
            return new AlterViewStmt(targetTableName, isSecurity, alterDialectType, properties, alterClause, createPos(context));
        }
    }

    @Override
    public ParseNode visitDropViewStatement(StarRocksParser.DropViewStatementContext context) {
        boolean ifExists = context.IF() != null && context.EXISTS() != null;
        QualifiedName qualifiedName = getQualifiedName(context.qualifiedName());
        TableName targetTableName = qualifiedNameToTableName(qualifiedName);
        return new DropTableStmt(ifExists, targetTableName, true, false, createPos(context));
    }

    // ------------------------------------------- Partition Statement ------------------------------------------------------

    @Override
    public ParseNode visitShowPartitionsStatement(StarRocksParser.ShowPartitionsStatementContext context) {
        boolean temp = context.TEMPORARY() != null;
        QualifiedName qualifiedName = getQualifiedName(context.qualifiedName());
        TableName tableName = qualifiedNameToTableName(qualifiedName);

        Expr where = null;
        if (context.expression() != null) {
            where = (Expr) visit(context.expression());
        }

        List<OrderByElement> orderByElements = new ArrayList<>();
        if (context.ORDER() != null) {
            orderByElements.addAll(visit(context.sortItem(), OrderByElement.class));
        }

        LimitElement limitElement = null;
        if (context.limitElement() != null) {
            limitElement = (LimitElement) visit(context.limitElement());
        }
        return new ShowPartitionsStmt(tableName, where, orderByElements, limitElement, temp, createPos(context));
    }

    @Override
    public ParseNode visitRecoverPartitionStatement(StarRocksParser.RecoverPartitionStatementContext context) {
        QualifiedName qualifiedName = getQualifiedName(context.qualifiedName());
        TableName tableName = qualifiedNameToTableName(qualifiedName);
        String partitionName = ((Identifier) visit(context.identifier())).getValue();
        return new RecoverPartitionStmt(tableName, partitionName, createPos(context));
    }

    // ------------------------------------------- Index Statement ------------------------------------------------------

    @Override
    public ParseNode visitShowTabletStatement(StarRocksParser.ShowTabletStatementContext context) {
        NodePosition pos = createPos(context);
        if (context.INTEGER_VALUE() != null) {
            return new ShowTabletStmt(null, Long.parseLong(context.INTEGER_VALUE().getText()), pos);
        } else {
            QualifiedName qualifiedName = getQualifiedName(context.qualifiedName());
            TableName dbTblName = qualifiedNameToTableName(qualifiedName);
            PartitionNames partitionNames = null;
            if (context.partitionNames() != null) {
                partitionNames = (PartitionNames) visit(context.partitionNames());
            }
            Expr where = null;
            if (context.expression() != null) {
                where = (Expr) visit(context.expression());
            }
            List<OrderByElement> orderByElements = null;
            if (context.ORDER() != null) {
                orderByElements = new ArrayList<>();
                orderByElements.addAll(visit(context.sortItem(), OrderByElement.class));
            }
            LimitElement limitElement = null;
            if (context.limitElement() != null) {
                limitElement = (LimitElement) visit(context.limitElement());
            }
            return new ShowTabletStmt(dbTblName, -1L, partitionNames, where, orderByElements, limitElement,
                    createPos(context));
        }
    }

    @Override
    public ParseNode visitCreateIndexStatement(StarRocksParser.CreateIndexStatementContext context) {
        String indexName = ((Identifier) visit(context.identifier())).getValue();
        List<Identifier> columnList = visit(context.identifierList().identifier(), Identifier.class);
        Token idxStart = context.identifier().start;
        Token idxStop = context.identifierList().stop;
        String comment = null;
        if (context.comment() != null) {
            comment = ((StringLiteral) visit(context.comment())).getStringValue();
            idxStop = context.comment().stop;
        }

        NodePosition idxPos = createPos(idxStart, idxStop);

        IndexDef indexDef = new IndexDef(indexName,
                columnList.stream().map(Identifier::getValue).collect(toList()),
                getIndexType(context.indexType()),
                comment, getPropertyList(context.propertyList()), idxPos);

        CreateIndexClause createIndexClause = new CreateIndexClause(indexDef, idxPos);

        QualifiedName qualifiedName = getQualifiedName(context.qualifiedName());
        TableName targetTableName = qualifiedNameToTableName(qualifiedName);
        return new AlterTableStmt(targetTableName, Lists.newArrayList(createIndexClause), createPos(context));
    }

    @Override
    public ParseNode visitDropIndexStatement(StarRocksParser.DropIndexStatementContext context) {
        Identifier identifier = (Identifier) visit(context.identifier());
        DropIndexClause dropIndexClause = new DropIndexClause(identifier.getValue(),
                createPos(context.identifier()));

        QualifiedName qualifiedName = getQualifiedName(context.qualifiedName());
        TableName targetTableName = qualifiedNameToTableName(qualifiedName);
        return new AlterTableStmt(targetTableName, Lists.newArrayList(dropIndexClause), createPos(context));
    }

    @Override
    public ParseNode visitShowIndexStatement(StarRocksParser.ShowIndexStatementContext context) {
        QualifiedName tableName = getQualifiedName(context.table);
        QualifiedName dbName = null;
        if (context.db != null) {
            dbName = getQualifiedName(context.db);
        }

        return new ShowIndexStmt(dbName == null ? null : dbName.toString(),
                qualifiedNameToTableName(tableName), createPos(context));
    }

    // ------------------------------------------- Task Statement ------------------------------------------------------

    private Map<String, String> buildProperties(StarRocksParser.PropertiesContext properties) {
        Map<String, String> result = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        if (properties != null) {
            List<Property> propertyList = visit(properties.property(), Property.class);
            for (Property property : ListUtils.emptyIfNull(propertyList)) {
                result.put(property.getKey(), property.getValue());
            }
        }
        return result;
    }

    private TaskSchedule parseTaskSchedule(StarRocksParser.TaskScheduleDescContext desc) {
        TaskSchedule schedule = new TaskSchedule();

        if (desc.START() != null) {
            NodePosition timePos = createPos(desc);
            StringLiteral stringLiteral = (StringLiteral) visit(desc.string());
            DateTimeFormatter dateTimeFormatter = null;
            try {
                dateTimeFormatter = DateUtils.probeFormat(stringLiteral.getStringValue());
                LocalDateTime startTime =
                        DateUtils.parseStringWithDefaultHSM(stringLiteral.getStringValue(), dateTimeFormatter);
                schedule.setStartTime(startTime.atZone(TimeUtils.getTimeZone().toZoneId()).toEpochSecond());
            } catch (SemanticException e) {
                throw new ParsingException(PARSER_ERROR_MSG.invalidDateFormat(stringLiteral.getStringValue()),
                        timePos);
            }
        }

        if (desc.taskInterval() != null) {
            var intervalLiteral = (IntervalLiteral) visit(desc.taskInterval());
            if (!(intervalLiteral.getValue() instanceof IntLiteral)) {
                String exprSql = intervalLiteral.getValue().toSql();
                throw new ParsingException(PARSER_ERROR_MSG.unsupportedExprWithInfo(exprSql, "INTERVAL"),
                        createPos(desc.taskInterval()));
            }

            long period = ((IntLiteral) intervalLiteral.getValue()).getLongValue();
            TimeUnit timeUnit = null;
            try {
                timeUnit = TimeUtils.convertUnitIdentifierToTimeUnit(
                        intervalLiteral.getUnitIdentifier().getDescription());
            } catch (DdlException e) {
                throw new ParsingException(PARSER_ERROR_MSG.unsupportedExprWithInfo(
                        intervalLiteral.getUnitIdentifier().getDescription(),
                        "INTERVAL "), createPos(desc.taskInterval()));
            }
            schedule.setPeriod(period);
            schedule.setTimeUnit(timeUnit);
        }

        return schedule;
    }

    private void parseTaskClause(List<StarRocksParser.TaskClauseContext> clauses, SubmitTaskStmt stmt) {
        for (var clause : clauses) {
            if (clause.properties() != null) {
                stmt.getProperties().putAll(buildProperties(clause.properties()));
            } else if (clause.taskScheduleDesc() != null) {
                stmt.setSchedule(parseTaskSchedule(clause.taskScheduleDesc()));
            }
        }
    }

    @Override
    public ParseNode visitSubmitTaskStatement(StarRocksParser.SubmitTaskStatementContext context) {
        QualifiedName qualifiedName = null;
        if (context.qualifiedName() != null) {
            qualifiedName = getQualifiedName(context.qualifiedName());
        }

        CreateTableAsSelectStmt createTableAsSelectStmt = null;
        InsertStmt insertStmt = null;
        DataCacheSelectStatement dataCacheSelectStmt = null;
        if (context.createTableAsSelectStatement() != null) {
            createTableAsSelectStmt = (CreateTableAsSelectStmt) visit(context.createTableAsSelectStatement());
        } else if (context.insertStatement() != null) {
            insertStmt = (InsertStmt) visit(context.insertStatement());
        } else if (context.dataCacheSelectStatement() != null) {
            dataCacheSelectStmt = (DataCacheSelectStatement) visit(context.dataCacheSelectStatement());
        }

        int startIndex = 0;
        if (createTableAsSelectStmt != null) {
            startIndex = context.createTableAsSelectStatement().start.getStartIndex();
        } else if (dataCacheSelectStmt != null) {
            startIndex = context.dataCacheSelectStatement().start.getStartIndex();
        } else {
            startIndex = context.insertStatement().start.getStartIndex();
        }

        NodePosition pos = createPos(context);
        TaskName taskName;
        if (qualifiedName == null) {
            taskName = new TaskName(null, null, pos);
        } else {
            taskName = qualifiedNameToTaskName(qualifiedName);
        }
        SubmitTaskStmt res;
        if (createTableAsSelectStmt != null) {
            res = new SubmitTaskStmt(taskName, startIndex, createTableAsSelectStmt, pos);
        } else if (dataCacheSelectStmt != null) {
            res = new SubmitTaskStmt(taskName, startIndex, dataCacheSelectStmt, pos);
        } else {
            res = new SubmitTaskStmt(taskName, startIndex, insertStmt, pos);
        }
        res.getProperties().putAll(extractVarHintValues(hintMap.get(context)));
        parseTaskClause(context.taskClause(), res);
        return res;
    }

    @Override
    public ParseNode visitDropTaskStatement(StarRocksParser.DropTaskStatementContext context) {
        QualifiedName qualifiedName = getQualifiedName(context.qualifiedName());
        TaskName taskName = qualifiedNameToTaskName(qualifiedName);
        boolean force = context.FORCE() != null;
        return new DropTaskStmt(taskName, context.IF() != null, force, createPos(context));
    }

    // ------------------------------------------- Materialized View Statement -----------------------------------------

    public static final ImmutableList<String> MATERIALIZEDVIEW_REFRESHSCHEME_SUPPORT_UNIT_IDENTIFIERS =
            new ImmutableList.Builder<String>()
                    .add("SECOND").add("MINUTE").add("HOUR").add("DAY")
                    .build();

    private void checkMaterializedViewAsyncRefreshSchemeUnitIdentifier(
            AsyncRefreshSchemeDesc asyncRefreshSchemeDesc) {
        if (asyncRefreshSchemeDesc.getIntervalLiteral() == null ||
                asyncRefreshSchemeDesc.getIntervalLiteral().getUnitIdentifier() == null) {
            return;
        }
        String unit = asyncRefreshSchemeDesc.getIntervalLiteral().getUnitIdentifier().getDescription();
        if (StringUtils.isEmpty(unit)) {
            return;
        }

        if (!MATERIALIZEDVIEW_REFRESHSCHEME_SUPPORT_UNIT_IDENTIFIERS.contains(unit)) {
            throw new ParsingException(PARSER_ERROR_MSG.forbidClauseInMV("Refresh interval unit", unit),
                    asyncRefreshSchemeDesc.getIntervalLiteral().getUnitIdentifier().getPos());
        }
    }

    @Override
    public ParseNode visitCreateMaterializedViewStatement(
            StarRocksParser.CreateMaterializedViewStatementContext context) {
        boolean ifNotExist = context.IF() != null;
        QualifiedName qualifiedName = getQualifiedName(context.mvName);
        TableName tableName = qualifiedNameToTableName(qualifiedName);

        List<ColWithComment> colWithComments = null;
        if (!context.columnNameWithComment().isEmpty()) {
            colWithComments = visit(context.columnNameWithComment(), ColWithComment.class);
        }

        String comment =
                context.comment() == null ? null : ((StringLiteral) visit(context.comment().string())).getStringValue();
        QueryStatement queryStatement = (QueryStatement) visit(context.queryStatement());
        int queryStartIndex = context.queryStatement().start.getStartIndex();
        int queryStopIndex = context.queryStatement().stop.getStopIndex() + 1;

        RefreshSchemeClause refreshSchemeDesc = null;
        Map<String, String> properties = new HashMap<>();
        List<Expr> partitionByExprs = null;
        DistributionDesc distributionDesc = null;
        List<String> sortKeys = null;

        for (StarRocksParser.MaterializedViewDescContext desc : ListUtils.emptyIfNull(context.materializedViewDesc())) {
            NodePosition clausePos = createPos(desc);
            // process properties
            if (desc.properties() != null) {
                if (MapUtils.isNotEmpty(properties)) {
                    throw new ParsingException(PARSER_ERROR_MSG.duplicatedClause("PROPERTY", "building materialized view"),
                            clausePos);
                }
                List<Property> propertyList = visit(desc.properties().property(), Property.class);
                for (Property property : propertyList) {
                    properties.put(property.getKey(), property.getValue());
                }
            }
            // process refresh
            if (desc.refreshSchemeDesc() != null) {
                if (refreshSchemeDesc != null) {
                    throw new ParsingException(PARSER_ERROR_MSG.duplicatedClause("REFRESH", "building materialized view"),
                            clausePos);
                }
                refreshSchemeDesc = ((RefreshSchemeClause) visit(desc.refreshSchemeDesc()));
            }

            // process partition by
            if (desc.mvPartitionExprs() != null) {
                if (partitionByExprs != null) {
                    throw new ParsingException(PARSER_ERROR_MSG.duplicatedClause("PARTITION", "building materialized view"),
                            clausePos);
                }
                partitionByExprs = Lists.newArrayList();
                List<StarRocksParser.PrimaryExpressionContext> primaryExpressionContexts =
                        desc.mvPartitionExprs().primaryExpression();

                for (var primaryExpression : primaryExpressionContexts) {
                    Expr expr = (Expr) visit(primaryExpression);
                    if (expr instanceof SlotRef) {
                        partitionByExprs.add(expr);
                    } else if (expr instanceof FunctionCallExpr) {
                        AnalyzerUtils.checkAndExtractPartitionCol((FunctionCallExpr) expr, null,
                                AnalyzerUtils.MV_DATE_TRUNC_SUPPORTED_PARTITION_FORMAT);
                        partitionByExprs.add(expr);
                    } else {
                        throw new ParsingException(PARSER_ERROR_MSG.unsupportedExprWithInfo(expr.toSql(), "PARTITION BY"),
                                expr.getPos());
                    }
                }
            }

            // process distribution
            if (desc.distributionDesc() != null) {
                if (distributionDesc != null) {
                    throw new ParsingException(PARSER_ERROR_MSG.duplicatedClause("DISTRIBUTION", "building materialized view"),
                            clausePos);
                }
                distributionDesc = (DistributionDesc) visit(desc.distributionDesc());
            }

            // Order By
            if (desc.orderByDesc() != null) {
                sortKeys = visit(desc.orderByDesc().identifierList().identifier(), Identifier.class)
                        .stream().map(Identifier::getValue).collect(toList());
            }
        }

        if (refreshSchemeDesc == null) {
            if (distributionDesc == null) {
                // use old materialized index
                refreshSchemeDesc = new SyncRefreshSchemeDesc();
            } else {
                // use new manual refresh
                refreshSchemeDesc =
                        new ManualRefreshSchemeDesc(MaterializedView.RefreshMoment.IMMEDIATE, NodePosition.ZERO);
            }
        }
        if (refreshSchemeDesc instanceof SyncRefreshSchemeDesc) {
            if (CollectionUtils.isNotEmpty(partitionByExprs)) {
                throw new ParsingException(PARSER_ERROR_MSG.forbidClauseInMV("SYNC refresh type", "PARTITION BY"),
                        partitionByExprs.get(0));
            }
            if (distributionDesc != null) {
                throw new ParsingException(PARSER_ERROR_MSG.forbidClauseInMV("SYNC refresh type", "DISTRIBUTION BY"),
                        distributionDesc.getPos());
            }
            return new CreateMaterializedViewStmt(tableName, queryStatement, properties);
        }
        if (refreshSchemeDesc instanceof AsyncRefreshSchemeDesc) {
            AsyncRefreshSchemeDesc asyncRefreshSchemeDesc = (AsyncRefreshSchemeDesc) refreshSchemeDesc;
            checkMaterializedViewAsyncRefreshSchemeUnitIdentifier(asyncRefreshSchemeDesc);
        }

        if (!Config.enable_experimental_mv) {
            throw new ParsingException(PARSER_ERROR_MSG.feConfigDisable("enable_experimental_mv"), NodePosition.ZERO);
        }

        String currentDBName = ConnectContext.get() == null ? null : ConnectContext.get().getDatabase();
        return new CreateMaterializedViewStatement(tableName, ifNotExist, colWithComments,
                context.indexDesc() == null ? null : getIndexDefs(context.indexDesc()),
                comment,
                refreshSchemeDesc,
                partitionByExprs, distributionDesc, sortKeys, properties, queryStatement, queryStartIndex, queryStopIndex,
                currentDBName,
                createPos(context));
    }

    @Override
    public ParseNode visitShowMaterializedViewsStatement(
            StarRocksParser.ShowMaterializedViewsStatementContext context) {
        String database = null;
        String catalog = null;
        NodePosition pos = createPos(context);
        if (context.qualifiedName() != null) {
            QualifiedName qualifiedName = getQualifiedName(context.qualifiedName());
            List<String> parts = qualifiedName.getParts();
            if (parts.size() == 2) {
                catalog = qualifiedName.getParts().get(0);
                database = qualifiedName.getParts().get(1);
            } else if (parts.size() == 1) {
                database = qualifiedName.getParts().get(0);
            }
        }
        if (context.pattern != null) {
            StringLiteral stringLiteral = (StringLiteral) visit(context.pattern);
            return new ShowMaterializedViewsStmt(catalog, database, stringLiteral.getValue(), null, pos);
        } else if (context.expression() != null) {
            return new ShowMaterializedViewsStmt(catalog, database, null, (Expr) visit(context.expression()), pos);
        } else {
            return new ShowMaterializedViewsStmt(catalog, database, null, null, pos);
        }
    }

    @Override
    public ParseNode visitDropMaterializedViewStatement(StarRocksParser.DropMaterializedViewStatementContext context) {
        QualifiedName mvQualifiedName = getQualifiedName(context.qualifiedName());
        TableName mvName = qualifiedNameToTableName(mvQualifiedName);
        return new DropMaterializedViewStmt(context.IF() != null, mvName, createPos(context));
    }

    @Override
    public ParseNode visitAlterMaterializedViewStatement(
            StarRocksParser.AlterMaterializedViewStatementContext context) {
        QualifiedName mvQualifiedName = getQualifiedName(context.qualifiedName());
        TableName mvName = qualifiedNameToTableName(mvQualifiedName);
        AlterTableClause alterTableClause = null;

        if (context.tableRenameClause() != null) {
            alterTableClause = (TableRenameClause) visit(context.tableRenameClause());
        }

        // process refresh
        if (context.refreshSchemeDesc() != null) {
            alterTableClause = ((RefreshSchemeClause) visit(context.refreshSchemeDesc()));
            if (alterTableClause instanceof AsyncRefreshSchemeDesc) {
                AsyncRefreshSchemeDesc asyncRefreshSchemeDesc = (AsyncRefreshSchemeDesc) alterTableClause;
                checkMaterializedViewAsyncRefreshSchemeUnitIdentifier(asyncRefreshSchemeDesc);
            }
        }

        if (context.modifyPropertiesClause() != null) {
            alterTableClause = (ModifyTablePropertiesClause) visit(context.modifyPropertiesClause());
        }

        if (context.statusDesc() != null) {
            String status = context.statusDesc().getText();
            alterTableClause = new AlterMaterializedViewStatusClause(status, createPos(context));
        }
        // swap table
        if (context.swapTableClause() != null) {
            alterTableClause = (SwapTableClause) visit(context.swapTableClause());
        }
        return new AlterMaterializedViewStmt(mvName, alterTableClause, createPos(context));
    }

    @Override
    public ParseNode visitRefreshMaterializedViewStatement(
            StarRocksParser.RefreshMaterializedViewStatementContext context) {
        QualifiedName mvQualifiedName = getQualifiedName(context.qualifiedName());
        TableName mvName = qualifiedNameToTableName(mvQualifiedName);
        PartitionRangeDesc rangePartitionDesc = null;
        Set<PListCell> cells = null;
        if (context.partitionRangeDesc() != null) {
            rangePartitionDesc =
                    (PartitionRangeDesc) visit(context.partitionRangeDesc());
        } else if (context.listPartitionValues() != null) {
            StarRocksParser.ListPartitionValuesContext listPartitionValuesContext =
                    context.listPartitionValues();
            if (listPartitionValuesContext.multiListPartitionValues() != null) {
                List<List<String>> multiListValues =
                        parseMultiListPartitionValues(listPartitionValuesContext.multiListPartitionValues());
                cells = multiListValues.stream()
                        .map(items -> new PListCell(ImmutableList.of(items)))
                        .collect(Collectors.toSet());
            } else {
                List<String> singleListValues =
                        parseSingleListPartitionValues(listPartitionValuesContext.singleListPartitionValues());
                cells = singleListValues.stream()
                        .map(item -> new PListCell(item))
                        .collect(Collectors.toSet());
            }
        }
        return new RefreshMaterializedViewStatement(mvName, new EitherOr(rangePartitionDesc, cells),
                context.FORCE() != null, context.SYNC() != null,
                context.priority != null ? Integer.parseInt(context.priority.getText()) : null,
                createPos(context));
    }

    @Override
    public ParseNode visitCancelRefreshMaterializedViewStatement(
            StarRocksParser.CancelRefreshMaterializedViewStatementContext context) {
        QualifiedName mvQualifiedName = getQualifiedName(context.qualifiedName());
        TableName mvName = qualifiedNameToTableName(mvQualifiedName);
        boolean force = context.FORCE() != null;
        return new CancelRefreshMaterializedViewStmt(mvName, force, createPos(context));
    }

    // ------------------------------------------- Catalog Statement ---------------------------------------------------

    @Override
    public ParseNode visitCreateExternalCatalogStatement(
            StarRocksParser.CreateExternalCatalogStatementContext context) {
        boolean ifNotExists = context.IF() != null;
        Identifier identifier = (Identifier) visit(context.identifierOrString());
        String catalogName = identifier.getValue();
        String comment = null;
        if (context.comment() != null) {
            comment = ((StringLiteral) visit(context.comment())).getStringValue();
        }
        Map<String, String> properties = new HashMap<>();
        if (context.properties() != null) {
            List<Property> propertyList = visit(context.properties().property(), Property.class);
            for (Property property : propertyList) {
                properties.put(property.getKey(), property.getValue());
            }
        }

        return new CreateCatalogStmt(catalogName, comment, properties, ifNotExists, createPos(context));
    }

    @Override
    public ParseNode visitDropExternalCatalogStatement(StarRocksParser.DropExternalCatalogStatementContext context) {
        Identifier identifier = (Identifier) visit(context.catalogName);
        boolean ifExists = context.IF() != null;
        String catalogName = identifier.getValue();
        return new DropCatalogStmt(catalogName, ifExists, createPos(context));
    }

    @Override
    public ParseNode visitShowCreateExternalCatalogStatement(
            StarRocksParser.ShowCreateExternalCatalogStatementContext context) {
        Identifier identifier = (Identifier) visit(context.catalogName);
        String catalogName = identifier.getValue();
        return new ShowCreateExternalCatalogStmt(catalogName, createPos(context));
    }

    @Override
    public ParseNode visitShowCatalogsStatement(StarRocksParser.ShowCatalogsStatementContext context) {
        NodePosition pos = createPos(context);
        if (context.pattern != null) {
            StringLiteral stringLiteral = (StringLiteral) visit(context.pattern);
            return new ShowCatalogsStmt(stringLiteral.getValue(), pos);
        }
        return new ShowCatalogsStmt(null, createPos(context));
    }

    @Override
    public ParseNode visitAlterCatalogStatement(StarRocksParser.AlterCatalogStatementContext context) {
        String catalogName = ((Identifier) visit(context.catalogName)).getValue();
        AlterClause alterClause = (AlterClause) visit(context.modifyPropertiesClause());
        return new AlterCatalogStmt(catalogName, alterClause, createPos(context));
    }

    // ------------------------------------------- DML Statement -------------------------------------------------------
    @Override
    public ParseNode visitInsertStatement(StarRocksParser.InsertStatementContext context) {
        QueryStatement queryStatement;
        if (context.VALUES() != null) {
            List<ValueList> rowValues = visit(context.expressionsWithDefault(), ValueList.class);
            List<List<Expr>> rows = rowValues.stream().map(ValueList::getRow).collect(toList());

            List<String> colNames = new ArrayList<>();
            for (int i = 0; i < rows.get(0).size(); ++i) {
                colNames.add("column_" + i);
            }

            queryStatement = new QueryStatement(new ValuesRelation(rows, colNames,
                    createPos(context.VALUES().getSymbol(), context.stop)));
        } else {
            queryStatement = (QueryStatement) visit(context.queryStatement());
        }

        if (context.explainDesc() != null) {
            queryStatement.setIsExplain(true, getExplainType(context.explainDesc()));
        }

        if (context.qualifiedName() != null) {
            QualifiedName qualifiedName = getQualifiedName(context.qualifiedName());
            TableName targetTableName = qualifiedNameToTableName(qualifiedName);
            PartitionNames partitionNames = null;
            if (context.partitionNames() != null) {
                partitionNames = (PartitionNames) visit(context.partitionNames());
            }

            String targetBranch = null;
            if (context.writeBranch() != null) {
                targetBranch = ((Identifier) visit(context.writeBranch())).getValue();
            }

            String label = null;
            boolean hasColumnAliases = false;
            boolean hasByName = false;
            List<String> columnAliases = null;
            InsertStmt.ColumnMatchPolicy columnMatchPolicy = InsertStmt.ColumnMatchPolicy.POSITION;
            for (StarRocksParser.InsertLabelOrColumnAliasesContext desc : ListUtils.emptyIfNull(
                    context.insertLabelOrColumnAliases())) {
                NodePosition clausePos = createPos(desc);
                if (desc.label != null) {
                    if (label != null) {
                        throw new ParsingException(PARSER_ERROR_MSG.duplicatedClause("WITH LABEL", "insert"), clausePos);
                    }
                    label = ((Identifier) visit(desc.label)).getValue();
                }
                if (desc.columnAliasesOrByName() != null) {
                    StarRocksParser.ColumnAliasesOrByNameContext columnAliasesOrByNameContext = desc.columnAliasesOrByName();
                    if (hasColumnAliases && columnAliasesOrByNameContext.columnAliases() != null) {
                        throw new ParsingException(PARSER_ERROR_MSG.duplicatedClause("COLUMN LIST", "insert"), clausePos);
                    } else if (hasByName && columnAliasesOrByNameContext.BY() != null) {
                        throw new ParsingException(PARSER_ERROR_MSG.duplicatedClause("BY NAME", "insert"), clausePos);
                    } else if (hasColumnAliases || hasByName) {
                        throw new ParsingException("Cannot use COLUMN LIST and BY NAME clause together in insert");
                    }

                    if (columnAliasesOrByNameContext.columnAliases() != null) {
                        columnAliases = getColumnNames(columnAliasesOrByNameContext.columnAliases());
                        hasColumnAliases = true;
                    } else {
                        Preconditions.checkState(columnAliasesOrByNameContext.BY() != null &&
                                columnAliasesOrByNameContext.NAME() != null);
                        columnMatchPolicy = InsertStmt.ColumnMatchPolicy.NAME;
                        hasByName = true;
                    }
                }
            }

            InsertStmt stmt = new InsertStmt(targetTableName, partitionNames, label, columnAliases, queryStatement,
                    context.OVERWRITE() != null, getProperties(context.properties()), createPos(context));
            stmt.setHintNodes(hintMap.get(context));
            stmt.setTargetBranch(targetBranch);
            stmt.setColumnMatchPolicy(columnMatchPolicy);
            return stmt;
        }

        if (context.BLACKHOLE() != null) {
            return new InsertStmt(queryStatement, createPos(context));
        }

        // INSERT INTO FILES(...)
        Map<String, String> tableFunctionProperties = getPropertyList(context.propertyList());
        InsertStmt res = new InsertStmt(tableFunctionProperties, queryStatement, createPos(context));
        res.setHintNodes(hintMap.get(context));
        return res;
    }

    @Override
    public ParseNode visitUpdateStatement(StarRocksParser.UpdateStatementContext context) {
        List<CTERelation> ctes = null;
        if (context.withClause() != null) {
            ctes = visit(context.withClause().commonTableExpression(), CTERelation.class);
        }
        QualifiedName qualifiedName = getQualifiedName(context.qualifiedName());
        TableName targetTableName = qualifiedNameToTableName(qualifiedName);
        List<ColumnAssignment> assignments = visit(context.assignmentList().assignment(), ColumnAssignment.class);
        List<Relation> fromRelations = null;
        if (context.fromClause() instanceof StarRocksParser.DualContext) {
            ValuesRelation valuesRelation = ValuesRelation.newDualRelation(createPos(context.fromClause()));
            fromRelations = Lists.newArrayList(valuesRelation);
        } else {
            StarRocksParser.FromContext fromContext = (StarRocksParser.FromContext) context.fromClause();
            if (fromContext.relations() != null) {
                fromRelations = visit(fromContext.relations().relation(), Relation.class);
            }
        }
        Expr where = context.where != null ? (Expr) visit(context.where) : null;
        UpdateStmt ret = new UpdateStmt(targetTableName, assignments, fromRelations, where, ctes, createPos(context));
        if (context.explainDesc() != null) {
            ret.setIsExplain(true, getExplainType(context.explainDesc()));
            if (StatementBase.ExplainLevel.ANALYZE.equals(ret.getExplainLevel())) {
                throw new ParsingException(PARSER_ERROR_MSG.unsupportedOp("analyze"));
            }
        }
        ret.setHintNodes(hintMap.get(context));
        return ret;
    }

    @Override
    public ParseNode visitDeleteStatement(StarRocksParser.DeleteStatementContext context) {
        List<CTERelation> ctes = null;
        if (context.withClause() != null) {
            ctes = visit(context.withClause().commonTableExpression(), CTERelation.class);
        }
        QualifiedName qualifiedName = getQualifiedName(context.qualifiedName());
        TableName targetTableName = qualifiedNameToTableName(qualifiedName);
        PartitionNames partitionNames = null;
        if (context.partitionNames() != null) {
            partitionNames = (PartitionNames) visit(context.partitionNames());
        }
        List<Relation> usingRelations = context.using != null ? visit(context.using.relation(), Relation.class) : null;
        Expr where = context.where != null ? (Expr) visit(context.where) : null;
        DeleteStmt ret =
                new DeleteStmt(targetTableName, partitionNames, usingRelations, where, ctes, createPos(context));
        if (context.explainDesc() != null) {
            ret.setIsExplain(true, getExplainType(context.explainDesc()));
            if (StatementBase.ExplainLevel.ANALYZE.equals(ret.getExplainLevel())) {
                throw new ParsingException(PARSER_ERROR_MSG.unsupportedOp("analyze"));
            }
        }
        ret.setHintNodes(hintMap.get(context));
        return ret;
    }

    // ------------------------------------------- Routine Statement ---------------------------------------------------

    @Override
    public ParseNode visitCreateRoutineLoadStatement(StarRocksParser.CreateRoutineLoadStatementContext context) {
        QualifiedName tableName = null;
        if (context.table != null) {
            tableName = getQualifiedName(context.table);
        }

        List<StarRocksParser.LoadPropertiesContext> loadPropertiesContexts = context.loadProperties();
        List<ParseNode> loadPropertyList = getLoadPropertyList(loadPropertiesContexts);
        String typeName = context.source.getText();
        Map<String, String> jobProperties = getJobProperties(context.jobProperties());
        Map<String, String> dataSourceProperties = getDataSourceProperties(context.dataSourceProperties());

        return new CreateRoutineLoadStmt(createLabelName(context.db, context.name),
                tableName == null ? null : tableName.toString(), loadPropertyList, jobProperties, typeName,
                dataSourceProperties, createPos(context));
    }

    @Override
    public ParseNode visitShowCreateRoutineLoadStatement(
            StarRocksParser.ShowCreateRoutineLoadStatementContext context) {
        return new ShowCreateRoutineLoadStmt(createLabelName(context.db, context.name));
    }

    @Override
    public ParseNode visitAlterRoutineLoadStatement(StarRocksParser.AlterRoutineLoadStatementContext context) {
        NodePosition pos = createPos(context);
        List<StarRocksParser.LoadPropertiesContext> loadPropertiesContexts = context.loadProperties();
        List<ParseNode> loadPropertyList = getLoadPropertyList(loadPropertiesContexts);
        Map<String, String> jobProperties = getJobProperties(context.jobProperties());

        if (context.dataSource() != null) {
            String typeName = context.dataSource().source.getText();
            Map<String, String> dataSourceProperties =
                    getDataSourceProperties(context.dataSource().dataSourceProperties());
            RoutineLoadDataSourceProperties dataSource =
                    new RoutineLoadDataSourceProperties(typeName, dataSourceProperties,
                            createPos(context.dataSource()));
            return new AlterRoutineLoadStmt(createLabelName(context.db, context.name),
                    loadPropertyList, jobProperties, dataSource, pos);
        }

        return new AlterRoutineLoadStmt(createLabelName(context.db, context.name), loadPropertyList, jobProperties,
                new RoutineLoadDataSourceProperties(), pos);
    }

    @Override
    public ParseNode visitAlterLoadStatement(StarRocksParser.AlterLoadStatementContext context) {
        Map<String, String> jobProperties = getJobProperties(context.jobProperties());

        return new AlterLoadStmt(createLabelName(context.db, context.name), jobProperties, createPos(context));
    }

    @Override
    public ParseNode visitStopRoutineLoadStatement(StarRocksParser.StopRoutineLoadStatementContext context) {
        return new StopRoutineLoadStmt(createLabelName(context.db, context.name), createPos(context));
    }

    @Override
    public ParseNode visitResumeRoutineLoadStatement(StarRocksParser.ResumeRoutineLoadStatementContext context) {
        return new ResumeRoutineLoadStmt(createLabelName(context.db, context.name), createPos(context));
    }

    @Override
    public ParseNode visitPauseRoutineLoadStatement(StarRocksParser.PauseRoutineLoadStatementContext context) {
        return new PauseRoutineLoadStmt(createLabelName(context.db, context.name), createPos(context));
    }

    @Override
    public ParseNode visitShowRoutineLoadStatement(StarRocksParser.ShowRoutineLoadStatementContext context) {
        boolean isVerbose = context.ALL() != null;
        String database = null;
        Expr where = null;
        if (context.expression() != null) {
            where = (Expr) visit(context.expression());
        }
        List<OrderByElement> orderByElements = null;
        if (context.ORDER() != null) {
            orderByElements = new ArrayList<>();
            orderByElements.addAll(visit(context.sortItem(), OrderByElement.class));
        }
        LimitElement limitElement = null;
        if (context.limitElement() != null) {
            limitElement = (LimitElement) visit(context.limitElement());
        }
        return new ShowRoutineLoadStmt(createLabelName(context.db, context.name), isVerbose, where, orderByElements,
                limitElement, createPos(context));
    }

    @Override
    public ParseNode visitShowRoutineLoadTaskStatement(StarRocksParser.ShowRoutineLoadTaskStatementContext context) {
        QualifiedName dbName = null;
        if (context.db != null) {
            dbName = getQualifiedName(context.db);
        }

        Expr where = null;
        if (context.expression() != null) {
            where = (Expr) visit(context.expression());
        }
        return new ShowRoutineLoadTaskStmt(dbName == null ? null : dbName.toString(), where, createPos(context));
    }

    @Override
    public ParseNode visitShowStreamLoadStatement(StarRocksParser.ShowStreamLoadStatementContext context) {
        boolean isVerbose = context.ALL() != null;
        String database = null;
        Expr where = null;
        if (context.expression() != null) {
            where = (Expr) visit(context.expression());
        }
        List<OrderByElement> orderByElements = null;
        if (context.ORDER() != null) {
            orderByElements = new ArrayList<>();
            orderByElements.addAll(visit(context.sortItem(), OrderByElement.class));
        }
        LimitElement limitElement = null;
        if (context.limitElement() != null) {
            limitElement = (LimitElement) visit(context.limitElement());
        }
        return new ShowStreamLoadStmt(createLabelName(context.db, context.name), isVerbose, where, orderByElements,
                limitElement, createPos(context));
    }

    // ------------------------------------------- Admin Statement -----------------------------------------------------

    @Override
    public ParseNode visitAdminSetConfigStatement(StarRocksParser.AdminSetConfigStatementContext context) {
        Property config = (Property) visitProperty(context.property());
        boolean persistent = context.PERSISTENT() != null;
        return new AdminSetConfigStmt(AdminSetConfigStmt.ConfigType.FRONTEND, config, persistent, createPos(context));
    }

    @Override
    public ParseNode visitAdminSetReplicaStatusStatement(
            StarRocksParser.AdminSetReplicaStatusStatementContext context) {
        List<Property> propertyList = visit(context.properties().property(), Property.class);
        return new AdminSetReplicaStatusStmt(new PropertySet(propertyList, createPos(context.properties())),
                createPos(context));
    }

    @Override
    public ParseNode visitAdminShowConfigStatement(StarRocksParser.AdminShowConfigStatementContext context) {
        NodePosition pos = createPos(context);
        if (context.pattern != null) {
            StringLiteral stringLiteral = (StringLiteral) visit(context.pattern);
            return new AdminShowConfigStmt(AdminSetConfigStmt.ConfigType.FRONTEND, stringLiteral.getValue(), pos);
        }
        return new AdminShowConfigStmt(AdminSetConfigStmt.ConfigType.FRONTEND, null, pos);
    }

    @Override
    public ParseNode visitAdminShowReplicaDistributionStatement(
            StarRocksParser.AdminShowReplicaDistributionStatementContext context) {
        Token start = context.qualifiedName().start;
        Token stop = context.qualifiedName().stop;
        QualifiedName qualifiedName = getQualifiedName(context.qualifiedName());
        TableName targetTableName = qualifiedNameToTableName(qualifiedName);
        PartitionNames partitionNames = null;
        if (context.partitionNames() != null) {
            stop = context.partitionNames().stop;
            partitionNames = (PartitionNames) visit(context.partitionNames());
        }
        return new AdminShowReplicaDistributionStmt(new TableRef(targetTableName, null,
                partitionNames, createPos(start, stop)),
                createPos(context));
    }

    @Override
    public ParseNode visitAdminShowReplicaStatusStatement(
            StarRocksParser.AdminShowReplicaStatusStatementContext context) {
        Token start = context.qualifiedName().start;
        Token stop = context.qualifiedName().stop;
        QualifiedName qualifiedName = getQualifiedName(context.qualifiedName());
        TableName targetTableName = qualifiedNameToTableName(qualifiedName);
        Expr where = context.where != null ? (Expr) visit(context.where) : null;
        PartitionNames partitionNames = null;
        if (context.partitionNames() != null) {
            stop = context.partitionNames().stop;
            partitionNames = (PartitionNames) visit(context.partitionNames());
        }
        return new AdminShowReplicaStatusStmt(
                new TableRef(targetTableName, null, partitionNames, createPos(start, stop)),
                where,
                createPos(context));
    }

    @Override
    public ParseNode visitAdminRepairTableStatement(StarRocksParser.AdminRepairTableStatementContext context) {
        Token start = context.qualifiedName().start;
        Token stop = context.qualifiedName().stop;
        QualifiedName qualifiedName = getQualifiedName(context.qualifiedName());
        TableName targetTableName = qualifiedNameToTableName(qualifiedName);
        PartitionNames partitionNames = null;
        if (context.partitionNames() != null) {
            stop = context.partitionNames().stop;
            partitionNames = (PartitionNames) visit(context.partitionNames());
        }
        return new AdminRepairTableStmt(new TableRef(targetTableName, null, partitionNames, createPos(start, stop)),
                createPos(context));
    }

    @Override
    public ParseNode visitAdminCancelRepairTableStatement(
            StarRocksParser.AdminCancelRepairTableStatementContext context) {
        Token start = context.qualifiedName().start;
        Token stop = context.qualifiedName().stop;
        QualifiedName qualifiedName = getQualifiedName(context.qualifiedName());
        TableName targetTableName = qualifiedNameToTableName(qualifiedName);
        PartitionNames partitionNames = null;
        if (context.partitionNames() != null) {
            stop = context.partitionNames().stop;
            partitionNames = (PartitionNames) visit(context.partitionNames());
        }
        return new AdminCancelRepairTableStmt(
                new TableRef(targetTableName, null, partitionNames, createPos(start, stop)),
                createPos(context));
    }

    @Override
    public ParseNode visitAdminCheckTabletsStatement(StarRocksParser.AdminCheckTabletsStatementContext context) {
        // tablet_ids and properties
        List<Long> tabletIds = Lists.newArrayList();
        if (context.tabletList() != null) {
            tabletIds = context.tabletList().INTEGER_VALUE().stream().map(ParseTree::getText)
                    .map(Long::parseLong).collect(toList());
        }
        return new AdminCheckTabletsStmt(tabletIds, (Property) visitProperty(context.property()), createPos(context));
    }

    @Override
    public ParseNode visitAdminSetPartitionVersion(StarRocksParser.AdminSetPartitionVersionContext context) {
        QualifiedName qualifiedName = getQualifiedName(context.qualifiedName());
        TableName targetTableName = qualifiedNameToTableName(qualifiedName);
        String partitionName = null;
        if (context.partitionName != null) {
            partitionName = ((Identifier) visit(context.partitionName)).getValue();
        }
        Long partitionId = null;
        if (context.partitionId != null) {
            partitionId = Long.parseLong(context.partitionId.getText());
        }
        long version = Long.parseLong(context.version.getText());
        if (partitionName != null) {
            return new AdminSetPartitionVersionStmt(targetTableName, partitionName, version, createPos(context));
        } else {
            return new AdminSetPartitionVersionStmt(targetTableName, partitionId, version, createPos(context));
        }
    }

    @Override
    public ParseNode visitKillStatement(StarRocksParser.KillStatementContext context) {
        NodePosition pos = createPos(context);
        long id = context.connId != null ? Long.parseLong(context.connId.getText()) : -1;
        String queryId = context.queryId != null ? ((StringLiteral) visit(context.queryId)).getStringValue() : null;
        if (context.QUERY() != null) {
            if (queryId != null) {
                return new KillStmt(queryId, pos);
            }
            return new KillStmt(id, pos);
        } else {
            if (queryId != null) {
                throw new ParsingException(String.format("connection id %s should be a positive integer", queryId));
            }
            return new KillStmt(true, id, pos);
        }
    }

    @Override
    public ParseNode visitSyncStatement(StarRocksParser.SyncStatementContext context) {
        return new SyncStmt(createPos(context));
    }

    @Override
    public ParseNode visitAdminSetAutomatedSnapshotOnStatement(
            StarRocksParser.AdminSetAutomatedSnapshotOnStatementContext context) {
        String svName = StorageVolumeMgr.BUILTIN_STORAGE_VOLUME;
        if (context.svName != null) {
            svName = getIdentifierName(context.svName);
        }
        return new AdminSetAutomatedSnapshotOnStmt(svName, createPos(context));
    }

    @Override
    public ParseNode visitAdminSetAutomatedSnapshotOffStatement(
            StarRocksParser.AdminSetAutomatedSnapshotOffStatementContext context) {
        return new AdminSetAutomatedSnapshotOffStmt(createPos(context));
    }

    // ------------------------------------------- Cluster Management Statement ----------------------------------------

    @Override
    public ParseNode visitAlterSystemStatement(StarRocksParser.AlterSystemStatementContext context) {
        return new AlterSystemStmt((AlterClause) visit(context.alterClause()), createPos(context));
    }

    @Override
    public ParseNode visitCancelAlterSystemStatement(StarRocksParser.CancelAlterSystemStatementContext context) {
        return new CancelAlterSystemStmt(visit(context.string(), StringLiteral.class)
                .stream().map(StringLiteral::getValue).collect(toList()), createPos(context));
    }

    @Override
    public ParseNode visitShowComputeNodesStatement(StarRocksParser.ShowComputeNodesStatementContext context) {
        return new ShowComputeNodesStmt(createPos(context));
    }

    // ------------------------------------------- Analyze Statement ---------------------------------------------------

    private List<Expr> getAnalyzeColumns(List<QualifiedName> qualifiedNames) {
        List<Expr> columns = Lists.newArrayList();
        for (QualifiedName qualifiedName : qualifiedNames) {
            if (qualifiedName.getParts().size() == 1) {
                columns.add(new SlotRef(null, qualifiedName.getParts().get(0)));
            } else {
                Expr base = new SlotRef(null, qualifiedName.getParts().get(0));
                columns.add(new SubfieldExpr(base, qualifiedName.getParts().subList(1,
                        qualifiedName.getParts().size())));
            }
        }
        return columns;
    }

    private Pair<Boolean, List<Expr>> visitAnalyzeColumnClause(StarRocksParser.AnalyzeColumnClauseContext context) {
        boolean usePredicateColumns = false;
        List<Expr> columns = Lists.newArrayList();
        if (context == null) {
            // noop
        } else if (context instanceof StarRocksParser.AllColumnsContext) {
            // noop
        } else if (context instanceof StarRocksParser.MultiColumnSetContext) {
            StarRocksParser.MultiColumnSetContext multiColumnSetContext =
                    (StarRocksParser.MultiColumnSetContext) context;
            List<QualifiedName> names = multiColumnSetContext.qualifiedName().stream()
                    .map(this::getQualifiedName).collect(toList());
            columns = getAnalyzeColumns(names);
        } else if (context instanceof StarRocksParser.PredicateColumnsContext) {
            usePredicateColumns = true;
        } else if (context instanceof StarRocksParser.RegularColumnsContext) {
            StarRocksParser.RegularColumnsContext regularColumnsContext =
                    (StarRocksParser.RegularColumnsContext) context;
            List<QualifiedName> names = regularColumnsContext.qualifiedName().stream()
                    .map(this::getQualifiedName).collect(toList());
            columns = getAnalyzeColumns(names);
        } else {
            Preconditions.checkState(false, "unreachable");
        }

        return Pair.create(usePredicateColumns, columns);
    }

    @Override
    public ParseNode visitAnalyzeStatement(StarRocksParser.AnalyzeStatementContext context) {
        PartitionNames partitionNames = null;
        if (context.partitionNames() != null) {
            partitionNames = (PartitionNames) visit(context.partitionNames());
        }

        TableName tableName = qualifiedNameToTableName(getQualifiedName(context.tableName().qualifiedName()));

        Map<String, String> properties = new HashMap<>();
        if (context.properties() != null) {
            List<Property> propertyList = visit(context.properties().property(), Property.class);
            for (Property property : propertyList) {
                properties.put(property.getKey(), property.getValue());
            }
        }

        boolean isSample = context.SAMPLE() != null;

        Pair<Boolean, List<Expr>> analyzeColumn = visitAnalyzeColumnClause(context.analyzeColumnClause());
        AnalyzeTypeDesc analyzeTypeDesc = new AnalyzeBasicDesc();
        if (context.analyzeColumnClause() instanceof StarRocksParser.MultiColumnSetContext) {
            List<StatsConstants.StatisticsType> statisticsTypes = Lists.newArrayList();
            statisticsTypes.add(StatsConstants.StatisticsType.MCDISTINCT);

            // we use sample strategy to collect multi-column combined statistics as default.
            isSample = context.FULL() == null;
            analyzeTypeDesc = new AnalyzeMultiColumnDesc(statisticsTypes);
        }

        return new AnalyzeStmt(tableName, analyzeColumn.second, partitionNames, properties,
                isSample,
                context.ASYNC() != null,
                analyzeColumn.first,
                analyzeTypeDesc, createPos(context));
    }

    @Override
    public ParseNode visitDropStatsStatement(StarRocksParser.DropStatsStatementContext context) {
        QualifiedName qualifiedName = getQualifiedName(context.qualifiedName());
        TableName tableName = qualifiedNameToTableName(qualifiedName);
        return new DropStatsStmt(tableName, context.MULTIPLE() != null, createPos(context));
    }

    @Override
    public ParseNode visitCreateAnalyzeStatement(StarRocksParser.CreateAnalyzeStatementContext context) {
        NodePosition pos = createPos(context);
        Map<String, String> properties = new HashMap<>();
        if (context.properties() != null) {
            List<Property> propertyList = visit(context.properties().property(), Property.class);
            for (Property property : propertyList) {
                properties.put(property.getKey(), property.getValue());
            }
        }

        StatsConstants.AnalyzeType analyzeType = StatsConstants.AnalyzeType.FULL;
        if (context.FULL() != null) {
            analyzeType = StatsConstants.AnalyzeType.FULL;
        } else if (context.SAMPLE() != null) {
            analyzeType = StatsConstants.AnalyzeType.SAMPLE;
        } else if (context.histogramStatement() != null) {
            analyzeType = StatsConstants.AnalyzeType.HISTOGRAM;
        }
        boolean isSample = context.FULL() == null;

        if (context.DATABASE() != null) {
            return new CreateAnalyzeJobStmt(((Identifier) visit(context.db)).getValue(), isSample,
                    properties, pos);
        } else if (context.TABLE() != null) {
            List<QualifiedName> qualifiedNames = context.qualifiedName().stream().map(this::getQualifiedName).
                    collect(toList());
            TableName tableName = qualifiedNameToTableName(qualifiedNames.get(0));
            List<Expr> columns = getAnalyzeColumns(qualifiedNames.subList(1, qualifiedNames.size()));
            return new CreateAnalyzeJobStmt(tableName, columns, context.IF() != null, isSample, properties,
                    analyzeType, null, pos);
        } else if (context.histogramStatement() != null) {
            AnalyzeStmt analyzeStmt = histogramStatement(context.histogramStatement());
            return new CreateAnalyzeJobStmt(analyzeStmt.getTableName(), analyzeStmt.getColumns(), false,
                    analyzeStmt.isSample(), analyzeStmt.getProperties(), analyzeType,
                    analyzeStmt.getAnalyzeTypeDesc(), pos);
        } else {
            return new CreateAnalyzeJobStmt(isSample, properties, pos);
        }
    }

    @Override
    public ParseNode visitDropAnalyzeJobStatement(StarRocksParser.DropAnalyzeJobStatementContext context) {
        long id = context.ALL() != null ? -1 : Long.parseLong(context.INTEGER_VALUE().getText());
        return new DropAnalyzeJobStmt(id, createPos(context));
    }

    @Override
    public ParseNode visitShowAnalyzeStatement(StarRocksParser.ShowAnalyzeStatementContext context) {
        Predicate predicate = null;
        NodePosition pos = createPos(context);
        if (context.expression() != null) {
            predicate = (Predicate) visit(context.expression());
        }

        List<OrderByElement> orderByElements = null;
        if (context.ORDER() != null) {
            orderByElements = new ArrayList<>(visit(context.sortItem(), OrderByElement.class));
        }
        LimitElement limitElement = null;
        if (context.limitElement() != null) {
            limitElement = (LimitElement) visit(context.limitElement());
        }

        if (context.STATUS() != null) {
            return new ShowAnalyzeStatusStmt(predicate, orderByElements, limitElement, pos);
        } else if (context.JOB() != null) {
            return new ShowAnalyzeJobStmt(predicate, orderByElements, limitElement, pos);
        } else {
            return new ShowAnalyzeJobStmt(predicate, orderByElements, limitElement, pos);
        }
    }

    @Override
    public ParseNode visitShowStatsMetaStatement(StarRocksParser.ShowStatsMetaStatementContext context) {
        Predicate predicate = null;
        if (context.expression() != null) {
            predicate = (Predicate) visit(context.expression());
        }

        List<OrderByElement> orderByElements = null;
        if (context.ORDER() != null) {
            orderByElements = new ArrayList<>();
            orderByElements.addAll(visit(context.sortItem(), OrderByElement.class));
        }
        LimitElement limitElement = null;
        if (context.limitElement() != null) {
            limitElement = (LimitElement) visit(context.limitElement());
        }

        if (context.MULTIPLE() != null) {
            return new ShowMultiColumnStatsMetaStmt(predicate, orderByElements, limitElement, createPos(context));
        } else {
            return new ShowBasicStatsMetaStmt(predicate, orderByElements, limitElement, createPos(context));
        }
    }

    @Override
    public ParseNode visitShowHistogramMetaStatement(StarRocksParser.ShowHistogramMetaStatementContext context) {
        Predicate predicate = null;
        if (context.expression() != null) {
            predicate = (Predicate) visit(context.expression());
        }

        List<OrderByElement> orderByElements = null;
        if (context.ORDER() != null) {
            orderByElements = new ArrayList<>();
            orderByElements.addAll(visit(context.sortItem(), OrderByElement.class));
        }
        LimitElement limitElement = null;
        if (context.limitElement() != null) {
            limitElement = (LimitElement) visit(context.limitElement());
        }

        return new ShowHistogramStatsMetaStmt(predicate, orderByElements, limitElement, createPos(context));
    }

    private AnalyzeStmt histogramStatement(StarRocksParser.HistogramStatementContext context) {
        TableName tableName = getTableName(context.tableName().qualifiedName());

        Pair<Boolean, List<Expr>> analyzeColumn = visitAnalyzeColumnClause(context.analyzeColumnClause());

        Map<String, String> properties = new HashMap<>();
        if (context.properties() != null) {
            List<Property> propertyList = visit(context.properties().property(), Property.class);
            for (Property property : propertyList) {
                properties.put(property.getKey(), property.getValue());
            }
        }

        long bucket;
        if (context.bucket != null) {
            bucket = Long.parseLong(context.bucket.getText());
        } else {
            bucket = Config.histogram_buckets_size;
        }

        return new AnalyzeStmt(tableName, analyzeColumn.second, null, properties, true,
                false, analyzeColumn.first, new AnalyzeHistogramDesc(bucket), createPos(context));
    }

    @Override
    public ParseNode visitAnalyzeHistogramStatement(StarRocksParser.AnalyzeHistogramStatementContext context) {
        AnalyzeStmt analyzeStmt = histogramStatement(context.histogramStatement());
        analyzeStmt.setIsAsync(context.ASYNC() != null);
        return analyzeStmt;
    }

    @Override
    public ParseNode visitDropHistogramStatement(StarRocksParser.DropHistogramStatementContext context) {
        List<QualifiedName> qualifiedNames = context.qualifiedName().stream().map(this::getQualifiedName).
                collect(toList());
        TableName tableName = qualifiedNameToTableName(qualifiedNames.get(0));
        List<Expr> columns = getAnalyzeColumns(qualifiedNames.subList(1, qualifiedNames.size()));

        return new DropHistogramStmt(tableName, columns, createPos(context));
    }

    @Override
    public ParseNode visitKillAnalyzeStatement(StarRocksParser.KillAnalyzeStatementContext context) {
        if (context.ALL() != null) {
            return new KillAnalyzeStmt(-1, createPos(context));
        } else {
            return new KillAnalyzeStmt(Long.parseLong(context.INTEGER_VALUE().getText()), createPos(context));
        }
    }

    // ------------------------------------------- Analyze Profile Statement -------------------------------------------

    @Override
    public ParseNode visitAnalyzeProfileStatement(StarRocksParser.AnalyzeProfileStatementContext context) {
        StringLiteral stringLiteral = (StringLiteral) visit(context.string());
        List<Integer> planNodeIds = Lists.newArrayList();
        if (context.INTEGER_VALUE() != null) {
            planNodeIds = context.INTEGER_VALUE().stream()
                    .map(ParseTree::getText)
                    .map(Integer::parseInt)
                    .collect(toList());
        }
        return new AnalyzeProfileStmt(stringLiteral.getStringValue(), planNodeIds, createPos(context));
    }

    // ------------------------------------------- Resource Group Statement --------------------------------------------

    public ParseNode visitCreateResourceGroupStatement(StarRocksParser.CreateResourceGroupStatementContext context) {
        Identifier identifier = (Identifier) visit(context.identifier());
        String name = identifier.getValue();

        List<List<Predicate>> predicatesList = new ArrayList<>();
        for (StarRocksParser.ClassifierContext classifierContext : context.classifier()) {
            List<Predicate> p = visit(classifierContext.expressionList().expression(), Predicate.class);
            predicatesList.add(p);
        }

        Map<String, String> properties = new HashMap<>();
        List<Property> propertyList = visit(context.property(), Property.class);
        for (Property property : propertyList) {
            properties.put(property.getKey(), property.getValue());
        }

        return new CreateResourceGroupStmt(name,
                context.EXISTS() != null,
                context.REPLACE() != null,
                predicatesList,
                properties, createPos(context));
    }

    @Override
    public ParseNode visitDropResourceGroupStatement(StarRocksParser.DropResourceGroupStatementContext context) {
        boolean ifExists = context.IF() != null;
        Identifier identifier = (Identifier) visit(context.identifier());
        return new DropResourceGroupStmt(identifier.getValue(), createPos(context), ifExists);
    }

    @Override
    public ParseNode visitAlterResourceGroupStatement(StarRocksParser.AlterResourceGroupStatementContext context) {
        Identifier identifier = (Identifier) visit(context.identifier());
        String name = identifier.getValue();
        NodePosition pos = createPos(context);
        if (context.ADD() != null) {
            List<List<Predicate>> predicatesList = new ArrayList<>();
            for (StarRocksParser.ClassifierContext classifierContext : context.classifier()) {
                List<Predicate> p = visit(classifierContext.expressionList().expression(), Predicate.class);
                predicatesList.add(p);
            }

            return new AlterResourceGroupStmt(name, new AlterResourceGroupStmt.AddClassifiers(predicatesList), pos);
        } else if (context.DROP() != null) {
            if (context.ALL() != null) {
                return new AlterResourceGroupStmt(name, new AlterResourceGroupStmt.DropAllClassifiers(), pos);
            } else {
                return new AlterResourceGroupStmt(name,
                        new AlterResourceGroupStmt.DropClassifiers(context.INTEGER_VALUE()
                                .stream().map(ParseTree::getText).map(Long::parseLong).collect(toList())), pos);
            }
        } else {
            Map<String, String> properties = new HashMap<>();
            List<Property> propertyList = visit(context.property(), Property.class);
            for (Property property : propertyList) {
                properties.put(property.getKey(), property.getValue());
            }

            return new AlterResourceGroupStmt(name, new AlterResourceGroupStmt.AlterProperties(properties), pos);
        }
    }

    @Override
    public ParseNode visitShowResourceGroupStatement(StarRocksParser.ShowResourceGroupStatementContext context) {
        NodePosition pos = createPos(context);
        if (context.GROUPS() != null) {
            return new ShowResourceGroupStmt(null, context.ALL() != null, context.VERBOSE() != null, pos);
        } else {
            Identifier identifier = (Identifier) visit(context.identifier());
            return new ShowResourceGroupStmt(identifier.getValue(), false, context.VERBOSE() != null, pos);
        }
    }

    // ------------------------------------------- External Resource Statement -----------------------------------------

    public ParseNode visitCreateResourceStatement(StarRocksParser.CreateResourceStatementContext context) {
        Identifier identifier = (Identifier) visit(context.identifierOrString());
        Map<String, String> properties = new HashMap<>();
        if (context.properties() != null) {
            List<Property> propertyList = visit(context.properties().property(), Property.class);
            for (Property property : propertyList) {
                properties.put(property.getKey(), property.getValue());
            }
        }
        return new CreateResourceStmt(context.EXTERNAL() != null, identifier.getValue(), properties,
                createPos(context));
    }

    public ParseNode visitDropResourceStatement(StarRocksParser.DropResourceStatementContext context) {
        Identifier identifier = (Identifier) visit(context.identifierOrString());
        return new DropResourceStmt(identifier.getValue(), createPos(context));
    }

    public ParseNode visitAlterResourceStatement(StarRocksParser.AlterResourceStatementContext context) {
        Identifier identifier = (Identifier) visit(context.identifierOrString());
        Map<String, String> properties = new HashMap<>();
        if (context.properties() != null) {
            List<Property> propertyList = visit(context.properties().property(), Property.class);
            for (Property property : propertyList) {
                properties.put(property.getKey(), property.getValue());
            }
        }
        return new AlterResourceStmt(identifier.getValue(), properties, createPos(context));
    }

    public ParseNode visitShowResourceStatement(StarRocksParser.ShowResourceStatementContext context) {
        return new ShowResourcesStmt(createPos(context));
    }

    // ------------------------------------------- Load Statement ------------------------------------------------------

    @Override
    public ParseNode visitLoadStatement(StarRocksParser.LoadStatementContext context) {
        NodePosition pos = createPos(context);

        LabelName label = getLabelName(context.labelName());
        List<DataDescription> dataDescriptions = null;
        if (context.data != null) {
            dataDescriptions = context.data.dataDesc().stream().map(this::getDataDescription)
                    .collect(toList());
        }
        Map<String, String> properties = null;
        if (context.props != null) {
            properties = Maps.newHashMap();
            List<Property> propertyList = visit(context.props.property(), Property.class);
            for (Property property : propertyList) {
                properties.put(property.getKey(), property.getValue());
            }
        }
        if (context.resource != null) {
            ResourceDesc resourceDesc = getResourceDesc(context.resource);
            return new LoadStmt(label, dataDescriptions, resourceDesc, properties, pos);
        }
        BrokerDesc brokerDesc = getBrokerDesc(context.broker);
        String cluster = null;
        if (context.system != null) {
            cluster = ((Identifier) visit(context.system)).getValue();
        }
        LoadStmt stmt = new LoadStmt(label, dataDescriptions, brokerDesc, cluster, properties, pos);
        stmt.setHintNodes(hintMap.get(context));
        return stmt;
    }

    private LabelName getLabelName(StarRocksParser.LabelNameContext context) {
        String label = ((Identifier) visit(context.label)).getValue();
        String db = "";
        if (context.db != null) {
            db = ((Identifier) visit(context.db)).getValue();
        }
        return new LabelName(db, label, createPos(context));
    }

    private DataDescription getDataDescription(StarRocksParser.DataDescContext context) {
        NodePosition pos = createPos(context);
        String dstTableName = ((Identifier) visit(context.dstTableName)).getValue();
        PartitionNames partitionNames = (PartitionNames) visitIfPresent(context.partitions);
        Expr whereExpr = (Expr) visitIfPresent(context.where);
        List<Expr> colMappingList = null;
        if (context.colMappingList != null) {
            colMappingList = visit(context.colMappingList.expressionList().expression(), Expr.class);
        }
        if (context.srcTableName != null) {
            String srcTableName = ((Identifier) visit(context.srcTableName)).getValue();
            return new DataDescription(dstTableName, partitionNames, srcTableName,
                    context.NEGATIVE() != null, colMappingList, whereExpr, pos);
        }
        List<String> files = context.srcFiles.string().stream().map(c -> ((StringLiteral) visit(c)).getStringValue())
                .collect(toList());
        ColumnSeparator colSep = getColumnSeparator(context.colSep);
        RowDelimiter rowDelimiter = getRowDelimiter(context.rowSep);
        String format = null;
        if (context.format != null) {
            if (context.format.identifier() != null) {
                format = ((Identifier) visit(context.format.identifier())).getValue();
            } else if (context.format.string() != null) {
                format = ((StringLiteral) visit(context.format.string())).getStringValue();
            }
        }
        List<String> colList = null;
        if (context.colList != null) {
            List<Identifier> identifiers = visit(context.colList.identifier(), Identifier.class);
            colList = identifiers.stream().map(Identifier::getValue).collect(toList());
        }
        List<String> colFromPath = null;
        if (context.colFromPath != null) {
            List<Identifier> identifiers = visit(context.colFromPath.identifier(), Identifier.class);
            colFromPath = identifiers.stream().map(Identifier::getValue).collect(toList());
        }
        StarRocksParser.FormatPropsContext formatPropsContext;
        CsvFormat csvFormat;
        if (context.formatPropsField != null) {
            formatPropsContext = context.formatProps();
            String escape = null;
            if (formatPropsContext.escapeCharacter != null) {
                StringLiteral stringLiteral = (StringLiteral) visit(formatPropsContext.escapeCharacter);
                escape = stringLiteral.getValue();
            }
            String enclose = null;
            if (formatPropsContext.encloseCharacter != null) {
                StringLiteral stringLiteral = (StringLiteral) visit(formatPropsContext.encloseCharacter);
                enclose = stringLiteral.getValue();
            }
            long skipheader = 0;
            if (formatPropsContext.INTEGER_VALUE() != null) {
                skipheader = Long.parseLong(formatPropsContext.INTEGER_VALUE().getText());
                if (skipheader < 0) {
                    skipheader = 0;
                }
            }
            boolean trimspace = false;
            if (formatPropsContext.booleanValue() != null) {
                trimspace = Boolean.parseBoolean(formatPropsContext.booleanValue().getText());
            }
            csvFormat = new CsvFormat((enclose == null || enclose.isEmpty()) ? 0 : (byte) enclose.charAt(0),
                    (escape == null || escape.isEmpty()) ? 0 : (byte) escape.charAt(0),
                    skipheader, trimspace);
        } else {
            csvFormat = new CsvFormat((byte) 0, (byte) 0, 0, false);
        }
        return new DataDescription(dstTableName, partitionNames, files, colList, colSep, rowDelimiter,
                format, colFromPath, context.NEGATIVE() != null, colMappingList, whereExpr,
                csvFormat, createPos(context));
    }

    private ColumnSeparator getColumnSeparator(StarRocksParser.StringContext context) {
        if (context != null) {
            String sep = ((StringLiteral) visit(context)).getValue();
            return new ColumnSeparator(sep);
        }
        return null;
    }

    private RowDelimiter getRowDelimiter(StarRocksParser.StringContext context) {
        if (context != null) {
            String sep = ((StringLiteral) visit(context)).getValue();
            return new RowDelimiter(sep);
        }
        return null;
    }

    private BrokerDesc getBrokerDesc(StarRocksParser.BrokerDescContext context) {
        if (context != null) {
            NodePosition pos = createPos(context);
            Map<String, String> properties = null;
            if (context.props != null) {
                properties = Maps.newHashMap();
                List<Property> propertyList = visit(context.props.property(), Property.class);
                for (Property property : propertyList) {
                    properties.put(property.getKey(), property.getValue());
                }
            }
            if (context.identifierOrString() != null) {
                String brokerName = ((Identifier) visit(context.identifierOrString())).getValue();
                return new BrokerDesc(brokerName, properties, pos);
            } else {
                return new BrokerDesc(properties, pos);
            }

        }
        return null;
    }

    private ResourceDesc getResourceDesc(StarRocksParser.ResourceDescContext context) {
        if (context != null) {
            String brokerName = ((Identifier) visit(context.identifierOrString())).getValue();
            Map<String, String> properties = null;
            if (context.props != null) {
                properties = Maps.newHashMap();
                List<Property> propertyList = visit(context.props.property(), Property.class);
                for (Property property : propertyList) {
                    properties.put(property.getKey(), property.getValue());
                }
            }
            return new ResourceDesc(brokerName, properties, createPos(context));
        }
        return null;
    }

    @Override
    public ParseNode visitShowLoadStatement(StarRocksParser.ShowLoadStatementContext context) {
        String db = null;
        if (context.identifier() != null) {
            db = ((Identifier) visit(context.identifier())).getValue();
        }
        Expr labelExpr = null;
        if (context.expression() != null) {
            labelExpr = (Expr) visit(context.expression());
        }
        List<OrderByElement> orderByElements = null;
        if (context.ORDER() != null) {
            orderByElements = new ArrayList<>();
            orderByElements.addAll(visit(context.sortItem(), OrderByElement.class));
        }
        LimitElement limitElement = null;
        if (context.limitElement() != null) {
            limitElement = (LimitElement) visit(context.limitElement());
        }
        boolean all = context.ALL() != null;
        ShowLoadStmt res = new ShowLoadStmt(db, labelExpr, orderByElements, limitElement, createPos(context));
        res.setAll(all);
        return res;
    }

    @Override
    public ParseNode visitShowLoadWarningsStatement(StarRocksParser.ShowLoadWarningsStatementContext context) {
        if (context.ON() != null) {
            String url = ((StringLiteral) visit(context.string())).getValue();
            return new ShowLoadWarningsStmt(null, url, null, null);
        }
        String db = null;
        if (context.identifier() != null) {
            db = ((Identifier) visit(context.identifier())).getValue();
        }
        Expr labelExpr = null;
        if (context.expression() != null) {
            labelExpr = (Expr) visit(context.expression());
        }
        LimitElement limitElement = null;
        if (context.limitElement() != null) {
            limitElement = (LimitElement) visit(context.limitElement());
        }
        return new ShowLoadWarningsStmt(db, null, labelExpr, limitElement, createPos(context));
    }

    @Override
    public ParseNode visitCancelLoadStatement(StarRocksParser.CancelLoadStatementContext context) {
        String db = null;
        if (context.identifier() != null) {
            db = ((Identifier) visit(context.identifier())).getValue();
        }
        Expr labelExpr = null;
        if (context.expression() != null) {
            labelExpr = (Expr) visit(context.expression());
        }
        return new CancelLoadStmt(db, labelExpr, createPos(context));
    }

    // ------------------------------------------- Compaction Statement ------------------------------------------------------

    @Override
    public ParseNode visitCancelCompactionStatement(StarRocksParser.CancelCompactionStatementContext context) {
        Expr txnIdExpr = null;
        if (context.expression() != null) {
            txnIdExpr = (Expr) visit(context.expression());
        }
        return new CancelCompactionStmt(txnIdExpr, createPos(context));
    }

    // ------------------------------------------- Show Statement ------------------------------------------------------

    @Override
    public ParseNode visitShowAuthorStatement(StarRocksParser.ShowAuthorStatementContext context) {
        return new ShowAuthorStmt(createPos(context));
    }

    @Override
    public ParseNode visitShowBackendsStatement(StarRocksParser.ShowBackendsStatementContext context) {
        return new ShowBackendsStmt(createPos(context));
    }

    @Override
    public ParseNode visitShowBrokerStatement(StarRocksParser.ShowBrokerStatementContext context) {
        return new ShowBrokerStmt(createPos(context));
    }

    @Override
    public ParseNode visitShowCharsetStatement(StarRocksParser.ShowCharsetStatementContext context) {
        String pattern = null;
        if (context.pattern != null) {
            StringLiteral stringLiteral = (StringLiteral) visit(context.pattern);
            pattern = stringLiteral.getValue();
        }

        Expr where = null;
        if (context.expression() != null) {
            where = (Expr) visit(context.expression());
        }

        return new ShowCharsetStmt(pattern, where, createPos(context));
    }

    @Override
    public ParseNode visitShowCollationStatement(StarRocksParser.ShowCollationStatementContext context) {
        String pattern = null;
        if (context.pattern != null) {
            StringLiteral stringLiteral = (StringLiteral) visit(context.pattern);
            pattern = stringLiteral.getValue();
        }

        Expr where = null;
        if (context.expression() != null) {
            where = (Expr) visit(context.expression());
        }

        return new ShowCollationStmt(pattern, where, createPos(context));
    }

    @Override
    public ParseNode visitShowDeleteStatement(StarRocksParser.ShowDeleteStatementContext context) {
        QualifiedName dbName = null;
        if (context.qualifiedName() != null) {
            dbName = getQualifiedName(context.db);
        }
        return new ShowDeleteStmt(dbName == null ? null : dbName.toString(), createPos(context));
    }

    @Override
    public ParseNode visitShowDynamicPartitionStatement(StarRocksParser.ShowDynamicPartitionStatementContext context) {

        QualifiedName dbName = null;
        if (context.db != null) {
            dbName = getQualifiedName(context.db);
        }

        return new ShowDynamicPartitionStmt(dbName == null ? null : dbName.toString(), createPos(context));
    }

    @Override
    public ParseNode visitShowEventsStatement(StarRocksParser.ShowEventsStatementContext context) {
        return new ShowEventsStmt(createPos(context));
    }

    @Override
    public ParseNode visitShowEnginesStatement(StarRocksParser.ShowEnginesStatementContext context) {
        return new ShowEnginesStmt(createPos(context));
    }

    @Override
    public ParseNode visitShowFrontendsStatement(StarRocksParser.ShowFrontendsStatementContext context) {
        return new ShowFrontendsStmt(createPos(context));
    }

    @Override
    public ParseNode visitShowPluginsStatement(StarRocksParser.ShowPluginsStatementContext context) {
        return new ShowPluginsStmt(createPos(context));
    }

    @Override
    public ParseNode visitShowRepositoriesStatement(StarRocksParser.ShowRepositoriesStatementContext context) {
        return new ShowRepositoriesStmt(createPos(context));
    }

    @Override
    public ParseNode visitShowOpenTableStatement(StarRocksParser.ShowOpenTableStatementContext context) {
        return new ShowOpenTableStmt(createPos(context));
    }

    @Override
    public ParseNode visitShowProcedureStatement(StarRocksParser.ShowProcedureStatementContext context) {
        NodePosition pos = createPos(context);
        if (context.pattern != null) {
            StringLiteral stringLiteral = (StringLiteral) visit(context.pattern);
            return new ShowProcedureStmt(stringLiteral.getValue(), null, pos);
        } else if (context.expression() != null) {
            return new ShowProcedureStmt(null, (Expr) visit(context.expression()), pos);
        } else {
            return new ShowProcedureStmt(null, null, pos);
        }
    }

    @Override
    public ParseNode visitShowProcStatement(StarRocksParser.ShowProcStatementContext context) {
        StringLiteral stringLiteral = (StringLiteral) visit(context.path);
        return new ShowProcStmt(stringLiteral.getValue(), createPos(context));
    }

    @Override
    public ParseNode visitShowProcesslistStatement(StarRocksParser.ShowProcesslistStatementContext context) {
        String forUser = null;
        if (context.FOR() != null) {
            forUser = ((StringLiteral) visit(context.string())).getValue();
        }
        boolean isShowFull = context.FULL() != null;
        return new ShowProcesslistStmt(isShowFull, forUser, createPos(context));
    }

    @Override
    public ParseNode visitShowProfilelistStatement(StarRocksParser.ShowProfilelistStatementContext context) {
        int limit = context.LIMIT() != null ? Integer.parseInt(context.limit.getText()) : -1;
        return new ShowProfilelistStmt(limit, createPos(context));
    }

    @Override
    public ParseNode visitShowRunningQueriesStatement(StarRocksParser.ShowRunningQueriesStatementContext context) {
        int limit = context.LIMIT() != null ? Integer.parseInt(context.limit.getText()) : -1;
        return new ShowRunningQueriesStmt(limit, createPos(context));
    }

    @Override
    public ParseNode visitShowResourceGroupUsageStatement(
            StarRocksParser.ShowResourceGroupUsageStatementContext context) {
        if (context.GROUPS() != null) {
            return new ShowResourceGroupUsageStmt(null, createPos(context));
        }

        Identifier groupName = (Identifier) visit(context.identifier());
        return new ShowResourceGroupUsageStmt(groupName.getValue(), createPos(context));
    }

    @Override
    public ParseNode visitShowTransactionStatement(StarRocksParser.ShowTransactionStatementContext context) {

        String database = null;
        if (context.qualifiedName() != null) {
            database = getQualifiedName(context.qualifiedName()).toString();
        }

        Expr where = null;
        if (context.expression() != null) {
            where = (Expr) visit(context.expression());
        }

        return new ShowTransactionStmt(database, where, createPos(context));
    }

    @Override
    public ParseNode visitShowStatusStatement(StarRocksParser.ShowStatusStatementContext context) {
        String pattern = null;
        if (context.pattern != null) {
            StringLiteral stringLiteral = (StringLiteral) visit(context.pattern);
            pattern = stringLiteral.getValue();
        }

        Expr where = null;
        if (context.expression() != null) {
            where = (Expr) visit(context.expression());
        }

        return new ShowStatusStmt(getVariableType(context.varType()), pattern, where, createPos(context));
    }

    @Override
    public ParseNode visitShowTriggersStatement(StarRocksParser.ShowTriggersStatementContext context) {
        return new ShowTriggersStmt(createPos(context));
    }

    @Override
    public ParseNode visitShowUserPropertyStatement(StarRocksParser.ShowUserPropertyStatementContext context) {
        String user;
        String pattern;
        if (context.FOR() == null) {
            user = null;
            pattern = context.LIKE() == null ? null : ((StringLiteral) visit(context.string(0))).getValue();
        } else {
            user = ((StringLiteral) visit(context.string(0))).getValue();
            pattern = context.LIKE() == null ? null : ((StringLiteral) visit(context.string(1))).getValue();
        }
        return new ShowUserPropertyStmt(user, pattern, createPos(context));
    }

    @Override
    public ParseNode visitShowVariablesStatement(StarRocksParser.ShowVariablesStatementContext context) {
        String pattern = null;
        if (context.pattern != null) {
            StringLiteral stringLiteral = (StringLiteral) visit(context.pattern);
            pattern = stringLiteral.getValue();
        }

        Expr where = null;
        if (context.expression() != null) {
            where = (Expr) visit(context.expression());
        }

        return new ShowVariablesStmt(getVariableType(context.varType()), pattern, where, createPos(context));
    }

    @Override
    public ParseNode visitShowWarningStatement(StarRocksParser.ShowWarningStatementContext context) {
        NodePosition pos = createPos(context);
        if (context.limitElement() != null) {
            return new ShowWarningStmt((LimitElement) visit(context.limitElement()), pos);
        }
        return new ShowWarningStmt(null, pos);
    }

    @Override
    public ParseNode visitHelpStatement(StarRocksParser.HelpStatementContext context) {
        String mask = ((Identifier) visit(context.identifierOrString())).getValue();
        return new HelpStmt(mask, createPos(context));
    }

    // ------------------------------------------- Backup Store Statement ----------------------------------------------
    private ParseNode getFunctionRef(StarRocksParser.QualifiedNameContext qualifiedNameContext,
                                     String alias, NodePosition position) {
        String functionName = getQualifiedName(qualifiedNameContext).toString();
        FunctionName fnName = FunctionName.createFnName(functionName);
        return new FunctionRef(fnName, alias, position);
    }

    private ParseNode getTableRef(StarRocksParser.QualifiedNameContext qualifiedNameContext,
                                  StarRocksParser.PartitionNamesContext partitionNamesContext,
                                  String alias, NodePosition position) {
        TableName tableName = qualifiedNameToTableName(getQualifiedName(qualifiedNameContext));
        PartitionNames partitionNames = null;
        if (partitionNamesContext != null) {
            partitionNames = (PartitionNames) visit(partitionNamesContext);
        }
        return new TableRef(tableName, alias, partitionNames, position);
    }

    private ParseNode parseBackupRestoreStatement(ParserRuleContext context) {
        StarRocksParser.BackupStatementContext backupContext = null;
        StarRocksParser.RestoreStatementContext restoreContext = null;

        if (context instanceof StarRocksParser.RestoreStatementContext) {
            restoreContext = (StarRocksParser.RestoreStatementContext) context;
        } else {
            backupContext = (StarRocksParser.BackupStatementContext) context;
        }

        List<CatalogRef> externalCatalogRefs = new ArrayList<>();
        boolean allExternalCatalog = backupContext != null ?
                (backupContext.ALL() != null) : (restoreContext.ALL() != null);
        if (!allExternalCatalog && (backupContext != null ?
                (backupContext.CATALOG() != null || backupContext.CATALOGS() != null) :
                (restoreContext.CATALOG() != null || restoreContext.CATALOGS() != null))) {
            if (backupContext != null) {
                StarRocksParser.IdentifierListContext identifierListContext = backupContext.identifierList();
                externalCatalogRefs = visit(identifierListContext.identifier(), Identifier.class)
                        .stream().map(Identifier::getValue)
                        .map(x -> new CatalogRef(x)).collect(Collectors.toList());
            } else {
                List<StarRocksParser.IdentifierWithAliasContext> identifierWithAliasList =
                        restoreContext.identifierWithAliasList().identifierWithAlias();
                for (StarRocksParser.IdentifierWithAliasContext identifierWithAliasContext : identifierWithAliasList) {
                    String originalName = getIdentifierName(identifierWithAliasContext.originalName);
                    String alias = identifierWithAliasContext.AS() != null ?
                            getIdentifierName(identifierWithAliasContext.alias) : "";
                    externalCatalogRefs.add(new CatalogRef(originalName, alias));
                }
            }
        }
        boolean containsExternalCatalog = allExternalCatalog || !externalCatalogRefs.isEmpty();

        boolean specifyDbExplicitly =
                backupContext != null ? (backupContext.DATABASE() != null) : (restoreContext.DATABASE() != null);

        if (specifyDbExplicitly && containsExternalCatalog) {
            throw new ParsingException(PARSER_ERROR_MSG.unsupportedSepcifyDbForExternalCatalog());
        }

        LabelName labelName = null;
        String repoName = null;
        // db which the snapshot should be restored in
        String dbAlias = null;
        // db name in snapshot meta data
        String originDb = null;

        boolean withOnClause = false;

        List<TableRef> tblRefs = new ArrayList<>();
        List<TableRef> mvRefs = new ArrayList<>();
        List<TableRef> viewRefs = new ArrayList<>();
        List<TableRef> mixTblRefs = new ArrayList<>();
        List<FunctionRef> fnRefs = new ArrayList<>();
        Set<BackupObjectType> allMarker = Sets.newHashSet();

        if (allExternalCatalog) {
            allMarker.add(BackupObjectType.EXTERNAL_CATALOG);
        }

        labelName = qualifiedNameToLabelName(getQualifiedName(backupContext != null ?
                backupContext.qualifiedName() : restoreContext.qualifiedName()));
        if (specifyDbExplicitly) {
            if (labelName.getDbName() != null) {
                throw new ParsingException(PARSER_ERROR_MSG.unsupportedSepcifyDbNameAfterSnapshotName());
            }

            originDb = getIdentifierName(backupContext != null ? backupContext.dbName : restoreContext.dbName);
            if (restoreContext != null && restoreContext.AS() != null) {
                dbAlias = getIdentifierName(restoreContext.dbAlias);
            }

            labelName.setDbName(dbAlias != null ? dbAlias : originDb);
        } else if (containsExternalCatalog && labelName.getDbName() != null) {
            throw new ParsingException(PARSER_ERROR_MSG.unsupportedSepcifyDbForExternalCatalog());
        }
        repoName = getIdentifierName(backupContext != null ? backupContext.repoName : restoreContext.repoName);

        List<StarRocksParser.BackupRestoreObjectDescContext> backupRestoreObjectDescContexts =
                backupContext != null ? backupContext.backupRestoreObjectDesc() : restoreContext.backupRestoreObjectDesc();

        for (StarRocksParser.BackupRestoreObjectDescContext backupRestoreObjectDescContext : backupRestoreObjectDescContexts) {
            boolean specifiedFunction = backupRestoreObjectDescContext.FUNCTION() != null ||
                    backupRestoreObjectDescContext.FUNCTIONS() != null;
            boolean specifiedMV = backupRestoreObjectDescContext.MATERIALIZED() != null;
            boolean specifiedView = !specifiedMV && (backupRestoreObjectDescContext.VIEW() != null ||
                    backupRestoreObjectDescContext.VIEWS() != null);
            boolean specifiedTable = backupRestoreObjectDescContext.TABLE() != null ||
                    backupRestoreObjectDescContext.TABLES() != null;

            if (backupContext != null && (backupRestoreObjectDescContext.AS() != null ||
                    (backupRestoreObjectDescContext.backupRestoreTableDesc() != null &&
                            backupRestoreObjectDescContext.backupRestoreTableDesc().AS() != null))) {
                throw new ParsingException(PARSER_ERROR_MSG.unsupportedSepcifyAliasInBackupStmt());
            }

            withOnClause = true;

            String alias = null;
            if (restoreContext != null) {
                if (backupRestoreObjectDescContext.AS() != null) {
                    alias = getIdentifierName(backupRestoreObjectDescContext.identifier());
                } else if (backupRestoreObjectDescContext.backupRestoreTableDesc() != null &&
                        backupRestoreObjectDescContext.backupRestoreTableDesc().AS() != null) {
                    alias = getIdentifierName(backupRestoreObjectDescContext.backupRestoreTableDesc().identifier());
                }
            }

            if (specifiedFunction) {
                if (backupRestoreObjectDescContext.ALL() != null) {
                    allMarker.add(BackupObjectType.FUNCTION);
                    continue;
                }

                fnRefs.add((FunctionRef) getFunctionRef(backupRestoreObjectDescContext.qualifiedName(),
                        alias, createPos(backupRestoreObjectDescContext)));
            } else if (specifiedMV) {
                if (backupRestoreObjectDescContext.ALL() != null) {
                    allMarker.add(BackupObjectType.MV);
                    continue;
                }

                mvRefs.add((TableRef) getTableRef(backupRestoreObjectDescContext.qualifiedName(),
                        null, alias, createPos(backupRestoreObjectDescContext)));
            } else if (specifiedView) {
                if (backupRestoreObjectDescContext.ALL() != null) {
                    allMarker.add(BackupObjectType.VIEW);
                    continue;
                }

                viewRefs.add((TableRef) getTableRef(backupRestoreObjectDescContext.qualifiedName(),
                        null, alias, createPos(backupRestoreObjectDescContext)));
            } else if (specifiedTable) {
                if (backupRestoreObjectDescContext.ALL() != null) {
                    allMarker.add(BackupObjectType.TABLE);
                    continue;
                }

                tblRefs.add((TableRef) getTableRef(backupRestoreObjectDescContext.backupRestoreTableDesc().qualifiedName(),
                        backupRestoreObjectDescContext.backupRestoreTableDesc().partitionNames(),
                        alias, createPos(backupRestoreObjectDescContext)));
            } else {
                mixTblRefs.add((TableRef) getTableRef(backupRestoreObjectDescContext.backupRestoreTableDesc().qualifiedName(),
                        backupRestoreObjectDescContext.backupRestoreTableDesc().partitionNames(),
                        alias, createPos(backupRestoreObjectDescContext)));
            }
        }

        if (restoreContext != null && withOnClause && labelName.getDbName() == null) {
            throw new ParsingException(PARSER_ERROR_MSG.unsupportedOnClauseWithoutAnyDbNameInRestoreStmt());
        }

        if (withOnClause && containsExternalCatalog) {
            throw new ParsingException(PARSER_ERROR_MSG.unsupportedOnForExternalCatalog());
        }

        // merge mv, view, table
        mixTblRefs.addAll(mvRefs);
        mixTblRefs.addAll(viewRefs);
        mixTblRefs.addAll(tblRefs);

        Map<String, String> properties = null;
        StarRocksParser.PropertyListContext contextProperties =
                (backupContext != null) ? backupContext.propertyList() : restoreContext.propertyList();
        if (contextProperties != null) {
            properties = new HashMap<>();
            List<Property> propertyList = visit(contextProperties.property(), Property.class);
            for (Property property : propertyList) {
                properties.put(property.getKey(), property.getValue());
            }
        }

        AbstractBackupStmt stmt = null;
        if (backupContext != null) {
            stmt = new BackupStmt(labelName, repoName, mixTblRefs, fnRefs, externalCatalogRefs, allMarker, withOnClause,
                    originDb != null ? originDb : "", properties, createPos(backupContext));
        } else {
            stmt = new RestoreStmt(labelName, repoName, mixTblRefs, fnRefs, externalCatalogRefs, allMarker, withOnClause,
                    originDb != null ? originDb : "", properties, createPos(restoreContext));
        }

        return stmt;
    }

    @Override
    public ParseNode visitBackupStatement(StarRocksParser.BackupStatementContext context) {
        return parseBackupRestoreStatement(context);
    }

    @Override
    public ParseNode visitCancelBackupStatement(StarRocksParser.CancelBackupStatementContext context) {
        if (context.CATALOG() == null && context.identifier() == null) {
            throw new ParsingException(PARSER_ERROR_MSG.nullIdentifierCancelBackupRestore());
        }
        return new CancelBackupStmt(context.CATALOG() != null ? "" : ((Identifier) visit(context.identifier())).getValue(),
                false, context.CATALOG() != null, createPos(context));
    }

    @Override
    public ParseNode visitShowBackupStatement(StarRocksParser.ShowBackupStatementContext context) {
        NodePosition pos = createPos(context);
        if (context.identifier() == null) {
            return new ShowBackupStmt(null, pos);
        }
        return new ShowBackupStmt(((Identifier) visit(context.identifier())).getValue(), pos);
    }

    @Override
    public ParseNode visitRestoreStatement(StarRocksParser.RestoreStatementContext context) {
        return parseBackupRestoreStatement(context);
    }

    @Override
    public ParseNode visitCancelRestoreStatement(StarRocksParser.CancelRestoreStatementContext context) {
        if (context.CATALOG() == null && context.identifier() == null) {
            throw new ParsingException(PARSER_ERROR_MSG.nullIdentifierCancelBackupRestore());
        }
        return new CancelBackupStmt(context.CATALOG() != null ? "" : ((Identifier) visit(context.identifier())).getValue(),
                true, context.CATALOG() != null, createPos(context));
    }

    @Override
    public ParseNode visitShowRestoreStatement(StarRocksParser.ShowRestoreStatementContext context) {
        NodePosition pos = createPos(context);
        if (context.identifier() == null) {
            return new ShowRestoreStmt(null, null, pos);
        }
        if (context.expression() != null) {
            return new ShowRestoreStmt(((Identifier) visit(context.identifier())).getValue(),
                    (Expr) visit(context.expression()), pos);
        } else {
            return new ShowRestoreStmt(((Identifier) visit(context.identifier())).getValue(), null, pos);
        }
    }

    @Override
    public ParseNode visitShowSnapshotStatement(StarRocksParser.ShowSnapshotStatementContext context) {
        StarRocksParser.ExpressionContext expression = context.expression();
        Expr where = null;
        if (expression != null) {
            where = (Expr) visit(context.expression());
        }

        String repoName = ((Identifier) visit(context.identifier())).getValue();

        return new ShowSnapshotStmt(repoName, where, createPos(context));
    }

    // ----------------------------------------------- Repository Statement --------------------------------------------

    @Override
    public ParseNode visitCreateRepositoryStatement(StarRocksParser.CreateRepositoryStatementContext context) {
        boolean isReadOnly = context.READ() != null && context.ONLY() != null;

        Map<String, String> properties = new HashMap<>();
        if (context.propertyList() != null) {
            List<Property> propertyList = visit(context.propertyList().property(), Property.class);
            for (Property property : propertyList) {
                properties.put(property.getKey(), property.getValue());
            }
        }
        String location = ((StringLiteral) visit(context.location)).getValue();
        String repoName = ((Identifier) visit(context.repoName)).getValue();
        String brokerName = null;
        if (context.brokerName != null) {
            brokerName = ((Identifier) visit(context.brokerName)).getValue();
        }

        return new CreateRepositoryStmt(isReadOnly, repoName, brokerName,
                location, properties, createPos(context));
    }

    @Override
    public ParseNode visitDropRepositoryStatement(StarRocksParser.DropRepositoryStatementContext context) {
        return new DropRepositoryStmt(((Identifier) visit(context.identifier())).getValue(), createPos(context));
    }

    // -------------------------------- Sql BlackList And WhiteList Statement ------------------------------------------

    @Override
    public ParseNode visitAddSqlBlackListStatement(StarRocksParser.AddSqlBlackListStatementContext context) {
        String sql = ((StringLiteral) visit(context.string())).getStringValue();
        if (sql == null || sql.isEmpty()) {
            throw new ParsingException(PARSER_ERROR_MSG.emptySql(), createPos(context.string()));
        }
        return new AddSqlBlackListStmt(sql);
    }

    @Override
    public ParseNode visitDelSqlBlackListStatement(StarRocksParser.DelSqlBlackListStatementContext context) {
        List<Long> indexes = context.INTEGER_VALUE().stream().map(ParseTree::getText)
                .map(Long::parseLong).collect(toList());
        return new DelSqlBlackListStmt(indexes, createPos(context));
    }

    @Override
    public ParseNode visitShowSqlBlackListStatement(StarRocksParser.ShowSqlBlackListStatementContext context) {
        return new ShowSqlBlackListStmt(createPos(context));
    }

    @Override
    public ParseNode visitShowWhiteListStatement(StarRocksParser.ShowWhiteListStatementContext context) {
        return new ShowWhiteListStmt();
    }

    // -------------------------------- backend BlackList Statement ---------------------------------------------------

    @Override
    public ParseNode visitAddBackendBlackListStatement(StarRocksParser.AddBackendBlackListStatementContext ctx) {
        List<Long> ids =
                ctx.INTEGER_VALUE().stream().map(ParseTree::getText).map(Long::parseLong).collect(toList());
        return new AddBackendBlackListStmt(ids, createPos(ctx));
    }

    @Override
    public ParseNode visitDelBackendBlackListStatement(StarRocksParser.DelBackendBlackListStatementContext ctx) {
        List<Long> ids =
                ctx.INTEGER_VALUE().stream().map(ParseTree::getText).map(Long::parseLong).collect(toList());
        return new DelBackendBlackListStmt(createPos(ctx), ids);
    }

    @Override
    public ParseNode visitShowBackendBlackListStatement(StarRocksParser.ShowBackendBlackListStatementContext ctx) {
        return new ShowBackendBlackListStmt(createPos(ctx));
    }

    // -------------------------------- Compute Node BlackList Statement ---------------------------------------------------

    @Override
    public ParseNode visitAddComputeNodeBlackListStatement(StarRocksParser.AddComputeNodeBlackListStatementContext ctx) {
        List<Long> ids =
                ctx.INTEGER_VALUE().stream().map(ParseTree::getText).map(Long::parseLong).collect(toList());
        return new AddComputeNodeBlackListStmt(ids, createPos(ctx));
    }

    @Override
    public ParseNode visitDelComputeNodeBlackListStatement(StarRocksParser.DelComputeNodeBlackListStatementContext ctx) {
        List<Long> ids =
                ctx.INTEGER_VALUE().stream().map(ParseTree::getText).map(Long::parseLong).collect(toList());
        return new DelComputeNodeBlackListStmt(createPos(ctx), ids);
    }

    @Override
    public ParseNode visitShowComputeNodeBlackListStatement(StarRocksParser.ShowComputeNodeBlackListStatementContext ctx) {
        return new ShowComputeNodeBlackListStmt(createPos(ctx));
    }

    // --------------------------------------- DataCache Management Statement -----------------------------------------
    @Override
    public ParseNode visitCreateDataCacheRuleStatement(StarRocksParser.CreateDataCacheRuleStatementContext ctx) {
        List<StarRocksParser.IdentifierOrStringOrStarContext> partList =
                ctx.dataCacheTarget().identifierOrStringOrStar();
        List<String> parts = partList.stream().map(c -> ((Identifier) visit(c)).getValue()).collect(toList());

        QualifiedName qualifiedName = QualifiedName.of(parts);

        int priority = Integer.parseInt(ctx.INTEGER_VALUE().getText());
        if (ctx.MINUS_SYMBOL() != null) {
            // handle negative number "-1"
            priority *= -1;
        }

        Expr predicates = null;
        if (ctx.expression() != null) {
            predicates = (Expr) visit(ctx.expression());
        }

        Map<String, String> properties = null;
        if (ctx.properties() != null) {
            properties = new HashMap<>();
            List<Property> propertyList = visit(ctx.properties().property(), Property.class);
            for (Property property : propertyList) {
                properties.put(property.getKey(), property.getValue());
            }
        }

        return new CreateDataCacheRuleStmt(qualifiedName, predicates, priority, properties, createPos(ctx));
    }

    @Override
    public ParseNode visitShowDataCacheRulesStatement(StarRocksParser.ShowDataCacheRulesStatementContext ctx) {
        return new ShowDataCacheRulesStmt(createPos(ctx));
    }

    @Override
    public ParseNode visitDropDataCacheRuleStatement(StarRocksParser.DropDataCacheRuleStatementContext ctx) {
        long id = Long.parseLong(ctx.INTEGER_VALUE().getText());
        return new DropDataCacheRuleStmt(id, createPos(ctx));
    }

    @Override
    public ParseNode visitClearDataCacheRulesStatement(StarRocksParser.ClearDataCacheRulesStatementContext ctx) {
        return new ClearDataCacheRulesStmt(createPos(ctx));
    }

    @Override
    public ParseNode visitDataCacheSelectStatement(StarRocksParser.DataCacheSelectStatementContext ctx) {
        // cache select only support select one table at a time
        // create a single table relation
        TableRelation tableRelation = null;
        {
            QualifiedName qualifiedName = getQualifiedName(ctx.qualifiedName());
            TableName tableName = qualifiedNameToTableName(qualifiedName);
            tableRelation = new TableRelation(tableName);
        }

        // create select items
        List<SelectListItem> selectItems = visit(ctx.selectItem(), SelectListItem.class);
        SelectList selectList = new SelectList(selectItems, false);

        // create query relation based on tableRelation and selectItems
        QueryRelation queryRelation = new SelectRelation(
                selectList,
                tableRelation,
                (Expr) visitIfPresent(ctx.where),
                null,
                null,
                createPos(ctx));

        // create queryStatement based on queryRelation
        QueryStatement queryStatement = new QueryStatement(queryRelation);

        // Convert queryStatement into InsertStmt(`INSERT INTO BLACKHOLE() SELECT xxx FROM TBL`)
        InsertStmt insertStmt = new InsertStmt(queryStatement, createPos(ctx));

        // properties
        Map<String, String> properties = new HashMap<>();
        if (ctx.properties() != null) {
            List<Property> propertyList = visit(ctx.properties().property(), Property.class);
            for (Property property : propertyList) {
                // ignore case sensitive
                properties.put(property.getKey().toLowerCase(), property.getValue().toLowerCase());
            }
        }

        return new DataCacheSelectStatement(insertStmt, properties, createPos(ctx));
    }

    // ----------------------------------------------- Export Statement ------------------------------------------------
    @Override
    public ParseNode visitExportStatement(StarRocksParser.ExportStatementContext context) {
        StarRocksParser.QualifiedNameContext qualifiedNameContext = context.tableDesc().qualifiedName();
        Token start = qualifiedNameContext.start;
        Token stop = qualifiedNameContext.stop;
        QualifiedName qualifiedName = getQualifiedName(qualifiedNameContext);
        TableName tableName = qualifiedNameToTableName(qualifiedName);
        PartitionNames partitionNames = null;
        if (context.tableDesc().partitionNames() != null) {
            stop = context.tableDesc().partitionNames().stop;
            partitionNames = (PartitionNames) visit(context.tableDesc().partitionNames());
        }
        TableRef tableRef = new TableRef(tableName, null, partitionNames, createPos(start, stop));

        StringLiteral stringLiteral = (StringLiteral) visit(context.string());
        // properties
        Map<String, String> properties = null;
        if (context.properties() != null) {
            properties = new HashMap<>();
            List<Property> propertyList = visit(context.properties().property(), Property.class);
            for (Property property : propertyList) {
                properties.put(property.getKey(), property.getValue());
            }
        }
        // brokers
        BrokerDesc brokerDesc = getBrokerDesc(context.brokerDesc());
        boolean sync = context.SYNC() != null;
        return new ExportStmt(tableRef, getColumnNames(context.columnAliases()),
                stringLiteral.getValue(), properties, brokerDesc, createPos(context), sync);
    }

    @Override
    public ParseNode visitCancelExportStatement(StarRocksParser.CancelExportStatementContext context) {
        String catalog = null;
        if (context.catalog != null) {
            QualifiedName dbName = getQualifiedName(context.catalog);
            catalog = dbName.toString();
        }

        Expr where = null;
        if (context.expression() != null) {
            where = (Expr) visit(context.expression());
        }
        return new CancelExportStmt(catalog, where, createPos(context));
    }

    @Override
    public ParseNode visitShowExportStatement(StarRocksParser.ShowExportStatementContext context) {
        String catalog = null;
        if (context.catalog != null) {
            QualifiedName dbName = getQualifiedName(context.catalog);
            catalog = dbName.toString();
        }

        LimitElement le = null;
        if (context.limitElement() != null) {
            le = (LimitElement) visit(context.limitElement());
        }
        List<OrderByElement> orderByElements = null;
        if (context.ORDER() != null) {
            orderByElements = new ArrayList<>();
            orderByElements.addAll(visit(context.sortItem(), OrderByElement.class));
        }
        Expr whereExpr = null;
        if (context.expression() != null) {
            whereExpr = (Expr) visit(context.expression());
        }
        return new ShowExportStmt(catalog, whereExpr, orderByElements, le, createPos(context));
    }

    // ------------------------------------------------- Plugin Statement --------------------------------------------------------

    @Override
    public ParseNode visitInstallPluginStatement(StarRocksParser.InstallPluginStatementContext context) {
        String pluginPath = ((Identifier) visit(context.identifierOrString())).getValue();
        Map<String, String> properties = getProperties(context.properties());
        return new InstallPluginStmt(pluginPath, properties, createPos(context));
    }

    @Override
    public ParseNode visitUninstallPluginStatement(StarRocksParser.UninstallPluginStatementContext context) {
        String pluginPath = ((Identifier) visit(context.identifierOrString())).getValue();
        return new UninstallPluginStmt(pluginPath, createPos(context));
    }

    // ------------------------------------------------- File Statement ----------------------------------------------------------

    @Override
    public ParseNode visitCreateFileStatement(StarRocksParser.CreateFileStatementContext context) {
        String fileName = ((StringLiteral) visit(context.string())).getStringValue();

        String catalog = null;
        if (context.catalog != null) {
            QualifiedName dbName = getQualifiedName(context.catalog);
            catalog = dbName.toString();
        }
        Map<String, String> properties = getProperties(context.properties());

        return new CreateFileStmt(fileName, catalog, properties, createPos(context));
    }

    @Override
    public ParseNode visitDropFileStatement(StarRocksParser.DropFileStatementContext context) {
        String fileName = ((StringLiteral) visit(context.string())).getStringValue();

        String catalog = null;
        if (context.catalog != null) {
            QualifiedName dbName = getQualifiedName(context.catalog);
            catalog = dbName.toString();
        }
        Map<String, String> properties = getProperties(context.properties());

        return new DropFileStmt(fileName, catalog, properties, createPos(context));
    }

    @Override
    public ParseNode visitShowSmallFilesStatement(StarRocksParser.ShowSmallFilesStatementContext context) {

        String catalog = null;
        if (context.catalog != null) {
            QualifiedName dbName = getQualifiedName(context.catalog);
            catalog = dbName.toString();
        }

        return new ShowSmallFilesStmt(catalog, createPos(context));
    }

    // ------------------------------------------------- Set Statement -----------------------------------------------------------
    @Override
    public ParseNode visitSetStatement(StarRocksParser.SetStatementContext context) {
        List<SetListItem> propertyList = visit(context.setVar(), SetListItem.class);
        return new SetStmt(propertyList, createPos(context));
    }

    @Override
    public ParseNode visitSetNames(StarRocksParser.SetNamesContext context) {
        NodePosition pos = createPos(context);
        if (context.CHAR() != null || context.CHARSET() != null) {
            if (context.identifierOrString().isEmpty()) {
                return new SetNamesVar(null, null, pos);
            } else {
                return new SetNamesVar(
                        ((Identifier) visit(context.identifierOrString().get(0))).getValue(),
                        null,
                        pos);
            }
        } else {
            String charset = null;
            if (context.charset != null) {
                charset = ((Identifier) visit(context.charset)).getValue();
            }
            String collate = null;
            if (context.collate != null) {
                collate = ((Identifier) visit(context.collate)).getValue();
            }

            return new SetNamesVar(charset, collate, pos);
        }
    }

    @Override
    public ParseNode visitSetPassword(StarRocksParser.SetPasswordContext context) {
        NodePosition pos = createPos(context);
        StringLiteral stringLiteral = (StringLiteral) visit(context.string());

        boolean isPlainPassword;
        if (context.PASSWORD().size() > 1) {
            isPlainPassword = true;
        } else {
            isPlainPassword = false;
        }
        UserAuthOption authOption = new UserAuthOption(AuthPlugin.Server.MYSQL_NATIVE_PASSWORD.name(),
                stringLiteral.getStringValue(), isPlainPassword, pos);

        if (context.user() != null) {
            return new SetPassVar((UserIdentity) visit(context.user()), authOption, pos);
        } else {
            return new SetPassVar(null, authOption, pos);
        }
    }

    @Override
    public ParseNode visitSetUserVar(StarRocksParser.SetUserVarContext context) {
        UserVariableExpr variableDesc = (UserVariableExpr) visit(context.userVariable());
        Expr expr = (Expr) visit(context.expression());
        return new UserVariable(variableDesc.getName(), expr, createPos(context));
    }

    @Override
    public ParseNode visitSetSystemVar(StarRocksParser.SetSystemVarContext context) {
        NodePosition pos = createPos(context);
        if (context.systemVariable() != null) {
            VariableExpr variableDesc = (VariableExpr) visit(context.systemVariable());
            Expr expr = (Expr) visit(context.setExprOrDefault());
            return new SystemVariable(variableDesc.getSetType() == null ? SetType.SESSION : variableDesc.getSetType(),
                    variableDesc.getName(), expr, pos);
        } else {
            Expr expr = (Expr) visit(context.setExprOrDefault());
            String variable = ((Identifier) visit(context.identifier())).getValue();
            if (context.varType() != null) {
                return new SystemVariable(getVariableType(context.varType()), variable, expr, pos);
            } else {
                return new SystemVariable(SetType.SESSION, variable, expr, pos);
            }
        }
    }

    @Override
    public ParseNode visitSetTransaction(StarRocksParser.SetTransactionContext context) {
        return new SetTransaction(createPos(context));
    }

    @Override
    public ParseNode visitSetUserPropertyStatement(StarRocksParser.SetUserPropertyStatementContext context) {
        String user = context.FOR() == null ? null : ((StringLiteral) visit(context.string())).getValue();
        List<SetUserPropertyVar> list = new ArrayList<>();
        if (context.userPropertyList() != null) {
            List<Property> propertyList = visit(context.userPropertyList().property(), Property.class);
            for (Property property : propertyList) {
                SetUserPropertyVar setVar = new SetUserPropertyVar(property.getKey(), property.getValue());
                if (!property.getKey().equalsIgnoreCase(UserProperty.PROP_MAX_USER_CONNECTIONS)) {
                    throw new ParsingException("Please use ALTER USER syntax to set user properties.");
                }
                list.add(setVar);
            }
        }
        return new SetUserPropertyStmt(user, list, createPos(context));
    }

    @Override
    public ParseNode visitSetExprOrDefault(StarRocksParser.SetExprOrDefaultContext context) {
        if (context.DEFAULT() != null) {
            return null;
        } else if (context.ON() != null) {
            return new StringLiteral("ON");
        } else if (context.ALL() != null) {
            return new StringLiteral("ALL");
        } else {
            return visit(context.expression());
        }
    }

    @Override
    public ParseNode visitExecuteScriptStatement(StarRocksParser.ExecuteScriptStatementContext context) {
        long beId = -1;
        if (context.INTEGER_VALUE() != null) {
            beId = Long.parseLong(context.INTEGER_VALUE().getText());
        }
        StringLiteral stringLiteral = (StringLiteral) visit(context.string());
        String script = stringLiteral.getStringValue();
        return new ExecuteScriptStmt(beId, script, createPos(context));
    }

    // ---------------------------------------- Storage Volume Statement ----------------------------------------------
    @Override
    public ParseNode visitCreateStorageVolumeStatement(StarRocksParser.CreateStorageVolumeStatementContext context) {
        Identifier identifier = (Identifier) visit(context.identifierOrString());
        String svName = identifier.getValue();

        String storageType = ((Identifier) visit(context.typeDesc().identifier())).getValue();

        List<StarRocksParser.StringContext> locationList = context.locationsDesc().stringList().string();
        List<String> locations = new ArrayList<>();
        for (StarRocksParser.StringContext location : locationList) {
            locations.add(((StringLiteral) visit(location)).getValue());
        }

        return new CreateStorageVolumeStmt(context.IF() != null,
                svName, storageType, getProperties(context.properties()), locations,
                context.comment() == null ? null : ((StringLiteral) visit(context.comment().string())).getStringValue(),
                createPos(context));
    }

    @Override
    public ParseNode visitShowStorageVolumesStatement(StarRocksParser.ShowStorageVolumesStatementContext context) {
        String pattern = null;
        if (context.pattern != null) {
            StringLiteral stringLiteral = (StringLiteral) visit(context.pattern);
            pattern = stringLiteral.getValue();
        }

        return new ShowStorageVolumesStmt(pattern, createPos(context));
    }

    @Override
    public ParseNode visitAlterStorageVolumeStatement(StarRocksParser.AlterStorageVolumeStatementContext context) {
        Identifier identifier = (Identifier) visit(context.identifierOrString());
        String svName = identifier.getValue();
        NodePosition pos = createPos(context);

        List<AlterStorageVolumeClause> alterClauses = visit(context.alterStorageVolumeClause(),
                AlterStorageVolumeClause.class);

        Map<String, String> properties = new HashMap<>();
        String comment = null;
        for (AlterStorageVolumeClause clause : alterClauses) {
            if (clause.getOpType().equals(AlterStorageVolumeClause.AlterOpType.ALTER_COMMENT)) {
                comment = ((AlterStorageVolumeCommentClause) clause).getNewComment();
            } else if (clause.getOpType().equals(AlterStorageVolumeClause.AlterOpType.MODIFY_PROPERTIES)) {
                properties = ((ModifyStorageVolumePropertiesClause) clause).getProperties();
            }
        }

        return new AlterStorageVolumeStmt(svName, properties, comment, pos);
    }

    @Override
    public ParseNode visitDropStorageVolumeStatement(StarRocksParser.DropStorageVolumeStatementContext context) {
        Identifier identifier = (Identifier) visit(context.identifierOrString());
        String svName = identifier.getValue();
        return new DropStorageVolumeStmt(context.IF() != null, svName, createPos(context));
    }

    @Override
    public ParseNode visitDescStorageVolumeStatement(StarRocksParser.DescStorageVolumeStatementContext context) {
        Identifier identifier = (Identifier) visit(context.identifierOrString());
        String svName = identifier.getValue();
        return new DescStorageVolumeStmt(svName, createPos(context));
    }

    @Override
    public ParseNode visitSetDefaultStorageVolumeStatement(
            StarRocksParser.SetDefaultStorageVolumeStatementContext context) {
        Identifier identifier = (Identifier) visit(context.identifierOrString());
        String svName = identifier.getValue();
        return new SetDefaultStorageVolumeStmt(svName, createPos(context));
    }

    @Override
    public ParseNode visitModifyStorageVolumeCommentClause(
            StarRocksParser.ModifyStorageVolumeCommentClauseContext context) {
        String comment = ((StringLiteral) visit(context.string())).getStringValue();
        return new AlterStorageVolumeCommentClause(comment, createPos(context));
    }

    @Override
    public ParseNode visitModifyStorageVolumePropertiesClause(
            StarRocksParser.ModifyStorageVolumePropertiesClauseContext context) {
        Map<String, String> properties = new HashMap<>();
        List<Property> propertyList = visit(context.propertyList().property(), Property.class);
        for (Property property : propertyList) {
            properties.put(property.getKey(), property.getValue());
        }
        return new ModifyStorageVolumePropertiesClause(properties, createPos(context));
    }

    // ----------------------------------------------- FailPoint Statement -----------------------------------------------------

    @Override
    public ParseNode visitUpdateFailPointStatusStatement(
            StarRocksParser.UpdateFailPointStatusStatementContext ctx) {
        String failpointName = ((StringLiteral) visit(ctx.string(0))).getStringValue();
        List<String> backendList;
        if (ctx.FRONTEND() != null) {
            backendList = null;
        } else {
            backendList = new ArrayList<>();
            if (ctx.BACKEND() != null) {
                String strValue = ((StringLiteral) visit(ctx.string(1))).getStringValue();
                backendList = Lists.newArrayList(strValue.split(","));
            }
        }
        if (ctx.ENABLE() != null) {
            if (ctx.times != null) {
                int nTimes = Integer.parseInt(ctx.times.getText());
                if (nTimes <= 0) {
                    throw new ParsingException(String.format(
                            "Invalid TIMES value %d, it should be a positive integer", nTimes));
                }
                return new UpdateFailPointStatusStatement(failpointName, nTimes, backendList, createPos(ctx));
            } else if (ctx.prob != null) {
                double probability = Double.parseDouble(ctx.prob.getText());
                if (probability < 0 || probability > 1) {
                    throw new ParsingException(String.format(
                            "Invalid PROBABILITY value %f, it should be in range [0, 1]", probability));
                }
                return new UpdateFailPointStatusStatement(failpointName, probability, backendList, createPos(ctx));
            }
            return new UpdateFailPointStatusStatement(failpointName, true, backendList, createPos(ctx));
        } else {
            return new UpdateFailPointStatusStatement(failpointName, false, backendList, createPos(ctx));
        }
    }

    @Override
    public ParseNode visitShowFailPointStatement(StarRocksParser.ShowFailPointStatementContext ctx) {
        String pattern = null;
        List<String> backendList = null;
        int idx = 0;
        if (ctx.LIKE() != null) {
            pattern = ((StringLiteral) visit(ctx.string(idx++))).getStringValue();
        }
        if (ctx.BACKEND() != null) {
            String tmp = ((StringLiteral) visit(ctx.string(idx++))).getStringValue();
            backendList = Lists.newArrayList(tmp.split(","));
        }
        return new ShowFailPointStatement(pattern, backendList, createPos(ctx));
    }

    // ----------------------------------------------- Dictionary Statement -----------------------------------------------------
    @Override
    public ParseNode visitCreateDictionaryStatement(StarRocksParser.CreateDictionaryStatementContext context) {
        String dictionaryName = getQualifiedName(context.dictionaryName().qualifiedName()).toString();
        String queryableObject = getQualifiedName(context.qualifiedName()).toString();

        List<StarRocksParser.DictionaryColumnDescContext> dictionaryColumnDescs = context.dictionaryColumnDesc();
        List<String> dictionaryKeys = new ArrayList<>();
        List<String> dictionaryValues = new ArrayList<>();
        for (StarRocksParser.DictionaryColumnDescContext desc : dictionaryColumnDescs) {
            String columnName = getQualifiedName(desc.qualifiedName()).toString();
            if (desc.KEY() != null) {
                dictionaryKeys.add(columnName);
            }
            if (desc.VALUE() != null) {
                dictionaryValues.add(columnName);
            }
        }

        Map<String, String> properties = null;
        if (context.properties() != null) {
            properties = new HashMap<>();
            List<Property> propertyList = visit(context.properties().property(), Property.class);
            for (Property property : propertyList) {
                properties.put(property.getKey(), property.getValue());
            }
        }

        return new CreateDictionaryStmt(dictionaryName, queryableObject, dictionaryKeys, dictionaryValues,
                properties, createPos(context));
    }

    @Override
    public ParseNode visitDropDictionaryStatement(StarRocksParser.DropDictionaryStatementContext context) {
        String dictionaryName = getQualifiedName(context.qualifiedName()).toString();
        boolean cacheOnly = false;
        if (context.CACHE() != null) {
            cacheOnly = true;
        }
        return new DropDictionaryStmt(dictionaryName, cacheOnly, createPos(context));
    }

    @Override
    public ParseNode visitRefreshDictionaryStatement(StarRocksParser.RefreshDictionaryStatementContext context) {
        String dictionaryName = getQualifiedName(context.qualifiedName()).toString();
        return new RefreshDictionaryStmt(dictionaryName, createPos(context));
    }

    @Override
    public ParseNode visitShowDictionaryStatement(StarRocksParser.ShowDictionaryStatementContext context) {
        String dictionaryName = null;
        if (context.qualifiedName() != null) {
            dictionaryName = getQualifiedName(context.qualifiedName()).toString();
        }
        return new ShowDictionaryStmt(dictionaryName, createPos(context));
    }

    @Override
    public ParseNode visitCancelRefreshDictionaryStatement(
            StarRocksParser.CancelRefreshDictionaryStatementContext context) {
        String dictionaryName = getQualifiedName(context.qualifiedName()).toString();
        return new CancelRefreshDictionaryStmt(dictionaryName, createPos(context));
    }

    // ----------------------------------------------- Unsupported Statement -----------------------------------------------------

    @Override
    public ParseNode visitUnsupportedStatement(StarRocksParser.UnsupportedStatementContext context) {
        return new UnsupportedStmt(createPos(context));
    }

    // ----------------------------------------------  Alter Clause --------------------------------------------------------------

    // ---------Alter system clause---------
    @Override
    public ParseNode visitAddFrontendClause(StarRocksParser.AddFrontendClauseContext context) {
        String cluster = ((StringLiteral) visit(context.string())).getStringValue();
        NodePosition pos = createPos(context);
        if (context.FOLLOWER() != null) {
            return new AddFollowerClause(cluster, pos);
        } else {
            return new AddObserverClause(cluster, pos);
        }
    }

    @Override
    public ParseNode visitDropFrontendClause(StarRocksParser.DropFrontendClauseContext context) {
        String cluster = ((StringLiteral) visit(context.string())).getStringValue();
        NodePosition pos = createPos(context);
        if (context.FOLLOWER() != null) {
            return new DropFollowerClause(cluster, pos);
        } else {
            return new DropObserverClause(cluster, pos);
        }
    }

    @Override
    public ParseNode visitModifyFrontendHostClause(StarRocksParser.ModifyFrontendHostClauseContext context) {
        List<String> clusters =
                context.string().stream().map(c -> ((StringLiteral) visit(c)).getStringValue()).collect(toList());
        return new ModifyFrontendAddressClause(clusters.get(0), clusters.get(1), createPos(context));
    }

    @Override
    public ParseNode visitAddBackendClause(StarRocksParser.AddBackendClauseContext context) {
        String whName = WarehouseManager.DEFAULT_WAREHOUSE_NAME;
        String cngroupName = "";
        if (context.warehouseName != null) {
            Identifier identifier = (Identifier) visit(context.identifierOrString().get(0));
            whName = identifier.getValue();
        }

        if (context.cngroupName != null) {
            Identifier identifier = (Identifier) visit(context.identifierOrString().get(1));
            cngroupName = identifier.getValue();
        }

        List<String> backends =
                context.string().stream().map(c -> ((StringLiteral) visit(c)).getStringValue()).collect(toList());
        return new AddBackendClause(backends, whName, cngroupName, createPos(context));
    }

    @Override
    public ParseNode visitDropBackendClause(StarRocksParser.DropBackendClauseContext context) {
        String whName = WarehouseManager.DEFAULT_WAREHOUSE_NAME;
        String cngroupName = "";
        if (context.warehouseName != null) {
            Identifier identifier = (Identifier) visit(context.identifierOrString().get(0));
            whName = identifier.getValue();
        }

        if (context.cngroupName != null) {
            Identifier identifier = (Identifier) visit(context.identifierOrString().get(1));
            cngroupName = identifier.getValue();
        }

        List<String> clusters =
                context.string().stream().map(c -> ((StringLiteral) visit(c)).getStringValue()).collect(toList());
        return new DropBackendClause(clusters, context.FORCE() != null, whName, cngroupName, createPos(context));
    }

    @Override
    public ParseNode visitDecommissionBackendClause(StarRocksParser.DecommissionBackendClauseContext context) {
        List<String> clusters =
                context.string().stream().map(c -> ((StringLiteral) visit(c)).getStringValue()).collect(toList());
        return new DecommissionBackendClause(clusters, createPos(context));
    }

    @Override
    public ParseNode visitModifyBackendClause(StarRocksParser.ModifyBackendClauseContext context) {
        List<String> strings =
                context.string().stream().map(c -> ((StringLiteral) visit(c)).getStringValue()).collect(toList());
        if (context.HOST() != null) {
            return new ModifyBackendClause(strings.get(0), strings.get(1), createPos(context));
        } else {
            String backendHostPort = strings.get(0);
            Map<String, String> properties = new HashMap<>();
            List<Property> propertyList = visit(context.propertyList().property(), Property.class);
            for (Property property : propertyList) {
                properties.put(property.getKey(), property.getValue());
            }
            return new ModifyBackendClause(backendHostPort, properties, createPos(context));
        }
    }

    @Override
    public ParseNode visitAddComputeNodeClause(StarRocksParser.AddComputeNodeClauseContext context) {
        String whName = WarehouseManager.DEFAULT_WAREHOUSE_NAME;
        String cngroupName = "";
        if (context.warehouseName != null) {
            Identifier identifier = (Identifier) visit(context.identifierOrString().get(0));
            whName = identifier.getValue();
        }
        if (context.cngroupName != null) {
            Identifier identifier = (Identifier) visit(context.identifierOrString().get(1));
            cngroupName = identifier.getValue();
        }

        List<String> hostPorts =
                context.string().stream().map(c -> ((StringLiteral) visit(c)).getStringValue()).collect(toList());
        return new AddComputeNodeClause(hostPorts, whName, cngroupName, createPos(context));
    }

    @Override
    public ParseNode visitDropComputeNodeClause(StarRocksParser.DropComputeNodeClauseContext context) {
        String whName = WarehouseManager.DEFAULT_WAREHOUSE_NAME;
        String cngroupName = "";
        if (context.warehouseName != null) {
            Identifier identifier = (Identifier) visit(context.identifierOrString().get(0));
            whName = identifier.getValue();
        }
        if (context.cngroupName != null) {
            Identifier identifier = (Identifier) visit(context.identifierOrString().get(1));
            cngroupName = identifier.getValue();
        }

        List<String> hostPorts =
                context.string().stream().map(c -> ((StringLiteral) visit(c)).getStringValue()).collect(toList());
        return new DropComputeNodeClause(hostPorts, whName, cngroupName, createPos(context));
    }

    @Override
    public ParseNode visitModifyBrokerClause(StarRocksParser.ModifyBrokerClauseContext context) {
        String brokerName = ((Identifier) visit(context.identifierOrString())).getValue();
        NodePosition pos = createPos(context);
        if (context.ALL() != null) {
            return ModifyBrokerClause.createDropAllBrokerClause(brokerName, pos);
        }
        List<String> hostPorts =
                context.string().stream().map(c -> ((StringLiteral) visit(c)).getStringValue()).collect(toList());
        if (context.ADD() != null) {
            return ModifyBrokerClause.createAddBrokerClause(brokerName, hostPorts, pos);
        }
        return ModifyBrokerClause.createDropBrokerClause(brokerName, hostPorts, pos);
    }

    @Override
    public ParseNode visitAlterLoadErrorUrlClause(StarRocksParser.AlterLoadErrorUrlClauseContext context) {
        return new AlterLoadErrorUrlClause(getProperties(context.properties()), createPos(context));
    }

    @Override
    public ParseNode visitCreateImageClause(StarRocksParser.CreateImageClauseContext context) {
        return new CreateImageClause(createPos(context));
    }

    @Override
    public ParseNode visitCleanTabletSchedQClause(
            StarRocksParser.CleanTabletSchedQClauseContext context) {
        return new CleanTabletSchedQClause(createPos(context));
    }

    // ---------Alter table clause---------

    @Override
    public ParseNode visitCreateIndexClause(StarRocksParser.CreateIndexClauseContext context) {
        Token start = context.identifier().start;
        String indexName = ((Identifier) visit(context.identifier())).getValue();
        List<Identifier> columnList = visit(context.identifierList().identifier(), Identifier.class);
        Token stop = context.identifierList().stop;
        String comment = null;
        if (context.comment() != null) {
            stop = context.comment().stop;
            comment = ((StringLiteral) visit(context.comment())).getStringValue();
        }

        IndexDef indexDef = new IndexDef(indexName,
                columnList.stream().map(Identifier::getValue).collect(toList()),
                getIndexType(context.indexType()),
                comment, getPropertyList(context.propertyList()),
                createPos(start, stop));

        return new CreateIndexClause(indexDef, createPos(context));
    }

    @Override
    public ParseNode visitDropIndexClause(StarRocksParser.DropIndexClauseContext context) {
        Identifier identifier = (Identifier) visit(context.identifier());
        return new DropIndexClause(identifier.getValue(), createPos(context));
    }

    @Override
    public ParseNode visitDropPersistentIndexClause(StarRocksParser.DropPersistentIndexClauseContext context) {
        // Initialize the list to store tablet IDs
        Set<Long> tabletIds = Sets.newHashSet();

        // Iterate through the integerValueList in the context
        StarRocksParser.Integer_listContext integerListContext = context.integer_list();
        for (TerminalNode integerValueNode : integerListContext.INTEGER_VALUE()) {
            try {
                // Parse each INTEGER_VALUE as a Long and add it to tabletIds
                Long tabletId = Long.parseLong(integerValueNode.getText());
                tabletIds.add(tabletId);
            } catch (NumberFormatException e) {
                // Handle invalid integer parsing (e.g., non-numeric values)
                throw new SemanticException("Invalid tablet ID: " + integerValueNode.getText(), e);
            }
        }

        // Return the constructed DropPersistentIndexClause object
        return new DropPersistentIndexClause(tabletIds, createPos(context));
    }

    @Override
    public ParseNode visitTableRenameClause(StarRocksParser.TableRenameClauseContext context) {
        Identifier identifier = (Identifier) visit(context.identifier());
        return new TableRenameClause(identifier.getValue(), createPos(context));
    }

    @Override
    public ParseNode visitModifyCommentClause(StarRocksParser.ModifyCommentClauseContext context) {
        String comment = ((StringLiteral) visit(context.string())).getStringValue();
        return new AlterTableCommentClause(comment, createPos(context));
    }

    @Override
    public ParseNode visitSwapTableClause(StarRocksParser.SwapTableClauseContext context) {
        Identifier identifier = (Identifier) visit(context.identifier());
        return new SwapTableClause(identifier.getValue(), createPos(context));
    }

    @Override
    public ParseNode visitModifyPropertiesClause(StarRocksParser.ModifyPropertiesClauseContext context) {
        Map<String, String> properties = new HashMap<>();
        List<Property> propertyList = visit(context.propertyList().property(), Property.class);
        for (Property property : propertyList) {
            properties.put(property.getKey(), property.getValue());
        }
        return new ModifyTablePropertiesClause(properties, createPos(context));
    }

    @Override
    public ParseNode visitOptimizeClause(StarRocksParser.OptimizeClauseContext context) {
        return new OptimizeClause(
                context.keyDesc() == null ? null : getKeysDesc(context.keyDesc()),
                context.partitionDesc() == null ? null : getPartitionDesc(context.partitionDesc(), null),
                context.distributionDesc() == null ? null : (DistributionDesc) visit(context.distributionDesc()),
                context.orderByDesc() == null ? null :
                        visit(context.orderByDesc().identifierList().identifier(), Identifier.class)
                                .stream().map(Identifier::getValue).collect(toList()),
                context.partitionNames() == null ? null : (PartitionNames) visit(context.partitionNames()),
                context.optimizeRange() == null ? null : (OptimizeRange) visit(context.optimizeRange()),
                createPos(context));
    }

    @Override
    public ParseNode visitOptimizeRange(StarRocksParser.OptimizeRangeContext context) {
        StringLiteral start = null;
        StringLiteral end = null;
        
        // Extract start value if present
        if (context.start != null) {
            start = (StringLiteral) visit(context.start);
        }
        
        // Extract end value if present
        if (context.end != null) {
            end = (StringLiteral) visit(context.end);
        }
        
        // Create and return OptimizeRange object with position information
        return new OptimizeRange(start, end, createPos(context));
    }

    @Override
    public ParseNode visitAddColumnClause(StarRocksParser.AddColumnClauseContext context) {
        ColumnDef columnDef = getColumnDef(context.columnDesc());
        if (columnDef.isAutoIncrement()) {
            throw new ParsingException(PARSER_ERROR_MSG.autoIncrementForbid(columnDef.getName(), "ADD"),
                    columnDef.getPos());
        }
        ColumnPosition columnPosition = null;
        if (context.FIRST() != null) {
            columnPosition = ColumnPosition.FIRST;
        } else if (context.AFTER() != null) {
            StarRocksParser.IdentifierContext identifier = context.identifier(0);
            String afterColumnName = getIdentifierName(identifier);
            columnPosition = new ColumnPosition(afterColumnName, createPos(identifier));
        }
        String rollupName = null;
        if (context.rollupName != null) {
            rollupName = getIdentifierName(context.rollupName);
        }
        Map<String, String> properties = new HashMap<>();
        ;
        properties = getProperties(context.properties());

        if (columnDef.isGeneratedColumn()) {
            if (rollupName != null) {
                throw new ParsingException(
                        PARSER_ERROR_MSG.generatedColumnLimit("rollupName", "ADD GENERATED COLUMN"),
                        columnDef.getPos());
            }

            if (columnPosition != null) {
                throw new ParsingException(
                        PARSER_ERROR_MSG.generatedColumnLimit("AFTER", "ADD GENERATED COLUMN"),
                        columnDef.getPos());
            }

            if (properties.size() != 0) {
                throw new ParsingException(
                        PARSER_ERROR_MSG.generatedColumnLimit("properties", "ADD GENERATED COLUMN"),
                        columnDef.getPos());
            }
        }

        return new AddColumnClause(columnDef, columnPosition, rollupName, properties, createPos(context));
    }

    @Override
    public ParseNode visitAddColumnsClause(StarRocksParser.AddColumnsClauseContext context) {
        List<ColumnDef> columnDefs = getColumnDefs(context.columnDesc());
        Map<String, String> properties = new HashMap<>();
        properties = getProperties(context.properties());
        String rollupName = null;
        if (context.rollupName != null) {
            rollupName = getIdentifierName(context.rollupName);
        }
        for (ColumnDef columnDef : columnDefs) {
            if (columnDef.isAutoIncrement()) {
                throw new ParsingException(PARSER_ERROR_MSG.autoIncrementForbid(columnDef.getName(), "ADD"),
                        columnDef.getPos());
            }
            if (columnDef.isGeneratedColumn()) {
                if (rollupName != null) {
                    throw new ParsingException(
                            PARSER_ERROR_MSG.generatedColumnLimit("rollupName", "ADD GENERATED COLUMN"),
                            columnDef.getPos());
                }

                if (properties.size() != 0) {
                    throw new ParsingException(
                            PARSER_ERROR_MSG.generatedColumnLimit("properties", "ADD GENERATED COLUMN"),
                            columnDef.getPos());
                }
            }
        }
        return new AddColumnsClause(columnDefs, rollupName, getProperties(context.properties()), createPos(context));
    }

    @Override
    public ParseNode visitDropColumnClause(StarRocksParser.DropColumnClauseContext context) {
        String columnName = getIdentifierName(context.identifier(0));
        String rollupName = null;
        if (context.rollupName != null) {
            rollupName = getIdentifierName(context.rollupName);
        }
        return new DropColumnClause(columnName, rollupName, getProperties(context.properties()), createPos(context));
    }

    @Override
    public ParseNode visitAddFieldClause(StarRocksParser.AddFieldClauseContext context) {
        String columnName = getIdentifierName(context.identifier(0));
        StarRocksParser.SubfieldDescContext subFieldDescContext = context.subfieldDesc();
        List<String> parts = new ArrayList<>();
        if (subFieldDescContext.nestedFieldName() != null) {
            parts = getFieldName(subFieldDescContext.nestedFieldName());
        } else {
            parts.add(getIdentifierName(subFieldDescContext.identifier()));
        }
        String fieldName = parts.get(parts.size() - 1);
        parts.remove(parts.size() - 1);
        TypeDef typeDef = new TypeDef(getType(subFieldDescContext.type()), createPos(subFieldDescContext.type()));
        ColumnPosition fieldPosition = null;
        if (context.FIRST() != null) {
            fieldPosition = ColumnPosition.FIRST;
        } else if (context.AFTER() != null) {
            StarRocksParser.IdentifierContext identifier = context.identifier(1);
            String afterFieldName = getIdentifierName(identifier);
            fieldPosition = new ColumnPosition(afterFieldName, createPos(identifier));
        }

        if (fieldName == null) {
            throw new ParsingException("add field clause name is null");
        }
        StructFieldDesc fieldDesc = new StructFieldDesc(fieldName, parts, typeDef, fieldPosition);
        return new AddFieldClause(columnName, fieldDesc, getProperties(context.properties()));
    }

    @Override
    public ParseNode visitDropFieldClause(StarRocksParser.DropFieldClauseContext context) {
        String columnName = getIdentifierName(context.identifier());
        List<String> parts = getFieldName(context.nestedFieldName());
        String fieldName = null;
        if (parts != null && !parts.isEmpty()) {
            fieldName = parts.get(parts.size() - 1);
            parts.remove(parts.size() - 1);
        }
        if (fieldName == null) {
            throw new ParsingException("drop field clause name is null");
        }
        return new DropFieldClause(columnName, fieldName, parts, getProperties(context.properties()));
    }

    @Override
    public ParseNode visitModifyColumnClause(StarRocksParser.ModifyColumnClauseContext context) {
        ColumnDef columnDef = getColumnDef(context.columnDesc());
        if (columnDef.isAutoIncrement()) {
            throw new ParsingException(PARSER_ERROR_MSG.autoIncrementForbid(columnDef.getName(), "MODIFY"),
                    columnDef.getPos());
        }
        ColumnPosition columnPosition = null;
        if (context.FIRST() != null) {
            columnPosition = ColumnPosition.FIRST;
        } else if (context.AFTER() != null) {
            StarRocksParser.IdentifierContext identifier = context.identifier(0);
            String afterColumnName = getIdentifierName(identifier);
            columnPosition = new ColumnPosition(afterColumnName, createPos(identifier));
        }
        String rollupName = null;
        if (context.rollupName != null) {
            rollupName = getIdentifierName(context.rollupName);
        }
        if (columnDef.isGeneratedColumn()) {
            if (rollupName != null) {
                throw new ParsingException(PARSER_ERROR_MSG.generatedColumnLimit("rollupName",
                        "MODIFY GENERATED COLUMN"), columnDef.getPos());
            }

            if (columnPosition != null) {
                throw new ParsingException(PARSER_ERROR_MSG.generatedColumnLimit("columnPosition",
                        "MODIFY GENERATED COLUMN"), columnDef.getPos());
            }
        }
        return new ModifyColumnClause(columnDef, columnPosition, rollupName, getProperties(context.properties()),
                createPos(context));
    }

    @Override
    public ParseNode visitModifyColumnCommentClause(StarRocksParser.ModifyColumnCommentClauseContext context) {
        return new ModifyColumnCommentClause(
                getIdentifierName(context.identifier()), ((StringLiteral) visit(context.comment())).getStringValue(),
                createPos(context));
    }

    @Override
    public ParseNode visitColumnRenameClause(StarRocksParser.ColumnRenameClauseContext context) {
        String oldColumnName = getIdentifierName(context.oldColumn);
        String newColumnName = getIdentifierName(context.newColumn);
        return new ColumnRenameClause(oldColumnName, newColumnName, createPos(context));
    }

    @Override
    public ParseNode visitReorderColumnsClause(StarRocksParser.ReorderColumnsClauseContext context) {
        List<String> cols =
                context.identifierList().identifier().stream().map(this::getIdentifierName).collect(toList());
        String rollupName = null;
        if (context.rollupName != null) {
            rollupName = getIdentifierName(context.rollupName);
        }
        return new ReorderColumnsClause(cols, rollupName, getProperties(context.properties()), createPos(context));
    }

    @Override
    public ParseNode visitRollupRenameClause(StarRocksParser.RollupRenameClauseContext context) {
        String rollupName = ((Identifier) visit(context.rollupName)).getValue();
        String newRollupName = ((Identifier) visit(context.newRollupName)).getValue();
        return new RollupRenameClause(rollupName, newRollupName, createPos(context));
    }

    @Override
    public ParseNode visitCompactionClause(StarRocksParser.CompactionClauseContext ctx) {
        NodePosition pos = createPos(ctx);
        boolean baseCompaction = ctx.CUMULATIVE() == null;

        if (ctx.identifier() != null) {
            final String partitionName = ((Identifier) visit(ctx.identifier())).getValue();
            return new CompactionClause(Collections.singletonList(partitionName), baseCompaction, pos);
        } else if (ctx.identifierList() != null) {
            final List<Identifier> identifierList = visit(ctx.identifierList().identifier(), Identifier.class);
            return new CompactionClause(identifierList.stream().map(Identifier::getValue).collect(toList()),
                    baseCompaction, pos);
        } else {
            return new CompactionClause(baseCompaction, pos);
        }
    }

    @Override
    public ParseNode visitSplitTabletClause(StarRocksParser.SplitTabletClauseContext context) {
        return new SplitTabletClause(
                context.partitionNames() == null ? null : (PartitionNames) visit(context.partitionNames()),
                context.tabletList() == null ? null : (TabletList) visit(context.tabletList()),
                getProperties(context.properties()),
                createPos(context));
    }

    // ---------Alter partition clause---------

    @Override
    public ParseNode visitAddPartitionClause(StarRocksParser.AddPartitionClauseContext context) {
        boolean temporary = context.TEMPORARY() != null;
        PartitionDesc partitionDesc = null;
        if (context.singleRangePartition() != null) {
            partitionDesc = (PartitionDesc) visitSingleRangePartition(context.singleRangePartition());
        } else if (context.multiRangePartition() != null) {
            partitionDesc = (PartitionDesc) visitMultiRangePartition(context.multiRangePartition());
        } else if (context.singleItemListPartitionDesc() != null) {
            partitionDesc = (PartitionDesc) visitSingleItemListPartitionDesc(context.singleItemListPartitionDesc());
        } else if (context.multiItemListPartitionDesc() != null) {
            partitionDesc = (PartitionDesc) visitMultiItemListPartitionDesc(context.multiItemListPartitionDesc());
        }
        DistributionDesc distributionDesc = null;
        if (context.distributionDesc() != null) {
            distributionDesc = (DistributionDesc) visitDistributionDesc(context.distributionDesc());
        }
        Map<String, String> properties = new HashMap<>();
        if (context.properties() != null) {
            List<Property> propertyList = visit(context.properties().property(), Property.class);
            for (Property property : propertyList) {
                properties.put(property.getKey(), property.getValue());
            }
        }
        return new AddPartitionClause(partitionDesc, distributionDesc, properties, temporary, createPos(context));
    }

    @Override
    public ParseNode visitDropPartitionClause(StarRocksParser.DropPartitionClauseContext context) {
        boolean temp = context.TEMPORARY() != null;
        boolean force = context.FORCE() != null;
        boolean exists = context.EXISTS() != null;
        boolean dropAll = context.ALL() != null;
        Identifier identifier = null;
        StarRocksParser.IdentifierContext identifierContext = context.identifier();
        if (identifierContext != null) {
            identifier = (Identifier) visit(context.identifier());
        }
        StarRocksParser.IdentifierListContext identifierListContext = context.identifierList();
        List<Identifier> identifierList = null;
        if (identifierListContext != null && identifierListContext.identifier() != null) {
            identifierList = visit(identifierListContext.identifier(), Identifier.class);
        }
        if (context.multiRangePartition() != null) {
            MultiRangePartitionDesc partitionDesc = (MultiRangePartitionDesc)
                    visitMultiRangePartition(context.multiRangePartition());
            return new DropPartitionClause(exists, partitionDesc, temp, force, createPos(context));
        } else if (identifier != null) {
            String partitionName = ((Identifier) visit(context.identifier())).getValue();
            return new DropPartitionClause(exists, partitionName, temp, force, createPos(context));
        } else if (context.where != null) {
            Expr whereExpr = (Expr) visitIfPresent(context.where);
            return new DropPartitionClause(exists, whereExpr, temp, force, createPos(context));
        } else if (dropAll) {
            return new DropPartitionClause(temp, force, dropAll, createPos(context));
        } else {
            if (CollectionUtils.isNotEmpty(identifierList)) {
                List<String> partitionNames = identifierList.stream().map(i -> i.getValue()).collect(toList());
                return new DropPartitionClause(exists, partitionNames, temp, force, createPos(context));
            }
        }
        return null;
    }

    @Override
    public ParseNode visitTruncatePartitionClause(StarRocksParser.TruncatePartitionClauseContext context) {
        PartitionNames partitionNames = null;
        if (context.partitionNames() != null) {
            partitionNames = (PartitionNames) visit(context.partitionNames());
        }
        return new TruncatePartitionClause(partitionNames, createPos(context));
    }

    @Override
    public ParseNode visitModifyPartitionClause(StarRocksParser.ModifyPartitionClauseContext context) {
        Map<String, String> properties = null;
        NodePosition pos = createPos(context);
        if (context.propertyList() != null) {
            properties = new HashMap<>();
            List<Property> propertyList = visit(context.propertyList().property(), Property.class);
            for (Property property : propertyList) {
                properties.put(property.getKey(), property.getValue());
            }
        }
        if (context.identifier() != null) {
            final String partitionName = ((Identifier) visit(context.identifier())).getValue();
            return new ModifyPartitionClause(Collections.singletonList(partitionName), properties, pos);
        } else if (context.identifierList() != null) {
            final List<Identifier> identifierList = visit(context.identifierList().identifier(), Identifier.class);
            return new ModifyPartitionClause(identifierList.stream().map(Identifier::getValue).collect(toList()),
                    properties, pos);
        } else {
            return ModifyPartitionClause.createStarClause(properties, pos);
        }
    }

    @Override
    public ParseNode visitReplacePartitionClause(StarRocksParser.ReplacePartitionClauseContext context) {
        PartitionNames partitionNames = (PartitionNames) visit(context.parName);
        PartitionNames newPartitionNames = (PartitionNames) visit(context.tempParName);

        return new ReplacePartitionClause(partitionNames, newPartitionNames,
                getProperties(context.properties()), createPos(context));
    }

    @Override
    public ParseNode visitPartitionRenameClause(StarRocksParser.PartitionRenameClauseContext context) {
        String partitionName = ((Identifier) visit(context.parName)).getValue();
        String newPartitionName = ((Identifier) visit(context.newParName)).getValue();

        return new PartitionRenameClause(partitionName, newPartitionName, createPos(context));
    }

    // -------------------------------------------- Pipe Statement -----------------------------------------------------

    private PipeName resolvePipeName(StarRocksParser.QualifiedNameContext context) {
        String dbName = null;
        String pipeName = null;
        QualifiedName qualifiedName = getQualifiedName(context);
        if (qualifiedName.getParts().size() == 2) {
            dbName = qualifiedName.getParts().get(0);
            pipeName = qualifiedName.getParts().get(1);
        } else if (qualifiedName.getParts().size() == 1) {
            pipeName = qualifiedName.getParts().get(0);
        } else {
            throw new ParsingException(PARSER_ERROR_MSG.invalidPipeName(qualifiedName.toString()));
        }

        if (dbName != null && pipeName != null) {
            return new PipeName(createPos(context), dbName, pipeName);
        } else if (pipeName != null) {
            return new PipeName(createPos(context), pipeName);
        } else {
            throw new ParsingException(PARSER_ERROR_MSG.invalidPipeName(qualifiedName.toString()));
        }
    }

    @Override
    public ParseNode visitCreatePipeStatement(StarRocksParser.CreatePipeStatementContext context) {
        PipeName pipeName = resolvePipeName(context.qualifiedName());
        boolean ifNotExists = context.ifNotExists() != null && context.ifNotExists().IF() != null;
        boolean replace = context.orReplace() != null && context.orReplace().OR() != null;

        if (ifNotExists && replace) {
            throw new ParsingException(PARSER_ERROR_MSG.conflictedOptions("OR REPLACE", "IF NOT EXISTS"));
        }
        ParseNode insertNode = visit(context.insertStatement());
        if (!(insertNode instanceof InsertStmt)) {
            throw new ParsingException(PARSER_ERROR_MSG.unsupportedStatement(insertNode.toSql()),
                    context.insertStatement());
        }
        Map<String, String> properties = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        if (context.properties() != null) {
            List<Property> propertyList = visit(context.properties().property(), Property.class);
            for (Property property : propertyList) {
                properties.put(property.getKey(), property.getValue());
            }
        }
        InsertStmt insertStmt = (InsertStmt) insertNode;
        int insertSqlIndex = context.insertStatement().start.getStartIndex();

        return new CreatePipeStmt(ifNotExists, replace, pipeName, insertSqlIndex, insertStmt, properties,
                createPos(context));
    }

    @Override
    public ParseNode visitDropPipeStatement(StarRocksParser.DropPipeStatementContext context) {
        PipeName pipeName = resolvePipeName(context.qualifiedName());
        boolean ifExists = context.IF() != null;
        return new DropPipeStmt(ifExists, pipeName, createPos(context));
    }

    @Override
    public ParseNode visitShowPipeStatement(StarRocksParser.ShowPipeStatementContext context) {
        String dbName = null;
        if (context.qualifiedName() != null) {
            dbName = getQualifiedName(context.qualifiedName()).toString();
        }
        List<OrderByElement> orderBy = null;
        if (context.ORDER() != null) {
            orderBy = new ArrayList<>();
            orderBy.addAll(visit(context.sortItem(), OrderByElement.class));
        }
        LimitElement limit = null;
        if (context.limitElement() != null) {
            limit = (LimitElement) visit(context.limitElement());
        }
        if (context.LIKE() != null) {
            StringLiteral stringLiteral = (StringLiteral) visit(context.pattern);
            return new ShowPipeStmt(dbName, stringLiteral.getValue(), null, orderBy, limit, createPos(context));
        } else if (context.WHERE() != null) {
            return new ShowPipeStmt(dbName, null, (Expr) visit(context.expression()), orderBy, limit,
                    createPos(context));
        } else {
            return new ShowPipeStmt(dbName, null, null, orderBy, limit, createPos(context));
        }
    }

    @Override
    public ParseNode visitDescPipeStatement(StarRocksParser.DescPipeStatementContext context) {
        PipeName pipeName = resolvePipeName(context.qualifiedName());
        return new DescPipeStmt(createPos(context), pipeName);
    }

    @Override
    public ParseNode visitAlterPipeClause(StarRocksParser.AlterPipeClauseContext context) {
        if (context.SUSPEND() != null) {
            return new AlterPipePauseResume(createPos(context), true);
        } else if (context.RESUME() != null) {
            return new AlterPipePauseResume(createPos(context), false);
        } else if (context.RETRY() != null) {
            if (context.ALL() != null) {
                return new AlterPipeClauseRetry(createPos(context), true);
            } else {
                String fileName = ((StringLiteral) visitString(context.fileName)).getStringValue();
                return new AlterPipeClauseRetry(createPos(context), false, fileName);
            }
        } else if (context.SET() != null) {
            Map<String, String> properties = getPropertyList(context.propertyList());
            if (MapUtils.isEmpty(properties)) {
                throw new ParsingException("empty property");
            }
            return new AlterPipeSetProperty(createPos(context), properties);
        } else {
            throw new ParsingException(PARSER_ERROR_MSG.unsupportedOpWithInfo(context.toString()));
        }
    }

    @Override
    public ParseNode visitAlterPipeStatement(StarRocksParser.AlterPipeStatementContext context) {
        PipeName pipeName = resolvePipeName(context.qualifiedName());
        AlterPipeClause alterPipeClause = (AlterPipeClause) visit(context.alterPipeClause());
        return new AlterPipeStmt(createPos(context), pipeName, alterPipeClause);
    }

    // ------------------------------------------- Plan Tuning Statement -----------------------------------------------
    public ParseNode visitAlterPlanAdvisorAddStatement(StarRocksParser.AlterPlanAdvisorAddStatementContext context) {
        QueryStatement queryStmt = (QueryStatement) visitQueryStatement(context.queryStatement());
        int start = context.queryStatement().start.getStartIndex();
        int end = context.queryStatement().stop.getStopIndex();
        Interval interval = new Interval(start, end);
        String query = context.start.getInputStream().getText(interval);
        queryStmt.setOrigStmt(new OriginStatement(query, 0));
        if (queryStmt.isExplain()) {
            throw new ParsingException(PARSER_ERROR_MSG.unsupportedStatement("query should not be a explain stmt"));
        }
        queryStmt.setIsExplain(false, StatementBase.ExplainLevel.PLAN_ADVISOR);
        return new AddPlanAdvisorStmt(createPos(context), queryStmt);
    }

    public ParseNode visitTruncatePlanAdvisorStatement(StarRocksParser.TruncatePlanAdvisorStatementContext context) {
        return new ClearPlanAdvisorStmt(createPos(context));
    }

    public ParseNode visitAlterPlanAdvisorDropStatement(StarRocksParser.AlterPlanAdvisorDropStatementContext context) {
        String advisorId = ((StringLiteral) visit(context.string())).getStringValue();
        return new DelPlanAdvisorStmt(createPos(context), advisorId);
    }

    public ParseNode visitShowPlanAdvisorStatement(StarRocksParser.ShowPlanAdvisorStatementContext context) {
        return new ShowPlanAdvisorStmt(createPos(context));
    }

    // ---------------------------------------- Warehouse Statement ---------------------------------------------------
    @Override
    public ParseNode visitCreateWarehouseStatement(StarRocksParser.CreateWarehouseStatementContext context) {
        Identifier identifier = (Identifier) visit(context.identifierOrString());
        String whName = identifier.getValue();
        Map<String, String> properties = null;
        if (context.properties() != null) {
            properties = new HashMap<>();
            List<Property> propertyList = visit(context.properties().property(), Property.class);
            for (Property property : propertyList) {
                properties.put(property.getKey(), property.getValue());
            }
        }
        String comment = null;
        if (context.comment() != null) {
            comment = ((StringLiteral) visit(context.comment())).getStringValue();
        }
        return new CreateWarehouseStmt(context.IF() != null, whName, properties, comment, createPos(context));
    }

    @Override
    public ParseNode visitSuspendWarehouseStatement(StarRocksParser.SuspendWarehouseStatementContext context) {
        String warehouseName = ((Identifier) visit(context.identifier())).getValue();
        return new SuspendWarehouseStmt(warehouseName, createPos(context));
    }

    @Override
    public ParseNode visitResumeWarehouseStatement(StarRocksParser.ResumeWarehouseStatementContext context) {
        String warehouseName = ((Identifier) visit(context.identifier())).getValue();
        return new ResumeWarehouseStmt(warehouseName, createPos(context));
    }

    @Override
    public ParseNode visitDropWarehouseStatement(StarRocksParser.DropWarehouseStatementContext context) {
        Identifier identifier = (Identifier) visit(context.identifierOrString());
        String warehouseName = identifier.getValue();
        return new DropWarehouseStmt(context.IF() != null, warehouseName, createPos(context));
    }

    @Override
    public ParseNode visitSetWarehouseStatement(StarRocksParser.SetWarehouseStatementContext context) {
        Identifier identifier = (Identifier) visit(context.identifierOrString());
        String warehouseName = identifier.getValue();
        return new SetWarehouseStmt(warehouseName, createPos(context));
    }

    @Override
    public ParseNode visitShowWarehousesStatement(StarRocksParser.ShowWarehousesStatementContext context) {
        String pattern = null;
        if (context.pattern != null) {
            StringLiteral stringLiteral = (StringLiteral) visit(context.pattern);
            pattern = stringLiteral.getValue();
        }

        return new ShowWarehousesStmt(pattern, createPos(context));
    }

    @Override
    public ParseNode visitShowClustersStatement(StarRocksParser.ShowClustersStatementContext context) {
        String whName = ((Identifier) visit(context.identifier())).getValue();
        return new ShowClustersStmt(whName, createPos(context));
    }

    @Override
    public ParseNode visitShowNodesStatement(StarRocksParser.ShowNodesStatementContext context) {
        String pattern = null;
        String warehouseName = null;
        String cnGroupName = "";
        if (context.WAREHOUSE() != null) {
            warehouseName = ((Identifier) visit(context.identifier())).getValue();
            if (context.cngroupName != null) {
                cnGroupName = ((Identifier) visit(context.identifierOrString())).getValue();
            }
        } else if (context.WAREHOUSES() != null) {
            if (context.pattern != null) {
                StringLiteral stringLiteral = (StringLiteral) visit(context.pattern);
                pattern = stringLiteral.getValue();
            }
        }
        return new ShowNodesStmt(warehouseName, cnGroupName, pattern, createPos(context));
    }

    @Override
    public ParseNode visitAlterWarehouseStatement(StarRocksParser.AlterWarehouseStatementContext context) {
        Identifier identifier = (Identifier) visit(context.identifierOrString());
        String whName = identifier.getValue();
        Map<String, String> properties = new HashMap<>();
        if (context.modifyPropertiesClause() != null) {
            ModifyTablePropertiesClause clause = (ModifyTablePropertiesClause) visit(context.modifyPropertiesClause());
            properties = clause.getProperties();
        }
        return new AlterWarehouseStmt(whName, properties, createPos(context));
    }

    @Override
    public ParseNode visitCreateCNGroupStatement(StarRocksParser.CreateCNGroupStatementContext context) {
        Identifier identifier = (Identifier) visit(context.identifierOrString(0));
        String warehouseName = identifier.getValue();
        boolean ifNotExists = context.IF() != null;
        String comment = context.comment() == null ? "" : ((StringLiteral) visit(context.comment())).getStringValue();

        identifier = (Identifier) visit(context.identifierOrString(1));
        String cnGroupName = identifier.getValue();

        Map<String, String> properties = null;
        if (context.properties() != null) {
            properties = new HashMap<>();
            List<Property> propertyList = visit(context.properties().property(), Property.class);
            for (Property property : propertyList) {
                properties.put(property.getKey(), property.getValue());
            }
        }
        return new CreateCnGroupStmt(ifNotExists, warehouseName, cnGroupName, comment, properties);
    }

    @Override
    public ParseNode visitDropCNGroupStatement(StarRocksParser.DropCNGroupStatementContext context) {
        Identifier identifier = (Identifier) visit(context.identifierOrString(0));
        String warehouseName = identifier.getValue();
        identifier = (Identifier) visit(context.identifierOrString(1));
        String cnGroupName = identifier.getValue();
        boolean ifExists = context.IF() != null;
        boolean isForce = context.FORCE() != null;

        return new DropCnGroupStmt(ifExists, warehouseName, cnGroupName, isForce);
    }

    @Override
    public ParseNode visitEnableCNGroupStatement(StarRocksParser.EnableCNGroupStatementContext context) {
        Identifier identifier = (Identifier) visit(context.identifierOrString(0));
        String warehouseName = identifier.getValue();
        identifier = (Identifier) visit(context.identifierOrString(1));
        String cnGroupName = identifier.getValue();
        return new EnableDisableCnGroupStmt(warehouseName, cnGroupName, true);
    }

    @Override
    public ParseNode visitDisableCNGroupStatement(StarRocksParser.DisableCNGroupStatementContext context) {
        Identifier identifier = (Identifier) visit(context.identifierOrString(0));
        String warehouseName = identifier.getValue();
        identifier = (Identifier) visit(context.identifierOrString(1));
        String cnGroupName = identifier.getValue();
        return new EnableDisableCnGroupStmt(warehouseName, cnGroupName, false);
    }

    @Override
    public ParseNode visitAlterCNGroupStatement(StarRocksParser.AlterCNGroupStatementContext context) {
        Identifier identifier = (Identifier) visit(context.identifierOrString(0));
        String warehouseName = identifier.getValue();
        identifier = (Identifier) visit(context.identifierOrString(1));
        String cnGroupName = identifier.getValue();

        Map<String, String> properties = new HashMap<>();
        if (context.modifyPropertiesClause() != null) {
            ModifyTablePropertiesClause clause = (ModifyTablePropertiesClause) visit(context.modifyPropertiesClause());
            properties = clause.getProperties();
        }
        return new AlterCnGroupStmt(warehouseName, cnGroupName, properties);
    }
    // ------------------------------------------- Transaction Statement ---------------------------------------------------

    @Override
    public ParseNode visitBeginStatement(StarRocksParser.BeginStatementContext context) {
        return new BeginStmt(createPos(context));
    }

    @Override
    public ParseNode visitCommitStatement(StarRocksParser.CommitStatementContext context) {
        return new CommitStmt(createPos(context));
    }

    @Override
    public ParseNode visitRollbackStatement(StarRocksParser.RollbackStatementContext context) {
        return new RollbackStmt(createPos(context));
    }

    // ------------------------------------------- Translate Statement -------------------------------------------------
    @Override
    public ParseNode visitTranslateStatement(StarRocksParser.TranslateStatementContext context) {
        String dialect = ((Identifier) visit(context.dialect().identifier())).getValue();
        return new TranslateStmt(createPos(context), dialect, ((StringLiteral) visit(context.translateSQL())).getValue());
    }

    @Override
    public ParseNode visitTranslateSQL(StarRocksParser.TranslateSQLContext context) {
        StringBuilder buf = new StringBuilder();
        int lastLine = context.start.getLine();
        int lastPosition = 0;
        for (int i = 0; i < context.getChildCount(); ++i) {
            TerminalNode child = (TerminalNode) context.getChild(i);
            if (i > 0) {
                int currentLine = child.getSymbol().getLine();
                if (lastLine != currentLine) {
                    buf.append('\n');
                    lastLine = currentLine;
                    lastPosition = 0;
                }

                buf.append(" ".repeat(child.getSymbol().getCharPositionInLine() - lastPosition));
                lastPosition = child.getSymbol().getCharPositionInLine();
            }
            buf.append(child.getText());
            lastPosition += child.getText().length();
        }
        return new StringLiteral(buf.toString(), createPos(context));
    }

    // ------------------------------------------- Query Statement -----------------------------------------------------

    @Override
    public ParseNode visitQueryStatement(StarRocksParser.QueryStatementContext context) {
        QueryRelation queryRelation = (QueryRelation) visit(context.queryRelation());
        QueryStatement queryStatement = new QueryStatement(queryRelation);
        if (context.outfile() != null) {
            queryStatement.setOutFileClause((OutFileClause) visit(context.outfile()));
        }

        if (context.explainDesc() != null) {
            queryStatement.setIsExplain(true, getExplainType(context.explainDesc()));
        }

        if (context.optimizerTrace() != null) {
            String module = "base";
            if (context.optimizerTrace().identifier() != null) {
                module = ((Identifier) visit(context.optimizerTrace().identifier())).getValue();
            }
            queryStatement.setIsTrace(getTraceMode(context.optimizerTrace()), module);
        }

        queryStatement.setQueryStartIndex(context.queryRelation().start.getStartIndex());

        return queryStatement;
    }

    private Tracers.Mode getTraceMode(StarRocksParser.OptimizerTraceContext context) {
        if (context.LOGS() != null) {
            return Tracers.Mode.LOGS;
        } else if (context.VALUES() != null) {
            return Tracers.Mode.VARS;
        } else if (context.TIMES() != null) {
            return Tracers.Mode.TIMER;
        } else if (context.ALL() != null) {
            return Tracers.Mode.TIMING;
        } else if (context.REASON() != null) {
            return Tracers.Mode.REASON;
        } else {
            return Tracers.Mode.NONE;
        }
    }

    @Override
    public ParseNode visitQueryRelation(StarRocksParser.QueryRelationContext context) {
        List<CTERelation> withQuery = new ArrayList<>();
        if (context.withClause() != null) {
            withQuery = visit(context.withClause().commonTableExpression(), CTERelation.class);
        }
        QueryRelation queryRelation = (QueryRelation) visit(context.queryNoWith());
        withQuery.forEach(queryRelation::addCTERelation);
        return queryRelation;
    }

    @Override
    public ParseNode visitCommonTableExpression(StarRocksParser.CommonTableExpressionContext context) {
        QueryRelation queryRelation = (QueryRelation) visit(context.queryRelation());
        // Regenerate cteID when generating plan
        return new CTERelation(
                RelationId.of(queryRelation).hashCode(),
                ((Identifier) visit(context.name)).getValue(),
                getColumnNames(context.columnAliases()),
                new QueryStatement(queryRelation),
                queryRelation.getPos());
    }

    @Override
    public ParseNode visitQueryNoWith(StarRocksParser.QueryNoWithContext context) {
        List<OrderByElement> orderByElements = new ArrayList<>();
        if (context.ORDER() != null) {
            orderByElements.addAll(visit(context.sortItem(), OrderByElement.class));
        }

        LimitElement limitElement = null;
        if (context.limitElement() != null) {
            limitElement = (LimitElement) visit(context.limitElement());
        }

        QueryRelation queryRelation = (QueryRelation) visit(context.queryPrimary());
        queryRelation.setOrderBy(orderByElements);
        queryRelation.setLimit(limitElement);
        return queryRelation;
    }

    @Override
    public ParseNode visitSetOperation(StarRocksParser.SetOperationContext context) {
        NodePosition pos = createPos(context);
        QueryRelation left = (QueryRelation) visit(context.left);
        QueryRelation right = (QueryRelation) visit(context.right);

        boolean distinct = true;
        if (context.setQuantifier() != null) {
            if (context.setQuantifier().DISTINCT() != null) {
                distinct = true;
            } else if (context.setQuantifier().ALL() != null) {
                distinct = false;
            }
        }

        SetQualifier setQualifier = distinct ? SetQualifier.DISTINCT : SetQualifier.ALL;
        switch (context.operator.getType()) {
            case StarRocksLexer.UNION:
                if (left instanceof UnionRelation && ((UnionRelation) left).getQualifier().equals(setQualifier)) {
                    ((UnionRelation) left).addRelation(right);
                    return left;
                } else {
                    return new UnionRelation(Lists.newArrayList(left, right), setQualifier, pos);
                }
            case StarRocksLexer.INTERSECT:
                if (left instanceof IntersectRelation &&
                        ((IntersectRelation) left).getQualifier().equals(setQualifier)) {
                    ((IntersectRelation) left).addRelation(right);
                    return left;
                } else {
                    return new IntersectRelation(Lists.newArrayList(left, right), setQualifier, pos);
                }
            default:
                if (left instanceof ExceptRelation && ((ExceptRelation) left).getQualifier().equals(setQualifier)) {
                    ((ExceptRelation) left).addRelation(right);
                    return left;
                } else {
                    return new ExceptRelation(Lists.newArrayList(left, right), setQualifier, pos);
                }
        }
    }

    private Map<String, String> extractVarHintValues(List<HintNode> hints) {
        Map<String, String> selectHints = new HashMap<>();
        if (CollectionUtils.isEmpty(hints)) {
            return selectHints;
        }

        for (HintNode hintNode : hints) {
            if (hintNode instanceof SetVarHint) {
                selectHints.putAll(hintNode.getValue());
            }
        }
        return selectHints;
    }

    @Override
    public ParseNode visitQuerySpecification(StarRocksParser.QuerySpecificationContext context) {
        Relation from = null;
        List<SelectListItem> selectItems = visit(context.selectItem(), SelectListItem.class);

        if (context.fromClause() instanceof StarRocksParser.DualContext) {
            for (SelectListItem item : selectItems) {
                if (item.isStar()) {
                    throw new ParsingException(PARSER_ERROR_MSG.noTableUsed(), item.getPos());
                }
            }
        } else {
            StarRocksParser.FromContext fromContext = (StarRocksParser.FromContext) context.fromClause();
            if (fromContext.relations() != null) {
                List<Relation> relations = visit(fromContext.relations().relation(), Relation.class);
                Iterator<Relation> iterator = relations.iterator();
                Relation relation = iterator.next();
                while (iterator.hasNext()) {
                    Relation next = iterator.next();
                    relation = new JoinRelation(null, relation, next, null, false);
                }
                from = relation;
            }

            if (fromContext.pivotClause() != null) {
                PivotRelation pivotRelation = (PivotRelation) visit(fromContext.pivotClause());
                pivotRelation.setQuery(from);
                from = pivotRelation;
            }
        }

        /*
          from == null means a statement without from or from dual, add a single row of null values here,
          so that the semantics are the same, and the processing of subsequent query logic can be simplified,
          such as select sum(1) or select sum(1) from dual, will be converted to select sum(1) from (values(null)) t.
          This can share the same logic as select sum(1) from table
         */
        if (from == null) {
            from = ValuesRelation.newDualRelation();
        }

        boolean isDistinct = context.setQuantifier() != null && context.setQuantifier().DISTINCT() != null;
        SelectList selectList = new SelectList(selectItems, isDistinct);
        selectList.setHintNodes(hintMap.get(context));

        SelectRelation resultSelectRelation = new SelectRelation(
                selectList,
                from,
                (Expr) visitIfPresent(context.where),
                (GroupByClause) visitIfPresent(context.groupingElement()),
                (Expr) visitIfPresent(context.having),
                createPos(context));

        // extend Query with QUALIFY to nested queries with filter.
        if (context.qualifyFunction != null) {
            resultSelectRelation.setOrderBy(new ArrayList<>());

            // used to indicate nested query, represent the 'from' part of outer query.
            SubqueryRelation subqueryRelation = new SubqueryRelation(new QueryStatement(resultSelectRelation));

            // use virtual table name to indicate subquery.
            TableName qualifyTableName = new TableName(null, "__QUALIFY__TABLE");
            subqueryRelation.setAlias(qualifyTableName);

            // use virtual item name to indicate column of window function.
            SelectListItem windowFunction = selectItems.get(selectItems.size() - 1);
            windowFunction.setAlias("__QUALIFY__VALUE");

            long selectValue = Long.parseLong(context.limit.getText());

            // need delete last item, because It shouldn't appear in result.
            List<SelectListItem> selectItemsVirtual = Lists.newArrayList(selectItems);
            selectItemsVirtual.remove(selectItemsVirtual.size() - 1);

            List<SelectListItem> selectItemsOuter = new ArrayList<>();
            for (SelectListItem item : selectItemsVirtual) {
                if (item.getExpr() instanceof SlotRef) {
                    SlotRef exprRef = (SlotRef) item.getExpr();
                    String columnName = item.getAlias() == null ? exprRef.getColumnName() : item.getAlias();
                    SlotRef resultSlotRef = new SlotRef(qualifyTableName, columnName);
                    selectItemsOuter.add(new SelectListItem(resultSlotRef, null));
                } else {
                    throw new ParsingException("Can't support result other than column.");
                }
            }

            // used to represent result, caused by we use nested query.
            SelectList selectListOuter = new SelectList(selectItemsOuter, isDistinct);

            // used to construct BinaryPredicate for QUALIFY.
            IntLiteral rightValue = new IntLiteral(selectValue);
            SlotRef leftSlotRef = new SlotRef(qualifyTableName, "__QUALIFY__VALUE");

            BinaryType op = getComparisonOperator(((TerminalNode) context.comparisonOperator()
                    .getChild(0)).getSymbol());
            return new SelectRelation(selectListOuter, subqueryRelation,
                    new BinaryPredicate(op, leftSlotRef, rightValue), null, null, createPos(context));
        } else {
            return resultSelectRelation;
        }
    }

    @Override
    public ParseNode visitSelectSingle(StarRocksParser.SelectSingleContext context) {
        String alias = null;
        if (context.identifier() != null) {
            alias = ((Identifier) visit(context.identifier())).getValue();
        } else if (context.string() != null) {
            alias = ((StringLiteral) visit(context.string())).getStringValue();
        }

        return new SelectListItem((Expr) visit(context.expression()), alias, createPos(context));
    }

    @Override
    public ParseNode visitSelectAll(StarRocksParser.SelectAllContext context) {
        NodePosition pos = createPos(context);
        List<String> excludedColumns = new ArrayList<>();
        if (context.excludeClause() != null) {
            StarRocksParser.ExcludeClauseContext excludeCtx = context.excludeClause();
            for (StarRocksParser.IdentifierContext idCtx : excludeCtx.identifier()) {
                excludedColumns.add(((Identifier) visit(idCtx)).getValue());
            }
        }
        if (context.qualifiedName() != null) {
            QualifiedName qualifiedName = getQualifiedName(context.qualifiedName());
            return new SelectListItem(qualifiedNameToTableName(qualifiedName), pos, excludedColumns);
        }
        return new SelectListItem(null, pos, excludedColumns);
    }

    @Override
    public ParseNode visitSingleGroupingSet(StarRocksParser.SingleGroupingSetContext context) {
        return new GroupByClause(new ArrayList<>(visit(context.expressionList().expression(), Expr.class)),
                GroupByClause.GroupingType.GROUP_BY, createPos(context));
    }

    @Override
    public ParseNode visitRollup(StarRocksParser.RollupContext context) {
        List<Expr> groupingExprs = visit(context.expressionList().expression(), Expr.class);
        return new GroupByClause(new ArrayList<>(groupingExprs), GroupByClause.GroupingType.ROLLUP, createPos(context));
    }

    @Override
    public ParseNode visitCube(StarRocksParser.CubeContext context) {
        List<Expr> groupingExprs = visit(context.expressionList().expression(), Expr.class);
        return new GroupByClause(new ArrayList<>(groupingExprs), GroupByClause.GroupingType.CUBE, createPos(context));
    }

    @Override
    public ParseNode visitMultipleGroupingSets(StarRocksParser.MultipleGroupingSetsContext context) {
        List<ArrayList<Expr>> groupingSets = new ArrayList<>();
        for (StarRocksParser.GroupingSetContext groupingSetContext : context.groupingSet()) {
            List<Expr> l = visit(groupingSetContext.expression(), Expr.class);
            groupingSets.add(new ArrayList<>(l));
        }

        return new GroupByClause(groupingSets, GroupByClause.GroupingType.GROUPING_SETS, createPos(context));
    }

    @Override
    public ParseNode visitGroupingOperation(StarRocksParser.GroupingOperationContext context) {
        List<Expr> arguments = visit(context.expression(), Expr.class);
        return new GroupingFunctionCallExpr("grouping", arguments, createPos(context));
    }

    @Override
    public ParseNode visitWindowFrame(StarRocksParser.WindowFrameContext context) {
        NodePosition pos = createPos(context);
        if (context.end != null) {
            return new AnalyticWindow(
                    getFrameType(context.frameType),
                    (AnalyticWindow.Boundary) visit(context.start),
                    (AnalyticWindow.Boundary) visit(context.end),
                    pos);
        } else {
            return new AnalyticWindow(
                    getFrameType(context.frameType),
                    (AnalyticWindow.Boundary) visit(context.start),
                    pos);
        }
    }

    private static AnalyticWindow.Type getFrameType(Token type) {
        if (type.getType() == StarRocksLexer.RANGE) {
            return AnalyticWindow.Type.RANGE;
        } else {
            return AnalyticWindow.Type.ROWS;
        }
    }

    @Override
    public ParseNode visitUnboundedFrame(StarRocksParser.UnboundedFrameContext context) {
        return new AnalyticWindow.Boundary(getUnboundedFrameBoundType(context.boundType), null);
    }

    @Override
    public ParseNode visitBoundedFrame(StarRocksParser.BoundedFrameContext context) {
        return new AnalyticWindow.Boundary(getBoundedFrameBoundType(context.boundType),
                (Expr) visit(context.expression()));
    }

    @Override
    public ParseNode visitCurrentRowBound(StarRocksParser.CurrentRowBoundContext context) {
        return new AnalyticWindow.Boundary(AnalyticWindow.BoundaryType.CURRENT_ROW, null);
    }

    private static AnalyticWindow.BoundaryType getBoundedFrameBoundType(Token token) {
        if (token.getType() == StarRocksLexer.PRECEDING) {
            return AnalyticWindow.BoundaryType.PRECEDING;
        } else {
            return AnalyticWindow.BoundaryType.FOLLOWING;
        }
    }

    private static AnalyticWindow.BoundaryType getUnboundedFrameBoundType(Token token) {
        if (token.getType() == StarRocksLexer.PRECEDING) {
            return AnalyticWindow.BoundaryType.UNBOUNDED_PRECEDING;
        } else {
            return AnalyticWindow.BoundaryType.UNBOUNDED_FOLLOWING;
        }
    }

    @Override
    public ParseNode visitSortItem(StarRocksParser.SortItemContext context) {
        return new OrderByElement(
                (Expr) visit(context.expression()),
                getOrderingType(context.ordering),
                getNullOrderingType(getOrderingType(context.ordering), context.nullOrdering),
                createPos(context));
    }

    private boolean getNullOrderingType(boolean isAsc, Token token) {
        if (token == null) {
            return (!SqlModeHelper.check(sqlMode, SqlModeHelper.MODE_SORT_NULLS_LAST)) == isAsc;
        }
        return token.getType() == StarRocksLexer.FIRST;
    }

    private static boolean getOrderingType(Token token) {
        if (token == null) {
            return true;
        }

        return token.getType() == StarRocksLexer.ASC;
    }

    @Override
    public ParseNode visitLimitElement(StarRocksParser.LimitElementContext context) {
        if (context.limit.PARAMETER() != null || (context.offset != null && context.offset.PARAMETER() != null)) {
            throw new ParsingException("using parameter(?) as limit or offset not supported");
        }

        Expr limit;
        Expr offset = new IntLiteral(0);

        if (context.limit.INTEGER_VALUE() != null) {
            limit = new IntLiteral(Long.parseLong(context.limit.INTEGER_VALUE().getText()));
        } else if (context.limit.userVariable() != null) {
            limit = (UserVariableExpr) visit(context.limit.userVariable());
        } else {
            throw new ParsingException("unsupported invalid limit value", createPos(context.limit));
        }

        if (context.offset != null) {
            if (context.offset.INTEGER_VALUE() != null) {
                offset = new IntLiteral(Long.parseLong(context.offset.INTEGER_VALUE().getText()));
            } else if (context.offset.userVariable() != null) {
                offset = (UserVariableExpr) visit(context.offset.userVariable());
            } else {
                throw new ParsingException("unsupported invalid offset value", createPos(context.offset));
            }
        }
        return new LimitElement(offset, limit, createPos(context));
    }

    @Override
    public ParseNode visitRelation(StarRocksParser.RelationContext context) {
        Relation relation = (Relation) visit(context.relationPrimary());
        List<JoinRelation> joinRelations = visit(context.joinRelation(), JoinRelation.class);

        Relation leftChildRelation = relation;
        for (JoinRelation joinRelation : joinRelations) {
            joinRelation.setLeft(leftChildRelation);
            leftChildRelation = joinRelation;
        }
        return leftChildRelation;
    }

    @Override
    public ParseNode visitParenthesizedRelation(StarRocksParser.ParenthesizedRelationContext context) {
        if (context.relations().relation().size() == 1) {
            return visit(context.relations().relation().get(0));
        } else {
            List<Relation> relations = visit(context.relations().relation(), Relation.class);
            Iterator<Relation> iterator = relations.iterator();
            Relation relation = iterator.next();
            while (iterator.hasNext()) {
                relation = new JoinRelation(null, relation, iterator.next(), null, false);
            }
            return relation;
        }
    }

    @Override
    public ParseNode visitTableAtom(StarRocksParser.TableAtomContext context) {
        Token start = context.start;
        Token stop = context.stop;
        QualifiedName qualifiedName = getQualifiedName(context.qualifiedName());
        TableName tableName = qualifiedNameToTableName(qualifiedName);
        PartitionNames partitionNames = null;
        if (context.partitionNames() != null) {
            stop = context.partitionNames().stop;
            partitionNames = (PartitionNames) visit(context.partitionNames());
        }

        List<Long> tabletIds = Lists.newArrayList();
        if (context.tabletList() != null) {
            stop = context.tabletList().stop;
            tabletIds = context.tabletList().INTEGER_VALUE().stream().map(ParseTree::getText)
                    .map(Long::parseLong).collect(toList());
        }

        List<Long> replicaLists = Lists.newArrayList();
        if (context.replicaList() != null) {
            stop = context.replicaList().stop;
            replicaLists = context.replicaList().INTEGER_VALUE().stream().map(ParseTree::getText).map(Long::parseLong)
                    .collect(toList());
        }

        TableRelation tableRelation =
                new TableRelation(tableName, partitionNames, tabletIds, replicaLists, createPos(start, stop));
        if (context.bracketHint() != null) {
            for (Identifier identifier : visit(context.bracketHint().identifier(), Identifier.class)) {
                // just ignore the hint if failed to add it which is the same as the previous behaviour
                tableRelation.addTableHint(identifier.getValue());
            }
        }

        if (context.alias != null) {
            Identifier identifier = (Identifier) visit(context.alias);
            tableRelation.setAlias(new TableName(null, identifier.getValue()));
        }

        if (context.queryPeriod() != null) {
            tableRelation.setQueryPeriodString(buildQueryPeriodString(context.queryPeriod()));
            QueryPeriod queryPeriod = (QueryPeriod) visit(context.queryPeriod());
            if (queryPeriod != null) {
                tableRelation.setQueryPeriod(queryPeriod);
            }
        }

        if (context.BEFORE() != null) {
            String ts = ((StringLiteral) visit(context.ts)).getStringValue();
            SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
            try {
                tableRelation.setGtid(GtidGenerator.getGtid(dateFormat.parse(ts).getTime()));
            } catch (ParseException e) {
                tableRelation.setGtid(Long.parseLong(ts));
            }
        }

        if (context.sampleClause() != null) {
            tableRelation.setSampleClause(visitSampleClause(context.sampleClause()));
        }

        return tableRelation;
    }

    @Override
    public TableSampleClause visitSampleClause(StarRocksParser.SampleClauseContext context) {
        TableSampleClause result = new TableSampleClause(createPos(context));
        if (context.propertyList() != null) {
            Map<String, String> properties = getPropertyList(context.propertyList());
            try {
                result.analyzeProperties(properties);
            } catch (AnalysisException e) {
                throw new ParsingException(e.getMessage(), createPos(context));
            }
        }
        return result;
    }

    @Override
    public ParseNode visitQueryPeriod(StarRocksParser.QueryPeriodContext context) {
        if (context.periodType() == null || context.end == null) {
            return null;
        }

        QueryPeriod.PeriodType type = getPeriodType((Token) context.periodType().getChild(0).getPayload());
        Expr end = (Expr) visit(context.end);
        return new QueryPeriod(type, end);
    }

    private QueryPeriod.PeriodType getPeriodType(Token token) {
        switch (token.getType()) {
            case StarRocksLexer.TIMESTAMP:
            case StarRocksLexer.SYSTEM_TIME:
                return QueryPeriod.PeriodType.TIMESTAMP;
            case StarRocksLexer.VERSION:
                return QueryPeriod.PeriodType.VERSION;
            default:
                throw new ParsingException("Unsupported query period type: " + token.getText());
        }
    }

    // only used for mysql external table
    private String buildQueryPeriodString(StarRocksParser.QueryPeriodContext context) {
        StringBuilder sb = new StringBuilder();
        for (ParseTree child : context.children) {
            sb.append(child.getText());
            sb.append(" ");
        }
        return sb.toString();
    }

    @Override
    public ParseNode visitJoinRelation(StarRocksParser.JoinRelationContext context) {
        // Because left recursion is required to parse the leftmost atom table first.
        // Therefore, the parsed result does not contain the information of the left table,
        // which is temporarily assigned to Null,
        // and the information of the left table will be filled in visitRelation
        Relation left = null;
        Relation right = (Relation) visit(context.rightRelation);

        JoinOperator joinType = JoinOperator.INNER_JOIN;
        if (context.crossOrInnerJoinType() != null) {
            if (context.crossOrInnerJoinType().CROSS() != null) {
                joinType = JoinOperator.CROSS_JOIN;
            } else {
                joinType = JoinOperator.INNER_JOIN;
            }
        } else if (context.outerAndSemiJoinType().LEFT() != null) {
            if (context.outerAndSemiJoinType().OUTER() != null) {
                joinType = JoinOperator.LEFT_OUTER_JOIN;
            } else if (context.outerAndSemiJoinType().SEMI() != null) {
                joinType = JoinOperator.LEFT_SEMI_JOIN;
            } else if (context.outerAndSemiJoinType().AWARE() != null) {
                joinType = JoinOperator.NULL_AWARE_LEFT_ANTI_JOIN;
            } else if (context.outerAndSemiJoinType().ANTI() != null) {
                joinType = JoinOperator.LEFT_ANTI_JOIN;
            } else {
                joinType = JoinOperator.LEFT_OUTER_JOIN;
            }
        } else if (context.outerAndSemiJoinType().RIGHT() != null) {
            if (context.outerAndSemiJoinType().OUTER() != null) {
                joinType = JoinOperator.RIGHT_OUTER_JOIN;
            } else if (context.outerAndSemiJoinType().SEMI() != null) {
                joinType = JoinOperator.RIGHT_SEMI_JOIN;
            } else if (context.outerAndSemiJoinType().ANTI() != null) {
                joinType = JoinOperator.RIGHT_ANTI_JOIN;
            } else {
                joinType = JoinOperator.RIGHT_OUTER_JOIN;
            }
        } else if (context.outerAndSemiJoinType().FULL() != null) {
            joinType = JoinOperator.FULL_OUTER_JOIN;
        }

        Expr predicate = null;
        List<String> usingColNames = null;
        if (context.joinCriteria() != null) {
            if (context.joinCriteria().ON() != null) {
                predicate = (Expr) visit(context.joinCriteria().expression());
            } else {
                List<Identifier> criteria = visit(context.joinCriteria().identifier(), Identifier.class);
                usingColNames = criteria.stream().map(Identifier::getValue).collect(Collectors.toList());
            }
        }

        JoinRelation joinRelation = new JoinRelation(joinType, left, right, predicate,
                context.LATERAL() != null, createPos(context));
        joinRelation.setUsingColNames(usingColNames);
        if (context.bracketHint() != null) {
            joinRelation.setJoinHint(((Identifier) visit(context.bracketHint().identifier(0))).getValue());
            if (context.bracketHint().primaryExpression() != null) {
                joinRelation.setSkewColumn((Expr) visit(context.bracketHint().primaryExpression()));
            }
            if (context.bracketHint().literalExpressionList() != null) {
                joinRelation.setSkewValues(visit(context.bracketHint().literalExpressionList().literalExpression(),
                        Expr.class));
            }
        }

        return joinRelation;
    }

    @Override
    public ParseNode visitInlineTable(StarRocksParser.InlineTableContext context) {
        List<ValueList> rowValues = visit(context.rowConstructor(), ValueList.class);
        List<List<Expr>> rows = rowValues.stream().map(ValueList::getRow).collect(toList());

        List<String> colNames = getColumnNames(context.columnAliases());
        if (colNames == null) {
            colNames = new ArrayList<>();
            for (int i = 0; i < rows.get(0).size(); ++i) {
                colNames.add("column_" + i);
            }
        }

        ValuesRelation valuesRelation = new ValuesRelation(rows, colNames, createPos(context));

        if (context.alias != null) {
            Identifier identifier = (Identifier) visit(context.alias);
            valuesRelation.setAlias(new TableName(null, identifier.getValue()));
        }

        return valuesRelation;
    }

    @Override
    public ParseNode visitNamedArguments(StarRocksParser.NamedArgumentsContext context) {
        String name = ((Identifier) visit(context.identifier())).getValue();
        if (name == null || name.isEmpty() || name.equals(" ")) {
            throw new ParsingException(PARSER_ERROR_MSG.unsupportedExpr(" The left of => shouldn't be empty"));
        }
        Expr node = (Expr) visit(context.expression());
        if (node == null) {
            throw new ParsingException(PARSER_ERROR_MSG.unsupportedExpr(" The right of => shouldn't be null"));
        }
        return new NamedArgument(name, node);
    }

    @Override
    public ParseNode visitTableFunction(StarRocksParser.TableFunctionContext context) {
        QualifiedName functionName = getQualifiedName(context.qualifiedName());
        List<Expr> parameters = visit(context.expressionList().expression(), Expr.class);
        FunctionCallExpr functionCallExpr =
                new FunctionCallExpr(FunctionName.createFnName(functionName.toString()), parameters);
        TableFunctionRelation tableFunctionRelation = new TableFunctionRelation(functionCallExpr);

        if (context.alias != null) {
            Identifier identifier = (Identifier) visit(context.alias);
            tableFunctionRelation.setAlias(new TableName(null, identifier.getValue()));
        }
        tableFunctionRelation.setColumnOutputNames(getColumnNames(context.columnAliases()));
        return tableFunctionRelation;
    }

    @Override
    public ParseNode visitNormalizedTableFunction(StarRocksParser.NormalizedTableFunctionContext context) {
        QualifiedName functionName = getQualifiedName(context.qualifiedName());
        List<Expr> parameters = null;
        if (context.argumentList().expressionList() != null) {
            parameters = visit(context.argumentList().expressionList().expression(), Expr.class);
        } else {
            parameters = visit(context.argumentList().namedArgumentList().namedArgument(), Expr.class);
        }
        int namedArgNum = parameters.stream().filter(f -> f instanceof NamedArgument).collect(toList()).size();
        if (namedArgNum > 0 && namedArgNum < parameters.size()) {
            throw new SemanticException("All arguments must be passed by name or all must be passed positionally");
        }
        FunctionCallExpr functionCallExpr =
                new FunctionCallExpr(FunctionName.createFnName(functionName.toString()), parameters,
                        createPos(context));
        TableFunctionRelation relation = new TableFunctionRelation(functionCallExpr);

        if (context.alias != null) {
            Identifier identifier = (Identifier) visit(context.alias);
            relation.setAlias(new TableName(null, identifier.getValue()));
        }
        relation.setColumnOutputNames(getColumnNames(context.columnAliases()));

        return new NormalizedTableFunctionRelation(relation);
    }

    @Override
    public ParseNode visitFileTableFunction(StarRocksParser.FileTableFunctionContext context) {
        Map<String, String> properties = getPropertyList(context.propertyList());
        return new FileTableFunctionRelation(properties, NodePosition.ZERO);
    }

    @Override
    public ParseNode visitRowConstructor(StarRocksParser.RowConstructorContext context) {
        ArrayList<Expr> row = new ArrayList<>(visit(context.expressionList().expression(), Expr.class));
        return new ValueList(row, createPos(context));
    }

    @Override
    public ParseNode visitPartitionNames(StarRocksParser.PartitionNamesContext context) {
        if (context.keyPartitions() != null) {
            return visit(context.keyPartitions());
        }

        List<Identifier> identifierList = visit(context.identifierOrString(), Identifier.class);
        return new PartitionNames(context.TEMPORARY() != null,
                identifierList.stream().map(Identifier::getValue).collect(toList()),
                createPos(context));
    }

    @Override
    public ParseNode visitTabletList(StarRocksParser.TabletListContext context) {
        return new TabletList(context.INTEGER_VALUE().stream().map(ParseTree::getText)
                .map(Long::parseLong).collect(toList()), createPos(context));
    }

    @Override
    public ParseNode visitKeyPartitionList(StarRocksParser.KeyPartitionListContext context) {
        List<String> partitionColNames = Lists.newArrayList();
        List<Expr> partitionColValues = Lists.newArrayList();
        for (StarRocksParser.KeyPartitionContext pair : context.keyPartition()) {
            Identifier partitionName = (Identifier) visit(pair.partitionColName);
            Expr partitionValue = (Expr) visit(pair.partitionColValue);
            partitionColNames.add(partitionName.getValue());
            partitionColValues.add(partitionValue);
        }

        return new PartitionNames(false, new ArrayList<>(), partitionColNames, partitionColValues, NodePosition.ZERO);
    }

    @Override
    public ParseNode visitSubquery(StarRocksParser.SubqueryContext context) {
        return visit(context.queryRelation());
    }

    @Override
    public ParseNode visitQueryWithParentheses(StarRocksParser.QueryWithParenthesesContext context) {
        QueryRelation relation = (QueryRelation) visit(context.subquery());
        return new SubqueryRelation(new QueryStatement(relation));
    }

    @Override
    public ParseNode visitSubqueryWithAlias(StarRocksParser.SubqueryWithAliasContext context) {
        QueryRelation queryRelation = (QueryRelation) visit(context.subquery());
        QueryStatement qs = new QueryStatement(queryRelation);
        SubqueryRelation subqueryRelation = new SubqueryRelation(qs, context.ASSERT_ROWS() != null, qs.getPos());

        if (context.alias != null) {
            Identifier identifier = (Identifier) visit(context.alias);
            subqueryRelation.setAlias(new TableName(null, identifier.getValue()));
        } else {
            subqueryRelation.setAlias(new TableName(null, null));
        }

        subqueryRelation.setColumnOutputNames(getColumnNames(context.columnAliases()));

        return subqueryRelation;
    }

    @Override
    public ParseNode visitSubqueryExpression(StarRocksParser.SubqueryExpressionContext context) {
        QueryRelation queryRelation = (QueryRelation) visit(context.subquery());
        return new Subquery(new QueryStatement(queryRelation));
    }

    @Override
    public ParseNode visitInSubquery(StarRocksParser.InSubqueryContext context) {
        boolean isNotIn = context.NOT() != null;
        QueryRelation query = (QueryRelation) visit(context.queryRelation());

        return new InPredicate((Expr) visit(context.value), new Subquery(new QueryStatement(query)),
                isNotIn, createPos(context));
    }

    @Override
    public ParseNode visitTupleInSubquery(StarRocksParser.TupleInSubqueryContext context) {
        boolean isNotIn = context.NOT() != null;
        QueryRelation query = (QueryRelation) visit(context.queryRelation());
        List<Expr> tupleExpressions = visit(context.expression(), Expr.class);

        return new MultiInPredicate(tupleExpressions, new Subquery(new QueryStatement(query)), isNotIn,
                createPos(context));
    }

    @Override
    public ParseNode visitExists(StarRocksParser.ExistsContext context) {
        QueryRelation query = (QueryRelation) visit(context.queryRelation());
        return new ExistsPredicate(new Subquery(new QueryStatement(query)), false, createPos(context));
    }

    @Override
    public ParseNode visitScalarSubquery(StarRocksParser.ScalarSubqueryContext context) {
        BinaryType op = getComparisonOperator(((TerminalNode) context.comparisonOperator().getChild(0))
                .getSymbol());
        Subquery subquery = new Subquery(new QueryStatement((QueryRelation) visit(context.queryRelation())));
        return new BinaryPredicate(op, (Expr) visit(context.booleanExpression()), subquery, createPos(context));
    }

    @Override
    public ParseNode visitShowFunctionsStatement(StarRocksParser.ShowFunctionsStatementContext context) {
        boolean isBuiltIn = context.BUILTIN() != null;
        boolean isGlobal = context.GLOBAL() != null;
        boolean isVerbose = context.FULL() != null;

        String dbName = null;
        if (context.db != null) {
            dbName = getQualifiedName(context.db).toString();
        }

        String pattern = null;
        if (context.pattern != null) {
            pattern = ((StringLiteral) visit(context.pattern)).getValue();
        }

        Expr where = null;
        if (context.expression() != null) {
            where = (Expr) visit(context.expression());
        }

        return new ShowFunctionsStmt(dbName, isBuiltIn, isGlobal, isVerbose, pattern, where, createPos(context));
    }

    @Override
    public ParseNode visitShowPrivilegesStatement(StarRocksParser.ShowPrivilegesStatementContext ctx) {
        return new ShowPrivilegesStmt();
    }

    @Override
    public ParseNode visitDropFunctionStatement(StarRocksParser.DropFunctionStatementContext context) {
        QualifiedName qualifiedName = getQualifiedName(context.qualifiedName());
        String functionName = qualifiedName.toString();
        boolean isGlobal = context.GLOBAL() != null;
        boolean dropIfExist = context.IF() != null && context.EXISTS() != null;
        FunctionName fnName = FunctionName.createFnName(functionName);
        if (isGlobal) {
            if (!Strings.isNullOrEmpty(fnName.getDb())) {
                throw new ParsingException(PARSER_ERROR_MSG.invalidUDFName(functionName), qualifiedName.getPos());
            }
            fnName.setAsGlobalFunction();
        }

        return new DropFunctionStmt(fnName, getFunctionArgsDef(context.typeList()), createPos(context), dropIfExist);
    }

    @Override
    public ParseNode visitCreateFunctionStatement(StarRocksParser.CreateFunctionStatementContext context) {
        String functionType = "SCALAR";
        boolean replaceIfExists = context.orReplace() != null && context.orReplace().OR() != null;
        boolean isGlobal = context.GLOBAL() != null;
        boolean createIfNotExists = context.ifNotExists() != null && context.ifNotExists().EXISTS() != null;
        if (context.functionType != null) {
            functionType = context.functionType.getText();
        }

        QualifiedName qualifiedName = getQualifiedName(context.qualifiedName());
        String functionName = qualifiedName.toString();

        TypeDef returnTypeDef = new TypeDef(getType(context.returnType), createPos(context.returnType));

        Map<String, String> properties = null;
        if (context.properties() != null) {
            properties = new HashMap<>();
            List<Property> propertyList = visit(context.properties().property(), Property.class);
            for (Property property : propertyList) {
                properties.put(property.getKey(), property.getValue());
            }
        }
        if (context.inlineProperties() != null) {
            properties = new HashMap<>();
            List<Property> propertyList = visit(context.inlineProperties().inlineProperty(), Property.class);
            for (Property property : propertyList) {
                properties.put(property.getKey(), property.getValue());
            }
        }
        String inlineContent = null;
        if (context.inlineFunction() != null) {
            String content = context.inlineFunction().ATTACHMENT().getText();
            inlineContent = context.inlineFunction().ATTACHMENT().getText().substring(2, content.length() - 2);
        }

        FunctionName fnName = FunctionName.createFnName(functionName);
        if (isGlobal) {
            if (!Strings.isNullOrEmpty(fnName.getDb())) {
                throw new ParsingException(PARSER_ERROR_MSG.invalidUDFName(functionName), qualifiedName.getPos());
            }
            fnName.setAsGlobalFunction();
        }

        return new CreateFunctionStmt(functionType, fnName,
                getFunctionArgsDef(context.typeList()), returnTypeDef, properties, inlineContent, replaceIfExists,
                createIfNotExists);
    }

    // ------------------------------------------- Authz Statement -------------------------------------------------

    @Override
    public ParseNode visitCreateUserStatement(StarRocksParser.CreateUserStatementContext context) {
        UserIdentity user = (UserIdentity) visit(context.user());
        UserAuthOption authOption = (UserAuthOption) visitIfPresent(context.authOption());
        boolean ifNotExists = context.IF() != null;

        List<String> roles = new ArrayList<>();
        if (context.roleList() != null) {
            roles.addAll(context.roleList().identifierOrString().stream().map(this::visit).map(
                    s -> ((Identifier) s).getValue()).collect(toList()));
        }

        Map<String, String> properties = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        if (context.properties() != null) {
            List<Property> propertyList = visit(context.properties().property(), Property.class);
            for (Property property : propertyList) {
                properties.put(property.getKey(), property.getValue());
            }
        }
        return new CreateUserStmt(user, ifNotExists, authOption, roles, properties, createPos(context));
    }

    @Override
    public ParseNode visitDropUserStatement(StarRocksParser.DropUserStatementContext context) {
        UserIdentity user = (UserIdentity) visit(context.user());
        return new DropUserStmt(user, context.EXISTS() != null, createPos(context));
    }

    @Override
    public ParseNode visitAlterUserStatement(StarRocksParser.AlterUserStatementContext context) {
        UserIdentity user = (UserIdentity) visit(context.user());

        if (context.ROLE() != null) {
            List<String> roles = new ArrayList<>();
            if (context.roleList() != null) {
                roles.addAll(context.roleList().identifierOrString().stream().map(this::visit).map(
                        s -> ((Identifier) s).getValue()).collect(toList()));
            }

            SetRoleType setRoleType;
            if (context.ALL() != null) {
                setRoleType = SetRoleType.ALL;
            } else if (context.NONE() != null) {
                setRoleType = SetRoleType.NONE;
            } else {
                setRoleType = SetRoleType.ROLE;
            }

            return new SetDefaultRoleStmt(user, setRoleType, roles, createPos(context));
        }

        if (context.authOption() != null) {
            UserAuthOption authOption = (UserAuthOption) visitIfPresent(context.authOption());
            Map<String, String> properties = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            if (context.properties() != null) {
                List<Property> propertyList = visit(context.properties().property(), Property.class);
                for (Property property : propertyList) {
                    properties.put(property.getKey(), property.getValue());
                }
            }
            return new AlterUserStmt(user, context.EXISTS() != null, authOption, properties, createPos(context));
        }

        // handle alter user xxx set properties
        List<SetUserPropertyVar> list = new ArrayList<>();
        List<Property> propertyList = visit(context.properties().property(), Property.class);
        for (Property property : propertyList) {
            list.add(new SetUserPropertyVar(property.getKey(), property.getValue()));
        }
        return new SetUserPropertyStmt(user.getUser(), list, createPos(context));
    }

    @Override
    public ParseNode visitShowUserStatement(StarRocksParser.ShowUserStatementContext context) {
        NodePosition pos = createPos(context);
        if (context.USERS() != null) {
            return new ShowUserStmt(true, pos);
        } else {
            return new ShowUserStmt(false, pos);
        }
    }

    @Override
    public ParseNode visitShowAllAuthentication(StarRocksParser.ShowAllAuthenticationContext context) {
        return new ShowAuthenticationStmt(null, true, createPos(context));
    }

    @Override
    public ParseNode visitShowAuthenticationForUser(StarRocksParser.ShowAuthenticationForUserContext context) {
        NodePosition pos = createPos(context);
        if (context.user() != null) {
            return new ShowAuthenticationStmt((UserIdentity) visit(context.user()), false, pos);
        } else {
            return new ShowAuthenticationStmt(null, false, pos);
        }
    }

    @Override
    public ParseNode visitExecuteAsStatement(StarRocksParser.ExecuteAsStatementContext context) {
        boolean allowRevert = context.WITH() == null;
        // we only support WITH NO REVERT for now
        return new ExecuteAsStmt((UserIdentity) visit(context.user()), allowRevert, createPos(context));
    }

    @Override
    public ParseNode visitCreateRoleStatement(StarRocksParser.CreateRoleStatementContext context) {
        List<String> roles = context.roleList().identifierOrString().stream().map(this::visit).map(
                s -> ((Identifier) s).getValue()).collect(Collectors.toList());
        String comment = context.comment() == null ? "" : ((StringLiteral) visit(context.comment())).getStringValue();
        return new CreateRoleStmt(roles, context.NOT() != null, comment, createPos(context));
    }

    @Override
    public ParseNode visitAlterRoleStatement(StarRocksParser.AlterRoleStatementContext context) {
        List<String> roles = context.roleList().identifierOrString().stream().map(this::visit).map(
                s -> ((Identifier) s).getValue()).collect(Collectors.toList());

        StringLiteral stringLiteral = (StringLiteral) visit(context.string());
        String comment = stringLiteral.getStringValue();
        return new AlterRoleStmt(roles, context.IF() != null, comment);
    }

    @Override
    public ParseNode visitDropRoleStatement(StarRocksParser.DropRoleStatementContext context) {
        List<String> roles = new ArrayList<>();
        roles.addAll(context.roleList().identifierOrString().stream().map(this::visit).map(
                s -> ((Identifier) s).getValue()).collect(toList()));
        return new DropRoleStmt(roles, context.EXISTS() != null, createPos(context));
    }

    @Override
    public ParseNode visitShowRolesStatement(StarRocksParser.ShowRolesStatementContext context) {
        return new ShowRolesStmt();
    }

    @Override
    public ParseNode visitGrantRoleToUser(StarRocksParser.GrantRoleToUserContext context) {
        List<String> roleNameList = new ArrayList<>();
        for (StarRocksParser.IdentifierOrStringContext oneContext : context.identifierOrStringList()
                .identifierOrString()) {
            roleNameList.add(((Identifier) visit(oneContext)).getValue());
        }

        return new GrantRoleStmt(roleNameList, (UserIdentity) visit(context.user()), createPos(context));
    }

    @Override
    public ParseNode visitGrantRoleToRole(StarRocksParser.GrantRoleToRoleContext context) {
        List<String> roleNameList = new ArrayList<>();
        for (StarRocksParser.IdentifierOrStringContext oneContext : context.identifierOrStringList()
                .identifierOrString()) {
            roleNameList.add(((Identifier) visit(oneContext)).getValue());
        }

        return new GrantRoleStmt(roleNameList, ((Identifier) visit(context.identifierOrString())).getValue(),
                GrantType.ROLE, createPos(context));
    }

    @Override
    public ParseNode visitRevokeRoleFromUser(StarRocksParser.RevokeRoleFromUserContext context) {
        List<String> roleNameList = new ArrayList<>();
        for (StarRocksParser.IdentifierOrStringContext oneContext : context.identifierOrStringList()
                .identifierOrString()) {
            roleNameList.add(((Identifier) visit(oneContext)).getValue());
        }

        return new RevokeRoleStmt(roleNameList, (UserIdentity) visit(context.user()), createPos(context));
    }

    @Override
    public ParseNode visitRevokeRoleFromRole(StarRocksParser.RevokeRoleFromRoleContext context) {
        List<String> roleNameList = new ArrayList<>();
        for (StarRocksParser.IdentifierOrStringContext oneContext : context.identifierOrStringList()
                .identifierOrString()) {
            roleNameList.add(((Identifier) visit(oneContext)).getValue());
        }

        return new RevokeRoleStmt(roleNameList, ((Identifier) visit(context.identifierOrString())).getValue(),
                GrantType.ROLE, createPos(context));
    }

    @Override
    public ParseNode visitSetRoleStatement(StarRocksParser.SetRoleStatementContext context) {
        List<String> roles = new ArrayList<>();

        if (context.roleList() != null) {
            roles.addAll(context.roleList().identifierOrString().stream().map(this::visit).map(
                    s -> ((Identifier) s).getValue()).collect(toList()));
        }

        SetRoleType setRoleType;
        if (context.ALL() != null) {
            setRoleType = SetRoleType.ALL;
        } else if (context.DEFAULT() != null) {
            setRoleType = SetRoleType.DEFAULT;
        } else if (context.NONE() != null) {
            setRoleType = SetRoleType.NONE;
        } else {
            setRoleType = SetRoleType.ROLE;
        }

        return new SetRoleStmt(setRoleType, roles, createPos(context));
    }

    @Override
    public ParseNode visitSetDefaultRoleStatement(StarRocksParser.SetDefaultRoleStatementContext context) {
        List<String> roles = new ArrayList<>();

        if (context.roleList() != null) {
            roles.addAll(context.roleList().identifierOrString().stream().map(this::visit).map(
                    s -> ((Identifier) s).getValue()).collect(toList()));
        }

        SetRoleType setRoleType;
        if (context.ALL() != null) {
            setRoleType = SetRoleType.ALL;
        } else if (context.NONE() != null) {
            setRoleType = SetRoleType.NONE;
        } else {
            setRoleType = SetRoleType.ROLE;
        }

        return new SetDefaultRoleStmt((UserIdentity) visit(context.user()), setRoleType, roles, createPos(context));
    }

    @Override
    public ParseNode visitShowGrantsStatement(StarRocksParser.ShowGrantsStatementContext context) {
        NodePosition pos = createPos(context);
        if (context.ROLE() != null) {
            Identifier role = (Identifier) visit(context.identifierOrString());
            return new ShowGrantsStmt(role.getValue(), GrantType.ROLE, pos);
        } else {
            UserIdentity userId = context.user() == null ? null : (UserIdentity) visit(context.user());
            return new ShowGrantsStmt(userId, pos);
        }
    }

    @Override
    public ParseNode visitAuthWithoutPlugin(StarRocksParser.AuthWithoutPluginContext context) {
        String password = ((StringLiteral) visit(context.string())).getStringValue();
        boolean isPasswordPlain = context.PASSWORD() == null;
        return new UserAuthOption(null, password, isPasswordPlain, createPos(context));
    }

    @Override
    public ParseNode visitAuthWithPlugin(StarRocksParser.AuthWithPluginContext context) {
        Identifier authPlugin = (Identifier) visit(context.identifierOrString());
        String authString = context.string() == null ?
                null : ((StringLiteral) visit(context.string())).getStringValue();
        boolean isPasswordPlain = context.AS() == null;

        return new UserAuthOption(authPlugin.getValue().toUpperCase(), authString, isPasswordPlain, createPos(context));
    }

    @Override
    public ParseNode visitGrantRevokeClause(StarRocksParser.GrantRevokeClauseContext context) {
        NodePosition pos = createPos(context);
        if (context.user() != null) {
            UserIdentity user = (UserIdentity) visit(context.user());
            return new GrantRevokeClause(user, null, pos);
        } else {
            String roleName = ((Identifier) visit(context.identifierOrString())).getValue();
            return new GrantRevokeClause(null, roleName, pos);
        }
    }

    @Override
    public ParseNode visitGrantOnUser(StarRocksParser.GrantOnUserContext context) {
        List<String> privList = Collections.singletonList("IMPERSONATE");
        GrantRevokeClause clause = (GrantRevokeClause) visit(context.grantRevokeClause());
        List<UserIdentity> users = context.user().stream()
                .map(user -> (UserIdentity) visit(user)).collect(toList());
        GrantRevokePrivilegeObjects objects = new GrantRevokePrivilegeObjects();
        objects.setUserPrivilegeObjectList(users);
        return new GrantPrivilegeStmt(privList, "USER", clause, objects,
                context.WITH() != null, createPos(context));
    }

    @Override
    public ParseNode visitRevokeOnUser(StarRocksParser.RevokeOnUserContext context) {
        List<String> privList = Collections.singletonList("IMPERSONATE");
        GrantRevokeClause clause = (GrantRevokeClause) visit(context.grantRevokeClause());
        List<UserIdentity> users = context.user().stream()
                .map(user -> (UserIdentity) visit(user)).collect(toList());
        GrantRevokePrivilegeObjects objects = new GrantRevokePrivilegeObjects();
        objects.setUserPrivilegeObjectList(users);
        return new RevokePrivilegeStmt(privList, "USER", clause, objects, createPos(context));
    }

    @Override
    public ParseNode visitGrantOnTableBrief(StarRocksParser.GrantOnTableBriefContext context) {
        List<String> privilegeList = context.privilegeTypeList().privilegeType().stream().map(
                c -> ((Identifier) visit(c)).getValue().toUpperCase()).collect(toList());

        return new GrantPrivilegeStmt(privilegeList, "TABLE",
                (GrantRevokeClause) visit(context.grantRevokeClause()),
                parsePrivilegeObjectNameList(context.privObjectNameList()),
                context.WITH() != null,
                createPos(context));
    }

    @Override
    public ParseNode visitRevokeOnTableBrief(StarRocksParser.RevokeOnTableBriefContext context) {
        List<String> privilegeList = context.privilegeTypeList().privilegeType().stream().map(
                c -> ((Identifier) visit(c)).getValue().toUpperCase()).collect(toList());

        return new RevokePrivilegeStmt(privilegeList, "TABLE",
                (GrantRevokeClause) visit(context.grantRevokeClause()),
                parsePrivilegeObjectNameList(context.privObjectNameList()),
                createPos(context));
    }

    @Override
    public ParseNode visitGrantOnSystem(StarRocksParser.GrantOnSystemContext context) {
        List<String> privilegeList = context.privilegeTypeList().privilegeType().stream().map(
                c -> ((Identifier) visit(c)).getValue().toUpperCase()).collect(toList());

        return new GrantPrivilegeStmt(privilegeList, "SYSTEM",
                (GrantRevokeClause) visit(context.grantRevokeClause()), null, context.WITH() != null,
                createPos(context));
    }

    @Override
    public ParseNode visitRevokeOnSystem(StarRocksParser.RevokeOnSystemContext context) {
        List<String> privilegeList = context.privilegeTypeList().privilegeType().stream().map(
                c -> ((Identifier) visit(c)).getValue().toUpperCase()).collect(toList());

        return new RevokePrivilegeStmt(privilegeList, "SYSTEM",
                (GrantRevokeClause) visit(context.grantRevokeClause()), null, createPos(context));
    }

    @Override
    public ParseNode visitGrantOnPrimaryObj(StarRocksParser.GrantOnPrimaryObjContext context) {
        List<String> privilegeList = context.privilegeTypeList().privilegeType().stream().map(
                c -> ((Identifier) visit(c)).getValue().toUpperCase()).collect(toList());
        String objectTypeUnResolved = ((Identifier) visit(context.privObjectType())).getValue().toUpperCase();

        return new GrantPrivilegeStmt(privilegeList, objectTypeUnResolved,
                (GrantRevokeClause) visit(context.grantRevokeClause()),
                parsePrivilegeObjectNameList(context.privObjectNameList()),
                context.WITH() != null,
                createPos(context));
    }

    @Override
    public ParseNode visitRevokeOnPrimaryObj(StarRocksParser.RevokeOnPrimaryObjContext context) {
        List<String> privilegeList = context.privilegeTypeList().privilegeType().stream().map(
                c -> ((Identifier) visit(c)).getValue().toUpperCase()).collect(toList());
        String objectTypeUnResolved = ((Identifier) visit(context.privObjectType())).getValue().toUpperCase();

        return new RevokePrivilegeStmt(privilegeList, objectTypeUnResolved,
                (GrantRevokeClause) visit(context.grantRevokeClause()),
                parsePrivilegeObjectNameList(context.privObjectNameList()),
                createPos(context));
    }

    @Override
    public ParseNode visitGrantOnFunc(StarRocksParser.GrantOnFuncContext context) {
        List<String> privilegeList = context.privilegeTypeList().privilegeType().stream().map(
                c -> ((Identifier) visit(c)).getValue().toUpperCase()).collect(toList());
        GrantRevokePrivilegeObjects objects = buildGrantRevokePrivWithFunction(context.privFunctionObjectNameList(),
                context.GLOBAL() != null);
        return new GrantPrivilegeStmt(privilegeList, extendPrivilegeType(context.GLOBAL() != null, "FUNCTION"),
                (GrantRevokeClause) visit(context.grantRevokeClause()), objects, context.WITH() != null,
                createPos(context));
    }

    @Override
    public ParseNode visitRevokeOnFunc(StarRocksParser.RevokeOnFuncContext context) {
        List<String> privilegeList = context.privilegeTypeList().privilegeType().stream().map(
                c -> ((Identifier) visit(c)).getValue().toUpperCase()).collect(toList());
        GrantRevokePrivilegeObjects objects = buildGrantRevokePrivWithFunction(context.privFunctionObjectNameList(),
                context.GLOBAL() != null);
        return new RevokePrivilegeStmt(privilegeList, extendPrivilegeType(context.GLOBAL() != null, "FUNCTION"),
                (GrantRevokeClause) visit(context.grantRevokeClause()), objects,
                createPos(context));
    }

    private GrantRevokePrivilegeObjects buildGrantRevokePrivWithFunction(
            StarRocksParser.PrivFunctionObjectNameListContext context, boolean isGlobal) {
        List<Pair<FunctionName, FunctionArgsDef>> functions = new ArrayList<>();
        int functionSize = context.qualifiedName().size();
        List<StarRocksParser.TypeListContext> typeListContexts = context.typeList();
        for (int i = 0; i < functionSize; ++i) {
            StarRocksParser.QualifiedNameContext qualifiedNameContext = context.qualifiedName(i);
            QualifiedName qualifiedName = getQualifiedName(qualifiedNameContext);
            FunctionName functionName;
            if (qualifiedName.getParts().size() == 1) {
                functionName = new FunctionName(qualifiedName.getParts().get(0));
            } else if (qualifiedName.getParts().size() == 2) {
                functionName = new FunctionName(qualifiedName.getParts().get(0), qualifiedName.getParts().get(1));
            } else {
                throw new SemanticException("Error function format " + qualifiedName);
            }

            if (isGlobal) {
                functionName.setAsGlobalFunction();
            }

            FunctionArgsDef argsDef = getFunctionArgsDef(typeListContexts.get(i));
            functions.add(Pair.create(functionName, argsDef));
        }

        GrantRevokePrivilegeObjects objects = new GrantRevokePrivilegeObjects();
        objects.setFunctions(functions);

        return objects;
    }

    public String extendPrivilegeType(boolean isGlobal, String type) {
        if (isGlobal) {
            if (type.equals("FUNCTIONS") || type.equals("FUNCTION")) {
                return "GLOBAL " + type;
            }
        }
        return type;
    }

    @Override
    public ParseNode visitGrantOnAll(StarRocksParser.GrantOnAllContext context) {
        List<String> privilegeList = context.privilegeTypeList().privilegeType().stream().map(
                c -> ((Identifier) visit(c)).getValue().toUpperCase()).collect(toList());
        String objectTypeUnResolved = ((Identifier) visit(context.privObjectTypePlural())).getValue().toUpperCase();

        GrantRevokePrivilegeObjects objects = new GrantRevokePrivilegeObjects();
        ArrayList<String> tokenList;
        if (context.isAll != null) {
            tokenList = Lists.newArrayList("*", "*");
        } else if (context.IN() != null) {
            String dbName = ((Identifier) visit(context.identifierOrString())).getValue();
            tokenList = Lists.newArrayList(dbName, "*");
        } else {
            tokenList = Lists.newArrayList("*");
        }
        objects.setPrivilegeObjectNameTokensList(Collections.singletonList(tokenList));

        GrantPrivilegeStmt grantPrivilegeStmt = new GrantPrivilegeStmt(privilegeList, objectTypeUnResolved,
                (GrantRevokeClause) visit(context.grantRevokeClause()),
                objects, context.WITH() != null, createPos(context));
        grantPrivilegeStmt.setGrantOnAll();
        return grantPrivilegeStmt;
    }

    @Override
    public ParseNode visitRevokeOnAll(StarRocksParser.RevokeOnAllContext context) {
        List<String> privilegeList = context.privilegeTypeList().privilegeType().stream().map(
                c -> ((Identifier) visit(c)).getValue().toUpperCase()).collect(toList());
        String objectTypeUnResolved = ((Identifier) visit(context.privObjectTypePlural())).getValue().toUpperCase();

        GrantRevokePrivilegeObjects objects = new GrantRevokePrivilegeObjects();
        ArrayList<String> tokenList;
        if (context.isAll != null) {
            tokenList = Lists.newArrayList("*", "*");
        } else if (context.IN() != null) {
            String dbName = ((Identifier) visit(context.identifierOrString())).getValue();
            tokenList = Lists.newArrayList(dbName, "*");
        } else {
            tokenList = Lists.newArrayList("*");
        }
        objects.setPrivilegeObjectNameTokensList(Collections.singletonList(tokenList));

        RevokePrivilegeStmt revokePrivilegeStmt = new RevokePrivilegeStmt(privilegeList, objectTypeUnResolved,
                (GrantRevokeClause) visit(context.grantRevokeClause()), objects, createPos(context));
        revokePrivilegeStmt.setGrantOnAll();
        return revokePrivilegeStmt;
    }

    @Override
    public ParseNode visitPrivilegeType(StarRocksParser.PrivilegeTypeContext context) {
        NodePosition pos = createPos(context);
        List<String> ps = new ArrayList<>();
        for (int i = 0; i < context.getChildCount(); ++i) {
            ps.add(context.getChild(i).getText());
        }
        return new Identifier(Joiner.on(" ").join(ps), pos);
    }

    @Override
    public ParseNode visitPrivObjectType(StarRocksParser.PrivObjectTypeContext context) {
        NodePosition pos = createPos(context);
        List<String> ps = new ArrayList<>();
        for (int i = 0; i < context.getChildCount(); ++i) {
            ps.add(context.getChild(i).getText());
        }
        return new Identifier(Joiner.on(" ").join(ps), pos);
    }

    @Override
    public ParseNode visitPrivObjectTypePlural(StarRocksParser.PrivObjectTypePluralContext context) {
        NodePosition pos = createPos(context);
        List<String> ps = new ArrayList<>();
        for (int i = 0; i < context.getChildCount(); ++i) {
            ps.add(context.getChild(i).getText());
        }
        return new Identifier(Joiner.on(" ").join(ps), pos);
    }

    private GrantRevokePrivilegeObjects parsePrivilegeObjectNameList(
            StarRocksParser.PrivObjectNameListContext context) {
        if (context == null) {
            return null;
        }

        GrantRevokePrivilegeObjects grantRevokePrivilegeObjects = new GrantRevokePrivilegeObjects(createPos(context));

        List<List<String>> objectNameList = new ArrayList<>();
        for (StarRocksParser.PrivObjectNameContext privObjectNameContext : context.privObjectName()) {
            objectNameList.add(privObjectNameContext.identifierOrStringOrStar().stream()
                    .map(c -> ((Identifier) visit(c)).getValue()).collect(toList()));
        }
        grantRevokePrivilegeObjects.setPrivilegeObjectNameTokensList(objectNameList);
        return grantRevokePrivilegeObjects;
    }

    // ---------------------------------------- Security Integration Statement --------------------------------------

    @Override
    public ParseNode visitCreateSecurityIntegrationStatement(
            StarRocksParser.CreateSecurityIntegrationStatementContext context) {
        String name = ((Identifier) visit(context.identifier())).getValue();
        Map<String, String> propertyMap = new HashMap<>();
        if (context.properties() != null) {
            List<Property> propertyList = visit(context.properties().property(), Property.class);
            for (Property property : propertyList) {
                propertyMap.put(property.getKey(), property.getValue());
            }
        }
        return new CreateSecurityIntegrationStatement(name, propertyMap, createPos(context));
    }

    @Override
    public ParseNode visitAlterSecurityIntegrationStatement(
            StarRocksParser.AlterSecurityIntegrationStatementContext context) {
        String name = ((Identifier) visit(context.identifier())).getValue();
        Map<String, String> properties = new HashMap<>();
        List<Property> propertyList = visit(context.propertyList().property(), Property.class);
        for (Property property : propertyList) {
            properties.put(property.getKey(), property.getValue());
        }
        return new AlterSecurityIntegrationStatement(name, properties, createPos(context));
    }

    @Override
    public ParseNode visitDropSecurityIntegrationStatement(
            StarRocksParser.DropSecurityIntegrationStatementContext context) {
        String name = ((Identifier) visit(context.identifier())).getValue();
        return new DropSecurityIntegrationStatement(name, createPos(context));
    }

    @Override
    public ParseNode visitShowCreateSecurityIntegrationStatement(
            StarRocksParser.ShowCreateSecurityIntegrationStatementContext context) {
        String name = ((Identifier) visit(context.identifier())).getValue();
        return new ShowCreateSecurityIntegrationStatement(name, createPos(context));
    }

    @Override
    public ParseNode visitShowSecurityIntegrationStatement(
            StarRocksParser.ShowSecurityIntegrationStatementContext context) {
        return new ShowSecurityIntegrationStatement(createPos(context));
    }

    // ---------------------------------------- Group Provider Statement --------------------------------------

    @Override
    public ParseNode visitCreateGroupProviderStatement(StarRocksParser.CreateGroupProviderStatementContext context) {
        String name = ((Identifier) visit(context.identifier())).getValue();
        Map<String, String> propertyMap = new HashMap<>();
        if (context.properties() != null) {
            List<Property> propertyList = visit(context.properties().property(), Property.class);
            for (Property property : propertyList) {
                propertyMap.put(property.getKey(), property.getValue());
            }
        }
        return new CreateGroupProviderStmt(name, propertyMap, createPos(context));
    }

    @Override
    public ParseNode visitDropGroupProviderStatement(
            StarRocksParser.DropGroupProviderStatementContext context) {
        String name = ((Identifier) visit(context.identifier())).getValue();
        return new DropGroupProviderStmt(name, createPos(context));
    }

    @Override
    public ParseNode visitShowCreateGroupProviderStatement(
            StarRocksParser.ShowCreateGroupProviderStatementContext context) {
        String name = ((Identifier) visit(context.identifier())).getValue();
        return new ShowCreateGroupProviderStmt(name, createPos(context));
    }

    @Override
    public ParseNode visitShowGroupProvidersStatement(
            StarRocksParser.ShowGroupProvidersStatementContext context) {
        return new ShowGroupProvidersStmt(createPos(context));
    }

    // ------------------------------------------- Expression ----------------------------------------------------------

    @Override
    public ParseNode visitExpressionOrDefault(StarRocksParser.ExpressionOrDefaultContext context) {
        if (context.DEFAULT() != null) {
            return new DefaultValueExpr(createPos(context));
        } else {
            return visit(context.expression());
        }
    }

    @Override
    public ParseNode visitExpressionsWithDefault(StarRocksParser.ExpressionsWithDefaultContext context) {
        ArrayList<Expr> row = Lists.newArrayList();
        for (int i = 0; i < context.expressionOrDefault().size(); ++i) {
            row.add((Expr) visit(context.expressionOrDefault(i)));
        }
        return new ValueList(row, createPos(context));
    }

    @Override
    public ParseNode visitExpressionSingleton(StarRocksParser.ExpressionSingletonContext context) {
        return visit(context.expression());
    }

    @Override
    public ParseNode visitLogicalNot(StarRocksParser.LogicalNotContext context) {
        return new CompoundPredicate(CompoundPredicate.Operator.NOT, (Expr) visit(context.expression()),
                null, createPos(context));
    }

    @Override
    public ParseNode visitLogicalBinary(StarRocksParser.LogicalBinaryContext context) {
        Expr left = (Expr) visit(context.left);
        Expr right = (Expr) visit(context.right);
        return new CompoundPredicate(getLogicalBinaryOperator(context.operator), left, right, createPos(context));
    }

    private static CompoundPredicate.Operator getLogicalBinaryOperator(Token token) {
        switch (token.getType()) {
            case StarRocksLexer.AND:
            case StarRocksLexer.LOGICAL_AND:
                return CompoundPredicate.Operator.AND;
            default:
                return CompoundPredicate.Operator.OR;
        }
    }

    @Override
    public ParseNode visitPredicate(StarRocksParser.PredicateContext context) {
        if (context.predicateOperations() != null) {
            return visit(context.predicateOperations());
        } else if (context.tupleInSubquery() != null) {
            return visit(context.tupleInSubquery());
        } else {
            return visit(context.valueExpression());
        }
    }

    @Override
    public ParseNode visitIsNull(StarRocksParser.IsNullContext context) {
        Expr child = (Expr) visit(context.booleanExpression());
        NodePosition pos = createPos(context);

        if (context.NOT() == null) {
            return new IsNullPredicate(child, false, pos);
        } else {
            return new IsNullPredicate(child, true, pos);
        }
    }

    @Override
    public ParseNode visitComparison(StarRocksParser.ComparisonContext context) {
        BinaryType op = getComparisonOperator(((TerminalNode) context.comparisonOperator().getChild(0))
                .getSymbol());
        return new BinaryPredicate(op, (Expr) visit(context.left), (Expr) visit(context.right), createPos(context));
    }

    private static BinaryType getComparisonOperator(Token symbol) {
        switch (symbol.getType()) {
            case StarRocksParser.EQ:
                return BinaryType.EQ;
            case StarRocksParser.NEQ:
                return BinaryType.NE;
            case StarRocksParser.LT:
                return BinaryType.LT;
            case StarRocksParser.LTE:
                return BinaryType.LE;
            case StarRocksParser.GT:
                return BinaryType.GT;
            case StarRocksParser.GTE:
                return BinaryType.GE;
            default:
                return BinaryType.EQ_FOR_NULL;
        }
    }

    @Override
    public ParseNode visitInList(StarRocksParser.InListContext context) {
        boolean isNotIn = context.NOT() != null;
        return new InPredicate(
                (Expr) visit(context.value),
                visit(context.expressionList().expression(), Expr.class), isNotIn, createPos(context));
    }

    @Override
    public ParseNode visitBetween(StarRocksParser.BetweenContext context) {
        boolean isNotBetween = context.NOT() != null;

        return new BetweenPredicate(
                (Expr) visit(context.value),
                (Expr) visit(context.lower),
                (Expr) visit(context.upper),
                isNotBetween,
                createPos(context));
    }

    @Override
    public ParseNode visitLike(StarRocksParser.LikeContext context) {
        LikePredicate likePredicate;
        NodePosition pos = createPos(context);
        if (context.REGEXP() != null || context.RLIKE() != null) {
            likePredicate = new LikePredicate(LikePredicate.Operator.REGEXP,
                    (Expr) visit(context.value),
                    (Expr) visit(context.pattern),
                    pos);
        } else {
            likePredicate = new LikePredicate(
                    LikePredicate.Operator.LIKE,
                    (Expr) visit(context.value),
                    (Expr) visit(context.pattern),
                    pos);
        }
        if (context.NOT() != null) {
            return new CompoundPredicate(CompoundPredicate.Operator.NOT, likePredicate, null, pos);
        } else {
            return likePredicate;
        }
    }

    @Override
    public ParseNode visitSimpleCase(StarRocksParser.SimpleCaseContext context) {
        return new CaseExpr(
                (Expr) visit(context.caseExpr),
                visit(context.whenClause(), CaseWhenClause.class),
                (Expr) visitIfPresent(context.elseExpression),
                createPos(context));
    }

    @Override
    public ParseNode visitSearchedCase(StarRocksParser.SearchedCaseContext context) {
        return new CaseExpr(
                null,
                visit(context.whenClause(), CaseWhenClause.class),
                (Expr) visitIfPresent(context.elseExpression),
                createPos(context));
    }

    @Override
    public ParseNode visitWhenClause(StarRocksParser.WhenClauseContext context) {
        return new CaseWhenClause((Expr) visit(context.condition), (Expr) visit(context.result), createPos(context));
    }

    @Override
    public ParseNode visitArithmeticUnary(StarRocksParser.ArithmeticUnaryContext context) {
        Expr child = (Expr) visit(context.primaryExpression());
        NodePosition pos = createPos(context);
        switch (context.operator.getType()) {
            case StarRocksLexer.MINUS_SYMBOL:
                if (child.isLiteral() && child.getType().isNumericType()) {
                    try {
                        ((LiteralExpr) child).swapSign();
                    } catch (NotImplementedException e) {
                        throw new ParsingException(PARSER_ERROR_MSG.unsupportedExpr(child.toSql()), child.getPos());
                    }
                    return child;
                } else {
                    return new ArithmeticExpr(ArithmeticExpr.Operator.MULTIPLY, new IntLiteral(-1), child, pos);
                }
            case StarRocksLexer.PLUS_SYMBOL:
                return child;
            case StarRocksLexer.BITNOT:
                return new ArithmeticExpr(ArithmeticExpr.Operator.BITNOT, child, null, pos);
            default:
                return new CompoundPredicate(CompoundPredicate.Operator.NOT, child, null, pos);
        }
    }

    @Override
    public ParseNode visitArithmeticBinary(StarRocksParser.ArithmeticBinaryContext context) {
        Expr left = (Expr) visit(context.left);
        Expr right = (Expr) visit(context.right);
        NodePosition pos = createPos(context);
        if (left instanceof IntervalLiteral) {
            return new TimestampArithmeticExpr(getArithmeticBinaryOperator(context.operator), right,
                    ((IntervalLiteral) left).getValue(),
                    ((IntervalLiteral) left).getUnitIdentifier().getDescription(),
                    true, pos);
        }

        if (right instanceof IntervalLiteral) {
            return new TimestampArithmeticExpr(getArithmeticBinaryOperator(context.operator), left,
                    ((IntervalLiteral) right).getValue(),
                    ((IntervalLiteral) right).getUnitIdentifier().getDescription(),
                    false, pos);
        }

        return new ArithmeticExpr(getArithmeticBinaryOperator(context.operator), left, right, pos);
    }

    private static ArithmeticExpr.Operator getArithmeticBinaryOperator(Token operator) {
        switch (operator.getType()) {
            case StarRocksLexer.PLUS_SYMBOL:
                return ArithmeticExpr.Operator.ADD;
            case StarRocksLexer.MINUS_SYMBOL:
                return ArithmeticExpr.Operator.SUBTRACT;
            case StarRocksLexer.ASTERISK_SYMBOL:
                return ArithmeticExpr.Operator.MULTIPLY;
            case StarRocksLexer.SLASH_SYMBOL:
                return ArithmeticExpr.Operator.DIVIDE;
            case StarRocksLexer.PERCENT_SYMBOL:
            case StarRocksLexer.MOD:
                return ArithmeticExpr.Operator.MOD;
            case StarRocksLexer.INT_DIV:
                return ArithmeticExpr.Operator.INT_DIVIDE;
            case StarRocksLexer.BITAND:
                return ArithmeticExpr.Operator.BITAND;
            case StarRocksLexer.BITOR:
                return ArithmeticExpr.Operator.BITOR;
            case StarRocksLexer.BITXOR:
                return ArithmeticExpr.Operator.BITXOR;
            case StarRocksLexer.BIT_SHIFT_LEFT:
                return ArithmeticExpr.Operator.BIT_SHIFT_LEFT;
            case StarRocksLexer.BIT_SHIFT_RIGHT:
                return ArithmeticExpr.Operator.BIT_SHIFT_RIGHT;
            case StarRocksLexer.BIT_SHIFT_RIGHT_LOGICAL:
                return ArithmeticExpr.Operator.BIT_SHIFT_RIGHT_LOGICAL;
            default:
                throw new ParsingException(PARSER_ERROR_MSG.wrongTypeOfArgs(operator.getText()),
                        new NodePosition(operator));
        }
    }

    @Override
    public ParseNode visitOdbcFunctionCallExpression(StarRocksParser.OdbcFunctionCallExpressionContext context) {
        FunctionCallExpr functionCallExpr = (FunctionCallExpr) visit(context.functionCall());
        OdbcScalarFunctionCall odbcScalarFunctionCall = new OdbcScalarFunctionCall(functionCallExpr);
        return odbcScalarFunctionCall.mappingFunction();
    }

    private static List<Expr> getArgumentsForTimeSlice(Expr time, Expr value, String ident, String boundary) {
        List<Expr> exprs = Lists.newLinkedList();
        exprs.add(time);
        addArgumentUseTypeInt(value, exprs);
        exprs.add(new StringLiteral(ident));
        exprs.add(new StringLiteral(boundary));

        return exprs;
    }

    private static void addArgumentUseTypeInt(Expr value, List<Expr> exprs) {
        // IntLiteral may use TINYINT/SMALLINT/INT/BIGINT type
        // but time_slice only support INT type when executed in BE
        try {
            if (value instanceof IntLiteral) {
                exprs.add(new IntLiteral(((IntLiteral) value).getValue(), Type.INT));
            } else {
                exprs.add(value);
            }
        } catch (Exception e) {
            throw new IllegalArgumentException(String.format("Cast argument %s to int type failed.", value.toSql()));
        }
    }

    @Override
    public ParseNode visitSimpleFunctionCall(StarRocksParser.SimpleFunctionCallContext context) {
        String fullFunctionName = getQualifiedName(context.qualifiedName()).toString();
        NodePosition pos = createPos(context);

        FunctionName fnName = FunctionName.createFnName(fullFunctionName);
        String functionName = fnName.getFunction();
        if (functionName.equals(FunctionSet.TIME_SLICE) || functionName.equals(FunctionSet.DATE_SLICE)) {
            if (context.expression().size() == 2) {
                Expr e1 = (Expr) visit(context.expression(0));
                Expr e2 = (Expr) visit(context.expression(1));
                if (!(e2 instanceof IntervalLiteral)) {
                    e2 = new IntervalLiteral(e2, new UnitIdentifier("DAY"));
                }
                IntervalLiteral intervalLiteral = (IntervalLiteral) e2;
                FunctionCallExpr functionCallExpr = new FunctionCallExpr(fnName, getArgumentsForTimeSlice(e1,
                        intervalLiteral.getValue(), intervalLiteral.getUnitIdentifier().getDescription().toLowerCase(),
                        "floor"), pos);

                return functionCallExpr;
            } else if (context.expression().size() == 3) {
                Expr e1 = (Expr) visit(context.expression(0));
                Expr e2 = (Expr) visit(context.expression(1));
                if (!(e2 instanceof IntervalLiteral)) {
                    e2 = new IntervalLiteral(e2, new UnitIdentifier("DAY"));
                }
                IntervalLiteral intervalLiteral = (IntervalLiteral) e2;

                ParseNode e3 = visit(context.expression(2));
                if (!(e3 instanceof UnitBoundary)) {
                    throw new ParsingException(PARSER_ERROR_MSG.wrongTypeOfArgs(functionName), e3.getPos());
                }
                UnitBoundary unitBoundary = (UnitBoundary) e3;
                FunctionCallExpr functionCallExpr = new FunctionCallExpr(fnName, getArgumentsForTimeSlice(e1,
                        intervalLiteral.getValue(), intervalLiteral.getUnitIdentifier().getDescription().toLowerCase(),
                        unitBoundary.getDescription().toLowerCase()), pos);

                return functionCallExpr;
            } else if (context.expression().size() == 4) {
                Expr e1 = (Expr) visit(context.expression(0));
                Expr e2 = (Expr) visit(context.expression(1));
                Expr e3 = (Expr) visit(context.expression(2));
                Expr e4 = (Expr) visit(context.expression(3));

                if (!(e3 instanceof StringLiteral)) {
                    throw new ParsingException(PARSER_ERROR_MSG.wrongTypeOfArgs(functionName), e3.getPos());
                }
                String ident = ((StringLiteral) e3).getValue();
                if (!(e4 instanceof StringLiteral)) {
                    throw new ParsingException(PARSER_ERROR_MSG.wrongTypeOfArgs(functionName), e4.getPos());
                }
                String boundary = ((StringLiteral) e4).getValue();
                return new FunctionCallExpr(fnName, getArgumentsForTimeSlice(e1, e2, ident, boundary));
            } else {
                throw new ParsingException(PARSER_ERROR_MSG.wrongNumOfArgs(functionName), pos);
            }
        }

        if (DATE_FUNCTIONS.contains(functionName)) {
            if (context.expression().size() != 2) {
                throw new ParsingException(PARSER_ERROR_MSG.wrongNumOfArgs(functionName), pos);
            }

            Expr e1 = (Expr) visit(context.expression(0));
            Expr e2 = (Expr) visit(context.expression(1));
            if (!(e2 instanceof IntervalLiteral)) {
                e2 = new IntervalLiteral(e2, new UnitIdentifier("DAY"));
            }
            IntervalLiteral intervalLiteral = (IntervalLiteral) e2;

            return new TimestampArithmeticExpr(functionName, e1, intervalLiteral.getValue(),
                    intervalLiteral.getUnitIdentifier().getDescription(), pos);
        }

        if (functionName.equals(FunctionSet.ELEMENT_AT)) {
            List<Expr> params = visit(context.expression(), Expr.class);
            if (params.size() != 2) {
                throw new ParsingException(PARSER_ERROR_MSG.wrongNumOfArgs(functionName), pos);
            }
            return new CollectionElementExpr(params.get(0), params.get(1), false);
        }

        if (functionName.equals(FunctionSet.ISNULL)) {
            List<Expr> params = visit(context.expression(), Expr.class);
            if (params.size() != 1) {
                throw new ParsingException(PARSER_ERROR_MSG.wrongNumOfArgs(functionName), pos);
            }
            return new IsNullPredicate(params.get(0), false, pos);
        }

        if (functionName.equals(FunctionSet.ISNOTNULL)) {
            List<Expr> params = visit(context.expression(), Expr.class);
            if (params.size() != 1) {
                throw new ParsingException(PARSER_ERROR_MSG.wrongNumOfArgs(functionName), pos);
            }
            return new IsNullPredicate(params.get(0), true, pos);
        }

        if (ArithmeticExpr.isArithmeticExpr(fnName.getFunction())) {
            if (context.expression().size() < 1) {
                throw new ParsingException(PARSER_ERROR_MSG.wrongNumOfArgs(functionName), pos);
            }

            Expr e1 = (Expr) visit(context.expression(0));
            Expr e2 = context.expression().size() > 1 ? (Expr) visit(context.expression(1)) : null;
            return new ArithmeticExpr(ArithmeticExpr.getArithmeticOperator(fnName.getFunction()), e1, e2, pos);
        }

        // add default delimiters and rewrite str_to_map(str, del1, del2) to str_to_map(split(str, del1),del2)
        if (functionName.equals(FunctionSet.STR_TO_MAP)) {
            Expr e0;
            Expr e1;
            Expr e2;
            String collectionDelimiter = ",";
            String mapDelimiter = ":";
            if (context.expression().size() == 1) {
                e0 = (Expr) visit(context.expression(0));
                e1 = new StringLiteral(collectionDelimiter, pos);
                e2 = new StringLiteral(mapDelimiter, pos);
            } else if (context.expression().size() == 2) {
                e0 = (Expr) visit(context.expression(0));
                e1 = (Expr) visit(context.expression(1));
                e2 = new StringLiteral(mapDelimiter, pos);
            } else if (context.expression().size() == 3) {
                e0 = (Expr) visit(context.expression(0));
                e1 = (Expr) visit(context.expression(1));
                e2 = (Expr) visit(context.expression(2));
            } else {
                throw new ParsingException(PARSER_ERROR_MSG.wrongNumOfArgs(FunctionSet.STR_TO_MAP));
            }
            return new FunctionCallExpr(functionName, ImmutableList.of(e0, e1, e2), pos);
        }

        if (fnName.getFunction().equalsIgnoreCase(FunctionSet.CONNECTION_ID)) {
            return new InformationFunction(FunctionSet.CONNECTION_ID.toUpperCase());
        }

        if (fnName.getFunction().equalsIgnoreCase(FunctionSet.SESSION_USER)) {
            return new InformationFunction(FunctionSet.SESSION_USER.toUpperCase());
        }

        if (fnName.getFunction().equalsIgnoreCase(FunctionSet.SESSION_ID)) {
            return new InformationFunction(FunctionSet.SESSION_ID.toUpperCase());
        }

        if (functionName.equals(FunctionSet.MAP)) {
            List<Expr> exprs;
            if (context.expression() != null) {
                int num = context.expression().size();
                if (num % 2 == 1) {
                    throw new ParsingException(PARSER_ERROR_MSG.wrongNumOfArgs(num, "map()",
                            "Arguments must be in key/value pairs"), pos);
                }
                exprs = visit(context.expression(), Expr.class);
            } else {
                exprs = Collections.emptyList();
            }
            return new MapExpr(Type.ANY_MAP, exprs, pos);
        }

        if (functionName.equals(FunctionSet.SUBSTR) || functionName.equals(FunctionSet.SUBSTRING)) {
            List<Expr> exprs = Lists.newArrayList();
            if (context.expression().size() == 2) {
                Expr e1 = (Expr) visit(context.expression(0));
                Expr e2 = (Expr) visit(context.expression(1));
                exprs.add(e1);
                addArgumentUseTypeInt(e2, exprs);
            } else if (context.expression().size() == 3) {
                Expr e1 = (Expr) visit(context.expression(0));
                Expr e2 = (Expr) visit(context.expression(1));
                Expr e3 = (Expr) visit(context.expression(2));
                exprs.add(e1);
                addArgumentUseTypeInt(e2, exprs);
                addArgumentUseTypeInt(e3, exprs);
            }
            return new FunctionCallExpr(fnName, exprs, pos);
        }

        if (functionName.equals(FunctionSet.LPAD) || functionName.equals(FunctionSet.RPAD)) {
            if (context.expression().size() == 2) {
                Expr e1 = (Expr) visit(context.expression(0));
                Expr e2 = (Expr) visit(context.expression(1));
                FunctionCallExpr functionCallExpr = new FunctionCallExpr(
                        fnName, Lists.newArrayList(e1, e2, new StringLiteral(" ")), pos);
                return functionCallExpr;
            }
        }

        if (functionName.equals(FunctionSet.DICT_MAPPING)) {
            List<Expr> params = visit(context.expression(), Expr.class);
            return new DictQueryExpr(params);
        }

        FunctionCallExpr functionCallExpr = new FunctionCallExpr(fnName,
                new FunctionParams(false, visit(context.expression(), Expr.class)), pos);
        if (context.over() != null) {
            return buildOverClause(functionCallExpr, context.over(), pos);
        }
        return SyntaxSugars.parse(functionCallExpr);
    }

    @Override
    public ParseNode visitTranslateFunctionCall(StarRocksParser.TranslateFunctionCallContext context) {
        String fullFunctionName = context.TRANSLATE().getText();
        NodePosition pos = createPos(context);

        FunctionName fnName = FunctionName.createFnName(fullFunctionName);
        FunctionCallExpr functionCallExpr = new FunctionCallExpr(fnName,
                new FunctionParams(false, visit(context.expression(), Expr.class)), pos);
        return SyntaxSugars.parse(functionCallExpr);
    }

    @Override
    public ParseNode visitAggregationFunctionCall(StarRocksParser.AggregationFunctionCallContext context) {
        NodePosition pos = createPos(context);
        String functionName;
        boolean isGroupConcat = false;
        boolean isLegacyGroupConcat = false;
        boolean isDistinct = false;
        if (context.aggregationFunction().COUNT() != null) {
            functionName = FunctionSet.COUNT;
        } else if (context.aggregationFunction().AVG() != null) {
            functionName = FunctionSet.AVG;
        } else if (context.aggregationFunction().SUM() != null) {
            functionName = FunctionSet.SUM;
        } else if (context.aggregationFunction().MIN() != null) {
            functionName = FunctionSet.MIN;
        } else if (context.aggregationFunction().ARRAY_AGG() != null) {
            functionName = FunctionSet.ARRAY_AGG;
        } else if (context.aggregationFunction().ARRAY_AGG_DISTINCT() != null) { // alias to ARRAY_AGG
            functionName = FunctionSet.ARRAY_AGG;
            isDistinct = true;
        } else if (context.aggregationFunction().GROUP_CONCAT() != null) {
            functionName = FunctionSet.GROUP_CONCAT;
            isGroupConcat = true;
            isLegacyGroupConcat = SqlModeHelper.check(sqlMode, SqlModeHelper.MODE_GROUP_CONCAT_LEGACY);
        } else {
            functionName = FunctionSet.MAX;
        }
        List<OrderByElement> orderByElements = new ArrayList<>();
        if (context.aggregationFunction().ORDER() != null) {
            orderByElements = visit(context.aggregationFunction().sortItem(), OrderByElement.class);
        }

        List<String> hints = Lists.newArrayList();
        if (context.aggregationFunction().bracketHint() != null) {
            hints = context.aggregationFunction().bracketHint().identifier().stream().map(
                    RuleContext::getText).collect(Collectors.toList());
        }
        if (context.aggregationFunction().setQuantifier() != null) {
            isDistinct = context.aggregationFunction().setQuantifier().DISTINCT() != null;
        }

        if (isDistinct && CollectionUtils.isEmpty(context.aggregationFunction().expression())) {
            throw new ParsingException(PARSER_ERROR_MSG.wrongNumOfArgs(functionName), pos);
        }
        List<Expr> exprs = visit(context.aggregationFunction().expression(), Expr.class);
        if (isGroupConcat && !exprs.isEmpty() && context.aggregationFunction().SEPARATOR() == null) {
            if (isLegacyGroupConcat) {
                if (exprs.size() == 1) {
                    Expr sepExpr;
                    String sep = ", ";
                    sepExpr = new StringLiteral(sep, pos);
                    exprs.add(sepExpr);
                }
            } else {
                Expr sepExpr;
                String sep = ",";
                sepExpr = new StringLiteral(sep, pos);
                exprs.add(sepExpr);
            }
        }
        if (!orderByElements.isEmpty()) {
            int exprSize = exprs.size();
            if (isGroupConcat) { // the last expr of group_concat is the separator
                exprSize--;
            }
            for (OrderByElement orderByElement : orderByElements) {
                Expr by = orderByElement.getExpr();
                if (by instanceof IntLiteral) {
                    long ordinal = ((IntLiteral) by).getLongValue();
                    if (ordinal < 1 || ordinal > exprSize) {
                        throw new ParsingException(format("ORDER BY position %s is not in %s output list", ordinal,
                                functionName), pos);
                    }
                    by = exprs.get((int) ordinal - 1);
                    orderByElement.setExpr(by);
                }
            }
            // remove const order-by items
            orderByElements = orderByElements.stream().filter(x -> !x.getExpr().isConstant()).collect(toList());
        }
        if (CollectionUtils.isNotEmpty(orderByElements)) {
            orderByElements.stream().forEach(e -> exprs.add(e.getExpr()));
        }

        FunctionCallExpr functionCallExpr;
        boolean isStar = context.aggregationFunction().ASTERISK_SYMBOL() != null;
        if (context.filter() == null) {
            functionCallExpr = new FunctionCallExpr(functionName,
                    isStar ? FunctionParams.createStarParam() : new FunctionParams(isDistinct, exprs, orderByElements),
                    pos);

        } else {
            // convert agg filter to agg_if
            boolean isCountFunc = functionName.equalsIgnoreCase(FunctionSet.COUNT);
            if (isCountFunc && isDistinct) {
                throw new ParsingException("Aggregation filter does not support COUNT DISTINCT");
            }
            Expr booleanExpr = (Expr) visit(context.filter().booleanExpression());
            functionName = functionName + FunctionSet.AGG_STATE_IF_SUFFIX;
            exprs.add(booleanExpr);

            if (isCountFunc && isStar) {
                functionCallExpr = new FunctionCallExpr(functionName, new FunctionParams(false, exprs, null, isDistinct, null));
            } else if (functionName.startsWith(FunctionSet.ARRAY_AGG) && isDistinct) {
                functionName = ARRAY_AGG_DISTINCT + FunctionSet.AGG_STATE_IF_SUFFIX;
                functionCallExpr = new FunctionCallExpr(functionName, new FunctionParams(false, exprs, orderByElements), pos);
            } else {
                functionCallExpr = new FunctionCallExpr(functionName,
                        isStar ? FunctionParams.createStarParam() : new FunctionParams(isDistinct, exprs, orderByElements),
                        pos);
            }
        }

        functionCallExpr = SyntaxSugars.parse(functionCallExpr);
        functionCallExpr.setHints(hints);
        if (context.over() != null) {
            return buildOverClause(functionCallExpr, context.over(), pos);
        }
        return functionCallExpr;
    }

    @Override
    public ParseNode visitWindowFunctionCall(StarRocksParser.WindowFunctionCallContext context) {
        FunctionCallExpr functionCallExpr = (FunctionCallExpr) visit(context.windowFunction());
        return buildOverClause(functionCallExpr, context.over(), createPos(context));
    }

    @Override
    public ParseNode visitWindowFunction(StarRocksParser.WindowFunctionContext context) {
        FunctionCallExpr functionCallExpr = new FunctionCallExpr(context.name.getText().toLowerCase(),
                new FunctionParams(false, visit(context.expression(), Expr.class)), createPos(context));
        functionCallExpr = SyntaxSugars.parse(functionCallExpr);
        boolean ignoreNull = CollectionUtils.isNotEmpty(context.ignoreNulls())
                && context.ignoreNulls().stream().anyMatch(Objects::nonNull);
        functionCallExpr.setIgnoreNulls(ignoreNull);
        return functionCallExpr;
    }

    private AnalyticExpr buildOverClause(FunctionCallExpr functionCallExpr, StarRocksParser.OverContext context,
                                         NodePosition pos) {
        functionCallExpr.setIsAnalyticFnCall(true);
        List<OrderByElement> orderByElements = new ArrayList<>();
        if (context.ORDER() != null) {
            orderByElements = visit(context.sortItem(), OrderByElement.class);
        }
        List<Expr> partitionExprs = visit(context.partition, Expr.class);
        return new AnalyticExpr(functionCallExpr, partitionExprs, orderByElements,
                (AnalyticWindow) visitIfPresent(context.windowFrame()),
                context.bracketHint() == null ? null : context.bracketHint().identifier().stream()
                        .map(RuleContext::getText).collect(toList()), pos);
    }

    @Override
    public ParseNode visitExtract(StarRocksParser.ExtractContext context) {
        String fieldString = context.identifier().getText();
        return new FunctionCallExpr(fieldString,
                new FunctionParams(Lists.newArrayList((Expr) visit(context.valueExpression()))), createPos(context));
    }

    @Override
    public ParseNode visitCast(StarRocksParser.CastContext context) {
        return new CastExpr(new TypeDef(getType(context.type())), (Expr) visit(context.expression()),
                createPos(context));
    }

    @Override
    public ParseNode visitConvert(StarRocksParser.ConvertContext context) {
        return new CastExpr(new TypeDef(getType(context.type())), (Expr) visit(context.expression()),
                createPos(context));
    }

    @Override
    public ParseNode visitInformationFunctionExpression(StarRocksParser.InformationFunctionExpressionContext context) {
        return new InformationFunction(context.name.getText().toUpperCase(), createPos(context));
    }

    @Override
    public ParseNode visitSpecialDateTimeExpression(StarRocksParser.SpecialDateTimeExpressionContext context) {
        List<Expr> expr = Lists.newArrayList();
        if (context.INTEGER_VALUE() != null) {
            expr.add(new IntLiteral(Long.parseLong(context.INTEGER_VALUE().getText()), Type.INT));
        }
        return new FunctionCallExpr(context.name.getText().toUpperCase(), new FunctionParams(false, expr));
    }

    @Override
    public ParseNode visitSpecialFunctionExpression(StarRocksParser.SpecialFunctionExpressionContext context) {
        NodePosition pos = createPos(context);
        if (context.CHAR() != null) {
            return new FunctionCallExpr("char", visit(context.expression(), Expr.class), pos);
        } else if (context.DAY() != null) {
            return new FunctionCallExpr("day", visit(context.expression(), Expr.class), pos);
        } else if (context.HOUR() != null) {
            return new FunctionCallExpr("hour", visit(context.expression(), Expr.class), pos);
        } else if (context.IF() != null) {
            return new FunctionCallExpr("if", visit(context.expression(), Expr.class), pos);
        } else if (context.LEFT() != null) {
            return new FunctionCallExpr("left", visit(context.expression(), Expr.class), pos);
        } else if (context.LIKE() != null) {
            return new FunctionCallExpr("like", visit(context.expression(), Expr.class), pos);
        } else if (context.MINUTE() != null) {
            return new FunctionCallExpr("minute", visit(context.expression(), Expr.class), pos);
        } else if (context.MOD() != null) {
            return new FunctionCallExpr("mod", visit(context.expression(), Expr.class), pos);
        } else if (context.MONTH() != null) {
            return new FunctionCallExpr("month", visit(context.expression(), Expr.class), pos);
        } else if (context.QUARTER() != null) {
            return new FunctionCallExpr("quarter", visit(context.expression(), Expr.class), pos);
        } else if (context.REGEXP() != null) {
            return new FunctionCallExpr("regexp", visit(context.expression(), Expr.class), pos);
        } else if (context.REPLACE() != null) {
            return new FunctionCallExpr("replace", visit(context.expression(), Expr.class), pos);
        } else if (context.RIGHT() != null) {
            return new FunctionCallExpr("right", visit(context.expression(), Expr.class), pos);
        } else if (context.RLIKE() != null) {
            return new FunctionCallExpr("regexp", visit(context.expression(), Expr.class), pos);
        } else if (context.SECOND() != null) {
            return new FunctionCallExpr("second", visit(context.expression(), Expr.class), pos);
        } else if (context.YEAR() != null) {
            return new FunctionCallExpr("year", visit(context.expression(), Expr.class), pos);
        } else if (context.PASSWORD() != null) {
            StringLiteral stringLiteral = (StringLiteral) visit(context.string());
            return new StringLiteral(new String(MysqlPassword.makeScrambledPassword(stringLiteral.getValue())), pos);
        } else if (context.FLOOR() != null) {
            return new FunctionCallExpr("floor", visit(context.expression(), Expr.class), pos);
        } else if (context.CEIL() != null) {
            return new FunctionCallExpr("ceil", visit(context.expression(), Expr.class), pos);
        }

        String functionName = context.TIMESTAMPADD() != null ? "TIMESTAMPADD" : "TIMESTAMPDIFF";
        UnitIdentifier e1 = (UnitIdentifier) visit(context.unitIdentifier());
        Expr e2 = (Expr) visit(context.expression(0));
        Expr e3 = (Expr) visit(context.expression(1));

        return new TimestampArithmeticExpr(functionName, e3, e2, e1.getDescription(), pos);

    }

    @Override
    public ParseNode visitConcat(StarRocksParser.ConcatContext context) {
        Expr left = (Expr) visit(context.left);
        Expr right = (Expr) visit(context.right);
        return new FunctionCallExpr("concat", new FunctionParams(Lists.newArrayList(left, right)),
                createPos(context));
    }

    @Override
    public ParseNode visitNullLiteral(StarRocksParser.NullLiteralContext context) {
        return new NullLiteral(createPos(context));
    }

    @Override
    public ParseNode visitBooleanLiteral(StarRocksParser.BooleanLiteralContext context) {
        NodePosition pos = createPos(context);
        String value = context.getText();
        return new BoolLiteral("TRUE".equalsIgnoreCase(value), pos);
    }

    @Override
    public ParseNode visitNumericLiteral(StarRocksParser.NumericLiteralContext context) {
        return visit(context.number());
    }

    @Override
    public ParseNode visitIntegerValue(StarRocksParser.IntegerValueContext context) {
        NodePosition pos = createPos(context);
        try {
            BigInteger intLiteral = new BigInteger(context.getText());
            // Note: val is positive, because we do not recognize minus character in 'IntegerLiteral'
            // -2^63 will be recognized as large int(__int128)
            if (intLiteral.compareTo(LONG_MAX) <= 0) {
                return new IntLiteral(intLiteral.longValue(), pos);
            } else if (intLiteral.compareTo(LARGEINT_MAX_ABS) <= 0) {
                return new LargeIntLiteral(intLiteral.toString(), pos);
            } else if (intLiteral.compareTo(INT256_MAX_ABS) <= 0) {
                return new DecimalLiteral(intLiteral.toString(), pos);
            } else {
                throw new ParsingException(PARSER_ERROR_MSG.numOverflow(context.getText()), pos);
            }
        } catch (NumberFormatException | AnalysisException e) {
            throw new ParsingException(PARSER_ERROR_MSG.invalidNumFormat(context.getText()), pos);
        }
    }

    @Override
    public ParseNode visitDoubleValue(StarRocksParser.DoubleValueContext context) {
        NodePosition pos = createPos(context);
        try {
            if (SqlModeHelper.check(sqlMode, SqlModeHelper.MODE_DOUBLE_LITERAL)) {
                return new FloatLiteral(context.getText(), pos);
            } else {
                BigDecimal decimal = new BigDecimal(context.getText());
                int precision = DecimalLiteral.getRealPrecision(decimal);
                int scale = DecimalLiteral.getRealScale(decimal);
                int integerPartWidth = precision - scale;
                if (integerPartWidth > 38) {
                    return new FloatLiteral(context.getText(), pos);
                }
                return new DecimalLiteral(decimal, pos);
            }

        } catch (AnalysisException | NumberFormatException e) {
            throw new ParsingException(PARSER_ERROR_MSG.invalidNumFormat(context.getText()), pos);
        }
    }

    @Override
    public ParseNode visitDecimalValue(StarRocksParser.DecimalValueContext context) {
        NodePosition pos = createPos(context);
        try {
            if (SqlModeHelper.check(sqlMode, SqlModeHelper.MODE_DOUBLE_LITERAL)) {
                return new FloatLiteral(context.getText(), pos);
            } else {
                return new DecimalLiteral(context.getText(), pos);
            }
        } catch (AnalysisException e) {
            throw new ParsingException(PARSER_ERROR_MSG.invalidNumFormat(context.getText()), pos);
        }
    }

    @Override
    public ParseNode visitDateLiteral(StarRocksParser.DateLiteralContext context) {
        NodePosition pos = createPos(context);
        String value = ((StringLiteral) visit(context.string())).getValue();
        try {
            if (context.DATE() != null) {
                return new DateLiteral(value, Type.DATE);
            } else {
                return new DateLiteral(value, Type.DATETIME);
            }
        } catch (AnalysisException e) {
            throw new ParsingException(PARSER_ERROR_MSG.invalidDateFormat(value), pos);
        }
    }

    @Override
    public ParseNode visitString(StarRocksParser.StringContext context) {
        String quotedString;
        NodePosition pos = createPos(context);
        if (context.SINGLE_QUOTED_TEXT() != null) {
            quotedString = context.SINGLE_QUOTED_TEXT().getText();
            // For support mysql embedded quotation
            // In a single-quoted string, two single-quotes are combined into one single-quote
            quotedString = quotedString.substring(1, quotedString.length() - 1).replace("''", "'");
        } else {
            quotedString = context.DOUBLE_QUOTED_TEXT().getText();
            // For support mysql embedded quotation
            // In a double-quoted string, two double-quotes are combined into one double-quote
            quotedString = quotedString.substring(1, quotedString.length() - 1).replace("\"\"", "\"");
        }
        return new StringLiteral(escapeBackSlash(quotedString), pos);
    }

    @Override
    public ParseNode visitBinary(StarRocksParser.BinaryContext context) {
        String quotedText;
        if (context.BINARY_SINGLE_QUOTED_TEXT() != null) {
            quotedText = context.BINARY_SINGLE_QUOTED_TEXT().getText();
        } else {
            quotedText = context.BINARY_DOUBLE_QUOTED_TEXT().getText();
        }
        return new VarBinaryLiteral(quotedText.substring(2, quotedText.length() - 1), createPos(context));
    }

    private static String escapeBackSlash(String str) {
        StringWriter writer = new StringWriter();
        int strLen = str.length();
        for (int i = 0; i < strLen; ++i) {
            char c = str.charAt(i);
            if (c == '\\' && (i + 1) < strLen) {
                switch (str.charAt(i + 1)) {
                    case 'n':
                        writer.append('\n');
                        break;
                    case 't':
                        writer.append('\t');
                        break;
                    case 'r':
                        writer.append('\r');
                        break;
                    case 'b':
                        writer.append('\b');
                        break;
                    case '0':
                        writer.append('\0'); // Ascii null
                        break;
                    case 'Z': // ^Z must be escaped on Win32
                        writer.append('\032');
                        break;
                    case '_':
                    case '%':
                        writer.append('\\'); // remember prefix for wildcard
                        /* Fall through */
                    default:
                        writer.append(str.charAt(i + 1));
                        break;
                }
                i++;
            } else {
                writer.append(c);
            }
        }

        return writer.toString();
    }

    @Override
    public ParseNode visitArrayConstructor(StarRocksParser.ArrayConstructorContext context) {
        NodePosition pos = createPos(context);
        Type type = null;
        if (context.arrayType() != null) {
            type = new ArrayType(getType(context.arrayType().type()));
        }

        List<Expr> exprs;
        if (context.expressionList() != null) {
            exprs = visit(context.expressionList().expression(), Expr.class);
        } else {
            exprs = Collections.emptyList();
        }
        return new ArrayExpr(type, exprs, pos);
    }

    @Override
    public ParseNode visitMapExpression(StarRocksParser.MapExpressionContext context) {
        ArrayList<Expr> row = Lists.newArrayList();
        Expr key = (Expr) visit(context.key);
        Expr value = (Expr) visit(context.value);
        row.add(key);
        row.add(value);
        return new ValueList(row, createPos(context));
    }

    @Override
    public ParseNode visitMapConstructor(StarRocksParser.MapConstructorContext context) {
        NodePosition pos = createPos(context);
        Type type = Type.ANY_MAP;
        if (context.mapType() != null) {
            type = getMapType(context.mapType());
        }
        List<Expr> exprs;
        if (context.mapExpressionList() != null) {
            List<ValueList> rowValues = visit(context.mapExpressionList().mapExpression(), ValueList.class);
            List<List<Expr>> rows = rowValues.stream().map(ValueList::getRow).collect(toList());
            exprs = rows.stream().flatMap(Collection::stream).collect(Collectors.toList());
            int num = exprs.size();
            if (num % 2 == 1) {
                throw new ParsingException(PARSER_ERROR_MSG.wrongNumOfArgs(num, "map()",
                        "Arguments must be in key/value pairs"), pos);
            }
        } else {
            exprs = Collections.emptyList();
        }
        return new MapExpr(type, exprs, pos);
    }

    @Override
    public ParseNode visitCollectionSubscript(StarRocksParser.CollectionSubscriptContext context) {
        Expr value = (Expr) visit(context.value);
        Expr index = (Expr) visit(context.index);
        return new CollectionElementExpr(value, index, false);
    }

    @Override
    public ParseNode visitArraySlice(StarRocksParser.ArraySliceContext context) {
        throw new ParsingException(PARSER_ERROR_MSG.unsupportedExpr("array slice"), createPos(context));
        // TODO: support array slice in BE
        /*
        Expr expr = (Expr) visit(context.primaryExpression());

        IntLiteral lowerBound;
        if (context.start != null) {
            lowerBound = new IntLiteral(Long.parseLong(context.start.getText()));
        } else {
            lowerBound = new IntLiteral(0);
        }
        IntLiteral upperBound;
        if (context.end != null) {
            upperBound = new IntLiteral(Long.parseLong(context.end.getText()));
        } else {
            upperBound = new IntLiteral(-1);
        }

        return new ArraySliceExpr(expr, lowerBound, upperBound);
         */
    }

    @Override
    public ParseNode visitTaskInterval(StarRocksParser.TaskIntervalContext context) {
        return new IntervalLiteral((Expr) visit(context.value), (UnitIdentifier) visit(context.from),
                createPos(context));
    }

    @Override
    public ParseNode visitInterval(StarRocksParser.IntervalContext context) {
        return new IntervalLiteral((Expr) visit(context.value), (UnitIdentifier) visit(context.from),
                createPos(context));
    }

    @Override
    public ParseNode visitTaskUnitIdentifier(StarRocksParser.TaskUnitIdentifierContext context) {
        return new UnitIdentifier(context.getText(), createPos(context));
    }

    @Override
    public ParseNode visitUnitIdentifier(StarRocksParser.UnitIdentifierContext context) {
        return new UnitIdentifier(context.getText(), createPos(context));
    }

    @Override
    public ParseNode visitUnitBoundary(StarRocksParser.UnitBoundaryContext context) {
        return new UnitBoundary(context.getText(), createPos(context));
    }

    @Override
    public ParseNode visitDereference(StarRocksParser.DereferenceContext ctx) {
        Expr base = (Expr) visit(ctx.base);
        NodePosition pos = createPos(ctx);

        String fieldName;
        if (ctx.DOT_IDENTIFIER() != null) {
            fieldName = ctx.DOT_IDENTIFIER().getText().substring(1);
        } else {
            fieldName = ((Identifier) visit(ctx.fieldName)).getValue();
        }

        // Trick method
        // If left is SlotRef type, we merge fieldName to SlotRef
        // The reason do this is to maintain compatibility with the original SlotRef
        if (base instanceof SlotRef) {
            // do merge
            SlotRef tmp = (SlotRef) base;
            List<String> parts = new ArrayList<>(tmp.getQualifiedName().getParts());
            parts.add(fieldName);
            return new SlotRef(QualifiedName.of(parts, pos));
        } else if (base instanceof SubfieldExpr) {
            // Merge multi-level subfield access
            SubfieldExpr subfieldExpr = (SubfieldExpr) base;
            ImmutableList.Builder<String> builder = new ImmutableList.Builder<>();
            for (String tmpFieldName : subfieldExpr.getFieldNames()) {
                builder.add(tmpFieldName);
            }
            builder.add(fieldName);
            return new SubfieldExpr(subfieldExpr.getChild(0), builder.build(), pos);
        } else {
            // If left is not a SlotRef, we can assume left node must be an StructType,
            // and fieldName must be StructType's subfield name.
            return new SubfieldExpr(base, ImmutableList.of(fieldName), pos);
        }
    }

    @Override
    public ParseNode visitColumnReference(StarRocksParser.ColumnReferenceContext context) {
        Identifier identifier = (Identifier) visit(context.identifier());
        List<String> parts = new ArrayList<>();
        parts.add(identifier.getValue());
        QualifiedName qualifiedName = QualifiedName.of(parts, createPos(context));
        SlotRef slotRef = new SlotRef(qualifiedName);
        if (identifier.isBackQuoted()) {
            slotRef.setBackQuoted(true);
        }
        return slotRef;
    }

    @Override
    public ParseNode visitArrowExpression(StarRocksParser.ArrowExpressionContext context) {
        Expr expr = (Expr) visit(context.primaryExpression());
        StringLiteral stringLiteral = (StringLiteral) visit(context.string());

        return new ArrowExpr(expr, stringLiteral, createPos(context));
    }

    @Override
    public ParseNode visitLambdaFunctionExpr(StarRocksParser.LambdaFunctionExprContext context) {
        List<String> names = Lists.newLinkedList();
        if (context.identifierList() != null) {
            final List<Identifier> identifierList = visit(context.identifierList().identifier(), Identifier.class);
            names = identifierList.stream().map(Identifier::getValue).collect(toList());
        } else {
            names.add(((Identifier) visit(context.identifier())).getValue());
        }
        List<Expr> arguments = Lists.newLinkedList();
        Expr expr = null;
        if (context.expression() != null) {
            expr = (Expr) visit(context.expression());
        } else if (context.expressionList() != null) {
            List<Expr> exprs = visit(context.expressionList().expression(), Expr.class);
            if (exprs.size() != 2) {
                throw new IllegalArgumentException("The right part of map lambda functions can accept at most 2 " +
                        "expressions, but there are " + exprs.size());
            }
            expr = new MapExpr(Type.ANY_MAP, exprs); // key expr, value expr.
        }
        arguments.add(expr); // put lambda body to the first argument
        for (int i = 0; i < names.size(); ++i) {
            arguments.add(new LambdaArgument(names.get(i)));
        }
        return new LambdaFunctionExpr(arguments);
    }

    @Override
    public ParseNode visitMatchExpr(StarRocksParser.MatchExprContext context) {
        NodePosition pos = createPos(context);
        MatchExpr matchExpr = new MatchExpr((Expr) visit(context.left), (Expr) visit(context.right), pos);
        if (context.NOT() != null) {
            return new CompoundPredicate(CompoundPredicate.Operator.NOT, matchExpr, null, pos);
        } else {
            return matchExpr;
        }
    }

    @Override
    public ParseNode visitUserVariable(StarRocksParser.UserVariableContext context) {
        String variable = ((Identifier) visit(context.identifierOrString())).getValue();
        return new UserVariableExpr(variable, createPos(context));
    }

    @Override
    public ParseNode visitSystemVariable(StarRocksParser.SystemVariableContext context) {
        SetType setType = getVariableType(context.varType());
        return new VariableExpr(((Identifier) visit(context.identifier())).getValue(), setType, createPos(context));
    }

    @Override
    public ParseNode visitCollate(StarRocksParser.CollateContext context) {
        return visit(context.primaryExpression());
    }

    @Override
    public ParseNode visitParenthesizedExpression(StarRocksParser.ParenthesizedExpressionContext context) {
        return visit(context.expression());
    }

    @Override
    public ParseNode visitUnquotedIdentifier(StarRocksParser.UnquotedIdentifierContext context) {
        return new Identifier(context.getText(), createPos(context));
    }

    @Override
    public ParseNode visitBackQuotedIdentifier(StarRocksParser.BackQuotedIdentifierContext context) {
        Identifier backQuotedIdentifier = new Identifier(context.getText().replace("`", ""), createPos(context));
        backQuotedIdentifier.setBackQuoted(true);
        return backQuotedIdentifier;
    }

    @Override
    public ParseNode visitDigitIdentifier(StarRocksParser.DigitIdentifierContext context) {
        return new Identifier(context.getText(), createPos(context));
    }

    @Override
    public ParseNode visitDictionaryGetExpr(StarRocksParser.DictionaryGetExprContext context) {
        List<Expr> params = visit(context.expressionList().expression(), Expr.class);
        return new DictionaryGetExpr(params);
    }

    @Override
    public ParseNode visitPivotClause(StarRocksParser.PivotClauseContext ctx) {
        List<PivotAggregation> aggregations = visit(ctx.pivotAggregationExpression(), PivotAggregation.class);
        List<Identifier> identifiers;
        if (ctx.identifierList() != null) {
            identifiers = visit(ctx.identifierList().identifier(), Identifier.class);
        } else if (ctx.identifier() != null) {
            identifiers = ImmutableList.of((Identifier) visit(ctx.identifier()));
        } else {
            identifiers = ImmutableList.of();
        }
        List<SlotRef> columns = identifiers.stream()
                .map(id -> {
                    List<String> parts = ImmutableList.of(id.getValue());
                    return new SlotRef(QualifiedName.of(parts, createPos(ctx)));
                })
                .collect(toList());

        List<PivotValue> values = visit(ctx.pivotValue(), PivotValue.class);

        if (columns.size() != values.get(0).getExprs().size()
                || values.stream().anyMatch(v -> v.getExprs().size() != columns.size())) {
            throw new ParsingException(
                    PARSER_ERROR_MSG.pivotValueArityMismatch(columns.size(), values.get(0).getExprs().size()),
                    createPos(ctx));
        }
        return new PivotRelation(null, aggregations, columns, values, createPos(ctx));
    }

    @Override
    public ParseNode visitPivotAggregationExpression(StarRocksParser.PivotAggregationExpressionContext ctx) {
        String alias = null;
        if (ctx.identifier() != null) {
            alias = ((Identifier) visit(ctx.identifier())).getValue();
        } else if (ctx.string() != null) {
            alias = ((StringLiteral) visit(ctx.string())).getStringValue();
        }
        FunctionCallExpr functionCallExpr = (FunctionCallExpr) visit(ctx.functionCall());
        return new PivotAggregation(functionCallExpr, alias, createPos(ctx));
    }

    @Override
    public ParseNode visitPivotValue(StarRocksParser.PivotValueContext ctx) {
        ImmutableList.Builder<LiteralExpr> exprs = new ImmutableList.Builder<>();
        if (ctx.literalExpression() != null) {
            exprs.add((LiteralExpr) visit(ctx.literalExpression()));
        } else if (ctx.literalExpressionList() != null) {
            exprs.addAll(visit(ctx.literalExpressionList().literalExpression(), LiteralExpr.class));
        }

        String alias = null;
        if (ctx.identifier() != null) {
            alias = ((Identifier) visit(ctx.identifier())).getValue();
        } else if (ctx.string() != null) {
            alias = ((StringLiteral) visit(ctx.string())).getStringValue();
        }
        return new PivotValue(exprs.build(), alias, createPos(ctx));
    }

    // ------------------------------------------- COMMON AST --------------------------------------------------------------

    private static StatementBase.ExplainLevel getExplainType(StarRocksParser.ExplainDescContext context) {
        StatementBase.ExplainLevel explainLevel = StatementBase.ExplainLevel.NORMAL;
        if (context.LOGICAL() != null) {
            explainLevel = StatementBase.ExplainLevel.LOGICAL;
        } else if (context.ANALYZE() != null) {
            explainLevel = StatementBase.ExplainLevel.ANALYZE;
        } else if (context.VERBOSE() != null) {
            explainLevel = StatementBase.ExplainLevel.VERBOSE;
        } else if (context.COSTS() != null) {
            explainLevel = StatementBase.ExplainLevel.COSTS;
        } else if (context.SCHEDULER() != null) {
            explainLevel = StatementBase.ExplainLevel.SCHEDULER;
        }
        return explainLevel;
    }

    public static SetType getVariableType(StarRocksParser.VarTypeContext context) {
        if (context == null) {
            // this means select @@max_allowed_packet
            return null;
        }

        if (context.GLOBAL() != null) {
            return SetType.GLOBAL;
        } else if (context.VERBOSE() != null) {
            return SetType.VERBOSE;
        } else {
            return SetType.SESSION;
        }
    }

    @Override
    public ParseNode visitAssignment(StarRocksParser.AssignmentContext context) {
        String column = ((Identifier) visit(context.identifier())).getValue();
        Expr expr = (Expr) visit(context.expressionOrDefault());
        return new ColumnAssignment(column, expr, createPos(context));
    }

    @Override
    public ParseNode visitPartitionDesc(StarRocksParser.PartitionDescContext context) {
        List<PartitionDesc> partitionDescList = new ArrayList<>();
        StarRocksParser.IdentifierListContext identifierListContext = context.identifierList();
        if (context.functionCall() != null) {
            FunctionCallExpr functionCallExpr = (FunctionCallExpr) visit(context.functionCall());
            String functionName = functionCallExpr.getFnName().getFunction();
            // except date_trunc, time_slice, str_to_date use generated column as partition column
            if (!FunctionSet.DATE_TRUNC.equals(functionName) && !FunctionSet.TIME_SLICE.equals(functionName)
                    && !FunctionSet.STR2DATE.equals(functionName)) {
                return generateMulitListPartitionDesc(context, Lists.newArrayList(functionCallExpr));
            }
            for (StarRocksParser.RangePartitionDescContext rangePartitionDescContext : context.rangePartitionDesc()) {
                final PartitionDesc rangePartitionDesc = (PartitionDesc) visit(rangePartitionDescContext);
                partitionDescList.add(rangePartitionDesc);
            }
            List<String> columnList = AnalyzerUtils.checkAndExtractPartitionCol(functionCallExpr, null);
            RangePartitionDesc rangePartitionDesc = new RangePartitionDesc(columnList, partitionDescList);
            return new ExpressionPartitionDesc(rangePartitionDesc, functionCallExpr);
        }
        if (identifierListContext == null) {
            if (context.partitionExpr() != null) {
                List<ParseNode> multiDescList = Lists.newArrayList();
                for (StarRocksParser.PartitionExprContext partitionExpr : context.partitionExpr()) {
                    if (partitionExpr.identifier() != null) {
                        Identifier identifier = (Identifier) visit(partitionExpr.identifier());
                        multiDescList.add(identifier);
                    } else if (partitionExpr.functionCall() != null) {
                        FunctionCallExpr expr = (FunctionCallExpr) visit(partitionExpr.functionCall());
                        multiDescList.add(expr);
                    } else {
                        throw new ParsingException("Partition column list is empty", createPos(context));
                    }
                }
                return generateMulitListPartitionDesc(context, multiDescList);
            }
        }
        List<Identifier> identifierList = visit(identifierListContext.identifier(), Identifier.class);

        if (context.LIST() == null && context.RANGE() == null) {
            List<String> columnList = identifierList.stream().map(Identifier::getValue).collect(toList());
            return new ListPartitionDesc(columnList, new ArrayList<>());
        } else {
            List<PartitionDesc> partitionDesc = visit(context.rangePartitionDesc(), PartitionDesc.class);
            return new RangePartitionDesc(
                    identifierList.stream().map(Identifier::getValue).collect(toList()),
                    partitionDesc,
                    createPos(context));
        }
    }

    @Override
    public ParseNode visitSingleRangePartition(StarRocksParser.SingleRangePartitionContext context) {
        PartitionKeyDesc partitionKeyDesc = (PartitionKeyDesc) visit(context.partitionKeyDesc());
        boolean ifNotExists = context.IF() != null;
        Map<String, String> properties = null;
        if (context.propertyList() != null) {
            properties = new HashMap<>();
            List<Property> propertyList = visit(context.propertyList().property(), Property.class);
            for (Property property : propertyList) {
                properties.put(property.getKey(), property.getValue());
            }
        }
        return new SingleRangePartitionDesc(ifNotExists, ((Identifier) visit(context.identifier())).getValue(),
                partitionKeyDesc, properties, createPos(context));
    }

    @Override
    public ParseNode visitMultiRangePartition(StarRocksParser.MultiRangePartitionContext context) {
        NodePosition pos = createPos(context);
        if (context.interval() != null) {
            IntervalLiteral intervalLiteral = (IntervalLiteral) visit(context.interval());
            Expr expr = intervalLiteral.getValue();
            long intervalVal;
            if (expr instanceof IntLiteral) {
                intervalVal = ((IntLiteral) expr).getLongValue();
            } else {
                throw new ParsingException(PARSER_ERROR_MSG.unsupportedExprWithInfo(expr.toSql(),
                        "RANGE DESC"), expr.getPos());
            }
            return new MultiRangePartitionDesc(
                    ((StringLiteral) visit(context.string(0))).getStringValue(),
                    ((StringLiteral) visit(context.string(1))).getStringValue(),
                    intervalVal,
                    intervalLiteral.getUnitIdentifier().getDescription(),
                    pos);
        } else {
            return new MultiRangePartitionDesc(
                    ((StringLiteral) visit(context.string(0))).getStringValue(),
                    ((StringLiteral) visit(context.string(1))).getStringValue(),
                    Long.parseLong(context.INTEGER_VALUE().getText()),
                    null,
                    pos);
        }
    }

    @Override
    public ParseNode visitPartitionRangeDesc(StarRocksParser.PartitionRangeDescContext context) {
        return new PartitionRangeDesc(
                ((StringLiteral) visit(context.string(0))).getStringValue(),
                ((StringLiteral) visit(context.string(1))).getStringValue(),
                createPos(context));
    }

    public List<String> parseSingleListPartitionValues(StarRocksParser.SingleListPartitionValuesContext valueListContext) {
        return valueListContext.listPartitionValue().stream().map(x -> {
            if (x.NULL() != null) {
                return PartitionValue.STARROCKS_DEFAULT_PARTITION_VALUE;
            } else {
                return ((StringLiteral) visit(x.string())).getStringValue();
            }
        }).collect(toList());
    }

    @Override
    public ParseNode visitSingleItemListPartitionDesc(StarRocksParser.SingleItemListPartitionDescContext context) {
        List<String> values = parseSingleListPartitionValues(context.singleListPartitionValues());
        boolean ifNotExists = context.IF() != null;
        Map<String, String> properties = null;
        if (context.propertyList() != null) {
            properties = new HashMap<>();
            List<Property> propertyList = visit(context.propertyList().property(), Property.class);
            for (Property property : propertyList) {
                properties.put(property.getKey(), property.getValue());
            }
        }
        return new SingleItemListPartitionDesc(ifNotExists, ((Identifier) visit(context.identifier())).getValue(),
                values, properties, createPos(context));
    }

    private List<List<String>> parseMultiListPartitionValues(StarRocksParser.MultiListPartitionValuesContext context) {
        return context.singleListPartitionValues().stream()
                .map(this::parseSingleListPartitionValues)
                .collect(toList());
    }

    @Override
    public ParseNode visitMultiItemListPartitionDesc(StarRocksParser.MultiItemListPartitionDescContext context) {
        boolean ifNotExists = context.IF() != null;
        List<List<String>> multiValues = parseMultiListPartitionValues(context.multiListPartitionValues());
        Map<String, String> properties = null;
        if (context.propertyList() != null) {
            properties = new HashMap<>();
            List<Property> propertyList = visit(context.propertyList().property(), Property.class);
            for (Property property : propertyList) {
                properties.put(property.getKey(), property.getValue());
            }
        }
        return new MultiItemListPartitionDesc(ifNotExists, ((Identifier) visit(context.identifier())).getValue(),
                multiValues, properties, createPos(context));
    }

    @Override
    public ParseNode visitPartitionKeyDesc(StarRocksParser.PartitionKeyDescContext context) {
        PartitionKeyDesc partitionKeyDesc;
        NodePosition pos = createPos(context);
        if (context.LESS() != null) {
            if (context.MAXVALUE() != null) {
                return PartitionKeyDesc.createMaxKeyDesc();
            }
            List<PartitionValue> partitionValueList =
                    visit(context.partitionValueList().get(0).partitionValue(), PartitionValue.class);
            partitionKeyDesc = new PartitionKeyDesc(partitionValueList, pos);
        } else {
            List<PartitionValue> lowerPartitionValueList =
                    visit(context.partitionValueList().get(0).partitionValue(), PartitionValue.class);
            List<PartitionValue> upperPartitionValueList =
                    visit(context.partitionValueList().get(1).partitionValue(), PartitionValue.class);
            partitionKeyDesc = new PartitionKeyDesc(lowerPartitionValueList, upperPartitionValueList, pos);
        }
        return partitionKeyDesc;
    }

    @Override
    public ParseNode visitPartitionValue(StarRocksParser.PartitionValueContext context) {
        NodePosition pos = createPos(context);
        if (context.MAXVALUE() != null) {
            return PartitionValue.MAX_VALUE;
        } else {
            return new PartitionValue(((StringLiteral) visit(context.string())).getStringValue(), pos);
        }
    }

    @Override
    public ParseNode visitDistributionDesc(StarRocksParser.DistributionDescContext context) {
        // default buckets number
        int buckets = 0;
        NodePosition pos = createPos(context);

        if (context.INTEGER_VALUE() != null) {
            buckets = Integer.parseInt(context.INTEGER_VALUE().getText());
        }
        if (context.HASH() != null) {
            List<Identifier> identifierList = visit(context.identifierList().identifier(), Identifier.class);
            return new HashDistributionDesc(buckets,
                    identifierList.stream().map(Identifier::getValue).collect(toList()),
                    pos);
        } else {
            return new RandomDistributionDesc(buckets, pos);
        }
    }

    @Override
    public ParseNode visitRefreshSchemeDesc(StarRocksParser.RefreshSchemeDescContext context) {
        LocalDateTime startTime = LocalDateTime.now();
        IntervalLiteral intervalLiteral = null;
        NodePosition pos = createPos(context);
        MaterializedView.RefreshMoment refreshMoment =
                Config.default_mv_refresh_immediate ?
                        MaterializedView.RefreshMoment.IMMEDIATE : MaterializedView.RefreshMoment.DEFERRED;
        if (context.DEFERRED() != null) {
            refreshMoment = MaterializedView.RefreshMoment.DEFERRED;
        } else if (context.IMMEDIATE() != null) {
            refreshMoment = MaterializedView.RefreshMoment.IMMEDIATE;
        }
        if (context.ASYNC() != null) {
            boolean defineStartTime = false;
            if (context.START() != null) {
                NodePosition timePos = createPos(context.string());
                StringLiteral stringLiteral = (StringLiteral) visit(context.string());
                DateTimeFormatter dateTimeFormatter = null;
                try {
                    dateTimeFormatter = DateUtils.probeFormat(stringLiteral.getStringValue());
                    LocalDateTime tempStartTime = DateUtils.
                            parseStringWithDefaultHSM(stringLiteral.getStringValue(), dateTimeFormatter);
                    startTime = tempStartTime;
                    defineStartTime = true;
                } catch (SemanticException e) {
                    throw new ParsingException(PARSER_ERROR_MSG.invalidDateFormat(stringLiteral.getStringValue()),
                            timePos);
                }
            }

            if (context.interval() != null) {
                intervalLiteral = (IntervalLiteral) visit(context.interval());
                if (!(intervalLiteral.getValue() instanceof IntLiteral)) {
                    String exprSql = intervalLiteral.getValue().toSql();
                    throw new ParsingException(PARSER_ERROR_MSG.unsupportedExprWithInfo(exprSql, "INTERVAL"),
                            createPos(context.interval()));
                }
            }
            return new AsyncRefreshSchemeDesc(defineStartTime, startTime, intervalLiteral, refreshMoment, pos);
        } else if (context.MANUAL() != null) {
            return new ManualRefreshSchemeDesc(refreshMoment, pos);
        } else if (context.INCREMENTAL() != null) {
            return new IncrementalRefreshSchemeDesc(refreshMoment, pos);
        }
        return null;
    }

    @Override
    public ParseNode visitProperty(StarRocksParser.PropertyContext context) {
        return new Property(
                ((StringLiteral) visit(context.key)).getStringValue().trim(),
                ((StringLiteral) visit(context.value)).getStringValue(),
                createPos(context));
    }

    @Override
    public ParseNode visitInlineProperty(StarRocksParser.InlinePropertyContext context) {
        return new Property(
                ((Identifier) visit(context.key)).getValue(),
                ((StringLiteral) visit(context.value)).getStringValue(),
                createPos(context));
    }

    @Override
    public ParseNode visitOutfile(StarRocksParser.OutfileContext context) {
        Map<String, String> properties = new HashMap<>();
        if (context.properties() != null) {
            List<Property> propertyList = visit(context.properties().property(), Property.class);
            for (Property property : propertyList) {
                properties.put(property.getKey(), property.getValue());
            }
        }

        String format = null;
        if (context.fileFormat() != null) {
            if (context.fileFormat().identifier() != null) {
                format = ((Identifier) visit(context.fileFormat().identifier())).getValue();
            } else if (context.fileFormat().string() != null) {
                format = ((StringLiteral) visit(context.fileFormat().string())).getStringValue();
            }
        }

        return new OutFileClause(
                ((StringLiteral) visit(context.file)).getStringValue(),
                format,
                properties, createPos(context));
    }

    @Override
    public ParseNode visitColumnNameWithComment(StarRocksParser.ColumnNameWithCommentContext context) {
        String comment = null;
        if (context.comment() != null) {
            comment = ((StringLiteral) visit(context.comment())).getStringValue();
        }

        return new ColWithComment(((Identifier) visit(context.identifier())).getValue(), comment,
                createPos(context));
    }

    @Override
    public ParseNode visitIdentifierOrStringOrStar(StarRocksParser.IdentifierOrStringOrStarContext context) {
        String s = null;
        if (context.identifier() != null) {
            return visit(context.identifier());
        } else if (context.string() != null) {
            s = ((StringLiteral) visit(context.string())).getStringValue();
        } else if (context.ASTERISK_SYMBOL() != null) {
            s = "*";
        }
        return new Identifier(s, createPos(context));
    }

    @Override
    public ParseNode visitIdentifierOrString(StarRocksParser.IdentifierOrStringContext context) {
        String s = null;
        if (context.identifier() != null) {
            return visit(context.identifier());
        } else if (context.string() != null) {
            s = ((StringLiteral) visit(context.string())).getStringValue();
        }

        return new Identifier(s, createPos(context));
    }

    @Override
    public ParseNode visitUserWithHostAndBlanket(StarRocksParser.UserWithHostAndBlanketContext context) {
        Identifier user = (Identifier) visit(context.identifierOrString(0));
        Identifier host = (Identifier) visit(context.identifierOrString(1));
        return new UserIdentity(user.getValue(), host.getValue(), true, false, createPos(context));
    }

    @Override
    public ParseNode visitUserWithHost(StarRocksParser.UserWithHostContext context) {
        Identifier user = (Identifier) visit(context.identifierOrString(0));
        Identifier host = (Identifier) visit(context.identifierOrString(1));
        return new UserIdentity(user.getValue(), host.getValue(), false, false, createPos(context));
    }

    @Override
    public ParseNode visitUserWithoutHost(StarRocksParser.UserWithoutHostContext context) {
        Identifier user = (Identifier) visit(context.identifierOrString());
        return new UserIdentity(user.getValue(), "%", false, false, createPos(context));
    }

    @Override
    public ParseNode visitPrepareStatement(StarRocksParser.PrepareStatementContext context) {
        String stmtName = context.identifier().getText();
        StatementBase statement = null;
        if (context.prepareSql().statement() != null) {
            statement = (StatementBase) visitStatement(context.prepareSql().statement());
            return new PrepareStmt(stmtName, statement, parameters);
        } else if (context.prepareSql().SINGLE_QUOTED_TEXT() != null) {
            String sql = context.prepareSql().SINGLE_QUOTED_TEXT().getText();
            statement = SqlParser.parseSingleStatement(sql.substring(1, sql.length() - 1), sqlMode);
            if (null != statement && statement instanceof PrepareStmt) {
                PrepareStmt prepareStmt = (PrepareStmt) statement;
                return new PrepareStmt(stmtName, prepareStmt.getInnerStmt(), prepareStmt.getParameters());
            } else {
                // prepare stm1 from select * from t1, no parameters
                return new PrepareStmt(stmtName, statement, ImmutableList.of());
            }
        }

        throw new ParsingException("error prepare sql");
    }

    @Override
    public ParseNode visitCreateBaselinePlanStatement(StarRocksParser.CreateBaselinePlanStatementContext ctx) {
        boolean isGlobal = ctx.GLOBAL() != null;
        QueryRelation bindStmt;
        QueryRelation planStmt;
        if (ctx.queryRelation().size() == 1) {
            bindStmt = null;
            planStmt = (QueryRelation) visitQueryRelation(ctx.queryRelation(0));
        } else if (ctx.queryRelation().size() == 2) {
            bindStmt = (QueryRelation) visitQueryRelation(ctx.queryRelation(0));
            planStmt = (QueryRelation) visitQueryRelation(ctx.queryRelation(1));
        } else {
            throw new ParsingException("Invalid number of statement arguments");
        }

        return new CreateBaselinePlanStmt(isGlobal, bindStmt, planStmt, createPos(ctx));
    }

    @Override
    public ParseNode visitDropBaselinePlanStatement(StarRocksParser.DropBaselinePlanStatementContext ctx) {
        if (ctx.INTEGER_VALUE() == null) {
            throw new ParsingException("Invalid number of statement arguments");
        }
        List<Long> ids = ctx.INTEGER_VALUE().stream()
                .map(ParseTree::getText)
                .map(Long::parseLong).toList();

        return new DropBaselinePlanStmt(ids, createPos(ctx));
    }

    @Override
    public ParseNode visitShowBaselinePlanStatement(StarRocksParser.ShowBaselinePlanStatementContext ctx) {
        Expr where = null;
        if (ctx.WHERE() != null) {
            where = (Expr) visit(ctx.expression());
            return new ShowBaselinePlanStmt(createPos(ctx), where);
        }
        if (ctx.ON() != null) {
            QueryRelation queryRelation = (QueryRelation) visit(ctx.queryRelation());
            return new ShowBaselinePlanStmt(createPos(ctx), queryRelation);
        }
        return new ShowBaselinePlanStmt(createPos(ctx), where);
    }

    @Override
    public ParseNode visitDisableBaselinePlanStatement(StarRocksParser.DisableBaselinePlanStatementContext ctx) {
        if (ctx.INTEGER_VALUE() == null) {
            throw new ParsingException("Invalid number of statement arguments");
        }
        List<Long> ids = ctx.INTEGER_VALUE().stream()
                .map(ParseTree::getText)
                .map(Long::parseLong).toList();
        return new ControlBaselinePlanStmt(false, ids, createPos(ctx));
    }

    @Override
    public ParseNode visitEnableBaselinePlanStatement(StarRocksParser.EnableBaselinePlanStatementContext ctx) {
        if (ctx.INTEGER_VALUE() == null) {
            throw new ParsingException("Invalid number of statement arguments");
        }
        List<Long> ids = ctx.INTEGER_VALUE().stream()
                .map(ParseTree::getText)
                .map(Long::parseLong).toList();
        return new ControlBaselinePlanStmt(true, ids, createPos(ctx));
    }

    @Override
    public ParseNode visitDeallocateStatement(StarRocksParser.DeallocateStatementContext ctx) {
        return new DeallocateStmt(ctx.identifier().getText());
    }

    @Override
    public ParseNode visitExecuteStatement(StarRocksParser.ExecuteStatementContext context) {
        String stmtName = context.identifier().getText();
        List<StarRocksParser.IdentifierOrStringContext> queryStatementContext = context.identifierOrString();
        List<Expr> variableExprs = new ArrayList<>();
        if (context.identifierOrString() != null) {
            queryStatementContext.forEach(varNameContext -> {
                Identifier identifier = (Identifier) visit(varNameContext);
                variableExprs.add(new UserVariableExpr(identifier.getValue(), identifier.getPos()));
            });
        }
        return new ExecuteStmt(stmtName, variableExprs);
    }

    @Override
    public ParseNode visitParameter(StarRocksParser.ParameterContext ctx) {
        if (parameters == null) {
            parameters = new ArrayList<>();
        }
        Parameter parameter = new Parameter(placeHolderSlotId++);
        parameters.add(parameter);
        return parameter;
    }

    @Override
    public ParseNode visitDecommissionDiskClause(StarRocksParser.DecommissionDiskClauseContext context) {
        throw new SemanticException("not support");
    }

    @Override
    public ParseNode visitCancelDecommissionDiskClause(StarRocksParser.CancelDecommissionDiskClauseContext context) {
        throw new SemanticException("not support");
    }

    @Override
    public ParseNode visitDisableDiskClause(StarRocksParser.DisableDiskClauseContext context) {
        throw new SemanticException("not support");
    }

    @Override
    public ParseNode visitCancelDisableDiskClause(StarRocksParser.CancelDisableDiskClauseContext context) {
        throw new SemanticException("not support");
    }

    // ------------------------------------------- Util Functions -------------------------------------------

    protected <T> List<T> visit(List<? extends ParserRuleContext> contexts, Class<T> clazz) {
        return contexts.stream()
                .map(this::visit)
                .map(clazz::cast)
                .collect(toList());
    }

    private <T> List<T> visitIfPresent(List<? extends ParserRuleContext> contexts, Class<T> clazz) {
        if (contexts != null && contexts.size() != 0) {
            return contexts.stream()
                    .map(this::visit)
                    .map(clazz::cast)
                    .collect(toList());
        } else {
            return null;
        }
    }

    private ParseNode visitIfPresent(ParserRuleContext context) {
        if (context != null) {
            return visit(context);
        } else {
            return null;
        }
    }

    private FunctionArgsDef getFunctionArgsDef(StarRocksParser.TypeListContext typeList) {
        List<TypeDef> typeDefList = new ArrayList<>();
        for (StarRocksParser.TypeContext typeContext : typeList.type()) {
            typeDefList.add(new TypeDef(getType(typeContext)));
        }
        boolean isVariadic = typeList.DOTDOTDOT() != null;
        return new FunctionArgsDef(typeDefList, isVariadic);
    }

    private String getIdentifierName(StarRocksParser.IdentifierContext context) {
        return ((Identifier) visit(context)).getValue();
    }

    private TableName getTableName(StarRocksParser.QualifiedNameContext context) {
        return qualifiedNameToTableName(getQualifiedName(context));
    }

    private QualifiedName getQualifiedName(StarRocksParser.QualifiedNameContext context) {
        List<String> parts = new ArrayList<>();
        NodePosition pos = createPos(context);
        for (ParseTree c : context.children) {
            if (c instanceof TerminalNode) {
                TerminalNode t = (TerminalNode) c;
                if (t.getSymbol().getType() == StarRocksParser.DOT_IDENTIFIER) {
                    parts.add(t.getText().substring(1));
                }
            } else if (c instanceof StarRocksParser.IdentifierContext) {
                StarRocksParser.IdentifierContext identifierContext = (StarRocksParser.IdentifierContext) c;
                Identifier identifier = (Identifier) visit(identifierContext);
                parts.add(identifier.getValue());
            }
        }

        return QualifiedName.of(parts, pos);
    }

    private List<String> getFieldName(StarRocksParser.NestedFieldNameContext context) {
        List<String> parts = new ArrayList<>();
        for (ParseTree c : context.children) {
            if (c instanceof StarRocksParser.SubfieldNameContext) {
                StarRocksParser.SubfieldNameContext subfieldNameContext = (StarRocksParser.SubfieldNameContext) c;
                if (subfieldNameContext.ARRAY_ELEMENT() != null) {
                    TerminalNode t = subfieldNameContext.ARRAY_ELEMENT();
                    parts.add(t.getText());
                } else {
                    StarRocksParser.IdentifierContext identifierContext = subfieldNameContext.identifier();
                    Identifier identifier = (Identifier) visit(identifierContext);
                    parts.add(identifier.getValue());
                }
            }
        }
        return parts;
    }

    private TaskName qualifiedNameToTaskName(QualifiedName qualifiedName) {
        // Hierarchy: database.table
        List<String> parts = qualifiedName.getParts();
        if (parts.size() == 2) {
            return new TaskName(parts.get(0), parts.get(1), qualifiedName.getPos());
        } else if (parts.size() == 1) {
            return new TaskName(null, parts.get(0), qualifiedName.getPos());
        } else {
            throw new ParsingException(PARSER_ERROR_MSG.invalidTaskFormat(qualifiedName.toString()),
                    qualifiedName.getPos());
        }
    }

    private TableName qualifiedNameToTableName(QualifiedName qualifiedName) {
        // Hierarchy: catalog.database.table
        List<String> parts = qualifiedName.getParts();
        if (parts.size() == 3) {
            return new TableName(parts.get(0), parts.get(1), parts.get(2), qualifiedName.getPos());
        } else if (parts.size() == 2) {
            return new TableName(null, qualifiedName.getParts().get(0), qualifiedName.getParts().get(1),
                    qualifiedName.getPos());
        } else if (parts.size() == 1) {
            return new TableName(null, null, qualifiedName.getParts().get(0), qualifiedName.getPos());
        } else {
            throw new ParsingException(PARSER_ERROR_MSG.invalidTableFormat(qualifiedName.toString()));
        }
    }

    public Type getType(StarRocksParser.TypeContext context) {
        if (context.baseType() != null) {
            return getBaseType(context.baseType());
        } else if (context.decimalType() != null) {
            return getDecimalType(context.decimalType());
        } else if (context.arrayType() != null) {
            return getArrayType(context.arrayType());
        } else if (context.structType() != null) {
            return getStructType(context.structType());
        } else {
            return getMapType(context.mapType());
        }
    }

    /**
     * Whether the input decimal is wildcard which is no precision or scale.
     */
    private boolean isWildcardDecimalType(StarRocksParser.TypeContext typeContext) {
        if (typeContext.decimalType() == null) {
            return false;
        }
        StarRocksParser.DecimalTypeContext context = typeContext.decimalType();
        Integer precision = null;
        Integer scale = null;
        if (context.precision != null) {
            precision = Integer.parseInt(context.precision.getText());
            if (context.scale != null) {
                scale = Integer.parseInt(context.scale.getText());
            }
        }
        return precision == null && scale == null;
    }

    /**
     * Create an agg state desc from ast context.
     *
     * @param context agg desc context from parser
     * @return the deduced agg function's intermediate type and its associated agg state desc pair
     */
    public Pair<Type, AggStateDesc> getAggStateDesc(StarRocksParser.AggDescContext context) {
        if (context == null || context.aggStateDesc() == null) {
            return null;
        }
        StarRocksParser.AggStateDescContext aggStateDescContext = context.aggStateDesc();
        Identifier aggFuncNameId = (Identifier) visit(aggStateDescContext.identifier());
        String aggFuncName = aggFuncNameId.getValue();
        if (FunctionSet.UNSUPPORTED_AGG_STATE_FUNCTIONS.contains(aggFuncName)) {
            throw new ParsingException(String.format("AggStateType function %s is not supported", aggFuncName),
                    createPos(context));
        }
        List<StarRocksParser.TypeWithNullableContext> typeWithNullables = aggStateDescContext.typeWithNullable();
        List<Type> argTypes = Lists.newArrayList();
        for (StarRocksParser.TypeWithNullableContext typeWithNullableContext : typeWithNullables) {
            Type argType = getType(typeWithNullableContext.type());
            if (isWildcardDecimalType(typeWithNullableContext.type())) {
                throw new ParsingException(String.format("AggStateType function %s with input %s has wildcard decimal",
                        aggFuncName, argType), createPos(context));
            }
            argTypes.add(argType);
        }
        if (argTypes.stream().anyMatch(t -> t.isUnknown() || t.isTime() ||
                t.isBitmapType() || t.isHllType() || t.isPercentile() || t.isNull() || t.isDecimalV2())) {
            throw new ParsingException(String.format("AggStateType function %s with input %s has unsupported type",
                    aggFuncName, argTypes), createPos(context));
        }

        // distinct or order by are not supported yet in agg_state desc.
        FunctionParams params = new FunctionParams(false, Lists.newArrayList());
        Type[] argumentTypes = argTypes.toArray(Type[]::new);
        Boolean[] isArgumentConstants = argTypes.stream().map(x -> false).toArray(Boolean[]::new);
        Function result = FunctionAnalyzer.getAnalyzedAggregateFunction(ConnectContext.get(), aggFuncName, params, argumentTypes,
                isArgumentConstants, createPos(context));
        if (result == null) {
            throw new ParsingException(String.format("AggStateType function %s with input %s not found", aggFuncName,
                    argTypes), createPos(context));
        }
        if (!(result instanceof AggregateFunction)) {
            throw new ParsingException(String.format("AggStateType function %s with input %s found but not an aggregate " +
                    "function", aggFuncName, argTypes), createPos(context));
        }
        AggregateFunction aggFunc = (AggregateFunction) result;
        if (!AggStateUtils.isSupportedAggStateFunction(aggFunc, false)) {
            throw new ParsingException(String.format("AggStateType function %s with input %s is not supported yet.",
                    aggFuncName, argTypes), createPos(context));
        }
        Type intermediateType = aggFunc.getIntermediateTypeOrReturnType();
        Type finalType = AnalyzerUtils.transformTableColumnType(intermediateType, false);
        AggStateDesc aggStateDesc = new AggStateDesc(aggFunc.functionName(), aggFunc.getReturnType().clone(),
                argTypes.stream().map(c -> c.clone()).collect(toList()));
        return Pair.create(finalType.clone(), aggStateDesc);
    }

    private Type getBaseType(StarRocksParser.BaseTypeContext context) {
        int length = -1;
        if (context.typeParameter() != null) {
            length = Integer.parseInt(context.typeParameter().INTEGER_VALUE().toString());
        }
        if (context.STRING() != null || context.TEXT() != null) {
            ScalarType type = ScalarType.createVarcharType(ScalarType.DEFAULT_STRING_LENGTH);
            return type;
        } else if (context.VARCHAR() != null) {
            ScalarType type = ScalarType.createVarcharType(length);
            return type;
        } else if (context.CHAR() != null) {
            ScalarType type = ScalarType.createCharType(length);
            return type;
        } else if (context.SIGNED() != null) {
            return Type.INT;
        } else if (context.HLL() != null) {
            ScalarType type = ScalarType.createHllType();
            return type;
        } else if (context.BINARY() != null || context.VARBINARY() != null) {
            ScalarType type = ScalarType.createVarbinary(length);
            return type;
        } else {
            return ScalarType.createType(context.getChild(0).getText());
        }
    }

    public ScalarType getDecimalType(StarRocksParser.DecimalTypeContext context) {
        Integer precision = null;
        Integer scale = null;
        if (context.precision != null) {
            precision = Integer.parseInt(context.precision.getText());
            if (context.scale != null) {
                scale = Integer.parseInt(context.scale.getText());
            }
        }
        if (context.DECIMAL() != null || context.NUMBER() != null || context.NUMERIC() != null) {
            if (precision != null) {
                if (scale != null) {
                    return ScalarType.createUnifiedDecimalType(precision, scale);
                }
                return ScalarType.createUnifiedDecimalType(precision);
            }
            return ScalarType.createUnifiedDecimalType(10, 0);
        } else if (context.DECIMAL32() != null || context.DECIMAL64() != null || context.DECIMAL128() != null) {
            try {
                ScalarType.checkEnableDecimalV3();
            } catch (AnalysisException e) {
                throw new SemanticException(e.getMessage());
            }
            final PrimitiveType primitiveType = PrimitiveType.valueOf(context.children.get(0).getText().toUpperCase());
            if (precision != null) {
                if (scale != null) {
                    return ScalarType.createDecimalV3Type(primitiveType, precision, scale);
                }
                return ScalarType.createDecimalV3Type(primitiveType, precision);
            }
            return ScalarType.createDecimalV3Type(primitiveType);
        } else if (context.DECIMALV2() != null) {
            if (precision != null) {
                if (scale != null) {
                    return ScalarType.createDecimalV2Type(precision, scale);
                }
                return ScalarType.createDecimalV2Type(precision);
            }
            return ScalarType.createDecimalV2Type();
        } else {
            throw new IllegalArgumentException("Unsupported type " + context.getText());
        }
    }

    public ArrayType getArrayType(StarRocksParser.ArrayTypeContext context) {
        return new ArrayType(getType(context.type()));
    }

    public StructType getStructType(StarRocksParser.StructTypeContext context) {
        ArrayList<StructField> fields = new ArrayList<>();
        List<StarRocksParser.SubfieldDescContext> subfields =
                context.subfieldDescs().subfieldDesc();
        for (StarRocksParser.SubfieldDescContext type : subfields) {
            Identifier fieldIdentifier = (Identifier) visit(type.identifier());
            String fieldName = fieldIdentifier.getValue();
            fields.add(new StructField(fieldName, getType(type.type()), null));
        }

        return new StructType(fields);
    }

    public MapType getMapType(StarRocksParser.MapTypeContext context) {
        Type keyType = getType(context.type(0));
        if (!keyType.isValidMapKeyType()) {
            throw new ParsingException(PARSER_ERROR_MSG.unsupportedType(keyType.toString(),
                    "for map's key, which should be base types"),
                    createPos(context.type(0)));
        }
        Type valueType = getType(context.type(1));
        return new MapType(keyType, valueType);
    }

    private LabelName qualifiedNameToLabelName(QualifiedName qualifiedName) {
        // Hierarchy: catalog.database.table
        List<String> parts = qualifiedName.getParts();
        if (parts.size() == 2) {
            return new LabelName(parts.get(0), parts.get(1), qualifiedName.getPos());
        } else if (parts.size() == 1) {
            return new LabelName(null, parts.get(0), qualifiedName.getPos());
        } else {
            throw new ParsingException(PARSER_ERROR_MSG.invalidTableFormat(qualifiedName.toString()),
                    qualifiedName.getPos());
        }
    }

    private Map<String, String> getProperties(StarRocksParser.PropertiesContext context) {
        Map<String, String> properties = new HashMap<>();
        if (context != null && context.property() != null) {
            List<Property> propertyList = visit(context.property(), Property.class);
            for (Property property : propertyList) {
                properties.put(property.getKey(), property.getValue());
            }
        }
        return properties;
    }

    private Map<String, String> getPropertyList(StarRocksParser.PropertyListContext context) {
        Map<String, String> properties = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        if (context != null && context.property() != null) {
            List<Property> propertyList = visit(context.property(), Property.class);
            for (Property property : propertyList) {
                properties.put(property.getKey(), property.getValue());
            }
        }
        return properties;
    }

    private List<ParseNode> getLoadPropertyList(List<StarRocksParser.LoadPropertiesContext> loadPropertiesContexts) {
        List<ParseNode> loadPropertyList = new ArrayList<>();
        Preconditions.checkNotNull(loadPropertiesContexts, "load properties is null");
        for (StarRocksParser.LoadPropertiesContext loadPropertiesContext : loadPropertiesContexts) {
            if (loadPropertiesContext.colSeparatorProperty() != null) {
                StringLiteral literal = (StringLiteral) visit(loadPropertiesContext.colSeparatorProperty().string());
                loadPropertyList.add(new ColumnSeparator(literal.getValue(), literal.getPos()));
            }

            if (loadPropertiesContext.rowDelimiterProperty() != null) {
                StringLiteral literal = (StringLiteral) visit(loadPropertiesContext.rowDelimiterProperty().string());
                loadPropertyList.add(new RowDelimiter(literal.getValue(), literal.getPos()));
            }

            if (loadPropertiesContext.importColumns() != null) {
                ImportColumnsStmt importColumnsStmt = (ImportColumnsStmt) visit(loadPropertiesContext.importColumns());
                loadPropertyList.add(importColumnsStmt);
            }

            if (loadPropertiesContext.expression() != null) {
                Expr where = (Expr) visit(loadPropertiesContext.expression());
                loadPropertyList.add(new ImportWhereStmt(where, where.getPos()));
            }

            if (loadPropertiesContext.partitionNames() != null) {
                loadPropertyList.add(visit(loadPropertiesContext.partitionNames()));
            }
        }
        return loadPropertyList;
    }

    @Override
    public ParseNode visitImportColumns(StarRocksParser.ImportColumnsContext importColumnsContext) {
        List<ImportColumnDesc> columns = new ArrayList<>();
        for (StarRocksParser.QualifiedNameContext qualifiedNameContext :
                importColumnsContext.columnProperties().qualifiedName()) {
            String column = ((Identifier) (visit(qualifiedNameContext))).getValue();
            ImportColumnDesc columnDesc = new ImportColumnDesc(column, null, createPos(qualifiedNameContext));
            columns.add(columnDesc);
        }
        for (StarRocksParser.AssignmentContext assignmentContext :
                importColumnsContext.columnProperties().assignment()) {
            ColumnAssignment columnAssignment = (ColumnAssignment) (visit(assignmentContext));
            Expr expr = columnAssignment.getExpr();
            ImportColumnDesc columnDesc = new ImportColumnDesc(columnAssignment.getColumn(), expr,
                    createPos(assignmentContext));
            columns.add(columnDesc);
        }
        return new ImportColumnsStmt(columns, createPos(importColumnsContext));
    }

    private Map<String, String> getJobProperties(StarRocksParser.JobPropertiesContext jobPropertiesContext) {
        Map<String, String> jobProperties = new HashMap<>();
        if (jobPropertiesContext != null) {
            List<Property> propertyList = visit(jobPropertiesContext.properties().property(), Property.class);
            for (Property property : propertyList) {
                jobProperties.put(property.getKey(), property.getValue());
            }
        }
        return jobProperties;
    }

    private Map<String, String> getDataSourceProperties(
            StarRocksParser.DataSourcePropertiesContext dataSourcePropertiesContext) {
        Map<String, String> dataSourceProperties = new HashMap<>();
        if (dataSourcePropertiesContext != null) {
            List<Property> propertyList = visit(dataSourcePropertiesContext.propertyList().property(), Property.class);
            for (Property property : propertyList) {
                dataSourceProperties.put(property.getKey(), property.getValue());
            }
        }
        return dataSourceProperties;
    }

    public List<String> getColumnNames(StarRocksParser.ColumnAliasesContext context) {
        if (context == null) {
            return null;
        }

        // StarRocks tables are not case-sensitive, so targetColumnNames are converted
        // to lowercase characters to facilitate subsequent matching.
        List<Identifier> targetColumnNamesIdentifiers = visitIfPresent(context.identifier(), Identifier.class);
        if (targetColumnNamesIdentifiers != null) {
            return targetColumnNamesIdentifiers.stream()
                    .map(Identifier::getValue).map(String::toLowerCase).collect(toList());
        } else {
            return null;
        }
    }

    protected NodePosition createPos(ParserRuleContext context) {
        Preconditions.checkState(context != null);
        return createPos(context.start, context.stop);
    }

    private NodePosition createPos(Token start, Token stop) {
        if (start == null) {
            return NodePosition.ZERO;
        }

        if (stop == null) {
            return new NodePosition(start.getLine(), start.getCharPositionInLine());
        }

        return new NodePosition(start, stop);
    }

    // labelName can be null or (db.)name format
    private LabelName createLabelName(StarRocksParser.QualifiedNameContext dbCtx,
                                      StarRocksParser.IdentifierContext nameCtx) {

        Token start = null;
        Token stop = null;

        String name = null;
        if (nameCtx != null) {
            name = getIdentifierName(nameCtx);
            start = nameCtx.start;
            stop = nameCtx.stop;
        }

        String dbName = null;
        if (dbCtx != null) {
            dbName = getQualifiedName(dbCtx).toString();
            start = dbCtx.start;
        }

        return new LabelName(dbName, name, createPos(start, stop));
    }

    private List<HintNode> extractQueryScopeHintNode() {
        List<HintNode> res = Lists.newArrayList();
        for (Map.Entry<ParserRuleContext, List<HintNode>> entry : hintMap.entrySet()) {
            for (HintNode hintNode : entry.getValue()) {
                if (hintNode.getScope() == HintNode.Scope.QUERY) {
                    res.add(hintNode);
                }
            }
        }
        Collections.sort(res);
        return res;
    }
}
