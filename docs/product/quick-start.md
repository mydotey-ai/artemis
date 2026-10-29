# 快速开始

版本: 1.1    更新时间: 2026-10-29

10 分钟从零跑通「注册 + 发现」：一条命令起全功能单容器，SDK 注册一个实例、订阅发现并看到它。

## 1. 前置条件

- **起服务**：Docker（最短路径）；或 JDK 25 + Maven 3.9（构建镜像用，容器内自含构建）
- **接 SDK**：JDK 8+ 与 Maven（SDK 为 Java 8 兼容；构建期 JDK 25 + toolchains 配置见 [构建与测试](artemis.md)）

## 2. 起服务（all-in-one 单容器）

```bash
git clone <本仓库> && cd artemis
docker build -f Dockerfile.all-in-one -t artemis-all-in-one:local .
docker run -d --name artemis \
  -p 8080:8080 -p 9090:9090 \
  -p 8081:8081 -p 9094:9094 \
  -p 8090:8090 -p 9095:9095 \
  -v artemis-data:/app/data \
  -e ARTEMIS_REGISTRY_CLUSTER_MEMBERS=all-in-one@127.0.0.1:9093@127.0.0.1:9090 \
  -e ARTEMIS_DISCOVERY_NODES=127.0.0.1:9095 \
  artemis-all-in-one:local
```

两个 `-e` 是宿主 SDK 接入的关键：SDK 前置寻址经 HTTP 拉节点目录，目录端点是服务端自报的容器内视角地址——端口映射场景须覆盖为宿主可达端点（[ADR-020](../decisions/adr-020-discovery-static-node-directory.md)；细节见 [docker.md](../deploy/docker.md)「SDK 接入」）。

就绪判定：`docker logs artemis` 出现 `all-in-one started`。console 在 `http://127.0.0.1:8081/`（默认账户 `artemis/artemis`，认证默认开启）。

| 端口 | 服务 | 用途 |
|---|---|---|
| 8080 / 9090 | registry | HTTP / gRPC（SDK 注册心跳） |
| 8081 / 9094 | management | console + Admin API（HTTP）/ 规则流（gRPC） |
| 8090 / 9095 | discovery | HTTP / gRPC（SDK 订阅发现） |

## 3. SDK 接入（注册 + 发现）

SDK 发布 Maven Central 的配置与流程已就绪（v1.0 phase-8，见[发布流程](../dev/release-java.md)），实际发布由维护者执行；发布前以 `0.1.0-SNAPSHOT` 本地安装消费：

```bash
mvn -f java/client/pom.xml install -DskipTests -DskipITs
```

业务工程依赖（Spring Boot 用户直接用 starter：`artemis-client-spring-boot-starter`，自动装配见 [SDK 文档](../api/sdk-java.md)）：

```xml
<dependency>
  <groupId>org.mydotey.ai.artemis</groupId>
  <artifactId>artemis-client</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

最简闭环（与部署冒烟 harness `java/scripts/SmokeSdk.java` 同源用法）：

```java
// 寻址与认证经系统属性（或对应环境变量）注入；registry 默认认证开启，缺账户会被 401 拒绝
System.setProperty("artemis.client.registry.addresses", "http://127.0.0.1:8080");
System.setProperty("artemis.client.discovery.addresses", "http://127.0.0.1:8090");
System.setProperty("artemis.client.auth.username", "artemis");
System.setProperty("artemis.client.auth.password", "artemis");

ServiceId service = ServiceId.newBuilder().namespace("default").service("demo-service").build();

// 注册侧：地址为 registry HTTP 寻址入口；账户默认 artemis/artemis（容器默认认证）
RegistryClient registry = ArtemisClients.newRegistryClient();
registry.start();
registry.register(ServiceInstance.newBuilder()
        .service(service)
        .instanceId("instance-1")
        .host("10.0.0.11")          // 业务进程可达地址
        .port(8080)
        .status(InstanceStatus.UP)
        .build());                   // fire-and-forget：心跳全量上报续约/补注册（ADR-012）

// 发现侧：地址为 discovery HTTP 寻址入口；订阅推送 + 本地视图
DiscoveryClient discovery = ArtemisClients.newDiscoveryClient();
discovery.start();
Subscription sub = discovery.subscribe(
        DiscoverySelector.newBuilder().addService(service).build());
List<ServiceInstance> instances = sub.getInstances(service);   // 推送到达后含 instance-1
```

认证键（`artemis.client.auth.*`）为注册/发现两侧共用的 Basic 账户：registry 默认开启认证（须配置），discovery 默认关闭（发送亦无害）；starter 用户经 `artemis.client.*` 配置项等价注入。完整配置项与生命周期语义见 [sdk-java.md](../api/sdk-java.md) 与 [sdk-java-discovery.md](../api/sdk-java-discovery.md)。

进程退出前 `close()` 两个 client（注册侧优雅下线：取消注册 + 停心跳）。

## 4. 下一步

- 生产部署：[Docker](../deploy/docker.md) / [VM 标准安装包](../deploy/vm.md) / [K8s Helm chart](../deploy/k8s.md)
- 全量配置：[配置参考](../deploy/config-reference.md)
- 监控告警：[运维手册](../deploy/operations.md)（告警示例在 [deploy/alerts/](../../deploy/alerts/prometheus.yml)）
- HTTP/gRPC API 与 Admin API：[API 文档](../api/README.md)
- 容量规划：[容量基线](../deploy/capacity-baseline.md)
- 产品概念与操作语义：[Artemis 产品文档](artemis.md)

## 更新历史

| 版本 | 日期 | 变更说明 |
| ------ | ------ | ------ |
| 1.1 | 2026-10-29 | v1.0 phase-8：starter 更名 artemis-client-spring-boot-starter、发布状态措辞更新（配置就绪待发布） |
| 1.0 | 2026-10-28 | 初版（v1.0 phase-7 文档站） |
