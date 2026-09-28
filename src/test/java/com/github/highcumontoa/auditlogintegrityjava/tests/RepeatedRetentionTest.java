package com.github.highcumontoa.auditlogintegrityjava.tests;

import com.github.highcumontoa.auditlogintegrityjava.config.AuditProperties;
import com.github.highcumontoa.auditlogintegrityjava.model.AuditEventInput;
import com.github.highcumontoa.auditlogintegrityjava.model.IntegrityReport;
import com.github.highcumontoa.auditlogintegrityjava.model.RetentionMode;
import com.github.highcumontoa.auditlogintegrityjava.model.RetentionReport;
import com.github.highcumontoa.auditlogintegrityjava.service.AuditIngestService;
import com.github.highcumontoa.auditlogintegrityjava.service.ChainHasher;
import com.github.highcumontoa.auditlogintegrityjava.service.EventNormalizer;
import com.github.highcumontoa.auditlogintegrityjava.service.IntegrityVerifier;
import com.github.highcumontoa.auditlogintegrityjava.service.RetentionService;
import com.github.highcumontoa.auditlogintegrityjava.store.FileAuditLogStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 连续多轮清理：锚点之间、锚点与新数据之间的链接始终连续且可校验。 */
class RepeatedRetentionTest {

    private static final Logger log = LoggerFactory.getLogger(RepeatedRetentionTest.class);

    @TempDir
    Path tempDir;

    @Test
    void multipleArchiveCyclesAndMixedModesKeepChainVerifiable() {
        AuditProperties props = new AuditProperties();
        props.setStorageDir(tempDir.toString());
        FileAuditLogStore store = new FileAuditLogStore(props, new ChainHasher());
        AuditIngestService ingest = new AuditIngestService(new EventNormalizer(), store);
        RetentionService retention = new RetentionService(store);
        IntegrityVerifier verifier = new IntegrityVerifier(store, new ChainHasher());

        long t = 1_000_000L;
        long now = t;

        // 第一轮：写入 3 条旧数据 + 2 条新数据，归档旧数据
        for (int i = 1; i <= 3; i++) {
            ingest(ingest, "e" + i, t + i);
        }
        now += 1_000_000L;
        for (int i = 4; i <= 5; i++) {
            ingest(ingest, "e" + i, now + i);
        }
        RetentionReport r1 = retention.enforceRetention(now, 500_000L, RetentionMode.ARCHIVE);
        log.info("cycle1 archived={} anchorSeq={} hash={}", r1.archivedRecords(), r1.anchorSequence(), r1.anchorHash());
        assertEquals(3, r1.archivedRecords());
        assertTrue(verifier.verify().intact());

        // 第二轮：又来 3 条新数据，时间推进后把上一轮留在活跃段的数据归档（DELETE 模式）
        now += 1_000_000L;
        for (int i = 6; i <= 8; i++) {
            ingest(ingest, "e" + i, now + i);
        }
        RetentionReport r2 = retention.enforceRetention(now, 500_000L, RetentionMode.DELETE);
        log.info("cycle2 archived={} anchorSeq={} hash={}", r2.archivedRecords(), r2.anchorSequence(), r2.anchorHash());
        assertEquals(2, r2.archivedRecords());

        IntegrityReport report = verifier.verify();
        log.info("after 2 cycles intact={} defects={} segmentStatuses={}",
                report.intact(), report.defects().size(),
                report.segments().stream().map(s -> s.segmentName() + ":" + s.status()).toList());
        assertTrue(report.defects().isEmpty(), "no tamper defects across mixed ARCHIVE/DELETE cycles");
        assertEquals(8L, report.headSequence(), "head reaches the newest data sequence");

        // 再追加一条：必须紧接锚点之后继续编号，校验仍通过
        var appended = ingest(ingest, "e9", now + 10_000L);
        log.info("post-cleanup append status={} sequence={}", appended.status(), appended.sequence());
        assertEquals(9L, appended.sequence());
        assertTrue(verifier.verify().defects().isEmpty());
    }

    private com.github.highcumontoa.auditlogintegrityjava.model.IngestResult ingest(
            AuditIngestService svc, String id, long eventTime) {
        AuditEventInput in = new AuditEventInput();
        in.setEventId(id);
        in.setSource("svc");
        in.setSubject("u");
        in.setAction("act");
        in.setEventTime(eventTime);
        return svc.ingest(in);
    }
}
