# ADR-015：模块内分层（领域为核心）与层间模型转换

版本: 1.7    更新时间: 2026-10-14

## 状态

Accepted（2026-10-12 v0.1 phase-2 设计 review 定案；2026-10-13 修订为 DDD 四层对齐；接口命名相应由 `Registry` 定为 `RegistryService`）

## 背景

原产品的公共数据模型（`artemis-common` 可变 POJO）直接贯穿 REST/WS 接入层、服务内核与客户端 SDK，契约类型即实现类型（能力基线 §2.1、局限 #23「可变 POJO + 混杂命名 + 字符串常量代枚举」）。重设计沿用「契约即模型」的便利（phase-2 设计初稿曾定内核 API 直接以 proto 请求/响应消息为输入输出），review 否决：**外部协议模型与内部实现模型同体，会把 wire 契约的演进约束与变更影响面直接压到内核与领域逻辑上**，不利于产品长期发展。

## 决策

按 **DDD 四层**组织（registry-core 先行落地，其余 core 与 client 随各自 phase 遵循），**领域层为核心**：

| DDD 层 | 物理落点 | 职责 |
|---|---|---|
| 接入层 | server 模块 | http/gRPC 等协议接入（端点绑定、`@GrpcService`）；dto/vo（proto 生成消息）收发 |
| 应用层 | core `application` 包 | 用例编排、契约级校验/归一；**接口层模型（dto）与领域模型互相转换**（mapper + proto 门面） |
| 领域层 | core `domain` 包 | 实体/值对象、聚合根与领域服务、请求/响应模型、**端口接口**；不依赖其它层 |
| 基础设施层 | core `infrastructure` 包 + server 内出站传输 | 对领域层定义的接口做具体实现，经 IoC 依赖倒置注入上层 |

```text
org.mydotey.ai.artemis.<service>
├── application/      应用层：应用服务（用例编排、契约级校验/归一）+ dto↔domain mapper + proto 门面
├── domain/           领域层（核心）：领域模型 + 请求/响应模型 + 聚合与领域语义 + 端口接口；不依赖其它层
└── infrastructure/   基础设施层：领域端口实现、调度/配置等技术实现、集群内部通信实现（协议无关部分）
```

- **依赖方向**：`application → domain`、`infrastructure → domain`、接入层（server）→ application；**领域层不依赖任何其它层**（不依赖 proto、Spring、配置源）；application 与 infrastructure 互不依赖。
- **依赖倒置 + 控制反转**：领域层涉及具体实现的能力只定义端口接口（TTL 提供 `LeaseTtlSource`、变更事件出口 `ChangeListener`、租约清理调度 `LeaseEvictionScheduler`、时长判定 `MonotonicClock`、跨进程时间戳用 JDK `java.time.Clock`），由基础设施层实现、装配层（server 的 Spring）注入。
- **请求/响应模型定义在领域层**（`XxxRequest`/`XxxResponse`，不用 Command/Result 命名），**应用层直接使用**，不重复建模型。
- **dto（proto 生成消息）只出现在 server 接入层与 application 的转换边界**（mapper/门面）；**domain 不出现 proto 类型**。同名模型以包名区分（`proto.…` / `domain.…`）。
- **层间 mapper**：proto ↔ domain 一个 mapper（在 application）；主映射由 **MapStruct** 编译期生成，语义性转换（缺省值语义、未知枚举哨兵、结构展开）以显式手写方法补齐。
- **传输绑定在 server**：gRPC 服务端用 Spring Boot 4.1 官方 starter（`org.springframework.boot:spring-boot-starter-grpc-server` + `@GrpcService`），不自写 `Server`/生命周期；`ImplBase` 一行委派 application 的 proto 门面。core 不感知网络。
- **集群内部节点间通信（数据复制、同步）同此模式**：领域层定义出站端口，基础设施层做具体实现（扇出/重试等协议无关编排在 core `infrastructure`；gRPC 传输绑定受 enforcer「core 禁 `io.grpc.*`」约束在 server 模块）。
- **契约级校验/归一在应用层**（信任边界一次的落点）；领域层不再重复防御（开发规范 §5）。

## 理由

- proto 契约的变更影响面收敛在 application 的 mapper 一处，领域与应用编排逻辑不发散（wire 冻结与内部重构解耦）。
- 领域模型可用语言惯用类型（record、枚举、`Optional`），不受 protobuf 生成代码的可变 builder / 缺省值语义牵制（原产品局限 #23 的直接修复）。
- 四层职责正交：协议接入（接入层）、模型转换 + 用例编排（应用层）、领域语义（领域层）、技术实现（基础设施层）各归其位——v1.2 的三层方案把协议接入塞进 infrastructure，与「领域端口的具体实现」混装；按标准 DDD 修正。
- 领域为核心 + 端口由领域定义（依赖倒置）：领域可独立测试与演进，与配置源、网络、调度、集群复制等技术细节解耦。
- 参照现代 Java 服务实践（DDD 四层/整洁架构/COLA Convertor 模式）；成本是 proto 与领域模型间一组 mapper 与往返测试。

## 否决方案

- **契约模型直接贯穿内核**（phase-2 设计初稿）：省一层映射，但把 wire 约束压入领域，长期演进代价大（首次 review 否决点）。
- **三层 + 协议接入归 infrastructure**（v1.2 曾定案）：基础设施层混装「协议接入」与「领域端口实现」两类职责，dto ↔ domain 转换错层（应在应用层）；本次 review 按标准 DDD 修正。
- **core 零 proto、接入层与转换整体移入 server**：分层更彻底，但一服务 proto 组需在 server 生成，配置与生成矩阵改动面大；proto 生成类留在 core 单源生成、仅约束消费位置，成本更低。
- **应用层另立一套 Command/Result 模型**：多一层模型与一组 mapper，且应用层与领域层的用例契约重复；应用层复用领域 request/response。

## 后果

- 开发规范 §2 包结构重写（core 内 `api/`+`internal/` → `application/`+`domain/`+`infrastructure`）；§1 补「dto 只进 server 接入层与 application 转换边界，domain 出现 proto 即违规」「gRPC 服务端用官方组件」纪律。
- 技术选型组件总表新增 MapStruct（编译期 mapper 生成，须与 Lombok 协同）与 Spring gRPC（服务端装配）。
- registry 内核四操作经 mapper 往返：影响面新增加 mapper 单测与「起服务」的端到端集成测试（v0.1 phase-2 内交付 gRPC 装配）。
- 集群复制（v0.2）按「领域定义出站端口、基础设施层实现」先例落地。
- 后续 discovery/management core 与 client SDK 按同一分层落地（client 为 Java 8 子集，mapper 实现方式在 phase-6 定）。
- 架构分层概念说明（§3.1）同步四层；ADR 总数 +1。

## 更新历史

| 版本 | 日期 | 变更说明 |
| ------ | ------ | ------ |
| 1.7 | 2026-10-14 | 端口枚举补时长判定 `MonotonicClock`（phase-2 D14：一切内部时长判定单调钟化，serverTimeMs 保留 JDK Clock） |
| 1.6 | 2026-10-14 | 端口枚举补租约清理调度 `LeaseEvictionScheduler`（domain 定义接口、infrastructure 实现，phase-2 ⑨ 设计反馈定案，取代「调度器整体归装配层组合」的临时处理） |
| 1.5 | 2026-10-13 | ④ 实现期坐标定案：gRPC starter 更正为 Spring Boot 4.1 官方模块 `org.springframework.boot:spring-boot-starter-grpc-server`（spring-grpc 自身 starter 止于 1.0.3，SB 4.0 起收编官方） |
| 1.4 | 2026-10-13 | code-review 修复：gRPC starter Maven 坐标更正为 `org.springframework.grpc:spring-grpc-server-spring-boot-starter` |
| 1.3 | 2026-10-13 | 四次 review 修订：对齐标准 DDD 四层——接入层独立（server 承载，dto/vo 收发）、dto↔domain 转换归应用层（mapper/门面从 infrastructure 移 application）、基础设施层纯化为领域端口实现；登记集群内部通信（复制/同步）「领域定义端口、基础设施实现」先例 |
| 1.2 | 2026-10-12 | 三次 review 修订：改为「领域为核心」三层（domain/application/infrastructure），依赖倒置 + 控制反转；请求/响应模型归领域层、应用层直接复用；接入层归 infrastructure，单一 mapper |
| 1.1 | 2026-10-12 | 二次 review 修订：应用层命名改 `XxxRequest`/`XxxResponse`；明确三层同名模型以包名区分、mapper 同名转换；传输绑定补 Spring Boot 官方 gRPC 组件 |
| 1.0 | 2026-10-12 | v0.1 phase-2 设计 review 定案成文 |
