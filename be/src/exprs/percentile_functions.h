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

#include "exprs/function_helper.h"

namespace starrocks {
class PercentileFunctions {
public:
    /**
     * @param:
     * @paramType columns: [TYPE_DOUBLE]
     * @return TYPE_PERCENTILE
     */
    DEFINE_VECTORIZED_FN(percentile_hash);

    /**
     * @param:
     * @paramType columns: []
     * @return TYPE_PERCENTILE
     */
    DEFINE_VECTORIZED_FN(percentile_empty);

    /**
     * @param:
     * @paramType columns: [TYPE_PERCENTILE, TYPE_DOUBLE]
     * @return TYPE_DOUBLE
     */
    DEFINE_VECTORIZED_FN(percentile_approx_raw);
};
} // namespace starrocks
