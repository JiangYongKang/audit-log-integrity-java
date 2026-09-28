package com.github.highcumontoa.auditlogintegrityjava.tests;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP 端到端：采集（接受/拒绝/重复）、校验、查询接口返回可区分的结论。
 */
@SpringBootTest
@AutoConfigureMockMvc
class AuditControllerTest {

    private static final Path STORE_DIR;

    static {
        try {
            STORE_DIR = Files.createTempDirectory("audit-http-test-");
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("audit.storage.dir", STORE_DIR::toString);
        registry.add("audit.retention.sweep-enabled", () -> "false");
    }

    @Autowired
    private MockMvc mvc;

    @Test
    void ingestRejectDuplicateVerifyQueryOverHttp() throws Exception {
        String body = """
                {"eventId":"http-1","source":"gw","subject":"alice","action":"login",
                 "result":"success","eventTime":1700000000000,"details":{"ip":"127.0.0.1"}}
                """;
        mvc.perform(post("/audit/events").contentType("application/json").content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("ACCEPTED"))
                .andExpect(jsonPath("$.sequence").value(0));

        // 重复上报
        mvc.perform(post("/audit/events").contentType("application/json").content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("DUPLICATE"));

        // 字段缺失/非法 -> REJECTED 且带可区分原因
        String bad = """
                {"eventId":"http-bad","source":"gw","subject":"alice","action":"login",
                 "result":"WEIRD"}
                """;
        mvc.perform(post("/audit/events").contentType("application/json").content(bad))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("REJECTED"))
                .andExpect(jsonPath("$.rejectReason").value("UNSUPPORTED_RESULT"));

        // 完整性校验
        mvc.perform(post("/audit/verify"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.intact").value(true))
                .andExpect(jsonPath("$.verifiedRecords").value(1));

        // 维度查询
        mvc.perform(get("/audit/records").param("subject", "alice").param("result", "SUCCESS"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].eventId").value("http-1"));
    }
}
