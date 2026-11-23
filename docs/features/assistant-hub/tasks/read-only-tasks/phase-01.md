# Phase 01. Control Plane 이 비서가 아닌 대화에 쓰는 요청을 거절하고 예약 작업을 기본 비서로 정한다

**Execution profile**: standard

## 목표

대화 응답이 `writable` 을 내고, 기본 비서가 아닌 대화에 사람이 쓰는 요청(보내기, 다시 생성, 결과 다시 전달, 대기 메시지)을 `VALIDATION_FAILED` 로 거절한다. 예약 작업의 만들기와 고치기는 에이전트를 기본 비서로 정한다.

**범위 외**: 화면(phase 02).

## 컨텍스트

**요구사항**: REQ5, REQ7

**근거 문서**: `docs/features/assistant-hub/spec.md`(REQ5, REQ7, DECISION4, DECISION8, DECISION16, DECISION22, DECISION23), `docs/features/assistant-hub/design.md` 의 「PR2 입구를 비서로 모으기」 의 읽기 전용과 예약 작업

- 판정: `backend/src/main/java/com/bifos/assistant/chat/conversation/application/ConversationEntry.java`(앞 PR 이 만든 클래스), `agent/application/PrimaryAgents.java`.
- 보내기: `chat/turn/application/ChatTurnRouting.routeSend`. `route` 는 예약 turn, 먼저 살펴보기, 대기 메시지 실행, 결과 전달도 쓰므로 거기서 막지 않는다.
- 다시 생성과 결과 다시 전달: `chat/turn/presentation/ChatController`. `ChatService.java` 는 500줄이라 늘리지 않는다.
- 대기 메시지: `chat/nextturn/presentation/PendingMessageController`.
- 대화 응답: `chat/turn/presentation/ChatDtos.ConversationView`, `ChatController.viewOf`, `RoomController`.
- 예약 작업: `task/application/TaskService`(만들기, 고치기), `task/application/dto/TaskInput`, `task/presentation/TaskDtos.TaskRequest`, `task/application/TaskAgentRequests`, `quality/application/DeployCheckRunner`.

## 작업 항목

### 1. 읽기 전용 판정

- `ConversationEntry` 에 `writableFor(CurrentUser)`(기본 비서를 한 번 읽어 `Predicate<Conversation>` 을 돌려준다), `writable(CurrentUser, Conversation)`, `requireWritable(CurrentUser, Long conversationId)` 를 더한다. 점검 대화는 늘 참이다.
- `routeSend` 가 이어 쓰는 대화면 `requireWritable` 을 부른다.
- `ChatController` 의 다시 생성과 결과 다시 전달이 SSE 를 열기 전에 `requireWritable` 을 부른다.
- `PendingMessageController` 의 넣기가 `requireWritable` 을 부른다.
- `ConversationView` 에 `boolean writable` 을 더하고 `ChatController` 의 모든 대화 응답과 `RoomController` 가 채운다.

### 2. 예약 작업

- `TaskService.create`, `update` 가 `PrimaryAgents.of(user)` 의 에이전트를 `agents.requireStartable` 로 확인해 쓴다. 기본 비서가 없으면 `AGENT_NOT_FOUND`.
- `TaskInput` 과 `TaskRequest` 의 `agentCode` 를 지운다. `TaskAgentRequests`, `DeployCheckRunner` 의 호출을 고친다.
- `pause`, `resume` 은 그대로다.

### 3. 테스트

- 새 `backend/src/test/java/com/bifos/assistant/chat/ReadOnlyConversationTest.java`(통합): 쇼핑 담당 대화에 보내기, 흐르는 보내기, 다시 생성, 결과 다시 전달, 대기 메시지 넣기가 `VALIDATION_FAILED` 이고 아무것도 저장하지 않는다. 비서 대화와 점검 대화는 된다. 대화 응답의 `writable`. 쇼핑 담당 대화의 예약 turn 과 결과 전달 turn 은 돈다.
- 새 `backend/src/test/java/com/bifos/assistant/task/TaskPrimaryAgentTest.java`(통합): 화면 API 와 `task_` 도구로 만들면 기본 비서, 쇼핑 담당 작업을 고치면 기본 비서로 바뀌고 멈추기·다시 켜기는 그대로다. 기본 비서가 없으면 `AGENT_NOT_FOUND`.
- 작업을 만드는 기존 테스트, 비서가 아닌 대화에 보내는 기존 테스트는 기본 비서를 두거나 기대를 고친다. 전체 backend 테스트로 찾는다.

## 검증

```bash
cd backend && ./gradlew test --tests '*ReadOnlyConversationTest' --tests '*TaskPrimaryAgentTest' --tests '*ArchitectureRulesTest'
cd backend && ./gradlew test
cd backend && ./gradlew checkstyleMain checkstyleTest spotlessCheck
node test/e2e/run.ts
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/chat/conversation/application/ConversationEntry.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/nextturn/presentation/PendingMessageController.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/turn/application/ChatTurnRouting.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/turn/presentation/ChatController.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/turn/presentation/ChatDtos.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/turn/presentation/RoomController.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/mcp/application/McpTaskTools.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/quality/application/DeployCheckRunner.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/task/application/TaskAgentRequests.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/task/application/TaskService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/task/application/dto/TaskInput.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/task/presentation/TaskDtos.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/ChatAttachmentTurnTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/ChatReplyTurnTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/ConversationEventControllerTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/ConversationMissingAgentTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/ConversationPagingTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/ConversationPublicIdTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/DelegationWakeServiceTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/ModelTierRequestValidationTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/NativeDelegationDeliveryTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/NativeResultHistoryTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/PendingBeforeDelegationTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/PendingMessageServiceTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/ReadOnlyConversationTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/chat/ResultDeliveryRetryTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/SourceReadMessagesTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/SteerServiceTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/SteerSettleTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/ToolDetailStreamTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/UserExecutionLimitChatTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/presentation/PendingMessageControllerTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/recovery/application/RestartReconcilerTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/mcp/McpTaskToolsTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/quality/application/EvalCleanerTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/task/ConversationTaskLabelTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/task/TaskAgentRequestsTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/task/TaskContentEncryptionTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/task/TaskControllerTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/task/TaskPlaintextBackfillTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/task/TaskPrimaryAgentTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/task/TaskRunStarterTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/task/TaskServiceTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/testsupport/ChatTestSupportController.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/testsupport/PrimaryAgentTestSupportController.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/testsupport/TestPrimaryAgents.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/usage/FailedExecutionUsageRoutesTest.java` | 수정 |
| `test/e2e/connector-support.ts` | 수정 |
| `test/e2e/harness.ts` | 수정 |
| `test/e2e/run.ts` | 수정 |
| `test/e2e/scenarios/agent-lifecycle.ts` | 수정 |
| `test/e2e/scenarios/mcp-principal.ts` | 수정 |
| `test/e2e/scenarios/native-delegation-mcp.ts` | 수정 |
| `test/e2e/scenarios/scheduled-task.ts` | 수정 |
