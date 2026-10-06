#!/usr/bin/env bash
# dashboard-profile-api 가 기대는 Hermes 내부 지점이 지정한 Hermes 소스에 그대로 있는지 확인한다(ADR-088).
#
# 인자가 없으면 hermes/tests/hermes_contract.py 의 HERMES_VERSION 을 상류 저장소에서 받는다.
# tag 를 주면 그 tag 를 받는다. 디렉터리를 주면 받지 않고 그 소스를 읽는다.
# 소스는 import 하지 않고 구문만 읽는다. Hermes 의 의존성을 설치하지 않는다.
set -Eeuo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
target="${1:-}"

if [ -n "${target}" ] && [ -d "${target}" ]; then
  source_dir="$(cd "${target}" && pwd)"
else
  repository="$(python3 -I -c 'import sys; sys.path.insert(0, sys.argv[1]); import hermes_contract as c; print(c.HERMES_REPOSITORY)' "${ROOT}/hermes/tests")"
  if [ -z "${target}" ]; then
    target="$(python3 -I -c 'import sys; sys.path.insert(0, sys.argv[1]); import hermes_contract as c; print(c.HERMES_VERSION)' "${ROOT}/hermes/tests")"
  fi
  work="$(mktemp -d)"
  trap 'rm -rf "${work}"' EXIT
  source_dir="${work}/hermes"
  echo "Hermes ${target} 의 소스를 받는다"
  git -c advice.detachedHead=false clone --quiet --depth 1 --branch "${target}" "${repository}" "${source_dir}"
fi

echo "계약을 확인할 소스: ${source_dir}"
cd "${ROOT}"
HERMES_SOURCE="${source_dir}" python3 -I -m unittest discover -s hermes/tests -p 'test_hermes_contract.py' -v
