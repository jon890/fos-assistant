#!/usr/bin/env bash
# 머지 전 로컬 검사를 AGENTS.md 「확인」 절의 순서대로 모두 돌린다.
#
# 처음 받은 checkout 에서도 돌도록 웹 의존성과 Playwright 의 chromium 을 먼저 설치한다.
# 둘 다 이미 있으면 바로 끝난다. `pnpm build` 에는 web/AGENTS.md 의 자리표시자 환경 변수를 준다.
#
# 브라우저 단계는 전체 spec 을 돌려 10분 가까이 걸린다. 수정 중에는 고친 화면의 spec 만 돌리고,
# 머지 전 브라우저 확인은 PR 의 CI(`browser-mobile`, `browser-desktop`)를 따른다(AGENTS.md 「확인」).
#
# 단계마다 로그를 따로 남기고, 처음 실패한 단계에서 멈춰 그 로그의 끝을 보인다.
set -Eeuo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
tmp_root="${TMPDIR:-/tmp}"
LOG_DIR="$(mktemp -d "${tmp_root%/}/fos-assistant-check.XXXXXX")"

# test/e2e 와 test/unit 은 Node 의 TypeScript 실행을 쓴다.
node_version="$(node -p 'process.versions.node')"
if ! node -e 'const [a,b]=process.versions.node.split(".").map(Number); process.exit(a>22||(a===22&&b>=18)?0:1)'; then
  echo "Node 22.18 이상이 필요하다. 지금은 ${node_version} 이다." >&2
  exit 2
fi

step() {
  local name="$1"; shift
  local log="${LOG_DIR}/${name}.log"
  local started=$SECONDS
  printf '%-16s' "${name}"
  if "$@" >"${log}" 2>&1; then
    echo "통과 ($((SECONDS - started))초)"
    # 통과해도 검사 범위가 줄었다는 알림은 보인다. check-public-safe.sh 는 값 목록이 없으면 형태만 검사하고 통과한다.
    grep -A3 '^알림:' "${log}" || true
  else
    echo "실패 ($((SECONDS - started))초)"
    echo "--- ${log} 의 끝 ---" >&2
    tail -n 40 "${log}" >&2
    exit 1
  fi
}

build_web() {
  AUTH_SECRET=build-time-placeholder \
  ASSISTANT_JWT_SECRET=build-time-placeholder \
  CONTROL_PLANE_BASE_URL=http://build-time-placeholder \
  AUTH_GOOGLE_ID=build-time-placeholder \
  AUTH_GOOGLE_SECRET=build-time-placeholder \
  pnpm --dir "${ROOT}/web" build
}

# CI 의 hermes job 과 같은 판이어야 한다. 다른 판이 깔려 있으면 맞춰 설치한 뒤 검사한다.
HERMES_MCP_VERSION="2.0.0"
HERMES_PYYAML_VERSION="6.0.3"

check_hermes() {
  cd "${ROOT}" || return 1
  if ! python3 - "$HERMES_MCP_VERSION" "$HERMES_PYYAML_VERSION" <<'PY'
import importlib.metadata as metadata
import sys

for name, wanted in (("mcp", sys.argv[1]), ("PyYAML", sys.argv[2])):
    try:
        if metadata.version(name) != wanted:
            sys.exit(1)
    except metadata.PackageNotFoundError:
        sys.exit(1)
PY
  then
    python3 -m pip install "mcp==${HERMES_MCP_VERSION}" "PyYAML==${HERMES_PYYAML_VERSION}" || return 1
  fi
  python3 -m unittest discover -s hermes/tests
}

echo "로그: ${LOG_DIR}"
step web-install     pnpm --dir "${ROOT}/web" install --frozen-lockfile
step playwright      pnpm --dir "${ROOT}/web" exec playwright install chromium
step backend         bash -c "cd '${ROOT}/backend' && ./gradlew test"
step mysql-migration "${ROOT}/scripts/check-mysql-migration.sh"
step web-typecheck   pnpm --dir "${ROOT}/web" typecheck
step web-build       build_web
step browser         pnpm --dir "${ROOT}/web" test:browser
step e2e             bash -c "cd '${ROOT}' && node test/e2e/run.ts"
step unit            bash -c "cd '${ROOT}' && node --test 'test/unit/**/*.test.ts'"
step hermes          check_hermes
step public-safe     "${ROOT}/scripts/check-public-safe.sh"
step quality         bash -c "cd '${ROOT}' && scripts/quality.sh check"
echo "모두 통과했다"
