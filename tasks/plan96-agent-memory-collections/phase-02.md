# Phase 02. 관리자 API 와 빠진 항목 수

**Execution profile**: standard

## 목표

`GET`, `PUT /api/v1/admin/agents/{code}/memory-collections` 를 만든다. 응답은 그룹의 collection 목록, 받는지와 민감 허용, 셈 대상 사용자의 실릴 수 있는 항목 수, 최근 변경 10줄이다.

**범위 외**: 화면과 웹 서버 라우트는 Phase 03. 판정 규칙(`MemoryQueries.injectable`, `MemoryService.accessOf`)은 바꾸지 않는다.

## 컨텍스트

- Phase 01 이 `AgentMemoryCollectionService` 에 `requireEditableAgent(String code)`, `rowsOf(Long)`, `recentChangesOf(Long)`, `replace(Long agentId, Map<String, Boolean> next, Set<String> listedCollections, Long changedByUserId)` 를 만들었다. `replace` 는 잠금 뒤에 목록에 없는 key 를 거절한다.
- 새 코드는 `memory` 패키지에 둔다. `memory` 는 `agent` 와 `user` 를 써도 되고 `agent` 는 `memory` 를 쓰지 못한다(`TopLevelPackageOrder.ORDER`).
- 그룹의 collection 목록은 `memory.application.MemoryCollectionService.collectionsOf(Long groupId)` 가 낸다. `MemoryCollection` 의 접근자는 `backend/src/main/java/com/bifos/assistant/memory/domain/MemoryCollection.java` 에서 읽어 쓴다.
- 요청자는 `CurrentUserProvider.requireAdmin()` 으로 받는다(`ADMIN` 이 아니면 `FORBIDDEN`). `CurrentUser` 는 `id`, `email`, `displayName`, `groupId`, `role` 이다.
- 사용자는 `user.infra.AppUserRepository` 로 읽는다. `AppUser` 는 `id()`, `displayName()`, `groupId()` 를 갖는다.
- 요청과 응답 모양은 `memory.presentation.MemoryDtos` 하나에 둔다(`backend/AGENTS.md` 「데이터 클래스는 컨트롤러 안에 두지 않는다」). 서비스 결과 모양은 `memory.application.model` 에 타입 하나에 파일 하나로 둔다.
- 집계 쿼리의 선례는 `backend/src/main/java/com/bifos/assistant/skill/infra/ExecutionSkillUseRepository.java` 의 `countByAgent`(JPQL `select new ...`)다. enum 값은 `backend/src/main/java/com/bifos/assistant/chat/infra/ResultDeliveryRepository.java` 처럼 JPQL 에 정규화된 이름으로 쓴다. `RepositoryQueryMysqlTest` 가 저장소 메서드를 스스로 찾아 실제 MySQL 에서 실행한다.

**근거 문서**: `docs/backend/memory.md` 의 「관리자가 에이전트의 collection 을 바꿀 때」(경로, 응답 JSON, 칸의 뜻, 갈리는 지점), `docs/adr/ADR-20261008-agent-memory-grants-admin.md`

## 의도 메모

- 셈 대상: 주인이 있으면 주인 id 와 주인의 `groupId`, `countedFor = OWNER`. 주인이 없으면 사용자 id 없이 관리자의 `groupId`, `countedFor = GROUP`. 그룹의 collection 목록도 이 그룹으로 읽는다.
- 「실릴 수 있는 항목」 은 `status = ACCEPTED`, `entryType <> SOURCE`, `retrieval <> ARCHIVE` 다. collection 과 민감도는 거르지 않는다.
- 항목의 제목과 번호는 응답에 싣지 않는다. 수만 싣는다.
- 바꿀 수 있는 collection 은 그룹 목록의 key 와 지금 받는 줄의 key 다. 그 밖은 `VALIDATION_FAILED`.

## 작업 항목

### 1. `memory.domain.MemoryCollectionCount` record 와 `MemoryRepository` 메서드

`record MemoryCollectionCount(String collection, MemorySensitivity sensitivity, long entries)`.

`MemoryRepository` 에:

```java
@Query("""
        select new com.bifos.assistant.memory.domain.MemoryCollectionCount(m.collection, m.sensitivity, count(m))
        from Memory m
        where m.status = com.bifos.assistant.memory.domain.type.MemoryStatus.ACCEPTED
            and m.entryType <> com.bifos.assistant.memory.domain.type.MemoryEntryType.SOURCE
            and m.retrieval <> com.bifos.assistant.memory.domain.type.MemoryRetrieval.ARCHIVE
            and ((m.scope = com.bifos.assistant.memory.domain.type.MemoryScope.USER and m.ownerUserId = :userId)
                or (m.scope = com.bifos.assistant.memory.domain.type.MemoryScope.GROUP and m.groupId = :groupId))
        group by m.collection, m.sensitivity
        """)
List<MemoryCollectionCount> countLoadableByCollection(@Param("userId") Long userId, @Param("groupId") Long groupId);
```

`userId` 가 null 이면 `USER` 조건은 참이 되지 않는다. Javadoc 에 그 뜻을 적는다. 엔티티의 필드 이름(`status`, `entryType`, `retrieval`, `scope`, `ownerUserId`, `groupId`, `collection`, `sensitivity`)은 `memory/domain/Memory.java` 에서 확인하고 다르면 그 이름을 쓴다.

### 2. `memory.application.model` 의 결과 모양

- `AgentMemoryCountScope` enum: `OWNER`, `GROUP`.
- `AgentMemorySettingCollection` record: `String key, String displayName, boolean listed, boolean granted, boolean allowSensitive, long entryCount, long sensitiveEntryCount`.
- `AgentMemorySettingChange` record: `String collection, String changeType, boolean allowSensitive, String changedByName, Instant changedAt`. 바꾼 사용자를 찾지 못하면 `changedByName` 은 null.
- `AgentMemorySetting` record: `AgentMemoryCountScope countedFor, String ownerName, List<AgentMemorySettingCollection> collections, List<AgentMemorySettingChange> changes`.
- `AgentMemoryGrantInput` record: `String collection, boolean allowSensitive`.

### 3. `memory.application.AgentMemorySettingService`

- `AgentMemorySetting settingOf(CurrentUser admin, String code)`: `requireEditableAgent(code)` → 셈 대상 정하기 → `collectionsOf(groupId)` → `rowsOf(agentId)` → `countLoadableByCollection` → `recentChangesOf(agentId)` 와 바꾼 사용자 이름(`AppUserRepository.findAllById`). 목록 순서는 그룹 목록 순서, 목록에 없는 받는 key 는 key 순서로 뒤에(`listed=false`, `displayName=key`). `@Transactional` (collectionsOf 가 쓰기를 할 수 있다).
- `AgentMemorySetting replace(CurrentUser admin, String code, List<AgentMemoryGrantInput> next)`: **`@Transactional` 을 달지 않는다.** 겹치는 collection 이나 64개 초과면 `ApiException(VALIDATION_FAILED, ...)`. `requireEditableAgent(code)` 와 셈 대상 그룹의 `collectionsOf(groupId)` 를 읽고, `Map` 과 그룹 목록 key 집합으로 `AgentMemoryCollectionService.replace(agentId, map, listedKeys, admin.id())` 를 부른 뒤 `settingOf` 를 돌려준다. 목록에 없는 key 의 판정은 Phase 01 의 `replace` 가 잠금 뒤에 한다. 이 메서드에 트랜잭션을 걸면 잠금 전의 읽기가 같은 트랜잭션에 들어가 잠금을 기다린 뒤에도 옛 줄을 본다(`AgentRepository.findIdByCode` 의 Javadoc 과 Phase 01 의 `replace` Javadoc). 메서드 Javadoc 에 이 까닭을 적는다.

### 4. `memory.presentation.MemoryDtos` 에 모양 더하기

- `AgentMemoryGrantBody(@NotBlank @Size(max = 64) String collection, boolean allowSensitive)` 와 `toInput()`.
- `ReplaceAgentMemoryRequest(@NotNull @Size(max = 64) List<@Valid @NotNull AgentMemoryGrantBody> collections)`.
- `AgentMemorySettingView`, `AgentMemoryCollectionView`, `AgentMemoryChangeView` 와 `from(...)`. JSON 칸 이름은 `docs/backend/memory.md` 의 응답 예와 같다(`countedFor`, `ownerName`, `collections[].key/displayName/listed/granted/allowSensitive/entryCount/sensitiveEntryCount`, `changes[].collection/changeType/allowSensitive/changedByName/changedAt`).

### 5. `memory.presentation.AgentMemorySettingController`

`@RequestMapping("/api/v1/admin/agents/{code}/memory-collections")`. `@GetMapping` 과 `@PutMapping` 둘. 각각 `currentUser.requireAdmin()` 으로 요청자를 받아 서비스에 넘긴다. `@PathVariable String code`, PUT 은 `@Valid @RequestBody ReplaceAgentMemoryRequest`.

### 6. 이 phase 를 검증하는 `backend/src/test/java/com/bifos/assistant/memory/AgentMemorySettingTest.java`

`@BackendIntegrationTest`. 통합 시험은 H2 하나를 함께 쓰므로 시험마다 고유한 그룹 id(예: 시험마다 다른 큰 수)와 고유한 메일(`<uuid>@example.com`)로 사용자를 만들고, 넣은 memory 줄은 끝에 지운다. 다른 시험이 그룹 `1` 에 남긴 항목이 수를 흔들지 않게 한다. 컨트롤러를 `new AgentMemorySettingController(service, currentUser)` 로 만들고 `CurrentUserProvider` 는 대역이되 `requireAdmin()` 이 `MEMBER` 에게 `ApiException(FORBIDDEN)` 을 던지게 한다(`backend/src/test/java/com/bifos/assistant/memory/MemoryDocumentTest.java` 의 `as(...)` 모양). 이름과 메일은 합성 값(`admin@example.com`, 「관리자A」).

- 「주인의 항목과 그룹 항목을 collection 마다 세고 받지 않는 collection 도 낸다」: 주인 사용자 A 의 비공개 에이전트, A 의 `career` `DOCUMENT` 둘(하나 `SENSITIVE`), `ARCHIVE` 하나, `PROPOSED` 하나, 다른 사용자의 `career` 하나, 그룹 `core` 하나. 응답 `countedFor=OWNER`, `career.entryCount=2`, `career.sensitiveEntryCount=1`, `career.granted=false`, `core.granted=true`, 다른 사용자의 것은 세지 않는다. 항목은 저장소나 `JdbcTemplate` 으로 넣는다.
- 「바꾸면 응답이 바뀐 값과 기록을 낸다」: PUT `[career true]` → `career.granted && allowSensitive`, `core.granted=false`, `changes[0]` 의 `changedByName` 이 관리자 이름.
- 「그룹 목록에 없는 collection 과 겹치는 collection 은 거절한다」: `VALIDATION_FAILED`.
- 「ADMIN 이 아니면 읽지도 바꾸지도 못한다」: `FORBIDDEN`.
- 「주인이 없는 그룹 에이전트는 그룹 항목만 센다」: `countedFor=GROUP`, `ownerName=null`.

## 검증

```bash
cd backend && ./gradlew test --tests 'com.bifos.assistant.memory.AgentMemorySettingTest' --tests 'com.bifos.assistant.agent.AgentMemoryCollectionServiceTest' --tests 'com.bifos.assistant.architecture.*'
cd backend && ./gradlew checkstyleMain checkstyleTest spotlessCheck
```

모두 종료 코드 0. `spotlessCheck` 가 처음 고친 파일의 포맷 차이를 내면 `./gradlew spotlessApply` 결과를 기능 변경과 다른 커밋으로 나눈다고 team-lead 에게 보고한다. Docker 가 있으면 `scripts/check-mysql-migration.sh` 로 `RepositoryQueryMysqlTest` 를 돌려 새 집계 쿼리가 MySQL 에서 실행되는지 본다(없으면 CI 의 backend job 이 본다).

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/memory/domain/MemoryCollectionCount.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/memory/infra/MemoryRepository.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/memory/application/model/AgentMemoryCountScope.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/memory/application/model/AgentMemorySettingCollection.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/memory/application/model/AgentMemorySettingChange.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/memory/application/model/AgentMemorySetting.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/memory/application/model/AgentMemoryGrantInput.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/memory/application/AgentMemorySettingService.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/memory/presentation/MemoryDtos.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/memory/presentation/AgentMemorySettingController.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/memory/AgentMemorySettingTest.java` | 신규 |
