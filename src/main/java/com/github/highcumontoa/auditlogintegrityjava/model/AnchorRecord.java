package com.github.highcumontoa.auditlogintegrityjava.model;

/**
 * 归档锚点：剪切一段流水到归档段后追加的边界条目。
 * 承载被剪切段的摘要，使清理之后的流水仍然可解释、可校验。
 */
public class AnchorRecord implements ChainEntry {

    private long sequence;
    private long storedAt;
    private String segmentFile;
    private long fromSequence;
    private long toSequence;
    private int recordCount;
    private long cutoffTime;
    private String mode;
    private String segmentHash;
    private String prevHash;
    private String entryHash;

    @Override public long sequence() { return sequence; }
    @Override public String prevHash() { return prevHash; }
    @Override public String entryHash() { return entryHash; }
    @Override public boolean isAnchor() { return true; }

    public long getSequence() { return sequence; }
    public void setSequence(long sequence) { this.sequence = sequence; }

    public long getStoredAt() { return storedAt; }
    public void setStoredAt(long storedAt) { this.storedAt = storedAt; }

    public String getSegmentFile() { return segmentFile; }
    public void setSegmentFile(String segmentFile) { this.segmentFile = segmentFile; }

    public long getFromSequence() { return fromSequence; }
    public void setFromSequence(long fromSequence) { this.fromSequence = fromSequence; }

    public long getToSequence() { return toSequence; }
    public void setToSequence(long toSequence) { this.toSequence = toSequence; }

    public int getRecordCount() { return recordCount; }
    public void setRecordCount(int recordCount) { this.recordCount = recordCount; }

    public long getCutoffTime() { return cutoffTime; }
    public void setCutoffTime(long cutoffTime) { this.cutoffTime = cutoffTime; }

    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }

    public String getSegmentHash() { return segmentHash; }
    public void setSegmentHash(String segmentHash) { this.segmentHash = segmentHash; }

    public String getPrevHash() { return prevHash; }
    public void setPrevHash(String prevHash) { this.prevHash = prevHash; }

    public String getEntryHash() { return entryHash; }
    public void setEntryHash(String entryHash) { this.entryHash = entryHash; }
}
