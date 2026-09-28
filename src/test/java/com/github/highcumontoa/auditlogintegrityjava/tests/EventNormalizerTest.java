package com.github.highcumontoa.auditlogintegrityjava.tests;

import com.github.highcumontoa.auditlogintegrityjava.core.EventNormalizer;
import com.github.highcumontoa.auditlogintegrityjava.model.AuditEvent;
import com.github.highcumontoa.auditlogintegrityjava.model.RejectReason;
import com.github.highcumontoa.auditlogintegrityjava.model.ValidatedEvent;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 归一化单测：覆盖必填缺失、非法取值、时间补全/非法时间、明细补全等分支。
 * 每个断言前打印输入与判定依据，失败原因可从日志直接区分。
 */
class EventNormalizerTest {

    private static final Logger LOG = Logger.getLogger(EventNormalizerTest.class.getName());
    private static final long NOW = 1_700_000_000_000L;

    @Test
    void acceptsWellFormedEvent() {
        AuditEvent e = new AuditEvent("e1", "system-a", "alice", "login",
                "success", NOW - 1000, Map.of("ip", "10.0.0.1"));
        ValidatedEvent v = EventNormalizer.normalize(e, NOW);
        LOG.info("input=" + e + " -> valid=" + v.valid() + " normalized=" + v.normalized()
                + " result=" + v.result() + " notes=" + v.normalizationNotes());
        assertTrue(v.valid());
        assertFalse(v.normalized());
        assertEquals("SUCCESS", v.result());
    }

    @Test
    void normalizesLowercaseResultAndFillsMissingFields() {
        AuditEvent e = new AuditEvent("e2", "system-b", "bob", "delete", "Denied", null, null);
        ValidatedEvent v = EventNormalizer.normalize(e, NOW);
        LOG.info("input=" + e + " -> valid=" + v.valid() + " normalized=" + v.normalized()
                + " eventTime=" + v.eventTime() + " details=" + v.details()
                + " notes=" + v.normalizationNotes());
        assertTrue(v.valid());
        assertTrue(v.normalized());
        assertEquals("DENIED", v.result());
        assertEquals(NOW, v.eventTime());
        assertNotNull(v.details());
    }

    @Test
    void rejectsWithDistinctReasons() {
        assertRejected(null, RejectReason.NULL_EVENT);
        assertRejected(new AuditEvent(" ", "s", "u", "a", "SUCCESS", NOW, null),
                RejectReason.MISSING_EVENT_ID);
        assertRejected(new AuditEvent("e", " ", "u", "a", "SUCCESS", NOW, null),
                RejectReason.MISSING_SOURCE);
        assertRejected(new AuditEvent("e", "s", " ", "a", "SUCCESS", NOW, null),
                RejectReason.MISSING_SUBJECT);
        assertRejected(new AuditEvent("e", "s", "u", " ", "SUCCESS", NOW, null),
                RejectReason.MISSING_ACTION);
        assertRejected(new AuditEvent("e", "s", "u", "a", "  ", NOW, null),
                RejectReason.MISSING_RESULT);
        assertRejected(new AuditEvent("e", "s", "u", "a", "WEIRD", NOW, null),
                RejectReason.UNSUPPORTED_RESULT);
        assertRejected(new AuditEvent("e", "s", "u", "a", "SUCCESS", -5L, null),
                RejectReason.EVENT_TIME_INVALID);
        assertRejected(new AuditEvent("e", "s", "u", "a", "SUCCESS",
                NOW + 60 * 60 * 1000L, null), RejectReason.EVENT_TIME_IN_FUTURE);

        Map<String, String> badDetails = new HashMap<>();
        badDetails.put("k", null);
        assertRejected(new AuditEvent("e", "s", "u", "a", "SUCCESS", NOW, badDetails),
                RejectReason.DETAILS_INVALID);
    }

    private void assertRejected(AuditEvent event, RejectReason expected) {
        ValidatedEvent v = EventNormalizer.normalize(event, NOW);
        LOG.info("input=" + event + " -> valid=" + v.valid()
                + " reason=" + v.rejectReason() + " notes=" + v.normalizationNotes());
        assertFalse(v.valid(), "should be rejected: " + event);
        assertEquals(expected, v.rejectReason(),
                "reject reason mismatch for input " + event);
    }
}
