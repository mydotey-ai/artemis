# Phase 1：TLS 传输安全

版本: 1.0    更新时间: 2026-10-27

## 目标

各服务外部 gRPC/HTTP 与内部通道（peer 复制、规则流）TLS 可启（证书配置加载），SDK TLS 支持；内网明文可配共存（架构 §6：TLS v1.0 支持、内外通道均可启、内网部署可配明文）。

## 范围

做什么：TLS 通道装配（服务端外部 gRPC/HTTP、内部 peer/规则流端口、discovery 上游连接、management 出站查询）+ 证书供给配置 + SDK 侧 TLS。

不做什么：mTLS 客户端证书校验的账户体系整合（RBAC 归 phase-2）；证书轮换自动化（配置重启生效）；K8s secret 挂载编排（归 phase-4 chart values）。

## 涉及模块

java/server 各 server 模块（通道装配）+ artemis-common（如证书配置抽象）+ java/client（SDK TLS）。proto 不涉（TLS 为传输层，契约不变）。

## 验收方式

对应测试文档：[phase-1-tls-test.md](phase-1-tls-test.md)

## 产品设计（③ 补）

### 总原则

- **显式启用、默认明文**（与 D9 认证哲学一致）：任何通道未配置 TLS 开关时行为与现状完全一致，eval 零依赖路径不变。
- **配置所有权按 §10.1 分界**：外部 gRPC/HTTP 端口是 Spring Boot 自有装配（`spring.grpc.server.*` / `server.*`），TLS 走框架 **Spring SSL bundle** 机制（PEM bundle + `spring.grpc.server.ssl.bundle` / `server.ssl.bundle` 引用），零代码；程序化通道（内部 peer、上游出站、SDK）走 **SCF 静态键**，本 phase 落代码。不复制/代理框架键。
- **仅 server-auth TLS**（加密 + 服务端身份校验）：不做 mTLS 客户端证书校验（归 phase-2 与账户体系整合）。客户端 hostname 校验按 gRPC/JDK 默认语义，不提供 skip-verify 开关——证书须带 SAN 覆盖成员地址/服务地址。
- **证书供给**：X.509 PEM 文件路径（服务端：证书链 + PKCS#8 私钥；客户端：可选自定义 CA = trust anchor，缺省用 JVM 默认信任库）。不内建证书生成；轮换 = 换文件重启生效（范围声明）。

### 通道 × 开关矩阵

| # | 通道 | 服务端 TLS 装配 | 客户端装配 | 配置（服务端 / 客户端） |
|---|---|---|---|---|
| 1 | SDK → registry 外部 gRPC（四操作/心跳） | Boot 主 server | SDK `GrpcRegistryClient` | `spring.grpc.server.ssl.bundle` / `artemis.client.tls.*` |
| 2 | registry 外部 HTTP（`/cluster/nodes` 辅助） | Boot Tomcat（`server.ssl.bundle`，零代码） | SDK `HttpNodeListFetcher`（https url，零代码；自定义 CA 时挂 SSLSocketFactory） | 同一 bundle / `artemis.client.tls.*` |
| 3 | SDK → discovery 外部 gRPC（查询/订阅） | Boot 主 server | SDK `GrpcDiscoveryClient` | 同 #1 机制 / `artemis.client.tls.*` |
| 4 | discovery 外部 HTTP（`/admin/v1`、`/cluster/nodes`） | Boot Tomcat | 浏览器/SDK fetcher | 同一 bundle / — |
| 5 | registry ↔ registry 内部 peer 端口 | 手工 `NettyGrpcServerFactory` 加 sslContext customizer | registry `PeerChannels`（复制 + bootstrap 共用池） | `artemis.registry.internal.tls.*`（一组键供两侧） |
| 6 | discovery → registry 投影订阅/bootstrap | （即 #5 的 peer server） | discovery `GrpcProjectionSource` | — / `artemis.discovery.registry.tls.*` |
| 7 | discovery → management 规则流/逻辑实例流 | management Boot 主 server（`spring.grpc.server.ssl.bundle`，零代码） | discovery `ManagementRuleChannels` + `GrpcLogicalInstanceSource` | — / `artemis.discovery.management.tls.*` |
| 8 | management → registry 只读事实查询 | （即 #5） | management `RegistryQueryGrpcClient`（单 endpoint） | — / `artemis.management.registry.tls.*` |
| 9 | console / Admin API（management 外部 HTTP） | Boot Tomcat | 浏览器 | 同一 bundle / — |
| 10 | 管理 HTTP（actuator，默认绑 127.0.0.1） | **不做 TLS**（本机 + 网络策略隔离，文档明示） | — | — |

Spring bundle 示例（#1–#4、#9，框架键，每服务一份）：

```properties
spring.ssl.bundle.pem.artemis.keystore.certificate=/path/cert-chain.pem
spring.ssl.bundle.pem.artemis.keystore.private-key=/path/private-key.pem
spring.grpc.server.ssl.enabled=true
spring.grpc.server.ssl.bundle=artemis
server.ssl.bundle=artemis
```

### SCF 键定义（静态分型，fail-fast）

键组后缀统一：`.enabled`（boolean，默认 false）、`.certChainPath`、`.privateKeyPath`、`.trustCaPath`（客户端；可空 = JVM 默认信任库）。

| 键组 | 作用通道 | 必需项（enabled=true 时） |
|---|---|---|
| `artemis.registry.internal.tls.*` | #5 服务端 + registry 出站 peer channel | 服务端：certChainPath + privateKeyPath；出站：可选 trustCaPath |
| `artemis.discovery.registry.tls.*` | #6 | 可选 trustCaPath |
| `artemis.discovery.management.tls.*` | #7 出站 | 可选 trustCaPath |
| `artemis.management.registry.tls.*` | #8 出站 | 可选 trustCaPath |
| `artemis.client.tls.*` | SDK #1/#3（gRPC）+ #2（HTTP）共用一组（同部署同 CA） | 可选 trustCaPath |

校验（guidelines §10.3）：enabled=true 且服务端必需项为空 / 文件不存在 / 不可读 → **fail-fast**（拒绝启动，报键名 + 原因）；trustCaPath 配置但文件不存在同样 fail-fast；disabled 时路径项不校验。

### 数据结构与接口（artemis-common 新增，服务端共享）

```java
/** 不可变值对象：SCF 静态绑定（suffix 约定见键组定义），一次定义多模块共用（§10.1）。 */
public final class TlsServerSettings { boolean enabled(); String certChainPath(); String privateKeyPath(); }
public final class TlsClientSettings { boolean enabled(); String trustCaPathOrNull(); }
// 绑定：TlsSettings.server(properties, "artemis.registry.internal.tls")
//       TlsSettings.client(properties, "artemis.discovery.registry.tls") —— 启动即校验（fail-fast）

/** TLS 装配（grpc-netty；SDK 侧以 shaded 版本同形实现 ClientTls，不入 common）。 */
public final class GrpcTlsContexts {
    /** 入站：证书 PEM → KeyManagerFactory（spring-grpc factory 构造参数——其内部以 ServerCredentials
     *  构 builder、安全设置冻结，customizer 挂 sslContext 会抛 Cannot change security；实现期定案） */
    public static KeyManagerFactory serverKeyManagers(TlsServerSettings s);
    public static SslContext client(TlsClientSettings s);   // forClient + trustManager(自定义 CA)；未配 CA 走默认
    public static ManagedChannelBuilder<?> channelForAddress(String host, int port, TlsClientSettings tls);
    public static ManagedChannelBuilder<?> channelForTarget(String target, TlsClientSettings tls);
    // disabled → usePlaintext()（既有行为）；enabled 未配 CA → 默认 TLS + JVM 信任库
}
```

### 行为语义

- **出站 channel 装配**（`PeerChannels` / `GrpcProjectionSource` / `ManagementRuleChannels` / `GrpcLogicalInstanceSource` / `RegistryQueryGrpcClient` / SDK 两 client）：enabled=false 保持 `usePlaintext()` 不变；enabled=true 不调 plaintext（默认 TLS），配置 trustCaPath 时以 `NettyChannelBuilder.sslContext(...)` 挂自定义信任锚。静态配置，channel 生命周期不变（重建时机沿用既有选址/重连逻辑）。
- **peer 内部 server 装配**（`PeerServerConfiguration`）：enabled=true 时以 `GrpcTlsContexts.serverKeyManagers(...)` 经 `NettyGrpcServerFactory` 构造参数接入（spring-grpc factory 安全设置冻结，customizer 不可挂 sslContext——实现期定案，见上）；认证/readiness 拦截器栈不变（TLS 是传输层，与 Basic 认证正交）。
- **SDK HTTP 辅助通道**（`HttpNodeListFetcher`）：nodesUrl 为 https 时走 `HttpsURLConnection`；配置 `artemis.client.tls.trustCaPath` 时挂对应 `SSLSocketFactory`（gRPC 与 HTTP 同一 CA）。
- **all-in-one（dist）**：TLS 属性逐服务透传，无代码改动；跨服务 loopback 通道（discovery→registry peer 端口等）启用 TLS 需证书含 localhost SAN（文档明示）。
- **artemis-common 新增直接依赖 `io.grpc:grpc-netty`**（grpc BOM 同族，`GrpcSslContexts`/`SslContext` 所在）；已在技术选型组件总表 grpc 条目范围内，④ 时核对总表登记。

### 验证点（实现期确认，非新决策）

- spring-boot-grpc 4.1.1 `spring.grpc.server.ssl.bundle` 对 Netty factory 的实际生效路径（属性类已确认存在：`GrpcServerProperties$Ssl{enabled,bundle,clientAuth}`）。
- grpc-netty 自定义 trustManager 时的 hostname 校验默认语义（IT 用 SAN= localhost/127.0.0.1 证书验证通过路径）。

## 实现结果（⑦ 补）

实现要点：

- **artemis-common `common.tls`**：`TlsSettings`（server/client 绑定，静态 + fail-fast）+ `TlsServerSettings`/`TlsClientSettings` 值对象 + `GrpcTlsContexts`（`serverKeyManagers` PEM→KeyManagerFactory、`client` 信任锚 SslContext、`channelForAddress/ForTarget` 明文/TLS 分支）。doc 按后缀全局常量（peer 组同键双绑单 PropertyConfig，§10.1）。新增直接依赖 grpc-netty（总表 #8 范围内）。`TestCertificates`（keytool 自签 + PEM 导出）以 test-jar 供 registry/discovery IT 复用。
- **入站**：外部 gRPC/HTTP 全走 Spring SSL bundle（零代码）；peer 内部端口经 `NettyGrpcServerFactory` 构造参数 `KeyManagerFactory` 接入——factory 以 ServerCredentials 冻结安全设置，customizer 挂 sslContext 会抛 "Cannot change security"（实现期定案，见产品设计）。
- **出站**：`PeerChannels`/`GrpcProjectionSource`/`ManagementRuleChannels`/`GrpcLogicalInstanceSource`/`RegistryQueryGrpcClient` 全部换 `GrpcTlsContexts.channelForAddress/ForTarget`（disabled 语义与原 `usePlaintext()` 逐位一致）。
- **SDK**：`ClientTls`（shaded grpc + JDK HttpsURLConnection 同锚），gRPC 上下文与 https 工厂构建期固化复用；`GrpcRegistryClient`/`GrpcDiscoveryClient`/`HttpNodeListFetcher` 注入式接线。
- 测试：common 单测 11 + client 单测 5 + `RegistryTlsIT` 3（SDK over TLS + 双节点 peer TLS 复制 + 明文/无锚拒绝，前置二分断言）+ `DiscoveryTlsIT` 1（三服务全 TLS：投影/规则流/console https/服务视图查询/nodes https）。

code-review（max，7 findings）修复记录：

1. **ClientTls https 多证书 bundle 崩溃**（拼接 base64 解码失败）：重写为逐 `BEGIN/END CERTIFICATE` 块解析、全部载入信任 KeyStore——与 gRPC 侧 trustManager(File) 的 bundle 语义对齐。
2. **trustCa 坏文件绕过启动 fail-fast**（SslContext 惰性构建，坏 CA 进运行期重试循环）：`TlsSettings.client` 绑定时主动解析一次（结果丢弃）；SDK `ClientTls.from` 创建即构建 gRPC 上下文与 https 工厂（顺带消除惰性双检锁）。
3. **`GrpcTlsContexts.client` 对未配 CA 抛裸 NPE**：前置 null 检查 → IllegalStateException。
4. **Upstream record 第三载体**：还原两字段，`GrpcProjectionSource` 构造器显式收 `TlsClientSettings`（`UpstreamSynchronizers` 传入 `upstreamConfig.tls()`），两访问模式归一。
5. **ClientTls 每消费类自建实例**：构造器改为注入式（tls 由 Default 层构造传递）。**取舍**：未做每 client 真单例——`DefaultRegistryClient` 测试构造器经 `NodeListFetcher` 接口注入 fake（无 tls 可复用），grpc 侧必须自持；每 client 两实例、配置同源构建幂等，无行为差异。
6. **TestCertificates 泄漏未加密私钥临时目录**：实现 `AutoCloseable` 递归删除，两 IT `@AfterAll` 关闭。
7. **docs/api/sdk-java.md 键表缺 TLS 键**：归 ⑩ 文档同步落地。

## 文档同步（⑩ 填）

| 文档 | 变更 | 与定案的对应 |
|---|---|---|
| `docs/product/artemis.md`（1.37） | 新增「v1.0 phase-1（TLS 传输安全）已交付」节：总原则/配置分界/行为/工程形态 | 产品可见能力（必含项） |
| `docs/api/sdk-java.md`（1.6）/ `sdk-java-discovery.md`（1.2） | 键表补 `artemis.client.tls.*`（enabled/trustCaPath、bundle、fail-fast） | code-review finding 7；API 配置变更 |
| `docs/arch/artemis-next-architecture.md`（1.46） | §6 TLS 行扩写：Spring bundle/SCF 键分界、仅 server-auth、SAN 要求、轮换重启生效 | 定案触及原始文档（§6 单行决策 → 落地形态） |
| `docs/dev/tech-stack-java.md`（1.29） | 组件总表 #8 用途补 artemis-common 直接依赖 grpc-netty（common.tls） | 新增直接依赖登记 |
| 三服务 `application.properties` | TLS 配置示例注释块（Spring bundle + SCF 键组） | ④ 随代码落地 |
| 各级索引 | 无新增/移动文件，索引不变；CLAUDE.md 构建与结构未过时，不动 | — |

## 更新历史

| 版本 | 日期 | 变更说明 |
| ------ | ------ | ------ |
| 1.3 | 2026-10-27 | ⑧⑨⑩：回归全绿 + 验证通过 + 文档同步收口 |
| 1.2 | 2026-10-27 | ④⑤⑥⑦：实现 + all 全绿 + code-review 7 findings 修复 |
| 1.0 | 2026-10-27 | 开工骨架（设计待 ③ 定稿） |
