#!/usr/bin/env bash
# SDK 发布 dry-run（v1.0 phase-8）：独立入口，不进 dev-test.sh 与 CI（发布前置检查非日常测试形态）。
# 隔离 GNUPGHOME 一次性测试密钥 + 临时 release 版本号 + 上传指向不可达端点：bundle 先组装后上传失败
# （0.11.0 的 skipPublishing 为整体跳过含 bundle 组装，不可用），断言 bundle 组件集合与签名可验证。
# 真实发布流程见 docs/dev/release-java.md。
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
client_dir="$(cd "${script_dir}/../client" && pwd)"

# JDK 25（非交互环境兜底，与 bench.sh 同法）
if ! command -v java >/dev/null 2>&1; then
  sdkman_java="${HOME}/.sdkman/candidates/java/current"
  if [ -x "${sdkman_java}/bin/java" ]; then
    export JAVA_HOME="${sdkman_java}" PATH="${sdkman_java}/bin:${PATH}"
  else
    echo "error: java (JDK 25) not found in PATH" >&2
    exit 1
  fi
fi
command -v mvn >/dev/null 2>&1 || { echo "error: mvn not found" >&2; exit 1; }
command -v gpg >/dev/null 2>&1 || { echo "error: gpg not found" >&2; exit 1; }
command -v unzip >/dev/null 2>&1 || { echo "error: unzip not found" >&2; exit 1; }

# ---- 一次性测试密钥（隔离 GNUPGHOME，退出清理；dry-run 验签名链路，不验身份） ----
gnupg_home="$(mktemp -d)"

# ---- 临时 release 版本号：SNAPSHOT 版本被 Central 插件按 Snapshot Publishing 跳过（不组 bundle），
#      全链路需以非 SNAPSHOT 版本执行。版本切换用 sed 字面替换（versions:set 实测会波及 reactor 外
#      引用 client 版本的 pom，如 server 线 registry-server 的 test 依赖）；备份恢复含失败退出 ----
base_version="$(grep -m1 -oP '(?<=<version>)[^<]+' "${client_dir}/pom.xml")"
dryrun_version="0.1.0-DRYRUN"
pom_backup="$(mktemp -d)"
settings_file="${pom_backup}/settings.xml"
cat > "$settings_file" <<'EOF'
<settings>
  <!-- dry-run 专用：上传指向不可达端点，dummy 凭证仅满足插件 server 读取，不消费、不出网 -->
  <servers>
    <server>
      <id>central</id>
      <username>dry-run</username>
      <password>dry-run</password>
    </server>
  </servers>
</settings>
EOF
poms=("${client_dir}/pom.xml" "${client_dir}"/artemis-client/pom.xml \
  "${client_dir}/artemis-client-spring-boot-starter/pom.xml" "${client_dir}/artemis-bench/pom.xml")
for pom in "${poms[@]}"; do
  cp "$pom" "${pom_backup}/$(echo "${pom#${client_dir}/}" | tr / _)"
done
restore_poms() {
  for pom in "${poms[@]}"; do
    cp "${pom_backup}/$(echo "${pom#${client_dir}/}" | tr / _)" "$pom"
  done
}
cleanup() { restore_poms; rm -rf "$gnupg_home" "$pom_backup"; }
trap cleanup EXIT INT TERM HUP
export GNUPGHOME="$gnupg_home"
gpg --batch --passphrase '' --quick-gen-key 'Artemis Dry Run <dry-run@artemis.invalid>' default default never >/dev/null 2>&1 \
  || { echo "error: test key generation failed" >&2; exit 1; }
key_id="$(gpg --list-secret-keys --with-colons | awk -F: '/^sec/ {print $5; exit}')"
[ -n "$key_id" ] || { echo "error: test key generation failed" >&2; exit 1; }

echo "==> release build (version ${dryrun_version}, unreachable central endpoint, isolated test key ${key_id})"
for pom in "${poms[@]}"; do
  sed -i "s|<version>${base_version}</version>|<version>${dryrun_version}</version>|" "$pom"
done
# sed 零匹配静默成功的防御：4 pom 必须都已带上 dry-run 版本（如 base_version 探测漂移则在此显式失败）
for pom in "${poms[@]}"; do
  grep -q "<version>${dryrun_version}</version>" "$pom" \
    || { echo "error: version replacement missed ${pom} (base_version=${base_version})" >&2; exit 1; }
done
build_log="${pom_backup}/deploy.log"
# 先清陈旧 bundle：断言只对本次运行产物成立（防上一轮 bundle 假绿）
find "${client_dir}" -name 'central-bundle.zip' -delete
set +e
mvn -B -s "$settings_file" -f "${client_dir}/pom.xml" -Prelease -pl '!artemis-bench' \
  -DskipTests=true -DskipITs=true -Dmaven.install.skip=true -Dgpg.passphrase= -Dgpg.keyname="${key_id}" \
  -DcentralBaseUrl=http://127.0.0.1:9 deploy > "${build_log}" 2>&1
build_exit=$?
set -e
# 预期失败形态：bundle 组装完成后上传在不可达端点失败；其它失败 = 配置/构建真问题
if [ "$build_exit" -eq 0 ]; then
  echo "error: deploy unexpectedly succeeded (upload should fail at unreachable endpoint)" >&2
  exit 1
fi
grep -Fq 'failed: Deployment Deployment failed while publishing' "${build_log}" || {
  echo "error: deploy failed for unexpected reason, last 60 lines:" >&2
  tail -60 "${build_log}" >&2
  exit 1
}

# ---- bundle 断言：三组件全件（精确文件名，主件与 .asc 各自必在）、bench 不在 ----
bundle="$(find "${client_dir}" -name 'central-bundle.zip' | head -n1)"
[ -n "$bundle" ] || { echo "error: central-bundle.zip not found" >&2; exit 1; }
bundle_list="$(mktemp)"
unzip -l "$bundle" | awk 'NR > 3 {print $4}' > "$bundle_list"

fail=0
check_present() { # 期望路径必须精确存在于 bundle
  if grep -Fxq "$1" "$bundle_list"; then
    echo "ok: ${1##*/} [${1%/*}]"
  else
    echo "FAIL: missing in bundle: $1"; fail=1
  fi
}
group_path="org/mydotey/ai/artemis"
for comp in artemis-client-parent artemis-client artemis-client-spring-boot-starter; do
  check_present "${group_path}/${comp}/${dryrun_version}/${comp}-${dryrun_version}.pom"
  check_present "${group_path}/${comp}/${dryrun_version}/${comp}-${dryrun_version}.pom.asc"
done
for comp in artemis-client artemis-client-spring-boot-starter; do
  for suffix in ".jar" "-sources.jar" "-javadoc.jar"; do
    check_present "${group_path}/${comp}/${dryrun_version}/${comp}-${dryrun_version}${suffix}"
    check_present "${group_path}/${comp}/${dryrun_version}/${comp}-${dryrun_version}${suffix}.asc"
  done
done
if grep -q "artemis-bench" "$bundle_list"; then
  echo "FAIL: artemis-bench must not be in bundle"; fail=1
else
  echo "ok: artemis-bench NOT in bundle"
fi

# ---- 签名抽验：gpg --verify 主件 ----
verify_dir="$(mktemp -d)"
for comp in artemis-client artemis-client-spring-boot-starter; do
  jar_rel="${group_path}/${comp}/${dryrun_version}/${comp}-${dryrun_version}.jar"
  if ! unzip -j -o "$bundle" "${jar_rel}" "${jar_rel}.asc" -d "$verify_dir" >/dev/null 2>&1; then
    echo "FAIL: extract ${comp} main jar/asc for gpg verify"; fail=1; continue
  fi
  jar_file="${verify_dir}/$(basename "$jar_rel")"
  if gpg --verify "${jar_file}.asc" "$jar_file" >/dev/null 2>&1; then
    echo "ok: gpg --verify ${comp} main jar"
  else
    echo "FAIL: gpg --verify ${comp} main jar"; fail=1
  fi
done
rm -rf "$verify_dir" "$bundle_list"

if [ "$fail" -ne 0 ]; then
  echo "error: release dry-run assertions failed (bundle: ${bundle})" >&2
  exit 1
fi
# 恢复并核验：4 pom 均已回到基线版本（显式恢复先于核验；trap 里的恢复幂等，覆盖失败/信号路径）
restore_poms
for pom in "${poms[@]}"; do
  if grep -q "<version>${dryrun_version}</version>" "$pom"; then
    echo "error: version restore missed ${pom}" >&2
    exit 1
  fi
done
echo "==> release dry-run passed (bundle: ${bundle})"
