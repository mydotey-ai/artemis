# 客户端 SDK 行为 · 功能规格

状态: 草案  日期: 2026-10-08

> 调研对象：原仓库 `~/Projects/mydotey/artemis`（version 2.0.2，git HEAD 9727bb5）。
> 定位：以需求语言规格化原产品客户端 SDK 作为**嵌入库**的行为契约（接入模型 / 配置 / 地址容灾 / 传输容错 / 线程与回调 / 生命周期），作为新产品需求设计输入。注册与发现的**业务语义**分别见 [registry-lease-spec.md](registry-lease-spec.md) 与 [discovery-spec.md](discovery-spec.md)，本文只管 SDK 基座。
> 证据引用：前缀「原仓库」= 相对原仓库根路径；`...` = `src/main/java/org/mydotey/artemis` 包路径缩写；`基线 §x` = [legacy-product-analysis.md](../legacy-product-analysis.md)。标 **⚠ legacy** 为原产品特有行为或包袱；缺陷性行为见同域 logic.md §7。

## 1. 域定位

SDK 是嵌入宿主进程的纯 Java 库，职责：向服务端集群提供稳定接入点（寻址 / 容灾 / 容错），封装注册心跳与发现订阅的传输细节，向宿主暴露极简同步 API。**设计立场：传输不可靠是常态，SDK 负责自动收敛；宿主只面对数据，不面对网络。**

角色：**宿主应用**（业务进程）经 SDK 注册实例、消费发现结果。

## 2. 概念与术语

| 概念 | 定义 |
|---|---|
| manager | SDK 实例单元，以 managerId 标识；一个宿主进程可持多个 manager（多服务身份） |
| 引导地址 | 配置的集群入口 URL（`.service.domain.url`），冷启动与降级用 |
| 可用节点列表 | 周期从集群拉取的存活节点 URL 集，随机选址的候选池 |
| 地址上下文（AddressContext） | 当前选定的节点绑定（httpUrl + wsEndpoint + 可用标志 + TTL） |
| 熔断（markUnavailable） | 将当前地址上下文标记不可用，下次访问强制重选址 |
| 会话强制重建 | WS 连接达到 TTL 即主动断开重连，防止长期漂留单点 |

## 3. 功能需求

### FR-CS-01 接入模型

**陈述**：SDK 应以 managerId 为键提供进程内单例 manager；注册面与发现面客户端应懒加载、相互独立。

**规则**：
- `getManager(managerId, config)` 首次调用创建并缓存 manager；**同一 managerId 再次调用无论传入何种 config，新配置被静默忽略**，返回首建实例（仅 managerId 空白 / config 为 null 抛 IllegalArgumentException）。
- managerId 的唯一作用 = 配置命名空间（前缀 `artemis.client.{managerId}.*`）+ 单例键。
- `getRegistryClient()` / `getDiscoveryClient()` 懒加载（双检锁）：只取 discovery 的进程不起心跳线程，反之亦然——线程随用到的面按需创建。
- 多 manager 共享同一配置源实例（StringProperties）是同进程多服务身份的预期用法。

**验收标准**：仅使用发现能力的进程，线程清单中不出现心跳检查线程。

**证据**：原仓库 `artemis-client/.../ArtemisClientManager.java:30,34-71`。

### FR-CS-02 公开 API 面与生命周期

**陈述**：SDK 公开 API 面应为最小集：注册 / 注销、发现查询 / 变更监听、注册过滤器扩展点。

**规则**：宿主可用 API 全集——`ArtemisClientManager.getManager/getRegistryClient/getDiscoveryClient/getManagerId/getManagerConfig`；`RegistryClient.register(Instance...) / unregister(Instance...)`；`DiscoveryClient.getService(DiscoveryConfig) / registerServiceChangeListener(config, listener)`；`RegistryFilter` SPI。入参校验：instanceId / serviceId / url 三者必填，否则 IllegalArgumentException。
- ⚠ legacy：**无 close / shutdown、无状态查询、无 unregisterListener、无同步 flush**——SDK 一旦启动无法优雅停止。

**证据**：原仓库 `artemis-client/.../ArtemisClientManager.java:34-71`、`registry/RegistryClient.java`、`discovery/DiscoveryClient.java`、`common/Conditions.java:9-27`。

### FR-CS-03 配置模型

**陈述**：全部运行参数应以前缀 `artemis.client.{managerId}.*` 配置；系统须明确每个键的**读取时机**（动态读 / 构造快照）。

**规则**：
- 配置以动态 Property 持有、读值时解析。
- **动态更新取决于三层**（[config-reference.md](config-reference.md) §0）：**源层**（配置源由宿主注入，可静态可动态）+ **声明层**（scf `PropertyConfig.isStatic`，默认 false 即可动态更新）+ **使用层**（本 SDK 的读取时机：**动态读** vs **构造快照**——后者即便源可变也不更新，如 `.websocket-session.text-message.buffer-size`、部署身份全部字段、各类 `.dynamic-scheduled-thread.init-delay`）。逐键读取时机见 [config-reference.md](config-reference.md) §5。

**证据**：原仓库 `artemis-client/.../common/AddressRepository.java:53-57`、`websocket/WebSocketSessionContext.java:60-64`；配置总表见 §5。

### FR-CS-04 部署身份快照

**陈述**：SDK 应在类加载时一次性快照部署身份，运行期不再变化。

**规则**：
- 身份字段：region.id / zone.id / app.id / app.path（无默认）、app.port（默认 8080，1–65535 校验，非法回退默认）、app.protocol（默认 http）；IP / 主机名自动探测（NetworkInterfaceManager）。
- 快照用于 up-nodes 请求与 lookup 请求携带 region/zone；**SDK 不自动以身份填充 Instance 字段**——注册实例的 region/zone 等由宿主自填。

**证据**：原仓库 `artemis-common/.../config/DeploymentConfig.java:38-88`、`artemis-client/.../common/AddressRepository.java:52`。

### FR-CS-05 三级地址容灾

**陈述**：SDK 应以三级管线维持可用服务端地址：引导地址 → 周期拉取的存活节点列表 → 随机选址 + 熔断 + TTL 强制轮换。

**规则**：
- 构造时同步执行一次节点列表刷新，随后 daemon 线程周期刷新（默认 5min，1–30min）；请求只携带客户端自身 region/zone。
- 刷新成功且列表非空 → 覆盖候选列表（过滤空 URL、去尾 `/`、去重）；**刷新失败或返回空时保留旧列表继续使用**——只有候选列表当前为空才降级回引导地址。
- 选址：候选列表均匀随机取一；当前地址上下文不可用（被熔断）或超 TTL（默认 1h）→ 重新随机选址。
- ⚠ legacy：熔断**不将地址移出候选池**（无冷却期、无失败计数）——坏节点在被下一轮列表刷新覆盖前可能被反复选中，容错依赖 HTTP 重试换址兜底。

**验收标准**：
1. 服务端单节点宕机：已建连接的请求失败 → 熔断 → 下次访问换节点，宿主无感知。
2. 节点列表服务整体不可达：SDK 继续使用旧列表 / 引导地址，恢复后自动更新。

**证据**：原仓库 `artemis-client/.../common/AddressRepository.java:52-123`、`AddressContext.java:22,36-73`、`AddressManager.java:26-63`；基线 §5.2。

### FR-CS-06 统一 HTTP 执行器

**陈述**：全部 HTTP 请求应经统一执行器：固定次数重试 + 错误码驱动决策 + 全链路 gzip。

**规则**（决策表，按序）：

| 情形 | 动作 |
|---|---|
| 响应 success / partial_fail / 其他非 serviceDown 且非 rerunnable 的失败 | 直接返回上层 |
| 失败且 errorCode ∈ serviceDown 集（internal-service-error / service-unavailable） | 熔断当前地址 → 重试 |
| 失败且 errorCode ∈ rerunnable 集（rate-limited / unknown） | 重试 |
| 异常 / 响应为 null / status 为 null | 熔断当前地址 → 重试 |
| 末次仍异常 | 抛原始异常 |
| 末次为失败响应 | 抛 RuntimeException（含 responseStatus） |

- 重试次数默认 5（1–10）、间隔默认 100ms（0–1000ms），动态可配；每轮重取地址上下文（可能换址）并重新序列化 + gzip 请求体。
- 错误码集合的唯一事实源为 `ErrorCodes`（serviceDown / rerunnable 划分）。

**验收标准**：单节点返回 service-unavailable（如启动门控未过）时，请求自动换节点成功，宿主无感知。

**证据**：原仓库 `artemis-client/.../common/ArtemisHttpClient.java:45-95`、`artemis-common/.../util/ResponseStatusUtil.java:46-66`、`ErrorCodes.java:28-33`；基线 §5.3。

### FR-CS-07 WebSocket 会话生命周期

**陈述**：WS 长连接应具备健康检查、强制重建与重连限流三重治理。

**规则**：
- 健康检查（默认 1s 周期）：地址可用 && 会话未超 TTL（默认 5min）&& ping/pong 存活（默认 1s 超时）——任一不满足即重连。
- 连接：握手异步 + 超时等待（默认 5s）；成功后替换并关闭旧会话，触发重订阅回调；失败熔断地址。
- 重连限流：默认 5 次 / 20s 窗口，超限仅记日志放弃本轮（防重连风暴）。
- markdown（强制重建）= 熔地址 + 立即健康检查，供心跳超时 / serviceDown 响应触发。

**⚠ legacy**：文本缓冲 `buffer-size` 默认 8KB（8–32）在构造时一次性设置，且写入 **JVM 全局共享的 WebSocketContainer**——同进程多 manager 互相覆盖，后构造者赢。

**验收标准**：服务端强制关闭连接（会话 TTL）后，客户端在健康检查周期内重建并完成重订阅，订阅数据不丢（经重连后全量推送 / 重拉收敛）。

**证据**：原仓库 `artemis-client/.../websocket/WebSocketSessionContext.java:54-64,105-161,219-244`；基线 §5.8。

### FR-CS-08 registry / discovery 双通道独立性

**陈述**：注册与发现应各自维护独立的地址体系、WS 会话与线程（互不影响故障域），配置上明确共享面。

**规则**：
- 独立：各自 AddressRepository（各自拉 up-registry-nodes / up-discovery-nodes）、各自熔断与轮换、各自 WS 会话。
- 共享：引导地址 key（同一个 `.service.domain.url`）、`.websocket-session.*` 配置族（两通道一套 key）、HTTP 重试配置按通道区分（`.registry.http-client.*` / `.discovery.http-client.*`）。

**⚠ legacy**：两个地址刷新线程**同名**（`{clientId}.address-repository`），线程 dump 无法区分通道。

**证据**：原仓库 `artemis-client/.../common/AddressManager.java:37-63`、`registry/InstanceRegistry.java:61`、`discovery/ServiceDiscovery.java:48`。

### FR-CS-09 发现快照契约

**陈述**：`getService` 应每次返回一致性克隆快照，保证宿主持有的数据与后续变更互不干扰。

**规则**：
- 快照 = Service 浅拷贝 + metadata Map 复制 + instances / logicInstances **List 容器复制** + routeRules 全量重建（每次重建路由索引，O(实例数 × 组数)）。
- 快照构建与缓存更新互斥（synchronized），保证单次调用看到的是一致版本。

**⚠ legacy**：List 内 **Instance 元素为共享引用**（不逐个 clone）——宿主直接修改 Instance 字段会污染 SDK 缓存；每次调用的深克隆 + 路由重建是已知性能热点（大服务高频读）。

**证据**：原仓库 `artemis-client/.../discovery/ServiceContext.java:44-48`、`artemis-common/.../Service.java:97-120`、`util/RouteRules.java:29-88`。

### FR-CS-10 变更回调交付语义

**陈述**：变更回调应经单线程队列异步交付，保证有序与隔离。

**规则**：
- 顺序保证：同一服务多次变更有序；跨服务也全局串行（单线程 FIFO）。
- 负载为提交时点的克隆快照（慢消费者读到的是当时的完整 Service，不会读到过期中间态）。
- listener 抛异常被捕获仅记日志，不影响后续任务与其他监听者。

**⚠ legacy**：队列为**单线程 + 无界**——一个慢消费者使任务（每任务含整服务克隆）无界堆积，OOM 风险 + 全部服务变更延迟。

**证据**：原仓库 `artemis-client/.../discovery/ServiceRepository.java:37,134-152,181-216`。

### FR-CS-11 RegistryFilter SPI

**陈述**：SDK 应提供注册前实例过滤扩展点，过滤结果只影响发往服务端的数据，不影响本地注册集。

**规则**：
- 调用点：每次心跳消息构造前（全量本地实例过滤，过滤后为空则本次不发心跳）+ HTTP 补注册前（为空则跳过补注册）。
- 执行：按注册时传入的 List **插入顺序串行**，filter 可增删改列表（操作副本，不回写本地集）；单个 filter 抛异常仅告警并继续后续 filter；每次记录 filter 耗时指标。

**证据**：原仓库 `artemis-client/.../registry/InstanceRepository.java:58-71,83-109`。

### FR-CS-12 失败语义与可见性

**陈述**：SDK 的失败语义应以「静默收敛」为默认，并因此承担相应的可见性责任。

**规则**：
- ⚠ legacy：首次 `getService` 拉取失败被静默吞掉，缓存并返回**空 Service 对象**（非 null、非异常）——调用方必须自行判空实例列表；恢复依赖 60s 兜底轮询与 WS 推送。serviceId 空白同样返回空 Service 而非抛错。
- ⚠ legacy：`register()` 返回**不代表已注册**（注册靠下一轮心跳 + 对账，延迟 ≈ 心跳间隔 + 检查周期）；`unregister()` 则是同步远程调用，立即生效——注销比注册「更快」，可用于优雅下线（跨域引用 registry-lease FR-RL-01/08）。
- ⚠ legacy：引导地址未配置时地址上下文为空 URL + 不可用，后续报错信息不提示「配置缺失」。
- SDK 不向宿主暴露任何运行状态（连接状态 / 当前注册实例列表 / 数据新鲜度）。

**证据**：原仓库 `artemis-client/.../discovery/ServiceRepository.java:79-132`、`registry/InstanceRepository.java:111-126`、`common/AddressContext.java:38-46`。

## 4. 与其他域的接口

| 域 | 关系 |
|---|---|
| registry-lease | 心跳 / 补注册 / 注销的业务语义跑在本域传输设施上 |
| discovery | 订阅协议 / 缓存更新决策 / 三层兜底为发现域语义；快照与回调契约（FR-CS-09/10）是其实装底座 |
| replication-cluster | up-nodes 端点（集群域供给）是本域地址体系的数据源 |

## 5. 配置项总表（前缀 `artemis.client.{managerId}`）

| key | 默认（范围） | 读取时机 | 关联 FR |
|---|---|---|---|
| `.service.domain.url` | ""（必配） | 是 | FR-CS-05 |
| `.address-repository`（列表刷新周期） | 5min（1–30min） | 是 | FR-CS-05 |
| `.address.context-ttl` | 1h（1min–24h） | 是 | FR-CS-05 |
| `.registry` / `.discovery` `.http-client.retry-times / retry-interval` | 5（1–10）/ 100ms（0–1000ms） | 是 | FR-CS-06 |
| `.instance-registry.heartbeat-interval` | 5s（500ms–5min） | 是 | registry-lease |
| `.instance-registry.instance-ttl` | 20s（5s–24h） | 是 | registry-lease |
| `.instance-registry.heartbeat-checker` | 1s（500ms–90s） | 是 | registry-lease |
| `.service-discovery.ttl` | 15min（1min–24h） | 是 | discovery |
| `.service-discovery`（兜底轮询周期） | 60s（60s–24h） | 是 | discovery |
| `.websocket-session.ttl / connect-timeout / ping-timeout` | 5min（5–30min）/ 5s（1–30s）/ 1s（50ms–10s） | 是 | FR-CS-07 |
| `.websocket-session.text-message.buffer-size` | 8KB（8–32） | **否** | FR-CS-07 ⚠ |
| `.websocket-session.reconnect-times` | 5 次（3–60）/ 20s 窗口 | 是 | FR-CS-07 |
| `.websocket-session.health-check` | 1s（100ms–10min） | 是 | FR-CS-07 |

## 6. 完整性对照

- 公开 API 面：FR-CS-01/02/11 全覆盖（manager 5 方法 + registry 2 + discovery 2 + listener/event + RegistryFilter）✓
- 配置：客户端全部 key 见 §5（含读取时机标注）；全量字典见 [config-reference.md](config-reference.md) §5 ✓
- 线程：logic.md §L7 清单（7+ 线程构成）✓
- 未入本域：心跳 / 补注册业务决策（registry-lease D5/D6）、发现缓存更新与兜底（discovery 域）
