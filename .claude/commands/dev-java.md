# dev-java：Java 开发工作流

无参数调用。按 `docs/milestones/dev-state.md` 判定当前位置，自动推进开发，直到下一个需要用户确认的停点。

## 0. 状态机判定

先读 `docs/milestones/dev-state.md`（不存在则视为冷启动），按下表判定并执行对应环节：

| dev-state.md 形态 | 动作 |
|---|---|
| 文件不存在，或「版本总览」为空 / 全为 done | → 环节 1「版本启动」：以 roadmap 中**最早未启动版本**为对象（未启动 = 未出现在版本总览中） |
| 版本总览最新版本状态 = planned（启动产物已写、基线未提交） | → 环节 1 步骤 5：呈现 phase 清单（转换点），确认后提示基线提交；用户确认提交完成后状态标 in-progress，转入 ① 开工首个 phase（转换点） |
| 「当前版本」节 phase 值非 — 且步骤未到「完工」 | → 环节 2「phase 执行」：从登记的当前步骤继续 |
| 版本状态 = in-progress 且「当前版本」节 phase 为 —：phase 总览仍有 planned → ① 开工下一 phase（**转换点**：先报告确认）；已全部 done → 环节 3 开工：版本总览标 acceptance 后执行 |
| 版本总览当前版本状态 = acceptance | → 环节 3「版本收尾」：从 plan 验收对照表已完成部分继续 |

推进规则：

- **转换点确认、步骤内自动**：phase 清单确认、新 phase 开工、设计制品 review 三个转换点先报告等用户人工 review，**通过后才能进入下一阶段**；phase 内其余步骤连续推进（⑨ 验证为人工停点：自动化检查由工作流自行执行并记录，随后停待用户自行验证并记录结论，不通过走该节回退规则）。**⑪ 提交后的 squash 合并不再是转换点**：worktree 分支提交完成即自动回主工作区 squash、更新 state、commit 并 push（见 ⑪ 步骤 2–3）；**版本收尾提交（环节 3 步骤 4）同样自动**——收尾变更直接 commit 并 push main，不再提示用户发起。
- **每完成一个步骤立即更新 dev-state.md**，这是断点续推的唯一依据。dev-state.md 是全流程**唯一状态源**：进度、版本/phase 状态以此为准；plan 不设状态列、phase 设计文档不记进度状态，不双写。
- 步骤状态枚举：`pending` | `in-progress` | `done` | `skipped`（如 ⑦ 在 ⑥ 无 findings 时标 skipped 并备注原因）。
- 若当前会话不在 worktree 而 state 显示 phase 进行中，**提示用户自行切换到对应 worktree**，切换完成后继续（不自动进入）。
- dev-state.md 由工作流按附录 D 固定模板自动维护，勿手工编辑（doc.md 已豁免其版本头/更新历史）。

**产出文档三类，形式固定**（模板见附录 A/B/C）：**plan 文档**、**phase 设计文档**（`phases/phase-<N>-<name>.md`）、**测试文档**（`phases/phase-<N>-<name>-test.md`）。

## 1. 版本启动（plan）

| 步骤 | 内容 | 产出 |
|---|---|---|
| 1 | 读 roadmap 对应版本的范围与验收要点、架构文档相关章节 | （会话上下文） |
| 2 | 写版本规划 | **plan 文档**（附录 A）：目标 + phase 清单 + 验收对照表（占位） |
| 3 | 拆 phase：**2 天工作量一个 phase**，不限数目；单一主题、有可验证产出；滚动细化（先拆明确的，随进度补充，不一次性全量规划） | plan 文档的 phase 清单（编号、主题、工作量、验收方式） |
| 4 | 初始化状态与索引 | `dev-state.md`（附录 D；版本总览该版本标 **planned**，基线提交后由步骤 5 转 in-progress）；`docs/milestones/README.md`、`v<版本>/README.md`、`v<版本>/phases/README.md` 三级索引；`docs/README.md` 根索引登记 `milestones/` |
| 5 | **转换点**：呈现 phase 清单；确认后**将 plan/state/索引（连同会话内的工作流/规则修订，如有）提交到 main**（提示用户发起 `/mydotey-ai:commit-and-push`；无关变更不得混入）——此基线提交是后续 worktree 分支的起点，未经提交不得开工。用户确认提交完成后，版本总览状态标 in-progress，转入 ① 开工首个 phase（转换点） | main 基线提交 |

## 2. phase 执行

步骤序列固定，严格按序执行，每步完成即登记 state：

```text
① worktree 开工 → ② 上下文加载 → ③ 设计 →（转换点：设计 review）→ ④ 实现 → ⑤ 测试
→ ⑥ /code-review → ⑦ /mydotey-ai:fix → ⑧ 回归测试 → ⑨ 验证
→ ⑩ 文档同步 → ⑪ 完工
```

### ① worktree 开工

- 分支命名 `{milestone}-phase<N>-{title}`（如 `v0.1-phase2-heartbeat`，git.md 分支命名表「开发工作流」行）；worktree 居 `.claude/worktrees/`，目录名与分支名一致。
- **幂等**：开工前检测同名分支/worktree 是否已存在——已存在则**先报告其与 main 的分叉/完工状态**（已 squash 合并的残留分支 vs 进行中分支），由用户决定续用或重建，**不静默续用**（静默续用已完工分叉分支会使二次 squash merge 冲突）。用户确认续用时以文件系统为准，补齐 state 登记后继续，不重建。
- 建 worktree 前检查 `.gitignore` 是否含 `.claude/worktrees/`，未含则添加；此类对 main 的改动**提交到 main 后再建 worktree**（自动 commit，不单独 push、随 ⑪ squash 收尾一并推送；worktree 从提交后的 HEAD 起点创建，未提交则分支基线缺该改动）。
- worktree 就绪后**不自动进入**（不调用 EnterWorktree、不切工作目录）：报告 worktree 路径与分支，**等用户自行切换进入**后继续。②–⑩ 的文件读写与命令一律在 worktree 目录执行，不从主工作区以绝对路径远程操作 worktree 文件；⑪ 提交完成后**自动回主工作区**执行 squash 收尾（无需用户手动切换；见 ⑪），worktree 保留不删。
- **产出**：分支 + worktree；**phase 设计文档骨架**（附录 B 模板）；`phases/README.md` 登记本 phase 两份文档。滚动细化的 phase 清单调整（拆分/新增）在本步同步更新 plan.md 与 dev-state phase 总览，随分支提交。

### ② 上下文加载（强制清单，缺一不可）

- roadmap 当前版本条目与验收要点
- `docs/arch/artemis-next-architecture.md` 相关章节
- `.claude/rules/tech-java.md`（全文）+ `docs/dev/coding-guidelines-java.md` 相关节
- 涉及 proto 时：`docs/dev/proto-contract.md`
- 涉及原产品机制继承/修复时：`docs/legacy/legacy-product-analysis.md` 对应资产/局限条目

**产出**：无文件；state 该步骤备注栏登记实际所读文档清单（可追溯）。

### ③ 设计

先设计后实现，两份产出：

- **phase 设计文档「产品设计」节**（附录 B）：行为、接口签名、数据结构与语义（接口为主，不含实现细节）。
- **测试文档**（附录 C，`phases/phase-<N>-<name>-test.md`）：测试方案（形态与覆盖面）+ 用例清单（用例名、场景一句话、形态：单测 / 进程内多实例集群语义测试，guidelines §7）。

**转换点（设计 review）**：两份制品**先落盘**（写入 `phases/` 目录），用户对落盘文档人工 review 确认——不在 chat 摘要中确认，chat 只呈现文件路径与决策要点；**确认通过后方可进入 ④ 实现**；有修改意见则修订制品落盘并再次呈现路径，直至通过。③ 的 done 以 review 通过为准——等待与修订期间 state 该行标 in-progress（备注「待人工 review」/「review 修订中」）。

**架构同步纪律**：review 定案的决策若变更架构文档（或 roadmap、proto 契约规范等）已记载的设计（机制、协议、数据模型、规则），须**同时修订对应原始文档**——重大决策同步新增 ADR 并登记架构 §7 总览表，不以「⑩ 记账」延后，避免架构文档与实际设计漂移；phase 设计文档的 review 定案节只记录决策与理由并指向已同步的文档，**每个定案 bullet 声明其触及的原始文档与同步落点**（review 通过以对照可查为前提，不靠记忆）；「决策理由」不复述原始文档规则本身（单一来源，防多副本漂移）。（④ 实现中出现同类变更同此纪律）

### ④ 实现

在 worktree 中按规范实现。

- 遇架构文档未覆盖的设计点**立即停下**，按 `.claude/rules/project.md` 对照基线 §5/§6 与用户讨论决策，不擅自定。
- **产出**：`java/` 源码 + 单元测试代码（分支内）；设计如有调整回写 phase 设计文档。

### ⑤ 测试 / ⑧ 回归测试

测试经自动化测试入口 `java/scripts/dev-test.sh` 执行——作用：统一测试执行入口，三模式 unit（surefire `*Test` 单测）/ integration（failsafe `*IT` 集群语义测试）/ all（全量 verify：spotless + enforcer + 测试一体）。⑤/⑧ 以 **all** 模式执行（≡ `mvn -f java/pom.xml verify`）；⑤ 为实现后首跑，⑧ 为 review 修复后的回归，轮次与模式记入测试文档「回归记录」节。失败即修，全绿才进下一步。

**脚本同步义务**：测试方案引入既有形态↔模式映射之外的新形态，或测试基础设施变化（插件、toolchains 等影响脚本命令）时，dev-test.sh 须同步更新——③ 设计在测试方案中明确扩展，④⑤ 实现随代码落地，脚本与测试制品不得漂移（如 phase-2 服务级集成测试接入：failsafe 入 parent pom，integration 模式随之生效）。

**产出**：dev-test.sh all 全绿；测试文档用例清单「结果」列更新。

### ⑥ /code-review max

**技能选择**：使用内置 **`code-review`** skill（参数 `max`）。不要误用同类插件技能——`mattpocock-skills:code-review`（Standards+Spec 双轴 PR 评审）、`code-review:code-review`（PR 评审）、`open-code-review:*`（外部 OCR）、`ponytail:ponytail-review`（仅过度设计清扫）均不是本步骤要求的实现级 correctness review。

**审查口径**：本 worktree 的未提交改动 + 本 phase 分支相对 main 的 diff（含未跟踪文件），逐文件审查 correctness / 并发 / 资源释放 / 测试有效性；findings 按 severity 分级并在 chat 呈现。

**已知环境故障与兜底（2026-10-23 实测）**：审查子 agent 被 spawn 时内置工具（Read/Grep/Glob/Bash）不可用，只能搜到 MCP 工具，会退而用浏览器/无关 MCP 工具取 diff 并**永久挂起**（实测卡在 `browser_run_code_unsafe` 约 1 小时无返回）。判定条件：子 agent 约 10 分钟无新增 transcript，或出现调用 browser/MCP 工具取代码/执行命令。一旦命中，终止该子 agent，改由**主会话以完整工具权限内联执行同口径 max 级 review**（自行 `git diff` + 未跟踪文件逐文件审查、逐条验证 findings）；state ⑥ 备注登记「内联替代 skill + 故障原因」，⑦⑧⑨ 流程不变。

**产出**：findings 清单（severity 分级，chat 呈现）。无 findings 则 ⑦ 标 skipped、备注「无 findings」，直接 ⑧。

### ⑦ /mydotey-ai:fix

**产出**：修复说明（每条 finding 的处理结果）；phase 设计文档「实现结果」节记录要点。

### ⑧ 回归测试（同 ⑤）

### ⑨ 验证

验证分两类，自动化归工作流，人工归用户：

- **自动化验证（工作流执行）**：命令可判定的检查（构建 / 测试 / 静态检查 / 日志取证等）不设人工清单，工作流直接执行，命令与结果记入测试文档「验证记录」节。
- **人工验证（用户执行，停点）**：自动化检查通过后即停——用户自行验证，方式与范围由用户决定（对制品——设计 / 代码 / 文档——的检查、对产品功能的试用等），工作流不指定；用户结论记入「验证记录」节，通过才进 ⑩。

**不通过 → 回退规则**：state 步骤 ④–⑨ 重置（④ 标 in-progress，⑤–⑨ 标 pending），修正后从 ⑤ 起**完整重走 ⑤–⑨（含 ⑥ code-review 与 ⑦ fix）**，与「严格按序」一致。

### ⑩ 文档同步

按 `.claude/rules/doc.md`「文档更新机制」执行。

**产出**：**产品文档 `docs/product/artemis.md` 同步更新（必须步骤）——本 phase 交付的对外可见能力（产品定位、核心概念、操作语义、配置与使用等）落入产品文档，与开发进度同步递补；文档不存在则首个收尾的 phase 建立**；API 变更 → `docs/api/` 对应文档；架构变更 → `docs/arch/` + **重大决策在 `docs/decisions/` 新增 ADR**（`adr-<序号>-<名称>.md`，一决策一文件）并登记架构 §7 总览表与 `decisions/README.md`；各级索引同步；**CLAUDE.md 若因本 phase 过时（构建命令、模块结构、当前状态——尤其 phase-1 脚手架）则同步更新**。变更清单登记于 phase 设计文档「文档同步」节。

### ⑪ 完工

1. 提示用户发起 `/mydotey-ai:commit`（在 worktree 分支上提交，含代码、三文档与 state 更新——plan 与 state 基线已在 main，分支上为增量；**只提交不推送**，分支止于本地 squash merge，合并后保留不删）。
2. 分支提交完成后**自动回到主工作区**（无需用户确认或手动切换；若会话经 EnterWorktree 进入则 `ExitWorktree(keep)`，否则以主工作区根路径的 `git -C` 与绝对路径文件操作完成收尾，不 `cd` 进入）：先检查主工作区 `git status`——有未提交改动则停下报告（不把无关变更混入 squash），干净才继续。
3. squash merge 回 main（自动）：`git -C <主工作区根> merge --squash <分支>`（暂存不提交）→ 以主工作区绝对路径更新 state（当前 phase 标 done、清空「当前版本」节的 phase/步骤字段，保留 phase 总览）并加入暂存 → 单个 squash commit 一并落地（message 按 git.md 取本 phase 核心变更）→ **push main 到远程**（`git push origin main`，本步为工作流既定动作；worktree 分支仍只留本地不推送）→ **保留分支，不自动删除**（squash 后分支与 main 无共同祖先、显示分叉属预期；分支留作本 phase 完整提交历史，由用户决定何时清理）。state 收尾随 squash commit 原子入 main，主工作区无未提交残留，下个 worktree 从含收尾的 HEAD 起。
4. 出口分支：phase 总览仍有 planned → 回到 ① 开工下一 phase（转换点确认）；已全部 done → 环节 3 开工（版本总览标 acceptance 后执行）。

## 3. 版本收尾

| 步骤 | 内容 | 产出 |
|---|---|---|
| 0 | 开工即把版本总览状态标 `acceptance`（断点恢复依据） | state 更新 |
| 1 | roadmap 该版验收要点**逐条对照**：每条有对应可执行测试且通过（guidelines §7）。该版无「验收要点」条目时（如 v1.x），以各 phase 验收方式汇总对照 | plan 文档验收对照表填齐（要点 / 对应测试 / 结果） |
| 2 | 文档终检 | 各级索引（含 milestones/ 索引）、CLAUDE.md「当前状态」的更新（如过时） |
| 3 | 版本标记完成 | state：版本总览该版本标 `done`（行保留，不清空；备注列移除「当前版本」标记）、清空「当前版本」节与 phase 总览 |
| 4 | **收尾提交（自动）**：在主工作区提交 plan/state/终检变更并 `git push origin main`（工作流既定动作，无需用户发起） | main 收尾提交 |
| 5 | 下次 `/dev-java` 自动进入下一版本启动 | — |

## 附录 A：plan 文档模板（`docs/milestones/v<版本>/plan.md`）

```markdown
# v<版本> 版本规划

版本: 1.0    更新时间: YYYY-MM-DD

## 目标

（引 roadmap 对应版本范围原文）

## phase 清单

（phase 状态以 dev-state.md 为准，此处不设状态列）

| phase | 主题 | 工作量（天） | 验收方式 |
|---|---|---|---|
| phase-1-scaffold | Maven 脚手架 | 2 | verify 全绿 + 模块结构 enforcer 断言 |

## 验收对照（收尾时填）

| roadmap 验收要点 | 对应测试 | 结果 |
|---|---|---|

## 更新历史

| 版本 | 日期 | 变更说明 |
| ------ | ------ | ------ |
| 1.0 | YYYY-MM-DD | 版本启动，初始 phase 拆分 |
```

## 附录 B：phase 设计文档模板（`phases/phase-<N>-<name>.md`）

```markdown
# Phase <N>：<主题>

版本: 1.0    更新时间: YYYY-MM-DD

## 目标

（一句话）

## 范围

做什么 / 不做什么。

## 涉及模块

java/ 模块与 proto 组。

## 验收方式

对应测试文档：<链接 phase-<N>-<name>-test.md>

## 产品设计（③ 补）

行为、接口签名、数据结构与语义。

## 实现结果（⑦ 补）

实现要点与 review 修复记录。

## 文档同步（⑩ 填）

变更文档清单；产品文档 `docs/product/artemis.md` 本 phase 增量为必含项（规则见 ⑩），review 定案触及的原始文档逐条登记同步落点。

## 更新历史

| 版本 | 日期 | 变更说明 |
| ------ | ------ | ------ |
| 1.0 | YYYY-MM-DD | 开工骨架 + 设计定稿 |
```

## 附录 C：测试文档模板（`phases/phase-<N>-<name>-test.md`）

```markdown
# Phase <N> 测试：<主题>

版本: 1.0    更新时间: YYYY-MM-DD

## 测试方案

形态（单测 / 进程内多实例集群语义测试）与覆盖面说明。执行入口 `java/scripts/dev-test.sh`（模式定义与形态↔模式映射见 ⑤ 节，此处不复制）；用例形态超出既有映射时，须在本节设计 dev-test.sh 的模式扩展，实现（④⑤）同步落地。

## 用例清单

| 用例 | 场景 | 形态 | 结果 |
|---|---|---|---|
| should_xxx_when_xxx | （一句话场景） | 单测 | pending |

## 验证记录（⑨ 填）

自动化验证（工作流执行）：命令与结果逐条记。人工验证（用户执行）：所做检查 / 试用与结论，内容由用户决定，工作流不指定。

## 回归记录（⑧ 填，经 dev-test.sh）

| 轮次 | 模式 | 范围 | 结果 |
|---|---|---|---|

## 更新历史

| 版本 | 日期 | 变更说明 |
| ------ | ------ | ------ |
| 1.0 | YYYY-MM-DD | 测试设计定稿 |
```

## 附录 D：dev-state.md 模板

```markdown
# 开发状态

> 工作流（/dev-java）自动维护，勿手工编辑。全流程唯一状态源。

## 版本总览（只追加，历史行保留）

| 版本 | 状态 | 备注 |
|---|---|---|
| v0.1 | in-progress | 当前版本 |

状态枚举：planned | in-progress | acceptance | done（planned = 启动产物已写、基线未提交）。

## 当前版本（版本 done 后清空本节）

| 项 | 值 |
|---|---|
| phase | phase-2-heartbeat |
| 步骤 | 实现 |
| 分支 / worktree | v0.1-phase2-heartbeat @ .claude/worktrees/v0.1-phase2-heartbeat |

## 当前 phase 步骤进度

| 步骤 | 状态 | 备注 |
|---|---|---|
| worktree 开工 | done | |
| 上下文加载 | done | 已读：roadmap v0.1、架构 §4.1、tech-java.md、guidelines §4 |
| 设计 | done | |
| 实现 | in-progress | |
| 测试 | pending | |
| code-review | pending | |
| fix | pending | |
| 回归测试 | pending | |
| 验证 | pending | |
| 文档同步 | pending | |
| 完工 | pending | |

步骤状态枚举：pending | in-progress | done | skipped。

## phase 总览（当前版本，版本 done 后清空；只记状态，主题/工作量以 plan 为准）

| phase | 状态 |
|---|---|
| phase-1-scaffold | done |
| phase-2-heartbeat | in-progress |

状态枚举：planned | in-progress | done。
```
