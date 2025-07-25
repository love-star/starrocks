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


package com.starrocks.journal;

import com.starrocks.common.Config;
import com.starrocks.common.io.DataOutputBuffer;
import com.starrocks.common.io.Text;
import com.starrocks.journal.bdbje.BDBJEJournal;
import mockit.Expectations;
import mockit.Mocked;
import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

public class JournalWriterTest {
    @Mocked
    private Journal journal = new BDBJEJournal(null);
    private BlockingQueue<JournalTask> journalQueue = new ArrayBlockingQueue<>(100);
    private JournalWriter writer = new JournalWriter(journal, journalQueue);
    /**
     * mock abortJournalTask() method to verify abort operations
     **/
    private JournalWriter abortedWriter = new JournalWriter(journal, journalQueue) {
        @Override
        protected void abortJournalTask(JournalTask task, String msg) {
            task.markAbort();
        }
    };

    @BeforeEach
    public void setup() throws Exception {
        writer.nextVisibleJournalId = 1;
        abortedWriter.nextVisibleJournalId = 1;
    }

    @AfterEach
    public void cleanup() throws Exception {
        // reset all configs
        Config.edit_log_roll_num = 50000;
        Config.metadata_journal_max_batch_size_mb = 100;
        Config.metadata_journal_max_batch_cnt = 100;
    }

    private DataOutputBuffer makeBuffer(int size) throws IOException {
        DataOutputBuffer buffer = new DataOutputBuffer();
        Text.writeString(buffer, StringUtils.repeat("x", size - 4));
        Assertions.assertEquals(size, buffer.getLength());
        return buffer;
    }

    @Disabled
    @Test
    public void testWriteOneLog() throws Exception {
        new Expectations(journal) {
            {
                journal.batchWriteBegin();
                times = 1;

                journal.batchWriteAppend(1, (DataOutputBuffer) any);
                times = 1;

                journal.batchWriteCommit();
                times = 1;
            }
        };
        Assertions.assertEquals(1, writer.nextVisibleJournalId);
        JournalTask task = new JournalTask(System.nanoTime(), makeBuffer(10), -1);
        journalQueue.add(task);
        writer.writeOneBatch();
        Assertions.assertEquals(1, writer.rollJournalCounter);
        Assertions.assertEquals(2, writer.nextVisibleJournalId);
        Assertions.assertEquals(1, writer.currentBatchTasks.size());
        Assertions.assertEquals(1, writer.currentBatchTasks.size());
        Assertions.assertEquals(0, task.latch.getCount());
    }

    @Disabled
    @Test
    public void testMustCommitBefore() throws Exception {
        new Expectations(journal) {
            {
                journal.batchWriteBegin();
                times = 1;

                journal.batchWriteAppend(1, (DataOutputBuffer) any);
                times = 1;

                journal.batchWriteAppend(2, (DataOutputBuffer) any);
                times = 1;

                journal.batchWriteCommit();
                times = 1;
            }
        };
        // 3 logs, the second one is emergency and must commit
        JournalTask expectConsumedEntity = new JournalTask(System.nanoTime(), makeBuffer(10), -1);
        journalQueue.add(expectConsumedEntity);
        JournalTask emergency = new JournalTask(System.nanoTime(), makeBuffer(10), -1);
        emergency.betterCommitBeforeTimeInNano = System.nanoTime() - 10;
        journalQueue.add(emergency);
        JournalTask expectNotConsumedEntity = new JournalTask(System.nanoTime(), makeBuffer(10), -1);
        journalQueue.add(expectNotConsumedEntity);

        Assertions.assertEquals(1, writer.nextVisibleJournalId);
        writer.writeOneBatch();
        Assertions.assertEquals(3, writer.nextVisibleJournalId);
        Assertions.assertEquals(2, writer.rollJournalCounter);
        Assertions.assertEquals(2, writer.currentBatchTasks.size());
        Assertions.assertEquals(0, expectConsumedEntity.latch.getCount());
        Assertions.assertEquals(0, emergency.latch.getCount());
        // one log left
        Assertions.assertEquals(1, expectNotConsumedEntity.latch.getCount());
        Assertions.assertEquals(1, journalQueue.size());
    }

    // TODO: this ut wastes too much memory
    @Disabled
    @Test
    public void testTooManyLogs() throws Exception {
        Config.metadata_journal_max_batch_cnt = 2;
        Config.metadata_journal_max_batch_size_mb = 2;
        new Expectations(journal) {
            {
                journal.batchWriteBegin();
                times = 2;
                journal.batchWriteAppend(1, (DataOutputBuffer) any);
                times = 1;
                journal.batchWriteAppend(2, (DataOutputBuffer) any);
                times = 1;
                journal.batchWriteAppend(3, (DataOutputBuffer) any);
                times = 1;
                journal.batchWriteCommit();
                times = 2;
            }
        };
        // 4 logs
        // round 1: 2 logs
        JournalTask expectConsumedEntity = new JournalTask(System.nanoTime(), makeBuffer(10), -1);
        journalQueue.add(expectConsumedEntity);
        JournalTask expectConsumedEntity2 = new JournalTask(System.nanoTime(), makeBuffer(10), -1);
        journalQueue.add(expectConsumedEntity2);
        // round 2: 1 big log
        JournalTask bigLog = new JournalTask(System.nanoTime(), makeBuffer(2 * 1024 * 1024 - 8), -1);
        Assertions.assertEquals(2 * 1024 * 1024, bigLog.estimatedSizeByte());
        journalQueue.add(bigLog);
        // this one should be left in queue
        JournalTask expectNotConsumedEntity = new JournalTask(System.nanoTime(), makeBuffer(10), -1);
        journalQueue.add(expectNotConsumedEntity);

        // round 1
        Assertions.assertEquals(1, writer.nextVisibleJournalId);
        writer.writeOneBatch();
        Assertions.assertEquals(3, writer.nextVisibleJournalId);
        Assertions.assertEquals(2, writer.rollJournalCounter);
        Assertions.assertEquals(2, writer.currentBatchTasks.size());
        Assertions.assertEquals(0, expectConsumedEntity.latch.getCount());
        Assertions.assertEquals(0, expectConsumedEntity2.latch.getCount());
        // two log left
        Assertions.assertEquals(1, bigLog.latch.getCount());
        Assertions.assertEquals(1, expectNotConsumedEntity.latch.getCount());
        Assertions.assertEquals(2, journalQueue.size());

        // round 2
        writer.writeOneBatch();
        Assertions.assertEquals(4, writer.nextVisibleJournalId);
        Assertions.assertEquals(3, writer.rollJournalCounter);
        Assertions.assertEquals(1, writer.currentBatchTasks.size());
        Assertions.assertEquals(0, bigLog.latch.getCount());
        // 1 log left
        Assertions.assertEquals(1, expectNotConsumedEntity.latch.getCount());
        Assertions.assertEquals(1, journalQueue.size());
    }

    @Disabled
    @Test
    public void testRollLog() throws Exception {
        Config.edit_log_roll_num = 4;

        // write 3 log * 4 times
        // expect roll 2 times, commit 4 times, put 12 times
        new Expectations(journal) {
            {
                journal.batchWriteBegin();
                times = 4;

                journal.batchWriteAppend(anyLong, (DataOutputBuffer) any);
                times = 12;

                journal.batchWriteCommit();
                times = 4;

                journal.rollJournal(7);
                times = 1;
                journal.rollJournal(13);
                times = 1;
            }
        };

        Assertions.assertEquals(1, writer.nextVisibleJournalId);
        for (int i = 0; i != 4; i++) {
            for (int j = 0; j != 3; j++) {
                journalQueue.add(new JournalTask(System.nanoTime(), makeBuffer(10), -1));
            }
            writer.writeOneBatch();
            Assertions.assertEquals(0, journalQueue.size());
        }
        Assertions.assertEquals(13, writer.nextVisibleJournalId);
    }

    @Disabled
    @Test
    public void testBatchWriteBeginException() throws Exception {
        JournalTask task1 = new JournalTask(System.nanoTime(), makeBuffer(10), -1);
        JournalTask task2 = new JournalTask(System.nanoTime(), makeBuffer(11), -1);
        journalQueue.add(task1);
        journalQueue.add(task2);
        new Expectations(journal) {
            {
                journal.batchWriteBegin();
                times = 1;
                result = new JournalException("mock batchWriteBegin exception");

                journal.batchWriteAbort();
                times = 1;
            }
        };
        Assertions.assertEquals(1, abortedWriter.nextVisibleJournalId);

        abortedWriter.writeOneBatch();

        Assertions.assertEquals(1, abortedWriter.nextVisibleJournalId);
        Assertions.assertFalse(task1.get());
        Assertions.assertEquals(1, journalQueue.size());
        Assertions.assertEquals(task2, journalQueue.take());
    }

    @Disabled
    @Test
    public void testBatchWriteAppendException() throws Exception {
        JournalTask task1 = new JournalTask(System.nanoTime(), makeBuffer(10), -1);
        JournalTask task2 = new JournalTask(System.nanoTime(), makeBuffer(11), -1);
        JournalTask task3 = new JournalTask(System.nanoTime(), makeBuffer(12), -1);
        journalQueue.add(task1);
        journalQueue.add(task2);
        journalQueue.add(task3);
        new Expectations(journal) {
            {
                journal.batchWriteBegin();
                times = 2;

                journal.batchWriteAppend(anyLong, (DataOutputBuffer) any);
                times = 3;
                result = new JournalException("mock batchWriteAppend exception");  // task1
                result = null;  // task2
                result = new JournalException("mock batchWriteAppend exception");  // task3

                journal.batchWriteCommit();
                times = 2;
            }
        };
        Assertions.assertEquals(1, abortedWriter.nextVisibleJournalId);
        abortedWriter.writeOneBatch();
        Assertions.assertEquals(1, abortedWriter.nextVisibleJournalId);
        Assertions.assertFalse(task1.get());
        Assertions.assertEquals(2, journalQueue.size());

        abortedWriter.writeOneBatch();
        Assertions.assertEquals(2, abortedWriter.nextVisibleJournalId);  // only task 2 succeed
        Assertions.assertTrue(task2.get());
        Assertions.assertFalse(task3.get());
        Assertions.assertEquals(0, journalQueue.size());
    }

    @Disabled
    @Test
    public void testBatchWriteCommitAbortException() throws Exception {
        JournalTask task1 = new JournalTask(System.nanoTime(), makeBuffer(10), -1);
        journalQueue.add(task1);

        new Expectations(journal) {
            {
                journal.batchWriteBegin();
                times = 2;

                journal.batchWriteAppend(anyLong, (DataOutputBuffer) any);
                times = 3;

                journal.batchWriteAbort();
                times = 2;
                result = null; // success first time
                result = new JournalException("mock batchWriteAbort exception");

                journal.batchWriteCommit();
                times = 2;
                result = new JournalException("mock batchWriteCommit exception");  // task1
                result = new JournalException("mock batchWriteCommit exception");  // task1 & task2
            }
        };

        // commit failed but abort succeed
        Assertions.assertEquals(1, abortedWriter.nextVisibleJournalId);
        abortedWriter.writeOneBatch();
        Assertions.assertEquals(1, abortedWriter.nextVisibleJournalId);
        Assertions.assertFalse(task1.get());
        Assertions.assertEquals(0, journalQueue.size());

        // both commit & abort failed
        JournalTask task2 = new JournalTask(System.nanoTime(), makeBuffer(11), -1);
        journalQueue.add(task1);
        journalQueue.add(task2);
        abortedWriter.writeOneBatch();
        Assertions.assertEquals(1, abortedWriter.nextVisibleJournalId);
        Assertions.assertFalse(task1.get());
        Assertions.assertFalse(task2.get());
        Assertions.assertEquals(0, journalQueue.size());
    }
}
