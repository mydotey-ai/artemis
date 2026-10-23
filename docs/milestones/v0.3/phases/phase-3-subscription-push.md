# Phase 3：discovery 订阅契约与推送

版本: 1.5    更新时间: 2026-10-24

## 目标

定稿 `discovery.v1` 订阅契约，在 discovery 侧交付订阅增量推送（视图语义变更批、一流多目标、快照基线）与推送指标——推拉结合之「推」（ADR-019）。

## 范围

做什么：

- `proto/discovery/v1`：订阅契约（server-stream 增量推送）。
- discovery-core：订阅管理（选择器过滤、labels 视图语义翻译、变更分发扇出）、推送端口。
- discovery-server：订阅 rpc 出口与推送执行。

不做什么：

- hash 对账 API（phase-4，ADR-019 三件套之三；checksum 表拉取是独立 unary rpc，随 phase-4 契约演进）。
- 三层兜底轮询与新鲜度出口（phase-4）。
- 多集群聚合（phase-5）。
- 摘除过滤（phase-6）。
- 消费方 SDK 兜底轮询/对账节奏（phase-8）。

## 涉及模块

| 模块 | 内容 |
|---|---|
| `proto/discovery/v1` | 订阅契约定稿 |
| `java/server/artemis-discovery-core` | 订阅管理、labels 翻译、变更分发扇出、推送端口 |
| `java/server/artemis-discovery-server` | 订阅 rpc 出口、推送执行与指标 |

## 验收方式

对应测试文档：[phase-3-subscription-push-test.md](phase-3-subscription-push-test.md)

## 产品设计（③ 补）

### 契约：discovery.v1 订阅定稿草案

`proto/discovery/v1/discovery.proto` 新增（既有 Query 部分零改动，wire 兼容只增）：

```protobuf
// ---- 订阅推送（SDK → discovery，架构 §2.2 外部端口；v0.3 phase-3）----

// 实例变更（视图语义）。与 projection.v1 同构：该组是 registry→discovery 内部契约，
// 组间不横向 import（proto-contract §1 import 只向 common）；两处 proto 各自映射到
// discovery-core domain 同一模型（语义单一来源）。
enum ChangeType {
  CHANGE_TYPE_UNSPECIFIED = 0;
  CHANGE_TYPE_UNKNOWN = 1;   // 本地映射目标
  CHANGE_TYPE_NEW = 2;
  CHANGE_TYPE_UPDATE = 3;
  CHANGE_TYPE_DELETE = 4;    // instance 至少携带 instance_id，其余字段无意义
}

message InstanceChange {
  artemis.common.v1.Instance instance = 1;
  ChangeType type = 2;
}

message ServiceChange {
  artemis.common.v1.ServiceKey key = 1;
  repeated InstanceChange changes = 2;  // 保持产生序
}

message SubscribeRequest {
  repeated artemis.common.v1.ServiceKey services = 1;  // 空 = 全部服务（语义同 QueryRequest）
  map<string, string> labels = 2;                      // 实例级过滤（语义同 QueryRequest）
}

// 流事件：单 service 视图变更 + 来源 cluster（实现期定稿：cluster 归因是多集群聚合的
// 确定需求（phase-5「可按 cluster 过滤」），订阅方须能区分事件来源；per-service 分条下
// 每条事件携带 cluster 与 service 一一对应）。首条两字段均未设 = 订阅建立确认
// （对齐 projection.v1 Watch 空批先例）；确认后逐 service 推送命中基线（全 NEW），稳态推增量。
message SubscriptionEvent {
  string cluster = 1;            // 来源 cluster（投影边界注入，ADR-014）
  ServiceChange service = 2;     // 单 service 视图语义变更（per-service 分条，D-S1）
}

service DiscoverySubscriptionService {
  // 订阅推送（推拉结合之「推」，ADR-019）：一流多目标，单流内按产生序有序；
  // 上游（registry）断连重连对订阅方透明——覆盖差异以 diff 增量续推，流不断。
  // at-most-once：慢消费者（isReady 背压）由服务端断流，消费方重订阅重新基线
  // （幂等自愈）；增量丢失检测归 hash 对账（phase-4）与兜底轮询（SDK）。
  // 未就绪 UNAVAILABLE（readiness 拦截器，语义同 Query）。
  rpc Subscribe(SubscribeRequest) returns (stream SubscriptionEvent);
}
```

### 语义定案

| # | 决策 | 说明 |
|---|---|---|
| D-S1 | **契约形态：新增 `DiscoverySubscriptionService.Subscribe`（server-stream），推视图语义增量变更批** | 与 Query 分列 service（用例独立演进）；`SubscribeRequest` 与 `QueryRequest` 同构（选择器语义零新面，D-D7）。流事件 = 变更批（增量语义天然批单元，对齐 projection.v1 `ChangeBatch` 先例），首条空批 = 建立确认；首推基线与稳态增量同构（全 NEW 批）——一种消息语义贯穿，无快照/增量双形态。**扇出统一 per-service 分条**（每条消息装一个 `ServiceChange`）：消息上界 = 单服务实例集（与 maxInbound 对齐，防 diff/大批单条超限），基线/稳态/diff 三路径同规则。事件携带来源 `cluster`（实现期定稿：多集群聚合归因是 phase-5 确定需求，订阅方须能按 cluster 区分事件来源） |
| D-S2 | **推拉结合（ADR-019），本 phase 交付「推」** | 实时增量流（本 phase）+ 全量拉（Query 已有）+ hash 对账（phase-4 checksum API）；订阅流不承担丢失检测（at-most-once），丢失由对账 + 兜底收敛。取代 ADR-007 订阅通道快照形态（快照单元语义保留于投影/查询/基线/对账） |
| D-S3 | **labels 视图语义翻译在服务端**：订阅者收到的即视图语义变更，SDK 无脑 apply | 翻译矩阵（旧匹配态 × 新匹配态）：NEW/UPDATE 新值匹配 → NEW/UPDATE（新值语义）；旧不匹配新匹配 → NEW（视图加入）；旧匹配新不匹配 → DELETE（视图移除）；双不匹配 → 丢弃。DELETE 事件旧值匹配才推。旧匹配态经 `onChanges` 的旧值 lookup 取得（稳态 = 投影当前值于 apply 前，覆盖 = 覆盖前旧投影）——实例退出视图（UPDATE 改 metadata）显式 DELETE，不依赖对账兜底 |
| D-S4 | **扇出内联同步线程，翻译先于投影变更** | drain 循环逐批：先 `onChanges`（per subscription 持锁：查旧值 + 翻译 + push）后 `registry.apply`；重连覆盖路径同构：先 diff 续推（旧值取覆盖前投影）后 `replaceAll`——「旧值在本批应用前」是两条路径共同的翻译正确性前提。推送是纯内存操作（翻译 + proto 构造 + onNext 非阻塞），上游事件队列（既有界 8192）天然背压。`ponytail:` 注释标注天花板：扇出成本 O(订阅者数 × 变更数)，规模化时升级 per-subscriber 分片 dispatcher |
| D-S5 | **per-subscription synchronized（查旧值 + 翻译 + push 同临界区）保单流序** | 订阅登记/基线在 gRPC handler 线程、稳态翻译推送在同步线程，两方对同一订阅的临界区互斥串行：订阅者视角等价单线程消费，无乱序无回退（apply 幂等 upsert/no-op 使重复推送安全）。锁内无 IO |
| D-S6 | **首推基线：确认空批 + 逐 service 全 NEW 批** | 订阅登记后同临界区：推确认空批，随后对选择器命中逐 service 推当前实例集全 NEW 批——基线即对齐，重订阅即恢复（幂等 upsert）。per-service 分条：消息上界 = 单服务实例集（10 万实例防单条超限）。显式 services 命中条目才基线（条目不存在不推，后续创建走增量）；空 services（全部）基线当前全部条目——服务消失的清空不靠基线（D-S7 diff / 对账兜底），订阅时刻不存在的服务后续创建自然经增量流入 |
| D-S7 | **上游重连覆盖 = diff 续推增量，订阅流与上游状态解耦** | 同步线程重连覆盖前取旧投影 `T0`、与 bootstrap 新快照 `T1` diff 生成变更批（服务消失全 DELETE / 新建全 NEW / 实例增删改按 equals），经与稳态增量**完全相同**的翻译扇出路径推送（旧值取 T0）后再 `replaceAll`——SDK 流不断。registry↔discovery 断连期间投影冻结、流静默（陈旧换可用，与查询一致），重连后 diff 收敛；SDK 流生命周期只由 SDK↔discovery 连接与慢消费者决定，上游抖动对订阅方透明（降级感知归 phase-4 新鲜度标记）。diff 在同一线程覆盖前一次性完成，无「覆盖中途变更」交错；registry 全冷重启时 diff = 全 DELETE（消费方正确感知服务消失） |
| D-S8 | **慢消费者：isReady 断流（at-most-once）** | server 出口 push 前检查 `ServerCallStreamObserver.isReady()`，false（outbound 背压）→ `onError(RESOURCE_EXHAUSTED)` + 移除订阅；消费方重订阅自愈。防 gRPC outbound 无界缓冲积累（原产品局限 #13 服务端版） |
| D-S9 | **订阅生命周期：句柄 AutoCloseable，cancel/完成/断流三路殊途同归** | domain `SubscriptionManager.subscribe(selector, target)` 返回 `Subscription`（AutoCloseable）；gRPC 出口挂 `setOnCancelHandler` + 完成回调 + 断流路径均 close。登记表 `ConcurrentHashMap` keySet 弱一致迭代（低频订阅变更 + 高频扇出遍历，读路径无锁） |
| D-S10 | **指标在 server 出口就地定义（core 不引 micrometer）；无新增配置** | `artemis.discovery.subscription.active`（gauge）、`.push`（counter）、`.closed.slow-consumer`（counter）定义在 gRPC 出口类内（guidelines §1 监控就地定义；micrometer-core 随 actuator 传递，缺则补——组件总表已有，无新评审）。无推送 executor/队列即无容量类配置；订阅数上限由外部端口认证承担（YAGNI） |

对照基线：ADR-019（本 phase review 定案成文）；架构 §4.2 推送模型/兜底段已同步（1.35）；局限 #13 修复（isReady 断流 + 增量无深克隆）；局限 #3 修复的落点在 phase-4（checksum 对账）；资产 #8 三层兜底的服务端契约面归 phase-4。

### discovery-core 设计

**domain**（`ChangeType`/`InstanceChange`/`ServiceChange` 复用 phase-2 既有模型，零新增数据模型）：

- `PushTarget`：端口接口——`void accept(ServiceChange change)`（视图语义变更，per-service 分条）+ `void disconnect(String reason)`（断流：慢消费者）。server 实现（写 gRPC 流 + 指标 + isReady 断流）。
- `SubscriptionManager`：登记表（CHM<Subscription, …>）+ 扇出。`subscribe(selector, target)`：登记 + 同临界区推确认空批与命中基线（读 `ProjectionRegistry`，逐 service 全 NEW 批）；`onChanges(cluster, List<ServiceChange> batch, Function<ServiceKey, ServiceInstances> oldValues)`（同步线程、投影变更前调用，oldValues 返回该服务本批应用前条目）：per subscription 持锁翻译（service 级：key ∈ selector.services 或 selector 全量；实例级：labels 匹配态矩阵 D-S3）→ 逐 ServiceChange 分条 push（D-S1）。
- `ProjectionDiff`（domain 纯函数）：`diff(List<ServiceInstances> old, List<ServiceInstances> fresh) → List<ServiceChange>`——服务消失全 DELETE、新建全 NEW、共存按实例 equals diff 增删改（实例按 instanceId 序遍历，确定序）。
- `ProjectionRegistry` 读方法扩展：`service(cluster, key)`（单条目读，稳态旧值 lookup）、`services(cluster)`（全条目读，覆盖前旧投影）——均无锁持引用。
- `Subscription`：订阅句柄（AutoCloseable，close 移除登记）。

**application**：

- `DiscoverySubscriptionService`：订阅用例——选择器归一/校验（同 `DiscoveryQueryService` 逐字语义，空串 → IllegalArgumentException 由门面转 INVALID_ARGUMENT）→ `SubscriptionManager.subscribe`。
- `DiscoveryProtoFacade`/`DiscoveryProtoMapper` 扩展：`SubscribeRequest` proto→domain、domain `ServiceChange`→proto（`ChangeType` UNKNOWN 哨兵映射）。

**infrastructure**：

- `ProjectionSynchronizer` 构造注入 `SubscriptionManager`，两条推送路径同构（D-S4/D-S7）：
  - drain 循环逐批：先 `onChanges(cluster, batch, key → registry.service(cluster, key))` 后 `registry.apply`；
  - 重连覆盖：bootstrap 得新快照后，`old = registry.services(cluster)` → `diff = ProjectionDiff.diff(old, snapshot)` → `onChanges(cluster, diff, key → old 表)` → `registry.replaceAll` → 继续 drain。

### discovery-server 设计

- `grpc/DiscoverySubscriptionGrpcService`：`@GrpcService` 出口：`Subscribe(req, observer)` → 门面校验 + 订阅 → `ServerCallStreamObserver` 适配 `PushTarget`（accept = isReady 检查 + onNext + push 计数；disconnect = onError(RESOURCE_EXHAUSTED) + close 订阅；`setOnCancelHandler` 清理）+ 指标三件（D-S10）。
- `DiscoveryConfiguration`：新增 `SubscriptionManager`/`DiscoverySubscriptionService`/出口 bean，`ProjectionSynchronizer` 注入 manager。

## 实现结果（⑦ 补）

**交付**：

- `proto/discovery/v1`：`DiscoverySubscriptionService.Subscribe`（server-stream）+ `SubscribeRequest`/`SubscriptionEvent`（cluster + 单 ServiceChange per-service 分条）+ `ChangeType`/`InstanceChange`/`ServiceChange`（与 projection.v1 同构，组间不横向 import）；既有 Query 零改动。
- discovery-core：domain `PushTarget`/`Subscription`/`SubscriptionManager`（表锁基线/扇出互斥 + labels 视图语义翻译 + per-call 旧值索引 memo）/`ProjectionDiff` + `ProjectionRegistry` 无锁读方法（`service`/`services`）；application `DiscoverySubscriptionService`（校验提取到 `DiscoverySelector.requireExplicitServicesValid`，查询/订阅共用）+ mapper/facade 订阅扩展；infrastructure `ProjectionSynchronizer` 双路径同构编排（drain：表锁内扇出+apply 成对；resync：diff 续推+覆盖成对）。
- discovery-server：`DiscoverySubscriptionGrpcService`（流出口 + isReady 慢消费者断流 + cancel 二检清理 + 指标三件就地定义）；装配扩展（SubscriptionManager/订阅服务/出口 bean）。
- 测试：core 单测 12（SubscriptionManager 9 + ProjectionDiff 3）+ server IT 7（确认/基线、增量亚秒、双流隔离、labels 视图四象限、上游重启 diff 续推流不断、未就绪拒绝、cancel 注销断言）。

**code-review 修复（⑥ skill max + 内联兜底双轨，10 findings）**：

| finding | 处理 |
|---|---|
| S1 subscribe 基线与同步线程「扇出+apply」交错三竞态（丢失/确认被抢/僵尸复活） | **表锁**：subscribe（登记+基线）与 runWithFanout（扇出+投影应用成对）互斥——批中间态对基线不可见、确认必为首条 |
| S2 基线异常无回滚（登记+gauge 泄漏） | subscribe 表锁内 try-catch 移除登记 |
| S3 显式 selector + labels 全滤空条目推空 ServiceChange（契约噪声） | 基线跳过空实例条目 |
| S4 findById 线性扫描 O(instances×changes) 阻塞同步线程 | per-call 旧值 HashMap 索引 memo，translate O(1) 查 |
| S5 resyncWithDiff 与 ProjectionDiff 重复建 oldByKey | diff 签名改收 by-key map，resync 单次构建共用 |
| S6 PushTarget.disconnect 死代码 | 删除（慢消费者在 accept 内联断流） |
| S7 deliver 冗余 selector 参数 | 删除，用订阅自身 selector |
| S8 isBlank 校验查询/订阅双份复制 | 提取 `DiscoverySelector.requireExplicitServicesValid` 共用 |
| S9 cancel IT 未断言注销 | 补 `manager.size()==0` await 断言 |
| S10 亚秒断言 CI 抖动风险 | 不修：roadmap v0.3「推送亚秒可见」验收语义优先，本地实测余量大（info 记录） |

## 文档同步（⑩ 填）

| 文档 | 变更 |
|---|---|
| `docs/product/artemis.md`（v1.18） | 新增 v0.3 phase-3 交付节（推拉结合模型/契约/labels 翻译/diff 续推/工程形态） |
| `docs/api/discovery-subscription-api.md` | 新建：订阅契约（流语义、错误门控、指标）；选择器语义引用 query 文档不复制 |
| `docs/api/README.md` | 登记 discovery-subscription-api.md；query 行描述去掉「随 phase-3 演进」尾巴 |
| `docs/decisions/adr-019-hybrid-push-pull-subscription.md` | ③ 已同步（v1.1，diff 续推定案） |
| 架构文档 / roadmap / v0.3 plan | ③ 已同步（1.36 / 1.12 / 1.3）——本 phase 无增量 |
| `CLAUDE.md` | 无需变更（进度单一来源为 dev-state.md，构建命令与模块结构未变） |
| 本 phase 测试文档（v1.4） | 用例结果/回归/验证记录（随 ⑤⑧⑨ 更新） |

## 更新历史

| 版本 | 日期 | 变更说明 |
| ------ | ------ | ------ |
| 1.5 | 2026-10-24 | ⑨ 验证通过（自动化 + 人工）；⑩ 文档同步清单 |
| 1.4 | 2026-10-24 | ④–⑧ 完成：实现结果与 code-review 修复记录（10 findings，含表锁竞态修复） |
| 1.3 | 2026-10-23 | 二轮 review 定案：上游覆盖改 diff 续推（订阅流与上游状态解耦）；扇出统一 per-service 分条；ADR-019 v1.1、架构 1.36、plan 1.3 同步 |
| 1.2 | 2026-10-23 | review 定案：订阅推送改推拉结合（ADR-019）——契约改视图语义增量变更批（首条空批确认 + 全 NEW 基线）、labels 服务端翻译、上游覆盖断流；架构/roadmap/plan 同步修订 |
| 1.1 | 2026-10-23 | 产品设计定稿（快照推送版：discovery.v1 订阅契约 + D-S1..D-S10 + 模块设计） |
| 1.0 | 2026-10-23 | 开工骨架 |
