# Phase 02. 「보고할 것 없음」 완료

**Execution profile**: standard

## 목표

예약 작업 turn 의 답 전체가 `[SILENT]` 면 그 발화를 `SUCCEEDED`(`NOTHING_TO_REPORT`)로 닫고 알림을 만들지 않으며, `NEW_PER_RUN` 대화는 목록에서 숨긴다.
새 결과가 없는 날 알림과 사이드바를 조용하게 두기 위해서다.

**범위 외**: 작업의 모델 단계(phase 01), 화면 문구(phase 03).

## 컨텍스트

**근거 문서**: `docs/backend/task.md` 의 「시작」 의 끝난 모양 표, 「보고할 것 없음」, 「알림」, 「API」 절. `docs/backend/schema/task.md` 의 `task_run.reason`. `docs/backend/schema/chat.md` 의 `conversation.hidden_at`. `docs/backend/conversation.md` 의 대화 목록 경로. `docs/adr/ADR-20261008-cron-to-task.md`.

- turn 결과를 적는 곳: `backend/src/main/java/com/bifos/assistant/task/application/TaskRunStarter.java` 의 `runTurn` 과 `finishSafely(Long runId, Long executionId, boolean cancelled, boolean failed)`. `runTurn` 은 `ChatTurn turn` 을 받고 `turn.assistantText()` 가 마지막 답 글이다(`chat/application/ChatTurn.java`)
- 발화 엔티티: `task/domain/TaskRun.java` 의 `succeed(Long executionId, Instant now)` 와 private `finish(...)`
- 까닭 enum: `task/domain/type/TaskRunReason.java`. DB 에 이름 그대로 저장되고 칸 길이는 32 다
- 알림: `task/application/TaskNotices.java` 의 `announce(Task, TaskRun)`
- 대화 목록: `chat/application/ChatConversationQueries.java` 의 `conversationsOf` 가 `ConversationRepository.findByUserIdAndDeletedAtIsNullOrderByUpdatedAtDescIdDesc` 와 `findPageAfter` 를 부른다. 이 둘 말고 이 메서드들을 부르는 곳은 없다
- 사용자 질문 저장: `chat/application/ChatTurnRouting.java` 의 `TurnIntent.Fresh` 갈래. 같은 트랜잭션에서 `conversationWriter.resetAutoTurns(conversation.id())` 를 부른다
- 조건부 update 의 트랜잭션 경계: `chat/application/ConversationWriter.java`. 각 메서드가 `ConversationRepository` 의 같은 이름 메서드를 감싼다
- 가짜 Hermes 의 답: `TaskRunStarterTest.setUp` 의 `stub().willReturn(HermesRunResult.of("run-task", "session", "completed", "정리한 답", ...))`. 시험에서 답 글만 바꿔 다시 부른다

## 의도 메모

- 표시는 `[SILENT]` 다. Hermes cron 이 쓰던 표시라 옮기는 지시문을 그대로 쓸 수 있다. 판정은 `assistantText` 가 null 이 아니고 `strip()` 한 값이 정확히 `[SILENT]` 일 때만이다. 대소문자를 무시하지 않는다
- 사용자가 중지(`turn.cancelled()`)했거나 실패한 turn 에는 쓰지 않는다
- **숨김은 대화 엔티티 메서드로 적는다.** `finishSafely` 의 트랜잭션은 `run` 을 잠그고 바꾼 뒤 `notices.announce` 에 넘긴다. `clearAutomatically = true` 인 update 질의를 쓰면 그 사이 `run` 이 분리된다
- 숨김 해제는 사용자 질문 저장과 같은 트랜잭션에서 하는 update 질의다. `resetAutoTurns` 와 같은 모양(`@Modifying` 만, `clearAutomatically` 없음)으로 둔다
- 대화를 지우지 않는다. `deleted_at` 은 조회와 보내기까지 막아 실행 기록에서 열 수 없게 된다
- `SINGLE` 대화는 숨기지 않는다

## 작업 항목

### 1. 마이그레이션 `V20261008104900__conversation_hidden_at.sql`

`ALTER TABLE conversation ADD COLUMN hidden_at DATETIME(6) NULL;` 한 문장과 머리 주석 한 줄. 색인은 더하지 않는다. 목록은 `(user_id, deleted_at, updated_at, id)` 색인으로 읽고 숨긴 줄은 그 뒤에 거른다.

### 2. `Conversation`

- `@Column(name = "hidden_at") private Instant hiddenAt;` 와 Javadoc. `@Getter`
- `public void hideFromList(Instant now)`: 이미 찼으면 그대로 둔다. `updatedAt` 은 바꾸지 않는다

### 3. `ConversationRepository` 와 `ConversationWriter`

- `findByUserIdAndDeletedAtIsNullOrderByUpdatedAtDescIdDesc` 를 `findByUserIdAndDeletedAtIsNullAndHiddenAtIsNullOrderByUpdatedAtDescIdDesc` 로 바꾸고 `ChatConversationQueries.conversationsOf` 의 호출을 고친다
- `findPageAfter` 의 where 에 `and c.hiddenAt is null` 을 더한다
- `@Modifying @Query("update Conversation c set c.hiddenAt = null where c.id = :id and c.hiddenAt is not null") int showInList(@Param("id") Long id);` 를 더한다. Javadoc 에 사용자가 질문하면 숨긴 대화를 다시 목록에 보인다고 적는다
- `ConversationWriter` 에 `@Transactional public int showInList(Long id)` 위임을 더한다

### 4. 질문 저장에서 숨김을 푼다

`ChatTurnRouting` 의 `TurnIntent.Fresh` 갈래에서 `conversationWriter.resetAutoTurns(conversation.id());` 바로 다음 줄에 `conversationWriter.showInList(conversation.id());` 를 더한다. `TurnIntent.Scheduled` 갈래에는 더하지 않는다.

### 5. 대화 숨기기 경로

- `ChatConversationManagement` 에 `void hideTaskConversation(Long conversationId, Instant now)` 를 더한다. `conversations.findById(conversationId)` 가 있으면 `hideFromList(now)` 를 부른다
- `ChatService` 에 같은 이름의 public 위임 메서드를 더한다

### 6. 발화 결과

- `TaskRunReason` 에 `NOTHING_TO_REPORT` 를 더한다. Javadoc: 답 전체가 `[SILENT]` 라 알릴 것 없이 끝냈다. 클래스 Javadoc 의 「`SKIPPED` 와 `FAILED` 의 까닭이다」 를 `SUCCEEDED` 의 이 까닭까지 포함하게 고친다
- `TaskRun` 에 `public void succeedQuietly(Long executionId, Instant now)` 를 더한다. `finish(TaskRunStatus.SUCCEEDED, TaskRunReason.NOTHING_TO_REPORT, executionId, now)` 다. 기존 `succeed` 를 따라 쓴다
- `TaskRunStarter` 에 `static final String NOTHING_TO_REPORT = "[SILENT]";` 를 둔다. `runTurn` 이 `finishSafely` 에 `turn.assistantText()` 를 넘기게 시그니처를 `finishSafely(Long runId, Long executionId, String answer, boolean cancelled, boolean failed)` 로 바꾸고 다른 호출부 둘(`start` 의 스레드 실패, `runTurn` 의 예외)은 `null` 을 넘긴다
- `finishSafely` 안: `failed` 도 `cancelled` 도 아니고 `answer != null && NOTHING_TO_REPORT.equals(answer.strip())` 면 `run.succeedQuietly(executionId, now)`. 그 작업이 `ConversationMode.NEW_PER_RUN` 이고 `run.conversationId() != null` 이면 같은 트랜잭션에서 `chat.hideTaskConversation(run.conversationId(), now)` 를 부른다. 작업은 이미 읽는 `tasks.findById(run.taskId())` 를 한 번만 읽어 숨김 판정과 `notices.announce` 에 함께 쓴다

### 7. 알림

`TaskNotices.announce` 의 `SUCCEEDED` 갈래에서 `run.reason() == TaskRunReason.NOTHING_TO_REPORT` 면 아무것도 만들지 않는다.

### 8. 이 phase 를 검증하는 시험

- `backend/src/test/java/com/bifos/assistant/task/TaskRunStarterTest.java` 에 넷:
  - 답이 `"  [SILENT]\n"` 인 `NEW_PER_RUN`, `ALWAYS` 발화: `SUCCEEDED`, `reason` 이 `NOTHING_TO_REPORT`, 실행 번호가 있다. 그 사용자의 알림이 없다. 대화의 `hiddenAt()` 이 찼고 `deletedAt()` 은 비어 있다
  - 같은 답의 `SINGLE` 발화: `NOTHING_TO_REPORT` 이고 알림이 없으며 대화의 `hiddenAt()` 은 비어 있다
  - 답이 `"[SILENT] 새 영상 없음"` 이면 보통 `SUCCEEDED`(`reason` null)와 `TASK_SUCCEEDED` 하나다
  - 답이 `"[silent]"` 이면 보통 완료다
- `backend/src/test/java/com/bifos/assistant/chat/ConversationPagingTest.java` 에 하나: 대화 셋 가운데 하나에 `hideFromList` 를 적어 저장하면 `ChatService` 의 목록 첫 쪽에 둘만 오고, 이 파일의 기존 다음 쪽 요청 방식으로 읽은 쪽에도 숨긴 대화가 없다. 숨긴 대화를 `ConversationWriter.showInList` 로 풀면 다시 목록에 온다
- `backend/src/test/java/com/bifos/assistant/chat/ChatServiceTest.java` 에 하나: 숨긴 대화에 사용자 질문을 보내면(이 파일의 기존 보내기 방식) 그 대화의 `hiddenAt()` 이 비고 목록에 다시 보인다

## 검증

```bash
cd backend && ./gradlew test --tests 'com.bifos.assistant.task.TaskRunStarterTest' --tests 'com.bifos.assistant.chat.ConversationPagingTest' --tests 'com.bifos.assistant.chat.ChatServiceTest' --tests 'com.bifos.assistant.task.TaskRunRecoveryTest'
cd backend && ./gradlew checkstyleMain checkstyleTest spotlessCheck
bash scripts/check-mysql-migration.sh
```

`check-mysql-migration.sh` 는 Docker 가 있어야 돈다. 없으면 건너뛰었다고 보고한다.
그 스크립트의 `RepositoryQueryMysqlTest` 가 이름을 바꾼 목록 메서드와 `showInList` 를 실제 MySQL 에서 한 번씩 돌린다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/resources/db/migration/V20261008104900__conversation_hidden_at.sql` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/domain/Conversation.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/infra/ConversationRepository.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/application/ConversationWriter.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/application/ChatConversationQueries.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/application/ChatTurnRouting.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/application/ChatConversationManagement.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/application/ChatService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/task/domain/TaskRun.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/task/domain/type/TaskRunReason.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/task/application/TaskRunStarter.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/task/application/TaskNotices.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/task/TaskRunStarterTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/ConversationPagingTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/ChatServiceTest.java` | 수정 |
