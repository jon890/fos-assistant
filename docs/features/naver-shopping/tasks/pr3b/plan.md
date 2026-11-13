# PR3b 주문서 종류와 상한 판정, 결과 판정, 결제·탭 확인 넘기기

설계: [`design.md`](../../design.md) 의 「PR3 구매 상한과 주문서」 가운데 PR3b 행. 스펙 REQ10~REQ12 의 서버 쪽이다.
이 계획서는 구현 PR 에서 지운다.

## 작업

| 단계 | 담는 것 | 끝난 조건 |
| --- | --- | --- |
| 1 보호 선언 | `ConnectorExecutionGuard` 와 대시보드 `connector_guard.py` 가 `CHECKOUT` 종류를 받는다. 그 도구는 `PURCHASE`/`always`, 모든 도구가 `CHECKOUT` 이면 `scope_fields` 는 빈 배열, 두 위험도를 섞지 않는다 | Java 와 Python 의 선언 검사 사례 |
| 2 준비 결과 | `ConnectorCheckoutSnapshot`(Java)과 `_prepare_result` 의 `CHECKOUT` 갈래(Python)가 같은 사례 표로 `executionArgs`·`summary`·원래 인자를 대조한다. 요청 키는 url, 옵션, 수량 | 같은 사례 표가 두 쪽에서 통과·거절 |
| 3 상한 판정 | `PurchaseLimits.requireWithin`, 하루 합계(`PURCHASE` 줄의 `expectedTotalKrw`). 준비(카드 만들 때), 승인 순간(`FAILED`/`PURCHASE_LIMIT`), claim(409), 결과 판정 | 네 때의 경계값(같으면 지남, 1원 넘으면 걸림, 0 이면 「상한을 정해 주세요」) |
| 4 FINANCIAL 분기 넓히기 | 설계의 「PR3b 가 넓혀야 할」 표의 자리. 토스 흐름은 그대로 | 토스 회귀 테스트 통과 |
| 5 결과 판정 | `ConnectorCheckoutOutcome`. 같거나 작음 → `SUCCEEDED`+`payment`, 1원 큼 → `FAILED`, 상한 → `FAILED`, 모름 → `UNKNOWN`+`tab_check`, claim 전 사람 화면 → `FAILED`+그 넘기기 | 표의 경우마다 테스트 |
| 6 넘기기 어휘 | `HandoffNeed` 와 웹 `handoff.ts` 에 `PAYMENT`, `TAB_CHECK`. 플러그인 알림으로는 받지 않는다. 목록 API 가 `payment` 에 두 금액을 붙이고 `tab_check` 는 탭이 비어도 된다. 카드가 두 금액을 보인다 | 백엔드·웹 단위 테스트 |

웹의 주문서 승인 카드(`financial-summary.tsx` 쪽)는 PR3c 다. 이 PR 의 목록 API 는 `CHECKOUT` 요약을 붙이지만 화면은 아직 그리지 않는다.

## 검증

- `./gradlew test --tests` 로 바꾼 자리의 테스트와 `FinancialOrderOpeningTest` 등 토스 회귀
- `python3 -m pytest hermes/tests` 의 보호 선언·준비 결과 사례
- 웹 `handoff` 단위 테스트
- `scripts/dev/check-local.sh --fast`
