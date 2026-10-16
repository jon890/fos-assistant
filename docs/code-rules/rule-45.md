# rule-45

기준 파일은 기존 위반을 줄이는 목록으로만 쓴다.

검사: [기준 파일 관리](../../scripts/checks/quality.sh), [리뷰](../../.github/workflows/code-review-prompt.txt)

## 까닭

기존 위반을 허용 계약으로 굳히지 않고 새 위반을 막으면서 줄여 간다.

## 예외와 검토 범위

설계상 suppression과 기존 부채 baseline을 구별한다.
위반을 고치면 기준도 같은 커밋에서 줄이고 불가피하게 늘리면 까닭을 커밋과 PR에 적는다.

## 이 저장소의 사례

[code-architecture.md](../../backend/docs/code-architecture.md)에서 확인한다.
backend 아키텍처 문서는 freeze·Checkstyle 기준과 파일 길이 기준의 갱신 절차를 나눠 둔다.

## 화면 기준 파일 관리

지금 있는 error 위반은 `web/eslint-suppressions.json`(eslint 의 bulk suppressions)에 두고 새 위반만 실패시킨다.

**기준에 든 위반은 허용이 아니라 줄여 갈 목록이다.** 위반을 고치면 기준도 같은 커밋에서 줄인다.

| 언제 | 어떻게 |
| --- | --- |
| 기준에 든 위반을 고쳤다 | `pnpm lint` 가 「쓰지 않는 기준이 남았다」 며 2 로 끝난다. `pnpm exec eslint --prune-suppressions` 로 그 줄을 빼고 같은 커밋에 넣는다. `scripts/checks/quality.sh fix` 도 이 명령을 돌린다 |
| 새 위반이 생겼다 | 고친다 |
| 꼭 받아들여야 한다 | `pnpm exec eslint --suppress-rule <규칙> <파일>` 로 더한다.
까닭을 커밋 메시지와 PR 본문에 적는다 |
| 규칙을 새로 더했다 | `pnpm exec eslint --suppress-all` 로 기존 위반을 기준에 둔다.
그 규칙의 위반 수를 커밋 메시지에 적는다 |

책임과 실행 절차는 [code-architecture.md](../../backend/docs/code-architecture.md)에서 이어 읽는다.
