#!/usr/bin/env bash
# proto wire 兼容检查（v1.0 phase-5 滚动升级协议验证·契约层）：
# 当前工作树的数据面五组 proto 相对各版本锚点无破坏性变更。
# 断言（纪律来源 docs/dev/proto-contract.md §2）：
#   1. 已有 (message, field number) 的类型不变（含 repeated/map 修饰）
#   2. 字段不删除；确需删除时该 message 编号与名字都 reserved
#   3. reserved 编号不被新字段复用
#   4. 锚点 commit 必须可解析——环境不完整（浅克隆）显式红，不静默假绿
# 锚点 = 各版本收尾 commit（仓库尚无 git tag，版本收尾 commit 是契约版本锚点的等价物；
# 打 tag 后本表切 tag 名）。v1.0 收尾后在此追加本版锚点。
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "${repo_root}"

# 锚点表：版本:commit-ish
anchors=(
  "v0.1:53aada8"
  "v0.2:e310b86"
  "v0.3:0c12330"
  "v0.4:1550374"
  "v0.5:43fca55"
)

# 数据面五组（proto-contract.md §2 冻结范围；management.v1 不在冻结列，不检查）
proto_files=(
  "proto/common/v1/model.proto"
  "proto/registry/v1/registry.proto"
  "proto/replication/v1/replication.proto"
  "proto/projection/v1/projection.proto"
  "proto/discovery/v1/discovery.proto"
)

# 解析 proto 文本，输出结构行（TAB 分隔，供 compare.awk 消费）：
#   F <TAB> message路径 <TAB> field编号 <TAB> 修饰+类型 <TAB> 字段名
#   RN <TAB> message路径 <TAB> 区间下界 <TAB> 区间上界      （reserved 编号区间，不物化 to max）
#   RM <TAB> message路径 <TAB> 名字                        （reserved 字段名）
# 解析器为正则级（单行声明；注释剥离后处理），本项目 proto 均为此风格。
parse_proto() {
  awk '
    function msg_path(  d, s) {
      s = ""
      for (d = 1; d <= block; d++) if (msg_at[d] != "") s = s (s == "" ? "" : ".") msg_at[d]
      return s
    }
    function reserved_items(spec,   n, i, p, a, b, t, part) {
      n = split(spec, part, ",")
      for (i = 1; i <= n; i++) {
        gsub(/[ \t]/, "", part[i])
        if (part[i] ~ /^"[^"]+"$/) {
          gsub(/"/, "", part[i])
          print "RM\t" msg_path() "\t" part[i]
        } else if (part[i] ~ /^[0-9]+(to[0-9]+|tomax)?$/) {
          a = b = part[i] + 0
          if (p = index(part[i], "to")) {
            t = substr(part[i], p + 2)
            b = (t == "max") ? 536870911 : t + 0
          }
          print "RN\t" msg_path() "\t" a "\t" b
        }
      }
    }
    function strip_comments(line,   p, q, pre, rest) {
      if (in_block) {
        if (p = index(line, "*/")) { line = substr(line, p + 2); in_block = 0 }
        else return ""
      }
      if (p = index(line, "/*")) {
        pre = substr(line, 1, p - 1)
        rest = substr(line, p + 2)
        if (q = index(rest, "*/")) line = pre substr(rest, q + 2)
        else { in_block = 1; line = pre }
      }
      if (p = index(line, "//")) line = substr(line, 1, p - 1)
      return line
    }
    {
      line = strip_comments($0)
      is_message = match(line, /message[ \t]+[A-Za-z0-9_]+[ \t]*\{/)
      if (is_message) {
        match(line, /message[ \t]+[A-Za-z0-9_]+/)
        msg_name = substr(line, RSTART + 8, RLENGTH - 8)
        gsub(/[ \t]/, "", msg_name)
      } else if (match(line, /^[ \t]*reserved[ \t][^;]+;/)) {
        spec = substr(line, RSTART, RLENGTH)
        sub(/^[ \t]*reserved[ \t]*/, "", spec)
        sub(/;$/, "", spec)
        reserved_items(spec)
      } else if (match(line, /^[ \t]*(repeated[ \t]+|optional[ \t]+)?([A-Za-z_][A-Za-z0-9_.]*|map[ \t]*<[^>]+>)[ \t]+[A-Za-z_][A-Za-z0-9_]*[ \t]*=[ \t]*[0-9]+/)) {
        # 行尾不限定：容忍字段选项（如 [deprecated = true]，契约弃用流程本身要求）
        f = line
        sub(/^[ \t]+/, "", f)
        num = f; sub(/^[^=]+=[ \t]*/, "", num); sub(/[^0-9].*$/, "", num)
        type = f; sub(/[ \t]*[A-Za-z_][A-Za-z0-9_]*[ \t]*=.*$/, "", type)
        # name 从 type 之后截取（type 可为多词：repeated/map 与限定类型名），防前缀剥离错位
        name = substr(f, length(type) + 1)
        sub(/^[ \t]+/, "", name); sub(/[ \t]*=.*$/, "", name)
        print "F\t" msg_path() "\t" num "\t" type "\t" name
      }
      # 块深度统一由本行花括号计数维护：message 的 push 挂在其 { 上（单行 message X{} 亦正确）
      for (i = 1; i <= length(line); i++) {
        c = substr(line, i, 1)
        if (c == "{") {
          block++
          if (is_message) msg_at[block] = msg_name
        } else if (c == "}") {
          if (msg_at[block] != "") delete msg_at[block]
          if (block > 0) block--
        }
      }
    }
  '
}

# 对比 old/new 两份结构文件，输出 FAIL 行（不带前缀，由调用方计数）。
# old 侧以 FILENAME 判别（NR==FNR 在首文件为空时失效——锚点占位文件正是空输入）。
compare() {
  awk -F'\t' -v version="$1" -v file="$2" -v old_file="$3" '
    FILENAME == old_file {
      if ($1 == "F") { old_type[$2 FS $3] = $4; old_name[$2 FS $3] = $5; old_keys[++old_n] = $2 FS $3 }
      next
    }
    {
      if ($1 == "F") {
        new_type[$2 FS $3] = $4
        if (!seen[$2 FS $3]++) new_keys[++new_n] = $2 FS $3
      } else if ($1 == "RN") { rn_n[$2]++; new_rn[$2 FS rn_n[$2]] = $3 FS $4 }
      else if ($1 == "RM") new_rm[$2 FS $3] = 1
    }
    function fail(what) { print "FAIL [" version "] " file ": " what; fails++ }
    function in_reserved_range(path, num,   i, ab, a, b) {
      for (i = 1; i <= rn_n[path]; i++) {
        split(new_rn[path FS i], ab, FS)
        if (num >= ab[1] && num <= ab[2]) return 1
      }
      return 0
    }
    END {
      for (i = 1; i <= old_n; i++) {
        key = old_keys[i]; path = substr(key, 1, index(key, FS) - 1); num = substr(key, index(key, FS) + 1) + 0
        if (key in new_type) {
          if (old_type[key] != new_type[key]) fail(path " field " num " 类型变更 " old_type[key] " -> " new_type[key])
        } else {
          # 删除合法性：编号区间与名字都 reserved（proto-contract §2 编号与名字都保留）
          if (!in_reserved_range(path, num)) fail(path " field " num " (" old_type[key] ") 被删除且未 reserved")
          else if (!((path FS old_name[key]) in new_rm))
            fail(path " field " num " 编号已 reserved 但名字 \"" old_name[key] "\" 未保留")
        }
      }
      for (i = 1; i <= new_n; i++) {
        key = new_keys[i]; path = substr(key, 1, index(key, FS) - 1); num = substr(key, index(key, FS) + 1) + 0
        if (in_reserved_range(path, num)) fail(path " field " num " 复用了 reserved 编号")
      }
      exit(fails > 0 ? 1 : 0)
    }
  ' "$3" "$4"
}

failures=0
checked=0
tmp_old=$(mktemp); tmp_new=$(mktemp)
trap 'rm -f "${tmp_old}" "${tmp_new}"' EXIT

for anchor in "${anchors[@]}"; do
  version="${anchor%%:*}"
  commit="${anchor#*:}"
  if ! git rev-parse --verify --quiet "${commit}^{commit}" >/dev/null; then
    echo "FAIL [${version}] 锚点 ${commit} 不可解析（浅克隆/未推送？）——环境不完整显式红，不静默跳过"
    failures=$((failures + 1))
    continue
  fi
  checked=$((checked + 1))
  for file in "${proto_files[@]}"; do
    if ! git cat-file -e "${commit}:${file}" 2>/dev/null; then
      continue # 锚点尚无此文件：无「旧有」字段，天然兼容
    fi
    git show "${commit}:${file}" | parse_proto >"${tmp_old}"
    parse_proto <"${file}" >"${tmp_new}"
    if ! out=$(compare "${version}" "${file}" "${tmp_old}" "${tmp_new}"); then
      echo "${out}"
      failures=$((failures + $(echo "${out}" | wc -l)))
    fi
  done
done

if [ "${failures}" -gt 0 ]; then
  echo "proto 兼容检查失败：${failures} 处问题（纪律见 docs/dev/proto-contract.md §2）"
  exit 1
fi
echo "proto 兼容检查通过：${checked}/${#anchors[@]} 个版本锚点 × ${#proto_files[@]} 个数据面契约文件无破坏性变更"
