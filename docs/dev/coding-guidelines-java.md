# Java 开发规范

版本: 1.22    更新时间: 2026-10-19

> 本文是新一代 Artemis Java 实现的开发规范：包结构、代码风格、并发与线程、错误处理、日志、测试、proto 兼容、SDK 特殊约束与配置管理。技术选型（组件与版本）见[Java 技术选型](tech-stack-java.md)；Git 分支与 commit 规范见 `.claude/rules/git.md`，不在此重复。

## 1. 工程与模块纪律

模块划分与 proto 生成矩阵以[技术选型](tech-stack-java.md) §2 为准，此处固化执行纪律：

- **跨模块传递 proto 生成类以同源 artifact 为界**——服务端 common.v1、projection.v1 与 management.v1 统一生成于 artemis-common，服务端模块间可直接共用；其余多模块各自生成的组（同 FQCN 异 artifact，如 registry.v1 在服务端与 client 各一份）与一切跨进程边界，只走 wire 序列化、不传递生成类。
- **client（artemis-client）不得依赖任何服务端模块**（core/server/common），仅从仓库顶层 `proto/` 目录引用 .proto 文件自行生成。
- **core 模块禁 Spring**（enforcer 强制）：内核不感知 Spring 类型、不读 Spring 配置抽象；配置经普通 Java 配置类注入。
- 服务实现类放 server 模块（实现 core 定义的接口/ proto `ImplBase`），core 只含内核逻辑与契约；**gRPC 服务端用 Spring Boot 官方 starter**（`org.springframework.boot:spring-boot-starter-grpc-server` + `@GrpcService`）自动装配，**禁自写 `Server`/生命周期类**（见[技术选型](tech-stack-java.md) §4.7）。
- **模块内 DDD 四层，领域为核心**（[ADR-015](../../decisions/adr-015-layered-model-inside-core.md)）：接入层（server 承载：协议接入，dto/vo 收发）→ 应用层（core `application`：用例编排 + dto↔domain 转换）→ 领域层（core `domain`）；基础设施层（core `infrastructure`）实现领域端口；`application`/`infrastructure` 依赖 `domain`，**domain 不依赖任何其它层**；领域定义端口、基础设施实现、装配层注入（依赖倒置 + 控制反转）。
- **dto（proto 生成消息）只出现在 server 接入层与 application 的转换边界**（mapper/proto 门面）；**domain 出现 proto 类型即违规**。
- **监控就地定义、不侵入服务接口**：metrics/log/trace 是实现与装配层关注点，**在产生/持有该数据的实现类内就地定义**（如自我保护窗口指标定义在 `SelfProtection`、心跳/清理计数定义在 `RegistryServiceImpl`），不建集中 metrics binder/统计 DTO；领域/应用服务接口只表达业务用例，不暴露监控数据。
- **内部 RPC 通信领域层定义端口、基础设施层/装配层（server）提供实现**（双向）：
  - **入站**：server 模块持有 gRPC/HTTP 端点（proto `ImplBase` 等，即接入层），一行委派 core application 的 proto 门面。
  - **出站**（peer 复制、registry→discovery 分发、discovery↔management 规则流等内部通道）：domain 定义端口接口，具体实现在基础设施层——协议无关编排（扇出、重试/退避策略）可落 core `infrastructure`，gRPC client 传输（channel、stub、`StreamObserver`）放 server 模块。
  - core 源码不 import `io.grpc.*`——传输技术与 Spring 同属装配层关注点，内核纯 Java；proto 消息与 stub 仍在 core 单源生成（避免同 FQCN 异 artifact），core 只需不消费 stub 类型（proto 消息类本身按上条纪律只进 application 转换边界与 server 接入层）。

## 2. 包结构

- 包前缀与 groupId 一致：`org.mydotey.ai.artemis`。
- core 模块内部分三层包，对应 DDD 四层中的应用/领域/基础设施三层（接入层由 server 模块承载），**领域层为核心**（[ADR-015](../../decisions/adr-015-layered-model-inside-core.md)）：

```text
org.mydotey.ai.artemis.registry
├── application/      # 应用层：应用服务（用例编排、契约级校验/归一）+ dto↔domain mapper + proto 门面；复用领域请求/响应模型
├── domain/           # 领域层（核心）：领域模型 + 请求/响应模型 + 聚合与领域语义 + 端口接口；不依赖其它层
└── infrastructure/   # 基础设施层：领域端口实现、调度/配置等技术实现、集群内部通信实现（协议无关部分）
```

- **依赖方向**：`application → domain`、`infrastructure → domain`、接入层（server）→ application；**domain 不依赖 proto、Spring、`ConfigurationSource`，也不依赖 application/infrastructure**；按 D2/D3 定案，domain 可依赖 SCF `StringProperties`/`Property` 并在类内定义配置（配置定义分散到使用方）；application 与 infrastructure 互不依赖。
- **依赖倒置 + 控制反转**：领域定义所需能力的端口接口（TTL 来源、变更事件出口等；时间源用 JDK `Clock`），基础设施实现，装配层（server 的 Spring）注入。
- **命名**：用例入参/出参用 `XxxRequest`/`XxxResponse`（与契约同名同构，**不用 Command/Result**），**定义在领域层、应用层直接使用**（不重复建模型）。
- **层间模型转换**：proto ↔ domain 一个 mapper（在 application），做**同名类按包转换**（`proto.…` 与 `domain.…` 同名类）；主映射由 MapStruct 编译期生成（组件总表），语义性转换（缺省值语义、未知枚举哨兵、结构展开）以显式手写方法补齐；映射规则集中于此，不散落业务代码。
- server 模块承载接入层与装配：`config/`（装配、端口注入）、`grpc/`、`http/`（接入层：协议端点绑定，一行委派 application 的 proto 门面）、`bootstrap/`。
- `domain` 的请求/响应模型是稳定面（变更需过兼容规则 §8）；`application`/`infrastructure` 不对外承诺。

## 3. 代码风格

- 格式化：Spotless + Palantir java-format，提交前分别执行 `mvn -f java/server/pom.xml spotless:apply`（服务端线）与 `mvn -f java/client/pom.xml spotless:apply`（SDK 线），CI `spotless:check` 强制。不做手工格式讨论，formatter 输出即终态。
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

- **不可变快照 + volatile 发布**：service 实例集的当前版本是不可变对象（内部结构构建完成后不再修改），经 `volatile` 引用发布；读侧无锁拿引用（订阅推送直接持有引用，零深克隆——修复原产品局限 #13「getService 深克隆」）；写侧（显式操作/心跳 upsert 产生变更）构建新快照后整体替换。
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

- **错误码驱动容错**（继承资产 #3）：错误码为契约注册表内稳定 string code（wire 走 `ErrorInfo` 消息，单一来源见[proto 契约规范](proto-contract.md) §2）；语言内在 api 包封装常量/枚举，未知码保留原样字符串（SDK 可映射本地 UNKNOWN）；整体性失败（不可进入业务处理）走 gRPC status（`NOT_FOUND`/`FAILED_PRECONDITION`/`RESOURCE_EXHAUSTED`/`UNAVAILABLE`/…），映射表集中定义，客户端按错误码 + status 双维度决策容灾。
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
- 单测覆盖核心机制：租约状态机、心跳续约/缺席补注册（upsert）与变更扇出、自我保护窗口、快照回放、投影合并、过滤器链——这些是 10 万实例正确性的根，必须测；不为覆盖率数字写空转测试。
- **服务级集成测试**（`*IT`，failsafe，经 `dev-test.sh integration`）：进程内起真实服务（随机端口）+ 真实协议客户端驱动端到端用例；不依赖外部容器与外部进程。
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
- **配置所有权边界**：框架与三方组件自有配置（Spring Boot、gRPC starter、actuator、OTLP 等）继续由组件自身配置体系管理；SCF 只管理本产品自定义业务配置，不复制/代理框架键（防双源漂移）。
- **集中与分散边界**：`ConfigurationManager` 与 `StringProperties` 每服务统一装配一份；具体 Property 由**使用它的类**在类内定义（键常量化、默认值、动静类型、校验与消费逻辑同处）。需要配置的类注入 `StringProperties` 后自行取得/定义 property，**不传递单个 `Property`**，不建集中配置门面类。同一 key 全局只允许一个 `PropertyConfig`（SCF manager 按 key 缓存），共享配置以配置值对象统一定义一次。
- 配置键命名 `artemis.<server>.<module>.<item>`，kebab/camel 统一 camel；client 侧前缀 `artemis.client.*`（与 starter 配置项前缀一致）。

### 10.2 动态与静态分型

每个配置项定义时必须显式声明动静类型，二者纪律不同：

| 型 | 定义 | 典型项 | 纪律 |
|---|---|---|---|
| 动态配置 | 运行时可变 | 心跳间隔、租约 TTL、自我保护阈值、复制重试参数、兜底轮询周期 | 用 SCF `Property` 动态语义：`getValue` 每次取最新；变更消费走监听器（listener），**禁止轮询 diff** |
| 静态配置 | 启动后不可变 | 监听端口、集群成员列表、数据目录、快照路径、DB 连接（期 3） | 以 `Property` 字段保存（`setStatic(true)`），使用处 `getValue()`；变更需重启生效（文档明示）；**禁止**临时构造 property 仅取一次值后丢弃 |

### 10.3 合规校验

- 全部配置项必须带校验后才能进入内核：数值项用 `RangeValueFilter` 值域过滤，类型转换用 `TypeConverter`，枚举项校验取值集合；**禁止裸 String 配置值直接进内核逻辑**。
- 校验失败策略分级：启动必需项（端口、成员列表等）**fail-fast**——拒绝启动并给出配置键 + 原因；可降级项回落默认值 + WARN 日志 + 指标计数。
- 配置项默认值必须显式定义在使用该配置的类（键常量旁），禁止散落在调用点。
- **全仓统一审计**：自定义配置（含历史代码）一律走 SCF；`System.getProperty/getenv` 直读、Spring `@Value`/`@ConfigurationProperties`、裸配置常量均须迁移（框架/三方组件自有配置除外，见 §10.1 所有权边界）。
- 静态项创建用 `ScfProperties.staticProperty`（泛型：目标类型 + `TypeConverter` + 值域过滤），long/int/string/boolean 有便捷方法；返回值一律作为 `Property` 字段保存，禁止临时取值后丢弃。

## 11. 版本规范

- 全 reactor 统一版本（`${revision}`），版本号语义化：v0.x 期间 minor 递增，v1.0 起 `MAJOR.MINOR.PATCH`。
- SDK 独立发布节奏（与服务端解耦发版）v1.0 有真实需求时再拆，届时单独决策。
- 依赖升级：安全修复随时进；次要版本升级集中批量，不在功能分支夹带。

## 更新历史

| 版本 | 日期 | 变更说明 |
| ------ | ------ | ------ |
| 1.22 | 2026-10-19 | management.v1 统一生成于 artemis-common（registry 实现 / management 消费，避免 all-in-one 同 JVM 双份生成类） |
| 1.21 | 2026-10-18 | §2 依赖方向明确：domain 不依赖 ConfigurationSource，但可按定案使用 SCF StringProperties/Property |
| 1.20 | 2026-10-18 | §1 修订：监控就地定义（不建集中 binder/统计 DTO），且不侵入服务接口 |
| 1.19 | 2026-10-18 | §1 新增「监控不侵入服务接口」：metrics/log/trace 归实现/装配层，服务接口只表达业务用例 |
| 1.18 | 2026-10-18 | §10.3 补充：静态项用泛型 `ScfProperties.staticProperty` 创建，返回值保存为 Property 字段 |
| 1.17 | 2026-10-18 | §10.2/§10.3 修订（D3）：静态项也以 Property 字段保存、使用处 getValue；新增全仓配置审计纪律 |
| 1.16 | 2026-10-18 | §10.1/§10.3 配置定义边界修订（phase-4 验证期定案）：集中 manager + StringProperties，Property 由使用类在类内定义，不集中定义、不传单个 Property |
| 1.15 | 2026-10-18 | §10.1 新增配置所有权边界（phase-4 设计 review 定案）：框架/三方组件配置仍由组件自身管理，SCF 只管自定义业务配置 |
| 1.14 | 2026-10-13 | ④ 实现期坐标定案：§1 gRPC starter 坐标更正为 `org.springframework.boot:spring-boot-starter-grpc-server`（Spring Boot 4.1 官方模块） |
| 1.13 | 2026-10-13 | code-review 修复：§1 gRPC starter 坐标更正为 `org.springframework.grpc:spring-grpc-server-spring-boot-starter`；更新历史 1.8 行还原 45aebcd 原始记录、phase-2 review 轮次重排、去除重复「合并 main」行 |
| 1.12 | 2026-10-13 | 四次 review 修订：对齐 DDD 四层（ADR-015 v1.3）——接入层由 server 承载（协议接入、dto/vo 收发）、dto↔domain mapper 与 proto 门面移 application、infrastructure 纯化为领域端口实现；内部 RPC 出站实现拆「协议无关编排（core infrastructure）+ gRPC 传输（server）」 |
| 1.11 | 2026-10-12 | v0.1 phase-2 三次 review 定案：§1/§2 改为「领域为核心」三层（domain/application/infrastructure），依赖倒置 + 控制反转，请求/响应模型归领域层由应用层复用，proto↔domain 单一 mapper 在 infrastructure |
| 1.10 | 2026-10-12 | v0.1 phase-2 二次 review 定案：§1 补 gRPC 服务端用官方组件（禁自写 Server）、§2 命名改 `XxxRequest`/`XxxResponse` 与三层同名类按包转换、mapper 归属明确到上游层 |
| 1.9 | 2026-10-12 | v0.1 phase-2 设计 review 定案：§1 补接入层模型纪律、§2 包结构改模块内三层（adapter/application/domain + 层间 mapper，ADR-015）、§7 补服务级集成测试形态（*IT 进程内起真实服务） |
| 1.8 | 2026-10-12 | §1 新增内部 RPC 分层纪律：领域层（core）定义端口接口、infra（server）提供 gRPC 实现，core 源码禁 import `io.grpc.*`（入站/出站双向） |
| 1.7 | 2026-10-12 | code-review 修复：§4/§7 清除「心跳 diff」残留（upsert 变更语义）；§1 projection.v1 入 artemis-common 共享；§5 错误码 wire 消息名 Error→ErrorInfo |
| 1.6 | 2026-10-12 | §5 错误码模型对齐契约定案（string code 注册表 + api 包封装 + gRPC status 分工） |
| 1.5 | 2026-10-11 | §1 proto 类跨模块边界改为「以同源 artifact 为界」：服务端 common.v1 经 artemis-common 共享可直传，其余组与跨进程只走 wire（随 proto 生成模型 server/client 分治定案） |
| 1.4 | 2026-10-10 | review 修复：§3 构建命令补 `-f java/pom.xml`（根目录无 pom）；§8 删除「此处不复制」之后的规则复述与 §1 已有内容的重复条 |
| 1.3 | 2026-10-10 | §8 wire 兼容规则与变更流程上移至 proto 契约规范（跨语言单一来源），本节收窄为 Java 生成代码纪律与 Java API 兼容；§1 契约目录引用改仓库顶层 `proto/` |
| 1.2 | 2026-10-10 | 新增 §10 配置管理规范（SCF）：分层解耦、动态/静态分型纪律、合规校验（fail-fast 分级）；原 §10 版本规范顺移 §11；§9 依赖清单补 SCF |
| 1.1 | 2026-10-10 | review 修复：两处「继承资产 #13」更正为「修复局限 #13」；契约冻结范围补 common.v1；日志规范明确 client 仅 SLF4J API；§9 依赖清单补 grpc-stub/grpc-protobuf |
| 1.0 | 2026-10-10 | 初版：工程纪律、包结构、风格（Palantir + Lombok 纪律）、并发（不可变快照发布）、错误处理、日志、测试、proto 兼容、SDK 约束、版本规范 |
