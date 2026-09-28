package com.github.highcumontoa.auditlogintegrityjava.tests;

import com.github.highcumontoa.auditlogintegrityjava.core.AuditIngestionService;
import com.github.highcumontoa.auditlogintegrityjava.model.AuditEvent;
import com.github.highcumontoa.auditlogintegrityjava.model.AuditRecord;
import com.github.highcumontoa.auditlogintegrityjava.model.CleanupResult;
import com.github.highcumontoa.auditlogintegrityjava.model.QueryFilter;
import com.github.highcumontoa.auditlogintegrityjava.model.SegmentMeta;
import com.github.highcumontoa.auditlogintegrityjava.model.SegmentState;
import com.github.highcumontoa.auditlogintegrityjava.model.VerificationReport;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 到期清理边界测试：
 * 1) ARCHIVE 模式：老段移入归档仍可查询、可完整校验；
 * 2) DELETE 模式：老段数据删除，查询只剩留存记录，校验报告 intact 且
 *    deletedSegments 说明缺口来源，首序号与检查点一致；
 * 3) 边界：恰好在保留期切点上的段（maxEventTime == cutoff）不清理；
 * 4) 清理后继续追加，新记录链跨越 DELETED 段后校验仍通过（边界可解释）。
 */
class RetentionCleanupTest {

    private static final Logger LOG = Logger.getLogger(RetentionCleanupTest.class.getName());

    private static final long DAY = 86_400_000L;
    /** 以固定“现在”为基准：day0~day4 的事件，每天 2 条，每段 2 条 => 每天一段 */
    private static final long NOW = 1_700_000_000_000L;

    private AuditTestSupport.Services seed(int retentionDays, String mode) {
        AuditTestSupport.Services svc = AuditTestSupport.newServices(2, retentionDays, mode);
        AuditIngestionService ingestion = new AuditIngestionService(svc.store());
        for (int day = 0; day < 5; day++) {
            for (int j = 0; j < 2; j++) {
                int i = day * 2 + j;
                ingestion.ingest(new AuditEvent("ret-evt-" + i, "src",
                        "user-" + (i % 4), "act", "SUCCESS",
                        NOW - (4 - day) * DAY + j * 1000L, Map.of()));
            }
        }
        return svc;
    }

    @Test
    void archiveModeKeepsRecordsQueryableAndIntact() {
        AuditTestSupport.Services svc = seed(2, "ARCHIVE");
        try {
            List<SegmentMeta> before = svc.store().listSegmentsInOrder();
            LOG.info("before sweep segments=" + before.size()
                    + " oldestMaxEventTime=" + before.get(0).maxEventTime()
                    + " cutoff=" + (NOW - 2 * DAY));

            CleanupResult result = svc.retention().sweep(NOW);
            LOG.info("ARCHIVE sweep result archived=" + result.archivedSegments()
                    + " removed=" + result.removedSegments());
            assertFalse(result.archivedSegments().isEmpty(), "old segments should be archived");

            // 归档段仍可读，全部记录仍可查询
            List<AuditRecord> all = svc.queryService().query(new QueryFilter(null, null, null, null));
            assertEquals(10, all.size(), "archived records must remain queryable");

            VerificationReport report = svc.verifier().verify();
            LOG.info("post-archive verify intact=" + report.intact()
                    + " verifiedRecords=" + report.verifiedRecords()
                    + " deletedSegments=" + report.deletedSegments()
                    + " issues=" + report.issues());
            assertTrue(report.intact(), report.issues().toString());
            assertEquals(10, report.verifiedRecords());
            assertEquals(0, report.deletedSegments());

            assertTrue(svc.store().listSegmentsInOrder().stream()
                    .limit(result.archivedSegments().size())
                    .allMatch(m -> m.state() == SegmentState.ARCHIVED));
        } finally {
            svc.deleteDir();
        }
    }

    @Test
    void deleteModeRemovesDataButChainRemainsExplainableAndIntact() {
        AuditTestSupport.Services svc = seed(2, "DELETE");
        try {
            CleanupResult result = svc.retention().sweep(NOW);
            LOG.info("DELETE sweep result removed=" + result.removedSegments()
                    + " removedRecords=" + result.removedRecords()
                    + " newFirstSequence=" + result.newFirstSequence());

            // day0/day1/day2(部分?) —— 段边界：第0、1段是 day0/day1，maxEventTime < now-2d
            assertFalse(result.removedSegments().isEmpty());
            assertEquals(4L, result.removedRecords(), "two oldest segments (day0,day1) expire");
            assertEquals(4L, result.newFirstSequence(), "surviving records start at sequence 4");

            List<AuditRecord> remaining =
                    svc.queryService().query(new QueryFilter(null, null, null, null));
            assertEquals(6, remaining.size(), "only surviving records are queryable");
            assertEquals(4L, remaining.get(0).sequence());

            VerificationReport report = svc.verifier().verify();
            LOG.info("post-delete verify intact=" + report.intact()
                    + " verifiedRecords=" + report.verifiedRecords()
                    + " deletedSegments=" + report.deletedSegments()
                    + " firstSequence=" + report.firstSequence()
                    + " issues=" + report.issues());
            assertTrue(report.intact(), "registered deleted boundary must not be tamper: "
                    + report.issues());
            assertEquals(2, report.deletedSegments());
            assertEquals(6, report.verifiedRecords());
            assertEquals(4L, report.firstSequence());
        } finally {
            svc.deleteDir();
        }
    }

    @Test
    void segmentAtExactCutoffIsNotRemoved() {
        // 保留期 3 天，cutoff = now-3d：
        // day0 段 maxEventTime = now-4d+1s < cutoff，到期删除；
        // day1 段 maxEventTime = now-3d+1s >= cutoff（切点包含），必须保留。
        AuditTestSupport.Services svc = seed(3, "DELETE");
        try {
            CleanupResult result = svc.retention().sweep(NOW);
            LOG.info("boundary sweep removed=" + result.removedSegments()
                    + " newFirstSequence=" + result.newFirstSequence());
            assertEquals(1, result.removedSegments().size(), "only day0 segment expires");
            assertEquals(2L, result.newFirstSequence());
        } finally {
            svc.deleteDir();
        }
    }

    @Test
    void appendingAfterDeleteStillVerifiesAndDedupesAcrossBoundary() {
        AuditTestSupport.Services svc = seed(2, "DELETE");
        try {
            svc.retention().sweep(NOW);
            AuditIngestionService ingestion = new AuditIngestionService(svc.store());

            // 清理后继续追加：序号接在现存最后一条之后，链以现存 tailHash 为前向
            var appended = ingestion.ingest(new AuditEvent("ret-evt-new", "src",
                    "user-9", "act", "SUCCESS", NOW + DAY, Map.of("phase", "after-delete")));
            LOG.info("post-delete append outcome=" + appended.outcome()
                    + " sequence=" + appended.sequence());
            assertEquals(10L, appended.sequence());

            // 被删段里的历史 eventId 不再在去重索引中，允许重新上报（数据已按保留期删除）
            var ancientId = ingestion.ingest(new AuditEvent("ret-evt-0", "src",
                    "user-0", "act", "SUCCESS", NOW - 4 * DAY, Map.of("phase", "re-report")));
            LOG.info("re-report of deleted eventId -> outcome=" + ancientId.outcome()
                    + " sequence=" + ancientId.sequence());
            assertEquals(com.github.highcumontoa.auditlogintegrityjava.model.AuditOutcome.ACCEPTED,
                    ancientId.outcome());

            VerificationReport report = svc.verifier().verify();
            LOG.info("verify after post-delete appends intact=" + report.intact()
                    + " verifiedRecords=" + report.verifiedRecords()
                    + " deletedSegments=" + report.deletedSegments()
                    + " issues=" + report.issues());
            assertTrue(report.intact(), report.issues().toString());
            assertEquals(8, report.verifiedRecords());
        } finally {
            svc.deleteDir();
        }
    }
}
