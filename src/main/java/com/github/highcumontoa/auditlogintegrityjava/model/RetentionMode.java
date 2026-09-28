package com.github.highcumontoa.auditlogintegrityjava.model;

/** 到期数据的处理方式。 */
public enum RetentionMode {
    /** 剪切到归档段文件，并在主流水追加锚点记录。 */
    ARCHIVE,
    /** 归档后删除段文件，仅保留锚点（含段摘要）。 */
    DELETE
}
