# 금융 워크벤치를 워크벤치 저장소의 원격 MCP 플러그인으로 옮긴다

구현을 마친 변경의 기록이며 지금 동작은 [기능 파일](../financial-workbench.md)이 갖는다.

## 목표

이 저장소의 로컬 금융 워크벤치 커넥터(`hermes/connectors/financial-workbench/`)를 걷어 내고, 워크벤치 저장소(`jon890/fos-graphrag`)의 플러그인 `valuation-ontology` 를 원격 MCP 플러그인으로 받는다.
[ADR-20261026 / remote-mcp-plugin](../../../hermes/docs/adr/ADR-20261026-remote-mcp-plugin.md) 「옮기는 순서」 의 2단계다.

## 범위

이번 PR 에 넣는 것:

- 대시보드 plugin 이 원격 도구 오류의 최상위 `{code, message, details}` 모양에서 선언한 코드와 세부를 읽는다
- 워크벤치 플러그인 모양(Claude Code·Codex manifest, 헤더 없는 `.mcp.json`, 헤더를 선언한 `assistant.json`)을 받는지 지키는 검사
- 옛 로컬 커넥터 디렉터리와 그 빌드·소유·무시 설정, 운영자 기준 주소 예외(ADR-20261025)를 걷어 낸다
- 기능 파일과 ADR, 플러그인 README 를 원격 플러그인 기준으로 고친다

이번 PR 에 넣지 않는 것:

- 워크벤치 저장소의 `plugin/assistant.json`. 그 저장소 #27(main `23cc57b`)로 먼저 머지됐다(사용자 결정 2026-11-02)
- 운영 목록 변경과 홈서버의 워크벤치 checkout. `fos-home-infra` 가 갖는다
- Control Plane 과 화면. 원격 플러그인은 1단계와 4단계에서 이미 받는다

## 결정

| 결정 | 정한 것 | 근거 |
| --- | --- | --- |
| 도구 정책을 정하는 곳 | 워크벤치 저장소의 `plugin/assistant.json`(`schema: 2`). 4단계 규칙(선언 없음, 모두 묻기)으로 받지 않는다 | 사용자 결정 2026-11-02. 워크벤치 `.mcp.json` 에는 헤더가 없어 4단계 규칙으로는 토큰 칸이 생기지 않는다. 헤더를 `.mcp.json` 에 넣으면 Claude Code·Codex 의 OAuth 사용자가 깨진다. 금융 쓰기 도구의 승인 방식을 지금과 같게 지키는 길이다 |
| 전환 방식 | 한 번에 한다. 이 PR 이 옛 커넥터까지 걷어 내고, 배포와 함께 운영 목록을 새 플러그인으로 바꾼다 | 사용자 결정 2026-11-02(권장안은 둘을 겹쳐 둔 뒤 다음 PR 에서 걷어 내기였다) |
| 기능 파일 이름 | `docs/features/financial-workbench-connector.md` 를 `docs/features/financial-workbench.md` 로, 스펙을 `docs/features/financial-workbench/spec.md` 로 옮긴다 | `<id>-connector.md` 는 이 저장소 `hermes/connectors/<id>/` 의 플러그인 문서 자리이고 `doc-files.test.ts` 가 그 디렉터리를 요구한다. 다른 저장소의 플러그인은 일반 기능 파일로 둔다. 기술 판단 |
| 원격 도구 오류 모양 | `error` 객체가 없고 최상위 `code` 가 글이면 최상위를 오류로 읽고, 세부는 최상위 `details` 객체에서 꺼낸다 | 워크벤치 `/mcp` 는 `{code, message, details}` 를 돌려준다. 남의 플러그인을 고치지 않고 받는다는 1단계의 방향이다. 기술 판단 |

## 요구사항과 인수 조건

| ID | 요구사항 | 인수 조건 |
| --- | --- | --- |
| R1 | 대시보드 plugin 이 도구 오류의 최상위 `{code, details}` 모양을 읽는다 | 검사용 선언 기준으로, 승인 실행이 `isError` 결과 `{"code": "PROPOSAL_LIMIT", "message": "...", "details": {"max": 200, "pending": 200}}` 를 받으면, `errors` 에 선언한 코드와 복구 어휘, 선언한 정수 세부(`max`, `pending`)만 응답에 싣고 `message` 는 싣지 않는다. `error` 객체가 있으면 지금처럼 그 객체만 본다. 선언하지 않은 코드는 지금처럼 `unavailable` 이다. `outcome_unknown` 코드의 `retry_key` 도 같은 모양에서 읽는다 |
| R2 | 워크벤치 플러그인 모양이 카탈로그에 오르고 원격으로 설치된다 | 검사용 사본(`hermes/tests/fixtures/valuation-ontology/`)을 운영 목록 `{root, url}` 로 올리면 카탈로그 항목이 `id` `valuation-ontology`, `schema: 2`, 제목 「금융 워크벤치」, 가리는 필수 칸 하나(`FINANCIAL_WORKBENCH_TOKEN`), `verify.tool` `get_connection` 이다. 설치한 서버 정의의 `url` 은 운영 목록 값이고 `.mcp.json` 의 주소가 아니며, 헤더는 `Authorization: Bearer ${칸 env}` 하나다. 조회 도구는 승인이 없고 `record_trades`, `submit_proposals`, `propose_ontology_change` 는 `WRITE`, `required`, 대화 허락이다. 스킬 네 개가 읽힌다 |
| R3 | 옛 로컬 커넥터와 운영자 기준 주소 예외를 걷어 낸다 | `hermes/connectors/financial-workbench/` 가 없고, 커넥터 이미지 빌드와 `.gitignore`, `.dockerignore`, `CODEOWNERS` 에 그 경로가 없다. `hermes/tests/test_connectors_contract.py` 는 이 저장소의 어느 플러그인에도 `operator_env` 를 허락하지 않는다. 커넥터 공통 검사와 묶음 검사가 통과한다 |
| R4 | 문서가 원격 플러그인을 설명한다 | ADR-20261025 는 `superseded` 로 `hermes/docs/adr/archive/` 에 있고 목록의 「보관」 표에 있다. ADR-20261026 「옮기는 순서」 의 2단계가 끝난 것으로 적혀 있다. 기능 파일 `docs/features/financial-workbench.md` 는 등록 칸, 도구와 정책, 오류, 보안, 실제 계정 확인을 원격 플러그인 기준으로 갖고 `covers:` 가 R2 의 검사를 가리킨다. 문서 검사(`test/unit/`)가 통과한다 |

## 설계

### R1 오류 모양

`connector_mcp.py` 의 `_tool_error(payload)` 만 바꾼다.

- `payload` 에 `error` 키가 있으면 지금 규칙 그대로다. 객체가 아니면 코드가 없다
- `error` 키가 없고 `payload["code"]` 가 글이면 `{**details, "code": code}` 를 오류 객체로 쓴다. `details` 는 `payload["details"]` 가 객체일 때만 쓴다. `message` 와 그 밖의 최상위 칸은 오류 객체에 들어가지 않는다
- 세부를 옮기는 규칙(선언한 이름, 정수와 boolean, 상한)은 바꾸지 않는다
- `retry_key` 는 이 모양에서 `details` 로 만든 오류 객체에서만 찾는다. 맨 위에는 원문 `message` 가 있어 지금처럼 맨 위까지 보면 원문이 재시도 식별자로 나갈 수 있다
- 같은 `_tool_error` 를 쓰는 `call` 경로도 같은 모양에서 선언한 공통 어휘를 읽는다

세부 이름은 `^[a-z][a-z0-9_]{0,31}$` 여야 선언할 수 있다. 워크벤치의 선언은 세부를 넘기지 않는다. 세부는 대화 허락과 상시 허락으로 바로 실행한 호출의 결과 글에는 남는다.

테스트는 `hermes/tests/test_connector_execute.py` 에 최상위 모양의 코드, 세부, 메시지 제외, `error` 객체 우선, 선언 없는 코드를 더한다.

### R2 워크벤치 플러그인 사본

`hermes/tests/fixtures/valuation-ontology/` 에 워크벤치 저장소 `plugin/` 의 모양을 둔다.

| 파일 | 내용 |
| --- | --- |
| `.claude-plugin/plugin.json` | 워크벤치 main `23cc57b` 의 파일 그대로. `skills` 칸이 없다 |
| `.codex-plugin/plugin.json` | 같은 `name` 과 스킬 네 개의 경로 목록 |
| `.mcp.json` | 서버 `valuation-ontology` 의 `type: http` 와 `url`. 헤더가 없다 |
| `assistant.json` | 워크벤치 main `23cc57b` 의 파일 그대로 |
| `skills/<이름>/SKILL.md` | 네 스킬의 머리말만 |

`.mcp.json` 의 주소는 자리표시로 바꿨다. 대시보드가 그 값을 무시하고 운영 목록의 `url` 만 쓰기 때문이다.

테스트는 `hermes/tests/test_connector_workbench_plugin.py` 가 카탈로그 항목, 설치한 서버 정의, 도구 정책, 스킬을 본다.

### R3 걷어 내기

- `git rm -r hermes/connectors/financial-workbench`
- `hermes/connectors/Dockerfile` 의 `COPY` 줄, `hermes/connectors/.dockerignore`, 루트 `.gitignore`, `.github/CODEOWNERS` 의 해당 줄을 지운다
- `test_connectors_contract.py` 의 `OPERATOR_BASE_URL_ENV` 와 그 허용 경로를 지우고, `operator_env` 가 있으면 늘 실패하게 한다
- `test/unit/connector-neutral.test.ts` 의 금지어는 그대로 둔다. 범용 코드에 서비스 이름이 들어오지 않게 막는 목록이다

### R4 문서

- ADR-20261025 는 `superseded` 로 바꾸고 `archive/` 로 옮긴다. 이 ADR 을 가리키던 링크는 ADR-20261026 으로 바꾼다
- ADR-20261026 의 결정 표에 「도구 오류 모양」 줄을 더하고, 「옮기는 순서」 2단계를 「머지」 로 바꾼다
- 기능 파일은 이름을 `financial-workbench.md` 로 바꾸고, 로컬 서버의 인자 검사와 HTTP 대응을 지운 뒤 워크벤치 저장소의 계약을 가리킨다
- `hermes/connectors/README.md` 와 루트 README 두 개에서 운영자 기준 주소 예외와 로컬 커넥터 설명을 고친다

## 위험

- **배포부터 사용자가 다시 연결할 때까지 금융 워크벤치를 쓸 수 없다.** 옛 플러그인이 카탈로그에서 빠지면 그 연결과 바인딩은 쓸 수 없고, 사용자가 「외부 서비스 연결」 에서 새 플러그인으로 연결하고 에이전트에 다시 붙여야 한다. 이름이 달라 재연결은 어차피 한 번 한다(사용자 결정 2026-10-26)
- **되돌리기**: 직전 커넥터 이미지와 옛 운영 목록 항목으로 돌아간다. 옛 연결의 보관 파일은 지우지 않으므로 다시 쓸 수 있다
- 원격 서버가 도구를 바꾸면 이 저장소의 검사는 모른다. 선언에 없는 도구는 지금처럼 거절한다

## PR 뒤 원격 검증

코디네이터가 배포 뒤 확인한다. 실행 방법과 운영 값은 운영 저장소가 갖는다.

| 확인 | 절차 |
| --- | --- |
| 운영 목록이 새 플러그인을 가리키고 옛 항목이 없다 | 운영 목록 변경 |
| 카탈로그에 「금융 워크벤치」 가 보이고 연동 토큰으로 연결된다 | 플러그인 연결 확인 |
| 토스증권을 붙인 에이전트에 함께 붙고, 보유 종목 하나에 결정과 시나리오가 나온다 | Hermes 왕복 확인 |
| 체결 기록이 승인 카드를 거쳐 저장되고 다시 보내면 `existing` 이다 | Hermes 왕복 확인 |
| 「조회만」 토큰의 기록이 범위 부족으로 끝난다 | Hermes 왕복 확인 |

## 미결정

없음
