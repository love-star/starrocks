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

#include <gutil/macros.h>

#include <string>
#include <vector>

#include "gen_cpp/Types_types.h"

namespace starrocks {

class CIDR;

class BackendOptions {
public:
    static bool init(bool is_cn);
    static std::string get_localhost();
    static std::string get_local_ip();
    static TBackend get_localBackend();
    static const char* get_service_bind_address();
    static const char* get_service_bind_address_without_bracket();
    static void set_localhost(const std::string& host);
    static void set_localhost(const std::string& host, const std::string& ip);
    static bool is_bind_ipv6();
    static bool is_cn();

private:
    static bool analyze_priority_cidrs();
    static bool is_in_prior_network(const std::string& ip);

    static std::string _s_localhost;
    static std::string _s_local_ip;
    static std::vector<CIDR> _s_priority_cidrs;
    static TBackend _backend;
    static bool _bind_ipv6;
    static bool _is_cn;

    BackendOptions(const BackendOptions&) = delete;
    const BackendOptions& operator=(const BackendOptions&) = delete;
};

} // namespace starrocks
