# Phase 8 测试：SDK 发布 Maven Central

版本: 1.1    更新时间: 2026-10-29

## 测试方案

本 phase 无产品运行时代码（pom 配置 + 脚本 + 文档），验证形态为**脚本化发布冒烟**（新形态，独立入口 `java/scripts/release-dry-run.sh`，与 bench.sh 同级的独立编排脚本，不进 dev-test.sh——发布前置检查非日常测试形态，dev-test.sh 模式矩阵不变、无同步扩展义务）：

- **发布冒烟**：隔离 GNUPGHOME 一次性测试密钥 + 临时 release 版本号（`0.1.0-DRYRUN`，退出恢复）+ 上传指向不可达端点（`centralBaseUrl=127.0.0.1:9`，bundle 组装后上传失败；0.11.0 的 `skipPublishing` 为整体跳过含 bundle，不可用——实现期实测），断言 central bundle 内容与签名可验证。
- **回归**：dev-test.sh all 全绿，证明 release profile 不进日常构建、无行为外溢。

覆盖面：sources/javadoc 产出与 Java 8 口径、全部发布件签名、bundle 组件集合（bench 排除）、日常构建不变。不覆盖：实际上传与 Portal 校验（用户凭账号执行，流程文档步骤，非本 phase 验收）。

## 用例清单

| 用例 | 场景 | 形态 | 结果 |
|---|---|---|---|
| should_produce_sources_and_javadoc_jars_when_release_profile_active | `-Prelease` 构建后 artemis-client 与 starter 各产出非空 `-sources.jar`/`-javadoc.jar`（JDK 25 构建进程、release=8 口径） | 发布冒烟（release-dry-run.sh） | pass（12 断言含 pom 恢复闭环复跑） |
| should_sign_all_bundle_components_when_gpg_configured | bundle 内三组件（parent pom/client/starter）主件、sources、javadoc、pom 均有 `.asc`，抽验 `gpg --verify` 通过 | 发布冒烟（release-dry-run.sh） | pass |
| should_exclude_bench_from_central_bundle_when_deploying | central bundle 含 `artemis-client-parent`/`artemis-client`/`artemis-client-spring-boot-starter` 全件、不含 `artemis-bench` 任何件 | 发布冒烟（release-dry-run.sh） | pass |
| should_keep_daily_build_unchanged_when_release_profile_absent | 不带 profile 的日常构建与测试行为不变 | 回归（dev-test.sh all） | pass（verify 全绿 + proto 兼容 5/5） |

## 验证记录（⑨ 填）

自动化验证（工作流执行）：

| 检查 | 命令 | 结果 |
|---|---|---|
| 发布冒烟全链路 | `java/scripts/release-dry-run.sh` | 通过：21 断言全绿（三组件 pom/主件/sources/javadoc 及各自 `.asc` 共 18 精确文件、bench 不在 bundle、gpg 验签 ×2）、pom 版本自动恢复、server 线 pom 零波及 |
| 全量构建与测试 | `java/scripts/dev-test.sh all` | 通过：verify 全绿 + proto 兼容 5/5 锚点 |
| 文档链接守门 | `java/scripts/check-docs.sh` | 通过：207 文件相对链接 ok、alerts ok（promtool/amtool 未装的降级口径，与 CI docs job 一致） |
| 组件版本核实 | repo1 maven-metadata 实查 | source 3.3.1（4.0.0-beta-1 不取）、javadoc 3.12.0、gpg 3.2.8、central-publishing 0.11.0（`publish` goal 参数 `excludeArtifacts`/`skipPublishing`/`publishingServerId` 经 plugin.xml 实证） |
| dry-run 幂等 | 干净态复跑（sed 备份→替换→恢复闭环） | 通过：两轮均 0 退出、无 DRYRUN/PROBE 残留 |

人工验证（用户执行）：待用户检查/试用与结论（含 code-review 上报的 starter 坐标命名裁决），内容由用户决定，工作流不指定。

## 回归记录（⑧ 填，经 dev-test.sh）

| 轮次 | 模式 | 范围 | 结果 |
|---|---|---|---|
| ⑤ 首轮 | all | java/ reactor | 失败：registry-server 依赖解析 `artemis-client:0.1.0-DRYRUN` 不存在——手动探针 versions:set 残留污染 server pom（非代码缺陷，恢复后定位并弃用 versions:set 改 sed 方案） |
| ⑤ 复跑 | all | java/ reactor | 通过（verify 全绿 + proto 兼容 5/5）；DiscoveryClientIT 于 dry-run 首跑曾现一次 flaky（`should_replay_snapshot_on_cold_start_when_server_down`），标准入口独立复跑 7/7 全绿，与本 phase 改动无交集 |
| ⑧ 回归 | dry-run + all | 发布冒烟（21 断言：18 精确文件 + bench 排除 + 2 gpg 验签）+ java/ reactor | 通过：dry-run exit 0 / pom 恢复 / server 线零波及；dev-test.sh all 全绿（verify + proto 兼容 5/5） |
| ⑧ 第二轮（改名 + 二轮 review 修复后） | dry-run + all | 同上 + `-pl '!artemis-bench'` + 陈旧 bundle 先删 + 恢复核验 + 子 pom `<name>` | 通过：dry-run 21 断言 + 恢复核验（4 pom 零 DRYRUN 残留）+ dev-test.sh all 全绿 + check-docs ok；中途一次「核验先于恢复」顺序缺陷当场定位修正（dryrun11） |

## 更新历史

| 版本 | 日期 | 变更说明 |
| ------ | ------ | ------ |
| 1.1 | 2026-10-29 | ⑨ 自动化记录更新（改名后两轮 dry-run + 全量）；⑧ 第二轮回归；starter 改名 D7 |
| 1.0 | 2026-10-29 | 测试设计定稿：发布冒烟（新形态独立脚本）+ 回归，四用例 |
