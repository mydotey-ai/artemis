# Phase 8：SDK 发布 Maven Central

版本: 1.4    更新时间: 2026-10-29

## 目标

client 线（artemis-client + artemis-client-spring-boot-starter）发布配置就绪（源码/Javadoc/签名）+ 发布流程文档 + 本地 dry-run 通过；实际发布由用户凭账号执行。

## 范围

**做**：client 线发布配置（`release` profile：sources / javadoc / gpg 签名 / Central Portal 上传插件）+ client parent pom 元数据补齐（Central 校验硬要求）+ dry-run 独立脚本（`java/scripts/release-dry-run.sh`）+ 发布流程文档（`docs/dev/release-java.md`）+ 技术选型组件总表登记（评审先行纪律）。

**不做**：实际发布（用户凭 Central Portal 账号执行，本 phase 交付配置与文档）；服务端线发 Central（见定案 D1）；artemis-bench 发布（工具模块，见定案 D3）；仓库版本 bump（pom 常态保持 SNAPSHOT，发布时按流程文档 bump）；CI 发布流水线（发布为人工账号操作，无自动化需求）。

## 涉及模块

`java/client/`（artemis-client、artemis-client-spring-boot-starter、artemis-client-parent）构建与发布配置；无 proto 变更。

## 验收方式

对应测试文档：[phase-8-release-test.md](phase-8-release-test.md)

发布配置就绪 + 本地 dry-run（central bundle 组装/组件集合/签名校验）通过。

## 产品设计（③ 补）

发布配置是构建面交付物，无运行时接口；本节定发布组件清单、profile 结构、元数据与 dry-run 口径。

### 发布组件清单（定案 D2）

| 组件（groupId 统一 `org.mydotey.ai.artemis`） | 形态 | 说明 |
|---|---|---|
| `artemis-client-parent` | pom | 必须发布：client/starter 的 pom 以 parent 引用，消费端解析需 Central 可得 |
| `artemis-client` | jar + sources + javadoc + 签名 | |
| `artemis-client-spring-boot-starter` | jar + sources + javadoc + 签名 | |

### `release` profile（artemis-client-parent，`-Prelease` 显式激活）

日常构建（不激活 profile）零变化。插件矩阵（版本经组件总表评审，#37–#40）：

| 插件 | 版本 | 行为 |
|---|---|---|
| maven-source-plugin | 3.3.1 | `jar-no-fork` 附加 `-sources.jar` |
| maven-javadoc-plugin | 3.12.0 | 附加 `-javadoc.jar`；`release` 随 `maven.compiler.release=8` 属性（JDK 25 构建进程产 Java 8 口径 javadoc）；`doclint=none`——proto 生成代码无注释，doclint 默认全开会失败 |
| maven-gpg-plugin | 3.2.8 | `sign` 签全部发布件；passphrase 走 `gpg.passphrase` 属性 / gpg-agent，不落盘 |
| central-publishing-maven-plugin（org.sonatype.central） | 0.11.0 | `<extensions>true</extensions>` 注入 deploy 生命周期（deploy 即上传 Central Portal，无 distributionManagement）；`publishingServerId=central`；`excludeArtifacts=artemis-bench`（D3）；SNAPSHOT 版本走 Snapshot Publishing 路径不组 bundle；0.11.0 的 `skipPublishing` 为整体跳过（含 bundle 组装，实现期实测）——dry-run 经不可达 `centralBaseUrl` 出 bundle（见 dry-run 口径） |

### client parent pom 元数据补齐（Central 校验硬要求）

`<name>` / `<url>`（github.com/mydotey-ai/artemis）/ `<licenses>`（Apache-2.0，仓库根 LICENSE）/ `<developers>` / `<scm>`（同仓库 remote）。只补 client parent（子模块继承），服务端线 pom 不动（不发 Central 无校验需求）。

### dry-run 口径（`java/scripts/release-dry-run.sh`，独立入口，不进 dev-test.sh）

1. 临时隔离 `GNUPGHOME` + 一次性空 passphrase 测试密钥（本机无用户密钥；不污染真实 keyring，真实发布用用户密钥）。
2. 临时 release 版本号（`0.1.0-DRYRUN`，sed 字面替换设、备份恢复；versions:set 实测会波及 reactor 外引用 client 版本的 server pom，弃用）——SNAPSHOT 版本会被 Central 插件按 Snapshot Publishing 路径跳过、不组 bundle（实现期实测定案）；dummy settings server 满足插件凭证读取（不消费）。
3. `mvn -Prelease deploy -DcentralBaseUrl=http://127.0.0.1:9`（不可达端点）——bundle 组装完成后上传失败退出（0.11.0 的 `skipPublishing` 为整体跳过含 bundle 组装，实现期实测排除），脚本校验失败形态为预期签名。
4. 断言：central bundle 含 parent/client/starter 三组件全件（主件/sources/javadoc/pom 各带 `.asc`）、不含 `artemis-bench`；抽验 `gpg --verify` 通过。
5. 用例明细与结果见[测试文档](phase-8-release-test.md)。

### 版本策略与发布流程（定案 D4/D5）

- 首次发布版本 **1.0.0**（对齐产品 v1.0 GA；SDK 独立版本线不强制从 0 起步）；仓库 pom 常态保持 SNAPSHOT，发布时按流程文档 bump、发布后回次版本 SNAPSHOT。
- 发布 tag 约定 `sdk-java-<version>`，与仓库版本 tag（proto 契约版本锚点，phase-5）语义分离。
- 前置条件、逐步操作与发布后动作（Portal 校验发布 / tag / 版本回收 / 消费验证）见 `docs/dev/release-java.md`（单一来源，此处不复述）。

### 设计定案（review 记录）

- **D1 服务端线不发 Central**：roadmap v1.0 仅列「SDK 与 starter 发布 Maven Central」；服务端以 dist jar / 容器镜像 / Helm chart 交付（phase-3/4 已建），无库消费场景。触及文档：无需同步（维持 roadmap 原记载，非变更）。
- **D2 发布组件 = parent pom + client + starter**：parent 必须随发，否则消费端解析 parent 引用失败。触及文档：`docs/dev/release-java.md`（新建，流程单一来源）。
- **D3 artemis-bench 不发布**：压测工具模块（tech-stack #36 已注「工具模块不发布」），经 `excludeArtifacts` 排除。触及文档：tech-stack #36 备注已覆盖，无漂移。
- **D4 首次发布版本 1.0.0**：SDK 独立版本线的首个公开版本对齐 GA；guidelines §11「SDK 独立发布节奏届时单独决策」的决策点即本 phase。触及文档：`docs/dev/coding-guidelines-java.md` §11 同步修订（④ 落地，落点登记于「文档同步」节）。
- **D5 发布通道 = Central Portal（central-publishing-maven-plugin）**：OSSRH 已 sunset，#28 组件总表已定 Central Portal；本 phase 落执行组件 #40。触及文档：`docs/dev/tech-stack-java.md` 组件总表新增 #37–#40（④ 随 pom 落地）。
- **D6 dry-run 用隔离测试密钥**：本机无用户 GPG 密钥；dry-run 目的是验证签名链路而非身份，一次性密钥在临时 GNUPGHOME 中生成销毁。真实发布前置（用户密钥 + keyserver 分发）在流程文档登记为用户步骤。
- **D7 starter 坐标更名 `artemis-client-spring-boot-starter`**（⑨ 用户裁决，2026-10-29）：`spring-boot-starter-*` 前缀为 Spring 官方保留约定，Central 坐标发布不可撤回——发布前为唯一低成本改名窗口（原名 v0.1 phase-8 起，运行时无影响、纯 Maven 层：目录/artifactId/modules/文档引用；包名与自动装配注册不涉名）。历史里程碑文档记当时事实不改。触及文档：tech-stack §2.1/#35（沿革注记）、.claude/rules/tech-java.md、docs/api/sdk-java*.md、docs/product/artemis.md、quick-start.md、release-java.md、本 phase 两文档。

## 实现结果（⑦ 补）

**实现要点**：client parent pom 补 name/url/licenses(Apache-2.0)/developers/scm 元数据 + `release` profile（source 3.3.1 jar-no-fork / javadoc 3.12.0 doclint=none / gpg 3.2.8 / central-publishing 0.11.0 extensions=true + excludeArtifacts=bench）；bench pom 跳过 release 件构建（source/javadoc/gpg skip）；`java/scripts/release-dry-run.sh`（隔离密钥 + sed 临时版本 + 不可达端点出 bundle + 精确断言）；`docs/dev/release-java.md` 发布流程单一来源；tech-stack 1.31（#37–#40）、guidelines 1.27（§11 决策落地）、dev/README 索引。

**实现期实测修正（相对设计）**：① 0.11.0 `skipPublishing` 整体跳过（含 bundle 组装），dry-run 改为不可达 `centralBaseUrl` 出 bundle 后上传失败口径；② SNAPSHOT 版本走 Snapshot Publishing 路径不组 bundle，dry-run/发布必须以 release 版本号执行；③ `versions:set` 会改写 reactor 外引用 client 版本的 pom（registry-server test 依赖实测被改），版本 bump 一律 sed 精确替换；④ failsafe 的 skip 参数绑定为 `-DskipITs`（`-DskipTests` 不覆盖 IT）。

**code-review（max）修复记录**：12 findings，11 修复——release-java.md 四处（bump 漏 registry-server 第 5 处引用 / 步骤 4 缺 `-DskipITs` / tag 先于提交不可复现 → 调整为「提交发布版本→tag→回收」/ sed 硬编码版本静默空转 → 变量化 + 生效计数校验 + 故障表两条）；脚本三处（`.asc` 与主件拆为精确文件断言、unzip 失败容错不裸崩 / build_log 悬空路径改输出 tail / base_version 探测加替换生效防御 + keygen 失败显式报错）；bench 跳过发布件构建；phase doc 两处口径漂移（插件矩阵 skipPublishing 行、验收方式 install→bundle）；dev-state `实现` 行状态矛盾（初判误驳回为快照过时，复核确认有效——编辑链中一次失败替换致该行漏更新，已置 done）。1 条上报用户裁决：starter 坐标命名（`spring-boot-starter-*` 前缀为 Spring 官方保留约定），见 review 呈现。

**code-review 第二轮（改名重走后，max）修复记录**：15 findings，12 修复——runbook 六处（前提补「主工作区 main + working tree 干净」/ sed 防御改失败即止 + 回收对称防御 / 步骤 7 补 push / 新增步骤 9 消费文档同步 / 步骤 4 `-pl '!artemis-bench'` 省构建 / 故障表补 `_remote.repositories` 与 worktree 误操作两行）；脚本四处（陈旧 bundle 先删防假绿 + 失败 grep 收紧为具体消息 / trap 覆盖 INT/TERM/HUP / 退出前 4 pom 恢复核验 / `-pl` 排 bench）；两个子 pom 补 `<name>`（Central 元数据不可逆，防与 parent 同名）；phase doc 版本头纠偏；tech-java.md 例外清单去 starter（更名后合规）。驳回 2：跨线版本引用 property 化（与字面量同为单点 sed、无防护增益，超本 phase 范围）、bundle 加 `_remote.repositories` absent 断言（该文件实测在 bundle 内，插件固有行为，加断言必红——转故障表记档）。1 项转 ⑩：改名触及的五个存量文档（sdk-java.md / sdk-java-discovery.md / product/artemis.md / quick-start.md / plan.md）版本头与更新历史补登。

## 文档同步（⑩ 填）

- **产品文档 `docs/product/artemis.md`**（必含项）：1.44——v1.0 phase-8 交付段（发布配置 / starter 更名 D7 / 发布流程与 dry-run / 1.0.0 与 tag 约定）。
- **`docs/dev/release-java.md`**（新建）：SDK 发布流程单一来源（组件坐标 / 前置条件 / 九步流程 / 故障表 9 行）；`docs/dev/README.md` Java 组登记。
- **`docs/dev/tech-stack-java.md`**（D5 落点）：1.31——组件总表 #37–#40（source/javadoc/gpg/central-publishing）；§2.1 与 #35 starter 更名沿革注记。
- **`docs/dev/coding-guidelines-java.md`**（D4 落点）：1.27——§11 SDK 独立发布决策落地（首次 1.0.0、tag `sdk-java-<version>`）。
- **`.claude/rules/tech-java.md`**（D7 落点）：例外清单去 starter（更名后合 `artemis-` 前缀规则，沿革注记指向 tech-stack）。
- **改名触及（D7，R15 补登版本头/历史）**：`docs/api/sdk-java.md` 1.7（+Central 发布说明）、`docs/api/sdk-java-discovery.md` 1.3、`docs/product/quick-start.md` 1.1（发布状态措辞）。
- **`docs/milestones/v1.0/plan.md`** 1.1：phase-8 验收方式口径修正（install→bundle/sign）。
- **CLAUDE.md**：无过时（构建命令与模块结构未变，starter 名未被直接引用）。
- **ADR**：无（发布为工程面，无架构决策；roadmap v1.0「SDK 与 starter 发布」维持原记载）。

## 更新历史

| 版本 | 日期 | 变更说明 |
| ------ | ------ | ------ |
| 1.4 | 2026-10-29 | 文档同步清单；⑨ 人工验证通过 |
| 1.3 | 2026-10-29 | D7 定案：starter 更名 artemis-client-spring-boot-starter（⑨ 用户裁决），⑤–⑨ 重走 |
| 1.2 | 2026-10-29 | 实现结果 + code-review 修复记录；skipPublishing/versions:set 实测口径修正 |
| 1.1 | 2026-10-29 | 设计定稿：发布范围/profile/元数据/dry-run 口径/版本策略（D1–D6） |
| 1.0 | 2026-10-29 | 开工骨架 |
