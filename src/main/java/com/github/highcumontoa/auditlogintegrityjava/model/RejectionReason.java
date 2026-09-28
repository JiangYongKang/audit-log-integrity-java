package com.github.highcumontoa.auditlogintegrityjava.model;

/** 拒绝入库的可区分原因。 */
public enum RejectionReason {
    MISSING_EVENT_ID,
    MISSING_SOURCE,
    MISSING_SUBJECT,
    MISSING_ACTION,
    MISSING_EVENT_TIME,
    INVALID_EVENT_TIME,
    UNSUPPORTED_OUTCOME
}
