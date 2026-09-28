package com.github.highcumontoa.auditlogintegrityjava.tests;

import com.github.highcumontoa.auditlogintegrityjava.core.AuditIngestionService;
import com.github.highcumontoa.auditlogintegrityjava.model.AuditEvent;
import com.github.highcumontoa.auditlogintegrityjava.model.AuditRecord;
import com.github.highcumontoa.auditlogintegrityjava.model.QueryFilter;
import com.github.highcumontoa.auditlogintegrityjava.model.VerificationReport;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 合规查询维度测试：时间区间、主体、结果过滤，以及归档段对查询仍然可见。
 */
class ComplianceQueryTest {

    private static final Logger LOG = Logger.getLogger(ComplianceQueryTest.class.getName());
    private static final long T = 1_700_000_000_000L;

    @Test
    void filtersByTimeSubjectResult() {
        AuditTestSupport.Services svc = AuditTestSupport.newServices(100, 90, "ARCHIVE");
        try {
            AuditIngestionService ingestion = new AuditIngestionService(svc.store());
            ingestion.ingest(new AuditEvent("q1", "s", "alice", "login", "SUCCESS", T, Map.of()));
            ingestion.ingest(new AuditEvent("q2", "s", "alice", "login", "FAILURE", T + 1000, Map.of()));
            ingestion.ingest(new AuditEvent("q3", "s", "bob", "login", "SUCCESS", T + 2000, Map.of()));
            ingestion.ingest(new AuditEvent("q4", "s", "alice", "logout", "DENIED", T + 3000, Map.of()));

            assertCount(svc, new QueryFilter(T + 1000L, T + 2000L, null, null), 2, "time window");
            assertCount(svc, new QueryFilter(null, null, "alice", null), 3, "subject=alice");
            assertCount(svc, new QueryFilter(null, null, null, "SUCCESS"), 2, "result=SUCCESS");
            assertCount(svc, new QueryFilter(T, T + 3000L, "alice", "DENIED"), 1,
                    "combined filters");
            // 未识别的 result 过滤值 -> 无结果（不报错）
            assertCount(svc, new QueryFilter(null, null, null, "NOPE"), 0, "unknown result");
        } finally {
            svc.deleteDir();
        }
    }

    @Test
    void archivedSegmentsRemainVisibleToQuery() {
        AuditTestSupport.Services svc = AuditTestSupport.newServices(2, 1, "ARCHIVE");
        try {
            AuditIngestionService ingestion = new AuditIngestionService(svc.store());
            long now = System.currentTimeMillis();
            for (int i = 0; i < 6; i++) {
                ingestion.ingest(new AuditEvent("aq" + i, "s", "user" + i, "a",
                        "SUCCESS", now - 10L * 86_400_000L + i, Map.of()));
            }
            // 再加 2 条近期记录，保证只归档最老段
            for (int i = 0; i < 2; i++) {
                ingestion.ingest(new AuditEvent("recent" + i, "s", "recent", "a",
                        "SUCCESS", now, Map.of()));
            }
            var cleanup = svc.retention().sweep(now);
            LOG.info("archived segments=" + cleanup.archivedSegments());

            List<AuditRecord> all = svc.queryService()
                    .query(new QueryFilter(null, null, null, null));
            LOG.info("query across archived+active returned " + all.size() + " records");
            assertEquals(8, all.size(), "archived records must remain queryable");
            assertTrue(all.stream().anyMatch(r -> r.subject().equals("user3")));

            VerificationReport report = svc.verifier().verify();
            assertTrue(report.intact(), report.issues().toString());
        } finally {
            svc.deleteDir();
        }
    }

    private void assertCount(AuditTestSupport.Services svc, QueryFilter f,
                             int expected, String label) {
        List<AuditRecord> hits = svc.queryService().query(f);
        LOG.info("query[" + label + "] filter=" + f + " -> matched=" + hits.size());
        assertEquals(expected, hits.size(), label + " -> " + hits);
    }
}
