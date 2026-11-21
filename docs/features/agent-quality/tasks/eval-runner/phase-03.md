# Phase 03. 문항 보내기와 마감 멈춤을 배포 뒤 확인 실행기에서 꺼낸다

**Execution profile**: standard

## 목표

`DeployCheckRunner` 안의 문항 보내기, 끝남 기다리기, 마감 멈춤을 `TurnDriver` 로 옮겨 평가 실행기가 함께 쓰게 한다. 배포 뒤 확인의 동작은 바꾸지 않는다.

**범위 외**: 평가 실행기(다음 PR).

## 컨텍스트

- 옮길 코드: `quality/application/DeployCheckRunner.java` 의 `send(evaluation, attempt, text, attachmentIds, deadline)`, `waitUntil(deadline, condition)`, 마감 뒤 `TurnCancellation.markOf` 와 `ChatService.stop`, `AgentDelegationService` 의 위임 자식 중지, `HERMES_RUN_TIMEOUT` 과 `USER_BUSY`·`CONVERSATION_BUSY` 처리. 보내기 전 빈 대화 만들기(`ChatService.startEmpty`)와 그 대화의 도는 turn 이 없어질 때까지 기다리기.
- 설계의 근거: `docs/features/agent-quality/design.md` 의 「기능마다 10분 마감」, DECISION9.

**요구사항**: REQ17

**근거 문서**: `docs/features/agent-quality/design.md` 의 「PR2a: 평가 사용자로 묶음 문항 돌리기」 의 「책임과 경계」

## 의도 메모

- 이동과 이름 바꾸기만 한다. 동작이 바뀌면 PR1 테스트가 잡는다.
- `TurnDriver` 는 기능 이름을 모른다. 대화 번호, 보낼 글, 첨부, 마감, 끝남 조건만 받고 결과(끝남, 시간 초과, 한도 거절, 실패)를 돌려준다.

## 작업 항목

### 1. `quality/application/TurnDriver.java`

위 코드를 옮긴다. 공개 메서드는 `openConversation(evaluation, agent)`, `send(evaluation, conversationId, text, attachmentIds, deadline, settled)` → `TurnOutcome`, `waitIdle(conversationId, deadline)`, `stopAll(evaluation, conversationId)`.

### 2. `DeployCheckRunner`

`TurnDriver` 를 부르게 바꾼다. 기능별 분기와 판정, 기록은 그대로 둔다.

### 3. `TurnDriverTest`

`ChatService`, `TurnCancellation`, `AgentDelegationService` 를 대역으로 두고, 끝남 조건을 만족하면 끝남, 마감이 지나면 도는 turn 을 멈추고 시간 초과, `USER_BUSY` 거절이면 한도 거절, `HERMES_RUN_TIMEOUT` 이면 시간 초과를 돌려주는지 본다.

## 검증

```bash
cd backend && ./gradlew test --tests 'com.bifos.assistant.quality.application.TurnDriverTest' --tests 'com.bifos.assistant.admin.quality.DeployCheckApiTest' --tests 'com.bifos.assistant.quality.application.DeployCheckCleanerTest' --tests 'com.bifos.assistant.quality.application.DeployCheckJudgeTest'
cd backend && ./gradlew archTest checkstyleMain
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/quality/application/TurnDriver.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/quality/application/DeployCheckRunner.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/quality/application/TurnDriverTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/admin/quality/DeployCheckApiTest.java` | 수정 (바뀐 것이 없으면 그대로 둔다) |
