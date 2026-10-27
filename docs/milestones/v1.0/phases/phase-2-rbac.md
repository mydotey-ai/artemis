# Phase 2：RBAC

版本: 1.2    更新时间: 2026-10-27

## 目标

management 管理访问面补齐 RBAC：角色 × 资源 × 动作模型（DB 持久化）+ Admin API/console 权限 enforcement + 账户管理，ADR-021 委托机制定案。

## 范围

**做**：

- RBAC 模型：预置角色（ADMIN / OPERATOR / VIEWER）× 资源 × 动作权限矩阵；角色分配 DB 持久化（账户表 `role` 列）。
- 多账户管理：用户 CRUD + 角色分配 + admin 重置密码（REST，admin only）+ 自助改密（既有）。
- enforcement：management 外部 HTTP `/admin/v1/**` 认证后判权（401/403 分层）；console 走同一 REST 面天然覆盖。
- 审计扩展：账户管理操作（建户/改角色/重置密码/删户）进审计表。
- ADR-021 委托机制定案（见「review 定案」：继续后置）。

**不做**：

- 自定义角色与自定义权限矩阵（三预置角色覆盖「读 / 治理写 / 全权」三档真实需求；角色→权限是产品语义，代码常量，DB 只存账户→角色映射）。
- 账户禁用态（删除即覆盖；enabled 状态机后置）。
- 内存态 RBAC：保持 v0.4 语义（单管理员配置凭证 = 全权）；用户管理 API 内存态 409（同改密先例）。
- console 用户管理页面（API 先行，页面后置按需）。
- 内部 gRPC 规则流端口的 RBAC：该端口是 discovery 服务凭证域（ADR-021 分域），非管理账户面，不动。
- mTLS 客户端证书校验（架构 §6 既定后置项，随委托机制一并后置）。

## 涉及模块

- `java/server/artemis-management-core`：domain（`Role`/`Permission`/`AccountStore` 扩展）、application（`AccountAuthService` 签名升级、`DbAccountAuthService` 多账户化、新增用户管理应用服务）。
- `java/server/artemis-management-server`：repository（V7 迁移 + `AccountStoreImpl` 扩展）、http（`RbacAuthorizationFilter`、`UserController`）、装配。
- proto：**无变更**（管理访问面是 REST + DB；内部 gRPC 端口凭证域不属本 phase）。

## 验收方式

对应测试文档：[phase-2-rbac-test.md](phase-2-rbac-test.md)

单测：权限矩阵判定；IT：角色 × 资源 × 动作允许/拒绝 + 未授权访问拒绝 + 审计记录。

## 产品设计（③ 补）

### 1. 权限模型

```java
// domain（core，无 Spring）
public enum Role { ADMIN, OPERATOR, VIEWER;
    public Set<Permission> permissions() { ... }   // 代码常量矩阵，非 DB
}
public enum Resource { SERVICE, EJECTION, ROUTE_RULE, LOGICAL_INSTANCE, AUDIT, USER }
public enum Action { READ, WRITE, MANAGE }
public record Permission(Resource resource, Action action) {
    public boolean grantedBy(Role role) { ... }
}
```

权限矩阵（角色 → 权限集合，产品语义）：

| Permission | ADMIN | OPERATOR | VIEWER | 覆盖端点（method + path 前缀） |
|---|---|---|---|---|
| `service:read` | ✓ | ✓ | ✓ | GET `/admin/v1/services/**`、GET `/admin/v1/subscriptions`、GET `/admin/v1/status/**` |
| `ejection:read` | ✓ | ✓ | ✓ | GET `/admin/v1/ejections` |
| `ejection:write` | ✓ | ✓ | — | POST `/admin/v1/ejections`、POST `/admin/v1/ejections/recover` |
| `route-rule:read` | ✓ | ✓ | ✓ | GET `/admin/v1/route-rules/**` |
| `route-rule:write` | ✓ | ✓ | — | POST/PUT/DELETE `/admin/v1/route-rules/**`、POST `.../release`、POST `.../rollback`、POST/DELETE `/admin/v1/canary/**` |
| `logical-instance:read` | ✓ | ✓ | ✓ | GET `/admin/v1/logical-instances` |
| `logical-instance:write` | ✓ | ✓ | — | PUT/DELETE `/admin/v1/logical-instances` |
| `audit:read` | ✓ | ✓ | — | GET `/admin/v1/audit-logs` |
| `user:manage` | ✓ | — | — | `/admin/v1/users/**`、GET `/admin/v1/roles` |

资源词汇表对照架构 §5.2 资源导向 API 的归并说明：

- `release` / `rollback` / `canary` 归并 `route-rule:write`——两段式发布与 canary 是 route-rule 的生命周期操作/预置派生用法（§5.2 canary = route-rule + release 派生），权限域不拆（「发布权与编辑权分离」在三预置角色下无承接角色，出现需求时加第四角色即可，矩阵代码常量演进无迁移成本）。
- 订阅视图（`subscriptions`）与排障面（`status/**`）归并 `service:read`——均为服务事实的读视图。
- `account` 自助改密不进矩阵：任何已认证用户可改自己密码（凭证卫生操作，非权限操作）；改别人密码走 `user:manage`。

### 2. 数据结构与迁移

`V7__rbac.sql`（MySQL + PostgreSQL 双方言同文）：

```sql
-- v1.0 phase-2：RBAC 角色列（预置角色 ADMIN/OPERATOR/VIEWER，权限矩阵为代码常量）
-- 存量行（v0.4 单管理员）升级即 ADMIN，行为无回退；seed 账户同 ADMIN
ALTER TABLE artemis_account ADD COLUMN role VARCHAR(32) NOT NULL DEFAULT 'ADMIN';
```

`Account` record 扩展：`Account(String username, String passwordHash, Role role, Instant createdAt, Instant updatedAt)`。

`AccountStore` 端口扩展（单管理员语义 → 多账户）：

```java
boolean existsAny();
Optional<Account> findByUsername(String username);   // 替代 findAny（verify 主路径）
List<Account> list();                                 // 用户列表（无 hash 泄露面：应用层剥离）
void insert(Account account);                         // 已存在抛 IllegalStateException（并发建户竞态落 DB 唯一键）
void updatePasswordHash(String username, String passwordHash, Instant updatedAt);
void updateRole(String username, Role role, Instant updatedAt);
void delete(String username);
```

### 3. 认证升级（认证返回 principal）

```java
// application
public record AuthenticatedAccount(String username, Role role) {}
public interface AccountAuthService {
    Optional<AuthenticatedAccount> verify(String username, String password);  // boolean → principal
    // ④ 实现调整：多账户下改密以认证主体定位账户（v0.4 单管理员 findAny 语义不再成立），
    // AccountController 传入 AUTH_USER_ATTRIBUTE；认证关闭时无主体 → 400
    String changePassword(String username, String oldPassword, String newPassword);
}
```

- 内存态 `ConfigAccountAuthService.verify` 返回固定 `ADMIN`（单管理员 = 全权，v0.1 行为零变化）。
- `DbAccountAuthService` 缓存单槽 → **按用户名键控**（`ConcurrentHashMap`；仅认证成功条目入缓存，容量 = 有效账户数天然有界；兑现 v0.4 `ponytail:` 预告）。失效路径：本人改密、admin 重置密码、角色变更、删户各自清对应槽（角色变更/删户清槽保证权限即时生效，不等 TTL）。
- `HttpBasicAuthFilter` 保留 `AUTH_USER_ATTRIBUTE`（String，10 处 Controller `@RequestAttribute` 不动），新增 principal attribute（`AuthenticatedAccount`）供判权 filter 消费。

### 4. HTTP enforcement

新增 `RbacAuthorizationFilter`（`OncePerRequestFilter`，注册于 `HttpBasicAuthFilter` 之后，URL 范围 `/admin/v1/*`）：

- 认证已通过（principal attribute 存在）→ 以 `method + AntPath` 匹配路由登记表（上表「覆盖端点」列逐端点显式登记）取 `Permission` → `permission.grantedBy(role)` 判定。
- **未登记路径默认 deny**（fail-closed；登记表遗漏 = 403 暴露，优于静默放行）。
- 无 principal（认证被禁用 `auth.enabled=false` 时的显式关闭语义）→ 放行（认证关闭即全权，与 v0.1「eval 显式关闭」哲学一致）。
- 拒绝响应 `403` JSON `{"error":"FORBIDDEN"}`；未认证 `401`（既有）。
- 仅 DB 态装配（内存态无多用户，无判权语义）；`/admin/v1` 之外的路径（console 静态资源等）不经此 filter。

### 5. 用户管理 API（REST，进 docs/api/admin-api.md §5）

| 端点 | 语义 | 权限 |
|---|---|---|
| `GET /admin/v1/users` | 列表（username/role/createdAt/updatedAt，不含 hash） | `user:manage` |
| `POST /admin/v1/users` | 建户 `{username, password, role}` | `user:manage` |
| `PUT /admin/v1/users/{username}/role` | 改角色 `{role}` | `user:manage` |
| `PUT /admin/v1/users/{username}/password` | admin 重置 `{newPassword}` | `user:manage` |
| `DELETE /admin/v1/users/{username}` | 删户 | `user:manage` |
| `GET /admin/v1/roles` | 预置角色列表（console 表单用） | `user:manage` |
| `PUT /admin/v1/account/password` | 自助改密（既有，不变） | 认证即可 |

校验与保护（应用层 `UserAccountService`，core）：

- username：非空白、≤64 字符、不含路径敏感字符（`/`）；password：≥8（复用既有规则常量）；role：枚举合法值。
- **最后 ADMIN 保护**：改角色/删户操作不得使 ADMIN 账户数归零（`409`）；**不可删自己**（`409`）。
- 重复建户 `409`（DB 唯一键兜底并发竞态）。
- 内存态全部用户管理端点 `409`（`MemoryModePasswordChangeException` 同族）。
- 审计：`CREATE_USER` / `UPDATE_USER_ROLE` / `RESET_USER_PASSWORD` / `DELETE_USER`（operator = 认证用户，target = `account:<username>`，尽力而为同既有语义）；403 拒绝不进审计表（审计是治理操作记录，非安全事件日志）。

### 6. ADR-021 委托机制定案（review 定案）

**继续后置**（v1.x，随真实需求）：ADR-021 否决理由在本 phase 依然成立——RBAC 的全部资源（§5.2 治理资源 + 账户）都在 management 本地管理访问面；registry/discovery 的管理端点是排障面、凭证域分域（ADR-021）不变；认证委托引入 registry 对 management 的运行时依赖与 failover 语义，代价仍不成比例。多用户管理各服务排障面的真实需求出现时随 v1.x 设计（同步评估 mTLS 客户端证书）。

**原始文档同步落点**（review 通过即同步，已完成）：

- `docs/decisions/adr-021-management-account-takeover.md` v1.1：决策节委托条目改写为「继续后置 v1.x」定案 + 状态行标注。
- `docs/arch/artemis-next-architecture.md` v1.47：§5.3 账户条目（RBAC 模型 + 委托定案）、§6 RBAC 行（跨服务委托与 mTLS 随 v1.x）。

## 实现结果（⑦ 补）

- **core**：`Role`（三预置角色常量矩阵）/ `Resource` / `Action` / `Permission`；`Account` 加 `role`；`AccountStore` 多账户化（`findByUsername`/`list`/`updateRole`/`delete`，`findAny` 退役）；`AccountAuthService.verify` 返回 `Optional<AuthenticatedAccount>`；`DbAccountAuthService` 键控缓存 + `invalidate`；`UserAccountService`（校验 + 最后 ADMIN/自删保护 + 缓存失效联动）。
- **server**：`AdminRoutePermissions`（逐端点登记）+ `RbacAuthorizationFilter`（仅 DB 态，order 2，默认 deny）；`UserController`（users CRUD/role/password + roles，展示面无 hash）；`AccountController` 改密以认证主体定位；`AccountStoreImpl`（`@TableId`、唯一键冲突翻译 ALREADY_EXISTS）；V7 双方言迁移（`role` 列默认 ADMIN）；`PersistenceDialectTestBase` schemaVersion 断言升 7。
- **⑤ 修复**：`UserController` `@PathVariable` 显式命名（编译无 `-parameters`，未命名 400）；单字段请求 record 加 `@JsonProperty`。
- **⑥/⑦ review（内联 max）**：2 条 low 均接受并注释——最后 ADMIN 保护 check-then-act 竞态（恢复路径 DBA SQL，见 `UserAccountService.adminCount` ponytail 注释）；认证缓存回填/invalidate 竞态 ≤60s TTL（见 `DbAccountAuthService.verify` ponytail 注释）。

## 文档同步（⑩ 填）

- `docs/product/artemis.md` v1.38：新增 v1.0 phase-2 交付节（权限模型/enforcement/用户管理/ADR-021 定案/工程形态）。
- `docs/api/admin-api.md` v1.16：§1 加 RBAC 行；§5 重写（5.1 自助改密主体定位、5.2 权限矩阵、5.3 用户管理端点）；§7 补 403/404/409 账户管理错误码；§8 已知限制（并发竞态两条 + 无自定义角色/console 无用户管理页）。
- review 定案同步（③ 已完成）：`docs/decisions/adr-021-management-account-takeover.md` v1.1（委托继续后置 v1.x）；`docs/arch/artemis-next-architecture.md` v1.47（§5.3 RBAC 模型 + §6 RBAC 行）。
- 索引：phase 两份文档开工时已登记 `phases/README.md`；admin-api/产品文档索引既有登记无变化；CLAUDE.md 无构建/模块变化不更新。

## 更新历史

| 版本 | 日期 | 变更说明 |
| ------ | ------ | ------ |
| 1.2 | 2026-10-27 | ⑦ 实现结果 + ⑩ 文档同步清单 |
| 1.1 | 2026-10-27 | 设计定稿（含 ADR-021 委托定案：继续后置） |
| 1.0 | 2026-10-27 | 开工骨架 |
