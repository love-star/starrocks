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

#include "exec/schema_scanner/schema_be_tablets_scanner.h"

#include "agent/master_info.h"
#include "exec/schema_scanner/schema_helper.h"
#include "gen_cpp/Types_types.h" // for TStorageMedium::type
#include "gutil/strings/substitute.h"
#include "storage/storage_engine.h"
#include "storage/tablet.h"
#include "storage/tablet_manager.h"

namespace starrocks {

namespace {
std::set<int64_t> get_authorized_table_ids(const TGetTablesConfigResponse& _tables_config_response) {
    std::set<int64_t> authorized_table_ids;
    for (const auto& v : _tables_config_response.tables_config_infos) {
        if (v.__isset.table_id) {
            authorized_table_ids.insert(v.table_id);
        }
    }

    return authorized_table_ids;
}
} // namespace

SchemaScanner::ColumnDesc SchemaBeTabletsScanner::_s_columns[] = {
        {"BE_ID", TypeDescriptor::from_logical_type(TYPE_BIGINT), sizeof(int64_t), false},
        {"TABLE_ID", TypeDescriptor::from_logical_type(TYPE_BIGINT), sizeof(int64_t), false},
        {"PARTITION_ID", TypeDescriptor::from_logical_type(TYPE_BIGINT), sizeof(int64_t), false},
        {"TABLET_ID", TypeDescriptor::from_logical_type(TYPE_BIGINT), sizeof(int64_t), false},
        {"NUM_VERSION", TypeDescriptor::from_logical_type(TYPE_BIGINT), sizeof(int64_t), false},
        {"MAX_VERSION", TypeDescriptor::from_logical_type(TYPE_BIGINT), sizeof(int64_t), false},
        {"MIN_VERSION", TypeDescriptor::from_logical_type(TYPE_BIGINT), sizeof(int64_t), false},
        {"NUM_ROWSET", TypeDescriptor::from_logical_type(TYPE_BIGINT), sizeof(int64_t), false},
        {"NUM_ROW", TypeDescriptor::from_logical_type(TYPE_BIGINT), sizeof(int64_t), false},
        {"DATA_SIZE", TypeDescriptor::from_logical_type(TYPE_BIGINT), sizeof(int64_t), false},
        {"INDEX_MEM", TypeDescriptor::from_logical_type(TYPE_BIGINT), sizeof(int64_t), false},
        {"CREATE_TIME", TypeDescriptor::from_logical_type(TYPE_BIGINT), sizeof(int64_t), false},
        {"STATE", TypeDescriptor::create_varchar_type(sizeof(Slice)), sizeof(Slice), false},
        {"TYPE", TypeDescriptor::create_varchar_type(sizeof(Slice)), sizeof(Slice), false},
        {"DATA_DIR", TypeDescriptor::create_varchar_type(sizeof(Slice)), sizeof(Slice), false},
        {"SHARD_ID", TypeDescriptor::from_logical_type(TYPE_BIGINT), sizeof(int64_t), false},
        {"SCHEMA_HASH", TypeDescriptor::from_logical_type(TYPE_BIGINT), sizeof(int64_t), false},
        {"INDEX_DISK", TypeDescriptor::from_logical_type(TYPE_BIGINT), sizeof(int64_t), false},
        {"MEDIUM_TYPE", TypeDescriptor::create_varchar_type(sizeof(Slice)), sizeof(Slice), false},
        {"NUM_SEGMENT", TypeDescriptor::from_logical_type(TYPE_BIGINT), sizeof(int64_t), false},
};

SchemaBeTabletsScanner::SchemaBeTabletsScanner()
        : SchemaScanner(_s_columns, sizeof(_s_columns) / sizeof(SchemaScanner::ColumnDesc)) {}

SchemaBeTabletsScanner::~SchemaBeTabletsScanner() = default;

Status SchemaBeTabletsScanner::start(RuntimeState* state) {
    if (!_is_init) {
        return Status::InternalError("used before initialized.");
    }
    TAuthInfo auth_info;
    if (nullptr != _param->db) {
        auth_info.__set_pattern(*(_param->db));
    }
    if (nullptr != _param->current_user_ident) {
        auth_info.__set_current_user_ident(*(_param->current_user_ident));
    } else {
        if (nullptr != _param->user) {
            auth_info.__set_user(*(_param->user));
        }
        if (nullptr != _param->user_ip) {
            auth_info.__set_user_ip(*(_param->user_ip));
        }
    }
    TGetTablesConfigRequest tables_config_req;
    tables_config_req.__set_auth_info(auth_info);

    RETURN_IF_ERROR(SchemaScanner::init_schema_scanner_state(state));
    RETURN_IF_ERROR(SchemaHelper::get_tables_config(_ss_state, tables_config_req, &_tables_config_response));

    // we only show tablets when the user has any privilege on the corresponding table
    // first get the table ids on which the current user has privilege
    auto authorized_table_ids = get_authorized_table_ids(_tables_config_response);

    auto o_id = get_backend_id();
    _be_id = o_id.has_value() ? o_id.value() : -1;
    _infos.clear();
    auto manager = StorageEngine::instance()->tablet_manager();
    manager->get_tablets_basic_infos(_param->table_id, _param->partition_id, _param->tablet_id, _infos,
                                     &authorized_table_ids);
    LOG(INFO) << strings::Substitute("get_tablets_basic_infos table_id:$0 partition:$1 tablet:$2 #info:$3",
                                     _param->table_id, _param->partition_id, _param->tablet_id, _infos.size());
    _cur_idx = 0;
    return Status::OK();
}

static const char* tablet_state_to_string(TabletState state) {
    switch (state) {
    case TabletState::TABLET_NOTREADY:
        return "NOTREADY";
    case TabletState::TABLET_RUNNING:
        return "RUNNING";
    case TabletState::TABLET_TOMBSTONED:
        return "TOMBSTONED";
    case TabletState::TABLET_STOPPED:
        return "STOPPED";
    case TabletState::TABLET_SHUTDOWN:
        return "SHUTDOWN";
    default:
        return "UNKNOWN";
    }
}

static const char* keys_type_to_string(KeysType type) {
    switch (type) {
    case KeysType::DUP_KEYS:
        return "DUP";
    case KeysType::UNIQUE_KEYS:
        return "UNIQUE";
    case KeysType::AGG_KEYS:
        return "AGG";
    case KeysType::PRIMARY_KEYS:
        return "PRIMARY";
    default:
        return "UNKNOWN";
    }
}

static const char* medium_type_to_string(TStorageMedium::type type) {
    switch (type) {
    case TStorageMedium::SSD:
        return "SSD";
    case TStorageMedium::HDD:
        return "HDD";
    default:
        return "UNKNOWN";
    }
}

Status SchemaBeTabletsScanner::fill_chunk(ChunkPtr* chunk) {
    const auto& slot_id_to_index_map = (*chunk)->get_slot_id_to_index_map();
    auto end = _cur_idx + 1;
    for (; _cur_idx < end; _cur_idx++) {
        auto& info = _infos[_cur_idx];
        for (const auto& [slot_id, index] : slot_id_to_index_map) {
            if (slot_id < 1 || slot_id > 20) {
                return Status::InternalError(strings::Substitute("invalid slot id:$0", slot_id));
            }
            ColumnPtr column = (*chunk)->get_column_by_slot_id(slot_id);
            switch (slot_id) {
            case 1: {
                // be id
                fill_column_with_slot<TYPE_BIGINT>(column.get(), (void*)&_be_id);
                break;
            }
            case 2: {
                // table id
                fill_column_with_slot<TYPE_BIGINT>(column.get(), (void*)&info.table_id);
                break;
            }
            case 3: {
                // partition id
                fill_column_with_slot<TYPE_BIGINT>(column.get(), (void*)&info.partition_id);
                break;
            }
            case 4: {
                // tablet id
                fill_column_with_slot<TYPE_BIGINT>(column.get(), (void*)&info.tablet_id);
                break;
            }
            case 5: {
                // num version
                fill_column_with_slot<TYPE_BIGINT>(column.get(), (void*)&info.num_version);
                break;
            }
            case 6: {
                // max version
                fill_column_with_slot<TYPE_BIGINT>(column.get(), (void*)&info.max_version);
                break;
            }
            case 7: {
                // min version
                fill_column_with_slot<TYPE_BIGINT>(column.get(), (void*)&info.min_version);
                break;
            }
            case 8: {
                // num rowset
                fill_column_with_slot<TYPE_BIGINT>(column.get(), (void*)&info.num_rowset);
                break;
            }
            case 9: {
                // num rows
                fill_column_with_slot<TYPE_BIGINT>(column.get(), (void*)&info.num_row);
                break;
            }
            case 10: {
                // data size
                fill_column_with_slot<TYPE_BIGINT>(column.get(), (void*)&info.data_size);
                break;
            }
            case 11: {
                // index mem
                fill_column_with_slot<TYPE_BIGINT>(column.get(), (void*)&info.index_mem);
                break;
            }
            case 12: {
                // create time
                fill_column_with_slot<TYPE_BIGINT>(column.get(), (void*)&info.create_time);
                break;
            }
            case 13: {
                // state
                Slice state = Slice(tablet_state_to_string((TabletState)info.state));
                fill_column_with_slot<TYPE_VARCHAR>(column.get(), (void*)&state);
                break;
            }
            case 14: {
                // type
                Slice type = Slice(keys_type_to_string((KeysType)info.type));
                fill_column_with_slot<TYPE_VARCHAR>(column.get(), (void*)&type);
                break;
            }
            case 15: {
                // DATA_DIR
                Slice data_dir = Slice(info.data_dir);
                fill_column_with_slot<TYPE_VARCHAR>(column.get(), (void*)&data_dir);
                break;
            }
            case 16: {
                // SHARD_ID
                fill_column_with_slot<TYPE_BIGINT>(column.get(), (void*)&info.shard_id);
                break;
            }
            case 17: {
                // SCHEMA_HASH
                fill_column_with_slot<TYPE_BIGINT>(column.get(), (void*)&info.schema_hash);
                break;
            }
            case 18: {
                // INDEX_DISK
                fill_column_with_slot<TYPE_BIGINT>(column.get(), (void*)&info.index_disk_usage);
                break;
            }
            case 19: {
                // medium type
                Slice medium_type = Slice(medium_type_to_string(info.medium_type));
                fill_column_with_slot<TYPE_VARCHAR>(column.get(), (void*)&medium_type);
                break;
            }
            case 20: {
                // NUM_SEGMENT
                fill_column_with_slot<TYPE_BIGINT>(column.get(), (void*)&info.num_segment);
            }
            default:
                break;
            }
        }
    }
    return Status::OK();
}

Status SchemaBeTabletsScanner::get_next(ChunkPtr* chunk, bool* eos) {
    if (!_is_init) {
        return Status::InternalError("call this before initial.");
    }
    if (_cur_idx >= _infos.size()) {
        *eos = true;
        return Status::OK();
    }
    if (nullptr == chunk || nullptr == eos) {
        return Status::InternalError("invalid parameter.");
    }
    *eos = false;
    return fill_chunk(chunk);
}

} // namespace starrocks
