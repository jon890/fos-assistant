# Phase 01. memory_link 테이블과 링크 서비스

**Execution profile**: standard

## 목표

링크를 저장하고, 규칙을 지나는 링크만 만들고, 받아들이고 끊는 서비스를 만든다. 항목을 지우면 그 항목의 링크도 지운다.

**범위 외**: 링크를 권한 조건에 쓰는 것(phase 02), MCP 도구(phase 03), 화면(phase 04).

## 컨텍스트

**근거 문서**: `docs/adr/ADR-20261015-memory-link.md` 의 「결정」 과 「구현 순서」, `docs/features/memory.md` 의 「기억 사이의 연결」, `backend/docs/data-schema.md` 의 「memory_link」 와 「memory_capture」, 「지울 때」.

- 마이그레이션 규칙: `backend/docs/data-schema.md` 의 「마이그레이션 작성 규칙」 과 「테이블과 컬럼에 한국어 COMMENT를 단다」. `MysqlMigrationTest.requiresCommentsOnEveryTableAndColumn` 이 COMMENT 누락을 막는다. 본보기는 `V20261016094357__memory_embedding.sql`
- 엔티티는 `memory.domain.entity`, 저장 enum 은 `memory.domain.type` 에 둔다(`backend/AGENTS.md` 의 「memory의 영속 타입」). 기존 아홉 타입 목록에는 넣지 않는다
- 지울 때: `memory/application/MemoryRevisionWriter.beforeDelete` 가 벡터 줄과 캐시를 이미 지운다. 링크도 여기서 지운다. `MemoryService.java` 는 481줄이고 상한은 500줄이다
- 삭제 분류: `backend/src/test/resources/purge/tables.tsv` 와 `fixture.sql`

## 작업 항목

### 1. 마이그레이션 `V<UTC 작성 시각>__memory_link.sql`

- `memory_link`: `id BIGINT AUTO_INCREMENT PRIMARY KEY`, `from_memory_id BIGINT NOT NULL`, `to_memory_id BIGINT NOT NULL`, `type VARCHAR(20) NOT NULL`, `label VARCHAR(200) NULL`, `origin VARCHAR(20) NOT NULL`, `status VARCHAR(20) NOT NULL`, `score DOUBLE NULL`, `created_by_execution_id BIGINT NULL`, `created_at DATETIME(6) NOT NULL`, `updated_at DATETIME(6) NOT NULL`, `revoked_at DATETIME(6) NULL`. 유일 `(from_memory_id, to_memory_id, type)`, 색인 `(to_memory_id, type, status)` 와 `(from_memory_id, status)`. 테이블과 컬럼마다 한국어 COMMENT
- `memory_capture` 에 `external_read BOOLEAN NOT NULL DEFAULT FALSE COMMENT ...` 를 더한다. DDL 만 담는다

### 2. 엔티티와 저장소

- `memory/domain/type/MemoryLinkType`(`SUPERSEDES`, `RELATED`), `MemoryLinkOrigin`(`AUTO`, `AGENT`, `HUMAN`), `MemoryLinkStatus`(`PROPOSED`, `ACTIVE`, `REVOKED`)
- `memory/domain/entity/MemoryLink`: `@Enumerated(STRING)`, fluent getter. `static proposeSupersede(newId, oldId, origin, executionId, now)`, `static related(a, b, label, origin, score, executionId, now)`(번호가 작은 쪽을 `from`), `accept(now)`, `revoke(now)`, `reopen(...)`(끊은 줄을 다시 쓴다)
- `MemoryCapture` 에 `externalRead` 를 더하고 `of(...)` 가 받는다
- `memory/infra/MemoryLinkRepository`: `findByFromMemoryIdAndToMemoryIdAndType`, `existsByToMemoryIdAndTypeAndStatus`, `findByToMemoryIdInAndTypeAndStatus`, `findByStatusAndTypeAndToMemoryIdIn`, `countByCreatedByExecutionId`, `deleteByMemoryId(@Param id)`(`from` 이나 `to` 가 그 번호인 줄을 지우는 JPQL)

### 3. 링크 서비스 `memory/application/MemoryLinkService.java` 신규

- `proposeSupersede(CurrentUser, Long newId, Long oldId, MemoryLinkOrigin, Long executionId)`, `relate(CurrentUser, Long a, Long b, String label, MemoryLinkOrigin, Long executionId)`, `accept(CurrentUser, Long linkId)`, `reject(CurrentUser, Long linkId)`, `revoke(CurrentUser, Long linkId)`, `supersededBy(Collection<Long> memoryIds)`(옛 번호 → 그 항목을 대신한 `ACTIVE` 줄의 새 번호), `pendingSupersedesFor(CurrentUser)`
- 규칙(어기면 `ApiException(MEMORY_NOT_FOUND)` 나 `VALIDATION_FAILED`): 양 끝이 요청자가 볼 수 있는 `ACCEPTED` 항목, `SOURCE` 가 아님, 같은 범위와 주인(`USER` 끼리는 같은 `ownerUserId`, `GROUP` 끼리는 같은 `groupId`), 자기 자신이 아님, `label` 은 200자 이하이고 `RELATED` 에만. 같은 줄이 `ACTIVE` 나 `PROPOSED` 면 그 줄을 그대로 돌려주고, `REVOKED` 면 다시 연다
- 받아들이기와 거절, 되돌리기는 옛 항목(`to`)을 고칠 수 있는 사람만 한다. 개인 기억은 주인, 그룹 기억은 `ADMIN`. 남의 줄과 없는 줄은 같은 404 다
- `SUPERSEDES` 를 받아들일 때 옛 항목이 이미 다른 `ACTIVE` `SUPERSEDES` 로 대체됐으면 두 줄을 함께 둔다(대체됨 판정은 「하나라도 있으면」 이다)
- 예외 메시지와 로그에 제목과 본문을 넣지 않는다

### 4. 지울 때와 삭제 분류

- `MemoryRevisionWriter.beforeDelete` 가 `MemoryLinkRepository.deleteByMemoryId` 를 부른다
- `tables.tsv` 에 `memory_link|USER|id|GAP|KEEP|KEEP|양 끝 기억 항목에 속한다. 항목을 지우면 같은 트랜잭션에서 지운다.` 를 이름 순 자리에, `fixture.sql` 에 fixture 항목을 가리키는 줄 하나를 넣는다

### 5. 이 phase 를 검증하는 테스트

- `backend/src/test/java/com/bifos/assistant/memory/MemoryLinkServiceTest.java` 신규(`@BackendIntegrationTest`): 대체 제안과 받아들이기, 거절, 되돌리기, 같은 쌍을 다시 맺으면 끊은 줄을 다시 연다. 거절: `USER` 와 `GROUP` 사이, 주인이 다른 `USER` 사이, `PROPOSED` 항목, `SOURCE`, 자기 자신, 201자 `label`, `SUPERSEDES` 의 `label`. 남의 줄의 받아들이기는 404. 그룹 항목의 대체는 `MEMBER` 가 받아들이지 못한다
- `MemoryServiceTest` 에 항목을 지우면 그 항목의 링크가 양쪽 모두 지워지는 경우
- `PurgeClassificationTest` 는 새 줄로 통과한다

## 검증

```bash
cd backend && ./gradlew test --tests '*MemoryLinkServiceTest' --tests '*MemoryServiceTest' --tests '*PurgeClassificationTest'
cd backend && ./gradlew archTest checkstyleMain checkstyleTest
scripts/checks/check-mysql-migration.sh --tests '*MysqlMigrationTest' --tests '*PurgeCoverageMysqlTest' --tests '*RepositoryQueryMysqlTest'
node scripts/checks/check-migration-versions.mjs origin/main
node scripts/checks/check-file-length.mjs
```

기대: 모두 통과.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/resources/db/migration/V*__memory_link.sql` | 신규 |
| `backend/src/main/java/com/bifos/assistant/memory/domain/type/MemoryLinkType.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/memory/domain/type/MemoryLinkOrigin.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/memory/domain/type/MemoryLinkStatus.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/memory/domain/entity/MemoryLink.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/memory/domain/entity/MemoryCapture.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/memory/infra/MemoryLinkRepository.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/memory/application/MemoryLinkService.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/memory/application/MemoryRevisionWriter.java` | 수정 |
| `backend/src/test/resources/purge/tables.tsv` | 수정 |
| `backend/src/test/resources/purge/fixture.sql` | 수정 |
| `backend/src/test/java/com/bifos/assistant/memory/MemoryLinkServiceTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/memory/MemoryServiceTest.java` | 수정 |
