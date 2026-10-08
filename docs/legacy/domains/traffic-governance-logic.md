# 流量治理 · 业务逻辑蓝本

版本: 1.0    更新时间: 2026-10-08

> 调研对象：原仓库 `~/Projects/mydotey/artemis`（version 2.0.2，git HEAD 9727bb5）。
> 定位：分组路由 / 两段式发布 / canary / 逻辑实例的完整业务逻辑，作为新产品设计时逐单元评估与优化的蓝本。事实性；取舍依据引用基线 §5/§6，对照见 §8。阅读约定：L1–L7、D1–D4、F1–F4；证据引用约定同 [traffic-governance-spec.md](traffic-governance-spec.md)。

## 1. 逻辑总览

```text
管理面 DB(分组/路由/绑定/静态实例, 双写审计)
   │ 各节点 GroupRepository 缓存刷新(5s): 全量拉取 → 装载过滤(ACTIVE) → 指纹 diff
   ▼                                          变化 serviceId → RELOAD 伪实例 → 推送
内存缓存: routeRules multimap + serviceInstances + 组级摘除 SearchTree
   │ 每次 lookup/getService（过滤器链第 1 环：GroupDiscoveryFilter）
   ▼
发现结果 = 注册实例 + logicInstances(全量注入) + routeRules(展开视图)
   │ 客户端每次 getService 本地重算(剔空组/修权重/保 canary/合成 default)
   ▼
宿主 RPC 按 strategy + zone 执行实际选址（产品不执行路由）
```

| 逻辑单元 | 职责 | 关键代码（原仓库） |
|---|---|---|
| L1 治理数据装载 | 全量拉取、ACTIVE 过滤、缓存构建 | `artemis-management/.../group/GroupRepository.java:399-467` |
| L2 展开算法 | routeRules 视图 + logicInstances 注入 + canary 展开 | `GroupDiscoveryFilter.java:36-64`、`util/RouteRules.java` |
| L3 归属解析 | 挂组前缀匹配 + 绑定精确匹配，并集去重 | `RouteRules.java:73-98`、`ServiceGroupKeys.java` |
| L4 两段式发布 | weight / unreleased 双列 + release / publish | `group/dao/RouteRuleGroupDao.java` |
| L5 canary 流程 | 四步 get-or-create + IP 全量覆盖 | `canary/CanaryServiceImpl.java`、`CanaryServices.java` |
| L6 生效推送 | 指纹 diff → reload | `GroupRepository.java:365-394,594-630` |
| L7 客户端视图重算 | 剔空组 / 修权重 / canary union / default 合成 | `artemis-client/.../discovery/ServiceContext.java:44-48`、`util/RouteRules.java:26-71` |

## 2. 概念模型与不变量

- 双重身份的权重：`weight`（线上事实）与 `unreleasedWeight`（暂存意向）——**发现永远只读 weight**，两段式的正确性依赖这一点。
- 派生视图不变量：I1 发现结果中 routeRules 仅含 ACTIVE 规则 × ACTIVE 组（装载期过滤保证）；I2 组成员 = 挂组路径匹配 ∪ 显式绑定（等权，instanceId 去重）；I3 logicInstances 与注册表零交叉（静态，无租约）。

## 3. 状态机

**权重发布状态**（每 RouteRuleGroup）：

| 状态 | 含义 | 迁移 |
|---|---|---|
| 已发布（weight=W，unreleased 为空） | 线上=W | 编辑 → 暂存中 |
| 暂存中（weight=W，unreleased=U） | 线上仍 W | release → 已发布（W:=U）；再编辑 → 覆盖 U |
| 发布且暂存（weight=W，unreleased=U） | 灰度中间态 | release 生效 U |

⚠ SQLite/Generic 分支破坏该状态机（§7.1）。

**规则 / 组生命周期**：DB 行 + status（active/inactive）+ 部分表软删（route_rule / group 有 deleted 列；route_rule_group / group_instance 硬删）。

## 4. 决策规则

### D1 展开参与判定（装载期）

```text
规则参与:   status == ACTIVE
组参与:     status == ACTIVE
规则保留:   过滤后仍有可用组, 否则整条不进缓存
权重取值:   released weight → fixWeight(null/<0 → 5, >10000 → 10000)
```

证据：`GroupRepository.java:429-455`、`ServiceGroups.java:24-34`。

### D2 组成员解析（并集）

```text
成员 = { instanceIds 精确匹配: 注册实例 ∪ 逻辑实例 }
     ∪ { 五级 groupKey 前缀匹配 groupKey+"/": 要求前四级全等 且 instance.groupId == 组名 }
按 instanceId 去重（注册实例优先于同名逻辑实例）
```

canary 组 key 为 `serviceId/{region}/canary/{appId}`（虚拟 zone "canary"）——普通实例 zoneId ≠ canary，前缀路径不命中，**成员实际只来自 IP 显式绑定**。证据：`RouteRules.java:73-98,129-150`。

### D3 canary 展开决策

发现展开时取**第一条**名为 canary-route-rule 的规则（同名多条只展开第一条 ⚠）；对其每个组按 D2 解析成员写入视图组；非 canary 规则的组不带成员（消费方按 groupKey 自行圈定）。

### D4 客户端重算决策（每次 getService）

剔除空组 → 再 fixWeight → canary 组 union 保留服务端展开成员 → 视图无 default-route-rule（大小写不敏感）则合成默认规则（组 key 常量 `default-group-key`、权重 5、成员 = 全部注册实例不分组）。证据：`RouteRules.java:26-71`。

## 5. 核心流程

### F1 灰度发布全链路

编辑（写 unreleased）→（可反复修改、可比对）→ release（`weight := unreleased`）→ sleep 2s 返回 → 各节点 ≤5s 刷新装载 → 指纹 diff（路由指纹含每组 weight 与成员）→ 变化服务 RELOAD 推送 → 消费方批量 lookup 重拉 → 新 routeRules 进缓存 → 客户端重算 → 宿主按新权重选址。端到端 ≈ 5–7s。

### F2 canary 上线 / 调整

调 update-canary-ips(serviceId, appId, ips)：get-or-create 规则（名 canary-route-rule、active、weighted-round-robin）→ get-or-create 组（name=appId、zone=canary）→ get-or-create 绑定（unreleased=5，weight 恒空）→ **全量覆盖** IP 绑定（@Transactional；空集 = 清空）→ sleep 2s → 刷新 diff → reload。

### F3 逻辑实例变更

insert/delete service-instances（完整连接信息 + metadata JSON）→ 刷新 → 12 字段指纹 diff（regionId/zoneId/groupId/serviceId/instanceId/machineName/ip/port/protocol/url/healthCheckUrl/metadata）→ 变化服务 RELOAD → 消费方重拉后 logicInstances 更新（WS 增量不更新该字段）。

### F4 发现展开（每次 lookup）

GroupDiscoveryFilter：注入 logicInstances（全量）→ 构造 routeRules 视图（D1）→ canary 规则按 D2/D3 展开成员 →（下一环 ManagementDiscoveryFilter 剔除 down 实例，但不清路由组内成员 ⚠）。

## 6. 并发与时序

| 场景 | 机制 | 结果 |
|---|---|---|
| canary 展开写缓存共享对象 | `setInstances` 无 clone 直写 GroupRepository 缓存内 ServiceGroup | 并发 lookup 数据竞争（结果幂等但非线程安全，§7.5） |
| 编辑与发布并发 | 双列隔离 | 线上视图不受编辑影响 |
| 刷新与查询并发 | 缓存整体换新 + filter 读当次引用 | 查询见完整旧版或新版 |
| DB 双写（业务 + log） | DAO 层同事务 / 同调用 | 审计与业务一致（delete 先 select 旧值再删再记） |

参数：刷新 5s / sleep 2s / 生效窗口 5–7s / 权重域 0–10000 默认 5。

## 7. 边界与已知缺陷（事实清单）

1. **SQLite 分支破坏两段式**：Generic/SQLite upsert 写 unreleased_weight 时执行 `weight=NULL`——编辑即丢已发布权重（缓存读到 null → 修正为默认 5）；MySQL 分支保留 weight。两实现语义漂移。证据：`RouteRuleGroupDao.java:493-494` vs `:249-272`。（新发现）
2. **摘除过滤不作用于路由视图成员**：ManagementDiscoveryFilter 只清 instances / logicInstances，不清 routeRules[].groups[].instances——被摘除实例仍可经 canary / 路由组视图可见。证据：`ManagementDiscoveryFilter.java:41-46`。（新发现）
3. **保留名无服务端保护**：default / canary 规则可被任意 CRUD；default 语义靠客户端合成兜底（删除后视图仍有合成版，语义上不可消除）；canary 靠 get-or-create 复活。证据：`GroupServiceImpl.java:48-110`（无保留名校验）。（新发现）
4. **publish / activate 半成品**：逻辑完整（publish 以 weight 为准发布并清暂存；activate 一步激活 + 未选失活 + release）但无 REST 暴露——推测内部 console 专用，开源形态不可达。证据：`BusinessDao.java:71-119`、`RouteRuleGroupDao.java:312-337`（grep 无调用方）。（新发现）
5. **canary 展开并发写共享缓存**：§6 第一行。证据：`GroupDiscoveryFilter.java:54-56`。（新发现）
6. **过滤器链 fail-open**：GroupDiscoveryFilter 抛异常仅记日志继续——管理面故障时发现降级为基础视图（无路由），静默降级。证据：`DiscoveryServiceImpl.java:236-242`。（新发现）
7. **同名 canary 规则只展开第一条**（break）；其余同名规则的组不带成员。证据：`GroupDiscoveryFilter.java:49-59`。（新发现）
8. **canary 绑定查询按 groupId 单键**：组已绑定其他规则时返回值有歧义（返回值实际被弃用，无实害）。证据：`RouteRuleGroupDao.java:86-95`。
9. **canary 匹配强依赖 instanceId = 纯 IP 约定**：SDK 不生成 / 校验 instanceId，`ip:port` 形态静默不生效。
10. **软删仅部分表**：route_rule / group 软删（可复活，canary get-or-create 依赖此）；route_rule_group / group_instance 硬删——删除语义不一致。
11. **create 校验 weight 无范围校验**（靠 fixWeight 兜底截断）；operation 上下文 token 只查非空。
12. **灰度元数据路由未实现**：appid / subenv 协议 key 全链路零消费（FR-TG-09，基线勘误）。

## 8. 逻辑单元 × 基线资产 / 局限对照

| 逻辑单元 | 基线对照 | 说明 |
|---|---|---|
| L4 | 资产 §5.5（两段式权重发布） | 分批灰度的机制基础；§7.1 表明仅 MySQL 分支完整 |
| L5 | 基线 §2.5（一键 Canary） | 差异化能力；强依赖 instanceId=IP 约定 |
| — | 资产 §5.6（逻辑实例） | 托管异构系统 |
| L2 + L7 | 局限 §6.17（表驱动 API、灰度需串 5 个 API） | 治理操作面原始 |
| — | 局限 §6.18（无鉴权 / token 不验） | FR-TG-10 |
| §7.1–§7.7 | 基线 §6 未收录 | 本次补证新发现，建议纳入局限输入 |
| — | 基线勘误 | §2.5「灰度元数据参与服务端筛选」不成立（预留未实现） |

## 更新历史

| 版本 | 日期 | 变更说明 |
| ------ | ------ | ------ |
| 1.0 | 2026-10-08 | 初版 |
