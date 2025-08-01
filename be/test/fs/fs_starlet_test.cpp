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

#include "fs/fs_starlet.h"

#include <fmt/format.h>
#include <fslib/configuration.h>
#include <fslib/fslib_all_initializer.h>
#include <fslib/star_cache_configuration.h>
#include <gtest/gtest.h>

#include <fstream>

#include "common/config.h"
#include "gutil/strings/join.h"
#include "service/staros_worker.h"
#include "testutil/assert.h"

namespace starrocks {

class StarletFileSystemTest : public ::testing::TestWithParam<std::string> {
public:
    StarletFileSystemTest() { srand(time(nullptr)); }
    ~StarletFileSystemTest() override = default;

    void SetUp() override {
        if (config::object_storage_access_key_id.empty()) {
            _is_skipped = true;
            GTEST_SKIP() << "Need set object_storage_access_key_id in be_test.conf";
        }
        if (config::object_storage_secret_access_key.empty()) {
            _is_skipped = true;
            GTEST_SKIP() << "Need set object_storage_secret_access_key in be_test.conf";
        }
        if (config::object_storage_endpoint.empty()) {
            _is_skipped = true;
            GTEST_SKIP() << "Need set object_storage_endpoint in be_test.conf";
        }
        if (config::object_storage_bucket.empty()) {
            _is_skipped = true;
            GTEST_SKIP() << "Need set object_storage_bucket in be_test.conf";
        }

        std::string test_type = GetParam();

        staros::starlet::fslib::register_builtin_filesystems();
        staros::starlet::ShardInfo shard_info;
        shard_info.id = 10086;
        auto fs_info = shard_info.path_info.mutable_fs_info();
        fs_info->set_fs_type(staros::FileStoreType::S3);
        auto s3_fs_info = fs_info->mutable_s3_fs_info();
        s3_fs_info->set_bucket(config::object_storage_bucket);
        s3_fs_info->set_endpoint(config::object_storage_endpoint);
        s3_fs_info->set_region("us-east-1");
        auto credential = s3_fs_info->mutable_credential();
        auto simple_credential = credential->mutable_simple_credential();
        simple_credential->set_access_key(config::object_storage_access_key_id);
        simple_credential->set_access_key_secret(config::object_storage_secret_access_key);
        // set full path
        shard_info.path_info.set_full_path(absl::StrFormat("s3://%s/%d/", s3_fs_info->bucket(), time(NULL)));

        // cache settings
        shard_info.cache_info.set_enable_cache(false);
        shard_info.cache_info.set_async_write_back(false);

        shard_info.properties["storageGroup"] = "10010";

        if (test_type == "cachefs") {
            shard_info.cache_info.set_enable_cache(true);
            std::string tmpl("/tmp/sr_starlet_ut_XXXXXX");
            EXPECT_TRUE(::mkdtemp(tmpl.data()) != nullptr);
            config::starlet_cache_dir = tmpl;
            staros::starlet::fslib::FLAGS_star_cache_async_init = false;
            setenv(staros::starlet::fslib::kFslibCacheDir.c_str(), config::starlet_cache_dir.c_str(),
                   1 /* overwrite */);
        }

        staros::starlet::StarletConfig starlet_config;
        starlet_config.rpc_port = config::starlet_port;
        g_worker = std::make_shared<starrocks::StarOSWorker>();
        g_starlet = std::make_unique<staros::starlet::Starlet>(g_worker);
        g_starlet->init(starlet_config);
        (void)g_worker->add_shard(shard_info);

        // Expect a clean root directory before testing
        ASSIGN_OR_ABORT(auto fs, FileSystem::CreateSharedFromString(StarletPath("/")));
        fs->delete_dir_recursive(StarletPath("/"));
    }
    void TearDown() override {
        if (_is_skipped) {
            return;
        }
        (void)g_worker->remove_shard(10086);
        shutdown_staros_worker();
        std::string test_type = GetParam();
        if (test_type == "cachefs" && config::starlet_cache_dir.compare(0, 5, std::string("/tmp/")) == 0) {
            // Clean cache directory
            std::string cmd = fmt::format("rm -rf {}", config::starlet_cache_dir);
            ::system(cmd.c_str());
        }
    }

    std::string StarletPath(std::string_view path) { return build_starlet_uri(10086, path); }

    void CheckIsDirectory(FileSystem* fs, const std::string& dir_name, bool expected_success,
                          bool expected_is_dir = true) {
        const StatusOr<bool> status_or = fs->is_directory(dir_name);
        EXPECT_EQ(expected_success, status_or.ok()) << status_or.status() << ": " << dir_name;
        if (status_or.ok()) {
            EXPECT_EQ(expected_is_dir, status_or.value());
        }
    }

public:
    bool _is_skipped = false;
};

TEST_P(StarletFileSystemTest, test_build_and_parse_uri) {
    ASSERT_EQ("staros://10", build_starlet_uri(10, ""));
    ASSERT_EQ("staros://10", build_starlet_uri(10, "/"));
    ASSERT_EQ("staros://10", build_starlet_uri(10, "///"));
    ASSERT_EQ("staros://10/abc", build_starlet_uri(10, "abc"));
    ASSERT_EQ("staros://10/abc", build_starlet_uri(10, "/abc"));
    ASSERT_EQ("staros://10/abc", build_starlet_uri(10, "////abc"));
    ASSERT_EQ("staros://10/abc/xyz", build_starlet_uri(10, "////abc/xyz"));

    ASSERT_FALSE(parse_starlet_uri("posix://x/y").ok());
    ASSERT_FALSE(parse_starlet_uri("staros:/10").ok());
    ASSERT_FALSE(parse_starlet_uri("staros://x/y").ok());
    ASSERT_FALSE(parse_starlet_uri("Staros://x/y").ok());
    ASSERT_FALSE(parse_starlet_uri("SATROS://x/y").ok());

    ASSIGN_OR_ABORT(auto path_and_id, parse_starlet_uri("staros://10/abc"));
    ASSERT_EQ("abc", path_and_id.first);
    ASSERT_EQ(10, path_and_id.second);

    ASSIGN_OR_ABORT(path_and_id, parse_starlet_uri("staros://10"));
    ASSERT_EQ("", path_and_id.first);
    ASSERT_EQ(10, path_and_id.second);

    ASSIGN_OR_ABORT(path_and_id, parse_starlet_uri("staros://10/usr/bin"));
    ASSERT_EQ("usr/bin", path_and_id.first);
    ASSERT_EQ(10, path_and_id.second);
}

TEST_P(StarletFileSystemTest, test_write_and_read) {
    auto uri = StarletPath("test1");
    ASSIGN_OR_ABORT(auto fs, FileSystem::CreateSharedFromString(uri));
    ASSIGN_OR_ABORT(auto wf, fs->new_writable_file(uri));
    EXPECT_OK(wf->append("hello"));
    EXPECT_OK(wf->append(" world!"));
    EXPECT_OK(wf->sync());
    ASSIGN_OR_ABORT(auto stats, wf->get_numeric_statistics());
    EXPECT_EQ((*stats).size(), 2);
    EXPECT_EQ((*stats).value(1), 12); // write bytes
    EXPECT_OK(wf->close());
    EXPECT_EQ(sizeof("hello world!"), wf->size() + 1);

    char buf[1024];
    ASSIGN_OR_ABORT(auto rf, fs->new_random_access_file(uri));
    ASSIGN_OR_ABORT(auto nr, rf->read_at(0, buf, sizeof(buf)));
    ASSIGN_OR_ABORT(auto stats2, rf->get_numeric_statistics());
    EXPECT_EQ((*stats2).size(), 11);
    EXPECT_EQ("hello world!", std::string_view(buf, nr));

    ASSIGN_OR_ABORT(nr, rf->read_at(3, buf, sizeof(buf)));
    EXPECT_OK(rf->touch_cache(0 /* offset */, sizeof("hello world!")));
    EXPECT_EQ("lo world!", std::string_view(buf, nr));

    EXPECT_OK(fs->delete_file(uri));
    ASSIGN_OR_ABORT(rf, fs->new_random_access_file(uri));
    EXPECT_TRUE(rf->read_at(0, buf, sizeof(buf)).status().is_not_found());
}

TEST_P(StarletFileSystemTest, test_directory) {
    ASSIGN_OR_ABORT(auto fs, FileSystem::CreateSharedFromString(StarletPath("/")));
    bool created = false;

    //
    //  /dirname0/
    //
    EXPECT_OK(fs->create_dir(StarletPath("/dirname0")));
    CheckIsDirectory(fs.get(), StarletPath("/dirname"), false);
    CheckIsDirectory(fs.get(), StarletPath("/dirname0"), true, true);
    EXPECT_TRUE(fs->create_dir(StarletPath("/dirname0")).is_already_exist());

    EXPECT_OK(fs->create_dir_if_missing(StarletPath("/dirname0"), &created));
    EXPECT_FALSE(created);
    CheckIsDirectory(fs.get(), StarletPath("/dirname0"), true, true);

    //
    //  /dirname0/
    //  /dirname1/
    //
    EXPECT_OK(fs->create_dir_if_missing(StarletPath("/dirname1"), &created));
    EXPECT_TRUE(created);
    CheckIsDirectory(fs.get(), StarletPath("/dirname1"), true, true);

    CheckIsDirectory(fs.get(), StarletPath("/noexistdir"), false);
    EXPECT_ERROR(fs->new_writable_file(StarletPath("/filename/")));

    //
    //  /dirname0/
    //  /dirname1/
    //  /file0
    //
    {
        ASSIGN_OR_ABORT(auto of, fs->new_writable_file(StarletPath("/file0")));
        EXPECT_OK(of->append("hello"));
        EXPECT_OK(of->close());
    }
    CheckIsDirectory(fs.get(), StarletPath("/file0"), true, false);

    //
    //  /dirname0/
    //  /dirname1/
    //  /dirname2/0.dat
    //  /file0
    //
    {
        // NOTE: Although directory "/dirname2" does not exist, we can still create file under "/dirname2" successfully
        ASSIGN_OR_ABORT(auto of, fs->new_writable_file(StarletPath("/dirname2/0.dat")));
        EXPECT_OK(of->append("hello"));
        EXPECT_OK(of->close());
        CheckIsDirectory(fs.get(), StarletPath("/dirname2/0.dat"), true, false);
        CheckIsDirectory(fs.get(), StarletPath("/dirname2/0"), false);
        CheckIsDirectory(fs.get(), StarletPath("/dirname2/0.da"), false);
    }

    //
    //  /dirname0/
    //  /dirname1/
    //  /dirname2/0.dat
    //  /dirname2/1.dat
    //  /file0
    //
    {
        ASSIGN_OR_ABORT(auto of, fs->new_writable_file(StarletPath("/dirname2/1.dat")));
        EXPECT_OK(of->append("hello"));
        EXPECT_OK(of->close());
        CheckIsDirectory(fs.get(), StarletPath("/dirname2/1.dat"), true, false);
    }

    //
    //  /dirname0/
    //  /dirname1/
    //  /dirname2/0.dat
    //  /dirname2/1.dat
    //  /dirname2/subdir0/
    //  /file0
    //
    EXPECT_OK(fs->create_dir(StarletPath("/dirname2/subdir0")));
    CheckIsDirectory(fs.get(), StarletPath("/dirname2/subdir0"), true, true);

    std::vector<std::string> entries;
    auto cb = [&](std::string_view name) -> bool {
        entries.emplace_back(name);
        return true;
    };

    EXPECT_ERROR(fs->iterate_dir(StarletPath("/nonexistdir"), cb));
    EXPECT_ERROR(fs->delete_dir(StarletPath("/nonexistdir")));

    entries.clear();
    EXPECT_OK(fs->iterate_dir(StarletPath("/"), cb));
    EXPECT_EQ("dirname0/,dirname1/,dirname2/,file0", JoinStrings(entries, ","));

    entries.clear();
    EXPECT_OK(fs->iterate_dir(StarletPath("/dirname0"), cb));
    EXPECT_EQ("", JoinStrings(entries, ","));

    entries.clear();
    EXPECT_OK(fs->iterate_dir(StarletPath("/dirname1"), cb));
    EXPECT_EQ("", JoinStrings(entries, ","));

    entries.clear();
    EXPECT_OK(fs->iterate_dir(StarletPath("/dirname2"), cb));
    EXPECT_EQ("subdir0/,0.dat,1.dat", JoinStrings(entries, ","));

    entries.clear();
    EXPECT_OK(fs->iterate_dir(StarletPath("/dirname2/subdir0"), cb));
    EXPECT_EQ("", JoinStrings(entries, ","));

    EXPECT_ERROR(fs->delete_dir(StarletPath("/dirname2"))); // dirname2 is not empty

    EXPECT_OK(fs->delete_dir(StarletPath("/dirname0")));
    EXPECT_OK(fs->delete_dir(StarletPath("/dirname1")));
    EXPECT_OK(fs->delete_file(StarletPath("/dirname2/0.dat")));
    EXPECT_OK(fs->delete_file(StarletPath("/dirname2/1.dat")));
    EXPECT_OK(fs->delete_dir(StarletPath("/dirname2/subdir0")));
    EXPECT_ERROR(fs->delete_dir(StarletPath("/dirname2"))); // "/dirname2/" is a non-exist object
    EXPECT_OK(fs->delete_file(StarletPath("/file0")));
}

TEST_P(StarletFileSystemTest, test_delete_dir_recursive) {
    ASSIGN_OR_ABORT(auto fs, FileSystem::CreateSharedFromString(StarletPath("/")));

    std::vector<std::string> entries;
    auto cb = [&](std::string_view name) -> bool {
        entries.emplace_back(name);
        return true;
    };

    bool created;
    fs->delete_dir_recursive(StarletPath("/dirname0"));
    EXPECT_OK(fs->create_dir_if_missing(StarletPath("/dirname0"), &created));
    ASSERT_OK(fs->delete_dir_recursive(StarletPath("/dirname0")));

    EXPECT_OK(fs->create_dir_if_missing(StarletPath("/dirname0"), &created));
    EXPECT_OK(fs->create_dir_if_missing(StarletPath("/dirname0/a"), &created));
    EXPECT_OK(fs->create_dir_if_missing(StarletPath("/dirname0/b"), &created));
    EXPECT_OK(fs->create_dir_if_missing(StarletPath("/dirname0/a/a"), &created));
    EXPECT_OK(fs->create_dir_if_missing(StarletPath("/dirname0/a/b"), &created));
    EXPECT_OK(fs->create_dir_if_missing(StarletPath("/dirname0/a/c"), &created));
    {
        ASSIGN_OR_ABORT(auto of, fs->new_writable_file(StarletPath("/dirname0/1.dat")));
        EXPECT_OK(of->append("hello"));
        EXPECT_OK(of->close());
    }
    {
        ASSIGN_OR_ABORT(auto of, fs->new_writable_file(StarletPath("/dirname0/a/1.dat")));
        EXPECT_OK(of->append("hello"));
        EXPECT_OK(of->close());
    }

    EXPECT_OK(fs->create_dir_if_missing(StarletPath("/dirname0x"), &created));
    ASSERT_OK(fs->delete_dir_recursive(StarletPath("/dirname0")));
    EXPECT_OK(fs->iterate_dir(StarletPath("/"), cb));
    ASSERT_EQ(1, entries.size());
    ASSERT_EQ("dirname0x/", entries[0]);
    ASSERT_OK(fs->delete_dir(StarletPath("/dirname0x")));
    ASSERT_OK(fs->delete_dir_recursive(StarletPath("/")));
    entries.clear();
    // NOTE: this is incompatible with other file systems.
    EXPECT_TRUE(fs->iterate_dir(StarletPath("/"), cb).is_not_found());
}

TEST_P(StarletFileSystemTest, test_delete_nonexist_file) {
    ASSIGN_OR_ABORT(auto fs, FileSystem::CreateSharedFromString(StarletPath("/")));
    ASSERT_OK(fs->delete_file(StarletPath("/nonexist.dat")));
}

TEST_P(StarletFileSystemTest, test_delete_files) {
    ASSIGN_OR_ABORT(auto fs, FileSystem::CreateSharedFromString(StarletPath("/")));

    auto uri1 = StarletPath("/f1");
    ASSIGN_OR_ABORT(auto wf1, fs->new_writable_file(uri1));
    EXPECT_OK(wf1->append("hello"));
    EXPECT_OK(wf1->append(" world!"));
    EXPECT_OK(wf1->sync());
    EXPECT_OK(wf1->close());
    EXPECT_EQ(sizeof("hello world!"), wf1->size() + 1);
    EXPECT_OK(fs->path_exists(uri1));

    auto uri2 = StarletPath("/f2");
    ASSIGN_OR_ABORT(auto wf2, fs->new_writable_file(uri2));
    EXPECT_OK(wf2->append("hello"));
    EXPECT_OK(wf2->append(" world!"));
    EXPECT_OK(wf2->sync());
    EXPECT_OK(wf2->close());
    EXPECT_EQ(sizeof("hello world!"), wf2->size() + 1);
    EXPECT_OK(fs->path_exists(uri2));

    EXPECT_OK(fs->path_exists(uri1));
    EXPECT_OK(fs->path_exists(uri2));

    std::vector<std::string> paths;
    EXPECT_OK(fs->delete_files(paths));

    paths.emplace_back(uri1);
    paths.emplace_back(uri2);
    EXPECT_OK(fs->delete_files(paths));
    EXPECT_TRUE(fs->path_exists(uri1).is_not_found());
    EXPECT_TRUE(fs->path_exists(uri2).is_not_found());

    staros::starlet::fslib::register_builtin_filesystems();
    staros::starlet::ShardInfo shard_info;
    shard_info.id = 10010;
    auto fs_info = shard_info.path_info.mutable_fs_info();
    fs_info->set_fs_type(staros::FileStoreType::S3);
    auto s3_fs_info = fs_info->mutable_s3_fs_info();
    s3_fs_info->set_bucket(config::object_storage_bucket);
    s3_fs_info->set_endpoint(config::object_storage_endpoint);
    s3_fs_info->set_region("us-east-1");
    auto credential = s3_fs_info->mutable_credential();
    auto simple_credential = credential->mutable_simple_credential();
    simple_credential->set_access_key(config::object_storage_access_key_id);
    simple_credential->set_access_key_secret(config::object_storage_secret_access_key);
    // set full path
    shard_info.path_info.set_full_path(absl::StrFormat("s3://%s/%d/", s3_fs_info->bucket(), time(NULL)));

    // cache settings
    shard_info.cache_info.set_enable_cache(false);
    shard_info.cache_info.set_async_write_back(false);

    (void)g_worker->add_shard(shard_info);

    auto uri3 = build_starlet_uri(shard_info.id, "/f1");
    paths.emplace_back(uri3);
    EXPECT_OK(fs->delete_files(paths));
    (void)g_worker->remove_shard(shard_info.id);
}

INSTANTIATE_TEST_CASE_P(StarletFileSystem, StarletFileSystemTest,
                        ::testing::Values(std::string("s3"), std::string("cachefs")));

} // namespace starrocks
