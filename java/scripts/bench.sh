#!/usr/bin/env bash
# 容量压测编排（v1.0 phase-6）：独立入口，不进 dev-test.sh 与 CI（时长/资源口径不合）。
# 用法: bench.sh [--profile dev|small|medium|large|custom] [--scenarios s1,s2,s3,s4,combo] \
#                [--topologyNodes 1|3] [--gc g1|zgc] [--rebuild] [--key value ...（原样转发 driver）]
# 设计与档位矩阵：docs/milestones/v1.0/phases/phase-6-capacity.md
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
reactor_dir="$(cd "${script_dir}/.." && pwd)"
repo_dir="$(cd "${reactor_dir}/.." && pwd)"

# JDK 25（非交互环境兜底；mvn 同理由 PATH 继承）
if ! command -v java >/dev/null 2>&1; then
  sdkman_java="${HOME}/.sdkman/candidates/java/current"
  if [ -x "${sdkman_java}/bin/java" ]; then
    export JAVA_HOME="${sdkman_java}" PATH="${sdkman_java}/bin:${PATH}"
  else
    echo "error: java (JDK 25) not found in PATH" >&2
    exit 1
  fi
fi

# ---- 参数透传与本地项拆分 ----
profile="small"
scenarios="s1,s2,s4"
gc="g1"
rebuild=0
jvm_args_server=""
jvm_args_driver=""
forward=()
while [ "$#" -gt 0 ]; do
  case "$1" in
    --profile) profile="$2"; shift 2 ;;
    --scenarios) scenarios="$2"; shift 2 ;;
    --gc) gc="$2"; shift 2 ;;
    --jvm-args-server) jvm_args_server="$2"; shift 2 ;;
    --jvm-args-driver) jvm_args_driver="$2"; shift 2 ;;
    --rebuild) rebuild=1; shift ;;
    *) forward+=("$1" "$2"); shift 2 ;;
  esac
done

report_dir="${script_dir}/bench-report/$(date +%Y%m%d-%H%M%S)"
mkdir -p "${report_dir}"
FAILED_SCENARIOS=0

# ---- 预检 1：jemalloc 强制（无回退，未装拒绝启动） ----
# awk exit 使上游 ldconfig 收 SIGPIPE，pipefail 下须兜底（否则 set -e 杀脚本）
jemalloc_so="$(ldconfig -p 2>/dev/null | awk '/libjemalloc\.so/{print $NF; exit}' || true)"
if [ -z "${jemalloc_so}" ]; then
  echo "error: jemalloc not found (required, no fallback): apt install libjemalloc2" >&2
  exit 1
fi
export LD_PRELOAD="${jemalloc_so}"

# ---- 构建产物（缺失或 --rebuild 时构建） ----
bench_jar="$(ls "${reactor_dir}/client/artemis-bench/target/artemis-bench-"*"-SNAPSHOT.jar" 2>/dev/null | head -1 || true)"
registry_jar="$(ls "${reactor_dir}/server/artemis-registry-server/target/artemis-registry-server-"*"-exec.jar" 2>/dev/null | head -1 || true)"
discovery_jar="$(ls "${reactor_dir}/server/artemis-discovery-server/target/artemis-discovery-server-"*"-exec.jar" 2>/dev/null | head -1 || true)"
if [ "${rebuild}" = 1 ] || [ -z "${bench_jar}" ] || [ -z "${registry_jar}" ] || [ -z "${discovery_jar}" ]; then
  echo "== building artifacts (skip tests) =="
  mvn -B -q -f "${reactor_dir}/pom.xml" \
    -pl ":artemis-bench,:artemis-registry-server,:artemis-discovery-server" -am package -DskipTests
  bench_jar="$(ls "${reactor_dir}/client/artemis-bench/target/artemis-bench-"*"-SNAPSHOT.jar" | head -1)"
  registry_jar="$(ls "${reactor_dir}/server/artemis-registry-server/target/artemis-registry-server-"*"-exec.jar" | head -1)"
  discovery_jar="$(ls "${reactor_dir}/server/artemis-discovery-server/target/artemis-discovery-server-"*"-exec.jar" | head -1)"
fi

# ---- 预检 2：资源模型（plan 输出为单一来源） ----
plan_scenario="s1"
if [[ "${scenarios}" == *s4* || "${scenarios}" == *combo* ]]; then plan_scenario="s4"; fi
plan="$(java -jar "${bench_jar}" --role plan --profile "${profile}" --scenario "${plan_scenario}" "${forward[@]}")"
need_mb="$(echo "${plan}" | awk -F= '$1=="memoryBudgetWithMarginMb"{print $2}')"
heavy="$(echo "${plan}" | awk -F= '$1=="heavyProcessCount"{print $2}')"
avail_mb="$(awk '/MemAvailable/{print int($2/1024)}' /proc/meminfo)"
echo "== profile=${profile} need=${need_mb}MB(heap+margin) avail=${avail_mb}MB heavy-jvms=${heavy} cores=$(nproc) =="
if [ "${avail_mb}" -lt "${need_mb}" ]; then
  echo "error: available memory ${avail_mb}MB < required ${need_mb}MB — lower profile or free memory" >&2
  exit 1
fi
if [ "${heavy}" -gt "$(nproc)" ]; then
  echo "warning: heavy JVM count ${heavy} > cores $(nproc) — latency data may be polluted (设计: CPU 判据)" >&2
fi

reg_heap="$(echo "${plan}" | awk -F= '$1=="registryHeapMb"{print $2}')"
disc_heap="$(echo "${plan}" | awk -F= '$1=="discoveryHeapMb"{print $2}')"
drv_heap="$(echo "${plan}" | awk -F= '$1=="driverHeapMb"{print $2}')"
driver_jvms="$(echo "${plan}" | awk -F= '$1=="driverJvms"{print $2}')"
topology_nodes="$(echo "${plan}" | awk -F= '$1=="topologyNodes"{print $2}')"
ramp_sec="$(echo "${plan}" | awk -F= '$1=="rampSec"{print $2}')"
duration_sec="$(echo "${plan}" | awk -F= '$1=="durationSec"{print $2}')"
subscribers="$(echo "${plan}" | awk -F= '$1=="subscribers"{print $2}')"
sub_heap="$(echo "${plan}" | awk -F= '$1=="subscriberHeapMb"{print $2}')"
echo "${plan}" | tee "${report_dir}/plan.txt" >/dev/null

if [ "${gc}" = "zgc" ]; then gc_flag="-XX:+UseZGC"; else gc_flag=""; fi
jvm_common="-XX:MaxDirectMemorySize=512m -Xlog:gc:file=${report_dir}/gc-%p.log ${gc_flag} ${jvm_args_server}"
jvm_driver_extra="${jvm_args_driver}"

# ---- 进程管理 ----
declare -a CHILD_PIDS=()
LAST_PID=""
cleanup() {
  for pid in "${CHILD_PIDS[@]:-}"; do
    kill "${pid}" 2>/dev/null || true
  done
}
trap cleanup EXIT

start_bg() { # start_bg <logfile> <cmd...> → LAST_PID
  local log="$1"; shift
  "$@" >"${log}" 2>&1 &
  LAST_PID="$!"
  CHILD_PIDS+=("${LAST_PID}")
  echo "${LAST_PID}" >>"${report_dir}/pids.txt"
}

alive_any() { # 任一给定 pid 存活 → 0
  local pid
  for pid in "$@"; do
    kill -0 "${pid}" 2>/dev/null && return 0 || true
  done
  return 1
}

# ---- 拓扑拉起：registry ×R（端口段按节点错开）+ discovery ×1 ----
port_grpc=() port_http=() port_peer=() port_mgmt=()
for i in $(seq 0 $((topology_nodes - 1))); do
  port_grpc+=("$((9090 + i * 1000))")
  port_http+=("$((8080 + i * 1000))")
  port_peer+=("$((9093 + i * 1000))")
  port_mgmt+=("$((9091 + i * 1000))")
done
members=""
declare -a self_entries=()
for i in $(seq 0 $((topology_nodes - 1))); do
  entry="bench-node-${i}@127.0.0.1:${port_peer[$i]}@127.0.0.1:${port_grpc[$i]}"
  members="${members:+${members},}${entry}"
  # self 只填 node-id（StaticClusterMembers.resolveSelf 按 id 命中成员表，全条目启动失败）
  self_entries+=("bench-node-${i}")
done

for i in $(seq 0 $((topology_nodes - 1))); do
  # 单节点也按「单成员集群」配置 members/self（对齐 all-in-one 自报补全：未配 members 时
  # /cluster/nodes 无 external 端点，SDK 寻址拿空列表无法连 gRPC）
  start_bg "${report_dir}/server-registry-${i}.log" java -Xms"${reg_heap}"m -Xmx"${reg_heap}"m ${jvm_common} \
    -jar "${registry_jar}" \
    --spring.grpc.server.port="${port_grpc[$i]}" --server.port="${port_http[$i]}" \
    --management.server.port="${port_mgmt[$i]}" \
    --artemis.registry.auth.enabled=false \
    --artemis.registry.internal.port="${port_peer[$i]}" \
    --artemis.registry.snapshot.path="${report_dir}/registry-${i}-snapshot.bin" \
    --artemis.registry.cluster.members="${members}" \
    --artemis.registry.cluster.self="${self_entries[$i]}"
done
# 就绪门控：registry 全 ready 才起 discovery（discovery bootstrap 拉上游）；超时即终止（静默继续会污染整轮）
wait_ready() { # wait_ready <mgmt-port> <name>
  local port="$1" name="$2"
  for _ in $(seq 1 120); do
    curl -sf "http://127.0.0.1:${port}/actuator/health/readiness" >/dev/null 2>&1 && return 0
    sleep 1
  done
  echo "error: ${name} not ready within 120s (see ${report_dir}/server-*.log)" >&2
  return 1
}
for i in $(seq 0 $((topology_nodes - 1))); do
  wait_ready "${port_mgmt[$i]}" "registry-${i}" || exit 1
done
peer_list="127.0.0.1:${port_peer[0]}"
for i in $(seq 1 $((topology_nodes - 1))); do peer_list="${peer_list},127.0.0.1:${port_peer[$i]}"; done
discovery_grpc=$((port_grpc[$((topology_nodes - 1))] + 5))
discovery_http=$((port_http[$((topology_nodes - 1))] + 1))
discovery_mgmt=$((port_mgmt[$((topology_nodes - 1))] + 1))
start_bg "${report_dir}/server-discovery.log" java -Xms"${disc_heap}"m -Xmx"${disc_heap}"m ${jvm_common} \
  -jar "${discovery_jar}" --spring.grpc.server.port="${discovery_grpc}" --server.port="${discovery_http}" \
  --management.server.port="${discovery_mgmt}" \
  --artemis.discovery.nodes="127.0.0.1:${discovery_grpc}" \
  --artemis.discovery.registry.clusters="{\"default\":\"${peer_list}\"}"
wait_ready "${discovery_mgmt}" "discovery" || exit 1

# ---- 周期采样（服务端 Prometheus + heap + RSS + 回环带宽；停止于末场景 watchdog 后） ----
sample_loop() {
  local out="$1" end_at="$2"
  : >"${out}"
  while [ "$(date +%s)" -lt "${end_at}" ]; do
    echo "### $(date +%s%3N)" >>"${out}"
    for i in $(seq 0 $((topology_nodes - 1))); do
      echo "# node-${i}" >>"${out}"
      curl -sf "http://127.0.0.1:${port_mgmt[$i]}/actuator/prometheus" 2>/dev/null >>"${out}" || true
    done
    curl -sf "http://127.0.0.1:${discovery_mgmt}/actuator/prometheus" 2>/dev/null >>"${out}" || true
    grep -E 'lo:' /proc/net/dev >>"${out}" || true
    for pid in $(cat "${report_dir}/pids.txt" 2>/dev/null); do
      awk '/VmRSS/{print "'"$pid"'", $2}' "/proc/${pid}/status" 2>/dev/null >>"${out}" || true
    done
    # JVM heap 概览（server/driver 进程；预判 #3/#9 的 heap 轨迹数据源）
    for pid in $(cat "${report_dir}/pids.txt" 2>/dev/null); do
      jcmd "${pid}" GC.heap_info 2>/dev/null | head -2 >>"${out}" || true
    done
    sleep 5
  done
}

# ---- 场景执行 ----
run_scenario() {
  local scenario="$1"
  echo "== scenario ${scenario} (ramp=${ramp_sec}s duration=${duration_sec}s) =="
  local budget=$((ramp_sec + duration_sec))
  local hard=$((budget * 2))
  local started_at
  started_at="$(date +%s)"

  local driver_args=(--role registry --profile "${profile}" --scenario "${scenario}"
    --target "http://127.0.0.1:${port_http[0]}" --reportDir "${report_dir}" "${forward[@]}")
  local driver_pids=()
  for i in $(seq 0 $((driver_jvms - 1))); do
    start_bg "${report_dir}/driver-${scenario}-${i}.log" java -Xms"${drv_heap}"m -Xmx"${drv_heap}"m ${jvm_common} ${jvm_driver_extra} \
      -jar "${bench_jar}" "${driver_args[@]}" --shardIndex "${i}" --shardCount "${driver_jvms}"
    driver_pids+=("${LAST_PID}")
  done
  if [ "${scenario}" = "s4" ] || [ "${scenario}" = "combo" ]; then
    start_bg "${report_dir}/driver-subscriber.log" java -Xms"${sub_heap}"m -Xmx"${sub_heap}"m ${jvm_common} ${jvm_driver_extra} \
      -jar "${bench_jar}" --role subscriber --profile "${profile}" --scenario "${scenario}" \
      --discoveryTarget "http://127.0.0.1:${discovery_http}" --reportDir "${report_dir}" \
      --subscribers "${subscribers}" "${forward[@]}"
    driver_pids+=("${LAST_PID}")
  fi
  sample_loop "${report_dir}/samples-server-${scenario}.txt" "$((started_at + budget + 30))" &
  local sampler_pid=$!

  # watchdog：budget 内等 driver 自然退出；硬顶 2×budget 强杀（设计: 时间控制两级）
  while [ "$(date +%s)" -lt "$((started_at + budget))" ]; do
    alive_any "${driver_pids[@]}" || break
    sleep 5
  done
  while [ "$(date +%s)" -lt "$((started_at + hard))" ]; do
    alive_any "${driver_pids[@]}" || break
    sleep 5
  done
  if alive_any "${driver_pids[@]}"; then
    echo "scenario ${scenario}: WATCHDOG timeout, killing drivers" | tee -a "${report_dir}/summary.txt"
    local pid
    for pid in "${driver_pids[@]}"; do
      kill "${pid}" 2>/dev/null || true
    done
  fi
  kill "${sampler_pid}" 2>/dev/null || true
  wait "${sampler_pid}" 2>/dev/null || true
  # 退出码核查：driver 崩溃（如启动即崩）不得记为正常完成（防零负载假报告）
  local failed=0
  for pid in "${driver_pids[@]}"; do
    wait "${pid}" 2>/dev/null || failed=1
  done
  if [ "${failed}" = "1" ]; then
    echo "scenario ${scenario}: FAILED (driver nonzero exit, see ${report_dir}/driver-${scenario}-*.log)" | tee -a "${report_dir}/summary.txt"
    FAILED_SCENARIOS=$((FAILED_SCENARIOS + 1))
  else
    echo "scenario ${scenario} finished in $(( $(date +%s) - started_at ))s" | tee -a "${report_dir}/summary.txt"
  fi
  # 场景间静默：变更扇出/订阅对账收敛
  sleep 15
}

total_start="$(date +%s)"
# 整场 watchdog（设计: 时间控制两级）：预算和 ×1.5 + 拓扑准备 5min，超时终止后续场景
max_total=$(( (ramp_sec + duration_sec) * $(echo "${scenarios}" | tr ',' '\n' | wc -l) * 3 / 2 + 300 ))
IFS=',' read -ra scenario_list <<<"${scenarios}"
for scenario in "${scenario_list[@]}"; do
  if [ $(( $(date +%s) - total_start )) -ge "${max_total}" ]; then
    echo "MAX-TOTAL timeout (${max_total}s), skip remaining scenarios: ${scenario}" | tee -a "${report_dir}/summary.txt"
    break
  fi
  run_scenario "${scenario}"
done
echo "== total $(( $(date +%s) - total_start ))s, report: ${report_dir} ==" | tee -a "${report_dir}/summary.txt"

# ---- 汇总：S4 延迟配对 ----
if [[ "${scenarios}" == *s4* || "${scenarios}" == *combo* ]]; then
  java -jar "${bench_jar}" --role join --profile "${profile}" --scenario s4 --reportDir "${report_dir}" \
    --shardCount "${driver_jvms}" "${forward[@]}" | tee -a "${report_dir}/summary.txt"
fi

# ---- 副本一致性（3 节点拓扑）：从末场景采样序列取各节点 instances 峰值对比（driver 优雅退出会注销实例，即时值无意义） ----
if [ "${topology_nodes}" = "3" ]; then
  last_scenario="${scenario_list[-1]}"
  echo "replica-consistency (from samples-server-${last_scenario}.txt, per-node peak instances):" >>"${report_dir}/summary.txt"
  awk '/^# node-/{node=$2} /^artemis_registry_instances /{if ($2+0 > peak[node]) peak[node]=$2} END {for (n in peak) print " ", n, peak[n]}' \
    "${report_dir}/samples-server-${last_scenario}.txt" >>"${report_dir}/summary.txt" || true
fi

if [ "${FAILED_SCENARIOS}" -gt 0 ]; then
  echo "FAILED: ${FAILED_SCENARIOS} scenario(s) — see ${report_dir}/summary.txt"
  exit 1
fi
echo "done. report dir: ${report_dir}"
