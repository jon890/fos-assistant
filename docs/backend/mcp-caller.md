# MCP 요청자

Control Plane MCP 의 토큰은 profile 만 증명하고, 사용자가 걸린 도구의 요청자는 서명한 `_fos_ctx` 로 찾은 **origin 실행**의 사용자다.
이 파일은 요청자를 정하는 클래스와 흐름, 하위 에이전트 session 등록, Control Plane MCP 서버와 결과물 쓰기 도구의 계약을 갖는다.
결정은 [ADR-032](../../backend/docs/adr/ADR-032-mcp-토큰은-profile-을-증명하고-실제-사용자는-부모-실행에서-정한다.md) 와 [ADR-037](../../backend/docs/adr/ADR-037-hermes-하위-에이전트-session-의-주인은-만들-때-등록한-줄로-정한다.md) 에 있다.

## 어느 클래스가 무엇을 하나

| 자리 | 하는 일 |
| --- | --- |
| `mcp.presentation.AgentTokenAuthenticationFilter` | `/mcp` 와 `/internal/hermes/session-bindings/subagent` 요청의 토큰을 인증해 `McpPrincipal` 을 인증 주체로 둔다. `CurrentUser` 를 두지 않는다. 서명 검증에 쓸 토큰 해시를 요청 속성으로도 넘긴다 |
| `mcp.application.AgentTokenService` | 발급, 목록, 폐기, 인증. 인증은 있고, 폐기되지 않았고, profile 이 묶인 토큰만 통과시킨다. 사용자는 토큰에서 읽지 않는다 |
| `mcp.application.McpPrincipal` | 인증 결과. 토큰 번호, profile, 토큰 해시. profile 은 늘 있다 |
| `mcp.application.McpCallerResolver` | `_fos_ctx` 서명 확인, origin 실행 찾기, 그 실행의 사용자 읽기를 차례로 한다. 모든 MCP 도구가 이 한 메서드 `resolve` 를 지난다. 실패는 모두 `MCP_CALL_CONTEXT_INVALID` 다 |
| `mcp.application.McpCaller` | 판정 결과. 요청자 `user`, origin 실행 `originExecution`, 확인한 `context`. 셋은 늘 있고 생성자가 빈 값을 거절한다. origin 실행은 끝난 실행일 수 있지만 그 실행이나 루트 실행이 `CANCELLED` 인 것은 아니다 |
| `mcp.application.McpCallContext` | `_fos_ctx` 를 읽고 서명을 확인한다. 모델이 준 다른 인자는 보지 않는다 |
| `mcp.application.SubagentRegistration` | 등록 본문을 읽고 서명을 확인한다. 서명할 글의 첫 줄이 `v1-subagent` 다 |
| `mcp.presentation.SubagentSessionController` | `POST /internal/hermes/session-bindings/subagent`. 인증 주체가 `McpPrincipal` 인지 보고 등록을 부른다 |
| `orchestration.application.SessionOwnerResolver` | profile, 서명한 루트 session, 그 호출의 session 으로 origin 실행을 정한다. 등록을 먼저 보고, 없으면 session 이 루트와 같을 때만 `DelegationParentResolver` 를 부른다. 등록의 origin 실행이나 그 루트 실행이 `CANCELLED` 면 거절한다 |
| `orchestration.application.DelegationParentResolver` | profile 과 서명한 루트 session 으로 도는 실행 하나를 찾는다. 최상위 session 의 판정과 최상위 자식의 등록이 쓴다. 사용자로 먼저 거르지 않는다. 없거나 둘 이상이거나 profile 이 다르면 같은 실패 |
| `orchestration.application.SubagentSessionRegistrar` | 등록 하나를 적는다. 부모를 풀고, 같은 origin 의 재등록은 그대로 두고, 다른 origin 은 거절한다 |
| `orchestration.domain.HermesSessionBinding`, `orchestration.infra.HermesSessionBindingRepository` | 하위 에이전트 session 등록 줄과 그 저장소 |

`McpController` 는 `params.name` 이 문자열이고 `params.arguments` 가 객체인지 본 뒤 `McpCallerResolver` 를 부른다. 도구별 인자 검사는 그 뒤에 하고, 그 `McpCaller` 로 `McpToolService` 를 부른다.
판정이 실패하면 `McpToolService.invalidContext()` 의 같은 도구 결과를 돌려준다.

등록 경로는 MCP 도구가 아니다. `tools/list` 에 나오지 않고 `McpController` 를 지나지 않는다.
`ControlPlaneJwtFilter` 는 이 경로를 `/mcp` 처럼 건너뛴다. 사용자 JWT 로 부르면 `AgentTokenAuthenticationFilter` 가 토큰으로 인증하지 못해 401 이다. 컨트롤러의 `McpPrincipal` 확인은 그 뒤의 방어 검사다.

## 토큰 관리 경로

관리자만 부른다. 경로와 응답은 `AgentTokenAdminController` 가 갖는다.
토큰은 profile 에 묶어 발급하고 원문은 한 번만 돌려준다. **사용자로 발급하는 길은 없다.** 폐기해도 행은 남는다.

profile 이름은 `HermesProfileName` 의 규칙을 따른다. 그 profile 에 에이전트가 있는지는 보지 않는다. profile 을 먼저 만들고 에이전트를 나중에 붙이는 순서가 있어서다.

## 실행 줄에 적는 session

Hermes 에 보낼 session 과 실행 줄에 적을 session 은 뜻이 다르다. 둘을 `chat.domain.RunSession` 하나로 넘긴다.
문자열 둘을 나란히 받으면 순서를 바꿔도 컴파일되기 때문이다.

| 만드는 자리 | `runtimeSessionId`(보낼 session) | `correlationSessionId`(실행 줄에 적을 session) |
| --- | --- | --- |
| `ConversationSessions.ensure` 가 대화 turn 에 | 대화의 `hermes_session_id` | 대화의 `hermes_root_session_id`, 비었으면 보낼 session |
| `RunSession.fresh()` 가 흐름의 하위 실행과 `agent_delegate` 의 위임 자식에 | 제출하기 전에 새로 정한 `fos-<uuid>`. 부모의 session 을 잇지 않는다 | 같은 값 |

`ChatService` 와 `ResearchAndBuildFlow` 의 Chief 는 `ensure` 의 값을, `ChildExecutionRunner` 는 `fresh()` 의 값을 `AgentRunner.run` 에 넘긴다.
Memory 제안은 session 을 적지 않는다. 그래서 그 실행 안에서는 사용자가 걸린 도구를 쓸 수 없다.

## MCP 호출의 요청자를 정할 때

`memory_read`, `artifact_write`, `agent_list`, `agent_delegate`, `agent_status`, `agent_stop`, `follow_up_propose` 가 모두 이 길을 지난다.

```mermaid
sequenceDiagram
    participant H as Hermes (공유 profile)
    participant P as profile 플러그인
    participant F as 토큰 인증
    participant R as 요청자 판정
    participant B as hermes_session_binding
    participant E as agent_execution

    H->>P: 도구 호출 (session_id, tool_call_id)
    P->>P: parent_session_id 사슬로 루트 session 을 찾고 그 profile 의 토큰으로 서명
    P->>F: POST /mcp, Bearer 토큰, 인자에 _fos_ctx
    F->>F: 토큰 해시로 한 줄을 찾는다. 폐기됐거나 profile 이 비었으면 401
    F->>R: McpPrincipal(토큰 번호, profile, 토큰 해시)
    R->>R: _fos_ctx 의 서명을 토큰 해시로 확인
    R->>B: (profile, session_id) 등록을 찾는다
    alt 등록이 있다
        B-->>R: origin 실행
        R->>R: origin 이나 그 루트 실행이 CANCELLED 면 거절. 다른 상태는 끝났어도 된다
    else 등록이 없고 session_id 가 루트와 같다
        R->>E: profile, 루트 session, RUNNING 이 맞는 줄을 둘까지 읽는다
        E-->>R: 정확히 하나
    else 등록이 없고 session_id 가 루트와 다르다
        R-->>H: 거절
    end
    R->>R: 그 실행의 user_id 로 사용자를 읽는다
    R-->>H: 그 사용자의 권한으로 도구를 돌린 결과
```

같은 profile 에서 두 사용자의 실행이 함께 돌아도 섞이지 않는다.
대화마다 루트 session 이 다르고, 호출마다 자기 루트 session 에 서명이 붙기 때문이다.

```text
공유 profile
  실행 #100  user=A  루트 session=fos-A → 서명한 루트 fos-A 의 호출은 A 로 돈다
  실행 #101  user=B  루트 session=fos-B → 서명한 루트 fos-B 의 호출은 B 로 돈다
```

토큰이 어느 사용자로 발급됐었는지는 결과를 바꾸지 않는다.

### 요청자 판정이 갈리는 지점

| 경우 | 결과 |
| --- | --- |
| 토큰이 없거나, 모르는 토큰이거나, 폐기됐다 | HTTP 401 |
| profile 이 빈 토큰이다 | HTTP 401. 옛 토큰을 사용자로 돌리던 경로는 지웠다([ADR-032](../../backend/docs/adr/ADR-032-mcp-토큰은-profile-을-증명하고-실제-사용자는-부모-실행에서-정한다.md) 의 「옛 토큰에서 옮겨 가는 길」) |
| `_fos_ctx` 가 없거나 모양이 틀렸거나 서명이 맞지 않는다 | 거절한다 |
| 서명이 맞고 그 호출의 session 에 하위 에이전트 등록이 있다 | 등록의 origin 실행의 사용자로 돈다. origin 실행이 `SUCCEEDED` 나 `FAILED` 로 끝났어도, 같은 대화의 다음 turn 이 돌고 있어도 같다 |
| 등록이 있는데 origin 실행이나 그 실행 트리의 루트 실행이 `CANCELLED` 다 | 거절한다. 사용자가 turn 이나 흐름을 중지해도 Hermes 하위 에이전트는 계속 돌 수 있어서다. 흐름을 멈출 때 이미 끝난 자식 실행에서 만든 하위 에이전트도 루트가 중지돼 거절된다. Control Plane MCP 도구만 막고, 하위 에이전트의 Hermes 자체 도구와 run 은 멈추지 못한다([ADR-037](../../backend/docs/adr/ADR-037-hermes-하위-에이전트-session-의-주인은-만들-때-등록한-줄로-정한다.md)) |
| 등록의 루트 session 이 서명한 루트와 다르다 | 거절한다 |
| 등록이 없고 그 호출의 session 이 루트와 다르다 | 거절한다. 등록이 빠진 하위 에이전트와 `compression.in_place: false` 로 교체된 최상위 session 이 여기 온다. 둘을 나눌 수 없어 추측하지 않는다([ADR-037](../../backend/docs/adr/ADR-037-hermes-하위-에이전트-session-의-주인은-만들-때-등록한-줄로-정한다.md)) |
| 등록이 없고 session 이 루트와 같지만 그 루트 session 으로 도는 실행이 없다 | 거절한다. 끝난 실행, Memory 제안 실행, Control Plane 이 시작하지 않은 run(Hermes cron, 다른 채팅 플랫폼 gateway)이 여기 온다. 요청자를 알 수 없어서다 |
| 그 루트 session 으로 도는 실행이 다른 profile 의 것이다 | 거절한다 |
| 도는 실행이 둘 이상이다 | 거절한다. 가장 최근 것을 고르지 않는다 |

거절은 모두 **같은 도구 결과** 하나로 보인다. `isError: true` 와 `McpToolService.INVALID_CONTEXT` 의 문구다.
서명이 틀린 것과 남의 profile 이 도는 것을 밖에서 나누지 못하게 해, 다른 사용자가 지금 실행 중인지 훑어 알아내지 못하게 한다.
이유는 서버 로그에만 남는다. 루트 session 으로 도는 실행이 없을 때는 옛 대화의 압축 교체일 수 있다는 표시(`DELEGATION_CONTEXT_UNAVAILABLE`)를 함께 남긴다. 등록이 없는 하위 에이전트 session 이면 `SUBAGENT_SESSION_UNREGISTERED` 를, 등록의 origin 실행이나 그 루트가 중지됐으면 `ORIGIN_CANCELLED` 를 남긴다.

**실행 줄에 session 을 적는 실행만 요청자가 될 수 있다.** 어느 실행이 어떤 session 을 적는지는 위 「실행 줄에 적는 session」 이 갖는다.

### 하위 에이전트 session 을 등록할 때

Hermes 의 하위 에이전트는 부모 run 보다 오래 살 수 있다. 그래서 만들어지는 순간 주인을 적는다.
계약은 [`hermes/plugins/fos-ctx/README.md`](../../hermes/plugins/fos-ctx/README.md#하위-에이전트-session-등록-계약), 결정은 [ADR-037](../../backend/docs/adr/ADR-037-hermes-하위-에이전트-session-의-주인은-만들-때-등록한-줄로-정한다.md) 에 있다.

```mermaid
sequenceDiagram
    participant H as Hermes 부모 스레드
    participant P as profile 플러그인
    participant C as 등록 경로
    participant B as hermes_session_binding
    participant E as agent_execution

    H->>H: delegate_task 가 자식 S1 을 만든다
    H->>P: subagent_start(parent_session_id, child_session_id)
    P->>C: POST /internal/hermes/session-bindings/subagent, Bearer 토큰, 서명한 본문
    C->>C: 토큰 인증과 서명 확인
    C->>B: (profile, parent_session_id) 등록을 찾는다
    alt 부모도 하위 에이전트다
        B-->>C: 부모의 origin 실행
    else 부모가 최상위 session 이다
        C->>E: profile, parent_root_session_id, RUNNING 인 줄 하나
        E-->>C: origin 실행
    end
    C->>B: (profile, S1) → origin, 사용자, 루트, 부모 session
    C-->>P: 201 또는 200
    P-->>H: hook 이 돌아온다
    H->>H: 자식 S1 이 돌기 시작한다
```

부모 run 이 먼저 끝나도 S1 의 등록은 남는다.

```text
FOS 실행 #100 user=A profile=chief session=R    ← 끝났다
 └ S1 등록: (chief, S1) → #100, A
     └ S2 등록: (chief, S2) → #100, A          ← S1 이 만든 자식도 #100 을 잇는다
FOS 실행 #105 같은 대화의 다음 turn          ← 돌아도 S1 은 #100 에 남는다
```

다른 에이전트에게 맡긴 FOS 실행 안에서 만든 하위 에이전트는 그 FOS 실행을 origin 으로 갖는다. 그 FOS 실행이 제 session 으로 돌기 때문이다.

응답 코드와 거절 조건(같은 등록의 재전송, 다른 origin, 부모를 풀지 못함, 서명, session 길이)은 [`hermes/plugins/fos-ctx/README.md`](../../hermes/plugins/fos-ctx/README.md#하위-에이전트-session-등록-계약) 의 「하위 에이전트 session 등록 계약」 이 갖는다. 아래는 그 밖의 경우다.

| 경우 | 결과 |
| --- | --- |
| `child_session_id` 가 루트 session 이거나, 그 profile 의 실행 줄이 쓰는 session 이거나, 대화가 적어 둔 session 이다 | `403` 으로 거절한다. 최상위 session 에 등록이 생기면 뒤 turn 이 앞 turn 에 묶인다. 압축 교체된 최상위 session 은 대화에만 남아 있어 대화도 본다 |
| 플러그인이 등록하지 못했다 | Hermes 는 hook 예외를 삼키고 자식을 돌린다. 그 자식의 호출은 위 판정에서 거절된다 |
| 부모 등록의 origin 실행이나 그 루트가 이미 `CANCELLED` 다 | 등록은 받는다. 그 자식의 MCP 호출이 위 판정에서 거절된다 |
| Control Plane 이 다시 떴다 | 등록은 데이터베이스에 있어 그대로 쓴다 |
| 부모 turn 이 끝난 뒤 하위 에이전트가 결과물을 썼다 | 파일은 대화 폴더에 남지만 어느 답에도 묶이지 않는다. 답에 묶는 것은 turn 이 끝날 때 폴더를 훑는 방식이다 |

## Control Plane MCP

제목만 `instructions` 에 실린 Memory 본문, 기억 남기기, 결과물 쓰기, 다른 에이전트에게 맡기기를 Control Plane 의 MCP 서버 하나가 맡는다.

서버 이름, 경로, 프로토콜 버전, 도구 목록은 `McpController` 와 `McpToolService` 가 갖는다.
Hermes 는 이 서버의 도구를 `mcp__fos_assistant__<도구>` 로 보인다.
토큰은 profile 마다 다르고, 그 profile 을 증명할 뿐 사용자를 정하지 않는다.
모든 도구가 위 「MCP 호출의 요청자를 정할 때」 의 판정을 지난다. 서명하는 쪽의 계약은 [`hermes/plugins/fos-ctx/README.md`](../../hermes/plugins/fos-ctx/README.md#부모-실행을-잇는-방법) 에 있다.

**먼저 살펴보기 트리에서는 받는 도구를 줄인다.** 받는 도구는 `McpController` 의 `CHECK_TREE_TOOLS` 와 `CHECK_TREE_WRITE_TOOLS` 가 갖는다.
요청자를 정한 뒤 `ProactiveCheckGuard.checkOf` 가 살펴보기 줄을 찾으면 그 밖의 도구는 `McpToolService.notAllowedInCheck()` 의 오류 결과(`isError: true`)다.

- 읽기와 위임 도구는 받는다
- 쓰기 도구를 허용한 살펴보기는 그 살펴보기의 점검 대화에 쓰는 `artifact_write` 를 더 받는다([ADR-082](../adr/ADR-082-먼저-살펴보기의-쓰기-도구는-관리자가-에이전트마다-켜고-커넥터-쓰기는-승인-카드로-보낸다.md))
- `follow_up_propose` 는 쓰기 도구 허용과 관계없이 받는다. 할 일은 제안만 하고 사람이 받아들여야 챙긴다([ADR-085](../adr/ADR-085-매일-깨우기는-예약-작업을-다시-쓰고-다섯-칸-보고를-지금-화면에-올린다.md))
- `memory_remember` 는 쓰기 도구를 허용한 살펴보기에서도 받지 않는다. 살펴보기는 바깥 글을 읽는 실행이라 기억을 남기면 프롬프트 주입의 길이 된다([ADR-20261007 / memory-remember](../adr/ADR-20261007-memory-remember.md))

새 도구를 더하면 살펴보기에서 받을지 함께 정한다([`proactive-check.md`](proactive-check.md) 의 「읽기 경계」).

도구마다의 인자와 결과는 아래가 갖는다.

| 도구 | 계약을 갖는 곳 |
| --- | --- |
| `memory_read` | [`memory.md`](memory.md) 의 「Memory 본문을 읽는 길」 |
| `artifact_write` | 아래 「결과물 쓰기 도구」 |
| `agent_list`, `agent_delegate`, `agent_status`, `agent_stop` | [`agent-delegation.md`](agent-delegation.md) 의 「도구 계약」 |
| `follow_up_propose` | [`follow-up.md`](follow-up.md) 의 「제안 도구」 |
| `memory_remember` | [`memory.md`](memory.md) 의 「에이전트가 기억을 남기는 길」 |

**Hermes 는 MCP 도구 이름 앞에 서버 이름을 붙인다.** 그래서 서버 이름에 특정 기능의 이름을 넣지 않는다.
Control Plane 이 여는 도구는 모두 이 서버 하나에 둔다.

**서버 이름을 바꿀 때는 등록 이름과 허용 목록을 한 번에 바꾼다.**
`platform_toolsets.api_server` 의 MCP 이름은 허용 목록이다. 목록에 등록되지 않은 이름만 남으면 Hermes 는 허용 목록이 없는 것으로 보고 전역 MCP 서버를 모두 켠다. 근거는 [v0.21.5 `hermes_cli/tools_config.py`](https://github.com/NousResearch/hermes-agent/blob/v2026.9.24/hermes_cli/tools_config.py) 의 `_get_platform_tools` 다.
그래서 대시보드 plugin 이 옛 이름과 새 이름을 함께 받는 동안 등록과 목록을 바꾸고, backend 가 새 이름을 쓰게 한 뒤 옛 이름을 막는다.
MCP 서버를 등록하는 설정 틀은 이 저장소의 `hermes/profile-template/` 가 갖는다. 토큰 값을 넣고 실제 연결을 확인하는 일은 비공개 저장소 `fos-home-infra` 가 맡는다.

### API server 에서 MCP 도구를 여는 범위

`platform_toolsets.api_server` 의 `no_mcp` 는 API server 로 들어온 실행에서 MCP 도구를 통째로 막는다.
`no_mcp` 대신 서버 이름을 허용 목록으로 적으면 그 서버의 도구만 모델에 전달하고 나머지는 막는다.

도구가 없던 profile 에 MCP 서버를 처음 열면 Hermes 가 `tool_search`, `tool_describe`, `tool_call` 중계를 함께 실어 입력이 도구 정의보다 크게(약 1,800 토큰) 늘 수 있다.
이미 이 중계를 쓰는 profile 은 MCP 서버를 더해도 서버의 도구 정의만큼만 늘어난다.

### 입력 비용은 API 콜 수가 정한다

실행의 입력 토큰은 provider 에 보낸 모든 API 콜의 입력을 더한 값이다.
추가 API 콜 하나는 그 시점의 전체 프롬프트 하나만큼 들기 때문에 대화가 길수록 도구 호출도 비싸진다.
`memory_read` 를 부른 턴은 API 콜이 한두 번 늘고, 한 턴에 항목을 몇 개 읽든 늘어나는 콜 수는 같다.
그래서 항목 수보다 도구를 부르는 턴 수가 입력 비용을 정한다.

항상 층(`retrieval` 이 `ALWAYS`)은 API 콜을 늘리지 않고 본문 길이만큼만 입력을 늘린다(한 글자당 약 0.49 토큰).
Memory 색인 한 줄은 약 12 토큰이다.
그래서 항목이 필요한 실행 하나만 비교하면 항상 층이 도구보다 싸다.
항목이 필요 없는 실행에도 본문을 싣는 비용까지 포함하면 사용 빈도가 손익분기를 정한다.
아래 값보다 본문이 길면 도구가 유리하다.

| 항목이 필요한 비율 | 새 대화 | 이어진 대화 |
| --- | --- | --- |
| 2회에 1회 | 8,000자 한도 안에서는 해당하지 않음 | 8,000자 한도 안에서는 해당하지 않음 |
| 4회에 1회 | 약 7,600자 | 8,000자 한도 안에서는 해당하지 않음 |
| 10회에 1회 | 약 3,000자 | 약 4,100자 |
| 50회에 1회 | 약 600자 | 약 800자 |

이 값은 측정한 프롬프트 크기와 API 콜 수를 기준으로 한 판단값이다.
profile 의 도구 구성이나 대화 길이가 달라지면 손익분기도 달라진다.

## 결과물 쓰기 도구

`artifact_write` 는 일반 파일 도구가 없는 profile 에 결과물 저장만 연다.
결정은 [ADR-028](../../backend/docs/adr/ADR-028-결과물은-사용자의-대화-폴더에-mcp-도구로-쓴다.md) 에 있다.

인자와 검사 규칙, 응답 모양, 오류 번호는 `McpController`, `McpToolService`, `McpDtos` 가 갖는다.

origin 실행의 사용자로 `ConversationAccess.requireOwn(CurrentUser, UUID)` 를 호출한다.
확인 전에는 폴더 생성과 URL 조회를 하지 않는다.
없는 대화, 지운 대화, 다른 사용자의 대화는 같은 `isError: true` 결과로 숨긴다.
같은 사용자의 다른 대화는 허용한다.
도구 입력에 사용자 번호나 profile 을 넣어 요청자를 바꿀 수 없다.
실행을 가리키는 값은 모델이 주는 인자가 아니라 플러그인이 서명한 `_fos_ctx` 로만 온다. 그 까닭은 [도구 호출에는 실행을 가리키는 값이 없다](../../hermes/plugins/fos-ctx/README.md#도구-호출에는-실행을-가리키는-값이-없다) 절이 갖는다.

호스트 경로와 내부 대화 번호는 반환하지 않는다.
URL 의 query, 응답 본문, 파일시스템 경로를 오류나 로그에 노출하지 않는다.

같은 경로를 덮어쓸 때도 임시 파일을 완성한 뒤 교체한다.
상한 초과나 다운로드 실패는 임시 파일을 지우고 기존 파일을 보존한다.
HTML 을 답에 묶는 일은 쓰기 도구가 하지 않는다. 묶는 시점은 [`artifact.md`](artifact.md#결과물을-mcp-로-쓸-때) 가 갖는다.

`source_url` 의 주소 검사와 SSRF 방어는 `ArtifactSourceFetcher` 와 `ArtifactSourceProperties` 가 갖는다.
허용 목록을 비워 두는 까닭, 검사한 IP 로 연결하는 까닭, 결과물 폴더의 경계로 Hermes 의 `HERMES_WRITE_SAFE_ROOT` 를 쓰지 않는 까닭은 [ADR-028](../../backend/docs/adr/ADR-028-결과물은-사용자의-대화-폴더에-mcp-도구로-쓴다.md) 이 갖는다.
