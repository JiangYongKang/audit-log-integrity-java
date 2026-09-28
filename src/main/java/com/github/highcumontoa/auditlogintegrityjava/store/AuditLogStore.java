package com.github.highcumontoa.auditlogintegrityjava.store;

import com.github.highcumontoa.auditlogintegrityjava.model.AuditRecord;
import com.github.highcumontoa.auditlogintegrityjava.model.ChainEntry;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * 审计流水本地存储抽象。
 * 实现负责：并发安全的顺序追加、编号连续、哈希链维护、归档剪切与回放读取。
 */
public interface AuditLogStore {

    /**
     * 幂等条件追加：eventId 不存在时把记录接到链尾（分配序号、维护哈希），
     * 返回 {@link AppendOutcome}；已存在时返回首条记录且不重复入库。
     * 整个检查-追加过程互斥，保证并发下顺序与编号连续、不重不漏。
     */
    AppendOutcome appendIfAbsent(AuditRecord record);

    /** 按 eventId 查首条记录（内存视图，含已知归档段）。 */
    Optional<AuditRecord> findByEventId(String eventId);

    /** 活跃段中的全部数据记录（按编号顺序，不含锚点）。 */
    List<AuditRecord> readActiveRecords();

    /** 活跃段全部链条目（含锚点，按编号顺序）。 */
    List<ChainEntry> readActiveChain();

    /** 归档段文件名列表（按被剪切顺序）。 */
    List<String> listArchiveFiles();

    /** 读取某个归档段内的数据记录；段已物理删除（DELETE 模式）时返回空 Optional。 */
    Optional<List<AuditRecord>> readArchiveRecords(String segmentFile);

    /** 从磁盘读取活跃文件原始文本行（校验器据此检测外部篡改）。 */
    List<String> readActiveRawLines() throws IOException;

    /** 从磁盘读取归档段原始文本行；段不存在时返回空。 */
    Optional<List<String>> readArchiveRawLines(String segmentFile) throws IOException;

    /** 存储根目录。 */
    Path storageDir();

    /** 追加结论。 */
    record AppendOutcome(boolean duplicate, AuditRecord stored) {
    }
}
