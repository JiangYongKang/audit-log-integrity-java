package com.github.highcumontoa.auditlogintegrityjava.model;

import java.util.Set;

/**
 * 审计结果的规范取值。来源侧非规范值由归一化层尝试映射，
 * 无法识别的取值按 UNSUPPORTED_RESULT 拒绝。
 */
public enum AuditResult {
    SUCCESS,
    FAILURE,
    DENIED,
    ERROR;

    public static final Set<String> SUPPORTED = Set.of(
            "SUCCESS", "FAILURE", "DENIED", "ERROR");

    public static boolean isSupported(String value) {
        return value != null && SUPPORTED.contains(value);
    }
}
