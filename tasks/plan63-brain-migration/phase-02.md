# Phase 02. 묶음을 대조하고 들이는 API

**Execution profile**: deep

## 목표

주인이 올린 묶음을 저장 없이 대조하는 경로와, 같은 묶음의 `NEW` 항목만 저장하는 경로를 만든다.
같은 출처를 두 번 저장하지 않고, 이미 있는 이름과 부딪히는 항목은 저장하지 않는다. 신원 항목은 아직 거절한다.

**범위 외**: 화면(phase 03), e2e 와 문서(phase 04), 신원 항목 열기(phase 05).

## Blocked 조건

- `backend/src/main/java/com/bifos/assistant/memory/presentation/MemoryDocumentController.java` 가 없다 → `PHASE_BLOCKED: plan62 가 머지되지 않았다` 출력 후 종료

## 컨텍스트

- `backend/src/main/java/com/bifos/assistant/memory/domain/Memory.java`: private 생성자 `Memory(scope, ownerUserId, groupId, title, StoredContent body, MemoryPlacement placement, MemoryStatus status, Long proposedByExecutionId, Instant now)` 가 `entryType` 을 `MEMORY` 로 둔다. `sourceType`(32자), `sourceRef`(512자), `sourceDate`(`LocalDate`) 칸이 있고 지금 채우는 길이 없다. 문서를 만드는 `Memory.document(...)` 가 같은 생성자 뒤에 `entryType` 과 `documentKey` 를 덮어 적는다. 같은 방식을 따른다
- `backend/src/main/java/com/bifos/assistant/memory/application/MemoryService.java`: `collectionsFor(CurrentUser user)` 가 그 사용자의 그룹이 쓰는 `MemoryCollection` 목록을 낸다. 본문을 암호화하는 `stored(...)` 는 private 이다. 이 phase 는 `MemoryContentCipher` 를 직접 쓴다
- `backend/src/main/java/com/bifos/assistant/memory/application/MemoryContentCipher.java`: `enabled()`, `seal(String plain, String binding)`. 묶는 글은 `"USER:" + ownerUserId` 다(`Memory.contentBinding()`)
- `backend/src/main/java/com/bifos/assistant/memory/domain/MemoryPlacement.java`: `isCollectionKey(value)`, `allows(retrieval, sensitivity)`
- `backend/src/main/java/com/bifos/assistant/memory/infra/MemoryRepository.java`: `findByScopeAndOwnerUserIdAndCollectionAndDocumentKey(scope, ownerUserId, collection, documentKey)` 가 있다
- 유일 제약 `uk_memory_user_document`(`owner_user_id`, `collection`, `document_key`)가 V47 에 있다. 테스트 데이터베이스는 엔티티로 표를 만들어 이 제약과 이 phase 가 더하는 제약이 없다. 그래서 대조는 서비스가 조회로 하고, 제약은 두 요청이 겹친 경우의 마지막 막이다
- 컨트롤러 선례는 `memory/presentation/MemoryDocumentController.java`, 요청과 응답 record 는 `memory/presentation/MemoryDtos.java`, `application` 의 결과 타입은 `memory/application/model/` 에 타입 하나에 파일 하나다
- 마이그레이션 테스트의 선례는 `backend/src/test/java/com/bifos/assistant/memory/MemoryContentKeyMigrationTest.java` 다

**근거 문서**: `docs/adr/ADR-058-기존-개인-지식-저장소는-주인이-검토한-묶음을-화면에서-올려-들여온다.md` 의 「적용 범위」

## 의도 메모

- 미리보기와 저장이 같은 판정 함수를 쓴다. 미리보기가 `NEW` 라 한 항목이 저장에서 다르게 판정되는 것은 그 사이에 데이터가 바뀐 때뿐이다
- 저장은 묶음 전체가 한 트랜잭션이다. `NEW` 가 아닌 항목은 건너뛰고 실패로 치지 않는다. 그래서 같은 묶음을 다시 올려도 된다
- 항목 하나가 틀렸다고 요청을 400 으로 거절하지 않는다. 그 항목만 `REJECTED` 다. 요청 전체를 거절하는 것은 묶음의 모양이 틀린 때와 암호화 key 가 없는 때뿐이다
- 대조에 본문을 쓰지 않는다. 민감 본문은 암호문이라 견줄 수 없고 해시를 두면 지문이 남는다(ADR-058)
- `GROUP` 범위로 들이는 길을 만들지 않는다. 요청이 범위와 주인을 정하지 못한다

## 작업 항목

### 1. `backend/src/main/resources/db/migration/V<N>__memory_source_unique.sql`

`<N>` 은 `origin/main` 의 가장 큰 번호 다음이다. 파일 이름과 아래 테스트의 번호를 그 값으로 적는다.

```sql
-- 같은 주인의 같은 출처가 두 줄이 되지 않게 한다(ADR-058).
-- 출처가 없는 줄은 source_ref 가 NULL 이라 이 제약에 걸리지 않는다.
CREATE UNIQUE INDEX uk_memory_user_source ON memory (owner_user_id, source_type, source_ref);
```

### 2. `Memory.imported`

```java
public static Memory imported(
        Long ownerUserId, MemoryEntryType entryType, String documentKey, String title,
        StoredContent body, MemoryPlacement placement,
        String sourceType, String sourceRef, LocalDate sourceDate, Instant now)
```

`USER` 범위, `ACCEPTED`, `acceptedByUserId` 는 주인, 판은 1 이다. private 생성자 뒤에 `entryType`, `documentKey`, 출처 칸 셋을 적는다. Javadoc: 주인이 검토한 묶음에서 들인 줄이다(ADR-058).

### 3. `MemoryRepository`

```java
List<Memory> findByScopeAndOwnerUserIdAndSourceTypeAndSourceRefIn(
        MemoryScope scope, Long ownerUserId, String sourceType, Collection<String> sourceRefs);

boolean existsByScopeAndOwnerUserIdAndEntryTypeAndCollectionAndTitle(
        MemoryScope scope, Long ownerUserId, MemoryEntryType entryType, String collection, String title);
```

### 4. 타입 `memory/domain/type/` 와 `memory/application/model/`

- `memory/domain/type/MemoryImportStatus.java`: `NEW`, `DUPLICATE`, `CONFLICT`, `REJECTED`
- `memory/application/model/MemoryImportItem.java`: `record MemoryImportItem(String sourceRef, LocalDate sourceDate, String collection, String entryType, String documentKey, String title, String content, boolean sensitive, String retrieval)`. `entryType` 과 `retrieval` 은 글로 받는다. 틀린 값을 그 항목의 `REJECTED` 로 답하기 위해서다
- `memory/application/model/MemoryImportOutcome.java`: `record MemoryImportOutcome(int index, String sourceRef, MemoryImportStatus status, String reason, Long memoryId)`. `reason` 은 `NEW` 와 `DUPLICATE` 에서 null, `memoryId` 는 저장한 때만 있다

### 5. `memory/application/MemoryImportService.java`

`@Service @RequiredArgsConstructor @Slf4j`. `MemoryRepository`, `MemoryService`, `MemoryContentCipher`, `Clock` 을 받는다.

```java
public static final String SOURCE_TYPE = "brain";
public static final int MAX_ITEMS = 100;
public static final int MAX_CONTENT_CHARS = 12000;
```

| 메서드 | 동작 |
| --- | --- |
| `@Transactional List<MemoryImportOutcome> preview(CurrentUser user, List<MemoryImportItem> items)` | 항목마다 판정만 한다. 저장하지 않는다. `collectionsFor` 가 기본 목록을 저장할 수 있어 읽기 전용으로 두지 않는다 |
| `@Transactional List<MemoryImportOutcome> commit(CurrentUser user, List<MemoryImportItem> items)` | 같은 판정 뒤 `NEW` 인 항목만 `Memory.imported` 로 저장하고 `memoryId` 를 채운다. 끝에 `log.info("memory import userId={} new={} duplicate={} conflict={} rejected={}", ...)` |

두 메서드의 앞 검사. 어긋나면 요청 전체를 거절한다.

- `items` 가 비었거나 `MAX_ITEMS` 를 넘으면 `VALIDATION_FAILED`
- 판정 결과에 `NEW` 이고 `sensitive` 인 항목이 하나라도 있는데 `cipher.enabled()` 가 거짓이면 `MEMORY_ENCRYPTION_UNAVAILABLE`. 미리보기도 같다

항목 하나의 판정. 위에서부터 보고 먼저 걸린 것으로 답한다.

| 결과 | `reason` | 언제 |
| --- | --- | --- |
| `REJECTED` | `INVALID_FIELD` | `sourceRef` 가 비었거나 512자를 넘는다. `title` 이 비었거나 200자를 넘는다. `content` 가 비었다. `entryType` 이 `MEMORY`, `DOCUMENT`, `SOURCE` 가 아니다. `retrieval` 이 `ALWAYS`, `SEARCH`, `ARCHIVE` 가 아니다. `collection` 이 `MemoryPlacement.isCollectionKey` 를 지나지 못한다. `DOCUMENT` 인데 `documentKey` 가 `[a-z0-9][a-z0-9-]{0,127}` 이 아니다. `DOCUMENT` 가 아닌데 `documentKey` 가 있다 |
| `REJECTED` | `CONTENT_TOO_LONG` | 본문이 `MAX_CONTENT_CHARS` 를 넘는다 |
| `REJECTED` | `UNKNOWN_COLLECTION` | `collection` 이 `MemoryService.collectionsFor(user)` 의 key 에 없다 |
| `REJECTED` | `IDENTITY_HELD` | `collection` 이 `identity` 다. 상수 `IDENTITY_COLLECTION = "identity"` 로 둔다 |
| `REJECTED` | `RETRIEVAL_NOT_ALLOWED` | `DOCUMENT` 인데 `SEARCH` 가 아니다. `SOURCE` 인데 `ARCHIVE` 가 아니다. `MEMORY` 인데 `ARCHIVE` 다. 민감 항목인데 `ALWAYS` 다 |
| `REJECTED` | `DUPLICATE_IN_BUNDLE` | 같은 묶음의 앞 항목과 `sourceRef` 가 같다. 또는 앞의 `NEW` 항목과 `DOCUMENT` 의 `collection` 과 `documentKey` 가 같다 |
| `DUPLICATE` | null | 요청자가 주인인 `USER` 범위의 줄 가운데 `sourceType` 이 `brain` 이고 `sourceRef` 가 같은 것이 있다. 묶음의 `sourceRef` 를 모아 `findByScopeAndOwnerUserIdAndSourceTypeAndSourceRefIn` 한 번으로 읽는다 |
| `CONFLICT` | `DOCUMENT_KEY_TAKEN` | `DOCUMENT` 이고 `findByScopeAndOwnerUserIdAndCollectionAndDocumentKey` 가 줄을 낸다 |
| `CONFLICT` | `TITLE_TAKEN` | `MEMORY` 이고 `existsByScopeAndOwnerUserIdAndEntryTypeAndCollectionAndTitle` 이 참이다 |
| `NEW` | null | 위 어느 것도 아니다 |

저장할 때 본문은 `sensitive` 면 `cipher.seal(content, "USER:" + user.id())`, 아니면 `StoredContent.plain(content)` 다. 민감도는 `sensitive` 로 `SENSITIVE` 와 `NORMAL` 을 정한다.
`commit` 은 `memories.saveAllAndFlush(...)` 로 저장한다. `DataIntegrityViolationException` 이 나면 `new ApiException(ErrorCode.MEMORY_IMPORT_RETRY, "the bundle changed while importing")` 로 바꿔 던진다. 두 요청이 같은 묶음을 함께 올린 경우이고 트랜잭션이 통째로 되돌아간다.

`ErrorCode` 의 `MEMORY_ENCRYPTION_UNAVAILABLE` 아래에 `MEMORY_IMPORT_RETRY(HttpStatus.CONFLICT)` 를 더한다. Javadoc: 들이는 사이에 같은 출처나 같은 이름의 줄이 먼저 들어왔다. 다시 올리면 그 항목이 `DUPLICATE` 나 `CONFLICT` 로 나온다(ADR-058).

### 6. `MemoryDtos` 와 `memory/presentation/MemoryImportController.java`

```java
public record ImportItemBody(
        String sourceRef, LocalDate sourceDate, String collection, String entryType,
        String documentKey, String title, String content, boolean sensitive, String retrieval) {}

public record ImportRequest(
        @NotNull Integer schemaVersion,
        @NotEmpty @Size(max = 100) List<ImportItemBody> items) {}

public record ImportOutcomeView(int index, String status, String reason, Long memoryId) {}

public record ImportResponse(
        int newCount, int duplicateCount, int conflictCount, int rejectedCount,
        List<ImportOutcomeView> items) {}
```

`ImportItemBody` 의 칸에는 검증 주석을 달지 않는다. 틀린 칸은 그 항목의 `REJECTED` 로 답한다.
`ImportOutcomeView` 는 `sourceRef` 를 싣지 않는다. 화면은 `index` 로 자기가 올린 항목과 맞춘다.

| 경로 | 동작 |
| --- | --- |
| `POST /api/v1/memory-imports/preview` | `schemaVersion` 이 1 이 아니면 `VALIDATION_FAILED`. `preview` 의 결과를 `ImportResponse` 로 |
| `POST /api/v1/memory-imports` | 같은 검사 뒤 `commit` 의 결과를 `ImportResponse` 로 |

`CurrentUserProvider.require()` 의 사용자로 부른다. `requireAdmin()` 을 쓰지 않는다. 응답에 `Cache-Control: no-store` 를 붙인다.

### 7. 마이그레이션 테스트 `backend/src/test/java/com/bifos/assistant/memory/MemorySourceUniqueMigrationTest.java`

`MemoryContentKeyMigrationTest` 의 방식이다. 이 phase 의 마이그레이션 바로 앞 번호까지 올리고 출처가 없는 `memory` 두 줄을 같은 주인으로 넣은 뒤 이 마이그레이션을 올린다.

- 출처가 없는 두 줄이 그대로 있다. 마이그레이션이 실패하지 않는다
- 같은 주인, `brain`, 같은 `source_ref` 의 줄을 두 번 넣으면 둘째가 실패한다
- 주인이 다르면 같은 `source_ref` 를 넣을 수 있다

### 8. 이 phase 를 검증하는 `backend/src/test/java/com/bifos/assistant/memory/MemoryImportTest.java`

`@SpringBootTest @ActiveProfiles("test")`. `MemoryDocumentTest` 처럼 컨트롤러를 직접 만들고 `CurrentUserProvider` 를 mock 으로 둔다. 사용자는 dad(번호 1, 그룹 1, `ADMIN`)와 kid(번호 2, 그룹 1, `MEMBER`)다. `@BeforeEach` 에서 `MemoryRepository` 와 `MemoryRevisionRepository` 를 비운다. 출처는 `private/wiki/sample/note-a.md` 같은 지어낸 글을 쓴다.

| 입력 | 기대 |
| --- | --- |
| dad 가 `MEMORY`(`core`, `ALWAYS`), `DOCUMENT`(`career`, `SEARCH`, 민감, 본문 `평문-표식-7391`), `SOURCE`(`health`, `ARCHIVE`) 셋을 미리보기 | 셋 다 `NEW`. `SELECT COUNT(*) FROM memory` 가 0 이다 |
| 같은 묶음을 `commit` | `newCount` 가 3. 세 줄의 `scope` 가 `USER`, `owner_user_id` 가 1, `status` 가 `ACCEPTED`, `accepted_by_user_id` 가 1, `revision` 이 1, `source_type` 이 `brain`, `source_ref` 와 `source_date` 가 보낸 값이다. `entry_type` 과 `retrieval` 이 보낸 값이다. 민감 문서의 `content` 가 `v1.` 으로 시작하고 표식을 담지 않는다 |
| 같은 묶음을 한 번 더 `commit` | 셋 다 `DUPLICATE`. `memory` 의 줄 수가 3 그대로다 |
| kid 가 같은 묶음을 `commit` | 셋 다 `NEW`. 주인이 다르다 |
| dad 가 화면으로 만든 문서(`career`, `position-notes`)가 있는데 출처가 다른 같은 이름의 `DOCUMENT` 를 올린다 | `CONFLICT`, `DOCUMENT_KEY_TAKEN`. 기존 문서의 `content` 와 `revision` 이 그대로다 |
| dad 의 `core` 에 제목 `일하는 방식` 인 `MEMORY` 가 있는데 같은 제목의 `MEMORY` 를 올린다 | `CONFLICT`, `TITLE_TAKEN` |
| `collection` 이 `identity` 인 민감 문서 | `REJECTED`, `IDENTITY_HELD`. 저장되지 않는다 |
| `collection` 이 `no-such-area` | `REJECTED`, `UNKNOWN_COLLECTION` |
| 본문이 12,001자 | `REJECTED`, `CONTENT_TOO_LONG` |
| 민감 `MEMORY` 를 `ALWAYS` 로, `SOURCE` 를 `SEARCH` 로 | 둘 다 `REJECTED`, `RETRIEVAL_NOT_ALLOWED` |
| `entryType` 이 `NOTE`, `documentKey` 가 `Position_Notes` | 둘 다 `REJECTED`, `INVALID_FIELD` |
| 한 묶음에 같은 `sourceRef` 둘 | 첫째는 `NEW`, 둘째는 `REJECTED`, `DUPLICATE_IN_BUNDLE`. 한 줄만 저장된다 |
| `NEW` 하나와 `REJECTED` 하나가 섞인 묶음을 `commit` | `NEW` 만 저장되고 예외가 없다 |
| 항목 101개 | `VALIDATION_FAILED` |
| `schemaVersion` 이 2 | `VALIDATION_FAILED` |
| 들인 `SOURCE` 와 들인 문서 | `MemoryController.readable()` 의 목록에 문서가 없다. 들인 `MEMORY` 는 있다 |
| 들인 `ALWAYS` `MEMORY` | `ContextAssembler.assembleForOwner(dad)` 의 글이 그 본문을 담는다. 들인 `SOURCE` 의 본문과 제목은 담지 않는다 |
| `OutputCaptureExtension` 으로 잡은 `commit` 의 로그 | `평문-표식-7391` 과 `note-a` 를 담지 않는다 |

key 가 없을 때는 `backend/src/test/java/com/bifos/assistant/memory/MemoryImportEncryptionDisabledTest.java` 에 둔다. `@SpringBootTest(properties = {"assistant.memory.encryption.active-key-id=", "assistant.memory.encryption.keys="}) @ActiveProfiles("test")`.

| 입력 | 기대 |
| --- | --- |
| 일반 항목 하나와 민감 문서 하나를 `commit` | `MEMORY_ENCRYPTION_UNAVAILABLE`. `memory` 의 줄 수가 0 이다. 일반 항목도 저장되지 않는다 |
| 같은 묶음을 미리보기 | 같은 예외다 |
| 일반 항목만 `commit` | 저장된다 |

## 검증

```bash
# cwd: 저장소 root
(cd backend && ./gradlew test --tests '*MemoryImportTest' --tests '*MemoryImportEncryptionDisabledTest' --tests '*MemorySourceUniqueMigrationTest' --tests '*MemoryDocumentTest' --tests '*ArchitectureRulesTest')
(cd backend && ./gradlew test)
(cd backend && ./gradlew qualityCheck)
node test/e2e/run.ts
scripts/check-public-safe.sh
! git grep -n "import com.bifos.assistant.people\|import com.bifos.assistant.user" -- backend/src/main/java/com/bifos/assistant/memory
```

- 모두 종료 코드 0. 마지막 줄은 일치하는 줄이 없어야 한다
- `node test/e2e/run.ts` 는 `./gradlew test` 뒤에 돌린다. e2e 는 Flyway 를 지나므로 새 유일 제약이 기존 시나리오의 데이터에 걸리지 않는지 여기서 본다

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/resources/db/migration/V*__memory_source_unique.sql` | 신규 |
| `backend/src/main/java/com/bifos/assistant/shared/error/ErrorCode.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/memory/domain/Memory.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/memory/domain/type/MemoryImportStatus.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/memory/infra/MemoryRepository.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/memory/application/model/MemoryImportItem.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/memory/application/model/MemoryImportOutcome.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/memory/application/MemoryImportService.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/memory/presentation/MemoryDtos.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/memory/presentation/MemoryImportController.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/memory/MemorySourceUniqueMigrationTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/memory/MemoryImportTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/memory/MemoryImportEncryptionDisabledTest.java` | 신규 |
