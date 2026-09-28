package com.github.highcumontoa.auditlogintegrityjava.service;

import com.github.highcumontoa.auditlogintegrityjava.model.AuditRecord;
import com.github.highcumontoa.auditlogintegrityjava.model.ChainEntry;
import com.github.highcumontoa.auditlogintegrityjava.model.AnchorRecord;
import com.github.highcumontoa.auditlogintegrityjava.model.QueryCriteria;
import com.github.highcumontoa.auditlogintegrityjava.model.QueryResult;
import com.github.highcumontoa.auditlogintegrityjava.store.AuditLogStore;
import com.github.highcumontoa.auditlogintegrityjava.store.FileAuditLogStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 合规查询：按时间区间、主体、来源、动作、结果过滤。
 *
 * <p>默认只查活跃段；{@code includeArchived=true} 时依次扫描全部归档段 + 活跃段。
 * DELETE 模式下已物理删除的段会出现在 {@code unavailableSegments} 中——
 * 查询结论因此始终自解释：命中数、扫描了哪些段、哪些区间已不可得，三者同时给出。</p>
 */
@Service
public class AuditQueryService {

    private static final Logger log = LoggerFactory.getLogger(AuditQueryService.class);

    private final AuditLogStore store;

    public AuditQueryService(AuditLogStore store) {
        this.store = store;
    }

    public QueryResult query(QueryCriteria criteria) {
        QueryCriteria c = criteria == null ? new QueryCriteria() : criteria;
        boolean includeArchived = Boolean.TRUE.equals(c.getIncludeArchived());
        int limit = c.getLimit() == null || c.getLimit() <= 0 ? Integer.MAX_VALUE : c.getLimit();

        List<String> scanned = new ArrayList<>();
        List<String> unavailable = new ArrayList<>();
        List<AuditRecord> hits = new ArrayList<>();

        if (includeArchived) {
            // 以活跃链中的锚点为归档区间的权威清单：段文件存在则扫描，
            // DELETE 模式下已物理删除的段归入 unavailableSegments，显式披露不可得区间。
            for (ChainEntry e : store.readActiveChain()) {
                if (!e.isAnchor()) {
                    continue;
                }
                AnchorRecord anchor = (AnchorRecord) e;
                String seg = anchor.getSegmentFile();
                Optional<List<AuditRecord>> recs = store.readArchiveRecords(seg);
                if (recs.isEmpty()) {
                    unavailable.add(seg);
                    continue;
                }
                scanned.add(seg);
                for (AuditRecord r : recs.get()) {
                    if (matches(r, c)) {
                        hits.add(r);
                    }
                }
            }
        }
        scanned.add(FileAuditLogStore.ACTIVE_FILE);
        for (AuditRecord r : store.readActiveRecords()) {
            if (matches(r, c)) {
                hits.add(r);
            }
        }

        hits.sort(Comparator.comparingLong(AuditRecord::getSequence));
        boolean truncated = hits.size() > limit;
        if (truncated) {
            hits = new ArrayList<>(hits.subList(0, limit));
        }
        Set<String> scannedDedup = new LinkedHashSet<>(scanned);
        log.info("query includeArchived={} hits={} truncated={} scanned={} unavailable={}",
                includeArchived, hits.size(), truncated, scannedDedup, unavailable);
        return new QueryResult(hits.size(), truncated, List.copyOf(scannedDedup), List.copyOf(unavailable), hits);
    }

    private boolean matches(AuditRecord r, QueryCriteria c) {
        if (c.getFromTime() != null && r.getEventTime() < c.getFromTime()) {
            return false;
        }
        if (c.getToTime() != null && r.getEventTime() > c.getToTime()) {
            return false;
        }
        if (c.getSubject() != null && !c.getSubject().equals(r.getSubject())) {
            return false;
        }
        if (c.getSource() != null && !c.getSource().equals(r.getSource())) {
            return false;
        }
        if (c.getAction() != null && !c.getAction().equals(r.getAction())) {
            return false;
        }
        return c.getOutcome() == null || c.getOutcome() == r.getOutcome();
    }
}
