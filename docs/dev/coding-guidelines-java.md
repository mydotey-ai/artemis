# Java 开发规范

版本: 1.4    更新时间: 2026-10-10

> 本文是新一代 Artemis Java 实现的开发规范：包结构、代码风格、并发与线程、错误处理、日志、测试、proto 兼容、SDK 特殊约束与配置管理。技术选型（组件与版本）见[Java 技术选型](tech-stack-java.md)；Git 分支与 commit 规范见 `.claude/rules/git.md`，不在此重复。

## 1. 工程与模块纪律

模块划分与 proto 生成矩阵以[技术选型](tech-stack-java.md) §2 为准，此处固化执行纪律：

- **跨模块只走 wire 协议，绝不在模块边界传递 proto 消息类**——同一 proto 组在多模块生成的类是不同 artifact 的同名类，二进制不共享；模块间数据交换一律序列化。
- **client（artemis-client）不得依赖任何服务端模块**（core/server/common），仅从仓库顶层 `proto/` 目录引用 .proto 文件自行生成。
- **core 模块禁 Spring**（enforcer 强制）：内核不感知 Spring 类型、不读 Spring 配置抽象；配置经普通 Java 配置类注入。
- 服务实现类放 server 模块（实现 core 定义的接口/ proto `ImplBase`），core 只含内核逻辑与契约。

## 2. 包结构

- 包前缀与 groupId 一致：`org.mydotey.ai.artemis`。
- core 模块内部分层：

```text
org.mydotey.ai.artemis.registry
├── api/          # 对外契约：接口、数据模型、错误码（client/server 均可依赖的部分）
└── internal/     # 内核实现：租约、复制、快照、自我保护等
```

- server 模块按装配职责组织：`config/`、`grpc/`、`http/`、`bootstrap/`。
- api 包内的类型是稳定面，变更需过兼容规则（§8）；internal 包不对外承诺。

## 3. 代码风格

- 格式化：Spotless + Palantir java-format，提交前 `mvn -f java/pom.xml spotless:apply`，CI `spotless:check` 强制。不做手工格式讨论，formatter 输出即终态。
- 命名：类名 UpperCamelCase、方法/变量 lowerCamelCase、常量 UPPER_SNAKE_CASE；布尔方法 `isXxx`/`hasXxx`；避免缩写（除 `id`、`url` 等公认项）。
- **Lombok 使用纪律**（全项目启用，`provided` scope）：
  - 值对象/配置类：`@Value`、`@Builder`、`@RequiredArgsConstructor` 优先；
  - 可变数据载体（内核内部）：`@Data` 允许，api 包慎用（`@Data` 含 `equals/hashCode`，契约类需显式定义语义）；
  - 日志用 `@Slf4j`；
  - 禁 `@Synchronized`（与并发规范冲突，统一用显式并发原语）；
  - proto 生成的消息类永不加 Lombok。
- 注释：公共 API（api 包、SDK 面）写 javadoc（一句话用途 + 关键语义）；internal 实现按需，解释「为什么」而非「是什么」。刻意简化留 `ponytail:` 注释注明天花板与升级路径。
- Java 25 端优先用语言新特性替代样板：record 定义不可变数据（与 §4 快照发布模式配合）、switch 模式匹配、text block 写 SQL/JSON。

## 4. 并发与线程规范

### 4.1 注册表数据结构

- **不可变快照 + volatile 发布**：service 实例集的当前版本是不可变对象（内部结构构建完成后不再修改），经 `volatile` 引用发布；读侧无锁拿引用（订阅推送直接持有引用，零深克隆——修复原产品局限 #13「getService 深克隆」）；写侧（心跳 diff）构建新快照后整体替换。
- 禁止在读路径加锁、禁止 `Collections.unmodifiableXxx` 包装可变底层后继续写底层（假不可变）。
- 聚合统计（自我保护窗口计数）用 `LongAdder`/并发队列，不用 `AtomicLong` 高频自旋。

### 4.2 线程使用规则

| 场景 | 用法 |
|---|---|
| gRPC handler（心跳、订阅、复制、查询） | 虚拟线程 executor（技术选型 §1） |
| 定时任务（租约清理、快照落盘） | `ScheduledExecutorService`（平台线程，每任务命名） |
| 后台异步扇出（变更事件复制） | 有界队列 + 专属 executor + 拒绝策略记录指标 |

- 虚拟线程内禁止长 CPU 计算（占住 carrier）；CPU 密集逻辑显式放平台线程池。
- 禁 `Thread.sleep` 轮询等待（用 `CompletableFuture`/条件变量/调度）。
- 禁无界队列；所有 executor 必须有界 + 命名（thread factory 带模块前缀，排障可认领）。
- 锁只保护短临界区；跨网络调用不得持锁。

## 5. 错误处理

- **错误码驱动容错**（继承资产 #3）：错误码是 api 包内的 enum，稳定编号；每类错误映射固定 gRPC status（`NOT_FOUND`/`FAILED_PRECONDITION`/`RESOURCE_EXHAUSTED`/`UNAVAILABLE`/…），映射表集中定义，客户端按错误码 + status 双维度决策容灾。
- 内核不吞异常：捕获必须记录日志并携带上下文（service、instance 标识），转错误码向上抛或计入指标；禁止空 catch。
- 对外（SDK/console）错误一律错误码 + 可读 message，不泄露堆栈；堆栈只进日志。
- 前置校验在信任边界（外部 API 入口）一次完成，内核不做重复防御。

## 6. 日志规范

- 服务端结构化 JSON（logback + logstash-logback-encoder），第一天生效；client 仅依赖 SLF4J API，不打日志实现进 SDK（宿主自带日志实现，避免 multiple SLF4J providers 冲突）。
- 级别语义：`ERROR` 需要人介入；`WARN` 自愈但需关注（复制重试、保护态触发）；`INFO` 状态变迁（节点上下线、配置变更）；`DEBUG` 热路径细节。
- 热路径（每心跳/每推送）日志一律 `DEBUG` + 占位符（不拼接字符串）；`INFO` 级不得出现在稳态心跳路径。
- 每条日志携带结构化键：`service`、`namespace`、`instanceId`、`cluster` 按相关性附带，便于检索。

## 7. 测试规范

- 命名：`XxxTest`（单测）、`XxxIT`（集成）；测试方法 `should_<期望>_when_<条件>`。
- 单测覆盖核心机制：租约状态机、心跳 diff/扇出、自我保护窗口、快照回放、投影合并、过滤器链——这些是 10 万实例正确性的根，必须测；不为覆盖率数字写空转测试。
- 集群语义测试用**进程内多实例**（随机端口组集群），不起容器；Testcontainers 只用于期 3 DB 与 v1.0 K8s 验收。
- 每个版本的验收要点（路线图各版本末条）须有对应可执行测试，随版本一起交付。
- 测试代码同样过 Spotless/Checkstyle；测试里允许 `@Data` 等 Lombok 简化。

## 8. proto 与 API 兼容规范

proto 的 wire 兼容规则与变更流程是**跨语言不变量**，单一来源见[proto 契约规范](proto-contract.md)，此处不复制。Java 侧补充：

- proto 生成的消息类永不加 Lombok（§3）。
- Java API（api 包/SDK 公共类）变更：新增标 `@since`；弃用先 `@Deprecated`（给替代方案）至少一个 minor 版本再删。

## 9. SDK（artemis-client / starter）特殊规范

- **Java 8 语法子集**：不用 Java 8 以上语法/ API；SDK 内工具类自备，不引 Guava 等额外运行时依赖（依赖纪律：grpc-netty-shaded + grpc-stub + grpc-protobuf（传递 protobuf-java 3.25.x）+ SCF（scf-simple）+ SLF4J API，见技术选型 §5）。
- 公共 API 即产品承诺：可见性最小化（只公开必须的），内部实现类放 `internal` 子包。
- 回调有界队列（修复原产品局限 #13「单线程无界队列，慢消费者拖垮全部通知」）：订阅回调在 SDK 专属 executor 执行，队列有界、满时按配置丢弃最旧 + 计数，不阻塞推送线程。
- 生命周期 API（close/优雅下线）是 v0.1 交付物，资源（连接、线程、磁盘快照句柄）在 close 全量释放，禁止遗留非 daemon 线程。
- starter 自动装配双注册（`AutoConfiguration.imports` + `spring.factories`），配置项前缀统一 `artemis.client.*`。

## 10. 配置管理规范（SCF）

选型依据见[技术选型](tech-stack-java.md) §4.5；本节为使用纪律。

### 10.1 分层与解耦

- `ConfigurationManager` 管理配置源集合，`ConfigurationSource` 按优先级叠加（默认序：系统属性 > 环境变量 > 配置文件；远端源后置版本按需追加）；业务代码只依赖 `Property` 抽象，**禁止**绕过 manager 直接读源、直接 `System.getProperty`。
- 配置项定义集中：每服务一个（或按域少数几个）配置门面类（原产品 `facade.StringProperties` 同模式），配置键全部常量化；禁止配置键字符串散落业务代码。
- 配置键命名 `artemis.<server>.<module>.<item>`，kebab/camel 统一 camel；client 侧前缀 `artemis.client.*`（与 starter 配置项前缀一致）。

### 10.2 动态与静态分型

每个配置项定义时必须显式声明动静类型，二者纪律不同：

| 型 | 定义 | 典型项 | 纪律 |
|---|---|---|---|
| 动态配置 | 运行时可变 | 心跳间隔、租约 TTL、自我保护阈值、复制重试参数、兜底轮询周期 | 用 SCF `Property` 动态语义：`getValue` 每次取最新；变更消费走监听器（listener），**禁止轮询 diff** |
| 静态配置 | 启动后不可变 | 监听端口、集群成员列表、数据目录、快照路径、DB 连接（期 3） | 启动时取值一次、固化为 `final`；变更需重启生效（文档明示）；**禁止**用动态 `Property` 读取静态项（防隐式可变） |

### 10.3 合规校验

- 全部配置项必须带校验后才能进入内核：数值项用 `RangeValueFilter` 值域过滤，类型转换用 `TypeConverter`，枚举项校验取值集合；**禁止裸 String 配置值直接进内核逻辑**。
- 校验失败策略分级：启动必需项（端口、成员列表等）**fail-fast**——拒绝启动并给出配置键 + 原因；可降级项回落默认值 + WARN 日志 + 指标计数。
- 配置项默认值必须显式定义在门面类，禁止散落在调用点。

## 11. 版本规范

- 全 reactor 统一版本（`${revision}`），版本号语义化：v0.x 期间 minor 递增，v1.0 起 `MAJOR.MINOR.PATCH`。
- SDK 独立发布节奏（与服务端解耦发版）v1.0 有真实需求时再拆，届时单独决策。
- 依赖升级：安全修复随时进；次要版本升级集中批量，不在功能分支夹带。

## 更新历史

| 版本 | 日期 | 变更说明 |
| ------ | ------ | ------ |
| 1.4 | 2026-10-10 | review 修复：§3 构建命令补 `-f java/pom.xml`（根目录无 pom）；§8 删除「此处不复制」之后的规则复述与 §1 已有内容的重复条 |
| 1.3 | 2026-10-10 | §8 wire 兼容规则与变更流程上移至 proto 契约规范（跨语言单一来源），本节收窄为 Java 生成代码纪律与 Java API 兼容；§1 契约目录引用改仓库顶层 `proto/` |
| 1.2 | 2026-10-10 | 新增 §10 配置管理规范（SCF）：分层解耦、动态/静态分型纪律、合规校验（fail-fast 分级）；原 §10 版本规范顺移 §11；§9 依赖清单补 SCF |
| 1.1 | 2026-10-10 | review 修复：两处「继承资产 #13」更正为「修复局限 #13」；契约冻结范围补 common.v1；日志规范明确 client 仅 SLF4J API；§9 依赖清单补 grpc-stub/grpc-protobuf |
| 1.0 | 2026-10-10 | 初版：工程纪律、包结构、风格（Palantir + Lombok 纪律）、并发（不可变快照发布）、错误处理、日志、测试、proto 兼容、SDK 约束、版本规范 |
