# Phase 02. 재기동 뒤 남은 전달 시도를 닫는다

**Execution profile**: standard

## 목표

이전 프로세스가 `RUNNING` 으로 남긴 전달 시도를 기동할 때 닫고, 기동 정리가 정한 부모 실행 줄의 시도를 그 줄과 같은 트랜잭션에서 닫는다.
알림 줄은 저장됐는데 부모 실행 줄이 생기기 전에 내려간 전달이 「실패(중단)」 로 보여 다시 전달할 수 있게 하려는 것이다.

**범위 외**: 다시 전달 API(phase 03). 기동 정리(`RestartReconciler`)가 무엇을 잡고 묻는지는 바꾸지 않는다.

## 컨텍스트

- phase 01 이 만든 것: 표 `result_delivery_attempt`, 엔티티 `chat.domain.ResultDeliveryAttempt`, 저장소 `chat.infra.ResultDeliveryAttemptRepository`(`finish` 는 `RUNNING` 일 때만 닫는 조건부 update), `chat.application.ResultDeliveryRecorder`(`finish(Long attemptId, DeliveryAttemptStatus status, String errorCode)` 가 시도와 묶음을 함께 닫는다), enum `DeliveryAttemptStatus`
- 기동 정리는 `chat.application.RestartReconciler` 가 돈다. 실행 줄을 적는 자리는 `chat.application.RecoveredRunRecorder` 의 `settle(Long executionId, HermesRunResult result)` 와 `failWithout(Long executionId, String errorCode)` 이고,
  둘 다 private `finishOnce` 가 `transactions.execute` 안에서 `executionRepository.lockById` 로 줄을 잠그고 `RUNNING` 일 때만 `write` 를 부른다. `write` 가 돌려준 `Written` 의 `row()` 가 적은 뒤의 실행 줄이다
- 기동 순서: `RestartReconciler.onReady` 가 `@EventListener(ApplicationReadyEvent.class) @Order(0)`, `NextTurnDispatcher.dispatchAfterStartup` 이 `@Order(10)` 이다. 웹 서버는 `ApplicationReadyEvent` 전에 요청을 받기 시작한다
- 실행 상태는 `usage.domain.type.ExecutionStatus` 의 `RUNNING`, `SUCCEEDED`, `FAILED`, `CANCELLED` 다. `AgentExecution.errorCode()` 가 실패 코드다

**근거 문서**: `docs/backend/agent-delegation.md` 의 「재기동과 사용자 한도와의 경계」, `docs/backend/turn-control.md` 의 「기동 정리가 갈리는 지점」, `docs/adr/ADR-070-결과-전달은-묶음과-시도로-남기고-사용자가-저장된-결과만-다시-전달한다.md`

## 의도 메모

- 기동할 때의 정리는 **이 프로세스가 뜨기 전에 시작한 시도만** 닫는다. 기준 시각은 `ResultDeliveryRecovery` 빈을 만들 때의 `clock.instant()` 다. `ApplicationReadyEvent` 전에 웹 서버가 받은 다시 전달 요청의 시도는 그 뒤에 시작하므로 건드리지 않는다
- 실행 줄이 아직 `RUNNING` 인 시도는 기동할 때 건드리지 않는다. 기동 정리가 그 줄을 정할 때 `RecoveredRunRecorder` 가 닫는다. 기동 정리가 꺼져 있으면(`assistant.restart-reconcile.enabled=false`) 그 시도는 `RUNNING` 으로 남는다. 테스트 profile 의 동작이고 운영은 켠다
- 닫은 묶음의 결과는 `result_delivered_at` 이 이미 채워져 있어 `dispatchAfterStartup` 이 자동으로 다시 열지 않는다. 자동 재시도를 더하지 않는다는 결정(ADR-070)이 그대로 지켜진다
- `RecoveredRunRecorder` 에서 시도를 닫다 예외가 나면 그 트랜잭션 전체가 되돌아가 실행 줄도 `RUNNING` 으로 남는다. 기동 정리는 그때 다시 묻는다(`docs/backend/turn-control.md` 의 「적다가 실패했다」 행). 같은 계약을 따르므로 따로 삼키지 않는다

## 작업 항목

### 1. `backend/src/main/java/com/bifos/assistant/chat/infra/ResultDeliveryAttemptRepository.java`

- `List<ResultDeliveryAttempt> findByStatusAndStartedAtBefore(DeliveryAttemptStatus status, Instant before)`
- `Optional<ResultDeliveryAttempt> findFirstByExecutionIdAndStatus(Long executionId, DeliveryAttemptStatus status)`

### 2. `backend/src/main/java/com/bifos/assistant/chat/application/ResultDeliveryRecorder.java`

- `public static final String INTERRUPTED = "INTERRUPTED";`
- `void finishByExecution(AgentExecution execution)`: `@Transactional`(부르는 쪽에 참여). 그 실행 번호로 `RUNNING` 시도를 찾아 실행 상태대로 닫는다. `SUCCEEDED → SUCCEEDED`, `FAILED → FAILED`(오류 코드는 `execution.errorCode()`), `CANCELLED → STOPPED`. 실행이 `RUNNING` 이거나 시도가 없으면 아무것도 하지 않는다
- `int closeLeftovers(Instant startedBefore)`: 시도마다 따로 트랜잭션을 연다(`TransactionTemplate`). `startedBefore` 전에 시작한 `RUNNING` 시도를 읽어,
  `execution_id` 가 비면 `FAILED` 와 `INTERRUPTED` 로 닫고, 실행 줄이 이미 끝났으면 `finishByExecution` 과 같은 규칙으로 닫고, 실행 줄이 `RUNNING` 이면 건너뛴다. 실행 줄이 지워졌으면 `FAILED` 와 `INTERRUPTED` 로 닫는다. 닫은 수를 돌려준다. 한 시도에서 예외가 나면 경고 로그를 남기고 다음 시도로 간다

### 3. `backend/src/main/java/com/bifos/assistant/chat/application/ResultDeliveryRecovery.java`

`@Component`. 생성자에서 `Clock` 을 받아 기준 시각을 적어 둔다.
`@EventListener(ApplicationReadyEvent.class) @Order(5)` 인 `public void closeAfterStartup()` 이 `recorder.closeLeftovers(startedAt)` 을 부르고, 닫은 수가 0 보다 크면 info 로그를 남긴다. 예외는 error 로그만 남기고 기동을 멈추지 않는다.
`@Order(5)` 는 기동 정리의 묻기(`@Order(0)`)가 시작한 뒤, 기동 뒤 깨우기(`@Order(10)`)보다 먼저라는 뜻이다. Javadoc 에 이 순서와 기준 시각의 까닭을 적는다.

### 4. `backend/src/main/java/com/bifos/assistant/chat/application/RecoveredRunRecorder.java`

`ResultDeliveryRecorder` 를 의존으로 더한다. `finishOnce` 의 `transactions.execute` 안에서, `write` 가 돌려준 `Written` 이 null 이 아니면 `resultDeliveries.finishByExecution(written.row())` 를 부른다.
실행 줄과 시도가 함께 적히거나 함께 되돌아간다. Javadoc 에 그 한 줄과 ADR-070 을 더한다.

### 5. 이 phase 를 검증하는 `backend/src/test/java/com/bifos/assistant/chat/ResultDeliveryRecoveryTest.java`

`@SpringBootTest(properties = "assistant.delegation-wake.enabled=true")`, `@ActiveProfiles("test")`, `@Import(ChatServiceTest.StubRuntime.class)`. 깨우기를 켜야 「다시 열리지 않는다」 검사가 실제로 기동 훑기를 거친다. 줄은 저장소로 직접 만든다. 결과 글은 지어낸 글만 쓴다.
기동 시각 기준을 검사하려고 `ResultDeliveryRecorder.closeLeftovers` 를 시각을 넘겨 직접 부른다.

| 검사 | 준비 | 기대 |
| --- | --- | --- |
| 실행 줄 없이 남은 시도 | 묶음 `DELIVERING`, 시도 `RUNNING` 과 `execution_id` 없음, `started_at` 이 기준 시각 전 | 시도 `FAILED` 와 `INTERRUPTED`, 묶음 `FAILED`, `finished_at` 채워짐 |
| 기준 시각 뒤에 시작한 시도 | 같은 줄인데 `started_at` 이 기준 시각 뒤 | 그대로 `RUNNING` 과 `DELIVERING` |
| 실행 줄은 끝났는데 시도가 남았다 | 실행 줄 `SUCCEEDED`, 시도 `RUNNING` | 시도 `SUCCEEDED`, 묶음 `DELIVERED` |
| 실행 줄이 취소로 끝났다 | 실행 줄 `CANCELLED` | 시도 `STOPPED`, 묶음 `STOPPED` |
| 실행 줄이 아직 돈다 | 실행 줄 `RUNNING` | 기동 때의 정리는 건너뛴다. 그 뒤 `RecoveredRunRecorder.failWithout(executionId, "REMOTE_RUN_LOST")` 를 부르면 시도 `FAILED` 와 `REMOTE_RUN_LOST`, 묶음 `FAILED` |
| 기동 정리가 성공으로 정했다 | 실행 줄 `RUNNING` 과 run 번호, 대화가 있는 대화 turn 루트 줄. `RecoveredRunRecorder.settle(executionId, 완료 결과)` | 실행 줄 `SUCCEEDED`, 답 줄 저장, 시도 `SUCCEEDED`, 묶음 `DELIVERED` |
| 닫은 묶음은 자동으로 다시 열리지 않는다 | 첫 검사의 묶음에 루트 부모 줄과 `result_delivered_at` 이 채워진 위임 자식 줄을 함께 만들고 항목으로 건다. 닫은 뒤 `NextTurnDispatcher.dispatchAfterStartup()` | 대역 Hermes 제출 없음. 같은 준비에서 `result_delivered_at` 만 비운 대조 줄로는 제출이 하나 생겨 검사가 기동 훑기를 실제로 거친다는 것을 보인다 |
| 기동 연결 | `ResultDeliveryRecovery` 를 고정 시각의 `Clock` 으로 직접 만들고 `closeAfterStartup()` 을 부른다 | 그 시각 전에 시작한 시도만 닫힘 |

기존 `backend/src/test/java/com/bifos/assistant/chat/RecoveredRunRecorderTest.java` 와 `RestartReconcilerTest` 는 고치지 않는다. 생성자 의존이 늘어 그 테스트가 직접 객체를 만들고 있으면 새 의존만 넘기도록 고치고 단언은 바꾸지 않는다.

## 검증

```bash
# cwd: backend/
./gradlew test --tests 'com.bifos.assistant.chat.ResultDeliveryRecoveryTest'
./gradlew test --tests 'com.bifos.assistant.chat.RecoveredRunRecorderTest' --tests 'com.bifos.assistant.chat.application.RestartReconcilerTest'
./gradlew test
```

```bash
# cwd: 저장소 root
scripts/check-mysql-migration.sh
scripts/quality.sh check
```

기대: 모두 종료 코드 0. 새 저장소 메서드 둘이 `RepositoryQueryMysqlTest` 에서 실행된다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/chat/infra/ResultDeliveryAttemptRepository.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/application/ResultDeliveryRecorder.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/application/ResultDeliveryRecovery.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/application/RecoveredRunRecorder.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/ResultDeliveryRecoveryTest.java` | 신규 |
