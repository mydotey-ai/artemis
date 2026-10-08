# 原产品文档索引

原产品（`~/Projects/mydotey/artemis`，version 2.0.2，只读参考）调查文档，重设计的需求输入。文档分三层：**规格层**（需求语言，新产品直接输入）→ 事实层（实现清单 / 架构视图，取证来源）→ 判断层（资产 / 局限，取舍依据）。入口：[product-overview.md](product-overview.md)。

| 文档 | 状态 | 说明 |
|---|---|---|
| [product-overview.md](product-overview.md) | 草案 | **产品级总览**：域地图 / 横切主题（错误码 / region-zone / 双轨 / 限流）/ 端到端场景 / 基线勘误汇总 / 新发现缺陷索引 / **待验证清单（复刻的能力空洞）** / 旧文档处置 |
| [nfr-spec.md](nfr-spec.md) | 草案 | **非功能性需求规格**（41 条需求语言 + 量化目标 + 满足度 + ⚠ 重立标记） |
| [domains/README.md](domains/README.md) | — | **业务域规格层索引**（6 域 × spec + logic 共 12 份 + 契约制品 5 份：data-model / api-contract / db-schema / client-sdk-api / config-reference） |
| [legacy-product-analysis.md](legacy-product-analysis.md) | 定稿 | 判断层：能力全景基线（§5 可继承资产 12 条、§6 局限 23 条、§8 规格层勘误与增补） |
| [features.md](features.md) | 草案 | 事实层：实现清单（REST 77 路径 + WS 3 端点全量、配置项、DAO/表；含 ⚠ 关键事实标注），规格层取证附录 |
| [arch.md](arch.md) | 草案 | 事实层：**架构总览入口**（风格判定 / 整体架构与部署全景 / 视图地图 / 机制索引 / 关键架构约束清单） |
| [arch/README.md](arch/README.md) | — | 事实层：**架构视图集索引**（结构 / 运行时 / 部署 / 质量与容量 / 决策记录 五份视图文档） |
