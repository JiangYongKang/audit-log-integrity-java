package com.github.highcumontoa.auditlogintegrityjava.storage;

import com.github.highcumontoa.auditlogintegrityjava.model.AuditRecord;

/**
 * 存储层追加结果：要么写入新记录，要么命中 eventId 去重。
 */
public record StoreAppendResult(boolean duplicate, long existingSequence, AuditRecord record) {
    static StoreAppendResult appended(AuditRecord r) {
        return new StoreAppendResult(false, -1, r);
    }
    static StoreAppendResult duplicate(long sequence) {
        return new StoreAppendResult(true, sequence, null);
    }
}
