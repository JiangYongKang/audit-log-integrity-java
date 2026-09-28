package com.github.highcumontoa.auditlogintegrityjava.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.highcumontoa.auditlogintegrityjava.model.AuditRecord;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.TreeMap;

/**
 * 哈希链工具：规范化内容编码与 SHA-256 链计算。
 * <p>
 * 链语义：
 * <pre>
 *   recordHash = sha256Hex( canonicalContent(record) || "\n" || prevHash )
 * </pre>
 * 首条记录的 prevHash 为固定的 {@link #GENESIS_HASH}。
 * canonicalContent 不包含 prevHash/recordHash 两个哈希字段，字段间以 SOH(0x01) 分隔，
 * details 使用键排序后的 JSON 以保证确定性。任何字节级改写都会改变 recordHash。
 */
public final class HashChain {

    /** 链首的前向哈希（64 个 0）。 */
    public static final String GENESIS_HASH =
            "0000000000000000000000000000000000000000000000000000000000000000";

    private static final char FIELD_SEP = '\u0001';
    private static final byte[] RECORD_SEPARATOR = "\n".getBytes(StandardCharsets.UTF_8);

    private static final ObjectMapper CANONICAL_MAPPER = new ObjectMapper();

    private HashChain() {
    }

    /** 生成一条记录的确定性规范内容（不含哈希字段）。 */
    public static String canonicalContent(AuditRecord r) {
        StringBuilder sb = new StringBuilder(128);
        sb.append(r.sequence()).append(FIELD_SEP)
                .append(nullToEmpty(r.segment())).append(FIELD_SEP)
                .append(nullToEmpty(r.eventId())).append(FIELD_SEP)
                .append(nullToEmpty(r.source())).append(FIELD_SEP)
                .append(nullToEmpty(r.subject())).append(FIELD_SEP)
                .append(nullToEmpty(r.action())).append(FIELD_SEP)
                .append(nullToEmpty(r.result())).append(FIELD_SEP)
                .append(r.eventTime()).append(FIELD_SEP)
                .append(r.ingestTime()).append(FIELD_SEP)
                .append(r.normalized()).append(FIELD_SEP)
                .append(canonicalDetails(r.details()));
        return sb.toString();
    }

    /** details 的规范 JSON：键按字典序排序，null 映射按空 map 处理。 */
    public static String canonicalDetails(Map<String, String> details) {
        try {
            Map<String, String> sorted = details == null ? Map.of() : new TreeMap<>(details);
            return CANONICAL_MAPPER.writeValueAsString(sorted);
        } catch (Exception e) {
            throw new IllegalStateException("failed to canonicalize details", e);
        }
    }

    /** 计算 recordHash = sha256(canonicalContent + LF + prevHash)。 */
    public static String computeRecordHash(String canonicalContent, String prevHash) {
        MessageDigest md = sha256Digest();
        md.update(canonicalContent.getBytes(StandardCharsets.UTF_8));
        md.update(RECORD_SEPARATOR);
        md.update(prevHash.getBytes(StandardCharsets.UTF_8));
        return toHex(md.digest());
    }

    public static String sha256Hex(byte[] bytes) {
        return toHex(sha256Digest().digest(bytes));
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String toHex(byte[] digest) {
        StringBuilder sb = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
