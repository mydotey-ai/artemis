# Proto 契约规范

版本: 1.1    更新时间: 2026-10-10

> 本文是 Artemis 各语言实现（Java / Rust / …）共同遵守的 proto 契约规范：契约组织、wire 兼容规则与变更流程。语言无关，是跨语言不变量的**单一来源**——各语言开发规范（如[Java 开发规范](coding-guidelines-java.md) §8）只保留本语言的生成代码使用纪律并引用本文，不复制规则本身。

## 1. 契约组织

- 契约居仓库顶层 `proto/` 目录，六组：`common/`（公共数据模型）+ `registry/`、`replication/`、`projection/`、`discovery/`、`management/`（各 v1）。
- `proto/` 是**语言无关纯目录**：不属于任何构建体系（非 Maven 模块、无 Cargo crate 产物）、不产 artifact；仓库 git tag 即契约版本，与各语言制品版本同源对齐（Java reactor 的 `${revision}` 与 tag 一致，SDK 版本即其携带契约的版本）。
- 各语言实现按需引用 .proto 文件、各自生成代码（Java 生成矩阵见[Java 技术选型](tech-stack-java.md) §2.2；Rust prost/tonic 基线 v1.x 定）。同一 proto 组在多端生成的类互不共享，**跨模块/跨端只走 wire 协议序列化，不传递生成类**。
- import 方向永远「内部协议 → 共享」（各组 import common），不出现环。

## 2. wire 兼容规则

- **field number 只增不改**；弃用字段标 `deprecated` 后删除时必须 `reserved`（编号与名字都保留）。
- 禁 `required`、禁改已有字段类型/编号、新增字段必须 optional 且老代码容忍未知字段（protobuf 默认满足，注意别依赖「缺省值 = 未设置」做语义）。
- 状态/错误码全走 proto enum，不引入字符串状态码（继承原产品「字符串状态码漂移」教训，能力基线局限清单）。
- **契约冻结范围**（架构 §3「数据面 proto」）：common/registry/replication/projection/discovery 五组——common.v1 是公共 wire 契约，与数据面各组同受冻结约束：v0.2–v1.0 期间可改但必须向后兼容，冻结后只增不改。management.v1（治理面）不在此列，随治理期版本演进。

## 3. 变更流程

- proto 变更走 review，**一次 commit 原子带动所有语言实现同步修改**（monorepo 契约治理的生命线，不分仓分批跟进），变更说明列出影响的语言与模块。
- **不存在允许不兼容变更的窗口**：v0.1 首版定稿前不受兼容约束；v0.2 起所有变更必须向后兼容（§2），冻结后只增不改。
- 各端 protoc/生成器版本独立锁定（见各语言技术选型，如[Java 技术选型](tech-stack-java.md) §2.2），升级生成器不改变 wire 兼容义务。

## 更新历史

| 版本 | 日期 | 变更说明 |
| ------ | ------ | ------ |
| 1.1 | 2026-10-10 | review 修复：§2 冻结范围恢复五组明确枚举（management.v1 显式排除，修正 1.0「与各组」的 scope 漂移）；§3 消除与 §2 的矛盾（明确不存在允许不兼容变更的窗口）；契约版本与 git tag/${revision} 的同源关系补说明；删除语言无关文档中的 Java protoc 具体版本（改指向各语言技术选型） |
| 1.0 | 2026-10-10 | 初版：自 Java 开发规范 §8 上移跨语言不变量（wire 兼容规则、契约冻结范围），新增契约组织（顶层 `proto/` 纯目录）与变更流程（monorepo 原子变更） |
