package com.github.highcumontoa.auditlogintegrityjava.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.highcumontoa.auditlogintegrityjava.config.AuditProperties;
import com.github.highcumontoa.auditlogintegrityjava.model.AnchorRecord;
import com.github.highcumontoa.auditlogintegrityjava.model.AuditRecord;
import com.github.highcumontoa.auditlogintegrityjava.model.ChainEntry;
import com.github.highcumontoa.auditlogintegrityjava.model.EventOutcome;
import com.github.highcumontoa.auditlogintegrityjava.service.ChainHasher;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 基于 JSON-lines 本地文件的审计流水存储。
 *
 * <p>磁盘布局：{@code <dir>/audit.log} 为活跃段（每行一个 DATA/ANCHOR 条目），
 * {@code <dir>/archives/segment-<from>-<to>.jsonl} 为归档段（首行 SEGMENT 头，其后 DATA 行）。</p>
 *
 * <p>全部变更在同一把内存锁内完成"读改写"，追加采用单行原子写入，
 * 活跃段整体重写（归档）采用临时文件 + 原子替换，保证并发下编号连续、不重不漏。</p>
 */
public class FileAuditLogStore implements AuditLogStore {

    public static final String ACTIVE_FILE = "audit.log";
    public static final String ARCHIVE_DIR = "archives";
    public static final String TYPE_DATA = "DATA";
    public static final String TYPE_ANCHOR = "ANCHOR";
    public static final String TYPE_SEGMENT = "SEGMENT";

    private final Path dir;
    private final Path activeFile;
    private final Path archiveDir;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ChainHasher hasher;
    private final Object lock = new Object();

    private final List<ChainEntry> activeChain = new ArrayList<>();
    private final Map<String, AuditRecord> eventIndex = new LinkedHashMap<>();

    public FileAuditLogStore(AuditProperties properties, ChainHasher hasher) {
        this.hasher = hasher;
        this.dir = Path.of(properties.getStorageDir());
        this.activeFile = dir.resolve(ACTIVE_FILE);
        this.archiveDir = dir.resolve(ARCHIVE_DIR);
        try {
            Files.createDirectories(archiveDir);
            load();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot initialize audit store at " + dir, e);
        }
    }

    // ---------------------------------------------------------------- load

    private void load() throws IOException {
        activeChain.clear();
        eventIndex.clear();
        if (Files.exists(activeFile)) {
            int lineNo = 0;
            for (String line : Files.readAllLines(activeFile, StandardCharsets.UTF_8)) {
                lineNo++;
                if (line.isBlank()) {
                    continue;
                }
                // 加载时对非法行容错：保留行由磁盘持有，完整性校验器会独立发现并定位它们；
                // 不能因为一行损坏就让存储无法打开（否则事后校验无从谈起）。
                ChainEntry e;
                try {
                    e = parseEntry(line);
                } catch (UncheckedIOException bad) {
                    org.slf4j.LoggerFactory.getLogger(FileAuditLogStore.class)
                            .warn("skipping malformed active log line {} during load: {}", lineNo, bad.getMessage());
                    continue;
                }
                activeChain.add(e);
                if (!e.isAnchor()) {
                    index((AuditRecord) e);
                }
            }
        }
        for (String seg : listArchiveFiles()) {
            readArchiveRecords(seg).ifPresent(records -> records.forEach(this::index));
        }
    }

    private void index(AuditRecord r) {
        eventIndex.putIfAbsent(r.getEventId(), r);
    }

    // -------------------------------------------------------------- append

    @Override
    public AppendOutcome appendIfAbsent(AuditRecord record) {
        synchronized (lock) {
            AuditRecord existing = eventIndex.get(record.getEventId());
            if (existing != null) {
                return new AppendOutcome(true, existing);
            }
            long seq = activeChain.isEmpty() ? 1L : activeChain.get(activeChain.size() - 1).sequence() + 1L;
            String prevHash = activeChain.isEmpty() ? ChainHasher.GENESIS_HASH
                    : activeChain.get(activeChain.size() - 1).entryHash();
            record.setSequence(seq);
            record.setStoredAt(System.currentTimeMillis());
            record.setPrevHash(prevHash);
            record.setEntryHash(hasher.hashEntry(prevHash, record));
            try {
                appendLine(activeFile, toDataMap(record));
            } catch (IOException e) {
                throw new UncheckedIOException("append failed at sequence " + seq, e);
            }
            activeChain.add(record);
            index(record);
            return new AppendOutcome(false, record);
        }
    }

    // ------------------------------------------------------------- archive

    /**
     * 归档剪切：剪切活跃段开头连续 DATA 条目中 eventTime &lt; cutoffTime 的最长前缀。
     * 锚点永远保留在活跃段。无到期记录时返回 null。
     */
    public AnchorRecord archivePrefix(long cutoffTime, String mode, boolean deleteAfterArchive) {
        synchronized (lock) {
            int firstDataIdx = 0;
            while (firstDataIdx < activeChain.size() && activeChain.get(firstDataIdx).isAnchor()) {
                firstDataIdx++;
            }
            int cutEnd = firstDataIdx;
            while (cutEnd < activeChain.size()
                    && !activeChain.get(cutEnd).isAnchor()
                    && ((AuditRecord) activeChain.get(cutEnd)).getEventTime() < cutoffTime) {
                cutEnd++;
            }
            if (cutEnd == firstDataIdx) {
                return null;
            }
            List<ChainEntry> leadingAnchors = new ArrayList<>(activeChain.subList(0, firstDataIdx));
            List<AuditRecord> cut = new ArrayList<>();
            for (ChainEntry e : activeChain.subList(firstDataIdx, cutEnd)) {
                cut.add((AuditRecord) e);
            }
            List<ChainEntry> rest = new ArrayList<>(activeChain.subList(cutEnd, activeChain.size()));

            AuditRecord first = cut.get(0);
            String startingPrevHash = first.getPrevHash();
            String segHash = hasher.segmentHash(startingPrevHash, cut);
            String segName = "segment-" + first.getSequence() + "-" + cut.get(cut.size() - 1).getSequence() + ".jsonl";

            AnchorRecord anchor = new AnchorRecord();
            anchor.setSequence(first.getSequence());
            anchor.setStoredAt(System.currentTimeMillis());
            anchor.setSegmentFile(segName);
            anchor.setFromSequence(first.getSequence());
            anchor.setToSequence(cut.get(cut.size() - 1).getSequence());
            anchor.setRecordCount(cut.size());
            anchor.setCutoffTime(cutoffTime);
            anchor.setMode(mode);
            anchor.setSegmentHash(segHash);
            anchor.setPrevHash(startingPrevHash);
            anchor.setEntryHash(segHash);

            try {
                Path segPath = archiveDir.resolve(segName);
                List<String> segLines = new ArrayList<>();
                segLines.add(writeSegmentHeader(anchor));
                for (AuditRecord r : cut) {
                    segLines.add(mapper.writeValueAsString(toDataMap(r)));
                }
                Files.write(segPath, segLines, StandardCharsets.UTF_8);
                if (deleteAfterArchive) {
                    Files.delete(segPath);
                }

                List<String> newActive = new ArrayList<>();
                for (ChainEntry a : leadingAnchors) {
                    newActive.add(mapper.writeValueAsString(toAnchorMap((AnchorRecord) a)));
                }
                newActive.add(mapper.writeValueAsString(toAnchorMap(anchor)));
                for (ChainEntry e : rest) {
                    newActive.add(mapper.writeValueAsString(e.isAnchor()
                            ? toAnchorMap((AnchorRecord) e) : toDataMap((AuditRecord) e)));
                }
                Path tmp = dir.resolve(ACTIVE_FILE + ".tmp");
                Files.write(tmp, newActive, StandardCharsets.UTF_8);
                try {
                    Files.move(tmp, activeFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(tmp, activeFile, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException e) {
                throw new UncheckedIOException("archive prefix failed", e);
            }

            loadQuietly();
            return anchor;
        }
    }

    private void loadQuietly() {
        try {
            load();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // --------------------------------------------------------------- reads

    @Override
    public Optional<AuditRecord> findByEventId(String eventId) {
        synchronized (lock) {
            return Optional.ofNullable(eventIndex.get(eventId));
        }
    }

    @Override
    public List<AuditRecord> readActiveRecords() {
        synchronized (lock) {
            List<AuditRecord> out = new ArrayList<>();
            for (ChainEntry e : activeChain) {
                if (!e.isAnchor()) {
                    out.add((AuditRecord) e);
                }
            }
            return out;
        }
    }

    @Override
    public List<ChainEntry> readActiveChain() {
        synchronized (lock) {
            return new ArrayList<>(activeChain);
        }
    }

    @Override
    public List<String> listArchiveFiles() {
        try {
            if (!Files.exists(archiveDir)) {
                return List.of();
            }
            List<String> names = new ArrayList<>();
            try (var stream = Files.list(archiveDir)) {
                stream.filter(p -> p.getFileName().toString().endsWith(".jsonl"))
                        .map(p -> p.getFileName().toString())
                        .sorted(this::compareSegmentNames)
                        .forEach(names::add);
            }
            return names;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private int compareSegmentNames(String a, String b) {
        return Long.compare(segmentFrom(a), segmentFrom(b));
    }

    private long segmentFrom(String name) {
        try {
            return Long.parseLong(name.replace("segment-", "").split("-")[0]);
        } catch (Exception e) {
            return Long.MAX_VALUE;
        }
    }

    @Override
    public Optional<List<AuditRecord>> readArchiveRecords(String segmentFile) {
        try {
            Optional<List<String>> raw = readArchiveRawLines(segmentFile);
            if (raw.isEmpty()) {
                return Optional.empty();
            }
            List<AuditRecord> records = new ArrayList<>();
            for (String line : raw.get()) {
                JsonNode node = mapper.readTree(line);
                if (TYPE_SEGMENT.equals(node.path("type").asText())) {
                    continue;
                }
                records.add(toRecord(node));
            }
            return Optional.of(records);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public List<String> readActiveRawLines() throws IOException {
        synchronized (lock) {
            return Files.exists(activeFile)
                    ? Files.readAllLines(activeFile, StandardCharsets.UTF_8) : List.of();
        }
    }

    @Override
    public Optional<List<String>> readArchiveRawLines(String segmentFile) throws IOException {
        Path p = archiveDir.resolve(segmentFile);
        return Files.exists(p)
                ? Optional.of(Files.readAllLines(p, StandardCharsets.UTF_8))
                : Optional.empty();
    }

    @Override
    public Path storageDir() {
        return dir;
    }

    // ------------------------------------------------------------- codecs

    private void appendLine(Path file, Map<String, Object> value) throws IOException {
        Files.writeString(file, mapper.writeValueAsString(value) + System.lineSeparator(),
                StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
    }

    private String writeSegmentHeader(AnchorRecord a) throws IOException {
        Map<String, Object> h = new LinkedHashMap<>();
        h.put("type", TYPE_SEGMENT);
        h.put("segmentFile", a.getSegmentFile());
        h.put("fromSequence", a.getFromSequence());
        h.put("toSequence", a.getToSequence());
        h.put("recordCount", a.getRecordCount());
        h.put("cutoffTime", a.getCutoffTime());
        h.put("mode", a.getMode());
        h.put("startPrevHash", a.getPrevHash());
        h.put("segmentHash", a.getEntryHash());
        return mapper.writeValueAsString(h);
    }

    private Map<String, Object> toDataMap(AuditRecord r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", TYPE_DATA);
        m.put("sequence", r.getSequence());
        m.put("eventId", r.getEventId());
        m.put("source", r.getSource());
        m.put("subject", r.getSubject());
        m.put("action", r.getAction());
        m.put("eventTime", r.getEventTime());
        m.put("storedAt", r.getStoredAt());
        m.put("outcome", r.getOutcome().name());
        m.put("detail", r.getDetail());
        m.put("prevHash", r.getPrevHash());
        m.put("entryHash", r.getEntryHash());
        return m;
    }

    private Map<String, Object> toAnchorMap(AnchorRecord a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", TYPE_ANCHOR);
        m.put("sequence", a.getSequence());
        m.put("storedAt", a.getStoredAt());
        m.put("segmentFile", a.getSegmentFile());
        m.put("fromSequence", a.getFromSequence());
        m.put("toSequence", a.getToSequence());
        m.put("recordCount", a.getRecordCount());
        m.put("cutoffTime", a.getCutoffTime());
        m.put("mode", a.getMode());
        m.put("segmentHash", a.getSegmentHash());
        m.put("prevHash", a.getPrevHash());
        m.put("entryHash", a.getEntryHash());
        return m;
    }

    private ChainEntry parseEntry(String line) {
        try {
            JsonNode node = mapper.readTree(line);
            return switch (node.path("type").asText(TYPE_DATA)) {
                case TYPE_ANCHOR -> toAnchor(node);
                default -> toRecord(node);
            };
        } catch (IOException e) {
            throw new UncheckedIOException("corrupt active log line: " + line, e);
        }
    }

    private AuditRecord toRecord(JsonNode n) {
        AuditRecord r = new AuditRecord();
        r.setSequence(n.path("sequence").asLong());
        r.setEventId(n.path("eventId").asText(null));
        r.setSource(n.path("source").asText(null));
        r.setSubject(n.path("subject").asText(null));
        r.setAction(n.path("action").asText(null));
        r.setEventTime(n.path("eventTime").asLong());
        r.setStoredAt(n.path("storedAt").asLong());
        r.setOutcome(EventOutcome.valueOf(n.path("outcome").asText()));
        r.setDetail(n.path("detail").isNull() ? null : n.path("detail").asText(null));
        r.setPrevHash(n.path("prevHash").asText(null));
        r.setEntryHash(n.path("entryHash").asText(null));
        return r;
    }

    private AnchorRecord toAnchor(JsonNode n) {
        AnchorRecord a = new AnchorRecord();
        a.setSequence(n.path("sequence").asLong());
        a.setStoredAt(n.path("storedAt").asLong());
        a.setSegmentFile(n.path("segmentFile").asText(null));
        a.setFromSequence(n.path("fromSequence").asLong());
        a.setToSequence(n.path("toSequence").asLong());
        a.setRecordCount(n.path("recordCount").asInt());
        a.setCutoffTime(n.path("cutoffTime").asLong());
        a.setMode(n.path("mode").asText(null));
        a.setSegmentHash(n.path("segmentHash").asText(null));
        a.setPrevHash(n.path("prevHash").asText(null));
        a.setEntryHash(n.path("entryHash").asText(null));
        return a;
    }
}
