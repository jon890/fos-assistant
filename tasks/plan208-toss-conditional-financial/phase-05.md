# Phase 05. 조건주문 SDK 준비와 단일 금융 전송

**Execution profile**: deep

## 목표

조건 CREATE/MODIFY/CANCEL을 실제 SDK와 Python 소비자에 연결해 loopback 수신으로 단일전송을 증명한다.

**범위 외**: 실제 Spring DB producer, 운영 허가·등록·공개 승인·실계좌·실토큰·거래·배포·WS다.

## 컨텍스트

**근거 문서**: `docs/features/tossinvest-connector.md`의 「조건주문 REST 쓰기의 구현 보완안」이다.
일반 금융 모듈은 #454(merge 38eed128f)로 main에 들어왔다. prepareTrade/tradingBody/registerTradingTools/claimApproval/Tossinvest.financial을 main에서 직접 읽는다.
2026-10-13 기준 main에는 금융 SDK 모듈과 조건 조회, #481 producer, #507 승인 본문 암호화가 모두 있다. 운영 서버는 여전히 금융 도구를 등록하지 않는다.
조건 조회 #452(merge 03e8fcab)의 conditional-read-tools.ts와 official-response.ts는 기존 schema/ID 검사/C0·C1·Cs/공식 필드 투영의 재사용 근거다.
일반 금융 financialOperation은 조건 CREATE/MODIFY를 차단하고 financialResult는 result.orderId를 요구한다. 조건 operation descriptor와 응답 분기를 반드시 추가한다.
일반 주문이 가진 DELETE 빈204 지원만으로 조건주문 전체가 구현되었다고 보고하지 않는다.

## Blocked 조건

- 조건 snapshot 소비 계약(이 계획의 1단계 PR)과 phase-04 의 일반 주문 개방 연결이 main 에 있어야 한다. 조건주문은 첫 실제 호출 경로가 아니므로 phase-04 뒤에 한다.
- 2026-10-13 확인: #454, #452, #481, #507 이 모두 MERGED 다. 착수 때 main 에서 심볼을 다시 확인한다. 운영 금융 허가는 이 관심사의 선행 조건이 아니다.
- 합성 claim과 목적지 loopback, Python MCP 라이브러리·Bun 실행 환경이 없으면 PHASE_BLOCKED다. skip을 성공으로 세지 않는다.
- client의 현재 독점 소유자를 확인하고 별도 구현 dispatch를 받은 뒤 시작한다.

## 의도 메모

- 기존 금융 guard·transport를 재사용한다. 일반 주문 body에 조건 leg를 섞거나 범용 JSON producer를 만들지 않는다.
- SDK 도구 등록은 테스트 entry만 한다. 운영 createTossinvestServer와 connector.json의 금융 목록은 0개로 둔다.
- 이 관심사를 설계·구현·회귀 한 PR로 끝낸다. 아래 배분은 운영 추가+삭제982줄, 여유518줄이며 상한1500줄과 TS/Python400줄을 지킨다. client는 고정 source394줄이라 책임 이동을 먼저 수행한다.

## 작업 항목

### 1. 실제 SDK 테스트를 먼저 실패시킨다

`conditional-trading.test.ts`는 fixture OpenAPI의 세 operation과 schemas를 직접 읽어 method/path/body/response를 단언한다.
실제 MCP SDK Client로 prepare_conditional_trade와 세 쓰기를 호출한다. 등록 누락과 일반 result.orderId 검사로 실패하는 red를 먼저 기록한다.
공식 fixture SHA·버전·41개 coverage는 기존 rest-coverage.test.ts와 함께 확인한다. 새 등록 때문에 다른 READ 목록을 7개로 되돌리지 않는다.

### 2. 조건 body와 prepare

신규 conditional-body.ts에 CONDITIONAL_MODEL_SCHEMAS/CONDITIONAL_EXECUTION_SCHEMAS/conditionalBody를 둔다.
문서의 정확한 입력·원문·summary 계약을 구현하고 일반 주문 tradingBody를 우회하지 않는다. false 동의는 body builder가 명시하며 true는 거절한다.
신규 prepare-conditional.ts에 prepareConditionalTrade와 readExpectedConditionalOrder를 둔다.
고정 조건 조회의 orderSegment(id:string):string을 export하여 재사용한다. conditional-read-tools.ts의 수정·회귀·예산은 아래 선행 통합 후 수정 표에 확정했다. 공식 응답 검증 뒤 officialFields로 투영하며 null/부재를 보존한다.
계좌·종목·현재 원주문과 OCO 현재가를 읽는다. decimal은 기존 canonicalDecimal과 정확한 비교를 사용하며 Number 변환·가격 절삭을 하지 않는다.
prepare에서만 UUID·decimal 표기를 만든다. normalization의 nested field 경로와 원래 값 대조, 시장·단위·유효기간·STOP/PROFIT_RATE 경계는 문서대로 따른다.
prepare 결과를 test vector와 대조하고 env가 준비 중 바뀌면 거절한다. 신규 PROFIT_RATE 생성은 없지만 PROFIT_RATE 원주문→새 STOP 전체 재설정 MODIFY는 지원한다.
새 prepare는 공식 상세의 모든 group/leg enum을 받으며 OTO first=ORDERED/COMPLETED·second=WATCHING CANCEL을 차단하지 않는다. KR quantity=0.5도 계약 vector로 수용한다. 실서버 성공 보증으로 보고하지 않는다.
CASE-PREPARE-BUDGET의 SDK 테스트는 cold auth/429 지연과1 MiB 초과 응답에서 금융0을 확인한다. 실제 Spring prepare read timeout2초 실패와 저장0은 phase-06의 실제 producer 테스트에서 확인한다. READ의 최대3전송/4초와 응답 크기 상한을 모든 준비의2초 내 성공 보장과 구분한다.

### 3. guard와 단일전송 연결

신규 conditional-tools.ts의 registerConditionalTradingTools는 prepare_conditional_trade와 create_conditional_order/modify_conditional_order/cancel_conditional_order를 등록한다.
takeApproval/claimApproval을 재사용하고 원문·SDK args를 변환 전에 대조한다. claimApproval은 기존 callback 반환을 ClaimedApproval로 바꾸고 trading-tools의 기존 호출부도 함께 수정한다. SDK schema는 strict이고 coercion/default를 두지 않는다.
client.financial의 기존 토큰 identity·quota·started 추적을 재사용한다. 금융 orchestration 책임을 신규 financial-transport.ts로 이동하여 client의 파일 상한을 지킨다.
`executeFinancial(context,method,path,body,authorization):Promise<any>`의 context는 `{timeoutMs,account,getToken(deadlineMs),exchange(operation,path,init,deadlineMs,beforeSend)}`다. Identity는 client 안에 남고 client wrapper가 acquire/release의 try/finally를 소유한다.
조건의 `conditionalOperation(method,path):Operation`, `conditionalResult(operationId,response):any`와 allowlist는 신규 conditional-result.ts에 둔다. financial-transport는 조건 세 경로만 이 분기로 보내고 나머지는 기존 financialOperation/financialResult로 보낸다.
conditionalBody(tool,input):Args와 prepareConditionalTrade(client,tool,raw):Promise<prepare 외피>, readExpectedConditionalOrder(client,id):Promise<공식 투영 상세>를 정의한다. registerConditionalTradingTools(register,client):void는 테스트 entry만 호출한다.
일반 financialOperation/financialResult는 기존 일반 주문 경로에서 그대로 호출한다. 조건 success는 result.conditionalOrderId를 검사하고 MODIFY 새 ID·CREATE optional clientOrderId를 대조한다.
CANCEL은 DELETE·본문 없음·빈204만 성공이다. 결과는 승인 대상 conditionalOrderId와 취소 완료 표시를 돌려주되 공식 JSON 응답이 있었다고 표시하지 않는다.
claim 뒤 현재 원주문·OCO 현재가를 다시 조회한다. 원문의 키/타입/값 변화는 전송0회다. 새 prepare에 이미 있던 terminal leg만으로 거절하지 않는다.
execution-guard.ts의 `ClaimedApproval=Readonly<{beforeSend:()=>void;deadlineMs:number}>`와 `claimApproval(...):Promise<ClaimedApproval>`을 정의한다. ticket과 응답 expiresAt를 기존 검사로 한 번 검증한 뒤 문서의 clock 변환식으로 deadlineMs를 반환한다.
client.financial은 method가 POST/DELETE, path가 string, body가 unknown, authorization이 callback 또는 ClaimedApproval이며 Promise<any>를 반환한다. 기존 callback 인자의 소스 호환을 유지한다. 기존 trading-tools는 approval.beforeSend()와 financial(...,approval)로 바꾸어 일반 주문도 ticket deadline을 전달한다.
financial-transport의 deadline은 `clock.now()+timeoutMs`와 approval.deadlineMs 중 작은 값이며 legacy callback이면 기존4초다. auth·quota·fetch와 본문 소비가 같은 절대 deadline을 사용한다. callback 검증 뒤 만료를 다시 검사하고 started=true 바로 뒤 fetch한다.
CASE-DEADLINE은 claim 뒤 GET/auth/quota/queue에서 경과를 주입한다. 잔여100ms fetch·본문 읽기를 abort/cancel하고 시작 뒤 UNKNOWN/noReplay, 시작 전 만료는 금융0회를 단언한다. ticket 재파싱은 execution-guard 이외에 없다.
이미 금융이 시작된 뒤 실패는 문서의 operation+status+code allowlist 이외에는 UNKNOWN이다. CANCEL404 conditional-order-not-found도 유효한 ErrorResponse면 FAILED다. CASE-REJECTION은 문서 표의 모든 조합과 같은 status의 미지 code/불량 envelope를 검사한다. 422 전체를 FAILED로 만들지 않는다.
401 응답 뒤 auth 재발급·금융 재전송은0회다. timeout/429/5xx/불량200·204/끊김에서 UNKNOWN과 noReplay를 확인한다.

### 4. 실제 Python 소비와 직접 수신

신규 tests/fixtures/conditional-entry.ts만 테스트 connector 원본과 SDK bundle을 생성한다. 기존 trading-entry의 생성 원본을 기반으로 조건 등록을 추가한 테스트 사본을 만든다.
이 한 생성 명령을 Bun 테스트와 Python fixture가 같이 호출한다. plugin.json/.mcp.json·READ prepare·FINANCIAL/always·guard·scope_fields 선언은 테스트 임시 원본에만 있다.
guard 의 `kinds` 에 세 조건 도구를 `CONDITIONAL` 로 선언한다. 선언이 없으면 일반 주문으로 보고 조건 summary 를 거절한다.
`hermes/tests/fixtures/toss-conditional-contract.py`는 실제 connector_manifest._load_connector와 _prepare_result를 호출하며 자체 manifest/SHA/검증기를 만들지 않는다.
`hermes/tests/test_connector_conditional_sdk.py`는 실제 _run_connector_prepare/_prepare_answer 및 실행 처리기로 SDK stdio를 왕복한다. 성공 결과만 JSON 파일로 넘겨 검증한 것을 실제 실행 소비 증거로 대신하지 않는다.
support 응답과 claim만 loopback 대역이다. ClaimResponse(v/allowed/ticketId/expiresAt), codec 요청8KiB·ticket4096자·scope 규칙과 MVC 성공200/replay409·실패 응답에 protocol vector를 직접 대조한다.
합성 ticket은 가짜 계정과 키만 쓰고 실제 Spring 인증·DB 소비 증거로 보고하지 않는다. 허용 목적지 이외 egress는 실패한다.
목적지 counter를 auth POST·각 GET·claim POST·금융 POST/DELETE로 분리한다. 정상 CREATE/MODIFY는 POST1/DELETE0, CANCEL은 POST0/DELETE1이다.
raw body에서 scope/market/currency/expected/symbol(MODIFY)/clientOrderId(MODIFY)가 없는지 직접 검사한다.
변조·추가 키·number decimal·C0/C1/Cs·scope/env·manifest 변경·만료·replay에서 금융0회를 확인한다. GET/auth/quota 각각에서 만료를 주입한다.
운영 SDK list_tools의 금융0개와 착수 시 READ 합집합, policy RISK_NOT_OPEN/noGrant를 기존 회귀와 함께 확인한다.

## 검증

```bash
cd hermes/connectors/tossinvest && bun run typecheck
cd hermes/connectors/tossinvest && bun run build
cd hermes/connectors/tossinvest && bun run check:bundle
cd hermes/connectors/tossinvest && bun test ./tests
PYTHONPATH=hermes/tests python3 -m unittest hermes/tests/test_connector_conditional_sdk.py hermes/tests/test_connector_conditional_validation.py hermes/tests/test_connectors_contract.py
node scripts/checks/check-file-length.mjs
```

red/green 실행 건수와 실제 Python stdio 소비, 정상 세 operation 수신·실패 주입·UNKNOWN 추가 전송0회가 모두 있어야 완료다.
기존 trading.test.ts/trading-review.test.ts와 account-rate-limit/rate-limit-clock/rest-rate-limit 테스트를 함께 실행한다. 일반 callback 직접 호출의4초 호환, 새 일반 호출의 ticket deadline, 금융401 auth 추가0회를 분리해 관측한다.
규모는 production numstat의 추가+삭제로 계산하며 final lineCount는 포맷 뒤 측정한다. 책임 이동도 삭제+신규를 모두 센다. baseline 증가는 없다.
실제 DB producer가 없는 사실은 이 관심사의 실패 조건이 아니다. 공개 최신 JSON은 구현 직전 GET으로 버전/SHA를 다시 대조하고 변경 시 공통 계약 담당자에게 보고한다.

## 선행 통합 후 수정 파일과 예산

다음은 고정 source에서 정의를 읽은 실제 파일이다. 현재 checkout에 없으므로 신규로 선언하거나 복제하지 않는다. 실제 main 통합 조건을 통과한 작업 공간에서는 아래 수정 행을 변경 파일 표에 합쳐 기본 verify_task를 다시 실행한다. 이 단계는 이미 확정된 경로·예산의 존재 대조이며 구현자에게 설계를 미루는 단계가 아니다.

| 고정 source의 파일 | 수정 책임 | 추가+삭제 / 최종 줄 상한 |
| --- | --- | --- |
| hermes/connectors/tossinvest/src/execution-guard.ts | ClaimedApproval 반환과 검증된 deadline 전달 | 30 / 247 |
| hermes/connectors/tossinvest/src/trading-tools.ts | 일반 주문의 새 반환값 소비·deadline 전달 | 12 / 95 |
| hermes/connectors/tossinvest/src/conditional-read-tools.ts | orderSegment export, 기존 조회 동작 유지 | 2 / 105 |
| hermes/connectors/tossinvest/tests/trading.test.ts | 일반 금융 deadline·callback 호환 회귀 | 테스트, production 제외 |
| hermes/connectors/tossinvest/tests/trading-review.test.ts | claim 선행·단일전송 회귀의 이동 경로 반영 | 테스트, production 제외 |

현재 변경 표의 client는 기존 파일이지만 금융 부분 기준은PR454의394줄이다. 고정 source의 금융 메서드293~334행42줄을 삭제하고 wrapper/import에38줄을 배분하면 최종390줄·변경80줄이다. 신규 financial-transport140줄에 이 책임을 넣으며 이동42줄의 삭제도 위80줄에 포함했다.

## 변경 파일

| 파일 | 변경 |
| --- | --- |
| `hermes/connectors/tossinvest/src/conditional-body.ts` | 신규 (260줄 이내) |
| `hermes/connectors/tossinvest/src/prepare-conditional.ts` | 신규 (230줄 이내) |
| `hermes/connectors/tossinvest/src/conditional-tools.ts` | 신규 (110줄 이내) |
| `hermes/connectors/tossinvest/src/client.ts` | 수정 (추가+삭제80, 최종390줄 이내) |
| `hermes/connectors/tossinvest/src/financial-transport.ts` | 신규 (140줄 이내) |
| `hermes/connectors/tossinvest/src/conditional-result.ts` | 신규 (118줄 이내) |
| `hermes/connectors/tossinvest/tests/conditional-trading.test.ts` | 신규 (400줄 이내) |
| `hermes/connectors/tossinvest/tests/fixtures/conditional-entry.ts` | 신규 (300줄 이내) |
| `hermes/tests/fixtures/toss-conditional-contract.py` | 신규 (160줄 이내) |
| `hermes/tests/test_connector_conditional_sdk.py` | 신규 (380줄 이내) |
| `docs/features/tossinvest-connector.md` | 수정 |

기본 검사는 현재 변경 표의 존재와 계획 구조를 검증한다. 위 선행 통합 표의 고정 source 직접 대조와 미래 actual main의 기본 검사를 구분한다. 현재 검사0은 phase-05 착수 허가가 아니다.
