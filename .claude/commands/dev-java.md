# dev-java：Java 开发工作流

无参数调用。按 `docs/milestones/dev-state.md` 判定当前位置，自动推进开发，直到下一个需要用户确认的停点。

## 0. 状态机判定

先读 `docs/milestones/dev-state.md`（不存在则视为冷启动），按下表判定并执行对应环节：

| dev-state.md 形态 | 动作 |
|---|---|
| 文件不存在，或「版本总览」为空 / 全为 done | → 环节 1「版本启动」：以 roadmap 中**最早未启动版本**为对象（未启动 = 未出现在版本总览中） |
| 「当前版本」节有 phase 且步骤未到「完工」 | → 环节 2「phase 执行」：从登记的当前步骤继续 |
| 「当前版本」节无 in-progress phase，phase 总览仍有 planned | → ① 开工下一 phase（**转换点**：先报告确认） |
| 版本总览当前版本状态 = acceptance | → 环节 3「版本收尾」：从 plan 验收对照表已完成部分继续 |
| 版本总览当前版本状态 = in-progress 且全部 phase done | → 环节 3 开工：版本总览标 acceptance 后执行 |

推进规则：

- **转换点确认、步骤内自动**：phase 清单确认、新 phase 开工、squash merge 三个转换点先报告等用户确认；phase 内步骤连续推进。
- **每完成一个步骤立即更新 dev-state.md**，这是断点续推的唯一依据。dev-state.md 是全流程**唯一状态源**：进度、版本/phase 状态以此为准，plan 与 phase 文档不记状态。
- 步骤状态枚举：`pending` | `in-progress` | `done` | `skipped`（如 ⑦ 在 ⑥ 无 findings 时标 skipped 并备注原因）。
- 若当前会话不在 worktree 而 state 显示 phase 进行中，先进入对应 worktree 再继续。
- dev-state.md 由工作流按附录 D 固定模板自动维护，勿手工编辑（doc.md 已豁免其版本头/更新历史）。

**产出文档三类，形式固定**（模板见附录 A/B/C）：**plan 文档**、**phase 设计文档**（`phases/phase-<N>-<name>.md`）、**测试文档**（`phases/phase-<N>-<name>-test.md`）。

## 1. 版本启动（plan）

| 步骤 | 内容 | 产出 |
|---|---|---|
| 1 | 读 roadmap 对应版本的范围与验收要点、架构文档相关章节 | （会话上下文） |
| 2 | 写版本规划 | **plan 文档**（附录 A）：目标 + phase 清单 + 验收对照表（占位） |
| 3 | 拆 phase：**2 天工作量一个 phase**，不限数目；单一主题、有可验证产出；滚动细化（先拆明确的，随进度补充，不一次性全量规划） | plan 文档的 phase 清单（编号、主题、工作量、验收方式） |
| 4 | 初始化状态与索引 | `dev-state.md`（附录 D）；`docs/milestones/README.md`、`v<版本>/README.md`、`v<版本>/phases/README.md` 三级索引；`docs/README.md` 根索引登记 `milestones/` |
| 5 | **转换点**：呈现 phase 清单；确认后**将 plan/state/索引提交到 main**（提示用户发起 `/mydotey-ai:commit-and-push`）——此基线提交是后续 worktree 分支的起点，未经提交不得开工 | main 基线提交 |

## 2. phase 执行

步骤序列固定，严格按序执行，每步完成即登记 state：

```text
① worktree 开工 → ② 上下文加载 → ③ 设计 → ④ 实现 → ⑤ 测试
→ ⑥ /code-review → ⑦ /mydotey-ai:fix → ⑧ 回归测试 → ⑨ 验证（人工）
→ ⑩ 文档同步 → ⑪ 完工
```

### ① worktree 开工

- 分支命名 `{milestone}-phase<N>-{title}`（如 `v0.1-phase2-heartbeat`，git.md 分支命名表「开发工作流」行）；worktree 居 `.claude/worktrees/`，目录名与分支名一致。
- **幂等**：开工前检测同名分支/worktree 是否已存在——已存在则采纳续用（以文件系统为准），补齐 state 登记后继续，不重建。
- 建 worktree 前检查 `.gitignore` 是否含 `.claude/worktrees/`，未含则添加。
- **产出**：分支 + worktree；**phase 设计文档骨架**（附录 B 四节，状态行标 in-progress）；`phases/README.md` 登记本 phase 两份文档。

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

### ④ 实现

在 worktree 中按规范实现。

- 遇架构文档未覆盖的设计点**立即停下**，按 `.claude/rules/project.md` 对照基线 §5/§6 与用户讨论决策，不擅自定。
- **产出**：`java/` 源码 + 单元测试代码（分支内）；设计如有调整回写 phase 设计文档。

### ⑤ 测试 / ⑧ 回归测试

`mvn -f java/pom.xml verify`（spotless + enforcer + 测试一体）。⑤ 为实现后首跑；⑧ 为 review 修复后的回归，结果记入测试文档「回归记录」节。失败即修，全绿才进下一步。

**产出**：verify 全绿；测试文档用例清单「结果」列更新。

### ⑥ /code-review max

**产出**：findings 清单（severity 分级，chat 呈现）。无 findings 则 ⑦ 标 skipped、备注「无 findings」，直接 ⑧。

### ⑦ /mydotey-ai:fix

**产出**：修复说明（每条 finding 的处理结果）；phase 设计文档「实现结果」节记录要点。

### ⑧ 回归测试（同 ⑤）

### ⑨ 验证（人工）

**产出**：测试文档「验证清单」节——每条含命令 / 操作步骤 / 预期结果；用户执行后在该节记录结论。

**不通过 → 回退规则**：state 步骤 ④–⑨ 重置（④ 标 in-progress，⑤–⑨ 标 pending），修正后从 ⑤ 起**完整重走 ⑤–⑨（含 ⑥ code-review 与 ⑦ fix）**，与「严格按序」一致。

### ⑩ 文档同步

按 `.claude/rules/doc.md`「文档更新机制」执行。

**产出**：API 变更 → `docs/api/` 对应文档；架构变更 → `docs/arch/` + **重大决策在 `docs/decisions/` 新增 ADR**（`adr-<序号>-<名称>.md`，一决策一文件）并登记架构 §7 总览表与 `decisions/README.md`；phase 设计文档状态行标 done；各级索引同步；**CLAUDE.md 若因本 phase 过时（构建命令、模块结构、当前状态——尤其 phase-1 脚手架）则同步更新**。变更清单登记于 phase 设计文档「文档同步」节。

### ⑪ 完工

1. 提示用户发起 `/mydotey-ai:commit-and-push`（在 worktree 分支上提交，含代码、三文档与 state 更新——plan 与 state 基线已在 main，分支上为增量）。
2. 提交完成后（**转换点**）：squash merge 回 main（message 按 git.md 取本 phase 核心变更）、删分支、删 worktree、回到主工作区。
3. **产出**：main 上一个 squash commit；state：当前 phase 标 done、清空「当前版本」节的 phase/步骤字段（保留 phase 总览）。
4. 自动进入下一 phase 开工（回到 ①，转换点确认）。

## 3. 版本收尾

| 步骤 | 内容 | 产出 |
|---|---|---|
| 0 | 开工即把版本总览状态标 `acceptance`（断点恢复依据） | state 更新 |
| 1 | roadmap 该版验收要点**逐条对照**：每条有对应可执行测试且通过（guidelines §7）。该版无「验收要点」条目时（如 v1.x），以各 phase 验收方式汇总对照 | plan 文档验收对照表填齐（要点 / 对应测试 / 结果） |
| 2 | 文档终检 | 各级索引、CLAUDE.md「当前状态」的更新（如过时） |
| 3 | 版本标记完成 | state：版本总览该版本标 `done`（行保留，不清空）、清空「当前版本」节与 phase 总览 |
| 4 | **收尾提交**：提示用户发起 `/mydotey-ai:commit-and-push`（main，plan/state/终检变更） | main 收尾提交 |
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
状态: in-progress    # ① 开工标 in-progress，⑩ 标 done

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

变更文档清单。

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

形态（单测 / 进程内多实例集群语义测试）与覆盖面说明。

## 用例清单

| 用例 | 场景 | 形态 | 结果 |
|---|---|---|---|
| should_xxx_when_xxx | （一句话场景） | 单测 | pending |

## 验证清单（人工，⑨ 填）

| # | 命令 / 步骤 | 预期 | 结论 |
|---|---|---|---|

## 回归记录（⑧ 填）

| 轮次 | 范围 | 结果 |
|---|---|---|

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

状态枚举：in-progress | acceptance | done。

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
| 验证（人工） | pending | |
| 文档同步 | pending | |
| 完工 | pending | |

步骤状态枚举：pending | in-progress | done | skipped。

## phase 总览（当前版本，版本 done 后清空）

| phase | 主题 | 工作量（天） | 状态 |
|---|---|---|---|
| phase-1-scaffold | Maven 脚手架 | 2 | done |
| phase-2-heartbeat | 心跳即注册 | 2 | in-progress |
```
