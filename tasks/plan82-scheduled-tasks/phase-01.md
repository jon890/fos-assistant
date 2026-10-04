# Phase 01. 예약 작업의 표와 시각 규칙, 작업 API

**Execution profile**: deep

## 목표

예약 작업과 그 시각, 발화 기록의 표를 만들고 작업을 만들고 고치고 멈추고 지우는 API 를 연다. 세 상한(작업 수, 최소 간격)과 시각 계산을 여기서 끝낸다.
대화에 작업을 가리키는 칸을 더하고 대화 목록 응답이 작업 이름을 싣게 한다. 다음 phase 의 발화기가 이 표와 시각 계산 위에서 돈다.

**범위 외**: 발화와 turn 시작, 하루 발화 상한, 작업 알림(phase 02). 화면(phase 03). 다시 하기, 일시 정지 자동 전환, 미리 허락, 에이전트 제안(다음 단계).

## 컨텍스트

**근거 문서**: `docs/backend/task.md` 의 「작업」, 「시각」, 「API」, 「설정」, `docs/backend/schema/task.md` 전체, `docs/adr/ADR-076-예약-작업은-control-plane-이-갖고-발화한-실행은-대화-turn-경로로-돈다.md`, `docs/adr/ADR-078-예약-작업의-결과는-실행마다-새-대화가-기본이고-목록은-작업으로-묶는다.md`, `docs/adr/ADR-079-예약-작업은-사용자당-10개-최소-간격-15분-하루-48번으로-제한한다.md`, `docs/backend/packages.md` 의 「최상위 패키지의 층 순서」.

경로는 `backend/src/main/java/com/bifos/assistant/` 아래를 `B/` 로 줄여 쓴다.

이 브랜치에는 알림 패키지 `B/notification/` 이 이미 있다(층 순서 3번). 이 phase 는 알림을 쓰지 않는다.

따를 기존 패턴:

- 엔티티와 공개 식별자: `B/notification/domain/Notification.java`, `B/connector/domain/ConnectorAction.java` 의 `public_id`(UUID v7, `BINARY(16)`).
- 저장되는 enum 은 `<기능>.domain.type` 에 둔다(`backend/AGENTS.md` 의 「enum 은 저장 여부로 둘 곳을 정한다」).
- 설정 record: `B/notification/application/NotificationProperties.java`(`@Validated`, `@ConfigurationProperties`).
- 현재 사용자와 오류: `CurrentUserProvider.require()`, `new ApiException(ErrorCode, message)`, `B/shared/error/ErrorCode.java`. DTO 는 `presentation/TaskDtos.java` 하나에 둔다. 요청 본문은 `@Valid` 와 jakarta 제약으로 검사한다.
- 에이전트 확인: `B/agent/application/AgentService.java` 의 `Agent requireStartable(CurrentUser user, String code)`(지웠거나 읽을 수 없으면 `AGENT_NOT_FOUND`, 꺼졌으면 `AGENT_DISABLED`). 흐름이 붙은 에이전트는 `B/agent/application/KnownFlows` 의 `known(agent.flow())` 로 판정한다(`FlowRegistry` 가 구현한다). 이 port 는 고치지 않는다.
- 대화 응답: `B/chat/presentation/ChatController.java` 의 `viewOf(Conversation, Agent)` 가 `ChatDtos.ConversationView` 를 만든다. 목록, 단건 조회, `rename`, `chooseModel`, `chooseModelTier` 의 다섯 응답이 같은 함수를 쓴다. 웹은 rename 응답으로 목록의 줄을 통째로 바꾸므로 다섯 응답 모두 작업 칸을 실어야 한다.
- 아래 패키지가 위 패키지의 값을 받는 port: `B/chat/application/AutoTurnResultSource` 를 `connector` 가 구현하고 `ChatService` 가 `List<AutoTurnResultSource>` 로 받는다(ADR-068).
- 트랜잭션은 application 층에만 둔다. 저장소 메서드에 `@Transactional` 을 달면 `ArchitectureRules.TRANSACTIONAL_ONLY_IN_APPLICATION` 에 걸린다. 수정 쿼리(`@Modifying`)는 application 의 트랜잭션 안에서 부른다. `B/notification/application/NotificationCleaner.java` 가 `TransactionTemplate` 으로 감싸는 본보기다.
- 시각 계산은 Spring 의 `org.springframework.scheduling.support.CronExpression` 을 쓴다. 6필드(초 포함)를 받으므로 5필드 앞에 `"0 "` 을 붙여 읽는다. 새 의존을 더하지 않는다.

새 마이그레이션 번호는 `V68` 이다. 구현 전에 `ls backend/src/main/resources/db/migration | sort -V | tail -3` 과 `gh pr list --state open` 으로 다른 브랜치가 V68 을 쥐지 않았는지 확인한다.
마이그레이션 규칙은 `docs/backend/schema/README.md` 의 「마이그레이션 작성 규칙」 이다. 새 표마다 `ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci` 를 적는다. 이 파일은 DDL 만 담는다.
`instruction` 은 `TEXT` 이므로 엔티티에 `columnDefinition = "TEXT"` 로 못 박는다(`backend/AGENTS.md` 의 「엔티티와 마이그레이션은 따로 논다」).

## 의도 메모

- 서버는 cron 만 안다. 「매일」, 「매주」 같은 고르기는 화면이 cron 으로 바꾼다. 저장 형식을 하나로 두기 위해서다.
- 최소 간격은 만들 때 거절한다. 발화할 때 줄이면 사용자가 무엇이 안 되는지 모른다(ADR-079).
- `chat` 은 작업 이름을 모른다. 목록의 작업 이름은 `chat` 의 port 를 `task` 가 구현해 준다.
- `task_trigger` 를 작업 표에 합치지 않는다. 다른 종류의 trigger 를 더할 자리이고, 발화 기록의 유일 제약이 trigger 번호를 쓴다(ADR-077).

## 작업 항목

### 1. 마이그레이션 `backend/src/main/resources/db/migration/V68__task.sql`

`docs/backend/schema/task.md` 의 세 표 `task`, `task_trigger`, `task_run` 을 만들고 `conversation` 에 `task_id BIGINT NULL` 을 더한다.

- `task`: `public_id` 유니크, `owner_user_id` 가 `app_user(id)` 를 가리키는 외래 키, 색인 `(owner_user_id, state)`
- `task_trigger`: `task_id` 유니크, `task_id` 외래 키, 색인 `(next_fire_at)`
- `task_run`: `public_id` 유니크, `(trigger_id, scheduled_for)` 유니크, `task_id` 와 `trigger_id` 외래 키, 색인 `(status, scheduled_for)`, `(owner_user_id, created_at)`, `(task_id, scheduled_for)`
- `conversation`: 색인 `(task_id)`

### 2. `B/task/` 패키지

| 파일 | 내용 |
| --- | --- |
| `domain/Task.java` | 엔티티. 팩토리 `Task.create(Long ownerUserId, Long agentId, String title, String instruction, ConversationMode mode, NotifyPolicy notify, Instant now)` 는 `ACTIVE` 로 만든다. `edit(...)`, `pause(now)`, `resume(now)`, `archive(now)`, `useConversation(Long conversationId, now)` |
| `domain/TaskTrigger.java` | 엔티티. `TaskTrigger.cron(Long taskId, String cronExpr, ZoneId zone, MissedPolicy missed, Instant nextFireAt, Instant now)`, `TaskTrigger.once(Long taskId, Instant fireAt, ZoneId zone, MissedPolicy missed, Instant now)`, `reschedule(...)`, `advance(Instant lastFired, Instant next, Instant now)` |
| `domain/TaskRun.java` | 엔티티. 이 phase 는 칸과 팩토리만 둔다. 상태 전이는 phase 02 가 더한다 |
| `domain/type/TaskState.java` | `ACTIVE`, `PAUSED`, `ARCHIVED` |
| `domain/type/ConversationMode.java` | `NEW_PER_RUN`, `SINGLE` |
| `domain/type/NotifyPolicy.java` | `ALWAYS`, `ON_FAILURE`, `NEVER` |
| `domain/type/TriggerType.java` | `CRON`, `ONCE` |
| `domain/type/MissedPolicy.java` | `RUN_ONCE`, `SKIP` |
| `domain/type/TaskRunStatus.java` | `QUEUED`, `RUNNING`, `SUCCEEDED`, `FAILED`, `CANCELLED`, `SKIPPED` |
| `domain/type/TaskRunReason.java` | `MISSED`, `DAILY_LIMIT`, `PAUSED`, `OWNER_REVOKED`, `AGENT_UNAVAILABLE`, `BUSY`, `FAILED`, `INTERRUPTED` |
| `infra/TaskRepository.java`, `infra/TaskTriggerRepository.java`, `infra/TaskRunRepository.java` | 이 phase 가 쓰는 조회. `countByOwnerUserIdAndStateNot`, `findByPublicIdAndOwnerUserId`, 주인의 보관하지 않은 작업 목록, 작업의 발화 기록 최근 순 |
| `application/TaskSchedule.java` | 아래 「시각 계산」 |
| `application/TaskService.java` | 아래 「서비스」 |
| `application/TaskProperties.java` | `@Validated @ConfigurationProperties("assistant.task")` record. `dispatchCron`, `missedGrace`(2m), `startTimeout`(10m), `maxPerUser`(10, 1 이상), `minInterval`(15m), `maxRunsPerDay`(48, 1 이상), `defaultTimeZone`(`Asia/Seoul`) |
| `application/TaskLabelSource.java` | `chat` 의 port `ConversationTaskLabels` 를 구현한다 |
| `application/model/` 아래 | 서비스가 주고받는 저장되지 않는 값(예: 시각 요청, 작업 보기). 서비스 안에 중첩 타입으로 두지 않는다(`ArchitectureRules.SERVICES_DO_NOT_EXPOSE_NESTED_TYPES`). 타입 하나에 파일 하나 |
| `presentation/TaskController.java` | 아래 API. 경로와 권한과 흐름만 둔다 |
| `presentation/TaskDtos.java` | `TaskRequest`, `ScheduleRequest`, `TaskView`, `ScheduleView`, `TaskRunView` |

#### 시각 계산 `TaskSchedule`

- `static CronExpression parseCron(String fiveFields)`: 공백으로 나눈 필드가 다섯이 아니거나 `CronExpression.parse("0 " + fiveFields)` 가 실패하면 `ApiException(TASK_SCHEDULE_INVALID)`
- `static void requireMinInterval(CronExpression cron, ZoneId zone, Instant from, Duration min)`: `from` 부터 1년 안의 예정 시각을 차례로 펼쳐(최대 400,000번) 이어지는 두 시각의 간격이 `min` 보다 짧으면 `TASK_SCHEDULE_INVALID`
- `static Instant nextAfter(CronExpression cron, ZoneId zone, Instant after)`: `after` 뒤의 첫 예정 시각. 없으면 `null`. 만들거나 시각을 고칠 때 이 값이 `null` 인 cron(예: `0 9 31 2 *`)은 `TASK_SCHEDULE_INVALID` 로 거절한다
- `static Instant latestAtOrBefore(CronExpression cron, ZoneId zone, Instant from, Instant now)`: `from` 이상 `now` 이하의 예정 시각 가운데 가장 늦은 것. 없으면 `null`. phase 02 가 놓친 발화에 쓴다
- `ONCE` 의 `fireAt` 은 시간대 없는 `LocalDateTime`(초는 있어도 없어도 된다)을 그 시간대로 해석한다. 지금보다 뒤가 아니면 `TASK_SCHEDULE_INVALID`
- 시간대 이름을 `ZoneId.of` 가 읽지 못하면 `TASK_SCHEDULE_INVALID`

#### 서비스 `TaskService`

| 메서드 | 동작 |
| --- | --- |
| `create(CurrentUser user, TaskInput input)` | 보관하지 않은 작업 수가 `maxPerUser` 이상이면 `TASK_LIMIT_REACHED`. 에이전트는 `requireStartable` 을 지나야 하고 흐름이 붙었으면 `TASK_AGENT_NOT_SUPPORTED`. 시각을 검사하고 `task` 와 `task_trigger` 를 한 트랜잭션에 저장한다. `next_fire_at` 은 `CRON` 이면 지금 뒤의 첫 시각, `ONCE` 면 그 시각 |
| `update(CurrentUser user, UUID taskId, TaskInput input)` | 작업 수 상한은 보지 않는다. 에이전트 검사는 `create` 와 같다. 시각(종류, cron, fireAt, 시간대)이 저장된 값과 다를 때만 시각을 검사하고 `next_fire_at` 을 다시 계산한다. 그래서 이미 발화한 `ONCE` 작업도 같은 시각을 보내며 이름을 고칠 수 있다. `ARCHIVED` 면 `TASK_NOT_FOUND` |
| `pause`, `resume`, `archive` | `pause` 는 `ACTIVE` 만, `resume` 은 `PAUSED` 만 바꾸고 다른 상태면 그대로 돌려준다. `resume` 은 `next_fire_at` 을 지금 뒤의 첫 시각으로 다시 계산한다. 이미 지난 `ONCE` 는 `next_fire_at` 이 빈 채로 남는다. `archive` 는 `ARCHIVED` 와 `archived_at` |
| `list(CurrentUser user)`, `get(CurrentUser user, UUID taskId)` | 주인의 보관하지 않은 작업만. 남의 것과 없는 것은 같은 `TASK_NOT_FOUND` |
| `runs(CurrentUser user, UUID taskId, int limit)` | `limit` 1 이상 100 이하, 밖이면 `VALIDATION_FAILED`. 예정 시각 역순 |

`Clock` 빈을 생성자로 받는다. 모든 시각은 `clock.instant()` 에서 나온다.

### 3. API `TaskController` (`@RequestMapping("/api/v1/tasks")`)

`docs/backend/task.md` 의 「API」 표 그대로다. `DELETE` 는 204 다.
`TaskRequest(@NotBlank @Size(max = 100) String title, @NotBlank String agentCode, @NotBlank @Size(max = 8000) String instruction, @NotNull @Valid ScheduleRequest schedule, ConversationMode conversationMode, MissedPolicy missedPolicy, NotifyPolicy notify)`. 뒤의 셋은 null 이면 기본값이다. `title` 은 앞뒤 공백을 뗀 뒤 저장한다.
`ScheduleRequest(@NotNull TriggerType type, String cron, LocalDateTime fireAt, String timeZone)`. `CRON` 인데 `cron` 이 비거나 `ONCE` 인데 `fireAt` 이 비면 `TASK_SCHEDULE_INVALID`.
`ScheduleView(TriggerType type, String cron, LocalDateTime fireAt, String timeZone)` 이다. `fireAt` 은 저장한 UTC 시각을 그 작업의 시간대로 바꾼 값이고 `CRON` 이면 null 이다.
`TaskView` 와 `TaskRunView` 의 칸은 `docs/backend/task.md` 의 「API」 가 적은 그대로다. `TaskRunView.conversationId` 는 대화의 공개 식별자이고, 대화 번호를 공개 식별자로 바꿀 때는 `chat` 의 기존 조회를 쓴다. `reason` 은 enum 이름 그대로 싣는다. 화면이 문구로 바꾼다.

### 4. `B/shared/error/ErrorCode.java`

`TASK_NOT_FOUND(NOT_FOUND)`, `TASK_LIMIT_REACHED(CONFLICT)`, `TASK_SCHEDULE_INVALID(BAD_REQUEST)`, `TASK_AGENT_NOT_SUPPORTED(BAD_REQUEST)` 를 더한다.

### 5. 대화에 작업 칸

- `B/chat/domain/Conversation.java`: `task_id` 칸(`Long taskId`, null 허용). 정적 팩토리 `Conversation.startedForTask(Long userId, String title, Long agentId, Long taskId, Instant now)` 를 더한다. 기존 `startedBy` 는 그대로 둔다
- `B/chat/application/ConversationTaskLabels.java`: port. `Map<Long, TaskLabel> labelsOf(Collection<Long> taskIds)`
- `B/chat/application/model/TaskLabel.java`: `record TaskLabel(UUID taskId, String title)`
- `B/chat/presentation/ChatDtos.java` 의 `ConversationView` 끝에 `UUID taskId, String taskTitle` 을 더한다. 작업 대화가 아니면 둘 다 null
- `B/chat/presentation/ChatController.java`: `viewOf` 를 부르는 다섯 응답(목록, 단건, `rename`, `chooseModel`, `chooseModelTier`) 모두 작업 칸을 싣는다. 목록은 그 쪽의 `taskId` 들을 모아 port 로 한 번에 읽고, 나머지는 그 대화 하나의 `taskId` 로 읽는다. 구현이 없을 수 있으므로 `List<ConversationTaskLabels>` 로 받는다. 보관한 작업도 이름을 돌려준다
- `TaskLabelSource` 가 `task` 표에서 번호로 읽어 `public_id` 와 `title` 을 돌려준다

### 6. 층 순서와 설정

- `backend/src/test/java/com/bifos/assistant/architecture/TopLevelPackageOrder.java` 의 `ORDER` 맨 끝(`"connector"` 뒤)에 `"task"` 를 더한다. `task` 는 `chat`, `agent`, `user`, `notification`, `connector` 를 쓸 수 있다
- `backend/src/test/java/com/bifos/assistant/architecture/TopLevelPackageOrderTest.java` 의 개수 단언을 하나씩 올린다(지금 값을 읽고 13→14, 14→15). `allowsTopPackageToUseEveryOtherPackage` 가 맨 위 패키지 이름으로 `"connector"` 를 쓰면 `"task"` 로 바꾼다. 메서드 이름과 `@DisplayName` 의 개수 낱말도 함께 고친다
- `backend/src/main/resources/application.yml` 의 `assistant:` 아래에 `task:` 를 더한다. 키와 기본값은 `docs/backend/task.md` 의 「설정」 표 그대로이고 키마다 한 줄 주석을 단다
- `backend/src/test/resources/application-test.yml` 에 `assistant.task.dispatch-cron: "-"` 를 더한다

### 7. 문서

`docs/backend/packages.md` 의 패키지 표와 「최상위 패키지의 층 순서」(15 `task`), `docs/backend/task.md`, `docs/backend/schema/task.md`, `docs/backend/schema/chat.md` 의 `task_id` 는 계획 커밋이 이미 적었다. 구현이 이 문서들과 다르면 같은 커밋에서 고친다. 고치기 전에 계획 담당에게 알린다.

### 8. 이 phase 를 검증하는 테스트

`backend/src/test/java/com/bifos/assistant/task/` 아래.

| 파일 | 확인하는 것 |
| --- | --- |
| `TaskScheduleTest.java` | `0 9 1 * *` 를 `Asia/Seoul` 로 읽어 `2026-10-04T00:00:00Z` 뒤의 첫 시각이 `2026-11-01T00:00:00Z`(11월 1일 9시)다. `0 9 31 2 *` 는 다음 시각이 없어 `TASK_SCHEDULE_INVALID` 다. 필드가 넷이거나 여섯인 값과 읽지 못하는 값은 `TASK_SCHEDULE_INVALID`. `*/5 * * * *` 와 `0,10 9 * * *` 는 최소 간격 15분에 걸리고 `*/15 * * * *` 와 `0 9 * * 1` 은 통과한다. `latestAtOrBefore` 가 사흘 놓친 매일 9시 작업에서 마지막 하루만 돌려준다. `America/New_York` 의 서머타임 시작일에 없는 2시 30분 cron 이 그날 건너뛰고, 서머타임이 끝나는 2026-11-01 의 `30 1 * * *` 는 서로 다른 두 순간(05:30Z 와 06:30Z)을 낸다. 없는 시간대 이름은 `TASK_SCHEDULE_INVALID` |
| `TaskServiceTest.java` | `Clock.fixed` 로 시각을 고정한다. 만들면 `task` 와 `task_trigger` 가 함께 생기고 `next_fire_at` 이 맞다. 11번째 작업은 `TASK_LIMIT_REACHED`, 하나를 보관하면 다시 만들 수 있다. 지난 `ONCE` 는 `TASK_SCHEDULE_INVALID`. 꺼진 에이전트는 `AGENT_DISABLED`, 흐름 에이전트는 `TASK_AGENT_NOT_SUPPORTED`, 남의 비공개 에이전트는 `AGENT_NOT_FOUND`. 남의 작업은 조회, 고치기, 멈추기, 지우기가 모두 `TASK_NOT_FOUND`. 멈춘 뒤 시계를 사흘 옮겨 다시 켜면 `next_fire_at` 이 그 뒤의 첫 시각이다. 이름만 고치면 `next_fire_at` 이 그대로다. 작업이 10개인 사용자도 기존 작업을 고칠 수 있다. 이미 발화한 `ONCE` 작업은 같은 시각을 보내며 이름을 고칠 수 있다 |
| `TaskControllerTest.java` | 만들기, 목록, 고치기, 멈추기, 다시 켜기, 지우기(204), 발화 기록의 응답 모양. 본문 검증 실패는 `VALIDATION_FAILED`. 형식이 틀린 `taskId` 는 400 |
| `ConversationTaskLabelTest.java` | 작업이 만든 대화가 목록, 단건 조회, 이름 바꾸기 응답에서 `taskId` 와 `taskTitle` 을 싣고, 보통 대화는 둘 다 null 이다. 보관한 작업의 대화도 이름을 싣는다 |

`RepositoryQueryMysqlTest` 가 새 저장소 메서드를 실제 MySQL 에서 스스로 실행한다. 인자를 만들지 못하는 타입이 나오면 `RepositoryQuerySweep` 에 그 타입의 값을 더한다(`backend/AGENTS.md` 의 「저장소 쿼리는 실제 MySQL 에서도 실행한다」).

## 검증

```bash
# cwd: backend/
./gradlew test --tests 'com.bifos.assistant.task.*' --tests 'com.bifos.assistant.architecture.*'
./gradlew test
./gradlew checkstyleMain checkstyleTest
./gradlew spotlessCheck
```

```bash
# cwd: 저장소 root
scripts/check-mysql-migration.sh
node --test 'test/unit/**/*.test.ts'
scripts/check-public-safe.sh
```

기대값: 모두 종료 코드 0. `spotlessCheck` 가 실패하면 기능 커밋 뒤 `./gradlew spotlessApply` 결과를 따로 커밋한다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/resources/db/migration/V68__task.sql` | 신규 |
| `backend/src/main/java/com/bifos/assistant/task/domain/Task.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/task/domain/TaskTrigger.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/task/domain/TaskRun.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/task/domain/type/TaskState.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/task/domain/type/ConversationMode.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/task/domain/type/NotifyPolicy.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/task/domain/type/TriggerType.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/task/domain/type/MissedPolicy.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/task/domain/type/TaskRunStatus.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/task/domain/type/TaskRunReason.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/task/infra/TaskRepository.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/task/infra/TaskTriggerRepository.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/task/infra/TaskRunRepository.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/task/application/TaskSchedule.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/task/application/TaskService.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/task/application/TaskProperties.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/task/application/TaskLabelSource.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/task/application/model/*.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/task/presentation/TaskController.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/task/presentation/TaskDtos.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/domain/Conversation.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/application/ConversationTaskLabels.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/application/model/TaskLabel.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/presentation/ChatDtos.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/presentation/ChatController.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/shared/error/ErrorCode.java` | 수정 |
| `backend/src/main/resources/application.yml` | 수정 |
| `backend/src/test/resources/application-test.yml` | 수정 |
| `backend/src/test/java/com/bifos/assistant/architecture/TopLevelPackageOrder.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/architecture/TopLevelPackageOrderTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/ChatAttachmentTurnTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/ConversationEventControllerTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/ConversationMissingAgentTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/ConversationModelChoiceTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/ConversationPublicIdTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/ModelTierRequestValidationTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/ToolDetailStreamTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/UserExecutionLimitChatTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/task/TaskScheduleTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/task/TaskServiceTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/task/TaskControllerTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/task/ConversationTaskLabelTest.java` | 신규 |
| `docs/backend/packages.md` | 수정 |
| `docs/backend/task.md` | 수정 |
| `docs/backend/schema/task.md` | 수정 |
| `docs/backend/schema/chat.md` | 수정 |
