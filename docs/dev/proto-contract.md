# Proto 契约规范

版本: 1.6    更新时间: 2026-10-28

> 本文是 Artemis 各语言实现（Java / Rust / …）共同遵守的 proto 契约规范：契约组织、wire 兼容规则与变更流程。语言无关，是跨语言不变量的**单一来源**——各语言开发规范（如[Java 开发规范](coding-guidelines-java.md) §8）只保留本语言的生成代码使用纪律并引用本文，不复制规则本身。

## 1. 契约组织

- 契约居仓库顶层 `proto/` 目录，六组：`common/`（公共数据模型）+ `registry/`、`replication/`、`projection/`、`discovery/`、`management/`（各 v1）。
- `proto/` 是**语言无关纯目录**：不属于任何构建体系（非 Maven 模块、无 Cargo crate 产物）、不产 artifact；仓库 git tag 即契约版本，与各语言制品版本同源对齐（Java reactor 的 `${revision}` 与 tag 一致，SDK 版本即其携带契约的版本）。**git tag 启用前**，契约版本锚点 = 各版本收尾 commit（`java/scripts/proto-compat-check.sh` 锚点表集中维护，v1.0 phase-5 起 wire 兼容纪律经此脚本在 `dev-test.sh all`/CI 强制）。
- 各语言实现按需引用 .proto 文件、各自生成代码（Java 生成矩阵见[Java 技术选型](tech-stack-java.md) §2.2；Rust prost/tonic 基线 v1.x 定）。同一 proto 组在多端（语言/独立编译单元）生成的类互不共享，**跨端只走 wire 协议序列化，不传递生成类**；语言内部的模块间共享边界由各语言规范定义（Java 见技术选型 §2.2）。
- import 方向永远「内部协议 → 共享」（各组 import common），不出现环。

## 2. wire 兼容规则

- **field number 只增不改**；弃用字段标 `deprecated` 后删除时必须 `reserved`（编号与名字都保留）。
- 禁 `required`、禁改已有字段类型/编号、新增字段必须 optional 且老代码容忍未知字段（protobuf 默认满足，注意别依赖「缺省值 = 未设置」做语义）。
- **数据枚举（状态类）走 proto enum**：各端对未知枚举值统一映射本地 UNKNOWN 哨兵（与服务端版本解耦）。
- **错误码走 `ErrorInfo` 消息**（`string code` + `string message`，命名避开 `java.lang.Error` 通配 import 歧义）：code 为本节错误码注册表内稳定字符串——大写蛇形命名、只增不改、永不重命名、全字符串精确匹配（禁前缀/解析）；message 仅人类可读排障描述。原产品「字符串状态码漂移」教训的漂移源是无契约散落字符串而非字符串本身，以注册表纪律消解；未知 code 对各端自描述可见可比（老 SDK 无需映射）。
- **Response 统一规范形式 `status + data`**（proto 无泛型，以约定落地）：每个 Response 固定两字段——`status`（common `ResponseStatus`：`ack` 二态 SUCCESS/FAIL + `error`，成败由 ack 判定，任一失败即 FAIL、明细与范围经 data 携带）、`data`（该操作的返回数据消息，命名 `XxxResponseData`，独立演化）；失败明细结构随请求对齐（service 单位请求按 service 分组，实例键平铺请求保持平铺）。

**错误码注册表**（单一来源在本节；各端语言内可再封装常量/枚举，未知码保留原样字符串）：

| code | 语义 |
|---|---|
| INVALID_ARGUMENT | 实例数据校验失败 |

- **契约冻结范围**（架构 §3「数据面 proto」）：common/registry/replication/projection/discovery 五组——common.v1 是公共 wire 契约，与数据面各组同受冻结约束：v0.2–v1.0 期间可改但必须向后兼容，冻结后只增不改。management.v1（治理面）不在此列，随治理期版本演进。

## 3. 变更流程

- proto 变更走 review，**一次 commit 原子带动所有语言实现同步修改**（monorepo 契约治理的生命线，不分仓分批跟进），变更说明列出影响的语言与模块。
- **不存在允许不兼容变更的窗口**：v0.1 首版定稿前不受兼容约束；v0.2 起所有变更必须向后兼容（§2），冻结后只增不改。
- 各端 protoc/生成器版本独立锁定（见各语言技术选型，如[Java 技术选型](tech-stack-java.md) §2.2），升级生成器不改变 wire 兼容义务。

## 更新历史

| 版本 | 日期 | 变更说明 |
| ------ | ------ | ------ |
| 1.6 | 2026-10-28 | §1 补契约版本锚点落地口径：git tag 启用前 = 版本收尾 commit（proto-compat-check.sh 强制 wire 兼容纪律，v1.0 phase-5） |
| 1.5 | 2026-10-12 | code-review 修复：错误码消息名 Error→ErrorInfo（避开 java.lang.Error 通配 import 歧义）；§1 Java 内联生成细节改指针 |
| 1.4 | 2026-10-12 | 注册表纪律补大写蛇形；表格后列表结构修复 |
| 1.3 | 2026-10-11 | §1 跨端/语言内共享边界拆分表述（跨端只走 wire；语言内边界由各语言规范定义，Java 服务端 common.v1 经 artemis-common 共享） |
| 1.2 | 2026-10-11 | v0.1 phase-1 契约设计定案：§2 状态码治理拆分为数据枚举（proto enum + UNKNOWN 哨兵）与错误码（`Error` 消息 string code 契约注册表，替换「全走 proto enum」）；新增 Response 统一规范（status + data）与错误码注册表（D12 关联） |
| 1.1 | 2026-10-10 | review 修复：§2 冻结范围恢复五组明确枚举（management.v1 显式排除，修正 1.0「与各组」的 scope 漂移）；§3 消除与 §2 的矛盾（明确不存在允许不兼容变更的窗口）；契约版本与 git tag/${revision} 的同源关系补说明；删除语言无关文档中的 Java protoc 具体版本（改指向各语言技术选型） |
| 1.0 | 2026-10-10 | 初版：自 Java 开发规范 §8 上移跨语言不变量（wire 兼容规则、契约冻结范围），新增契约组织（顶层 `proto/` 纯目录）与变更流程（monorepo 原子变更） |
