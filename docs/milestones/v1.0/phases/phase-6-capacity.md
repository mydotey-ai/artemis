# Phase 6：容量验证

版本: 1.10    更新时间: 2026-10-28

## 目标

10 万实例级容量压测，发布容量基线文档（内存/带宽/QPS/推送延迟），瓶颈结论入 docs/deploy/。

## 范围

**做什么**：

- 压测 driver 模块 `java/client/artemis-bench`：SDK 驱动（复用 `ArtemisClients` 三入口注入 MeterRegistry），registry 侧（多 client 模拟进程 × 每进程本地实例集）与 discovery 侧（订阅 + 推送延迟测量）负载生成、场景化编排、driver 侧指标 CSV 输出。
- 编排脚本 `java/scripts/bench.sh`：一条命令拉起完整压测——服务端拓扑（artemis-dist fat jar 真实部署产物）→ driver 分片（多 JVM 均摊 client）→ 周期采样（服务端 Prometheus 端点 + JVM/进程级）→ 汇总。
- 场景矩阵 S1–S4（注册风暴 / 稳态心跳 / 变更风暴 / 订阅推送），单节点与 3 节点集群两种拓扑。
- 容量基线文档 `docs/deploy/capacity-baseline.md`：环境口径 + 场景 × 实测结果 + 容量公式 + 瓶颈结论（对照 legacy §3.1 上限）+ 复现步骤。

**不做什么**：

- 不补建容量机制——批量/聚合心跳（消息率 ∝ 进程数）、批量扇出、无锁读 v0.1 起内建；本 phase 验证与出数，发现的瓶颈**只记录结论与后置项**，不做机制优化。
- 不做多机分布式压测——单机 8C/16G 回环网络；带宽数字按回环口径记录并注明与真实网络的换算限制。
- 不压治理面——management/DB/规则流不在矩阵内（数据面核心容量：registry 心跳/复制 + discovery 投影/推送）。
- 不进 CI——压测时长与资源不合 CI 口径；driver 编排逻辑以单测进 surefire。

## 涉及模块

- 新增 `java/client/artemis-bench`（client 线，release=8）：依赖 `artemis-client` + `slf4j-api` + `micrometer-core`（均 client 线既有封闭清单内，**零新组件**）；不依赖任何服务端模块。
- 新增 `java/scripts/bench.sh`。
- 服务端以既有 `artemis-dist` fat jar 拉起（零代码变更）。
- proto：无变更（压测走 SDK 现有契约）。

## 验收方式

对应测试文档：[phase-6-capacity-test.md](phase-6-capacity-test.md)

压测报告（基线数据 + 瓶颈结论），文档入 docs/deploy/。

## 产品设计（③ 定稿）

### 工具形态定案：独立压测模块 + 脚本编排（非脚本化裸 SDK 集群）

plan 说明二选一，定案**独立压测模块**，理由：场景编排（分片计算、实例分配矩阵、变更注入节奏、延迟配对）需要真实代码承载，纯脚本无法维护；模块挂 client 线（依赖封闭：禁依赖服务端模块），服务端拓扑经 bench.sh 用 artemis-dist fat jar 拉起——**压测对象即真实部署产物**，与 phase-3/4 交付形态闭环。脚本化部分收敛为 bench.sh 一层薄编排（可复现 = 一条命令）。

### 规模参数体系与资源模型（review 意见 1/2：规模 = 实例矩阵 × 进程布局，两者共同决定「能否在机器上跑」）

**两层规模参数**：

- **逻辑规模**（服务端看到的协议面）：`--services S --instances-per-service I`（总实例 N=S×I）→ `--processes P`（模拟进程数 = RegistryClient 实例数，每 client 持有 N/P 实例，心跳消息率 = P/心跳周期）；`--subscribers Q`（discovery 订阅 client 数）。命名确定性：serviceId/instanceId 由参数派生（`bench-svc-0001` / `bench-svc-0001-i042`），数据可整体识别与清理；实例分配确定性（复现可对齐）。
- **物理布局**（机器资源消耗的真正来源）：`--topology 1n|3n`（registry 节点数，+1 discovery）→ `--driver-jvms k`（registry driver JVM 数，P 个 client 均摊到 k 个 JVM，每 JVM 承载 P/k client；订阅 driver 独立 1 JVM 承载 Q 个 DiscoveryClient）。

**资源模型（能否跑的判定公式，bench.sh 预检依据）**：

- 内存：`可用 ≥ (R×registry-heap + 1×discovery-heap + k×driver-heap + 1×subscriber-heap) × 1.2`（R=拓扑节点数；heap 各进程显式 `-Xmx`，禁默认吃满）。
- CPU：重 JVM 进程数 `R + 1 + k + 1` 建议 ≤ 核数（否则调度争用污染时延数据）；每 JVM 内 client 数建议 ≤ ~50（grpc channel/线程争用，超过即预判 #7 的失真风险）。
- 物理布局与逻辑规模解耦：同一逻辑规模（S×I×P）可配不同 k 适配机器——加 JVM 数分摊 client 不改变服务端协议面。

### 档位表（全维度展开，全部底层参数可覆盖）

`--profile <name>` 展开为下表组合；`custom` 全参数显式给：

| 档位 | 拓扑（R+disc） | 实例矩阵 S×I | client P（每 client 实例） | driver JVM k（每 JVM client） | 订阅 Q | 时长/场景 | 内存分解（合计） | 适用环境 |
|---|---|---|---|---|---|---|---|---|
| dev | 1+1 | 100×10 = 1 千 | 20（50） | 1（20） | 5 | 2min | 512m+512m+512m+512m ≈ **2G** | 8C/16G 开发机日常 |
| small | 1+1 | 500×20 = 1 万 | 50（200） | 2（25） | 10 | 10min | 1.5G+768m+1G×2+512m ≈ **5G** | 8C/16G 开发机（本机 main 档） |
| medium | 1+1（3+1 可选） | 1000×50 = 5 万 | 100（500） | 3（~33） | 20 | 30min | 3G+1G+1.5G×3+768m ≈ **10G** | ≥16C/32G |
| large | 1+1 与 3+1 | 1000×100 = 10 万 | 200（500） | 4（50） | 20 | 30–60min | 单拓扑 ≈ **12.5G**（reg 4G+disc 1.5G+driver 1.5G×4+sub 1G）；3+1 ≈ **17G+** | ≥16C/32G 独立压测机 |

**开发机（8C/16G，可用 ~11G）保护措施**：

- 默认档 **small**（1 万，2 driver JVM）；large 单拓扑（进程数 1+1+4+1=7 ≈ 核数上限）属本机极限操作，**3 节点 large（17G+、9 进程）本机不可行**——需独立压测机。
- **逐档递增纪律**：dev → small → large，每档确认资源曲线（heap/RSS/CPU/进程数）无异常再上一档；跳档视为流程违规。
- bench.sh 启动前按资源模型公式预检（进程清单 × heap 预算），不满足拒绝启动（防把开发机跑挂）。
- 10 万基线报告注明执行环境与物理布局口径；开发机跑 large 受限（内存/CPU 不足）时，以 small 档数据线性外推 + 注明口径降级，或独立机补跑。

### 时间控制（压测时长须有界）

- **场景内结构**：每场景 = `--ramp`（负载爬坡建立，默认 30s；S1 注册风暴爬坡 = 注册分批速率，是风暴强度参数）+ `--duration`（**稳态测量窗，出数只取此窗**，排除启动抖动）+ 收尾采样。
- **时长默认随档位**：dev 2min / small 10min / medium 30min / large 30min（上限 60min 可覆盖），全部时间参数可单独覆盖。
- **watchdog 两级**（防脚本/服务端 hang 长期占机）：
  - 场景级硬顶 = `(ramp + duration) × 2`——driver 未按期正常退出即强杀进程树、该场景标记 failed、继续或终止由整场预算决定；
  - 整场上限 `--max-total`（默认 = 各场景时间预算之和 ×1.5 + 拓扑准备 5min）——超时终止未开始/进行中场景，已完成场景数据与采样保留。
- 基线文档记录各场景实际起止时刻与测量窗口。

### 预判瓶颈与评估方法（review 意见 3：压测是诊断导向，非只出数）

压测前预判瓶颈点、命中判据与优化方向；压测报告「瓶颈结论」节按此表**逐条对照**写结论（命中/未命中 + 数据 + 是否后置）：

| # | 预判瓶颈点 | 命中判据（看什么） | 优化方向（后置，不在本 phase 实施） |
|---|---|---|---|
| 1 | 心跳全量 upsert 的逐实例内容比对 CPU 热点（每消息 N 实例 × 每实例字段比对） | S2 稳态 CPU 单核打满 + 消息处理速率 < 消息到达速率；S1（upsert 主导）vs S2（RENEW 主导）处理耗时对比 | 内容 hash 短路比对（SDK 端预计算） |
| 2 | 全对全复制写放大 ×(N-1)（legacy §3.1 上限，变更吞吐天花板） | 3 节点 vs 单节点 S3 吞吐差 > 接近 2 倍衰减；复制批次队列积压（`batches.rejected/dropped` 非零或延迟增长） | 批组加大；负责制分片（v1.x 预留） |
| 3 | 租约过期清理全表扫描（1s 周期 × 10 万租约） | S2/S3 清理周期耗时增长趋势、老年代/GC 频率抬升 | 分片扫描/时间轮（legacy 同上限） |
| 4 | 不可变快照写放大：churn 高频变更服务的快照重建分配风暴 | S3 大服务（I 高）变更期 heap 分配率/GC 暂停突增，小服务对照不明显 | 快照粒度/结构优化（COW） |
| 5 | 大服务订阅推送批大小与慢消费者断流 | S4 推送延迟 P99 抖动、isReady 断流计数非零、discovery heap 峰值 | 推送批调度优化；慢消费者分级 |
| 6 | discovery bootstrap 全量拉取瞬时峰值（大矩阵下 Q×全量） | S4 bootstrap 时延超秒级 + bootstrap 期 heap 峰值 | bootstrap 分页/限速 |
| 7 | driver 侧瓶颈失真（单 JVM 多 client 的 grpc/线程争用被误判为服务端瓶颈） | driver CPU ≈ or > server CPU 时数据不可信，需增大分片数重跑 | bench.sh 分片参数调整；多机 driver（本 phase 仅注明） |
| 8 | 自我保护误触发（正常 churn 判心跳丢失） | S2/S3 期 `service.protection`/节点级保护态指标触发 | 保护态阈值调参 |
| 9 | GC 停顿污染延迟数据（大堆 + 高分配率下 G1 停顿与推送延迟耦合） | S4 推送延迟 P99 尖刺与 `-Xlog:gc` 停顿时刻相关性分析 | GC 器对照（G1 vs ZGC，JVM 参数口径小节）、分代/region 调参 |

未命中预判但实测异常的现象同样记录（发现未知问题 = 压测核心价值，表外发现单列）。

### 场景矩阵

| 场景 | registry 侧动作 | discovery 侧动作 | 出数 |
|---|---|---|---|
| S1 register-storm | 全量实例 register（ramp 可配），无心跳外的额外操作 | — | 10 万实例到位时间曲线（租约总数达峰）、注册期 QPS/内存峰值 |
| S2 steady-heartbeat | 稳态心跳持续（默认 30min），零显式变更 | — | 稳态心跳消息率、实例事件率、heap/RSS 稳态、GC、带宽 |
| S3 churn | 持续变更注入：`--churnIntervalMs`（默认 1000）节奏的 update + 每 10 tick 一对 deregister/register（DELETE→NEW 全链路，净集合规模不变） | — | 变更吞吐、复制批次（3 节点拓扑对照单节点 = 复制放大系数）、heap 峰值 |
| S4 subscribe-push | 与 S3 同源变更驱动 | Q 个 DiscoveryClient 订阅全量服务集（空服务集选择器；Q 默认 20，子集订阅后置） | bootstrap 全量时延、变更→订阅者 listener 触发的推送延迟 P50/P95/P99/max（**每订阅者独立配对**，非 min-of-Q） |

场景可叠加（S2+S3+S4 = 稳态综合负载）；S1 单独跑（冷启动语义）。

### 拓扑与架构图

- **基线拓扑**（R=1）：单节点 registry + 单节点 discovery。
- **复制拓扑**（R=3）：3 节点 registry 集群（静态成员，全对全复制）+ 1 discovery——出复制放大与副本一致性数据（对照 legacy §3.1「写放大 ×(N-1)」上限）。

```text
┌────────────────────────── 压测机（单机 · 回环网络）──────────────────────────┐
│                                                                             │
│  [registry driver JVM ×k]───P 条心跳流（gRPC stream）───►[registry JVM ×R]   │
│   每 JVM 持 P/k 个 RegistryClient       │                │ R=3 时节点间      │
│   每 client 持 N/P 实例                 │                │ 全对全复制        │
│   （churn 场景：注入 update/滚动）      │                ▼                 │
│                                         │       bootstrap 全量 + 变更流     │
│                                         │                │                 │
│  [subscriber driver JVM ×1]──Q 条订阅流（推+对账）──►[discovery JVM ×1]      │
│   每 JVM 持 Q 个 DiscoveryClient        │  投影构建：registry 为上游源       │
│   （S4：listener 时间戳记推送延迟）     │                                   │
│                                         │                                   │
│  bench.sh（编排进程，非数据面）：                                            │
│   资源预检（资源模型公式）→ 拉起拓扑 → driver 分片 → 周期采样（5s）→ 汇总 CSV │
│   采样源：server Prometheus 端点 · jcmd GC.heap_info · /proc RSS 与 net/dev │
└─────────────────────────────────────────────────────────────────────────────┘
```

物理进程清单（资源模型输入）：registry ×R + discovery ×1 + registry driver ×k + subscriber driver ×1；S1/S2/S3 场景不启动 subscriber driver（进程清单按场景裁剪）。

### JVM 参数口径（review 意见：JVM 参数是重要调优维度）

- **默认参数集**（bench.sh 内置，随档位绑定，全部可经 `--jvm-args-server/--jvm-args-driver` 覆盖透传）：

| 参数 | registry / discovery | driver |
|---|---|---|
| `-Xms`/`-Xmx` | 按档位表（如 large reg 4G） | 按档位表（如 1.5G×k） |
| GC 器 | JDK 25 默认 G1；`--gc zgc` 快捷开关供对照实验 | 同左 |
| `-XX:MaxDirectMemorySize` | 显式限定（grpc-netty 堆外 buffer，防堆外泄漏计入 RSS 失真） | 同左 |
| `-Xlog:gc` | 落文件（GC 停顿与延迟相关性分析数据源） | 同左 |

**native 内存分配器**（长时运行 JVM 进程，bench.sh 对全部子进程默认注入）：

- 全部子进程 `LD_PRELOAD` jemalloc 替换 glibc malloc——避免多 arena 小块碎片导致 RSS 膨胀、加快小内存归还 OS；**无回退**：预检发现 jemalloc 未安装（`ldconfig -p` 解析不到）即拒绝启动压测，提示安装（如 Debian 系 `apt install libjemalloc2`）。

- **记录义务**：容量基线文档逐进程记录完整 JVM 参数**与所用分配器**（复现要素）；JVM 参数差异（如 G1 vs ZGC）作为独立小节出数，不与规模矩阵混排。
- **纪律**：首轮基线一律默认参数集（可比性）；调优对照实验在基线出数后进行，改变量单变量。

### 指标口径

| 指标 | 采集点 | 方式（周期 5s） |
|---|---|---|
| 服务端 QPS/计数（心跳消息、实例事件、复制批次、推送批次、租约总数、保护态） | server Prometheus 端点 | bench.sh 抓取文本存序列，事后差分出速率 |
| 服务端/driver JVM 内存 | heap used + RSS | `jcmd GC.heap_info` + `/proc/<pid>/status` VmRSS |
| GC | 次数/停顿 | `-Xlog:gc` 落文件 |
| 网络带宽 | 回环总量（全机口径，含 driver 流量，报告注明） | `/proc/net/dev` 差分 |
| 推送延迟 | driver 侧：churn 注入记 t0 → subscriber listener 记 t1 | 事件 id 配对（单机同钟），CSV join 事后算分布 |
| 心跳流消息率 | client 注入的 MeterRegistry | driver 周期读数写 CSV |
| 注册到位时间 | 租约总数序列首次达 S×I | 采样序列 |

### 容量基线文档结构（docs/deploy/capacity-baseline.md）

环境与口径（硬件/回环限制/JVM 参数/档位）→ 拓扑 → 场景 × 结果矩阵（每场景一节：参数、曲线数据表、关键数字）→ 容量公式（每实例内存系数、稳态消息率 = P/心跳周期、复制放大 = ×(N-1)）→ **瓶颈结论（按「预判瓶颈与评估方法」表逐条对照：命中/未命中 + 数据 + 优化方向是否后置及去向）** → 表外新发现问题单列 → 复现步骤（bench.sh 命令行与档位）。

## 实现结果（⑦ 补）

实现要点：`java/client/artemis-bench`（BenchConfig 档位/分片/确定性分配、BenchDriver 四角色 plan/registry/subscriber/join、LatencyPairer 每订阅者配对、CsvAppender）+ `java/scripts/bench.sh`（预检/拓扑/分片/采样/watchdog/汇总）。

⑥ code-review（max）13 findings 全修复，要点：实例 port 公式越界（small+ 档启动即崩，F1）、churn cursor 步长偏斜（每进程恒 churn 同一服务 → 改 shard 服务索引独立轮转，F2）、driver 崩溃被记为正常完成（wait 退出码 + FAILED_SCENARIOS 非零退出，F3）、subscriber JVM 堆配置与 plan 预检漏算（F4）、3 节点一致性检查在优雅注销后失效（改从采样序列取各节点峰值，F5）、shade SPI 合并（F6 前置修复 + 组件总表 #36 登记，F12）、S3 轮换/churn 速率可配/max-total 两级 watchdog/jvm-args 透传/jcmd heap 采样补齐（F7）、订阅观察带 subscriber 标识（F8）、report_dir 锚定 script_dir（F9）、meter tags 分号分隔保 CSV 三列（F10）、事件文件按场景命名防多场景截断（F11）、gc/maxTotalSec 死参数删除（F13）。

## 文档同步（⑩ 填）

- `docs/deploy/capacity-baseline.md`（新增，phase 核心交付：small 实测 + 外推口径 + 预判对照 + 表外发现）
- `docs/deploy/README.md`（索引登记）
- `docs/product/artemis.md` v1.42（phase-6 交付节：工具、基线要点、新发现、口径降级）
- `docs/dev/tech-stack-java.md` v1.30（组件总表 #36 maven-shade-plugin，⑥ finding 补登记）
- 本文档 v1.9（⑥⑦ 修复回写：S4 选择器口径、S3 参数定名、实现结果）
- 测试文档 v1.5/v1.6（用例结果、回归 4 轮、验证记录）
- `.gitignore`（bench-report/、dependency-reduced-pom.xml）
- `.claude/commands/bench-java-test.md`（新增会话侧命令，与 /dev-java-test 对应：启动压测环境 + 执行 + 结果提取口径防呆 + 失败处置；dev-test.sh 与 bench.sh 各自独立入口互不包含）
- CLAUDE.md：无需更新（bench 模块归 client/ 组内既有分组，dev-test.sh 形态映射不变）

review 定案触及原始文档的同步落点：档位/资源模型/时间控制等新机制仅存在于本文档（无原始文档已记载冲突）；maven-shade-plugin 进组件总表（tech-stack-java.md §3）。

## 更新历史

| 版本 | 日期 | 变更说明 |
| ------ | ------ | ------ |
| 1.10 | 2026-10-28 | ⑩ 补：新增 `.claude/commands/bench-java-test.md` 会话侧压测命令（与 /dev-java-test 对应，dev-test.sh 与 bench.sh 独立入口互不包含） |
| 1.9 | 2026-10-28 | ⑥⑦ 修复回写：S4 选择器口径收口（空服务集全量、每订阅者独立配对）、S3 参数定名（--churnIntervalMs + 每 10 tick DELETE→NEW 轮换）、实现结果 13 findings 记录 |
| 1.8 | 2026-10-28 | review 修订：时间控制——场景内 ramp+稳态测量窗结构、时长随档位、两级 watchdog（场景硬顶强杀 + 整场 max-total 保留已完成数据） |
| 1.7 | 2026-10-28 | review 定案：jemalloc 强制（LD_PRELOAD 无回退，未装阻止启动并提示安装） |
| 1.6 | 2026-10-28 | review 修订：分配器方案收敛——默认 LD_PRELOAD jemalloc（未装回退 MALLOC_ARENA_MAX=2），删对照实验矩阵 |
| 1.5 | 2026-10-28 | review 修订：native 分配器环境变量默认注入（MALLOC_ARENA_MAX=2 + trim/jemalloc 对照项），RSS 构成口径（MaxDirectMemorySize 与 arena 协同），记录义务扩展 |
| 1.4 | 2026-10-28 | review 修订：压测拓扑架构图（进程/连接/采样路径）、JVM 参数口径（默认参数集表 + 覆盖透传 + 记录义务 + 单变量对照纪律）、预判 #9 GC 停顿污染延迟 |
| 1.3 | 2026-10-28 | review 修订：规模参数两层化（逻辑规模 S×I×P×Q + 物理布局拓扑/driver JVM 数），资源模型判定公式（进程清单 × heap 预算 ×1.2 + CPU 进程数上限），档位表全维度展开含内存分解 |
| 1.2 | 2026-10-28 | review 修订：规模档位体系（dev/small/medium/large/custom + 环境适配）、开发机保护（默认 small 档、逐档递增、heap 预算、内存预检）、预判瓶颈×评估方法×优化方向八条（诊断导向） |
| 1.1 | 2026-10-28 | 设计定稿：工具形态（bench 模块 + bench.sh 编排）、场景矩阵 S1–S4、拓扑、指标口径、容量基线文档结构 |
| 1.0 | 2026-10-28 | 开工骨架 |
