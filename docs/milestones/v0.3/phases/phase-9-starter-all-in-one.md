# Phase 9：发现侧 starter 与 all-in-one 打包

版本: 1.3    更新时间: 2026-10-24

## 目标

交付发现侧 Spring Boot starter 自动装配、registry + discovery all-in-one 单进程双端口打包，并收口 v0.3 验收场景（推送亚秒、断连自愈、多集群 union、all-in-one 单包起）。

## 范围

做什么（据 plan phase-9 说明）：

- 发现侧 starter 自动装配（与注册侧共存、按配置条件装配；`artemis.client.*` 配置、生命周期、`MeterRegistry` 注入）。
- all-in-one 打包：同进程起 registry + discovery 两个 server、各自端口（架构 §2.1 统一网络协议，不做进程内直连优化）。
- v0.3 验收场景端到端收尾。

不做什么：

- v0.4 治理面（management DB / console / 完整规则链）。
- 重写 phase-8 SDK 内部实现（只做 starter 装配与必要的最小适配）。
- 数据面契约变更（除非 ③ 设计发现必要缺口，须显式登记）。

## 涉及模块

| 模块 | 内容 |
|---|---|
| `java/client/spring-boot-starter-artemis-client` | 发现侧自动装配：既有 `ArtemisClientAutoConfiguration` 内补 `DiscoveryClient` bean，注册/发现两侧按配置条件共存 |
| `java/server/artemis-dist`（新增，服务端线 / Java 25） | all-in-one 单包入口：同 JVM 起 registry + discovery 两个 Spring 上下文、各自端口；打包为可执行 fat jar |
| `java/server/pom.xml` | 聚合新增 `artemis-dist` 模块 |
| `java/server/artemis-registry-server`、`java/server/artemis-discovery-server` | 不加新能力，仅被 dist 组合；`PeerServerConfig.port()` 作为 registry 内部端口的组合期读取点 |
| `proto/` | 无变更（复用全部既有契约） |

## 验收方式

对应测试文档：[phase-9-starter-all-in-one-test.md](phase-9-starter-all-in-one-test.md)

## 产品设计（③ 补）

### 1. 两条交付线

1. **starter 线**（client/，Java 8）：注册侧 + 发现侧共存的自动装配。不改 SDK 内部实现、不加依赖、不改配置键，只补装配条件与 discovery bean。
2. **all-in-one 线**（server/，Java 25）：新增 `artemis-dist` 模块，把 registry-server 与 discovery-server **作为两个独立 Spring 上下文**在同一 JVM 启动，彼此仍走网络协议（ADR-001：不做进程内直连优化）。二者端口全部独立。

### 2. 发现侧 starter 自动装配

#### 2.1 装配结构

沿用既有自动配置类 `ArtemisClientAutoConfiguration`（类名不变，phase-8 D-C10），单份 `StringProperties` 由注册/发现共享：

```java
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass({RegistryClient.class, DiscoveryClient.class})
@ConditionalOnProperty(prefix = "artemis.client", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class ArtemisClientAutoConfiguration {

    @Bean(name = "artemisClientStringProperties")
    @ConditionalOnMissingBean(name = "artemisClientStringProperties")  // 同名 bean 即接管配置桥
    public StringProperties artemisClientStringProperties(Environment environment);

    @Bean(name = "artemisRegistryClient", initMethod = "start", destroyMethod = "close")
    @ConditionalOnMissingBean(RegistryClient.class)
    @ConditionalOnProperty(prefix = "artemis.client.registry", name = "addresses")
    public RegistryClient artemisRegistryClient(
            @Qualifier("artemisClientStringProperties") StringProperties properties,
            ObjectProvider<MeterRegistry> meterRegistries);

    @Bean(name = "artemisDiscoveryClient", initMethod = "start", destroyMethod = "close")
    @ConditionalOnMissingBean(DiscoveryClient.class)
    @ConditionalOnProperty(prefix = "artemis.client.discovery", name = "addresses")
    public DiscoveryClient artemisDiscoveryClient(
            @Qualifier("artemisClientStringProperties") StringProperties properties,
            ObjectProvider<MeterRegistry> meterRegistries);

    @Bean
    public ApplicationListener<ContextClosedEvent> artemisClientShutdownListener(
            ObjectProvider<RegistryClient> registryClients,
            ObjectProvider<DiscoveryClient> discoveryClients);
}
```

- 两个 client 共用同一 `StringProperties`/`ConfigurationManager`（同 key 单一 `PropertyConfig`）与同一个 `MeterRegistry` 选择逻辑（宿主唯一 `MeterRegistry`，否则 `Metrics.globalRegistry`）。
- 生命周期与注册侧一致：`initMethod=start`；`ContextClosedEvent` 第一时机先关注册侧（注销）再关发现侧；`destroyMethod=close` 幂等兜底。
- 发现侧只创建并启动 client，**不代建订阅**——选择器与订阅时机是宿主业务决定（phase-8 D-C2）。

#### 2.2 条件语义（对 v0.1 语义的显式修订）

| 配置 | 结果 |
|---|---|
| 配了 `artemis.client.registry.addresses` | 创建并启动 `RegistryClient` |
| 配了 `artemis.client.discovery.addresses` | 创建并启动 `DiscoveryClient` |
| 两者都配 | 两个 client 共存，互不影响 |
| 两者都未配 | 不创建任何 client，宿主正常启动 |
| `artemis.client.enabled=false` | 两侧都不装配 |

**语义变更**：v0.1 的「引入 starter 即注册意图、缺 `registry.addresses` 启动 fail-fast」不再成立——starter 现在是双用途，只做发现的消费方不应被迫配置 registry。地址**已配置但非法**仍在 client 构造期 fail-fast（错误定位不变）。此变更需同步 `docs/api/sdk-java.md`、`docs/api/sdk-java-discovery.md` 与产品文档；同时修正 guidelines §9 中「自定义 `ArtemisClient` bean」的过时措辞（改为 `RegistryClient`/`DiscoveryClient`）。

#### 2.3 宿主覆盖

- 宿主定义自己的 `RegistryClient` / `DiscoveryClient` bean 时，对应自动配置 bean 让位（按类型 `@ConditionalOnMissingBean`，两侧独立）。
- `StringProperties` 配置桥**按名让位**（⑨ 人工验证定案，修订初版「starter 独占创建」）：宿主定义**同名** bean `artemisClientStringProperties` 即接管 SCF 配置源链，client 按名注入自动跟随接管 bean；宿主定义**异名** `StringProperties` bean（其它用途）时 starter 桥照常创建、二者共存，client 经 `@Qualifier("artemisClientStringProperties")` 按名绑定 starter 桥，不误注入宿主无关 bean。

### 3. all-in-one 打包

#### 3.1 模块与入口

新增 `java/server/artemis-dist`（parent = `artemis-server-parent`，Java 25，`artifactId=artemis-dist`）：

```text
java/server/artemis-dist/
├── pom.xml
├── src/main/java/org/mydotey/ai/artemis/dist/
│   ├── ArtemisAllInOneServer.java      # main + Node 句柄
│   ├── AllInOneOptions.java            # 组合端口（不可变，builder + defaults）
│   └── AllInOneConfig.java             # SCF：artemis.dist.* 静态配置 → AllInOneOptions
├── src/main/resources/
│   ├── artemis-all-in-one-registry.properties
│   └── artemis-all-in-one-discovery.properties
└── src/test/java/org/mydotey/ai/artemis/dist/AllInOneIT.java
```

```java
public final class ArtemisAllInOneServer {
    /** 生产入口：组合端口由 AllInOneConfig（SCF，artemis.dist.*）提供。 */
    public static void main(String[] args);
    /** 组合启动（测试/嵌入用）：registry 先起，读实际 peer 端口后起 discovery；失败时回滚已起上下文。 */
    public static Node start(AllInOneOptions options);
    /** 双上下文句柄：暴露实际端口；close 幂等并按 discovery → registry 顺序关闭。 */
    public static final class Node implements AutoCloseable {
        ConfigurableApplicationContext registry();
        ConfigurableApplicationContext discovery();
        int registryGrpcPort();  int registryHttpPort();
        int discoveryGrpcPort(); int discoveryHttpPort();
        @Override void close();
    }
}
```

- 打包：`spring-boot-maven-plugin` `repackage` + `classifier=exec`，显式 `mainClass=org.mydotey.ai.artemis.dist.ArtemisAllInOneServer`（classpath 上存在两个 `@SpringBootApplication`，必须显式指定）；产物 `artemis-dist-<version>-exec.jar` 即「单包起」。
- 依赖：`artemis-registry-server` + `artemis-discovery-server`（compile）。两者共享 `artemis-common` 单份 proto 生成类（projection.v1 集中生成的设计正是为此形态去重），无 FQCN 冲突。

#### 3.2 启动 / 停止时序

```text
main → AllInOneConfig(SCF) → start(options)
  1. 起 registry 上下文（spring.config.name=artemis-all-in-one-registry）
  2. 从 registry 上下文取 PeerServerConfig.port()（唯一事实源，不猜默认值）
  3. 起 discovery 上下文（spring.config.name=artemis-all-in-one-discovery）
     · 上游集群固定为同进程 registry：artemis.discovery.registry.clusters={"default":"127.0.0.1:<peerPort>"}
  4. 返回 Node；注册单个 JVM shutdown hook（两上下文均 registerShutdownHook=false）
     关闭序：discovery → registry（幂等）
```

- registry 空表即 ready，discovery 首轮 bootstrap 空表即 SYNCED，因此冷启动无互相等待。
- 启动第 3 步失败：关闭已起的 registry 上下文后抛出，不留半启进程。
- 两上下文共用工作目录 `artemis.properties`（各自 SCF source 已支持）：`artemis.registry.*` 与 `artemis.discovery.*` 键各取所需；`artemis.dist.*` 只被 dist 读取。

#### 3.3 端口与配置边界

| 服务 | gRPC | HTTP | 管理端口 | 其他 |
|---|---|---|---|---|
| registry | 9090 | 8080 | 9091 | 内部 peer 9093（all-in-one 配置绑 `127.0.0.1`） |
| discovery | 9095 | 8090 | 9096 | 节点目录默认 `127.0.0.1:9095` |

- **端口由 launcher 以命令行参数注入**各上下文（`--spring.grpc.server.port` / `--server.port` / `--management.server.port` / registry 的 `--artemis.registry.internal.port`），两个上下文永不共享同一 Spring 键值。
- **两个 `spring.config.name` 分隔配置文件**：dist 自带 `artemis-all-in-one-registry.properties`、`artemis-all-in-one-discovery.properties`（内容为各自单服务 `application.properties` 的组合版：认证/快照/actuator/日志/trace 等，**不含端口与上游集群**）。这样两个 server jar 中同名的 `application.properties` 不参与 all-in-one 加载，避免 classpath 先者胜导致的配置串味。
- 组合期覆盖键（SCF，静态、范围 1..65535、非法 fail-fast；source 优先级同服务端：系统属性 > 环境变量 > 工作目录 `artemis.properties`）：

  | 键 | 默认 |
  |---|---|
  | `artemis.dist.registry.grpcPort` | 9090 |
  | `artemis.dist.registry.httpPort` | 8080 |
  | `artemis.dist.registry.managementPort` | 9091 |
  | `artemis.dist.registry.peerPort` | 9093 |
  | `artemis.dist.discovery.grpcPort` | 9095 |
  | `artemis.dist.discovery.httpPort` | 8090 |
  | `artemis.dist.discovery.managementPort` | 9096 |

  标准的 `SPRING_*`/`--server.port` 等共享键不用于 all-in-one 覆盖（会同时命中两个上下文）；端口请用上表键。
- 上游集群固定同进程 registry（命令行注入，优先于 `artemis.properties` 同名键）：all-in-one 的多集群聚合能力不在本形态提供（多集群 union 仍由独立 discovery 部署承载，phase-5 已验收）。该限制登记到 all-in-one 文档。

#### 3.4 与单服务部署的关系

- registry-server / discovery-server 单服务入口与各自 `application.properties` 完全不变；`artemis-dist` 只是新增组合入口与打包产物。
- 架构 §2.1「同一进程内起多个 server、各自端口」在 v0.3 只落 registry + discovery（management 的 all-in-one 完整形态归 v0.4 路线图）。

### 4. 语义定案与原始文档同步

| # | 定案 | 原始文档同步落点 |
|---|---|---|
| D9-1 | starter 注册/发现两侧均按「对应 `addresses` 是否配置」条件装配；缺地址不再 fail-fast（双用途语义显式修订），非法地址仍构造期 fail-fast | ⑩：`docs/api/sdk-java.md`（starter 条件 + 变更说明）、`docs/api/sdk-java-discovery.md`（补 starter 用法/条件）、`docs/product/artemis.md`；`docs/dev/coding-guidelines-java.md` §9 过时措辞修正 |
| D9-2 | 发现侧自动装配并入既有 `ArtemisClientAutoConfiguration`，共享 `StringProperties`/`MeterRegistry`，class 名与双注册不变；`StringProperties` 配置桥按名让位（同名接管 / 异名共存按名绑定，⑨ 定案修订） | 同上 API 文档；技术选型 §2.1 starter 行无需改（依赖与编译基线未变） |
| D9-3 | all-in-one = 新增 `artemis-dist` 模块，同 JVM 两个 Spring 上下文、网络互访、不进程内直连 | ③ 即改 `docs/dev/tech-stack-java.md` §2.1 模块树（落位 `artemis-dist`）；ADR-001 已覆盖决策本身，不新增 ADR |
| D9-4 | 每上下文独立 `spring.config.name` + launcher 注入端口；组合期端口键 `artemis.dist.*` | ⑩：新建 `docs/api/all-in-one.md`（入口、端口表、配置键、限制）；技术选型 §2.1 |
| D9-5 | all-in-one 的 discovery 固定订阅同进程 registry；多集群聚合仍属独立 discovery 部署 | ⑩：`docs/api/all-in-one.md` 限制节；`docs/product/artemis.md` 交付说明 |
| D9-6 | 测试形态不新增：starter 用既有 `*Test`；dist 用既有 `*IT`（failsafe）；`dev-test.sh` 无需改（all=verify 自动纳入新模块） | ⑩：测试文档记录；roadmap v0.3 验收要点由既有 IT + 新 AllInOneIT 对应，版本收尾填 plan 验收对照表 |

### 5. 不做的事

- 不做进程内直连/短路调用（ADR-001）；不改 SDK 内部同步引擎；不加发现侧新配置键。
- 不做 management 的 all-in-one（v0.4）；不做 all-in-one 的多集群上游配置。
- 不把两个 server 合并成一个 Spring 上下文（会破坏单服务部署的独立性与端口/配置边界）。
- 不做容器镜像/部署脚本（v1.0 部署形态）。

## 实现结果（⑦ 补）

### 实现要点

- **starter**（`spring-boot-starter-artemis-client`）：注册侧 bean 更名 `artemisRegistryClient` 并加 `@ConditionalOnProperty(artemis.client.registry.addresses)`；发现侧 `artemisDiscoveryClient` 落在**嵌套配置类** `DiscoveryClientConfiguration`（自带 `@ConditionalOnClass(DiscoveryClient.class)`，类级条件只留 RegistryClient——宿主钉旧版 artemis-client 时注册侧不受影响）；两侧独立 `@ConditionalOnMissingBean` 按类型让位；`StringProperties` 单份共享；shutdown listener 按 bean 名只关 starter 自建两 client（宿主覆盖 bean 生命周期归宿主），注册侧先于发现侧。
- **all-in-one**（新增 `java/server/artemis-dist`）：`AllInOneOptions`（@Value+@Builder，7 端口默认值）+ `AllInOneConfig`（SCF `artemis.dist.*`，source 链：系统属性 > 环境变量 > 工作目录 artemis.properties，1..65535 非法 fail-fast）+ `ArtemisAllInOneServer`（registry 先起 → 读 `PeerServerConfig.port()` → discovery 起，失败回滚已起上下文；关闭序 discovery → registry，单个 JVM shutdown hook 于 `Node.close()` 中移除）；dist 自带 `logback-spring.xml`（BOOT-INF/classes 优先于 lib jar，确定性生效）与两份 `spring.config.name` 配置文件（不含端口与上游集群）；`spring-boot-maven-plugin` repackage `classifier=exec` + 显式 mainClass，产物 `artemis-dist-<version>-exec.jar`。
- 节点目录默认值经 launcher 注入（`127.0.0.1:<discoveryGrpcPort>`），**仅宿主未配置 `artemis.discovery.nodes` 时**——显式配置优先于组合期默认（上游集群则固定同进程 registry，见 D9-5）。

### code-review（⑥ max 级）findings 处置

| # | finding | 处置 |
|---|---|---|
| 1 | launcher 无条件注入 `artemis.discovery.nodes`，压掉用户工作目录配置 | 修复：仅未配置时注入（`AllInOneConfig.configuredDiscoveryNodes()`） |
| 2 | 类级 `@ConditionalOnClass({Registry, Discovery})` AND 语义，旧版 client 缺 DiscoveryClient 时注册侧一起失效 | 修复：发现侧独立嵌套配置类自带 class 条件 |
| 3 | YAML 列表写法 `addresses: [..]` 在新条件语义下从 fail-fast 变静默跳过 | 不改代码：该写法本就不被 SCF 精确键查找支持（v0.1 同样不可用，此前为响亮失败）；SDK 文档明确 addresses 为逗号分隔字符串键 |
| 4 | AllInOneIT 未隔离 registry 快照，跨运行残留回放造成假绿 | 修复：每用例 `@TempDir` + 系统属性 `artemis.registry.snapshot.path`（registry 上下文 SCF source 3 生效） |
| 5 | shutdown listener 按类型关闭宿主覆盖 bean | 修复：按 bean 名只关 starter 自建 bean |
| 6 | 启动回滚只捕 RuntimeException、回滚 close 掩盖原始异常、Node.close 前者抛异常阻断后者 | 修复：捕 `RuntimeException\|Error`、suppressed 附加、close 两段独立收集异常 |
| 7 | 每次 start() 泄漏一个 JVM shutdown hook | 修复：hook 存于 Node，close() 中 removeShutdownHook |
| 8 | 测试覆盖回退（registry 侧 meter 注入、global 回落、destroy 路径断言被删） | 修复：恢复 2 例 meter 用例（独立 clientId 防全局污染）+ 用例 1 补 destroy 路径断言 |
| 9 | main(String[]) 静默丢弃命令行参数 | 修复：有 args 时 WARN 提示配置出口（不转发——会同时命中两上下文） |
| 10 | freePort() TOCTOU 端口抢占 | 不改：既有 IT 统一模式（「释放即起服务，缩小 TOCTOU 窗口」），全库一致 |
| 11 | phase 文档「实现结果」占位 | 本节即补（⑦ 步骤内完成，非缺陷） |
| 12 | fat jar 内两 server jar 的 logback-spring.xml classpath 顺序不确定 | 修复：dist 自带 logback-spring.xml（BOOT-INF/classes 确定性优先） |

### code-review 第二轮（⑨ 反馈修订后增量，6 findings）

| # | finding | 处置 |
|---|---|---|
| R1 | `@ConditionalOnProperty(addresses)` 只看 Spring Environment，同名接管 SCF 源且 addresses 不经 Spring 面时 client 静默不建 | 不改代码：Spring 条件机制所限（D9-1 边界）；javadoc 明确该边界与出口（接管 source 时请同时自定义 client bean） |
| R2 | `configuredDiscoveryNodes()` 三层链不含 Spring Environment source，Spring 途径配置的 nodes 会被 launcher 默认覆盖 | 不改代码：all-in-one 配置出口纪律 = SCF 三层（系统属性/环境变量/工作目录 artemis.properties），Spring 途径业务键不在支持面；javadoc 明确 |
| R3 | listener 按 bean 名关闭，同名宿主 bean（类型让位后名字归宿主）被误代关 | 修复：按 bean definition 声明类判定归属（source 为 Method/MethodMetadata 或 factoryMethodMetadata 三路取 declaring，精确匹配本类或 `$` 嵌套类——startsWith 前缀撞名已实证排除）；+1 用例 should_not_close_host_bean_with_same_bean_name |
| R4 | `closeIfPresent` 异常传播：registry close 失败跳过 discovery 关闭并中断其余监听 | 修复：close 尽力而为，失败记 WARN 不传播（SLF4J，starter 零 Lombok 手写 logger） |
| R5 | AllInOneConfig 每次 start() 建两个 SCF manager（各一个 ReloadablePropertiesFile watcher 线程）泄漏 | 修复：nodes 原始值并入 `AllInOneOptions.configuredDiscoveryNodes` 字段，`options()` 单次建 manager 一次读出 |
| R6 | `peerGrpcServerLifecycle` 字符串在测试侧重复 3 处无编译期防护 | 不改：生产侧已有常量单点（PEER_LIFECYCLE_BEAN），既有测试辅助非本 phase 引入；bean 重命名会被 AllInOneIT 立即捕获 |

## 文档同步（⑩ 填）

| 文档 | 变更 |
|---|---|
| `docs/product/artemis.md`（1.24） | 新增 phase-9 交付段（starter 双用途语义、all-in-one 交付物与限制）；模块表补 `artemis-dist`、starter 行补双侧装配；v0.3 状态 phase-1–9 已交付 |
| `docs/api/sdk-java.md`（1.5） | §Spring Boot 自动装配重写：双侧条件语义（D9-1 修订说明）、配置桥同名让位/异名共存（⑨ 定案）、addresses 逗号分隔字符串键说明 |
| `docs/api/sdk-java-discovery.md`（1.1） | 新增 §9 Spring Boot 自动装配（条件、不代建订阅、与注册侧语义一致引用） |
| `docs/api/all-in-one.md`（新建 1.0） | 入口与交付物、端口表、`artemis.dist.*` 配置键、语义与限制（多集群不在该形态、配置出口纪律、启动/关停时序） |
| `docs/api/README.md` | 登记 all-in-one.md、sdk-java-discovery.md 说明补 starter |
| `docs/dev/coding-guidelines-java.md`（1.26） | §9 修正过时措辞：`StringProperties` 配置桥同名让位 + `@Qualifier` 按名绑定；覆盖出口 `ArtemisClient` → `RegistryClient`/`DiscoveryClient` |
| `docs/dev/tech-stack-java.md`（1.24） | §2.1 模块树落位 `artemis-dist`（替换占位注释；依赖与编译基线未变，组件总表无需新增行） |
| CLAUDE.md | 无需更新（构建命令、模块组织粒度未变） |
| ADR | 无新增（D9-3：all-in-one 决策已由 ADR-001 覆盖） |

## 更新历史

| 版本 | 日期 | 变更说明 |
| ------ | ------ | ------ |
| 1.4 | 2026-10-24 | ⑨ 第二轮验证通过；⑩ 文档同步（7 文档 + 索引，清单见上节） |
| 1.3 | 2026-10-24 | ⑨ 人工验证反馈修订：`StringProperties` 配置桥同名 bean 让位 + client `@Qualifier` 按名注入（§2.1/§2.3、D9-2 更新），④–⑨ 重走含第二轮 code-review 6 findings 处置（R1–R6） |
| 1.2 | 2026-10-24 | ④⑤⑥⑦⑧：实现 + all 全绿 + code-review 12 findings 处置（10 修复 / 2 记录不改），实现结果与处置清单落盘 |
| 1.1 | 2026-10-24 | ③ 设计定稿：starter 按 addresses 条件装配（双用途语义修订）、`artemis-dist` 同 JVM 双上下文 all-in-one、`artemis.dist.*` 端口组合配置与文档同步落点（D9-1–D9-6） |
| 1.0 | 2026-10-24 | 开工骨架 |
