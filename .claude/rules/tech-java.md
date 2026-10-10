# Java 技术选型规则

写 `java/` 目录代码时强制遵守。完整选型与理由见 `docs/dev/tech-stack-java.md`，开发规范见 `docs/dev/coding-guidelines-java.md`——本文只固化硬约束，细节以文档为准。

## 版本与构建

- 服务端模块（artemis-common、各 core、各 server）编译目标 `release=25`；artemis-client 与 spring-boot-starter-artemis-client `release=8`，同一 Maven reactor 混合编译，构建入口 `mvn -f java/pom.xml`。
- client 代码只用 Java 8 语法/API 子集；SDK 工具类自备，不引 Guava 等额外运行时依赖。
- groupId 与包前缀统一 `org.mydotey.ai.artemis`；artifactId 前缀 `artemis-`（例外：parent 为 `artemis`、starter 为 `spring-boot-starter-artemis-client`）。
- 全 reactor 统一版本 `${revision}` + flatten-maven-plugin。

## 模块依赖纪律（enforcer 硬约束）

- core 模块禁 `org.springframework:*`，内核不感知 Spring 类型与配置抽象。
- client 禁依赖 artemis-common 与各 `*-core`/`*-server`；运行时依赖封闭清单：grpc-netty-shaded + grpc-stub + grpc-protobuf + scf-simple + SLF4J API（精确版本见技术选型组件总表）。
- **新增任何依赖组件须先进 `docs/dev/tech-stack-java.md` 组件总表（评审）再进 pom**。
- 跨模块只走 wire 协议序列化，不在模块边界传递 proto 生成类。

## proto

- 契约在仓库顶层 `proto/`，各模块从 `../../proto/<组>` 文件级引用各自生成（client 用 protoc 3.25.x，服务端用 4.x）；wire 兼容与变更流程见 `docs/dev/proto-contract.md`（跨语言单一来源）。
- proto 生成的消息类永不加 Lombok。

## 代码约束

- 格式化 Spotless + Palantir java-format：提交前 `mvn -f java/pom.xml spotless:apply`，CI `spotless:check` 强制，不手工讨论格式。
- Lombok：`@Value`/`@Builder`/`@RequiredArgsConstructor` 优先，日志用 `@Slf4j`；禁 `@Synchronized`。
- 配置一律走 SCF（manager / sources / property 三层抽象）：禁 `System.getProperty` 直读、禁裸 String 配置值进内核；动态/静态分型纪律见开发规范 §10。
- 注册表数据结构用不可变快照 + volatile 发布，读路径无锁；所有 executor 有界 + 命名，禁无界队列、禁 `Thread.sleep` 轮询。
- 服务端日志结构化 JSON；client 仅依赖 SLF4J API，不打日志实现进 SDK。
