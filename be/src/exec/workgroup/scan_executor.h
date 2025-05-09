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

#pragma once

#include "exec/pipeline/pipeline_metrics.h"
#include "util/limit_setter.h"
#include "util/threadpool.h"
#include "work_group.h"

namespace starrocks::pipeline {
class PipelineExecutorMetrics;
}

namespace starrocks::workgroup {

class ScanExecutor;
class WorkGroupManager;
struct ScanTask;
class ScanTaskQueue;

class ScanExecutor {
public:
    explicit ScanExecutor(std::unique_ptr<ThreadPool> thread_pool, std::unique_ptr<ScanTaskQueue> task_queue,
                          pipeline::ScanExecutorMetrics* metrics);
    virtual ~ScanExecutor() = default;

    void initialize(int32_t num_threads);
    void close();
    void change_num_threads(int32_t num_threads);

    bool submit(ScanTask task);

    void force_submit(ScanTask task);

    void bind_cpus(const CpuUtil::CpuIds& cpuids, const std::vector<CpuUtil::CpuIds>& borrowed_cpuids);

    int64_t num_tasks() const;

private:
    void worker_thread();

    LimitSetter _num_threads_setter;
    std::unique_ptr<ScanTaskQueue> _task_queue;
    // _thread_pool must be placed after _task_queue, because worker threads in _thread_pool use _task_queue.
    std::unique_ptr<ThreadPool> _thread_pool;

    pipeline::ScanExecutorMetrics* _metrics;
};

} // namespace starrocks::workgroup
