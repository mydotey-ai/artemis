# Artemis 产品文档

版本: 1.9    更新时间: 2026-10-20

> 本文是 Artemis 的产品文档：产品定位、核心概念、开发者入口与当前交付状态。随版本递补——每个 phase 收尾时同步本版交付的对外可见能力。产品规划与版本路线见[路线图](artemis-next-roadmap.md)，架构与设计决策见[架构设计](../arch/artemis-next-architecture.md)。

## 1. 产品定位

开源服务注册中心，对标 Nacos：具备原产品（支撑过 10 万+ 实例）的核心能力并现代化，单集群 10 万实例级。成功标准与迭代策略见[路线图 §1](artemis-next-roadmap.md#1-定位与成功标准)。

## 2. 核心概念

- **服务注册与发现**：实例向注册中心注册并周期心跳续约，消费方订阅获取服务实例集。
- **namespace**：多租户隔离单元，服务的内禀属性。
- **服务与实例标识**：服务由 `(namespace, service)` 标识（`ServiceKey`）；实例由 `instance_id` 在服务内标识（`InstanceKey`）。
- **实例状态**：客户端自报（服务端不做主动探测）；实例数据由客户端上报，服务端只存事实。

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
| `spring-boot-starter-artemis-client` | 8 | client 的 Spring Boot 集成（自动装配、生命周期托管、MeterRegistry 注入） |

## 4. 交付状态

当前 **v0.2（registry 集群）**，进行中；进度以 `docs/milestones/dev-state.md` 为准。

**phase-1（脚手架 + proto 契约骨架）已交付**：`java/` 十模块 Maven reactor 骨架（parent + 各 core/server/client/starter）与 `proto/` 六组契约骨架（v0.1 定稿 `common.v1` + `registry.v1`，其余四组占位）；`mvn -f java/pom.xml verify` 全绿，enforcer 模块边界断言（core 禁 Spring、client 禁服务端模块）生效。

**phase-2（注册模型四操作 + 租约过期清理 + gRPC 装配）已交付**：

- **注册表服务**：`registry.v1` 四操作（register 无条件覆盖 / deregister 意图幂等 / update 幂等 upsert）与心跳全量上报对账（逐实例续约 + 缺席自动注册 + 内容变化覆盖，不做集合 diff）落地；批量部分失败语义（逐实例 `INVALID_ARGUMENT` 明细，weight ≤ 0 归一 100）。
- **租约与过期**：心跳续约租约，默认 TTL **30s**（原产品 20s），过期清理周期默认 **1s**；TTL 判定用**单调时间源**——系统校时（NTP 跳变）不影响过期正确性。
- **接入**：gRPC 服务（Spring Boot 官方 starter 装配，默认端口 9090；心跳为 bidi-stream 逐请求 ack，`seq` 归属 + `server_time_ms` 服务器时间基准）；同 key 变更事件有序派生（为 v0.2 集群复制预留，当前进程内出口为 no-op）。
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

- **公开 API**：`ArtemisClient`（`start`/`register`/`update`/`deregister`/`close`）+ 不可变值类型 `ServiceId`/`InstanceId`/`ServiceInstance`/`InstanceStatus`；入口 `ArtemisClients.newClient(...)`（无 builder）。完整用法见 [sdk-java.md](../api/sdk-java.md)。
- **操作语义**：fire-and-forget——本地集同步更新后异步上报，失败内部重试 + 心跳自愈，调用方不处理操作结果；重试前重读本地集，防陈旧状态重放。
- **心跳与容错**：gRPC bidi 流全量上报（默认 5s），seq/ack、指数退避重连、ack 看门狗与 keepAlive 感知半开连接；三级地址容灾骨架（配置地址列表随机选址，运行时断流换址）。
- **生命周期**：`start`/`close` 幂等；close 先停心跳、等待在途上报，再全量注销——优雅下线实例即刻消失；零非 daemon 线程遗留。
- **配置**：全部经 SCF `artemis.client.*`（地址/凭证/重试/心跳/重连），环境变量容器友好映射；注册侧不做磁盘快照（崩溃后业务重新注册 + 心跳幂等收敛）。
- **可观测**：Micrometer 指标（操作失败/耗时、心跳失败、重连、连接态、本地实例数，带 client 标签），缺省挂 `Metrics.globalRegistry`，starter 将自动接 Boot `MeterRegistry`。
- **工程形态**：client 24 单测（JDK 8 运行时）；registry-server 侧 `ClientSdkIT` 5 例端到端（注册可见/续约/更新/优雅下线/错误凭证）。

**phase-8（Spring Boot starter + CI + Dockerfile）已交付**：

- **starter**：`spring-boot-starter-artemis-client`（Java 8 / Spring Boot 2.7+）自动装配——`spring.factories`（老 2.x）+ `AutoConfiguration.imports`（2.7+）双注册；配置经 Spring Environment 进 SCF（application.properties/YAML、命令行、系统属性/环境变量，键写法 camelCase/kebab/snake 均可）；`initMethod=start` / `destroyMethod=close` 托管生命周期；自动注入 Boot `MeterRegistry`（无 registry 或多个同型 bean 时回落 `Metrics.globalRegistry`）；`artemis.client.enabled=false` 可关闭，宿主自定义 `ArtemisClient` 时让位。用法见 [sdk-java.md](../api/sdk-java.md#spring-boot-自动装配)。
- **CI**：GitHub Actions 按路径触发（`java/**`、`proto/**`、Dockerfile/workflow 变更）；`java` job 以 JDK 25 构建、经 Maven toolchains fork JDK 8 跑 client/starter 测试，强制 spotless:check + enforcer + 单测/集成测试；`docker` job 构建镜像。
- **镜像**：仓库根多阶段 Dockerfile（`maven:3.9-eclipse-temurin-25` → `eclipse-temurin:25-jre`），`docker build .` 一条命令产出 registry 单服务镜像；非 root 运行，快照目录 `/app/data` 可写；暴露外部 gRPC 9090 / HTTP 8080。

**v0.2 phase-1（peer 协议与静态多节点成员）已交付**：

- **复制契约**：`replication.v1` 一次定稿成员/节点状态、增量变更批量投递（按 service 聚合，unary 响应即确认）、全量 bootstrap（server-stream 分片）；本 phase 实现 `GetNodeStatus`，`Replicate`/`Bootstrap` 在 phase-2/3 实现前返回 `UNIMPLEMENTED`。
- **双 gRPC 监听**：外部端口仅承载 SDK；新增内部 peer 端口（默认 **9093**，固定端口，地址无默认值——未配置时绑所有网卡）。内部端口承载 peer 复制/节点状态与 management 只读查询，使用独立认证账户（默认关闭；启用后与外部账户互不通用）。关闭认证的部署需以网络策略隔离内部端口。
- **静态多节点成员**：`artemis.registry.cluster.members=<node-id>@<host>:<port>,...`（支持括号 IPv6），`artemis.registry.cluster.self` 显式标识本节点；非法条目/重复 id/self 未命中启动失败。节点对外端点不进入成员配置（留待节点状态 API 版本）。
- **配置动态性**：自我保护窗口、清理/快照周期、认证开关与凭证、management 查询 deadline 支持运行期变更（SCF 监听/每次取值）；监听地址与集群拓扑等启动期属性变更需重启。
- **工程形态**：双监听/认证隔离/内部查询迁移/3 节点进程内成员互见 IT；proto、架构、开发规范与 API 文档同步。

## 更新历史

| 版本 | 日期 | 变更说明 |
| ------ | ------ | ------ |
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
