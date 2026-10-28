# Phase 7 测试：文档站

版本: 1.1    更新时间: 2026-10-29

## 测试方案

纯文档 + 示例配置 phase，无单测/集群语义测试形态。两类可执行检查 + 一项既有部署冒烟复跑 + 人工走查：

| 检查 | 形态 | 入口 |
|---|---|---|
| 告警示例可加载 | 脚本检查（promtool 优先，YAML 结构断言兜底——本机无 promtool，结构断言为准） | `java/scripts/check-docs.sh alerts`（独立入口，同 smoke-deploy.sh 惯例不进 dev-test.sh 三模式） |
| 文档链接有效 | 脚本检查（docs 全目录 + README + CLAUDE.md 相对链接目标存在性） | `java/scripts/check-docs.sh links` |
| 快速开始可复现 | 部署冒烟（docker 模式，命令序列与 quick-start 同源） | `java/scripts/smoke-deploy.sh docker` |
| 构建回归基线 | all（本 phase 无代码变更，证不破坏既有全绿） | `java/scripts/dev-test.sh all` |
| 四类覆盖齐 | 人工走查 | — |

CI 同步：`.github/workflows/docs.yml` 按路径触发跑 check-docs.sh all（设计定案 6）。

## 用例清单

| 用例 | 场景 | 形态 | 结果 |
|---|---|---|---|
| should_load_alert_examples_when_checked | 告警三件套（prometheus/rules/alertmanager）通过 check-docs.sh alerts 校验 | 脚本检查 | 通过（本机无 promtool 走结构断言；CI docs.yml 装_promtool 走真校验） |
| should_resolve_all_doc_links_when_checked | docs 全目录 + README + CLAUDE.md 相对链接目标全部存在（含未跟踪新文档） | 脚本检查 | 通过（204 文件；首跑抓出 4 处存量断链已修） |
| should_reproduce_quick_start_when_docker_path_smoked | 快速开始 docker 命令序列 smoke-deploy.sh docker 全绿（同源可复现） | 部署冒烟 | 通过（ALL PASS；env 覆盖用例经启动日志断言实证 ARTEMIS_DIST_* 生效——原 ARTHEMIS 拼写 + TCP 探测空转缺陷已修） |
| should_stay_green_when_docs_phase_regressed | dev-test.sh all 全绿（无代码变更回归基线） | all | 通过（25:18，proto 兼容检查 5/5） |
| should_cover_four_doc_classes_when_phase_done | 四类文档 + README 门户 + 三处索引齐备且链接正确 | 人工走查 | pending（⑨ 用户验证） |

## 验证记录（⑨ 填）

自动化验证（工作流执行）：

| 检查 | 结果 |
|---|---|
| `java/scripts/check-docs.sh all`（links 204 文件 + alerts 结构断言） | 通过（review 修复后复跑绿；CI docs.yml 安装 promtool+amtool 走真校验——本机无两工具走兜底） |
| `java/scripts/smoke-deploy.sh docker`（⑤/⑧ 两轮；env 覆盖用例经 HTTP 语义断言） | 通过（ALL PASS ×2；`env override verified: registry HTTP serving on 18080 (401 default auth)`） |
| `java/scripts/dev-test.sh all`（⑤/⑧ 两轮） | 通过（25:18 / 20:48，BUILD SUCCESS + proto 兼容检查 5/5 版本锚点） |
| `bash -n` smoke-deploy.sh / check-docs.sh（⑦ 修改后） | 通过 |

人工验证（用户执行）：通过（2026-10-29，用户确认；覆盖新文档四件、告警三件套语义与阈值、CI/校验脚本与修复摘要）。

## 回归记录（⑧ 填，经 dev-test.sh）

| 轮次 | 模式 | 范围 | 结果 |
|---|---|---|---|
| 1（⑤ 首跑） | all + 独立入口 | dev-test.sh all（全量 verify + proto 兼容 5/5）+ check-docs.sh all（204 文件链接 + 告警结构）+ smoke-deploy.sh docker（含 env 覆盖新断言） | 通过 |
| 2（⑧ review 修复后） | all + 独立入口 | smoke-deploy.sh docker（env 断言换 HTTP 语义后复验：`env override verified ... 401 default auth`）+ dev-test.sh all（20:48，proto 兼容 5/5）+ check-docs.sh all（amtool 分工后结构断言绿） | 通过 |

## 更新历史

| 版本 | 日期 | 变更说明 |
| ------ | ------ | ------ |
| 1.1 | 2026-10-29 | ⑤⑧⑨ 记录：用例结果、回归两轮、自动化验证 |
| 1.0 | 2026-10-28 | 测试设计定稿（脚本检查/部署冒烟/人工走查形态） |
