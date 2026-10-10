# Java 技术选型

版本: 1.2    更新时间: 2026-10-10

> 本文是新一代 Artemis Java 实现的技术选型基线：语言与运行时、工程结构、全量技术组件清单与依赖冲突策略。设计依据为[架构设计](../arch/artemis-next-architecture.md)（§3 技术栈策略、§7 关键决策 D8），开发规范见[Java 开发规范](coding-guidelines-java.md)。Rust 实现（v1.x 混合集群）直接消费 `artemis-proto/` 契约目录，不受本文 Java 组件约束。

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

### 2.1 模块划分

Maven 多模块单 reactor，groupId 统一 `org.mydotey.ai.artemis`，artifactId 前缀 `artemis-`（两个例外：parent 为 `artemis`；starter 为 `spring-boot-starter-artemis-client`，遵循 Spring starter 命名惯例）：

```
org.mydotey.ai.artemis:artemis (parent)
├── artemis-proto                纯契约目录 (packaging=pom)，只放 .proto 文件，不生成代码
│     common/ registry/ replication/ projection/ discovery/ management/   (各 v1)
├── artemis-common               服务端公共规范/工具 (Java 25)
├── artemis-registry-core        registry 内核，纯 Java 零 Spring (Java 25)
├── artemis-registry-server      Spring Boot 装配 (Java 25)
├── artemis-discovery-core       discovery 投影内核 (Java 25)
├── artemis-discovery-server     Spring Boot 装配 (Java 25)
├── artemis-management-core      治理面内核 (Java 25)
├── artemis-management-server    Spring Boot 装配 (Java 25)
├── artemis-client               注册 + 发现统一 SDK，零 Spring (Java 8)
├── spring-boot-starter-artemis-client   Spring 集成 (Java 8)
└── (artemis-dist 打包模块 v0.3 起引入，不影响边界)
```

### 2.2 proto 契约模型

`artemis-proto` 是**纯契约目录**：Maven 模块 `packaging=pom` 只管版本，不含 Java 产物。各消费模块引用需要的 .proto 文件，各自生成代码到自己的包：

| 模块 | 生成的 proto 组 | protoc 基线 | 编译版本 |
|---|---|---|---|
| artemis-registry-core | common.v1 + registry.v1 + replication.v1 + **projection.v1** | 4.x | Java 25 |
| artemis-discovery-core | common.v1 + discovery.v1 + projection.v1 | 4.x | Java 25 |
| artemis-management-core | common.v1 + management.v1 + registry.v1（读注册表视图） | 4.x | Java 25 |
| artemis-client | common.v1 + registry.v1 + discovery.v1 | **3.25.x** | Java 8 |
| Rust registry（v1.x） | common.v1 + registry.v1 + replication.v1 + projection.v1 | prost/tonic 构建锁定（v1.x 定基线） | — |

**每个 Java 消费模块都生成 common.v1**：被各组 import 的公共消息随该模块的生成范围一起产出（模块间不共享生成类，见下）。registry-core 含 projection.v1 是因为 registry 是分发流（registry → discovery 订阅 + 快照 bootstrap）的**服务端**。

要点：

- **公共数据模型消息**（Instance、ServiceInstances 等）定义在 `artemis-proto/common/v1/`，各组 proto import 之；import 方向永远「内部协议 → 共享」，不出现环。
- **客户端用 protoc 3.25.x 生成、服务端用 4.x 生成**：3.25 生成代码在 protobuf runtime ≥ 3.25（含 4.x）的宿主上均可运行（protobuf 官方政策：runtime 版本须 ≥ gencode 版本，老 runtime 不在支持范围），这是「各自生成」模型的直接红利——共享 artifact 方案无法两端分叉 protoc 版本。
- 同一 proto 组会在多个模块生成同 FQCN 的类（如 registry.v1 在 core 与 client 各一份）。**跨模块只走 wire 协议，绝不在模块边界传递 proto 消息类**（纪律见开发规范 §1）。
- 插件对 JDK 25 + protoc 4.x/3.25 + grpc-java 组合的支持、跨模块 proto import 与 `protoSourceRoot` 相对路径（`../artemis-proto/<组>`）的具体配置，均在脚手架阶段一并验证；跨仓库复用需求出现时再升级为 proto-sources 依赖机制。

### 2.3 依赖硬约束

| 约束 | 执行机制 |
|---|---|
| core 模块零 Spring（延续原产品实践，能力基线 §1–§2 组件事实） | maven-enforcer `bannedDependencies` 禁 `org.springframework:*`，CI 强制 |
| client 不依赖任何服务端模块 | 同样用 enforcer `bannedDependencies` 硬约束：禁 `org.mydotey.ai.artemis` 下 `artemis-common` 与各 `*-core`/`*-server` artifact；proto 契约经文件级引用（`artemis-proto` 为 packaging=pom，本就无 jar 可依赖） |
| 全 reactor 统一版本 | parent `${revision}` 占位符 + **flatten-maven-plugin**（Maven 3.x CI Friendly Versions 必须：无 flatten 时 install/deploy 产物 pom 残留字面量 `${revision}`，外部不可消费） |

## 3. 技术组件总表

版本列为基线版本线，精确 patch 版本在搭建脚手架时锁定最新稳定。**# 为稳定编号**：不随插入重排，新增条目顺取下一号（表按类别归位插入）。

| # | 类别 | 组件 | 选型与版本基线 | 用途 / 作用范围 | 备注 |
|---|---|---|---|---|---|
| 1 | 语言运行时 | 服务端 JDK | Java 25 LTS（`release=25`） | common、各 core、各 server | 虚拟线程（JEP 491 后无 pinning 顾虑） |
| 2 | 语言运行时 | SDK 编译目标 | Java 8（`release=8`） | artemis-client、starter | 同 reactor 混合编译 |
| 3 | 语言运行时 | GC | 默认 G1 | 服务端 | ZGC 大堆选项留 v1.0 压测后定 |
| 4 | 构建 | 构建系统 | Maven 3.9.x | 全仓单 reactor | Maven 4 成熟后评估 |
| 5 | 构建 | 依赖治理 | maven-enforcer-plugin + flatten-maven-plugin | 全仓 | enforcer：Java/Maven 版本、依赖上界（requireUpperBoundDeps）、core 禁 Spring 与 client 禁服务端模块（§2.3）；flatten：`${revision}` 落库（§2.3） |
| 6 | 构建 | proto 生成 | protobuf-maven-plugin（**ascopes**）+ protoc + grpc-java 插件 | 各消费模块 | xolstice 已于 2025-04 archived，其 README 推荐 ascopes 为后继；`protoSourceRoot` 相对路径，按 §2.2 生成矩阵 |
| 7 | 框架 | 应用框架 | Spring Boot 4.1.x | 仅各 server 模块 | DI、配置绑定、MVC、actuator；不出现在 core/client classpath |
| 8 | 通信 | RPC 框架 | grpc-java 1.7x | 服务端 grpc-netty；client grpc-netty-shaded | 五通道全 gRPC；client shaded 隔离宿主 netty |
| 9 | 通信 | 序列化（服务端） | protobuf-java 4.x | 服务端 proto 消息 + 服务端快照文件格式 | 快照 = proto 二进制 + 版本头，不自研格式 |
| 10 | 通信 | 序列化（client） | protobuf-java **3.25.x**（protoc 3.25 生成） | artemis-client | 宿主 3.25+/4.x runtime 双兼容，见 §5 |
| 11 | HTTP | HTTP 辅助通道 | Spring MVC（SB 内置） | server 模块 | 外部 HTTP 辅助端点 + console REST；不引 WebFlux |
| 12 | 代码简化 | Lombok | 1.18.x | 全项目 | `provided` scope，不传递给 SDK 使用方 |
| 31 | 配置管理 | SCF（Spark Configuration Framework） | `org.mydotey.scf:scf-bom` 1.6.x（scf-core + scf-simple） | 全仓（含 client） | manager / sources / property 三层抽象，与具体配置源解耦；原产品同栈（Maven Central 在库）；Java 8 目标两端通用，传递闭包仅 scf-core + lang-extension + slf4j-api；动静分型与合规校验规范见[开发规范](coding-guidelines-java.md) §10 |
| 13 | 数据（期 3） | ORM | MyBatis-Plus 3.5.x + SB starter | 仅 management | 版本锁 SB 4.1 适配线，期 3 实施时锁定 |
| 14 | 数据（期 3） | Schema migration | Flyway（SB 管版本） | 仅 management | MySQL/PG 双方言脚本 |
| 15 | 数据（期 3） | 连接池 | HikariCP（SB 自带） | 仅 management | — |
| 16 | 数据（期 3） | 数据库 | MySQL 8.x / PostgreSQL 16+ | 运维侧二选一 | 双方言为产品承诺 |
| 17 | 可观测 | Metrics | Micrometer + Prometheus registry | 全服务端 | actuator 端点，v0.1 第一天 |
| 18 | 可观测 | Trace | OpenTelemetry（SB 4.1 官方集成） | 全服务端 | v0.1 传播 + 基础 span |
| 19 | 可观测 | 日志 | SLF4J + Logback + logstash-logback-encoder | 服务端；client 仅依赖 SLF4J API | 结构化 JSON 第一天；SDK 不打日志实现进宿主（避免 multiple SLF4J providers 冲突） |
| 20 | 测试 | 单元测试 | JUnit 5 + Mockito + AssertJ | 全仓 | — |
| 21 | 测试 | 集成测试 | Testcontainers | DB（期 3）、K8s 验收（v1.0） | 集群语义测试用进程内多实例（随机端口），不起容器 |
| 22 | 测试 | 压测工具 | 待定（v1.0 容量验证时选型） | — | v0.x 不引入 |
| 23 | 质量 | 格式化 | Spotless + Palantir java-format | 全仓 | CI 强制 `spotless:check` |
| 24 | 质量 | 静态检查 | Checkstyle | 全仓 | 命名/结构规则 |
| 25 | 质量 | Bug 模式 | Error Prone | 全仓 | 经 compiler `annotationProcessorPaths` |
| 26 | 质量 | 覆盖率 | JaCoCo | 全仓 | 出报告，不设强制门槛 |
| 27 | CI/CD | CI | GitHub Actions | 全仓 | PR 全量检查；**JDK 25 单构建 job**（工具/插件进程全跑 JDK 25），client 的 Java 8 运行时兼容经 **Maven toolchains 以 JDK 8 fork 测试 JVM** 验证——不设 JDK 8 独立构建 job（`release=25` 模块在其上无法构建） |
| 28 | CI/CD | 发布渠道 | Maven Central（Central Portal）+ 容器镜像仓库 | SDK/starter（v1.0）、镜像 | 镜像仓库 GHCR vs Docker Hub 待 v1.0 定 |
| 29 | SDK 分发 | starter 自动装配 | `AutoConfiguration.imports` + `spring.factories` 双注册 | starter | 覆盖 SB 2.7+ 与老 SB 2.x；编译目标 Java 8 |
| 30 | SDK 分发 | client 依赖纪律 | grpc-netty-shaded + grpc-stub + grpc-protobuf（传递 protobuf-java 3.25.x）+ SCF（scf-simple，传递 scf-core/lang-extension）+ SLF4J API | artemis-client | grpc-netty-shaded **不传递** stub/protobuf 集成类，生成代码必需 grpc-stub/grpc-protobuf；冲突策略见 §5 |

生效期暗含在类别中：#13–16 期 3（v0.4）引入；v0.1 实际依赖面为语言、构建、SB、gRPC、Lombok、SCF、可观测、测试与质量工具。

测试与质量工具（#20、#23–26）进程统一运行于 JDK 25——client 的 `release=8` 是 javac 交叉编译目标，与工具进程 JVM 无关（Mockito 5.x / Checkstyle 10.x / Palantir format 现行版均需 Java 11+，不受影响）；client 的 Java 8 兼容性在测试 JVM 层验证（toolchains，#27）。

## 4. 关键选型理由

### 4.1 「内核零 Spring」的分模块强制

原产品内核零 Spring 是组件层既成事实（能力基线 §1–§2：artemis-common 为零 Spring 基础库；非 §5 资产清单条目，此处延续实践而非引用资产编号）。本设计将 core 与 server 拆为独立模块，用 enforcer `bannedDependencies` 硬约束而非包约定——依赖检查在构建期失败，比 review/ArchUnit 可靠。该边界同时为 v1.x Rust 对照实现划出清晰内核范围。

### 4.2 proto 纯契约目录（不生成共享 artifact）

契约定义为**文件目录而非 Java artifact**：任何语言（Java/Rust/Go）直接从 .proto 文件生成，各端编译版本与 protoc 版本自由（client Java 8 + protoc 3.25，服务端 Java 25 + protoc 4.x）。代价是同一 proto 组多端各生成一份同 FQCN 类，以「跨模块只走 wire」纪律消解。多语言路线（Rust 混合集群、Go/Rust SDK）下这是最干净的模型，etcd 等项目同路线。

### 4.3 client 用 grpc-netty-shaded

shaded 版将 netty 重打包进 `io.grpc.netty.shaded.*` 命名空间，与宿主自带 netty（任意版本）类名隔离，零冲突——Java SDK 分发主流做法。

### 4.4 服务端快照 = proto 二进制 + 版本头

复用 proto 契约做快照序列化，不自研文件格式；版本头保证跨版本回放兼容（v1.0 滚动升级验收依赖）。

### 4.5 配置管理：SCF 三层解耦

- **分层**：`ConfigurationManager` 管理配置源集合（`ConfigurationSource` 按优先级叠加，如系统属性 > 环境变量 > 配置文件），业务代码只依赖 `Property` 抽象——增删换源不改业务代码。
- **动静分型在 property 层落地**：动态配置用 SCF `Property` 原生动态语义（`getValue` 每次取最新 + 变更监听）；静态配置启动取值后固化（规范见开发规范 §10）。
- **合规校验用 SCF 自带能力**：`RangeValueFilter`（值域）+ `TypeConverter`（类型），配置错误在启动期暴露。
- **不用 Spring `@ConfigurationProperties`**：core/client 零 Spring，它进不了内核与 SDK；且其为启动期静态绑定，动态配置支持弱。SCF 为原产品同栈（`org.mydotey.scf` 已发布 Maven Central），Java 8 目标，服务端（Java 25）与 client（Java 8）两端通用。

## 5. 依赖冲突策略（SDK 对宿主的影响）

| 组件 | 对宿主影响 | 结论 |
|---|---|---|
| netty | grpc-netty-shaded 命名空间隔离 | **零影响**（任意宿主 netty 版本） |
| protobuf | client 用 3.25 生成代码，宿主 runtime ≥ 3.25（含 4.x）均可运行（protobuf 政策：runtime 须 ≥ gencode） | 宿主 protobuf < 3.25 属不支持场景，文档声明 |
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
| 1.2 | 2026-10-10 | 新增配置管理选型 SCF（#31）：三层解耦、动静分型、RangeValueFilter/TypeConverter 校验（§4.5）；client 依赖纪律与 v0.1 依赖面同步；组件表编号改为稳定 ID 不重排 |
| 1.1 | 2026-10-10 | review 修复：client 依赖清单补 grpc-stub/grpc-protobuf；生成矩阵补 common.v1 与 registry-core 的 projection.v1、Rust 行 protoc 基线更正；`${revision}` 补 flatten-maven-plugin；日志范围改「服务端，client 仅 SLF4J API」；零 Spring 引用改 §1–§2 组件事实；protobuf 兼容口径统一为 runtime ≥ 3.25；proto 插件换 ascopes（xolstice 已 archived）；CI 改 JDK 25 单 job + toolchains JDK 8 fork 测试；artifactId 前缀补例外；client 禁依赖升级为 enforcer 硬约束 |
| 1.0 | 2026-10-10 | 初版：语言/运行时、工程结构与 proto 契约模型、30 项技术组件总表、依赖冲突策略 |
