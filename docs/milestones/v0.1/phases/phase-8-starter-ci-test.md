# Phase 8 测试：Spring Boot starter + CI + Dockerfile

版本: 1.5    更新时间: 2026-10-19

## 测试方案

**形态：单测**（starter 模块，surefire `*Test`，fork JDK 8）。用 `ApplicationContextRunner`（`spring-boot-test`，test scope）在进程内起最小 Spring 容器验证自动装配：bean 条件、配置经 Spring `Environment` → SCF 的绑定、初始化/销毁生命周期、`MeterRegistry` 注入与回落、双注册资源存在性。用例指向不可达地址（`127.0.0.1:1`）即可——client 建连与心跳均为异步，测试只断言装配与生命周期状态，不依赖网络；无需起 registry-server（starter 不能依赖服务端模块，enforcer 边界不变）。

CI 与镜像构建不是 JUnit 用例：CI 绿与 `docker build` 成功由 ⑨ 人工验证（本地执行 + CI 运行结果）覆盖，dev-test.sh 模式无需扩展。

## 用例清单

| 用例 | 场景 | 形态 | 结果 |
|---|---|---|---|
| should_create_started_client_when_addresses_configured | 配置 `serverAddresses` 后容器启动即 `start()`（再次 start 报 already started），容器关闭即 `close()`（register 抛 closed） | 单测 | pass |
| should_inject_boot_meter_registry_when_present | 容器内存在 `SimpleMeterRegistry` bean 时，client 指标注册到该 registry（非 global） | 单测 | pass |
| should_fallback_to_global_registry_when_no_meter_registry | 无 `MeterRegistry` bean 时指标回落 `Metrics.globalRegistry` | 单测 | pass |
| should_fail_fast_when_server_addresses_missing | 未配置必配地址时容器启动失败，根因含 `artemis.client.serverAddresses must be set` | 单测 | pass |
| should_not_create_client_when_enabled_false | `artemis.client.enabled=false` 时无 `ArtemisClient` bean | 单测 | pass |
| should_allow_host_override_when_custom_client_bean_present | 宿主自定义 `ArtemisClient` bean 时自动装配不覆盖 | 单测 | pass |
| should_register_auto_configuration_in_both_boot_registries | `spring.factories` 与 `AutoConfiguration.imports` 均登记自动配置类（SB 2.7+ / 老 2.x 双覆盖） | 单测 | pass |
| should_resolve_relaxed_key_variants | kebab-case、SDK 大写蛇形、小写蛇形三种键写法均归一解析到 camelCase 键 | 单测 | pass |

## 验证清单（人工，⑨ 填）

| # | 命令 / 步骤 | 预期 | 结论 |
|---|---|---|---|
| 1 | `java/scripts/dev-test.sh all` | BUILD SUCCESS；starter `Tests run: 8, Failures: 0`；全 reactor 12 模块 SUCCESS | 通过（用户确认） |
| 2 | `docker build -t artemis-registry-server:dev .` | 多阶段构建成功（构建环境需可用 Docker daemon；本机当前 daemon 未启动，由 CI `docker` job 等效验证） | 通过（修复后 CI docker job 绿） |
| 3 | `docker run --rm -d --name artemis-dev -p 8080:8080 -p 9090:9090 artemis-registry-server:dev`，等待数秒后 `curl -sf http://localhost:8080/readyz` | 返回 `ready`（`/readyz` 无认证白名单）；随后 `docker exec artemis-dev sh -c 'test -w /app/data'` 退出码 0（快照目录可写）；`docker stop artemis-dev` | 可选运行时冒烟，未执行（本 phase 验收口径为镜像构建成功；运行形态验收归 v1.0） |
| 4 | ⑪ 推送 main 后查看 GitHub Actions `CI` 运行 | `java` job 绿（JDK 25 + toolchains JDK 8、spotless/enforcer/测试）与 `docker` job 绿（镜像构建） | 通过（run 39241442907：两 job 均 success） |

## 回归记录（⑧ 填，经 dev-test.sh）

| 轮次 | 模式 | 范围 | 结果 |
|---|---|---|---|
| 1（⑤ 首跑） | all | 全 reactor（12 模块） | 首跑仅 starter 两文件 spotless 违规；`spotless:apply` 后重跑全绿（7/7 新测，全 reactor BUILD SUCCESS） |
| 2（⑧ 回归） | all | 全 reactor（12 模块） | code-review 修复后全绿（8/8 starter 单测，全 reactor BUILD SUCCESS） |

## 更新历史

| 版本 | 日期 | 变更说明 |
| ------ | ------ | ------ |
| 1.5 | 2026-10-19 | CI 修复后复验：java/docker 双 job 绿（run 39241442907）；运行时冒烟归 v1.0 |
| 1.4 | 2026-10-19 | CI 首轮 docker job 失败（test-jar 缺失），回退 `-DskipTests`；验证结论按实际状态修正 |
| 1.3 | 2026-10-19 | ⑨ 人工验证结论（1–3 通过；CI 待推送后确认） |
| 1.2 | 2026-10-19 | ⑦ review 修复补 `should_resolve_relaxed_key_variants`（配置键多写法归一） |
| 1.1 | 2026-10-19 | ③ 测试设计定稿：7 个装配单测用例 + CI/镜像人工验证口径 |
| 1.0 | 2026-10-19 | 开工骨架 |
