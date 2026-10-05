# Phase 06. e2e 시나리오

**Execution profile**: deep

## 목표

홈서버 없이 도는 e2e 가 새 흐름 전체를 지난다: 계정 연결, 일반 에이전트에 붙이기, 관리자 반영 완료, 그 에이전트의 직접 호출 판정, 승인한 호출의 실행, 떼기, 먼저 살펴보기의 직접 호출.
연결 등록이 에이전트를 만든다고 전제한 시나리오를 새 흐름으로 고친다.

**범위 외**: 책임 문서(phase 07).

## 컨텍스트

**근거 문서**: `docs/adr/ADR-083-커넥터는-사용자가-한-번-연결하고-자기-에이전트에-여럿-붙여-그-에이전트가-도구를-직접-부른다.md`, `docs/connectors.md`, `docs/backend/proactive-check.md`

- 결정: `docs/adr/ADR-083-커넥터는-사용자가-한-번-연결하고-자기-에이전트에-여럿-붙여-그-에이전트가-도구를-직접-부른다.md`
- 실행: `node test/e2e/run.ts` 가 `test/e2e/scenarios/` 의 시나리오를 차례로 돌린다. 시나리오는 `run.ts` 의 목록에 import 해 넣는다
- 대역: `test/e2e/fake-hermes.ts`. `DEMO_CONNECTOR`, `connectorRequests()`, `connectorToolCalls()`, `setConnectorPolicy(endpoint)` 가 있다. phase 05 가 보관 파일과 바인딩 설치를 흉내 내게 고쳤다
- 연결 등록이 에이전트를 만든다고 전제한 시나리오
  - `test/e2e/scenarios/connector.ts`(`connectorScenario`): 등록 뒤의 `agentCode`
  - `test/e2e/scenarios/connector-policy.ts`(`connectorPolicyScenario`): 커넥터 에이전트의 profile 로 판정을 묻는다
  - `test/e2e/scenarios/connector-delegation.ts`(`connectorDelegationScenario`): 커넥터 에이전트에 맡긴다
  - `test/e2e/scenarios/proactive-check.ts`(`proactiveCheckScenario`): 등록한 커넥터의 에이전트를 찾아 `agent_delegate` 와 `agent_status wait_seconds` 로 맡긴다
  - `test/e2e/scenarios/delivery-retry.ts`, `test/e2e/scenarios/notifications.ts`, `test/e2e/scenarios/scheduled-task.ts`: 연결 응답의 `agentCode` 로 커넥터 에이전트를 찾아 쓴다
- 옛 커넥터 에이전트의 경계(Memory 생략, Control Plane MCP 거절, 결과 감싸기)는 backend 단위 시험(`AgentRunnerConnectorContextTest`, `McpCallerResolverTest`, `McpAgentToolsTest`)이 지킨다. 새 연결이 그 에이전트를 만들지 않으므로 e2e 로는 만들 수 없다

## 의도 메모

- `connector-delegation.ts` 는 지운다. 그 흐름(새 연결의 커넥터 에이전트에 맡기기)은 더는 생기지 않는다. 남는 옛 에이전트의 경계는 위 단위 시험이 지킨다
- 시나리오는 비밀 칸의 값이 응답 본문, 화면용 응답, 대역이 받은 요청 로그 가운데 Control Plane 응답에 나오지 않는 것도 본다

## 작업 항목

### 1. `test/e2e/scenarios/connector.ts`

등록 응답에 `agentCode` 가 없고 연결이 `READY` 이며 `bindings` 가 빈 목록이다. 값 교체, 확인, 해제의 흐름은 지금 단언을 새 응답 모양으로 고친다. 등록이 보관 파일 쓰기를 한 번 부르고 profile 을 만들지 않는다.

### 2. `test/e2e/scenarios/connector-binding.ts`(신규, `connectorBindingScenario`)

1. 사용자가 연결을 등록한다
2. 자기 비공개 에이전트에 붙인다. 대역이 받은 설치 요청에 `bind.vault` 가 있고 그 profile 의 `api_server` 에 서버 이름이 더해지며 Control Plane MCP 가 남는다. 바인딩은 `PENDING`, 재시작 필요다
3. 이 상태에서 그 에이전트의 실행이 커넥터 도구를 부르면 판정이 `NOT_READY` 로 막는다
4. 관리자가 반영 완료를 누르면 `READY` 다
5. 같은 에이전트의 실행이 읽기 도구를 부르면 허용되고, 쓰기 도구는 승인 줄이 된다. 승인하면 대역의 실행 경로가 그 에이전트의 profile 로 한 번 불린다
6. 그룹 공개로 바꾸려 하면 `AGENT_CONNECTIONS_REQUIRE_PRIVATE` 다
7. 다른 사용자와 관리자는 그 에이전트에 붙이거나 떼지 못한다
8. 떼면 바인딩이 사라지고 같은 도구 호출이 줄 없이 막힌다
9. 연결을 해제하면 붙은 바인딩이 모두 떼어지고 보관 파일이 지워진다

`test/e2e/run.ts` 의 목록에 `connectorScenario` 뒤로 넣는다.

### 3. `test/e2e/scenarios/connector-policy.ts`

판정을 묻는 profile 을 「연결을 붙인 일반 에이전트의 profile」 로 바꾼다. 연결 둘을 붙인 에이전트에서 두 서버의 도구가 각자의 연결로 판정되는 경우를 더한다. 대역의 `DEMO_CONNECTOR` 하나뿐이면 둘째 커넥터를 대역에 더한다(`fake-hermes.ts` 수정).

### 4. `test/e2e/scenarios/connector-delegation.ts`

지우고 `run.ts` 에서 뺀다.

### 5. `agentCode` 에 기대던 시나리오 셋

`delivery-retry.ts`, `notifications.ts`, `scheduled-task.ts` 가 연결 응답의 `agentCode` 로 찾던 에이전트를, 시나리오가 만든 일반 비공개 에이전트에 연결을 붙이고 반영 완료한 것으로 바꾼다. 커넥터와 무관한 단언은 그대로 둔다. 붙이기 준비(연결 등록, 비공개 에이전트 만들기, 붙이기, 관리자 반영 완료)는 세 시나리오와 `connector-binding.ts`, `connector-policy.ts`, `proactive-check.ts` 가 함께 쓰도록 새 도움 파일 `test/e2e/connector-support.ts` 에 둔다. 본보기는 같은 디렉터리의 `test/e2e/delegation-support.ts` 다.

### 6. `test/e2e/scenarios/proactive-check.ts`

커넥터 부분을 직접 호출로 바꾼다. 살펴보기 에이전트에 연결을 붙이고 반영 완료한 뒤 살펴보기를 시작한다.
읽기 전용 살펴보기에서 대역의 모델이 커넥터 읽기 도구를 직접 부르면 허용되고, 쓰기 도구는 `READ_ONLY_RUN` 으로 거절되며 승인 줄이 생기지 않는다.
쓰기 허용 살펴보기에서는 쓰기 도구가 승인 줄이 된다. 위임 상한과 `wait_seconds` 단언 가운데 커넥터 에이전트에 기대던 것은 지운다. 커넥터와 무관한 단언은 그대로 둔다.

## 검증

```bash
node test/e2e/run.ts
cd backend && ./gradlew test
```

모두 종료 코드 0 이어야 한다.

## 변경 파일

| 파일 | 변경 |
| --- | --- |
| `test/e2e/run.ts` | 수정 |
| `test/e2e/connector-support.ts` | 신규 |
| `test/e2e/fake-hermes.ts` | 수정 |
| `test/e2e/scenarios/connector.ts` | 수정 |
| `test/e2e/scenarios/connector-binding.ts` | 신규 |
| `test/e2e/scenarios/connector-policy.ts` | 수정 |
| `test/e2e/scenarios/connector-delegation.ts` | 삭제 |
| `test/e2e/scenarios/proactive-check.ts` | 수정 |
| `test/e2e/scenarios/delivery-retry.ts` | 수정 |
| `test/e2e/scenarios/notifications.ts` | 수정 |
| `test/e2e/scenarios/scheduled-task.ts` | 수정 |
