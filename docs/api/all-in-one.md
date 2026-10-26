# all-in-one 单进程部署

版本: 2.0    更新时间: 2026-10-26

registry + management + discovery 的单进程完整组合形态（v0.3 phase-9 双服务、v0.4 phase-7 扩三服务）：同一 JVM 起三个独立 Spring 上下文，各自端口、彼此走标准网络协议（ADR-001：不做进程内直连优化）。面向单机/评估/开发场景「单包起」，治理链（规则流 + 逻辑实例流 + 事实查询）在组合内默认闭合；生产集群部署仍用各单服务包。

## 交付物与启动

`java/server/artemis-dist` 模块产出可执行 fat jar：`artemis-dist-<version>-exec.jar`。

```bash
java -jar artemis-dist-<version>-exec.jar
```

- 单服务入口与各自 `application.properties` 完全不变；all-in-one 是组合入口，不替代单服务部署。
- 命令行参数不转发（端口/上下文隔离要求 launcher 独占注入位，共享 `--spring.*` 键会同时命中多个上下文）；配置出口见下节。

## 端口

| 服务 | gRPC | HTTP | 管理端口 | 其他 |
|---|---|---|---|---|
| registry | 9090 | 8080 | 9091（loopback） | 内部 peer 9093（绑 `127.0.0.1`，被同进程 management/discovery 消费） |
| management | 9094（规则流 + 逻辑实例流） | 8081（Admin/状态 API/console） | 9092（loopback） | — |
| discovery | 9095 | 8090 | 9096（loopback） | 节点目录默认 `127.0.0.1:9095` |

## 组合期配置键（SCF，静态）

source 优先级（同服务端纪律，无 Spring 层）：**系统属性 > 环境变量 > 工作目录 `artemis.properties`**。非法值（非 1..65535 整数）启动 fail-fast，不静默回落默认。

| 键 | 默认 |
|---|---|
| `artemis.dist.registry.grpcPort` | 9090 |
| `artemis.dist.registry.httpPort` | 8080 |
| `artemis.dist.registry.managementPort` | 9091 |
| `artemis.dist.registry.peerPort` | 9093 |
| `artemis.dist.management.grpcPort` | 9094 |
| `artemis.dist.management.httpPort` | 8081 |
| `artemis.dist.management.managementPort` | 9092 |
| `artemis.dist.discovery.grpcPort` | 9095 |
| `artemis.dist.discovery.httpPort` | 8090 |
| `artemis.dist.discovery.managementPort` | 9096 |

组合期之外的业务配置（认证、快照路径、上游重连参数等）走三个上下文各自的 SCF 装配，可共享同一工作目录 `artemis.properties`（`artemis.registry.*`、`artemis.management.*` 与 `artemis.discovery.*` 键各取所需）。**不要**用 `SPRING_*`/`--server.port` 等共享键覆盖端口（会同时命中多个上下文）。

## DB 双轨（management）

- **未配置 `spring.datasource.url`**（默认）：management 内存态运行，单机 eval **零 DB 依赖**（架构 §5.3 双轨）——摘除/逻辑实例重启丢失（内存态既有语义）。
- **配置后**：HikariCP + Flyway 自动装配，migration 脚本按 url 方言取 vendor 目录（MySQL/PostgreSQL）；摘除/账户/审计/逻辑实例持久化，跨重启恢复。
- 配置出口为 **Spring 标准途径**（环境变量 `SPRING_DATASOURCE_URL` 或系统属性 `spring.datasource.url`）：进入三个上下文共享的 Environment，但仅 management 上下文有 DataSource 条件装配（registry/discovery 入口已排除 `DataSourceAutoConfiguration`），无副作用。不引入 `artemis.dist.*` 的 DB 映射键。

## 语义与限制

- **治理链默认接通且固定同进程**（launcher 命令行注入，优先级最高）：discovery 固定订阅同进程 management（规则流 + 逻辑实例流，`artemis.discovery.management.endpoints` 不可经工作目录覆盖）、management 固定查询同进程 registry 内部端口（`artemis.management.registry.endpoint` 同理）。想要无 management 的组合或外置治理面：用各单服务包自行部署（v0.3 的双服务 all-in-one jar 已被三服务完整形态取代，不再单独交付）。
- 上游集群**固定**为同进程 registry；**多集群 union 聚合不在 all-in-one 形态提供**——多集群仍由独立 discovery 部署承载（phase-5 已交付）。
- 节点目录默认指向本 discovery 外部端口（`127.0.0.1:<discoveryGrpcPort>`）：仅宿主未配置 `artemis.discovery.nodes` 时注入，显式配置（系统属性/环境变量/工作目录文件）优先。
- 启动时序 registry → management → discovery（依赖反序）：registry 空表即 ready → management 起（事实查询上游就位）→ discovery 起（上游与规则流端点确定，治理链零等待闭合）。任一后续上下文启动失败按启动反序回滚，不留半启进程。关闭序 discovery → management → registry（SIGTERM 单个 JVM hook，幂等）。
- 三个上下文经 `spring.config.name` 分隔配置（`artemis-all-in-one-registry/management/discovery.properties`，不含端口与互连端点），单服务 jar 的 `application.properties` 不参与 all-in-one 加载；日志配置由 dist 自带 `logback-spring.xml` 确定（classpath 首位）。
- **认证**：registry 外部端口与 management Admin API/console 认证默认开启（默认账户 `artemis/artemis`，经工作目录 `artemis.properties` 各自键改凭证或关闭）；组合内部互连（management→registry 查询、discovery→management 规则流）走 loopback 明文、默认无认证；TLS/内部通道加固归 v1.0。
- 容器镜像/部署脚本归 v1.0 部署形态。

## 更新历史

| 版本 | 日期 | 变更说明 |
| ------ | ------ | ------ |
| 2.0 | 2026-10-26 | v0.4 phase-7：扩三服务完整形态（+management 上下文、组合键 +3、治理链默认闭合、DB 双轨、凭证与限制更新） |
| 1.0 | 2026-10-24 | v0.3 phase-9 初版：入口、端口表、`artemis.dist.*` 组合配置键、语义与限制 |
