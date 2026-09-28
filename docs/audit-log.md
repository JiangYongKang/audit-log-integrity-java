# 本地审计日志：采集、完整性校验与保留

本模块在本地文件上承载审计流水，提供「多来源归一化采集 → 哈希链顺序追加 →
事后完整性校验 → 保留期归档/删除 → 合规查询」的完整闭环。
所有结论都显式可区分：接受 / 拒绝（含原因）/ 重复；完整 / 有缺陷（含定位）/
段已按策略删除（可解释的不可复核）。

## 1. 记录模型

### 1.1 归一化记录 `AuditRecord`（链上 DATA 条目）

| 字段 | 含义 | 说明 |
|---|---|---|
| `sequence` | 全局连续序号 | 从 1 开始，由存储在互斥区内分配 |
| `eventId` | 来源方事件标识 | 幂等键，重复上报只入库一次 |
| `source` | 事件来源系统 | 必填 |
| `subject` | 行为主体 | 必填（用户/服务账号等） |
| `action` | 行为类型 | 必填 |
| `eventTime` | 事件发生时间（epoch 毫秒） | 必填，必须 > 0 |
| `storedAt` | 入库时间（epoch 毫秒） | 存储分配，参与摘要 |
| `outcome` | `SUCCESS` / `FAILURE` | 缺省按 SUCCESS；无法映射则拒绝 |
| `detail` | 附加明细 | 可空 |
| `prevHash` | 上一条目摘要 | 首条为常量 `GENESIS` |
| `entryHash` | 本条目摘要 | 见第 2 节 |

### 1.2 归一化规则（`EventNormalizer`）

不同来源使用不同字段名，归一化器按下划线/连字符无关的别名映射：

- eventId：`eventId` / `id` / `event_id`
- source：`source` / `sourceSystem` / `source_system` / `origin`
- subject：`subject` / `principal` / `user` / `subject_id`
- action：`action` / `operation` / `op` / `act`
- eventTime：`eventTime` / `event_time` / `timestamp` / `occurredAt`
- outcome：`outcome` / `result` / `status`

**任何非法输入都不会被静默丢弃**，而是返回 `REJECTED` 与互斥的拒绝原因：

| RejectionReason | 触发条件 |
|---|---|
| `MISSING_EVENT_ID` / `MISSING_SOURCE` / `MISSING_SUBJECT` / `MISSING_ACTION` | 必填字段为空 |
| `MISSING_EVENT_TIME` | 缺事件时间 |
| `INVALID_EVENT_TIME` | 时间值 ≤ 0 或非数字 |
| `UNSUPPORTED_OUTCOME` | outcome 无法映射到 SUCCESS/FAILURE（兼容 ok/failed/denied 等写法） |

同 `eventId` 的重复上报返回 `DUPLICATE`，并回传**首次**入库记录的序号与哈希，
绝不产生第二条流水。三种状态 `ACCEPTED / REJECTED / DUPLICATE` 互斥可区分。

### 1.3 归档锚点 `AnchorRecord`（链上 ANCHOR 条目）

保留期清理会把一段数据剪切出活跃段，并在原位置留下锚点：

`sequence`（等于被剪切段首条数据的序号）、`segmentFile`、`fromSequence`、
`toSequence`、`recordCount`、`cutoffTime`、`mode`（ARCHIVE/DELETE）、
`prevHash`（剪切点之前的链摘要）、`entryHash`（被剪切段的折叠摘要）。

### 1.4 磁盘布局（JSON Lines，UTF-8）

```
<audit.storage-dir>/
├── audit.log                     # 活跃段：每行一个 DATA 或 ANCHOR
└── archives/
    └── segment-<from>-<to>.jsonl # 归档段：第 1 行 SEGMENT 头，其后 DATA 行
```

## 2. 完整性校验语义

### 2.1 哈希链

- 规范化内容：按固定字段顺序把 8 个业务字段（eventId, source, subject, action,
  eventTime, storedAt, outcome, detail）序列化为 JSON。字段顺序固定，与存储行
  的文本格式无关，因此**任意字段被改写**都会使重算摘要不一致。
- 条目摘要：`SHA-256( prevHash + NUL + canonicalJson )`，十六进制。
- 段折叠摘要：从段起点的 `prevHash` 开始逐条折叠，结果等于段末条目的摘要。
  锚点的 `entryHash` 就是这个值，用一个摘要代表整段被剪切的记录。

### 2.2 清理后的链接结构

活跃文件中的逻辑链按存储顺序为：

```
[ANCHOR(第1段)] DATA.. [ANCHOR(第2段)] DATA.. ...
```

- 锚点的 `prevHash` = 被剪切段之前的链摘要；
- 锚点 `entryHash` = 被剪切段的折叠摘要；
- 锚点之后第一条 DATA 的 `prevHash` 又回指锚点摘要。

因此即使中间的记录被剪切归档，链依然首尾相接，校验结论与写入顺序一致。

### 2.3 缺陷类型与定位（`DefectType`）

校验器直接读磁盘原始文本行并自行解析（不经过存储的内存视图，损坏文件也能打开）：

| 缺陷 | 判定依据（可区分不同失败原因） |
|---|---|
| `HASH_MISMATCH` | 重算条目摘要 ≠ 存储的 entryHash → **内容被改写**，position=序号 |
| `SEQUENCE_GAP` | 序号大于期望值 → **记录被删除**，position=缺失起点序号 |
| `SEQUENCE_DUPLICATE` | 序号小于期望值 → **中途插入**占用了已有编号 |
| `PREV_HASH_BROKEN` | prevHash 与上一条目摘要不符 → 插入/重排/删除，position=当前序号 |
| `MALFORMED_LINE` | 某物理行不是合法 JSON → 行级损坏，position=物理行号 |
| `ARCHIVE_SEGMENT_MISSING` | ARCHIVE 模式的锚点引用的段文件不存在（非预期丢失） |
| `ARCHIVE_SEGMENT_HASH_MISMATCH` | 归档段重算折叠 ≠ 锚点声明摘要 → 段内被篡改 |
| `MISSING_RECORD` | 锚点声明的范围/条数与段实际内容不一致 |

每个段给出三态结论：`VERIFIED` / `FAILED` / `DELETED`。

### 2.4 安全边界（重要）

链使用无密钥 SHA-256，能可靠发现**外部直接改写文件**（改字段、删行、插入未重算
哈希的行、破坏 JSON）。它不防御拥有写权限且愿意**重算全部哈希并伪造整条链**的
攻击者——那需要独立锚点（HMAC 密钥保管在进程外、或定期把 headHash 外发）。
需要时可在 `ChainHasher` 中把 SHA-256 替换为 HMAC-SHA256，校验语义不变。

## 3. 保留规则

- 触发：`POST /audit/retention?now=<ms>&retentionMillis=<ms>&mode=ARCHIVE|DELETE`，
  或开启 `audit.retention-schedule-enabled=true` 定时执行。
- 判定：**只剪切活跃段开头连续的、`eventTime < now - retentionMillis` 的 DATA
  前缀**；时间未到期的记录即使排在后面也绝不动，锚点永久保留在活跃段。
- ARCHIVE 模式：剪切为 `archives/segment-<from>-<to>.jsonl`（含 SEGMENT 头），
  并在活跃段写入锚点。
- DELETE 模式：段文件落盘后物理删除，仅保留锚点（范围、条数、折叠摘要）。
- 剪切通过「临时文件 + 原子替换」重写活跃段；全程与追加共用同一把内存锁。
- 清理后再追加的新记录紧接锚点编号，无重号、无缺口（见
  `RepeatedRetentionTest` 的多轮混合 ARCHIVE/DELETE 场景）。

清理后的行为是**可预期**的：

- 校验：ARCHIVE 段被重新折叠交叉验证；DELETE 段标记为 `DELETED`
  （可解释的不可复核，不是篡改缺陷）；任何真正的篡改仍报 FAILED。
- 查询：`includeArchived=true` 时按活跃链中的锚点清单枚举归档区间；
  已删除的段出现在 `unavailableSegments` 中显式披露，而不是假装不存在。

## 4. HTTP 接口

| 方法与路径 | 作用 |
|---|---|
| `POST /audit/events` | 上报事件（JSON），返回 ACCEPTED/REJECTED/DUPLICATE |
| `GET /audit/verify` | 全链完整性校验，返回 intact、head 位置、各段状态与缺陷列表 |
| `POST /audit/retention` | 手动执行保留期清理 |
| `POST /audit/query` | 按 fromTime/toTime/subject/source/action/outcome 过滤，includeArchived 控制是否扫描归档段，limit 限制条数 |

## 5. 本地验证方法

```bash
# 运行全部测试（离线）：17 个用例，覆盖归一化拒绝原因、并发追加、
# 篡改四类手法、归档/删除边界、多轮清理与 HTTP 端到端
mvn test -o

# 只跑关键场景
mvn test -o -Dtest=ConcurrentAppendTest       # 12 线程 x100 并发追加 + 重启校验
mvn test -o -Dtest=TamperDetectionTest        # 改写/删除/插入/非法行定位
mvn test -o -Dtest=RetentionAndQueryTest      # 归档/删除边界与跨段查询
mvn test -o -Dtest=RepeatedRetentionTest      # 多轮 ARCHIVE/DELETE 混合清理
```

测试用例均在日志中打印**输入与判定依据**（eventId、来源、拒绝原因、缺陷类型与
位置、扫描段与不可得段），失败原因可直接从日志区分。

手工体验：

```bash
mvn spring-boot:run -o
curl -s -XPOST localhost:8080/audit/events -H 'Content-Type: application/json' \
  -d '{"eventId":"e1","source":"crm","subject":"alice","action":"login","eventTime":1700000000000,"outcome":"success"}'
curl -s 'localhost:8080/audit/verify'
curl -s -XPOST localhost:8080/audit/query -H 'Content-Type: application/json' -d '{"subject":"alice"}'
```

## 6. 配置项（application.properties）

| 配置 | 默认值 | 说明 |
|---|---|---|
| `audit.storage-dir` | `data/audit` | 本地流水目录，可用 `AUDIT_STORAGE_DIR` 覆盖 |
| `audit.retention-millis` | `0` | 保留时长（毫秒），0 表示不自动清理 |
| `audit.retention-mode` | `ARCHIVE` | `ARCHIVE` 或 `DELETE` |
| `audit.retention-schedule-enabled` | `false` | 是否启用定时保留任务 |
| `audit.retention-schedule-millis` | `3600000` | 定时任务间隔 |
