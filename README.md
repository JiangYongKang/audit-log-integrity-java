# 本地审计日志：采集、完整性校验与保留

一套完全基于**本地文件存储**的审计流水方案。不同来源的事件被归一化为统一记录后，
以「分段 + SHA-256 哈希链 + 检查点」的方式只追加写入；支持事后完整性校验
（定位被改写/删除/插入的具体位置）、保留期归档与到期删除，以及按时间/主体/结果
的合规查询。

## 1. 记录模型

### 1.1 入库前：`AuditEvent`（来源事件）

| 字段 | 说明 |
| --- | --- |
| `eventId` | 来源侧事件唯一 ID，**重复上报的去重依据** |
| `source` | 事件来源（system-a / gateway …） |
| `subject` | 主体（账号、服务名） |
| `action` | 动作 |
| `result` | 结果，规范取值 `SUCCESS/FAILURE/DENIED/ERROR`（大小写不敏感） |
| `eventTime` | 事件时间（epoch 毫秒），允许缺失 |
| `details` | 字符串键值明细，允许缺失 |

### 1.2 归一化：四种互斥的处理结论

`EventNormalizer` 对每条事件给出**可区分**的结论，绝不静默丢弃：

- `ACCEPTED`：原样通过，入库；
- `ACCEPTED_NORMALIZED`：存在可补全的缺失字段（`eventTime` 缺失→补接收时间；
  `details` 缺失→补空 map；`result` 小写→转大写），入库且记录上保留 `normalized=true`
  与处理备注；
- `REJECTED`：不入库，附带明确的 `RejectReason`：
  `NULL_EVENT / MISSING_EVENT_ID / MISSING_SOURCE / MISSING_SUBJECT / MISSING_ACTION /
  MISSING_RESULT / UNSUPPORTED_RESULT / EVENT_TIME_INVALID / EVENT_TIME_IN_FUTURE /
  DETAILS_INVALID`。被拒绝的记录**不消耗序号**；
- `DUPLICATE`：`eventId` 已入库，返回首次入库的序号，不重复写入、不覆盖原记录。

### 1.3 入库后：`AuditRecord`（链上记录，JSONL 一行一条）

```
sequence | segment | eventId | source | subject | action | result
eventTime | ingestTime | normalized | details | prevHash | recordHash
```

- `sequence`：全局连续序号，从 0 开始；
- `prevHash`：前一条记录的 `recordHash`，第一条为 64 个 0 的创世哈希；
- `recordHash`：本条内容的链哈希（见下节）。

## 2. 完整性校验语义

### 2.1 哈希链

```
canonical(record) = 字段序列（不含哈希）以 SOH(0x01) 分隔，
                    details 取键排序后的 JSON
recordHash        = sha256_hex( canonical(record) || "\n" || prevHash )
```

任何一个字节被改写都会改变该条的 `recordHash`，并使其后所有记录的链关系失效。

### 2.2 分段存储布局

```
<store-dir>/
├── segments/<name>.log   # ACTIVE 段，JSONL 只追加
├── archive/<name>.log    # ARCHIVED 段（保留期归档）
└── checkpoint.json       # 检查点：每段边界 + 全局序号 + 链尾
```

段容量由 `audit.segment.max-records` 控制，写满后滚动新段。段名内嵌起始序号，
天然按时间/序号排序。

### 2.3 校验器核对的内容

`IntegrityVerifier.verify()` 对每个现存段逐条重算并核对：

1. **记录改写** → `HASH_MISMATCH`（重算哈希 ≠ 记录内 `recordHash`），
   问题定位到 `segment + sequence`；
2. **删除记录** → 删除点出现 `SEQUENCE_GAP`，同时其后一条 `CHAIN_BROKEN`
   （`prevHash` 对不上前向哈希）；
3. **中途插入** → 插入点出现 `SEQUENCE_DUPLICATE` 或 `SEQUENCE_GAP`，并伴随
   `CHAIN_BROKEN`；
4. **跨段插入/搬运** → 每段首记录的 `prevHash`（段的 `entryHash`）必须等于
   上一段的 `tailHash`，不符为 `CROSS_SEGMENT_CHAIN_BROKEN`；
   记录声称的 `segment` 与其所在文件不符也会报 `CHAIN_BROKEN`；
5. **整文件替换** → 段文件 SHA-256 与检查点中的 `fileChecksum` 不符，
   报 `CHECKPOINT_MISMATCH`；段文件无故缺失报 `SEGMENT_FILE_MISSING`，
   无法解析报 `SEGMENT_FILE_UNREADABLE`；
6. 检查点还登记每段的首末序号、记录数、事件时间范围，全部参与比对。

校验报告 `VerificationReport` 包含：`intact`、实际重算的记录数、现存首末序号、
被到期删除的段数，以及按段/序号排好序的问题列表。**校验结论与写入顺序一致**：
并发下所有状态变更（序号分配、哈希计算、段滚动、检查点落盘）在同一把锁内原子完成。

### 2.4 崩溃恢复

启动时从 `checkpoint.json` 重建内存状态，并对最后一段做一次「文件 vs 检查点」对账：
若进程崩溃在「记录落盘后、检查点落盘前」的窗口，尾部已完整落盘的记录会被重放回链；
随后重建 eventId 去重索引。检查点以临时文件 + 原子 move 落盘。

## 3. 保留规则

- 判定粒度是**整段**：段的 `maxEventTime < now − retentionDays × 1 天` 才到期，
  切点上的段（等于 cutoff）保留；只清理**最老的连续到期段**，缺口永远只在时间线头部。
- 两种模式（`audit.retention.mode`）：
  - `ARCHIVE`（默认）：段文件移入 `archive/`，状态 `ARCHIVED`，**仍可查询、仍可完整
    校验**；
  - `DELETE`：删除段数据文件，状态置 `DELETED`，检查点保留该段的
    `firstSequence/lastSequence/entryHash/tailHash`。之后完整性校验把该位置视为
    **有登记、可解释的边界**（报告中 `deletedSegments > 0` 但 `intact=true`），
    而不是篡改；如果有人在该边界之后伪造插入，`CROSS_SEGMENT_CHAIN_BROKEN`
    依然会报出。
- 到期删除同时释放该段的 eventId 去重索引：数据既已按保留期合法删除，同一 eventId
  的重新上报按新记录处理。
- 定时任务默认开启（`audit.retention.sweep-enabled`），延迟由
  `audit.retention.sweep-fixed-delay-ms` 控制；也可通过 HTTP 手动触发。

## 4. HTTP 接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/audit/events` | 采集一条事件，返回 `outcome/rejectReason/sequence/segment` |
| GET  | `/audit/records?from=&to=&subject=&result=` | 按事件时间区间/主体/结果查询 |
| POST | `/audit/verify` | 全量完整性校验，返回报告 |
| POST | `/audit/retention/sweep` | 立即执行一次保留期清理 |

## 5. 本地验证方法

需要 JDK 21+、Maven 3.9+（离线仓库可用时加 `-o`）。

```bash
# 全量测试：并发追加 / 篡改检测 / 到期清理 / 恢复 / HTTP 端到端
mvn test

# 只跑某一组
mvn test -Dtest=ConcurrentAppendTest      # 8 线程 x 50 条，序号 0..399 不重不漏
mvn test -Dtest=TamperDetectionTest       # 改写/删除/插入/整段缺失的定位
mvn test -Dtest=RetentionCleanupTest      # ARCHIVE/DELETE、切点边界、清理后追加
mvn test -Dtest=EventNormalizerTest       # 各拒绝原因与归一化分支

# 启动服务（端口随机；可在 application.properties 固定）
mvn spring-boot:run
curl -s -X POST localhost:8080/audit/events -H 'Content-Type: application/json' \
  -d '{"eventId":"e1","source":"gw","subject":"alice","action":"login",
       "result":"success","eventTime":1700000000000,"details":{"ip":"127.0.0.1"}}'
curl -s -X POST localhost:8080/audit/verify | jq .
curl -s 'localhost:8080/audit/records?subject=alice' | jq .
```

可配置项（`src/main/resources/application.properties`）：

```properties
audit.storage.dir=${java.io.tmpdir}/audit-log-store
audit.segment.max-records=1000
audit.retention.days=90
audit.retention.mode=ARCHIVE          # 或 DELETE
audit.retention.sweep-enabled=true
audit.retention.sweep-fixed-delay-ms=3600000
```

测试日志会打印每个用例的输入、判定依据（拒绝原因/归一化备注）、校验问题明细，
便于区分不同失败原因。

## 6. 本地信任边界（已知约束）

- 检查点与段文件同在本机，方案能检测**任何对数据文件的事后改写、删除、插入**，
  但对「同时合法重写检查点与数据文件」的本机全权攻击者不提供密码学防护
  （如需可在此基础上对检查点追加离线签名）。
- 并发安全边界为单 JVM 实例（进程内加锁）；多机共享目录不在本方案范围内。
