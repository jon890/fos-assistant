# Hermes 쪽 코드

Control Plane 이 기대는 Hermes 쪽 코드다. Hermes 에 설치하는 plugin 두 개와 새 profile 의 설정 틀을 둔다.
이 저장소가 이것을 갖는 근거는 [ADR-041](../docs/adr/ADR-041-hermes-에-설치하는-plugin-과-profile-틀은-이-저장소가-소유한다.md) 에 있다.

| plugin | 하는 일 | 두는 곳 | 읽는 프로세스 |
| --- | --- | --- | --- |
| `dashboard-profile-api` | 대시보드의 profile 관리와 도구 목록 경로를 `Authorization: Bearer` 로 연다 | Hermes 기본 루트의 `plugins/` | 대시보드 |
| `fos-ctx` | Control Plane MCP 호출 인자에 서명한 run 맥락 `_fos_ctx` 를 덮어쓰고, `skill_manage` 를 막고, 자식 session 을 등록한다 | Control Plane MCP 를 등록한 profile 마다 | gateway |

## 설치 묶음

```bash
# cwd: 저장소 root
hermes/bundle.sh --out <디렉터리> --mcp-url <Control Plane MCP 주소>
```

`--out` 은 없거나 비어 있는 디렉터리여야 하고, `--mcp-url` 은 `http://` 나 `https://` 로 시작해야 한다.
묶음은 Hermes 의 `plugins/dashboard-profile-api/` 자리에 그대로 들어갈 모양이다.
묶음의 모양과 묶음을 Hermes 에 넣는 쪽은 [`../docs/code-architecture.md`](../docs/code-architecture.md) 의 「Hermes 쪽 코드 (`hermes/`)」 절이 갖는다.

## 운영 값

운영 값은 코드에 두지 않고 설치할 때 받는다.
각 값의 모양과 없을 때의 동작은 [`../docs/code-architecture.md`](../docs/code-architecture.md) 의 「Hermes 쪽 코드 (`hermes/`)」 절에 있는 표가 소유한다.

| 값 | 받는 곳 |
| --- | --- |
| Control Plane MCP 주소 | `bundle.sh --mcp-url` |
| 커넥터 plugin 경로 | `FOS_ASSISTANT_CONNECTOR_ROOTS` |
| 커넥터 실행 파일 | `FOS_ASSISTANT_CONNECTOR_COMMAND` |
| 대시보드 서비스 토큰 | `HERMES_DASHBOARD_PROFILE_API_SECRET` |
| 스킬 루트 | `FOS_ASSISTANT_SKILL_AGENT_ROOT` |

## 검사

```bash
# cwd: 저장소 root
python3 -m unittest discover -s hermes/tests
```

Hermes 모듈은 가짜로 끼우므로 Hermes 를 설치하지 않아도 돈다. 실제 Hermes 와 맞는지는 운영 저장소의 live 검사가 본다.

## fos-ctx 가 붙이는 것

Control Plane 의 `agent_*` 도구는 이 값으로 부모 실행을 찾고, 서명이 없거나 틀리면 거절한다.
키와 서명 규칙은 [`../docs/hermes/delegation.md`](../docs/hermes/delegation.md) 의 「`_fos_ctx` 계약」 이 소유한다.
`hermes/tests/test_fos_ctx.py` 가 그 절의 기대 서명으로 plugin 을 검사한다.

| 도구 | 서명하지 못할 때 |
| --- | --- |
| `agent_*`, `memory_read`, `artifact_write` | 막는다. 모델에게 막은 이유가 간다 |
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
Control Plane 은 부모 run 으로 자식의 요청자를 찾지 못하므로, fos-ctx 가 `subagent_start` hook 에서 자식 session 의 부모와 뿌리를 등록한다.
경로, 본문, 서명, 응답은 [`../docs/hermes/delegation.md`](../docs/hermes/delegation.md) 「하위 에이전트 session 등록 계약」 이 소유한다.

| 항목 | 값 |
| --- | --- |
| 주소 | gateway 프로세스의 환경 변수 `FOS_CTX_SUBAGENT_URL`. 운영이 준다. 없으면 등록하지 않는다 |
| 인증 | 그 profile 의 MCP 토큰 |
| 제한 시간 | 3초. 연결 실패와 5xx 에만 한 번 더 부른다 |
| 실패 | 로그만 남긴다. 등록이 없는 자식의 호출은 Control Plane 이 거절한다 |

Hermes 는 자식을 만드는 자리에서 부모 스레드로 이 hook 을 동기로 부른다. 그래서 등록이 자식의 첫 도구 호출보다 먼저 끝난다.
일회용 컨테이너에서 부모 run 이 0.25초에 끝나고 등록이 0.12초에 도착했다.
등록 로그는 `fos-ctx: 자식 session 을 등록했다 (시도 N)` 이고, 실패하면 상태 코드나 예외 종류만 남는다.

Hermes 가 보이는 도구 이름은 `mcp__fos_assistant__<도구>` 다. 서버 이름의 `-` 가 `_` 로 바뀐다.
서명에는 앞부분을 뗀 서버 쪽 이름을 넣는다. 서버 이름을 바꾸면 plugin 의 `TOOL_PREFIX` 도 함께 바꾼다.

**서명은 그 profile 의 모델이 셸로 파일을 읽지 못하는 동안만 위조를 막는다.**
key 는 그 profile `.env` 의 MCP 토큰에서 나오고, terminal 도구는 Hermes 프로세스의 사용자가 읽을 수 있는 파일을 모두 읽는다.
셸을 여는 profile 의 목록은 운영 저장소의 live 검사가 소유한다.
그 목록에 Control Plane MCP 를 등록한 profile 을 더할 때는 이 제약을 함께 판단한다.

## dashboard-profile-api 가 여는 것

| 요청 | 쓰임 |
| --- | --- |
| `GET /api/profiles` | 이름이 이미 있는지 본다 |
| `POST /api/profiles` | profile 을 만들고 같은 요청 안에서 설정 틀과 서명 plugin 과 관리 표식을 둔다 |
| `DELETE /api/profiles/<이름>` | 관리 표식이 있는 profile 을 지운다 |
| `PUT /api/env` | 그 profile 의 `.env` 에 정해 둔 key 한 줄을 쓴다 |
| `DELETE /api/env` | 관리 profile 의 가계부 key 만 지운다 |
| `GET PUT /api/connectors` | 알려진 connector 의 상태를 읽거나 관리 profile 에 설치하고 제거한다 |
| `POST /api/mcp/servers/accountbook/test` | 설치한 가계부 MCP 서버만 probe 한다 |
| `GET /api/tools/toolsets` | 도구 이름과 설명을 읽는다 |
| `PUT /api/config` | 지정한 profile 의 API 도구 목록과 올린 스킬 경로를 쓴다 |
| `GET /api/skills` | 지정한 profile 의 스킬 목록을 읽는다 |
| `PUT /api/skills/toggle` | 지정한 profile 의 스킬 하나를 켜고 끈다 |
| `GET PUT /api/profiles/<이름>/soul` | 그 profile 의 SOUL.md 를 읽고 쓴다 |

모든 요청에서 기본 profile 은 400 이고 없는 profile 은 404 다.
본문과 query 에 profile 이 둘 다 있으면 같아야 한다.

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
가계부 key 는 [가계부 연결](../docs/connectors.md) 과 운영 저장소의 커넥터 운영 문서를 따른다.
본문은 `{profile, key, value}` 이고 값은 한 줄이어야 한다.
provider credential 은 이 토큰으로 쓰지 못한다.

**커넥터는 경로와 실행 파일을 환경 변수로 받는다.**
`GET PUT /api/connectors` 와 `POST /api/mcp/servers/accountbook/test` 가 다루는 커넥터는 요청에서 이름만 받는다.
그 이름의 plugin 디렉터리는 `FOS_ASSISTANT_CONNECTOR_ROOTS` 에서, MCP 서버를 실행할 파일은 `FOS_ASSISTANT_CONNECTOR_COMMAND` 에서 읽는다.
둘 가운데 하나라도 없거나 읽지 못하면 대시보드는 그대로 뜨고 커넥터만 쓸 수 없다.
경로를 받지 못하면 알려진 커넥터가 하나도 없어 설치 요청이 400 이고, 실행 파일을 받지 못하면 설치와 probe 가 503 이다.
profile 의 소유 기록에는 설치할 때의 실행 파일 경로가 남는다. 값을 바꾸면 이미 설치한 profile 의 기록이 검증에서 거절된다.

**토큰으로 부른 `PUT /api/config` 는 본문을 검사한 뒤 Hermes 처리기에 넘긴다.**
최상위에는 `profile` 과 `config` 만 둔다.
`config` 에는 `platform_toolsets.api_server` 와 `skills.external_dirs` 가운데 하나나 둘을 둔다.

도구 목록은 이렇게 본다.

- `agent` 키는 받지 않으므로 기존 `agent.disabled_toolsets` 가 모든 platform 에 그대로 적용된다
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
`DELETE /api/env` 는 관리 profile 의 가계부 key 만 받는다.
