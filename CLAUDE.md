# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目背景

这是 Artemis 微服务注册中心的**重设计项目**仓库（起点仓库，尚未有代码）。原作者多年前在携程开发的原产品支撑过 10 万+ 服务实例的注册发现，本仓库目标是重新设计开发该产品。

- **原产品仓库（只读参考，勿修改）**：`~/Projects/mydotey/artemis`（Java 8 / Spring Boot 2.3 多模块 Maven 工程）
- **原产品能力基线**：`docs/legacy/legacy-product-analysis.md` —— 重设计的需求输入，包含原产品功能性与非功能性能力全景、12 条可继承设计资产、23 条局限清单。做任何新设计决策前先读它
- 项目文档与交流使用中文，技术名词保留英文

## 架构基线（原产品）

原产品是 AP 型注册中心：对等节点全对全异步复制 + 租约心跳（「心跳即注册」）+ Eureka 式自我保护 + 静态集群成员；数据面（注册表纯内存）与管理面（DB 持久化流量治理元数据）双轨分离。详见 `docs/legacy/legacy-product-analysis.md` §1–§2。

## 当前状态

仓库处于设计起步阶段：无构建系统、无测试、无源码。首次搭建脚手架后，请更新本文件补充构建/测试命令与架构说明。
