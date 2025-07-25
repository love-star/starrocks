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
//   https://github.com/apache/incubator-doris/blob/master/be/src/util/string_parser.hpp

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

#pragma once

#include <fast_float/fast_float.h>

#include <cmath>
#include <cstdint>
#include <cstring>
#include <limits>
#include <string>
#include <type_traits>

#include "common/compiler_util.h"
#include "common/status.h"
#include "types/logical_type.h"
#include "util/decimal_types.h"

namespace starrocks {

// Utility functions for doing atoi/atof on non-null terminated strings.  On micro benchmarks,
// this is significantly faster than libc (atoi/strtol and atof/strtod).
//
// Strings with leading and trailing whitespaces are accepted.
// Branching is heavily optimized for the non-whitespace successful case.
// All the StringTo* functions first parse the input string assuming it has no leading whitespace.
// If that first attempt was unsuccessful, these functions retry the parsing after removing
// whitespace. Therefore, strings with whitespace take a perf hit on branch mis-prediction.
//
// For overflows, we are following the mysql behavior, to cap values at the max/min value for that
// data type.  This is different from hive, which returns NULL for overflow slots for int types
// and inf/-inf for float types.
//
// Things we tried that did not work:
//  - lookup table for converting character to digit
// Improvements (TODO):
//  - Validate input using _sidd_compare_ranges
//  - Since we know the length, we can parallelize this: i.e. result = 100*s[0] + 10*s[1] + s[2]
class StringParser {
public:
    enum ParseResult { PARSE_SUCCESS = 0, PARSE_FAILURE, PARSE_OVERFLOW, PARSE_UNDERFLOW };

    template <typename T>
    class StringParseTraits {
    public:
        /// Returns the maximum ascii string length for this type.
        /// e.g. the max/min int8_t has 3 characters.
        static int max_ascii_len();
    };

    template <typename T>
    static T numeric_limits(bool negative);

    // This is considerably faster than glibc's implementation (25x).
    // In the case of overflow, the max/min value for the data type will be returned.
    // Assumes s represents a decimal number.
    template <typename T>
    static inline T string_to_int(const char* s, int len, ParseResult* result) {
        T ans = string_to_int_internal<T>(s, len, result);
        if (LIKELY(*result == PARSE_SUCCESS)) {
            return ans;
        }

        int i = skip_leading_whitespace(s, len);
        return string_to_int_internal<T>(s + i, len - i, result);
    }

    // This is considerably faster than glibc's implementation.
    // In the case of overflow, the max/min value for the data type will be returned.
    // Assumes s represents a decimal number.
    template <typename T>
    static inline T string_to_unsigned_int(const char* s, int len, ParseResult* result) {
        T ans = string_to_unsigned_int_internal<T>(s, len, result);
        if (LIKELY(*result == PARSE_SUCCESS)) {
            return ans;
        }

        int i = skip_leading_whitespace(s, len);
        return string_to_unsigned_int_internal<T>(s + i, len - i, result);
    }

    // Convert a string s representing a number in given base into a decimal number.
    template <typename T>
    static inline T string_to_int(const char* s, int len, int base, ParseResult* result) {
        T ans = string_to_int_internal<T>(s, len, base, result);
        if (LIKELY(*result == PARSE_SUCCESS)) {
            return ans;
        }

        int i = skip_leading_whitespace(s, len);
        return string_to_int_internal<T>(s + i, len - i, base, result);
    }

    template <typename T>
    static inline T string_to_float(const char* s, int len, ParseResult* result) {
        T ans = string_to_float_internal<T>(s, len, result);
        return ans;
    }

    // Parses a string for 'true' or 'false', case insensitive.
    static inline bool string_to_bool(const char* s, int len, ParseResult* result) {
        bool ans = string_to_bool_internal(s, len, result);
        if (LIKELY(*result == PARSE_SUCCESS)) {
            return ans;
        }

        int i = skip_leading_whitespace(s, len);
        return string_to_bool_internal(s + i, len - i, result);
    }

    template <typename T = __int128>
    static inline T string_to_decimal(const char* s, int len, int type_precision, int type_scale, ParseResult* result);

    template <typename T>
    static Status split_string_to_map(const std::string& base, const T element_separator, const T key_value_separator,
                                      std::map<std::string, std::string>* result) {
        int key_pos = 0;
        int key_end;
        int val_pos;
        int val_end;

        while ((key_end = base.find(key_value_separator, key_pos)) != std::string::npos) {
            if ((val_pos = base.find_first_not_of(key_value_separator, key_end)) == std::string::npos) {
                break;
            }
            if ((val_end = base.find(element_separator, val_pos)) == std::string::npos) {
                val_end = base.size();
            }
            result->insert(
                    std::make_pair(base.substr(key_pos, key_end - key_pos), base.substr(val_pos, val_end - val_pos)));
            key_pos = val_end;
            if (key_pos != std::string::npos) {
                ++key_pos;
            }
        }

        return Status::OK();
    }

private:
    // This is considerably faster than glibc's implementation.
    // In the case of overflow, the max/min value for the data type will be returned.
    // Assumes s represents a decimal number.
    // Return PARSE_FAILURE on leading whitespace. Trailing whitespace is allowed.
    template <typename T>
    static inline T string_to_int_internal(const char* s, int len, ParseResult* result);

    // This is considerably faster than glibc's implementation.
    // In the case of overflow, the max/min value for the data type will be returned.
    // Assumes s represents a decimal number.
    // Return PARSE_FAILURE on leading whitespace. Trailing whitespace is allowed.
    template <typename T>
    static inline T string_to_unsigned_int_internal(const char* s, int len, ParseResult* result);

    // Convert a string s representing a number in given base into a decimal number.
    // Return PARSE_FAILURE on leading whitespace. Trailing whitespace is allowed.
    template <typename T>
    static inline T string_to_int_internal(const char* s, int len, int base, ParseResult* result);

    // Converts an ascii string to an integer of type T assuming it cannot overflow
    // and the number is positive.
    // Leading whitespace is not allowed. Trailing whitespace will be skipped.
    template <typename T>
    static inline T string_to_int_no_overflow(const char* s, int len, ParseResult* result);

    template <typename T>
    static inline T string_to_float_internal(const char* s, int len, ParseResult* result);

    // parses a string for 'true' or 'false', case insensitive
    // Return PARSE_FAILURE on leading whitespace. Trailing whitespace is allowed.
    static inline bool string_to_bool_internal(const char* s, int len, ParseResult* result);

    // Returns true if s only contains whitespace.
    static inline bool is_all_whitespace(const char* s, int len) {
        for (int i = 0; i < len; ++i) {
            if (!LIKELY(is_whitespace(s[i]))) {
                return false;
            }
        }
        return true;
    }

public:
    // Returns the position of the first non-whitespace character in s.
    static inline int skip_leading_whitespace(const char* s, int len) {
        int i = 0;
        while (i < len && is_whitespace(s[i])) {
            ++i;
        }
        return i;
    }

    // Our own definition of "isspace" that optimize on the ' ' branch.
    static inline bool is_whitespace(const char& c) {
        return LIKELY(c == ' ') || UNLIKELY(c == '\t' || c == '\n' || c == '\v' || c == '\f' || c == '\r');
    }

}; // end of class StringParser

template <typename T>
inline T StringParser::string_to_int_internal(const char* s, int len, ParseResult* result) {
    if (UNLIKELY(len <= 0)) {
        *result = PARSE_FAILURE;
        return 0;
    }

    typedef typename std::make_unsigned<T>::type UnsignedT;
    UnsignedT val = 0;
    UnsignedT max_val = StringParser::numeric_limits<T>(false);
    bool negative = false;
    int i = 0;
    switch (*s) {
    case '-':
        negative = true;
        max_val = StringParser::numeric_limits<T>(false) + 1;
    case '+':
        ++i;
    }

    // This is the fast path where the string cannot overflow.
    if (LIKELY(len - i < StringParseTraits<T>::max_ascii_len())) {
        val = string_to_int_no_overflow<UnsignedT>(s + i, len - i, result);
        return static_cast<T>(negative ? (~val + 1) : val);
    }

    const T max_div_10 = max_val / 10;
    const T max_mod_10 = max_val % 10;

    int first = i;
    for (; i < len; ++i) {
        if (LIKELY(s[i] >= '0' && s[i] <= '9')) {
            T digit = s[i] - '0';
            // This is a tricky check to see if adding this digit will cause an overflow.
            if (UNLIKELY(val > (max_div_10 - (digit > max_mod_10)))) {
                *result = PARSE_OVERFLOW;
                return negative ? (~max_val + 1) : max_val;
            }
            val = val * 10 + digit;
        } else {
            if ((UNLIKELY(i == first || !is_all_whitespace(s + i, len - i)))) {
                // Reject the string because either the first char was not a digit,
                // or the remaining chars are not all whitespace
                *result = PARSE_FAILURE;
                return 0;
            }
            // Returning here is slightly faster than breaking the loop.
            *result = PARSE_SUCCESS;
            return static_cast<T>(negative ? (~val + 1) : val);
        }
    }
    *result = PARSE_SUCCESS;
    return static_cast<T>(negative ? (~val + 1) : val);
}

template <typename T>
inline T StringParser::string_to_unsigned_int_internal(const char* s, int len, ParseResult* result) {
    if (UNLIKELY(len <= 0)) {
        *result = PARSE_FAILURE;
        return 0;
    }

    T val = 0;
    T max_val = std::numeric_limits<T>::max();
    int i = 0;

    typedef typename std::make_signed<T>::type signedT;
    // This is the fast path where the string cannot overflow.
    if (LIKELY(len - i < StringParseTraits<signedT>::max_ascii_len())) {
        val = string_to_int_no_overflow<T>(s + i, len - i, result);
        return val;
    }

    const T max_div_10 = max_val / 10;
    const T max_mod_10 = max_val % 10;

    int first = i;
    for (; i < len; ++i) {
        if (LIKELY(s[i] >= '0' && s[i] <= '9')) {
            T digit = s[i] - '0';
            // This is a tricky check to see if adding this digit will cause an overflow.
            if (UNLIKELY(val > (max_div_10 - (digit > max_mod_10)))) {
                *result = PARSE_OVERFLOW;
                return max_val;
            }
            val = val * 10 + digit;
        } else {
            if ((UNLIKELY(i == first || !is_all_whitespace(s + i, len - i)))) {
                // Reject the string because either the first char was not a digit,
                // or the remaining chars are not all whitespace
                *result = PARSE_FAILURE;
                return 0;
            }
            // Returning here is slightly faster than breaking the loop.
            *result = PARSE_SUCCESS;
            return val;
        }
    }
    *result = PARSE_SUCCESS;
    return val;
}

template <typename T>
inline T StringParser::string_to_int_internal(const char* s, int len, int base, ParseResult* result) {
    typedef typename std::make_unsigned<T>::type UnsignedT;
    UnsignedT val = 0;
    UnsignedT max_val = StringParser::numeric_limits<T>(false);
    bool negative = false;
    if (UNLIKELY(len <= 0)) {
        *result = PARSE_FAILURE;
        return 0;
    }
    int i = 0;
    switch (*s) {
    case '-':
        negative = true;
        max_val = StringParser::numeric_limits<T>(false) + 1;
        if (UNLIKELY(len == 1)) {
            *result = PARSE_FAILURE;
            return 0;
        }
    case '+':
        i = 1;
    }

    const T max_div_base = max_val / base;
    const T max_mod_base = max_val % base;

    int first = i;
    for (; i < len; ++i) {
        T digit;
        if (LIKELY(s[i] >= '0' && s[i] <= '9')) {
            digit = s[i] - '0';
        } else if (s[i] >= 'a' && s[i] <= 'z') {
            digit = (s[i] - 'a' + 10);
        } else if (s[i] >= 'A' && s[i] <= 'Z') {
            digit = (s[i] - 'A' + 10);
        } else {
            if ((UNLIKELY(i == first || !is_all_whitespace(s + i, len - i)))) {
                // Reject the string because either the first char was not an alpha/digit,
                // or the remaining chars are not all whitespace
                *result = PARSE_FAILURE;
                return 0;
            }
            // skip trailing whitespace.
            break;
        }

        // Bail, if we encounter a digit that is not available in base.
        if (digit >= base) {
            break;
        }

        // This is a tricky check to see if adding this digit will cause an overflow.
        if (UNLIKELY(val > (max_div_base - (digit > max_mod_base)))) {
            *result = PARSE_OVERFLOW;
            return static_cast<T>(negative ? (~max_val + 1) : max_val);
        }
        val = val * base + digit;
    }
    *result = PARSE_SUCCESS;
    return static_cast<T>(negative ? (~val + 1) : val);
}

template <typename T>
inline T StringParser::string_to_int_no_overflow(const char* s, int len, ParseResult* result) {
    T val = 0;
    if (UNLIKELY(len == 0)) {
        *result = PARSE_FAILURE;
        return val;
    }
    // Factor out the first char for error handling speeds up the loop.
    if (LIKELY(s[0] >= '0' && s[0] <= '9')) {
        val = s[0] - '0';
    } else {
        *result = PARSE_FAILURE;
        return 0;
    }
    for (int i = 1; i < len; ++i) {
        if (LIKELY(s[i] >= '0' && s[i] <= '9')) {
            T digit = s[i] - '0';
            val = val * 10 + digit;
        } else {
            if ((UNLIKELY(!is_all_whitespace(s + i, len - i)))) {
                *result = PARSE_FAILURE;
                return 0;
            }
            *result = PARSE_SUCCESS;
            return val;
        }
    }
    *result = PARSE_SUCCESS;
    return val;
}

template <typename T>
inline T StringParser::string_to_float_internal(const char* s, int len, ParseResult* result) {
    if (UNLIKELY(len <= 0)) {
        *result = PARSE_FAILURE;
        return 0;
    }
    int i = 0;
    // skip leading spaces
    for (; i < len; ++i) {
        if (!is_whitespace(s[i])) {
            break;
        }
    }

    // skip back spaces
    int j = len - 1;
    for (; j >= i; j--) {
        if (!is_whitespace(s[j])) {
            break;
        }
    }

    if (i > j) {
        *result = PARSE_FAILURE;
        return 0;
    }

    bool negative = false;
    // skip leading +/-
    switch (s[i]) {
    case '-':
        negative = true;
        i = i + 1;
        break;
    case '+':
        i = i + 1;
    }

    if (i > j) {
        *result = PARSE_FAILURE;
        return 0;
    }

    // check int/-inf and nan
    for (int k = i; k <= j; ++k) {
        if (s[k] == 'i' || s[k] == 'I') {
            if (len > k + 2 && (s[k + 1] == 'n' || s[k + 1] == 'N') && (s[k + 2] == 'f' || s[k + 2] == 'F')) {
                // Note: Hive writes inf as Infinity, at least for text. We'll be a little loose
                // here and interpret any column with inf as a prefix as infinity rather than
                // checking every remaining byte.
                *result = PARSE_SUCCESS;
                return negative ? -INFINITY : INFINITY;
            } else {
                // Starts with 'i   ', but isn't inf...
                *result = PARSE_FAILURE;
                return 0;
            }
        } else if (s[k] == 'n' || s[k] == 'N') {
            if (len > k + 2 && (s[k + 1] == 'a' || s[k + 1] == 'A') && (s[k + 2] == 'n' || s[k + 2] == 'N')) {
                *result = PARSE_SUCCESS;
                return negative ? -NAN : NAN;
            } else {
                // Starts with 'n', but isn't NaN...
                *result = PARSE_FAILURE;
                return 0;
            }
        } else {
            break;
        }
    }

    // check invalid char
    bool exponential = false;
    for (int k = i; k <= j; ++k) {
        if ((s[k] >= '0' && s[k] <= '9') || s[k] == '.') {
            continue;
        } else if (s[k] == 'e' || s[k] == 'E') {
            if (LIKELY(!exponential)) {
                exponential = true;
            } else {
                *result = PARSE_FAILURE;
                return 0;
            }
        } else if (s[k] == '-' || s[k] == '+') {
            if (LIKELY(k > i && (s[k - 1] == 'e' || s[k - 1] == 'E'))) {
                continue;
            } else {
                *result = PARSE_FAILURE;
                return 0;
            }
        } else {
            *result = PARSE_FAILURE;
            return 0;
        }
    }

    double val;
    auto res = fast_float::from_chars(s + i, s + j + 1, val);

    if (LIKELY(res.ec == std::errc())) {
        // 'res.ptr' is set to point right after the parsed number.
        // if there are some chars left, treate it as failure.
        // for example,
        // '10.11.12.13' is parsed as 10.11, res.ptr is '.12.13', so it is invalid.
        if (res.ptr != s + j + 1) {
            *result = PARSE_FAILURE;
            return 0;
        }
        if (UNLIKELY(val == std::numeric_limits<T>::infinity())) {
            *result = PARSE_OVERFLOW;
        } else {
            *result = PARSE_SUCCESS;
        }
        return negative ? (T)-val : (T)val;
    }

    *result = PARSE_FAILURE;
    return 0;
}

inline bool StringParser::string_to_bool_internal(const char* s, int len, ParseResult* result) {
    *result = PARSE_SUCCESS;

    if (len == 1) {
        if (s[0] == '1') {
            return true;
        } else if (s[0] == '0') {
            return false;
        }
    } else if (len >= 4 && (s[0] == 't' || s[0] == 'T')) {
        bool match = (s[1] == 'r' || s[1] == 'R') && (s[2] == 'u' || s[2] == 'U') && (s[3] == 'e' || s[3] == 'E');
        if (match && LIKELY(is_all_whitespace(s + 4, len - 4))) {
            return true;
        }
    } else if (len >= 5 && (s[0] == 'f' || s[0] == 'F')) {
        bool match = (s[1] == 'a' || s[1] == 'A') && (s[2] == 'l' || s[2] == 'L') && (s[3] == 's' || s[3] == 'S') &&
                     (s[4] == 'e' || s[4] == 'E');
        if (match && LIKELY(is_all_whitespace(s + 5, len - 5))) {
            return false;
        }
    }

    *result = PARSE_FAILURE;
    return false;
}

template <>
__int128 StringParser::numeric_limits<__int128>(bool negative);

template <typename T>
T StringParser::numeric_limits(bool negative) {
    return negative ? std::numeric_limits<T>::lowest() : std::numeric_limits<T>::max();
}

template <>
inline int StringParser::StringParseTraits<int8_t>::max_ascii_len() {
    return 3;
}

template <>
inline int StringParser::StringParseTraits<uint8_t>::max_ascii_len() {
    return 3;
}

template <>
inline int StringParser::StringParseTraits<int16_t>::max_ascii_len() {
    return 5;
}

template <>
inline int StringParser::StringParseTraits<int32_t>::max_ascii_len() {
    return 10;
}

template <>
inline int StringParser::StringParseTraits<int64_t>::max_ascii_len() {
    return 19;
}

template <>
inline int StringParser::StringParseTraits<__int128>::max_ascii_len() {
    return 39;
}

template <>
inline int StringParser::StringParseTraits<int256_t>::max_ascii_len() {
    return 77;
}

template <typename T>
inline T StringParser::string_to_decimal(const char* s, int len, int type_precision, int type_scale,
                                         ParseResult* result) {
    // Special cases:
    //   1) '' == Fail, an empty string fails to parse.
    //   2) '   #   ' == #, leading and trailing white space is ignored.
    //   3) '.' == 0, a single dot parses as zero (for consistency with other types).
    //   4) '#.' == '#', a trailing dot is ignored.

    // Ignore leading and trailing spaces.
    while (len > 0 && is_whitespace(*s)) {
        ++s;
        --len;
    }
    while (len > 0 && is_whitespace(s[len - 1])) {
        --len;
    }

    bool is_negative = false;
    if (len > 0) {
        switch (*s) {
        case '-':
            is_negative = true;
        case '+':
            ++s;
            --len;
        }
    }

    // Ignore leading zeros.
    bool found_value = false;
    while (len > 0 && UNLIKELY(*s == '0')) {
        found_value = true;
        ++s;
        --len;
    }

    // Ignore leading zeros even after a dot. This allows for differentiating between
    // cases like 0.01e2, which would fit in a DECIMAL(1, 0), and 0.10e2, which would
    // overflow.
    int scale = 0;
    int found_dot = 0;
    if (len > 0 && *s == '.') {
        found_dot = 1;
        ++s;
        --len;
        while (len > 0 && UNLIKELY(*s == '0')) {
            found_value = true;
            ++scale;
            ++s;
            --len;
        }
    }

    // decimal's leading zeros after dot should count as precision
    int precision = 0;
    bool found_exponent = false;
    int8_t exponent = 0;
    T value = 0;
    for (int i = 0; i < len; ++i) {
        const char& c = s[i];
        if (LIKELY('0' <= c && c <= '9')) {
            found_value = true;
            // Ignore digits once the type's precision limit is reached. This avoids
            // overflowing the underlying storage while handling a string like
            // 10000000000e-10 into a DECIMAL(1, 0). Adjustments for ignored digits and
            // an exponent will be made later.
            if (LIKELY(type_precision > precision)) {
                value = (value * 10) + (c - '0'); // Benchmarks are faster with parenthesis...
            }
            DCHECK(value >= 0); // For some reason //DCHECK_GE doesn't work with __int128.
            ++precision;
            scale += found_dot;
        } else if (c == '.' && LIKELY(!found_dot)) {
            found_dot = 1;
        } else if ((c == 'e' || c == 'E') && LIKELY(!found_exponent)) {
            found_exponent = true;
            exponent = string_to_int_internal<int8_t>(s + i + 1, len - i - 1, result);
            if (UNLIKELY(*result != StringParser::PARSE_SUCCESS)) {
                if (*result == StringParser::PARSE_OVERFLOW && exponent < 0) {
                    *result = StringParser::PARSE_UNDERFLOW;
                }
                return 0;
            }
            break;
        } else {
            *result = StringParser::PARSE_FAILURE;
            return 0;
        }
    }

    // Find the number of truncated digits before adjusting the precision for an exponent.
    int truncated_digit_count = precision - type_precision;
    if (exponent > scale) {
        // Ex: 0.1e3 (which at this point would have precision == 1 and scale == 1), the
        //     scale must be set to 0 and the value set to 100 which means a precision of 3.
        precision += exponent - scale;
        int shift = exponent - scale;
        if (shift <= decimal_precision_limit<T>) {
            value *= get_scale_factor<T>(exponent - scale);
            scale = 0;
        } else {
            *result = ParseResult::PARSE_FAILURE;
            return 0;
        }
    } else {
        // Ex: 100e-4, the scale must be set to 4 but no adjustment to the value is needed,
        //     the precision must also be set to 4 but that will be done below for the
        //     non-exponent case anyways.
        scale -= exponent;
    }
    // Ex: 0.001, at this point would have precision 1 and scale 3 since leading zeros
    //     were ignored during previous parsing.
    if (scale > precision) precision = scale;

    // Microbenchmarks show that beyond this point, returning on parse failure is slower
    // than just letting the function run out.
    *result = StringParser::PARSE_SUCCESS;
    if (UNLIKELY(precision - scale > type_precision - type_scale)) {
        *result = StringParser::PARSE_OVERFLOW;
    } else if (UNLIKELY(scale > type_scale)) {
        *result = StringParser::PARSE_UNDERFLOW;
        int shift = scale - type_scale;
        if (UNLIKELY(truncated_digit_count > 0)) shift -= truncated_digit_count;
        if (shift > 0) {
            if (shift <= decimal_precision_limit<T>) {
                T divisor = starrocks::get_scale_factor<T>(shift);
                if (LIKELY(divisor >= 0)) {
                    T remainder = value % divisor;
                    value /= divisor;
                    if (std::abs(remainder) >= (divisor >> 1)) {
                        value += 1;
                    }
                } else {
                    DCHECK(divisor == -1); // //DCHECK_EQ doesn't work with __int128.
                    value = 0;
                }
            } else {
                value = 0;
            }
        }
        DCHECK(value >= 0); // //DCHECK_GE doesn't work with __int128.
    } else if (UNLIKELY(!found_value && !found_dot)) {
        *result = StringParser::PARSE_FAILURE;
    }

    if (type_scale > scale) {
        value *= get_scale_factor<T>(type_scale - scale);
    }

    return is_negative ? -value : value;
}

} // namespace starrocks
