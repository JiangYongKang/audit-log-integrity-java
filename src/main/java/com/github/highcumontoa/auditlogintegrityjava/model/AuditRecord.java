package com.github.highcumontoa.auditlogintegrityjava.model;

/** 归一化后的审计流水记录（链上数据条目）。 */
public class AuditRecord implements ChainEntry {

    private long sequence;
    private String eventId;
    private String source;
    private String subject;
    private String action;
    private long eventTime;
    private long storedAt;
    private EventOutcome outcome;
    private String detail;
    private String prevHash;
    private String entryHash;

    @Override public long sequence() { return sequence; }
    @Override public String prevHash() { return prevHash; }
    @Override public String entryHash() { return entryHash; }
    @Override public boolean isAnchor() { return false; }

    public long getSequence() { return sequence; }
    public void setSequence(long sequence) { this.sequence = sequence; }

    public String getEventId() { return eventId; }
    public void setEventId(String eventId) { this.eventId = eventId; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public String getSubject() { return subject; }
    public void setSubject(String subject) { this.subject = subject; }

    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }

    public long getEventTime() { return eventTime; }
    public void setEventTime(long eventTime) { this.eventTime = eventTime; }

    public long getStoredAt() { return storedAt; }
    public void setStoredAt(long storedAt) { this.storedAt = storedAt; }

    public EventOutcome getOutcome() { return outcome; }
    public void setOutcome(EventOutcome outcome) { this.outcome = outcome; }

    public String getDetail() { return detail; }
    public void setDetail(String detail) { this.detail = detail; }

    public String getPrevHash() { return prevHash; }
    public void setPrevHash(String prevHash) { this.prevHash = prevHash; }

    public String getEntryHash() { return entryHash; }
    public void setEntryHash(String entryHash) { this.entryHash = entryHash; }
}
