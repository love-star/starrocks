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

#include <type_traits>

#include "common/logging.h"
#include "types/logical_type.h"

// Infra to build the type system:
// 1. Macro `APPLY_FOR*` to build generic codes
// 2. Function `field_type_dispatch*` to dynamic dispatch with better customlization

namespace starrocks {

#define APPLY_FOR_ALL_INT_TYPE(M) \
    M(TYPE_TINYINT)               \
    M(TYPE_SMALLINT)              \
    M(TYPE_INT)                   \
    M(TYPE_BIGINT)                \
    M(TYPE_LARGEINT)

#define APPLY_FOR_ALL_NUMBER_TYPE(M) \
    M(TYPE_TINYINT)                  \
    M(TYPE_SMALLINT)                 \
    M(TYPE_INT)                      \
    M(TYPE_BIGINT)                   \
    M(TYPE_LARGEINT)                 \
    M(TYPE_FLOAT)                    \
    M(TYPE_DOUBLE)                   \
    M(TYPE_DECIMAL32)                \
    M(TYPE_DECIMAL64)                \
    M(TYPE_DECIMAL128)               \
    M(TYPE_DECIMAL256)

#define APPLY_FOR_ALL_SCALAR_TYPE(M) \
    APPLY_FOR_ALL_NUMBER_TYPE(M)     \
    M(TYPE_DECIMALV2)                \
    M(TYPE_VARCHAR)                  \
    M(TYPE_CHAR)                     \
    M(TYPE_DATE)                     \
    M(TYPE_DATETIME)                 \
    M(TYPE_TIME)                     \
    M(TYPE_JSON)                     \
    M(TYPE_VARBINARY)                \
    M(TYPE_BOOLEAN)

#define APPLY_FOR_COMPLEX_TYPE(M) \
    M(TYPE_STRUCT)                \
    M(TYPE_MAP)                   \
    M(TYPE_ARRAY)

#define APPLY_FOR_ALL_STRING_TYPE(M) \
    M(TYPE_VARCHAR)                  \
    M(TYPE_CHAR)                     \
    M(TYPE_BINARY)                   \
    M(TYPE_VARBINARY)

#define APPLY_FOR_ALL_SCALAR_TYPE_WITH_NULL(M) \
    APPLY_FOR_ALL_SCALAR_TYPE(M)               \
    M(TYPE_NULL)

#define APPLY_FOR_COMPLEX_THRIFT_TYPE(M) \
    M(STRUCT)                            \
    M(MAP)                               \
    M(ARRAY)

#define APPLY_FOR_SCALAR_THRIFT_TYPE(M) \
    M(BOOLEAN)                          \
    M(TINYINT)                          \
    M(SMALLINT)                         \
    M(INT)                              \
    M(BIGINT)                           \
    M(FLOAT)                            \
    M(DOUBLE)                           \
    M(DATE)                             \
    M(DATETIME)                         \
    M(DECIMAL)                          \
    M(CHAR)                             \
    M(LARGEINT)                         \
    M(INT256)                           \
    M(VARCHAR)                          \
    M(HLL)                              \
    M(DECIMALV2)                        \
    M(TIME)                             \
    M(OBJECT)                           \
    M(PERCENTILE)                       \
    M(DECIMAL32)                        \
    M(DECIMAL64)                        \
    M(DECIMAL128)                       \
    M(DECIMAL256)                       \
    M(FUNCTION)                         \
    M(BINARY)                           \
    M(VARBINARY)                        \
    M(JSON)

#define APPLY_FOR_BITSET_FILTER_SUPPORTED_TYPE(M) \
    M(TYPE_BOOLEAN)                               \
    M(TYPE_TINYINT)                               \
    M(TYPE_SMALLINT)                              \
    M(TYPE_INT)                                   \
    M(TYPE_BIGINT)                                \
    M(TYPE_DECIMAL32)                             \
    M(TYPE_DECIMAL64)                             \
    M(TYPE_DATE)

#define _TYPE_DISPATCH_CASE(type) \
    case type:                    \
        return fun.template operator()<type>(args...);

// Aggregate types
template <class Functor, class... Args>
auto type_dispatch_aggregate(LogicalType ltype, Functor fun, Args... args) {
    switch (ltype) {
        APPLY_FOR_ALL_NUMBER_TYPE(_TYPE_DISPATCH_CASE)
        _TYPE_DISPATCH_CASE(TYPE_BOOLEAN)
        _TYPE_DISPATCH_CASE(TYPE_DATE)
        _TYPE_DISPATCH_CASE(TYPE_DATETIME)
        _TYPE_DISPATCH_CASE(TYPE_DECIMALV2)
    default:
        CHECK(false) << "Unknown type: " << ltype;
        __builtin_unreachable();
    }
}

// type_dispatch_*:
template <class Functor, class... Args>
auto type_dispatch_basic(LogicalType ltype, Functor fun, Args... args) {
    switch (ltype) {
        APPLY_FOR_ALL_SCALAR_TYPE_WITH_NULL(_TYPE_DISPATCH_CASE)
    default:
        CHECK(false) << "Unknown type: " << ltype;
        __builtin_unreachable();
    }
}

template <class Functor, class... Args>
auto type_dispatch_basic_and_complex_types(LogicalType ltype, Functor fun, Args... args) {
    switch (ltype) {
        APPLY_FOR_ALL_SCALAR_TYPE_WITH_NULL(_TYPE_DISPATCH_CASE)
        _TYPE_DISPATCH_CASE(TYPE_ARRAY)
        _TYPE_DISPATCH_CASE(TYPE_MAP)
        _TYPE_DISPATCH_CASE(TYPE_STRUCT)
    default:
        CHECK(false) << "Unknown type: " << ltype;
        __builtin_unreachable();
    }
}

template <class Functor, class... Args>
auto type_dispatch_all(LogicalType ltype, Functor fun, Args... args) {
    switch (ltype) {
        APPLY_FOR_ALL_SCALAR_TYPE_WITH_NULL(_TYPE_DISPATCH_CASE)
        _TYPE_DISPATCH_CASE(TYPE_ARRAY)
        _TYPE_DISPATCH_CASE(TYPE_STRUCT)
        _TYPE_DISPATCH_CASE(TYPE_MAP)
        _TYPE_DISPATCH_CASE(TYPE_HLL)
        _TYPE_DISPATCH_CASE(TYPE_OBJECT)
        _TYPE_DISPATCH_CASE(TYPE_PERCENTILE)
    default:
        CHECK(false) << "Unknown type: " << ltype;
        __builtin_unreachable();
    }
}

// Types could build into columns
template <class Functor, class... Args>
auto type_dispatch_column(LogicalType ltype, Functor fun, Args... args) {
    switch (ltype) {
        APPLY_FOR_ALL_SCALAR_TYPE_WITH_NULL(_TYPE_DISPATCH_CASE)
        _TYPE_DISPATCH_CASE(TYPE_HLL)
        _TYPE_DISPATCH_CASE(TYPE_OBJECT)
        _TYPE_DISPATCH_CASE(TYPE_PERCENTILE)
    default:
        CHECK(false) << "Unknown type: " << ltype;
        __builtin_unreachable();
    }
}

// Types which are sortable
template <class Functor, class... Args>
auto type_dispatch_sortable(LogicalType ltype, Functor fun, Args... args) {
    switch (ltype) {
        APPLY_FOR_ALL_SCALAR_TYPE(_TYPE_DISPATCH_CASE)
    default:
        CHECK(false) << "Unknown type: " << ltype;
        __builtin_unreachable();
    }
}

template <class Ret, class Functor, class... Args>
Ret type_dispatch_predicate(LogicalType ltype, bool assert, Functor fun, Args... args) {
    switch (ltype) {
        APPLY_FOR_ALL_SCALAR_TYPE(_TYPE_DISPATCH_CASE)
    default:
        if (assert) {
            CHECK(false) << "Unknown type: " << ltype;
            __builtin_unreachable();
        } else {
            return Ret{};
        }
    }
}

constexpr size_t get_num_scalar_types() {
    size_t size = 0;
#define _NUM_SCALAR_TYPES(type) size++;
    APPLY_FOR_ALL_SCALAR_TYPE(_NUM_SCALAR_TYPES)
    return size;
}
constexpr size_t NUM_SCALAR_TYPES = get_num_scalar_types();
constexpr std::array<LogicalType, NUM_SCALAR_TYPES> get_scalar_types() {
    std::array<LogicalType, NUM_SCALAR_TYPES> scalar_types;
    size_t index = 0;
#define _BUILD_SCALAR_TYPES(type) scalar_types[index++] = type;
    APPLY_FOR_ALL_SCALAR_TYPE(_BUILD_SCALAR_TYPES)

    return scalar_types;
}

template <size_t I, class Functor>
constexpr auto dispatch_helper(LogicalType ltype, Functor fun) {
    constexpr std::array<LogicalType, NUM_SCALAR_TYPES> scalar_types = get_scalar_types();
    if constexpr (I < scalar_types.size()) {
        if (ltype == scalar_types[I]) {
            return fun.template operator()<scalar_types[I]>();
        } else {
            return dispatch_helper<I + 1>(ltype, fun);
        }
    } else {
        using RetType = decltype(fun.template operator()<scalar_types[0]>());
        if constexpr (std::is_void_v<RetType>) {
            return;
        } else {
            return RetType{};
        }
    }
}

template <class Functor, class... Args>
auto scalar_type_dispatch(LogicalType ltype, Functor fun, Args... args) {
    return dispatch_helper<0>(ltype, fun);
}

template <class Functor, class Ret, class... Args>
auto type_dispatch_filter(LogicalType ltype, Ret default_value, Functor fun, Args... args) {
    switch (ltype) {
        APPLY_FOR_ALL_SCALAR_TYPE(_TYPE_DISPATCH_CASE)
    default:
        return default_value;
    }
}

template <class Functor, class Ret, class... Args>
auto type_dispatch_bitset_filter(LogicalType ltype, Ret default_value, Functor fun, Args... args) {
    switch (ltype) {
        APPLY_FOR_BITSET_FILTER_SUPPORTED_TYPE(_TYPE_DISPATCH_CASE)
    default:
        return default_value;
    }
}

#undef _TYPE_DISPATCH_CASE

} // namespace starrocks
