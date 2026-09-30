## ADR-032: MCP 토큰은 profile 을 증명하고 실제 사용자는 부모 실행에서 정한다

- **status**: `accepted`
- **결정**: Control Plane MCP 의 토큰(`agent_token`)은 **어느 Hermes profile 이 부르는지만** 증명한다. 사용자를 정하지 않는다.
  사용자가 걸린 도구(`memory_read`, `artifact_write`, 앞으로의 `agent_*`)는 profile 플러그인이 서명한 `_fos_ctx` 로 **도는 부모 실행 하나**를 찾고, 그 실행의 `user_id` 를 요청자로 쓴다.
  부모 실행은 토큰이 증명한 profile 과 서명한 뿌리 session 과 `RUNNING` 이 모두 맞는 줄이어야 한다. profile 이 다르면 거절한다.
  위임을 두 번 만들지 않는 `delegation_key` 는 뿌리 session 에 그 호출의 session 을 더해 계산한다.
- **대체된 부분**: [ADR-037](ADR-037-hermes-하위-에이전트-session-의-주인은-만들-때-등록한-줄로-정한다.md) 이 하위 에이전트 session 의 요청자를 바꿨다. 하위 에이전트 session 은 도는 부모 실행이 아니라 만들 때 등록한 origin 실행의 사용자로 돈다. origin 실행이 끝났어도 되지만, origin 실행이나 그 뿌리 실행이 `CANCELLED` 면 거절한다. 등록이 없는 하위 에이전트 session 은 거절한다. 최상위 session 은 이 결정 그대로 도는 실행 하나를 찾는다.
- **맥락**:
  - 전에는 토큰 한 줄이 `user_id` 를 갖고 그 사용자로 모든 MCP 도구를 돌렸다. 개인 profile 은 그 사용자 한 사람만 쓰므로 맞았다.
  - GROUP 에이전트는 여러 사용자가 같은 profile 을 쓴다([ADR-002](ADR-002-profile은-나누고-ai-계정은-가족이-함께-쓴다.md)). MCP 연결은 profile 당 하나이고 헤더는 연결할 때 한 번 정해진다([ADR-031](ADR-031-mcp-호출의-부모-실행은-profile-플러그인이-서명한-뿌리-session-으로-잇는다.md)). 그 profile 설정에 사용자 A 의 토큰이 들어 있으면 B 의 대화에서 부른 `memory_read` 가 A 의 권한으로 돌았다.
    ```text
    B 의 대화 → 공유 profile → memory_read → Bearer(A 의 토큰) → CurrentUser = A → A 의 개인 Memory
    ```
  - `AgentToolPolicy` 는 GROUP 에이전트에도 Control Plane MCP 를 늘 붙인다. 그래서 위 경로는 GROUP 에이전트를 쓰는 모든 대화에 열려 있었다.
  - `agent_execution` 은 이미 `user_id` 와 `profile_name` 을 갖고 시작할 때 적힌다([ADR-011](ADR-011-실행은-시작할-때-기록하고-끝날-때-갱신한다.md)). 누가 어느 profile 로 도는지는 Control Plane 이 이미 기록한 값이다.
  - Hermes 의 `delegate_task` 하위 에이전트는 부모와 다른 session 을 갖는다. `tool_call_id` 가 뿌리 아래 모든 session 에서 유일하다는 보장은 없다.
- **대안 기각**:
  - **토큰에 `user_id` 를 필수로 두고 `profile_name` 을 더한다.** 권한 판정에 쓰지 않을 칸이 의미 있는 값처럼 남아, 뒤에 오는 코드가 그것을 다시 읽을 수 있다.
  - **GROUP 에이전트는 주인의 사용자로 돈다.** GROUP 에이전트에는 주인이 없거나 다른 뜻이다. 쓰는 사람과 권한이 어긋난다.
  - **profile 이름으로 사용자를 거꾸로 찾는다.** 같은 profile 을 여러 사용자가 쓴다.
  - **요청 본문에 사용자를 넣는다.** 모델의 입력은 믿지 않는다.
  - **그 profile 에서 가장 최근에 도는 실행을 쓴다.** 동시 실행에서 사용자가 섞인다.
  - **`delegation_key` 를 부모 실행 번호로 계산한다.** 같은 호출의 재시도 사이에 부모가 바뀌면 키가 달라져 위임이 두 번 생긴다.
- **결과**:
  - 얻는 것:
    - 같은 profile 에서 사용자 A 와 B 의 실행이 동시에 돌아도 호출마다 자기 실행의 사용자로 판정된다
    - 토큰이 유출돼도 그 profile 로 지금 도는 실행의 뿌리 session 을 모르면 사용자 권한을 얻지 못한다
    - 위임 도구가 같은 판정 경로를 그대로 쓴다
  - 감당할 것:
    - profile 플러그인이 빠진 profile 에서는 `memory_read` 와 `artifact_write` 가 거절된다. 플러그인 배치가 이 두 도구의 전제가 된다
    - Control Plane 이 session 을 모르는 실행에서 온 호출은 거절된다. 대화 turn 과 흐름의 하위 실행은 제출하기 전에 session 을 적는다. Memory 제안 실행은 적지 않으므로 그 안에서는 이 도구들을 쓸 수 없다
    - 이 결정 전에 Hermes 가 session 을 정한 대화에서 압축 교체가 일어났으면 뿌리가 우리 기록과 달라 거절된다
    - **Control Plane 이 시작하지 않은 실행의 호출은 거절된다.** Hermes cron, 다른 채팅 플랫폼 gateway 처럼 우리가 실행 줄을 만들지 않은 run 에는 도는 부모 실행이 없다. 누가 요청했는지 알 수 없으므로 거절이 의도한 동작이다. 옛 토큰으로 설정이 참인 동안에는 전처럼 그 토큰의 사용자로 돈다
    - 토큰 하나를 여러 profile 설정에 함께 두던 운영은 profile 마다 토큰을 따로 발급해야 한다
- **적용 범위**: 부모 실행을 찾는 방법과 `_fos_ctx` 의 서명은 [ADR-031](ADR-031-mcp-호출의-부모-실행은-profile-플러그인이-서명한-뿌리-session-으로-잇는다.md) 을 그대로 따른다. 서명할 글과 key 는 바꾸지 않는다.

### `delegation_key`

`v1`, 부모 실행의 `profile_name`, `root_session_id`, `session_id`, `tool_call_id` 를 이 순서로 줄바꿈(`\n`) 하나로 이은 UTF-8 의 SHA-256 소문자 16진수 64자다.

| 칸 | 넣는 까닭 |
| --- | --- |
| `v1` | 정의를 바꿀 때 옛 키와 섞이지 않게 한다 |
| `profile_name` | 이 결정 전 대화의 뿌리 session 은 Hermes 가 정한 값이라 profile 사이에서 유일하다고 보장하지 못한다 |
| `root_session_id` | 뿌리가 다른 대화의 호출과 나눈다 |
| `session_id` | 같은 뿌리 아래 하위 에이전트마다 session 이 다르고, `tool_call_id` 가 session 사이에서 겹칠 수 있다 |
| `tool_call_id` | Hermes 가 같은 호출을 다시 보내도 같다 |

같은 호출의 재시도는 다섯 칸이 모두 같아 같은 키가 되고, 실행은 하나만 생긴다.
서버 안에서만 쓰는 값이라 플러그인이 서명할 글과는 무관하다.

### 옛 토큰에서 옮겨 가는 길

`profile_name` 이 빈 토큰은 이 결정 전에 사용자 기준으로 발급된 것이다.
설정 `assistant.mcp.legacy-user-tokens` 가 참일 때만 그 토큰의 `user_id` 로 전처럼 돈다. 쓰일 때마다 경고 로그를 남긴다.
거짓이면 인증에서 거절한다. 기본값은 거짓이다.

**토큰에 profile 을 묶는 것이 그 profile 의 전환 스위치다.** 묶인 토큰에는 옛 경로가 없다.

1. 백엔드를 배포하고 운영 설정에서 `legacy-user-tokens` 를 참으로 둔다
2. profile 마다 플러그인이 Control Plane MCP 의 모든 도구에 `_fos_ctx` 를 붙이는지 확인한다
3. 관리 API 로 그 profile 의 토큰에 profile 을 묶는다. 그 순간부터 그 profile 은 `_fos_ctx` 가 필수다. 묶을 때 `user_id` 를 비운다. `user_id` 를 읽는 옛 판의 서버로 되돌려도 묶인 토큰은 인증에서 거절된다
4. 모든 토큰이 묶이면 설정을 거짓으로 돌린다
5. 다음 변경에서 설정과 `agent_token.user_id` 칸을 지운다

되돌릴 때는 설정을 참으로 돌린다. 이미 묶인 토큰은 풀지 않는다. 새 토큰은 옛 방식으로 발급할 수 없다.
