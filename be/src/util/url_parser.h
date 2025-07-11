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
//   https://github.com/apache/incubator-doris/blob/master/be/src/util/url_parser.h

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

#include "runtime/string_search.hpp"

namespace starrocks {

// TODO: For now, our parse_url may not behave exactly like Hive
// when given malformed URLs.
// If necessary, we can closely follow Java's URL implementation
// to behave exactly like Hive.

// Example for explaining URL parts:
// http://user:pass@example.com:80/docs/books/tutorial/index.html?name=networking#DOWNLOADING
// PROTOCOL = http
// AUTHORITY = example.com:80
// HOST = example.com
// PATH = /docs/books/tutorial/index.html
// QUERY = name=networking
// FILENAME = /docs/books/tutorial/index.html?name=networking
// REF = DOWNLOADING
// USERINFO = user:pass
// More details on what exactly the URL parts mean can be found here:
// http://docs.oracle.com/javase/tutorial/networking/urls/urlInfo.html
class UrlParser {
public:
    // Parts of a URL that can be requested.
    enum UrlPart { INVALID, AUTHORITY, FILE, HOST, PATH, PROTOCOL, QUERY, REF, USERINFO };

    // Tries to parse the part from url. Places the result in result.
    // Returns false if the URL is malformed or if part is invalid. True otherwise.
    // If false is returned the contents of results are undefined.
    static bool parse_url(const Slice& url, UrlPart part, Slice* result);

    // Compares part against url_authority, url_file, url_host, etc.,
    // and returns the corresponding enum.
    // If part did not match any of the url part constants, returns INVALID.
    static UrlPart get_url_part(const Slice& part);

private:
    // Constants representing parts of a URL.
    static const Slice _s_url_authority;
    static const Slice _s_url_file;
    static const Slice _s_url_host;
    static const Slice _s_url_path;
    static const Slice _s_url_protocol;
    static const Slice _s_url_query;
    static const Slice _s_url_ref;
    static const Slice _s_url_userinfo;
    // Constants used in searching for URL parts.
    static const Slice _s_protocol;
    static const Slice _s_at;
    static const Slice _s_slash;
    static const Slice _s_colon;
    static const Slice _s_question;
    static const Slice _s_hash;
    static const StringSearch _s_protocol_search;
    static const StringSearch _s_at_search;
    static const StringSearch _s_slash_search;
    static const StringSearch _s_colon_search;
    static const StringSearch _s_question_search;
    static const StringSearch _s_hash_search;
};

} // namespace starrocks
