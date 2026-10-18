# Phase 4：配置规范化（SCF）+ registry-server 装配

版本: 1.12    更新时间: 2026-10-18

## 目标

先用 SCF 建立各服务独立的配置体系（source/manager/properties），再在其上完成 registry-server 完整装配（HTTP 辅助通道、内外端口分离、默认认证、metrics/log/trace、成员抽象接口）。

## 范围

**做**：

- 配置规范化（SCF）：
  - 各服务独立定义 manager/properties，配置门面放各自 `infrastructure` 包；
  - 领域层、应用服务层构造注入 `Property<T>` 并按其语义使用；基础设施层定义配置项（键、默认值、动静类型、校验）；
  - server 包提供具体装配：configuration source 列表 → `ConfigurationManager` → `StringProperties` → 服务配置门面；
  - manager 原生支持多 source，不使用 `PipelineConfigurationSource`；
  - phase-3 遗留配置常量迁入配置门面。
- 服务装配：HTTP 辅助通道（Spring MVC + actuator）、内部/外部端口分离、默认单账户认证、Micrometer/Prometheus metrics（含自我保护态指标）、结构化 JSON log、OpenTelemetry trace、成员抽象接口（静态单成员实现）。

**不做**：管理 API 与有效视图（phase-5）、集群复制与成员动态发现（v0.2）、SDK 侧配置门面（phase-6）、TLS/RBAC（v1.0）。

## 涉及模块

- `java/server/artemis-common`：静态配置工具 `ScfProperties`、通用环境变量 source `RelaxedEnvironmentConfigurationSource`。
- `java/server/artemis-registry-core`：domain/application/infrastructure 各消费类就地定义并使用配置；新增 members/统计相关实现。
- `java/server/artemis-registry-server`：source/manager/properties 装配、HTTP/gRPC 端口与认证、可观测接入、成员抽象装配。

## 验收方式

对应测试文档：[phase-4-server-assembly-test.md](phase-4-server-assembly-test.md)

## 产品设计（③ 补）

### 1. 配置体系（SCF）

#### 1.1 分层职责（review 定案 D2）

| 层 | 落点 | 职责 |
|---|---|---|
| 集中 | `registry-server/config` | 只统一定义 **`ConfigurationManager` + `StringProperties`**（source 装配、manager、properties 门面） |
| 定义 + 使用 | 各消费类（domain/application/infrastructure/server） | 构造函数注入 `StringProperties`，在**本类内**定义所需 property（键、默认值、动静类型、校验）并读取 |
| 装配 | `registry-server/config` | 把同一个 `StringProperties` 注入各组件 |

- **不集中定义 Property**：键、默认值与校验属于使用它的类，避免一个门面类随功能增长成为上帝对象。
- **不传递单个 `Property`**：需要配置的类接收 `StringProperties`，自己通过它取得/创建 property。
- 集中部分只有 manager 与 properties；SCF manager 按 key 缓存 property，同一 key 全局只允许一个 `PropertyConfig`——共享配置（如认证）由一个配置值对象统一定义一次（`AuthConfig(StringProperties)`），其余 key 各消费类独占。
- 依赖：消费类只依赖 `StringProperties`（scf-simple）；静态项经 `ScfProperties` 工具（`artemis-common`，`PropertyConfig.setStatic(true)`）在类内固化。

#### 1.2 source 与 manager

```java
// registry-server：source 优先级（数字大者优先；SCF 按优先级降序取第一个非空值）
Map<Integer, ConfigurationSource> sources = Map.of(
        1, new ReloadablePropertiesFileConfigurationSource("artemis.properties"), // 存在时注册
        2, new RelaxedEnvironmentConfigurationSource(),   // ARTEMIS_REGISTRY_LEASE_TTL_MS -> artemis.registry.lease.ttlMs
        3, SimpleConfigurationSources.newSystemPropertiesSource(),
        4, new SpringEnvironmentConfigurationSource(environment)); // 命令行 --key=value / Spring 属性
ConfigurationManager manager = ConfigurationManagers.newManager("artemis-registry", sources);
StringProperties properties = new StringProperties(manager);
```

- 优先级：Spring Environment（命令行/导入配置）> 系统属性 > 环境变量 > 工作目录 `artemis.properties`。文件缺失时不注册 source（默认值兜底）；文件不可读/格式错误构造期 fail-fast。
- **动态生效**：配置文件 source 用 WatchService 监听变更并 `raiseChangeEvent()`，动态 property（TTL/保护阈值）运行中可更新；环境变量本身不可变，系统属性经 SCF 的 programmatic setter 触发变更。
- **不用 `PipelineConfigurationSource`**：manager 原生管理多 source（`newManager(name, Map<Integer, ConfigurationSource>)`）。
- 环境变量源做键名映射（`artemis-common` 的 `RelaxedEnvironmentConfigurationSource`）：点号转 `_`、camel 边界转 `_`、全大写。SCF 内置 env source 按 key 精确匹配，点号键在容器/K8s 中不可设置，故用映射源替代（通用 source，discovery/management 可复用）。
- `SpringEnvironmentConfigurationSource` 让 SCF 看到 Spring Boot 标准覆盖路径（`--artemis.registry.*=...`、application.properties），优先级最高。
- manager 名 `artemis-registry`；未来 discovery/management 各自独立建 manager 与 properties。

#### 1.3 使用语义

- 动态项：类内 `properties.getLongProperty/IntProperty/DoubleProperty(key, RangeValueConfig)` 取得 `Property`，**作为类字段**保存；每次 `getValue()` 取最新值。
- 静态项：`ScfProperties.staticXxx(...)` 返回 `Property`（`setStatic(true)`），同样**作为类字段**保存；使用处 `getValue()`（SCF 拒绝运行期变更，值启动即固化）。**不允许**临时构造 property 仅取一次值后丢弃。
- 校验：数值项用 `RangeValueConfig`（越界值被源忽略并回落默认值 + SCF error 日志）；静态字符串/列表与认证组合由定义类显式校验，失败抛 `IllegalArgumentException`（启动失败并给出配置键）。
- 默认值定义在使用类，调用点不出现魔法值。

#### 1.4 配置项与定义位置

| 键 | 类型 | 默认 | 型 | 校验 / 说明 | 定义/使用处 |
|---|---|---|---|---|---|
| `artemis.registry.lease.ttlMs` | Long | 30000 | 动态 | 1000..600000 | `InstanceRegistry`（phase-5 迁入；phase-4 时在 `RegistryServiceImpl`） |
| `artemis.registry.lease.evictionIntervalMs` | Long | 1000 | 静态 | 100..60000 | `ScheduledLeaseEvictionScheduler` |
| `artemis.registry.selfProtection.windowSeconds` | Integer | 10 | 静态 | 1..300；桶数组容量启动固定 | `SelfProtection`（领域层） |
| `artemis.registry.selfProtection.factor` | Double | 0.85 | 动态 | 0.1..1.0 | `SelfProtection` |
| `artemis.registry.selfProtection.minCount` | Integer | 50 | 动态 | 0..1000000 | `SelfProtection` |
| `artemis.registry.snapshot.intervalMs` | Long | 300000 | 静态 | 1000..86400000 | `ScheduledSnapshotScheduler` |
| `artemis.registry.snapshot.maxAgeMs` | Long | 3600000 | 静态 | 60000..86400000 | `RegistryServiceImpl`（启动回放） |
| `artemis.registry.snapshot.clockSkewToleranceMs` | Long | 60000 | 静态 | 0..300000 | `RegistryServiceImpl`（回拨宽容） |
| `artemis.registry.snapshot.path` | String | `data/registry-snapshot.bin` | 静态 | 非空白；相对工作目录 | `FileRegistrySnapshotStore` |
| `artemis.registry.cluster.members` | String | 空 | 静态 | 逗号分隔 `host:port`；非法条目启动失败 | `StaticClusterMembers` |
| `artemis.registry.auth.enabled` | Boolean | true | 静态 | 严格 true/false | `AuthConfig`（server） |
| `artemis.registry.auth.username` | String | `artemis` | 静态 | enabled 时非空白 | `AuthConfig` |
| `artemis.registry.auth.password` | String | `artemis` | 静态 | enabled 时非空白；默认值启动 WARN | `AuthConfig` |

动静分类说明：自我保护窗口是统计结构参数（分桶数组容量），启动固定；阈值 factor/minCount 动态。清理/快照周期选择静态，避免调度器自重排的额外并发路径；租约 TTL 动态已覆盖核心运行时调参需求。

#### 1.5 框架配置边界（review 定案 D1）

Spring Boot 与三方组件自有配置（监听端口、actuator 暴露、OTLP endpoint、gRPC starter 参数等）继续由组件自身配置体系管理；**自定义业务配置由 SCF 管理**（上表），不复制/代理框架键。框架键由 autoconfiguration 消费，测试经 `@SpringBootTest(properties)`/`@LocalGrpcServerPort` 控制；再经 SCF 复制一份会形成双源漂移。

### 2. 配置消费改造

| 组件 | 现状 | 改造 |
|---|---|---|
| `RegistryConfiguration` | 常量 `DEFAULT_*` | 只装配 source → manager → `StringProperties` bean；常量随配置项移到使用类 |
| `SelfProtection` | `SelfProtectionConfig` 不可变 | 构造注入 `StringProperties`，类内定义 window（静态）+ minCount/factor（动态 property）；删除 `SelfProtectionConfig` |
| `RegistryServiceImpl` | `LeaseTtlSource` + `Duration snapshotMaxAge` 注入 | 注入 `StringProperties`：类内定义动态 `leaseTtlMs`（构造 `LeaseTtlSource` lambda 给 `InstanceRegistry`）与静态 `snapshotMaxAgeMs`；新增 `stats()` |
| `ScheduledLeaseEvictionScheduler` / `ScheduledSnapshotScheduler` | `Duration`/`long` 构造 | 注入 `StringProperties`，类内定义静态 interval |
| `FileRegistrySnapshotStore` | `Path` 构造 | 注入 `StringProperties`，类内定义静态 path；保留 `Path` 构造供测试 |
| 新增 `ScfProperties`（artemis-common） | — | 静态项通用工具：`setStatic(true)` + 取值一次 + 值域校验（各使用类传入自己的键/默认值/范围） |
| 指标就地定义（D5） | — | 无集中 metrics binder / 统计 DTO：`SelfProtection` 自注册 protection/renewals/max gauges；`RegistryServiceImpl` 自注册心跳/清理 counters + 实例数 gauge（读 `InstanceRegistry.instanceCount()`） |
| 新增 `ClusterMember` / `ClusterMembers` | — | domain 接口 + infrastructure `StaticClusterMembers(StringProperties)`（类内解析静态成员列表）；v0.1 无消费方，v0.2 复制/readiness 分支①接入 |
| `AuthConfig` | registry-core infrastructure | 移到 server：`AuthConfig(StringProperties)` 类内定义 enabled/username/password（严格布尔 + 空白校验），interceptor/filter 接收该配置值对象 |

### 3. 服务装配（4B）

#### 3.1 端口与协议

| 端口 | 默认 | 协议/用途 | 认证 |
|---|---|---|---|
| 外部 gRPC | 9090 | SDK 四操作 + 心跳 | 是 |
| 外部 HTTP | 8080 | HTTP 辅助通道（phase-5 Admin API 接入点） | 是（`/readyz` 白名单） |
| 内部管理 HTTP | 9091 | actuator：health（含 readiness）、info、prometheus | 否（内部端口） |

- Spring Boot 属性：`server.port`、`spring.grpc.server.port`、`management.server.port`；端口可由环境变量/启动参数覆盖（框架标准能力）。
- HTTP readiness：`ReadinessGate` → `HealthIndicator`，`/actuator/health/readiness`（phase-3 延后的 HTTP readiness endpoint）。另在外部 HTTP 提供 `GET /readyz`（就绪 200 / 未就绪 503，无认证，供 LB 使用）。

#### 3.2 默认认证

- 单账户 HTTP Basic（`Authorization: Basic base64(username:password)`），v0.1 默认开启，配置可关闭（ADR-009）；布尔项使用 SCF 内置 `StringToBooleanConverter`（`Boolean.parseBoolean` 语义：仅 `true` 为 true）。
- gRPC：`@GlobalServerInterceptor` 实现 `ServerInterceptor`，校验 metadata `authorization`；缺失/错误返回 `UNAUTHENTICATED`；**框架健康服务 `grpc.health.v1.Health` 放行**（K8s gRPC probe 无凭证）。
- HTTP：`OncePerRequestFilter` 校验同一头；白名单按 `servletPath` 判定（`/readyz`、`/error`，兼容 context-path）。管理端口（独立 management context）不施加该过滤器。
- 凭证比较用 `MessageDigest.isEqual`（常量时间），不记录凭证；默认密码未修改时启动 WARN。SCF 将空白值视为未设置，空白凭证回落默认账户（文档明示，不宣称 fail-fast）。
- 错误语义：HTTP 401 + `WWW-Authenticate: Basic`；不泄露用户名是否存在（同一响应）。

#### 3.3 可观测

| 项 | 实现 | 出口 |
|---|---|---|
| metrics | Micrometer + Prometheus registry；指标由数据持有类就地注册（`SelfProtection` / `RegistryServiceImpl`），无集中 binder | `/actuator/prometheus`（管理端口） |
| 指标 | `artemis_registry_instances`、`artemis_registry_evictions_total`、`artemis_registry_heartbeat_instances_total`、`artemis_registry_self_protection`、`artemis_registry_self_renewals`、`artemis_registry_self_max` | 同上 |
| log | logback + logstash-logback-encoder，JSON 到 stdout；字段为 logstash 默认 schema（`@timestamp/level/logger_name/message`），MDC 可带 `service/instanceId` | stdout |
| trace | Spring Boot 4.1 官方 OpenTelemetry starter；默认关闭 OTLP 导出（`management.otlp.tracing.export.enabled=false`），配置 endpoint 后导出；gRPC/HTTP 观测自动接线 | OTLP |

#### 3.4 成员抽象

```java
public record ClusterMember(String id, String host, int port) {}

public interface ClusterMembers {
    List<ClusterMember> members();
}
```

`StaticClusterMembers`：启动解析 `artemis.registry.cluster.members`（逗号分隔，条目 `host:port`，id=原文），空配置返回空表（v0.1 单节点无 peer）。接口先行为 v0.2 复制/readiness 分支①预留，本 phase 无运行时消费方。

### 4. 依赖增量

| 模块 | 新增依赖 | 用途 |
|---|---|---|
| `registry-core` | `scf-core`、`scf-simple` | `Property` 抽象 + 配置门面（转换器/值域过滤） |
| `registry-server` | `spring-boot-starter-web`、`spring-boot-starter-actuator`、`micrometer-registry-prometheus`、`spring-boot-starter-opentelemetry`、`logstash-logback-encoder` 9.0、`scf-core`/`scf-simple` | MVC + 指标/健康 + trace + JSON 日志 + source 装配 |

组件均已在 `tech-stack-java.md` 总表（#11/#17/#18/#19/#31），无新增组件类别；`logstash-logback-encoder` 精确版本补入 server parent 属性。

### 5. 测试与配置的交互

- 单测：测试设施用 `MemoryMapConfigurationSource` 构造 `StringProperties`（不依赖环境/文件）；配置用例随定义类分布——`SelfProtectionTest`（窗口/阈值默认与动态）、`RegistryServiceImplTest`（TTL 动态/回放时效）、调度器测试（interval 静态）、`FileRegistrySnapshotStoreTest`（路径）、`StaticClusterMembersTest`（成员解析）、`AuthConfigTest`（严格布尔/空白）。
- 集成测试：既有 IT 的 gRPC channel 统一挂 Basic auth 拦截器（默认凭证）；需要覆盖业务配置的用例（如 100ms TTL）用 `@Primary StringProperties` 测试 bean 提供内存 source。
- `SnapshotRestoreIT` 的 `spring.main.web-application-type=none` 保留（该用例不测 HTTP）；认证开启后其 channel 同样挂凭证。

## 设计 review 定案（③）

| # | 决策 | 理由 | 原始文档同步 |
|---|---|---|---|
| D1 | 配置所有权边界：Spring Boot 与三方组件自有配置继续由组件自身管理；自定义业务配置由 SCF 管理 | 框架键由 autoconfiguration 消费、测试依赖自身属性源；SCF 复制框架键会形成双源漂移 | guidelines §10.1 增「配置所有权边界」（v1.15，已同步） |
| D2 | 配置定义分散到使用方：集中定义 `ConfigurationManager` + `StringProperties`；需要配置的类注入 `StringProperties` 并在类内定义 property，不集中定义 Property、不传递单个 `Property`（2026-10-18 验证期用户定案） | 避免配置门面成为上帝对象；配置靠近使用点，键/默认值/校验与消费逻辑同处一文件；共享配置（认证）以配置值对象定义一次 | guidelines §10.1 修订「配置项定义集中」条款（v1.16，已同步）；本文 §1/§2 重写 |
| D3 | Property 一律作为**类字段**存在（含静态项），使用处 `getValue()`；`ScfProperties` 静态方法返回 `Property` 而非值；全仓自定义配置统一走 SCF（含非本 phase 代码审计）（2026-10-18 验证期用户定案） | 配置对象可检查/监听/统一生命周期；避免「临时 property 取值后丢弃」；防止历史代码残留 `System.getProperty`/`@Value`/裸配置常量 | guidelines §10.2/§10.3（v1.17/v1.18，已同步）；本文 §1.3/§2 更新 |
| D4 | 监控（metrics/log/trace）属实现/装配关注点，**不进入领域/应用服务接口**；`RegistryService` 只保留业务用例 | 服务接口是业务契约，监控实现可替换（Micrometer/OTel/其他），接口不应因监控需求变化 | guidelines §1「监控不侵入服务接口」（v1.19/v1.20，已同步）；本文 §2/§3.3 更新 |
| D5 | 指标**就地定义**：由产生/持有数据的实现类自行注册 meters（`SelfProtection` 保护态三项；`RegistryServiceImpl` 心跳/清理计数 + 实例数 gauge），不建集中 metrics binder、不建统计 DTO（`RegistryStats` 删除）（2026-10-18 验证期用户定案） | 指标与数据来源同处，避免集中聚合层随指标增长膨胀；不需要为监控在接口上开数据出口 | guidelines §1（v1.20，已同步）；本文 §2/§3.3 更新 |

## 实现结果（⑦ 补）

### 实现要点

- **SCF 配置（D2）**：`RegistryConfigurationSources` 只装配三 source（系统属性 3 > 环境变量 2 > `artemis.properties` 1，数字大者优先）+ `ConfigurationManager` + `StringProperties`；`RelaxedEnvironmentConfigurationSource` 做 `ARTEMIS_REGISTRY_*` 键映射。具体 property 由使用类在类内定义：`SelfProtection`（窗口/阈值）、`RegistryServiceImpl`（TTL/回放时效）、两个调度器（周期）、`FileRegistrySnapshotStore`（路径）、`StaticClusterMembers`（成员）、`AuthConfig`（认证）。静态项经 `ScfProperties`（artemis-common）启动固化；数值项 `RangeValueConfig` 过滤；非法静态项 fail-fast。
- **消费改造**：组件统一注入 `StringProperties`（不接收单个 `Property`）；`RegistryServiceImpl` 在类内构造 `LeaseTtlSource` lambda 并新增 `stats()`；`InstanceRegistry.instanceCount()`；新增 `ClusterMember(s)` + `StaticClusterMembers`。
- **服务装配**：Spring MVC + actuator；外部 gRPC 9090 / 外部 HTTP 8080 / 管理 HTTP 9091（默认绑 127.0.0.1）；gRPC `@GlobalServerInterceptor` + HTTP filter Basic 认证（默认 `artemis/artemis`）；Prometheus 指标由数据持有类就地注册（无集中 binder/统计 DTO）；`/actuator/health/readiness` + `/readyz`；logback JSON；OTel starter（仅关闭 OTLP 导出，传播与 span 开启）。
- **测试**：既有 IT 统一挂默认凭证；新增配置/认证/指标/健康/观测单测与端到端 IT。

### review 修复记录（8 条 findings 全部处置）

| # | finding | 处置 |
|---|---|---|
| 1 | `auth.enabled` 用 `Boolean.parseBoolean`，`yes/1/拼写错误` 静默关闭认证（安全 fail-open） | 改严格布尔解析：仅 true/false（忽略大小写），非法值启动失败；新增用例 |
| 2 | trace 用全局 `management.tracing.export.enabled=false`，连传播/自动配置一起关掉 | 改用 `management.tracing.export.otlp.enabled=false`，仅关 OTLP 导出 |
| 3 | 管理端口默认绑 0.0.0.0 且无认证，指标/健康暴露面过大 | 默认 `management.server.address=127.0.0.1`，容器/集群部署显式覆盖 |
| 4 | Basic scheme 大小写敏感（RFC 7617 要求不敏感） | `BasicAuth.matches` 按 scheme 忽略大小写解析；新增小写 scheme 用例 |
| 5 | 成员端口越界错误未带配置键 | `parseMembers` 校验端口/主机并统一带 `artemis.registry.cluster.members`；新增用例 |
| 6 | 单次抓取 6 个指标各自执行 `stats()`（6 次 O(实例数) 扫描） | `RegistryMetrics` 1s 共享快照缓存（`ponytail:` 注明陈旧上限）；用例断言仅统计一次 |
| 7 | 动态/静态 property 构建逻辑重复，易漂移 | 合并为单一 `property(..., isStatic, ...)` 构建路径 |
| 8 | server 显式声明 mockito-core（starter-test 已传递） | 移除冗余声明 |

review 第二轮修复记录（15 条 findings）：

| # | finding | 处置 |
|---|---|---|
| 1 | `auth.enabled` 用内置 boolean converter，非 `true` 值静默为 false | 按用户 D3 定案保留 SCF 内置转换器；文档明确 `Boolean.parseBoolean` 语义，默认值 true 保证未配置时认证开启 |
| 2 | 配置 source 均为启动一次性，动态 property 实际不更新 | `ReloadablePropertiesFileConfigurationSource`（WatchService + raiseChangeEvent）；新增重载测试 |
| 3 | 命令行/application.properties 的 `artemis.*` 对 SCF 不可见 | 新增 `SpringEnvironmentConfigurationSource`（最高优先级）；新增测试 |
| 4 | 空白凭证回落默认账户 | SCF 语义（空白视为未设置）；按评测友好保留默认账户并文档明示；删除不可达的 fail-fast 宣称 |
| 5 | gRPC 健康服务被全局认证拦截 | `grpc.health.v1.Health` 加入放行集合；新增测试 |
| 6 | domain 依赖 SCF 配置与 guidelines §2 冲突 | 按用户 D2/D3 定案更新 guidelines §2（domain 不依赖 ConfigurationSource，可用 StringProperties/Property） |
| 7 | 成员解析不支持括号 IPv6、裸 IPv6 误解析 | 括号 IPv6 解析 + 裸 IPv6 fail-fast；新增测试 |
| 8 | `.gitignore` 的 `data/` 误伤 docs/data | 改为 `**/registry-snapshot.bin*` 精确模式 |
| 9 | 配置文件不可读/损坏被 SCF 吞掉 | 可重载 source 构造期用 `Properties.load` 校验，失败 fail-fast |
| 10 | `/readyz` 白名单不兼容 context-path | 改用 `servletPath` 判定；新增测试 |
| 11 | 日志字段名与设计文档不一致 | 文档改为 logstash 默认 schema（`@timestamp/logger_name`） |
| 12 | 三个保护态 gauge 各自 stats()，抓取副作用/不一致 | SelfProtection 内 1s 共享快照缓存（就地定义） |
| 13 | 调度器测试绕过生产配置路径 | 新增生产 `StringProperties` 构造路径测试（配置周期生效） |
| 14 | trace sampling 1.0 但导出关闭 | 移除该属性，回落 Boot 默认 0.1 |
| 15 | AuthObservabilityIT 依赖宿主环境配置 | 测试提供 `@Primary StringProperties` 隔离 |

验证期设计修正（D3，用户定案）：

- 全部配置项（含静态项）以 `Property` 字段保存：`SelfProtection.windowSeconds`、`RegistryServiceImpl.leaseTtlMs/snapshotMaxAgeMs/clockSkewToleranceMs`、两个调度器 interval、快照 store path、成员列表、认证三项；使用处 `getValue()`。
- `ScfProperties` 提供泛型 `staticProperty`（任意目标类型 + TypeConverter/值域过滤）与 long/int/string/**boolean** 便捷方法，全部返回 `Property` 字段；布尔直接使用 SCF 内置 `StringToBooleanConverter`（`Boolean.parseBoolean` 语义：仅 `true` 为 true）。`AuthConfig.enabled` 为 `Property<String,Boolean>`。
- 快照时钟回拨宽容从硬编码常量改为 SCF 配置 `artemis.registry.snapshot.clockSkewToleranceMs`（默认 60s）。
- 全仓配置审计：移除测试中的 `@Value`（改用测试 `StringProperties`）；唯一剩余的 `System.getenv` 是 SCF source 适配器本身；框架/三方配置（端口/actuator/OTLP）按 D1 仍归 Spring Boot。
- 回归：common 4 / core 72 / server 21 / IT 13 全绿。

验证期设计修正（D2，用户定案）：

- 配置定义从集中门面 `RegistryProperties` 改为**分散到使用类**；集中只剩 `ConfigurationManager` + `StringProperties`；组件接收 `StringProperties` 自建 property，不再传单个 `Property`。
- 删除 `RegistryProperties` / `PropertyLeaseTtlSource`；新增 `ScfProperties` 静态项工具；`AuthConfig` 移到 server，构造注入 `StringProperties` 并以 Property 字段保存三项认证配置。
- 测试设施改为 `TestProperties`（内存 source → manager → StringProperties）；配置断言随定义类分布。
- 回归：common 5 / core 72 / server 22 / IT 13 全绿。

验证期补充修正（smoke test 发现）：

- OTel starter 带入的 Micrometer OTLP metrics push registry 默认外发：`management.otlp.metrics.export.enabled=false`（无 collector 时不再 WARN 外发）。
- 工作目录无 `artemis.properties` 时 SCF 会打完整堆栈 WARN：装配层仅在文件存在时注册 file source。
- 可执行 fat jar 加 `-exec` classifier：原 jar 保持普通布局，failsafe 测试 classpath 不受 `BOOT-INF` 重排影响。

## 文档同步（⑩ 填）

| 文档 | 变更 |
|---|---|
| `docs/product/artemis.md` v1.3 | phase-4 交付节：SCF 配置分层/热加载、默认认证、三端口、Prometheus/JSON 日志/OTel、HTTP readiness、成员抽象；模块职责补 common 配置工具 |
| `docs/arch/artemis-next-architecture.md` v1.21 | §2.2 v0.1 端口落点（外部 gRPC/HTTP + 管理 HTTP，peer 端口 v0.2）；§4.1 HTTP readiness 出口 |
| `docs/decisions/adr-009-security-by-default.md` v1.1 | v0.1 实现语义：默认账户、关闭开关、传输与放行面、空白凭证处理 |
| `docs/dev/coding-guidelines-java.md` v1.21 | §1 监控就地定义/不侵入接口；§2 domain 依赖配置边界；§10 配置定义分散/Property 字段/全仓审计；§3 格式化命令修正 |
| `docs/dev/tech-stack-java.md` v1.18 | #19 日志精确版本 9.0 与字段 schema |
| `CLAUDE.md`、`.claude/rules/tech-java.md` | Spotless 命令修正为 server/client 两个 parent（根聚合器无插件前缀） |
| 索引 | phase-4 设计/测试文档已在 `phases/README.md` 登记；无新增 ADR/API/proto 变更 |

## 更新历史

| 版本 | 日期 | 变更说明 |
| ------ | ------ | ------ |
| 1.12 | 2026-10-18 | phase-5 同步：租约 TTL 配置定义点迁入 `InstanceRegistry`（§1.4 配置表 owner 更新；§2/§实现记录中的 phase-4 历史描述保留） |
| 1.11 | 2026-10-18 | 第二轮 review 15 条 findings 处置：可重载文件 source、Spring Environment source、健康服务放行、IPv6、context-path、gauge 缓存等 |
| 1.10 | 2026-10-18 | `RelaxedEnvironmentConfigurationSource` 移至 artemis-common（通用 source，各服务复用） |
| 1.9 | 2026-10-18 | D5：指标就地定义（删除 RegistryMetrics/RegistryStats），SelfProtection/RegistryServiceImpl 自注册 meters；guidelines v1.20 同步 |
| 1.8 | 2026-10-18 | D4：监控不侵入服务接口——RegistryService 移除 stats()，指标绑定实现类；guidelines v1.19 同步 |
| 1.7 | 2026-10-18 | D3 细化：ScfProperties 泛型 staticProperty + boolean Property 支持；AuthConfig 使用 Property<String,Boolean> |
| 1.6 | 2026-10-18 | 验证期 design 修正 D3：Property 一律字段化、ScfProperties 返回 Property、全仓配置审计；guidelines v1.17 同步 |
| 1.5 | 2026-10-18 | 验证期 design 修正 D2：配置定义分散到使用方，集中仅 manager + StringProperties；§1/§2 重写 |
| 1.4 | 2026-10-18 | 验证期补充修正：OTLP metrics 默认关闭、file source 条件注册、exec classifier |
| 1.3 | 2026-10-18 | 实现结果与 review 修复记录（8 条 findings 全部处置，含安全 fail-open 与 trace 配置键修复） |
| 1.2 | 2026-10-18 | review 定案 D1：配置所有权边界（框架/三方组件配置归组件，SCF 管自定义配置），guidelines §10.1 v1.15 同步 |
| 1.1 | 2026-10-18 | 产品设计定稿：SCF source/manager/properties、配置项与动静分型、消费改造、服务装配（端口/认证/可观测/成员）、依赖增量 |
| 1.0 | 2026-10-18 | 开工骨架（范围据 plan v1.11 phase-4 调整） |
