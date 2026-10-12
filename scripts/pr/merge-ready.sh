#!/usr/bin/env bash
# PR 이 지금 머지할 수 있는지 판정한다. 머지 직전에 손으로 반복하던 확인을 한 번에 한다.
#
#   scripts/pr/merge-ready.sh <PR 번호>
#
# 보는 것: 브랜치 보호의 필수 검사(이름으로 대조), 미해결 리뷰 스레드, Draft 여부, mergeStateStatus,
# head 가 origin/main 을 포함하는지. 포함하지 않으면 CI 뒤 main 에 들어온 커밋이 이 PR 과 겹치는지,
# 마이그레이션, 빌드와 의존성 파일, test/unit, 공유 계약을 건드렸는지 보고 다시 합쳐야 하는지 가른다.
# 공유 계약으로 볼 경로와 까닭은 scripts/pr/merge-ready.mjs 의 RISK_PATHS 에 있다.
# 필수가 아닌 claude-review 만 실패해 UNSTABLE 이면 머지할 수 있는 것으로 본다.
#
# 첫 줄이 판정한 head 의 해시이고, 머지는 그 해시로 고정한다(gh pr merge --match-head-commit).
# 마지막 줄이 판정이고 종료 코드가 같이 갈린다.
#   0   머지 가능
#   10  대기(도는 중이거나 아직 없는 필수 검사, mergeable 계산 중, 조회 중 head 변경)
#   11  main 다시 합치기 필요(까닭)
#   12  막힘(까닭): Draft, 충돌, 필수 검사 실패, 미해결 스레드, 그 밖의 UNSTABLE 과 CLEAN 이 아닌 mergeStateStatus
#   2   판정 불가: 사용법이 틀렸거나 gh, git 조회가 실패했거나 필수 검사가 0개
#
# gh 가 로그인돼 있어야 하고, 저장소 root 의 어느 워크트리에서 돌려도 된다. 머지는 하지 않는다.
set -euo pipefail
exec node "$(dirname "$0")/merge-ready.mjs" "$@"
