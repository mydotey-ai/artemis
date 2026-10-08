# 架构图索引

`docs/legacy/arch/diagrams/` 目录的索引。本目录存放 [arch/](../README.md) 架构视图文档所用的图。

**HTML 是唯一真源，SVG 是导出物。** 改图改 `.html`，然后跑 `./export.sh` 重导全部 SVG（该脚本调用 diagram-design skill 的 `export_svg.py`，并补上其内置字体清单缺失的 `Noto Sans SC`）。文档里嵌入的是 `.svg`；`.html` 供再编辑与浏览器打开查看。

图由 [diagram-design](https://github.com/cathrynlavery/diagram-design) skill 生成（默认 skin：paper `#f5f5f5` / ink `#2d3142` / accent `#eb6c36`）。每张图的复杂度预算：节点 ≤9、箭头 ≤12、珊瑚色元素 ≤2 —— 四张图均已按此自查。

**SVG 的一个已知限制**：Markdown 用 `<img>` 引用 SVG 时，浏览器按 secure-static 处理，**不加载 SVG 内的 `@import` 外部字体**，中文标签退回查看器本地的 CJK 字体。字体栈已按覆盖面从宽排列（`Noto Sans SC` → `Noto Sans CJK SC` → `Source Han Sans SC` → `PingFang SC` → `Hiragino Sans GB` → `Microsoft YaHei` → `WenQuanYi Micro Hei` → `sans-serif`），macOS / Windows / 主流 Linux 桌面均命中。万一是无任何 CJK 字体的环境（精简容器、部分 CI），标签会显示为方块——那时请直接打开对应的 `.html`，页内 `<link>` 会正常拉取 Google Fonts。

| 图 | 用于 | 说明 |
|---|---|---|
| [overview-architecture.html](overview-architecture.html) / [.svg](overview-architecture.svg) | [../../arch.md](../../arch.md) §2 | **部署全景**：宿主进程内的客户端 SDK、artemis-server 单进程的三层、管理面独占的 MySQL/SQLite、region 内的 peer 副本，以及请求 / 推送 / 复制 / 管理四条连线 |
| [structure-dependencies.html](structure-dependencies.html) / [.svg](structure-dependencies.svg) | [../structure.md](../structure.md) §3.1 | **包级依赖与唯一的环**：discovery / registry / cluster / status / registry.replication 的依赖方向，珊瑚色只用在 `cluster ⇄ registry.replication` 这两条环边上 |
| [runtime-startup-gate.html](runtime-startup-gate.html) / [.svg](runtime-startup-gate.svg) | [../runtime.md](../runtime.md) §3.2 | **启动门控与就绪循环**：两个 readiness 目标的判定顺序、不达标回到 1s 循环、空集群死锁 |
| [runtime-node-state.html](runtime-node-state.html) / [.svg](runtime-node-state.svg) | [../runtime.md](../runtime.md) §4.1 | **节点状态机**：UNKNOWN → STARTING → UP，force-down 打进 DOWN、force-up 是其唯一出口；标出 UP 无回退、DOWN 粘性 |

## 未配图的视图

`deployment.md`、`quality.md`、`decisions.md` 未配图，原因逐条记录：

- **deployment**：该视图的内容是「单元对齐表 + 配置三层模型」，属表格形态；其唯一的视觉论点（每节点持有 region 全量副本、扇出 ∝ N−1）已由 overview-architecture 的 `peer ×(N−1)` 节点与复制通道承载。按 diagram-design 的 Deployment 类型规范，「把逻辑架构加主机名重画一遍」是该类型明列的 anti-pattern，故不做。
- **quality**：容量模型的论点是公式与数量级，柱状图只会稀释它。
- **decisions**：ADR 是逐条论证，无视觉论点。
