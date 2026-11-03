#!/usr/bin/env bash
# 커넥터별 타입, 동작, 소스에서 빌드한 묶음 파일을 한 명령으로 검사한다. 커넥터끼리는 동시에 돈다.
set -Eeuo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
expected_bun="1.3.14"
actual_bun="$(bun --version)"
if [ "${actual_bun}" != "${expected_bun}" ]; then
  echo "커넥터 검사는 Bun ${expected_bun}이 필요하다. 현재는 ${actual_bun}이다." >&2
  exit 2
fi

connectors=()
for connector in "${ROOT}"/hermes/connectors/*; do
  [ -d "${connector}" ] || continue
  connectors+=("${connector}")
done

# 설치는 Bun 의 전역 캐시를 함께 쓰므로 차례로 한다. 몇 초면 끝난다.
for connector in "${connectors[@]}"; do
  bun install --cwd "${connector}" --frozen-lockfile
done

# 커넥터끼리는 파일과 포트를 공유하지 않으므로 타입 검사, 테스트, 묶음 검사를 커넥터마다 동시에 돈다.
# 출력이 섞이지 않게 커넥터별 파일에 모았다가 끝난 뒤 차례로 보인다.
log_dir="$(mktemp -d "${TMPDIR:-/tmp}/check-connectors.XXXXXX")"
trap 'rm -rf "${log_dir}"' EXIT
check_connector() {
  bun run --cwd "$1" typecheck &&
    bun run --cwd "$1" test &&
    bun run --cwd "$1" check:bundle
}
pids=()
for connector in "${connectors[@]}"; do
  (check_connector "${connector}") >"${log_dir}/$(basename "${connector}").log" 2>&1 &
  pids+=("$!")
done
# 커넥터 하나가 멈추면 CI 시간 제한에 묻히지 않게 1분마다 아직 도는 커넥터 이름을 알린다.
(
  sleeper=""
  trap '[ -z "${sleeper}" ] || kill "${sleeper}" 2>/dev/null; exit 0' TERM
  while true; do
    sleep 60 &
    sleeper=$!
    wait "${sleeper}"
    for index in "${!connectors[@]}"; do
      if kill -0 "${pids[$index]}" 2>/dev/null; then
        echo "아직 도는 중: $(basename "${connectors[$index]}")" >&2
      fi
    done
  done
) >/dev/null &
progress_pid=$!
trap 'kill "${progress_pid}" 2>/dev/null || true; rm -rf "${log_dir}"' EXIT
failed=()
for index in "${!connectors[@]}"; do
  name="$(basename "${connectors[$index]}")"
  status=0
  wait "${pids[$index]}" || status=$?
  echo "===== ${name} ====="
  cat "${log_dir}/${name}.log"
  if [ "${status}" -ne 0 ]; then failed+=("${name}"); fi
done
kill "${progress_pid}" 2>/dev/null || true
if [ "${#failed[@]}" -gt 0 ]; then
  echo "커넥터 검사가 실패했다: ${failed[*]}" >&2
  exit 1
fi

# 루트의 묶음 생산 코드도 커넥터에 고정한 기존 TypeScript 의존성으로 검사한다.
bun "${ROOT}/hermes/connectors/gmail/node_modules/typescript/bin/tsc" \
  --noEmit --strict --skipLibCheck --target ESNext --module ESNext \
  --moduleResolution bundler --allowImportingTsExtensions \
  --typeRoots "${ROOT}/hermes/connectors/gmail/node_modules/@types" \
  "${ROOT}/hermes/connectors/package.ts" "${ROOT}/hermes/connectors/package.test.ts"
bun test "${ROOT}/hermes/connectors/package.test.ts"
