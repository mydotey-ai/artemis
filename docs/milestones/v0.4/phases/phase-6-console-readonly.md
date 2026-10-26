# Phase 6：console 只读版

版本: 1.1    更新时间: 2026-10-26

## 目标

management 内嵌静态 console（只读）：服务列表/详情/搜索（分页 + 模糊）+ 实例视图，不引入独立前端部署单元。

## 范围

**做**：
- 服务列表查询扩展：`keyword` 模糊过滤 + `offset`/`limit` 分页 + `total`（additive，复用既有 `/admin/v1/services` 端点）。
- 服务详情实例视图分页：`offset`/`limit` 作用于 instances，计数始终全量。
- console 静态页面：**原生单文件 SPA**（`index.html` 内嵌 CSS/JS，零依赖零构建）经 Spring Boot 默认静态资源机制内嵌于 management-server，入口 = 外部端口根路径 `/`；认证由既有 `/*` HttpBasicAuthFilter 自动覆盖。

**不做**（v0.5 console 管理版，roadmap 既定）：
- 写操作（发布/摘除）、订阅关系视图、操作审计流、自我保护态展示。
- 不新增 proto / gRPC 契约、不引入新依赖组件与前端构建链（原生单文件定案即为此排除 npm/框架，CI 无 node 依赖）。

## 涉及模块

- `java/server/artemis-management-core`：`ManagementService` 查询签名扩展 + `ServicePage` 视图 + 分页/过滤语义。
- `java/server/artemis-management-server`：`AdminController` 参数绑定与 limit clamp；`src/main/resources/static/index.html`（console 页面，零 Java 装配代码）。
- proto：无变更（纯 HTTP REST 查询扩展 + 静态资源）。

## 验收方式

对应测试文档：[phase-6-console-readonly-test.md](phase-6-console-readonly-test.md)

## 产品设计（③ 补）

### API 扩展（additive，无新端点）

**服务列表** `GET /admin/v1/services?namespace=&keyword=&offset=&limit=`

| 参数 | 语义 |
|---|---|
| `keyword` | null/空白 = 不过滤；否则**大小写不敏感 substring**，命中 `namespace` 或 `service` 任一即保留 |
| `offset` | ≥ 0，缺省 0；越界返回空页（不报错） |
| `limit` | ≥ 1，缺省 = 全量（既有行为不变）；显式传入时 clamp 至 1..1000（与既有 list 端点同上界） |

- 响应：`{"services": [ServiceView...], "total": <过滤后总数>}`——`total` 为新增字段，既有消费者 additive 兼容。
- 稳定分页：沿用既有 `SERVICE_ORDER`（namespace 升序、service 升序）排序后过滤再切片。

**服务详情** `GET /admin/v1/services/{namespace}/{service}?includeEjected=&offset=&limit=`

- `offset`/`limit` 作用于**过滤后可见实例集**（includeEjected 语义不变，先摘除过滤再切片）；`instanceCount`/`ejectedCount`/`effectiveInstanceCount` 始终为全量计数。
- `limit` 缺省 = 全量，clamp 同上界——单服务实例数巨大时响应有界（10 万实例语义的分页上界）。

### 应用层接口（core，签名直接改，HTTP 调用点唯一为 AdminController）

```java
record ServicePage(List<ServiceView> services, int total) {}

ServicePage listServices(String namespace, String keyword, int offset, int limit); // limit ≤ 0 = 全量
ServiceDetail getService(ServiceKey key, boolean includeEjected, int offset, int limit);
```

数据流：registry 全量拉取（既有语义，每次调用现取，不缓存）→ 摘除计数合并 → 排序 → keyword 过滤 → total 计数 → 切片。

### console 页面（单文件 `static/index.html`）

- **列表视图**：搜索框（回车/按钮触发）+ 分页控件（每页 20）+ 表格（namespace / service / 实例数 / 摘除数 / 查看详情）。
- **详情视图**：计数概览 + `includeEjected` 切换 + 实例表（instanceId / host:port / status / weight / metadata / 摘除标注与命中 scope）+ 分页。
- hash 路由（`#/`、`#/s/{namespace}/{service}?includeEjected=`），可刷新可分享；fetch 同源自动携带 Basic 凭证（protection space 已含根路径）。
- 入口 = 外部端口根路径 `/`（Spring Boot 默认 welcome page，零装配代码）；`/error` 白名单外全路径经认证过滤器（ADR-021：console 与 Admin API 同一认证源）。

### 10 万实例语义

分页上界保证响应体 ∝ 页大小而非总量：列表服务数、详情实例数均有 limit clamp + 切片；单测以大列表内存构造验证切片与 total 正确性，不做真实 10 万实例压测（容量压测属 v1.0）。

## 实现结果（⑦ 补）

**实现要点**：
- core：`ServicePage` 视图 + `ManagementService` 列表/详情签名扩展；`slice` 语义（limit ≤ 0 全量、offset 越界空页、全量路径零复制）与 keyword 匹配（`Locale.ROOT` 大小写折叠）在 `ManagementServiceImpl`。
- server：`AdminController` 绑定 `keyword`/`offset`/`limit`（`boundLimit` clamp 1..1000，缺省 0 = 全量），`ListServicesResponse` 增 `total`；console 为 `static/index.html` 单文件（hash 路由 + fetch，Spring Boot welcome page 零装配）。
- 测试基建：`RegistryFixture` 从 `ManagementAdminIT` 嵌套类提为测试包顶层类（同 JVM 多 IT 共用）。

**review 修复记录（4 findings）**：
1. IT 用例顺序脆弱：`ManagementConsoleIT` 两用例共用 namespace 无清理，方法序变化即伪失败 → namespace 按用例隔离（`console-list-it` / `console-detail-it`），确定性隔离且免清理。
2. console `route()` 的 `decodeURIComponent` 在 try 之外，残缺 hash 抛 URIError 未捕获 → `route()` 调用移入 try，错误回显到页面。
3. `slice` 全量路径整表复制 → `from == 0 && to == size` 直接返回原列表（入参均不可变）。
4. IT HTTP/gRPC 辅助与 `ManagementAdminIT` 重复约 40 行 → 抽共享基类 `ManagementHttpIT`（端口注入、Basic 认证请求、JSON 断言包装），两个 IT 继承。

## 文档同步（⑩ 填）

- `docs/product/artemis.md`（1.30）：§4 交付状态新增「v0.4 phase-6（console 只读版）已交付」块（页面入口与能力、API 扩展、工程形态）。
- `docs/api/admin-api.md`（1.11）：§4.1 服务列表增 `keyword`/`offset`/`limit` 与响应 `total`；§4.2 详情增实例分页语义（计数始终全量）；§1 部署表登记 console 入口；顺手清理更新历史表中部的冗余分隔行。
- 索引：`phases/README.md` 已于 ① 登记本 phase 两份文档；无新增文档文件。
- CLAUDE.md：无需更新（构建命令、模块结构、状态口径均未变化）。
- review 定案触及的原始文档：无——架构 §5.3「management 内嵌静态 SPA + REST」未变更，原生单文件为其实例化定案，无新 ADR。

## 更新历史

| 版本 | 日期 | 变更说明 |
| ------ | ------ | ------ |
| 1.1 | 2026-10-26 | 实现结果与 code-review 修复（4 findings）回填；文档同步清单 |
| 1.0 | 2026-10-26 | 开工骨架 + 设计定稿 |
