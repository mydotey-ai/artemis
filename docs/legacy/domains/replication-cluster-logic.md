# 复制与集群一致性 · 业务逻辑蓝本

版本: 1.0    更新时间: 2026-10-08

> 调研对象：原仓库 `~/Projects/mydotey/artemis`（version 2.0.2，git HEAD 9727bb5）。
> 定位：复制协议 / 集群成员 / 节点状态机 / 冷启动 / 管理面同步的完整业务逻辑，作为新产品设计时逐单元评估与优化的蓝本。事实性；取舍依据引用基线 §5/§6，对照见 §8。阅读约定：L1–L8、D1–D5、F1–F5；证据引用约定同 [replication-cluster-spec.md](replication-cluster-spec.md)。

## 1. 逻辑总览

```text
业务线程(写成功) ──replicate(task)──► 双通道 TaskDispatcher
                                      acceptor 线程: 5ms 收集 → taskId 去重合并
                                      批通道: 250条/2s 成批 | 单通道: 逐任务
                                      executor(每通道20线程): 出队滤过期(TTL 5s)
                                        → TrafficShaper 退避(默认无)
                                        → 扇出: 遍历 otherNodes() 查状态表
                                          可服务 → HTTP POST peer
                                          不可服务/UNKNOWN → 跳过(不生成失败任务)
                                        失败 → 分类: 可重试 → reaccept 插队队首
                                                      不可重试 → 丢弃告警
peer 端: 限流1M → replicationExecute(免readiness/免zone, region强校)
         register 覆盖入库 | heartbeat 缺租约同步补注册 | getServices 全量返回
```

| 逻辑单元 | 职责 | 关键代码（原仓库） |
|---|---|---|
| L1 复制任务管线 | 接收 / 去重 / 成批 / 执行 / 重试 | `artemis-common/.../taskdispatcher/*` |
| L2 双通道与乱序容错 | 批量 / 单条并发、无跨通道顺序 | 同上 + `RegistryReplicationManager.java` |
| L3 扇出与状态门控 | 广播遍历 + 跳过不可服务节点 + 定向重试 | `registry/replication/RegistryReplicationTool.java:127-196` |
| L4 接收端处理 | 豁免校验 / 限流 / 补注册 / 全量返回 | `RegistryReplicationServiceImpl.java` |
| L5 成员管理与探测 | 静态拓扑（配置驱动）、5s 串行自声明探测 | `cluster/{ServiceCluster,ClusterManager}.java` |
| L6 节点状态机 | STARTING→UP 门控、force 矩阵、终态性 | `cluster/NodeManager.java` |
| L7 冷启动初始化 | peer 全量拉取重建 + 空表拒就绪 | `cluster/RegistryReplicationInitializer.java` |
| L8 管理面 DB 同步 | 共享 DB + 两级轮询 + sleep 2s | `artemis-management/.../{ManagementRepository,GroupRepository}.java` |

## 2. 概念模型与不变量

- 复制 = 「写放大的摊销器」：每客户端写 ×(N-1) peer；靠批量（250/2s）与去重合并控制放大系数。
- taskId = `任务类简名 : InstanceKey : serviceUrl`（广播为字面 `"null"`）——**（类型 × 实例 × peer）** 三元去重键。
- 不变量：I1 出队任务的 submitTime ≤ 当前时间且 expiryTime 固定（TTL 自首次提交）；I2 同 taskId 缓冲中至多一条（覆盖继承旧 submitTime）；I3 广播只达可服务节点、定向不受状态约束；I4 接收端注册幂等覆盖 + 新租约保护使乱序 / 重复收敛（依赖 registry-lease D7）。

## 3. 状态机

**任务状态机**：`SUBMITTED → ACCEPTED（缓冲，同键覆盖）→ BATCHED/QUEUED → EXECUTING →（成功 ⇒ 完成 ｜ 可重试失败 ⇒ REACCEPTED（插队，继承 expiryTime）⇒ ACCEPTED ｜ 不可重试失败 ⇒ DROPPED）`；任一非完成态遇 `now ≥ expiryTime` ⇒ 丢弃（无重放）。

**节点状态机**（NodeManager）：

| 当前态 | 事件 | 次态 |
|---|---|---|
| STARTING | REGISTRY + DISCOVERY 双 initializer 成功 | UP（daemon 循环退出，终态） |
| STARTING | force-up | UP（**跳过全部初始同步**） |
| 任意 | force-down.<本机IP> | DOWN（覆盖 force-up） |
| UP | （无事件可触发） | 不迁移——无回退路径 |
| DOWN | 撤销 force-down | **保持 DOWN**（粘性，需 force-up 或重启） |

## 4. 决策规则

### D1 失败分类映射（响应错误码 → TaskErrorCode）

| 响应 / 异常 | 分类 | 处置 |
|---|---|---|
| bad-request / no-permission / internal-service-error / service-unavailable | PermanentFail | 丢弃 + warn |
| rate-limited | RateLimited | reaccept |
| unknown / data-not-found / partial_fail（逐实例）/ 网络异常（归 unknown） | RerunnableFail | reaccept |

证据：`replication/ReplicationTool.java:16-30`、`RegistryReplicationTool.java:172-200`。⚠ 批量通道的 reaccept 有截断缺陷（§7.1）。

### D2 成批与出队决策

成批：同批凑满 250 或队头任务等待满 2s；出队：`expiryTime ≤ now` 丢弃；批延迟按**队头** submitTime 判定（去重继承旧 submitTime ⇒ 重复心跳不延长等待）。证据：`BatchingTaskAcceptor.java:45-113`、`TaskAcceptor.java:181-183,211-244`。

### D3 扇出门控

```text
for peer in otherNodes():            # 出批时读最新成员视图
    status = 状态表[peer]             # 5s 探测的自声明状态
    if !canServiceRegistry(status):  # UP→true, DOWN→false, STARTING/UNKNOWN→看标志(null=false)
        continue                     # 跳过且不生成失败任务
    HTTP POST peer
失败(peer粒度) → 仅对失败 peer 生成定向重试任务(携带 serviceUrl)
```

证据：`RegistryReplicationTool.java:136-163`、`ServiceNodeUtil.java:37-65`。

### D4 canService 判定（口径分裂点）

`status == UP → true`；`status == DOWN → false`；STARTING / UNKNOWN → 读平面布尔位。**executeInitializers 内部用原始布尔位、对外判定用 status 优先口径**——两口径在 UP 后不等价（§7.5）。证据：`ServiceNodeUtil.java:37-65`、`NodeManager.java:166-190`。

### D5 force 优先级矩阵（任一配置变更即全量重算）

```text
status.force-up          → UP + canR=canD=true
status.force-down.<ip>   → DOWN（覆盖上行）
registry.force-up/down   → 置/清 canR（down 后判，覆盖 up）
discovery.force-up/down  → 置/清 canD（同上）
```

证据：`NodeManager.java:129-163`。

## 5. 核心流程

### F1 写 → 复制端到端

客户端写成功（业务线程）→ replicate 入通道即返回 → acceptor 5ms 收集 + 去重 → 成批 → executor 出队（滤过期）→ D3 扇出（HTTP，心跳 200ms 超时）→ peer 端 D1 分类处理 → 失败按 D1 重试 / 丢弃。

### F2 peer 故障与恢复

peer 不可达 → 探测呈现 UNKNOWN → D3 跳过（该 peer 收不到广播，缺口不补）→ peer 恢复后：① 客户端心跳打到它 → 缺租约 data-not-found → 客户端补注册；② 其他 peer 复制心跳到它（若它此时 UNKNOWN 被跳过——则仅靠①）→ 收敛。**恢复不依赖复制重放，依赖心跳全量重述。**

### F3 冷启动

启动 → STARTING → daemon 1s 循环 → REGISTRY：按 localZoneOtherNodes → otherZoneNodes 顺序找 UP peer → POST services.json（region/zone 参数，zone 不生效）→ 全量返回 → 逐服务 register 重建（发 NEW 事件，事件流可能被缓冲截断）→ `instanceCount > 0` 判成功 → DISCOVERY：管理面双仓库首轮刷新成功 → 双目标达成 → UP。

### F4 成员变更

配置变更 → ServiceCluster 更新拓扑（发布形态需重启，见 config-reference §0）（**空配置跳过更新**）→ ClusterManager 重建五个 volatile 视图（**新列表为空的视图残留旧成员**）→ 广播出批读新视图即时生效 → 定向重试任务不受影响照发 → 被移除节点下轮探测从状态表消失；其自身配置未变期间仍视本节点为 peer。

### F5 管理写生效

REST 写 DB → sleep 2s（仅阻塞本节点 API 线程）→ 返回 → 各节点轮询（Management 1s / Group 5s）全量拉 DB → diff → reload 推送（traffic-governance / operations-audit 域）。**无 ACK、无版本、无失败回调**——对端刷新持续失败时生效窗口无限大。

## 6. 并发与时序

| 场景 | 机制 | 结果 |
|---|---|---|
| 双通道并发（register 单条 vs heartbeat 批量） | 独立 dispatcher 线程池 | 跨通道无顺序；靠接收端幂等覆盖 + creationTime 保护收敛 |
| reaccept 与新任务同键竞争 | reaccept-drop（新任务胜出） | 不放大重复 |
| 状态表整体重建 vs 读取 | 普通 HashMap 换新（非 volatile） | 读线程短暂读旧表（benign race） |
| force 开关并发变更 | 每键 listener 全量重算 | 最终一致 |

参数：TTL 5s / 批 250 条 / 2s / acceptor 写等待 5ms / executor 20 线程×2 通道 / 接收限流 1M / 探测 5s×3 重试 / 门控循环 1s / 冷启动拉取超时 2s / 心跳复制超时 200ms / sleep 2s + 轮询 1s、5s。

## 7. 边界与已知缺陷（事实清单）

1. **批量失败重试截断（最重要）**：`TaskExecutor#execute` 循环内遇第一个可重试任务即 `reaccept + return`——**同批其余失败任务（含可重试的）被静默丢弃**（无重试、无丢弃日志）。批量心跳失败时实际重试面收窄为「每批 1 实例」，其余靠心跳自愈兜底。证据：`TaskExecutor.java:110-113`。（新发现）
2. **TrafficShaper 默认无退避**：fail-delay 配置默认**空 map**，缺失键返回 0——「默认 10ms 整形延迟」的既有表述不成立（10ms 仅在显式配置条目且值为 null 时兜底）。证据：`TrafficShaper.java:44-77,108-115`。（基线勘误）
3. **冷启动空集群死锁**：`instanceCount > 0` 成功门槛使无数据集群永不就绪（STARTING 循环），必须 force-up 引导。证据：`RegistryReplicationInitializer.java:112`。（新发现）
4. **force-up 跳过全部初始同步**：initAsync 见 UP 即退出——未同步数据的节点直接对外服务，违背 readiness 初衷，属危险开关。证据：`NodeManager.java:106-107,130-135`。（新发现）
5. **UP 后平面不可摘 + 判定口径不一致**：对外判定 status 优先（UP 恒 true），`registry.force-down` 在 UP 后被压制；而 executeInitializers 用原始布尔位——两套口径不等价。证据：`ServiceNodeUtil.java:37-65` vs `NodeManager.java:166-190`。（新发现）
6. **DOWN 粘性**：撤销 force-down 无恢复路径（updateNodeStatus 只设 DOWN），需 force-up 或重启。（新发现）
7. **成员清空视图残留**：五个视图仅在新列表非空时替换——本 zone 其他节点全部移除的场景旧成员永久残留，继续被探测、收广播。证据：`ClusterManager.java:177-187`。（新发现）
8. **定向重试不看成员 / 状态表**：携带 serviceUrl 直发，移出集群的节点仍会收到。证据：`RegistryReplicationTool.java:172-196`。
9. **超时缺失**：register/unregister 复制与 `getClusterNodeStatus` 探测调用均未设显式超时（依赖连接池 / 客户端默认）。证据：`RegistryReplicationServiceClient.java`、`StatusServiceClient.java:42-52`。
10. **冷启动重建事件流截断**：逐服务 register 每实例发 NEW，事件缓冲按实例折叠 + 10k 上限——大规模重建的推送流不完整，订阅方靠客户端兜底。证据：`RegistryRepository.java:263-275`。
11. **管理面生效无确认**：sleep 2s 是唯一手段且只覆盖 Group 仓库约一个刷新周期；对端刷新失败时窗口无限大。（基线 §6.8 的行级细化）

## 8. 逻辑单元 × 基线资产 / 局限对照

| 逻辑单元 | 基线对照 | 说明 |
|---|---|---|
| L1 + L2 | 资产 §5.11（批量贯穿） | 复制批量是写放大的唯一摊销手段 |
| L3 + F2 | 基线 §3.3（收敛路径）/ 局限 §6.4（写放大、无确认闭环） | 尽力而为 + 心跳自愈 |
| L5 | 局限 §6.10（静态成员 + 子串识别 + 串行探测） | 运维模型停留在改配置时代 |
| L6 + L7 | 资产 §5.9（readiness 门控） | 数据不全不接流量；§7.3/§7.4 表明门控有两个洞 |
| L8 | 局限 §6.8（sleep 2s + 轮询，无生效确认） | 管理面一致性靠时序赌注 |
| §7.1 / §7.3–§7.7 | 基线 §6 未收录 | 本次补证新发现，建议纳入局限输入 |
| — | 基线勘误 | TrafficShaper「默认 10ms」不成立（§7.2）；「register/unregister 走单条通道」实为可热切默认值 |

## 更新历史

| 版本 | 日期 | 变更说明 |
| ------ | ------ | ------ |
| 1.0 | 2026-10-08 | 初版 |
