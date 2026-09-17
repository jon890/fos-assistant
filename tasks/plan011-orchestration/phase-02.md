# Phase 02. 가장 작은 다중 에이전트 실행 하나를 만든다

**Execution profile**: deep

## 목표

에이전트 하나가 다른 에이전트를 불러 그 결과를 합치는 실행을 만든다.
하나만 만든다.

**범위 외**:
범용 workflow 엔진을 만들지 않는다.
Task 를 쪼개는 일반 기능, 의존 그래프, 재시도 정책을 만들지 않는다.
화면은 phase-03 이 만든다.

## Blocked 조건

phase-01 의 ADR 이 받아들여지지 않았으면
`PHASE_BLOCKED: orchestration 방식을 정하는 ADR 이 아직 받아들여지지 않았다` 를 내고 멈춘다.
그 ADR 이 A, B, C 중 무엇을 골랐는지에 따라 이 phase 의 구현이 갈린다.

## 컨텍스트

만들 흐름 하나다.

```
사용자 요청 → Chief → Researcher 와 Engineer 를 나란히 → Synthesizer → 최종 답
```

**처음부터 범용으로 만들지 않는다.**
이 흐름 하나가 끝까지 도는 것을 먼저 본다.
그 과정에서 무엇이 어려운지 알게 되고, 그것을 알고 나서 일반화한다.

바닥은 이미 있다.

| 무엇 | 어디 |
| --- | --- |
| 실행을 시작할 때 만들고 끝날 때 갱신 | plan008 의 phase-02 |
| `parent_execution_id` 와 `root_execution_id` | 같은 곳 |
| 실행 사건 저장 | plan010 의 phase-01 |
| 실행 나무 조회 | plan010 의 phase-02 |

**근거 문서**: phase-01 이 만든 ADR,
`docs/adr/ADR-011-실행은-시작할-때-기록하고-끝날-때-갱신한다.md`,
`docs/adr/ADR-014-실제-청구액과-환산액을-나눠-적는다.md`,
`docs/data-schema.md` 의 「agent_execution」 절

## 의도 메모

- 에이전트 넷을 새로 만들지 않는다.
  지금 `bifos` 와 `career` 둘뿐이고, 역할이 다른 profile 을 넷 만들면
  그 자체로 홈서버 작업이 커진다.
  같은 profile 을 다른 `instructions` 로 부르는 것으로 시작한다.
- 실패하면 거기서 멈춘다. 재시도하지 않는다.
  재시도를 넣으면 무엇이 몇 번 돌았는지가 비용과 얽히고, 그것을 이 단계에서 풀지 않는다.
- 병렬은 둘까지다. Researcher 와 Engineer 만 나란히 돈다.
  `career` profile 의 `max_concurrent_sessions` 가 1 이다.
  같은 profile 로 둘을 동시에 부르면 한쪽이 기다린다. 그 사실을 측정해 보고한다.

## 작업 항목

### 1. 자식 실행을 만드는 자리

`backend/src/main/java/com/bifos/assistant/orchestration/` 아래에 둔다.
새 도메인이므로 `presentation`, `application`, `domain`, `infra` 배치를 따른다.

```java
/**
 * 에이전트 하나가 다른 에이전트를 부르는 실행을 만든다.
 *
 * <p>자식 실행도 부모와 같은 사용자의 것이다. 요청 본문이 그것을 바꾸지 못한다.
 */
@Service
public class ChildExecutionRunner {

    /**
     * 부모 실행 아래에 자식 실행 하나를 돌린다.
     *
     * @param parent 이 실행을 부른 실행
     * @param instructions 이 자식에게만 주는 지시
     */
    public HermesRunResult run(
            CurrentUser user, Conversation conversation, Agent agent,
            AgentExecution parent, String instructions);
}
```

**경계를 지킨다.**

- `user` 는 부모의 사용자다. 다른 사용자로 바꾸는 인자를 두지 않는다
- `agent` 는 그 사용자가 쓸 수 있는 것만 온다. `AgentService.requireReadable` 을 지난다
- `instructions` 는 `ContextAssembler` 가 만든 것 위에 얹는다.
  자식이라고 Memory 경계가 느슨해지지 않는다
- `parentExecutionId` 는 부모의 `id`, `rootExecutionId` 는
  부모의 `rootExecutionId` 가 있으면 그것, 없으면 부모의 `id` 다

### 2. 흐름 하나를 정의한다

`orchestration/application/ResearchAndBuildFlow.java` 다.
이름은 흐름의 내용을 가리킨다. `Flow1` 처럼 번호로 두지 않는다.

```java
/**
 * 조사와 구현을 나란히 돌리고 그 둘을 합쳐 답한다.
 *
 * <p>이 흐름 하나만 있다. 범용 엔진이 아니다.
 */
@Service
public class ResearchAndBuildFlow {

    public ChatTurn run(CurrentUser user, Long conversationId, String text);
}
```

단계는 넷이다.

| 단계 | 하는 일 | 부모 |
| --- | --- | --- |
| Chief | 요청을 읽고 조사할 것과 만들 것을 나눈다 | 없음. 이것이 뿌리다 |
| Researcher | 조사한다 | Chief |
| Engineer | 만든다 | Chief |
| Synthesizer | 둘을 합쳐 최종 답을 만든다 | Chief |

Researcher 와 Engineer 는 나란히 돈다.
`CompletableFuture` 로 둘을 띄우고 둘 다 끝나면 Synthesizer 로 간다.

**한쪽이 실패하면 다른 쪽을 기다렸다가 거기서 멈춘다.**
돌고 있는 것을 중간에 끊지 않는다. 끊어도 Hermes 쪽 실행은 계속 돌고 토큰은 이미 쓰인다.
멈출 때 부모 실행을 `FAILED` 로 갱신하고 어느 자식이 실패했는지 `error_code` 에 적는다.

### 3. 부르는 경로

이 흐름을 어떻게 시작할지 정한다.

**새 API 를 열지 않는다.** 에이전트 하나를 이 흐름에 묶는다.
`agent` 표에 `flow` 칸을 더하고, 그 칸이 있는 에이전트로 대화를 시작하면
`ChatService` 가 `ResearchAndBuildFlow` 로 보낸다.

```sql
ALTER TABLE agent ADD COLUMN flow VARCHAR(64) NULL;
```

`flow` 가 비어 있으면 지금처럼 Hermes 를 한 번 부른다.
값이 있으면 그 이름의 흐름으로 간다. 지금은 `research-and-build` 하나다.

모르는 이름이면 기동할 때 실패시킨다. 실행할 때가 아니라 기동할 때다.
잘못 적힌 이름을 배포한 뒤 사용자가 그 에이전트를 고를 때 알게 되면 늦다.

### 4. 사용량이 자식을 포함한다

`GET /api/v1/usage/executions` 는 지금 실행을 평평하게 낸다.
자식이 생기면 목록이 자식으로 찬다.

**뿌리만 목록에 낸다.** `rootExecutionId` 가 비어 있는 것만이다.
자식은 phase-03 의 나무 화면에서 본다.

월 비용 합계는 자식을 **포함한다.** 실제로 쓴 토큰이기 때문이다.
plan010 의 phase-02 가 만든 나무 조회는 그대로 쓴다.

**phase-01 에서 자식 토큰이 부모에 이미 포함되는 것으로 확인됐다면 이 결정을 뒤집는다.**
그 경우 합계에서 자식을 빼야 두 번 세지 않는다.
어느 쪽인지 ADR 에 적혀 있다. 그것을 읽고 따른다.

### 5. 이 phase 를 검증하는 테스트

`backend/src/test/java/com/bifos/assistant/orchestration/ResearchAndBuildFlowTest.java` 를 새로 만든다.

- **정상 경로**: 흐름 한 번이 실행 넷을 남긴다.
  Chief 가 뿌리이고 나머지 셋의 `rootExecutionId` 가 Chief 를 가리킨다
- Researcher 와 Engineer 의 `parentExecutionId` 가 Chief 다
- **이 phase 가 다루는 실패**: Researcher 가 실패하면 Engineer 가 끝날 때까지 기다린 뒤
  Chief 가 `FAILED` 로 갱신되고, Synthesizer 는 돌지 않는다
- **경계**: 자식 실행의 `userId` 가 전부 부모와 같다
- 남의 에이전트를 흐름에 넣으려 하면 `AGENT_NOT_FOUND` 다

`backend/src/test/java/com/bifos/assistant/orchestration/ChildExecutionRunnerTest.java` 를 새로 만든다.

- 자식의 `instructions` 에 요청자가 볼 수 있는 Memory 만 들어 있다.
  **다른 구성원의 개인 Memory 가 들어 있지 않은 것을 단언문으로 고정한다**

`test/e2e/scenarios/orchestration.ts` 를 새로 만들고 `test/e2e/run.ts` 의 목록에 더한다.

- `flow` 가 붙은 에이전트로 대화하면 실행 넷이 남고 나무로 조회된다
- `flow` 가 없는 에이전트는 지금처럼 실행 하나만 남는다

## 검증

```bash
# cwd: 저장소 root
cd backend && ./gradlew test
node test/e2e/run.ts
cd web && pnpm typecheck && pnpm build
```

```bash
# cwd: 저장소 root
cd backend && ./gradlew test --tests '*ResearchAndBuildFlowTest*'
```

**가짜 Hermes 로 통과해도 운영에서 도는지는 모른다.**
실제로 그렇게 스트리밍이 통째로 동작하지 않은 채 배포된 적이 있다.
배포한 뒤 실제로 한 번 왕복시켜 아래를 보고에 적는다.

- 실행 넷이 실제로 남았는지
- `career` 의 `max_concurrent_sessions` 가 1 이라 병렬 둘이 실제로는 줄서는지.
  Researcher 와 Engineer 의 `startedAt` 과 `finishedAt` 을 견준다
- 넷의 토큰 합계와 걸린 시간

## Critical Files

| 파일 | 변경 |
|---|---|
| `backend/src/main/resources/db/migration/V9__agent_flow.sql` | 신규 |
| `backend/src/main/java/com/bifos/assistant/orchestration/application/ChildExecutionRunner.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/orchestration/application/ResearchAndBuildFlow.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/agent/domain/Agent.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/application/ChatService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/usage/infra/AgentExecutionRepository.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/orchestration/ResearchAndBuildFlowTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/orchestration/ChildExecutionRunnerTest.java` | 신규 |
| `test/e2e/scenarios/orchestration.ts` | 신규 |
| `test/e2e/run.ts` | 수정 |
