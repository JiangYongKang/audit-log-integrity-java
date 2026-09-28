package com.github.highcumontoa.auditlogintegrityjava.tests;

import com.github.highcumontoa.auditlogintegrityjava.core.AuditIngestionService;
import com.github.highcumontoa.auditlogintegrityjava.model.AuditEvent;
import com.github.highcumontoa.auditlogintegrityjava.model.AuditOutcome;
import com.github.highcumontoa.auditlogintegrityjava.model.AuditRecord;
import com.github.highcumontoa.auditlogintegrityjava.model.IngestionResult;
import com.github.highcumontoa.auditlogintegrityjava.model.VerificationReport;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 并发追加边界测试：
 * 多来源、多线程同时写入时，全局序号必须连续、不重不漏；
 * 重复上报必须得到 DUPLICATE 且不重复入库；写入完成后完整性校验必须通过。
 */
class ConcurrentAppendTest {

    private static final Logger LOG = Logger.getLogger(ConcurrentAppendTest.class.getName());

    @Test
    void concurrentAppendsProduceContinuousUniqueSequence() throws Exception {
        AuditTestSupport.Services svc =
                AuditTestSupport.newServices(7, 90, "ARCHIVE");
        try {
            AuditIngestionService ingestion = new AuditIngestionService(svc.store());
            int threads = 8;
            int perThread = 50;
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(threads);
            ConcurrentLinkedQueue<IngestionResult> results = new ConcurrentLinkedQueue<>();

            for (int t = 0; t < threads; t++) {
                final int sourceIdx = t;
                pool.submit(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < perThread; i++) {
                            AuditEvent e = new AuditEvent(
                                    "src" + sourceIdx + "-evt" + i,
                                    "source-" + sourceIdx,
                                    "user-" + (i % 5),
                                    "action-" + (i % 3),
                                    (i % 2 == 0) ? "SUCCESS" : "FAILURE",
                                    System.currentTimeMillis(),
                                    Map.of("thread", String.valueOf(sourceIdx)));
                            results.add(ingestion.ingest(e));
                        }
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertTrue(done.await(60, TimeUnit.SECONDS), "append tasks did not finish in time");
            pool.shutdown();

            List<IngestionResult> accepted = results.stream()
                    .filter(r -> r.outcome() == AuditOutcome.ACCEPTED)
                    .collect(Collectors.toList());
            LOG.info("input: threads=" + threads + " perThread=" + perThread
                    + " total=" + (threads * perThread)
                    + " -> accepted=" + accepted.size() + " outcomes=" + results.size());

            // 不重：序号集合无重复
            Set<Long> uniqueSequences = accepted.stream()
                    .map(IngestionResult::sequence)
                    .collect(Collectors.toCollection(HashSet::new));
            assertEquals(threads * perThread, uniqueSequences.size(),
                    "duplicate sequences detected");

            // 不漏：序号恰好是 0..N-1
            for (long s = 0; s < (long) threads * perThread; s++) {
                assertTrue(uniqueSequences.contains(s), "missing sequence " + s);
            }

            // 记录落盘后的顺序与编号一致
            List<AuditRecord> records = svc.store().readAllRecords();
            assertEquals(threads * perThread, records.size());
            for (int i = 0; i < records.size(); i++) {
                assertEquals(i, records.get(i).sequence(),
                        "persisted order mismatch at index " + i);
            }
            // 段容量 7 -> 必然发生过段滚动
            long segmentCount = svc.store().listSegmentsInOrder().size();
            LOG.info("segments after concurrent append=" + segmentCount
                    + " (maxRecords=7, expect >1)");
            assertTrue(segmentCount > 1, "segment rotation should have occurred");

            VerificationReport report = svc.verifier().verify();
            LOG.info("verification intact=" + report.intact()
                    + " verifiedRecords=" + report.verifiedRecords()
                    + " issues=" + report.issues());
            assertTrue(report.intact(), "chain broken after concurrent append: " + report.issues());
            assertEquals((long) threads * perThread, report.verifiedRecords());
        } finally {
            svc.deleteDir();
        }
    }

    @Test
    void duplicateEventIdIsRejectedAndNotStoredTwice() {
        AuditTestSupport.Services svc = AuditTestSupport.newServices(100, 90, "ARCHIVE");
        try {
            AuditIngestionService ingestion = new AuditIngestionService(svc.store());
            AuditEvent first = new AuditEvent("dup-1", "s", "u", "a", "SUCCESS",
                    1_700_000_000_000L, Map.of());
            AuditEvent second = new AuditEvent("dup-1", "s", "u", "a", "SUCCESS",
                    1_700_000_000_001L, Map.of("resent", "true"));

            IngestionResult r1 = ingestion.ingest(first);
            IngestionResult r2 = ingestion.ingest(second);
            LOG.info("input first=" + first + " -> " + r1);
            LOG.info("input second(duplicate eventId)=" + second + " -> " + r2);

            assertEquals(AuditOutcome.ACCEPTED, r1.outcome());
            assertEquals(AuditOutcome.DUPLICATE, r2.outcome());
            assertEquals(0L, r2.sequence());

            List<AuditRecord> records = new ArrayList<>(svc.store().readAllRecords());
            assertEquals(1, records.size(), "duplicate must not be stored twice");
            assertEquals(1_700_000_000_000L, records.get(0).eventTime(),
                    "duplicate must not overwrite original record");
        } finally {
            svc.deleteDir();
        }
    }

    @Test
    void invalidEventsAreRejectedNotSilentlyDropped() {
        AuditTestSupport.Services svc = AuditTestSupport.newServices(100, 90, "ARCHIVE");
        try {
            AuditIngestionService ingestion = new AuditIngestionService(svc.store());
            IngestionResult bad = ingestion.ingest(
                    new AuditEvent("x", "s", "u", "a", "BOGUS", 1L, null));
            IngestionResult good = ingestion.ingest(
                    new AuditEvent("ok", "s", "u", "a", "SUCCESS", 1L, Map.of()));
            LOG.info("bogus result -> outcome=" + bad.outcome()
                    + " reason=" + bad.rejectReason() + " message=" + bad.message());
            LOG.info("valid result -> " + good);
            assertEquals(AuditOutcome.REJECTED, bad.outcome());
            assertEquals(AuditOutcome.ACCEPTED, good.outcome());
            assertEquals(0L, good.sequence(), "rejected records must not consume a sequence");
            assertEquals(1, svc.store().readAllRecords().size());
        } finally {
            svc.deleteDir();
        }
    }
}
