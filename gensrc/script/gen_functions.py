#!/usr/bin/env python
# encoding: utf-8

"""
  Copyright 2021-present StarRocks, Inc. All rights reserved.

  Licensed under the Apache License, Version 2.0 (the "License");
  you may not use this file except in compliance with the License.
  You may obtain a copy of the License at

       https://www.apache.org/licenses/LICENSE-2.0

  Unless required by applicable law or agreed to in writing, software
  distributed under the License is distributed on an "AS IS" BASIS,
  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  See the License for the specific language governing permissions and
  limitations under the License.
"""

import argparse
import os
import sys

from string import Template

import functions

sys.path.append(os.path.abspath(os.path.dirname(os.path.dirname(__file__))))

license_string = """
// Copyright 2021-present StarRocks, Inc. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      https://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

// This is a generated file, DO NOT EDIT.
// To add new functions, see the generator at
// common/function-registry/gen_builtins_catalog.py or the function list at
// common/function-registry/starrocks_builtins_functions.py.
"""

java_template = Template(
    """
${license}

package com.starrocks.builtins;

import com.starrocks.catalog.FunctionSet;
import com.starrocks.catalog.Type;

public class VectorizedBuiltinFunctions {
    public static void initBuiltins(FunctionSet functionSet) {
        ${functions}
  }
}

"""
)

cpp_template = """
#include "exprs/builtin_functions.h"
namespace starrocks {{
void __attribute__((constructor)) {module}_initialize() {{
{content}
}}
}}
"""

function_list = list()
function_set = set()
function_signature_set = set()


def add_function(fn_data):
    entry = dict()
    if fn_data[0] in function_set:
        print("=================================================================")
        print("Duplicated function id: " + str(fn_data))
        print("=================================================================")
        exit(1)
    function_set.add(fn_data[0])

    entry["id"] = fn_data[0]
    entry["name"] = fn_data[1]
    entry["exception_safe"] = str(fn_data[2]).lower()
    entry["check_overflow"] = str(fn_data[3]).lower()
    entry["ret"] = fn_data[4]
    entry["args"] = fn_data[5]

    function_signature = "%s#%s#(%s)" % (
        entry["ret"],
        entry["name"],
        ", ".join(entry["args"]),
    )

    if function_signature in function_signature_set:
        print("=================================================================")
        print("Duplicated function signature: " + function_signature)
        print("=================================================================")
        exit(1)
    function_signature_set.add(function_signature)

    if "..." in fn_data[5]:
        assert 2 <= len(fn_data[5]), "Invalid arguments in functions.py:\n\t" + repr(
            fn_data
        )
        assert (
            "..." == fn_data[5][-1]
        ), "variadic parameter must at the end:\n\t" + repr(fn_data)

        entry["args_nums"] = len(fn_data[5]) - 1
    else:
        entry["args_nums"] = len(fn_data[5])

    entry["fn"] = "&" + fn_data[6] if fn_data[6] != "nullptr" else "nullptr"

    if len(fn_data) >= 9:
        entry["prepare"] = "&" + fn_data[7] if fn_data[7] != "nullptr" else "nullptr"
        entry["close"] = "&" + fn_data[8] if fn_data[8] != "nullptr" else "nullptr"

    function_list.append(entry)


def generate_fe(path):
    fn_template = Template(
        'functionSet.addVectorizedScalarBuiltin(${id}, "${name}", ${has_vargs}, Type.${ret}${args_types});'
    )

    def gen_fe_fn(fnm):
        fnm["args_types"] = ", " if len(fnm["args"]) > 0 else ""
        fnm["args_types"] = fnm["args_types"] + ", ".join(
            ["Type." + i for i in fnm["args"] if i != "..."]
        )
        fnm["has_vargs"] = "true" if "..." in fnm["args"] else "false"

        return fn_template.substitute(fnm)

    value = dict()
    value["license"] = license_string
    value["functions"] = "\n        ".join([gen_fe_fn(i) for i in function_list])

    content = java_template.substitute(value)

    with open(path, mode="w+") as f:
        f.write(content)


def generate_cpp(path):
    def gen_be_fn(fnm):
        res = ""
        if "prepare" in fnm:
            res = '{%d, {"%s", %d, %s, %s, %s, %s, %s, "%s", {%s} }}' % (
                fnm["id"],
                fnm["name"],
                fnm["args_nums"],
                fnm["fn"],
                fnm["prepare"],
                fnm["close"],
                fnm["exception_safe"],
                fnm["check_overflow"],
                fnm['ret'], 
                ", ".join(['"%s"' % arg for arg in fnm['args']]),
            )
        else:
            res = '{%d, {"%s", %d, %s, %s, %s, "%s", {%s} }}' % (
                fnm["id"],
                fnm["name"],
                fnm["args_nums"],
                fnm["fn"],
                fnm["exception_safe"],
                fnm["check_overflow"],
                fnm['ret'], 
                ", ".join(['"%s"' % arg for arg in fnm['args']]),
            )

        return res

    value = dict()
    value["license"] = license_string
    value["functions"] = ", \n        ".join([gen_be_fn(i) for i in function_list])

    modules = [
        "MathFunctions",
        "StringFunctions",
        "LikePredicate",
        "BinaryFunctions",
        "BitFunctions",
        "TimeFunctions",
        "ConditionFunctions",
        "HyperloglogFunctions",
        "BitmapFunctions",
        "HashFunctions",
        "GroupingSetsFunctions",
        "StructFunctions",
        "UtilityFunctions",
        "JsonFunctions",
        "EncryptionFunctions",
        "ESFunctions",
        "GeoFunctions",
        "PercentileFunctions",
        "ArrayFunctions",
        "MapFunctions",
        "GinFunctions",
    ]

    modules_contents = dict()
    for module in modules:
        modules_contents[module] = ""

    for fnm in function_list:
        target = "Unknown"
        if fnm["fn"] == "nullptr":
            continue
        for module in modules:
            if module in fnm["fn"]:
                target = module
                break
        if target == "Unknown":
            print("fnm:" + fnm["fn"] + str(fnm))

        if "prepare" in fnm:
            modules_contents[target] = modules_contents[
                target
            ] + '\tBuiltinFunctions::emplace_builtin_function(static_cast<uint64_t>(%d), "%s", %d, %s, %s, %s, %s, %s);\n' % (
                fnm["id"],
                fnm["name"],
                fnm["args_nums"],
                fnm["fn"],
                fnm["prepare"],
                fnm["close"],
                fnm["exception_safe"],
                fnm["check_overflow"],
            )
        else:
            modules_contents[target] = modules_contents[
                target
            ] + '\tBuiltinFunctions::emplace_builtin_function(static_cast<uint64_t>(%d), "%s", %d, %s, %s, %s);\n' % (
                fnm["id"],
                fnm["name"],
                fnm["args_nums"],
                fnm["fn"],
                fnm["exception_safe"],
                fnm["check_overflow"],
            )

    for module in modules:
        with open(path + module + ".inc", mode="w+") as f:
            content = cpp_template.format(
                module=module, content=modules_contents[module]
            )
            f.write(content)


if __name__ == "__main__":
    FE_PATH = "../../fe/fe-core/target/generated-sources/build"
    BE_PATH = "../build/gen_cpp"

    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--cpp",
        dest="cpp_path",
        default=BE_PATH,
        help="Path of generated cpp file",
        type=str,
    )
    parser.add_argument(
        "--java",
        dest="java_path",
        default=FE_PATH,
        help="Path of generated java file",
        type=str,
    )
    args = parser.parse_args()

    be_functions_dir = args.cpp_path + "/opcode"
    if not os.path.exists(be_functions_dir):
        os.makedirs(be_functions_dir)

    fe_functions_dir = args.java_path + "/com/starrocks/builtins"
    if not os.path.exists(fe_functions_dir):
        os.makedirs(fe_functions_dir)

    # Read the function metadata inputs
    for function in functions.vectorized_functions:
        add_function(function)

    generate_fe(fe_functions_dir + "/VectorizedBuiltinFunctions.java")
    generate_cpp(be_functions_dir + "/")
