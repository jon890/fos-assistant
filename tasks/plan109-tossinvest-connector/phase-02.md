# Phase 02. 보유 종목, 주문 가능 현금, 주문 내역 도구

**Execution profile**: standard

## 목표

phase 01 의 `tossinvest` 커넥터에 계좌 데이터를 읽는 `READ` 도구 셋(`get_holdings`, `get_buying_power`, `list_orders`)을 더한다. `list_orders` 는 이 phase 에서 결과로만 돌려준다.

**범위 외**: `list_orders` 의 파일 출력(phase 03). 주문, 정정, 취소 도구는 만들지 않는다.

## 컨텍스트

**근거 문서**: `docs/connectors/tossinvest.md` 의 「도구와 정책」, 「도구의 인자와 결과」, 「오류」, `docs/adr/ADR-20261008-tossinvest-connector.md`

- phase 01 이 만든 `hermes/connectors/tossinvest/src/client.ts` 의 `Tossinvest.request(path, {query, account})` 를 쓴다. `account: true` 면 `X-Tossinvest-Account` 헤더에 `TOSSINVEST_ACCOUNT_SEQ` 를 싣는다. 이 env 가 비었거나 `^[0-9]{1,10}$` 가 아니면 요청 없이 `TOSSINVEST_ACCOUNT_NOT_FOUND` 다. 이 판정이 phase 01 에 없으면 이 phase 에서 `client.ts` 에 더한다
- 도구 등록과 시험 대역은 phase 01 의 `src/tool-registration.ts`, `tests/support.ts` 를 그대로 쓴다. 대역의 허용 목록에 이 phase 의 경로를 더한다
- 파일마다 400줄을 넘지 않는다. 새 도구는 `src/account-tools.ts` 에 둔다

### 토스증권 API 가운데 이 phase 가 쓰는 것

모두 `X-Tossinvest-Account` 헤더가 필요하다.

| API | 질의 | 답의 `result` |
| --- | --- | --- |
| `GET /api/v1/holdings` | 없음 | `{totalPurchaseAmount: {krw, usd}, marketValue: {amount: {krw, usd}, amountAfterCost}, profitLoss: {amount: {krw, usd}, amountAfterCost, rate, rateAfterCost}, dailyProfitLoss: {amount: {krw, usd}, rate}, items: [{symbol, name, marketCountry, currency, quantity, lastPrice, averagePurchasePrice, marketValue, profitLoss, dailyProfitLoss, cost}]}`. 금액과 비율은 10진수 글이고 `usd` 는 null 일 수 있다 |
| `GET /api/v1/buying-power` | `currency`: `KRW` \| `USD` | `{currency, cashBuyingPower}` |
| `GET /api/v1/sellable-quantity` | `symbol` | 매도 가능 수량. 칸 이름은 공식 스펙의 그 응답을 따른다 |
| `GET /api/v1/orders` | `status`: `OPEN` \| `CLOSED`(필수), `symbol`, `from`, `to`(`YYYY-MM-DD`, 한국 시각, 포함), `cursor`, `limit`(CLOSED 만, 최대 100) | `{orders: [{orderId, symbol, side, orderType, timeInForce, status, price, quantity, orderAmount, currency, orderedAt, canceledAt, execution: {filledQuantity, averageFilledPrice, filledAmount, commission, tax, filledAt, settlementDate}}], nextCursor, hasNext}`. `OPEN` 은 커서와 `limit` 을 무시하고 전량을 준다 |

`items[]` 안의 `marketValue`, `profitLoss`, `dailyProfitLoss`, `cost` 의 칸 구조는 공식 스펙(`latest/openapi.json` 의 `GET /api/v1/holdings` 응답)을 읽고 맞춘다. 스펙을 읽을 수 없으면 `PHASE_BLOCKED` 로 멈춘다.

## 의도 메모

- 결과의 칸 이름은 snake_case 로 옮기되 값은 API 가 준 글 그대로 둔다. 숫자로 바꾸지 않는다. 미국 주식의 달러 금액과 소수점 수량이 있어 바꾸면 값이 바뀐다
- `list_orders` 결과는 주문 100건까지다. `CLOSED` 는 `limit=100` 으로 한 쪽만 읽고 `has_more` 로 다음 쪽이 있는지 알린다. 다음 쪽을 읽는 커서 인자는 두지 않는다. 기간 전체는 phase 03 의 파일 출력이 맡는다
- 날짜는 `^\d{4}-\d{2}-\d{2}$` 이고 실제 날짜여야 한다. `from` 이 `to` 보다 늦거나 둘 다 있을 때 기간이 366일을 넘으면 요청 없이 `TOSSINVEST_INVALID_INPUT`
- 계좌 그룹 API 의 호출 한도가 초당 1회라 한 도구 호출이 계좌 API 를 여러 번 부르지 않게 한다. `get_buying_power` 는 `/buying-power` 하나와, `symbol` 을 주었을 때만 `/sellable-quantity` 하나다

## 작업 항목

### 1. `hermes/connectors/tossinvest/src/account-tools.ts`

| 도구 | 입력 | 결과 | 설명 |
| --- | --- | --- | --- |
| `get_holdings` | 없음 | `{total: {purchase_amount, market_value, profit_loss, profit_loss_rate, daily_profit_loss, daily_profit_loss_rate}, items: [{symbol, name, market, currency, quantity, last_price, average_purchase_price, market_value, profit_loss, profit_loss_rate, daily_profit_loss, daily_profit_loss_rate}]}`. 금액 칸은 `{krw, usd}` 로 둔다 | 「연결한 계좌의 보유 종목과 평가 금액, 손익을 읽습니다.」 |
| `get_buying_power` | `currency`: `KRW` \| `USD`, `symbol`: 선택 | `{currency, cash_buying_power, sellable_quantity}`. `symbol` 이 없으면 `sellable_quantity` 는 null | 「통화별 주문 가능 현금을 읽습니다. symbol 을 주면 그 종목의 매도 가능 수량도 읽습니다.」 |
| `list_orders` | `status`: `OPEN` \| `CLOSED`, `from`, `to`, `symbol`: 선택 | `{orders: [{order_id, symbol, side, order_type, status, price, quantity, order_amount, currency, filled_quantity, average_filled_price, filled_amount, commission, tax, ordered_at, filled_at, canceled_at, settlement_date}], has_more}` | 「미체결(OPEN)이나 끝난(CLOSED) 주문을 기간으로 읽습니다. 100건까지 돌려주고 더 있으면 has_more 가 참입니다.」 |

모두 `annotations: { readOnlyHint: true }` 다. `server.ts` 에서 등록한다.

### 2. `hermes/connectors/tossinvest/connector.json`

`tools` 에 `get_holdings`, `get_buying_power`, `list_orders` 를 `{ "risk": "READ" }` 로 더한다.

### 3. `hermes/connectors/tossinvest/skills/tossinvest/SKILL.md`

도구 셋의 쓰임을 더한다. 보유 종목 브리핑은 `get_holdings` 와 `get_quotes` 를 쓰고, 끝난 주문을 기간 전체로 세려면 파일 출력을 쓴다는 문장은 phase 03 에서 더한다.

### 4. 묶음 파일

`bun run build` 로 `dist/tossinvest-mcp.js` 를 다시 만든다.

### 5. 시험 `hermes/connectors/tossinvest/tests/account-tools.test.ts`

대역 허용 목록에 `GET /api/v1/holdings`, `GET /api/v1/buying-power`, `GET /api/v1/sellable-quantity`, `GET /api/v1/orders` 를 더한다(`tests/support.ts`).

- `get_holdings` 정상: 계좌 헤더에 env 의 순번이 실리고, 금액 글이 그대로 옮겨진다
- 계좌 순번 env 가 비었으면 요청 없이 `TOSSINVEST_ACCOUNT_NOT_FOUND`. API 의 `404 account-not-found` 도 같은 코드
- `get_buying_power`: `symbol` 이 없으면 `/sellable-quantity` 를 부르지 않는다. 있으면 한 번 부른다. `currency` 가 `EUR` 면 요청 없이 `TOSSINVEST_INVALID_INPUT`
- `list_orders`: `CLOSED` 는 `limit=100` 을 싣고 `hasNext` 를 `has_more` 로 옮긴다. `OPEN` 은 `limit` 과 `cursor` 를 싣지 않는다. `from` 이 `to` 보다 늦거나 기간이 367일이면 요청 없이 `TOSSINVEST_INVALID_INPUT`
- `tests/tossinvest.test.ts` 의 도구 수 단언(있으면)을 다섯으로 고친다

## 검증

```bash
cd hermes/connectors/tossinvest && bun install --frozen-lockfile && bun run typecheck && bun test ./tests && bun run check:bundle
python3 -m unittest discover -s hermes/tests -p 'test_connectors_contract.py'
node scripts/check-file-length.mjs
```

셋 다 실패 없이 끝난다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `hermes/connectors/tossinvest/src/account-tools.ts` | 신규 |
| `hermes/connectors/tossinvest/src/client.ts` | 수정 |
| `hermes/connectors/tossinvest/src/server.ts` | 수정 |
| `hermes/connectors/tossinvest/src/tool-registration.ts` | 수정 |
| `hermes/connectors/tossinvest/connector.json` | 수정 |
| `hermes/connectors/tossinvest/skills/tossinvest/SKILL.md` | 수정 |
| `hermes/connectors/tossinvest/dist/tossinvest-mcp.js` | 수정 |
| `hermes/connectors/tossinvest/tests/support.ts` | 수정 |
| `hermes/connectors/tossinvest/tests/tossinvest.test.ts` | 수정 |
| `hermes/connectors/tossinvest/tests/account-tools.test.ts` | 신규 |
