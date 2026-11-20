# Phase 01. 요약 칸과 암호화

**Execution profile**: standard

## 목표

기억과 revision 에 요약 칸을 두고 제목·본문과 같은 주인 key 로 암호화해 저장하고 푼다. 뒤 phase 가 이 칸을 읽고 쓴다.

**범위 외**: 조립(phase 02), 도구 인자(phase 03), 이관(phase 04).

## 컨텍스트

**요구사항**: REQ1

**근거 문서**: `docs/features/memory/spec.md`, `docs/features/memory/design.md` 의 「스키마(PR1)」, 「글 타입」

- 암호화 틀은 `backend/src/main/java/com/bifos/assistant/memory/application/MemoryTexts.java` 다. AAD 는 `memory:<번호>:<칸>:<범위>:<주인>` 이다. 요약은 칸 이름 `summary` 를 쓴다
- 글 타입은 `memory/domain/MemoryText.java`, `StoredMemoryText.java`, `SealedMemoryText.java` 다
- 엔티티는 `memory/domain/entity/Memory.java`, `MemoryRevision.java`. revision 은 `MemoryRevisionWriter` 가 남긴다
- 기동 암호화 이관 `MemoryTextMigration` 의 `rewrite`, `verify`, `migrateRevision` 도 요약을 함께 다룬다
- 마이그레이션 경로는 예약한 `backend/src/main/resources/db/migration/V20261105074422__memory_summary.sql` 이다. 기준 브랜치를 합친 뒤에는 `orch queue reserve --repo <root> --module backend --name memory_summary` 를 다시 불러 `renewed` 면 이름을 바꾼다

## 의도 메모

- 요약 칸 `NULL` 은 「이관 전」 표지다. 이 phase 뒤로 기억을 새로 넣거나 고치는 경로는 요약이 없어도 빈 글을 암호화해 적는다
- 요약을 본문 안에 섞지 않는다. 검색과 `memory_read` 의 뜻이 바뀐다

## 작업 항목

### 1. 마이그레이션

`memory.summary TEXT NULL`, `memory_revision.summary TEXT NULL`, `memory_capture.replaced_memory_ids VARCHAR(512) NULL` 을 더한다. DDL 만 둔다. `backend/docs/data-schema.md` 의 `memory`, `memory_revision`, `memory_capture` 칸 설명을 고친다.

### 2. 글 타입과 `MemoryTexts`

`MemoryText(title, content, summary)`, `StoredMemoryText` 와 `SealedMemoryText` 에 `summary` 를 더한다. `seal(where, title, content, summary)` 가 셋을 암호화하고 `open` 은 저장된 요약이 `NULL` 이면 빈 글을 낸다. 셋 가운데 하나라도 풀지 못하면 지금처럼 빈 값이다. KEK 가 없는 설치는 평문이다.

### 3. 엔티티와 revision

- `Memory` 에 `storedSummary`(`summary` 칸), `summary()`, `hasSummary()`, `SUMMARY_MAX = 200` 을 더한다. 생성 팩터리(`accepted`, `proposed`, `remembered`, `document`, `proposedUser`)는 요약을 받거나 빈 글로 둔다
- 요약을 적는 메서드는 `USER` 범위의 `MEMORY` 가 아니면 빈 글 밖의 값을 `IllegalArgumentException` 으로 거절한다
- `revise(content, summary, sealed, retrieval, dedupKey, at)` 로 요약을 받는다. `seal`, `detachForSealing` 이 요약도 다룬다
- `MemoryRevision` 에 요약 칸을 더하고 `MemoryRevisionWriter.saveRevision`(다시 암호화하는 분기 포함)과 `revise` 가 요약을 옮긴다. `MemoryService.revise` 와 `update` 에 요약 인자를 더한다(`null` 은 지금 값 유지)
- `MemoryCapture` 에 `replacedMemoryIds`(JSON 배열 문자열, rule-15·rule-27 의 저장 타입 규칙을 본다)를 더한다
- `MemoryTextMigration` 의 다시 암호화와 해시 대조에 요약을 넣는다

### 4. 테스트

- `MemoryEncryptionTest` 에 더한다: 요약이 암호문으로 저장되고 풀린다, 요약 암호문을 본문 칸으로 옮기면 풀리지 않는다, KEK 가 없으면 평문이다
- `MemoryServiceTest` 에 더한다: 요약을 고치면 revision 에 이전 요약이 남는다, 그룹 기억과 문서에 빈 글 밖의 요약을 적으면 거절된다
- 평문 줄에 평문 요약이 있을 때 `MemoryTextMigration` 이 셋을 함께 옮기고 줄이 다시 풀리는 테스트

## 검증

```bash
cd backend && HEAVY_SLOTS=2 orch slot heavy -- ./gradlew test --tests '*MemoryEncryptionTest' --tests '*MemoryServiceTest' --tests '*MemoryTextMigration*' --tests '*MemorySupersede*'
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
| `backend/src/main/java/com/bifos/assistant/memory/domain/entity/MemoryCapture.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/memory/application/MemoryTexts.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/memory/application/MemoryRevisionWriter.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/memory/application/MemoryService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/memory/application/MemoryTextMigration.java` | 수정 |
| `backend/docs/data-schema.md` | 수정 |
| `backend/src/test/java/com/bifos/assistant/memory/MemoryEncryptionTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/memory/MemoryServiceTest.java` | 수정 |
