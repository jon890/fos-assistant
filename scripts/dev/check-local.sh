#!/usr/bin/env bash
# push 전 로컬 검사를 차례로 돌린다. 돌리는 명령과 순서, 작업 폴더는 이 스크립트가 정본이다.
#
# 처음 받은 checkout 에서도 돌도록 필요한 의존성을 lockfile 로 설치한다.
# 전체 검사는 금융 대역이 쓰는 tossinvest 의존성도 준비하고, 브라우저 검사 때만 chromium 을 설치한다.
# `pnpm build` 에는 web/Dockerfile 과 같은 자리표시자 환경 변수를 준다.
#
# 실행마다 $TMPDIR 아래 새 폴더에 단계별 원본 로그를 남긴다(없으면 /tmp).
# 처음 실패한 단계에서 멈춰 마지막 40줄과 원본 로그의 절대 경로를 보인다.
# 성공한 실행의 로그만 지우고, 실패한 실행의 로그는 조사할 수 있도록 보존한다.
# public-safe 출력에는 비공개 값이 있을 수 있어 성공·실패 모두 화면에 보인 뒤 로그를 지운다.
# 조사 뒤 로그 정리: rm -r -- "<출력한 원본 로그의 상위 폴더>". 자동으로 만료하지 않는다.
#
# 사용법: scripts/dev/check-local.sh [--fast | --skip-browser] [브라우저 spec ...]
# --fast 는 Draft PR 을 열기 전에 쓴다. 몇 분 안에 끝나는 단계만 돈다.
#   공개 정보, PR 크기, 파일 길이, 마이그레이션 버전, 웹 typecheck, 웹 node 테스트, test/unit, 품질(컴파일과 구조 규칙, 포맷) 검사다.
#   공개 정보 검사는 push 하면 되돌릴 수 없으므로 빼지 않고 맨 앞에 둔다.
#   이 검사는 추적 파일의 지금 내용만 본다. 커밋 메시지와 앞 커밋에 넣었다 지운 값은 push 전에 직접 확인한다.
#   SQL·Java 마이그레이션이나 purge fixture 가 바뀌면 PurgeClassificationTest 만 추가로 돌린다.
#   origin/main 과의 공통 조상부터 작업 트리까지 비교하고, 추적하지 않은 파일도 포함한다.
#   비교 기준이 없으면 분류 검사를 돌린다. 바뀐 것이 없으면 건너뛴다.
#   나머지 backend 테스트, MySQL, 웹 빌드, 브라우저, e2e, 커넥터, Hermes 는 Draft CI 가 확인한다.
#   금융 대역을 실행하지 않는 --fast 에서는 tossinvest 의존성을 설치하지 않는다.
# 인자는 브라우저 검사에만 넘긴다. 인자가 없으면 브라우저 검사 전체를 돌린다.
# 화면을 고쳤는데 --fast 를 쓰지 않을 때는 고친 화면과 그 컴포넌트를 쓰는 화면의 spec 을 준다.
# CI 가 돌지 못하면 머지 전에 인자 없이 전체를 돌린다.
set -Eeuo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

fast=false
skip_browser=false
case "${1:-}" in
  --fast) fast=true; skip_browser=true; shift ;;
  --skip-browser) skip_browser=true; shift ;;
esac
case "${1:-}" in
  --*) echo "옵션은 --fast 나 --skip-browser 중 하나만 맨 앞에 준다: $1" >&2; exit 2 ;;
esac
if [ "${skip_browser}" = true ] && [ "$#" -gt 0 ]; then
  echo "--fast 나 --skip-browser 와 브라우저 spec 을 함께 줄 수 없다." >&2
  exit 2
fi

# test/e2e 와 test/unit 은 Node 의 TypeScript 실행을 쓴다.
node_version="$(node -p 'process.versions.node')"
if ! node -e 'const [a]=process.versions.node.split(".").map(Number); process.exit(a>=24?0:1)'; then
  echo "Node 24 이상이 필요하다. 지금은 ${node_version} 이다." >&2
  exit 2
fi

tmp_root="${TMPDIR:-/tmp}"
tmp_root="$(cd "${tmp_root}" && pwd)"
LOG_DIR="$(mktemp -d "${tmp_root%/}/fos-assistant-check.XXXXXX")"
trap 'if [ "$?" -eq 0 ]; then rm -rf "${LOG_DIR}"; else rmdir "${LOG_DIR}" 2>/dev/null || true; fi' EXIT

step() {
  local name="$1"; shift
  local log="${LOG_DIR}/${name}.log"
  local started=$SECONDS
  printf '%-16s' "${name}"
  if "$@" >"${log}" 2>&1; then
    echo "통과 ($((SECONDS - started))초)"
    # 통과해도 검사 범위가 줄었다는 알림은 보인다. check-public-safe.sh 는 값 목록이 없으면 형태만 검사하고 통과한다.
    if [ "${name}" = pr-size ]; then
      cat "${log}"
    else
      grep -A3 '^알림:' "${log}" || true
    fi
    if [ "${name}" = public-safe ]; then rm -f "${log}"; fi
  else
    echo "실패 ($((SECONDS - started))초)"
    echo "--- ${log} 의 끝 ---" >&2
    tail -n 40 "${log}" >&2
    if [ "${name}" = public-safe ]; then
      rm -f "${log}"
      echo "공개 정보 검사 로그는 비공개 값이 남지 않도록 보존하지 않는다." >&2
    else
      echo "원본 로그: ${log}" >&2
    fi
    exit 1
  fi
}

check_pr_size() {
  if [ "$(git -C "${ROOT}" branch --show-current)" = main ] ||
     ! git -C "${ROOT}" merge-base origin/main HEAD >/dev/null 2>&1; then
    echo "알림: main 브랜치이거나 공통 조상을 구할 수 없어 PR 크기를 건너뛴다."
    return 0
  fi
  (cd "${ROOT}" && node scripts/pr/pr-size.mjs origin/main HEAD)
}

check_purge_classification() {
  local base changed untracked
  local inputs=(backend/src/main/resources/db/migration/ backend/src/main/java/db/migration/ backend/src/test/resources/purge/)
  if base="$(git -C "${ROOT}" merge-base origin/main HEAD)"; then
    changed="$(git -C "${ROOT}" diff --name-only "${base}" -- "${inputs[@]}")" || return 1
    untracked="$(git -C "${ROOT}" ls-files --others --exclude-standard -- "${inputs[@]}")" || return 1
    if [ -z "${changed}${untracked}" ]; then
      echo "purge-classification: 변경이 없어 건너뛴다."
      return 0
    fi
  else
    echo "알림: 비교 기준을 구할 수 없어 purge 분류 검사를 돌린다."
  fi
  step purge-classification bash -c "cd '${ROOT}/backend' && ./gradlew test --tests '*PurgeClassificationTest'"
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
HERMES_PILLOW_VERSION="12.3.0"

check_hermes() {
  cd "${ROOT}" || return 1
  if ! python3 - "$HERMES_MCP_VERSION" "$HERMES_PYYAML_VERSION" "$HERMES_PILLOW_VERSION" <<'PY'
import importlib.metadata as metadata
import sys

for name, wanted in (("mcp", sys.argv[1]), ("PyYAML", sys.argv[2]), ("Pillow", sys.argv[3])):
    try:
        if metadata.version(name) != wanted:
            sys.exit(1)
    except metadata.PackageNotFoundError:
        sys.exit(1)
PY
  then
    python3 -m pip install "mcp==${HERMES_MCP_VERSION}" "PyYAML==${HERMES_PYYAML_VERSION}" "Pillow==${HERMES_PILLOW_VERSION}" || return 1
  fi
  python3 -m unittest discover -s hermes/tests
}

echo "임시 로그(성공 시 삭제, 실패 시 보존): ${LOG_DIR}"
if [ "${fast}" = true ]; then
  step public-safe     "${ROOT}/scripts/checks/check-public-safe.sh"
  step pr-size         check_pr_size
  step file-length     node "${ROOT}/scripts/checks/check-file-length.mjs"
  step web-install     pnpm --dir "${ROOT}/web" install --frozen-lockfile
  step migration-versions bash -c "cd '${ROOT}' && node scripts/checks/check-migration-versions.mjs"
  check_purge_classification
  step web-typecheck   pnpm --dir "${ROOT}/web" typecheck
  step web-test        pnpm --dir "${ROOT}/web" test:node
  step unit            bash -c "cd '${ROOT}' && node --test 'test/unit/**/*.test.ts'"
  step quality         bash -c "cd '${ROOT}' && scripts/checks/quality.sh check"
  echo "빠른 검사를 모두 통과했다. 나머지는 Draft CI 가 확인한다."
  exit 0
fi
if [ "$#" -gt 0 ]; then
  echo "브라우저 검사는 인자로 준 spec 만 돌린다: $*"
fi
# 파일 길이는 무거운 검사 전에 먼저 실패시킨다. 마지막 quality 단계에서도 독립 실행을 위해 다시 확인한다.
step file-length     node "${ROOT}/scripts/checks/check-file-length.mjs"
step web-install     pnpm --dir "${ROOT}/web" install --frozen-lockfile
if [ "${skip_browser}" = false ]; then
  step playwright    pnpm --dir "${ROOT}/web" exec playwright install chromium
fi
step tossinvest-install bun install --frozen-lockfile --cwd "${ROOT}/hermes/connectors/tossinvest"
step backend         bash -c "cd '${ROOT}/backend' && ./gradlew test"
step migration-versions bash -c "cd '${ROOT}' && node scripts/checks/check-migration-versions.mjs"
step mysql-migration "${ROOT}/scripts/checks/check-mysql-migration.sh"
step web-typecheck   pnpm --dir "${ROOT}/web" typecheck
step web-test        pnpm --dir "${ROOT}/web" test:node
step web-build       build_web
if [ "${skip_browser}" = false ]; then
  step browser       pnpm --dir "${ROOT}/web" test:browser "$@"
else
  echo "관련 화면 spec 이 없어 브라우저 검사를 생략한다. 전체 검사는 PR CI 가 확인한다."
fi
step e2e             bash -c "cd '${ROOT}' && node test/e2e/run.ts"
step unit            bash -c "cd '${ROOT}' && node --test 'test/unit/**/*.test.ts'"
step connectors      bash "${ROOT}/scripts/checks/check-connectors.sh"
step hermes          check_hermes
step public-safe     "${ROOT}/scripts/checks/check-public-safe.sh"
step quality         bash -c "cd '${ROOT}' && scripts/checks/quality.sh check"
echo "모두 통과했다"
