# 服务发现与变更通知 · 功能规格

版本: 1.0    更新时间: 2026-10-08

> 调研对象：原仓库 `~/Projects/mydotey/artemis`（version 2.0.2，git HEAD 9727bb5）。
> 定位：以需求语言规格化原产品「服务发现与变更通知」域的行为，作为新产品需求设计输入。传输设施（地址容灾 / 重试 / WS 生命周期 / 回调线程）见 [client-sdk-spec.md](client-sdk-spec.md)；过滤器的具体实现分属 traffic-governance / operations-audit 域。
> 证据引用约定同 [registry-lease-spec.md](registry-lease-spec.md)；标 **⚠ legacy** 为原产品特有行为或包袱；缺陷见同域 logic.md §7。

## 1. 域定位

消费方经本域获得服务提供方的实例列表与路由视图，并在实例变化时收到推送。设计立场：**推送为主、轮询兜底、缓存永不说谎失败**——正确性目标为「正常路径亚秒级送达；任何漏推 / 丢推场景在有界时间内（默认 ≤ 15min）由全量拉取纠正」。

角色：**服务消费方**（SDK 嵌入业务进程）查询服务、订阅变更。

## 2. 概念与术语

| 概念 | 定义 |
|---|---|
| DiscoveryConfig | 发现查询单元：serviceId + regionId / zoneId + discoveryData（Map） |
| lookup | 批量实时查询（一次多服务，直读内存） |
| 订阅 | 客户端经 WS 发送 DiscoveryConfig，声明只收该 serviceId 的变更 |
| 变更事件（InstanceChange） | instance + changeType（NEW / DELETE / RELOAD）+ changeTime |
| reload 伪实例 | `instanceId=reload / ip=0.0.0.0` 的特殊事件，语义 = 「全量重拉」 |
| 兜底轮询 | 客户端周期扫描（默认 60s），对选定服务集做批量 lookup 纠偏 |
| 版本化缓存 | services.json 全量快照的后台预生成（version = 毫秒时间戳，保留 3 份） |

## 3. 功能需求

### FR-DIS-01 发现查询

**陈述**：系统应提供批量实时查询（lookup）与单服务查询（getService），直读内存注册表并经发现过滤器链加工。

**规则**：
- lookup 一次携带多个 DiscoveryConfig，逐个直读注册表 → 过滤器链 → 聚合返回；regionId / zoneId 由客户端置于请求顶层（取自身部署身份）。
- 查询前门槛：节点 canServiceDiscovery（否则 service-unavailable 整体失败）→ 同 zone 校验（`allow-from-other-zone` 放开；违反 → no-permission）。
- 过滤器链顺序 = 注册顺序（Group → Management）；**单过滤器异常仅记日志并继续**（fail-open：管理面故障时发现降级为基础视图）。

**证据**：原仓库 `artemis-service/.../discovery/DiscoveryServiceImpl.java:97-107,195-243`、`artemis-management/.../ManagementInitializer.java:44-45`。

### FR-DIS-02 空服务语义

**陈述**：查询不存在的服务应返回 serviceId 正确但实例列表为空（null）的 Service 对象，**永不返回 null、永不抛「不存在」异常**。

**规则**：
- 消费方以实例列表判空识别「无实例」，须自行容忍 null / 空列表两种形态。
- ⚠ legacy：lookup 与 service 两端点对空服务的行为**不一致**——lookup 的空服务仍过过滤器链（携带路由 / 逻辑实例元数据），service 端点先判 null 不过滤器链（纯空壳）。

**证据**：原仓库 `DiscoveryServiceImpl.java:101-106,137-140`、`artemis-client/.../discovery/ServiceRepository.java:79-92`。

### FR-DIS-03 订阅协议

**陈述**：客户端应经 WS 声明单服务订阅；一条文本消息 = 一个 DiscoveryConfig = 一个 serviceId。

**规则**：
- 一个连接可通过发送多条消息订阅多个服务；重复订阅幂等（会话集合去重，无计数）。
- 订阅表 = `serviceId → 会话集合` 反向索引；会话关闭时其订阅项惰性清理（下次该服务有变更时剔除死会话 ID）。
- 服务端会话治理：连接最长存活会话 TTL（默认 6min，**不因活动续期**），到期强制关闭——配合客户端 5min 主动轮换，连接不长期驻留单点。
- ⚠ legacy：订阅**无确认响应**（fire-and-forget）；客户端发送失败仅记日志，未建连时静默跳过——补订阅最久等下一次建连（≤ 5min）。

**证据**：原仓库 `artemis-server/.../websocket/ServiceChangeWsHandler.java:41-52,105-150`、`ArtemisWsHandler.java:42-43,114-135`、`artemis-client/.../discovery/ServiceDiscovery.java:161-185`。

### FR-DIS-04 变更推送（双通道）

**陈述**：实例变更应实时推送订阅方：单服务订阅通道（按 serviceId 定向）+ 全服务广播通道（无订阅语义，连上即收全部）。

**规则**：
- 单播：变更按 serviceId 找会话集，逐会话同步发送（per-session 互斥保证单条消息写完整）；消息只序列化一次。
- 发送失败即关闭该会话，**该条消息对该订阅者丢失**（无队列 / 无 ACK / 无重放）；关闭的会话由客户端自愈重连 + 重订阅。
- 广播通道：不按 serviceId 过滤，广播全部变更给全部会话；SDK 不消费（供外部系统）。
- 推送前过滤：变更先经推送过滤器链（DELETE / RELOAD 恒放行；摘除实例的 NEW 类推送被压掉）。

**验收标准**：实例注册 / 剔除后，订阅方在亚秒级（消费 poll-wait 20ms + 10 worker）收到对应 NEW / DELETE。

**证据**：原仓库 `ServiceChangeWsHandler.java:55-99`、`AllServicesChangeWsHandler.java:29-71`、`artemis-service/.../discovery/notify/NotificationCenter.java:71-102`。

### FR-DIS-05 推送可靠性语义

**陈述**：推送应为**尽力而为（at-most-once）**：不保证不丢、不保证有序；可靠性下界由客户端兜底轮询保证。

**规则**（丢推场景收敛表）：

| 丢失场景 | 纠正机制 | 最久纠正时间 |
|---|---|---|
| 发送失败关会话 | 客户端重连重订阅 + 兜底轮询 | 15min（TTL 全量） |
| 变更缓冲去重折叠 / 缓冲溢出丢最老 | 兜底轮询 | 15min |
| 推送乱序（并发消费） | 兜底轮询 | 15min |
| 服务实例变空 | 空服务即入 60s 轮询集 | 60s |

**证据**：原仓库 `NotificationCenter.java:71-94`、`artemis-service/.../registry/RegistryRepository.java:263-275`；logic.md §7。

### FR-DIS-06 客户端缓存与增量更新

**陈述**：客户端应以服务为粒度缓存发现结果，收到增量变更原地更新，仅在有实际变化时回调监听者。

**规则**：
- 变更类型语义：DELETE → 从缓存移除实例（**真的移除才回调**）；NEW → 替换 / 追加实例（**恒回调，无内容 diff**）；CHANGE 类型实际不存在于服务端产生点（⚠ 防御性分支，行为等同 NEW）。
- 缓存键 = serviceId 小写；**注册写侧统一小写**。
- ⚠ legacy：增量落地时按推送消息内的**原始大小写** serviceId 查缓存——混大小写 serviceId 服务的增量推送会被静默丢弃，只能靠 15min 全量收敛（全系统实际约定 serviceId 全小写，但无任何一层强制）。
- WS 增量只更新 instances，**不更新 logicInstances / routeRules**——两者仅 reload 全量刷新。

**证据**：原仓库 `artemis-client/.../discovery/ServiceRepository.java:181-216`、`ServiceContext.java:60-93`；补证（CHANGE 无产生点：全仓库 grep）。

### FR-DIS-07 reload 与全量重拉

**陈述**：RELOAD 事件应触发客户端对该服务立即批量重拉（lookup），全量替换缓存并回调。

**规则**：
- reload 来源：管理面元数据变化（逻辑实例 / 分组路由 / zone 摘除）合成 RELOAD 伪实例。
- 全量替换**无 diff、恒回调**（changeType = RELOAD）——宿主须容忍周期性全量事件。
- ⚠ legacy：批量重拉中任一服务失败，**整批**全部计入失败集（含已成功者，仅导致多拉）后抛出。

**证据**：原仓库 `ServiceDiscovery.java:94-101,125-159`、`ServiceRepository.java:154-179`。

### FR-DIS-08 三层兜底轮询

**陈述**：客户端应周期（默认 60s）扫描并批量重拉以下服务集：① 上次 reload 失败的；② 实例列表为空的；③ 距上次成功全量刷新超 TTL（默认 15min）的（此层为全部缓存服务）。

**规则**：reload 成功一次即从失败集清除；`lastUpdateTime` 仅在成功时刷新。

**验收标准**：任何单条推送丢失场景，实例视图在 ≤ 15min 内与注册表一致。

**证据**：原仓库 `ServiceDiscovery.java:67-79,103-119`；基线 §5.8。

### FR-DIS-09 缓存永不失效

**陈述**：server 全挂时客户端应继续以内存最后一份快照应答 getService（接受陈旧换可用）；缓存无新鲜度元信息（⚠ 消费方无法感知数据陈旧 / 降级）。

**证据**：原仓库 `ServiceRepository.java`（无失效路径）；基线 §2.4、局限 §6.15。

### FR-DIS-10 全量服务列表与版本化缓存

**陈述**：系统应提供全量服务列表端点（services.json，读后台预生成快照并返回 version）与按 version 增量端点（services-delta）。

**规则**：
- 快照：后台单线程周期（默认 30s，首刷延迟 60s）生成全量（GENERIC 配置过过滤器链，实例全被过滤的服务剔除），保留最近 3 份；version = 本地毫秒时间戳（**无单调性保证**）。
- delta：命中缓存版本返回预计算差集；version 过旧返回 data-not-found 逼全量。有效窗口 ≈ 3 × 30s = 90s。
- ⚠ legacy：SDK **不消费**这两个端点（增量完全靠 WS 推送 + 兜底）——一套设计完整却未被产品自身消费的机制。

**证据**：原仓库 `artemis-service/.../cache/VersionedCacheManager.java`、`artemis-client`（grep 零命中）；基线 §2.4、局限 §6.3。

### FR-DIS-11 就近访问

**陈述**：发现请求默认仅接受同 zone 客户端；up-nodes 端点按「能服务 + zone 匹配（或放开）」**过滤**（非排序）返回节点列表，就近选址由客户端消费方与宿主 RPC（依实例 zoneId）完成。

**规则**：空列表返回 data-not-found；端点独立限流（10s 窗口）。
- ⚠ legacy：无任何「同 zone 优先排序」逻辑（基线 §2.4 原表述「同 zone 优先」与代码不符，已勘误）。

**证据**：原仓库 `artemis-service/.../cluster/ClusterServiceImpl.java:46-109`；up-nodes 端点归属 replication-cluster 域。

### FR-DIS-12 发现过滤器链 SPI

**陈述**：发现结果应经注册式过滤器链加工（分组路由注入、摘除剔除均为过滤器实现），链为 append-only 不可变列表、顺序 = 注册顺序、逐个独立容错（fail-open）。

**⚠ legacy**：过滤器操作注册表 Service 的克隆壳，但 instances 元素与租约**共享引用**——过滤器须自律不改 Instance（无强制）。

**证据**：原仓库 `artemis-service/.../discovery/DiscoveryFilters.java:19-32`、`DiscoveryServiceImpl.java:232-243`、`RegistryRepository.java:308-311`。

### FR-DIS-13 serviceId 大小写契约

**陈述**：⚠ legacy 系统实际约定 serviceId 全小写；服务端注册表、订阅表、缓存全链路对 serviceId **大小写敏感**且不做归一化，客户端仅写侧归一小写——混大小写使用会导致查不到、收不到推送、增量静默丢弃。新产品应显式定义大小写契约。

**证据**：原仓库 `RegistryRepository.java:113`、`ServiceChangeWsHandler.java:135`、`ServiceRepository.java:61,72,188`。

## 4. 与其他域的接口

| 域 | 关系 |
|---|---|
| registry-lease | 消费其 NEW / DELETE 事件；变更缓冲的折叠 / 溢出行为决定推送完整性（FR-DIS-05） |
| traffic-governance | GroupDiscoveryFilter 注入 routeRules / logicInstances；元数据变化合成 RELOAD |
| operations-audit | ManagementDiscoveryFilter 剔除 down 实例；摘除合成 DELETE / NEW / RELOAD |
| client-sdk | 传输 / 会话 / 回调线程底座；getService 快照契约（FR-CS-09/10） |
| replication-cluster | up-nodes 端点（地址候选来源）；冷启动全量拉取复用复制端点 |

## 5. 配置项总表（本域）

| 配置键 | 默认 | 关联 FR |
|---|---|---|
| `artemis.service.discovery.versioned-cache.cache-count / cache-refresh.init-delay / interval` | 3 / 60s / 30s | FR-DIS-10 |
| `artemis.service.discovery.notify.thread-count` | 10（1–100） | FR-DIS-04 |
| `artemis.service.discovery.allow-from-other-zone` | false（发布配置 true） | FR-DIS-01/11 |
| `artemis.service.registry.data.instance-change.max-buffer-size / poll-wait` | 10000 / 20ms | FR-DIS-05 |
| 客户端 `.service-discovery.ttl` / 兜底轮询周期 | 15min / 60s | FR-DIS-08 |
| 服务端 WS 会话 TTL / 客户端 WS 会话 TTL | 6min / 5min | FR-DIS-03 |

## 6. 完整性对照

- 端点：`/api/discovery/{lookup,service,services,services-delta}.json` + WS `{instance-change, all-instance-change}` 全覆盖 ✓
- 错误码：service-unavailable（readiness）/ no-permission（zone）/ data-not-found（空 up-nodes、delta 未命中）/ rate-limited（up-nodes）✓
- 事件类型：NEW / DELETE / RELOAD 的产生点全集与 CHANGE 不产生（FR-DIS-06）✓
- 未入本域：up-nodes 实现（replication-cluster）、过滤器实现（traffic-governance / operations-audit）、传输配置（client-sdk §5）

## 更新历史

| 版本 | 日期 | 变更说明 |
| ------ | ------ | ------ |
| 1.0 | 2026-10-08 | 初版 |
