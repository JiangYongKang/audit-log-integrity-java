package com.github.highcumontoa.auditlogintegrityjava.model;

/**
 * 检查点中登记的一个段的边界信息。
 */
public record SegmentMeta(
        String name,
        SegmentState state,
        long firstSequence,
        long lastSequence,
        long count,
        long minEventTime,
        long maxEventTime,
        /** 段内首条记录的 prevHash（与上一段末尾衔接） */
        String entryHash,
        /** 段内末条记录的 recordHash（交给下一段衔接） */
        String tailHash,
        /** 段内容文件的 SHA-256，用于发现段文件被整体替换 */
        String fileChecksum
) {
}
