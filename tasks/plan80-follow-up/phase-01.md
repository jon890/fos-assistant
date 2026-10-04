# Phase 01. 할 일 표와 사람이 쓰는 API

**Execution profile**: deep

## 목표

사용자 한 사람의 할 일을 저장하고, 사람이 웹 JWT 로 더하고 받아들이고 고치고 끝내는 API 를 연다.
먼저 알리기(phase 02)와 에이전트가 제안하는 도구(phase 03)가 이 표와 서비스 위에 선다.

**범위 외**: 지금 화면의 판정(phase 02). MCP 도구 `follow_up_propose` 와 제안 억제(phase 03). 화면(plan81).

## 컨텍스트

- 할 일의 뜻, 상태 전이, API 는 `docs/backend/follow-up.md` 가 갖는다. 표의 칸과 유일 제약은 `docs/backend/schema/attention.md` 의 「follow_up」 이 갖는다. 이 phase 는 그 두 문서를 그대로 구현한다
- 결정은 `docs/adr/ADR-073-할-일은-에이전트가-제안하고-사람이-받아들인-것만-챙긴다.md` 다
- 새 최상위 패키지 `followup` 은 층 순서에서 `chat` 바로 위, `orchestration` 아래에 둔다. 층 순서는 `backend/src/test/java/com/bifos/assistant/architecture/TopLevelPackageOrder.java` 의 `ORDER` 가 갖고 `docs/backend/packages.md` 의 「최상위 패키지의 층 순서」 가 그것을 옮겨 적는다(ADR-068)
  - `followup` 은 `chat` 의 `ConversationAccess`(대화 주인 판정)와 `ConversationPublicIdLookup`(대화 번호를 공개 식별자로)을 쓴다. 둘 다 `backend/src/main/java/com/bifos/assistant/chat/application/` 에 있다
  - `ConversationAccess.requireOwnId(CurrentUser, UUID)` 는 남의 대화와 없는 대화를 같은 404 `CONVERSATION_NOT_FOUND` 로 답한다
  - `ConversationPublicIdLookup` 은 `usage.application.ConversationPublicIds` 의 구현이다. `publicIdsOf(Collection<Long>)` 로 한 번에 읽는다
- 따라 할 패턴
  - 컨트롤러와 현재 사용자: `backend/src/main/java/com/bifos/assistant/memory/presentation/MemoryController.java`(`CurrentUserProvider currentUser`, `currentUser.require()`)
  - 공개 식별자 칸: `backend/src/main/java/com/bifos/assistant/connector/domain/ConnectorAction.java` 의 `publicId`(`@UuidGenerator(style = UuidGenerator.Style.VERSION_7)`, `@JdbcTypeCode(SqlTypes.BINARY)`, `columnDefinition = "BINARY(16)"`)
  - 엔티티 모양: `backend/src/main/java/com/bifos/assistant/memory/domain/Memory.java`(`@Getter`, `@Accessors(fluent = true)`, `@NoArgsConstructor(access = AccessLevel.PROTECTED)`)
  - 지문: `com.bifos.assistant.shared.util.Sha256.hex(String)` 가 SHA-256 소문자 16진수 64자를 낸다
  - 오류: `backend/src/main/java/com/bifos/assistant/shared/error/ErrorCode.java` 에 값을 더하고 `ApiException(ErrorCode, String)` 으로 던진다
- 저장소 쿼리는 `RepositoryQueryMysqlTest` 가 실제 MySQL 에서 모두 한 번씩 돌린다. 저장소 메서드의 인자는 `Long`, `UUID`, `Instant`, enum, `Collection` 이면 `RepositoryQuerySweep` 이 이미 만든다

**근거 문서**: `docs/backend/follow-up.md` 의 「상태」, 「API」, 「지키는 것」, `docs/backend/schema/attention.md` 의 「follow_up」, `docs/adr/ADR-073-할-일은-에이전트가-제안하고-사람이-받아들인-것만-챙긴다.md`, `docs/backend/packages.md`

## 의도 메모

- 할 일을 Memory 의 한 종류로 두지 않는다. 상태와 기한이 있고 대화마다 실리면 안 된다(ADR-073 의 대안 기각)
- `open_marker` 는 열린 줄만 1 이고 끝난 줄은 NULL 이다. MySQL 과 H2 모두 유일 제약에서 NULL 을 서로 다르다고 본다. 그래서 끝난 할 일과 같은 제목을 다시 열 수 있다
- 사람이 직접 더한 줄은 `proposed_by_execution_id` 가 비고 바로 `OPEN` 이며 `accepted_at` 을 만든 시각으로 적는다
- 같은 열린 제목을 사람이 다시 더하면 새 줄을 만들지 않고 있는 줄을 돌려준다. 유일 제약 위반을 500 으로 내지 않는다. 동시에 들어온 두 요청은 제약이 하나만 남기고, 진 쪽은 다시 읽어 그 줄을 돌려준다
- `title_key` 는 phase 03 의 제안 억제도 쓴다. 정규화 함수를 서비스 하나에 두어 두 경로가 같은 값을 쓰게 한다

## 작업 항목

### 1. `backend/src/main/resources/db/migration/V68__follow_up.sql`

번호는 구현할 때 main 의 마지막 다음 번호로 바꾸고, 바꾸면 아래 변경 파일 표도 같은 커밋에서 고친다.
`docs/backend/schema/attention.md` 의 「follow_up」 칸을 그대로 만든다.

- `id BIGINT NOT NULL AUTO_INCREMENT`, `public_id BINARY(16) NOT NULL`, `user_id BIGINT NOT NULL`, `conversation_id BIGINT NULL`, `proposed_by_execution_id BIGINT NULL`, `title VARCHAR(200) NOT NULL`, `title_key CHAR(64) NOT NULL`, `due_at DATETIME(6) NULL`, `waiting BOOLEAN NOT NULL DEFAULT FALSE`, `status VARCHAR(20) NOT NULL`, `open_marker TINYINT NULL`, `created_at`, `updated_at DATETIME(6) NOT NULL`, `accepted_at`, `closed_at DATETIME(6) NULL`
- `UNIQUE KEY uk_follow_up_public_id (public_id)`, `UNIQUE KEY uk_follow_up_open_title (user_id, title_key, open_marker)`
- `CONSTRAINT fk_follow_up_user FOREIGN KEY (user_id) REFERENCES app_user(id)`. 대화와 실행에는 외래 키를 걸지 않는다(`V46__connector_action.sql` 과 같다)
- `ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci`
- 색인 `idx_follow_up_user_status (user_id, status)`, `idx_follow_up_conversation_status (conversation_id, status)`

### 2. `followup` 패키지

| 파일 | 내용 |
| --- | --- |
| `backend/src/main/java/com/bifos/assistant/followup/domain/type/FollowUpStatus.java` | `PROPOSED`, `OPEN`, `DONE`, `DROPPED`, `REJECTED` |
| `backend/src/main/java/com/bifos/assistant/followup/domain/FollowUp.java` | 엔티티. 위 칸. 정적 생성자 `proposed(userId, conversationId, executionId, title, titleKey, dueAt, waiting, now)` 와 `opened(userId, conversationId, title, titleKey, dueAt, waiting, now)`. 전이 메서드 `accept(now)`, `reject(now)`, `done(now)`, `drop(now)`, `revise(title, titleKey, dueAt, waiting, now)`. 허용하지 않는 전이는 `IllegalStateException` 이 아니라 아래 서비스가 `FOLLOW_UP_STATE_CONFLICT` 로 바꿀 수 있게 참거짓을 돌려주거나 서비스가 먼저 상태를 본다. 끝난 세 상태로 갈 때 `open_marker` 를 비우고 `closed_at` 을 적는다 |
| `backend/src/main/java/com/bifos/assistant/followup/infra/FollowUpRepository.java` | `findByPublicIdAndUserId(UUID, Long)`, `findByUserIdAndStatusInOrderByIdAsc(Long, Collection<FollowUpStatus>)`, `findByUserIdAndTitleKeyAndOpenMarker(Long, String, Integer)` |
| `backend/src/main/java/com/bifos/assistant/followup/application/FollowUpService.java` | 아래 「서비스」 |
| `backend/src/main/java/com/bifos/assistant/followup/presentation/FollowUpController.java` | `@RequestMapping("/api/v1/follow-ups")`. 경로와 권한과 흐름만 둔다 |
| `backend/src/main/java/com/bifos/assistant/followup/presentation/FollowUpDtos.java` | `CreateFollowUpRequest(String title, Instant dueAt, Boolean waiting, UUID conversationId)`, `UpdateFollowUpRequest`, `FollowUpView` |

**서비스** `FollowUpService`:

- `static String titleKey(String title)`: `Normalizer.normalize(title, Normalizer.Form.NFC)` 뒤 `strip()`, 연속 공백(`\\s+`)을 한 칸으로, `toLowerCase(Locale.ROOT)`, 그 글의 `Sha256.hex`. phase 03 도 이 함수를 쓴다
- `list(CurrentUser)`: `PROPOSED` 와 `OPEN` 을 만든 순서로
- `create(CurrentUser, title, dueAt, waiting, conversationPublicId)`: 제목을 `strip()` 해 1자부터 200자가 아니면 400 `VALIDATION_FAILED`. `conversationPublicId` 가 있으면 `ConversationAccess.requireOwnId` 로 번호를 얻는다. 같은 `titleKey` 의 열린 줄이 있으면 그 줄을 돌려준다. 없으면 `OPEN` 줄을 만든다
- `update(CurrentUser, UUID id, ...)`: `PROPOSED` 나 `OPEN` 만. 제목을 바꾸면 `titleKey` 도 다시 계산한다. 바꾼 제목이 다른 열린 줄과 같아지면 409 `FOLLOW_UP_STATE_CONFLICT`
- `accept`, `reject`, `done`, `drop(CurrentUser, UUID id)`: `docs/backend/follow-up.md` 「상태」 표의 전이만. 아니면 409 `FOLLOW_UP_STATE_CONFLICT`
- 남의 줄과 없는 줄은 404 `FOLLOW_UP_NOT_FOUND`
- 로그는 `log.info("follow-up {} userId={} followUpId={}", 동작, userId, id)` 모양으로 번호만 남긴다. 제목을 넘기지 않는다

**응답** `FollowUpView`: `id`(공개 식별자), `title`, `status`, `dueAt`, `waiting`, `conversationId`(대화 공개 식별자. `ConversationPublicIdLookup.publicIdsOf` 로 바꾼다), `proposed`(`proposed_by_execution_id` 가 있으면 참), `createdAt`, `acceptedAt`, `closedAt`.

**`dueAt` 의 형식**: 요청 본문은 ISO-8601 시각(`2026-10-05T09:00:00Z`)이다. 날짜만 받는 형식은 phase 03 의 도구 인자에만 있다.

**`PATCH` 는 키가 없는 것과 `null` 을 다르게 읽는다.** `dueAt` 이 `null` 이면 기한을 지우고, 키가 없으면 그대로 둔다.
record 하나로는 둘을 구분하지 못한다. 컨트롤러가 본문을 Jackson 3 의 `tools.jackson.databind.JsonNode` 로 받아 `has("dueAt")` 를 본 뒤 `UpdateFollowUpRequest(String title, Instant dueAt, boolean dueAtPresent, Boolean waiting)` 로 바꿔 서비스에 넘긴다.
`title` 과 `waiting` 은 키가 없거나 `null` 이면 그대로 둔다.

### 3. `ErrorCode`

`backend/src/main/java/com/bifos/assistant/shared/error/ErrorCode.java` 에 `FOLLOW_UP_NOT_FOUND(HttpStatus.NOT_FOUND)`, `FOLLOW_UP_STATE_CONFLICT(HttpStatus.CONFLICT)` 를 더한다.

### 4. 층 순서

- `TopLevelPackageOrder.ORDER` 에서 `"chat"` 바로 뒤에 `"followup"` 을 넣는다
- `backend/src/test/java/com/bifos/assistant/architecture/TopLevelPackageOrderTest.java` 의 개수 단언(`hasSize(13)` 둘, `others` 의 `hasSize(12)`, `@DisplayName` 의 「열셋」)을 지금 값에 1 을 더한 값으로 고친다. plan79 가 먼저 `attention` 을 넣었으면 그 값에서 1 을 더한다
- `docs/backend/packages.md` 의 「패키지와 책임」 표에 `followup` 줄(「할 일의 저장과 상태 전이, 사람이 쓰는 API, 에이전트의 제안 저장」)을 더하고, 「최상위 패키지의 층 순서」 표에 `chat` 다음 자리로 넣고 뒤의 자리 번호를 하나씩 민다

### 5. 문서

- `docs/backend/schema/attention.md` 의 「follow_up」 절 머리의 「**아직 구현 전이다.**」 줄을 지운다. plan79 phase 02 가 머리 문장을 「`follow_up` 은 아직 마이그레이션이 없다」 로 바꿔 두었으면 그 문장도 지운다. plan79 phase 02 가 아직이면 머리 문장에서 `follow_up` 만 뺀다
- `docs/backend/schema/README.md` 의 표 줄에서 `follow_up` 에 붙은 구현 전 표시를 위와 같게 맞춘다

### 6. 이 phase 를 검증하는 테스트

`backend/src/test/java/com/bifos/assistant/followup/FollowUpServiceTest.java`(`@SpringBootTest`, `@ActiveProfiles("test")`)

| 입력 | 기대 |
| --- | --- |
| `titleKey("  할 일   검사 ")` 와 `titleKey("할 일 검사")`, `titleKey("ABC")` 와 `titleKey("abc")` | 각각 같다. 64자 16진수다 |
| 아빠가 제목 `할 일 검사 7391` 로 `create` | `OPEN`, `acceptedAt` 이 채워지고 `proposed` 가 거짓 |
| 같은 제목에 공백만 다르게 다시 `create` | 새 줄 없이 같은 `id` |
| `done` 뒤 같은 제목으로 `create` | 새 `OPEN` 줄. 앞 줄은 `DONE` 이고 `closedAt` 이 있다 |
| `OPEN` 에 `accept` | 409 `FOLLOW_UP_STATE_CONFLICT` |
| `DONE` 에 `update` | 409 `FOLLOW_UP_STATE_CONFLICT` |
| 아이가 아빠의 `id` 로 `done` | 404 `FOLLOW_UP_NOT_FOUND` |
| 아빠가 아이의 대화 공개 식별자로 `create` | 404 `CONVERSATION_NOT_FOUND` |
| 제목이 공백뿐이거나 201자 | 400 `VALIDATION_FAILED` |

`backend/src/test/java/com/bifos/assistant/followup/FollowUpMigrationTest.java`: H2 에서 Flyway 를 끝까지 적용하고 `uk_follow_up_open_title` 이 열린 같은 제목 둘을 막고 끝난 줄(`open_marker` NULL) 둘은 받는다. `backend/src/test/java/com/bifos/assistant/memory/MemorySourceUniqueMigrationTest.java` 의 데이터베이스 준비를 따른다.

`test/e2e/scenarios/follow-up.ts` 를 새로 만들고 `test/e2e/run.ts` 의 `SCENARIOS` 에서 `chatScenario` 뒤에 넣는다.

| 단계 | 기대 |
| --- | --- |
| 아빠가 `/chat/messages` 로 대화 하나를 만들고 그 `conversationId` 로 `POST /follow-ups` | 200, `OPEN`, `conversationId` 가 같다 |
| `PATCH` 로 `dueAt` 을 넣고, 다시 `dueAt: null` 로 보낸다 | 넣은 값, 그다음 `null` |
| 아이가 `GET /follow-ups` | 아빠의 줄이 없다 |
| 아이가 아빠의 `id` 로 `POST /follow-ups/{id}/done` | 404 |
| 아빠가 `done` 하고 다시 `done` | 200, 그다음 409 |

## 검증

```bash
# cwd: backend/
./gradlew test --tests 'com.bifos.assistant.followup.*' --tests 'com.bifos.assistant.architecture.*'
./gradlew test
./gradlew checkstyleMain checkstyleTest
./gradlew spotlessCheck
```

```bash
# cwd: 저장소 root
scripts/check-mysql-migration.sh
node test/e2e/run.ts
node --test 'test/unit/**/*.test.ts'
scripts/check-public-safe.sh
scripts/quality.sh check
```

`scripts/check-mysql-migration.sh` 는 `RepositoryQueryMysqlTest` 로 새 저장소 메서드를 실제 MySQL 에서 돌리고, `MysqlMigrationTest` 로 새 표의 정렬 규칙을 본다.
`test/unit/migration-collation.test.ts` 가 새 마이그레이션의 `COLLATE` 구절을 본다.
`git grep -n "log\.\(info\|warn\|debug\).*title" -- backend/src/main/java/com/bifos/assistant/followup` 의 결과가 비어야 한다.

## 변경 파일

| 파일 | 변경 |
| --- | --- |
| `backend/src/main/resources/db/migration/V68__follow_up.sql` | 신규 |
| `backend/src/main/java/com/bifos/assistant/followup/domain/type/FollowUpStatus.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/followup/domain/FollowUp.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/followup/infra/FollowUpRepository.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/followup/application/FollowUpService.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/followup/presentation/FollowUpController.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/followup/presentation/FollowUpDtos.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/shared/error/ErrorCode.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/architecture/TopLevelPackageOrder.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/architecture/TopLevelPackageOrderTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/followup/FollowUpServiceTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/followup/FollowUpMigrationTest.java` | 신규 |
| `test/e2e/scenarios/follow-up.ts` | 신규 |
| `test/e2e/run.ts` | 수정 |
| `docs/backend/packages.md` | 수정 |
| `docs/backend/schema/attention.md` | 수정 |
| `docs/backend/schema/README.md` | 수정 |
