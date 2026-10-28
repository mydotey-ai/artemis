# Phase 7：文档站

版本: 1.3    更新时间: 2026-10-29

## 目标

文档四类覆盖齐（快速开始/配置参考/API 参考/运维手册）+ 告警示例配置（Prometheus + Alertmanager）+ README 门户，按文档操作可复现。

## 范围

**做**：新增快速开始、配置参考、运维手册三份文档；告警示例配置三件套（部署工件）；根 README 重写为门户；索引同步；文档校验脚本 `check-docs.sh`（链接存在性 + 告警配置可加载性）与独立 CI workflow。

**不做**：不引入独立前端站点（plan 说明：与 console 同哲学，不新增部署单元）；不新建 API 文档（`docs/api/` 14 文档已聚合齐，门户直链）；不改 Java 代码与 proto；SDK/starter 配置键不复制进配置参考（单一来源在 sdk 文档）。

## 涉及模块

纯文档 + `deploy/alerts/` 示例配置 + `java/scripts/check-docs.sh` 脚本 + `.github/workflows/docs.yml`；无 `java/` 源码与 `proto/` 变更。

## 验收方式

对应测试文档：[phase-7-docs-test.md](phase-7-docs-test.md)

文档四类覆盖齐 + 告警示例规则可加载（check-docs.sh alerts：promtool 优先、结构断言兜底）+ 快速开始按文档操作可复现（与 smoke-deploy.sh docker 模式同源命令序列）。

## 产品设计（③ 定稿）

### 定案 1：目录结构（plan 授权 ③ 定案）

| 交付物 | 落点 | 说明 |
|---|---|---|
| 快速开始 | `docs/product/quick-start.md` | 产品开发者入口（用户面），与 artemis.md §3 衔接 |
| 配置参考 | `docs/deploy/config-reference.md` | 服务端配置全表（运维面） |
| 运维手册 | `docs/deploy/operations.md` | 运维面 |
| 告警示例配置 | `deploy/alerts/`（`prometheus.yml` / `rules.yml` / `alertmanager.yml`） | 部署工件，与 `helm/`、`vm/` 同级（既有部署工件目录惯例） |
| README 门户 | 根 `README.md` 重写 | 现状三行且含过时镜像命令（`docker build -t artemis-registry-server .` 为旧单服务口径） |
| API 参考 | 不新增 | `docs/api/` 已聚合齐全，门户直链其 README |

索引同步：`docs/README.md`、`docs/product/README.md`、`docs/deploy/README.md`。

### 定案 2：快速开始内容契约（`quick-start.md`）

读者路径：10 分钟从零跑通「注册 + 发现」。

1. 前置条件（docker 路径 / JDK+Maven 路径）
2. 起服务：all-in-one 一条命令（命令序列与 `smoke-deploy.sh` docker 模式**同源**——可复现性锚点，部署细节链 [docker.md](../../../deploy/docker.md) 不复写）
3. SDK 接入：Maven 依赖 + 注册/发现最简代码（对齐 `SmokeSdk.java` 的最小用法）
4. 下一步：配置参考 / API 参考 / 三种部署形态 / 运维手册 / 容量基线

单一来源纪律：只写「第一次跑通」所需，一切细节链接既有文档。

### 定案 3：配置参考内容契约（`config-reference.md`）

覆盖面 = 服务端全部配置键，按域分节（通用 / registry 外部与内部通道 / discovery / management 与 DB / all-in-one `artemis.dist.*` / TLS / Spring 键 `server.port`、`spring.ssl.bundle.*`、actuator 暴露），每节三列表：**键 / 默认值 / 说明**。

- 数据来源 = ④ 从代码穷举 SCF 键与 Spring 配置（grep 实名，不凭记忆写）；键清单以代码为准，文档不发明键。
- SDK/starter 键（`artemis.client.*`）不复制，链 `docs/api/sdk-java.md` / `sdk-java-discovery.md`；helm values 口径链 `docs/deploy/k8s.md`。

### 定案 4：运维手册内容契约（`operations.md`）

1. 启停与升级：三形态各链其部署文档；滚动升级口径引 v1.0 phase-5 验证结论（对等节点逐台重启，SDK 容灾不断流）
2. 监控接入：metrics 端点暴露方式（actuator 默认仅绑本机）、scrape 接入链 `deploy/alerts/prometheus.yml`
3. 告警规则与处置表：每条规则一张处置行（含义 → 排查路径）
4. 排障：症状 → 诊断入口（`/readyz`、`/cluster/nodes`、`/admin/v1/status/*`、结构化 JSON 日志）
5. 数据与恢复：registry snapshot、management DB、VM 数据目录

### 定案 5：告警规则集（arch §6 承诺「保护态触发、节点失联」必含，其余为机械健康规则）

指标名 = Micrometer → Prometheus 格式（`.` → `_`，代码实测 `artemis.registry.*` 系命名，④ 复核）：

| 规则 | severity | expr（for 见文件） | 含义 |
|---|---|---|---|
| ArtemisTargetDown | critical | `up{job=~"artemis-.*"} == 0` for 2m | 节点失联（scrape 视角） |
| ArtemisNodePeersLost | critical | `artemis_registry_cluster_peers_up == 0` for 2m | 节点与全部 peer 断联（集群视角；仅多节点集群适用，单节点恒 0） |
| ArtemisNodeSelfProtection | warning | `artemis_registry_self_protection > 0` for 5m | 节点级自我保护触发 |
| ArtemisServiceProtection | warning | `artemis_registry_self_service_protection_count > 0` for 5m | 服务级保护态出现 |
| ArtemisReplicationFailing | warning | `increase(artemis_registry_replication_batches_failed_total[10m]) > 0` | 复制批失败 |
| ArtemisReplicationDropped | warning | `increase(artemis_registry_replication_batches_dropped_total[10m]) > 0` | 复制批丢弃（对端未就绪） |
| ArtemisBootstrapFailing | warning | `increase(artemis_registry_bootstrap_failures_total[30m]) > 0` | bootstrap 反复失败 |

counter 指标带 `_total` 后缀（Micrometer Prometheus 格式），gauge 不带。不做启发式规则（如实例数骤降 delta——噪声大，保护态规则已是其规范出口）。

### 定案 6：文档校验入口 `java/scripts/check-docs.sh [links|alerts|all]`

- 同 `smoke-deploy.sh` 惯例：非 mvn 形态独立入口，不进 dev-test.sh 三模式与 verify 生命周期。
- `links`：`docs/**/*.md` + 根 `README.md` + `CLAUDE.md` 相对链接目标存在性（python3，文件级不做锚点级）。
- `alerts`：promtool（rules.yml + prometheus.yml）与 amtool（alertmanager.yml，Alertmanager 自有 schema）可用则真校验；缺失的工具退化为 bash 行级形状断言（规则字段计数配对、`rule_files` 引用存在、`route.receivers` ⊆ `receivers` 定义——不解析 YAML 语法，语法兜底在 CI 安装两工具）。
- CI：新增独立 `.github/workflows/docs.yml`（paths：`docs/**`、`README.md`、`deploy/alerts/**`、`java/scripts/check-docs.sh`），只跑 check-docs.sh——不动 ci.yml（避免 docs 变更连带 java/docker/helm job 全跑）。

### 触及的原始文档与同步落点

| 定案 | 触及 | 落点 |
|---|---|---|
| 定案 5 告警示例 | arch §6「告警……随 v1.0 提供示例配置」 | 交付即兑现承诺，arch §6 无需改写（示例文件即落点，operations.md 反链） |
| 定案 6 CI docs job | `.claude/rules/project.md`「CI 按路径触发」条目 | 该条目补 docs job 一句（④ 一并落地） |
| 其余定案 | 无架构/roadmap/proto 契约变更 | 无 |

## 实现结果（⑦ 补）

**实现要点**：

- 三文档 + 告警三件套 + README 门户 + `check-docs.sh`（links 补覆盖未跟踪文件——新写文档恰是未跟踪态）+ `docs.yml` 按定案落地；配置参考键面从代码穷举（`KEY_` 常量 + `DEFAULT_` 配对 + `application.properties`），非凭记忆。
- links 检查首跑抓出并修复 4 处**存量断链**（adr-016 / coding-guidelines×2 / v0.2 phase-5 相对层级算错）。
- **发现并修复 phase-3 存量缺陷**：smoke docker 模式 env 覆盖用例变量名拼错（`ARTHEMIS_DIST_*`，正确为 `ARTEMIS_DIST_*`）致 env 从未生效，且断言仅 TCP 探测——docker userland-proxy 对未监听端口也接受宿主连接，探测空转假绿。修复：正确拼写 + HTTP 语义断言（匿名 `/cluster/nodes` 重试至 401；503/000 视为未就绪重试）+ curl 进预检。docker.md 与 phase-3 文档同款拼写一并修正。
- 实现期收缩两处：配置参考不逐键标动态/静态（逐键热生效语义需读全部消费方，改为总则：认证凭证与保护/租约参数热生效、端口/成员/TLS/队列容量重启生效）；alerts 兜底从「python3 YAML 结构断言」改为 bash 行级形状断言（本机无 PyYAML/promtool；语法校验兜底在 CI 装两工具）。

**⑥ code-review（max，内置 skill）15 条 findings（14 CONFIRMED / 1 PLAUSIBLE）修复记录**：

| # | 修复 |
|---|---|
| promtool 校验 alertmanager 必红（Prometheus schema 不认 route/receivers） | `check-docs.sh` 分工 amtool（`amtool check-config`），`docs.yml` 增装 amtool v0.28.0，alertmanager.yml 头注释同步 |
| docs.yml tar 无 sudo | 两处 tar 加 sudo |
| quick-start 缺 SDK 认证键（registry 默认认证开，照跑必 401） | 代码块补 4 个 `System.setProperty`（addresses ×2 + auth ×2），散文说明 auth 键两侧共用语义 |
| operations.md 引用 discovery 不存在的 `/admin/v1/status(-rules)` | 修正：新鲜度/断流 → discovery `/admin/v1/upstreams`；规则水位 → management `/admin/v1/status/rules`；入口表列实际四路径并链两份 API 文档 |
| 配置优先级漏 Spring Environment source（优先级最高） | 配置模型节重写：五级链 + 「application.properties 已有键文件盖不动」推论 + `artemis.dist.*` 组合期独立链 |
| management.registry 凭证默认值误写 artemis/artemis | 改（空）+ 说明空用户名不发送认证头、实际部署须显式配置 |
| check-docs.sh rule_files 检查死代码（awk 顶格匹配 vs 实际缩进） | 模式改 `/^[[:space:]]*- /`，实测输出 rules.yml |
| 残留 6 处 `**(动态)**` 标注（2 处实为静态队列容量） | 全部清除，语义并入总则句 |
| 「认证启动期读取」与代码相反（Property 实时读，轮换免重启） | 总则句修正 |
| 漏列 grace-period 与 readiness group 键 | 观测节补两行 |
| phase 文档定案 5 expr 与 rules.yml 漂移（`_total` 后缀、`artemis-.*`） | 定案 5 表对齐实现 + 注明 counter 后缀规则 |
| 定案 6 兜底描述与实现不符 | 定案 6 改述实际行级断言 |
| README 设更新历史违反索引豁免 | 移除该节 |
| smoke env 断言耦合日志措辞 + SIGPIPE 隐患 | 换 HTTP 重试断言（401/503/000 语义） |

#13（⑦/⑩ 节空占位）非缺陷，属步骤时序。

## 文档同步（⑩ 填）

本 phase 变更文档清单：

| 变更 | 文档 |
|---|---|
| 新建（四类文档 + 门户） | `docs/product/quick-start.md`、`docs/deploy/config-reference.md`、`docs/deploy/operations.md`、根 `README.md` 重写；API 参考复用既有 `docs/api/`（14 文档已聚合，门户直链） |
| 新建（示例配置与守门） | `deploy/alerts/{prometheus,rules,alertmanager}.yml`、`java/scripts/check-docs.sh`、`.github/workflows/docs.yml` |
| 产品文档增量（必含项） | `docs/product/artemis.md` 1.43：v1.0 phase-7 交付节（四类文档/告警示例/守门/存量缺陷修复）+ 开发者入口节首补快速开始指路 |
| 索引同步 | `docs/README.md`（deploy 行）、`docs/product/README.md`（+quick-start）、`docs/deploy/README.md`（+config-reference/operations + alerts 指路） |
| 规则同步（review 定案 6 落点） | `.claude/rules/project.md` CI 按路径触发条目补 docs job |
| 存量修正 | `docs/deploy/docker.md` + `phase-3-docker-vm.md` env 拼写（ARTHEMIS→ARTEMIS）；4 处存量断链（adr-016、coding-guidelines ×2、v0.2 phase-5） |
| 无需更新 | CLAUDE.md（构建命令/模块结构/当前状态均未过时）；架构文档（告警示例为 §6 既定承诺兑现，无设计变更，无新 ADR） |

## 更新历史

| 版本 | 日期 | 变更说明 |
| ------ | ------ | ------ |
| 1.3 | 2026-10-29 | ⑨ 验证记录与 ⑩ 文档同步清单 |
| 1.2 | 2026-10-29 | 实现与 review 修复记录（15 findings）；定案 5/6 与实现对齐 |
| 1.1 | 2026-10-28 | 设计定稿（目录结构/四类文档契约/告警规则集/校验入口） |
| 1.0 | 2026-10-28 | 开工骨架 |
