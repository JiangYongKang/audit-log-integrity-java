package com.github.highcumontoa.auditlogintegrityjava.model;

/** 一次完整性校验中单个链段的结论。 */
public record VerificationSegment(
        String segmentName,
        SegmentStatus status,
        long fromSequence,
        long toSequence,
        int entryCount,
        String detail
) {

    public enum SegmentStatus {
        /** 段存在且哈希、编号、链接全部一致。 */
        VERIFIED,
        /** 段存在但检测到缺陷，详见报告 defects。 */
        FAILED,
        /** DELETE 模式下段已物理删除；锚点摘要仍可解释该区间，结论是"不可复核"而非完整。 */
        DELETED
    }
}
