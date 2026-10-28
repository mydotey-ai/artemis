# 部署文档

v1.0 部署形态与运维文档索引（形态定义见架构 [§2.3](../arch/artemis-next-architecture.md)）。

| 文档 | 说明 | 状态 |
|---|---|---|
| [docker.md](docker.md) | Docker 单机容器（all-in-one，eval 最短路径） | 可用 |
| [vm.md](vm.md) | VM 标准安装包（tar.gz + systemd） | 可用 |
| [k8s.md](k8s.md) | K8s/Helm chart（三服务独立编排扩缩） | 可用 |
| [capacity-baseline.md](capacity-baseline.md) | 容量基线（压测实测 + 容量公式 + 瓶颈结论） | 可用 |
| [config-reference.md](config-reference.md) | 配置参考（三服务 + all-in-one 全量配置键） | 可用 |
| [operations.md](operations.md) | 运维手册（启停升级/监控告警/排障/数据恢复） | 可用 |

告警示例配置（Prometheus + Alertmanager 三件套）在仓库 [deploy/alerts/](../../deploy/alerts/rules.yml)，加载校验经 `java/scripts/check-docs.sh`。
