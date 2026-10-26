# Phase 7：all-in-one 完整形态与版本收尾

版本: 1.2    更新时间: 2026-10-26

## 目标

`artemis-dist` 扩为三服务（registry + discovery + management）同 JVM 组合形态，并收口 roadmap v0.4 验收要点的端到端场景。

## 范围

做什么：

- `artemis-dist` 并入第三个 Spring 上下文（management）：新增组合配置文件、组合端口键 +3、治理链内部互连注入（discovery → management 规则流、management → registry 事实查询），启动/关闭时序扩展为三上下文。
- all-in-one 形态端到端 IT：三服务单进程起停 + roadmap v0.4 四要点场景（摘除跨重启恢复、规则生效水位可见、逻辑实例可发现、console 可用）。
- 版本收尾的材料准备（验收对照表在版本收尾环节 3 填写，本 phase 提供端到端测试证据）。

不做什么：

- 不改三个单服务入口与单服务 `application.properties`（`artemis-dist` 只是组合入口；单服务部署形态完全不变）。
- 不做进程内直连优化（ADR-001：三上下文彼此仍走网络协议）。
- 不做容器镜像/安装包/systemd（v1.0 部署形态）；不做 all-in-one 多集群上游（D9-5 延续，多集群 union 由独立 discovery 部署承载）。
- 零 proto 变更、零 SDK 变更、零 console 新能力（phase-6 已交付，本 phase 仅组合）。
- 不重复 phase-1–4 已双方言验证的持久化语义矩阵（all-in-one DB 态端到端只跑 PG 单方言；双方言语义由既有 IT 承载）。

## 涉及模块

| 模块 | 内容 |
|---|---|
| `java/server/artemis-dist` | 主要改动：`ArtemisAllInOneServer`（三上下文启动/回滚/关闭）、`AllInOneOptions`/`AllInOneConfig`（+3 组合端口键）、新增 `artemis-all-in-one-management.properties`、pom 依赖 `artemis-management-server`、`AllInOneIT` 扩展 |
| `java/server/artemis-management-server` | 仅被组合（入口已排除 `DataSourceAutoConfiguration`，v0.4 phase-1 已为组合 classpath 铺路；无代码改动） |
| `java/server/artemis-registry-server`、`java/server/artemis-discovery-server` | 无改动，仅被组合 |
| `proto/` | 无变更 |

## 验收方式

对应测试文档：[phase-7-all-in-one-full-test.md](phase-7-all-in-one-full-test.md)

## 产品设计（③ 补）

### 1. 组合结构

三上下文 = 在 v0.3 双上下文机制（独立 `spring.config.name` + launcher 命令行注入端口，ADR-001 网络互访）上并入 management，机制不变、只扩一元：

```text
main → AllInOneConfig(SCF artemis.dist.*) → start(options)
  1. 起 registry 上下文（spring.config.name=artemis-all-in-one-registry，不变）
  2. 读 registry 内部 peer 端口实际值（PeerServerConfig.port()，RegistryQueryService 驻留端口）
  3. 起 management 上下文（spring.config.name=artemis-all-in-one-management）
     · 注入 artemis.management.registry.endpoint=127.0.0.1:<peer端口>（事实查询上游，loopback）
  4. 读 management gRPC 实际端口
  5. 起 discovery 上下文（既有 + 注入 artemis.discovery.management.endpoints=127.0.0.1:<management gRPC端口>）
  6. 返回 Node；单个 JVM shutdown hook，关闭序 discovery → management → registry（幂等）
```

- **启动序 registry → management → discovery**（依赖反序）：registry 空表即 ready 不等下游；management 起后治理面就绪，discovery 最后起、启动即能订阅规则流与逻辑实例流（虽 fail-open 自愈，先起 management 让治理链零等待收敛）；discovery 上游 registry 与管理面互连均在启动期确定。
- **失败回滚**：任一后续上下文启动失败，按已起反序关闭（discovery 失败 → 关 management、registry；management 失败 → 关 registry），异常传播、回滚关闭异常 suppressed 附加（既有模式）。
- **关闭序 discovery → management → registry**（依赖反序，v0.3「消费方先停」原则扩展）：discovery 先停（断开规则流/上游订阅），management 次之（停规则源与 registry 查询），registry 最后（排水 + 终刷快照既有语义不受影响）。
- 互连注入均为命令行参数（优先级高于工作目录 `artemis.properties` 同名键，同 D9-5「固定同进程上游」语义）：all-in-one 完整形态中治理链**默认接通**——discovery 固定订阅同进程 management、management 固定查询同进程 registry，不做条件开关（想要无 management 的组合形态即 v0.3 交付的双服务形态，单服务 jar 亦各自独立存在）。

### 2. 端口与组合配置键

| 服务 | gRPC | HTTP | 管理端口 | 内部/其他 |
|---|---|---|---|---|
| registry | 9090 | 8080 | 9091 | peer 9093（绑 127.0.0.1） |
| management | 9094（规则流 + 逻辑实例流） | 8081（Admin/状态/console） | 9092 | — |
| discovery | 9095 | 8090 | 9096 | 节点目录默认 127.0.0.1:9095 |

新增组合键（SCF 静态、1..65535、非法 fail-fast，`AllInOneConfig` 单次读出）：

| 键 | 默认 |
|---|---|
| `artemis.dist.management.grpcPort` | 9094 |
| `artemis.dist.management.httpPort` | 8081 |
| `artemis.dist.management.managementPort` | 9092 |

- management gRPC 端口单服务默认 `-1`（不监听，management 独立运行语义）；all-in-one 必须监听（治理链闭环前提），组合键默认 9094，`0` = 随机（仅测试/嵌入，`AllInOneOptions` 直构路径；组合键经 SCF 读出限 1..65535 与既有 7 键一致）。
- 默认值与三个单服务各自默认一致错开（8081/9092/9094 vs 8080/9091/9093 vs 8090/9096/9095），单机默认起完整形态零冲突。

### 3. 配置文件与 DB 双轨

- 新增 `artemis-all-in-one-management.properties`：management 单服务 `application.properties` 的组合版——保留 actuator 暴露/trace 与 OTLP 关闭/`spring.flyway.locations`（DB 态需要），**不含**端口与 `artemis.management.registry.endpoint`（launcher 注入位）。三份组合配置文件互不相交，两个 server jar 同名 `application.properties` 不参与加载（既有隔离机制）。
- **DB 双轨保持（架构 §2.3 / §5.3）**：all-in-one 未配置 `spring.datasource.url` 时 management 内存态运行（单机 eval 零 DB 依赖）；配置后 HikariCP + Flyway 双方言接管（`{vendor}` 目录）。`spring.datasource.*` 属 Spring 框架键、经 Spring 标准途径（环境变量/系统属性）进入三个上下文的 Environment——仅 management 上下文有 DataSource 条件装配（registry/discovery 入口已排除 `DataSourceAutoConfiguration`），共享 Environment 属性面无副作用；工作目录 `artemis.properties` 是 SCF 业务配置源、不承载 `spring.datasource.*`。all-in-one 不引入 `artemis.dist.*` 的 DB 键（不做二次映射，Spring 键直用）。
- 内部互连认证默认关闭（`artemis.management.grpc.auth.*` / `artemis.discovery.management.auth.*` 默认 false，三份组合配置文件保持默认）：单进程内 loopback 互访；外部端口的认证语义由各单服务配置承载（all-in-one 用户按需经工作目录 `artemis.properties` 配置，键各取所需）。TLS/内部通道加固归 v1.0。

### 4. Node 句柄扩展

```java
public static final class Node implements AutoCloseable {
    ConfigurableApplicationContext registry();
    ConfigurableApplicationContext discovery();
    ConfigurableApplicationContext management();          // 新增
    int registryGrpcPort();  int registryHttpPort();  int registryPeerPort();
    int managementGrpcPort(); int managementHttpPort();   // 新增
    int discoveryGrpcPort(); int discoveryHttpPort();
    @Override void close();   // 关闭序 discovery → management → registry
}
```

读实际端口复用现有口径（`GrpcServerLifecycle` 排除 peer bean 取主端口；HTTP 经 `WebServerApplicationContext`）；主 gRPC 端口读取从 Node 私有方法提为启动期可复用（discovery 注入 management 规则流端点需在 Node 构造前拿到）；management 的 registry 查询端点用内部 peer 端口（`RegistryQueryService` 驻留位置，与 v0.2 管理查询走内部端口的设计一致）。

### 5. 语义定案与原始文档同步

| # | 定案 | 原始文档同步落点 |
|---|---|---|
| D7-1 | all-in-one 升为三服务同 JVM 组合（registry → management → discovery，启动/关闭依赖反序），机制沿用 v0.3 双上下文（ADR-001 网络互访、独立 `spring.config.name`、launcher 注入） | ⑩：`docs/api/all-in-one.md`（形态、时序、端口表）；`docs/dev/tech-stack-java.md` §2.1 dist 行（两上下文 → 三服务）；`docs/product/artemis.md`。ADR-001 已覆盖组合机制本身，不新增 ADR |
| D7-2 | 治理链在组合内默认接通且固定同进程：discovery 固定订阅 management（规则流 + 逻辑实例流）、management 固定查询 registry 主 gRPC；均命令行注入（优先于工作目录同名键，D9-5 同语义），不做条件开关 | ⑩：`docs/api/all-in-one.md`（治理链小节 + 限制节：无 management 组合用 v0.3 双服务形态已不可得——完整形态取代双服务 jar，此为产品演进，登记到 all-in-one 文档交付说明） |
| D7-3 | 组合端口键 +3（`artemis.dist.management.*`，默认 9094/8081/9092 与单服务默认一致）；management gRPC 组合必监听 | ⑩：`docs/api/all-in-one.md` 端口表与配置键 |
| D7-4 | DB 双轨保持：`spring.datasource.*` 经 Spring 标准途径、仅 management 上下文消费；不引入 `artemis.dist.*` DB 映射键 | ⑩：`docs/api/all-in-one.md` DB 小节（eval 零 DB、配置后双方言）；`docs/product/artemis.md` 交付说明 |
| D7-5 | all-in-one DB 态端到端只跑 PG 单方言（Testcontainers + Docker 守卫）；双方言语义由 phase-1–4 既有 IT 承载，不在组合场景重复矩阵 | ⑩：测试文档记录；版本收尾验收对照表引用 |
| D7-6 | 测试形态不新增（`*IT` failsafe 既有映射，Testcontainers 既有用途）；`dev-test.sh` 无需改 | ⑩：测试文档记录 |

### 6. 不做的事

- 不把三个 server 合并成一个 Spring 上下文；不做进程内直连；不做全部上下文共享配置文件。
- 不做 all-in-one 形态的治理开/关开关（完整形态即治理链闭合形态，D7-2）。
- 不做 DB 组合期端口/URL 的 `artemis.dist.*` 键（Spring 键直用，D7-4）。

## 实现结果（⑦ 补）

### 实现要点

- **launcher**（`ArtemisAllInOneServer`）：三上下文启动序 registry → management → discovery，`started` 列表统一回滚（启动反序 close、suppressed 附加）；management 上下文 gRPC 监听双键注入（`spring.grpc.server.port` 实际监听位 + `artemis.management.grpc.port` 条件装配开关）+ `artemis.management.registry.endpoint=127.0.0.1:<peerPort>`（`RegistryQueryService` 驻留 registry 内部端口，v0.2 管理查询走内部端口设计一致）；discovery 上下文追加 `artemis.discovery.management.endpoints=127.0.0.1:<managementGrpcPort>`（治理链固定闭合）；`grpcPort()` 从 Node 私有提为包级静态（discovery 注入需在 Node 构造前读 management 实际端口）；Node 扩 `management()`/`managementGrpcPort()`/`managementHttpPort()`，关闭序 discovery → management → registry。
- **组合配置**：`AllInOneOptions`/`AllInOneConfig` +3 端口键（默认 9094/8081/9092）；新增 `artemis-all-in-one-management.properties`（actuator loopback、规则流停机 grace 1s、`spring.flyway.locations={vendor}`、OTLP/OTel 关闭；不含端口与 registry 端点）。
- **依赖**：dist + `artemis-management-server`（compile）+ `testcontainers-junit-jupiter`/`testcontainers-postgresql`（test，版本随 SB BOM）。
- **测试**：`AllInOneIT` 扩 6 用例（三上下文起停、注册发现回归、治理链闭环+水位、逻辑实例 union、console）；`AllInOnePersistenceIT`（DB 态独立类，类级 `disabledWithoutDocker` 只守卫 DB 态）跨组合重启恢复场景——固定 peer/managementGrpc 端口 + 同 DB + 同快照路径两个 Node 顺序起停。

### code-review（⑥ max 级，内联执行——环境无 Skill 通道，按工作流兜底条款）findings 处置

| # | severity | finding | 处置 |
|---|---|---|---|
| 1 | medium | union 断言只 `contains("i1")` 未排他——li-1 泄漏进真实 cluster 用例仍绿 | 修复：`equals(List.of("i1"))`（互不渗透正交断言） |
| 2 | low | `register` 双重载，static 版仅被转发调用 | 修复：合并单份 |
| 3 | low | `json()` 返回 `JsonObject` 后冗余 `.getAsJsonObject()` | 修复：删冗余 |
| 4 | low | DB 态用例 close→start 固定端口复用 TCP 释放竞态 | 不改：与既有 `DiscoveryLogicalInstanceIT.startManagement` 同模式，基线风险持平，实测全绿；CI 偶发再议 |
| 5 | low | all-in-one 文档缺默认凭证与治理链不可覆盖限制说明 | ⑩ 文档同步落入（见「文档同步」节） |

并发/资源释放审查无 findings（launcher 无共享可变状态、Node final 字段 + 幂等 close、回滚 suppressed 链完整）。

## 文档同步（⑩ 填）

| 文档 | 变更 |
|---|---|
| `docs/product/artemis.md`（1.31） | v0.4 phase-7 交付段（组合结构/治理链闭合/DB 双轨/四要点端到端）；模块表 `artemis-dist` 行更三上下文 |
| `docs/api/all-in-one.md`（2.0） | 三服务完整形态重写：端口表 +3、组合键 +3、治理链默认闭合与不可覆盖限制、DB 双轨（Spring 标准途径/仅 management 消费）、默认凭证、启动/关闭时序；「management 归 v0.4」条目兑现 |
| `docs/dev/tech-stack-java.md`（1.28） | §2.1 模块树 `artemis-dist` 描述更新（三 Spring 上下文；test 依赖随 SB BOM，组件总表无需新增行） |
| CLAUDE.md | 无需更新（构建命令、模块结构、当前状态粒度未变） |
| ADR | 无新增（组合机制由 ADR-001 覆盖，D7-1 同机制扩元） |

review 定案同步落点核对：D7-1/2/3/4 → `docs/api/all-in-one.md` + `docs/product/artemis.md` + 技术选型 §2.1（均已落）；D7-5/6 → 测试文档（已落）。

## 更新历史

| 版本 | 日期 | 变更说明 |
| ------ | ------ | ------ |
| 1.2 | 2026-10-26 | ⑦ 实现结果与 findings 处置、⑩ 文档同步清单（3 文档） |
| 1.1 | 2026-10-26 | ③ 设计定稿：三上下文组合（D7-1–D7-6）、端口/配置/DB 双轨、端到端测试方案 |
| 1.0 | 2026-10-26 | 开工骨架 |
