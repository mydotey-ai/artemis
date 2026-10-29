# Java SDK 发布流程（Maven Central）

版本: 1.0    更新时间: 2026-10-29

SDK 线（client + starter）发布 Maven Central 的单一来源：发布组件与 `release` profile 定义见[技术选型](tech-stack-java.md) #37–#40，本文只写流程与前置条件。实际发布由维护者凭 Central Portal 账号执行（本仓库工作流交付配置与 dry-run，不代发）；服务端线不发 Central（dist jar / 镜像 / Helm 交付，见各 phase 文档）。

## 发布组件与坐标

groupId 统一 `org.mydotey.ai.artemis`：

| 组件 | 形态 | 说明 |
|---|---|---|
| `artemis-client-parent` | pom | 必须随发：client/starter 的 pom 以 parent 引用，消费端解析需 Central 可得 |
| `artemis-client` | jar + sources + javadoc + `.asc` × 4 | |
| `artemis-client-spring-boot-starter` | jar + sources + javadoc + `.asc` × 4 | |

`artemis-bench` 为压测工具模块，经 `excludeArtifacts` 排除，永不发布。

消费端坐标（首次发布 1.0.0 起可用）：

```xml
<dependency>
  <groupId>org.mydotey.ai.artemis</groupId>
  <artifactId>artemis-client</artifactId>
  <version>1.0.0</version>
</dependency>
```

## 前置条件（一次性）

1. **Central Portal 账号**：[central.sonatype.com](https://central.sonatype.com) 注册（旧 OSSRH 账号已迁移）；Namespace `org.mydotey.ai.artemis` 已验证——既有已验证 namespace `org.mydotey`（org.mydotey.* 库发布沿革）覆盖子组，Portal 页面确认即可，未覆盖则按页面指引补验证。
2. **访问 token**：Portal → Account → Generate User Token；写入 `~/.m2/settings.xml`：

   ```xml
   <server>
     <id>central</id>
     <username>${token 用户名}</username>
     <password>${token 密码}</password>
   </server>
   ```

3. **GPG 密钥**：`gpg --full-generate-key`（RSA 4096）；公钥分发 `gpg --keyserver keyserver.ubuntu.com --send-keys <KEYID>`（Portal 校验签名需可检索）。passphrase 走 gpg-agent（`GPG_TTY=$(tty) gpg-agent ...`）或构建时 `-Dgpg.passphrase=`。

## 发布步骤

**前提**：在**主工作区 main 分支**上执行（发布版本提交进 main；勿在 worktree 开发分支上操作——`git push origin main` 会推到不含发布提交的旧 main）；working tree 干净（`git status --porcelain` 为空）——发布构建的输入必须与 tag 的树一致，脏树会把未提交改动编进不可撤回的发布件。

```bash
# 1. 版本 bump（client 线独立版本线；当前发布版本 1.0.0）
#    sed 精确替换版本字面量，共 5 处：client 线 4 pom + registry-server 的 client test 依赖（同版本线跟随）。
#    不用 versions:set——实测会波及 reactor 外引用 client 版本的 pom 且范围不可控
RELEASE=1.0.0; NEXT=1.0.1-SNAPSHOT; FROM=0.1.0-SNAPSHOT
POMS="java/client/pom.xml java/client/artemis-client/pom.xml \
  java/client/artemis-client-spring-boot-starter/pom.xml java/client/artemis-bench/pom.xml \
  java/server/artemis-registry-server/pom.xml"
sed -i "s|<version>${FROM}</version>|<version>${RELEASE}</version>|" $POMS
# sed 零匹配静默成功的防御：5 个 pom 必须都已带上发布版本，否则中止
N=$(grep -l "<version>${RELEASE}</version>" $POMS | wc -l)
[ "${N}" -eq 5 ] || { echo "版本替换仅命中 ${N}/5，核对 FROM=${FROM}"; exit 1; }

# 2. 全量测试（发布前置）
java/scripts/dev-test.sh all

# 3. dry-run：隔离测试密钥 + 临时 release 版本号 + 不可达上传端点，全链路出 bundle 后断言（组件集合/签名）
java/scripts/release-dry-run.sh

# 4. 发布（deploy 即上传 Portal，需 settings.xml token + 真实 GPG 密钥；测试已由步骤 2 把关，跳过；
#    -pl 排除 bench 工具模块，bundle 不收且省 shade 时长，excludeArtifacts 仍为双保险）
mvn -B -f java/client/pom.xml -Prelease -pl '!artemis-bench' \
  -DskipTests=true -DskipITs=true deploy

# 5. Portal 校验发布：central.sonatype.com → Releases → 校验通过（pom/javadoc/sources/签名/校验和）→ Publish
#    （配置 autoPublish=true 则跳过手动确认）

# 6. 提交发布版本并 tag（tag 必须指向发布版本已提交的树，保证从 tag 可复现发布件；
#    tag 语义与仓库版本 tag 分离：sdk-java-<version>）
git add $POMS
git commit -m "chore(release): java sdk ${RELEASE}"
git tag "sdk-java-${RELEASE}" && git push origin main "sdk-java-${RELEASE}"

# 7. 版本回收（回次版本 SNAPSHOT，第二个提交并推送）
sed -i "s|<version>${RELEASE}</version>|<version>${NEXT}</version>|" $POMS
N=$(grep -l "<version>${RELEASE}</version>" $POMS | wc -l)
[ "${N}" -eq 0 ] || { echo "回收残留 ${N} 个 pom 仍为 ${RELEASE}"; exit 1; }
git add -u java/ && git commit -m "chore(release): java sdk 回收 ${NEXT}" && git push origin main

# 8. 消费验证：Central 搜索坐标可解析，依赖 snippet 拉取成功

# 9. 消费文档同步：docs/api/sdk-java.md、sdk-java-discovery.md、docs/product/quick-start.md 的
#    依赖 snippet 版本与「发布状态」说明更新为已发布坐标，随回收后的提交入库
```

dry-run（步骤 3）与正式发布（步骤 4）走同一 `release` profile 全链路（sources/javadoc/签名/bundle 组装），差异只在密钥与上传去向——dry-run 用临时隔离密钥验链路（不验身份），上传指向不可达端点：bundle 组装完成后上传失败退出，脚本断言失败形态与 bundle 内容（三组件全件、bench 排除、签名可验证），pom 版本临时 bump 为 `0.1.0-DRYRUN` 后自动恢复。SNAPSHOT 版本会被插件按 Snapshot Publishing 路径跳过，发布必须先完成步骤 1 的版本 bump。

## 常见故障

| 现象 | 处置 |
|---|---|
| Portal 校验失败：namespace 未验证 | 前置条件 1，Portal 页面补验证后重新上传 |
| 签名校验失败（key not found） | 公钥未分发 keyserver，`--send-keys` 后等索引（分钟级）重试 |
| javadoc 生成失败 | profile 已设 `doclint=none`；若新代码触发其它 javadoc 错误，先本地 `-Prelease package` 复现修复，不放宽 profile |
| bundle 含 bench / 缺组件 | `excludeArtifacts` 配置漂移，对照 tech-stack #40 |
| deploy 找不到凭证 | settings.xml server `id` 须为 `central`（`publishingServerId`） |
| deploy 走 Snapshot Publishing、不出 bundle | 版本仍是 SNAPSHOT——步骤 1 的 sed 未生效（版本字面量已变），核对 `FROM` 后重跑 |
| registry-server 构建解析 artemis-client 失败 | 版本 bump 漏了 registry-server pom 的第 5 处引用，补齐后重跑 |
| Portal 拒收 bundle 内 `_remote.repositories`/`maven-metadata-local` 条目 | central-publishing 插件从本地暂存收集的固有产物（dry-run bundle 实测含此两类）；如遇拒收属插件与 Portal 校验器的兼容问题，升级插件版本并反馈上游，不在本仓库配置层消解 |
| 步骤 6 `git push origin main` 被拒（non-fast-forward） | 发布操作在 worktree/开发分支上执行了——回到前提（主工作区 main），将发布提交合并/重做到 main 后重推 |

## 更新历史

| 版本 | 日期 | 变更说明 |
| ------ | ------ | ------ |
| 1.0 | 2026-10-29 | v1.0 phase-8 初版：发布组件/前置条件/八步流程/故障表 |
