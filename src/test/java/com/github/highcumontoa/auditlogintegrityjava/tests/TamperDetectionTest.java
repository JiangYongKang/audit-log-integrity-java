package com.github.highcumontoa.auditlogintegrityjava.tests;

import com.github.highcumontoa.auditlogintegrityjava.config.AuditProperties;
import com.github.highcumontoa.auditlogintegrityjava.model.AuditEventInput;
import com.github.highcumontoa.auditlogintegrityjava.model.ChainDefect;
import com.github.highcumontoa.auditlogintegrityjava.model.DefectType;
import com.github.highcumontoa.auditlogintegrityjava.model.IntegrityReport;
import com.github.highcumontoa.auditlogintegrityjava.service.AuditIngestService;
import com.github.highcumontoa.auditlogintegrityjava.service.ChainHasher;
import com.github.highcumontoa.auditlogintegrityjava.service.EventNormalizer;
import com.github.highcumontoa.auditlogintegrityjava.service.IntegrityVerifier;
import com.github.highcumontoa.auditlogintegrityjava.store.FileAuditLogStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 篡改检测：改写 / 删除 / 中途插入 / 非法行必须被检测并定位，失败原因可区分。 */
class TamperDetectionTest {

    private static final Logger log = LoggerFactory.getLogger(TamperDetectionTest.class);

    @TempDir
    Path tempDir;
    private Path activeFile;

    @BeforeEach
    void seed() {
        AuditProperties props = new AuditProperties();
        props.setStorageDir(tempDir.toString());
        FileAuditLogStore store = new FileAuditLogStore(props, new ChainHasher());
        AuditIngestService ingest = new AuditIngestService(new EventNormalizer(), store);
        for (int i = 1; i <= 6; i++) {
            AuditEventInput in = new AuditEventInput();
            in.setEventId("evt-" + i);
            in.setSource("svc");
            in.setSubject("alice");
            in.setAction("login");
            in.setEventTime(1_700_000_000_000L + i);
            in.setOutcome(i % 2 == 0 ? "failure" : "success");
            in.setDetail("payload-" + i);
            ingest.ingest(in);
        }
        activeFile = tempDir.resolve(FileAuditLogStore.ACTIVE_FILE);
    }

    private IntegrityReport verifyAfter(Runnable tamper) {
        tamper.run();
        AuditProperties props = new AuditProperties();
        props.setStorageDir(tempDir.toString());
        FileAuditLogStore reopened = new FileAuditLogStore(props, new ChainHasher());
        IntegrityReport report = new IntegrityVerifier(reopened, new ChainHasher()).verify();
        for (ChainDefect d : report.defects()) {
            log.info("tamper evidence type={} position={} detail={}", d.type(), d.position(), d.detail());
        }
        return report;
    }

    @Test
    void detectsModifiedRecordContent() throws IOException {
        IntegrityReport report = verifyAfter(() -> {
            try {
                List<String> lines = Files.readAllLines(activeFile, StandardCharsets.UTF_8);
                // 改写第 3 条的 subject，但保留原 entryHash
                lines.set(2, lines.get(2).replace("\"subject\":\"alice\"", "\"subject\":\"mallory\""));
                Files.write(activeFile, lines, StandardCharsets.UTF_8);
                log.info("tamper action=modify line=3 subject alice->mallory");
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
        assertFalse(report.intact());
        assertTrue(report.defects().stream().anyMatch(d -> d.type() == DefectType.HASH_MISMATCH && d.position() == 3),
                "altered content located at sequence 3");
        // 只改了内容，链指针未变，后续记录不应报断链
        assertTrue(report.defects().stream().noneMatch(d -> d.type() == DefectType.PREV_HASH_BROKEN));
    }

    @Test
    void detectsDeletedMiddleRecord() throws IOException {
        IntegrityReport report = verifyAfter(() -> {
            try {
                List<String> lines = Files.readAllLines(activeFile, StandardCharsets.UTF_8);
                lines.remove(3); // 删除第 4 条
                Files.write(activeFile, lines, StandardCharsets.UTF_8);
                log.info("tamper action=delete line=4 (sequence 4)");
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
        assertFalse(report.intact());
        ChainDefect gap = firstOf(report, DefectType.SEQUENCE_GAP);
        assertEquals(4L, gap.position(), "gap located starting at missing sequence 4");
        ChainDefect broken = firstOf(report, DefectType.PREV_HASH_BROKEN);
        assertEquals(5L, broken.position(), "the record after deletion points to a vanished hash");
    }

    @Test
    void detectsMidChainInsert() throws IOException {
        IntegrityReport report = verifyAfter(() -> {
            try {
                List<String> lines = Files.readAllLines(activeFile, StandardCharsets.UTF_8);
                // 在第 2 与第 3 条之间插入一条伪造的 seq=3 记录（复制并篡改）
                String forged = lines.get(2)
                        .replace("\"eventId\":\"evt-3\"", "\"eventId\":\"evt-forged\"")
                        .replace("\"detail\":\"payload-3\"", "\"detail\":\"injected\"");
                lines.add(2, forged);
                Files.write(activeFile, lines, StandardCharsets.UTF_8);
                log.info("tamper action=insert between seq2 and seq3 forgedEventId=evt-forged");
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
        assertFalse(report.intact());
        assertTrue(report.defects().stream().anyMatch(d -> d.type() == DefectType.SEQUENCE_DUPLICATE),
                "inserted row reuses an existing sequence -> duplicate");
        assertTrue(report.defects().stream().anyMatch(d -> d.type() == DefectType.PREV_HASH_BROKEN),
                "inserted row breaks the prevHash linkage");
    }

    @Test
    void detectsMalformedLine() throws IOException {
        IntegrityReport report = verifyAfter(() -> {
            try {
                List<String> lines = Files.readAllLines(activeFile, StandardCharsets.UTF_8);
                lines.add(2, "{ this is not valid json ");
                Files.write(activeFile, lines, StandardCharsets.UTF_8);
                log.info("tamper action=garbage line inserted at line 3");
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
        assertFalse(report.intact());
        ChainDefect malformed = firstOf(report, DefectType.MALFORMED_LINE);
        assertEquals(3L, malformed.position(), "malformed line located by physical line number");
    }

    private ChainDefect firstOf(IntegrityReport r, DefectType type) {
        return r.defects().stream().filter(d -> d.type() == type).findFirst()
                .orElseThrow(() -> new AssertionError("expected defect " + type + " in " + r.defects()));
    }
}
