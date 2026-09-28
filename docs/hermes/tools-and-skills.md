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
plugin 이 본문을 먼저 읽은 뒤 처리기가 다시 읽는 방식은 이 조사에서 검증하지 않았다.

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

## Memory MCP

제목만 `instructions` 에 실린 Memory 본문은 Control Plane 의 MCP 서버에서 읽는다.

| 항목 | 계약 |
| --- | --- |
| 서버 이름 | `fos-assistant-memory` |
| 경로 | `/mcp` |
| 프로토콜 | Streamable HTTP `2025-03-26` |
| 인증 | profile마다 다른 Bearer 토큰 |
| 도구 | `memory_read` |

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
