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

#include "storage/lake/txn_log_applier.h"

#include <fmt/format.h>

#include "gutil/strings/join.h"
#include "storage/lake/lake_primary_index.h"
#include "storage/lake/lake_primary_key_recover.h"
#include "storage/lake/meta_file.h"
#include "storage/lake/tablet.h"
#include "storage/lake/tablet_metadata.h"
#include "storage/lake/update_manager.h"
#include "testutil/sync_point.h"
#include "util/dynamic_cache.h"
#include "util/phmap/phmap_fwd_decl.h"
#include "util/trace.h"

namespace starrocks::lake {

namespace {

Status apply_alter_meta_log(TabletMetadataPB* metadata, const TxnLogPB_OpAlterMetadata& op_alter_metas,
                            TabletManager* tablet_mgr) {
    for (const auto& alter_meta : op_alter_metas.metadata_update_infos()) {
        if (alter_meta.has_enable_persistent_index()) {
            auto update_mgr = tablet_mgr->update_mgr();
            metadata->set_enable_persistent_index(alter_meta.enable_persistent_index());
            update_mgr->set_enable_persistent_index(metadata->id(), alter_meta.enable_persistent_index());
            // Try remove index from index cache
            // If tablet is doing apply rowset right now, remove primary index from index cache may be failed
            // because the primary index is available in cache
            // But it will be remove from index cache after apply is finished
            (void)update_mgr->index_cache().try_remove_by_key(metadata->id());
        }
        // Check if the alter_meta has a persistent index type change
        if (alter_meta.has_persistent_index_type()) {
            // Get the previous and new persistent index types
            PersistentIndexTypePB prev_type = metadata->persistent_index_type();
            PersistentIndexTypePB new_type = alter_meta.persistent_index_type();
            // Apply the changes to the persistent index type
            metadata->set_persistent_index_type(new_type);
            LOG(INFO) << fmt::format("alter persistent index type from {} to {} for tablet id: {}",
                                     PersistentIndexTypePB_Name(prev_type), PersistentIndexTypePB_Name(new_type),
                                     metadata->id());
            // Get the update manager
            auto update_mgr = tablet_mgr->update_mgr();
            // Try to remove the index from the index cache
            (void)update_mgr->index_cache().try_remove_by_key(metadata->id());
        }
        // update tablet meta
        // 1. rowset_to_schema is empty, maybe upgrade from old version or first time to do fast ddl. So we will
        //    add the tablet schema before alter into historical schema.
        // 2. rowset_to_schema is not empty, no need to update historical schema because we historical schema already
        //    keep the tablet schema before alter.
        if (alter_meta.has_tablet_schema()) {
            VLOG(2) << "old schema: " << metadata->schema().DebugString()
                    << " new schema: " << alter_meta.tablet_schema().DebugString();
            // add/drop field for struct column is under testing, To avoid impacting the existing logic, add the
            // `lake_enable_alter_struct` configuration. Once testing is complete, this configuration will be removed.
            if (config::lake_enable_alter_struct) {
                if (metadata->rowset_to_schema().empty() && metadata->rowsets_size() > 0) {
                    metadata->mutable_historical_schemas()->clear();
                    auto schema_id = metadata->schema().id();
                    auto& item = (*metadata->mutable_historical_schemas())[schema_id];
                    item.CopyFrom(metadata->schema());
                    for (int i = 0; i < metadata->rowsets_size(); i++) {
                        (*metadata->mutable_rowset_to_schema())[metadata->rowsets(i).id()] = schema_id;
                    }
                }
                // no need to update
            }
            metadata->mutable_schema()->CopyFrom(alter_meta.tablet_schema());
        }

        if (alter_meta.has_bundle_tablet_metadata()) {
            // do nothing
        }
    }
    return Status::OK();
}
} // namespace

class PrimaryKeyTxnLogApplier : public TxnLogApplier {
public:
    PrimaryKeyTxnLogApplier(const Tablet& tablet, MutableTabletMetadataPtr metadata, int64_t new_version,
                            bool rebuild_pindex, bool skip_write_tablet_metadata)
            : _tablet(tablet),
              _metadata(std::move(metadata)),
              _base_version(_metadata->version()),
              _new_version(new_version),
              _builder(_tablet, _metadata),
              _rebuild_pindex(rebuild_pindex) {
        _metadata->set_version(_new_version);
        _skip_write_tablet_metadata = skip_write_tablet_metadata;
    }

    ~PrimaryKeyTxnLogApplier() override { handle_failure(); }

    Status init() override { return check_meta_version(); }

    Status check_meta_version() {
        // check tablet meta
        RETURN_IF_ERROR(_tablet.update_mgr()->check_meta_version(_tablet, _base_version));
        return Status::OK();
    }

    void handle_failure() {
        if (_index_entry != nullptr && !_has_finalized) {
            // if we meet failures and have not finalized yet, have to clear primary index,
            // then we can retry again.
            // 1. unload index first
            _index_entry->value().unload();
            // 2. and then release guard
            _guard.reset(nullptr);
            // 3. remove index from cache to save resource
            _tablet.update_mgr()->remove_primary_index_cache(_index_entry);
        } else {
            _tablet.update_mgr()->release_primary_index_cache(_index_entry);
        }
        _index_entry = nullptr;
    }

    Status check_rebuild_index() {
        if (_rebuild_pindex) {
            for (const auto& sstable : _metadata->sstable_meta().sstables()) {
                FileMetaPB file_meta;
                file_meta.set_name(sstable.filename());
                file_meta.set_size(sstable.filesize());
                file_meta.set_shared(sstable.shared());
                _metadata->mutable_orphan_files()->Add(std::move(file_meta));
            }
            _metadata->clear_sstable_meta();
            _guard.reset(nullptr);
            _index_entry = nullptr;
            ASSIGN_OR_RETURN(_index_entry, _tablet.update_mgr()->rebuild_primary_index(
                                                   _metadata, &_builder, _base_version, _new_version, _guard));
            _rebuild_pindex = false;
        }
        return Status::OK();
    }

    Status apply(const TxnLogPB& log) override {
        SCOPED_THREAD_LOCAL_CHECK_MEM_LIMIT_SETTER(true);
        SCOPED_THREAD_LOCAL_SINGLETON_CHECK_MEM_TRACKER_SETTER(
                config::enable_pk_strict_memcheck ? _tablet.update_mgr()->mem_tracker() : nullptr);
        _max_txn_id = std::max(_max_txn_id, log.txn_id());
        RETURN_IF_ERROR(check_rebuild_index());
        if (log.has_op_write()) {
            RETURN_IF_ERROR(check_and_recover([&]() { return apply_write_log(log.op_write(), log.txn_id()); }));
        }
        if (log.has_op_compaction()) {
            RETURN_IF_ERROR(
                    check_and_recover([&]() { return apply_compaction_log(log.op_compaction(), log.txn_id()); }));
        }
        if (log.has_op_schema_change()) {
            RETURN_IF_ERROR(apply_schema_change_log(log.op_schema_change()));
        }
        if (log.has_op_alter_metadata()) {
            DCHECK_EQ(_base_version + 1, _new_version);
            return apply_alter_meta_log(_metadata.get(), log.op_alter_metadata(), _tablet.tablet_mgr());
        }
        if (log.has_op_replication()) {
            RETURN_IF_ERROR(apply_replication_log(log.op_replication(), log.txn_id()));
        }
        return Status::OK();
    }

    Status finish() override {
        SCOPED_THREAD_LOCAL_CHECK_MEM_LIMIT_SETTER(true);
        SCOPED_THREAD_LOCAL_SINGLETON_CHECK_MEM_TRACKER_SETTER(
                config::enable_pk_strict_memcheck ? _tablet.update_mgr()->mem_tracker() : nullptr);
        // local persistent index will update index version, so we need to load first
        // still need prepre primary index even there is an empty compaction
        if (_index_entry == nullptr &&
            (_has_empty_compaction || (_metadata->enable_persistent_index() &&
                                       _metadata->persistent_index_type() == PersistentIndexTypePB::LOCAL))) {
            // get lock to avoid gc
            _tablet.update_mgr()->lock_shard_pk_index_shard(_tablet.id());
            DeferOp defer([&]() { _tablet.update_mgr()->unlock_shard_pk_index_shard(_tablet.id()); });
            RETURN_IF_ERROR(prepare_primary_index());
        }

        // Must call `commit` before `finalize`,
        // because if `commit` or `finalize` fail, we can remove index in `handle_failure`.
        // if `_index_entry` is null, do nothing.
        if (_index_entry != nullptr) {
            RETURN_IF_ERROR(_index_entry->value().commit(_metadata, &_builder));
            _tablet.update_mgr()->index_cache().update_object_size(_index_entry, _index_entry->value().memory_usage());
        }
        _metadata->GetReflection()->MutableUnknownFields(_metadata.get())->Clear();
        RETURN_IF_ERROR(_builder.finalize(_max_txn_id, _skip_write_tablet_metadata));
        _has_finalized = true;
        return Status::OK();
    }

private:
    bool need_recover(const Status& st) { return _builder.recover_flag() != RecoverFlag::OK; }
    bool need_re_publish(const Status& st) { return _builder.recover_flag() == RecoverFlag::RECOVER_WITH_PUBLISH; }
    bool is_column_mode_partial_update(const TxnLogPB_OpWrite& op_write) const {
        // TODO support COLUMN_UPSERT_MODE
        return op_write.txn_meta().partial_update_mode() == PartialUpdateMode::COLUMN_UPDATE_MODE;
    }

    Status check_and_recover(const std::function<Status()>& publish_func) {
        auto ret = publish_func();
        if (config::enable_primary_key_recover && need_recover(ret)) {
            {
                TRACE_COUNTER_SCOPE_LATENCY_US("primary_key_recover");
                LOG(INFO) << "Primary Key recover begin, tablet_id: " << _tablet.id() << " base_ver: " << _base_version;
                // release and remove index entry's reference
                _tablet.update_mgr()->release_primary_index_cache(_index_entry);
                _guard.reset(nullptr);
                _index_entry = nullptr;
                // rebuild delvec and pk index
                LakePrimaryKeyRecover recover(&_builder, &_tablet, _metadata);
                RETURN_IF_ERROR(recover.recover());
                LOG(INFO) << "Primary Key recover finish, tablet_id: " << _tablet.id()
                          << " base_ver: " << _base_version;
            }
            if (need_re_publish(ret)) {
                _builder.set_recover_flag(RecoverFlag::OK);
                // duplicate primary key happen when prepare index, so we need to re-publish it.
                return publish_func();
            } else {
                _builder.set_recover_flag(RecoverFlag::OK);
                // No need to re-publish, make sure txn log already apply
                return Status::OK();
            }
        }
        return ret;
    }

    // We call `prepare_primary_index` only when first time we apply `write_log` or `compaction_log`, instead of
    // in `TxnLogApplier.init`, because we have to build primary index after apply `schema_change_log` finish.
    Status prepare_primary_index() {
        if (_index_entry == nullptr) {
            ASSIGN_OR_RETURN(_index_entry, _tablet.update_mgr()->prepare_primary_index(
                                                   _metadata, &_builder, _base_version, _new_version, _guard));
        }
        return Status::OK();
    }

    Status apply_write_log(const TxnLogPB_OpWrite& op_write, int64_t txn_id) {
        // get lock to avoid gc
        _tablet.update_mgr()->lock_shard_pk_index_shard(_tablet.id());
        DeferOp defer([&]() { _tablet.update_mgr()->unlock_shard_pk_index_shard(_tablet.id()); });

        if (op_write.dels_size() == 0 && op_write.rowset().num_rows() == 0 &&
            !op_write.rowset().has_delete_predicate()) {
            return Status::OK();
        }
        RETURN_IF_ERROR(prepare_primary_index());
        if (is_column_mode_partial_update(op_write)) {
            return _tablet.update_mgr()->publish_column_mode_partial_update(op_write, txn_id, _metadata, &_tablet,
                                                                            &_builder, _base_version);
        } else {
            return _tablet.update_mgr()->publish_primary_key_tablet(op_write, txn_id, _metadata, &_tablet, _index_entry,
                                                                    &_builder, _base_version);
        }
    }

    Status apply_compaction_log(const TxnLogPB_OpCompaction& op_compaction, int64_t txn_id) {
        // get lock to avoid gc
        _tablet.update_mgr()->lock_shard_pk_index_shard(_tablet.id());
        DeferOp defer([&]() { _tablet.update_mgr()->unlock_shard_pk_index_shard(_tablet.id()); });

        if (op_compaction.input_rowsets().empty()) {
            DCHECK(!op_compaction.has_output_rowset() || op_compaction.output_rowset().num_rows() == 0);
            // Apply the compaction operation to the cloud native pk index.
            // This ensures that the pk index is updated with the compaction changes.
            _builder.remove_compacted_sst(op_compaction);
            if (op_compaction.input_sstables().empty() || !op_compaction.has_output_sstable()) {
                return Status::OK();
            }
            RETURN_IF_ERROR(prepare_primary_index());
            RETURN_IF_ERROR(_index_entry->value().apply_opcompaction(*_metadata, op_compaction));
            return Status::OK();
        }
        RETURN_IF_ERROR(prepare_primary_index());
        return _tablet.update_mgr()->publish_primary_compaction(op_compaction, txn_id, *_metadata, _tablet,
                                                                _index_entry, &_builder, _base_version);
    }

    Status apply_schema_change_log(const TxnLogPB_OpSchemaChange& op_schema_change) {
        DCHECK_EQ(1, _base_version);
        DCHECK_EQ(0, _metadata->rowsets_size());
        for (const auto& rowset : op_schema_change.rowsets()) {
            DCHECK(rowset.has_id());
            auto new_rowset = _metadata->add_rowsets();
            new_rowset->CopyFrom(rowset);
            _metadata->set_next_rowset_id(new_rowset->id() + std::max(1, new_rowset->segments_size()));
        }
        if (op_schema_change.has_delvec_meta()) {
            DCHECK(op_schema_change.linked_segment());
            _metadata->mutable_delvec_meta()->CopyFrom(op_schema_change.delvec_meta());
        }
        // op_schema_change.alter_version() + 1 < _new_version means there are other logs to apply besides the current
        // schema change log.
        if (op_schema_change.alter_version() + 1 < _new_version) {
            // Save metadata before applying other transaction logs, don't bother to update primary index and
            // load delete vector here.
            _base_version = op_schema_change.alter_version();
            auto base_meta = std::make_shared<TabletMetadata>(*_metadata);
            base_meta->set_version(_base_version);
            RETURN_IF_ERROR(_tablet.put_metadata(std::move(base_meta)));
        }
        return Status::OK();
    }

    Status apply_replication_log(const TxnLogPB_OpReplication& op_replication, int64_t txn_id) {
        const auto& txn_meta = op_replication.txn_meta();

        if (txn_meta.txn_state() != ReplicationTxnStatePB::TXN_REPLICATED) {
            LOG(WARNING) << "Fail to apply replication log, invalid txn meta state: "
                         << ReplicationTxnStatePB_Name(txn_meta.txn_state());
            return Status::Corruption("Invalid txn meta state: " + ReplicationTxnStatePB_Name(txn_meta.txn_state()));
        }

        if (txn_meta.data_version() == 0) {
            if (txn_meta.snapshot_version() != _new_version) {
                LOG(WARNING) << "Fail to apply replication log, mismatched snapshot version and new version"
                             << ", snapshot version: " << txn_meta.snapshot_version()
                             << ", base version: " << txn_meta.visible_version() << ", new version: " << _new_version;
                return Status::Corruption("mismatched snapshot version and new version");
            }
        } else if (txn_meta.snapshot_version() - txn_meta.data_version() + txn_meta.visible_version() != _new_version) {
            LOG(WARNING) << "Fail to apply replication log, mismatched version, snapshot version: "
                         << txn_meta.snapshot_version() << ", data version: " << txn_meta.data_version()
                         << ", old version: " << txn_meta.visible_version() << ", new version: " << _new_version;
            return Status::Corruption("mismatched version");
        }

        if (txn_meta.incremental_snapshot()) {
            for (const auto& op_write : op_replication.op_writes()) {
                RETURN_IF_ERROR(apply_write_log(op_write, txn_id));
            }
            LOG(INFO) << "Apply pk incremental replication log finish. tablet_id: " << _tablet.id()
                      << ", base_version: " << _base_version << ", new_version: " << _new_version
                      << ", txn_id: " << txn_id;
        } else {
            auto old_rowsets = std::move(*_metadata->mutable_rowsets());
            _metadata->mutable_rowsets()->Clear();
            _metadata->mutable_delvec_meta()->Clear();

            auto new_next_rowset_id = _metadata->next_rowset_id();
            for (const auto& op_write : op_replication.op_writes()) {
                auto rowset = _metadata->add_rowsets();
                rowset->CopyFrom(op_write.rowset());
                const auto new_rowset_id = rowset->id() + _metadata->next_rowset_id();
                rowset->set_id(new_rowset_id);
                new_next_rowset_id =
                        std::max<uint32_t>(new_next_rowset_id, new_rowset_id + std::max(1, rowset->segments_size()));
            }

            for (const auto& [segment_id, delvec_data] : op_replication.delvecs()) {
                auto delvec = std::make_shared<DelVector>();
                RETURN_IF_ERROR(delvec->load(_new_version, delvec_data.data().data(), delvec_data.data().size()));
                _builder.append_delvec(delvec, segment_id + _metadata->next_rowset_id());
            }

            _metadata->set_next_rowset_id(new_next_rowset_id);
            _metadata->set_cumulative_point(0);
            old_rowsets.Swap(_metadata->mutable_compaction_inputs());

            _tablet.update_mgr()->unload_primary_index(_tablet.id());

            LOG(INFO) << "Apply pk full replication log finish. tablet_id: " << _tablet.id()
                      << ", base_version: " << _base_version << ", new_version: " << _new_version
                      << ", txn_id: " << txn_id;
        }

        if (op_replication.has_source_schema()) {
            _metadata->mutable_source_schema()->CopyFrom(op_replication.source_schema());
        }

        return Status::OK();
    }

    Tablet _tablet;
    MutableTabletMetadataPtr _metadata;
    int64_t _base_version{0};
    int64_t _new_version{0};
    int64_t _max_txn_id{0}; // Used as the file name prefix of the delvec file
    MetaFileBuilder _builder;
    DynamicCache<uint64_t, LakePrimaryIndex>::Entry* _index_entry{nullptr};
    std::unique_ptr<std::lock_guard<std::shared_timed_mutex>> _guard{nullptr};
    // True when finalize meta file success.
    bool _has_finalized = false;
    bool _rebuild_pindex = false;
};

class NonPrimaryKeyTxnLogApplier : public TxnLogApplier {
public:
    NonPrimaryKeyTxnLogApplier(const Tablet& tablet, MutableTabletMetadataPtr metadata, int64_t new_version,
                               bool skip_write_tablet_metadata)
            : _tablet(tablet), _metadata(std::move(metadata)), _new_version(new_version) {
        _skip_write_tablet_metadata = skip_write_tablet_metadata;
    }

    Status apply(const TxnLogPB& log) override {
        if (log.has_op_write()) {
            RETURN_IF_ERROR(apply_write_log(log.op_write()));
        }
        if (log.has_op_compaction()) {
            RETURN_IF_ERROR(apply_compaction_log(log.op_compaction()));
        }
        if (log.has_op_schema_change()) {
            RETURN_IF_ERROR(apply_schema_change_log(log.op_schema_change()));
        }
        if (log.has_op_replication()) {
            RETURN_IF_ERROR(apply_replication_log(log.op_replication()));
        }
        if (log.has_op_alter_metadata()) {
            return apply_alter_meta_log(_metadata.get(), log.op_alter_metadata(), _tablet.tablet_mgr());
        }
        return Status::OK();
    }

    Status finish() override {
        _metadata->GetReflection()->MutableUnknownFields(_metadata.get())->Clear();
        _metadata->set_version(_new_version);
        if (_skip_write_tablet_metadata) {
            return ExecEnv::GetInstance()->lake_tablet_manager()->cache_tablet_metadata(_metadata);
        }
        return _tablet.put_metadata(_metadata);
    }

private:
    Status apply_write_log(const TxnLogPB_OpWrite& op_write) {
        TEST_ERROR_POINT("NonPrimaryKeyTxnLogApplier::apply_write_log");
        if (op_write.has_rowset() && (op_write.rowset().num_rows() > 0 || op_write.rowset().has_delete_predicate())) {
            auto rowset = _metadata->add_rowsets();
            rowset->CopyFrom(op_write.rowset());
            rowset->set_id(_metadata->next_rowset_id());
            _metadata->set_next_rowset_id(_metadata->next_rowset_id() + std::max(1, rowset->segments_size()));
            if (!_metadata->rowset_to_schema().empty()) {
                auto schema_id = _metadata->schema().id();
                (*_metadata->mutable_rowset_to_schema())[rowset->id()] = schema_id;
                // first rowset of latest schema
                if (_metadata->historical_schemas().count(schema_id) <= 0) {
                    auto& item = (*_metadata->mutable_historical_schemas())[schema_id];
                    item.CopyFrom(_metadata->schema());
                }
            }
        }
        return Status::OK();
    }

    Status apply_compaction_log(const TxnLogPB_OpCompaction& op_compaction) {
        // It's ok to have a compaction log without input rowset and output rowset.
        if (op_compaction.input_rowsets().empty()) {
            DCHECK(!op_compaction.has_output_rowset() || op_compaction.output_rowset().num_rows() == 0);
            return Status::OK();
        }

        struct Finder {
            int64_t id;
            bool operator()(const RowsetMetadata& r) const { return r.id() == id; }
        };

        auto input_id = op_compaction.input_rowsets(0);
        auto first_input_pos = std::find_if(_metadata->mutable_rowsets()->begin(), _metadata->mutable_rowsets()->end(),
                                            Finder{input_id});
        if (UNLIKELY(first_input_pos == _metadata->mutable_rowsets()->end())) {
            LOG(INFO) << "input rowset not found";
            return Status::InternalError(fmt::format("input rowset {} not found", input_id));
        }

        // Safety check:
        // 1. All input rowsets must exist in |_metadata->rowsets()|
        // 2. Position of the input rowsets must be adjacent.
        auto pre_input_pos = first_input_pos;
        for (int i = 1, sz = op_compaction.input_rowsets_size(); i < sz; i++) {
            input_id = op_compaction.input_rowsets(i);
            auto it = std::find_if(pre_input_pos + 1, _metadata->mutable_rowsets()->end(), Finder{input_id});
            if (it == _metadata->mutable_rowsets()->end()) {
                return Status::InternalError(fmt::format("input rowset {} not exist", input_id));
            } else if (it != pre_input_pos + 1) {
                return Status::InternalError(fmt::format("input rowset position not adjacent"));
            } else {
                pre_input_pos = it;
            }
        }

        std::vector<uint32_t> input_rowsets_id(op_compaction.input_rowsets().begin(),
                                               op_compaction.input_rowsets().end());
        ASSIGN_OR_RETURN(auto tablet_schema, ExecEnv::GetInstance()->lake_tablet_manager()->get_output_rowset_schema(
                                                     input_rowsets_id, _metadata.get()));
        int64_t output_rowset_schema_id = tablet_schema->id();

        auto last_input_pos = pre_input_pos;
        RowsetMetadataPB last_input_rowset = *last_input_pos;
        trim_partial_compaction_last_input_rowset(_metadata, op_compaction, last_input_rowset);

        const auto end_input_pos = pre_input_pos + 1;
        for (auto iter = first_input_pos; iter != end_input_pos; ++iter) {
            if (iter != last_input_pos) {
                _metadata->mutable_compaction_inputs()->Add(std::move(*iter));
            } else {
                // might be a partial compaction, use real last input rowset
                _metadata->mutable_compaction_inputs()->Add(std::move(last_input_rowset));
            }
        }

        auto first_idx = static_cast<uint32_t>(first_input_pos - _metadata->mutable_rowsets()->begin());
        bool has_output_rowset = false;
        uint32_t output_rowset_id = 0;
        if (op_compaction.has_output_rowset() && op_compaction.output_rowset().num_rows() > 0) {
            // Replace the first input rowset with output rowset
            auto output_rowset = _metadata->mutable_rowsets(first_idx);
            output_rowset->CopyFrom(op_compaction.output_rowset());
            output_rowset->set_id(_metadata->next_rowset_id());
            _metadata->set_next_rowset_id(_metadata->next_rowset_id() + output_rowset->segments_size());
            ++first_input_pos;
            has_output_rowset = true;
            output_rowset_id = output_rowset->id();
        }
        // Erase input rowsets from _metadata
        _metadata->mutable_rowsets()->erase(first_input_pos, end_input_pos);

        // Update historical schema and rowset schema id
        if (!_metadata->rowset_to_schema().empty()) {
            for (int i = 0; i < op_compaction.input_rowsets_size(); i++) {
                _metadata->mutable_rowset_to_schema()->erase(op_compaction.input_rowsets(i));
            }

            if (has_output_rowset) {
                (*_metadata->mutable_rowset_to_schema())[output_rowset_id] = output_rowset_schema_id;
            }

            std::unordered_set<int64_t> schema_id;
            for (auto& pair : _metadata->rowset_to_schema()) {
                schema_id.insert(pair.second);
            }

            for (auto it = _metadata->mutable_historical_schemas()->begin();
                 it != _metadata->mutable_historical_schemas()->end();) {
                if (schema_id.find(it->first) == schema_id.end()) {
                    it = _metadata->mutable_historical_schemas()->erase(it);
                } else {
                    it++;
                }
            }
        }

        // Set new cumulative point
        uint32_t new_cumulative_point = 0;
        // size tiered compaction policy does not need cumulative point
        if (!config::enable_size_tiered_compaction_strategy) {
            if (first_idx >= _metadata->cumulative_point()) {
                // cumulative compaction
                new_cumulative_point = first_idx;
            } else if (_metadata->cumulative_point() >= op_compaction.input_rowsets_size()) {
                // base compaction
                new_cumulative_point = _metadata->cumulative_point() - op_compaction.input_rowsets_size();
            }
            if (op_compaction.has_output_rowset() && op_compaction.output_rowset().num_rows() > 0) {
                ++new_cumulative_point;
            }
            if (new_cumulative_point > _metadata->rowsets_size()) {
                return Status::InternalError(fmt::format("new cumulative point: {} exceeds rowset size: {}",
                                                         new_cumulative_point, _metadata->rowsets_size()));
            }
        }
        _metadata->set_cumulative_point(new_cumulative_point);

        // Debug new tablet metadata
        std::vector<uint32_t> rowset_ids;
        std::vector<uint32_t> delete_rowset_ids;
        for (const auto& rowset : _metadata->rowsets()) {
            rowset_ids.emplace_back(rowset.id());
            if (rowset.has_delete_predicate()) {
                delete_rowset_ids.emplace_back(rowset.id());
            }
        }
        LOG(INFO) << "Compaction finish. tablet: " << _metadata->id() << ", version: " << _metadata->version()
                  << ", cumulative point: " << _metadata->cumulative_point() << ", rowsets: ["
                  << JoinInts(rowset_ids, ",") << "]"
                  << ", delete rowsets: [" << JoinInts(delete_rowset_ids, ",") + "]";
        return Status::OK();
    }

    Status apply_schema_change_log(const TxnLogPB_OpSchemaChange& op_schema_change) {
        TEST_ERROR_POINT("NonPrimaryKeyTxnLogApplier::apply_schema_change_log");
        DCHECK_EQ(0, _metadata->rowsets_size());
        for (const auto& rowset : op_schema_change.rowsets()) {
            DCHECK(rowset.has_id());
            auto new_rowset = _metadata->add_rowsets();
            new_rowset->CopyFrom(rowset);
            _metadata->set_next_rowset_id(new_rowset->id() + std::max(1, new_rowset->segments_size()));
        }
        DCHECK(!op_schema_change.has_delvec_meta());
        return Status::OK();
    }

    Status apply_replication_log(const TxnLogPB_OpReplication& op_replication) {
        const auto& txn_meta = op_replication.txn_meta();

        if (txn_meta.txn_state() != ReplicationTxnStatePB::TXN_REPLICATED) {
            LOG(WARNING) << "Fail to apply replication log, invalid txn meta state: "
                         << ReplicationTxnStatePB_Name(txn_meta.txn_state());
            return Status::Corruption("Invalid txn meta state: " + ReplicationTxnStatePB_Name(txn_meta.txn_state()));
        }

        if (txn_meta.data_version() == 0) {
            if (txn_meta.snapshot_version() != _new_version) {
                LOG(WARNING) << "Fail to apply replication log, mismatched snapshot version and new version"
                             << ", snapshot version: " << txn_meta.snapshot_version()
                             << ", base version: " << txn_meta.visible_version() << ", new version: " << _new_version;
                return Status::Corruption("mismatched snapshot version and new version");
            }
        } else if (txn_meta.snapshot_version() - txn_meta.data_version() + txn_meta.visible_version() != _new_version) {
            LOG(WARNING) << "Fail to apply replication log, mismatched version, snapshot version: "
                         << txn_meta.snapshot_version() << ", data version: " << txn_meta.data_version()
                         << ", old version: " << txn_meta.visible_version() << ", new version: " << _new_version;
            return Status::Corruption("mismatched version");
        }

        if (txn_meta.incremental_snapshot()) {
            for (const auto& op_write : op_replication.op_writes()) {
                RETURN_IF_ERROR(apply_write_log(op_write));
            }
            LOG(INFO) << "Apply incremental replication log finish. tablet_id: " << _tablet.id()
                      << ", base_version: " << _metadata->version() << ", new_version: " << _new_version
                      << ", txn_id: " << txn_meta.txn_id();
        } else {
            auto old_rowsets = std::move(*_metadata->mutable_rowsets());
            _metadata->mutable_rowsets()->Clear();

            for (const auto& op_write : op_replication.op_writes()) {
                RETURN_IF_ERROR(apply_write_log(op_write));
            }

            _metadata->set_cumulative_point(0);
            old_rowsets.Swap(_metadata->mutable_compaction_inputs());

            LOG(INFO) << "Apply full replication log finish. tablet_id: " << _tablet.id()
                      << ", base_version: " << _metadata->version() << ", new_version: " << _new_version
                      << ", txn_id: " << txn_meta.txn_id();
        }

        if (op_replication.has_source_schema()) {
            _metadata->mutable_source_schema()->CopyFrom(op_replication.source_schema());
        }

        return Status::OK();
    }

    Tablet _tablet;
    MutableTabletMetadataPtr _metadata;
    int64_t _new_version;
    bool _skip_write_tablet_metadata;
};

std::unique_ptr<TxnLogApplier> new_txn_log_applier(const Tablet& tablet, MutableTabletMetadataPtr metadata,
                                                   int64_t new_version, bool rebuild_pindex,
                                                   bool skip_write_tablet_metadata) {
    if (metadata->schema().keys_type() == PRIMARY_KEYS) {
        return std::make_unique<PrimaryKeyTxnLogApplier>(tablet, std::move(metadata), new_version, rebuild_pindex,
                                                         skip_write_tablet_metadata);
    }
    return std::make_unique<NonPrimaryKeyTxnLogApplier>(tablet, std::move(metadata), new_version,
                                                        skip_write_tablet_metadata);
}

} // namespace starrocks::lake
