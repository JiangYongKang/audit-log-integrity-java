package com.github.highcumontoa.auditlogintegrityjava.model;

/**
 * 单个事件采集入库的处理结果，可区分四种结论与拒绝原因。
 */
public record IngestionResult(
        AuditOutcome outcome,
        RejectReason rejectReason,
        Long sequence,
        String segment,
        String eventId,
        String message
) {
    public static IngestionResult accepted(AuditRecord r) {
        return new IngestionResult(r.normalized() ? AuditOutcome.ACCEPTED_NORMALIZED : AuditOutcome.ACCEPTED,
                null, r.sequence(), r.segment(), r.eventId(),
                r.normalized() ? "normalized and appended" : "appended");
    }

    public static IngestionResult rejected(AuditEvent e, RejectReason reason, String message) {
        return new IngestionResult(AuditOutcome.REJECTED, reason, null, null,
                e == null ? null : e.eventId(), message);
    }

    public static IngestionResult duplicate(String eventId, long existingSequence) {
        return new IngestionResult(AuditOutcome.DUPLICATE, null, existingSequence, null,
                eventId, "duplicate eventId, already at sequence " + existingSequence);
    }
}
