package com.github.highcumontoa.auditlogintegrityjava.model;

import java.util.Map;

/**
 * 来自不同来源的原始审计事件（入库前形态）。字段允许缺失，
 * 由 {@link com.github.highcumontoa.auditlogintegrityjava.core.EventNormalizer}
 * 做统一归一化与校验。
 *
 * @param eventId   来源侧事件唯一标识（去重依据）
 * @param source    事件来源（如 system-a / gateway）
 * @param subject   主体（账号/服务名）
 * @param action    动作
 * @param result    结果，规范取值见 AuditResult（SUCCESS / FAILURE / DENIED / ERROR）
 * @param eventTime 事件发生时间，epoch 毫秒；为 null 时可由归一化补全
 * @param details   附加明细，可为 null
 */
public record AuditEvent(
        String eventId,
        String source,
        String subject,
        String action,
        String result,
        Long eventTime,
        Map<String, String> details
) {
}
