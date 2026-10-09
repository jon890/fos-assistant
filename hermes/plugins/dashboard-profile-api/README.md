# dashboard-profile-api plugin

Hermes 대시보드에 설치하는 plugin 이다. Control Plane 이 profile 과 도구, 스킬, 커넥터를 HTTP 로 다루는 경로를 연다.
설치와 운영 값은 [`hermes/README.md`](../../README.md), Hermes 쪽 동작은 [`hermes/docs/hermes-contract.md`](../../docs/hermes-contract.md) 가 갖는다.

## dashboard-profile-api 가 여는 것

인증은 모두 `Authorization: Bearer <대시보드 서비스 토큰>` 이고 없거나 틀리면 401 이다.
Control Plane 이 이 경로들을 부르는 순서와 뜻은 부르는 쪽 문서가 갖는다. 커넥터 경로는 [커넥터 설치](../../../docs/backend/connector-install.md) 의 「대시보드 plugin 계약」 이, 에이전트 만들기는 [에이전트](../../../docs/backend/agent.md) 가 갖는다.

| 요청 | 쓰임 | 본문이나 query | 성공 | 실패 |
| --- | --- | --- | --- | --- |
| `GET /api/profiles` | 이름이 이미 있는지 본다 | 없음 | 200 | |
| `POST /api/profiles` | profile 을 만들고 같은 요청 안에서 설정 틀과 서명 plugin 과 관리 표식을 둔다 | `name`, `no_skills`, `description` 셋을 받고 다른 키는 거절한다. Control Plane 은 `{name, no_skills: true}` 를 보낸다 | 200. 이때 안전한 도구 목록, Control Plane MCP 등록(토큰 값 없음), 서명 plugin 켜짐, 관리 표식이 모두 있다 | 400 이름 규칙, 이미 있는 이름(Hermes 는 409 가 아니라 400), 허용하지 않는 키. 500 틀 적용 실패(만든 것은 지웠다) |
| `DELETE /api/profiles/<이름>` | 관리 표식이 있는 profile 을 지운다 | 없음 | 200 | 401 관리 표식이 없는 profile. 404 없는 profile. 두 번째 호출의 404 는 「이미 지움」 으로 읽는다 |
| `PUT /api/env` | 그 profile 의 `.env` 에 정해 둔 key 한 줄을 쓴다 | `{profile, key, value}`. key 는 `API_SERVER_KEY`, `API_SERVER_MODEL_NAME`, `MCP_FOS_ASSISTANT_API_KEY` 와 카탈로그 커넥터의 `fields[].env` 뿐 | 200. 커넥터 key 는 `{profile, key, restart_required}` | 400 다른 key, `default`, 형식. 404 없는 profile |
| `DELETE /api/env` | 관리 profile 의 커넥터 칸 key 만 지운다 | `{profile, key}` | `{profile, key, restart_required}` | |
| `GET /api/connectors/catalog` | 운영 목록에 있고 검증을 통과한 커넥터의 manifest 를 낸다 | 없음 | `[{id, schema, title, description, icon, link, fields[], verify, mcp_server, toolsets, attachments, single_binding, tools, skills, owner_browser, owner_browser_login_url}]`. 아래 「카탈로그 응답」 이 칸을 갖는다 | |
| `POST /api/connectors/<id>/call` | 후보 값이나 보관 파일의 값으로 그 커넥터의 선택지 도구나 확인 도구를 한 번 부른다 | `{tool, values}` 또는 `{tool, vault}`. 둘 가운데 정확히 하나다. 사용자 브라우저를 쓰는 커넥터면 Control Plane 이 `owner_browser` 를 더한다 | `{ok: true, result}` 또는 `{ok: false, error: <공통 어휘>}` | 400 그 커넥터의 보관 파일이 없다 |
| `POST /api/connectors/<id>/execute` | Control Plane 이 승인한 호출을 그 profile 의 값과 받은 인자로 한 번 실행한다 | `{profile, hermes_tool, args}`. 중계 주소는 본문으로 받지 않고 설치한 서버 정의에서 읽는다 | `{ok: true, result}` 또는 `{ok: false, error: <공통 어휘>}` | 504 실행됐는지 모른다 |
| `GET /api/connectors?profile=<p>` | 커넥터의 상태를 읽는다 | query `profile` | `{profile, policy_hook, connectors: [{plugin, enabled, configured, mode}]}`. `mode` 는 `bind` 나 `isolated` 다 | 401 관리 표식과 커넥터 표식이 모두 없다 |
| `PUT /api/connectors` | profile 에 커넥터를 설치하고 제거한다 | `{profile, plugin, enabled, sandbox_owner?, owner_browser?}`. 바인딩 설치는 `bind: {vault}` 를 더하고 Control Plane 이 그 에이전트의 `sandbox_owner` 를 함께 보낸다. `owner_attachments_env` 를 선언한 커넥터의 바인딩 설치와 사진 도구를 여는 옛 설치는 `sandbox_owner` 가 필요하다. `owner_output_env` 를 선언한 커넥터는 `sandbox_owner` 와 `connector_output_root` 가 있고 profile 이 정책에 등록됐을 때만 출력 디렉터리를 받고, 아니면 빈 값으로 붙는다. 떼면 그 디렉터리를 지운다. `owner_browser_env` 를 선언한 커넥터의 바인딩 설치에는 Control Plane 이 `owner_browser`(그 바인딩의 중계 주소, 중계가 꺼졌으면 빈 값)를 더하고 plugin 이 그 값을 서버 정의에 넣는다. 선언하지 않은 커넥터는 이 값을 쓰지 않는다 | `{profile, plugin, enabled, changed, restart_required, plugin_updated, reload_pending}`. `reload_pending` 은 바인딩 설치만 참이 될 수 있다. 옛 설치는 서버 등록과 함께 그 profile 의 API 도구 목록과 `SOUL.md` 를 다시 쓴다. 사진 도구를 여는 옛 설치는 `terminal:` 과 함께 `approvals.unattended_mode` 도 맞춘다. 바인딩 설치는 서버 이름만 더하고 뺀다 | 400 보관 파일이 없거나 다른 커넥터의 것이다, `owner_browser` 가 문자열이 아니거나 중계 주소 모양이 아니다. 401 표식. 409 운영자 설정과 충돌한다, 사진 도구에 실행 공간 정책이나 등록 profile 이 없다, `owner_attachments_env` 를 선언한 커넥터에 실행 공간 정책이 없거나 그 주인의 첨부 디렉터리를 링크 없이 확인하지 못했다, `sandbox_required` 를 선언한 커넥터의 바인딩 설치인데 profile 이 실행 공간 정책에 없다. 실행 공간 사유의 409 는 본문 `code` 가 `sandbox_unavailable` 이고, 운영자 설정 충돌은 그 `code` 를 싣지 않는다. 400 그 커넥터에 `sandbox_owner` 가 없다 |
| `PUT /api/connector-vault` | 연결의 칸 값을 보관 파일 하나에 쓴다 | `{vault, connector, values}`. `values` 는 `fields[].key` 를 키로 한 값이다 | `{ok: true}` | 400 형식, 운영 목록에 없는 커넥터, 칸 선언과 맞지 않는 값. 409 같은 이름의 보관 파일이 다른 커넥터의 것이다 |
| `DELETE /api/connector-vault` | 보관 파일 하나를 지운다 | `{vault}` | `{changed}`. 없었으면 `false` 다 | 400 형식 |
| `POST /api/connector-vault/import` | 그 커넥터를 옛 설치한 관리 profile 의 `.env` 에서 칸 값을 보관 파일로 옮긴다 | `{vault, connector, profile}` | `{ok: true}` | 400 필수 칸이 비었다. 401 관리 표식이 없다. 404 없는 profile, 설치하지 않은 커넥터. 409 다른 커넥터의 보관 파일 |
| `POST /api/mcp/servers/<서버>/test?profile=<p>` | 그 profile 에 설치한 커넥터의 MCP 서버만 probe 한다 | 없음 | `{ok, tools: [{name}]}` | |
| `GET /api/tools/toolsets` | 도구 이름과 설명을 읽는다 | 없음 | 200 | |
| `PUT /api/config` (도구) | 지정한 profile 의 API 도구 목록을 쓴다. `terminal`, `file`, `code_execution`, `vision`, `image_gen`, `video_gen` 가운데 하나라도 있으면 profile 의 `terminal:` 을 실행 공간 설정으로 다시 쓴다. 같은 쓰기에서 `approvals.unattended_mode` 를 docker 면 `approve` 로 두고 local 이면 지운다(「셸 실행 공간」) | `{profile, config: {platform_toolsets: {api_server: [...]}}, sandbox_owner?, require_sandbox?}`. `sandbox_owner` 는 `^[a-z][a-z0-9-]{0,63}$` 이고 실행 공간 도구를 켜는 등록 profile 에서 필수다. `require_sandbox` 는 참 거짓 값이고, 참이면 정책에 없는 profile 의 셸 도구 저장을 local 로 돌리지 않고 409 `sandbox_unavailable` 로 거절한다. Control Plane 의 기본 도구 적용이 보낸다 | 200 | [ADR-029](../../../backend/docs/adr/ADR-029-에이전트-도구는-control-plane-이-등급으로-판정하고-hermes-설정-api-로-쓴다.md) 그대로. 409 소유 기록의 바인딩 서버 이름이 목록에 빠졌다 |
| `PUT /api/config` (스킬 게시) | 지정한 profile 의 올린 스킬 경로를 쓴다 | `{profile, config: {skills: {external_dirs: [<Hermes 쪽 스킬 루트>/<profile>/<버전>]}}, sandbox_owner?, require_sandbox?}`. 버전 이름은 `v[0-9]{13}-[a-z0-9]{4}` 다(`v` 뒤에 UTC 밀리초 13자리와 소문자 영숫자 4자). 목록은 0개나 1개. 0개는 게시 해제. 도구 목록을 같은 본문에 둘 수 있다. `scripts/` 가 든 스킬을 게시할 때 Control Plane 은 지금 도구 목록과 `require_sandbox: true` 를 함께 보낸다. 그때 plugin 은 목록에 실행 공간 도구가 있고, profile 이 정책에 등록돼 있고, 정책에 `skill_root` 가 있어야 받는다. 이 판정이 없는 옛 plugin 은 `require_sandbox` 를 셸 도구에만 적용해 그대로 받으므로, 이 판정을 가진 plugin 을 Control Plane 보다 먼저 배포한다 | 200 | 400 경로 형식, 다른 profile 의 prefix, 둘 이상, 심볼릭 링크, 없는 디렉터리, `skills` 도구가 꺼진 채 게시. 409 운영자가 넣은 다른 외부 경로가 있다. 409 `sandbox_unavailable` 은 `require_sandbox` 인데 위 셋 가운데 하나가 없다. 404 없는 profile |
| `GET /api/skills?profile=<p>` | 지정한 profile 의 스킬 목록을 읽는다 | query `profile` 하나 | 200 `[{name, description, category, enabled, usage, provenance}]`. `enabled` 는 전역 `skills.disabled` 만 반영 | 400 query 누락, 둘 이상, `default`. 404 |
| `PUT /api/skills/toggle` | 지정한 profile 의 스킬 하나를 켜고 끈다 | `{profile, name, enabled}` | 200 `{ok, name, enabled}` | 400, 404 |
| `GET PUT /api/profiles/<이름>/soul` | 그 profile 의 SOUL.md 를 읽고 쓴다 | `PUT` 은 `{content}` | 200 | |
| `GET /api/profiles/<이름>/model-defaults` | 그 profile 설정의 `provider`, `model`, `reasoningEffort` 세 값만 읽는다. 기본 profile 도 읽는다 | 없음 | 200 `{provider, model, reasoningEffort}`. 없는 값은 `null`. 설정 전체와 비밀값은 반환하지 않는다. [모델 단계와 실행 기록](../../../docs/model-tiers.md) 의 「profile 기본 강도」 를 따른다 | 400 이름 형식. 404 없는 profile. 503 설정을 읽지 못함 |
| `GET /api/profiles/<이름>/sessions/<session id>/provider` | 그 profile 의 자식 session 한 줄에서 provider 와 모델만 읽는다. 기본 profile 도 읽는다 | 없음 | 200 `{provider, model}`. 아래 「자식 session 의 provider」 가 칸을 갖는다 | 400 이름이나 session 번호 형식. 404 없는 profile, 없는 session, 자식이 아닌 session. 503 저장소를 읽지 못함 |

표의 「그 profile」 은 있고 `default` 가 아닌 이름이다.
기본 profile 은 400 이고 없는 profile 은 404 다. `model-defaults` 와 자식 session 의 provider 경로는 기본 profile 도 받는다.
본문과 query 에 profile 이 둘 다 있으면 같아야 한다.

### 자식 session 의 provider

API server 의 session 응답은 provider 를 주지 않는다. 그 값은 Hermes 의 session 저장소에만 있다.
이 경로는 그 값을 Control Plane 에 읽어 준다. 근거는 [ADR-067](../../../backend/docs/adr/ADR-067-native-하위-에이전트의-provider-는-대시보드-plugin-이-session-저장소에서-읽어-준다.md) 에,
저장소의 어느 칸이 무엇을 뜻하는지는 [`hermes/docs/hermes-contract.md`](../../docs/hermes-contract.md) 의 「자식 session 의 provider 는 저장소에만 있다」 에 있다.

| 항목 | 값 |
| --- | --- |
| session 번호 | `[A-Za-z0-9_-]` 1자에서 128자. 아니면 400 |
| 읽는 파일 | 그 profile 디렉터리의 `state.db`. 없으면 404 |
| 여는 방식 | Python `sqlite3` 의 `mode=ro` URI. Hermes 의 저장소 클래스를 쓰지 않는다. 제한 시간은 2초다 |
| 대상 | `sessions` 표에서 `id` 가 같고 `source` 가 `subagent` 인 줄. 없으면 404 |
| `model` | 그 줄의 `model`. 비었으면 `null` |
| `provider` | 그 줄의 `billing_provider`. 비었으면 `null` |
| 짝이 둘 이상 | `session_model_usage` 에서 그 session 의 `task` 가 빈 줄을 `(model, billing_provider)` 로 묶어 둘 이상이면 `provider` 를 `null` 로 준다. 한 금액으로 환산할 수 없는 자식이다 |
| 짝이 하나인데 provider 가 다르다 | 그 짝의 `billing_provider` 가 `sessions` 줄의 값과 다르면 `provider` 를 `null` 로 준다. 어느 쪽이 맞는지 알 수 없다 |
| 읽기 실패 | 파일을 열지 못했거나 표나 칸이 없으면 503. 까닭을 응답과 로그에 싣지 않는다 |

이 둘 말고는 어느 칸도 내보내지 않는다. 대화 본문, system prompt, 토큰 수, 다른 종류의 session 은 이 경로로 읽지 못한다.
저장소를 쓰는 연결을 열지 않는다. 그래서 이 경로는 `state.db` 의 내용을 바꾸지 못한다.
쓰는 연결이 하나도 없을 때 읽으면 SQLite 가 WAL 보조 파일(`state.db-shm`, `state.db-wal`)을 만들 수 있다. 그 디렉터리에 쓸 수 없고 보조 파일도 없으면 열지 못해 503 이다.

**Hermes 버전을 올리면 표 이름과 칸 이름을 다시 확인한다.** 이름이 바뀌면 이 경로가 503 으로 답하고 Control Plane 은 그 자식을 가격 미확인으로 남긴다.

**카탈로그 응답.** `fields[]` 는 manifest 의 칸 그대로(`env`, `options` 포함)이고 `verify` 는 `{tool}` 이다. `owner_browser` 는 `owner_browser_env` 를 선언했는지이고 env 이름은 내지 않는다. `owner_browser_login_url` 은 선언한 `https` 주소나 null 이다. 옛 대시보드 plugin 은 두 칸을 내지 않고 Control Plane 은 거짓과 null 로 읽는다.
`toolsets` 와 `attachments` 는 manifest 에 없으면 빈 목록과 거짓이다. 옛 대시보드 plugin 은 두 칸을 내지 않고, Control Plane 은 없는 칸을 같은 기본값으로 읽는다.
`single_binding` 은 boolean 이고 선언이 없으면 거짓이다. 옛 대시보드 plugin 은 이 칸을 내지 않고, Control Plane 은 없는 칸을 거짓으로 읽는다. `sandbox_required` 는 싣지 않는다. 대시보드가 바인딩 설치에서 직접 판정한다([ADR-20261008 / connector-binding-guards](../../../docs/adr/ADR-20261008-connector-binding-guards.md)).
`tools` 는 `{<이름>: {risk, approval, title, grant, outbound, identifiers}}` 이고 `approval`, `grant`, `outbound`, `identifiers` 는 기본값을 채운 값이다. `schema: 1` 은 `verify.tool` 과 `options.tool` 만 `READ` 로 담는다.
`schema` 가 없는 응답은 `1` 로 읽는다. `operator_env` 의 이름과 값, `errors` 는 담지 않는다.
`skills` 는 바인딩 설치가 profile 에 복사할 스킬의 이름 목록이고 이름 순이다. 이름은 `SKILL.md` 앞머리의 `name` 이고, 없으면 디렉터리 이름이다. 본문은 담지 않는다.
`fields` 가 빈 목록인 커넥터도 받는다. 값을 받지 않는 일반 MCP 서버다.
`icon` 은 `{media_type, data}` 이거나 null 이고 `link` 는 글이거나 null 이다. 검사 규칙은 [커넥터 연결](../../connectors/README.md) 의 「아이콘과 링크」 가 갖는다. 옛 대시보드 plugin 은 두 칸을 내지 않고, Control Plane 은 없는 칸을 null 로 읽는다.

**`POST /api/profiles` 는 틀을 쓰지 못하면 만든 것을 지운다.**
본문은 `name`, `no_skills`, `description` 만 받는다. `clone_from` 은 다른 profile 의 `.env` 를 끌어오므로 400 이다.
clone 없이 만든 profile 은 그대로 두면 API 경로에 거의 모든 toolset 이 열린다.
plugin 이 `default-config.yaml.template` 을 쓰고 `_get_platform_tools` 로 계산해,
`memory`, `terminal`, `file`, `code_execution`, `browser` 가 하나라도 남으면 지우고 500 을 돌려준다.
틀이 켜는 plugin 을 `profile-plugins/` 에서 찾지 못해도 같다. 순서와 분기는 plugin 의 docstring 이 소유한다.

틀에는 Control Plane MCP 등록(토큰은 `${MCP_FOS_ASSISTANT_API_KEY}` 참조)과 `plugins.enabled: [fos-ctx]` 가 들어 있다.
만든 뒤 plugin 이 공유 gateway 에 그 profile 의 plugin 을 다시 읽게 해 첫 실행부터 hook 이 돈다.
MCP 도구는 만든 뒤 1~2분 안에 붙는다. 이유는 운영 저장소의 Hermes 운영 문서가 갖는다.

**틀은 묶음을 만들 때 plugin 옆에 복사된다.** 저장소에는 `profile-template/config.yaml.template` 한 벌만 둔다.
틀이나 fos-ctx 를 고쳤으면 묶음을 다시 만들어 설치하고 대시보드를 다시 띄워야 새 profile 에 반영된다.

**사람이 대시보드에서 만드는 profile 에는 틀을 쓰지 않는다.** 쿠키로 들어온 요청은 plugin 이 손대지 않는다.

**`DELETE` 는 관리 표식 `.fos-assistant-managed` 가 있는 profile 에만 열린다.**
표식은 토큰으로 만든 profile 에 plugin 이 모든 검사를 지난 뒤 마지막에 쓴다.
사람이 만든 profile 과 기본 profile 은 401 이다. 파일이라 대시보드를 다시 띄워도 남는다.

**`PUT /api/env` 는 [plugin 의 허용 목록](common.py) 에 있는 key 만 받는다.**
기본 key 는 plugin 이 갖고, 커넥터 key 는 카탈로그 manifest 의 `fields[].env` 로 요청마다 계산한다.
본문은 `{profile, key, value}` 이고 값은 한 줄이어야 한다.
provider credential 은 이 토큰으로 쓰지 못한다.

### 커넥터

**plugin 은 커넥터의 이름을 코드에 두지 않는다.**
운영 목록의 plugin 디렉터리마다 `connector.json` 을 읽어 카탈로그로 내고, 선택지와 확인 도구를 대신 부른다.
`connector.json` 의 형식은 [커넥터 연결](../../connectors/README.md) 의 「connector.json」 이 소유한다. 커넥터 경로가 설치와 확인에서 뜻하는 것은 [커넥터 설치](../../../docs/backend/connector-install.md) 의 「대시보드 plugin 계약」 이 소유한다. 근거는 [ADR-043](../../../backend/docs/adr/ADR-043-커넥터는-plugin-의-connector-json-으로-선언하고-control-plane-은-범용-흐름만-갖는다.md) 에 있다.

**운영 목록은 환경 변수 `FOS_ASSISTANT_CONNECTOR_ROOTS` 로 받는다.** 커넥터 이름마다 값 하나를 둔 JSON object 다.

```json
{
  "<커넥터 이름>": {
    "root": "<plugin 디렉터리>",
    "command": "<MCP 서버를 실행할 파일>",
    "env": { "<operator_env 이름>": "<값>" }
  },
  "<다른 커넥터 이름>": "<plugin 디렉터리>"
}
```

- 값이 문자열이면 `{"root": 그 값}` 으로 읽는다
- `command` 가 없으면 `FOS_ASSISTANT_CONNECTOR_COMMAND` 를 쓴다. 둘 다 없거나 실행 권한이 없으면 그 커넥터는 쓸 수 없다
- `connector.json` 이 `operator_env` 로 적은 이름은 모두 `env` 에 값이 있어야 한다. 그 값은 설치할 때 서버 정의에 직접 들어가고 profile `.env` 를 거치지 않는다
- 경로는 절대 경로다. 모양이 틀린 항목은 그 항목만 버린다

**plugin 디렉터리에서 읽는 것은 넷이다.** `.claude-plugin/plugin.json`, `.mcp.json`, `connector.json`, 스킬 디렉터리다.
하나라도 검증에 실패하면 그 커넥터는 카탈로그에서 빠지고 경고 로그 한 줄이 남는다. 대시보드와 다른 커넥터는 그대로 돈다.

- `.mcp.json` 은 서버 하나만 둔다. 그 이름이 커넥터의 MCP 서버 이름이다
- 서버의 `command` 는 쓰지 않고 운영 목록의 실행 파일로 바꾼다
- 서버의 `args` 가운데 `${CLAUDE_PLUGIN_ROOT}/` 로 시작하는 것은 plugin 디렉터리 안의 링크 없는 파일이어야 하고 실제 경로로 바꾼다. 그 밖의 `$` 치환은 받지 않는다
- 서버의 `env` 는 자기 이름의 `${이름}` 참조만 둔다. 선택 칸은 `${이름:-}` 도 된다
- 값이 없는 선택 칸은 설치할 때 서버 정의에 빈 값을 명시한다. Hermes 는 빈 변수의 참조를 그대로 남기기 때문이다

**`POST /api/connectors/<id>/call` 은 자식 프로세스를 띄운다.**

- 자식이 받는 환경 변수는 요청의 칸 값, 운영 목록의 `env`, MCP SDK 가 늘 더하는 기본 env(`HOME`, `LOGNAME`, `PATH`, `SHELL`, `TERM`, `USER`)뿐이다. `owner_attachments_env` 와 `owner_output_env` 를 선언한 커넥터에는 그 이름을 빈 값으로 준다. 이 경로에는 바인딩 주인이 없기 때문이다.
  `owner_browser_env` 를 선언한 커넥터에는 요청의 `owner_browser`(요청자의 호출 표식을 실은 중계 주소)를 준다. 없거나 중계 주소 모양이 아니면 빈 값이다. 선언하지 않은 커넥터는 그 값을 받아도 넘기지 않는다.
  `vault` 로 부르면 보관 파일의 키 가운데 지금 manifest 의 칸에 없는 것은 버린다. 칸을 뺀 커넥터의 옛 연결도 확인되게 하려는 것이다. `values` 의 모르는 칸은 그대로 `invalid_input` 이다.
  대시보드 프로세스의 다른 env(서비스 토큰, 다른 커넥터의 값)는 넘어가지 않는다. `PATH` 는 대시보드의 값 대신 실행 파일이 있는 디렉터리만 준다
- 후보 값은 디스크, 응답, 로그에 남기지 않는다. 자식의 stderr 도 대시보드 로그로 보내지 않는다
- 도구는 인자 없이 부른다. 값은 환경 변수로만 간다
- profile 쓰기 잠금 밖에서 돈다. 도구를 기다리는 동안 다른 profile 요청이 멈추지 않는다
- 시간을 넘기거나 예외가 나면 SDK 의 정리 구간이 자식의 stdin 을 닫고, 끝나지 않으면 프로세스 묶음을 죽인 뒤에 응답한다
- `mcp` SDK 는 이 경로 안에서 import 한다. SDK 가 없으면 이 경로만 `unavailable` 이고 나머지는 그대로 돈다

**`POST /api/connectors/<id>/execute` 는 Control Plane 이 승인한 호출만 부른다. 대시보드는 승인 여부를 다시 확인하지 않는다.**

- 그 profile 에 관리 표식이나 커넥터 표식과 그 커넥터의 소유 기록이 있어야 한다. 커넥터 표식만 있는 profile 은 소유 기록의 그 항목이 바인딩 설치여야 한다. 자식의 env 는 그 profile `.env` 의 칸 값과 운영 목록의 `env` 다. `owner_attachments_env` 를 선언한 커넥터는 설치한 서버 정의의 그 값을 더한다. `owner_output_env` 는 빈 값이다. `owner_browser_env` 를 선언한 커넥터는 설치한 서버 정의의 중계 주소를 더하고, 그 값이 중계 주소 모양이 아니면 빈 값이다
- 인자와 결과를 로그에 싣지 않는다
- 실행되지 않은 것이 분명한 실패는 `{ok: false}` 로, 시간 초과와 도구 호출을 보낸 뒤의 실패는 504 로 답한다. 504 는 실행됐는지 모른다는 뜻이다
- 도구가 `errors` 표에서 `outcome_unknown` 인 코드로 실패해도 504 로 답한다. `call` 에서는 그 코드를 `unavailable` 로 돌려준다
- 요청과 응답은 [커넥터 연결](../../../docs/connectors.md) 의 「승인」 이 소유한다. 근거는 [ADR-050](../../../backend/docs/adr/ADR-050-커넥터-쓰기는-control-plane-이-승인-줄을-저장하고-승인한-인자로-한-번만-실행한다.md) 에 있다

**소유 기록 `.fos-connectors.json` 은 설치할 때의 서버 정의를 갖는다.** 옛 설치는 요청마다 지금의 manifest 와 같은지 검증한다. 아래 표는 옛 설치의 판정이다.

| 상태 | 설치한 적 없는 profile | 이미 설치한 profile |
| --- | --- | --- |
| 운영 목록에서 빠졌다 | 상태 조회에 나오지 않고 설치는 400 이다. 제거는 끌 것이 없어 `changed: false` 로 성공한다 | 상태 조회는 `configured: false` 다. 제거는 되고 probe 는 404 다 |
| 실행 파일을 받지 못했다 | 설치가 503 이다 | 상태 조회, 제거, probe 가 모두 503 이다 |
| 설치한 뒤 실행 파일, plugin 경로, 실행 정의의 command·args, 운영자 env 값이 바뀌었다 | 해당 없음 | 상태 조회, 제거, probe 가 모두 503 이다 |

운영 목록에서 빠진 커넥터를 제거하면 plugin 이 그 기록의 서버 env 가 `${이름}` 으로 참조하던 key 를 profile `.env` 에서 함께 지운다. Control Plane 이 그 이름을 더는 알 수 없기 때문이다.
값이 바뀌어 503 이 된 profile 은 값을 되돌리면 다시 읽힌다.
바인딩의 실행과 probe 는 요청이 가리키는 커넥터의 항목만 견준다. 상태 조회는 바뀐 바인딩 항목만 `configured: false` 로 답하고, 같은 profile 의 다른 커넥터는 probe 와 실행과 붙이기가 그대로 된다.
바인딩 설치는 기록의 형식과 실제 설정의 소유권을 확인한 뒤, command, args, env 가 바뀌었어도 새 manifest 로 다시 설치한다. 보관 파일과 연결 값은 유지한다. 떼기는 바인딩 항목을 지금 manifest 와 견주지 않는다.
연결 확인과 관리자 반영 완료의 재설치·재시작 대기 흐름은 [커넥터 설치](../../../docs/backend/connector-install.md) 의 「바인딩의 반영 맞추기」 가 갖는다.
**운영은 환경 변수를 먼저 준 뒤 plugin 을 올린다.** 옛 설치의 값을 바꿔야 하면 바꾸기 전에 설치한 커넥터를 제거한다.

**설치는 두 가지다.** 소유 기록 항목의 `mode` 가 방식을 적는다. 칸이 없으면 옛 설치다.
한 profile 에 두 방식을 섞지 않는다. 섞으려는 설치는 409 다. 옛 설치는 Control Plane MCP 등록을 지우는데 바인딩 설치는 그 등록이 있어야 하기 때문이다.

| | 옛 설치(`isolated`) | 바인딩 설치(`bind`) |
| --- | --- | --- |
| 받는 profile | 관리 표식이 있는 profile | 관리 표식이나 커넥터 표식이 있고, `platform_toolsets.api_server` 목록에 Control Plane MCP 가 있고, `fos-ctx` 가 켜진 profile |
| 칸 값 | Control Plane 이 `PUT /api/env` 로 그 profile `.env` 에 쓴다 | 설치가 보관 파일의 값을 그 profile `.env` 로 복사한다 |
| API 도구 목록 | 설치한 커넥터의 서버 이름과 선언한 `toolsets` 로 통째로 다시 쓴다 | 서버 이름만 더하고 뺀다. 다른 이름은 그대로 둔다 |
| Control Plane MCP 등록 | 지운다 | 그대로 둔다 |
| 지침 | 스킬 본문을 `SOUL.md` 에 쓴다 | 스킬 디렉터리를 그 profile 의 `skills/` 로 복사한다. `SOUL.md` 는 읽지도 쓰지도 않는다 |
| 대응 파일 | `isolated` 칸 없이 쓴다 | `isolated: false` 를 싣고 소유 기록의 모든 서버와 뗀 서버 기록 `.fos-connector-detached.json` 의 서버를 싣는다 |
| 설치의 `restart_required` | 지금과 같다 | 이미 있던 서버의 정의나 그 서버의 `.env` 값이 바뀌었거나, 뗀 서버 기록에 남은 이름을 다시 붙이면 참이다. 공유 gateway 의 MCP 설정 맞추기는 이름만 비교해 같은 이름을 다시 연결하지 않는다. 뗀 기록에 없는 새 이름, 스킬, 이름 대응만 바뀐 설치는 거짓이고 `reload_pending` 이 참이다([ADR-20261007 / connector-live-reload](../../../docs/adr/ADR-20261007-connector-live-reload.md)) |
| 떼기의 `restart_required` | 지금과 같다 | 거짓이고, 바뀐 것이 있으면 `reload_pending` 이 참이다. 도구 목록에서 이름을 빼므로 다음 실행부터 막힌다. 떼기 전에 시작한 실행의 호출은 대응에 남은 서버로 판정이 막는다 |
| `reload_pending` | 늘 거짓이다 | 바뀐 것이 있고 `restart_required` 와 `plugin_updated` 가 모두 거짓이면 참이다. `plugin_updated` 가 참이면 그것만으로 재시작을 기다린다 |
| 스킬 색인 표식 | 쓰지 않는다 | 스킬 파일을 바꾼 설치는 `skills.disabled` 에서 `fos-skill-index-` 로 시작하는 항목을 빼고 새 표식 하나를 더한다. gateway 가 그 profile 의 스킬 색인을 새로 만든다. 다른 이름은 그대로 두고, 문자열이면 Hermes 가 읽는 대로 목록으로 바꿔 쓴다. 고칠 수 없는 모양이면 붙이기는 409 이고 떼기는 표식 없이 뗀다 |

**보관 파일은 연결의 칸 값을 연결마다 하나씩 둔다.**
대시보드의 HERMES_HOME 아래 `connector-vault/<vault>.json` 이고, 디렉터리는 700, 파일은 600 이다.
`vault` 는 `^c[1-9][0-9]{0,18}$` 이다. Control Plane 의 연결 번호 앞에 `c` 를 붙인 것이다.
본문은 `{"v": 1, "connector": "<id>", "values": {"<field key>": "<값>"}}` 이고, 빈 선택 칸은 넣지 않는다. 칸이 없는 커넥터는 빈 `values` 다.
값과 경로는 응답, 로그, 예외 메시지, 설정 백업에 싣지 않는다. 쓰기와 지우기는 profile 쓰기 잠금 안에서 하고, 바인딩 설치도 같은 잠금 안에서 보관 파일을 읽는다.

**커넥터 표식 `.fos-connector-host` 는 사람이 만든 profile 이 바인딩 설치를 받는다는 표시다.**
운영자가 그 profile 에 두고 plugin 은 쓰지 않는다. 표식만 있는 profile 은 바인딩 설치와 떼기, 상태 조회, probe, 바인딩 항목의 실행만 받는다. 소유 기록에 없는 커넥터의 떼기도 바인딩 떼기로 받아 `changed: false` 로 답한다. 옛 설치와 커넥터 칸 key 의 `PUT /api/env`, `DELETE /api/env` 는 401 이다. 바인딩의 env 는 설치와 떼기가 쓰고 지운다.

설치와 제거는 Hermes 등록 이름과 원래 도구 이름의 대응을 그 profile 의 `.fos-connector-tools.json` 에 다시 쓰고, 설치는 `approval: always` 인 도구를 서버 정의의 `tools.exclude` 에 넣고 profile 의 `fos-ctx` 를 묶음의 판으로 맞춘 뒤 파일이 바뀌었는지를 `plugin_updated` 로 답한다.
`GET /api/connectors` 는 `policy_hook` 을 함께 낸다. `fos-ctx` 가 켜져 있고 묶음의 판과 같고 대응 파일과 `tools.exclude` 가 지금 manifest 와 맞을 때만 참이며, 조건은 [커넥터 도구 정책](../../../docs/backend/connector-tool-policy.md) 의 「hook 이 켜져 있는지」 가 갖는다.

**한 배포 동안 옛 Control Plane 의 호출과 옛 소유 기록의 필드 모양·운영자 env 표현을 받는다**([ADR-041](../../../docs/adr/ADR-041-hermes-에-설치하는-plugin-과-profile-틀은-이-저장소가-소유한다.md)). 옛 설치는 실행 정의의 command·args 가 지금 manifest 와 다른 기록을 받지 않는다. 바인딩은 위 재설치 경로로 새 정의를 적용한다.

- 옛 기록은 운영자 env 를 `${이름}` 참조로 갖고 MCP 서버 이름 칸이 없다. 운영자 env 는 그 참조와 지금의 직접 값을 같다고 본다. 다음 설치 요청이 기록을 새 모양으로 다시 쓴다
- 운영자 env 이름의 `PUT /api/env` 와 `DELETE /api/env` 는 성공으로 답하고 아무것도 쓰지 않는다

**토큰으로 부른 `PUT /api/config` 는 본문을 검사한 뒤 Hermes 처리기에 넘긴다.**
최상위에는 `profile` 과 `config` 를 두고, `sandbox_owner` 를 더할 수 있다. 형식은 `^[a-z][a-z0-9-]{0,63}$` 이다.
`terminal`, `file`, `code_execution` 을 켜고 해당 profile 이 실행 공간 정책에 등록돼 있으면 `sandbox_owner` 가 필수다. 미등록 profile 의 local 저장에서는 생략할 수 있다.
`require_sandbox: true` 면 미등록 profile 의 셸 저장은 local 대신 409 다.
`vision`, `image_gen`, `video_gen` 은 미등록 profile 에서 local 로 저장하지 않고 409 로 거절한다. Hermes host에서 다른 사용자의 첨부를 직접 읽지 않게 하려는 것이다.
`terminal:` 은 plugin 이 직접 쓰므로 본문의 `config` 에 두지 않는다.
`config` 에는 `platform_toolsets.api_server` 와 `skills.external_dirs` 가운데 하나나 둘을 둔다.

도구 목록은 이렇게 본다.

- `agent` 키는 받지 않는다. `agent.disabled_toolsets` 는 아래 「켠 API 도구는 `disabled_toolsets` 에서 뺀다」 대로 plugin 이 고친다
- 목록의 이름은 Hermes toolset 또는 그 profile 에 등록된 MCP 서버여야 한다
- `api_server` 에는 `memory` 를 넣지 않고 Control Plane MCP `fos-assistant` 를 남긴다
- Control Plane MCP 가 아직 등록되지 않은 profile 은 그 이름과 알려진 내장 toolset 을 함께 넣는다
- Hermes 가 계산한 실제 API 도구에 요청 목록 밖의 이름이 있으면 저장하지 않는다
- 다른 platform 의 계산 결과가 바뀌어도 저장하지 않는다
- 목록에 `terminal`, `file`, `code_execution`, `vision`, `image_gen`, `video_gen` 가운데 하나라도 있으면 `FOS_ASSISTANT_SANDBOX` 를 검사한다. 값이 없거나 틀리면 409 와 본문 `code: sandbox_unavailable` 이다. 정책에 등록된 profile 만 `terminal:` 전체를 docker 설정으로 다시 쓴다. 미등록 profile 은 `terminal`, `file`, `code_execution` 만 local 로 둘 수 있고 `vision`, `image_gen`, `video_gen` 은 409 로 거절한다. 이때 `approvals.unattended_mode` 도 함께 맞춘다(docker 는 `approve`, local 은 삭제)

올린 스킬 경로는 이렇게 본다. 루트는 대시보드 프로세스의 환경 변수 `FOS_ASSISTANT_SKILL_AGENT_ROOT` 다.

- `skills` 에는 `external_dirs` 만 둔다. `create_dir` 같은 다른 키는 모델이 스킬을 쓰는 자리를 바꾸므로 400 이다
- 목록은 경로 0개나 1개다. 0개는 게시를 거둔다
- 경로는 정확히 `<루트>/<profile>/<버전>` 이다. `~`, `$`, 역슬래시, 빈 조각, `.`, `..` 는 400 이다
- 버전 디렉터리가 있어야 한다. Hermes 는 없는 디렉터리를 오류 없이 건너뛰어 올린 스킬이 말없이 사라진다
- 경로와 그 아래 어디에도 심볼릭 링크가 없어야 한다. `skill_view` 가 파일을 그대로 읽어, 링크가 다른 profile 의 `.env` 를 가리키면 그 토큰이 모델에게 간다
- 경로 0개가 아니면 계산한 API 도구에 `skills` 가 있어야 한다. 같은 요청에서 켜도 된다
- 이미 저장된 `external_dirs` 에 그 profile 의 루트 밖 경로가 있으면 409 다. 목록을 통째로 바꾸면 운영자가 넣은 경로가 지워진다

경로 검사는 profile 설정을 읽기 전에 돈다. 운영 검사가 없는 profile 이름으로 거절 분기를 본다.

**켠 API 도구는 `disabled_toolsets` 에서 뺀다.**
Hermes 는 허용 목록을 계산한 뒤 `agent.disabled_toolsets` 를 마지막에 빼므로, 설정 틀이 막아 둔 `code_execution` 을 목록에 넣어도 API 실행에서 빠진다.
그래서 요청 목록에 있는 이름이 `disabled_toolsets` 에 있으면 plugin 이 그 이름을 거기서 뺀다. API 경로의 도구는 `api_server` 목록 하나가 정한다.

- `disabled_toolsets` 는 모든 platform 에 걸린다. 목록이 없어 기본 toolset 을 쓰는 platform 가운데 계산 결과가 바뀌는 것은 지금 계산 결과를 명시 목록으로 먼저 고정한다
- 목록이 이미 있는 platform 에 그 도구가 열리게 되면 위의 다른 platform 검사가 거절한다. 운영자가 그 목록을 먼저 고친다
- 요청 목록에 없는 이름은 그대로 둔다. 도구를 끌 때 다시 넣지 않는다. API 는 허용 목록이 막는다
- `memory` 는 요청 목록에 들어올 수 없으므로 `disabled_toolsets` 에 남는다

Hermes 처리기의 병합은 본문의 키만 쓰므로, plugin 이 검사를 마친 설정 전체를 처리기보다 먼저 쓴다.
검사한 뒤 파일이 바뀌었으면 쓰지 않고 409 를 돌려준다. 처리기가 실패하면 파일이 plugin 이 쓴 그대로일 때만 원래 바이트로 되돌린다.
Control Plane 은 사용자 소유권과 도구 등급을 판정한다. plugin 은 도구 등급을 판정하지 않는다.

**`GET /api/skills` 는 query 에 `profile` 하나만 받는다.** 없으면 대시보드 자기 profile 의 스킬이 읽히기 때문이다.
**`PUT /api/skills/toggle` 은 본문 `{profile, name, enabled}` 만 받는다.** 켜고 끄기는 Hermes 의 전역 `skills.disabled` 를 바꾼다.

### 올린 스킬의 이름은 다른 스킬 경로와 겹치지 않아야 한다

v2026.9.24 는 스킬 이름 하나를 아래 경로에서 찾고, 위의 것이 먼저다. 파일은 이미지의 `/opt/hermes` 기준이다.

| 순서 | 경로 | 보는 호출 |
| --- | --- | --- |
| 1 | `skills.trusted_project_dirs` 로 신뢰한 저장소의 `.hermes/skills`, `.agents/skills`. 작업 디렉터리가 그 저장소일 때만 | 모두 |
| 2 | profile 의 `skills/`. bundled 스킬도 동기화로 여기 들어온다 | 모두 |
| 3 | `skills.create_dir` | 시스템 프롬프트 색인만 |
| 4 | `skills.external_dirs`. 올린 스킬이 여기 있다 | 모두 |

경로 목록은 `agent/skill_utils.py` 의 `get_project_skills_dirs`, `get_all_skills_dirs` 와 `tools/skills_tool.py` 의 `_skill_search_dirs` 가 만든다.
올린 경로 밖에 같은 이름이 있으면 올린 스킬이 실행되지 않는다. 일회용 컨테이너에서 확인했다.

- 시스템 프롬프트의 스킬 색인, `skills_list`, 슬래시 명령은 위 경로의 스킬을 고른다.
  `agent/prompt_builder.py` `build_skills_system_prompt`, `tools/skills_tool.py` `_find_all_skills`, `agent/skill_commands.py` `scan_skill_commands` 다
- `skill_view(이름)` 은 후보가 둘 이상이면 이름 충돌로 거절한다. `tools/skills_tool.py` `_locate_skill` 이다.
  후보는 디렉터리 이름과 frontmatter `name` 가운데 어느 쪽이 같아도 모인다

plugin 스킬은 `plugin:이름` 으로만 불려 겹치지 않는다.

**새 이미지의 bundled 스킬은 올린 스킬을 가리지 않는다.**
`tools/skills_sync.py` `sync_skills` 는 `external_dirs` 에 같은 이름이 있으면 profile 로 복사하지 않고,
전에 복사한 것이 새 이미지의 원본과 바이트까지 같을 때만 지운다.
가리는 것은 사람이 둔 로컬 스킬, 고쳤거나 이전 판인 bundled 복사본, hub 로 설치한 스킬, `create_dir` 과 신뢰한 저장소다.

운영 저장소의 스킬 이름 충돌 검사 스크립트가 profile 마다 이것을 본다.
판정은 `skill_view` 가 후보를 모으는 `_collect_skill_candidates` 를 부르고, 위 표의 경로를 모두 넘긴다.
올린 스킬이 있는 profile 은 설정의 스킬 경로가 절대 경로이고 실제로 있어야 판정한다. 아니면 판정하지 못함으로 실패한다.
정기 live 검사와 업그레이드 검사가 부르고, 업그레이드 전에는 새 이미지로 부른다.
순서는 운영 저장소의 운영 절차 문서가 소유한다.

**여기 없는 것은 맞는 토큰으로도 열리지 않는다.**
`GET /api/config` 와 이름을 바꾸는 `PATCH`, 스킬을 만들고 고치는 `POST /api/skills`, `PUT /api/skills/content` 가 여기 해당한다.
`DELETE /api/env` 는 관리 profile 의 커넥터 칸 key 만 받는다.
