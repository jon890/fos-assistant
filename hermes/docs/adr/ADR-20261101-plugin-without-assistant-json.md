## ADR-20261101 / plugin-without-assistant-json: `assistant.json` 이 없는 표준 플러그인도 받고, 그 도구는 모두 호출마다 사람이 승인한다

- **status**: `accepted`
- Date: 2026-11-01
- [ADR-20261026 / plugin-standard-format](ADR-20261026-plugin-standard-format.md) 의 「`assistant.json` 이 없을 때의 기본값」 을 정하고 구현을 시작한다. 그 ADR 의 나머지 읽기 규칙은 그대로다.
- [ADR-20261026 / remote-mcp-plugin](ADR-20261026-remote-mcp-plugin.md) 「옮기는 순서」 의 4단계다. 원격 서버의 주소와 헤더 규칙은 그 ADR 이 갖는다.
- 도구 판정의 순서는 [ADR-049](../../../backend/docs/adr/ADR-049-커넥터-도구-호출은-profile-plugin-의-hook-이-control-plane-에-물어-판정한다.md) 와 `ToolPolicyDecision.decide` 가 갖는다.

### 결정

**Claude Code 나 Codex 형식의 플러그인은 `assistant.json` 없이도 카탈로그에 오른다. 그 MCP 서버의 도구는 하나도 선언되지 않은 것으로 보고, 모두 호출마다 승인을 받는다. `assistant.json` 은 있으면 이 기본값을 통째로 덮어쓰는 선택 파일이다.**

| 항목 | 정한 것 |
| --- | --- |
| schema | 카탈로그의 `schema: 3`. `assistant.json` 이 이 값을 선언하면 거절한다. `schema: 3` 은 「운영자가 읽어 본 선언이 없다」 는 뜻이다 |
| 제목과 설명 | Claude Code manifest 의 `displayName`(없으면 `name`)과 `description`. Codex 의 `interface` 는 읽지 않는다 |
| 입력 칸 | 로컬 서버는 `env` 의 `${이름}` 마다 필수 칸, `${이름:-}` 마다 선택 칸이다. 원격 서버는 헤더 값의 `${이름}` 마다 필수 칸이다. 칸은 모두 가리는 값(`secret: true`)이고 표시 이름은 env 이름이다 |
| 받지 않는 칸 값 | 리터럴, 다른 이름의 참조, 빈 값이 아닌 기본값. OS·Python·Hermes 전역 이름(원격 MCP ADR 의 목록)은 로컬 서버에서도 칸으로 받지 않는다 |
| 운영자 값 | 운영 목록 항목에 `env` 가 있으면 뺀다. 운영자 env 는 `assistant.json` 의 `operator_env` 로만 선언한다 |
| 확인 도구 | 없다(`verify: null`). 선택지 도구도 없다. 대시보드 `call` 이 부를 도구가 없다 |
| 도구 정책 | 카탈로그의 `tools` 는 빈 객체다. 설치에서 모델에게 빼는 도구(`tools.exclude`)도 없다 |
| 판정 | Control Plane 은 그 서버의 등록 이름을 가진 호출을 모두 `WRITE`, `required` 로 읽고 상시 허락과 대화 허락을 닫는다. 결과는 언제나 승인 필요다. 다른 서버의 등록 이름은 지금처럼 `UNDECLARED` 로 거절한다 |
| 살펴보기 | 읽기 경계의 살펴보기는 승인 줄 없이 거절하고, 쓰기를 허용한 살펴보기는 승인 줄로 보낸다. `schema: 1` 의 선언 없는 도구와 같다 |
| 승인한 실행 | 대시보드의 `execute` 는 `schema: 2` 가 아니면 선언을 보지 않고 등록 이름이 맞는 도구 하나를 부른다. `schema: 3` 도 같다 |
| 옛 Control Plane | `schema: 3` 항목은 `GET /api/connectors/catalog?include=standard` 로 청한 쪽에만 싣는다. 옛 Control Plane 은 `verify.tool` 이 없는 항목 하나로 카탈로그 전체를 읽지 못한다([rule-66](../../../docs/code-rules/rule-66.md)) |
| 덮어쓰기 | `assistant.json` 이 있으면 지금처럼 그 선언만 읽는다. `schema: 3` 의 추론과 섞지 않는다. 일부 도구만 선언하고 나머지를 「묻는다」 로 두는 일은 아래 「남은 단계」 가 정한다 |

**MCP tool annotations 는 판정을 느슨하게 하지 않는다. `readOnlyHint: true` 인 도구도 첫 호출은 사람이 승인하고, 그 뒤 사용자가 그 도구에 상시 허락을 줄 수 있다(사용자 결정 2026-11-01).**

- `readOnlyHint` 가 없거나 거짓인 도구는 상시 허락도 닫힌 채 호출마다 승인이다
- 서버가 표시를 거짓으로 바꾸거나 도구를 목록에서 빼면 그 도구의 상시 허락을 거둔다. 표시는 연결 확인과 승인 실행 때 대시보드가 받은 `tools/list` 로 다시 본다. 그 사이에 바뀐 표시는 다음 확인까지 모른다. 이 틈은 「감당할 것」 에 있다
- `destructiveHint`, `idempotentHint`, `openWorldHint` 는 판정에 쓰지 않는다. 승인 카드의 안내에만 쓸 수 있다
- 이 규칙은 아래 「남은 단계」 의 2단계(#627)가 구현했다. 표시는 `connector_connection.listed_tools` 에 두고, 판정과 승인 카드와 허락을 만드는 승인이 같은 값을 본다

**ChatGPT 형식에서는 MCP 서버 연결과 스킬만 받는다.**

| ChatGPT 형식의 것 | 처리 |
| --- | --- |
| root 의 `plugin.json` 과 `mcp.json`(이식형 묶음) | 받는다. 남은 단계 3에서 Claude Code·Codex manifest 와 같은 자리로 읽는다 |
| `mcp.json` 서버의 `type: streamable-http` 와 `url` | 받는다. 우리의 `type: http` 와 같게 읽고, 주소는 지금처럼 운영 목록의 `url` 만 쓴다 |
| `extensions["com.openai"].auth` 의 `api_key`(`headerScheme: bearer`, `custom_header`) | 받는다. 그 헤더 하나를 칸 하나로 바꾼다. `basic` 은 값 둘을 이어 붙여야 해 받지 않는다 |
| `auth` 의 `oauth`, `mixed`(DCR, CIMD, 등록한 client) | 받지 않는다. 원격 MCP ADR 의 「인증」 이 OAuth 를 미뤘다. 그 서버가 개인 토큰도 받으면 `assistant.json` 으로 헤더를 선언한다 |
| `_meta.ui.resourceUri`, `openai/outputTemplate` 의 위젯 UI 와 `text/html;profile=mcp-app` 자원 | 받지 않는다. 우리 대화 화면에 남의 HTML 을 그릴 자리가 없고, 그 자원은 실행 공간 밖의 스크립트다. 도구 결과의 글만 쓴다 |
| `.app.json` 의 등록 앱 대응, `interface` 의 표시 칸, `onboardingSkill`, `hooks` | 받지 않는다. ChatGPT 의 앱 등록과 화면에 묶인 값이고 Hermes 에 옮길 자리가 없다 |

### 맥락

사용자는 ChatGPT, Claude 의 플러그인처럼 MCP 와 스킬을 묶은 플러그인을 그대로 받기를 원했다(2026-11-01).
1단계(#607)로 Claude Code·Codex manifest 와 원격 MCP 는 읽게 됐지만, `assistant.json` 이 없으면 카탈로그에서 뺐다. 남이 만든 플러그인은 파일을 하나 더해야 붙었다.
선언하지 않은 도구를 거절하는 지금의 판정(`default_tool_policy: deny`) 때문에, 받기만 하고 거절하면 쓸 수 있는 도구가 없다.
Control Plane 은 이미 `schema: 1` 의 선언 없는 도구를 승인 필요로 판정한다. 같은 길에 상시 허락만 닫으면 새 테이블이나 칸 없이 판정이 된다.

#### 형식 비교(2026-11-01 공식 문서 기준)

| 항목 | Claude Code 플러그인 | Codex·ChatGPT 플러그인 | MCP 명세 2025-06-18 |
| --- | --- | --- | --- |
| manifest | `.claude-plugin/plugin.json`. `name` 만 필수. manifest 가 없어도 기본 배치로 읽는다 | 이식형은 root `plugin.json`, Codex 호환은 `.codex-plugin/plugin.json`. OpenAI 전용 칸은 `extensions.com.openai` 아래 | 해당 없음 |
| 스킬 | `skills/<이름>/SKILL.md`. `skills` 칸은 기본 `skills/` 에 더한다 | root `skills/` 를 찾는다. 스캐폴드는 `skills: "./skills/"` 를 적는다 | 해당 없음 |
| MCP 정의 | root `.mcp.json` 과 `mcpServers`(경로, 묶음, 인라인 객체, 배열). 같은 이름은 뒤의 것이 이긴다 | 이식형은 root `mcp.json`, 서버마다 `type` 필수(예: `streamable-http`). Codex 스캐폴드는 `.mcp.json` | 해당 없음 |
| 전송 | `stdio`(`command`, `args`, `env`), `http`, `sse`, `ws`(`url`, `headers`, `headersHelper`) | 원격 HTTP 를 문서화한다. 로컬 stdio 는 데스크톱 전용 안내로 넘긴다 | stdio, Streamable HTTP |
| 치환 | `${CLAUDE_PLUGIN_ROOT}`, `${CLAUDE_PLUGIN_DATA}`, `${user_config.KEY}`, 환경 변수 `${VAR}` | hook 의 `${PLUGIN_ROOT}` | 해당 없음 |
| 사용자 입력 | `userConfig`(`type`, `title`, `description`, `required`, `sensitive` 등). 민감한 값은 OS 자격 증명 저장소 | 연결 설정에서 받는다. manifest 에 비밀값과 `clientSecret` 을 두지 않는다 | 해당 없음 |
| 인증 | 원격 서버의 OAuth 는 Claude Code 가 맡고, 정적 헤더와 `headersHelper` 도 받는다 | 서버마다 `extensions["com.openai"].auth.type`: `none`, `oauth`(`provided`, `dcr`, `cimd`), `mixed`, `api_key`(`headerScheme`: `bearer`, `basic`, `custom_header`) | 원격 전송의 인가는 OAuth 2.1 |
| 도구 위험 표시 | manifest 에 없다. 승인은 Claude Code 권한 설정이 정한다 | manifest 에 없다. 서버의 annotations 를 심사에서 본다(`readOnlyHint`, `destructiveHint`, `openWorldHint`). 승인은 Codex host 의 정책 | `annotations.readOnlyHint`(기본 false), `destructiveHint`(기본 true), `idempotentHint`(기본 false), `openWorldHint`(기본 true). 모두 hint 이고, 믿지 않는 서버의 값으로 도구 사용을 정하지 말라고 적는다 |
| 화면 | 없음 | 도구의 `_meta.ui.resourceUri`(별칭 `openai/outputTemplate`)가 `text/html;profile=mcp-app` 자원을 가리킨다 | 해당 없음 |
| 그 밖 | `commands`, `agents`, `hooks`, `lspServers`, `outputStyles`, `bin/` 등 | `.app.json`, `interface`, `onboardingSkill`, `hooks` | 해당 없음 |

출처(모두 2026-11-01 에 읽었다):

- [Claude Code Plugin manifest reference](https://code.claude.com/docs/en/plugins-reference)
- [OpenAI Plugins: Package your plugin](https://developers.openai.com/plugins/build/plugins), [Plugins 개요](https://learn.chatgpt.com/docs/plugins), [ChatGPT UI](https://developers.openai.com/plugins/build/chatgpt-ui), [MCP server review](https://developers.openai.com/plugins/deploy/app-review)
- [MCP 명세 2025-06-18 Tools](https://modelcontextprotocol.io/specification/2025-06-18/server/tools), [Schema 의 ToolAnnotations](https://modelcontextprotocol.io/specification/2025-06-18/schema)

### 대안 기각

- **`readOnlyHint: true` 인 도구를 승인 없이 연다**: 남의 서버가 낸 표시 하나로 사람의 확인이 빠진다. 운영 목록의 원격 서버는 우리 검사 밖에서 도구 목록을 바꾼다. MCP 명세도 믿지 않는 서버의 annotations 로 정하지 말라고 한다.
- **선언하지 않은 도구를 지금처럼 거절한다**: 받은 플러그인의 도구를 하나도 쓰지 못한다. 쓰려면 남의 저장소에 `assistant.json` 을 더해야 해, 1단계가 없앤 손질이 그대로 남는다.
- **`approval: always` 로 읽는다**: `always` 도구는 설치가 모델에게서 뺀다(`tools.exclude`). 도구 이름을 미리 모르는 `schema: 3` 에서는 뺄 목록을 만들 수 없고, 빼면 부를 수 있는 도구가 없다.
- **`schema: 2` 에 `default_tool_policy: ask` 를 더해 표현한다**: `schema: 2` 는 확인 도구와 비지 않은 `tools` 를 요구한다. 옛 Control Plane 은 새 칸을 모른 채 `schema: 2` 로 읽어, 그 커넥터를 거절해야 할 자리에서 도구를 선언 없는 거절로 다룬다. schema 값을 새로 두면 옛 쪽이 모르는 값으로 보고 버린다.
- **카탈로그에 `schema: 3` 을 그대로 싣는다**: 옛 Control Plane 이 `verify.tool` 이 없는 항목에서 카탈로그 읽기 전체를 실패로 다뤄, 대시보드 plugin 을 먼저 올리는 동안 모든 커넥터가 끊긴다.

### 결과

- 얻는 것: 남이 만든 Claude Code·Codex 플러그인을 고치지 않고 운영 목록에 올린다. 모든 호출을 사람이 보므로 그 플러그인의 위험도 판단을 운영자가 미리 하지 않아도 된다.
- 감당할 것:
  - 모든 호출에 승인 카드가 뜬다. 읽기 도구가 많은 플러그인은 번거롭다. 편하게 쓰려면 플러그인 작성자나 운영자가 `assistant.json` 을 둔다.
  - 승인 카드에는 선언한 제목이 없어 원래 도구 이름과 인자만 보인다(2단계). 서버가 낸 도구 설명은 아직 카드에 보이지 않고, 대화의 알림 줄은 「이름 없는 동작」 이다. 설명 표시는 아직 계획이 없는 남은 일이다.
  - 칸의 표시 이름이 env 이름이다. 사람 말 이름이 필요하면 `assistant.json` 을 둔다.
  - 확인 도구가 없어 1단계의 Control Plane 은 `schema: 3` 연결의 등록을 `CONNECTOR_UNAVAILABLE` 로 거절했다. 2단계부터는 대시보드의 `tools/list` 가 성공하는지로 확인한다. 운영 목록에 올리는 일은 운영 저장소가 정한다.
  - `readOnlyHint` 로 연 상시 허락은 서버가 표시를 바꾼 뒤 다음 확인까지 남는다. 확인은 연결 확인과 승인 실행 때뿐이라, 그 사이 표시만 바꾼 쓰기 도구가 허락으로 나갈 수 있다. 원격 서버의 소유자를 믿는 운영 목록 등록이 이 틈을 막는다.
  - 셸, 웹, 브라우저와의 반출 정책(#375)은 schema 와 상관없이 그대로다. `schema: 3` 도구의 결과도 다른 커넥터 결과와 같은 신뢰 경계에 놓인다.

### 남은 단계

PR 하나가 한 단계다. 1단계가 이 ADR 과 함께 머지된 뒤 다음 단계를 계획한다. 2단계부터의 계획서는 `tasks/` 에 둔다.

| 순서 | 단계 | 담는 것 |
| --- | --- | --- |
| 1 | 카탈로그와 판정 | `schema: 3` 의 카탈로그 항목, 칸 추론, `include=standard`, Control Plane 의 판정(상시 허락 닫힘), `schema: 3` 연결 등록 거절. 이 ADR 과 형식 비교 |
| 2 | 연결과 읽기 표시 | 확인 도구 대신 MCP 도구 목록으로 연결을 확인한다. `readOnlyHint` 로 상시 허락을 열고 표시가 바뀌면 거둔다. 승인 카드에 도구 이름을 보인다. 화면이 `schema: 3` 연결을 보인다. #627 로 끝났다. 서버 설명 표시는 하지 않았고 남은 일이다 |
| 3 | ChatGPT 이식형 묶음 | root `plugin.json` 과 `mcp.json`, `streamable-http`, `auth.api_key` 를 위 표대로 읽는다 |
| 4 | 부분 선언 | `assistant.json` 이 일부 도구만 선언하고 나머지를 `schema: 3` 처럼 묻게 하는 선언(`default_tool_policy: ask`) |

화면과 문서의 「커넥터」 를 「플러그인」 으로 바꾸는 일은 원격 MCP ADR 「옮기는 순서」 의 3단계가 갖는다.
