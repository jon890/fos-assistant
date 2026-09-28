# 도구와 스킬

## 도구와 스킬과 승인 설정을 HTTP 로 쓰는 길

2026-09-28 에 v0.21.0 소스로 확인했다.
도구 목록과 스킬 입력 크기는 함수에 설정을 넣어 계산한 결과다.
설정 변경 뒤 실제 실행으로 적용 시점을 검증한 것은 아니다.

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
이 도구를 주면 같은 컨테이너 안의 다른 profile 파일에도 닿을 수 있다.

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

## v0.21.3 에서 확인한 쓰기 경로

2026-09-28 에 v0.21.3 코드와 네트워크가 없는 일회용 컨테이너로 확인했다. 에이전트 도구 선택과 스킬 올리기가 이 경로에 기댄다.

### 설정 API와 profile 경계

`hermes_cli.web_routers.config_env.update_config`는 `ConfigUpdate`의 `config`를 기존 원문에 재귀 병합한다.
목록은 받은 목록으로 바뀌며, `model`, `approvals`, `mcp_servers` 같은 다른 키도 막지 않는다.
본문에 `profile`이 있으면 query의 `profile`보다 먼저 선택한다.
`_profile_scope`는 선택한 profile의 설정과 스킬 경로를 가리키지만 요청자가 그 profile의 주인인지는 판단하지 않는다.
`token_auth_middleware`도 등록된 경로의 토큰만 인증하고 본문을 제한하지 않는다.
근거는 [설정 처리기](https://github.com/NousResearch/hermes-agent/blob/v2026.9.14/hermes_cli/web_routers/config_env.py), [profile 범위](https://github.com/NousResearch/hermes-agent/blob/v2026.9.14/hermes_cli/web_server_profiles.py), [토큰 인증](https://github.com/NousResearch/hermes-agent/blob/v2026.9.14/hermes_cli/dashboard_auth/token_auth.py)이다.

일회성 컨테이너에서 미들웨어가 `await request.json()`으로 본문을 읽고 같은 요청을 `update_config`에 넘겼다.
설정은 200으로 저장됐다.
`?profile=alpha`와 본문 `profile=beta`를 함께 보냈을 때 `beta`의 설정이 바뀌었고 `alpha`는 바뀌지 않았다.
따라서 plugin은 JSON 객체의 최상위 키와 `config` 아래 키를 정확히 검사하고, query와 본문의 profile이 모두 토큰에 묶인 대상과 같은지 확인해야 한다.
본문을 읽는 것 자체는 이 버전의 FastAPI 경로에서 처리기의 재읽기를 막지 않았다.

현재와 같은 단일 서비스 토큰에는 profile 신원이 들어 있지 않다.
그 토큰으로 요청한 서로 다른 사용자 사이의 profile 경계를 plugin만으로 증명하려면 profile별 토큰 또는 서버가 검증하는 profile 신원값이 추가로 필요하다.
Control Plane이 주인을 검사해 정확한 profile만 보낼 수는 있지만, 공유 토큰 자체가 유출되면 다른 profile을 지정할 수 있다.
`GET /api/config`는 환경 변수 참조를 펼친 설정을 돌려줄 수 있으므로 도구 화면의 조회 경로로 열지 않는다.

도구 선택값을 저장할 때는 제품이 허용한 이름만 받아 목록 전체를 계산하고, `memory`를 항상 제거해야 한다.
기억 MCP 서버 이름은 API 허용 목록에 계속 남겨야 한다.
등록된 MCP 서버 이름이 하나도 없는 목록은 모든 활성 MCP 서버를 통과시킬 수 있으므로, 알 수 없는 이름만 남은 목록을 허용해서는 안 된다.
`_get_platform_tools`로 저장 뒤 실제 목록을 계산해 허용 목록과 대조한다.
새 plugin toolset은 저장 목록에 없어도 자동으로 켜질 수 있고, `agent.disabled_toolsets`는 마지막에 적용되므로 둘 다 확인해야 한다.
근거는 [도구 설정 계산](https://github.com/NousResearch/hermes-agent/blob/v2026.9.14/hermes_cli/tools_config.py)이다.

### 도구 목록 조회와 버전 차이

`GET /api/tools/toolsets`는 `name`, `label`, `description`, `tools`, `enabled`, `configured`, `platform`을 돌려준다.
격리 컨테이너에서 29개 항목을 받았다.
`enabled`는 `_toolset_configuration_platform`을 따른다. 대부분의 도구에서는 `cli`라 API 실행의 켜짐 상태와 다를 수 있다.
v0.21.3의 API server는 별도 `GET /v1/toolsets`를 제공하며 같은 설명과 도구 목록에 **`api_server` 기준 `enabled`**를 붙인다.
근거는 [대시보드 도구 경로](https://github.com/NousResearch/hermes-agent/blob/v2026.9.14/hermes_cli/web_routers/tools.py), [API server 도구 경로](https://github.com/NousResearch/hermes-agent/blob/v2026.9.14/gateway/platforms/api_server.py)다.

v0.21.0과 비교하면 v0.21.3의 설정 가능한 목록에 `connections`와 `kanban`이 들어왔다.
`kanban`은 기본 꺼짐이다.
아무 목록도 저장하지 않은 API server의 기본 계산에는 v0.21.0의 목록에 없던 `connections`가 들어간다.
새 profile에 허용 목록 키를 빠뜨리면 도구가 넓게 열리는 이유다.
근거는 [v0.21.0 도구 목록](https://github.com/NousResearch/hermes-agent/blob/v2026.8.31/hermes_cli/tools_config.py), [v0.21.3 도구 목록](https://github.com/NousResearch/hermes-agent/blob/v2026.9.14/hermes_cli/tools_config.py), [v0.21.3 릴리스](https://github.com/NousResearch/hermes-agent/releases/tag/v2026.9.14)다.

### 스킬 파일과 색인 적용 시점

대시보드의 `POST /api/skills`와 `PUT /api/skills/content`는 `SKILL.md` 하나만 만들거나 바꾼다.
생성에는 YAML frontmatter의 `name`, `description`, 비어 있지 않은 본문이 필요하다.
스킬 이름과 범주 이름은 최대 64자이며 소문자, 숫자, 점, 밑줄, 붙임표를 쓴다.
새 스킬의 설명은 색인 예산에 맞춰 60자 이하여야 하고 `SKILL.md`는 최대 100,000자다.
이 두 HTTP 경로에는 `references/`, `templates/`, `assets/`, `scripts/` 파일 인자가 없다.
`skill_manage(write_file)`는 해당 네 하위 디렉터리의 텍스트 파일을 다루며 파일당 1 MiB와 100,000자 제한이 있지만, 대시보드 HTTP 경로로 노출되지 않았다.
근거는 [스킬 HTTP 경로](https://github.com/NousResearch/hermes-agent/blob/v2026.9.14/hermes_cli/web_routers/skills.py), [스킬 쓰기와 검증](https://github.com/NousResearch/hermes-agent/blob/v2026.9.14/tools/skill_manager_tool.py)이다.

공식 `POST /api/files/upload`는 data URL로 파일 하나를 올리고, `POST /api/files/upload-stream`은 multipart로 올린다.
둘 다 하위 디렉터리를 만들 수 있고 개별 파일의 Hermes 한도는 100 MiB다.
이 경로 자체는 스킬 전용도 profile 전용도 아니므로 plugin에서 대상 경로를 profile의 승인된 업로드 디렉터리로 제한해야 한다.
업로드 경로를 분리해도 같은 컨테이너에서 `file`이나 `terminal` 도구가 다른 경로를 읽을 수 있으면 파일 자체는 profile 간에 격리되지 않는다.
제품에는 더 작은 파일 및 전체 묶음 한도를 둘 수 있다.
`DELETE /api/skills`는 없으므로 삭제는 `skills.external_dirs`에서 경로를 빼고 파일 경로를 제한한 `DELETE /api/files`로 정리하는 흐름이 필요하다.
근거는 [파일 HTTP 경로](https://github.com/NousResearch/hermes-agent/blob/v2026.9.14/hermes_cli/web_routers/files.py), [파일 루트 제한](https://github.com/NousResearch/hermes-agent/blob/v2026.9.14/hermes_cli/web_server_files.py)이다.

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

### profile 생성과 Memory MCP

`POST /api/profiles`가 받는 이름은 정규화 뒤 `[a-z0-9][a-z0-9_-]{0,63}`에 맞아야 하며 예약 이름은 거절한다.
생성 함수에는 profile 개수 상한이 없다. 서비스 자체의 자원 한도는 별도로 정해야 한다.
`no_skills: true`는 번들 스킬 심기를 건너뛰고, clone 옵션과 함께 쓸 수 없다.
clone은 설정과 `.env`를 복사하므로 사용자별 profile을 만들 때 쓰지 않는다.
`API_SERVER_KEY`를 `PUT /api/env`로 넣으면 공유 listener는 다음 요청부터 새 key를 읽는다.
v0.21.3 공유 gateway는 profile 추가를 감지해 adapter를 추가하며, 새 profile에는 MCP 발견도 시도한다.
근거는 [profile 생성](https://github.com/NousResearch/hermes-agent/blob/v2026.9.14/hermes_cli/profiles.py), [생성 HTTP 경로](https://github.com/NousResearch/hermes-agent/blob/v2026.9.14/hermes_cli/web_routers/profiles.py), [공유 gateway의 profile 감지](https://github.com/NousResearch/hermes-agent/blob/v2026.9.14/gateway/run_profile_reconcile.py)다.

생성 HTTP 본문에는 `mcp_servers`도 있으나, 생성 처리기는 profile을 게시하고 gateway에 알린 다음 MCP 설정을 best effort로 쓴다.
MCP 설정 쓰기 실패가 profile 생성 실패로 바뀌지 않는다.
따라서 이 인자만으로 새 profile의 Memory MCP 연결을 원자적으로 보장할 수 없다.
기존 profile의 `POST /api/mcp/servers`도 설정 저장이지 gateway 연결 완료가 아니다.
공유 gateway에는 profile 대화의 `/reload-mcp`가 있으며 재시작 없이 MCP를 다시 발견하지만, 기본적으로 확인 절차를 거치고 이 기능을 직접 호출하는 전용 HTTP 경로는 찾지 못했다.
새 profile의 자동 발견 또는 이 명령을 쓸 수 없는 경우에는 공유 gateway 재시작이 확실한 적용 경로다.
근거는 [MCP 설정 API](https://github.com/NousResearch/hermes-agent/blob/v2026.9.14/hermes_cli/web_routers/mcp.py), [MCP 재발견](https://github.com/NousResearch/hermes-agent/blob/v2026.9.14/gateway/run_turn.py)이다.

v0.21.3에서는 공유 gateway의 MCP 연결이 profile 범위의 키를 사용한다.
같은 서버 이름을 두 profile이 써도 서로 다른 credential의 연결을 구분하도록 고쳤다.
이는 [profile별 공유 MCP 가시성 수정](https://github.com/NousResearch/hermes-agent/pull/106314)과 [동일 이름 연결 분리 수정](https://github.com/NousResearch/hermes-agent/pull/108352)에 해당한다.
설정 파일 분리만 믿지 말고, 새 profile의 실제 API 실행에 Memory 도구가 나타나는지 검증해야 한다.

### 검증 범위

격리 실험은 운영 이미지와 같은 이미지 ID의 일회성 컨테이너에서 `--network none`으로 실행했다.
FastAPI의 실제 설정, 도구, 스킬 처리기를 사용했으며 실험 파일은 컨테이너와 함께 없어졌다.
본문 재읽기, profile 우선순위, 도구 목록 29개, 스킬 생성·수정·비활성화와 색인 경로 변경을 확인했다.
공유 gateway의 MCP 자동 발견과 새 profile의 실제 대화 실행은 이 조사에서 구동하지 않았으므로 코드 판정으로 구분했다.

## Memory MCP

제목만 `instructions` 에 실린 Memory 본문은 Control Plane 의 MCP 서버에서 읽는다.

| 항목 | 계약 |
| --- | --- |
| 서버 이름 | `fos-assistant-memory` |
| 경로 | `/mcp` |
| 프로토콜 | Streamable HTTP `2025-03-26` |
| 인증 | profile마다 다른 Bearer 토큰 |
| 도구 | `memory_read`, `artifact_write` |

토큰이 요청자를 정한다. 요청 본문에 사용자 번호를 넣어도 사용자를 바꿀 수 없다.
Control Plane 은 그 사용자가 볼 수 있고 승인됐으며 항상 주입하지 않는 항목만 응답한다.
실제 MCP 서버 등록과 토큰 전달은 비공개 저장소 `fos-home-infra`가 맡는다.

### API server 에서 MCP 도구를 여는 범위

`platform_toolsets.api_server` 의 `no_mcp` 는 API server 로 들어온 실행에서 MCP 도구를 통째로 막는다.
`no_mcp` 대신 서버 이름을 허용 목록으로 적으면 그 서버의 도구만 모델에 전달하고 나머지는 막는다.

도구가 없던 profile 에 MCP 서버를 처음 열면 Hermes 가 `tool_search`, `tool_describe`, `tool_call` 중계를 함께 싣는다.
도구 정의가 약 59 토큰이어도 중계 때문에 입력이 약 1,800 토큰 늘 수 있다.
이미 이 중계를 쓰는 profile 은 MCP 서버를 더해도 서버의 도구 정의만큼만 늘어난다.

### 입력 비용은 API 콜 수가 정한다

실행의 입력 토큰은 provider 에 보낸 모든 API 콜의 입력을 더한 값이다.
추가 API 콜 하나는 그 시점의 전체 프롬프트 하나만큼 들기 때문에 대화가 길수록 도구 호출도 비싸진다.

도구를 부르지 않는 턴은 API 콜이 한 번이고, `memory_read` 를 부른 턴은 두 번이나 세 번이었다.
한 턴에서 항목을 한 개 읽든 세 개를 병렬로 읽든 API 콜 수는 같았다.
항목 수보다 도구를 부르는 턴 수가 입력 비용을 정한다.

Memory 색인 한 줄은 약 12 토큰이고, `always_inject` 본문은 한 글자당 약 0.49 토큰이다.
`always_inject` 는 API 콜 수를 늘리지 않는다.
Control Plane 이 두 방식을 고르는 기준은
[`flow.md`](../flow.md#도구와-always_inject-를-고르는-기준)에 둔다.

## 결과물 쓰기 도구

`artifact_write` 는 일반 파일 도구가 없는 profile 에 결과물 저장만 연다.
`fos-assistant-memory` 서버가 등록된 profile 에서만 보인다. 기존 서버에 도구를 추가하므로 Hermes 서버 등록과 허용 목록을 바꾸지 않는다.
실행 입력은 이 도구가 있으면 MCP 로 저장하고, 도구가 없고 파일 도구가 있으면 대화 폴더에 직접 쓰도록 안내한다.
결정은 [ADR-028](../adr/ADR-028-결과물은-사용자의-대화-폴더에-mcp-도구로-쓴다.md) 에 있다.

| 인자 | 계약 |
| --- | --- |
| `conversation_id` | 필수 UUID 문자열. `Conversation.publicId` 를 가리킨다. 내부 번호를 받지 않는다 |
| `path` | 필수 상대 경로. 대화 폴더 밖을 가리키지 않는다 |
| `content` | 선택 문자열. UTF-8 로 저장한다. `html`, `css` 만 받는다 |
| `source_url` | 선택 HTTPS URL. `png`, `jpg`, `jpeg`, `gif`, `webp` 만 받는다 |

`content` 와 `source_url` 은 정확히 하나만 있어야 한다.
`content` 는 빈 문자열도 파일 본문으로 인정한다. `source_url` 은 빈 값을 받지 않는다.
선택 인자에 `null` 을 넣거나 두 방식의 확장자를 섞으면 인자 오류다.
base64 인자와 정의하지 않은 인자는 거절한다.
`path` 는 빈 값, 절대 경로, 역슬래시, NUL, 빈 경로 조각과 `.` 또는 `..` 조각을 거절한다.
확장자의 대소문자는 가리지 않는다.
공통 상한은 **5MB(5 × 1024 × 1024 바이트)** 이고 본문은 문자 수 대신 UTF-8 바이트 수로 센다.
경로는 기존 `chat_artifact.path` 에 맞춰 500자까지 받는다.

토큰의 사용자로 `ConversationAccess.requireOwn(CurrentUser, UUID)` 를 호출한다.
확인 전에는 폴더 생성과 URL 조회를 하지 않는다.
없는 대화, 지운 대화, 다른 사용자의 대화는 같은 `isError: true` 결과로 숨긴다.
같은 사용자의 다른 대화는 허용한다.
도구 입력에 사용자 번호나 profile 을 넣어 요청자를 바꿀 수 없다.
실행 연결값을 받지 않는 이유는 [도구 호출에는 실행을 가리키는 값이 없다](delegation.md#도구-호출에는-실행을-가리키는-값이-없다) 절이 갖는다.

성공은 기존 MCP 결과의 `content[0].text` 에 JSON 문자열을 담아 반환한다.
그 JSON 은 `{"path":"test/index.html","byteSize":123}` 모양이며 `isError` 는 `false` 다.
호스트 경로와 내부 대화 번호는 반환하지 않는다.
잘못된 인자는 JSON-RPC `-32602`, 모르는 도구는 `-32601` 로 답한다.
인자 오류의 `error.data` 에는 확장자, 경로 형식, 크기 초과처럼 정해 둔 이유만 담고 입력 경로나 URL query 는 넣지 않는다.
주인 확인 실패, 파일 저장 실패, URL 방어와 다운로드 실패는 `isError: true` 로 답한다.
URL 의 query, 응답 본문, 파일시스템 경로를 오류나 로그에 노출하지 않는다.

같은 경로를 덮어쓸 때도 임시 파일을 완성한 뒤 교체한다.
상한 초과나 다운로드 실패는 임시 파일을 지우고 기존 파일을 보존한다.
HTML 을 답에 묶는 일은 쓰기 도구가 하지 않는다.
turn 끝의 `ArtifactService.recordTurn` 이 기존대로 바뀐 HTML 을 찾는다.

### 주소 방식과 SSRF 방어

| 항목 | 계약 |
| --- | --- |
| URL | `https` 만. userinfo, fragment, IP 리터럴을 거절한다. 포트는 생략하거나 HTTPS 표준 포트만 받는다 |
| 호스트 설정 | `assistant.artifact.source.allowed-hosts`. 소문자 ASCII 호스트의 정확한 일치만 허용한다. wildcard 와 접미사 일치를 쓰지 않는다 |
| 기본 허용 목록 | 빈 목록. 설정 전에는 모든 `source_url` 을 거절하고 `content` 는 허용한다 |
| DNS | A 와 AAAA 결과를 모두 검사한다. 사설, loopback, link-local, unspecified, multicast, IPv6 ULA 와 IPv4 를 담은 IPv6 의 비공개 주소를 거절한다 |
| 연결 | 검사한 IP 로 연결한다. 재시도도 검사한 주소만 쓰며 TLS 인증서, SNI 와 HTTP Host 는 원래 호스트를 쓴다 |
| HTTP | redirect 를 따라가지 않고 200 응답만 받는다. 서버의 인증 헤더와 쿠키를 보내지 않는다 |
| 형식 | `png` 는 `image/png`, `jpg` 와 `jpeg` 는 `image/jpeg`, `gif` 는 `image/gif`, `webp` 는 `image/webp`. MIME 의 매개변수는 제외하고 비교한다 |
| 크기 | `Content-Length` 가 상한보다 크면 읽기 전에 거절한다. 길이가 있으면 선언된 바이트만 읽고 조기 EOF 를 거절한다. 길이가 없으면 chunked 또는 연결 종료까지 읽되 5MB 를 넘는 순간 거절한다 |
| 제한 시간 | 연결 5초, 읽기 10초, DNS 를 포함한 호출 전체 30초. 느린 본문이 읽기 제한만 피해도 전체 제한으로 끝낸다 |

DNS 검사 뒤 원래 호스트 URL 을 일반 HTTP 클라이언트로 다시 부르는 구현은 쓰지 않는다.
클라이언트가 이름을 다시 풀면 검사한 IP 와 연결한 IP 가 달라질 수 있다.
연결 시점에도 IP 를 고정하고 원래 호스트 인증을 유지해야 한다.

Hermes v0.21.0 의 태그는 `v2026.8.31` 이다.
[해당 버전의 이미지 생성 소스](https://github.com/NousResearch/hermes-agent/blob/v2026.8.31/tools/image_generation_tool.py) 는
FAL 응답의 첫 이미지 URL 을 `success`, `image` 결과로 돌려준다.
소스는 출력 호스트를 고정하지 않는다.
[FAL 공식 응답 예시](https://fal.ai/models/fal-ai/flux/dev/api#output) 의 `images[].url` 은 빈 값이다.
이 근거로는 실제 출력 호스트를 확정할 수 없어 허용 목록의 기본값을 비워 둔다.
운영 호스트 확인과 설정은 `fos-home-infra` 에서 맡는다.

[같은 버전의 파일 안전 소스](https://github.com/NousResearch/hermes-agent/blob/v2026.8.31/agent/file_safety.py) 의
`get_safe_write_roots()` 는 `HERMES_WRITE_SAFE_ROOT` 를 프로세스 환경에서 읽는다.
이 값은 profile 별 쓰기 권한을 정하지 못한다.
읽기 거절 규칙도 결과물 폴더만 읽게 하는 경계가 아니다.

## 스킬을 profile 에 붙이는 방법

**정식 설정 키는 `skills.external_dirs` 다.**
`hermes_cli/config_defaults.py` 에 있고 기본값은 빈 목록이다.
`skill_paths` 와 `external_skill_paths` 라는 이름의 키는 없다.

스킬이 실리는 경로가 셋이다.

| 방법 | 내용 |
| --- | --- |
| `config.yaml` 의 `skills.external_dirs` | 디렉터리 목록을 그대로 읽는다 |
| `hermes skills trust <경로>` | 그 저장소의 `./.hermes/skills` 와 `./.agents/skills` 를 읽는다 |
| profile 의 `skills/` 에 심볼릭 링크 | 외부 디렉터리를 직접 가리킨다 |

스킬 설명이 매 대화마다 입력으로 함께 실린다.
그러므로 에이전트마다 그 에이전트가 쓰는 스킬만 붙인다.

### 스킬의 출처와 색인 입력

2026-09-28 에 v0.21.0 소스와 색인 계산으로 확인했다.

| 출처 | 연결·제외 단위 |
| --- | --- |
| profile 의 `skills/` | 범주 아래 스킬 디렉터리와 링크를 읽는다. `skills.disabled` 로 제외한다 |
| Hermes 번들 | profile 생성과 `tools/skills_sync.py` 의 기동 동기화가 심는다. `.no-bundled-skills` 는 일반 번들을 막지만 필수 스킬은 남긴다 |
| `skills.external_dirs` | 연결 디렉터리 아래 `SKILL.md` 를 읽는다. 경로를 빼거나 `skills.disabled` 로 제외한다 |
| 신뢰한 저장소 | `./.hermes/skills`, `./.agents/skills` 를 읽는다. 신뢰를 거두면 제외된다 |
| hub 설치 | 스킬 하나를 설치·제거하거나 비활성화한다 |

같은 이름이면 신뢰한 저장소, profile 로컬, 외부 디렉터리 순으로 앞의 것을 쓴다.
`agent/system_prompt.py` 는 실행 도구에 `skills_list`, `skill_view`, `skill_manage` 중
하나라도 있을 때만 `build_skills_system_prompt` 를 부른다.
**`skills` toolset 을 닫으면 스킬 파일이 있어도 색인이 입력에 들어가지 않는다.**

색인은 고정 안내문, 범주별 한 줄, 스킬별 이름과 설명 한 줄로 구성된다.
설명은 `SKILL_PROMPT_DESC_LIMIT` 인 60자에서 자른다.
본문은 `skill_view` 로 읽을 때 들어가고, 조건부 스킬은 `_skill_should_show` 가
도구와 platform 으로 거른다.

`o200k_base` 로 색인을 계산한 크기다. provider 의 토크나이저에 따라 달라질 수 있다.

| 설명 구성 | 스킬 수 | 전체 색인 | 스킬 한 줄 | 고정 부분 |
| --- | --- | --- | --- | --- |
| 한국어 위주 | 6 | 476 토큰 | 33~42 토큰 | 238 토큰 |
| 한국어·영어 혼합 | 7 | 490 토큰 | 18~44 토큰 | 292 토큰 |

스킬 하나는 API 호출 한 번의 입력에 대략 20~45 토큰을 더한다.
한국어 설명은 40 토큰 안팎이며 실행 안의 API 호출마다 다시 실린다.

**`skills` 는 읽기 전용 toolset 이 아니다.**
`skill_manage` 는 profile 로컬 스킬을 만들고 고친다.
외부 디렉터리가 쓰기 가능하면 사용자가 지시한 외부 스킬 수정도 가능하다.
`tools/skill_manager_tool.py` 의 외부 스킬 보호는 백그라운드 curator 의 쓰기만 막는다.

근거는 [v0.21.0 system_prompt.py](https://github.com/NousResearch/hermes-agent/blob/v2026.8.31/agent/system_prompt.py),
[skill_utils.py](https://github.com/NousResearch/hermes-agent/blob/v2026.8.31/agent/skill_utils.py),
[skill_manager_tool.py](https://github.com/NousResearch/hermes-agent/blob/v2026.8.31/tools/skill_manager_tool.py) 다.

### 스킬 수를 줄인 실측

**다만 스킬이 입력 비용의 대부분은 아니다.**
같은 문장을 `career` profile 에 보내 스킬 97개일 때와 6개일 때를 측정했다.

| 스킬 수 | 입력 토큰 |
| --- | --- |
| 97 | 14,949 |
| 6 | 12,388 |

줄어든 것이 2,561 토큰으로 17% 였다.

### 도구 정의가 더 크다

같은 방법으로 도구 범위를 바꿔 가며 측정한 것이다.
바이트는 `hermes prompt-size --platform api_server --json` 이 보고한 값이고,
이 명령은 API 를 부르지 않고 계산한다.

| 구성 | 도구 수 | 도구 정의 바이트 | 입력 토큰 |
| --- | --- | --- | --- |
| 전부 열림 | 18 | 31,147 | 12,388 |
| terminal·file·skills·memory·web | 14 | 20,364 | 10,180 |
| terminal·file·skills·memory | 12 | 18,454 | 9,792 |
| 전부 잠금 | 0 | 2 | 4,278 |

도구를 전부 잠그면 65.5% 가 줄어든다.
이 값은 도구 정의 JSON 만이 아니라 system prompt 의 도구 안내까지 함께 줄어든 결과다.
system prompt 가 22,799 글자에서 13,123 글자로 줄었다.

`career` 에서 실제로 뺄 수 있는 것은 2,596 토큰, 21% 다.
`career-os` 스킬이 `bun` 을 부르므로 `terminal` 이, 파일을 읽으므로 `file` 이 필요하다.
`sync-profile` 이 쓰는 브라우저는 Hermes 의 browser toolset 이 아니라
외부 스크립트이고 `terminal` 로 돌리므로 그 toolset 은 빼도 된다.

비용을 줄이려면 스킬만 보지 말고 도구 설정을 함께 본다.

### 실행 한 번의 비용은 이보다 훨씬 크다

위 숫자는 짧은 대화 한 번의 고정 비용이다.
실제 작업을 시키면 그 고정 비용이 턴마다 다시 실린다.

`career` 에 포지션 추천을 한 번 시킨 실측이다.

| 항목 | 값 |
| --- | --- |
| 입력 | 3,460,816 토큰 |
| 출력 | 15,301 토큰 |
| `gpt-5.6-sol` 공개 가격 환산 | 14.15달러 |

짧은 대화 한 번의 279배다. 그 실행은 활성 공고 122건을 비교했다.
`career` 의 `max_turns` 는 60 이다.

v0.21.0 과 v0.21.3 Runs API 의 `usage` 에는 cache 항목이 없다.
`input_tokens`, `output_tokens`, `total_tokens` 뿐이다.
`cached_input_tokens` 가 실행 기록에서 비어 있는 것은
prompt cache 가 붙지 않아서가 아니라 이 API 가 보고하지 않기 때문이다.

에이전트 정의 저장소의 스킬은 `.claude/skills` 에 있어 `trust` 가 보는 경로가 아니다.
그래서 심볼릭 링크를 쓴다. 구조는 `skills/<범주>/<스킬>` 이다.

```
~/.hermes/profiles/<profile>/skills/<범주>/<스킬>
  -> <컨테이너에 마운트된 에이전트 정의 저장소>/<범주>/.claude/skills/<스킬>
```

마운트 경로는 `fos-home-infra` 가 정한다.

링크 대상은 **컨테이너 안의 경로**여야 한다. 호스트 경로로 걸면 컨테이너 안에서 끊긴 링크가 된다.
붙인 뒤 gateway 를 다시 띄워야 인식된다. `GET /v1/skills` 로 확인한다.
실측으로 확인했다.
이미 연결한 스킬의 본문과 색인 변경은 「변경이 적용되는 시점」 에서 구분한다.
