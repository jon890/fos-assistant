# Phase 01. 사용자가 문서를 쓰고 고치는 API

**Execution profile**: standard

## 목표

사용자가 자기 `USER` 범위의 `DOCUMENT` 를 만들고, 목록과 본문을 읽고, 판 번호를 견줘 고친다.
직접 쓴 문서는 곧 `ACCEPTED` 다. 문서는 기존 Memory 목록과 수정 경로에서 빠진다.

**범위 외**: 서비스 토큰(phase 02), 다른 서비스의 읽기(phase 03), 화면(plan62).

## Blocked 조건

- `backend/src/main/java/com/bifos/assistant/memory/application/MemoryContentCipher.java` 가 없다 → `PHASE_BLOCKED: 민감 본문 암호화가 없다` 출력 후 종료

## 컨텍스트

- `backend/src/main/java/com/bifos/assistant/memory/domain/Memory.java`: 지금 만드는 길은 `accepted(scope, ownerUserId, groupId, title, StoredContent body, placement, acceptedByUserId, now)` 와 `proposedUser(...)` 다. private 생성자가 `entryType` 을 늘 `MemoryEntryType.MEMORY` 로 두고 `documentKey` 는 비워 둔다. 문서를 만드는 길이 없다
- `MemoryPlacement(collection, retrieval, sensitivity)` 가 collection key 의 모양(`[a-z][a-z0-9-]{0,63}`)과 「민감 항목은 `ALWAYS` 불가」 를 검사한다
- `backend/src/main/java/com/bifos/assistant/memory/application/MemoryService.java`
  - `readableBy(user)` 가 Memory 목록 API 의 원천이다. 종류를 거르지 않는다
  - `update(user, id, content, boolean alwaysInject)` 가 화면의 수정 경로다(`PATCH /api/v1/memories/{id}`)
  - private `revise(user, memory, content, retrieval, sensitivity)` 가 판을 남기고 본문을 암호화해 고친다
  - private `requireWritableForUpdate(user, id)` 가 `MemoryRepository.findByIdForUpdate` 로 쓰기 잠금을 잡는다
  - `contentOf(memory)` 가 평문을 낸다. 민감 줄이면 푼다
  - 없는 항목과 볼 수 없는 항목은 private `notFound()` 의 `MEMORY_NOT_FOUND` 로 같게 답한다
- `backend/src/main/java/com/bifos/assistant/memory/application/MemoryCollectionService.java`: `collectionsOf(Long groupId)` 가 그 그룹의 `MemoryCollection` 목록을 낸다. key 는 `MemoryCollection.key()`, 이름은 `displayName()` 이다. 부르는 API 가 아직 없다
- 유일 제약 `uk_memory_user_document`(`owner_user_id`, `collection`, `document_key`)가 V47 에 있다. 테스트 데이터베이스는 엔티티로 표를 만들므로 이 제약이 없다. 그래서 겹침은 서비스가 먼저 조회로 막는다
- 컨트롤러 선례는 `memory/presentation/MemoryController.java`, 요청과 응답 record 는 `memory/presentation/MemoryDtos.java` 하나에 모은다. 사용자는 `CurrentUserProvider.require()` 로 꺼낸다
- `ErrorCode` 는 `backend/src/main/java/com/bifos/assistant/shared/error/ErrorCode.java` 의 enum 이다

**근거 문서**: `docs/adr/ADR-057-문서는-사람이-화면에서-직접-쓰고-고친다.md` 의 「적용 범위」

## 의도 메모

- 문서의 꺼내는 방식은 `SEARCH` 로 고정한다. 요청으로 받지 않는다. 민감 문서가 `ALWAYS` 가 될 길을 만들지 않는다
- 본문 상한 12,000자는 `content` 칸이 `TEXT` 이기 때문이다. 한글은 한 글자가 3바이트이고 암호문은 base64 로 3분의 4 가 된다
- 문서를 기존 수정 경로(`PATCH /api/v1/memories/{id}`)에서 막는다. 그 경로는 「항상 싣기」 를 켤 수 있다
- 삭제는 기존 `DELETE /api/v1/memories/{id}` 를 그대로 쓴다. 새 경로를 만들지 않는다

## 작업 항목

### 1. `ErrorCode`

`MEMORY_ENCRYPTION_UNAVAILABLE` 아래에 둘을 더한다. 둘 다 `HttpStatus.CONFLICT` 다.

- `MEMORY_DOCUMENT_EXISTS`: 같은 주인과 collection 에 같은 이름의 문서가 이미 있다
- `MEMORY_REVISION_CONFLICT`: 화면이 읽은 판이 지금 판이 아니다. 그 사이에 다른 수정이 있었다

### 2. `Memory.document`

```java
public static Memory document(
        Long ownerUserId, String collection, String documentKey, String title,
        StoredContent body, MemorySensitivity sensitivity, Instant now)
```

`USER` 범위, `ACCEPTED`, `acceptedByUserId` 는 주인, `entryType` 은 `DOCUMENT`, `documentKey` 를 적고, 꺼내는 방식은 `SEARCH`, 판은 1 이다. private 생성자를 그대로 쓰고 그 뒤에 `entryType` 과 `documentKey` 를 덮어 적는다.

### 3. `MemoryRepository`

```java
List<Memory> findByScopeAndOwnerUserIdAndEntryTypeOrderByCollectionAscDocumentKeyAsc(
        MemoryScope scope, Long ownerUserId, MemoryEntryType entryType);

Optional<Memory> findByScopeAndOwnerUserIdAndCollectionAndDocumentKey(
        MemoryScope scope, Long ownerUserId, String collection, String documentKey);
```

### 4. `MemoryService`

`MemoryCollectionService collections` 를 주입받는다.

| 메서드 | 동작 |
| --- | --- |
| `List<Memory> documentsOf(CurrentUser user)` | 요청자가 주인인 `USER` 범위의 `DOCUMENT` 를 collection 과 이름 순으로 낸다 |
| `Memory documentFor(CurrentUser user, Long id)` | 번호로 읽는다. `USER` 범위가 아니거나, 주인이 요청자가 아니거나, 종류가 `DOCUMENT` 가 아니면 `notFound()` |
| `@Transactional Memory createDocument(CurrentUser user, String collection, String documentKey, String title, String content, MemorySensitivity sensitivity)` | 아래 순서로 검사하고 만든다 |
| `@Transactional Memory reviseDocument(CurrentUser user, Long id, String content, MemorySensitivity sensitivity, int expectedRevision)` | `findByIdForUpdate` 로 잠그고 `documentFor` 와 같은 조건을 본다. `memory.revision() != expectedRevision` 이면 `MEMORY_REVISION_CONFLICT`. 통과하면 private `revise(user, memory, content, memory.retrieval(), sensitivity)` 를 부른다 |
| `@Transactional List<MemoryCollection> collectionsFor(CurrentUser user)` | `user.groupId()` 가 null 이면 빈 목록, 아니면 `collections.collectionsOf(user.groupId())`. 줄이 없는 그룹이면 그 메서드가 기본 목록을 저장하므로 읽기 전용 트랜잭션으로 두지 않는다 |

`createDocument` 의 검사 순서:

1. `documentKey` 가 `[a-z0-9][a-z0-9-]{0,127}` 이 아니면 `VALIDATION_FAILED`
2. `collection` 이 `collectionsFor(user)` 의 key 에 없으면 `VALIDATION_FAILED`
3. `findByScopeAndOwnerUserIdAndCollectionAndDocumentKey(USER, user.id(), collection, documentKey)` 가 있으면 `MEMORY_DOCUMENT_EXISTS`
4. 본문을 private `stored(content, sensitivity, "USER:" + user.id())` 로 바꾼다. key 가 없고 민감 문서면 여기서 `MEMORY_ENCRYPTION_UNAVAILABLE` 이 난다
5. `memories.saveAndFlush(Memory.document(...))`. `DataIntegrityViolationException` 이 나면 `MEMORY_DOCUMENT_EXISTS` 로 바꿔 던진다. 두 요청이 3번을 함께 지난 경우다

기존 메서드를 고친다.

- `readableBy(user)`: 종류가 `MEMORY` 인 줄만 낸다. `MemoryQueries` 에 `listedFor(Long userId, Long groupId)` 를 더해 `readable(...)` 조건에 `entryType == MemoryEntryType.MEMORY` 를 `and` 로 묶고, `readableBy` 가 그것을 쓴다. Javadoc 을 「문서는 문서 API 가 따로 낸다(ADR-057)」 로 고친다
- `update(user, id, content, boolean alwaysInject)` 는 민감 항목을 이미 `MEMORY_SENSITIVE_NOT_EDITABLE` 로 거절한다(ADR-055). 문서 검사를 그 앞에 둔다. 민감 문서도 이 경로에서는 `MEMORY_NOT_FOUND` 다
- `accept`, `reject`, `update(user, id, content, boolean alwaysInject)`, `update(user, id, content, retrieval, sensitivity)` 넷: `requireWritableForUpdate` 로 잠근 항목의 종류가 `MEMORY` 가 아니면 `notFound()`. 넷이 함께 쓰는 private 메서드 하나로 둔다. 막지 않으면 주인이 `POST /api/v1/memories/{id}/reject` 로 문서를 `REJECTED` 로 만들 수 있다
- `delete` 는 그대로 둔다. 문서도 이 경로로 지운다

### 5. `MemoryDtos` 와 `MemoryDocumentController`

`MemoryDtos` 에 더한다.

```java
public record CreateDocumentRequest(
        @NotBlank String collection,
        @NotBlank String documentKey,
        @NotBlank @Size(max = 200) String title,
        @NotBlank @Size(max = 12000) String content,
        boolean sensitive) {}

public record UpdateDocumentRequest(
        @NotBlank @Size(max = 12000) String content,
        @NotNull Boolean sensitive,
        @NotNull Integer expectedRevision) {}

public record DocumentSummaryView(
        Long id, String collection, String documentKey, String title,
        boolean sensitive, int revision, Instant updatedAt) {}

public record DocumentView(
        Long id, String collection, String documentKey, String title,
        boolean sensitive, int revision, Instant updatedAt, String content) {}

public record CollectionView(String key, String displayName) {}
```

`backend/src/main/java/com/bifos/assistant/memory/presentation/MemoryDocumentController.java`:

| 경로 | 동작 |
| --- | --- |
| `GET /api/v1/memory-documents` | `documentsOf` 를 `DocumentSummaryView` 로 |
| `GET /api/v1/memory-documents/{id}` | `documentFor` 와 `contentOf` 로 `DocumentView` |
| `POST /api/v1/memory-documents` | `createDocument`. 응답은 `DocumentView` 이고 `content` 는 요청이 보낸 글이다 |
| `PUT /api/v1/memory-documents/{id}` | `reviseDocument`. 응답은 `DocumentView` |
| `GET /api/v1/memory-collections` | `collectionsFor` 를 `CollectionView` 로. 같은 컨트롤러에 두되 클래스의 `@RequestMapping` 을 `/api/v1` 로 둔다 |

`sensitive` 는 `MemorySensitivity.SENSITIVE` 와 `NORMAL` 로 바꾼다.

### 6. 이 phase 를 검증하는 `backend/src/test/java/com/bifos/assistant/memory/MemoryDocumentTest.java`

`@SpringBootTest @ActiveProfiles("test")`. `MemoryControllerTest` 처럼 컨트롤러를 직접 만들고 `CurrentUserProvider` 를 mock 으로 둔다. 사용자는 `new CurrentUser(1L, "dad@example.com", "dad", 1L, UserRole.ADMIN)` 와 `new CurrentUser(2L, "kid@example.com", "kid", 1L, UserRole.MEMBER)` 다. `@BeforeEach` 에서 `MemoryRepository` 와 `MemoryRevisionRepository` 를 비운다. 데이터베이스에 적힌 글은 `JdbcTemplate` 으로 읽는다.

| 입력 | 기대 |
| --- | --- |
| dad 가 `identity`, `application-profile`, 본문 `평문-표식-7391`, `sensitive: true` 로 만든다 | 응답의 `revision` 이 1, `sensitive` 가 참, `content` 가 보낸 글. `memory` 줄의 `entry_type` 이 `DOCUMENT`, `status` 가 `ACCEPTED`, `retrieval` 이 `SEARCH`, `scope` 가 `USER`, `content` 가 표식을 담지 않는다 |
| 같은 이름으로 한 번 더 만든다 | `MEMORY_DOCUMENT_EXISTS` |
| kid 가 같은 collection 과 이름으로 만든다 | 된다. 주인이 다르다 |
| kid 가 dad 의 문서 번호로 `GET` 과 `PUT` | 둘 다 `MEMORY_NOT_FOUND` |
| dad 의 목록 | 자기 문서 하나. 응답 타입에 `content` 가 없다 |
| dad 가 `expectedRevision: 1` 로 고친다 | `revision` 이 2. `memory_revision` 에 판 1 이 `UPDATED` 로 있다 |
| dad 가 다시 `expectedRevision: 1` 로 고친다 | `MEMORY_REVISION_CONFLICT`. `memory_revision` 의 줄 수가 그대로다 |
| `documentKey` 가 `Application_Profile` | `VALIDATION_FAILED` |
| `collection` 이 `no-such-area` | `VALIDATION_FAILED` |
| 문서를 만든 뒤 `MemoryController.readable()` | 그 문서가 없다 |
| 문서 번호로 `MemoryService.update(dad, id, "x", true)` | `MEMORY_NOT_FOUND`. 문서의 `retrieval` 이 `SEARCH` 그대로다. 민감 문서와 일반 문서 둘 다 본다 |
| 문서 번호로 `MemoryService.reject(dad, id)` 와 `accept(dad, id)` | 둘 다 `MEMORY_NOT_FOUND`. 문서의 `status` 가 `ACCEPTED` 그대로다 |
| 문서를 `MemoryService.delete` 로 지운 뒤 같은 이름으로 다시 만든다 | 된다. 지운 문서의 `DELETED` 판 `content` 가 표식을 담지 않는다 |
| `GET /api/v1/memory-collections` 에 해당하는 메서드 | `identity` 가 들어 있다 |

### 7. key 가 없을 때의 테스트 `backend/src/test/java/com/bifos/assistant/memory/MemoryDocumentEncryptionDisabledTest.java`

`@SpringBootTest(properties = {"assistant.memory.encryption.active-key-id=", "assistant.memory.encryption.keys="}) @ActiveProfiles("test")`. 같은 디렉터리의 `MemoryEncryptionDisabledTest.java` 가 선례다. `@BeforeEach` 에서 `MemoryRepository` 와 `MemoryRevisionRepository` 를 비운다.

| 입력 | 기대 |
| --- | --- |
| `sensitive: true` 문서를 만든다 | `MEMORY_ENCRYPTION_UNAVAILABLE` 이고 `SELECT COUNT(*) FROM memory` 가 0 이다 |
| `sensitive: false` 문서를 만든 뒤 `sensitive: true` 로 고친다 | 같은 예외다. `memory.content` 가 평문 그대로이고 `revision` 이 1 이며 `memory_revision` 에 줄이 없다 |
| `sensitive: false` 문서를 만든다 | 만들어진다 |

## 검증

```bash
# cwd: 저장소 root
(cd backend && ./gradlew test --tests '*MemoryDocumentTest' --tests '*MemoryDocumentEncryptionDisabledTest' --tests '*MemoryControllerTest' --tests '*MemoryServiceTest')
(cd backend && ./gradlew test)
(cd backend && ./gradlew qualityCheck)
node test/e2e/run.ts
scripts/check-public-safe.sh
```

- 모두 종료 코드 0
- `node test/e2e/run.ts` 는 `./gradlew test` 뒤에 돌린다. 기존 Memory 시나리오가 목록에서 문서를 뺀 뒤에도 통과하는지 본다

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/shared/error/ErrorCode.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/memory/domain/Memory.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/memory/infra/MemoryRepository.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/memory/infra/MemoryQueries.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/memory/application/MemoryService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/memory/presentation/MemoryDtos.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/memory/presentation/MemoryDocumentController.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/memory/MemoryDocumentTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/memory/MemoryDocumentEncryptionDisabledTest.java` | 신규 |
