package com.github.highcumontoa.auditlogintegrityjava.service;

import com.github.highcumontoa.auditlogintegrityjava.config.AuditProperties;
import com.github.highcumontoa.auditlogintegrityjava.model.RetentionMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 保留期定时执行器。仅当 {@code audit.retention-schedule-enabled=true} 且
 * {@code audit.retention-millis>0} 时真正动作；固定速率由配置给出。
 */
@Component
public class RetentionScheduler {

    private static final Logger log = LoggerFactory.getLogger(RetentionScheduler.class);

    private final AuditProperties properties;
    private final RetentionService retentionService;

    public RetentionScheduler(AuditProperties properties, RetentionService retentionService) {
        this.properties = properties;
        this.retentionService = retentionService;
    }

    @Scheduled(fixedDelayString = "${audit.retention-schedule-millis:3600000}")
    public void scheduledEnforcement() {
        if (!properties.isRetentionScheduleEnabled() || properties.getRetentionMillis() <= 0L) {
            return;
        }
        RetentionMode mode;
        try {
            mode = RetentionMode.valueOf(properties.getRetentionMode().trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warn("invalid audit.retention-mode={}, skip scheduled enforcement", properties.getRetentionMode());
            return;
        }
        retentionService.enforceRetention(System.currentTimeMillis(), properties.getRetentionMillis(), mode);
    }
}
