# 토스증권 커넥터

사용자의 토스증권 계좌를 읽는 범용 커넥터의 개발 문서다.

covers: `hermes/connectors/tossinvest/`

코드는 [`hermes/connectors/tossinvest/`](../../hermes/connectors/tossinvest) 에 있다.
이 문서는 도구와 정책, 보안, 연결 유지, 실제 계정으로 확인하는 절차를 갖는다. 사용자 안내는 [토스증권 커넥터 README](../../hermes/connectors/tossinvest/README.md) 가 갖는다.
결정은 [ADR-20261008 / tossinvest-connector](../../hermes/docs/adr/ADR-20261008-tossinvest-connector.md) 에 있다.
커넥터 공통 계약은 [커넥터 연결](connector.md) 과 [커넥터 도구 정책](connector-policy.md) 이 갖는다.

서비스는 [토스증권 공식 사이트](https://www.tossinvest.com/) 에 있다. API 설정 근거는 [토스증권 개발자 문서](https://developers.tossinvest.com/)다.

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

MCP 서버 이름은 `tossinvest` 다. 도구 33개는 모두 `READ` 이고 승인 없이 돈다. SDK 설명은 connector.json의 본문을 그대로 쓴다. 서버의 도구에도 `readOnlyHint` 를 참으로 둔다.

| 도구 | 부르는 API | 하는 일 |
| --- | --- | --- |
| `list_accounts` | `GET /api/v1/accounts` | 계좌 순번, 계좌 유형, 계좌번호 끝 네 자리를 읽는다 |
| `get_quotes` | `GET /api/v1/prices`, `GET /api/v1/stocks` | 종목 200개까지의 현재가와 이름을 읽는다 |
| `get_stocks` | `GET /api/v1/stocks` | 종목 200개까지의 상세 참조 정보를 읽는다 |
| `list_stocks` | `GET /api/v1/stocks/all` | 시장별 종목 전체 목록을 읽는다 |
| `get_stock_warnings` | `GET /api/v1/stocks/{symbol}/warnings` | 종목 하나의 유의사항을 읽는다 |
| `get_holdings` | `GET /api/v1/holdings` | 보유 종목과 평가, 손익을 읽는다 |
| `get_sellable_quantity` | `GET /api/v1/sellable-quantity` | 종목 하나의 매도 가능 수량을 읽는다 |
| `get_buying_power` | `GET /api/v1/buying-power`, `GET /api/v1/sellable-quantity` | 통화별 주문 가능 현금과, 종목을 주면 그 종목의 매도 가능 수량을 읽는다 |
| `list_orders` | `GET /api/v1/orders` | 미체결이나 끝난 주문을 기간으로 읽는다 |
| `get_orderbook` | `GET /api/v1/orderbook` | 한 종목의 매수·매도 호가와 잔량을 읽는다 |
| `get_trades` | `GET /api/v1/trades` | 한 종목의 당일 최근 체결 내역을 읽는다 |
| `get_price_limits` | `GET /api/v1/price-limits` | 한 종목의 당일 상하한가를 읽는다 |
| `get_candles` | `GET /api/v1/candles` | 한 종목의 분봉·일봉 캔들을 한 페이지 읽는다 |
| `get_exchange_rate` | `GET /api/v1/exchange-rate` | KRW와 USD 사이의 참고용 표시 환율을 읽는다 |
| `get_market_calendar` | `GET /api/v1/market-calendar/KR`, `GET /api/v1/market-calendar/US` | 국내 또는 미국 시장의 전일·당일·익일 운영 일정을 읽는다 |
| `list_sectors` | `GET /api/v1/sectors` | 전체 섹터 목록과 계층을 읽는다 |
| `get_sector_rankings` | `GET /api/v1/sectors/rankings` | 시장·기간·기준별 섹터 랭킹을 읽는다 |
| `get_sector` | `GET /api/v1/sectors/{sectorId}` | 섹터 기본 정보와 기간별 등락률을 읽는다 |
| `get_sector_stocks` | `GET /api/v1/sectors/{sectorId}/stocks` | 섹터와 하위 섹터에 속한 보통주 전체를 읽는다 |
| `get_sector_etfs` | `GET /api/v1/sectors/{sectorId}/etfs` | 관련 ETF의 symbol과 name을 읽는다 |
| `list_conditional_orders` | `GET /api/v1/conditional-orders` | 진행 중이나 종료된 조건주문을 한 페이지 읽는다 |
| `get_conditional_order` | `GET /api/v1/conditional-orders/{conditionalOrderId}` | 조건주문 하나의 상세를 읽는다 |
| `get_rankings` | `GET /api/v1/rankings` | KR/US 주식의 6종 랭킹을 7종 기간으로 읽는다. TOP의 realtime 4개 조합은 거절한다 |
| `get_market_indicator_prices` | `GET /api/v1/market-indicators/prices` | 국내 지수 2종과 국채 금리 6종의 시세를 읽는다 |
| `get_market_indicator_candles` | `GET /api/v1/market-indicators/{symbol}/candles` | 지수 분봉·일봉과 국채 일봉을 읽는다 |
| `get_market_indicator_investor_trading` | `GET /api/v1/market-indicators/{symbol}/investor-trading` | 코스피·코스닥 투자자별 KRW 매매대금을 읽는다 |
| `get_order` | `GET /api/v1/orders/{orderId}` | 주문 하나의 상세와 체결을 읽는다 |
| `get_commissions` | `GET /api/v1/commissions` | 전체 시장의 수수료 비율과 적용 날짜를 읽는다 |
| `get_stock_investor_trading` | `GET /api/v1/stocks/{symbol}/investor-trading` | 투자자별 매매와 외국인 보유, CFD를 읽는다 |
| `get_stock_program_trades` | `GET /api/v1/stocks/{symbol}/program-trades` | KRX 프로그램 매매를 읽는다 |
| `get_stock_short_selling` | `GET /api/v1/stocks/{symbol}/short-selling` | 공매도 수량과 금액, 비중을 읽는다 |
| `get_stock_credit_trades` | `GET /api/v1/stocks/{symbol}/credit-trades` | 개인 신용거래를 읽는다 |
| `get_stock_securities_lending` | `GET /api/v1/stocks/{symbol}/securities-lending` | 기관 대차거래를 읽는다 |

계좌 데이터(보유, 잔고, 주문)도 `READ` 다. 연결이 붙은 에이전트는 주인만 쓰고, 메일 본문을 `READ` 로 둔 Gmail 커넥터와 같은 판단이다. 먼저 살펴보기와 예약 작업이 계좌를 읽는다.

### 도구의 인자와 결과

| 도구 | 인자 | 결과 |
| --- | --- | --- |
| `list_accounts` | 없음 | `{accounts: [{account_seq, account_type, label}]}`. `label` 은 `종합매매 ****1234` 꼴이다. 계좌번호 원문은 싣지 않는다. 순번은 양의 int64 범위의 정확한 문자열이다. 잘못된 행은 전체 응답 오류로 알린다 |
| `get_quotes` | `symbols`: 쉼표로 이은 종목 코드나 티커 1~200개 | `{quotes: [{symbol, name, last_price, currency, timestamp}]}` |
| `get_stocks` | `symbols`: 쉼표로 이은 종목 1~200개 | `{result: StockInfo[], metadata}`. 국내 시장 상세와 decimal 문자열, boolean, null을 보존한다 |
| `list_stocks` | `market`: 필수. `status`, `securityType`, `commonShare`: 선택 | `{result: StockSummary[], metadata}`. 페이지 없이 전량을 반환한다 |
| `get_stock_warnings` | `symbol`: 필수, 단일 종목 | `{result: StockWarning[], metadata}`. 빈 배열은 정상이고 미존재는 입력 오류다 |
| `get_holdings` | `symbol`: 선택 | `{total, items}`. 금액은 공제 전(`market_value`, `profit_loss`, `profit_loss_rate`)과 비용 공제 후(`..._after_cost`)를 함께 싣는다. `total` 의 금액은 `{krw, usd}` 이고, `items` 의 금액은 그 종목의 거래 통화 기준 글 하나다. 금액과 수량은 API 가 준 10진수 글 그대로다 |
| `get_buying_power` | `currency`: `KRW` 나 `USD`. `symbol`: 선택 | `{currency, cash_buying_power, sellable_quantity}`. `sellable_quantity` 는 `symbol` 을 주지 않으면 null 이다 |
| `get_sellable_quantity` | `symbol`: 필수 | `{result, metadata, sellable_quantity}` |
| `list_orders` | `status`: `OPEN` 이나 `CLOSED`. `from`, `to`: 선택, `YYYY-MM-DD`(한국 시각). `symbol`, `cursor`, `limit`: 선택 | `{orders, has_more, nextCursor, hasNext}`. 아래 「주문 내역」 |
| `get_orderbook` | `symbol`: 필수 | `{result, metadata}`. 매수·매도 호가와 잔량, 통화, 데이터 시각 |
| `get_trades` | `symbol`: 필수. `count`: 기본 50, 1~50의 정수 | `{result, metadata}`. 체결가·수량·시각·통화 배열, 체결이 없으면 빈 배열 |
| `get_price_limits` | `symbol`: 필수 | `{result, metadata}`. 당일 상하한가와 통화, 시각. 미국처럼 가격제한이 없으면 가격은 null |
| `get_candles` | `symbol`, `interval`: 필수. `interval`은 `1m` 또는 `1d`. `count`: 기본 100, 1~200의 정수. `before`, `adjusted`: 선택 | `{result, metadata}`. 최신순 캔들 배열과 `nextBefore` |
| `get_exchange_rate` | `baseCurrency`, `quoteCurrency`: 서로 다른 `KRW` 또는 `USD`. `dateTime`: 선택, 초와 시간대가 있는 RFC 3339 시각 | `{result, metadata}`. 환율 한 건의 decimal 문자열과 `validFrom`, `validUntil`을 보존한다 |
| `get_market_calendar` | `market`: `KR` 또는 `US`. `date`: 선택, `YYYY-MM-DD`(미국은 미국 현지 날짜) | `{result, metadata}`. `today`, `previousBusinessDay`, `nextBusinessDay`의 세션과 휴장 null을 보존한다 |
| `get_order` | `orderId`: 필수 opaque 문자열 | `{result: Order, metadata: {}}`. 공식 13개 필드와 execution 7개 필드를 보존한다 |
| `get_commissions` | 없음 | `{result: Commission[], metadata: {}}`. 시장별 4개 필드와 배열 순서, null과 부재를 보존한다 |

모든 조회는 기존 표시 필드와 함께 공식 `result`와 `metadata`를 제공한다. 단일 조회의 metadata는 현재 `{}`다. `get_quotes`는 `{prices, stocks}`, `get_buying_power`는 `{buyingPower, sellableQuantity}`를 result에 담고 metadata도 같은 키를 쓴다. symbol 없는 buying power의 sellableQuantity와 그 metadata는 null이다.
공식 result의 문자열, decimal, boolean, null, 배열과 unknown enum을 보존한다. Account.accountSeq는 원문 int64에서 정확한 문자열로 바꾸고 accountNo는 결과를 만들기 전에 마스킹한다. 필수 필드 누락과 잘못된 타입은 일부 성공으로 숨기지 않는다.
종목 기호는 `^[A-Za-z0-9.-]+$`만 받는다. 조회 기간의 길이 제한은 없으며 실제 날짜와 from/to 순서를 검사한다. 이름 100자와 일반 문자열 64자 절단은 기존 표시 필드에만 적용한다. 공식 result, orderId, cursor와 decimal은 자르지 않는다.

### 종목 참조 정보

`get_stock_warnings`는 경로를 바꾸는 점 segment `.`과 `..`을 인증과 HTTP 요청 전에 거절한다.

`list_stocks`의 market은 KOSPI, KOSDAQ, NYSE, NASDAQ, AMEX, KR_ETC, US_ETC다. status는 SCHEDULED, ACTIVE, DELISTED이고 생략하면 공식 API가 ACTIVE를 적용한다. securityType은 STOCK, FOREIGN_STOCK, DEPOSITARY_RECEIPT, INFRASTRUCTURE_FUND, REIT, ETF, FOREIGN_ETF, ETN, STOCK_WARRANTS다. commonShare=false도 전송한다.
전체 목록은 페이지나 cursor를 만들지 않는다. 기존 1 MiB 상한을 넘으면 오류로 끝낸다. STOCK_ALL은 초당 1회이며 상세와 유의사항의 STOCK 큐와 독립적이다. 큐 대기도 기존 4초 deadline에 포함한다. 참조 정보는 화면·세션 진입 시 읽고 짧은 주기로 반복 조회하지 않는다. 유의사항의 exchange는 거래소 코드이며 상장 시장 market과 다르다. 날짜와 exchange의 null, 알 수 없는 코드를 보존한다.

### 주문 내역

get_order는 list_orders의 페이지와 별개로 주문 하나를 반환한다. orderId는 길이를 자르지 않고 Unicode를 인코딩하며 경로 구분자, percent, 제어문자와 단독 surrogate를 거절한다. get_commissions에는 marketCountry query나 필터가 없다. commissionRate는 decimal 비율이며 0.00015를 퍼센트 숫자로 바꾸지 않는다. 날짜와 시각은 공식 KST 문자열을 유지한다. 두 도구는 검증된 응답을 공용 schema 투영으로 제한해 추가 민감키를 제거한다.

끝난 주문(`CLOSED`)은 기본 20건, 최대 100건의 한 페이지를 읽고 `nextCursor`와 `hasNext`를 보존한다. 받은 cursor를 다음 호출에 그대로 넣는다. `has_more`는 CLOSED의 hasNext와 같다. 미체결(`OPEN`)은 cursor와 limit 없이 전량을 반환한다.
금액과 수량은 API 가 준 10진수 글 그대로 둔다. 미국 주식의 달러 금액과 소수점 수량이 있어 정수로 바꾸면 값이 바뀐다.

| `orders[]` 의 칸 | 뜻 |
| --- | --- |
| `order_id`, `symbol`, `side`, `order_type`, `time_in_force`, `status`, `currency` | API 값 그대로 |
| `price`, `quantity`, `order_amount` | API 가 준 10진수 글. 없으면 null |
| `filled_quantity`, `average_filled_price`, `filled_amount`, `commission`, `tax` | `execution` 의 값. 10진수 글이거나 null |
| `ordered_at`, `filled_at`, `canceled_at`, `settlement_date` | API 값 그대로 |

### 시세와 캔들

네 시세 도구의 `symbol`은 국내 종목 코드(`005930`, `0101N0`)나 미국 티커(`AAPL`, `BRK.B`) 한 개다. 계좌 헤더를 보내지 않는다. 조회 시점의 REST 응답을 읽으며 실시간 구독이나 자동 폴링은 하지 않는다.
호가와 잔량, 체결가와 수량, 캔들의 OHLCV는 공식 필드의 decimal 문자열 그대로다. 미국 가격제한의 null과 빈 배열, 데이터 미제공 시 호가 시각의 null도 보존한다. 필드 정본은 [공식 fixture](../../hermes/connectors/tossinvest/tests/fixtures/openapi-1.2.24.json)와 생성된 계약이다.

캔들의 `adjusted`는 기본 true이고 false도 query로 전달한다. `before`는 타임존이 있는 ISO 8601 시각이며 그 시각과 같거나 이전인 봉을 읽는다. 예를 들어 `2026-03-25T09:00:00+09:00`을 인자로 넣으면 커넥터가 `+`를 인코딩한다.
다음 페이지는 `result.nextBefore`를 다음 호출의 `before`로 그대로 넣는다. 마지막 페이지는 null이다. 한 호출에서 다음 페이지를 자동으로 모으지 않는다.

### 환율과 시장 일정

환율은 1분 주기로 갱신되는 참고용 표시값이며 실제 주문에 적용될 거래 환율을 보장하지 않는다. `dateTime`을 생략하면 현재 유효 환율을 요청한다. 환전과 주문은 하지 않는다.
시장 일정은 API가 반환한 3영업일 정보 그대로다. 모든 응답 시각은 KST(+09:00)이며 날짜를 계산하거나 세션을 합치지 않는다. 국내는 KRX+NXT 통합 세션이고 장전·장후 시간외종가는 제외된다. 미국은 `dayMarket`, `preMarket`, `regularMarket`, `afterMarket`을 각각 보존한다. 휴장과 부분 휴장의 null도 바꾸지 않는다.
세 조회는 `MARKET_INFO` 그룹의 공용 큐를 사용하며 최소 간격은 334ms다. 두 도구의 metadata는 현재 빈 객체다. 인자 검증은 HTTP 전송 전에 끝나며 같은 통화 쌍, 실제로 없는 날짜, 초를 생략하거나 시간대가 없는 시각과 추가 인자를 거절한다.

`connector_guard.py`는 `execution_guard`가 있을 때만 전체 선언 원문인 `declared`를 재귀 키 정렬·공백 없는 UTF-8 JSON으로 직렬화해 SHA-256을 계산한다. 도구 추가도 그 SHA를 바꾸므로 보호 선언이 있는 커넥터에서는 이전 SHA에 묶인 승인 ticket이 무효가 된다. 이 커넥터는 현재 `execution_guard`가 없고 해시 값은 null이며 조회 도구 33개의 승인 정책은 `none`이다. 금융 승인 ticket도 없으므로 이번 변경으로 무효화되는 ticket은 없다. 도구 목록 반영은 운영 저장소의 절차에 따라 연결을 수동으로 다시 동기화해야 한다. 조회 도구는 `READ`로 선언하며 금융 쓰기 권한을 열지 않는다.

### 섹터 조회

섹터 도구 5개도 공식 `result` 전체와 `metadata`를 돌려준다. `list_sectors`는 인자가 없고, 나머지는 `marketCountry: KR/US`가 필수다. `get_sector_rankings`는 `type: TOP_GAINERS/MARKET_TRADING_AMOUNT`와 `duration: 1d/1w/1mo/3mo/6mo/1y`를 받는다. 상세·주식·ETF는 목록에서 확인한 `sectorId`를 받으며 영문·숫자·밑줄·하이픈 1~40자만 허용한다. 페이지와 추가 필터는 없다. `get_sector`는 랭킹 경로와 겹치는 `sectorId: rankings`를 거절한다. 랭킹은 대분류·중분류(depth 0·1)의 상위 100건까지다.

depth 0/1/2와 parentSectorId를 보존하고 대분류의 parentSectorId와 상세 summary는 null일 수 있다. 상세 changeRates는 6개 기간이며 데이터가 없는 changeRate는 null이다. 주식의 volume·tradingAmount·marketCapitalization과 랭킹의 금액도 null일 수 있다. 등락률 decimal은 소수비율이며 0.01은 1%다. decimal 문자열은 숫자로 바꾸지 않으며 US 금액도 currency에 표시된 원화 환산값이다. ETF는 하위 섹터를 포함하지 않으며 symbol·name만 제공하고 관련 항목이 없으면 빈 배열이다. 한 종목이 여러 섹터에 속할 수 있다.

### 조건주문 조회

`list_conditional_orders`는 `status=OPEN/CLOSED`와 선택 `symbol`, `cursor`, `limit`를 받는다. 두 상태 모두 기본 20건, 최대 100건의 한 페이지만 반환한다. `result.conditionalOrders`, `nextCursor`, `hasNext`를 보존하며 다음 페이지는 받은 cursor로 별도 호출한다.
`get_conditional_order`는 목록에서 받은 불투명 `conditionalOrderId`만 받는다. C0·DEL·C1 제어문자, 점 경로, percent 인코딩, 경로 구분자와 URL 주입을 인증 HTTP 전에 거절한다. 계좌, token, URL은 caller가 지정하지 못하며 현재 연결의 env 계좌만 사용한다.
SINGLE/OCO/OTO, STOP/PROFIT_RATE와 최상위·leg 상태를 구분한다. HOLDING이나 CANCELED인 leg를 최상위 상태로 바꾸지 않는다. nullable 가격과 `second=null`, 없는 `expireDate`와 decimal 문자열을 보존한다. `targetProfitRate="10.5"`는 10.5%다. WATCHING은 체결 완료를 뜻하지 않는다. 조건주문 생성·수정·취소와 WebSocket은 지원하지 않는다.

조건조회 결과는 공식 schema의 알려진 필드만 반환한다. 최상위와 leg, 중첩 배열의 확장 키는 제거하며 서비스가 추가한 정보는 누락될 수 있다. 공식 필드의 unknown enum 값은 보존한다. 지원하지 않는 객체 schema는 원문을 반환하지 않고 전체 오류로 거절한다. metadata는 현재 `{}`이며 upstream metadata를 그대로 노출하지 않는다. 계좌번호 표시는 기존 `list_accounts`의 끝 네 자리 마스킹을 따른다.

### 랭킹과 시장 지표

랭킹 count는 1..100, 기본 100이고 excludeInvestmentCaution은 기본 false다. 시장 전체와 토스증권 체결 기준을 구분하며 TOP은 기간 시작값, 나머지는 전일 기준값을 쓴다. 허용 시장·기간 조합은 80개다. 기간은 거래일 기준이며 rankedAt이 집계 시각이다. changeRate는 소수비율이므로 0.0125는 1.25%다.
지수 단위는 point, 국채 금리는 percent다. 3.25는 3.25%다. 캔들 count는 1..200 기본 100이며 nextBefore를 다음 before에 그대로 넣는다. 투자자 매매대금은 1d/1w/1mo/1y, count=1..100 기본 10이며 nextUntil을 다음 until에 넣는다. 각 도구의 nextBefore 또는 nextUntil이 null이면 마지막 페이지다. 선택 필드가 없다는 것만으로 마지막이라고 단정하지 않는다. before는 offset을 포함한 ISO 시각이고 until은 해당 날짜를 포함하는 YYYY-MM-DD 상한이다. 둘을 생략하면 최신부터 읽는다. 시세 symbols는 알려진 8종을 쉼표로 이은 1~200개다. 각 항목 양끝의 공백을 제거하므로 `KOSPI, KOSDAQ`는 `KOSPI,KOSDAQ`로 전송하며 순서와 중복은 유지한다. 빈 항목과 기호 내부 공백, 알 수 없는 기호는 요청 전에 거절한다. 투자자 당일 값은 잠정치이며 updatedAt이 갱신 시각이다.
이 네 도구는 계좌·token·URL 인자를 받지 않는다. 연결 env만 사용하고 시장 조회에는 계좌 헤더를 보내지 않는다.

### 국내 종목 통계

5개 통계 도구는 `symbol`을 필수로 받고 `count`는 기본 10, 최대 100이다.
예를 들어 `get_stock_investor_trading`에 `{"symbol":"005930","count":100,"until":"2026-07-17"}`을 넣는다.
`until`은 해당 날짜를 포함한다. 다음 페이지에는 `result.nextUntil`을 그대로 넣고 null이거나 없으면 끝낸다.
응답은 `{result: {records, nextUntil?}, metadata: {}}`이며 한 페이지의 전체 행과 순서를 보존한다.
symbol은 영문·숫자·점·하이픈을 받지만 통계는 KR 전용이다. 미국 종목의 `unsupported-market`는 `TOSSINVEST_INVALID_INPUT`으로 알린다.
모델의 계좌, token, URL과 추가 인자는 인증 전에 거절한다. 통계 GET에는 연결 계좌 헤더도 보내지 않는다.
공식 필드만 재귀적으로 반환하고 중첩 객체·배열의 비공식 확장키를 제거한다. decimal 문자열, null과 선택 필드의 부재는 그대로 둔다.

| 통계 | 단위와 집계 범위 | 반영 시점과 해석 |
| --- | --- | --- |
| 투자자별 매매 | KRX·NXT 합계 수량(주), 기관 7개 분류·등록외국인·보유·CFD. 보유와 CFD 비중은 소수 비율 | 장중 잠정치에는 개인·기관 세부·기타법인·보유·CFD가 null일 수 있다. 당일 저녁 확정치, CFD 다음 영업일 새벽, 보유 다음 영업일 오전 재갱신을 구분한다. updatedAt은 기록 전체의 마지막 갱신이다 |
| 프로그램 | KRX 거래량(주), 차익 arbitrage·비차익 nonArbitrage. NXT 제외 | 당일 값은 장 종료 전 바뀔 수 있다. 거래대금·updatedAt은 없다 |
| 공매도 | 수량(주), 금액(KRW), 소수 비율 | 당일 저녁 확정치. 비중의 분모는 정규장 외 세션을 포함한 누적 값이며 부재는 null, 0은 문자열 0이다 |
| 신용 | 자기신용·유통금융 합계 수량(주), balanceRate·tradingRate는 소수 비율 | marginLoan·stockLoan은 각각 nullable 객체다. 다음 영업일 새벽에 전 영업일 값이 반영된다. 개인 신용대주다 |
| 대차 | 기관 대여·차입의 체결·상환·잔고 수량(주), 잔고 금액(KRW) | 당일 저녁 확정치. 개인 신용대주와 구분한다 |

음수 순매수는 순매도다. 비율을 백분율로 바꾸거나 장중 값을 확정치로 말하지 않는다.
5개는 `STOCK_TRADING_TREND` 그룹의 초당 10회 한도를 공유하며 최소 호출 간격은 100ms다. AUTH는 별도 초당 5회다.

### 토큰

- 토큰은 프로세스 메모리에만 둔다. `expires_in` 에서 60초 뺀 시각까지 쓴다. 60초 이하면 `expires_in` 의 절반과 5초 가운데 긴 동안 쓰되 `expires_in` 을 넘기지 않고, 값이 없거나 양의 정확한 수명이 아니면 발급 오류로 거절한다. 매 호출이 토큰을 새로 받아 서로를 무효로 만들지 않게 하기 위해서다.
- `401 token-revoked` 나 `expired-token` 을 받으면 토큰을 한 번 새로 받고 그 호출을 한 번만 다시 보낸다. 정규화한 origin, client ID, 메모리 credential identity별 발급은 한 번에 하나다.
- 다시 보낸 호출도 `token-revoked` 면 다른 프로세스와 토큰을 다툰 것이라 `TOSSINVEST_UNAVAILABLE`(잠시 뒤 다시)로 끝낸다. 자격 증명이 틀린 것이 아니다.
- `invalid-token` 과 `invalid_client` 는 다시 받지 않는다.
- 확인 도구와 선택지 호출은 새 프로세스라 토큰을 새로 받는다. 그때 Hermes 쪽 프로세스의 토큰이 무효가 되고 다음 호출이 한 번 다시 받는다.

### 계좌 조회 호출 간격

공식 [OpenAPI 스펙](https://openapi.tossinvest.com/openapi-docs/latest/openapi.json)은 계좌 관련 조회를 아래 API 그룹으로 나눈다.

| 엔드포인트 | 공식 호출 한도 그룹 | 공식 초당 한도 | 커넥터의 최소 간격 |
| --- | --- | --- | --- |
| `GET /api/v1/accounts` | `ACCOUNT` | 1회 | 1,000ms |
| `GET /api/v1/holdings` | `ASSET` | 5회 | 200ms |
| `GET /api/v1/buying-power`, `GET /api/v1/sellable-quantity`, `GET /api/v1/commissions` | `ORDER_INFO` | 6회, 09:00~09:10 KST에는 3회 | 평시 167ms, 피크 334ms |
| `GET /api/v1/orders`, `GET /api/v1/orders/{orderId}` | `ORDER_HISTORY` | 5회 | 200ms |

한도 수치는 공식 [연동 가이드의 Rate Limits](https://openapi.tossinvest.com/openapi-docs/overview.md#rate-limits)에서 확인했다. 공식 한도는 사전 공지 없이 바뀔 수 있다.
커넥터는 프로세스 공용 큐를 그룹별로 두어 같은 그룹의 요청을 한 번에 하나씩 보낸다. 요청이 끝난 뒤 다음 요청까지 표의 간격을 둔다. `ORDER_INFO`는 KST 09:00~09:10에 피크 한도를 적용한다. 공식 19그룹과 AUTH도 같은 큐 경계를 사용한다. 예산은 origin/client ID별로 공유하며 secret 변경으로 한도를 우회하지 못한다.
`get_buying_power`의 두 조회와 서로 다른 호출의 같은 그룹 요청, 토큰 재발급 뒤 재송도 이 큐를 거친다. 다른 그룹은 서로의 큐를 기다리지 않는다. X-RateLimit의 낮은 한도와 Remaining/상대 Reset, Retry-After를 우선 적용한다. 큐 대기도 4초 호출 deadline 안에 포함한다.

간격, 상대 헤더 대기, 호출 deadline과 토큰 TTL은 `performance.timeOrigin + performance.now()`의 소수 밀리초 시각을 함께 쓴다. 실행 중 벽시계가 보정되어도 경과 시간은 바뀌지 않는다. KST 피크 여부는 현재 벽시계로 판단한다. 타이머에 넘기는 남은 시간은 밀리초 단위로 올림하고, 깬 뒤 간격과 deadline을 다시 검사한다. `Date.now()`의 정수 눈금만 비교하면 실제로 1초가 지나기 전에 다음 요청을 보낼 수 있기 때문이다.

GET이 429로 거절되면 같은 그룹의 큐에서 최소 1초 기다려 한 번만 다시 보낸다. `Retry-After`가 초 단위 수치로 더 긴 대기를 요구하면 그 시간도 지킨다. 재송도 429면 `TOSSINVEST_RATE_LIMITED`로 끝낸다. 다른 프로세스와는 큐를 공유하지 않으므로 그 사이의 충돌은 이 재시도로 대응한다.

### 오류

| 코드 | 공통 어휘 | 복구 | 언제 |
| --- | --- | --- | --- |
| `TOSSINVEST_UNAUTHORIZED` | `credential_rejected` | `reconnect` | 토큰 발급이 400, 401, `access_denied` 아닌 403 으로 거절됐다(client ID 나 secret 이 틀렸거나 형식이 맞지 않는다), API 가 `token-revoked` 와 `expired-token` 밖의 401 로 답했다 |
| `TOSSINVEST_IP_NOT_ALLOWED` | `forbidden` | `reconnect` | API 의 `ip-not-allowed`, 토큰 발급의 403 `access_denied`. 허용 IP 가 바뀌었을 수 있다 |
| `TOSSINVEST_FORBIDDEN` | `forbidden` | | 그 밖의 403 |
| `TOSSINVEST_ACCOUNT_NOT_FOUND` | `invalid_input` | `reconnect` | 고른 계좌 순번이 없다. 상태와 관계없이 `account-not-found` 면 이 코드다 |
| `TOSSINVEST_INVALID_INPUT` | `invalid_input` | `fix_input` | 인자가 형식에 맞지 않는다, 없는 종목·섹터이거나 요청한 시점의 환율이 없다 |
| `TOSSINVEST_RATE_LIMITED` | `unavailable` | `retry_later` | GET의 한 번 재시도 뒤에도 429 |
| `TOSSINVEST_NETWORK`, `TOSSINVEST_UNAVAILABLE` | `unavailable` | `retry_later` | 닿지 못했거나 시간이 지났다, 5xx 와 그 밖의 4xx, 다시 보낸 호출도 `token-revoked` 였다 |

결과와 오류에 토큰, secret, 계좌번호 원문, 서비스가 준 오류 원문을 싣지 않는다.

## 서버와 검사

`src/server.ts` 가 stdio MCP 서버이고 `dist/tossinvest-mcp.js` 로 묶는다. 생성 entry는 Git에서 추적하지 않고 테스트와 배포 묶음 생산 때 소스에서 빌드한다. 검사는 `tests/` 에서 로컬 HTTP 대역으로 돈다. 실제 서비스에 닿지 않는다.
공식 JSON fixture의 41 operation 중 인증 1개와 조회 GET 34개를 연결한다. MCP 도구는 READ 33개다. `getStocks`를 사용하는 `get_quotes`와 `get_stocks`, `getSellableQuantity`를 공유하는 두 도구는 고유 operation으로 한 번만 센다. 나머지 6개 금융 쓰기 operation은 구현·등록하지 않았으며 주문을 실행할 수 없다. `hermes/connectors/tossinvest/scripts/generate-contract.ts --check`가 원문 고정 계약의 재현성을 검사한다.
도구와 고유 operation은 다른 수치다. coverage 테스트는 모든 도구와 한국·미국 시장 일정을 호출해 인증 1회와 조회 36회를 수신한다. `getStocks`와 `getSellableQuantity`는 여러 도구가 공유한다.

SDK 설명과 manifest의 tools.description은 33개 모두 같은 평문이다. 설명은 표시 정보이며 READ/none 정책과 분리된다. 설명을 넣은 manifest의 운영 설치는 새 reader가 실제 plugin에 로드된 것을 확인한 뒤 진행한다.

대역은 받은 요청의 메서드와 고정 경로 template을 명시적으로 주입한 허용 operation 집합과 견준다. 테스트별로 필요한 `GET`과 `POST /oauth2/token`만 허용한다. 다른 GET이나 금융 POST·DELETE가 오면 테스트가 실패한다. 공식 성공 예시 78개와 production SDK·소스에서 빌드한 stdio bundle의 실제 HTTP 도달을 검사한다.

## 보안

- **돈이 움직이는 도구가 없다.** 그러나 키 자체는 주문할 수 있다. 키가 새면 허용 IP 에서 주문할 수 있다. 실행 공간도 같은 공인 IP 로 나가므로 허용 IP 는 셸에 샌 키를 막지 못한다. 그래서 `sandbox_required` 다.
- 외부 글이 모델을 속였을 때 닿는 범위: 이 커넥터에는 쓰는 도구가 없다. 속은 모델이 할 수 있는 것은 읽은 계좌 데이터를 다른 도구(셸, 웹)로 내보내는 것이다. 그 길은 [READ 데이터 흐름](connector-policy.md) 의 RF-08, RF-09 와 같다.
- 보유와 잔고가 모델 공급자에게 간다(RF-20). 사용자가 받아들였다.
- 토스증권 이용 약관은 시세의 제3자 제공을 금지한다. 모델 공급자 전송이 여기 드는지는 확인하지 못했다.

## 연결 유지

### 허용 IP 가 바뀌었을 때

가정 회선은 공인 IP 가 바뀔 수 있다. 바뀌면 모든 호출이 `TOSSINVEST_IP_NOT_ALLOWED` 로 실패하고, 에이전트가 허용 IP 를 확인하라고 안내한다.
WTS 에서 허용 IP 를 고친 뒤 외부 서비스 연결 화면에서 연결 확인을 누른다. IP 가 바뀌었다는 알림은 운영이 한다.
연결 상태는 저절로 바뀌지 않는다. 연결 확인이 같은 오류로 실패해야 연결이 `PENDING` 이 된다.

### 끊기

연결을 해제하면 보관 파일과 붙은 profile 의 값이 지워진다. 토스증권 WTS 에서 그 client 도 지운다.

## 실제 계정으로 확인하기

배포 뒤 소유자가 자기 계정으로 한 번 확인한다. 계좌번호, 금액, 종목은 어디에도 적지 않는다.

1. 외부 서비스 연결 화면에서 등록하고, 계좌가 끝 네 자리로 보이는지 본다
2. 실행 공간이 없는 에이전트에 붙이면 거절되는지, 있는 에이전트에 붙는지 본다
3. 같은 연결을 두 번째 에이전트에 붙이면 거절되는지 본다
4. 대화에서 보유 종목, 현재가, 주문 가능 현금, 이번 달 끝난 주문을 묻는다. 보유 종목의 손익과 비율 칸이 null 이 아닌지 본다. 서비스가 공식 decimal 형식이 아닌 값을 주면 응답 오류로 끝난다
5. 연결 확인을 누른 직후 대화에서 다시 보유 종목을 물어, 토큰을 한 번 다시 받고 성공하는지 본다

## 매매 개방 조건

**지금은 주문을 실행할 수 없다.** 아래 조건을 모두 채우고, 소유자가 개방을 명시적으로 승인한 뒤 따로 여는 PR 에서만 연다.
금융 실행 전체를 켜고 끄는 설정(kill switch)은 두지 않는다. **개방 PR 의 머지가 곧 개방이다.**
그 PR 전까지는 공개 진입점이 이어지지 않아 머지만으로 주문이 열리지 않는다.
개방 PR 이 머지된 뒤에도 운영에 keyed hash key 파일과 허용 목록, 0 이 아닌 상한을 넣기 전에는 주문이 거절된다. key 파일이 운영의 마지막 잠금이다.
첫 개방 대상은 일반 주문의 `create_order` 와 `cancel_order` 다. `modify_order` 와 조건주문 세 쓰기는 첫 개방에 넣지 않는다.

| 조건 | 무엇을 보장하는가 | 지금 |
| --- | --- | --- |
| keyed hash key 파일 | 운영 비밀 파일 하나를 읽지 못하면 준비, 승인, claim 이 모두 금융 전송 없이 거절한다 | 있음. `digest-key-file`. key 를 쓰는 해시 계산은 「해시 추측 방어」 가 더한다 |
| 호출마다 승인 | FINANCIAL 도구는 `always` 다. 상시 허락과 대화 허락을 받지 않는다(grant 불가) | 있음 |
| 계좌 허용 목록 | 운영 설정에 미리 적은 계좌만 주문한다. 연결의 공개 칸 `account` 가 목록에 없으면 준비부터 거절한다 | 있음. `allowed-accounts`. 승인과 claim 에서도 저장 scope 로 다시 본다 |
| 주문 상한 | 한 건의 금액과 수량, 하루 합계 금액에 운영 설정 상한을 둔다. 기본값은 0 이라 아무 주문도 받지 않는다 | 있음. `max-order-amount-krw`, `max-order-amount-usd`, `max-order-quantity`, `max-daily-amount-krw`, `max-daily-amount-usd` |
| 멱등 key | 준비가 만든 `clientOrderId` 하나로 한 번만 보낸다. 같은 의도의 PENDING, EXECUTING, UNKNOWN 이 있으면 새 준비를 막는다 | 있음 |
| UNKNOWN 은 재시도하지 않는다 | 결과를 모르는 주문은 ticket 을 다시 내지 않고 다시 보내지 않는다. 사용자가 토스에서 확인한다 | 있음 |
| 연결 재등록 우회 차단 | request_key 를 연결 번호가 아니라 사용자, 커넥터, 계좌 범위로 계산한다. 기존 키와의 호환은 키 버전 접두로 가른다 | 없음 (#507 후속) |
| 해시 추측 방어 | 금융 줄의 인자 해시와 request_key 를 서버 비밀로 keyed hash 한다. DB 만 읽은 사람이 인자를 대입해 맞혀 보지 못한다 | 없음 (#507 후속) |
| 공개 진입점 연결 | 정책 판정과 승인 API 가 FINANCIAL 을 producer 와 금융 승인으로 보낸다. 토스 서버와 manifest 가 두 도구를 등록한다 | 없음. producer 와 금융 승인은 package-private 이다 |
| 승인 카드 표시 | 승인 카드가 저장한 summary(종목, 방향, 수량, 가격, 정규화)를 보인다. 계좌는 summary 가 아니라 Control Plane 이 실제 전송 scope(`account_seq`)와 연결의 공개 칸, 허용 목록에서 만들어 보인다. 모델 인자만 보이고 승인하지 않는다 | 없음 |

계획은 `tasks/plan208-toss-conditional-financial/` 이 갖는다.

### 첫 실제 호출 절차

개방 PR 이 배포된 뒤 소유자가 직접 한다. 계좌번호, 금액, 종목은 어디에도 적지 않는다.
체결되지 않을 주문 하나를 넣고 바로 취소해, 전송과 기록이 한 번씩만 남는지 본다.

1. 배포 직후 key 파일이나 허용 목록이 아직 없는지 확인한다. 대화에서 주문을 요청해 금융 전송 없이 거절되는지 본다
2. 계좌 허용 목록에 쓸 계좌 하나만 넣고, 주문 상한을 아래 주문을 겨우 담을 만큼 작게 둔다
3. key 파일을 두고 재기동해 관문을 연다. 여는 것은 소유자가 그 자리에서 승인한다
4. 대화에서 국내 종목 하나를 1주, 당일 하한가에 가까워 체결되지 않을 지정가로 매수하라고 요청한다. 가격은 `get_price_limits` 와 `get_orderbook` 으로 먼저 읽는다
   주문은 국내 정규장 안에서 넣고, 장 시작과 마감의 동시호가 시간과 마감 직전은 피한다. 정규장 밖에서 넣으면 토스가 예약 주문으로 받거나 거절해 미체결 주문 하나와 취소를 확인하지 못할 수 있다.
   그 종목이 하한가로 내려가면 이 주문은 체결된다. 그때의 최대 손실은 1주 가격이다
5. 승인 카드의 계좌가 허용 목록에 적은 그 계좌인지, 종목, 방향, 수량, 가격이 요청과 같은지 보고 승인한다. 카드의 계좌는 Control Plane 이 실제 전송 계좌에서 만든 값이다. summary 의 끝 네 자리는 커넥터가 낸 값이라 계좌 확인의 근거로 쓰지 않는다
6. `list_orders` 로 미체결 주문이 하나만 생겼는지 본다. 토스증권 앱에서도 같은 주문 하나를 확인한다
7. 그 주문을 취소하라고 요청하고 승인한다. 미체결 목록에서 사라졌는지 앱과 `list_orders` 로 본다
8. 계속 쓰지 않을 거면 아래 「되돌리기」 로 다시 닫는다

거절은 어느 검사에 걸렸는지 응답에 밝히지 않는다. 무엇을 채울지는 기동 로그의 `financial gate:` 한 줄(key 읽힘 여부, 허용 계좌 수, 0 인 상한 이름)로 가린다.
승인 줄과 실행 줄의 상태가 SUCCEEDED 하나씩이고 UNKNOWN 과 중복 전송이 없는지 확인하는 방법은 `fos-home-infra` 가 갖는다.
도중에 UNKNOWN 이 나오면 다시 요청하지 않는다. 토스증권 앱에서 주문 상태를 먼저 확인한다.

### 되돌리기

key 파일 설정을 비우거나 허용 목록을 비우면 새 준비, 대기 중인 승인, 아직 소비하지 않은 claim 이 모두 금융 전송 없이 거절된다. 상한을 0 으로 두면 새 주문과 대기 중인 주문의 승인이 거절된다. 배포를 되돌리지 않아도 된다.
설정과 key 는 기동 뒤 한 번 읽으므로 운영 값을 바꾼 뒤 Control Plane 을 재기동해야 반영된다. key 파일만 지우고 재기동하지 않으면 닫히지 않는다.
이미 토스에 들어간 주문은 이 설정이 취소하지 않는다. 토스증권 앱에서 취소한다.
닫는 방법은 `fos-home-infra` 가 갖는다.

## 토스증권 REST 전체 지원

목표는 공식 REST의 정상 기능을 MCP 사용자에게 모두 제공하는 것이다. WebSocket 연결, 구독, 실시간 사건은 범위 밖이다.
현재 READ 도구 33개가 조회 GET 34개와 내부 인증 1개의 공식 결과와 페이지를 지원한다. 공식 41 operation의 계약과 19그룹 한도를 갖추었으며 나머지 6개 금융 쓰기 operation은 구현·등록하지 않았다. READ 조회 추가가 금융 실행 허용을 뜻하지 않는다.
종목 참조 도구 `get_stocks`, `list_stocks`, `get_stock_warnings`는 production 서버에 등록되어 있다. `getStocks`는 기존 `get_quotes`와 공유하므로 고유 operation 증가분은 `listStocks`, `getStockWarnings` 두 개다. 전체 목록은 7개 시장과 선택 필터를 지원하며 페이지 없이 전량을 반환한다. 유의사항의 exchange는 상장 시장 market과 구분하고 빈 배열과 미존재 오류를 구분한다. 이 세 도구도 READ/none이며 SDK의 readOnlyHint는 참이다.
현재 금융 도구는 차단돼 있다. 아래 이름은 구현할 도구의 계약이며 등록 완료를 뜻하지 않는다.

### 공식 기준과 완료 조건

2026-10-12에 [공식 OpenAPI JSON](https://openapi.tossinvest.com/openapi-docs/latest/openapi.json)의 1.2.24, OpenAPI 3.1.0, 41 operation을 확인했다.
원문 SHA-256은 `7e753c21dc4b938d8f9610ce3efd6542783018977cb030e39db5a006f5b59d40`이다.
[공식 개요](https://openapi.tossinvest.com/openapi-docs/overview.md)는 호출 한도와 오류의 근거다.
구현을 시작할 때 버전, method/path 집합, 참조 schema, 설명의 조건과 오류를 다시 대조한다.
차이가 있으면 변경점을 기능 문서와 fixture에 반영하고 범위를 재검토한 뒤 구현한다. 과거 검색 색인을 쓰지 않는다.
[공식 llms 안내](https://developers.tossinvest.com/llms.txt)의 JWKS 소개는 canonical paths에 없으므로 42번째 완료 조건으로 세지 않는다.

완료는 41개 method/path가 아래 도구 또는 내부 인증에 연결되고 입력, 응답, 페이지, 시장 조건과 오류를 실제 HTTP 테스트로 확인한 상태다.
HTTP 함수만 만들거나 목록에 이름만 등록한 상태는 완료가 아니다.
구현된 operation만 fixture의 상태를 올린다. 목표 41개와 현재 연결 35개를 같은 수치로 표시하지 않는다.

### 도구 입력과 결과

새 도구는 아래 공식 camelCase 입력 이름을 그대로 쓴다. 기존 `get_quotes(symbols)`, `get_buying_power(currency,symbol)`과 `list_orders(status,from,to,symbol)`은 유지하고 선택 입력을 더한다.
`get_market_calendar`는 필수 `market: KR/US`로 고정된 두 주소 중 하나를 고른다.
API의 account 헤더는 입력에 노출하지 않는다. 연결 env의 계좌만 사용하며 모델의 계좌, URL, 토큰과 clientOrderId 입력은 거절한다.
읽기 도구는 공식 `result`와 필요한 envelope 메타데이터를 무손실로 제공한다. 기존 snake_case 표시 필드는 호환을 위해 유지하고 공식 `result`를 추가한다.
`get_quotes`의 공식 결과는 `{prices,stocks}`이며 각각 공식 배열이다. `get_stocks`도 별도로 등록해 이름 이외의 참조 정보를 읽는다.
`get_holdings`는 선택 `symbol`을 추가한다. `get_sellable_quantity`는 단독 호출도 가능하게 한다.
get_buying_power의 result는 symbol 부재 시 {buyingPower: BuyingPowerResponse, sellableQuantity: null}, symbol 존재 시 {buyingPower: BuyingPowerResponse, sellableQuantity: SellableQuantityResponse}다. 각 값은 공식 result 전체이며 후자는 두 GET을 각각 한 번 호출한다.
envelope 메타데이터는 result 밖의 metadata: {buyingPower: {...}, sellableQuantity: {...} | null}에 둔다. 각 객체의 metadata는 현재 항상 {}이며 알 수 없는 envelope 추가 키는 전달하지 않는다. symbol 없는 sellableQuantity의 metadata만 null이다.
단독 get_sellable_quantity는 result: SellableQuantityResponse와 metadata: {}를 반환한다. get_quotes는 result: {prices,stocks}, metadata: {prices: {}, stocks: {}}로 같은 규칙을 쓴다. 한 호출 실패나 schema 위반을 부분 성공/null로 숨기지 않는다.
기존 currency/cash_buying_power/sellable_quantity 표시와 선택지 accounts/account_seq/label을 유지한다. symbol 부재의 기존 sellable_quantity는 null이다.
producer가 schema 검증 뒤 accountNo를 끝 네 자리로 마스킹한 복사본에서 표시·result·metadata·structuredContent·content를 함께 만든다. 선택지와 오류·로그에도 계좌 원문을 싣지 않으며 표시 helper에만 마스킹을 맡기지 않는다.
계좌번호 원문은 예외다. `list_accounts`는 `accountNo`를 끝 네 자리로 마스킹하고 `accountSeq`와 `accountType`을 보존한다. 이 제한을 설명과 결과 schema에 명시한다.
계좌 순번의 현재 10자리 제한과 목록 행 삭제는 제거한다. 공식 int64 범위의 양의 식별자를 원문에서 정확한 10진수 문자열로 읽어 연결 env와 헤더에 사용한다.
무손실 파싱 경계는 client.ts의 exchange → api-contract.ts의 parseApiResponse(rawText, operation, status)다. bounded 1 MiB 본문을 UTF-8로 읽은 직후 호출하며 일반 JSON.parse의 손실한 값을 producer에 먼저 돌려주지 않는다.
Bun 1.3.14의 native JSON.parse reviver 세 번째 인자 context.source를 사용한다. reviver에서 number primitive의 원문을 holder 객체/키별 WeakMap에 보관한 다음 operation/status의 응답 schema를 따라 객체·배열·$ref·allOf를 순회해 지정 위치만 변환한다. 같은 이름인 다른 필드나 문자열은 변환하지 않는다.
schema 위치와 출력 변환은 api-contract.ts의 응답 descriptor가 소유한다. canonical 1.2.24의 int64 위치는 Account.accountSeq와 OAuth2TokenResponse.expires_in 두 곳이다. 원문 schema를 수정하지 않고 MCP 출력 descriptor에 변환을 선언한다.
Account.accountSeq는 항상 정규 10진수 문자열이며 1..9223372036854775807이다. 다른 int64는 -9223372036854775808..9223372036854775807에서 schema의 추가 제약을 적용하고 안전 정수는 number, 그 밖은 정규 10진수 문자열로 반환한다. expires_in은 추가로 양수여야 한다.
expires_in의 정확한 초→ms 계산은 BigInt로 하고 안전한 `clock.now()`/expiresAt 표현 범위를 넘으면 발급 오류로 거절한다. `clock.now()`는 `performance.timeOrigin + performance.now()`의 소수 밀리초 시각이며 토큰 TTL, 호출 deadline과 상대 한도 대기에 함께 쓴다. KST 피크 여부만 현재 벽시계로 판단한다. token을 모델 결과에 내보내지 않는다.
JSON integer 계약은 1.0, 1e3처럼 수학적으로 정수인 표기도 허용한다. source의 부호·계수·소수 자리·지수를 문자열로 분해해 정확한 정수성과 signed int64 범위를 검사한다. 1.5와 1e-1은 거절한다. 지수가 크면 1 MiB 본문 안에서도 자리수 비교로 조기 거절하고 큰 0 문자열을 할당하지 않는다.
Number(value)나 반올림한 Number로 범위를 판정하지 않는다. primitive 문자열의 가짜 숫자는 그대로 두며 구문 오류는 native parser가 거절한다. context.source가 없으면 정확성을 가장하지 않고 실패한다. JSON 정규식 재작성과 새 의존성은 금지한다.
raw fixture의 기대값은 손으로 적은 10진수 문자열/BigInt 상수에서 만든다. 2^53-1·2^53·2^53+1, 인접 큰 정수, int64 최대·초과·최소·미만, accountSeq의 0·음수, nested 배열, 같은 이름의 비schema 필드, 문자열 속 가짜 숫자, 정수 지수/소수와 비정수 표기·문법 오류를 검사한다.
실제 tools/call의 structuredContent와 content JSON, list_accounts 선택지와 env 헤더, 표시 호환과 raw result 모두 정확한 값을 단언한다. parser 단위 테스트만으로 완료하지 않는다.
형식이 잘못된 계좌 행은 전체 응답 오류로 알리고 정상 행처럼 누락시키지 않는다. 모델이 계좌를 지정하는 입력은 계속 받지 않는다.
기존의 이름 100자, 일반 문자열 64자 절단은 표시 필드에만 적용한다. 공식 `result`, orderId, cursor와 decimal을 자르지 않는다.
decimal은 string 그대로 유지하고 산술이 필요하면 정확한 문자열 연산을 쓴다. 응답의 null, boolean, 배열, 음수와 unknown enum을 버리지 않는다.
요청 enum은 공식 지원값만 허용한다. 읽기 결과의 새 enum은 그대로 전달하되 금융 준비에서는 지원을 확인하지 못한 값으로 실행하지 않는다.
schema 위반은 명확한 오류로 끝내며 누락을 null로 바꾸거나 일부 행을 삭제하지 않는다.

### 공식 operation과 producer

입력 parameter, 성공·오류 응답과 필드 제약의 정본은 [공식 OpenAPI JSON](https://openapi.tossinvest.com/openapi-docs/latest/openapi.json)이다.
확인한 원문은 [1.2.24 fixture](../../hermes/connectors/tossinvest/tests/fixtures/openapi-1.2.24.json)에 그대로 보존한다.
[생성된 계약](../../hermes/connectors/tossinvest/src/api-contract.ts)은 이 fixture의 method/path, 입력, 응답과 schema를 사용한다.
아래 표는 operationId와 MCP 도구, 권한·호출 한도 그룹, 현재 구현 상태만 연결한다. 단계는 구현 관심사의 구분이며 PR 개수가 아니다.

| 공식 operationId | 도구 또는 인증 | 권한과 한도 그룹 | 현재 상태 | 단계 |
| --- | --- | --- | --- | --- |
| `issueOAuth2Token` | `내부 Tossinvest.token/issue` | 내부 인증; AUTH | 기반 구현 | 기반 |
| `getOrderbook` | `get_orderbook` | READ/none; MARKET_DATA | 구현 | 시세 |
| `getPrices` | `get_quotes` | READ/none; MARKET_DATA | 기반 구현 | 기반 |
| `getTrades` | `get_trades` | READ/none; MARKET_DATA | 구현 | 시세 |
| `getPriceLimit` | `get_price_limits` | READ/none; MARKET_DATA | 구현 | 시세 |
| `getCandles` | `get_candles` | READ/none; MARKET_DATA_CHART | 구현 | 시세 |
| `getStocks` | `get_stocks`, `get_quotes` | READ/none; STOCK | 구현 | 종목 |
| `listStocks` | `list_stocks` | READ/none; STOCK_ALL | 구현 | 종목 |
| `getStockWarnings` | `get_stock_warnings` | READ/none; STOCK | 구현 | 종목 |
| `getStockInvestorTrading` | `get_stock_investor_trading` | READ/none; STOCK_TRADING_TREND | 구현 | 종목 |
| `getStockProgramTrades` | `get_stock_program_trades` | READ/none; STOCK_TRADING_TREND | 구현 | 종목 |
| `getStockShortSelling` | `get_stock_short_selling` | READ/none; STOCK_TRADING_TREND | 구현 | 종목 |
| `getStockCreditTrades` | `get_stock_credit_trades` | READ/none; STOCK_TRADING_TREND | 구현 | 종목 |
| `getStockSecuritiesLending` | `get_stock_securities_lending` | READ/none; STOCK_TRADING_TREND | 구현 | 종목 |
| `getExchangeRate` | `get_exchange_rate` | READ/none; MARKET_INFO | 구현 | 시장 일정 |
| `getKrMarketCalendar` | `get_market_calendar(market=KR)` | READ/none; MARKET_INFO | 구현 | 시장 일정 |
| `getUsMarketCalendar` | `get_market_calendar(market=US)` | READ/none; MARKET_INFO | 구현 | 시장 일정 |
| `getRankings` | `get_rankings` | READ/none; RANKING | 구현 | 랭킹·지표 |
| `getMarketIndicatorPrices` | `get_market_indicator_prices` | READ/none; MARKET_INDICATOR | 구현 | 랭킹·지표 |
| `getMarketIndicatorCandles` | `get_market_indicator_candles` | READ/none; MARKET_INDICATOR_CHART | 구현 | 랭킹·지표 |
| `getMarketIndicatorInvestorTrading` | `get_market_indicator_investor_trading` | READ/none; MARKET_INDICATOR | 구현 | 랭킹·지표 |
| `listSectors` | `list_sectors` | READ/none; SECTOR | 구현 | 섹터 |
| `getSectorRankings` | `get_sector_rankings` | READ/none; SECTOR_RANKING | 구현 | 섹터 |
| `getSector` | `get_sector` | READ/none; SECTOR | 구현 | 섹터 |
| `getSectorStocks` | `get_sector_stocks` | READ/none; SECTOR | 구현 | 섹터 |
| `getSectorEtfs` | `get_sector_etfs` | READ/none; SECTOR | 구현 | 섹터 |
| `getAccounts` | `list_accounts` | READ/none; ACCOUNT | 기반 구현 | 기반 |
| `getHoldings` | `get_holdings` | READ/none; ASSET | 기반 구현 | 기반 |
| `getOrders` | `list_orders` | READ/none; ORDER_HISTORY | 기반 구현 | 기반 |
| `createOrder` | `create_order` | FINANCIAL/always; ORDER | 미연결 | 일반주문 |
| `getOrder` | `get_order` | READ/none; ORDER_HISTORY | 구현 | 일반주문 |
| `modifyOrder` | `modify_order` | FINANCIAL/always; ORDER | 미연결 | 일반주문 |
| `cancelOrder` | `cancel_order` | FINANCIAL/always; ORDER | 미연결 | 일반주문 |
| `createConditionalOrder` | `create_conditional_order` | FINANCIAL/always; CONDITIONAL_ORDER | 미연결 | 조건주문 |
| `getConditionalOrders` | `list_conditional_orders` | READ/none; CONDITIONAL_ORDER_HISTORY | 구현 | 조건 조회 |
| `getConditionalOrder` | `get_conditional_order` | READ/none; CONDITIONAL_ORDER_HISTORY | 구현 | 조건 조회 |
| `cancelConditionalOrder` | `cancel_conditional_order` | FINANCIAL/always; CONDITIONAL_ORDER | 미연결 | 조건주문 |
| `modifyConditionalOrder` | `modify_conditional_order` | FINANCIAL/always; CONDITIONAL_ORDER | 미연결 | 조건주문 |
| `getBuyingPower` | `get_buying_power` | READ/none; ORDER_INFO | 기반 구현 | 기반 |
| `getSellableQuantity` | `get_sellable_quantity` | READ/none; ORDER_INFO | 기반 구현 | 기반 |
| `getCommissions` | `get_commissions` | READ/none; ORDER_INFO | 구현 | 일반주문 |

### 필드 계약

공식 schema와 예시는 위 fixture와 생성된 계약에서 확인한다. 이 문서는 필드 표를 따로 복제하지 않는다.
오류 code는 공식 예시의 값이며 폐쇄된 enum이 아니다. 공통 인증·IP·maintenance 오류는 [공식 개요](https://openapi.tossinvest.com/openapi-docs/overview.md)도 함께 확인한다.

1.2.24의 StockInfo schema는 englishName을 필수로 선언하지만 같은 원문의 getStocks ETF 성공 예시는 이를 생략하고 securityType을 ETF로 제공한다.
이 불일치에 한해 StockInfo의 securityType이 ETF일 때 englishName 부재를 허용한다. 누락 값을 채우지 않으며 존재하는 englishName의 타입은 그대로 검증한다.
일반 주식·다른 분류와 다른 필수 필드에는 이 호환 처리를 적용하지 않는다. 실제 API 응답 형태는 이번 loopback 검증의 범위 밖이다.
현재 34개 READ operation의 공식 성공 예시 78개와 공식 ETF·일반 종목의 실제 MCP SDK 호출을 회귀로 검사한다. production SDK와 소스·빌드한 stdio가 각각 33개 도구를 호출해 인증·조회 35개 operation에 도달하는지 HTTP 수신 기록으로 확인하며 stdio bundle에서도 종목 참조·시세·시장 도구의 도달을 검사한다.

### 페이지와 시장별 조건

| 대상 | 지원 계약 |
| --- | --- |
| prices, stocks | 공식 최대 200종목을 허용한다. 단일 symbol에는 쉼표를 허용하지 않고 공식 pattern을 적용하며 임의 12자 제한은 제거한다. 금융 저장의 32자 제한은 별도 보호 계약으로 명시한다 |
| candles | `interval=1m/1d`, `count=1..200`, `before`, `adjusted`를 지원한다. nextBefore를 다음 before에 그대로 전달하고 마지막 null을 보존한다. 시간대의 +는 URLSearchParams로 인코딩한다 |
| stocks/all | 공식 market/status/securityType/commonShare 필터를 모두 지원한다. 공식 페이징이 없는 전량 응답을 임의 cursor로 바꾸지 않는다 |
| 종목 수급 5개 | 국내 종목만 지원한다. count=1..100, until은 실제 날짜로 검사하며 응답의 잠정·확정 값과 null을 보존한다. nextUntil을 다음 until에 그대로 전달하고 null이면 종료한다 |
| exchange-rate | baseCurrency/quoteCurrency와 선택 dateTime을 지원한다. 참고용 표시 환율을 주문 체결 환율로 설명하지 않는다 |
| KR/US calendar | 시장별 영업일, KRX/NXT와 미국 세션, 시간대·서머타임을 원문대로 전달한다. 날짜 미지정은 공식 현재일 기준이며 UTC 날짜로 임의 치환하지 않는다 |
| rankings | 6종 type, KR/US, 7종 duration, excludeInvestmentCaution, count를 모두 제공한다. TOP_GAINERS/TOP_LOSERS에는 realtime 금지다. 시장 전체와 토스 체결 기준, 기간 시작값과 전일 기준값을 구분한다. 빈 rankings와 rankedAt=null도 정상이다 |
| market indicators | KOSPI/KOSDAQ와 KR_BOND_2Y/3Y/5Y/10Y/20Y/30Y만 시세·일봉을 지원한다. 분봉은 지수만, investor-trading은 KOSPI/KOSDAQ와 1d/1w/1mo/1y만 지원한다 |
| sectors | 목록·랭킹·상세·주식·ETF의 5개 조회다. 계층 0/1/2와 여러 섹터 소속, KR/US 차이와 빈 목록을 보존한다 |
| orders OPEN | 공식 전량을 보존하고 100건으로 자르지 않는다. limit/cursor는 upstream이 무시하며 from/to는 KST orderedAt 날짜 필터다 |
| orders CLOSED | limit=1..100, 기본 20과 opaque cursor를 노출한다. nextCursor/hasNext를 보존하고 마지막 페이지에서 커서를 만들지 않는다. 임의 366일 제한은 제거한다 |
| conditional-orders | OPEN/CLOSED 모두 cursor/limit 페이지를 지원한다. 다른 채널의 주문도 조회한다. 응답 PROFIT_RATE는 읽되 ConditionRequest에 없는 생성 필드를 발명하지 않는다 |
| 큰 응답 | 1 MiB 상한과 4초 HTTP timeout을 유지한다. 공식 페이지가 있으면 사용자가 다음 페이지를 읽는다. 페이지가 없는 큰 응답은 명확한 크기 초과 오류로 끝내며 몰래 일부 행을 남기지 않는다 |

### 조건주문 REST 쓰기의 구현 보완안

이 절은 구현 제안이다. 운영 금융 도구는 미등록이며 FINANCIAL의 `RISK_NOT_OPEN`, noGrant와 `confirmHighValueOrder=false`를 유지한다.
SDK와 실제 Python 소비자를 loopback에서 검증하는 구현은 실제 DB producer 연결 및 운영 금융 허가와 별개로 완료할 수 있다.
일반 주문과 조건주문은 같은 ticket·claim을 쓰되 본문과 표시 값을 각각의 엄격한 계약으로 검증한다. 공개 hook·approve를 열거나 새 grant·permit flag를 만들지 않는다.

#### 공식 계약과 입력

2026-10-11 공개 GET으로 확인한 [공식 OpenAPI](https://openapi.tossinvest.com/openapi-docs/latest/openapi.json)는 1.2.24, OpenAPI 3.1.0이며 REST operation은 인증을 포함해 41개다.
원문 SHA-256은 `7e753c21dc4b938d8f9610ce3efd6542783018977cb030e39db5a006f5b59d40`이다. 기존 fixture 및 생성 계약을 재사용한다.
41개 각각의 method/path/body/response/group과 운영 등록 여부를 별개로 확인한다. 이번에는 쓰기 세 개만 테스트 entry에 연결하며 WS는 제외한다.

| operation | method/path | 본문 | 성공 |
| --- | --- | --- | --- |
| createConditionalOrder | POST /api/v1/conditional-orders | ConditionalOrderCreateRequest | 200, result.conditionalOrderId 필수; clientOrderId 선택/nullable |
| modifyConditionalOrder | POST /api/v1/conditional-orders/{conditionalOrderId}/modify | ConditionalOrderModifyRequest | 200, result.conditionalOrderId 필수; 새 ID를 보존하며 기존 ID와 같으면 UNKNOWN |
| cancelConditionalOrder | DELETE /api/v1/conditional-orders/{conditionalOrderId} | 없음 | 빈 204; JSON 성공 응답을 만들지 않는다 |

모두 Bearer와 `X-Tossinvest-Account`를 사용한다. 계좌 int64는 연결 공개 칸 account와 env의 문자열로 대조한다.
CREATE의 모델 입력은 symbol/type/quantity/orderType/expireDate/first, 선택 second/confirmHighValueOrder다.
MODIFY는 conditionalOrderId/type/quantity/orderType/expireDate/first, 선택 second/confirmHighValueOrder다. 부분 patch가 아니라 전체 재설정이다.
CANCEL의 모델 입력은 conditionalOrderId 하나다. 나머지 키, 모델 clientOrderId·scope·expected 값은 거절한다.
본문에서 scope·currency·market·현재 원주문을 제거한다. CREATE만 prepare에서 UUID clientOrderId를 한 번 만들고 MODIFY는 symbol/clientOrderId를 보내지 않는다.
confirmHighValueOrder는 입력 false만 허용하고 본문은 false를 명시한다. 금액 orderAmount와 일반 주문 timeInForce는 세 operation 모두 허용하지 않는다.

quantity는 주 단위이며 양의 1~30자 decimal 문자열이다. KR/US 모두 공식 decimal 계약을 적용한다. 조건 quantity에는 KR 정수 제한을 추가하지 않는다. 일반 주문의 소수 수량 시간 제한을 조건주문 규칙으로 복사하지 않는다.
first/second의 쓰기 leg는 orderSide와 triggerPrice, LIMIT일 때 orderPrice만 받는다. 가격은 양의 decimal 문자열이며 숫자·지수·공백·부호는 거절한다.
KR orderPrice는 원 단위 정수, US는 1달러 미만 4자리·이상 2자리까지다. KR 호가표를 예시 하나로 추론하지 않으며 서버의 invalid-request/tickSize 거절을 보존하고 자동 절삭·보정·재전송하지 않는다.
SINGLE은 second를 생략한다. OCO는 두 SELL leg, OTO는 BUY→SELL leg이며 둘 다 LIMIT만 허용한다.
OCO는 준비와 claim 뒤 전송 직전의 현재가 조회에서 first.triggerPrice > 현재가 > second.triggerPrice를 확인한다. 값 비교는 decimal 문자열을 정확히 비교하며 Number로 바꾸지 않는다.
expireDate는 실제 YYYY-MM-DD이며 KST 오늘부터 달력상 1년 뒤까지다. 윤년의 다음 해 같은 날짜가 없으면 말일을 상한으로 삼는다. 실행에서도 현재 날짜를 검사하며 만료일을 바꾸지 않는다.
executionArgs.market은 StockInfo.market의 KOSPI/KOSDAQ/KR_ETC/NYSE/NASDAQ/AMEX/US_ETC 중 하나를 그대로 저장한다.
KOSPI/KOSDAQ/KR_ETC와 KRW 조합은 KR, NYSE/NASDAQ/AMEX/US_ETC와 USD 조합은 US다. 나머지 조합은 거절한다.
summary.market과 expected_conditional_order.market은 KR/US다. Python·Spring 조건 검증기는 이 고정 대응으로 실행 시장을 변환한 뒤 summary 및 원주문과 동일성을 검사한다. 일반 주문의 market 문자열 직접 동일성 검사를 복사하지 않는다.

#### 표시 값과 현재 원주문

조회의 ConditionalOrderDetailResponse·ConditionalOrderCondition과 알려진 필드 투영을 재사용한다.
불투명 ID는 기존 조건 조회와 같은 한 segment 검사로 검증한다. 빈 값·점 경로·공백·슬래시·역슬래시·query/hash/percent/colon·C0/C1과 잘못된 surrogate(Cs)를 거절하고 encodeURIComponent로 한 번 인코딩한다.
조회 응답의 null과 부재는 구분한다. 현재 원주문 대조는 공식 필드만 투영한 객체의 키·값·타입을 비교한다. first/second의 type/status/triggerPrice/targetProfitRate/orderPrice/triggeredOrderId와 상위 createdAt/expireDate까지 포함한다.
새 prepare의 원주문 수용과 claim 뒤 변화 검사를 분리한다. 공식 DELETE와 MODIFY에는 WATCHING/PAUSED만 허용하거나 terminal leg를 무조건 거절하는 규칙이 없다.

| operation | 새 prepare가 받는 원주문 | claim 뒤 전송 조건 |
| --- | --- | --- |
| CREATE | 원주문 없음; 새 ConditionRequest를 검증한다 | 날짜·env 및 OCO 현재가 관계를 재검사한다 |
| MODIFY | 공식 상세 schema의 모든 group/leg 상태와 STOP/PROFIT_RATE; 새 STOP 본문은 전체 재설정 계약으로 검사한다 | 투영 원문이 준비 당시와 키·값·타입까지 같아야 한다 |
| CANCEL | 공식 상세 schema의 모든 group/leg 상태와 STOP/PROFIT_RATE; expireDate 부재를 허용한다 | 투영 원문이 준비 당시와 키·값·타입까지 같아야 한다 |

group 상태는 WATCHING/PAUSED/ORDERING/ORDERED/COMPLETED/EXPIRED, leg 상태는 여기에 HOLDING/CANCELED를 더한 공식 enum이다. 새 prepare에서 terminal first만으로 차단하지 않는다.
OTO first=ORDERED 또는 COMPLETED, second=WATCHING인 CANCEL과 PROFIT_RATE 원주문→SINGLE STOP 전체 재설정 MODIFY를 지원 vector로 둔다.
PROFIT_RATE의 targetProfitRate는 퍼센트 문자열 그대로 보존한다. 신규 PROFIT_RATE 생성 필드는 공식 ConditionRequest에 없으므로 만들지 않는다.
이 수용은 공개 REST 계약을 지원한다는 뜻이다. 실서버가 모든 거래 상태에서 성공한다는 보증은 아니며 upstream 거절은 아래 단일전송 결과 규칙을 따른다.
claim 뒤 원문의 어느 공식 필드라도 바뀌거나 ID가 다르면 금융 전송은 0회다. 새 prepare에서 이미 존재한 상태와 준비 이후 발생한 변화를 혼동하지 않는다.
유효기간이 없는 기존 조회도 CANCEL은 원문 부재를 보존해 수용한다. CREATE/MODIFY의 새 유효기간은 필수다.

조건주문 summary의 정확한 키는 orderKind/account/operation/conditionalOrderId/symbol/market/currency/type/quantity/orderType/expireDate/first/second/original/normalization이다.
orderKind는 CONDITIONAL, account는 끝 네 자리, market은 KR/US, currency는 KRW/USD, operation은 기존 CREATE/MODIFY/CANCEL이다.
CREATE의 conditionalOrderId/original은 null이며 MODIFY/CANCEL은 승인 대상 ID와 공식 원주문 투영이다.
CREATE/MODIFY의 first/second는 쓰기 leg이고 SINGLE second는 표시에서 null이다. CANCEL은 조회 leg를 표시하며 목표수익률·상태·발동 주문 ID까지 읽을 수 있다.
CANCEL의 expireDate 부재는 summary에서 null로 표시하되 original/expected_conditional_order에서는 부재를 보존한다.
normalization은 symbol/quantity/first.triggerPrice/first.orderPrice/second.triggerPrice/second.orderPrice의 표기 변경만 `{field,before,after}`로 남긴다.
symbol은 공식 허용 문자열의 ASCII 소문자만 대문자로 바꾼다. decimal은 ASCII `[0-9]+(\.[0-9]+)?`, 원문 1~30자와 양수 여부를 검사하고 정수부 선행 0·소수부 후행 0·빈 소수점을 제거한다. 반올림·절삭·Number 변환은 없다.
각 변경은 before가 모델 원문, after가 실행 값이어야 하며 반드시 canonical(before)==after다. 002→2와 00100.00→100은 허용하지만 002→200, 00100.00→200, AAPL→MSFT는 거절한다.
모델에 존재하는 허용 필드의 표기가 실제 달라진 경우만 경로 순서대로 한 번 기록한다. 중복·누락·추가·동일 before/after·숫자 타입·없는 필드 추가·null↔부재 변경은 거절한다.
type/orderType/expireDate/conditionalOrderId와 leg.orderSide는 모델과 실행이 정확히 같아야 한다. first/second는 이름 있는 객체이며 교환·배열 변환·leg 추가·삭제는 거절한다.
현재 일반 소비자의 값 변경 수용 결손은 별도 수정의 책임이다. 조건 검증은 이 의미 보존 규칙을 새로 보완하고, 일반 분기 이동은 그 수정이 반영된 main의 회귀를 보존한다.
executionArgs는 정규화한 모델 입력에서 false 동의를 뺀 값, account_seq/market/currency와 CREATE의 clientOrderId, MODIFY/CANCEL의 expected_conditional_order를 가진다.
CANCEL의 symbol 등 원주문 표시 값은 실행 입력에 복제하지 않고 expected_conditional_order에서 읽는다.

#### 실제 소비 계약의 결손과 보완

현재 Python connector_guard_validation._prepare_result는 일반 주문 SUMMARY와 orderId/side/timeInForce를 요구하므로 조건주문을 거절한다.
Spring ConnectorExecutionSnapshot도 일반 주문의 SUMMARY/ORIGINAL/EXPECTED만 허용하며 requestKey는 expected_order/clientOrderId 이외의 값을 문자열로 요구한다. leg 객체를 그대로 넣으면 실패한다.
Python에 별도 connector_conditional_validation.py, Spring에 ConnectorConditionalSnapshot을 두고 조건 summary와 실행 입력의 정확한 키·타입·공식 분기·정규화 전후를 검증한다.
기존 외피 `{v:1,executionArgs,summary}`와 approval-claim-v1, 바이트·깊이 상한은 유지한다. 검증 분기는 manifest `execution_guard.kinds` 가 도구에 선언한 종류가 고르고, summary 의 orderKind 는 그 종류와 같아야 한다. 금융 권한은 기존 guard·현재 바인딩·서명·DB 소비가 정한다.
일반 주문 분기를 수정해 임의 JSON을 허용하지 않는다. 조건 분기는 신규 summary 키가 정확히 일치할 때만 선택하고 모델 인자·실행 인자·표시 값을 함께 검증한다.
Python RESERVED와 Spring RESERVED_SCOPE_NAMES는 다음 전체 집합으로 같게 한다.
`orderId symbol market currency side quantity orderAmount price orderType timeInForce status filledQuantity execution clientOrderId expected_order confirmHighValueOrder userId connectionId tool orderKind account operation original normalization conditionalOrderId type expireDate first second expected_conditional_order orderSide triggerPrice targetProfitRate orderPrice triggeredOrderId createdAt`.
조건 scope는 정확히 `{account_seq: 문자열}` 하나다. 양쪽 scope_fields도 account_seq만 선언하고 연결 공개 account와 env를 대조한다. 이 필드는 예약 집합에 넣지 않는다.

조건 의도 키는 다음 고정 순서로 SHA-256을 계산한다. 일반 주문 키는 바꾸지 않는다.

| operation | 순서대로 넣는 경로 |
| --- | --- |
| 공통 접두 필드 | operation, userId, connectionId, tool, account_seq, market, currency |
| CREATE | 공통 뒤 symbol, type, quantity, orderType, expireDate, first, first.orderSide, first.triggerPrice, first.orderPrice, second, second.orderSide, second.triggerPrice, second.orderPrice |
| MODIFY | 공통 뒤 conditionalOrderId, type, quantity, orderType, expireDate, first, first.orderSide, first.triggerPrice, first.orderPrice, second, second.orderSide, second.triggerPrice, second.orderPrice |
| CANCEL | 공통 뒤 conditionalOrderId |

market은 실행의 StockInfo.market 원문이다. symbol과 decimal은 위 canonical 값이며 userId/connectionId는 양의 정수의 10진 문자열이다.
LP(s)는 UTF-8 byte 길이의 ASCII 10진수, `:`, UTF-8(s)를 차례로 붙인 값이다. 해시 원문은 LP(`fos-conditional-intent-v1`) 뒤 각 경로마다 경로의 LP와 value를 붙인 바이트다. 구분자·BOM·개행을 더하지 않는다.
value는 문자열이면 `S`+LP(value), 부재면 `A`, null이면 `N`, 존재하는 first/second 객체면 `O`다. 부재 객체의 하위 경로도 각각 A다. 쓰기 입력의 null은 검증에서 거절하되 인코딩 A/N을 혼동하지 않는다.
clientOrderId와 expected_conditional_order는 의도에 넣지 않는다. 원주문과 generated UUID를 바꾸어 UNKNOWN 충돌을 우회할 수 없어야 한다.

공통 known hash 입력은 operation=CREATE, userId=1, connectionId=2, tool=create_conditional_order, account_seq=1234, market=NASDAQ, currency=USD, symbol=AAPL, type=SINGLE, quantity=1, orderType=MARKET, expireDate=2026-10-12, first={orderSide:BUY,triggerPrice:100}, second 부재다. first.orderPrice와 second 하위 값은 A다.
아래 값은 독립 Python 계산의 expected이며 미래 Java 실행 증거가 아니다.

| vector | expected SHA-256 |
| --- | --- |
| KEY-AAPL | 76499167ff1329a07ededd73d5cb9f9508f787b079053d9a4e4bebd17ce8f332 |
| KEY-MSFT: symbol만 MSFT | b004ccf50aea679e139c997826e79955cca0f6b91a762f5cce1adbb003e96dda |
| KEY-MODIFY: operation=MODIFY, tool=modify_conditional_order, symbol 대신 conditionalOrderId=cond-1 | 8c57876cd9da4fd1f23cb283b3125947d1fdbbc49bbf1c85ec480ca96d8e77bf |
| KEY-CANCEL: operation=CANCEL, tool=cancel_conditional_order, conditionalOrderId=cond-1 | 918d32df5f532d4e22cca7404487c8b15f7122f1707483415b082bb0a1f66fca |

Python/Java는 같은 원문 바이트와 expected를 검사한다. aapl→AAPL, 001.00→1, 00100.00→100은 KEY-AAPL과 같고 다른 symbol·대상 ID·operation은 달라야 한다. UTF-8 다중 byte 길이와 A/N 차이도 검사한다.
실제 producer의 사용자 잠금 아래 conflicts로 PENDING/EXECUTING/UNKNOWN 중복을 검증하는 것은 실제 DB 통합의 완료 조건이다. clientOrderId 또는 expected만 바꾼 같은 의도는 차단하고 다른 종목은 차단하지 않는다.
암호화 저장·원문 SHA·AAD·ticket/claim DTO는 재사용하며 신규 컬럼·migration·새 wire 권한 칸은 없다.
공통 vector는 modelArgsJson/executionArgsJson/summaryJson/scopeJson을 객체 대신 정확한 UTF-8 원문 문자열로 보관하고 각 SHA를 명시한다. 검증 뒤 원문을 재직렬화하지 않는다.
model/execution은 각16 KiB, summary8 KiB, scope2 KiB이며 strict duplicate/trailing token·nesting5를 유지한다. 한글 다중 byte로 상한 직전/초과와 중복 키·trailing JSON을 검사한다.
AAD는 `connector_action_execution:<actionId>:column:<column>:user:<userId>`이고 column은 execution_args_json/summary_json/scope_json이다. 실제 TextCipher와 동일 keyId로 세 원문을 seal/open하며 column·action·user·keyId 변경을 거절한다.
현재 main 및 고정 일반 금융 source에는 sourceContext라는 정의나 조건 producer가 없다. 임의 DTO가 이미 있다고 가정하지 않는다. 실제 producer가 준비 전 복사하는 소유권 맥락과 저장 후 summary 읽기는 별도 통합에서 확인한다.
기존 승인 카드 조회는 현재 카탈로그와 원래 모델 인자를 사용한다. 저장 summary를 공개 화면에 연결하는 일과 일괄 승인 제외는 운영 개방의 후속 범위이며 이 보완안의 완료 조건이 아니다.

#### 일회 실행과 HTTP 예산

prepare → 실제 Python 결과 검증 → 불변 원문·summary 저장 → 별도 승인 → ticket → 원문 대조 → claim 1회 → 현재 원주문/현재가 대조 → 금융 전송 1회 순서다.
prepare는 금융 전송을 하지 않는다. 승인 뒤 prepare·UUID·본문 정규화를 다시 수행하지 않는다. SDK schema에도 coercion/default/strip을 두지 않는다.
claim 요청은 기존 ticket/tool/argsSha256/scope, 응답은 v/allowed/ticketId/expiresAt이다. 성공은 200, replay는 409이며 redirect·재시도는 0회다.
SDK 테스트의 합성 claim은 이 실제 DTO·codec·MVC 테스트와 대조하지만 실제 서명·DB 일회 소비를 증명한 것으로 보고하지 않는다.
claim 뒤 조회·auth·quota 대기와 금융 queue callback에서 만료 및 계좌 env를 다시 확인한다. 불일치하면 금융 전송 0회다.
claimApproval은 검증된 `ClaimedApproval={beforeSend:()=>void, deadlineMs:number}`를 반환한다. deadlineMs는 응답과 ticket expiresAt를 한 번 대조한 직후 `clock.now()+max(0,expiresAt-max(Date.now(),clock.now()))`로 변환한 절대 deadline이며 immutable이다.
`financial(method,path,body,authorization:(()=>void)|ClaimedApproval)`은 기존 callback 인자를 계속 받는다. 새 일반/조건 호출부는 ClaimedApproval을 넘기며 `min(clock.now()+timeoutMs,deadlineMs)`를 auth·quota·fetch·본문 읽기에 적용한다. 다른 모듈은 ticket을 재파싱하지 않는다.
큐 안에서 beforeSend와 deadline을 다시 검사한 뒤에만 started=true로 만들고 fetch를 호출한다. 잔여100ms 전송은 같은 deadline에 abort하고 시작 뒤 실패는 UNKNOWN이다. 만료 전에 끝내지 못한 본문 읽기도 abort/cancel하여 UNKNOWN으로 끝내며 추가 전송은 없다.
Spring의 prepare 응답 읽기 제한2초는 여러 GET 각각의4초와 다르다. 실제 HttpHermesConnectorGuard.prepareClient의 read timeout을 재사용한다.
cold auth·429 대기로 prepare 응답 읽기가2초를 넘거나1 MiB 초과 응답을 받으면 prepare 실패, snapshot 저장0회·금융0회다. 읽기 제한을 연결 시간을 포함한 전체 처리 deadline이나 모든 준비의2초 내 성공 보장으로 설명하지 않는다.

| 구간 | 논리 호출 상한 | 재시도와 시간 경계 |
| --- | --- | --- |
| prepare CREATE | accounts·stocks 각 1, OCO만 prices 1 | READ는 기존 GET 최대 3회 전송 정책, 금융 0회; Spring prepare 읽기 제한 2초 |
| prepare MODIFY/CANCEL | 상세·accounts·stocks 각 1, MODIFY OCO만 prices 1 | 동일 READ 정책; CANCEL은 prices 0회 |
| 실행 | claim 1, MODIFY/CANCEL 상세 1, CREATE/MODIFY OCO prices 1 | READ 재시도 예산을 금융 재시도와 분리한다 |
| 금융 | CREATE/MODIFY POST 1 또는 CANCEL DELETE 1 | retry 0, noAuthRetry; 기존 REQUEST_TIMEOUT_MS와 ticket 남은 시간 중 짧은 deadline |
| 토큰 | 기존 client identity의 공유 확보 | 각 금융 호출의 확보는 1회, 금융 응답 401 뒤 재발급·재전송 0회; READ 재발급 counter는 별도로 센다 |

[공식 개요](https://openapi.tossinvest.com/openapi-docs/overview.md)의 CONDITIONAL_ORDER는 초당 5회, CONDITIONAL_ORDER_HISTORY는 10회다. 기존 그룹 queue와 응답 헤더 기반 완화를 재사용한다.
전송 전 검증·토큰 실패는 FAILED다. 전송 후 공식 ErrorResponse와 확인된 operation별 거절 코드가 맞는 경우만 FAILED다.
아래 allowlist는 [공식 OpenAPI](https://openapi.tossinvest.com/openapi-docs/latest/openapi.json)의 operation 응답과 [공식 개요](https://openapi.tossinvest.com/openapi-docs/overview.md)의 HTTP/code 표를 함께 대조한 것이다. 정확한 operation+status+code와 유효한 ErrorResponse가 모두 맞을 때만 FAILED다.

| 적용 operation | HTTP | code | 근거 |
| --- | --- | --- | --- |
| CREATE/MODIFY/CANCEL | 400 | invalid-request, account-header-required | 각 operation의400 + 개요 공통 계좌/요청 오류 |
| CREATE/MODIFY | 400 | confirm-high-value-required | 개요 생성/정정 동의 오류; false 계약은 유지 |
| CREATE | 404 | stock-not-found | CREATE 응답 예시 |
| MODIFY/CANCEL | 404 | conditional-order-not-found | MODIFY 예시·CANCEL404 schema + 개요 조건 ID 오류 |
| CREATE/MODIFY/CANCEL | 404 | account-not-found, edge-blocked | 개요 공통 계좌/경로 거절 |
| CREATE/MODIFY/CANCEL | 401 | invalid-token, edge-blocked, expired-token, token-revoked, login-user-not-found | 개요 공통 인증 오류 |
| CREATE/MODIFY/CANCEL | 403 | edge-blocked, ip-not-allowed, forbidden | 개요 공통 권한/IP 오류 |
| CREATE/MODIFY/CANCEL | 414 | edge-blocked | 개요 공통 URI 오류 |
| CREATE/MODIFY | 415 | unsupported-content-type | 개요 JSON 본문 오류 |
| CREATE/MODIFY | 422 | duplicate-conditional-order, condition-already-met | operation422 + 개요 조건주문 명시 코드 |
| CREATE | 422 | idempotency-key-conflict | 개요 조건주문 생성의 clientOrderId 충돌 |

422의 나머지 일반주문 전용/적용 범위 미확정 코드는 조건 allowlist에 복사하지 않는다. 이는 REST 본문 지원 범위를 줄이는 제한이 아니라 결과가 불확실할 때 재전송을 막는 분류다.
ErrorResponse.error의 requestId/message/code 문자열과 선택 data의 object/null 계약을 공식 parser로 검사한다. HTTP만 같고 code가 다르거나 envelope가 불량이면 UNKNOWN이다.
모르는 code·409 처리 중·429·5xx·timeout·본문 읽기 실패·불량 2xx/204는 UNKNOWN이다. allowlist 인증 거절이어도 금융 응답 뒤 auth 재발급·금융 재전송은0회다.
CREATE의 clientOrderId 누락/null은 공식상 성공이며 존재하면 보낸 UUID와 같아야 한다. MODIFY는 새 conditionalOrderId만 요구한다. 설명에 언급된 type을 응답 필수 필드로 발명하지 않는다.
UNKNOWN은 자동 재실행·ticket 재발급·UUID 교체를 하지 않는다. 실제 producer의 동일 의도 PENDING/EXECUTING/UNKNOWN 충돌 차단을 재사용하고 supersedes_unknown_action_id는 NULL로 유지한다.

#### 구현 관심사와 증거

| 관심사 | 예상 운영 변경 | 필수 완료 증거 | 실제 main 선행 조건 |
| --- | --- | --- | --- |
| 조건 snapshot 소비 계약 | 일반 주문 검증 이동·삭제와 Python·Spring 분기1310줄 배분; 여유190줄 | 공통 정상/거절 vector의 직접 Python 소비와 실제 Spring validate/capture/open; 일반 금융 회귀 | 기존 snapshot·ticket·claim 정의만 필요 |
| 조건 SDK 준비와 단일전송 | TS 모듈·client와 guard982줄 배분; 여유518줄 | 실제 SDK→실제 Python loader/_prepare_result→합성 claim→loopback 목적지, 세 method/path/body/response 수신 및 retry 0 | 일반 금융 모듈과 조건 조회 모듈이 main에 존재해야 한다; 실제 DB producer와 운영 허가는 불필요 |
| 실제 DB producer 통합 | 기존 업무 구현으로 추가 운영0줄 가정; actual main에서 재검증 | H2/MySQL 저장·승인·실제 claim HTTP→Python→SDK→목적지 왕복과 UNKNOWN DB 상태 | guard 설치·일반 금융 모듈·실제 dormant producer가 main에 존재해야 한다 |

각 관심사는 설계·구현·회귀를 함께 담은 독립 PR이며 운영 변경 1500줄 상한과 파일 길이를 지킨다. 계획만 PR로 올리지 않는다.
모든 관심사는 먼저 실패하는 회귀를 확인하고 같은 커밋에서 구현과 성공 증거를 남긴다. 직접 수신 counter는 auth POST/prepare GET/claim POST/금융 POST/DELETE로 나눈다.
변조·숫자 decimal·null/부재 변경·C0/C1/Cs·만료·revision/owner/profile/manifest 변경·철회·replay는 금융 0회다. 429/5xx/끊김/불량 성공의 UNKNOWN과 추가 전송 0회를 실패 주입으로 확인한다.
운영 server/manifest의 금융 도구 0개, 착수 당시 main의 READ 합집합, noGrant와 RISK_NOT_OPEN을 회귀로 지킨다.
실계좌·실토큰·거래·배포는 세 관심사 모두 범위 밖이다. mock 성공을 운영 거래나 공개 승인 E2E 완료로 보고하지 않는다.

### 조건주문 snapshot 소비 계약

Python의 `connector_conditional_validation`과 Spring의 `ConnectorConditionalSnapshot`은 조건주문 prepare 결과를 검증한다.
manifest `execution_guard.kinds` 가 도구를 `CONDITIONAL` 로 선언한 경우에만 이 분기를 고르고, 그 도구의 summary 는 `orderKind=CONDITIONAL` 이어야 한다.
선언하지 않은 도구는 일반 주문(`ORDER`)이며 조건 summary 를 내면 거절한다. prepare 가 낸 summary 가 분기를 정하면 사람은 조건주문 카드를 승인하는데 일반 도구가 불리는 일이 생긴다.
Spring 은 producer 와 승인·claim 재검증(`ConnectorExecutionCurrent`) 두 곳에서, Python 은 `_prepare_result` 에서 같은 대조를 한다.
일반 주문은 기존 정규화 의미 보존 규칙을 유지하며 `ConnectorOrderSnapshot`이 검증한다.
이 구현은 금융 권한을 발급하지 않는다. 금융 도구의 `RISK_NOT_OPEN`, noGrant와 `confirmHighValueOrder=false`를 유지한다.
실제 producer, SDK 금융 전송, claim 뒤 원주문 조회, 등록과 운영 개방은 아직 연결하지 않았다.

조건 summary는 정확히 다음 15개 키를 갖는다.
`orderKind account operation conditionalOrderId symbol market currency type quantity orderType expireDate first second original normalization`.
account는 끝 네 자리이고, scope와 선언은 연결 공개 account에 대응하는 `account_seq` 하나만 받는다.
실행 시장은 KOSPI/KOSDAQ/KR_ETC이면 KRW와 KR, NYSE/NASDAQ/AMEX/US_ETC이면 USD와 US에 대응한다.
실행에는 일곱 시장 원문을 저장하고 summary와 원주문에는 KR/US를 쓴다. 다른 통화 조합과 실행의 KR/US는 거절한다.

| operation | 모델 입력과 실행 입력 | 표시와 원주문 |
| --- | --- | --- |
| CREATE | symbol/type/quantity/orderType/expireDate/first가 필수다. second와 false 동의만 선택이며 실행에는 scope·market·currency·UUID clientOrderId를 더한다 | conditionalOrderId와 original은 null이다. SINGLE의 표시 second는 null이고 실행에는 없다 |
| MODIFY | conditionalOrderId와 새 쓰기 값 전체가 필수다. symbol과 clientOrderId는 받지 않으며 실행에 expected_conditional_order를 더한다 | 공식 원주문과 expected의 키·값·타입이 같아야 한다. 원주문 symbol을 표시하며 타입 전환을 허용한다 |
| CANCEL | conditionalOrderId만 모델에서 받는다. 실행에는 scope·market·currency·expected_conditional_order만 더한다 | 원주문의 공식 상세와 leg를 표시한다. expireDate 부재는 표시에서만 null이며 원주문에는 부재로 남긴다 |

쓰기 leg는 orderSide/triggerPrice와 LIMIT일 때 orderPrice만 받는다. SINGLE은 second를 생략하고 OCO는 SELL→SELL, OTO는 BUY→SELL이며 두 타입은 LIMIT만 받는다.
quantity는 KR/US 모두 양의 1~30자 decimal 문자열이다. KR 소수 수량에 별도 정수 제약을 넣지 않는다.
KR의 새 orderPrice는 정수, US는 1달러 미만 소수 네 자리·이상 두 자리까지다. 원주문의 가격을 새 쓰기 가격 규칙으로 다시 제한하지 않는다.
OCO는 first.triggerPrice가 second.triggerPrice보다 커야 한다. 현재가 사이 관계는 실제 준비·전송 단계가 확인해야 한다.
expireDate는 실제 YYYY-MM-DD인지 검증한다. 저장 원문은 지난 날짜에도 읽을 수 있어야 하므로 오늘부터 1년의 실행 시점 제한은 producer와 SDK가 별도로 확인해야 한다.
금액·timeInForce·모델 scope·generated ID·expected 입력과 true 동의, 추가 키·숫자 decimal·잘못된 leg를 거절한다.

원주문은 공식 `ConditionalOrderDetailResponse`와 `ConditionalOrderCondition`의 필드만 받는다.
모든 공식 group/leg 상태와 STOP/PROFIT_RATE를 수용한다. OTO 첫 leg가 ORDERED/COMPLETED이고 둘째가 WATCHING인 CANCEL과 PROFIT_RATE 원주문에서 STOP으로 전체 재설정하는 MODIFY도 수용한다.
PROFIT_RATE의 targetProfitRate는 퍼센트 원문을 그대로 둔다. 신규 쓰기에 PROFIT_RATE 필드를 만들지 않는다.
부재와 null을 구분하고 createdAt/expireDate, leg 타입·상태·가격·발동 주문 ID까지 expected와 대조한다.
이 대조는 새 prepare의 소비 검증이며 claim 뒤 원주문이 그대로인지 확인하는 실행 검증은 후속 연결의 책임이다.
불투명 ID는 점 경로·공백·슬래시·역슬래시·query/hash/percent/colon·C0/C1·잘못된 surrogate를 거절한다.

normalization은 symbol/quantity/first.triggerPrice/first.orderPrice/second.triggerPrice/second.orderPrice 순서의 실제 표기 변경만 `{field,before,after}`로 기록한다.
ASCII symbol 대문자화와 decimal의 선행·후행 0 제거만 허용한다. before는 모델 원문, after는 실행 값이며 canonical(before)==after여야 한다.
002→2와 00100.00→100은 허용하고 002→200은 거절한다. enum/date/ID/orderSide, leg 순서와 null/부재를 바꾸지 않는다.
중복·누락·추가 기록, 같은 before/after, 없는 값 추가와 leg 배열화를 거절한다.

조건 분기에서 거절하는 전체 예약 이름은 다음과 같다. 정확한 account_seq 하나만 받으므로 이 이름들은 scope에 들어갈 수 없다.
`orderId symbol market currency side quantity orderAmount price orderType timeInForce status filledQuantity execution clientOrderId expected_order confirmHighValueOrder userId connectionId tool orderKind account operation original normalization conditionalOrderId type expireDate first second expected_conditional_order orderSide triggerPrice targetProfitRate orderPrice triggeredOrderId createdAt`.
일반 주문과 manifest의 기존 19개 예약 이름은 유지한다. manifest에는 orderKind 분류 정보가 없고 기존 일반 snapshot은 account arg도 허용하므로 공통 집합을 확대하면 기존 저장 내용의 재검증을 거절하게 된다.
조건 분기에서만 이 제약을 적용해 일반 계약을 보존한다. 연결의 공개 field 이름은 두 분기 모두 예약 검사의 대상이 아니다.

조건 의도 키는 LP(`fos-conditional-intent-v1`) 뒤 경로마다 LP(경로)와 값 인코딩을 붙인 SHA-256이다.
LP는 UTF-8 byte 길이의 10진수, 콜론, 원문 byte다. 문자열은 S+LP(값), 부재는 A, null은 N, 존재하는 leg 객체는 O이며 부재 객체의 하위 값도 A다.
조건 의도 키 primitive는 tool을 포함한 LP 문자열의 UTF-8 인코딩을 엄격히 검사한다. 잘못된 surrogate를 대체 문자로 바꾸지 않고 거절하며 한글과 정상 비 BMP 문자의 해시는 유지한다.
manifest의 도구 이름은 기존 ASCII 등록 규칙을 유지한다. primitive에서 정상 UTF-8인 `tool?`의 키를 계산해도 그 이름을 등록할 수 있다는 뜻은 아니다.

| operation | 고정 경로 순서 |
| --- | --- |
| 공통 | operation/userId/connectionId/tool/account_seq/market/currency |
| CREATE | 공통 뒤 symbol/type/quantity/orderType/expireDate/first/first.orderSide/first.triggerPrice/first.orderPrice/second/second.orderSide/second.triggerPrice/second.orderPrice |
| MODIFY | CREATE의 symbol 대신 conditionalOrderId를 넣는다 |
| CANCEL | 공통 뒤 conditionalOrderId |

userId/connectionId는 양의 정수의 10진 문자열이며 market은 실행 원문이다. clientOrderId와 expected는 의도 키에서 제외한다.
다른 symbol·대상 ID·operation은 다른 키가 된다. UUID와 현재 원주문만 바꾸어 같은 의도를 우회하지 못하며 일반 주문 의도 키는 유지한다.
실제 DB producer의 중복 의도 차단은 이 키 계산과 별도로 연결해야 한다.

공통 [원문 vector](../../test/resources/conditional-snapshot-v1.json)는 model/execution/summary/scope를 정확한 UTF-8 문자열과 각각의 SHA로 보관한다.
Python은 strict parser와 실제 prepare 소비를, Spring은 validate/capture와 실제 사용자 암호화·H2/MySQL 저장 후 open을 검사한다.
Spring 원문 상한은 각각 16/16/8/2 KiB와 nesting5이며 중복 키와 trailing JSON을 거절한다. Python은 prepare 외피와 각 객체의 기존 byte·깊이 상한을 적용한다.
공통 179개에서 Spring 원문 경계의 기대와 실제는 수용 47개·거절 132개다. Python의 실제 `_strict_json`과 `_prepare_result` 객체 소비는 수용 51개·거절 128개다.
차이인 `RAW-modelArgs-BYTES`, `RAW-executionArgs-BYTES`, `RAW-summary-BYTES`, `RAW-scope-BYTES`는 추가 공백이 객체 변환 뒤 사라져 Python에서 허용되며 나머지 175개는 기대가 같다.
테스트의 네 원문 길이 단언은 Spring 경계의 기대를 설명하는 검사이며 Python production의 거절로 세지 않는다. Python 외부 API는 기존 객체 계약을 유지한다.
저장 시 실행·scope 원문 SHA를 유지하고 세 원문을 다시 직렬화하지 않는다. 같은 keyId로 seal/open하며 AAD는 `connector_action_execution:<actionId>:column:<column>:user:<userId>`다.
열·action·사용자·keyId와 저장 summary의 변조를 거절한다. table·migration·ticket·claim DTO와 암호화 형식은 바꾸지 않는다.

### 요청 경계와 테스트

API base는 고정한다. path 식별자는 한 segment로 `encodeURIComponent`하고 query는 URLSearchParams로 만든다.
조회는 GET과 body 없음, 공식 계좌 대상만 env의 계좌 헤더를 사용한다. redirect와 프록시 경유를 허용하지 않는다.
기존 Bun `FakeToss`와 실제 MCP 등록을 사용해 method, URL, query, header와 body를 검증한다.
공식 예시의 모든 필드, nullable, 긴 decimal과 cursor, 빈 결과와 여러 페이지를 검사한다.
잘못된 입력은 HTTP 0회이고 429, 5xx, timeout, credential/IP/revoke, malformed, 응답 크기 초과를 각 도구 그룹에서 검사한다.
소스 AST와 자체 복제 schema의 일치만으로 통과시키지 않는다. 빌드한 bundle의 SDK 호출이 로컬 HTTP 목적지에 도달해야 한다.
CI의 `hermes` job과 `scripts/checks/check-connectors.sh`가 새 테스트를 실행하며 구현 PR마다 전체 로컬 검사와 별도 검토를 통과한다.

### 일반 금융 모듈

`prepare-trade.ts`와 `trading-tools.ts`는 일반 주문 CREATE·MODIFY·CANCEL을 구현한다.
운영 `server.ts`는 이 등록 함수를 호출하지 않는다. 운영 manifest는 READ 목록을 유지하고
FINANCIAL 도구와 execution_guard를 선언하지 않는다. 공개 hook·approve의 RISK_NOT_OPEN과
plugin의 금융 숨김, 상시 허락 차단, DESTRUCTIVE 차단도 유지한다.

prepare는 계좌 끝 네 자리, 공식 StockInfo의 market·currency와 정정·취소의 원주문을 조회한다.
실행 args와 summary는 [금융 저장 계약](connector-policy.md#표시-값과-실행-인자)을 따른다.
모델 예약 키를 거절하고 문자열 decimal 표기와 기본 DAY의 변경을 normalization에 기록한다.
CREATE의 UUID는 여기서 한 번 생성한다. US 가격은 1달러 미만 소수 넷째 자리,
1달러 이상 둘째 자리까지 받으며 값을 절삭하지 않는다. confirmHighValueOrder는 false만 받는다.
공식 inline oneOf의 수량·금액 분기별로 본문을 고르고 금액 본문에서 timeInForce·price를 뺀다.
quantity는 주 단위 수량, KR price는 원, US price와 orderAmount는 달러다.
수량 주문은 quantity, US MARKET 금액 주문은 orderAmount를 사용하며 둘을 함께 넣지 않는다.
공식 금액 주문과 소수 수량 매도는 정규장 시작부터 종료 1시간 전까지 접수할 수 있다.
시간 조건은 서버가 최종 판정하며 준비 성공이 접수 가능 시간을 보장하지 않는다.
금액 원주문을 LIMIT로 정정하는 것은 현재 prepare와 실행 양쪽에서 거절한다.
이를 지원하려면 원주문 대조를 유지하면서 소비 계약과 새 표시 값을 함께 설계해야 한다.
일반 취소는 POST와 `{}`이며 정정·취소 응답의 새 orderId를 보존한다.
정정·취소의 2xx가 원주문과 같은 orderId를 반환하면 계약 위반으로 UNKNOWN이다.
CREATE 응답 clientOrderId의 누락·null을 허용하고 값이 있으면 준비한 UUID와 대조한다.

guard는 자식의 권한 env를 회수하고 실행 원문의 바이트 SHA와 SDK 인자의 모든 키·값·타입을 대조한다.
env 계좌를 문자열 그대로 대조한 뒤 ticket·tool·argsSha256·scope로 claim을 한 번 호출한다.
claim 응답의 v·allowed·ticketId·expiresAt을 검사하며 redirect나 재시도를 하지 않는다.
정정·취소는 claim 뒤 원주문을 다시 읽어 expected_order와 filledQuantity까지 대조한다.
금융 fetch를 시작하는 quota queue callback에서 계좌와 ticket 만료를 마지막으로 검사한다.
POST·DELETE는 GET 재시도 루프를 공유하지 않는다. 전송 전 실패와 공식 명백한 거절은 FAILED,
429·5xx·timeout·불량2xx와 불분명한 오류는 UNKNOWN이며 자동 재전송하지 않는다.
MODIFY·CANCEL도 UNKNOWN이면 새 권한이나 UUID로 자동 재전송하지 않는다.
공식 해당 operation의 명백한 거절 401은 FAILED, 미지·해석 불가 401은 UNKNOWN이다.
두 경우 모두 토큰 재발급과 금융 재전송은 없다.
client의 financial은 승인 검증을 제공하지 않는 저수준 내부 transport다.
생산 금융 호출자는 trading-tools이며 claim과 본문 검증을 거친다. 타입만으로 보안 경계를 만들지 않는다.

테스트 원본의 단일 생산자는 `tests/fixtures/trading-entry.ts --generate`다.
운영 manifest·plugin·MCP 정의를 복사한 고유 임시 디렉터리의 사본에만 금융 선언과 실제 SDK bundle을 넣는다.
Python은 이 원본을 실제 `_load_connector`와 `_guard_manifest`로 읽고 준비 결과 소비자로 검증한다.
이 소비자가 계산한 manifest SHA를 후속 catalog·bind·support 연결에도 사용해야 한다.
각 일반 주문의 금융 POST 1회와 replay·변조·GET/auth/quota 만료의 금융 POST 0회를 수신 counter로 검증한다.
auth POST, 준비 GET, claim HTTP와 금융 POST·DELETE는 별도로 센다. 목적지와 claim은 loopback만 허용한다.

이 증거는 실제 SDK·claim 대역·mock 금융 목적지의 왕복이다. 대역의 protocol vector는 main의
ClaimResponse·codec·claims·MVC 테스트와 직접 대조하지만 실제 서명 인증·DB 일회 소비는 검증하지 않는다.
실제 DB/Spring claim→Python→SDK→mock 목적지 왕복은 [운영 비활성 금융 snapshot producer](connector-policy.md#운영-비활성-금융-snapshot-producer)가 검증한다.
일반 주문 DELETE는 0회다. DELETE transport의 합성 204 검사는 조건주문 등록의 근거가 아니다.
### 국내 종목 통계 계약

통계 5개는 `registerStockStatisticsTools` 한 곳에서 등록한다. 기존 stock-trend 후보와 동시에 등록하지 않는다.
모두 schema 2의 READ/none이고 금융 승인 정책은 바꾸지 않는다. 연결 계좌 헤더도 통계 GET에 보내지 않는다.
`symbol`은 공식 영문·숫자·점·하이픈 pattern을 유지하되 `.`과 `..` 및 URL segment 주입은 인증 전에 거절한다.
caller의 계좌, token, URL과 추가 인자도 HTTP 0회로 거절한다. 통계는 KR 전용이며 미국 종목의 unsupported-market는 `TOSSINVEST_INVALID_INPUT`이다.
`count`는 기본 10, 1~100의 정수이며 `until`은 실제 날짜(YYYY-MM-DD)다. until은 해당 날짜를 포함한다.
응답 `{result: {records, nextUntil?}, metadata: {}}`의 모든 행과 배열 순서를 보존한다. 다음 페이지에는 nextUntil을 그대로 넣고 null이거나 없으면 끝낸다.
검증된 응답을 공용 `officialFields`로 투영해 공식 필드만 재귀적으로 남긴다. 중첩 객체·배열의 민감 확장키를 제거하고 decimal 문자열, null과 선택 필드의 부재를 보존한다.
잘못된 행은 일부 성공으로 삭제하지 않고 전체 응답 오류로 끝낸다. 자동 페이지 합치기나 수치 변환은 없다.

| 통계 | 집계·단위 | 값의 시점 |
| --- | --- | --- |
| 투자자별 매매 | KRX·NXT 거래량(주), 기관 7개 분류·등록외국인·외국인 보유·CFD. 보유·CFD 비중은 소수 비율 | 장중 잠정치의 개인·기관 세부·기타법인·보유·CFD는 null일 수 있다. 당일 저녁 확정치, CFD 다음 영업일 새벽, 보유 다음 영업일 오전 재갱신을 구분한다. updatedAt은 기록 전체의 마지막 갱신이다 |
| 프로그램 | KRX 거래량(주), 차익 arbitrage·비차익 nonArbitrage. NXT 제외 | 당일 값은 장 종료 전 바뀔 수 있다. 거래대금·updatedAt은 없다 |
| 공매도 | 수량(주), 거래대금(KRW), 소수 비율 | 당일 저녁 확정치. 정규장 외 세션을 포함한 누적 분모의 부재는 null, 0은 문자열 0이다 |
| 신용 | 자기신용·유통금융 합계 수량(주), balanceRate·tradingRate는 소수 비율. marginLoan·stockLoan은 nullable 객체 | 다음 영업일 새벽에 전 영업일 값이 반영된다. 개인 신용대주다 |
| 대차 | 기관 대여·차입의 체결·상환·잔고 수량(주), 잔고 금액(KRW) | 당일 저녁 확정치. 개인 신용대주와 구분한다 |

음수 순매수는 순매도이며 비율을 백분율로 바꾸지 않는다. 장중 확정치나 모든 시장 지원을 보장하지 않는다.
5개 operation은 STOCK_TRADING_TREND 초당 10회와 최소 100ms 간격을 공유한다. AUTH 초당 5회는 별도다.
모듈 테스트와 production SDK·standalone stdio 테스트는 loopback에서 GET 목적지, 빈 body, 인증 POST와 금융 POST·DELETE 0회를 각각 확인한다. 실제 credentials·계좌·거래와 배포는 이 검사의 범위 밖이다.

### 조회 합집합과 공식 필드 투영

종목 참조·섹터·조건주문 조회·랭킹과 지표·주문 상세와 수수료·국내 수급 통계를 같은 production 서버에 등록한다.
기존 시세와 시장 조회, 계좌 조회의 입력·페이지·오류 계약을 유지한다. `getStocks`와 `getSellableQuantity`의 공유 호출은 중복 operation으로 세지 않는다.
조회 GET 34개는 공식 REST의 모든 GET이며 나머지 6개 POST·DELETE 금융 producer는 별도 구현 대상이다.

조건조회, 랭킹·시장 지표, 주문 상세·수수료와 통계는 검증 뒤 공용 `officialFields(value, schema)`로 공식 알려진 필드를 투영한다.
조건조회의 nullable·ref·allOf와 통계의 설명 전용 result 구체화, 정확한 ref/null 두 분기 계약을 함께 사용한다.
decimal 문자열, unknown enum, null과 선택 필드 부재, 배열 전체와 순서를 보존하고 중첩 객체·배열의 비공식 민감 추가 키를 제거한다.
미지원 union·anyOf·map·미정의 ref와 properties 없는 객체는 원문을 반환하지 않고 전체 오류로 막는다.
helper는 검증된 현재 고정 schema에 쓰는 제한된 투영 함수다. 겹치는 중첩 allOf의 범용 무손실 병합이나 알려진 문자열 안의 민감값 탐지는 지원한다고 주장하지 않는다.
기존 종목·시세·계좌·섹터 producer의 모든 확장키까지 일괄 제거하는 계약으로 확대하지 않는다.

합집합 회귀는 SDK와 소스·빌드한 stdio 각각에서 도구 33개·고유 REST 35개를 검사한다.
시장 일정의 KR·US를 각각 호출하고 get_quotes와 get_buying_power의 두 요청을 포함하면 각 경로의 전송은 GET 36회와 인증 POST 1회다.
각 도구의 caller 계좌·token·URL 추가 인자는 stdio에서 인증을 포함해 HTTP 0회로 거절한다.
금융 POST·DELETE는 0회이며 READ/none, 기본 deny, single_binding과 sandbox_required를 유지한다.
