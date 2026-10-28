# 配置参考

版本: 1.0    更新时间: 2026-10-28

服务端全部配置键（registry / discovery / management 三服务 + all-in-one 组合），键与默认值以代码为准穷举。SDK 配置键（`artemis.client.*`）见 [sdk-java.md](../api/sdk-java.md) 与 [sdk-java-discovery.md](../api/sdk-java-discovery.md)，此处不复制；K8s values 口径见 [k8s.md](k8s.md)。

## 配置模型与优先级

配置走 SCF（manager / sources / property 三层）。**各服务 manager** 的 source 优先级（数字大者优先，高 → 低）：

1. **Spring Environment**（含启动参数 `--key=value`、Spring 侧环境变量、模块 `application.properties`——all-in-one 组合端口即经启动参数由此注入）
2. **系统属性**（`-Dartemis.registry.lease.ttlMs=60000`）
3. **环境变量**（relaxed 映射：点号/camel 键 → 大写蛇形，`artemis.registry.lease.ttlMs` → `ARTEMIS_REGISTRY_LEASE_TTL_MS`）
4. **工作目录配置文件** `artemis.properties`（包根/工作目录，模板见 [deploy/vm/artemis.properties](../../deploy/vm/artemis.properties)）
5. **代码内置默认**（即本表「默认值」列）

注意第 1 条的推论：**已写进模块 `application.properties` 的键**（如 `artemis.registry.internal.port=9093`）优先级高于 `artemis.properties` 文件——要覆盖它们须经 Spring 侧（启动参数或环境变量，含 SCF relaxed 大写蛇形形式），文件盖不动。`artemis.dist.*` 组合键是例外：组合期由 dist 自建 manager 读取（无 Spring source），顺序为系统属性 > 环境变量 > `artemis.properties`。

Spring 框架键（`server.port`、`spring.datasource.*` 等）遵循 Spring Boot 自身规则。生效语义：**端口、成员表、TLS、组合键与队列容量等启动期读取**（改后重启生效）；**认证凭证**（`artemis.*.auth.*`）与保护/租约参数等机制键消费侧持 SCF Property 实时读值，`artemis.properties` 修改热生效（认证轮换无需重启）。

## registry

### 端口（Spring 键）

| 键 | 默认值 | 说明 |
|---|---|---|
| `spring.grpc.server.port` | 9090 | 外部 gRPC：SDK 四操作/心跳 + 节点状态 API |
| `server.port` | 8080 | 外部 HTTP 辅助通道：`GET /cluster/nodes`（SDK 前置寻址） |
| `management.server.port` | 9091 | actuator（health/readiness/prometheus） |

### 外部认证

| 键 | 默认值 | 说明 |
|---|---|---|
| `artemis.registry.auth.enabled` | true | SDK/API Basic 认证（D9 默认开；eval 可显式关） |
| `artemis.registry.auth.username` | artemis | |
| `artemis.registry.auth.password` | artemis | |

### 集群成员与探测

| 键 | 默认值 | 说明 |
|---|---|---|
| `artemis.registry.cluster.members` | （无） | 静态成员表，逗号分隔 `nodeId@peerHost:peerPort@externalHost:externalPort`；all-in-one 未配置时自报单成员 |
| `artemis.registry.cluster.self` | （自动） | 本节点 nodeId，空 = 从成员表地址自动匹配 |
| `artemis.registry.cluster.probeIntervalMs` | 5000 | 成员后台探测周期 |
| `artemis.registry.cluster.probeTimeoutMs` | 1000 | 探测超时 |

### 租约与自我保护

| 键 | 默认值 | 说明 |
|---|---|---|
| `artemis.registry.lease.ttlMs` | 30000 | 租约 TTL（允许 1000–600000 |
| `artemis.registry.lease.evictionIntervalMs` | 1000 | 过期摘除扫描周期（100–60000 |
| `artemis.registry.selfProtection.windowSeconds` | 10 | 续约统计窗口 |
| `artemis.registry.selfProtection.minCount` | 50 | 触发保护的最小实例基数 |
| `artemis.registry.selfProtection.factor` | 0.85 | 续约占比阈值（0.1–1.0 |
| `artemis.registry.selfProtection.maxCountResetMs` | 600000 | 峰值回落重置窗口 |

### 快照与停机

| 键 | 默认值 | 说明 |
|---|---|---|
| `artemis.registry.snapshot.path` | data/registry-snapshot.bin | 文件快照路径（静态，改后重启） |
| `artemis.registry.snapshot.intervalMs` | 300000 | 周期刷盘间隔（1000–86400000） |
| `artemis.registry.snapshot.maxAgeMs` | 3600000 | 启动加载快照的最大年龄（超龄丢弃） |
| `artemis.registry.snapshot.clockSkewToleranceMs` | 60000 | 快照时钟偏差容忍 |
| `artemis.registry.shutdown.drainMs` | 10000 | 优雅停机排水窗口（0–600000） |

### 复制 / bootstrap / 投影（内部通道）

| 键 | 默认值 | 说明 |
|---|---|---|
| `artemis.registry.replication.queueCapacity` | 16384 | 复制扇出总队列 |
| `artemis.registry.replication.peerQueueCapacity` | 4096 | 每 peer 队列（满即丢，bootstrap 兜底 |
| `artemis.registry.replication.maxBatchChanges` | 1024 | 单批最大变更数 |
| `artemis.registry.replication.retryInitialMs` | 1000 | 失败重试退避初值 |
| `artemis.registry.replication.retryMaxMs` | 60000 | 重试退避上限 |
| `artemis.registry.replication.sendTimeoutMs` | 5000 | 单批发送超时（100–600000 |
| `artemis.registry.bootstrap.timeoutMs` | 60000 | peer 全量 bootstrap 超时（1000–600000） |
| `artemis.registry.bootstrap.retryInitialMs` | 1000 | bootstrap 重试退避初值（100–600000） |
| `artemis.registry.bootstrap.retryMaxMs` | 10000 | bootstrap 重试退避上限（100–600000） |
| `artemis.registry.bootstrap.statusTimeoutMs` | 1000 | 成员状态查询超时（100–60000） |
| `artemis.registry.bootstrap.shardBytes` | 1048576 | bootstrap 快照分片大小（字节） |
| `artemis.registry.bootstrap.maxInboundMessageBytes` | 16777216 | peer 侧收包上限 |
| `artemis.registry.projection.queueCapacity` | 8192 | 投影分发队列 |
| `artemis.registry.projection.subscriberQueueCapacity` | 2048 | 每订阅者队列（满即断流重连 |
| `artemis.registry.projection.maxBatchChanges` | 1024 | 单批最大变更数 |

### 内部 peer 端口与认证

| 键 | 默认值 | 说明 |
|---|---|---|
| `artemis.registry.internal.port` | 9093 | 内部 peer gRPC 固定端口（1–65535） |
| `artemis.registry.internal.address` | （空） | peer 绑定地址；空 = 绑所有网卡（保证跨节点可达） |
| `artemis.registry.internal.auth.enabled` | false | 内部端口独立账户（与外部不通用）；关闭认证的部署必须以网络策略隔离内部端口 |
| `artemis.registry.internal.auth.username` | （空） | 启用时必填 |
| `artemis.registry.internal.auth.password` | （空） | 启用时必填 |
| `artemis.registry.internal.shutdownGraceMs` | 5000 | peer 端口关停宽限（0–300000） |
| `artemis.registry.internal.maxInboundMessageBytes` | 16777216 | peer 端口收包上限 |

## discovery

### 端口

| 键 | 默认值 | 说明 |
|---|---|---|
| `spring.grpc.server.port` | 9095 | 外部 gRPC：查询/订阅/对账数据面 |
| `server.port` | 8090 | 外部 HTTP：只读发现管理面 `/admin/v1` + `GET /cluster/nodes` |
| `management.server.port` | 9096 | actuator |

### 外部认证

| 键 | 默认值 | 说明 |
|---|---|---|
| `artemis.discovery.auth.enabled` | false | 外部账户（gRPC 数据面与 HTTP 管理面共用；启用须显式配置凭证） |
| `artemis.discovery.auth.username` | （空） | |
| `artemis.discovery.auth.password` | （空） | |

### 上游 registry（投影源）

| 键 | 默认值 | 说明 |
|---|---|---|
| `artemis.discovery.registry.clusters` | （必填） | JSON map：`{"clusterId":"host:port,..."}`，键 = 投影 cluster 维度值（ADR-014），多集群 union 聚合；未配置/非法 fail-fast 拒绝启动 |
| `artemis.discovery.upstream.auth.enabled` | false | 对 registry 内部端口的 Basic 认证 |
| `artemis.discovery.upstream.auth.username` | （空） | |
| `artemis.discovery.upstream.auth.password` | （空） | |
| `artemis.discovery.upstream.establishTimeoutMs` | 10000 | 订阅建立超时 |
| `artemis.discovery.upstream.reconnectBackoffMinMs` | 1000 | 断流重连退避初值 |
| `artemis.discovery.upstream.reconnectBackoffMaxMs` | 10000 | 断流重连退避上限 |
| `artemis.discovery.upstream.bootstrapTimeoutMs` | 60000 | 上游全量 bootstrap 超时 |
| `artemis.discovery.upstream.maxInboundMessageBytes` | 16777216 | 上游收包上限 |
| `artemis.discovery.projection.queueCapacity` | 8192 | 投影事件应用队列 |

### 节点目录与治理面规则流

| 键 | 默认值 | 说明 |
|---|---|---|
| `artemis.discovery.nodes` | （空） | 静态节点目录（逗号分隔 `host:port`），SDK 前置寻址 `GET /cluster/nodes` 数据源；空 = 空目录（SDK 寻址不可用） |
| `artemis.discovery.management.endpoints` | （空） | management 规则流端点（0..1）；空 = 不过滤（fail-open） |
| `artemis.discovery.management.auth.enabled` | false | 规则流订阅认证 |
| `artemis.discovery.management.auth.username` | （空） | |
| `artemis.discovery.management.auth.password` | （空） | |
| `artemis.discovery.management.queueCapacity` | 1024 | 规则应用队列 |
| `artemis.discovery.management.reconnectBackoffMinMs` | 1000 | 规则流重连退避初值 |
| `artemis.discovery.management.reconnectBackoffMaxMs` | 10000 | 规则流重连退避上限 |
| `artemis.discovery.management.maxInboundMessageBytes` | 16777216 | 规则流收包上限 |

## management

### 端口

| 键 | 默认值 | 说明 |
|---|---|---|
| `server.port` | 8081 | 外部 HTTP：console + Admin/状态 API |
| `artemis.management.grpc.port` | （未配置不监听） | 内部 gRPC 规则流端口（discovery 订阅）；配置才启用 |
| `management.server.port` | 9092 | actuator |

### 认证

| 键 | 默认值 | 说明 |
|---|---|---|
| `artemis.management.auth.enabled` | true | 外部 HTTP 账户（v0.4 起 DB 单管理员接管，本地账户为服务凭证，ADR-021） |
| `artemis.management.auth.username` | artemis | |
| `artemis.management.auth.password` | artemis | |
| `artemis.management.grpc.auth.enabled` | false | 规则流端口独立账户 |
| `artemis.management.grpc.auth.username` | （空） | |
| `artemis.management.grpc.auth.password` | （空） | |

### 出站（registry 只读事实查询 / discovery 订阅视图）

| 键 | 默认值 | 说明 |
|---|---|---|
| `artemis.management.registry.endpoint` | （空） | registry 外部 gRPC 端点；空 = 不装配只读查询 |
| `artemis.management.registry.username` | （空） | registry 认证用户名；空 = 不发送认证头——registry 外部认证默认开启，实际部署须显式配置 |
| `artemis.management.registry.password` | （空） | registry 认证密码 |
| `artemis.management.registry.timeoutMs` | 3000 | 单次查询超时（1–60000） |
| `artemis.management.discovery.endpoints` | （空） | discovery HTTP 端点列表（0..N，console 订阅视图数据源；空 = 视图无数据） |
| `artemis.management.discovery.auth.username` | （空） | discovery `/admin` 认证（空 = 不发送） |
| `artemis.management.discovery.auth.password` | （空） | |
| `artemis.management.discovery.timeoutMs` | 3000 | 单源请求超时（100–60000） |

### DB（治理元数据持久化，可选依赖）

| 键 | 默认值 | 说明 |
|---|---|---|
| `spring.datasource.url` | （未配置） | 未配置 = 零 DB 装配（内存态）；配置后 HikariCP + Flyway 自动装配 |
| `spring.flyway.locations` | classpath:db/migration/{vendor} | migration 按方言取 vendor 目录（MySQL/PostgreSQL） |

## all-in-one（`artemis.dist.*`，组合期静态）

组合端口（三个子上下文经启动参数注入，静态、改后重启）：

| 键 | 默认值 | 说明 |
|---|---|---|
| `artemis.dist.registry.grpcPort` / `.httpPort` / `.managementPort` / `.peerPort` | 9090 / 8080 / 9091 / 9093 | registry 子上下文端口 |
| `artemis.dist.management.grpcPort` / `.httpPort` / `.managementPort` | 9094 / 8081 / 9092 | management 子上下文端口 |
| `artemis.dist.discovery.grpcPort` / `.httpPort` / `.managementPort` | 9095 / 8090 / 9096 | discovery 子上下文端口 |

组合期同时消费 `artemis.discovery.nodes` / `artemis.registry.cluster.members` / `.self`（未配置时注入单成员自报缺省）；治理链默认闭合、DB 双轨等组合语义见 [all-in-one 文档](../api/all-in-one.md)。

## TLS（架构 §6：内外通道均可启，内网可配明文）

**外部端口（gRPC/HTTP）走 Spring SSL bundle（框架键，零代码）**——三服务同键，各自上下文独立配置：

| 键 | 说明 |
|---|---|
| `spring.ssl.bundle.pem.artemis.keystore.certificate` | PEM 证书链路径 |
| `spring.ssl.bundle.pem.artemis.keystore.private-key` | PEM 私钥路径 |
| `spring.grpc.server.ssl.enabled` / `spring.grpc.server.ssl.bundle` | 外部 gRPC 启 TLS（`bundle=artemis`） |
| `server.ssl.bundle` | 外部 HTTP 启 TLS（`bundle=artemis`） |

**程序化通道（内部 peer、上游出站）走 SCF 静态键**，前缀 + 四后缀（`enabled` 默认 false / `certChainPath` / `privateKeyPath` / `trustCaPath`，客户端侧仅 `enabled` + `trustCaPath`）：

| 前缀 | 通道 | 侧 |
|---|---|---|
| `artemis.registry.internal.tls.*` | peer 复制/bootstrap（服务端证书 + 出站信任同组键） | 服务端 + 客户端 |
| `artemis.discovery.registry.tls.*` | discovery → registry 内部端口 | 客户端 |
| `artemis.discovery.management.tls.*` | discovery → management 规则流 | 客户端 |
| `artemis.management.registry.tls.*` | management → registry 只读查询 | 客户端 |

证书 = PEM 文件路径，轮换重启生效；证书须带 SAN 覆盖目标地址（不提供跳过校验开关）；坏配置启动 fail-fast。

## 观测（Spring Boot actuator/tracing 键）

| 键 | 默认值 | 说明 |
|---|---|---|
| `management.server.address` | 127.0.0.1 | actuator 绑定地址；集群/容器部署显式改并配网络策略 |
| `management.endpoints.web.exposure.include` | health,info,prometheus | |
| `management.endpoint.health.probes.enabled` | true | 含 readiness group |
| `management.endpoint.health.group.readiness.include` | readinessState,registryReadiness（registry）/<br>readinessState,discoveryReadiness（discovery） | readiness 聚合业务就绪（registry 复制/bootstrap、discovery 上游） |
| `spring.grpc.server.shutdown.grace-period` | 1s | gRPC 端口关停宽限（三服务统一 1s；registry 存量流量由 `shutdown.drainMs` 排水承担，避免超出 K8s 终止宽限） |
| `management.tracing.export.otlp.enabled` | false | OTLP trace 导出（eval 关；有 collector 时开） |
| `management.otlp.metrics.export.enabled` | false | OTLP metrics push（同上） |

## 更新历史

| 版本 | 日期 | 变更说明 |
| ------ | ------ | ------ |
| 1.0 | 2026-10-28 | 初版（v1.0 phase-7：服务端全量键穷举自代码） |
