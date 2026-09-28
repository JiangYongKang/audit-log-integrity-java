package com.github.highcumontoa.auditlogintegrityjava.model;

import java.util.List;

/** 一次保留期清理/归档的执行结论。 */
public record RetentionReport(
        long cutoffTime,
        RetentionMode mode,
        int archivedRecords,
        int archivedSegments,
        List<String> segmentFiles,
        long newActiveFirstSequence,
        long anchorSequence,
        String anchorHash
) {
}
