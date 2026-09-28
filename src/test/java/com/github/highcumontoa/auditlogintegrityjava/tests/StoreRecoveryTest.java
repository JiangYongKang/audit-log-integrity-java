package com.github.highcumontoa.auditlogintegrityjava.tests;

import com.github.highcumontoa.auditlogintegrityjava.config.AuditProperties;
import com.github.highcumontoa.auditlogintegrityjava.core.AuditIngestionService;
import com.github.highcumontoa.auditlogintegrityjava.model.AuditEvent;
import com.github.highcumontoa.auditlogintegrityjava.model.AuditOutcome;
import com.github.highcumontoa.auditlogintegrityjava.model.IngestionResult;
import com.github.highcumontoa.auditlogintegrityjava.model.VerificationReport;
import com.github.highcumontoa.auditlogintegrityjava.storage.LocalSegmentStore;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 重启恢复测试：用同一目录重新构造存储实例，检查点重建后
 * 序号延续、去重索引恢复、完整性校验通过。
 */
class StoreRecoveryTest {

    private static final Logger LOG = Logger.getLogger(StoreRecoveryTest.class.getName());

    @Test
    void reopeningStoreRecoversFromCheckpoint() {
        AuditTestSupport.Services svc = AuditTestSupport.newServices(3, 90, "ARCHIVE");
        Path dir;
        try {
            dir = svc.dir();
            AuditIngestionService ingestion = new AuditIngestionService(svc.store());
            for (int i = 0; i < 10; i++) {
                ingestion.ingest(new AuditEvent("rec-" + i, "s", "u", "a",
                        i % 2 == 0 ? "SUCCESS" : "FAILURE", 1_700_000_000_000L + i, Map.of()));
            }
        } finally {
            // 只删内存对象，不删目录
        }

        AuditProperties props = new AuditProperties();
        props.getStorage().setDir(dir.toString());
        props.getSegment().setMaxRecords(3);
        LocalSegmentStore reopened = new LocalSegmentStore(props);
        try {
            AuditIngestionService ingestion2 = new AuditIngestionService(reopened);
            IngestionResult next = ingestion2.ingest(new AuditEvent("rec-10", "s", "u", "a",
                    "SUCCESS", 1_700_000_001_000L, Map.of()));
            LOG.info("after reopen append -> outcome=" + next.outcome()
                    + " sequence=" + next.sequence() + " segment=" + next.segment());
            assertEquals(AuditOutcome.ACCEPTED, next.outcome());
            assertEquals(10L, next.sequence(), "sequence must continue after recovery");

            IngestionResult dup = ingestion2.ingest(new AuditEvent("rec-0", "s", "u", "a",
                    "SUCCESS", 1_700_000_000_000L, Map.of()));
            LOG.info("after reopen duplicate -> outcome=" + dup.outcome()
                    + " existingSequence=" + dup.sequence());
            assertEquals(AuditOutcome.DUPLICATE, dup.outcome());
            assertEquals(0L, dup.sequence());

            VerificationReport report = new com.github.highcumontoa.auditlogintegrityjava
                    .core.IntegrityVerifier(reopened).verify();
            LOG.info("post-recovery verify intact=" + report.intact()
                    + " verifiedRecords=" + report.verifiedRecords()
                    + " issues=" + report.issues());
            assertTrue(report.intact(), report.issues().toString());
            assertEquals(11, report.verifiedRecords());
        } finally {
            AuditTestSupport.deleteRecursively(dir);
        }
    }
}
