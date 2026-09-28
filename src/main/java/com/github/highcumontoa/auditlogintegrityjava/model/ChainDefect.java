package com.github.highcumontoa.auditlogintegrityjava.model;

/** 定位到具体位置的一条完整性缺陷。 */
public record ChainDefect(
        DefectType type,
        long position,
        String detail
) {
}
