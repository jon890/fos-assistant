# fos-ctx plugin

profile 에 설치하는 plugin 이다. MCP 호출에 실행 맥락을 서명해 붙이고, 하위 에이전트 session 을 Control Plane 에 등록하고, 커넥터 도구 호출을 Control Plane 에 묻는다.
설치와 운영 값은 [`hermes/README.md`](../../README.md), Hermes 쪽 동작은 [`hermes/docs/hermes-contract.md`](../../docs/hermes-contract.md) 가 갖는다.

## fos-ctx 가 붙이는 것

`delegate_task` 의 `images` 와 `tasks[].images` 는 HTTP(S) 주소와 이미지 data URL 만 받는다.
로컬 파일 경로는 Docker 밖에서 읽히므로 hook 이 파일 읽기 전에 막는다. 이미지 없는 자식 실행은 유지한다.
이 제한은 `fos-ctx` 1.4.0 이 켜진 profile 에 적용한다. 기존 일반 profile 은 파일 갱신과 gateway reload 를,
커넥터는 재설치를 끝낸 뒤 로컬 이미지 거절을 확인해야 한다. hook 이 꺼진 profile 은 막지 못한다.

Control Plane 의 `agent_*` 도구는 이 값으로 부모 실행을 찾고, 서명이 없거나 틀리면 거절한다.
키와 서명 규칙은 [`hermes/plugins/fos-ctx/README.md`](README.md) 의 「`_fos_ctx` 계약」 이 소유한다.
`hermes/tests/test_fos_ctx.py` 가 그 절의 기대 서명으로 plugin 을 검사한다.

| 도구 | 서명하지 못할 때 |
| --- | --- |
| `agent_*`, `memory_read`, `artifact_write`, `follow_up_propose`, `memory_remember` | 막는다. 모델에게 막은 이유가 간다 |
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
경로, 본문, 서명, 응답은 [`hermes/plugins/fos-ctx/README.md`](README.md) 「하위 에이전트 session 등록 계약」 이 소유한다.

| 항목 | 값 |
| --- | --- |
| 주소 | 위 「운영 값」 의 자식 session 등록 주소. 운영이 준다 |
| 인증 | 그 profile 의 MCP 토큰 |
| 제한 시간 | 3초. 연결 실패와 5xx 에만 한 번 더 부른다 |
| 실패 | 로그만 남긴다. 등록이 없는 자식의 호출은 Control Plane 이 거절한다 |

Hermes 는 자식을 만드는 자리에서 부모 스레드로 이 hook 을 동기로 부른다. 그래서 등록이 자식의 첫 도구 호출보다 먼저 끝난다.
일회용 컨테이너에서 부모 run 이 0.25초에 끝나고 등록이 0.12초에 도착했다.
등록 로그는 `fos-ctx: 자식 session 을 등록했다 (시도 N)` 이고, 실패하면 상태 코드나 예외 종류만 남는다.

### 커넥터 도구 호출을 묻는다

profile 디렉터리에 이름 대응 파일 `.fos-connector-tools.json` 이 있으면 fos-ctx 는 그 profile 의 커넥터 MCP 도구 호출마다 Control Plane 에 묻고 답대로 한다.
경로와 인증, 서명할 글, Control Plane 이 판정하는 단계는 [`docs/backend/connector-tool-policy.md`](../../../docs/backend/connector-tool-policy.md) 의 「도구 호출 판정」 이 소유한다.
요청 칸은 Control Plane 의 `ConnectorPolicyRequest` 가, 응답 칸은 `ConnectionDtos.ConnectorPolicyResponse` 가, 판정 조건과 순서는 `ToolPolicyDecision` 이 갖는다.
대응 파일이 없는 profile 에서는 아래 처리를 하지 않는다.

대응 파일의 `isolated` 칸이 profile 의 방식을 정한다. 칸의 뜻은 같은 문서의 「이름 대응」 이 갖는다.

| 방식 | `isolated` | 어떤 profile 인가 |
| --- | --- | --- |
| 옛 설치 profile | 없거나 `true` | 커넥터마다 만든 전용 profile. Control Plane MCP 가 없고 커넥터 서버만 있다 |
| 바인딩 profile | `false` | 일반 에이전트의 profile 에 커넥터를 붙인 것. Control Plane MCP, 내장 도구, 운영자가 넣은 다른 MCP 서버가 함께 있다 |

| hook 이 본 것 | 옛 설치 profile | 바인딩 profile |
| --- | --- | --- |
| `skill_manage` | 위와 같이 막는다 | 같다 |
| 대응 파일을 읽지 못한다. `isolated` 가 boolean 이 아닌 것도 같다 | `mcp__` 도구와 `execute_code` 를 모두 막는다. Control Plane MCP 의 도구도 막는다. 옛 설치 profile 일 수 있고, 그 profile 에는 Control Plane MCP 가 없기 때문이다. 그 밖의 도구는 건드리지 않는다 | 같다 |
| 대응 파일의 서버와 맞는 도구 | 등록 이름이 Control Plane MCP 의 접두사로 시작해도 Control Plane 에 묻는다. `_fos_ctx` 를 붙이지 않는다 | 같다 |
| 대응 파일의 어느 서버와도 맞지 않는 Control Plane MCP 의 도구 | 위와 같이 `_fos_ctx` 를 붙인다 | 같다 |
| `execute_code` | 막는다. 실행 맥락 없이 도구를 부르는 경로다 | 건드리지 않는다. 그 안에서 부른 커넥터 도구는 session 이 없어 아래 규칙으로 막힌다 |
| `mcp__` 로 시작하지 않는 도구 | 건드리지 않는다 | 같다 |
| `prefix` 가 맞는 서버가 없는 `mcp__` 도구 | 막는다. Control Plane 에 묻지 않는다 | 건드리지 않는다. 운영자가 넣은 다른 MCP 서버의 도구다 |
| `session_id` 나 `tool_call_id` 가 없거나 인자가 객체가 아니다 | 막는다. Control Plane 에 묻지 않는다 | 같다 |
| 직렬화한 인자 글이 UTF-8 로 60KB 를 넘는다 | 막는다. Control Plane 에 묻지 않는다 | 같다 |
| 대응 파일에 없는 도구 | `tool` 을 `null` 로 묻는다 | 같다. manifest 를 읽지 못해 `tools` 가 빈 서버의 도구도 여기 온다 |
| 답이 200 의 `allow` | 통과한다 | 같다 |
| 답이 200 의 `block` 이고 글이 있다 | 그 글로 막는다 | 같다 |
| 주소나 토큰이 없다, 제한 시간 안에 답이 없다, 200 이 아니다, 답을 읽지 못한다, 예외가 났다 | 정해 둔 글로 막는다 | 같다 |

**바인딩 profile 은 대응 파일에 그 profile 의 모든 커넥터 서버가 실려 있다는 데 기댄다.**
대응에 없는 `mcp__` 도구를 통과시키므로, 실리지 않은 커넥터 서버가 있으면 그 서버의 도구가 판정 없이 나간다.
대시보드 plugin 의 바인딩 설치와 떼기가 소유 기록의 모든 서버를 싣고, manifest 를 읽지 못했거나 소유 기록의 서버 이름이나 실행 정의가 지금 manifest 와 다른 서버는 소유 기록의 이름으로 빈 `tools` 와 함께 싣는다.
뗀 서버도 빈 `tools` 로 남긴다. 떼기 전에 시작한 실행이 그 서버를 쥐고 있어도 그 호출을 묻는다.

**바인딩 profile 의 커넥터 도구 결과는 `<external-data>` 로 감싼다.**
fos-ctx 의 `transform_tool_result` hook 이 대응 파일의 서버와 맞는 도구의 결과가 글이면 Control Plane 의 `ExternalData` 와 같은 모양으로 바꾼다.
안내 문장 한 줄, `<external-data>` 줄, 본문, `</external-data>` 줄이다. 본문 안의 닫는 표시는 대소문자와 안쪽 공백에 상관없이 `<\/external-data>` 로 바꾼다.
오류 글도 감싼다. 판정이 막은 호출은 이 hook 에 닿지 않으므로 여기 닿은 오류 글은 커넥터 서버가 낸 것이다.
글이 아닌 결과, 다른 도구, 옛 설치 profile 은 바꾸지 않는다. 대응 파일을 읽지 못하거나 예외가 나면 바꾸지 않고, Hermes 의 `<untrusted_tool_result>` 감싸기만 남는다.
Hermes 가 이 hook 을 언제 부르는지는 [`hermes/docs/hermes-contract.md`](../../docs/hermes-contract.md) 의 「도구 결과를 바꾸는 hook」 이 갖는다.

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
key 는 그 profile `.env` 의 MCP 토큰에서 나온다. terminal backend 가 `local` 이면 terminal 도구는 Hermes 프로세스의 사용자가 읽을 수 있는 파일을 모두 읽는다. docker 실행 공간이 적용된 profile 은 [실행 공간](../../docs/hermes-contract.md) 이 갖는다. 스킬 앞머리의 비밀 요청 칸이 그 토큰을 실행 공간에 넣을 수 있어 올린 스킬과 커넥터 스킬은 그 칸을 거절한다.
셸을 여는 profile 의 목록은 운영 저장소의 live 검사가 소유한다.
그 목록에 Control Plane MCP 를 등록한 profile 을 더할 때는 이 제약을 함께 판단한다.

## _fos_ctx 와 session 등록

Hermes 의 MCP 도구 호출에 실행을 가리키는 값을 싣는 방법과 그 근거가 된 Hermes 동작을 갖는다.
profile 플러그인이 붙이는 `_fos_ctx` 의 서명 계약과 하위 에이전트 session 등록 계약이 여기 있다.
Control Plane 이 그 값으로 요청자를 정하는 순서는 [`docs/backend/mcp-caller.md`](../../../docs/backend/mcp-caller.md) 가 갖는다.

### 하위 에이전트에 사진을 넘길 때

`delegate_task` 의 `images` 와 `tasks[].images` 에는 HTTP(S) 주소와 `data:image/` URL 만 받는다.
로컬 절대·상대 경로와 `file:` 주소, 잘못된 목록은 `pre_tool_call` 에서 비지 않은 안내와 함께 막는다.
이미지 없는 호출은 그대로 통과한다. Hermes 의 자식 이미지 전달은 Docker 설정과 관계없이 호스트 파일을
직접 읽기 때문에 사용자별 첨부 mount 만으로는 막을 수 없다. 결정은 [ADR-091](../../../docs/adr/ADR-091-사진-첨부는-사용자별로-저장하고-실행-공간에는-그-사용자만-붙인다.md) 이 갖는다.

이 제한은 새 판 `fos-ctx` 가 켜진 profile 에만 적용된다. 기존 일반 profile 도 파일을 갱신하고 gateway 에서
다시 읽어야 하며, 커넥터는 재설치 때 갱신된다. hook 이 꺼진 profile 은 막지 못한다.

### Control Plane 의 MCP 도구로 다른 실행을 부를 때

`memory_read` 를 두고 있는 그 자리에 실행을 시작하는 도구를 하나 더 두는 구조가 기대는 Hermes 동작이다.

#### 도구 호출에는 실행을 가리키는 값이 없다

`tools/call` 요청에는 우리가 설정에 적은 `Authorization` 헤더와 MCP 규약 헤더만 실린다.
`params._meta` 는 빈 객체다.

**도구가 아는 것은 어느 profile 이 불렀는지까지다.** 어느 실행에서 왔는지는 오지 않는다.
그러므로 이 경로로 만든 실행을 부모 `agent_execution` 에 잇는 값은 우리가 만들어야 한다.

#### 부모 실행을 잇는 방법

v0.21.5(`v2026.9.24`) 격리 환경에서 측정했다. 결정은 [ADR-031](../../../backend/docs/adr/ADR-031-mcp-호출의-부모-실행은-profile-플러그인이-서명한-루트-session-으로-잇는다.md) 에 있다.

**MCP 호출에 run 맥락을 실을 수 있는 공개 경로는 profile 플러그인의 `pre_tool_call` hook 하나다.**

| 경로 | 쓸 수 있나 | 근거 |
| --- | --- | --- |
| `tools/call` 의 `_meta`, HTTP 헤더 | 없다 | 도구 호출은 `tools/mcp_tool_handlers.py` 의 `call_tool(tool_name, arguments=args)` 한 곳이고 `meta` 를 넘기지 않는다. 헤더는 연결할 때 한 번 정해진다(`tools/mcp_tool_transport.py`) |
| `Mcp-Session-Id` | 없다 | 연결 하나의 값이다. 연결은 profile 당 하나를 모든 run 과 하위 에이전트가 함께 쓴다(`tools/mcp_tool_scope.py` 의 `_server_key`) |
| 설정의 `${VAR}` | 없다 | 설정을 읽을 때 한 번만 푼다(`tools/mcp_tool_config.py` 의 `_interpolate_env_vars`) |
| `/v1/runs` 본문 | 없다 | `provider`, `model`, `model_options` 만 agent 에 가고 LLM 요청에만 쓰인다 |
| `pre_tool_call` hook | 된다 | hook 은 `tool_name`, `args`, `task_id`, `session_id`, `tool_call_id` 를 받고 `{"action": "modify", "args": {...}}` 로 인자를 덮어쓴다(`hermes_cli/plugins.py`, `agent/tool_executor.py`) |

실측한 것이다.

| 확인 | 결과 |
| --- | --- |
| hook 이 넣은 키가 MCP `arguments` 에 도착한다 | 도착한다. Hermes 는 hook 이 더한 키를 도구 입력 규격으로 검증하지 않는다 |
| 모델이 같은 키를 넣었을 때 | hook 의 값이 이긴다 |
| hook 에서 그 profile 의 비밀값 읽기 | `get_secret` 으로 scope 오류 없이 읽힌다 |
| 같은 profile 에서 run 둘을 동시에 | 호출마다 자기 run 의 session 이 붙고 섞이지 않는다 |
| `/v1/runs` 에 Hermes 가 모르는 `session_id` 를 준 첫 run | 그 id 로 session 이 생긴다. 같은 id 로 보낸 다음 run 이 이어진다 |
| `delegate_task` 하위 에이전트의 호출 | session id 가 부모와 다르다. 하위 session 은 `parent_session_id` 로 부모를 가리킨다 |
| 압축 | 기본값 `compression.in_place: true` 에서는 session 이 바뀌지 않는다. `false` 이면 run 도중 새 session 으로 바뀐다 |
| `parent_session_id` 사슬을 따라 처음 session 찾기 | 1ms 미만. 하위 에이전트와 압축 교체 모두 처음 session 에 닿는다 |

그래서 hook 이 서명하는 값은 그 호출의 session 이 아니라 **사슬의 처음 session(루트 session)** 이다.

**플러그인은 profile 마다 둔다.** profile 디렉터리의 `plugins/` 에 두고 그 profile 설정에서 켜야 그 profile 의 호출에 붙는다. plugin 원본은 이 저장소의 [`hermes/plugins/fos-ctx/`](.) 에 있고, 대시보드 plugin 이 새 profile 을 만들 때 그 profile 로 복사한다.

**hook 이 끼우지 못한 호출도 서버에 도착한다.** 플러그인이 빠졌거나 hook 이 값을 돌려주지 않으면 원래 인자 그대로 간다. 그래서 서버는 서명이 없거나 틀린 호출을 거절한다. `memory_read`, `artifact_write`, `follow_up_propose`, `memory_remember`, `agent_*` 가 모두 그렇다([ADR-032](../../../backend/docs/adr/ADR-032-mcp-토큰은-profile-을-증명하고-실제-사용자는-부모-실행에서-정한다.md)).

##### `_fos_ctx` 계약

hook 은 Control Plane MCP 의 모든 도구 인자에 `_fos_ctx` 를 덮어쓴다.

| 키 | 값 |
| --- | --- |
| `v` | `1` |
| `session_id` | 그 호출의 session |
| `root_session_id` | `parent_session_id` 사슬의 처음 session |
| `tool_call_id` | 그 도구 호출의 id. Hermes 의 재시도에도 같다 |
| `sig` | 아래 서명의 소문자 16진수 |

서명은 HMAC-SHA256 이다.

- key 는 **그 profile 의 MCP 토큰을 SHA-256 한 값의 소문자 16진수 문자열**이다. 서버는 토큰의 원문 대신 이 해시를 저장하므로 같은 key 를 갖는다
- 서명할 글은 `v1`, 도구 이름(서버 쪽 이름. 예: `agent_delegate`), `root_session_id`, `session_id`, `tool_call_id` 를 이 순서로 줄바꿈(`\n`) 하나로 이은 것이다

인코딩은 아래와 같다. 플러그인도 같은 규칙으로 서명한다.

- HMAC key 는 64자 소문자 16진수 문자열의 **UTF-8 바이트**다. 16진수를 풀어 낸 32바이트가 아니다. 서명할 글도 UTF-8 이다
- `v` 는 JSON 숫자 `1` 이다. 문자열 `"1"` 은 거절한다
- `sig` 는 소문자 16진수 64자만 받는다. 대문자가 섞이면 거절한다
- 서명할 글에 도구 인자는 넣지 않는다

서명을 맞춰 보는 값이다. Python `hmac` 으로 계산했고 구현과 무관한 기대값이다. 토큰은 가짜 값이다.

| 항목 | 값 |
| --- | --- |
| 토큰 원문 | `test-mcp-token-0001` |
| key(토큰의 SHA-256 소문자 16진수) | `41ed73a34f34174ba0b6ded1b16cf4a085b6da45df0f711ccbeaf2a2bbc2a2ac` |
| 도구 이름 | `agent_delegate` |
| `root_session_id` | `fos-00000000-0000-4000-8000-000000000001` |
| `session_id` | `하위-세션-1` |
| `tool_call_id` | `call_0001` |
| 기대 `sig` | `b28a128dbb642aba7a8b4c35dcb237e2feb5a452305ec275909c32a00ae1b25b` |
| 같은 칸에 도구 이름만 `agent_status` 일 때 | `62109c6c99e7ed4638e4343f1e5b6b22a3f55dd974560916149986866c253236` |

서버는 모든 도구에서 `_fos_ctx` 로 요청자를 정한다. 서명을 확인한 뒤 보는 순서는 [`docs/backend/mcp-caller.md`](../../../docs/backend/mcp-caller.md#mcp-호출의-요청자를-정할-때) 의 「MCP 호출의 요청자를 정할 때」 가 갖는다.
도구 인자 검사에 넘기기 전에 `_fos_ctx` 는 떼어 낸다. 도구 규격이 그 키를 모르기 때문이다.
플러그인에 요구하는 동작이다. 서명할 수 없으면 `memory_read`, `artifact_write`, `follow_up_propose`, `memory_remember`, `agent_*` 를 모두 Hermes 쪽에서 막는다. 플러그인은 이 저장소의 `hermes/plugins/fos-ctx/` 가 갖고 `hermes/tests/test_fos_ctx.py` 가 그 동작을 검사한다. 다만 profile 에 실제로 설치되어 켜졌는지는 이 저장소가 확인하지 못한다. 그래서 서버도 서명이 없는 호출을 거절한다. 플러그인이 막지 못해도 서버에서 같은 조건으로 막힌다.

##### 호출은 profile 마다 하나씩 나간다

같은 profile 의 MCP 연결 하나가 `_rpc_lock` 으로 호출을 직렬로 보낸다(`tools/mcp_tool.py`).
한 run 의 도구 호출이 오래 걸리면 같은 profile 의 다른 run 이 기다린다.
그래서 `agent_delegate` 는 제출까지만 기다리고, `agent_status` 는 저장된 값만 읽는다.
예외는 먼저 살펴보기 트리의 `agent_status` 다. `wait_seconds` 를 주면 `assistant.delegation.status-wait-max`(기본 20초)까지 기다리고, 그동안 같은 profile 의 다른 MCP 호출이 기다린다. 살펴보기 트리가 아닌 호출은 `wait_seconds` 를 받아도 기다리지 않는다([`docs/backend/proactive-check.md`](../../../docs/backend/proactive-check.md)).

##### 재시도와 `tool_call_id`

Hermes 는 401 이면 다시 연결해 같은 인자로 한 번 더 보낸다. session 이 만료되면 읽기 전용 도구만 다시 보내고 쓰기 도구는 `outcome_uncertain` 으로 끝낸다.
hook 이 넣은 `tool_call_id` 는 인자에 들어 있어 다시 보낼 때도 같다. 그래서 **profile, 루트 session, 그 호출의 session, `tool_call_id`** 로 같은 위임을 두 번 만들지 않는 키를 계산한다.
`tool_call_id` 가 루트 아래 모든 session 에서 유일하다는 보장은 없다. 하위 에이전트마다 session 이 달라, session 을 빼면 다른 하위 에이전트의 같은 번호가 같은 위임으로 잘못 합쳐진다. 정의는 [ADR-032 의 「`delegation_key`」](../../../backend/docs/adr/ADR-032-mcp-토큰은-profile-을-증명하고-실제-사용자는-부모-실행에서-정한다.md#delegation_key) 에 있다.
JSON-RPC 의 `id` 는 연결마다 새로 매겨져 이 용도로 쓰지 않는다.

##### 공유 profile 에서 두 사용자의 호출을 실제로 확인하는 절차

가짜 Hermes 검사는 플러그인을 흉내 내므로, 실제 Hermes 와 실제 플러그인에서 한 번 더 확인한다.
환경과 실행 방법은 `fos-home-infra` 가 갖는다. 여기에는 무엇을 보고 무엇이 나와야 하는지만 적는다.

준비할 것이다.

- Hermes v0.21.5 와 그 profile 에 켠 플러그인. 그 profile 을 가리키는 GROUP 에이전트 하나
- 그 profile 에 묶인 토큰. 사용자 A 와 B 가 각자 볼 수 있는 USER Memory 하나씩. 둘 다 제목만 싣는 색인 항목이다
- 플러그인의 `pre_tool_call` 이 받은 `session_id`, 사슬로 찾은 `root_session_id`, `tool_call_id` 를 로그에 남기게 한 상태. 서명과 토큰은 남기지 않는다

확인할 것이다.

| 순서 | 할 것 | 기대하는 것 |
| --- | --- | --- |
| 1 | A 와 B 가 각자 새 대화에서 같은 에이전트로 자기 Memory 본문을 묻는다. 두 run 이 겹쳐 돌게 한다 | 두 run 의 `root_session_id` 가 서로 다르고, 각자 그 대화의 `conversation.hermes_root_session_id` 와 같다 |
| 2 | 같은 두 run 의 `pre_tool_call` 로그를 본다 | 한 run 의 모든 호출에서 `root_session_id` 가 같다. 다른 run 의 값이 섞이지 않는다 |
| 3 | 두 답을 본다 | A 의 답에는 A 의 본문만, B 의 답에는 B 의 본문만 있다 |
| 4 | Control Plane 로그의 `memory read` 줄을 본다 | 호출마다 `userId` 가 그 run 의 실행 줄 `user_id` 와 같고 `executionId` 가 그 run 의 실행 줄이다 |
| 5 | A 가 `delegate_task` 를 쓰게 해 하위 에이전트가 `memory_read` 를 부르게 한다 | 하위 에이전트 호출의 `session_id` 는 부모와 다르고 `root_session_id` 는 A 의 대화 루트와 같다. 결과는 A 의 본문이다. 부모 run 이 끝난 뒤에 불러도 같다 |
| 6 | 플러그인을 끈 profile 에서 같은 질문을 한다 | `memory_read` 가 「호출 맥락을 확인할 수 없습니다」 로 끝나고 본문은 오지 않는다 |

1 과 2 가 어긋나면 서버 쪽 판정이 옳아도 사용자가 섞인다. 그때는 배포를 되돌리지 말고 그 profile 의 토큰 묶기를 미루고 원인을 조사한다.

#### 하위 에이전트는 부모 run 보다 오래 산다

v0.21.5(`v2026.9.24`, commit `f97608f178d1ffeca59860195ab7da295f7c8e5f`)의 소스를 읽어 확인했다. 실제 실행으로 확인하는 절차는 아래 「하위 에이전트를 실제로 확인하는 절차」 에 있다.

| 확인한 것 | 근거 |
| --- | --- |
| 모델이 부르는 최상위 `delegate_task` 는 늘 background 로 요청된다. 깊이 1 이상의 오케스트레이터 자식이 부르면 동기다. 도구 규격의 `background` 인자는 무시한다 | [`run_agent.py`](https://github.com/NousResearch/hermes-agent/blob/v2026.9.24/run_agent.py) 의 `_dispatch_delegate_task` |
| background 가 실제로 떨어져 나가는지는 `_resolve_async_wake_sid` 가 정한다. API server 는 비동기 전달을 지원하지 않지만, 그 run 이 session 이력으로 결과를 받는 run 이면 자식이 떨어져 나가고 결과는 다음 turn 을 위해 session 이력에 저장된다 | [`tools/delegate_tool_dispatch.py`](https://github.com/NousResearch/hermes-agent/blob/v2026.9.24/tools/delegate_tool_dispatch.py) 의 `_resolve_async_wake_sid` |
| `/v1/runs` 가 `conversation_history` 와 `previous_response_id` 없이 오면 session 이력으로 결과를 받는 run 이다 | [`gateway/platforms/api_server_runs.py`](https://github.com/NousResearch/hermes-agent/blob/v2026.9.24/gateway/platforms/api_server_runs.py) 의 `session_history_delivery` |
| `subagent_start` hook 은 자식을 만드는 `_build_children` 안에서 부모 스레드가 부른다. 인자는 `parent_session_id`, `parent_turn_id`, `parent_subagent_id`, `child_session_id`, `child_subagent_id`, `child_role`, `child_goal` 이다 | [`tools/delegate_tool.py`](https://github.com/NousResearch/hermes-agent/blob/v2026.9.24/tools/delegate_tool.py) 의 `_build_child_agent` 끝. `_build_children` 이 자식마다 부른다 |
| 그 hook 은 제한 시간 목록에 없어 끝날 때까지 기다린다. 자식은 `_build_children` 이 모든 자식을 만든 뒤에야 `_run_batch` 로 돌기 시작한다 | [`hermes_cli/plugins_dispatch.py`](https://github.com/NousResearch/hermes-agent/blob/v2026.9.24/hermes_cli/plugins_dispatch.py) 의 `_HOOK_TIMEOUT_BOUNDED_HOOKS`, `delegate_task` 의 호출 순서 |
| hook 이 예외를 던져도 Hermes 는 삼키고 자식을 그대로 돌린다 | 같은 자리의 `_quiet("subagent_start hook invocation failed")` |
| 압축 교체에는 plugin hook 이 없다 | [`hermes_cli/plugins.py`](https://github.com/NousResearch/hermes-agent/blob/v2026.9.24/hermes_cli/plugins.py) 의 `VALID_HOOKS` |

Control Plane 은 `/v1/runs` 에 `session_id` 만 보낸다. 그래서 **모델이 부른 하위 에이전트는 부모 FOS 실행이 끝난 뒤에도 돈다.**
부모 실행의 `RUNNING` 으로 요청자를 찾으면 그 뒤의 호출은 거절되거나 같은 대화의 다음 turn 에 붙는다.
그래서 하위 에이전트 session 은 만들어질 때 등록한다([ADR-037](../../../backend/docs/adr/ADR-037-hermes-하위-에이전트-session-의-주인은-만들-때-등록한-줄로-정한다.md)).

**등록이 첫 MCP 호출보다 먼저 끝나는 근거는 hook 이 동기라는 것이다.** 자식은 hook 이 돌아온 뒤에 시작하므로 자식의 첫 도구 호출은 등록 응답 뒤에 온다.
실제 실행에서 시각으로 한 번 더 확인한다. 등록이 늦거나 실패하면 그 자식의 호출은 거절된다. 추측으로 이어 붙이지 않는다.

추측과 확인하지 않은 것이다.

- 오케스트레이터 자식이 동기로 만든 자식도 같은 `_build_children` 을 지나므로 hook 이 불린다고 본다. 실제 실행으로 확인하지 않았다
- 하위 에이전트의 압축 교체는 자식의 `parent_session_id` 사슬을 늘린다고 본다. 교체된 하위 에이전트 session 은 등록이 없어 거절된다

##### 하위 에이전트 session 등록 계약

profile 플러그인이 `subagent_start` hook 에서 부른다. 모델 도구가 아니고 도구 목록에 나오지 않는다.

| 항목 | 값 |
| --- | --- |
| 경로 | `POST /internal/hermes/session-bindings/subagent` |
| 인증 | `Authorization: Bearer <그 profile 의 MCP 토큰>`. `/mcp` 와 같은 토큰이다. profile 이 빈 토큰은 인증에서 거절한다 |
| 본문 | JSON 객체. 아래 칸 |
| 제한 시간 | 플러그인이 짧게 둔다(예: 3초). 한 번만 다시 보낸다 |

| 칸 | 값 |
| --- | --- |
| `v` | JSON 정수 `1` |
| `parent_session_id` | hook 이 받은 `parent_session_id` |
| `parent_root_session_id` | `parent_session_id` 사슬의 처음 session. `pre_tool_call` 이 계산하는 루트와 같다 |
| `child_session_id` | hook 이 받은 `child_session_id` |
| `child_subagent_id` | hook 이 받은 값. 없으면 `null`. 서명하지 않고 로그에만 쓴다 |
| `parent_subagent_id` | hook 이 받은 값. 최상위 자식이면 `null`. 서명하지 않고 로그에만 쓴다 |
| `sig` | 아래 서명의 소문자 16진수 64자 |

서명은 `_fos_ctx` 와 같은 HMAC-SHA256 이고 key 도 같다. 서명할 글만 다르다.

- key 는 그 profile 의 MCP 토큰을 SHA-256 한 소문자 16진수 64자 문자열의 **UTF-8 바이트**다
- 서명할 글은 `v1-subagent`, `parent_root_session_id`, `parent_session_id`, `child_session_id` 를 이 순서로 줄바꿈(`\n`) 하나로 이은 UTF-8 이다
- 첫 줄이 도구 이름 자리와 달라 `_fos_ctx` 서명을 등록 서명으로 쓸 수 없다

| 항목 | 값 |
| --- | --- |
| 토큰 원문 | `test-mcp-token-0001` |
| `parent_root_session_id` | `fos-00000000-0000-4000-8000-000000000001` |
| 최상위 자식: `parent_session_id` | `fos-00000000-0000-4000-8000-000000000001` |
| 최상위 자식: `child_session_id` | `하위-세션-1` |
| 최상위 자식의 기대 `sig` | `ba540b481d830453accd812c8610be8d9467a532d5ff3db891514dcfe3d0726b` |
| 중첩 자식: `parent_session_id` | `하위-세션-1` |
| 중첩 자식: `child_session_id` | `하위-세션-2` |
| 중첩 자식의 기대 `sig` | `5479a21f26ddeb337754d4fd86dd3a0e36e0ef1f6ff2cc879c0fd07da5d84485` |

서버는 서명을 확인한 뒤 같은 `child_session_id` 의 줄을 먼저 본다. 있고 루트와 부모가 요청과 같으면 부모를 풀지 않고 성공으로 답한다. 첫 응답을 잃고 다시 보내는 사이 부모 run 이 끝날 수 있어서다. 그 밖에는 부모를 푼다.

1. `(토큰의 profile, parent_session_id)` 등록이 있으면 그 origin 을 잇는다. 하위 에이전트의 하위 에이전트다
2. 없으면 `(토큰의 profile, parent_root_session_id)` 로 도는 실행 하나가 origin 이다. 최상위 session 이 만든 자식이고, 압축 교체된 최상위 session 이 만든 자식도 루트가 같아 여기 온다
3. 둘 다 없으면 거절한다

| 응답 | 뜻 |
| --- | --- |
| `201` `{"result": "created"}` | 새로 등록했다 |
| `200` `{"result": "exists"}` | 같은 부모와 루트로, 또는 같은 origin 으로 이미 등록돼 있다. 다시 보낸 것으로 본다. 앞의 경우 부모를 다시 풀지 않는다 |
| `401` | 토큰이 없거나 모르는 토큰이거나 폐기됐거나 profile 이 빈 토큰이다. 사용자 JWT 로 불러도 같다. 본문이 없다 |
| `403` `{"code": "SESSION_BINDING_REJECTED"}` | JSON 이 아니거나 모양이 틀린 본문, 서명, 부모를 풀지 못함, session 값이 128자를 넘음, `child_session_id` 가 부모나 루트나 실행 줄이나 대화의 session 과 같음. 이유는 서버 로그에만 남는다 |
| `409` `{"code": "SESSION_BINDING_CONFLICT"}` | 그 `child_session_id` 가 다른 origin 으로 이미 등록돼 있다. 덮어쓰지 않는다 |

플러그인은 2xx 가 아니거나 연결하지 못하면 로그만 남기고 hook 을 돌려준다. 등록이 없는 하위 에이전트의 호출은 서버가 거절하므로 안전한 쪽으로 실패한다.
플러그인과 서버 모두 토큰, `sig`, 본문을 로그에 남기지 않는다.

##### 모든 토큰이 profile 에 묶여 있다

등록과 origin 판정, [위임](../../docs/hermes-contract.md#취소가-아래로-내려가지-않는다) 의 「취소가 아래로 내려가지 않는다」 가 적은 취소 차단은 토큰이 profile 을 증명하는 것을 전제로 한다.
`agent_token` 에는 사용자 칸이 없고, 폐기되지 않은 토큰은 늘 `profile_name` 을 갖는다. profile 이 빈 토큰은 `/mcp` 와 등록 경로 모두 인증에서 `401` 이다.

사용자 기준으로 발급하던 옛 토큰의 경로는 지웠다. 순서와 근거는 [ADR-032](../../../backend/docs/adr/ADR-032-mcp-토큰은-profile-을-증명하고-실제-사용자는-부모-실행에서-정한다.md) 의 「옛 토큰에서 옮겨 가는 길」 에 있다.

##### 하위 에이전트를 실제로 확인하는 절차

환경과 실행 방법은 `fos-home-infra` 가 갖는다. 일회용 Hermes v0.21.5 에서 본다.

| 순서 | 할 것 | 기대하는 것 |
| --- | --- | --- |
| 1 | 플러그인이 `subagent_start` 를 받았는지 본다 | hook 이 불리고 `parent_session_id`, `child_session_id` 가 채워져 있다 |
| 2 | `delegate_task` 를 부르는 최상위 run 을 돌린다 | 부모 run 이 먼저 끝나고 자식은 계속 돈다 |
| 3 | 등록 응답 시각과 자식의 첫 도구 호출 시각을 로그로 남긴다 | 등록 응답이 먼저다 |
| 4 | 부모 run 이 끝난 뒤 자식이 `memory_read` 를 부른다 | origin 실행의 사용자의 본문이 온다 |
| 5 | `compression.in_place: false` 로 교체된 최상위 session 에서 자식을 만든다 | 등록 본문의 `parent_root_session_id` 가 원래 루트다 |
| 6 | 플러그인의 등록을 끈 채 자식이 `memory_read` 를 부른다 | 「호출 맥락을 확인할 수 없습니다」 로 끝난다 |
| 7 | 자식이 도는 동안 사용자가 부모 turn 을 중지하고, 그 뒤 자식이 `memory_read` 를 부른다 | origin 실행이 `CANCELLED` 이고 「호출 맥락을 확인할 수 없습니다」 로 끝난다 |

Hermes 를 올릴 때도 이 표를 다시 돌린다. `subagent_start` 가 사라지거나 인자 이름이 바뀌면 하위 에이전트의 MCP 호출이 모두 거절된다.

### 바인딩 profile 의 판정

일반 에이전트의 profile 에 커넥터를 붙이면 그 profile 에서 `fos-ctx` 가 두 가지 일을 함께 한다([ADR-083](../../../docs/adr/ADR-083-커넥터는-사용자가-한-번-연결하고-자기-에이전트에-여럿-붙여-그-에이전트가-도구를-직접-부른다.md)).
Control Plane MCP 호출에는 지금처럼 `_fos_ctx` 를 붙이고, 커넥터 MCP 도구 호출은 Control Plane 에 판정을 묻는다.
이름 대응 파일의 `isolated` 가 `false` 인 profile 이 바인딩 profile 이다. 칸의 뜻은 [`docs/backend/connector-tool-policy.md`](../../../docs/backend/connector-tool-policy.md) 의 「이름 대응」 이 갖는다.

| 도구 | 바인딩 profile 에서 하는 것 |
| --- | --- |
| 대응 파일의 서버와 맞는 도구 | Control Plane 에 묻는다. 등록 이름이 Control Plane MCP 의 접두사로 시작해도 묻고 `_fos_ctx` 를 붙이지 않는다 |
| 그 밖의 Control Plane MCP 도구 | 대응 파일이 없는 profile 과 같이 `_fos_ctx` 를 붙인다 |
| `execute_code`, 다른 MCP 서버의 도구, 내장 도구 | 건드리지 않는다. `skill_manage` 만 지금처럼 막는다 |
| `execute_code` 안에서 부른 커넥터 도구 | hook 에 `session_id` 가 오지 않아 묻지 않고 막는다 |

판정을 물을 때와 `_fos_ctx` 를 서명할 때 같은 profile 토큰을 쓴다. 그래서 바인딩 profile 에는 Control Plane MCP 의 토큰이 있어야 한다.
옛 설치 profile 과 다른 점은 대응에 없는 도구를 통과시키는 것이다. 그 profile 에는 Control Plane MCP 와 운영자가 넣은 MCP 서버가 함께 있어 막으면 그 서버들이 모두 막힌다.
그 대신 대응 파일에 그 profile 의 모든 커넥터 서버가 실려 있어야 한다. 바인딩 설치와 떼기가 manifest 를 읽지 못했거나 소유 기록의 서버 이름이나 실행 정의가 지금 manifest 와 다른 서버도 소유 기록의 이름으로 빈 `tools` 와 함께 싣는다.
뗀 서버도 빈 `tools` 로 남는다. 떼기 전에 시작한 실행이 그 서버를 쥐고 있기 때문이다.

커넥터 도구의 결과는 `transform_tool_result` hook 이 Control Plane 의 `ExternalData` 와 같은 `<external-data>` 로 감싼다.
hook 이 무엇을 묻고 막고 감싸는지 전체 표는 [`hermes/plugins/fos-ctx/README.md`](README.md) 의 「커넥터 도구 호출을 묻는다」 가, Hermes 가 그 hook 을 언제 부르는지는 [도구 hook 과 승인](../../docs/hermes-contract.md) 의 「도구 결과를 바꾸는 hook」 이 갖는다.
