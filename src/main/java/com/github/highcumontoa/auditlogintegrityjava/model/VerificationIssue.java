package com.github.highcumontoa.auditlogintegrityjava.model;

/**
 * 一条完整性校验问题，定位到段名与序号。
 */
public record VerificationIssue(
        IssueType type,
        String segment,
        Long sequence,
        String detail
) {
    public String describe() {
        return "[" + type + "] segment=" + segment
                + (sequence == null ? "" : " sequence=" + sequence)
                + " : " + detail;
    }
}
