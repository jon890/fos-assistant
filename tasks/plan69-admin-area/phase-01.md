# Phase 01. MEMBER 역할 응답에서 내부 값을 뺀다

**Execution profile**: deep

## 목표

Control Plane 이 일반 경로의 응답을 만들 때 요청자가 `ADMIN` 이 아니면 금액, 모델, 토큰, 시각 구간, 설정 구분값을 비운다.
화면에서만 가리면 브라우저의 개발자 도구로 그 값이 보이기 때문이다.

**범위 외**: 화면의 구조를 바꾸는 것(phase 02 부터). 관리자 API 경로(`/api/v1/admin/**`). `GET /api/v1/chat/model-options` 와 대화 응답의 모델 값(사용자가 쓰는 고급 모델 선택의 입력이라 빼지 않는다).

## 컨텍스트

- 빼는 값과 빼지 않는 값의 정본은 `docs/backend/conversation.md` 의 「역할에 따라 응답에서 빼는 값」 절이다. 그 표와 구현이 한 줄도 다르지 않아야 한다. 구현하다 표와 달라져야 할 까닭을 찾으면 멈추고 `PHASE_BLOCKED` 로 알린다
- 이미 같은 일을 하는 선례가 있다. 도구 `detail` 이다
  - `backend/src/main/java/com/bifos/assistant/usage/application/ToolDetailPolicy.java` 가 판정한다
  - `backend/src/main/java/com/bifos/assistant/usage/application/ExecutionEventView.java` 가 실행 트리 응답을 만들 때 `detail` 을 비운다
  - `backend/src/main/java/com/bifos/assistant/chat/application/ChatEvent.java` 의 `forViewer(viewer)` 가 SSE 사건을 줄인다. 호출처는 `ChatController` 둘과 `ConversationEventController` 하나다
- 요청자는 `CurrentUserProvider.require()` 가 주는 `CurrentUser` 이고 `isAdmin()` 으로 역할을 본다. `UsageController` 는 이미 `currentUser` 를 갖고 있다
- DTO 는 Java record 다. 빼는 값은 `null` 로 채운다. 원시형(`long`, `boolean`)인 칸은 `Long` 으로 바꿔 `null` 을 실을 수 있게 한다

**근거 문서**: `docs/backend/conversation.md` 의 「역할에 따라 응답에서 빼는 값」 절, `docs/adr/ADR-060-관리자-전용-표시와-동작은-관리자-영역에만-두고-일반-경로의-응답은-서버가-역할에-따라-줄인다.md`, `docs/adr/ADR-038-도구의-명령-원문은-관리자에게만-보내고-사용자에게는-사람-말로-보인다.md`

## 의도 메모

- 오류 코드는 빼지 않는다. 화면이 코드를 사람 말 문구로 바꾸고 분기에도 쓴다(`web/src/components/usage/execution-list.tsx`, `web/src/components/execution/execution-event-row.tsx`, `web/src/components/chat-panel.tsx`)
- `subagentName`, `latencyMs`, `modelTier`, `skillNames` 는 빼지 않는다
- 판정을 DTO 마다 흩지 않는다. 「요청자가 내부 값을 받는가」 를 답하는 자리를 하나 두고(예: `usage/application` 에 `InternalValuePolicy.visibleTo(CurrentUser)`) 각 DTO 의 조립이 그것을 부른다. `ToolDetailPolicy` 옆에 둔다
- `ADMIN` 의 응답은 한 칸도 바뀌지 않아야 한다. `monthly-cost` 에 `totalExecutions` 가 더해지는 것만 예외다

## 작업 항목

### 1. 사용량 응답 (`UsageController`, `UsageDtos`)

파일: `backend/src/main/java/com/bifos/assistant/usage/presentation/UsageController.java`, `backend/src/main/java/com/bifos/assistant/usage/presentation/UsageDtos.java`

- `GET /api/v1/usage/executions`: `ExecutionView` 의 `agentCode`, `provider`, `model`, `reasoningEffort`, `costMode`, `runtimeFingerprint`, `instructionsHash`, `costCurrency`, `pricingVersion`, `inputTokens`, `cachedInputTokens`, `outputTokens`, `totalTokens`, `contextChars`, `contextOmittedItems`, `estimatedCostMicros`, `actualCostMicros` 를 `MEMBER` 에게 `null` 로 보낸다. `errorCode` 는 그대로 둔다
- `GET /api/v1/usage/monthly-cost`: `MonthlyCostView` 에 `totalExecutions`(long, 그 달 실행 건수. 지금의 `pricedExecutions + unpricedExecutions`)를 더해 모두에게 싣는다. `currency`, `estimatedCostMicros`, `actualCostMicros`, `pricedExecutions`, `unpricedExecutions`, `subscriptionExecutions` 는 `MEMBER` 에게 `null` 이다
- `GET /api/v1/usage/breakdown`: 맨 앞에서 `currentUser.requireAdmin()` 을 부른다. `MEMBER` 는 `FORBIDDEN` 을 받는다
- `GET /api/v1/usage/skills`: `MySkillUsageView.agentCode` 를 `MEMBER` 에게 `null` 로 보낸다

### 2. 실행 트리 응답

파일: `backend/src/main/java/com/bifos/assistant/usage/application/ExecutionTreeService.java`, `backend/src/main/java/com/bifos/assistant/usage/application/ExecutionNode.java`, `backend/src/main/java/com/bifos/assistant/usage/application/ExecutionEventView.java`

- 노드: `agentCode`, `provider`, `model`, `reasoningEffort`, `reasoningEffortSource`, `inputTokens`, `cachedInputTokens`, `outputTokens`, `totalTokens`, `estimatedCostMicros`, `requestReceivedAt`, `submittedAt`, `firstDeltaAt`, `finishedAt` 를 `MEMBER` 에게 `null` 로 보낸다
- 사건: `model`, `inputTokens`, `outputTokens` 를 `MEMBER` 에게 `null` 로 보낸다. `PROVIDER_SWITCHED` 사건은 `MEMBER` 의 응답에서 사건째 뺀다
- 도구 `detail` 의 지금 판정은 그대로다

### 3. 대화 응답과 SSE

파일: `backend/src/main/java/com/bifos/assistant/chat/presentation/ChatController.java`, `backend/src/main/java/com/bifos/assistant/chat/application/ChatEvent.java`, `backend/src/main/java/com/bifos/assistant/chat/presentation/ConversationEventController.java`, `backend/src/main/java/com/bifos/assistant/chat/application/ModelTierService.java`

- `GET /api/v1/chat/conversations/{id}/messages`: `MessageView.switchedTo` 를 `MEMBER` 에게 `null` 로 보낸다. 비우는 자리는 `ChatService.switchedLabels` 다. 요청자를 인자로 받아 `ADMIN` 이 아니면 빈 `Map` 을 돌려준다. `ChatController` 의 호출처가 요청자를 넘긴다
- `ChatEvent.forViewer(viewer)`: `type` 이 `subagent` 면 `model`, `inputTokens`, `outputTokens` 를 `MEMBER` 에게 비운다. `type` 이 `switched` 인 사건은 `MEMBER` 에게 보내지 않는다. `forViewer` 가 「보내지 않는다」 를 표현할 수 있게 반환을 `Optional<ChatEvent>` 로 바꾸거나 호출처 셋이 거르게 한다. 호출처 셋 모두에 같은 방식으로 적용한다
- `GET /api/v1/chat/model-tiers`: 단계 한 줄의 `provider`, `model`, `reasoningEffort` 를 `MEMBER` 에게 `null` 로 보낸다. `tier`, `label`, `userDefaultTier`, `groupDefaultTier`, `admin` 은 그대로다

### 4. web 의 타입과 `MEMBER` 화면

- `web/src/components/usage/monthly-summary.tsx`: `MonthlyCost` 타입에 `totalExecutions: number` 를 더하고 금액과 건수 구분 칸을 `number | null` 로 둔다. 관리자가 아닐 때의 실행 건수는 `totalExecutions` 로 그린다
- `web/src/components/usage/skill-usage-list.tsx`: React key 에 `agentCode` 를 쓰지 않는다. `skillName` 과 `agentName` 과 순번으로 만든다. `web/src/lib/skill.ts` 의 `SkillUsageRow.agentCode` 를 `string | null` 로 바꾼다
- `web/src/components/chat/activity/activity-state.ts`: `switched` 사건과 `PROVIDER_SWITCHED` 사건의 글이 비었으면 줄을 만들지 않는다. 「여기부터 로 실행해요」 같은 깨진 문장이 나오지 않게 한다
- 값이 `null` 로 올 수 있게 된 칸의 TS 타입을 `| null` 로 맞춘다(`web/src/components/usage/execution-list.tsx` 의 `UsageExecution`, 실행 트리 타입). 지금 `isAdmin` 이 거짓일 때 그 칸을 읽는 자리가 없는지 확인한다

### 5. 이 phase 를 검증하는 테스트

- `backend/src/test/java/com/bifos/assistant/usage/UsageControllerTest.java`: `MEMBER` 로 `executions`, `monthly-cost`, `skills` 를 받아 위 값이 `null` 이고 `errorCode`, `latencyMs`, `totalExecutions` 는 있음을 단언한다. `MEMBER` 의 `breakdown` 은 403 `FORBIDDEN` 이다. 같은 자료를 `ADMIN` 으로 받으면 값이 그대로다
- `backend/src/test/java/com/bifos/assistant/usage/ExecutionTreeServiceTest.java`: `MEMBER` 의 노드에 모델, 토큰, 금액, 네 시각이 `null` 이고 `PROVIDER_SWITCHED` 사건이 없다. `RUN_FAILED` 의 `detail` 은 남는다. `ADMIN` 은 그대로다
- `backend/src/test/java/com/bifos/assistant/chat/ToolDetailStreamTest.java`: `MEMBER` 의 `subagent` 사건에 `model` 과 토큰이 `null` 이고 `ADMIN` 은 그대로다
- `backend/src/test/java/com/bifos/assistant/chat/ModelTierServiceTest.java`: `MEMBER` 가 받은 단계의 `model` 이 `null`, `ADMIN` 은 그대로다
- `backend/src/test/java/com/bifos/assistant/chat/ChatServiceTest.java`: `PROVIDER_SWITCHED` 사건이 있는 실행의 메시지로 `switchedLabels` 를 부르면 `MEMBER` 는 빈 `Map`, `ADMIN` 은 그 메시지의 라벨을 받는다
- `test/unit/activity-state.test.ts`: 글이 빈 `switched` 사건이 줄을 만들지 않는다
- `test/e2e/scenarios/usage-cost.ts`: `monthly-cost` 를 단언하는 자리에 `totalExecutions` 가 `pricedExecutions + unpricedExecutions` 와 같다는 단언을 더한다. e2e 의 내부 값 단언은 모두 `ADMIN` 토큰(`tokens.dad`)이라 다른 자리는 고치지 않는다

## 검증

```bash
# cwd: 저장소 root
(cd backend && ./gradlew test)
node test/e2e/run.ts
node --test 'test/unit/**/*.test.ts'
(cd web && pnpm typecheck && pnpm lint)
(cd web && pnpm test:browser usage.spec.ts execution-tree.spec.ts tool-detail-redaction.spec.ts)
scripts/check-public-safe.sh
```

모두 종료 코드 0 이다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/usage/application/InternalValuePolicy.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/usage/presentation/UsageController.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/usage/presentation/UsageDtos.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/usage/application/ExecutionTreeService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/usage/application/ExecutionNode.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/usage/application/ExecutionEventView.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/presentation/ChatController.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/presentation/ConversationEventController.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/application/ChatEvent.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/application/ModelTierService.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/usage/UsageControllerTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/usage/ExecutionTreeServiceTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/ToolDetailStreamTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/ModelTierServiceTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/ChatServiceTest.java` | 수정 |
| `web/src/components/usage/monthly-summary.tsx` | 수정 |
| `web/src/components/usage/skill-usage-list.tsx` | 수정 |
| `web/src/lib/skill.ts` | 수정 |
| `web/src/components/usage/execution-list.tsx` | 수정 |
| `web/src/components/chat/activity/activity-state.ts` | 수정 |
| `test/unit/activity-state.test.ts` | 수정 |
| `test/e2e/scenarios/usage-cost.ts` | 수정 |
