# Phase 1：route-rule 资源模型与持久化

版本: 1.3    更新时间: 2026-10-26

## 目标

交付 route-rule 领域资源（metadata 选择器 + 目标 + 动作）的模型、V5 双方言持久化与 CRUD API——本版全部治理能力（分组路由 / canary / 两段发布）的规则载体。

## 范围

**做什么**：

- route-rule 领域模型定稿（选择器算子、目标/动作词汇、两段式权重双列、状态语义）。
- V5 双方言 migration（`artemis_route_rule`）+ MyBatis store 实现与内存态 store。
- 资源导向 CRUD HTTP API（创建 / 查询 / 列表 / 全量替换 / 删除）。
- CRUD 全量审计（既有审计表，摘要行语义）。

**不做什么**：

- 规则流下发与 proto 契约（phase-2 release 下发，通道复用 v0.4 版本化规则流 + ack 水位；discovery filter 消费归 phase-3）——本 phase 不动 `proto/`。
- diff 预览 / 回滚（phase-2；历史版本由 release 记录承载，见 §1 版本化决策）。
- canary 派生用法（phase-4 生成专属规则组）。
- selector 匹配执行语义（phase-3 discovery 过滤器链定稿；本 phase 只定义与存储）。

## 涉及模块

| 模块 | 变更 |
|---|---|
| artemis-management-core | domain 新增 `RouteRuleStatus` / `RouteRuleTarget` / `RouteRule` / `RouteRuleStore` 端口 + `RouteRules` 校验纯函数；application `ManagementService` 增 CRUD 用例；infrastructure `InMemoryRouteRuleStore` |
| artemis-management-server | `V5__route_rule.sql` 双方言；MyBatis（Entity/Mapper/StoreImpl）；`AdminController` route-rule 端点 + DTO；装配双轨 |

## 验收方式

对应测试文档：[phase-1-route-rule-test.md](phase-1-route-rule-test.md)

## 产品设计（③ 补）

### 1. 领域模型（选择器表达、目标/动作词汇定稿，plan 待定项）

对照架构 §5.2（资源 `route-rule`，分组以实例 metadata 表达）、[ADR-013](../../../decisions/adr-013-group-as-instance-metadata.md)（分组不进数据模型）、legacy §2.5 / §5-5（两段式权重、命名规则、内置保留规则先例）、§6-17（表驱动 API 局限：一个 API 一个意图）。

```java
// artemis-management-core（org.mydotey.ai.artemis.management.domain）
public enum RouteRuleStatus { ACTIVE, INACTIVE }

/** 路由目标：selector 命中的实例集合分得权重；空 selector = 兜底目标（承接原产品默认组语义）。 */
public record RouteRuleTarget(
        Map<String, String> selector,   // 实例 metadata 等值匹配，多键 AND
        int weight,                     // 生效权重（release 拷贝后）
        int unreleasedWeight            // 编辑中权重（两段式，phase-2 消费）
) {}

/** 路由规则：服务内命名资源，一组有序目标按权重分流。 */
public record RouteRule(
        ServiceKey service,
        String name,                    // (namespace, service) 内唯一
        RouteRuleStatus status,
        List<RouteRuleTarget> targets,
        String operator,                // 最后操作者
        Instant createdAt, Instant updatedAt
) {}
```

词汇定稿（plan「模型语义由 ③ 定稿」项）：

- **选择器算子 = 等值匹配**（多键 AND）：与原产品 groupKey 等值匹配同构（legacy §2.5），覆盖分组路由（phase-3）与 canary 实例圈定（phase-4，metadata 单键等值）全部消费方；IN / 正则等复合算子本版无需求方，不引入。
- **动作词汇 = 权重分配**（targets 各带非负相对权重，比例语义、无总和约束）：原产品 strategy 两种中就近访问（close-by-visit）由宿主 RPC 框架执行、客户端不内置 LB（legacy §2.4），注册中心只表达权重切分意图——**不保留 strategy 字段**，就近路由等新动作词汇待真实需求出现经 targets JSON 演进（向后兼容面友好）。
- **status ACTIVE / INACTIVE**：继承原产品 active/inactive（停用不删除）；INACTIVE = discovery 不应用（执行语义 phase-3，本 phase 只存储与透出）。
- **weight / unreleasedWeight 同目标共存**（两段式，继承资产 §5-5）：编辑写 unreleasedWeight、显式 release 拷贝到 weight（phase-2 动作）；本 phase CRUD 全量写两列，创建即允许两值不同（为 phase-2 diff 预览留数据面）。
- **兜底目标**：selector 空 map 合法且仅语义为兜底（不匹配任何 metadata 约定的流量归此）；不设隐式默认规则——兜底是规则内目标，非系统保留规则。
- **保留名**：不做保留名校验——原产品 `default-route-rule` / `canary-route-rule` 为系统内置先例，新产品 canary 专属规则组的命名与占位策略归 phase-4 定稿（届时按「预置派生」决定是否引入保留名约束，本 phase 提前约束是需求空转）。

### 2. 域约束（信任边界校验，`RouteRules` 纯函数）

| 项 | 约束 |
|---|---|
| namespace / service | 非空，≤255（既有约定） |
| name | 非空 ≤255，`[A-Za-z0-9._-]+`（进 URL 路径段，避免编码歧义） |
| status | `ACTIVE` / `INACTIVE`，缺省 ACTIVE |
| targets | 1–10 条，保序存储 |
| selector | 键 1–8 个；键非空 ≤128；值非空 ≤512（长度约定同 ejection target_value） |
| weight / unreleasedWeight | 0–10000（原产品量级沿用） |
| 目标间 selector | 不要求互斥——实例可被多目标命中，权重分配是调用方侧消费语义（phase-3 filter / 客户端），管理面不重排 |

超限 → 400（`RouteRuleInvalidException` 域异常，Controller 统一翻译）。

### 3. 存储端口与双轨（`LogicalInstanceStore` 同构）

```java
public interface RouteRuleStore {
    RouteRule find(ServiceKey service, String name);

    /** (namespace, service, name) 升序。 */
    List<RouteRule> findAll();

    /** 创建（insert-if-absent）；已存在返回 null（→ 409）。 */
    RouteRule insert(RouteRule rule);

    /** 全量替换；缺失返回 null（→ 404）。 */
    RouteRule update(RouteKey key, RouteRule rule);

    /** 删除；缺失返回 null（幂等 no-op）。 */
    RouteRule delete(ServiceKey service, String name);
}
```

- 内存态 `InMemoryRouteRuleStore`：ConcurrentHashMap，insert 走 `computeIfAbsent`、update/delete 走条件 compute——无读-改-写无锁竞争正确性依赖。
- DB 态 MyBatis：Entity / Mapper / StoreImpl（phase-2 `AccountStoreImpl` 模式，`DataAccessException` → domain 异常翻译；insert 撞唯一键 → 已存在语义）；targets JSON 列由应用侧 gson 序列化（V4 metadata 先例，双方言零方言层差异）。
- 双轨判据 = DataSource bean 存在性（phase-1 起既有纪律），零新增配置键。

`V5__route_rule.sql`（双方言，时间戳 Java 侧 UTC 写入同 V2–V4 约定）：

```sql
CREATE TABLE artemis_route_rule (
    id          BIGINT       NOT NULL AUTO_INCREMENT,   -- pg: BIGSERIAL
    namespace   VARCHAR(255) NOT NULL,
    service     VARCHAR(255) NOT NULL,
    name        VARCHAR(255) NOT NULL,
    status      VARCHAR(16)  NOT NULL,     -- ACTIVE/INACTIVE
    targets     TEXT         NOT NULL,     -- JSON 数组：[{selector,weight,unreleasedWeight}]
    operator    VARCHAR(64)  NOT NULL,
    created_at  DATETIME(6)  NOT NULL,     -- pg: TIMESTAMP WITH TIME ZONE
    updated_at  DATETIME(6)  NOT NULL,
    CONSTRAINT pk_artemis_route_rule PRIMARY KEY (id),
    CONSTRAINT uk_artemis_route_rule UNIQUE (namespace, service, name)
);
```

**版本化决策（phase-2 前瞻）**：表只存当前态、无版本列——回滚（指向历史版本的反向 release）所需历史由 phase-2 release 记录承载（append-only 快照），不在规则表预铺版本化（回滚语义 = 用历史 release 旧快照再发一次，规则表始终当前态）。

### 4. HTTP API（资源导向，`/admin/v1/route-rules`）

```text
POST /admin/v1/route-rules
{"namespace","service","name","status?","targets":[{"selector":{...},"weight","unreleasedWeight"}]}
→ 201 完整资源视图（含 operator/createdAt/updatedAt）
→ 400 校验失败；409 同名已存在；503 ROUTE_RULE_STORE_UNAVAILABLE

GET /admin/v1/route-rules?namespace=&service=&limit=100
→ 200 {"rules": [...]}                    # 过滤可选；(ns,svc,name) 升序；limit 默认 100 上界 1000

GET /admin/v1/route-rules/{namespace}/{service}/{name}
→ 200 完整视图 / 404

PUT /admin/v1/route-rules/{namespace}/{service}/{name}
→ 200 更新后视图（service/name 路径定位不可改，body 不一致 → 400）；404 缺失

DELETE /admin/v1/route-rules/{namespace}/{service}/{name}
→ 204（缺失幂等 204）
```

一个端点一个意图（§6-17 修复）；发布（release）与摘除类操作不在本 phase。

### 5. 审计（架构 §5.3「其余治理操作全量进审计表」）

- CREATE / UPDATE / DELETE 各追加审计摘要行：operation = `CREATE_ROUTE_RULE` / `UPDATE_ROUTE_RULE` / `DELETE_ROUTE_RULE`，target = `route-rule:{ns}/{svc}/{name}`，result = SUCCESS，detail = targets 摘要（条数与权重序列，超长截断）。
- 审计失败不阻塞主操作（ERROR 日志）——phase-3 已定案的尽力而为语义，无跨表事务。
- operator 取 `HttpBasicAuthFilter` 透出的认证用户名（既有机制，认证关闭回落 "anonymous"）。

### 6. 并发与事务

- **零新增锁**：CRUD 无读-判-写复合段（insert-if-absent / 条件替换由 store 原子语义或 DB 唯一键承载）；phase-2 release 引入状态机时再按 `ejectionLock` 先例评估加锁。
- management 单节点假设沿用（roadmap）；跨副本由 DB 唯一键兜底唯一性，无进一步并发控制。
- 读路径全量遍历 + 内存过滤（管理操作级频率，千级规则量可忽略；量级增长后按 service 索引——同 phase-3 §8 读路径决策）。

### 7. review 定案登记（2026-10-26 通过）

- §1–§6 全部机制落点不变更已记载设计（架构 §5.2 资源 API 形态 / ADR-013 分组语义 / phase-2 MyBatis 模式 / V4 JSON 列先例 / phase-3 审计分工与尽力而为语义），无原始文档修订。
- 词汇定稿（等值选择器 / 权重动作不保留 strategy / 两段双列 / 兜底目标）为架构 §5.2 已记载机制的语义细化，产品文档登记随 ⑩。

## 实现结果（⑦ 补）

实施要点：

- 分层落点：domain 六类（`RouteRule`/`RouteRuleTarget`/`RouteRuleStatus`/`RouteRules` 校验纯函数/`RouteRuleStore` 端口/`RouteRuleException`）+ `InMemoryRouteRuleStore`（putIfAbsent/replace/remove 原子原语，零锁设计 §6）+ `RouteRuleStoreImpl`（MyBatis-Plus，gson 2.14 直接序列化 domain record，无中转载体）+ `AdminController` 五端点 + `ManagementExceptionHandler` RouteRuleException 三态映射（409/404/503）。
- 时间戳应用层填写（两态一致）：create 双 now；update 继承 createdAt、刷新 updatedAt。
- audit target = `route-rule:{ns}/{svc}/{name}`（实现期修正：初版漏前缀，core 单测锁定）；detail = `targets={n} weights=[...]`（生效列）。
- IT 键隔离：restart 用例独立 service（两用例共享容器 DB 且顺序不定）；CREATE 审计断言用包含性而非精确计数（同因）。
- `PersistenceDialectTestBase` schemaVersion 断言 4 → 5（V5 落地随动）。

实现期调试（⑤ 首跑轮内修复，非 review findings）：测试 body 字符串拼接后 `.formatted` 运算符优先级陷阱（`a + b + "...".formatted(x)` 只格式化末段字面量，`%s` 落库成字面量）——两处 IT body 改 text block。

### code-review 修复（⑦，max 级 6 findings，全修）

1. **[高] PUT 缺 name NPE → 500**：`rule.name().equals(name)` 在 body 缺 name 时 NPE，被兜底 handler 吞成 500（应为 400）——改 `Objects.equals`，IT 补行为锁定断言（PUT 无 name → 400）。
2. **[高] `RouteRuleStoreImpl.update` 忽略行数**：select 与 updateById 间并发删除（0 行）仍返回 true → 幻影 200 + 无效审计，与内存态 replace 语义分歧——改 `updateById(...) > 0`。
3. **[高] `delete` 同样忽略行数**：并发双 DELETE 产生虚假 DELETE 审计——改 `deleteById(...) > 0`。
4. **[中] targets 字符上限 vs MySQL TEXT 字节上限**：16,384 字符 × 4 字节 UTF-8 可溢出 65,535 字节列（MySQL-only 503 而非 400）——校验改 UTF-8 字节数 ≤ 60KB。
5. **[低] `RouteRules` 长度计量 length() 与 Controller requireLength（codePointCount）口径不一**：星体平面字符被 UTF-16 双计过度拒绝——`RouteRules` 统一 codePointCount。
6. **[低] Controller 冗余 null selector 值检查**：`RouteRules.validateTarget` 已拒（isBlank 含 null），删除冗余分支，域校验单一来源。

V4 metadata 列存在同 #4 的潜在字节溢出模式（review 注明 pre-existing）：不扩面修（属既有边界，量级同 16KB 字符×4 字节场景），留待 v1.0 容量验证统一收口。

## 文档同步（⑩ 填）

- [产品文档](../../../product/artemis.md)（1.32）：新增 v0.5 phase-1 交付节（资源模型词汇、V5 持久化与版本化决策、CRUD API 与审计、零锁与 review 修复工程形态）。
- [admin-api.md](../../../api/admin-api.md)（1.12）：§4.10 路由规则 CRUD 五端点（模型语义 + 域约束 + 审计 + phase-2/3 边界声明）；§7 错误表补 `ROUTE_RULE_NOT_FOUND`（404）/`ROUTE_RULE_ALREADY_EXISTS`（409）/`ROUTE_RULE_STORE_UNAVAILABLE`（503）；§8 限制更新。
- 架构 / ADR / proto 契约：无变更——③ review 定案已确认全部机制落点为已记载设计的落地（见 §7），无原始文档修订、无新 ADR 级决策；本 phase 未动 `proto/`。
- 索引 / CLAUDE.md：phases/README.md 已于 ① 登记；构建命令、模块结构、当前状态无过时项。

## 更新历史

| 版本 | 日期 | 变更说明 |
| ------ | ------ | ------ |
| 1.3 | 2026-10-26 | ⑨ 人工验证通过；⑩ 文档同步登记（产品文档 1.32 / admin-api 1.12） |
| 1.2 | 2026-10-26 | 实现结果 + code-review 修复登记（6 findings 全修：PUT NPE/行数判定/字节上限/码点计量/冗余检查） |
| 1.1 | 2026-10-26 | 产品设计定稿（待 review）：模型词汇（等值选择器/权重动作/两段双列/兜底目标）、域约束、store 端口与 V5 双方言、CRUD API、审计、零锁并发决策 |
| 1.0 | 2026-10-26 | 开工骨架 |
