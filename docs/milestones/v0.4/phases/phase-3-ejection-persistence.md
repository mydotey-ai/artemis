# Phase 3：四级摘除持久化与审计

版本: 1.3    更新时间: 2026-10-25

## 目标

摘除从内存态升级为 DB 持久化（四级级联 instance → server → zone → group + 操作记录即状态 + 审计表）；跨 management 重启经既有规则流重下发恢复摘除态（v0.4 roadmap 验收要点）。

## 范围

做什么（据 plan phase-3 说明）：

- 摘除记录表 + 审计表（V3 migration，双方言）；摘除/恢复/改密等治理操作进审计（phase-2 改密审计在此补齐）。
- 四级级联摘除（instance → server → zone → group）+ **操作记录即状态**（下线 = 可叠加、带原因、可审计的记录；恢复 = 反向记录）——继承治理语义（架构 §5.2）。
- 跨重启恢复：DB 态启动加载既有摘除记录 → 经既有摘除规则流（v0.3，EjectionRuleDispatcher）对订阅中的 discovery 重下发快照。
- server/zone/group 取值来源（实例 metadata 键）与级联展开语义由 ③ 对照架构 §5.2 / [ADR-013](../../../decisions/adr-013-group-as-instance-metadata.md) 定稿。

不做什么：

- 规则版本 + ack 生效水位（phase-5；本 phase 沿用 v0.3 快照 + 增量规则流形态）。
- 逻辑实例（phase-4）、console 操作面（phase-6）。
- discovery 侧 filter 链变更（既有摘除过滤照常消费）。

## 涉及模块

| 模块 | 变更 |
|---|---|
| proto/management/v1 | `EjectionRule` / `EjectionRecovered` 扩展 scope 级字段（wire 兼容追加）+ `EjectionScope` 枚举 |
| artemis-common | 新增 `governance` 包：`EjectionScope` 枚举 + metadata 键常量（management/discovery 两侧共享，client 不依赖 common 无泄漏） |
| artemis-management-core | domain：`EjectionTarget` / `EjectionRecord` 重构（action/operator/sequence）/ `EjectionRecords` 纯函数 / `AuditLog` + `AuditLogStore` / `EjectionStore` 端口重构；application：`ManagementService` 四级扩展 + 审计写入；infrastructure：`InMemoryEjectionStore` 重构 + `InMemoryAuditLogStore` + dispatcher 事件适配 |
| artemis-management-server | `V3__ejection.sql` 双方言；MyBatis 实现（Entity/Mapper/StoreImpl ×2）；`AdminController` 统一 ejection 端点 + 审计查询端点；`AccountController` 改密进审计；`HttpBasicAuthFilter` 透出认证用户名；装配 |
| artemis-discovery-core | `EjectionRule` / `EjectionRules` / `EjectionFilter` / `EjectionRuleListener` / `EjectionRuleSynchronizer` 四级判定与扇出扩展 |
| artemis-discovery-server | `GrpcEjectionRuleSource` / watch / `DiscoveryProtoMapper` 适配新契约字段 |

## 验收方式

对应测试文档：[phase-3-ejection-persistence-test.md](phase-3-ejection-persistence-test.md)（③ 落盘）

## 产品设计（③ 补）

### 1. 四级目标模型与 metadata 键约定（plan 待定项定稿）

四级 scope 及其摘除目标的取值来源（继承原产品物理机/机房/组语义，载体改实例 metadata，ADR-013 延伸）：

| scope | 摘除目标 | 取值来源（实例 metadata 键） | 生效范围 |
|---|---|---|---|
| INSTANCE | 单实例 | `(namespace, service, instanceId)` 定位 | 该实例 |
| SERVER | 物理机 | `metadata["server"]`（如宿主 IP） | **全局**（所有 namespace/service 中该 metadata 命中的实例） |
| ZONE | 机房 | `metadata["zone"]` | 全局 |
| GROUP | 实例组 | `metadata["group"]`（ADR-013 既有键） | 全局 |

- 键名 `server` / `zone` / `group` 为治理约定（common 常量 + 产品文档登记）；实例不带该键 = 不参与对应级摘除。
- selector 级（SERVER/ZONE/GROUP）**不绑 service/namespace**：物理机/机房故障不分 namespace，忠实继承原产品全局语义。
- **级联展开 = 规则下发 selector、discovery 实时按 metadata 匹配**（继承原产品 `SearchTree` 按 groupKey 级联匹配的执行点语义），不做 management 侧展开为实例清单——展开快照对新注册实例失效（语义有损）且需周期重算任务；selector 实时匹配天然覆盖新实例。

```java
// artemis-common（org.mydotey.ai.artemis.common.governance）
public enum EjectionScope {
    INSTANCE(null), SERVER("server"), ZONE("zone"), GROUP("group");
    private final String metadataKey;   // INSTANCE 无
}

// management-core domain
public record EjectionTarget(EjectionScope scope, ServiceKey service, String instanceId, String value) {
    // INSTANCE：service + instanceId 必填；SERVER/ZONE/GROUP：value 必填（service/instanceId 为 null）
    public static EjectionTarget instance(ServiceKey service, String instanceId);
    public static EjectionTarget selector(EjectionScope scope, String value);
}
```

### 2. 操作记录即状态（叠加 + 反向记录，架构 §5.2 继承语义落地）

```java
public record EjectionRecord(
        EjectionTarget target, EjectionAction action, String reason,
        String operator, Instant timestamp, long sequence) {}
public enum EjectionAction { EJECT, RECOVER }
```

- **下线 = 追加 EJECT 记录**（可叠加：同目标多条 EJECT 各带 reason/operator，全部留痕）。
- **恢复 = 追加 RECOVER 记录（反向记录，不删历史）**——原产品「恢复即删记录」的改进：审计完整性优先。
- **对消与生效判定**（纯函数，per target）：`Σ(EJECT) − Σ(RECOVER) > 0` 为摘除中；RECOVER 多余时 no-op（幂等，返回 false，不追加）。
- 记录序：`sequence` 单调递增（DB 自增主键；内存态 AtomicLong），全记录按 sequence 升序即操作时间线。

```java
// domain 纯函数（内存/DB 两态共享语义，单测一处覆盖）
public final class EjectionRecords {
    public static List<EjectionRecord> activeOf(List<EjectionRecord> all);        // 未对消记录，确定性排序
    public static Map<ServiceKey, Long> activeInstanceCountByService(...);         // INSTANCE 级计数（服务列表）
    public static EjectionRecord match(List<EjectionRecord> active, Instance instance); // 四级级联命中判定
    // match 优先序：INSTANCE > SERVER > ZONE > GROUP（命中标注取最深一级首条）
}
```

`match` 判定即四级级联：instance 级按键等值，selector 级按 `metadata[scope.metadataKey()] == value`。

### 3. 存储端口与双轨

store 端口最小化（生效计算归 domain 纯函数，store 只存取）：

```java
public interface EjectionStore {
    void append(EjectionRecord record);   // DB/内存两态
    List<EjectionRecord> findAll();       // 按 sequence 升序（DB 态 = SELECT ORDER BY id）
}

public record AuditLog(long sequence, String operation, String target, String operator,
        String result, String detail, Instant timestamp) {}
public interface AuditLogStore {
    void append(AuditLog log);
    List<AuditLog> find(String operation, int limit);   // 时间倒序（最新在前），operation 可选过滤
}
```

- **DB 态**：V3 migration（双方言各自表达），MyBatis 实现（模式照 phase-2 `AccountStoreImpl`：Entity/Mapper + `DataAccessException` → domain 异常翻译）。
- **内存态**：`InMemoryEjectionStore` 同步升级为四级模型（List 追加 + AtomicLong sequence），`InMemoryAuditLogStore` 新增；行为与 DB 态语义一致，仅重启丢失（期 1 既有语义）。
- DB 态不设启动加载任务：规则流快照现读 `EjectionRecords.activeOf(store.findAll())`，discovery 重连收快照即恢复——跨重启恢复零新增组件。

`artemis_ejection_record`（时间戳 Java 侧 UTC 写入，双方言时间类型同 phase-2 约定）：

```sql
CREATE TABLE artemis_ejection_record (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,   -- mysql: BIGINT AUTO_INCREMENT
    scope        VARCHAR(16)  NOT NULL,     -- INSTANCE/SERVER/ZONE/GROUP
    namespace    VARCHAR(255) NULL,         -- INSTANCE 级
    service      VARCHAR(255) NULL,         -- INSTANCE 级
    instance_id  VARCHAR(512) NULL,         -- INSTANCE 级
    target_value VARCHAR(512) NULL,         -- SERVER/ZONE/GROUP 级
    action       VARCHAR(8)   NOT NULL,     -- EJECT/RECOVER
    reason       VARCHAR(1024) NULL,
    operator     VARCHAR(64)  NOT NULL,
    created_at   <时间类型>   NOT NULL
);
CREATE INDEX idx_ejection_scope_value ON artemis_ejection_record (scope, target_value);
```

`artemis_audit_log`：

```sql
CREATE TABLE artemis_audit_log (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    operation  VARCHAR(64)  NOT NULL,       -- EJECT/RECOVER/CHANGE_PASSWORD/...
    target     VARCHAR(1024) NOT NULL,      -- 资源描述串（如 "instance:ns/svc/i1"、"server:10.1.1.1"）
    operator   VARCHAR(64)  NOT NULL,
    result     VARCHAR(16)  NOT NULL,       -- SUCCESS/FAILURE
    detail     VARCHAR(2048) NULL,          -- reason/失败信息等
    created_at <时间类型>   NOT NULL
);
```

### 4. 审计语义（摘除记录表与审计表的分工）

- **摘除/恢复操作**：`artemis_ejection_record` 本身即完整操作审计（action/reason/operator/timestamp 全留痕，操作记录即状态）；审计表**追加一行摘要**（operation=EJECT/RECOVER、target、result=SUCCESS、detail=reason），满足架构 §5.3「全部治理操作进审计表」的统一审计查询入口。
- **一致性语义**（评审决策点）：audit 追加失败**不阻塞摘除操作**（ERROR 日志）——摘除状态源唯一在 record 表，audit 摘要丢失只影响统一审计视图的完整性，不产生状态错误；避免跨表事务编排。
  <!-- ponytail: 摘要尽力而为；若合规要求强一致审计，升级为 server 层事务模板包裹两表写入 -->
- **改密审计**（phase-2 补齐）：`AccountController` 改密成功后 append（operation=CHANGE_PASSWORD）；失败路径不记（错误响应即反馈，phase-6 console 审计流交付时按需扩 FAILURE 语义）。
- operator 取认证用户名：`HttpBasicAuthFilter` 验证通过后 `request.setAttribute("artemis.auth.user", username)`，Controller 经 `@RequestAttribute` 取（内存态配置凭证用户名同样透出）。

### 5. HTTP API（资源导向统一 ejection 资源，替换 v0.1 实例级端点）

v0.1 两个实例级端点**删除**，统一为 ejection 资源（四级一套模型，避免原产品 4 组前缀 57 API 形态；v0.4 未 GA 无兼容包袱，`admin-api.md` 同步改写）：

```text
POST /admin/v1/ejections            # 摘除（追加 EJECT）
{"scope": "INSTANCE", "namespace": "ns", "service": "svc", "instanceId": "i1", "value": null, "reason": "..."}
{"scope": "SERVER", "value": "10.1.1.1", "reason": "host maintenance"}   # selector 级只需 value
→ 200 {"scope", "namespace", "service", "instanceId", "value", "action", "reason", "operator", "timestamp", "sequence"}
→ 400（scope 非法 / INSTANCE 缺 service/instanceId / selector 级缺 value / reason > 1024）

POST /admin/v1/ejections/recover    # 恢复（追加 RECOVER，对消）
{"scope": "INSTANCE", "namespace": "ns", "service": "svc", "instanceId": "i1"}
→ 204 No Content（无可对消记录同样 204，幂等）

GET /admin/v1/ejections?activeOnly=true&scope=SERVER&limit=100
→ 200 {"records": [...]}            # 时间倒序（最新在前），limit 默认 100 上界 1000；activeOnly=true 只返回未对消集（review #5）

GET /admin/v1/audit-logs?operation=&limit=100
→ 200 {"logs": [...]}               # 时间倒序，limit 默认 100 上界 1000
```

- 详情视图 `includeEjected=true` 时逐实例四级级联标注：`ejected=true` + 命中记录的 reason/timestamp（`match` 优先序 INSTANCE > SERVER > ZONE > GROUP）。
- 服务列表 `ejectedCount` 语义维持 INSTANCE 级有效计数（selector 级不折算——列表无实例明细，折算需逐服务拉全量实例；语义在 admin-api.md 明示，精确视图走详情）。
  <!-- ponytail: 列表不折算 selector 命中；console（phase-6）若需精确值按详情口径取 -->

### 6. 规则流契约扩展（wire 兼容追加）

```proto
enum EjectionScope {
  EJECTION_SCOPE_INSTANCE = 0;
  EJECTION_SCOPE_SERVER = 1;
  EJECTION_SCOPE_ZONE = 2;
  EJECTION_SCOPE_GROUP = 3;
}

message EjectionRule {
  artemis.common.v1.ServiceKey service = 1;   // INSTANCE 级必填
  string instance_id = 2;                     // INSTANCE 级必填
  string reason = 3;
  EjectionScope scope = 4;                    // 缺省 INSTANCE（proto3 默认值，旧事件兼容）
  string target_value = 5;                    // SERVER/ZONE/GROUP 级：metadata 值
}

message EjectionRecovered {
  artemis.common.v1.ServiceKey service = 1;
  string instance_id = 2;
  EjectionScope scope = 3;
  string target_value = 4;
}
```

- 快照 = **有效规则集**（`activeOf` 结果，含四级）；域内 `EjectionRuleSubscriber.onSnapshot(List<EjectionRecord>)` 不变形，record 扩展承载 scope。
- 增量推送条件（对消语义约束）：**仅目标生效态翻转时推**——EJECT 使目标 inactive→active 推 `onEjected`（叠加第二条 EJECT、目标本已 active，不推）；RECOVER 使 active→inactive 推 `onRecovered`（对消后仍 active，不推）。否则按 record 直推会使 discovery 误删仍生效的规则。
- 判定归属：`ManagementServiceImpl` append 前后各算一次目标 active 态（`EjectionRecords` 纯函数，管理操作级频率遍历可忽略）。

### 7. discovery 侧四级判定与扇出

- `EjectionRule`（discovery domain）扩展为 `(scope, service, instanceId, value, reason)`；`EjectionRules` 重构为两级结构：

```java
public final class EjectionRules {
    // instance 级：Map<ServiceKey, Set<String>>（现状结构不变）
    // selector 级：EnumMap<EjectionScope, Set<String>>（三集合，各自值集）
    public boolean isEjected(ServiceKey service, String instanceId, Map<String, String> metadata);
    public EjectionRules with(EjectionRule rule) / without(EjectionTarget target);   // 幂等语义不变
}
```

- `EjectionFilter.isVisible(service, instance)` 改带 metadata 判定（既有 filter 链签名不动，`Instance` 自带 metadata）。
- `EjectionRuleSynchronizer.fanoutDiff` 受影响服务集扩展：instance 级按规则 service 键枚举（现状）；**selector 值集发生变化时并入投影全部服务**（`clusterIds × 全 service` 逐实例新旧可见性差判定——selector 摘除为低频运维操作，全投影扫描可忽略）。
  <!-- ponytail: selector 扇出全投影扫描；规则规模 × 服务数增长后按 metadata 反向索引 -->

### 8. 事务与配置

- 零新增自定义配置键（phase-1/2 纪律延续：双轨判据 = DataSource bean 存在性；migration/审计无新配置）。
- 零跨表事务（§4 决策）；MyBatis store 实现不带 `@Repository`，DB 态以 mapper 可得性条件装配（phase-2 ObjectProvider 模式）。
- **management 单节点假设**（roadmap「management 保持单节点」）：对消「读-判-写」由 JVM 内 `ejectionLock` 串行（含规则流推送，防增量乱序——review #1/#3）；跨副本共享 DB 需 DB 级并发控制，多副本部署形态出现时再引入。
  <!-- ponytail: JVM 锁足够服务单节点；多副本时改 per-target DB 乐观锁或唯一约束对消 -->
- **读路径全表遍历**：服务列表/详情每次 `findAll()` 全量加载 + `activeOf` 纯函数计算（review #6）——管理操作级频率与千级记录量下可忽略；记录到十万级（console phase-6 之后）改 SQL 聚合或 active 集缓存。
- 组合 classpath 防护沿用三主类 `excludeName` 既有边界（phase-1 定案），本 phase 不新增 introspection 面（新 store 方法签名零引用 Spring/MyBatis 类型）。

### 9. review 定案登记（2026-10-25 通过）

- **§4 审计分工定稿**（record 表即摘除操作审计 + audit 表摘要行、无跨表事务）：原始文档 [架构](../../../arch/artemis-next-architecture.md) §5.3「全部治理操作进审计表」已同步精化（1.41）。
- §1 metadata 键 `server`/`zone`/`group`：ADR-013 后果节「key 命名由治理文档约定」既有授权，产品文档登记随 ⑩。
- §2/§3/§5–§8 机制落点不变更已记载设计（架构 §5.2 继承语义 / phase-1 双轨判据 / phase-2 MyBatis 模式 / 规则流 v0.3 契约形态 wire 兼容追加），无原始文档修订。

## 实现结果（⑦ 补）

实施要点：

- 生效集语义修正（实现期定稿）：`EjectionRecords.activeOf` per target FIFO 对消后保留**一条代表记录**（最早未对消 EJECT）——叠加多条同目标在规则流快照与 `activeOnly` 列表中只占一项（生效目标集语义）；全量列表（`activeOnly=false`）保留全部操作时间线。
- 增量推送条件（实现期修正）：EJECT 使目标 inactive→active 才推（叠加不推）；RECOVER 完全对消（active→inactive）才推（部分对消不推）——否则 discovery 会误删仍生效规则。推送在 `ejectionLock` 内与「读-判-写」成对串行（review #1）。
- discovery `EjectionRules` selector 空集陷阱：`Map.copyOf` / `new EnumMap<>(Map)` 均拒绝空 map——selector 恢复到空集时从类型构造起（⑤ 首跑发现）。
- `fanoutDiff` 统一为 per-instance 新旧可见性差判定（instance 级与 selector 级同路径）；selector 值集变化时受影响服务并入投影全部服务。
- 装配双轨沿用 phase-2 ObjectProvider 模式（mapper 可得性判据），未用 `@ConditionalOnMissingBean`（同配置类内评估顺序脆弱）。
- HttpBasicAuthFilter 验证通过后 `request.setAttribute("artemis.auth.user", username)` 透出 operator；认证关闭（eval）时 Controller 回落 "anonymous"。

### code-review 修复（⑦，8 findings）

1. **[高] 规则流增量乱序竞态**：推送原在 `ejectionLock` 外——并发同目标操作可把 onEjected/onRecovered 乱序送达 discovery 致规则态分歧（fail-open 会保持错误态直至重连）——推送移入锁内与读-判-写成对。
2. **[中] 信任边界长度校验缺失**：namespace/service ≤255、instanceId/value ≤512 契约层校验（超长值原会在 DB 态溢出列宽 503、内存态 200 的双态分歧）。
3. **[中] 跨副本对消竞态**：不修码——management 单节点（roadmap 既有假设），`ejectionLock` JVM 内串行足够；§8 登记多副本形态再引入 DB 级并发控制。
4. **[高→⑩] API 文档不同步**：admin-api.md / ejection-rule-stream-api.md 更新归 ⑩ 文档同步既定步骤执行。
5. **[中] 记录列表无上界**：`GET /admin/v1/ejections` 加 `limit`（默认 100 上界 1000）+ 时间倒序（全量表只追加增长）。
6. **[低] 读路径全表遍历**：不修码——管理操作级频率 + 千级记录量可忽略；§8 登记量级与升级路径。
7. **[低] 设计文档响应字段偏差**：§5 响应示例修正为实际字段（timestamp/action/sequence，无 ejectedAt）。
8. **[低] 死代码**：`EjectionFilter.isVisible` 三参便捷重载零调用方——删除。

## 文档同步（⑩ 填）

- [产品文档](../../../product/artemis.md)（1.27）：新增 v0.4 phase-3 交付节（四级目标与 metadata 键、操作记录即状态、V3 持久化与跨重启恢复、统一 ejection API、审计分工、规则流契约链接、工程形态）。
- [admin-api.md](../../../api/admin-api.md)（1.8）：§2 有效视图语义四级化改写；§4.3–4.6 统一 ejection 端点 + 记录/审计查询（替换 v0.1 实例级端点）；§7 补 400 场景与 503 `EJECTION_STORE_UNAVAILABLE`；§8 限制更新（code-review #4 清偿）。
- [ejection-rule-stream-api.md](../../../api/ejection-rule-stream-api.md)（1.1）：四级规则（scope + target_value wire 兼容扩展）、快照 = 有效规则集、增量仅翻转时产生、DB 态跨重启恢复、selector 扇出语义。
- [架构](../../../arch/artemis-next-architecture.md)（1.41）：③ review 定案时已同步（§5.3 审计表述精化）——⑩ 无增量。
- ADR / 索引 / CLAUDE.md / proto-contract.md：无过时项（无新 ADR 级决策；两份 phase 文档已在 phases/README.md 登记；management.v1 为治理面组随版本演进、无契约规范变更；模块结构与构建命令未变）。

## 更新历史

| 版本 | 日期 | 变更说明 |
| ------ | ------ | ------ |
| 1.3 | 2026-10-25 | 文档同步登记（产品文档 1.27 / admin-api 1.8 / ejection-rule-stream-api 1.1）；人工验证通过 |
| 1.2 | 2026-10-25 | 实现结果 + code-review 修复登记（8 findings：推送入锁/长度校验/列表 limit/文档字段/死代码，跨副本与全表读登记不修码）；§5/§8 按 review 修正 |
| 1.1 | 2026-10-25 | 产品设计定稿（待 review）：四级目标模型与 metadata 键约定、操作记录即状态（叠加 + 反向记录）、store 端口与 V3 双方言表、审计分工（摘要尽力而为）、统一 ejection API、规则流 wire 兼容扩展、discovery 四级判定与扇出 |
| 1.0 | 2026-10-25 | 开工骨架 |
