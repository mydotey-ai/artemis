# discovery 查询 API

版本: 1.2    更新时间: 2026-10-24

> 本文定义 discovery-service 的查询契约（`discovery.v1`，SDK 与控制面消费）。v0.3 phase-2 交付 unary 查询；订阅推送 rpc 随 phase-3 以新增 rpc 演进（wire 兼容）。

## 1. 契约与部署

| 项 | 值 |
|---|---|
| proto | `proto/discovery/v1/discovery.proto`（`package artemis.discovery.v1`） |
| Java 生成 | 服务端生成于 `artemis-discovery-core`；client SDK 独立生成（同 FQCN 异 artifact，跨端只走 wire） |
| 传输 | gRPC（unary），discovery **外部端口**（`spring.grpc.server.port`，默认 9095） |
| 认证 | 外部端口 BasicAuth `artemis.discovery.auth.*`，**默认关闭**；启用须显式配置凭证（动态，可运行期轮换）。凭证缺失/错误统一 `UNAUTHENTICATED` |
| 就绪 | discovery readiness 门控：首轮投影同步（bootstrap 覆盖落地）完成前返回 `UNAVAILABLE`——空数据不作真相返回；同步完成后上游断连**不降 ready**（投影继续服务，新鲜度出口随 phase-4） |

## 2. 方法

### `Query`

`QueryRequest` → `QueryResponse`

查询 discovery 当前投影（`(cluster, namespace, service)` 组织的多源合并视图；v0.3 phase-5 起单节点可订阅多个 registry 集群，默认 union，可按 `clusters` 过滤）。

**请求选择器**（查询与订阅共用模型）：

| 字段 | 语义 |
|---|---|
| `services` | `ServiceKey` 精确集合；**空 = 全部服务**；重复 key 去重。指定时 namespace/service 空白 → `ACK_FAIL` + `INVALID_ARGUMENT` |
| `labels` | 实例 metadata 全键值匹配（实例级过滤：响应实例集为匹配子集；分组以实例 metadata 表达，ADR-013） |
| `clusters` | 来源 cluster 精确集合（v0.3 phase-5 多集群聚合）；**空 = 全部 cluster（union）**；非空 = 只返回命中 cluster 的条目。**未知 cluster 名静默无命中**（cluster 清单是服务端部署拓扑，不耦合 SDK）；集合内空白名 → `ACK_FAIL` + `INVALID_ARGUMENT` |

**响应**：`data.services` 为 `ClusterServiceInstances` 列表——`cluster`（来源标识，投影边界注入，ADR-014）+ `instances`（`ServiceInstances`：service 实例集快照，ADR-007 原子单元）。

**新鲜度元信息**（v0.3 phase-4）：`data.upstreams` 为 `UpstreamStatus` 列表（`cluster` + `UpstreamSyncState`：`SYNCED` 实时 / `SYNCING`、`DISCONNECTED` 投影冻结陈旧）——**非 `SYNCED` 即数据可能陈旧**（消费方感知数据降级；快照顺序不保证，按 cluster 键控消费）。语义细节与枚举值见 [discovery-reconciliation-api.md](discovery-reconciliation-api.md) §4。

**空条目规则**（条目存在性 = 服务存在性，≥1 实例）：

| 场景 | 返回 |
|---|---|
| 显式指定 services，服务存在但实例全不匹配 labels | 空实例集条目（有效答案） |
| 服务零实例（全部下线）或不存在 | 不返回条目（与未命中同形） |
| 未指定 services 的全量查询 | 不返回 labels 过滤后为空的条目 |

## 3. 配置（SCF，`artemis.discovery.*`）

| 键 | 默认 | 说明 |
|---|---|---|
| `registry.clusters` | —（必填） | 上游 registry 集群 JSON map（v0.3 phase-5）：键 = clusterId（投影 cluster 维度值，ADR-014）、值 = 该集群端点 `host:port` 逗号分隔列表，如 `{"cluster-a":"r1:9093,r2:9093","cluster-b":"r3:9093"}`。未配置/JSON 非法/集群名为空/端点非法均启动失败（静态） |
| `upstream.auth.enabled/username/password` | `false`/—/— | registry 内部端口凭证（动态） |
| `upstream.establishTimeoutMs` | 10000 | watch 建立确认超时（静态） |
| `upstream.bootstrapTimeoutMs` | 60000 | bootstrap 流 deadline（静态） |
| `upstream.reconnectBackoffMinMs/MaxMs` | 1000/10000 | 断流重连全抖动指数退避（静态） |
| `projection.queueCapacity` | 8192 | 变更事件队列容量（静态；满 = 慢消费断流重连） |

管理端口（actuator health/readiness/prometheus）默认 9096 仅绑本机。

## 更新历史

| 版本 | 日期 | 变更说明 |
| ------ | ------ | ------ |
| 1.2 | 2026-10-24 | phase-5：选择器新增 `clusters` 来源过滤（空 = union）；上游配置改 JSON map `registry.clusters`（替换 `upstream.clusterId`/`upstream.endpoints`） |
| 1.1 | 2026-10-24 | phase-4：`QueryResponseData.upstreams` 新鲜度元信息字段（wire 兼容只增） |
| 1.0 | 2026-10-23 | 初版：discovery.v1 查询契约（选择器语义、空条目规则、readiness/认证、上游配置） |
