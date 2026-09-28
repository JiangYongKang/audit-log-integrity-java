package com.github.highcumontoa.auditlogintegrityjava.tests;

import com.github.highcumontoa.auditlogintegrityjava.config.AuditProperties;
import com.github.highcumontoa.auditlogintegrityjava.model.AuditEventInput;
import com.github.highcumontoa.auditlogintegrityjava.model.AuditRecord;
import com.github.highcumontoa.auditlogintegrityjava.model.IntegrityReport;
import com.github.highcumontoa.auditlogintegrityjava.model.QueryCriteria;
import com.github.highcumontoa.auditlogintegrityjava.model.QueryResult;
import com.github.highcumontoa.auditlogintegrityjava.model.RetentionMode;
import com.github.highcumontoa.auditlogintegrityjava.model.RetentionReport;
import com.github.highcumontoa.auditlogintegrityjava.model.VerificationSegment;
import com.github.highcumontoa.auditlogintegrityjava.service.AuditIngestService;
import com.github.highcumontoa.auditlogintegrityjava.service.AuditQueryService;
import com.github.highcumontoa.auditlogintegrityjava.service.ChainHasher;
import com.github.highcumontoa.auditlogintegrityjava.service.EventNormalizer;
import com.github.highcumontoa.auditlogintegrityjava.service.IntegrityVerifier;
import com.github.highcumontoa.auditlogintegrityjava.service.RetentionService;
import com.github.highcumontoa.auditlogintegrityjava.store.FileAuditLogStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 保留期边界：只剪切到期前缀；ARCHIVE 后跨段可查可校验；DELETE 后结论可解释。 */
class RetentionAndQueryTest {

    private static final Logger log = LoggerFactory.getLogger(RetentionAndQueryTest.class);
    private static final long NOW = 2_000_000_000_000L;

    @TempDir
    Path tempDir;

    private FileAuditLogStore store;
    private AuditIngestService ingest;
    private RetentionService retention;
    private AuditQueryService query;
    private IntegrityVerifier verifier;

    @BeforeEach
    void setup() {
        AuditProperties props = new AuditProperties();
        props.setStorageDir(tempDir.toString());
        store = new FileAuditLogStore(props, new ChainHasher());
        ingest = new AuditIngestService(new EventNormalizer(), store);
        retention = new RetentionService(store);
        query = new AuditQueryService(store);
        verifier = new IntegrityVerifier(store, new ChainHasher());
    }

    private void seedRecords(int n, long baseTime) {
        for (int i = 1; i <= n; i++) {
            AuditEventInput in = new AuditEventInput();
            in.setEventId("evt-" + i);
            in.setSource("svc-" + (i % 2));
            in.setSubject(i <= 3 ? "alice" : "bob");
            in.setAction("login");
            in.setEventTime(baseTime + i * 1000L);
            in.setOutcome(i % 2 == 0 ? "failure" : "success");
            ingest.ingest(in);
        }
    }

    private void ingestAt(String eventId, String subject, long eventTime) {
        AuditEventInput in = new AuditEventInput();
        in.setEventId(eventId);
        in.setSource("svc");
        in.setSubject(subject);
        in.setAction("login");
        in.setEventTime(eventTime);
        in.setOutcome("success");
        ingest.ingest(in);
    }

    @Test
    void archiveCutsOnlyExpiredPrefixAndChainStaysIntact() {
        // 事件时间横跨 cutoff：evt1..evt3 到期，evt4 恰在边界上（eventTime < cutoff 才剪切，故保留），evt5 更新
        long cutoff = NOW - 100_000L;
        ingestAt("evt-1", "alice", cutoff - 200_000L);
        ingestAt("evt-2", "alice", cutoff - 100_000L);
        ingestAt("evt-3", "alice", cutoff - 1L);
        ingestAt("evt-4", "bob", cutoff);
        ingestAt("evt-5", "bob", cutoff + 50_000L);

        RetentionReport report = retention.enforceRetention(NOW, 100_000L, RetentionMode.ARCHIVE);
        log.info("retention cutoff={} archivedRecords={} anchorRange=[{},{}] newActiveFirstSeq={}",
                report.cutoffTime(), report.archivedRecords(),
                report.anchorSequence() - report.archivedRecords() + 1, report.anchorSequence(),
                report.newActiveFirstSequence());

        assertEquals(3, report.archivedRecords(), "only the strictly-expired prefix is cut");
        assertEquals(4L, report.newActiveFirstSequence(), "active tail resumes right after the anchor range");

        List<AuditRecord> active = store.readActiveRecords();
        assertEquals(2, active.size());
        assertEquals(4L, active.get(0).getSequence(), "sequence continuous across the anchor boundary");
        assertEquals(5L, active.get(1).getSequence());
        for (AuditRecord r : active) {
            assertTrue(r.getEventTime() >= cutoff, "remaining records are within the retention window");
        }

        IntegrityReport verify = verifier.verify();
        log.info("post-archive verify intact={} segments={} defects={}",
                verify.intact(), verify.segments().size(), verify.defects().size());
        assertTrue(verify.intact(), () -> "chain intact across archive boundary: " + verify.defects());
        assertEquals(1, store.listArchiveFiles().size());
    }

    @Test
    void nothingExpiredIsNoOp() {
        seedRecords(3, NOW - 1000L); // 全部很新
        RetentionReport report = retention.enforceRetention(NOW, 100_000L, RetentionMode.ARCHIVE);
        log.info("retention no-op archivedRecords={} segments={}", report.archivedRecords(), report.archivedSegments());
        assertEquals(0, report.archivedRecords());
        assertEquals(0, report.archivedSegments());
        assertEquals(3, store.readActiveRecords().size());
        assertTrue(verifier.verify().intact());
    }

    @Test
    void querySpansActiveAndArchivedSegmentsWithFilters() {
        long cutoff = NOW - 100_000L;
        // alice 的 3 条全部到期；bob 的 3 条全部在保留窗内
        ingestAt("evt-1", "alice", cutoff - 300_000L);
        ingestAt("evt-2", "alice", cutoff - 200_000L);
        ingestAt("evt-3", "alice", cutoff - 1L);
        ingestAt("evt-4", "bob", cutoff);
        ingestAt("evt-5", "bob", cutoff + 10_000L);
        ingestAt("evt-6", "bob", cutoff + 20_000L);
        retention.enforceRetention(NOW, 100_000L, RetentionMode.ARCHIVE);

        QueryCriteria criteria = new QueryCriteria();
        criteria.setIncludeArchived(true);
        criteria.setSubject("alice");
        QueryResult result = query.query(criteria);
        log.info("query subject=alice includeArchived=true total={} scanned={} unavailable={} seqs={}",
                result.total(), result.scannedSegments(), result.unavailableSegments(),
                result.records().stream().map(AuditRecord::getSequence).toList());
        assertEquals(3, result.total(), "alice's records all live in the archived segment");
        assertTrue(result.scannedSegments().size() >= 2);
        assertTrue(result.unavailableSegments().isEmpty());

        QueryCriteria failureOnly = new QueryCriteria();
        failureOnly.setIncludeArchived(true);
        failureOnly.setSubject("bob");
        QueryResult bob = query.query(failureOnly);
        log.info("query subject=bob total={} (all fresh, in active segment)", bob.total());
        assertEquals(3, bob.total());
    }

    @Test
    void deleteModeRemovesSegmentButKeainsExplainableAnchor() {
        long cutoff = NOW - 100_000L;
        ingestAt("evt-1", "alice", cutoff - 300_000L);
        ingestAt("evt-2", "alice", cutoff - 200_000L);
        ingestAt("evt-3", "alice", cutoff - 1L);
        ingestAt("evt-4", "bob", cutoff);
        RetentionReport rr = retention.enforceRetention(NOW, 100_000L, RetentionMode.DELETE);
        log.info("delete retention archivedRecords={} segment={} anchorHash={}",
                rr.archivedRecords(), rr.segmentFiles(), rr.anchorHash());
        assertTrue(rr.archivedRecords() > 0);
        Path segPath = tempDir.resolve(FileAuditLogStore.ARCHIVE_DIR).resolve(rr.segmentFiles().get(0));
        assertFalse(Files.exists(segPath), "segment file physically removed in DELETE mode");

        IntegrityReport report = verifier.verify();
        log.info("post-delete verify intact={} segmentStatuses={}",
                report.intact(),
                report.segments().stream().map(s -> s.segmentName() + ":" + s.status()).toList());
        // DELETE 是策略内的可解释状态：没有缺陷（defects），仅存在 DELETED 状态的段
        assertTrue(report.defects().isEmpty(), "no tamper defects for policy-driven deletion");
        assertTrue(report.segments().stream()
                .anyMatch(s -> s.status() == VerificationSegment.SegmentStatus.DELETED));

        QueryResult qr = query.query(new QueryCriteria() {{
            setIncludeArchived(true);
        }});
        log.info("post-delete query total={} unavailable={}", qr.total(), qr.unavailableSegments());
        assertEquals(1, qr.unavailableSegments().size(), "missing range is explicitly disclosed");
        for (AuditRecord r : qr.records()) {
            assertTrue(r.getEventTime() >= NOW - 100_000L, "only still-retained records are returned");
        }
    }
}
