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
//   https://github.com/apache/incubator-doris/blob/master/fe/fe-core/src/main/java/org/apache/doris/analysis/ExprSubstitutionMap.java

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

package com.starrocks.analysis;

import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Map of expression substitutions: lhs[i] gets substituted with rhs[i].
 * To support expression substitution across query blocks, rhs exprs must already be
 * analyzed when added to this map. Otherwise, analysis of a SlotRef may fail after
 * substitution, e.g., because the table it refers to is in a different query block
 * that is not visible.
 * See Expr.substitute() and related functions for details on the actual substitution.
 */
public final class ExprSubstitutionMap {
    private static final Logger LOG = LoggerFactory.getLogger(ExprSubstitutionMap.class);

    private boolean checkAnalyzed_ = true;
    private List<Expr> lhs_; // left-hand side
    private List<Expr> rhs_; // right-hand side

    public ExprSubstitutionMap() {
        this(Lists.<Expr>newArrayList(), Lists.<Expr>newArrayList());
    }

    // Only used to convert show statement to select statement
    public ExprSubstitutionMap(boolean checkAnalyzed) {
        this(Lists.<Expr>newArrayList(), Lists.<Expr>newArrayList());
        this.checkAnalyzed_ = checkAnalyzed;
    }

    public ExprSubstitutionMap(List<Expr> lhs, List<Expr> rhs) {
        lhs_ = lhs;
        rhs_ = rhs;
    }

    /**
     * Add an expr mapping. The rhsExpr must be analyzed to support correct substitution
     * across query blocks. It is not required that the lhsExpr is analyzed.
     */
    public void put(Expr lhsExpr, Expr rhsExpr) {
        Preconditions.checkState(!checkAnalyzed_ || rhsExpr.isAnalyzed(),
                "Rhs expr must be analyzed.");
        lhs_.add(lhsExpr);
        rhs_.add(rhsExpr);
    }

    /**
     * Returns the expr mapped to lhsExpr or null if no mapping to lhsExpr exists.
     */
    public Expr get(Expr lhsExpr) {
        for (int i = 0; i < lhs_.size(); ++i) {
            if (lhsExpr.equals(lhs_.get(i))) {
                return rhs_.get(i);
            }
        }
        return null;
    }

    /**
     * Returns the union of two substitution maps. Always returns a non-null map.
     */
    public static ExprSubstitutionMap combine(ExprSubstitutionMap f,
                                              ExprSubstitutionMap g) {
        if (f == null && g == null) {
            return new ExprSubstitutionMap();
        }
        if (f == null) {
            return g;
        }
        if (g == null) {
            return f;
        }
        ExprSubstitutionMap result = new ExprSubstitutionMap();
        result.lhs_ = Lists.newArrayList(f.lhs_);
        result.lhs_.addAll(g.lhs_);
        result.rhs_ = Lists.newArrayList(f.rhs_);
        result.rhs_.addAll(g.rhs_);
        result.verify();
        return result;
    }

    public List<Expr> getLhs() {
        return lhs_;
    }

    public List<Expr> getRhs() {
        return rhs_;
    }

    public int size() {
        return lhs_.size();
    }

    public String debugString() {
        Preconditions.checkState(lhs_.size() == rhs_.size());
        List<String> output = Lists.newArrayList();
        for (int i = 0; i < lhs_.size(); ++i) {
            output.add(lhs_.get(i).toSql() + ":" + rhs_.get(i).toSql());
            output.add("(" + lhs_.get(i).debugString() + ":" + rhs_.get(i).debugString() + ")");
        }
        return "smap(" + Joiner.on(" ").join(output) + ")";
    }

    /**
     * Verifies the internal state of this smap: Checks that the lhs_ has no duplicates,
     * and that all rhs exprs are analyzed.
     */
    private void verify() {
        for (int i = 0; i < lhs_.size(); ++i) {
            for (int j = i + 1; j < lhs_.size(); ++j) {
                if (lhs_.get(i).equals(lhs_.get(j))) {
                    if (LOG.isTraceEnabled()) {
                        LOG.trace("verify: smap=" + this.debugString());
                    }
                    // TODO(zc): partition by k1, order by k1, there is failed.
                    // Preconditions.checkState(false);
                }
            }
            Preconditions.checkState(!checkAnalyzed_ || rhs_.get(i).isAnalyzed());
        }
    }

    public void clear() {
        lhs_.clear();
        rhs_.clear();
    }

    @Override
    public ExprSubstitutionMap clone() {
        return new ExprSubstitutionMap(Expr.cloneList(lhs_), Expr.cloneList(rhs_));
    }
}
