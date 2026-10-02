## ADR-031: MCP 호출의 부모 실행은 profile 플러그인이 서명한 루트 session 으로 잇는다

- **status**: `accepted`
- **결정**: Control Plane MCP 의 `agent_*` 도구는 **Hermes profile 플러그인이 도구 인자에 덮어쓴 `_fos_ctx`** 로 부모 실행을 찾는다.
  플러그인은 `pre_tool_call` hook 에서 그 호출의 `session_id`, 그 session 이 속한 **루트 session**(`root_session_id`), `tool_call_id` 를 넣고, 그 profile 의 MCP 토큰으로 서명한다.
  서버는 서명을 확인한 뒤 루트 session 을 가진 **도는 중인 실행**을 부모로 쓴다. 서명이 없거나 틀리면 거절한다.
  Control Plane 은 새 대화의 첫 turn 과 위임한 자식 실행의 Hermes session id 를 `fos-<uuid>` 로 직접 정해, 제출하기 전에 실행 줄에 적는다.
- **대체된 부분**: [ADR-032](ADR-032-mcp-토큰은-profile-을-증명하고-실제-사용자는-부모-실행에서-정한다.md) 이 세 곳을 바꿨다. 서명을 요구하는 도구가 `agent_*` 에서 `memory_read`, `artifact_write` 까지 넓어졌다. 부모 실행은 토큰의 사용자가 아니라 토큰이 증명한 profile 로 거른다. 같은 위임을 막는 키는 루트 session 과 `tool_call_id` 의 짝이 아니라 profile, 루트 session, 그 호출의 session, `tool_call_id` 로 계산한다. 서명할 글과 key 는 그대로다. [ADR-037](ADR-037-hermes-하위-에이전트-session-의-주인은-만들-때-등록한-줄로-정한다.md) 은 하위 에이전트 session 의 부모를 도는 실행이 아니라 만들 때 등록한 origin 실행으로 바꿨다.
- **맥락**:
  - [ADR-017](ADR-017-무엇을-할지는-hermes-가-정하고-control-plane-은-경계만-갖는다.md) 은 `agent_delegate` 를 열기로 했지만, MCP 호출에 어느 실행이 불렀는지가 없어 그 방법을 구현 계획으로 미뤘다.
  - Hermes 는 MCP `tools/call` 에 run 이나 session 맥락을 싣지 않는다. 헤더는 연결할 때 한 번 정해지고, 연결은 profile 당 하나를 모든 run 이 함께 쓴다. `/v1/runs` 본문의 어느 칸도 MCP 호출까지 가지 않는다.
  - Hermes 의 `pre_tool_call` hook 은 `session_id`, `task_id`, `tool_call_id` 를 받고 도구 인자를 덮어쓸 수 있다. 모델이 같은 키를 넣어도 hook 의 값이 이긴다.
  - 2026-09-29 v0.21.5 격리 환경에서 실측했다. hook 이 넣은 값이 MCP 인자에 도착했고, 모델의 위조를 이겼고, 같은 profile 의 동시 run 에서 섞이지 않았다. 플러그인은 `get_secret` 으로 호출한 profile 의 key 를 scope 오류 없이 읽었다. Control Plane 이 정한 `fos-<uuid>` 로 시작한 run 은 다음 run 에서 이어졌다.
  - 같은 실측에서 `delegate_task` 하위 에이전트와 압축 교체(`compression.in_place: false`)는 session id 가 바뀌었다. Hermes session 저장소의 `parent_session_id` 사슬을 따라 올라가면 처음 session 이 나온다(1ms 미만). 그 저장소는 Hermes 가 쓰므로 모델이 바꾸지 못한다.
  - 우리 서버는 MCP 토큰의 SHA-256 만 저장한다. 원문을 갖지 않는다.
  - 근거와 계약은 [`hermes/fos-ctx.md`](../hermes/fos-ctx.md#부모-실행을-잇는-방법) 에 있다.
- **대안 기각**:
  - **같은 profile 에서 가장 최근의 도는 실행을 부모로 쓴다.** 동시 실행에서 부모가 섞인다.
  - **모델이 부모 실행 번호를 인자로 준다.** 모델의 입력은 믿지 않는다. 프롬프트 주입으로 다른 실행을 가리킬 수 있다.
  - **profile 에서 한 번에 하나의 run 만 돌린다.** 토큰만으로 부모를 알 수 있지만 동시 실행을 포기한다.
  - **run 마다 다른 토큰을 헤더로 준다.** Hermes 가 헤더를 연결할 때 한 번만 풀어 run 마다 바꿀 수 없다.
  - **Hermes 의 내부 contextvar 에서 run 번호를 꺼낸다.** 공개 계약이 아니어서 판이 바뀌면 조용히 깨진다.
  - **서명 없이 hook 값만 믿는다.** 플러그인이 빠진 profile 에서는 모델이 같은 키를 흉내 낼 수 있다.
  - **전용 서명 key 를 profile 마다 따로 둔다.** 인증과 서명을 나눠 돌릴 수 있지만 새 비밀값을 두 곳에 배포해야 한다. 지금은 MCP 토큰을 그대로 쓴다.
- **결과**:
  - 얻는 것:
    - Hermes core 를 고치지 않고 동시 실행에서도 부모를 정확히 잇는다
    - 하위 에이전트와 압축 교체가 일어나도 같은 대화의 실행에 이어진다. Chief 안의 `delegate_task` 하위 에이전트도 `agent_*` 를 부를 수 있다
    - `tool_call_id` 가 Hermes 의 재시도에도 같아, 같은 위임이 두 번 시작되는 것을 막는 키로 쓴다
  - 감당할 것:
    - 플러그인을 profile 마다 배치하고 켜야 한다. 새 profile 을 만들 때도 같다. 배치와 검사는 `fos-home-infra` 가 갖는다
    - **서명은 그 profile 에 셸 도구가 없는 동안만 위조를 막는다.** 셸이 있으면 모델이 토큰 파일을 직접 읽는다. 셸과 파일 도구는 관리자만 켜고 비공개 에이전트에만 둔다([ADR-029](ADR-029-에이전트-도구는-control-plane-이-등급으로-판정하고-hermes-설정-api-로-쓴다.md))
    - 이 결정 전에 만들어져 Hermes 가 session id 를 정한 대화는, 그 뒤 압축 교체가 일어났다면 루트가 우리 기록과 달라 위임할 수 없다. 기본 설정은 교체하지 않는다
    - 같은 profile 의 MCP 호출은 Hermes 쪽에서 한 번에 하나씩 나간다. `agent_*` 도구는 빨리 답해야 한다
