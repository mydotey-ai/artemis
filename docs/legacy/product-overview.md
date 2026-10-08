# Artemis 原产品总览（Product Overview）

版本: 1.3    更新时间: 2026-10-08

> 调研对象：原仓库 `~/Projects/mydotey/artemis`（version 2.0.2，git HEAD 9727bb5）。
> 定位：**产品级**综合梳理——从整个产品角度看全部业务域、横切主题与端到端场景，是规格层文档集（[domains/](domains/)）的入口与汇总；并汇总规格层补证（2026-10-08）对基线的勘误与新发现缺陷。判断层结论（资产 / 局限）见基线 §5/§6，本文不重复。
> 规格层 = **域文档**（6 域 × spec + logic：回答「怎么运转」）+ **契约制品**（data-model / api-contract / db-schema / client-sdk-api / config-reference：回答「长什么样」），两者合起来构成 1:1 对标复刻的完整蓝本。索引见 [domains/README.md](domains/README.md)。

## 1. 产品定位

AP 型微服务注册中心：对等节点全对全异步复制 + 租约心跳（「心跳即注册」）+ Eureka 式自我保护 + 静态集群成员；**数据面**（注册表，纯内存）与**管理面**（DB 持久化流量治理元数据）双轨分离。差异化价值在流量治理（分组 / 加权路由 / 两段式灰度 / canary / 逻辑实例）。支撑过 10 万+ 服务实例（口头历史）。产品**只生产路由视图、不执行路由**——选址由宿主 RPC 完成。

## 2. 业务域地图

```text
                    ┌────────────────────────────────────────────┐
   服务提供方 ──►   │ client-sdk：接入 / 寻址容灾 / 传输容错 / 回调  │
                    └────────────┬───────────────────────────────┘
                        HTTP/WS  ▼
                    ┌────────────────────────────────────────────┐
   服务消费方 ◄────  │ registry-lease：注册 / 租约 / 剔除 / 自我保护 │◄─┐
                    │ discovery：查询 / 订阅推送 / 兜底 / 版本缓存   │  │ 复制(3消息)
                    └───────┬─────────────────────┬──────────────┘  │ 全对全异步
                            │ 变更事件             │ 过滤器链注入      │
                    ┌───────▼──────────┐  ┌───────▼──────────────┐ │
                    │ replication-     │  │ traffic-governance：  │ │
                    │ cluster：复制/成员│  │ 分组/路由/灰度/canary  │ │
                    │ /readiness/冷启动│  │ operations-audit：    │ │
                    └──────────────────┘  │ 四级摘除/审计/状态API  │ │
                           ▲              └──────────┬───────────┘ │
                           │    共享 DB（管理面事实源）◆─────────────┘
```

| 域 | 一句话职责 | 规格 |
|---|---|---|
| registry-lease | 维护「实例在线」事实：心跳即注册、租约 TTL、失联剔除、自我保护 | [spec](domains/registry-lease-spec.md) / [logic](domains/registry-lease-logic.md) |
| discovery | 消费实例事实：查询、订阅推送（at-most-once）、三层兜底、版本化缓存 | [spec](domains/discovery-spec.md) / [logic](domains/discovery-logic.md) |
| replication-cluster | region 内最终一致：全对全异步复制、静态成员、readiness、冷启动 | [spec](domains/replication-cluster-spec.md) / [logic](domains/replication-cluster-logic.md) |
| traffic-governance | 流量视图控制：分组、加权路由、两段式灰度、canary、逻辑实例 | [spec](domains/traffic-governance-spec.md) / [logic](domains/traffic-governance-logic.md) |
| operations-audit | 不删数据的上下线控制：四级摘除级联、操作记录即状态、审计、状态 API | [spec](domains/operations-audit-spec.md) / [logic](domains/operations-audit-logic.md) |
| client-sdk | 嵌入库基座：manager 单例、三级地址容灾、错误码驱动容错、回调线程 | [spec](domains/client-sdk-spec.md) / [logic](domains/client-sdk-logic.md) |

## 3. 横切主题

### 3.1 错误码语义（`ErrorCodes`，全产品唯一事实源）

| 码 | 语义 | 客户端处置（rerunnable / serviceDown 划分） | 复制处置 |
|---|---|---|---|
| success / partial_fail | 成功 / 部分失败 | 直接返回 | 无失败任务（partial 逐实例处理） |
| bad-request | 请求非法 | 直接返回 | PermanentFail 丢弃 |
| rate-limited | 限流 | 重试 | RateLimited 重试 |
| no-permission | region / zone 准入拒绝 | 直接返回 | PermanentFail 丢弃 |
| data-not-found | 目标不存在（心跳 miss / 空 up-nodes / delta 过旧） | **触发补注册**（心跳场景） | RerunnableFail 重试 |
| internal-service-error / service-unavailable | 节点故障 / 未就绪 | **熔节点换址** | PermanentFail 丢弃 |
| unknown | 未分类（含网络异常） | 重试 | RerunnableFail 重试 |

### 3.2 region / zone 三层模型

- **region = 集群边界**：region 间零同步（多 region = 多独立集群）；全部写路径 region 强校验（复制也不豁免）。
- **zone = 写入准入单位**：默认仅同 zone 可注册 / 可发现（`allow-from-other-zone` 放开）；**不是数据局部性单位**（复制仍 region 全量，zone 参数在拉取端点不生效）。
- 就近逻辑在宿主：服务端不注入就近标记，消费方按 Instance.zoneId + 自身位置执行 close-by-visit。

### 3.3 双轨分离

数据面（注册表，纯内存，零持久化，任一节点全量）与管理面（MySQL/SQLite，20 张表，流量治理元数据 + 审计）完全正交：管理面经三个注入点作用于数据面——发现过滤器链（Group → Management）、推送过滤器（NotificationFilter）、合成事件（复用数据面推送管线）；管理面挂了注册发现照常（`artemis.management.enabled=false` 可整体关闭）。摘除态与租约态正交（operations-audit F4）。

### 3.4 限流体系（caravan RateLimiter，全通道超限返回 rate-limited 而非挂死）

registry 100k / replication 1M / cluster（up-nodes）10k / status 30 / management.group 30（均 10s 窗口）；发现查询通道无限流配置。

### 3.5 观测与安全现状

- 观测：状态 API 是实际排障面（config.json 含配置来源、leases.json 含自我保护统计）；metric / trace 埋点点位完整但开源实现为空壳（NullProvider）；无大盘无告警。
- 安全：全 API 无认证鉴权、明文 http/ws、DB 明文密码；唯一屏障 WS IP 黑名单 + region/zone 软隔离；token 只存不验。**开源形态不可上生产**（新产品必须重立）。

### 3.6 配置体系

三件套（application / artemis / data-source.properties）+ scf 管线（env → system → 文件级联 + IP cascaded）；**127 个键模式**，全量字典见 [domains/config-reference.md](domains/config-reference.md)。

**动态更新能力：三层控制**（一个属性初始化后能否被动态更新，三层须同时允许；详见 config-reference §0）：

1. **源层**——配置源是否发变更事件。**配置源由宿主注入**（各组织配置中心不同，不在产品内）；产品自带的默认三件套（env / sysprops / properties 文件）是**静态源**（构造时一次性 load、无文件监听、不发事件）。
2. **声明层**——`PropertyConfig.isStatic()`（**产品 / 使用方在定义属性时声明**）：默认 `false`（可动态更新）；声明 `true` 时 `DefaultConfigurationManager.onSourceChange` 记 warn 并忽略该变更（"will be applied when app restart"）。**原产品全部属性经便捷方法创建、未用 `isStatic` → 声明层全部允许动态更新。**
3. **使用层**——代码读取时机：

- **动态读**（运行期反复 `getValue()`）——源层 + 声明层满足时即时生效；
- **构造快照**（构造 / 类加载只读一次）——即使源可变也不更新；属**设计选择**（线程数、schedule 周期、初始化容量等无需运行期变更），非声明级 static。

代码中大量 `addChangeListener`（NodeManager / ServiceCluster / SafeChecker）配合动态读，是**为接入动态配置源预置的完整链路**。

> 设计启示（新产品）：把「是否支持动态更新」在**属性定义处显式声明**（如 `isStatic`），好过隐含在代码读取方式中——原产品在声明层留了这个口子但未使用。

## 4. 端到端场景速查（跨域时序）

| 场景 | 链路 | 时延量级 |
|---|---|---|
| 首次注册可发现 | register（本地）→ 心跳 miss → HTTP 补注册 → NEW 事件 → 推送 + 复制 | ≈ 6s（1 心跳间隔 + 补注册） |
| 实例宕机摘除 | 停跳 → 全节点各自 TTL 过期 → clean → DELETE 推送 | ≈ 21s（TTL 20s + clean 1s） |
| 运维摘除生效 | operate → DB → 1s 刷新 → 过滤 + 合成 DELETE 推送 | ≈ 1–3s（API 含 sleep 2s） |
| 灰度权重生效 | 编辑 unreleased → release → 5s 刷新 → reload → 消费方重拉 | ≈ 5–7s |
| 节点故障（客户端视角） | serviceDown → 熔节点 → 换址重试 | 毫秒–秒级（宿主无感） |
| 节点冷启动 | 门控循环 → peer 全量拉取重建 → UP | 数据量相关；空集群**无法自举**（缺陷） |
| 全集群重启 | 注册表零持久化 → 客户端风暴重注册（心跳天然摊批） | 分钟级；无 DB 可救 |
| server 全挂（消费方） | 缓存永不失效 → 继续返回最后快照；重启客户端 = 不可用（无磁盘快照） | — |

## 5. 对基线的勘误汇总（2026-10-08 规格层补证）

以下原仓库代码事实与基线（[legacy-product-analysis.md](legacy-product-analysis.md)）表述不符，域文档已按事实修正；基线已附勘误节：

| # | 基线位置 | 原表述 | 代码事实 | 证据出处 |
|---|---|---|---|---|
| 1 | §2.5 | discoveryData（appid/subenv）随 lookup 上送参与服务端筛选 | **纯协议预留，全链路零消费**（regionId 参数亦被忽略） | traffic-governance §FR-TG-09；两路独立补证 |
| 2 | §2.4 | up-nodes 返回同 zone 优先的节点列表 | 过滤（同 zone 全返回或全放开），**无排序** | discovery FR-DIS-11 |
| 3 | §2.4 | new/delete/change 原地更新 | **CHANGE 类型无任何服务端产生点**（客户端分支等价 NEW） | discovery FR-DIS-06 |
| 4 | §3.4 | 每次 getService 深克隆 | List 壳复制 + **Instance 引用共享** + RouteRules 全量重建（真实开销） | client-sdk FR-CS-09 |
| 5 | §2.8 | 节点列表拉不到降级回引导地址 | 拉取失败**保留旧列表**；仅列表为空才降级 | client-sdk D1 |
| 6 | §2.8 | 客户端配置均可热更 | 缺**源层前提**（见勘误 #18）：声明层全部可动态更新，源由宿主注入 | client-sdk FR-CS-03 |
| 7 | §2.8 | 每 manager 7+ daemon 线程 | 回调 executor 线程 **non-daemon**（且无 shutdown，阻止 JVM 退出） | client-sdk logic §7.1 |
| 8 | §2.7 | TrafficShaper 按错误码退避（默认 10ms） | 默认**空 map = 无退避**（10ms 仅配置项值为 null 时兜底） | replication-cluster logic §7.2 |
| 9 | §2.7 | 失败任务重试插队队首 | 批量通道存在**重试截断缺陷**：一批仅第一个可重试任务被重试，其余静默丢弃 | replication-cluster logic §7.1 |
| 10 | §2.6 | 审计 log 行含操作前后数据快照；可按 token/reason/时间段过滤 | **单快照**（删前 / 写后）；instance/server 日志无快照无 reason；token/reason/时间段**不可过滤** | operations-audit FR-OA-06 |
| 11 | features §3.2（基线未述及） | destroyServers 批量物理删除 | **死代码**（无端点无调用方），且 instance 侧删除条件错位 | operations-audit FR-OA-09 |
| 12 | §2.5 | 管理面统一 5s 重刷 | 两级：instance/server 摘除缓存 1s，group/zone 5s（features 附录已勘误） | operations-audit FR-OA-03 |

**契约层补证追加（2026-10-08 第二批，四份契约制品）**：

| # | 位置 | 原表述 | 代码事实 | 出处 |
|---|---|---|---|---|
| 13 | 基线 §2.1 | `ServiceGroup.weight[0–10000 默认 5]` | 字段默认 **null**；5 仅由 `fixWeight` 展开时施加 | data-model §9 |
| 14 | features §1.5 | `ServiceNodeStatus` 记于 common | 实际在 **artemis-service** 模块 | data-model §9 |
| 15 | features §1.5 | `RegisterType` 记于 common | 实际在 **artemis-client** 模块 | data-model §9 |
| 16 | features §3.8 | 「group 域 16 个 DAO」 | 实为 15 个（总 21 个 DAO 类） | db-schema §8 |
| 17 | 基线 §2.1 / features §1.1 | 未记 JSON 键名与序列化规则 | 两套 mapper；键名 = getter 派生；HTTP 字母序 + 大小写不敏感入参 | data-model §1、client-sdk-api §1 |

**配置层补证追加（2026-10-08 第三批）**：

| # | 位置 | 原表述 | 代码事实 | 出处 |
|---|---|---|---|---|
| 18 | 基线 §2.8 / §3.7、product-overview §3.6、nfr-spec NFR-35/37、features §5.7、client-sdk-spec FR-CS-03 | 「配置**全热更**」（例外仅部署身份、WS buffer-size） | 表述缺**源层前提**：产品声明层全部可动态更新（未用 `isStatic`），但**源由宿主注入**——默认三件套为静态源故不生效；使用层另有构造快照键。**属部署形态差别，非产品能力缺失** | config-reference §0 |
| 19 | features §2.9 | 限流表列 4 个 | 实际 **5 个**（含 `artemis.service.management.group`） | config-reference §2.6 |
| 20 | 全部文档 | 未记依赖库键 `<clientId>.default-request-config` | 该键为**事实死键**（无 value converter，String 源恒返回 null）；Provider 有一组独立默认参数（socketTimeout 10000 等） | config-reference §5.1 |

## 6. 新发现缺陷索引（基线 §6 之外，重设计负输入补充）

| 缺陷 | 域 | 位置 |
|---|---|---|
| 大小写键语义分裂 → 永不清理的发现残留 | registry-lease | logic §7.1 |
| 变更缓冲按实例折叠丢事件 / Comparator 违反契约 | registry-lease / discovery | 两域 §7 |
| WS 心跳解析异常回固定 success（错误吞掉） | registry-lease | logic §7.5 |
| 自我保护不覆盖显式下线（语义边界需重申） | registry-lease | logic §7.4 |
| 批量复制失败重试截断 | replication-cluster | logic §7.1 |
| 冷启动空集群死锁（空数据拒绝就绪） | replication-cluster | logic §7.3 |
| force-up 跳过全部初始同步；UP 无回退；DOWN 粘性；UP 后平面不可摘 | replication-cluster | logic §7.4–7.6 |
| 成员清空时视图陈旧残留；定向重试不看成员表 | replication-cluster | logic §7.7–7.8 |
| 复制 register/unregister 与节点探测无显式超时；节点探测**未设 socket timeout**（200ms 属性名被另一方法使用，命名与用途不符） | replication-cluster | logic §7.9、[arch/runtime](arch/runtime.md) §4.1 |
| **批量复制通道的缓冲保护失效**：`BatchingTaskAcceptor._pendingTaskCount` 遮蔽父类同名字段，父类计数只减不加 → `buffer-full-dropped` 在心跳复制通道上**不触发**（单条通道正常） | replication-cluster | [arch/runtime](arch/runtime.md) §2.2 |
| DISCOVERY 就绪门控**不含 ZoneRepository**（虽被 init，但不参与门控判定） | replication-cluster | [arch/runtime](arch/runtime.md) §3.2 |
| 摘除判定**只看 operation 行是否存在，与取值无关** → 恢复只能删行，无法用反向记录抵消 | operations-audit | [arch/runtime](arch/runtime.md) §4.3 |
| zone 摘除推送盲区（同服务第二条记录不触发推送） | operations-audit | logic §7.1 |
| 合成事件 server 级只按 IP 匹配（跨 region 虚假 DELETE 且被放行） | operations-audit | logic §7.2 |
| 恢复单条性 + operation 无枚举校验 | operations-audit | logic §7.3–7.4 |
| SQLite 分支破坏两段式发布（编辑清空已发布权重） | traffic-governance | logic §7.1 |
| 摘除过滤不作用于路由视图成员（down 实例经 canary 可见） | traffic-governance | logic §7.2 |
| 保留规则名无服务端保护；publish/activate 无端点半成品 | traffic-governance | logic §7.3–7.4 |
| GroupDiscoveryFilter 并发写共享缓存；同名 canary 只展开第一条 | traffic-governance | logic §7.5/7.7 |
| 回调线程 non-daemon 阻止 JVM 退出；WebSocketContainer 全局单例多 manager 互相覆盖 | client-sdk | logic §7.1–7.2 |
| 首次 getService 失败静默返回空 Service；熔断不排除坏地址；构造期同步刷新阻塞 | client-sdk | logic §7.3–7.5 |
| ServiceRepository 单锁串行化三热点（慢查询阻塞推送落地） | discovery | logic §7.6 |
| serviceId 大小写全链路敏感（系统约定全小写但无强制） | discovery | FR-DIS-13 |

### 6.1 契约层补证新发现（2026-10-08 第二批）

四份契约制品调查中发现的实现级缺陷（此前未记录），均带行级证据：

| 缺陷 | 制品 |
|---|---|
| `RouteRule.clone()` **丢弃 `groups`**（克隆体只剩 routeId + strategy） | data-model §8.2 |
| `Service.clone()` **完全不拷贝 `routeRules`**（克隆体与本体共享 list，改克隆体污染本体） | data-model §8.2 |
| `ServiceNodeStatus.equals` 漏两个 `allow*` 字段、`hashCode` 含全部 6 个——**契约不一致** | data-model §5 |
| `GetServicesDeltaResponse.delta` 的 Map 键为 `Service` 对象——序列化/反序列化**往返隐患**（Jackson 无法从字符串还原键） | data-model §4.2 |
| `Instance.Status` 的 `starting`/`unhealthy`/`unknown` **零引用（死值）**；`ResponseStatus.Status.UKNOWN` 拼写错误且零引用 | data-model §7 |
| `ArtemisClientManagerConfig` 第 4 构造**丢弃入参**（DiscoveryClientConfig 永不生效） | client-sdk-api §6 |
| `ArtemisClientManager` 双检锁字段**未加 volatile** | client-sdk-api §6 |
| `RegistryClientImpl#unregister` **重复校验**两次；`UnregisterResponse` 字段名拼写错误 | client-sdk-api §6 |
| `SQLITE_SETUP.md` 宣称「首次启动自动建表」——生产代码**零建表逻辑**（仅测试建表） | db-schema §8 |
| `COMPLETE` 列 **DDL 注释与代码语义相反**（注释 true=未完成，代码 true=已完成） | db-schema §5 |
| DDL↔DAO **10 处不一致**（service_group_log 五列从不落库、zone 日志 reason 不落库、route_rule_group_log.WEIGHT 实为 unreleasedWeight、service_group.type 恒默认、索引顺序反了…） | db-schema §6 |
| `management/GetServiceRequest` 构造器 **regionId/zoneId 写反** | api-contract §2.A.10 |
| leases 端点 **GET 参数名 `appIds` vs body 字段名 `serviceIds` 不一致**；`LeaseStatus.evitionTime` 拼写错误 | api-contract §1.D |
| `GroupInstance` 用 public 字段；`DeleteGroupsInstancesRequest` 类名单复数错位；`service-instance` insert 返回异类 `OperationResponse` | api-contract §2.B |
| 无全局异常处理——畸形 JSON 请求返回框架默认 400，**不是** `ResponseStatus` 结构 | client-sdk-api §5 |

**架构视图补证追加（2026-10-08 第四批）**：

| # | 位置 | 原表述 | 代码事实 | 出处 |
|---|---|---|---|---|
| 21 | 基线 §1 模块依赖树、原 arch.md §2 依赖链 | 依赖画成线性链 `common → service → management → server → package` | `artemis-server` **同时** compile 依赖 `artemis-service` 与 `artemis-management`（pom 首两项）——是 DAG 而非链；基线 §1 的树形缩进图无法表达该直连边 | [arch/structure](arch/structure.md) §5.1 |
| 22 | 基线 §3.1 | 「10 万实例 × TTL 20s ≈ 5k 心跳/s 原生流量」 | 以 **TTL** 当心跳周期计算有误：默认心跳间隔为 **5s**，且心跳**按 manager（进程）聚合**为一条消息（payload = 该进程全量实例集），消息率自变量是**进程数**而非实例数 | [arch/quality](arch/quality.md) §5.2 |
| 23 | 原 arch.md §7 / 基线 §3.4 | 「WS 8KB 缓冲上限」未区分侧别 | 8KB 是**客户端**的 incoming text message 上限（容器级，构造期一次生效）；**服务端无对应配置**（落 Tomcat 默认） | [arch/runtime](arch/runtime.md) §6.2 |
| 24 | 原 arch.md §1 / §2 | 「管理面通过三个注入点作用于数据面」；「artemis-management 依赖 spring-jdbc/context」 | 管理面共 **5 个接入点**（其中 3 个作用于数据面，与原表述一致，另 2 个为启动门控参与与管理面内部 filter 链）；management 的 `spring-jdbc` 为 compile（当 JDBC 工具库），`spring-context`/`spring-webmvc` 为 **test scope**——两者并列易误读 | [arch/structure](arch/structure.md) §3.4、§4.5 |
| 25 | 基线 §1 | 逐模块文件数 common 95 / client 27、test 52 | 实测 **common 93 / client 28、test 51**（`main` 总数 408 与基线一致，差异在逐模块计数口径） | [arch/structure](arch/structure.md) §5.1 |
| 26 | 基线 §3.1 | 「10 万实例 × TTL 20s ≈ 5k 心跳/s 原生流量 ×(N−1) peer」 | `×(N−1)` 方向正确，但基数把 **TTL 当心跳周期**（默认 5s）且按**实例数**计——心跳按 manager 聚合，自变量是**进程数**。修正后的模型见 [arch/quality](arch/quality.md) §5.2 | [arch/quality](arch/quality.md) §5.2 |
| 27 | 基线 §3.6 | 「ErrorCodes 定义了 no-permission 但无实现」 | 该码**有产出点**（region / zone 准入：`RegistryTool`、`DiscoveryServiceImpl`、`RegistryReplicationServiceImpl`）；基线的「无实现」指无**鉴权**实现，不是无产出 | [arch/quality](arch/quality.md) §1.1 |

配置层（2026-10-08 第三批）：

| 缺陷 / 事实 | 制品 |
|---|---|
| 动态更新能力为**三层模型**（源 / 声明 `isStatic` / 读取时机）——原表述只提「读取时机」一层，缺源层与声明层 | config-reference §0 |
| `<clientId>.default-request-config` 为**事实死键**（无 value converter，String 源恒返回 null）——依赖库键无法经配置修改 | config-reference §5.1 |
| 发布配置 5 个死键（`replicaton` 拼写 ×3、`lease-manager.thread-pool-size` ×2）→ 实际生效线程数 = 代码默认 20 | config-reference §7.1 |
| 100+ 个键代码读取但发布配置未提供（全靠代码默认） | config-reference §7.2 |
| 限流器实际 5 个（`features` 旧表列 4 个，遗漏 `artemis.service.management.group`） | config-reference §2.6 |

## 7. 待验证清单（复刻的能力空洞）

1:1 复刻时以下条目**尚无代码级结论**，须按类处理：

**A. 外部依赖不可取证**（`org.mydotey.{codec,rpc,java,lang}` 系列源码不在原仓库、`~/.m2` 亦无）——须引入依赖源码或**实机抓包/实验**确认：

| 事项 | 影响 | 出处 |
|---|---|---|
| `JacksonJsonCodec.DEFAULT` 的 feature / 命名策略 / null 包含策略 / 键序 | **WS 通道报文的确切字节**（与 HTTP REST 的字母序可能不同） | data-model §8.4、client-sdk-api §1 |
| `FileExtension.concatPathParts` 的边界归一化 | groupKey 前缀匹配的精确行为 | data-model §7.10 |
| `HttpRequestFactory` / `HttpRequestExecutors` 的响应 gzip 解压与 charset 处理 | HTTP 全链路压缩的兼容性 | client-sdk-api §5 |
| `ObjectExtension.requireNonNull` 的精确异常类型 | SDK 异常契约 | client-sdk-api §2.5 |

**B. 原仓库内可进一步取证**（本轮未展开，属可解项）：

| 事项 | 出处 |
|---|---|
| `ServiceNodeUtil.isUp/isDown` 的比较实现（字符串 equals 还是 equalsIgnoreCase） | data-model §8.4 |
| `management/GetServiceRequest` 构造器 regionId/zoneId 写反对 **GET 绑定**的实际影响 | api-contract §2.A.10 |

**C. 历史与实测类**：

| 事项 | 说明 | 出处 |
|---|---|---|
| 10 万+ 实例实绩 | 口头历史，仓库内无压测报告/数据 | nfr-spec NFR-1 |
| 10 万实例下管理查询可用性 | 无分页为既定事实；实际退化程度需实测 | operations-audit §6 |
| `destroyServers` 的历史调用方 | 需对比 1.5.x tag | operations-audit FR-OA-09 |
| shipped `thread-pool-size` 是否曾为有效键 | 需对比 1.5.x tag（2.0.2 判定为死键） | nfr-spec NFR-41 |
| 生产是否在引导地址前置 LB | 不可从代码证实 | [arch/deployment](arch/deployment.md) §6 |
| HTTP 心跳端点的旧代客户端用途 | 已判为死端点；为旧客户端保留的推断未证实 | registry-lease logic §7.7 |

## 8. 旧文档处置

- [legacy-product-analysis.md](legacy-product-analysis.md)：**保留**为判断层（资产 / 局限，项目规则引用其 §5/§6）；已附 §8 勘误与增补。
- [features.md](features.md)：**保留**为事实层实现清单（端点 / 配置 / 类级行为的取证附录），域文档的证据索引。
- [arch.md](arch.md) 与 [arch/](arch/README.md)：**保留**为事实层架构视图。arch.md 为总览入口（风格判定 / 部署全景 / 视图地图 / 关键架构约束），详细视图拆入 arch/ 目录（结构 / 运行时 / 部署 / 质量与容量 / 决策记录），与域文档（行为 / 逻辑视角）互补。
- non-features.md（NFR 反推中间稿）：**已删除**（内容并入 [nfr-spec.md](nfr-spec.md) 与各域 spec 配置表）。

## 更新历史

| 版本 | 日期 | 变更说明 |
| ------ | ------ | ------ |
| 1.3 | 2026-10-08 | §5 新增勘误 #25–27（文件数 / 心跳率口径 / no-permission）；§7 LB 指针改指 arch/deployment |
| 1.2 | 2026-10-08 | 架构视图补证：§5 新增勘误 #21–24，§6 新增缺陷 4 条（批量复制缓冲保护失效 / 探测无超时 / Zone 不入门控 / 摘除只看行存在） |
| 1.1 | 2026-10-08 | §8 旧文档处置同步架构视图拆分（arch.md 为总览入口，详细视图入 arch/ 目录） |
| 1.0 | 2026-10-08 | 初版 |
