# Phase 02. 민감 줄을 암호문으로 저장하고 읽는 자리에서 푼다

**Execution profile**: deep

## 목표

`sensitivity` 가 `SENSITIVE` 인 Memory 의 본문을 `memory.content` 와 `memory_revision.content` 에 암호문으로 저장한다.
본문을 내는 자리는 `MemoryService.contentOf` 를 거쳐 푼다. key 가 없으면 민감 항목의 저장을 거절하고 평문을 남기지 않는다.

**범위 외**: 이미 평문으로 남은 민감 줄을 옮기는 일(phase 03), 민감 항목을 만드는 화면과 API(다른 plan).

## 컨텍스트

- phase 01 이 `memory.application.MemoryContentCipher`(`enabled()`, `seal(plain, binding)`, `open(stored, keyId, binding)`)와 `memory.domain.StoredContent(content, keyId)` 와 `ErrorCode.MEMORY_ENCRYPTION_UNAVAILABLE` 을 만들었다
- `backend/src/main/java/com/bifos/assistant/memory/domain/Memory.java`: `@Getter @Accessors(fluent = true)` 엔티티다. 본문은 `content`(`TEXT`) 칸이다. 만드는 길은 `Memory.accepted(scope, ownerUserId, groupId, title, content, placement, acceptedByUserId, now)` 와 `Memory.proposedUser(...)` 둘이고, 고치는 길은 `revise(content, retrieval, sensitivity, updatedProposalDedupKey, at)` 하나다
- `backend/src/main/java/com/bifos/assistant/memory/domain/MemoryRevision.java`: `MemoryRevision.of(memory, changeType, changedByUserId, reason, at)` 가 `memory.content()` 를 그대로 옮긴다. 그래서 `Memory` 가 암호문을 갖고 있으면 판도 암호문이 된다
- `backend/src/main/java/com/bifos/assistant/memory/application/MemoryService.java`: 만들기는 `create(user, scope, title, content, collection, retrieval, sensitivity)`, 고치기는 private `revise(user, memory, content, retrieval, sensitivity)` 로 모인다. `revise` 는 판을 먼저 남기고 `memory.revise` 를 부른다. 제안 중복 키는 평문 본문으로 계산한다(`proposalDedupKey`)
- 본문을 밖으로 내는 자리는 넷이다

  | 자리 | 지금 | 민감 줄이 올 수 있는가 |
  | --- | --- | --- |
  | `mcp/application/McpToolService.readMemory` | `memory.content()` | 온다. 민감 허용을 받은 에이전트가 `memory_read` 로 읽는다 |
  | `memory/presentation/MemoryDtos.MemoryView.from` | `memory.content()` | 온다. 목록이 볼 수 있는 줄을 모두 낸다 |
  | `context/ContextAssembler` 의 항상 층 | `memory.content()` | 오지 않는다. 민감 항목은 `ALWAYS` 로 저장하지 못한다 |
  | `memory/application/MemoryProposer` | 제안의 본문 | 오지 않는다. 제안은 `core` 의 `NORMAL` 이다 |

- 테스트 데이터베이스는 Flyway 를 끄고 엔티티로 표를 만든다. 마이그레이션은 따로 검사한다. 선례는 `backend/src/test/java/com/bifos/assistant/memory/MemoryV2MigrationTest.java` 다
- 가장 큰 마이그레이션은 `backend/src/main/resources/db/migration/V47__memory_v2.sql` 이다

**근거 문서**: `docs/adr/ADR-054-민감-memory-본문은-저장할-때-암호화하고-key-는-환경-변수로-받는다.md`, `docs/data-schema.md` 의 「memory」 와 「memory_revision」

## 의도 메모

- 암호화를 JPA 변환기로 하지 않는다. 변환기는 그 줄의 민감도를 모른다. 서비스가 저장하기 전에 `StoredContent` 로 바꾼다
- `Memory.content()` 의 뜻을 「저장된 글」 로 둔다. 민감 줄이면 암호문이다. 푸는 것을 잊은 자리는 암호문을 내고, 평문을 새게 하지 않는다
- 목록(`MemoryView`)은 민감 본문을 풀지 않고 빈 글로 낸다. 목록이 key 문제로 통째로 실패하지 않고, 화면을 열 때마다 민감 본문이 오가지 않는다
- 판은 그때의 모양대로 둔다. 민감도를 `NORMAL` 로 내려도 물러난 판은 암호문으로 남는다

## 작업 항목

### 1. `backend/src/main/resources/db/migration/V48__memory_content_key.sql`

```sql
-- 민감 본문을 암호화한 key 의 id 다(ADR-054). 비어 있으면 content 는 평문이다.
-- 지운 항목의 본문도 판에 남으므로 두 표에 함께 둔다.
ALTER TABLE memory ADD COLUMN content_key_id VARCHAR(32) NULL;
ALTER TABLE memory_revision ADD COLUMN content_key_id VARCHAR(32) NULL;
```

`origin/main` 에 V48 이 이미 있으면 다음 번호로 옮기고 아래 테스트의 번호도 함께 고친다.

### 2. `Memory` 와 `MemoryRevision`

`Memory`:

- `@Column(name = "content_key_id", length = 32) private String contentKeyId;` 를 `content` 아래에 더한다
- private 생성자와 `accepted(...)` 의 `String content` 인자를 `StoredContent body` 로 바꾼다. `this.content = body.content(); this.contentKeyId = body.keyId();`
- `proposedUser(...)` 의 인자는 그대로 `String content` 다. 안에서 `StoredContent.plain(content)` 로 넘긴다
- `revise(StoredContent body, MemoryRetrieval retrieval, MemorySensitivity sensitivity, String updatedProposalDedupKey, Instant at)` 로 바꾼다. 두 칸을 함께 적는다
- `public boolean sealed()`: `contentKeyId != null`
- `public String contentBinding()`: `USER` 면 `"USER:" + ownerUserId`, `GROUP` 이면 `"GROUP:" + groupId`
- 클래스 Javadoc 에 한 문단을 더한다. 「`content` 는 저장된 글이다. 민감 줄이면 암호문이고 `contentKeyId` 가 그 key 를 적는다. 평문은 `MemoryService.contentOf` 로 읽는다(ADR-054).」

`MemoryRevision`:

- `@Column(name = "content_key_id", length = 32) private String contentKeyId;` 를 더하고 `of(...)` 가 `memory.contentKeyId()` 를 옮긴다
- `public String contentBinding()` 을 `Memory` 와 같은 규칙으로 더한다

`Memory.accepted(` 와 `.revise(` 를 부르는 곳을 모두 맞춘다. `grep -rn "Memory.accepted(\|\.revise(" backend/src` 로 찾는다. 테스트가 직접 부르면 `StoredContent.plain("...")` 으로 감싼다.

### 3. `MemoryService`

- `MemoryContentCipher cipher` 를 주입받는다
- private `StoredContent stored(String plain, MemorySensitivity sensitivity, String binding)`: `SENSITIVE` 면 `cipher.seal(plain, binding)`, 아니면 `StoredContent.plain(plain)`
- `create(user, scope, title, content, collection, retrieval, sensitivity)`: `placement(...)` 검사 뒤에 `stored(...)` 를 만들고 `Memory.accepted` 에 넘긴다. `binding` 은 `GROUP` 이면 `"GROUP:" + user.groupId()`, `USER` 면 `"USER:" + user.id()` 다. **암호화가 거절되면 아무것도 저장하지 않는다**
- private `revise(...)`: `requirePlaceable` 다음, **판을 남기기 전에** `stored(content, sensitivity, memory.contentBinding())` 를 만든다. 그 뒤에 판을 남기고 `memory.revise(body, ...)` 를 부른다. 제안 중복 키는 민감도가 `NORMAL` 이면 지금처럼 평문 `content` 로 계산하고, `SENSITIVE` 면 `null` 로 둔다. 그 키는 본문의 해시라 평문 칸에 민감 본문의 지문이 남는다
- `public String contentOf(Memory memory)`: `memory.sealed()` 면 `cipher.open(memory.content(), memory.contentKeyId(), memory.contentBinding())`, 아니면 `memory.content()`. Javadoc: 「본문을 평문으로 낸다. 본문을 밖으로 내는 자리는 엔티티의 `content()` 가 아니라 이것을 쓴다.」
- 클래스 Javadoc 에 한 줄을 더한다. 「민감 항목의 본문은 암호문으로 저장한다(ADR-054).」

### 4. 본문을 내는 자리

- `McpToolService.readMemory`: `result(memory.content(), false)` 를 `result(memories.contentOf(memory), false)` 로 바꾼다. `catch` 는 그대로 둔다. `MEMORY_ENCRYPTION_UNAVAILABLE` 은 지금처럼 다시 던져진다
- `MemoryDtos.MemoryView`: record 에 `boolean sensitive` 를 `omittedFromContext` 앞에 더한다. `from` 은 민감도가 `SENSITIVE` 이거나 `memory.sealed()` 이면 빈 글을, 아니면 `memory.content()` 를 `content` 로, `memory.sensitivity() == MemorySensitivity.SENSITIVE` 를 `sensitive` 로 낸다. Javadoc 에 「민감 항목의 본문은 목록에 싣지 않는다」 를 더한다
- `ContextAssembler` 와 `MemoryProposer` 는 고치지 않는다

### 5. 마이그레이션 테스트 `backend/src/test/java/com/bifos/assistant/memory/MemoryContentKeyMigrationTest.java`

`MemoryV2MigrationTest` 의 방식(H2 메모리 데이터베이스, `Flyway` 를 목표 판까지 직접 돌린다)을 따른다.

- V47 까지 올리고 `memory` 한 줄과 `memory_revision` 한 줄을 넣는다. 본문은 `옮기기-전-본문` 이다
- V48 을 올린다
- 두 줄의 `content` 가 그대로이고 `content_key_id` 가 `NULL` 이다

### 6. 암호화 테스트 `backend/src/test/java/com/bifos/assistant/memory/MemoryEncryptionTest.java`

`@SpringBootTest @ActiveProfiles("test")`. `MemoryService`, `MemoryRepository`, `MemoryRevisionRepository`, `JdbcTemplate` 을 주입받고 `@BeforeEach` 에서 두 저장소를 비운다. 사용자는 `new CurrentUser(1L, "admin@example.com", "admin", 1L, UserRole.ADMIN)` 이다. **데이터베이스에 실제로 적힌 글은 `JdbcTemplate` 으로 읽는다.** 엔티티로 읽으면 같은 값을 다시 볼 뿐이다.

| 입력 | 기대 |
| --- | --- |
| `create(user, USER, "신원 문서", "평문-표식-7391", "identity", SEARCH, SENSITIVE)` | `SELECT content, content_key_id FROM memory` 의 `content` 가 `v1.` 으로 시작하고 `평문-표식-7391` 을 담지 않는다. `content_key_id` 가 `test-1` 이다. `contentOf` 는 `평문-표식-7391` 이다 |
| 그 항목을 `update(user, id, "평문-표식-8802", SEARCH, SENSITIVE)` | `memory_revision` 의 판 1 `content` 가 두 표식을 모두 담지 않고 `content_key_id` 가 `test-1` 이다. `memory` 의 `content` 도 표식을 담지 않는다 |
| 그 항목을 `delete` | `memory_revision` 의 `DELETED` 판 `content` 가 `평문-표식-8802` 를 담지 않는다 |
| `NORMAL` 항목을 만든다 | `content` 가 평문 그대로이고 `content_key_id` 가 `NULL` 이다 |
| `NORMAL` 항목을 `SENSITIVE` 로 고친다 | `memory.content` 가 암호문이 된다. 물러난 판 1 은 평문이고 `content_key_id` 가 `NULL` 이다 |
| `SENSITIVE` 항목을 `NORMAL` 로 고친다 | `memory.content` 가 평문이고 `content_key_id` 가 `NULL` 이다. 물러난 판 1 은 암호문이다 |
| `GROUP` 범위의 `SENSITIVE` 항목 | `contentOf` 가 원래 글을 낸다 |
| 민감 항목의 `MemoryView` | `content` 가 빈 글이고 `sensitive` 가 참이다. `MemoryController` 는 `MemoryControllerTest` 처럼 직접 만든다 |

### 7. key 가 없을 때의 테스트 `backend/src/test/java/com/bifos/assistant/memory/MemoryEncryptionDisabledTest.java`

`@SpringBootTest(properties = {"assistant.memory.encryption.active-key-id=", "assistant.memory.encryption.keys="}) @ActiveProfiles("test")`.

**이 테스트가 「key 가 없을 때 평문으로 저장되는 경로가 없다」 를 본다.**
`@BeforeEach` 에서 `MemoryRepository` 와 `MemoryRevisionRepository` 를 비운다. 설정이 다른 Spring 컨텍스트도 같은 메모리 데이터베이스를 쓰므로, 비우지 않으면 줄 수 단언이 실행 순서에 따라 흔들린다.

| 입력 | 기대 |
| --- | --- |
| `SENSITIVE` 항목을 만든다 | `ApiException` 이고 코드가 `MEMORY_ENCRYPTION_UNAVAILABLE`. `SELECT COUNT(*) FROM memory` 가 0 이다 |
| `NORMAL` 항목을 만든 뒤 `SENSITIVE` 로 고친다 | 같은 예외다. `memory.content` 와 `sensitivity` 와 `revision` 이 그대로이고 `SELECT COUNT(*) FROM memory_revision` 이 0 이다 |
| `NORMAL` 항목을 만들고 고치고 지운다 | 모두 된다 |
| `JdbcTemplate` 으로 암호문 모양의 민감 줄(`content` 는 `v1.AAAA.BBBB`, `content_key_id` 는 `gone-1`)을 넣고 `contentOf` 를 부른다 | `ApiException` 이고 코드가 `MEMORY_ENCRYPTION_UNAVAILABLE` |
| 같은 줄이 있는 채로 `readableBy` 로 목록을 읽어 `MemoryView` 로 바꾼다 | 실패하지 않는다. 그 줄의 `content` 는 빈 글이다 |
| `JdbcTemplate` 으로 평문으로 남은 민감 줄(`content` 는 `평문-표식-5520`, `content_key_id` 는 `NULL`)을 넣고 목록을 `MemoryView` 로 바꾼다 | 그 줄의 `content` 가 빈 글이다. 평문으로 남은 민감 줄도 목록으로 나가지 않는다 |

### 8. `backend/src/test/java/com/bifos/assistant/mcp/McpMemoryToolTest.java`

경우 하나를 더한다. origin 실행의 에이전트에 `identity` 와 민감 허용을 주고 민감 항목을 `memory_read` 로 읽으면 평문이 나온다.
허용은 `AgentMemoryCollectionRepository` 를 `@Autowired` 로 받아 `AgentMemoryCollection.of(agentId, "identity", true, now)` 를 저장해 준다. 이 테스트에는 그 선례가 없다. `backend/src/test/java/com/bifos/assistant/agent/AgentMemoryCollectionServiceTest.java` 가 선례다.

### 9. 그 밖의 테스트

`backend/src/test/java/com/bifos/assistant/memory/MemoryServiceTest.java` 에 민감 항목의 `bodyFor(...).content()` 가 평문과 같다고 단언하는 줄이 있다(385줄 근처). 이제 그 값은 암호문이다. `memories.contentOf(memories.bodyFor(...))` 로 바꾼다. **`Memory.content()` 가 평문을 내도록 되돌리지 않는다.**

`MemoryView` 에 칸이 늘었다. `grep -rn "new MemoryView(" backend/src` 로 직접 만드는 곳을 찾아 맞춘다. `test/e2e/scenarios/memory.ts` 의 `MemoryView` 타입에 `sensitive: boolean` 을 더한다. 단언은 더하지 않는다.

## 검증

```bash
# cwd: 저장소 root
(cd backend && ./gradlew test --tests '*MemoryEncryptionTest' --tests '*MemoryEncryptionDisabledTest' --tests '*MemoryContentKeyMigrationTest' --tests '*McpMemoryToolTest')
(cd backend && ./gradlew test)
(cd backend && ./gradlew qualityCheck)
node test/e2e/run.ts
node --test 'test/unit/**/*.test.ts'
scripts/check-public-safe.sh
! git grep -n "memory.content()" -- backend/src/main/java/com/bifos/assistant/mcp backend/src/main/java/com/bifos/assistant/memory/presentation/MemoryController.java
```

- 모두 종료 코드 0. 마지막 줄은 일치하는 줄이 없어야 한다
- `node test/e2e/run.ts` 는 `./gradlew test` 뒤에 돌린다. 앞선 실행이 남긴 데이터에 걸린다
- e2e 는 암호화 key 없이 뜬다. 민감 항목을 만들지 않으므로 통과해야 한다. 실패하면 key 가 없을 때 기동하지 못한다는 뜻이다

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/resources/db/migration/V48__memory_content_key.sql` | 신규 |
| `backend/src/main/java/com/bifos/assistant/memory/domain/Memory.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/memory/domain/MemoryRevision.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/memory/application/MemoryService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/memory/presentation/MemoryDtos.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/mcp/application/McpToolService.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/memory/MemoryContentKeyMigrationTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/memory/MemoryEncryptionTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/memory/MemoryEncryptionDisabledTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/mcp/McpMemoryToolTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/memory/*.java` | 수정 |
| `test/e2e/scenarios/memory.ts` | 수정 |
