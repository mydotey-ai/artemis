# Phase 1 测试：Maven 脚手架 + proto 契约骨架

版本: 1.10    更新时间: 2026-10-12

## 测试方案

脚手架 phase 无业务逻辑，测试分两层：

1. **构建级断言**（verify 生命周期一体）：spotless:check、enforcer（版本/依赖上界/banned）、编译、各模块测试全过——即验收的「verify 全绿」。占位 proto 组（replication/projection/discovery/management 无消息定义、无可引用类）经「生成失败→构建失败」机制由此层隐含覆盖。
2. **模块级冒烟单测**：正向用例逐模块引用 v0.1 定稿组（common.v1/registry.v1）的生成类断言语义（引用即验证该模块生成/依赖矩阵，设计 §3）；Spring 上下文可启动；client 生成类在 Java 8 JVM 运行；无生成任务的模块（starter、纯骨架模块）按设计 §6「每模块至少一个可运行测试」给占位用例。

无集群语义测试（guidelines §7 的多实例测试随 phase-4 起）。

构建期行为无法以正向单测表达，列入 ⑨ 人工验证清单（预定五项）：enforcer 违规拒绝（负向注入实验：core 加 Spring 依赖、client 加 artemis-common 依赖各应构建失败）、toolchains JDK 8 前提确认、flatten 产物 `${revision}` 解析检查、各模块 proto 生成目录矩阵检查（includes 圈定组不缺不多）、`dev-test.sh` 三模式与 `/dev-java-test` command 可用性（设计 §7：默认 all、unit 可执行、integration 无测试时行为符合预期）。

测试产出形态：单元测试代码（各模块 `src/test/java`）+ **自动化回归入口 `java/scripts/dev-test.sh`**（模式与形态↔模式映射单一来源见设计 §7 与 dev-java ⑤；本 phase 用例全为单测 → unit/all）。测试报告无独立文档，以本文档三节为载体（用例「结果」列 ⑤ 填、「回归记录」⑧ 填、「验证清单」⑨ 填）。

## 用例清单

| 用例 | 场景 | 形态 | 结果 |
|---|---|---|---|
| should_generate_common_proto_when_building_artemis_common | artemis-common 生成 common.v1 类可引用且语义正确（ServiceKey 默认值、ResponseStatus 默认 ack=SUCCESS） | 单测 | pass（all 全绿） |
| should_generate_registry_proto_when_building_registry_core | registry-core 生成 registry.v1 类可引用（HeartbeatRequest 默认实例），common.v1 经 artemis-common 依赖可用 | 单测 | pass（all 全绿；含 grpc stub 生成链路断言） |
| should_generate_registry_proto_when_building_management_core | management-core 生成矩阵含 registry.v1（读注册表视图），生成类可引用 | 单测 | pass（all 全绿） |
| should_reference_common_proto_when_building_discovery_core | discovery-core 经 Maven 依赖引用 artemis-common 的 common.v1 类（discovery.v1/projection 为占位组无可引用类，生成链路由构建级断言隐含覆盖） | 单测 | pass（all 全绿） |
| should_generate_common_and_registry_proto_when_building_client | client（protoc 3.25.x、Java 8 语法）自生成 common.v1/registry.v1 类可引用（discovery.v1 占位组隐含覆盖），测试经 toolchains fork JDK 8 JVM 执行 | 单测 | pass（all 全绿；测试 JVM 1.8.0_504 经 surefire 报告验证） |
| should_load_application_context_when_server_starts | 三个 server 模块 Spring 上下文各自可启动（contextLoads） | 单测 | pass（all 全绿；三个 server 各一条） |
| should_run_placeholder_test_when_building_starter | starter 模块含可运行占位测试（设计 §6 每模块至少一测），构建通过 | 单测 | pass（all 全绿；starter→client 依赖链断言） |

## 验证清单（人工，⑨ 填）

构建期行为无法以正向单测表达，逐条人工执行；负向注入实验完成后还原 pom。

| # | 命令 / 步骤 | 预期 | 结论 |
|---|---|---|---|
| 1 | 在 `java/artemis-registry-core/pom.xml` 临时加 `org.springframework.boot:spring-boot-starter` 依赖 → `mvn -f java/pom.xml validate -pl artemis-registry-core` → 还原 | 构建失败，`bannedDependencies` 报 `org.springframework:*` | ✅ 通过：`Rule 0 BannedDependencies failed`，`spring-core/spring-context:7.0.9 banned` |
| 2 | 在 `java/artemis-client/pom.xml` 临时加 `artemis-common` 依赖 → `mvn -f java/pom.xml validate -pl artemis-client` → 还原 | 构建失败，`bannedDependencies` 报 `org.mydotey.ai.artemis:artemis-common` | ✅ 通过（排除传递依赖后）：`Rule 0 BannedDependencies failed`，`artemis-common:0.1.0-SNAPSHOT banned`。附注：不排除传递时先撞 `requireUpperBoundDeps`（protobuf 4.36.2 vs 3.25.9）——同为构建失败，安全性未受损 |
| 3 | `java/scripts/dev-test.sh unit` → `grep -o 'java.version" value="[^"]*"' java/artemis-client/target/surefire-reports/TEST-*.xml` | client 报告 `1.8.0_504`（toolchains fork JDK 8）；其余模块报告 `25.0.4` | ✅ 通过：client=`1.8.0_504`，artemis-common=`25.0.4` |
| 4 | `mvn -f java/pom.xml process-resources -q` → `grep -c '\${revision}' java/.flattened-pom.xml java/*/.flattened-pom.xml` | 无 `${revision}` 残留；各 `.flattened-pom.xml` 的 `<version>` 为 `0.1.0-SNAPSHOT` | ✅ 通过：`${revision}` 占位符计数全 0；`<version>0.1.0-SNAPSHOT</version>`（`<revision>` property 定义保留、值已解析，正常） |
| 5 | `for m in artemis-common artemis-registry-core artemis-discovery-core artemis-management-core artemis-client; do echo -n "$m: "; find java/$m/target/generated-sources -type d -path "*artemis/proto/*/v1" \| sed 's\|.*artemis/proto/\|\|;s\|/v1\|\|' \| sort -u \| tr '\n' ' '; echo; done` | common: `common projection`；registry-core: `registry replication`；discovery-core: `discovery`；management-core: `management registry`；client: `common discovery registry`（与 tech-stack §2.2 矩阵一致，不缺不多） | ✅ 通过：逐模块与矩阵完全一致 |
| 6 | `java/scripts/dev-test.sh unit`；`… integration`；`… all`；`java/scripts/dev-test.sh bogus; echo exit=$?` | 前三者成功（integration 无 `*IT` 时亦成功）；`bogus` 打印 usage 且 `exit=2` | ✅ 通过：三模式均 `exit=0`；`bogus` 打印 usage、`exit=2`；多参数亦 `exit=2` |

## 回归记录（⑧ 填，经 dev-test.sh）

| 轮次 | 模式 | 范围 | 结果 |
|---|---|---|---|
| 1 | all | 全 reactor（`mvn verify`） | SUCCESS：10 模块 BUILD SUCCESS，9 用例全过 |
| 2 | all | 全 reactor（⑦ 修复后回归） | SUCCESS：10 模块全绿 |

## 更新历史

| 版本 | 日期 | 变更说明 |
| ------ | ------ | ------ |
| 1.10 | 2026-10-12 | ⑨ 验证清单 6 项全部执行通过并记录结论 |
| 1.9 | 2026-10-12 | ⑧ 回归记录两轮全绿；⑨ 验证清单就绪（6 项，待人工执行） |
| 1.8 | 2026-10-12 | ⑤ 用例清单结果全量 pass（dev-test.sh all 全绿，client fork JDK 8 验证） |
| 1.7 | 2026-10-12 | code-review 修复：产出形态段指针化（映射单一来源设计 §7 / dev-java ⑤，消除四处重复已现分歧） |
| 1.6 | 2026-10-12 | review 定案：对齐 dev-java 工作流 dev-test.sh 集成——产出形态补形态↔模式映射、回归记录表加「模式」列（经 dev-test.sh 执行） |
| 1.5 | 2026-10-12 | review 定案：command 更名 /dev-java-test；脚本默认模式改 all |
| 1.4 | 2026-10-12 | review 定案：测试产出形态更新——自动化回归入口 java/scripts/dev-test.sh（unit/integration/all）+ /dev-test command；人工验证清单增至五项（补脚本与 command 可用性） |
| 1.3 | 2026-10-12 | review 修订：补 discovery-core 生成用例、client 用例矩阵补全 discovery.v1、starter 占位用例（设计 §6 每模块一测）；人工验证清单预列四项（enforcer 负向注入、toolchains 前提、flatten 解析、生成目录矩阵检查） |
| 1.2 | 2026-10-11 | 用例随 proto 生成模型分治调整（common.v1 生成用例移至 artemis-common，core 用例改为经依赖引用验证） |
| 1.1 | 2026-10-10 | 测试方案与用例清单定稿，待 review |
| 1.0 | 2026-10-10 | 骨架（测试设计待 ③ 定稿） |
