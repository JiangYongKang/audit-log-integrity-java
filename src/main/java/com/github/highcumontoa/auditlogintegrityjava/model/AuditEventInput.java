package com.github.highcumontoa.auditlogintegrityjava.model;

/**
 * 来自任意来源的原始事件。
 * 归一化器负责字段别名映射、缺字段/非法取值的判定。
 */
public class AuditEventInput {

    private String eventId;
    private String source;
    private String subject;
    private String action;
    private Long eventTime;
    private String outcome;
    private String detail;

    public String getEventId() { return eventId; }
    public void setEventId(String eventId) { this.eventId = eventId; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public String getSubject() { return subject; }
    public void setSubject(String subject) { this.subject = subject; }

    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }

    public Long getEventTime() { return eventTime; }
    public void setEventTime(Long eventTime) { this.eventTime = eventTime; }

    public String getOutcome() { return outcome; }
    public void setOutcome(String outcome) { this.outcome = outcome; }

    public String getDetail() { return detail; }
    public void setDetail(String detail) { this.detail = detail; }
}
