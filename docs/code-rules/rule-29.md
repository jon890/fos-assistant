# rule-29

Hermes timeout 설정은 허용된 경로로만 읽는다.

검사: [HERMES_TIMEOUTS_ONLY_THROUGH_LIVE_PROPERTIES](../../backend/src/test/java/com/bifos/assistant/architecture/ArchitectureRules.java)

## 까닭

실행 중 timeout 변경이 오래된 설정 참조 때문에 무시되지 않게 한다.

## 예외와 검토 범위

실제 HTTP 클라이언트 두 타입과 기동 비교는 한 번 읽는 경로를 허용한다.
정확한 타입은 검사 코드가 갖는다.

## 이 저장소의 사례

[HttpHermesRunsClient.java](../../backend/src/main/java/com/bifos/assistant/hermes/HttpHermesRunsClient.java)에서 확인한다.
HttpHermesRunsClient는 HTTP 클라이언트를 구성할 때 timeout을 읽는 허용 경로다.
