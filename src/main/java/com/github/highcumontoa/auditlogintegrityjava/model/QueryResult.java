package com.github.highcumontoa.auditlogintegrityjava.model;

import java.util.List;

/** 查询结果，带"数据来源"说明，使清理后结论可解释。 */
public record QueryResult(
        int total,
        boolean truncated,
        List<String> scannedSegments,
        List<String> unavailableSegments,
        List<AuditRecord> records
) {
}
