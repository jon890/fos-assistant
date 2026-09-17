#!/usr/bin/env bash
# 공개 저장소에 적으면 안 되는 운영 정보가 들어갔는지 검사한다.
#
# 판정 기준은 AGENTS.md 의 「공개 저장소」 절이 소유한다.
#
# **이 파일에 감출 값을 적지 않는다.**
# 여기 적으면 무엇을 감추는지가 아니라 감추려던 값 자체가 공개된다.
# 그래서 두 층으로 나눈다.
#
#   형태 패턴   key 를 꺼내는 명령처럼 값이 아니라 모양인 것. 이 파일이 갖는다
#   값 목록     컨테이너 이름, 포트, 경로처럼 우리 환경의 값. 비공개 저장소가 갖는다
#
# 값 목록은 fos-home-infra 의 `secrets/public-repo-denylist.txt` 에서 읽는다.
# 한 줄에 확장 정규식 하나이고 `#` 로 시작하는 줄은 건너뛴다.
# 그 저장소가 없으면 형태 패턴만 검사하고 그 사실을 알린다.
#
# 종료 코드
#   0  찾지 못했다
#   1  의심스러운 줄을 찾았다
#   2  검사를 돌리지 못했다
set -Eeuo pipefail

cd "$(dirname "$0")/.."

DENYLIST="${PUBLIC_REPO_DENYLIST:-$HOME/personal/fos-home-infra/secrets/public-repo-denylist.txt}"

EXCLUDES=(
  ':(exclude)scripts/check-public-safe.sh'
  ':(exclude)AGENTS.md'
  # 컨테이너가 자기 자신을 부르는 헬스체크다. 우리 환경의 값이 아니다.
  ':(exclude)backend/Dockerfile'
  ':(exclude)web/Dockerfile'
)

# 값이 아니라 모양이라 이 파일에 적어도 된다.
SHAPES=(
  'API_SERVER_KEY[[:space:]]*=::key 를 꺼내는 명령'
  'docker[[:space:]]+exec::컨테이너 안에서 명령을 돌리는 방법'
  'ssh[[:space:]]+homeserver.*(curl|grep|cat|docker|sed|awk)::홈서버에서 명령을 돌리는 방법'
  '127\.0\.0\.1:[0-9]{4}::내부 주소와 포트'
)

found=0

report() {
  echo "[$2]"
  echo "$1" | sed 's/^/  /'
  echo
  found=1
}

for entry in "${SHAPES[@]}"; do
  pattern="${entry%%::*}"
  reason="${entry#*::}"
  # git 이 추적하는 파일만 본다. .gitignore 아래는 공개되지 않는다.
  if hits=$(git grep -nE "$pattern" -- . "${EXCLUDES[@]}" 2>/dev/null); then
    report "$hits" "$reason"
  fi
done

if [ -r "$DENYLIST" ]; then
  while IFS= read -r line; do
    [ -z "$line" ] && continue
    case "$line" in \#*) continue ;; esac
    if hits=$(git grep -nE "$line" -- . "${EXCLUDES[@]}" 2>/dev/null); then
      report "$hits" "비공개 목록에 걸렸다"
    fi
  done < "$DENYLIST"
else
  echo "알림: 값 목록을 읽지 못해 형태 패턴만 검사했다."
  echo "  찾은 자리: $DENYLIST"
  echo "  그 파일은 비공개 저장소 fos-home-infra 가 소유한다."
  echo "  경로가 다르면 PUBLIC_REPO_DENYLIST 로 준다."
  echo
fi

if [ "$found" -eq 0 ]; then
  echo "통과: 공개 저장소에 적으면 안 되는 것을 찾지 못했다"
  exit 0
fi

cat <<'GUIDE'
위 줄을 고친다. 판정 기준은 AGENTS.md 의 「공개 저장소」 절이다.

- 무엇을 확인해야 하는지만 적고, 실행 방법은 fos-home-infra 를 가리킨다
- 측정한 결과 수치는 적어도 된다. 그것을 얻은 명령을 적지 않는 것이다
- 조사 기록은 저장소 밖에 둔다
GUIDE
exit 1
