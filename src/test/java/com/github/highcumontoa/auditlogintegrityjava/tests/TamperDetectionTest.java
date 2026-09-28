package com.github.highcumontoa.auditlogintegrityjava.tests;

import com.github.highcumontoa.auditlogintegrityjava.core.AuditIngestionService;
import com.github.highcumontoa.auditlogintegrityjava.model.AuditEvent;
import com.github.highcumontoa.auditlogintegrityjava.model.IssueType;
import com.github.highcumontoa.auditlogintegrityjava.model.SegmentMeta;
import com.github.highcumontoa.auditlogintegrityjava.model.VerificationIssue;
import com.github.highcumontoa.auditlogintegrityjava.model.VerificationReport;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 事后篡改检测测试：
 * 1) 改写某条记录字段 -> HASH_MISMATCH，定位到段名+序号；
 * 2) 删除某条记录 -> SEQUENCE_GAP + CHAIN_BROKEN，位置即删除点；
 * 3) 中途插入伪造记录 -> SEQUENCE_DUPLICATE/CHAIN_BROKEN；
 * 4) 段文件整体缺失（未登记删除）-> SEGMENT_FILE_MISSING；
 * 5) 正常基线校验通过。
 */
class TamperDetectionTest {

    private static final Logger LOG = Logger.getLogger(TamperDetectionTest.class.getName());
    private static final long BASE_TIME = 1_700_000_000_000L;

    private AuditTestSupport.Services appendSegments(int maxRecords, int count) {
        AuditTestSupport.Services svc = AuditTestSupport.newServices(maxRecords, 90, "ARCHIVE");
        AuditIngestionService ingestion = new AuditIngestionService(svc.store());
        for (int i = 0; i < count; i++) {
            ingestion.ingest(new AuditEvent("tamper-evt-" + i, "source-" + (i % 2),
                    "user-" + (i % 3), "action", i % 2 == 0 ? "SUCCESS" : "FAILURE",
                    BASE_TIME + i * 1000L, Map.of("idx", String.valueOf(i))));
        }
        return svc;
    }

    @Test
    void intactBaselinePasses() {
        AuditTestSupport.Services svc = appendSegments(3, 7);
        try {
            VerificationReport report = svc.verifier().verify();
            LOG.info("baseline verify intact=" + report.intact()
                    + " verifiedRecords=" + report.verifiedRecords()
                    + " segments=" + svc.store().listSegmentsInOrder().size());
            assertTrue(report.intact(), "baseline should be intact: " + report.issues());
            assertEquals(7, report.verifiedRecords());
        } finally {
            svc.deleteDir();
        }
    }

    @Test
    void modifyingARecordFieldIsDetectedAtExactPosition() throws Exception {
        AuditTestSupport.Services svc = appendSegments(3, 7);
        try {
            SegmentMeta second = svc.store().listSegmentsInOrder().get(1);
            Path file = svc.store().segmentFile(second.state(), second.name());
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            // 改写第二段第一条记录（全局序号 3）的 subject
            String tampered = lines.get(0).replace("user-0", "user-EVIL");
            Files.write(file, List.of(tampered), StandardCharsets.UTF_8);
            LOG.info("tamper action: rewrite subject at segment=" + second.name()
                    + " expectedSequence=3");

            VerificationReport report = svc.verifier().verify();
            assertFalse(report.intact());
            boolean hit = report.issues().stream()
                    .anyMatch(i -> i.type() == IssueType.HASH_MISMATCH
                            && second.name().equals(i.segment())
                            && i.sequence() == 3L);
            report.issues().stream().map(VerificationIssue::describe).forEach(LOG::warning);
            assertTrue(hit, "HASH_MISMATCH should be located at segment=" + second.name()
                    + " sequence=3, issues=" + report.issues());
        } finally {
            svc.deleteDir();
        }
    }

    @Test
    void deletingARecordIsDetectedAtDeletePoint() throws Exception {
        AuditTestSupport.Services svc = appendSegments(5, 10);
        try {
            SegmentMeta first = svc.store().listSegmentsInOrder().get(0);
            Path file = svc.store().segmentFile(first.state(), first.name());
            List<String> lines = new java.util.ArrayList<>(Files.readAllLines(file, StandardCharsets.UTF_8));
            // 删除序号 2 的记录（段内第三行）
            String removed = lines.remove(2);
            Files.write(file, lines, StandardCharsets.UTF_8);
            LOG.info("tamper action: delete record at expectedSequence=2, removedLine=" + removed);

            VerificationReport report = svc.verifier().verify();
            assertFalse(report.intact());
            boolean gap = report.issues().stream()
                    .anyMatch(i -> i.type() == IssueType.SEQUENCE_GAP
                            && first.name().equals(i.segment()) && i.sequence() == 3L);
            boolean chain = report.issues().stream()
                    .anyMatch(i -> i.type() == IssueType.CHAIN_BROKEN
                            && first.name().equals(i.segment()) && i.sequence() == 3L);
            report.issues().stream().map(VerificationIssue::describe).forEach(LOG::warning);
            assertTrue(gap, "SEQUENCE_GAP at sequence 3 expected: " + report.issues());
            assertTrue(chain, "CHAIN_BROKEN at sequence 3 expected: " + report.issues());
        } finally {
            svc.deleteDir();
        }
    }

    @Test
    void insertingARecordInMiddleIsDetected() throws Exception {
        AuditTestSupport.Services svc = appendSegments(5, 10);
        try {
            SegmentMeta first = svc.store().listSegmentsInOrder().get(0);
            Path file = svc.store().segmentFile(first.state(), first.name());
            List<String> lines = new java.util.ArrayList<>(Files.readAllLines(file, StandardCharsets.UTF_8));
            // 复制第 3 行（序号 2）伪造一份插到其前面 -> 出现序号 2 重复 + 链断裂
            String forged = lines.get(2);
            lines.add(2, forged);
            Files.write(file, lines, StandardCharsets.UTF_8);
            LOG.info("tamper action: duplicate-insert a copy of sequence=2 line");

            VerificationReport report = svc.verifier().verify();
            assertFalse(report.intact());
            boolean dup = report.issues().stream()
                    .anyMatch(i -> i.type() == IssueType.SEQUENCE_DUPLICATE
                            && first.name().equals(i.segment()) && i.sequence() == 2L);
            boolean chain = report.issues().stream()
                    .anyMatch(i -> i.type() == IssueType.CHAIN_BROKEN
                            && first.name().equals(i.segment()));
            report.issues().stream().map(VerificationIssue::describe).forEach(LOG::warning);
            assertTrue(dup, "SEQUENCE_DUPLICATE at sequence 2 expected: " + report.issues());
            assertTrue(chain, "CHAIN_BROKEN expected after insertion: " + report.issues());
        } finally {
            svc.deleteDir();
        }
    }

    @Test
    void undeclaredSegmentFileDeletionIsDetected() {
        AuditTestSupport.Services svc = appendSegments(3, 7);
        try {
            SegmentMeta second = svc.store().listSegmentsInOrder().get(1);
            Path file = svc.store().segmentFile(second.state(), second.name());
            assertTrue(file.toFile().delete());
            LOG.info("tamper action: delete segment file " + second.name()
                    + " without checkpoint update");

            VerificationReport report = svc.verifier().verify();
            assertFalse(report.intact());
            boolean missing = report.issues().stream()
                    .anyMatch(i -> i.type() == IssueType.SEGMENT_FILE_MISSING
                            && second.name().equals(i.segment()));
            report.issues().stream().map(VerificationIssue::describe).forEach(LOG::warning);
            assertTrue(missing, "SEGMENT_FILE_MISSING expected for " + second.name()
                    + ": " + report.issues());
        } finally {
            svc.deleteDir();
        }
    }
}
