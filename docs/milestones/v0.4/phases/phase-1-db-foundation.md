# Phase 1：management DB 接入基座

版本: 1.3    更新时间: 2026-10-25

## 目标

为 management-service 接入可选 DB 持久化基座：MySQL/PG 双方言 + Flyway migration + 连接装配，未配置 DB 时保持期 1 内存态运行（零 DB 双轨约束）。

## 范围

做什么（据 plan phase-1 说明）：

- DB 连接装配（可选依赖：未配置 = 内存态，配置 = 持久化启用）。
- Flyway migration 组织与双方言脚本策略。
- 运行形态可观测（内存态 / DB 态 + schema 版本）。
- 测试基建：双方言验证策略（Testcontainers，③ 设计定稿）。

不做什么：

- 业务表与业务语义（账户/摘除/审计/逻辑实例/规则——归 phase-2..5）。
- **ORM / repository 层（范围调整，见「产品设计 §5」，review 确认点）**：phase-1 无业务表，MyBatis-Plus（技术选型 #13）及其 SB 4.1 适配决断（§6 待定项）推迟至首个业务表 phase（phase-2 账户）。
- console 与 all-in-one（phase-6/7）。
- 数据面（registry/discovery）任何变更。

## 涉及模块

| 模块 | 变更 |
|---|---|
| artemis-management-server | DB 依赖组（starter-jdbc + Flyway + 双驱动）、Flyway 脚本与 locations 配置、PersistenceInfo 装配、状态 API 出口、Testcontainers IT |
| artemis-management-core | 仅 `application` 包新增 `PersistenceInfo`（运行形态信息，非领域概念，domain 不动） |
| proto | 不涉及（无契约变更） |

新增依赖组件均为技术选型组件总表已登记项：#14 Flyway、#15 HikariCP（随 starter-jdbc）、#16 MySQL/PG 驱动、#21 Testcontainers（首次启用，test scope）；无新增待评审组件。

## 验收方式

对应测试文档：[phase-1-db-foundation-test.md](phase-1-db-foundation-test.md)

## 产品设计（③ 补）

### 1. 运行形态与双轨语义

DB 是 management 的**可选装配依赖**，由配置驱动二选一，运行期不切换（DB 连接为静态配置，guidelines §10.2）：

| 形态 | 触发条件 | 行为 |
|---|---|---|
| 内存态（memory） | 未配置 `spring.datasource.url` | 期 1 现状不变：无 DataSource/Flyway 装配，摘除记录等保持内存实现 |
| DB 态（database） | 配置 `spring.datasource.url` | 装配 HikariCP DataSource + Flyway migration 启动期执行；**phase-1 业务仍内存态**（摘除等不落库），持久化业务随 phase-2+ 逐表接管 |

「配置了 DB 但业务仍内存」是 phase-1 到 phase-5 的过渡期显式语义：基座就绪即验证（migration 可跑、连接可用、形态可观测），业务接管逐 phase 交付；状态 API 如实暴露形态，不产生「已落库」的误导。

### 2. 配置模型

- **配置归属**：`spring.datasource.*` 与 `spring.flyway.*` 是 Spring Boot / HikariCP / Flyway 组件自有配置，按 guidelines §10.1「框架与三方组件自有配置由组件自身体系管理」，**不经 SCF**；本 phase **零新增自定义配置键**——「是否 DB 态」唯一判据为 `spring.datasource.url` 是否存在，不引入平行开关（防双源漂移）。
- **双方言**：`url` 指向 MySQL 或 PostgreSQL 任一，由驱动与 Flyway 按协议自适应（两驱动均打包，见 §3），不做产品层方言枚举配置。

### 3. 依赖落点（artemis-management-server）

```xml
<!-- 连接池 + 事务管理器自动装配（HikariCP，#15） -->
spring-boot-starter-jdbc
<!-- migration（#14；Flyway 10+ 数据库支持拆分模块，版本随 SB BOM） -->
flyway-core + flyway-mysql + flyway-database-postgresql
<!-- 双方言驱动（#16，产品承诺：两驱动均 runtime 打包，按 url 自动选择） -->
mysql-connector-j (runtime) + org.postgresql:postgresql (runtime)
```

未配置 url 时上述依赖零装配（Boot DataSource/Flyway 自动配置条件不满足，classpath 残留无害）；artemis-dist（v0.4 phase-7 三服务组合）经 management-server 传递携带，registry/discovery 自身不引任何 DB 依赖。

### 4. Flyway migration 组织

- **脚本位置**：`artemis-management-server/src/main/resources/db/migration/{vendor}/`——Flyway 原生 vendor 目录（`{vendor}` 由 Flyway 按 JDBC url 替换为 `mysql` / `postgresql`），**双方言各自成目录、各自写脚本**，不追求 SQL 兼容子集（后续业务表的 ID 生成、时间类型等差异直接各方言表达）。
- **locations 配置**：`spring.flyway.locations=classpath:db/migration/{vendor}` 进 management-server 默认配置（application.yml）。
- **V1 基线**：两方言各一份 `V1__baseline.sql`（注释性 baseline，无表）；业务表自 V2 起逐 phase 追加（phase-2 账户表为首个）。
- 版本顺序纪律：migration 只追加不修改（Flyway checksum 约束），脚本评审随各 phase 设计走。

### 5. ORM / repository 推迟（范围调整决策）

plan 验收方式原文含「repository 双方言语义」；本设计调整为**推迟至 phase-2**（首个业务表出现时），理由：

- phase-1 无业务表，repository/事务模板无消费面，引入即空转（依赖先于需求的反模式）；
- MyBatis-Plus 对 SB 4.1 的适配是技术选型 §6 待定项，决断需要真实表与 SQL 作验证载体，空转引入无法完成决断只把风险前移；
- 基座的核心价值（连接装配、双方言 migration 机制、形态可观测）不依赖 ORM 验证即可闭环。

事务管理器随 starter-jdbc 自动装配就绪，phase-2 首个写操作直接消费，无需本 phase 显式装配。

### 6. 运行形态可观测（状态 API 出口）

- core `application` 包新增运行形态值对象：

```java
public record PersistenceInfo(Mode mode, String databaseProduct, String schemaVersion) {
    public enum Mode { MEMORY, DATABASE }
    public static PersistenceInfo memory();   // (MEMORY, null, null)
}
```

- server 装配按 DataSource bean 存在性二选一构造：无 DataSource → `PersistenceInfo.memory()`；有 → `DATABASE` + JDBC `DatabaseMetaData` 产品名 + Flyway history 末行版本（`installed_rank` 序、`success` 过滤、字符串原样；查询失败如 `spring.flyway.enabled=false` 时降级 null + WARN，不拒绝启动——migration 管理方式属运维自由）。
- `StatusController` 新增 `GET /admin/v1/status/persistence` 返回 `PersistenceInfo`（资产 #12 状态 API 排障面延续；受既有 Basic 认证保护）。双轨验收出口即此端点。

## 实现结果（⑦ 补）

实施要点与设计假设修正（详细修复过程见测试文档「回归记录」）：

- 装配形态修正：SB 4.1 引入 starter-jdbc 后未配置 url 时 Boot 数据源自动配置**不跳过而是启动失败**（驱动推断失败）——主类 `excludeName` 排除 `DataSourceAutoConfiguration`（字符串形式：Class 引用在组合 classpath 缺 spring-boot-jdbc 时使注解属性解析失败）+ `PersistenceConfiguration` 以 `@ConditionalOnProperty("spring.datasource.url")` 条件自建 HikariDataSource（经 Binder 保留 `spring.datasource.hikari.*` 配置面）；Boot 的 Flyway/事务/JdbcTemplate 自动配置因 DataSource bean 存在而照常接管。补充依赖 `spring-boot-flyway`（SB 4.1 Flyway 自动装配独立模块，无 starter）。
- **组合 classpath 装配边界**（实施期演进，⑦ 定稿）：DB 组为正常依赖（optional 曾用于隔离传染，但 repackage 会排除 optional 致 exec jar 部署即崩——见 review 修复 #1）；防护由三服务主类 `excludeName` 显式声明（registry/discovery 不装配 DB）。dist 同 JVM 组合（phase-7）沿用该边界。
- **组合 classpath introspection 约束**：`PersistenceConfiguration` 方法签名与泛型参数零引用 Flyway/Hikari 类型（反射 introspection/依赖解析在组合 classpath 上会因类缺失失败），schema 版本经 JDBC 只读 `flyway_schema_history`（双方言同构 SQL）、migration 先行经方法体内触发 `FlywayMigrationInitializer`（`ObjectProvider` 参数不被 Boot 数据库初始化依赖检测识别）。
- Testcontainers **2.0** API：模块名带 `testcontainers-` 前缀、容器类移包 `org.testcontainers.{mysql,postgresql}`、容器类去泛型；`@Testcontainers(disabledWithoutDocker = true)` 无 Docker 整类跳过。
- 本地验证环境：Docker Desktop daemon 启动 + daemon.json 配 registry mirror（Docker Hub 直连不可达）。

### review 定案登记（2026-10-24 通过）

- §5 ORM/repository 推迟 phase-2：触及 [技术选型](../../../dev/tech-stack-java.md) §6 待定项 #13 决断时机——已同步修订（1.25）。
- §1–§4、§6 机制落点不变更已记载设计（#14/#15/#16 实施落位、guidelines §10.1 配置归属既有规则），无原始文档修订。

### code-review 修复（⑦，6 findings 全修）

1. **exec fat jar 缺 DB 依赖（致命）**：optional 依赖被 boot repackage 排除，部署产物配 DB 即 `NoClassDefFoundError`——DB 组回退为正常依赖；组合 classpath 防护改为**各服务主类 `excludeName` 显式声明装配边界**（registry/discovery/management 三主类均排除 `DataSourceAutoConfiguration`，自描述且覆盖 fixture 与 dist 同 JVM 组合；管理面 fixture 的临时 exclude 参数随之删除）。plugin 级 `<dependencies>` hack 试过无效（repackage 不支持附加依赖），已弃。
2. **schemaVersion 字典序错值**：`MAX(version)`（VARCHAR 字典序）V10+ 取错、点分版本解析崩启动——改 `WHERE success ORDER BY installed_rank DESC LIMIT 1`（双方言同构）、类型 Integer→String（Flyway 版本号原样）。
3. **flyway 禁用场景启动崩**：`spring.flyway.enabled=false` + url 配置时查 history 表 SQLException 拒绝启动——版本查询降级 null + WARN（DB 态由 url 决定，migration 管理方式属运维自由）。
4. dev-state 步骤表重复行清理（编辑残留）。
5. 本文档「（待实现）」占位残留删除。
6. `spring.datasource.url` 空串配置显式失败（清晰报错，防静默内存态退化）。

## 文档同步（⑩ 填）

- [产品文档](../../../product/artemis.md)（1.25）：新增 v0.4 phase-1 交付节（DB 可选装配/双方言/配置/状态 API/过渡期语义）；交付状态首段 v0.3 收尾修正。
- [admin-api.md](../../../api/admin-api.md)（1.6）：新增 §5.4 `GET /admin/v1/status/persistence`。
- [技术选型](../../../dev/tech-stack-java.md)（1.26，③ 时已同步 1.25）：#21 Testcontainers 补 2.0 版本线与模块/包名变化、无 Docker 跳过形态；§6 待定项 #13 决断时机细化（③ 定案）。
- 架构文档/ADR：无机制变更（双轨 + 外部 DB 为 ADR-005 既有记载，装配边界与 introspection 约束为实现细节，已记录于本文档「实现结果」）。
- CLAUDE.md / 索引：无过时项（模块结构、构建命令未变；本 phase 两文档已在 phases/README.md 登记）。

## 更新历史

| 版本 | 日期 | 变更说明 |
| ------ | ------ | ------ |
| 1.3 | 2026-10-25 | 文档同步登记（产品文档/admin-api/技术选型）；人工验证通过 |
| 1.2 | 2026-10-25 | code-review 修复登记（6 findings：exec jar 打包、版本查询语义、降级、残留清理）；实现结果补实施要点 |
| 1.1 | 2026-10-24 | 产品设计定稿（待 review）：双轨装配语义、配置归属、依赖落点、Flyway vendor 目录、ORM 推迟 phase-2、状态 API 出口 |
| 1.0 | 2026-10-24 | 开工骨架 |
