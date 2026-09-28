package com.github.highcumontoa.auditlogintegrityjava.tests;

import com.github.highcumontoa.auditlogintegrityjava.config.AuditProperties;
import com.github.highcumontoa.auditlogintegrityjava.model.AuditEventInput;
import com.github.highcumontoa.auditlogintegrityjava.model.AuditRecord;
import com.github.highcumontoa.auditlogintegrityjava.model.ChainEntry;
import com.github.highcumontoa.auditlogintegrityjava.model.IngestResult;
import com.github.highcumontoa.auditlogintegrityjava.model.IngestStatus;
import com.github.highcumontoa.auditlogintegrityjava.model.IntegrityReport;
import com.github.highcumontoa.auditlogintegrityjava.service.AuditIngestService;
import com.github.highcumontoa.auditlogintegrityjava.service.ChainHasher;
import com.github.highcumontoa.auditlogintegrityjava.service.EventNormalizer;
import com.github.highcumontoa.auditlogintegrityjava.service.IntegrityVerifier;
import com.github.highcumontoa.auditlogintegrityjava.store.FileAuditLogStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 并发追加：顺序编号连续、不重不漏；重复 eventId 幂等不重复入库。 */
class ConcurrentAppendTest {

    private static final Logger log = LoggerFactory.getLogger(ConcurrentAppendTest.class);

    @TempDir
    Path tempDir;

    private AuditIngestService newIngest() {
        AuditProperties props = new AuditProperties();
        props.setStorageDir(tempDir.toString());
        ChainHasher hasher = new ChainHasher();
        FileAuditLogStore store = new FileAuditLogStore(props, hasher);
        EventNormalizer normalizer = new EventNormalizer();
        return new AuditIngestService(normalizer, store);
    }

    private FileAuditLogStore newStore() {
        AuditProperties props = new AuditProperties();
        props.setStorageDir(tempDir.toString());
        return new FileAuditLogStore(props, new ChainHasher());
    }

    @Test
    void concurrentAppendsProduceContinuousUniqueSequence() throws Exception {
        AuditIngestService ingest = newIngest();
        int threads = 12;
        int perThread = 100;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        ConcurrentLinkedQueue<IngestResult> results = new ConcurrentLinkedQueue<>();

        for (int t = 0; t < threads; t++) {
            final int tid = t;
            pool.submit(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                for (int i = 0; i < perThread; i++) {
                    AuditEventInput in = new AuditEventInput();
                    in.setEventId("evt-" + tid + "-" + i);
                    in.setSource("source-" + (tid % 3));
                    in.setSubject("user-" + (i % 7));
                    in.setAction("op");
                    in.setEventTime(1_700_000_000_000L + tid * 1000L + i);
                    in.setOutcome(i % 2 == 0 ? "success" : "failure");
                    results.add(ingest.ingest(in));
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS), "threads finished in time");

        List<Long> seqs = new ArrayList<>();
        for (IngestResult r : results) {
            assertEquals(IngestStatus.ACCEPTED, r.status(), "every distinct event accepted");
            seqs.add(r.sequence());
        }
        log.info("concurrent inputs={} accepted={} distinctSequences={}",
                threads * perThread, results.size(), new HashSet<>(seqs).size());

        assertEquals(threads * perThread, results.size());
        Set<Long> distinct = new HashSet<>(seqs);
        assertEquals(threads * perThread, distinct.size(), "no duplicate sequence assigned");
        assertEquals(1L, Collections.min(seqs));
        assertEquals((long) threads * perThread, Collections.max(seqs));

        // 重新打开存储（模拟重启），从磁盘验证顺序与连续性
        FileAuditLogStore reopened = newStore();
        List<ChainEntry> chain = reopened.readActiveChain();
        long expected = 1L;
        String prev = ChainHasher.GENESIS_HASH;
        for (ChainEntry e : chain) {
            assertEquals(expected, e.sequence(), "sequence continuous on disk");
            assertEquals(prev, e.prevHash(), "prevHash matches write order");
            prev = e.entryHash();
            expected++;
        }
        log.info("reopened chain size={} lastSequence={} lastHash={}",
                chain.size(), chain.get(chain.size() - 1).sequence(),
                chain.get(chain.size() - 1).entryHash());

        IntegrityReport report = new IntegrityVerifier(reopened, new ChainHasher()).verify();
        log.info("post-concurrent verify intact={} defects={}", report.intact(), report.defects().size());
        assertTrue(report.intact(), () -> "chain intact after concurrency: " + report.defects());
    }

    @Test
    void duplicateEventIdIsIdempotentAcrossConcurrentWriters() throws Exception {
        AuditIngestService ingest = newIngest();
        int writers = 20;
        ExecutorService pool = Executors.newFixedThreadPool(writers);
        CountDownLatch start = new CountDownLatch(1);
        ConcurrentLinkedQueue<IngestResult> results = new ConcurrentLinkedQueue<>();
        for (int t = 0; t < writers; t++) {
            pool.submit(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                AuditEventInput in = new AuditEventInput();
                in.setEventId("same-event-id");
                in.setSource("svc");
                in.setSubject("u");
                in.setAction("act");
                in.setEventTime(1_700_000_000_000L);
                results.add(ingest.ingest(in));
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        long accepted = results.stream().filter(r -> r.status() == IngestStatus.ACCEPTED).count();
        long duplicate = results.stream().filter(r -> r.status() == IngestStatus.DUPLICATE).count();
        log.info("duplicate race writers={} accepted={} duplicates={} assignedSequence={}",
                writers, accepted, duplicate, results.peek().sequence());
        assertEquals(1, accepted, "exactly one row persisted");
        assertEquals(writers - 1, duplicate, "all others reported DUPLICATE");

        FileAuditLogStore store = newStore();
        List<AuditRecord> rows = store.readActiveRecords();
        assertEquals(1, rows.size(), "no duplicate row in storage");
    }
}
