package com.github.highcumontoa.auditlogintegrityjava.core;

import com.github.highcumontoa.auditlogintegrityjava.model.AuditEvent;
import com.github.highcumontoa.auditlogintegrityjava.model.AuditResult;
import com.github.highcumontoa.auditlogintegrityjava.model.RejectReason;
import com.github.highcumontoa.auditlogintegrityjava.model.ValidatedEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 把不同来源的 {@link AuditEvent} 归一化成统一字段。
 * 规则（每条规则对应一个可区分的 {@link RejectReason}，不静默丢弃）：
 * <ol>
 *   <li>eventId/source/subject/action/result 必填且非空白，缺失分别对应 MISSING_* 原因；</li>
 *   <li>result 必须是 {@link AuditResult} 的规范取值（大小写不敏感），
 *       无法识别为 UNSUPPORTED_RESULT；</li>
 *   <li>eventTime 为 null 时以接收时间补全（标记 normalized）；
 *       为负或无法解析为 EVENT_TIME_INVALID，领先当前时间超过
 *       {@link #MAX_FUTURE_SKEW_MS} 为 EVENT_TIME_IN_FUTURE；</li>
 *   <li>details 为 null 时补为空 map（标记 normalized）；出现 null 键/值为 DETAILS_INVALID。</li>
 * </ol>
 */
public final class EventNormalizer {

    /** 事件时间允许领先当前时间的上限（毫秒），超出视为非法时间。 */
    public static final long MAX_FUTURE_SKEW_MS = 5 * 60 * 1000L;

    private EventNormalizer() {
    }

    public static ValidatedEvent normalize(AuditEvent event, long nowMillis) {
        if (event == null) {
            return invalid(RejectReason.NULL_EVENT, null, null, null, null, null, 0, 0, null,
                    List.of("event payload is null"));
        }

        String eventId = trimOrNull(event.eventId());
        String source = trimOrNull(event.source());
        String subject = trimOrNull(event.subject());
        String action = trimOrNull(event.action());
        String rawResult = trimOrNull(event.result());

        List<String> notes = new ArrayList<>();

        if (eventId == null) {
            return reject(RejectReason.MISSING_EVENT_ID, event, notes, "eventId is blank");
        }
        if (source == null) {
            return reject(RejectReason.MISSING_SOURCE, event, notes, "source is blank");
        }
        if (subject == null) {
            return reject(RejectReason.MISSING_SUBJECT, event, notes, "subject is blank");
        }
        if (action == null) {
            return reject(RejectReason.MISSING_ACTION, event, notes, "action is blank");
        }
        if (rawResult == null) {
            return reject(RejectReason.MISSING_RESULT, event, notes, "result is blank");
        }

        String result = rawResult.toUpperCase(java.util.Locale.ROOT);
        if (!AuditResult.isSupported(result)) {
            return reject(RejectReason.UNSUPPORTED_RESULT, event, notes,
                    "result value not supported: " + rawResult);
        }

        boolean normalized = false;
        long eventTime;
        if (event.eventTime() == null) {
            eventTime = nowMillis;
            normalized = true;
            notes.add("eventTime missing, filled with ingest time " + nowMillis);
        } else {
            eventTime = event.eventTime();
            if (eventTime < 0L) {
                return reject(RejectReason.EVENT_TIME_INVALID, event, notes,
                        "eventTime is negative: " + eventTime);
            }
            if (eventTime > nowMillis + MAX_FUTURE_SKEW_MS) {
                return reject(RejectReason.EVENT_TIME_IN_FUTURE, event, notes,
                        "eventTime " + eventTime + " exceeds max future skew over now " + nowMillis);
            }
        }

        Map<String, String> details;
        if (event.details() == null) {
            details = Map.of();
            normalized = true;
            notes.add("details missing, filled with empty map");
        } else {
            try {
                var tmp = new java.util.LinkedHashMap<String, String>(event.details().size());
                event.details().forEach((k, v) -> {
                    if (k == null || v == null) {
                        throw new IllegalArgumentException("details contains null key or value");
                    }
                    tmp.put(k, v);
                });
                details = Map.copyOf(tmp);
            } catch (IllegalArgumentException ex) {
                return reject(RejectReason.DETAILS_INVALID, event, notes, ex.getMessage());
            }
        }

        return new ValidatedEvent(true, normalized, null,
                eventId, source, subject, action, result,
                eventTime, nowMillis, details, List.copyOf(notes));
    }

    private static ValidatedEvent reject(RejectReason reason, AuditEvent event,
                                         List<String> notes, String message) {
        notes.add("rejected: " + reason + " - " + message);
        return invalid(reason,
                event.eventId(), event.source(), event.subject(), event.action(), event.result(),
                event.eventTime() == null ? 0L : event.eventTime(),
                0L, event.details(), notes);
    }

    private static ValidatedEvent invalid(RejectReason reason, String eventId, String source,
                                          String subject, String action, String result,
                                          long eventTime, long ingestTime,
                                          Map<String, String> details, List<String> notes) {
        return new ValidatedEvent(false, false, reason,
                eventId, source, subject, action, result,
                eventTime, ingestTime, details == null ? Map.of() : details, notes);
    }

    private static String trimOrNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
