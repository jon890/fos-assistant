# Phase 01. 사용자 자리를 세는 제한기와 turn 자리

**Execution profile**: deep

## 목표

`usage.application.UserExecutionLimiter` 를 만들어 사용자 한 명이 쥔 자리를 turn 자리와 `RUNNING` 실행 줄의 합으로 세고, 사용자 잠금 하나 안에서 판정과 자리 만들기를 함께 한다.
대화 turn 을 열 때 turn 자리를 얻고, 실행 줄을 만들 때 자식과 백그라운드 실행을 판정한다.

**범위 외**: 한도에 닿았을 때 대기 메시지, 자동 turn, 위임, 흐름, 추천 질문, Memory 제안이 어떻게 끝나는지는 phase 02 가 정한다. 원격 종료 확인 자리는 phase 03 이다. 화면과 측정은 phase 04 다.

## 컨텍스트

**근거 문서**: `docs/backend/execution-limit.md` 의 「세는 방법」, 「기동 정리와의 관계」, 「설정」 절, `docs/adr/ADR-069-사용자-전체-실행-한도는-turn-자리와-실행-줄을-사용자-잠금-하나에서-센다.md`, `docs/backend/turn-control.md` 의 「중지」 절.

- 실행 줄은 모두 `backend/src/main/java/com/bifos/assistant/usage/application/ExecutionRecorder.java` 의 private `record(...)` 에서 `executions.save(...)` 로 `RUNNING` 이 되어 생긴다. 공개 진입은 `start(...)`(12개 인자 판과 그것을 부르는 9개 인자 판), `startInheriting(...)`(Memory 제안), `startDetached(...)`(추천 질문) 셋이다.
- `start` 를 부르는 곳: `chat/application/ChatService.java` 의 `begin`(대화 turn 의 루트, `parentExecutionId` null, 대화 있음), `orchestration/application/AgentRunner.java` 의 `run`(흐름 Chief 는 parent null 이고 대화 있음, 흐름 단계와 위임 자식은 parent 있음). 이 호출들은 모두 트랜잭션 밖이다.
- 대화 잠금은 `chat/application/TurnCancellation.java` 의 `open(Long userId, Long conversationId)` 가 `byConversation.putIfAbsent` 로 잡고 `close(TurnHandle)` 가 풀며 닫기 리스너를 부른다. `runIfIdle` 은 `TurnHandle(null, conversationId)` 로 잡고 실행을 열지 않는다. `TurnHandle` 은 `chat/application/TurnHandle.java` 이고 생성자는 `TurnHandle(Long userId, Long conversationId)` 다.
- `chat/application/RestartReconciler.java` 의 잡기 단계(약 283행)가 `turns.open(row.userId(), row.conversationId())` 로 기동 때 남은 대화 turn 의 잠금을 잡는다.
- 저장소는 `usage/infra/AgentExecutionRepository.java`. 엔티티 `usage/domain/AgentExecution` 의 칸은 `userId`, `conversationId`, `parentExecutionId`, `status`(`usage.domain.type.ExecutionStatus`: `RUNNING`, `SUCCEEDED`, `FAILED`, `CANCELLED`) 이다.
- 설정 record 의 본보기는 `orchestration/application/DelegationProperties.java`(`@Validated`, `@ConfigurationProperties`, compact 생성자에서 `IllegalStateException`)와 `chat/application/RestartReconcileProperties.java`(비어 있으면 null 인 `Duration`)다. `@ConfigurationPropertiesScan` 이 켜져 있어 등록은 따로 하지 않는다.
- 기존 사용자별 제한기 `connector/application/ConnectorCallLimiter.java` 가 메모리에 사용자별 상태를 두는 본보기다. 위임의 루트 잠금은 `AgentDelegationService` 의 `ReentrantLock[64]` stripe 다.
- 오류 코드는 `shared/error/ErrorCode.java` 의 enum 이다. `CONVERSATION_BUSY(HttpStatus.CONFLICT)` 옆에 둔다.
- 서비스와 infra 에 공개된 중첩 타입을 새로 두지 않는다(`ArchitectureRules.SERVICES_DO_NOT_EXPOSE_NESTED_TYPES`). 저장하지 않는 enum 은 `<기능>.application.model` 에 둔다(`backend/AGENTS.md` 의 「enum 은 저장 여부로 둘 곳을 정한다」).
- 테스트 profile 은 `backend/src/test/resources/application-test.yml` 이다. 여러 검사가 같은 H2 를 쓰며 `RUNNING` 줄을 남긴다. 그래서 테스트 profile 의 한도는 크게 두고, 한도를 보는 검사만 작은 값을 쓴다.

## 의도 메모

- 실행 줄만 세지 않는다. 흐름은 Chief 루트 줄이 `SUCCEEDED` 가 된 뒤 자식이 돌고, 대화 turn 은 질문을 저장한 뒤에야 루트 줄이 생긴다. 그 틈의 turn 을 turn 자리로 센다. 대화 turn 의 루트 줄(`parentExecutionId` null 이고 `conversationId` 있음)은 줄 수에서 빼야 두 번 세지 않는다.
- 메모리 semaphore 로 줄을 대신 세지 않는다. 반환 경로가 실행 줄의 상태 기록과 따로 놀아 새거나 두 번 돌아온다.
- 판정과 자리 만들기를 사용자 잠금 하나 안에서 한다. turn 자리와 실행 줄을 따로 세면 합이 한도를 넘는다. 이 경쟁을 테스트로 보인다(코디네이터 요구).
- 실행 줄 저장은 잠금 안에서 커밋까지 끝나야 한다. 트랜잭션 안에서 부르면 잠금을 푼 뒤 커밋되어 다른 스레드가 그 줄을 세지 못한다. 그래서 자식과 백그라운드 판정은 활성 트랜잭션이 있으면 `IllegalStateException` 을 던진다.
- `CONVERSATION_BUSY` 가 `USER_BUSY` 보다 먼저다. 웹은 `CONVERSATION_BUSY` 를 받으면 대기 메시지로 넣는다. 그래서 대화 잠금을 잡은 뒤 turn 자리를 얻는다.
- 기동 정리가 잡는 turn 은 이미 Hermes 에서 돈다. 한도를 보지 않고 자리를 얻는다.

## 작업 항목

### 1. `backend/src/main/java/com/bifos/assistant/usage/application/UserExecutionProperties.java` 신규

```java
@Validated
@ConfigurationProperties(prefix = "assistant.user-execution")
public record UserExecutionProperties(int maxRunning, int backgroundReserve, Duration remoteEndMaxWait)
```

- compact 생성자: `maxRunning < 1` 이면 `IllegalStateException("assistant.user-execution.max-running must be at least 1: " + v)`.
  `backgroundReserve < 0` 이거나 `backgroundReserve >= maxRunning` 이면 거절한다.
  `remoteEndMaxWait` 는 null 을 허용하고, null 이 아니면 0 보다 커야 한다.
- Javadoc 은 `docs/backend/execution-limit.md` 의 「설정」 표와 같은 뜻으로 쓴다. `remoteEndMaxWait` 는 phase 03 이 쓴다. null 이면 `hermes.run-timeout` 이다.

### 2. `backend/src/main/resources/application.yml`

`assistant.delegation` 블록 아래에 둔다. 주석은 한국어로, 기존 블록처럼 한 줄씩 단다.

```yaml
  user-execution:
    # 한 사용자가 Hermes 에 동시에 맡길 수 있는 실행 수. turn 자리와 RUNNING 실행 줄의 합이다(ADR-069)
    max-running: 4
    # 추천 질문과 Memory 제안이 돈 뒤에도 남겨 둘 사용자 자리 수
    background-reserve: 1
    # Hermes 에서 끝났는지 모르는 run 의 자리를 쥐는 상한. 비우면 hermes.run-timeout 과 같다
    remote-end-max-wait: ${ASSISTANT_USER_EXECUTION_REMOTE_END_MAX_WAIT:}
```

### 3. `backend/src/test/resources/application-test.yml`

`assistant` 아래에 `user-execution.max-running: 1000` 을 더한다. 주석: 같은 H2 를 쓰는 다른 검사가 남긴 `RUNNING` 줄과 나란히 도는 turn 이 한도에 걸리지 않게 올린다. 한도 자체는 `UserExecutionLimiterTest` 와 한도를 보는 검사가 작은 값으로 본다.

### 4. `backend/src/main/java/com/bifos/assistant/shared/error/ErrorCode.java`

`CONVERSATION_BUSY` 다음 줄에 `USER_BUSY(HttpStatus.CONFLICT)` 를 더하고 Javadoc 한 줄: 그 사용자가 여러 대화와 위임으로 동시 실행 한도를 모두 쓰고 있다(ADR-069).

### 5. `backend/src/main/java/com/bifos/assistant/usage/infra/AgentExecutionRepository.java`

```java
@Query("""
        select count(e) from AgentExecution e
        where e.userId = :userId
          and e.status = :status
          and (e.parentExecutionId is not null or e.conversationId is null)
        """)
long countRunningOutsideTurns(@Param("userId") Long userId, @Param("status") ExecutionStatus status);
```

이름과 인자 모양은 저장소의 다른 `@Query` 메서드를 따른다. Javadoc 한 줄: 대화 turn 의 루트 줄은 turn 자리로 세므로 뺀다.
마이그레이션은 더하지 않는다. `RUNNING` 줄은 적고 `(status)` 색인(V6)이 있다.
`RepositoryQueryMysqlTest` 가 저장소 메서드를 스스로 찾아 실제 MySQL 에서 실행한다(`backend/AGENTS.md` 의 「저장소 쿼리는 실제 MySQL 에서도 실행한다」).

### 6. `backend/src/main/java/com/bifos/assistant/usage/application/model/ExecutionAdmission.java` 신규

```java
/** 실행 줄을 만들 때 사용자 실행 한도를 어떻게 볼지다(ADR-069). 저장하지 않는다. */
public enum ExecutionAdmission {
    /** 대화 turn 의 루트 줄. turn 자리가 이미 세었으므로 다시 보지 않는다 */
    TURN_ROOT,
    /** 흐름 단계와 위임 자식. 쥔 자리가 max-running 보다 작아야 한다 */
    CHILD,
    /** 추천 질문과 Memory 제안. 줄을 만든 뒤에도 background-reserve 자리가 남아야 한다 */
    BACKGROUND
}
```

### 7. `backend/src/main/java/com/bifos/assistant/usage/application/TurnSlot.java` 신규

turn 자리 하나. `public void release()` 는 처음 한 번만 제한기에 돌려준다(`AtomicBoolean`). 생성자는 패키지 안에서만 쓴다. 제한기가 만들고 `TurnCancellation` 이 들고 있다가 닫을 때 돌려준다.

### 8. `backend/src/main/java/com/bifos/assistant/usage/application/UserExecutionLimiter.java` 신규 (`@Component`)

의존: `UserExecutionProperties`, `AgentExecutionRepository`. phase 03 이 `HermesRunsClient` 와 `HermesProperties` 를 더한다.

상태(모두 프로세스 메모리):
- 사용자별 잠금. `ConcurrentHashMap<Long, ReentrantLock>` 의 `computeIfAbsent` 나 고정 stripe 배열 가운데 하나. 잠금 하나 안에서 판정과 증감을 모두 한다.
- 사용자별 turn 자리 수 `Map<Long, Integer>`.
- 사용자별 원격 종료 확인 자리 `Map<Long, Set<Long>>`(실행 번호). 이 phase 에서는 비어 있고 합계에만 더한다. phase 03 이 채운다.

공개 메서드:

| 메서드 | 하는 일 |
| --- | --- |
| `TurnSlot acquireTurn(Long userId)` | 잠금 안에서 `used(userId)` 가 `maxRunning` 이상이면 `new ApiException(ErrorCode.USER_BUSY, "this user has reached the concurrent execution limit")` 를 던진다. 아니면 turn 자리를 하나 늘리고 `TurnSlot` 을 돌려준다 |
| `TurnSlot acquireRecoveredTurn(Long userId)` | 한도를 보지 않고 turn 자리를 늘린다. 기동 정리 전용 |
| `<T> T admit(Long userId, ExecutionAdmission admission, Supplier<T> create)` | `TURN_ROOT` 면 잠금 없이 `create.get()`. `CHILD` 와 `BACKGROUND` 는 먼저 `TransactionSynchronizationManager.isActualTransactionActive()` 가 참이면 `IllegalStateException` 을 던진다. 잠금 안에서 `CHILD` 는 `used >= maxRunning`, `BACKGROUND` 는 `used + 1 + backgroundReserve > maxRunning` 이면 `USER_BUSY` 를 던지고, 통과하면 같은 잠금 안에서 `create.get()` 을 부른다 |
| `int used(Long userId)` | 잠금 안에서 turn 자리 + 원격 종료 확인 자리 + `countRunningOutsideTurns(userId, RUNNING)` 를 돌려준다. 테스트와 로그용으로 공개한다 |

`TurnSlot.release()` 가 부르는 패키지 메서드는 잠금 안에서 turn 자리를 하나 줄이고 0 이 되면 맵에서 지운다.
거절할 때는 사용자 번호와 그때의 세 값을 `info` 로그로 남긴다. 본문이나 프롬프트는 남기지 않는다.

### 9. `backend/src/main/java/com/bifos/assistant/usage/application/ExecutionRecorder.java`

- `UserExecutionLimiter` 를 주입받는다(`@RequiredArgsConstructor` 의 final 필드).
- private `record(...)` 에 `ExecutionAdmission admission` 인자를 더하고 `executions.save(...)` 를 `limiter.admit(user.id(), admission, () -> executions.save(...))` 로 감싼다.
- `start(...)` 는 `parentExecutionId != null` 이면 `CHILD`, `conversation != null` 이면 `TURN_ROOT`, 둘 다 아니면 `BACKGROUND` 로 넘긴다.
  `startInheriting(...)` 은 `BACKGROUND` 다. `startDetached(...)` 는 `start` 를 거쳐 `BACKGROUND` 가 된다.
- 각 공개 메서드의 Javadoc 에 「사용자 실행 한도에 닿으면 `USER_BUSY` 를 던지고 줄을 만들지 않는다. 트랜잭션 밖에서 부른다」 를 더한다.
- `ExecutionRecorder` 를 직접 생성하는 테스트가 있으면 생성자 인자를 맞춘다(`grep -rn "new ExecutionRecorder(" backend/src/test`).

### 10. `backend/src/main/java/com/bifos/assistant/chat/application/TurnHandle.java`, `TurnCancellation.java`

- `TurnHandle` 에 `TurnSlot slot` 필드(패키지 접근)를 둔다.
- `TurnCancellation` 생성자에 `UserExecutionLimiter` 를 더한다.
- `open(userId, conversationId)`: `putIfAbsent` 가 성공한 뒤 `handle.slot = limiter.acquireTurn(userId)`. 예외가 나면 `byConversation.remove(conversationId, handle)` 로 되돌리고 닫기 리스너를 부르지 않은 채 다시 던진다.
- 새 메서드 `openRecovered(Long userId, Long conversationId)`: `open` 과 같되 `acquireRecoveredTurn` 을 쓴다. Javadoc: 기동 정리 전용, 이미 Hermes 에서 도는 turn 이라 한도를 보지 않는다.
- `close(handle)`: 맵에서 뺀 직후, `notifyClosed` 보다 먼저 `handle.slot` 이 있으면 `release()` 한다. 닫기 리스너가 다음 turn 을 열 때 자리가 돌아와 있어야 한다.
- `runIfIdle` 은 바꾸지 않는다. 자리를 얻지 않는다.
- `TurnCancellation` 을 직접 만드는 테스트(`TurnCancellationTest`, `TurnCancellationCloseTest` 등, `grep -rn "new TurnCancellation(" backend/src/test`)의 생성자 인자를 맞춘다. 제한기는 `new UserExecutionLimiter(new UserExecutionProperties(1000, 0, null), repository)` 처럼 한도가 큰 실물이나 Mockito 대역을 쓴다.

### 11. `backend/src/main/java/com/bifos/assistant/chat/application/RestartReconciler.java`

잡기 단계의 `turns.open(row.userId(), row.conversationId())` 를 `turns.openRecovered(...)` 로 바꾼다. 묻기 단계가 다시 잡는 자리(같은 파일에서 `turns.open(` 을 찾는다)도 같다.

### 12. 테스트

- `backend/src/test/java/com/bifos/assistant/usage/UserExecutionPropertiesTest.java` 신규: 정상 값으로 만들어진다. `maxRunning` 0, `backgroundReserve` -1, `backgroundReserve == maxRunning`, `remoteEndMaxWait` 0 이 각각 `IllegalStateException`.
- `backend/src/test/java/com/bifos/assistant/usage/UserExecutionLimiterTest.java` 신규. `@SpringBootTest` 와 `@ActiveProfiles("test")` 에 `properties = {"assistant.user-execution.max-running=3", "assistant.user-execution.background-reserve=1"}` 를 준다. 실제 H2 저장소로 줄을 센다. 테스트마다 새 사용자 번호(`AppUser` 를 저장하거나 이 검사만 쓰는 큰 번호)를 써서 다른 검사의 줄과 섞이지 않게 한다. 실행 줄은 기존 검사가 줄을 만드는 방식(`grep -rn "AgentExecution.builder()" backend/src/test` 의 본보기)으로 만든다.
  - turn 자리 3개를 얻으면 넷째 `acquireTurn` 이 `USER_BUSY`. 하나를 `release()` 하면 다시 얻는다. 같은 `TurnSlot` 을 두 번 `release()` 해도 한 번만 줄어든다(`used` 로 확인).
  - turn 자리 2개와 `CHILD` 줄 1개면 셋째 `CHILD` 는 `USER_BUSY` 이고 `create` 가 불리지 않는다.
  - 대화 turn 의 루트 줄(`parentExecutionId` null, `conversationId` 있음)은 `used` 에 들지 않는다. 추천 질문 줄(둘 다 null)은 든다.
  - `BACKGROUND` 는 `used` 가 1 이면 통과(1+1+1 ≤ 3), 2 이면 `USER_BUSY`.
  - `acquireRecoveredTurn` 은 한도를 넘겨도 얻는다.
  - **경쟁**: 스레드 16개가 `CountDownLatch` 로 함께 출발해 절반은 `acquireTurn`, 절반은 `admit(CHILD, () -> 실제 RUNNING 줄 저장)` 을 부른다. 성공한 수의 합이 정확히 3 이고, 끝난 뒤 `used` 가 3 이다. 잠금을 빼면 실패하는 검사여야 한다.
  - 활성 트랜잭션 안에서 `admit(CHILD, ...)` 를 부르면 `IllegalStateException`(`TransactionTemplate` 으로 감싼다).
- `backend/src/test/java/com/bifos/assistant/chat/application/TurnCancellationTest.java` 에 더한다:
  - 한도가 1 인 제한기로 대화 A 의 `open` 뒤 대화 B 의 `open` 이 `USER_BUSY` 이고, B 의 잠금이 남지 않는다(`markOf(B)` 가 `TurnMark.NONE`, 닫기 리스너가 불리지 않는다).
  - 같은 대화에 이미 turn 이 있으면 `CONVERSATION_BUSY` 가 먼저 나간다.
  - `close` 뒤 자리가 돌아와 다른 대화의 `open` 이 된다. `close` 를 두 번 불러도 자리는 한 번만 돌아온다.
  - `openRecovered` 는 한도를 넘겨도 연다.
- 기존 검사(루트당 한도 `AgentDelegationServiceTest`, 대화 잠금 `ChatRunningTurnTest`, 중지 `ChatStopTest`, 기동 정리 `RestartReconcilerTest`, `TurnCancellationCloseTest`)를 지우거나 단언을 약하게 바꾸지 않는다. 생성자 인자만 맞춘다.

## 검증

```bash
# cwd: backend/
./gradlew test --tests '*UserExecutionPropertiesTest' --tests '*UserExecutionLimiterTest' --tests '*TurnCancellationTest' --tests '*TurnCancellationCloseTest' --tests '*RestartReconcilerTest'
./gradlew test
./gradlew qualityCheck
```

```bash
# cwd: 저장소 root
scripts/check-mysql-migration.sh
scripts/quality.sh check
```

- 모두 종료 코드 0.
- `RepositoryQueryMysqlTest` 가 `countRunningOutsideTurns` 를 실행했다(`scripts/check-mysql-migration.sh` 출력이나 그 테스트 보고서에서 확인한다).

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/usage/application/UserExecutionProperties.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/usage/application/UserExecutionLimiter.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/usage/application/TurnSlot.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/usage/application/model/ExecutionAdmission.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/usage/application/ExecutionRecorder.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/usage/infra/AgentExecutionRepository.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/shared/error/ErrorCode.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/application/TurnHandle.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/application/TurnCancellation.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/application/RestartReconciler.java` | 수정 |
| `backend/src/main/resources/application.yml` | 수정 |
| `backend/src/test/resources/application-test.yml` | 수정 |
| `backend/src/test/java/com/bifos/assistant/usage/UserExecutionPropertiesTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/usage/UserExecutionLimiterTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/chat/application/TurnCancellationTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/application/TurnCancellationCloseTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/**/*.java` | 수정 |
