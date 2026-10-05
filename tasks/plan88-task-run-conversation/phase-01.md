# Phase 01. 발화가 미리 만든 대화를 에이전트가 다르거나 건너뛰면 버린다

**Execution profile**: standard

## 목표

- `QUEUED` 발화가 앞 tick 에 만든 대화의 에이전트가 지금 작업의 에이전트와 다르면 그 대화를 다시 쓰지 않고 새 대화를 만든다
- `NEW_PER_RUN` 발화가 대화를 만든 뒤 `SKIPPED` 로 닫히면, 그 대화에 메시지가 없을 때 대화를 지우고 발화의 대화 칸을 비운다

**범위 외**: `FAILED` 로 닫힌 발화의 대화(실패 알림이 그 대화로 이끈다. 지우지 않는다). `SINGLE` 작업의 대화(다음 발화가 다시 쓴다). 멈추기와 시작의 잠금(다른 계획).

## 컨텍스트

- `backend/src/main/java/com/bifos/assistant/task/application/TaskRunStarter.java`
  - `conversationFor(Task task, TaskRun run, Instant now)`: `run.conversationId()` 가 있고 `conversations.findByIdAndUserIdAndDeletedAtIsNull(run.conversationId(), task.ownerUserId())` 가 있으면 그것을 그대로 쓴다. **에이전트를 보지 않는다.** `SINGLE` 분기의 `reusableConversation(task)` 는 `conversation.agentId().equals(task.agentId())` 로 에이전트를 본다. 같은 거르기를 줄의 대화에도 쓴다
  - `skip(Task task, TaskRun run, TaskRunReason reason, Instant now)`: `run.skip(reason, now)` 와 `notices.announce(task, run)` 만 한다. `prepare` 안의 네 군데(`PAUSED`, `OWNER_REVOKED`, `AGENT_UNAVAILABLE`, `BUSY`)가 부른다. 다른 계획이 `markRunning` 에서도 `skip(..., PAUSED, ...)` 를 부르게 할 수 있다. 정리는 `skip` 안에 두어 어느 경로든 같게 한다
  - 대화는 `chat.startForTask(ownerUserId, agentId, title, taskId)` 가 만든다(`ChatService.startForTask` → `Conversation.startedForTask`)
- 대화 지우기의 선례: `ChatService.deleteCreatedConversation(Long conversationId)` 가 사용자 실행 한도로 거절한 요청의 빈 대화를 `conversations.deleteById` 로 지운다(행을 지운다). `conversation` 을 외래 키로 가리키는 표는 `proactive_check` 하나뿐이고(V69), 예약 작업 대화는 점검 대화가 아니다
- 메시지 저장소: `backend/src/main/java/com/bifos/assistant/chat/infra/ChatMessageRepository.java`. 대화에 메시지가 있는지 보는 메서드가 지금 없다
- `TaskRun.useConversation(Long)` 은 null 을 받지 않는다(`Objects.requireNonNull`). `backend/src/main/java/com/bifos/assistant/task/domain/TaskRun.java`
- `task_run.conversation_id` 는 외래 키가 없다(V68). `docs/backend/schema/task.md` 는 「시작 전에 끝난 줄은 비어 있을 수 있다」 고 이미 적는다
- 층 방향: `task` 는 이미 `chat.application.ChatService` 와 `chat.infra.ConversationRepository` 를 쓴다(`TaskRunStarter` import)

**근거 문서**: `docs/backend/task.md` 의 「시작」 첫 문단 「건너뛴 `NEW_PER_RUN` 줄이 만든 빈 대화는 지운다」 와 표의 「대화를 준비한다」 줄(이 plan 이 이미 고쳤다). `docs/adr/ADR-078-예약-작업의-결과는-실행마다-새-대화가-기본이고-목록은-작업으로-묶는다.md`

## 의도 메모

- 대화를 turn 잠금을 얻은 뒤에 만드는 안은 기각한다. `TurnCancellation.open(userId, conversationId)` 가 대화 번호를 받아 순서를 바꾸려면 잠금 계약을 고쳐야 한다
- 사이드바나 목록 API 에서 빈 대화를 숨기는 안은 기각한다. 사람이 막 만든 빈 대화도 함께 사라진다
- 메시지가 있으면 지우지 않는다. 지금 경로에서는 skip 이 메시지 저장 전에만 일어나지만, 지우기는 되돌릴 수 없어 확인을 둔다
- `deleted_at` 을 적는 대신 행을 지운다. `deleted_at` 은 사용자가 지운 시각이라는 뜻이고, 선례 `deleteCreatedConversation` 도 행을 지운다

## 작업 항목

### 1. 메시지 존재 조회

`ChatMessageRepository` 에 `boolean existsByConversationId(Long conversationId)` 를 더한다. `RepositoryQueryMysqlTest` 가 저장소 메서드를 스스로 찾아 실제 MySQL 에서 실행한다(`backend/AGENTS.md`).

### 2. `ChatService` 에 빈 예약 대화 지우기

`public boolean discardEmptyTaskConversation(Long conversationId)` 를 더한다. 메시지가 없으면 `conversations.deleteById(conversationId)` 하고 true, 있으면 아무것도 하지 않고 false. Javadoc 에 「예약 작업 발화가 미리 만들었다가 쓰지 않은 대화만 부른다」 를 적는다. 부르는 쪽의 트랜잭션 안에서 돈다.

### 3. `TaskRun` 의 대화 칸 비우기

`TaskRun` 에 `public void forgetConversation()` 을 더해 `conversationId` 를 null 로 둔다. Javadoc: 「발화가 미리 만든 빈 대화를 지웠을 때만 부른다」.

### 4. `TaskRunStarter`

- `conversationFor`: 줄의 대화를 다시 쓰는 조건에 `conversation.agentId().equals(task.agentId())` 를 더한다. 조건에 맞지 않고 줄의 대화가 남아 있으며 작업이 `NEW_PER_RUN` 이면 `chat.discardEmptyTaskConversation(run.conversationId())` 를 부른다. 그 뒤는 지금처럼 새 대화를 만들어 `run.useConversation` 한다. `SINGLE` 이면 지우지 않는다(작업의 대화일 수 있다)
- `skip`: `run.skip` 앞에, 작업이 `ConversationMode.NEW_PER_RUN` 이고 `run.conversationId()` 가 있으면 `chat.discardEmptyTaskConversation(...)` 가 true 일 때 `run.forgetConversation()` 한다
- 메서드 Javadoc 을 위 동작에 맞게 고친다

### 5. 이 phase 를 검증하는 검사

`backend/src/test/java/com/bifos/assistant/task/TaskRunStarterTest.java` 에 더한다. 기존 `fixture`, `queued`, `fillSlots`(그 사용자의 turn 자리를 채운다), `awaitFinished`, `StubHermesRunsClient.received()` 를 쓴다. 합성 데이터만 쓴다.

| `@DisplayName` | 순서 | 기대 |
| --- | --- | --- |
| 자리를 얻지 못하고 시작 시간을 넘긴 NEW_PER_RUN 발화의 빈 대화를 지운다 | `fillSlots(owner)` 로 자리를 채우고 `startQueued(NOW)` → 대화가 생기고 `QUEUED` 로 남는다. 이어 `startQueued(NOW + start-timeout)` | 발화가 `SKIPPED`(`BUSY`), `conversationId` 가 null. 첫 tick 이 만든 대화 행이 없다. Hermes 제출 0건 |
| 기다리는 동안 작업을 멈추면 빈 대화를 지운다 | 위처럼 첫 tick 뒤 작업을 `pause` 하고 자리를 비운 뒤 다음 tick | `SKIPPED`(`PAUSED`), 대화 행이 없다 |
| SINGLE 발화가 건너뛰어지면 대화를 지우지 않는다 | `SINGLE` 로 같은 BUSY 순서 | `SKIPPED`(`BUSY`), 작업의 `conversationId` 가 가리키는 대화가 남는다 |
| 기다리는 동안 에이전트를 바꾸면 새 에이전트의 새 대화로 돈다 | 첫 tick 이 대화를 만든 뒤 `tasks` 에서 작업의 에이전트를 같은 주인이 시작할 수 있는 다른 합성 에이전트로 바꾸고(`TaskService.update` 또는 `task.edit`), 자리를 비우고 다음 tick | 발화가 `SUCCEEDED`, 그 대화의 `agentId` 가 새 에이전트다. 첫 대화 행은 지워졌다 |

자리를 비우는 방법과 두 번째 합성 에이전트를 만드는 방법은 같은 파일의 기존 검사를 따른다. 없으면 `fixture` 의 에이전트 만들기를 그대로 한 번 더 쓴다.

## 검증

```bash
cd backend && ./gradlew test --tests 'com.bifos.assistant.task.*' --tests 'com.bifos.assistant.chat.*'
scripts/check-mysql-migration.sh
scripts/check-local.sh tasks
```

- 첫 줄: 새 검사 넷과 기존 작업, 대화 검사가 통과한다
- 둘째 줄: 새 저장소 메서드가 실제 MySQL 에서 돈다
- 셋째 줄: 전체 로컬 검사. 브라우저 검사는 `test/browser/tasks.spec.ts` 만 돈다

## 변경 파일

| 파일 | 변경 |
| --- | --- |
| `backend/src/main/java/com/bifos/assistant/chat/infra/ChatMessageRepository.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/application/ChatService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/task/domain/TaskRun.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/task/application/TaskRunStarter.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/task/TaskRunStarterTest.java` | 수정 |
| `docs/backend/task.md` | 수정 |
