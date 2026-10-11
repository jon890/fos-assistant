# Phase 01. 일반 금융 모듈과 단일전송 경계

**Execution profile**: deep

## 목표

일반 주문 CREATE·MODIFY·CANCEL의 준비와 실행 보호를 실제 모듈로 만들고 mock 목적지에서 검증한다.

**범위 외**: 조건주문, 운영 도구 등록·금융 manifest 선언·exclude 해제, 실제 거래와 공개 승인 경로 연결이다.

## 컨텍스트

**근거 문서**: `docs/features/connector.md`의 「공식 operation과 producer」와 「일반 금융 모듈의 구현 경계 보완안」, `docs/features/connector-policy.md`의 「표시 값과 실행 인자」다.
CURRENT_OPERATIONS와 server.ts는 현재 인증 및 7개 READ operation만 연결한다. 계약 생성기의 operation 존재와 실제 producer 등록을 구분한다.
getStocks·getAccounts·getOrders·getBuyingPower·getSellableQuantity의 transport는 재사용한다.
getOrder와 getCommissions 및 조건 조회 producer는 현재 main에 없다. 다른 READ 워커의 모듈은 읽기만 한다.

## Blocked 조건

437 실제 main 통합, 독립 계획 검토 승인, 기본 verify_task 0과 소유권 확인 뒤 착수한다.
현재 scope의 getOrder 내부 조회는 기존 operationFor/parseApiResponse를 사용해 신규 prepare 모듈이 직접 GET을 호출한다.
get_order 공개 READ 등록이나 없는 register 함수는 선행 조건으로 삼지 않는다. 나중에 READ producer가 main에 있으면 해당 구현과 중복 없이 통합한다.
getCommissions는 이 모듈의 필수 입력이 아니다. 수수료를 추정해 summary의 새로운 필드로 넣지 않는다.

## 작업 항목

### 1. 공식 입력·본문·응답 대조

fixture OpenAPI 1.2.24의 실제 parameter/body/response/oneOf를 테스트가 직접 읽는다.
createOrder는 POST /api/v1/orders, modifyOrder는 POST /api/v1/orders/{orderId}/modify, cancelOrder는 POST /api/v1/orders/{orderId}/cancel이다.
cancelOrder 본문은 공식 예시 {}다. 일반 주문 DELETE는 0회이며 조건주문 DELETE를 일반 cancel로 발명하지 않는다.
OrderCreateRequest.oneOf는 별도 component가 아닌 inline quantity/amount 분기다. 금액 분기는 US MARKET이며 timeInForce·price가 없다.
OrderModifyRequest는 orderType 필수, KR quantity 필수 양의 정수, US quantity 전달 금지다. 정정·취소에 clientOrderId를 넣지 않는다.
공식 OrderResponse와 OrderOperationResponse는 result.orderId를 요구한다. 정정·취소의 새 ID를 보존한다.
CREATE에서 clientOrderId 응답은 공식 schema상 선택/nullable이므로 누락·null을 무조건 불량2xx로 오판하지 않는다. 값이 있으면 보낸 UUID와 일치해야 한다.
scope_fields는 `{arg:account_seq,field:account}`다. 기존 연결 공개 칸 account와 TOSSINVEST_ACCOUNT_SEQ를 문자열 그대로 대조한다.
공식 X-Tossinvest-Account 헤더의 int64를 Number로 바꾸지 않는다.
body builder는 공식 branch별 필드만 고른다. scope·market·currency·expected_order는 body에서 빠지고 금액 분기 timeInForce도 빠진다.
confirmHighValueOrder는 입력 false만 허용하고 실행 args에는 남기지 않는다. 외부 body에서도 true를 만들지 않는다.

### 2. prepare와 실제 실행 원문

prepare-trade.ts는 기존 client GET으로 계좌 끝 네 자리, 종목 market·currency, 원래 주문을 읽는다.
가짜 symbol을 운영 기본값으로 만들지 않는다. KR/US 판단은 공식 StockInfo.market과 currency 및 공식 시장 대응을 사용한다.
KOSPI·KOSDAQ·KR_ETC는 KR/KRW, NYSE·NASDAQ·AMEX·US_ETC는 US/USD로 대조한다. 모르는 market이나 통화 불일치는 거절한다.
수량·금액·가격은 양의 1~30자 decimal 문자열이다. 숫자·지수·공백·부호·0·quantity/orderAmount 동시 입력은 거절한다.
소수 수량은 US MARKET SELL에서 최대 6자리다. KR LIMIT/MARKET DAY·OPG, US LIMIT DAY·CLS, US MARKET DAY만 허용한다.
US 가격은 1달러 미만 4자리, 1달러 이상 2자리를 넘으면 prepare에서 거절한다. 절삭하지 않는다.
기본 DAY 보충과 허용된 문자열 표기 정규화는 normalization에 기록한다. summary·expected_order는 기존 Java/Python 소비자가 요구하는 정확한 키와 타입이다.
CREATE clientOrderId는 prepare에서 UUID를 한 번 만든다. 모델 예약 키를 거절하고 실행 원문을 승인 이후 다시 만들지 않는다.
MODIFY/CANCEL은 PENDING·PARTIAL_FILLED만 준비한다. 실행 전 재조회가 expected_order와 filledQuantity까지 다르면 금융 전송 0회다.
claim 뒤 조회와 token 확보 시간이 지나도 전송 직전에 ticket 만료를 다시 확인한다.
execution-guard.ts는 FOS_APPROVAL_ARGS_JSON의 원문 SHA와 실제 tools/call 인자의 모든 키·값·타입을 coercion·strip·default 전에 대조한다.
SDK 등록 schema에도 변환·기본값을 두지 않는다. 실행 메타데이터를 strict schema에 정확히 선언한다.
env 계좌 일치 후 기존 claim 본문 ticket/tool/argsSha256/scope로 한 번만 묻는다. 응답 v/allowed/ticketId/expiresAt을 검증한다.
claim redirect·재시도·재사용은 금지한다. child의 FOS_APPROVAL_*는 종료·예외에도 폐기하고 원문은 로그·결과에 싣지 않는다.

### 3. 단일전송과 결과

client.ts에 POST/DELETE 전용 호출을 구현하되 GET request의 401·429 재시도 루프를 공유하지 않는다.
현재 토큰 identity·quota queue·bounded response를 재사용하고 전송 개시 여부를 내부에서 추적한다. 새 wire 필드를 만들지 않는다.
quota 대기·입력·토큰 발급 등 명백한 금융 전송 전 실패는 FAILED다. fetch가 시작된 뒤 네트워크 예외는 UNKNOWN이다.
공식 ErrorResponse와 해당 operation의 명백한 거절 코드가 일치한 400/401/404/422, already-filled/canceled/modified/rejected 등은 FAILED다.
409 request-in-progress·already-processing과 알려지지 않은 오류, 429·5xx·timeout·본문 읽기 실패·불량2xx는 보수적 UNKNOWN이다.
TOSSINVEST_OUTCOME_UNKNOWN을 connector.json의 오류 선언 outcome_unknown에 잇는다. 기존 Python 504와 Java ConnectorExecutionUnknown을 재사용한다.
UNKNOWN 이후 token 재발급·새 clientOrderId 생성으로 주문을 자동 재전송하지 않는다.

### 4. 등록 경계와 테스트

검사 대상은 `hermes/connectors/tossinvest/tests/trading.test.ts`와 `hermes/connectors/tossinvest/tests/execution-guard.test.ts`다.
trading-tools.ts는 실제 등록 함수를 제공하지만 운영 server.ts의 createTossinvestServer는 아직 호출하지 않는다.
운영 connector.json에는 FINANCIAL 도구나 execution_guard를 추가하지 않는다. CLOSED_RISKS 검사와 READ 목록을 유지한다.
테스트 fixture entry만 기존 등록 함수로 실제 prepare/거래 모듈을 SDK에 등록한다. 새 정책이나 검증용 HTTP API를 생산 코드에 넣지 않는다.
테스트가 Bun.build로 실제 모듈과 SDK를 bundle하여 stdio로 실행한다. 운영 dist에 금융 도구가 있다는 증거로 보고하지 않는다.
목적지와 claim 서버는 loopback만 허용하며 실제 credentials를 사용하지 않는다. egress 검사가 실제 주소 요청을 실패시킨다.
trading.test.ts·execution-guard.test.ts는 원문 변조·추가 키·숫자 decimal·env 계좌 변경·replay·만료에서 금융 POST/DELETE 0회를 센다.
정상 mock CREATE/MODIFY/CANCEL은 각각 금융 POST 1회이며 body·header·path와 공식 응답 ID를 단언한다.
429/5xx/timeout/불량2xx는 UNKNOWN과 금융 재전송 0회를 단언한다. auth POST·준비 GET·금융 POST/DELETE·승인 HTTP를 서로 다른 counter로 기록한다.
DELETE transport는 조건주문을 등록하지 않고 테스트가 단일전송과 body 없는 204/불량204 분류를 검사한다.
기존 READ 회귀와 공식 oneOf 분기, US 가격 경계, CREATE optional clientOrderId 응답도 검사한다.

## 검증

```bash
cd hermes/connectors/tossinvest && bun run typecheck
cd hermes/connectors/tossinvest && bun run build
cd hermes/connectors/tossinvest && bun run check:bundle
cd hermes/connectors/tossinvest && bun test ./tests
node scripts/check-file-length.mjs
PYTHONPATH=hermes/tests python3 -m unittest test_connectors_contract
```

종료 코드 0, 신설 테스트의 실행 건수와 테스트 bundle의 SDK 호출을 확인한다.
공식 JSON은 구현 직전 다시 공개 GET으로 읽고 version·원문 SHA·method/path·schema를 fixture와 대조한다. 다른 버전이면 fixture 변경을 공통 integrator의 별도 관심사로 보고한다.

## 변경 파일

| 파일 | 변경 |
| --- | --- |
| `hermes/connectors/tossinvest/src/prepare-trade.ts` | 신규 |
| `hermes/connectors/tossinvest/src/trading-tools.ts` | 신규 |
| `hermes/connectors/tossinvest/src/execution-guard.ts` | 신규 |
| `hermes/connectors/tossinvest/src/trading-body.ts` | 신규 |
| `hermes/connectors/tossinvest/src/client.ts` | 수정 |
| `hermes/connectors/tossinvest/connector.json` | 수정 |
| `hermes/connectors/tossinvest/tests/trading.test.ts` | 신규 |
| `hermes/connectors/tossinvest/tests/execution-guard.test.ts` | 신규 |
| `hermes/connectors/tossinvest/tests/fixtures/trading-entry.ts` | 신규 |
| `docs/features/connector.md` | 수정 |

## 예산과 완료

운영 코드 변경 목표는 1100줄이며 PR 상한은 1500줄이다. 편집 전에 예산을 계산하고 넘으면 transport·guard와 prepare·일반 주문으로 나눈다.
각 신규 TS 파일은 저장소 길이 검사 상한을 지키고 기존 baseline은 늘리지 않는다. 공용 client와 manifest는 한 integrator만 쓴다.
모듈·테스트와 해당 계약을 같은 PR에 담는다. 운영 등록과 공개 금융 경로 활성화는 별도 결정 및 보안 리뷰 전까지 보류한다.
