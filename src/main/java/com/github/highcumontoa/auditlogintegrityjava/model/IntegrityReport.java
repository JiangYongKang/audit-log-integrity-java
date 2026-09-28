package com.github.highcumontoa.auditlogintegrityjava.model;

import java.util.List;

/** 完整链（归档段 + 锚点 + 活跃段）的校验结论。 */
public record IntegrityReport(
        boolean intact,
        long headSequence,
        String headHash,
        List<VerificationSegment> segments,
        List<ChainDefect> defects
) {
}
