# Artemis 产品文档

版本: 2.0    更新时间: 2026-10-29

> 本文是 Artemis 的产品总览：产品定位、架构、核心概念与功能地图，并导航到快速开始、部署与 API 文档。上手从[快速开始](quick-start.md)进入；产品规划见[路线图](artemis-next-roadmap.md)，架构与设计决策见[架构设计](../arch/artemis-next-architecture.md)。

## 1. 产品定位

Artemis 是开源服务注册中心，对标 Nacos：具备原产品（在携程支撑过 10 万+ 服务实例）的核心能力并现代化重设计，目标单集群 10 万实例级。核心取舍：

- **AP 可用性优先**：对等节点异步复制 + 租约心跳，网络分区时宁可返回陈旧数据不拒绝服务；数据面纯内存，不依赖外部存储。
- **注册与发现分离**：registry（注册面）与 discovery（发现面）独立部署、独立扩缩；发现侧单节点可聚合多个 registry 集群。
- **数据面与管理面双轨**：注册发现数据（内存 + 复制 + 快照）与治理元数据（DB 持久化）分离，数据面零 DB 依赖。
- **零依赖起步**：单进程 all-in-one + 内存态治理即可完整体验，生产按规模拆分部署。

成功标准与迭代策略见[路线图 §1](artemis-next-roadmap.md#1-定位与成功标准)。

## 2. 架构总览

三个可独立部署的服务组成产品：

| 服务 | 角色 | 数据 | 形态 |
|---|---|---|---|
| registry | 注册面：接收注册/心跳，维护权威注册表 | 纯内存 + 快照落盘，对等复制 | 集群（1..N 对等节点） |
| discovery | 发现面：消费 registry 分发流，提供查询/订阅 | 只读投影副本，不组集群 | 单节点或多实例（各自独立聚合） |
| management | 治理面：摘除/灰度/逻辑实例/账户与审计 | MySQL / PostgreSQL 或内存态 | 单实例 |

- **数据流**：业务实例 → registry（心跳即注册）；registry → discovery（全量快照 + 增量变更流的投影分发）；management → discovery（治理规则流 + 逻辑实例流）；消费方 → discovery（订阅/查询）。
- **管理流**：management 经 registry 内部只读端口查询事实（服务/实例/租约/保护态/配置）。
- 三服务可合并为单进程 all-in-one（[all-in-one.md](../api/all-in-one.md)），治理链默认闭合、DB 可选。
- 端口约定与全部配置键见[配置参考](../deploy/config-reference.md)。

## 3. 核心概念

- **服务注册与发现**：实例向注册中心注册并周期心跳续约，消费方订阅获取服务实例集。
- **namespace**：多租户隔离单元，服务的内禀属性。
- **服务与实例标识**：服务由 `(namespace, service)` 标识（`ServiceKey`）；实例由 `instance_id` 在服务内标识（`InstanceKey`）。
- **租约**：注册的事实形态——心跳续约租约（默认 TTL 30s，单调时钟判定不受系统校时影响），过期自动清理；实例状态客户端自报，服务端只存事实、不做主动探测。
- **自我保护**：续约量异常下跌（网络分区/客户端批量异常）时暂停过期清理，防大规模误摘；节点级兜底 + 服务级权威两层判定。
- **投影分发（registry → discovery）**：registry 向 discovery 推送实例事实（全量快照 + 增量变更流）；discovery 侧投影是只读副本，discovery 不组集群。
- **多集群聚合**：单 discovery 节点可同时订阅多个 registry 集群，各源投影本地独立维护、查询/订阅时刻合并（默认 union，可按来源 cluster 过滤）；聚合只发生在本地，无跨源污染。
- **逻辑实例（静态实例）**：第三方/异构服务不经注册体系，由管理面维护为静态实例，以保留来源 cluster `logical` 进入统一发现视图——与真实实例 union 出现在查询/订阅/对账中；无心跳无租约，摘除对其同样生效。
- **推拉结合**：订阅通道 = 实时推增量 + 定期拉全量 + checksum 对账兜底，增量丢失可显式检测与自愈。

数据模型与操作语义以仓库顶层 `proto/` 契约为单一来源（[proto 契约规范](../dev/proto-contract.md)）。

## 4. 功能总览

### 4.1 服务注册与发现

- **注册模型**：register / update / deregister 三操作语义明确（无条件覆盖 / 幂等 upsert / 幂等意图注销），心跳流全量上报对账——缺席实例自动补注册、内容变化覆盖，客户端崩溃后无需显式恢复。
- **发现订阅**：server-stream 推送，首条全量基线 + 稳态实时增量；按 services / labels / clusters 选择器订阅，labels 订阅推送即视图语义（进出视图由服务端翻译，SDK 直接 apply）。
- **兜底与新鲜度**：checksum 对账（默认 60s）显式检测增量丢失，全量刷新（15min）为最终兜底；查询/对账响应携带上游新鲜度（`SYNCED` / `DISCONNECTED` 陈旧标记），消费方可感知数据降级。
- **SDK 侧容灾**：本地视图不可变快照零拷贝读、磁盘快照冷启动回放，server 全挂时最后视图继续服务。

### 4.2 集群与高可用

- **对等复制（无主）**：心跳续约即向 peer 扇出维持副本；删除不跨节点传播——收不到续约的租约全集群分别过期，分区恢复自动补注册；单节点部署零复制开销。
- **新节点 bootstrap**：加入/重启优先从就绪 peer 全量同步（分片流 + 整体替换），无 peer 时回退本地快照，否则空表就绪（心跳即注册自愈）。
- **优雅停机与滚动升级**：停机先标记 `DOWN`（SDK 选址降权）→ 排水等待 → 终刷快照，客户端无感换址；proto wire 兼容纪律 + 未知字段前向兼容由 CI 契约门槛保障，3 节点滚动重启不断流。
- **快照与恢复**：注册表默认 5min 原子落盘，重启只回放时效内（默认 1h）快照，TTL 从回放时刻重新起算；缺失/损坏/超期统一空表启动不拒绝服务。

### 4.3 弹性与保护

- **两层自我保护**：服务级窗口为权威（该服务峰值达 `minCount` 后按服务判定，保护中的服务保留租约、其余照常清理），节点级全局窗口兜底网络分区/小服务；显式 deregister 不受影响；健康后超时自动回落（防客户端迁移造成永久保护）。
- **集群语义**：各节点只按本地收到的心跳独立判定，保护态不复制不持久化。
- **容量与已知限制**：见[§6 性能与容量](#6-性能与容量)。

### 4.4 流量治理（management）

治理能力经管理面 API（[admin-api.md](../api/admin-api.md)）与内嵌 console 操作，规则经版本化规则流下发 discovery，生效水位可观测（`GET /admin/v1/status/rules`，含各活订阅已应用版本）。

- **四级摘除**：INSTANCE / SERVER / ZONE / GROUP（后三级按实例 metadata 键 `server`/`zone`/`group` 取值匹配，实时生效、新注册实例天然命中）；操作记录即状态（EJECT 可叠加 / RECOVER 反向对消，不删历史），全部治理操作进审计表。
- **route-rule 两段发布**：编辑列与生效列物理分离——编辑通道永不触碰线上权重（修复「编辑清空已发布权重」缺陷模式）；`release` 生效并落 append-only 发布记录，`rollback` 指向历史记录一键还原，`diff` 发布预览。
- **canary 一键用法**：`POST /admin/v1/canary/{ns}/{svc}`（selector + weight）一个 API 完成金丝雀部署——weight 0 = 金丝雀实例摘出（dark launch）、10000 = 稳定组全量切流，`DELETE` 结束灰度；金丝雀身份以实例 metadata 表达（如 `version=canary`），全程零 IP 维护。
- **逻辑实例**：第三方服务静态实例纳入统一发现视图（见 §3）。
- **console**：内嵌原生单文件 SPA（management 外部端口根路径，零前端依赖），服务/实例视图、摘除、路由规则发布闭环、订阅关系、审计流、自我保护态七视图；与 Admin API 同源认证。

### 4.5 安全

- **认证**：各服务默认开启 HTTP Basic 单账户（eval 默认凭证 `artemis`/`artemis`，生产必改）；registry/discovery 外部端口与内部 peer 端口凭证分域互不通用；management DB 态下账户唯一源为账户表（PBKDF2 哈希存储，支持改密与多账户）。
- **RBAC**：management 预置 ADMIN / OPERATOR / VIEWER 三角色，`/admin/v1` 逐端点判权、未登记路径默认 deny（401 未认证 / 403 越权分层）；角色→权限矩阵为产品语义。
- **TLS**：显式启用、默认明文（eval 零依赖路径不变）——外部端口经 Spring SSL bundle，内部通道经 SCF 键；server-auth（加密 + 服务端身份校验），证书 PEM 文件轮换重启生效。

### 4.6 可观测

- **指标**：各服务管理端口 `/actuator/prometheus`（实例数、心跳、复制、bootstrap、订阅推送、自我保护等）；SDK 侧 Micrometer 指标随宿主应用暴露。
- **告警**：`deploy/alerts/` 提供 Prometheus 规则 7 条（节点失联/保护态/复制健康）+ scrape 配置 + Alertmanager 路由示例；处置路径见[运维手册](../deploy/operations.md)。
- **状态 API**：租约明细、自我保护统计（节点级 + 服务级）、配置状态（敏感值脱敏）、持久化形态、规则生效水位、上游新鲜度与订阅关系。
- **日志与追踪**：结构化 JSON 日志；OpenTelemetry trace 可选启用。

### 4.7 SDK

Java 8 SDK（零 Spring 依赖），注册与发现统一，坐标（版本线独立于服务端，首版 1.0.0）：

```xml
<dependency>
    <groupId>org.mydotey.ai.artemis</groupId>
    <artifactId>artemis-client</artifactId>
    <version>1.0.0</version>
</dependency>
```

- **注册侧 `RegistryClient`**：fire-and-forget（本地集同步更新后异步上报，失败内部重试 + 心跳自愈，调用方不处理操作结果）；close 即优雅下线，实例即刻消失。
- **发现侧 `DiscoveryClient`**：按选择器独立订阅、本地视图 union、磁盘快照冷启动兜底。
- **地址容灾**：三级地址管线（配置 url → HTTP 前置寻址 → 节点目录）+ 节点观测态优先级选址 + 熔断换址 + 连接 TTL 定期轮换——节点死亡客户端无感切换。
- **Spring Boot**：`artemis-client-spring-boot-starter`（2.7+）双侧自动装配、生命周期托管、MeterRegistry 注入。
- 用法见 [sdk-java.md](../api/sdk-java.md) / [sdk-java-discovery.md](../api/sdk-java-discovery.md)；多语言 SDK 按路线图 v1.x 推进。

## 5. 部署形态

| 形态 | 适用 | 入口 |
|---|---|---|
| Docker all-in-one | 体验/开发/单机小规模 | 一条 `docker run` 起三服务（[docker.md](../deploy/docker.md)） |
| VM 安装包 | 虚拟机/物理机 systemd 托管 | `artemis-<version>.tar.gz` + `app.sh` 运维脚本（[vm.md](../deploy/vm.md)） |
| Kubernetes Helm | 生产容器编排 | chart 三服务独立编排、DB/TLS 可选（[k8s.md](../deploy/k8s.md)） |
| 单服务镜像 | 按角色独立部署/扩缩 | registry / discovery / management 各自镜像 |

部署共性：配置全 env 可注入（relaxed 映射）、DB 可选（未配置即内存态）、TLS/认证透传、探针与优雅停机对齐编排语义。运维（启停升级/监控接入/告警处置/排障/数据恢复）见[运维手册](../deploy/operations.md)，全量配置键见[配置参考](../deploy/config-reference.md)。

## 6. 性能与容量

small 档实测（3 节点 registry + 1 万实例，[容量基线](../deploy/capacity-baseline.md)）：

| 指标 | 结果 |
|---|---|
| 注册风暴 | 30s ramp 全部到位 |
| 稳态续约 | 10 分钟零丢失（续约率 2100/s 吻合理论值） |
| 订阅推送延迟 | p50 = 5ms / p95 = 7ms（每订阅者独立配对） |
| 副本一致性 | 3 节点全量一致 |
| 内存公式 | registry ≈ 65KB/实例；10 万实例外推口径与直测复现命令见基线文档 |

已知限制：3 节点拓扑下订阅推送实时性退化（观察率 37%，部分事件靠对账分钟级收敛；单拓扑 p95 = 7ms 对照），机制排查后置 v1.x。

## 7. 版本与路线

当前 **v1.0 GA（生产就绪）**：注册发现集群闭环（复制 / bootstrap / 优雅停机 / 滚动升级验证）+ 完整治理面（摘除 / 灰度 / 逻辑实例 / console / RBAC）+ TLS + 三种部署形态 + 容量基线。

- 版本规划与后置项：[路线图](artemis-next-roadmap.md)（v1.x：多语言 SDK、Rust 混合集群等）
- 开发进度与 phase 状态：[dev-state.md](../milestones/dev-state.md)（唯一状态源）
- API 全集：[API 参考门户](../api/README.md)

## 8. 参与开发

源码为多语言 monorepo（`proto/` 语言无关契约 + `java/` Maven reactor）。构建命令、模块边界与技术选型见仓库根 `CLAUDE.md` 与 `docs/dev/`（[tech-stack-java.md](../dev/tech-stack-java.md)、[coding-guidelines-java.md](../dev/coding-guidelines-java.md)）。

## 更新历史

| 版本 | 日期 | 变更说明 |
| ------ | ------ | ------ |
| 2.0 | 2026-10-29 | 重写为产品总览文档：按能力维度组织（架构总览 / 功能地图 / 部署形态 / 性能容量）；「交付状态」phase 日志节退役——进度唯一源为 `docs/milestones/dev-state.md`，phase 明细在 `docs/milestones/`；开发者入口压缩为「参与开发」一节。1.x 版本为 phase 交付日志形态，历史可溯 git |
