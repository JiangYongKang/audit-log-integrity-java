package com.github.highcumontoa.auditlogintegrityjava.model;

/**
 * 段的生命周期状态。
 * ACTIVE   ：正在追加，目录下存在 segments/&lt;name&gt;.log
 * ARCHIVED ：已归档，数据移至 archive/&lt;name&gt;.log，仍可查询与校验
 * DELETED  ：超过保留期被删除，数据文件不在，仅检查点保留边界信息，
 *            在校验中表现为可解释的边界，而不是篡改。
 */
public enum SegmentState {
    ACTIVE,
    ARCHIVED,
    DELETED
}
