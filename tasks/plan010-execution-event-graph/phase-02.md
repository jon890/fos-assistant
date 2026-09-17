# Phase 02. 실행 하나를 나무로 조회한다

**Execution profile**: standard

## 목표

실행 하나와 그 사건을 나무 모양으로 돌려주는 API 를 만든다.
부모와 자식 실행이 있으면 그 관계도 함께 낸다.

**범위 외**: 화면은 phase-03 이 만든다.

## 컨텍스트

phase-01 이 `execution_event` 에 사건을 쌓았다.
이 phase 는 그것을 화면이 그릴 수 있는 모양으로 내준다.

실행 하나가 가질 수 있는 구조는 둘이다.
하나는 그 실행 안의 도구 호출 순서이고, 다른 하나는 그 실행이 부른 다른 실행이다.
plan008 의 phase-02 가 `parent_execution_id` 와 `root_execution_id` 를 만들어 두었다.

**지금은 자식 실행을 만드는 경로가 없다.** plan011 이 만든다.
그래도 이 API 는 자식을 담을 수 있는 모양으로 낸다.
나중에 응답 형태가 바뀌면 화면을 함께 고쳐야 하기 때문이다.

Memory 제안 경로가 켜져 있으면 그것이 첫 자식 실행이 된다.
plan009 의 phase-03 이 만든 `MemoryProposer` 가 부모의 `id` 를 넘긴다.

**근거 문서**: `docs/adr/ADR-013-실행-사건은-우리-모델로-정규화해-저장한다.md`,
`docs/data-schema.md` 의 「execution_event」 와 「agent_execution」 절

## 의도 메모

- 나무를 데이터베이스에서 재귀로 만들지 않는다.
  `root_execution_id` 로 한 번에 읽어 메모리에서 잇는다.
  한 실행의 자식 수가 많아질 일이 없고, 재귀 질의는 읽기 어렵다.
- 깊이에 상한을 둔다. 순환이 생기면 응답이 끝나지 않는다.
  지금 순환이 생길 경로는 없지만, 잘못 적힌 `parent_execution_id` 하나로 생길 수 있다.

## 작업 항목

### 1. 조회를 더한다

`usage/infra/ExecutionEventRepository.java` 다.

```java
List<ExecutionEvent> findByExecutionIdOrderBySequenceAsc(Long executionId);
```

`usage/infra/AgentExecutionRepository.java` 다.

```java
/** 뿌리와 그 자손을 한 번에 읽는다. 뿌리 자신은 rootExecutionId 가 null 이라 따로 읽는다. */
List<AgentExecution> findByRootExecutionId(Long rootExecutionId);
```

### 2. 나무를 만드는 서비스

`usage/application/ExecutionTreeService.java` 다.

```java
/**
 * 실행 하나를 그 사건과 자식 실행까지 묶어 낸다.
 *
 * <p>어느 실행 번호를 주든 그 실행이 속한 나무의 뿌리부터 낸다.
 * 화면이 자식 실행에서 들어와도 전체를 보게 하기 위해서다.
 */
@Service
public class ExecutionTreeService {

    public ExecutionTree of(CurrentUser user, Long executionId);
}
```

**남의 실행은 없는 것과 같은 오류로 응답한다.**
`EXECUTION_NOT_FOUND` 를 `ErrorCode` 에 더한다.
뿌리를 찾아 올라간 뒤에도 주인을 확인한다. 자식이 남의 것일 수 없지만 검사는 한다.

깊이 상한을 8 로 둔다. 넘으면 그 아래를 자르고 잘랐다는 것을 응답에 표시한다.

### 3. 응답 형태

```java
public record ExecutionTree(
        ExecutionNode root,
        boolean truncated) {
}

public record ExecutionNode(
        Long executionId,
        String agentCode,
        String agentName,
        String status,
        String model,
        Long inputTokens,
        Long outputTokens,
        Long estimatedCostMicros,
        Long latencyMs,
        Instant startedAt,
        List<ExecutionEventView> events,
        List<ExecutionNode> children) {
}

public record ExecutionEventView(
        int sequence,
        String eventType,
        String toolName,
        String subagentName,
        Long durationMs,
        String detail,
        Instant occurredAt) {
}
```

자식이 없으면 `children` 은 빈 목록이다. `null` 을 내지 않는다.

### 4. API 를 연다

| 메서드 | 경로 | 하는 일 |
| --- | --- | --- |
| `GET` | `/api/v1/usage/executions/{id}/tree` | 그 실행이 속한 나무 |

`UsageController` 에 더한다. 새 컨트롤러를 만들지 않는다.

### 5. 사용량 목록이 나무로 갈 수 있게 한다

`ExecutionView` 에 `hasChildren` 을 더한다.
목록에서 어느 실행이 자식을 가졌는지 보여, 화면이 들어갈 곳을 고를 수 있게 한다.

목록 조회가 실행마다 자식을 세면 질의가 실행 수만큼 늘어난다.
목록의 실행 번호를 모아 한 번에 센다.

### 6. 이 phase 를 검증하는 테스트

`backend/src/test/java/com/bifos/assistant/usage/ExecutionTreeServiceTest.java` 를 새로 만든다.

- **정상 경로**: 사건 셋을 가진 실행 하나를 주면 `sequence` 순서로 나온다
- 자식 실행이 있으면 `children` 에 담긴다
- **자식의 번호로 물어도 뿌리부터 나온다**
- **이 phase 가 다루는 실패**: 남의 실행 번호로 물으면 `EXECUTION_NOT_FOUND` 다
- `parent_execution_id` 가 순환을 이루면 깊이 8 에서 끊기고 `truncated` 가 참이다
- 사건이 하나도 없는 실행도 오류 없이 나온다

`test/e2e/scenarios/usage-cost.ts` 에 더한다.

- 스트리밍 실행 하나의 나무를 조회하면 `RUN_STARTED` 로 시작하는 사건이 들어 있다

## 검증

```bash
# cwd: 저장소 root
cd backend && ./gradlew test
node test/e2e/run.ts
cd web && pnpm typecheck && pnpm build
```

```bash
# cwd: 저장소 root
cd backend && ./gradlew test --tests '*ExecutionTreeServiceTest*'
```

목록 조회가 실행 수만큼 질의를 내지 않는지 본다.
`spring.jpa.properties.hibernate.generate_statistics` 를 테스트에서 켜고 질의 수를 확인하거나,
로그에 찍힌 `select` 수를 세어 보고에 적는다.

## Critical Files

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/usage/application/ExecutionTreeService.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/usage/presentation/UsageController.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/usage/infra/ExecutionEventRepository.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/usage/infra/AgentExecutionRepository.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/shared/error/ErrorCode.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/usage/ExecutionTreeServiceTest.java` | 신규 |
| `test/e2e/scenarios/usage-cost.ts` | 수정 |
