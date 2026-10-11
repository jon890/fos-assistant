# Phase 01. 국내 종목 통계 5개 조회 모듈과 SDK 시험

**Execution profile**: standard

## 목표

`registerStockStatisticsTools(register, client)`를 구현한다.
실제 MCP SDK가 공용 client와 loopback FakeToss를 거쳐 5개 GET에 도달하고 공식 한 페이지를 보존한다.
이 단계가 끝나도 production 등록은 미완료다.
전체 계획은 다음 단계까지 같은 구현 PR에서 끝내며, 그때 계획서를 삭제한다.

**범위 외**: 공용 client, generated 계약, 한도, fixture, generator, server, 등록 helper, manifest, README, SKILL, 기능 문서, support, dist 변경과 push, PR, 배포.
다른 워커가 공용 금융 client를 구현 중이므로 이 단계에서 그 파일을 수정하지 않는다.

## 컨텍스트

**근거 문서**: `docs/features/connector.md`의 「공식 operation과 producer」, 「정본 계약에서 확인할 내용」, 「요청 경계와 시험」과 `docs/features/connector-policy.md`의 READ 정책.
main 문서는 5개 도구를 미연결로 기록하고 있다.
신규 통계 세부 설명이 이미 구현되어 있다고 가정하지 않는다.
공식 세부 근거는 [OpenAPI JSON](https://openapi.tossinvest.com/openapi-docs/latest/openapi.json) 1.2.24와 [개요](https://openapi.tossinvest.com/openapi-docs/overview.md)다.
원문과 `hermes/connectors/tossinvest/tests/fixtures/openapi-1.2.24.json`의 SHA256은 `7e753c21dc4b938d8f9610ce3efd6542783018977cb030e39db5a006f5b59d40`이다.

planning 1~7단계의 판단은 다음과 같다.
기존 Bun, TypeScript, Zod, SDK와 `Tossinvest.request`, `parseApiResponse`, `createToolRegistration`, `FakeToss`, `withMcp`를 재사용하므로 새 의존이나 저장 모델이 필요 없다.
흐름은 엄격한 인자 검증, 내부 인증, 통계 GET, 공식 응답 검증, 한 페이지 반환이다.
실패는 공용 오류 코드로 끝내며 빈 배열은 성공이다.
동시 요청과 429, 크기, deadline은 기존 client와 한도 큐가 맡는다.
제품 범위, 모듈 책임, 데이터 저장과 ADR의 변경은 없다.
운영 설명과 문서 갱신은 이 워커의 권한 밖이며 다음 단계의 명시적 완료 조건이다.

## 작업 항목

### 1. 통계 조회 모듈

| operationId | 도구 | 경로의 마지막 segment |
| --- | --- | --- |
| `getStockInvestorTrading` | `get_stock_investor_trading` | `investor-trading` |
| `getStockProgramTrades` | `get_stock_program_trades` | `program-trades` |
| `getStockShortSelling` | `get_stock_short_selling` | `short-selling` |
| `getStockCreditTrades` | `get_stock_credit_trades` | `credit-trades` |
| `getStockSecuritiesLending` | `get_stock_securities_lending` | `securities-lending` |

경로는 `/api/v1/stocks/{symbol}/` 뒤에 위 segment를 붙인다.
symbol은 공식 영문, 숫자, 점, 하이픈 pattern을 쓰되 `.`과 `..`는 인증 전 거절한다.
URL segment 주입과 caller 계좌, token, URL, extra 입력은 HTTP 0회로 거절한다.
count는 정수 1~100, 기본 10이며 until은 실제 달력에 존재하는 `YYYY-MM-DD`다.
until은 해당 날짜를 포함하고 다음 페이지는 응답 nextUntil을 그대로 전달한다.
자동 페이지 합치기, 행 삭제, 숫자 변환과 nullable 대체는 하지 않는다.
`result` 전체와 기존 `metadata: {}` 형식을 보존한다.
공식 알려진 필드는 generated schema를 따라 모두 보존하며 비공식 확장키는 중첩 객체와 배열에서도 제거한다.
검증은 기존 parseApiResponse가 먼저 수행한다. 투영 과정에서 불량 행을 삭제하거나 값을 변환하지 않는다.
기존 Git 후보 `stock-trend-tools.ts`의 도구 매핑, query, 날짜와 한 페이지 반환 패턴을 재사용하고 점 segment 방어와 공식 필드 투영을 보완한다.

### 2. 필드 의미와 시점

투자자별 매매는 KRX와 NXT 통합 거래량(주)이며 기관 7개 분류, 등록외국인 보유와 CFD를 보존한다.
음수 순매수는 순매도다. 장중에는 개인, 기관 세부, 기타법인, 외국인 보유, CFD가 null일 수 있다.
당일 저녁 확정치, CFD T+1, 외국인 보유 다음 영업일 오전 재갱신을 updatedAt과 함께 해석한다.

프로그램은 KRX 거래량만 집계하며 차익 arbitrage와 비차익 nonArbitrage를 구분한다.
당일 값은 장 종료 전 바뀔 수 있고 거래대금은 없다.

공매도 거래대금은 KRW다.
비중은 정규장 외 세션을 포함한 당일 누적 분모가 없으면 null, 0이면 문자열 `0`이다.
일별 확정치는 당일 저녁에 반영된다.

신용은 자기신용과 유통금융의 합계이며 marginLoan과 stockLoan은 각각 nullable 객체다.
수량은 주, balanceRate와 tradingRate는 소수 비율이며 백분율로 바꾸지 않는다.
신용대주는 개인 신용거래이고 다음 영업일 새벽에 전 영업일 값이 반영된다.

대차는 기관 투자자 간 대여와 차입이다.
체결, 상환, 잔고 수량은 주이고 balanceAmount는 KRW다.
개인 신용대주와 구분하며 일별 확정치는 해당 일자 저녁에 반영된다.

5개 모두 KR 전용이다. 입력 pattern으로 시장을 추측하지 않고 upstream unsupported-market를 오류로 반환한다.
`STOCK_TRADING_TREND`는 client와 그룹 단위 초당 10회이며 인증 AUTH는 별도 초당 5회다.

### 3. `hermes/connectors/tossinvest/tests/stock-statistics-tools.test.ts`의 실제 SDK 시험

`McpServer`와 `createToolRegistration`을 써 모듈만 등록한다.
FakeToss의 허용 operation은 위 5개와 `issueOAuth2Token`뿐이다.
공식 예시 전체, 독립적인 기대 수치, 음수, 긴 decimal, null, optional 부재, 확장 enum 값, 페이지와 빈 결과를 확인한다.
잘못된 행 하나가 있으면 전체 실패하며 정상 행만 남기지 않는다.
수신 method, path, query, body, 계좌 헤더 없음과 별도 인증 POST를 단언한다.
금융 POST와 DELETE는 0회다.
429, HTTP 오류, malformed, 1 MiB와 deadline을 검사하고 finally에서 FakeToss.stop을 보장한다.
공용 helper에는 새 description이 없으므로 이 단계 시험을 최종 description 검증으로 보고하지 않는다.

## 검증

```bash
cd hermes/connectors/tossinvest && bun test tests/stock-statistics-tools.test.ts
cd hermes/connectors/tossinvest && bun test ./src ./tests ./scripts
cd hermes/connectors/tossinvest && bun run typecheck
node scripts/format-connectors.mjs check
node --experimental-strip-types --test test/unit/doc-files.test.ts
bash scripts/check-public-safe.sh
git diff --check
```

무거운 검사는 제공된 heavy-lock으로 이 checkout에서 순차 실행한다.
계획 생성 검사와 구현 뒤 audit, Korean 검사도 실행한다.
bundle은 이 단계의 소유 밖이므로 만들지 않는다.

## 변경 파일

| 파일 | 변경 |
| --- | --- |
| `hermes/connectors/tossinvest/src/stock-statistics-tools.ts` | 신규 |
| `hermes/connectors/tossinvest/tests/stock-statistics-tools.test.ts` | 신규 |
