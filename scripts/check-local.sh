#!/usr/bin/env bash
# 머지 전 로컬 검사를 AGENTS.md 「확인」 절의 순서대로 모두 돌린다.
#
# 처음 받은 checkout 에서도 돌도록 웹 의존성과 Playwright 의 chromium 을 먼저 설치한다.
# 둘 다 이미 있으면 바로 끝난다. `pnpm build` 에는 web/AGENTS.md 의 자리표시자 환경 변수를 준다.
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

echo "로그: ${LOG_DIR}"
step web-install     pnpm --dir "${ROOT}/web" install --frozen-lockfile
step playwright      pnpm --dir "${ROOT}/web" exec playwright install chromium
step backend         bash -c "cd '${ROOT}/backend' && ./gradlew test"
step web-typecheck   pnpm --dir "${ROOT}/web" typecheck
step web-build       build_web
step browser         pnpm --dir "${ROOT}/web" test:browser
step e2e             bash -c "cd '${ROOT}' && node test/e2e/run.ts"
step unit            bash -c "cd '${ROOT}' && node --test 'test/unit/**/*.test.ts'"
step public-safe     "${ROOT}/scripts/check-public-safe.sh"
echo "모두 통과했다"
