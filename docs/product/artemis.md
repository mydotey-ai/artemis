# Artemis 产品文档

版本: 1.29    更新时间: 2026-10-26

> 本文是 Artemis 的产品文档：产品定位、核心概念、开发者入口与当前交付状态。随版本递补——每个 phase 收尾时同步本版交付的对外可见能力。产品规划与版本路线见[路线图](artemis-next-roadmap.md)，架构与设计决策见[架构设计](../arch/artemis-next-architecture.md)。

## 1. 产品定位

开源服务注册中心，对标 Nacos：具备原产品（支撑过 10 万+ 实例）的核心能力并现代化，单集群 10 万实例级。成功标准与迭代策略见[路线图 §1](artemis-next-roadmap.md#1-定位与成功标准)。

## 2. 核心概念

- **服务注册与发现**：实例向注册中心注册并周期心跳续约，消费方订阅获取服务实例集。
- **namespace**：多租户隔离单元，服务的内禀属性。
- **服务与实例标识**：服务由 `(namespace, service)` 标识（`ServiceKey`）；实例由 `instance_id` 在服务内标识（`InstanceKey`）。
- **实例状态**：客户端自报（服务端不做主动探测）；实例数据由客户端上报，服务端只存事实。
- **投影分发（registry → discovery）**：registry 向 discovery 推送实例事实（全量快照 + 新增/变更/移除三值变更流）；discovery 侧投影是只读副本，discovery 不组集群。
- **多集群聚合**：单 discovery 节点可同时订阅多个 registry 集群，各源投影本地独立维护、查询/订阅时刻合并（默认 union，可按来源 cluster 过滤）；聚合只发生在本地，无跨源污染（ADR-002）。
- **逻辑实例（静态实例）**：第三方/异构服务（不经注册体系）由管理面维护为静态实例，经 management 持久化与专属订阅流下发，discovery 以保留来源 cluster `logical` 进入统一发现视图——与真实实例 union 出现在查询/订阅/对账中；无心跳无租约（status 为管理面静态事实），四级摘除对其同样生效。

数据模型与操作语义以仓库顶层 `proto/` 契约（单一来源：[proto 契约规范](../dev/proto-contract.md)）为准；本版契约的设计细节见 [phase-1 设计文档](../milestones/v0.1/phases/phase-1-scaffold.md)。

## 3. 开发者入口

### 仓库结构

单 monorepo，顶层按语言分目录：

| 目录 | 内容 |
|---|---|
| `proto/` | 语言无关 proto 契约（六组：`common` 公共数据模型 + `registry`/`replication`/`projection`/`discovery`/`management`），非构建体系、不产 artifact |
| `java/` | Java 实现：Maven 单 reactor，服务端（`release=25`）与 SDK/starter（`release=8`）同一 reactor 混合编译 |

### 构建与测试

```bash
mvn -f java/pom.xml verify                 # 全量构建 + 测试（spotless + enforcer + 单测）
java/scripts/dev-test.sh [unit|integration|all]   # 统一测试入口，默认 all
mvn -f java/server/pom.xml spotless:apply  # 提交前格式化（服务端线）
mvn -f java/client/pom.xml spotless:apply  # 提交前格式化（SDK 线）
docker build -t artemis-registry-server .  # registry-server 镜像（多阶段自包含）
```

环境前提：JDK 25（编译/工具进程）与 JDK 8（client 测试 JVM，经 Maven toolchains；模板 `java/toolchains.xml.example`，复制到 `~/.m2/toolchains.xml` 并改 `jdkHome`）。

### 模块

| 模块 | 目标 | 职责 |
|---|---|---|
| `artemis-common` | 25 | 服务端公共规范/工具（SCF 配置工具/通用 source）；proto 生成 `common.v1` + `projection.v1` + `management.v1`（服务端共享） |
| `artemis-registry-core` / `-server` | 25 | registry 内核 / Spring Boot 装配 |
| `artemis-discovery-core` / `-server` | 25 | discovery 投影内核 / Spring Boot 装配 |
| `artemis-management-core` / `-server` | 25 | 治理面内核 / Spring Boot 装配 |
| `artemis-client` | 8 | 注册 + 发现统一 SDK（零 Spring） |
| `spring-boot-starter-artemis-client` | 8 | client 的 Spring Boot 集成（双侧自动装配、生命周期托管、MeterRegistry 注入） |
| `artemis-dist` | 25 | all-in-one 组合入口：同 JVM 起 registry + discovery 双上下文，产出单包可执行 jar |

## 4. 交付状态

**v0.2（registry 集群）已交付**（2026-10-23 版本收尾，验收对照见 `docs/milestones/v0.2/plan.md`）；**v0.3（discovery）已交付**（2026-10-24 版本收尾，验收对照见 `docs/milestones/v0.3/plan.md`）；v0.4（治理面 I）进行中；进度以 `docs/milestones/dev-state.md` 为准。

**phase-1（脚手架 + proto 契约骨架）已交付**：`java/` 十模块 Maven reactor 骨架（parent + 各 core/server/client/starter）与 `proto/` 六组契约骨架（v0.1 定稿 `common.v1` + `registry.v1`，其余四组占位）；`mvn -f java/pom.xml verify` 全绿，enforcer 模块边界断言（core 禁 Spring、client 禁服务端模块）生效。

**phase-2（注册模型四操作 + 租约过期清理 + gRPC 装配）已交付**：

- **注册表服务**：`registry.v1` 四操作（register 无条件覆盖 / deregister 意图幂等 / update 幂等 upsert）与心跳全量上报对账（逐实例续约 + 缺席自动注册 + 内容变化覆盖，不做集合 diff）落地；批量部分失败语义（逐实例 `INVALID_ARGUMENT` 明细，weight ≤ 0 归一 100）。
- **租约与过期**：心跳续约租约，默认 TTL **30s**（原产品 20s），过期清理周期默认 **1s**；TTL 判定用**单调时间源**——系统校时（NTP 跳变）不影响过期正确性。
- **接入**：gRPC 服务（Spring Boot 官方 starter 装配，默认端口 9090；心跳为 bidi-stream 逐请求 ack，`seq` 归属 + `server_time_ms` 服务器时间基准）；同 key 变更事件有序派生（v0.2 phase-2 起由变更事件扇出消费）。
- **工程形态**：registry-core 按 DDD 四层组织（ADR-015：application / domain / infrastructure，gRPC 接入在 server 模块）；37 单测 + 6 服务级集成测试（进程内起真实 gRPC 服务端到端验证）。

**phase-3（自我保护 + 服务端快照 + readiness 门控）已交付**：

- **自我保护基础版**：按全局续约滑动窗口统计（窗口 10s），历史峰值达启用下限（50）后，当前窗口续约量低于峰值的 85% 即进入保护态；保护态暂停整轮租约过期清理（防网络分区/客户端异常时大规模误摘），显式 deregister 不受影响。
- **服务端快照与重启回放**：内存注册表默认每 **5min** 落盘到 `data/registry-snapshot.bin`（原子写：临时文件 fsync 后 rename），快照带落盘时间戳；重启只回放**时效内**（默认 ≤ **1h**）快照，实例 TTL 从回放时刻重新起算；缺失/损坏/超期快照统一丢弃并空表启动（不拒绝启动）。时钟回拨宽容上限 1min，超前超限按超期处置。
- **readiness 门控**：启动数据源就绪（v0.1 = 回放完成或确认无有效快照）前不接客户端流量，gRPC 返回 `UNAVAILABLE`；无快照时空表直接就绪（心跳即注册，客户端自愈），不做跨节点等待。
- **工程形态**：56 内核单测 + 5 服务端单测 + 7 服务级集成测试（含重启回放端到端、readiness 正常路径）。

**phase-4（配置规范化 + registry-server 装配）已交付**：

- **配置（SCF）**：每服务统一装配 `ConfigurationManager` + `StringProperties`，业务配置项由使用它的类就地定义。source 优先级：Spring Environment（命令行 `--artemis.registry.*=` / Spring 属性）> 系统属性 > 环境变量（`ARTEMIS_REGISTRY_*` 映射）> 工作目录 `artemis.properties`（运行中变更自动重载）。动态项：租约 TTL、自我保护窗口/阈值、清理与快照周期、认证开关与凭证（v0.2 起）、management 查询 deadline；静态项：监听端口/地址、快照路径/回放时效、集群成员与自身标识、management 端点（v0.2 起）。越界数值回落默认并记日志；配置文件不可读/格式错误启动失败。
- **端口**：外部 gRPC **9090**、外部 HTTP **8080**、管理 HTTP **9091**（默认仅绑本机）。管理端口承载 actuator。
- **认证**：默认开启单账户 HTTP Basic（默认 `artemis`/`artemis`，生产须修改；`artemis.registry.auth.enabled=false` 可关闭）。gRPC 与外部 HTTP 生效；外部 `/readyz` 与 gRPC 健康探针放行。
- **可观测**：管理端口 `/actuator/prometheus`（实例数、心跳、清理、保护态指标）与 `/actuator/health/readiness`；外部 `/readyz`；结构化 JSON 日志；OpenTelemetry trace 接线（默认不导出 OTLP，配置 endpoint 后开启）。
- **成员抽象**：`ClusterMembers` 静态实现（逗号分隔 `host:port`，支持括号 IPv6），v0.2 接入复制与 readiness。
- **工程形态**：common 8 + core 77 + server 22 单测、13 服务级集成测试。

**phase-5（registry 内部只读查询 RPC）已交付**：

- **契约归属**：查询 service 定义在 `management.v1`（管理面契约组，不随数据面冻结、client SDK 不生成）；服务端由 artemis-common 单源生成，registry 实现、management 消费。
- **查询能力**：服务列表（可按 namespace 过滤）、实例快照、租约明细（age/剩余 TTL；可按 namespace/service 过滤，单次上限 10000 条并标记 truncated）、自我保护统计、配置状态（key/value/source/静态标记/默认值/doc，按 key 升序）。
- **安全**：敏感配置（password/passwd/pwd/secret/token/credential/apikey/accesskey/privatekey）的 value 与默认值均输出 `******`，registry 永不返回明文凭证；查询复用外部 gRPC 端口与默认认证；v0.1 与 SDK 共用单账户凭证（RBAC 随 v1.0、内部端口分离随 v0.2）为已知限制。
- **工程形态**：common 8 + core 89 + server 22 单测、20 服务级集成测试。

**phase-6（management-service 内存态管理面）已交付**：

- **独立服务**：management-service 独立进程（外部 HTTP **8081**、管理端口 **9092**），默认 Basic 认证；启动不依赖 registry 可用，registry 故障时查询 API 返回 503。
- **实例管理 Admin API**：服务列表 / 服务详情 / 实例摘除 / 恢复（`/admin/v1`）。摘除记录归 management（内存态、重启丢失），**不修改 registry** 的注册数据与租约；详情默认返回已应用摘除过滤的**有效视图**，`includeEjected=true` 可展开被摘除实例；摘除/恢复幂等。
- **状态 API**：租约明细 + 自我保护统计、registry 配置状态（敏感值 `******`），数据经 registry 只读查询透传。
- **出站查询**：management → registry 走 gRPC 只读查询（v0.1 复用外部端口；v0.2 起改连内部 peer 端口，端点无默认值，单次 deadline 默认 3s 且动态可调，入站上限 16 MiB）；registry 不可用或端点未配置映射为 503。
- **契约**：Admin/状态 API 为 HTTP/JSON（[admin-api.md](../api/admin-api.md)），不新增 proto；`BasicAuth` 上移 `artemis-common` 供两服务复用。
- **工程形态**：management-core 19 单测；management-server 16 单测 + 11 服务级集成测试（同 JVM 起真实 registry + management，覆盖有效视图/事实不变/恢复/幂等/认证/状态 API）。

**phase-7（Java 8 SDK 注册侧）已交付**：

- **公开 API**：`ArtemisClient`（v0.3 phase-8 起更名 `RegistryClient`；`start`/`register`/`update`/`deregister`/`close`）+ 不可变值类型 `ServiceId`/`InstanceId`/`ServiceInstance`/`InstanceStatus`；入口 `ArtemisClients.newClient(...)`（phase-8 起 `newRegistryClient`，无 builder）。完整用法见 [sdk-java.md](../api/sdk-java.md)。
- **操作语义**：fire-and-forget——本地集同步更新后异步上报，失败内部重试 + 心跳自愈，调用方不处理操作结果；重试前重读本地集，防陈旧状态重放。
- **心跳与容错**：gRPC bidi 流全量上报（默认 5s），seq/ack、指数退避重连、ack 看门狗与 keepAlive 感知半开连接；地址经前置寻址发现管线（v0.2 phase-5 升级为三级容灾 + TTL 轮换，见下）。
- **生命周期**：`start`/`close` 幂等；close 先停心跳、等待在途上报，再全量注销——优雅下线实例即刻消失；零非 daemon 线程遗留。
- **配置**：全部经 SCF `artemis.client.*`（地址/凭证/重试/心跳/重连），环境变量容器友好映射；注册侧不做磁盘快照（崩溃后业务重新注册 + 心跳幂等收敛）。
- **可观测**：Micrometer 指标（操作失败/耗时、心跳失败、重连、连接态、本地实例数，带 client 标签），缺省挂 `Metrics.globalRegistry`，starter 将自动接 Boot `MeterRegistry`。
- **工程形态**：client 24 单测（JDK 8 运行时）；registry-server 侧 `ClientSdkIT` 5 例端到端（注册可见/续约/更新/优雅下线/错误凭证）。

**phase-8（Spring Boot starter + CI + Dockerfile）已交付**：

- **starter**：`spring-boot-starter-artemis-client`（Java 8 / Spring Boot 2.7+）自动装配——`spring.factories`（老 2.x）+ `AutoConfiguration.imports`（2.7+）双注册；配置经 Spring Environment 进 SCF（application.properties/YAML、命令行、系统属性/环境变量，键写法 camelCase/kebab/snake 均可）；`initMethod=start` / `destroyMethod=close` 托管生命周期；自动注入 Boot `MeterRegistry`（无 registry 或多个同型 bean 时回落 `Metrics.globalRegistry`）；`artemis.client.enabled=false` 可关闭，宿主自定义 `RegistryClient` 时让位。用法见 [sdk-java.md](../api/sdk-java.md#spring-boot-自动装配)。
- **CI**：GitHub Actions 按路径触发（`java/**`、`proto/**`、Dockerfile/workflow 变更）；`java` job 以 JDK 25 构建、经 Maven toolchains fork JDK 8 跑 client/starter 测试，强制 spotless:check + enforcer + 单测/集成测试；`docker` job 构建镜像。
- **镜像**：仓库根多阶段 Dockerfile（`maven:3.9-eclipse-temurin-25` → `eclipse-temurin:25-jre`），`docker build .` 一条命令产出 registry 单服务镜像；非 root 运行，快照目录 `/app/data` 可写；暴露外部 gRPC 9090 / HTTP 8080。

**v0.2 phase-1（peer 协议与静态多节点成员）已交付**：

- **复制契约**：`replication.v1` 一次定稿成员/节点状态、增量变更批量投递（按 service 聚合，unary 响应即确认）、全量 bootstrap（server-stream 分片）；本 phase 实现 `GetNodeStatus`，`Replicate`/`Bootstrap` 在 phase-2/3 实现前返回 `UNIMPLEMENTED`。
- **双 gRPC 监听**：外部端口仅承载 SDK；新增内部 peer 端口（默认 **9093**，固定端口，地址无默认值——未配置时绑所有网卡）。内部端口承载 peer 复制/节点状态与 management 只读查询，使用独立认证账户（默认关闭；启用后与外部账户互不通用）。关闭认证的部署需以网络策略隔离内部端口。
- **静态多节点成员**：`artemis.registry.cluster.members=<node-id>@<host>:<port>,...`（支持括号 IPv6），`artemis.registry.cluster.self` 显式标识本节点；非法条目/重复 id/self 未命中启动失败。节点对外端点在 phase-3 扩展为三段式成员条目（见 phase-3 交付节）。
- **配置动态性**：自我保护窗口、清理/快照周期、认证开关与凭证、management 查询 deadline 支持运行期变更（SCF 监听/每次取值）；监听地址与集群拓扑等启动期属性变更需重启。
- **工程形态**：双监听/认证隔离/内部查询迁移/3 节点进程内成员互见 IT；proto、架构、开发规范与 API 文档同步。

**v0.2 phase-2（变更事件扇出）已交付**：

- **副本租约语义（继承原产品）**：副本与事实源是同一种租约——心跳纯续约发 `RENEW` 批量扇出维持 peer 副本；TTL 过期清理本地各自发生、产生的 DELETE **不扇出**（删除不跨节点传播，收不到续约的租约全集群分别过期）；复制入站零事件（防回环）、`RENEW` 到达而本地副本已被清理时自动补注册重建（分区恢复路径）。多节点配置（`cluster.members` + `cluster.self`）时启用扇出；单节点零开销（事件出口为 no-op）。
- **扇出管线**：变更事件（NEW/UPDATE/DELETE/RENEW）经全局有界队列 → 聚合线程按 service 聚合批量（同实例变更合并、产生序保持、单批上限拆分）→ 每 peer 独立有界队列串行投递；投递失败全抖动指数退避重发同批（保序）；**确定性失败**（批次非法整批拒绝、滚动升级窗口 `UNIMPLEMENTED`、凭证不匹配）丢批续投不阻塞队头。队列满丢弃（全局丢当条、peer 队列丢最旧）+ 计数，丢失由自愈链兜底（变更再扇出 / 客户端 TTL 轮换 / bootstrap）。
- **复制配置**（`artemis.registry.replication.*`，SCF）：`queueCapacity`（全局事件队列，静态，默认 16384）、`peerQueueCapacity`（per-peer 批队列，静态，默认 4096）、`maxBatchChanges`（单批上限，动态，默认 1024）、`retryInitialMs`/`retryMaxMs`（退避基数/上限，动态，默认 1s/60s）、`sendTimeoutMs`（投递 deadline，动态，默认 5s）。
- **复制指标**（`/actuator/prometheus`）：`artemis.registry.replication.batches.sent|failed|rejected|dropped`（tag `peer`，前二者为投递结果、rejected 为确定性失败丢批、dropped 为 peer 队列满丢最旧）、`events.dropped`（全局队列满）、入站侧 `batches.received` / `applied.changes`。
- **工程形态**：副本租约语义与扇出编排单测 + 3 节点进程内 IT（任一节点写入全节点最终一致、RENEW 维持、猝死全清理、过期重建、缺席 peer 退避收敛、指标）；proto `CHANGE_TYPE_RENEW=5`（兼容追加）。

**v0.2 phase-3（peer 全量 bootstrap 与节点状态 API）已交付**：

- **peer 全量 bootstrap**：新节点加入/重启时若存在已就绪（`UP`）peer，异步从候选 peer 拉取全量注册表（server-stream 分片，按序列化字节预算），校验/归一后**整体替换**（零事件、TTL 从应用起算）——同步成功才 ready。失败按全抖动指数退避重试（默认 1s→10s），每轮依次尝试全部 `UP` 候选；**一旦确认过数据源，后续 peer 全部不可达也保持 not ready、不降级本地快照**。无 `UP` peer 时回退本地有效快照，否则空表 ready（分支②/③）。
- **not ready 语义**：外部写流量由 readiness 拦截；内部 `Replicate`/`Bootstrap` 返回 `UNAVAILABLE`（发送侧保序重试，避免覆盖全量同步、也禁止未就绪节点充当数据源）；快照调度器在 not ready 期间不落盘（防止空表覆盖可回退快照）。
- **成员配置**：`<node-id>@<peer-host>:<peer-port>@<external-host>:<external-port>`（集群模式三段必填；host 支持括号 IPv6）。对外端点显式配置以支持多网卡/NAT/K8s，节点状态 API 原样返回。
- **节点状态 API**：外部 gRPC `artemis.replication.v1.NodeStatusService/GetNodeStatus`（保留外部认证；readiness 按方法名豁免，便于观察 `STARTING`）。返回 `self` 就绪态 + 全部成员的 peer/external 端点与**观测态**；观测态由后台探测维护——默认每 **5s**（`probeIntervalMs`）用虚拟线程**并发探测全部 peer**，单 peer/整轮超时默认 **1s**（`probeTimeoutMs`），失败/超时/nodeId 不匹配 = `UNKNOWN`，每轮整体重建。探测只服务 API/观测，不参与复制扇出门控；SDK（phase-5）以该列表为候选地址来源，优先 `UP`、连接失败/熔断兜底。
- **配置（SCF）**：`artemis.registry.cluster.probeIntervalMs`、`probeTimeoutMs`（动态）；`artemis.registry.bootstrap.retryInitialMs`、`retryMaxMs`、`statusTimeoutMs`、`timeoutMs`、`shardBytes`（动态）；`artemis.registry.bootstrap.maxInboundMessageBytes`（静态）。
- **指标**：`artemis.registry.bootstrap.attempts|failures|synced.instances`、`artemis.registry.cluster.probe.rounds`、`artemis.registry.cluster.probe.failures{peer}`、`artemis.registry.cluster.peers.up`（经 `/actuator/prometheus`）。
- **工程形态**：探测并发/超时/恢复、启动分支、整体替换、bootstrap 分片等单测；集群 IT 覆盖新节点加入全量同步、重启优先 peer、无 UP peer 回退快照、已就绪 peer 同步失败重试恢复、对外节点状态 API（含停节点标记 UNKNOWN）。

**v0.2 phase-5（SDK 三级地址容灾与连接 TTL 轮换）已交付**：

- **三级地址管线**：配置 url（`artemis.client.registry.addresses`，生产配域名/LB）→ HTTP 前置寻址 `GET /cluster/nodes`（Basic 认证，protobuf bytes，稳态 5min 刷新）取得节点列表 → 选址池按观测态优先级随机（`UP` > `UNKNOWN`/`STARTING` > `DOWN`，状态只作优先级不作排除）。拉取失败保留旧列表（不清空）、指数退避重试；未就绪节点寻址应答 503（SDK 重试其它 url / 单域名退避重试原址）。
- **熔断换址**：断流/开流失败/ack 超时计入当前地址连续失败（仅心跳引擎驱动，归因无竞态），达阈值（默认 3）熔断并立即重新前置寻址；全部熔断仍全量随机（降权不清零）。当前节点死亡无感换址，心跳全量上报使新节点 upsert 自愈——业务实例不过期。
- **连接 TTL 轮换**（ADR-006 分布式对账）：心跳流按 TTL（默认 10min ± 20% 抖动）主动换址重建，开流全量上报修复新节点丢失的事件残留；健康换址不计失败不进熔断。
- **配置与可观测**：`registry.connection.ttlMs`、`registry.address.refreshIntervalMs/retryBaseMs/retryMaxMs/circuitThreshold/circuitOpenMs`（均动态可调）；指标 `rotation`、`address.circuit.opened{address}`、`nodelist.refresh{outcome}`。
- **工程形态**：client 44 单测（选址/熔断/寻址/轮换压测）+ registry-server `SdkFailoverIT`/`ClusterNodesHttpIT`（3 节点真实集群 + 真实 SDK 端到端容灾）；API 文档（sdk-java / node-status-api）同步。

**v0.2 phase-4（自我保护 2.0：节点级兜底 + 服务级权威）已交付**：

- **两层判定**：保留节点级全局窗口（原 10s/85%/下限 50 语义）作为网络分区/小服务/纯副本兜底；新增每服务窗口，服务峰值达 `minCount` 后服务级状态权威，否则回落节点级。过期清理按服务判定：保护中的服务保留租约，其余服务照常清理；显式 deregister 不受影响。
- **显式状态与回落**：每窗口维护 `NORMAL/PROTECTING`、`protectingSince`、迁移计数与结构化日志；状态只由 1s 清理调度中的周期评估器写入。节点健康时服务峰值 `maxCountResetMs`（默认 10min）未抬升即回落，限制客户端迁移造成的永久保护；节点级保护中不回落。动态调整窗口时以新口径重建峰值基线，避免误触发。
- **可观测（有界基数）**：节点级沿用 `artemis.registry.self.protection|renewals|max`；服务级 `artemis.registry.self.service.protection{namespace,service}` 仅在保护中注册，另有 `...count` 与 `...transitions{to=protecting|normal}`。服务级明细经 API 查询，不常驻指标。
- **状态 API**：registry 内部 `management.v1.GetSelfProtectionStatus`（management 透传 `GET /admin/v1/status/protection`）默认只返回保护中服务，支持 namespace/service 过滤、10000 条上限、`node_fallback` 与节点级统计；指定 namespace+service 时该服务总是返回。`GetLeaseStatus` 保持节点级语义不变。
- **集群语义**：各节点只按本地收到的心跳独立判定；保护态不复制、不持久化，也不抑制复制。3 节点进程内 IT 验证节点独立保护、指标标签与超时退出。
- **工程形态**：registry-core 单测覆盖双层窗口/状态机/回落/指标/查询/回收竞态，registry-server 服务级与 3 节点集群 IT，management client/Admin 端点端到端；全量 `dev-test.sh all` 绿。

**v0.2 phase-6（优雅停机与集群验收收尾）已交付**：

- **节点优雅停机**：停机序列在 Spring `ContextClosedEvent` 第一时机执行（先于 gRPC 端口关闭）——先标记自身 `DOWN`（节点状态 API 与 peer 探测可见），排水等待（`artemis.registry.shutdown.drainMs`，默认 **10s**，`0` = 不等待），再终刷快照，最后停端口。排水期间不接新流量（新 RPC `UNAVAILABLE`、`/readyz` 与 `/cluster/nodes` 返回 503、`Replicate`/`Bootstrap` 拒绝），**已建立的心跳流继续服务到端口关闭**（续约照常）；端口关闭即断流信号，客户端凭容灾无感换址（断流重连 + 熔断 + TTL 轮换），**不做连接迁移**。
- **DOWN 传播**：就绪门升级为三态（未就绪 / 就绪 / DOWN，DOWN 终态不复活）；`DOWN` 优先于 `UP` 自报，经节点状态 API 与 `/cluster/nodes` 对外可见，SDK 选址池降权（`UP > UNKNOWN/STARTING > DOWN`）。
- **终刷与重启恢复**：仅**曾就绪**的节点在停机时终刷快照（未就绪时注册表为空/残缺，跳过以免覆盖可回退旧快照）；单节点重启回放终刷数据，集群节点重启优先从就绪 peer 全量 bootstrap——两条路径均收敛到停机前状态。
- **SDK 优雅下线时序**：starter 监听 `ContextClosedEvent` 自动 `close()`——停心跳 → 等在途上报 → 全量注销 → 释放，发生在容器关闭第一时机（程序仍可用、早于宿主停流与 bean 销毁）；`destroyMethod=close` 保留为幂等兜底。
- **部署注意**：gRPC 关停宽限期显式设为 **1s**（框架默认 30s 会与排水叠加）。K8s 等编排环境 `terminationGracePeriodSeconds` 须 ≥ `drainMs` + 1s + 余量，否则 SIGKILL 会截断排水（截断后仍由客户端容灾 + peer 副本兜底）。
- **工程形态**：新增 11 例单测（就绪门三态、停机序列编排、DOWN 自报、未就绪不终刷、DOWN 不 bootstrap、starter 事件关闭）与 `GracefulShutdownIT` 4 例集群 IT（排水窗口取证、无感换址、终刷回放、停机后重启收敛）；全量 `dev-test.sh all` 绿。

**v0.3 phase-1（projection.v1 契约 + registry 侧数据分发）已交付**：

- **分发契约**：`projection.v1` 定义于 registry 内部 peer 端口——`Bootstrap`（全量快照 server-stream，按字节预算分片，默认 1MB，与 peer bootstrap 共用配置）与 `Watch`（变更流 server-stream）；变更仅三值 `NEW/UPDATE/DELETE`（纯续约不推送、TTL 清理映射为删除）。
- **消费方安全序**：`Watch` 登记订阅后立即返回一条空批作为建立确认；消费方「先 Watch → 收到确认 → Bootstrap」，重叠窗口变更被全量覆盖幂等吸收，无丢失。推送为 at-most-once，断流/慢消费者由服务端断开，消费方重 Bootstrap 收敛。
- **registry 侧实现**：注册表事件统一出口（写路径与租约清理同锁有序派发）；异步组批（同实例后到覆盖、按 service 聚合、批量上限）→ 每订阅者有界队列 + 串行推送；慢消费者/推送失败断流并计数。复制扇出发送侧过滤清理事件（删除不跨节点传播语义不变）。
- **边界**：契约只承载实例事实，registry 自我保护态（stale）等内部概念不下发（排障面仍由 management 状态 API 提供）。
- **工程形态**：内部端口 readiness 门控（未就绪 `UNAVAILABLE`）；10 单测 + 8 服务级 IT（全量/分片、变更流、清理删除、断流重连 re-bootstrap）；决策记录 [ADR-018](../decisions/adr-018-projection-distribution-contract.md)。

**v0.3 phase-2（discovery 投影内核与查询）已交付**：

- **投影模型**：discovery 侧按 `(cluster, namespace, service)` 组织只读投影——cluster 为来源标识（投影边界注入，ADR-014），phase-2 单集群起步（多集群聚合 phase-5）。存储 per-service 条目（`ConcurrentHashMap` + 不可变实例集，读路径无锁零克隆）；**条目存在性 = 服务存在性（≥1 实例）**，实例全部下线即删条目（与 registry 空服务回收语义收敛）。
- **上游消费**：消费 phase-1 `projection.v1` 分发流——「Watch 建立确认 → Bootstrap 全量覆盖 → 变更流应用」安全序（覆盖先于一切事件应用，无丢失）；断流/失败/慢消费（事件队列满）整轮重来：关会话 → 全抖动指数退避（1s→10s）→ 重新 Watch+Bootstrap 全量覆盖幂等自愈（每轮端点轮换，watch 与 bootstrap 同端点保同源视图）。watch 长流启用 keepalive 检测半开连接。
- **readiness**：首轮投影同步完成前查询 `UNAVAILABLE`（空数据不作真相）；同步后上游断连不降 ready——投影继续服务（陈旧换可用），新鲜度标记出口随 phase-4。
- **查询 API**：`discovery.v1 DiscoveryQueryService/Query`（unary，外部端口 gRPC 9095，BasicAuth 默认关闭）——选择器 `services`（精确集合，空=全部）+ `labels`（实例 metadata 全键值过滤，分组语义 ADR-013）；响应携带 cluster 来源标识。契约详见 [discovery-query-api.md](../api/discovery-query-api.md)。
- **配置（SCF，`artemis.discovery.*`）**：上游集群 map `registry.clusters`（**必填**，详见 phase-5 节；phase-2 初版为 `upstream.clusterId` + `upstream.endpoints` 两键，phase-5 已替换）、`upstream.auth.*`、`upstream.establishTimeoutMs/bootstrapTimeoutMs/reconnectBackoffMinMs/MaxMs`、`projection.queueCapacity`（默认 8192）。
- **工程形态**：discovery-core 16 单测（投影存储/消费安全序/选择器/mapper）+ `DiscoveryProjectionIT` 4 例（进程内真实 registry + discovery 双 context 端到端：稳态注册→查询亚秒可见、更新/删除反映、registry 重启 re-bootstrap 覆盖陈旧、未同步 UNAVAILABLE）；code-review 修复 4 项中高危（陈旧 DELETE no-op、keepalive、旧轮回调隔离 per-round 队列、DISCONNECTED 门控）。

**v0.3 phase-3（discovery 订阅契约与推送）已交付**：

- **推拉结合模型（ADR-019，取代 ADR-007 订阅通道快照形态）**：订阅通道 = 实时推增量（本 phase）+ 定期拉全量（Query 已有）+ hash 对账（phase-4）——稳态带宽 O(变更数) 而非 O(服务实例数 × 变更数)，增量丢失由 checksum 对账显式检测（原产品局限 #3「无版本/世代」的现代化）。
- **订阅契约**：`discovery.v1 DiscoverySubscriptionService.Subscribe`（server-stream）——首条空事件 = 建立确认，随后逐 service 推送全 `NEW` 基线；稳态推视图语义增量变更批（per-service 分条、单流按产生序有序、一流多目标、事件携带来源 cluster）。
- **labels 视图语义翻译（服务端）**：labels 订阅收到的即视图语义——实例进出 labels 视图（UPDATE 改 metadata）由服务端显式翻译为 `NEW`/`DELETE`，SDK 收到即 apply，无需自行判断匹配态。
- **上游解耦**：registry↔discovery 断连重连对订阅方透明——断连期间流静默（陈旧换可用），重连覆盖差异以 **diff 增量续推**（旧投影 vs 新快照 diff，消失服务全 `DELETE`、registry 全冷重启推全删），流不断。
- **at-most-once**：慢消费者（outbound 背压）服务端断流 `RESOURCE_EXHAUSTED` + 注销，重订阅重新基线自愈；推送指标（active/push/closed.slow-consumer）就地内建。
- **工程形态**：discovery-core 28 单测（投影 diff、翻译矩阵四象限、表锁基线/扇出互斥）+ `DiscoverySubscriptionIT` 7 例（确认/基线、增量亚秒可见、双流隔离、labels 视图、上游重启 diff 续推流不断、未就绪 UNAVAILABLE、cancel 注销）；code-review 双轨（skill max + 内联）10 findings，核心修复 = 订阅基线与批「扇出+应用」表锁串行化（消除僵尸复活/确认被抢/变更丢失三竞态）。契约详见 [discovery-subscription-api.md](../api/discovery-subscription-api.md)。

**v0.3 phase-4（discovery 兜底对账与新鲜度）已交付**：

- **hash 对账（ADR-019 三件套之三，原产品局限 #3「漏推最久 15min 才纠正、丢失不可检测」的现代化）**：`discovery.v1 DiscoveryReconciliationService/Checksum`（unary，外部端口）拉取 per-service **视图 checksum 表**——SDK 定期与本地视图逐 service 比对，不一致触发 `Query` 全量拉，增量丢失显式检测、漂移窗口 = 对账周期。响应**缺项 = 权威空**（SDK 本地空 + 表缺即一致；丢失的 `NEW` 必致表项出现）。
- **canonical 规则跨语言稳定（单一来源 [discovery-reconciliation-api.md](../api/discovery-reconciliation-api.md) §3，含 golden vector）**：per-instance FNV-1a-32 + XOR 结合——实例集与 metadata **双重抗序**（不定义跨语言排序规则），唯一顺序敏感点收窄到单实例标量字段位置序列；碰撞 ~2⁻³² 由 15min 全量刷新兜底。
- **缓存分型**：checksum 随投影条目缓存（写入时计算）；无 labels 请求响应零计算，labels 视图响应对算（仅 labels 订阅方、对账周期频率）。
- **新鲜度标记（局限 #15「缓存无新鲜度元信息」修复）**：查询与对账响应携带 `UpstreamStatus`（per-cluster：`SYNCED` 实时 / `SYNCING`、`DISCONNECTED` 投影冻结陈旧）——非 `SYNCED` 即可能陈旧，消费方感知数据降级。registry 自我保护态不进新鲜度（内部概念）；订阅流不携带（D-S7 流与上游解耦，降级感知经拉取面）。
- **三层兜底轮询（资产 #8）契约面收口**：空服务层被对账吸收（缺项 = 权威空）、15min 全量刷新为对账失效的最终兜底、推送失败先重订阅再轮询兜底——服务端零新增配置与指标，SDK 侧节奏归 phase-8。
- **工程形态**：单测 8 用例（canonical 抗序/字段敏感、条目缓存一致性、labels 视图、状态上报、proto 映射）+ `DiscoveryReconciliationIT` 5 例（SDK 角色独立实现 canonical 规则驱动端到端：表随注册/更新/注销一致演变、篡改本地视图对账检测后全量拉收敛、registry 关停 DISCONNECTED 标记可见且陈旧换可用、重启双面收敛、labels 视图指纹、未就绪拒绝）；code-review 双轨 6 findings（核心修复 = 快照先于读表，防「陈旧数据 + SYNCED」夸大新鲜度竞态）。


**v0.3 phase-5（discovery 多集群聚合）已交付**：

- **多集群聚合（ADR-002/014）**：单 discovery 节点独立订阅 1..N 个 registry 集群——每 cluster 独立投影表、独立同步器（端点轮换限于本集群端点）、独立退避重连与上游状态（**跨源隔离**：某集群断连只冻结该源投影并标记其新鲜度，其余集群不受影响）。聚合只在本地发生，无跨源污染。
- **union 语义**：查询/订阅/对账默认返回全部来源 cluster 的并列条目（同 `ServiceKey` 多集群并存即并列，不冲突消解）；条目携带来源 `cluster`（ADR-014 投影边界注入）。
- **选择器 cluster 维度**：`discovery.v1` 三 request（`QueryRequest`/`SubscribeRequest`/`ChecksumRequest`）新增 `clusters` 字段（wire 兼容只增）——空 = 全部（union），非空 = 精确集合；未知 cluster 名静默无命中（部署拓扑不耦合 SDK），空白名 `INVALID_ARGUMENT`。新鲜度列表同样按选择器过滤。
- **上游配置**（`artemis.discovery.registry.clusters`，静态）：JSON map `{"cluster-a":"r1:9093,r2:9093","cluster-b":"r3:9093"}`——键 = clusterId、值 = 该集群端点逗号分隔列表；未配置/JSON 非法/集群名空白/端点非法均拒绝启动。集群集合启动定死（对齐 registry 静态成员哲学），上游认证全局共享（per-cluster 凭证待真实需求）。
- **readiness 全量门**：**全部**配置上游完成首轮同步才放行查询——任一上游缺席即 union 不完整（空数据不作真相）；断连仍不降 ready（陈旧换可用）。
- **工程形态**：discovery-core/server 单测（选择器过滤/就绪门/配置解析/mapper）+ `DiscoveryMultiClusterIT` 6 例（进程内 2 个真实 registry 集群 + 1 discovery：union 并列、cluster 过滤、订阅归因与过滤、对账过滤、新鲜度独立性、readiness 全量门）；三个既有 discovery IT 切换新配置键回归单集群语义；API 文档同步（[查询](../api/discovery-query-api.md)/[订阅](../api/discovery-subscription-api.md)/[对账](../api/discovery-reconciliation-api.md) 三文补 `clusters`）。

**v0.3 phase-6（management 摘除规则订阅与 discovery 过滤）已交付**：

- **摘除规则流**（`management.v1 EjectionRuleService/WatchEjections`，server-stream）：management 内部 gRPC 端口（条件启用——配置 `artemis.management.grpc.port` 才监听，默认不监听可独立运行；独立认证默认关闭）。首条全量快照（message presence 区分空集）→ 其后增量按产生序；服务端单锁全序免快照竞态；at-most-once（v0.4 phase-5 起携带版本 + ack，见下 phase-5 节）、慢消费者 isReady 断流；management 内存态重启清零，discovery 重连空快照即摘除消失（期 1 设计内行为）。
- **discovery 过滤器链 SPI**（继承资产 #7，架构 §4.2）：`DiscoveryFilter`（实例可见性谓词）+ 链（逐 filter fail-open——单 filter 异常放行不中断发现服务）。查询/订阅推送/对账 checksum **三出口一致过滤**（过滤后视图口径——checksum 不过滤会使 SDK 对账永久不一致）；checksum 零计算路径保留（规则无效果时读条目缓存）。
- **摘除过滤语义**：规则按 `(namespace, service, instanceId)` 匹配**所有来源 cluster** 同键实例（跨 cluster 生效）；投影存储保持 registry 事实（未过滤），过滤只发生在视图构成时刻（与 phase-5 聚合同构）。规则变更以**视图语义直投**推送订阅者（摘除 → `DELETE` 携带实例完整数据、恢复 → `NEW`），labels/clusters/services 订阅过滤照常。
- **fail-open / 最后已知规则**：management 不可用时 discovery 沿用最后已知规则继续过滤（规则缓存只在收到事件时替换、断连不清空），退避重连（默认 1s→10s）恢复后重新全量覆盖收敛。
- **0..1 可选**：discovery 未配置 `artemis.discovery.management.endpoints` 时不订阅不过滤（空链直通零开销，不建连接不启线程）。
- **工程形态**：management-core `EjectionStore.findAll` + `EjectionRuleDispatcher`（单锁全序分发）；discovery-core 规则同步引擎 + 过滤链；单测 15 例（规则模型/链/翻译矩阵/出口过滤/断流保持/分发全序/配置）+ `EjectionRuleStreamIT` 4 例（规则流契约）+ `DiscoveryEjectionFilterIT` 3 例（registry + discovery + management 三服务端到端：摘除过滤/恢复收敛/management 停机沿用最后规则/重启空快照收敛/未配置不过滤）；API 文档新建 [ejection-rule-stream-api.md](../api/ejection-rule-stream-api.md)、discovery 三 API 与 admin API 同步。

**v0.3 phase-7（discovery 发现管理 API）已交付**：

- **只读发现管理面**（治理期 2，架构 §5.1）：discovery 外部 HTTP 端口（默认 8090）新增 `/admin/v1` JSON API——`subscriptions`（订阅关系）、`services`（服务视图列表/详情）、`upstreams`（上游新鲜度 + 规则源状态）。复用 discovery 外部账户（`artemis.discovery.auth.*`，默认关闭）；**不做 readiness 门控**，启动期即可排障，未同步由状态字段自解释。
- **订阅关系（调用方视角，原产品局限 #18 修复）**：订阅登记时分配进程内单调 `id`、记录选择器、gRPC 远端地址（单账户下唯一可靠调用方标识）与登记时刻；只保留活跃订阅，流取消/断流即消失（无历史/审计，审计归 v0.4 期 3）。
- **服务视图**：列表按 `(cluster, namespace, service)` 聚合并支持 namespace/cluster 过滤；详情逐 cluster 返回可见实例。口径 = **摘除过滤后的有效发现视图**（与查询/推送/对账一致）；未过滤事实与摘除记录仍归 management 有效视图职责（ADR-016）。
- **上游状态/规则源状态**：`/admin/v1/upstreams` 输出各 registry 来源 `SYNCING/SYNCED/DISCONNECTED`，以及 management 规则源 `DISABLED/SYNCING/SYNCED/DISCONNECTED`（phase-6 D-E9 出口；断连/停机时规则冻结、最后已知规则继续生效）。
- **工程形态**：无 proto/wire 变更、无新增配置键；discovery-core 12 单测（订阅快照/视图聚合与过滤/规则源状态转移）+ `DiscoveryAdminIT` 6 例（认证、订阅出现与取消移除、视图/详情、上游与规则源状态、客户端错误）。契约详见 [discovery-admin-api.md](../api/discovery-admin-api.md)。

**v0.3 phase-8（消费方 SDK + discovery 静态节点目录）已交付**：

- **发现侧 Java 8 SDK**（并入既有 `artemis-client`）：`DiscoveryClient.subscribe(DiscoverySelector, listener)` 按选择器（services/labels/clusters，空集 = 全部）建立**独立订阅**（各自流/视图/对账/快照），client 级共享地址寻址、gRPC 连接、applier、调度与监听队列。本地视图为来源 cluster 的 union，不可变快照 + 零拷贝读；推送为视图语义，SDK 直接 apply。
- **同步与兜底**（ADR-019 推拉结合）：订阅确认后 bootstrap 全量 Query 整体替换视图（期间推送缓冲后重放，断连期间消失的服务可被删除且不制造空窗）；稳态实时增量 + **60s checksum 对账**（不一致触发全量刷新）+ **15min 全量刷新**；server 全挂时最后视图继续服务。监听回调有界队列（满丢最旧 + 计数）。
- **磁盘快照**：每订阅 `<dir>/<clientId>/selector-<fingerprint>.snapshot`，**随视图变更异步落盘**（合并写 + close 终写、原子替换）；冷启动回放、server 全挂兜底；不过期，`DiscoveryStatus.isSnapshotReplay()` 暴露降级。
- **发现专属地址容灾**（与 registry 同管线，ADR-020）：HTTP 引导地址 → discovery 静态节点目录 → 历史最近成功优先 + 随机 + 熔断换址；慢消费者断流不熔断/不换址。配置键与 registry 对称（`artemis.client.discovery.*`）。
- **discovery 静态节点目录**：`artemis.discovery.nodes`（`host:port` 列表，无节点 id/self，地址即标识；未配置 = 空目录）+ 外部 HTTP `GET /cluster/nodes`（`discovery.v1 DiscoveryNodesResponse` protobuf、复用外部 Basic、未就绪 503）。仍是部署声明：不组集群、无节点间协议、无存活探测（ADR-020 修订 ADR-002）。
- **统一 client 身份**：`artemis.client.clientId`（默认 `default`），注册/发现共用，作为指标 `client` 标签与快照目录隔离键；注册侧公开接口更名 `ArtemisClient` → `RegistryClient`、`newClient` → `newRegistryClient`（starter 自动配置类名保持 `ArtemisClientAutoConfiguration`）。
- **契约与文档**：`discovery.v1` 增 `DiscoveryNode`/`DiscoveryNodesResponse`（只增，无新 service）；新增 [discovery-nodes-api.md](../api/discovery-nodes-api.md)、[sdk-java-discovery.md](../api/sdk-java-discovery.md)，注册侧 [sdk-java.md](../api/sdk-java.md) 同步更名/clientId。
- **工程形态**：client 63 单测 + 7 进程内 fake discovery IT（真实 gRPC + 真实 HTTP 节点目录：推送、断连收敛、换址、快照回放、全挂兜底、checksum 自愈、多订阅隔离）；discovery-server 9 单测 + 2 节点目录 HTTP IT；全量 `dev-test.sh all` 绿。发现侧 Spring Boot starter 自动装配随 phase-9 交付（见下）。

**v0.3 phase-9（发现侧 starter + all-in-one 打包）已交付**：

- **starter 双用途**（`spring-boot-starter-artemis-client` 不变）：注册/发现两侧按「对应 `addresses` 是否配置」独立装配、可共存——只做发现的消费方不必配 registry 地址；缺地址不再 fail-fast（v0.1 语义修订），已配置但非法仍构造期 fail-fast；两侧独立让位宿主自定义 `RegistryClient`/`DiscoveryClient` bean。SCF 配置桥 `artemisClientStringProperties` 同名 bean 让位（接管 source 链）、异名共存时 client `@Qualifier` 按名绑定不误注入；关停监听只关 starter 自建 bean、关闭失败不阻断另一侧。发现侧只建 client 不代建订阅。用法见 [sdk-java.md](../api/sdk-java.md) / [sdk-java-discovery.md](../api/sdk-java-discovery.md)。
- **all-in-one 单进程**（新增 `artemis-dist` 模块）：`artemis-dist-<version>-exec.jar` 单包起 registry + discovery——同 JVM 两个独立 Spring 上下文、端口全独立（registry 9090/8080/9091/9093、discovery 9095/8090/9096）、彼此走标准网络协议不进程内直连（ADR-001）；上游固定同进程 registry、多集群聚合不在该形态提供；组合端口经 `artemis.dist.*`（SCF：系统属性 > 环境变量 > 工作目录 `artemis.properties`，非法 fail-fast）；启动失败回滚不留半启进程、SIGTERM 按 discovery → registry 优雅关停。单服务入口与打包不变。详见 [all-in-one.md](../api/all-in-one.md)。
- **工程形态**：starter 17 单测（双侧条件/让位/接管/共存/关闭语义）；dist 3 服务级 IT（双端口起停、registry 注册 → discovery 网络可见、幂等关停）；单包冒烟（7 端口监听 + 双 actuator 200 + SIGTERM 11s 优雅退出）；全量 `dev-test.sh all` 绿。

**v0.4 phase-1（management DB 接入基座）已交付**：

- **DB 可选装配（双轨）**：DB 是 management 的可选依赖——未配置 `spring.datasource.url` 时保持内存态运行（零 DB 装配，registry/discovery 完全不感知）；配置后 HikariCP 连接池 + Flyway migration 启动期执行。**MySQL 8.x / PostgreSQL 16+ 双方言**为产品承诺：脚本按 vendor 目录（`db/migration/{mysql,postgresql}`）各自维护，当前 V1 空基线、业务表自 V2 起随各 phase 追加；双方言驱动均随包分发，按 url 自动选择。
- **配置**：`spring.datasource.*`（url/username/password）、`spring.datasource.hikari.*`（连接池）、`spring.flyway.*`（migration）均为 Spring Boot 标准配置键；url 配置为空串时启动显式失败（不静默退化为内存态）。
- **状态 API**：`GET /admin/v1/status/persistence` 返回运行形态 `{"mode":"MEMORY|DATABASE","databaseProduct":"MySQL|PostgreSQL","schemaVersion":"1"}`（受 Basic 认证保护；`spring.flyway.enabled=false` 时 `schemaVersion` 为 null）。见 [admin-api.md](../api/admin-api.md)。
- **运行时语义（过渡期）**：DB 态下业务（摘除等）仍为内存实现，持久化随 phase-2（账户）起逐表接管；registry/discovery 主类显式排除数据源自动装配（组合部署装配边界，同 JVM 组合形态不受 DB 依赖混入影响）。
- **工程形态**：双方言 IT（Testcontainers 2.0 真实容器起停 + migration 断言，无 Docker 环境自动跳过）+ 内存态装配单测；ORM（MyBatis-Plus，技术选型 #13）决断推迟首个业务表 phase（phase-2）。

**v0.4 phase-2（账户体系与统一认证接管）已交付**：

- **单管理员 DB 账户**：首个业务表 `artemis_account`（V2，双方言——PG `timestamptz` / MySQL `datetime(6)`）；密码 PBKDF2-HMAC-SHA256 哈希存储（JDK 内置，600k iterations、随机 salt、常量时间比较，哈希串自描述格式），明文不落库不落日志。ORM 采用 MyBatis-Plus 3.5.17（boot4 starter，技术选型 #13 决断，SB 4.1 实测兼容；core 保持零 MyBatis/Spring 依赖，端口 + 实现分层）。
- **统一认证接管（ADR-021）**：接管 = **管理访问面收敛**——DB 态下 Admin API 认证唯一源为账户表，`artemis.management.auth.username/password` 配置凭证失效（首启一次性物化 seed 进表，已配置自定义凭证的部署升级无缝；表非空永不覆盖）；registry/discovery 本地单账户零改动（SDK 数据面与管理凭证分域，全面委托随 v1.0 RBAC）。内存态行为不变（配置凭证，支持运行期轮换）。
- **改密 API**：`PUT /admin/v1/account/password`（DB 态闭环，默认密码消除出口；旧凭证即时失效；新密码 ≥ 8 字符）；内存态返回 409（凭证事实源是配置）。见 [admin-api.md](../api/admin-api.md)。
- **语义细节**：成功凭证 60s 服务端缓存（正确路径每分钟一次哈希开销，错误路径每次全额计算）；认证后端不可用 fail-closed（401 拒绝 / API 503）；默认密码在用时启动 WARN（ADR-009 延续）。
- **工程形态**：core 哈希器/裁决服务单测 + server 过滤器三态/端点单测；双方言 `AccountFlowIT`（seed 默认登录 → 改密接管生效 → 跨重启账户保留 + 配置凭证失效，Testcontainers 真实容器）。

**v0.4 phase-3（四级摘除持久化与审计）已交付**：

- **四级摘除**：摘除目标四级——INSTANCE（`(namespace, service, instanceId)` 定位）与 SERVER / ZONE / GROUP（以实例 metadata 键 `server` / `zone` / `group` 取值匹配，全局生效不绑服务，ADR-013 延伸）；实例不带对应键则不参与该级摘除。规则经既有摘除规则流下发 selector，discovery 按实例 metadata **实时匹配**——新注册实例天然命中。
- **操作记录即状态**：下线 = 追加 EJECT 记录（可叠加，各带原因与操作者）；恢复 = 追加 RECOVER 反向记录（FIFO 对消，不删历史——原产品「恢复即删」的改进）；生效判定 = 每目标 EJECT 与 RECOVER 计数差 > 0。规则流增量仅在目标生效态翻转时推送（叠加/部分对消不推）。
- **持久化（V3，双方言）**：`artemis_ejection_record`（操作时间线，自增主键 = 序）+ `artemis_audit_log`（治理操作审计）。DB 态下 management 重启后摘除经规则流快照重下发恢复（roadmap v0.4 验收要点闭环）；内存态行为不变（重启丢失，期 1 语义）。
- **统一 ejection API**：`POST /admin/v1/ejections`（四级一套请求体）/ `POST /admin/v1/ejections/recover` / `GET /admin/v1/ejections`（时间倒序 + limit）/ `GET /admin/v1/audit-logs`（operation 过滤 + limit）——替换 v0.1 实例级端点。见 [admin-api.md](../api/admin-api.md)。
- **审计**：全部治理操作可审计——摘除/恢复以记录表自身为完整留痕 + 审计表摘要行（尽力而为，摘除状态源唯一在记录表）；改密等其余治理操作全量进审计表。操作者取认证用户名。
- **规则流契约**：`EjectionRule`/`EjectionRecovered` wire 兼容扩展（scope + target_value，缺省 INSTANCE 承载 v0.3 既有事件）；快照 = 有效规则集。见 [ejection-rule-stream-api.md](../api/ejection-rule-stream-api.md)。
- **工程形态**：对消/命中判定纯函数（内存/DB 两态共享语义）单测；双方言 `EjectionPersistenceIT`（四级落库 + 审计 + 跨重启快照恢复）；三服务端到端 selector 过滤（zone 级摘除跨服务命中）。

**v0.4 phase-4（逻辑实例）已交付**：

- **模型与 API**：逻辑实例 = `(namespace, service, instanceId)` 唯一标识的静态实例（host/port/status/weight/metadata；protocol/url 等扩展属性走 metadata，D13 哲学延伸）；`PUT /admin/v1/logical-instances`（upsert，内容相同幂等 no-op）、`DELETE`（幂等）、`GET`（namespace/service 过滤 + limit）。归一同注册入口（status 缺省 UP、weight ≤ 0 → 100）。变更写审计摘要行（`LOGICAL_INSTANCE_UPSERT/DELETE`）。
- **持久化（V4，双方言）**：`artemis_logical_instance`（唯一键三元组；instanceId 上限 255——MySQL 组合唯一索引键长 3072 字节限；metadata TEXT 存 JSON）；内存态/DB 态双轨。DB 态 management 重启后逻辑实例经流快照重下发恢复。
- **下发通道**：management.v1 新增 `LogicalInstanceService.WatchLogicalInstances`（与摘除规则流同内部 gRPC 端口；快照 + 增量、at-most-once、无版本/ack——数据语义快照自愈，规则版本化归 phase-5 规则流）。见 [logical-instance-stream-api.md](../api/logical-instance-stream-api.md)。
- **discovery 统一发现视图**：逻辑实例以保留 cluster `logical` 进入投影（复用既有投影同步/查询/订阅/对账全套）——与真实实例 union；摘除对逻辑实例同样生效（instance 级按键、selector 级按 metadata）；management 不可用时沿用最后已知逻辑实例（fail-open），不门控 discovery readiness；上游 cluster id 撞保留名启动失败。
- **工程形态**：dispatcher/store/应用用例/映射单测；`LogicalInstanceStreamIT`（流契约）+ `DiscoveryLogicalInstanceIT`（三服务端到端：union/推送/摘除过滤/fail-open/0..1）+ 双方言 `LogicalInstancePersistenceIT`（跨重启重下发，无 Docker 环境跳过）。

**v0.4 phase-5（规则下发闭环）已交付**：

- **规则版本化**：摘除规则流事件（快照与增量）携带规则集状态版本 = **触发生效态翻转的记录 sequence**（DB 自增主键 / 内存计数器，零新增状态；叠加/对消无翻转不推亦不产生版本）。版本是**水位/观测语义，非顺序协议**——跳号合法、等/旧号增量幂等重放，正确性骨干仍是 at-most-once + 断流重连快照全量覆盖自愈。
- **生效 ack 上行**：`EjectionRuleService` 增 `AckEjections` unary（discovery 应用规则后上报已应用版本；失败容忍，重连快照后重新 ack 收敛）；ack 走当前 watch 所在端点（订阅登记在该 management 进程）。
- **生效水位 API**：`GET /admin/v1/status/rules`——当前版本 + 各**活订阅**已应用版本（appliedVersion/lastAckAt/atLatest）+ 已生效节点数（appliedLatestCount）。水位只反映活订阅（断流即清除，不冒充已生效、免无界滞留），内存观测态不持久化（重启经重连重建）；订阅条目带代际令牌，同 id 重连的迟到旧流注销不误删新条目。
- **内存/DB 双态语义**：内存态 management 重启版本随记录归零（快照全量替换 + 重新 ack 后水位重新一致）；DB 态版本跨重启单调延续（sequence 持久）。
- **discovery 本地对照**：`/admin/v1/upstreams` 增 `management.appliedVersion`（本节点已应用版本，-1 = 尚未应用）。
- **工程形态**：`lastFlipSequence` 纯函数与 AckRegistry（代际令牌）单测 + 同步器 ack 行为单测；`EjectionRuleStreamIT`（版本 + ack 水位契约）+ `DiscoveryRuleAckLoopIT`（三服务端到端：水位可见 / management 重启重连重新 ack / 双 discovery 节点生效计数）+ 双方言 `EjectionPersistenceIT` 增版本跨重启延续断言。见 [ejection-rule-stream-api.md](../api/ejection-rule-stream-api.md)。

## 更新历史

| 版本 | 日期 | 变更说明 |
| ------ | ------ | ------ |
| 1.29 | 2026-10-26 | v0.4 phase-5 交付：规则下发闭环——版本化规则流（版本 = 翻转记录 sequence）+ discovery ack 上行 + 生效水位 API（`GET /admin/v1/status/rules`）+ discovery 本地应用版本对照；phase-6 节「无版本/ack」表述修正 |
| 1.28 | 2026-10-25 | v0.4 phase-4 交付：逻辑实例（静态实例）——模型/CRUD/V4 双方言持久化 + 专属下发流 + discovery 保留 cluster `logical` 统一发现视图（union/摘除生效/fail-open） |
| 1.27 | 2026-10-25 | v0.4 phase-3 交付：四级摘除（metadata selector 实时匹配）+ 操作记录即状态（叠加/反向对消）+ V3 双方言持久化与审计 + 统一 ejection API + 跨重启重下发恢复 |
| 1.26 | 2026-10-25 | v0.4 phase-2 交付：单管理员 DB 账户 + 统一认证接管（ADR-021：管理访问面收敛、配置凭证 seed 物化）、改密 API |
| 1.25 | 2026-10-25 | v0.4 phase-1 交付：management DB 可选装配（MySQL/PG 双方言 + Flyway + 双轨运行）、`GET /admin/v1/status/persistence`；交付状态首段 v0.3 收尾修正 |
| 1.24 | 2026-10-24 | v0.3 phase-9 交付：starter 双用途条件装配（缺地址不 fail-fast、配置桥同名让位/按名绑定）、`artemis-dist` all-in-one 单包（同 JVM 双上下文、`artemis.dist.*`）；新建 api/all-in-one.md |
| 1.23 | 2026-10-24 | v0.3 phase-8 交付：发现侧 Java 8 SDK（订阅/缓存/快照/兜底/地址容灾）、discovery 静态节点目录（ADR-020）、`RegistryClient` 更名与 `clientId`；新建 api/sdk-java-discovery.md、api/discovery-nodes-api.md |
| 1.22 | 2026-10-24 | v0.3 phase-7 交付：discovery 只读发现管理面（订阅关系/服务视图/上游与规则源状态）；新建 api/discovery-admin-api.md |
| 1.21 | 2026-10-24 | v0.3 phase-6 交付：management 摘除规则流 + discovery 过滤器链（三出口一致过滤、跨 cluster 规则、fail-open/最后已知规则、0..1 可选） |
| 1.20 | 2026-10-24 | v0.3 phase-5 交付：多集群聚合（union + cluster 选择器过滤、跨源隔离、JSON map 上游配置、readiness 全量门） |
| 1.19 | 2026-10-24 | v0.3 phase-4 交付：hash 对账（canonical 规则单一来源 + golden vector）、新鲜度标记；新建 api/discovery-reconciliation-api.md |
| 1.18 | 2026-10-24 | v0.3 phase-3 交付：推拉结合订阅推送（ADR-019：增量流 + labels 视图翻译 + diff 续推）；新建 api/discovery-subscription-api.md |
| 1.17 | 2026-10-23 | v0.3 phase-2 交付：discovery 投影内核（消费安全序/自愈重连/readiness）与 discovery.v1 查询 API；新建 api/discovery-query-api.md |
| 1.16 | 2026-10-23 | v0.3 phase-1 交付：projection.v1 分发契约与 registry 侧投影数据源；核心概念补「投影分发」 |
| 1.15 | 2026-10-23 | v0.2 版本收尾：交付状态更新为已交付（验收对照见 v0.2/plan.md），下一版本 v0.3 |
| 1.14 | 2026-10-23 | v0.2 phase-6 交付：节点优雅停机（DOWN 标记 → drain → 终刷 → 停端口）、就绪门三态与 DOWN 传播、SDK starter ContextClosedEvent 自动注销、K8s 排水配置注意 |
| 1.13 | 2026-10-23 | v0.2 phase-5 交付：SDK 三级地址容灾（HTTP 前置寻址 + 观测态优先级选址 + 熔断换址）与连接 TTL 轮换；`GET /cluster/nodes` 发现端点（未就绪 503）；SDK 配置键与指标递补 |
| 1.12 | 2026-10-21 | v0.2 phase-4 交付：自我保护 2.0（节点级兜底 + 服务级权威、显式状态与超时回落）、有界指标、`GetSelfProtectionStatus` 与 Admin 状态端点、集群独立判定 |
| 1.11 | 2026-10-21 | v0.2 phase-3 交付：peer 全量 bootstrap（异步/退避/整体替换/not ready 语义）、三段式成员配置、对外节点状态 API 与后台并发探测、配置与指标；修正 phase-1 交付节对外端点表述 |
| 1.10 | 2026-10-21 | v0.2 phase-2 交付：变更事件扇出（副本租约继承原产品：RENEW 续约复制、清理不扇出、缺席补注册）、扇出管线与退避重试、复制配置项与指标；修正 v0.1 phase-2 节事件出口表述 |
| 1.9 | 2026-10-20 | v0.2 phase-1 交付：replication.v1、双 gRPC 监听与认证隔离、静态多节点成员/自身识别、配置动态性；当前版本转 v0.2 |
| 1.8 | 2026-10-19 | phase-8 交付节：Spring Boot starter 自动装配、CI 流水线、多阶段 Docker 镜像；开发者入口补镜像构建命令 |
| 1.7 | 2026-10-19 | phase-7 交付节：Java 8 注册侧 SDK（fire-and-forget、心跳自愈、生命周期、SCF 配置、Micrometer 指标）；新增 SDK API 文档 |
| 1.6 | 2026-10-19 | phase-6 交付节：management-service 独立进程、Admin API（有效视图/摘除/恢复）、状态 API、出站只读查询与错误语义；管理面 API 文档链接 |
| 1.5 | 2026-10-19 | phase-5 交付节：内部只读查询能力/脱敏/上限/契约归属（management.v1，client 不生成）；common 生成组同步 |
| 1.4 | 2026-10-18 | phase-5 交付：registry 内部只读查询 RPC（服务/实例/租约/配置状态，敏感值脱敏）；phase 编号按 ADR-016 重排（管理面 phase-6） |
| 1.3 | 2026-10-18 | phase-4 交付：SCF 配置（分层 source/热加载/动态与静态项）、默认 Basic 认证、三端口分离、Prometheus/JSON 日志/OTel、HTTP readiness、成员抽象 |
| 1.2 | 2026-10-18 | phase-3 交付：自我保护基础版（10s 窗口/85%/下限 50）、快照落盘回放（5min / maxAge 1h / 路径默认）、readiness 门控与 UNAVAILABLE 语义 |
| 1.1 | 2026-10-14 | phase-2 交付：注册四操作 + 心跳对账 + 租约 TTL/清理默认值（30s/1s，单调钟判定）+ gRPC 接入 |
| 1.0 | 2026-10-12 | 初版（首个收尾 phase 建立）：产品定位、核心概念、开发者入口、交付状态 |
