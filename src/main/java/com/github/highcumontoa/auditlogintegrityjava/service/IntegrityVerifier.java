package com.github.highcumontoa.auditlogintegrityjava.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.highcumontoa.auditlogintegrityjava.model.AuditRecord;
import com.github.highcumontoa.auditlogintegrityjava.model.ChainDefect;
import com.github.highcumontoa.auditlogintegrityjava.model.DefectType;
import com.github.highcumontoa.auditlogintegrityjava.model.EventOutcome;
import com.github.highcumontoa.auditlogintegrityjava.model.IntegrityReport;
import com.github.highcumontoa.auditlogintegrityjava.model.VerificationSegment;
import com.github.highcumontoa.auditlogintegrityjava.store.AuditLogStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 事后完整性校验。
 *
 * <p>校验直接读取活跃段磁盘原始文本行并自行解析（不经过存储的内存视图），
 * 被外部改写、删除、中途插入的行都能定位到具体行/序号。</p>
 *
 * <p>逻辑链按活跃文件中的存储顺序推进：{@code [锚点 数据..] [锚点 数据..] ...}。
 * 锚点回指"被剪切段之前"的摘要（prevHash），其 entryHash 等于该段的折叠摘要；
 * 锚点之后第一条数据的 prevHash 又回指锚点，形成跨清理边界的完整链接。</p>
 *
 * <p>段文件状态三态可区分：VERIFIED（存在且折叠一致）、DELETED（DELETE 模式下
 * 预期物理删除，锚点摘要是唯一证据，属可解释状态）、FAILED（ARCHIVE 模式下缺失
 * 或折叠不一致，属篡改/损坏）。</p>
 */
@Service
public class IntegrityVerifier {

    private static final Logger log = LoggerFactory.getLogger(IntegrityVerifier.class);

    private final AuditLogStore store;
    private final ChainHasher hasher;
    private final ObjectMapper mapper = new ObjectMapper();

    public IntegrityVerifier(AuditLogStore store, ChainHasher hasher) {
        this.store = store;
        this.hasher = hasher;
    }

    public IntegrityReport verify() {
        List<ChainDefect> defects = new ArrayList<>();
        List<VerificationSegment> segments = new ArrayList<>();

        long expectedSeq = 1L;
        String cursorPrev = ChainHasher.GENESIS_HASH;
        long headSeq = 0L;
        String headHash = null;

        long activeFrom = -1L;
        int activeCount = 0;
        boolean activeFailed = false;

        List<String> lines;
        try {
            lines = store.readActiveRawLines();
        } catch (IOException e) {
            defects.add(new ChainDefect(DefectType.MALFORMED_LINE, 0L, "active log unreadable: " + e.getMessage()));
            return new IntegrityReport(false, 0L, null, segments, defects);
        }

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isBlank()) {
                continue;
            }
            long lineNo = i + 1L;
            JsonNode node;
            try {
                node = mapper.readTree(line);
            } catch (Exception e) {
                activeFailed = true;
                defects.add(new ChainDefect(DefectType.MALFORMED_LINE, lineNo, "audit.log: " + e.getMessage()));
                continue;
            }
            long nodeSeq = node.path("sequence").asLong(-1L);
            String type = node.path("type").asText("DATA");

            if ("ANCHOR".equals(type)) {
                AnchorCheck ac = checkAnchor(node, cursorPrev, defects);
                segments.add(ac.segment);
                activeFailed |= ac.segment.status() == VerificationSegment.SegmentStatus.FAILED;
                cursorPrev = ac.declaredHash;
                expectedSeq = ac.anchorTo + 1;
                headSeq = ac.anchorTo;
                headHash = ac.declaredHash;
                activeCount++;
                if (activeFrom < 0) {
                    activeFrom = ac.anchorFrom;
                }
                continue;
            }

            AuditRecord r = toRecord(node);
            if (activeFrom < 0) {
                activeFrom = r.getSequence();
            }
            activeCount++;
            boolean local = seqCheck(r, expectedSeq, cursorPrev, "audit.log", lineNo, defects);
            String recomputed = hasher.hashEntry(cursorPrev, r);
            if (!recomputed.equals(r.getEntryHash())) {
                defects.add(new ChainDefect(DefectType.HASH_MISMATCH, r.getSequence(),
                        "audit.log line " + lineNo + ": record content or stored hash altered"));
                local = true;
            }
            activeFailed |= local;
            cursorPrev = r.getEntryHash();
            expectedSeq = r.getSequence() + 1L;
            headSeq = r.getSequence();
            headHash = r.getEntryHash();
        }

        segments.add(new VerificationSegment("audit.log",
                activeFailed ? VerificationSegment.SegmentStatus.FAILED : VerificationSegment.SegmentStatus.VERIFIED,
                activeFrom < 0 ? 0L : activeFrom, headSeq, activeCount, ""));

        boolean intact = defects.isEmpty()
                && segments.stream().noneMatch(s -> s.status() == VerificationSegment.SegmentStatus.FAILED);
        IntegrityReport report = new IntegrityReport(intact, headSeq, headHash, segments, defects);
        log.info("integrity verify intact={} headSequence={} segments={} defects={}",
                intact, headSeq, segments.size(), defects.size());
        for (ChainDefect d : defects) {
            log.info("defect type={} position={} detail={}", d.type(), d.position(), d.detail());
        }
        return report;
    }

    private record AnchorCheck(VerificationSegment segment, long anchorFrom, long anchorTo, String declaredHash) {}

    private AnchorCheck checkAnchor(JsonNode node, String cursorPrev, List<ChainDefect> defects) {
        long anchorSeq = node.path("sequence").asLong();
        long anchorFrom = node.path("fromSequence").asLong();
        long anchorTo = node.path("toSequence").asLong();
        int anchorCount = node.path("recordCount").asInt();
        String mode = node.path("mode").asText("ARCHIVE");
        String segFile = node.path("segmentFile").asText();
        String declaredHash = node.path("entryHash").asText();
        boolean failed = false;

        if (!node.path("prevHash").asText().equals(cursorPrev)) {
            failed = true;
            defects.add(new ChainDefect(DefectType.PREV_HASH_BROKEN, anchorSeq,
                    "anchor " + segFile + " prevHash does not match preceding chain"));
        }

        Optional<List<AuditRecord>> recs = store.readArchiveRecords(segFile);
        VerificationSegment.SegmentStatus status;
        String detail = "";
        if (recs.isEmpty()) {
            if ("DELETE".equals(mode)) {
                // 预期内的物理删除：锚点保留了段范围与折叠摘要，结论可解释，但无法独立复核
                status = VerificationSegment.SegmentStatus.DELETED;
                detail = "deleted per retention policy; anchor fold " + declaredHash + " is retained evidence";
            } else {
                status = VerificationSegment.SegmentStatus.FAILED;
                failed = true;
                detail = "archive segment missing unexpectedly";
                defects.add(new ChainDefect(DefectType.ARCHIVE_SEGMENT_MISSING, anchorSeq,
                        segFile + " referenced by ARCHIVE anchor but file is gone"));
            }
        } else {
            List<AuditRecord> list = recs.get();
            String folded = hasher.segmentHash(cursorPrev, list);
            if (!folded.equals(declaredHash)) {
                failed = true;
                defects.add(new ChainDefect(DefectType.ARCHIVE_SEGMENT_HASH_MISMATCH, anchorSeq,
                        segFile + " recomputed fold " + folded + " != anchor " + declaredHash));
            }
            if (list.size() != anchorCount
                    || list.get(0).getSequence() != anchorFrom
                    || list.get(list.size() - 1).getSequence() != anchorTo) {
                failed = true;
                defects.add(new ChainDefect(DefectType.MISSING_RECORD, anchorSeq,
                        segFile + " declared range/count disagrees with segment contents"));
            }
            status = failed ? VerificationSegment.SegmentStatus.FAILED : VerificationSegment.SegmentStatus.VERIFIED;
        }
        return new AnchorCheck(new VerificationSegment(segFile, status, anchorFrom, anchorTo,
                anchorCount, detail), anchorFrom, anchorTo, declaredHash);
    }

    /** 编号与链接检查：删除=GAP，中途插入=DUPLICATE，指针断链=PREV_HASH_BROKEN。 */
    private boolean seqCheck(AuditRecord r, long expectedSeq, String expectedPrev,
                             String where, long line, List<ChainDefect> defects) {
        boolean bad = false;
        if (r.getSequence() < expectedSeq) {
            bad = true;
            defects.add(new ChainDefect(DefectType.SEQUENCE_DUPLICATE, r.getSequence(),
                    where + " line " + line + ": sequence " + r.getSequence()
                            + " already passed (possible mid-chain insert)"));
        } else if (r.getSequence() > expectedSeq) {
            bad = true;
            defects.add(new ChainDefect(DefectType.SEQUENCE_GAP, expectedSeq,
                    where + " line " + line + ": missing sequence(s) "
                            + expectedSeq + ".." + (r.getSequence() - 1) + " (possible delete)"));
        }
        if (!r.getPrevHash().equals(expectedPrev)) {
            bad = true;
            defects.add(new ChainDefect(DefectType.PREV_HASH_BROKEN, r.getSequence(),
                    where + " line " + line + ": prevHash disagrees with prior chain entry"));
        }
        return bad;
    }

    private AuditRecord toRecord(JsonNode n) {
        AuditRecord r = new AuditRecord();
        r.setSequence(n.path("sequence").asLong());
        r.setEventId(n.path("eventId").asText(null));
        r.setSource(n.path("source").asText(null));
        r.setSubject(n.path("subject").asText(null));
        r.setAction(n.path("action").asText(null));
        r.setEventTime(n.path("eventTime").asLong());
        r.setStoredAt(n.path("storedAt").asLong());
        String oc = n.path("outcome").asText(null);
        if (oc != null) {
            r.setOutcome(EventOutcome.valueOf(oc));
        }
        r.setDetail(n.path("detail").isNull() ? null : n.path("detail").asText(null));
        r.setPrevHash(n.path("prevHash").asText(null));
        r.setEntryHash(n.path("entryHash").asText(null));
        return r;
    }
}
