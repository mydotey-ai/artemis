# Phase 1：peer 协议与静态多节点成员

版本: 1.10    更新时间: 2026-10-20

## 目标

定稿 `replication.v1` 复制契约，落地 registry 内部 gRPC 端口与静态多节点成员配置（含节点自身识别），为后续扇出与 peer bootstrap 提供契约与传输基础。

## 范围

- **做**：复制契约（成员/节点状态、全量 bootstrap、增量变更批量、投递确认/失败语义）；registry 内部 gRPC 端口（与外部端口分离、认证隔离）；静态成员多节点配置解析与自身识别；management → registry 只读事实查询迁至内部端口（架构 §2.2 v0.2 承诺）。
- **不做**：变更事件扇出与重试（phase-2）、peer bootstrap 与 readiness 分支①（phase-3）、自我保护 2.0（phase-4）、SDK 容灾与 TTL 轮换（phase-5）、优雅停机（phase-6）。

## 涉及模块

- `proto/replication/v1/replication.proto`（定稿）
- `java/server/artemis-registry-core`（成员/节点状态领域模型与端口）
- `java/server/artemis-registry-server`（内部 gRPC 端口装配、成员配置）
- `java/server/artemis-management-server`（只读查询改连内部端口）
- 测试基础设施：进程内多 registry 节点集群 harness（供本 phase 与后续 phase 复用）

## 验收方式

对应测试文档：[phase-1-peer-protocol-test.md](phase-1-peer-protocol-test.md)

## 产品设计（③ 补）

### 1. `replication.v1` 契约定稿

一次定稿四类报文（本 phase 只实现 `GetNodeStatus`，其余在 phase-2/3 实现；契约先冻结以稳定 wire 面）：

```proto
service ReplicationService {
  rpc GetNodeStatus(GetNodeStatusRequest) returns (GetNodeStatusResponse);
  rpc Replicate(ReplicateRequest) returns (ReplicateResponse);          // phase-2
  rpc Bootstrap(BootstrapRequest) returns (stream BootstrapResponse);   // phase-3
}
```

- **成员/节点状态**：节点状态自报（`NodeState`：UNSPECIFIED/UNKNOWN/STARTING/UP/DOWN），不做 peer 主动探测。静态成员只配置 **peer 端点**（连接所需的最小信息）；`GetNodeStatusResponseData = self(NodeStatus{node_id,state,started_at_ms}) + members(静态成员 peer 端点)`。**对外（SDK/管理面）端点不进本 phase 契约**：外部端口可由 server 生命周期自动取得，主机地址需结合部署形态（多网卡/NAT/K8s）与 SDK bootstrap 路径确定，留给 phase-3 节点状态 API 设计；契约按 field number 只增规则可后续追加字段。
- **增量变更批量投递**：`ReplicationBatch{source_node_id, repeated ServiceChange{key, repeated InstanceChange{instance, type}}}`——按 service 聚合，DELETE 只要求 `instance_id`。**不设 batch_seq**：`Replicate` 为 unary，响应本身即投递确认（失败/超时由发送侧重发），不存在需要序号关联的并发在途批次。投递语义：同一源节点对同一 peer 串行投递、重试保序；重复投递幂等（NEW/UPDATE 为覆盖写、DELETE 幂等）；整批拒绝走 `ResponseStatus.ACK_FAIL + ErrorInfo`（错误码复用注册表 `INVALID_ARGUMENT`），失败批次由发送侧重试。
- **全量 bootstrap**：`BootstrapRequest{requester_node_id}` → server-stream 的 `BootstrapResponse` 分片（每片 `repeated common.v1.ServiceInstances`）；流正常结束 = 全量传完，空注册表 = 零分片直接结束；中断/失败 = 客户端整轮重来（全量覆盖幂等）。
- 不在 wire 上携带 `changeTimeMs`：domain 单调钟明确「禁止跨进程比较」（D14）。
- 固定响应形式 `status + data` 遵循[proto 契约规范](../../../dev/proto-contract.md) §2。

### 2. 内部端口与认证隔离

- registry 起**两个 gRPC 监听**：外部端口（既有 `spring.grpc.server.*`，SDK 四操作 + 心跳）与内部端口（`artemis.registry.internal.*`，peer 复制 + management 只读事实查询）。
- 第二监听复用官方 `NettyGrpcServerFactory` + `GrpcServerLifecycle`（`spring-grpc-core`），不手写 `io.grpc.Server` 生命周期；内部服务**不加 `@GrpcService`**（不会被主 server 自动发现），由装配层显式 `addService` 绑定。主 server 的 `@GlobalServerInterceptor`（外部认证、readiness）不会泄漏到内部端口。
- **认证隔离**：内部端口使用独立开关与账户 `artemis.registry.internal.auth.{enabled,username,password}`——**`enabled` 默认 false（不启用），不设默认用户名/密码**；启用时 username/password 必填，空白即启动失败。启用后与外部 `artemis.registry.auth.*` 互不通用（隔离验收在此模式下测）。内部服务按 service 绑定拦截器：复制类只绑内部认证（启用时）；`RegistryQueryService` 额外绑 `ReadinessInterceptor`（保持 v0.1「未就绪不服务管理查询」语义，复制/节点状态在未就绪时仍可应答——phase-3 bootstrap 需要）。
- **management 查询迁移**：`RegistryQueryService` 从外部端口移到内部端口；management 客户端使用单一端点配置 `artemis.management.registry.endpoint`（host:port，**无默认值**；未配置时调用返回 REGISTRY_UNAVAILABLE）。凭证 `artemis.management.registry.{username,password}` 默认空——username 非空时才附加 Basic 认证（与内部认证默认关闭对齐）；凭证与 timeout 均为动态配置。
- 端口/传输配置：`artemis.registry.internal.port`（默认 9093，**固定端口 1..65535，不支持 0 动态端口**）、`artemis.registry.internal.address`（**无默认地址**；未配置时绑定所有网卡——内部端口必须可被 peer 跨节点访问，裸 IPv6 自动补方括号）、`artemis.registry.internal.maxInboundMessageBytes`（默认 16 MiB，bootstrap 分片余量）。端口/大小/停机宽限走 SCF 既有类型转换与值域过滤：非法/越界值（含端口 0）按 SCF 语义**回落默认值**，不阻断启动。内部认证默认关闭时启动 WARN：部署须以网络策略隔离内部端口或显式启用认证。gRPC handler 用命名虚拟线程 executor。

### 3. 静态多节点成员与自身识别

- 成员条目格式（逗号分隔）：`<node-id>@<peer-host>:<peer-port>`；host 支持括号 IPv6。示例：
  `artemis.registry.cluster.members=node-a@10.0.0.1:9093,node-b@10.0.0.2:9093`
  只配连接 peer 所需的最小信息；对外端点不在成员配置中（见 §1，留待 phase-3 节点状态 API 设计）。
- 自身识别显式配置 `artemis.registry.cluster.self=<node-id>`，替代原产品「URL 子串匹配本机」（legacy §6 #10）：
  - members 为空（单节点）→ `self` 必须缺省；节点无成员身份（node status 的 node_id 为空、members 为空）。
  - members 非空 → `self` 必填且必须在成员表中命中。
  - 非法条目、重复 node-id、端口越界、self 缺失/未命中均 **fail-fast**（启动失败，报配置键 + 原因）。
- 领域模型改造：
  ```java
  public record ClusterEndpoint(String host, int port) {}
  public record ClusterMember(String nodeId, ClusterEndpoint peer) {}
  public record NodeStatus(String nodeId, NodeState state, long startedAtMs) {}
  public interface ClusterMembers {
      List<ClusterMember> members();
      Optional<ClusterMember> self();
  }
  ```
- 状态映射：`ReadinessGate.isReady()` → `UP`，否则 `STARTING`；`DOWN` 留给 phase-6 优雅停机显式标记。

### 4. 本 phase 实现边界

- 实现：契约生成、双监听装配、内部认证、成员解析/自身识别、`GetNodeStatus`（domain → proto 门面 → gRPC 接入）。
- 未实现（gRPC 返回 UNIMPLEMENTED）：`Replicate`（phase-2）、`Bootstrap`（phase-3）。
- 测试基础设施：进程内多节点 harness（预分配端口 + N 个 Spring context），本 phase 起被后续 phase 复用。

### 5. 设计决策与同步落点

- **双 gRPC 监听用官方工厂/生命周期装配** → 同步 `docs/dev/coding-guidelines-java.md` §1（多端口装配例外说明）与 `docs/dev/tech-stack-java.md` §4.7。
- **内部端口承载 peer + management 查询、独立内部账户** → 同步 `docs/arch/artemis-next-architecture.md` §2.2（v0.2 端口落点）。
- **静态成员只配 peer 端点 + 自身识别；对外端点留待 phase-3** → 同步架构 §4.1「集群成员」。
- **`replication.v1` 报文形态** → 契约本体 `proto/replication/v1/replication.proto`；不改变 ADR-004/D6 既有决策，不新增 ADR。
- **管理查询端点迁移**为 §2.2 已承诺的 v0.2 落点，运维可见变化随 ⑩ 进产品文档。

## 实现结果（⑦ 补）

- **契约**：`replication.v1` 落地（成员/节点状态、增量批量、bootstrap 分片）；registry-core 生成并通过 `ProtoGenerationTest`。
- **双监听**：`PeerServerConfiguration` 用官方 `NettyGrpcServerFactory` + `GrpcServerLifecycle` 装配内部监听；factory 不作为 `GrpcServerFactory` bean（避免关闭主 server 自动装配）；主 server 经 `GrpcServerFactoryCustomizer` + `ServerServiceDefinitionFilter` 排除内部服务（Boot 会收集全部 `BindableService`）。
- **拦截器**：内部端口显式绑定内部 Basic 认证、框架观测拦截器、全局异常映射；查询额外绑 readiness。gRPC 列表「后者最外层」，auth 置末保证认证先于 readiness。
- **成员/状态**：`ClusterEndpoint`/`ClusterMember`/`ClusterMembers.self()`；静态配置 `<node-id>@<host>:<port>` + `cluster.self` 显式自身识别；host 语法校验（空白/非法 IPv6 启动失败）；IPv6 在 wire 上带方括号。
- **配置语义**：内部端口/大小/停机宽限走 SCF 类型转换与值域过滤，非法/越界值（含端口 0）回落默认值（不阻断启动）；地址无默认值（未配置绑所有网卡）；management 客户端端点无默认值。**动态配置**：租约 TTL、自我保护窗口/minCount/factor、租约清理与快照周期、management 查询 timeout、各认证 enabled/username/password（运行期可轮换）、client 心跳参数；**静态配置**：监听端口/地址、集群成员与自身标识、快照路径/回放时效、management 端点。内部认证关闭时启动 WARN 提示网络隔离。
- **生命周期**：peer executor 关闭用 `shutdownNow`，避免虚拟线程 executor `close()` 无限等待。
- **配置动态性 review**：租赁 TTL、自我保护 minCount/factor 原已动态；本轮把自我保护窗口（分桶环按上限固定、聚合读取当前窗口长度）、租约清理/快照周期（SCF 监听即时重排）、management 查询 timeout、全部认证 enabled/username/password（gRPC/HTTP 拦截器与客户端每请求现取）改为动态；监听/拓扑/快照路径等按启动期属性归为静态。
- **测试修复**：补内部认证启用下的查询认证用例、客户端凭证附加用例、主 server 过滤回归；三节点 harness 按成员表端点逐项验证并使用一次性端口预留。
- 测试结果：`dev-test.sh all` 全绿（⑤ 首跑、⑧ 回归）。

## 文档同步（⑩ 填）

- 产品文档 `docs/product/artemis.md` v1.9：当前版本转 v0.2；新增 v0.2 phase-1 交付节（复制契约、双监听与认证隔离、静态成员/自身识别、配置动态性）；更新配置动态/静态划分与 management 出站查询描述。
- 架构 `docs/arch/artemis-next-architecture.md` v1.27：§2.2 v0.2 双监听落点（内部端口承载复制 + management 查询、独立认证默认关闭、地址无默认/端口固定）；§4.1 静态成员仅配 peer 端点 + 显式自身识别。
- 开发规范 `docs/dev/coding-guidelines-java.md` v1.25：§1 多监听端口装配纪律。
- 技术选型 `docs/dev/tech-stack-java.md` v1.22：§4.7 双监听装配细节（factory 非 bean、全量 BindableService 发现需 service filter）与测试端口解析。
- API：`docs/api/admin-api.md` v1.3（单一 endpoint 无默认值、凭证/deadline 动态）；`docs/api/registry-query-api.md` v1.2（内部端口、认证动态轮换）。
- 契约：`proto/replication/v1/replication.proto` 定稿。
- 索引：`docs/milestones/v0.2/phases/README.md` 已登记本 phase 两份文档；其余各级索引无需变更。
- `CLAUDE.md`：构建命令与模块结构未变，进度以 dev-state 为准，无需修改。

## 更新历史

| 版本 | 日期 | 变更说明 |
| ------ | ------ | ------ |
| 1.10 | 2026-10-20 | ⑨ 人工验证通过；⑩ 文档同步清单回填（产品/架构/规范/选型/API） |
| 1.9 | 2026-10-20 | review 修订：management 端点恢复单一配置；认证凭证/清理与快照周期动态化 |
| 1.8 | 2026-10-20 | 配置动态性 review：自我保护窗口、management 查询 deadline 改动态；其余静态项归类说明 |
| 1.7 | 2026-10-20 | review 修订：地址不设默认值（未配置绑所有网卡/客户端视为未配置），端口必须固定（不支持 0） |
| 1.6 | 2026-10-20 | review 修订：非法配置回落默认值（不抛错），与 SCF 既有语义一致 |
| 1.5 | 2026-10-20 | review 修订：内部端口默认保持 `0.0.0.0`（peer 跨节点可达） |
| 1.4 | 2026-10-20 | ⑦ 实现结果回填；配置严格解析（code-review 修复） |
| 1.3 | 2026-10-20 | review 修订：去掉对外端点配置与 NodeStatus 对外字段（留 phase-3）；内部认证默认关闭、无默认凭证 |
| 1.2 | 2026-10-20 | review 修订：去掉 batch_seq（unary 响应即确认）；成员配置简化为 `node-id@peer-host:peer-port`，对外端点由 NodeStatus 自报 |
| 1.1 | 2026-10-20 | ③ 设计定稿：复制契约、双监听与认证隔离、静态成员自身识别、实现边界与同步落点 |
| 1.0 | 2026-10-20 | 开工骨架（① worktree 开工） |
