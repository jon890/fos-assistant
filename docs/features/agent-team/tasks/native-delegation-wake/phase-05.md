# Phase 05. 이력 API 의 결과 상태와 대화 정리

**Execution profile**: standard

## 목표

이력 API 가 내장 위임 결과의 알림 줄에 `nativeResult` 를 붙여, 새로 고치거나 다른 기기에서 열어도 같은 줄이 보이게 한다. 대화를 정리하면 등록 줄의 글 칸을 비운다.

**범위 외**: 화면(phase 6).

## 컨텍스트

**요구사항**: REQ14(DECISION33 의 줄이 새로 고쳐도 같다)

**근거 문서**: `docs/features/agent-team/design.md` 의 「PR6 내장 위임 결과」 → 「책임과 경계」, 「이력 API」, 「등록 줄의 새 칸」(정리), 설계 DECISION40, `backend/docs/data-schema.md` 의 「지울 때」 절(「하위 에이전트 session 등록(`hermes_session_binding`)도 지우지 않는다」 줄)

- 이력 API: `chat/turn/presentation/ChatController.java` 의 `messages(...)` 가 `ChatDtos.MessageView` 를 만든다. 알림 줄에는 이미 `delivery`, `deliveredResults` 를 붙인다.
- `chat/turn/application/ChatService.java` 는 500줄로 상한에 닿아 있다. 이 phase 는 `ChatService` 에 메서드를 더하지 않는다.
- `chat` 은 `orchestration` 보다 아래다. `chat` 에는 port 만 두고 `orchestration` 이 구현한다(설계 「책임과 경계」).
- 정리: `chat/purge/application/ConversationPurgeWriter.purge` 가 한 트랜잭션에서 각 port 를 부른다. port 본보기는 `chat/purge/application/port/ConversationActionPurge.java`.
- 정리 분류: `backend/src/test/resources/purge/tables.tsv` 의 `hermes_session_binding` 줄은 지금 `KEEP|KEEP|KEEP` 이다. `PurgeClassificationTest` 가 이 파일과 `purge/fixture.sql` 을 대조하고, `PurgeCoverageMysqlTest`(Docker 의 MySQL, CI 가 돈다)가 실제 정리 결과를 본다.
- phase 3 이 `chat/turn/application/dto/NativeResultView.java` 를 만들었다.
- 테스트 명령은 worker 지침대로 무거운 검사 대기열로 감싸 돌린다. `check-mysql-migration.sh` 는 로컬에서 돌리지 않는다.

## 의도 메모

- 상태는 등록 줄의 `notice_message_id` 와 `result_status` 에서 읽는다. 다시 전달은 `notice_message_id` 를 바꾸지 않으므로 다시 전달 뒤에도 첫 줄에 같은 표시가 남는다(설계 DECISION40).
- 글 칸은 깨울 자식에만 차고 깨울 자식은 늘 대화가 있다. 그래서 사용자 정리도 대화 정리를 거쳐 같은 칸이 빈다.

## 작업 항목

### 1. 새 port `chat/delivery/application/port/AutoTurnNoticeLabels.java`

`Map<Long, String> statusesByNotice(Long conversationId)`: 그 대화의 알림 줄 번호마다 결과 상태 이름.

### 2. 새 `chat/delivery/application/AutoTurnNoticeStatuses.java`

`List<AutoTurnNoticeLabels>` 를 받아 합친 맵을 낸다. 구현이 없으면 빈 맵이다. `ChatController` 가 주인 확인을 마친 대화 번호로 부른다.

### 3. 새 `orchestration/application/NativeDelegationNoticeLabels.java`

`AutoTurnNoticeLabels` 구현. 그 대화 실행을 origin 으로 둔 등록 줄 가운데 `notice_message_id` 와 `result_status` 가 있는 줄을 읽는다.

### 4. `ChatDtos.MessageView` 와 `ChatController`

- `MessageView` 끝에 `NativeResultView nativeResult` 를 더하고 Javadoc `@param` 을 적는다. 알림 줄이 아니거나 내장 위임 결과 줄이 아니면 null.
- `ChatController.messages` 가 `AutoTurnNoticeStatuses` 의 맵으로 채운다. 생성자가 바뀌므로 `new ChatController(` 로 직접 만드는 테스트 10개를 함께 고친다(`git grep -ln "new ChatController(" backend/src/test`). 대역은 빈 맵을 내는 `new AutoTurnNoticeStatuses(List.of())` 다.
- `MessageView` 를 만드는 다른 곳이 있으면 함께 고친다(`git grep -n "new MessageView("`).

### 5. 정리 port

- 새 `chat/purge/application/port/ConversationNativeResultPurge.java`: `void eraseBodies(Long conversationId)`.
- 새 `orchestration/application/NativeDelegationResultPurge.java` 가 구현한다. 그 대화 실행을 origin 으로 둔 줄의 `goal_preview`, `result_text`, `result_key_id` 를 비운다. `@Transactional(propagation = MANDATORY)`.
- `ConversationPurgeWriter.purge` 가 `executions.eraseBodies` 다음에 부른다.
- `backend/src/test/resources/purge/tables.tsv` 의 `hermes_session_binding` 줄을 `SCRUB|SCRUB|KEEP` 으로 바꾸고 근거 칸에 비우는 칸을 적는다. `purge/fixture.sql` 의 그 줄은 정리할 대화의 실행을 origin 으로 두고 새 칸(`wake_eligible`, `goal_preview`, `result_text`, `result_key_id` 등)을 채운다.
- `backend/src/test/java/com/bifos/assistant/PurgeCoverageMysqlTest.java` 의 SCRUB 판정(`default` 가 「검사에 더한다」 로 실패하는 switch)에 `hermes_session_binding` case 를 더한다. 세 칸이 비고 나머지 칸은 그대로인지 본다. 이 검사는 CI 의 MySQL 묶음이 돈다.
- `backend/docs/data-schema.md` 「지울 때」 절의 「하위 에이전트 session 등록(`hermes_session_binding`)도 지우지 않는다」 를 「줄은 남기고 결과 글 칸과 key 를 비운다」 로 고친다.

### 6. 테스트

- 새 `backend/src/test/java/com/bifos/assistant/chat/NativeResultHistoryTest.java`(`@BackendIntegrationTest`): 알림 줄과 등록 줄(`notice_message_id`, `result_status=FAILED`)을 JDBC 로 만들고 이력 API 를 부르면 그 줄에만 `nativeResult.status == "FAILED"` 가 있다. 다른 알림 줄은 null 이다. 같은 결과를 `resultsFor` 로 다시 전달한 뒤에도 첫 줄의 값이 그대로다. 남의 대화는 지금처럼 거절된다.
- 같은 파일에서 대화를 지우고 정리하면 그 등록 줄의 세 칸이 비고 줄은 남는다.
- `backend/src/test/java/com/bifos/assistant/PurgeClassificationTest.java` 는 고치지 않고 다시 돈다.

## 검증

```bash
cd backend && ./gradlew test --tests '*NativeResultHistoryTest' --tests '*PurgeClassificationTest' --tests '*DelegationLinesTest' --tests '*ConnectorDeliveryRetryTest' --tests '*ChatAttachmentTurnTest' --tests '*ConversationEventControllerTest' --tests '*ConversationMissingAgentTest' --tests '*ConversationPagingTest' --tests '*ConversationPublicIdTest' --tests '*ModelTierRequestValidationTest' --tests '*SourceReadMessagesTest' --tests '*ToolDetailStreamTest' --tests '*UserExecutionLimitChatTest' --tests '*ConversationTaskLabelTest'
cd backend && ./gradlew archTest checkstyleMain checkstyleTest spotlessCheck
node scripts/checks/check-file-length.mjs
node --test test/repo/doc-links.test.ts test/repo/doc-code-references.test.ts
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/chat/delivery/application/port/AutoTurnNoticeLabels.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/delivery/application/AutoTurnNoticeStatuses.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/orchestration/application/NativeDelegationNoticeLabels.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/turn/presentation/ChatDtos.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/turn/presentation/ChatController.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/purge/application/port/ConversationNativeResultPurge.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/orchestration/application/NativeDelegationResultPurge.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/purge/application/ConversationPurgeWriter.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/orchestration/infra/HermesSessionBindingRepository.java` | 수정 |
| `backend/src/test/resources/purge/tables.tsv` | 수정 |
| `backend/src/test/resources/purge/fixture.sql` | 수정 |
| `backend/src/test/java/com/bifos/assistant/PurgeCoverageMysqlTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/ChatAttachmentTurnTest.java` | 수정 (`new ChatController(` 인자) |
| `backend/src/test/java/com/bifos/assistant/chat/ConversationEventControllerTest.java` | 수정 (`new ChatController(` 인자) |
| `backend/src/test/java/com/bifos/assistant/chat/ConversationMissingAgentTest.java` | 수정 (`new ChatController(` 인자) |
| `backend/src/test/java/com/bifos/assistant/chat/ConversationPagingTest.java` | 수정 (`new ChatController(` 인자) |
| `backend/src/test/java/com/bifos/assistant/chat/ConversationPublicIdTest.java` | 수정 (`new ChatController(` 인자) |
| `backend/src/test/java/com/bifos/assistant/chat/ModelTierRequestValidationTest.java` | 수정 (`new ChatController(` 인자) |
| `backend/src/test/java/com/bifos/assistant/chat/SourceReadMessagesTest.java` | 수정 (`new ChatController(` 인자) |
| `backend/src/test/java/com/bifos/assistant/chat/ToolDetailStreamTest.java` | 수정 (`new ChatController(` 인자) |
| `backend/src/test/java/com/bifos/assistant/chat/UserExecutionLimitChatTest.java` | 수정 (`new ChatController(` 인자) |
| `backend/src/test/java/com/bifos/assistant/task/ConversationTaskLabelTest.java` | 수정 (`new ChatController(` 인자) |
| `backend/src/test/java/com/bifos/assistant/chat/NativeResultHistoryTest.java` | 신규 |
| `backend/docs/data-schema.md` | 수정 |
