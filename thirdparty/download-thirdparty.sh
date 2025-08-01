#!/bin/bash
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.

#set -e
################################################################
# This script will download all thirdparties and java libraries
# which are defined in *vars.sh*, unpack patch them if necessary.
# You can run this script multi-times.
# Things will only be downloaded, unpacked and patched once.
################################################################

curdir=`dirname "$0"`
curdir=`cd "$curdir"; pwd`

if [[ -z "${STARROCKS_HOME}" ]]; then
    STARROCKS_HOME=$curdir/..
fi

# include custom environment variables
if [[ -f ${STARROCKS_HOME}/custom_env.sh ]]; then
    . ${STARROCKS_HOME}/custom_env.sh
fi

if [[ -z "${TP_DIR}" ]]; then
    TP_DIR=$curdir
fi

if [ ! -f ${TP_DIR}/vars.sh ]; then
    echo "vars.sh is missing".
    exit 1
fi
. ${TP_DIR}/vars.sh

mkdir -p ${TP_DIR}/src

md5sum_bin=md5sum
if ! command -v ${md5sum_bin} >/dev/null 2>&1; then
    echo "Warn: md5sum is not installed"
    md5sum_bin=""
fi

md5sum_func() {
    local FILENAME=$1
    local DESC_DIR=$2
    local MD5SUM=$3

    if [ "$md5sum_bin" == "" ]; then
       return 0
    else
       md5=`md5sum "$DESC_DIR/$FILENAME"`
       if [ "$md5" != "$MD5SUM  $DESC_DIR/$FILENAME" ]; then
           echo "$DESC_DIR/$FILENAME md5sum check failed!"
           echo -e "expect-md5 $MD5SUM \nactual-md5 $md5"
           return 1
       fi
    fi

    return 0
}

download_func() {
    local FILENAME=$1
    local DOWNLOAD_URL=$2
    local DESC_DIR=$3
    local MD5SUM=$4

    if [ -z "$FILENAME" ]; then
        echo "Error: No file name specified to download"
        exit 1
    fi
    if [ -z "$DOWNLOAD_URL" ]; then
        echo "Error: No download url specified for $FILENAME"
        exit 1
    fi
    if [ -z "$DESC_DIR" ]; then
        echo "Error: No dest dir specified for $FILENAME"
        exit 1
    fi


    SUCCESS=0
    for attemp in 1 2; do
        if [ -r "$DESC_DIR/$FILENAME" ]; then
            if md5sum_func $FILENAME $DESC_DIR $MD5SUM; then
                echo "Archive $FILENAME already exist."
                SUCCESS=1
                break;
            fi
            echo "Archive $FILENAME will be removed and download again."
            rm -f "$DESC_DIR/$FILENAME"
        else
            echo "Downloading $FILENAME from $DOWNLOAD_URL to $DESC_DIR"
            wget --no-check-certificate $DOWNLOAD_URL -O $DESC_DIR/$FILENAME
            if [ "$?"x == "0"x ]; then
                if md5sum_func $FILENAME $DESC_DIR $MD5SUM; then
                    SUCCESS=1
                    echo "Success to download $FILENAME"
                    break;
                fi
                echo "Archive $FILENAME will be removed and download again."
                rm -f "$DESC_DIR/$FILENAME"
            else
                echo "Failed to download $FILENAME. attemp: $attemp"
            fi
        fi
    done

    if [ $SUCCESS -ne 1 ]; then
        echo "Failed to download $FILENAME"
    fi
    return $SUCCESS
}

# download thirdparty archives
echo "===== Downloading thirdparty archives..."
for TP_ARCH in ${TP_ARCHIVES[*]}
do
    NAME=$TP_ARCH"_NAME"
    MD5SUM=$TP_ARCH"_MD5SUM"
    if test "x$REPOSITORY_URL" = x; then
        URL=$TP_ARCH"_DOWNLOAD"
        download_func ${!NAME} ${!URL} $TP_SOURCE_DIR ${!MD5SUM}
        if [ "$?"x == "0"x ]; then
            echo "Failed to download ${!NAME}"
            exit 1
        fi
    else
        URL="${REPOSITORY_URL}/${!NAME}"
        download_func ${!NAME} ${URL} $TP_SOURCE_DIR ${!MD5SUM}
        if [ "$?x" == "0x" ]; then
            #try to download from home
            URL=$TP_ARCH"_DOWNLOAD"
            download_func ${!NAME} ${!URL} $TP_SOURCE_DIR ${!MD5SUM}
            if [ "$?x" == "0x" ]; then
                echo "Failed to download ${!NAME}"
                exit 1 # download failed again exit.
            fi
        fi
    fi
done
echo "===== Downloading thirdparty archives...done"

# check if all tp archievs exists
echo "===== Checking all thirdpart archives..."
for TP_ARCH in ${TP_ARCHIVES[*]}
do
    NAME=$TP_ARCH"_NAME"
    if [ ! -r $TP_SOURCE_DIR/${!NAME} ]; then
        echo "Failed to fetch ${!NAME}"
        exit 1
    fi
done
echo "===== Checking all thirdpart archives...done"

# unpacking thirdpart archives
echo "===== Unpacking all thirdparty archives..."
TAR_CMD="tar"
UNZIP_CMD="unzip"
SUFFIX_TGZ="\.(tar\.gz|tgz)$"
SUFFIX_XZ="\.tar\.xz$"
SUFFIX_ZIP="\.zip$"
SUFFIX_BZ2="\.bz2$"
# temporary directory for unpacking
# package is unpacked in tmp_dir and then renamed.
mkdir -p $TP_SOURCE_DIR/tmp_dir
for TP_ARCH in ${TP_ARCHIVES[*]}
do
    NAME=$TP_ARCH"_NAME"
    SOURCE=$TP_ARCH"_SOURCE"

    if [ -z "${!SOURCE}" ]; then
        continue
    fi

    if [ ! -d $TP_SOURCE_DIR/${!SOURCE} ]; then
        if [[ "${!NAME}" =~ $SUFFIX_TGZ  ]]; then
            echo "$TP_SOURCE_DIR/${!NAME}"
            echo "$TP_SOURCE_DIR/${!SOURCE}"
            if ! $TAR_CMD xzf "$TP_SOURCE_DIR/${!NAME}" -C $TP_SOURCE_DIR/tmp_dir; then
                echo "Failed to untar ${!NAME}"
                exit 1
            fi
        elif [[ "${!NAME}" =~ $SUFFIX_XZ ]]; then
            echo "$TP_SOURCE_DIR/${!NAME}"
            echo "$TP_SOURCE_DIR/${!SOURCE}"
            if ! $TAR_CMD xJf "$TP_SOURCE_DIR/${!NAME}" -C $TP_SOURCE_DIR/tmp_dir; then
                echo "Failed to untar ${!NAME}"
                exit 1
            fi
        elif [[ "${!NAME}" =~ $SUFFIX_ZIP ]]; then
            if ! $UNZIP_CMD "$TP_SOURCE_DIR/${!NAME}" -d $TP_SOURCE_DIR/tmp_dir; then
                echo "Failed to unzip ${!NAME}"
                exit 1
            fi
        elif [[ "${!NAME}" =~ $SUFFIX_BZ2 ]]; then
            echo "$TP_SOURCE_DIR/${!NAME}"
            echo "$TP_SOURCE_DIR/${!SOURCE}"
            if ! $TAR_CMD jxvf "$TP_SOURCE_DIR/${!NAME}" -C $TP_SOURCE_DIR/tmp_dir; then
                echo "Failed to untar ${!NAME}"
                exit 1
            fi
        else
            echo "nothing has been done with ${!NAME}"
            continue
        fi
        mv $TP_SOURCE_DIR/tmp_dir/* $TP_SOURCE_DIR/${!SOURCE}
    else
        echo "${!SOURCE} already unpacked."
    fi
done
rm -r $TP_SOURCE_DIR/tmp_dir
echo "===== Unpacking all thirdparty archives...done"

echo "===== Patching thirdparty archives..."

###################################################################################
# PATCHED_MARK is a empty file which will be created in some thirdparty source dir
# only after that thirdparty source is patched.
# This is to avoid duplicated patch.
###################################################################################
PATCHED_MARK="patched_mark"

# glog patch
cd $TP_SOURCE_DIR/$GLOG_SOURCE
if [ ! -f $PATCHED_MARK ] && [ $GLOG_SOURCE == "glog-0.3.3" ]; then
    patch -p1 < $TP_PATCH_DIR/glog-0.3.3-vlog-double-lock-bug.patch
    patch -p1 < $TP_PATCH_DIR/glog-0.3.3-for-starrocks2.patch
    patch -p1 < $TP_PATCH_DIR/glog-0.3.3-remove-unwind-dependency.patch
    # patch Makefile.am to make autoreconf work
    patch -p0 < $TP_PATCH_DIR/glog-0.3.3-makefile.patch
    touch $PATCHED_MARK
fi
if [ ! -f $PATCHED_MARK ] && [ $GLOG_SOURCE == "glog-0.4.0" ]; then
    patch -p1 < $TP_PATCH_DIR/glog-0.4.0-for-starrocks2.patch
    patch -p1 < $TP_PATCH_DIR/glog-0.4.0-remove-unwind-dependency.patch
    patch -p1 < $TP_PATCH_DIR/glog-0.4.0-add-handler-after-output-log.patch
    touch $PATCHED_MARK
fi
if [ ! -f $PATCHED_MARK ] && [ $GLOG_SOURCE == "glog-0.7.1" ]; then
    patch -p1 < $TP_PATCH_DIR/glog-0.7.1.patch
    patch -p1 < $TP_PATCH_DIR/glog-0.7.1-add-handler-after-output-log.patch
    patch -p1 < $TP_PATCH_DIR/glog-0.7.1-lwp.patch
    touch $PATCHED_MARK
fi
cd -
echo "Finished patching $GLOG_SOURCE"

# re2 patch
cd $TP_SOURCE_DIR/$RE2_SOURCE
if [ ! -f $PATCHED_MARK ]; then
    patch -p1 < $TP_PATCH_DIR/re2-2022-12-01.patch
    touch $PATCHED_MARK
fi
cd -
echo "Finished patching $RE2_SOURCE"

# libevent patch
cd $TP_SOURCE_DIR/$LIBEVENT_SOURCE
if [ ! -f $PATCHED_MARK ]; then
    patch -p1 < $TP_PATCH_DIR/libevent_on_free_cb.patch
    touch $PATCHED_MARK
fi
cd -
echo "Finished patching $LIBEVENT_SOURCE"

# thrift patch
# cd $TP_SOURCE_DIR/$THRIFT_SOURCE
# if [ ! -f $PATCHED_MARK ]; then
#     patch -p0 < $TP_PATCH_DIR/thrift-0.9.3-aclocal.patch
#     touch $PATCHED_MARK
# fi
# cd -
# echo "Finished patching $THRIFT_SOURCE"

# lz4 patch to disable shared library
cd $TP_SOURCE_DIR/$LZ4_SOURCE
if [ ! -f $PATCHED_MARK ] && [ $LZ4_SOURCE == "lz4-1.7.5" ]; then
    patch -p0 < $TP_PATCH_DIR/lz4-1.7.5.patch
    touch $PATCHED_MARK
fi
cd -
echo "Finished patching $LZ4_SOURCE"

cd $TP_SOURCE_DIR/$ROCKSDB_SOURCE
if [ ! -f $PATCHED_MARK ] && [ $ROCKSDB_SOURCE == "rocksdb-6.22.1" ]; then
    patch -p1 < $TP_PATCH_DIR/rocksdb-6.22.1-metadata-header.patch
    patch -p1 < $TP_PATCH_DIR/rocksdb-6.22.1-gcc14.patch
    touch $PATCHED_MARK
fi
cd -
echo "Finished patching $ROCKSDB_SOURCE"

# brpc patch to disable shared library
cd $TP_SOURCE_DIR/$BRPC_SOURCE
if [ ! -f $PATCHED_MARK ] && [ $BRPC_SOURCE == "brpc-0.9.5" ]; then
    patch -p1 < $TP_PATCH_DIR/brpc-0.9.5.patch
    touch $PATCHED_MARK
fi
if [ ! -f $PATCHED_MARK ] && [ $BRPC_SOURCE == "brpc-0.9.7" ]; then
    patch -p1 < $TP_PATCH_DIR/brpc-0.9.7.patch
    touch $PATCHED_MARK
fi
if [ ! -f $PATCHED_MARK ] && [ $BRPC_SOURCE == "brpc-1.3.0" ]; then
    patch -p1 < $TP_PATCH_DIR/brpc-1.3.0.patch
    patch -p1 < $TP_PATCH_DIR/brpc-1.3.0-CVE-2023-31039.patch
    patch -p1 < $TP_PATCH_DIR/brpc-1.3.0-2479.patch
    touch $PATCHED_MARK
fi
if [ ! -f $PATCHED_MARK ] && [ $BRPC_SOURCE == "brpc-1.9.0" ]; then
    patch < $TP_PATCH_DIR/brpc-1.9.0.patch
    touch $PATCHED_MARK
fi
cd -
echo "Finished patching $BRPC_SOURCE"

# s2 patch to disable shared library
cd $TP_SOURCE_DIR/$S2_SOURCE
if [ ! -f $PATCHED_MARK ]; then
    patch -p1 < $TP_PATCH_DIR/s2geometry-0.9.0.patch
    # replace uint64 with uint64_t to make compiler happy
    patch -p0 < $TP_PATCH_DIR/s2geometry-0.9.0-uint64.patch
    patch -p1 < $TP_PATCH_DIR/s2geometry-0.9.0-cxx17.patch
    touch $PATCHED_MARK
fi
cd -
echo "Finished patching $S2_SOURCE"

# ryu patch to detailed description
cd $TP_SOURCE_DIR/$RYU_SOURCE
if [ ! -f $PATCHED_MARK ]; then
    patch -p1 < $TP_PATCH_DIR/ryu.patch
    touch $PATCHED_MARK
fi
cd -
echo "Finished patching $RYU_SOURCE"

# patch boost-1.75.0 diff
cd $TP_SOURCE_DIR/$BOOST_SOURCE
if [ ! -f $PATCHED_MARK ]; then
    patch -p1 < $TP_PATCH_DIR/boost-1.75.0.patch
    touch $PATCHED_MARK
fi
cd -
echo "Finished patching $BOOST_SOURCE"

# patch protobuf-3.5.1
cd $TP_SOURCE_DIR/$PROTOBUF_SOURCE
if [ ! -f $PATCHED_MARK ] && [ $PROTOBUF_SOURCE == "protobuf-3.5.1" ]; then
    patch -p1 < $TP_PATCH_DIR/protobuf-3.5.1.patch
    touch $PATCHED_MARK
fi
cd -
echo "Finished patching $PROTOBUF_SOURCE"

# patch tcmalloc_hook
cd $TP_SOURCE_DIR/$GPERFTOOLS_SOURCE
if [ ! -f $PATCHED_MARK ] && [ $GPERFTOOLS_SOURCE = "gperftools-gperftools-2.7" ]; then
    patch -p1 < $TP_PATCH_DIR/tcmalloc_hook.patch
    touch $PATCHED_MARK
fi
cd -
echo "Finished patching $GPERFTOOLS_SOURCE"

# patch librdkafka
cd $TP_SOURCE_DIR/$LIBRDKAFKA_SOURCE
if [ ! -f $PATCHED_MARK ] && [ $LIBRDKAFKA_SOURCE = "librdkafka-2.0.2" ]; then
    patch -p0 < $TP_PATCH_DIR/librdkafka.patch
    touch $PATCHED_MARK
fi
cd -
echo "Finished patching $LIBRDKAFKA_SOURCE"

# patch roaring-bitmap
cd $TP_SOURCE_DIR/$CROARINGBITMAP_SOURCE
if [ ! -f $PATCHED_MARK ] && [ $CROARINGBITMAP_SOURCE = "CRoaring-0.2.60" ]; then
    patch -p1 < $TP_PATCH_DIR/roaring-bitmap-patch-v0.2.60.patch
    touch $PATCHED_MARK
fi
cd -
echo "Finished patching $CROARINGBITMAP_SOURCE"

# patch pulsar
cd $TP_SOURCE_DIR/$PULSAR_SOURCE
if [ ! -f $PATCHED_MARK ] && [ $PULSAR_SOURCE = "pulsar-client-cpp-3.3.0" ]; then
    patch -p1 < $TP_PATCH_DIR/pulsar.patch
    touch $PATCHED_MARK
fi
cd -
echo "Finished patching $PULSAR_SOURCE"

# patch mariadb-connector-c-3.2.5
cd $TP_SOURCE_DIR/$MARIADB_SOURCE
if [ ! -f $PATCHED_MARK ] && [ $MARIADB_SOURCE = "mariadb-connector-c-3.2.5" ]; then
    patch -p0 < $TP_PATCH_DIR/mariadb-connector-c-3.2.5-for-starrocks-static-link.patch
    touch $PATCHED_MARK
    echo "Finished patching $MARIADB_SOURCE"
fi
if [ ! -f $PATCHED_MARK ] && [ $MARIADB_SOURCE = "mariadb-connector-c-3.1.14" ]; then
    patch -p1 < $TP_PATCH_DIR/mariadb-connector-c-3.1.14-gcc14.patch
    touch $PATCHED_MARK
    echo "Finished patching $MARIADB_SOURCE"
fi

# patch aws-sdk-cpp
cd $TP_SOURCE_DIR/$AWS_SDK_CPP_SOURCE
if [ $AWS_SDK_CPP_SOURCE = "aws-sdk-cpp-1.11.267" ]; then
    if [ ! -f prefetch_crt_dep_ok ]; then
        bash ./prefetch_crt_dependency.sh
        touch prefetch_crt_dep_ok
    fi
    if [ ! -f $PATCHED_MARK ]; then
        patch -p1  < $TP_PATCH_DIR/aws-cpp-sdk-1.11.267-disable-chunked-upload.patch
        touch $PATCHED_MARK
    fi
fi
cd -
echo "Finished patching $AWS_SDK_CPP_SOURCE"

# patch jemalloc_hook
cd $TP_SOURCE_DIR/$JEMALLOC_SOURCE
if [ ! -f $PATCHED_MARK ] && [ $JEMALLOC_SOURCE = "jemalloc-5.3.0" ]; then
    patch -p0 < $TP_PATCH_DIR/jemalloc_hook.patch
    patch -p0 < $TP_PATCH_DIR/jemalloc_nallocx.patch
    patch -p0 < $TP_PATCH_DIR/jemalloc_nodump.patch
    touch $PATCHED_MARK
fi
cd -
echo "Finished patching $JEMALLOC_SOURCE"

# patch streamvbyte
cd $TP_SOURCE_DIR/$STREAMVBYTE_SOURCE
if [ ! -f $PATCHED_MARK ] && [ $STREAMVBYTE_SOURCE = "streamvbyte-0.5.1" ]; then
    patch -p1 < $TP_PATCH_DIR/streamvbyte.patch
    touch $PATCHED_MARK
fi
cd -
echo "Finished patching $STREAMVBYTE_SOURCE"

# patch hyperscan
cd $TP_SOURCE_DIR/$HYPERSCAN_SOURCE
if [ ! -f $PATCHED_MARK ] && [ $HYPERSCAN_SOURCE = "hyperscan-5.4.0" ]; then
    patch -p1 < $TP_PATCH_DIR/hyperscan-5.4.0.patch
    touch $PATCHED_MARK
fi
cd -
echo "Finished patching $HYPERSCAN_SOURCE"

# patch vpack
cd $TP_SOURCE_DIR/$VPACK_SOURCE
if [ ! -f $PATCHED_MARK ] && [ $VPACK_SOURCE = "velocypack-XYZ1.0" ]; then
    patch -p1 < $TP_PATCH_DIR/velocypack-XYZ1.0.patch
    touch $PATCHED_MARK
fi
cd -
echo "Finished patching $VPACK_SOURCE"

# patch avro-c and avro-cpp
cd $TP_SOURCE_DIR/$AVRO_SOURCE
if [ ! -f $PATCHED_MARK ] && [ $AVRO_SOURCE = "avro-release-1.12.0" ]; then
    # c patches
    cd $TP_SOURCE_DIR/$AVRO_SOURCE
    patch -p1 < $TP_PATCH_DIR/avro-1.12.0.c.patch
    cp $TP_PATCH_DIR/avro-1.12.0.c.findjansson.patch ./lang/c/Findjansson.cmake

    # c++ patches
    patch -p1 < $TP_PATCH_DIR/avro-1.12.0.cpp.patch
    touch $PATCHED_MARK
fi
cd -
echo "Finished patching $AVRO_SOURCE-c"

# patch serdes
cd $TP_SOURCE_DIR/$SERDES_SOURCE
if [ ! -f $PATCHED_MARK ] && [ $SERDES_SOURCE = "libserdes-7.3.1" ]; then
    patch -p0 < $TP_PATCH_DIR/libserdes-7.3.1.patch
    touch $PATCHED_MARK
fi
echo "Finished patching $SERDES_SOURCE"
cd -

# patch sasl2
cd $TP_SOURCE_DIR/$SASL_SOURCE
if [ ! -f $PATCHED_MARK ] && [ $SASL_SOURCE = "cyrus-sasl-2.1.28" ]; then
    patch -p1 < $TP_PATCH_DIR/sasl2-add-k5support-link.patch
    patch -p1 < $TP_PATCH_DIR/sasl2-gcc14.patch
    touch $PATCHED_MARK
fi
echo "Finished patching $SASL_SOURCE"
cd -

cd $TP_SOURCE_DIR/$RAPIDJSON_SOURCE
if [ ! -f $PATCHED_MARK ] && [ $RAPIDJSON_SOURCE = "rapidjson-1.1.0" ]; then
    patch -p1 < $TP_PATCH_DIR/rapidjson-gcc14.patch
    touch $PATCHED_MARK
fi
echo "Finished patching $RAPIDJSON_SOURCE"
cd -

# patch arrow
if [[ -d $TP_SOURCE_DIR/$ARROW_SOURCE ]] ; then
    cd $TP_SOURCE_DIR/$ARROW_SOURCE
    if [ ! -f $PATCHED_MARK ] && [ $ARROW_SOURCE = "arrow-apache-arrow-5.0.0" ] ; then
        # use our built jemalloc
        patch -p1 < $TP_PATCH_DIR/arrow-5.0.0-force-use-external-jemalloc.patch
        # fix exception handling
        patch -p1 < $TP_PATCH_DIR/arrow-5.0.0-fix-exception-handling.patch
        patch -p1 < $TP_PATCH_DIR/arrow-5.0.0-parquet-map-key.patch
        touch $PATCHED_MARK
    fi
    if [ ! -f $PATCHED_MARK ] && [ $ARROW_SOURCE = "arrow-apache-arrow-16.1.0" ] ; then
        patch -p1 < $TP_PATCH_DIR/arrow-16.1.0-parquet-map-key.patch
        patch -p1 < $TP_PATCH_DIR/arrow-16.1.0-use-zstd-1.5.7.patch
        touch $PATCHED_MARK
    fi
    cd -
    echo "Finished patching $ARROW_SOURCE"
fi

# patch bzip
if [[ -d $TP_SOURCE_DIR/$BZIP_SOURCE ]] ; then
    cd $TP_SOURCE_DIR/$BZIP_SOURCE
    if [ ! -f "$PATCHED_MARK" ] && [[ $BZIP_SOURCE == "bzip2-1.0.8" ]] ; then
        patch -p1 < "$TP_PATCH_DIR/bzip2-1.0.8.patch"
        touch "$PATCHED_MARK"
    fi
    cd -
    echo "Finished patching $BZIP_SOURCE"
fi

# patch bitshuffle
if [[ -d $TP_SOURCE_DIR/$BITSHUFFLE_SOURCE ]] ; then
    cd $TP_SOURCE_DIR/$BITSHUFFLE_SOURCE
    if [ ! -f "$PATCHED_MARK" ] && [[ $BITSHUFFLE_SOURCE == "bitshuffle-0.5.1" ]] ; then
        patch -p1 < "$TP_PATCH_DIR/bitshuffle-0.5.1.patch"
        touch "$PATCHED_MARK"
    fi
    cd -
    echo "Finished patching $BITSHUFFLE_SOURCE"
fi

# patch flatbuffers
if [[ -d $TP_SOURCE_DIR/$FLATBUFFERS_SOURCE ]] ; then
    cd $TP_SOURCE_DIR/$FLATBUFFERS_SOURCE
    if [ ! -f "$PATCHED_MARK" ] && [[ $FLATBUFFERS_SOURCE == "flatbuffers-1.10.0" ]] ; then
        patch -p1 < "$TP_PATCH_DIR/flat-buffers-1.10.0-no-stringop-overread.patch"
        touch "$PATCHED_MARK"
    fi
    cd -
    echo "Finished patching $FLATBUFFERS_SOURCE"
fi

#patch clucene
if [[ -d $TP_SOURCE_DIR/$CLUCENE_SOURCE ]] ; then
    cd $TP_SOURCE_DIR/$CLUCENE_SOURCE
    if [ ! -f "$PATCHED_MARK" ] ; then
        patch -p1 < "$TP_PATCH_DIR/clucene-gcc14.patch"
        touch "$PATCHED_MARK"
    fi
    cd -
    echo "Finished patching $CLUCENE_SOURCE"
fi

#patch poco
if [[ -d $TP_SOURCE_DIR/$POCO_SOURCE ]] ; then
    cd $TP_SOURCE_DIR/$POCO_SOURCE
    if [ ! -f "$PATCHED_MARK" ] && [[ $POCO_SOURCE == "poco-1.12.5-release" ]] ; then
        patch -p1 < "$TP_PATCH_DIR/poco-1.12.5-ca.patch"
        patch -p1 < "$TP_PATCH_DIR/poco-1.12.5-zero-copy.patch"
        patch -p1 < "$TP_PATCH_DIR/poco-1.12.5-keep-alive.patch"
        touch "$PATCHED_MARK"
    fi
    cd -
    echo "Finished patching $POCO_SOURCE"
fi

# patch breakpad

if [[ -d $TP_SOURCE_DIR/$BREAK_PAD_SOURCE ]] ; then
    cd $TP_SOURCE_DIR/$BREAK_PAD_SOURCE
    if [ ! -f "$PATCHED_MARK" ] && [[ $BREAK_PAD_SOURCE == "breakpad-2022.07.12" ]] ; then
        patch -p1 < "$TP_PATCH_DIR/breakpad-2022.07.12.patch"
        touch "$PATCHED_MARK"
    fi
    if [ ! -f "$PATCHED_MARK" ] && [[ $BREAK_PAD_SOURCE == "breakpad-2024.02.16" ]] ; then
        patch -p1 < "$TP_PATCH_DIR/breakpad-2024.02.16.patch"
        touch "$PATCHED_MARK"
    fi
    cd -
    echo "Finished patching $BREAK_PAD_SOURCE"
fi

# patch azure
if [[ -d $TP_SOURCE_DIR/$AZURE_SOURCE ]] ; then
    cd $TP_SOURCE_DIR/$AZURE_SOURCE
    if [ ! -f "$PATCHED_MARK" ] && [[ $AZURE_SOURCE == "azure-storage-files-shares_12.12.0" ]] ; then
        patch -p1 < "$TP_PATCH_DIR/azure-storage-files-shares_12.12.0.patch"
        touch "$PATCHED_MARK"
    fi
    cd -
    echo "Finished patching $AZURE_SOURCE"
fi

#patch cctz
if [[ -d $TP_SOURCE_DIR/$CCTZ_SOURCE ]] ; then
    cd $TP_SOURCE_DIR/$CCTZ_SOURCE
    if [ ! -f "$PATCHED_MARK" ] && [[ $CCTZ_SOURCE == "cctz-2.3" ]] ; then
        patch -p1 < "$TP_PATCH_DIR/cctz_civil_cache.patch"
        patch -p1 < "$TP_PATCH_DIR/cctz_02_lookup_offset.patch"
        touch "$PATCHED_MARK"
    fi
    cd -
    echo "Finished patching $CCTZ_SOURCE"
fi
