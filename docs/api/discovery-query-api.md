# discovery 查询 API

版本: 1.0    更新时间: 2026-10-23

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

查询 discovery 当前投影（`(cluster, namespace, service)` 组织的多源合并视图；v0.3 phase-2 单集群，多集群聚合 phase-5）。

**请求选择器**（查询与订阅共用模型）：

| 字段 | 语义 |
|---|---|
| `services` | `ServiceKey` 精确集合；**空 = 全部服务**；重复 key 去重。指定时 namespace/service 空白 → `ACK_FAIL` + `INVALID_ARGUMENT` |
| `labels` | 实例 metadata 全键值匹配（实例级过滤：响应实例集为匹配子集；分组以实例 metadata 表达，ADR-013） |

**响应**：`data.services` 为 `ClusterServiceInstances` 列表——`cluster`（来源标识，投影边界注入，ADR-014）+ `instances`（`ServiceInstances`：service 实例集快照，ADR-007 原子单元）。

**空条目规则**（条目存在性 = 服务存在性，≥1 实例）：

| 场景 | 返回 |
|---|---|
| 显式指定 services，服务存在但实例全不匹配 labels | 空实例集条目（有效答案） |
| 服务零实例（全部下线）或不存在 | 不返回条目（与未命中同形） |
| 未指定 services 的全量查询 | 不返回 labels 过滤后为空的条目 |

## 3. 配置（SCF，`artemis.discovery.*`）

| 键 | 默认 | 说明 |
|---|---|---|
| `upstream.clusterId` | `default` | 投影 cluster 维度值（静态） |
| `upstream.endpoints` | —（必填） | 上游 registry 内部 peer 端点 `host:port` 逗号分隔；未配置启动失败（静态） |
| `upstream.auth.enabled/username/password` | `false`/—/— | registry 内部端口凭证（动态） |
| `upstream.establishTimeoutMs` | 10000 | watch 建立确认超时（静态） |
| `upstream.bootstrapTimeoutMs` | 60000 | bootstrap 流 deadline（静态） |
| `upstream.reconnectBackoffMinMs/MaxMs` | 1000/10000 | 断流重连全抖动指数退避（静态） |
| `projection.queueCapacity` | 8192 | 变更事件队列容量（静态；满 = 慢消费断流重连） |

管理端口（actuator health/readiness/prometheus）默认 9096 仅绑本机。

## 更新历史

| 版本 | 日期 | 变更说明 |
| ------ | ------ | ------ |
| 1.0 | 2026-10-23 | 初版：discovery.v1 查询契约（选择器语义、空条目规则、readiness/认证、上游配置） |
