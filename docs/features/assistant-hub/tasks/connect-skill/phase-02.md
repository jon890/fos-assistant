# Phase 02. Control Plane 이 전문 에이전트의 스킬을 대화 turn 없이 맡긴다

**Execution profile**: standard

## 목표

맡길 수 있는 전문 에이전트의 스킬 목록 API 와 스킬 맡기기 보내기 API 를 두고, 부모 없는 위임 실행을 띄워 결과를 지금 깨우기로 전한다. 예약 작업 지시의 `/에이전트 이름 · 스킬` 도 같은 실행을 쓴다.

**범위 외**: 화면(phase 03).

## 컨텍스트

**요구사항**: REQ10

**근거 문서**: `docs/features/assistant-hub/spec.md`(REQ10, DECISION6, DECISION10, DECISION26, DECISION27), `docs/features/assistant-hub/design.md` 의 「PR3」 과 DECISION6-DECISION9

- 실행: `orchestration/application/AgentRunner`(부모와 루트를 받은 대로 적는다), `DelegationRuns`(전체 한도, 시작·끝 사건), `Handoff`, `config/DelegationProperties`.
- 결과 전달: `usage/execution/infra/AgentExecutionRepository`(`findUndeliveredResults`, `findConversationsWithUndeliveredResults`), `chat/nextturn/application/DelegationWakeService`.
- 맡김 줄: `chat/turn/application/MentionLines`, `MentionInputs`. 질문 잇기: `usage/execution/application/ExecutionQuestions`.
- 질문 저장: 새 `chat/nextturn/application/SkillDelegationQuestions`. `ChatService` 는 500줄이고 `orchestration` 이 부를 수 없다.
- 스킬: `skill/library/application/SkillCommandCatalog`, `chat/turn/application/dto/SkillCommand`.
- 예약 작업: `task/application/TaskRunStarter.runTurn`, `TaskService.requireRunnableCommand`.

## 작업 항목

### 1. 글 꼴

- `chat/turn/application/dto/DelegatedSkillCommand`(에이전트 이름, 스킬, 나머지)와 `parse`. `skillCommand()` 가 `SkillCommand` 를 돌려준다.

### 2. 목록과 보내기

- `orchestration/application/SkillDelegations`: `targets(user)`, `delegate(user, conversationId, agentCode, text)`, `runScheduled(user, conversationId, notice, text)`.
- `orchestration/presentation/SkillDelegationController`: `GET /api/v1/chat/skill-delegations/targets`, `POST /api/v1/chat/skill-delegations`(202).
- `SkillDelegationQuestions`: 새 대화 또는 쓸 수 있는 대화를 정하고 질문(예약이면 알림 줄과 질문)을 저장한다. 실패하면 지운다.
- `DelegationRuns` 에 부모 없는 실행을 돌리는 길을 더한다. 실행 줄이 생기면 질문을 잇고, 예약이면 사람이 없는 실행으로 적는다.

### 3. 결과 전달과 맡김 줄

- 두 조회가 부모 없는 위임 실행도 읽는다. `MentionInputs` 는 위임 키가 있는 맡김을 넣지 않는다.

### 4. 예약 작업

- `TaskRunStarter.runTurn` 이 꼴을 보고 `SkillDelegations.runScheduled` 를 부른다. `TaskService.requireRunnableCommand` 는 이 꼴을 건너뛴다.

### 5. 테스트

- 새 `SkillDelegationTest`(통합): 목록이 기본 비서·꺼진 에이전트·스킬 없는 에이전트를 뺀다. 보내기가 질문과 부모 없는 위임 실행을 만들고 turn 을 열지 않으며, 끝나면 결과 도착 줄과 비서 답이 생긴다. 판정마다의 거절(남의 대화, 읽기 전용, 기본 비서, 꺼진 에이전트, 꺼진 스킬, 꼴이 아님). 답이 도는 동안에도 보낸다.
- `TaskRunStarterTest`: `/이름 · 스킬` 지시가 그 에이전트를 사람이 없는 실행으로 돌리고, 없는 에이전트나 꺼진 스킬이면 `FAILED`.
- `DelegatedSkillCommandTest`(단위): 꼴과 경계.

## 검증

```bash
cd backend && ./gradlew test --tests '*SkillDelegation*' --tests '*DelegatedSkillCommand*' --tests '*TaskRunStarterTest' --tests '*DelegationWakeServiceTest' --tests '*ArchitectureRulesTest'
cd backend && ./gradlew test
node test/e2e/run.ts
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/chat/nextturn/application/SkillDelegationQuestions.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/nextturn/application/dto/SkillDelegationQuestion.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/recovery/application/RestartReconciler.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/turn/application/MentionInputs.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/turn/application/dto/DelegatedSkillCommand.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/orchestration/application/AgentDelegationService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/orchestration/application/ChildExecutionRunner.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/orchestration/application/DelegationRuns.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/orchestration/application/SkillDelegations.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/orchestration/application/dto/SkillDelegationRun.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/orchestration/application/dto/SkillDelegationStarted.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/orchestration/application/dto/SkillDelegationTarget.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/orchestration/presentation/SkillDelegationController.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/orchestration/presentation/SkillDelegationDtos.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/task/application/TaskRunStarter.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/task/application/TaskService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/usage/execution/infra/AgentExecutionRepository.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/DelegatedSkillCommandTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/orchestration/SkillDelegationTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/task/TaskRunStarterTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/testsupport/PrimaryAgentTestSupportController.java` | 수정 |
| `test/e2e/connector-support.ts` | 수정 |
| `test/e2e/scenarios/connector-binding.ts` | 수정 |
| `test/e2e/scenarios/proactive-check.ts` | 수정 |
