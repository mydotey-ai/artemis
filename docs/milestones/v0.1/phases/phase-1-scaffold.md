# Phase 1：Maven 脚手架 + proto 契约骨架

版本: 1.23    更新时间: 2026-10-12

## 目标

搭建 monorepo 全模块 Maven reactor 与 proto 契约目录骨架，`mvn -f java/pom.xml verify` 全绿且 enforcer 依赖断言生效。

## 范围

做什么：`proto/` 六组契约目录骨架（v0.1 仅定稿 `common.v1` + `registry.v1` 两组消息）；`java/` 全模块 reactor（parent + 各模块 pom）；flatten `${revision}`；Spotless + enforcer 插件配置；各模块占位源码与测试；**自动化测试入口 `java/scripts/dev-test.sh` 与 `/dev-java-test` command**（定案 2026-10-12，见 §7）；**产品文档 `docs/product/artemis.md` 建立**（⑩ 必须步骤，规则见 dev-java ⑩）。

不做什么：任何业务功能实现（心跳/租约/自我保护等归后续 phase）；CI、Dockerfile（phase-7）；client 实际功能代码。

## 涉及模块

`proto/`（common.v1、registry.v1 及其余四组目录骨架）；`java/` 全部模块（tech-stack-java.md §2.1 模块清单一次到位）。

## 验收方式

对应测试文档：[phase-1-scaffold-test.md](phase-1-scaffold-test.md)

## 产品设计（③ 补）

### 1. 顶层布局

```text
artemis/
├── proto/    六组契约（common + 五组 service，各 v1/），语言无关纯目录
└── java/     Maven 单 reactor（构建入口 mvn -f java/pom.xml）
    └── scripts/dev-test.sh   自动化测试入口（§7）
```

### 2. proto 契约

六组目录一次建齐；**v0.1 定稿 `common.v1` + `registry.v1`**（phase-2 心跳内核的直接契约输入），其余四组仅包声明占位（随各自版本填充，proto-contract.md §1）。

**common/v1/model.proto**（公共数据模型，被各组 import）：

```protobuf
syntax = "proto3";
package artemis.common.v1;
option java_package = "org.mydotey.ai.artemis.proto.common.v1";
option java_multiple_files = true;

// 服务标识：namespace 多租户隔离（D10）。
// 分组不进数据模型：需要时以 metadata 元素表达（如 metadata["group"]），
// 治理分组路由按 metadata 选择器过滤（定案 2026-10-12）
message ServiceKey {
  string namespace = 1;
  string service = 2;
}

// 实例标识：定位单个实例（deregister/update 的操作目标）
message InstanceKey {
  ServiceKey service = 1;
  string instance_id = 2;
}

// 实例状态：客户端自报（服务端不做主动探测，架构 §4.2 哲学）；
// 未设置（UNSPECIFIED）语义等同 UP；客户端对不能识别的值统一映射 INSTANCE_STATUS_UNKNOWN
enum InstanceStatus {
  INSTANCE_STATUS_UNSPECIFIED = 0;     // 未设置，语义同 UP
  INSTANCE_STATUS_UNKNOWN = 1;         // 客户端无法识别（本地映射目标）
  INSTANCE_STATUS_UP = 2;
  INSTANCE_STATUS_DOWN = 3;
}

// 服务实例：由客户端上报（显式操作与心跳全量），服务端只存事实
message Instance {
  string instance_id = 1;
  string host = 2;
  int32 port = 3;
  InstanceStatus status = 4;
  int32 weight = 5;                    // LB 权重；≤ 0 等同未设置，入口归一为 100
  map<string, string> metadata = 6;
}

// 单服务实例集：上报（操作与心跳）与推送的原子单元
message ServiceInstances {
  ServiceKey key = 1;
  repeated Instance instances = 2;
}

// ---- 操作结果（各服务响应共用组件；Response 规范形式见 registry.v1） ----

// 操作整体判定：二态——任一失败即 FAIL，失败明细与范围经 data 携带
enum Ack {
  ACK_SUCCESS = 0;                     // 成功（0 = 默认值；fail-open 有意取舍，同 gRPC OK=0，传输级异常由 gRPC status 兜底）
  ACK_FAIL = 1;                        // 失败（含部分失败：逐实例明细见 data）
}

// 错误：code 为错误类型标识（契约注册表内稳定字符串，只增不改、永不重命名）；
// message 人类可读排障描述，非状态码。
// 命名 ErrorInfo 而非 Error：避开与 java.lang.Error 在通配 import 下的歧义
message ErrorInfo {
  string code = 1;
  string message = 2;
}

// 实例级错误：批量操作部分失败明细（data 侧组件）
message InstanceError {
  InstanceKey instance = 1;
  ErrorInfo error = 2;
}

// 服务对齐的错误分组：与 ServiceInstances 请求结构对齐（key + 逐实例失败明细）
message ServiceInstanceErrors {
  ServiceKey key = 1;
  repeated InstanceError failed = 2;
}

// 操作结果状态：ack 整体判定 + error 失败错误（FAIL 时携带代表性错误，SUCCESS 不设；
// 逐实例明细在 data）
message ResponseStatus {
  Ack ack = 1;
  ErrorInfo error = 2;
}
```

**registry/v1/registry.proto**（四操作契约：显式操作 + 心跳全量上报续约/补注册）：

```protobuf
syntax = "proto3";
package artemis.registry.v1;
import "common/v1/model.proto";
option java_package = "org.mydotey.ai.artemis.proto.registry.v1";
option java_multiple_files = true;

// ---- 心跳：进程级全量实例集上报（逐实例续约 + 缺席自动注册） ----

message HeartbeatRequest {
  // 全量实例集：SDK 进程持有的全部服务实例，幂等上报；
  // 逐实例处理：续约 + 缺席自动注册（upsert）；不做集合 diff——
  // 上报中缺席的实例不摘除，由租约 TTL 过期清理最终一致
  repeated artemis.common.v1.ServiceInstances services = 1;
  // 心跳序号：客户端进程内单调递增（重启可重置），ack 原样回填——
  // gRPC bidi 不保证 1:1 响应（服务端可合并 ack），seq 使 ack 可归属、丢 ack 可检测；
  // SDK 不阻塞等待特定 seq 的 ack（超时即重发，全量幂等上报自愈）
  int64 seq = 2;
}

message HeartbeatResponse {
  artemis.common.v1.ResponseStatus status = 1;
  HeartbeatResponseData data = 2;
}

// ---- 显式实例操作：批量 + 部分失败语义（批量贯穿资产 #11） ----

// 注册：无条件覆盖（幂等）
message RegisterRequest {
  repeated artemis.common.v1.ServiceInstances services = 1;
}

// 反注册：意图语义——目标缺席即目标状态已达成（幂等成功）
message DeregisterRequest {
  repeated artemis.common.v1.InstanceKey instances = 1;
}

// 更新：幂等 upsert——已存在则全量覆盖（非字段 patch），不存在则自动注册
message UpdateRequest {
  repeated artemis.common.v1.ServiceInstances services = 1;
}

// Response 统一规范：status + data。proto 无泛型——「泛型 T」以约定落地：
// 每个 Response 固定两字段，data 类型命名 XxxResponseData（T 的具体化），
// 独立定义独立演化；请求结构在 data 中对齐体现（service 单位请求 → service 分组明细）
message RegisterResponse {
  artemis.common.v1.ResponseStatus status = 1;
  RegisterResponseData data = 2;
}

message DeregisterResponse {
  artemis.common.v1.ResponseStatus status = 1;
  DeregisterResponseData data = 2;
}

message UpdateResponse {
  artemis.common.v1.ResponseStatus status = 1;
  UpdateResponseData data = 2;
}

message HeartbeatResponseData {
  int64 server_time_ms = 1;            // ack 基准时间，租约语义 phase-2 细化
  int64 seq = 2;                       // echo 对应 HeartbeatRequest.seq
}

// 注册：失败明细按 service 分组，与请求结构对齐
message RegisterResponseData {
  repeated artemis.common.v1.ServiceInstanceErrors services = 1;
}

// 反注册：请求为实例键平铺，失败明细同为平铺
message DeregisterResponseData {
  repeated artemis.common.v1.InstanceError instances = 1;
}

// 更新：失败明细按 service 分组，与请求结构对齐
message UpdateResponseData {
  repeated artemis.common.v1.ServiceInstanceErrors services = 1;
}

service RegistryService {
  // bidi-stream：每条心跳经 seq 关联 ack（gRPC 双向流承载心跳，架构 §2.2）；
  // 流断开 = 租约进入倒计时
  rpc Heartbeat(stream HeartbeatRequest) returns (stream HeartbeatResponse);
  // 显式操作：unary，同步语义
  rpc Register(RegisterRequest) returns (RegisterResponse);
  rpc Deregister(DeregisterRequest) returns (DeregisterResponse);
  rpc Update(UpdateRequest) returns (UpdateResponse);
}
```

占位组（replication / projection / discovery / management）每组一个仅含 `syntax`/`package`/`java_package`/注释的 .proto。

**错误码注册表**：单一来源在 [proto 契约规范](../../../dev/proto-contract.md) §2（纪律与初始分配），此处不复述。

**注册操作语义**（review 定案 2026-10-11：保留四操作，按语义设计）：

| 操作 | SDK 本地集 | rpc | 服务端语义 | 派生事件 |
|---|---|---|---|---|
| register | 加实例 | unary 批量 | 无条件覆盖建租约（幂等，继承原产品语义） | NEW |
| deregister | 删实例 | unary 批量 | 立即摘除；目标缺席视为成功（意图已达成）；**显式操作不受自我保护拦截**（重申原产品边界，phase-3 保护实现须遵守） | DELETE |
| update | 改实例 | unary 批量 | 幂等 **upsert**：已存在则全量覆盖（UPDATE 事件），不存在则自动注册（**NEW 事件 + 建租约**，定案 2026-10-11） | UPDATE（命中）/ NEW（缺席） |
| heartbeat | —（周期） | bidi-stream | 全量上报：逐实例**续约 + 缺席自动注册**（upsert，发 NEW）；**不做集合 diff**，上报中缺席的实例不摘除——租约 TTL 过期清理兜底 | NEW（缺席补注册） |

模型不变量：

- **本地实例集仍是唯一事实源**：四操作全部先改本地集再上报，心跳上报本地集全量——资产 #1 的实质（全量幂等上报、断网重连自动收敛）保留。
- **op 失败无需 SDK 重试状态机**：register/update 丢失由下一次心跳 upsert 即时自愈（服务端 upsert 替代原产品「miss 报告 → 客户端补注册」跨网络环）；deregister 丢失由 TTL 过期清理最终收敛（与原产品消失方向语义一致）。
- **稳态心跳 = 纯续约**：零新事件零复制流量（变更事件扇出语义不变，架构 §4.1）。
- **操作天然幂等无需请求 ID**：register 覆盖、deregister 意图达成、update 覆盖——重复投递无害。

**架构同步（已执行，2026-10-11）**：按工作流新增的「架构决策同时修订原始文档」纪律随即完成——架构 §4.1 改写为显式四操作 + 心跳全量上报续约/补注册（D12，adr-012 成文并登记 §7 总览与 decisions 索引）、§8 资产 #1 处置与状态码治理表述更新；proto-contract §2 修订（数据枚举 proto enum + UNKNOWN 哨兵、错误码 string code 契约注册表（消息名后定 `ErrorInfo`）、Response 统一规范，错误码注册表入规范）；roadmap v0.1 内核条目、plan.md phase-2 主题与验收方式同步。

设计要点：

- `java_package` 与模块无关（`org.mydotey.ai.artemis.proto.<组>.v1`）——同一组在多模块生成同 FQCN 类是预期模型（tech-stack §2.2），跨模块只走 wire。
- `Instance.status`/`weight` 进 v0.1 数据模型：与 namespace 同属「后加代价大」的地基字段（status 承载「客户端自报状态」哲学的最小出口，weight 为 discovery LB 消费预留）；两段式权重发布归 v0.5 治理，不在此列。
- import 方向 registry → common（不出现环）。

review 定案（2026-10-11）：

- **注册模型四操作化**：保留 register / deregister / update / heartbeat 四语义并按语义设计——前三者先更新本地集再上报、服务端按语义处理；heartbeat 全量实例上报（逐实例续约 + 缺席补注册，不做集合 diff）。变更大架构 §4.1「免显式注册状态机」，见上「架构同步」。取舍对照原产品（三显式 API + WS 全量心跳 + miss 补注册环）：保留显式操作的即时语义与原产品已验证的 API 面，新增方向以服务端 upsert 替代跨网络 miss 环（即时自愈），消失方向维持原产品同款 TTL 最终一致。
- **cluster 不进 ServiceKey**：namespace 是服务的内禀属性（租户），cluster 是数据的外禀来源（哪个 registry 集群产出）——绑定发生在边界。v0.1–v0.2 registry 数据面（心跳/租约/复制）隐式同集群，集群名是服务端静态配置；v0.3 起作为来源标识进 projection/discovery 包装层（投影按 `(cluster, …)` 组织，维度在投影边界注入）；客户端心跳永不携带。v0.3 加包装字段向后兼容，推迟零代价。（[adr-014](../../../decisions/adr-014-cluster-out-of-service-key.md)）
- **InstanceSet 容器删除，各请求消息内联 `repeated ServiceInstances`**：不设进程级共享容器；common.v1 需要共享的单元是 `ServiceInstances`（上报元素 = diff 单元 = 复制单元 = 推送单元 ADR-007）。SDK 本地事实源为自持 Java 模型，phase-6 磁盘快照信封届时按需定义（v0.1 定稿前改契约零成本，定稿后新增消息只增不改）。
- **容器用 repeated 而非 map**：proto3 map key 只能标量，复合 key 只能字符串拼接（结构进字符串反模式）；`ServiceInstances` 须为全链路可引用的一等类型（map entry 不可引用）；重复 ServiceKey 须在信任边界检出拒绝（map 语义静默 last-win）；wire 表数据、索引结构归内核内存注册表。
- **错误码 `ErrorInfo` string code**：契约注册表内稳定字符串，未知码对各端自描述可见——有意重审原产品「字符串状态码漂移」教训，以注册表纪律消解（纪律单一来源 proto-contract §2）；消息名 `ErrorInfo` 避开 `java.lang.Error` 通配 import 歧义（code-review 定案 2026-10-12）。
- **`ResponseStatus` 二态 ack + error**：任一失败即 FAIL、范围经 data 明细表达（有意简化原产品三态 partial_fail）；`ACK_SUCCESS=0` 未设置即成功为有意 fail-open 取舍（同 gRPC OK=0 同构，传输级异常由 gRPC status 兜底）。
- **Response 规范 `status + data`**：proto 无泛型以约定落地——每 Response 固定两字段，data 命名 `XxxResponseData`、独立演化（规范单一来源 proto-contract §2）；`ResponseStatus`/`Ack`/`ErrorInfo`/`InstanceError` 居 common.v1，数据枚举保持 proto enum + UNKNOWN 哨兵。
- **proto 生成模型 server/client 分治**（定案 2026-10-11）：server 端 common.v1 统一生成于 artemis-common、各子服务 core 依赖共用（单 artifact 单 FQCN，服务端模块间可直传）；client 独立于 server（enforcer 禁依赖 + protoc 3.25/Java 8 分叉），自生成 common+registry+discovery。2026-10-12 增补：projection.v1 同归 artemis-common（registry/discovery core 双消费，all-in-one classpath 去重）。tech-stack §2.2/§4.2、guidelines §1、proto-contract §1、arch §2.2 已同步。
- **心跳不做集合 diff**（定案 2026-10-12，code-review finding 7 处置）：心跳全量上报按逐实例处理——续约 + 缺席自动注册（upsert，发 NEW）；上报中缺席的实例不摘除，由租约 TTL 过期清理最终一致（与原产品消失方向语义一致）。对账基准/client 身份字段随之不需要；deregister 丢失场景从「重连即摘」退化为「等 TTL」为有意取舍——显式下线的低延迟依赖 deregister 调用成功，不承诺断网期间的即时性。
- **Instance 缺省语义显式化**（定案 2026-10-12，code-review finding 8 处置）：`weight ≤ 0` 等同未设置，信任边界归一为 100（真零权重摘流以 status DOWN / 治理摘除表达）；`status` 未设置（UNSPECIFIED）语义等同 UP。此为 proto-contract §2「缺省值警示」的显式文档化例外（有意定义，非隐式依赖）。
- **分组退出数据模型**（定案 2026-10-12，[adr-013](../../../decisions/adr-013-group-as-instance-metadata.md)）：`ServiceKey` = (namespace, service)（去 group），`Instance` 不设 group 字段——需要时以 metadata 元素表达（如 `metadata["group"]`），治理分组路由（v0.5 Group filter）与四级摘除的 group 级按 metadata 选择器表达。取证对照：原产品 group 为实例属性（`Instance._groupId`，blank → 默认组）且 `InstanceKey` 不含 group、治理面另有 Group 实体；新产品进一步收敛为 metadata 元素——身份（ServiceKey/InstanceKey）稳定不随分组变化、推送单元（service 实例集快照，ADR-007）不碎片化、不用分组的服务零成本。架构 §4.2 投影组织与订阅选择器、roadmap v0.3 模型表述已同步。
- **update 语义幂等 upsert + 失败明细结构对齐**（review 定案）：update 不存在则自动注册、**缺席按 NEW 事件建租约**（定案 2026-10-11；register/update 服务端差异仅剩命中已有实例时的事件类型：register 无条件 NEW、update 发 UPDATE）；全部 Response（含 Heartbeat）从 Response 规范（见上）；失败明细结构随请求对齐——service 单位请求（register/update）经 common `ServiceInstanceErrors` 按 service 分组、字段名 `services` 镜像请求，实例键平铺请求（deregister）保持平铺、字段名 `instances` 镜像请求。

### 3. Maven reactor（10 模块一次到位）

| 模块 | release | 内容（本 phase 骨架级） |
|---|---|---|
| artemis（parent） | 25 | 插件栈与 dependencyManagement（§4） |
| artemis-common | 25 | **proto 生成 common.v1 + projection.v1（服务端共享，各 core 依赖）** + 包骨架；公共规范/工具随版本递补 |
| artemis-registry-core | 25 | proto 生成（registry+replication）+ api/internal 包骨架 + 生成验证单测；依赖 artemis-common |
| artemis-registry-server | 25 | `@SpringBootApplication` + context smoke test |
| artemis-discovery-core | 25 | proto 生成（discovery）+ 包骨架；依赖 artemis-common |
| artemis-discovery-server | 25 | 同 registry-server |
| artemis-management-core | 25 | proto 生成（management+registry）+ 包骨架；依赖 artemis-common |
| artemis-management-server | 25 | 同 registry-server |
| artemis-client | 8 | proto 生成（common+registry+discovery，protoc 3.25.x）+ Java 8 占位类 + 生成验证单测 |
| spring-boot-starter-artemis-client | 8 | pom + 空壳（自动装配 phase-7） |

全 reactor `${revision}` = `0.1.0-SNAPSHOT` + flatten-maven-plugin。

### 4. parent 插件栈与依赖断言

| 项 | 决策 |
|---|---|
| enforcer | `requireJavaVersion`(25)、`requireMavenVersion`(3.9)、`requireUpperBoundDeps`；**bannedDependencies**：各 core 模块禁 `org.springframework:*`（common 不单独配——core 的 banned 检查传递闭包，common 带 Spring 会在 core 构建失败）；artemis-client 禁 `org.mydotey.ai.artemis` 下 `artemis-common` 与各 `*-core`/`*-server` |
| Spotless | Palantir java-format，`spotless:check` 绑定 verify；`spotless:apply` 手动 |
| Checkstyle | parent 统一配置，最小规则集（命名/结构）起步 |
| JaCoCo | 出报告不设门槛 |
| Error Prone | **延至 phase-7（CI）前接入**：JDK 25 + Lombok + 多 release 混合编译组合下 annotationProcessorPaths 冲突风险高，脚手架阶段不引入，属有意延后非裁剪 |
| 测试栈 | JUnit 5 + Mockito + AssertJ，parent dependencyManagement；server 模块另加 spring-boot-starter-test |
| toolchains | client 模块 surefire 经 Maven toolchains fork JDK 8 JVM 跑测试（#27 口径）；环境前提：本机 JDK 8 + `~/.m2/toolchains.xml`（模板 `java/toolchains.xml.example`，复制后改 jdkHome），实现时验证，缺失则与用户商定（⑨ 验证含此项） |
| failsafe | parent pluginManagement 仅锁插件版本（不绑生命周期）；phase-4 集群语义测试接入时正式绑定（dev-test.sh integration 模式直调 goal 依赖此版本锁定，避免解析最新版不可复现） |

### 5. proto 生成配置

- 插件：ascopes protobuf-maven-plugin + protoc 二进制下载 + grpc-java 代码生成（service 需要）。
- **`sourceDirectories` 指向仓库顶层 `../../proto`（单 root），模块以 includes 圈定各自生成组**：跨组 import（`common/v1/model.proto`）需要单一解析根；若按组设多 root，`v1/` 相对路径在各组间撞名无法解析。此为 tech-stack §2.2「文件级引用」的实现形态（该节明示具体配置脚手架阶段验证）。
- **服务端 common.v1 与 projection.v1 集中生成于 artemis-common**（common 定案 2026-10-11；projection 定案 2026-10-12——registry-core 与 discovery-core 双消费，双生成同 FQCN 会在 v0.3 all-in-one 同 JVM classpath 加载序碰撞，单一 home 消解）：各 core 的 includes 不含这两组、经 Maven 依赖引用；client 独立自生成（common+registry+discovery，protoc 3.25）。服务端模块间可直传这两组类（同源 artifact）；多模块各自生成的组（registry.v1 见 registry-core 与 management-core）仍同 FQCN 异 artifact，跨模块只走 wire。
- 服务端 protoc 4.x、client 3.25.x；精确 patch 版本实现时按组件总表基线锁最新稳定。

### 6. 骨架代码纪律

- 所有 executor/线程规则、SCF、结构化日志等规范随首个业务 phase（phase-2）落地，本 phase 仅保证：包结构合规（`org.mydotey.ai.artemis.*`）、每模块至少一个可运行测试、无空模块。
- 各 server 模块依赖对应 core（装配依赖关系就位），Spring 只进 server。

### 7. 自动化测试入口（dev-test.sh + /dev-java-test command）

统一测试入口，双载体一个语义（定案 2026-10-12，review 新增）：

**`java/scripts/dev-test.sh`**——测试执行入口脚本（bash，`set -euo pipefail`，从脚本自身定位 reactor 不依赖 cwd）：

```bash
dev-test.sh [unit|integration|all]   # 默认 all
```

| 模式 | 命令 | 覆盖 |
|---|---|---|
| unit | `mvn test` | surefire `*Test` 单测，快速迭代 |
| integration | `mvn test-compile failsafe:integration-test failsafe:verify` | failsafe `*IT` 约定；**phase-1 无集成测试**，phase-4 集群语义测试接入时 failsafe 正式入 parent pom。前置 `test-compile`：直调 goal 不走 lifecycle 不编译，clean 树 0 测试静默假绿（code-review 定案 2026-10-12）；插件版本经 parent pluginManagement 锁定（§4） |
| all | `mvn verify` | 全量：spotless + enforcer + 单测 ≡ `mvn -f java/pom.xml verify`——**工作流 ⑤/⑧ 验收入口**（dev-java 工作流已修订为经本脚本 all 模式执行）；**默认模式**（无参数即全量，漏校验不放过） |

参数边界：默认值 `${1:-all}`；未知模式打印 usage 到 stderr 退出码 2；拒绝第二个及以后位置参数。

**`/dev-java-test` command**（`.claude/commands/dev-java-test.md`，纯 markdown 无 frontmatter，同 dev-java 形式）——会话侧入口：透传模式参数调用脚本，汇总各模块结果；失败时定位失败模块与用例、给出诊断结论，不擅自修改代码（修复走用户指示或工作流 ⑦）。

## 实现结果（⑦ 补）

实现要点（脚手架实测，锁定的精确版本见 tech-stack §3）：

- 10 模块 reactor 一次到位，`mvn -f java/pom.xml verify` 全绿（9 用例全过）；proto 生成矩阵、grpc stub 生成链路、Spring 上下文、client Java 8 交叉编译均经用例正向验证。
- **实现期决策 1（gson 上界）**：grpc-core 1.84.2 传递 gson 2.14.0，高于 SB BOM 管理线 2.13.2，触发 `requireUpperBoundDeps`；在 parent dependencyManagement 将 gson 显式钉 2.14.0 并置于 SB import 之前（同 pom 内先声明优先），一处根修覆盖 client 与 starter。
- **实现期决策 2（server JUnit 分治）**：SB 4.1 starter-test 的 `SpringExtension` 编译于 JUnit 6 API（依赖 `ExtensionContext.Store.computeIfAbsent` 的 6 签名），与全局 JUnit 5.14.4 冲突；全局保持 JUnit 5.14.4（Java 8 兼容线，client/starter/core），三个 server 模块以模块级 `junit-bom` 6.0.3 覆盖（随 SB 原生线）。
- **实现期决策 3（checkstyle 测试方法豁免）**：`MethodName` 严格 camel 规则与测试方法 `should_xxx_when_xxx` 惯例（开发规范 §7）冲突；`SuppressionXpathSingleFilter` 对 `src/test/java` 跳过 `MethodName`（checkstyle 14 已删 `TestMethodName`，测试命名由规范与 review 约束）。
- **实现期决策 4（checkstyle 排除生成代码）**：`maven-checkstyle-plugin` 显式限定 `sourceDirectories` 为手写主源码 + 测试源码，排除 proto 生成代码（生成类不适用命名/结构规则）；配置路径经 `${maven.multiModuleProjectDirectory}/config/checkstyle.xml`（实测该属性在 `mvn -f` 与子目录内启动均稳定解析为 `java/`，由 `java/.mvn/` 锚定）。
- **工具链前提**：本机 JDK 8 + `~/.m2/toolchains.xml`（模板 `java/toolchains.xml.example`）；client 测试实测 fork JDK `1.8.0_504` 通过。

⑦ code-review 修复（5 findings，均 hygiene/doc 类，无 correctness bug）：

| # | finding | 处理 |
|---|---|---|
| 1 | `.gitignore` 漏 `.flattened-pom.xml`（flatten 每次 verify 重写，会入库并造成 diff churn） | `.gitignore` 补 `.flattened-pom.xml` |
| 2 | 本文档两处 ADR 链接多一层 `../`（4 层 up 越出 docs/） | 修为 3 层 up（adr-013/adr-014 断链修复） |
| 3 | `.claude/rules/tech-java.md` 与 tech-stack §2.2 用 `protoSourceRoot`（xolstice 旧参数，ascopes 实际为 `sourceDirectories`，误用被静默忽略致生成失败） | 全部改为 `sourceDirectories`（tech-java.md、tech-stack §2.2×2/#6、本文档 §5） |
| 4 | client `jdkToolchain` 硬依赖外部 `~/.m2/toolchains.xml`，仓库不提供也不守卫 | 新增 `java/toolchains.xml.example` 模板并在此登记（环境前提按设计 §4 声明） |
| 5 | command 与脚本对「单一来源」措辞不一（脚本 pin 死版本路径，会漂移） | 两侧统一为「当前 phase 设计文档 §7（见 dev-state.md）」 |

## 文档同步（⑩ 填）

本 phase（含 ⑦ fix）变更文档清单：

| 文档 | 变更 |
|---|---|
| `docs/product/artemis.md` | **新建**（⑩ 必须步骤，首个收尾 phase 建立）：产品定位、核心概念、开发者入口（仓库结构/构建测试/模块）、交付状态 |
| `docs/product/README.md` | 登记 `artemis.md` |
| `docs/README.md` | product 行说明补「产品文档」 |
| `CLAUDE.md` | 「当前状态」去「尚无源码」；新增「构建与测试（Java）」节（命令、混合编译与 toolchains 前提、模块边界指针） |
| `docs/dev/tech-stack-java.md` | v1.8：`protoSourceRoot`→`sourceDirectories`（§2.2/#6）；#3 组件总表锁定脚手架实测精确 patch |
| `.claude/rules/tech-java.md` | `protoSourceRoot`→`sourceDirectories` |
| `.claude/commands/dev-java-test.md` | 新增于本 phase（⑦：单一来源指针措辞统一） |
| `java/scripts/dev-test.sh` | 新增于本 phase（⑦：注释措辞统一） |
| `.gitignore` | 增 `target/`、`.flattened-pom.xml` |
| 本 phase 设计/测试文档 | 实现结果、验证清单结论、阶段状态更新 |

③ 架构同步（review 定案即执行、前序 commit 已落，此处仅登记落点）：架构 §4.1/§8、`docs/decisions/adr-012`/`adr-013`/`adr-014`、`docs/dev/proto-contract.md` §2、roadmap v0.1 内核条目、`plan.md` phase-2 主题——逐条同步落点见本文「产品设计」节的 review 定案 bullet。

## 更新历史

| 版本 | 日期 | 变更说明 |
| ------ | ------ | ------ |
| 1.23 | 2026-10-12 | ⑩ 文档同步：产品文档 `docs/product/artemis.md` 新建，索引与 CLAUDE.md 更新，变更清单登记 |
| 1.22 | 2026-10-12 | ⑦ fix（5 findings）：断链修复（adr-013/014 三层 up）；`protoSourceRoot`→`sourceDirectories`；补 toolchains 模板；实现结果节登记脚手架实测决策（gson 上界、server JUnit 6 分治、checkstyle 测试豁免与生成代码排除） |
| 1.21 | 2026-10-12 | code-review 修复：Error→ErrorInfo（java.lang.Error 通配 import 歧义）；Heartbeat 加 seq 关联字段（bidi 不保证 1:1 ack）；projection.v1 归 artemis-common（all-in-one classpath 去重，adr 关联）；integration 模式前置 test-compile 修假绿 + failsafe pluginManagement 锁版本 + 参数边界；update 行派生事件补缺席 NEW；定案节按架构同步纪律指针化压缩；断链修复 |
| 1.20 | 2026-10-12 | review 定案：dev-java 工作流 ⑩ 增产品文档 docs/product/artemis.md 必须同步步骤（phase 收尾递补、首个收尾 phase 建立）；本 phase 范围补其建立 |
| 1.19 | 2026-10-12 | review 定案：dev-java 工作流集成 dev-test.sh——⑤/⑧ 执行入口与作用说明、附录 C 测试文档模板集成（方案节执行入口与形态↔模式映射、回归记录经脚本并记模式） |
| 1.18 | 2026-10-12 | review 定案：command 更名 /dev-java-test（文件 dev-java-test.md，对齐 dev-java）；脚本默认模式改 all（无参数即全量） |
| 1.17 | 2026-10-12 | review 定案：新增自动化测试入口 java/scripts/dev-test.sh（unit/integration/all 三模式，all ≡ verify）与 /dev-test command（§7） |
| 1.16 | 2026-10-12 | review 定案：分组退出数据模型——ServiceKey 去 group、Instance 不设字段，以 metadata 元素表达；架构 §4.2/roadmap 同步 |
| 1.15 | 2026-10-12 | review 定案：心跳不做集合 diff（逐实例续约 + 缺席 upsert，缺席等 TTL 最终一致，client_id 不需要）；Instance 缺省语义显式化（weight ≤0 归一 100、UNSPECIFIED 同 UP） |
| 1.14 | 2026-10-12 | code-review 修复：文档漂移对账（bidi 表述统一、错误码注册表去重指向单一来源、Ack fail-open 取舍补记、多轮叠层矛盾清理） |
| 1.13 | 2026-10-11 | review 定案：proto 生成模型 server/client 分治——服务端 common.v1 集中生成于 artemis-common，client 独立自生成；tech-stack/guidelines/proto-contract/arch 同步 |
| 1.12 | 2026-10-11 | 架构同步即时执行（工作流新纪律）：arch §4.1/§7/§8、adr-012、proto-contract §2、roadmap、plan.md 同步落地，⑩ 记账转为已执行 |
| 1.11 | 2026-10-11 | review 定案：update 缺席按 NEW 事件建租约（register/update 差异仅剩命中时事件类型） |
| 1.10 | 2026-10-11 | review 修正：update 语义改幂等 upsert（不存在自动注册），NOT_FOUND 移出初始错误码分配 |
| 1.9 | 2026-10-11 | review 定案：Response 全量统一 status + data（含 Heartbeat）；Data 命名 XxxResponseData；失败明细随请求结构对齐（common 增 ServiceInstanceErrors） |
| 1.8 | 2026-10-11 | review 定案：Ack 简化为二态 SUCCESS/FAIL（部分失败即 FAIL，范围经 data 明细表达，有意偏离原产品三态） |
| 1.7 | 2026-10-11 | review 定案：Error.code 改 string（契约注册表标识）；ResponseStatus 收敛为 ack + error（单数）；确立 Response 规范形式 status + data（data 为各操作返回数据消息，泛型 T 以约定落地），三操作新增 Data 消息承载 partial_fail 明细 |
| 1.6 | 2026-10-11 | review 定案：错误码改 Error struct（int code + message，无成功保留值，成败由 ack 判定），去掉 ErrorCode 枚举；ResponseStatus/Ack/Error/InstanceError 上移 common.v1；补错误码注册表初始分配 |
| 1.5 | 2026-10-11 | review 定案：错误码保持 proto enum + UNKNOWN 客户端哨兵映射（SUCCESS=0/UNKNOWN=1/具体码从 2 起，InstanceStatus 同纪律）；三操作响应独立 + 共用 ResponseStatus（ack 三态 + errors） |
| 1.4 | 2026-10-11 | review 修订：三操作响应拆分（RegisterResponse/DeregisterResponse/UpdateResponse，独立演化）；错误码表示待定（int32 vs proto enum 分析中） |
| 1.3 | 2026-10-11 | review 定案：注册模型四操作化（register/deregister/update 显式操作 + heartbeat 全量对账），registry.v1 契约重写，common.v1 增 InstanceKey；登记架构 §4.1 同步事项 |
| 1.2 | 2026-10-11 | review 修订：删除 InstanceSet 容器内联进 HeartbeatRequest；设计要点补 cluster 表达策略、repeated vs map 选型理由 |
| 1.1 | 2026-10-10 | 产品设计定稿（proto 契约、reactor 模块、插件栈与断言），待 review |
| 1.0 | 2026-10-10 | 开工骨架 |
