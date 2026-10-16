# rule-68

동시 변경은 조건부 갱신으로 보호하고 오래된 엔티티를 통째로 덮어쓰지 않는다.

검사: [위임 경합 회귀](../../backend/src/test/java/com/bifos/assistant/orchestration/AgentDelegationServiceRaceTest.java), [리뷰](../../.github/workflows/code-review-prompt.txt)

## 까닭

다른 요청이 갱신한 삭제 표시나 이름을 오래된 엔티티가 되돌리지 않게 한다.

## 예외와 검토 범위

경합의 승패는 조건부 갱신 결과로 정한다.
중지와 제출처럼 실행 하나를 공유하는 요청은 가능한 순서를 나눠 재현한다.

## 이 저장소의 사례

[chat.md](../../docs/features/chat.md)에서 확인한다.
chat 기능 문서는 중지와 제출의 경합에서 실행 상태를 보존하는 흐름을 설명한다.
