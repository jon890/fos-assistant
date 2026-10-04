# Phase 02. 숨기기, 미루기, 지표 사건

**Execution profile**: deep

## 목표

사용자가 항목을 숨기고 미루고 되돌릴 수 있게 하고, 판정이 그 제어를 억제 신호로 쓰게 한다.
지금 화면에 보인 항목과 그 항목에 한 일을 지표 사건으로 남기고, 관리자가 `trigger` 별로 센 값을 읽게 한다.

**범위 외**: 화면의 메뉴와 「되돌리기」 줄(plan81), 결과 전달 실패(phase 03), 할 일(plan80).

## 컨텍스트

**근거 문서**: `docs/backend/attention.md` 의 「억제 신호」, 「API」, 「저장」, 「지표」, 「기준값」. `docs/backend/schema/attention.md` 의 「attention_control」, 「attention_event」. `docs/backend/schema/README.md` 의 「마이그레이션 작성 규칙」.

phase 01 이 만든 것을 쓴다. 클래스 경로는 모두 `backend/src/main/java/com/bifos/assistant/` 아래다.

| 무엇 | 어디 |
| --- | --- |
| 판정 | `attention.application.AttentionJudge.judge(..., List<AttentionControl> controls, ...)`. phase 01 의 `AttentionService.view` 는 빈 제어를 넘긴다 |
| 제어 값 | `attention.application.model.AttentionControl(CardKey card, String itemKey, String stateKey, Instant snoozedUntil)` |
| 저장하는 enum | `attention.domain.type.AttentionTrigger`, `AttentionLevel`(`NOW`, `LATER`, `SUPPRESSED`), `CardKey`(`FAILURES`, `NEEDS_ME`, `DELEGATED`, `CONTINUE`) |
| 카드 열쇠의 글 | `attention.presentation.AttentionDtos.cardKeyText(CardKey)` 가 소문자 글을 낸다 |
| 후보 목록 | `AttentionService` 가 모든 `AttentionCandidates` 를 불러 만든다 |

기존 코드의 본보기다.

| 무엇 | 어디 |
| --- | --- |
| 표를 만드는 마이그레이션 | `backend/src/main/resources/db/migration/V48__connector_tool_grant.sql`. 새 표는 `ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci` 로 끝낸다. `test/unit/migration-collation.test.ts` 가 본다 |
| 엔티티의 유일 제약 선언 | `connector/domain/ConnectorAction.java` 의 `@Table(name = …, uniqueConstraints = { @UniqueConstraint(name = …, columnNames = …) })`. 테스트 스키마는 엔티티로 만든다(`application-test.yml` 의 `ddl-auto: create-drop`). 선언하지 않으면 H2 위의 테스트에 유일 제약이 없다 |
| 저장만 트랜잭션 안에서 하고 충돌은 밖에서 처리 | `connector/application/ConnectorPolicyService.java` 225-245줄. 생성자에서 `new TransactionTemplate(transactionManager)` 를 만들고, `transactions.execute(status -> actions.saveAndFlush(action))` 를 `try` 로 감싸 `DataIntegrityViolationException` 을 밖에서 잡아 다시 읽는다. 메서드에는 `@Transactional` 을 붙이지 않는다. 붙이면 예외를 잡아도 트랜잭션이 rollback-only 로 남아 커밋할 때 `UnexpectedRollbackException` 이 난다 |
| 하루 한 번 지우는 일정 | `chat/application/AttachmentCleaner.java` 의 `@Scheduled(cron = "${assistant.attachment.cleanup-cron}")` 과 본체 메서드를 나눈 모양. 테스트 설정 `backend/src/test/resources/application-test.yml` 은 cron 을 `"-"` 로 끈다 |
| 관리자만 여는 경로 | `CurrentUserProvider.requireAdmin()`. 관리자가 아니면 `FORBIDDEN` 이다 |
| 관리자 거절을 보는 테스트 | `backend/src/test/java/com/bifos/assistant/usage/UsageControllerTest.java` 의 `memberBreakdownIsForbiddenAndAdminGetsRows`. `mock(CurrentUserProvider.class)` 에 `doCallRealMethod().when(currentUser).requireAdmin()` 을 걸고 `when(currentUser.require()).thenReturn(new CurrentUser(…, UserRole.MEMBER))` 로 둔 뒤 컨트롤러를 `new` 로 만들어 직접 부르고, `ApiException` 의 `code()` 가 `ErrorCode.FORBIDDEN` 인지 본다 |
| 시계를 옮기는 테스트 | `backend/src/test/java/com/bifos/assistant/task/TaskServiceTest.java` 의 `@Import(TaskServiceTest.FixedClock.class)` 와 389줄의 `TestClock`(`set(Instant)`) |
| 오류 코드 | `shared/error/ErrorCode.java`. 404 코드는 `CONVERSATION_NOT_FOUND(HttpStatus.NOT_FOUND)` 처럼 둔다 |

## 의도 메모

- **숨기기는 클라이언트가 본 `stateKey` 를 저장한다.** 보고 누르는 사이에 상태가 바뀌었으면 새 상태는 사용자가 보지 않은 것이라 다시 보여야 한다. 서버가 지금의 `stateKey` 로 바꿔 넣지 않는다
- **제어는 카드마다 따로다.** 같은 대화가 실패 카드와 이어서 하기 카드에 같은 `itemKey` 로 나온다. 제어를 `(user_id, card_key, item_key)` 에 한 줄로 두어, 한 카드에서 숨긴 것이 다른 카드의 제어를 덮어쓰지 않게 한다(`docs/backend/attention.md` 「API」)
- **숨기기와 미루기는 지금 후보에 있는 항목만 받는다.** 억제 전의 그 카드 후보 목록에 그 `itemKey` 가 없으면 404 `ATTENTION_ITEM_NOT_FOUND` 다. 볼 수 있던 것만 숨길 수 있고, 남의 항목이 있는지 알리지 않는다. 열쇠 형식마다 주인 판정을 따로 두지 않으려는 것이다
- **`SHOWN` 사건은 `GET /api/v1/attention` 이 남긴다.** `summary` 는 남기지 않는다. 사이드바가 화면을 옮길 때마다 부르기 때문이다
- **사건은 모든 종류에 같은 규칙으로 넣는다.** 사건은 같은 `(user_id, item_key, state_key, event_type, attention)` 에 한 줄이다. `SHOWN`, `OPENED`, `ACTED`, `HIDDEN`, `SNOOZED` 모두 「이미 있으면 넘어간다, 줄마다 새 트랜잭션」 이다. 같은 숨기기를 두 번 보내도, 다른 `until` 로 두 번 미뤄도, 같은 `ACTED` 를 두 번 보내도 204 이고 사건은 한 줄이다(`docs/backend/attention.md` 「API」 의 「같은 요청을 다시 보내도 204 다」)
- **제어 줄 갱신은 사건 삽입과 다른 트랜잭션이다.** 사건 삽입이 실패해도 숨기기와 미루기는 남는다
- 같은 상태에서 `LATER` 가 `NOW` 로 바뀌면(30분을 넘긴 맡긴 일, 기한에 들어온 할 일) `NOW` 의 `SHOWN` 이 따로 남아야 `NOW` 의 헛보임을 셀 수 있다
- **사건을 남기지 못해도 응답은 낸다.** 지표는 관측용이다. 실패는 `log.warn` 에 사용자 번호와 개수만 남긴다
- `stale` 은 출처 하나라도 신선도가 `STALE` 이면 참이다. 이 plan 의 출처는 모두 판정할 때 읽은 기록이라 늘 거짓이다
- 기각: 지표를 브라우저가 따로 보내는 방식. 화면이 보낸 것만 세면 같은 응답을 두 번 그리는 일과 빠진 일을 구분하지 못한다. 보인 것은 서버가 낸 응답으로 센다

## 작업 항목

### 1. 마이그레이션 `backend/src/main/resources/db/migration/V71__attention_control_event.sql`

머지 직전 main 과 번호가 겹치면 다음 번호로 옮기고 변경 파일 표도 같은 커밋에서 고친다.

DDL 만 담는다. `docs/backend/schema/attention.md` 의 두 표를 그대로 만든다.

- `attention_control`: `id BIGINT NOT NULL AUTO_INCREMENT`, `user_id BIGINT NOT NULL`, `card_key VARCHAR(16) NOT NULL`, `item_key VARCHAR(80) NOT NULL`, `action VARCHAR(16) NOT NULL`, `state_key VARCHAR(64) NULL`, `until_at DATETIME(6) NULL`, `created_at DATETIME(6) NOT NULL`, `updated_at DATETIME(6) NOT NULL`. `PRIMARY KEY (id)`, 유일 제약 `uk_attention_control_item (user_id, card_key, item_key)`, `CONSTRAINT fk_attention_control_user FOREIGN KEY (user_id) REFERENCES app_user(id)`
- `attention_event`: `id BIGINT NOT NULL AUTO_INCREMENT`, `user_id BIGINT NOT NULL`, `item_key VARCHAR(80) NOT NULL`, `state_key VARCHAR(64) NOT NULL`, `trigger_type VARCHAR(32) NOT NULL`, `attention VARCHAR(16) NOT NULL`, `event_type VARCHAR(16) NOT NULL`, `stale BOOLEAN NOT NULL DEFAULT FALSE`, `created_at DATETIME(6) NOT NULL`. `PRIMARY KEY (id)`, 유일 제약 `uk_attention_event_once (user_id, item_key, state_key, event_type, attention)`, 색인 `idx_attention_event_created (created_at)`, `CONSTRAINT fk_attention_event_user FOREIGN KEY (user_id) REFERENCES app_user(id)`
- 두 표 모두 `ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci`

### 2. 엔티티와 저장소

- `attention.domain.type.AttentionAction`: `HIDE`, `SNOOZE`
- `attention.domain.type.AttentionEventType`: `SHOWN`, `OPENED`, `ACTED`, `HIDDEN`, `SNOOZED`
- `attention.domain.AttentionControlEntry`(`@Table(name = "attention_control", uniqueConstraints = @UniqueConstraint(name = "uk_attention_control_item", columnNames = {"user_id", "card_key", "item_key"}))`). 칸 `cardKey` 는 `CardKey` 를 `@Enumerated(EnumType.STRING)` 으로 저장한다(`FAILURES` 같은 이름). `action` 도 `@Enumerated(EnumType.STRING)`. 정적 생성 `hide(Long userId, CardKey cardKey, String itemKey, String stateKey, Instant now)`, `snooze(Long userId, CardKey cardKey, String itemKey, Instant until, Instant now)` 와 같은 줄을 고치는 `rehide(String stateKey, Instant now)`, `resnooze(Instant until, Instant now)`. `rehide` 는 `until_at` 을 비우고, `resnooze` 는 `state_key` 를 비운다
- `attention.domain.AttentionEvent`(`@Table(name = "attention_event", uniqueConstraints = @UniqueConstraint(name = "uk_attention_event_once", columnNames = {"user_id", "item_key", "state_key", "event_type", "attention"}))`). 정적 생성 `of(Long userId, String itemKey, String stateKey, AttentionTrigger trigger, AttentionLevel level, AttentionEventType type, boolean stale, Instant now)`. 세 enum 칸은 `@Enumerated(EnumType.STRING)`
- `attention.infra.AttentionControlRepository`: `findByUserId(Long userId)`, `findByUserIdAndCardKeyAndItemKey(Long userId, CardKey cardKey, String itemKey)`, `@Modifying` `deleteByUserIdAndCardKeyAndItemKey(Long userId, CardKey cardKey, String itemKey)`
- `attention.infra.AttentionEventRepository`:
  - `findByUserIdAndEventTypeAndItemKeyIn(Long userId, AttentionEventType eventType, Collection<String> itemKeys)`
  - `findFirstByUserIdAndItemKeyAndEventTypeOrderByIdDesc(Long userId, String itemKey, AttentionEventType eventType)` 는 `record` 가 쓴다
  - `findByCreatedAtGreaterThanEqual(Instant since)` 는 지표 계산이 쓴다
  - `@Modifying` `@Query("delete from AttentionEvent e where e.createdAt < :before")` 의 `int deleteCreatedBefore(@Param("before") Instant before)` 는 정리가 쓴다

엔티티 이름이 `AttentionControlEntry` 인 까닭은 phase 01 의 값 record `AttentionControl` 과 겹치지 않게 하려는 것이다.
`docs/backend/schema/attention.md` 「attention_control」 표의 `card_key` 뜻 칸을 「제어를 건 카드. `FAILURES`, `NEEDS_ME`, `DELEGATED`, `CONTINUE`. API 의 소문자 열쇠를 대문자로 쓴 값이다」 로 고친다.

### 3. 설정

`AttentionProperties` 에 칸 둘을 더한다. `snoozeMax`(`Duration`, 기본 8일), `eventRetention`(`Duration`, 기본 90일).
`application.yml` 의 `assistant.attention` 에 `snooze-max: 8d`, `event-retention: 90d`, `cleanup-cron: "0 30 4 * * *"` 와 칸마다 주석 한 줄을 더한다.
`backend/src/test/resources/application-test.yml` 의 `assistant:` 아래에 `attention:` 과 `cleanup-cron: "-"` 를 더한다.

### 4. 사건 기록 `AttentionEventWriter`

`attention.application.AttentionEventWriter`(`@Component`). 생성자에서 `TransactionTemplate` 을 만들고 `setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW)` 로 둔다. 부르는 쪽이 트랜잭션 안이어도 줄마다 따로 커밋하려는 것이다.

- `void recordOnce(AttentionEvent event)`: `transactions.executeWithoutResult(status -> events.saveAndFlush(event))` 를 `try` 로 감싸고, `DataIntegrityViolationException` 이면 이미 있는 것으로 보고 넘어간다
- `void recordShown(Long userId, List<AttentionEvent> shown)`: `findByUserIdAndEventTypeAndItemKeyIn(userId, SHOWN, 열쇠들)` 한 번으로 이미 있는 `(item_key, state_key, attention)` 을 읽고, 없는 것만 `recordOnce` 로 넣는다
- 두 메서드 모두 그 밖의 `RuntimeException` 을 던지지 않고 `log.warn("attention event failed userId={} count={}", userId, 개수)` 만 남긴다

### 5. 제어와 사건의 서비스

`attention.application.AttentionControlService`(`@Service`). 메서드에 `@Transactional` 을 붙이지 않는다. 제어 줄은 `TransactionTemplate`(기본 전파) 안에서 저장하고, 사건은 그 밖에서 `AttentionEventWriter` 로 남긴다.
`card` 는 컨트롤러가 `AttentionDtos.cardOf(String)` 로 바꿔 넘긴다. 모르는 글이면 `null` 이다.

| 메서드 | 하는 일 |
| --- | --- |
| `hide(CurrentUser user, CardKey card, String itemKey, String stateKey)` | 먼저 길이를 본다. `itemKey` 가 비었거나 80자를 넘거나 `stateKey` 가 비었거나 64자를 넘으면 `VALIDATION_FAILED`. `card` 가 `null` 이면 `VALIDATION_FAILED`. 그 카드의 후보 목록에 `itemKey` 가 없으면 `ATTENTION_ITEM_NOT_FOUND`. 그 카드의 줄이 있으면 `rehide` 하고 없으면 `hide` 로 만든다. 저장이 `DataIntegrityViolationException` 이면(같은 요청이 동시에 왔다) 다시 읽어 `rehide` 를 한 번 더 한다. 그 뒤 `HIDDEN` 사건을 `recordOnce` 로 남긴다 |
| `snooze(CurrentUser user, CardKey card, String itemKey, Instant until)` | `itemKey` 길이 검사와 `card` 검사는 위와 같다. `until` 이 `null` 이거나 지금 뒤가 아니거나 지금부터 `snoozeMax` 를 넘으면 `VALIDATION_FAILED`. 나머지는 위와 같고 `resnooze` 와 `SNOOZED` 사건을 쓴다 |
| `restore(CurrentUser user, CardKey card, String itemKey)` | `itemKey` 길이 검사와 `card` 검사는 위와 같다. 그 사용자의 그 카드의 그 줄을 지운다. 없어도 204 다 |
| `record(CurrentUser user, String itemKey, String stateKey, AttentionEventType type)` | 먼저 `hide` 와 같은 길이 검사를 한다. `type` 이 `OPENED`, `ACTED` 가 아니면 `VALIDATION_FAILED`. 그 사건의 `trigger_type` 과 `attention` 은 아래 「사건의 판정 칸」 순서로 고른다. 셋 다 없으면 `ATTENTION_ITEM_NOT_FOUND`. 그 뒤 `recordOnce` 로 남긴다 |

**사건의 판정 칸.** `events` 요청 본문에는 `card` 가 없다. 같은 `itemKey` 가 두 카드에 있을 수 있으므로 아래 순서로 하나를 고른다.

1. 지금 판정 결과에 그 `itemKey` 가 있으면, `CardKey` 선언 순서로 처음 나온 카드의 그 항목의 `trigger` 와 `level`. 중복 억제가 뒤 카드의 같은 열쇠를 이미 지우므로 실패 카드의 대화가 이어서 하기보다 앞선다
2. 없으면 그 사용자와 `itemKey` 의 가장 최근 `SHOWN` 사건(`findFirstByUserIdAndItemKeyAndEventTypeOrderByIdDesc`)의 `trigger_type` 과 `attention`. 동작이 성공해 후보에서 빠진 뒤의 `ACTED` 를 잃지 않으려는 것이다
3. 그것도 없고 억제 전 후보에만 있으면(숨겼거나 미뤘다) `CardKey` 선언 순서로 처음 나온 후보의 `trigger` 와 `AttentionLevel.SUPPRESSED`

`hide` 와 `snooze` 의 사건도 같은 순서로 고르되, 1 과 3 은 요청의 카드 안에서만 찾는다.
`shared.error.ErrorCode` 에 `ATTENTION_ITEM_NOT_FOUND(HttpStatus.NOT_FOUND)` 를 더한다.

「후보 목록」 은 `AttentionService` 가 억제 전에 모은 후보다. `AttentionService` 에 `AttentionSnapshot snapshot(CurrentUser user)` 를 두고 `view` 와 제어 서비스가 같이 쓴다. `snapshot` 의 `cards` 는 `AttentionControlRepository.findByUserId` 로 읽은 제어를 적용해 판정한 결과다. 그래서 숨긴 뒤 `summary` 의 `nowCount` 가 줄어든다. `attention.application.model.AttentionSnapshot(Instant now, Map<CardKey, List<AttentionCandidate>> candidates, Set<CardKey> unavailable, List<AttentionCard> cards)` 다.

`AttentionService.view` 를 고친다.

- `AttentionControlRepository.findByUserId` 로 읽은 줄을 `AttentionControl` 로 바꿔 `AttentionJudge` 에 넘긴다. `HIDE` 는 `stateKey` 를, `SNOOZE` 는 `snoozedUntil` 을 채운다
- 응답에 실린 `NOW` 와 `LATER` 항목마다 `SHOWN` 사건을 `AttentionEventWriter.recordShown` 으로 남긴다. `summary` 는 `snapshot` 만 쓰고 사건을 남기지 않는다

### 6. 지표

`attention.application.AttentionMetricsService.metrics(int days)` 는 `days` 가 1부터 90 밖이면 400 `VALIDATION_FAILED` 로 거절하고(`docs/backend/attention.md` 「API」 의 지표 줄), `now - days` 일 뒤의 사건을 읽어 `trigger_type` 별로 센다.
계산은 `docs/backend/attention.md` 의 「지표」 표 그대로다. 줄 순서는 `AttentionTrigger` 선언 순서이고 `shown` 이 0 인 `trigger` 는 줄을 내지 않는다.

| 응답 칸 | 값 |
| --- | --- |
| `trigger` | `AttentionTrigger` 이름 |
| `shown` | `SHOWN` 이 있는 `(item_key, state_key)` 수 |
| `hidden`, `snoozed` | 그 가운데 `HIDDEN`, `SNOOZED` 가 있는 수 |
| `acted` | 그 가운데 `OPENED` 나 `ACTED` 가 있는 수 |
| `nowShown` | `SHOWN` 의 `attention` 이 `NOW` 인 수 |
| `nowHiddenWithoutAction` | 그 가운데 `OPENED`, `ACTED` 없이 `HIDDEN` 이 있는 수 |
| `staleShown` | `SHOWN` 의 `stale` 이 참인 수 |
| `medianSecondsToFirstAction` | 첫 `SHOWN` 에서 첫 `OPENED` 나 `ACTED` 까지 초의 중앙값(`Long`). 짝수 개면 가운데 둘의 평균을 내림한다. 없으면 `null` |

제목, `item_key`, 사용자 번호를 응답에 내지 않는다.

### 7. 보관 기간 정리

`attention.application.AttentionEventCleaner`(`@Component`). `@Scheduled(cron = "${assistant.attention.cleanup-cron}")` 의 `runScheduled()` 가 `clean(clock.instant())` 를 부른다.
`@Transactional` 인 `int clean(Instant now)` 은 `deleteCreatedBefore(now - eventRetention)` 로 지우고 지운 수를 돌려준다. 로그에는 그 수만 낸다.

### 8. 컨트롤러

`AttentionController` 에 더한다. 요청 record 는 `AttentionDtos` 에 둔다. `AttentionDtos.cardOf(String text)` 는 `failures`, `needs_me`, `delegated`, `continue` 를 `CardKey` 로 바꾸고 그 밖의 글과 `null` 은 `null` 을 낸다.

| 경로 | 본문 | 응답 |
| --- | --- | --- |
| `POST /api/v1/attention/hide` | `HideRequest(String card, String itemKey, String stateKey)` | 204 |
| `POST /api/v1/attention/snooze` | `SnoozeRequest(String card, String itemKey, Instant until)` | 204 |
| `POST /api/v1/attention/restore` | `RestoreRequest(String card, String itemKey)` | 204 |
| `POST /api/v1/attention/events` | `EventRequest(String itemKey, String stateKey, String type)` | 204. `type` 은 `AttentionEventType` 이름이 아니면 `null` 로 넘겨 `VALIDATION_FAILED` 가 된다 |

새 `attention.presentation.AttentionAdminController`(`@RequestMapping("/api/v1/admin/attention")`): `@GetMapping("/metrics")` 의 `@RequestParam(defaultValue = "30") int days` → `AttentionDtos.MetricsResponse(int days, List<MetricRow> rows)`. 처음에 `currentUser.requireAdmin()` 을 부른다.
`MetricRow(String trigger, long shown, long hidden, long snoozed, long acted, long nowShown, long nowHiddenWithoutAction, long staleShown, Long medianSecondsToFirstAction)` 다.

### 9. 문서

- `docs/backend/attention.md` 의 「기준값」 표의 `cleanup-cron` 줄, 「API」 절의 404 `ATTENTION_ITEM_NOT_FOUND` 문장과 카드별 제어 문장, `days` 의 400 규칙이 이 phase 의 구현과 같은지 확인한다. 다르면 구현을 문서에 맞춘다
- `docs/backend/attention.md` 「저장」 의 `attention_event` 줄 끝에 「모든 사건 종류가 같은 규칙이다. 같은 줄이 이미 있으면 넣지 않고, 줄마다 따로 커밋해 한 줄의 충돌이 다른 줄이나 제어 줄을 되돌리지 않는다」 를 더한다
- `docs/backend/schema/attention.md` 의 「attention_control」 절과 「attention_event」 절 바로 아래의 「**아직 구현 전이다.**」 줄을 지운다. 머리의 「세 표는 아직 마이그레이션이 없다. 표마다 그 절의 「아직 구현 전이다」 줄을 표를 만든 PR 이 지운다.」 는 「`follow_up` 은 아직 마이그레이션이 없다. 표를 만든 PR 이 그 절의 「아직 구현 전이다」 줄과 이 문장을 지운다.」 로 바꾼다
- `docs/backend/schema/README.md` 의 `attention.md` 줄 끝 「아직 구현 전이다」 를 「`follow_up` 은 아직 구현 전이다」 로 바꾼다

### 10. 이 phase 를 검증하는 테스트

`backend/src/test/java/com/bifos/assistant/attention/AttentionControlServiceTest.java` (`@SpringBootTest`, `@ActiveProfiles("test")`, `@Import(AttentionControlServiceTest.FixedClock.class)`. `TaskServiceTest` 의 `TestClock` 과 같은 모양을 이 파일 안에 둔다):

| 입력 | 기대 |
| --- | --- |
| 실패 항목을 `FAILURES` 카드와 그 `stateKey` 로 숨긴다 | 실패 카드에 그 항목이 없고 `HIDDEN` 사건이 한 줄. 같은 대화가 이어서 하기 카드에 보인다 |
| 같은 숨기기를 한 번 더 보낸다 | 예외 없이 끝나고 제어 줄은 하나, `HIDDEN` 사건도 한 줄이다 |
| 이어서 하기 카드의 그 대화를 `CONTINUE` 카드로 숨긴다 | 이어서 하기에서 빠진다. 실패 카드의 숨기기 줄이 그대로이고 실패 항목은 여전히 보이지 않는다(제어 줄이 둘) |
| 같은 대화에 새 실패가 생긴다(`stateKey` 가 바뀜) | 그 항목이 다시 `NOW` 로 보인다 |
| 내일 오전으로 미룬다 | 지금은 빠지고, `TestClock.set` 으로 그 뒤로 옮긴 `view` 에는 보인다 |
| 다른 `until` 로 두 번 미룬다 | 제어 줄의 `until_at` 은 두 번째 값이고 `SNOOZED` 사건은 한 줄이다 |
| `until` 이 9일 뒤 | `VALIDATION_FAILED` |
| 다른 사용자의 대화 열쇠로 숨긴다 | `ATTENTION_ITEM_NOT_FOUND` |
| `SHOWN` 이 남은 승인 대기를 처리해 후보에서 빠진 뒤 `ACTED` 를 보낸다 | 예외 없이 끝난다. `ACTED` 사건이 남고 `trigger_type` 은 `APPROVAL_PENDING`, `attention` 은 그 `SHOWN` 의 `NOW` 다 |
| 같은 `ACTED` 를 한 번 더 보낸다 | 예외 없이 끝나고 `ACTED` 사건은 한 줄이다 |
| `SHOWN` 도 없고 후보에도 없는 열쇠로 `OPENED` 를 보낸다 | `ATTENTION_ITEM_NOT_FOUND` |
| `restore` | 다시 보인다 |
| `view` 를 두 번 부른다 | 항목마다 `SHOWN` 이 한 줄이다 |
| 20분 전에 시작한 `RUNNING` 위임으로 `view`, 시계를 15분 옮겨 다시 `view` | 같은 `stateKey` 에 `LATER` 의 `SHOWN` 과 `NOW` 의 `SHOWN` 이 한 줄씩 있다 |
| `card` 가 `null`(모르는 글) | `VALIDATION_FAILED` |
| `hide` 의 `stateKey` 가 65자 | 후보를 읽지 않고 `VALIDATION_FAILED` |
| `record` 의 `itemKey` 가 81자 | 후보를 읽지 않고 `VALIDATION_FAILED` |
| `summary` 를 부른다 | 사건이 늘지 않는다 |
| `record` 에 `SHOWN` | `VALIDATION_FAILED` |

`backend/src/test/java/com/bifos/assistant/attention/AttentionMetricsServiceTest.java` (`@SpringBootTest`, `@ActiveProfiles("test")`. 사건은 `AttentionEventRepository` 로 직접 저장한다):

| 입력 | 기대 |
| --- | --- |
| `EXECUTION_FAILED` 항목 둘이 `NOW` 로 보이고 하나는 10초 뒤 `OPENED`, 하나는 행동 없이 `HIDDEN` | `shown` 2, `acted` 1, `hidden` 1, `nowShown` 2, `nowHiddenWithoutAction` 1, `medianSecondsToFirstAction` 10 |
| 91일 전 사건 | `metrics(30)` 에 들지 않는다. `AttentionEventCleaner.clean` 이 지우고 1 을 돌려준다 |
| `metrics(0)`, `metrics(91)` | `VALIDATION_FAILED` |
| `UsageControllerTest` 와 같은 모양으로 `MEMBER` 역할의 대역 `CurrentUserProvider` 를 넣어 `new AttentionAdminController(metrics, currentUser).metrics(30)` 을 부른다 | `ApiException` 의 `code()` 가 `ErrorCode.FORBIDDEN` 이다. 같은 대역을 `ADMIN` 으로 바꾸면 응답을 받는다 |

`test/e2e/scenarios/attention.ts` 의 단계 3(`summary` 와 `nowCount` 비교)과 단계 4(성공하는 메시지로 해소) 사이에 아래를 끼운다. 그 시점에는 phase 01 이 만든 실패 항목이 남아 있다.

- 그 실패 항목을 `POST /attention/hide`(`card: "failures"`, 응답에서 읽은 `itemKey` 와 `stateKey`)로 숨기면 204, `GET /attention` 의 `failures` 카드에서 빠지고 `summary.nowCount` 가 1 줄어든다
- 같은 본문으로 한 번 더 보내도 204 다
- `POST /attention/restore`(`card: "failures"`, 같은 `itemKey`) 뒤에 다시 보인다
- `itemKey` 를 `conversation:00000000-0000-4000-8000-000000000000` 으로 숨기면 404 다

## 검증

```bash
# cwd: backend/
./gradlew test --tests 'com.bifos.assistant.attention.AttentionControlServiceTest' --tests 'com.bifos.assistant.attention.AttentionMetricsServiceTest' --tests 'com.bifos.assistant.attention.AttentionServiceTest'
./gradlew test
./gradlew checkstyleMain checkstyleTest spotlessCheck
```

```bash
# cwd: 저장소 root
node --test test/unit/migration-collation.test.ts test/unit/migration-immutable.test.ts
node test/e2e/run.ts
scripts/check-mysql-migration.sh
scripts/check-public-safe.sh
```

기대값: 명령이 모두 종료 코드 0 이다. `scripts/check-mysql-migration.sh` 의 `MysqlMigrationTest` 가 새 마이그레이션을 실제 MySQL 에 적용하고(`ddl-auto: validate`) 두 표의 정렬 규칙이 `utf8mb4_0900_ai_ci` 인지 본다. Docker 가 있어야 한다.

## 변경 파일

| 파일 | 변경 |
| --- | --- |
| `backend/src/main/resources/db/migration/V71__attention_control_event.sql` | 신규 |
| `backend/src/main/java/com/bifos/assistant/attention/domain/type/AttentionAction.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/attention/domain/type/AttentionEventType.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/attention/domain/AttentionControlEntry.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/attention/domain/AttentionEvent.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/attention/infra/AttentionControlRepository.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/attention/infra/AttentionEventRepository.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/attention/application/model/AttentionSnapshot.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/attention/application/AttentionProperties.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/attention/application/AttentionService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/attention/application/AttentionEventWriter.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/attention/application/AttentionControlService.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/attention/application/AttentionMetricsService.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/attention/application/AttentionEventCleaner.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/attention/presentation/AttentionController.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/attention/presentation/AttentionAdminController.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/attention/presentation/AttentionDtos.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/shared/error/ErrorCode.java` | 수정 |
| `backend/src/main/resources/application.yml` | 수정 |
| `backend/src/test/resources/application-test.yml` | 수정 |
| `backend/src/test/java/com/bifos/assistant/attention/AttentionControlServiceTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/attention/AttentionMetricsServiceTest.java` | 신규 |
| `test/e2e/scenarios/attention.ts` | 수정 |
| `docs/backend/attention.md` | 수정 |
| `docs/backend/schema/attention.md` | 수정 |
| `docs/backend/schema/README.md` | 수정 |
