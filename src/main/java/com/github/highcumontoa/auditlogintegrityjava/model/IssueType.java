package com.github.highcumontoa.auditlogintegrityjava.model;

/**
 * 完整性校验发现的问题类型，可定位到具体段/序号。
 */
public enum IssueType {
    /** 段文件不存在（应处于 ACTIVE/ARCHIVED 状态却缺失） */
    SEGMENT_FILE_MISSING,
    /** 段文件无法读取/解析 */
    SEGMENT_FILE_UNREADABLE,
    /** 某条记录内容被改写：重算哈希与记录内 recordHash 不一致 */
    HASH_MISMATCH,
    /** 某条记录的 prevHash 与前一条不一致（删/插导致断链） */
    CHAIN_BROKEN,
    /** 序号不连续：出现缺口（记录被删除） */
    SEQUENCE_GAP,
    /** 序号重复（中途插入挤入已有序号） */
    SEQUENCE_DUPLICATE,
    /** 段内首记录的 prevHash 与上一段末尾哈希不匹配（跨段插入/改写） */
    CROSS_SEGMENT_CHAIN_BROKEN,
    /** 段文件实际内容与检查点登记的边界信息不一致 */
    CHECKPOINT_MISMATCH
}
