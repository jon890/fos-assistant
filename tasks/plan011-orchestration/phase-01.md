# Phase 01. 자식 실행을 만드는 자리를 만든다

**Execution profile**: deep

## 목표

에이전트 하나가 다른 에이전트를 부르는 경로를 만든다.
자식 실행이 부모와 같은 사용자의 것이고 같은 경계를 물려받는 것을 테스트로 고정한다.

**범위 외**:
흐름을 잇는 것은 phase-02 가 한다. 이 phase 는 자식 하나를 돌리는 자리만 만든다.
화면은 phase-03 이 만든다.
Task 를 나누는 일반 기능을 만들지 않는다.

## 컨텍스트

[ADR-016](../../docs/adr/ADR-016-다중-에이전트-조율은-control-plane이-맡는다.md) 이
Task 분해와 에이전트 선택과 병렬 실행을 Control Plane 이 맡기로 정했다.
Hermes 는 `agent_execution` 하나를 실행하는 런타임으로만 쓴다.

그 ADR 이 고른 근거가 경계 상속이다.
요청자의 에이전트 바인딩, Memory 접근 권한, credential 경계를
모든 실행에 같은 값으로 적용해야 하는데,
Hermes 안에서 자식이 만들어지면 Control Plane 이 그 자식이 누구의 것인지 알 방법이 없다.

**자식 실행을 top-level Runs API 실행으로 만든다.**
그래야 기존 실행 기록에 `usage` 가 남는다.
ADR-016 이 그것을 「확인」 으로 적었다.

바닥은 이미 있다.

| 무엇 | 어디 |
| --- | --- |
| 실행을 시작할 때 만들고 끝날 때 갱신 | plan008 |
| `parent_execution_id` 와 `root_execution_id` | plan008 |
| 요청자가 볼 수 있는 Memory 만 조립 | plan009 의 `ContextAssembler` |
| 실행 사건 저장 | plan010 |

**근거 문서**: `docs/adr/ADR-016-다중-에이전트-조율은-control-plane이-맡는다.md`,
`docs/adr/ADR-011-실행은-시작할-때-기록하고-끝날-때-갱신한다.md`,
`docs/adr/ADR-003-memory-권한은-주입으로-강제한다.md`,
`docs/data-schema.md` 의 「agent_execution」 절

## 의도 메모

- Hermes 의 `delegate_task` 를 쓰지 않는다.
  ADR-016 이 확인한 것이다. `max_spawn_depth` 가 1이라 첫 자식이 이미 상한에 닿고,
  그 자식의 토큰이 부모 `usage` 에 들어가지 않는다.
  Control Plane 이 직접 부르면 각 실행이 자기 기록을 갖는다.
- 자식에게 줄 지시를 요청 본문이 정하지 못하게 한다.
  모델이 만든 Task 가 profile 을 직접 고르면 경계가 무너진다.
  에이전트는 Control Plane 이 요청자의 바인딩에서 고른다.
- 깊이를 1로 제한한다. 자식이 다시 자식을 부르지 않는다.
  필요해지는 시점은 흐름 하나를 돌려 보고 정한다.

## 작업 항목

### 1. `orchestration` 패키지를 만든다

`backend/src/main/java/com/bifos/assistant/orchestration/` 아래다.
다른 도메인과 같은 배치를 따른다.

```java
/**
 * 부모 실행 아래에서 다른 에이전트를 한 번 돌린다.
 *
 * <p>자식도 부모와 같은 사용자의 것이다. 요청 본문이나 모델의 출력이 그것을 바꾸지 못한다.
 */
@Service
public class ChildExecutionRunner {

    /**
     * @param parent 이 실행을 부른 실행
     * @param agentCode 자식이 쓸 에이전트. 요청자가 쓸 수 있는 것만 통과한다
     * @param task 이 자식에게만 주는 지시
     */
    public ChildResult run(
            CurrentUser user, Conversation conversation,
            AgentExecution parent, String agentCode, String task);
}
```

```java
/** 자식 실행 하나의 결과. 실패해도 실행 줄은 남는다. */
public record ChildResult(Long executionId, boolean succeeded, String output, String errorCode) {}
```

**경계를 지키는 규칙이 넷이다. 각각 테스트로 고정한다.**

| 규칙 | 어떻게 |
| --- | --- |
| 사용자 | `user` 는 부모의 것이다. 바꾸는 인자를 두지 않는다 |
| 에이전트 | `AgentService.requireReadable(user, agentCode)` 를 지난다 |
| Memory | `ContextAssembler.assemble(user)` 로 다시 조립한다. 부모 것을 복사하지 않는다 |
| 실행 관계 | `parentExecutionId` 는 부모의 `id`, `rootExecutionId` 는 부모의 뿌리 |

`rootExecutionId` 를 정하는 규칙이다.
부모의 `rootExecutionId` 가 있으면 그것을 쓰고, 없으면 부모의 `id` 를 쓴다.
부모가 뿌리이면 자기 `rootExecutionId` 는 비어 있기 때문이다.

### 2. 깊이를 제한한다

부모의 `rootExecutionId` 가 이미 채워져 있으면 그 부모는 자식이다.
자식이 다시 자식을 부르는 것을 막는다.

```java
if (parent.rootExecutionId() != null) {
    throw new ApiException(ErrorCode.ORCHESTRATION_DEPTH_EXCEEDED, "a child cannot spawn another child");
}
```

`ORCHESTRATION_DEPTH_EXCEEDED` 를 `ErrorCode` 에 더한다.

### 3. 자식도 실행 기록을 남긴다

`ExecutionRecorder` 를 그대로 쓴다. 새 기록 경로를 만들지 않는다.

- `start(...)` 에 `parentExecutionId` 와 `rootExecutionId` 를 넘긴다
- 끝나면 `complete(...)` 또는 `fail(...)`
- `contextChars` 도 부모와 같은 방식으로 적는다

**자식 실행의 토큰은 부모 `usage` 에 들어 있지 않다.**
ADR-016 이 실측으로 확인했다.
부모 Runs API 가 16,583 토큰을 보고했고, 자식 session 이 따로 6,544 토큰을 썼다.
그러므로 각 실행이 자기 토큰을 그대로 적으면 두 번 세지 않는다.

### 4. 자식의 답을 대화 이력에 남기지 않는다

`chat_message` 에 자식의 답을 넣지 않는다.
사용자가 읽을 답은 마지막에 합친 하나다.
중간 산출물을 이력에 넣으면 대화 화면이 그것으로 찬다.

자식의 답은 `ChildResult.output` 으로 부르는 쪽에 돌려주고,
그 실행 기록과 `execution_event` 로 남는다.

### 5. 이 phase 를 검증하는 테스트

`backend/src/test/java/com/bifos/assistant/orchestration/ChildExecutionRunnerTest.java` 를 새로 만든다.

- **정상 경로**: 자식 실행 하나가 `SUCCEEDED` 로 남고
  `parentExecutionId` 와 `rootExecutionId` 가 부모를 가리킨다
- 부모가 이미 자식이면 `ORCHESTRATION_DEPTH_EXCEEDED` 다
- **경계 1**: 자식의 `userId` 가 부모와 같다
- **경계 2**: 요청자가 쓸 수 없는 에이전트 코드를 주면 `AGENT_NOT_FOUND` 다
- **경계 3**: 자식의 `instructions` 에 다른 구성원의 개인 Memory 가 없다.
  `doesNotContain` 으로 고정한다
- **이 phase 가 다루는 실패**: 자식이 실패해도 실행 줄이 `FAILED` 로 남고
  예외가 부모의 흐름을 끊지 않는다. `ChildResult.succeeded` 가 거짓이다
- 자식의 답이 `chat_message` 에 들어가지 않는다

## 검증

```bash
# cwd: 저장소 root
cd backend && ./gradlew test
node test/e2e/run.ts
cd web && pnpm typecheck
```

```bash
# cwd: 저장소 root
cd backend && ./gradlew test --tests '*ChildExecutionRunnerTest*'
```

## Critical Files

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/orchestration/application/ChildExecutionRunner.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/orchestration/domain/ChildResult.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/shared/error/ErrorCode.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/orchestration/ChildExecutionRunnerTest.java` | 신규 |
