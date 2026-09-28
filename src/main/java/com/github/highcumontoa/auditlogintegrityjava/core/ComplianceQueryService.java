package com.github.highcumontoa.auditlogintegrityjava.core;

import com.github.highcumontoa.auditlogintegrityjava.model.AuditRecord;
import com.github.highcumontoa.auditlogintegrityjava.model.QueryFilter;
import com.github.highcumontoa.auditlogintegrityjava.storage.LocalSegmentStore;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * 合规查询：按事件时间区间（包含两端）、主体、结果维度过滤现存
 * （ACTIVE + ARCHIVED）记录，结果按序号升序。
 * 已 DELETED 的段数据不存在，不会出现在结果中；这是保留期语义的一部分，
 * 调用方可通过完整性报告中的 deletedSegments 得知该缺口是“到期清理”而非异常。
 */
@Service
public class ComplianceQueryService {

    private static final Logger LOG = Logger.getLogger(ComplianceQueryService.class.getName());

    private final LocalSegmentStore store;

    public ComplianceQueryService(LocalSegmentStore store) {
        this.store = store;
    }

    public List<AuditRecord> query(QueryFilter filter) {
        QueryFilter f = filter == null ? new QueryFilter(null, null, null, null) : filter;
        List<AuditRecord> hits = store.readAllRecords().stream()
                .filter(r -> f.fromEventTime() == null || r.eventTime() >= f.fromEventTime())
                .filter(r -> f.toEventTime() == null || r.eventTime() <= f.toEventTime())
                .filter(r -> f.subject() == null || f.subject().equals(r.subject()))
                .filter(r -> f.result() == null
                        || f.result().toUpperCase(Locale.ROOT).equals(r.result()))
                .collect(Collectors.toList());
        LOG.info("query filter=[" + f.fromEventTime() + "," + f.toEventTime()
                + "] subject=" + f.subject() + " result=" + f.result()
                + " matched=" + hits.size());
        return hits;
    }
}
