#!/usr/bin/env bash
# 작업 단위의 마지막 「정리」 단계를 돌린다. 무엇을 지우는지와 순서는 이 스크립트가 정본이다.
#
#   scripts/finish-task.sh <브랜치>
#
# 브랜치의 PR 이 머지됐을 때만 지운다. 머지되지 않았으면 아무것도 지우지 않고 2 로 끝난다.
# 지우는 것은 넷이다.
#   1. 그 브랜치를 체크아웃한 워크트리
#   2. 로컬 브랜치
#   3. 원격 브랜치(남아 있을 때만)
#   4. 이 저장소의 로컬 검사가 띄웠다가 남긴 임시 컨테이너
#      표식 fos-assistant.temp 가 붙었거나 이름이 mysql-migration-check- 로 시작하는 것 중 멈춘 것만 지운다.
#      돌고 있는 것은 다른 작업의 검사일 수 있어 남긴다.
# 위임한 에이전트 세션은 스크립트가 끝낼 수 없다. 띄운 쪽이 회신을 받은 뒤 끝낸다.
set -euo pipefail

if [ "$#" -ne 1 ]; then
  echo "사용법: scripts/finish-task.sh <브랜치>" >&2
  exit 2
fi
branch="$1"
if [ "${branch}" = "main" ]; then
  echo "main 은 정리 대상이 아니다." >&2
  exit 2
fi

ROOT="$(git -C "$(dirname "${BASH_SOURCE[0]}")" rev-parse --path-format=absolute --git-common-dir)"
ROOT="$(dirname "${ROOT}")"

state="$(gh pr list --repo jon890/fos-assistant --state all --head "${branch}" --json state --jq '.[0].state // ""')"
if [ "${state}" != "MERGED" ]; then
  echo "${branch} 의 PR 이 머지되지 않았다(상태: ${state:-PR 없음}). 아무것도 지우지 않는다." >&2
  exit 2
fi

# 1. 워크트리. 정리를 그 워크트리 안에서 돌리면 지울 수 없으므로 저장소 root 에서 돌린다.
worktree="$(git -C "${ROOT}" worktree list --porcelain \
  | awk -v ref="refs/heads/${branch}" '/^worktree /{d=substr($0,10)} $0=="branch "ref{print d}')"
if [ -n "${worktree}" ]; then
  case "$(pwd -P)/" in
    "$(cd "${worktree}" && pwd -P)/"*)
      echo "지울 워크트리 안에서 돌렸다. 저장소 root 에서 다시 돌린다: ${worktree}" >&2
      exit 2 ;;
  esac
  git -C "${ROOT}" worktree remove "${worktree}"
  echo "워크트리를 지웠다: ${worktree}"
fi

# 2. 로컬 브랜치. PR 이 머지됐으므로 -D 로 지운다. merge commit 방식이라 main 에 같은 커밋이 있다.
if git -C "${ROOT}" show-ref --verify --quiet "refs/heads/${branch}"; then
  git -C "${ROOT}" branch -D "${branch}"
fi

# 3. 원격 브랜치.
if git -C "${ROOT}" ls-remote --exit-code --heads origin "${branch}" >/dev/null 2>&1; then
  git -C "${ROOT}" push origin --delete "${branch}"
fi
git -C "${ROOT}" worktree prune

# 4. 임시 컨테이너. Docker 가 없거나 꺼져 있으면 건너뛴다.
if command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1; then
  ids="$( { docker ps -aq --filter label=fos-assistant.temp --filter status=exited --filter status=created --filter status=dead
            docker ps -aq --filter name=^mysql-migration-check- --filter status=exited --filter status=created --filter status=dead; } | sort -u)"
  if [ -n "${ids}" ]; then
    echo "${ids}" | xargs docker rm -f >/dev/null
    echo "멈춘 임시 컨테이너 $(echo "${ids}" | wc -l | tr -d ' ')개를 지웠다."
  fi
else
  echo "알림: Docker 에 붙지 못해 임시 컨테이너 정리를 건너뛰었다."
fi

echo "정리를 마쳤다: ${branch}"
