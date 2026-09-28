package com.github.highcumontoa.auditlogintegrityjava.model;

/** 上报事件的处理结论。 */
public enum IngestStatus {
    /** 正常入库并追加到流水末尾。 */
    ACCEPTED,
    /** 校验未通过，被拒绝，原因见 RejectionReason。 */
    REJECTED,
    /** eventId 重复，幂等返回首次入库记录，不重复追加。 */
    DUPLICATE
}
