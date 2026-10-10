# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目背景

这是 Artemis 微服务注册中心的**重设计项目**仓库（起点仓库，尚未有代码）。原作者多年前在携程开发的原产品支撑过 10 万+ 服务实例的注册发现，本仓库目标是重新设计开发该产品。

- **原产品仓库（只读参考，勿修改）**：`~/Projects/mydotey/artemis`（Java 8 / Spring Boot 2.3 多模块 Maven 工程）
- **原产品调查文档 `docs/legacy/`**（重设计的需求输入，入口 `product-overview.md`）：分三层——**规格层**（需求语言：product-overview / nfr-spec / `domains/` 域规格与契约制品）、**事实层**（取证：`features.md` 实现清单 / `arch.md` + `arch/` 架构视图集）、**判断层**（取舍依据）。做任何新设计决策前先读判断层 `legacy-product-analysis.md`（可继承资产 / 局限清单）与 `industry-benchmark.md`；需原产品行为取证查事实层，域级需求输入查规格层
- 项目文档与交流使用中文，技术名词保留英文

## 架构基线（原产品）

原产品是 AP 型注册中心：对等节点全对全异步复制 + 租约心跳（「心跳即注册」）+ Eureka 式自我保护 + 静态集群成员；数据面（注册表纯内存）与管理面（DB 持久化流量治理元数据）双轨分离。详见 `docs/legacy/legacy-product-analysis.md` §1–§2。

## 当前状态

设计已定案：架构设计（`docs/arch/artemis-next-architecture.md`，关键决策以 ADR 记录于 `docs/decisions/`）、版本路线图（`docs/product/artemis-next-roadmap.md`）、Java 技术选型与开发规范（`docs/dev/`）均完成评审。仓库尚无源码，开发经 `/dev-java` 工作流按版本路线图推进——进度与 phase 状态的唯一状态源是 `docs/milestones/dev-state.md`，本文件不复述进度与版本细节。首次搭建脚手架后，请更新本文件补充构建/测试命令与架构说明。
