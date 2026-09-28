package com.github.highcumontoa.auditlogintegrityjava.model;

/**
 * 合规查询过滤条件；null 表示该维度不过滤。时间为 epoch 毫秒，区间两端都包含。
 */
public record QueryFilter(
        Long fromEventTime,
        Long toEventTime,
        String subject,
        String result
) {
}
