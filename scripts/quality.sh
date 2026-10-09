#!/usr/bin/env bash
# backend 와 web 의 품질 검사를 한 번에 돌리거나 기계가 고칠 수 있는 것을 고친다.
#
#   scripts/quality.sh check  파일을 바꾸지 않고 검사한다. backend 와 web 을 모두 돌린 뒤 하나라도 실패하면 1 로 끝난다.
#   scripts/quality.sh fix    사람이 판단하지 않아도 되는 것만 고친 뒤 check 를 돌린다. 새 위반을 기준 파일에 더하지 않는다.
#
# 규칙은 backend 는 ArchitectureRules.java 와 backend/config/checkstyle/ 이, web 은 web/eslint.config.mjs 가 갖는다.
# 기준 파일을 다루는 방법은 backend/docs/code-architecture.md 와 web/AGENTS.md 에 있다.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

usage() {
  echo "사용법: scripts/quality.sh <check|fix>" >&2
  echo "  check  파일을 바꾸지 않고 검사한다" >&2
  echo "  fix    기계가 고칠 수 있는 것을 고친 뒤 check 를 돌린다" >&2
}

if [ "$#" -ne 1 ] || { [ "$1" != "check" ] && [ "$1" != "fix" ]; }; then
  usage
  exit 2
fi
mode="$1"

if [ ! -d "${ROOT}/web/node_modules" ]; then
  echo "web/node_modules 가 없다. 먼저 'cd web && pnpm install --frozen-lockfile' 을 실행한다." >&2
  exit 1
fi

# 단계의 종료 코드를 알린다. 0 이 아니어도 멈추지 않고 그 코드를 돌려준다.
describe() {
  local name="$1" code="$2"
  if [ "${code}" -eq 0 ]; then
    echo "${name}: 통과"
  else
    echo "${name}: 실패 (종료 코드 ${code})"
  fi
}

run_check() {
  local backend=0 lint=0 format=0 file_length=0

  node "${ROOT}/scripts/check-file-length.mjs" || file_length=$?

  # --continue: 앞 검사가 실패해도 나머지를 돌려 한 번에 모든 위반을 본다.
  (cd "${ROOT}/backend" && ./gradlew qualityCheck --continue) || backend=$?

  # 옛 보고서를 읽어 이미 고친 위반을 보이지 않도록 지우고 시작한다.
  rm -f "${ROOT}/web/build/eslint-report.json"
  # --output-file 을 주면 ESLint 는 표준 출력에 내지 않는다. 실패한 위반은 quality-report.mjs 가 같은 JSON 에서 뽑는다.
  (cd "${ROOT}/web" && pnpm lint --format json --output-file build/eslint-report.json) || lint=$?
  (cd "${ROOT}/web" && pnpm format:check) || format=$?

  echo ""
  describe "파일 길이" "${file_length}"
  describe "backend (qualityCheck)" "${backend}"
  describe "web (lint)" "${lint}"
  describe "web (format:check)" "${format}"
  echo ""
  node "${ROOT}/scripts/quality-report.mjs"

  if [ "${file_length}" -ne 0 ] || [ "${backend}" -ne 0 ] || [ "${lint}" -ne 0 ] || [ "${format}" -ne 0 ]; then
    return 1
  fi
  return 0
}

if [ "${mode}" = "check" ]; then
  run_check
  exit $?
fi

# fix: 앞 단계가 실패해도 다음 단계로 가고, 끝에 check 로 남은 위반을 본다.
rewrite=0 spotless=0 eslint_fix=0 prettier=0
(cd "${ROOT}/backend" && ./gradlew rewriteChanged) || rewrite=$?
# archTest 는 고친 위반을 기준에서 빼기만 한다. 새 위반은 그대로 실패한다.
(cd "${ROOT}/backend" && ./gradlew spotlessApply archTest --rerun -Parchunit.freeze.store.default.allowStoreUpdate=true) || spotless=$?
(cd "${ROOT}/web" && pnpm exec eslint --fix --prune-suppressions) || eslint_fix=$?
(cd "${ROOT}/web" && pnpm format:changed) || prettier=$?

echo ""
describe "OpenRewrite (rewriteChanged)" "${rewrite}"
describe "Spotless 와 ArchUnit 기준 줄이기" "${spotless}"
describe "eslint --fix --prune-suppressions" "${eslint_fix}"
describe "Prettier (format:changed)" "${prettier}"
echo ""
echo "남은 위반을 확인하려고 check 를 돌린다."
check_status=0
run_check || check_status=$?
if [ "${check_status}" -ne 0 ]; then
  echo ""
  echo "사람이 판단할 위반이 남았다. 위 목록을 직접 고친다. 새 위반은 기준 파일에 더하지 않는다."
fi
exit "${check_status}"
