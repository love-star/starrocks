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

#include "formats/parquet/encoding.h"

#include <iterator>
#include <memory>
#include <string>
#include <unordered_map>
#include <utility>

#include "formats/parquet/encoding_bss.h"
#include "formats/parquet/encoding_delta.h"
#include "formats/parquet/encoding_dict.h"
#include "formats/parquet/encoding_plain.h"
#include "formats/parquet/types.h"
#include "gutil/strings/substitute.h"

namespace starrocks::parquet {

Status Decoder::next_batch_with_nulls(size_t count, const NullInfos& null_infos, ColumnContentType content_type,
                                      Column* dst, const FilterData* filter) {
    const uint8_t* __restrict is_nulls = null_infos.nulls_data();
    size_t idx = 0;
    size_t off = 0;
    while (idx < count) {
        bool is_null = is_nulls[idx++];
        size_t run = 1;
        while (idx < count && is_nulls[idx] == is_null) {
            idx++;
            run++;
        }
        if (is_null) {
            dst->append_nulls(run);
        } else {
            const FilterData* forward_filter = filter ? filter + off : filter;
            RETURN_IF_ERROR(this->next_batch(run, content_type, dst, forward_filter));
        }
        off += run;
    }
    return Status::OK();
}
void Decoder::_next_null_column(size_t count, const NullInfos& null_infos, NullableColumn* dst) {
    size_t null_cnt = null_infos.num_nulls;
    NullColumn* null_column = down_cast<NullableColumn*>(dst)->mutable_null_column();
    const uint8_t* __restrict is_nulls = null_infos.nulls_data();
    auto& null_data = null_column->get_data();
    size_t prev_num_rows = null_data.size();
    raw::stl_vector_resize_uninitialized(&null_data, count + prev_num_rows);
    uint8_t* __restrict__ dst_nulls = null_data.data() + prev_num_rows;
    memcpy(dst_nulls, is_nulls, count);
    down_cast<NullableColumn*>(dst)->set_has_null(null_cnt > 0);
}

using TypeEncodingPair = std::pair<tparquet::Type::type, tparquet::Encoding::type>;

struct EncodingMapHash {
    size_t operator()(const TypeEncodingPair& pair) const { return (pair.first << 5) ^ pair.second; }
};

template <tparquet::Type::type type, tparquet::Encoding::type encoding>
struct TypeEncodingTraits {};

template <tparquet::Type::type type>
struct TypeEncodingTraits<type, tparquet::Encoding::PLAIN> {
    static Status create_decoder(std::unique_ptr<Decoder>* decoder) {
        decoder->reset(new PlainDecoder<typename PhysicalTypeTraits<type>::CppType>());
        return Status::OK();
    }
    static Status create_encoder(std::unique_ptr<Encoder>* encoder) {
        encoder->reset(new PlainEncoder<typename PhysicalTypeTraits<type>::CppType>());
        return Status::OK();
    }
};

template <tparquet::Type::type type>
struct TypeEncodingTraits<type, tparquet::Encoding::RLE_DICTIONARY> {
    static Status create_decoder(std::unique_ptr<Decoder>* decoder) {
        decoder->reset(new DictDecoder<typename PhysicalTypeTraits<type>::CppType>());
        return Status::OK();
    }
    static Status create_encoder(std::unique_ptr<Encoder>* encoder) {
        encoder->reset(new DictEncoder<typename PhysicalTypeTraits<type>::CppType>());
        return Status::OK();
    }
};

template <tparquet::Type::type type>
struct TypeEncodingTraits<type, tparquet::Encoding::DELTA_BINARY_PACKED> {
    static Status create_decoder(std::unique_ptr<Decoder>* decoder) {
        decoder->reset(new DeltaBinaryPackedDecoder<typename PhysicalTypeTraits<type>::CppType>());
        return Status::OK();
    }
    static Status create_encoder(std::unique_ptr<Encoder>* encoder) {
        encoder->reset(new DeltaBinaryPackedEncoder<typename PhysicalTypeTraits<type>::CppType>());
        return Status::OK();
    }
};

template <tparquet::Type::type type>
struct TypeEncodingTraits<type, tparquet::Encoding::DELTA_LENGTH_BYTE_ARRAY> {
    static Status create_decoder(std::unique_ptr<Decoder>* decoder) {
        *decoder = std::make_unique<DeltaLengthByteArrayDecoder<type>>();
        return Status::OK();
    }
    static Status create_encoder(std::unique_ptr<Encoder>* encoder) {
        *encoder = std::make_unique<DeltaLengthByteArrayEncoder>();
        return Status::OK();
    }
};

template <tparquet::Type::type type>
struct TypeEncodingTraits<type, tparquet::Encoding::DELTA_BYTE_ARRAY> {
    static Status create_decoder(std::unique_ptr<Decoder>* decoder) {
        *decoder = std::make_unique<DeltaByteArrayDecoder>();
        return Status::OK();
    }
    static Status create_encoder(std::unique_ptr<Encoder>* encoder) {
        *encoder = std::make_unique<DeltaByteArrayEncoder>();
        return Status::OK();
    }
};

template <tparquet::Type::type type>
struct TypeEncodingTraits<type, tparquet::Encoding::BYTE_STREAM_SPLIT> {
    static Status create_decoder(std::unique_ptr<Decoder>* decoder) {
        decoder->reset(new ByteStreamSplitDecoder<type>());
        return Status::OK();
    }
    static Status create_encoder(std::unique_ptr<Encoder>* encoder) {
        encoder->reset(new ByteStreamSplitEncoder<type>());
        return Status::OK();
    }
};

template <>
struct TypeEncodingTraits<tparquet::Type::FIXED_LEN_BYTE_ARRAY, tparquet::Encoding::PLAIN> {
    static Status create_decoder(std::unique_ptr<Decoder>* decoder) {
        *decoder = std::make_unique<FLBAPlainDecoder>();
        return Status::OK();
    }
    static Status create_encoder(std::unique_ptr<Encoder>* encoder) {
        *encoder = std::make_unique<FLBAPlainEncoder>();
        return Status::OK();
    }
};

template <tparquet::Type::type type_arg, tparquet::Encoding::type encoding_arg>
struct EncodingTraits : TypeEncodingTraits<type_arg, encoding_arg> {
    static constexpr tparquet::Type::type type = type_arg;
    static constexpr tparquet::Encoding::type encoding = encoding_arg;
};

class EncodingInfoResolver {
public:
    EncodingInfoResolver();
    ~EncodingInfoResolver();

    Status get(tparquet::Type::type data_type, tparquet::Encoding::type encoding_type, const EncodingInfo** out);

private:
    // Not thread-safe
    template <tparquet::Type::type type, tparquet::Encoding::type encoding>
    void _add_map() {
        auto key = std::make_pair(type, encoding);
        auto it = _encoding_map.find(key);
        if (it != _encoding_map.end()) {
            return;
        }
        EncodingTraits<type, encoding> traits;
        std::unique_ptr<EncodingInfo> info(new EncodingInfo(traits));
        _encoding_map.emplace(key, info.release());
    }

    std::unordered_map<TypeEncodingPair, EncodingInfo*, EncodingMapHash> _encoding_map;
};

EncodingInfoResolver::EncodingInfoResolver() {
    // BOOL
    _add_map<tparquet::Type::BOOLEAN, tparquet::Encoding::PLAIN>();
    // INT32
    _add_map<tparquet::Type::INT32, tparquet::Encoding::PLAIN>();
    _add_map<tparquet::Type::INT32, tparquet::Encoding::RLE_DICTIONARY>();
    _add_map<tparquet::Type::INT32, tparquet::Encoding::DELTA_BINARY_PACKED>();
    _add_map<tparquet::Type::INT32, tparquet::Encoding::BYTE_STREAM_SPLIT>();

    // INT64
    _add_map<tparquet::Type::INT64, tparquet::Encoding::PLAIN>();
    _add_map<tparquet::Type::INT64, tparquet::Encoding::RLE_DICTIONARY>();
    _add_map<tparquet::Type::INT64, tparquet::Encoding::DELTA_BINARY_PACKED>();
    _add_map<tparquet::Type::INT64, tparquet::Encoding::BYTE_STREAM_SPLIT>();

    // INT96
    _add_map<tparquet::Type::INT96, tparquet::Encoding::PLAIN>();
    _add_map<tparquet::Type::INT96, tparquet::Encoding::RLE_DICTIONARY>();

    // FLOAT
    _add_map<tparquet::Type::FLOAT, tparquet::Encoding::PLAIN>();
    _add_map<tparquet::Type::FLOAT, tparquet::Encoding::RLE_DICTIONARY>();
    _add_map<tparquet::Type::FLOAT, tparquet::Encoding::BYTE_STREAM_SPLIT>();

    // DOUBLE
    _add_map<tparquet::Type::DOUBLE, tparquet::Encoding::PLAIN>();
    _add_map<tparquet::Type::DOUBLE, tparquet::Encoding::RLE_DICTIONARY>();
    _add_map<tparquet::Type::DOUBLE, tparquet::Encoding::BYTE_STREAM_SPLIT>();

    // BYTE_ARRAY encoding
    _add_map<tparquet::Type::BYTE_ARRAY, tparquet::Encoding::PLAIN>();
    _add_map<tparquet::Type::BYTE_ARRAY, tparquet::Encoding::RLE_DICTIONARY>();
    _add_map<tparquet::Type::BYTE_ARRAY, tparquet::Encoding::DELTA_LENGTH_BYTE_ARRAY>();
    _add_map<tparquet::Type::BYTE_ARRAY, tparquet::Encoding::DELTA_BYTE_ARRAY>();

    // FIXED_LEN_BYTE_ARRAY encoding
    _add_map<tparquet::Type::FIXED_LEN_BYTE_ARRAY, tparquet::Encoding::PLAIN>();
    _add_map<tparquet::Type::FIXED_LEN_BYTE_ARRAY, tparquet::Encoding::RLE_DICTIONARY>();
    _add_map<tparquet::Type::FIXED_LEN_BYTE_ARRAY, tparquet::Encoding::DELTA_LENGTH_BYTE_ARRAY>();
    _add_map<tparquet::Type::FIXED_LEN_BYTE_ARRAY, tparquet::Encoding::DELTA_BYTE_ARRAY>();
    _add_map<tparquet::Type::FIXED_LEN_BYTE_ARRAY, tparquet::Encoding::BYTE_STREAM_SPLIT>();
}

EncodingInfoResolver::~EncodingInfoResolver() {
    for (auto& it : _encoding_map) {
        delete it.second;
    }
    _encoding_map.clear();
}

Status EncodingInfoResolver::get(tparquet::Type::type type, tparquet::Encoding::type encoding,
                                 const EncodingInfo** out) {
    auto key = std::make_pair(type, encoding);
    auto it = _encoding_map.find(key);
    if (it == std::end(_encoding_map)) {
        return Status::InternalError(strings::Substitute("fail to find valid type encoding, type:$0, encoding:$1",
                                                         ::tparquet::to_string(type), ::tparquet::to_string(encoding)));
    }
    *out = it->second;
    return Status::OK();
}

static EncodingInfoResolver s_encoding_info_resolver;

template <typename TraitsClass>
EncodingInfo::EncodingInfo(TraitsClass traits)
        : _create_decoder_func(TraitsClass::create_decoder),
          _create_encoder_func(TraitsClass::create_encoder),
          _type(TraitsClass::type),
          _encoding(TraitsClass::encoding) {}

Status EncodingInfo::get(tparquet::Type::type type, tparquet::Encoding::type encoding, const EncodingInfo** out) {
    return s_encoding_info_resolver.get(type, encoding, out);
}

} // namespace starrocks::parquet
