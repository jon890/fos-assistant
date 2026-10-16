# rule-47

실행 profile은 요청자의 바인딩에서만 고른다.

검사: [인증 회귀](../../test/e2e/scenarios/auth.ts), [리뷰](../../.github/workflows/code-review-prompt.txt)

## 까닭

본문을 바꿔 남의 profile이나 credential로 실행할 수 없게 한다.

## 예외와 검토 범위

요청자의 바인딩에서 실행 대상을 정한다.
클라이언트가 준 사용자나 profile 값으로 권한을 대신하지 않는다.

## 이 저장소의 사례

[auth.ts](../../test/e2e/scenarios/auth.ts)에서 확인한다.
auth 시나리오는 인증과 사용자 접근 경계의 회귀를 확인한다.
