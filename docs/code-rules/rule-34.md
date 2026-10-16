# rule-34

단순한 생성자 주입은 RequiredArgsConstructor를 쓴다.

검사: [requiredArgsConstructor](../../backend/config/checkstyle/checkstyle.xml), [리뷰](../../.github/workflows/code-review-prompt.txt)

## 까닭

의존 필드와 단순 주입 생성자를 중복해서 적지 않는다.

## 예외와 검토 범위

계산, HTTP 클라이언트 구성, 테스트 전용 생성자는 리뷰로 구별한다.
warning은 실패가 아니며 생성자를 무조건 지우지 않는다.

## 이 저장소의 사례

[HttpHermesRunsClient.java](../../backend/src/main/java/com/bifos/assistant/hermes/HttpHermesRunsClient.java)에서 확인한다.
HttpHermesRunsClient처럼 클라이언트를 구성하는 생성자는 계산 없는 단순 주입과 구별한다.
