package com.github.highcumontoa.auditlogintegrityjava.config;

import com.github.highcumontoa.auditlogintegrityjava.service.ChainHasher;
import com.github.highcumontoa.auditlogintegrityjava.service.EventNormalizer;
import com.github.highcumontoa.auditlogintegrityjava.store.AuditLogStore;
import com.github.highcumontoa.auditlogintegrityjava.store.FileAuditLogStore;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 审计模块装配入口：存储目录由 {@link AuditProperties#getStorageDir()} 决定。 */
@Configuration
@EnableConfigurationProperties(AuditProperties.class)
public class AuditConfig {

    @Bean
    public AuditLogStore auditLogStore(AuditProperties properties, ChainHasher hasher) {
        return new FileAuditLogStore(properties, hasher);
    }

    @Bean
    public EventNormalizer eventNormalizer() {
        return new EventNormalizer();
    }
}
