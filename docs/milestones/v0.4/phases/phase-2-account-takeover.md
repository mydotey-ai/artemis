# Phase 2：账户体系与统一认证接管

版本: 1.3    更新时间: 2026-10-25

## 目标

单管理员账户 DB 持久化（首张业务表，V2）+ 统一认证接管：management 作为认证源，各服务账户对接（接管范围与既有本地单账户行为兼容由 ③ 对照架构 §5.3/§6 定稿）。

## 范围

做什么（据 plan phase-2 说明）：

- ORM 决断落地（phase-1 推迟：MyBatis-Plus 对 SB 4.1 适配验证，无适配回落——技术选型 §6 待定项）。
- 账户表（V2 migration，双方言）+ 账户 repository。
- 单管理员账户 DB 持久化（建立/凭证语义）。
- 统一认证接管：management 自身认证源切换 DB；各服务账户对接方式（③ 定稿）。

不做什么：

- RBAC（角色 × 资源 × 动作，v1.0）。
- 多用户/账户管理 API 全量（本 phase 单管理员）。
- console（phase-6）。

## 涉及模块

| 模块 | 变更 |
|---|---|
| artemis-management-core | domain 新增 `Account` / `AccountStore` 端口 / `PasswordHasher` / 认证不可用异常；application 新增 `AccountAuthService` 接口与 DB 态实现（缓存 + seed + 改密用例）。零 Spring / 零 MyBatis（enforcer 不变） |
| artemis-management-server | 依赖 MyBatis-Plus boot4 starter（仅本模块）；`V2__account.sql` 双方言；MyBatis `AccountStore` 实现 + 装配（内存态/DB 态二选一）；`HttpBasicAuthFilter` 改走裁决器；新增改密端点 |
| artemis-registry-server / artemis-discovery-server | **零改动**（各服务本地单账户维持，见产品设计 §7） |
| proto | 不涉及（账户为 management 本地管理面能力，无契约变更） |

## 验收方式

对应测试文档：[phase-2-account-takeover-test.md](phase-2-account-takeover-test.md)

## 产品设计（③ 补）

### 1. ORM 决断（技术选型 §6 待定项 #13，本 phase 决断）

**采用 MyBatis-Plus 3.5.17（`mybatis-plus-spring-boot4-starter`）**，仅 management-server 依赖：

- 适配依据：baomidou 已发布 boot4 starter 线（3.5.13–3.5.17，最新 2026-07），3.5.17 基于 SB 4.0.1 BOM + mybatis-spring 4.0.0；SB 4.0 → 4.1 为 minor 兼容窗口，starter 依赖面为 autoconfigure（API 窄）。
- 适配验证载体 = 本 phase 账户表：enforcer + 编译 + 装配 + 双方言 Testcontainers IT 全绿即决断成立；**回退预案**：实测不兼容 → JdbcTemplate（零新依赖，phase-1 基座已就绪 Boot 自动配置），同步修订技术选型 #13。
- 落地纪律：core 零感知（端口接口 + 纯 Java 实现）；DB 行映射 entity 与 mapper 居 server，domain `Account` 不带 MyBatis 注解（ADR-015 分层）。
- 同步落点：技术选型 #13 决断落定 + §6 待定项移除（review 通过时）。

### 2. 账户域模型（management-core）

```java
// domain
public record Account(String username, String passwordHash, Instant createdAt, Instant updatedAt) {}

public interface AccountStore {
    boolean existsAny();
    Optional<Account> findAny();                       // 单管理员：ORDER BY username LIMIT 1
    void insert(Account account);
    void updatePasswordHash(String username, String passwordHash, Instant updatedAt);
}

public final class PasswordHasher {                    // 纯 JDK，零新依赖
    public static String hash(String rawPassword);     // pbkdf2-sha256$600000$<b64salt>$<b64dk>
    public static boolean matches(String rawPassword, String storedHash);
}
```

- **密码哈希**：PBKDF2WithHmacSHA256（JDK 内置），iterations 600,000（OWASP 现行推荐）、salt 16B SecureRandom、DK 32B；哈希串自描述（算法/参数内嵌），存储格式演进不改表结构。比较用 `MessageDigest.isEqual` 常量时间。
- **单管理员语义**：表至多一行（应用层保证——seed 前查 `existsAny`）；PK 为 username，脏多行时 `findAny` 取字典序首行（容错不 fail-fast，排障面可见）。
- **认证不可用异常**（domain）：`AccountStore` 访问失败由 application 实现包装抛出，接入层转 503（见 §5）。

### 3. V2 migration（双方言，各自表达）

`db/migration/{vendor}/V2__account.sql`：

```sql
-- postgresql: timestamp with time zone；mysql: datetime(6)（UTC 写入，规避 TIMESTAMP 2038/时区语义）
CREATE TABLE artemis_account (
    username      VARCHAR(64)  PRIMARY KEY,
    password_hash VARCHAR(512) NOT NULL,
    created_at    <时间类型>   NOT NULL,
    updated_at    <时间类型>   NOT NULL
);
```

时间戳由 Java 侧统一 UTC 写入，不依赖 DB 默认值函数（双方言差异不入 SQL）。`password_hash` 512 为格式演进裕量（当前串约 90 字符）。

### 4. 认证裁决与双轨切换

application 定义裁决端口，装配层按运行形态二选一（判据沿用 phase-1：DataSource bean 存在性，零新增配置键）：

```java
// application
public interface AccountAuthService {
    boolean verify(String username, String password);
    void changePassword(String oldPassword, String newPassword);   // 单管理员：不含 username
}
```

| 形态 | 实现 | verify 语义 | changePassword 语义 |
|---|---|---|---|
| 内存态（无 DataSource） | `ConfigAccountAuthService`（server 装配层，包装 `ManagementAuthConfig`） | 配置凭证比对（v0.1 行为不变） | 不支持（端点 409，提示走配置） |
| DB 态 | `DbAccountAuthService`（core application，依赖 `AccountStore` + `Clock`） | 表账户 PBKDF2 验证 + 成功凭证缓存 | 校验 old → 哈希新密码 → 写表 → 清缓存 |

- **DB 态凭证唯一源 = 账户表**：`artemis.management.auth.username/password` 在 DB 态失效（启动 WARN 提示已 seed 物化）；`artemis.management.auth.enabled=false` 开关两态语义不变（关 = 全放行）。
- **成功凭证单槽缓存**（DB 态）：`volatile (token, expiry)`，TTL 60s——正确路径每 60s 付一次 PBKDF2（~百 ms 级），错误路径每次全额计算（暴力破解无缓存折扣）；`changePassword` 即时清槽（旧凭证立即失效）。`// ponytail: 单槽缓存只服务单管理员；多用户 v1.0 RBAC 改按账户键控缓存`。
- **seed 语义（一次性物化）**：DB 态启动且表空 → 读 `ManagementAuthConfig` 当前配置值（默认 artemis/artemis）hash 后 insert——已配置自定义凭证的部署升级到 DB 态无缝；表非空跳过（配置凭证此后被忽略）。seed 在 Flyway migration 后执行（装配顺序经 `persistenceInfo` bean 依赖保证，phase-1 触发机制不变）。
- **默认密码告警延续**（ADR-009 语义）：seed 默认密码时 WARN；后续启动以 `PasswordHasher.matches(默认密码, stored)` 判定仍用默认密码则 WARN（哈希不可逆但可主动验证）。

### 5. 接入层改造（management-server）

- `BasicAuth` 新增 `decode`：Basic header → `(username, password)` 拆解（坏 b64 / 缺冒号 → null，拒绝）。
- `HttpBasicAuthFilter`：`enabled` 判定与白名单不变；凭证检查从「配置凭证整串比较」改为 `decode → AccountAuthService.verify`。裁决结果三态：true 放行 / false 401（含 WWW-Authenticate）/ 认证不可用 503 + WARN（fail-closed，不记录凭证）。
- 内部规则流 gRPC 端口（`ManagementGrpcAuthConfig`，discovery 订阅面）**不在接管范围**：独立服务账户、默认关闭，维持现状。
- 新增改密端点：

```text
PUT /admin/v1/account/password        # 受当前认证源保护（两态均拦截）
{"oldPassword": "...", "newPassword": "..."}
→ 200 {"username": "<账户名>"} | 400（old 错 / newPassword < 8 字符 / 参数缺失）| 409（内存态，body 说明走配置）
```

新密码最小长度 8（seed 的默认密码 7 字符豁免——默认凭证本就要求立即改）；改密成功即时生效（清缓存）。改密操作的审计记录随 phase-3 审计表落地（本 phase 无审计表，不预建）。

### 6. MyBatis-Plus 落地（server）

- `AccountEntity`（`@TableName("artemis_account")`）+ `AccountMapper extends BaseMapper<AccountEntity>`（`@Mapper`，免 MapperScan）；`AccountStoreImpl` 实现 domain 端口，entity ↔ `Account` record 转换在实现内。
- `Instant` 字段用 mybatis 内置 TypeHandler（PG `timestamptz` / MySQL `datetime(6)`，UTC 语义）。
- **组合 classpath 风险预登记**（phase-1 教训延续）：registry/discovery 主类已排除 `DataSourceAutoConfiguration`，无 DataSource bean 时 MyBatis-Plus 自动配置静默不激活（条件装配）；`@Mapper` 扫描在无 SqlSessionFactory 时不产生 bean。④ 实现须在 dist/fixture 组合 classpath 场景回归验证（`ApplicationContextTest` 系）。

### 7. 统一认证接管范围（架构 §5.3「接管各服务本地账户」的落地定稿）

**接管 = 管理访问面收敛于 management 单管理员 DB 账户；各服务本地单账户保留为服务自身凭证，零改动。**

| 访问面 | 认证源 | 本 phase 变化 |
|---|---|---|
| management Admin API（外部 HTTP） | DB 态：账户表；内存态：配置凭证 | **接管落点**（本 phase） |
| console（phase-6，management 内嵌） | 同上（复用 Admin API 认证链） | 交付即用 DB 账户 |
| registry / discovery 外部端口（SDK 数据面） | 各服务本地单账户（配置凭证，动态轮换） | 零改动（凭证与管理凭证**分域**：SDK 凭证随业务方分发，不应与管理员凭证同源） |
| discovery `/admin/v1` 只读排障面 | discovery 本地账户 | 零改动 |
| management 内部规则流 gRPC | 独立服务账户（默认关闭） | 零改动 |

理由：registry 零上游依赖是架构 §2.1 资产（认证委托会引入 management 运行时依赖 + failover 复杂度）；单管理员场景下「统一」的真实消费面是 console + Admin API（均在 management）；各服务管理面委托机制随 v1.0 RBAC（多用户出现真实需求）一并设计。

**架构同步（review 通过时落）**：架构 §5.3 / §6 认证行「接管各服务本地账户」表述精化为上表语义；ADR-009 补 v0.4 落地链接；新增 **ADR-021**（单管理员账户接管形态：DB 唯一源 + 分域保留 + 委托后置 RBAC）并登记架构 §7 总览表与 `decisions/README.md`。

### 8. 可观测与配置

- 登录失败 WARN（不带凭证）、seed/改密 INFO（结构化 JSON，既有 log 通道）；认证指标（失败计数等）随 v1.0 安全审计递补。
- **零新增自定义配置键**：形态判据、seed 源均复用既有键（phase-1 纪律延续）；密码策略（最小长度）与 PBKDF2 参数为常量（`PasswordHasher` 内，注释标注依据）。

### 9. review 定案登记（2026-10-25 通过）

- **§7 接管范围定稿**（管理访问面收敛 + 各服务账户保留分域）：原始文档 [架构](../../../arch/artemis-next-architecture.md) §5.3/§6「接管各服务本地账户」表述已同步精化（1.40）；新增 [ADR-021](../../../decisions/adr-021-management-account-takeover.md) 并登记架构 §7 总览表（D21）与 `decisions/README.md`；[ADR-009](../../../decisions/adr-009-security-by-default.md) 补 v0.4 落地形态节（1.2）。
- **§1 ORM 决断落定**（MyBatis-Plus 3.5.17 boot4-starter + 实测验证 + JdbcTemplate 回退预案）：[技术选型](../../../dev/tech-stack-java.md) #13 已同步决断（1.27），§6 待定项移除。
- §2–§6、§8 机制落点不变更已记载设计（phase-1 双轨判据 / ADR-015 分层 / ADR-009 默认凭证语义），无原始文档修订。

## 实现结果（⑦ 补）

实施要点与设计假设修正：

- **MyBatis-Plus 3.5.17（boot4-starter）对 SB 4.1 实测兼容**（enforcer + 编译 + 装配 + 双方言 IT 全绿，含 dist 组合 classpath）——#13 决断成立，JdbcTemplate 回退未触发。内存态零风险路径确认：无 DataSource bean 时 MyBatis-Plus autoconfig 类级条件不成立、整体不激活，`@Mapper` 零注册零实例化。
- **装配时序**：`accountAuthService` bean 显式依赖 `persistenceInfo`（其构造触发 Flyway，phase-1 机制），保证建表先于 seed；IT `should_seed_default_account_and_login` 实证。
- `AccountStore` bean 以 `@ConditionalOnProperty("spring.datasource.url")` 条件装配（与 DataSource 同键，不依赖 bean 定义顺序）；`AccountStoreImpl` 不加 `@Repository`（无条件实例化会在内存态因缺 mapper 崩溃）。
- SB 4.1 包名/陷阱：`WebServerApplicationContext` 移包 `org.springframework.boot.web.server.context`；手动起的 context 自身不是 bean，取端口用强转而非 `getBean`。
- **flyway 禁用 + url 配置的行为边界**（review 信息项）：schemaVersion 读取维持 phase-1 降级语义，但账户 seed 需表存在——表缺失时启动失败（fail-fast）。外部工具管理 schema 的部署须先建表再启动，属合理暴露而非回归。
- **MySQL 时间字面量**（review 信息项）：`Instant`→`Timestamp` 走 JDBC 默认时区写入 `DATETIME(6)`，非 UTC JVM 下 `created_at`/`updated_at` 字面值偏移（PG `timestamptz` 无此问题）——展示型时间戳无正确性影响；跨时区部署统一 JVM UTC 或后续切 `TIMESTAMP` 语义再议。

### code-review 修复（⑦，2 findings 全修 + 2 信息项）

1. **[中] 503 响应形态不一致**：认证后端不可用路径 `sendError(503)` 走容器 error 机制，与 401 手写 JSON 不一致——改手写 `{"error":"UNAVAILABLE"}`。
2. **[低] 测试语义误导**：`ConfigAccountAuthServiceTest` 「空白配置凭证拒绝」注释与 SCF 空白回落默认语义（ADR-009）不符——改为直测「请求侧空白密码拒绝」。
3. 信息项两条（flyway 禁用边界、MySQL 时区）登记于上文实施要点，不改码。

### review 定案登记（2026-10-25 通过）

## 文档同步（⑩ 填）

- [产品文档](../../../product/artemis.md)（1.26）：新增 v0.4 phase-2 交付节（DB 账户/接管语义/改密 API/缓存与 fail-closed 细节/工程形态）。
- [admin-api.md](../../../api/admin-api.md)（1.7）：§1 认证双态语义（DB 态账户表唯一源 + seed 物化 + fail-closed）；新增 §5 账户 API（改密端点）；错误表补 409 `NOT_SUPPORTED_IN_MEMORY_MODE` 与 503 `ACCOUNT_STORE_UNAVAILABLE`；状态 API 重编号 §6。
- [架构](../../../arch/artemis-next-architecture.md)（1.40）+ [ADR-021](../../../decisions/adr-021-management-account-takeover.md)（新增）+ [ADR-009](../../../decisions/adr-009-security-by-default.md)（1.2）+ [技术选型](../../../dev/tech-stack-java.md)（1.27，#13 决断 + §6 待定项移除）：③ review 定案时已同步（见「review 定案登记」）。
- CLAUDE.md / 索引：无过时项（模块结构、构建命令、测试入口未变；两文档已在 phases/README.md 登记）。

## 更新历史

| 版本 | 日期 | 变更说明 |
| ------ | ------ | ------ |
| 1.3 | 2026-10-25 | 文档同步登记（产品文档/admin-api）；人工验证通过 |
| 1.2 | 2026-10-25 | 实现结果登记：MyBatis-Plus SB 4.1 实测兼容、装配时序、SB 4.1 陷阱；code-review 2 findings 修复 + 2 信息项 |
| 1.1 | 2026-10-25 | 产品设计定稿（待 review）：ORM 决断（MyBatis-Plus boot4 线 + 回退预案）、账户域模型与 V2 表、双轨认证裁决与 seed 语义、改密端点、接管范围定稿（ADR-021 预登记） |
| 1.0 | 2026-10-25 | 开工骨架 |
