package com.github.highcumontoa.auditlogintegrityjava.service;

import com.github.highcumontoa.auditlogintegrityjava.model.AuditEventInput;
import com.github.highcumontoa.auditlogintegrityjava.model.IngestResult;
import com.github.highcumontoa.auditlogintegrityjava.store.AuditLogStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 采集入口：归一化 -&gt; 幂等去重 -&gt; 串行追加。
 *
 * <p>拒绝、重复、接受三种结论互斥可区分；重复上报返回首次入库的序号与哈希，
 * 绝不产生第二条流水。</p>
 */
@Service
public class AuditIngestService {

    private static final Logger log = LoggerFactory.getLogger(AuditIngestService.class);

    private final EventNormalizer normalizer;
    private final AuditLogStore store;

    public AuditIngestService(EventNormalizer normalizer, AuditLogStore store) {
        this.normalizer = normalizer;
        this.store = store;
    }

    public IngestResult ingest(AuditEventInput input) {
        EventNormalizer.Normalized n = normalizer.normalize(input);
        if (!n.accepted()) {
            log.info("ingest rejected eventId={} source={} reason={}",
                    input.getEventId(), input.getSource(), n.rejectionReason());
            return IngestResult.rejected(n.rejectionReason());
        }
        AuditLogStore.AppendOutcome outcome = store.appendIfAbsent(n.record());
        if (outcome.duplicate()) {
            log.info("ingest duplicate eventId={} existingSequence={}",
                    outcome.stored().getEventId(), outcome.stored().getSequence());
            return IngestResult.duplicate(outcome.stored().getSequence(), outcome.stored().getEntryHash());
        }
        log.info("ingest accepted eventId={} sequence={} hash={}",
                outcome.stored().getEventId(), outcome.stored().getSequence(),
                outcome.stored().getEntryHash());
        return IngestResult.accepted(outcome.stored().getSequence(), outcome.stored().getEntryHash());
    }
}
