# Phase 03. 문항의 대화가 만든 것을 지우는 부품을 더한다

**Execution profile**: standard

## 목표

평가 문항 하나의 대화로 평가 사용자에게 생긴 예약 작업, 기억, 할 일, 보관본을 지우고 기다리는 승인 줄을 닫는다(REQ17).

**범위 외**: 언제 부르는지(phase 05), 대화 자체와 결과물 지우기(PR1 의 `DeployCheckCleaner.dropConversation` 을 그대로 쓴다).

## 컨텍스트

| 만든 것 | 찾는 길 | 지우는 길 |
| --- | --- | --- |
| 예약 작업 | 루트 실행이 만든 것은 `Task.conversationId`(`TaskAgentRequests.create` 가 `parentExecutionId()==null` 일 때만 원래 대화를 넣는다). 위임 자식이 만든 것은 대화 칸이 비므로, 문항 시작 시각 뒤 생긴 평가 사용자의 대화 칸이 빈 작업 가운데 지시문이 배포 뒤 확인의 고정 지시문(`DeployCheckItems.TASK_INSTRUCTION`, 같은 패키지라 그대로 읽는다)이 아닌 것 | `TaskService.archive` 와 같은 보관 처리 |
| 기억 | `MemoryCaptureService.capturesOf(user, conversationId)` | phase 01 의 `DeployCheckCleaner.forgetCaptured`(`CREATED`, `PROPOSED` 지우기, `UPDATED` 늦은 것부터 되돌리기)를 그대로 부른다 |
| 보관본 | `ArtifactVaultFinder.putIdsOf(userId, conversationId)` | `ArtifactVaultService` 의 지우기. 통과·실패와 상관없이 지운다 |
| 할 일 | `FollowUp.conversationId`(`FollowUpRepository` 의 대화 조회들) | 새 대화 단위 지우기 |
| 승인 줄 | `ConnectorActionRepository.findByConversationIdAndUserIdAndStatusOrderByIdAsc` | `ConnectorActionService.reject(user, actionId)`. 줄은 기록으로 남는다 |

**요구사항**: REQ17

**근거 문서**: `docs/features/agent-quality/design.md` 의 「비우기」, DECISION11

## 의도 메모

- 시각으로 고르지 않는다. 함께 도는 배포 뒤 확인의 것을 지우게 된다.
- 하나가 실패해도 나머지를 계속 지우고 경고 로그만 남긴다. 실패 여부를 돌려줘 부르는 쪽이 「비우기 끝남」 을 적을지 정한다.

## 작업 항목

### 1. `task`: 대화 단위 보관

`TaskRepository` 에 사용자와 대화로 보관하지 않은 작업을 찾는 조회와, 사용자의 대화 칸이 빈 보관하지 않은 작업을 생성 시각 뒤로 찾는 조회를 더한다. `TaskService.archiveForConversation(CurrentUser, Long conversationId)` 와 `archiveDetachedSince(CurrentUser, Instant since, Predicate<지시문> keep)` 를 더한다. 이름과 칸은 `Task.java` 의 실제 칸(생성 시각 칸 포함)을 읽고 맞춘다.

### 2. `followup`: 대화 단위 지우기

`FollowUpService.deleteForConversation(CurrentUser, Long conversationId)`. 그 사용자의 그 대화 할 일을 지운다. 지우는 방식은 `FollowUpUserPurge` 의 지우기 순서(딸린 줄 먼저)를 따른다.

### 3. `quality/application/EvalCleaner.java`

`boolean cleanQuestion(CurrentUser evaluation, Long conversationId, Instant questionStartedAt)`: 위 표를 차례로 지우고 모두 성공하면 true.

### 4. 테스트

- `TaskServiceTest`: 대화 A 의 작업만 보관되고 대화 B 와 다른 사용자의 작업은 남는다. 시각 뒤 생긴 대화 칸 없는 작업은 보관되고, 지킬 지시문의 작업과 그 전 작업은 남는다.
- `FollowUpServiceTest`: 대화 A 의 할 일만 지워진다.
- `EvalCleanerTest`(신규 통합): 대화 A 로 만든 예약 작업(루트와 위임 자식), 기억(생성, 제안), 할 일, 보관본이 지워지고 기다리는 승인 줄이 거절되며, 대화 B, 배포 뒤 확인의 고정 지시문 작업, 다른 사용자의 것은 그대로다. 준비 기억을 두 번 고친 capture 는 늦은 것부터 되돌아온다.

## 검증

```bash
cd backend && ./gradlew test --tests 'com.bifos.assistant.task.TaskServiceTest' --tests 'com.bifos.assistant.followup.FollowUpServiceTest' --tests 'com.bifos.assistant.quality.application.EvalCleanerTest'
cd backend && ./gradlew archTest
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/task/infra/TaskRepository.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/task/application/TaskService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/followup/infra/FollowUpRepository.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/followup/application/FollowUpService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/quality/application/EvalCleaner.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/task/TaskServiceTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/followup/FollowUpServiceTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/quality/application/EvalCleanerTest.java` | 신규 |
