---
name: pr-merge
description: |
  이 저장소에서 Draft PR 을 열고 Ready 로 바꾸고, Claude 리뷰를 반영해 머지하기까지의 순서다.
  PR 을 열 때, Draft CI 가 끝나 Ready 로 바꿀 때, 리뷰 등급을 보고 머지할지 정할 때 읽는다.
---

# pr-merge

**목표: PR 이 지금 main 과 합친 상태에서 필수 검사를 모두 통과했고, Claude 리뷰를 한 번 반영한 뒤 머지되고, 그 작업이 만든 것이 모두 지워진다.**

워커의 보고를 읽는 것은 확인이 아니다. CI 결과와 브랜치 보호는 직접 조회한다.

## 실행 절차

| 단계 | 이름 | 통과 조건 | reference |
| --- | --- | --- | --- |
| 1 | Draft PR 열기 | `scripts/dev/check-local.sh --fast` 가 0 으로 끝났고 `gh pr create --draft` 가 PR 주소를 냈다 | |
| 2 | Draft CI | `gh pr checks` 의 모든 검사가 통과다 | |
| 3 | main 합치고 Ready | 최신 main 을 합친 커밋의 CI 가 모두 통과했고 `gh pr ready` 가 끝났다 | |
| 4 | 리뷰 반영 | 남은 🔴 P1 과 🟠 P2 를 한 번 고쳐 push 했거나 P2 를 고치지 않는 까닭을 PR 에 남겼다 | [`code-review-prompt.txt`](../../../.github/workflows/code-review-prompt.txt) |
| 5 | 머지 판정 | 지금 main 과 합친 상태에서 돈 CI 가 필수 검사를 모두 통과했다 | |
| 6 | 정리 | `scripts/dev/finish-task.sh` 가 0 으로 끝났고, 위임한 세션을 끝냈다 | |

아래 명령의 `$PR` 은 PR 번호다.

## 1. Draft PR 열기

push 전에 빠른 로컬 검사만 돌리고 Draft 로 연다.
backend 테스트와 MySQL, 브라우저처럼 무거운 검사는 로컬에서 기다리지 않고 Draft CI 에 맡긴다.
Draft 에서는 브라우저 전체 shard 를 포함한 CI 가 돌고 Claude 리뷰는 돌지 않는다.

```bash
# cwd: 저장소 root
scripts/dev/check-local.sh --fast
git push -u origin HEAD
gh pr create --draft --title "$TITLE" --body-file "$BODY_FILE"
```

## 2. Draft CI

```bash
# cwd: 저장소 root
gh pr checks "$PR" --watch
```

실패하면 이 PR 에서 고치고 다시 기다린다. 다른 화면의 테스트가 깨졌어도 이 PR 에서 고친다.

## 3. main 합치고 Ready

Draft CI 가 전체 통과하면 Ready 직전에 최신 main 을 작업 브랜치에 merge 한다.
rebase 와 force push 로 이력을 바꾸지 않는다.
합친 뒤 빠른 검사를 다시 돌리고 push 한다. 코디네이터가 충돌을 직접 풀었을 때도 같은 검사를 거친다.

```bash
# cwd: 저장소 root
git fetch origin
git merge origin/main
scripts/dev/check-local.sh --fast
git push
gh pr checks "$PR" --watch
gh pr ready "$PR"
```

main 에 새 커밋이 없으면 합칠 것이 없다. 같은 head 의 CI 결과를 쓰고, Ready 전환만으로 CI 를 다시 돌리지 않는다.

## 4. 리뷰 반영

Ready 로 바꾸면 Claude 리뷰가 돈다. Draft 가 아닌 PR 을 바로 열 때도 돈다.
리뷰가 도는 동안 PR 의 CI 결과를 확인한다.

| 남은 지적 | 어떻게 |
| --- | --- |
| 🔴 P1 치명 | 고쳐 push 한다. 고친 커밋의 CI 가 통과하면 머지한다 |
| 🟠 P2 높음 | 머지 전에 고쳐 push 한다. 이번에 고치지 않으면 그 까닭을 PR 에 한 줄 남긴다 |
| 🟡 P3 부터 ⚪ P5 까지 | 반영할지 판단해 머지해도 된다 |

**리뷰는 한 번 반영하고 끝낸다. 고친 뒤 `/review` 댓글로 리뷰를 다시 돌리지 않는다.**
`/review` 는 사람이 다시 봐 달라고 요청할 때만 단다.

## 5. 머지 판정

**머지 판정은 PR 의 CI 결과로 한다. 머지마다 승인을 받지 않는다.** 다음 순서로 확인한다.

1. CI 가 PR 을 지금 main 과 합친 상태에서 실행했는지 확인한다.
   CI 가 시작된 뒤 main 이 바뀌었으면 `gh pr update-branch` 로 갱신하고 새 CI 가 끝날 때까지 기다린다.
   예외가 하나 있다. 아래를 모두 만족하면 직전 CI 결과로 머지하고 그 근거를 PR 에 한 줄 남긴다.
   여러 PR 이 함께 돌 때 main 이 바뀔 때마다 전체 CI 를 다시 돌리면 머지가 끝없이 밀리기 때문이다.
   - CI 가 돈 기준 커밋부터 지금 main 까지 바뀐 파일과 이 PR 이 고친 파일이 하나도 겹치지 않는다.
   - 양쪽 어디도 마이그레이션(`backend/src/main/resources/db/migration/`), 빌드 설정과 의존성 파일, 저장소 전체를 보는 `test/unit/` 검사, 여러 모듈이 함께 쓰는 계약 파일을 건드리지 않았다. 파일이 겹치지 않아도 함께 깨질 수 있는 곳이다.
   - `mergeable` 이 `MERGEABLE` 이고 `mergeStateStatus` 가 `CLEAN` 이다. 필수가 아닌 `claude-review` 만 실패해 `UNSTABLE` 이면 `CLEAN` 과 같이 본다. 그 밖의 `UNSTABLE` 이나 `UNKNOWN` 이면 예외를 쓰지 않는다.

   ```bash
   # cwd: 저장소 root. $CI_BASE 는 CI 가 돈 merge 커밋의 main 쪽 부모다.
   gh pr view "$PR" --json mergeable,mergeStateStatus
   comm -12 <(git diff --name-only "$CI_BASE" origin/main | sort) <(gh pr diff "$PR" --name-only | sort)
   ```
2. main 브랜치 보호가 정한 필수 검사의 통과를 직접 확인한다. 브랜치 보호의 `strict` 는 켜지 않는다.
3. 4단계를 충족하면 머지한다. 이력을 한 줄로 합치지 않는다.

```bash
# cwd: 저장소 root
gh pr checks "$PR" --required
gh pr merge "$PR" --merge
```

GitHub Actions 장애 등으로 CI 가 돌지 못하면 머지 전에 로컬에서 인자 없이 `scripts/dev/check-local.sh` 를 돌려 전체 통과를 확인한다.

## 6. 정리

머지한 뒤 바로 그 작업이 만든 워크트리, 로컬과 원격 브랜치, 로컬 검사가 남긴 임시 컨테이너를 지운다.
정리를 미루면 다음 작업자가 끝난 작업과 진행 중인 작업을 가리지 못한다.
무엇을 지우는지는 스크립트의 머리말이 갖는다. 지울 워크트리 안이 아니라 저장소 root 에서 돌린다.

```bash
# cwd: 저장소 root
BRANCH="$(gh pr view "$PR" --json headRefName -q .headRefName)"
scripts/dev/finish-task.sh "$BRANCH"
```

작업을 에이전트에 맡겼으면 그 회신을 받은 뒤 세션을 끝낸다. 스크립트는 세션을 끝내지 못한다.
