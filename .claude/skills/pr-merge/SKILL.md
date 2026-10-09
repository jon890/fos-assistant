---
name: pr-merge
description: |
  이 저장소에서 Draft PR 을 열고 Ready 로 바꾸고, Claude 리뷰를 반영해 머지하기까지의 순서다.
  PR 을 열 때, Draft CI 가 끝나 Ready 로 바꿀 때, 리뷰 등급을 보고 머지할지 정할 때 읽는다.
---

# pr-merge

**목표: PR 이 지금 main 과 합친 상태에서 필수 검사를 모두 통과했고, Claude 리뷰를 한 번 반영한 뒤 머지된다.**

워커의 보고를 읽는 것은 확인이 아니다. CI 결과와 브랜치 보호는 직접 조회한다.

## 실행 절차

| 단계 | 이름 | 통과 조건 | reference |
| --- | --- | --- | --- |
| 1 | Draft PR 열기 | `scripts/check-local.sh` 가 0 으로 끝났고 `gh pr create --draft` 가 PR 주소를 냈다 | |
| 2 | Draft CI | `gh pr checks` 의 모든 검사가 통과다 | |
| 3 | main 합치고 Ready | 최신 main 을 합친 커밋의 CI 가 모두 통과했고 `gh pr ready` 가 끝났다 | |
| 4 | 리뷰 반영 | 남은 🔴 P1 과 🟠 P2 를 한 번 고쳐 push 했거나 P2 를 고치지 않는 까닭을 PR 에 남겼다 | [`code-review-prompt.txt`](../../../.github/workflows/code-review-prompt.txt) |
| 5 | 머지 판정 | 지금 main 과 합친 상태에서 돈 CI 가 필수 검사를 모두 통과했다 | |

아래 명령의 `$PR` 은 PR 번호, `$SPEC` 은 고친 화면과 그 컴포넌트를 쓰는 화면의 spec 이름이다.

## 1. Draft PR 열기

push 전에 로컬 검사를 돌리고 Draft 로 연다.
Draft 에서는 브라우저 전체 shard 를 포함한 CI 가 돌고 Claude 리뷰는 돌지 않는다.

```bash
# cwd: 저장소 root
scripts/check-local.sh "$SPEC"
git push -u origin HEAD
gh pr create --draft --title "$TITLE" --body-file "$BODY_FILE"
```

## 2. Draft CI

```bash
# cwd: 저장소 root
gh pr checks "$PR" --watch
```

실패하면 이 PR 에서 고치고 다시 기다린다. 다른 화면의 시험이 깨졌어도 이 PR 에서 고친다.

## 3. main 합치고 Ready

Draft CI 가 전체 통과하면 Ready 직전에 최신 main 을 작업 브랜치에 merge 한다.
rebase 와 force push 로 이력을 바꾸지 않는다.
합친 뒤 관련 spec 을 포함해 다시 검사하고 push 한다. 코디네이터가 충돌을 직접 풀었을 때도 같은 검사를 거친다.

```bash
# cwd: 저장소 root
git fetch origin
git merge origin/main
scripts/check-local.sh "$SPEC"
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
2. main 브랜치 보호가 정한 필수 검사의 통과를 직접 확인한다. 브랜치 보호의 `strict` 는 켜지 않는다.
3. 4단계를 충족하면 머지한다. 이력을 한 줄로 합치지 않는다.

```bash
# cwd: 저장소 root
gh pr checks "$PR" --required
gh pr merge "$PR" --merge
```

GitHub Actions 장애 등으로 CI 가 돌지 못하면 머지 전에 로컬에서 인자 없이 `scripts/check-local.sh` 를 돌려 전체 통과를 확인한다.
