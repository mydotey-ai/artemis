# Artemis 原产品行业对标分析报告（Industry Benchmark）

版本: 1.3    更新时间: 2026-10-09

> 调研对象：原产品 `~/Projects/mydotey/artemis`（version 2.0.2，2016 年设计、2020-12 技术栈定格）。
> 定位：**判断层 · 行业对标**——以 2026-10 注册中心行业的普遍水位为参照系，评估原产品的优劣，回答三个问题：① 当年（2016 语境）它做对了什么；② 今天（2026 语境）直接拿去投产差距在哪；③ 对重设计，哪些值得继承、哪些必须现代化、哪些要从零建。
> 证据来源：原产品事实全部溯源自本仓库调研文档集（[基线](legacy-product-analysis.md)、[产品总览](product-overview.md)、[NFR 规格](nfr-spec.md)、[架构视图](arch/README.md)），不再重复取证；行业事实来自 2026-10 官方一手来源调研，关键处随文标注来源，集中清单见 §7。
> 方法论约定：区分「**产品能力**」与「**载体/部署默认形态**」——产品提供机制（含扩展点）即算能力，宿主未启用属部署形态差别，不计为缺失（如 metric provider 注入、配置源注入，见 [product-overview](product-overview.md) §3.5–3.6）。

---

## 1. 行业格局速览（2026-10）

本节是评判的参照系。三层结论：

**① 品类没有消失，但形态被重新定义。** 独立注册中心作为品类仍活跃——开源侧 Nacos（3.2.4，月度节奏）、Consul（2.0.4，归 IBM 后刚过大版本）、etcd（3.7.2）均在活跃迭代；商业侧四大云厂商全部持续经营托管产品（阿里云 MSE、腾讯云 TSE、华为云 CSE、AWS Cloud Map），卖点收敛于免运维、SLA（99.95% 常见）、平滑迁移、安全、防雪崩，并出现「按量 Serverless」新档位。但纯 K8s 场景的注册发现职能已被 API server + EndpointSlice + CoreDNS 吸收（registration 变成 controller 的副产物、health 变成 probe）；多集群发现的标准锚点是 MCS API + 网络层方案（Cilium ClusterMesh、Submariner），而非「再装一个全局注册中心」；网格侧 Istio ambient 已 GA（1.24，2024-11），服务来源收敛为 K8s Service + ServiceEntry。独立注册中心的生态位收窄为：**非 K8s / 混合部署负载的统一发现 + 治理规则面 + 多环境多租户聚合层**。

**② 技术基线十年内完成了一轮整体抬升。** 与原产品设计期相比，2026 年的行业普遍水位：一致性上「分层混合」成为主流——易失的实例数据走 AP、元数据与配置走 Raft（Nacos Distro + JRaft；Consul Raft + gossip 只做成员）；传输上短连接轮询被长连接双向流取代（Nacos 2.0 gRPC 化官方称 10 倍性能提升；etcd watch；K8s watch EndpointSlice）；安全上**默认鉴权**成为新共识（Nacos 3.0 起 console/inner API 默认鉴权、v1 API 默认关闭），TLS/mTLS + 细粒度授权为标配；韧性上客户端磁盘快照回放（Nacos）、snapshot 工具链（etcd）成为兜底标配；可观测上 Prometheus + tracing + console + 健康自检是标准配置；接口面是 DNS + HTTP/gRPC OpenAPI + xDS 多面。规模叙事从「十万实例」变成「百万级服务」（Nacos 官网）。

**③ Eureka 谱系（AP）的思想活着，形态变了。** Netflix 官方 wiki 明确 2.0 已废弃（"use at your own risk"）、1.x 维持性质维护；Spring Cloud Netflix 仓库已移除 Ribbon/Hystrix/Zuul，仅存 eureka-client/server。Eureka 的「心跳 + AP 复制 + 自我保护」思想经 Nacos Distro 继承并改进：全对全复制改为**带快照校验的增量同步**，HTTP 短连接换 gRPC 长连接，持久数据另走 Raft 分层，自我保护从隐式全局开关演进为显式产品化承诺（腾讯云 TSE「实例剔除超量即停止剔除，防止雪崩」）。**纯 AP-only 的新建系统在 2026 年不再出现。**

## 2. 原产品概要（对标对象）

一段话定位：**AP 型微服务注册中心**——对等节点全对全异步复制 + 租约心跳（「心跳即注册」）+ Eureka 式自我保护 + 静态集群成员；数据面（注册表，纯内存零持久化）与管理面（DB 持久化流量治理元数据）双轨分离；差异化价值在流量治理（服务分组、加权路由、两段式灰度、一键 Canary、逻辑实例）。支撑过 10 万+ 服务实例（前公司生产实绩，作者确认；无压测数据留档）。工程规模 408 个 Java 文件 ≈ 3 万行；时间线：2016 内网落地 → 2017-10 开源 → 2020-12 Spring Boot 2.3 重写 → 2021–2025 完全沉寂 → 2026-03 单机化改造。完整能力全景见[基线](legacy-product-analysis.md)。

关键画像（对标用）：

| 项 | 原产品 |
|---|---|
| 一致性 | 纯 AP，region 内全对全异步复制，无 leader/quorum/版本向量 |
| 注册模型 | 心跳即注册：客户端本地实例集为事实源，5s 全量上报，幂等对账 |
| 剔除时效 | TTL 20s + clean 1s ≈ 21s；自我保护（窗口 10s / 阈值 85%） |
| 推送 | WS 订阅推送（亚秒级，at-most-once）+ 60s/15min 三层兜底轮询 |
| 持久化 | 数据面零持久化；客户端无磁盘快照（两侧同时无） |
| 安全 | 无认证、无 TLS、DB 明文；region/zone 软隔离 |
| 客户端 | 纯 Java SDK，绑定 org.mydotey.* 自研库栈（已发布 Maven Central，可解析获取） |
| 管理 | 57 API 无 UI；四级摘除级联；审计双写 log 表 |
| 观测 | 状态 API 7 端点（排障利器）；metric/trace provider 可注入但开源实现空壳 |
| 工程 | 技术栈定格 2020-12 且已 EOL；核心路径零测试、无 CI、无容器化 |

## 3. 十维度对标详析

判定分级（相对 2026 行业基线）：**领先** / **持平** / **落后** / **缺失**；每维度同时标注 2016 语境下的历史评价。判定依据的行级证据在既有调研文档中，此处只给结论与关键对照。

### 3.1 功能完整性 —— 判定：核心完整、治理领先、外围缺失（历史：领先）

**行业基线（2026）**：注册发现只是最小底座；配置管理、治理规则（路由/限流/熔断）、多租户（namespace/ACL）为标配扩展面；头部产品（Nacos 3.x）已把 AI 资产注册（MCP/A2A/Prompt）纳入同一注册中心；接口面为 DNS + HTTP/gRPC + xDS 多面。

**原产品实况**：核心注册发现链路完整（注册/续约/剔除/自我保护/订阅推送/兜底轮询/版本化缓存），且**流量治理是超越时代的差异化能力**——服务分组 + 加权路由 + **两段式灰度发布**（weight/unreleased_weight + 显式 release）+ 一键 Canary + 逻辑实例（非注册体系的第三方系统纳入统一发现视图）。这套能力对应的正是 2026 年托管产品的卖点（MSE「灰度发布」、CSE「Spring Cloud/Dubbo 零代码接入治理」），其中两段式权重灰度、按 IP 批量拉入拉出（四级摘除级联）在开源自建方案中至今不算标配。

**差距**：无多租户/多环境（region 是集群边界不是租户边界）；无配置管理；无 DNS 接口；订阅关系/调用方视图缺失；实例 metadata 不可修改；API 无版本化（无 `/v1/`，无协议协商）。

### 3.2 性能与容量 —— 判定：正常路径持平、规模化路径落后（历史：领先）

**行业基线（2026）**：长连接推送 + 增量同步为底线；秒级收敛是当前预期；公开叙事以「百万级服务」为标尺（Nacos 官网）；etcd 官方 benchmark 单机读 16.6k QPS@P90 6.1ms。

**原产品实况（持平部分）**：变更推送亚秒级，达到当代预期；实例宕机摘除 ≈21s，与当代惯例（「秒级心跳、十秒级标记、三十秒级剔除」）同量级；批量贯穿一切（API/复制/lookup 全批量、心跳按进程聚合为一条消息）是规模化立身之本；读写路径分离彻底（读纯内存 + 后台快照，请求线程零锁等待零 DB 零 peer 调用）。

**原产品实况（落后部分）**：

- **写放大天花板**：全对全复制使集群总投递量 ∝ N−1，且加节点不提升单节点可承载写容量（[quality](arch/quality.md) §5.2 定量模型）——对比 Nacos Distro 的「快照 + 增量同步」，这是被行业明确超越的架构代差。
- **长尾不一致**：漏推场景最久 15min 才由兜底轮询纠正，且无数据版本/世代概念，「落后多少」不可度量——当代基线是增量 + 版本去重 + 快照回放。
- **协议栈代差**：WS + JSON 文本帧，客户端 incoming 缓冲默认 8KB（上限 32KB），与「批量、全量、10 万实例」叙事直接冲突；行业已完成 gRPC/HTTP2 二进制长连接迁移。
- 首次注册可发现 ≈6s（心跳间隔 + 补注册），比当代「注册即推送」慢一个心跳周期。

### 3.3 可用性 —— 判定：理念持平、兜底层次落后（历史：领先）

**行业基线（2026）**：分层高可用（AP 数据面 + Raft 元数据面）+ **客户端本地磁盘快照兜底**；托管 SLA 99.95% + 多可用区 + 自动恢复。

**原产品实况（持平部分）**：「可用性换一致性」的组合拳贯彻得非常彻底，是 Eureka 谱系的优秀实践：数据面对等无单点、任一节点可读写；客户端缓存永不失效（server 全挂仍可发现）；三级地址容灾（引导地址 → 存活列表 → 随机选址 + 熔断 + TTL 轮换）；幂等对账（断网/重启自动重注册）；readiness 门控（数据不全不接流量）；五重收敛路径每个不可靠组件都有上游对账。

**原产品实况（落后部分）**：持久化兜底**两侧同时缺失**——服务端注册表零持久化（全集群同时重启只能靠客户端风暴式重注册，无 DB 可救）、客户端无磁盘快照（server 全挂 + 客户端重启 = 发现完全不可用）——对比 Eureka/Nacos 均有磁盘 snapshot；冷启动空集群死锁（空数据拒绝就绪）；节点故障恢复依赖至少一个存活 peer。

### 3.4 稳定性 —— 判定：机制方向正确、实现质量落后（历史：持平偏上）

**行业基线（2026）**：防雪崩从隐式自我保护演进为**显式产品化承诺**（TSE「实例剔除超量即停止剔除」）+ 推空保护/过载保护（MSE）；一致性校验与快照回放为韧性标配。

**原产品实况**：2016 年就具备自我保护（窗口统计 + 阈值跳过清理）+ 分通道限流（registry 100k/replication 1M/超限返回 rate-limited 而非挂死）+ 显式 unregister 绕过保护的语义边界——**方向完全正确**，Nacos/TSE 在做的事当年已有原型。落后在三点：

- 自我保护**全局单阈值**（85%/峰值 50 经验值），无法区分局部异常与网络故障；保护态对消费方静默（拿到已死实例不可知）。
- **缺陷密度高**：基线 §6 23 条局限之外，规格层补证又发现 20+ 条实现级缺陷（批量复制重试截断、缓冲保护失效、WS 解析异常回固定 success、UP 无回退/DOWN 粘性、SQLite 分支破坏两段式发布、`Service.clone()` 不拷贝 routeRules 等，索引见 [product-overview](product-overview.md) §6）——核心路径（复制/推送/租约/故障转移）**零测试覆盖**，这些缺陷在当代工程标准下是不可接受的出厂状态。
- 发现查询通道（lookup/getServices）**无限流**——读路径未受保护。

### 3.5 可管理性 —— 判定：语义模型领先、产品化形态缺失（历史：领先）

**行业基线（2026）**：console 独立部署（Nacos 3.0）+ Admin API 三分类 + 一键扩缩容 + 灰度升级 + 显式升级路径（breaking changes 清单）；数据面/管理面分离以「软件内 console 独立」实现。

**原产品实况（领先部分）**：管理**语义模型**至今优秀——四级摘除级联（instance → server → zone → group，按故障半径摘除）+「操作记录即状态」（下线 = 可叠加、带原因、可审计的记录，不删数据，恢复即删记录）+ 管理操作全量审计双写 + 6 个 force 逃生开关。这套语义比多数当代产品的「直接改注册表」式管理精细得多。

**原产品实况（缺失部分）**：无控制台 UI（内部 console 未开源，仓库 0 前端文件）；无分页无模糊搜索（10 万实例下管理查询不可用）；57 个表驱动细粒度 API（一次灰度发布需串 5 个 API）；扩缩容 = 改配置 + 重启（静态成员 + URL 子串识别本机）；管理面变更生效靠 `sleep(2s)` + 轮询，无生效确认；无 RBAC/审批流。

### 3.6 易用性 —— 判定：落后（历史：持平——内部产品语境）

**行业基线（2026）**：开箱即用（内嵌存储起步）+ 容器化安装 + 多语言 SDK + DNS/HTTP 开放协议；「零代码迁移」是云厂商采购决策的一级因子；Spring Boot starter 自动装配是 Java 生态标配。

**原产品实况**：依赖可得性无障碍——org.mydotey.* 自研库栈（scf/rpc-util/caravan/codec/lang-extension/circular-buffer）**均已发布 Maven Central 且版本覆盖**（勘误见[基线](legacy-product-analysis.md) §8-14），外部工程可直接解析；但接入体验显著低于当代基线：无 Spring Boot starter/自动装配（纯 API + 手工配置三件套）；纯 Java 单语言（无 DNS/HTTP 开放协议面）；SDK 无生命周期 API（无 close()，回调线程 non-daemon 阻止 JVM 退出）；`register()` 语义隐晦（实际靠心跳通道生效，WS 起不来则不可发现且消费方不可见）；首次 getService 失败静默返回空 Service；自研库栈虽可得但属小众生态，文档与社区面窄。需要公允记录的是：这是**内部框架部门产品**的正常形态（配置源/指标由宿主注入是刻意设计），但按「产品」标准评价，接入成本仍显著高于行业基线。

### 3.7 可观察性 —— 判定：排障设计领先、指标体系形态落后（历史：领先）

**行业基线（2026）**：Prometheus metrics + tracing 插件 + Web console + 健康自检（per-node/per-leader 指标）为标准配置；注册中心自身行为（剔除、复制延迟、连接数）需可量化。

**原产品实况（领先部分）**：状态 API 设计是排障利器——`config.json`（全部配置 + 来源）、`leases.json`（租约明细 + 自我保护统计）、cluster/deployment/ws-connection 共 7 端点，这种「把内部状态无保留暴露」的设计直觉在当代产品中仍属上乘。

**原产品实况（落后部分）**：metric/trace 埋点点位完整、provider 可注入（产品能力，内网接 caravan 体系），但**开源形态零落地**（NullProvider、MetricLoggerHelper 空方法体），且 provider 协议是私有体系而非 Prometheus 事实标准；无指标时间序列、无大盘、无告警、无 traceId 贯穿。10 万实例规模下「当前是否处于保护态」「哪个节点落后多少」无法自动回答——按当代 SRE 实践不可运维。

### 3.8 可维护性 —— 判定：缺失（历史：落后——开源即沉寂）

**行业基线（2026）**：插件化架构（存储/鉴权/追踪/数据源皆插件）、清晰升级文档与兼容矩阵、活跃社区与明确归属（阿里/IBM/Apache/CNCF）。

**原产品实况**：技术栈定格 2020-12 且**整体 EOL**（Java 8、Spring Boot 2.3.6、javax、springfox 3.0.0 已死项目、HttpClient 4、MySQL Connector 5.1、JUnit 4 + Mockito 1.10）；分布式核心路径（复制/推送/租约/故障转移）**零测试**（仅 management DAO 层 57 @Test）；无 CI/CD、无 Dockerfile、无 K8s manifest；无迁移路径（无 API 版本、无数据格式版本、无滚动升级协议）；2021–2025 完全沉寂，26 commits 单人分支。**作为开源项目已死**——社区维度无维护性可言。

### 3.9 成本 —— 判定：小规模友好、规模化结构差（历史：领先）

**行业基线（2026）**：内存型注册表 + 按需持久化是低成本主流（Nacos 模式）；3 节点起步；托管定价出现按量档（华为云 CSE ¥1.747/h 起、Cloud Map 按操作计费、MSE Serverless）。

**原产品实况（友好部分）**：**部署依赖成本是同类最低档**——单一 fat jar、数据面零外部依赖（无 MQ/无缓存/无 DB，管理面可整体关闭或退化为 SQLite 单文件）；10 万实例注册表内存约百 MB 量级（推导模型，[quality](arch/quality.md) §5.3，未经实测）；批量 + gzip 全链路控制带宽。

**原产品实况（差的部分）**：写放大使成本随规模**结构性恶化**——集群总投递量 ∝ N−1，加节点只买读容量不买写容量还抬高每节点带宽/CPU 负载；无弹性伸缩（静态成员，扩容 = 改配置重启）；对照托管产品「按量 Serverless」档，自建 3 节点的隐性运维成本（无观测、无自动恢复）在 2026 年的比较坐标下更高。

### 3.10 安全 —— 判定：缺失（历史：落后——但为时代惯例）

**行业基线（2026）**：**默认开启鉴权成为共识**（Nacos 3.0）；TLS/mTLS + 细粒度授权为标配（Consul ACL + TLS 已演进到后量子混合 KEX；MSE RAM 五级粒度授权）；多租户 namespace 隔离。

**原产品实况**：几乎为零——全 API 无认证鉴权（`no-permission` 错误码有产出点但产出的是 region/zone 准入，非身份校验）；明文 http/ws 无 TLS 配置点；DB 密码明文（样例 admin/123456）；管理写接口（摘除实例、改路由权重）同样裸奔；`OperationContext.token` 只存不验；唯一屏障 WS IP 黑名单。**开源形态不可上生产**。公允记录：2016 年内网产品的「信任内网」是当时行业常态（Eureka 同期亦无鉴权），但 2026 年这是第一道不可逾越的门槛。

## 4. 横向对照矩阵

原产品与代表性产品按关键维度对照（行业侧数据来源见 §7；原产品侧见 §2 画像）：

| 维度 | Artemis（2016 设计） | Eureka 1.x（同代，维护态） | Nacos 3.x（2026 标杆） | Consul 2.x | etcd 3.7 |
|---|---|---|---|---|---|
| 一致性 | 纯 AP，全对全复制 | 纯 AP，peer 异步复制 | 分层：AP（Distro 临时实例）+ CP（JRaft 持久/元数据） | CP（Raft）+ gossip 成员 | CP（Raft） |
| 注册/保活 | 心跳即注册（全量幂等对账） | register + renew 两段式 | 临时实例心跳 / 持久实例 Raft | agent 注册 + 多类型探测 | lease TTL 保活 |
| 健康检查 | 客户端自报（healthCheckUrl 零消费） | 客户端心跳 | 心跳 + 服务端主动探测（TCP/HTTP/MySQL/Redis/自定义） | agent 主动探测（HTTP/TCP/gRPC/Script/TTL…） + critical 超时注销 | lease 到期即失效 |
| 订阅推送 | WS 推送（at-most-once）+ 15min 兜底 | 客户端 30s 级定时全量拉取 | gRPC 长连接双向流 + 增量 + 磁盘快照回放 | watch / blocking query + agent 缓存 | watch 增量事件流 |
| 持久化 | 零持久化（两侧均无快照） | 服务端/客户端磁盘快照 | 内存 + 持久数据外置 | Raft WAL | Raft + snapshot 工具链 |
| 流量治理 | **分组/加权路由/两段式灰度/Canary/逻辑实例** | 无 | 治理规则 + MSE 商业化灰度 | mesh 流量规则 | 无（纯 KV） |
| 多租户 | 无（region=集群边界） | 无 | namespace（环境/租户/业务域） | ACL namespace | 无（靠前缀约定） |
| 安全 | 无 | 无 | **默认鉴权** + TLS + KMS | ACL + TLS（后量子 KEX 已入 rc） | TLS + RBAC |
| 客户端 | Java 单语言 SDK（自研库栈已发布 Central） | Java（Spring Cloud 生态） | 多语言 SDK + DNS/HTTP/gRPC + proto 统一协议 | DNS + HTTP（语言无关） | gRPC/HTTP 多语言 |
| 规模验证 | 10 万+（作者确认生产实绩） | Netflix 大规模 | 官网宣称 millions of services | 无公开数据 | 单机读 16.6k QPS（官方 benchmark） |
| 工程状态 | EOL 栈、零核心测试、已沉寂 | 维护态（wiki 明示 2.0 废弃） | 月度发版、Apache 2.0、33k stars | 月度发版、IBM、BUSL | 季度节奏、CNCF graduated |

矩阵读法：原产品与同代 Eureka 相比在**推送时效（WS vs 30s 轮询）、治理能力、幂等对账模型**上领先，与 2026 标杆相比在**一致性分层、持久化兜底、安全、多租户、客户端生态、工程状态**上全面落后——落后的主体是「行业十年演进的红利」，而非当初设计判断的错误。

## 5. 综合评判

### 5.1 历史语境（2016）：一个领先于时代的内部产品

对标当年可参照系（Eureka 1.x 为当时主流、ZK 为 CP 代表），原产品在设计上有五处明确领先，且都经受了 10 万级实例的生产验证：

1. **心跳即注册**比 Eureka 的 register + renew 两段式更优雅——一条通道完成注册、续约、对账，断网续注册免费获得，免显式状态机。
2. **WS 推送**比 Eureka 客户端 30s 定时全量拉取领先整整一代——亚秒级变更可见。
3. **流量治理面**（分组/加权路由/两段式灰度/Canary/逻辑实例）是 Eureka 完全没有的能力，对应的是当年只有头部互联网内部平台才有的 SOA 治理；这套能力直到今天仍是托管产品卖点，设计并未过时。
4. **批量贯穿 + 心跳按进程聚合**：心跳消息率的自变量是进程数而非实例数，这是规模化叙事里最经济的设计之一。
5. **双轨分离 + readiness 门控**：管理面可整体关闭、数据面零依赖、数据不全不接流量——2016 年就把故障隔离与启动安全想清楚了。

需要同时记录的史实：产品错过了 2018–2022 的行业大演进窗口（gRPC 化、Raft 分层、云原生化、默认安全）——恰好是 Nacos 完成 2.0（2021）→ 3.0（2025）跃迁、行业基线整体抬升的时期，原仓库在 2021–2025 完全沉寂。**今天评估中的大部分「落后」，本质是这五年缺席的复利。**

### 5.2 当下语境（2026）：不可投产，但是高价值设计资产库

直接投产判定：**不可行**。两重一票否决——安全缺失（开源形态不可上生产）、可维护性缺失（EOL 栈 + 零核心测试 + 已死项目），任一项都足以否决；易用性为显著落后（依赖可从 Maven Central 解析获取，但无 starter/多语言/生命周期 API），不足以单独否决但加剧差距。

但作为**设计资产库**，其价值密度高于多数同代开源产品：12 条可继承设计资产（基线 §5）中，「心跳即注册幂等对账」「三级地址容灾」「错误码驱动容错」「四级摘除级联」「两段式权重发布」「启动门控」「批量贯穿」在 2026 年的设计坐标系里依然是正确答案；五条跨决策组合红利（[decisions](arch/decisions.md) §0——AP 语义自洽、读写路径分离、双轨零依赖、批量异步扇出、节点故障客户端无感）是架构层的正资产。**「设计领先、工程搁浅」是这个产品的准确墓志铭。**

### 5.3 行业坐标中的定位

- 它的 AP 血统（Eureka 谱系）没有死，但活着的形态是 **Nacos 式分层**：实例数据 AP + 元数据 Raft + 长连接 + 快照兜底 + 默认安全。纯 AP-only、全对全复制、静态成员的组合在 2026 年新建系统中不再出现。
- 它的治理特色（两段式灰度、摘除即记录、逻辑实例）在 2026 年的开源产品中仍非标配——这是重设计最值得保留的差异化筹码，且与托管产品卖点同向。
- 它的生态位判断需要重做：若新产品只服务 K8s 负载，注册职能已被 EndpointSlice + CoreDNS 吸收；独立注册中心的合理生态位是**混合部署（VM + 容器）统一发现 + 治理规则面 + 多环境/多租户聚合**，这正是原产品当年在携程内网实际占据的位置——时代变了，但这个生态位在混合云语境下反而更宽了。

## 6. 对重设计的启示（三层结论）

结合基线 §5（可继承资产）、§6（局限清单）与本报告行业对照，重设计输入分三层：

**第一层：仍然领先，直接继承**——心跳即注册的幂等对账语义（补持久化）；流量治理面全套语义（两段式灰度、四级摘除级联、操作记录即状态、逻辑实例）；双轨分离（但兑现为部署隔离）；readiness 门控；三级地址容灾与错误码驱动容错；批量贯穿；状态 API 排障面。

**第二层：被行业超越，必须现代化**——复制协议：全对全 → 快照校验 + 增量同步（Distro 路线），并给数据加版本/世代使「落后多少」可度量；传输协议：WS+JSON → gRPC/HTTP2 长连接（含心跳与推送统一，消除三套传输重复实现）；自我保护：隐式全局单阈值 → 显式化、可观测、可配置阈值（对标 TSE/MSE 的产品化承诺），保护态需告知消费方；可观测：私有 provider → Prometheus/tracing 事实标准 + 告警；管理面：表驱动 57 API + sleep(2s) → console + Admin API + 生效确认（版本/ack 数）；自我保护与 TTL 的职责边界、发现通道限流补齐。

**第三层：从未达标，从零建**——安全（认证/TLS/细粒度授权/凭据保护，重设计的一级需求而非选项）；持久化与快照（服务端 snapshot + 客户端磁盘回放，补齐「两侧同时无」的结构性缺口）；客户端生态（starter 自动装配、生命周期 API、多语言或开放协议优先、基于行业标准库栈而非自研小众库）；工程基线（核心路径测试、CI/CD、容器化、滚动升级协议与 API 版本化——「可安全滚动升级」是从零开始的能力，不是改进）。

## 7. 行业事实来源清单

本报告引用的行业事实均来自 2026-10 官方一手来源（版本号、发布节奏、能力项、引言）：

| 主题 | 来源 |
|---|---|
| Nacos 版本/3.0 架构/AI Registry/xDS/默认鉴权 | <https://github.com/alibaba/nacos/releases>（3.0.0、3.2.4 等）、<https://nacos.io/en/>、<https://nacos.io/en/blog/nacos-gvr7dx_awbbpb_gzlzyehxberthsng/> |
| Consul 版本/架构/Raft+Serf/健康检查/BUSL/IBM | <https://github.com/hashicorp/consul/releases>、<https://developer.hashicorp.com/consul/docs/architecture>、<https://developer.hashicorp.com/consul/docs/agent/checks>、<https://www.hashicorp.com/blog/hashicorp-joins-ibm> |
| Eureka 维护状态/2.0 废弃/Spring 侧现状 | <https://github.com/Netflix/eureka/wiki>、<https://github.com/spring-cloud/spring-cloud-netflix> |
| etcd 版本/benchmark/watch | <https://github.com/etcd-io/etcd/releases>、<https://etcd.io/docs/v3.6/benchmarks/etcd-3-demo-benchmarks/> |
| K8s EndpointSlice/CoreDNS | <https://kubernetes.io/docs/concepts/services-networking/endpoint-slices/>、<https://coredns.io/> |
| Istio ambient GA/服务来源 | <https://istio.io/latest/blog/2024/ambient-reaches-ga/>、<https://istio.io/latest/docs/ops/configuration/traffic-management/traffic-routing/> |
| 多集群（Cilium/Submariner/MCS API） | <https://docs.cilium.io/en/stable/network/clustermesh/intro/>、<https://submariner.io/>、<https://github.com/kubernetes-sigs/mcs-api> |
| 托管产品（MSE/TSE/CSE/Cloud Map） | <https://www.aliyun.com/product/aliware/mse>、<https://cloud.tencent.com/product/tse>、<https://www.huaweicloud.com/product/cse.html>、<https://aws.amazon.com/cloud-map/> |
| Kafka 去 ZK（ZK 边缘化信号） | <https://kafka.apache.org/blog/2025/03/18/apache-kafka-4.0.0-release-announcement/> |

本报告仅采用已核实事实；以下 12 项未能从一手来源核实，凡涉及其中的表述均已随文标注「待核实」或采用回避该事实的措辞，此处集中登记（调研工作笔记另存 `docs/plans/`，不入库）：

1. Nacos 3.x naming/config/metadata 完全分离部署（官方仅确认 console 独立部署 + API 三分类）
2. Distro 协议官方文档专页（同步批大小、校验周期等细节）
3. Nacos/MSE 公开压测报告的精确数字（十万/百万实例、TPS/QPS）
4. Spring 2018-12「Spring Cloud Netflix 模块进入维护模式」博客原文（链接失效）
5. IBM 完成 HashiCorp 收购的官方公告页（日期与金额）
6. Consul 官方容量/吞吐 benchmark；network segments 细节
7. Istio 移除 Consul/Eureka/CF registry adapter 与弃用 MCP-over-xDS 的具体版本
8. Eureka GitHub v1.10.x/v2.0.x 各 tag 的准确发布年份
9. OpenSergo 与 CNCF 的从属关系；Polaris 的 CNCF 状态
10. Nacos 5s/15s/30s、Eureka 30s/90s 等心跳/剔除经典默认值的一手文档出处
11. Karmada MultiClusterService 等跨集群服务子功能；AWS App Runner 是否提供发现职能
12. MSE Nacos 各规格（实例数/连接数/TPS）配额数字

## 更新历史

| 版本 | 日期 | 变更说明 |
| ------ | ------ | ------ |
| 1.3 | 2026-10-09 | §7 待核实 12 项内联（原指向不入库笔记）；§3.9 内存数字补推导限定 |
| 1.2 | 2026-10-09 | 规模数字定性更新：10 万+ 实例为作者确认的生产实绩（原「口头历史」） |
| 1.1 | 2026-10-09 | 事实修正：org.mydotey.* 依赖已发布 Maven Central（基线 §8-14），易用性判定由「缺失」调整为「落后」，投产否决项由三重改为两重 |
| 1.0 | 2026-10-09 | 初版：2026 行业格局 + 十维度对标 + 横向矩阵 + 双语境综合评判 + 重设计三层启示 |
