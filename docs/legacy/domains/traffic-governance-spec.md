# 流量治理 · 功能规格

状态: 草案  日期: 2026-10-08

> 调研对象：原仓库 `~/Projects/mydotey/artemis`（version 2.0.2，git HEAD 9727bb5）。
> 定位：以需求语言规格化原产品「流量治理」域（分组 / 路由规则 / 两段式权重 / canary / 逻辑实例）行为，作为新产品需求设计输入。这是原产品区别于通用注册中心的差异化能力。摘除类运维操作见 [operations-audit-spec.md](operations-audit-spec.md)；发现链路见 [discovery-spec.md](discovery-spec.md)。
> 证据引用约定同 [registry-lease-spec.md](registry-lease-spec.md)；标 **⚠ legacy** 为原产品特有行为或包袱；缺陷见同域 logic.md §7。

## 1. 域定位

在「实例列表」之上提供**流量视图控制**：把实例组织为分组、按规则加权路由、支持灰度分批发布与一键 canary、托管不经注册中心的静态实例。治理数据以管理面 DB 为事实源，经各节点缓存刷新注入**发现结果**（`routeRules` / `logicInstances` 派生视图），实际选址由宿主 RPC 框架执行——**本产品只生产路由视图，不执行路由**。

角色：**运维 / 发布系统**（写治理配置）；**服务消费方**（收到带路由视图的发现结果）。

## 2. 概念与术语

| 概念 | 定义 |
|---|---|
| Group（分组） | 四级 key `serviceId/regionId/zoneId/name`；status active / inactive；可带 weight / tags / 绑定实例 |
| RouteRule（路由规则） | name + strategy（weighted-round-robin / close-by-visit）+ status；组织多个分组加权 |
| RouteRuleGroup | 规则-分组关系，**权重双列**：`weight`（已发布生效）+ `unreleasedWeight`（编辑暂存） |
| 两段式发布 | 编辑写 unreleased 列、显式 release 才拷贝到 weight 生效——分批灰度的机制基础 |
| canary | 保留规则 `canary-route-rule` 的专用分组路由（按 IP 绑定实例） |
| 逻辑实例（ServiceInstance） | DB 维护的静态实例（完整 ip/port/protocol/url/metadata），进发现结果 `logicInstances` |
| groupKey（五级） | 实例侧分组路径 `serviceId/regionId/zoneId/groupId/instanceId`（小写） |

## 3. 功能需求

### FR-TG-01 治理模型总则

**陈述**：治理数据应以共享 DB 为事实源，各节点定时全量拉取进内存缓存（默认 5s），经指纹 diff 触发变化服务的 reload 推送；发现时从缓存展开为派生视图注入发现结果。

**规则**：仅 status=active 的规则与组参与展开（装载期过滤，inactive 不进缓存）；规则下无可用组则整条不参与。

**证据**：原仓库 `artemis-management/.../group/GroupRepository.java:110-124,399-467`、`ManagementInitializer.java:44-45`。

### FR-TG-02 服务分组

**陈述**：系统应支持按四级 key 组织服务实例分组，分组可挂权重、标签、显式实例绑定。

**规则**：
- 分组成员两条路径等权并集（去重）：① 实例 `groupId` 挂组（五级 groupKey 前缀匹配，要求 serviceId/regionId/zoneId 与组一致且 `instance.groupId == 组名`）；② GroupInstance 显式绑定（按 instanceId 精确匹配，可绑定逻辑实例）。
- `groupId` 空或 "default" 归一为 `default`（无 default 组实体，未挂组实例仅体现在五级 key 第四段）。
- GroupTags 为纯元数据标注，随发现视图 `ServiceGroup.metadata` 下发，不参与路由计算。

**证据**：原仓库 `artemis-common/.../util/{ServiceGroupKeys,RouteRules,ServiceGroups}.java`、`GroupRepository.java:437-449`。

### FR-TG-03 路由规则与发现视图展开

**陈述**：发现结果应携带 `routeRules` 视图：每条 active 规则（name + strategy + 组列表，组带生效权重与成员）。

**规则**：
- 视图中 `routeId` 即规则名（⚠ 无独立 ID 暴露）；组权重只取 **released** weight 列，经修正（null / 负 → 5，> 10000 → 10000）。
- `logicInstances` = 该服务 service_instance 表**全量注入**（无筛选、status 固定 up、不参与租约心跳）。
- ⚠ legacy：摘除过滤（ManagementDiscoveryFilter）只清 `instances` / `logicInstances`，**不清理路由组内已展开的成员**——被摘除实例可能仍经路由视图对消费方可见（见 operations-audit 域与 logic.md §7.2）。

**证据**：原仓库 `GroupRepository.java:422-467`、`GroupDiscoveryFilter.java:36-64`、`ManagementDiscoveryFilter.java:41-46`。

### FR-TG-04 两段式权重发布

**陈述**：权重变更应支持「编辑暂存 → 显式发布」两段式：编辑写 unreleased_weight 不影响线上；release 将其拷贝到 weight 生效。

**规则**：
- 编辑未 release 期间发现结果完全不受影响（缓存只读 released 列）。
- 发布生效链路：release 落库 → 本节点固定等待（sleep 2s）→ 各节点 ≤5s 刷新 → diff → reload 推送 → 消费方重拉。**端到端生效窗口约 5–7s，且写成功 ≠ 全网生效**（无确认，FR-RC-13）。
- ⚠ legacy：`publish`（反向：以 weight 为准发布并清 unreleased）与 `activate` 逻辑存在但**无 REST 暴露**（推测内部 console 专用）；⚠ SQLite/Generic 分支的 upsert 在写 unreleased 同时清空 weight——SQLite 模式下两段式语义被破坏（logic.md §7.1）。

**验收标准**：编辑权重 → 未 release 前消费方路由视图不变；release 后 ≤7s 全部消费方看到新权重。

**证据**：原仓库 `group/dao/RouteRuleGroupDao.java:122-138,249-272,312-337,493-494`、`GroupRepository.java:344-346`。

### FR-TG-05 一键 Canary

**陈述**：系统应提供单 API 完成灰度实例集合更新：自动 get-or-create 保留规则 `canary-route-rule`、canary 专属组（组名 = appId、虚拟 zone = "canary"）、规则-组绑定、按 IP 全量覆盖实例绑定。

**规则**：
- 幂等：重复调用以新 IP 集**全量覆盖**（不在集合的绑定删除；传空 = 清空 canary）；规则 get-or-create 可复活软删记录。
- canary 绑定权重不设已发布值（恒走默认修正 5），**永不需要 release**。
- ⚠ legacy：匹配约定 = `GroupInstance.instanceId` 按精确 instanceId 匹配——要求宿主把实例 instanceId 设为纯 IP；instanceId 为 `ip:port` 等形态时 canary 不生效。
- ⚠ legacy：四步（规则 / 组 / 绑定 / IP）无整体事务，中途失败留部分数据，靠幂等重调收敛。

**证据**：原仓库 `canary/CanaryServiceImpl.java`、`CanaryServices.java:16-22`、`group/dao/{RouteRuleDao,GroupDao,BusinessDao}.java`。

### FR-TG-06 逻辑实例（静态实例）

**陈述**：系统应支持维护不经注册中心的静态实例（完整连接信息 + metadata），全量注入该服务发现结果的 `logicInstances`。

**规则**：逻辑实例不参与租约 / 心跳 / 剔除（无健康语义，status 固定 up）；变化经 12 字段指纹 diff 触发该服务 reload 推送；metadata JSON 解析失败静默按空处理。可与 canary / 分组绑定参与路由。

**验收标准**：新增第三方系统静态实例后 ≤5s（缓存刷新）+ 推送延迟内，消费方发现结果含该实例。

**证据**：原仓库 `GroupRepository.java:145-150,469-494,540-567,613-630`；基线 §5.6。

### FR-TG-07 路由策略契约

**陈述**：本产品应只生产路由视图、不执行路由：strategy 字符串（weighted-round-robin / close-by-visit）随规则下发，选址由宿主 RPC 按 strategy + 实例 zoneId + 自身位置执行。

**规则**：
- 客户端 SDK 每次 getService **本地重算**路由视图：剔除空组、再修正权重、保留 canary 组已展开成员；发现视图无 `default-route-rule` 时**客户端合成**一条（默认组 key 常量、默认权重 5、成员 = 全部注册实例不分组）——「未配置路由 = 全实例均等」语义由客户端保证。
- 就近访问：服务端不注入任何就近标记；消费方可用的位置信息仅 Instance.regionId / zoneId 与 groupKey 内嵌的 region/zone 段。
- WS 增量推送**不更新** routeRules / logicInstances，两者仅 reload 全量刷新。

**⚠ legacy**：`isLocalZone` 判定工具为死代码；保留规则名（default / canary）无服务端保护，可被任意 CRUD——default 语义靠客户端合成兜底（删除后消费方视图仍见合成版），canary 靠 get-or-create 幂等。

**证据**：原仓库 `artemis-client/.../discovery/ServiceContext.java:44-48`、`artemis-common/.../util/RouteRules.java:23-98`、`ServiceGroups.java:50-64`。

### FR-TG-08 生效链路（指纹 diff）

**陈述**：治理缓存刷新应以指纹比对检测变化：静态实例指纹（12 字段拼接）与路由指纹（`routeId/strategy` + 每组 `routeId/groupKey/weight(+成员)`），变化服务触发 reload 伪实例推送。

**证据**：原仓库 `GroupRepository.java:365-394,594-630`。

### FR-TG-09 ⚠ 灰度元数据路由（预留未实现）

**陈述**：`DiscoveryConfig.discoveryData` 的 `appid` / `subenv` key 为**纯协议预留**：服务端与客户端均无任何消费点（上送后零读取、零筛选）——不应作为既有能力继承；新产品如需按消费方元数据路由，属全新设计。

**证据**：原仓库 `util/DiscoveryConfigs.java`（全仓库 main 零调用）、`GroupRepository.java:130-132`（regionId 参数被忽略）；两路独立补证一致。（基线 §2.5 该行已确认与代码不符，待勘误）

### FR-TG-10 管理写约束

**陈述**：治理写操作应满足：本节点 UP 才受理（checkCurrentNode）、写后固定等待对端刷新（waitForPeerSync）、全量双写审计 log 表（操作时实体快照 + 操作者上下文）。

**规则**：create-route-rule 强校验必填（serviceId 一致性、region/zone/name/appId/status 非空、weight 非 null——范围靠 fixWeight 兜底）；operation 上下文四元组非空校验，**token 仅查非空不验值**（⚠ 全域无鉴权）。

**证据**：原仓库 `group/dao/BusinessDao.java:213-350`、`GroupServiceImpl.java:48-110`。

## 4. 与其他域的接口

| 域 | 关系 |
|---|---|
| discovery | 展开结果注入发现响应；元数据变化合成 RELOAD；全量缓存（GENERIC）同样过过滤器链 |
| operations-audit | 组级摘除共用 SearchTree；摘除过滤与本域展开的顺序（Group 先、Management 后） |
| replication-cluster | 生效链路依赖缓存刷新管线（FR-RC-13） |
| registry-lease | 展开消费注册表实例；逻辑实例不进租约 |

## 5. 配置项总表（本域）

| 配置键 | 默认 | 关联 FR |
|---|---|---|
| `artemis.management.group.data.cache-refresher.run-interval` | 5000ms（10ms–60s） | FR-TG-01/08 |
| `artemis.management.db-sync.wait-time` | 2000ms（0–60s） | FR-TG-04 |
| `artemis.service.management.group`（限流） | 30 QPS（1–1000） | FR-TG-10 |
| 权重常量 | MIN 0 / MAX 10000 / DEFAULT 5 | FR-TG-03 |
| 保留规则名 | `default-route-rule` / `canary-route-rule`（大小写不敏感） | FR-TG-05/07 |

## 6. 完整性对照

- 端点：`/api/management/group/` 32 个（route-rule 6 / route-rule-group 6 / group 5 / group-tag 5 / group-operation 4 / group-instance 3 / service-instance 3）+ `/api/management/canary/update-canary-ips.json` 全覆盖 ✓
- 数据模型：Group / RouteRule / RouteRuleGroup（双列权重）/ GroupInstance / ServiceInstance / GroupOperations / GroupTags ✓
- 未入本域：组级摘除判定细节（operations-audit）、审计查询（operations-audit）、DB/DAO 层（features §3.8）
