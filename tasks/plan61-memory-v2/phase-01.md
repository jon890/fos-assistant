# Phase 01. 기존 Memory 를 넓힌 칸으로 옮기고 에이전트 기준으로 판정한다

**Execution profile**: deep

## 목표

`memory` 표에 collection, 종류, 꺼내는 방식, 민감도, 판, 출처 칸을 더하고 기존 줄을 옮긴다.
주입과 `memory_read` 가 에이전트의 허용 collection 과 민감도를 함께 판정하게 한다.
같은 사용자와 같은 에이전트가 받는 글은 옮기기 전과 같아야 한다.

**범위 외**: collection 탭과 문서 편집 화면, 에이전트의 collection 설정 경로, 다른 서비스용 문서 API, 들여오기 API, 민감 항목 암호화, 검색 엔진, `always_inject` 칸 제거. `docs/code-architecture.md` 의 「Memory」 아래 「다음」 이 목록을 갖는다.

## 컨텍스트

- 지금 `MemoryService` 는 `findAll()` 로 모두 읽어 메모리에서 거른다. `alwaysInjectedFor(user)`, `indexedFor(user)`, `bodyFor(user, id)` 가 사용자만 받는다
- `ContextAssembler.assemble(user)` 를 `ChatService`, `AgentRunner`, `MemoryController` 가 부른다. 앞의 둘은 `Agent.connectorManaged()` 면 조립하지 않는다
- `McpToolService.readMemory` 는 `McpCaller.originExecution()` 을 갖고 있다. 그 실행의 `agentId()` 로 에이전트를 안다
- 에이전트를 저장하는 자리는 셋이다. `UserProvisioningService.createFirstAgent`, `AgentLifecycleService.provisionAgent`, `AgentAdminController.create`
- `context` 패키지가 `agent` 를 쓰면 `ArchitectureRules.TOP_LEVEL_PACKAGES_FREE_OF_CYCLES` 가 새 간선으로 실패한다. `memory -> agent` 는 이미 있는 간선이다. `agent -> memory` 는 없다
- 마이그레이션 SQL 은 H2 의 MySQL 모드에서도 돌아야 한다(`backend/AGENTS.md` 의 「엔티티와 마이그레이션은 따로 논다」). `test/e2e` 는 Flyway 로 만든 H2 스키마를 `validate` 로 검증한다
- 새 enum 은 `<기능>.domain.type` 에 둔다(`ArchitectureRules.ENUMERATED_FIELDS_USE_DOMAIN_TYPE`). 엔티티 접근자는 Lombok `@Getter` 와 `@Accessors(fluent = true)` 로 둔다

**근거 문서**: `docs/adr/ADR-051-memory-는-collection-종류-꺼내는-방식-민감도-판-출처를-가진다.md`, `docs/adr/ADR-052-에이전트는-허용된-collection-의-memory-만-받는다.md`, `docs/data-schema.md` 의 「memory」, 「memory_revision」, 「memory_collection」, 「agent_memory_collection」, `docs/code-architecture.md` 의 「Memory」, `docs/flow.md` 의 「Memory 본문을 읽는 길」

## 의도 메모

- `agent_memory_collection` 은 `agent` 패키지가 갖는다. 에이전트의 설정이고, `memory` 가 `agent` 를 읽는 방향은 이미 있다
- 줄이 없으면 `core` 를 받는 것으로 보지 않는다. 표가 말하는 것과 판정이 달라진다. 새 에이전트의 `core` 는 저장소의 저장이 내는 `AgentCreated` 사건 하나로 넣는다. 경로 셋에 따로 넣으면 하나가 빠진다
- `ContextAssembler` 는 `Agent` 가 아니라 에이전트 번호를 받는다. 패키지 순환을 늘리지 않기 위해서다
- API 응답과 화면은 바꾸지 않는다. `alwaysInject` 칸을 그대로 주고받는다
- `always_inject` 칸은 지우지 않는다. 쓸 때마다 `retrieval` 과 맞추고 읽을 때는 `retrieval` 만 본다

## 작업 항목

### 1. `backend/src/main/resources/db/migration/V47__memory_v2.sql`

- `memory` 에 `collection`(기본 `core`), `entry_type`(기본 `MEMORY`), `document_key`, `retrieval`(기본 `SEARCH`), `sensitivity`(기본 `NORMAL`), `revision`(기본 1), `source_type`, `source_ref`, `source_date` 를 더한다. 모두 기본값이 있거나 비어도 되어 옛 코드의 INSERT 가 통과한다
- `always_inject = TRUE` 인 줄의 `retrieval` 을 `ALWAYS` 로 바꾼다
- 유일 색인 `uk_memory_user_document(owner_user_id, collection, document_key)`, `uk_memory_group_document(group_id, collection, document_key)`
- 표 `memory_revision`, `memory_collection`, `agent_memory_collection`. 칸은 `docs/data-schema.md` 그대로다
- `memory_collection` 에 사용자가 있는 그룹마다 key 일곱 개를 넣는다
- `agent_memory_collection` 에 `connector_managed = FALSE` 인 모든 에이전트의 `core` 를 넣는다

### 2. 도메인과 저장소

- `memory.domain.type` 에 `MemoryEntryType`, `MemoryRetrieval`, `MemorySensitivity`, `MemoryChangeType`. 기존 `MemoryScope`, `MemoryStatus` 도 이 패키지로 옮긴다
- `memory.domain.Memory` 에 새 칸을 더한다. `revise(content, retrieval, sensitivity, dedupKey, at)` 가 판을 올리고 `always_inject` 를 맞춘다. 시각은 인자로 받는다
- `memory.domain.MemoryPlacement`, `MemoryRevision`, `MemoryRevisionId`, `MemoryCollection`, `MemoryCollectionId`
- `memory.infra.MemoryRevisionRepository`, `MemoryCollectionRepository`, `MemoryQueries`. `MemoryRepository` 는 `JpaSpecificationExecutor<Memory>` 를 더 받는다
- `agent.domain.AgentMemoryCollection`, `AgentMemoryCollectionId`, `AgentCreated`. `Agent` 는 처음 저장할 때 `AgentCreated` 를 한 번 낸다(`@DomainEvents`)
- `agent.infra.AgentMemoryCollectionRepository`

### 3. 판정과 호출부

- `agent.application.AgentMemoryCollectionService`: `grantsOf(Long agentId)` 와 `AgentCreated` 를 받는 `grantDefaultCollection`. 결과 타입은 `AgentMemoryGrants`
- `memory.application.model.MemoryAccess`
- `memory.application.MemoryService`: `accessOf(agentId)`, `alwaysInjectedFor(user, access)`, `indexedFor(user, access)`, `bodyFor(user, access, id)`, `revisionsOf(user, id)`. 수정과 삭제가 `MemoryRevision` 을 남긴다. 민감 항목을 `ALWAYS` 로 두려 하면 `ErrorCode.MEMORY_SENSITIVE_ALWAYS`
- `memory.application.MemoryCollectionService`
- `context.ContextAssembler`: `assemble(user, agentId)` 와 `assembleForOwner(user)`
- `ChatService`, `AgentRunner` 는 에이전트 번호를 넘긴다. `MemoryController` 는 `assembleForOwner` 를 쓴다. `McpToolService.readMemory` 는 origin 실행의 에이전트로 판정한다

### 4. 이 phase 를 검증하는 테스트

- `backend/src/test/java/com/bifos/assistant/memory/MemoryV2MigrationTest.java`: V46 에 줄을 넣고 V47 로 올린다. 줄이 남고 `retrieval` 이 옮겨졌는지, 커넥터가 아닌 에이전트만 `core` 를 받았는지, 그룹마다 key 일곱 개인지, 옛 칸만으로 INSERT 가 되는지, `document_key` 유일 제약
- `backend/src/test/java/com/bifos/assistant/memory/MemoryServiceTest.java`: 고친 뒤 앞선 판이 남는다, 지운 뒤에도 남는다, 남의 판은 비어 있다, 민감 항목을 `ALWAYS` 로 두면 거절, 받지 않는 collection 은 주입과 색인에 없고 본문은 `MEMORY_NOT_FOUND`, 민감 허용, 보관
- `backend/src/test/java/com/bifos/assistant/agent/AgentMemoryCollectionServiceTest.java`: 새 에이전트의 `core`, 커넥터 에이전트는 줄이 있어도 0, 없는 에이전트 0
- `backend/src/test/java/com/bifos/assistant/memory/MemoryCollectionServiceTest.java`
- `backend/src/test/java/com/bifos/assistant/context/ContextAssemblerTest.java`: 커넥터와 모르는 에이전트는 빈 문맥, 받지 않는 collection 은 제목도 없다
- `backend/src/test/java/com/bifos/assistant/mcp/McpMemoryToolTest.java`: 받지 않는 collection, 허용받지 않은 민감 항목, 남의 번호가 없는 번호와 같은 응답
- `test/e2e/scenarios/memory.ts`: 같은 사용자와 같은 에이전트가 받는 Memory 구역을 글자 그대로 견준다. 이 검사는 표를 넓히기 전의 코드에서도 통과해야 한다
- 실행에 에이전트가 없으면 Memory 를 읽지 못하므로, 실행 줄을 직접 만드는 기존 MCP 검사는 `McpCallSigner.running(executions, agents, ...)` 로 에이전트를 붙인다

## 검증

```bash
# cwd: backend/
./gradlew test
./gradlew checkstyleMain checkstyleTest spotlessCheck
```

```bash
# cwd: 저장소 root. Node 22.18 이상
node test/e2e/run.ts
scripts/check-public-safe.sh
```

- `./gradlew test` 가 `MemoryV2MigrationTest`, `MemoryServiceTest`, `AgentMemoryCollectionServiceTest`, `MemoryCollectionServiceTest`, `ContextAssemblerTest`, `McpMemoryToolTest`, `ArchitectureRulesTest` 를 포함해 모두 통과한다
- `node test/e2e/run.ts` 가 「모두 통과했다」 로 끝난다. Flyway 로 만든 스키마를 엔티티로 검증하므로 V47 과 엔티티가 어긋나면 기동에서 실패한다

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/resources/db/migration/V47__memory_v2.sql` | 신규 |
| `backend/src/main/java/com/bifos/assistant/memory/domain/type/MemoryEntryType.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/memory/domain/type/MemoryRetrieval.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/memory/domain/type/MemorySensitivity.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/memory/domain/type/MemoryChangeType.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/memory/domain/type/MemoryScope.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/memory/domain/type/MemoryStatus.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/memory/domain/MemoryScope.java` | 삭제 |
| `backend/src/main/java/com/bifos/assistant/memory/domain/MemoryStatus.java` | 삭제 |
| `backend/src/main/java/com/bifos/assistant/memory/domain/Memory.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/memory/domain/MemoryPlacement.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/memory/domain/MemoryRevision.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/memory/domain/MemoryRevisionId.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/memory/domain/MemoryCollection.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/memory/domain/MemoryCollectionId.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/memory/infra/MemoryRepository.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/memory/infra/MemoryRevisionRepository.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/memory/infra/MemoryCollectionRepository.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/memory/infra/MemoryQueries.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/memory/application/MemoryService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/memory/application/MemoryCollectionService.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/memory/application/model/MemoryAccess.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/memory/**/*.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/agent/domain/Agent.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/agent/domain/AgentCreated.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/agent/domain/AgentMemoryCollection.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/agent/domain/AgentMemoryCollectionId.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/agent/infra/AgentMemoryCollectionRepository.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/agent/application/AgentMemoryCollectionService.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/agent/application/AgentMemoryGrants.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/context/ContextAssembler.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/application/ChatService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/orchestration/application/AgentRunner.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/mcp/application/McpToolService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/shared/error/ErrorCode.java` | 수정 |
| `backend/config/archunit/store/*` | 수정 |
| `backend/config/checkstyle/baseline.xml` | 수정 |
| `backend/src/test/java/com/bifos/assistant/memory/MemoryV2MigrationTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/memory/MemoryCollectionServiceTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/agent/AgentMemoryCollectionServiceTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/**/*.java` | 수정 |
| `test/e2e/scenarios/memory.ts` | 수정 |
