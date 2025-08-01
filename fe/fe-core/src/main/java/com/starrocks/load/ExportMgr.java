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
//   https://github.com/apache/incubator-doris/blob/master/fe/fe-core/src/main/java/org/apache/doris/load/ExportMgr.java

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

package com.starrocks.load;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.gson.Gson;
import com.starrocks.analysis.TableName;
import com.starrocks.authorization.AccessDeniedException;
import com.starrocks.catalog.Database;
import com.starrocks.catalog.InternalCatalog;
import com.starrocks.common.AnalysisException;
import com.starrocks.common.Config;
import com.starrocks.common.FeConstants;
import com.starrocks.common.Pair;
import com.starrocks.common.StarRocksException;
import com.starrocks.common.util.ListComparator;
import com.starrocks.common.util.OrderByPair;
import com.starrocks.common.util.TimeUtils;
import com.starrocks.memory.MemoryTrackable;
import com.starrocks.persist.ImageWriter;
import com.starrocks.persist.metablock.SRMetaBlockEOFException;
import com.starrocks.persist.metablock.SRMetaBlockException;
import com.starrocks.persist.metablock.SRMetaBlockID;
import com.starrocks.persist.metablock.SRMetaBlockReader;
import com.starrocks.persist.metablock.SRMetaBlockWriter;
import com.starrocks.qe.ConnectContext;
import com.starrocks.server.GlobalStateMgr;
import com.starrocks.sql.analyzer.Authorizer;
import com.starrocks.sql.ast.CancelExportStmt;
import com.starrocks.sql.ast.ExportStmt;
import com.starrocks.sql.common.MetaUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;

public class ExportMgr implements MemoryTrackable {
    private static final Logger LOG = LogManager.getLogger(ExportJob.class);

    // lock for export job
    // lock is private and must use after db lock
    private ReentrantReadWriteLock lock;

    private Map<Long, ExportJob> idToJob; // exportJobId to exportJob

    public ExportMgr() {
        idToJob = Maps.newHashMap();
        lock = new ReentrantReadWriteLock(true);
    }

    public void readLock() {
        lock.readLock().lock();
    }

    public void readUnlock() {
        lock.readLock().unlock();
    }

    private void writeLock() {
        lock.writeLock().lock();
    }

    private void writeUnlock() {
        lock.writeLock().unlock();
    }

    public Map<Long, ExportJob> getIdToJob() {
        return idToJob;
    }

    public void addExportJob(UUID queryId, ExportStmt stmt) throws Exception {
        long jobId = GlobalStateMgr.getCurrentState().getNextId();
        ExportJob job = createJob(jobId, queryId, stmt);
        writeLock();
        try {
            unprotectAddJob(job);
            GlobalStateMgr.getCurrentState().getEditLog().logExportCreate(job);
        } finally {
            writeUnlock();
        }
        LOG.info("add export job. {}", job);
    }

    public void unprotectAddJob(ExportJob job) {
        idToJob.put(job.getId(), job);
    }

    private ExportJob createJob(long jobId, UUID queryId, ExportStmt stmt) throws Exception {
        ExportJob job = new ExportJob(jobId, queryId, ConnectContext.get().getCurrentWarehouseId());
        job.setJob(stmt);
        return job;
    }

    public ExportJob getExportJob(String dbName, UUID queryId) {
        Database db = GlobalStateMgr.getCurrentState().getLocalMetastore().getDb(dbName);
        MetaUtils.checkDbNullAndReport(db, dbName);
        long dbId = db.getId();
        ExportJob matchedJob = null;
        readLock();
        try {
            for (ExportJob job : idToJob.values()) {
                UUID jobQueryId = job.getQueryId();
                if (job.getDbId() == dbId && (jobQueryId != null && jobQueryId.equals(queryId))) {
                    matchedJob = job;
                    break;
                }
            }
        } finally {
            readUnlock();
        }
        return matchedJob;
    }

    public void cancelExportJob(CancelExportStmt stmt) throws StarRocksException {
        ExportJob matchedJob = getExportJob(stmt.getDbName(), stmt.getQueryId());
        UUID queryId = stmt.getQueryId();
        if (matchedJob == null) {
            throw new AnalysisException("Export job [" + queryId.toString() + "] is not found");
        }
        matchedJob.cancel(ExportFailMsg.CancelType.USER_CANCEL, "user cancel");
    }

    public List<ExportJob> getExportJobs(ExportJob.JobState state) {
        List<ExportJob> result = Lists.newArrayList();
        readLock();
        try {
            for (ExportJob job : idToJob.values()) {
                if (job.getState() == state) {
                    result.add(job);
                }
            }
        } finally {
            readUnlock();
        }

        return result;
    }

    public ExportJob getExportByQueryId(UUID queryId) {
        if (queryId == null) {
            return null;
        }
        readLock();
        try {
            for (ExportJob job : idToJob.values()) {
                if (queryId.equals(job.getQueryId())) {
                    return job;
                }
            }
        } finally {
            readUnlock();
        }
        return null;
    }

    // NOTE: jobid and states may both specified, or only one of them, or neither
    public List<List<String>> getExportJobInfosByIdOrState(
            long dbId, long jobId, Set<ExportJob.JobState> states, UUID queryId,
            ArrayList<OrderByPair> orderByPairs, long limit) {

        long resultNum = limit == -1L ? Integer.MAX_VALUE : limit;
        LinkedList<List<Comparable>> exportJobInfos = new LinkedList<>();
        //If sorting is required, all data needs to be obtained before limiting it
        //If not needed, directly obtain the limit quantity and then sort it
        boolean isLimitBreak = orderByPairs == null;
        readLock();
        try {
            int counter = 0;
            for (ExportJob job : idToJob.values()) {
                long id = job.getId();
                ExportJob.JobState state = job.getState();

                if (job.getDbId() != dbId) {
                    continue;
                }

                // filter job
                if (jobId != 0 && id != jobId) {
                    continue;
                }

                if (states != null && !states.contains(state)) {
                    continue;
                }

                UUID jobQueryId = job.getQueryId();
                if (queryId != null && !queryId.equals(jobQueryId)) {
                    continue;
                }

                // check auth
                TableName tableName = job.getTableName();
                if (tableName == null || tableName.getTbl().equals("DUMMY")) {
                    // forward compatibility, no table name is saved before
                    Database db = GlobalStateMgr.getCurrentState().getLocalMetastore().getDb(dbId);
                    if (db == null) {
                        continue;
                    }

                    try {
                        Authorizer.checkAnyActionOnOrInDb(ConnectContext.get(),
                                InternalCatalog.DEFAULT_INTERNAL_CATALOG_NAME,
                                db.getFullName());
                    } catch (AccessDeniedException e) {
                        continue;
                    }
                } else {
                    try {
                        Authorizer.checkAnyActionOnTable(ConnectContext.get(), tableName);
                    } catch (AccessDeniedException e) {
                        continue;
                    }
                }

                List<Comparable> jobInfo = new ArrayList<>();

                jobInfo.add(id);
                // query id
                jobInfo.add(jobQueryId != null ? jobQueryId.toString() : FeConstants.NULL_STRING);
                jobInfo.add(state.name());
                jobInfo.add(job.getProgress() + "%");

                // task infos
                Map<String, Object> infoMap = Maps.newHashMap();
                List<String> partitions = job.getPartitions();
                if (partitions == null) {
                    partitions = Lists.newArrayList();
                    partitions.add("*");
                }
                infoMap.put("db", job.getTableName().getDb());
                infoMap.put("tbl", job.getTableName().getTbl());
                infoMap.put("partitions", partitions);
                List<String> columns = job.getColumnNames() == null ? Lists.newArrayList("*") : job.getColumnNames();
                infoMap.put("columns", job.isReplayed() ? "N/A" : columns);
                infoMap.put("broker", job.getBrokerDesc().getName());
                infoMap.put("column separator", job.getColumnSeparator());
                infoMap.put("row delimiter", job.getRowDelimiter());
                infoMap.put("mem limit", job.getMemLimit());
                infoMap.put("coord num", job.getCoordList().size());
                infoMap.put("tablet num", job.getTabletLocations() == null ? -1 : job.getTabletLocations().size());
                jobInfo.add(new Gson().toJson(infoMap));
                // path
                jobInfo.add(job.getExportPath());

                jobInfo.add(TimeUtils.longToTimeString(job.getCreateTimeMs()));
                jobInfo.add(TimeUtils.longToTimeString(job.getStartTimeMs()));
                jobInfo.add(TimeUtils.longToTimeString(job.getFinishTimeMs()));
                jobInfo.add(job.getTimeoutSecond());

                // error msg
                if (job.getState() == ExportJob.JobState.CANCELLED) {
                    ExportFailMsg failMsg = job.getFailMsg();
                    jobInfo.add("type:" + failMsg.getCancelType() + "; msg:" + failMsg.getMsg());
                } else {
                    jobInfo.add(FeConstants.NULL_STRING);
                }

                exportJobInfos.add(jobInfo);

                if (isLimitBreak && ++counter >= resultNum) {
                    break;
                }
            }
        } finally {
            readUnlock();
        }

        // order by
        ListComparator<List<Comparable>> comparator;
        if (orderByPairs != null) {
            OrderByPair[] orderByPairArr = new OrderByPair[orderByPairs.size()];
            comparator = new ListComparator<>(orderByPairs.toArray(orderByPairArr));
        } else {
            // sort by id asc
            comparator = new ListComparator<>(0);
        }
        exportJobInfos.sort(comparator);

        List<List<String>> results = Lists.newArrayList();
        //The maximum return value of Math.min(resultNum, exportJobInfos.size()) is Integer.MAX_VALUE
        int upperBound = (int) Math.min(resultNum, exportJobInfos.size());
        for (int i = 0; i < upperBound; i++) {
            results.add(exportJobInfos.get(i).stream().map(Object::toString).collect(Collectors.toList()));
        }
        return results;
    }

    private boolean isJobExpired(ExportJob job, long currentTimeMs) {
        return (currentTimeMs - job.getCreateTimeMs()) / 1000 > Config.history_job_keep_max_second
                && (job.getState() == ExportJob.JobState.CANCELLED
                || job.getState() == ExportJob.JobState.FINISHED);
    }

    public void removeOldExportJobs() {
        writeLock();
        try {
            long currentTimeMs = System.currentTimeMillis();
            Iterator<Map.Entry<Long, ExportJob>> iter = idToJob.entrySet().iterator();
            while (iter.hasNext()) {
                Map.Entry<Long, ExportJob> entry = iter.next();
                ExportJob job = entry.getValue();
                if (isJobExpired(job, currentTimeMs)) {
                    LOG.info("remove expired job: {}", job);
                    iter.remove();
                }
            }

        } finally {
            writeUnlock();
        }

    }

    public void replayCreateExportJob(ExportJob job) {
        writeLock();
        try {
            unprotectAddJob(job);
        } finally {
            writeUnlock();
        }
    }

    @Deprecated
    public void replayUpdateJobState(long jobId, ExportJob.JobState newState) {
        writeLock();
        try {
            ExportJob job = idToJob.get(jobId);
            job.updateState(newState, true, System.currentTimeMillis());
            if (isJobExpired(job, System.currentTimeMillis())) {
                LOG.info("remove expired job: {}", job);
                idToJob.remove(jobId);
            }
        } finally {
            writeUnlock();
        }
    }

    public void replayUpdateJobInfo(ExportJob.ExportUpdateInfo info) {
        writeLock();
        try {
            ExportJob job = idToJob.get(info.jobId);
            job.updateState(info.state, true, info.stateChangeTime);
            if (isJobExpired(job, System.currentTimeMillis())) {
                LOG.info("remove expired job: {}", job);
                idToJob.remove(info.jobId);
            }
            job.setSnapshotPaths(info.deserialize(info.snapshotPaths));
            job.setExportTempPath(info.exportTempPath);
            job.setExportedFiles(info.exportedFiles);
            job.setFailMsg(info.failMsg);
        } finally {
            writeUnlock();
        }
    }

    public long getJobNum(ExportJob.JobState state, long dbId) {
        int size = 0;
        readLock();
        try {
            for (ExportJob job : idToJob.values()) {
                if (job.getState() == state && job.getDbId() == dbId) {
                    ++size;
                }
            }
        } finally {
            readUnlock();
        }
        return size;
    }

    public void saveExportJobV2(ImageWriter imageWriter) throws IOException, SRMetaBlockException {
        int numJson = 1 + idToJob.size();
        SRMetaBlockWriter writer = imageWriter.getBlockWriter(SRMetaBlockID.EXPORT_MGR, numJson);
        writer.writeInt(idToJob.size());
        for (ExportJob job : idToJob.values()) {
            writer.writeJson(job);
        }
        writer.close();
    }

    public void loadExportJobV2(SRMetaBlockReader reader) throws IOException, SRMetaBlockException, SRMetaBlockEOFException {
        long currentTimeMs = System.currentTimeMillis();

        reader.readCollection(ExportJob.class, job -> {
            // discard expired job right away
            if (isJobExpired(job, currentTimeMs)) {
                LOG.info("discard expired job: {}", job);
                return;
            }
            unprotectAddJob(job);
        });
    }

    @Override
    public Map<String, Long> estimateCount() {
        return ImmutableMap.of("ExportJob", (long) idToJob.size());
    }

    @Override
    public List<Pair<List<Object>, Long>> getSamples() {
        return Lists.newArrayList(Pair.create(new ArrayList<>(idToJob.values()), (long) idToJob.size()));
    }
}
