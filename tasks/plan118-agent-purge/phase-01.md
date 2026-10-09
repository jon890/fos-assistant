# Phase 01. 지운 에이전트 하나를 한 트랜잭션으로 지운다

**Execution profile**: deep

## 목표

지운 에이전트 하나의 행과 딸린 줄을 FK 순서에 맞게 한 트랜잭션으로 지우는 `AgentPurgeWriter` 와, 위 패키지가 자기 표를 맡는 port `AgentPurgeParticipant` 를 만든다.
정리 작업(언제, 몇 개, 재시도)은 이 phase 의 범위가 아니다. 실제 MySQL 에서 FK 에 막히지 않고 지워지는 것까지 확인한다.

**범위 외**: 예약 작업과 후보 고르기, profile 거두기, 설정은 phase 02 다. 화면 표기는 phase 03 이다.

## 컨텍스트

**근거 문서**: `backend/docs/adr/ADR-20261009-agent-purge.md`, `backend/docs/data-schema.md` 의 「지울 때」 와 각 표 절, `backend/docs/flow.md` 의 「에이전트 만들기와 지우기」 의 「지운 에이전트 정리」, `backend/docs/code-architecture.md` 의 「최상위 패키지의 층 순서」.

- 층 순서(`backend/src/test/java/com/bifos/assistant/architecture/TopLevelPackageOrder.java` 의 `ORDER`)에서 `agent` 는 `chat`, `proactive`, `connector` 보다 아래다. `agent` 는 그 패키지를 import 하지 못한다. 그래서 port 를 `agent.application` 에 두고 위 패키지가 구현한다. 본보기: `backend/src/main/java/com/bifos/assistant/agent/application/AgentConnectorDetacher.java` 와 그 구현 `connector/application/ConnectorBindingService.java`.
- 대화 정리 트랜잭션의 본보기: `backend/src/main/java/com/bifos/assistant/chat/application/ConversationPurgeWriter.java`. 벌크 삭제 본보기: `chat/infra/ChatPendingMessageRepository.java` 의 `deleteAllOf` (`@Modifying(flushAutomatically = true, clearAutomatically = true)` 와 JPQL `delete`).
- 에이전트 행 잠금: `AgentRepository.findByIdForUpdate(Long id)`. `Agent` 의 접근자는 `deletedAt()`, `profileManaged()`, `hermesProfile()`, `isDeleted()` 다.
- `agent` 를 FK 로 가리키는 표와 이 phase 의 처리:

| 표 | 엔티티와 저장소 | 처리 |
| --- | --- | --- |
| `agent_memory_collection` | `AgentMemoryCollection`(키는 `@EmbeddedId AgentMemoryCollectionId id`, 칸 `id.agentId`), `AgentMemoryCollectionRepository` | 지운다 |
| `agent_memory_collection_change` | `AgentMemoryCollectionChange.agentId`, `AgentMemoryCollectionChangeRepository` | 지운다 |
| `agent_toolset_request` | `AgentToolsetRequest.agentId`, `AgentToolsetRequestRepository` | 지운다 |
| `agent_connector_binding` | `ConnectorBinding.agent`(`@ManyToOne`, JPQL 은 `b.agent.id`), `ConnectorBindingRepository` | 지운다 |
| `connector_connection.agent_id` | 엔티티 `ConnectorConnection` 이 지금 이 칸을 매핑하지 않는다. 읽기 전용 칸 `legacyAgentId` 를 더한다. `ConnectorConnectionRepository` | 비운다 |
| `connector_action.agent_id` | `ConnectorAction.agentId`(지금 `@Column(name = "agent_id", nullable = false)`), `ConnectorActionRepository` | 비운다. 칸을 NULL 허용으로 바꾸는 새 마이그레이션이 필요하다 |
| `proactive_loop_setting` | `ProactiveLoopSetting.agentId`, `ProactiveLoopSettingRepository` | 지운다 |
| `proactive_check` | `ProactiveCheck.agentId`, `ProactiveCheckRepository` | 지운다. 자식 표(`proactive_check_finding`, `proactive_check_problem`, `proactive_value_evaluation`, `proactive_autonomy_decision`, `proactive_loop_run`, `decision_feedback_event`)는 MySQL FK 의 `ON DELETE CASCADE` 와 `ON DELETE SET NULL` 이 처리한다 |

- `conversation`, `agent_execution`, `task` 는 `agent_id` 에 FK 가 없고 그대로 둔다.
- 시험 DB: 일반 시험은 H2 이고 스키마를 엔티티로 만든다(`backend/src/test/resources/application-test.yml` 의 `ddl-auto: create-drop`, Flyway 꺼짐). 그래서 H2 에는 마이그레이션의 FK 와 CASCADE 가 없다. 예외로 `ConnectorBinding.agent` 의 `@ManyToOne` 은 H2 에도 `agent_connector_binding → agent` FK 를 만든다. 바인딩을 에이전트 행보다 먼저 지우지 않으면 H2 에서도 실패한다. FK 순서는 `@Tag("mysql")` 하위 클래스가 Flyway 로 만든 실제 MySQL 에서 확인한다. 본보기: `backend/src/test/java/com/bifos/assistant/agent/ToolsetRequestFlowMysqlTest.java` (상위 `ToolsetRequestFlowTest` 를 상속하고 `@DynamicPropertySource` 로 `MysqlTestDatabase` 를 끼운다).
- 저장소에 메서드를 더하면 `RepositoryQueryMysqlTest` 가 스스로 찾아 실제 MySQL 에서 한 번 실행한다. 같은 저장소에 같은 이름의 오버로드를 두지 않는다(`RepositoryQuerySweep` 이 실패한다).
- 마이그레이션 규칙: `backend/docs/data-schema.md` 의 「마이그레이션 작성 규칙」. 이미 적용된 파일은 고치지 않는다(`test/unit/migration-immutable.test.ts`).

## 의도 메모

- FK 를 `ON DELETE CASCADE` 로 바꾸지 않는다. 무엇이 함께 지워지는지를 코드(참여자)에 모은다. `proactive_check` 의 자식 표만 이미 있는 CASCADE 를 쓴다.
- `connector_action` 줄은 지우지 않는다. 외부 서비스에 쓴 승인 이력이고 남은 대화의 승인 카드가 읽는다. 비어 있는 `agent_id` 는 승인할 때 `ConnectorActionApproval` 이 바인딩을 찾지 못해 `NOT_EXECUTABLE` 로 거절한다(코드는 바꾸지 않는다).
- 대기 판정(정리되지 않은 대화가 있나)은 쓰기 잠금을 잡은 뒤 같은 트랜잭션에서 한다.

## 작업 항목

### 1. `backend/src/main/resources/db/migration/V20261009132714__connector_action_agent_nullable.sql` 신규

```sql
-- 지운 에이전트를 정리할 때 승인 이력은 남기고 에이전트만 비운다(ADR-20261009 / agent-purge).
ALTER TABLE connector_action MODIFY COLUMN agent_id BIGINT NULL;
```

FK `fk_connector_action_agent` 는 그대로 둔다. 비어 있는 값은 FK 에 걸리지 않는다.
`backend/src/main/java/com/bifos/assistant/connector/domain/ConnectorAction.java` 의 `agentId` 칸을 `@Column(name = "agent_id")` 로 바꾸고 Javadoc 에 「지운 에이전트를 정리하면 비어 있다」 를 더한다.

### 2. port `backend/src/main/java/com/bifos/assistant/agent/application/AgentPurgeParticipant.java` 신규

```java
public interface AgentPurgeParticipant {
    /** 그 에이전트 행을 아직 지우면 안 되는가. 정리 트랜잭션 안에서 부른다. */
    boolean blocksPurge(Long agentId);

    /** 그 에이전트를 가리키는 자기 표의 줄을 지우거나 비운다. 정리 트랜잭션 안에서 부른다. */
    void release(Long agentId);
}
```

Javadoc 에 ADR-20261009 / agent-purge 와 「`agent` 보다 위 패키지가 구현한다」 를 적는다.

### 3. 참여자 셋 신규

모두 `@Component` 이고 `@Transactional(propagation = Propagation.MANDATORY)` 로 부르는 쪽 트랜잭션 안에서만 돈다.

- `backend/src/main/java/com/bifos/assistant/chat/application/ConversationAgentPurge.java`: `blocksPurge` 는 `ConversationRepository.existsByAgentIdAndDeletedAtIsNotNullAndPurgedAtIsNull(agentId)`. `release` 는 아무것도 하지 않는다(대화 줄은 남긴다는 주석).
- `backend/src/main/java/com/bifos/assistant/proactive/application/ProactiveAgentPurge.java`: `blocksPurge` 는 거짓. `release` 는 `ProactiveLoopSettingRepository.deleteAllOfAgent(agentId)` 다음 `ProactiveCheckRepository.deleteAllOfAgent(agentId)`.
- `backend/src/main/java/com/bifos/assistant/connector/application/ConnectorAgentPurge.java`: `blocksPurge` 는 거짓. `release` 는 `ConnectorBindingRepository.deleteAllOfAgent(agentId)`, `ConnectorConnectionRepository.clearAgentOf(agentId)`, `ConnectorActionRepository.clearAgentOf(agentId)` 순이다.

저장소 메서드(모두 `@Modifying(flushAutomatically = true, clearAutomatically = true)`, 반환 `int`):

| 저장소 | 메서드 | 질의 |
| --- | --- | --- |
| `chat/infra/ConversationRepository` | `boolean existsByAgentIdAndDeletedAtIsNotNullAndPurgedAtIsNull(Long agentId)` | 파생 질의(`@Modifying` 아님) |
| `proactive/infra/ProactiveLoopSettingRepository` | `deleteAllOfAgent(@Param("agentId") Long agentId)` | `delete from ProactiveLoopSetting s where s.agentId = :agentId` |
| `proactive/infra/ProactiveCheckRepository` | `deleteAllOfAgent(@Param("agentId") Long agentId)` | `delete from ProactiveCheck c where c.agentId = :agentId` |
| `connector/infra/ConnectorBindingRepository` | `deleteAllOfAgent(@Param("agentId") Long agentId)` | `delete from ConnectorBinding b where b.agent.id = :agentId` |
| `connector/infra/ConnectorConnectionRepository` | `clearAgentOf(@Param("agentId") Long agentId)` | `update ConnectorConnection c set c.legacyAgentId = null where c.legacyAgentId = :agentId` |
| `connector/infra/ConnectorActionRepository` | `clearAgentOf(@Param("agentId") Long agentId)` | `update ConnectorAction a set a.agentId = null where a.agentId = :agentId` |
| `agent/infra/AgentMemoryCollectionRepository` | `deleteAllOfAgent(@Param("agentId") Long agentId)` | `delete from AgentMemoryCollection c where c.id.agentId = :agentId` |
| `agent/infra/AgentMemoryCollectionChangeRepository` | `deleteAllOfAgent(@Param("agentId") Long agentId)` | `delete from AgentMemoryCollectionChange c where c.agentId = :agentId` |
| `agent/infra/AgentToolsetRequestRepository` | `deleteAllOfAgent(@Param("agentId") Long agentId)` | `delete from AgentToolsetRequest r where r.agentId = :agentId` |
| `agent/infra/AgentRepository` | `deletePurged(@Param("id") Long id)` | `delete from Agent a where a.id = :id` |

`backend/src/main/java/com/bifos/assistant/connector/domain/ConnectorConnection.java` 에 칸 하나를 더한다.

```java
/** 바인딩 앞의 옛 커넥터 에이전트다. 엔티티는 쓰지 않고, 지운 에이전트를 정리할 때 벌크 갱신만 비운다(ADR-20261009 / agent-purge). */
@Getter(AccessLevel.NONE)
@Column(name = "agent_id", insertable = false, updatable = false)
private Long legacyAgentId;
```

`insertable = false` 라 새 연결은 지금처럼 `agent_id` 를 비운 채 저장된다. 이 매핑이 없으면 엔티티로 스키마를 만드는 H2 시험에 칸이 없어 갱신이 실패한다.
클래스에 `@Getter` 와 `@Accessors(fluent = true)` 가 붙어 있어 필드에 `@Getter(AccessLevel.NONE)` 를 달아 getter 를 막는다. 클래스 Javadoc 에서 `agent_id` 를 매핑하지 않는다고 적은 문장을 「`agent_id` 는 지운 에이전트 정리의 벌크 갱신만 쓰는 읽기 전용 칸으로 매핑한다」 로 고친다.
Hibernate 가 `updatable = false` 칸의 JPQL 벌크 갱신을 거절하면 같은 메서드를 native 질의 `update connector_connection set agent_id = null where agent_id = :agentId` 로 바꾼다. 매핑이 있으므로 H2 에도 칸이 있다.

### 4. `backend/src/main/java/com/bifos/assistant/agent/application/AgentPurgeWriter.java` 신규

`@Component`, 생성자 주입. 필드: `AgentRepository agents`, `AgentMemoryCollectionRepository collections`, `AgentMemoryCollectionChangeRepository collectionChanges`, `AgentToolsetRequestRepository toolsetRequests`, `List<AgentPurgeParticipant> participants`.

```java
/** 지운 지 cutoff 앞인 에이전트 하나를 지운다. */
@Transactional
public AgentPurgeOutcome purge(Long agentId, Instant cutoff)
```

1. `agents.findByIdForUpdate(agentId)` 가 비었거나 `deletedAt()` 이 null 이거나 `deletedAt()` 이 `cutoff` 보다 뒤면 `AgentPurgeOutcome.GONE`.
2. 참여자 가운데 하나라도 `blocksPurge(agentId)` 가 참이면 `AgentPurgeOutcome.WAITING`.
3. 참여자 모두의 `release(agentId)`, 그 뒤 `toolsetRequests.deleteAllOfAgent`, `collectionChanges.deleteAllOfAgent`, `collections.deleteAllOfAgent`, 마지막에 `agents.deletePurged(agentId)`. `AgentPurgeOutcome.PURGED`.

`backend/src/main/java/com/bifos/assistant/agent/application/model/AgentPurgeOutcome.java` 신규 enum: `PURGED`, `WAITING`, `GONE`. 각 값에 한 줄 Javadoc.

### 5. 주석 고치기

- `backend/src/main/java/com/bifos/assistant/agent/domain/Agent.java` 의 `deletedAt` Javadoc: 「행은 지우지 않는다…」 를 「지운 지 `assistant.agents.purge-after` 가 지나면 정리 작업이 행을 지운다(ADR-20261009 / agent-purge). 대화, 실행, 사용량은 행이 없어도 「지운 에이전트」 로 그린다」 로 바꾼다.
- `backend/src/main/java/com/bifos/assistant/chat/application/ConversationPurger.java` 188줄 주석 「에이전트 줄은 지우지 않으므로 에이전트 없이 돈 실행만 여기 온다」 를 「지운 에이전트는 7일 뒤 행이 사라진다. 관리형 profile 의 session 은 profile 과 함께 이미 지워졌다」 로 바꾼다. 동작은 바꾸지 않는다.

### 6. 이 phase 를 검증하는 `backend/src/test/java/com/bifos/assistant/agent/AgentPurgeWriterTest.java` 신규

`@BackendIntegrationTest`. 매 검사에 사용자와 에이전트를 새로 만든다(`ConversationPurgerTest` 의 `setUp` 본보기, 이메일은 `@example.test`). 에이전트의 `deleted_at` 은 `Agent.markDeleted(instant)` 뒤 `agents.saveAndFlush` 로 `2000-01-01T00:00:00Z` 를 적어 다른 검사의 에이전트와 겹치지 않게 한다. `JdbcTemplate` 에 `Timestamp.from(...)` 을 넘기면 JVM 시간대로 적혀, `hibernate.jdbc.time_zone: UTC` 로 읽는 쪽과 어긋난다. 다른 시각 칸도 엔티티로 적거나 UTC `Calendar` 를 쓴다. `cutoff` 는 `2000-01-08T00:00:00Z` 다.

- 「딸린 줄이 모두 있는 지운 에이전트를 지우고 대화와 실행은 남긴다」: `agent_memory_collection`, `agent_memory_collection_change`, `agent_toolset_request`, `agent_connector_binding`(연결 하나와 함께), `connector_action` 한 줄, 그 연결의 `connector_connection.agent_id` 를 `jdbc.update("UPDATE connector_connection SET agent_id = ? WHERE id = ?", agentId, connectionId)` 로 적은 값, `proactive_loop_setting`, `proactive_check`, 지우지 않은 대화 하나, 그 대화의 `agent_execution` 한 줄을 만든다. 결과가 `PURGED`, `agent` 행과 앞의 설정 줄이 없고, `connector_action` 줄은 남되 `agent_id` 가 null, 연결 줄은 남되 `connector_connection.agent_id` 가 null, 대화와 실행 줄은 남는다.
- 「cutoff 보다 늦게 지운 에이전트는 남긴다」: `deleted_at` 을 `2000-01-08T00:00:01Z` 로 두면 `GONE` 이고 행이 남는다.
- 「지우지 않은 에이전트는 건드리지 않는다」: `deleted_at` 이 null 이면 `GONE` 이고 행과 설정이 남는다.
- 「지웠지만 정리되지 않은 대화가 있으면 미룬다」: 대화의 `deleted_at` 을 적고 `purged_at` 은 비우면 `WAITING` 이고 아무 줄도 지워지지 않는다. `purged_at` 까지 적으면 `PURGED`.

줄을 만드는 방법은 엔티티 팩터리가 있으면 그것을, 없으면 `JdbcTemplate` 의 `INSERT` 를 쓴다. H2 는 엔티티로 만든 스키마라 엔티티가 매핑하지 않는 칸에는 값을 넣지 않는다.

### 7. `backend/src/test/java/com/bifos/assistant/agent/AgentPurgeWriterMysqlTest.java` 신규

`@Tag("mysql")`, `AgentPurgeWriterTest` 를 상속하고 `ToolsetRequestFlowMysqlTest` 와 같은 `@DynamicPropertySource` 를 둔다.
상위 클래스의 검사가 모두 실제 MySQL 의 FK 아래에서 돈다. 여기에 검사 하나를 더한다.

- 「살펴보기의 자식 줄은 FK 가 함께 지우거나 비운다」: `proactive_check` 에 `proactive_check_finding` 한 줄과 그 check 를 `source_check_id` 로 가리키는 `decision_feedback_event` 한 줄을 `JdbcTemplate` 으로 넣고 정리한 뒤, finding 줄은 없고 feedback 줄의 `source_check_id` 칸이 null 인지 본다. 칸 이름은 `V69__proactive_check.sql` 과 `V20261007044901__decision_feedback_event.sql` 에서 읽는다.

## 검증

```bash
cd backend && ./gradlew test --tests 'com.bifos.assistant.agent.AgentPurgeWriterTest' --tests 'com.bifos.assistant.chat.ConversationPurgerTest' --tests 'com.bifos.assistant.architecture.*'
scripts/check-mysql-migration.sh
cd backend && ./gradlew spotlessCheck checkstyleMain checkstyleTest
```

- 첫 줄: 새 검사와 대화 정리 검사, 구조 규칙(층 순서 포함)이 통과한다.
- 둘째 줄: `AgentPurgeWriterMysqlTest`, `RepositoryQueryMysqlTest`(새 저장소 메서드), `MysqlMigrationTest`(새 마이그레이션 적용과 `validate`)가 Docker 의 MySQL 8.4 에서 통과한다. 종료 코드 0. 로컬 부하를 줄이려 `.omc/scripts/heavy-lock scripts/check-mysql-migration.sh` 로 감싸 돌린다.
- 셋째 줄: 포맷과 코드 규칙. 포맷이 바뀌면 기능 커밋과 나눠 커밋한다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/resources/db/migration/V20261009132714__connector_action_agent_nullable.sql` | 신규 |
| `backend/src/main/java/com/bifos/assistant/connector/domain/ConnectorAction.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/connector/domain/ConnectorConnection.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/agent/application/AgentPurgeParticipant.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/agent/application/AgentPurgeWriter.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/agent/application/model/AgentPurgeOutcome.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/application/ConversationAgentPurge.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/proactive/application/ProactiveAgentPurge.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/connector/application/ConnectorAgentPurge.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/infra/ConversationRepository.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/proactive/infra/ProactiveLoopSettingRepository.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/proactive/infra/ProactiveCheckRepository.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/connector/infra/ConnectorBindingRepository.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/connector/infra/ConnectorConnectionRepository.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/connector/infra/ConnectorActionRepository.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/agent/infra/AgentMemoryCollectionRepository.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/agent/infra/AgentMemoryCollectionChangeRepository.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/agent/infra/AgentToolsetRequestRepository.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/agent/infra/AgentRepository.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/agent/domain/Agent.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/application/ConversationPurger.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/agent/AgentPurgeWriterTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/agent/AgentPurgeWriterMysqlTest.java` | 신규 |
