## ADR-20261113 / purchase-checkout: 구매는 사용자 상한 안에서 매번 승인한 주문서까지만 채우고, 금융 승인 틀에 위험도 `PURCHASE` 로 얹는다

- **status**: `accepted`
- Date: 2026-11-13
- **decision-type**: `architecture`
- **areas**: `security, operations`
- **현재 규칙의 원본**: 스펙 [`docs/features/naver-shopping/spec.md`](../features/naver-shopping/spec.md)(사용자 확인 2026-11-10)의 DECISION8, DECISION9, DECISION10, 설계 [`design.md`](../features/naver-shopping/design.md) 의 「PR3 구매 상한과 주문서」, [플러그인 도구 정책](../features/connector-policy.md)
- [ADR-20261010 / financial-execution-guard](ADR-20261010-financial-execution-guard.md) 와 [ADR-20261013 / financial-order-opening](ADR-20261013-financial-order-opening.md) 의 준비·승인·ticket·claim 틀을 그대로 쓴다. [ADR-20261015 / financial-user-limits](ADR-20261015-financial-user-limits.md) 의 사용자 상한과 같은 잠금 원칙이다. [ADR-20261110 / naver-shopping-connector](ADR-20261110-naver-shopping-connector.md) 의 「읽기 도구는 누르지 않는다」 에 예외 하나를 둔다

### 결정

**에이전트는 사용자가 정한 구매 상한 안에서, 사람이 매번 승인한 주문서까지만 채운다. 결제 단추는 누르지 않는다.**

| 항목 | 정한 것 |
| --- | --- |
| 승인 카드 | 서버가 상품 화면을 다시 읽어 만든 상품 칸만 보인다. 상품명, 판매처, 옵션, 수량, 상품 금액, 배송비, 예상 합계, 오늘 남은 구매 한도, 상품 링크. 배송지와 결제 수단은 싣지 않는다. 상시 허락과 대화 허락이 없다(spec DECISION8) |
| 구매 상한 | 한 건(예상 합계)과 KST 하루(그날 승인한 예상 합계의 합) 둘, 원 단위, 기본 0. 0 이면 주문서를 채우지 않는다. 운영 최대치는 없다. `app_user` 의 컬럼 둘이고 사용자가 「외부 서비스 연결」 화면에서만 바꾼다(spec DECISION9, DECISION15, DECISION31) |
| 상한을 보는 때 | 카드를 만들 때, 승인하는 순간, 채우기 직전(claim), 결제 직전(주문서의 최종 결제 금액). 판정과 상한 변경은 같은 사용자 행 잠금으로 차례가 정해진다 |
| 결제 직전 | 최종 결제 금액이 승인한 예상 합계보다 1원이라도 크거나 그 순간의 상한을 넘으면 결제 화면을 넘기지 않는다. 같거나 작으면 넘긴다(spec DECISION10) |
| 하루 합계 | 결제 화면까지 간 승인과 결과를 모르는 승인을 센다. 결제 화면을 넘기지 못한 승인은 뺀다(spec DECISION17, DECISION30) |
| 결과 불명 | 다시 보내지 않고 그 승인을 끝낸 뒤 「내 브라우저」 의 탭을 확인해 달라는 넘기기를 띄운다(spec DECISION33) |
| 시작하는 실행 | 사람이 연 대화의 흐름만. 사람이 없는 실행이 준비 도구를 부르면 금융 준비로 넘기지 않고 `ATTENDED_ONLY` 로 거절한다 |

**승인과 실행은 금융 승인 틀(`execution_guard`, 준비, 승인, ticket, claim)에 주문서 종류 `CHECKOUT` 을 더해 쓰고, 채우기 도구는 새 위험도 `PURCHASE` 로 둔다.**

| 항목 | 정한 것 |
| --- | --- |
| `PURCHASE` | 승인 방식 하한은 `always`. 직접 호출은 `RISK_NOT_OPEN`, 상시 허락과 대화 허락이 없고 모델 도구 목록에서 뺀다. 보호 선언의 `operations` 에 든 도구만 연다. `FINANCIAL` 과 같은 닫힘이고 위험도만 다르다(사용자 결정 2026-11-13) |
| 개방 조건 | 플러그인을 붙이고, 사용자가 두 상한을 0 보다 크게 정하고, 금융 관문의 keyed hash key 를 읽을 수 있으면 열린다. 계좌 허용 목록과 운영 스위치는 두지 않는다(사용자 결정 2026-11-13) |
| 닫는 법 | 사용자가 상한을 0 으로 두거나 바인딩을 뗀다. 운영에서는 플러그인 선언을 내린다 |

**읽기 도구가 옵션 드롭다운을 여는 누르기 하나는 허용한다.** 옵션이 있는 상품은 드롭다운을 눌러야 옵션 목록이 실린다(2026-11-13 실측). 열기만 하고 값을 고르거나 구매하기·장바구니를 누르지 않는다. 닫는 식은 두지 않고 읽기 탭째 닫는다(사용자 결정 2026-11-13).

### 맥락

네이버 쇼핑에는 구매 API 가 없어 「내 브라우저」 화면으로 주문서까지 가야 한다(spec DECISION2). 돈이 걸린 단계라 승인과 상한, 결제 직전의 재확인이 필요하다.
토스 매매가 이미 서버가 만든 요약 카드, 승인 때와 실행 직전의 다시 판정, ticket 단일 소비, 결과 불명의 `UNKNOWN` 을 갖고 있다.
그 틀의 금융 하루 합계(`FinancialOrderLimits.spentToday`)와 연결 화면의 주문 상한 칸은 위험도 `FINANCIAL` 로 줄을 고른다.

### 대안 기각

- **채우기 도구를 `FINANCIAL` 로 둔다**: 위험도 목록은 그대로지만 하루 합계, 상한 칸, 계좌 허용 목록 검사마다 쇼핑을 가르는 분기가 들어가고, 하나라도 빠뜨리면 쇼핑 금액이 토스 원화 하루 합계에 섞인다
- **승인과 실행의 새 길을 따로 만든다**: 준비 결과 검증, 암호화 snapshot, ticket, claim, 결과 불명 처리를 두 벌 갖게 된다
- **일반 승인 줄(Gmail 길)로 실행한다**: 카드가 모델이 보낸 인자를 보여 서버가 만든 요약이 아니고, 승인 뒤 실행 직전의 상한 판정이 없다
- **운영 스위치나 사용자 허용 목록을 더 둔다**: 결제는 사람이 하고 기본 0 이 이미 닫힌 상태라 이중 잠금의 이득이 작다(spec DECISION31 과 같은 근거)
- **플러그인이 결제 직전 상한까지 판정한다**: 그 순간의 상한과 오늘 합계는 Control Plane 만 안다. 플러그인은 최종 금액을 내고 Control Plane 이 견준다
- **옵션 드롭다운을 연 뒤 닫는 식을 둔다**: 누르기 식이 둘이 된다. 읽기 탭은 우리가 연 탭이라 정상으로 끝나면 탭째 닫는다

### 결과

- 얻는 것:
  - 사용자가 정한 금액 밖의 주문서는 열리지 않고, 승인보다 많이 내는 결제 화면은 사람에게 넘어가지 않는다
  - 토스 매매의 합계와 상한 칸, 코드 경로가 바뀌지 않는다
  - 뒤의 구매·예매 플러그인(KTX 등)이 같은 위험도와 종류로 얹힌다
- 감당할 것:
  - 결과를 모르는 승인은 하루 합계에 들어가 실제로 안 산 금액만큼 한도가 줄 수 있다
  - 승인 뒤 상한을 낮춘 드문 경우 결제 직전에 멈춰도 주문서 탭이 「내 브라우저」 에 남는다
  - 주문서 화면의 칸 위치는 아직 재지 않았다. 읽지 못하면 결과 불명으로 끝난다
  - 옵션 드롭다운 열기는 네이버 계정에 남는 기록이 아니지만 읽기 도구의 첫 누르기다. 상수 식 하나와 검사로 범위를 묶는다
- **확인 방법**: `ToolPolicyDecisionTest`, `AttendedOnlyPrepareTest`, `PurchaseLimitSettingsTest`, `PurchaseLimitControllerTest`, 대시보드 선언 검사(`hermes/tests/test_connector_manifest.py`, `hermes/tests/test_connectors_contract.py`), 웹 `test/node/purchase-limits-route.test.ts`. 주문서 종류와 상한 판정, 결과 판정은 `CheckoutApprovalTest`, `ConnectorCheckoutSnapshotTest`, `hermes/tests/test_connector_checkout_validation.py`(Java 와 같은 사례 표 `test/resources/checkout-snapshot-v1.json`)가 본다. 플러그인 도구의 검사는 그 구현 PR 이 더한다
