## ADR-016: 다중 에이전트 조율은 Control Plane이 맡는다

- **status**: `accepted`
- **결정**: Task 분해, 에이전트 선택, 의존 관계, 병렬 실행, 재시도와 결과 종합을
  Control Plane이 맡는다.
  Hermes는 `agent_execution` 하나를 실행하는 런타임으로만 쓴다.
  Control Plane의 다중 에이전트 경로에서는 Hermes Kanban과 내장 delegation을 쓰지 않는다.

### 맥락

만들려는 흐름은 Chief가 받은 요청을 Orchestrator가 Task로 나누고,
Specialist와 Worker가 조사와 구현을 병렬로 수행한 뒤
Reviewer와 Synthesizer가 결과를 검토하고 하나로 합치는 구조다.

이 구조에서 가장 중요한 것은 병렬 실행 자체가 아니다.
요청자의 에이전트 바인딩, Memory 접근 권한과 credential 경계를
모든 실행에 같은 값으로 적용해야 한다.
요청 본문이나 모델이 만든 Task가 Hermes profile을 직접 정하면 이 경계가 무너진다.

Control Plane에는 이미 실행마다 `user_id`, `agent_id`, `profile_name`,
`parent_execution_id`와 `root_execution_id`를 남기는 자리가 있다.
Memory도 실행마다 요청자를 기준으로 다시 고르고 `instructions`로 주입한다.
다중 에이전트도 이 경로를 반복해서 써야 한다.

### Kanban과 내장 orchestrator의 관계

**둘은 다른 기능이다.**

Hermes Kanban은 profile을 작업자로 쓰는 SQLite 작업 보드다.
`kanban decompose`와 `kanban swarm`이 Task를 만들고,
`task_links`가 의존 관계를, `task_runs`가 실행 시도와 실패 상태를 남긴다.
`swarm`은 worker, verifier와 synthesizer profile을 각각 받는다.

`delegation.orchestrator_enabled`는 `delegate_task`로 만든 자식이
다시 자식을 만들 수 있는지를 정하는 설정이다.
Kanban을 켜는 설정이 아니며 Task 보드와도 연결되지 않는다.

배포 설정의 `max_spawn_depth`는 1이다.
배포 소스 `tools/delegate_tool.py`의 `_build_child_agent`에 따르면
첫 자식의 깊이가 이미 상한에 닿으므로 자식은 leaf가 된다.
따라서 현재 `orchestrator_enabled: true`는 중첩 실행을 열지 않는다.
delegation toolset도 비활성 상태라 부모가 `delegate_task`를 사용할 수 없다.

`max_async_children`은 같은 파일의 `_get_max_async_children`에서
폐기된 설정으로 처리된다.
현재 동시 실행 상한은 `max_concurrent_children` 하나가 정한다.

### 확인한 기능과 설계 판단

아래 표에서 **확인**은 배포본 도움말, 소스나 실측으로 확인한 내용이다.
**판단**은 선택한 방식을 구현할 때 필요한 설계다.

| 요구 | A. Kanban | B. Plugin API | C. Control Plane |
| --- | --- | --- | --- |
| Task 분해 | 확인: `decompose`와 `swarm`이 Task를 만든다 | 확인: Kanban 기능을 그대로 부를 수 있다 | 판단: Orchestrator 실행의 출력 계약을 정하고 Control Plane이 검증한다 |
| 에이전트 선택 | 확인: Task의 assignee가 profile이다 | 판단: Plugin이 요청자의 허용 에이전트만 profile로 바꿔야 한다 | 판단: 모델은 역할만 제안하고 Control Plane이 요청자의 바인딩에서 에이전트를 고른다 |
| 의존 Task 그래프 | 확인: `task_links`가 부모와 자식을 잇는다 | 확인: Kanban 그래프를 그대로 쓸 수 있다 | 판단: Control Plane이 Task와 의존 관계를 저장하고 준비된 Task만 실행한다 |
| 병렬 실행 | 확인: dispatcher가 준비된 profile 작업자를 병렬로 띄운다 | 확인: 같은 dispatcher를 쓸 수 있다 | 판단: 의존 관계가 없는 실행을 정한 상한 안에서 함께 제출한다 |
| 실패와 재시도 | 확인: `task_runs`에 실패 상태가 있고 Task에 재시도 상한이 있다 | 확인: Kanban 상태를 API로 내보낼 수 있다 | 판단: 실행 상태와 재시도 횟수를 Control Plane이 저장한다 |
| 결과 합치기 | 확인: `swarm`이 verifier와 synthesizer Task를 만든다 | 확인: 같은 Task를 API로 만들 수 있다 | 판단: Reviewer와 Synthesizer를 의존 Task로 실행한다 |
| 부모와 뿌리 연결 | 확인: Kanban 안의 Task와 run은 연결되지만 `agent_execution`과는 연결되지 않는다 | 판단: Plugin이 Kanban 식별자와 실행 식별자를 따로 연결해야 한다 | 확인: 기존 `parent_execution_id`와 `root_execution_id`에 직접 기록할 수 있다 |
| 사용량과 비용 | 확인: `task_runs`에는 토큰과 비용 칸이 없다 | 판단: Plugin hook으로 실행별 사용량을 따로 모아야 한다 | 확인: 각 Task를 top-level Runs API 실행으로 만들면 기존 실행 기록에 usage를 남길 수 있다 |
| 사용자, Memory와 credential 경계 | 확인: `tenant`는 문자열이고 assignee profile은 호출자가 정한다 | 판단: Plugin이 Control Plane의 권한 검사를 다시 구현해야 한다 | 확인: 기존 바인딩 확인, Memory 주입과 profile key 조회 경로를 실행마다 그대로 쓸 수 있다 |

### 자식 토큰 실측

2026년 9월 17일에 Hermes Agent v0.21.0 배포본으로 측정했다.
delegation을 잠시 켜고 자식 하나를 실행한 뒤 바로 원복했다.
변경 전과 원복 뒤는 모두 도구 13개와 도구 정의 17,379바이트였고,
delegation이 비활성 상태인 것도 다시 확인했다.

자식을 쓰지 않은 대조 실행은 API 호출 한 번에 입력 8,027토큰과
출력 6토큰을 써서 합계가 8,033토큰이었다.

자식을 쓴 실행에서 부모 Runs API는 입력 16,487토큰과
출력 96토큰, 합계 16,583토큰을 보고했다.
부모의 모델 사용 기록은 일반 입력 8,551토큰, cache read 7,936토큰과
출력 96토큰이었다.
`8551 + 7936 + 96 = 16583`이므로 Runs API 값은 부모가 직접 쓴 토큰과 정확히 같다.

별도 subagent session은 입력 6,537토큰과 출력 7토큰,
합계 6,544토큰을 썼고 부모 session을 가리켰다.
부모와 자식의 실제 합계는 23,127토큰이다.
따라서 **자식 토큰은 부모 Runs API의 usage에 포함되지 않는다.**

소스도 실측과 같은 동작을 한다.
`gateway/platforms/api_server_runs.py`는 부모 agent의 token 누계로 usage를 만든다.
`tools/delegate_tool.py`의 `_finalize_child_results`는 자식 비용을 부모 비용에 더하지만,
자식 token을 부모의 token 누계에는 더하지 않는다.

Hermes 내장 delegation을 쓰면서 부모 usage만 저장하면 6,544토큰을 잃는다.
자식 session을 별도 `agent_execution`으로 받는 API도 현재 없다.
선택한 방식은 각 Task를 top-level Runs API 실행으로 제출하므로
모든 Task의 usage를 실행별로 저장한 뒤 합칠 수 있다.

### 경계 상속

내장 delegation의 자식은 부모 provider와 credential을 기본으로 물려받고,
자식 toolset은 부모 toolset의 범위를 넘지 않는다.
같은 profile의 session 저장소에 별도 session으로 남고 부모 session도 기록한다.

하지만 `tools/delegate_tool.py`의 `_build_child_agent`는 자식을
`skip_memory=True`로 만들고, 자식 system prompt를 goal과 명시적으로 넘긴 context로 새로 만든다.
Control Plane이 부모의 `instructions`에 넣은 Memory를 자동으로 물려주는 경로는 확인하지 못했다.
실제 개인 Memory를 넣은 자식 실행은 하지 않았으므로 이 부분은 소스에 따른 판단이다.

Kanban은 더 어렵다.
worker가 assignee profile의 별도 프로세스로 실행되므로 그 profile의 credential과 설정을 읽는다.
Kanban의 `tenant`가 Control Plane의 인증 사용자와 같다는 보장은 없고,
Task 본문에 Memory를 넣으면 누가 읽을 수 있는지를 Kanban과 Plugin에서도 다시 검사해야 한다.

Control Plane 방식에서는 실행마다 원래 요청자의 `user_id`를 유지한다.
Task가 요구하는 역할을 요청자가 쓸 수 있는 에이전트로 바꾼 뒤,
그 에이전트의 서버 쪽 바인딩에서만 profile을 꺼낸다.
Memory도 같은 요청자의 개인 범위와 가족 공용 범위에서 실행마다 다시 조립한다.
모델 출력과 요청 본문은 profile, credential이나 Memory 범위를 정하지 못한다.

### 대안 기각

- **A. Hermes Kanban을 그대로 쓴다.**
  Task 그래프, 병렬 실행, 재시도와 종합자는 이미 있다.
  그러나 현재 Runs API에 Kanban endpoint가 없고,
  Kanban의 Task와 run이 `agent_execution`의 부모와 뿌리에 연결되지 않는다.
  실행 시도에는 사용량도 없으며 사용자와 Memory 경계도 Control Plane과 연결되지 않는다.
- **B. Hermes Plugin으로 orchestration API를 노출한다.**
  배포본에는 Kanban dashboard용 HTTP route 소스가 있지만 Runs API endpoint는 아니다.
  별도 Plugin을 만들면 Kanban 기능을 재사용할 수 있으나,
  요청자 확인, 허용 에이전트 선택, Memory 주입, 실행 식별자 연결과 사용량 수집을
  Hermes 쪽에 다시 만들어야 한다.
  정책을 두 곳에서 유지하면 한쪽 수정이 빠질 때 사용자 경계가 달라진다.
- **Hermes 내장 delegation을 orchestration으로 쓴다.**
  Kanban과 다른 기능이며 현재 설정에서는 leaf 한 단계만 실행할 수 있다.
  내구성 있는 Task 그래프와 재시도 상태가 없고 자식 token도 부모 usage에서 빠진다.
  부모에게 주입한 Memory가 자식에게 자동으로 전달되는 것도 확인하지 못했다.

### 결과

얻는 것

- Chief, Orchestrator, Specialist, Worker, Reviewer와 Synthesizer를
  모두 같은 `agent_execution` 나무에서 볼 수 있다.
- 조사와 구현처럼 의존 관계가 없는 Task를 병렬로 실행하고,
  Reviewer와 Synthesizer는 선행 실행이 끝난 뒤 시작할 수 있다.
- 모든 실행이 기존 사용자 확인, 에이전트 바인딩, Memory 주입과 credential 조회를 거친다.
- Task마다 Hermes가 보고한 usage를 한 번만 저장한다.
  부모와 자식 합계는 Control Plane이 만든 실행 나무의 행을 더하므로 빠지거나 두 번 세지 않는다.
- Hermes core를 고치지 않고 기존 Runs API만 사용한다.

감당할 것

- Kanban이 이미 가진 Task 보드, scheduler와 재시도를 Control Plane에 새로 만들어야 한다.
- Orchestrator가 내놓을 Task 형식, 중복 제출 방지, 취소와 재시도 규칙은 아직 정하지 않았다.
- 어떤 실패에서 같은 에이전트를 다시 쓸지 다른 에이전트로 바꿀지도 아직 정하지 않았다.
- Reviewer가 수정을 요구할 때 어느 Task까지 다시 실행할지 정해야 한다.
- Hermes 버전을 올릴 때 Runs API usage와 내장 delegation의 token 집계 방식을 다시 측정한다.
- Kanban Plugin이 사용자별 Memory를 안전하게 주입할 수 있는지는 실제로 확인하지 못했다.
  이 대안을 다시 검토하려면 먼저 사용자와 credential 경계의 실증이 필요하다.
