# Phase 02. 토스 커넥터의 주문 도구 선언과 등록

**Execution profile**: deep

## 목표

운영 토스 서버가 `prepare_trade`, `create_order`, `cancel_order` 를 등록하고, manifest 가 세 도구와 `execution_guard` 를 선언한다.
`modify_order` 는 등록하지도 선언하지도 않는다. 소수점 거래는 미국 `MARKET` 금액 주문으로만 연다는 결정을 테스트로 보인다.

**범위 외**: Control Plane 의 진입점과 관문(phase-01), 화면과 기능 문서(phase-03), plugin(`hermes/plugins/`) 변경, 실제 토스 API 호출.

## 컨텍스트

- 주문 준비와 실행은 이미 구현돼 있다. `hermes/connectors/tossinvest/src/prepare-trade.ts`(준비), `trading-body.ts`(검증과 본문), `trading-tools.ts`(`registerTradingTools`), `execution-guard.ts`(claim)다.
- 운영 `src/server.ts` 는 `registerTradingTools` 를 부르지 않는다. 테스트 entry `tests/fixtures/trading-entry.ts` 만 부르고, 그 fixture 는 운영 `connector.json` 을 복사한 뒤 금융 선언을 덧붙인다.
- 서버의 도구 설명은 manifest 의 `description` 을 먼저 쓴다(`src/tool-registration.ts`).
- 생성 bundle `dist/` 는 Git 이 추적하지 않는다. 테스트가 소스에서 빌드한다.
- plugin 은 `approval: always` 도구를 모델 목록에서 빼고(`connector_manifest.py`), `execution_guard` 를 `connector_guard.py` 의 `_guard_manifest` 로 검증한다. 키는 정확히 `protocol`, `prepare_tool`, `scope_fields`, `operations` 와 선택 `kinds` 다. FINANCIAL 도구는 `grant` 와 `conversation_grant` 를 선언하면 manifest 전체가 거절된다.
- 저장소 계약 테스트 `hermes/tests/test_connectors_contract.py` 는 지금 모든 커넥터의 `FINANCIAL` 선언을 막는다(`CLOSED_RISKS`). READ 가 아닌 도구에는 `title` 과 boolean `outbound` 를 요구하고, `outbound` 가 참이면 `grant: false` 를 요구한다. 사용자 문구 검사(`user_copy_problems`)는 README 「도구 목록」 의 줄 수가 도구 수와 같고, title 과 summary 에 도구 이름과 영문 인자 이름이 없기를 요구한다.

공식 명세 1.2.24(`tests/fixtures/openapi-1.2.24.json` 의 `OrderCreateRequest`)의 소수점 조건이다.

- 금액 갈래는 `symbol`, `side`(BUY, SELL), `orderType=MARKET`, `orderAmount` 만 받고 미국 시장 전용이다. `timeInForce` 와 `price` 는 없다
- 소수 수량은 미국 `MARKET` 매도에만, 소수 6자리까지 받는다. 지정가와 매수, 국내의 소수 수량은 `400 invalid-request` 다
- 금액 주문과 소수 수량 주문은 정규장 시작부터 종료 1시간 전까지만 접수한다
- 최소 주문 금액과 금액의 소수 자리는 명세에 없다

`trading-body.ts` 의 `tradingBody` 가 이 조건을 이미 지킨다. 바꾸지 말고 테스트로 보인다.

**근거 문서**: `docs/adr/ADR-20261013-financial-order-opening.md`, `docs/features/tossinvest-connector.md` 의 「일반 금융 모듈」, `docs/features/connector-policy.md` 의 「표시 값과 실행 인자」, `hermes/docs/adr/ADR-20261008-tossinvest-connector.md`

## 의도 메모

- `outbound` 는 `false` 로 둔다. 참이면 계약 테스트가 `grant: false` 를 요구하는데, FINANCIAL 은 `grant` 를 선언하면 plugin 이 manifest 를 거절한다. FINANCIAL 은 `always` 라 `outbound` 와 관계없이 허락이 닫힌다.
- `modify_order` 는 테스트 fixture 에만 남긴다. 기존 정정 테스트를 지우지 않기 위해 등록 함수가 도구 목록을 인자로 받게 한다.
- 계약 테스트의 FINANCIAL 허용은 그 커넥터의 `execution_guard.operations` 에 든 도구로만 좁힌다. `DESTRUCTIVE` 는 계속 닫는다.

## 작업 항목

### 1. 등록 함수가 열 도구를 인자로 받게 한다

`hermes/connectors/tossinvest/src/trading-body.ts` 에 `export const OPEN_TRADE_TOOLS: readonly [TradeTool, ...TradeTool[]] = ["create_order", "cancel_order"];` 를 더한다.

`hermes/connectors/tossinvest/src/trading-tools.ts`

- `registerTradingTools(register, client, tools: readonly [TradeTool, ...TradeTool[]] = OPEN_TRADE_TOOLS)` 로 바꾼다. `z.enum` 이 비지 않은 튜플을 요구하므로 인자 타입을 이렇게 둔다.
- `prepare_trade` 의 `tool` 입력은 `z.enum(tools)` 로, 실행 도구 등록 반복은 `tools` 로 돈다.
- 파일 머리 주석 「운영 server에서는 아직 호출하지 않는다」 를 지금 동작으로 고친다.

`hermes/connectors/tossinvest/tests/fixtures/trading-entry.ts` 는 `registerTradingTools(register, client, ["create_order", "modify_order", "cancel_order"])` 로 세 도구를 그대로 등록한다. fixture manifest 의 금융 선언 덧붙이기는 그대로 둔다.

### 2. 운영 서버가 두 도구를 등록한다

`hermes/connectors/tossinvest/src/server.ts` 의 `createTossinvestServer` 가 `registerTradingTools(register, client)` 를 부른다.

### 3. manifest 에 세 도구와 보호 선언을 더한다

`hermes/connectors/tossinvest/connector.json`

- `tools.prepare_trade`: `risk: "READ"`, `title`, `summary`, `description`
- `tools.create_order`, `tools.cancel_order`: `risk: "FINANCIAL"`, `approval: "always"`, `outbound: false`, `title`, `summary`, `description`. `grant` 와 `conversation_grant` 는 두지 않는다
- 최상위 `execution_guard`:

```json
{
  "protocol": "approval-claim-v2",
  "prepare_tool": "prepare_trade",
  "scope_fields": [{ "arg": "account_seq", "field": "account" }],
  "operations": { "create_order": "CREATE", "cancel_order": "CANCEL" }
}
```

- 최상위 `description` 의 「주문하지 않습니다」 를 「승인한 주문 한 건씩만 넣거나 취소합니다」 같은 지금 동작으로 고친다.
- `prepare_trade` 의 `description` 은 모델이 읽는다. `{v: 1, tool, args}` 입력, `tool` 은 `create_order` 나 `cancel_order`, 실행은 사용자 승인 뒤에만 한 번 일어나고 이 호출은 승인 요청만 남긴다는 것, 소수점 매수와 매도는 미국 `MARKET` 의 `orderAmount`(달러)로만 한다는 것, 지정가와 국내의 소수 수량은 거절된다는 것을 적는다.
- 실행 도구 둘의 `description` 은 「모델이 직접 부르지 않는다. 승인 뒤 Control Plane 만 부른다」 를 적는다.
- title 과 summary 는 해요체이고 도구 이름과 영문 인자 이름을 쓰지 않는다. 예: 「주문 준비하기」 「주문을 넣기 전에 내용을 정리해 승인을 요청해요.」, 「주문 넣기」, 「주문 취소하기」.

### 4. 모델이 읽는 스킬과 README 의 사용자 안내를 고친다

`hermes/connectors/tossinvest/skills/tossinvest/SKILL.md` 는 모델에게 설치된다. 지금 「주문하지 않는다」 절이 주문을 앱으로 넘기라고 지시해, 이대로면 모델이 준비 도구를 부르지 않는다.

- front matter `description` 의 「주문하지 않는다.」 를 「승인받은 일반 주문을 넣거나 취소한다. 정정과 조건주문은 하지 않는다.」 로 고친다.
- 「주문하지 않는다」 절을 「주문」 절로 바꾼다. 담을 것: 사용자가 대화에서 직접 시킨 주문만 준비한다. 주문과 취소는 `prepare_trade` 하나로 `{v: 1, tool: "create_order" | "cancel_order", args}` 를 부른다. 이 호출은 승인 요청만 남기고, 실제 주문은 사용자가 승인 카드에서 승인한 뒤 한 번만 나간다. 승인 전에 주문했다고 말하지 않는다. 소수점 매수와 매도는 미국 종목의 `MARKET` 금액 주문(`orderAmount`, 달러)으로만 한다. 지정가와 국내의 소수 수량, 정정, 조건주문은 하지 않는다. 금액 주문은 미국 정규장 시작부터 종료 1시간 전까지만 받는다. 거절이나 결과 불명이면 다시 부르지 말고 사용자에게 승인 카드와 토스증권 앱을 확인해 달라고 한다.
- 다른 절의 「주문이나 WebSocket 도구는 제공하지 않는다」 같은 문장을 「WebSocket 은 제공하지 않는다」 처럼 지금 동작에 맞게 고친다. `grep -n '주문' hermes/connectors/tossinvest/skills/tossinvest/SKILL.md` 로 남은 문장을 모두 본다.

README 는 아래처럼 고친다.

`hermes/connectors/tossinvest/README.md`

- 「도구 목록」 표에 세 줄을 더한다. 첫 칸은 manifest 의 title, 둘째 칸은 summary 와 같은 글이다. 도구 이름은 README 어디에도 쓰지 않는다.
- 「주문하지 않는다」 류의 문장을 「주문은 대화에서 요청하면 승인 카드가 뜨고, 승인한 한 건만 들어간다. 정정과 조건주문은 하지 않는다」 로 고친다.
- 운영자가 허용 목록과 상한, key 파일을 넣기 전에는 주문이 거절된다는 것을 한 줄로 적는다. 값과 위치는 적지 않는다.

### 5. 금융 비활성과 도구 수를 기대하던 테스트를 지금 선언에 맞춘다

운영 서버에 세 도구가 더해지면 「도구가 33개이고 모두 READ, `readOnlyHint` 참」 을 단정한 테스트가 깨진다. 기대를 「READ 도구 33개는 그대로이고, `prepare_trade` READ 하나와 FINANCIAL 둘(`create_order`, `cancel_order`)이 따로 있다」 로 바꾼다. 아래가 그 자리다.

- `hermes/connectors/tossinvest/tests/market-info-stdio.test.ts` 86-92행
- `hermes/connectors/tossinvest/tests/indicator-csv-compatibility.test.ts` 56-57행
- `hermes/connectors/tossinvest/tests/order-read-stdio.test.ts` 67-75행
- `hermes/connectors/tossinvest/tests/read-union-stdio.test.ts` 25행, 64-74행
- `hermes/connectors/tossinvest/tests/rest-coverage.test.ts` 447-461행
- `hermes/connectors/tossinvest/tests/ranking-indicator-production.test.ts` 95-100행
- `hermes/connectors/tossinvest/tests/stock-statistics-integration.test.ts` 50-58행
- `hermes/connectors/tossinvest/tests/conditional-stdio.test.ts` 128-135행
- `hermes/connectors/tossinvest/tests/market-data-tools.test.ts` 116행
- `hermes/tests/test_connector_manifest.py` 의 `test_toss_read_union_preserves_descriptions_and_read_policy`(887-916행 근처). READ 도구 집합만 33개와 견주고, 금융 셋은 따로 단언한다

행 번호는 이 계획을 쓴 시점의 값이다. 각 파일에서 `33`, `readOnlyHint`, `READ` 를 찾아 같은 단언을 고친다. 그 밖에도 같은 단언이 있으면 `bun run test` 가 알려 준다.

- `hermes/connectors/tossinvest/tests/execution-guard.test.ts` 의 「운영 SDK·manifest의 READ 합집합과 금융 비활성을 유지한다」: 운영 manifest 의 `execution_guard` 가 위 선언과 같고, FINANCIAL 도구는 `create_order`, `cancel_order` 둘뿐이며, 서버 `listTools` 가 manifest 도구 집합과 같고 `modify_order` 가 없는지로 바꾼다. 대역 HTTP 0회 기대는 둔다.
- `hermes/connectors/tossinvest/tests/tossinvest.test.ts` 의 「manifest 의 도구 선언과 서버의 도구 목록이 같고 모두 읽기 전용이다」: READ 도구는 `readOnlyHint` 참, FINANCIAL 도구는 `readOnlyHint` 거짓과 `destructiveHint` 참인지로 나눈다. 설명이 manifest 와 같은지는 모든 도구에 둔다.
- `hermes/tests/test_connectors_contract.py`: `FINANCIAL` 은 그 커넥터의 `execution_guard.operations` 키에 든 도구일 때만 허용한다. `DESTRUCTIVE` 는 계속 위반이다. 이 규칙을 지키는 단위 검사(가짜 manifest 로 허용 한 건, 거절 한 건)를 같은 파일에 더한다.

### 6. 소수점과 USD 가격의 준비 결과를 테스트로 보인다

`hermes/connectors/tossinvest/tests/trading.test.ts` 에 더한다. 기존 대역(`FakeToss`)으로 `prepareTrade` 와 운영 서버의 `prepare_trade` 를 부르고, 금융 POST 가 0회인지 센다.

`prepareTrade` 는 `timeInForce` 가 없으면 `DAY` 로 채우고 소문자 `symbol` 을 대문자로 바꾸며, 둘 다 `normalization` 에 적는다. 아래 입력은 모두 대문자 `symbol` 과 `timeInForce: "DAY"` 를 준다.

- 미국 `MARKET` 매도 `quantity` "0.1": 실행 인자의 `quantity` 가 "0.1" 이고 `normalization` 이 비었다
- 미국 `LIMIT` 매수 `quantity` "1", `price` "12.34": 실행 인자와 summary 의 가격이 "12.34" 그대로이고 `normalization` 이 비었다. `price` "0.1234" 도 그대로다
- 미국 `MARKET` 매수 `orderAmount` "25.50": 실행 인자가 "25.5" 이고 `normalization` 은 `{field:"orderAmount", before:"25.50", after:"25.5"}` 하나뿐이다. summary 의 `price` 와 `quantity` 는 null 이다
- 거절: 미국 `LIMIT` 매수 `quantity` "0.1", 국내 종목의 `orderAmount`, `LIMIT` 의 `orderAmount`, `modify_order` 를 운영 서버의 `prepare_trade` 로 부른 호출

## 검증

```bash
cd hermes/connectors/tossinvest && bun install --frozen-lockfile && bun run typecheck && bun run build
cd hermes/connectors/tossinvest && bun test ./tests/execution-guard.test.ts ./tests/tossinvest.test.ts ./tests/trading.test.ts
cd hermes/connectors/tossinvest && bun test ./tests/market-info-stdio.test.ts ./tests/indicator-csv-compatibility.test.ts ./tests/order-read-stdio.test.ts ./tests/read-union-stdio.test.ts ./tests/rest-coverage.test.ts ./tests/ranking-indicator-production.test.ts ./tests/stock-statistics-integration.test.ts ./tests/conditional-stdio.test.ts ./tests/market-data-tools.test.ts
cd hermes/connectors/tossinvest && bun run test && bun run check:bundle
bash scripts/ci/check-connector-package.sh
PYTHONPATH=hermes/tests python3 -m unittest hermes/tests/test_connectors_contract.py
PYTHONPATH=hermes/tests python3 -m unittest test_connector_guard_binding test_connector_guard_transport
PYTHONPATH=hermes/tests python3 -m unittest hermes/tests/test_connector_manifest.py
```

모두 0 으로 끝나야 한다.
Python 계약 테스트는 커넥터 묶음(`dist/`)이 빌드돼 있어야 돈다. CI 의 `hermes` job 과 같은 순서로 `scripts/ci/check-connector-package.sh` 를 먼저 돌린다. 이 단계 없이 돌리면 main 에서도 실패한다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `hermes/connectors/tossinvest/src/trading-body.ts` | 수정 |
| `hermes/connectors/tossinvest/src/trading-tools.ts` | 수정 |
| `hermes/connectors/tossinvest/src/server.ts` | 수정 |
| `hermes/connectors/tossinvest/connector.json` | 수정 |
| `hermes/connectors/tossinvest/README.md` | 수정 |
| `hermes/connectors/tossinvest/skills/tossinvest/SKILL.md` | 수정 |
| `hermes/connectors/tossinvest/tests/fixtures/trading-entry.ts` | 수정 |
| `hermes/connectors/tossinvest/tests/execution-guard.test.ts` | 수정 |
| `hermes/connectors/tossinvest/tests/tossinvest.test.ts` | 수정 |
| `hermes/connectors/tossinvest/tests/trading.test.ts` | 수정 |
| `hermes/tests/test_connectors_contract.py` | 수정 |
| `hermes/tests/test_connector_manifest.py` | 수정 |
| `hermes/connectors/tossinvest/tests/market-info-stdio.test.ts` | 수정 |
| `hermes/connectors/tossinvest/tests/indicator-csv-compatibility.test.ts` | 수정 |
| `hermes/connectors/tossinvest/tests/order-read-stdio.test.ts` | 수정 |
| `hermes/connectors/tossinvest/tests/read-union-stdio.test.ts` | 수정 |
| `hermes/connectors/tossinvest/tests/rest-coverage.test.ts` | 수정 |
| `hermes/connectors/tossinvest/tests/ranking-indicator-production.test.ts` | 수정 |
| `hermes/connectors/tossinvest/tests/stock-statistics-integration.test.ts` | 수정 |
| `hermes/connectors/tossinvest/tests/conditional-stdio.test.ts` | 수정 |
| `hermes/connectors/tossinvest/tests/market-data-tools.test.ts` | 수정 |
