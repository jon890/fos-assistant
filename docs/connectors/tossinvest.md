# 토스증권 커넥터

사용자의 토스증권 계좌를 읽는 범용 커넥터다. 코드는 [`hermes/connectors/tossinvest/`](../../hermes/connectors/tossinvest) 에 있다.
이 문서는 도구와 정책, 보안, 설정 안내, 실제 계정으로 확인하는 절차를 갖는다.
결정은 [ADR-20261008 / tossinvest-connector](../../hermes/docs/adr/ADR-20261008-tossinvest-connector.md) 에 있다.
커넥터 공통 계약은 [커넥터 연결](../connectors.md) 과 [커넥터 도구 정책](../backend/connector-tool-policy.md) 이 갖는다.

**이 커넥터는 주문하지 않는다.** 주문, 정정, 취소 도구가 없다. 매수와 매도는 사용자가 토스증권 앱이나 웹에서 한다.

## 등록 칸

| 칸 | env | 비밀 | 무엇 |
| --- | --- | --- | --- |
| `client_id` | `TOSSINVEST_CLIENT_ID` | 아니다 | 토스증권 WTS 의 설정 > Open API 에서 받은 client ID |
| `client_secret` | `TOSSINVEST_CLIENT_SECRET` | 그렇다 | 그 client 의 secret |
| `account` | `TOSSINVEST_ACCOUNT_SEQ` | 아니다 | 읽을 계좌. 선택지 도구 `list_accounts` 가 계좌 순번과 끝 네 자리를 보이고, 계좌가 하나면 화면이 고른다 |

셋 다 필수다. 운영자가 주는 값(`operator_env`)은 없다.
확인 도구는 `list_accounts` 다. 등록할 때 두 값으로 토큰을 받고 계좌 목록을 읽어 보고, 읽히면 저장한다.

| 선언 | 값 | 까닭 |
| --- | --- | --- |
| `single_binding` | 참 | 토스증권은 client 마다 유효한 토큰이 하나다. 두 에이전트에 붙이면 서로의 토큰을 무효로 만든다 |
| `sandbox_required` | 참 | 키에 권한 범위가 없어 조회용 키로도 주문할 수 있다. 셸이 `.env` 를 읽는 profile 에 붙이지 않는다 |

규칙은 [ADR-20261008 / connector-binding-guards](../adr/ADR-20261008-connector-binding-guards.md) 가 갖는다.

## 도구와 정책

MCP 서버 이름은 `tossinvest` 다. 도구는 모두 `READ` 이고 승인 없이 돈다. 서버의 도구에도 `readOnlyHint` 를 참으로 둔다.

| 도구 | 부르는 API | 하는 일 |
| --- | --- | --- |
| `list_accounts` | `GET /api/v1/accounts` | 계좌 순번, 계좌 유형, 계좌번호 끝 네 자리를 읽는다 |
| `get_quotes` | `GET /api/v1/prices`, `GET /api/v1/stocks` | 종목 20개까지의 현재가와 이름을 읽는다 |
| `get_holdings` | `GET /api/v1/holdings` | 보유 종목과 평가, 손익을 읽는다 |
| `get_buying_power` | `GET /api/v1/buying-power`, `GET /api/v1/sellable-quantity` | 통화별 주문 가능 현금과, 종목을 주면 그 종목의 매도 가능 수량을 읽는다 |
| `list_orders` | `GET /api/v1/orders` | 미체결이나 끝난 주문을 기간으로 읽는다 |

계좌 데이터(보유, 잔고, 주문)도 `READ` 다. 연결이 붙은 에이전트는 주인만 쓰고, 메일 본문을 `READ` 로 둔 Gmail 커넥터와 같은 판단이다. 먼저 살펴보기와 예약 작업이 계좌를 읽는다.

### 도구의 인자와 결과

| 도구 | 인자 | 결과 |
| --- | --- | --- |
| `list_accounts` | 없음 | `{accounts: [{account_seq, account_type, label}]}`. `label` 은 `종합매매 ****1234` 꼴이다. 계좌번호 원문은 싣지 않는다. 순번이 1~10자리 숫자가 아닌 계좌는 뺀다 |
| `get_quotes` | `symbols`: 쉼표로 이은 종목 코드나 티커 1~20개 | `{quotes: [{symbol, name, last_price, currency, timestamp}]}` |
| `get_holdings` | 없음 | `{total, items}`. 금액은 공제 전(`market_value`, `profit_loss`, `profit_loss_rate`)과 비용 공제 후(`..._after_cost`)를 함께 싣는다. `total` 의 금액은 `{krw, usd}` 이고, `items` 의 금액은 그 종목의 거래 통화 기준 글 하나다. 금액과 수량은 API 가 준 10진수 글 그대로다 |
| `get_buying_power` | `currency`: `KRW` 나 `USD`. `symbol`: 선택 | `{currency, cash_buying_power, sellable_quantity}`. `sellable_quantity` 는 `symbol` 을 주지 않으면 null 이다 |
| `list_orders` | `status`: `OPEN` 이나 `CLOSED`. `from`, `to`: 선택, `YYYY-MM-DD`(한국 시각). `symbol`: 선택 | `{orders, has_more}`. 아래 「주문 내역」 |

종목 기호는 `^[A-Za-z0-9.-]{1,12}$` 만 받는다. 종목 이름은 100자, 그 밖에 서비스가 준 글은 64자(코드 포인트)로 자르고, 글과 숫자가 아닌 값은 null 로 둔다. 가격, 금액, 수량, 비율은 자르면 값이 바뀌므로 자르지 않는다. 10진수 꼴이 아니거나 64자를 넘으면 null 로 둔다. 날짜는 `from` 이 `to` 보다 늦거나 기간이 366일을 넘으면 `TOSSINVEST_INVALID_INPUT` 이다.

### 주문 내역

주문 100건까지를 결과에 담고 `has_more` 로 더 있는지 알린다. 끝난 주문(`CLOSED`)은 한 쪽(100건)만 읽는다. 미체결(`OPEN`)은 API 가 전량을 주므로 앞 100건만 담는다.
금액과 수량은 API 가 준 10진수 글 그대로 둔다. 미국 주식의 달러 금액과 소수점 수량이 있어 정수로 바꾸면 값이 바뀐다.

| `orders[]` 의 칸 | 뜻 |
| --- | --- |
| `order_id`, `symbol`, `side`, `order_type`, `status`, `currency` | API 값 그대로 |
| `price`, `quantity`, `order_amount` | API 가 준 10진수 글. 없으면 null |
| `filled_quantity`, `average_filled_price`, `filled_amount`, `commission`, `tax` | `execution` 의 값. 10진수 글이거나 null |
| `ordered_at`, `filled_at`, `canceled_at`, `settlement_date` | API 값 그대로 |

### 토큰

- 토큰은 프로세스 메모리에만 둔다. `expires_in` 에서 60초 뺀 시각까지 쓴다. 60초 이하면 `expires_in` 의 절반과 5초 가운데 긴 동안 쓰되 `expires_in` 을 넘기지 않고, 값이 없으면 5초 동안 쓴다. 매 호출이 토큰을 새로 받아 서로를 무효로 만들지 않게 하기 위해서다.
- `401 token-revoked` 나 `expired-token` 을 받으면 토큰을 한 번 새로 받고 그 호출을 한 번만 다시 보낸다. 한 프로세스 안의 재발급은 한 번에 하나다.
- 다시 보낸 호출도 `token-revoked` 면 다른 프로세스와 토큰을 다툰 것이라 `TOSSINVEST_UNAVAILABLE`(잠시 뒤 다시)로 끝낸다. 자격 증명이 틀린 것이 아니다.
- `invalid-token` 과 `invalid_client` 는 다시 받지 않는다.
- 확인 도구와 선택지 호출은 새 프로세스라 토큰을 새로 받는다. 그때 Hermes 쪽 프로세스의 토큰이 무효가 되고 다음 호출이 한 번 다시 받는다.

### 계좌 조회 호출 간격

공식 [OpenAPI 스펙](https://openapi.tossinvest.com/openapi-docs/latest/openapi.json)은 계좌 관련 조회를 아래 API 그룹으로 나눈다.

| 엔드포인트 | 공식 호출 한도 그룹 | 공식 초당 한도 | 커넥터의 최소 간격 |
| --- | --- | --- | --- |
| `GET /api/v1/accounts` | `ACCOUNT` | 1회 | 1,000ms |
| `GET /api/v1/holdings` | `ASSET` | 5회 | 200ms |
| `GET /api/v1/buying-power`, `GET /api/v1/sellable-quantity` | `ORDER_INFO` | 6회, 09:00~09:10 KST에는 3회 | 334ms |
| `GET /api/v1/orders` | `ORDER_HISTORY` | 5회 | 200ms |

한도 수치는 공식 [연동 가이드의 Rate Limits](https://openapi.tossinvest.com/openapi-docs/overview.md#rate-limits)에서 확인했다. 공식 한도는 사전 공지 없이 바뀔 수 있다.
커넥터는 프로세스 공용 큐를 그룹별로 두어 같은 그룹의 요청을 한 번에 하나씩 보낸다. 요청이 끝난 뒤 다음 요청까지 표의 간격을 둔다. `ORDER_INFO`는 시간대와 관계없이 피크 시간 한도를 적용한다. 새 그룹의 한도를 알 수 없으면 1초 간격을 쓴다.
`get_buying_power`의 두 조회와 서로 다른 호출의 같은 그룹 요청, 토큰 재발급 뒤 재송도 이 큐를 거친다. 다른 그룹과 시세, 종목 정보, 토큰 발급은 서로의 큐를 기다리지 않는다.

계좌 조회가 429로 거절되면 같은 그룹의 큐에서 최소 1초 기다려 한 번만 다시 보낸다. `Retry-After`가 초 단위 수치로 더 긴 대기를 요구하면 그 시간도 지킨다. 재송도 429면 `TOSSINVEST_RATE_LIMITED`로 끝낸다. 다른 프로세스와는 큐를 공유하지 않으므로 그 사이의 충돌은 이 재시도로 대응한다.

### 오류

| 코드 | 공통 어휘 | 복구 | 언제 |
| --- | --- | --- | --- |
| `TOSSINVEST_UNAUTHORIZED` | `credential_rejected` | `reconnect` | 토큰 발급이 400, 401, `access_denied` 아닌 403 으로 거절됐다(client ID 나 secret 이 틀렸거나 형식이 맞지 않는다), API 가 `token-revoked` 와 `expired-token` 밖의 401 로 답했다 |
| `TOSSINVEST_IP_NOT_ALLOWED` | `forbidden` | `reconnect` | API 의 `ip-not-allowed`, 토큰 발급의 403 `access_denied`. 허용 IP 가 바뀌었을 수 있다 |
| `TOSSINVEST_FORBIDDEN` | `forbidden` | | 그 밖의 403 |
| `TOSSINVEST_ACCOUNT_NOT_FOUND` | `invalid_input` | `reconnect` | 고른 계좌 순번이 없다. 상태와 관계없이 `account-not-found` 면 이 코드다 |
| `TOSSINVEST_INVALID_INPUT` | `invalid_input` | `fix_input` | 인자가 형식에 맞지 않는다, 없는 종목이다 |
| `TOSSINVEST_RATE_LIMITED` | `unavailable` | `retry_later` | 계좌 조회의 한 번 재시도 뒤에도 429, 또는 계좌 조회 밖의 429 |
| `TOSSINVEST_NETWORK`, `TOSSINVEST_UNAVAILABLE` | `unavailable` | `retry_later` | 닿지 못했거나 시간이 지났다, 5xx 와 그 밖의 4xx, 다시 보낸 호출도 `token-revoked` 였다 |

결과와 오류에 토큰, secret, 계좌번호 원문, 서비스가 준 오류 원문을 싣지 않는다.

## 서버와 검사

`src/server.ts` 가 stdio MCP 서버이고 `dist/tossinvest-mcp.js` 로 묶어 커밋한다. 검사는 `tests/` 에서 로컬 HTTP 대역으로 돈다. 실제 서비스에 닿지 않는다.
대역은 받은 요청의 메서드와 경로를 허용 목록과 견준다. 목록은 위 다섯 도구가 부르는 `GET` 과 `POST /oauth2/token` 뿐이다. `POST /api/v1/orders` 같은 다른 경로가 오면 시험이 실패한다.

## 보안

- **돈이 움직이는 도구가 없다.** 그러나 키 자체는 주문할 수 있다. 키가 새면 허용 IP 에서 주문할 수 있다. 실행 공간도 같은 공인 IP 로 나가므로 허용 IP 는 셸에 샌 키를 막지 못한다. 그래서 `sandbox_required` 다.
- 외부 글이 모델을 속였을 때 닿는 범위: 이 커넥터에는 쓰는 도구가 없다. 속은 모델이 할 수 있는 것은 읽은 계좌 데이터를 다른 도구(셸, 웹)로 내보내는 것이다. 그 길은 [READ 데이터 흐름](../read-data-flow.md) 의 RF-08, RF-09 와 같다.
- 보유와 잔고가 모델 공급자에게 간다(RF-20). 사용자가 받아들였다.
- 토스증권 이용 약관은 시세의 제3자 제공을 금지한다. 모델 공급자 전송이 여기 드는지는 확인하지 못했다.

## 설정 안내

### 1. client 받기

1. 토스증권 WTS 에 로그인해 설정 > Open API 에서 client 를 만든다. **이 커넥터 전용 client 를 따로 만든다.** 같은 client 를 다른 프로그램에서 쓰면 서로의 토큰을 무효로 만든다.
2. 같은 화면의 허용 IP 관리에 Hermes 가 밖으로 나가는 공인 IP 를 등록한다. 어떤 IP 인지는 운영자에게 묻는다.

### 2. 연결 화면에 넣기

client ID 와 secret 을 넣으면 계좌 목록이 뜬다. 읽을 계좌를 고르고 저장한다.
그 뒤 실행 공간이 있는 자기 비공개 에이전트 하나에 붙인다. 실행 공간이 없는 에이전트에는 붙지 않는다.

### 3. 허용 IP 가 바뀌었을 때

가정 회선은 공인 IP 가 바뀔 수 있다. 바뀌면 모든 호출이 `TOSSINVEST_IP_NOT_ALLOWED` 로 실패하고, 에이전트가 허용 IP 를 확인하라고 안내한다.
WTS 에서 허용 IP 를 고친 뒤 연결 화면에서 연결 확인을 누른다. IP 가 바뀌었다는 알림은 운영이 한다.
연결 상태는 저절로 바뀌지 않는다. 연결 확인이 같은 오류로 실패해야 연결이 `PENDING` 이 된다.

### 4. 끊기

연결을 해제하면 보관 파일과 붙은 profile 의 값이 지워진다. 토스증권 WTS 에서 그 client 도 지운다.

## 실제 계정으로 확인하기

배포 뒤 소유자가 자기 계정으로 한 번 확인한다. 계좌번호, 금액, 종목은 어디에도 적지 않는다.

1. 연결 화면에서 등록하고, 계좌가 끝 네 자리로 보이는지 본다
2. 실행 공간이 없는 에이전트에 붙이면 거절되는지, 있는 에이전트에 붙는지 본다
3. 같은 연결을 두 번째 에이전트에 붙이면 거절되는지 본다
4. 대화에서 보유 종목, 현재가, 주문 가능 현금, 이번 달 끝난 주문을 묻는다. 보유 종목의 손익과 비율 칸이 null 이 아닌지 본다. 서비스가 `+` 부호나 지수 꼴로 주면 10진수 규칙에 걸려 null 이 된다
5. 연결 확인을 누른 직후 대화에서 다시 보유 종목을 물어, 토큰을 한 번 다시 받고 성공하는지 본다
