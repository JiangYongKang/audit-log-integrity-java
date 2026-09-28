package com.github.highcumontoa.auditlogintegrityjava.model;

import java.util.List;

/**
 * 一次保留期清理/归档的结果。
 *
 * @param removedSegments   被删除的段
 * @param archivedSegments  被归档的段
 * @param removedRecords    被删除/归档的记录总数
 * @param newFirstSequence  清理后现存记录的最小序号（全部清空时为 null）
 */
public record CleanupResult(
        List<String> removedSegments,
        List<String> archivedSegments,
        long removedRecords,
        Long newFirstSequence
) {
}
