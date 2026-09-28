package com.github.highcumontoa.auditlogintegrityjava.model;

import java.util.List;

/**
 * 全量（或指定范围）完整性校验结论。
 *
 * @param intact              true 当且仅当 issues 为空
 * @param verifiedRecords     实际重算校验过的记录数（不含已删除段）
 * @param firstSequence       现存记录的最小序号
 * @param lastSequence        现存记录的最大序号
 * @param deletedSegments     因保留期被删除的段数量（按检查点视为可解释的缺口）
 * @param issues              发现的问题，按段/序号顺序排列
 */
public record VerificationReport(
        boolean intact,
        long verifiedRecords,
        Long firstSequence,
        Long lastSequence,
        long deletedSegments,
        List<VerificationIssue> issues
) {
}
