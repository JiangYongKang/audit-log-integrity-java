package com.github.highcumontoa.auditlogintegrityjava.model;

/** 完整性缺陷的类型，可区分不同失败原因。 */
public enum DefectType {
    HASH_MISMATCH,
    PREV_HASH_BROKEN,
    SEQUENCE_GAP,
    SEQUENCE_DUPLICATE,
    MISSING_RECORD,
    ARCHIVE_SEGMENT_MISSING,
    ARCHIVE_SEGMENT_HASH_MISMATCH,
    MALFORMED_LINE
}
