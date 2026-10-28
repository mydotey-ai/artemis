# artemis

Artemis 微服务注册中心——重设计与再实现。三服务架构（registry 注册 / discovery 发现 / management 治理），AP 型数据面（心跳即注册 + 全对全异步复制），治理元数据外部 DB 持久化。产品概念与操作语义见[产品文档](docs/product/artemis.md)，架构设计见[架构文档](docs/arch/artemis-next-architecture.md)。

## 快速开始

[10 分钟跑通注册 + 发现](docs/product/quick-start.md)：一条命令起 all-in-one 单容器，SDK 注册实例并订阅发现。

## 部署

三种形态（定义见架构 §2.3）：

| 形态 | 文档 | 适用 |
|---|---|---|
| Docker all-in-one 单容器 | [docker.md](docs/deploy/docker.md) | eval 最短路径 |
| VM 标准安装包（tar.gz + systemd） | [vm.md](docs/deploy/vm.md) | 虚拟机/物理机 |
| K8s Helm chart（三服务独立编排） | [k8s.md](docs/deploy/k8s.md) | 容器编排环境 |

运维手册（监控/告警/排障）见 [operations.md](docs/deploy/operations.md)，容量规划见[容量基线](docs/deploy/capacity-baseline.md)。

## 开发者

- [文档总索引](docs/README.md)：架构 / API / 部署 / 决策（ADR）/ 里程碑
- [配置参考](docs/deploy/config-reference.md)：服务端全量配置键
- [API 参考](docs/api/README.md)：HTTP/gRPC API、Admin API、Java SDK（注册侧 / 发现侧）
- 构建：`mvn -f java/pom.xml verify`（JDK 25 + JDK 8 toolchains，见 [CLAUDE.md](CLAUDE.md)）；统一测试入口 `java/scripts/dev-test.sh`
- 项目约定与工作流见 [CLAUDE.md](CLAUDE.md) 与 `.claude/rules/`
