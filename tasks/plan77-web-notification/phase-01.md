# Phase 01. 알림 표와 사용자 단위 SSE, 승인 요청과 만료 알림

**Execution profile**: deep

## 목표

Control Plane 에 `notification` 패키지를 만든다. 승인이 필요한 커넥터 호출이 새 승인 줄을 만들 때와 그 줄이 만료될 때 알림 줄을 같은 트랜잭션에 남기고, 커밋 뒤 사용자 단위 SSE 로 알린다.
목록, 읽음 표시, 모두 읽음, SSE 경로를 연다. 사용자가 다른 화면에 있어도 승인 요청을 알게 하려는 것이다.

**범위 외**: 웹 화면과 서버 라우트(phase 02). 예약 작업 알림(다음 plan). 커넥터 재연결 알림과 웹 푸시(아직 만들지 않는다).

## 컨텍스트

**근거 문서**: `docs/backend/notification.md` 전체, `docs/backend/schema/notification.md`, `docs/adr/ADR-070-알림은-control-plane-의-notification-표가-원장이고-웹은-사용자-단위-SSE-로-받는다.md`, `docs/backend/packages.md` 의 「최상위 패키지의 층 순서」, `docs/backend/connector-tool-policy.md` 의 「승인이 필요한 호출」.

경로는 모두 `backend/src/main/java/com/bifos/assistant/` 아래를 `B/` 로 줄여 쓴다.

따를 기존 패턴:

- 엔티티와 공개 식별자: `B/connector/domain/ConnectorAction.java` 의 `public_id`(`@UuidGenerator(style = VERSION_7)`, `@JdbcTypeCode(SqlTypes.BINARY)`, `updatable = false`).
- 대화 단위 허브: `B/chat/application/ConversationEventHub.java`(`subscribe` 가 해제용 `Runnable` 을 돌려주고, `publish` 가 예외 난 구독자를 뺀다). 사용자 단위 허브는 같은 모양에 키만 사용자 번호다.
- SSE 경로: `B/chat/presentation/ChatEventStreams.java` 의 `follow` 와 `B/chat/presentation/ConversationEventController.java`. `new SseEmitter(0L)`, 열자마자 주석 `connected`, 가상 스레드가 heartbeat 마다 주석 `ping`, `onCompletion`·`onTimeout`·`onError` 에서 구독 해제. `notification` 은 층 순서에서 `chat` 아래라 `ChatEventStreams` 를 import 할 수 없다. 같은 동작을 `notification.presentation` 에 따로 둔다.
- 커서 페이지: `ChatService.conversationsOf(CurrentUser, String cursor, int limit)` 와 `ChatDtos.ConversationPageView(items, nextCursor)`.
- 예약 정리: `B/chat/application/AttachmentCleaner.java`(`@Scheduled(cron = "${...}")`, `Clock` 주입, `runScheduled()` 가 `cleanExpired(clock.instant())` 를 부른다). test yml 은 모든 cron 을 `"-"` 로 둔다.
- 현재 사용자: `B/shared/auth/CurrentUserProvider` 의 `require()`. 오류는 `new ApiException(ErrorCode, message)`, `ErrorCode` 는 `B/shared/error/ErrorCode.java`.
- 시각 고정 테스트: `AttachmentCleanerClockTest` 의 `Clock.fixed(...)`.

승인 줄을 만드는 곳은 `B/connector/application/ConnectorPolicyService.java` 의 `decide(...)` 다.
`transactions.execute(status -> actions.saveAndFlush(action))` 한 줄이 저장하고, 커밋 뒤 `needsApproval && saved.conversationId() != null` 일 때 `ConnectorActionChanged` 사건을 낸다.
같은 실행의 같은 호출은 그 앞에서 기존 `PENDING` 줄을 찾아 재사용하므로 저장까지 오지 않는다. `DataIntegrityViolationException` 으로 먼저 저장된 줄을 돌려주는 경로도 새 줄이 아니다.

만료는 `B/connector/application/ConnectorActionService.java` 의 `public int expire(Instant now)` 다. 줄마다 `transactions.execute(... findByPublicIdForUpdate ... action.expire(now) ... actions.save(action))` 로 잠그고 바꾼 뒤 `publish(changed.get())` 한다.
`B/connector/application/ConnectorActionExpirer.java` 가 1분마다 부른다.

도구 제목은 승인 카드가 쓰는 제목과 같아야 한다. 카드의 제목은 `ConnectorActionService` 가 커넥터 선언의 도구 정책 제목(`ToolPolicy::title`)으로 채우고, 대화 알림 줄은 `ConnectorActionListener.closureNotice` 가 `closure.title()` 을 「」 로 감싼다.
`decide` 안에서는 판정에 쓴 선언 도구 정책을 이미 갖고 있다. 그 제목이 없으면 `hermesTool` 을 쓴다. 만료 쪽은 `ConnectorActionService` 가 카드 제목을 계산하는 방식을 그대로 쓴다. 구현 전에 두 곳에서 제목을 얻는 코드를 읽고 같은 값이 나오게 한다.

새 마이그레이션 번호는 `V66` 이다. 구현 전에 `ls backend/src/main/resources/db/migration | sort -V | tail -3` 과 `gh pr list --state open` 으로 다른 브랜치가 V66 을 쥐지 않았는지 확인한다. 쥐었으면 다음 빈 번호를 쓴다.
마이그레이션 규칙은 `docs/backend/schema/README.md` 의 「마이그레이션 작성 규칙」 이다. 새 표는 `ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci` 를 적는다. 엔티티와 마이그레이션의 칸 타입을 맞춘다(`backend/AGENTS.md` 의 「엔티티와 마이그레이션은 따로 논다」).

## 의도 메모

- 알림 줄을 커밋 뒤 사건 리스너에서 따로 만들지 않는다. 승인 줄은 있는데 알림이 없는 상태가 생긴다(ADR-070).
- SSE 사건에 본문을 싣지 않는다. 화면이 다시 읽는다. 승인 카드의 `approval` 사건과 같은 방식이다.
- 대화 단위 허브를 넓히지 않는다. 대화 사건은 양이 많고 보는 사람에 따라 가리는 규칙이 있다.
- 알림 SSE 에는 `ChatEvent` 를 쓰지 않는다. `notification.application` 에 `NotificationEvent` record 를 따로 둔다.

## 작업 항목

### 1. 마이그레이션 `backend/src/main/resources/db/migration/V66__notification.sql`

`docs/backend/schema/notification.md` 의 칸 그대로 `notification` 표를 만든다. DDL 만 담는다.

- `public_id` 유니크 제약, `user_id` 가 `app_user(id)` 를 가리키는 외래 키
- 색인 `(user_id, created_at, id)` 와 `(user_id, read_at)`

### 2. `B/notification/` 패키지

| 파일 | 내용 |
| --- | --- |
| `domain/Notification.java` | 엔티티. 정적 팩토리 `Notification.of(Long userId, NotificationKind kind, String title, String body, NotificationTarget target, Instant now)`. `markRead(Instant now)` 는 이미 읽었으면 바꾸지 않는다. 손 접근자는 Lombok 으로 만든다(Checkstyle `entityHandwrittenAccessor`) |
| `domain/type/NotificationKind.java` | `APPROVAL_REQUESTED`, `APPROVAL_EXPIRED`. `@Enumerated(EnumType.STRING)` 으로 저장한다 |
| `domain/type/NotificationTargetType.java` | `CONVERSATION` |
| `domain/NotificationTarget.java` | `record NotificationTarget(NotificationTargetType type, UUID publicId)`. 엔티티에는 두 칸으로 펼쳐 저장한다. 갈 곳이 없으면 `null` 을 넘기고 두 칸을 비운다 |
| `infra/NotificationRepository.java` | `findByPublicIdAndUserId`, 커서 페이지 조회(만든 시각 역순, 같은 시각이면 `id` 역순), `countByUserIdAndReadAtIsNull`, 사용자의 읽지 않은 줄을 한 번에 읽음으로 바꾸는 update 쿼리, `deleteByCreatedAtBefore` |
| `application/NotificationService.java` | 아래 표 |
| `application/NotificationEventHub.java` | 사용자 번호를 키로 하는 구독 허브. `Runnable subscribe(Long userId, Consumer<NotificationEvent>)`, `void publish(Long userId, NotificationEvent)` |
| `application/NotificationEvent.java` | `record NotificationEvent(String type, UUID notificationId, long unreadCount)`. 정적 팩토리 `created(UUID, long)`, `read(long)` |
| `application/NotificationCleaner.java` | `@Scheduled(cron = "${assistant.notification.cleanup-cron}")` 의 `runScheduled()`, 본체 `int cleanExpired(Instant now)` 가 `now - retention` 보다 오래된 줄을 지운다 |
| `application/NotificationProperties.java` | `@ConfigurationProperties("assistant.notification")` record. `retention`(기본 `90d`), `cleanupCron`, `streamHeartbeat`(기본 `20s`) |
| `presentation/NotificationController.java` | 아래 API. 경로와 권한과 흐름만 둔다 |
| `presentation/NotificationDtos.java` | `NotificationView`, `NotificationPageView`, `UnreadCountView` |
| `presentation/NotificationEventStreams.java` | 사용자 단위 SSE 를 여는 도우미. `ChatEventStreams.follow` 와 같은 동작 |

`NotificationService`:

| 메서드 | 동작 |
| --- | --- |
| `Notification notify(Long userId, NotificationKind kind, String title, String body, NotificationTarget target)` | 줄을 저장한다. **부르는 쪽의 트랜잭션에 참여한다**(`Propagation.MANDATORY`). 커밋 뒤 `TransactionSynchronization.afterCommit` 에서 `NotificationEvent.created` 를 그 사용자에게 publish 한다. 사건을 보내다 난 예외는 로그만 남긴다 |
| `NotificationPage page(CurrentUser user, String cursor, int limit)` | `limit` 은 1 이상 100 이하로 받는다. 범위 밖은 `VALIDATION_FAILED`. 읽지 않은 수를 함께 돌려준다 |
| `Notification markRead(CurrentUser user, UUID notificationId)` | 남의 것이나 없는 것은 `NOTIFICATION_NOT_FOUND`(404). 새로 읽음이 되면 커밋 뒤 `NotificationEvent.read` 를 publish 한다. 이미 읽은 줄은 그대로 돌려준다 |
| `long markAllRead(CurrentUser user)` | 읽지 않은 줄을 모두 읽음으로 바꾸고 커밋 뒤 `read(0)` 을 publish 한다. 0 을 돌려준다 |
| `long unreadCount(Long userId)` | |

`Clock` 빈을 생성자로 받는다(`B/shared/config/ClockConfig.java`).

### 3. API `NotificationController` (`@RequestMapping("/api/v1/notifications")`)

| 메서드 | 경로 | 응답 |
| --- | --- | --- |
| GET | `` (`?cursor=&limit=30`) | `NotificationPageView(List<NotificationView> items, String nextCursor, long unreadCount)` |
| POST | `/{notificationId}/read` | `NotificationView` |
| POST | `/read-all` | `UnreadCountView(long unreadCount)` |
| GET | `/events` (`produces = text/event-stream`) | 사용자 단위 SSE |

`NotificationView(UUID id, NotificationKind kind, String title, String body, NotificationTargetType targetType, UUID targetId, Instant createdAt, Instant readAt)`.
SSE 의 `data:` JSON 은 `{"type":"created","notificationId":"...","unreadCount":3}` 과 `{"type":"read","unreadCount":0}` 이다. `read` 사건은 `notificationId` 를 싣지 않는다.
`SecurityConfig` 는 바꾸지 않는다. `/api/v1/**` 는 이미 인증이 필요하고 SSE 의 비동기 디스패치는 이미 열려 있다.

### 4. `B/shared/error/ErrorCode.java`

`NOTIFICATION_NOT_FOUND(HttpStatus.NOT_FOUND)` 를 더한다.

### 5. 승인 요청과 만료에 알림을 붙인다

- `ConnectorPolicyService.decide`: 새 승인 줄을 `saveAndFlush` 하는 같은 `transactions.execute` 안에서, `needsApproval && saved.conversationId() != null` 이면 `notifications.notify(saved.userId(), APPROVAL_REQUESTED, "승인을 기다리는 요청이 있어요", "「" + 도구 제목 + "」", new NotificationTarget(CONVERSATION, 대화 공개 식별자))` 를 부른다.
  대화 공개 식별자는 `connector` 가 이미 쓰는 길로 읽는다. `B/chat/application/ConversationNotices.java` 의 `publicIdOf(Long)` 가 있다(`connector` 는 `chat` 위라 쓸 수 있다).
  재사용한 `PENDING` 줄과 유니크 충돌로 돌려받은 줄에서는 부르지 않는다.
- `ConnectorActionService.expire`: 줄을 잠그고 `action.expire(now)` 한 같은 트랜잭션 안에서, `conversationId` 가 있으면 `APPROVAL_EXPIRED` 를 「승인 요청이 만료됐어요」 와 「도구 제목」 으로 남긴다.
  승인하려다 이미 만료돼 `beginApproval` 이 바로 `expire` 하는 경로도 같은 알림을 남긴다. 그 경로를 찾아 같은 도우미를 부른다.
- 두 곳 모두 알림 저장이 실패하면 트랜잭션이 되돌아간다. 따로 잡지 않는다.

### 6. 층 순서와 설정

- `backend/src/test/java/com/bifos/assistant/architecture/TopLevelPackageOrder.java` 의 `ORDER` 에 `"notification"` 을 `"user"` 바로 뒤에 넣는다.
- `backend/src/main/resources/application.yml` 의 `assistant:` 아래에 `notification:` 을 더한다. `retention: 90d`, `cleanup-cron: "0 30 4 * * *"`, `stream-heartbeat: 20s`. 각 키 위에 한 줄 주석을 단다.
- `backend/src/test/resources/application-test.yml` 에 `assistant.notification.cleanup-cron: "-"` 를 더한다.

### 7. 이 phase 를 검증하는 backend 테스트

`backend/src/test/java/com/bifos/assistant/notification/` 아래. 메서드 이름은 영문 camelCase, `@DisplayName` 은 한국어다.

| 파일 | 확인하는 것 |
| --- | --- |
| `NotificationServiceTest.java` | 알림을 저장하면 커밋 뒤 그 사용자 구독자만 `created` 와 읽지 않은 수를 받는다. 트랜잭션이 되돌아가면 줄도 사건도 없다. 트랜잭션 밖에서 `notify` 를 부르면 실패한다. 커서 페이지가 만든 시각 역순이고 다음 쪽이 이어진다. `limit` 0 과 101 은 `VALIDATION_FAILED`. 남의 알림 읽음 표시는 `NOTIFICATION_NOT_FOUND`. 이미 읽은 줄을 다시 읽어도 `read_at` 이 바뀌지 않는다. 모두 읽음 뒤 읽지 않은 수가 0 이다 |
| `NotificationCleanerTest.java` | `Clock.fixed` 로 시각을 고정하고 보관 기간보다 오래된 줄만 지운다. 읽지 않은 오래된 줄도 지운다 |
| `NotificationControllerTest.java` | 목록, 읽음, 모두 읽음의 응답 모양. 형식이 틀린 `notificationId` 는 400 `VALIDATION_FAILED`. SSE 경로가 `text/event-stream` 으로 열리고 첫 주석 `connected` 를 보낸다(`ConversationEventControllerTest` 의 standaloneSetup 방식) |
| `ApprovalNotificationTest.java` | `ConnectorPolicyService.decide` 로 승인 줄을 만들면 `APPROVAL_REQUESTED` 가 하나 생기고 대상이 그 대화다. 같은 호출을 다시 판정하면 알림이 늘지 않는다. 대화 없는 실행의 승인 줄은 알림이 없다. `expire(now)` 가 줄을 만료하면 `APPROVAL_EXPIRED` 가 하나 생긴다. 이 파일은 `backend/src/test/java/com/bifos/assistant/connector/` 에 둔다. `ConnectorPolicyTestDoubles.java` 와 `ConnectorActionServiceTest.java` 의 준비 방식을 따른다 |

### 8. e2e 시나리오 `test/e2e/scenarios/notifications.ts`

`test/e2e/scenarios/connector-policy.ts` 가 승인 줄을 만드는 방식을 읽고 같은 방법으로 승인 요청을 하나 만든다.
그 사용자의 `GET /api/v1/notifications` 에 `APPROVAL_REQUESTED` 가 있고 `targetId` 가 그 대화의 공개 식별자인지 본다.
다른 사용자의 토큰으로는 그 알림을 읽음으로 표시하지 못한다(404).
`POST /api/v1/notifications/read-all` 뒤 `unreadCount` 가 0 이다.
`GET /api/v1/notifications/events` 를 열어 둔 채 승인 요청을 하나 더 만들면 `created` 사건을 받는다. SSE 는 `web/src/lib/stream.ts` 의 `readEventStream` 으로 읽는다(`test/e2e/scenarios/conversation-manage.ts` 참고).
`test/e2e/run.ts` 의 `SCENARIOS` 에서 `connector-policy` 시나리오 뒤에 넣는다. 앞 시나리오가 남긴 상태를 가정하지 말고 이 시나리오가 필요한 연결과 대화를 직접 만든다. 이미 있으면 그대로 쓴다.

### 9. 문서

구현이 아래 문서와 다르면 문서를 같은 커밋에서 고친다. 고치기 전에 계획 담당에게 알린다.
`docs/backend/notification.md`, `docs/backend/schema/notification.md`.

## 검증

```bash
# cwd: backend/
./gradlew test --tests 'com.bifos.assistant.notification.*' --tests 'com.bifos.assistant.connector.ApprovalNotificationTest' --tests 'com.bifos.assistant.connector.*'
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
```

기대값: 모두 종료 코드 0. `./gradlew test` 안의 `ArchitectureRules` 가 `notification` 의 층 순서를 통과한다. `RepositoryQueryMysqlTest` 가 새 저장소 메서드를 실제 MySQL 에서 실행한다.
`spotlessCheck` 가 실패하면 기능 커밋 뒤 `./gradlew spotlessApply` 결과를 따로 커밋한다(`backend/AGENTS.md` 의 「포맷」).

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/resources/db/migration/V66__notification.sql` | 신규 |
| `backend/src/main/java/com/bifos/assistant/notification/domain/Notification.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/notification/domain/NotificationTarget.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/notification/domain/type/NotificationKind.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/notification/domain/type/NotificationTargetType.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/notification/infra/NotificationRepository.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/notification/application/NotificationService.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/notification/application/NotificationEventHub.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/notification/application/NotificationEvent.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/notification/application/NotificationCleaner.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/notification/application/NotificationProperties.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/notification/presentation/NotificationController.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/notification/presentation/NotificationDtos.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/notification/presentation/NotificationEventStreams.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/shared/error/ErrorCode.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/connector/application/ConnectorPolicyService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/connector/application/ConnectorActionService.java` | 수정 |
| `backend/src/main/resources/application.yml` | 수정 |
| `backend/src/test/resources/application-test.yml` | 수정 |
| `backend/src/test/java/com/bifos/assistant/architecture/TopLevelPackageOrder.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/notification/NotificationServiceTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/notification/NotificationCleanerTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/notification/NotificationControllerTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/connector/ApprovalNotificationTest.java` | 신규 |
| `test/e2e/scenarios/notifications.ts` | 신규 |
| `test/e2e/run.ts` | 수정 |
| `docs/backend/notification.md` | 수정 |
| `docs/backend/schema/notification.md` | 수정 |
