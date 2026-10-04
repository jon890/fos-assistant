# Phase 01. 자동 turn 의 결과 전달을 묶음과 시도로 남긴다

**Execution profile**: deep

## 목표

자동 turn 이 알림 줄을 저장하는 트랜잭션에서 전달 묶음, 항목, 첫 시도를 함께 만들고, 그 turn 이 끝난 방식으로 시도와 묶음을 닫는다.
부모 결과 정리가 실패하거나 중지돼도 그 사실이 데이터베이스에 남게 하려는 것이다.

**범위 외**: 기동할 때 남은 시도를 닫는 일(phase 02), 다시 전달 API 와 이력 API(phase 03), 화면(phase 05).

## 컨텍스트

- 지금 자동 turn 은 `ChatService.runDelegationResults` 가 결과를 모아 `TurnIntent.DelegationResults(executionIds, notices, deliveries)` 로 `runTurn` 을 부르고,
  `runTurn` 첫 줄의 `saveQuestion` 이 `DelegationResults` 분기에서 한 트랜잭션으로 알림 줄 저장, `deliveryWriter.markResultDelivered`, `source.markDelivered`, `conversationWriter.incrementAutoTurns` 를 한다.
  그 뒤 문맥 조립, `modelTiers.resolve`, `begin`(실행 줄 생성), `submit`, `awaitCompletion` 이 이어진다.
- `runTurn` 은 성공이면 `cancelled()` 가 거짓인 `ChatTurn` 을, 중지면 참인 `ChatTurn` 을 돌려주고, 실패면 `ApiException` 이나 다른 `RuntimeException` 을 던진다.
- 승인 결과는 `AutoTurnResultSource` 구현 `connector.application.ConnectorActionResultSource` 가 낸다. 결과 이름 `AutoTurnResult.key()` 는 승인 줄의 `public_id` UUID 글이다.
- 엔티티 모양은 `backend/src/main/java/com/bifos/assistant/chat/domain/ChatPendingMessage.java`(`@Getter`, `@Accessors(fluent = true)`, `protected` 기본 생성자, 정적 생성 메서드)를, 저장소는 `backend/src/main/java/com/bifos/assistant/chat/infra/ChatPendingMessageRepository.java`(고치는 쿼리는 `@Modifying(flushAutomatically = true, clearAutomatically = true)`)를 따른다.
- 저장되는 enum 은 `<기능>.domain.type` 에 둔다(`backend/AGENTS.md` 의 「enum 은 저장 여부로 둘 곳을 정한다」). 저장되지 않는 결과 타입은 `chat.application.model` 에 둔다.
- 마이그레이션 규칙은 `docs/backend/schema/README.md` 의 「마이그레이션 작성 규칙」 이 갖는다. 새 표에 `ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci` 를 적는다. `test/unit/migration-collation.test.ts` 가 검사한다.

**근거 문서**: `docs/adr/ADR-070-결과-전달은-묶음과-시도로-남기고-사용자가-저장된-결과만-다시-전달한다.md`, `docs/backend/schema/chat.md` 의 `result_delivery`, `result_delivery_item`, `result_delivery_attempt`, `docs/backend/agent-delegation.md` 의 「결과 전달이 끝나지 않았을 때」

## 의도 메모

- `result_delivered_at` 과 `connector_action.result_delivered_at` 의 계약은 바꾸지 않는다. 알림 줄과 같은 트랜잭션에서 적고, turn 이 실패해도 같은 결과로 자동 turn 을 다시 열지 않는다. 전달 표시를 turn 이 끝난 뒤로 미루면 실패를 끝없이 되풀이한다(ADR-070 의 「대안 기각」)
- 묶음과 시도의 상태를 바꾸는 쓰기는 늘 같은 트랜잭션이다
- 알림 줄이 없으면(결과가 비었거나 흐름 대화라 돌아가는 경우) 묶음을 만들지 않는다. 묶음은 알림 줄과 함께만 생긴다
- 자동 turn 을 사용자 실행 한도로 미루는 동작(`DelegationWakeService.retryLaterAfterUserBusy`)은 건드리지 않는다. 그 경로는 대화 잠금을 잡기 전이라 묶음이 생기지 않는다
- 시도를 닫는 쓰기가 실패해도 turn 의 원래 결과나 예외를 바꾸지 않는다. 경고 로그만 남긴다. 그 시도는 phase 02 의 기동 정리가 닫는다

## 작업 항목

### 1. `backend/src/main/resources/db/migration/V66__result_delivery.sql`

DDL 만 담는다. 머리 주석에 ADR-070 을 적는다.

```sql
CREATE TABLE result_delivery (
    id BIGINT NOT NULL AUTO_INCREMENT,
    conversation_id BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL,
    attempt_count INT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
CREATE INDEX idx_result_delivery_conversation_status ON result_delivery (conversation_id, status);

CREATE TABLE result_delivery_item (
    id BIGINT NOT NULL AUTO_INCREMENT,
    delivery_id BIGINT NOT NULL,
    source VARCHAR(40) NOT NULL,
    result_key VARCHAR(64) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_result_delivery_item_source_key (source, result_key),
    CONSTRAINT fk_result_delivery_item_delivery FOREIGN KEY (delivery_id) REFERENCES result_delivery(id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
CREATE INDEX idx_result_delivery_item_delivery ON result_delivery_item (delivery_id, id);

CREATE TABLE result_delivery_attempt (
    id BIGINT NOT NULL AUTO_INCREMENT,
    delivery_id BIGINT NOT NULL,
    attempt_no INT NOT NULL,
    status VARCHAR(20) NOT NULL,
    execution_id BIGINT NULL,
    notice_message_id BIGINT NULL,
    error_code VARCHAR(64) NULL,
    started_at DATETIME(6) NOT NULL,
    finished_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_result_delivery_attempt_no (delivery_id, attempt_no),
    CONSTRAINT fk_result_delivery_attempt_delivery FOREIGN KEY (delivery_id) REFERENCES result_delivery(id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
CREATE INDEX idx_result_delivery_attempt_status ON result_delivery_attempt (status, started_at);
CREATE INDEX idx_result_delivery_attempt_execution ON result_delivery_attempt (execution_id);
```

### 2. 저장되는 enum

- `backend/src/main/java/com/bifos/assistant/chat/domain/type/DeliveryStatus.java`: `DELIVERING`, `DELIVERED`, `FAILED`, `STOPPED`. `boolean retryable()` 은 `FAILED` 와 `STOPPED` 에서 참이다
- `backend/src/main/java/com/bifos/assistant/chat/domain/type/DeliveryAttemptStatus.java`: `RUNNING`, `SUCCEEDED`, `FAILED`, `STOPPED`. `DeliveryStatus deliveryStatus()` 가 `RUNNING → DELIVERING`, `SUCCEEDED → DELIVERED`, `FAILED → FAILED`, `STOPPED → STOPPED` 를 돌려준다

### 3. 엔티티 셋

`chat.domain` 에 둔다. 칸 이름과 null 허용은 위 SQL 과 같다. enum 칸은 `@Enumerated(EnumType.STRING)` 이다.

- `ResultDelivery`(표 `result_delivery`): `static ResultDelivery opened(Long conversationId, Instant now)` 는 `DELIVERING`, `attempt_count` 1 로 만든다
- `ResultDeliveryItem`(표 `result_delivery_item`): `static ResultDeliveryItem of(Long deliveryId, String source, String resultKey)`
- `ResultDeliveryAttempt`(표 `result_delivery_attempt`): `static ResultDeliveryAttempt started(Long deliveryId, int attemptNo, Long noticeMessageId, Instant now)` 는 `RUNNING` 으로 만든다. `execution_id` 는 비워 둔다

### 4. 저장소 셋

`chat.infra` 에 둔다. 트랜잭션은 부르는 서비스가 연다.

- `ResultDeliveryRepository extends JpaRepository<ResultDelivery, Long>`
  - `@Modifying` `int changeStatus(Long id, DeliveryStatus status, Instant at)`: `update ResultDelivery d set d.status = :status, d.updatedAt = :at where d.id = :id`
- `ResultDeliveryItemRepository extends JpaRepository<ResultDeliveryItem, Long>`
  - `List<ResultDeliveryItem> findByDeliveryIdOrderByIdAsc(Long deliveryId)`
- `ResultDeliveryAttemptRepository extends JpaRepository<ResultDeliveryAttempt, Long>`
  - `@Modifying` `int attachExecution(Long id, Long executionId)`: `update ... set a.executionId = :executionId where a.id = :id and a.executionId is null`
  - `@Modifying` `int finish(Long id, DeliveryAttemptStatus status, String errorCode, Instant at)`: `update ... set a.status = :status, a.errorCode = :errorCode, a.finishedAt = :at where a.id = :id and a.status = com.bifos.assistant.chat.domain.type.DeliveryAttemptStatus.RUNNING`

### 5. `backend/src/main/java/com/bifos/assistant/chat/application/ResultDeliveryRecorder.java`

`@Service`. 전달 기록을 쓰는 자리는 이 클래스 하나다.

- `ResultDeliveryStart open(Long conversationId, List<DeliveryItemRef> items, Long noticeMessageId, Instant now)`
  `@Transactional`(부르는 쪽 트랜잭션에 참여). 묶음, 항목, 첫 시도를 저장하고 번호 둘을 돌려준다. `items` 가 비면 `IllegalArgumentException`
- `void attachExecution(Long attemptId, Long executionId)`: `@Transactional`. 시도에 부모 실행 줄을 잇는다
- `void finish(Long attemptId, DeliveryAttemptStatus status, String errorCode)`: `@Transactional`. 시도를 `RUNNING` 일 때만 닫고, 닫았으면 그 묶음의 상태를 `status.deliveryStatus()` 로 바꾼다. 이미 닫힌 시도면 아무것도 하지 않는다. `status` 가 `RUNNING` 이면 `IllegalArgumentException`
- 새 record 둘은 `chat.application.model` 에 파일 하나씩 둔다
  - `DeliveryItemRef(String source, String resultKey)`
  - `ResultDeliveryStart(Long deliveryId, Long attemptId)`
- 출처 이름 상수 `public static final String DELEGATION_SOURCE = "DELEGATION";` 를 이 클래스에 둔다

### 6. `backend/src/main/java/com/bifos/assistant/chat/application/AutoTurnResultSource.java`

`String source();` 를 더한다. 전달 묶음의 항목에 적는 출처 이름이고 40자 안의 대문자 이름이다. `"DELEGATION"` 은 쓰지 못한다고 Javadoc 에 적는다.
`backend/src/main/java/com/bifos/assistant/connector/application/ConnectorActionResultSource.java` 가 `"CONNECTOR_ACTION"` 을 돌려준다.

### 7. `backend/src/main/java/com/bifos/assistant/chat/application/TurnIntent.java`

`DelegationResults` 를 `record DelegationResults(Long attemptId) implements TurnIntent {}` 로 바꾼다. `attemptId` 는 이 turn 의 전달 시도 번호다.
알림 줄 저장이 `saveQuestion` 밖으로 나가므로 앞의 세 칸은 쓰는 곳이 없어진다. `instructionFor` 와 `DELEGATION_RESULTS_INSTRUCTION` 은 그대로 둔다.
`chat.application.model.AutoTurnDelivery` 는 `runDelegationResults` 안에서 계속 쓴다.

### 8. `backend/src/main/java/com/bifos/assistant/chat/application/ChatService.java`

`ResultDeliveryRecorder` 를 생성자 의존으로 더한다.

- `runDelegationResults`: 결과를 모으고 `route` 와 흐름 확인을 하는 지금 순서는 그대로 둔다. 흐름 확인 뒤, `runTurn` 전에 새 private 메서드 `recordDelivery(Conversation, List<AgentExecution>, List<String> notices, List<AutoTurnDelivery>, Consumer<ChatEvent>)` 를 부른다.
  이 메서드는 지금 `saveQuestion` 의 `DelegationResults` 분기가 하는 트랜잭션을 그대로 옮기고, 같은 트랜잭션 안에서 `resultDeliveries.open(conversationId, items, lastNoticeId, deliveredAt)` 을 부른다.
  항목은 위임 결과마다 `DeliveryItemRef(DELEGATION_SOURCE, executionId 의 글)`, `AutoTurnDelivery` 의 열쇠마다 `DeliveryItemRef(source.source(), key)` 이고 순서는 입력에 넣은 순서다. 저장한 알림 줄마다 `ChatEvent.system` 을 내는 것도 이 메서드가 한다. 시도 번호를 돌려준다
- `saveQuestion` 의 `DelegationResults` 분기를 지운다. `DelegationResults` 는 `Regenerate` 처럼 아무것도 저장하지 않고 돌아간다
- `begin`: `executions.start` 로 실행 줄을 만든 직후, `intent` 가 `DelegationResults` 면 `resultDeliveries.attachExecution(attemptId, execution.id())` 를 부른다. `modelTiers.resolve` 가 실패한 경로도 `begin` 을 거치므로 함께 잇는다. `attachExecution` 이 던지면 경고 로그만 남기고 삼킨다. 던지게 두면 실행 줄이 `RUNNING` 으로 남는다(`begin` 뒤에는 그 줄을 실패로 적는 경로가 없다)
- 새 private 메서드 `runDeliveryTurn(CurrentUser owner, Routed routed, String input, Long attemptId, TurnHandle handle, Consumer<ChatEvent> onEvent)` 가 `runTurn` 과 끝 사건 내기를 감싼다. phase 03 의 다시 전달도 이 메서드를 쓴다
  - `runTurn` 이 돌려준 `ChatTurn` 이 중지면 `finish(attemptId, STOPPED, null)`, 아니면 `finish(attemptId, SUCCEEDED, null)`
  - 예외로 끝났는데 `turns.isStopConfirmed(handle)` 가 참이면 `finish(attemptId, STOPPED, null)` 이다. 중지가 확정된 뒤 submit, relay, await 가 던지면 `cancelAndHoldIfStopConfirmed` 가 실행 줄을 `CANCELLED` 로 적으므로 시도도 중지로 닫는다
  - 그 밖의 `ApiException` 이면 `finish(attemptId, FAILED, ex.code().name())`, 다른 `RuntimeException` 이면 `finish(attemptId, FAILED, ErrorCode.INTERNAL_ERROR.name())` 를 부르고 원래 예외를 다시 던진다
  - 닫았는지를 지역 변수로 두고, `finally` 에서 아직 닫지 않았으면 `finish(attemptId, FAILED, ErrorCode.INTERNAL_ERROR.name())` 를 부른다. `Error` 로 끝나도 시도가 `RUNNING` 으로 남지 않게 하려는 것이다
  - `finish` 자체가 던지면 경고 로그만 남기고 원래 결과를 지킨다
  - 시도의 `error_code` 는 이 경로에서 turn 이 던진 예외의 코드다. 실행 줄의 `error_code` 와 다를 수 있다(예: 실행 줄은 Hermes 상태, 시도는 `HERMES_RUN_FAILED`). phase 02 의 기동 정리 경로는 실행 줄의 `error_code` 를 쓴다
  - 끝 사건(`done`, `stopped`)을 내는 것은 지금 `runDelegationResults` 끝의 코드를 옮긴다. 시도를 닫은 뒤에 낸다. 화면이 끝 사건을 받고 이력을 다시 읽을 때 묶음 상태가 이미 바뀌어 있어야 한다
- 클래스와 `runDelegationResults` 의 Javadoc 에서 「알림 줄 저장, 전했다는 표시, 자동 turn 수 증가는 한 트랜잭션」 문장에 전달 묶음과 첫 시도를 더하고 ADR-070 을 적는다

### 9. 이 phase 를 검증하는 `backend/src/test/java/com/bifos/assistant/chat/ResultDeliveryRecordTest.java`

`DelegationWakeServiceTest` 와 같은 틀이다. `@SpringBootTest(properties = "assistant.delegation-wake.enabled=true")`, `@ActiveProfiles("test")`, `@Import(ChatServiceTest.StubRuntime.class)`, `HermesRunEventStream` 은 `@MockitoBean`.
준비와 정리, `delegated(...)`, `finished(...)`, `awaitIdle(...)` 는 그 파일의 도우미를 옮겨 쓴다. 정리에서 새 표 셋도 지운다(항목과 시도를 묶음보다 먼저).
결과 글은 「조사 결과」 같은 지어낸 글만 쓴다.

| 검사 | 준비 | 기대 |
| --- | --- | --- |
| 성공 | 끝난 위임 결과 하나, 대역 Hermes 가 완료 | 묶음 하나가 `DELIVERED`, `attempt_count` 1. 항목 하나가 `DELEGATION` 과 그 실행 번호. 시도 하나가 `SUCCEEDED`, `execution_id` 가 자동 turn 의 실행 줄, `notice_message_id` 가 `SYSTEM` 줄, `finished_at` 이 채워짐 |
| provider 실패 | 대역 Hermes 가 `failed` 상태를 돌려줌 | 시도 `FAILED`, `error_code` 가 `HERMES_RUN_FAILED`, 묶음 `FAILED`. 위임 결과의 `result_delivered_at` 은 채워짐. 30초를 기다리지 않고 `tryWake` 를 다시 불러도 대역 Hermes 의 제출 수가 늘지 않음 |
| 실행 줄 전 실패 | `ContextAssembler` 를 `@MockitoSpyBean` 으로 감싸 `assemble` 이 한 번 던지게 함 | 시도 `FAILED`, `execution_id` 가 비어 있음, `error_code` 가 `INTERNAL_ERROR`(던진 예외가 `ApiException` 이면 그 코드). `SYSTEM` 줄은 남음 |
| 사용자 중지 | `stub().beforeAwait(...)` 에서 그 turn 의 실행 번호로 `chat.stop(dad, executionId)` | 시도 `STOPPED`, 묶음 `STOPPED`, `error_code` 비어 있음 |
| 중지를 확정한 뒤 await 가 던진다 | `beforeAwait` 에서 중지한 뒤 `stub().willFail(new ApiException(HERMES_UNAVAILABLE, ...))` | 실행 줄 `CANCELLED`, 시도 `STOPPED` |
| 시도를 잇다 실패한다 | `ResultDeliveryRecorder` 를 `@MockitoSpyBean` 으로 감싸 `attachExecution` 이 한 번 던지게 함 | turn 은 그대로 답을 남기고 시도는 `SUCCEEDED` 로 닫힘. `execution_id` 는 비어 있음 |
| 위임 결과와 승인 결과를 함께 | `AutoTurnResultSource` 를 구현한 테스트 전용 `@TestConfiguration` bean 이 결과 하나(`source()` 는 `TEST_SOURCE`)를 낸다 | 묶음 하나에 항목 둘. 순서는 `DELEGATION` 다음 `TEST_SOURCE` |
| 같은 결과의 종료 사건이 겹쳐 온다 | 같은 `DelegationFinished` 를 스레드 둘에서 동시에 냄 | 묶음 하나, 시도 하나, `ASSISTANT` 줄 하나, 대역 Hermes 제출 하나 |
| 같은 결과를 두 묶음에 넣으려 한다 | 트랜잭션 안에서 `open` 을 같은 항목으로 두 번 | 두 번째가 유일 제약으로 실패하고 그 트랜잭션이 되돌아감 |

`chat.stop` 이 쓰는 실행 번호는 `stub().beforeAwait` 안에서 찾는다. 기존 중지 검사 `backend/src/test/java/com/bifos/assistant/chat/ChatStopTest.java` 의 `latestExecution(dad)` 와 같은 방법을 따른다. `stub().willFail` 의 정확한 모양은 `backend/src/test/java/com/bifos/assistant/hermes/StubHermesRunsClient.java` 를 읽고 맞춘다.

## 검증

```bash
# cwd: backend/
./gradlew test --tests 'com.bifos.assistant.chat.ResultDeliveryRecordTest'
./gradlew test --tests 'com.bifos.assistant.chat.DelegationWakeServiceTest' --tests 'com.bifos.assistant.chat.ConnectorActionDeliveryTest' --tests 'com.bifos.assistant.chat.DelegationWakeUserLimitTest'
./gradlew test
```

```bash
# cwd: 저장소 root
scripts/check-mysql-migration.sh
node --test 'test/unit/**/*.test.ts'
scripts/quality.sh check
```

기대: 모두 종료 코드 0. `RepositoryQueryMysqlTest` 가 새 저장소 메서드 다섯을 실제 MySQL 에서 실행한다. 인자 타입 때문에 실패하면 `backend/src/test/java/com/bifos/assistant/testsupport/RepositoryQuerySweep.java` 에 그 타입의 값을 더한다. 건너뛰게 하지 않는다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/resources/db/migration/V66__result_delivery.sql` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/domain/type/DeliveryStatus.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/domain/type/DeliveryAttemptStatus.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/domain/ResultDelivery.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/domain/ResultDeliveryItem.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/domain/ResultDeliveryAttempt.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/infra/ResultDeliveryRepository.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/infra/ResultDeliveryItemRepository.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/infra/ResultDeliveryAttemptRepository.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/application/model/DeliveryItemRef.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/application/model/ResultDeliveryStart.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/application/ResultDeliveryRecorder.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/application/AutoTurnResultSource.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/connector/application/ConnectorActionResultSource.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/application/TurnIntent.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/application/ChatService.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/ResultDeliveryRecordTest.java` | 신규 |
