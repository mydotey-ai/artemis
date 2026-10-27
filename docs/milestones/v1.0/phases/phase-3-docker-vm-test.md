# Phase 3 测试：部署形态 I（Docker + VM）

版本: 1.3    更新时间: 2026-10-28

## 测试方案

本 phase 无 Java 源码变更，测试以**部署冒烟**为主形态：

- **部署冒烟（docker）**：`java/scripts/smoke-deploy.sh docker`——镜像构建 + 容器起服 + 就绪判定 + console 探活 + SDK 走通。依赖宿主 Docker。
- **部署冒烟（vm）**：`java/scripts/smoke-deploy.sh vm`——tar.gz 构建解包 + 结构断言 + `app.sh start/stop` 生命周期 + SDK 走通。
- **人工验证**：systemd install/uninstall/enable 流程（需 root/systemd，用户本机 VM 执行）。

形态↔入口映射：本 phase 引入的「部署冒烟」形态**不经 dev-test.sh**（非 mvn 形态，不进 verify 生命周期），独立入口 `smoke-deploy.sh`——dev-test.sh 三模式（unit/integration/all）命令不变，脚本同步义务不触发；本节即该形态的入口声明。⑤/⑧ 的 mvn 回归照常经 dev-test.sh all 执行（确认部署制品与脚本变更不破坏既有构建）。

SDK 走通经 single-file harness（`java/scripts/smoke-sdk.java`，JDK 25 source-launch）：注册一实例 + 订阅发现断言收到，退出码判定；覆盖注册侧 + 发现侧 + 治理链端口的部署级连通性（全语义由既有 AllInOneIT/DiscoveryClientIT 承载，不重复）。

## 用例清单

| 用例 | 场景 | 形态 | 结果 |
|---|---|---|---|
| should_build_all_in_one_image_when_docker_build | `docker build -f Dockerfile.all-in-one .` 成功且镜像含 fat jar | 部署冒烟（docker） | 通过 |
| should_start_all_services_when_docker_run | `docker run` 单容器三服务六对外端口就绪，日志含 `all-in-one started` | 部署冒烟（docker） | 通过 |
| should_enforce_default_auth_when_console_probe | console HTTP 探活返回 401（默认认证开启） | 部署冒烟（docker） | 通过 |
| should_apply_env_config_when_artemis_dist_env_set | `ARTEMIS_DIST_REGISTRY_HTTP_PORT` 覆盖端口生效（容器起在改后端口） | 部署冒烟（docker） | 通过 |
| should_register_and_discover_when_sdk_against_container | SDK harness 对容器端口注册 + 订阅发现走通 | 部署冒烟（docker） | 通过 |
| should_produce_complete_package_when_build_dist | `build-dist.sh` 产出 tar.gz，结构断言（bin/app.sh、lib/jar、lib/version、artemis.properties、artemis.service、data/） | 部署冒烟（vm） | 通过 |
| should_start_and_stop_when_app_sh_lifecycle | `app.sh start` 起服就绪 + SDK 走通，`stop` 优雅退出无残留进程 | 部署冒烟（vm） | 通过 |
| should_apply_file_config_when_properties_overridden | 包根 `artemis.properties` 改端口后 `app.sh start` 生效 | 部署冒烟（vm） | 通过 |
| should_install_systemd_unit_when_install_command | `app.sh install` → `systemctl status/enable` → 服务可用 → `uninstall` 卸载干净 | 人工验证 | 通过（用户验证，2026-10-28） |

## 验证记录（⑨ 填）

自动化验证（工作流执行）：

| 命令 | 结果 |
|---|---|
| `java/scripts/dev-test.sh all`（⑤ 轮） | 通过（BUILD SUCCESS，全 reactor） |
| `java/scripts/smoke-deploy.sh all`（⑤ 轮） | 通过（docker + vm 全过） |
| `java/scripts/smoke-deploy.sh all`（⑧ 修复后回归） | 通过（ALL PASS；含 docker→vm 段容器残留污染修复） |
| `mvn -pl :artemis-dist verify`（⑧ 随机端口注入缺陷修复验证） | 通过（AllInOneIT 6/6 + AllInOnePersistenceIT 1/1） |
| `java/scripts/smoke-deploy.sh vm`（⑧ 修复后新 jar 复验） | 通过（ALL PASS） |
| `java/scripts/dev-test.sh all`（⑧ 最终轮） | 通过（BUILD SUCCESS，全 reactor） |

人工验证（用户执行）：用户于 2026-10-28 验证通过（自动化 6 项 + 人工检查后确认）。

## 回归记录（⑧ 填，经 dev-test.sh）

| 轮次 | 模式 | 范围 | 结果 |
|---|---|---|---|
| 1（⑤） | all | 全 reactor（spotless + enforcer + 单测 + IT；基线 `RouteRulePersistenceIT` 格式遗留经 `spotless:apply` 修复） | 通过（BUILD SUCCESS） |
| 1（⑤） | smoke-deploy all | docker + vm 两模式部署冒烟（8 用例） | 通过（ALL PASS） |
| 2（⑧） | smoke-deploy all | ⑦ 修复后回归；首轮暴露 docker→vm 段容器残留污染端口（EXIT trap 清理过晚），修复为段内即清后 | 通过（ALL PASS） |

## 更新历史

| 版本 | 日期 | 变更说明 |
| ------ | ------ | ------ |
| 1.3 | 2026-10-28 | ⑧⑨ 回归与验证完成：全用例通过（9/9，人工项用户确认） |
| 1.2 | 2026-10-28 | ⑤ 用例执行：docker/vm 两模式冒烟全过（8/9，systemd 流程留人工） |
| 1.1 | 2026-10-28 | 测试设计定稿（部署冒烟两模式 + 人工 systemd 流程） |
| 1.0 | 2026-10-27 | 测试骨架 |
