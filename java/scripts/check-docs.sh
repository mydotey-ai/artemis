#!/usr/bin/env bash
# 文档校验入口（v1.0 phase-7）：
#   links  git 跟踪 markdown 的相对链接目标存在性（python3 stdlib，跳过围栏代码块与外链）
#   alerts 告警示例可加载性：promtool（rules.yml + prometheus.yml）与 amtool（alertmanager.yml，
#         Alertmanager 自有 schema，promtool check config 不适用）可用则真校验；
#         缺失的工具退化为 grep 行级形状断言（字段计数配对、rule_files 引用存在、
#         receiver 引用一致——不校验 YAML 语法，语法兜底在 CI 安装两工具）
# 独立入口（同 smoke-deploy.sh 惯例：非 mvn 形态，不进 dev-test.sh 三模式与 verify 生命周期）；
# CI 入口 .github/workflows/docs.yml（预装 promtool）。
# 用法: check-docs.sh [links|alerts|all]   # 默认 all
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "${script_dir}/../.." && pwd)"
alerts_dir="${repo_root}/deploy/alerts"

usage() {
  echo "usage: check-docs.sh [links|alerts|all]" >&2
  echo "  links  markdown 相对链接目标存在性" >&2
  echo "  alerts 告警示例可加载性（promtool 优先，grep 形状断言兜底）" >&2
}

fail() { echo "FAIL: $*" >&2; exit 1; }
log() { echo "==> $*"; }

if [ "$#" -gt 1 ]; then usage; exit 2; fi
mode="${1:-all}"

check_links() {
    command -v python3 >/dev/null 2>&1 || fail "python3 required for links check"
    log "checking markdown relative links"
    python3 - "$repo_root" <<'PY'
import os, re, subprocess, sys

repo = sys.argv[1]
out = subprocess.run(["git", "-C", repo, "ls-files", "--others", "--exclude-standard", "*.md"],
                     capture_output=True, text=True, check=True).stdout.splitlines() \
    + subprocess.run(["git", "-C", repo, "ls-files", "*.md"],
                     capture_output=True, text=True, check=True).stdout.splitlines()
if not out:
    sys.exit("no tracked markdown files found")

link = re.compile(r"\[[^\]]+\]\(([^)\s]+)\)")
broken = []
for f in out:
    path = os.path.join(repo, f)
    in_fence = False
    with open(path, encoding="utf-8") as fh:
        for lineno, line in enumerate(fh, 1):
            if line.lstrip().startswith("```"):
                in_fence = not in_fence
                continue
            if in_fence:
                continue
            for target in link.findall(line):
                if target.startswith(("http://", "https://", "mailto:", "#")):
                    continue
                t = target.split("#", 1)[0]
                if not t:  # 纯锚点
                    continue
                if not os.path.exists(os.path.normpath(os.path.join(os.path.dirname(path), t))):
                    broken.append(f"{f}:{lineno} -> {target}")

if broken:
    sys.exit("broken relative links:\n  " + "\n  ".join(broken))
print(f"links ok ({len(out)} files)")
PY
}

check_alerts() {
    [ -f "${alerts_dir}/rules.yml" ] || fail "missing ${alerts_dir}/rules.yml"
    [ -f "${alerts_dir}/prometheus.yml" ] || fail "missing ${alerts_dir}/prometheus.yml"
    [ -f "${alerts_dir}/alertmanager.yml" ] || fail "missing ${alerts_dir}/alertmanager.yml"

    if command -v promtool >/dev/null 2>&1; then
        log "checking rules.yml + prometheus.yml with promtool"
        promtool check rules "${alerts_dir}/rules.yml"
        promtool check config "${alerts_dir}/prometheus.yml"
    else
        log "promtool not found, structural assertions for rules/prometheus (YAML syntax NOT checked)"

        local alerts exprs fors severities
        alerts=$(grep -cE '^\s*- alert:' "${alerts_dir}/rules.yml" || true)
        exprs=$(grep -cE '^\s*expr:' "${alerts_dir}/rules.yml" || true)
        fors=$(grep -cE '^\s*for:' "${alerts_dir}/rules.yml" || true)
        severities=$(grep -cE '^\s*severity:' "${alerts_dir}/rules.yml" || true)
        [ "$alerts" -gt 0 ] || fail "rules.yml: no alert rules found"
        [ "$alerts" -eq "$exprs" ] || fail "rules.yml: alert/expr count mismatch ($alerts/$exprs)"
        [ "$alerts" -eq "$fors" ] || fail "rules.yml: alert/for count mismatch ($alerts/$fors)"
        [ "$alerts" -eq "$severities" ] || fail "rules.yml: alert/severity count mismatch ($alerts/$severities)"

        local ref
        while IFS= read -r ref; do
            ref="${ref//\'/}"; ref="${ref//\"/}"
            [ -n "$ref" ] && [ -f "${alerts_dir}/${ref}" ] || fail "prometheus.yml: rule_files ref not found: ${ref}"
        done < <(awk '/^rule_files:/{f=1;next} /^[^[:space:]-]/{f=0} f && /^[[:space:]]*- /{print $2}' "${alerts_dir}/prometheus.yml")
    fi

    if command -v amtool >/dev/null 2>&1; then
        log "checking alertmanager.yml with amtool"
        amtool check-config "${alerts_dir}/alertmanager.yml"
    else
        log "amtool not found, structural assertions for alertmanager"
        grep -q '^route:' "${alerts_dir}/alertmanager.yml" || fail "alertmanager.yml: missing route"
        local recv defined
        defined="$(grep -E '^\s*- name:' "${alerts_dir}/alertmanager.yml" | awk '{print $3}' | sort -u)"
        [ -n "$defined" ] || fail "alertmanager.yml: no receivers defined"
        while IFS= read -r recv; do
            [ -n "$recv" ] || continue
            echo "$defined" | grep -qx "$recv" || fail "alertmanager.yml: route receiver '$recv' not defined"
        done < <(grep -E '^\s*receiver:' "${alerts_dir}/alertmanager.yml" | awk '{print $2}')
    fi
    log "alerts ok"
}

case "${mode}" in
    links) check_links ;;
    alerts) check_alerts ;;
    all) check_links; check_alerts ;;
    *) usage; exit 2 ;;
esac
