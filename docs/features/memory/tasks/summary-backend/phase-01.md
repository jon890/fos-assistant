# Phase 01. 요약 칸과 암호화

**Execution profile**: deep

## 목표

기억과 revision 에 요약 칸을 두고 제목·본문과 같은 주인 key 로 암호화해 저장하고 푼다. 기존 동작은 바뀌지 않고, 이 커밋만으로 전체 테스트가 통과한다.

**범위 외**: 조립(phase 02), 도구 인자(phase 03), 요약 이관(phase 04), 합치기 칸(PR1b).

## 컨텍스트

**요구사항**: REQ1

**근거 문서**: `docs/features/memory/spec.md`, `docs/features/memory/design.md` 의 「스키마(PR1)」, 「글 타입」

- 암호화 틀은 `backend/src/main/java/com/bifos/assistant/memory/application/MemoryTexts.java` 다. AAD 는 `memory:<번호>:<칸>:<범위>:<주인>` 이고 요약은 칸 이름 `summary` 다
- 글 타입은 `memory/domain/MemoryText.java`, `StoredMemoryText.java`, `SealedMemoryText.java` 다
- 엔티티는 `memory/domain/entity/Memory.java`, `MemoryRevision.java`. revision 은 `MemoryRevisionWriter` 가 남긴다
- 마이그레이션 경로는 예약한 `backend/src/main/resources/db/migration/V20261105074422__memory_summary.sql` 이다. 기준 브랜치를 합친 뒤 `orch queue reserve --repo <root> --module backend --name memory_summary` 를 다시 불러 `renewed` 면 이름을 바꾼다. 새 칸에는 한국어 `COMMENT` 를 단다(`backend/docs/data-schema.md` 「마이그레이션 작성 규칙」, `MysqlMigrationTest`)

## 의도 메모

- 요약 칸 `NULL` 은 「이관 전」 표지다. 이 phase 뒤로 기억을 새로 넣거나 고치는 경로는 요약이 없어도 빈 글을 암호화해 적는다
- 파급을 줄이려고 공개 시그니처는 그대로 두고 요약을 받는 오버로드를 더한다. 바꾸는 시그니처는 `MemoryTexts.seal` 하나다
- 요약을 본문 안에 섞지 않는다. 검색과 `memory_read` 의 뜻이 바뀐다

## 작업 항목

### 1. 마이그레이션과 스키마 문서

`memory.summary TEXT NULL`, `memory_revision.summary TEXT NULL` 을 COMMENT 와 함께 더한다. `backend/docs/data-schema.md` 의 `memory`, `memory_revision` 칸 설명에 요약 칸과 `NULL` 의 뜻을 더한다.

### 2. 글 타입과 `MemoryTexts`

- `MemoryText(title, content, summary)`. `toString` 은 지금처럼 글을 내지 않는다
- `StoredMemoryText` 와 `SealedMemoryText` 에 `summary`(저장된 값, `NULL` 가능)를 더한다
- `seal(where, title, content, summary)` 가 셋을 암호화한다. `open` 은 저장된 요약이 `NULL` 이면 빈 글을 내고, 셋 가운데 하나라도 풀지 못하면 빈 값이다. KEK 가 없는 설치는 평문이다
- 호출부 `MemoryTexts.insert`, `MemoryRevisionWriter`(41, 70행), `MemoryTextMigration.rewrite` 를 고친다

### 3. 엔티티와 revision

- `Memory`: `storedSummary`(`summary` 칸), `summary()`(없으면 빈 글), `hasSummary()`, `SUMMARY_MAX = 200`. 기존 팩터리는 요약을 빈 글로 둔다. `remembered` 와 `proposed` 에 요약을 받는 오버로드를 더한다
- 요약을 바꾸는 `reviseSummary` 와 `revise` 의 요약 오버로드는 `USER` 범위 `MEMORY` 가 아니면 빈 글 밖의 값을 `IllegalArgumentException` 으로 거절한다
- `seal`, `detachForSealing`, `stored()` 가 요약을 다룬다
- `MemoryRevision`: 요약 칸과 `summary()`(`NULL` 이면 빈 글)
- `MemoryRevisionWriter.revise(user, memory, content, summary, retrieval)` 오버로드를 더하고, 기존 4인자는 지금 요약을 넘긴다. `saveRevision` 과 다시 암호화하는 분기가 요약을 옮긴다
- `MemoryService.revise` 에 같은 오버로드를 더한다
- `MemoryTextMigration` 의 `rewrite`, `verify`, `migrateRevision` 이 요약도 함께 옮기고 대조한다. 요약이 `NULL` 이면 `NULL` 로 둔다

### 4. 테스트

- `MemoryTextsTest`: 4인자 `seal` 로 고치고, 요약이 암호문으로 저장되고 풀린다, 요약 암호문을 본문 칸 AAD 로는 못 푼다, KEK 가 없으면 평문이다
- `MemoryServiceTest`: 요약 오버로드로 고치면 revision 에 이전 요약이 남는다, 그룹 기억에 빈 글 밖의 요약을 적으면 거절된다
- `MemoryEncryptionTest`: 평문 줄에 평문 요약이 있으면 `MemoryTextMigration` 이 셋을 함께 옮기고 다시 풀린다, 요약이 `NULL` 인 평문 줄은 옮긴 뒤에도 `NULL` 이다

## 검증

```bash
cd backend && HEAVY_SLOTS=2 orch slot heavy -- ./gradlew test --tests '*MemoryTextsTest' --tests '*MemoryServiceTest' --tests '*MemoryEncryptionTest' --tests '*MemoryCapture*' --tests '*MemoryDocumentTest'
cd backend && ./gradlew checkstyleMain checkstyleTest spotlessCheck
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/resources/db/migration/V20261105074422__memory_summary.sql` | 신규 |
| `backend/src/main/java/com/bifos/assistant/memory/domain/MemoryText.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/memory/domain/StoredMemoryText.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/memory/domain/SealedMemoryText.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/memory/domain/entity/Memory.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/memory/domain/entity/MemoryRevision.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/memory/application/MemoryTexts.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/memory/application/MemoryRevisionWriter.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/memory/application/MemoryService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/memory/application/MemoryTextMigration.java` | 수정 |
| `backend/docs/data-schema.md` | 수정 |
| `backend/src/test/java/com/bifos/assistant/memory/application/MemoryTextsTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/memory/MemoryEncryptionTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/memory/MemoryServiceTest.java` | 수정 |
