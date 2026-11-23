# Phase 01. 새 대화는 기본 비서로만 열고 `@` 맡기기를 거절하며, 지시문과 평가 세트를 맞춘다

**Execution profile**: standard

## 목표

Control Plane 이 새 대화의 에이전트를 기본 비서로 정하고 `@` 맡기기(`delegateTo`)를 거절한다. 지시문은 에이전트 고르기와 링크를 안내하지 않고, 이름을 부르거나 바꿔 달라는 부탁은 비서가 맡긴다고 안내한다.

**범위 외**: 화면(phase 02). 읽기 전용 대화와 예약 작업(다음 PR).

## 컨텍스트

**요구사항**: REQ4, REQ6, REQ8

**근거 문서**: `docs/features/assistant-hub/spec.md`(REQ4, REQ6, REQ8, DECISION3, DECISION4, DECISION8, DECISION9, DECISION15, DECISION24), `docs/features/assistant-hub/design.md` 의 「PR2 입구를 비서로 모으기」

- 기본 비서: `agent/application/PrimaryAgents.of(CurrentUser)`.
- 새 대화를 만드는 길: `ChatTurnRouting.route`(새 대화면 `agents.requireStartable(user, agentCode)`), `ChatConversationManagement.startEmpty`.
- `@` 맡기기: `ChatTurnRouting.routeSend`/`routeMention`, `TurnIntent.Mention`/`forSend`, 그 분기를 보는 `ChatTurnLifecycle`, `ChatTurnRunner`, `MentionInputs`, `ChatService`.
- `ChatService.java` 는 파일 길이 상한(500줄)에 닿아 줄을 늘리지 않는다.
- 지시문: `context/application/ServiceConceptsInstructions.java`, `chat/turn/application/DelegationTargetsInstructions.java`. 평가 세트: `test/model-eval/concepts/`.

## 작업 항목

### 1. 새 `backend/src/main/java/com/bifos/assistant/chat/conversation/application/ConversationEntry.java`

`chat.turn.application` 은 클래스 수 상한(25)에 닿아 `chat.conversation.application` 에 둔다.
- `Agent startAgent(CurrentUser user, String agentCode)`: 기본 비서가 없으면 `AGENT_NOT_FOUND`. `agentCode` 가 비지 않았고 기본 비서의 코드와 다르면 `VALIDATION_FAILED`("a new conversation starts with the primary assistant"). 그 밖에는 `agents.requireStartable(user, primary.code())`(꺼졌으면 `AGENT_DISABLED`).
- `ChatTurnRouting.route` 의 새 대화와 `ChatConversationManagement.startEmpty` 가 이것을 쓴다.

### 2. `@` 맡기기 지우기

- `ChatTurnRouting.routeSend`: `delegateTo` 가 비지 않았으면 `VALIDATION_FAILED`("delegating with @ is no longer supported"). `routeMention` 을 지운다.
- `TurnIntent.Mention`, `forSend`, `MENTION_INSTRUCTION` 을 지우고 `ChatService` 는 `new TurnIntent.Fresh()` 를 쓴다. `Mention` 분기를 보는 곳(`ChatTurnLifecycle`, `ChatTurnRunner`, `ChatTurnRouting`, `MentionInputs`)의 그 조건을 지운다.
- 지난 맡김을 그리는 `MentionLines` 와 다음 turn 입력에 넣는 `MentionInputs.carryOver` 는 남긴다. 맡김 turn 만 쓰던 `recentLines` 와 그 머리 글은 지운다.

### 3. 지시문

- `ServiceConceptsInstructions`: 에이전트를 바꾸는 링크를 안내하던 줄을 「대화의 에이전트는 바뀌지 않는다. 다른 에이전트의 일을 부탁하면 그 에이전트에게 맡기고 링크를 붙이지 않는다」 로 바꾼다.
- `DelegationTargetsInstructions` 머리에 「사용자가 아래 에이전트의 이름을 말하며 부탁하면 그 에이전트에게 맡긴다」 를 더한다.

### 4. 평가 세트

- `test/model-eval/concepts/questions.json` 의 에이전트 바꾸기 문항: `/new?agent=` 링크와 「바꿨어요」 가 있으면 실패하고, 맡긴다는 말이 있어야 한다. 함께 시킨 일이 없어 도구는 기대하지 않는다.
- 이름을 부른 부탁 문항(`agent-delegate-by-name`)을 더한다. 기대 도구 `agent_delegate`.
- 같은 개념의 모든 문항이 `/new?agent=` 링크를 실패로 센다.
- `concepts-eval-data.test.ts`, `concepts-eval-score.test.ts` 를 맞춘다.

### 5. 테스트

- 새 `backend/src/test/java/com/bifos/assistant/chat/ConversationEntryTest.java`(통합): 빈 코드와 기본 비서 코드로 새 대화와 빈 대화가 비서로 생긴다. 다른 에이전트 코드는 `VALIDATION_FAILED`. 기본 비서가 꺼졌으면 `AGENT_DISABLED`. 기본 비서 대화를 이어 쓸 때 다른 코드를 줘도 대화의 에이전트로 돈다.
- `MentionDelegationTest`: `delegateTo` 가 `VALIDATION_FAILED` 이고 실행이 생기지 않는 사례로 바꾼다. 지난 맡김 줄을 읽는 사례가 있으면 남긴다.
- `TurnIntent` 를 쓰는 테스트(`TurnIntentByUserTest`, `TurnIntentScopeTest`, `SteerServiceTest`)와 지시문 테스트의 기대를 고친다.
- 기본 비서는 허용 목록 줄로만 찾으므로, 새 대화를 여는 backend 통합 테스트는 새 `testsupport.TestPrimaryAgents` 로 대화할 에이전트를 그 사용자의 기본 비서로 둔다. 만든 줄은 `IntegrationTestIsolation` 이 검사마다 지운다. 전문 에이전트와의 지난 대화가 필요한 테스트는 대화를 저장소로 바로 만든다.
- e2e 와 브라우저 테스트가 쓸 test-support API 를 둔다: `PrimaryAgentTestSupportController`(기본 비서 정하기와 없애기), `ChatTestSupportController` 의 지난 대화 만들기와 실행의 에이전트 바꾸기.
- e2e(`test/e2e/`)는 `ASSISTANT_TESTSUPPORT_ENABLED` 를 켜고, dad 의 기본 비서를 정하며, 전문 에이전트의 실행을 보는 시나리오는 그 에이전트와의 지난 대화로 보낸다(`harness.ts` 의 `usePrimary`, `seedConversation`, `clearPrimary`).
- 운영 평가 묶음 `backend/src/main/resources/quality/bundles/service-concepts.json` 을 `questions.json` 과 같게 고친다(`test/repo/quality-bundles.test.ts`).

## 검증

```bash
cd backend && ./gradlew test --tests '*ConversationEntryTest' --tests '*MentionDelegationTest' --tests '*TurnIntent*' --tests '*SteerServiceTest' --tests '*Instructions*' --tests '*ArchitectureRulesTest'
cd backend && ./gradlew test
cd backend && ./gradlew checkstyleMain checkstyleTest spotlessCheck
node --test test/model-eval/concepts/concepts-eval-data.test.ts test/model-eval/concepts/concepts-eval-score.test.ts test/repo/quality-bundles.test.ts
node test/e2e/run.ts
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/chat/conversation/application/ConversationEntry.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/turn/application/ChatConversationManagement.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/turn/application/ChatService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/turn/application/ChatTurnLifecycle.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/turn/application/ChatTurnRouting.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/turn/application/ChatTurnRunner.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/turn/application/DelegationTargetsInstructions.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/turn/application/MentionInputs.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/turn/application/dto/TurnIntent.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/turn/presentation/ChatDtos.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/context/application/ServiceConceptsInstructions.java` | 수정 |
| `backend/src/main/resources/quality/bundles/service-concepts.json` | 수정 |
| `backend/src/test/java/com/bifos/assistant/agent/AgentLifecycleFlagsTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/ArtifactTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/ChatAttachmentTurnTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/ChatInterimTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/ChatRegenerateTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/ChatReplyTurnTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/ChatRunningTurnTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/ChatServiceTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/ChatStopTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/ConversationEntryTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/chat/ConversationEventControllerTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/ConversationManageTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/ConversationPagingTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/ConversationPublicIdTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/EmptyConversationTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/MentionDelegationTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/ModelSelectionTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/RegenerateDeletedAttachmentTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/SandboxFileSpaceTurnTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/SteerServiceTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/ToolDetailStreamTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/UserExecutionLimitChatTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/presentation/PendingMessageControllerTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/turn/application/DelegationTargetsInstructionsTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/turn/application/dto/TurnIntentByUserTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/turn/application/dto/TurnIntentScopeTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/context/ServiceConceptsInstructionsTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/orchestration/UserExecutionLimitDelegationTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/testsupport/ChatTestSupportController.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/testsupport/IntegrationTestDoubles.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/testsupport/IntegrationTestIsolation.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/testsupport/PrimaryAgentTestSupportController.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/testsupport/TestPrimaryAgents.java` | 신규 |
| `test/e2e/connector-support.ts` | 수정 |
| `test/e2e/delegation-support.ts` | 수정 |
| `test/e2e/harness.ts` | 수정 |
| `test/e2e/run.ts` | 수정 |
| `test/e2e/scenarios/agent-lifecycle.ts` | 수정 |
| `test/e2e/scenarios/binding.ts` | 수정 |
| `test/e2e/scenarios/connector-binding.ts` | 수정 |
| `test/e2e/scenarios/connector-policy.ts` | 수정 |
| `test/e2e/scenarios/content-draft-mcp.ts` | 수정 |
| `test/e2e/scenarios/content-snapshot-save.ts` | 수정 |
| `test/e2e/scenarios/delivery-retry.ts` | 수정 |
| `test/e2e/scenarios/follow-up-mcp.ts` | 수정 |
| `test/e2e/scenarios/mcp-principal.ts` | 수정 |
| `test/e2e/scenarios/media-observation-mcp.ts` | 수정 |
| `test/e2e/scenarios/memory-remember-mcp.ts` | 수정 |
| `test/e2e/scenarios/native-delegation-mcp.ts` | 수정 |
| `test/e2e/scenarios/notifications.ts` | 수정 |
| `test/e2e/scenarios/user-execution-limit.ts` | 수정 |
| `test/model-eval/concepts/concepts-eval-data.test.ts` | 수정 |
| `test/model-eval/concepts/questions.json` | 수정 |
