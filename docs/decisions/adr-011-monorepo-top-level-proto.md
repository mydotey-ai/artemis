# ADR-011：多语言 monorepo，契约居仓库顶层 proto/ 纯目录

版本: 1.0    更新时间: 2026-10-10

## 状态

Accepted（2026-10-10，随 Java 技术选型定案）

## 背景

产品确定多语言路线：Java + Rust 服务端、多语言 SDK。proto 契约的初始工程形态是 `artemis-proto` Maven 模块（packaging=pom，不生成代码）——但该形态把契约绑在 Java 构建体系上，且 protoc 版本被单一 reactor 锁死（client 需 3.25.x、服务端需 4.x）。

## 决策

单 monorepo，顶层按语言分目录：`proto/`（契约）、`java/`（Maven reactor，现行）、`rust/`（v1.x 才创建）、`go/` 等按需。契约居仓库顶层 `proto/` 纯目录——不属于任何构建体系、不产 artifact，各语言实现从 .proto 文件各自生成代码；`artemis-proto` Maven 模块取消。

## 理由

- 契约变更一次 commit 原子带动全部语言实现（多仓库存在漂移窗口）。
- 各端 protoc 与编译版本自由（client Java 8 + protoc 3.25.x，服务端 Java 25 + protoc 4.x）。

## 否决方案

- 契约独立仓库 + 各语言实现仓库：版本对齐负担、漂移窗口。
- proto 共享 artifact：protoc 版本被锁死，无法按端分叉。

## 后果

- 同一 proto 组多端各生成一份同 FQCN 类，跨模块/跨端只走 wire 序列化（[proto 契约规范](../dev/proto-contract.md)为跨语言单一来源）。
- 工程落地形态见[Java 技术选型](../dev/tech-stack-java.md) §2；CI 按路径触发（proto/** 变更跑全量）。

## 更新历史

| 版本 | 日期 | 变更说明 |
| ------ | ------ | ------ |
| 1.0 | 2026-10-10 | 自架构文档 §7 决策表 D11 迁移成文 |
