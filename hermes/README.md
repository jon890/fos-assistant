# Hermes 쪽 코드

Control Plane 이 기대는 Hermes 쪽 코드다. Hermes 에 설치하는 plugin 두 개와 새 profile 의 설정 틀을 둔다.
이 저장소가 이것을 갖는 근거는 [ADR-041](../docs/adr/ADR-041-hermes-에-설치하는-plugin-과-profile-틀은-이-저장소가-소유한다.md) 에 있다.

| plugin | 하는 일 | 두는 곳 | 읽는 프로세스 |
| --- | --- | --- | --- |
| `dashboard-profile-api` | 대시보드의 profile 관리와 도구 목록 경로를 `Authorization: Bearer` 로 연다 | Hermes 기본 루트의 `plugins/` | 대시보드 |
| `fos-ctx` | Control Plane MCP 호출 인자에 서명한 run 맥락 `_fos_ctx` 를 덮어쓰고, `skill_manage` 를 막고, 자식 session 을 등록하고, 연결용 profile 의 커넥터 도구 호출을 Control Plane 에 묻는다 | Control Plane MCP 를 등록한 profile 마다 | gateway |

## 범용 커넥터

`hermes/connectors/<커넥터 이름>/` 은 이 저장소가 유지보수하는 커넥터다([ADR-064](../docs/adr/ADR-064-범용-커넥터는-이-저장소의-hermes-connectors-에-두고-저장소가-유지보수한다.md)).
대시보드 plugin 이 읽는 plugin 디렉터리 모양 그대로이고, 운영자가 아래 「커넥터」 의 운영 목록에 그 디렉터리를 올려야 카탈로그에 나온다.
설치 묶음에는 들어가지 않는다.

| 커넥터 | 하는 일 | 문서 |
| --- | --- | --- |
| `gmail` | Gmail 을 찾고 읽고, 승인받은 메일과 라벨, 자동 분류 필터를 쓴다 | [Gmail 커넥터](../docs/connectors/gmail.md) |

커넥터의 MCP 서버는 TypeScript 로 쓰고 의존성까지 한 JavaScript 파일로 묶어 커밋한다.
운영 목록의 `command` 는 Bun 실행 파일이어야 한다. 서버 실행 중 패키지를 내려받지 않는다.
만드는 방법과 공통 검사는 [커넥터 만들기](../docs/connector-authoring.md) 가 갖는다.

## 설치 묶음

```bash
# cwd: 저장소 root
hermes/bundle.sh --out <디렉터리> --mcp-url <Control Plane MCP 주소>
```

`--out` 은 없거나 비어 있는 디렉터리여야 하고, `--mcp-url` 은 `http://` 나 `https://` 로 시작해야 한다.
묶음은 Hermes 의 `plugins/dashboard-profile-api/` 자리에 그대로 들어갈 모양이다.
plugin 파일, 주소를 채운 `default-config.yaml.template`, 틀의 `plugins.enabled` 가 켜는 profile plugin 을 담은 `profile-plugins/<이름>/` 이다.
틀의 MCP 주소 자리는 `__FOS_ASSISTANT_MCP_URL__` 이다. 주소를 주지 않거나 자리가 남으면 묶음을 만들지 않고 실패한다.
묶음을 Hermes 에 넣고 대시보드를 다시 띄우는 것은 운영 저장소가 한다.
디렉터리 배치와 배포 순서는 [`../docs/code-architecture.md`](../docs/code-architecture.md) 의 「Hermes 쪽 코드 (`hermes/`)」 절이 갖는다.

## 운영 값

운영 값은 코드에 두지 않고 설치할 때 받는다.

| 값 | 받는 곳 | 모양과 없을 때의 동작 |
| --- | --- | --- |
| Control Plane MCP 주소 | `bundle.sh --mcp-url` | 묶음을 만들 때 틀에 채운다 |
| 커넥터 목록 | 대시보드 프로세스의 환경 변수 `FOS_ASSISTANT_CONNECTOR_ROOTS` | 값의 모양은 아래 「커넥터」 에 있다. 이 목록에 있고 `connector.json` 검증을 통과한 것만 카탈로그에 나온다. 비었거나 읽지 못하면 커넥터가 하나도 없는 것으로 본다 |
| 커넥터 실행 파일 기본값 | 대시보드 프로세스의 환경 변수 `FOS_ASSISTANT_CONNECTOR_COMMAND` | 절대 경로다. 목록 항목에 `command` 가 없을 때 쓴다 |
| 대시보드 서비스 토큰 | 환경 변수 `HERMES_DASHBOARD_PROFILE_API_SECRET` | |
| 스킬 루트 | 대시보드 프로세스의 환경 변수 `FOS_ASSISTANT_SKILL_AGENT_ROOT` | |
| 커넥터 정책을 물을 주소 | gateway 프로세스의 환경 변수 `FOS_CTX_POLICY_URL` | Control Plane 의 `/internal/hermes/connector-policy` 다. 없으면 `fos-ctx` 가 연결용 profile 의 커넥터 도구를 모두 막는다 |
| 자식 session 을 등록할 주소 | gateway 프로세스의 환경 변수 `FOS_CTX_SUBAGENT_URL` | 없으면 등록하지 않는다. 등록이 없는 자식의 호출은 Control Plane 이 거절한다 |

## 검사

```bash
# cwd: 저장소 root
python3 -m unittest discover -s hermes/tests
```

Hermes 모듈은 가짜로 끼우므로 Hermes 를 설치하지 않아도 돈다. 실제 Hermes 와 맞는지는 운영 저장소의 live 검사가 본다.
Python 3.13 과 PyYAML 과 `mcp` SDK, 커넥터를 실행할 Bun 이 있어야 한다. Hermes 이미지와 같은 버전이다.
검사는 `mcp==2.0.0` 으로 돌고 plugin 의 지원 범위는 `mcp>=2.0,<3` 이다. SDK 계약은 [커넥터 설치](../docs/backend/connector-install.md) 의 「MCP SDK 계약」 이 갖는다.
커넥터 도구 호출 검사는 `tests/fixtures/demo-connector/` 의 시험 커넥터를 자식 프로세스로 띄운다.
`tests/test_connectors_contract.py` 는 `connectors/` 아래 커넥터를 모두 찾아 계약을 본다. 커넥터 전용 시험은 각 커넥터의 `tests/` 에 있다.
타입 검사와 전용 시험, 묶음 파일 비교는 저장소 루트의 `bash scripts/check-connectors.sh` 로 돌린다.

## fos-ctx 가 붙이는 것

Control Plane 의 `agent_*` 도구는 이 값으로 부모 실행을 찾고, 서명이 없거나 틀리면 거절한다.
키와 서명 규칙은 [`../docs/hermes/fos-ctx.md`](../docs/hermes/fos-ctx.md) 의 「`_fos_ctx` 계약」 이 소유한다.
`hermes/tests/test_fos_ctx.py` 가 그 절의 기대 서명으로 plugin 을 검사한다.

| 도구 | 서명하지 못할 때 |
| --- | --- |
| `agent_*`, `memory_read`, `artifact_write`, `follow_up_propose` | 막는다. 모델에게 막은 이유가 간다 |
| 그 밖의 Control Plane MCP 도구 | 막지 않고 원래 인자 그대로 보낸다 |
| Control Plane MCP 가 아닌 도구 | 건드리지 않는다. `skill_manage` 만 아래처럼 막는다 |

**v2026.9.24 는 MCP 도구를 `tool_call` 중계 도구 뒤에 둔다.** 기본 설정 `tools.tool_search` 가 켜져 있어서다.
모델은 `tool_call` 로 `mcp__fos_assistant__<도구>` 를 부르고, Hermes 는 그때도 실제 도구 이름으로 `pre_tool_call` 을 부른다.
일회용 컨테이너에서 이 경로로 서명이 붙어 도착하는 것을 확인했다.

### skill_manage 를 막는다

올린 스킬은 Control Plane 이 쓰고 Hermes 는 읽기 전용으로 본다.
그래도 모델이 `skill_manage` 로 같은 이름의 로컬 스킬을 만들면 로컬이 먼저 선택되어 올린 스킬이 가려진다.
그래서 fos-ctx 를 켠 profile 에서는 `skill_manage` 를 막고 「이 환경에서는 스킬을 대화로 만들거나 고칠 수 없다. 에이전트 관리 화면에서 올린다」 를 돌려준다.
fos-ctx 를 켠 모든 profile 에 걸린다. 사람이 운영하는 profile 도 fos-ctx 가 있으면 대화로 스킬을 만들 수 없다.

### 자식 session 을 등록한다

최상위 run 의 `delegate_task` 자식은 부모 run 이 끝난 뒤에도 백그라운드로 돈다.
Control Plane 은 부모 run 으로 자식의 요청자를 찾지 못하므로, fos-ctx 가 `subagent_start` hook 에서 자식 session 의 부모와 루트를 등록한다.
경로, 본문, 서명, 응답은 [`../docs/hermes/fos-ctx.md`](../docs/hermes/fos-ctx.md) 「하위 에이전트 session 등록 계약」 이 소유한다.

| 항목 | 값 |
| --- | --- |
| 주소 | 위 「운영 값」 의 자식 session 등록 주소. 운영이 준다 |
| 인증 | 그 profile 의 MCP 토큰 |
| 제한 시간 | 3초. 연결 실패와 5xx 에만 한 번 더 부른다 |
| 실패 | 로그만 남긴다. 등록이 없는 자식의 호출은 Control Plane 이 거절한다 |

Hermes 는 자식을 만드는 자리에서 부모 스레드로 이 hook 을 동기로 부른다. 그래서 등록이 자식의 첫 도구 호출보다 먼저 끝난다.
일회용 컨테이너에서 부모 run 이 0.25초에 끝나고 등록이 0.12초에 도착했다.
등록 로그는 `fos-ctx: 자식 session 을 등록했다 (시도 N)` 이고, 실패하면 상태 코드나 예외 종류만 남는다.

### 연결용 profile 의 커넥터 도구 호출을 묻는다

profile 디렉터리에 이름 대응 파일 `.fos-connector-tools.json` 이 있으면 fos-ctx 는 그 profile 을 연결용 profile 로 읽는다.
그 profile 에서는 커넥터 MCP 도구 호출마다 Control Plane 에 묻고 답대로 한다.
경로, 본문, 서명, 응답은 [`../docs/backend/connector-tool-policy.md`](../docs/backend/connector-tool-policy.md) 의 「도구 호출 판정」 이 소유한다.
대응 파일이 없는 profile 에서는 아래 처리를 하지 않는다.

| hook 이 본 것 | 처리 |
| --- | --- |
| `skill_manage` | 위와 같이 막는다 |
| 대응 파일을 읽지 못한다 | `mcp__` 도구와 `execute_code` 를 모두 막는다. Control Plane MCP 의 도구도 막는다. 연결용 profile 일 수 있고, 연결용 profile 에는 Control Plane MCP 가 없기 때문이다. 그 밖의 도구는 건드리지 않는다 |
| 대응 파일의 서버와 맞는 도구 | 등록 이름이 Control Plane MCP 의 접두사로 시작해도 Control Plane 에 묻는다. `_fos_ctx` 를 붙이지 않는다 |
| 대응 파일의 어느 서버와도 맞지 않는 Control Plane MCP 의 도구 | 위와 같이 `_fos_ctx` 를 붙인다 |
| `execute_code` | 막는다. 실행 맥락 없이 도구를 부르는 경로다 |
| `mcp__` 로 시작하지 않는 도구 | 건드리지 않는다 |
| `prefix` 가 맞는 서버가 없는 `mcp__` 도구 | 막는다. Control Plane 에 묻지 않는다 |
| `session_id` 나 `tool_call_id` 가 없거나 인자가 객체가 아니다 | 막는다. Control Plane 에 묻지 않는다 |
| 직렬화한 인자 글이 UTF-8 로 60KB 를 넘는다 | 막는다. Control Plane 에 묻지 않는다 |
| 대응 파일에 없는 도구 | `tool` 을 `null` 로 묻는다 |
| 답이 200 의 `allow` | 통과한다 |
| 답이 200 의 `block` 이고 글이 있다 | 그 글로 막는다 |
| 주소나 토큰이 없다, 제한 시간 안에 답이 없다, 200 이 아니다, 답을 읽지 못한다, 예외가 났다 | 정해 둔 글로 막는다 |

hook 은 등록 이름이 대응 파일의 `tools` 에 있는 서버를 먼저 고르고, 없을 때만 `prefix` 가 맞는 서버를 고른다. `prefix` 가 여럿 맞으면 가장 긴 것을 고른다.
서버 이름이 다른 서버 이름의 앞부분일 때 원래 도구 이름을 엉뚱한 서버에서 찾지 않게 한다.

| 항목 | 값 |
| --- | --- |
| 주소 | 위 「운영 값」 의 커넥터 정책 주소. 운영이 준다 |
| 인증 | 그 profile 의 MCP 토큰 |
| 제한 시간 | 3초. 한 번만 부르고 다시 부르지 않는다. 기다리는 동안 run 의 스레드가 묶인다 |
| 로그 | 상태 코드나 예외 종류만 남긴다. 토큰, 서명, 인자, 응답 본문은 남기지 않는다 |

막을 때는 늘 글이 든 `block` 을 돌려준다. Hermes 는 글이 없는 `block` 을 통과로 읽는다. 예외의 본문을 글에 넣지 않는다.
`hermes/tests/test_fos_ctx.py` 가 서버 쪽 검사와 같은 서명 확인 값으로 plugin 을 검사한다.

Hermes 가 보이는 도구 이름은 `mcp__fos_assistant__<도구>` 다. 서버 이름의 `-` 가 `_` 로 바뀐다.
서명에는 앞부분을 뗀 서버 쪽 이름을 넣는다. 서버 이름을 바꾸면 plugin 의 `TOOL_PREFIX` 도 함께 바꾼다.

**서명은 그 profile 의 모델이 셸로 파일을 읽지 못하는 동안만 위조를 막는다.**
key 는 그 profile `.env` 의 MCP 토큰에서 나오고, terminal 도구는 Hermes 프로세스의 사용자가 읽을 수 있는 파일을 모두 읽는다.
셸을 여는 profile 의 목록은 운영 저장소의 live 검사가 소유한다.
그 목록에 Control Plane MCP 를 등록한 profile 을 더할 때는 이 제약을 함께 판단한다.

## dashboard-profile-api 가 여는 것

인증은 모두 `Authorization: Bearer <대시보드 서비스 토큰>` 이고 없거나 틀리면 401 이다.
Control Plane 이 이 경로들을 부르는 순서와 뜻은 부르는 쪽 문서가 갖는다. 커넥터 경로는 [커넥터 설치](../docs/backend/connector-install.md) 의 「대시보드 plugin 계약」 이, 에이전트 만들기는 [에이전트](../docs/backend/agent.md) 가 갖는다.

| 요청 | 쓰임 | 본문이나 query | 성공 | 실패 |
| --- | --- | --- | --- | --- |
| `GET /api/profiles` | 이름이 이미 있는지 본다 | 없음 | 200 | |
| `POST /api/profiles` | profile 을 만들고 같은 요청 안에서 설정 틀과 서명 plugin 과 관리 표식을 둔다 | `name`, `no_skills`, `description` 셋을 받고 다른 키는 거절한다. Control Plane 은 `{name, no_skills: true}` 를 보낸다 | 200. 이때 안전한 도구 목록, Control Plane MCP 등록(토큰 값 없음), 서명 plugin 켜짐, 관리 표식이 모두 있다 | 400 이름 규칙, 이미 있는 이름(Hermes 는 409 가 아니라 400), 허용하지 않는 키. 500 틀 적용 실패(만든 것은 지웠다) |
| `DELETE /api/profiles/<이름>` | 관리 표식이 있는 profile 을 지운다 | 없음 | 200 | 401 관리 표식이 없는 profile. 404 없는 profile. 두 번째 호출의 404 는 「이미 지움」 으로 읽는다 |
| `PUT /api/env` | 그 profile 의 `.env` 에 정해 둔 key 한 줄을 쓴다 | `{profile, key, value}`. key 는 `API_SERVER_KEY`, `API_SERVER_MODEL_NAME`, `MCP_FOS_ASSISTANT_API_KEY` 와 카탈로그 커넥터의 `fields[].env` 뿐 | 200. 커넥터 key 는 `{profile, key, restart_required}` | 400 다른 key, `default`, 형식. 404 없는 profile |
| `DELETE /api/env` | 관리 profile 의 커넥터 칸 key 만 지운다 | `{profile, key}` | `{profile, key, restart_required}` | |
| `GET /api/connectors/catalog` | 운영 목록에 있고 검증을 통과한 커넥터의 manifest 를 낸다 | 없음 | `[{id, schema, title, description, fields[], verify, mcp_server, toolsets, attachments, tools}]`. 아래 「카탈로그 응답」 이 칸을 갖는다 | |
| `POST /api/connectors/<id>/call` | 후보 값으로 그 커넥터의 선택지 도구나 확인 도구를 한 번 부른다 | `{tool, values}` | `{ok: true, result}` 또는 `{ok: false, error: <공통 어휘>}` | |
| `POST /api/connectors/<id>/execute` | Control Plane 이 승인한 호출을 그 profile 의 값과 받은 인자로 한 번 실행한다 | `{profile, hermes_tool, args}` | `{ok: true, result}` 또는 `{ok: false, error: <공통 어휘>}` | 504 실행됐는지 모른다 |
| `GET /api/connectors?profile=<p>` | 커넥터의 상태를 읽는다 | query `profile` | `{profile, policy_hook, connectors: [{plugin, enabled, configured}]}` | |
| `PUT /api/connectors` | 관리 profile 에 커넥터를 설치하고 제거한다 | `{profile, plugin, enabled}` | `{profile, plugin, enabled, changed, restart_required, plugin_updated}`. 설치는 서버 등록과 함께 그 profile 의 API 도구 목록과 `SOUL.md` 를 다시 쓴다 | |
| `POST /api/mcp/servers/<서버>/test?profile=<p>` | 그 profile 에 설치한 커넥터의 MCP 서버만 probe 한다 | 없음 | `{ok, tools: [{name}]}` | |
| `GET /api/tools/toolsets` | 도구 이름과 설명을 읽는다 | 없음 | 200 | |
| `PUT /api/config` (도구) | 지정한 profile 의 API 도구 목록을 쓴다 | `{profile, config: {platform_toolsets: {api_server: [...]}}}` | 200 | [ADR-029](../docs/adr/ADR-029-에이전트-도구는-control-plane-이-등급으로-판정하고-hermes-설정-api-로-쓴다.md) 그대로 |
| `PUT /api/config` (스킬 게시) | 지정한 profile 의 올린 스킬 경로를 쓴다 | `{profile, config: {skills: {external_dirs: [<Hermes 쪽 스킬 루트>/<profile>/<버전>]}}}`. 버전 이름은 `v[0-9]{13}-[a-z0-9]{4}` 다(`v` 뒤에 UTC 밀리초 13자리와 소문자 영숫자 4자). 목록은 0개나 1개. 0개는 게시 해제. 도구 목록을 같은 본문에 둘 수 있다 | 200 | 400 경로 형식, 다른 profile 의 prefix, 둘 이상, 심볼릭 링크, 없는 디렉터리, `skills` 도구가 꺼진 채 게시. 409 운영자가 넣은 다른 외부 경로가 있다. 404 없는 profile |
| `GET /api/skills?profile=<p>` | 지정한 profile 의 스킬 목록을 읽는다 | query `profile` 하나 | 200 `[{name, description, category, enabled, usage, provenance}]`. `enabled` 는 전역 `skills.disabled` 만 반영 | 400 query 누락, 둘 이상, `default`. 404 |
| `PUT /api/skills/toggle` | 지정한 profile 의 스킬 하나를 켜고 끈다 | `{profile, name, enabled}` | 200 `{ok, name, enabled}` | 400, 404 |
| `GET PUT /api/profiles/<이름>/soul` | 그 profile 의 SOUL.md 를 읽고 쓴다 | `PUT` 은 `{content}` | 200 | |
| `GET /api/profiles/<이름>/model-defaults` | 그 profile 설정의 `provider`, `model`, `reasoningEffort` 세 값만 읽는다. 기본 profile 도 읽는다 | 없음 | 200 `{provider, model, reasoningEffort}`. 없는 값은 `null`. 설정 전체와 비밀값은 반환하지 않는다. [모델 단계와 실행 기록](../docs/model-tiers.md) 의 「profile 기본 강도」 를 따른다 | 400 이름 형식. 404 없는 profile. 503 설정을 읽지 못함 |
| `GET /api/profiles/<이름>/sessions/<session id>/provider` | 그 profile 의 자식 session 한 줄에서 provider 와 모델만 읽는다. 기본 profile 도 읽는다 | 없음 | 200 `{provider, model}`. 아래 「자식 session 의 provider」 가 칸을 갖는다 | 400 이름이나 session 번호 형식. 404 없는 profile, 없는 session, 자식이 아닌 session. 503 저장소를 읽지 못함 |

표의 「그 profile」 은 있고 `default` 가 아닌 이름이다.
기본 profile 은 400 이고 없는 profile 은 404 다. `model-defaults` 와 자식 session 의 provider 경로는 기본 profile 도 받는다.
본문과 query 에 profile 이 둘 다 있으면 같아야 한다.

### 자식 session 의 provider

API server 의 session 응답은 provider 를 주지 않는다. 그 값은 Hermes 의 session 저장소에만 있다.
이 경로는 그 값을 Control Plane 에 읽어 준다. 근거는 [ADR-067](../docs/adr/ADR-067-native-하위-에이전트의-provider-는-대시보드-plugin-이-session-저장소에서-읽어-준다.md) 에,
저장소의 어느 칸이 무엇을 뜻하는지는 [`../docs/hermes/delegation.md`](../docs/hermes/delegation.md) 의 「자식 session 의 provider 는 저장소에만 있다」 에 있다.

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

**카탈로그 응답.** `fields[]` 는 manifest 의 칸 그대로(`env`, `options` 포함)이고 `verify` 는 `{tool}` 이다.
`toolsets` 와 `attachments` 는 manifest 에 없으면 빈 목록과 거짓이다. 옛 대시보드 plugin 은 두 칸을 내지 않고, Control Plane 은 없는 칸을 같은 기본값으로 읽는다.
`tools` 는 `{<이름>: {risk, approval, title, grant, outbound}}` 이고 `approval`, `grant`, `outbound` 는 기본값을 채운 값이다. `schema: 1` 은 `verify.tool` 과 `options.tool` 만 `READ` 로 담는다.
`schema` 가 없는 응답은 `1` 로 읽는다. `operator_env` 의 이름과 값, `errors` 는 담지 않는다.

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

**`PUT /api/env` 는 [plugin 의 허용 목록](plugins/dashboard-profile-api/__init__.py) 에 있는 key 만 받는다.**
기본 key 는 plugin 이 갖고, 커넥터 key 는 카탈로그 manifest 의 `fields[].env` 로 요청마다 계산한다.
본문은 `{profile, key, value}` 이고 값은 한 줄이어야 한다.
provider credential 은 이 토큰으로 쓰지 못한다.

### 커넥터

**plugin 은 커넥터의 이름을 코드에 두지 않는다.**
운영 목록의 plugin 디렉터리마다 `connector.json` 을 읽어 카탈로그로 내고, 선택지와 확인 도구를 대신 부른다.
`connector.json` 의 형식은 [커넥터 연결](../docs/connectors.md) 의 「connector.json」 이 소유한다. 커넥터 경로가 설치와 확인에서 뜻하는 것은 [커넥터 설치](../docs/backend/connector-install.md) 의 「대시보드 plugin 계약」 이 소유한다. 근거는 [ADR-043](../docs/adr/ADR-043-커넥터는-plugin-의-connector-json-으로-선언하고-control-plane-은-범용-흐름만-갖는다.md) 에 있다.

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

- 자식이 받는 환경 변수는 요청의 칸 값, 운영 목록의 `env`, MCP SDK 가 늘 더하는 기본 env(`HOME`, `LOGNAME`, `PATH`, `SHELL`, `TERM`, `USER`)뿐이다.
  대시보드 프로세스의 다른 env(서비스 토큰, 다른 커넥터의 값)는 넘어가지 않는다. `PATH` 는 대시보드의 값 대신 실행 파일이 있는 디렉터리만 준다
- 후보 값은 디스크, 응답, 로그에 남기지 않는다. 자식의 stderr 도 대시보드 로그로 보내지 않는다
- 도구는 인자 없이 부른다. 값은 환경 변수로만 간다
- profile 쓰기 잠금 밖에서 돈다. 도구를 기다리는 동안 다른 profile 요청이 멈추지 않는다
- 시간을 넘기거나 예외가 나면 SDK 의 정리 구간이 자식의 stdin 을 닫고, 끝나지 않으면 프로세스 묶음을 죽인 뒤에 응답한다
- `mcp` SDK 는 이 경로 안에서 import 한다. SDK 가 없으면 이 경로만 `unavailable` 이고 나머지는 그대로 돈다

**`POST /api/connectors/<id>/execute` 는 Control Plane 이 승인한 호출만 부른다. 대시보드는 승인 여부를 다시 확인하지 않는다.**

- 그 profile 에 관리 표식과 그 커넥터의 소유 기록이 있어야 한다. 자식의 env 는 그 profile `.env` 의 칸 값과 운영 목록의 `env` 다
- 인자와 결과를 로그에 싣지 않는다
- 실행되지 않은 것이 분명한 실패는 `{ok: false}` 로, 시간 초과와 도구 호출을 보낸 뒤의 실패는 504 로 답한다. 504 는 실행됐는지 모른다는 뜻이다
- 도구가 `errors` 표에서 `outcome_unknown` 인 코드로 실패해도 504 로 답한다. `call` 에서는 그 코드를 `unavailable` 로 돌려준다
- 요청과 응답은 [커넥터 연결](../docs/connectors.md) 의 「승인」 이 소유한다. 근거는 [ADR-050](../docs/adr/ADR-050-커넥터-쓰기는-control-plane-이-승인-줄을-저장하고-승인한-인자로-한-번만-실행한다.md) 에 있다

**소유 기록 `.fos-connectors.json` 은 설치할 때의 서버 정의를 갖고, 요청마다 지금의 manifest 와 같은지 검증한다.**

| 상태 | 설치한 적 없는 profile | 이미 설치한 profile |
| --- | --- | --- |
| 운영 목록에서 빠졌다 | 상태 조회에 나오지 않고 설치는 400 이다. 제거는 끌 것이 없어 `changed: false` 로 성공한다 | 상태 조회는 `configured: false` 다. 제거는 되고 probe 는 404 다 |
| 실행 파일을 받지 못했다 | 설치가 503 이다 | 상태 조회, 제거, probe 가 모두 503 이다 |
| 설치한 뒤 실행 파일, plugin 경로, 실행 정의의 command·args, 운영자 env 값이 바뀌었다 | 해당 없음 | 상태 조회, 제거, probe 가 모두 503 이다 |

운영 목록에서 빠진 커넥터를 제거하면 plugin 이 그 기록의 서버 env 가 `${이름}` 으로 참조하던 key 를 profile `.env` 에서 함께 지운다. Control Plane 이 그 이름을 더는 알 수 없기 때문이다.
값이 바뀌어 503 이 된 profile 은 값을 되돌리면 다시 읽힌다.
**운영은 환경 변수를 먼저 준 뒤 plugin 을 올린다.** 값을 바꿔야 하면 바꾸기 전에 설치한 커넥터를 제거한다.

설치와 제거는 Hermes 등록 이름과 원래 도구 이름의 대응을 그 profile 의 `.fos-connector-tools.json` 에 다시 쓰고, 설치는 `approval: always` 인 도구를 서버 정의의 `tools.exclude` 에 넣고 profile 의 `fos-ctx` 를 묶음의 판으로 맞춘 뒤 파일이 바뀌었는지를 `plugin_updated` 로 답한다.
`GET /api/connectors` 는 `policy_hook` 을 함께 낸다. `fos-ctx` 가 켜져 있고 묶음의 판과 같고 대응 파일과 `tools.exclude` 가 지금 manifest 와 맞을 때만 참이며, 조건은 [커넥터 도구 정책](../docs/backend/connector-tool-policy.md) 의 「hook 이 켜져 있는지」 가 갖는다.

**한 배포 동안 옛 Control Plane 의 호출과 옛 소유 기록의 필드 모양·운영자 env 표현을 받는다**([ADR-041](../docs/adr/ADR-041-hermes-에-설치하는-plugin-과-profile-틀은-이-저장소가-소유한다.md)). 실행 정의의 command·args 가 지금 manifest 와 다른 기록까지 받는 것은 아니다.

- 옛 기록은 운영자 env 를 `${이름}` 참조로 갖고 MCP 서버 이름 칸이 없다. 운영자 env 는 그 참조와 지금의 직접 값을 같다고 본다. 다음 설치 요청이 기록을 새 모양으로 다시 쓴다
- 운영자 env 이름의 `PUT /api/env` 와 `DELETE /api/env` 는 성공으로 답하고 아무것도 쓰지 않는다

**토큰으로 부른 `PUT /api/config` 는 본문을 검사한 뒤 Hermes 처리기에 넘긴다.**
최상위에는 `profile` 과 `config` 만 둔다.
`config` 에는 `platform_toolsets.api_server` 와 `skills.external_dirs` 가운데 하나나 둘을 둔다.

도구 목록은 이렇게 본다.

- `agent` 키는 받지 않는다. `agent.disabled_toolsets` 는 아래 「켠 API 도구는 `disabled_toolsets` 에서 뺀다」 대로 plugin 이 고친다
- 목록의 이름은 Hermes toolset 또는 그 profile 에 등록된 MCP 서버여야 한다
- `api_server` 에는 `memory` 를 넣지 않고 Control Plane MCP `fos-assistant` 를 남긴다
- Control Plane MCP 가 아직 등록되지 않은 profile 은 그 이름과 알려진 내장 toolset 을 함께 넣는다
- Hermes 가 계산한 실제 API 도구에 요청 목록 밖의 이름이 있으면 저장하지 않는다
- 다른 platform 의 계산 결과가 바뀌어도 저장하지 않는다

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
