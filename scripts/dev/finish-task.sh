#!/usr/bin/env bash
# 작업 단위의 마지막 「정리」 단계를 돌린다. 무엇을 지우는지와 순서는 이 스크립트가 정본이다.
#
#   scripts/dev/finish-task.sh <브랜치>
#
# 이 저장소에서 머지된 PR 의 끝 커밋까지만 담긴 브랜치를 정리한다.
# 다음이면 아무것도 지우지 않고 2 로 끝난다.
#   - 머지된 PR 이 없다(fork 의 같은 이름 PR 은 보지 않는다)
#   - 로컬이나 원격 브랜치에 머지된 PR 의 끝 커밋 뒤로 커밋이 더 있다
#   - 워크트리에 ignored 파일이 든 .omc/ 가 있다. 조사 기록일 수 있어 옮긴 뒤 다시 돌린다
#   - 지울 워크트리 안에서 돌렸다
# 지우는 것은 넷이다.
#   1. 그 브랜치를 체크아웃한 워크트리. 작업 트리가 깨끗하지 않으면 git 이 멈춘다
#   2. 로컬 브랜치
#   3. 원격 브랜치(남아 있을 때만)
#   4. 이 저장소의 로컬 검사가 남긴 멈춘(exited, dead) 임시 컨테이너
#      표식 fos-assistant.temp 로 찾는다. 이름 mysql-migration-check- 로 찾는 것은 표식이 붙기 전 컨테이너를 위한 것이고 옮겨 가는 동안만 둔다.
#      돌고 있거나 막 만들어진 것은 다른 작업의 검사일 수 있어 남긴다.
# 위임한 에이전트 세션은 스크립트가 끝낼 수 없다. 띄운 쪽이 회신을 받은 뒤 끝낸다.
set -euo pipefail

REPO="jon890/fos-assistant"

if [ "$#" -ne 1 ]; then
  echo "사용법: scripts/dev/finish-task.sh <브랜치>" >&2
  exit 2
fi
branch="$1"
if [ "${branch}" = "main" ]; then
  echo "main 은 정리 대상이 아니다." >&2
  exit 2
fi

# 첫 워크트리가 저장소 root 다. 이 스크립트가 어느 워크트리에 있든 같은 값을 얻는다.
ROOT="$(git -C "$(dirname "${BASH_SOURCE[0]}")" worktree list --porcelain | sed -n '1s/^worktree //p')"
git -C "${ROOT}" worktree prune

merged_head="$(gh pr list --repo "${REPO}" --state merged --head "${branch}" \
  --json headRefOid,isCrossRepository --jq '[.[] | select(.isCrossRepository | not)][0].headRefOid // ""')"
if [ -z "${merged_head}" ]; then
  echo "${branch} 의 머지된 PR 이 없다. 아무것도 지우지 않는다." >&2
  exit 2
fi
git -C "${ROOT}" fetch --quiet origin "${merged_head}" 2>/dev/null || git -C "${ROOT}" fetch --quiet origin

# 머지된 끝 커밋 뒤로 커밋이 더 있으면 그 커밋은 어디에도 머지되지 않았다. 지우면 되살릴 수 없다.
ensure_merged() {
  local where="$1" sha="$2"
  if ! git -C "${ROOT}" merge-base --is-ancestor "${sha}" "${merged_head}"; then
    echo "${where} 브랜치에 머지된 PR 뒤의 커밋이 있다(${sha}). 아무것도 지우지 않는다." >&2
    exit 2
  fi
}
if git -C "${ROOT}" show-ref --verify --quiet "refs/heads/${branch}"; then
  ensure_merged "로컬" "$(git -C "${ROOT}" rev-parse "refs/heads/${branch}")"
fi
remote_rc=0
remote_line="$(git -C "${ROOT}" ls-remote --exit-code --heads origin "${branch}")" || remote_rc=$?
case "${remote_rc}" in
  0) ensure_merged "원격" "${remote_line%%[[:space:]]*}" ;;
  2) ;;
  *) echo "원격 브랜치를 조회하지 못했다(종료 코드 ${remote_rc}). 아무것도 지우지 않는다." >&2
     exit 2 ;;
esac

# 1. 워크트리.
worktree="$(git -C "${ROOT}" worktree list --porcelain \
  | awk -v ref="refs/heads/${branch}" '/^worktree /{d=substr($0,10)} $0=="branch "ref{print d}')"
if [ -n "${worktree}" ] && [ -d "${worktree}" ]; then
  case "$(pwd -P)/" in
    "$(cd "${worktree}" && pwd -P)/"*)
      echo "지울 워크트리 안에서 돌렸다. 저장소 root 에서 다시 돌린다: ${worktree}" >&2
      exit 2 ;;
  esac
  # git worktree remove 는 untracked 파일에서는 멈추지만 ignored 파일은 함께 지운다.
  if [ -d "${worktree}/.omc" ] && [ -n "$(ls -A "${worktree}/.omc")" ]; then
    echo "워크트리에 .omc/ 가 남아 있다. 남길 것을 옮기거나 지운 뒤 다시 돌린다: ${worktree}/.omc" >&2
    exit 2
  fi
  git -C "${ROOT}" worktree remove "${worktree}"
  echo "워크트리를 지웠다: ${worktree}"
fi

# 2. 로컬 브랜치. 위에서 머지된 끝 커밋의 조상임을 확인했다.
if git -C "${ROOT}" show-ref --verify --quiet "refs/heads/${branch}"; then
  git -C "${ROOT}" branch -D "${branch}"
fi

# 3. 원격 브랜치.
if [ "${remote_rc}" -eq 0 ]; then
  git -C "${ROOT}" push origin --delete "${branch}"
fi

# 4. 임시 컨테이너. Docker 가 없거나 꺼져 있으면 건너뛴다.
if command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1; then
  ids="$( { docker ps -aq --filter label=fos-assistant.temp --filter status=exited
            docker ps -aq --filter label=fos-assistant.temp --filter status=dead
            docker ps -aq --filter name=^mysql-migration-check- --filter status=exited
            docker ps -aq --filter name=^mysql-migration-check- --filter status=dead; } | sort -u)"
  if [ -n "${ids}" ]; then
    echo "${ids}" | xargs docker rm -f >/dev/null
    echo "멈춘 임시 컨테이너 $(echo "${ids}" | wc -l | tr -d ' ')개를 지웠다."
  fi
else
  echo "알림: Docker 에 붙지 못해 임시 컨테이너 정리를 건너뛰었다."
fi

echo "정리를 마쳤다: ${branch}"
