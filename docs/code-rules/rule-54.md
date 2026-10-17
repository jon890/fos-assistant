# rule-54

서버 HTTP 클라이언트는 timeout을 정하고 테스트 설정 파일 이름을 분리한다.

검사: [리뷰](../../.github/workflows/code-review-prompt.txt)

## 까닭

기본 구성이 없는 HTTP 클라이언트나 테스트 설정이 실제 실행을 멈추지 않게 한다.

## 예외와 검토 범위

RestClient.Builder는 자동 구성되지 않으므로 직접 만들고 timeout을 준다.
테스트 설정을 application.yml로 두면 smokeRun이 실제 설정 대신 읽는다.

## 이 저장소의 사례

[HttpHermesRunsClient.java](../../backend/src/main/java/com/bifos/assistant/hermes/infra/HttpHermesRunsClient.java)에서 확인한다.
HttpHermesRunsClient는 직접 만든 HTTP 클라이언트의 timeout을 설정한다.
