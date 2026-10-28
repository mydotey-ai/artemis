# K8s/Helm 部署

版本: 1.0    更新时间: 2026-10-28

三服务独立编排形态（架构 §2.3 形态 III）：registry 有状态副本（StatefulSet + PVC 快照卷 + peer headless svc 稳定成员 DNS）、discovery/management 无状态（Deployment），可独立扩缩与按需裁剪（`enabled` 开关）。Helm chart 在 [deploy/helm/artemis/](../../deploy/helm/artemis/)；配置注入全 env（SCF relaxed 映射 `ARTEMIS_*` 与 Spring 标准键），与 all-in-one 容器形态同出口，零新增配置面。

## 前置

- 工具：`helm`（≥3.16）、`kubectl`、K8s 集群（≥1.28）；本地冒烟用 `kind`。
- 三服务镜像（参数化单 Dockerfile）：

```bash
for svc in registry discovery management; do
  docker build -f Dockerfile.service --build-arg SERVICE=$svc -t myregistry/artemis-$svc:1.0.0 .
done
```

## 安装

```bash
helm install artemis deploy/helm/artemis \
  --namespace artemis --create-namespace \
  --set image.registry=myregistry/ --set image.tag=1.0.0
```

默认拓扑（全部开关见 chart [values.yaml](../../deploy/helm/artemis/values.yaml)）：

| 工作负载 | Service | 端口 | 说明 |
|---|---|---|---|
| StatefulSet `<rel>-registry` | `<rel>-registry`（ClusterIP） | http 8080 / grpc 9090 | SDK 数据面接入（/cluster/nodes 节点目录、/readyz） |
| | `<rel>-registry-peer`（headless） | peer 9093 | 成员稳定 DNS + peer 复制/bootstrap + management 只读查询 |
| Deployment `<rel>-discovery` | `<rel>-discovery` | http 8090 / grpc 9095 | 消费方 SDK 订阅发现（节点目录） |
| Deployment `<rel>-management` | `<rel>-management` | http 8081 / grpc 9094 | console/Admin API / 规则流（discovery 订阅） |

治理链 chart 内默认闭合：discovery 订阅 management 规则流、management 查询本 chart registry（关 management 后 discovery fail-open 沿用最后已知规则）。

## 关键配置

| values | 默认 | 说明 |
|---|---|---|
| `registry.replicas` | `1` | 扩容即改：成员表（三段式 `<id>@<peer>@<external>`）随渲染更新，滚动重启生效。静态成员哲学：缩容后陈旧条目表现为探测 `UNKNOWN`，不参与扇出门控 |
| `registry.membersOverride` / `selfOverride` | 空 | 显式配置优先（完整成员列表 / self id），外部接入改造（NodePort/LB）时覆盖 external 端点 |
| `registry.storage.*` | `1Gi` / 默认 SC | 快照 PVC（volumeClaimTemplates） |
| `discovery.clusters` | 渲染值 | 上游 registry 集群 map（JSON）；多集群 union 或外置集群自配；`registry.enabled=false` 时必填 |
| `discovery.nodes` | 渲染值 | SDK 节点目录端点；外部 SDK 接入（非集群内）时覆盖为可达端点 |
| `management.db.*` | `false` | DB 可选依赖：`false` = 内存态（单机 eval 语义）；`true` = 持久化（RBAC 多账户/角色 + 摘除/逻辑实例/审计跨重启恢复，url 必填，密码可走 `existingSecret`）。不内置 DB 子 chart——外接现成实例 |
| `*.auth.*` | `artemis/artemis` | 单账户凭证（registry/management 产品默认开，discovery 默认关）；生产必改，`existingSecret` 免明文 |
| `*.tls.*` | 全关 | TLS 透传（phase-1 键面）：secret 挂 `/etc/artemis/tls`，外部走 Spring SSL bundle、内部 peer/出站走 `ARTEMIS_*_TLS_*`；所需文件名清单见 values.yaml 注释 |
| `*.enabled` | `true` | 三服务独立裁剪（如仅 registry+discovery + 外置管理面） |
| `*.extraEnv` | 空 | 任意 env 追加（逃生口，如 `registry.enabled=false` 时的 `artemis.management.registry.endpoint`） |

Service 默认 ClusterIP（集群内消费）；外部接入按需改 svc 类型——**SDK 侧节点目录地址须同步覆盖**（registry `membersOverride` external 端点 + `discovery.nodes`），否则 SDK 拿到集群内 DNS 不可达（同 Docker 端口映射场景语义）。

## 探针与就绪

- registry readiness：外部 `/readyz`（http 8080）——无认证白名单，**优雅停机排水期 503**，K8s 自动摘流量对接停机排水语义。
- liveness / discovery、management readiness：actuator 管理端口 `/actuator/health/{liveness,readiness}`（env 注入 `MANAGEMENT_SERVER_ADDRESS=0.0.0.0` 供 kubelet 直达 pod；产品默认 loopback 不变，管理端口不出 Service）。
- startupProbe：registry 5s×36（快照回放 + peer bootstrap 宽裕窗口）。

## 集群内 SDK 接入

pod 内 SDK bootstrap 端点直接用 svc DNS：

```text
registry: http://artemis-registry.artemis.svc:8080   discovery: http://artemis-discovery.artemis.svc:8090
```

节点目录返回集群内 DNS（SDK 同 namespace/集群内可达），零额外配置。

## 冒烟

```bash
java/scripts/smoke-deploy.sh helm   # kind 起集群 + 三镜像 + helm install + 就绪 + SDK 走通（工具前置 helm/kind/kubectl）
```

## 卸载

```bash
helm uninstall artemis -n artemis && kubectl delete ns artemis
```

PVC 随 StatefulSet 删除策略：volumeClaimTemplates 的 PVC 默认不随 release 卸载删除（K8s 语义，防误删快照数据），显式 `kubectl delete pvc -n artemis data-artemis-registry-0`。

## 更新历史

| 版本 | 日期 | 变更说明 |
| ------ | ------ | ------ |
| 1.0 | 2026-10-28 | 初版（v1.0 phase-4：chart 拓扑、values 口径、探针、SDK 接入、冒烟） |
