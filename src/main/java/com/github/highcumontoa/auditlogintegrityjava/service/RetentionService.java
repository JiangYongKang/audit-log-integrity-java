package com.github.highcumontoa.auditlogintegrityjava.service;

import com.github.highcumontoa.auditlogintegrityjava.model.AnchorRecord;
import com.github.highcumontoa.auditlogintegrityjava.model.RetentionMode;
import com.github.highcumontoa.auditlogintegrityjava.model.RetentionReport;
import com.github.highcumontoa.auditlogintegrityjava.store.FileAuditLogStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 保留期管理。
 *
 * <p>把活跃段开头连续、eventTime 早于 {@code now-retentionMillis} 的数据记录剪切：
 * ARCHIVE 模式保留段文件；DELETE 模式段文件归档落盘后物理删除，
 * 两种模式都在活跃段留下锚点（含序号范围、条数、折叠摘要），
 * 因此清理之后链的编号、链接与历史结论仍然可解释。</p>
 */
@Service
public class RetentionService {

    private static final Logger log = LoggerFactory.getLogger(RetentionService.class);

    private final FileAuditLogStore store;

    public RetentionService(FileAuditLogStore store) {
        this.store = store;
    }

    public RetentionReport enforceRetention(long now, long retentionMillis, RetentionMode mode) {
        if (retentionMillis < 0) {
            throw new IllegalArgumentException("retentionMillis must be >= 0");
        }
        long cutoff = now - retentionMillis;
        boolean delete = mode == RetentionMode.DELETE;
        AnchorRecord anchor = store.archivePrefix(cutoff, mode.name(), delete);

        if (anchor == null) {
            List<Long> seqInfo = activeRange();
            log.info("retention no-op cutoff={} mode={} activeRange=[{},{}]",
                    cutoff, mode, seqInfo.get(0), seqInfo.get(1));
            return new RetentionReport(cutoff, mode, 0, 0, List.of(),
                    seqInfo.get(0), 0L, null);
        }

        long newFirstSeq = anchor.getToSequence() + 1L;
        RetentionReport report = new RetentionReport(
                cutoff, mode, anchor.getRecordCount(), 1,
                List.of(anchor.getSegmentFile()),
                newFirstSeq, anchor.getSequence(), anchor.getEntryHash());
        log.info("retention enforced cutoff={} mode={} archived [{},{}] count={} anchorSeq={} hash={}",
                cutoff, mode, anchor.getFromSequence(), anchor.getToSequence(),
                anchor.getRecordCount(), anchor.getSequence(), anchor.getEntryHash());
        return report;
    }

    /** 返回活跃数据记录的首尾序号；空段返回 [1,0]。 */
    private List<Long> activeRange() {
        var records = store.readActiveRecords();
        if (records.isEmpty()) {
            return List.of(1L, 0L);
        }
        return List.of(records.get(0).getSequence(), records.get(records.size() - 1).getSequence());
    }
}
