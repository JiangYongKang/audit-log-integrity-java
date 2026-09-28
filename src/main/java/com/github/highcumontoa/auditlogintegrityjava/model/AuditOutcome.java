package com.github.highcumontoa.auditlogintegrityjava.model;

/**
 * 一条原始事件在归一化入库阶段的处理结论。
 */
public enum AuditOutcome {
    /** 原样通过校验并入库 */
    ACCEPTED,
    /** 存在可补全的缺失字段，归一化后入库（记录上会保留 normalized 标记） */
    ACCEPTED_NORMALIZED,
    /** 校验未通过，被拒绝入库，拒绝原因见 RejectReason */
    REJECTED,
    /** 与已入库记录 eventId 重复，未重复入库 */
    DUPLICATE
}
