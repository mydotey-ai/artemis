# Phase 4 测试：部署形态 II（K8s/Helm）

版本: 1.2    更新时间: 2026-10-28

## 测试方案

本 phase 零 Java 变更（部署制品：镜像 Dockerfile + Helm chart + 冒烟脚本），测试形态为**部署冒烟**（phase-3 既有形态的 helm 扩展）：

- **渲染校验**（无集群依赖，CI 主载体）：`helm lint` + `helm template` 渲染断言——默认 values 渲染出 StatefulSet（registry）+ Deployment（discovery/management）+ 4 Service，env 注入（互连端点/探针地址/成员表）与探针路径逐项 grep 断言。
- **全链路冒烟**（本地 kind，CI 不跑）：`smoke-deploy.sh helm` 模式——kind 起集群 → 三镜像 build + load → helm install → pod 就绪（readiness 探针）→ port-forward + SDK 走通（复用 `SmokeSdk.java` harness：注册 + 订阅发现闭环）→ 卸载清理。
- 工具前置：helm / kind / kubectl（冒烟 fail-fast 预检；本机缺则先装，不入库）。

执行入口 `java/scripts/smoke-deploy.sh helm`（模式定义与形态↔模式映射见 ⑤ 节；部署冒烟独立于 dev-test.sh，phase-3 先例）。**dev-test.sh 无扩展**（未引入新的 mvn 形态；⑤/⑧ 仍以 all 模式跑全量 verify 确认 Java 基线零回归）。

## 用例清单

| 用例 | 场景 | 形态 | 结果 |
|---|---|---|---|
| should_pass_lint_and_render_expected_workloads_when_helm_template | 默认 values 下 lint 通过，模板渲染出 registry StatefulSet + discovery/management Deployment + peer/external/discovery/management 四 Service，env 断言：成员表三段式、互连端点注入（discovery clusters/nodes、management registry/discovery endpoint、gRPC 9094 启用）、探针路径 `/actuator/health/{liveness,readiness}` + `MANAGEMENT_SERVER_ADDRESS=0.0.0.0` | 渲染校验（helm lint/template，冒烟脚本内 + CI helm job） | pass |
| should_registry_expand_members_when_replicas_set | `--set registry.replicas=3` 渲染成员表含 3 个三段式条目（peer headless DNS + external svc 端点）；`membersOverride` 非空时整体替换 | 渲染校验（helm template --set 断言） | pass |
| should_toggle_components_when_disabled | `management.enabled=false` 时 discovery 规则流端点不注入且 management Deployment 不渲染 | 渲染校验（helm template --set 断言） | pass |
| should_services_ready_when_kind_helm_install | kind 集群 helm install 后三服务 pod 全部就绪（readiness 探针通过；management 默认 `db.enabled=false` 内存态起） | 部署冒烟（smoke-deploy.sh helm） | pass |
| should_sdk_roundtrip_when_port_forward | port-forward 四通道下 SDK 走通：注册一实例 → 订阅发现收到该实例（复用 SmokeSdk harness） | 部署冒烟（smoke-deploy.sh helm） | pass |
| should_default_auth_enforced_when_console_probe | management console 匿名访问 401（默认认证开） | 部署冒烟（smoke-deploy.sh helm） | pass |
| should_cleanup_when_uninstall | helm uninstall 后 release 资源清空、kind 集群删除无残留 | 部署冒烟（smoke-deploy.sh helm cleanup 路径） | pass |

## 验证记录（⑨ 填）

**自动化验证（工作流执行）**：

| 命令 | 结果 |
|---|---|
| `helm lint deploy/helm/artemis` | 1 chart(s) linted, 0 failed |
| `helm template artemis deploy/helm/artemis`（默认 + replicas=3 / management.enabled=false / membersOverride / tls.enabled / 53 字符 release 名变体） | 拓扑（STS×1 + Deployment×2 + Service×4 + SA）与 env/探针/守卫/截断断言全绿；fail 场景（registry.enabled=false 无 clusters、db.enabled 无 url、TLS 不配对×2）退出码均非 0、错误文案可读 |
| `java/scripts/smoke-deploy.sh helm`（修复后两轮：⑤ 期脚本修复 + ⑥ review 修复） | ALL PASS ×2：kind 三镜像、pod 全 Ready（readiness 探针）、console 匿名 401、SDK 注册 + discovery 订阅 instances=1、helm uninstall --wait 残留断言、kind 删除、kubectl context 恢复 |
| `java/scripts/dev-test.sh all` | BUILD SUCCESS（19:03 全量；⑥⑦ 修复仅触及 chart/bash/CI 文档，Java 零变更，结果未失效不重跑） |

**人工验证（用户执行）**：用户于 2026-10-28 复核制品与冒烟结论，验证通过（chat 确认）。

## 回归记录（⑧ 填，经 dev-test.sh）

## 回归记录（⑧ 填，经 dev-test.sh）

| 轮次 | 模式 | 范围 | 结果 |
|---|---|---|---|
| ⑤-1 | all（dev-test.sh all ≡ verify） | 全量（spotless + enforcer + 单测 + IT；零 Java 变更基线回归） | BUILD SUCCESS（19:03，Tests run 0 失败；冒烟另经 smoke-deploy.sh helm 全 pass） |
| ⑧-1 | 渲染断言（helm lint/template） | ⑥ 修复点逐项：auth ENABLED 键、discovery 端点无 scheme 前缀、TLS readiness scheme HTTPS、两个 TLS 配对 fail 守卫退出码、长名截断（fullname/SA 50） | 全绿 |
| ⑧-2 | 部署冒烟（smoke-deploy.sh helm） | review 修复后全链路回归：三服务 Ready + console 401 + SDK instances=1 + uninstall --wait 残留断言 + context 恢复 | ALL PASS（Java 零变更，dev-test all 不需重跑） |

## 更新历史

| 版本 | 日期 | 变更说明 |
| ------ | ------ | ------ |
| 1.3 | 2026-10-28 | ⑨ 自动化验证记录（lint/渲染断言/冒烟×2/dev-test all）与 ⑧ 回归两轮 |
| 1.2 | 2026-10-28 | ⑤ 冒烟与渲染校验 7 用例全 pass；记录冒烟期 sdk_classpath 捕获污染修复（回归记录另记） |
| 1.1 | 2026-10-28 | 测试设计定稿（渲染校验 + kind 全链路冒烟，7 用例） |
| 1.0 | 2026-10-28 | 测试骨架 |
