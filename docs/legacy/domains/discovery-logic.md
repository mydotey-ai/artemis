# 服务发现与变更通知 · 业务逻辑蓝本

版本: 1.0    更新时间: 2026-10-08

> 调研对象：原仓库 `~/Projects/mydotey/artemis`（version 2.0.2，git HEAD 9727bb5）。
> 定位：原产品发现与推送的完整业务逻辑，作为新产品设计时逐单元评估与优化的蓝本。事实性；取舍依据引用基线 §5/§6，对照见 §8。阅读约定：逻辑单元 L1–L7、决策规则 D1–D4、流程 F1–F5；证据引用约定同 [discovery-spec.md](discovery-spec.md)。

## 1. 逻辑总览

```text
注册表(registry-lease 域) ──变更事件──► 变更跳表(有界1万, 按实例去重)
                                          │ NotificationCenter：10 worker 阻塞消费
                                          ▼
                                    推送过滤器链(DELETE/RELOAD 恒放行)
                                          │ 同一 worker 串行调用两个 subscriber
                          ┌───────────────┴───────────────┐
                          ▼                               ▼
                  单播 handler(按 serviceId)        广播 handler(全部会话)
                  find sessions → 同步发送          全会话同步发送
                          │ 发送失败关会话=丢消息(at-most-once)
                          ▼
客户端: WS 增量原地更新缓存 ── reload 伪实例 ──► 批量 lookup 全量替换
        60s 兜底轮询(失败集+空服务+TTL 15min) ──► 批量 lookup 纠偏
```

| 逻辑单元 | 职责 | 关键代码（原仓库） |
|---|---|---|
| L1 查询管线 | readiness / zone 门槛 → 直读注册表 → 过滤器链 | `artemis-service/.../discovery/DiscoveryServiceImpl.java` |
| L2 版本化缓存与 delta | 后台快照预生成 + 版本差集预计算 | `cache/VersionedCacheManager.java`、`ServicesDeltaGenerator.java` |
| L3 订阅表管理 | serviceId → 会话集反向索引、惰性清理 | `artemis-server/.../websocket/ServiceChangeWsHandler.java:105-150` |
| L4 推送分发 | worker 消费 → 过滤 → subscriber 串行 → 会话同步发送 | `notify/NotificationCenter.java:71-102` |
| L5 客户端增量落地 | new/delete/change 原地更新 + updated 判定 | `artemis-client/.../discovery/ServiceRepository.java:181-216` |
| L6 reload 选集与批量拉取 | 三层兜底决策 + 整批失败语义 | `discovery/ServiceDiscovery.java:94-159` |
| L7 注册即订阅 | 首次 getService 同步 lookup + 订阅注册 | `ServiceRepository.java:110-132`、`ServiceDiscovery.java:82-84,161-185` |

## 2. 概念模型与不变量

- 服务端变更流：产生（register / 剔除 / 管理合成）→ 有序缓冲（跳表）→ 多 worker 并发消费 → 会话分发。**at-most-once**：无持久化、无 ACK、无重放。
- 客户端缓存：`Map<serviceId(lowercase), ServiceContext>`；ServiceContext = Service 快照 + 监听者 + 可用性。
- 不变量：I1 客户端每服务缓存值 ⊆ 某次全量快照 + 后续增量（增量丢失时可能陈旧，由兜底纠正）；I2 DELETE 回调当且仅当实例真的从缓存移除；I3 WS 增量不动 logicInstances / routeRules（只有 reload 更新）。

## 3. 状态机

变更事件生命周期：`产生 → 缓冲中（同实例折叠，仅一条）→ 已消费 → 已过滤/已分发 →（发送失败 ⇒ 对该订阅者丢失）`。无重试态、无重放态——这是推送语义的核心事实。

会话生命周期与订阅状态见 client-sdk-logic.md §3（WS 状态机）+ 本域 L3（订阅项随会话死亡而惰性清理）。

## 4. 决策规则

### D1 增量落地决策（客户端，update(InstanceChange)）

| changeType | 动作 | updated 判定 | 回调 |
|---|---|---|---|
| DELETE | instances.remove(instance)（InstanceKey 相等匹配） | 真的移除才 true | true 时 |
| NEW | 先删后加（serviceId 大小写不敏感校验） | **恒 true**（无内容 diff） | 恒回调 |
| CHANGE | 等同 NEW（服务端无产生点） | 恒 true | 恒回调 |
| 其他 | 仅 info 日志 | false | 否 |

缓存键匹配：按推送内**原始大小写** serviceId 查小写键缓存——miss 即静默丢弃（§7.9）。证据：`ServiceRepository.java:181-216`、`ServiceContext.java:60-93`。

### D2 reload 选集决策（60s poller）

```text
if 距上次成功全量 > ttl(15min):        选集 = 全部已订阅服务
else:                                 选集 = reload 失败集 ∪ 实例列表为空的服务
批量 lookup 成功: 全量替换(无 diff 恒回调 RELOAD) + 逐服务清失败标记
批量 lookup 失败(Throwable): 整批全部记入失败集后重抛(已成功者也记, 仅多拉)
```

证据：`ServiceDiscovery.java:103-159`。

### D3 推送过滤决策（NotificationCenter）

DELETE / RELOAD 恒放行（绕过全部 NotificationFilter）；其余类型经过滤器链，摘除实例的 NEW 被压掉（operations-audit 域注册）。证据：`NotificationCenter.java:21-22,96-102`。

### D4 查询门槛决策（服务端）

canServiceDiscovery（否则 service-unavailable）→ 同 zone 或 allow 放开（否则 no-permission）→ 直读 + 过滤器链（逐 filter 独立 try/catch，fail-open）→ 空服务返回空 Service（lookup 过滤器链 / service 端点不过，两端不一致）。证据：`DiscoveryServiceImpl.java:97-140,195-243`。

## 5. 核心流程

### F1 首次发现（消费方视角）

getService miss → 同步 lookup（HTTP，失败仅记日志、缓存空 Service，靠 60s 轮询补）→ 注册 DiscoveryConfig → 向当前 WS 会话补发订阅消息（会话未建连则静默跳过，等建连时统一补）→ 后续增量 + 兜底。

### F2 增量推送落地（服务端视角）

register / 剔除产生 InstanceChange → 入跳表（同实例折叠、超限丢最老）→ worker pollFirst → D3 过滤 → 单播 handler：按 serviceId 找会话集（顺手剔除死会话 ID）→ 消息序列化一次 → 逐会话 `synchronized(session)` 同步发送 → 失败关会话（该订阅者丢此条）。

### F3 管理元数据变更 → reload 全链路

管理面写 DB（分组 / 路由 / 逻辑实例 / zone 摘除）→ 各节点缓存刷新 diff 出变化 serviceId → 合成 RELOAD 伪实例入跳表 → 推送 → 客户端批量 lookup 全量替换（routeRules / logicInstances 至此才更新）→ RELOAD 回调宿主。

### F4 兜底轮询周期

60s 扫描 → D2 选集 → 批量 lookup → 替换 + 回调。三层语义：失败重试（60s 级）/ 空服务守护（60s 级）/ 全量纠偏（15min 级）。

### F5 丢推收敛（典型场景）

服务 S 实例变更 → 推送丢失（发送失败 / 缓冲折叠）→ 客户端缓存陈旧 → 若实例变空：60s 进轮询集；否则：15min TTL 全量刷新纠正 → 回调 RELOAD。**纠偏粒度是全量、不是单条重发。**

## 6. 并发与时序

| 场景 | 机制 | 结果 |
|---|---|---|
| 10 worker 并发消费同实例的 NEW+DELETE | 各自 pollFirst，发送完成顺序无保证 | 客户端可能最终态颠倒，15min 兜底纠正（§7.2） |
| 两个 subscriber（单播 / 广播）分发 | 同一 worker 线程串行同步调用 | 广播通道慢消费者拖慢单播分发（§7.5 变体） |
| 慢 WS 订阅者 | 同步发送占住 worker（10 个） | 少量慢客户端耗尽推送并行度 |
| 首次 getService 同步 lookup | ServiceRepository 单实例锁 | 最多 5×100ms 的 REST 重试阻塞所有服务的推送落地与首次注册（§7.6） |

参数：poll-wait 20ms / worker 10 / 缓冲 10000 / 快照 30s × 3 份 / 客户端 TTL 15min / poller 60s / 服务端会话 6min / 客户端会话 5min。

## 7. 边界与已知缺陷（事实清单）

1. **变更缓冲按实例折叠丢事件**：`InstanceChange.equals` 只比 instance，同实例在缓冲中同时只挂一条；缓冲未满时后到变更 add 失败**被静默丢弃**（非替换），满时才替换——快速「注册→注销」翻转可能只留一条变更。证据：`InstanceChange.java:44-59`、`InstanceChangeComparator.java:14-26`、`RegistryRepository.java:263-275`。（新发现；registry-lease 域 §7.2 同源）
2. **乱序推送**：并发 worker 对同实例事件的发送顺序无保证，客户端最终态可能颠倒，仅靠 15min 兜底。证据：`NotificationCenter.java:71-94`。
3. **推送无背压重放**：发送失败关会话即丢（at-most-once）；服务端无 per-subscriber 队列。设计取舍，需新产品显式决策。
4. **客户端入站 8KB 文本缓冲与消息尺寸无匹配保障**：大 metadata 实例的推送 JSON 超限 → 处理失败甚至断连（重连自愈但该条丢失）。服务端出站缓冲取决于容器默认（仓库内无配置证据）。证据：`WebSocketSessionContext.java:60-64`。
5. **广播通道与单播串行**：同一 worker 先广播后单播（或反之）同步执行，广播慢会话拖慢单播分发。证据：`NotificationCenter.java:80-87`。
6. **ServiceRepository 单锁三热点**：注册（含同步 REST）、全量更新、增量更新共用一把锁——首次发现的慢查询阻塞全部服务的推送落地。证据：`ServiceRepository.java:110,154,181`。
7. **全量 reload 无 diff 恒回调**：15min 周期对每个订阅服务发 RELOAD 回调，宿主须容忍周期性全量事件。证据：`ServiceRepository.java:154-179`。
8. **空服务两端点行为不一致**：lookup 空服务带 group 元数据、service 端点不带。证据：`DiscoveryServiceImpl.java:101-106,137-140`。
9. **serviceId 大小写链路敏感 + 客户端读侧未归一**：混大小写服务的增量推送静默丢弃（D1）；系统约定全小写但无强制。证据：`ServiceChangeWsHandler.java:135`、`ServiceRepository.java:188`。
10. **订阅 fire-and-forget 无确认**：发送失败 / 未建连静默，补订阅最久 5min（客户端会话轮换）。证据：`ServiceDiscovery.java:82-84,171-185`。
11. **up-nodes 端点独立限流**：大规模客户端同时冷启动刷新地址表可能触发 rate-limited，客户端降级用旧列表 / 引导地址（自愈）。证据：`ClusterServiceImpl.java:38-49`。

## 8. 逻辑单元 × 基线资产 / 局限对照

| 逻辑单元 | 基线对照 | 说明 |
|---|---|---|
| L4 + F5 | 资产 §5.8（三层兜底） / 局限 §6.3（delta 未用） | 推送丢失自愈兜底；版本化增量机制完整但未被消费 |
| L1 | 资产 §5.7（过滤器链 SPI） | 分组路由 / 摘除均为 filter 实现，扩展点干净 |
| L2 | 局限 §6.7（delta 窗口 90s、version 无单调性） | 设计完整未用 |
| L5 | 局限 §6.13（回调 / 克隆代价） | 无 diff 恒回调 + 深克隆（client-sdk §7.6） |
| FR-DIS-09 | 局限 §6.15（无新鲜度元信息） | 缓存永不失效且不可感知陈旧 |
| §7.1 / §7.2 / §7.6 / §7.9 | 基线 §6 未收录 | 本次补证新发现，建议纳入局限输入 |
| — | 基线勘误 | 「灰度元数据参与筛选」（未实现，见 traffic-governance）、「同 zone 优先」（实为过滤）、「深克隆」（实为壳克隆 + 引用共享）、「change 原地更新」（CHANGE 无产生点）——已在本域 spec 修正表述，基线 §2.4/§2.5 待勘误 |

## 更新历史

| 版本 | 日期 | 变更说明 |
| ------ | ------ | ------ |
| 1.0 | 2026-10-08 | 初版 |
