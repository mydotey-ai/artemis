# Phase 8：Spring Boot starter + CI + Dockerfile

版本: 1.1    更新时间: 2026-10-19

## 目标

交付注册侧 Spring Boot starter 自动装配、CI 流水线与 registry-server 镜像构建，闭合 v0.1 工程项。

## 范围

做什么：

- `spring-boot-starter-artemis-client` 注册侧自动装配：`AutoConfiguration.imports` + `spring.factories` 双注册、`artemis.client.*` 配置绑定、client 生命周期（start/close）托管、`MeterRegistry` 自动注入、bean 条件与关闭顺序。
- GitHub Actions CI：按路径触发（`java/**` Java job、`proto/**` 全量）、JDK 25 单构建 job + toolchains JDK 8 fork client 测试、`spotless:check` 强制。
- `registry-server` Dockerfile 镜像构建。

不做什么：

- 不实现 v0.2+ 发现侧装配、集群能力。
- 不发布 Maven Central / 镜像仓库（v1.0）。

## 涉及模块

`java/client/spring-boot-starter-artemis-client`、`java/server/artemis-registry-server`、仓库根 CI 配置与 Dockerfile。

## 验收方式

对应测试文档：[phase-8-starter-ci-test.md](phase-8-starter-ci-test.md)

starter 自动装配测试；CI 绿；镜像构建成功。

## 产品设计（③ 补）

### starter 自动装配

```java
// org.mydotey.ai.artemis.client.spring.boot.ArtemisClientAutoConfiguration
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(ArtemisClient.class)
@ConditionalOnProperty(prefix = "artemis.client", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ArtemisClientAutoConfiguration {

    @Bean   // 不设 @ConditionalOnMissingBean：Environment source 是 starter 的配置入口
    public StringProperties artemisClientStringProperties(Environment environment);

    @Bean(initMethod = "start", destroyMethod = "close") @ConditionalOnMissingBean
    public ArtemisClient artemisClient(StringProperties properties, ObjectProvider<MeterRegistry> meterRegistries);
}
```

**行为语义**：

- **配置走 SCF（不引 `@ConfigurationProperties`）**：starter 注册 `SpringEnvironmentConfigurationSource`（`AbstractConfigurationSource` 包一层 `Environment.getProperty`），SCF manager 名为 `artemis-client-starter`。Spring 标准 `Environment` 已包含命令行参数、system properties、环境变量与 `application.properties/YAML`。因 `Environment.getProperty` 只做精确名查找，source 对 camelCase 键补三种写法：SDK 自有的大写蛇形（`ARTEMIS_CLIENT_SERVER_ADDRESSES`）、YAML 惯例 kebab-case（`artemis.client.server-addresses`）、properties 惯例小写蛇形；`ARTEMIS_CLIENT_SERVERADDRESSES` 由 Spring 的 `SystemEnvironmentPropertySource` 原生覆盖。这样 `artemis.client.*` 键仍是 client 的唯一配置来源（键定义在 client 消费类内，starter 不复制）。
- **生命周期**：`initMethod = "start"` 随 Spring 容器启动建连并首跳心跳；`destroyMethod = "close"` 在容器关闭时按 Spring 销毁顺序优雅下线（停心跳 → 注销本地实例 → 释放资源，client 自身幂等）。无需额外 `@DependsOn`：client 仅依赖 `MeterRegistry`/配置 bean，逆序销毁已保证先关 client。
- **条件**：`enabled`（默认 `true`）显式关闭自动装配；宿主自定义 `ArtemisClient` bean 时自动装配整体让位。`StringProperties` **不设覆盖条件**——复用宿主无关的 SCF bean 会静默丢失 `application.properties/YAML` 中的配置，配置入口由 starter 独占。**不按 `serverAddresses` 是否存在做条件**——starter 被显式引入即表达注册意图，缺失必配项在容器启动期 fail-fast（client 现有语义），避免「静默不注册」。
- **Micrometer**：`ObjectProvider.getIfUnique` 取 Boot/actuator 提供的唯一 registry；不存在**或多个同型 bean**时回落 `Metrics.globalRegistry`（client 库模式，宿主不接管则空转；避免多 registry 场景启动失败）。指标集沿用 phase-7 六项，经宿主 actuator 暴露。
- **双注册**：`META-INF/spring.factories`（`EnableAutoConfiguration` 键，覆盖老 SB 2.x）+ `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`（SB 2.7+）。为兼容老 2.x 运行时，自动配置类用 `@Configuration` 而非 SB 2.7 才有的 `@AutoConfiguration`，且只使用 2.x 全系可用的条件注解与 `ObjectProvider` API。

### 编译基线

starter 以 **Spring Boot 2.7.18**（2.x 最后版本，支持 Java 8）为编译基线，依赖版本在 `artemis-client-parent` 依赖管理中自钉，**不 import SB BOM**（SDK 线独立依赖管理纪律，tech-stack §2.3）；`spring-boot-autoconfigure` 为 compile 依赖，`spring-boot-test` 仅 test（用 `ApplicationContextRunner` 驱动装配测试）。surefire 与 artemis-client 一样 fork JDK 8 验证运行期兼容。

### CI

`.github/workflows/ci.yml`，按路径触发（`java/**`、`proto/**`、`.github/workflows/**`；后续语言 job 按同一模式追加）：

| job | 内容 |
|---|---|
| `java` | checkout → setup-java 8（取路径写 `~/.m2/toolchains.xml`）→ setup-java 25（默认 JVM + Maven 缓存）→ `mvn -B -f java/pom.xml verify`（含 spotless:check、enforcer、单测、failsafe IT） |
| `docker` | checkout → `docker build`（与 `java` 并行，互不依赖） |

JDK 25 单构建 job + toolchains JDK 8 fork 测试 JVM，与 tech-stack #27 一致；不设 JDK 8 独立构建 job。

### Dockerfile

仓库根多阶段 `Dockerfile`，镜像只含 `artemis-registry-server` 单服务（management 随 v0.4 独立编排，v0.1 无 all-in-one 产物）：

```dockerfile
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /src
COPY java ./java
COPY proto ./proto
RUN mvn -B -f java/pom.xml -pl :artemis-registry-server -am package -DskipTests

FROM eclipse-temurin:25-jre
# 非 root 运行；COPY --from=build .../*-exec.jar /app/app.jar
EXPOSE 8080 9090
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
```

自包含构建（`docker build .` 一条命令即可，不依赖宿主预构建）；builder 内 `-am` 只构建 registry-server 及其依赖模块，`-DskipTests` 跳过测试（CI 的 `java` job 已跑全量）。actuator 端口 9091 默认绑 `127.0.0.1`（`application.properties` 现状），不对外 EXPOSE。

### 设计决策（对照基线 §5/§6 与架构）

- **补齐原产品局限 #14（无 starter/自动装配）**：starter 是 v0.1 接入体验的组成；不做配置项二次建模（SCF 单一来源）。
- **SB 2.7.18 基线 + 双注册**：client 线 `release=8` 只能运行在 SB 2.x（SB 3+ 需 Java 17）；2.7+ 用 imports、老 2.x 用 factories。否决为兼容新 SB 另起 3.x 编译分支（v0.1 无需要，双份构建增加矩阵）。
- **缺失地址 fail-fast 而非条件跳过**：starter 是显式依赖，静默禁用会让注册「看起来成功但从未发生」；错误信息由 client 给出完整键名。`enabled=false` 是唯一关闭方式。
- **Docker 多阶段自包含**：否决「宿主先 `mvn package` 再 `docker build`」的两步式（CI 需跨步骤传产物、eval 易漏步骤）；构建时长由 CI 缓存与 v1.0 镜像优化承接，本 phase 只要求构建成功。
- **文档同步落点**：tech-stack-java.md 组件总表新增 SB 2.x starter 基线行（§2.3 依赖纪律不变）；coding-guidelines-java.md §9 补 starter 基线/条件语义；产品文档 `docs/product/artemis.md`（⑩）。架构文档与 ADR 不变（部署形态、协议、数据模型均未变）。

## 实现结果（⑦ 补）

### 实现要点

- starter：`ArtemisClientAutoConfiguration`（SCF Environment source + 生命周期托管 + MeterRegistry `getIfUnique` 回落）、`SpringEnvironmentConfigurationSource`（camelCase 键的四写法归一）、双注册资源。
- 构建纪律：`jdkToolchain=8` 与 SDK 边界 enforcer（禁 common/core/server）统一上移 `artemis-client-parent`，client 与 starter 共同继承；starter 自动装配测试 8 例。
- Dockerfile 多阶段（`maven:3.9-eclipse-temurin-25` → `eclipse-temurin:25-jre`，非 root + 可写 `data/` 快照目录）；CI 两 job（java / docker）。

### code-review 修复记录（⑥ max → ⑦）

| # | finding | 处理 |
|---|---|---|
| 1 | 容器内 `/app` 归 root，非 root 用户无法写默认快照路径 | 镜像内 `mkdir -p /app/data && chown -R artemis:artemis /app` |
| 2 | starter（Spring 映射）与裸 SDK（camel 边界蛇形）环境变量写法不一致 | source 补 `ARTEMIS_CLIENT_SERVER_ADDRESSES` 写法，两入口可用同一键 |
| 3 | `@ConditionalOnMissingBean` 复用宿主无关 `StringProperties` 会丢 Spring 配置源 | 去掉该条件，配置入口由 starter 独占；覆盖出口收窄为自定义 `ArtemisClient` |
| 4 | 设计文档承诺的 `ConfigurationManager` bean 未实现 | 按 #3 收敛设计：只保留 `ArtemisClient` 覆盖，文档同步修订 |
| 5 | `Environment.getProperty` 精确查找，kebab/snake 写法不可见 | source 补 kebab 与小写蛇形变体（含单测） |
| 6 | 多 `MeterRegistry` bean 时 `getIfAvailable` 抛异常而非回落 | 改 `getIfUnique(() -> Metrics.globalRegistry)` |
| 7 | CI 未装 Maven ≥3.9，runner 预装版本不定 | 增加 `stCarolas/setup-maven@v5`（3.9.11） |
| 8 | CI 路径过滤漏 Dockerfile/.dockerignore | 两条 paths 补齐 |
| 9 | starter 无 SDK 边界 enforcer | 边界规则上移 client parent，两模块共同强制 |
| 10 | docker job 只 build 不起容器 | 接受：本 phase 验收口径为「镜像构建成功」；运行时冒烟归 v1.0 部署形态验收（/app/data 权限问题已按 #1 根因修复） |
| 11 | 测试 `@AfterEach` 清空全局 Micrometer registry | 删除；client close 已摘除自身 gauge，无残留 |
| 12 | 注入 registry 用例未断言 global 未被写 | 补断言 |
| 13 | Docker 构建无依赖缓存且 skipTests 仍编译测试 | 改 `-Dmaven.test.skip=true`；依赖缓存留给 v1.0 镜像优化 |
| 14 | 基础镜像 mutable tag | 接受：已锁主/次版本线，digest 固定在 v1.0 发布镜像时落实 |
| 15 | CI 重复维护 toolchains.xml、各模块重复 surefire 配置 | CI 从 `java/toolchains.xml.example` sed 生成；surefire `jdkToolchain` 上移 client parent |

## 文档同步（⑩ 填）

- `docs/product/artemis.md`（1.8）：phase-8 交付节（starter 自动装配语义、CI、镜像）与开发者入口镜像构建命令。
- `docs/api/sdk-java.md`（1.1）：starter 坐标、Spring Boot 自动装配用法/条件、配置键写法；修正裸 SDK 环境变量映射名。`docs/api/README.md` 描述同步。
- `docs/dev/tech-stack-java.md`（1.21）：组件总表 #35 Spring Boot 2.x starter 基线。
- `docs/dev/coding-guidelines-java.md`（1.24）：§9 starter 基线、双注册、`@Configuration` 与 SCF 配置纪律。
- `README.md`：移除「尚无代码」过时表述，补文档与构建入口。
- 索引：`docs/milestones/v0.1/phases/README.md` 已在 ① 登记；本 phase 未新增文档，无其它索引变更。
- 架构文档与 ADR：无变更（部署形态、协议、数据模型未变；starter/CI/镜像属工程实现）。

## 更新历史

| 版本 | 日期 | 变更说明 |
| ------ | ------ | ------ |
| 1.3 | 2026-10-19 | ⑩ 文档同步清单 |
| 1.2 | 2026-10-19 | ⑦ 实现结果与 code-review 修复记录；装配契约按 review 收敛（仅 `ArtemisClient` 可覆盖、配置键多写法归一） |
| 1.1 | 2026-10-19 | ③ 设计定稿：starter 装配契约、SB 2.7.18 基线、CI 与多阶段 Dockerfile |
| 1.0 | 2026-10-19 | 开工骨架 |
