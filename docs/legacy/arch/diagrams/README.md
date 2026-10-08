# 架构图索引

`docs/legacy/arch/diagrams/` 目录的索引。本目录存放 [arch/](../README.md) 架构视图文档所用的图。

**HTML 是唯一真源，SVG 是导出物。** 改图改 `.html`，然后跑 `./export.sh` 重导全部 SVG（该脚本调用 diagram-design skill 的 `export_svg.py`，并补上其内置字体清单缺失的 `Noto Sans SC`）。文档里嵌入的是 `.svg`；`.html` 供再编辑与浏览器打开查看。

图由 [diagram-design](https://github.com/cathrynlavery/diagram-design) skill 生成（默认 skin：paper `#f5f5f5` / ink `#2d3142` / accent `#eb6c36`）。每张图的复杂度预算：节点 ≤9、箭头 ≤12、珊瑚色元素 ≤2（sequence 另有：lifeline ≤5、message ≤12、fragment ≤1）—— 十张图均已按此自查并通过 skill 的 `self_check.py`。

**SVG 的一个已知限制**：Markdown 用 `<img>` 引用 SVG 时，浏览器按 secure-static 处理，**不加载 SVG 内的 `@import` 外部字体**，中文标签退回查看器本地的 CJK 字体。字体栈已按覆盖面从宽排列（`Noto Sans SC` → `Noto Sans CJK SC` → `Source Han Sans SC` → `PingFang SC` → `Hiragino Sans GB` → `Microsoft YaHei` → `WenQuanYi Micro Hei` → `sans-serif`），macOS / Windows / 主流 Linux 桌面均命中。万一是无任何 CJK 字体的环境（精简容器、部分 CI），标签会显示为方块——那时请直接打开对应的 `.html`，页内 `<link>` 会正常拉取 Google Fonts。

| 图 | 用于 | 说明 |
|---|---|---|
| [overview-architecture.html](overview-architecture.html) / [.svg](overview-architecture.svg) | [../../arch.md](../../arch.md) §2 | **整体架构与部署全景（图集入口）**：宿主进程内的客户端 SDK（标注缓存永不失效、三级地址容灾）、artemis-server 单进程的三层、管理面独占的 MySQL/SQLite、region 内的 peer 副本与请求 / 推送 / 复制 / 管理四条连线；虚线框表达双轨分离（数据面 KERNEL+peer 纯内存零 DB / 管理面 MGMT+DB 可整体关闭），peer 区标注 region=集群边界、zone=准入 |
| [structure-components.html](structure-components.html) / [.svg](structure-components.svg) | [../structure.md](../structure.md) §2 | **核心组件与关系（组件全景）**：宿主进程内 registry-client（持有 instance 事实源，珊瑚）与 discovery-client；服务端 registry-service → discovery-service 变更事件、→ peer 复制（珊瑚），cluster 读写双向门控，management-service 经 filter 注入与合成伪实例作用于数据面、DAO 读写 DB；边注声明接入层与 peer 探测边的省略 |
| [structure-dependencies.html](structure-dependencies.html) / [.svg](structure-dependencies.svg) | [../structure.md](../structure.md) §3.1 | **包级依赖与唯一的环**：discovery / registry / cluster / status / registry.replication 的依赖方向，珊瑚色只用在 `cluster ⇄ registry.replication` 这两条环边上 |
| [structure-data-ownership.html](structure-data-ownership.html) / [.svg](structure-data-ownership.svg) | [../structure.md](../structure.md) §4.1 | **数据所有权：事实源与投影**：客户端本地实例集是唯一事实源（珊瑚色），服务端注册表与 peer 副本均为纯内存投影，发现视图是过滤器链派生、消费方缓存为快照（永不失效），管理面 DB 是唯一持久化权威经轮询缓存注入过滤器 |
| [runtime-startup-gate.html](runtime-startup-gate.html) / [.svg](runtime-startup-gate.svg) | [../runtime.md](../runtime.md) §3.2 | **启动门控与就绪循环**：两个 readiness 目标的判定顺序、不达标回到 1s 循环、空集群死锁 |
| [runtime-node-state.html](runtime-node-state.html) / [.svg](runtime-node-state.svg) | [../runtime.md](../runtime.md) §4.1 | **节点状态机**：UNKNOWN → STARTING → UP，force-down 打进 DOWN、force-up 是其唯一出口；标出 UP 无回退、DOWN 粘性 |
| [scenario-register-heartbeat.html](scenario-register-heartbeat.html) / [.svg](scenario-register-heartbeat.svg) | [../runtime.md](../runtime.md) §7.1 | **注册与心跳端到端（心跳即注册）**：注册只入本地集 → 心跳 miss 返回 data-not-found → 补注册建租约（珊瑚色主线）→ 复制 + 推送 NEW 可见；LOOP 稳态 5s 心跳 + 批量复制；注记首次可见 ≈5–6s |
| [scenario-discovery-subscribe.html](scenario-discovery-subscribe.html) / [.svg](scenario-discovery-subscribe.svg) | [../runtime.md](../runtime.md) §7.2 | **发现与订阅推送**：首次 lookup 拿派生视图（珊瑚色返回）→ WS 订阅 → 增量推送 at-most-once；ALT 三层兜底（60s 批量 / 15min 全量纠偏）；注记「纠偏是全量不是单条重发」 |
| [scenario-replication-fanout.html](scenario-replication-fanout.html) / [.svg](scenario-replication-fanout.svg) | [../runtime.md](../runtime.md) §7.3 | **对等复制：双通道去重与批量扇出**：单条 / 批量双通道，5ms 去重合并、成批 250/2s，POST 批量心跳（珊瑚色）到 peer 幂等覆盖；ALT 失败处置（reaccept 插队 / TTL 5s 丢弃）；注记扇出门控与写放大摊销 |
| [scenario-expiry-selfprotection.html](scenario-expiry-selfprotection.html) / [.svg](scenario-expiry-selfprotection.svg) | [../runtime.md](../runtime.md) §7.4 | **失联剔除与自我保护**：租约过期粘滞 → clean tryLock → ALT safe 摘除 + 推送 DELETE（珊瑚色）/ unsafe 跳过保留；注记「各节点独立重复」「显式 evict 跳过保护」 |

## 未配图的视图

`deployment.md`、`quality.md`、`decisions.md` 未配图，原因逐条记录：

- **deployment**：该视图的内容是「单元对齐表 + 配置三层模型」，属表格形态；其唯一的视觉论点（每节点持有 region 全量副本、扇出 ∝ N−1）已由 overview-architecture 的 `peer ×(N−1)` 节点与复制通道承载。按 diagram-design 的 Deployment 类型规范，「把逻辑架构加主机名重画一遍」是该类型明列的 anti-pattern，故不做。
- **quality**：容量模型的论点是公式与数量级，柱状图只会稀释它。
- **decisions**：ADR 是逐条论证，无视觉论点。
