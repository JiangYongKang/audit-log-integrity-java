package com.github.highcumontoa.auditlogintegrityjava.model;

/** 合规查询条件，所有维度均可空（不过滤）。 */
public class QueryCriteria {

    private Long fromTime;
    private Long toTime;
    private String subject;
    private String source;
    private String action;
    private EventOutcome outcome;
    private Boolean includeArchived;
    private Integer limit;

    public Long getFromTime() { return fromTime; }
    public void setFromTime(Long fromTime) { this.fromTime = fromTime; }

    public Long getToTime() { return toTime; }
    public void setToTime(Long toTime) { this.toTime = toTime; }

    public String getSubject() { return subject; }
    public void setSubject(String subject) { this.subject = subject; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }

    public EventOutcome getOutcome() { return outcome; }
    public void setOutcome(EventOutcome outcome) { this.outcome = outcome; }

    public Boolean getIncludeArchived() { return includeArchived; }
    public void setIncludeArchived(Boolean includeArchived) { this.includeArchived = includeArchived; }

    public Integer getLimit() { return limit; }
    public void setLimit(Integer limit) { this.limit = limit; }
}
