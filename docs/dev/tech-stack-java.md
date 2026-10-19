# Java 技术选型

版本: 1.20    更新时间: 2026-10-19

> 本文是新一代 Artemis Java 实现的技术选型基线：语言与运行时、工程结构、全量技术组件清单与依赖冲突策略。设计依据为[架构设计](../arch/artemis-next-architecture.md)（§3 技术栈策略、§7 关键决策 D8），开发规范见[Java 开发规范](coding-guidelines-java.md)。Rust 实现（v1.x 混合集群）直接消费仓库顶层 `proto/` 契约目录（见[proto 契约规范](proto-contract.md)），不受本文 Java 组件约束。

## 1. 语言与运行时

| 项 | 决策 | 说明 |
|---|---|---|
| 服务端 JDK | Java 25 LTS（`release=25`） | common、各 core、各 server；架构 D8 |
| SDK 编译目标 | Java 8（`release=8`） | artemis-client、spring-boot-starter-artemis-client；同一 Maven reactor 混合编译 |
| 线程模型 | gRPC handler 走虚拟线程 executor | IO 密集、海量长连接（心跳/订阅推送）；Java 25 已解决 synchronized pinning（JEP 491），无锁升级顾虑 |
| 定时任务 | `ScheduledExecutorService` 平台线程池 | 租约过期清理、快照落盘等周期任务 |
| GC | 默认 G1 | ZGC 作为大堆可选项，v1.0 容量压测后决定，不预绑定 |

虚拟线程使用规则与禁忌见开发规范 §4。

## 2. 工程结构

### 2.1 仓库布局与模块划分

仓库为**多语言 monorepo，顶层按语言分目录**；契约是语言无关纯目录（组织与治理见[proto 契约规范](proto-contract.md)）：

```text
artemis/
├── proto/        契约：六组 .proto（common + 五组 service，各 v1），语言无关，非 Maven 模块
├── java/         Java 实现：Maven 单 reactor（本节）
└── rust/         Rust 实现（v1.x 才创建）：Cargo workspace，从 ../proto 生成
```

Java reactor 居 `java/`（构建入口 `mvn -f java/pom.xml`；根目录不放聚合构建文件，各语言构建入口在各自目录）。groupId 统一 `org.mydotey.ai.artemis`，artifactId 前缀 `artemis-`（例外：三个 parent `artemis-parent`（java/ 纯聚合器）/`artemis-server-parent`/`artemis-client-parent`；starter 为 `spring-boot-starter-artemis-client`，遵循 Spring starter 命名惯例）：

```text
java/                            单 reactor 单构建入口（mvn -f java/pom.xml）
├── pom.xml                      artemis-parent：纯聚合器（不发布），目录分界 = server/ 服务端线 vs client/ SDK 线
├── server/                      服务端线（parent = artemis：${revision} 统一版本 + dm + 全局依赖）
│   ├── pom.xml
│   ├── artemis-common               服务端公共规范/工具 (Java 25)
│   ├── artemis-registry-core        registry 内核，纯 Java 零 Spring (Java 25)
│   ├── artemis-registry-server      Spring Boot 装配 (Java 25)
│   ├── artemis-discovery-core       discovery 投影内核 (Java 25)
│   ├── artemis-discovery-server     Spring Boot 装配 (Java 25)
│   ├── artemis-management-core      治理面内核 (Java 25)
│   └── artemis-management-server    Spring Boot 装配 (Java 25)
└── client/                      SDK 线（parent = artemis-client-parent：独立版本线 + 独立依赖管理，见 §2.3）
    ├── pom.xml
    ├── artemis-client               注册 + 发现统一 SDK，零 Spring (Java 8)
    └── spring-boot-starter-artemis-client   Spring 集成 (Java 8)
└── (artemis-dist 打包模块 v0.3 起引入，不影响边界)
```

### 2.2 proto 契约模型

Java 各消费模块从仓库顶层 `proto/`（组织与治理见[proto 契约规范](proto-contract.md)）引用需要的 .proto 文件（`sourceDirectories` 指向 `../../../proto` 单 root——模块在 `java/{server,client}/` 目录下，模块 basedir 三层回到仓库根；includes 按下表矩阵圈定生成组），生成代码到自己的包：

| 模块 | 生成的 proto 组 | protoc 基线 | 编译版本 |
|---|---|---|---|
| artemis-common | **common.v1 + projection.v1 + management.v1（服务端共享，各 core 依赖）** | 4.x | Java 25 |
| artemis-registry-core | registry.v1 + replication.v1（management.v1 经 artemis-common 共享） | 4.x | Java 25 |
| artemis-discovery-core | discovery.v1 | 4.x | Java 25 |
| artemis-management-core | （无自生成组；management.v1 经 artemis-common 共享） | 4.x | Java 25 |
| artemis-client | common.v1 + registry.v1 + discovery.v1 | **3.25.x** | Java 8 |
| Rust registry（v1.x） | common.v1 + registry.v1 + replication.v1 + projection.v1 + management.v1（只读查询实现） | prost/tonic 构建锁定（v1.x 定基线） | — |

**服务端 common.v1、projection.v1 与 management.v1 统一生成于 artemis-common**（common 定案 2026-10-11；projection 2026-10-12；management 2026-10-19）：被多端消费的消息在服务端只生成一份，各 core 经 Maven 依赖共用——单 artifact 单 FQCN，服务端模块间可直接传递。projection.v1 归 artemis-common 是因 registry-core 与 discovery-core 均消费（分发流服务端与投影消费端）；management.v1 归入是因 registry-core 实现只读查询 service、management-core 作为客户端调用，双端自生成会在 v0.4 三服务 all-in-one 同 JVM classpath 加载序碰撞。client 独立于服务端（enforcer 禁依赖 + protoc/runtime 分叉），自生成 common+registry+discovery，不生成 management.v1。其余各组仍在多模块各自生成（如 registry.v1 见服务端与 client，同 FQCN 异 artifact），跨模块只走 wire（开发规范 §1）。

要点：

- **公共数据模型消息**（Instance、ServiceInstances 等）定义在 `proto/common/v1/`，各组 proto import 之；import 方向永远「内部协议 → 共享」，不出现环。
- **客户端用 protoc 3.25.x 生成、服务端用 4.x 生成**：3.25 生成代码的宿主 runtime 支持窗口为 **3.25.x–4.x**（protobuf 官方政策是双侧窗口：gencode V 仅保证 runtime V 与 V+1；≥ V+2 在 class-init 时 poison-pill 硬失败——宿主 protobuf 5.x 与 < 3.25 同列不支持场景），这是「各自生成」模型的直接红利——共享 artifact 方案无法两端分叉 protoc 版本。
- 同一 proto 组会在多个模块生成同 FQCN 的类（如 registry.v1 在 core 与 client 各一份）；跨模块传递边界纪律见开发规范 §1。
- 插件对 JDK 25 + protoc 4.x/3.25 + grpc-java 组合的支持、跨模块 proto import 与 `sourceDirectories` 单 root + includes 的具体配置，均在脚手架阶段一并验证；跨仓库复用需求出现时再升级为 proto-sources 依赖机制。

### 2.3 依赖硬约束

| 约束 | 执行机制 |
|---|---|
| core 模块零 Spring（延续原产品实践，能力基线 §1–§2 组件事实） | maven-enforcer `bannedDependencies` 禁 `org.springframework:*`，CI 强制 |
| client 不依赖任何服务端模块 | 同样用 enforcer `bannedDependencies` 硬约束：禁 `org.mydotey.ai.artemis` 下 `artemis-common` 与各 `*-core`/`*-server` artifact；proto 契约经文件级引用（`proto/` 为纯目录非 Maven 模块，本就无 jar 可依赖） |
| 服务端模块统一版本（parent、common、各 core、各 server） | parent `${revision}` 占位符 + **flatten-maven-plugin**（Maven 3.x CI Friendly Versions 必须：无 flatten 时 install/deploy 产物 pom 残留字面量 `${revision}`，外部不可消费） |
| **client SDK 独立版本线 + 独立依赖管理**（artemis-client-parent：client、starter） | 字面版本号（非 parent 属性——属性随服务端线演进会把版本耦合回来），随 SDK 语义化版本独立演进；**parent 继承树独立于 artemis**（聚合仍在 java/ reactor——Maven modules 聚合与 parent 继承解耦）：dm 自建（grpc/protobuf 3.25/scf/slf4j 2.0 线自钉），不 import SB BOM、不受服务端 guava flavor/gson 干预（保留 grpc 原生传递树）、全局零 Lombok；工具链插件版本（spotless/checkstyle 等）与服务端各持一份、允许独立节奏 |

## 3. 技术组件总表

版本列为基线版本线，精确 patch 版本在搭建脚手架时锁定最新稳定。**# 为稳定编号**：不随插入重排，新增条目顺取下一号（表按类别归位插入）。

| # | 类别 | 组件 | 选型与版本基线 | 用途 / 作用范围 | 备注 |
|---|---|---|---|---|---|
| 1 | 语言运行时 | 服务端 JDK | Java 25 LTS（`release=25`） | common、各 core、各 server | 虚拟线程（JEP 491 后无 pinning 顾虑） |
| 2 | 语言运行时 | SDK 编译目标 | Java 8（`release=8`） | artemis-client、starter | 同 reactor 混合编译 |
| 3 | 语言运行时 | GC | 默认 G1 | 服务端 | ZGC 大堆选项留 v1.0 压测后定 |
| 4 | 构建 | 构建系统 | Maven 3.9.x | java/ 单 reactor | Maven 4 成熟后评估 |
| 5 | 构建 | 依赖治理 | maven-enforcer-plugin + flatten-maven-plugin | java/ reactor | enforcer：Java/Maven 版本、依赖上界（requireUpperBoundDeps）、core 禁 Spring 与 client 禁服务端模块（§2.3）；flatten：服务端 `${revision}` 落库（§2.3；client 线为字面版本不经 flatten 处理） |
| 6 | 构建 | proto 生成 | protobuf-maven-plugin（**ascopes**）+ protoc + grpc-java 插件 | 各消费模块 | xolstice 已于 2025-04 archived，其 README 推荐 ascopes 为后继；`sourceDirectories` 相对路径，按 §2.2 生成矩阵 |
| 7 | 框架 | 应用框架 | Spring Boot 4.1.x | 仅各 server 模块 | DI、配置绑定、MVC、actuator；不出现在 core/client classpath |
| 8 | 通信 | RPC 框架 | grpc-java 1.84.x（reactor 窄版本线锁 1.84.2，parent dm 先声明优先） | 服务端 grpc-netty；client grpc-netty-shaded | 五通道全 gRPC；client shaded 隔离宿主 netty；grpc 线内 guava flavor 混排由 parent dm 统一 jre（33.6.0-jre），grpc 升级时同步 |
| 9 | 通信 | 序列化（服务端） | protobuf-java 4.x | 服务端 proto 消息 + 服务端快照文件格式 | 快照 = proto 二进制 + 版本头，不自研格式 |
| 10 | 通信 | 序列化（client） | protobuf-java **3.25.x**（protoc 3.25 生成） | artemis-client | 宿主 runtime 窗口 3.25.x–4.x，见 §5 |
| 11 | HTTP | HTTP 辅助通道 | Spring MVC（SB 内置） | server 模块 | 外部 HTTP 辅助端点 + console REST；不引 WebFlux |
| 12 | 代码简化 | Lombok | 1.18.x | java/ reactor | `provided` scope，不传递给 SDK 使用方 |
| 31 | 配置管理 | SCF（Spark Configuration Framework） | `org.mydotey.scf:scf-bom` 1.6.x（scf-core + scf-simple） | java/ reactor（含 client） | manager / sources / property 三层抽象，与具体配置源解耦；原产品同栈（Maven Central 在库）；Java 8 目标两端通用，传递闭包仅 scf-core + lang-extension + slf4j-api；动静分型与合规校验规范见[开发规范](coding-guidelines-java.md) §10 |
| 32 | 模型转换 | MapStruct | 1.6.x（`mapstruct` + `mapstruct-processor`） | 各 core（[ADR-015](../decisions/adr-015-layered-model-inside-core.md) 层间 mapper） | 编译期生成 mapper 实现，**零运行时依赖**（mapstruct 仅注解，processor 为 annotation processor）；与 Lombok 协同需 `lombok-mapstruct-binding`（processor path 顺序）；Java 25 支持在实施期验证（见 §4.6） |
| 34 | 可观测（SDK） | Micrometer（client 库模式） | `io.micrometer:micrometer-core` 1.17.x（client 线自钉，Java 8 基线兼容） | artemis-client（v0.1 phase-7 起） | SDK 可观测标准化端口（Counter/Timer/Gauge 全套 + 标签维度），替代自建 metrics 接口；`newClient(properties, meterRegistry)` 注入、缺省挂 `Metrics.globalRegistry`（官方库模式，宿主 `Metrics.addRegistry` 接管、不加则空转）；指标集见 phase-7 设计文档；starter（phase-8）自动注入 Boot `MeterRegistry`，经宿主 actulator 供 Prometheus 抓取 |
| 33 | 通信 | gRPC（Spring Boot 官方装配） | `org.springframework.boot:spring-boot-starter-grpc-server`（SB 4.1 官方，版本随 SB BOM；底层 spring-grpc-core 1.1.x） | 各 server 模块 | 官方 gRPC 自动装配（`@GrpcService` + `spring.grpc.server.*` 配置），**不自写 Server/生命周期**；集成测试支持 `org.springframework.boot:spring-boot-grpc-test`（随机端口/进程内）；grpc-java 版本线：SB BOM 管理 1.83.1，本 reactor 窄版本线先声明锁 1.84.2（`requireUpperBoundDeps` 兜底），phase-2 实测兼容（见 §4.7） |
| 13 | 数据（期 3） | ORM | MyBatis-Plus 3.5.x + SB starter | 仅 management | 版本锁 SB 4.1 适配线，期 3 实施时锁定 |
| 14 | 数据（期 3） | Schema migration | Flyway（SB 管版本） | 仅 management | MySQL/PG 双方言脚本 |
| 15 | 数据（期 3） | 连接池 | HikariCP（SB 自带） | 仅 management | — |
| 16 | 数据（期 3） | 数据库 | MySQL 8.x / PostgreSQL 16+ | 运维侧二选一 | 双方言为产品承诺 |
| 17 | 可观测 | Metrics | Micrometer + Prometheus registry | 服务端（Boot actuator）+ client SDK（micrometer-core 库模式） | actuator 端点 v0.1 第一天；client 侧 #34 |
| 18 | 可观测 | Trace | OpenTelemetry（SB 4.1 官方集成） | 全服务端 | v0.1 传播 + 基础 span |
| 19 | 可观测 | 日志 | SLF4J + Logback + logstash-logback-encoder **9.0** | 服务端；client 仅依赖 SLF4J API | 结构化 JSON 第一天（logstash 默认字段 schema：`@timestamp/logger_name`）；SDK 不打日志实现进宿主（避免 multiple SLF4J providers 冲突） |
| 20 | 测试 | 单元测试 | JUnit 5 + Mockito + AssertJ | java/ reactor | — |
| 21 | 测试 | 集成测试 | Testcontainers | DB（期 3）、K8s 验收（v1.0） | 集群语义测试用进程内多实例（随机端口），不起容器 |
| 22 | 测试 | 压测工具 | 待定（v1.0 容量验证时选型） | — | v0.x 不引入 |
| 23 | 质量 | 格式化 | Spotless + Palantir java-format | java/ reactor | CI 强制 `spotless:check` |
| 24 | 质量 | 静态检查 | Checkstyle | java/ reactor | 命名/结构规则 |
| 25 | 质量 | Bug 模式 | Error Prone | java/ reactor | 经 compiler `annotationProcessorPaths` |
| 26 | 质量 | 覆盖率 | JaCoCo | java/ reactor | 出报告，不设强制门槛 |
| 27 | CI/CD | CI | GitHub Actions | 全仓（按路径触发：java/** Java job、rust/** Rust job、proto/** 全量） | **JDK 25 单构建 job**（工具/插件进程全跑 JDK 25），client 的 Java 8 运行时兼容经 **Maven toolchains 以 JDK 8 fork 测试 JVM** 验证——不设 JDK 8 独立构建 job（`release=25` 模块在其上无法构建） |
| 28 | CI/CD | 发布渠道 | Maven Central（Central Portal）+ 容器镜像仓库 | SDK/starter（v1.0）、镜像 | 镜像仓库 GHCR vs Docker Hub 待 v1.0 定 |
| 29 | SDK 分发 | starter 自动装配 | `AutoConfiguration.imports` + `spring.factories` 双注册 | starter | 覆盖 SB 2.7+ 与老 SB 2.x；编译目标 Java 8 |
| 30 | SDK 分发 | client 依赖纪律 | grpc-netty-shaded + grpc-stub + grpc-protobuf（传递 protobuf-java 3.25.x）+ SCF（scf-simple，传递 scf-core/lang-extension）+ SLF4J API + micrometer-core（#34） | artemis-client | grpc-netty-shaded **不传递** stub/protobuf 集成类，生成代码必需 grpc-stub/grpc-protobuf；冲突策略见 §5 |

生效期暗含在类别中：#13–16 期 3（v0.4）引入；v0.1 实际依赖面为语言、构建、SB、gRPC、Lombok、SCF、可观测、测试与质量工具。

测试与质量工具（#20、#23–26）进程统一运行于 JDK 25——client 的 `release=8` 是 javac 交叉编译目标，与工具进程 JVM 无关（Mockito 5.x / Checkstyle 10.x / Palantir format 现行版均需 Java 11+，不受影响）；client 的 Java 8 兼容性在测试 JVM 层验证（toolchains，#27）。

## 4. 关键选型理由

### 4.1 「内核零 Spring」的分模块强制

原产品内核零 Spring 是组件层既成事实（能力基线 §1–§2：artemis-common 为零 Spring 基础库；非 §5 资产清单条目，此处延续实践而非引用资产编号）。本设计将 core 与 server 拆为独立模块，用 enforcer `bannedDependencies` 硬约束而非包约定——依赖检查在构建期失败，比 review/ArchUnit 可靠。该边界同时为 v1.x Rust 对照实现划出清晰内核范围。

### 4.2 proto 纯契约目录（语言无关，按端生成）

契约定义为**文件目录而非 Maven 模块**：任何语言（Java/Rust/Go）直接从 .proto 文件生成，各端编译版本与 protoc 版本自由（client Java 8 + protoc 3.25，服务端 Java 25 + protoc 4.x）。Java 服务端内部以 artemis-common 集中生成 common.v1、projection.v1 与 management.v1 消除服务端重复（§2.2）；其余组多端各生成一份同 FQCN 类，以「跨模块只走 wire」纪律消解。多语言路线（Rust 混合集群、Go/Rust SDK）下这是最干净的模型，etcd 等项目同路线。

### 4.3 client 用 grpc-netty-shaded

shaded 版将 netty 重打包进 `io.grpc.netty.shaded.*` 命名空间，与宿主自带 netty（任意版本）类名隔离，零冲突——Java SDK 分发主流做法。

### 4.4 服务端快照 = proto 二进制 + 版本头

复用 proto 契约做快照序列化，不自研文件格式；版本头保证跨版本回放兼容（v1.0 滚动升级验收依赖）。

### 4.5 配置管理：SCF 三层解耦

- **分层**：`ConfigurationManager` 管理配置源集合（`ConfigurationSource` 按优先级叠加，如系统属性 > 环境变量 > 配置文件），业务代码只依赖 `Property` 抽象——增删换源不改业务代码。
- **动静分型在 property 层落地**：动态配置用 SCF `Property` 原生动态语义（`getValue` 每次取最新 + 变更监听）；静态配置启动取值后固化（规范见开发规范 §10）。
- **合规校验用 SCF 自带能力**：`RangeValueFilter`（值域）+ `TypeConverter`（类型），配置错误在启动期暴露。
- **不用 Spring `@ConfigurationProperties`**：core/client 零 Spring，它进不了内核与 SDK；且其为启动期静态绑定，动态配置支持弱。SCF 为原产品同栈（`org.mydotey.scf` 已发布 Maven Central），Java 8 目标，服务端（Java 25）与 client（Java 8）两端通用。

### 4.6 层间 mapper：MapStruct

- **编译期生成、零运行时**：`mapstruct-processor` 在编译期按接口生成实现类，产物是普通 Java（无反射、无运行期依赖），符合内核零 Spring/低依赖纪律；`mapstruct` 注解 artifact 为 compile，processor 走 `annotationProcessorPaths`。
- **与 Lombok 协同**：processor path 需同时含 `mapstruct-processor`、`lombok`、`lombok-mapstruct-binding`，否则 MapStruct 看不到 Lombok 生成的访问器。
- **语义转换不交给生成器**：**枚举整表手写**（proto 常量带前缀 `INSTANCE_STATUS_UP` 与领域名 `UP` 不对应，无法按名映射；`UNRECOGNIZED` 哨兵一并处理）、缺省值语义（weight ≤ 0 归一、`UNSPECIFIED` 处理）、repeated/map 结构展开用显式手写方法，MapStruct 只承载字段级主映射（开发规范 §2）。
- 否决手写全部 mapper：层间字段映射样板多、易漏字段；否决运行期映射框架（反射开销与运行期依赖）。
- **实施期验证点**（phase-2）：MapStruct 1.6.x 对 `release=25` + Lombok 1.18 的组合支持；不支持则回落手写 mapper（分层与模型边界决策不受影响）。

### 4.7 gRPC 服务端：Spring Boot 官方组件

- **不自写 Server/生命周期类**：`org.springframework.boot:spring-boot-starter-grpc-server`（Spring Boot 4.1 官方模块）提供 gRPC 服务端自动装配——服务实现以 `@GrpcService` 注册、端口与传输经 `spring.grpc.server.*` 配置，生命周期由 starter 管理（含优雅停机接入 Spring 生命周期）。
- **坐标沿革（phase-2 实施期定案）**：spring-grpc 项目自身的 starter 线止于 1.0.3（SB 3.x 适配）；SB 4.0 起 starter 收编为 Spring Boot 官方模块（`spring-boot-starter-grpc-server` / `spring-boot-grpc-test`），spring-grpc 1.1.x 只发布 core 库（`@GrpcService` 注解即出自其 `org.springframework.grpc.server.service` 包）。SB 4.1 reactor 的正确坐标是 Boot 官方 starter，版本随 SB BOM。
- **测试支持**：`org.springframework.boot:spring-boot-grpc-test` 提供随机端口（`spring.grpc.server.port=0` + `@LocalGrpcServerPort`）与进程内传输支持，集成测试（`*IT`）据此起服务，不起外部进程（guidelines §7）。
- 与 #8 grpc-java 的关系：SB 4.1.1 starter 传递 grpc-java 1.83.1（SB BOM 管理），本 reactor 的窄版本线在前声明锁 1.84.2（parent `dependencyManagement` 先声明者优先）；phase-2 实测（enforcer + 编译 + 装配 + 进程内起服务）兼容。grpc 线内 guava flavor 混排（stub/protobuf/netty → android、services → jre）由 parent dm 统一 jre flavor。

## 5. 依赖冲突策略（SDK 对宿主的影响）

| 组件 | 对宿主影响 | 结论 |
|---|---|---|
| netty | grpc-netty-shaded 命名空间隔离 | **零影响**（任意宿主 netty 版本） |
| protobuf | client 用 3.25 生成代码，宿主 runtime 支持窗口 3.25.x–4.x（protobuf 政策为双侧窗口：gencode V 仅保证 runtime V/V+1） | 宿主 protobuf < 3.25 或 ≥ 5.x 属不支持场景，文档声明 |
| grpc | 宿主自用 grpc 时 classpath 调解只留一个版本 | 无法根除；grpc stub API 高度稳定，实践中风险低，声明支持的宿主 grpc 下限 |
| guava/perfmark | grpc-java 传递依赖，不 shaded | 理论冲突源；grpc 使用 guava API 子集极窄，业内普遍接受，标注即可 |

## 6. 待定项

| 项 | 决策时机 |
|---|---|
| 压测工具（10 万实例容量验证） | v1.0 |
| 容器镜像仓库（GHCR vs Docker Hub） | v1.0 |
| GC 最终线（G1 vs ZGC） | v1.0 压测后 |
| MyBatis-Plus 对 SB 4.1 适配版本确认（无适配则评估原生 MyBatis + 通用 mapper） | 期 3（v0.4）实施时 |

## 更新历史

| 版本 | 日期 | 变更说明 |
| ------ | ------ | ------ |
| 1.20 | 2026-10-19 | v0.1 phase-7 设计 review 定案：组件总表新增 #34 Micrometer（client 库模式，SDK 可观测标准端口，替换自建 ClientMetrics）；#17/#30 同步 |
| 1.19 | 2026-10-19 | management.v1 生成矩阵调整：统一生成于 artemis-common（registry 实现只读查询 / management 消费，registry-core 与 management-core 不再自生成） |
| 1.18 | 2026-10-18 | #19 日志补精确版本 logstash-logback-encoder 9.0 与 logstash 默认字段 schema（phase-4 实施） |
| 1.17 | 2026-10-13 | parent 命名统一：artemis-parent（java/ 聚合器，原 artemis-java）/artemis-server-parent（服务端线，原 artemis）/artemis-client-parent（SDK 线）——三 pom 角色由名字表达，前缀例外条款更新 |
| 1.16 | 2026-10-13 | 目录按线分组：java/ 拆 server/（服务端线）与 client/（SDK 线），java/pom.xml 改纯聚合器 artemis-java（单构建入口不变）；proto sourceDirectories 路径随深度更新（§2.1/§2.2） |
| 1.15 | 2026-10-13 | SDK 依赖管理独立：新增 artemis-client-parent（§2.1 结构 + §2.3）——client/starter 脱离 artemis 继承树（聚合不变），dm 自建（slf4j 2.0.20 自钉、无 SB BOM、无服务端 guava/gson 干预、零 Lombok） |
| 1.14 | 2026-10-13 | 版本线拆分：client SDK（artemis-client + starter）独立字面版本，与服务端 ${revision} 解耦（§2.3 拆两行、#5 备注）；parent dm 移除 artemis-client 条目 |
| 1.13 | 2026-10-13 | code-review 修复：#8 版本字段更正为 1.84.x（窄版本线锁 1.84.2）并补 guava flavor 统一说明（parent dm jre，grpc 升级同步） |
| 1.12 | 2026-10-13 | phase-2 实施期坐标定案：#33/§4.7 更正为 Spring Boot 4.1 官方模块（`spring-boot-starter-grpc-server` / `spring-boot-grpc-test`）——spring-grpc 自身 starter 止于 1.0.3（SB 3.x 线），SB 4.0 起收编官方；grpc 1.84.2 窄版本线与 guava jre 统一实测记录 |
| 1.11 | 2026-10-13 | code-review 修复：#33/§4.7 Spring gRPC starter Maven 坐标更正为 `org.springframework.grpc:spring-grpc-server-spring-boot-starter`、测试支持为 `org.springframework.grpc:spring-grpc-test`；§4.6 补「枚举整表手写」（proto 常量前缀与领域名不对应） |
| 1.10 | 2026-10-12 | v0.1 phase-2 二次 review 定案：组件总表新增 #33 Spring gRPC（服务端用官方组件不自写 Server）、新增 §4.7 与 grpc-java 版本线关系 |
| 1.9 | 2026-10-12 | v0.1 phase-2 设计 review 定案：组件总表新增 #32 MapStruct（层间 mapper，ADR-015）、新增 §4.6 选型理由与实施期验证点 |
| 1.8 | 2026-10-12 | 脚手架实测修正：§2.2 与 #6 的 `protoSourceRoot`（xolstice 旧参数）改为 ascopes 实际参数 `sourceDirectories`（误用会被静默忽略致生成失败） |
| 1.7 | 2026-10-12 | code-review 修复：生成矩阵 projection.v1 归 artemis-common（v0.3 all-in-one classpath 去重）；protobuf 宿主窗口改双侧 3.25.x–4.x（gencode V 仅保证 runtime V/V+1）；§2.2 重复规则句去重 |
| 1.6 | 2026-10-12 | review 修复：§2.2 引言与要点对齐生成矩阵（protoSourceRoot 单 root + includes；同源 artifact 边界） |
| 1.5 | 2026-10-11 | review 定案：proto 生成模型 server/client 分治——§2.2 生成矩阵调整（common.v1 移至 artemis-common 服务端共享，各 core 不再自生成）、§4.2 理由与代价表述更新 |
| 1.4 | 2026-10-10 | review 修复：组件表范围随 monorepo 更正（#4/#5/#12/#20/#23–26/#31 的「全仓/全项目」→「java/ reactor」）；#27 CI 改按路径触发口径（与 .claude/rules/project.md 一致）；§2.1 代码块补 text 标注、rust/ 注明 v1.x 才创建；§2.2 开头与 §4.2 去重（仓库布局事实归 §2.1，契约治理归 proto 契约规范） |
| 1.3 | 2026-10-10 | 多语言 monorepo 定案：§2.1 改为仓库布局（顶层 `proto/` 契约 + `java/` reactor + `rust/` v1.x），`artemis-proto` Maven 模块（packaging=pom）取消、契约目录提升仓库顶层；proto 引用路径改 `../../proto/<组>`；wire 兼容规则上移至新增的 proto 契约规范（跨语言单一来源） |
| 1.2 | 2026-10-10 | 新增配置管理选型 SCF（#31）：三层解耦、动静分型、RangeValueFilter/TypeConverter 校验（§4.5）；client 依赖纪律与 v0.1 依赖面同步；组件表编号改为稳定 ID 不重排 |
| 1.1 | 2026-10-10 | review 修复：client 依赖清单补 grpc-stub/grpc-protobuf；生成矩阵补 common.v1 与 registry-core 的 projection.v1、Rust 行 protoc 基线更正；`${revision}` 补 flatten-maven-plugin；日志范围改「服务端，client 仅 SLF4J API」；零 Spring 引用改 §1–§2 组件事实；protobuf 兼容口径统一为 runtime ≥ 3.25；proto 插件换 ascopes（xolstice 已 archived）；CI 改 JDK 25 单 job + toolchains JDK 8 fork 测试；artifactId 前缀补例外；client 禁依赖升级为 enforcer 硬约束 |
| 1.0 | 2026-10-10 | 初版：语言/运行时、工程结构与 proto 契约模型、30 项技术组件总表、依赖冲突策略 |
