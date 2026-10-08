# 도구와 스킬

## 도구와 스킬과 승인 설정을 HTTP 로 쓰는 길

### 대시보드 설정 API

대시보드 웹서버는 `_profile_scope(profile)` 안에서 profile 의 설정을 읽고 쓴다.
아래 경로는 API server 의 `/v1/runs` 와 다른 서버가 처리한다.

| 경로 | 동작 | 근거 함수 |
| --- | --- | --- |
| `GET /api/config/raw` | `config.yaml` 원문을 읽는다 | `hermes_cli/web_server.py` 의 `get_config_raw` |
| `GET /api/config` | 환경 변수 참조를 펼친 설정을 읽는다 | 같은 파일의 `get_config`, `load_config` |
| `PUT /api/config` | 받은 값을 디스크 원문에 병합한다. 쓸 수 있는 키를 제한하지 않는다 | 같은 파일의 `update_config`, `hermes_cli/config.py` 의 `_deep_merge` |
| `PUT /api/config/raw` | 받은 YAML 로 설정 파일 전체를 바꾼다 | `update_config_raw` |
| `GET /api/tools/toolsets` | toolset 의 이름, 설명, 도구 목록을 읽는다 | `hermes_cli/web_routers/tools.py` 의 `get_toolsets` |
| `PUT /api/tools/toolsets/{name}` | 보통 `platform_toolsets.cli` 를 바꾼다. Discord 전용은 `discord` 에 저장한다 | 같은 파일의 `toggle_toolset`, `_toolset_configuration_platform` |
| `GET /api/skills` | 스킬 목록, 켜짐 여부와 출처를 읽는다 | `hermes_cli/web_routers/skills.py` 의 `get_skills` |
| `PUT /api/skills/toggle` | `skills.disabled` 에 이름을 넣거나 뺀다 | 같은 파일의 `toggle_skill`, `save_disabled_skills` |
| `POST /api/skills`, `PUT /api/skills/content` | profile 로컬 스킬을 만들거나 본문을 바꾼다 | 같은 파일의 `create_skill`, `update_skill_content` |
| `/api/mcp/servers` 계열 | `mcp_servers` 를 조회, 추가하고 `enabled` 를 바꾼다 | `hermes_cli/web_routers/mcp.py` |

`PUT /api/config` 는 객체를 재귀로 병합하고 목록은 받은 값으로 통째로 바꾼다.
따라서 `platform_toolsets.api_server`, `agent.disabled_toolsets`, `skills.external_dirs` 를
각각 원하는 목록으로 쓸 수 있다.
병합의 바탕은 `read_raw_config()` 의 원문이라 `${VAR}` 참조는 그대로 남는다.
반면 `GET /api/config` 는 `_expand_env_vars` 가 펼친 비밀값도 응답에 싣는다.
기계용 설정 조회에는 `GET /api/config/raw` 를 쓰되 원문도 민감한 설정으로 취급한다.

**대시보드 toolset 토글은 API 실행의 도구를 바꾸지 않는다.**
API 실행은 `platform_toolsets.api_server` 를 읽는다.
`GET /api/tools/toolsets` 는 목록 원본으로 쓸 수 있지만, 토글 응답의 `enabled` 는 CLI 기준이다.
스킬 토글의 `skills.disabled` 는 그 profile 의 모든 platform 에 적용된다.
platform 별 `skills.platform_disabled.<platform>` 은 이 토글 경로로 쓰지 않는다.
필수 스킬 `hermes-agent` 는 `agent/skill_utils.py` 의 `ESSENTIAL_SKILLS` 로 보호되어 끌 수 없다.

기계용 인증은 [「profile 을 HTTP 로 만드는 길」](profiles.md#profile-을-http-로-만드는-길) 의 token provider 를 쓴다.
`register_token_route` 는 메서드를 보지 않고 경로 문자열만 맞춘다.
본문이나 query 로 profile 을 고르는 설정 경로를 열면 모든 profile 에 열린다.
`PUT /api/config` 에서 `model`, `approvals`, `mcp_servers`, `terminal`, `memory` 등의
키를 골라 허용하는 기능은 Hermes 에 없다.
plugin 이 본문을 먼저 읽은 뒤 처리기가 다시 읽는 방식은 v0.21.3 에서 동작한다. [「설정 API와 profile 경계」](#설정-api와-profile-경계) 를 본다.

근거는 [v0.21.0 web_server.py](https://github.com/NousResearch/hermes-agent/blob/v2026.8.31/hermes_cli/web_server.py),
[tools router](https://github.com/NousResearch/hermes-agent/blob/v2026.8.31/hermes_cli/web_routers/tools.py),
[skills router](https://github.com/NousResearch/hermes-agent/blob/v2026.8.31/hermes_cli/web_routers/skills.py) 다.

### 변경이 적용되는 시점

| 바꾸는 것 | 적용 시점 | 근거 |
| --- | --- | --- |
| `platform_toolsets.api_server`, `agent.disabled_toolsets` | 다음 실행. 재시작이 필요 없다 | `_create_agent` 가 실행마다 새 agent 를 만들고 `_load_gateway_config` 가 profile 설정을 읽는다 |
| 도구 정의 | 다음 실행 | `model_tools.get_tool_definitions` 캐시 키에 toolset 목록과 설정 파일 지문이 들어 있다 |
| `skills.external_dirs`, `skills.disabled` | 다음 실행 | 외부 경로 캐시가 config mtime 을 보고 스킬 색인 캐시 키에 경로와 비활성화 목록이 들어 있다 |
| 연결된 디렉터리 안의 스킬 추가·삭제, 색인 설명 변경 | gateway 재시작 뒤 | `agent/prompt_builder.py` 의 `_SKILLS_PROMPT_CACHE` 키에 디렉터리 내용이 없다 |
| 대시보드로 스킬 생성·수정 | gateway 색인은 재시작 뒤 | `create_skill` 이 비우는 캐시는 대시보드 프로세스에만 있다 |
| `SKILL.md` 본문 | 다음 `skill_view`. 재시작이 필요 없다 | `skill_view` 가 파일을 직접 읽는다 |
| `mcp_servers` 추가 | gateway 재시작 뒤 | `_discover_gateway_mcp_tools` 가 기동 때 도구를 발견한다 |
| 이미 연결된 MCP 서버의 허용 목록 | 다음 실행 | `_get_platform_tools` 가 실행마다 교집합을 만든다 |
| `approvals.mode`, `deny`, `timeout`, `cron_mode`, `unattended_mode`, `single_query_mode` | 다음 명령 판정 | `tools/approval.py` 의 읽기 함수가 `load_config_readonly()` 를 부른다 |
| `delegation.subagent_auto_approve` | 다음 위임 | `tools/delegate_tool.py` 의 `_get_subagent_approval_callback` |
| `command_allowlist` | 실행마다 다시 읽지 않는다 | `load_permanent_allowlist` 가 import 때 `_permanent_approved` 를 만든다 |

진행 중인 실행은 이미 만든 agent 의 도구 목록을 계속 쓴다.
`approvals.deny` 는 mode 와 yolo 판정보다 먼저 검사한다.
`approvals.mode` 를 바꾸며 보내는 `session.info` 는 대시보드 자기 profile 의 대화에만 간다.
API 경로에서 새 승인 설정을 읽는 것과는 별개다.

공유 listener 의 요청 문맥은 `gateway/run.py` 의 `_profile_runtime_scope` 가 만들고,
contextvar 를 통해 작업 스레드로 전달된다.
도구·스킬·승인 설정은 요청 profile 의 파일을 읽지만 다음 항목은 프로세스가 공유한다.

| 공유 항목 | 영향 |
| --- | --- |
| `command_allowlist` | listener 주인 profile 의 값이 모든 profile 에 적용된다. 보조 profile 의 값은 읽히지 않는다 |
| MCP 연결 | 기동 때 한 번 연결하고 profile 별 허용 목록과 교집합을 만든다 |
| 스킬 색인 캐시 | profile 별 키를 쓰지만 디렉터리 내용 변경은 알아채지 못한다 |
| gateway 재시작 | 그 listener 가 제공하는 모든 profile 의 실행에 영향을 준다 |

### toolset 의 목록과 접근 범위

v0.21.0 의 `toolsets.py` 의 `TOOLSETS` 와
`hermes_cli/tools_config.py` 의 `CONFIGURABLE_TOOLSETS` 를 대조한 결과다.
**profile 분리는 셸이나 파일 도구의 파일 접근을 격리하지 않는다.**
terminal backend 가 `local` 이면 이 도구는 같은 컨테이너 안의 다른 profile 파일에도 닿는다.
`terminal`, `file`, `code_execution` 을 docker 실행 공간으로 옮기는 계약과 남는 구멍은 [실행 공간](sandbox.md) 이 갖는다.

| toolset | 대표 도구 | 접근 범위 | 설정 목록 |
| --- | --- | --- | --- |
| `terminal` | `terminal`, `process_manage` | 셸이 닿는 파일, 네트워크와 프로세스 | 있다 |
| `file` | `read_file`, `write_file`, `patch`, `search_files` | 컨테이너 파일 시스템 읽기·쓰기 | 있다 |
| `code_execution` | `execute_code` | Python 에서 열린 다른 도구를 호출한다 | 있다 |
| `browser` | `browser_navigate`, `browser_exec`, `web_search` 등 | 브라우저 조작, 페이지 스크립트와 외부 웹 | 있다 |
| `computer_use` | `computer_use` | 데스크톱 화면과 입력 | 있다 |
| `web`, `x_search` | `web_search`, `web_extract`, `x_search` | 외부 검색·추출 API. `x_search` 는 기본 꺼짐 | 있다 |
| `memory`, `session_search` | 같은 이름의 도구 | profile 기억 읽기·쓰기와 지난 대화 검색 | 있다 |
| `skills` | `skills_list`, `skill_view`, `skill_manage` | 스킬 읽기·쓰기. 외부 스킬도 쓰기 가능하면 수정할 수 있다 | 있다 |
| `cronjob`, `delegation` | `cronjob_manage`, `delegate_task` | 무인 예약 실행과 자식 agent 생성 | 있다 |
| `image_gen`, `video_gen` | `image_generate`, `video_generate`, `xai_video_edit`, `xai_video_extend` | 외부 유료 생성 API. `video_gen` 은 기본 꺼짐 | 있다 |
| `tts`, `stt` | `text_to_speech`. `stt` 는 도구가 없다 | 음성 합성·인식 API. 인식은 `stt.enabled` 로 켠다 | 있다 |
| `vision`, `video` | `vision_analyze`, `video_analyze` | 이미지·영상 분석. `video` 는 기본 꺼짐 | 있다 |
| `todo`, `clarify` | `todo_list`, `clarify` | 실행 안의 할 일과 사용자 질문 | 있다 |
| `context_engine` | 엔진이 정한다 | 기본 엔진이 아닐 때 도구가 생긴다 | 있다 |
| `homeassistant`, `spotify` | `ha_*`, `spotify_*` | 스마트홈 기기와 Spotify 계정. 기본 꺼짐 | 있다 |
| `discord`, `discord_admin` | 같은 이름의 도구 | Discord 읽기·참여·관리. 기본 꺼짐, Discord 전용 | 있다 |
| `yuanbao` | `yb_*` | Yuanbao 메시지 | 있다 |
| `kanban` | `kanban_*` | 디스패처가 띄운 작업자의 작업판 | 없다 |
| `a2a`, `google_meet` | plugin 이 정한다 | plugin toolset. `a2a` 는 기본 꺼짐 | 없다 |
| `project`, `desktop_ui`, `feishu_*`, `bot_room` | GUI 또는 해당 platform 전용 | API 경로와 관계없다 | 없다 |

`_get_platform_tools` 는 모든 활성화 규칙을 적용한 뒤 `agent.disabled_toolsets` 를 뺀다.
없는 비활성화 이름은 집합에서 빠질 것이 없어 오류 없이 무시된다.
허용 목록의 잘못된 이름은 목록 전체가 무효일 때만 마지막 검사에서 알린다.

`platform_toolsets.api_server` 에 이름을 명시해도 그 목록만 켜지는 것은 아니다.

- `_enable_recently_shipped_toolsets` 가 새 toolset 을 더할 수 있다. v0.21.0 의 추가 목록은 비어 있다.
  `known_builtin_toolsets` 에 적힌 이름은 이미 확인하고 거절한 것으로 본다.
- `known_plugin_toolsets` 에 없는 plugin toolset 은 기본 꺼짐 목록을 제외하고 켜진다.
- MCP 서버 이름을 하나도 지정하지 않으면 등록된 MCP 서버가 모두 통과한다.
  전부 막으려면 `no_mcp` 를 둔다.

허용 목록 키를 없애면 `hermes-api-server` 복합 toolset 을 쓴다.
v0.21.0 에서 기본 꺼짐 목록을 뺀 결과는 다음과 같다.

```text
browser, code_execution, cronjob, delegation, file, image_gen, memory,
session_search, skills, terminal, todo, vision, web
```

managed scope 의 `apply_managed_overlay` 는 설정 leaf 마다 사용자 값보다 우선한다.
목록은 통째로 바뀌고 managed scope 는 프로세스에 하나다.
여기에 `agent.disabled_toolsets` 를 두면 모든 profile 에 같은 목록이 적용되어
profile 별 목록을 대신한다.

근거는 [v0.21.0 tools_config.py](https://github.com/NousResearch/hermes-agent/blob/v2026.8.31/hermes_cli/tools_config.py),
[toolsets.py](https://github.com/NousResearch/hermes-agent/blob/v2026.8.31/toolsets.py),
[managed_scope.py](https://github.com/NousResearch/hermes-agent/blob/v2026.8.31/hermes_cli/managed_scope.py) 다.

### 설정 API와 profile 경계

`hermes_cli.web_routers.config_env.update_config`는 `ConfigUpdate`의 `config`를 기존 원문에 재귀 병합한다.
목록은 받은 목록으로 바뀌며, `model`, `approvals`, `mcp_servers` 같은 다른 키도 막지 않는다.
본문에 `profile`이 있으면 query의 `profile`보다 먼저 선택한다.
`_profile_scope`는 선택한 profile의 설정과 스킬 경로를 가리키지만 요청자가 그 profile의 주인인지는 판단하지 않는다.
`token_auth_middleware`도 등록된 경로의 토큰만 인증하고 본문을 제한하지 않는다.
근거는 [설정 처리기](https://github.com/NousResearch/hermes-agent/blob/v2026.9.14/hermes_cli/web_routers/config_env.py), [profile 범위](https://github.com/NousResearch/hermes-agent/blob/v2026.9.14/hermes_cli/web_server_profiles.py), [토큰 인증](https://github.com/NousResearch/hermes-agent/blob/v2026.9.14/hermes_cli/dashboard_auth/token_auth.py)이다.

plugin 미들웨어가 `await request.json()`으로 본문을 먼저 읽어도 처리기는 같은 본문을 다시 읽어 저장한다.
따라서 plugin은 JSON 객체의 최상위 키와 `config` 아래 키를 정확히 검사하고, query와 본문의 profile이 모두 토큰에 묶인 대상과 같은지 확인해야 한다.

현재와 같은 단일 서비스 토큰에는 profile 신원이 들어 있지 않다.
그 토큰으로 요청한 서로 다른 사용자 사이의 profile 경계를 plugin만으로 증명하려면 profile별 토큰 또는 서버가 검증하는 profile 신원값이 추가로 필요하다.
Control Plane이 주인을 검사해 정확한 profile만 보낼 수는 있지만, 공유 토큰 자체가 유출되면 다른 profile을 지정할 수 있다.
`GET /api/config`는 환경 변수 참조를 펼친 설정을 돌려줄 수 있으므로 도구 화면의 조회 경로로 열지 않는다.

도구 선택값을 저장할 때는 제품이 허용한 이름만 받아 목록 전체를 계산하고, `memory`를 항상 제거해야 한다.
Control Plane MCP(`fos-assistant`)의 서버 이름은 API 허용 목록에 계속 남겨야 한다. 연결을 붙인 에이전트의 profile 도 같고, 그 목록에는 붙인 커넥터의 서버 이름이 함께 있다. 도구 저장은 그 이름을 함께 보내고, 대시보드 plugin 은 그 이름이 빠진 목록을 거절한다([`../backend/connector-install.md`](../backend/connector-install.md) 의 「바인딩 설치」).
남아 있는 옛 커넥터 에이전트의 profile 은 예외다. 그 목록은 설치한 커넥터의 MCP 서버 이름과 그 커넥터의 manifest 가 선언한 읽기 전용 이미지 도구(`vision`)만 갖고 대시보드 plugin 의 설치가 쓴다([ADR-045](../adr/ADR-045-커넥터-에이전트는-자기-mcp-서버만-받고-memory-와-control-plane-도구를-받지-않는다.md)).
등록된 MCP 서버 이름이 하나도 없는 목록은 모든 활성 MCP 서버를 통과시킬 수 있으므로, 알 수 없는 이름만 남은 목록을 허용해서는 안 된다.
`_get_platform_tools`로 저장 뒤 실제 목록을 계산해 허용 목록과 대조한다.
새 plugin toolset은 저장 목록에 없어도 자동으로 켜질 수 있고, `agent.disabled_toolsets`는 마지막에 적용되므로 둘 다 확인해야 한다.
근거는 [도구 설정 계산](https://github.com/NousResearch/hermes-agent/blob/v2026.9.14/hermes_cli/tools_config.py)이다.

### 도구 목록 조회와 버전 차이

`GET /api/tools/toolsets`는 `name`, `label`, `description`, `tools`, `enabled`, `configured`, `platform`을 돌려준다.
격리 컨테이너에서 29개 항목을 받았다.
`enabled`는 `_toolset_configuration_platform`을 따른다. 대부분의 도구에서는 `cli`라 API 실행의 켜짐 상태와 다를 수 있다.
v0.21.3의 API server는 별도 `GET /v1/toolsets`를 제공하며 같은 설명과 도구 목록에 **`api_server` 기준 `enabled`**를 붙인다.
**두 경로의 응답 모양이 다르다.** 대시보드 경로는 항목 배열을 그대로 돌려주고, `GET /v1/toolsets`는 `{"object": "list", "platform": "api_server", "data": [...]}`로 감싼다.
이 목록에는 MCP 서버 이름이 없다. v0.21.3의 실제 응답 29개 항목에도 기억 MCP 이름이 없었고, 이 경로의 구현도 내장 도구 목록을 반환한다.
따라서 Control Plane MCP 서버 `fos-assistant`는 설정에 넣되, 저장 뒤 이 경로로 다시 읽은 결과와 비교하지 않는다.
근거는 [대시보드 도구 경로](https://github.com/NousResearch/hermes-agent/blob/v2026.9.14/hermes_cli/web_routers/tools.py), [API server 도구 경로](https://github.com/NousResearch/hermes-agent/blob/v2026.9.14/gateway/platforms/api_server.py)다.

v0.21.0과 비교하면 v0.21.3의 설정 가능한 목록에 `connections`와 `kanban`이 들어왔다.
`kanban`은 기본 꺼짐이다.
아무 목록도 저장하지 않은 API server의 기본 계산에는 v0.21.0의 목록에 없던 `connections`가 들어간다.
새 profile에 허용 목록 키를 빠뜨리면 도구가 넓게 열리는 이유다.
근거는 [v0.21.0 도구 목록](https://github.com/NousResearch/hermes-agent/blob/v2026.8.31/hermes_cli/tools_config.py), [v0.21.3 도구 목록](https://github.com/NousResearch/hermes-agent/blob/v2026.9.14/hermes_cli/tools_config.py), [v0.21.3 릴리스](https://github.com/NousResearch/hermes-agent/releases/tag/v2026.9.14)다.

### 지난 대화 검색의 범위

`session_search` 는 profile 하나에 갇히지 않는다.

| 호출 | 읽는 범위 |
| --- | --- |
| `profile` 인자 없음 | 지금 profile 의 `state.db` 에 있는 모든 대화. API, Discord, CLI 를 가리지 않고, 그룹 공개 에이전트로 다른 사용자가 나눈 대화도 들어 있다 |
| `profile` 인자 있음 | 그 이름의 profile 의 `state.db` 를 읽기 전용으로 연다. profile 이 있는지만 확인한다 |

`kanban`, `subagent`, `tool` 에서 시작한 세션만 목록에서 뺀다.
근거는 [v0.21.3 `tools/session_search_tool.py`](https://github.com/NousResearch/hermes-agent/blob/v2026.9.14/tools/session_search_tool.py) 의 `_resolve_profile_db`, `_dispatch` 와 도구 설명의 `profile` 항목이다.
그래서 이 도구는 관리자 등급이고 켜진 에이전트는 비공개로만 둔다([ADR-029](../adr/ADR-029-에이전트-도구는-control-plane-이-등급으로-판정하고-hermes-설정-api-로-쓴다.md)).

### 스킬 파일과 색인 적용 시점

대시보드의 `POST /api/skills`와 `PUT /api/skills/content`는 `SKILL.md` 하나만 만들거나 바꾼다.
생성에는 YAML frontmatter의 `name`, `description`, 비어 있지 않은 본문이 필요하다.
스킬 이름과 범주 이름은 최대 64자이며 소문자, 숫자, 점, 밑줄, 붙임표를 쓴다.
새 스킬의 설명은 색인 예산에 맞춰 60자 이하여야 하고 `SKILL.md`는 최대 100,000자다.

v0.21.5 의 `tools/skill_manager_tool.py` 의 `_validate_frontmatter(content, new_skill)` 를 읽고 확인한 검사다.

| 검사 | 언제 | 세는 법 |
| --- | --- | --- |
| 설명 60자(`SKILL_PROMPT_DESC_LIMIT`) | 새 스킬(`create`)만. 고칠 때는 보지 않는다 | `len(desc.strip().strip("'\""))`. 앞뒤 공백과 앞뒤 따옴표를 뺀 Python 문자 수, 곧 code point 수 |
| 설명 1024자(`MAX_DESCRIPTION_LENGTH`) | 늘 | 앞뒤를 빼지 않은 문자 수 |
| 앞머리 뒤 본문 | 늘 | 닫는 `---` 줄 뒤가 공백뿐이면 「SKILL.md must have content after the frontmatter」 로 거절한다 |

이름 64자(`MAX_NAME_LENGTH`)와 설명 1024자는 `tools/skills_tool_plugin.py` 에도 같은 값으로 있다.
색인은 `agent/skill_utils.py` 의 `extract_skill_description` 이 같은 방법으로 앞뒤를 뺀 설명을 60자에서 잘라 57자에 `...` 을 붙인다.
Control Plane 이 저장 규칙을 이 검사에 맞추는 까닭은 [ADR-034](../adr/ADR-034-올린-스킬은-control-plane-이-버전-디렉터리에-쓰고-hermes-는-읽기만-한다.md) 의 「저장할 수 있는 스킬은 Hermes 가 제대로 고를 수 있는 스킬이다」 에 있다.
근거는 [v0.21.5 skill_manager_tool.py](https://github.com/NousResearch/hermes-agent/blob/v2026.9.24/tools/skill_manager_tool.py), [skill_utils.py](https://github.com/NousResearch/hermes-agent/blob/v2026.9.24/agent/skill_utils.py), [skills_tool_plugin.py](https://github.com/NousResearch/hermes-agent/blob/v2026.9.24/tools/skills_tool_plugin.py) 다.
이 두 HTTP 경로에는 `references/`, `templates/`, `assets/`, `scripts/` 파일 인자가 없다.
`skill_manage(write_file)`는 해당 네 하위 디렉터리의 텍스트 파일을 다루며 파일당 1 MiB와 100,000자 제한이 있지만, 대시보드 HTTP 경로로 노출되지 않았다.
근거는 [스킬 HTTP 경로](https://github.com/NousResearch/hermes-agent/blob/v2026.9.14/hermes_cli/web_routers/skills.py), [스킬 쓰기와 검증](https://github.com/NousResearch/hermes-agent/blob/v2026.9.14/tools/skill_manager_tool.py)이다.

`DELETE /api/skills`는 없다. 스킬을 빼려면 `skills.external_dirs`에서 경로를 뺀다.

대시보드가 스킬을 만들면 자기 프로세스의 색인 캐시만 비운다.
공유 gateway의 `build_skills_system_prompt` 메모리 캐시 키에는 스킬 디렉터리 경로와 비활성화 목록은 있지만 디렉터리 내용은 없다.
격리 컨테이너에서 별도 프로세스의 `POST /api/skills`가 200으로 파일을 만든 뒤에도 이미 색인을 계산한 프로세스의 다음 색인에는 새 스킬이 없었다.
별도 프로세스의 `PUT /api/skills/content`로 설명을 고쳐도 기존 설명이 남았다.
반면 `skills.external_dirs`에 새로운 버전 경로를 더하자 같은 프로세스의 다음 색인에 새 스킬이 나타났다.
`PUT /api/skills/toggle`로 `skills.disabled`를 바꾸면 캐시 키가 달라져 다음 색인에서 빠졌다.
`/reload-skills`는 명령 목록을 다시 훑지만 시스템 프롬프트 캐시는 비우지 않는다.
근거는 [색인 캐시](https://github.com/NousResearch/hermes-agent/blob/v2026.9.14/agent/prompt_builder.py), [스킬 경로 계산](https://github.com/NousResearch/hermes-agent/blob/v2026.9.14/agent/skill_utils.py), [다시 읽기 명령](https://github.com/NousResearch/hermes-agent/blob/v2026.9.14/gateway/slash_commands.py)이다.

따라서 스킬 묶음은 profile 전용의 새 버전 디렉터리에 전부 올린 뒤 검사하고, 마지막 `PUT /api/config`에서 해당 디렉터리만 `skills.external_dirs`에 게시하는 편이 적용 시점을 확실하게 만든다.
수정도 기존 경로를 덮어쓰지 않고 새 버전을 게시한다.
profile 로컬 스킬이 같은 이름이면 외부 스킬보다 먼저 선택되므로 이 경로와 `POST /api/skills`를 같은 이름에 섞지 않는다.
실행 중인 요청은 이미 만든 프롬프트를 계속 쓸 수 있다.
`skill_view`는 파일을 직접 읽지만 색인 갱신을 대신하지 않는다.

### profile 생성과 Control Plane MCP

`POST /api/profiles`가 받는 이름은 정규화 뒤 `[a-z0-9][a-z0-9_-]{0,63}`에 맞아야 하며 예약 이름은 거절한다.
생성 함수에는 profile 개수 상한이 없다. 서비스 자체의 자원 한도는 별도로 정해야 한다.
`no_skills: true`는 번들 스킬 심기를 건너뛰고, clone 옵션과 함께 쓸 수 없다.
clone은 설정과 `.env`를 복사하므로 사용자별 profile을 만들 때 쓰지 않는다.
`API_SERVER_KEY`를 `PUT /api/env`로 넣으면 공유 listener는 다음 요청부터 새 key를 읽는다.
v0.21.3 공유 gateway는 profile 추가를 감지해 adapter를 추가하며, 새 profile에는 MCP 발견도 시도한다.
근거는 [profile 생성](https://github.com/NousResearch/hermes-agent/blob/v2026.9.14/hermes_cli/profiles.py), [생성 HTTP 경로](https://github.com/NousResearch/hermes-agent/blob/v2026.9.14/hermes_cli/web_routers/profiles.py), [공유 gateway의 profile 감지](https://github.com/NousResearch/hermes-agent/blob/v2026.9.14/gateway/run_profile_reconcile.py)다.

생성 HTTP 본문에는 `mcp_servers`도 있으나, 생성 처리기는 profile을 게시하고 gateway에 알린 다음 MCP 설정을 best effort로 쓴다.
MCP 설정 쓰기 실패가 profile 생성 실패로 바뀌지 않는다.
따라서 이 인자만으로 새 profile의 Control Plane MCP 연결을 원자적으로 보장할 수 없다.
기존 profile의 `POST /api/mcp/servers`도 설정 저장이지 gateway 연결 완료가 아니다.
공유 gateway에는 profile 대화의 `/reload-mcp`가 있으며 재시작 없이 MCP를 다시 발견하지만, 기본적으로 확인 절차를 거치고 이 기능을 직접 호출하는 전용 HTTP 경로는 찾지 못했다.
새 profile의 자동 발견 또는 이 명령을 쓸 수 없는 경우에는 공유 gateway 재시작이 확실한 적용 경로다.
근거는 [MCP 설정 API](https://github.com/NousResearch/hermes-agent/blob/v2026.9.14/hermes_cli/web_routers/mcp.py), [MCP 재발견](https://github.com/NousResearch/hermes-agent/blob/v2026.9.14/gateway/run_turn.py)이다.

공유 gateway의 housekeeping은 60초마다 served profile마다 `mcp_servers`의 이름과 살아 있는 연결을 맞춘다.
새 이름은 연결하고 빠진 이름은 끊는다. 이름만 비교하므로 같은 이름의 정의나 env 값이 바뀐 것은 다시 연결하지 않는다.
스킬 색인 캐시 키에는 profile 스킬 디렉터리의 내용이 없고 `skills.disabled`가 들어 있다.
그래서 바인딩 설치는 스킬 파일을 바꿀 때 `skills.disabled`의 색인 표식(`fos-skill-index-` 앞머리)을 새 값으로 바꿔 그 profile의 다음 실행이 색인을 새로 만들게 한다.
판정과 근거는 [ADR-20261007 / connector-live-reload](../adr/ADR-20261007-connector-live-reload.md)가 갖는다.
