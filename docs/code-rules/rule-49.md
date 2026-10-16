# rule-49

남의 자료는 없는 것과 같은 응답을 주며 실패 실행도 남긴다.

검사: [남의 대화 접근 거절](../../test/e2e/scenarios/chat.ts), [리뷰](../../.github/workflows/code-review-prompt.txt)

## 까닭

남의 자료가 있다는 사실을 감추고 실패한 실행도 추적할 수 있게 한다.

## 예외와 검토 범위

대화·실행·기억·첨부의 구체 응답은 각 기능 문서가 갖는다.
실행 시작 기록을 만들고 종료 상태를 갱신한다.

## 이 저장소의 사례

[ExecutionRecorder.java](../../backend/src/main/java/com/bifos/assistant/usage/application/ExecutionRecorder.java)에서 확인한다.
ExecutionRecorder는 실행을 시작할 때 만든 기록을 종료 때 갱신한다.
