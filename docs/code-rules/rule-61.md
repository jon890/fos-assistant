# rule-61

수가 정해지지 않은 목록과 비용 큰 탭은 자동 prefetch를 끈다.

검사: [대화 요청 수](../../test/browser/conversation-requests.spec.ts), [사용량 탭](../../test/browser/usage.spec.ts), [리뷰](../../.github/workflows/code-review-prompt.txt)

## 까닭

보이는 링크 수만큼 요청하거나 선택하지 않은 탭의 집계를 미리 읽지 않게 한다.

## 예외와 검토 범위

줄 수가 정해지지 않은 목록과 사용량 탭은 prefetch를 끈다.
선택 주소와 표시가 함께 바뀌는지도 확인한다.

## 이 저장소의 사례

[conversation-requests.spec.ts](../../test/browser/conversation-requests.spec.ts)에서 확인한다.
conversation-requests spec은 대화가 많은 사이드바의 미리 읽기 요청을 센다.
