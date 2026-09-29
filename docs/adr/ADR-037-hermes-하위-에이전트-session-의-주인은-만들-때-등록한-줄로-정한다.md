## ADR-037: Hermes 하위 에이전트 session 의 주인은 만들 때 등록한 줄로 정한다

- **status**: `accepted`
- **결정**: Hermes `delegate_task` 가 만든 하위 에이전트 session 은 **만들어지는 순간 Control Plane 에 등록한다.** 등록 한 줄은 `(profile, session)` 을 그 session 을 낳은 FOS 실행(**origin 실행**)과 그 사용자에 묶고, 한 번 적으면 바꾸지 않는다.
  MCP 호출의 요청자는 서명을 확인한 뒤 `(토큰의 profile, 그 호출의 session)` 으로 등록을 먼저 찾는다. 있으면 그 origin 실행의 사용자다. **origin 실행의 상태는 보지 않는다.**
  등록이 없으면 그 호출의 session 이 뿌리 session 과 같을 때만 [ADR-032](ADR-032-mcp-토큰은-profile-을-증명하고-실제-사용자는-부모-실행에서-정한다.md) 의 규칙(profile, 뿌리 session, `RUNNING`)으로 최상위 실행을 찾는다. session 이 뿌리와 다르고 등록이 없으면 거절한다.
  등록은 profile 플러그인이 `subagent_start` hook 에서 내부 경로 `POST /internal/hermes/session-bindings/subagent` 로 보낸다. 모델 도구가 아니다.
- **맥락**:
  - Hermes v0.21.5(`v2026.9.24`)에서 모델이 부르는 최상위 `delegate_task` 는 늘 background 로 요청된다. `/v1/runs` 가 `conversation_history` 와 `previous_response_id` 없이 오면 자식이 부모 run 에서 떨어져 나가 따로 돈다. Control Plane 은 `session_id` 만 보내므로 **자식이 부모 FOS 실행보다 오래 산다.** 근거는 [`hermes/delegation.md`](../hermes/delegation.md#하위-에이전트는-부모-run-보다-오래-산다) 에 있다.
  - ADR-032 의 판정은 뿌리 session 과 `RUNNING` 으로 부모를 찾는다. 부모 실행이 끝나면 자식의 `memory_read` 가 거절된다. 같은 대화의 다음 turn 이 돌고 있으면 자식 호출이 그 turn 에 붙는다. 사용자는 같지만 부모 실행이 틀린다. 앞으로의 `agent_delegate` 가 이 부모 아래에 자식을 붙이므로 실행 나무가 틀린다.
  - 같은 대화 session 은 여러 turn 이 이어 쓴다. session 하나가 실행 하나라고 볼 수 없다. 반면 하위 에이전트 session 은 한 turn 에서 만들어져 그 turn 에 속한다. 다음 turn 이 시작돼도 바뀌지 않아야 한다.
  - `subagent_start` hook 은 자식을 만드는 `_build_children` 안에서 부모 스레드가 동기로 부른다. 제한 시간 목록에 없어 끝날 때까지 기다린다. 이때 부모 run 은 아직 돌고 있고, 자식은 hook 이 끝난 뒤에 돌기 시작한다. hook 예외는 Hermes 가 삼키고 자식은 그대로 돈다.
  - 압축 교체(`compression.in_place: false`)에는 hook 이 없다. 교체된 session 을 등록할 방법이 없다.
- **대안 기각**:
  - **자식의 첫 MCP 호출 때 뿌리 session 의 실행으로 추측해 등록한다.** 그때 부모가 이미 끝났거나 다음 turn 이 돌고 있으면 틀린 실행에 묶인다.
  - **가장 최근에 끝난 실행이나 가장 최근 turn 을 쓴다.** 동시 실행과 연속 turn 에서 사용자와 부모가 섞인다.
  - **판정에서 `RUNNING` 조건만 뺀다.** 뿌리 session 하나에 실행이 turn 마다 쌓여 어느 것인지 정할 수 없다.
  - **하위 에이전트마다 `agent_execution` 줄을 만든다.** Hermes 안에서만 도는 자식을 우리 실행으로 복제하면 비용과 상태를 두 곳에서 맞춰야 한다. 자식은 지금처럼 `SUBAGENT_STARTED`, `SUBAGENT_COMPLETED` 사건으로 보인다.
  - **JVM 메모리에만 둔다.** Control Plane 이 다시 뜨면 도는 자식이 요청자를 잃는다.
  - **`_fos_ctx` 에 사용자나 실행 번호를 싣는다.** 모델의 입력에서 사용자와 실행을 받지 않는다. 서명할 글을 바꾸면 배치된 플러그인이 모두 거절된다.
  - **MCP 도구로 등록한다.** 모델 도구 목록에 드러나고 모델이 부를 수 있다.
  - **등록 없는 session 도 뿌리의 도는 실행으로 판정한다.** 압축 교체된 최상위 session 은 계속 동작하지만, 등록이 빠진 자식이 다음 turn 에 붙는다. 위의 추측과 같다.
- **결과**:
  - 얻는 것:
    - 부모 실행이 끝난 뒤에도 하위 에이전트가 자기 사용자의 권한으로 `memory_read`, `artifact_write` 를 쓴다
    - 다음 turn 이 시작돼도 하위 에이전트는 처음 origin 실행에 속한다. 앞으로의 `agent_delegate` 가 그 실행 아래 정확히 붙는다
    - 하위 에이전트가 다시 만든 자식도 같은 origin 을 잇는다. 다른 에이전트에게 맡긴 FOS 실행 안의 하위 에이전트는 그 FOS 실행을 origin 으로 갖는다
    - 등록은 데이터베이스에 있어 Control Plane 이 다시 떠도 남는다
  - 감당할 것:
    - **등록이 없는 하위 에이전트의 호출은 거절된다.** 플러그인의 hook 이 실패하거나 빠진 profile 에서는 하위 에이전트가 사용자가 걸린 도구를 쓸 수 없다. 안전한 쪽으로 실패한다
    - **`compression.in_place: false` 에서 교체된 최상위 session 이 직접 부르는 호출은 거절된다.** 그 session 은 뿌리와 다르고 등록이 없다. 기본값 `in_place: true` 에서는 session 이 바뀌지 않아 일어나지 않는다. 교체된 session 이 만든 하위 에이전트는 등록할 때 뿌리의 도는 실행으로 부모가 풀려 정상 동작한다
    - 부모 turn 이 끝난 뒤 하위 에이전트가 쓴 결과물은 어느 답에도 묶이지 않는다. 답에 묶는 것은 turn 이 끝날 때 폴더를 훑는 방식이다. 파일은 대화 폴더에 남는다
    - 등록 줄은 지우지 않는다. 실행 기록과 같이 남는다
    - 플러그인이 `subagent_start` hook 을 보내야 한다. 배치와 확인은 `fos-home-infra` 가 갖는다
- **적용 범위**: ADR-032 의 「도는 부모 실행 하나를 찾는다」 는 최상위 session 에만 남고, 하위 에이전트 session 은 이 결정을 따른다. ADR-031 의 `_fos_ctx` 서명할 글과 key 는 그대로다. 등록 경로의 계약과 오류 코드는 [`hermes/delegation.md`](../hermes/delegation.md#하위-에이전트-session-등록-계약) 에, 저장 모델은 [`data-schema.md`](../data-schema.md#hermes_session_binding) 에 있다.

### 판정 순서

```text
_fos_ctx 서명 확인
 └ (토큰의 profile, session_id) 등록이 있다   → origin 실행의 사용자. 상태 무관
 └ 없고 session_id == root_session_id         → (profile, 뿌리 session, RUNNING) 실행 하나의 사용자
 └ 없고 session_id != root_session_id         → 거절
```

등록의 `root_session_id` 가 서명한 뿌리와 다르면 거절한다.

### 등록할 때 부모 풀기

```text
서명 확인
 └ (토큰의 profile, parent_session_id) 등록이 있다   → 그 origin 을 잇는다(하위 에이전트의 하위 에이전트)
 └ 없으면 (profile, parent_root_session_id, RUNNING) → 그 실행이 origin(최상위 session 이 만든 자식)
 └ 둘 다 없으면                                       → 거절
```

같은 `(profile, child_session_id)` 가 같은 부모와 뿌리로 다시 오면 부모를 다시 풀지 않고 성공으로 답한다. 첫 응답을 잃고 다시 보내는 사이 부모 run 이 끝날 수 있어서다. 부모나 뿌리가 다르면 부모를 풀어, 같은 origin 이면 성공이고 다른 origin 이면 거절하고 덮어쓰지 않는다.
`child_session_id` 가 뿌리 session 과 같거나, 그 profile 의 실행 줄이 쓰는 session 이거나, 대화가 적어 둔 session 이면 거절한다. 압축 교체된 최상위 session 은 대화에만 남는다. 최상위 session 에 등록이 생기면 뒤 turn 의 호출이 앞 turn 에 묶이기 때문이다.

### 이 결정 뒤에 할 일

`agent_*` 도구를 열기 전에 옛 토큰 경로를 지운다. 운영의 모든 토큰을 profile 에 묶고, `assistant.mcp.legacy-user-tokens` 를 거짓으로 돌리고, 옛 경로가 쓰이지 않는 것을 확인한 뒤 `agent_token.user_id` 칸과 설정과 `McpPrincipal.legacyUserId` 를 지운다.
옛 토큰으로 온 호출에는 origin 실행이 없어 `agent_*` 가 부모를 정할 수 없다.
