# Phase 1 测试：management DB 接入基座

版本: 1.1    更新时间: 2026-10-24

## 测试方案

覆盖面：双轨装配语义（未配置 DB 内存态 / 配置 DB 启用 migration）+ 双方言（MySQL/PG）migration 起停。本 phase 逻辑面薄（装配条件 + record），核心验证在服务级 IT；ORM/repository 双方言语义随范围调整推迟 phase-2（设计 §5），本 phase 不设对应用例。

形态与映射（既有形态，无扩展——dev-test.sh 不动）：

| 形态 | 模式 | 说明 |
|---|---|---|
| Spring context 装配测试（`*Test`，surefire） | unit | 内存态装配断言，不起真实服务端口 |
| Testcontainers 双方言 IT（`*IT`，failsafe） | integration | Testcontainers 起 MySQL/PG 真实容器 → `spring.datasource.url` 指向 → 起管理面上下文断言；**Docker 不可用环境 assumeTrue 跳过并打印提示**（本地无 Docker 可跑单测线，CI 有 Docker 全跑；Testcontainers 为技术选型 #21 预定用途，非新形态——仍为 failsafe `*IT` 既有映射） |

## 用例清单

| 用例 | 场景 | 形态 | 结果 |
|---|---|---|---|
| should_assemble_memory_persistence_when_db_not_configured | 不配置 `spring.datasource.*` 起 management 上下文：无 DataSource bean、`PersistenceInfo` = MEMORY | 单测（context） | 通过 |
| should_run_flyway_and_report_database_mode_on_mysql | Testcontainers MySQL + url 配置：V1 applied、`PersistenceInfo` = DATABASE + MySQL + 版本 1 | IT | 通过（真实容器 29s） |
| should_run_flyway_and_report_database_mode_on_postgresql | 同上，PostgreSQL | IT | 通过（真实容器 10s） |
| should_expose_persistence_status_when_http_api_called | 状态 API `/admin/v1/status/persistence` 返回 DB 态 JSON（受 Basic 认证保护） | IT（基类共享） | 通过 |
| should_skip_dialect_it_when_docker_unavailable | 无 Docker 环境：双方言 IT 整类跳过（`@Testcontainers(disabledWithoutDocker = true)`） | IT（守卫） | 通过（skip 路径首轮已实测） |

## 验证记录（⑨ 填）

自动化验证（工作流执行）：

| 命令 | 结果 |
|---|---|
| `java/scripts/dev-test.sh all`（⑤ 首跑终绿） | 全 reactor BUILD SUCCESS（11 min；双方言 IT 真实容器：MySQL 29s / PG 10s；DiscoveryEjectionFilterIT 组合 classpath 3/3） |
| `java/scripts/dev-test.sh all`（⑧ review 修复回归） | 全 reactor BUILD SUCCESS（11 min；DiscoveryClientIT 7/7 含上轮偶发用例） |
| `mvn -f java/pom.xml -pl :artemis-management-server -am package -DskipTests` + `unzip -l *-exec.jar \| grep BOOT-INF/lib` | 修复后 exec jar 含全部 8 个 DB jar（HikariCP/flyway×3/双驱动/spring-boot-jdbc/flyway 模块）——F1 部署缺陷实证修复 |

人工验证（用户执行）：通过（2026-10-25，用户确认）。

## 回归记录（⑧ 填，经 dev-test.sh）

| 轮次 | 模式 | 范围 | 结果 |
|---|---|---|---|
| ⑤ 首跑（多轮修复后终绿） | all（全量 verify） | 全 reactor | 通过（11 min，双方言 IT 真实容器执行、DiscoveryEjectionFilterIT 组合 classpath 回归 3/3） |
| ⑧ review 修复回归 | all（全量 verify） | 全 reactor | 通过（11 min；fix 后全绿——含 exec jar 打包复验：BOOT-INF/lib 含 8 个 DB jar） |

⑤ 实施期修复记录（均为设计假设修正，已回写 phase 设计文档「实现结果」）：
- SB 4.1 引 starter-jdbc 无 url 时 DataSource 自动配置不跳过而是启动失败 → 主类 `excludeName` 排除 + 条件化自建 HikariDataSource（Binder 保留 `spring.datasource.hikari.*` 配置面）。
- DB 依赖进组合 classpath 引爆无关服务的自动配置（registry fixture / discovery IT）→ 依赖组全 `optional` + fixture 显式 exclude + 配置类签名/泛型零 DB 组类型引用（方法体内惰性引用）。
- `ObjectProvider<DataSource>` 参数不被 Boot 数据库初始化依赖检测识别，persistenceInfo 构造先于 Flyway migrate → 方法体显式触发 `FlywayMigrationInitializer`。
- Testcontainers 2.0 模块重命名（`testcontainers-*`）与容器类移包（`org.testcontainers.{mysql,postgresql}`）、去泛型。

## 更新历史

| 版本 | 日期 | 变更说明 |
| ------ | ------ | ------ |
| 1.1 | 2026-10-24 | 测试设计定稿（待 review）：context 单测 1 + Testcontainers 双方言 IT 2 + Docker 守卫；dev-test.sh 无扩展 |
| 1.0 | 2026-10-24 | 测试文档骨架 |
