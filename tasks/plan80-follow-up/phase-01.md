# Phase 01. 할 일 표와 사람이 쓰는 API

**Execution profile**: deep

## 목표

사용자 한 사람의 할 일을 저장하고, 사람이 웹 JWT 로 더하고 받아들이고 고치고 끝내는 API 를 연다.
먼저 알리기(phase 02)와 에이전트가 제안하는 도구(phase 03)가 이 표와 서비스 위에 선다.

**범위 외**: 지금 화면의 판정(phase 02). MCP 도구 `follow_up_propose` 와 제안 억제(phase 03). 화면(plan81).

## 컨텍스트

- 할 일의 뜻, 상태 전이, API 는 `docs/backend/follow-up.md` 가 갖는다. 표의 칸과 유일 제약은 `docs/backend/schema/attention.md` 의 「follow_up」 이 갖는다. 이 phase 는 그 두 문서를 그대로 구현한다
- 결정은 `docs/adr/ADR-073-할-일은-에이전트가-제안하고-사람이-받아들인-것만-챙긴다.md` 다
- 새 최상위 패키지 `followup` 의 자리는 실제 의존으로 정한다. `followup` 은 `chat` 을 쓰고(대화 주인 판정, 공개 식별자), `mcp`(phase 03)와 `attention`(phase 02)이 `followup` 을 쓴다. 그래서 `chat` 바로 위, `orchestration` 아래다. `attention` 보다 아래이고 `mcp` 보다 아래다. 층 순서는 `backend/src/test/java/com/bifos/assistant/architecture/TopLevelPackageOrder.java` 의 `ORDER` 가 갖고 `docs/backend/packages.md` 의 「최상위 패키지의 층 순서」 가 그것을 옮겨 적는다(ADR-068)
- 이 phase 를 시작할 때의 층 순서는 plan79 phase 01 뒤의 16개다. `"hermes"`, `"user"`, `"notification"`, `"model"`, `"agent"`, `"skill"`, `"usage"`, `"memory"`, `"context"`, `"chat"`, `"orchestration"`, `"mcp"`, `"people"`, `"connector"`, `"task"`, `"attention"`. `TopLevelPackageOrderTest` 는 `allowsTopPackageToUseEveryOtherPackage` 의 `hasSize(15)` 와 `violations("attention", others)`, `orderHasSixteenDistinctPackages`(`@DisplayName("층 순서는 겹치는 이름 없이 열여섯이다")`, `hasSize(16)` 둘)를 갖는다
- `followup` 이 쓰는 `chat` 의 클래스는 둘이다. 둘 다 `backend/src/main/java/com/bifos/assistant/chat/application/` 에 있다
  - `ConversationAccess.requireOwnId(CurrentUser, UUID)` 는 남의 대화와 없는 대화를 같은 404 `CONVERSATION_NOT_FOUND` 로 답하고 대화 번호를 돌려준다
  - `ConversationPublicIdLookup.activePublicIdsOf(Collection<Long>)` 는 대화 번호를 공개 식별자로 바꾸고 지운 대화를 뺀다. 빈 번호면 읽지 않는다
- 따라 할 패턴
  - 컨트롤러와 현재 사용자: `backend/src/main/java/com/bifos/assistant/memory/presentation/MemoryController.java`(`CurrentUserProvider currentUser`, `currentUser.require()`)
  - 공개 식별자 칸과 유일 제약 선언: `backend/src/main/java/com/bifos/assistant/connector/domain/ConnectorAction.java` 의 `@Table(name = "connector_action", uniqueConstraints = { @UniqueConstraint(name = "uk_connector_action_dedupe_key", columnNames = "dedupe_key"), @UniqueConstraint(name = "uk_connector_action_public_id", columnNames = "public_id") })` 와 `publicId`(`@UuidGenerator(style = UuidGenerator.Style.VERSION_7)`, `@JdbcTypeCode(SqlTypes.BINARY)`, `columnDefinition = "BINARY(16)"`). 테스트 스키마는 엔티티로 만든다(`backend/src/test/resources/application-test.yml` 의 `ddl-auto: create-drop`). 유일 제약을 엔티티에 선언하지 않으면 H2 위의 `@SpringBootTest` 에 제약이 없어 경합 처리 경로가 돌지 않는다
  - 상태 전이: `ConnectorAction` 의 전이 메서드는 `void` 이고 private `require(ActionStatus expected)` 가 틀린 상태면 `IllegalStateException` 을 던진다. 서비스(`ConnectorActionService` 의 private `requirePending`)가 먼저 상태를 보고 `ApiException` 으로 답한다. 할 일도 이 모양을 따른다
  - 저장만 트랜잭션 안에서 하고 유일 제약 충돌은 밖에서 처리: `backend/src/main/java/com/bifos/assistant/connector/application/ConnectorPolicyService.java` 225-245줄. 생성자에서 `new TransactionTemplate(transactionManager)` 를 만들고, `transactions.execute(status -> repository.saveAndFlush(…))` 를 `try` 로 감싸 `DataIntegrityViolationException` 을 밖에서 잡아 다시 읽는다. 메서드에 `@Transactional` 을 붙이지 않는다. 붙이면 예외를 잡고 정상 반환해도 트랜잭션이 rollback-only 로 남아 커밋할 때 `UnexpectedRollbackException` 이 나고 500 이 된다
  - 요청 글의 형식 오류: `backend/src/main/java/com/bifos/assistant/usage/presentation/UsageController.java` 의 private `parseMonth` 가 `DateTimeParseException` 을 잡아 `ApiException(ErrorCode.VALIDATION_FAILED, "month must look like 2026-09", ex)` 로 바꾼다. 요청 본문의 Jackson 변환 오류는 Control Plane 에서 500 이므로(`docs/backend/packages.md` 의 「경로 변수와 요청 인자의 형식이 틀리면」 문단), 시각과 UUID 는 글로 받아 컨트롤러가 읽는다
  - 엔티티 모양: `backend/src/main/java/com/bifos/assistant/memory/domain/Memory.java`(`@Getter`, `@Accessors(fluent = true)`, `@NoArgsConstructor(access = AccessLevel.PROTECTED)`)
  - 지문: `com.bifos.assistant.shared.util.Sha256.hex(String)` 가 SHA-256 소문자 16진수 64자를 낸다
  - 오류: `backend/src/main/java/com/bifos/assistant/shared/error/ErrorCode.java` 에 값을 더하고 `ApiException(ErrorCode, String)` 으로 던진다
  - JSON 본문을 노드로 받는 컨트롤러: `backend/src/main/java/com/bifos/assistant/mcp/presentation/McpController.java` 의 `@RequestBody JsonNode request`(`tools.jackson.databind.JsonNode`)
- 저장소 쿼리는 `RepositoryQueryMysqlTest` 가 실제 MySQL 에서 모두 한 번씩 돌린다. 인자가 `Long`, `UUID`, `Instant`, `Integer`, enum, `Collection` 이면 `RepositoryQuerySweep` 이 이미 만든다
- `open_marker` 의 Java 타입: 운영과 `MysqlMigrationTest` 는 `ddl-auto: validate` 다. Hibernate 의 검증은 `columnDefinition` 글이 데이터베이스의 형 이름으로 시작하면 맞다고 본다. 그래서 `@Column(name = "open_marker", columnDefinition = "TINYINT") private Integer openMarker;` 로 둔다. 이 저장소에 TINYINT 칸의 선례는 없다

**근거 문서**: `docs/backend/follow-up.md` 의 「상태」, 「API」, 「지키는 것」, `docs/backend/schema/attention.md` 의 「follow_up」, `docs/adr/ADR-073-할-일은-에이전트가-제안하고-사람이-받아들인-것만-챙긴다.md`, `docs/backend/packages.md`

## 의도 메모

- 할 일을 Memory 의 한 종류로 두지 않는다. 상태와 기한이 있고 대화마다 실리면 안 된다(ADR-073 의 대안 기각)
- `open_marker` 는 열린 줄만 1 이고 끝난 줄은 NULL 이다. MySQL 과 H2 모두 유일 제약에서 NULL 을 서로 다르다고 본다. 그래서 끝난 할 일과 같은 제목을 다시 열 수 있다
- 사람이 직접 더한 줄은 `proposed_by_execution_id` 가 비고 바로 `OPEN` 이며 `accepted_at` 을 만든 시각으로 적는다
- 같은 열린 제목을 사람이 다시 더하면 새 줄을 만들지 않고 있는 줄을 돌려준다. 그 줄이 `PROPOSED` 면 받아들인 것으로 보고 `OPEN` 으로 바꾼다. 유일 제약 위반을 500 으로 내지 않는다. 동시에 들어온 두 요청은 제약이 하나만 남기고, 진 쪽은 트랜잭션 밖에서 다시 읽어 그 줄을 돌려준다
- `title_key` 는 phase 03 의 제안 억제도 쓴다. 정규화 함수를 서비스 하나에 두어 두 경로가 같은 값을 쓰게 한다
- 대화를 지운 할 일은 `conversationId` 를 `null` 로 낸다. 눌러도 갈 곳이 없기 때문이다. 줄 자체는 남는다(`docs/backend/schema/attention.md` 「follow_up」 의 「대화를 지워도 줄은 남는다」)

## 작업 항목

### 1. `backend/src/main/resources/db/migration/V72__follow_up.sql`

머지 직전 main 과 번호가 겹치면 다음 번호로 옮기고 변경 파일 표도 같은 커밋에서 고친다.
`docs/backend/schema/attention.md` 의 「follow_up」 칸을 그대로 만든다.

- `id BIGINT NOT NULL AUTO_INCREMENT`, `public_id BINARY(16) NOT NULL`, `user_id BIGINT NOT NULL`, `conversation_id BIGINT NULL`, `proposed_by_execution_id BIGINT NULL`, `title VARCHAR(200) NOT NULL`, `title_key CHAR(64) NOT NULL`, `due_at DATETIME(6) NULL`, `waiting BOOLEAN NOT NULL DEFAULT FALSE`, `status VARCHAR(20) NOT NULL`, `open_marker TINYINT NULL`, `created_at DATETIME(6) NOT NULL`, `updated_at DATETIME(6) NOT NULL`, `accepted_at DATETIME(6) NULL`, `closed_at DATETIME(6) NULL`, `PRIMARY KEY (id)`
- `UNIQUE KEY uk_follow_up_public_id (public_id)`, `UNIQUE KEY uk_follow_up_open_title (user_id, title_key, open_marker)`
- `CONSTRAINT fk_follow_up_user FOREIGN KEY (user_id) REFERENCES app_user(id)`. 대화와 실행에는 외래 키를 걸지 않는다(`V46__connector_action.sql` 과 같다)
- `ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci`
- 색인 `CREATE INDEX idx_follow_up_user_status ON follow_up (user_id, status)`, `CREATE INDEX idx_follow_up_conversation_status ON follow_up (conversation_id, status)`

### 2. `followup` 패키지

| 파일 | 내용 |
| --- | --- |
| `backend/src/main/java/com/bifos/assistant/followup/domain/type/FollowUpStatus.java` | `PROPOSED`, `OPEN`, `DONE`, `DROPPED`, `REJECTED`. `boolean open()` 은 `PROPOSED` 나 `OPEN` 이면 참 |
| `backend/src/main/java/com/bifos/assistant/followup/domain/FollowUp.java` | 엔티티. `@Table(name = "follow_up", uniqueConstraints = { @UniqueConstraint(name = "uk_follow_up_public_id", columnNames = "public_id"), @UniqueConstraint(name = "uk_follow_up_open_title", columnNames = {"user_id", "title_key", "open_marker"}) })`. 위 칸. `status` 는 `@Enumerated(EnumType.STRING)`, `openMarker` 는 컨텍스트의 `Integer` 와 `columnDefinition = "TINYINT"`. 정적 생성자 `proposed(Long userId, Long conversationId, Long executionId, String title, String titleKey, Instant dueAt, boolean waiting, Instant now)` 와 `opened(Long userId, Long conversationId, String title, String titleKey, Instant dueAt, boolean waiting, Instant now)`. 전이 메서드 `void accept(Instant now)`(`PROPOSED` 만), `void reject(Instant now)`(`PROPOSED` 만), `void done(Instant now)`(`OPEN` 만), `void drop(Instant now)`(`OPEN` 만), `void revise(String title, String titleKey, Instant dueAt, boolean waiting, Instant now)`(`PROPOSED` 나 `OPEN` 만). 틀린 상태면 private `require(...)` 가 `IllegalStateException` 을 던진다. 끝난 세 상태로 갈 때 `openMarker` 를 `null` 로, `closedAt` 을 `now` 로 둔다. 모든 전이가 `updatedAt` 을 `now` 로 둔다 |
| `backend/src/main/java/com/bifos/assistant/followup/infra/FollowUpRepository.java` | `Optional<FollowUp> findByPublicIdAndUserId(UUID publicId, Long userId)`, `List<FollowUp> findByUserIdAndStatusInOrderByIdAsc(Long userId, Collection<FollowUpStatus> statuses)`, `Optional<FollowUp> findByUserIdAndTitleKeyAndOpenMarker(Long userId, String titleKey, Integer openMarker)` |
| `backend/src/main/java/com/bifos/assistant/followup/application/model/FollowUpSnapshot.java` | 서비스가 돌려주는 값. `FollowUpSnapshot(UUID publicId, Long conversationId, UUID conversationPublicId, String title, Instant dueAt, boolean waiting, FollowUpStatus status, boolean proposed, Instant createdAt, Instant updatedAt, Instant acceptedAt, Instant closedAt)`. `conversationPublicId` 는 `activePublicIdsOf` 로 바꾼 값이고 대화가 없거나 지워졌으면 `null` 이다. `proposed` 는 `proposed_by_execution_id` 가 있으면 참이다. `toString` 을 고쳐 `title` 을 빼고 `publicId` 와 `status` 만 낸다(로그에 제목을 남기지 않는다) |
| `backend/src/main/java/com/bifos/assistant/followup/application/model/NewFollowUp.java` | 만들기 입력. `NewFollowUp(String title, Instant dueAt, boolean waiting, UUID conversationId)` |
| `backend/src/main/java/com/bifos/assistant/followup/application/model/FollowUpPatch.java` | 고치기 입력. `FollowUpPatch(String title, boolean dueAtPresent, Instant dueAt, Boolean waiting)`. `title` 과 `waiting` 은 `null` 이면 그대로 둔다. `dueAtPresent` 가 거짓이면 기한을 그대로 두고, 참이면 `dueAt` 으로 바꾼다(`null` 이면 지운다) |
| `backend/src/main/java/com/bifos/assistant/followup/application/FollowUpService.java` | 아래 「서비스」 |
| `backend/src/main/java/com/bifos/assistant/followup/presentation/FollowUpController.java` | `@RequestMapping("/api/v1/follow-ups")`. 경로, 권한, 요청 읽기, 응답 만들기만 둔다. 아래 「컨트롤러」 |
| `backend/src/main/java/com/bifos/assistant/followup/presentation/FollowUpDtos.java` | `CreateFollowUpRequest(String title, String dueAt, Boolean waiting, String conversationId)`, `FollowUpView(UUID id, String title, String status, Instant dueAt, boolean waiting, UUID conversationId, boolean proposed, Instant createdAt, Instant acceptedAt, Instant closedAt)` 와 `static FollowUpView from(FollowUpSnapshot snapshot)` |

**서비스** `FollowUpService`(`@Service`). 쓰는 메서드에 `@Transactional` 을 붙이지 않고 생성자에서 만든 `TransactionTemplate` 안에서 읽고 저장한다. `list` 만 `@Transactional(readOnly = true)` 다.

- `static String titleKey(String title)`: `Normalizer.normalize(title, Normalizer.Form.NFC)` 뒤 `strip()`, 연속 공백(`\\s+`)을 한 칸으로, `toLowerCase(Locale.ROOT)`, 그 글의 `Sha256.hex`. phase 03 도 이 함수를 쓴다
- `List<FollowUpSnapshot> list(CurrentUser user)`: `PROPOSED` 와 `OPEN` 을 만든 순서로
- `FollowUpSnapshot create(CurrentUser user, NewFollowUp input)`: 제목을 `strip()` 해 1자부터 200자가 아니면 400 `ApiException(VALIDATION_FAILED, "title must be 1 to 200 characters")`. `conversationId` 가 있으면 `ConversationAccess.requireOwnId` 로 번호를 얻는다. 트랜잭션 안에서 같은 `titleKey` 의 열린 줄(`openMarker` 1)을 찾는다. 그 줄이 `PROPOSED` 면 `accept` 하고, `OPEN` 이면 그대로 돌려준다. 없으면 `FollowUp.opened(...)` 를 `saveAndFlush` 한다. 저장이 `DataIntegrityViolationException` 이면 트랜잭션 밖에서 잡아, 새 트랜잭션에서 같은 열린 줄을 다시 읽어 위와 같이 처리해 돌려준다
- `FollowUpSnapshot update(CurrentUser user, UUID id, FollowUpPatch patch)`: 줄이 `PROPOSED` 나 `OPEN` 이 아니면 409. 제목을 바꾸면 위와 같은 길이 검사를 하고 `titleKey` 도 다시 계산한다. 바꾼 `titleKey` 의 다른 열린 줄이 있으면 409 `FOLLOW_UP_STATE_CONFLICT`. 저장이 `DataIntegrityViolationException` 이면(동시에 같은 제목이 열렸다) 409 `FOLLOW_UP_STATE_CONFLICT`
- `accept`, `reject`, `done`, `drop(CurrentUser user, UUID id)` → `FollowUpSnapshot`: 서비스가 먼저 상태를 보고 `docs/backend/follow-up.md` 「상태」 표의 전이가 아니면 409 `ApiException(FOLLOW_UP_STATE_CONFLICT, "follow-up is not in a state for this action")`. 맞으면 전이 메서드를 부른다
- 남의 줄과 없는 줄은 404 `ApiException(FOLLOW_UP_NOT_FOUND, "no such follow-up")`
- 로그는 `log.info("follow-up {} userId={} followUpId={}", 동작, userId, publicId)` 모양으로 번호만 남긴다. 제목을 넘기지 않는다

**컨트롤러** `FollowUpController`.

- `GET` → `List<FollowUpView>`
- `POST` 의 본문은 `CreateFollowUpRequest`. `dueAt` 은 private `parseInstant(String)` 이 `OffsetDateTime.parse(text).toInstant()` 로 읽는다(`2026-10-05T09:00:00Z`, `2026-10-05T18:00:00+09:00`). 못 읽으면 400 `ApiException(VALIDATION_FAILED, "dueAt must be an ISO-8601 instant with offset")`. `conversationId` 는 `UUID.fromString` 으로 읽고 못 읽으면 400 `ApiException(VALIDATION_FAILED, "conversationId must be a UUID")`. `waiting` 이 `null` 이면 거짓이다. 그 값으로 `NewFollowUp` 을 만들어 넘긴다
- `PATCH /{id}` 는 본문을 `JsonNode` 로 받는다. 객체가 아니면 400. `title` 키가 있고 값이 `null` 이 아니면 문자열이어야 한다. `waiting` 은 같은 규칙으로 참거짓이어야 한다. `dueAt` 키가 있으면 `dueAtPresent` 가 참이고, 값이 `null` 이면 `dueAt` 도 `null`, 문자열이면 `parseInstant` 로 읽는다. 타입이 틀리면 400 `VALIDATION_FAILED`. 그 밖의 키는 읽지 않는다. 그 값으로 `FollowUpPatch` 를 만들어 넘긴다
- `POST /{id}/accept`, `/reject`, `/done`, `/drop` → `FollowUpView`. 경로의 `{id}` 는 `UUID` 다. 형식이 틀리면 `GlobalExceptionHandler` 가 400 으로 답한다
- 응답은 모두 서비스가 돌려준 `FollowUpSnapshot` 으로 컨트롤러가 `FollowUpView.from` 을 불러 만든다

### 3. `ErrorCode`

`backend/src/main/java/com/bifos/assistant/shared/error/ErrorCode.java` 에 `FOLLOW_UP_NOT_FOUND(HttpStatus.NOT_FOUND)`, `FOLLOW_UP_STATE_CONFLICT(HttpStatus.CONFLICT)` 를 더한다.

### 4. 층 순서

- `TopLevelPackageOrder.ORDER` 에서 `"chat"` 바로 뒤에 `"followup"` 을 넣는다. 17개가 된다
- `backend/src/test/java/com/bifos/assistant/architecture/TopLevelPackageOrderTest.java`:
  - `allowsTopPackageToUseEveryOtherPackage` 의 `hasSize(15)` 를 `hasSize(16)` 으로 고친다. `violations("attention", others)` 는 그대로다
  - `orderHasSixteenDistinctPackages` 를 `orderHasSeventeenDistinctPackages` 로 이름을 바꾸고, `@DisplayName` 을 「층 순서는 겹치는 이름 없이 열일곱이다」 로, 두 `hasSize(16)` 을 `hasSize(17)` 로 고친다
- `docs/backend/packages.md`:
  - 「패키지와 책임」 표에 `| \`followup\` | 할 일의 저장과 상태 전이, 사람이 쓰는 API, 에이전트의 제안 저장([\`follow-up.md\`](follow-up.md)) |` 를 `notification` 줄 뒤에 더한다
  - 「최상위 패키지의 층 순서」 의 「자리 1 이 맨 아래이고 16 이 맨 위다.」 를 「자리 1 이 맨 아래이고 17 이 맨 위다.」 로 고친다. 표의 `| 10 | \`chat\` |` 다음에 `| 11 | \`followup\` |` 를 넣고, 뒤의 `orchestration` 부터 `attention` 까지 자리 번호를 12 부터 17 까지 하나씩 민다
  - 그 절의 설명 문단에 「`followup` 은 `chat` 바로 위다. 대화 주인을 확인하고 공개 식별자를 얻으려고 `chat` 을 쓰고, 제안 도구(`mcp`)와 먼저 알리기(`attention`)가 `followup` 을 쓴다.」 를 더한다

### 5. 문서

- `docs/backend/schema/attention.md`: 「follow_up」 절 바로 아래의 「**아직 구현 전이다.**」 줄을 지운다. 머리의 「`follow_up` 은 아직 마이그레이션이 없다. 표를 만든 PR 이 그 절의 「아직 구현 전이다」 줄과 이 문장을 지운다.」 문장도 지운다
- `docs/backend/schema/README.md`: `attention.md` 줄 끝의 「`follow_up` 은 아직 구현 전이다」 를 지운다
- `docs/backend/follow-up.md` 「API」 의 경로 표 아래 「응답의 칸은 …」 문단 앞에 아래를 더한다

> `title` 이 비었거나 200자를 넘으면 400 `VALIDATION_FAILED` 다. 앞뒤 공백을 지운 길이로 센다.
> `dueAt` 은 시간대가 붙은 ISO-8601 시각(`2026-10-05T09:00:00Z`)이다. 읽지 못하면 400 `VALIDATION_FAILED` 다.
> 본문의 `conversationId` 가 UUID 가 아니면 400 `VALIDATION_FAILED` 다. `PATCH` 의 `title` 과 `waiting` 은 키가 없거나 `null` 이면 그대로 둔다.

### 6. 이 phase 를 검증하는 테스트

`backend/src/test/java/com/bifos/assistant/followup/FollowUpServiceTest.java`(`@SpringBootTest`, `@ActiveProfiles("test")`)

| 입력 | 기대 |
| --- | --- |
| `titleKey("  할 일   검사 ")` 와 `titleKey("할 일 검사")`, `titleKey("ABC")` 와 `titleKey("abc")` | 각각 같다. 64자 16진수다 |
| 아빠가 제목 `할 일 검사 7391` 로 `create` | `OPEN`, `acceptedAt` 이 채워지고 `proposed` 가 거짓 |
| 같은 제목에 공백만 다르게 다시 `create` | 새 줄 없이 같은 `publicId` |
| `done` 뒤 같은 제목으로 `create` | 새 `OPEN` 줄. 앞 줄은 `DONE` 이고 `closedAt` 이 있다 |
| `FollowUp.proposed(...)` 로 저장한 `PROPOSED` 와 같은 제목으로 `create` | 새 줄 없이 같은 `publicId`, `status` 가 `OPEN`, `acceptedAt` 이 있다 |
| `FollowUpRepository` 로 같은 사용자, 같은 `titleKey`, `openMarker` 1 인 줄을 하나 더 `saveAndFlush` | `DataIntegrityViolationException`. 엔티티의 유일 제약이 H2 스키마에 있다 |
| `OPEN` 에 `accept` | 409 `FOLLOW_UP_STATE_CONFLICT` |
| `DONE` 에 `update` | 409 `FOLLOW_UP_STATE_CONFLICT` |
| 열린 줄 둘 가운데 하나의 제목을 다른 줄의 제목으로 `update` | 409 `FOLLOW_UP_STATE_CONFLICT` |
| `FollowUpPatch(null, false, null, null)` 로 기한이 있는 줄을 `update` | 기한이 그대로다. `FollowUpPatch(null, true, null, null)` 이면 기한이 지워진다 |
| 아이가 아빠의 `publicId` 로 `done` | 404 `FOLLOW_UP_NOT_FOUND` |
| 아빠가 아이의 대화 공개 식별자로 `create` | 404 `CONVERSATION_NOT_FOUND` |
| 제목이 공백뿐이거나 201자 | 400 `VALIDATION_FAILED` |
| 연결한 대화를 지운 뒤 `list` | 그 줄의 `conversationPublicId` 가 `null` 이다 |

`backend/src/test/java/com/bifos/assistant/followup/FollowUpMigrationTest.java`: H2 에서 Flyway 를 끝까지 적용하고 `uk_follow_up_open_title` 이 열린 같은 제목 둘을 막고 끝난 줄(`open_marker` NULL) 둘은 받는다. `backend/src/test/java/com/bifos/assistant/memory/MemorySourceUniqueMigrationTest.java` 의 데이터베이스 준비를 따른다.

`test/e2e/scenarios/follow-up.ts` 를 새로 만들고 `test/e2e/run.ts` 의 `SCENARIOS` 에서 `chatScenario` 뒤에 넣는다. **이 시나리오는 turn 을 돌리지 않는다.** 바로 뒤의 `usageCostScenario` 가 실행 수를 `COMPLETED_TURNS` 와 정확히 같은지 단언하기 때문이다(`test/e2e/scenarios/usage-cost.ts` 62줄과 100줄). 그래서 `usage-cost.ts` 는 고치지 않는다.

| 단계 | 기대 |
| --- | --- |
| 아빠가 `GET /chat/conversations` 의 첫 줄(`chatScenario` 가 만든 대화)의 `id` 로 `POST /follow-ups` | 200, `OPEN`, `conversationId` 가 같다 |
| `PATCH` 로 `dueAt` 을 넣고, `title` 만 보내고, 다시 `dueAt: null` 로 보낸다 | 넣은 값, 그대로인 값, 그다음 `null` |
| `dueAt` 이 `"내일"` 인 `POST /follow-ups` | 400 `VALIDATION_FAILED` |
| `title` 이 공백뿐인 `POST /follow-ups` | 400 `VALIDATION_FAILED` |
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
! git grep -n "log\.\(info\|warn\|debug\).*title" -- backend/src/main/java/com/bifos/assistant/followup
```

기대값: 명령이 모두 종료 코드 0 이다.
`scripts/check-mysql-migration.sh` 는 `RepositoryQueryMysqlTest` 로 새 저장소 메서드를 실제 MySQL 에서 돌리고, `MysqlMigrationTest` 로 새 표의 정렬 규칙과 `ddl-auto: validate`(`open_marker` 의 `TINYINT` 포함)를 본다.
`test/unit/migration-collation.test.ts` 가 새 마이그레이션의 `COLLATE` 구절을 본다.

## 변경 파일

| 파일 | 변경 |
| --- | --- |
| `backend/src/main/resources/db/migration/V72__follow_up.sql` | 신규 |
| `backend/src/main/java/com/bifos/assistant/followup/domain/type/FollowUpStatus.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/followup/domain/FollowUp.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/followup/infra/FollowUpRepository.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/followup/application/model/FollowUpSnapshot.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/followup/application/model/NewFollowUp.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/followup/application/model/FollowUpPatch.java` | 신규 |
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
| `docs/backend/follow-up.md` | 수정 |
| `docs/backend/schema/attention.md` | 수정 |
| `docs/backend/schema/README.md` | 수정 |
