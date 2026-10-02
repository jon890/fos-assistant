#!/usr/bin/env bash
# 바뀐 파일 경로를 받아 PR 에 붙일 위험 라벨을 한 줄에 하나씩 낸다.
#
# 판정은 경로 규칙만으로 한다. LLM 이 매기는 점수는 같은 PR 에서도 실행마다 달라져
# 머지 기준으로 쓸 수 없다. 규칙이 틀렸으면 이 파일을 고친다.
#
# 사용법:
#   git diff --name-only origin/main...HEAD | scripts/pr-risk-labels.sh
#   gh pr diff 12 --name-only | scripts/pr-risk-labels.sh
#
# 라벨이 하나도 없으면 아무것도 내지 않고 종료 코드 0 으로 끝난다.

set -euo pipefail

migration=0
security=0
hermes=0
deploy=0

while IFS= read -r path; do
  [ -z "$path" ] && continue
  case "$path" in
    backend/src/main/resources/db/migration/*)
      migration=1 ;;
  esac
  case "$path" in
    backend/src/main/java/com/bifos/assistant/shared/auth/* | \
    backend/src/main/java/com/bifos/assistant/shared/config/SecurityConfig.java | \
    backend/src/main/java/com/bifos/assistant/mcp/* | \
    backend/src/main/java/com/bifos/assistant/people/application/SignInPolicy.java | \
    web/src/auth.ts | \
    web/src/lib/control-plane.ts | \
    web/src/app/api/* | \
    scripts/check-public-safe.sh | \
    hermes/plugins/*)
      security=1 ;;
  esac
  case "$path" in
    backend/src/main/java/com/bifos/assistant/hermes/* | \
    test/e2e/fake-hermes.ts | \
    docs/hermes/* | \
    docs/backend/mcp-caller.md | \
    docs/backend/conversation.md | \
    hermes/*)
      hermes=1 ;;
  esac
  case "$path" in
    backend/Dockerfile | web/Dockerfile | \
    backend/src/main/resources/application*.yml | \
    .github/workflows/*)
      deploy=1 ;;
  esac
done

[ "$migration" = 1 ] && echo "위험:마이그레이션"
[ "$security" = 1 ] && echo "위험:보안"
[ "$hermes" = 1 ] && echo "위험:Hermes연동"
[ "$deploy" = 1 ] && echo "위험:배포설정"
exit 0
