# Phase 01. 받는 collection 을 바꾸고 변경 기록을 남기는 agent 쪽 쓰기

**Execution profile**: standard

## 목표

`agent_memory_collection` 을 관리자가 고른 목록으로 바꾸고, 바뀐 collection 마다 `agent_memory_collection_change` 에 한 줄을 남기는 서비스 메서드를 만든다.

**범위 외**: HTTP 경로, 그룹의 collection 목록 검사, 항목 수 세기는 Phase 02. 화면은 Phase 03.

## 컨텍스트

- 판정 규칙은 그대로다. `AgentMemoryCollectionService.grantsOf(Long agentId)` 를 바꾸지 않는다.
- `agent` 패키지는 층 순서에서 `memory` 보다 아래다(`backend/src/test/java/com/bifos/assistant/architecture/TopLevelPackageOrder.java`). 이 phase 는 `memory` 를 import 하지 않는다. `user` 는 써도 된다.
- 엔티티 모양은 `backend/src/main/java/com/bifos/assistant/agent/domain/AgentMemoryCollection.java` 를 따른다(`@Getter`, `@Accessors(fluent = true)`, 보호된 기본 생성자, `of(...)` 정적 팩터리).
- 저장되는 enum 은 `<기능>.domain.type` 에 둔다(`backend/AGENTS.md` 「enum 은 저장 여부로 둘 곳을 정한다」).
- 잠금은 `AgentRepository.findByIdForUpdate(Long id)` 를 쓴다. 잠금을 기다린다. `findByCodeForUpdate` 는 경합하면 곧바로 실패하므로 쓰지 않는다.

**근거 문서**: `docs/adr/ADR-20261008-agent-memory-grants-admin.md`, `docs/backend/schema/memory.md` 의 「agent_memory_collection」 과 「agent_memory_collection_change」, `docs/backend/memory.md` 의 「관리자가 에이전트의 collection 을 바꿀 때」

## 의도 메모

- 마이그레이션이 넣은 `core` 와 `grantDefaultCollection` 이 넣는 `core` 는 기록하지 않는다. 사람이 바꾼 것만 남긴다.
- 민감 허용만 바꾸면 줄을 지우고 다시 넣지 않는다. `created_at` 은 붙인 시각으로 남는다.
- 바뀐 것이 없으면 아무것도 쓰지 않는다.

## 작업 항목

### 1. `backend/src/main/resources/db/migration/V20261008020943__agent_memory_collection_change.sql`

```sql
CREATE TABLE agent_memory_collection_change (
    id BIGINT NOT NULL AUTO_INCREMENT,
    agent_id BIGINT NOT NULL,
    collection VARCHAR(64) NOT NULL,
    change_type VARCHAR(20) NOT NULL,
    allow_sensitive BOOLEAN NOT NULL,
    changed_by_user_id BIGINT NOT NULL,
    changed_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_agent_memory_collection_change_agent (agent_id, id),
    CONSTRAINT fk_agent_memory_collection_change_agent FOREIGN KEY (agent_id) REFERENCES agent(id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
```

머리에 한국어 주석 한 줄(무엇을 남기는 표인지, ADR-20261008 / agent-memory-grants-admin). `changed_by_user_id` 에는 외래 키를 걸지 않는다.

### 2. `agent.domain.type.AgentMemoryCollectionChangeType`

`GRANTED`, `REVOKED`, `SENSITIVE_CHANGED`. 각 값에 한 줄 Javadoc.

### 3. `agent.domain.AgentMemoryCollectionChange` 엔티티

표 `agent_memory_collection_change`. 칸: `id`(IDENTITY), `agentId`, `collection`(length 64), `changeType`(`@Enumerated(STRING)`, length 20), `allowSensitive`, `changedByUserId`, `changedAt`(Instant). 정적 팩터리 `of(Long agentId, String collection, AgentMemoryCollectionChangeType type, boolean allowSensitive, Long changedByUserId, Instant now)`.

### 4. `agent.domain.AgentMemoryCollection` 에 메서드 하나

`public void changeAllowSensitive(boolean allowSensitive)`.

### 5. `agent.infra.AgentMemoryCollectionChangeRepository`

`JpaRepository<AgentMemoryCollectionChange, Long>`, 메서드 `List<AgentMemoryCollectionChange> findTop10ByAgentIdOrderByIdDesc(Long agentId)`.

### 6. `agent.application.AgentMemoryCollectionService` 에 메서드 넷

- `Agent requireEditableAgent(String code)`: `agents.findByCode(code)`. 없거나 `isDeleted()` 면 `ApiException(AGENT_NOT_FOUND, "no such agent")`. `connectorManaged()` 면 `ApiException(FORBIDDEN, "connector-managed agents receive no memory")`. `@Transactional(readOnly = true)`.
- `List<AgentMemoryCollection> rowsOf(Long agentId)`: `grants.findByIdAgentId(agentId)`. 읽기 전용.
- `List<AgentMemoryCollectionChange> recentChangesOf(Long agentId)`: `changes.findTop10ByAgentIdOrderByIdDesc(agentId)`. 읽기 전용.
- `void replace(Long agentId, Map<String, Boolean> next, Long changedByUserId)`: `@Transactional`. 순서:
  1. `agents.findByIdForUpdate(agentId)` 로 잠근다. 없거나 지웠거나 커넥터 에이전트면 위와 같은 예외.
  2. 지금 줄을 `rowsOf` 로 읽어 collection 으로 묶는다.
  3. `next` 에만 있으면 `AgentMemoryCollection.of(agentId, key, allow, now)` 를 저장하고 `GRANTED` 기록(allow).
  4. 지금 줄에만 있으면 지우고 `REVOKED` 기록(지우기 전의 `allowSensitive`).
  5. 둘 다 있고 민감 허용이 다르면 `changeAllowSensitive` 후 `SENSITIVE_CHANGED` 기록(바꾼 뒤의 값).
  6. 기록은 collection key 순서로 넣는다. 모든 기록의 `changedAt` 은 `clock.instant()` 한 값이다.
  7. 바꾼 뒤 `log.info("agent memory collections changed agentId={} by={} granted={} revoked={} sensitiveChanged={}", ...)`. collection key 만 남기고 항목 내용은 남기지 않는다.

  클래스 Javadoc 의 첫 문장에 「관리자가 고른 목록으로 바꾸고 변경을 기록한다」 를 더한다. 새 필드 `AgentMemoryCollectionChangeRepository changes` 를 생성자 주입에 더한다.

### 7. 이 phase 를 검증하는 `backend/src/test/java/com/bifos/assistant/agent/AgentMemoryCollectionServiceTest.java`

기존 시험에 더한다. `@BackendIntegrationTest`. 각 시험은 `agents.save(agent())` 로 새 에이전트를 만들어 쓴다.

- 「관리자가 고른 목록으로 바꾸면 붙이고 떼고 민감 허용을 바꾸고 한 번에 기록한다」: 새 에이전트(core 만) → `replace(id, {career:true}, 7L)`. `grantsOf` 가 `career` 만, 민감 `career`. 기록 2줄: `career GRANTED true`, `core REVOKED false`, 둘 다 `changedByUserId` 7. 이어 `replace(id, {career:false}, 7L)` → `SENSITIVE_CHANGED false` 한 줄이 더해지고 `career` 줄의 `createdAt` 이 그대로다.
- 「바뀐 것이 없으면 기록하지 않는다」: `replace(id, {core:false}, 7L)` → 기록 0줄.
- 「빈 목록이면 모두 떼어 그 에이전트는 아무것도 받지 않는다」: `replace(id, {}, 7L)` → `grantsOf` 빈 값.
- 「옛 커넥터 에이전트와 지운 에이전트는 바꾸지 못한다」: 커넥터 에이전트를 만드는 방법은 같은 파일의 기존 시험(`grantsOf` 가 커넥터 에이전트에 빈 값을 내는 시험)을 따른다. `FORBIDDEN`, 지운 에이전트는 `AGENT_NOT_FOUND`.

## 검증

```bash
cd backend && ./gradlew test --tests 'com.bifos.assistant.agent.AgentMemoryCollectionServiceTest' --tests 'com.bifos.assistant.architecture.*'
cd backend && ./gradlew checkstyleMain checkstyleTest
node scripts/check-migration-versions.mjs
```

모두 종료 코드 0. Docker 가 있으면 `scripts/check-mysql-migration.sh` 로 Flyway 스키마와 엔티티가 맞는지도 본다(없으면 CI 의 backend job 이 본다).

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/resources/db/migration/V20261008020943__agent_memory_collection_change.sql` | 신규 |
| `backend/src/main/java/com/bifos/assistant/agent/domain/type/AgentMemoryCollectionChangeType.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/agent/domain/AgentMemoryCollectionChange.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/agent/domain/AgentMemoryCollection.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/agent/infra/AgentMemoryCollectionChangeRepository.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/agent/application/AgentMemoryCollectionService.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/agent/AgentMemoryCollectionServiceTest.java` | 수정 |
