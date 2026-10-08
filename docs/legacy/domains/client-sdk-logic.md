# 客户端 SDK 行为 · 业务逻辑蓝本

状态: 草案  日期: 2026-10-08

> 调研对象：原仓库 `~/Projects/mydotey/artemis`（version 2.0.2，git HEAD 9727bb5）。
> 定位：SDK 基座的完整业务逻辑（实例化 / 配置 / 寻址 / 传输容错 / 回调与克隆 / 线程），作为新产品设计时逐单元评估与优化的蓝本。事实性；取舍依据引用基线 §5/§6，对照见 §8。阅读约定：逻辑单元 L1–L8、决策规则 D1–D4、流程 F1–F5；证据引用约定同 [client-sdk-spec.md](client-sdk-spec.md)。

## 1. 逻辑总览

| 逻辑单元 | 职责 | 关键代码（原仓库） |
|---|---|---|
| L1 接入与实例化 | managerId 单例、懒加载、构造副作用 | `artemis-client/.../ArtemisClientManager.java` |
| L2 配置管线 | 动态 Property 读取（读取时机分类，见 config-reference §0） | `common/AddressRepository.java`、`config/DeploymentConfig.java` |
| L3 地址体系 | 列表刷新 / 随机选址 / 熔断 / TTL 轮换（三级容灾） | `common/{AddressRepository,AddressContext,AddressManager}.java` |
| L4 HTTP 执行器 | 固定重试 + 错误码决策 + 换址 | `common/ArtemisHttpClient.java` |
| L5 WS 会话上下文 | 健康检查 / 重建 / 限流 / 全局容器 | `websocket/WebSocketSessionContext.java` |
| L6 回调与克隆 | 快照克隆 / 路由重算 / 单线程回调 | `discovery/{ServiceContext,ServiceRepository}.java`、`util/RouteRules.java` |
| L7 线程模型 | 每 manager 7+ 线程构成 | 各组件构造器 |
| L8 注册过滤器链 | RegistryFilter SPI 执行 | `registry/InstanceRepository.java:58-71` |

## 2. 概念模型与不变量

- manager = 配置命名空间 + 线程树 + 两套通道（registry / discovery）的聚合根；manager 之间除 StringProperties 可共享外完全隔离（**唯一例外**：JVM 级 WebSocketContainer 全局共享，见 §7.2）。
- 地址三级：引导地址（动态 Property）→ 候选列表（刷新覆盖，失败保留）→ 当前上下文（随机选定，带 TTL 与可用标志）。
- 不变量：I1 候选列表一经成功刷新非空则永不为空（失败不清空）；I2 当前上下文不可用或过期后必然被重选（getContext 原子替换）；I3 回调负载是提交时点的克隆快照（消费者与缓存演化解耦）。

## 3. 状态机

**地址上下文**（AddressContext）：

| 状态 | 迁移条件 | 动作 |
|---|---|---|
| ACTIVE | 构造（随机选址） | 固化 createTime / httpUrl / wsEndpoint |
| ACTIVE → INVALID | markUnavailable（CAS） | 仅作废本上下文，不改候选列表 |
| ACTIVE → EXPIRED | now ≥ createTime + ttl（默认 1h） | 下次 getContext 强制重选 |
| INVALID / EXPIRED → （新 ACTIVE） | 下次 getContext | 重新随机选址并原子替换 |

**WS 会话**：CONNECTING（handshake 异步，future.get 超时 5s）→ ACTIVE（入回调、开始健康检查）→ CLOSED（TTL 5min 到期 / ping 超时 / 对端关闭 / 主动 markdown）→（重连限流通过）→ CONNECTING。

## 4. 决策规则

### D1 地址选择（AddressRepository.get + AddressManager.getContext）

```text
get():        候选列表非空 → 均匀随机取一
              候选列表为空 → 返回引导地址（引导地址也空 → 空 URL）
getContext(): 当前上下文可用且未过期 → 复用
              否则 → newAddressContext()（重新随机）
```

刷新（daemon 线程，默认 5min）：请求仅携带构造时固化的自身 region/zone；成功且列表非空 → 过滤空 URL、去尾 `/`、去重后覆盖候选列表；**失败或返回空 → 保留旧列表**。证据：`AddressRepository.java:52-123`。

### D2 HTTP 重试决策（每轮：重取上下文 → 重建请求 → gzip）

| 情形 | 动作 |
|---|---|
| success / partial_fail / 非 serviceDown 且非 rerunnable 的失败 | 返回上层 |
| 失败且 errorCode ∈ serviceDown | markUnavailable → 下一轮换址 |
| 失败且 errorCode ∈ rerunnable | 重试（不换址） |
| 异常 / null 响应 / null status | markUnavailable → 重试 |
| 末次异常 | 抛原始异常 |
| 末次失败响应 | 抛 RuntimeException（含 responseStatus） |

证据：`ArtemisHttpClient.java:45-95`、`ResponseStatusUtil.java:46-66`。

### D3 WS 健康检查（默认 1s 周期）

```text
checkHealth(): 地址可用 && 会话未过期(ttl 5min) && ping/pong 存活(1s 超时) → 保持
               否则 → connect()
connect():     重连限流(5 次/20s，超限仅记日志放弃) → handshake 异步 + 等待(5s 超时)
               成功 → 关旧会话、替换、回调 afterConnectionEstablished(重订阅)
               失败 → markUnavailable（下次 getContext 换址）
markdown():    熔当前地址 + 立即 checkHealth（供心跳超时 / serviceDown 响应触发）
```

证据：`WebSocketSessionContext.java:105-161,219-244`。

### D4 回调分发（ServiceRepository）

缓存更新（synchronized）→ 按监听者注册顺序 submit（负载 = newService() 克隆快照）→ 单线程 FIFO 执行；listener 异常 catch 仅记日志。证据：`ServiceRepository.java:134-152`。

## 5. 核心流程

### F1 SDK 初始化

`getManager(id, config)`（computeIfAbsent，二次调用忽略新 config）→ 首次 `getRegistryClient()/getDiscoveryClient()`（双检锁）→ 构造组件链。⚠ 阻塞点：AddressRepository 构造函数内**同步执行一次刷新**——引导地址不可达时首次 getClient 调用被一次可能数秒的 HTTP 失败阻塞（超时依赖外部库默认值）。证据：`AddressRepository.java:60`。

### F2 地址生命周期

进程启动 → 引导地址（唯一候选）→ 首次刷新成功 → 候选列表（多节点）→ 随机选址建上下文 → 运行期：熔断（作废重选）/ TTL 到期（强制重选）交替；每 5min 列表刷新纠偏候选池。

### F3 HTTP 失败重试

请求发出 → 失败（异常 / serviceDown）→ markUnavailable → 下一轮 getContext 换址重建请求 → 最多 5 轮 × 100ms → 成功返回 / 抛出。

### F4 WS 会话生命周期

建连（订阅 / 心跳开启）→ 稳态（健康检查通过）→ 异常（服务端 6min 强关 / 网络断 / ping 超时）→ checkHealth 判死 → connect（限流）→ 重连成功 → afterConnectionEstablished 重发全部订阅（发现）/ 续传心跳（注册）。

### F5 进程退出

⚠ 无任何受控退出路径：无 close API；回调 executor 线程 **non-daemon** 且无 shutdown → 阻止 JVM 退出；`WebSocketSessionContext.shutdown()` 已定义但全仓库无调用者。宿主只能依赖 exit 时 daemon 线程消亡 + non-daemon 线程被强杀或阻塞。

## 6. 并发与时序

| 场景 | 机制 | 结果 |
|---|---|---|
| 心跳发送期间会话被替换 | sendHeartbeat 两次 `get()` 之间会话可能换（`InstanceRegistry.java:135,149`） | 旧会话发送异常被 catch，下轮心跳走新会话（良性竞态） |
| getService 与缓存更新并发 | newService 在 synchronized 内构建快照 | 调用方拿到一致版本快照 |
| 多 manager 并存 | WebSocketContainer 为 JVM 全局单例，构造时 setDefaultMaxTextMessageBufferSize | 后构造者覆盖先者的 buffer 值（跨 manager 污染） |
| 回调慢消费者 | 单线程无界队列 | 任务（含整服务克隆）堆积，OOM 风险 + 全局变更延迟 |

时序参数：列表刷新 5min / 上下文 TTL 1h / WS 会话 TTL 5min / 健康检查 1s / ping 超时 1s / 连接超时 5s / 重连限流 5 次每 20s / HTTP 重试 5 × 100ms / 心跳 5s。

## 7. 边界与已知缺陷（事实清单）

1. **回调线程 non-daemon 且无 shutdown**：`ServiceRepository.java:37` 用 `Executors.newSingleThreadExecutor()`（defaultThreadFactory）——阻止 JVM 退出；基线「每 manager 7+ daemon 线程」表述不准确（回调线程不是 daemon）。`WebSocketSessionContext.shutdown()` 为无调用者的死代码。（新发现）
2. **WebSocketContainer JVM 级全局单例**：`WebSocketSessionContext.java:63-64` 构造时设置全局 buffer 上限——多 manager（或多 SDK 实例）互相覆盖，后构造者赢。（新发现）
3. **首次 getService 失败静默返回空 Service**：`ServiceRepository.java:110-132` 异常仅记日志，缓存空 ServiceContext；serviceId 空白同样返回空 Service 不抛错——调用方拿到「看似正常的空服务」，必须自判实例空。（新发现）
4. **熔断不排除地址**：`AddressContext.java:69-73` 仅作废当前上下文，候选列表不动——坏节点无冷却期、无失败计数，可能被反复随机选中，容错压在 HTTP 重试换址上。（新发现；基线 §5.2 容灾管线的实际漏洞）
5. **构造期同步刷新阻塞**：`AddressRepository.java:60`——首次 getClient 被 HTTP 失败阻塞，超时不受 SDK 配置控制。（新发现）
6. **Instance 元素共享引用**：`Service.clone` 复制 List 容器但不克隆元素——宿主改 Instance 字段污染 SDK 缓存。（新发现）
7. **buffer-size 构造快照**：`.websocket-session.text-message.buffer-size` 构造时一次性生效，此后变更无效（叠加 §7.2 全局单例问题）。
8. **地址刷新线程同名**：registry/discovery 两个刷新线程同名 `{clientId}.address-repository`，线程 dump 无法区分。（新发现）
9. **引导地址缺失无诊断**：地址上下文为空 URL + 不可用，后续报错不提示「配置缺失」，仅有刷新 error 日志。

## 8. 逻辑单元 × 基线资产 / 局限对照

| 逻辑单元 | 基线对照 | 说明 |
|---|---|---|
| L3 | 资产 §5.2（三级地址容灾） | 管线结构值得继承；§7.4 表明熔断语义有洞 |
| L4 | 资产 §5.3（错误码驱动容错） | ErrorCodes 单一事实源 |
| L5 | 资产 §5.8（会话 TTL 轮换 + 重连限流） | 防漂移与防风暴双重治理 |
| L7 | 局限 §6.12（线程放大、双套重复） | 每 manager 7+ 线程线性放大 |
| L6 | 局限 §6.13（深克隆 + 回调单线程无界） | 每次 getService 克隆 + 路由重建；无界队列 |
| — | 局限 §6.11（无生命周期 API） | 无 close / 无状态查询；§7.1 non-daemon 加剧 |
| — | 局限 §6.14（无 starter / 多语言） | 纯 Java API，配置源宿主注入 |
| §7.1 / §7.2 / §7.3 / §7.4 / §7.5 | 基线 §6 未收录 | 本次补证新发现，建议纳入局限输入 |
