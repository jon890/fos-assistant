# Phase 01. 사건 실행의 알림, 조용한 시간 미루기, 다시 연결 알림

**Execution profile**: standard

## 목표

사건 실행이 끝나면 알릴 메일이 있을 때 알림 한 건을 만들고, 조용한 시간에는 미뤘다가 아침에 한 건으로 내며, 연속 실패가 정한 횟수에 처음 닿으면 다시 연결 알림을 한 번 낸다.

**범위 외**: 켜고 끄는 API 와 화면(phase 02, 03), 기능 파일 갱신(phase 04).

## 컨텍스트

PR1 이 확인과 깨우기를 만들었다. 이 phase 는 끝난 실행과 실패를 알림으로 잇는다.
설계는 `docs/features/proactive/design.md` 의 「계기와 결과 블록」, 「알림과 상태(PR2)」, 「실패와 일관성」 이다.

- 알림 저장은 `notification/application/NotificationService.notify` 이고 부르는 쪽 트랜잭션이 필수다(`Propagation.MANDATORY`). 예약 작업 알림 `task/application/TaskNotices.java` 의 모양을 따른다
- 상태 갱신은 `connector/connection/application/ConnectorEventWatches.update` 하나다. 줄을 잠그고 다시 읽은 뒤 쓴다
- 살펴보기의 끝 정리는 `proactive/check/application/ProactiveCheckService.finish` 다. 각 단계는 `recordQuietly` 로 감싼다
- 발견 판정은 `ProactiveCheckResults.answer` 의 `judged`(`FindingKind.NEW` 가 「새로 알릴 것」)다
- 조용한 시간 값은 `task/application/ProactiveQuietHours.java` 의 22시~07시다

**요구사항**: REQ5, REQ6, REQ11

**근거 문서**: `docs/features/proactive/spec.md`, `docs/features/proactive/design.md`, `docs/adr/ADR-20261105-event-watch.md`

## 의도 메모

- 결과 블록에 새 칸을 두지 않는다. 알릴 수는 NEW 발견의 `topicKey` 가 입력 식별자인 것만 센다(설계 DECISION5)
- 알림 제목과 본문에는 수와 원천 이름(플러그인 제목)만 둔다. 발견의 `title` 이나 `facts` 를 넣지 않는다(스펙 DECISION23)
- 다시 연결 알림은 조용한 시간에 내지 않고 07시 뒤 첫 실패 확인에서 낸다(설계 DECISION10). 아침 묶음은 알릴 수가 0 이면 만들지 않는다(설계 DECISION11)
- 새 테이블과 마이그레이션은 없다. `notification.kind` 는 `VARCHAR(32)`, `target_type` 은 `VARCHAR(20)` 이라 새 값이 들어간다

## 작업 항목

### 1. 알림 종류와 갈 곳

- `notification/domain/type/NotificationKind.java` 에 `EVENT_CHECK_REPORTED`, `EVENT_WATCH_FAILED` 를 더한다
- `notification/domain/type/NotificationTargetType.java` 에 `CONNECTIONS`(사용자의 외부 서비스 연결 목록 화면)를 더한다
- `notification/domain/NotificationTarget.java` 와 `Notification.java` 의 「목록 화면만 공개 식별자를 비운다」 판정이 `CONNECTIONS` 도 받게 한다
- 알림 두 enum 은 `StoredEnumNamesTest` 의 고정 대상이 아니다. 고정값은 아래 3 의 `FindingReason` 만 더한다

### 2. 상태 두 칸

`connector/connection/domain/EventWatchState.java` 에 `boolean failureNotified` 와 `Deferred deferred`(record `Deferred(int runs, int newCount, int notifyCount, Long conversationId)`, 없으면 null)를 더한다.
기존 모든 생성 경로(`fresh`, `checked`, `reachable`, `failed`, `consumed`, `ConnectorEventWatches.enable`)가 두 칸을 그대로 옮긴다. `reachable` 은 `failures` 와 함께 `failureNotified` 를 비운다.
`Deferred` 는 아침에 카탈로그를 읽지 못해도 묶음을 내도록 `sourceTitle`, `noun` 도 갖는다.
새 메서드: `defer(int newCount, int notifyCount, Long conversationId, String sourceTitle, String noun)`(더하기), `withoutDeferred()`, `failureNoticed()`, `inZone(ZoneId)`(다시 켜기가 시간대만 바꾼다).
옛 JSON(두 칸 없음)은 `false` 와 null 로 읽힌다. `EventWatchStateTest` 에 이 사례를 더한다.

### 3. 끝났다는 사건과 알릴 수

- 새 값 `proactive/check/application/dto/EventCheckEnded.java`: `(Long userId, Long conversationId, EventBatch batch, int notifyCount, Instant endedAt)`
- `proactive/check/domain/type/FindingReason.java` 에 `NOT_IN_EVENT` 를 더하고, `FindingJudgement.withinEvent(JudgedFinding, Set<String>)` 가 사건 실행의 NEW 발견 가운데 `topicKey` 가 입력 식별자가 아닌 것을 그 까닭의 REFERENCE 로 내린다. `ProactiveCheckResults.answer` 가 사건 실행이면 판정 뒤에 적용한다. `CheckAnswerRenderer` 의 까닭 문구와 `StoredEnumNamesTest` 의 고정값에 더한다
- `ProactiveCheckResults` 가 `notifyCount()` 를 갖는다. `answer` 에서 `check.trigger() == EVENT` 이고 batch 가 있으면 NEW 판정 발견 가운데 `topicKey` 가 `batch.ids()` 안에 있는 것의 서로 다른 수를 적는다. 침묵하거나 블록을 읽지 못하면 0 이다. `ProactiveCheckRun` 이 `event()` 와 `notifyCount()` 를 내준다
- `ProactiveCheckService.finish` 의 `returned` 분기 끝에서 `run.event() != null` 이면 `recordQuietly` 안에서 `EventCheckEnded` 를 낸다. 예외나 멈춤으로 끝난 실행은 내지 않는다
- `EventBatch` Javadoc 의 `{@link EventCheckEnded}` 가 실제 타입을 가리키게 된다

### 4. `task/application/EventWatchNotices.java`(신규)

`@Component`. 의존: `ConnectorEventWatches`, `NotificationService`, `ConversationPublicIdLookup`, `TransactionTemplate`.

- `@EventListener void ended(EventCheckEnded)`: 새 트랜잭션에서 `watches.update(batch.sourceId(), ...)` 로 끝난 시각이 상태 시간대의 조용한 시간이면 `defer` 를 더한다. 조용하지 않고 `notifyCount > 0` 이면 `EVENT_CHECK_REPORTED` 를 만든다. 제목 `새 {noun} {newCount}건 중 {notifyCount}건 확인이 필요해요`, 본문은 `batch.sourceTitle()`, 갈 곳은 그 점검 대화(`CONVERSATION`). 바인딩이 꺼져 update 가 비면 시간대를 몰라 미루지 않고 알릴 항목이 있으면 바로 알린다
- `void flushDeferred(Long bindingId, Long userId, Instant now)`: 부르는 쪽 트랜잭션 안에서, 조용한 시간 밖이고 `deferred` 가 있으면 비우고 `notifyCount > 0` 이면 `밤사이 새 {noun} {newCount}건 중 {notifyCount}건 확인이 필요해요` 한 건을 만든다. 이름은 `deferred` 에 적어 둔 것이다. 갈 곳은 `deferred.conversationId` 의 공개 식별자이고 지워졌으면 null
- `void failed(Long bindingId, Long userId, String sourceTitle, int threshold, Instant now)`: 부르는 쪽 트랜잭션 안에서 실패 수가 정한 횟수 이상이고 `failureNotified` 가 거짓이며 조용한 시간 밖이면 참으로 적고 `EVENT_WATCH_FAILED` 를 만든다. 제목 `{sourceTitle} 다시 연결이 필요해요`, 본문 `외부 서비스 연결에서 다시 연결해 주세요`, 갈 곳 `CONNECTIONS`
- 조용한 시간 판정은 `ProactiveQuietHours` 에 정적 도우미 `quiet(ZoneId, Instant)` 를 두고 기존 `outsideQuietHours` 와 함께 쓴다

### 5. `EventWatchPoller` 와 설정

- `task/config/EventWatchProperties.java` 에 `@DefaultValue("4") int failureThreshold` 를 더하고 0 이하면 기동을 멈춘다. `application.yml` 의 `assistant.event-watch` 에 같은 값과 주석을 더한다
- `check` 의 맨 앞(상태가 있으면, 막힘 판정 전)에서 한 트랜잭션으로 `notices.flushDeferred(...)` 를 부른다
- 실패 분기에서 `failed` 상태 갱신과 `notices.failed(...)` 를 한 트랜잭션에서 부른다. 원천 이름은 선언의 `title` 이다

### 6. 이 phase 를 검증하는 테스트

`backend/src/test/java/com/bifos/assistant/proactive/EventWatchNoticesTest.java`(신규). 준비(대역 카탈로그, `bind`, `listing`, `eventFinding`)는 `EventWatchPollerTest` 에서 새 기반 클래스 `EventWatchTestSupport` 로 옮기고 두 테스트가 이어받는다.

- REQ5: 낮(서울 10시)에 `m1`, `m2` 로 깨우고 답이 `m1` 과 입력 밖 `zz` 를 발견으로 쓰면 알림이 한 건, 제목이 `새 메모 2건 중 1건 확인이 필요해요`, 갈 곳이 그 점검 대화, 제목과 본문에 발견 제목이 없다. 답에서 `zz` 는 「참고」 다. 입력 밖 발견만 쓴 답은 침묵한다. 알릴 것이 없는 답이면 알림이 없다
- REQ6: 서울 23시에 끝난 실행은 알림이 없고 `deferred` 가 차며, 07시 뒤 첫 확인에서 `밤사이` 알림 한 건이 생기고 비워진다. 같은 순간을 UTC 시간대로 켠 바인딩은 조용한 시간이 아니라 곧바로 알린다
- 그 사이 바인딩을 끈 실행은 시간대를 몰라 바로 알린다. 알릴 수가 0 인 아침 묶음은 알림 없이 비운다(설계 DECISION11)
- REQ11: 연결 문제 실패 네 번째에만 `EVENT_WATCH_FAILED` 한 건(갈 곳 `CONNECTIONS`), 다섯째에는 없음, 성공 뒤 다시 네 번이면 한 건 더. 조용한 시간의 넷째 실패는 알리지 않고 07시 뒤 실패에서 한 건

## 검증

```bash
cd backend && ./gradlew test --tests '*EventWatch*' --tests '*StoredEnumNamesTest' --tests '*Notification*'
cd backend && ./gradlew checkstyleMain checkstyleTest spotlessCheck
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/notification/domain/type/NotificationKind.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/notification/domain/type/NotificationTargetType.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/notification/domain/NotificationTarget.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/notification/domain/Notification.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/connector/connection/domain/EventWatchState.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/connector/connection/application/ConnectorEventWatches.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/proactive/check/application/dto/EventCheckEnded.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/proactive/check/application/ProactiveCheckResults.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/proactive/check/application/FindingJudgement.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/proactive/check/application/CheckAnswerRenderer.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/proactive/check/domain/type/FindingReason.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/proactive/EventWatchPollerTest.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/proactive/check/application/ProactiveCheckRun.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/proactive/check/application/ProactiveCheckService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/task/application/EventWatchNotices.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/task/application/EventWatchPoller.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/task/application/ProactiveQuietHours.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/task/config/EventWatchProperties.java` | 수정 |
| `backend/src/main/resources/application.yml` | 수정 |
| `backend/src/test/java/com/bifos/assistant/architecture/StoredEnumNamesTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/connector/connection/EventWatchStateTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/proactive/EventWatchNoticesTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/proactive/EventWatchTestSupport.java` | 신규 |
