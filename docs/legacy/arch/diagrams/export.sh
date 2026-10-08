#!/usr/bin/env bash
# 重新导出本目录全部 *.html → *.svg。
#
# 用 diagram-design 的官方 export_svg.py（HTML 是唯一真源，SVG 是导出物），
# 再补一步：该脚本注入的 Google Fonts 清单是固定的 KR/TC 组合、不含 SC，
# 简体中文标签会退回本地字体。这里把 Noto Sans SC 补进 @import。
set -euo pipefail

resolve_skill() {
  if [ -n "${DIAGRAM_DESIGN_SKILL:-}" ]; then
    printf '%s\n' "$DIAGRAM_DESIGN_SKILL"
    return
  fi
  # `|| true` 是必需的：glob 无匹配时 ls 返回非零，否则 set -e 会在赋值处直接中断，
  # 下面的友好报错就成了死代码。
  ls -d "$HOME"/.claude/plugins/cache/diagram-design/diagram-design/*/skills/diagram-design 2>/dev/null \
    | sort -V | tail -1 || true
}

SKILL="$(resolve_skill)"
if [ -z "$SKILL" ] || [ ! -d "$SKILL" ]; then
  echo "找不到 diagram-design skill（已查 DIAGRAM_DESIGN_SKILL 与插件缓存）。" >&2
  echo "请先安装 diagram-design 插件，或设 DIAGRAM_DESIGN_SKILL=<skill 目录>。" >&2
  exit 1
fi

cd "$(dirname "$0")"

patch_fonts() {
  python3 - "$1" <<'PY'
import sys
path = sys.argv[1]
s = open(path, encoding='utf-8').read()
if 'Noto+Sans+SC' in s:
    sys.exit(0)
old = 'family=Noto+Serif:ital@0;1'
new = ('family=Noto+Serif:ital@0;1&amp;family=Noto+Sans+SC:wght@400;500;600'
       '&amp;family=Noto+Serif+SC:wght@400')
assert old in s, f'{path}: 找不到字体注入点，export_svg.py 输出格式可能已变'
open(path, 'w', encoding='utf-8').write(s.replace(old, new, 1))
print(f'  + Noto Sans SC → {path}')
PY
}

for f in *.html; do
  svg="${f%.html}.svg"
  python3 "$SKILL/scripts/export_svg.py" "$f"
  patch_fonts "$svg"
done
