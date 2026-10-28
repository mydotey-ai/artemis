# 运维手册

版本: 1.0    更新时间: 2026-10-28

部署后运维入口：启停与升级、监控告警接入、告警处置、排障、数据与恢复。部署操作本身见三形态文档（[Docker](docker.md) / [VM](vm.md) / [K8s](k8s.md)），配置键见[配置参考](config-reference.md)。

## 1. 启停与升级

| 形态 | 启停 | 升级 |
|---|---|---|
| Docker all-in-one | `docker start/stop/restart artemis` | 换镜像 tag 重建容器（数据卷 `artemis-data` 保留） |
| VM | `bin/app.sh start/stop/restart`，或 systemd `systemctl start/stop artemis` | 替换安装目录后重启（[vm.md](vm.md)「升级」） |
| K8s/Helm | Deployment/StatefulSet 自管理 | `helm upgrade`（滚动策略见下） |

**滚动升级口径**（v1.0 phase-5 验证：proto 前后兼容 + 3 节点滚动重启 SDK 不断流）：对等节点逐台重启，每台先等就绪摘除再停（registry 停机排水窗口 `artemis.registry.shutdown.drainMs` 默认 10s，存量心跳服务到底）；SDK 侧经节点列表多地址容灾（[ADR-020](../decisions/adr-020-discovery-static-node-directory.md)），滚动期间注册心跳不断流。K8s 形态由 StatefulSet 滚动策略承载；proto wire 兼容由 CI 兼容检查门控（`dev-test.sh all`）。

## 2. 监控接入

- **指标端点**：各服务 actuator management 端口（registry 9091 / discovery 9096 / management 9092）的 `/actuator/prometheus`，Micrometer Prometheus 格式。**默认仅绑本机**（`management.server.address=127.0.0.1`）——集群部署显式改绑定地址并以网络策略保护该端口。
- **scrape 与告警示例**：[deploy/alerts/prometheus.yml](../../deploy/alerts/prometheus.yml)（三服务 job）、[rules.yml](../../deploy/alerts/rules.yml)（告警规则）、[alertmanager.yml](../../deploy/alerts/alertmanager.yml)（路由桩）。指标清单与语义见架构 [§6](../arch/artemis-next-architecture.md)。
- **trace**：OTel 默认仅传播不开导出（`management.tracing.export.otlp.enabled=false`），有 collector 时开启。

## 3. 告警规则与处置

规则定义见 [deploy/alerts/rules.yml](../../deploy/alerts/rules.yml)，加载校验 `promtool check rules`（CI 经 [docs.yml](../../.github/workflows/docs.yml) 守门）。

| 告警 | severity | 含义与处置 |
|---|---|---|
| ArtemisTargetDown | critical | scrape 拉不到节点 metrics：进程退出 / 网络不通 / management 端口未暴露。先看进程与端口，再查网络策略 |
| ArtemisNodePeersLost | critical | 节点与全部 peer 断联（复制不进不出）。查 peer 端口 9093 连通性、内部认证是否两端一致；**仅多节点集群适用**（单节点无 peer 恒 0，部署时移除该规则） |
| ArtemisNodeSelfProtection | warning | 节点级自我保护触发（续约率跌破 factor）。判断性质：大规模真实下线（发布/扩缩）→ 预期内，等实例回心自动退出；非预期 → 排查网络或 SDK 心跳 |
| ArtemisServiceProtection | warning | 单服务进入保护态。定位该服务（`service.protection{namespace,service}` 标签 / `/admin/v1` 实例视图）：批量重启则预期内；单服务掉心跳则查该服务 SDK 与网络 |
| ArtemisReplicationFailing | warning | 复制批发送失败（对端不可达/未就绪）。结合上两条定位对端节点 |
| ArtemisReplicationDropped | warning | 复制重试队列满触发丢弃（AP 语义，对端 bootstrap 兜底）。查对端为何长期未就绪（重启循环/卡 STARTING） |
| ArtemisBootstrapFailing | warning | 节点全量同步失败，以空/旧数据运行。查 peer 连通性与 bootstrap 超时配置 |

保护态语义（双层、显式状态与超时回落）见 [ADR-017](../decisions/adr-017-self-protection-two-layer.md)。

## 4. 排障

**诊断入口**：

| 入口 | 位置 | 用途 |
|---|---|---|
| `GET /readyz` | registry 外部 HTTP（8080，认证白名单） | registry 就绪（含 peer/bootstrap 状态） |
| `GET /actuator/health/readiness` | 各服务 management 端口 | 就绪探针同源状态 |
| `GET /cluster/nodes` | registry/discovery 外部 HTTP | 成员列表与探测态 / SDK 寻址目录（未就绪 503） |
| `GET /admin/v1/*` | management 8081（[Admin API](../api/admin-api.md)：实例事实、租约排障、账户、规则生效水位 `status/rules`）/ discovery 8090（[只读管理面](../api/discovery-admin-api.md)：`subscriptions` 订阅关系、`services` 服务视图、`upstreams` 上游新鲜度与规则源状态） | 管理面观测与排障（discovery 侧免 readiness 门控，启动期可排障） |
| 日志 | stdout/文件 | 结构化 JSON（字段含时间/级别/线程/traceId） |

**常见症状**：

| 症状 | 路径 |
|---|---|
| SDK 注册失败 | 401 → 认证键不一致（registry 默认 `artemis/artemis`）；UNAVAILABLE → 地址端口与寻址目录（`/cluster/nodes` 是否宿主可达）；寻址 503 → 节点未就绪，SDK 自动重试其它地址 |
| 订阅断流/数据不新鲜 | discovery `/admin/v1/upstreams` 查上游新鲜度与断流计数；SDK 侧有兜底拉取 + hash 对账（ADR-019），持续不新鲜查 discovery→registry 内部通道 |
| 服务实例「该摘除没摘除」 | 先看是否保护中（保护态暂停过期摘除是设计行为）；management `/admin/v1` 查实例租约状态 |
| 治理规则不生效 | management `/admin/v1/status/rules` 查规则生效水位（版本 + 已生效节点数）；management 不可用时 discovery 沿用最后已知规则（fail-open） |
| 节点间数据不一致 | AP 语义窗口内允许（无 peer 拉对账，ADR-006）；持续不一致查复制告警与 bootstrap 状态 |

## 5. 数据与恢复

| 数据 | 位置 | 语义 |
|---|---|---|
| registry 注册表 | 内存（AP，事实源） | 重启后经 peer bootstrap 恢复；单节点靠文件快照（`data/registry-snapshot.bin`，默认 5 分钟刷盘、超 1 小时作废）**加速**恢复，非持久化承诺 |
| 治理元数据 | management DB（`spring.datasource.*`，MySQL/PostgreSQL） | 持久化，随 DB 备份恢复；schema 由 Flyway 自动迁移 |
| 摘除/管理操作审计 | management DB | 同上 |

备份口径：DB 常规快照即可；registry 数据面无需备份（心跳即注册，实例自行续约重建）。all-in-one 数据卷 `/app/data`（快照）随容器编排保留。

## 更新历史

| 版本 | 日期 | 变更说明 |
| ------ | ------ | ------ |
| 1.0 | 2026-10-28 | 初版（v1.0 phase-7 文档站） |
