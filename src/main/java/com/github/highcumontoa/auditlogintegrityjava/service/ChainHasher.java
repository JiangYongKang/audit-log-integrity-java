package com.github.highcumontoa.auditlogintegrityjava.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.highcumontoa.auditlogintegrityjava.model.AuditRecord;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 哈希链计算原语。
 *
 * <p>规范化内容：按固定字段顺序构造 JSON（LinkedHashMap 保证键序），
 * 与存储行的文本格式无关——校验时从反序列化后的字段重新推导，
 * 因此任何字段级改写都会导致摘要不一致。</p>
 *
 * <p>条目摘要：{@code SHA-256( prevHash + NUL + canonicalJson )}；
 * 首条记录的 prevHash 为 {@link #GENESIS_HASH}。</p>
 *
 * <p>段摘要：按链顺序对段内记录折叠，结果等于段末记录的条目摘要，
 * 因此归档锚点可以用一个摘要值代表整段被剪切的记录。</p>
 */
@Component
public class ChainHasher {

    public static final String GENESIS_HASH = "GENESIS";

    private final ObjectMapper canonicalMapper = new ObjectMapper();

    /** 数据记录内容（不含链字段）的规范化串。 */
    public String canonicalContent(AuditRecord r) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("eventId", r.getEventId());
        body.put("source", r.getSource());
        body.put("subject", r.getSubject());
        body.put("action", r.getAction());
        body.put("eventTime", r.getEventTime());
        body.put("storedAt", r.getStoredAt());
        body.put("outcome", r.getOutcome() == null ? null : r.getOutcome().name());
        body.put("detail", r.getDetail());
        try {
            return canonicalMapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new IllegalStateException("canonical serialization failed", e);
        }
    }

    /** 条目哈希 = sha256(prevHash | content)。 */
    public String hashEntry(String prevHash, AuditRecord r) {
        return digest(prevHash + '\0' + canonicalContent(r));
    }

    /**
     * 段折叠摘要：从 startingPrevHash 开始逐条折叠，
     * 返回值等于段内最后一条记录的 entryHash。
     */
    public String segmentHash(String startingPrevHash, List<AuditRecord> records) {
        String h = startingPrevHash;
        for (AuditRecord r : records) {
            h = hashEntry(h, r);
        }
        return h;
    }

    /** SHA-256 十六进制摘要。 */
    public String digest(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] raw = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(raw.length * 2);
            for (byte b : raw) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
