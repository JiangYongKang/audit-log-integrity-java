package com.github.highcumontoa.auditlogintegrityjava.core;

import com.github.highcumontoa.auditlogintegrityjava.config.AuditProperties;
import com.github.highcumontoa.auditlogintegrityjava.model.CleanupResult;
import com.github.highcumontoa.auditlogintegrityjava.model.SegmentMeta;
import com.github.highcumontoa.auditlogintegrityjava.model.SegmentState;
import com.github.highcumontoa.auditlogintegrityjava.storage.LocalSegmentStore;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.logging.Logger;

/**
 * 保留期管理。
 * <p>
 * 判定粒度是<strong>整段</strong>：当一段的 {@code maxEventTime} 早于
 * {@code now - retentionDays} 时，整段到期（避免段内逐条删除破坏链结构）。
 * <ul>
 *   <li>ARCHIVE 模式：段文件移入 archive/，状态置 ARCHIVED，仍可查询、可完整校验；</li>
 *   <li>DELETE 模式：段数据文件删除，状态置 DELETED，检查点保留
 *       firstSequence/lastSequence/entryHash/tailHash，使链在该位置表现为
 *       有登记、可解释的边界，之后插入的记录仍会在跨段边界检查中被发现。</li>
 * </ul>
 * 只有<strong>最老的连续段</strong>会被清理，保证时间线缺口只出现在头部，
 * 清理后查询与校验结论始终可预期。
 */
@Service
public class RetentionService {

    private static final Logger LOG = Logger.getLogger(RetentionService.class.getName());

    private final LocalSegmentStore store;
    private final AuditProperties properties;

    public RetentionService(LocalSegmentStore store, AuditProperties properties) {
        this.store = store;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${audit.retention.sweep-fixed-delay-ms:3600000}")
    public void scheduledSweep() {
        if (properties.getRetention().isSweepEnabled()) {
            sweep(System.currentTimeMillis());
        }
    }

    public CleanupResult sweep(long nowMillis) {
        long cutoff = nowMillis - properties.getRetention().getDays() * 86_400_000L;
        boolean archiveMode = !"DELETE".equalsIgnoreCase(properties.getRetention().getMode());
        List<String> removed = new ArrayList<>();
        List<String> archived = new ArrayList<>();
        long removedRecords = 0L;

        List<SegmentMeta> metas = store.listSegmentsInOrder();
        for (SegmentMeta meta : metas) {
            if (meta.state() == SegmentState.DELETED) {
                continue; // 头部可能已存在已删段，继续往后找
            }
            // 只清理最老的连续到期段：一旦遇到未到期段就停止
            if (meta.maxEventTime() >= cutoff) {
                break;
            }
            if (archiveMode) {
                store.archiveSegment(meta.name());
                archived.add(meta.name());
                // 归档不移除记录，不计入 removedRecords
            } else {
                store.deleteSegment(meta.name());
                removed.add(meta.name());
                removedRecords += meta.count();
            }
            LOG.info("retention sweep cutoff=" + cutoff + " mode="
                    + (archiveMode ? "ARCHIVE" : "DELETE")
                    + " segment=" + meta.name() + " count=" + meta.count()
                    + " maxEventTime=" + meta.maxEventTime());
        }

        Long newFirst = null;
        for (SegmentMeta meta : store.listSegmentsInOrder()) {
            if (meta.state() != SegmentState.DELETED) {
                newFirst = meta.firstSequence();
                break;
            }
        }
        LOG.info(String.format(Locale.ROOT,
                "retention sweep done archived=%d removed=%d removedRecords=%d newFirstSequence=%s",
                archived.size(), removed.size(), removedRecords, newFirst));
        return new CleanupResult(List.copyOf(removed), List.copyOf(archived),
                removedRecords, newFirst);
    }
}
