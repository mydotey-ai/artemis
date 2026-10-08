# 实例注册与租约生命周期 · 业务逻辑蓝本

状态: 草案  日期: 2026-10-08

> 调研对象：原仓库 `~/Projects/mydotey/artemis`（version 2.0.2，git HEAD 9727bb5）。
> 定位：原产品本域的完整业务逻辑——概念模型 / 状态机 / 决策规则 / 流程（含失败路径）/ 并发时序 / 已知缺陷，作为新产品设计时**逐单元评估与优化**的蓝本。本文保持事实性，不展开改进方案；取舍依据引用基线 §5（资产）/ §6（局限），对照表见 §8。
> 阅读约定：逻辑单元编号 L1–L10、决策规则 D1–D7、流程 F1–F7，供新产品设计文档与同域 [spec.md](registry-lease-spec.md) 引用。证据引用约定同 spec.md。

## 1. 逻辑总览

```text
客户端进程                                服务端（每个对等节点，各持全量）
──────────                                ──────────────────────────────
本地实例集 (AtomicReference<Set>)           RegistryRepository
  │  register/unregister ── HTTP ────────►   ├ _services: Map<serviceId, Service>
  │      ▲  （前置注销，失败不回滚）          ├ _leases: Map<serviceId, Map<instanceId, Lease>>
  │      │                                   └ _instanceChangeSet: 跳表(有界 1 万)
  │      └─ data-not-found ── HTTP 补注册          ▲ NEW/DELETE 事件 → 发现推送(discovery 域)
  └─ WS 心跳(全量, 5s) ──────────────────►  写处理管线: 限流→校验→准入→执行→复制
                                            租约池 ×2 (普通 20s / legacy 90s)
                                              ├ LeaseManager._leaseCache (Instance 为键)
                                              └ clean 线程(2×1s) ── D2 摘除决策
                                                   ▲ 受 D4 自我保护约束
```

| 逻辑单元 | 职责 | 关键代码（原仓库） |
|---|---|---|
| L1 本地实例集管理 | 注册状态唯一事实源的维护与合并 | `artemis-client/.../registry/InstanceRepository.java` |
| L2 心跳引擎 | 双阈值调度、全量消息编码、WS 收发 | `InstanceRegistry.java` |
| L3 服务端写处理管线 | 限流 / 校验 / 准入 / 逐实例执行 / 触发复制 | `artemis-service/.../registry/{RegistryServiceImpl,RegistryTool}.java` |
| L4 租约对象语义 | renew / evict / 过期粘滞 | `artemis-common/.../lease/Lease.java` |
| L5 租约池与双池判定 | 普通 / legacy 池选择，共享注册表视图 | `RegistryRepository.java:66-71,318-323` |
| L6 过期清理算法 | 全表扫描 + tryLock 摘除 | `LeaseManager.java:126-164` |
| L7 清理保护 | creationTime 新旧比较，防误删新租约 | `LeaseManager.java:141-148`、`RegistryRepository.java:289-293` |
| L8 自我保护 | 续约量滑动窗口检测 | `LeaseUpdateSafeChecker.java` |
| L9 对账闭环 | 客户端补注册 + 复制面同步补注册 | `InstanceRegistry.java:170-187`、`RegistryReplicationServiceImpl.java:89-93` |
| L10 变更事件生成 | NEW / DELETE 产生、有界缓冲、去重折叠 | `RegistryRepository.java:74-75,263-275` |

## 2. 概念模型与不变量

**数据结构**（服务端，纯内存零持久化）：

- `_services: ConcurrentHashMap<serviceId, Service>`——服务壳，注册时无条件 put。
- `_leases: ConcurrentHashMap<serviceId, ConcurrentHashMap<instanceId, Lease>>`——**原始大小写字符串**为键（⚠ 与租约池的 Instance 键语义分裂，见 §7.1）。
- 租约池 `LeaseManager._leaseCache: ConcurrentHashMap<Instance, Lease>`——Instance 为键，equals 委托 InstanceKey（**大小写不敏感**）。`_leases` 与 `_leaseCache` 是同一租约的两份索引，靠清理回调保持一致。
- `_instanceChangeSet: ConcurrentSkipListSet<InstanceChange>`——按 changeTime 排序，容量超限丢最老。

**不变量**（意图；破损处标注）：

- I1 客户端本地集 ⊇ 应在线实例；服务端租约集 ≈ 最近 TTL 内有心跳的实例（心跳全量上报维持）。
- I2 `_leases[serviceId][instanceId]` 恒指向 creationTime 最新的租约（覆盖写 + D7 清理保护维持；§7.1 大小写残留破坏此不变量）。
- I3 实例离开注册表仅两条路径：过期剔除（受 D4 保护）或显式 evict（不受保护）。
- I4 DELETE 事件仅在实例真正移除时发出（D7 保证覆盖注册 / 双池迁移不误发）。
- I5 剔除纯本地（无剔除复制消息），集群收敛靠「续约复制到每个节点」+「各节点独立过期」（F3）。

## 3. 状态机

### 3.1 租约状态机（服务端视角）

| 当前态 | 事件 | 动作 | 次态 |
|---|---|---|---|
| ABSENT | register（含补注册 / 复制注册） | new Lease(creationTime=now, renewalTime=now)；发 NEW 事件；复制 RegisterTask | LIVE |
| LIVE | 心跳 renew 成功 | renewalTime=now；自我保护打点 markUpdate | LIVE |
| LIVE | now > renewalTime + ttl | 过期标志**粘滞置位**，此后 renew 一律拒绝（返回 false） | EXPIRED |
| LIVE / EXPIRED | unregister（显式 evict） | evictionTime=now（幂等，首写胜出）；复制 UnregisterTask | EVICTED |
| EXPIRED | clean 周期到达 | D2 判定：D4 保护通过 → 摘除；发 DELETE 事件；空服务回收 | REMOVED |
| EVICTED | clean 周期到达 | D2 判定：**跳过保护检查直接摘除**；发 DELETE 事件 | REMOVED |
| 任意态 | 同标识再 register | 全新 Lease 覆盖池缓存（旧对象成孤儿，永不参与 clean、不发 DELETE） | LIVE（新对象） |

证据：`Lease.java:24-31,53-59,65-86`、`LeaseManager.java:69-75`、`RegistryRepository.java:103-119,277-306`。

### 3.2 客户端注册投影（本地集 × 远端租约）

| 本地集 | 远端租约 | 状态 | 收敛动作 |
|---|---|---|---|
| 有 | 有 | 一致 | 心跳续约（稳态） |
| 有 | 无 | 失配 | 心跳返回 data-not-found → HTTP 补注册（D5/F1） |
| 无 | 有 | 残留 | 停止心跳（空集不发）→ 服务端 TTL 过期剔除（F3） |
| 无 | 无 | 一致（空） | 无流量 |

## 4. 决策规则

### D1 池选择（每次请求独立判定）

`metadata.java_registry` 非空且非空白 → legacy 池（TTL 发布配置 90s）；否则普通池（20s）。register / heartbeat / unregister 三入口均按**当次**请求判定。证据：`RegistryRepository.java:111-112,125-126,134-135,318-323`。

### D2 过期清理摘除决策（LeaseManager.clean，逐租约）

| # | 条件 | 动作 |
|---|---|---|
| 1 | tryLock(lease) 失败 | 跳过（正被续约 / 其他 clean 线程处理） |
| 2 | 租约**未被显式 evict** 且 D4 判定 unsafe | 跳过（保护期不剔「仅过期」租约） |
| 3 | 租约未被 evict 且未过期（now ≤ renewalTime + ttl） | 跳过（保留） |
| 4 | 池缓存现存 value.creationTime > 本租约.creationTime | putIfAbsent 放回，跳过（并发覆盖的新租约，L7 第一处） |
| 5 | 以上均否 | 摘除 → 回调 onLeaseClean → L7 第二处保护 → 移除 + DELETE 事件 + 空服务回收 |

要点：evicted 租约**跳过 #2/#3**（保护与过期检查都不做，必摘）——这是显式注销立即生效、且自我保护期仍可下线实例的根源。证据：`LeaseManager.java:126-164`。

### D3 写请求准入（有序门槛）

限流（rate-limited）→ 请求/实例校验（bad-request）→ 节点就绪，复制请求豁免（service-unavailable）→ region 一致，复制请求**不豁免**（no-permission）→ zone 一致或节点放开，复制请求豁免（no-permission）→ 执行（异常 internal-service-error；心跳缺失 data-not-found）→ 聚合（全成 success / 部分 partial_fail）。完整表格见 spec.md FR-RL-07；证据 `RegistryTool.java:78-101,115-154`。另有第二层冗余防线：RegistryRepository 内 SameRegionChecker 直接抛异常 → 包装为 internal-service-error（`RegistryRepository.java:105,123,132`）。

### D4 自我保护判定（每池独立，每秒评估）

输入：滑动窗口（默认 10s）内**成功续约**次数 count；历史峰值 maxCount 及其最后更新时间。

```text
if count > maxCount:              maxCount = count（随高峰抬升）
if maxCount < 50:                 safe（未达启用门槛，不做保护）
elif count * 100 / maxCount < 85: unsafe（保护态）
else:                             safe
if maxCount 超过 10min 未刷新:     maxCount = count（回落，防历史高峰永久抬高）
```

unsafe 期间 D2 #2 拦截全部「仅过期」剔除。证据：`LeaseUpdateSafeChecker.java:90-112,147-185`、`Lease.java:65-79`（renew 成功才 markUpdate）。

### D5 心跳响应客户端决策

| 响应内容 | 动作 |
|---|---|
| responseStatus ∈ serviceDown 集 | markdown()：熔当前节点 + 立即健康检查重建 |
| failedInstances[].errorCode = data-not-found 或 unknown | 收集实例 → HTTP 批量注册（registerToRemote，经过滤器链） |
| 其余（rate-limited / no-permission / internal-service-error / service-unavailable / bad-request） | 忽略，等下一轮心跳 |

证据：`InstanceRegistry.java:103-131,176-183`、`ErrorCodes.java:12-20`。

### D6 心跳发送调度（检查线程默认 1s）

```text
if 距上次心跳 ≥ instance-ttl (20s):   markdown() 强制重建连接（可能换节点）
elif 距上次心跳 ≥ interval (5s):      发送全量心跳（空集 → 跳过但仍刷新 lastHeartbeatTime）
```

证据：`InstanceRegistry.java:140-168`、`InstanceRepository.java:83-95`。

### D7 清理保护（creationTime 新旧比较，两处）

1. **池内**（D2 #4）：clean 遍历到的旧租约 vs 池缓存现存 value——现存更新则放回。
2. **共享视图**（onLeaseClean）：从 `_leases[serviceId]` remove 出的 existing vs 被清理租约——existing 更新则 putIfAbsent 放回且**不发 DELETE**。

用途：并发覆盖注册、双池迁移（F6）不误删新租约、不误发 DELETE。证据：`LeaseManager.java:141-148`、`RegistryRepository.java:289-293`。

## 5. 核心流程

### F1 注册（端到端，首次）

1. `register(instances)`：HTTP unregister 清旧租约（失败不阻塞）→ 并入本地集。⚠ 不发注册请求。
2. ≤1 个心跳间隔后，WS 全量心跳到达服务端 → 管线执行 → 租约缺失 → 响应 failedInstances(data-not-found)。
3. 客户端 HTTP `/api/registry/register.json` 补注册 → 服务端 new Lease + NEW 事件 + 复制 RegisterTask → peer 各自注册（复制注册同样发 NEW 事件）。
4. 发现订阅者收到 NEW，实例可被发现。**首次注册可见延迟 ≈ 1 心跳间隔 + 补注册一轮**（≈ 5–6s 量级）。

### F2 稳态心跳

客户端 5s 全量上报 → 续约（markUpdate 供 D4 统计）→ 复制 HeartbeatTask（批量通道 250 条/2s，replication-cluster 域）→ peer 续约。稳态下无 failedInstances、无补注册流量。

### F3 失联剔除（时间线）

客户端停跳（崩溃 / 网络断）→ **所有节点**同时失去续约来源（此前续约靠复制维持）→ 本节点：renewalTime + 20s 过期 → 下一 clean 周期（≤1s）D2 判定 → D4 safe 则摘除 → DELETE 事件 → 本节点订阅者收 DELETE。各节点独立重复此过程。**保护期例外**：D4 unsafe 时摘除暂停，实例保留至保护解除。总延迟 ≈ TTL(20s) + clean(1s) + 推送。

### F4 显式注销

unregister HTTP → 服务端 evict（幂等标记）+ 复制 UnregisterTask → peer 各自 evict → 各节点 clean 周期内摘除（**跳过 D4 保护**）→ DELETE 事件。可见延迟 ≈ 1s 量级，比过期剔除快一个数量级。

### F5 断线恢复

WS 断开 / 服务端会话 TTL(6min) 强制关闭 → 客户端健康检查（1s）判不可用 → connect（重连限流 5 次/20s；失败熔地址换节点）→ 重连成功 → 心跳照常 → 租约已过期则 data-not-found → 补注册。全程无状态机、无人工干预——对账即恢复。证据：`WebSocketSessionContext.java:94-161,219-235`。

### F6 legacy 池迁移（metadata 中途变化）

心跳按新 metadata 在新池 miss → data-not-found → 客户端补注册到新池（`_leases` 被新 Lease 覆盖）→ 旧池旧租约到 TTL 过期 → clean 摘除时 D7 #2 拦截（existing 更新）→ **静默消失，不发 DELETE**。⚠ 例外：迁移窗口期若 D4 unsafe，旧池过期租约不被清理，形成租约临时残留（per-pool 状态视图可见），保护解除后消失。

### F7 服务端节点重启

节点注册表清空 → 冷启动门控未过则 API 返回 service-unavailable（客户端 markdown 换节点）；门控靠 peer 全量拉取重建（replication-cluster 域）。三路收敛：① 客户端心跳 data-not-found → 补注册；② peer 复制心跳遇缺失 → 同步补注册；③ 冷启动全量拉取。任一路径单独足以恢复。

## 6. 并发与时序

### 6.1 时序参数总表（默认值）

| 参数 | 值 | 所属 |
|---|---|---|
| 心跳间隔 / 客户端 instance-ttl | 5s / 20s | 客户端 D6 |
| 服务端租约 TTL（普通 / legacy） | 20s / 90s（发布配置） | L5 |
| clean 线程 × 周期 | 2 × 1s（每池） | L6 |
| 自我保护窗口 / 评估周期 / 阈值 / 启用门槛 / 回落 | 10s / 1s / 85% / 50 / 10min（每池） | L8 |
| 客户端 WS 会话 TTL / 服务端会话 TTL / 服务端检查周期 | 5min / 6min / 60s | L2 |
| 客户端健康检查 / 重连限流 | 1s / 5 次每 20s | L2 |

### 6.2 竞态场景表

| 竞态 | 机制 | 结果 |
|---|---|---|
| renew vs clean 同一租约 | Lease 内 tryLock 非阻塞互斥 | 一方胜出；renew 失败 → data-not-found → 客户端补注册（良性，新租约） |
| 覆盖注册 vs clean 迭代 | D7 #1 creationTime 比较 | 旧租约迭代到时发现缓存已有新租约 → 放回，不误删 |
| 双池迁移清理 vs 新注册 | D7 #2 | 旧池清理被共享视图保护拦截，不发 DELETE |
| unregister vs clean | evict 幂等（首写胜出） | 无重复副作用 |
| 同标识不同大小写注册 | 池键不敏感 vs `_leases` 键敏感 | ⚠ 发现视图残留，见 §7.1 |
| 2 个 clean 线程并发 | tryLock + D7 双保险 | 同一租约至多摘一次 |

## 7. 边界与已知缺陷（事实清单，重设计输入）

1. **大小写键语义分裂 → 永不清理的发现残留**：租约池以 InstanceKey（不敏感）覆盖，`_leases` 以原始字符串（敏感）并存——同实例混大小写重复注册后，旧条目指向的 Lease 已不在任何池缓存中，**永远不会被 clean 移除**，且心跳对任一大小写都能续租同一 Lease，使残留条目长期存活。证据：`InstanceKey.java:72-94`、`RegistryRepository.java:113-116`、`LeaseManager.java:29,69-81`。（基线 §6 未收录，新发现）
2. **变更事件折叠去重忽略 changeType**：`InstanceChange.equals` 只比较 instance，事件集为 Set——同实例 NEW 与 DELETE 未被消费前相邻到达会折叠为一条（后到者丢弃）。另 `InstanceChangeComparator.compare` 在 changeTime 相等且实例不同时双向都返回 -1，违反 Comparator 契约。证据：`InstanceChange.java:66-79`、`InstanceChangeComparator.java:26-27`。（新发现）
3. **客户端重复注册不更新数据**：本地集 HashSet 合并，InstanceKey 相等即 no-op——同标识新数据（如 metadata 变化）注册后心跳载荷仍是旧对象。影响有限（心跳只续租不上载数据，数据更新靠补注册的覆盖写）。证据：`InstanceRepository.java:133-141`。（新发现）
4. **自我保护不覆盖显式下线**：保护期 unregister / 管理摘除照常生效——语义上是「保护被动失联，不干预主动运维」，为原产品取舍；新产品须显式重申或修改该边界。证据：`LeaseManager.java:133`。
5. **WS 心跳解析异常回固定 success**：畸形心跳消息被静默吞掉，客户端无从感知注册未生效。证据：`HeartbeatWsHandler.java:32-53`。
6. **事件缓冲有界丢弃**：超 1 万条丢最老，通知可能不完整——设计取舍，由发现域全量兜底（15min）收敛。证据：`RegistryRepository.java:58-60,263-275`。
7. **HTTP 心跳端点无调用方**：客户端心跳仅走 WS；`heartbeat.json` 与 `RegistryServiceClient` 为有实现无调用的死路径（供旧代客户端的推断未证实）。证据：`ArtemisRegistryHttpClient.java:28-68`（仅 register/unregister 两方法）。
8. **allow-from-other-zone 默认值漂移**：代码 false / 发布配置 true——引用原产品行为时以发布配置为准。证据：`NodeManager.java:56-57`、`artemis.properties:33`。

## 8. 逻辑单元 × 基线资产 / 局限对照

| 逻辑单元 | 基线对照 | 说明 |
|---|---|---|
| L1 + L2 + L9 | 资产 §5.1（心跳即注册） | 全量幂等对账，断线 / 重启 / 服务端恢复自动收敛 |
| L6 + L8 | 资产 §5.10；局限 §6.9 | 自我保护语义值得继承；全局单阈值无法区分局部异常与网络故障 |
| L3 批量化 | 资产 §5.11 | 批量贯穿是 10 万实例的立身之本 |
| L4 + L7 creationTime 保护 | 基线 §2.7 冲突处理 | 覆盖写 + 新旧比较，无版本向量的冲突收敛手段 |
| L5 双租约池 | 基线 §2.2、§3.8 | legacy 客户端兼容包袱，新产品须重新决策 |
| L10 有界缓冲 | 基线 §2.4（三层兜底） | 通知不完整由发现域兜底收敛 |
| I5 剔除本地化 | 基线 §3.3 收敛路径 | 最终一致的多重收敛设计一环 |
| §7.1 / §7.2 / §7.3 / §7.5 | 基线 §6 未收录 | 本次补证新发现的缺陷，建议新产品设计时纳入局限输入 |
