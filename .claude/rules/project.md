# 项目工作规则

- 交流与文档一律使用中文，技术名词、代码标识符保留英文。
- 原产品仓库 `~/Projects/mydotey/artemis` 为**只读参考**，任何情况下不得修改；`.claude/settings.json` 已对其禁用 Write/Edit。
- 涉及原产品行为的结论必须以源码为证据，不得以原仓库 Readme 为依据（已知宣称与代码漂移，详见基线文档 §4）。
- 新产品的重大设计决策须对照 `docs/legacy/legacy-product-analysis.md` 的「可继承设计资产」（§5）与「局限清单」（§6）说明取舍。
- 文档规范以 `.claude/rules/doc.md` 为准；项目补充：原产品调查文档放 `docs/legacy/`（全局目录类型表外的专用目录），文档多语言组织见下节。

## 仓库目录结构（多语言 monorepo）

- 单 monorepo，顶层按语言分目录：`proto/`（契约）、`java/`（Maven reactor）、`rust/`（v1.x Cargo workspace）、`go/` 等按需。一种语言一个顶层目录，该语言的服务端与 SDK 同目录；根目录不放聚合构建文件，各语言构建入口在各自目录（Java 为 `mvn -f java/pom.xml`）。
- `proto/` 是语言无关纯目录：不属于任何构建体系（非 Maven 模块）、不产 artifact，契约版本跟仓库 git tag 走；各语言实现从 .proto 文件各自生成代码，组织、wire 兼容规则与变更流程见 `docs/dev/proto-contract.md`（跨语言单一来源）。
- CI 按路径触发：`java/**` 跑 Java job、`rust/**` 跑 Rust job；`proto/**` 变更触发全量（全量已含各语言 job，不重复单独触发）。

## 文档多语言组织

- 语言无关内容（架构、协议、数据模型、部署、路线图）只写一份，不分语言。
- 语言相关文档文件名带语言后缀（如 `tech-stack-java.md`、`coding-guidelines-rust.md`），`docs/dev/README.md` 按「共享 / 各语言」分组登记；各 SDK 使用指南同理（如 `api/sdk-java.md`）。`.claude/rules/` 语言技术规则同惯例：`tech-<lang>.md`（如 `tech-java.md`；`tech-rust.md` 于 v1.x 引入）。
- 跨语言不变量（wire 兼容规则、契约变更流程）单一来源在 `docs/dev/proto-contract.md`，各语言规范只写本语言使用纪律并引用之，不复制规则本身。
