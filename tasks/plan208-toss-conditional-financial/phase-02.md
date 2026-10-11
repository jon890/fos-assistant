# Phase 02. 조건주문 SDK 준비와 단일 금융 전송

**Execution profile**: deep

## 목표

조건 CREATE/MODIFY/CANCEL을 실제 SDK와 Python 소비자에 연결해 loopback 수신으로 단일전송을 증명한다.

**범위 외**: 실제 Spring DB producer, 운영 허가·등록·공개 승인·실계좌·실토큰·거래·배포·WS다.

## 컨텍스트

**근거 문서**: `docs/features/connector.md`의 「조건주문 REST 쓰기의 구현 보완안」이다.
일반 금융 고정 source는 PR454의 9c2f9a45cac68b34de47721ee82d445b4fdec4c6이며 prepareTrade/tradingBody/registerTradingTools/claimApproval/Tossinvest.financial을 직접 읽은 근거다.
현재 기준 main에는 이 금융 모듈이 없다. 이 계획은 복사본이나 없는 운영 등록 함수가 이미 있다고 가정하지 않는다.
조건 조회 PR452의 conditional-read-tools.ts와 official-response.ts는 기존 schema/ID 검사/C0·C1·Cs/공식 필드 투영의 재사용 근거다.
일반 금융 financialOperation은 조건 CREATE/MODIFY를 차단하고 financialResult는 result.orderId를 요구한다. 조건 operation descriptor와 응답 분기를 반드시 추가한다.
일반 주문이 가진 DELETE 빈204 지원만으로 조건주문 전체가 구현되었다고 보고하지 않는다.

## Blocked 조건

- 조건 snapshot 소비 계약, 일반 금융 모듈, 조건 조회 모듈이 실제 main에 통합되어 있어야 한다. gh merged 상태와 merge SHA의 main 조상 여부 및 실제 심볼을 확인한다.
- 일반 금융 파일 존재만으로 actual main 통합을 추정하지 않는다. 실제 producer·guard 설치 완료나 운영 금융 허가는 이 관심사의 선행 조건이 아니다.
- 합성 claim과 목적지 loopback, Python MCP 라이브러리·Bun 실행 환경이 없으면 PHASE_BLOCKED다. skip을 성공으로 세지 않는다.
- client의 현재 독점 소유자를 확인하고 별도 구현 dispatch를 받은 뒤 시작한다.

## 의도 메모

- 기존 금융 guard·transport를 재사용한다. 일반 주문 body에 조건 leg를 섞거나 범용 JSON producer를 만들지 않는다.
- SDK 도구 등록은 테스트 entry만 한다. 운영 createTossinvestServer와 connector.json의 금융 목록은 0개로 둔다.
- 이 관심사를 설계·구현·회귀 한 PR로 끝내며 운영 변경 목표 1200줄, 상한1500줄과 TS 파일400줄 상한을 지킨다.

## 작업 항목

### 1. 실제 SDK 테스트를 먼저 실패시킨다

`conditional-trading.test.ts`는 fixture OpenAPI의 세 operation과 schemas를 직접 읽어 method/path/body/response를 단언한다.
실제 MCP SDK Client로 prepare_conditional_trade와 세 쓰기를 호출한다. 등록 누락과 일반 result.orderId 검사로 실패하는 red를 먼저 기록한다.
공식 fixture SHA·버전·41개 coverage는 기존 rest-coverage.test.ts와 함께 확인한다. 새 등록 때문에 다른 READ 목록을 7개로 되돌리지 않는다.

### 2. 조건 body와 prepare

신규 conditional-body.ts에 CONDITIONAL_MODEL_SCHEMAS/CONDITIONAL_EXECUTION_SCHEMAS/conditionalBody를 둔다.
문서의 정확한 입력·원문·summary 계약을 구현하고 일반 주문 tradingBody를 우회하지 않는다. false 동의는 body builder가 명시하며 true는 거절한다.
신규 prepare-conditional.ts에 prepareConditionalTrade와 readExpectedConditionalOrder를 둔다.
조건 조회의 ID segment 검사 함수는 export하여 재사용한다. 공식 응답 검증 뒤 officialFields로 투영하며 null/부재를 보존한다.
계좌·종목·현재 원주문과 OCO 현재가를 읽는다. decimal은 기존 canonicalDecimal과 정확한 비교를 사용하며 Number 변환·가격 절삭을 하지 않는다.
prepare에서만 UUID·decimal 표기를 만든다. normalization의 nested field 경로와 원래 값 대조, 시장·단위·유효기간·STOP/PROFIT_RATE 경계는 문서대로 따른다.
prepare 결과를 test vector와 대조하고 env가 준비 중 바뀌면 거절한다. 목표수익률 신규 생성과 PROFIT_RATE MODIFY는 금융0회로 거절한다.

### 3. guard와 단일전송 연결

신규 conditional-tools.ts의 registerConditionalTradingTools는 prepare_conditional_trade와 create_conditional_order/modify_conditional_order/cancel_conditional_order를 등록한다.
takeApproval/claimApproval을 재사용하고 원문·SDK args를 변환 전에 대조한다. SDK schema는 strict이고 coercion/default를 두지 않는다.
client.financial의 기존 토큰 identity·quota·started 추적을 재사용한다. 조건 전용 operation/result 선택 함수를 conditional-body.ts에 두고 client에서 세 operation만 해당 분기로 연결한다.
일반 financialOperation/financialResult는 기존 일반 주문 경로에서 그대로 호출한다. 조건 success는 result.conditionalOrderId를 검사하고 MODIFY 새 ID·CREATE optional clientOrderId를 대조한다.
CANCEL은 DELETE·본문 없음·빈204만 성공이다. 결과는 승인 대상 conditionalOrderId와 취소 완료 표시를 돌려주되 공식 JSON 응답이 있었다고 표시하지 않는다.
claim 뒤 현재 원주문·OCO 현재가를 다시 조회한다. 값/타입/키 변화와 terminal leg면 전송0회다.
금융 deadline은 기존 4000ms와 ticket 남은 시간의 최솟값이다. queue callback에서 beforeSend를 최종 호출하고 deadline도 남은 ticket 시간에 맞춘다.
이미 금융이 시작된 뒤 실패는 공식 거절 allowlist 이외에는 UNKNOWN이다. 조건의 422/404처럼 code가 미정인 경우 상태만으로 FAILED를 만들지 않는다.
401 응답 뒤 auth 재발급·금융 재전송은0회다. timeout/429/5xx/불량200·204/끊김에서 UNKNOWN과 noReplay를 확인한다.

### 4. 실제 Python 소비와 직접 수신

신규 tests/fixtures/conditional-entry.ts만 테스트 connector 원본과 SDK bundle을 생성한다. 기존 trading-entry의 생성 원본을 기반으로 조건 등록을 추가한 테스트 사본을 만든다.
이 한 생성 명령을 Bun 시험과 Python fixture가 같이 호출한다. plugin.json/.mcp.json·READ prepare·FINANCIAL/always·guard·scope_fields 선언은 테스트 임시 원본에만 있다.
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
node scripts/check-file-length.mjs
```

red/green 실행 건수와 실제 Python stdio 소비, 정상 세 operation 수신·실패 주입·UNKNOWN 추가 전송0회가 모두 있어야 완료다.
실제 DB producer가 없는 사실은 이 관심사의 실패 조건이 아니다. 공개 최신 JSON은 구현 직전 GET으로 버전/SHA를 다시 대조하고 변경 시 공통 계약 담당자에게 보고한다.

## 변경 파일

| 파일 | 변경 |
| --- | --- |
| `hermes/connectors/tossinvest/src/conditional-body.ts` | 신규 |
| `hermes/connectors/tossinvest/src/prepare-conditional.ts` | 신규 |
| `hermes/connectors/tossinvest/src/conditional-tools.ts` | 신규 |
| `hermes/connectors/tossinvest/src/client.ts` | 수정 |
| `hermes/connectors/tossinvest/tests/conditional-trading.test.ts` | 신규 |
| `hermes/connectors/tossinvest/tests/fixtures/conditional-entry.ts` | 신규 |
| `hermes/tests/fixtures/toss-conditional-contract.py` | 신규 |
| `hermes/tests/test_connector_conditional_sdk.py` | 신규 |
| `docs/features/connector.md` | 수정 |

조건 조회의 ID helper export는 현재 main에 파일이 없으므로 표에 가짜 수정 행을 두지 않는다. 조건 조회가 main에 통합된 새 checkout에서 기존 orderSegment를 확인하고 구현 dispatch 전에 수정 행을 변경 표에 추가해 기본 검사를 다시 실행한다. 검사를 복제하거나 없는 파일을 신규로 선언하지 않는다.
