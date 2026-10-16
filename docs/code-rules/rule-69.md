# rule-69

화면 갱신은 입력 상태와 레이아웃을 불필요하게 다시 만들지 않는다.

검사: [입력 상태 회귀](../../test/browser/composer-send-flow.spec.ts), [리뷰](../../.github/workflows/code-review-prompt.txt)

## 까닭

상태 갱신 때 입력 상태가 사라지거나 레이아웃이 밀리는 일을 막는다.

## 예외와 검토 범위

마우스나 이력 갱신으로 요소가 늦게 생기는지, 부모 교체로 입력창이 unmount되어 첨부가 지워지는지 확인한다.

## 이 저장소의 사례

[composer-send-flow.spec.ts](../../test/browser/composer-send-flow.spec.ts)에서 확인한다.
composer-send-flow spec은 메시지 전송 중 입력과 첨부 상태의 흐름을 확인한다.
