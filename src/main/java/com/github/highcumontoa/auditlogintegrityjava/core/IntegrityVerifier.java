package com.github.highcumontoa.auditlogintegrityjava.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.highcumontoa.auditlogintegrityjava.model.AuditRecord;
import com.github.highcumontoa.auditlogintegrityjava.model.IssueType;
import com.github.highcumontoa.auditlogintegrityjava.model.SegmentMeta;
import com.github.highcumontoa.auditlogintegrityjava.model.SegmentState;
import com.github.highcumontoa.auditlogintegrityjava.model.VerificationIssue;
import com.github.highcumontoa.auditlogintegrityjava.model.VerificationReport;
import com.github.highcumontoa.auditlogintegrityjava.storage.LocalSegmentStore;
import org.springframework.stereotype.Component;

import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * 完整性校验器。
 * <p>
 * 对每个 ACTIVE/ARCHIVED 段逐记录执行四项核对：
 * <ol>
 *   <li>{@code recordHash}：用规范内容+prevHash 重算，检测<strong>记录被改写</strong>；</li>
 *   <li>{@code prevHash} 链接：必须等于前一条 recordHash，
 *       检测<strong>删除/插入</strong>导致的断链；</li>
 *   <li>序号必须与写入顺序连续，检测<strong>序号缺口/重复</strong>；</li>
 *   <li>段边界（entryHash/tailHash/序号范围/文件 checksum）与检查点一致，
 *       检测<strong>跨段插入</strong>与整文件替换。</li>
 * </ol>
 * DELETED 段（保留期清理产生）数据已不存在，按检查点登记的边界信息跳过内容核对，
 * 其后续段的 entryHash 仍与被删段 tailHash 衔接——这是一个“可解释的边界”，
 * 而不是篡改问题。
 */
@Component
public class IntegrityVerifier {

    private static final Logger LOG = Logger.getLogger(IntegrityVerifier.class.getName());

    private final LocalSegmentStore store;
    private final ObjectMapper mapper = new ObjectMapper();

    public IntegrityVerifier(LocalSegmentStore store) {
        this.store = store;
    }

    public VerificationReport verify() {
        List<SegmentMeta> metas = store.listSegmentsInOrder();
        List<VerificationIssue> issues = new ArrayList<>();

        String expectedPrev = HashChain.GENESIS_HASH;
        long expectedSeq = 0L;
        long verifiedRecords = 0L;
        long deletedSegments = 0L;
        Long firstSequence = null;
        Long lastSequence = null;

        for (int si = 0; si < metas.size(); si++) {
            SegmentMeta meta = metas.get(si);

            // 跨段边界：每段（含 DELETED）的 entryHash 必须接在上一段 tailHash 后
            if (!safeEquals(meta.entryHash(), expectedPrev)) {
                issues.add(issue(IssueType.CROSS_SEGMENT_CHAIN_BROKEN, meta.name(), null,
                        "entryHash does not match previous tail: expected=" + expectedPrev
                                + " but checkpoint entryHash=" + meta.entryHash()));
            }

            if (meta.state() == SegmentState.DELETED) {
                deletedSegments++;
                expectedPrev = meta.tailHash();
                expectedSeq = meta.lastSequence() + 1;
                continue;
            }

            firstSequence = firstSequence == null ? meta.firstSequence() : firstSequence;
            lastSequence = meta.lastSequence();

            List<AuditRecord> records = readSegmentRecords(meta, issues);
            long observedMin = Long.MAX_VALUE;
            long observedMax = Long.MIN_VALUE;
            AuditRecord prevInSegment = null;

            for (AuditRecord r : records) {
                verifiedRecords++;
                observedMin = Math.min(observedMin, r.eventTime());
                observedMax = Math.max(observedMax, r.eventTime());

                // 1) 序号连续性
                if (r.sequence() != expectedSeq) {
                    IssueType type = r.sequence() < expectedSeq
                            ? IssueType.SEQUENCE_DUPLICATE : IssueType.SEQUENCE_GAP;
                    issues.add(issue(type, meta.name(), r.sequence(),
                            "expected sequence " + expectedSeq + " but found " + r.sequence()));
                }

                // 2) 链：prevHash 必须等于运行中的前向哈希
                if (!safeEquals(r.prevHash(), expectedPrev)) {
                    issues.add(issue(IssueType.CHAIN_BROKEN, meta.name(), r.sequence(),
                            "prevHash mismatch: expected=" + expectedPrev
                                    + " actual=" + r.prevHash()));
                }

                // 3) 内容哈希：检测字节级改写
                String recomputed = HashChain.computeRecordHash(
                        HashChain.canonicalContent(stripHashes(r)), r.prevHash());
                if (!safeEquals(recomputed, r.recordHash())) {
                    issues.add(issue(IssueType.HASH_MISMATCH, meta.name(), r.sequence(),
                            "recordHash mismatch: stored=" + r.recordHash()
                                    + " recomputed=" + recomputed));
                }

                // 记录声称的所属段必须与所在文件一致（防止记录被跨文件搬运）
                if (!meta.name().equals(r.segment())) {
                    issues.add(issue(IssueType.CHAIN_BROKEN, meta.name(), r.sequence(),
                            "record belongs to segment " + r.segment()
                                    + " but was found in " + meta.name()));
                }

                expectedPrev = r.recordHash();
                expectedSeq = r.sequence() + 1;
                prevInSegment = r;
            }

            // 4) 段边界与检查点核对
            verifySegmentBoundaries(meta, records, prevInSegment,
                    observedMin, observedMax, issues);

            // 即使发现异常，也以检查点登记的边界推进，以便继续发现后续问题
            expectedPrev = meta.tailHash();
            expectedSeq = meta.lastSequence() + 1;
        }

        boolean intact = issues.isEmpty();
        LOG.info("verification finished intact=" + intact
                + " verifiedRecords=" + verifiedRecords
                + " deletedSegments=" + deletedSegments
                + " firstSequence=" + firstSequence
                + " lastSequence=" + lastSequence
                + " issues=" + issues.size());
        issues.forEach(i -> LOG.warning(i.describe()));

        return new VerificationReport(intact, verifiedRecords, firstSequence, lastSequence,
                deletedSegments, List.copyOf(issues));
    }

    private List<AuditRecord> readSegmentRecords(SegmentMeta meta,
                                                 List<VerificationIssue> issues) {
        // 文件 checksum 与检查点比对：整文件替换可被发现
        try {
            var file = store.segmentFile(meta.state(), meta.name());
            if (!Files.exists(file)) {
                issues.add(issue(IssueType.SEGMENT_FILE_MISSING, meta.name(), null,
                        "state=" + meta.state() + " but file does not exist: " + file));
                return List.of();
            }
            byte[] bytes = Files.readAllBytes(file);
            String checksum = HashChain.sha256Hex(bytes);
            if (meta.fileChecksum() != null && !meta.fileChecksum().equals(checksum)) {
                issues.add(issue(IssueType.CHECKPOINT_MISMATCH, meta.name(), null,
                        "segment file checksum differs from checkpoint: checkpoint="
                                + meta.fileChecksum() + " actual=" + checksum));
            }
        } catch (Exception e) {
            issues.add(issue(IssueType.SEGMENT_FILE_UNREADABLE, meta.name(), null,
                    "cannot read segment file: " + e.getMessage()));
            return List.of();
        }

        List<AuditRecord> records = new ArrayList<>();
        List<String> lines;
        try {
            lines = store.readSegmentRawLines(meta);
        } catch (LocalSegmentStore.MissingSegmentFileException e) {
            issues.add(issue(IssueType.SEGMENT_FILE_MISSING, meta.name(), null, e.getMessage()));
            return List.of();
        } catch (UncheckedIOException e) {
            issues.add(issue(IssueType.SEGMENT_FILE_UNREADABLE, meta.name(), null, e.getMessage()));
            return List.of();
        }

        int lineNo = 0;
        for (String line : lines) {
            lineNo++;
            if (line.isBlank()) {
                continue;
            }
            try {
                records.add(mapper.readValue(line, AuditRecord.class));
            } catch (Exception e) {
                issues.add(issue(IssueType.SEGMENT_FILE_UNREADABLE, meta.name(), null,
                        "line " + lineNo + " cannot be parsed: " + e.getMessage()));
            }
        }
        return records;
    }

    private void verifySegmentBoundaries(SegmentMeta meta, List<AuditRecord> records,
                                         AuditRecord lastRecord, long observedMin, long observedMax,
                                         List<VerificationIssue> issues) {
        if (records.isEmpty()) {
            return;
        }
        AuditRecord firstRecord = records.get(0);
        if (firstRecord.sequence() != meta.firstSequence()) {
            issues.add(issue(IssueType.CHECKPOINT_MISMATCH, meta.name(), meta.firstSequence(),
                    "firstSequence checkpoint=" + meta.firstSequence()
                            + " actual=" + firstRecord.sequence()));
        }
        if (lastRecord.sequence() != meta.lastSequence()) {
            issues.add(issue(IssueType.CHECKPOINT_MISMATCH, meta.name(), meta.lastSequence(),
                    "lastSequence checkpoint=" + meta.lastSequence()
                            + " actual=" + lastRecord.sequence()));
        }
        if (records.size() != meta.count()) {
            issues.add(issue(IssueType.CHECKPOINT_MISMATCH, meta.name(), null,
                    "record count checkpoint=" + meta.count()
                            + " actual=" + records.size()));
        }
        if (!safeEquals(lastRecord.recordHash(), meta.tailHash())) {
            issues.add(issue(IssueType.CHECKPOINT_MISMATCH, meta.name(), meta.lastSequence(),
                    "tailHash checkpoint=" + meta.tailHash()
                            + " actual=" + lastRecord.recordHash()));
        }
        if (!safeEquals(firstRecord.prevHash(), meta.entryHash())) {
            issues.add(issue(IssueType.CHECKPOINT_MISMATCH, meta.name(), meta.firstSequence(),
                    "entryHash checkpoint=" + meta.entryHash()
                            + " actual=" + firstRecord.prevHash()));
        }
        if (observedMin != meta.minEventTime() || observedMax != meta.maxEventTime()) {
            issues.add(issue(IssueType.CHECKPOINT_MISMATCH, meta.name(), null,
                    "eventTime range checkpoint=[" + meta.minEventTime() + ","
                            + meta.maxEventTime() + "] actual=[" + observedMin + ","
                            + observedMax + "]"));
        }
    }

    /** canonicalContent 只读取非哈希字段，这里直接返回原记录即可。 */
    private static AuditRecord stripHashes(AuditRecord r) {
        return r;
    }

    private static boolean safeEquals(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    private static VerificationIssue issue(IssueType type, String segment, Long sequence,
                                           String detail) {
        VerificationIssue issue = new VerificationIssue(type, segment, sequence, detail);
        LOG.warning(issue.describe());
        return issue;
    }
}
