# rule-12

orchestration은 ChatService에 의존하지 않는다.

검사: [ORCHESTRATION_DOES_NOT_CALL_CHAT_SERVICE](../../backend/src/test/java/com/bifos/assistant/architecture/ArchitectureRules.java)

## 까닭

대화 처리와 실행 제어가 서로를 호출하는 순환을 막는다.

## 예외와 검토 범위

중지의 상태 전이와 결과 보존은 chat 기능 문서가 갖는다.

## 이 저장소의 사례

[chat.md](../../docs/features/chat.md)에서 확인한다.
중지 흐름은 실행 제어와 대화 기록의 책임을 분리한다.
