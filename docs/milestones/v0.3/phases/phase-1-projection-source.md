# Phase 1：projection.v1 契约 + registry 侧数据分发

版本: 1.9    更新时间: 2026-10-23

## 目标

定稿 `projection.v1` 分发契约，并在 registry 侧交付投影数据源（全量 bootstrap + 变更流），承载于内部 peer 端口。

## 范围

做什么：

- `proto/projection/v1/projection.proto`：bootstrap（server-stream 分片）与变更流（订阅后增量推送）两类报文，只承载实例事实。
- registry-core：投影源领域端口/应用服务（从注册表与变更事件出口取数）。
- registry-server：内部 peer 端口绑定分发 service、认证与 readiness 语义、装配。

不做什么：

- discovery 侧投影构建与查询（phase-2）。
- discovery 订阅推送（phase-3）。
- 新鲜度标记的消费侧出口（phase-4）。
- 规则版本 + ack（v0.4）。

## 涉及模块

| 模块 | 内容 |
|---|---|
| `proto/projection/v1` | 分发契约定稿（投影语义三值变更事件，服务端生成于 artemis-common） |
| `java/server/artemis-common` | projection.v1 生成（include 已就绪） |
| `java/server/artemis-registry-core` | 投影源端口 + 应用服务（domain/application/infrastructure） |
| `java/server/artemis-registry-server` | 内部端口 gRPC 绑定、认证、装配 |

`replication.v1` 与 `common.v1` 零改动（变更事件按各通道消费方语义独立定义，见 D-P5）。

## 验收方式

对应测试文档：[phase-1-projection-source-test.md](phase-1-projection-source-test.md)

## 产品设计（③ 补）

### 契约：projection.v1 定稿草案

```protobuf
syntax = "proto3";
package artemis.projection.v1;
import "common/v1/model.proto";
option java_package = "org.mydotey.ai.artemis.proto.projection.v1";
option java_multiple_files = true;

// registry → discovery 数据分发契约（架构 §2.2 通道③、§4.2）。
// 绑定 registry 内部 peer 端口（与 peer 复制同端口同认证）；ready 门控：未就绪 UNAVAILABLE。
// 数据不带 cluster 维度：registry 是单集群事实源，cluster 由 discovery 按订阅源本地附加。
// 投放语义：at-most-once（推送不重试不确认）；丢失/乱序窗口由消费方自愈——
// 断连重连重新 Bootstrap（全量覆盖幂等）+ 兜底轮询（架构 §4.2 三层兜底）。
// 消费方安全序：先 Watch，收到订阅建立确认（首条空批）后再 Bootstrap——确认之前的变更
// 已包含在随后的全量快照中，重叠窗口的变更被全量覆盖幂等吸收，无丢失。

// ---- 变更事件（投影语义：消费方只关心实例的新增/变更/移除） ----

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

// 按 service 聚合：一个 key 下的变更保持源节点产生序
message ServiceChange {
  artemis.common.v1.ServiceKey key = 1;
  repeated InstanceChange changes = 2;
}

// ---- 全量 bootstrap（拉取 + 分片应答） ----

message BootstrapRequest {
  string requester_id = 1;   // 订阅方标识（discovery 节点名等，排障用）
}

message BootstrapResponse {
  artemis.common.v1.ResponseStatus status = 1;
  BootstrapResponseData data = 2;
}

// 分片单位：单服务实例集（复用公共模型，单一来源）
message BootstrapResponseData {
  repeated artemis.common.v1.ServiceInstances services = 1;
}

// ---- 变更流（订阅后增量推送，server-stream） ----

message WatchRequest {
  string subscriber_id = 1;  // 订阅方标识（排障用）
}

// 变更批次：按 service 聚合；移除统一为 DELETE。
// 空批（services 为空）= 订阅建立确认，服务端在登记订阅后立即发送一条。
message ChangeBatch {
  repeated ServiceChange services = 1;
}

// 流事件 envelope（非 unary Response，不适用 status + data 规范）：
// 未来新增事件类型以新增字段演进（field number 只增）
message WatchEvent {
  ChangeBatch changes = 1;
}

service ProjectionService {
  // 全量快照分片：流正常结束 = 同步完成；中断由客户端整轮重来（全量覆盖幂等）。
  // 空分片 + 流正常结束 = 空注册表。未就绪 UNAVAILABLE（未就绪节点不充当数据源）。
  rpc Bootstrap(BootstrapRequest) returns (stream BootstrapResponse);

  // 变更订阅：按批推送变更。
  // 慢消费者/推送失败由服务端断流（at-most-once），消费方重 Bootstrap 自愈；
  // 流断开 = 订阅结束。未就绪 UNAVAILABLE。
  rpc Watch(WatchRequest) returns (stream WatchEvent);
}
```

### 语义定案

| # | 决策 | 说明 |
|---|---|---|
| D-P1 | **统一事件源：清理也派发事件，消费者过滤** | 聚合恢复「所有结构性变更统一派发」不变量：`evictExpired` 复用既有 commit 路径派发 `EVICTED` 事件（同 Holder 锁，写路径与清理路径全序天然保证）；**复制扇出在发送侧过滤 `EVICTED`**（架构 §4.1「删除不跨节点传播」行为语义不变，过滤点从「不派发」移到扇出侧）；投影 dispatcher 将 `EVICTED` **映射为 `DELETE` 下发**——清理方式是 registry 内部概念，不进 wire 契约。不新增 evict 参数/注入通道 |
| D-P2 | **RENEW 不进投影流** | 纯续约维持 peer 副本租约；投影无租约、内容未变零推送（dispatcher 过滤） |
| D-P3 | **stale 是 registry 内部概念，不进分发契约** | 自我保护态是 registry 的清理门控实现细节，不是分发数据的一部分——投影契约只承载实例事实（bootstrap 全量 + 三值变更），discovery 无 stale 概念、无判定逻辑、无保护态感知；保护态排障面继续由 management 的 `GetSelfProtectionStatus` 提供（v0.2 已交付，不变）；discovery 侧新鲜度（如上游断连）属 phase-4 自身语义，与 registry 保护态无关 |
| D-P3′ | **因此 bootstrap 直接复用 `common.ServiceInstances`** | 无附加新鲜度字段后 `ServiceProjection` 包装无存在价值，分片单位即 `ServiceInstances`（key + instances），零重复模型 |
| D-P4 | **at-most-once + 断流语义** | 变更推送不重试不确认；慢消费者（per-subscriber 有界队列满）与推送失败 → 服务端断流注销，消费方重 bootstrap 全量收敛（架构 §4.2 自愈模型），不引入序号/变更 ack 机制。唯一非变更消息是**订阅建立确认**（首条空批）——它使「先 Watch 后 Bootstrap」的无丢失序可执行 |
| D-P5 | **变更事件按消费方语义独立定义** | registry 内部事件语义与 discovery 关心的事件不等价：投影 `ChangeType` 仅 NEW/UPDATE/DELETE 三值（RENEW 服务端过滤、EVICTED 映射 DELETE，不进契约）；**契约注释同样按消费方语义书写**——TTL、过期清理、心跳、内部事件映射等 registry 机制不进 proto 注释（内部机制说明落实现 javadoc 与本文档）；`replication.v1`/`common.v1` **零改动**（冻结契约零触碰、Java 引用零改动） |
| D-P6 | **bootstrap 分片复用既有配置** | 分片预算复用 `artemis.registry.bootstrap.shardBytes`（同一注册表全量导出、同一消息尺寸约束，一处配置） |
| D-P7 | **Watch 无选择器** | discovery 投影是全量表（v0.3），多集群聚合在 discovery 本地（架构 §2.1/§4.2）；选择器随需要以新增字段演进 |
| D-P8 | **异步批量组批（复制扇出同模式）** | ChangeListener 非阻塞入全局有界队列 → 单聚合线程 drain 积压、同 service 聚合、同实例保留最新、按 `maxBatchChanges` 上限组批 → per-subscriber 有界队列 + 专属线程串行推送；**无时间合批窗**（v0.3 验收「推送亚秒可见」，稳态低流量每批即发、高峰自动合批；原产品复制通道 2s 批延迟是跨网络减消息数取舍，投影订阅者少不适用） |

对照基线：继承资产 #11（批量贯穿——异步组批、按 service 聚合）、修复局限 #3（delta 机制设计而未用 → 变更流真实落地）、#15（缓存无新鲜度元信息 → 由 discovery 侧 phase-4 在上游连接维度解决，不涉及 registry 保护态）。原产品无对应分发协议（discovery 与 registry 同节点，legacy §2.4），本通道为架构 §2 拆分服务后的新建。

### registry-core 设计

**domain**（新增）：

```java
// 订阅者端口：服务端推送出口（core 无 gRPC 类型，server 接入层适配 StreamObserver）
public interface ProjectionSubscriber extends AutoCloseable {
    void onChanges(List<ServiceChange> batch);   // domain record，下同
    @Override void close();   // 服务端主动断流
}

public record ServiceChange(ServiceKey key, List<InstanceChange> changes) {}   // 复用既有 InstanceChange
// bootstrap 单元直接复用既有 ServiceInstances（无 stale 包装）
```

**domain（既有组件演进）**：

- `ChangeType` 枚举新增 `EVICTED`（TTL 过期清理产出；registry 内部统一事件源的五值类型：NEW/UPDATE/DELETE/RENEW/EVICTED，各通道在边界收敛为消费方语义——复制四值、投影三值）。
- `InstanceRegistry.evictExpired`：structural 分支复用既有 `commit` 路径（快照重建 + 空 holder 回收 + **事件派发**）——过期租约派发 `InstanceChange(EVICTED)`，与写路径同 Holder 锁全序；javadoc 的「清理不派发事件」表述更新为「清理派发 EVICTED，复制扇出发送侧过滤」。
- `ReplicationFanout.onChange`：入口过滤 `EVICTED`（删除不跨节点传播，D4 不变量保持）。

**infrastructure**：`ProjectionDispatcher`（ChangeListener 实现，AutoCloseable）——`RENEW` 过滤、`EVICTED` **映射为 `DELETE`** 后入全局有界队列（投影通道自此只有三值事件）；单聚合线程 drain、同 service 聚合（同实例保留最新）组批；per-subscriber 有界队列 + 专属虚拟线程推送（单线程 onNext，gRPC observer 非线程安全的约束）；队列满/推送失败 → 断流注销。

**application**：`ProjectionProtoFacade`（bootstrap 惰性逐 service 映射迭代——同 ReplicationProtoFacade 全量流模式，不物化整表 proto；watch 注册委派 dispatcher）。`RegistryServiceImpl` 无改动（清理事件经统一 listener 通道，无需注入）。

### registry-server 设计

- `ProjectionGrpcService extends ProjectionServiceGrpc.ProjectionServiceImplBase`：bootstrap（readiness 门控 UNAVAILABLE + shardBytes 预算分片，模式同 ReplicationGrpcService）；watch（readiness 门控 + 适配 ProjectionSubscriber + `setOnCancelHandler` 注销）。
- `PeerServerConfiguration`：`INTERNAL_SERVICES` 加 `artemis.projection.v1.ProjectionService`；peer 端口显式绑定（内部认证 + 观测/异常拦截器 + readiness 门控）。
- `RegistryConfiguration`：ProjectionDispatcher bean（start/close 生命周期）；InstanceRegistry 的 changeListener 组合复制扇出与投影 dispatcher（各自非阻塞）；`RegistryServiceImpl` 无改动（清理事件经统一 listener 通道）。
- 配置键：`artemis.registry.projection.queueCapacity`（全局队列，默认 8192）、`artemis.registry.projection.subscriberQueueCapacity`（默认 2048）、`artemis.registry.projection.maxBatchChanges`（默认 1024）。

## 实现结果（⑦ 补）

**交付**：

- `proto/projection/v1/projection.proto`：`ProjectionService.Bootstrap`（server-stream 分片）与 `Watch`（server-stream 事件流）；三值 `ChangeType`（NEW/UPDATE/DELETE）；`BootstrapResponseData` 复用 `common.ServiceInstances`；订阅建立确认 = 首条空批。
- registry-core：`ChangeType.EVICTED`（统一事件源）；`InstanceRegistry.evictExpired` 经既有 commit 路径派发 EVICTED；`ReplicationFanout` 发送侧过滤 EVICTED；`ProjectionDispatcher`（异步组批、per-subscriber 有界队列 + 串行推送、慢消费者/推送失败断流、订阅建立确认）；`ProjectionSubscriber`/`ProjectionSubscriptions`/`ServiceChange` 端口与模型；`ProjectionProtoFacade`/`ProjectionProtoMapper`；`InstanceChangeMerge`（与复制扇出共用合并规则）。
- registry-server：`ProjectionGrpcService`（readiness 门控、取消竞态兜底、流适配）；`BootstrapShards`（与 peer bootstrap 共用分片实现与配置键）；peer 端口绑定 + 内部认证；`RegistryConfiguration` 复合 `ChangeListener`（复制扇出 + 投影分发）。

**code-review 修复（⑥/⑦）**：

| finding | 处理 |
|---|---|
| Watch 与 Bootstrap 无建立信号，「先 Watch 后 Bootstrap」无丢失序不可执行 | 契约新增订阅建立确认（首条空批），dispatcher 在登记订阅读取任何事件前入队 |
| 客户端取消早于订阅登记导致订阅者/线程悬挂 | 订阅后补查 `isCancelled` 显式注销；dispatcher 停机后 subscribe 直接关闭流 |
| subscribe 与 close 竞态（悬挂流 / 线程泄漏） | 订阅前后双重 running 检查 + `removeAndClose` 兜底；`worker` 改 volatile |
| slow-consumer 指标把正常 unsubscribe 计入 | 正常取消走 `removeAndClose`（不计数），仅慢消费者/推送失败计数 |
| 重复实现：分片循环/配置键、变更合并规则 | 提取 `BootstrapShards`（两线共用）与 `InstanceChangeMerge`（两扇出共用） |
| `applyReplicated` 缺 EVICTED 分支 | 防御性按 DELETE 应用 |
| `evictExpired` 每轮每服务分配 List | 惰性分配（稳态零分配） |
| `WatchResponse` 违反 Response 规范形式 | 更名 `WatchEvent`（流事件 envelope 明确豁免） |
| IT/单测组批时序竞态、缺分片与重连覆盖 | 异步断言改末态判定；新增分片、断流重连、建立确认用例 |

## 文档同步（⑩ 填）

| 文档 | 变更 |
|---|---|
| `docs/product/artemis.md`（v1.16） | 新增 v0.3 phase-1 交付节；核心概念补「投影分发」 |
| `docs/decisions/adr-018-projection-distribution-contract.md` | 新增：投影分发契约决策（三值事件/at-most-once/建立确认/保护态不进契约） |
| `docs/decisions/README.md`、架构 §7（v1.34） | 登记 D18 / ADR-018 |
| `docs/arch/artemis-next-architecture.md`（v1.33） | §4.1/§4.2 保护态不进分发契约（设计 review 时已同步） |
| `docs/product/artemis-next-roadmap.md`（v1.11） | v0.2/v0.3 条目移除 stale 出口（设计 review 时已同步） |
| `docs/milestones/v0.3/plan.md`（v1.1） | phase-1/phase-4 说明与 phase-1 验收方式同步 |
| `docs/milestones/v0.3/phases/README.md` | 已登记本 phase 两份文档（开工时） |
| `CLAUDE.md` | 无需变更（进度单一来源为 dev-state.md，构建命令/模块结构未变） |

## 更新历史

| 版本 | 日期 | 变更说明 |
| ------ | ------ | ------ |
| 1.9 | 2026-10-23 | ⑨ 用户验证通过；⑩ 文档同步清单（产品文档 + ADR-018 + 架构/索引） |
| 1.8 | 2026-10-23 | ④–⑧ 完成：实现结果与 code-review 修复记录；契约补订阅建立确认、`WatchEvent` 更名；plan phase-1 验收方式同步 |
| 1.7 | 2026-10-23 | review 定案：stale 完全移出分发契约（registry 内部概念）——bootstrap 直接复用 `common.ServiceInstances`，D-P3 重写；架构 §4.1/§4.2、roadmap、v0.3 plan 同步修订 |
| 1.6 | 2026-10-23 | review 修订：删 ProtectionSnapshot 机制——stale 仅随 bootstrap 携带，Watch 流纯变更分发（D-P3 重写、dispatcher 与配置简化） |
| 1.5 | 2026-10-23 | review 修订：契约注释按消费方语义书写——TTL/过期清理/内部事件映射不进 proto 注释（stale 注释改为消费方语义） |
| 1.4 | 2026-10-23 | review 修订：契约按消费方语义独立定义——投影 ChangeType 三值（NEW/UPDATE/DELETE），撤销组件上移 common 方案；replication.v1/common.v1 零改动 |
| 1.3 | 2026-10-23 | review 修订：EVICTED 只进 domain 枚举（registry 内部概念），不进 proto；投影流清理映射为 DELETE 下发；replication 契约枚举值零变化 |
| 1.2 | 2026-10-23 | review 修订：变更事件组件上移 common.v1（D-P5）；统一事件源/清理派发 EVICTED、消费者过滤（D-P1 重写，撤销 evictExpired 参数注入）；批量组批明确为决策 D-P8 |
| 1.1 | 2026-10-23 | 产品设计定稿（契约草案 + 语义定案 D-P1..D-P7 + 模块设计） |
| 1.0 | 2026-10-23 | 开工骨架 |
