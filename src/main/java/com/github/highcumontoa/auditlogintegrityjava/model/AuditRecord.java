package com.github.highcumontoa.auditlogintegrityjava.model;

import java.util.Map;

/**
 * 归一化后入库的统一审计记录。是哈希链的最小计算单元。
 *
 * @param sequence   全局连续序号，从 0 开始，并发追加下不重不漏
 * @param segment    所属段名
 * @param eventId    来源事件唯一标识
 * @param source     事件来源
 * @param subject    主体
 * @param action     动作
 * @param result     规范结果
 * @param eventTime  事件发生时间（epoch 毫秒）
 * @param ingestTime 入库时间（epoch 毫秒）
 * @param normalized 是否经过归一化补全
 * @param details    附加明细，永不为 null
 * @param prevHash   前一条记录的 recordHash；首条为固定的 GENESIS
 * @param recordHash 本条记录内容 + prevHash 的 SHA-256（hex）
 */
public record AuditRecord(
        long sequence,
        String segment,
        String eventId,
        String source,
        String subject,
        String action,
        String result,
        long eventTime,
        long ingestTime,
        boolean normalized,
        Map<String, String> details,
        String prevHash,
        String recordHash
) {
}
