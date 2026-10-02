# Phase 01. 민감으로 바꿀 때 앞선 판을 함께 암호화하고 민감 항목의 목록 수정을 거절한다

**Execution profile**: deep

## 목표

일반 항목을 민감 항목으로 바꿀 때 그 항목의 평문 판이 `memory_revision` 에 남지 않게 한다.
민감 항목을 Memory 목록의 수정 경로로 덮어쓰지 못하게 한다.
실행에 싣는 글을 조립할 때 암호문인 줄을 건너뛴다.

**범위 외**: 기동할 때의 보정(phase 02). 암호문의 모양(`v1`)과 AAD 는 바꾸지 않는다.

## 컨텍스트

- `backend/src/main/java/com/bifos/assistant/memory/application/MemoryService.java`
  - private `revise(user, memory, content, retrieval, sensitivity)` 가 `stored(content, sensitivity, memory.contentBinding())` 로 새 본문을 만들고, `revisions.save(MemoryRevision.of(memory, MemoryChangeType.UPDATED, user.id(), null, clock.instant()))` 로 물러나는 판을 남긴 뒤 `memory.revise(...)` 를 부른다. 물러나는 판은 `memory.content()` 와 `memory.contentKeyId()` 를 그대로 옮기므로, 일반 항목이었으면 평문이다
  - `revise` 를 부르는 쪽은 모두 `requireWritableForUpdate` 로 그 항목의 쓰기 잠금을 잡은 뒤다
  - `update(user, id, content, boolean alwaysInject)` 가 화면의 수정 경로(`PATCH /api/v1/memories/{id}`)다. 요청은 `content` 와 `alwaysInject` 를 늘 함께 보낸다(`MemoryDtos.UpdateMemoryRequest`). 본문 없이 다른 칸만 고치는 길이 없다
  - `update(user, id, content, MemoryRetrieval retrieval, MemorySensitivity sensitivity)` 는 민감도를 바꾸는 경로다. 고치지 않는다
- `backend/src/main/java/com/bifos/assistant/memory/application/MemoryContentCipher.java`: `seal(plain, binding)` 이 `StoredContent` 를 낸다. key 가 없으면 `MEMORY_ENCRYPTION_UNAVAILABLE` 을 던진다
- `backend/src/main/java/com/bifos/assistant/memory/domain/MemoryRevision.java`: `sealInPlace(StoredContent body)` 가 평문 판의 저장 모양만 바꾼다. 이미 암호문이면 `IllegalStateException` 이다. `contentBinding()` 은 `Memory.contentBinding()` 과 같은 규칙이다
- `backend/src/main/java/com/bifos/assistant/memory/infra/MemoryRevisionRepository.java`: `findByIdMemoryIdOrderByIdRevisionAsc(Long memoryId)` 가 있다
- `backend/src/main/java/com/bifos/assistant/context/ContextAssembler.java`: `appendAlways` 가 `memory.content()` 를 그대로 싣는다. `MemoryService.contentOf` 를 거치지 않는다
- `MemoryDtos.MemoryView.from` 은 민감 항목의 `content` 를 빈 글로 낸다. 그래서 화면이 그 항목을 고치면 본문을 읽지 않은 채 덮어쓴다
- 테스트 선례: `backend/src/test/java/com/bifos/assistant/memory/MemoryEncryptionTest.java`(key 가 있다), `MemoryEncryptionDisabledTest.java`(key 가 없다), `backend/src/test/java/com/bifos/assistant/context/ContextAssemblerTest.java`

**근거 문서**: `docs/adr/ADR-055-민감-memory-본문은-저장할-때-암호화하고-key-는-환경-변수로-받는다.md` 의 「적용 범위」 둘째 표와 「감당할 것」

## 의도 메모

- 판의 `sensitivity`, 판 번호, `changed_at` 은 바꾸지 않는다. 판의 뜻은 그대로이고 저장 모양만 바뀐다
- 암호화가 먼저다. key 가 없으면 `stored(...)` 가 먼저 던지므로 판도 본문도 바뀌지 않는다. 이 순서를 지킨다
- 민감 항목의 목록 수정은 요청을 통째로 거절한다. 그 요청은 본문을 늘 싣고, 민감 항목은 `ALWAYS` 가 되지 못해 「항상 싣기」 만 바꾸는 요청도 뜻이 없다
- `ContextAssembler` 가 암호문을 풀게 하지 않는다. 민감 항목은 `ALWAYS` 가 되지 못하므로 그 층에 암호문이 있다면 데이터가 어긋난 것이다. 건너뛰는 것이 맞다

## 작업 항목

### 1. `ErrorCode`

`backend/src/main/java/com/bifos/assistant/shared/error/ErrorCode.java` 의 `MEMORY_ENCRYPTION_UNAVAILABLE` 아래에 `MEMORY_SENSITIVE_NOT_EDITABLE(HttpStatus.CONFLICT)` 를 더한다.
Javadoc: 민감 항목은 Memory 목록의 수정 경로로 고치지 못한다. 목록이 본문을 싣지 않아 그 요청이 본문을 읽지 않은 채 덮어쓴다(ADR-055).

### 2. `MemoryRevisionRepository`

```java
/** 한 항목의 판 가운데 평문으로 남은 것을 찾는다. 그 항목을 민감으로 바꿀 때 함께 암호화한다(ADR-055). */
List<MemoryRevision> findByIdMemoryIdAndContentKeyIdIsNull(Long memoryId);
```

### 3. `MemoryService.revise`

새 민감도가 `SENSITIVE` 이면 판을 남긴 뒤 그 항목의 평문 판을 모두 암호화한다.

```java
revisions.save(MemoryRevision.of(memory, MemoryChangeType.UPDATED, user.id(), null, clock.instant()));
if (sensitivity == MemorySensitivity.SENSITIVE) {
    sealPlainRevisions(memory.id());
}
```

private `sealPlainRevisions(Long memoryId)`: `revisions.flush()` 뒤 `findByIdMemoryIdAndContentKeyIdIsNull(memoryId)` 의 줄마다 `row.sealInPlace(cipher.seal(row.content(), row.contentBinding()))` 를 부르고 저장한다. 방금 남긴 판도 평문이면 이 조회에 들어온다.
항목이 이미 민감 항목이었으면 평문 판이 없어 아무것도 하지 않는다. 주석에 그 까닭(일반에서 민감으로 바뀔 때와, 민감에서 일반으로 갔다가 다시 민감으로 올 때 평문 판이 생긴다)을 적는다.

### 4. `MemoryService.update(user, id, content, boolean alwaysInject)`

`requireWritableForUpdate` 로 잠근 항목의 `sensitivity()` 가 `SENSITIVE` 이면 `new ApiException(ErrorCode.MEMORY_SENSITIVE_NOT_EDITABLE, "a sensitive memory cannot be edited from the list")` 를 던진다. 메서드 Javadoc 에 한 문장을 더한다.

### 5. `ContextAssembler.appendAlways`

`.filter(memory -> memory.scope() == scope)` 뒤에 `.filter(memory -> !memory.sealed())` 를 더한다. 주석: 본문을 풀지 않는다. 암호문인 줄은 싣지 않는다(ADR-055).

### 6. `web/src/components/error-message.ts`

`MESSAGES` 에 `MEMORY_SENSITIVE_NOT_EDITABLE: "민감한 항목은 여기서 고칠 수 없어요."` 를 더한다.
`test/unit/error-message.test.ts` 에 이 코드의 문구를 단언하는 test 하나를 더한다.

### 7. 이 phase 를 검증하는 테스트

`MemoryEncryptionTest.java`

| 입력 | 기대 |
| --- | --- |
| 기존 `normalToSensitive` 를 고친다. 일반 항목을 만들고 민감으로 고친다 | 판 1 의 `content` 가 `v1.` 으로 시작하고 처음 글을 담지 않는다. `content_key_id` 가 `test-1` 이다. 판의 `sensitivity` 는 `NORMAL` 그대로다. `@DisplayName` 을 「일반 항목을 민감으로 고치면 지금 줄과 물러난 판이 모두 암호문이다」 로 고친다 |
| 일반 항목을 두 번 고친 뒤(판 1, 2 가 평문) 민감으로 고친다 | 판 1, 2, 3 이 모두 암호문이다. `SELECT COUNT(*) FROM memory_revision WHERE memory_id = ? AND content_key_id IS NULL` 이 0 이다 |
| 다른 일반 항목 하나를 한 번 고쳐 평문 판을 둔 채, 위 항목만 민감으로 고친다 | 다른 항목의 판은 평문 그대로다 |
| 민감 항목을 `memories.update(ADMIN, id, "x", false)` 로 고친다 | `ApiException` 이고 코드가 `MEMORY_SENSITIVE_NOT_EDITABLE` 이다. `memory.revision` 과 `memory.content` 가 그대로이고 `memory_revision` 에 줄이 늘지 않는다 |
| 일반 항목을 `memories.update(ADMIN, id, "x", false)` 로 고친다 | 된다 |

`MemoryEncryptionDisabledTest.java`

| 입력 | 기대 |
| --- | --- |
| 기존 `normalToSensitiveIsRejected` 를 고친다. 일반 항목을 먼저 한 번 고쳐 평문 판 1 을 둔 뒤 민감으로 고친다. 그 테스트의 기존 단언 둘(`revision` 이 1, `memory_revision` 이 0 줄)을 `revision` 2, 줄 수 1 로 바꾼다 | `MEMORY_ENCRYPTION_UNAVAILABLE` 이다. 판 1 의 `content` 가 평문 그대로이고 `content_key_id` 가 null 이다. `memory.revision` 이 2 그대로이고 `memory_revision` 의 줄 수가 1 그대로다 |

`MemoryServiceTest.java`

| 입력 | 기대 |
| --- | --- |
| 기존 `sensitiveItemsCannotAlwaysBeInjected` 가 민감 항목에 `memories.update(ADMIN, sensitive.id(), "내용", true)` 를 부르고 `MEMORY_SENSITIVE_ALWAYS` 를 기대한다. 그 단언의 기대 코드를 `MEMORY_SENSITIVE_NOT_EDITABLE` 로 바꾼다. 같은 테스트의 다른 단언(만들 때와 민감도를 바꾸는 `update` 의 `MEMORY_SENSITIVE_ALWAYS`)은 그대로 둔다 | 테스트가 통과한다 |

`ContextAssemblerTest.java`

| 입력 | 기대 |
| --- | --- |
| 이 클래스에는 `JdbcTemplate` 주입이 없다. `@Autowired JdbcTemplate jdbc` 를 더한다. `JdbcTemplate` 으로 `scope` 가 `USER`, `owner_user_id` 가 1, `collection` 이 `core`, `retrieval` 이 `ALWAYS`, `always_inject` 가 참, `sensitivity` 가 `NORMAL`, `content` 가 `v1.봉인-표식-5512`, `content_key_id` 가 `test-1` 인 `ACCEPTED` 개인 줄을 넣고, 평문인 `ALWAYS` 항목 하나를 서비스로 만든 뒤 `assembler.assemble(ADMIN, agentId)` 를 부른다. 그 에이전트는 `core` 만 받으므로 collection 이 `core` 가 아니면 필터 없이도 통과해 검사가 뜻을 잃는다 | 조립한 글이 `봉인-표식-5512` 를 담지 않고 평문 항목의 본문은 담는다 |

넣는 줄의 칸은 `MemoryContentBackfillTest.insertMemory` 의 INSERT 를 선례로 삼는다.

## 검증

```bash
# cwd: 저장소 root
(cd backend && ./gradlew test --tests '*MemoryEncryptionTest' --tests '*MemoryEncryptionDisabledTest' --tests '*ContextAssemblerTest' --tests '*MemoryControllerTest' --tests '*MemoryServiceTest')
(cd backend && ./gradlew test)
(cd backend && ./gradlew qualityCheck)
node --test test/unit/error-message.test.ts
(cd web && pnpm lint && pnpm format:check && pnpm typecheck)
node test/e2e/run.ts
scripts/check-public-safe.sh
```

- 모두 종료 코드 0
- `node test/e2e/run.ts` 는 `./gradlew test` 뒤에 돌린다

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/shared/error/ErrorCode.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/memory/infra/MemoryRevisionRepository.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/memory/application/MemoryService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/context/ContextAssembler.java` | 수정 |
| `web/src/components/error-message.ts` | 수정 |
| `test/unit/error-message.test.ts` | 수정 |
| `backend/src/test/java/com/bifos/assistant/memory/MemoryEncryptionTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/memory/MemoryEncryptionDisabledTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/context/ContextAssemblerTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/memory/MemoryServiceTest.java` | 수정 |
