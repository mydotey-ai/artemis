# Phase 4：部署形态 II（K8s/Helm）

版本: 1.4    更新时间: 2026-10-28

## 目标

K8s/Helm chart：三服务独立编排扩缩（registry 有状态副本、discovery/management 无状态）+ DB 依赖可选组件 + 就绪探针。

## 范围

**做**：

- 三单服务镜像：根 `Dockerfile.service`（`ARG SERVICE` 参数化 registry/discovery/management，见 §1）；既有根 `Dockerfile`（registry）与 `Dockerfile.all-in-one` 保留不动（phase-3 定案）。
- Helm chart：`deploy/helm/artemis/`（结构见 §2），配置全 env 注入（SCF relaxed 映射，与 all-in-one 容器形态同出口），无 ConfigMap。
- 编排：registry StatefulSet（peer headless svc + external svc + PVC 快照卷）+ discovery/management Deployment（无状态），互连端点 chart 内默认接通（治理链闭合，同 all-in-one 哲学）。
- 探针：actuator liveness/readiness（§6）。
- DB 可选组件：values 开关，外接透传（§5），不内置 DB 子 chart。
- TLS / 认证凭证 / RBAC（DB）values 透出（§7）。
- 部署冒烟：`smoke-deploy.sh` 新增 `helm` 模式（§8）；工具前置 helm/kind/kubectl（本机当前缺，⑤ 前安装）。
- 部署文档 `docs/deploy/k8s.md` + `deploy/README.md`、`docs/deploy/README.md` 索引更新。
- CI：docker job 补 discovery/management 两镜像 build + helm lint job；paths 补 `deploy/helm/**` 与 `Dockerfile.service`。

**不做**：

- 镜像推送 registry / 签名 / 扫描（与形态 I 一致，CI 只 build 不 push）。
- 动态成员（K8s 成员源是 v1.x 后置项，roadmap §3 v1.x；静态成员经 helm replicas 生成，缩容陈旧条目属静态成员既有语义——探测 UNKNOWN、不参与扇出门控）。
- 内置 DB / 依赖子 chart（bitnami 等）：K8s 场景 DB 现成或用户自配，`SPRING_DATASOURCE_*` 透传即可（YAGNI）。
- 镜像内 HEALTHCHECK（形态 I 定案归 Helm 探针，本 phase 以 K8s probe 落地）。
- HPA / PodDisruptionBudget / NetworkPolicy：扩缩语义交付「静态 replicas 可配」，自动伸缩器后置（容量基线 phase-6 出数后再定）。
- 多集群 union（discovery 上游多 registry 集群）：values `registry.clusters` 开放为 map（透传既有键），chart 默认单集群指向本 chart registry；外部集群接入经 values 自配。

## 涉及模块

无 Java 源码变更。新增部署制品：根 `Dockerfile.service`、`deploy/helm/artemis/`（chart）、`java/scripts/smoke-deploy.sh` 扩展（helm 模式）、`.github/workflows/ci.yml`（镜像×2 + helm lint job）、`docs/deploy/k8s.md`。三单服务 exec jar（既有）为镜像唯一服务端交付物。

## 验收方式

对应测试文档：[phase-4-helm-test.md](phase-4-helm-test.md)

冒烟：`helm lint` + kind 集群 `helm install` 三服务起 + 就绪探针通过 + SDK 走通。

## 产品设计（③ 补）

### 1. 三单服务镜像（`Dockerfile.service`）

```bash
# 根目录；三服务参数化单文件（registry/discovery/management 差异仅构建目标与 jar 路径）
docker build -f Dockerfile.service --build-arg SERVICE=discovery -t artemis-discovery:local .
```

| 项 | 值 | 说明 |
|---|---|---|
| `ARG SERVICE` | `registry` \| `discovery` \| `management` | build stage 与 runtime COPY 均以 `artemis-${SERVICE}-server` 取 jar；非法值 build 失败 |
| 构建目标 | `-pl :artemis-${SERVICE}-server -am package -DskipTests` | 同既有两 Dockerfile 模式（`-DskipTests` 非 `maven.test.skip`，test-jar 依赖理由同） |
| 运行镜像 / 用户 / ENTRYPOINT | `eclipse-temurin:25-jre` / uid 10001 `artemis` / `java -jar /app/app.jar` | 与既有镜像一致 |
| data 卷 | 仅 registry 创建 `/app/data`（快照落盘） | discovery/management 无状态不建 |
| 端口 | registry `8080/9090/9093`；discovery `8090/9095`；management `8081/9094` | **不设 EXPOSE**（ARG 无法条件化，`docker run -P` 场景显式 `-p`）；K8s 端口以 chart containerPort 为准。管理端口（9091/9092/9096）不对外，K8s 探针走 env 覆盖地址后经 pod 网络直达（见 §6） |

三次 build 各自 `mvn`（`COPY java/proto` 层共享 build cache），构建时长三倍于单镜像——冒烟/CI 可接受，不为提速引入 cache mount（与既有 Dockerfile 模式保持一致）。

### 2. chart 结构与 values 骨架

```text
deploy/helm/artemis/
├── Chart.yaml            # name: artemis, version: 0.1.0, appVersion: 0.1.0-SNAPSHOT（跟服务端 ${revision}）
├── values.yaml
└── templates/
    ├── _helpers.tpl      # 名字/标签助手
    ├── registry/         # statefulset.yaml + svc-peer.yaml(headless) + svc.yaml(external)
    ├── discovery/        # deployment.yaml + svc.yaml
    ├── management/       # deployment.yaml + svc.yaml
    └── serviceaccount.yaml
```

- 三服务各设 `enabled` 开关（默认 `true`）——「独立编排」语义：可只装 registry+discovery（外置治理面）等组合；关 management 时 discovery 规则流端点不注入（fail-open 既有语义）。
- 配置注入**全 env**（`ARTEMIS_*` SCF relaxed + `SPRING_DATASOURCE_*` Spring 标准 + `MANAGEMENT_SERVER_*`）：与 all-in-one 容器形态同一出口，模板不生成 ConfigMap/properties 文件，upgrade 即改 env。

**values 口径**（完整清单见 chart `values.yaml`，此处列语义分组）：

| 组 | 键（节选） | 默认 | 说明 |
|---|---|---|---|
| 全局镜像 | `image.registry` / `tag` / `pullPolicy`，`image.servicePrefix` | 空 / `local` / `IfNotPresent` / `artemis-` | 三服务镜像名 `<registry/><servicePrefix><service>:<tag>`；空 registry = 本地/内网名 |
| registry | `replicas` | `1` | StatefulSet 副本；扩容即改（静态成员列表随渲染更新，滚动重启生效） |
| | `resources` / `storage.size` / `storage.className` | 宽松 / `1Gi` / 空(默认 SC) | PVC（volumeClaimTemplates，快照卷） |
| | `membersOverride` | `""` | 完整成员列表（三段式逗号分隔），显式配置优先（产品语义）；默认由 `replicas` 渲染（§3） |
| | `selfOverride` | `""` | 默认 `fieldRef metadata.name` 注入 |
| discovery | `nodes` | 渲染值 | SDK 节点目录（`artemis.discovery.nodes`）；默认指向本 chart discovery svc |
| | `clusters` | 渲染值 | 上游 registry 集群 map（透传 JSON；默认 `{"default": "<peer headless 列表>"}`） |
| management | `db.enabled` / `db.url` / `db.username` / `db.existingSecret` | `false` / — | §5 |
| 治理面 | `management.enabled`（开关本身） | `true` | 关闭时 discovery `management.endpoints` 不注入 |
| 安全 | `auth.{registry,discovery,management}.{username,password}` | `artemis` | env 直传（eval 哲学）；生产经 `--set` 或改 values，文档提示 |
| | `tls.*` | 全关 | §7 |
| 探针/杂项 | `probe.*` / 各服务 `extraEnv` | — | 逃生口：任意 env 追加（不预置未消费的键） |

### 3. registry StatefulSet 编排

```yaml
# svc-peer（headless，peer 9093 + management 只读查询）：成员稳定 DNS
# svc（ClusterIP，external gRPC 9090 + HTTP 8080）：SDK/管理面接入
```

- **成员表渲染**：默认按 `replicas` 生成——peer 端点 `<pod>.<peer-svc>.<ns>.svc:9093`，external 端点 `<ext-svc>.<ns>.svc:9090`（Service 稳定入口；SDK 容灾换连接由 client 熔断兜底，长连接经 ClusterIP 轮询可接受）。条目三段式 `<svc>-<ordinal>@<peer-dns>:9093@<ext-svc-dns>:9090`。`membersOverride` 非空时整体替换。
- **self**：`fieldRef metadata.name` → `ARTEMIS_REGISTRY_CLUSTER_SELF`；`selfOverride` 优先。
- **PVC**：`volumeClaimTemplates` 挂 `/app/data`（快照 `data/registry-snapshot.bin` 相对工作目录，镜像已建目录）；`fsGroup: 10001` 对齐非 root 用户。
- **pod DNS 与 peer 绑定**：单服务部署 peer 端口绑 `0.0.0.0`（all-in-one 的 loopback 绑定是组合形态 launcher 语义，单服务集群复制本就跨机），headless 直达 pod。

### 4. discovery / management Deployment 编排

- **discovery**（`replicas` 可配，默认 1，无状态可扩）：
  - `ARTEMIS_DISCOVERY_REGISTRY_CLUSTERS`：默认 `{"default":"<registry peer pod 列表逗号分隔>"}`（渲染自 `registry.replicas`）；`values.discovery.clusters` 显式配置优先（多集群 union 自配）。
  - `ARTEMIS_DISCOVERY_NODES`：默认 `<discovery-svc>.<ns>.svc:9095`。
  - `management.enabled=true` 时注入 `ARTEMIS_DISCOVERY_MANAGEMENT_ENDPOINTS=<mgt-svc>:9094`（治理链闭合；关闭则不注入 = fail-open）。
- **management**（`replicas` 可配，默认 1）：
  - `ARTEMIS_MANAGEMENT_GRPC_PORT=9094`（独立部署默认 `-1` 不监听；K8s 形态显式启用——discovery 要订阅规则流）。
  - `ARTEMIS_MANAGEMENT_REGISTRY_ENDPOINT=<registry peer svc>:9093`（只读事实查询，走 peer 端口）。
  - `ARTEMIS_MANAGEMENT_DISCOVERY_ENDPOINTS=http://<discovery-svc>.<ns>.svc:8090`（订阅关系视图源，v0.5 phase-5 键）。
  - DB 见 §5。

### 5. DB 可选组件（management）

- `db.enabled=false`（默认）：零 `SPRING_DATASOURCE_*` 注入 → 内存态（单机 eval 语义：摘除/逻辑实例重启丢失，RBAC 角色退化单管理员——既有双轨语义，不改）。
- `db.enabled=true`：注入 `SPRING_DATASOURCE_URL/USERNAME`（值来自 values）+ `SPRING_DATASOURCE_PASSWORD`（`db.existingSecret` 引用时 password 改 secretKeyRef，username/url 仍 env）；Flyway migration 按 url 方言自动（既有机制）。
- 不内置 DB 子 chart / 不内置 mysql/postgres Deployment：外接现成 DB 是 K8s 常态，连接串透传即全部所需。

### 6. 探针

- **registry readiness 用外部 `/readyz`**（HTTP 8080，无认证白名单，**排水期 503**——优雅停机时 K8s 自动摘流量，语义对接比 actuator readiness 更准；registry 专属端点，discovery/management 无此端点）。
- 其余探针走 actuator 管理端口（`management.server.port`：registry 9091 / discovery 9096 / management 9092）：liveness `GET /actuator/health/liveness`、readiness `GET /actuator/health/readiness`（discovery/management）。
- **plan 原文「探针接 `/readyz`」定案**：registry 直用既有 `/readyz`；discovery/management 用 actuator probes 路径（`management.endpoint.health.probes.enabled=true` 已开，各服务 application.properties 既有），不新增任何端点。
- env 注入 `MANAGEMENT_SERVER_ADDRESS=0.0.0.0`（产品默认 `127.0.0.1` 不变——application.properties 注释本就指引「容器/集群部署显式改」；K8s kubelet 经 pod 网络探测，loopback 不可达）。管理端口不出 Service（不暴露集群外，与「管理端口绑 loopback」产品哲学同向，网络策略后置 phase-6/7）。
- startupProbe：registry 快照回放 + peer bootstrap 慢于默认探测窗口，宽裕值（5s × 36 = 3 分钟；discovery/management 5s × 24）。

### 7. TLS / 认证凭证 / RBAC 透出

- **TLS**（默认全关，与产品「显式启用」一致）：每服务 `tls.enabled` + `tls.secretName`（用户自备，含 cert-chain/private-key/trust-ca PEM）；挂载 `/etc/artemis/tls` 只读，env 注入既有键（`SPRING_SSL_BUNDLE_*` 框架键 + `ARTEMIS_*_INTERNAL_TLS_*` 内部通道键 + 出站 `ARTEMIS_*_TLS_TRUSTCAPATH`）——键面与 phase-1 交付一致，chart 只做挂载与透传，不生成证书。
- **认证凭证**：默认 `artemis/artemis` env 直传；`auth.*.existingSecret` 可选（secretKeyRef 替换明文）。
- **RBAC**：角色/账户模型依赖 management DB（phase-2）——`db.enabled=true` 即全量；内存态为单管理员既有语义。chart 无独立 RBAC 键。

### 8. 部署冒烟（`smoke-deploy.sh` 新增 `helm` 模式）

- **工具前置**：`require_tools helm kind kubectl docker`（fail-fast）；本机安装 helm/kind/kubectl 到 `~/.local/bin`（⑤ 前完成，不入库）。
- **流程**：kind create cluster（名 `artemis-smoke`，用后删）→ 三镜像 build（`Dockerfile.service`×3，tag `:smoke`）→ `kind load docker-image`×3 → `helm lint` → `helm install artemis`（镜像 tag=smoke + `membersOverride`/`nodes` 覆盖为 port-forward 宿主端点，同形态 I「端口映射须覆盖节点目录」语义）→ `kubectl wait --for=condition=ready pod`（就绪探针通过即验收）→ port-forward（registry HTTP/gRPC + discovery HTTP/gRPC 四条）→ `run_sdk` 复用（注册 + 订阅发现闭环）→ console 401 探活 → `helm uninstall` + `kind delete`。
- **port-forward 与节点目录**：SDK 前置寻址拿到的端点必须宿主可达——chart values 覆盖 `registry.membersOverride=<pod名>@<peer-svc>:9093@127.0.0.1:<pf-grpc>`（成员 id 须与 self（pod 名）一致，否则 self 未命中启动失败）与 `discovery.nodes=127.0.0.1:<pf-grpc>`（覆盖位即产品「显式配置优先」出口，不为冒烟加机制；冒烟脚本实际注入 `artemis-registry-0@artemis-registry-peer.artemis.svc:9093@127.0.0.1:<pf-grpc>`）。
- CI 不跑 kind（时长与镜像 load 成本），K8s 全链路冒烟归本地/人工；CI 只 `helm lint` + `helm template` 渲染断言。

### 9. CI

- docker job：补 `docker build -f Dockerfile.service --build-arg SERVICE=discovery|management` 两步。
- 新增 helm job：`curl 安装 helm → helm lint deploy/helm/artemis → helm template`（渲染成功即过，不进集群）。
- paths：补 `Dockerfile.service`、`deploy/helm/**`。

### 10. 定案与架构同步

- **架构 §2.3 无需修订**：形态定义「K8s/Helm 部署：Helm chart，三服务可独立编排扩缩」即本 phase 落地对象，语义一致；无新机制决策，不新增 ADR（部署制品包装层，与 phase-3「零新配置键、形态不新增能力」同一归属原则：所有配置键、互连语义、双轨 DB、TLS/RBAC 键面全部复用既有产品出口）。
- **plan「探针接 /readyz」定案为 actuator probes 实际路径**（§6），属实现细节校准，plan/roadmap 无需改文。
- **`Dockerfile.service` 参数化单文件**（vs 三份 Dockerfile）：三服务构建差异仅目标模块与 jar 路径，ARG 参数化消除重复；既有两 Dockerfile 不动（phase-3「保留」定案延续）。
- **配置全 env、无 ConfigMap**：与 all-in-one 容器形态同出口（env relaxed 映射），模板面最小；工作目录 properties 文件出口留给 VM 形态（载体惯例各随其形）。
- 触及原始文档：`docs/deploy/README.md`（登记 k8s.md）、`deploy/README.md`（chart 登记）、`docs/product/artemis.md`（⑩ 交付节）。

## 实现结果（⑦ 补）

**实现要点**：`Dockerfile.service`（ARG SERVICE 参数化，EXPOSE 不可条件化故以注释列端口、K8s 端口以 chart 为准）+ `deploy/helm/artemis/`（Chart/values/helpers + registry StatefulSet×2 svc/discovery/management Deployment×svc/SA，配置全 env 注入）+ `smoke-deploy.sh` helm 模式（kind + 三镜像 load + port-forward + SDK harness 复用）+ CI（镜像×2 + helm job 渲染断言）。

**⑤ 期冒烟脚本修复**（既有 phase-3 交付物的缺陷，基线顺带修复）：`sdk_classpath` 三处——① 缓存判断对 client jar 缺失（新 worktree）误判有效（`-nt` 对空串失效）→ 补 `[ ! -f ]` 强制重建；② `mvn install` 只 `-DskipTests` 不跳 failsafe IT（client `DiscoveryClientIT` 照跑，且 JaCoCo agent 在 JDK 25 上 instrument grpc 1.84.2 netty 新类失败刷屏）→ 补 `-DskipITs`；③ 调用方 `$(sdk_classpath)` 捕获函数全部 stdout 作 classpath，mvn/测试/JaCoCo 输出混入即污染（表现 `NoClassDefFoundError`）→ 函数内输出 stderr 化 + mvn 输出隔离、失败 fail。另加 run_sdk 失败时 dump classpath 现场（诊断保留）。

**⑥ findings 处置（10/10 修）**：

1. `ARTEMIS_MANAGEMENT_DISCOVERY_ENDPOINTS` 多拼 `http://`（消费方 `DiscoveryAdminClient` 自拼前缀，双前缀致订阅视图全挂）→ drop 前缀（源码实证 `DiscoveryAdminClient.java:45`）。
2. `auth.enabled` 只控凭证注入不发 `ARTEMIS_*_AUTH_ENABLED`——「关」不生效（默认凭证仍在跑）、「开」不生效（discovery 默认关）→ authEnv helper 必发 ENABLED 键。
3. registry TLS 开启时 readiness `/readyz`（8080 已 HTTPS）仍 httpGet → tls.enabled 时 scheme: HTTPS（actuator 管理端口独立监听不受影响）。
4. smoke helm 模式 `docker build -f Dockerfile.service` 相对 cwd → 绝对路径 `$REPO_ROOT/...`。
5. `kubectl config use-context` 切全局 context 不恢复 → 记录原 context，cleanup 恢复。
6. uninstall 残留断言与异步删除竞态（helm 无 --wait 即返回）→ `--wait --timeout 120s`。
7. CI helm job 注释承诺的断言（成员覆盖/fail 场景/env grep）未实现 → 补齐（拓扑计数、env 逐项 grep、成员覆盖、TLS scheme、三个 fail 场景退出码断言）。
8. discovery/management 出站 TLS 与本 chart registry TLS 不配对（TLS 拨明文必失败且无启动报错指向）→ 模板 fail 守卫（外置集群经 `discovery.clusters` 显式配置豁免）。
9. phase 文档三处漂移（EXPOSE 表、appVersion、§8 成员 id 示例与 self 不匹配）→ 修正。
10. fullname trunc 63 后拼组件后缀超 DNS 标签 → 预算 trunc 50（63 − 组件后缀 10 − ordinal 2）；SA 名统一走 fullname。

**⑧ 回归**：渲染断言（lint/默认+变体拓扑/env/守卫退出码/长名截断）全绿 + kind 全链路冒烟 ALL PASS（SDK instances=1、uninstall 干净、context 恢复）；Java 零变更（⑤ 的 dev-test all BUILD SUCCESS 未失效，未重跑）。

## 文档同步（⑩ 填）

| 文档 | 变更 |
|---|---|
| `docs/product/artemis.md`（v1.40） | 交付状态新增「v1.0 phase-4（部署形态 II）」节（镜像/chart/探针/values 口径/冒烟/文档） |
| `docs/deploy/k8s.md`（新建） | K8s/Helm 形态文档（拓扑/values 表/探针/集群内外 SDK 接入/卸载与 PVC 语义） |
| `docs/deploy/README.md` | 登记 k8s.md（可用） |
| `deploy/README.md` | helm/ 登记指向 docs/deploy/k8s.md |
| `docs/milestones/v1.0/phases/README.md` | 两文档登记（① 已做） |
| CLAUDE.md | 未过时（构建/测试节不含部署入口，phase-3 定案延续：部署入口由 docs/deploy/ 承载） |
| ADR / 架构文档 | 无——§2.3 形态定义即本 phase 落地对象，零新机制决策（§10 定案节） |

## 更新历史

| 版本 | 日期 | 变更说明 |
| ------ | ------ | ------ |
| 1.4 | 2026-10-28 | ⑩ 文档同步清单（产品文档 v1.40 交付节 + docs/deploy/k8s.md + 索引） |
| 1.3 | 2026-10-28 | ⑦ 实现结果与 review 修复记录（⑥ 10 findings 全修 + ⑤ 期冒烟脚本 classpath 污染修复） |
| 1.2 | 2026-10-28 | ④ 回写：registry readiness 改用外部 `/readyz`（实现期确认 registry 专属、排水 503 语义正对接 K8s 摘流量） |
| 1.1 | 2026-10-28 | 设计定稿（镜像/chart 结构/编排/DB 可选/探针路径定案/冒烟/CI） |
| 1.0 | 2026-10-28 | 开工骨架 |
