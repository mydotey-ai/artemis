# Phase 5：console 管理版

版本: 2.1    更新时间: 2026-10-27

## 目标

交付 console 管理版操作面——发布/摘除操作、订阅关系视图（调用方视角）、操作审计流、自我保护态展示（架构 §5.3、roadmap v0.5「console 管理版」的 console 侧闭环）。

## 范围

**做什么**：

1. console SPA 升管理版（management 内嵌静态单文件延续，无前端构建链）：新增摘除/恢复操作、路由规则与发布操作（diff/release/rollback/canary）、订阅关系视图、审计流、自我保护态五个操作/视图面。
2. management → discovery 只读组合通道：management 经 HTTP 拉取所配 discovery 节点（0..N）的 `/admin/v1/subscriptions`，逐源隔离合并，经 `GET /admin/v1/subscriptions` 出口供 console 订阅关系视图。
3. all-in-one 组合注入：launcher 向 management 上下文注入 discovery HTTP 端点（eval 形态订阅视图开箱可用）。

**不做什么**：

- 不另立 console 专有端点——操作面全部驱动既有治理 REST API（`/admin/v1/*`，plan 预告）；唯一新增 REST 端点是订阅关系组合查询。
- 不改 discovery 侧任何代码（订阅关系数据源 = v0.3 phase-7 既有只读 API）。
- 不做 RBAC/多账户、订阅方身份升级（`subscriber` 仍为传输层地址，discovery-admin-api §3 已声明后置）。
- 不引入前端框架/构建链（架构 §5.3「management 内嵌静态 SPA」的既有形态延续）。
- proto 零变更（操作面走 REST，无新契约组）。

## 涉及模块

| 模块 | 变更 |
|---|---|
| artemis-management-core | domain 新增 `SubscriptionQueryPort` + 订阅视图 record；application `ManagementService.subscriptions()` 组合 |
| artemis-management-server | `http/` 新增 discovery 订阅 HTTP client（RestClient）+ SCF 配置类 + `GET /admin/v1/subscriptions` 端点；`static/index.html` 升管理版 |
| artemis-discovery-server | 无产品代码变更；新增跨服务 IT（三服务同 JVM 形态） |
| artemis-dist | all-in-one launcher 注入 `artemis.management.discovery.endpoints` |

## 验收方式

对应测试文档：[phase-5-console-management-test.md](phase-5-console-management-test.md)

## 产品设计（③ 补）

### 1. 数据通道：management → discovery 只读组合（订阅关系视图数据源）

架构 §2.1 依赖矩阵已登记 management 对 discovery 0..N 依赖，本 phase 落地第一条通道（只读、HTTP）：

- **domain 端口**（core，依赖倒置同 `RegistryQueryPort` 先例）：

```java
/** discovery 只读发现管理查询出站端口（HTTP 实现由 server 模块提供，架构 §2.1）。 */
public interface SubscriptionQueryPort {
    /** 逐源结果：单源失败以 error 承载，不拖垮其余源（只读排障面 per-source fail-open）。 */
    List<SubscriptionSource> subscriptions();
}

public record SubscriptionSource(String endpoint, String error, List<Subscription> subscriptions) {}
public record Subscription(
        long id, String subscriber, Instant establishedAt,
        List<ServiceKey> services, Map<String, String> labels, List<String> clusters) {}
```

- **server 实现**：`DiscoveryAdminClient`（Spring `RestClient`，逐 endpoint 并行拉取，超时可配）；认证可选（discovery 外部账户默认关闭）。零新依赖组件（spring-web 既有）。
- **配置**（SCF 静态，`DiscoveryClientConfig` 类内定义，命名与 discovery 侧 `artemis.discovery.management.*` 对称）：

| 键 | 缺省 | 语义 |
|---|---|---|
| `artemis.management.discovery.endpoints` | 空 | 逗号分隔 `host:port`（discovery 外部 HTTP 端口）；空 = 无订阅关系数据（视图自解释），不报错 |
| `artemis.management.discovery.auth.username` / `.password` | 空 | 可选 Basic 凭证（discovery `/admin/*` 认证启用时配置） |
| `artemis.management.discovery.timeoutMs` | 3000 | 单源请求超时 |

- **application 组合**：`ManagementService.subscriptions()` = 逐源调用 + 逐源错误隔离（error 非空源订阅列表为空）+ 稳定排序（源序 → id 升序）。
- **REST 出口**（AdminController，`GET /admin/v1/subscriptions`）：

```json
{
  "sources": [
    {"endpoint": "127.0.0.1:8090", "error": null,
     "subscriptions": [{"id": 7, "subscriber": "/127.0.0.1:54321", "establishedAt": "...",
                        "services": [{"namespace": "default", "service": "order-service"}],
                        "labels": {}, "clusters": []}]}
  ]
}
```

订阅条目字段与 discovery-admin-api §3 输出同构（wire 语义透传，management 侧为 domain record 重建，不传递 discovery 生成类——跨模块传递以 artifact 为界纪律）。

### 2. console SPA 管理版（视图与操作面）

hash 路由七视图，header 导航切换；全部请求走 `/admin/v1`（浏览器 Basic 认证，401 时提示以管理账户刷新）：

| 路由 | 内容 | 驱动 API |
|---|---|---|
| `#/` | 服务列表：keyword 模糊 + 分页（既有只读版能力保留） | `GET /admin/v1/services` |
| `#/s/{ns}/{svc}` | 服务详情（既有实例表）+ **实例摘除**（instanceId 级 + reason）/**恢复** + **canary 一键部署**（selector k=v + weight 0–10000）/**结束灰度** | `POST /admin/v1/ejections`、`POST /admin/v1/ejections/recover`、`POST/DELETE /admin/v1/canary/{ns}/{svc}` |
| `#/ejections` | 摘除记录：activeOnly 过滤 + 逐行恢复 + selector 级新建摘除表单（scope/value/reason，四级一套） | `GET /admin/v1/ejections`、`POST /admin/v1/ejections[/recover]` |
| `#/rules` | 路由规则：列表（namespace/service 过滤）+ 新建/编辑（name + targets：selector k=v + unreleasedWeight）+ **diff 预览** + **发布** + **回滚**（历史 release 选择）+ 删除 | `/admin/v1/route-rules*`（CRUD + release/rollback/diff/releases） |
| `#/subscriptions` | **订阅关系视图（调用方视角）**：按源分组表（endpoint / subscriber / establishedAt / services / labels / clusters），源错误态内联标注 | `GET /admin/v1/subscriptions` |
| `#/audit` | **操作审计流**：operation 过滤 + 时间倒序（operation/operator/target/result/时间） | `GET /admin/v1/audit-logs` |
| `#/protection` | **自我保护态**：节点级统计卡（windowRenewals/maxCount/protecting）+ 服务明细表（protecting/nodeFallback/protectingForMs），includeNormal 开关 | `GET /admin/v1/status/protection` |

操作均为「表单 → 既有 REST API → 刷新视图/错误内联展示」，无 console 侧状态机；发布两段语义（编辑列 vs 生效列）在 diff 预览与规则表（weight/unreleasedWeight 两列）中如实呈现。

### 3. all-in-one 组合注入

`ArtemisAllInOneServer` management 启动参数追加 `--artemis.management.discovery.endpoints=127.0.0.1:<discoveryHttpPort>`（同进程 loopback，与 registry endpoint 注入同模式）。

### 4. 定案决策与原始文档同步落点

- **订阅关系视图口径 = discovery 活跃订阅快照的逐源并列（非跨源合并去重）**：discovery 节点互为独立投影副本（架构 §2.1），同一调用方可对多节点各持一条流，去重无事实依据；源列即排障维度。→ 同步落点：`docs/api/admin-api.md` 新增 §4.13（含口径说明）。
- **management → discovery 通道落点补登记**：架构 §2.1 已列依赖（discovery 0..N），§2.2 端口表「discovery ↔ management」行精化——上行规则流/状态之外补下行只读发现管理查询（HTTP、复用 discovery 外部 HTTP 端口）。→ 同步落点：`docs/arch/artemis-next-architecture.md` §2.2。
- **单源失败不拖垮视图（per-source fail-open）**：订阅关系是只读排障面，多源部署下单源故障不应使 console 视图整体 503；错误以源级 `error` 字段自解释（与 discovery `/admin/v1` 免 readiness 门控的排障面哲学一致）。→ 同步落点：admin-api.md §4.13。
- **console 操作面零新端点**（plan 预告定案的执行确认）：操作语义（两段发布、四级摘除、canary 派生）已在 v0.5 phase-1–4 API 定案，本 phase 纯展示/驱动层。→ 同步落点：无（既有契约不变）。
- **订阅方身份后置**：`subscriber` 为传输层地址的既有口径不变（v0.4 账户体系接管后升级，discovery-admin-api §3 已声明），本 phase 不在 management 侧二次加工。→ 同步落点：无。
- 对照基线：本 phase 是原产品局限 #16/#17（表直透 API、console 缺操作闭环）修复的收尾——资源导向 API + console 管理版操作/视图面（架构 §8 处置行）。

## 实现结果（⑦ 补）

- **core**：`Subscription`/`SubscriptionSource` record + `SubscriptionQueryPort`（domain）；`ManagementService.subscriptions()` 组合（显式按 id 稳定排序，不依赖远端排序纪律），构造器第 13 参（registry 之后，同为出站查询）。
- **server**：`DiscoveryAdminClient`（RestClient per endpoint 构造时固化 + **共享命名虚拟线程 executor**（`artemis-management-discovery-*`，AutoCloseable）；JDK HttpClient connect/read 双超时）+ `DiscoveryClientConfig`（ScfProperties staticString/staticLong 便捷方法）；`GET /admin/v1/subscriptions`（domain record 直序列化，StatusController 先例）。SPA 管理版（index.html 单文件，七视图 hash 路由）。
- **dist**：launcher 注入订阅源（端口 0 守卫，同 discovery.nodes 先例）。
- **review 修复（8 findings 全修，无一驳回）**：① 高优——console 编辑表单 PUT 丢 `status` → 缺省 ACTIVE **静默激活 INACTIVE 规则**（status 随编辑回传 + 表单提示状态保持）；② ejections 恢复按钮误判 `record.active`（wire 无此字段，死码）→ 挂 EJECT 行（recover 服务端幂等）；③ all-in-one 端口 0 注入坏源 → 守卫跳过；④ rules 提交后 hash 拼接错（namespace 空时 `#/rules&service=`）→ URLSearchParams；⑤ 属性选择器/PUT 路径/edit 参数对 namespace/service 自由字符集（引号/斜杠）的编码与解析边界 → getElementById + encodeURIComponent + edit 参数 JSON 承载；⑥ canary 规则行 CRUD「编辑」必 400 → 该行禁编辑（管理入口在服务详情页）；⑦ 摘除提交后 nav 同 hash 不触发刷新 → 直接 render()；⑧ 每次调用新建未命名 executor（违 tech-java 命名纪律）→ 共享构造期创建。
- **实现期偏差**：无（设计定案结构原样落地；IT 起停参数补 `optional:classpath` 阻断测试 classpath 误载——既有 DiscoveryCanaryIT 同型，非设计变更）。

## 文档同步（⑩ 填）

| 文档 | 变更 | 时机 |
|---|---|---|
| `docs/arch/artemis-next-architecture.md`（1.45） | §2.2 端口表补 management → discovery 只读发现管理查询通道 | review 通过后（架构同步纪律，非 ⑩ 记账） |
| `docs/api/admin-api.md`（1.15） | 新增 §4.13 订阅关系查询；§1 console 行升管理版 + 上游补 discovery 通道；配置表补 `artemis.management.discovery.*` 三键 | 同上 |
| `docs/api/all-in-one.md`（2.1） | 治理链注入键补 management → discovery 订阅源（0/随机端口不注入） | ⑩ |
| `docs/product/artemis.md`（1.36） | v0.5 phase-5 交付节（必含项）+ v0.4 phase-6 操作面状态句更新 | ⑩ |
| `docs/milestones/v0.5/phases/phase-5-console-management{,-test}.md` | 本 phase 设计/测试文档（含验证与回归记录） | 全程 |
| CLAUDE.md | 不更新（无构建命令/模块结构变化） | — |

## 更新历史

| 版本 | 日期 | 变更说明 |
| ------ | ------ | ------ |
| 2.1 | 2026-10-27 | 实现结果与文档同步收尾（④–⑩：review 8 findings 全修记录 + 变更清单） |
| 2.0 | 2026-10-27 | 设计定稿（③）：订阅关系组合通道 + 七视图操作面 + all-in-one 注入 |
| 1.0 | 2026-10-27 | 开工骨架 |
