# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目背景

这是 Artemis 微服务注册中心的**重设计项目**仓库。原作者多年前在携程开发的原产品支撑过 10 万+ 服务实例的注册发现，本仓库目标是重新设计开发该产品。

- **原产品仓库（只读参考，勿修改）**：`~/Projects/mydotey/artemis`（Java 8 / Spring Boot 2.3 多模块 Maven 工程）
- **原产品调查文档 `docs/legacy/`**（重设计的需求输入，入口 `product-overview.md`）：分三层——**规格层**（需求语言：product-overview / nfr-spec / `domains/` 域规格与契约制品）、**事实层**（取证：`features.md` 实现清单 / `arch.md` + `arch/` 架构视图集）、**判断层**（取舍依据）。做任何新设计决策前先读判断层 `legacy-product-analysis.md`（可继承资产 / 局限清单）与 `industry-benchmark.md`；需原产品行为取证查事实层，域级需求输入查规格层
- 项目文档与交流使用中文，技术名词保留英文
- **规则优先级**：工作流（`.claude/commands/`）针对具体步骤的明确要求优先于 `.claude/rules/` 的通用规则；两者冲突时以工作流为准（例：`/dev-java` ⑪.3 的 squash 后 push main 是工作流既定动作，不受 git.md「仅在用户明确要求时执行」约束）

## 架构基线（原产品）

原产品是 AP 型注册中心：对等节点全对全异步复制 + 租约心跳（「心跳即注册」）+ Eureka 式自我保护 + 静态集群成员；数据面（注册表纯内存）与管理面（DB 持久化流量治理元数据）双轨分离。详见 `docs/legacy/legacy-product-analysis.md` §1–§2。

## 当前状态

设计已定案：架构设计（`docs/arch/artemis-next-architecture.md`，关键决策以 ADR 记录于 `docs/decisions/`）、版本路线图（`docs/product/artemis-next-roadmap.md`）、Java 技术选型与开发规范（`docs/dev/`）均完成评审。脚手架（v0.1 phase-1）已建成 `java/` Maven reactor（`server/` 服务端线 + `client/` SDK 线两目录分组，SDK 独立 parent/版本/依赖管理；`java/pom.xml` 为纯聚合器）与 `proto/` 六组契约骨架，开发经 `/dev-java` 工作流按版本路线图推进——进度与 phase 状态的唯一状态源是 `docs/milestones/dev-state.md`，本文件不复述进度与版本细节。产品文档见 `docs/product/artemis.md`。

## 构建与测试（Java）

monorepo 顶层按语言分目录：`proto/`（语言无关契约，六组）、`java/`（Maven 单 reactor）。根目录无聚合构建文件，各语言构建入口在各自目录：

```bash
mvn -f java/pom.xml verify                        # 全量构建 + 测试（spotless + enforcer + 单测）
java/scripts/dev-test.sh [unit|integration|all]   # 统一测试入口，默认 all（工作流 ⑤/⑧ 验收经此）
mvn -f java/pom.xml spotless:apply                # 提交前格式化
```

- 服务端模块（common、各 core/server）编译目标 `release=25`；client 与 starter `release=8`，同一 reactor 混合编译。
- 环境前提：JDK 25（编译/工具进程）+ JDK 8（client 测试 JVM，经 Maven toolchains，模板 `java/toolchains.xml.example` → `~/.m2/toolchains.xml`）。
- 模块边界由 enforcer 硬约束（core 禁 Spring、client 禁服务端模块），详见 `.claude/rules/tech-java.md`。
