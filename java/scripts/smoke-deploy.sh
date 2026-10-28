#!/usr/bin/env bash
# 部署冒烟（v1.0 部署形态 I + II）：docker 模式（all-in-one 镜像一条命令全功能）、vm 模式（tar.gz 解包
# 生命周期）与 helm 模式（kind 集群 helm install 三服务 + 就绪探针 + SDK 走通）。
# 独立于 dev-test.sh（非 mvn 形态，不进 verify 生命周期）；用例映射见
# docs/milestones/v1.0/phases/phase-3-docker-vm-test.md 与 phase-4-helm-test.md。
# 用法：smoke-deploy.sh [docker|vm|helm|all]（默认 all）
#
# 宿主端口（低占用段，env 可覆盖）：docker 主容器 18090/18095/18081，env 覆盖用例容器 18080；
# vm 包经 artemis.properties 改 registry.httpPort=18080 + discovery.grpcPort=18095；
# helm 模式 port-forward 用 28xxx 段（SMOKE_HELM_* 覆盖）。
# helm 模式工具前置：helm / kind / kubectl / docker；SMOKE_HELM_KEEP=1 失败时保留集群排障（默认清理）。
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
MODE="${1:-all}"

SMOKE_IMAGE="artemis-all-in-one:smoke"
DOCKER_REG_GRPC="${SMOKE_DOCKER_REG_GRPC:-18090}"
DOCKER_REG_HTTP="${SMOKE_DOCKER_REG_HTTP:-18088}"
DOCKER_DISC_GRPC="${SMOKE_DOCKER_DISC_GRPC:-18095}"
DOCKER_DISC_HTTP="${SMOKE_DOCKER_DISC_HTTP:-18089}"
DOCKER_MGT_HTTP="${SMOKE_DOCKER_MGT_HTTP:-18081}"
ENV_CASE_HTTP="${SMOKE_ENV_CASE_HTTP:-18080}"
VM_TMP="$(mktemp -d /tmp/artemis-vm-smoke.XXXXXX)"
DOCKER_CONTAINERS=()

# ---- helm 模式 ----
KIND_CLUSTER="artemis-smoke"
HELM_NS="artemis"
HELM_REG_HTTP="${SMOKE_HELM_REG_HTTP:-28080}"
HELM_REG_GRPC="${SMOKE_HELM_REG_GRPC:-28090}"
HELM_DISC_HTTP="${SMOKE_HELM_DISC_HTTP:-28085}"
HELM_DISC_GRPC="${SMOKE_HELM_DISC_GRPC:-28095}"
HELM_MGT_HTTP="${SMOKE_HELM_MGT_HTTP:-28081}"
PF_PIDS=()
ORIG_KUBE_CTX="$(kubectl config current-context 2>/dev/null || true)"

cleanup_containers() {
    if [ ${#DOCKER_CONTAINERS[@]} -gt 0 ]; then
        docker rm -f "${DOCKER_CONTAINERS[@]}" >/dev/null 2>&1 || true
        DOCKER_CONTAINERS=()
    fi
}

cleanup() {
    local failed=$?
    cleanup_containers
    if [ -f "$VM_TMP/artemis/app.pid" ] && [ -d "$VM_TMP" ]; then
        "$VM_TMP/artemis/bin/app.sh" stop >/dev/null 2>&1 || true
    fi
    docker volume rm artemis-smoke-data >/dev/null 2>&1 || true
    rm -rf "$VM_TMP"
    if [ ${#PF_PIDS[@]} -gt 0 ]; then
        kill "${PF_PIDS[@]}" >/dev/null 2>&1 || true
        PF_PIDS=()
    fi
    if [ "$MODE" = "helm" ] || [ "$MODE" = "all" ]; then
        helm uninstall artemis -n "$HELM_NS" >/dev/null 2>&1 || true
        if [ "$failed" -eq 0 ] || [ -z "${SMOKE_HELM_KEEP:-}" ]; then
            kind delete cluster --name "$KIND_CLUSTER" >/dev/null 2>&1 || true
        else
            echo "==> keep kind cluster $KIND_CLUSTER for debugging (SMOKE_HELM_KEEP=1)" >&2
        fi
        # 恢复冒烟前的 kubectl context（use-context 改的是全局 kubeconfig）
        if [ -n "$ORIG_KUBE_CTX" ] && [ "$ORIG_KUBE_CTX" != "kind-$KIND_CLUSTER" ]; then
            kubectl config use-context "$ORIG_KUBE_CTX" >/dev/null 2>&1 || true
        fi
    fi
    exit $failed
}
trap cleanup EXIT

log() { echo "==> $*"; }
fail() { echo "FAIL: $*" >&2; exit 1; }

require_tools() { # 预检工具存在性（fail-fast，避免跑到一半才暴露）
    local missing=()
    local tool
    for tool in "$@"; do
        command -v "$tool" >/dev/null 2>&1 || missing+=("$tool")
    done
    [ ${#missing[@]} -eq 0 ] || fail "required tools not found: ${missing[*]}"
}

wait_tcp() { # host port timeout_s label
    local host="$1" port="$2" timeout="$3" label="$4"
    log "waiting for $label ($host:$port, up to ${timeout}s)"
    for _ in $(seq 1 "$timeout"); do
        if (exec 3<>"/dev/tcp/$host/$port") 2>/dev/null; then exec 3>&- 3<&-; log "$label ready"; return 0; fi
        sleep 1
    done
    fail "$label not reachable at $host:$port within ${timeout}s"
}

wait_no_tcp() { # host port timeout_s label（断言端口关闭：stop 干净退出用例）
    local host="$1" port="$2" timeout="$3" label="$4"
    for _ in $(seq 1 "$timeout"); do
        (exec 3<>"/dev/tcp/$host/$port") 2>/dev/null || { exec 3>&- 3<&- 2>/dev/null; log "$label closed"; return 0; }
        exec 3>&- 3<&- 2>/dev/null
        sleep 1
    done
    fail "$label still listening at $host:$port after ${timeout}s"
}

wait_container_started() { # container timeout_s
    local container="$1" timeout="$2"
    log "waiting for container log 'all-in-one started' (up to ${timeout}s)"
    for _ in $(seq 1 "$timeout"); do
        if docker logs "$container" 2>&1 | grep -q "all-in-one started"; then return 0; fi
        sleep 1
    done
    docker logs --tail 50 "$container" >&2 || true
    fail "container $container did not report 'all-in-one started' within ${timeout}s (last 50 log lines above)"
}

# ---- SDK harness：classpath = 依赖（dependency:build-classpath，缓存 /tmp）+ client 自身 jar ----
SDK_CP_FILE="/tmp/artemis-smoke-sdk-cp.txt"
sdk_classpath() {
    # 缓存失效：client jar 更新（mtime 新于缓存）即重建，避免依赖集变更后 classpath 陈旧
    local client_jar
    client_jar="$(ls "$REPO_ROOT"/java/client/artemis-client/target/artemis-client-*.jar 2>/dev/null | head -n1)"
    # 缓存失效：不存在缓存 / jar 未构建（新 worktree，-nt 对空串失效会误判缓存有效）/ jar 更新
    if [ ! -s "$SDK_CP_FILE" ] || [ ! -f "$client_jar" ] || [ "$client_jar" -nt "$SDK_CP_FILE" ]; then
        # 本分支所有输出走 stderr 并隔离 mvn 输出：调用方以 $() 捕获函数 stdout 作 classpath，
        # 任何混入（log 行、surefire/failsafe 测试与 JaCoCo 输出）都会污染 classpath
        echo "==> building SDK classpath (client line install + dependency:build-classpath)" >&2
        # -DskipTests 只跳 surefire；failsafe IT 须 -DskipITs（install 全生命周期会跑 IT，慢且输出海啸）
        mvn -B -q -f "$REPO_ROOT/java/client/pom.xml" -DskipTests -DskipITs install >/dev/null 2>&1 \
            || fail "client line mvn install failed (rerun manually: mvn -f java/client/pom.xml install)"
        mvn -B -q -f "$REPO_ROOT/java/client/artemis-client/pom.xml" \
            dependency:build-classpath -Dmdep.outputFile="$SDK_CP_FILE" >/dev/null 2>&1 \
            || fail "dependency:build-classpath failed"
        client_jar="$(ls "$REPO_ROOT"/java/client/artemis-client/target/artemis-client-*.jar | head -n1)"
    fi
    [ -n "$client_jar" ] && [ -f "$client_jar" ] \
        || fail "artemis-client jar not found (run: mvn -f java/client/pom.xml package)"
    echo "$(cat "$SDK_CP_FILE"):$client_jar"
}

run_sdk() { # registryHttpUrl discoveryHttpUrl label（SDK 前置寻址经 HTTP 拉节点目录，ADR-020）
    local cp
    cp="$(sdk_classpath)"
    log "SDK smoke against registry=$1 discovery=$2"
    java -cp "$cp" "$SCRIPT_DIR/SmokeSdk.java" "$1" "$2" \
        || { printf '%s\n' "$cp" > /tmp/artemis-smoke-sdk-fail-cp.txt; fail "SDK smoke failed ($3)"; }
}

probe_unauthorized() { # url label —— 默认认证开启，匿名访问预期 401
    if ! command -v curl >/dev/null 2>&1; then
        log "WARN: curl not found, skipping $2 401 probe"
        return 0
    fi
    local code
    code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 "$1" || echo 000)"
    [ "$code" = "401" ] || fail "$2: expected 401 (default auth), got $code"
    log "$2: default auth enforced (401)"
}

# ============================== docker 模式 ==============================

smoke_docker() {
    require_tools docker java mvn curl
    log "building all-in-one image ($SMOKE_IMAGE)"
    (cd "$REPO_ROOT" && docker build -f Dockerfile.all-in-one -t "$SMOKE_IMAGE" .)

    local container="artemis-smoke-$RANDOM"
    DOCKER_CONTAINERS+=("$container")
    log "run container $container (mapped: reg-http=$DOCKER_REG_HTTP reg-grpc=$DOCKER_REG_GRPC disc-http=$DOCKER_DISC_HTTP disc-grpc=$DOCKER_DISC_GRPC mgt-http=$DOCKER_MGT_HTTP)"
    # 节点目录与集群成员显式配置宿主视角端点（显式配置优先于 launcher 的 loopback 默认）——
    # 端口映射下 SDK 从目录拿到的容器内地址指向宿主自身而非容器，须覆盖为映射端口
    docker run -d --name "$container" \
        -p "$DOCKER_REG_HTTP:8080" -p "$DOCKER_REG_GRPC:9090" \
        -p "$DOCKER_DISC_HTTP:8090" -p "$DOCKER_DISC_GRPC:9095" \
        -p "$DOCKER_MGT_HTTP:8081" \
        -e "ARTEMIS_REGISTRY_CLUSTER_MEMBERS=all-in-one@127.0.0.1:9093@127.0.0.1:$DOCKER_REG_GRPC" \
        -e "ARTEMIS_DISCOVERY_NODES=127.0.0.1:$DOCKER_DISC_GRPC" \
        -v artemis-smoke-data:/app/data \
        "$SMOKE_IMAGE" >/dev/null
    wait_container_started "$container" 300
    wait_tcp 127.0.0.1 "$DOCKER_REG_HTTP" 30 "registry HTTP (SDK bootstrap)"
    wait_tcp 127.0.0.1 "$DOCKER_REG_GRPC" 30 "registry gRPC"
    wait_tcp 127.0.0.1 "$DOCKER_DISC_HTTP" 30 "discovery HTTP (SDK bootstrap)"
    wait_tcp 127.0.0.1 "$DOCKER_DISC_GRPC" 30 "discovery gRPC"
    wait_tcp 127.0.0.1 "$DOCKER_MGT_HTTP" 30 "management HTTP/console"
    probe_unauthorized "http://127.0.0.1:$DOCKER_MGT_HTTP/" "console anonymous"
    run_sdk "http://127.0.0.1:$DOCKER_REG_HTTP" "http://127.0.0.1:$DOCKER_DISC_HTTP" "docker container"

    # env 覆盖用例：ARTEMIS_DIST_REGISTRY_HTTP_PORT 生效（SCF relaxed 映射 artemis.dist.registry.httpPort）。
    # 生效断言用 HTTP 语义探测（匿名 /cluster/nodes 预期 401）而非纯 TCP——docker userland-proxy
    # 对容器内未监听端口也接受宿主连接，纯 TCP 探测在 env 未生效时会假绿（v1.0 phase-7 修正：
    # 原用例拼错变量名 ARTHEMIS_* 且仅 TCP 探测，env 从未生效却一直「绿」）
    local env_container="artemis-smoke-env-$RANDOM"
    DOCKER_CONTAINERS+=("$env_container")
    log "env override case: ARTEMIS_DIST_REGISTRY_HTTP_PORT=$ENV_CASE_HTTP"
    docker run -d --name "$env_container" \
        -e "ARTEMIS_DIST_REGISTRY_HTTP_PORT=$ENV_CASE_HTTP" \
        -p "$ENV_CASE_HTTP:$ENV_CASE_HTTP" \
        "$SMOKE_IMAGE" >/dev/null
    wait_container_started "$env_container" 300
    # 401 = registry HTTP 真服务在覆盖端口（默认认证拒绝匿名）；503/000 = readiness 未就绪，重试等待
    local code=""
    for _ in $(seq 1 30); do
        code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 "http://127.0.0.1:$ENV_CASE_HTTP/cluster/nodes" || echo 000)"
        [ "$code" = "401" ] && break
        { [ "$code" = "503" ] || [ "$code" = "000" ]; } || fail "env-overridden registry HTTP: unexpected code $code"
        sleep 1
    done
    if [ "$code" != "401" ]; then
        docker logs --tail 20 "$env_container" >&2 || true
        fail "env override not applied (registry HTTP not serving on $ENV_CASE_HTTP, last code $code)"
    fi
    log "env override verified: registry HTTP serving on $ENV_CASE_HTTP (401 default auth)"
    cleanup_containers
    log "PASS: docker mode"
}

# ============================== vm 模式 ==============================

smoke_vm() {
    require_tools java mvn
    log "building VM package"
    bash "$SCRIPT_DIR/build-dist.sh"
    local tarball
    tarball="$(ls "$REPO_ROOT"/build/artemis-*.tar.gz | head -n1)"

    log "unpacking $tarball -> $VM_TMP"
    tar -xzf "$tarball" -C "$VM_TMP"
    local pkg="$VM_TMP/artemis"

    # 结构断言
    local missing=()
    for f in bin/app.sh lib/artemis-dist.jar lib/version artemis.properties artemis.service; do
        [ -f "$pkg/$f" ] || missing+=("$f")
    done
    [ -d "$pkg/data" ] || missing+=("data/")
    [ ${#missing[@]} -eq 0 ] || fail "package structure incomplete, missing: ${missing[*]}"
    grep -q '^VERSION=' "$pkg/lib/version" || fail "lib/version missing VERSION"
    log "package structure complete"

    # 配置文件覆盖用例：包根 artemis.properties 改端口（http 18080 + discovery grpc 18095 避让宿主端口）
    log "file config case: registry.httpPort=$ENV_CASE_HTTP discovery.grpcPort=$DOCKER_DISC_GRPC"
    {
        echo "artemis.dist.registry.httpPort=$ENV_CASE_HTTP"
        echo "artemis.dist.discovery.grpcPort=$DOCKER_DISC_GRPC"
    } >>"$pkg/artemis.properties"

    "$pkg/bin/app.sh" start
    wait_tcp 127.0.0.1 "$ENV_CASE_HTTP" 120 "registry HTTP (file-overridden)"
    wait_tcp 127.0.0.1 "$DOCKER_DISC_GRPC" 120 "discovery gRPC (file-overridden)"
    # discovery HTTP 用默认 8090（SDK 寻址入口）；节点目录由 launcher 注入 127.0.0.1:18095（宿主直跑可达）
    run_sdk "http://127.0.0.1:$ENV_CASE_HTTP" "http://127.0.0.1:8090" "VM package"

    "$pkg/bin/app.sh" stop
    wait_no_tcp 127.0.0.1 "$ENV_CASE_HTTP" 30 "registry HTTP after stop"
    ! pgrep -f "$pkg/lib/artemis-dist.jar" >/dev/null || fail "app.sh stop left a running process"
    log "PASS: vm mode"
}

# ============================== helm 模式（K8s 部署形态 II）==============================

pf() { # svcName localPort remotePort —— 后台 port-forward，pid 记入 PF_PIDS
    kubectl -n "$HELM_NS" port-forward "svc/$1" "$2:$3" >/dev/null 2>&1 &
    PF_PIDS+=("$!")
}

smoke_helm() {
    require_tools docker java mvn helm kind kubectl
    log "creating kind cluster $KIND_CLUSTER (reuse if exists)"
    if ! kind get clusters 2>/dev/null | grep -qx "$KIND_CLUSTER"; then
        kind create cluster --name "$KIND_CLUSTER"
    fi
    local ctx="kind-$KIND_CLUSTER"
    kubectl config use-context "$ctx" >/dev/null

    local svc
    for svc in registry discovery management; do
        log "building service image ($svc)"
        docker build -f "$REPO_ROOT/Dockerfile.service" --build-arg "SERVICE=$svc" -t "artemis-$svc:smoke" "$REPO_ROOT"
        kind load docker-image "artemis-$svc:smoke" --name "$KIND_CLUSTER"
    done

    log "helm lint"
    helm lint "$REPO_ROOT/deploy/helm/artemis"

    # membersOverride/nodes 覆盖为 port-forward 宿主端点（同 docker 模式「端口映射须覆盖节点目录」语义：
    # SDK 从节点目录拿到的地址必须宿主可达）
    log "helm install (namespace $HELM_NS)"
    helm install artemis "$REPO_ROOT/deploy/helm/artemis" \
        --namespace "$HELM_NS" --create-namespace \
        --set image.tag=smoke \
        --set-string "registry.membersOverride=artemis-registry-0@artemis-registry-peer.$HELM_NS.svc:9093@127.0.0.1:$HELM_REG_GRPC" \
        --set-string "discovery.nodes=127.0.0.1:$HELM_DISC_GRPC"

    log "waiting for all pods ready (readiness probe, up to 300s)"
    kubectl -n "$HELM_NS" wait --for=condition=Ready pod \
        -l app.kubernetes.io/instance=artemis --timeout=300s

    pf artemis-registry "$HELM_REG_HTTP" 8080
    pf artemis-registry "$HELM_REG_GRPC" 9090
    pf artemis-discovery "$HELM_DISC_HTTP" 8090
    pf artemis-discovery "$HELM_DISC_GRPC" 9095
    pf artemis-management "$HELM_MGT_HTTP" 8081
    wait_tcp 127.0.0.1 "$HELM_REG_HTTP" 30 "registry HTTP (SDK bootstrap, port-forward)"
    wait_tcp 127.0.0.1 "$HELM_REG_GRPC" 30 "registry gRPC (port-forward)"
    wait_tcp 127.0.0.1 "$HELM_DISC_HTTP" 30 "discovery HTTP (SDK bootstrap, port-forward)"
    wait_tcp 127.0.0.1 "$HELM_DISC_GRPC" 30 "discovery gRPC (port-forward)"
    wait_tcp 127.0.0.1 "$HELM_MGT_HTTP" 30 "management HTTP/console (port-forward)"

    probe_unauthorized "http://127.0.0.1:$HELM_MGT_HTTP/" "console anonymous"
    run_sdk "http://127.0.0.1:$HELM_REG_HTTP" "http://127.0.0.1:$HELM_DISC_HTTP" "helm release"

    log "uninstall"
    kill "${PF_PIDS[@]}" >/dev/null 2>&1 || true
    PF_PIDS=()
    # --wait：helm uninstall 默认发起即返回，sts 终止 pod 需时间，立即 get 会误报残留
    helm uninstall artemis -n "$HELM_NS" --wait --timeout 120s
    if kubectl -n "$HELM_NS" get deploy,sts 2>/dev/null | grep -qv "No resources"; then
        fail "resources left after uninstall"
    fi
    log "PASS: helm mode"
}

case "$MODE" in
    docker) smoke_docker ;;
    vm) smoke_vm ;;
    helm) smoke_helm ;;
    all) smoke_docker; smoke_vm; smoke_helm ;;
    *) echo "usage: smoke-deploy.sh [docker|vm|helm|all]" >&2; exit 2 ;;
esac

echo "==> smoke-deploy $MODE: ALL PASS"
