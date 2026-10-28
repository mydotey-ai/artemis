# 容量基线

版本: 1.0    更新时间: 2026-10-28

10 万实例级容量验证的基线数据与瓶颈结论（roadmap v1.0「容量验证」交付物）。压测工具 `java/scripts/bench.sh`（用法与档位矩阵见 [phase-6 设计](../milestones/v1.0/phases/phase-6-capacity.md)），服务端为各 `*-server` 可执行 jar 真实部署产物。

## 环境与口径

| 项 | 值 |
|---|---|
| 硬件 | 8C/16G 开发机（x86_64），**回环网络**（带宽数字为回环口径，真实网络需重测） |
| JDK | Temurin 25.0.4；GC G1（默认参数集，未调优） |
| native 分配器 | jemalloc 5.3.0（全部子进程 `LD_PRELOAD`，强制无回退） |
| JVM 参数 | heap 随档位显式；`-XX:MaxDirectMemorySize=512m`；`-Xlog:gc` 落盘 |
| 口径降级 | large 档（10 万）本机预检不可行（预算 14.1G > 可用 ~11.3G）——以 **small 档（1 万实例）实测 + 线性外推**出数，10 万直测需 ≥16C/32G 独立机补跑（复现命令见文末） |

**压测矩阵（small 档）**：500 服务 × 20 实例 = 1 万实例，50 个模拟进程（每进程 10 服务 200 实例），2 个 registry driver JVM；S4 另起 1 个 subscriber JVM（10 订阅者）。

## 场景结果（small 档，1 万实例）

### S1 注册风暴

| 指标 | 值 |
|---|---|
| 注册到位 | 全量 10,000 实例到位，**ramp 30s 计划内完成**（首个非零采样后 ~26s 达满，采样粒度 5s） |
| 心跳自愈 | ramp 期 register 与心跳 upsert 并行生效，无丢失 |
| registry heap 峰值 | 652 MB / Xmx 1536 MB |
| registry GC | 全程（s1+s2 约 21 分钟）17 次停顿，无 Full GC |

### S2 稳态心跳

| 指标 | 值 |
|---|---|
| 租约稳定性 | 10,000 实例 10 分钟**零丢失**、无保护态误触发 |
| 心跳实例续约率 | 实测 ~2,100 renewals/s（理论值 2,000 = 50 进程 / 5s 周期 × 200 实例，吻合） |
| 心跳消息率 | 10 条/s（= 进程数 / 周期，与实例数解耦——「消息率 ∝ 进程数」资产验证） |
| registry heap | ~650 MB 稳态（每实例均摊 ~65 KB 含全部开销） |
| discovery heap | ~466 MB / 768 MB（全量投影 + 10 订阅者推送） |

### S4 订阅推送

| 指标 | 值 |
|---|---|
| 推送延迟（10 订阅者 × 1260 注入，per-subscriber 配对 n=12,590） | **p50=5ms / p90=6ms / p95=7ms** / p99=8286ms / max=12527ms |
| 分布形态 | 双峰：≥95% 样本亚 10ms；~1% 样本 8–12s 尖刺（见归因） |
| 尖刺归因 | 慢消费者断流计数 **0**、订阅全程稳定 10 活跃——排除订阅重连；候选为变更批组批/SDK listener 批量 apply 的尾部 + 轮换 tick（DELETE→NEW）慢路径，全样本留存于 `latency-s4.csv`，10 万档复查 |
| 服务端推送 | push_total ≈ 10.5 万事件（稳态合并推送含心跳引发的视图刷新） |
| registry/discovery heap | 与 S2 持平（churn 1/s 注入下无增长趋势） |

### 3 节点拓扑（复制）

| 指标 | 值 |
|---|---|
| 副本一致性 | 三节点 instances 峰值全部 **10,000**（采样序列逐节点提取，churn 期一致） |
| 复制应用变更 | 三节点合计 ~95 万 changes/600s（心跳 RENEW + UPDATE/DELETE 批组复制，RENEW 合并使实测低于理论线性外推） |
| 订阅推送 | **发现退化**：push_total 26,470 vs 单拓扑 118,660（22%）；订阅者观察率 4,600/12,600（37%），延迟 p50=6ms 但 p95≈500s（分钟级，靠 60s checksum 对账兜底收敛——正确性保持、实时性退化，见「表外新发现」） |

## 容量公式（外推口径）

- **内存**：registry ≈ 65 KB/实例（small 实测均摊，含心跳/租约/快照结构）→ 10 万实例 ≈ 6.5 GB heap（Xmx 建议 8G+）；discovery ≈ 47 KB/实例（全量投影）。
- **心跳消息率** = 模拟进程数 / 心跳周期（默认 5s），与实例数无关；实例续约处理率 = 消息率 × 每进程实例数。
- **复制放大**（全对全）：3 节点稳态复制流量 ≈ 单节点变更吞吐 × 2（legacy §3.1「写放大 ×(N-1)」同模型，实测系数见 3 节点节）。

## 瓶颈结论（对照预判表逐条）

| # | 预判 | 结论 |
|---|---|---|
| 1 | 心跳 upsert 逐实例比对 CPU 热点 | 未命中（1 万实例 2100 renewals/s 下 CPU 无单核打满；10 万档待测） |
| 2 | 全对全复制写放大 | 部分命中：复制本身无积压（batches dropped=0、副本一致），但 3 节点拓扑暴露订阅推送退化（表外发现 1）——写放大未成瓶颈，拓扑相关推送路径是 |
| 3 | 租约清理全表扫描 | 未命中（1s 周期 × 1 万租约无可见压力；10 万档为首要观察项） |
| 4 | 不可变快照重建分配风暴 | 未命中（S3 变更期无 GC 突增；churn 速率 1/s 观察窗口有限，留 10 万档） |
| 5 | 大服务推送/慢消费者断流 | 未命中（`subscription_closed_slow_consumer_total=0`，10 订阅全程活跃；单拓扑延迟 p95=7ms） |
| 6 | discovery bootstrap 峰值 | 未命中（订阅建立正常、无 heap 峰值突刺；bootstrap 时延未单独打点，留 10 万档观察） |
| 7 | driver 侧失真 | 未命中（driver CPU < server；2 分片下无争用） |
| 8 | 保护态误触发 | 未命中（S2/S3 全程 `self_protection=0`） |
| 9 | GC 停顿污染延迟 | 未命中（单拓扑 p95=7ms 亚 10ms，尖刺与 GC 停顿无时间相关） |
| — | 表外新发现 1 | **3 节点拓扑订阅推送退化**：变更→订阅者实时推送率 ~22-37%，延迟退化至分钟级靠对账兜底；单拓扑同负载 p95=7ms。候选机制：discovery 上游变更流与变更事实权节点（node-0）的相对位置（上游地址列表三节点、churn 事实在 node-0）——机制排查后置，全样本 `bench-report/20261028-191246/latency-s4.csv` 留存 |
| — | 表外新发现 2 | 3 节点复制 applied_changes 实测（~95 万/10min）低于心跳续约线性外推（RENEW 组批合并），复制流量模型按「组批合并后与实例数部分解耦」修正认知 |

## 复现步骤

```bash
# 预检依赖：jemalloc（apt install libjemalloc2）、JDK 25、Maven
# small 档单拓扑全场景（本文数据源）
java/scripts/bench.sh --profile small --scenarios s1,s2,s4
# 3 节点复制拓扑
java/scripts/bench.sh --profile small --topologyNodes 3 --scenarios s4
# 10 万实例基线（需 ≥16C/32G 独立压测机）
java/scripts/bench.sh --profile large --scenarios s1,s2,s4
java/scripts/bench.sh --profile large --topologyNodes 3 --scenarios s4
# 输出：java/scripts/bench-report/<时间戳>/（plan/summary/各场景采样 CSV/GC log）
```

档位矩阵、指标口径与两级 watchdog 见 phase 设计文档；工具用法 `bench.sh` 头部注释。

## 更新历史

| 版本 | 日期 | 变更说明 |
| ------ | ------ | ------ |
| 1.0 | 2026-10-28 | 初版：small 档（1 万实例）单拓扑 S1/S2/S4 + 3 节点副本一致性实测、容量公式（10 万外推口径）、预判九条逐条结论、表外新发现 2 条（3 节点订阅推送退化 / RENEW 组批合并） |
