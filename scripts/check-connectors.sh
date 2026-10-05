#!/usr/bin/env bash
# 커넥터별 타입, 동작, 커밋한 묶음 파일을 한 명령으로 검사한다.
set -Eeuo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
for connector in "${ROOT}"/hermes/connectors/*; do
  [ -d "${connector}" ] || continue
  bun install --cwd "${connector}" --frozen-lockfile
  bun run --cwd "${connector}" typecheck
  bun run --cwd "${connector}" test
  bun run --cwd "${connector}" check:bundle
done
