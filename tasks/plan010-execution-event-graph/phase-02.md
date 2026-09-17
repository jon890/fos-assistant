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

**순환이 생길 수 있는 자리가 둘이고 대응이 다르다.**

하나는 **뿌리를 찾아 올라가는 길**이다.
`root_execution_id` 가 비어 있는 실행에서 `parent_execution_id` 를 따라 올라갈 때,
`A` 의 부모가 `B` 이고 `B` 의 부모가 `A` 이면 끝나지 않는다.
올라간 횟수를 8 로 제한하고, 넘으면 마지막으로 닿은 실행을 뿌리로 삼는다.
이때는 **나무의 `truncated` 만** 참으로 둔다.
잘린 것이 그 노드의 자식이 아니라 그 위쪽이라, 노드의 `truncated` 로 적으면 화면이 아래를 가리킨다.

다른 하나는 **자식을 붙여 내려가는 길**이다.
평면 목록을 부모 번호로 묶어 잇는데, 이미 붙인 실행을 다시 붙이면 같은 가지가 무한히 자란다.
이미 쓴 실행 번호를 모아 두고 다시 만나면 붙이지 않는다.
깊이도 8 로 제한하고, 그 깊이에서 자식이 남아 있으면 그 노드의 `truncated` 를 참으로 둔다.

**뿌리에서 닿지 않는 실행은 나무에 넣지 않는다.**
`root_execution_id` 로 읽어 온 것 중 부모 사슬이 뿌리까지 닿지 않는 줄이 그렇다.
그것은 데이터가 어긋난 것이고, 그 때문에 응답이 끝나지 않으면 안 된다.
버린 줄이 있으면 `log.warn` 으로 실행 번호를 남긴다.

### 3. 응답 형태

```java
public record ExecutionTree(
        ExecutionNode root,
        boolean truncated) {
}

public record ExecutionNode(
        boolean truncated,
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

**`truncated` 가 두 곳에 있고 뜻이 다르다.**

| 어디 | 뜻 |
| --- | --- |
| `ExecutionTree.truncated` | 이 나무 어딘가를 잘랐다. 화면이 한 번에 알아채는 값이다 |
| `ExecutionNode.truncated` | **이 노드 아래**를 잘랐다. 화면이 그 자리에 한 줄을 적는다 |

나무 전체의 값은 노드 중 하나라도 참이면 참이다.
phase-03 이 잘린 자리를 그리므로 노드마다 필요하다.

### 4. API 를 연다

| 메서드 | 경로 | 하는 일 |
| --- | --- | --- |
| `GET` | `/api/v1/usage/executions/{id}/tree` | 그 실행이 속한 나무 |

`UsageController` 에 더한다. 새 컨트롤러를 만들지 않는다.

### 5. 사용량 목록이 나무로 갈 수 있게 한다

`ExecutionView` 에 `hasChildren` 을 더한다.
목록에서 어느 실행이 자식을 가졌는지 보여, 화면이 들어갈 곳을 고를 수 있게 한다.

**`ExecutionView` 는 record 이고 다른 plan 도 여기에 칸을 더한다.**
`hasChildren` 을 **맨 끝에** 놓는다. 중간에 끼우면 머지할 때 `from()` 이 양쪽에서 깨진다.

목록 조회가 실행마다 자식을 세면 질의가 실행 수만큼 늘어난다.
목록의 실행 번호를 모아 한 번에 읽는다.

`AgentExecutionRepository` 에 더한다.

```java
/** 이 번호들 중 자식을 가진 것만 낸다. 목록이 실행마다 세지 않게 한 번에 읽는다. */
@Query("""
        select distinct e.parentExecutionId from AgentExecution e
        where e.parentExecutionId in :parentIds
        """)
List<Long> findParentIdsHavingChildren(@Param("parentIds") Collection<Long> parentIds);
```

`parentIds` 가 비면 부르지 않는다. 빈 `in` 절은 데이터베이스마다 다르게 동작한다.

**판정 기준은 「목록 길이가 늘어도 이 질의가 한 번이다」 이다.**
목록 조회 전체의 질의 수를 세지 않는다.
`agents.requireById` 가 실행마다 부르는 것은 이 plan 이 만든 것이 아니고 고치지 않는다.
그것까지 함께 세면 무엇이 이 plan 때문인지 갈라지지 않는다.

확인은 `ExecutionTreeServiceTest` 가 아니라 `UsageController` 를 부르는 테스트에서 한다.
`spring.jpa.properties.hibernate.generate_statistics` 를 켜고
실행을 2개 넣었을 때와 10개 넣었을 때의 질의 수 차이를 견준다.
이 질의가 한 번이면 차이가 실행 수만큼 늘지 않는다.

### 6. 이 phase 를 검증하는 테스트

`backend/src/test/java/com/bifos/assistant/usage/ExecutionTreeServiceTest.java` 를 새로 만든다.

- **정상 경로**: 사건 셋을 가진 실행 하나를 주면 `sequence` 순서로 나온다
- 자식 실행이 있으면 `children` 에 담긴다
- **자식의 번호로 물어도 뿌리부터 나온다**
- **이 phase 가 다루는 실패**: 남의 실행 번호로 물으면 `EXECUTION_NOT_FOUND` 다
- **뿌리를 찾아 올라가는 길이 순환이면** 8 번 올라간 뒤 멈추고 `truncated` 가 참이다.
  서로를 부모로 가리키는 두 실행으로 만든다
- **깊이 9 짜리 나무를 주면** 8 단까지 나오고 마지막 노드의 `truncated` 가 참이다
- 뿌리에서 닿지 않는 실행이 섞여 있어도 응답이 끝나고, 그 실행은 나무에 없다
- 사건이 하나도 없는 실행도 오류 없이 나온다

**e2e 는 `test/e2e/scenarios/streaming.ts` 에 더한다. `usage-cost.ts` 가 아니다.**

`test/e2e/run.ts` 의 `SCENARIOS` 가 `usageCostScenario` 를 `streamingScenario` 보다 먼저 돌린다.
usage-cost 가 도는 시점에는 스트리밍 실행이 아직 없다.
거기서 하나 만들면 같은 파일의 실행 수 판정과 월 합계 판정이 함께 깨진다.

`streaming.ts` 에 더한다. 그 파일이 이미 스트리밍 실행 하나를 만들고 `done` 에서 실행 번호를 받는다.

- 그 실행 번호로 나무를 조회하면 `RUN_STARTED` 로 시작하는 사건이 순서대로 들어 있다
- 도구 사건이 가짜 Hermes 가 보낸 이름으로 들어 있다
- 자식이 없으므로 `children` 이 빈 목록이고 `truncated` 가 거짓이다
- 남의 토큰으로 같은 번호를 물으면 `EXECUTION_NOT_FOUND` 다

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

5번의 판정 기준대로 질의 수를 세어 보고에 적는다.

## Critical Files

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/usage/application/ExecutionTreeService.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/usage/presentation/UsageController.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/usage/infra/ExecutionEventRepository.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/usage/infra/AgentExecutionRepository.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/shared/error/ErrorCode.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/usage/ExecutionTreeServiceTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/usage/UsageControllerTest.java` | 신규 또는 수정 (질의 수) |
| `test/e2e/scenarios/streaming.ts` | 수정 (나무 조회) |

**`UsageController` 와 `AgentExecutionRepository` 와 `ExecutionView` 를 다른 plan 도 고친다.**
메서드와 칸을 **더하기만 한다.** 기존 것을 지우거나 시그니처를 바꾸지 않는다.
