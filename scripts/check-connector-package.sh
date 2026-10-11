#!/usr/bin/env bash
# 같은 checkout에서 생성 entry를 비우고 Docker 빌드와 runner 재빌드의 모든 파일을 비교한다.
set -Eeuo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
# Python을 부르는 기존 커넥터 검사가 다음 Docker context에 캐시를 남기지 않게 한다.
export PYTHONDONTWRITEBYTECODE=1
work="$(mktemp -d "${TMPDIR:-/tmp}/connector-package.XXXXXX")"
image="fos-connector-package-check:$(basename "$work" | tr '[:upper:]' '[:lower:]')"
container=""
connectors=()
cleanup() {
  if [ -n "$container" ]; then docker rm "$container" >/dev/null; fi
  docker image rm "$image" >/dev/null 2>&1 || true
  for id in "${connectors[@]}"; do
    local entry="hermes/connectors/$id/dist/$id-mcp.js"
    if [ -f "$work/original/$id" ]; then
      cp -p "$work/original/$id" "$entry"
    else
      rm -f "$entry"
    fi
  done
  rm -rf "$work"
}
trap cleanup EXIT
mkdir "$work/original"
for connector in hermes/connectors/*/; do
  [ -f "$connector/connector.json" ] || continue
  id="$(basename "$connector")"
  connectors+=("$id")
  entry="$connector/dist/$id-mcp.js"
  if [ -f "$entry" ]; then cp -p "$entry" "$work/original/$id"; fi
  # 선언한 entry만 지운다. dist 아래의 다른 추적 파일은 그대로 둔다.
  rm -f "$entry"
done
sha="$(git rev-parse HEAD)"
docker build --build-arg "SOURCE_COMMIT=$sha" -t "$image" hermes/connectors
container="$(docker create "$image")"
docker cp "$container:/bundle" "$work/bundle"
docker rm "$container" >/dev/null
container=""
bun run hermes/connectors/package.ts verify "$work/bundle" "$sha"
for id in "${connectors[@]}"; do
  mkdir -p "hermes/connectors/$id/dist"
  cp -p "$work/bundle/hermes/connectors/$id/dist/$id-mcp.js" "hermes/connectors/$id/dist/$id-mcp.js"
  git diff --exit-code -- "hermes/connectors/$id/dist/$id-mcp.js"
done
# 기존 HTTP 대역과 check:bundle로 Docker와 runner의 바이트 동일성도 확인한다.
bash scripts/check-connectors.sh
bun run hermes/connectors/package.ts build "$work/runner" "$sha"
bun run hermes/connectors/package.ts verify "$work/runner" "$sha"
diff -r "$work/bundle" "$work/runner"
echo "Docker와 runner의 전체 묶음, 추적 entry의 바이트가 일치했다"
