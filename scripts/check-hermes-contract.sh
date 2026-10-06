#!/usr/bin/env bash
# dashboard-profile-api 가 기대는 Hermes 내부 지점이 지정한 Hermes 소스에 그대로 있는지 확인한다(ADR-088).
#
# 인자가 없으면 hermes/tests/hermes_contract.py 의 HERMES_VERSION 을 상류 저장소에서 받는다.
# tag 를 주면 그 tag 를 받는다. 디렉터리를 주면 받지 않고 그 소스를 읽는다.
# 소스는 import 하지 않고 구문만 읽는다. Hermes 의 의존성을 설치하지 않는다.
set -Eeuo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
target="${1:-}"

contract() {
  python3 -I -c 'import sys; sys.path.insert(0, sys.argv[1]); import hermes_contract as c; print(getattr(c, sys.argv[2]))' \
    "${ROOT}/hermes/tests" "$1"
}

if [ -n "${target}" ] && [ -d "${target}" ]; then
  source_dir="$(cd "${target}" && pwd)"
elif [[ "${target}" == */* ]]; then
  echo "${target} 는 없는 디렉터리다. tag 는 / 를 담지 않는다" >&2
  exit 2
else
  repository="$(contract HERMES_REPOSITORY)"
  pinned="$(contract HERMES_VERSION)"
  target="${target:-${pinned}}"
  work="$(mktemp -d)"
  trap 'rm -rf "${work}"' EXIT
  source_dir="${work}/hermes"
  echo "Hermes ${target} 의 소스를 받는다"
  # CI 의 필수 검사에서 돈다. 일시적인 네트워크 실패로 떨어지지 않게 세 번까지 받는다.
  for attempt in 1 2 3; do
    if git -c advice.detachedHead=false clone --quiet --depth 1 --branch "${target}" "${repository}" "${source_dir}"; then
      break
    fi
    rm -rf "${source_dir}"
    if [ "${attempt}" = 3 ]; then
      echo "Hermes ${target} 의 소스를 받지 못했다" >&2
      exit 1
    fi
    sleep $((attempt * 5))
  done
  if [ "${target}" = "${pinned}" ]; then
    expected="$(contract HERMES_COMMIT)"
    actual="$(git -C "${source_dir}" rev-parse HEAD)"
    if [ "${actual}" != "${expected}" ]; then
      echo "tag ${target} 가 ${actual} 를 가리킨다. 계약의 HERMES_COMMIT 은 ${expected} 다" >&2
      exit 1
    fi
  fi
fi

echo "계약을 확인할 소스: ${source_dir}"
cd "${ROOT}"
HERMES_SOURCE="${source_dir}" HERMES_CONTRACT_REQUIRED=1 \
  python3 -I -m unittest discover -s hermes/tests -p 'test_hermes_contract.py' -v
