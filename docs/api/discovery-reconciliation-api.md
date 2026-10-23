# discovery 对账与新鲜度 API

版本: 1.0    更新时间: 2026-10-24

> 本文定义 discovery-service 的 hash 对账契约（`discovery.v1 DiscoveryReconciliationService`）与新鲜度标记语义（[ADR-019](../decisions/adr-019-hybrid-push-pull-subscription.md) 推拉结合三件套之三）。canonical checksum 规则的**单一来源**在本文 §3——各语言实现（Java 服务端 / SDK / v1.x Rust）照此独立实现，不得互相抄代码或依赖 protobuf 序列化字节。

## 1. 契约与部署

| 项 | 值 |
|---|---|
| proto | `proto/discovery/v1/discovery.proto`（`package artemis.discovery.v1`） |
| Java 生成 | 服务端生成于 `artemis-discovery-core`；client SDK 独立生成（同 FQCN 异 artifact，跨端只走 wire） |
| 传输 | gRPC（unary `Checksum`），discovery **外部端口**（默认 9095） |
| 认证 / 就绪 | 与 Query 同一外部端口语义：BasicAuth 默认关闭；未就绪 `UNAVAILABLE`（readiness 拦截器全局生效） |
| 无服务端配置与指标 | 对账节奏（周期、比对、触发全量拉）全部归 SDK 侧（v0.3 phase-8） |

## 2. 方法

### `Checksum`

`ChecksumRequest` → `ChecksumResponse`

拉取 per-service **视图 checksum 表**。SDK 定期与本地视图逐 service 比对，不一致的 service 触发 `Query` 全量拉——增量丢失的显式检测，漂移窗口 = 对账周期。

**请求选择器**与 `Query`/`Subscribe` 同一语义（`services` 精确集合，空 = 全部；`labels` 实例级过滤），checksum 计算于该选择器过滤后的**视图**（与 SDK 本地持有视图可比）。

**响应**：`data.clusters` 为 `ClusterChecksums` 列表（`cluster` 来源标识 + per-service `(key, uint32 checksum)` 条目）。

**缺项规则**：响应只含视图中有实例的条目——**缺项 = 该服务在视图中无实例（权威空）**。SDK 本地空 + 表缺 = 一致；丢失的 `NEW` 推送必使表项出现 → 不一致 → 全量拉。absent 与 empty 在协议上等价，无歧义。

**实现分型**（D-F3）：无 labels 请求读服务端 per-条目缓存（投影写入时计算，响应零计算）；labels 请求对过滤视图响应时计算（成本 O(命中实例)，仅 labels 订阅方、频率 = 对账周期）。

**弱一致**：对账读与并发投影应用无锁交错，瞬时不一致触发的一次多余全量拉无害（下次对账收敛）；新鲜度快照先于表读取（与同步线程发布序交错只产生保守向：陈旧标签 + 新数据）。

## 3. canonical checksum 规则（跨语言单一来源）

```text
serviceChecksum = xor { instanceHash(i) : i ∈ 实例集 }          （空集 = 0）

instanceHash    = fnv1a32(utf8(s))  ^  xor { fnv1a32(utf8(m)) : m ∈ metadata 条目 }

s（标量序列，位置编码、固定顺序）：
    instanceId ␀ host ␀ port ␀ statusName ␀ weight ␀
m（单条键值对）：
    key ␀ value ␀
```

| 项 | 规则 |
|---|---|
| `␀` | 分隔符 U+0000；字段值含 U+0000 不受支持 |
| `port` / `weight` | 十进制整数文本（如 `8080`、`100`） |
| `statusName` | 语义名：`UNSPECIFIED` / `UNKNOWN` / `UP` / `DOWN`（= wire 枚举值名去 `INSTANCE_STATUS_` 前缀） |
| `fnv1a32` | FNV-1a 32 位：offset basis `0x811C9DC5`、prime `0x01000193`，逐 **UTF-8 字节（无符号）**：`hash = (hash ^ byte) * prime` |
| 抗序性 | 实例集与 metadata 均以 XOR 结合（交换律）——**迭代/排序序无关**，不定义任何跨语言排序规则；唯一顺序敏感点 = 单实例标量字段的位置序列 |
| 碰撞天花板 | 32 位指纹：不同内容碰撞概率 ~2⁻³²（instanceId 键唯一，无重复元素消没）；冲突由 SDK 15min 全量刷新兜底 |

**非 Java 实现注意（Rust 等）**：

- FNV 乘法是 mod 2³² wrapping 运算——Rust 须 `wrapping_mul`（默认溢出 panic）；
- wire 字段 `uint32` 与本地有符号表示比较时按**位模式**（Java 侧 `int` 与 `uint32` 互转不丢位）。

**golden vector**（跨语言对齐锚点）：

```text
输入：namespace="default", service="order",
      instance = { id="i1", host="10.0.0.1", port=8080, status=UP, weight=100, metadata={"group":"a"} }
期望：serviceChecksum = 3584519012（0xd5a76b64）
```

## 4. 新鲜度标记（查询与对账响应共用）

`QueryResponseData.upstreams` 与 `ChecksumResponseData.upstreams` 携带 `UpstreamStatus`（`cluster` + `UpstreamSyncState`）：

| 状态 | 语义 |
|---|---|
| `SYNCED` | 已完成首轮同步，正在消费变更流（数据实时） |
| `SYNCING` | 连接与首轮 bootstrap 进行中（含曾同步后的重连轮次） |
| `DISCONNECTED` | 曾同步但当前断连，投影冻结继续服务 |

- 消费方判定规则统一为**非 `SYNCED` 即可能陈旧**，无需细分；对未知枚举值映射本地 `UNKNOWN` 哨兵（与整体枚举纪律一致）。
- registry 自我保护态不进新鲜度（registry 内部清理门控概念，架构 §4.1 定案）。
- 出口只在拉取面（查询 + 对账）；订阅流不携带——上游断连期间订阅流静默是 D-S7 定案（流与上游状态解耦），降级感知经 SDK 兜底轮询的拉取响应到达。
- 快照顺序不保证，按 `cluster` 键控消费；多集群（phase-5）每上游独立一条。

## 更新历史

| 版本 | 日期 | 变更说明 |
| ------ | ------ | ------ |
| 1.0 | 2026-10-24 | 初版：Checksum 契约、canonical 规则单一来源（golden vector）、缺项规则、新鲜度语义 |
