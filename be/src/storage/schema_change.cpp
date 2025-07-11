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
//   https://github.com/apache/incubator-doris/blob/master/be/src/olap/schema_change.cpp

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

#include "storage/schema_change.h"

#include <csignal>
#include <memory>
#include <utility>
#include <vector>

#include "exec/sorting/sorting.h"
#include "exprs/expr.h"
#include "exprs/expr_context.h"
#include "gutil/strings/substitute.h"
#include "runtime/current_thread.h"
#include "runtime/mem_pool.h"
#include "runtime/runtime_state.h"
#include "storage/chunk_aggregator.h"
#include "storage/convert_helper.h"
#include "storage/memtable.h"
#include "storage/memtable_rowset_writer_sink.h"
#include "storage/rowset/rowset_factory.h"
#include "storage/rowset/rowset_id_generator.h"
#include "storage/storage_engine.h"
#include "storage/tablet.h"
#include "storage/tablet_manager.h"
#include "storage/tablet_meta_manager.h"
#include "storage/tablet_updates.h"
#include "util/failpoint/fail_point.h"
#include "util/unaligned_access.h"

namespace starrocks {

using ChunkRow = std::pair<size_t, Chunk*>;

int compare_chunk_row(const ChunkRow& lhs, const ChunkRow& rhs, const std::vector<ColumnId>& sort_key_idxes) {
    for (uint16_t i = 0; i < sort_key_idxes.size(); ++i) {
        int res = lhs.second->get_column_by_index(sort_key_idxes[i])
                          ->compare_at(lhs.first, rhs.first, *rhs.second->get_column_by_index(sort_key_idxes[i]), -1);
        if (res != 0) {
            return res;
        }
    }
    return 0;
}

struct MergeElement;
// TODO: optimize it with vertical sort
class HeapChunkMerger {
public:
    explicit HeapChunkMerger(TabletSharedPtr tablet, std::vector<ColumnId> sort_key_idxes);
    virtual ~HeapChunkMerger();

    Status merge(std::vector<ChunkPtr>& chunk_arr, RowsetWriter* rowset_writer);
    static void aggregate_chunk(ChunkAggregator& aggregator, ChunkPtr& chunk, RowsetWriter* rowset_writer);

private:
    friend class MergeElement;
    bool _make_heap(std::vector<ChunkPtr>& chunk_arr);
    void _pop_heap();

    TabletSharedPtr _tablet;
    std::priority_queue<MergeElement> _heap;
    std::unique_ptr<ChunkAggregator> _aggregator;
    std::vector<ColumnId> _sort_key_idxes;
};

struct MergeElement {
    bool operator<(const MergeElement& other) const {
        return compare_chunk_row(std::make_pair(row_index, chunk), std::make_pair(other.row_index, other.chunk),
                                 _merger->_sort_key_idxes) > 0;
    }

    Chunk* chunk;
    size_t row_index;
    HeapChunkMerger* _merger;
};

bool ChunkSorter::sort(ChunkPtr& chunk, const TabletSharedPtr& new_tablet) {
    Schema new_schema = ChunkHelper::convert_schema(new_tablet->tablet_schema());
    if (_swap_chunk == nullptr || _max_allocated_rows < chunk->num_rows()) {
        _swap_chunk = ChunkHelper::new_chunk(new_schema, chunk->num_rows());
        if (_swap_chunk == nullptr) {
            LOG(WARNING) << "allocate swap chunk for sort failed";
            return false;
        }
        _max_allocated_rows = chunk->num_rows();
    }

    _swap_chunk->reset();

    std::vector<ColumnId> sort_key_idxes;
    if (new_schema.sort_key_idxes().empty()) {
        int num_key_columns = chunk->schema()->num_key_fields();
        for (ColumnId i = 0; i < num_key_columns; ++i) {
            sort_key_idxes.push_back(i);
        }
    } else {
        sort_key_idxes = new_schema.sort_key_idxes();
    }
    Columns key_columns;
    for (const auto sort_key_idx : sort_key_idxes) {
        key_columns.push_back(chunk->get_column_by_index(sort_key_idx));
    }

    SmallPermutation perm = create_small_permutation(chunk->num_rows());
    Status st =
            stable_sort_and_tie_columns(false, key_columns, SortDescs::asc_null_first(sort_key_idxes.size()), &perm);
    CHECK(st.ok());
    std::vector<uint32_t> selective;
    permutate_to_selective(perm, &selective);
    _swap_chunk = chunk->clone_empty_with_schema();
    _swap_chunk->append_selective(*chunk, selective.data(), 0, chunk->num_rows());

    chunk->swap_chunk(*_swap_chunk);
    return true;
}

HeapChunkMerger::HeapChunkMerger(TabletSharedPtr tablet, std::vector<ColumnId> sort_key_idxes)
        : _tablet(std::move(tablet)), _aggregator(nullptr), _sort_key_idxes(std::move(sort_key_idxes)) {}

HeapChunkMerger::~HeapChunkMerger() {
    if (_aggregator != nullptr) {
        _aggregator->close();
    }
}

void HeapChunkMerger::aggregate_chunk(ChunkAggregator& aggregator, ChunkPtr& chunk, RowsetWriter* rowset_writer) {
    aggregator.aggregate();
    while (aggregator.is_finish()) {
        (void)rowset_writer->add_chunk(*aggregator.aggregate_result());
        aggregator.aggregate_reset();
        aggregator.aggregate();
    }

    DCHECK(aggregator.source_exhausted());
    aggregator.update_source(chunk);
    aggregator.aggregate();

    while (aggregator.is_finish()) {
        (void)rowset_writer->add_chunk(*aggregator.aggregate_result());
        aggregator.aggregate_reset();
        aggregator.aggregate();
    }
}

Status HeapChunkMerger::merge(std::vector<ChunkPtr>& chunk_arr, RowsetWriter* rowset_writer) {
    auto process_err = [this] {
        VLOG(3) << "merge chunk failed";
        while (!_heap.empty()) {
            _heap.pop();
        }
    };

    _make_heap(chunk_arr);
    size_t nread = 0;
    Schema new_schema = ChunkHelper::convert_schema(_tablet->tablet_schema());
    ChunkPtr tmp_chunk = ChunkHelper::new_chunk(new_schema, config::vector_chunk_size);
    if (_tablet->keys_type() == KeysType::AGG_KEYS) {
        _aggregator = std::make_unique<ChunkAggregator>(&new_schema, config::vector_chunk_size, 0);
    }

    StorageEngine* storage_engine = StorageEngine::instance();
    bool bg_worker_stopped = storage_engine->bg_worker_stopped();
    while (!_heap.empty() && !bg_worker_stopped) {
        if (!tmp_chunk->capacity_limit_reached().ok() || nread >= config::vector_chunk_size) {
            if (_tablet->keys_type() == KeysType::AGG_KEYS) {
                aggregate_chunk(*_aggregator, tmp_chunk, rowset_writer);
            } else {
                (void)rowset_writer->add_chunk(*tmp_chunk);
            }
            tmp_chunk->reset();
            nread = 0;
        }

        tmp_chunk->append(*_heap.top().chunk, _heap.top().row_index, 1);
        nread += 1;
        _pop_heap();
        bg_worker_stopped = StorageEngine::instance()->bg_worker_stopped();
    }

    if (bg_worker_stopped) {
        return Status::InternalError("back ground worker stopped, BE maybe exit");
    }

    if (_tablet->keys_type() == KeysType::AGG_KEYS) {
        aggregate_chunk(*_aggregator, tmp_chunk, rowset_writer);
        if (_aggregator->has_aggregate_data()) {
            _aggregator->aggregate();
            (void)rowset_writer->add_chunk(*_aggregator->aggregate_result());
        }
    } else {
        (void)rowset_writer->add_chunk(*tmp_chunk);
    }

    if (auto st = rowset_writer->flush(); !st.ok()) {
        LOG(WARNING) << "failed to finalizing writer: " << st;
        process_err();
        return st;
    }

    return Status::OK();
}

bool HeapChunkMerger::_make_heap(std::vector<ChunkPtr>& chunk_arr) {
    for (const auto& chunk : chunk_arr) {
        MergeElement element;
        element.chunk = chunk.get();
        element.row_index = 0;
        element._merger = this;

        _heap.push(element);
    }

    return true;
}

void HeapChunkMerger::_pop_heap() {
    MergeElement element = _heap.top();
    _heap.pop();

    if (++element.row_index >= element.chunk->num_rows()) {
        return;
    }

    _heap.push(element);
}

Status LinkedSchemaChange::process(TabletReader* reader, RowsetWriter* new_rowset_writer, TabletSharedPtr new_tablet,
                                   TabletSharedPtr base_tablet, RowsetSharedPtr rowset,
                                   TabletSchemaCSPtr base_tablet_schema) {
    RETURN_IF_ERROR_WITH_WARN(CurrentThread::mem_tracker()->check_mem_limit("LinkedSchemaChange"),
                              fmt::format("{}fail to execute schema change", alter_msg_header()));

    Status status =
            new_rowset_writer->add_rowset_for_linked_schema_change(rowset, _chunk_changer->get_schema_mapping());
    if (!status.ok()) {
        LOG(WARNING) << alter_msg_header() << "fail to convert rowset."
                     << ", new_tablet=" << new_tablet->full_name() << ", base_tablet=" << base_tablet->full_name()
                     << ", version=" << new_rowset_writer->version();
        return status;
    }
    return Status::OK();
}

Status LinkedSchemaChange::generate_delta_column_group_and_cols(const Tablet* new_tablet, const Tablet* base_tablet,
                                                                const RowsetSharedPtr& src_rowset, RowsetId rid,
                                                                int64_t version, ChunkChanger* chunk_changer,
                                                                DeltaColumnGroupList& dcgs,
                                                                std::vector<int> last_dcg_counts,
                                                                const TabletSchemaCSPtr& base_tablet_schema) {
    // This function is just used for adding generated column if src_rowset
    // contain some segment files
    bool no_segment_file = (last_dcg_counts.size() == 0);
    if (chunk_changer->get_gc_exprs()->size() == 0 || no_segment_file) {
        return Status::OK();
    }

    // Get read schema chunk and new partial schema(schema with new added generated column only) chunk
    std::vector<uint32_t> new_columns_ids;
    std::vector<uint32_t> all_ref_columns_ids;
    std::set<uint32_t> s_ref_columns_ids;
    for (const auto& iter : *chunk_changer->get_gc_exprs()) {
        new_columns_ids.emplace_back(iter.first);
        // get read schema only through associated column in all generate columns' expr
        Expr* root = (iter.second)->root();
        std::vector<SlotId> slot_ids;
        root->get_slot_ids(&slot_ids);

        s_ref_columns_ids.insert(slot_ids.begin(), slot_ids.end());
    }
    all_ref_columns_ids.resize(s_ref_columns_ids.size());
    all_ref_columns_ids.assign(s_ref_columns_ids.begin(), s_ref_columns_ids.end());
    std::sort(all_ref_columns_ids.begin(), all_ref_columns_ids.end());
    std::sort(new_columns_ids.begin(), new_columns_ids.end());

    // If all expression is constant, all_ref_columns_ids will be empty.
    // we just append 0 into it to construct the read schema for simplicity.
    if (all_ref_columns_ids.size() == 0) {
        all_ref_columns_ids.emplace_back(0);
    }

    Schema read_schema = ChunkHelper::convert_schema(base_tablet_schema, all_ref_columns_ids);
    ChunkPtr read_chunk = ChunkHelper::new_chunk(read_schema, config::vector_chunk_size);

    auto new_tablet_schema = new_tablet->tablet_schema();
    Schema new_schema = ChunkHelper::convert_schema(new_tablet_schema, new_columns_ids);
    ChunkPtr new_chunk = ChunkHelper::new_chunk(new_schema, config::vector_chunk_size);

    OlapReaderStatistics stats;
    RowsetReleaseGuard guard(src_rowset->shared_from_this());
    auto res = src_rowset->get_segment_iterators2(read_schema, base_tablet_schema, nullptr, version, &stats,
                                                  base_tablet->data_dir()->get_meta());
    if (!res.ok()) {
        return res.status();
    }

    auto seg_iterators = res.value();

    // Fetch the new columns value into the new_chunk
    for (int idx = 0; idx < seg_iterators.size(); ++idx) {
        auto seg_iterator = seg_iterators[idx];
        if (seg_iterator.get() == nullptr) {
            std::stringstream ss;
            ss << "Failed to get segment iterator, segment id: " << idx;
            LOG(WARNING) << ss.str();
            continue;
        }

        new_chunk->reset();

        while (true) {
            read_chunk->reset();
            Status status = seg_iterator->get_next(read_chunk.get());
            if (!status.ok()) {
                if (status.is_end_of_file()) {
                    break;
                } else {
                    std::stringstream ss;
                    ss << "segment iterator failed to get next chunk, status is:" << status.to_string();
                    LOG(WARNING) << ss.str();
                    return Status::InternalError(ss.str());
                }
            }
            status = chunk_changer->append_generated_columns(read_chunk, new_chunk, all_ref_columns_ids,
                                                             base_tablet_schema->num_columns());
            if (!status.ok()) {
                LOG(WARNING) << "failed to append generated columns";
                return Status::InternalError("failed to append generated columns");
            }
        }

        // Write cols file with current new_chunk
        ASSIGN_OR_RETURN(auto fs, FileSystem::CreateSharedFromString(new_tablet->schema_hash_path()));
        const std::string path = Rowset::delta_column_group_path(new_tablet->schema_hash_path(), rid, idx, version,
                                                                 last_dcg_counts[idx]);
        // must record unique column id in delta column group
        std::vector<ColumnUID> unique_column_ids;
        for (const auto& iter : *chunk_changer->get_gc_exprs()) {
            ColumnUID unique_id = new_tablet_schema->column(iter.first).unique_id();
            unique_column_ids.emplace_back(unique_id);
        }
        std::sort(unique_column_ids.begin(), unique_column_ids.end());
        auto cols_file_schema = TabletSchema::create_with_uid(new_tablet_schema, unique_column_ids);

        (void)fs->delete_file(path); // delete .cols if already exist
        WritableFileOptions opts{.sync_on_close = true};
        ASSIGN_OR_RETURN(auto wfile, fs->new_writable_file(opts, path));
        SegmentWriterOptions writer_options;
        auto segment_writer = std::make_unique<SegmentWriter>(std::move(wfile), idx, cols_file_schema, writer_options);
        RETURN_IF_ERROR(segment_writer->init(false));

        uint64_t segment_file_size = 0;
        uint64_t index_size = 0;
        uint64_t footer_position = 0;
        RETURN_IF_ERROR(segment_writer->append_chunk(*new_chunk));
        RETURN_IF_ERROR(segment_writer->finalize(&segment_file_size, &index_size, &footer_position));

        // abort schema change if cols file' size is larger than max_segment_file_size
        // It is nearly impossible happen unless the expression result of the generated column
        // is extremely large or there are too many generated column to added.
        if (UNLIKELY(segment_file_size > config::max_segment_file_size)) {
            (void)fs->delete_file(path);
            std::stringstream ss;
            ss << "cols file' size is larger than max_segment_file_size: " << config::max_segment_file_size;
            LOG(WARNING) << ss.str();
            return Status::InternalError(ss.str());
        }

        // Get DeltaColumnGroup for current cols file
        auto dcg = std::make_shared<DeltaColumnGroup>();
        std::vector<std::vector<ColumnUID>> dcg_column_ids{unique_column_ids};
        std::vector<std::string> dcg_column_files{file_name(segment_writer->segment_path())};
        dcg->init(version, dcg_column_ids, dcg_column_files);
        dcgs.emplace_back(dcg);
    }

    return Status::OK();
}

Status SchemaChangeDirectly::process(TabletReader* reader, RowsetWriter* new_rowset_writer, TabletSharedPtr new_tablet,
                                     TabletSharedPtr base_tablet, RowsetSharedPtr rowset,
                                     TabletSchemaCSPtr base_tablet_schema) {
    auto cur_base_tablet_schema = !base_tablet_schema ? base_tablet->tablet_schema() : base_tablet_schema;
    Schema base_schema =
            ChunkHelper::convert_schema(cur_base_tablet_schema, _chunk_changer->get_selected_column_indexes());
    ChunkPtr base_chunk = ChunkHelper::new_chunk(base_schema, config::vector_chunk_size);
    auto new_tschema = new_tablet->tablet_schema();
    std::vector<ColumnId> cids;
    for (size_t i = 0; i < new_tschema->num_columns(); i++) {
        if (new_tschema->column(i).name() == Schema::FULL_ROW_COLUMN) {
            continue;
        }
        cids.push_back(i);
    }
    Schema new_schema = ChunkHelper::convert_schema(new_tablet->tablet_schema(), cids);
    auto char_field_indexes = ChunkHelper::get_char_field_indexes(new_schema);

    ChunkPtr new_chunk = ChunkHelper::new_chunk(new_schema, config::vector_chunk_size);

    std::unique_ptr<MemPool> mem_pool(new MemPool());
    bool bg_worker_stopped = false;
    bool is_eos = false;
    while (!bg_worker_stopped && !is_eos) {
        Status st;

        bg_worker_stopped = StorageEngine::instance()->bg_worker_stopped();
        if (bg_worker_stopped) {
            return Status::InternalError(alter_msg_header() + "bg_worker_stopped");
        }

        RETURN_IF_ERROR_WITH_WARN(CurrentThread::mem_tracker()->check_mem_limit("DirectSchemaChange"),
                                  fmt::format("{}fail to execute schema change", alter_msg_header()));

        if (st = reader->do_get_next(base_chunk.get()); !st.ok()) {
            if (is_eos = st.is_end_of_file(); !is_eos) {
                LOG(WARNING) << alter_msg_header()
                             << "tablet reader failed to get next chunk, status: " << st.message();
                return st;
            }
        }

        if (base_chunk->num_rows() == 0) {
            break;
        }

        if (!_chunk_changer->change_chunk_v2(base_chunk, new_chunk, base_schema, new_schema, mem_pool.get())) {
            std::string err_msg = strings::Substitute("failed to convert chunk data. base tablet:$0, new tablet:$1",
                                                      base_tablet->tablet_id(), new_tablet->tablet_id());
            LOG(WARNING) << alter_msg_header() + err_msg;
            return Status::InternalError(alter_msg_header() + err_msg);
        }
        // Since mv supports where expression, new_chunk may be empty after where expression evalutions.
        if (new_chunk->num_rows() == 0) {
            continue;
        }

        if (auto st = _chunk_changer->fill_generated_columns(new_chunk); !st.ok()) {
            LOG(WARNING) << alter_msg_header() << "fill generated columns failed: " << st.message();
            return st;
        }

        ChunkHelper::padding_char_columns(char_field_indexes, new_schema, new_tablet->tablet_schema(), new_chunk.get());

        if (st = new_rowset_writer->add_chunk(*new_chunk); !st.ok()) {
            std::string err_msg = strings::Substitute(
                    "failed to execute schema change. base tablet:$0, new_tablet:$1. err msg: failed to add chunk to "
                    "rowset writer: $2",
                    base_tablet->tablet_id(), new_tablet->tablet_id(), st.message());
            LOG(WARNING) << alter_msg_header() << err_msg;
            return Status::InternalError(alter_msg_header() + err_msg);
        }
        base_chunk->reset();
        new_chunk->reset();
        mem_pool->clear();
    }

    if (auto st = new_rowset_writer->flush(); !st.ok()) {
        LOG(WARNING) << alter_msg_header() << "failed to flush rowset writer: " << st;
        return st;
    }

    return Status::OK();
}

SchemaChangeWithSorting::SchemaChangeWithSorting(ChunkChanger* chunk_changer, size_t memory_limitation)
        : SchemaChange(), _chunk_changer(chunk_changer), _memory_limitation(memory_limitation) {}

Status SchemaChangeWithSorting::process(TabletReader* reader, RowsetWriter* new_rowset_writer,
                                        TabletSharedPtr new_tablet, TabletSharedPtr base_tablet, RowsetSharedPtr rowset,
                                        TabletSchemaCSPtr base_tablet_schema) {
    auto cur_base_tablet_schema = !base_tablet_schema ? base_tablet->tablet_schema() : base_tablet_schema;
    MemTableRowsetWriterSink mem_table_sink(new_rowset_writer);
    Schema base_schema =
            ChunkHelper::convert_schema(cur_base_tablet_schema, _chunk_changer->get_selected_column_indexes());
    auto new_tschema = new_tablet->tablet_schema();
    std::vector<ColumnId> cids;
    for (size_t i = 0; i < new_tschema->num_columns(); i++) {
        if (new_tschema->column(i).name() == Schema::FULL_ROW_COLUMN) {
            continue;
        }
        cids.push_back(i);
    }
    Schema new_schema = ChunkHelper::convert_schema(new_tablet->tablet_schema(), cids);
    auto char_field_indexes = ChunkHelper::get_char_field_indexes(new_schema);

    // memtable max buffer size set default 80% of memory limit so that it will do _merge() if reach limit
    // set max memtable size to 4G since some column has limit size, it will make invalid data
    size_t max_buffer_size = std::min<size_t>(
            4294967296, static_cast<size_t>(_memory_limitation * config::memory_ratio_for_sorting_schema_change));
    auto mem_table = std::make_unique<MemTable>(new_tablet->tablet_id(), &new_schema, &mem_table_sink, max_buffer_size,
                                                CurrentThread::mem_tracker());

    auto selective = std::make_unique<std::vector<uint32_t>>();
    selective->resize(config::vector_chunk_size);
    for (uint32_t i = 0; i < config::vector_chunk_size; i++) {
        (*selective)[i] = i;
    }

    std::unique_ptr<MemPool> mem_pool(new MemPool());

    StorageEngine* storage_engine = StorageEngine::instance();
    bool bg_worker_stopped = false;
    bool is_eos = false;
    while (!bg_worker_stopped && !is_eos) {
        bg_worker_stopped = storage_engine->bg_worker_stopped();
        if (bg_worker_stopped) {
            return Status::InternalError(alter_msg_header() + "bg_worker_stopped");
        }

#ifndef BE_TEST
        auto cur_usage = CurrentThread::mem_tracker()->consumption();
        // we check memory usage exceeds 90% since tablet reader use some memory
        // it will return fail if memory is exhausted
        if (cur_usage > CurrentThread::mem_tracker()->limit() * 0.9) {
            RETURN_IF_ERROR_WITH_WARN(mem_table->finalize(), alter_msg_header() + "failed to finalize mem table");
            RETURN_IF_ERROR_WITH_WARN(mem_table->flush(), alter_msg_header() + "failed to flush mem table");
            mem_table = std::make_unique<MemTable>(new_tablet->tablet_id(), &new_schema, &mem_table_sink,
                                                   max_buffer_size, CurrentThread::mem_tracker());
            VLOG(2) << alter_msg_header() << "SortSchemaChange memory usage: " << cur_usage << " after mem table flush "
                    << CurrentThread::mem_tracker()->consumption();
        }
#endif
        ChunkPtr base_chunk = ChunkHelper::new_chunk(base_schema, config::vector_chunk_size);
        if (auto status = reader->do_get_next(base_chunk.get()); !status.ok()) {
            if (is_eos = status.is_end_of_file(); !is_eos) {
                LOG(WARNING) << alter_msg_header() << "failed to get next chunk, status is:" << status.to_string();
                return status;
            }
        }

        if (base_chunk->num_rows() <= 0) {
            break;
        }

        ChunkPtr new_chunk = ChunkHelper::new_chunk(new_schema, base_chunk->num_rows());

        if (!_chunk_changer->change_chunk_v2(base_chunk, new_chunk, base_schema, new_schema, mem_pool.get())) {
            std::string err_msg = strings::Substitute("failed to convert chunk data. base tablet:$0, new tablet:$1",
                                                      base_tablet->tablet_id(), new_tablet->tablet_id());
            LOG(WARNING) << alter_msg_header() << err_msg;
            return Status::InternalError(alter_msg_header() + err_msg);
        }
        // Since mv supports where expression, new_chunk may be empty after where expression evalutions.
        if (new_chunk->num_rows() == 0) {
            continue;
        }

        ChunkHelper::padding_char_columns(char_field_indexes, new_schema, new_tablet->tablet_schema(), new_chunk.get());

        auto res = mem_table->insert(*new_chunk, selective->data(), 0, new_chunk->num_rows());
        if (!res.ok()) {
            std::string msg = strings::Substitute("$0 failed to insert mem table: $1", alter_msg_header(),
                                                  res.status().to_string());
            LOG(WARNING) << msg;
            return res.status();
        }
        auto full = res.value();
        if (full) {
            RETURN_IF_ERROR_WITH_WARN(mem_table->finalize(), alter_msg_header() + "failed to finalize mem table");
            RETURN_IF_ERROR_WITH_WARN(mem_table->flush(), alter_msg_header() + "failed to flush mem table");
            mem_table = std::make_unique<MemTable>(new_tablet->tablet_id(), &new_schema, &mem_table_sink,
                                                   max_buffer_size, CurrentThread::mem_tracker());
        }

        mem_pool->clear();
    }

    RETURN_IF_ERROR_WITH_WARN(mem_table->finalize(), alter_msg_header() + "failed to finalize mem table");
    RETURN_IF_ERROR_WITH_WARN(mem_table->flush(), alter_msg_header() + "failed to flush mem table");

    if (auto st = new_rowset_writer->flush(); !st.ok()) {
        LOG(WARNING) << alter_msg_header() << "failed to flush rowset writer: " << st;
        return st;
    }

    return Status::OK();
}

Status SchemaChangeWithSorting::_internal_sorting(std::vector<ChunkPtr>& chunk_arr, RowsetWriter* new_rowset_writer,
                                                  TabletSharedPtr tablet) {
    if (chunk_arr.size() == 1) {
        Status st;
        if (st = new_rowset_writer->add_chunk(*chunk_arr[0]); !st.ok()) {
            LOG(WARNING) << "failed to add chunk: " << st;
            return st;
        }
        if (st = new_rowset_writer->flush(); !st.ok()) {
            LOG(WARNING) << "failed to finalizing writer: " << st;
        }
        return st;
    }

    std::vector<ColumnId> sort_key_idxes = tablet->tablet_schema()->sort_key_idxes();
    if (sort_key_idxes.empty()) {
        sort_key_idxes.resize(tablet->tablet_schema()->num_key_columns());
        std::iota(sort_key_idxes.begin(), sort_key_idxes.end(), 0);
    }
    HeapChunkMerger merger(std::move(tablet), std::move(sort_key_idxes));
    if (auto st = merger.merge(chunk_arr, new_rowset_writer); !st.ok()) {
        LOG(WARNING) << "merge chunk arr failed";
        return st;
    }

    return Status::OK();
}

Status SchemaChangeHandler::process_alter_tablet(const TAlterTabletReqV2& request) {
    VLOG(2) << _alter_msg_header << "begin to do request alter tablet: base_tablet_id=" << request.base_tablet_id
            << ", base_schema_hash=" << request.base_schema_hash << ", new_tablet_id=" << request.new_tablet_id
            << ", new_schema_hash=" << request.new_schema_hash << ", alter_version=" << request.alter_version;
    _task_detail_msg += fmt::format("[begin to do request alter tablet, base: {}/{}, new: {}/{}, alter_version:{}]",
                                    request.base_tablet_id, request.base_schema_hash, request.new_tablet_id,
                                    request.new_schema_hash, request.alter_version);

    MonotonicStopWatch timer;
    timer.start();

    // Lock schema_change_lock util schema change info is stored in tablet header
    bool hold_lock = StorageEngine::instance()->tablet_manager()->try_schema_change_lock(request.base_tablet_id);
    RETURN_ERROR_IF_FALSE(hold_lock,
                          string::Substitute("failed to obtain schema change lock, tablet:$0", request.base_tablet_id));
    DeferOp release_lock(
            [&] { StorageEngine::instance()->tablet_manager()->release_schema_change_lock(request.base_tablet_id); });

    Status status = _do_process_alter_tablet(request);
    VLOG(2) << _alter_msg_header << "finished alter tablet process, status=" << status.to_string()
            << " duration: " << timer.elapsed_time() / 1000000
            << "ms, peak_mem_usage: " << CurrentThread::mem_tracker()->peak_consumption() << " bytes";
    if (status.ok()) {
        _task_detail_msg.clear();
    }
    _task_detail_msg += fmt::format("finished alter tablet process, status={}, duration:{} ms, peak_mem_usage:{} bytes",
                                    status.to_string(), timer.elapsed_time() / 1000000,
                                    CurrentThread::mem_tracker()->peak_consumption());
    return status;
}

DEFINE_FAIL_POINT(base_tablet_max_version_greater_than_alter_version);
DEFINE_FAIL_POINT(add_rowset_already_exist);
DEFINE_FAIL_POINT(add_rowset_failed);
Status SchemaChangeHandler::_do_process_alter_tablet(const TAlterTabletReqV2& request) {
    TabletSharedPtr base_tablet = StorageEngine::instance()->tablet_manager()->get_tablet(request.base_tablet_id);
    RETURN_IF((base_tablet == nullptr),
              Status::InternalError(fmt::format("[fail to find base_tablet: {}/{}]", request.base_tablet_id,
                                                request.base_schema_hash)));
    // new tablet has to exist
    TabletSharedPtr new_tablet = StorageEngine::instance()->tablet_manager()->get_tablet(request.new_tablet_id);
    RETURN_IF((new_tablet == nullptr),
              Status::InternalError(
                      fmt::format("[fail to find new tablet: {}/{}]", request.new_tablet_id, request.new_schema_hash)));

    // check if tablet's state is not_ready, if it is ready, it means the tablet already finished
    // check whether the tablet's max continuous version == request.version
    if (new_tablet->tablet_state() != TABLET_NOTREADY) {
        Status st = _validate_alter_result(new_tablet, request);
        VLOG(2) << _alter_msg_header << "tablet's state=" << new_tablet->tablet_state()
                << " the convert job already finished, check its version"
                << " res=" << st.to_string();
        _task_detail_msg += fmt::format("[new tablet state:{}, convert job finished, check version status:{}]",
                                        new_tablet->tablet_state(), st.to_string());
        return st;
    }

    VLOG(2) << _alter_msg_header
            << "finish to validate alter tablet request. begin to convert data from base tablet to new tablet"
            << " base_tablet=" << base_tablet->full_name() << " new_tablet=" << new_tablet->full_name();

    _task_detail_msg +=
            fmt::format("[finishe validate alter table request. begin to convert data from tablet:{} to tablet:{}]",
                        base_tablet->full_name(), new_tablet->full_name());

    std::shared_lock base_migration_rlock(base_tablet->get_migration_lock(), std::try_to_lock);
    if (!base_migration_rlock.owns_lock()) {
        return Status::InternalError(_alter_msg_header + "base tablet get migration r_lock failed");
    }
    if (Tablet::check_migrate(base_tablet)) {
        return Status::InternalError(
                strings::Substitute(_alter_msg_header + "tablet $0 is doing disk balance", base_tablet->tablet_id()));
    }
    std::shared_lock new_migration_rlock(new_tablet->get_migration_lock(), std::try_to_lock);
    if (!new_migration_rlock.owns_lock()) {
        return Status::InternalError(_alter_msg_header + "new tablet get migration r_lock failed");
    }
    if (Tablet::check_migrate(new_tablet)) {
        return Status::InternalError(_alter_msg_header +
                                     strings::Substitute("tablet $0 is doing disk balance", new_tablet->tablet_id()));
    }

    // Create a new tablet schema, should merge with dropped columns in light schema change
    TabletSchemaCSPtr base_tablet_schema;
    if (!request.columns.empty() && request.columns[0].col_unique_id >= 0) {
        base_tablet_schema = TabletSchema::copy(*base_tablet->tablet_schema(), request.columns);
    } else {
        base_tablet_schema = base_tablet->tablet_schema();
    }
    auto new_tablet_schema = new_tablet->tablet_schema();

    SchemaChangeParams sc_params;
    sc_params.base_tablet = base_tablet;
    sc_params.new_tablet = new_tablet;
    sc_params.base_tablet_schema = base_tablet_schema;
    sc_params.base_table_column_names = request.base_table_column_names;
    sc_params.alter_job_type = request.alter_job_type;
    sc_params.chunk_changer =
            std::make_unique<ChunkChanger>(sc_params.base_tablet_schema, new_tablet_schema,
                                           sc_params.base_table_column_names, sc_params.alter_job_type);

    auto* chunk_changer = sc_params.chunk_changer.get();
    if (sc_params.alter_job_type == TAlterJobType::ROLLUP) {
        if (!request.__isset.query_options || !request.__isset.query_globals) {
            return Status::InternalError(_alter_msg_header +
                                         "change materialized view but query_options/query_globals is not set");
        }
        chunk_changer->init_runtime_state(request.query_options, request.query_globals);

        RuntimeState* runtime_state = chunk_changer->get_runtime_state();
        RETURN_IF_ERROR(DescriptorTbl::create(runtime_state, chunk_changer->get_object_pool(), request.desc_tbl,
                                              &sc_params.desc_tbl, runtime_state->chunk_size()));
        chunk_changer->set_query_slots(sc_params.desc_tbl);
    }

    // generated column index in new schema
    std::unordered_set<int> generated_column_idxs;
    if (request.materialized_column_req.mc_exprs.size() != 0) {
        for (const auto& it : request.materialized_column_req.mc_exprs) {
            generated_column_idxs.insert(it.first);
        }
    }

    // primary key do not support materialized view, initialize materialized_params_map here,
    // just for later column_mapping of _parse_request.
    SchemaChangeUtils::init_materialized_params(request, &sc_params.materialized_params_map, sc_params.where_expr);
    Status status = SchemaChangeUtils::parse_request(base_tablet_schema, new_tablet_schema, chunk_changer,
                                                     sc_params.materialized_params_map, sc_params.where_expr,
                                                     !base_tablet->delete_predicates().empty(), &sc_params.sc_sorting,
                                                     &sc_params.sc_directly, &generated_column_idxs);

    if (!status.ok()) {
        VLOG(2) << _alter_msg_header << "failed to parse the request. res=" << status.message();
        return status;
    }

    if (request.__isset.materialized_column_req && request.materialized_column_req.mc_exprs.size() != 0) {
        // Currently, a schema change task for generated column is just
        // ADD/DROP/MODIFY a single generated column, so it is impossible
        // that sc_sorting == true, for generated column can not be a KEY.
        DCHECK_EQ(sc_params.sc_sorting, false);

        chunk_changer->init_runtime_state(request.materialized_column_req.query_options,
                                          request.materialized_column_req.query_globals);

        for (const auto& it : request.materialized_column_req.mc_exprs) {
            ExprContext* ctx = nullptr;
            RETURN_IF_ERROR(Expr::create_expr_tree(chunk_changer->get_object_pool(), it.second, &ctx,
                                                   chunk_changer->get_runtime_state()));
            RETURN_IF_ERROR(ctx->prepare(chunk_changer->get_runtime_state()));
            RETURN_IF_ERROR(ctx->open(chunk_changer->get_runtime_state()));

            chunk_changer->get_gc_exprs()->insert({it.first, ctx});
        }
    }

    if (base_tablet->keys_type() == KeysType::PRIMARY_KEYS) {
        // pk table can handle the case that convert version > request version, duplicate versions will be skipped
        int64_t request_version = request.alter_version;
        int64_t base_max_version = base_tablet->max_version().second;
        FAIL_POINT_TRIGGER_EXECUTE(base_tablet_max_version_greater_than_alter_version,
                                   { request_version = base_max_version - 1; });
        if (base_max_version > request_version) {
            VLOG(2) << _alter_msg_header << " base_tablet's max_version:" << base_max_version
                    << " > request_version:" << request_version
                    << " using max_version instead, base_tablet:" << base_tablet->tablet_id()
                    << " new_tablet:" << new_tablet->tablet_id();
            _task_detail_msg +=
                    fmt::format("[base tablet max_version: {} > request_version: {}, use max_version instead]",
                                base_max_version, request_version);
            request_version = base_max_version;
        }
        if (sc_params.sc_directly) {
            status = new_tablet->updates()->convert_from(base_tablet, request_version, chunk_changer,
                                                         base_tablet_schema, _alter_msg_header);
        } else if (sc_params.sc_sorting) {
            status = new_tablet->updates()->reorder_from(base_tablet, request_version, chunk_changer,
                                                         base_tablet_schema, _alter_msg_header);
        } else {
            status = new_tablet->updates()->link_from(base_tablet.get(), request_version, chunk_changer,
                                                      base_tablet_schema, _alter_msg_header);
        }
        if (!status.ok()) {
            LOG(WARNING) << _alter_msg_header << "schema change new tablet load snapshot error: " << status.to_string();
            return status;
        }
        return Status::OK();
    } else {
        return _do_process_alter_tablet_normal(request, sc_params, base_tablet, new_tablet);
    }
}

Status SchemaChangeHandler::_do_process_alter_tablet_normal(const TAlterTabletReqV2& request,
                                                            SchemaChangeParams& sc_params,
                                                            const TabletSharedPtr& base_tablet,
                                                            const TabletSharedPtr& new_tablet) {
    // begin to find deltas to convert from base tablet to new tablet so that
    // obtain base tablet and new tablet's push lock and header write lock to prevent loading data
    RowsetSharedPtr max_rowset;
    std::vector<RowsetSharedPtr> rowsets_to_change;
    int32_t end_version = -1;
    Status status;
    std::vector<std::unique_ptr<TabletReader>> readers;
    {
        std::lock_guard l1(base_tablet->get_push_lock());
        std::lock_guard l2(new_tablet->get_push_lock());
        std::shared_lock l3(base_tablet->get_header_lock());
        std::lock_guard l4(new_tablet->get_header_lock());

        std::vector<Version> versions_to_be_changed;
        RETURN_IF_ERROR(_get_versions_to_be_changed(base_tablet, &versions_to_be_changed));
        VLOG(3) << "versions to be changed size:" << versions_to_be_changed.size();

        auto base_tablet_schema = sc_params.base_tablet_schema;
        Schema base_schema;
        base_schema =
                ChunkHelper::convert_schema(base_tablet_schema, sc_params.chunk_changer->get_selected_column_indexes());

        for (auto& version : versions_to_be_changed) {
            rowsets_to_change.push_back(base_tablet->get_rowset_by_version(version));
            if (rowsets_to_change.back()->rowset_meta()->gtid() > sc_params.gtid) {
                sc_params.gtid = rowsets_to_change.back()->rowset_meta()->gtid();
            }
            if (rowsets_to_change.back() == nullptr) {
                std::vector<Version> base_tablet_versions;
                base_tablet->list_versions(&base_tablet_versions);
                std::stringstream ss;
                ss << " rs_version_map: ";
                for (auto& ver : base_tablet_versions) {
                    ss << ver << ",";
                }
                ss << " versions_to_be_changed: ";
                for (auto& ver : versions_to_be_changed) {
                    ss << ver << ",";
                }
                return Status::InternalError(fmt::format("fail to get rowset by version: {}", ss.str()));
            }
            // prepare tablet reader to prevent rowsets being compacted
            std::unique_ptr<TabletReader> tablet_reader =
                    std::make_unique<TabletReader>(base_tablet, version, base_schema, base_tablet_schema);
            RETURN_IF_ERROR(tablet_reader->prepare());
            readers.emplace_back(std::move(tablet_reader));
        }
        VLOG(3) << "rowsets_to_change size is:" << rowsets_to_change.size();

        Version max_version = base_tablet->max_version();
        max_rowset = base_tablet->rowset_with_max_version();
        RETURN_IF((max_rowset == nullptr || max_version.second < request.alter_version),
                  Status::InternalError(fmt::format("base tablet's max_version: {} is less than request_version:{}",
                                                    (max_rowset == nullptr ? 0 : max_rowset->end_version()),
                                                    request.alter_version)));

        VLOG(2) << _alter_msg_header << "begin to remove all data from new tablet to prevent rewrite."
                << " new_tablet=" << new_tablet->full_name();
        _task_detail_msg += fmt::format("[begin to remove all data from new tablet({}) to prevent rewrite]",
                                        new_tablet->full_name());
        std::vector<RowsetSharedPtr> rowsets_to_delete;
        std::vector<Version> new_tablet_versions;
        new_tablet->list_versions(&new_tablet_versions);
        for (auto& version : new_tablet_versions) {
            if (version.second <= max_rowset->end_version()) {
                rowsets_to_delete.push_back(new_tablet->get_rowset_by_version(version));
            }
        }
        VLOG(3) << "rowsets_to_delete size is:" << rowsets_to_delete.size()
                << " version is:" << max_rowset->end_version();
        new_tablet->modify_rowsets_without_lock(std::vector<RowsetSharedPtr>(), rowsets_to_delete, nullptr);
        new_tablet->set_cumulative_layer_point(-1);
        new_tablet->save_meta();
        for (auto& rowset : rowsets_to_delete) {
            // do not call rowset.remove directly, using gc thread to delete it
            StorageEngine::instance()->add_unused_rowset(rowset);
        }

        // init one delete handler
        for (auto& version : versions_to_be_changed) {
            if (version.second > end_version) {
                end_version = version.second;
            }
        }
    }

    Version delete_predicates_version(0, max_rowset->version().second);
    TabletReaderParams read_params;
    read_params.reader_type = ReaderType::READER_ALTER_TABLE;
    read_params.skip_aggregation = false;
    read_params.chunk_size = config::vector_chunk_size;
    if (sc_params.sc_directly) {
        // If the segments of rowset is overlapping, will should use heap merge,
        // otherwise the rows is not ordered by short key.
        read_params.sorted_by_keys_per_tablet = true;
    }

    // open tablet readers out of lock for open is heavy because of io
    for (auto& tablet_reader : readers) {
        tablet_reader->set_delete_predicates_version(delete_predicates_version);
        RETURN_IF_ERROR(tablet_reader->open(read_params));
    }

    sc_params.rowset_readers = std::move(readers);
    sc_params.version = Version(0, end_version);
    sc_params.rowsets_to_change = rowsets_to_change;

    status = _convert_historical_rowsets(sc_params);
    if (!status.ok()) {
        return status;
    }

    Status res = Status::OK();
    {
        // set state to ready
        std::unique_lock new_wlock(new_tablet->get_header_lock());
        res = new_tablet->set_tablet_state(TabletState::TABLET_RUNNING);
        if (!res.ok()) {
            // do not drop the new tablet and its data. GC thread will
            return res;
        }
        // link schema change will not generate low cardinality dict for new column
        // so that we need disable shortchut compaction make sure dict will generate by further compaction
        if (!sc_params.sc_directly && !sc_params.sc_sorting) {
            new_tablet->tablet_meta()->set_enable_shortcut_compaction(false);
        }
        new_tablet->save_meta();
    }

    // _validate_alter_result should be outside the above while loop.
    // to avoid requiring the header lock twice.
    status = _validate_alter_result(new_tablet, request);
    if (!status.ok()) {
        // do not drop the new tablet and its data. GC thread will
        return status;
    }
    VLOG(2) << _alter_msg_header << "success to alter tablet. base_tablet=" << base_tablet->full_name();
    return Status::OK();
}

Status SchemaChangeHandler::_get_versions_to_be_changed(const TabletSharedPtr& base_tablet,
                                                        std::vector<Version>* versions_to_be_changed) {
    RowsetSharedPtr rowset = base_tablet->rowset_with_max_version();
    if (rowset == nullptr) {
        return Status::InternalError(fmt::format("tablet: {} has no version", base_tablet->full_name()));
    }
    std::vector<Version> span_versions;
    if (!base_tablet->capture_consistent_versions(Version(0, rowset->version().second), &span_versions).ok()) {
        return Status::InternalError(
                fmt::format("base tablet: {} capture consistent versions failed", base_tablet->tablet_id()));
    }
    versions_to_be_changed->insert(std::end(*versions_to_be_changed), std::begin(span_versions),
                                   std::end(span_versions));
    return Status::OK();
}

Status SchemaChangeHandler::_convert_historical_rowsets(SchemaChangeParams& sc_params) {
    VLOG(2) << _alter_msg_header << "begin to convert historical rowsets for new_tablet from base_tablet."
            << " base_tablet=" << sc_params.base_tablet->full_name()
            << ", new_tablet=" << sc_params.new_tablet->full_name();
    _task_detail_msg += fmt::format("[begin to convert historical rowset from tablet {} to {}]",
                                    sc_params.base_tablet->full_name(), sc_params.new_tablet->full_name());
    DeferOp save_meta([&sc_params] {
        std::unique_lock new_wlock(sc_params.new_tablet->get_header_lock());
        sc_params.new_tablet->save_meta();
    });
    std::unique_ptr<SchemaChange> sc_procedure;
    auto chunk_changer = sc_params.chunk_changer.get();
    if (sc_params.sc_sorting) {
        VLOG(2) << _alter_msg_header << "doing schema change with sorting for base_tablet "
                << sc_params.base_tablet->full_name();
        _task_detail_msg += fmt::format("[tablet: {} doing sorting schema change]", sc_params.base_tablet->full_name());
        size_t memory_limitation =
                static_cast<size_t>(config::memory_limitation_per_thread_for_schema_change) * 1024 * 1024 * 1024;
        sc_procedure = std::make_unique<SchemaChangeWithSorting>(chunk_changer, memory_limitation);
    } else if (sc_params.sc_directly) {
        VLOG(2) << _alter_msg_header << "doing directly schema change for base_tablet "
                << sc_params.base_tablet->full_name();
        _task_detail_msg +=
                fmt::format("[tablet: {} doing directly schema change]", sc_params.base_tablet->full_name());
        sc_procedure = std::make_unique<SchemaChangeDirectly>(chunk_changer);
    } else {
        VLOG(2) << _alter_msg_header << "doing linked schema change for base_tablet "
                << sc_params.base_tablet->full_name();
        _task_detail_msg += fmt::format("[tablet: {} doing linked schema change]", sc_params.base_tablet->full_name());
        sc_procedure = std::make_unique<LinkedSchemaChange>(chunk_changer);
    }

    if (sc_procedure == nullptr) {
        return Status::InternalError(
                fmt::format("failed to malloc SchemaChange, size: {}", sizeof(SchemaChangeWithSorting)));
    }
    sc_procedure->set_alter_msg_header(_alter_msg_header);

    Status status;
    std::vector<std::vector<DeltaColumnGroupList>> all_historical_dcgs;
    std::vector<RowsetId> new_rowset_ids;

    for (int i = 0; i < sc_params.rowset_readers.size(); ++i) {
        VLOG(3) << "begin to convert a history rowset. version=" << sc_params.rowsets_to_change[i]->version();

        TabletSharedPtr new_tablet = sc_params.new_tablet;
        TabletSharedPtr base_tablet = sc_params.base_tablet;
        RowsetWriterContext writer_context;
        writer_context.rowset_id = StorageEngine::instance()->next_rowset_id();
        writer_context.tablet_uid = new_tablet->tablet_uid();
        writer_context.tablet_id = new_tablet->tablet_id();
        writer_context.partition_id = new_tablet->partition_id();
        writer_context.tablet_schema_hash = new_tablet->schema_hash();
        writer_context.rowset_path_prefix = new_tablet->schema_hash_path();
        writer_context.tablet_schema = new_tablet->tablet_schema();
        writer_context.rowset_state = VISIBLE;
        writer_context.version = sc_params.rowsets_to_change[i]->version();
        writer_context.segments_overlap = sc_params.rowsets_to_change[i]->rowset_meta()->segments_overlap();

        if (sc_params.sc_sorting) {
            writer_context.schema_change_sorting = true;
        }

        std::unique_ptr<RowsetWriter> rowset_writer;
        status = RowsetFactory::create_rowset_writer(writer_context, &rowset_writer);
        if (!status.ok()) {
            return Status::InternalError(fmt::format("bulid rowset writer failed: {}", status.to_string()));
        }

        auto st = sc_procedure->process(sc_params.rowset_readers[i].get(), rowset_writer.get(), new_tablet, base_tablet,
                                        sc_params.rowsets_to_change[i], sc_params.base_tablet_schema);
        if (!st.ok()) {
            return st;
        }
        sc_params.rowset_readers[i]->close();
        auto new_rowset = rowset_writer->build();
        if (!new_rowset.ok()) {
            VLOG(2) << _alter_msg_header << "failed to build rowset: " << new_rowset.status() << ". exit alter process";
            _task_detail_msg +=
                    fmt::format("[fail to build rowset: {}. exit alter process]", new_rowset.status().to_string());
            break;
        }
        if (config::enable_rowset_verify) {
            RETURN_IF_ERROR((*new_rowset)->verify());
        }
        status = sc_params.new_tablet->add_rowset(*new_rowset, false);
        FAIL_POINT_TRIGGER_EXECUTE(add_rowset_already_exist,
                                   { status = Status::AlreadyExist("rowset already exist"); });
        FAIL_POINT_TRIGGER_EXECUTE(add_rowset_failed, { status = Status::InternalError("add rowset failed"); });
        if (status.is_already_exist()) {
            VLOG(2) << _alter_msg_header << "version already exist, version revert occurred. "
                    << "tablet=" << sc_params.new_tablet->full_name() << ", version='" << sc_params.version.first << "-"
                    << sc_params.version.second;
            _task_detail_msg +=
                    fmt::format("[version already exist, version revert occurred. tablet:{}, version:{}-{}]",
                                sc_params.new_tablet->full_name(), sc_params.version.first, sc_params.version.second);
            StorageEngine::instance()->add_unused_rowset(*new_rowset);
            status = Status::OK();
        } else if (!status.ok()) {
            VLOG(2) << _alter_msg_header << "failed to register new version. "
                    << " tablet=" << sc_params.new_tablet->full_name() << ", version=" << sc_params.version.first << "-"
                    << sc_params.version.second;
            _task_detail_msg +=
                    fmt::format("[fail to register new version. tablet:{}, version:{}-{}]",
                                sc_params.new_tablet->full_name(), sc_params.version.first, sc_params.version.second);
            StorageEngine::instance()->add_unused_rowset(*new_rowset);
            break;
        } else {
            VLOG(3) << "register new version. tablet=" << sc_params.new_tablet->full_name()
                    << ", version=" << sc_params.version.first << "-" << sc_params.version.second;
        }

        std::vector<DeltaColumnGroupList> historical_dcgs;
        historical_dcgs.resize(sc_params.rowsets_to_change[i]->num_segments());
        for (uint32_t j = 0; j < sc_params.rowsets_to_change[i]->num_segments(); j++) {
            int64_t tablet_id = sc_params.rowsets_to_change[i]->rowset_meta()->tablet_id();
            RowsetId rowsetid = sc_params.rowsets_to_change[i]->rowset_meta()->rowset_id();
            RETURN_IF_ERROR(TabletMetaManager::get_delta_column_group(new_tablet->data_dir()->get_meta(), tablet_id,
                                                                      rowsetid, j, INT64_MAX, &historical_dcgs[j]));
        }
        if (!sc_params.sc_sorting && !sc_params.sc_directly && chunk_changer->get_gc_exprs()->size() != 0) {
            // new added dcgs info for every segment in rowset.
            DeltaColumnGroupList dcgs;
            std::vector<int> last_dcg_counts;
            for (uint32_t j = 0; j < sc_params.rowsets_to_change[i]->num_segments(); j++) {
                // check the lastest historical_dcgs version if it is equal to schema change version
                // of the rowset. If it is, we should merge the dcg info.
                last_dcg_counts.emplace_back((historical_dcgs[j].size() != 0 &&
                                              historical_dcgs[j].front()->version() == sc_params.version.second)
                                                     ? historical_dcgs[j].front()->relative_column_files().size()
                                                     : 0);
            }

            RETURN_IF_ERROR(LinkedSchemaChange::generate_delta_column_group_and_cols(
                    new_tablet.get(), base_tablet.get(), sc_params.rowsets_to_change[i], (*new_rowset)->rowset_id(),
                    sc_params.version.second, chunk_changer, dcgs, last_dcg_counts, sc_params.base_tablet_schema));

            // merge dcg info if necessary
            if (dcgs.size() != 0) {
                if (dcgs.size() != sc_params.rowsets_to_change[i]->num_segments()) {
                    std::stringstream ss;
                    ss << "The size of dcgs and segment file in src rowset is different, "
                       << "base tablet id: " << base_tablet->tablet_id() << " "
                       << "new tablet id: " << new_tablet->tablet_id();
                    LOG(WARNING) << ss.str();
                    return Status::InternalError(ss.str());
                }
                for (uint32_t j = 0; j < dcgs.size(); j++) {
                    if (dcgs[j]->merge_into_by_version(historical_dcgs[j], new_tablet->schema_hash_path(),
                                                       (*new_rowset)->rowset_id(), j) == 0) {
                        // In this case, historical_dcgs[j] contain no suitable dcg:
                        // 1. no version of dcg in historical_dcgs[j] satisfy the src rowset version.
                        // 2. historical_dcgs[j] is empty
                        // So nothing can be merged, and we should just insert the dcgs[j] into historical_dcgs
                        historical_dcgs[j].insert(historical_dcgs[j].begin(), dcgs[j]); /* reverse order by version */
                    }
                }
                (*new_rowset)->rowset_meta()->set_partial_schema_change(true);
            }
        }
        all_historical_dcgs.emplace_back(historical_dcgs);
        new_rowset_ids.emplace_back((*new_rowset)->rowset_meta()->rowset_id());

        VLOG(10) << "succeed to convert a history version."
                 << " version=" << sc_params.version.first << "-" << sc_params.version.second;
    }

    auto data_dir = sc_params.new_tablet->data_dir();
    rocksdb::WriteBatch wb;
    RETURN_IF_ERROR(TabletMetaManager::clear_delta_column_group(data_dir, &wb, sc_params.new_tablet->tablet_id()));

    // for sorting and directly mode, cols files has been compacted even they exist before schema change.
    if (!sc_params.sc_sorting && !sc_params.sc_directly) {
        for (int i = 0; i < sc_params.rowsets_to_change.size(); ++i) {
            for (uint32_t j = 0; j < sc_params.rowsets_to_change[i]->num_segments(); j++) {
                RETURN_IF_ERROR(
                        TabletMetaManager::put_delta_column_group(data_dir, &wb, sc_params.new_tablet->tablet_id(),
                                                                  new_rowset_ids[i], j, all_historical_dcgs[i][j]));
            }
        }
    }

    status = sc_params.new_tablet->data_dir()->get_meta()->write_batch(&wb);
    if (!status.ok()) {
        LOG(WARNING) << "Fail to delete old dcg and write new dcg" << sc_params.new_tablet->tablet_id() << ": "
                     << status;
        return Status::InternalError("Fail to delete old dcg and write new dcg");
    }

    if (status.ok()) {
        status = sc_params.new_tablet->check_version_integrity(sc_params.version);
    }
    sc_params.new_tablet->update_max_continuous_version();

    VLOG(2) << _alter_msg_header << "finish converting rowsets for new_tablet from base_tablet. "
            << "base_tablet=" << sc_params.base_tablet->full_name()
            << ", new_tablet=" << sc_params.new_tablet->full_name() << ", status is " << status.to_string();
    _task_detail_msg += fmt::format("[convert rowsets status: {}]", status.to_string());

    return status;
}

Status SchemaChangeHandler::_validate_alter_result(const TabletSharedPtr& new_tablet,
                                                   const TAlterTabletReqV2& request) {
    int64_t max_continuous_version = new_tablet->max_continuous_version();
    VLOG(2) << _alter_msg_header << "find max continuous version of tablet=" << new_tablet->full_name()
            << ", version=" << max_continuous_version;
    _task_detail_msg += fmt::format("[validate alter result, tablet:{} max continuous version: {}]",
                                    new_tablet->full_name(), max_continuous_version);
    if (max_continuous_version >= request.alter_version) {
        return Status::OK();
    } else {
        return Status::InternalError(_alter_msg_header + "version missed");
    }
}

} // namespace starrocks
