# all-in-one 单进程部署

版本: 1.0    更新时间: 2026-10-24

registry 与 discovery 的单进程组合形态（v0.3 phase-9）：同一 JVM 起两个独立 Spring 上下文，各自端口、彼此走标准网络协议（ADR-001：不做进程内直连优化）。面向单机/评估/开发场景「单包起」；生产集群部署仍用各单服务包。

## 交付物与启动

`java/server/artemis-dist` 模块产出可执行 fat jar：`artemis-dist-<version>-exec.jar`。

```bash
java -jar artemis-dist-<version>-exec.jar
```

- 单服务入口与各自 `application.properties` 完全不变；all-in-one 是**新增**组合入口，不替代单服务部署。
- 命令行参数不转发（端口/上下文隔离要求 launcher 独占注入位，共享 `--spring.*` 键会同时命中两个上下文）；配置出口见下节。

## 端口

| 服务 | gRPC | HTTP | 管理端口 | 其他 |
|---|---|---|---|---|
| registry | 9090 | 8080 | 9091（loopback） | 内部 peer 9093（绑 `127.0.0.1`，只被同进程 discovery 消费） |
| discovery | 9095 | 8090 | 9096（loopback） | 节点目录默认 `127.0.0.1:9095` |

## 组合期配置键（SCF，静态）

source 优先级（同服务端纪律，无 Spring 层）：**系统属性 > 环境变量 > 工作目录 `artemis.properties`**。非法值（非 1..65535 整数）启动 fail-fast，不静默回落默认。

| 键 | 默认 |
|---|---|
| `artemis.dist.registry.grpcPort` | 9090 |
| `artemis.dist.registry.httpPort` | 8080 |
| `artemis.dist.registry.managementPort` | 9091 |
| `artemis.dist.registry.peerPort` | 9093 |
| `artemis.dist.discovery.grpcPort` | 9095 |
| `artemis.dist.discovery.httpPort` | 8090 |
| `artemis.dist.discovery.managementPort` | 9096 |

组合期之外的业务配置（认证、快照路径、上游重连参数等）走两个上下文各自的 SCF 装配，可共享同一工作目录 `artemis.properties`（`artemis.registry.*` 与 `artemis.discovery.*` 键各取所需）。**不要**用 `SPRING_*`/`--server.port` 等共享键覆盖端口（会同时命中两个上下文）。

## 语义与限制

- 上游集群**固定**为同进程 registry（launcher 注入，不可覆盖）；**多集群 union 聚合不在 all-in-one 形态提供**——多集群仍由独立 discovery 部署承载（phase-5 已交付）。
- 节点目录默认指向本 discovery 外部端口（`127.0.0.1:<discoveryGrpcPort>`）：仅宿主未配置 `artemis.discovery.nodes` 时注入，显式配置（系统属性/环境变量/工作目录文件）优先。
- 启动时序：registry 先起（空表即 ready）→ 读实际 peer 端口 → discovery 起（首轮 bootstrap 空表即 SYNCED，冷启动无互相等待）；discovery 启动失败回滚已起的 registry，不留半启进程。关闭序 discovery → registry（SIGTERM 单个 JVM hook，幂等）。
- 两个上下文经 `spring.config.name` 分隔配置（`artemis-all-in-one-registry/discovery.properties`，不含端口与上游集群），单服务 jar 的 `application.properties` 不参与 all-in-one 加载；日志配置由 dist 自带 `logback-spring.xml` 确定（classpath 首位）。
- management 的 all-in-one 完整形态归 v0.4 路线图；容器镜像/部署脚本归 v1.0。

## 更新历史

| 版本 | 日期 | 变更说明 |
| ------ | ------ | ------ |
| 1.0 | 2026-10-24 | v0.3 phase-9 初版：入口、端口表、`artemis.dist.*` 组合配置键、语义与限制 |
