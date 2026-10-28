# Docker 单机容器部署

版本: 1.0    更新时间: 2026-10-28

all-in-one 单容器形态：registry + management + discovery 三服务一容器起全功能，eval 最短路径（架构 §2.3 形态 I-a）。服务端形态语义（端口矩阵、治理链默认闭合、DB 双轨）见 [all-in-one 文档](../api/all-in-one.md)——容器只是交付载体，不改变配置出口。

## 构建镜像

```bash
docker build -f Dockerfile.all-in-one -t artemis-all-in-one:local .
```

多阶段自包含：构建阶段在容器内跑 Maven（registry 单服务镜像用根 `Dockerfile`，与本镜像并列）。

## 一条命令起全功能

```bash
docker run -d --name artemis \
  -p 8080:8080 -p 9090:9090 \
  -p 8081:8081 -p 9094:9094 \
  -p 8090:8090 -p 9095:9095 \
  -v artemis-data:/app/data \
  artemis-all-in-one:local
```

| 端口 | 服务 | 用途 |
|---|---|---|
| 8080 / 9090 | registry | HTTP / gRPC（SDK 注册心跳） |
| 8081 / 9094 | management | console + Admin API（HTTP）/ 规则流（gRPC） |
| 8090 / 9095 | discovery | HTTP / gRPC（SDK 订阅发现） |

就绪判定：`docker logs artemis` 出现 `all-in-one started`。console 默认认证开启（默认账户 `artemis/artemis`）。

## 配置外置

配置出口与裸跑 fat jar 完全一致（零新增机制），按优先级系统属性 > 环境变量 > 工作目录 `artemis.properties`：

1. **环境变量**（SCF relaxed 映射，camel 键转 `_` 大写）：`-e ARTEMIS_DIST_REGISTRY_HTTP_PORT=18080` 覆盖组合端口；`-e ARTEMIS_REGISTRY_AUTH_PASSWORD=...` 改认证；`-e SPRING_DATASOURCE_URL=...` 接 DB（仅 management 消费，见 all-in-one 文档「DB 双轨」）。
2. **挂载工作目录配置文件**：`-v $PWD/artemis.properties:/app/artemis.properties:ro`（模板见 [deploy/vm/artemis.properties](../../deploy/vm/artemis.properties)）。
3. **数据持久化**：registry 快照在 `/app/data`（卷挂 `-v artemis-data:/app/data`）。

**SDK 接入（端口映射场景）**：SDK 前置寻址经 HTTP 端口拉节点目录，目录端点是服务端自报的容器内视角地址——宿主 SDK 接入时以 env 覆盖为宿主可达端点（显式配置优先）：

```bash
-e ARTEMIS_REGISTRY_CLUSTER_MEMBERS=all-in-one@127.0.0.1:9093@127.0.0.1:<regGrpc 映射端口> -e ARTEMIS_DISCOVERY_NODES=127.0.0.1:<discGrpc 映射端口>
```

裸跑（无映射，`--network host`）无需覆盖。

## 内部端口访问

peer 端口（9093）与三个 actuator 管理端口（9091/9092/9096）绑容器 loopback，**端口映射不可达**（产品默认，评估形态不外暴观测面）。需要 metrics/health 时：

```bash
docker exec artemis curl -s localhost:9091/actuator/prometheus | head
```

## 冒烟

```bash
java/scripts/smoke-deploy.sh docker   # 构建镜像 + 起容器 + 端口就绪 + console 401 + SDK 注册订阅走通
```

## 更新历史

| 版本 | 日期 | 变更说明 |
| ------ | ------ | ------ |
| 1.0 | 2026-10-28 | v1.0 phase-3 首版 |
