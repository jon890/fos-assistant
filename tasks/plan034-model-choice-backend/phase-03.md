# Phase 03. 실행이 대화가 고른 모델과 effort 로 Hermes 를 부르고 넘기지 않는다

**Execution profile**: deep

## 목표

보내기, 다시 생성, Memory 제안, 흐름의 하위 실행이 대화에 적힌 선택으로 Hermes 를 부른다.
고르지 않은 대화는 모델을 빼고 보내 profile 기본값으로 돈다. provider 가 막혀도 다른 모델로 넘기지 않고 `PROVIDER_BLOCKED` 로 실패한다.

**범위 외**: 고를 수 있는 모델 목록 경로는 phase 04 다. 에이전트 모델 목록 표와 `AgentModelSelector`, `ProviderBlocklist` 를 지우는 것은 plan035 다. 이 phase 는 그것들을 부르지 않게만 한다. 모델을 고르는 화면은 plan036 이다. 이 phase 의 web 변경은 막힘 문구와 브라우저 검사뿐이다.

## 컨텍스트

**근거 문서**: `docs/flow.md` 「모델을 고를 때」 의 「갈리는 지점」 과 「실행이 실패할 때」, `docs/data-schema.md` 「agent_execution」, `docs/code-architecture.md` 「대화의 모델 선택」, `docs/adr/ADR-030-모델과-effort-는-대화가-고르고-기본값은-hermes-profile-이-갖는다.md`

phase 02 가 끝난 상태다. `Conversation.modelChoice()`, `ModelChoice`, `ChatService.chooseModel`, `AgentExecution.reasoningEffort` 가 있다.

- 지금 `ChatService` 의 turn 은 `modelSelector.availableFor(agent)` 의 목록을 차례로 돌며, provider 가 막히면(`HermesRunResult.providerBlocked()`) `blocklist.block` 뒤 다음 순위로 새 실행 줄을 만든다(`PROVIDER_SWITCHED` 사건, `ChatEvent.switched`, `ChatEvent.reset`). 이 루프를 없앤다
- 다시 생성은 `routeExisting` 을 거쳐 보내기와 같은 `runTurn` 또는 `runFlow` 로 간다. 따로 도는 경로가 없어 turn 을 고치면 함께 바뀐다
- `backend/src/main/java/com/bifos/assistant/orchestration/application/AgentRunner.java` 는 `modelSelector.availableFor(agent)` 의 첫 줄을 쓰고, 없으면 `NO_MODEL_AVAILABLE` 로 실패시킨다
- `backend/src/main/java/com/bifos/assistant/memory/application/MemoryProposer.java` 의 `proposeFrom(user, conversation, agent, parentExecution, answer)` 도 첫 줄을 쓴다
- `backend/src/main/java/com/bifos/assistant/usage/application/ExecutionRecorder.java` 의 `start(..., ModelOption requested, Long retryOfExecutionId)`, `complete(execution, agent, result, requested)`, `cancel(execution, agent, result, requested)` 가 `agent/domain/ModelOption requested` 를 받는다. 실제로 돈 값은 `readActualRuntime` 이 세션에서 읽고, 없으면 요청한 값을 적는다
- 기본값으로 보냈고 세션 조회도 답하지 못하면 실행 줄의 provider 와 모델은 비고 금액도 비어 있다(`docs/data-schema.md` 「agent_execution」 의 `provider`, `model` 줄)
- backend 테스트의 Hermes 대역은 `backend/src/test/java/com/bifos/assistant/hermes/StubHermesRunsClient.java` 다. `willReportSessionRuntime(new SessionRuntime(model, provider))` 로 세션 조회의 답을 정한다. 기본은 null(읽지 못함)이다
- `ErrorCode.PROVIDER_BLOCKED`(502)는 이미 있다

## 의도 메모

- `ExecutionEventType.PROVIDER_SWITCHED` 는 지우지 않는다. 예전 실행의 사건 행이 그 이름으로 저장돼 있다. 새로 쓰지만 않는다
- `ChatEvent.switched` 와 `reset` 은 화면이 아직 받는다. 이 phase 는 보내지 않게만 하고, 받는 쪽 정리는 plan036 의 몫이다
- 흐름의 하위 실행도 대화의 선택을 쓴다. 자식 에이전트가 달라도 사용자가 이 대화에서 고른 모델이 그 대화의 모든 실행에 적용된다는 규칙을 하나로 둔다(`docs/flow.md` 「갈리는 지점」)
- `NO_MODEL_AVAILABLE` 은 더 생기지 않는다. 코드 값은 plan035 가 지운다
- `ExecutionRecorder.start` 의 `retryOfExecutionId` 인자는 이제 늘 null 이다. 칸과 인자는 plan035 가 정리하므로 이 phase 는 null 을 넘긴다
- 테스트에서 `modelSelector.seedFirst(...)` 로 준비만 하는 곳은 그대로 둔다. 선택기는 plan035 가 지운다. 기록된 provider, 모델, 금액을 단언하는 곳만 준비를 바꾼다
- 배포 뒤 실제 실행을 한 번 왕복시켜 확인한다(`AGENTS.md` 「배포했다고 말하기 전에 보는 것」): 기본값 대화의 실행이 세션의 실제 provider 와 모델을 적는가, effort 를 고른 대화의 실행이 성공하고 실행 줄에 effort 가 남는가. 확인 방법은 `fos-home-infra` 가 갖는다

## 작업 항목

### 1. `ExecutionRecorder`

- `ModelOption requested` 인자를 `chat/domain/ModelChoice requested` 로 바꾼다(`start`, `complete`, `cancel`)
- `start` 는 `provider`, `model` 에 선택의 값을(비면 null), `reasoningEffort` 에 effort 를 적는다
- 끝난 실행의 provider 와 모델은 지금처럼 세션에서 읽은 실제 값을 먼저 쓰고, 없으면 선택의 값, 그것도 없으면 null 이다
- Javadoc 의 「넘김이 일어난 실행」 같은 넘김 전제의 문장을 지금 동작에 맞게 고친다

### 2. `ChatService` 의 turn

- turn 은 `routed.conversation().modelChoice()` 하나로 실행 줄을 만들고 제출한다. `modelSelector` 와 `blocklist` 필드, 순위 루프, `noModelAvailable` 을 없앤다
- 결과가 `providerBlocked()` 이면 실행을 `PROVIDER_BLOCKED` 로 실패시키고 `RUN_FAILED` 사건을 남긴 뒤 `ApiException(ErrorCode.PROVIDER_BLOCKED, ...)` 을 던진다. 다음 모델로 넘기지 않고 `ChatEvent.switched`, `ChatEvent.reset` 을 보내지 않는다
- `begin` 은 `HermesRunCommand` 에 선택의 provider, 모델, effort 를 싣는다. 비어 있으면 null 그대로다

### 3. `AgentRunner`, `MemoryProposer`

둘 다 `conversation.modelChoice()` 를 `HermesRunCommand` 와 `ExecutionRecorder` 에 넘긴다. `modelSelector` 필드와 `NO_MODEL_AVAILABLE` 분기를 없앤다.

### 4. web 의 막힘 문구

- `web/src/components/error-message.ts`: `PROVIDER_BLOCKED` 안내 「이 모델은 지금 쓸 수 없어요. 다른 모델을 골라 다시 보내 주세요.」 를 더한다
- `web/src/components/usage/execution-list.tsx`: `PROVIDER_BLOCKED` 의 표시를 「다음 모델로 다시 시도함」 에서 「모델을 쓸 수 없음」 으로 바꾼다

### 5. 브라우저 검사

넘김을 없애면 넘김 알림을 검사하던 브라우저 검사가 깨진다.
- `test/browser/chat.spec.ts` 의 「막혀서 넘어가면 그 답 위에 넘어간 곳을 한 줄로 알린다」 를 「막힌 모델을 고른 대화는 넘기지 않고 실패한다」 로 바꾼다. `POST /api/chat/conversations` 로 빈 대화를 만들고 phase 02 의 `PUT /api/chat/conversations/{id}/model` 로 막힌 provider 와 모델을 고른 뒤 보내면 응답이 실패이고 `PROVIDER_BLOCKED` 다. `provider-switched` 알림이 없다
- `test/browser/usage.spec.ts` 의 「막혀서 넘어간 실패와 보통 실패를 다르게 보인다」 를 같은 준비로 바꾸고, 실행 기록에 「모델을 쓸 수 없음」 이 보이는지 본다. 넘김이 없으므로 `execution-retry-of` 단언은 지운다
- 두 검사가 관리자 경로 `PUT /api/admin/agents/{code}/models` 로 에이전트 모델 목록을 심던 준비는 지운다. `test/browser/fixtures.ts` 의 `SWITCH_AGENT_CODE` 에이전트는 막힘 검사용으로 그대로 쓰고, 이름 「넘김 비서」 는 「막힘 비서」 로 바꾼다

### 6. e2e `test/e2e/scenarios/model-selection.ts`

관리자 모델 목록과 넘김을 검사하던 단계를 지우고 새 동작으로 다시 쓴다.
- 새 대화의 실행은 가짜 Hermes 의 `lastSubmittedRuntime()` 에 provider 와 모델이 없다. 실행 목록의 `provider`, `model` 이 가짜 Hermes 의 기본값(`openai-codex`, `example-model`)이다
- `PUT /chat/conversations/{id}/model` 뒤 보내면 고른 provider, 모델, effort 가 실린다. 실행 목록의 `model` 이 고른 값이다
- 막힌 provider 를 고르면 `PROVIDER_BLOCKED` 로 실패한다(가짜 Hermes 의 `blockProvider`)

`test/e2e/scenarios/usage-cost.ts` 는 고치지 않고 그대로 통과해야 한다. 기본값 대화의 provider 와 모델이 가짜 Hermes 세션의 기본값이라 가격을 찾는다.

### 7. 이 phase 를 검증하는 backend 테스트

`backend/src/test/java/com/bifos/assistant/chat/ModelSelectionTest.java` 를 새 동작으로 다시 쓴다. 넘김을 검사하던 경우는 지운다.
- 고르지 않은 대화의 실행 요청에 provider, 모델, effort 가 모두 없다. 실행 줄의 `reasoningEffort` 가 null 이다
- 모델과 effort 를 고른 뒤 보내면 요청에 셋이 실리고 실행 줄의 `reasoningEffort` 가 고른 값이다. 세션이 다른 모델을 답하면 그 값을 적는다
- 기본값으로 보냈고 세션이 답하지 못하면 실행 줄의 provider, 모델, 금액이 비어 있다
- provider 가 막히면 `PROVIDER_BLOCKED` 로 실패하고 Hermes 를 한 번만 부른다. `PROVIDER_SWITCHED` 사건이 남지 않는다
- 선택을 바꾼 뒤 이어 보내면 바꾼 값이 실린다

경우 하나씩 더한다.
- `backend/src/test/java/com/bifos/assistant/chat/ChatRegenerateTest.java`: 모델을 고른 대화에서 다시 생성하면 요청에 그 선택이 실린다
- `backend/src/test/java/com/bifos/assistant/chat/ChatMemoryProposalTest.java`: 모델을 고른 대화의 Memory 제안 실행이 그 선택으로 Hermes 를 부른다
- `backend/src/test/java/com/bifos/assistant/orchestration/ChildExecutionRunnerTest.java`: 모델을 고른 대화의 자식 실행이 그 선택으로 Hermes 를 부른다. 자식 에이전트의 모델 목록을 흉내 내던 `modelSelector.replace(childAgent, ...)` 경우는 이 경우로 바꾼다

바뀐 동작에 맞게 고치는 기존 테스트다.

| 파일 | 경우 | 처리 |
| --- | --- | --- |
| `backend/src/test/java/com/bifos/assistant/chat/ChatServiceTest.java` | 두 순위로 넘김을 검사하던 경우 | 「막히면 넘기지 않고 `PROVIDER_BLOCKED` 로 실패한다」 로 바꾼다 |
| 같음 | 첫 보내기가 provider `anthropic`, 모델 `example-model-large` 를 적는다는 단언 | `willReportSessionRuntime(new SessionRuntime("example-model-large", "anthropic"))` 로 세션이 답하게 준비한다 |
| 같음 | `records_the_bound_model_when_the_run_only_echoes_the_profile_name` | 전제가 사라졌다. 「기본값으로 보냈고 세션이 답하지 못하면 provider 와 모델이 비어 있다」 로 바꾼다 |
| `backend/src/test/java/com/bifos/assistant/chat/ChatRunningTurnTest.java` | 두 순위로 넘김을 검사하던 경우 | 「막히면 넘기지 않고 실패한다」 로 바꾼다 |
| `backend/src/test/java/com/bifos/assistant/chat/ConversationManageTest.java` | `provider를_넘어가면_새_실행_번호를_다시_보낸다` | 지운다. 새 실행 줄이 더 생기지 않는다 |
| 같음 | `모델이_없어도_실패한_실행의_번호를_보낸다` | 「provider 가 막혀 실패해도 실패한 실행의 번호를 보낸다」 로 바꾼다. 오류 코드는 `PROVIDER_BLOCKED` 다 |
| `backend/src/test/java/com/bifos/assistant/chat/ChatStopTest.java` | `provider_전환_뒤에는_새_실행만_중지_대상이고_이전_실행은_끝난_것으로_응답한다` | 지운다. 전환이 없다 |
| `backend/src/test/java/com/bifos/assistant/orchestration/ResearchAndBuildFlowTest.java` | `pricedExecutions == 4` 와 뿌리의 `estimatedCostMicros isNotNull` | 가격표에 있는 provider 와 모델(지금 이 테스트가 에이전트에 심는 값)로 `willReportSessionRuntime` 을 준비한다 |
| `backend/src/test/java/com/bifos/assistant/memory/MemoryProposerTest.java` | `AgentModelSelector` 를 mock 으로 흉내 내던 곳 | 대화의 선택을 넘기게 바꾼다 |
| `backend/src/test/java/com/bifos/assistant/usage/ExecutionLifecycleTest.java` | `requested(agent)` 가 `ModelOption` 을 돌려준다 | `new ModelChoice(agent.provider(), agent.model(), null)` 을 돌려준다 |
| `backend/src/test/java/com/bifos/assistant/usage/UsageCostRecordingTest.java` | 같음 | 같음 |

위 표에 없는 테스트가 기록된 provider, 모델, 금액을 에이전트의 모델 목록에서 온 값으로 단언해 깨지면 같은 원칙으로 고친다. 세션이 답하게 준비하거나 대화에 모델을 고른다. 단언을 지우거나 약하게 하지 않는다. 그렇게 고친 파일은 회신에 적는다.

## 검증

`AGENTS.md` 「확인」 절의 여섯 명령을 적힌 순서대로 모두 돌린다. 앞의 두 줄은 이 phase 의 테스트만 먼저 돌리는 것이다.

```bash
# cwd: 저장소 root
cd backend && ./gradlew test --tests '*ModelSelectionTest' --tests '*ChatRegenerateTest' --tests '*ChatMemoryProposalTest' --tests '*ChildExecutionRunnerTest' --tests '*ConversationManageTest' --tests '*ChatStopTest' --tests '*ResearchAndBuildFlowTest'
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
- `grep -rn "agent.domain.ModelOption" backend/src/main/java/com/bifos/assistant/usage` 가 아무것도 내지 않는다

## 변경 파일

| 파일 | 변경 |
| --- | --- |
| `backend/src/main/java/com/bifos/assistant/chat/application/ChatService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/orchestration/application/AgentRunner.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/memory/application/MemoryProposer.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/usage/application/ExecutionRecorder.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/ModelSelectionTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/ChatRegenerateTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/ChatMemoryProposalTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/ChatServiceTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/ChatRunningTurnTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/ConversationManageTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/ChatStopTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/orchestration/ChildExecutionRunnerTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/orchestration/ResearchAndBuildFlowTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/memory/MemoryProposerTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/usage/ExecutionLifecycleTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/usage/UsageCostRecordingTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/**/*Test.java` | 수정 |
| `test/e2e/scenarios/model-selection.ts` | 수정 |
| `web/src/components/error-message.ts` | 수정 |
| `web/src/components/usage/execution-list.tsx` | 수정 |
| `test/browser/chat.spec.ts` | 수정 |
| `test/browser/usage.spec.ts` | 수정 |
| `test/browser/fixtures.ts` | 수정 |
