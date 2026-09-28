package com.github.highcumontoa.auditlogintegrityjava.model;

/** 一次事件上报的处理结果。 */
public record IngestResult(
        IngestStatus status,
        RejectionReason rejectionReason,
        Long sequence,
        String entryHash
) {

    public static IngestResult accepted(long sequence, String entryHash) {
        return new IngestResult(IngestStatus.ACCEPTED, null, sequence, entryHash);
    }

    public static IngestResult rejected(RejectionReason reason) {
        return new IngestResult(IngestStatus.REJECTED, reason, null, null);
    }

    public static IngestResult duplicate(long sequence, String entryHash) {
        return new IngestResult(IngestStatus.DUPLICATE, null, sequence, entryHash);
    }
}
