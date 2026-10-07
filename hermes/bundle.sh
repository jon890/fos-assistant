#!/usr/bin/env bash
# Hermes 에 설치할 대시보드 plugin 묶음을 만든다.
#
#   hermes/bundle.sh --out <디렉터리> --mcp-url <Control Plane MCP 주소>
#
# 묶음은 Hermes 의 plugins/dashboard-profile-api/ 자리에 그대로 들어갈 모양이다.
#
#   <out>/
#     __init__.py, plugin.yaml         plugins/dashboard-profile-api/ 그대로
#     default-config.yaml.template     profile-template/config.yaml.template 에 MCP 주소를 채운 것
#     profile-plugins/<이름>/          틀의 plugins.enabled 가 켜는 plugin
#
# 묶음을 Hermes 에 넣고 대시보드를 다시 띄우는 것은 운영 저장소가 한다. 근거는 ADR-041 이 갖는다.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DASHBOARD_PLUGIN="$SCRIPT_DIR/plugins/dashboard-profile-api"
TEMPLATE="$SCRIPT_DIR/profile-template/config.yaml.template"
PLACEHOLDER="__FOS_ASSISTANT_MCP_URL__"

OUT=""
MCP_URL=""
# 채우기 시작한 뒤에 실패하면 반쯤 만든 것을 지운다.
# 이 실행이 만든 디렉터리는 통째로 지우고, 있던 빈 디렉터리는 내용만 비운다.
FILLING=0
CREATED_OUT=0

usage() {
  echo "사용법: hermes/bundle.sh --out <디렉터리> --mcp-url <URL>" >&2
}

fail() {
  echo "bundle.sh: $*" >&2
  exit 1
}

cleanup() {
  local code=$?
  if [[ $code -ne 0 && $FILLING -eq 1 ]]; then
    if [[ $CREATED_OUT -eq 1 ]]; then
      rm -rf -- "$OUT"
    else
      find "$OUT" -mindepth 1 -delete
    fi
  fi
  exit "$code"
}
trap cleanup EXIT

while [[ $# -gt 0 ]]; do
  case "$1" in
    --out)
      [[ $# -ge 2 ]] || { usage; fail "--out 에 값이 없다."; }
      OUT="$2"
      shift 2
      ;;
    --mcp-url)
      [[ $# -ge 2 ]] || { usage; fail "--mcp-url 에 값이 없다."; }
      MCP_URL="$2"
      shift 2
      ;;
    *)
      usage
      fail "알 수 없는 인자다: $1"
      ;;
  esac
done

[[ -n "$OUT" ]] || { usage; fail "--out 이 필요하다."; }
# `-` 로 시작하는 상대 경로를 뒤의 명령이 옵션으로 읽지 않게 절대 경로로 바꾼다. 없는 디렉터리도 받으므로 풀지는 않는다.
[[ "$OUT" == /* ]] || OUT="$PWD/$OUT"
[[ "$MCP_URL" =~ ^https?://[^[:space:]]+$ ]] \
  || { usage; fail "--mcp-url 은 http:// 나 https:// 로 시작하는 주소여야 한다."; }

if [[ -e "$OUT" ]]; then
  [[ -d "$OUT" ]] || fail "--out 이 디렉터리가 아니다: $OUT"
  [[ -z "$(ls -A -- "$OUT")" ]] || fail "--out 이 비어 있지 않다: $OUT"
else
  CREATED_OUT=1
fi

[[ -f "$DASHBOARD_PLUGIN/__init__.py" && -f "$DASHBOARD_PLUGIN/plugin.yaml" ]] \
  || fail "$DASHBOARD_PLUGIN 이 온전하지 않다."
[[ -f "$TEMPLATE" ]] || fail "$TEMPLATE 이 없다."

python3 -c 'import yaml' 2>/dev/null || fail "python3 에 PyYAML 이 없다. 틀을 읽으려면 필요하다."

FILLING=1
mkdir -p -- "$OUT/profile-plugins"

cp -- "$DASHBOARD_PLUGIN/__init__.py" "$DASHBOARD_PLUGIN/plugin.yaml" "$OUT/"
cp -- "$SCRIPT_DIR/decision-profile/config.yaml.template" "$OUT/decision-config.yaml.template"

# 주소를 글자 그대로 넣는다. sed 의 구분자와 특수 글자를 피하려고 Python 으로 바꾼다.
python3 - "$TEMPLATE" "$OUT/default-config.yaml.template" "$PLACEHOLDER" "$MCP_URL" <<'PY' \
  || fail "틀에 MCP 주소를 채우지 못했다."
import sys

source, target, placeholder, url = sys.argv[1:5]
with open(source, encoding="utf-8") as handle:
    text = handle.read()
with open(target, "w", encoding="utf-8") as handle:
    handle.write(text.replace(placeholder, url))
PY

names="$(python3 - "$OUT/default-config.yaml.template" "$PLACEHOLDER" "$MCP_URL" <<'PY'
import sys

import yaml

path, placeholder, url = sys.argv[1:4]
try:
    with open(path, encoding="utf-8") as handle:
        template = yaml.safe_load(handle) or {}
except yaml.YAMLError as exc:
    sys.exit("주소를 채운 틀이 YAML 로 읽히지 않는다: %s" % type(exc).__name__)
if not isinstance(template, dict):
    sys.exit("주소를 채운 틀이 mapping 이 아니다")
server = (template.get("mcp_servers") or {}).get("fos-assistant") or {}
with open(path, encoding="utf-8") as handle:
    if placeholder in handle.read():
        sys.exit("틀에 MCP 주소 자리가 남았다")
if server.get("url") != url:
    sys.exit("mcp_servers.fos-assistant.url 에 주소가 들어가지 않았다")
for name in (template.get("plugins") or {}).get("enabled") or []:
    print(name)
PY
)" || fail "채운 틀을 확인하지 못했다. 까닭은 바로 윗줄에 있다."

while IFS= read -r name; do
  [[ -n "$name" ]] || continue
  [[ "$name" =~ ^[a-z0-9][a-z0-9_-]*$ ]] || fail "틀의 plugins.enabled 에 이름이 올바르지 않은 항목이 있다: $name"
  [[ -f "$SCRIPT_DIR/plugins/$name/plugin.yaml" && -f "$SCRIPT_DIR/plugins/$name/__init__.py" ]] \
    || fail "틀이 켜는 plugin '$name' 이 $SCRIPT_DIR/plugins 에 온전하지 않다."
  cp -R -- "$SCRIPT_DIR/plugins/$name" "$OUT/profile-plugins/$name"
  rm -rf -- "$OUT/profile-plugins/$name/__pycache__"
done <<< "$names"

# BSD chmod 는 모드 뒤의 `--` 를 파일 이름으로 읽는다. 모드 앞에 둔다.
chmod -R -- u=rwX,go=rX "$OUT"

echo "묶음을 만들었다: $OUT"
