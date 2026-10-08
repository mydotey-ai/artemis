# 运维管控与审计 · 业务逻辑蓝本

版本: 1.0    更新时间: 2026-10-08

> 调研对象：原仓库 `~/Projects/mydotey/artemis`（version 2.0.2，git HEAD 9727bb5）。
> 定位：四级摘除 / 合成事件 / 审计双写 / 状态端点的完整业务逻辑，作为新产品设计时逐单元评估与优化的蓝本。事实性；取舍依据引用基线 §5/§6，对照见 §8。阅读约定：L1–L7、D1–D4、F1–F4；证据引用约定同 [operations-audit-spec.md](operations-audit-spec.md)。

## 1. 逻辑总览

```text
REST 写(operate-*) ──checkCurrentNode(UP)──► DB 操作记录表(+log 双写)
                                                │ sleep 2s 后返回
各节点缓存刷新(1s/5s): 全量拉取 → key 集 diff → 换缓存引用
        ├─► 发现过滤(实时 isInstanceDown): instances/logicInstances 剔除 ──即刻生效
        ├─► 推送过滤(实时 isInstanceDown): down 实例 NEW 压掉
        └─► 合成事件: 记录新增→DELETE / 消失→NEW / 逻辑实例变化→RELOAD
                      → 变更缓冲 → NotificationCenter 推送订阅方
```

| 逻辑单元 | 职责 | 关键代码（原仓库） |
|---|---|---|
| L1 操作记录存储与缓存 | upsert / 删除、多 operation 叠加、缓存重建 | `artemis-management/.../ManagementRepository.java:183-209,325-404` |
| L2 四级级联判定 | 短路 OR、各级键与匹配 | `ManagementRepository.java:155-171`、`GroupRepository.java:126-128` |
| L3 生效管线 | 过滤 + 合成事件的产生与时序 | `ManagementRepository.java:436-509`、两个 Filter |
| L4 zone / group 摘除 | 服务粒度 zone、SearchTree 组级 | `ZoneRepository.java`、`GroupRepository.java:372-382` |
| L5 管理视角查询 | status 覆写 + 租约注入 | `ManagementRepository.java:298-319` |
| L6 审计双写与查询 | log 行内容、快照时机、过滤 | `ManagementLogServiceImpl.java`、`group/dao/*LogDao.java` |
| L7 状态端点 | 7 端点内容与限流 | `artemis-service/.../status/StatusServiceImpl.java` |

## 2. 概念模型与不变量

- 操作记录表 = 摘除态的唯一事实（DB），内存缓存为其投影；**注册表租约与摘除态完全正交**（心跳照常、剔除照常——被摘实例真下线后租约到期产生天然 DELETE，与合成 DELETE 幂等叠加）。
- 不变量：I1 `isInstanceDown` 结果仅由当前缓存中记录的存在性决定（实时计算，无状态）；I2 恢复 = 记录消失（删单条）；I3 四级 OR 无抵消（高层记录存在时低层恢复无效）。

## 3. 状态机

**操作记录生命周期**：`无记录（up）→（下线：insert，complete=false）→ 有记录（down，可叠加多 operation）→（恢复：按原 operation 删一条）→ …全部删除 → up`。

**实例的发现投影状态**：`可见 →（任一级摘除命中 + 缓存刷新）→ 隐藏（过滤剔除 + 收到合成 DELETE）→（记录全删 + 刷新）→ 可见（合成 NEW）`。注册表侧状态（租约）独立演化，两轨只在「实例真实消失」时交汇（天然 DELETE）。

## 4. 决策规则

### D1 四级级联判定（短路 OR）

```text
isInstanceDown(instance):
    instanceOperations[InstanceKey.of(instance)] 存在            → down   # 小写键
    serverOperations[regionId + instance.ip] 存在                → down   # 跨服务
    zoneOperations[regionId + serviceId + zoneId] 存在           → down   # 服务粒度
    groupSearchTree.first(五级 groupKey) != null                 → down   # 精确前缀
    否则                                                          → up
```

注意：operation 字符串值不参与判定（存在即摘）；空 operation 行在刷新装载时被跳过。证据：`ManagementRepository.java:155-171,386-398`。

### D2 合成事件决策（缓存刷新 diff）

```text
canServiceDiscovery == false: 本轮不推（直接返回）
server 记录新增  → 对 ip 多重映射的每实例合成 DELETE（⚠ 只按 ip 匹配, 不比 region）
server 记录消失  → 对应实例合成 NEW
instance 记录新增→ 注册表现存该实例 → 合成 DELETE（不在注册表则不合成）
instance 记录消失→ 现存 → 合成 NEW
服务同时有逻辑实例变化 → 聚合为一条 RELOAD（跳过逐实例事件）
```

diff 是 **key 集合层面**（新增 / 消失），不感知同 key 内容变化（operator / token 变更不触发事件）。证据：`ManagementRepository.java:325-374,436-509`。

### D3 zone 变化检测（盲区所在）

```text
diff 仅取 serviceId 集合对称差:
    服务从「无任何 zone 记录」→「有」   → 发 RELOAD
    服务从「有」→「无」                → 发 RELOAD
    服务已有记录, 再摘/恢复另一 zone    → 不发任何事件（仅过滤静默生效）
```

证据：`ZoneRepository.java:132-151`。

### D4 审计快照时机

insert / update → 记写后值；delete → 先 select 旧值、删、记删前值；均为**单快照**。instance / server 路径无实体快照（仅键 + 审计字段）；zone 的 reason 模型字段存在但 insert SQL 无该列。证据：`BusinessDao.java:213-255`、各 LogDao insert SQL。

## 5. 核心流程

### F1 实例摘除全链路

`operate-instance`（complete=false）→ checkCurrentNode → DB insert + log 双写 → sleep 2s → 返回 →（异步）各节点刷新（1s）：重建缓存 → key diff 出新增 → 换引用（过滤即刻生效：后续 lookup 不含该实例）→ 对现存实例合成 DELETE 入变更缓冲 → 推送 → 订阅方视图移除。端到端（API 返回 + 生效）≈ 1–3s。

### F2 服务器摘除（机房 / 单机维护）

`operate-server`（regionId + IP）→ 记录生效路径同 F1 → 影响 = 同 region 该 IP 上**全部服务**的全部实例；合成事件按 ip 多重映射逐实例发 DELETE。恢复对称。

### F3 叠加与逐层恢复

摘 zone（服务级应急）后又单摘其中一实例 → 恢复该实例记录 → zone 记录仍在 → 实例仍 down（OR 无抵消）；须逐层全部恢复才可见。多条 operation 叠加需逐条按原字符串恢复。

### F4 与注册表的双轨交汇

被摘实例一直不恢复 → 心跳照常、租约不断 → 注册表与发现视图长期分叉（注册表有、发现无）。实例真实下线（停心跳）→ 租约到期天然 DELETE 推送（订阅方已不可见，幂等）→ 记录仍留 DB 等人工恢复或清理。

## 6. 并发与时序

| 场景 | 机制 | 结果 |
|---|---|---|
| 同轮刷新内过滤与推送 | 先换缓存引用、后发合成事件 | 订阅方收到 DELETE 时，过滤已生效（顺序保证） |
| down 判定的实时性 | 过滤 / 推送过滤按请求实时调 isInstanceDown | 无状态化，缓存换新即变 |
| 操作记录 upsert 竞态 | DB 唯一键 + on duplicate | 最终一致 |
| 多节点并发写 | 共享 DB 串行化 | 记录叠加语义稳定 |

参数：instance/server 缓存 1s / group、zone 缓存 5s / sleep 2s / status 限流 30 每 10s。

## 7. 边界与已知缺陷（事实清单）

1. **zone 摘除推送盲区**（D3）：同服务第二条 zone 记录增删不触发推送——与 instance / group 路径行为不一致，客户端只能靠自身全量兜底（最久 15min）感知。证据：`ZoneRepository.java:138-151`。（新发现）
2. **合成事件 region 不匹配**：server 级合成只按 IP（D2），跨 region IP 复用时对未摘除实例推虚假 DELETE；推送过滤器实时重查（region 不符 → 非 down）反而放行该虚假事件。证据：`ManagementRepository.java:467-479`。（新发现）
3. **恢复的单条性**：叠加多 operation 需逐条按原字符串恢复，传错无报错；审计上完整恢复需知全部历史 operation 值。（新发现）
4. **operation 无枚举校验**：任意非空字符串（含 "up"）都构成摘除——语义靠约定。（新发现）
5. **force-up 跳过初始同步**（FR-OA-08）：危险开关无护栏。（新发现，与 replication-cluster §7.4 同源）
6. **审计不对称**：instance / server 无快照无 reason；zone reason 不落库；单快照非双值；token / reason / 时间段不可过滤——「全量审计」的实际能力边界远小于宣称（基线 §2.6 待勘误）。
7. **getAllZoneOperations 忽略 regionId 参数**：查询端点传参无效，恒返回全量。证据：`ZoneRepository.java:85-87`。（新发现）
8. **destroyServers 死代码 + 删除条件错位**（FR-OA-09）。
9. **zone 写路径无 waitForPeerSync**：变更生效完全押注 5s 轮询。

## 8. 逻辑单元 × 基线资产 / 局限对照

| 逻辑单元 | 基线对照 | 说明 |
|---|---|---|
| L1 + L2 | 资产 §5.4（四级摘除 + 操作记录即状态） | 可叠加 / 可审计 / 不删数据；§7.3/§7.4 表明恢复语义与校验粗糙 |
| L3 | 资产 §5.7（过滤器 SPI） / §5.12（状态 API） | 管理面作用于数据面的干净注入点 |
| L6 | 局限 §6.16（无分页）/ §6.18（无鉴权） | 审计查询 10 万级不可用；token 不验 |
| F4 双轨交汇 | 基线 §3.3 | 摘除与租约正交是双轨分离架构的红利 |
| §7.1 / §7.2 / §7.7 | 基线 §6 未收录 | 本次补证新发现，建议纳入局限输入 |
| — | 基线勘误 | §2.6「审计前后数据快照」「token/reason/时间段过滤」「destroyServers」三处表述与代码不符 |

## 更新历史

| 版本 | 日期 | 变更说明 |
| ------ | ------ | ------ |
| 1.0 | 2026-10-08 | 初版 |
