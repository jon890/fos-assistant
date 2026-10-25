# 금융 워크벤치 커넥터

사용자의 금융 온톨로지 워크벤치를 조회하고, 체결을 기록하고, 해석을 제안으로 올리는 커넥터의 개발 문서다.

covers: `hermes/connectors/financial-workbench/`

코드는 [`hermes/connectors/financial-workbench/`](../../hermes/connectors/financial-workbench) 에 있다.
이 문서는 등록 칸과 운영 값, 도구와 정책, 보안, 실제 계정으로 확인할 것을 갖는다. 사용자 안내는 [금융 워크벤치 커넥터 README](../../hermes/connectors/financial-workbench/README.md) 가 갖는다.
운영 값을 받는 근거는 [ADR-20261025 / connector-operator-base-url](../../hermes/docs/adr/ADR-20261025-connector-operator-base-url.md) 에 있다.
커넥터 공통 계약은 [커넥터 연결](connector.md) 과 [커넥터 도구 정책](connector-policy.md) 이 갖는다.

워크벤치는 공개 저장소 [`jon890/fos-graphrag`](https://github.com/jon890/fos-graphrag) 의 `financial-workbench` 다.
투자 판단을 「세상의 변화 → 병목 → 산업 → 기업 → 밸류에이션(시나리오) → 투자 결정」 으로 잇는 그래프이고, 근거 문서와 검토 상태가 있는 주장, 개념, 사용자별 공부 목록을 갖는다.
이 커넥터가 따르는 API 는 그 저장소의 `financial-workbench/docs/api-contract.md` 가운데 「연동 토큰」 과 「에이전트 API(`/api/v1`)」 절이다.

**체결 사실은 바로 저장하고, 모델이 해석한 것은 제안으로 올린다.** 사용자 결정(2026-10-25)이다.
토스증권 체결은 금액과 수량까지 워크벤치에 들어가고 워크벤치에서 주인만 본다.
투자 결정 만들기, 체결과 결정 잇기, 근거 잇기, 공부 메모는 사람이 워크벤치의 「더보기 → 제안」 에서 승인해야 반영된다. 워크벤치 쪽 결정은 그 저장소의 ADR-013 이다.

## 등록 칸

| 칸 | env | 비밀 | 무엇 |
| --- | --- | --- | --- |
| `token` | `FINANCIAL_WORKBENCH_TOKEN` | 그렇다 | 워크벤치의 「더보기 → 연동 토큰」 에서 「조회와 기록」(`READ_WRITE`) 범위로 발급한 개인 연동 토큰. `^fwb_[A-Za-z0-9_-]{43}$` 만 받는다 |

필수다. 확인 도구는 `get_connection` 이다. 등록할 때 토큰으로 `GET /api/v1/me` 를 불러 사용자 표시 이름과 토큰 이름이 읽히면 저장한다.
「조회만」(`READ`) 토큰도 등록은 된다. 조회 도구는 그대로 쓰고, 쓰는 도구는 `403 INSUFFICIENT_SCOPE` 로 끝난다. 범위는 `get_connection` 결과의 `token.scope` 에 보인다.

### 운영 값

| env | 비밀 | 무엇 |
| --- | --- | --- |
| `FINANCIAL_WORKBENCH_BASE_URL` | 아니다 | 운영자가 띄운 워크벤치의 공개 기준 주소. 이 주소 뒤에 `/api/v1/...` 를 붙인다 |

값은 운영 목록이 갖고 이 저장소에는 적지 않는다. 운영 목록에 값이 없으면 대시보드가 카탈로그에서 뺀다.
커넥터는 값이 `https://` 주소이고 계정 정보와 쿼리, fragment 가 없을 때만 부른다. 아니면 부르지 않고 `WORKBENCH_NOT_CONFIGURED` 로 끝낸다. 끝의 `/` 는 지운다.

| 선언 | 값 | 까닭 |
| --- | --- | --- |
| `single_binding` | 선언하지 않는다 | 토큰은 사람마다 5개까지 따로 발급되고, 서로를 무효로 만들지 않는다. 같은 연결을 여러 에이전트에 붙여도 된다 |
| `sandbox_required` | 선언하지 않는다 | 셸이 `.env` 의 토큰을 읽어도 할 수 있는 것은 이 커넥터의 도구와 같다. 돈이 움직이지 않고, 체결 기록은 주인이 워크벤치에서 지울 수 있으며, 제안은 사람이 승인해야 반영된다 |

토스증권 커넥터는 두 선언을 모두 참으로 둔다. 두 선언은 토스증권 연결에만 걸리므로, 실행 공간이 있는 에이전트 하나에 토스증권과 워크벤치를 함께 붙일 수 있다.
스킬 이름은 `financial-workbench` 라 토스증권의 `tossinvest` 와 겹치지 않는다.

## 도구와 정책

MCP 서버 이름은 `financial-workbench` 다. `READ` 도구는 서버에서 `readOnlyHint` 가 참이고, 쓰는 두 도구는 `readOnlyHint` 가 거짓, `destructiveHint` 가 거짓, `idempotentHint` 가 참이다.

| 도구 | 위험도 | 승인 | 부르는 API | 하는 일 |
| --- | --- | --- | --- | --- |
| `get_connection` | `READ` | 없음 | `GET /api/v1/me` | 토큰을 확인하고 사용자 표시 이름과 토큰 이름을 읽는다 |
| `get_ontology` | `READ` | 없음 | `GET /api/v1/ontology` | 노드 타입, 관계 종류, 주장 predicate 를 읽는다 |
| `search_nodes` | `READ` | 없음 | `GET /api/v1/nodes` | 이름과 종목 코드로 항목을 찾는다 |
| `get_node` | `READ` | 없음 | `GET /api/v1/nodes/{id}` | 항목 하나의 속성과 관계, 주장, 근거 문서, 개념을 읽는다 |
| `trace_node` | `READ` | 없음 | `GET /api/v1/nodes/{id}/trace` | 근거 사슬을 위로 따라가고 비어 있는 단계를 보인다 |
| `get_neighborhood` | `READ` | 없음 | `GET /api/v1/graph/neighborhood` | 한두 홉 안의 항목과 관계를 읽는다 |
| `lookup_security` | `READ` | 없음 | `GET /api/v1/securities/lookup` | 시장과 종목 코드로 종목, 투자 결정, 밸류에이션을 함께 읽는다 |
| `list_my_knowledge` | `READ` | 없음 | `GET /api/v1/me/knowledge` | 토큰 주인의 공부 목록을 읽는다 |
| `record_trades` | `WRITE` | `required`, 대화 허락 | `POST /api/v1/trades` | 토스증권 체결을 내 체결 기록에 저장한다 |
| `list_my_trades` | `READ` | 없음 | `GET /api/v1/trades` | 내 체결 기록과 이어진 투자 결정을 읽는다 |
| `submit_proposals` | `WRITE` | `required`, 대화 허락 | `POST /api/v1/proposals` | 해석을 사람이 승인할 제안으로 올린다 |
| `list_my_proposals` | `READ` | 없음 | `GET /api/v1/proposals` | 내 제안의 상태와 거절 까닭을 읽는다 |

### 쓰는 도구의 선언

| 선언 | 값 | 까닭 |
| --- | --- | --- |
| `risk`, `approval` | `WRITE`, `required` | 워크벤치의 상태를 바꾸지만 되돌릴 수 있다. 정책의 하한이다 |
| `outbound` | 거짓 | 데이터가 토큰 주인 계정의 워크벤치로만 간다. 체결 기록과 제안은 그 주인만 본다 |
| `conversation_grant` | 참 | 체결 여러 건을 기록하고 제안을 올리는 일이 한 대화에서 이어진다. 대화 안에서 여러 번 불려도 체결은 주인만 보는 기록이고 주인이 지울 수 있으며, 같은 체결과 같은 `dedupeKey` 는 두 번 생기지 않는다. 제안은 워크벤치에서 사람이 다시 승인해야 반영된다 |
| `grant` | 선언하지 않는다(참) | 같은 까닭으로 상시 허락도 연다 |
| `identifiers` | 선언하지 않는다 | 승인 카드의 식별자 면제는 맨 위 칸의 글이나 글 배열에만 걸린다. 두 도구는 맨 위 칸이 객체 배열(`trades`, `proposals`) 하나라 면제받을 칸이 없다. 체결과 노드를 가리키는 UUID 는 카드가 원래 가리지 않고, 상시 허락을 연 도구라 가려진 글이 있어도 승인할 수 있다 |
| `arg_labels` | `trades` 는 「체결」, `proposals` 는 「제안」 | 승인 카드의 칸 이름이다 |

### 도구의 인자와 결과

인자는 서버가 부르기 전에 계약의 범위로 검사하고, 어기면 부르지 않고 `WORKBENCH_INVALID_INPUT` 으로 끝낸다.

| 도구 | 인자 | 쿼리 |
| --- | --- | --- |
| `search_nodes` | `q`(100자까지), `types`(쉼표로 이은 타입 키), `page`(0부터), `size`(기본 20, 1~50) | 빈 `q`, `types` 는 보내지 않는다. `page`, `size` 는 늘 보낸다 |
| `get_node`, `trace_node` | `id` | UUID 모양만 받는다. 경로에 들어가는 값이라 다른 경로로 새지 않게 한다 |
| `get_neighborhood` | `id`, `hops`(1이나 2, 기본 1) | `id`, `hops` |
| `lookup_security` | `ticker`(영문, 숫자, 점, 하이픈 32자까지), `market`(선택) | `market` 은 대문자로 바꿔 계약의 값(`KR`, `US`, `KOSPI`, `KOSDAQ`, `NASDAQ`, `NYSE`, `AMEX`, `KR_ETC`, `US_ETC`, `KRX_KOSPI`, `KRX_KOSDAQ`, `OTHER`)만 보낸다 |
| `list_my_knowledge` | `level`(쉼표로 이은 `KNOWN`, `UNSURE`, `LEARNING`), `page`, `size` | 빈 `level` 은 보내지 않는다 |
| `list_my_trades` | `ticker`(32자까지), `from`, `to`(오프셋이 붙은 ISO-8601 시각), `page`, `size` | 빈 값은 보내지 않는다 |
| `list_my_proposals` | `status`(`PENDING`, `ACCEPTED`, `REJECTED` 가운데 하나), `page`, `size` | `status` 는 대문자로 바꿔 보낸다 |

`lookup_security` 는 토스증권 도구가 준 종목 코드와 시장 코드를 그대로 받는다. 시장 코드를 워크벤치의 시장으로 옮기는 일은 워크벤치가 한다.

### 체결 기록

`record_trades` 는 `trades` 에 1~50건을 받아 `{"trades": [...]}` 로 보낸다. 토스증권의 주문 하나(`Order`)가 체결 하나다.

| 계약의 칸 | 도구 인자 | 토스증권의 칸 |
| --- | --- | --- |
| `broker` | `broker`(선택, 기본 `TOSS`, `OTHER`) | 없다 |
| `externalId` | `externalId` | `orderId`. 토스증권은 체결 식별자를 따로 주지 않는다 |
| `side` | `side` | `side` |
| `market` | `market` | `get_stocks` 의 `market`. 모르면 `currency` 가 `KRW` 면 `KR`, `USD` 면 `US` |
| `ticker` | `ticker` | `symbol` |
| `name` | `name`(선택) | `get_stocks` 나 보유 종목의 이름 |
| `quantity` | `quantity` | `execution.filledQuantity` |
| `price` | `price` | `execution.averageFilledPrice` |
| `currency` | `currency` | `currency` |
| `amount` | `amount` | `execution.filledAmount` |
| `fee` | `commission`, `tax`(선택) 또는 `fee`(선택) | `execution.commission`, `execution.tax` |
| `executedAt` | `executedAt` | `execution.filledAt` |

- 체결이 끝난 상태(`FILLED`, `CANCELED`, `REJECTED`, `REPLACED`) 가운데 `filledQuantity` 가 0 보다 큰 주문만 넣게 도구 설명에 적었다. 토스증권 계약이 앞의 셋 밖의 셋도 `filledQuantity` 로 부분 체결을 보라고 한다. 이미 있는 체결은 워크벤치가 고치지 않으므로, 체결이 끝나지 않은 주문을 넣으면 그 뒤의 체결이 반영되지 않는다
- 선택 칸(`broker`, `name`, `commission`, `tax`, `fee`)은 `null` 이나 빈 글이면 없는 값으로 본다. 토스증권의 `commission`, `tax` 는 `null` 일 수 있다
- 수와 금액은 토스증권의 decimal 글이나 JSON 수를 받는다. JSON 수로 보냈을 때 자리가 바뀌지 않는 값만 받고, 지수 표기와 음수를 거절한다. `quantity` 는 0 보다 커야 한다
- `commission` 과 `tax` 는 서버가 소수 자리까지 정확히 더해 `fee` 로 보낸다. 모델이 더하지 않게 한다. `fee` 를 직접 주면 둘과 함께 줄 수 없다
- 표 밖의 칸(계좌 번호, 계좌 순번 등)이 있으면 보내지 않고 `WORKBENCH_INVALID_INPUT` 으로 끝낸다
- `side`, `market`, `currency`, `ticker` 는 대문자로 바꾸고, 글은 앞뒤 공백을 지워 보낸다. `executedAt` 은 받은 글 그대로 보낸다
- 워크벤치가 `(broker, externalId)` 로 이미 있는 것을 찾으므로 같은 체결을 다시 보내도 된다. 결과의 `status` 가 `created` 나 `existing` 이다

### 제안

`submit_proposals` 는 `proposals` 에 1~20건을 받아 `{"proposals": [...]}` 로 보낸다.
종류(`CREATE_NODE`, `CREATE_RELATIONSHIP`, `LINK_TRADE_DECISION`, `STUDY_NOTE`)별 `payload` 의 설명과 예시는 도구 설명에 둔다.

- `kind` 는 대문자로 바꾸고 계약의 넷만 받는다. `payload` 는 객체인지만 보고 종류별 규칙은 워크벤치가 본다
- `rationale` 은 앞뒤 공백을 지워 1~2000자, `dedupeKey` 는 1~100자다. UTF-16 단위로 센다
- **`dedupeKey` 를 꼭 받는다.** 계약에서는 선택이지만, 결과를 모르는 실패 뒤에 같은 제안이 두 번 생기지 않게 이 도구가 좁혔다
- `evidence` 는 선택이다. `nodeIds`, `tradeIds` 는 UUID 20개, 50개까지, `urls` 는 `http`, `https` 절대 주소 10개까지다. 빠진 목록은 빈 배열로 채워 보낸다
- 표 밖의 칸이 있으면 보내지 않고 거절한다

### 쓰기 공통

- 요청 본문이 64KiB 를 넘으면 부르지 않고 `WORKBENCH_PAYLOAD_TOO_LARGE` 로 끝낸다
- 쓰기는 다시 부르지 않는다. 보낸 뒤 시간 초과나 연결 끊김으로 답을 받지 못했거나, `200` 인데 본문을 읽지 못했거나, 워크벤치가 되돌렸다고 밝히지 않은 `5xx`(`code` 가 `QUERY_TIMEOUT`, `DATABASE_UNAVAILABLE` 이 아닌 것)를 받으면 `WORKBENCH_WRITE_UNKNOWN`(`outcome_unknown`)으로 끝낸다. 앞단 프록시가 낸 `502`, `504` 나 커밋 뒤의 `500` 은 저장됐을 수 있다. 스킬은 조회 도구로 저장됐는지 본 뒤에만 같은 값으로 다시 보내게 한다
- 워크벤치는 한 요청을 전부 저장하거나 전부 거절한다. 결과 모름이 아닌 실패면 아무것도 저장되지 않았다

결과는 워크벤치 응답의 JSON 을 그대로 돌려준다. `get_connection` 만 `user`, `token`, `rateLimitPerMinute`, `writeRateLimitPerMinute` 네 칸으로 줄인다. `token` 에 `scope` 가 있다.
워크벤치가 둔 잘림 표시(`relationsTruncated`, `claimsTruncated`, `matchesTruncated`, `decisionsTruncated`, `valuationsTruncated`, `scenariosTruncated`, `truncated`, `dependentsTruncated`)는 바꾸지 않는다.
워크벤치는 상세의 속성 글을 자르지 않는다. 커넥터는 글 하나가 20,000자를 넘으면 잘라 끝에 `…` 를 붙이고 결과 맨 위에 `textTruncated: true` 를 둔다.
응답 본문이 4 MiB 를 넘으면 읽지 않고 `WORKBENCH_UNAVAILABLE` 로 끝낸다.

### 오류

오류는 코드만 내고 워크벤치의 오류 글은 싣지 않는다.

| 코드 | 언제 | 공통 어휘 | 복구 |
| --- | --- | --- | --- |
| `WORKBENCH_UNAUTHORIZED` | `401`. 토큰이 없거나 형식이 틀렸으면 부르지 않고 같은 코드로 끝낸다 | `credential_rejected` | `reconnect` |
| `WORKBENCH_FORBIDDEN` | `403` 가운데 아래 범위 부족이 아닌 것 | `forbidden` | |
| `WORKBENCH_INSUFFICIENT_SCOPE` | `403 INSUFFICIENT_SCOPE`. 「조회만」 토큰으로 쓰는 도구를 불렀다 | `forbidden` | `reconnect` |
| `WORKBENCH_INVALID_INPUT` | `400`, 또는 서버가 부르기 전에 인자를 거절했다. `400` 은 `item_index`(몇 번째 항목), `fields`(필드 경로), `rule`(온톨로지 규칙 이름)을 싣는다 | `invalid_input` | `fix_input` |
| `WORKBENCH_NOT_FOUND` | `id` 를 받는 도구(`get_node`, `trace_node`, `get_neighborhood`)의 `404`. 없거나 보관한 항목이다. 쓰기의 `404` 는 `details.index` 가 있을 때 이 코드이고 `item_index` 를 싣는다 | `invalid_input` | `fix_input` |
| `WORKBENCH_CONFLICT` | 쓰기의 `409` 가운데 아래 둘이 아닌 것(`ARCHIVED`, `DUPLICATE` 등). `item_index` 를 싣는다 | `invalid_input` | `fix_input` |
| `WORKBENCH_PROPOSAL_LIMIT` | `409 PROPOSAL_LIMIT`. `details.max` 를 `max_pending`, `details.pending` 을 `pending` 정수로 싣는다 | `unavailable` | |
| `WORKBENCH_PAYLOAD_TOO_LARGE` | `413`, 또는 보내기 전에 본문이 64KiB 를 넘었다 | `invalid_input` | `fix_input` |
| `WORKBENCH_RATE_LIMITED` | `429`. 1분 60번이나 쓰기 1분 10번을 넘겼다. `Retry-After` 초를, 없으면 `details.retryAfterSeconds` 를 `retry_after_seconds` 정수로 싣는다 | `unavailable` | `retry_later` |
| `WORKBENCH_WRITE_UNKNOWN` | 쓰기를 보냈지만 답을 받지 못했거나, 되돌렸다고 밝히지 않은 `5xx` 를 받았다 | `outcome_unknown` | |
| `WORKBENCH_NOT_CONFIGURED` | 운영 값이 없거나 `https://` 주소가 아니다. `id` 가 없는 경로의 `404` 도 기준 주소가 틀린 것이라 이 코드다 | `unavailable` | |
| `WORKBENCH_UNAVAILABLE` | 그 밖의 상태(`409 TRANSIENT_CONFLICT`, 조회의 `5xx`, 쓰기의 `503 QUERY_TIMEOUT`, `503 DATABASE_UNAVAILABLE` 포함), 다른 곳으로 넘기는 응답, 조회의 시간 초과와 연결 실패, JSON 객체가 아닌 응답 | `unavailable` | `retry_later` |

`item_index`, `max_pending`, `pending`, `retry_after_seconds` 는 `connector.json` 의 `errors` 항목에 `details` 로 선언해 승인한 실행의 실패에서도 에이전트에 닿는다.
`fields` 와 `rule` 은 글이라 대화 허락이나 상시 허락으로 바로 실행한 호출의 결과에만 남는다([ADR-092](../adr/ADR-092-승인한-실행의-실패는-커넥터가-선언한-오류-코드와-복구-어휘와-정수-세부만-에이전트까지-전한다.md)).
`fields` 는 `fieldErrors` 의 키 가운데 필드 경로 모양(`trades[2].quantity`)만 20개까지 옮기고 사유 글은 버린다. `rule` 은 영문 소문자와 하이픈 모양만 옮긴다.

`401` 을 받으면 스킬이 「워크벤치의 더보기 → 연동 토큰에서 다시 발급해 외부 서비스 연결에서 이 연결을 고치라」 고 안내한다.
`403 INSUFFICIENT_SCOPE` 를 받으면 스킬이 「워크벤치에서 『조회와 기록』 토큰을 새로 발급해 외부 서비스 연결에서 이 연결을 고치라」 고 안내한다. 범위는 발급한 뒤 바꿀 수 없다.
`429` 는 다시 부르지 않는다. 토큰 하나의 한도가 1분에 60번, 쓰기는 10번이라 같은 호출을 되풀이하면 그 1분을 모두 쓴다.

## 서버와 검사

`src/server.ts` 가 stdio MCP 서버이고 `dist/financial-workbench-mcp.js` 로 묶는다. 생성 entry 는 Git 에서 추적하지 않는다.
요청 하나의 제한 시간은 8초다. 대시보드가 확인 도구를 기다리는 10초 안에 끝나게 한다.
다른 곳으로 넘기는 응답(`3xx`)은 따라가지 않는다. 토큰이 다른 호스트로 따라가지 않게 한다.

검사는 `tests/` 에서 로컬 HTTP 대역으로 돈다. 실제 워크벤치에 닿지 않는다.
대역은 계약의 `/api/v1` 조회 경로와 두 쓰기 `POST` 만 허용 목록으로 갖고, 목록 밖의 메서드나 경로가 오면 그 테스트가 실패한다.
도구마다의 경로와 쿼리, Bearer 헤더, `401`, `429` 와 `Retry-After`, 잘림 표시, 긴 글 자르기, 다른 곳으로 넘기는 응답, 시간 초과, 부르기 전의 인자 거절, 기준 주소 검사를 본다. 결과에 토큰과 워크벤치의 오류 글이 없는지도 본다.
쓰는 도구는 토스증권 칸을 옮긴 본문, 수수료와 세금 더하기, 계좌 칸 거절, 본문 상한, 범위 부족 `403`, `409 PROPOSAL_LIMIT`, `413`, `429`, `400` 필드 오류, `404` 항목 번호, `created` 와 `existing` 결과, 결과 모름을 본다.

## 보안

- **공유 지식을 바로 바꾸는 도구가 없다.** 외부 글이 모델을 속여도 노드와 관계, 이해 표시는 사람이 제안을 승인해야 바뀐다. 속은 모델이 바로 할 수 있는 것은 주인만 보는 체결 기록을 더하는 것과 대기 제안을 쌓는 것이다. 체결은 주인이 지우고, 제안은 거절한다. 대기 제안은 200개에서 막힌다.
- 읽은 내용을 다른 도구(셸, 웹)로 내보내는 길은 [READ 데이터 흐름](connector-policy.md) 의 RF-08, RF-09 와 같다. 쓰는 도구는 주인의 워크벤치로만 보내므로 `outbound` 가 거짓이다.
- 계좌 정보는 보내지 않는다. 도구가 계약 밖의 칸을 거절한다.
- 워크벤치의 메모, 주장, 근거 문서 발췌는 사용자와 다른 사람이 쓴 글이다. 스킬이 자료로만 다루게 하고, 그 글이 시키는 주문이나 전송을 하지 않게 한다.
- 토큰은 그 사용자의 역할로 인증된다. 새면 그 사용자가 볼 수 있는 워크벤치 내용이 읽히고, 「조회와 기록」 토큰이면 체결 기록과 제안이 더해진다. 워크벤치에서 그 토큰을 폐기하면 바로 막힌다.
- 토큰과 기준 주소를 결과, 로그, 오류 글에 싣지 않는다. 서버는 로그를 남기지 않는다.
- 기준 주소는 운영자가 정한다. 사용자가 주소를 넣는 칸이 없어 내부망 주소로 토큰을 보내게 할 수 없다.
- 투자 결정과 근거, 공부 목록, 체결의 금액과 수량이 모델 공급자에게 간다. 사용자가 받아들였다.

## 실제 계정으로 확인할 것

배포 뒤 소유자가 자기 워크벤치 계정으로 한 번 확인한다. 실행 방법과 운영 값은 운영 저장소가 갖는다.

1. 운영 목록에 기준 주소를 넣은 뒤 카탈로그에 커넥터가 보이는지
2. 워크벤치에서 발급한 토큰으로 등록하면 확인 도구가 표시 이름과 토큰 이름을 읽는지, 폐기한 토큰은 거절되는지
3. 토스증권을 붙인 에이전트에 함께 붙는지
4. 대화에서 보유 종목 하나를 물으면 토스증권의 보유와 워크벤치의 결정, 시나리오가 함께 나오는지
5. 그 결정으로 근거 따라가기를 물으면 비어 있는 단계가 그렇다고 나오는지
6. 워크벤치의 토큰 목록에서 그 토큰의 마지막 사용 시각이 바뀌었는지
7. 「조회와 기록」 토큰으로 토스증권 체결 하나를 기록하면 워크벤치의 내 체결에 금액과 수량이 보이고, 같은 체결을 다시 시키면 `existing` 으로 끝나는지
8. 체결과 결정을 잇는 제안이 워크벤치의 「더보기 → 제안」 에 대기로 보이고, 승인하면 그 체결의 `decisions` 에 결정이 보이는지
9. 정정된 주문(`REPLACED`)의 체결분이 정정으로 생긴 새 주문의 `execution` 에 다시 담기지 않는지. 다시 담기면 두 번 기록되므로 도구 설명에서 `REPLACED` 를 뺀다
10. 「조회만」 토큰으로 바꾸면 기록이 범위 부족으로 끝나고 새 토큰을 발급하라는 안내가 나오는지
