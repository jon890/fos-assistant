# rule-47

실행 profile은 요청자의 바인딩에서만 고른다.

검사: [요청자 바인딩](../../test/e2e/scenarios/binding.ts), [대화의 profile 유지](../../test/e2e/scenarios/chat.ts), [리뷰](../../.github/workflows/code-review-prompt.txt)

## 까닭

본문을 바꿔 남의 profile이나 credential로 실행할 수 없게 한다.

## 예외와 검토 범위

요청자의 바인딩에서 실행 대상을 정한다.
클라이언트가 준 사용자나 profile 값으로 권한을 대신하지 않는다.

## 이 저장소의 사례

[binding.ts](../../test/e2e/scenarios/binding.ts)에서 확인한다.
binding 시나리오는 없는 에이전트를 거절하고 chat 시나리오는 처음 선택한 profile을 유지하는지 확인한다.
