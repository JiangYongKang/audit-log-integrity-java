package com.github.highcumontoa.auditlogintegrityjava.tests;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 经 REST 接口端到端验证四种结论与清理后查询的可解释性。 */
@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuditApiIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(AuditApiIntegrationTest.class);

    // 类加载阶段（早于 Spring 上下文缓存）就指定一个独立临时目录，
    // 避免与其它测试类共享默认 data/audit 上下文
    private static final Path STORAGE_DIR;
    static {
        try {
            STORAGE_DIR = Files.createTempDirectory("audit-it-");
            System.setProperty("AUDIT_STORAGE_DIR", STORAGE_DIR.toString());
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @Autowired
    private MockMvc mvc;
    private final ObjectMapper json = new ObjectMapper();

    @BeforeAll
    void logStorageDir() {
        log.info("integration test storage dir: {}", STORAGE_DIR);
    }

    @Test
    void fullLifecycleOverHttp() throws Exception {
        long oldTime = 1_000_000L;
        long now = 2_000_000L;

        // 1) 接受两条
        postEvent(Map.of("eventId", "a1", "source", "crm", "subject", "alice",
                "action", "login", "eventTime", oldTime, "outcome", "success"));
        postEvent(Map.of("eventId", "a2", "source", "crm", "subject", "bob",
                "action", "login", "eventTime", now, "outcome", "failure"));

        // 2) 重复 -> DUPLICATE，不产生新序号
        JsonNode dup = postEvent(Map.of("eventId", "a1", "source", "crm", "subject", "alice",
                "action", "login", "eventTime", oldTime));
        log.info("http duplicate status={} seq={}", dup.path("status").asText(), dup.path("sequence").asLong());
        assertEquals("DUPLICATE", dup.path("status").asText());
        assertEquals(1L, dup.path("sequence").asLong());

        // 3) 非法 -> REJECTED + 可区分原因
        JsonNode rejected = postEvent(Map.of("eventId", "a3", "source", "crm",
                "subject", "alice", "action", "login"));
        log.info("http rejected status={} reason={}", rejected.path("status").asText(),
                rejected.path("rejectionReason").asText());
        assertEquals("REJECTED", rejected.path("status").asText());
        assertEquals("MISSING_EVENT_TIME", rejected.path("rejectionReason").asText());

        // 4) 初始校验通过
        JsonNode verify = getVerify();
        log.info("http verify intact={} headSequence={}", verify.path("intact").asBoolean(),
                verify.path("headSequence").asLong());
        assertTrue(verify.path("intact").asBoolean());
        assertEquals(2L, verify.path("headSequence").asLong());

        // 5) 保留期清理：a1 到期被归档，a2 保留
        JsonNode rr = json.readTree(mvc.perform(post("/audit/retention")
                        .param("now", String.valueOf(now))
                        .param("retentionMillis", "500000")
                        .param("mode", "ARCHIVE"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        log.info("http retention archived={} anchorSeq={} newActiveFirst={}",
                rr.path("archivedRecords").asInt(), rr.path("anchorSequence").asLong(),
                rr.path("newActiveFirstSequence").asLong());
        assertEquals(1, rr.path("archivedRecords").asInt());
        assertEquals(2L, rr.path("newActiveFirstSequence").asLong());

        // 重复执行清理：没有新的到期数据 -> no-op
        JsonNode rr2 = json.readTree(mvc.perform(post("/audit/retention")
                        .param("now", String.valueOf(now))
                        .param("retentionMillis", "500000")
                        .param("mode", "ARCHIVE"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertEquals(0, rr2.path("archivedRecords").asInt());

        // 6) 清理后校验仍通过（跨锚点）
        JsonNode verify2 = getVerify();
        log.info("http post-retention verify intact={} segments={}", verify2.path("intact").asBoolean(),
                verify2.path("segments").size());
        assertTrue(verify2.path("intact").asBoolean());

        // 7) 含归档的查询：alice 命中归档段
        JsonNode q = json.readTree(mvc.perform(post("/audit/query")
                        .contentType("application/json")
                        .content("{\"subject\":\"alice\",\"includeArchived\":true}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        log.info("http query alice total={} scanned={}", q.path("total").asInt(),
                q.path("scannedSegments").findValuesAsText(""));
        assertEquals(1, q.path("total").asInt());
    }

    private JsonNode postEvent(Map<String, Object> body) throws Exception {
        String resp = mvc.perform(post("/audit/events")
                        .contentType("application/json")
                        .content(json.writeValueAsString(body)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode node = json.readTree(resp);
        log.info("http ingest eventId={} -> status={} reason={} seq={}",
                body.get("eventId"), node.path("status").asText(),
                node.path("rejectionReason").asText(null), node.path("sequence").asLong(-1));
        return node;
    }

    private JsonNode getVerify() throws Exception {
        return json.readTree(mvc.perform(get("/audit/verify"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }
}
