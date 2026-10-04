# Phase 02. 숨기기, 미루기, 지표 사건

**Execution profile**: standard

## 목표

사용자가 항목을 숨기고 미루고 되돌릴 수 있게 하고, 판정이 그 제어를 억제 신호로 쓰게 한다.
지금 화면에 보인 항목과 그 항목에 한 일을 지표 사건으로 남기고, 관리자가 `trigger` 별로 센 값을 읽게 한다.

**범위 외**: 화면의 메뉴와 「되돌리기」 줄(plan81), 결과 전달 실패(phase 03), 할 일(plan80).

## 컨텍스트

**근거 문서**: `docs/backend/attention.md` 의 「억제 신호」, 「API」, 「저장」, 「지표」, 「기준값」. `docs/backend/schema/attention.md` 의 「attention_control」, 「attention_event」. `docs/backend/schema/README.md` 의 「마이그레이션 작성 규칙」.

phase 01 이 만든 것을 쓴다.

| 무엇 | 어디 |
| --- | --- |
| 판정 | `attention.application.AttentionJudge.judge(..., List<AttentionControl> controls, ...)`. phase 01 의 `AttentionService.view` 는 빈 제어를 넘긴다 |
| 제어 값 | `attention.application.model.AttentionControl(String itemKey, String stateKey, Instant snoozedUntil)` |
| 저장하는 enum | `attention.domain.type.AttentionTrigger`, `AttentionLevel` |
| 후보 목록 | `AttentionService` 가 모든 `AttentionCandidates` 를 불러 만든다 |

기존 코드의 본보기다.

| 무엇 | 어디 |
| --- | --- |
| 표를 만드는 마이그레이션 | `backend/src/main/resources/db/migration/V48__connector_tool_grant.sql`. 단, 새 표는 `ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci` 로 끝낸다. `test/unit/migration-collation.test.ts` 가 본다 |
| 하루 한 번 지우는 일정 | `backend/src/main/java/com/bifos/assistant/chat/application/AttachmentCleaner.java` 의 `@Scheduled(cron = "${assistant.attachment.cleanup-cron}")` 과 본체 메서드를 나눈 모양. 테스트 설정 `backend/src/test/resources/application-test.yml` 은 cron 을 `"-"` 로 끈다 |
| 관리자만 여는 경로 | `CurrentUserProvider.requireAdmin()`. 관리자가 아니면 `FORBIDDEN` 이다 |
| 오류 코드 | `backend/src/main/java/com/bifos/assistant/shared/error/ErrorCode.java`. 404 코드는 `CONVERSATION_NOT_FOUND(HttpStatus.NOT_FOUND)` 처럼 둔다 |

## 의도 메모

- **숨기기는 클라이언트가 본 `stateKey` 를 저장한다.** 보고 누르는 사이에 상태가 바뀌었으면 새 상태는 사용자가 보지 않은 것이라 다시 보여야 한다. 서버가 지금의 `stateKey` 로 바꿔 넣지 않는다
- **제어는 카드마다 따로다.** 같은 대화가 실패 카드와 이어서 하기 카드에 같은 `itemKey` 로 나온다. 제어를 `(user_id, card_key, item_key)` 에 한 줄로 두어, 한 카드에서 숨긴 것이 다른 카드의 제어를 덮어쓰지 않게 한다(`docs/backend/attention.md` 「API」)
- **숨기기와 미루기는 지금 후보에 있는 항목만 받는다.** 억제 전의 그 카드 후보 목록에 그 `itemKey` 가 없으면 404 `ATTENTION_ITEM_NOT_FOUND` 다. 볼 수 있던 것만 숨길 수 있고, 남의 항목이 있는지 알리지 않는다. 열쇠 형식마다 주인 판정을 따로 두지 않으려는 것이다
- **`SHOWN` 사건은 `GET /api/v1/attention` 이 남긴다.** `summary` 는 남기지 않는다. 사이드바가 화면을 옮길 때마다 부르기 때문이다
- 사건은 같은 `(user_id, item_key, state_key, event_type, attention)` 에 한 줄이다. 같은 상태에서 `LATER` 가 `NOW` 로 바뀌면(30분을 넘긴 맡긴 일, 기한에 들어온 할 일) `NOW` 의 `SHOWN` 이 따로 남아야 `NOW` 의 헛보임을 셀 수 있다. 먼저 한 번의 조회로 이미 있는 줄을 읽고 없는 것만 넣는다. 동시에 같은 줄을 넣다 유일 제약에 걸리면 이미 있는 것으로 보고 넘어간다. 그 삽입은 줄마다 새 트랜잭션에서 해 한 줄의 충돌이 다른 줄을 되돌리지 않게 한다
- **사건을 남기지 못해도 응답은 낸다.** 지표는 관측용이다. 실패는 `log.warn` 에 사용자 번호와 개수만 남긴다
- `stale` 은 출처 하나라도 신선도가 `STALE` 이면 참이다. 이 plan 의 source(`EXECUTION_STATE`, Memory 제안)는 늘 `FRESH` 라 지금은 늘 거짓이다. 결과를 source 로 쓰는 항목이 생기면 참이 될 수 있다
- 기각: 지표를 브라우저가 따로 보내는 방식. 화면이 보낸 것만 세면 같은 응답을 두 번 그리는 일과 빠진 일을 구분하지 못한다. 보인 것은 서버가 낸 응답으로 센다

## 작업 항목

### 1. 마이그레이션 `V67__attention_control_event.sql`

**구현할 때 main 의 마지막 다음 번호로 바꾸고 이 phase 의 「변경 파일」 표와 `README.md` 의 그 줄도 고친다.** 지금 main 의 마지막은 V65 이고 plan78 이 V66 을 쓸 예정이다.

DDL 만 담는다. `docs/backend/schema/attention.md` 의 두 표를 그대로 만든다.

- `attention_control`: `id BIGINT NOT NULL AUTO_INCREMENT`, `user_id BIGINT NOT NULL`, `card_key VARCHAR(16) NOT NULL`, `item_key VARCHAR(80) NOT NULL`, `action VARCHAR(16) NOT NULL`, `state_key VARCHAR(64) NULL`, `until_at DATETIME(6) NULL`, `created_at DATETIME(6) NOT NULL`, `updated_at DATETIME(6) NOT NULL`. 유일 제약 `uk_attention_control_item (user_id, card_key, item_key)`. `user_id` 에 `app_user(id)` FK
- `attention_event`: `id BIGINT NOT NULL AUTO_INCREMENT`, `user_id BIGINT NOT NULL`, `item_key VARCHAR(80) NOT NULL`, `state_key VARCHAR(64) NOT NULL`, `trigger_type VARCHAR(32) NOT NULL`, `attention VARCHAR(16) NOT NULL`, `event_type VARCHAR(16) NOT NULL`, `stale BOOLEAN NOT NULL DEFAULT FALSE`, `created_at DATETIME(6) NOT NULL`. 유일 제약 `uk_attention_event_once (user_id, item_key, state_key, event_type, attention)`. `created_at` 색인 `idx_attention_event_created`. `user_id` 에 FK
- 두 표 모두 `ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci`

### 2. 엔티티와 저장소

- `attention.domain.type.AttentionAction`: `HIDE`, `SNOOZE`
- `attention.domain.type.AttentionEventType`: `SHOWN`, `OPENED`, `ACTED`, `HIDDEN`, `SNOOZED`
- `attention.domain.AttentionControlEntry`(표 `attention_control`). 칸 `cardKey` 는 phase 01 의 `CardKey` 를 `@Enumerated(EnumType.STRING)` 대신 응답과 같은 소문자 글(`failures` 같은 것)로 저장한다. 정적 생성 `hide(userId, cardKey, itemKey, stateKey, now)`, `snooze(userId, cardKey, itemKey, until, now)` 와 같은 줄을 고치는 `rehide(stateKey, now)`, `resnooze(until, now)`. enum 칸은 `@Enumerated(EnumType.STRING)`
- `attention.domain.AttentionEvent`(표 `attention_event`). 정적 생성 `of(userId, itemKey, stateKey, trigger, level, type, stale, now)`
- `attention.infra.AttentionControlRepository`: `findByUserId(Long userId)`, `findByUserIdAndCardKeyAndItemKey(Long userId, String cardKey, String itemKey)`, `deleteByUserIdAndCardKeyAndItemKey(Long userId, String cardKey, String itemKey)`
- `attention.infra.AttentionEventRepository`:
  - `findByUserIdAndEventTypeAndItemKeyIn(Long userId, AttentionEventType eventType, Collection<String> itemKeys)`
  - `existsByUserIdAndItemKeyAndStateKeyAndEventTypeAndAttention(...)`
  - `findByCreatedAtGreaterThanEqual(Instant since)` 는 지표 계산이 쓴다
  - `@Modifying` `deleteByCreatedAtBefore(Instant before)` 는 정리가 쓴다

엔티티 이름이 `AttentionControlEntry` 인 까닭은 phase 01 의 값 record `AttentionControl` 과 겹치지 않게 하려는 것이다.

### 3. 설정

`AttentionProperties` 에 칸 둘을 더한다. `snoozeMax`(`Duration`, 기본 8일), `eventRetention`(`Duration`, 기본 90일).
`application.yml` 의 `assistant.attention` 에 `snooze-max: 8d`, `event-retention: 90d`, `cleanup-cron: "0 30 4 * * *"` 와 주석을 더한다.
`backend/src/test/resources/application-test.yml` 에 `assistant.attention.cleanup-cron: "-"` 를 더한다.

### 4. 제어와 사건의 서비스

`attention.application.AttentionControlService`:

| 메서드 | 하는 일 |
| --- | --- |
| `hide(CurrentUser user, String card, String itemKey, String stateKey)` | `card` 가 `failures`, `needs_me`, `delegated`, `continue` 가 아니면 `VALIDATION_FAILED`. 그 카드의 후보 목록에 `itemKey` 가 없으면 `ATTENTION_ITEM_NOT_FOUND`. 그 카드의 줄이 있으면 `HIDE` 로 고치고 없으면 만든다. `HIDDEN` 사건을 남긴다 |
| `snooze(CurrentUser user, String card, String itemKey, Instant until)` | `card` 검사는 위와 같다. | `until` 이 지금 뒤이고 `snoozeMax` 안이 아니면 `VALIDATION_FAILED`. 나머지는 위와 같고 `SNOOZED` 사건을 남긴다 |
| `restore(CurrentUser user, String card, String itemKey)` | `card` 검사는 위와 같다. 그 사용자의 그 카드의 그 줄을 지운다. 없어도 204 다 |
| `record(CurrentUser user, String itemKey, String stateKey, AttentionEventType type)` | `OPENED`, `ACTED` 만 받는다. 다른 값은 `VALIDATION_FAILED`. 후보에 없으면 `ATTENTION_ITEM_NOT_FOUND` |

`shared.error.ErrorCode` 에 `ATTENTION_ITEM_NOT_FOUND(HttpStatus.NOT_FOUND)` 를 더한다.
「후보 목록」 은 `AttentionService` 가 억제 전에 모은 후보다. `AttentionService` 에 `candidatesOf(CurrentUser user)` 를 두고 `view` 와 같이 쓴다.

`AttentionService.view` 를 고친다.

- `AttentionControlRepository.findByUserId` 로 읽은 줄을 `AttentionControl`(카드 포함)로 바꿔 `AttentionJudge` 에 넘긴다. 판정은 카드별로 제어를 맞춘다(phase 01 의 `AttentionJudge` 1번)
- 응답에 실린 `NOW` 와 `LATER` 항목마다 `SHOWN` 사건을 한 번 남긴다. 「의도 메모」 의 방법을 따른다

### 5. 지표

`attention.application.AttentionMetricsService.metrics(int days)` 는 `days` 가 1부터 90 밖이면 400 `VALIDATION_FAILED` 로 거절하고(`docs/backend/attention.md` 「API」, plan78 의 지연 시간 API 와 같다), 그 기간의 사건을 읽어 `trigger_type` 별로 센다.
계산은 `docs/backend/attention.md` 의 「지표」 표 그대로다.

| 응답 칸 | 값 |
| --- | --- |
| `trigger` | `AttentionTrigger` 이름 |
| `shown` | `SHOWN` 이 있는 `(item_key, state_key)` 수 |
| `hidden`, `snoozed` | 그 가운데 `HIDDEN`, `SNOOZED` 가 있는 수 |
| `acted` | 그 가운데 `OPENED` 나 `ACTED` 가 있는 수 |
| `nowShown` | `SHOWN` 의 `attention` 이 `NOW` 인 수 |
| `nowHiddenWithoutAction` | 그 가운데 `OPENED`, `ACTED` 없이 `HIDDEN` 이 있는 수 |
| `staleShown` | `SHOWN` 의 `stale` 이 참인 수 |
| `medianSecondsToFirstAction` | 첫 `SHOWN` 에서 첫 `OPENED` 나 `ACTED` 까지 초의 중앙값. 없으면 `null` |

제목, `item_key`, 사용자 번호를 응답에 내지 않는다.

### 6. 보관 기간 정리

`attention.application.AttentionEventCleaner`(`@Component`). `@Scheduled(cron = "${assistant.attention.cleanup-cron}")` 의 `runScheduled()` 가 `clean(clock.instant())` 를 부른다.
`clean(Instant now)` 은 `now - eventRetention` 보다 앞선 줄을 지우고 지운 수를 돌려준다. 로그에는 그 수만 낸다.

### 7. 컨트롤러

`AttentionController` 에 더한다. 요청 record 는 `AttentionDtos` 에 둔다.

| 경로 | 본문 | 응답 |
| --- | --- | --- |
| `POST /api/v1/attention/hide` | `HideRequest(String card, String itemKey, String stateKey)` | 204 |
| `POST /api/v1/attention/snooze` | `SnoozeRequest(String card, String itemKey, Instant until)` | 204 |
| `POST /api/v1/attention/restore` | `RestoreRequest(String card, String itemKey)` | 204 |
| `POST /api/v1/attention/events` | `EventRequest(String itemKey, String stateKey, String type)` | 204 |

새 `attention.presentation.AttentionAdminController`(`@RequestMapping("/api/v1/admin/attention")`): `GET /metrics?days=30` → `AttentionDtos.MetricsResponse(int days, List<MetricRow> rows)`. 처음에 `currentUser.requireAdmin()` 을 부른다.

### 8. 문서

- `docs/backend/attention.md` 의 「기준값」 표의 `cleanup-cron` 줄, 「API」 절의 404 `ATTENTION_ITEM_NOT_FOUND` 문장과 카드별 제어 문장, `days` 의 400 규칙이 이 phase 의 구현과 같은지 확인한다. 다르면 구현을 문서에 맞춘다
- `docs/backend/schema/attention.md` 의 「attention_control」 절과 「attention_event」 절 바로 아래의 「**아직 구현 전이다.**」 줄을 지운다. 머리의 「세 표는 아직 마이그레이션이 없다」 문장은 「`follow_up` 은 아직 마이그레이션이 없다」 로 바꾼다
- `docs/backend/schema/README.md` 의 `attention.md` 줄 끝 「아직 구현 전이다」 를 「`follow_up` 은 아직 구현 전이다」 로 바꾼다
- phase 03 없이 PR 을 내면 아래도 한다(`README.md` 의 「PR 과 계획서 삭제」)
  - `docs/adr/ADR-072-먼저-알리기의-기본값은-알리지-않음이고-control-plane-기록에서-정한-신호만-화면-안에-올린다.md` 의 `status` 와 `docs/adr/INDEX.md` 의 ADR-072 줄을 「`DELIVERY_FAILED` 는 아직 구현 전이다」 로
  - `docs/backend/attention.md` 의 구현 전 단락을 「**`DELIVERY_FAILED` 는 아직 구현 전이다.** 구현한 PR 이 이 단락을 지운다.」 로
  - `docs/code-architecture.md` 의 「아직 만들지 않은 것」 의 `attention` 줄을 「먼저 알리기의 결과 전달 실패 후보」 한 줄로

### 9. 이 phase 를 검증하는 테스트

`backend/src/test/java/com/bifos/assistant/attention/AttentionControlServiceTest.java` (`@SpringBootTest`, `@ActiveProfiles("test")`):

| 입력 | 기대 |
| --- | --- |
| 실패 항목을 `failures` 카드와 그 `stateKey` 로 숨긴다 | 실패 카드에 그 항목이 없고 `HIDDEN` 사건이 한 줄. 같은 대화가 이어서 하기 카드에 보인다 |
| 이어서 하기 카드의 그 대화를 `continue` 카드로 숨긴다 | 이어서 하기에서 빠진다. 실패 카드의 숨기기 줄이 그대로이고 실패 항목은 여전히 보이지 않는다(제어 줄이 둘) |
| 같은 대화에 새 실패가 생긴다(`stateKey` 가 바뀜) | 그 항목이 다시 `NOW` 로 보인다 |
| 내일 오전으로 미룬다 | 지금은 빠지고, 시계를 그 뒤로 옮긴 `view` 에는 보인다 |
| `until` 이 9일 뒤 | `VALIDATION_FAILED` |
| 다른 사용자의 대화 열쇠로 숨긴다 | `ATTENTION_ITEM_NOT_FOUND` |
| `restore` | 다시 보인다 |
| `view` 를 두 번 부른다 | 항목마다 `SHOWN` 이 한 줄이다 |
| 20분 전에 시작한 `RUNNING` 위임으로 `view`, 시계를 15분 옮겨 다시 `view` | 같은 `stateKey` 에 `LATER` 의 `SHOWN` 과 `NOW` 의 `SHOWN` 이 한 줄씩 있다 |
| `card` 가 `unknown` | `VALIDATION_FAILED` |
| `summary` 를 부른다 | 사건이 늘지 않는다 |
| `record` 에 `SHOWN` | `VALIDATION_FAILED` |

`backend/src/test/java/com/bifos/assistant/attention/AttentionMetricsServiceTest.java`:

| 입력 | 기대 |
| --- | --- |
| `EXECUTION_FAILED` 항목 둘이 보이고 하나는 10초 뒤 `OPENED`, 하나는 행동 없이 `HIDDEN` | `shown` 2, `acted` 1, `hidden` 1, `nowHiddenWithoutAction` 1, `medianSecondsToFirstAction` 10 |
| 91일 전 사건 | `metrics(30)` 에 들지 않는다. `AttentionEventCleaner.clean` 이 지운다 |
| `metrics(0)`, `metrics(91)` | `VALIDATION_FAILED` |
| `MEMBER` 역할로 `AttentionAdminController` 를 부른다 | `FORBIDDEN` |

`test/e2e/scenarios/attention.ts` 에 이어 붙인다.

- phase 01 이 만든 실패 항목을 `POST /attention/hide`(`card: "failures"`)로 숨기면 `GET /attention` 에서 빠지고 `summary.nowCount` 가 1 줄어든다
- `POST /attention/restore` 뒤에 다시 보인다
- 아무 글이나 넣은 `itemKey` 로 숨기면 404 다

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

기대값: 명령이 모두 종료 코드 0 이다. `scripts/check-mysql-migration.sh` 의 `MysqlMigrationTest` 가 새 마이그레이션을 실제 MySQL 에 적용하고 두 표의 정렬 규칙이 `utf8mb4_0900_ai_ci` 인지 본다. Docker 가 있어야 한다.

## 변경 파일

| 파일 | 변경 |
| --- | --- |
| `backend/src/main/resources/db/migration/V67__attention_control_event.sql` | 신규 |
| `backend/src/main/java/com/bifos/assistant/attention/domain/type/AttentionAction.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/attention/domain/type/AttentionEventType.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/attention/domain/AttentionControlEntry.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/attention/domain/AttentionEvent.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/attention/infra/AttentionControlRepository.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/attention/infra/AttentionEventRepository.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/attention/application/AttentionProperties.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/attention/application/AttentionService.java` | 수정 |
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
| `docs/adr/ADR-072-먼저-알리기의-기본값은-알리지-않음이고-control-plane-기록에서-정한-신호만-화면-안에-올린다.md` | 수정 |
| `docs/adr/INDEX.md` | 수정 |
| `docs/code-architecture.md` | 수정 |

ADR-072, `INDEX.md`, `code-architecture.md` 는 phase 03 없이 PR 을 낼 때만 고친다. phase 03 과 함께 내면 phase 03 이 고친다.
