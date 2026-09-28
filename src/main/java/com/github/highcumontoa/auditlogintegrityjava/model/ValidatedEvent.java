package com.github.highcumontoa.auditlogintegrityjava.model;

import java.util.List;
import java.util.Map;

/**
 * 归一化结果。通过时携带可直接入库的字段；不通过时携带拒绝原因。
 */
public record ValidatedEvent(
        boolean valid,
        boolean normalized,
        RejectReason rejectReason,
        String eventId,
        String source,
        String subject,
        String action,
        String result,
        long eventTime,
        long ingestTime,
        Map<String, String> details,
        List<String> normalizationNotes
) {
}
