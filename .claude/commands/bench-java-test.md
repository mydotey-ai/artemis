# bench-java-test：容量压测执行

启动压测环境并执行压测（`java/scripts/bench.sh` 的会话侧入口，v1.0 phase-6 交付）。出数、汇总、呈现结果一站式；工具参数语义与档位矩阵单一来源见 `docs/milestones/v1.0/phases/phase-6-capacity.md`，容量口径见 `docs/deploy/capacity-baseline.md`。

## 用法

```text
/bench-java-test                                  # 默认：small 档、单拓扑、s1,s2,s4
/bench-java-test --profile dev --scenarios s4     # dev 档快速验证订阅推送
/bench-java-test --profile small --topologyNodes 3 --scenarios s4   # 3 节点复制拓扑
/bench-java-test <bench.sh 其余参数原样透传>        # --gc zgc / --jvm-args-* / --rampSec 等
```

## 执行流程

### 1. 前置说明

- bench.sh 自带：JDK 25 探测兜底（sdkman current）、jemalloc 强制预检（未装拒绝并提示 `apt install libjemalloc2`）、内存预检（进程清单 × heap 预算 ×1.2，不足拒绝）、产物缺失自动构建（`-DskipTests`）。
- **逐档递增纪律**：dev → small → large；large 前确认 small 资源曲线无异常。large（10 万实例）需 ≥16C/32G 独立机，16G 开发机会被预检拒绝（预期行为）。
- 中断恢复：被外层时限杀掉时进程经 trap 清理；未完成场景重跑该场景即可（事件 CSV 按场景命名不互相污染，s1 起新拓扑冷启动）。

### 2. 启动压测（后台 + 监听，勿前台阻塞）

预计时长 = 场景数 × (ramp+duration+静默) + 构建与拓扑准备；small 档全场景 ≈ 33 分钟：

- `run_in_background` 执行 `java/scripts/bench.sh <参数>`，stdout/stderr 落 `/tmp/bench-<profile>.log`。
- 后台命令有 **30 分钟上限**：small 全场景/3 节点轮按场景拆分多次启动（`--scenarios s4` 单场景单独起），或到点被杀后**补跑未完成场景**（看 `bench-report/<ts>/summary.txt` 已完成的 `scenario ... finished` 行判断进度）。
- Monitor 布防完成信号：`until ! pgrep -f bench.sh; do sleep 20; done` 后取 summary。

### 3. 结果提取（口径防呆——勿踩已知的坑）

数据在 `java/scripts/bench-report/<时间戳>/`：

| 数据 | 提取方式 | 防呆 |
|---|---|---|
| 场景完成态 | `summary.txt` | `FAILED`/`WATCHDOG`/`MAX-TOTAL` 行 = 失败标记，bench.sh 退出码非 0 |
| 实例到位（S1） | `awk '/^### /{ts=$2} /^artemis_registry_instances /{...}' samples-server-s1.txt` 序列 | **instances 是无标签单值**；勿取尾部（场景结束后 driver 优雅退出会注销实例→0），取**峰值/首达时间** |
| 心跳续约率（S2） | heartbeat_instances_total **首末差 / 时长** | 单窗口差分有抖动；理论值 = P/心跳周期 × 每进程实例数，对照 |
| 推送延迟（S4） | `summary.txt` 的 push-latency 行 + `latency-s4.csv` 全样本 | per-subscriber 配对；双峰分布看 p90/p95/p99 分位 |
| 副本一致性（3n） | samples 内 `# node-i` 标记行 + instances 峰值逐节点 | node 取标记行 `$2`；勿 curl 即时值（注销后为 0） |
| heap/GC | samples 的 `garbage-first heap` 行 + `gc-*.log` Pause 计数 | jcmd heap_info 已内嵌采样 |

### 4. 呈现（chat 汇总表）

- 环境行（profile/拓扑/预算 vs 可用/重 JVM 数 vs 核数）+ 每场景一行（时长 + 关键数）+ S4 延迟分布 + 异常标记。
- 数据落基线文档不属本命令职责（出数进 `docs/deploy/capacity-baseline.md` 走 /dev-java 的 phase 流程）。

## 失败处置

| 症状 | 处置 |
|---|---|
| jemalloc 未装 | 按提示安装后重试（无回退是定案） |
| 内存预检拒绝 | 降档（large→small→dev）或换机；不要改预算绕过 |
| registry/discovery not ready（120s） | 看 `bench-report/<ts>/server-*.log` 尾部（端口占用/配置非法 fail-fast）；杀残留 `pgrep -af 'artemis.*exec.jar'` 后重试 |
| 场景 FAILED（driver 非零退出） | 看 `driver-<scenario>-*.log`；多为参数/端口问题，修后单场景重跑 |
| WATCHDOG 强杀 | driver 未按期退出（hang）——查 server 日志与 GC log，视作待归因发现记录 |
