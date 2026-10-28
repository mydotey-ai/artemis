# Phase 3：部署形态 I（Docker + VM）

版本: 1.4    更新时间: 2026-10-28

## 目标

Docker 单机容器（all-in-one 一条命令起全功能，eval 最短路径）+ VM 标准安装包（tar.gz + systemd unit + bin 脚本）。

## 范围

**做**：

- all-in-one Docker 镜像：根目录 `Dockerfile.all-in-one`（多阶段自包含构建，基于 artemis-dist fat jar），CI docker job 补第二个 build。
- VM 标准安装包：部署模板仓内化（`deploy/vm/`）+ 打包脚本 `java/scripts/build-dist.sh`，产出 `artemis-<version>.tar.gz`（bin/app.sh + systemd unit + 外置配置/数据目录）。
- 部署冒烟脚本 `java/scripts/smoke-deploy.sh`（docker / vm 两模式）：起服 + 端口就绪 + **SDK 走通**（single-file Java harness：注册 + 订阅发现）。
- 部署文档：`docs/deploy/` 目录建立（README + docker.md + vm.md），后续 phase-7 文档站汇编复用。

**实现期设计调整（④ 回写）**：① 冒烟发现 all-in-one 缺 registry 集群成员自报——SDK 前置寻址（ADR-020）经 `/cluster/nodes` 取成员 external gRPC 端点，未配 `artemis.registry.cluster.members` 时自报为空、SDK 无法接入（SDK 文档定案「单节点 = 1 成员集群，无特殊分支」）。launcher 补默认单成员注入（`all-in-one@127.0.0.1:<peerPort>@127.0.0.1:<grpcPort>` + self，同 `discovery.nodes` 既有注入模式：显式配置优先）。**零 Java 变更的定案作废**，实际含 dist launcher 一处注入改动（约 20 行）；架构无涉（成员抽象接口行为，架构 §4.1 语义不变），语义落 all-in-one.md v2.3。② `.dockerignore` 增补 `deploy`、`java/scripts`（镜像缓存与脚本/模板迭代解耦）。

**不做**：

- 镜像推送 registry / 签名 / 扫描（CI 只 build 不 push，与现状一致）。
- 单服务 VM 包（VM 形态交付 all-in-one；registry 单服务镜像保留既有根 `Dockerfile` 不动）。
- K8s/Helm（phase-4）；镜像内 HEALTHCHECK（eval 最短路径，探针归 phase-4 Helm）。
- 日志文件输出（dist logback 仅 STDOUT → 容器 stdout / systemd journal，不引入 logs/ 目录与文件 appender）。
- 管理端口对外暴露（`management.server.address=127.0.0.1` 产品默认不变；容器内 `docker exec` 访问，文档说明）。

## 涉及模块

无 Java 源码变更。新增部署制品：根 `Dockerfile.all-in-one`、`deploy/vm/`（模板）、`java/scripts/build-dist.sh`、`java/scripts/smoke-deploy.sh`（含 SDK harness）、`.github/workflows/ci.yml`（docker job + paths）、`docs/deploy/`。既有 `java/server/artemis-dist`（fat jar）为唯一服务端交付物，不动。

## 验收方式

对应测试文档：[phase-3-docker-vm-test.md](phase-3-docker-vm-test.md)

冒烟：`docker run` 单容器全功能（三服务端口就绪 + SDK 走通）；tar.gz 解包 systemd 流程冒烟。

## 产品设计（③ 补）

### 1. Docker all-in-one 镜像

```dockerfile
# 根目录 Dockerfile.all-in-one；docker build -f Dockerfile.all-in-one -t artemis-all-in-one:local .
# 多阶段自包含（同根 Dockerfile 模式），构建目标 -pl :artemis-dist -am
```

| 项 | 值 | 说明 |
|---|---|---|
| 构建阶段 | `maven:3.9-eclipse-temurin-25` | `mvn -pl :artemis-dist -am package -DskipTests`（`-DskipTests` 非 `maven.test.skip`，同根 Dockerfile 注释理由） |
| 运行镜像 | `eclipse-temurin:25-jre` | 与单服务镜像一致 |
| 用户 | 非 root（uid 10001 `artemis`） | 同单服务镜像 |
| 工作目录 | `/app`，卷 `/app/data` | registry 快照 `data/registry-snapshot.bin` 相对工作目录 |
| EXPOSE | `8080 8081 8090 9090 9094 9095` | registry HTTP/gRPC、management HTTP/gRPC（console/Admin）、discovery HTTP/gRPC；peer 9093 与三个管理端口绑 loopback，不 EXPOSE |
| ENTRYPOINT | `java -jar /app/app.jar` | fat jar 重命名为 `app.jar`（同单服务镜像惯例，免版本通配） |

**配置外置**（all-in-one 既有配置出口零新增机制，见 [docs/api/all-in-one.md](../../../api/all-in-one.md)）：

1. **环境变量**：SCF relaxed 映射（`artemis.dist.registry.httpPort` → `ARTEMIS_DIST_REGISTRY_HTTP_PORT`，camel 转 `_` 大写）覆盖组合端口与业务键；`SPRING_DATASOURCE_URL` 等标准键接 DB（仅 management 上下文消费）。
2. **挂载工作目录文件**：`-v $PWD/artemis.properties:/app/artemis.properties:ro`（工作目录 `artemis.properties` 是 all-in-one 既有 SCF source）。
3. **数据持久化**：`-v artemis-data:/app/data`。

一条命令 eval：`docker run -d -p 8080:8080 -p 9090:9090 -p 8081:8081 -p 9094:9094 -p 8090:8090 -p 9095:9095 -v artemis-data:/app/data artemis-all-in-one:local`。

### 2. VM 标准安装包

**模板仓内化**：新增顶层 `deploy/` 目录（部署制品模板，语言无关——与 `proto/` 同为语言无关顶层目录的先例一致；phase-4 Helm 落 `deploy/helm/`）。`deploy/vm/` 内容随 git 管理，`build-dist.sh` 从模板组装（模板可修改、构建可复现，不靠每次生成）。

**包结构**（`artemis-<version>.tar.gz` 解包后 `artemis/`）：

```text
artemis/
├── bin/
│   └── app.sh            # 运维脚本（见下）
├── lib/
│   ├── artemis-dist.jar  # fat jar（构建时从 target 复制，重命名去版本）
│   └── version           # VERSION=<version> + BUILD_TIME=<ISO8601>（脚本生成）
├── data/                 # 空目录；registry 快照运行时写入
├── artemis.properties    # 全注释模板（工作目录 SCF 出口，见下）
└── artemis.service       # systemd unit 模板
```

**与通用安装包模板形态（bin/config/lib 三目录 + logs/）的两处有意偏离**：

- `artemis.properties` 置包根而非 `config/`：all-in-one 的 SCF source 固定读**工作目录**下该文件名（`AllInOneConfig.CONFIG_FILE_NAME`），根置零特判，`WorkingDirectory=APP_HOME` 即生效。
- 无 `logs/`：dist logback 仅 STDOUT appender，`journalctl -u artemis` 即日志，不引文件输出。

**app.sh 命令集**：`run`（前台，systemd/容器用）/ `start` / `stop` / `restart` / `status` / `logs`（journalctl 兜底 tail 文件）/ `version` / `install` / `uninstall`。要点：

- 启动前 `cd APP_HOME`（SCF 工作目录语义依赖 cwd）；`java ${JAVA_OPTS:-} -jar lib/artemis-dist.jar`。
- `JAVA_OPTS` 经环境变量注入（systemd unit `Environment=` 或 shell 前缀），模板不设默认堆参数。
- `start`/`stop` 走 pid 文件 + `kill -TERM`（JVM shutdown hook 优雅停机：discovery → management → registry 反序）；systemd 路径由 unit 的 `KillSignal=SIGTERM` 殊途同归。
- `install`：复制 `artemis.service` 到 `${SYSTEMD_UNIT_DIR:-/etc/systemd/system}` + `daemon-reload` + `enable`（`SYSTEMD_UNIT_DIR` 供 rootless 测试覆盖）。

**artemis.service 要点**：`Type=simple` + `ExecStart=<APP_HOME>/bin/app.sh run` + `WorkingDirectory=<APP_HOME>` + `Restart=on-failure` + `TimeoutStopSec=90`（覆盖优雅停机排水窗口）+ `LimitNOFILE=65536` + `User=artemis`（注释默认，装包时按需启用）。install 时以实际 APP_HOME 实例化路径。

**build-dist.sh 流程**：`mvn -f java/pom.xml -pl :artemis-dist -am package -DskipTests` → 组装 `build/artemis/`（模板 + jar + version）→ `tar czf build/artemis-<version>.tar.gz` + md5。`build/` 已在 `.gitignore` 补充。

### 3. 部署冒烟（`java/scripts/smoke-deploy.sh [docker|vm|all]`）

- **docker 模式**：build 镜像 → `docker run -d` → 就绪判定（容器日志含 `all-in-one started` + 对外六端口 TCP 通）→ console HTTP 探活（默认认证开，预期 401）→ **SDK 走通** → `docker rm -f`。
- **vm 模式**：`build-dist.sh` → 解包 tmp → 结构断言（bin/lib/version/properties/service 齐备）→ `app.sh start` → 就绪判定 + SDK 走通 → `app.sh stop` → 断言进程退出、无残留。
- **systemd install/uninstall/enable 流程**：需 root/systemd 环境，归**人工验证**（⑨ 用户本机执行），测试文档留位。
- **SDK harness**：`java/scripts/smoke-sdk.java` single-file（JDK 25 source-launch，`java -cp <client-classpath> smoke-sdk.java`，classpath 经 `mvn -f java/client/pom.xml -pl artemis-client dependency:build-classpath` 取）：`ArtemisClients.newRegistryClient()` 注册一实例 → `newDiscoveryClient()` 订阅同服务，断言收到该实例即 PASS；退出码非 0 即失败。harness 覆盖「SDK 走通」的最小闭环（注册侧 + 发现侧 + 治理链路过的同进程端口），不替代 AllInOneIT 的全语义覆盖。

### 4. CI

- docker job 补 `docker build -f Dockerfile.all-in-one -t artemis-all-in-one:ci .`。
- `paths` 触发清单补 `Dockerfile.all-in-one` 与 `deploy/**`（`java/**` 已含 `java/scripts/**`）。

### 5. 定案与架构同步

- **零新配置键、零 Java 变更**：Docker/VM 均为 artemis-dist fat jar 的包装层，配置出口（SCF `artemis.dist.*` + 工作目录 `artemis.properties` + `SPRING_DATASOURCE_*` 双轨）原样沿用——镜像与安装包只是交付载体，不另立配置面（与「产品能力 vs 部署形态」的归属原则一致：形态不新增能力）。触及原始文档：[docs/api/all-in-one.md](../../../api/all-in-one.md) 末行「容器镜像/部署脚本归 v1.0 部署形态」更新为指向 `docs/deploy/`。
- **架构 §2.3 无需修订**：三形态定义（§2.3）本 phase 落地其二，形态语义与架构定义一致（Docker = eval 单容器 all-in-one；VM = tar.gz + systemd）；Docker 镜像为单机 eval 不含集群语义，集群部署仍走单服务包/Helm。
- **`deploy/` 顶层目录**：部署模板语言无关仓内化（proto/ 先例延伸）；CLAUDE.md「当前状态」不涉此层，不更新；`docs/deploy/` 为文档目录（doc.md 类型表），与 `deploy/` 模板目录职责分离、互不含混。
- **registry 单服务镜像保留**：根 `Dockerfile` 原样不动（v0.1 交付物、CI 依赖），all-in-one 以并列 `-f Dockerfile.all-in-one` 提供（plan「保留并补全」原文落点）。

## 实现结果（⑦ 补）

**实现要点**：零依赖新增、零产品配置键新增；`Dockerfile.all-in-one`（多阶段，`-pl :artemis-dist -am`）+ `deploy/vm/` 模板（app.sh 九命令 / unit / 全注释 properties）+ `build-dist.sh`（jar 版本从文件名提取）+ `smoke-deploy.sh`（docker/vm 两模式 + `SmokeSdk.java` single-file harness，JDK source-launch）+ CI docker job 补第二个 build。基线顺带修复：`RouteRulePersistenceIT` spotless 格式遗留。

**实现期产品缺口修复**：all-in-one launcher 补 registry 单成员自报（`cluster.members`/`cluster.self` 未配置时默认注入、显式配置优先，self 独立注入免配第二键）——修前 `/cluster/nodes` 无 external 端点，SDK 前置寻址空列表不可接入。

**⑥ findings 处置**：修 3——冒烟 `wait_container_started` 失败分支 dead code（诊断日志丢失，改为先打日志再 fail）、命名卷 `artemis-smoke-data` cleanup 残留（补卷清理）、`sdk_classpath` 缓存无失效（client jar mtime 对比）；不修 1——`install_unit` sed 对含 `#` 路径（极端场景，记录）。

## 文档同步（⑩ 填）

| 文档 | 变更 |
|---|---|
| `docs/product/artemis.md`（v1.39） | 交付状态新增「v1.0 phase-3（部署形态 I）」节 |
| `docs/deploy/`（新建） | README + docker.md + vm.md（形态文档，phase-7 文档站汇编复用） |
| `docs/api/all-in-one.md`（v2.3，③④ 期同步） | 末行指向 docs/deploy/；成员自报语义（members/self 默认注入、显式配置优先） |
| `docs/README.md` | 根索引登记 deploy/ |
| `deploy/README.md`（新建） | 模板目录索引（vm 现状 + helm 预留） |
| CLAUDE.md | 未过时（构建/测试节不含部署入口，无需改）；部署入口由 docs/deploy/ 承载 |
| ADR / 架构文档 | 无——§2.3 三形态定义未变，launcher 注入为既有模式补全非新决策 |

## 更新历史

| 版本 | 日期 | 变更说明 |
| ------ | ------ | ------ |
| 1.4 | 2026-10-28 | ⑩ 文档同步清单 |
| 1.3 | 2026-10-28 | ⑦ 实现结果与 review 修复记录 |
| 1.2 | 2026-10-28 | ④ 实现期调整：launcher 补单成员自报（SDK 可接入性）+ .dockerignore 增补 |
| 1.1 | 2026-10-28 | 设计定稿（镜像/包结构/配置外置/冒烟方案） |
| 1.0 | 2026-10-27 | 开工骨架 |
