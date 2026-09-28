package com.github.highcumontoa.auditlogintegrityjava.tests;

import com.github.highcumontoa.auditlogintegrityjava.config.AuditProperties;
import com.github.highcumontoa.auditlogintegrityjava.core.ComplianceQueryService;
import com.github.highcumontoa.auditlogintegrityjava.core.IntegrityVerifier;
import com.github.highcumontoa.auditlogintegrityjava.core.RetentionService;
import com.github.highcumontoa.auditlogintegrityjava.storage.LocalSegmentStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/**
 * 测试辅助：为每个用例创建独立的临时存储目录并手工装配组件，避免测试间相互污染。
 */
public final class AuditTestSupport {

    public record Services(Path dir,
                           AuditProperties properties,
                           LocalSegmentStore store,
                           IntegrityVerifier verifier,
                           ComplianceQueryService queryService,
                           RetentionService retention) {
        public void deleteDir() {
            deleteRecursively(dir);
        }
    }

    private AuditTestSupport() {
    }

    public static Services newServices(int maxRecordsPerSegment, int retentionDays, String mode) {
        try {
            Path dir = Files.createTempDirectory("audit-test-");
            AuditProperties props = new AuditProperties();
            props.getStorage().setDir(dir.toString());
            props.getSegment().setMaxRecords(maxRecordsPerSegment);
            props.getRetention().setDays(retentionDays);
            props.getRetention().setMode(mode);
            props.getRetention().setSweepEnabled(false);
            LocalSegmentStore store = new LocalSegmentStore(props);
            IntegrityVerifier verifier = new IntegrityVerifier(store);
            ComplianceQueryService query = new ComplianceQueryService(store);
            RetentionService retention = new RetentionService(store, props);
            return new Services(dir, props, store, verifier, query, retention);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static void deleteRecursively(Path dir) {
        if (!Files.exists(dir)) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // 临时目录清理尽力而为
                }
            });
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
