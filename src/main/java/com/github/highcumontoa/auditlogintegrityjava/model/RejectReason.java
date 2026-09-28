package com.github.highcumontoa.auditlogintegrityjava.model;

/**
 * 事件被拒绝入库的可区分原因。
 */
public enum RejectReason {
    NULL_EVENT,
    MISSING_EVENT_ID,
    MISSING_SOURCE,
    MISSING_SUBJECT,
    MISSING_ACTION,
    MISSING_RESULT,
    UNSUPPORTED_RESULT,
    EVENT_TIME_INVALID,
    EVENT_TIME_IN_FUTURE,
    DETAILS_INVALID
}
