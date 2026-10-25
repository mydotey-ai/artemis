# Phase 4：逻辑实例（静态实例纳入统一发现视图）

版本: 1.5    更新时间: 2026-10-25

## 目标

第三方/异构服务注册为静态实例（logical instance），经 management 持久化并纳入 discovery 统一发现视图（与真实实例 union）。

## 范围

做什么：

- 逻辑实例模型与持久化（management 域模型 + 双方言语义统一的 V4 迁移 + 双轨存储端口）。
- Admin API：`/admin/v1/logical-instances` 资源 CRUD（upsert / delete / list）+ 审计摘要行。
- 下发通道：management.v1 新增 `LogicalInstanceService.WatchLogicalInstances` 订阅流（与 `EjectionRuleService` 同内部 gRPC 端口、同 envelope 模式；不复用摘除规则流——见 §5）。
- discovery 投影合并：逻辑实例以保留 cluster id `"logical"` 进入 `ProjectionRegistry`，复用既有投影同步/查询/订阅/对账全套，与真实实例 union。

不做什么：

- **零数据面契约变更**（③ 显式登记）：common/registry/replication/projection/discovery 五组不动；`LogicalInstance` 复用 `common.v1.Instance` wire 模型，仅 management.v1（治理面契约）演进。
- management 侧服务视图合并逻辑实例（服务列表/详情含纯逻辑服务）归 phase-6 console 视图组合。
- 逻辑实例不做健康检查/探活（客户端自报哲学延续——静态实例无心跳，status 为管理面静态事实，分发照原样、消费方自行决策）。
- 规则版本 + ack（phase-5 规则流范畴，不涵盖本流——数据语义快照自愈，见 §5）。

## 涉及模块

| 模块 | 变更 |
|---|---|
| `proto/management/v1/management.proto` | 新增 LogicalInstanceService 组（消息 + service） |
| `java/server/artemis-management-core` | domain：`LogicalInstance`、`LogicalInstanceStore`、`LogicalInstancePublisher`/`LogicalInstanceSubscriptions`/`LogicalInstanceSubscriber`；application：`ManagementService` 扩展 + mapper；infrastructure：`InMemoryLogicalInstanceStore`、`LogicalInstanceDispatcher` |
| `java/server/artemis-management-server` | `db/migration/{postgresql,mysql}/V4__logical_instance.sql`；repository：Entity/Mapper/StoreImpl；http：AdminController 扩展；grpc：`LogicalInstanceGrpcService`（并入 `ManagementGrpcConfiguration` 条件装配） |
| `java/server/artemis-discovery-core` | 仅 `DiscoveryProtoMapper` 增逻辑实例事件映射（复用 ProjectionRegistry/ProjectionSynchronizer，零结构改动） |
| `java/server/artemis-discovery-server` | upstream：`GrpcLogicalInstanceSource`（ProjectionSource 适配）；`DiscoveryConfiguration`/`UpstreamSynchronizers` 装配扩展 |

## 验收方式

对应测试文档：[phase-4-logical-instance-test.md](phase-4-logical-instance-test.md)

## 产品设计（③ 补）

### 1. 模型与语义

逻辑实例 = 由管理面维护的静态服务实例（不经注册体系），资产 #6 继承。唯一键 `(namespace, service, instanceId)`。

```java
// management-core domain（复用既有 domain Instance / ServiceKey）
public record LogicalInstance(ServiceKey service, Instance instance) {}
```

- wire 复用 `common.v1.Instance`（instance_id/host/port/status/weight/metadata）；protocol/url 等扩展属性以 metadata 表达（D13 哲学延伸——分组同法）。
- **归一（application 层，registry 入口同规则）**：status UNSPECIFIED → UP；weight ≤ 0 → 100。
- status 为静态管理事实：无租约、无 TTL、无过期清理；DOWN 逻辑实例照常分发（事实分发的既有口径，消费方决策）。

### 2. Admin API（HTTP，`/admin/v1`，资源导向）

| 端点 | 语义 |
|---|---|
| `PUT /admin/v1/logical-instances` | upsert（全量覆盖语义）；内容与当前一致 → no-op（不写库不推送不审计，返回当前视图） |
| `DELETE /admin/v1/logical-instances?namespace=&service=&instanceId=` | 幂等删除；缺失 → no-op 204（recover 先例） |
| `GET /admin/v1/logical-instances?namespace=&service=&limit=` | 列表（namespace/service 过滤可选；limit 默认 100、上界 1000，同 ejections） |

- PUT body：`{namespace, service, instanceId, host, port, status?, weight?, metadata?}`；响应 = 落库后视图。
- 校验（信任边界一次，失败 = INVALID_ARGUMENT / HTTP 400 既有出口）：namespace ≤255、service ≤255、instanceId ≤255（④ review 修正：MySQL utf8mb4 组合唯一索引键长上限 3072 字节，512 会建表失败——逻辑实例 id 为运维命名非 SDK 生成，255 与 ejection 的 512 分立）；host 非空 ≤255；port ∈ 1..65535；status ∈ {UP, DOWN}；weight ≥ 0；metadata 值非空且序列化 JSON ≤ 16KB。
- 审计：变更时写摘要行（operation = `LOGICAL_INSTANCE_UPSERT` / `LOGICAL_INSTANCE_DELETE`，target = 键描述，detail = `host:port`）；尽力而为（同摘除审计，架构 §5.3）。

### 3. 持久化（V4，双方言）

```sql
CREATE TABLE artemis_logical_instance (
    id          BIGSERIAL/BIGINT AUTO_INCREMENT NOT NULL,
    namespace   VARCHAR(255)  NOT NULL,
    service     VARCHAR(255)  NOT NULL,
    instance_id VARCHAR(255)  NOT NULL,   -- 255：MySQL 组合唯一索引键长 3072 字节限（(255+255+512)*4 超限）
    host        VARCHAR(255)  NOT NULL,
    port        INT           NOT NULL,
    status      VARCHAR(16)   NOT NULL,
    weight      INT           NOT NULL,
    metadata    TEXT          NOT NULL,   -- JSON 字符串（gson 序列化，双方言零方言层差异）
    created_at  TIMESTAMP WITH TIME ZONE / DATETIME(6) NOT NULL,
    updated_at  同上 NOT NULL,
    CONSTRAINT pk_artemis_logical_instance PRIMARY KEY (id),
    CONSTRAINT uk_logical_instance UNIQUE (namespace, service, instance_id)
);
```

- 端口（双轨判据 = mapper 可得性，phase-2 模式；读-改-写在应用层锁内，免双方言 upsert SQL）：

```java
public interface LogicalInstanceStore {
    List<LogicalInstance> findAll();
    LogicalInstance upsert(LogicalInstance instance);   // 返回 previous（null = 新增）
    LogicalInstance delete(ServiceKey service, String instanceId);  // 返回 removed（null = 缺失）
}
```

- 内存实现：CHM 键 `(ServiceKey, instanceId)`；DB 实现：MyBatis-Plus mapper + Entity，metadata JSON 以 gson 直依赖序列化（版本随 server parent 管理；组件总表补登记行——discovery-server phase-5 同例）。

### 4. 下发通道（management → discovery，通道① 同端口新流）

```proto
message LogicalInstance {
  artemis.common.v1.ServiceKey service = 1;
  artemis.common.v1.Instance instance = 2;
}
message LogicalInstanceKey {
  artemis.common.v1.ServiceKey service = 1;
  string instance_id = 2;
}
message LogicalInstancesRequest { string subscriber_id = 1; }
message LogicalInstancesSnapshot { repeated LogicalInstance instances = 1; }
message LogicalInstancesEvent {
  LogicalInstancesSnapshot snapshot = 1;   // message presence 区分空快照与增量（EjectionRulesEvent 先例）
  LogicalInstance upserted = 2;            // 新增/覆盖（upsert 语义）
  LogicalInstanceKey deleted = 3;          // 与 upserted 互斥，单条消息恰一 action
}
service LogicalInstanceService {
  rpc WatchLogicalInstances(LogicalInstancesRequest) returns (stream LogicalInstancesEvent);
}
```

- 语义：首条快照（含空集）→ 增量按产生序；**at-most-once、无版本/ack**——逻辑实例是数据而非规则，断流重连快照全量覆盖自愈（语义对齐通道③ 投影流；phase-5 规则版本化不涵盖本流，**显式登记**）。慢消费者断流注销（服务端 `isReady` 背压，D-E10 同例）。
- management 侧端口与分发（模式同 ejection，独立 dispatcher 实例与锁，无交叉持锁）：

```java
public interface LogicalInstanceSubscriber extends AutoCloseable {
    void onSnapshot(List<LogicalInstance> instances);
    void onUpserted(LogicalInstance instance);
    void onDeleted(ServiceKey service, String instanceId);
}
public interface LogicalInstancePublisher {
    void onUpserted(LogicalInstance instance);
    void onDeleted(ServiceKey service, String instanceId);
}
public interface LogicalInstanceSubscriptions {
    void subscribe(LogicalInstanceSubscriber subscriber);   // 登记即推快照（同锁段，无半增量竞态）
    void unsubscribe(LogicalInstanceSubscriber subscriber);
}
```

- 推送时机：upsert **内容变化**（identical no-op 不推）、delete **命中**；推送在应用层锁内与存储写成对（乱序防护，phase-3 review #1 先例）。
- 内存态重启清零 → discovery 重连收空快照收敛（期 1 设计内行为，DB 态闭环——ejection 同型）。

### 5. discovery 投影合并（保留 cluster，plan 待定项定稿）

**方案**：逻辑实例以保留 cluster id `"logical"` 进入 `ProjectionRegistry`——`GrpcLogicalInstanceSource` 将逻辑实例流适配为 `ProjectionSource`：

- 流首快照 → 按 ServiceKey 分组成 `List<ServiceInstances>`，先 stash 后 `onEstablished()`；`bootstrap()` 返回 stash（synchronizer 在 established 后调用，立即可得）。
- `upserted` → `ServiceChange(InstanceChange(instance, UPDATE))`——UPDATE 在投影 apply 与订阅翻译两路径均为 upsert 语义（翻译分支天然区分新入视图 NEW / 视图内 UPDATE / 退出 DELETE），恒正确；`deleted` → `InstanceChange(仅 instanceId, DELETE)`。
- 复用 `ProjectionSynchronizer` 全套：断连重连 diff 续推（D-S7）、订阅扇出、checksum 对账（hash 对账覆盖逻辑实例）、多 cluster union、`UpstreamStatuses` 新鲜度（`logical` 条目进上游状态表与管理面视图）。

**取舍**（否决方案）：查询时独立存储合并——订阅推送、checksum、重连 diff 需对逻辑实例逐一特判，侵入三条路径；投影 cluster 方案零结构改动复用全部机制。混入摘除规则流（envelope 加字段）——语义泥化（规则 vs 数据），且 phase-5 规则流版本化会被数据负载绑架。

**边界语义**：

- readiness **不受** logical 源门控（0..1 治理增强；`ProjectionSyncListener` 传 no-op——management 不可用时最后已知逻辑实例保持服务、状态 DISCONNECTED，fail-open 同规则流 D-E3）。
- 默认 union 查询/订阅含逻辑实例（cluster=`logical` 条目与真实实例并列）；按 cluster 过滤的选择器不命中 `logical`（逻辑实例不属任何 registry 集群，语义正确）。
- 上游 cluster id 与 `"logical"` 冲突 → 启动 fail-fast（保留名，防静默合并）。
- 摘除对逻辑实例同样生效：instance 级按 `(namespace, service, instanceId)`、selector 级按 metadata 匹配（`EjectionRuleSynchronizer.fanoutDiff` 遍历全 cluster 含 `logical`，零改动）。

### 6. 实现落点补充

- `ManagementService` 扩展：`upsertLogicalInstance(LogicalInstance, operator)`、`deleteLogicalInstance(ServiceKey, instanceId, operator)`、`listLogicalInstances(namespace, service, limit)`；应用层 `logicalLock`（用例级互斥，ejectionLock 先例）。
- `UpstreamSynchronizers` 构造扩展：接受可选 logical source（未配置 management 时不建同步器零开销），构建 `ProjectionSynchronizer(clusterId="logical", no-op listener)` 并入统一 start/close 编排；构造时保留名冲突检查。
- `ManagementGrpcConfiguration`：`LogicalInstanceService` 并入内部端口条件装配（同 `EjectionRuleService`）。
- `PersistenceDialectTestBase` schema 版本断言 3 → 4（既有双方言 IT 随 V4 修订）。

### 7. review 定案登记（2026-10-25 通过）

- **下发通道 = 通道① 同端口新流**（`LogicalInstanceService`，无版本/ack）：原始文档 = 架构 §2.2 端口落点，同步落点 = §2.2 v0.3 端口段落补 v0.4 句（架构 v1.42）。
- **discovery 合并 = 保留 cluster `logical` 进投影**（复用 ProjectionSynchronizer；不门控 readiness；保留名冲突 fail-fast）：原始文档 = 架构 §4.2，同步落点 = §4.2 投影构建段补句（架构 v1.42）。
- **逻辑实例继承语义条目补下发机制指针**：原始文档 = 架构 §5.2，同步落点 = 逻辑实例 bullet（架构 v1.42）。
- **gson 直依赖登记**：非 reactor 新组件（server 线经 grpc 传递、parent 管版本），management-server 直依赖以 pom 注释登记（discovery-server phase-5 先例），组件总表不加行——修正设计 §3 的「组件总表补登记行」表述。
- 不新增 ADR：机制决策在既有 D1/D13/ADR-016 框架内（ejection 流 v0.3 phase-6 同级决策未立 ADR 先例）。

## 实现结果（⑦ 补）

实现要点：

- proto：management.v1 追加 `LogicalInstanceService` 组（LogicalInstance/Key/Request/Snapshot/Event + rpc），六组生成矩阵不变。
- management 三层：domain 端口六件（模型/Store/流三端口/异常）+ infra（内存 store、dispatcher）+ application（Service 三用例、归一、审计摘要、mapper 出站事件）；推送与存储写在 `logicalInstanceLock` 内成对（乱序防护）。
- management-server：V4 双方言迁移、repository 三件（metadata TEXT 存 gson JSON）、Admin API（PUT/DELETE/GET）、`LogicalInstanceGrpcService` 并入内部端口条件装配、双轨存储接线；gson 直依赖（pom 注释登记，discovery-server phase-5 先例）。
- discovery：`GrpcLogicalInstanceSource` 将逻辑实例流适配为 ProjectionSource（流首快照 stash → onEstablished，bootstrap 即取；upserted→UPDATE、deleted→DELETE 恒映射）；`UpstreamSynchronizers` 以保留 cluster `logical` 并入统一 start/close 编排 + 撞名 fail-fast；不门控 readiness。
- `ManagementConfig` 收编通道共享配置 `artemis.discovery.management.maxInboundMessageBytes`（单一 PropertyConfig，guidelines §10.1；规则流与逻辑实例流两源此前同 key 异 doc 冲突，⑤ 修复）。
- Admin 请求 DTO port/weight 用 boxed 入参（tools.jackson 默认拒绝 null → primitive，缺省字段 400 的 ⑤ 修复），边界归一后再进域模型。

code-review 修复（⑥，6 findings：1 HIGH / 1 MEDIUM / 2 LOW / 2 INFO）：

1. HIGH——MySQL V4 唯一索引键长 (255+255+512)×4=4088 > 3072 建表必败（无 Docker 环境未拦截）：instanceId 列与 API 上限收 255（(255+255+255)×4=3060 合规），双方言列宽一致。
2. MEDIUM——DB upsert update 分支 created_at 被 now 覆盖：保留 existing.createdAt。
3. LOW——DELETE 路径补长度校验（与 PUT 对称）。
4. LOW——metadata 值 null 时 NPE 500 → 400（值非空校验）。
5/6. INFO 接受——保留名 fail-fast 无独立单测（3 行防御，IT 成本不对称）；bootstrap 先于 watch 的 NPE 不可达（端口契约序）。

## 文档同步（⑩ 填）

- `docs/product/artemis.md`（v1.28）：核心概念补「逻辑实例」条目；§4 增 v0.4 phase-4 交付段。
- `docs/api/admin-api.md`（v1.9）：§4.7–4.9 逻辑实例 API（幂等语义、255 校验上限、静态事实语义）；§7 错误表补 `LOGICAL_INSTANCE_STORE_UNAVAILABLE`；§8 已知限制更新。
- `docs/api/logical-instance-stream-api.md`（新建 v1.0）+ `docs/api/README.md` 登记（admin-api 状态列升 v0.4）。
- 架构文档已在 ③ 随定案同步（v1.42：§2.2/§4.2/§5.2），⑩ 无增量；无新增 ADR（定案节登记理由）；CLAUDE.md 检查无过时（构建命令/模块结构/当前状态均未变）；各级索引已同步。
- proto 契约变更：仅 management.v1（治理面组），Java 唯一实现语言随分支原子提交；proto-contract.md 无规则变更。

## 更新历史

| 版本 | 日期 | 变更说明 |
| ------ | ------ | ------ |
| 1.2 | 2026-10-25 | review 通过，登记定案节；架构同步（v1.42） |
| 1.1 | 2026-10-25 | 设计落盘（待 review）：模型/CRUD/V4 持久化/新流通道/保留 cluster 投影合并 |
| 1.0 | 2026-10-25 | 开工骨架 |
