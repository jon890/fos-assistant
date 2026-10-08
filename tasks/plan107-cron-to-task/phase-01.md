# Phase 01. 예약 작업의 모델 단계 칸

**Execution profile**: deep

## 목표

예약 작업에 `model_tier`(`FAST`, `BALANCED`, `DEEP`, 비움)를 저장하고, 발화가 그 대화를 그 단계로 고른 대화로 만든다.
같은 에이전트에서 도는 작업을 서로 다른 모델 단계로 돌리기 위해서다.

**범위 외**: 「보고할 것 없음」 완료(phase 02), 화면(phase 03).

## 컨텍스트

**근거 문서**: `docs/backend/task.md` 의 「작업」 표와 「모델 단계」, 「API」 절. `docs/backend/schema/task.md` 의 `task.model_tier`. `docs/model-tiers.md` 의 「모델 선택」. `docs/adr/ADR-20261008-cron-to-task.md`.

- 작업 엔티티: `backend/src/main/java/com/bifos/assistant/task/domain/Task.java`. `create(...)` 와 `edit(...)` 의 시그니처는 그대로 둔다
- 단계 enum: `com.bifos.assistant.model.domain.type.ModelTier`(`FAST`, `BALANCED`, `DEEP`)
- 대화 엔티티: `backend/src/main/java/com/bifos/assistant/chat/domain/Conversation.java`. 모델 선택 칸은 `modelProvider`, `model`, `reasoningEffort`, `modelSelectionMode`(`ModelSelectionMode.TIER` 등), `modelTier` 다
- 발화가 대화를 정하는 곳: `backend/src/main/java/com/bifos/assistant/task/application/TaskRunStarter.java` 의 `conversationFor(Task, TaskRun, Instant)`. `prepare` 의 트랜잭션(`transactions.execute`) 안에서 돈다
- 작업 저장: `TaskService.create` 와 `TaskService.update`. 요청은 `TaskDtos.TaskRequest` 가 `TaskInput` 으로 바꾼다. 응답은 `TaskDtos.TaskView.from(TaskDetail)`
- 새 마이그레이션은 `backend/src/main/resources/db/migration/V<UTC 14자리>__<설명>.sql`. 작성 규칙은 `docs/backend/schema/README.md`

## 의도 메모

- **대화의 단계를 `ConversationRepository.chooseModelTierIfActive` 로 바꾸지 않는다.** 그 질의는 `clearAutomatically = true` 라 `prepare` 트랜잭션의 영속성 컨텍스트를 비운다. 그 뒤 `run.useConversation(...)` 의 변경이 반영되지 않는다
- **대화 엔티티를 읽어 칸을 바꾸는 dirty checking 도 쓰지 않는다.** `Conversation` 에는 `@DynamicUpdate` 와 `@Version` 이 없어 commit 때 갱신 가능한 칸 전체가 다시 써진다. 그 사이 다른 트랜잭션이 쓴 `hermes_session_id`, `updated_at`, `title`, `auto_turn_count`, `deleted_at` 이 읽은 때의 값으로 되돌아간다. `Conversation.java` 의 `autoTurnCount` Javadoc 이 「이 칸만 바꾸는 갱신은 update 질의로 한다」 고 적은 까닭이다. `resetAutoTurns` 와 같은 모양(`@Modifying` 만, `clearAutomatically` 없음)의 조건부 update 질의를 `ConversationWriter` 로 부른다. 새 대화도 IDENTITY 라 이미 insert 된 뒤여서 이 질의가 적용된다
- 작업의 단계가 비어 있으면 대화의 선택을 건드리지 않는다. `SINGLE` 대화에서 사용자가 고른 모델을 지우지 않기 위해서다
- 작업을 저장할 때 그룹의 단계 정의를 확인하지 않는다. 정의가 비면 그 단계는 에이전트 기본 모델로 돈다(`docs/model-tiers.md`)
- `CHECK` 작업(`Task.check`)의 단계는 늘 비어 있다

## 작업 항목

### 1. 마이그레이션 `V20261008104800__task_model_tier.sql`

`ALTER TABLE task ADD COLUMN model_tier VARCHAR(16) NULL;` 한 문장. 머리 주석에 무엇을 위한 칸인지 한 줄 적는다(ADR-20261008 / cron-to-task).

### 2. `Task` 에 칸과 메서드

- `@Enumerated(EnumType.STRING) @Column(name = "model_tier", length = 16) private ModelTier modelTier;` 와 Javadoc 한 줄. `@Getter` 로 `modelTier()` 가 생긴다
- `public void chooseModelTier(ModelTier tier, Instant now)`: `kind != TaskKind.TURN` 이면 `IllegalStateException`. `modelTier = tier`(null 허용), `updatedAt = micros(now)`

### 3. 요청과 응답

- `TaskInput` 끝에 `ModelTier modelTier` 를 더한다. Javadoc 에 비우면 작업의 단계를 지운다고 적는다. 클래스 Javadoc 의 「뒤의 셋은 비면 기본값이다」 를 「`conversationMode`, `missedPolicy`, `notifyPolicy` 는 비면 기본값이다」 로 고친다
- `TaskDtos.TaskRequest` 끝에 `ModelTier modelTier` 를 더하고 `toInput()` 에 넘긴다. 그 record Javadoc 의 「뒤의 셋은 비우면 기본값이다」 도 같은 식으로 고친다
- `TaskDtos.TaskView` 의 `notifyPolicy` 뒤, `createdAt` 앞에 `ModelTier modelTier` 를 더하고 `from` 에서 `task.modelTier()` 를 싣는다

### 4. `TaskService`

`create` 는 `tasks.save(Task.create(...))` 다음, `update` 는 `task.edit(...)` 다음에 `task.chooseModelTier(input.modelTier(), now)` 를 부른다. 같은 트랜잭션이라 dirty checking 으로 저장된다.

### 5. 대화에 단계를 고르기

- `ConversationRepository` 에 질의를 더한다. `@Modifying`(`clearAutomatically` 없음) `@Query("update Conversation c set c.modelSelectionMode = :mode, c.modelTier = :tier, c.modelProvider = null, c.model = null, c.reasoningEffort = null where c.id = :id") int chooseTierForTask(@Param("id") Long id, @Param("mode") ModelSelectionMode mode, @Param("tier") ModelTier tier);` Javadoc 에 예약 작업 발화가 부르는 경로이고, 부르는 쪽 트랜잭션의 영속성 컨텍스트를 비우지 않으려고 `clearAutomatically` 를 두지 않는다고 적는다. `updated_at` 은 바꾸지 않는다(목록 순서를 흔들지 않는다)
- `ConversationWriter` 에 `@Transactional public int chooseTierForTask(Long id, ModelTier tier)` 를 더한다. `repository.chooseTierForTask(id, ModelSelectionMode.TIER, tier)` 다
- `ChatService` 는 `ConversationWriter` 를 갖지 않는다. `ChatConversationManagement`(이미 `ConversationWriter` 를 가진다)에 `void chooseTierForTask(Long conversationId, ModelTier tier)` 위임을 두고, `ChatService` 에 같은 이름의 public 위임을 더한다. Javadoc 에 예약 작업 발화가 부르는 경로라고 적는다
- `TaskRunStarter.conversationFor` 가 대화 번호를 정해 돌려주기 전, 모든 갈래(줄의 대화를 다시 쓸 때, `SINGLE` 의 대화를 다시 쓰거나 새로 만들 때, `NEW_PER_RUN` 의 새 대화)에서 `task.modelTier() != null` 이면 `chat.chooseTierForTask(conversationId, task.modelTier())` 를 부른다. `task.modelTier()` 가 null 이면 부르지 않는다

### 6. 이 phase 를 검증하는 시험

- `backend/src/test/java/com/bifos/assistant/task/TaskServiceTest.java`: `TaskInput` 을 만드는 보조 메서드 둘(파일 끝)에 마지막 인자를 더한다. 새 시험 셋: `BALANCED` 로 만든 작업의 `modelTier()` 가 `BALANCED` 다. 그 작업을 `modelTier` null 로 고치면 `modelTier()` 가 null 이다. `Task.check(...)` 로 만든 작업에 `chooseModelTier(ModelTier.FAST, now)` 를 부르면 `IllegalStateException` 이다(실패 경로)
- `backend/src/test/java/com/bifos/assistant/task/TaskRunStarterTest.java`: 새 시험 셋.
  - `NEW_PER_RUN` 작업에 `FAST` 를 고르고 발화하면 그 대화의 `modelSelectionMode()` 가 `TIER`, `modelTier()` 가 `FAST` 다
  - `SINGLE` 작업의 대화를 미리 만들고 그 대화가 `CUSTOM` 모델을 고른 상태에서, 작업에 `DEEP` 을 고르고 발화하면 그 대화가 `TIER`, `DEEP` 이고 `modelChoice()` 의 모델이 비어 있다
  - 단계가 비어 있는 `NEW_PER_RUN` 작업의 발화 대화는 `modelSelectionMode()` 가 null 이다
  - 단계는 `tasks.findById(task.id())` 로 다시 읽은 작업에 `chooseModelTier(...)` 를 부르고 `tasks.save(...)` 로 저장한다. fixture 가 돌려준 분리된 객체를 대화가 생긴 뒤 저장하면 merge 가 `conversation_id` 를 null 로 되돌린다(`TaskRunStarter` 가 그 칸만 질의로 적는 까닭)
  - `SINGLE` 시험의 순서: 작업에 `DEEP` 을 고르고 첫 발화를 끝낸다. 그 대화를 `ChatService.chooseModel` 같은 기존 경로나 `ConversationRepository.chooseModelIfActive` 로 `CUSTOM` 으로 바꾼다. 두 번째 발화를 끝낸 뒤 같은 대화가 다시 `TIER`, `DEEP` 인지 본다
  - `NEW_PER_RUN` `FAST` 시험은 그 발화의 루트 실행(`agent_execution`, `TaskRun.executionId()`)의 단계 칸도 `FAST` 인지 단언한다. 실행 엔티티의 칸 이름은 `AgentExecution` 에서 읽는다
- `backend/src/test/java/com/bifos/assistant/task/TaskControllerTest.java`: `POST /api/v1/tasks` 에 `"modelTier": "BALANCED"` 를 보내면 응답의 `modelTier` 가 `BALANCED` 이고, 보내지 않으면 `null` 이다. 모르는 enum 값을 보내는 시험은 두지 않는다. 지금 전역 처리기는 읽지 못한 본문을 500 으로 내고, 그것을 400 으로 바꾸는 일은 이 계획 밖이다 이 파일의 기존 요청 작성 방식을 따른다

## 검증

```bash
cd backend && ./gradlew test --tests 'com.bifos.assistant.task.TaskServiceTest' --tests 'com.bifos.assistant.task.TaskRunStarterTest' --tests 'com.bifos.assistant.task.TaskControllerTest'
cd backend && ./gradlew checkstyleMain checkstyleTest spotlessCheck
bash scripts/check-mysql-migration.sh
```

`check-mysql-migration.sh` 는 Docker 가 있어야 돈다. 없으면 건너뛰었다고 보고한다.
마이그레이션 검사(`MysqlMigrationTest`)가 엔티티의 `model_tier` 와 마이그레이션이 맞는지 본다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/resources/db/migration/V20261008104800__task_model_tier.sql` | 신규 |
| `backend/src/main/java/com/bifos/assistant/task/domain/Task.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/task/application/model/TaskInput.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/task/application/TaskService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/task/application/TaskRunStarter.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/task/presentation/TaskDtos.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/infra/ConversationRepository.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/application/ConversationWriter.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/application/ChatConversationManagement.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/application/ChatService.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/task/TaskServiceTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/task/TaskRunStarterTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/task/TaskControllerTest.java` | 수정 |
