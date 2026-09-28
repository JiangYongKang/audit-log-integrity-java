package com.github.highcumontoa.auditlogintegrityjava.web;

import com.github.highcumontoa.auditlogintegrityjava.core.AuditIngestionService;
import com.github.highcumontoa.auditlogintegrityjava.core.ComplianceQueryService;
import com.github.highcumontoa.auditlogintegrityjava.core.IntegrityVerifier;
import com.github.highcumontoa.auditlogintegrityjava.core.RetentionService;
import com.github.highcumontoa.auditlogintegrityjava.model.AuditEvent;
import com.github.highcumontoa.auditlogintegrityjava.model.AuditRecord;
import com.github.highcumontoa.auditlogintegrityjava.model.CleanupResult;
import com.github.highcumontoa.auditlogintegrityjava.model.IngestionResult;
import com.github.highcumontoa.auditlogintegrityjava.model.QueryFilter;
import com.github.highcumontoa.auditlogintegrityjava.model.VerificationReport;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 本地审计流水的 HTTP 入口：采集、查询、校验、手动清理。
 */
@RestController
@RequestMapping("/audit")
public class AuditController {

    private final AuditIngestionService ingestion;
    private final ComplianceQueryService queryService;
    private final IntegrityVerifier verifier;
    private final RetentionService retention;

    public AuditController(AuditIngestionService ingestion,
                           ComplianceQueryService queryService,
                           IntegrityVerifier verifier,
                           RetentionService retention) {
        this.ingestion = ingestion;
        this.queryService = queryService;
        this.verifier = verifier;
        this.retention = retention;
    }

    @PostMapping("/events")
    public IngestionResult ingest(@RequestBody AuditEvent event) {
        return ingestion.ingest(event);
    }

    @GetMapping("/records")
    public List<AuditRecord> query(@RequestParam(required = false) Long from,
                                   @RequestParam(required = false) Long to,
                                   @RequestParam(required = false) String subject,
                                   @RequestParam(required = false) String result) {
        return queryService.query(new QueryFilter(from, to, subject, result));
    }

    @PostMapping("/verify")
    public VerificationReport verify() {
        return verifier.verify();
    }

    @PostMapping("/retention/sweep")
    public CleanupResult sweep() {
        return retention.sweep(System.currentTimeMillis());
    }
}
