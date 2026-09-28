package com.github.highcumontoa.auditlogintegrityjava.service;

import com.github.highcumontoa.auditlogintegrityjava.model.AuditEventInput;
import com.github.highcumontoa.auditlogintegrityjava.model.AuditRecord;
import com.github.highcumontoa.auditlogintegrityjava.model.EventOutcome;
import com.github.highcumontoa.auditlogintegrityjava.model.RejectionReason;

import java.util.Map;

/**
 * 把不同来源的原始事件归一为统一记录。
 *
 * <p>字段别名（不区分大小写、去掉下划线/连字符）：
 * eventId/id/event_id；source/sourceSystem/source_system/origin；
 * subject/principal/user/subject_id；action/operation/op/act；
 * eventTime/event_time/timestamp/occurredAt；outcome/result/status。</p>
 *
 * <p>校验顺序固定，拒绝原因互斥且可区分：缺字段类原因优先于取值非法原因，
 * 任何非法输入都得到显式 REJECTED，不静默丢弃。</p>
 */
public class EventNormalizer {

    /** 归一化结论：要么得到记录，要么带一个明确拒绝原因。 */
    public record Normalized(AuditRecord record, RejectionReason rejectionReason) {
        public boolean accepted() { return record != null; }

        static Normalized ok(AuditRecord r) { return new Normalized(r, null); }
        static Normalized reject(RejectionReason reason) { return new Normalized(null, reason); }
    }

    /** 从松散的来源 Map（别名键）构造输入对象。 */
    public AuditEventInput fromMap(Map<String, Object> raw) {
        AuditEventInput in = new AuditEventInput();
        in.setEventId(asString(pick(raw, "eventid", "id")));
        in.setSource(asString(pick(raw, "source", "sourcesystem", "origin")));
        in.setSubject(asString(pick(raw, "subject", "principal", "user", "subjectid")));
        in.setAction(asString(pick(raw, "action", "operation", "op", "act")));
        in.setEventTime(asLong(pick(raw, "eventtime", "timestamp", "occurredat")));
        in.setOutcome(asString(pick(raw, "outcome", "result", "status")));
        in.setDetail(asString(pick(raw, "detail", "message", "description")));
        return in;
    }

    /**
     * 归一化校验。必填：eventId/source/subject/action/eventTime；
     * outcome 可空（默认 SUCCESS），非空时必须能映射到枚举。
     */
    public Normalized normalize(AuditEventInput input) {
        if (isBlank(input.getEventId())) {
            return Normalized.reject(RejectionReason.MISSING_EVENT_ID);
        }
        if (isBlank(input.getSource())) {
            return Normalized.reject(RejectionReason.MISSING_SOURCE);
        }
        if (isBlank(input.getSubject())) {
            return Normalized.reject(RejectionReason.MISSING_SUBJECT);
        }
        if (isBlank(input.getAction())) {
            return Normalized.reject(RejectionReason.MISSING_ACTION);
        }
        if (input.getEventTime() == null) {
            return Normalized.reject(RejectionReason.MISSING_EVENT_TIME);
        }
        if (input.getEventTime() <= 0L) {
            return Normalized.reject(RejectionReason.INVALID_EVENT_TIME);
        }
        EventOutcome outcome = EventOutcome.SUCCESS;
        if (!isBlank(input.getOutcome())) {
            String v = input.getOutcome().trim().toUpperCase();
            if ("SUCCESS".equals(v) || "SUCCEEDED".equals(v) || "OK".equals(v)) {
                outcome = EventOutcome.SUCCESS;
            } else if ("FAILURE".equals(v) || "FAILED".equals(v) || "DENIED".equals(v)) {
                outcome = EventOutcome.FAILURE;
            } else {
                return Normalized.reject(RejectionReason.UNSUPPORTED_OUTCOME);
            }
        }

        AuditRecord r = new AuditRecord();
        r.setEventId(input.getEventId().trim());
        r.setSource(input.getSource().trim());
        r.setSubject(input.getSubject().trim());
        r.setAction(input.getAction().trim());
        r.setEventTime(input.getEventTime());
        r.setOutcome(outcome);
        r.setDetail(input.getDetail() == null ? null : input.getDetail().trim());
        return Normalized.ok(r);
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static Object pick(Map<String, Object> raw, String... keys) {
        for (String k : keys) {
            for (Map.Entry<String, Object> e : raw.entrySet()) {
                String nk = e.getKey().toLowerCase().replace("_", "").replace("-", "");
                if (nk.equals(k) && e.getValue() != null) {
                    return e.getValue();
                }
            }
        }
        return null;
    }

    private static String asString(Object o) {
        return o == null ? null : o.toString();
    }

    private static Long asLong(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(o.toString().trim());
        } catch (NumberFormatException e) {
            return -1L;
        }
    }
}
