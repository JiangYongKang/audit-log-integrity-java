package com.github.highcumontoa.auditlogintegrityjava.tests;

import com.github.highcumontoa.auditlogintegrityjava.model.AuditEventInput;
import com.github.highcumontoa.auditlogintegrityjava.model.EventOutcome;
import com.github.highcumontoa.auditlogintegrityjava.model.RejectionReason;
import com.github.highcumontoa.auditlogintegrityjava.service.EventNormalizer;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** 归一化：别名映射、缺失字段与非法取值必须给出可区分的拒绝原因。 */
class EventNormalizerTest {

    private static final Logger log = LoggerFactory.getLogger(EventNormalizerTest.class);

    private final EventNormalizer normalizer = new EventNormalizer();

    @Test
    void acceptsCanonicalInputAndDefaultsOutcome() {
        AuditEventInput in = new AuditEventInput();
        in.setEventId("e-1");
        in.setSource("auth-service");
        in.setSubject("alice");
        in.setAction("login");
        in.setEventTime(1_700_000_000_000L);

        EventNormalizer.Normalized n = normalizer.normalize(in);
        log.info("input={} -> accepted={} outcome={}", in.getEventId(), n.accepted(),
                n.record() == null ? null : n.record().getOutcome());

        assertEquals(true, n.accepted());
        assertEquals(EventOutcome.SUCCESS, n.record().getOutcome());
    }

    @Test
    void mapsAliasesFromHeterogeneousSourceMap() {
        Map<String, Object> raw = Map.of(
                "id", "evt-42",
                "source_system", "crm",
                "principal", "bob",
                "operation", "export",
                "occurredAt", 1_700_000_000_001L,
                "result", "FAILED",
                "message", "batch export");

        AuditEventInput mapped = normalizer.fromMap(raw);
        EventNormalizer.Normalized n = normalizer.normalize(mapped);
        log.info("raw aliases={} -> accepted={} eventId={} source={} subject={} action={} outcome={}",
                raw.keySet(), n.accepted(), mapped.getEventId(), mapped.getSource(),
                mapped.getSubject(), mapped.getAction(),
                n.record() == null ? null : n.record().getOutcome());

        assertEquals(true, n.accepted());
        assertEquals("evt-42", n.record().getEventId());
        assertEquals("crm", n.record().getSource());
        assertEquals("bob", n.record().getSubject());
        assertEquals("export", n.record().getAction());
        assertEquals(EventOutcome.FAILURE, n.record().getOutcome());
        assertEquals("batch export", n.record().getDetail());
    }

    @Test
    void rejectsEachMissingFieldWithDistinctReason() {
        assertRejection(baseWith(b -> b.setEventId(" ")), RejectionReason.MISSING_EVENT_ID);
        assertRejection(baseWith(b -> b.setSource(null)), RejectionReason.MISSING_SOURCE);
        assertRejection(baseWith(b -> b.setSubject("")), RejectionReason.MISSING_SUBJECT);
        assertRejection(baseWith(b -> b.setAction(null)), RejectionReason.MISSING_ACTION);
        assertRejection(baseWith(b -> b.setEventTime(null)), RejectionReason.MISSING_EVENT_TIME);
    }

    @Test
    void rejectsIllegalValuesWithDistinctReasons() {
        assertRejection(baseWith(b -> b.setEventTime(0L)), RejectionReason.INVALID_EVENT_TIME);
        assertRejection(baseWith(b -> b.setEventTime(-5L)), RejectionReason.INVALID_EVENT_TIME);
        assertRejection(baseWith(b -> b.setOutcome("MAYBE")), RejectionReason.UNSUPPORTED_OUTCOME);
    }

    private void assertRejection(AuditEventInput in, RejectionReason expected) {
        EventNormalizer.Normalized n = normalizer.normalize(in);
        log.info("input eventId={} source={} eventTime={} outcome={} -> accepted={} reason={}",
                in.getEventId(), in.getSource(), in.getEventTime(), in.getOutcome(),
                n.accepted(), n.rejectionReason());
        assertEquals(false, n.accepted());
        assertNull(n.record());
        assertEquals(expected, n.rejectionReason());
    }

    private AuditEventInput baseWith(java.util.function.Consumer<AuditEventInput> tweak) {
        AuditEventInput in = new AuditEventInput();
        in.setEventId("e-x");
        in.setSource("svc");
        in.setSubject("carol");
        in.setAction("read");
        in.setEventTime(1_700_000_000_000L);
        in.setOutcome("success");
        tweak.accept(in);
        return in;
    }
}
