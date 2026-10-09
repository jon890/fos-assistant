#!/usr/bin/env bash
# 마이그레이션을 실제 MySQL 에서 검사한다. 로컬과 CI 가 이 스크립트를 함께 쓴다.
#
# 일회용 MySQL 컨테이너를 띄우고 backend 의 mysqlMigrationTest 를 돌린 뒤 컨테이너를 지운다.
# 그 태스크는 마이그레이션 검사와 함께 모든 저장소 쿼리를 한 번씩 실행하는 검사(RepositoryQueryMysqlTest)도 돌린다.
# Docker 가 있어야 한다.
#
# 서버의 기본 정렬 규칙을 utf8mb4_unicode_ci 로 준다. 운영 서버가 그렇게 떠 있다.
# 이 값이 MySQL 8.4 의 기본값(utf8mb4_0900_ai_ci)과 달라, 정렬 규칙을 적지 않은 새 표가 생기면 그 표만
# utf8mb4_unicode_ci 가 되고 MysqlMigrationTest 가 잡는다. 까닭과 규칙은 backend/docs/data-schema.md 의 「마이그레이션 작성 규칙」 에 있다.
set -Eeuo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
IMAGE="mysql:8.4"
NAME="mysql-migration-check-$$"
# 일회용 컨테이너에만 쓰는 값이다. 컨테이너는 이 머신의 loopback 에만 열린다.
ROOT_PASSWORD="migration-check"

cleanup() {
  docker rm -f "${NAME}" >/dev/null 2>&1 || true
}
trap cleanup EXIT

docker run -d --name "${NAME}" \
  -e MYSQL_ROOT_PASSWORD="${ROOT_PASSWORD}" \
  -p 127.0.0.1::3306 \
  --health-cmd "mysqladmin ping --protocol=tcp -h 127.0.0.1 -uroot -p${ROOT_PASSWORD} --silent" \
  --health-interval 2s --health-retries 60 \
  "${IMAGE}" \
  --character-set-server=utf8mb4 \
  --collation-server=utf8mb4_unicode_ci >/dev/null

host_port="$(docker port "${NAME}" 3306/tcp | head -n 1 | sed 's/.*://')"

# 이미지는 초기화하는 동안 소켓으로만 받는 임시 서버를 띄웠다가 내린다. TCP 로 붙어야 준비된 것이다.
# 그 판정은 컨테이너의 health check 가 하고 여기서는 상태만 읽는다.
ready=0
for _ in $(seq 1 60); do
  if [ "$(docker inspect --format '{{.State.Health.Status}}' "${NAME}")" = "healthy" ]; then
    ready=1
    break
  fi
  sleep 2
done
if [ "${ready}" -ne 1 ]; then
  echo "MySQL 이 2분 안에 준비되지 않았다." >&2
  docker logs "${NAME}" 2>&1 | tail -n 20 >&2
  exit 1
fi

cd "${ROOT}/backend"
MIGRATION_MYSQL_URL="jdbc:mysql://127.0.0.1:${host_port}/" \
MIGRATION_MYSQL_USERNAME=root \
MIGRATION_MYSQL_PASSWORD="${ROOT_PASSWORD}" \
  ./gradlew mysqlMigrationTest "$@"
