# Phase 02. 발화기와 예약 turn, 작업 알림

**Execution profile**: deep

## 목표

정해 둔 간격마다 시각이 된 작업의 발화를 `task_run` 줄로 한 번만 만들고, 그 줄을 작업 주인의 대화 turn 으로 연다. 끝나면 결과를 적고 작업의 알림 설정대로 알린다.
서버를 다시 띄워도 같은 예정 시각의 발화는 하나여야 하고, 사람 없이 도는 turn 의 쓰기 도구는 기존 승인을 그대로 거쳐야 한다.

**범위 외**: 작업을 만들고 고치는 API(phase 01). 화면(phase 03). 실패의 다시 하기와 일시 정지 자동 전환, 작업 범위 미리 허락(다음 단계).

## 컨텍스트

**근거 문서**: `docs/backend/task.md` 의 「발화와 시작」, 「알림」, 「설정」, `docs/adr/ADR-071-예약-작업은-control-plane-이-갖고-발화한-실행은-대화-turn-경로로-돈다.md`, `docs/adr/ADR-072-발화는-trigger-와-예정-시각의-유일-제약으로-한-번만-만들고-놓친-발화는-작업마다-정한다.md`, `docs/adr/ADR-074-예약-작업은-사용자당-10개-최소-간격-15분-하루-48번으로-제한한다.md`, `docs/backend/notification.md`, `docs/backend/turn-control.md` 의 「기동할 때 남은 실행 정리」.

경로는 `backend/src/main/java/com/bifos/assistant/` 아래를 `B/` 로 줄여 쓴다. phase 01 이 만든 `B/task/` 의 엔티티, 저장소, `TaskSchedule`, `TaskProperties` 를 쓴다. 구현 전에 그 파일들을 읽어 이름을 맞춘다.

사람 없이 turn 을 여는 기존 경로가 본보기다. `B/chat/application/DelegationWakeService.java` 의 `tryWake` 와 `runAutoTurn`:

- 대화와 에이전트와 주인을 확인하고, `TurnCancellation.open(Long userId, Long conversationId)` 로 잠금과 사용자 자리를 얻는다. `CONVERSATION_BUSY` 와 `USER_BUSY` 는 `ApiException` 으로 온다
- 잠금을 얻으면 `new CurrentUser(owner.id(), owner.email(), owner.displayName(), owner.groupId(), owner.role())` 를 만들고 `Thread.ofVirtual().name(...).start(...)` 로 turn 을 돌린다. 스레드를 띄우지 못하면 `turns.close(handle)`
- turn 은 `ChatService.runDelegationResults(owner, conversationId, handle, event -> hub.publish(conversationId, event))` 이고 `finally` 에서 `turns.close(handle)`
- 대화 SSE 는 `B/chat/application/ConversationEventHub.publish(Long conversationId, ChatEvent)`

turn 의 저장은 `B/chat/application/ChatService.java` 의 `saveQuestion` 이 `TurnIntent` 종류로 나눈다. `TurnIntent.Fresh` 분기가 제목 채우기(`fillBlankTitle`), `ChatMessage.fromUser`, `conversationWriter.resetAutoTurns` 를 한 트랜잭션에 하고, `DelegationResults` 분기가 `ChatMessage.fromSystem` 알림 줄과 `ChatEvent.system` 을 낸다. `TurnIntent.appendTo` 가 intent 별 지시를 instructions 뒤에 붙인다.
`ChatService.runTurn(...)` 은 `ChatTurn(conversationId, conversationPublicId, executionId, assistantText, messageId, cancelled)` 을 돌려준다. `runDelegationResults` 가 `route(...)` 결과의 `flow()` 를 보고 흐름이면 돌리지 않는 모습을 따른다.

주인이 허용 목록에서 꺼졌는지는 `B/user/application/SignInRevocation` 의 `revoked(String email)` 로 본다(ADR-059, `AllowedUserResolver` 가 같은 것을 쓴다). 에이전트는 `B/agent/application/AgentService.requireStartable(CurrentUser, String code)` 와 phase 01 이 쓴 흐름 판정을 다시 한다.

알림은 `B/notification/application/NotificationService.notify(Long userId, NotificationKind kind, String title, String body, NotificationTarget target)` 이고 부르는 쪽 트랜잭션 안에서만 부를 수 있다(`Propagation.MANDATORY`).

기동 순서는 `@EventListener(ApplicationReadyEvent)` 와 `@Order` 로 정한다. `RestartReconciler` 가 0, `ConnectorActionSweeper` 가 5, `NextTurnDispatcher` 가 10 이다.

- 트랜잭션은 application 층에만 둔다. 저장소 메서드에 `@Transactional` 을 달면 `ArchitectureRules.TRANSACTIONAL_ONLY_IN_APPLICATION` 에 걸린다. 수정 쿼리(`@Modifying`)는 application 의 트랜잭션 안에서 부른다. `B/notification/application/NotificationCleaner.java` 가 `TransactionTemplate` 으로 감싸는 본보기다.
예약 작업의 cron 은 test yml 에서 `"-"` 로 꺼져 있다(phase 01). 검사는 발화기의 메서드를 고정한 시각으로 직접 부른다. 예약 실행 틀은 `B/chat/application/AttachmentCleaner.java`(`runScheduled()` 가 `clock.instant()` 로 본체를 부른다)를 따른다.
검사의 가짜 Hermes 는 `backend/src/test/java/com/bifos/assistant/hermes/StubHermesRunsClient.java` 와 `ChatServiceTest.StubRuntime` 이다. 사용자 자리 포화는 `DelegationWakeUserLimitTest` 가 만드는 방식을 본다.

## 의도 메모

- 발화와 시작을 나눈다. 발화는 짧은 트랜잭션 하나이고, turn 은 Hermes 를 부르는 긴 일이라 트랜잭션 밖에서 연다(ADR-072).
- 예약 turn 을 위한 판정이나 허락을 따로 두지 않는다. `ChatService` 의 기존 turn 경로를 그대로 탄다. 그래서 커넥터 도구 판정과 승인 줄과 `APPROVAL_REQUESTED` 알림이 사람이 보낸 turn 과 같다(ADR-071).
- `RUNNING` 으로 남은 줄은 기동할 때 다시 돌리지 않는다. 쓰기가 두 번 일어날 수 있다.
- `QUEUED` 줄이 열리지 못해도 대화를 다시 만들지 않는다. 처음 만든 대화 번호를 줄에 적어 둔다.

## 작업 항목

### 1. `chat` 에 예약 turn 을 더한다

- `B/chat/application/TurnIntent.java`: `record Scheduled(String notice) implements TurnIntent`. `SCHEDULED_INSTRUCTION` 상수 「예약 작업으로 연 turn 이다. 사용자는 지금 화면에 없을 수 있다. 사용자의 승인이 필요한 도구는 승인 요청을 남기고 답을 마친다.」 를 두고 `instructionFor` 가 돌려준다
- `B/chat/application/ChatService.java`:
  - `public ChatTurn runScheduledTurn(CurrentUser owner, Long conversationId, TurnHandle handle, String notice, String instruction, Consumer<ChatEvent> onEvent)`. `route(...)` 가 흐름을 돌려주면 `ApiException(TASK_AGENT_NOT_SUPPORTED)` 를 던진다. 아니면 `runTurn(owner, routed, instruction, new TurnIntent.Scheduled(notice), onEvent, true, handle)` 을 돌리고 끝에 `done` 이나 `stopped` 사건을 낸 뒤 `ChatTurn` 을 돌려준다(`runDelegationResults` 의 끝과 같다)
  - `saveQuestion` 의 `Scheduled` 분기: 한 트랜잭션에서 `ChatMessage.fromSystem(notice)` 저장, `fillBlankTitle(conversation, instruction)`, `ChatMessage.fromUser(conversation.id(), user.id(), instruction, now)` 저장, `conversationWriter.resetAutoTurns`. 그 뒤 `ChatEvent.system` 과 `ChatEvent.user` 를 낸다
  - `public Conversation startForTask(Long ownerUserId, Long agentId, String title, Long taskId)`: `Conversation.startedForTask(...)` 를 저장해 돌려준다
- `runTurn` 과 `saveQuestion` 안에서 `TurnIntent.Fresh` 만 보고 갈리는 다른 곳(예: 제목, 대기 메시지, 다시 생성 판정)을 `grep -n "TurnIntent" B/chat/application/ChatService.java` 로 모두 찾고, `Scheduled` 가 사람이 보낸 질문과 같게 다뤄져야 하는 곳은 같게 둔다. 바꾼 곳을 회신에 적는다

### 2. `B/task/application/TaskFiring.java` 발화

`public int fireDue(Instant now)`. 트랜잭션을 메서드 하나에 걸지 않는다.

- 먼저 잠그지 않고 `next_fire_at <= now` 이고 작업이 `ACTIVE` 인 trigger 번호를 `next_fire_at` 순으로 읽는다
- **trigger 마다 `TransactionTemplate` 트랜잭션 하나**에서 `TaskTriggerRepository` 의 `@Lock(PESSIMISTIC_WRITE)` 조회로 그 줄을 다시 읽고, 조건이 아직 맞으면 처리한다. trigger 하나의 예외는 잡아 로그를 남기고 다음 trigger 로 간다. 그 trigger 만 되돌아가고 다음 tick 에 다시 시도된다
- trigger 마다 `docs/backend/task.md` 의 「발화」 표 그대로 처리한다. 예정 시각은 `TaskSchedule.latestAtOrBefore` 로 구한다
- 하루 상한: `TaskRunRepository` 에 `owner_user_id` 와 `created_at > now - 24h` 와 `status <> SKIPPED` 로 세는 쿼리를 더한다. `maxRunsPerDay` 이상이면 `SKIPPED`(`DAILY_LIMIT`)
- 같은 `(trigger_id, scheduled_for)` 가 있는지 먼저 보고 있으면 만들지 않는다. 유일 제약이 마지막 방어선이다
- 같은 트랜잭션에서 trigger 의 `last_fired_at` 과 `next_fire_at` 을 옮긴다
- `SKIPPED` 를 만들고 알릴 것이면 같은 트랜잭션에서 아래 3의 알림 도우미를 부른다

### 3. `B/task/application/TaskNotices.java` 알림 도우미

작업과 `task_run` 을 받아 `docs/backend/task.md` 의 「알림」 표대로 `NotificationService.notify` 를 부른다. `NotifyPolicy` 를 지킨다. 대상은 대화가 있으면 `CONVERSATION`(대화 공개 식별자), 없으면 `TASK`(작업 공개 식별자)다.
까닭 한 줄은 같은 문서의 표 그대로 둔다.

`B/notification/domain/type/NotificationKind.java` 에 `TASK_SUCCEEDED`, `TASK_FAILED`, `TASK_SKIPPED` 를, `NotificationTargetType.java` 에 `TASK` 를 더한다. `notification` 은 `task` 를 모른다. 값만 더한다.

### 4. `B/task/application/TaskRunStarter.java` 시작

`public int startQueued(Instant now)`. `QUEUED` 줄을 예정 시각 순으로 읽어 줄마다 `docs/backend/task.md` 의 「시작」 표를 차례로 본다. 줄 하나의 실패가 다른 줄을 막지 않게 줄마다 예외를 잡고 로그를 남긴다.

- 상태 판정과 `SKIPPED` 기록과 알림은 줄을 잠그고 다시 읽는 짧은 트랜잭션에서 한다
- 대화 준비: `NEW_PER_RUN` 은 줄의 `conversation_id` 가 비면 `ChatService.startForTask(...)` 로 만들어 줄에 적는다. `SINGLE` 은 작업의 대화가 있고 지워지지 않았고 에이전트가 같으면 그것을, 아니면 새로 만들어 작업과 줄에 적는다. 대화 제목은 작업 이름이다
- `TurnCancellation.open(owner.id(), conversationId)`. `CONVERSATION_BUSY`, `USER_BUSY` 면 `QUEUED` 로 둔다
- 잠금을 얻으면 짧은 트랜잭션에서 `RUNNING` 과 `started_at` 을 적는다. 그 저장이 실패하면 `turns.close(handle)` 하고 넘어간다
- 가상 스레드 `task-run-{id}` 에서 `chat.runScheduledTurn(owner, conversationId, handle, "예약 작업 「" + title + "」 을 시작했어요", instruction, event -> hub.publish(conversationId, event))` 를 부른다. 결과를 받으면 `SUCCEEDED`(취소면 `CANCELLED`)와 `execution_id`, 예외면 `FAILED`(`FAILED`)를 적고 알린다. `finally` 에서 `turns.close(handle)`
- 같은 줄을 두 tick 이 동시에 열지 않는다. 시작 단계는 발화기와 같은 예약 작업에서 차례로 돌고, `RUNNING` 으로 바꾸는 트랜잭션이 `QUEUED` 인지 다시 확인한다

`TaskRun` 엔티티에 `start(now)`, `succeed(executionId, now)`, `cancel(executionId, now)`, `fail(reason, now)`, `skip(reason, now)`, `useConversation(conversationId)` 를 더한다.

### 5. `B/task/application/TaskDispatcher.java` 와 기동 정리

- `@Scheduled(cron = "${assistant.task.dispatch-cron}") public void runScheduled()` 가 `tick(clock.instant())` 를 부른다. `tick` 은 `fireDue` 뒤에 `startQueued` 를 부르고, 둘의 예외를 따로 잡는다
- `@Scheduled` 는 `ApplicationReadyEvent` 보다 먼저 돌기 시작한다. `TaskRunRecovery` 가 끝나기 전에는 `runScheduled` 가 아무것도 하지 않게 한다(`TaskRunRecovery` 가 끝나면 켜는 `AtomicBoolean`). 그 전에 연 줄을 정리가 닫지 않게 하기 위해서다
- `B/task/application/TaskRunRecovery.java`: `@EventListener(ApplicationReadyEvent.class) @Order(20)` 에서 `RUNNING` 줄을 `FAILED`(`INTERRUPTED`)로 닫고 알린다

### 6. e2e 와 설정

- `test/e2e/run.ts` 의 Control Plane 환경(`ASSISTANT_CONNECTOR_POLICY_EXPIRE_CRON` 이 있는 곳)에 `ASSISTANT_TASK_DISPATCH_CRON: "* * * * * *"` 를 더한다
- `test/e2e/scenarios/scheduled-task.ts`: 아래 「테스트」 의 e2e. `test/e2e/run.ts` 의 `SCENARIOS` 에서 `notifications` 시나리오 뒤에 넣는다

### 7. 문서

`docs/backend/task.md`, `docs/backend/notification.md` 의 `TASK` 대상, `docs/backend/turn-control.md` 의 「기동할 때 남은 실행 정리」 는 계획 커밋이 이미 적었다. 구현이 이 문서들과 다르면 같은 커밋에서 고친다. 고치기 전에 계획 담당에게 알린다.

### 8. 이 phase 를 검증하는 테스트

`backend/src/test/java/com/bifos/assistant/task/` 아래. 시각은 테스트가 움직일 수 있는 `Clock`(예: `ChatServiceTest.TestClock` 과 같은 모양)으로 고정한다.

| 파일 | 확인하는 것 |
| --- | --- |
| `TaskFiringTest.java` | `0 9 1 * *` `Asia/Seoul` 작업에서 시계를 `2026-11-01T00:00:30Z` 로 두고 `fireDue` 하면 `QUEUED` 하나와 `next_fire_at = 2026-12-01T00:00:00Z`. 같은 시각으로 다시 불러도 하나. **발화를 만든 뒤 `next_fire_at` 을 옮기기 전에 서버가 내려간 것처럼** trigger 의 `next_fire_at` 을 `2026-11-01T00:00:00Z` 로 되돌린 뒤 `fireDue` 를 다시 불러도 줄은 하나이고 예외가 없으며 `next_fire_at` 은 다시 12월 1일이다. 예외를 내는 trigger(예: 저장된 시간대 이름이 틀린 줄)와 정상 trigger 를 함께 두면 정상 trigger 는 발화한다. 사흘 늦게 부르면 `RUN_ONCE` 는 마지막 예정 시각 하나, `SKIP` 은 `SKIPPED`(`MISSED`) 하나이고 알림이 없다. 멈춘 작업은 발화하지 않는다. 그 사용자의 24시간 안 발화가 48개면 `SKIPPED`(`DAILY_LIMIT`)와 `TASK_SKIPPED` 알림. `ONCE` 는 한 번 뒤 `next_fire_at` 이 빈다 |
| `TaskRunStarterTest.java` | 가짜 Hermes 로 `QUEUED` 를 열면 `SUCCEEDED` 와 `execution_id` 가 차고, 새 대화의 `task_id` 와 제목이 작업이고, 메시지가 SYSTEM 알림 줄, USER 지시, ASSISTANT 답 순이고 `auto_turn_count` 가 0 이다. `ALWAYS` 면 `TASK_SUCCEEDED` 하나, `ON_FAILURE` 면 없음. 사용자 자리를 채워 두면 `QUEUED` 로 남고 대화가 하나만 생긴다. 사용자 자리를 채운 채 줄을 만든 때에서 10분이 지나면 `SKIPPED`(`BUSY`)와 알림. 놓친 발화로 예정 시각보다 30분 늦게 만든 `QUEUED` 줄은 바로 열린다. 에이전트를 끄면 `SKIPPED`(`AGENT_UNAVAILABLE`). 주인을 허용 목록에서 끄면 `SKIPPED`(`OWNER_REVOKED`)이고 알림이 없다. 멈춘 작업의 `QUEUED` 는 `SKIPPED`(`PAUSED`). Hermes 가 실패하면 `FAILED` 와 `TASK_FAILED`. `SINGLE` 은 두 번째 발화가 같은 대화에 이어진다 |
| `TaskRunRecoveryTest.java` | `RUNNING` 줄이 기동 정리에서 `FAILED`(`INTERRUPTED`)가 되고 알림이 하나 생긴다. `QUEUED` 줄은 그대로다. 정리가 끝나기 전에 `runScheduled` 를 부르면 아무 줄도 만들거나 열지 않는다 |

`test/e2e/scenarios/scheduled-task.ts`:

- `test/e2e/scenarios/connector-policy.ts` 와 `notifications.ts` 가 승인이 필요한 커넥터 도구를 부르게 하는 방식(가짜 Hermes 의 probe 문구, 연결 등록, 정책 hook)을 읽고 같은 방법을 쓴다
- 그 커넥터 에이전트로 `ONCE` 작업을 지금부터 3초 뒤(`timeZone: "UTC"`)로 만든다. 가짜 Hermes 는 입력이 `CONNECTOR_TOOL_PROBE` 문구로 **시작할 때만** probe 로 본다(`test/e2e/fake-hermes.ts`). 지시의 맨 앞에 그 문구를 둔다
- `GET /api/v1/tasks/{id}/runs` 를 짧은 간격으로 읽어 한 줄이 `SUCCEEDED` 가 되기를 기다린다(상한 60초)
- 그 줄의 대화의 승인 줄 목록에 `PENDING` 이 있고, 알림 목록에 그 대화를 가리키는 `APPROVAL_REQUESTED` 와 `TASK_SUCCEEDED` 가 있다
- 그 승인 줄을 승인하면 기존 승인 경로대로 실행되고 결과가 그 대화에 이어진다. e2e 의 승인 만료는 15초다(`test/e2e/run.ts` 의 `ASSISTANT_CONNECTOR_POLICY_APPROVAL_TTL`). `SUCCEEDED` 를 본 뒤 바로 승인한다
- 발화 기록은 여전히 한 줄이다
- 이웃 시나리오처럼 `finally` 에서 정책 hook 을 되돌리고 연결을 해제하고 작업을 지운다

## 검증

```bash
# cwd: backend/
./gradlew test --tests 'com.bifos.assistant.task.*' --tests 'com.bifos.assistant.chat.*' --tests 'com.bifos.assistant.notification.*'
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

기대값: 모두 종료 코드 0. `spotlessCheck` 가 실패하면 기능 커밋 뒤 `./gradlew spotlessApply` 결과를 따로 커밋한다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/chat/application/TurnIntent.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/application/ChatService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/task/domain/TaskRun.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/task/domain/Task.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/task/infra/TaskTriggerRepository.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/task/infra/TaskRunRepository.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/task/application/TaskFiring.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/task/application/TaskNotices.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/task/application/TaskRunStarter.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/task/application/TaskDispatcher.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/task/application/TaskRunRecovery.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/notification/domain/type/NotificationKind.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/notification/domain/type/NotificationTargetType.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/task/TaskFiringTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/task/TaskRunStarterTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/task/TaskRunRecoveryTest.java` | 신규 |
| `test/e2e/scenarios/scheduled-task.ts` | 신규 |
| `test/e2e/run.ts` | 수정 |
| `docs/backend/task.md` | 수정 |
| `docs/backend/turn-control.md` | 수정 |
| `docs/backend/notification.md` | 수정 |
