package com.github.highcumontoa.auditlogintegrityjava.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.highcumontoa.auditlogintegrityjava.config.AuditProperties;
import com.github.highcumontoa.auditlogintegrityjava.core.HashChain;
import com.github.highcumontoa.auditlogintegrityjava.model.AuditRecord;
import com.github.highcumontoa.auditlogintegrityjava.model.SegmentMeta;
import com.github.highcumontoa.auditlogintegrityjava.model.SegmentState;
import com.github.highcumontoa.auditlogintegrityjava.model.ValidatedEvent;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 基于本地文件的分段追加存储。
 * <pre>
 *   root/segments/&lt;name&gt;.log   ACTIVE 段文件（JSONL，每行一条记录）
 *   root/archive/&lt;name&gt;.log    ARCHIVED 段文件
 *   root/checkpoint.json       段边界、序号、哈希链检查点
 * </pre>
 * 所有改变状态的方法都 synchronized：并发追加时序号分配、链哈希计算、
 * 段滚动与检查点落盘是一个原子步骤，因此序号连续、不重不漏，
 * 且记录的链顺序与写入顺序一致。
 */
@Component
public class LocalSegmentStore {

    private final Path root;
    private final Path segmentsDir;
    private final Path archiveDir;
    private final Path checkpointFile;
    private final int maxRecordsPerSegment;
    private final ObjectMapper mapper = new ObjectMapper();

    /** 内存中的检查点镜像；list 的顺序即段时间顺序。 */
    private final List<SegmentMeta> segments = new ArrayList<>();
    /** 下一条记录应分配的全局序号。 */
    private long nextSequence = 0L;
    /** 现存最后一条记录的 recordHash，新段/新记录的 prevHash 取它。 */
    private String tailHash = HashChain.GENESIS_HASH;
    /** eventId -> 首次入库序号，用于重复上报拒绝重复入库。 */
    private final Map<String, Long> eventIdIndex = new LinkedHashMap<>();

    public LocalSegmentStore(AuditProperties properties) {
        this.root = Path.of(properties.getStorage().getDir());
        this.segmentsDir = root.resolve("segments");
        this.archiveDir = root.resolve("archive");
        this.checkpointFile = root.resolve("checkpoint.json");
        this.maxRecordsPerSegment = Math.max(1, properties.getSegment().getMaxRecords());
        try {
            Files.createDirectories(segmentsDir);
            Files.createDirectories(archiveDir);
            recover();
        } catch (IOException e) {
            throw new UncheckedIOException("failed to initialize local segment store at " + root, e);
        }
    }

    // ---------------------------------------------------------------- append

    /**
     * 加锁追加：去重判定 → 序号分配 → 链哈希 → 必要时滚动新段 → 追加落盘 → 检查点落盘。
     */
    public synchronized StoreAppendResult append(ValidatedEvent event) {
        Objects.requireNonNull(event, "event");
        Long existing = eventIdIndex.get(event.eventId());
        if (existing != null) {
            return StoreAppendResult.duplicate(existing);
        }

        long seq = nextSequence;
        String segmentName = currentOrNextSegmentName();
        String prevHash = tailHash;

        AuditRecord record = new AuditRecord(
                seq, segmentName, event.eventId(), event.source(), event.subject(),
                event.action(), event.result(), event.eventTime(), event.ingestTime(),
                event.normalized(), event.details(), prevHash, null);
        String recordHash = HashChain.computeRecordHash(HashChain.canonicalContent(record), prevHash);
        AuditRecord hashed = withHash(record, recordHash);

        try {
            Path file = segmentsDir.resolve(segmentName + ".log");
            Files.writeString(file, mapper.writeValueAsString(hashed) + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);

            applyAppended(hashed);
            persistCheckpoint();
        } catch (IOException e) {
            throw new UncheckedIOException("failed to append record " + seq, e);
        }
        return StoreAppendResult.appended(hashed);
    }

    private static AuditRecord withHash(AuditRecord r, String recordHash) {
        return new AuditRecord(r.sequence(), r.segment(), r.eventId(), r.source(), r.subject(),
                r.action(), r.result(), r.eventTime(), r.ingestTime(), r.normalized(),
                r.details(), r.prevHash(), recordHash);
    }

    /** 把已落盘的记录推进到内存状态。 */
    private void applyAppended(AuditRecord r) {
        eventIdIndex.put(r.eventId(), r.sequence());
        nextSequence = r.sequence() + 1;
        tailHash = r.recordHash();

        int idx = segments.isEmpty() ? -1 : segments.size() - 1;
        if (segments.isEmpty() || !segments.get(idx).name().equals(r.segment())) {
            segments.add(new SegmentMeta(r.segment(), SegmentState.ACTIVE,
                    r.sequence(), r.sequence(), 1L,
                    r.eventTime(), r.eventTime(), r.prevHash(), r.recordHash(), null));
        } else {
            segments.set(idx, extendMeta(segments.get(idx), r));
        }
    }

    private SegmentMeta extendMeta(SegmentMeta m, AuditRecord r) {
        return new SegmentMeta(m.name(), m.state(),
                m.firstSequence(), r.sequence(), m.count() + 1,
                Math.min(m.minEventTime(), r.eventTime()),
                Math.max(m.maxEventTime(), r.eventTime()),
                m.entryHash(), r.recordHash(), m.fileChecksum());
    }

    /** 当前 ACTIVE 段未满则复用，否则滚动出下一段。 */
    private String currentOrNextSegmentName() {
        if (!segments.isEmpty()) {
            SegmentMeta last = segments.get(segments.size() - 1);
            if (last.state() == SegmentState.ACTIVE && last.count() < maxRecordsPerSegment) {
                return last.name();
            }
        }
        return newSegmentName();
    }

    private String newSegmentName() {
        return "seg-" + String.format(Locale.ROOT, "%019d-%03d", nextSequence, segments.size());
    }

    // ------------------------------------------------------------ lifecycle

    /** 把段标记为 ARCHIVED 并把数据文件从 segments/ 移到 archive/。 */
    public synchronized void archiveSegment(String name) {
        int idx = findSegmentIndex(name);
        SegmentMeta meta = segments.get(idx);
        if (meta.state() == SegmentState.DELETED) {
            throw new IllegalStateException("segment already deleted: " + name);
        }
        try {
            if (meta.state() == SegmentState.ACTIVE) {
                Files.move(segmentsDir.resolve(name + ".log"), archiveDir.resolve(name + ".log"),
                        StandardCopyOption.REPLACE_EXISTING);
            }
            segments.set(idx, withState(meta, SegmentState.ARCHIVED));
            persistCheckpoint();
        } catch (IOException e) {
            throw new UncheckedIOException("failed to archive segment " + name, e);
        }
    }

    /** 删除段数据文件，只在检查点保留边界信息（entryHash/tailHash/序号范围）。 */
    public synchronized void deleteSegment(String name) {
        int idx = findSegmentIndex(name);
        SegmentMeta meta = segments.get(idx);
        try {
            // 删除前读出该段的 eventId，把它们从去重索引移除：
            // 数据既已按保留期删除，同一 eventId 的重新上报应被视为新记录。
            if (meta.state() != SegmentState.DELETED) {
                Path existing = segmentFile(meta.state(), name);
                if (Files.exists(existing)) {
                    try (BufferedReader reader =
                                 Files.newBufferedReader(existing, StandardCharsets.UTF_8)) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            if (!line.isBlank()) {
                                eventIdIndex.remove(deserialize(line).eventId());
                            }
                        }
                    }
                }
            }
            Files.deleteIfExists(segmentsDir.resolve(name + ".log"));
            Files.deleteIfExists(archiveDir.resolve(name + ".log"));
            segments.set(idx, withState(meta, SegmentState.DELETED));
            persistCheckpoint();
        } catch (IOException e) {
            throw new UncheckedIOException("failed to delete segment " + name, e);
        }
    }

    // -------------------------------------------------------------- reads

    public synchronized List<SegmentMeta> listSegmentsInOrder() {
        return segments.stream().map(this::copyMeta).collect(Collectors.toList());
    }

    /** 读取段文件的原始 JSONL 行；DELETED 段无数据文件，抛出异常由校验器解释为缺口。 */
    public List<String> readSegmentRawLines(SegmentMeta meta) {
        Path file = segmentFile(meta.state(), meta.name());
        if (!Files.exists(file)) {
            throw new MissingSegmentFileException(meta.name(), file);
        }
        try {
            return Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read segment " + meta.name(), e);
        }
    }

    /** 顺序读取所有 ACTIVE/ARCHIVED 段中的记录；DELETED 段无数据，自然跳过。 */
    public List<AuditRecord> readAllRecords() {
        List<AuditRecord> all = new ArrayList<>();
        synchronized (this) {
            for (SegmentMeta meta : segments) {
                if (meta.state() == SegmentState.DELETED) {
                    continue;
                }
                for (String line : readSegmentRawLines(meta)) {
                    if (!line.isBlank()) {
                        all.add(deserialize(line));
                    }
                }
            }
        }
        return all;
    }

    public Path segmentFile(SegmentState state, String name) {
        Path dir = state == SegmentState.ARCHIVED ? archiveDir : segmentsDir;
        return dir.resolve(name + ".log");
    }

    // ------------------------------------------------------------- recovery

    /**
     * 启动恢复：优先以检查点重建内存状态，再用现存段文件校验最后一段是否完整
     * （进程可能在“追加落盘后、检查点落盘前”崩溃）。
     */
    private void recover() throws IOException {
        if (!Files.exists(checkpointFile)) {
            bootstrapFromFiles();
            return;
        }
        Checkpoint cp = mapper.readValue(checkpointFile.toFile(), Checkpoint.class);
        this.nextSequence = cp.nextSequence;
        this.tailHash = cp.tailHash == null ? HashChain.GENESIS_HASH : cp.tailHash;
        this.segments.clear();
        this.segments.addAll(cp.segments);

        reconcileLastSegment();
        rebuildEventIdIndex();

        if (!checkpointMatchesInMemory(cp)) {
            persistCheckpoint();
        }
    }

    /** 无检查点时（全新或仅有裸文件）按文件名顺序扫描恢复。 */
    private void bootstrapFromFiles() throws IOException {
        segments.clear();
        nextSequence = 0;
        tailHash = HashChain.GENESIS_HASH;
        List<Path> files = new ArrayList<>();
        if (Files.isDirectory(segmentsDir)) {
            try (var stream = Files.list(segmentsDir)) {
                stream.filter(p -> p.getFileName().toString().endsWith(".log")).forEach(files::add);
            }
        }
        files.sort(Path::compareTo);
        for (Path file : files) {
            String name = fileNameWithoutExt(file);
            ingestSegmentFile(file, name, SegmentState.ACTIVE);
        }
        if (!segments.isEmpty()) {
            persistCheckpoint();
        }
    }

    private void ingestSegmentFile(Path file, String name, SegmentState state) throws IOException {
        long first = -1;
        long last = -1;
        long count = 0;
        long minTime = Long.MAX_VALUE;
        long maxTime = Long.MIN_VALUE;
        String entry = HashChain.GENESIS_HASH;
        String tail = HashChain.GENESIS_HASH;
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                AuditRecord r = deserialize(line);
                if (first < 0) {
                    first = r.sequence();
                    entry = r.prevHash();
                }
                last = r.sequence();
                count++;
                minTime = Math.min(minTime, r.eventTime());
                maxTime = Math.max(maxTime, r.eventTime());
                tail = r.recordHash();
                eventIdIndex.put(r.eventId(), r.sequence());
                nextSequence = Math.max(nextSequence, r.sequence() + 1);
            }
        }
        if (count > 0) {
            segments.add(new SegmentMeta(name, state, first, last, count,
                    minTime, maxTime, entry, tail, checksumOf(file)));
            this.tailHash = tail;
        }
    }

    /**
     * 处理“最后追加已落盘但检查点未更新”的崩溃窗口：
     * 重放最后一个 ACTIVE 段，把尾部多出的记录补进内存状态。
     */
    private void reconcileLastSegment() throws IOException {
        int lastIdx = segments.size() - 1;
        while (lastIdx >= 0 && segments.get(lastIdx).state() == SegmentState.DELETED) {
            lastIdx--;
        }
        if (lastIdx < 0) {
            return;
        }
        SegmentMeta meta = segments.get(lastIdx);
        Path file = segmentFile(meta.state(), meta.name());
        if (!Files.exists(file)) {
            return;
        }
        List<AuditRecord> records = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isBlank()) {
                    records.add(deserialize(line));
                }
            }
        }
        if (records.isEmpty()) {
            return;
        }
        AuditRecord lastInFile = records.get(records.size() - 1);
        if (lastInFile.sequence() == meta.lastSequence()
                && lastInFile.recordHash().equals(meta.tailHash())) {
            return; // 一致
        }
        // 文件比检查点多（崩溃在检查点落盘前）：以文件为准补放
        SegmentMeta rebuilt = rebuildMetaFromRecords(meta.name(), meta.state(), records);
        segments.set(lastIdx, rebuilt);
        for (AuditRecord r : records) {
            eventIdIndex.putIfAbsent(r.eventId(), r.sequence());
            if (r.sequence() >= nextSequence) {
                nextSequence = r.sequence() + 1;
            }
        }
        tailHash = rebuilt.tailHash();
    }

    private SegmentMeta rebuildMetaFromRecords(String name, SegmentState state,
                                               List<AuditRecord> records) {
        AuditRecord first = records.get(0);
        AuditRecord last = records.get(records.size() - 1);
        long min = Long.MAX_VALUE;
        long max = Long.MIN_VALUE;
        for (AuditRecord r : records) {
            min = Math.min(min, r.eventTime());
            max = Math.max(max, r.eventTime());
        }
        return new SegmentMeta(name, state, first.sequence(), last.sequence(), records.size(),
                min, max, first.prevHash(), last.recordHash(),
                checksumOf(segmentFile(state, name)));
    }

    private void rebuildEventIdIndex() {
        if (!eventIdIndex.isEmpty()) {
            return;
        }
        for (SegmentMeta meta : segments) {
            if (meta.state() == SegmentState.DELETED) {
                continue;
            }
            Path file = segmentFile(meta.state(), meta.name());
            if (!Files.exists(file)) {
                continue;
            }
            try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.isBlank()) {
                        AuditRecord r = deserialize(line);
                        eventIdIndex.put(r.eventId(), r.sequence());
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException("failed to rebuild index over " + nameOf(meta), e);
            }
        }
    }

    private boolean checkpointMatchesInMemory(Checkpoint cp) {
        return cp.nextSequence == nextSequence
                && Objects.equals(cp.tailHash, tailHash)
                && cp.segments.equals(segments);
    }

    // ----------------------------------------------------------- checkpoint

    private synchronized void persistCheckpoint() {
        try {
            List<SegmentMeta> withChecksums = new ArrayList<>(segments.size());
            for (SegmentMeta m : segments) {
                String checksum = m.fileChecksum();
                if (m.state() != SegmentState.DELETED) {
                    Path f = segmentFile(m.state(), m.name());
                    if (Files.exists(f)) {
                        checksum = checksumOf(f);
                    }
                }
                withChecksums.add(withChecksum(m, checksum));
            }
            Checkpoint cp = new Checkpoint(1, nextSequence, tailHash, withChecksums,
                    HashChain.sha256Hex(("audit-checkpoint-v1|" + nextSequence
                            + "|" + tailHash + "|" + withChecksums).getBytes(StandardCharsets.UTF_8)));
            // 重算出的 checksum 同时回写内存镜像，保证“追加后立即校验”看到的边界是最新的
            this.segments.clear();
            this.segments.addAll(withChecksums);
            Path tmp = checkpointFile.resolveSibling("checkpoint.json.tmp");
            mapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), cp);
            Files.move(tmp, checkpointFile, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to persist checkpoint", e);
        }
    }

    // -------------------------------------------------------------- helpers

    private int findSegmentIndex(String name) {
        for (int i = 0; i < segments.size(); i++) {
            if (segments.get(i).name().equals(name)) {
                return i;
            }
        }
        throw new IllegalArgumentException("unknown segment: " + name);
    }

    private static SegmentMeta withState(SegmentMeta m, SegmentState state) {
        return new SegmentMeta(m.name(), state, m.firstSequence(), m.lastSequence(), m.count(),
                m.minEventTime(), m.maxEventTime(), m.entryHash(), m.tailHash(), m.fileChecksum());
    }

    private static SegmentMeta withChecksum(SegmentMeta m, String checksum) {
        return new SegmentMeta(m.name(), m.state(), m.firstSequence(), m.lastSequence(), m.count(),
                m.minEventTime(), m.maxEventTime(), m.entryHash(), m.tailHash(), checksum);
    }

    private SegmentMeta copyMeta(SegmentMeta m) {
        return new SegmentMeta(m.name(), m.state(), m.firstSequence(), m.lastSequence(), m.count(),
                m.minEventTime(), m.maxEventTime(), m.entryHash(), m.tailHash(), m.fileChecksum());
    }

    private static String nameOf(SegmentMeta m) {
        return m.name();
    }

    private static String fileNameWithoutExt(Path p) {
        String n = p.getFileName().toString();
        int dot = n.lastIndexOf('.');
        return dot < 0 ? n : n.substring(0, dot);
    }

    private String checksumOf(Path file) {
        try {
            return HashChain.sha256Hex(Files.readAllBytes(file));
        } catch (IOException e) {
            throw new UncheckedIOException("checksum failed for " + file, e);
        }
    }

    @SuppressWarnings("unchecked")
    private AuditRecord deserialize(String line) {
        try {
            Map<String, Object> m = mapper.readValue(line, Map.class);
            Map<String, String> details = new LinkedHashMap<>();
            Object d = m.get("details");
            if (d instanceof Map<?, ?> dm) {
                dm.forEach((k, v) -> details.put(String.valueOf(k), v == null ? null : String.valueOf(v)));
            }
            return new AuditRecord(
                    ((Number) m.get("sequence")).longValue(),
                    (String) m.get("segment"),
                    (String) m.get("eventId"),
                    (String) m.get("source"),
                    (String) m.get("subject"),
                    (String) m.get("action"),
                    (String) m.get("result"),
                    ((Number) m.get("eventTime")).longValue(),
                    ((Number) m.get("ingestTime")).longValue(),
                    Boolean.TRUE.equals(m.get("normalized")),
                    details,
                    (String) m.get("prevHash"),
                    (String) m.get("recordHash"));
        } catch (Exception e) {
            throw new UncheckedIOException(new IOException("corrupt record line: " + line, e));
        }
    }

    /** 检查点 JSON 结构（Jackson 直接映射字段名）。 */
    public static final class Checkpoint {
        public int version;
        public long nextSequence;
        public String tailHash;
        public List<SegmentMeta> segments = new ArrayList<>();
        public String digest;

        public Checkpoint() {
        }

        public Checkpoint(int version, long nextSequence, String tailHash,
                          List<SegmentMeta> segments, String digest) {
            this.version = version;
            this.nextSequence = nextSequence;
            this.tailHash = tailHash;
            this.segments = segments;
            this.digest = digest;
        }
    }

    /** 段文件缺失（对 DELETED 段属预期），供校验器区分。 */
    public static class MissingSegmentFileException extends UncheckedIOException {
        private final String segmentName;

        public MissingSegmentFileException(String segmentName, Path file) {
            super("segment file missing: " + segmentName + " at " + file, (IOException) null);
            this.segmentName = segmentName;
        }

        public String segmentName() {
            return segmentName;
        }
    }
}
