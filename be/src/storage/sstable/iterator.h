// Copyright (c) 2011 The LevelDB Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license.
// (https://developers.google.com/open-source/licenses/bsd)

#pragma once

#include "common/status.h"
#include "storage/sstable/sstable_predicate_fwd.h"
#include "util/slice.h"

namespace starrocks::sstable {

class Iterator {
public:
    Iterator();

    Iterator(const Iterator&) = delete;
    Iterator& operator=(const Iterator&) = delete;

    virtual ~Iterator();

    // An iterator is either positioned at a key/value pair, or
    // not valid.  This method returns true iff the iterator is valid.
    virtual bool Valid() const = 0;

    // Position at the first key in the source.  The iterator is Valid()
    // after this call iff the source is not empty.
    virtual void SeekToFirst() = 0;

    // Position at the last key in the source.  The iterator is
    // Valid() after this call iff the source is not empty.
    virtual void SeekToLast() = 0;

    // Position at the first key in the source that is at or past target.
    // The iterator is Valid() after this call iff the source contains
    // an entry that comes at or past target.
    virtual void Seek(const Slice& target) = 0;

    // Moves to the next entry in the source.  After this call, Valid() is
    // true iff the iterator was not positioned at the last entry in the source.
    // REQUIRES: Valid()
    virtual void Next() = 0;

    // Moves to the previous entry in the source.  After this call, Valid() is
    // true iff the iterator was not positioned at the first entry in source.
    // REQUIRES: Valid()
    virtual void Prev() = 0;

    // Return the key for the current entry.  The underlying storage for
    // the returned slice is valid only until the next modification of
    // the iterator.
    // REQUIRES: Valid()
    virtual Slice key() const = 0;

    // Return the value for the current entry.  The underlying storage for
    // the returned slice is valid only until the next modification of
    // the iterator.
    // REQUIRES: Valid()
    virtual Slice value() const = 0;

    // If an error has occurred, return it.  Else return an ok status.
    virtual Status status() const = 0;

    // Return the max rss_rowid the iterator contains.
    virtual uint64_t max_rss_rowid() const { return 0; };

    /*
     * Return predicate the iterator contains.
     * Currently, predicate is available for TwoLevelIterator and MergingIterator, because such
     * two kind of iterators is sstable-level iterator which derived from Iterator. Other kinds of
     * iterator will return empty predicate.
     * 
     * NOTE: If you use predicate, user must pay special attention that, currently, sstable predicate has not
     * been pushed down into storage layer(sstable-iterator level), which avoids modifying iterator-level
     * code and enhances stability. But at the same time, it means that user must handle the predicate
     * evaluation by themselves wherever they want to use it.
    */
    virtual SstablePredicateSPtr predicate() const { return nullptr; }

    // Clients are allowed to register function/arg1/arg2 triples that
    // will be invoked when this iterator is destroyed.
    //
    // Note that unlike all of the preceding methods, this method is
    // not abstract and therefore clients should not override it.
    using CleanupFunction = void (*)(void* arg1, void* arg2);
    void RegisterCleanup(CleanupFunction function, void* arg1, void* arg2);

private:
    // Cleanup functions are stored in a single-linked list.
    // The list's head node is inlined in the iterator.
    struct CleanupNode {
        // True if the node is not used. Only head nodes might be unused.
        bool IsEmpty() const { return function == nullptr; }
        // Invokes the cleanup function.
        void Run() {
            assert(function != nullptr);
            (*function)(arg1, arg2);
        }

        // The head node is used if the function pointer is not null.
        CleanupFunction function;
        void* arg1;
        void* arg2;
        CleanupNode* next;
    };
    CleanupNode cleanup_head_;
};

// Return an empty iterator (yields nothing).
Iterator* NewEmptyIterator();

// Return an empty iterator with the specified status.
Iterator* NewErrorIterator(const Status& status);

} // namespace starrocks::sstable