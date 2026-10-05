#!/usr/bin/env bash
# 커넥터별 타입, 동작, 커밋한 묶음 파일을 한 명령으로 검사한다.
set -Eeuo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
expected_bun="1.3.5"
actual_bun="$(bun --version)"
if [ "${actual_bun}" != "${expected_bun}" ]; then
  echo "커넥터 검사는 Bun ${expected_bun}이 필요하다. 현재는 ${actual_bun}이다." >&2
  exit 2
fi

for connector in "${ROOT}"/hermes/connectors/*; do
  [ -d "${connector}" ] || continue
  bun install --cwd "${connector}" --frozen-lockfile
  bun run --cwd "${connector}" typecheck
  bun run --cwd "${connector}" test
  bun run --cwd "${connector}" check:bundle
done
