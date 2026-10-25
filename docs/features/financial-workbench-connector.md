# 금융 워크벤치 커넥터

사용자의 금융 온톨로지 워크벤치를 조회하는 커넥터의 개발 문서다.

covers: `hermes/connectors/financial-workbench/`

코드는 [`hermes/connectors/financial-workbench/`](../../hermes/connectors/financial-workbench) 에 있다.
이 문서는 등록 칸과 운영 값, 도구와 정책, 보안, 실제 계정으로 확인할 것을 갖는다. 사용자 안내는 [금융 워크벤치 커넥터 README](../../hermes/connectors/financial-workbench/README.md) 가 갖는다.
운영 값을 받는 근거는 [ADR-20261025 / connector-operator-base-url](../../hermes/docs/adr/ADR-20261025-connector-operator-base-url.md) 에 있다.
커넥터 공통 계약은 [커넥터 연결](connector.md) 과 [커넥터 도구 정책](connector-policy.md) 이 갖는다.

워크벤치는 공개 저장소 [`jon890/fos-graphrag`](https://github.com/jon890/fos-graphrag) 의 `financial-workbench` 다.
투자 판단을 「세상의 변화 → 병목 → 산업 → 기업 → 밸류에이션(시나리오) → 투자 결정」 으로 잇는 그래프이고, 근거 문서와 검토 상태가 있는 주장, 개념, 사용자별 공부 목록을 갖는다.
이 커넥터가 따르는 API 는 그 저장소의 `financial-workbench/docs/api-contract.md` 가운데 「연동 토큰」 과 「에이전트 API(`/api/v1`)」 절이다.

**이 커넥터는 조회만 한다.** 체결 기록을 워크벤치에 쓰는 일은 다음 단계다.

## 등록 칸

| 칸 | env | 비밀 | 무엇 |
| --- | --- | --- | --- |
| `token` | `FINANCIAL_WORKBENCH_TOKEN` | 그렇다 | 워크벤치의 「더보기 → 연동 토큰」 에서 발급한 개인 연동 토큰. `^fwb_[A-Za-z0-9_-]{43}$` 만 받는다 |

필수다. 확인 도구는 `get_connection` 이다. 등록할 때 토큰으로 `GET /api/v1/me` 를 불러 사용자 표시 이름과 토큰 이름이 읽히면 저장한다.

### 운영 값

| env | 비밀 | 무엇 |
| --- | --- | --- |
| `FINANCIAL_WORKBENCH_BASE_URL` | 아니다 | 운영자가 띄운 워크벤치의 공개 기준 주소. 이 주소 뒤에 `/api/v1/...` 를 붙인다 |

값은 운영 목록이 갖고 이 저장소에는 적지 않는다. 운영 목록에 값이 없으면 대시보드가 카탈로그에서 뺀다.
커넥터는 값이 `https://` 주소이고 계정 정보와 쿼리, fragment 가 없을 때만 부른다. 아니면 부르지 않고 `WORKBENCH_NOT_CONFIGURED` 로 끝낸다. 끝의 `/` 는 지운다.

| 선언 | 값 | 까닭 |
| --- | --- | --- |
| `single_binding` | 선언하지 않는다 | 토큰은 사람마다 5개까지 따로 발급되고, 서로를 무효로 만들지 않는다. 같은 연결을 여러 에이전트에 붙여도 된다 |
| `sandbox_required` | 선언하지 않는다 | `/api/v1` 은 조회만 있다. 셸이 `.env` 의 토큰을 읽어도 할 수 있는 것은 이 커넥터의 도구와 같은 조회다 |

토스증권 커넥터는 두 선언을 모두 참으로 둔다. 두 선언은 토스증권 연결에만 걸리므로, 실행 공간이 있는 에이전트 하나에 토스증권과 워크벤치를 함께 붙일 수 있다.
스킬 이름은 `financial-workbench` 라 토스증권의 `tossinvest` 와 겹치지 않는다.

## 도구와 정책

MCP 서버 이름은 `financial-workbench` 다. 모든 도구가 `READ` 이고 서버에서 `readOnlyHint` 가 참이다.
`/api/v1` 에 `GET` 만 있어 쓰는 도구를 만들 수 없다.

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

### 도구의 인자와 결과

인자는 서버가 부르기 전에 계약의 범위로 검사하고, 어기면 부르지 않고 `WORKBENCH_INVALID_INPUT` 으로 끝낸다.

| 도구 | 인자 | 쿼리 |
| --- | --- | --- |
| `search_nodes` | `q`(100자까지), `types`(쉼표로 이은 타입 키), `page`(0부터), `size`(기본 20, 1~50) | 빈 `q`, `types` 는 보내지 않는다. `page`, `size` 는 늘 보낸다 |
| `get_node`, `trace_node` | `id` | UUID 모양만 받는다. 경로에 들어가는 값이라 다른 경로로 새지 않게 한다 |
| `get_neighborhood` | `id`, `hops`(1이나 2, 기본 1) | `id`, `hops` |
| `lookup_security` | `ticker`(영문, 숫자, 점, 하이픈 32자까지), `market`(선택) | `market` 은 대문자로 바꿔 계약의 값(`KR`, `US`, `KOSPI`, `KOSDAQ`, `NASDAQ`, `NYSE`, `AMEX`, `KR_ETC`, `US_ETC`, `KRX_KOSPI`, `KRX_KOSDAQ`, `OTHER`)만 보낸다 |
| `list_my_knowledge` | `level`(쉼표로 이은 `KNOWN`, `UNSURE`, `LEARNING`), `page`, `size` | 빈 `level` 은 보내지 않는다 |

`lookup_security` 는 토스증권 도구가 준 종목 코드와 시장 코드를 그대로 받는다. 시장 코드를 워크벤치의 시장으로 옮기는 일은 워크벤치가 한다.

결과는 워크벤치 응답의 JSON 을 그대로 돌려준다. `get_connection` 만 `user`, `token`, `rateLimitPerMinute` 세 칸으로 줄인다.
워크벤치가 둔 잘림 표시(`relationsTruncated`, `claimsTruncated`, `matchesTruncated`, `decisionsTruncated`, `valuationsTruncated`, `scenariosTruncated`, `truncated`, `dependentsTruncated`)는 바꾸지 않는다.
워크벤치는 상세의 속성 글을 자르지 않는다. 커넥터는 글 하나가 20,000자를 넘으면 잘라 끝에 `…` 를 붙이고 결과 맨 위에 `textTruncated: true` 를 둔다.
응답 본문이 4 MiB 를 넘으면 읽지 않고 `WORKBENCH_UNAVAILABLE` 로 끝낸다.

### 오류

오류는 코드만 내고 워크벤치의 오류 글은 싣지 않는다.

| 코드 | 언제 | 공통 어휘 | 복구 |
| --- | --- | --- | --- |
| `WORKBENCH_UNAUTHORIZED` | `401`. 토큰이 없거나 형식이 틀렸으면 부르지 않고 같은 코드로 끝낸다 | `credential_rejected` | `reconnect` |
| `WORKBENCH_FORBIDDEN` | `403` | `forbidden` | |
| `WORKBENCH_INVALID_INPUT` | `400`, 또는 서버가 부르기 전에 인자를 거절했다 | `invalid_input` | `fix_input` |
| `WORKBENCH_NOT_FOUND` | `404`. 없거나 보관한 항목이다 | `invalid_input` | `fix_input` |
| `WORKBENCH_RATE_LIMITED` | `429`. `Retry-After` 초를, 없으면 `details.retryAfterSeconds` 를 `retry_after_seconds` 정수로 싣는다 | `unavailable` | `retry_later` |
| `WORKBENCH_NOT_CONFIGURED` | 운영 값이 없거나 `https://` 주소가 아니다 | `unavailable` | |
| `WORKBENCH_UNAVAILABLE` | 그 밖의 상태, 다른 곳으로 넘기는 응답, 시간 초과, 연결 실패, JSON 객체가 아닌 응답 | `unavailable` | `retry_later` |

`401` 을 받으면 스킬이 「워크벤치의 더보기 → 연동 토큰에서 다시 발급해 외부 서비스 연결에서 이 연결을 고치라」 고 안내한다.
`429` 는 다시 부르지 않는다. 토큰 하나의 한도가 1분에 60번이라 같은 호출을 되풀이하면 그 1분을 모두 쓴다.

## 서버와 검사

`src/server.ts` 가 stdio MCP 서버이고 `dist/financial-workbench-mcp.js` 로 묶는다. 생성 entry 는 Git 에서 추적하지 않는다.
요청 하나의 제한 시간은 8초다. 대시보드가 확인 도구를 기다리는 10초 안에 끝나게 한다.
다른 곳으로 넘기는 응답(`3xx`)은 따라가지 않는다. 토큰이 다른 호스트로 따라가지 않게 한다.

검사는 `tests/` 에서 로컬 HTTP 대역으로 돈다. 실제 워크벤치에 닿지 않는다.
대역은 계약의 `/api/v1` 조회 경로만 허용 목록으로 갖고, 목록 밖의 메서드나 경로가 오면 그 테스트가 실패한다.
도구마다의 경로와 쿼리, Bearer 헤더, `401`, `429` 와 `Retry-After`, 잘림 표시, 긴 글 자르기, 다른 곳으로 넘기는 응답, 시간 초과, 부르기 전의 인자 거절, 기준 주소 검사를 본다. 결과에 토큰과 워크벤치의 오류 글이 없는지도 본다.

## 보안

- **쓰는 도구가 없다.** 외부 글이 모델을 속여도 워크벤치를 바꾸지 못한다. 속은 모델이 할 수 있는 것은 토큰 주인이 볼 수 있는 항목을 더 읽는 것이다. 읽은 내용을 다른 도구(셸, 웹)로 내보내는 길은 [READ 데이터 흐름](connector-policy.md) 의 RF-08, RF-09 와 같다.
- 워크벤치의 메모, 주장, 근거 문서 발췌는 사용자와 다른 사람이 쓴 글이다. 스킬이 자료로만 다루게 하고, 그 글이 시키는 주문이나 전송을 하지 않게 한다.
- 토큰은 그 사용자의 역할로 인증되지만 `/api/v1` 에는 조회만 있다. 새면 그 사용자가 볼 수 있는 워크벤치 내용이 읽힌다. 워크벤치에서 그 토큰을 폐기하면 바로 막힌다.
- 토큰과 기준 주소를 결과, 로그, 오류 글에 싣지 않는다. 서버는 로그를 남기지 않는다.
- 기준 주소는 운영자가 정한다. 사용자가 주소를 넣는 칸이 없어 내부망 주소로 토큰을 보내게 할 수 없다.
- 투자 결정과 근거, 공부 목록이 모델 공급자에게 간다. 사용자가 받아들였다.

## 실제 계정으로 확인할 것

배포 뒤 소유자가 자기 워크벤치 계정으로 한 번 확인한다. 실행 방법과 운영 값은 운영 저장소가 갖는다.

1. 운영 목록에 기준 주소를 넣은 뒤 카탈로그에 커넥터가 보이는지
2. 워크벤치에서 발급한 토큰으로 등록하면 확인 도구가 표시 이름과 토큰 이름을 읽는지, 폐기한 토큰은 거절되는지
3. 토스증권을 붙인 에이전트에 함께 붙는지
4. 대화에서 보유 종목 하나를 물으면 토스증권의 보유와 워크벤치의 결정, 시나리오가 함께 나오는지
5. 그 결정으로 근거 따라가기를 물으면 비어 있는 단계가 그렇다고 나오는지
6. 워크벤치의 토큰 목록에서 그 토큰의 마지막 사용 시각이 바뀌었는지
