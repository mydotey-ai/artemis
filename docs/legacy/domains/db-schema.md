# 管理面数据库 Schema（DDL 级）

状态: 草案  日期: 2026-10-08

> 调研对象：原仓库 `~/Projects/mydotey/artemis`（version 2.0.2，git HEAD 9727bb5）。
> 定位：**契约层**制品——管理面 DB 的 DDL 级完整描述，供 1:1 对标复刻建库。概念与行为见 [traffic-governance-logic](traffic-governance-logic.md) / [operations-audit-logic](operations-audit-logic.md)。
> 证据源：MySQL DDL `artemis-package/deployment/artemis-management.sql`（337 行）；SQLite 测试 DDL `artemis-test/src/test/resources/schema.sql`（298 行）；DAO `artemis-management/.../{dao,group/dao,zone/dao}/`。路径均相对原仓库根。

## 1. 总览

20 张表 = **10 业务表 + 10 审计日志表**。全部 `ENGINE=InnoDB DEFAULT CHARSET=utf8`；**无任何 FOREIGN KEY 约束**（DDL 仅有 PK / UNIQUE KEY / KEY，引用完整性由 Java 层保证）。

| # | 业务表 | 日志表 |
|---|---|---|
| 1 | instance | instance_log |
| 2 | server | server_log |
| 3 | service_group | service_group_log |
| 4 | service_group_instance | service_group_instance_log |
| 5 | service_group_operation | service_group_operation_log |
| 6 | service_group_tag | service_group_tag_log |
| 7 | service_instance | service_instance_log |
| 8 | service_route_rule | service_route_rule_log |
| 9 | service_route_rule_group | service_route_rule_group_log |
| 10 | service_zone | service_zone_log |

**共同模式**：`ID bigint(20) AUTO_INCREMENT` 主键；`CREATE_TIME datetime NOT NULL DEFAULT CURRENT_TIMESTAMP`；`DataChange_LastTime timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP` + 独立 KEY。文本列多为 `NOT NULL DEFAULT ''`。**列名大小写不统一**（`DataChange_LastTime` 大驼峰、`service_group.type` / `service_route_rule.strategy` / 多处 `reason` 小写、`REASON`/`OPERATION` 大写）——1:1 复刻须保持原样。

## 2. 逐表 DDL

### 2.1 摘除操作表（OPERATION 语义见 [operations-audit-spec](operations-audit-spec.md) FR-OA-01）

**instance** — 实例下线状态表（行存在 = 生效中；删除行 = 恢复。**非注册表**）

| 字段 | 类型(长度) | 可空 | 默认 | 键/索引 |
|---|---|---|---|---|
| ID | bigint(20) | N | AUTO_INCREMENT | PK |
| INSTANCE_ID / SERVICE_ID / REGION_ID / OPERATION | varchar(255) | N | '' | UNIQUE `INSTANCE_ID`(INSTANCE_ID,SERVICE_ID,REGION_ID,OPERATION) |
| OPERATOR_ID / TOKEN | varchar(255) | N | '' | |
| CREATE_TIME | datetime | N | CURRENT_TIMESTAMP | |
| DataChange_LastTime | timestamp | N | ON UPDATE | KEY |

**instance_log**：字段同上 + `COMPLETE tinyint(1) NOT NULL DEFAULT '0'` + `EXTENSIONS varchar(2048) NOT NULL DEFAULT '{}'`；键为**普通 KEY**（非唯一）+ DataChange_LastTime。

**server** / **server_log**：同模式，去掉 SERVICE_ID / INSTANCE_ID，`SERVER_ID` + `REGION_ID` + `OPERATION` 组成 UNIQUE `SERVER_ID`。

**service_zone**（L309-320）：`SERVICE_ID` + `REGION_ID` + `ZONE_ID` + `OPERATION` → UNIQUE `SERVICE_REGION_ZONE`；无软删。
**service_zone_log**（L322-337）：+ `OPERATOR_ID` / `TOKEN` / `REASON varchar(128) DEFAULT ''` / `COMPLETE tinyint(1)`；索引 `SERVICE_REGION_ZONE`（非唯一）。

### 2.2 分组与路由表（概念见 [traffic-governance-logic](traffic-governance-logic.md)）

**service_group**（L63-82）

| 字段 | 类型 | 可空 | 默认 | 键/索引 |
|---|---|---|---|---|
| ID | bigint(20) | N | AUTO | PK |
| SERVICE_ID / REGION_ID / ZONE_ID / NAME | varchar(255) | N | '' | UNIQUE `SERVICE_REGION_ZONE_GROUP`(SERVICE_ID,REGION_ID,ZONE_ID,NAME) |
| APP_ID | varchar(255) | N | '' | |
| DESCRIPTION | varchar(1024) | Y | NULL | |
| STATUS | varchar(255) | N | '' | KEY `STATUS`（active/inactive） |
| DELETED | tinyint(1) | Y | '0' | KEY（**软删**） |
| type | varchar(64) | N | 'physical' | 小写列名；⚠ DAO 从不写（§6.6） |
| CREATE_TIME / DataChange_LastTime | | | | KEY |

**service_group_instance**（L84-93）：`GROUP_ID bigint(20)` + `INSTANCE_ID varchar(255)` → UNIQUE `GROUP_INSTANCE`；无软删。
**service_group_instance_log**（L95-108）：+ `OPERATION` / `OPERATOR_ID` / `TOKEN` / `REASON varchar(128) DEFAULT ''`；**无 EXTENSIONS 列**。

**service_group_operation**（L138-147）：`GROUP_ID` + `OPERATION` → UNIQUE `GROUP_OPEATION`（**原样拼写错误，复刻须保留**）；无软删。
**service_group_operation_log**（L149-164）：+ `OPERATOR_ID` / `TOKEN` / `COMPLETE` / `EXTENSIONS` / `reason varchar(128)`（**小写列名**）。

**service_group_tag**（L166-176）：`GROUP_ID` + `TAG` + `VALUE` → UNIQUE `GROUP_TAG`。
**service_group_tag_log**（L178-193）：+ OPERATION / OPERATOR_ID / TOKEN / EXTENSIONS；**无 COMPLETE、无 REASON**。

**service_instance**（L195-216）— 逻辑实例（静态实例，非注册表）

| 字段 | 类型 | 可空 | 默认 | 说明 |
|---|---|---|---|---|
| ID | bigint(20) | N | AUTO | PK |
| SERVICE_ID / INSTANCE_ID | varchar(255) | N | '' | UNIQUE `SERVICE_INSTANCE` |
| IP / MACHINE_NAME | varchar(255) | N | '' | |
| METADATA | varchar(1024) | N | '' | JSON 串 |
| PORT | int(11) | N | '80' | |
| PROTOCOL | varchar(16) | N | 'http' | |
| REGION_ID | varchar(16) | N | '' | |
| ZONE_ID | varchar(128) | N | '' | |
| GROUP_ID | varchar(255) | N | '' | varchar，非 FK |
| HEALTHY_CHECK_URL / URL | varchar(512) | N | '' | |
| DESCRIPTION | varchar(1024) | Y | NULL | |
| CREATE_TIME / DataChange_LastTime | | | | |

**service_instance_log**（L218-241）：= service_instance 去 DESCRIPTION，+ OPERATION / OPERATOR_ID / TOKEN；**无 COMPLETE / EXTENSIONS / REASON**。

**service_route_rule**（L243-259）：`SERVICE_ID` + `NAME` → UNIQUE `SERVICE_ROUTE_RULE_NAME`；`STATUS`（KEY）；`DELETED tinyint(1) DEFAULT '0'`（KEY，**软删**）；`strategy varchar(64) NOT NULL DEFAULT 'weighted-round-robin'`（小写列名）。

**service_route_rule_group**（L261-273）：`ROUTE_RULE_ID` + `GROUP_ID` → UNIQUE `ROUTE_RULE_GROUP`；`WEIGHT int(11) NULL`；`UNRELEASED_WEIGHT int(11) NULL`（两段式发布双列）。

**service_route_rule_group_log**（L275-290）：`ROUTE_RULE_ID` + `GROUP_ID` + `WEIGHT` + OPERATION / OPERATOR_ID / TOKEN / EXTENSIONS / REASON；**无 UNRELEASED_WEIGHT 列**（⚠ 语义陷阱见 §6.4）。
**service_route_rule_log**（L292-307）：`SERVICE_ID` + `NAME` + `STATUS` + OPERATION / OPERATOR_ID / TOKEN / EXTENSIONS / REASON；**无 COMPLETE 列**。

### 2.3 service_group_log（L110-136）— 结构异常表

| 字段 | 类型 | 可空 | 默认 | 说明 |
|---|---|---|---|---|
| ID | bigint(20) | N | AUTO | PK |
| GROUP_ID / PARENT_ID | bigint(20) | N | '0' | ⚠ DAO 从不写（§6.2） |
| NAME / STATUS | varchar(255) | N | '' | |
| WEIGHT | int(11) | Y | NULL | ⚠ DAO 从不写 |
| TYPE | varchar(255) | N | '' | ⚠ DAO 从不写 |
| OPERATION / OPERATOR_ID / TOKEN | varchar(255) | N | '' | |
| DESCRIPTION | varchar(1024) | Y | NULL | ⚠ DAO 从不写 |
| EXTENSIONS | varchar(2048) | N | '{}' | |
| SERVICE_ID / REGION_ID / ZONE_ID | varchar(255) | N | '' | 后置列 |
| APP_ID | varchar(255) | N | '' | |
| REASON | varchar(128) | Y | '' | **大写列名** |
| CREATE_TIME / DataChange_LastTime | | | | KEY |

索引（均非唯一）：`NAME_PARENT_TYPE(NAME,PARENT_ID)`、STATUS、TYPE、`OPERATOR(OPERATOR_ID)`、DataChange_LastTime。

## 3. 主键 / 唯一键 / 索引 / 外键

- **主键**：20 张表全为单列 `ID`。
- **唯一键**（10 个，均业务键组合）：instance(INSTANCE_ID,SERVICE_ID,REGION_ID,OPERATION)、server(SERVER_ID,REGION_ID,OPERATION)、service_group(SERVICE_ID,REGION_ID,ZONE_ID,NAME)、service_group_instance(GROUP_ID,INSTANCE_ID)、service_group_operation(GROUP_ID,OPERATION)、service_group_tag(GROUP_ID,TAG)、service_instance(SERVICE_ID,INSTANCE_ID)、service_route_rule(SERVICE_ID,NAME)、service_route_rule_group(ROUTE_RULE_ID,GROUP_ID)、service_zone(SERVICE_ID,REGION_ID,ZONE_ID,OPERATION)。
- **索引**：各表必带 `DataChange_LastTime`；其余按查询维度散列。
- **外键**：**零个**。`GROUP_ID` / `ROUTE_RULE_ID` 等为裸 bigint/varchar，无 DB 级引用完整性。

## 4. 软删与时间戳语义

- **软删列 `DELETED` 仅 2 张表**：`service_group`、`service_route_rule`。删除 = `update ... set deleted = true where id = ?`；upsert/insert 时重置 `deleted = false`；所有 select 恒带 `where deleted = false`（`GroupDao.java:76/152`、`RouteRuleDao.java:67/163`）。其余 18 表**硬删**。
- **时间戳列 DAO 层完全不写**——依赖 MySQL DDL 的 `DEFAULT CURRENT_TIMESTAMP` 与 `ON UPDATE CURRENT_TIMESTAMP`；RowMapper 只读。逻辑删除触发 `DataChange_LastTime` 自动更新，这是管理面缓存增量刷新的时间依据。
- **无乐观锁、无版本列**；并发写靠唯一键 + `ON DUPLICATE KEY UPDATE` / `insert ignore`。

## 5. 日志双写关系与 COMPLETE 语义

**映射与写入点**（业务写与 log 写为两步，**非全部同事务**）：

| 业务表 | 日志表 | 双写代码 |
|---|---|---|
| instance / server | instance_log / server_log | `ManagementRepository.java:183-208` |
| service_group | service_group_log | `BusinessDao.java:217/223/229` |
| service_group_instance | service_group_instance_log | `BusinessDao.java:281/288/294` |
| service_group_operation | service_group_operation_log | `BusinessDao.java:200-210` |
| service_instance | service_instance_log | `BusinessDao.java:301/309/317` |
| service_route_rule | service_route_rule_log | `BusinessDao.java:236/242/248` |
| service_route_rule_group | service_route_rule_group_log | `BusinessDao.java:255/262/268/274` |
| service_zone | service_zone_log | `ZoneRepository.java:102-111` |

**快照语义**：`insert` / `update` 记写后值；`delete` 先查旧值再写 log——**单快照，非前后 diff**。

**⚠ COMPLETE 列语义陷阱（DDL 注释与代码相反）**：DDL 注释写「true indicates that operation is **not** complete」，代码事实相反——`complete = true` = 操作**已完成**（业务行被删除）；`complete = false` = 操作**刚登记**（业务行存在）。证据：`ManagementRepository.java:185`（insertServer → false）、`:190`（deleteServer → true）；`BusinessDao.java:201-202` / `:209-210`。有 COMPLETE 列的表：instance_log / server_log / service_group_operation_log / service_zone_log；其余 6 张日志表无该列。

**事务性**：`BusinessDao` 的 `createServiceRouteRules` / `activateServiceRouteRules` / `operationGroupOperation` / `updateGroupInstance` 标了 `@Transactional`；`insertGroups` / `deleteGroups` / `insertOrUpdateGroups`（L213-230）**未标注**；instance / server / zone 路径**无事务**——业务行与 log 行存在写一半的窗口。

## 6. DDL 与 DAO SQL 不一致清单

1. **service_zone_log.REASON 从不落库**：DDL 有该列（:330），模型有值，但 `ZoneOperationLogDao.insert`（:116）与 `query` select（:69）**均不含 reason**——用户传的 reason 静默丢弃。
2. **service_group_log 五列从不落库**：GROUP_ID / PARENT_ID / WEIGHT / TYPE / DESCRIPTION（DDL :112-120），`GroupLogDao.insert`（:126）只写 11 列，恒为默认值 / NULL。
3. **组日志无法按 group_id 过滤**：`GroupLogDao.select`（:49-55）过滤集为 name/service_id/region_id/zone_id/app_id/operation/operator_id，**不含 group_id**（因不落库）。
4. **service_route_rule_group_log.WEIGHT 实为 unreleasedWeight**：`RouteRuleGroupLogModel` 构造把 `getUnreleasedWeight()` 存入父类 weight 字段，`RouteRuleGroupLogDao.insert`（:134-138）再绑到 weight 列——**列名与语义不符**。
5. **RouteRuleLog.complete 死字段**：模型有（`RouteRuleLog.java:16`），但 DDL 无 COMPLETE 列，insert（:120）不写。
6. **service_group.type 恒为默认 'physical'**：DDL 有列，`GroupDao` 各 insert 列清单不含 type，无法写入。
7. **service_route_rule_log 索引顺序反了**：`(NAME,SERVICE_ID)`（:305）与业务表 UNIQUE `(SERVICE_ID,NAME)`（:254）相反——select 按 service_id 过滤无法用索引前导列。
8. **service_group_log 无 GROUP_ID 索引**：与「按 group_id 查组日志」诉求不符。
9. **ServiceInstanceLogModel.description 存在但不可持久化**：DDL 与 insert 均无该列。
10. **命名 typo 原样存在**：唯一键 `GROUP_OPEATION`（:145/161）——复刻须保留，否则迁移脚本不匹配。

## 7. MySQL 与 SQLite 差异

**关键事实：生产代码无任何 SQLite 建表逻辑。** `DataConfig` 只按 driver/url 判定 `isMySQL()` 并选 DAO 实现，**不含 CREATE TABLE**；`ManagementInitializer.init()` 只做 `DataConfig.init()` + 缓存初始化。唯一建表代码在测试（`TestDatabaseInitializer.java:45-75` 执行 `artemis-test/src/test/resources/schema.sql`）。故 `SQLITE_SETUP.md:78`「首次启动时会自动创建表结构」为**不实宣称**（推翻前的表述，见 §8）。

| 维度 | MySQL | SQLite（测试 schema） |
|---|---|---|
| 类型映射 | bigint(20)/varchar(n)/datetime/timestamp/tinyint(1)/int(11) | INTEGER/TEXT/DATETIME/INTEGER |
| 主键 | `bigint AUTO_INCREMENT` + 单独 PK | `INTEGER PRIMARY KEY AUTOINCREMENT` |
| 文本默认 | `NOT NULL DEFAULT ''` | 多为 `NOT NULL`（无 DEFAULT） |
| 时间戳自动更新 | `ON UPDATE CURRENT_TIMESTAMP` | **无 ON UPDATE**（`DataChange_LastTime` 永不自增） |
| 普通索引 | 各业务/时间列均有 KEY | 仅关键唯一索引；`DataChange_LastTime` 无索引 |
| **唯一索引缺口** | service_instance 与 service_route_rule 有唯一键 | **两处均无**——Generic 分支 check-then-insert 失去唯一兜底，可重复插入 |
| service_group_log 列 | 含 GROUP_ID 等 5 列 | 缺这 5 列 |
| service_instance_log | 无 DESCRIPTION | **多出** description 列（未用） |
| service_group_instance_log | 无 EXTENSIONS | **多出** EXTENSIONS |
| service_zone_log | 有 REASON | 缺 REASON |
| 列名大小写 | 大写为主 | 全小写 |
| upsert 语法 | `ON DUPLICATE KEY UPDATE` / `INSERT IGNORE` | check-then-insert/update |

**upsert 行为差异**：MySQL 分支用 `insert ignore` / `on duplicate key update`；Generic 分支改 SELECT-then-INSERT/UPDATE。特别地 `RouteRuleGroupDao.GenericRouteRuleGroupDao` 的 upsert 执行 `set unreleased_weight=?, weight=NULL`（:494）——**两段式权重发布在 SQLite 下被破坏**，仅 MySQL 分支语义完整（与 [traffic-governance-logic](traffic-governance-logic.md) §7.1 一致）。

## 8. 对既有文档的勘误

| # | 位置 | 原表述 | 实际 |
|---|---|---|---|
| 1 | [features.md](../features.md) §3.8 | 「group 域 16 个 DAO」 | `group/dao/` 实为 **15 个**（含 BusinessDao）；加 dao 域 4 + zone 域 2 = **21 个 DAO 类**。已修正 |
| 2 | [features.md](../features.md) §3.8、[arch.md](../arch.md) | 未点破 SQLite 建表漂移 | `SQLITE_SETUP.md:78`「首次启动自动建表」**无代码支撑**（生产无建表逻辑，仅测试建表） |

## 9. 复刻完备性自检（本制品）

- 表数：20（10 业务 + 10 日志）✓ 与 DDL 逐表核对一致
- 每表字段级定义 ✓（含类型 / 可空 / 默认 / 键）
- 主键 / 唯一键 / 索引 / 外键全量 ✓
- 软删（2 表）与时间戳语义 ✓
- 日志双写映射（10 对）与 COMPLETE 语义 ✓
- MySQL↔SQLite 差异 ✓
- DDL↔DAO 不一致清单（10 条）✓
