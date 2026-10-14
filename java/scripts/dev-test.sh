#!/usr/bin/env bash
# Artemis 统一测试入口（模式与形态↔模式映射的单一来源：当前 phase 设计文档「自动化测试入口」节，见 docs/milestones/dev-state.md）
# 用法: dev-test.sh [unit|integration|all]   # 默认 all
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
reactor_dir="$(cd "${script_dir}/.." && pwd)"

usage() {
  echo "usage: dev-test.sh [unit|integration|all]" >&2
  echo "  unit        surefire *Test 单测（mvn test），快速迭代" >&2
  echo "  integration failsafe *IT 集成测试（test-compile 前置编译）" >&2
  echo "  all         全量 verify：spotless + enforcer + 测试（默认，工作流 ⑤/⑧ 验收入口）" >&2
}

if [ "$#" -gt 1 ]; then
  usage
  exit 2
fi

mode="${1:-all}"

case "${mode}" in
  unit)
    exec mvn -f "${reactor_dir}/pom.xml" test
    ;;
  integration)
    # 直调 goal 不走 lifecycle：先 test-compile，clean 树 0 测试静默假绿已修
    exec mvn -f "${reactor_dir}/pom.xml" test-compile failsafe:integration-test failsafe:verify
    ;;
  all)
    exec mvn -f "${reactor_dir}/pom.xml" verify
    ;;
  *)
    usage
    exit 2
    ;;
esac
