# Phase 02. 대화가 고른 모델과 effort 를 저장하고 실행에 싣는다

**Execution profile**: deep

## 목표

대화마다 모델과 reasoning effort 를 저장하고, 보내기, 다시 생성, Memory 제안, 흐름의 하위 실행이 그 값으로 Hermes 를 부른다.
고르지 않은 대화는 모델을 빼고 보내 profile 기본값으로 돈다. provider 가 막혀도 다른 모델로 넘기지 않는다.

**범위 외**: 고를 수 있는 모델 목록 경로는 phase 03 이다. 에이전트 모델 목록 표와 `AgentModelSelector`, `ProviderBlocklist` 를 지우는 것은 plan035 다. 이 phase 는 그것들을 부르지 않게만 한다. 모델을 고르는 화면은 plan036 이다. 이 phase 의 web 변경은 브라우저 검사가 선택을 넣을 수 있는 서버 라우트와 막힘 문구뿐이다.

## 컨텍스트

**근거 문서**: `docs/flow.md` 「모델을 고를 때」 의 「갈리는 지점」 과 「실행이 실패할 때」, `docs/data-schema.md` 「conversation」 과 「agent_execution」, `docs/code-architecture.md` 「대화」 의 「경로」 와 「대화의 모델 선택」, `docs/adr/ADR-030-모델과-effort-는-대화가-고르고-기본값은-hermes-profile-이-갖는다.md`

- 지금 `ChatService` 의 turn 은 `modelSelector.availableFor(agent)` 의 목록을 차례로 돌며, provider 가 막히면(`HermesRunResult.providerBlocked()`) `blocklist.block` 뒤 다음 순위로 새 실행 줄을 만든다(`PROVIDER_SWITCHED` 사건, `ChatEvent.switched`, `ChatEvent.reset`). 이 루프를 없앤다
- `orchestration/application/AgentRunner.java` 는 `modelSelector.availableFor(agent)` 의 첫 줄을 쓰고, 없으면 `NO_MODEL_AVAILABLE` 로 실패시킨다
- `memory/application/MemoryProposer.java` 의 `proposeFrom(user, conversation, agent, parentExecution, answer)` 도 첫 줄을 쓴다
- `usage/application/ExecutionRecorder.java` 의 `start`, `complete`, `cancel(execution, agent, result, requested)` 가 `agent/domain/ModelOption requested` 를 받는다. 실제로 돈 값은 `readActualRuntime` 이 세션에서 읽고, 없으면 요청한 값을 적는다
- 대화 이름 바꾸기는 `ConversationRepository.renameIfActive` 로 칸만 고친다. **turn 이 끝날 때 대화를 통째로 저장하지 않는다**(`docs/code-architecture.md` 「대화」). 모델 선택도 같은 방식의 갱신 질의로 쓴다
- 마지막 migration 은 `V25__group_rename.sql` 이다. 새 파일은 `V26` 이다. 이미 적용된 migration 은 고치지 않는다(`backend/AGENTS.md`)
- `ErrorCode.PROVIDER_BLOCKED`(502)는 이미 있다

## 의도 메모

- `ExecutionEventType.PROVIDER_SWITCHED` 는 지우지 않는다. 예전 실행의 사건 행이 그 이름으로 저장돼 있다. 새로 쓰지만 않는다
- `ChatEvent.switched` 와 `reset` 은 화면이 아직 받는다. 이 phase 는 보내지 않게만 하고, 받는 쪽 정리는 plan036 의 몫이다
- 흐름의 하위 실행도 대화의 선택을 쓴다. 자식 에이전트가 달라도 사용자가 이 대화에서 고른 모델이 그 대화의 모든 실행에 적용된다는 규칙을 하나로 둔다
- `NO_MODEL_AVAILABLE` 은 더 생기지 않는다. 코드 값은 plan035 가 지운다
- 선택을 바꿔도 `updated_at` 은 건드리지 않는다. 대화 목록의 순서는 주고받은 시각으로 정한다

## 작업 항목

### 1. migration `backend/src/main/resources/db/migration/V26__conversation_model_choice.sql`

- `conversation` 에 `model_provider VARCHAR(64) NULL`, `model VARCHAR(128) NULL`, `reasoning_effort VARCHAR(16) NULL`
- `agent_execution` 에 `reasoning_effort VARCHAR(16) NULL`
- MySQL 과 H2 MySQL 모드에 함께 있는 문법만 쓴다

### 2. `backend/src/main/java/com/bifos/assistant/chat/domain/ModelChoice.java`

record `ModelChoice(String provider, String model, String reasoningEffort)`.
- 앞뒤 공백을 떼고 빈 문자열은 null 로 둔다
- provider 와 모델은 함께 채우거나 함께 비운다. 하나만 오면 `ApiException(VALIDATION_FAILED)`
- effort 는 null 이거나 `low`, `medium`, `high`, `xhigh`, `max` 중 하나. 아니면 `VALIDATION_FAILED`
- `static ModelChoice defaults()` 는 셋 다 null, `boolean usesDefaultModel()` 은 모델이 비었는가

### 3. `Conversation` 과 `ConversationRepository`

`chat/domain/Conversation.java` 에 세 칸과 `ModelChoice modelChoice()` 를 더한다.
`chat/infra/ConversationRepository.java` 에 `chooseModelIfActive(id, userId, provider, model, reasoningEffort)` 갱신 질의를 `renameIfActive` 와 같은 모양으로 더한다(`updatedAt` 은 건드리지 않는다).

### 4. `ChatService` 의 선택 변경과 turn

- `public Conversation chooseModel(CurrentUser user, Long conversationId, ModelChoice choice)` 를 `rename` 과 같은 순서로 둔다
- turn 은 `routed.conversation().modelChoice()` 하나로 실행 줄을 만들고 제출한다. `modelSelector` 와 `blocklist` 필드와 루프, `noModelAvailable` 을 없앤다
- 결과가 `providerBlocked()` 이면 실행을 `PROVIDER_BLOCKED` 로 실패시키고 `RUN_FAILED` 를 남긴 뒤 `ApiException(ErrorCode.PROVIDER_BLOCKED, ...)` 을 던진다. 다음 모델로 넘기지 않는다
- `begin` 은 `HermesRunCommand` 에 선택의 provider, 모델, effort 를 싣는다. 비어 있으면 null 그대로다
- 다시 생성도 같은 turn 경로를 타므로 같은 선택을 쓴다. 다르게 도는 곳이 있으면 맞춘다

### 5. `AgentRunner`, `MemoryProposer`

둘 다 `conversation.modelChoice()` 를 쓴다. `modelSelector` 필드와 `NO_MODEL_AVAILABLE` 분기를 없앤다.

### 6. `ExecutionRecorder` 와 `AgentExecution`

- `ModelOption requested` 인자를 `ModelChoice requested` 로 바꾼다. `start` 는 `provider`, `model` 에 선택의 값을(비면 null), 새 칸 `reasoningEffort` 에 effort 를 적는다
- `usage/domain/AgentExecution.java` 에 `@Column(name = "reasoning_effort", length = 16) String reasoningEffort` 와 builder, 읽기 메서드를 더한다
- 끝난 실행의 provider 와 모델은 지금처럼 세션에서 읽은 실제 값을 먼저 쓴다

### 7. 경로 `PUT /api/v1/chat/conversations/{conversationId}/model`

- `chat/presentation/ChatDtos.java` 에 `ChooseModelRequest(String provider, String model, String reasoningEffort)` 와 `toChoice()`
- `ConversationView` 에 `provider`, `model`, `reasoningEffort` 칸을 더한다. 목록과 이름 바꾸기 응답도 같은 모양이 된다
- `chat/presentation/ChatController.java` 에 경로를 더하고 `access.requireOwnId` 로 주인을 확인한다. 바뀐 대화 한 줄을 돌려준다

### 8. 이 phase 를 검증하는 backend 테스트

`backend/src/test/java/com/bifos/assistant/chat/ModelSelectionTest.java` 를 새 동작으로 다시 쓴다. 넘김을 검사하던 경우는 지운다.
- 고르지 않은 대화의 실행 요청에 provider, 모델, effort 가 모두 없다. 실행 줄의 `reasoningEffort` 가 null 이다
- 모델과 effort 를 고른 뒤 보내면 요청에 셋이 실리고 실행 줄의 `reasoningEffort` 가 고른 값이다. 세션이 다른 모델을 답하면 그 값을 적는다
- provider 가 막히면 `PROVIDER_BLOCKED` 로 실패하고 Hermes 를 한 번만 부른다. `PROVIDER_SWITCHED` 사건이 남지 않는다
- `chooseModel` 에 provider 만 주거나 모르는 effort 를 주면 `VALIDATION_FAILED`, 남의 대화면 `CONVERSATION_NOT_FOUND`
- 다시 생성과 Memory 제안이 대화의 선택을 쓴다(`ChatRegenerateTest`, `ChatMemoryProposalTest` 에 경우 하나씩)

두 순위로 넘김을 검사하던 `ChatServiceTest` 와 `ChatRunningTurnTest` 의 경우는 「막히면 넘기지 않고 실패한다」 로 바꾼다.
`ChildExecutionRunnerTest`, `ResearchAndBuildFlowTest`, `MemoryProposerTest` 에서 `AgentModelSelector` 를 흉내 내던 곳은 대화의 선택으로 바꾼다.
H2 로 모든 migration 을 도는 `*MigrationTest` 가 V26 을 함께 통과해야 한다.

### 9. web 의 얇은 변경과 브라우저 검사

넘김을 없애면 넘김 알림을 검사하던 브라우저 검사가 깨진다. 같은 phase 에서 고친다.
- `web/src/app/api/chat/conversations/[conversationId]/model/route.ts`: `PUT` 을 Control Plane `PUT /api/v1/chat/conversations/{id}/model` 로 넘긴다. 같은 폴더의 `route.ts` 의 `PATCH` 가 본보기다(대화 주소 검사와 오류 응답)
- `web/src/components/error-message.ts`: `PROVIDER_BLOCKED` 안내 「이 모델은 지금 쓸 수 없어요. 다른 모델을 골라 다시 보내 주세요.」 를 더한다
- `web/src/components/usage/execution-list.tsx`: `PROVIDER_BLOCKED` 의 표시를 「다음 모델로 다시 시도함」 에서 「모델을 쓸 수 없음」 으로 바꾼다
- `test/browser/chat.spec.ts` 의 「막혀서 넘어가면 그 답 위에 넘어간 곳을 한 줄로 알린다」 를 「막힌 모델을 고른 대화는 넘기지 않고 실패한다」 로 바꾼다. 빈 대화를 만들고 새 라우트로 막힌 provider 를 고른 뒤 보내면 응답이 실패이고 `PROVIDER_BLOCKED` 다. `provider-switched` 알림이 없다
- `test/browser/usage.spec.ts` 의 「막혀서 넘어간 실패와 보통 실패를 다르게 보인다」 를 같은 준비로 바꾸고, 실행 기록에 「모델을 쓸 수 없음」 이 보이는지 본다

### 10. e2e `test/e2e/scenarios/model-selection.ts`

관리자 모델 목록과 넘김을 검사하던 단계를 지우고 새 동작으로 다시 쓴다.
- 새 대화의 실행은 가짜 Hermes 의 `lastSubmittedRuntime()` 에 provider 와 모델이 없다
- `PUT /chat/conversations/{id}/model` 뒤 보내면 고른 provider, 모델, effort 가 실린다. 실행 목록의 `model` 이 고른 값이다
- 막힌 provider 를 고르면 `PROVIDER_BLOCKED` 로 실패한다(가짜 Hermes 의 `blockProvider`)

## 검증

`AGENTS.md` 「확인」 절의 여섯 명령을 적힌 순서대로 모두 돌린다. 새 워크트리라 `web` 에서 `pnpm install --frozen-lockfile` 이 먼저 필요하다. 첫 줄은 이 phase 의 테스트만 먼저 돌리는 것이다.

```bash
# cwd: 저장소 root
cd backend && ./gradlew test --tests '*ModelSelectionTest' --tests '*ChatRegenerateTest' --tests '*ChatMemoryProposalTest' --tests '*MigrationTest'
cd web && pnpm test:browser chat.spec.ts usage.spec.ts
cd backend && ./gradlew test
cd web && pnpm typecheck
cd web && AUTH_SECRET=build-time-placeholder ASSISTANT_JWT_SECRET=build-time-placeholder CONTROL_PLANE_BASE_URL=http://build-time-placeholder AUTH_GOOGLE_ID=build-time-placeholder AUTH_GOOGLE_SECRET=build-time-placeholder pnpm build
cd web && pnpm test:browser
node test/e2e/run.ts
node --test 'test/unit/**/*.test.ts'
scripts/check-public-safe.sh
```

- 모두 통과한다
- `grep -rn "modelSelector\|blocklist\." backend/src/main/java/com/bifos/assistant/chat backend/src/main/java/com/bifos/assistant/orchestration backend/src/main/java/com/bifos/assistant/memory` 가 아무것도 내지 않는다

## 변경 파일

| 파일 | 변경 |
| --- | --- |
| `backend/src/main/resources/db/migration/V26__conversation_model_choice.sql` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/domain/ModelChoice.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/domain/Conversation.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/infra/ConversationRepository.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/application/ChatService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/presentation/ChatController.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/presentation/ChatDtos.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/orchestration/application/AgentRunner.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/memory/application/MemoryProposer.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/usage/application/ExecutionRecorder.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/usage/domain/AgentExecution.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/ModelSelectionTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/ChatRegenerateTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/ChatMemoryProposalTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/ChatServiceTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/ChatRunningTurnTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/orchestration/ChildExecutionRunnerTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/orchestration/ResearchAndBuildFlowTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/memory/MemoryProposerTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/**/*Test.java` | 수정 |
| `test/e2e/scenarios/model-selection.ts` | 수정 |
| `test/e2e/fake-hermes.ts` | 수정 |
| `web/src/app/api/chat/conversations/[[]conversationId]/model/route.ts` | 신규 |
| `web/src/components/error-message.ts` | 수정 |
| `web/src/components/usage/execution-list.tsx` | 수정 |
| `test/browser/chat.spec.ts` | 수정 |
| `test/browser/usage.spec.ts` | 수정 |
