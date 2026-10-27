# Phase 4：canary（自动专属规则组）

版本: 1.3    更新时间: 2026-10-27

## 目标

交付 canary 一键用法——route-rule + release 的预置派生（架构 §5.2「由 management 生成专属规则组并下发，不新增独立资源类型」）——roadmap v0.5「canary（自动专属规则组）」与验收要点「canary 流量切分」的交付主体。

## 范围

**做什么**：

- `deployCanary` / `deleteCanary` 两个应用层用例：selector + weight 一键派生保留名 `canary` 规则（canary 目标 + 兜底目标、两列一致即已发布态）+ RELEASE 记录 + 规则流下发 + 审计。
- HTTP API 两端点（`POST /admin/v1/canary/{ns}/{svc}`、`DELETE /admin/v1/canary/{ns}/{svc}`）。
- 保留名定稿（phase-1 留白项）：规则名 `canary` 为 canary 通道独占，CRUD 创建拒绝。
- 端到端验收：canary 部署 → 规则流 → discovery Group filter 视图切分（IT 锁定）。

**不做什么**：

- proto / discovery 侧任何变更（零——canary 规则即普通 ACTIVE route-rule，phase-2 流与 phase-3 执行原样消费）。
- canary 专属 GET / diff / rollback 端点——规则即普通规则，既有 route-rule 查询、diff、releases、rollback API 直接可用（`/route-rules/{ns}/{svc}/canary/*`），零新端点。
- 多 canary 组（每服务一组；多组/复杂圈定是 route-rule 手工用法，预置派生只覆盖典型用法）。
- IP 列表圈定（见 §1 输入形态定案）。
- console UI（phase-5）。

## 涉及模块

| 模块 | 变更 |
|---|---|
| artemis-management-core | domain `RouteRules` 增保留名校验（`canary` 名 CRUD 通道拒绝）；application `ManagementService` 增 `deployCanary` / `deleteCanary` 用例（`routeRuleLock` 内派生 + 写 + 推流 + 记录 + 审计）；审计 operation `DEPLOY_CANARY` / `DELETE_CANARY` |
| artemis-management-server | `AdminController` 两端点 + `CanaryRequest` DTO；`ManagementExceptionHandler` 复用既有 RouteRuleException 映射（零新增错误码，503 复用 `ROUTE_RULE_STORE_UNAVAILABLE`） |

## 验收方式

对应测试文档：[phase-4-canary-test.md](phase-4-canary-test.md)

## 产品设计（③ 补）

### 1. 语义：一个 API 部署金丝雀切分

对照架构 §5.2（canary = route-rule + release 的预置派生，management 生成下发、执行在过滤器链）、legacy §2.5「一键 Canary」（`CanaryServiceImpl#updateCanaryIPs`：传 serviceId + appId + IP 列表自动生成 canary 专属规则 + 组 + 绑定）与 §6-17（灰度发布需前端串 5 个 API 的表驱动局限）。

canary 部署 = **预置派生的 release**：调用方声明「哪些实例是金丝雀（metadata selector）+ 承接多少权重（weight）」，management 自动完成：

1. 派生 targets（纯函数）：
   - canary 目标：`selector = 请求 selector`，`weight = unreleasedWeight = weight`
   - 兜底目标：`selector = {}`，`weight = unreleasedWeight = 10000 - weight`
2. 写保留名 `canary` 规则（不存在则 insert、存在则全量替换 targets；恒 ACTIVE）。
3. 推规则流（dispatcher `onUpserted`，与 phase-2 写序一致）。
4. append `RELEASE` 记录（snapshot = 派生 targets；canary 部署语义上就是一个 release，落记录后既有 rollback / releases / diff API 对 canary 规则天然可用）。
5. 审计 `DEPLOY_CANARY` 摘要行。

两列一致即已发布态——不经编辑通道，不破坏 phase-2 不变量「生效列唯一写入口 = release / rollback」：canary 部署就是 release 通道的自动化组合，RELEASE 记录在案。

**weight 语义**（与 phase-3 二值承接组合后的运维视图）：

| weight | 金丝雀实例 | 稳定组实例 | 场景 |
|---|---|---|---|
| 0 | 摘出视图 | 可见 | 上线不导流（dark launch） |
| 1–9999 | 可见 | 可见 | 灰度中；比例意图（weight : 10000-weight）由调用方消费 |
| 10000 | 可见 | 摘出 | 全量切流 |

- 删除 canary（`DELETE /admin/v1/canary/{ns}/{svc}`）= 删规则 = 无治理意图 = 全实例回归可见——结束灰度（转正或放弃）的一键出口；releases 记录 append-only 保留。
- 回滚到上一权重：复用既有 rollback（`POST /admin/v1/route-rules/{ns}/{svc}/canary/rollback`），零新端点。
- 中途调整权重：再次 `POST` 同 selector 不同 weight——全量替换派生 targets（旧的 canary 组若 selector 变更，旧组实例自然回归兜底承接，语义自洽）；同请求幂等（照常落 RELEASE 记录，显式动作审计需要，phase-2 同语义）。
- 与服务既有手工规则的关系：多规则 AND（phase-3 语义），canary 不豁免——服务已有手工分组规则时再部署 canary，实例需同时满足两条规则才可见（AND 语义一致，产品文档明示）。

**输入形态定案（selector 取代原产品 IP 列表）**：金丝雀身份以实例 metadata 表达（ADR-013 世界：部署金丝雀版本时打标签，如 `version=canary`），selector 等值匹配直接圈定；IP 列表需要 management 反查实例且产物无法用等值 selector 表达（selector 值是单字符串，不是集合）。原产品 IP 输入是其「分组 = 显式绑定」模型的产物，模型已被 ADR-013 取代，输入随之收编——部署侧打标一次，圈定/重圈/摘除全程零 IP 维护。

**域约束**：`weight` 0–10000；`selector` 必须非空且非空 map（空 = 兜底语义冲突）；键值长度/数量沿用 `RouteRules` 既有约束；selector 等值重复不可能（canary + 兜底至多两目标，兜底 selector 为空 map 不与任何非空 selector 等值）。

### 2. 保留名 `canary`（phase-1 留白项定稿）

- 规则名 `canary` 为保留名：CRUD 通道 create 拒绝（400——`RouteRules.validate` 抛 IllegalArgumentException，经既有 handler 映射 `INVALID_ARGUMENT`；保留名是非法名语义而非已存在冲突）；PUT 不可能改名（name 路径定位不可改，phase-1 既有约束），GET / DELETE / release 等对保留名规则不特判（规则是规则，幂等透明）。
- 校验落点：`RouteRules.validate` 增可选保留名拒绝——canary 派生用例构造规则时绕过（域校验信任边界在 Controller/用例组装处，保留名约束是 CRUD 通道的输入约束而非 domain record 不变量；实现以最小改动落定：校验函数带 allowReserved 参数或拆两层）。
- 单组 canary（每服务一条保留名规则）；多组灰度是 route-rule 手工用法。

### 3. API

```text
POST /admin/v1/canary/{namespace}/{service}
{"selector": {"version": "canary"}, "weight": 1000}
→ 200 派生后规则完整视图（两 targets、两列一致、operator/时间戳）
→ 400 selector 缺失/为空/非法、weight 越界、namespace/service 非法（IllegalArgumentException → INVALID_ARGUMENT 既有映射）
→ 503 ROUTE_RULE_STORE_UNAVAILABLE

DELETE /admin/v1/canary/{namespace}/{service}
→ 204（幂等，规则删除）
→ 503 ROUTE_RULE_STORE_UNAVAILABLE
```

- operator 取认证用户名（既有机制）。
- 查询 / 历史 / diff / 回滚不经 canary 端点——`GET /admin/v1/route-rules/{ns}/{svc}/canary`、`.../releases`、`.../diff`、`.../rollback` 既有 API 直接用。

### 4. 写序、并发与审计

- `routeRuleLock` 内成对串行（phase-2 语义沿用）：store 写 → dispatcher 通知 → release append → 审计；append 失败 → 503，重试幂等补录（两列已一致，照常落记录——phase-2 review #1 同型边界）。
- 审计：`DEPLOY_CANARY`（target = `route-rule:{ns}/{svc}/canary`，与规则审计同 target 可关联；detail = selector 摘要 + weight）、`DELETE_CANARY`（同 target）；审计失败不阻塞主操作（尽力而为语义）。CRUD 通道对保留名规则的 DELETE 照旧落 `DELETE_ROUTE_RULE`（不特判）。
- 零新增锁、零新增配置键、零新增 store——全部复用 phase-1/2 交付物。

### 5. review 定案登记（2026-10-27 通过）

- §1–§4 机制落点为架构 §5.2 已记载设计（canary = route-rule + release 预置派生、management 生成下发、执行在过滤器链）的落地细化，无机制变更、无新 ADR 级决策。
- 保留名定稿兑现 phase-1「canary 专属规则组命名与占位策略归 phase-4」留白；selector 输入形态是 ADR-013 已定模型的自然收编（原产品 IP 绑定模型已废）。
- 触及的原始文档：产品文档（canary 用法与 weight 语义，⑩ 同步）、admin-api.md（§4 canary 节，⑩ 同步）；架构 / proto / ADR 无修订（零 proto、零端口变更）。

## 实现结果（⑦ 补）

**实现要点**：

- management-core：`RouteRules` 增 `RESERVED_NAME_CANARY` 与 `validate(rule, allowReservedName)` 重载（保留名是 CRUD 通道输入约束而非 domain 不变量，canary 派生通道放行）；`ManagementServiceImpl` 增 `deployCanary` / `deleteCanary`（`routeRuleLock` 内「写 → notify → append RELEASE 记录」写序对齐 release；派生 targets 单次构造，createdAt 继承创建事实）；审计 `DEPLOY_CANARY` / `DELETE_CANARY`（target = `route-rule:{ns}/{svc}/canary`，与规则审计同 target 可关联）。
- management-server：`AdminController` 两端点 + `CanaryRequest` DTO；错误映射零新增（IllegalArgumentException → 400、503 复用 `ROUTE_RULE_STORE_UNAVAILABLE`）。
- proto / discovery / 配置 / store：零变更——canary 规则即普通 ACTIVE route-rule，phase-2 流与 phase-3 Group filter 原样消费。
- 测试：core 单测 5 方法（ManagementServiceImplTest 54 → 59 中含 canary 5）；`CanaryIT`（内存态 5 用例，方法级 service 键隔离——共享 context 内存 store 跨方法累计，phase-1 先例）；`RouteRulePersistenceIT` 增 canary 重启用例（双方言继承，无 Docker 跳过）；`DiscoveryCanaryIT` 端到端（roadmap 验收要点「canary 流量切分」）。

**⑤ 首跑轮内修复（非 review findings）**：CanaryIT 五方法共享 Spring context 内存 store、同键部署累计 release 记录（expected 1 but was 5）→ 方法级独立 service；Spotless 格式两轮；DiscoveryCanaryIT 两处编译笔误（`.getStatusCode()` 大写、`.DELETE()` 缺 `.build()`）。

**code-review 修复（max 级 4 findings：全修）**：

1. **[中高] DiscoveryCanaryIT 尾断言恒真**：weight=1000 下双实例已可见，DELETE 后 `size()==2` 不锁删除事件传播链——改为「重部署 weight=0（金丝雀摘出）→ DELETE → await 回归 2」，删除增量 → filter 视图重建成为真断言。
2. **[中] 设计文档引用不存在的 `RouteRuleInvalidException`**（phase-1 文档同偏差的复制）——改述为实际机制：`RouteRules.validate` 抛 IllegalArgumentException → 既有 handler 映射 `INVALID_ARGUMENT`（§2/§3 两处）。
3. **[低] dev-state.md 表格被空行打断**（⑤ 更新引入）——修复表格连续性，「完工」行补齐第三格。
4. **[低] deployCanary 三次构造 RouteRule**——收敛单次构造（createdAt 按 find 结果取），insert/update 分支仅剩 store 调用与防御性异常兜底。

## 文档同步（⑩ 填）

## 文档同步（⑩ 填）

- [产品文档](../../../product/artemis.md)（1.35）：新增 v0.5 phase-4 交付节（预置派生一键用法与 DELETE 结束、weight 运维语义、selector 取代 IP 绑定、保留名定稿、零变更工程形态）。
- [admin-api.md](../../../api/admin-api.md)（1.14）：新增 §4.12 canary 两端点（派生语义 / weight 视图 / 保留名 / rollback 复用 / 审计）；§4.11 尾注与 §8 边界更新——顺带修正 phase-3 已交付但未随动的两处滞留表述（「执行归 phase-3」）。
- 架构 / ADR / proto 契约 / route-rule-stream-api / discovery-admin-api：无变更——零 proto、零端口、零机制变更（§5 定案），执行语义已由 phase-3 落档。
- 索引 / CLAUDE.md：无过时项（无新文档文件、无构建命令与模块结构变化；进度状态以 dev-state.md 为唯一源）。

## 更新历史

| 版本 | 日期 | 变更说明 |
| ------ | ------ | ------ |
| 1.3 | 2026-10-27 | ⑨ 人工验证通过；⑩ 文档同步登记（产品文档 1.35 / admin-api 1.14） |
| 1.2 | 2026-10-27 | 实现结果 + code-review 修复登记（4 findings 全修）；§2/§3 错误映射表述修正（review #2） |
| 1.1 | 2026-10-27 | 产品设计定稿（review 通过，§5 登记）：一键派生语义与 weight 视图、selector 输入定案、保留名 canary、两 API、写序与审计 |
| 1.0 | 2026-10-27 | 开工骨架 |
