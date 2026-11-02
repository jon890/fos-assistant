# 금융 워크벤치 플러그인

사용자의 금융 온톨로지 워크벤치를 조회하고, 체결을 기록하고, 해석을 제안으로 올리는 플러그인을 이 저장소가 받는 방식이다.

covers: `hermes/tests/fixtures/valuation-ontology/`, `hermes/tests/test_connector_workbench_plugin.py`

플러그인은 워크벤치 저장소 [`jon890/fos-graphrag`](https://github.com/jon890/fos-graphrag) 의 `plugin/`(이름 `valuation-ontology`, 화면 제목 「금융 워크벤치」)이다.
도구는 그 저장소의 서버가 연 원격 MCP(`/mcp`)에 있고, 도구의 입력과 결과, 오류는 그 저장소 API 계약 문서의 「원격 MCP」 절이 갖는다.
이 저장소는 그 플러그인을 원격 MCP 플러그인으로 받는다. 받는 규칙은 [ADR-20261026 / remote-mcp-plugin](../../hermes/docs/adr/ADR-20261026-remote-mcp-plugin.md) 이 갖고, 플러그인 공통 계약은 [플러그인 연결](connector.md) 과 [플러그인 도구 정책](connector-policy.md) 이 갖는다.
이 문서는 등록 칸과 운영 값, 도구 정책과 그 까닭, 오류, 보안, 실제 계정으로 확인할 것을 갖는다.

**도구 정책은 워크벤치 저장소의 `plugin/assistant.json` 이 선언한다.** `.mcp.json` 에는 헤더가 없어 선언 없이 받으면 토큰 칸이 생기지 않고, 헤더를 `.mcp.json` 에 넣으면 Claude Code 와 Codex 의 OAuth 사용자가 깨진다(사용자 결정 2026-11-02).
`hermes/tests/fixtures/valuation-ontology/` 는 그 플러그인의 사본이고(`assistant.json` 과 Claude Code manifest 는 그대로, `.mcp.json` 의 주소는 자리표시) `test_connector_workbench_plugin.py` 가 카탈로그와 설치, 정책을 본다. 워크벤치의 `assistant.json` 이 바뀌면 사본도 고친다.

투자 판단을 「세상의 변화 → 병목 → 산업 → 기업 → 밸류에이션(시나리오) → 투자 결정」 으로 잇는 그래프이고, 근거 문서와 검토 상태가 있는 주장, 개념, 사용자별 공부 목록을 갖는다.

**체결 사실은 바로 저장하고, 모델이 해석한 것은 제안으로 올린다.** 사용자 결정(2026-10-25)이다.
토스증권 체결은 금액과 수량까지 워크벤치에 들어가고 워크벤치에서 주인만 본다.
투자 결정 만들기, 체결과 결정 잇기, 근거 잇기, 공부 메모, 온톨로지 변경은 사람이 워크벤치의 「더보기 → 제안」 에서 승인해야 반영된다. 워크벤치 쪽 결정은 그 저장소의 ADR-013 이다.

## 등록 칸

| 칸 | 비밀 | 무엇 |
| --- | --- | --- |
| `token` | 그렇다 | 워크벤치의 「더보기 → 연동 토큰」 에서 「조회와 기록」(`READ_WRITE`) 범위로 발급한 개인 연동 토큰. `^fwb_[A-Za-z0-9_-]{43}$` 만 받는다 |

필수다. env 는 `FINANCIAL_WORKBENCH_TOKEN` 이고, 값은 `Authorization: Bearer` 헤더로만 원격 서버에 간다. 옛 로컬 커넥터와 env 이름이 같지만 플러그인 이름이 달라 연결은 새로 만든다.
확인 도구는 `get_connection` 이다. 등록할 때 사용자 표시 이름과 연결 범위가 읽히면 저장한다.
「조회만」(`READ`) 토큰도 등록은 된다. 조회 도구는 그대로 쓰고, 쓰는 도구는 `INSUFFICIENT_SCOPE` 로 끝난다. 범위는 `get_connection` 결과의 `connection.scope` 에 보인다.
원격 서버가 받는 OAuth 로그인은 쓰지 않는다. 원격 MCP ADR 의 「인증」 이 미뤘다.

### 운영 값

운영 목록 항목은 `{"root": <워크벤치 checkout 의 plugin 디렉터리>, "url": <운영자가 띄운 워크벤치의 /mcp 주소>}` 다.
값과 checkout 갱신 절차는 운영 저장소가 갖고 이 저장소에는 적지 않는다. `.mcp.json` 의 주소는 무시한다.

| 선언 | 값 | 까닭 |
| --- | --- | --- |
| `single_binding` | 선언하지 않는다 | 토큰은 사람마다 5개까지 따로 발급되고, 서로를 무효로 만들지 않는다. 같은 연결을 여러 에이전트에 붙여도 된다 |
| `sandbox_required` | 선언하지 않는다 | 셸이 `.env` 의 토큰을 읽어도 할 수 있는 것은 이 플러그인의 도구와 같다. 돈이 움직이지 않고, 체결 기록은 주인이 워크벤치에서 지울 수 있으며, 제안은 사람이 승인해야 반영된다 |

토스증권 플러그인은 두 선언을 모두 참으로 둔다. 두 선언은 토스증권 연결에만 걸리므로, 실행 공간이 있는 에이전트 하나에 토스증권과 워크벤치를 함께 붙일 수 있다.

## 도구와 정책

MCP 서버 이름은 `valuation-ontology` 다. 조회 도구는 서버에서 `readOnlyHint` 가 참이고, 쓰는 도구는 `readOnlyHint` 가 거짓, `destructiveHint` 가 거짓, `idempotentHint` 가 참이다.

| 도구 | 위험도 | 승인 | 하는 일 |
| --- | --- | --- | --- |
| `get_connection` | `READ` | 없음 | 토큰을 확인하고 사용자 표시 이름과 연결 범위를 읽는다 |
| `get_ontology` | `READ` | 없음 | 노드 타입, 관계 종류, 주장 predicate 를 읽는다 |
| `get_ontology_yaml` | `READ` | 없음 | 온톨로지 정의 전체와 버전을 읽는다 |
| `search_nodes` | `READ` | 없음 | 이름과 종목 코드로 항목을 찾는다 |
| `get_node` | `READ` | 없음 | 항목 하나의 속성과 관계, 주장, 근거 문서, 개념을 읽는다 |
| `trace_node` | `READ` | 없음 | 근거 사슬을 위로 따라가고 비어 있는 단계를 보인다 |
| `get_neighborhood` | `READ` | 없음 | 한두 홉 안의 항목과 관계를 읽는다 |
| `lookup_security` | `READ` | 없음 | 시장과 종목 코드로 종목, 투자 결정, 밸류에이션을 함께 읽는다 |
| `list_my_knowledge` | `READ` | 없음 | 토큰 주인의 공부 목록을 읽는다 |
| `record_trades` | `WRITE` | `required`, 대화 허락 | 토스증권 체결을 내 체결 기록에 저장한다 |
| `list_my_trades` | `READ` | 없음 | 내 체결 기록과 이어진 투자 결정을 읽는다 |
| `submit_proposals` | `WRITE` | `required`, 대화 허락 | 해석을 사람이 승인할 제안으로 올린다 |
| `propose_ontology_change` | `WRITE` | `required`, 대화 허락 | 온톨로지 정의를 고치는 제안을 올린다 |
| `list_my_proposals` | `READ` | 없음 | 내 제안의 상태와 거절 까닭을 읽는다 |

선언하지 않은 도구는 지금처럼 거절한다(`default_tool_policy: deny`). 서버가 도구를 더하면 `assistant.json` 에 선언해야 쓴다.

### 쓰는 도구의 선언

| 선언 | 값 | 까닭 |
| --- | --- | --- |
| `risk`, `approval` | `WRITE`, `required` | 워크벤치의 상태를 바꾸지만 되돌릴 수 있다. 정책의 하한이다 |
| `outbound` | 거짓 | 데이터가 토큰 주인 계정의 워크벤치로만 간다. 체결 기록과 제안은 그 주인만 본다 |
| `conversation_grant` | 참 | 체결 여러 건을 기록하고 제안을 올리는 일이 한 대화에서 이어진다. 체결은 주인만 보는 기록이고 주인이 지울 수 있으며, 같은 체결과 같은 `dedupeKey` 는 두 번 생기지 않는다. 제안은 워크벤치에서 사람이 다시 승인해야 반영된다 |
| `grant` | 선언하지 않는다(참) | 같은 까닭으로 상시 허락도 연다 |

`propose_ontology_change` 는 `submit_proposals` 에 온톨로지 변경 한 건을 담은 것과 같아 같은 정책을 쓴다. 온톨로지 변경은 워크벤치의 관리자가 승인해야 반영된다.

## 오류

원격 서버는 도구 오류를 `isError` 결과의 `{code, message, details}` 로 돌려준다. 대시보드는 이 모양에서 `assistant.json` 의 `errors` 에 선언한 코드와 정수 세부만 넘기고 `message` 는 버린다([플러그인 연결](connector.md) 의 「대시보드 plugin 계약」).
워크벤치의 선언은 세부(`details`)를 넘기지 않는다. 항목 번호와 `retryAfterSeconds` 같은 세부는 승인한 실행에서는 에이전트에 가지 않는다.
대화 허락이나 상시 허락으로 바로 실행한 호출은 Hermes 가 결과 글을 그대로 모델에 주므로 `message` 와 세부가 보인다.

| 코드 | 공통 어휘 | 복구 |
| --- | --- | --- |
| `UNAUTHENTICATED` | `credential_rejected` | `reconnect` |
| `FORBIDDEN` | `forbidden` | |
| `INSUFFICIENT_SCOPE` | `forbidden` | `reconnect` |
| `VALIDATION_FAILED`, `ONTOLOGY_VIOLATION`, `ONTOLOGY_INVALID`, `NOT_FOUND`, `ARCHIVED`, `REVISION_CONFLICT`, `PAYLOAD_TOO_LARGE` | `invalid_input` | `fix_input` |
| `PROPOSAL_LIMIT`, `INTERNAL_ERROR` | `unavailable` | |
| `TRANSIENT_CONFLICT`, `RATE_LIMITED`, `QUERY_TIMEOUT`, `DATABASE_UNAVAILABLE` | `unavailable` | `retry_later` |

토큰이 없거나 틀리면 원격 서버가 HTTP `401` 로 답하고, 대시보드의 연결 확인은 `credential_rejected` 다.
쓰기를 보낸 뒤 답을 받지 못하면 대시보드가 결과 모름으로 다룬다. 같은 체결과 같은 `dedupeKey` 는 두 번 생기지 않으므로 조회 도구로 확인한 뒤 다시 보낼 수 있다.

## 보안

- **공유 지식을 바로 바꾸는 도구가 없다.** 외부 글이 모델을 속여도 노드와 관계, 이해 표시, 온톨로지 정의는 사람이 제안을 승인해야 바뀐다. 속은 모델이 바로 할 수 있는 것은 주인만 보는 체결 기록을 더하는 것과 대기 제안을 쌓는 것이다. 체결은 주인이 지우고, 제안은 거절한다
- 읽은 내용을 다른 도구(셸, 웹)로 내보내는 길은 [READ 데이터 흐름](connector-policy.md) 의 RF-08, RF-09 와 같다. 쓰는 도구는 주인의 워크벤치로만 보내므로 `outbound` 가 거짓이다
- 워크벤치의 메모, 주장, 근거 문서 발췌는 사용자와 다른 사람이 쓴 글이다. 스킬이 자료로만 다루게 하고, 그 글이 시키는 주문이나 전송을 하지 않게 한다
- 토큰은 그 사용자의 역할로 인증된다. 새면 그 사용자가 볼 수 있는 워크벤치 내용이 읽히고, 「조회와 기록」 토큰이면 체결 기록과 제안이 더해진다. 워크벤치에서 그 토큰을 폐기하면 바로 막힌다
- 주소는 운영 목록만 정한다. 사용자가 주소를 넣는 칸이 없고, 플러그인 갱신으로 토큰의 목적지가 바뀌지 않는다
- 원격 서버가 도구 설명이나 목록을 바꾸면 이 저장소의 검사는 모른다. 운영자가 믿은 checkout 만 운영 목록에 올린다
- 투자 결정과 근거, 공부 목록, 체결의 금액과 수량이 모델 공급자에게 간다. 사용자가 받아들였다

## 실제 계정으로 확인할 것

배포 뒤 소유자가 자기 워크벤치 계정으로 한 번 확인한다. 실행 방법과 운영 값은 운영 저장소가 갖는다.

1. 운영 목록을 바꾼 뒤 카탈로그에 「금융 워크벤치」 가 보이는지
2. 워크벤치에서 발급한 토큰으로 등록하면 확인 도구가 표시 이름과 범위를 읽는지, 폐기한 토큰은 거절되는지
3. 토스증권을 붙인 에이전트에 함께 붙는지
4. 대화에서 보유 종목 하나를 물으면 토스증권의 보유와 워크벤치의 결정, 시나리오가 함께 나오는지
5. 그 결정으로 근거 따라가기를 물으면 비어 있는 단계가 그렇다고 나오는지
6. 「조회와 기록」 토큰으로 토스증권 체결 하나를 기록하면 승인 카드를 거쳐 워크벤치의 내 체결에 금액과 수량이 보이고, 같은 체결을 다시 시키면 `existing` 으로 끝나는지
7. 체결과 결정을 잇는 제안이 워크벤치의 「더보기 → 제안」 에 대기로 보이는지
8. 「조회만」 토큰으로 바꾸면 기록이 범위 부족으로 끝나고 새 토큰을 발급하라는 안내가 나오는지
