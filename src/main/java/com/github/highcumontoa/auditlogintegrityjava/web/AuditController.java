package com.github.highcumontoa.auditlogintegrityjava.web;

import com.github.highcumontoa.auditlogintegrityjava.model.AuditEventInput;
import com.github.highcumontoa.auditlogintegrityjava.model.IngestResult;
import com.github.highcumontoa.auditlogintegrityjava.model.IntegrityReport;
import com.github.highcumontoa.auditlogintegrityjava.model.QueryCriteria;
import com.github.highcumontoa.auditlogintegrityjava.model.QueryResult;
import com.github.highcumontoa.auditlogintegrityjava.model.RetentionMode;
import com.github.highcumontoa.auditlogintegrityjava.model.RetentionReport;
import com.github.highcumontoa.auditlogintegrityjava.service.AuditIngestService;
import com.github.highcumontoa.auditlogintegrityjava.service.AuditQueryService;
import com.github.highcumontoa.auditlogintegrityjava.service.IntegrityVerifier;
import com.github.highcumontoa.auditlogintegrityjava.service.RetentionService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 审计日志本地接口：采集 / 校验 / 保留 / 查询。 */
@RestController
@RequestMapping("/audit")
public class AuditController {

    private final AuditIngestService ingestService;
    private final IntegrityVerifier verifier;
    private final RetentionService retentionService;
    private final AuditQueryService queryService;

    public AuditController(AuditIngestService ingestService,
                           IntegrityVerifier verifier,
                           RetentionService retentionService,
                           AuditQueryService queryService) {
        this.ingestService = ingestService;
        this.verifier = verifier;
        this.retentionService = retentionService;
        this.queryService = queryService;
    }

    @PostMapping("/events")
    public IngestResult ingest(@RequestBody AuditEventInput input) {
        return ingestService.ingest(input);
    }

    @GetMapping("/verify")
    public IntegrityReport verify() {
        return verifier.verify();
    }

    @PostMapping("/retention")
    public RetentionReport enforceRetention(@RequestParam long now,
                                            @RequestParam long retentionMillis,
                                            @RequestParam(defaultValue = "ARCHIVE") RetentionMode mode) {
        return retentionService.enforceRetention(now, retentionMillis, mode);
    }

    @PostMapping("/query")
    public QueryResult query(@RequestBody QueryCriteria criteria) {
        return queryService.query(criteria);
    }
}
