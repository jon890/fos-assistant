## ADR-20261026 / remote-mcp-plugin: 원격 MCP 플러그인은 운영자가 믿은 주소에만 붙고, 사용자의 토큰은 헤더로만 간다

- **status**: `accepted`
- Date: 2026-10-26
- 플러그인을 읽는 규칙은 [ADR-20261026 / plugin-standard-format](ADR-20261026-plugin-standard-format.md) 이 갖는다. 이 ADR 은 그 가운데 `type: http` 서버를 받는 규칙과 옮기는 순서를 정한다.
- 금융 워크벤치를 이 방식으로 옮긴 뒤 [ADR-20261025 / connector-operator-base-url](ADR-20261025-connector-operator-base-url.md) 을 `superseded` 로 바꾼다. 그 전까지는 둘 다 유효하다.

### 결정

**`.mcp.json` 의 서버가 `type: http` 이면 대시보드 plugin 은 자식 프로세스 대신 Streamable HTTP 로 붙는다. 주소는 운영 목록에서만 오고, 사용자의 입력 칸 값은 `assistant.json` 이 선언한 헤더에만 들어간다.**

| 항목 | 정한 것 |
| --- | --- |
| 전송 | MCP Streamable HTTP. `sse` 는 받지 않는다 |
| 주소의 출처 | 운영 목록 항목의 `url`(`http://` 나 `https://`)이 필수다. `.mcp.json` 의 `url` 은 무시한다. 플러그인 갱신만으로 토큰의 목적지가 바뀌지 않는다. 운영 목록 주소에는 계정 정보, 쿼리, fragment, `$` 치환이 없다 |
| 사용자 입력 | `fields` 의 값은 헤더 값의 `${이름}` 자리에만 들어간다. 주소, 경로, 헤더 이름에는 들어가지 않는다. env 이름은 `^[A-Z][A-Z0-9_]{0,127}$` 이고 Hermes 전역 이름은 받지 않는다 |
| 헤더 선언 | `.mcp.json` 서버의 `headers` 와 `assistant.json` 의 `headers` 를 합친다. 같은 이름이 둘에 있으면 카탈로그에서 뺀다. 값은 `${이름}` 이나 `Bearer ${이름}` 이고 `${이름}` 은 필수 칸 하나다. 리터럴 값과 선택 칸(`${이름:-}`)은 받지 않는다 |
| 인증 | 이번에는 사용자가 넣는 토큰만 받는다. 원격 MCP 의 OAuth 로그인은 Hermes 와 대시보드 plugin 에 넣지 않는다 |
| 원격에서 받지 않는 선언 | `operator_env`, `owner_attachments_env`, `owner_output_env`, `owner_browser_env`, `snapshot`, `execution_guard`. 모두 자식 프로세스의 env 로 값을 건네는 선언이다 |
| 운영 목록 항목 | `{"root": "<플러그인 디렉터리>", "url": "<필수 원격 MCP 주소>"}`. `command` 와 `env` 가 있으면 그 항목을 버린다 |
| 설치하는 서버 정의 | `{"url": ..., "headers": {...}, "strict_redirect_headers": true, "enabled": true}` 와 지금처럼 `tools.exclude`. 헤더 값의 `${이름}` 은 profile `.env` 가 채운다. `strict_redirect_headers` 는 Hermes 가 다른 origin 으로 가는 redirect 에서 설정 헤더를 모두 지우게 한다 |
| 대시보드의 호출 | 확인 도구, 선택지, 승인 실행은 대시보드 plugin 이 직접 같은 원격 서버에 붙고 redirect 를 따라가지 않는다. MCP probe 는 Hermes 가 붙으므로 Hermes 의 redirect 규칙(아래 「감당할 것」)을 따른다 |
| 도구 이름과 승인 | Hermes 등록 이름은 `mcp__<서버>__<도구>` 그대로이고 전송과 무관하다. `fos-ctx` 의 판정과 이름 대응 파일은 바뀌지 않는다 |
| 다른 저장소의 플러그인 | 운영자가 홈서버에 받아 둔 그 저장소의 checkout 을 운영 목록의 `root` 로 가리킨다. 실행 중에 marketplace 나 원격 저장소에서 내려받지 않는다. 경로와 갱신 절차는 `fos-home-infra` 가 갖는다 |

원격 칸 env 검사는 [Hermes v0.21.6 `agent/secret_scope.py`](https://github.com/NousResearch/hermes-agent/blob/v0.21.6/agent/secret_scope.py)의 `_GLOBAL_ENV_EXACT`, `_GLOBAL_ENV_PREFIXES`가 근거다. OS·Python 이름은 정확히 일치할 때 거절하고, `HERMES_`, `TERMINAL_`, `GATEWAY_RELAY_`, `API_SERVER_`는 접두 전체를 거절한다. 소문자 context 변수도 이름 형식에서 빠진다.

대시보드는 `Accept-Encoding: identity`를 보내고 `accept-encoding`을 플러그인이 덮어쓰지 못하게 한다. 응답의 `content-encoding`이 없거나 `identity`인 경우만 받고 다른 압축 응답은 읽기 전에 닫는다. 허용한 응답은 SDK가 본문을 모으기 전에 일반 본문과 SSE 이벤트 하나마다 10 MiB로 제한한다. 압축을 풀면서 상한을 우회하는 일을 막는다. Hermes의 [`_make_mcp_body_cap_transport`](https://github.com/NousResearch/hermes-agent/blob/v0.21.6/tools/mcp_tool_errors.py)와 같은 크기 상한이고, 긴 SSE 연결은 누적 크기로 끊지 않는다.

### 옮기는 순서

PR 하나가 한 단계다. 앞 단계가 머지된 뒤 다음 단계를 계획한다.

| 순서 | 단계 | 상태 | 담는 것 |
| --- | --- | --- | --- |
| 1 | 형식 수용과 원격 MCP | 머지 | 대시보드 plugin 의 manifest 읽기, `assistant.json` 과 옛 `connector.json`, `type: http` 서버의 설치와 호출, 문서. 이 저장소의 로컬 커넥터는 그대로 돈다 |
| 2 | 금융 워크벤치 전환 | 대기 | 워크벤치 저장소의 플러그인(`valuation-ontology`)에 `assistant.json` 이 생긴 뒤, 운영 목록을 그 플러그인으로 바꾸고 `hermes/connectors/financial-workbench/` 와 ADR-20261025 를 걷어 낸다. 이름이 달라 사용자는 한 번 다시 연결한다(사용자 결정 2026-10-26) |
| 3 | 이름 변경 | 진행 | 화면과 문서, 용어표의 「커넥터」 를 「플러그인」 으로 바꾸고 이 저장소 커넥터의 `connector.json` 을 `assistant.json` 으로 옮긴다([ADR-20261026 / plugin-naming](../../../docs/adr/ADR-20261026-plugin-naming.md)) |
| 4 | `assistant.json` 없는 플러그인 | 머지 | 선언하지 않은 도구를 「묻는다」 로 판정하고 입력 칸을 MCP 정의에서 추론한다. Control Plane 과 화면이 바뀐다. 결정과 그 안의 단계는 [ADR-20261101 / plugin-without-assistant-json](ADR-20261101-plugin-without-assistant-json.md) 이 갖는다 |

3단계를 2단계보다 먼저 한다(사용자 결정 2026-11-01). 이름 변경은 화면 문구와 문서, 선언 파일 이름만 바꾸고 금융 워크벤치 전환과 파일이 겹치지 않아 기다릴 까닭이 없다.

### 맥락

금융 워크벤치는 비개발자도 Claude Code 와 Codex 에서 쓰게 하려고 서버가 MCP 를 직접 연다(`/mcp`, Streamable HTTP, OAuth 로그인).
그 서버는 OAuth 접근 토큰과 함께 지금의 개인 연동 토큰(`Bearer`)도 받는다.
assistant 가 같은 플러그인을 쓰면 도구 구현이 서버 하나에 모이고, 이 저장소의 로컬 TypeScript 커넥터와 운영자 기준 주소 예외가 필요 없어진다.
Hermes 는 이미 `mcp_servers` 에 `url` 과 `${이름}` 을 참조하는 헤더를 받는다. profile 틀의 Control Plane MCP 등록이 같은 모양이다.

### 대안 기각

- **사용자가 주소를 넣는다**: 대시보드 plugin 과 Hermes 는 홈서버 안에서 돈다. 사용자가 넣은 주소로 내부망을 부르는 SSRF 가 된다. ADR-20261025 가 같은 까닭으로 버렸다.
- **원격 MCP 의 OAuth 로그인을 이번에 넣는다**: 동적 등록, 인가 코드와 PKCE, 갱신 토큰을 저장하고 돌리는 일을 대시보드 plugin 과 Hermes 설정이 함께 맡아야 한다. Hermes core 를 고치지 않는 경계([ADR-001](../../../docs/adr/ADR-001-hermes를-런타임으로-두고-core를-고치지-않는다.md)) 안에서 갱신 토큰을 profile 마다 맞추는 방법도 아직 없다. 첫 대상은 개인 연동 토큰을 받으므로 미룬다.
- **원격 서버를 로컬 실행형 중계(`mcp-remote` 같은 것)로 감싼다**: 지금 검증을 그대로 쓰지만 새 실행 파일 의존이 생기고, 토큰이 자식 프로세스의 인자나 env 로 한 번 더 건너간다.
- **marketplace 에서 플러그인을 내려받는다**: 실행 중에 바깥 저장소의 내용이 바뀌면 운영자가 읽어 보지 않은 스킬과 위험도 선언이 들어온다. 홈서버의 가계부 연결도 운영자가 믿은 checkout 만 읽는다.

### 결과

- 얻는 것: 다른 저장소가 연 원격 MCP 를 그 저장소의 플러그인 그대로 붙인다. 사용자는 지금처럼 토큰만 넣는다. 원격 플러그인은 실행 파일이 없어 운영 목록에 `command` 가 필요 없다.
- 감당할 것:
  - 원격 서버가 바뀌면 이 저장소의 검사는 모른다. 선언하지 않은 도구는 지금처럼 거절하고 연결 상태에 센다. 실제 서버로 붙는지는 그 플러그인의 소유자가 확인한다.
  - 토큰이 HTTP 헤더로 나간다. 운영 목록의 `url` 이 `http://` 이면 그 구간은 운영자가 믿은 내부망이어야 한다. 그 판단은 `fos-home-infra` 가 갖는다.
  - Hermes 는 다른 호스트로 가는 redirect 를 따라간다. 설치하는 서버 정의의 `strict_redirect_headers: true` 로 그때 `Authorization` 과 함께 선언한 헤더를 모두 지우게 한다([Hermes 계약의 「원격 MCP 서버」](../hermes-contract.md#원격-mcp-서버)). 이 설정은 헤더만 지우고 목적지와 POST 본문을 막지 않는다. 이 키가 있으면 Hermes 는 SSE 로 다시 붙어 보지 않는다. 대시보드 plugin 의 호출은 redirect 를 따라가지 않는다.
  - 원격 서버의 redirect 로 Hermes 요청이 내부망에 닿을 수 있다. 운영 목록 `url` 고정과 홈서버 egress 제한으로 막는다. egress 제한은 `fos-home-infra` 후속이다.
  - 원격 서버의 응답 시간은 자식 프로세스보다 흔들린다. 시간 제한과 동시 한도는 지금 값을 그대로 쓴다.
  - Codex 와 Claude Code 처럼 OAuth 로 붙고 싶은 요구가 생기면 이 ADR 의 「인증」 을 다시 판단한다.
