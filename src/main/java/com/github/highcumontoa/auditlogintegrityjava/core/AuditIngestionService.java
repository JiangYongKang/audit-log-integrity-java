package com.github.highcumontoa.auditlogintegrityjava.core;

import com.github.highcumontoa.auditlogintegrityjava.model.AuditEvent;
import com.github.highcumontoa.auditlogintegrityjava.model.IngestionResult;
import com.github.highcumontoa.auditlogintegrityjava.model.ValidatedEvent;
import com.github.highcumontoa.auditlogintegrityjava.storage.LocalSegmentStore;
import com.github.highcumontoa.auditlogintegrityjava.storage.StoreAppendResult;
import org.springframework.stereotype.Service;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 采集入库入口：归一化校验 → 存储层原子追加（含 eventId 去重）。
 * 四种结论互斥可区分：ACCEPTED / ACCEPTED_NORMALIZED / REJECTED(reason) / DUPLICATE。
 */
@Service
public class AuditIngestionService {

    private static final Logger LOG = Logger.getLogger(AuditIngestionService.class.getName());

    private final LocalSegmentStore store;

    public AuditIngestionService(LocalSegmentStore store) {
        this.store = store;
    }

    public IngestionResult ingest(AuditEvent event) {
        long now = System.currentTimeMillis();
        ValidatedEvent v = EventNormalizer.normalize(event, now);
        if (!v.valid()) {
            LOG.log(Level.INFO, "REJECTED eventId={0} reason={1} notes={2}",
                    new Object[]{v.eventId(), v.rejectReason(), v.normalizationNotes()});
            return IngestionResult.rejected(event, v.rejectReason(),
                    String.join("; ", v.normalizationNotes()));
        }

        StoreAppendResult appended = store.append(v);
        if (appended.duplicate()) {
            LOG.log(Level.INFO, "DUPLICATE eventId={0} existingSequence={1}",
                    new Object[]{v.eventId(), appended.existingSequence()});
            return IngestionResult.duplicate(v.eventId(), appended.existingSequence());
        }

        LOG.log(Level.INFO, "{0} eventId={1} sequence={2} segment={3} notes={4}",
                new Object[]{
                        v.normalized() ? "ACCEPTED_NORMALIZED" : "ACCEPTED",
                        v.eventId(), appended.record().sequence(),
                        appended.record().segment(), v.normalizationNotes()});
        return IngestionResult.accepted(appended.record());
    }
}
