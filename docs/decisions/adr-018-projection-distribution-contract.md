# ADR-018：registry → discovery 投影分发契约

版本: 1.0    更新时间: 2026-10-23

## 背景

架构 §2 将 registry 与 discovery 拆为独立服务，通道③（registry → discovery 数据分发）需要契约。原产品中 discovery 与 registry 同节点、直接共享内存，无对应协议可继承；同时 registry 内部事件（纯续约 `RENEW`、TTL 过期清理）与 discovery 关心的事件（新增/变更/移除）语义并不等价，且自我保护态（stale）是 registry 的清理门控实现细节。

契约形态直接影响：消费方代码是否需要理解 registry 内部机制、推送丢失的收敛路径、以及未来规则流（v0.4 版本 + ack）的演进空间。

## 决策

`projection.v1` 只承载实例事实，按消费方语义定义：

1. **事件三值**：`ChangeType` 仅 `NEW/UPDATE/DELETE`。`RENEW`（纯续约）由服务端过滤——投影无租约、内容未变零推送；TTL 过期清理由服务端映射为 `DELETE`——清理方式是 registry 内部概念，不进 wire 契约。
2. **统一事件源**：聚合层对所有结构性变更统一派发（清理派发 `EVICTED` 内部事件）；复制扇出在发送侧过滤 `EVICTED`（「删除不跨节点传播」行为不变），投影分发过滤 `RENEW`、映射 `EVICTED → DELETE`。消费方过滤点取代「清理不派发事件」的旧不变量。
3. **at-most-once 投放**：推送不重试、不确认；慢消费者（订阅者有界队列满）或推送失败 → 服务端断流注销，消费方重 `Bootstrap` 全量收敛，辅以三层兜底轮询。不引入序号/变更 ack/版本。
4. **订阅建立确认**：`Watch` 登记订阅后立即发送一条空批（`services` 为空）作为建立信号。消费方安全序为「先 Watch → 收到确认 → Bootstrap」——确认之前的变更已包含在随后的全量快照中，重叠窗口被全量覆盖幂等吸收，实现可执行的无丢失序。
5. **bootstrap 复用公共模型**：`BootstrapResponseData` 直接携带 `common.v1.ServiceInstances`（server-stream 按字节预算分片，配置与 peer bootstrap 共用），不自立包装「投影服务」消息。
6. **registry 保护态不进契约**：`stale` 是 registry 内部清理门控概念，discovery 不感知、不判定；保护态排障面继续由 management `GetSelfProtectionStatus` 提供。discovery 侧新鲜度（如上游断连）属其自身语义。
7. **契约注释按消费方语义书写**：TTL、过期清理、心跳、内部事件映射等 registry 机制不出现在 proto 注释中，落实现 javadoc 与设计文档。

## 否决方案

| 方案 | 否决理由 |
|---|---|
| 变更事件组件上移 `common.v1`，复制与投影共用 `ChangeType`（含 RENEW） | 复制语义与投影语义不等价；共用枚举迫使消费方理解「哪些值不会出现」，契约泄漏实现细节 |
| 契约携带 `stale`（bootstrap 逐服务 / Watch 保护态快照） | discovery 不需要关心 registry 自我保护；保护期间数据本就冻结、变更最少，独立推送机制性价比低 |
| Watch 采用序号 + 确认（或版本水位） | 引入可靠投递状态机；断流重 bootstrap 全量覆盖已提供同等收敛，复杂度不划算 |
| 无建立确认，靠「先 Watch 后 Bootstrap」的时序约定 | 两个独立流无建立信号，订阅登记与快照时刻之间的事件可能两头落空，约定不可执行 |

## 后果

- 消费方（discovery）只实现三值事件的最终状态应用，无需租约、清理、保护态知识；断流即重 bootstrap。
- 服务端为每个订阅者维护有界队列与串行推送线程；慢消费者被主动断开，不做背压协商。
- 未来规则流（版本 + ack）与数据面分发流互不耦合（v0.4）。
- 清理事件进入统一事件源后，复制扇出与投影分发的过滤规则集中在各自边界，聚合层无需感知消费方差异。

## 更新历史

| 版本 | 日期 | 变更说明 |
| ------ | ------ | ------ |
| 1.0 | 2026-10-23 | v0.3 phase-1 设计 review 定案 |
