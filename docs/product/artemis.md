# Artemis 产品文档

版本: 1.0    更新时间: 2026-10-12

> 本文是 Artemis 的产品文档：产品定位、核心概念、开发者入口与当前交付状态。随版本递补——每个 phase 收尾时同步本版交付的对外可见能力。产品规划与版本路线见[路线图](artemis-next-roadmap.md)，架构与设计决策见[架构设计](../arch/artemis-next-architecture.md)。

## 1. 产品定位

开源服务注册中心，对标 Nacos：具备原产品（支撑过 10 万+ 实例）的核心能力并现代化，单集群 10 万实例级。成功标准与迭代策略见[路线图 §1](artemis-next-roadmap.md#1-定位与成功标准)。

## 2. 核心概念

- **服务注册与发现**：实例向注册中心注册并周期心跳续约，消费方订阅获取服务实例集。
- **namespace**：多租户隔离单元，服务的内禀属性。
- **服务与实例标识**：服务由 `(namespace, service)` 标识（`ServiceKey`）；实例由 `instance_id` 在服务内标识（`InstanceKey`）。
- **实例状态**：客户端自报（服务端不做主动探测）；实例数据由客户端上报，服务端只存事实。

数据模型与操作语义以仓库顶层 `proto/` 契约（单一来源：[proto 契约规范](../dev/proto-contract.md)）为准；本版契约的设计细节见 [phase-1 设计文档](../milestones/v0.1/phases/phase-1-scaffold.md)。

## 3. 开发者入口

### 仓库结构

单 monorepo，顶层按语言分目录：

| 目录 | 内容 |
|---|---|
| `proto/` | 语言无关 proto 契约（六组：`common` 公共数据模型 + `registry`/`replication`/`projection`/`discovery`/`management`），非构建体系、不产 artifact |
| `java/` | Java 实现：Maven 单 reactor，服务端（`release=25`）与 SDK/starter（`release=8`）同一 reactor 混合编译 |

### 构建与测试

```bash
mvn -f java/pom.xml verify                 # 全量构建 + 测试（spotless + enforcer + 单测）
java/scripts/dev-test.sh [unit|integration|all]   # 统一测试入口，默认 all
mvn -f java/pom.xml spotless:apply         # 提交前格式化
```

环境前提：JDK 25（编译/工具进程）与 JDK 8（client 测试 JVM，经 Maven toolchains；模板 `java/toolchains.xml.example`，复制到 `~/.m2/toolchains.xml` 并改 `jdkHome`）。

### 模块

| 模块 | 目标 | 职责 |
|---|---|---|
| `artemis-common` | 25 | 服务端公共规范/工具；proto 生成 `common.v1` + `projection.v1`（服务端共享） |
| `artemis-registry-core` / `-server` | 25 | registry 内核 / Spring Boot 装配 |
| `artemis-discovery-core` / `-server` | 25 | discovery 投影内核 / Spring Boot 装配 |
| `artemis-management-core` / `-server` | 25 | 治理面内核 / Spring Boot 装配 |
| `artemis-client` | 8 | 注册 + 发现统一 SDK（零 Spring） |
| `spring-boot-starter-artemis-client` | 8 | client 的 Spring Boot 集成 |

## 4. 交付状态

当前 **v0.1（registry 单服务）**，进行中；进度以 `docs/milestones/dev-state.md` 为准。

**phase-1（脚手架 + proto 契约骨架）已交付**：`java/` 十模块 Maven reactor 骨架（parent + 各 core/server/client/starter）与 `proto/` 六组契约骨架（v0.1 定稿 `common.v1` + `registry.v1`，其余四组占位）；`mvn -f java/pom.xml verify` 全绿，enforcer 模块边界断言（core 禁 Spring、client 禁服务端模块）生效。注册/心跳、租约、自我保护等业务能力自 phase-2 起交付。

## 更新历史

| 版本 | 日期 | 变更说明 |
| ------ | ------ | ------ |
| 1.0 | 2026-10-12 | 初版（首个收尾 phase 建立）：产品定位、核心概念、开发者入口、交付状态 |
