# Phase 03. 모델 숨김을 요청에 모델을 싣지 않는 실행까지 강제한다

**Execution profile**: deep

## 목표

`model_hidden` 은 실제로 도는 모델까지 막는 실행 정책이다.
지금은 에이전트 기본 모델이 빈 에이전트가 요청에 모델을 싣지 않아, Hermes 가 profile 의 기본 모델로 돌릴 때 그 모델이 숨긴 것이어도 잡지 못한다.
그룹에 숨김이 있으면 실행 직전에 profile 의 기본 모델을 숨김과 견준다.

**범위 외**: 숨김 저장과 에이전트 기본 모델 저장의 검증은 바꾸지 않는다(저장 시점에 거절하지 않는다). 마이그레이션이 없다.
Hermes core 와 plugin 을 고치지 않는다. 코드와 테스트에 실제 모델 이름을 넣지 않는다. 테스트는 기존 테스트가 쓰는 `example-` 로 시작하는 이름을 쓴다.

## 컨텍스트

- `backend/src/main/java/com/bifos/assistant/chat/application/ModelTierService.java`
  - `resolve(user, conversation, agent)` 가 `visibility.requireVisible(user.groupId(), resolved.choice())` 로 판정한다.
    `ModelVisibilityService.requireVisible` 은 `choice.usesDefaultModel()` 이면 통과시킨다. 여기가 빈틈이다.
  - `resolveTier` 의 빈 mapping 갈래도 `visibility.requireVisible(user.groupId(), fallback)` 을 부른다. 같은 빈틈이다.
  - `resolveTier` 의 mapping 이 있는 갈래는 이미 실행 직전에 `modelOptions.optionsForAgent(...)` 로 들고 있는 목록을 읽는다. 같은 길을 쓴다.
- `backend/src/main/java/com/bifos/assistant/chat/application/ModelOptionsService.java`
  - private `catalogFor(Agent agent)` 가 profile 마다 `ttl`(기본 10분) 동안 목록을 들고 있고, 다시 읽다 실패하면 옛 목록을 돌려준다.
    한 번도 읽지 못했으면 `HERMES_UNAVAILABLE` 을 던진다.
  - `HermesModelCatalog.defaultProvider()`, `defaultModel()` 이 profile 의 기본값이다. Hermes 가 주지 않으면 null 이다.
- `backend/src/main/java/com/bifos/assistant/chat/application/HiddenModels.java` 의 `hides(provider, model)` 은 provider 가 같고
  모델이 같거나 provider 전체를 숨긴 항목이 있으면 참이다.
- 호출하는 곳: `ChatService`(대화 turn. 실패하면 `FAILED` 실행 줄과 그 오류 코드를 남기고 다시 던진다), `orchestration/application/AgentRunner`(흐름과 위임. 같은 방식),
  `ChatService` 의 단계 선택 저장(`modelTiers.resolveTier`).
- `backend/src/main/java/com/bifos/assistant/agent/application/StarterSuggestionService.java` 의 `generate` 는 phase 02 뒤로
  `ModelChoice.stored(agent 기본값)` 을 만들어 `startDetached` 와 명령에 쓴다. 숨김 판정은 지나지 않는다.
- 관리 화면: `web/src/components/agent/agent-model-section.tsx` 가 `settings`(`agentDefault`, `catalog`, `catalogMissing`, `hidden`)를 들고 있다.
  타입은 `web/src/lib/model-settings.ts` 에 있다. `catalog.defaultProvider`, `catalog.defaultModel` 이 profile 의 기본값이다.
- 사용자 문구는 `web/src/components/error-message.ts` 에 이미 있다. `MODEL_HIDDEN` 은 「이 모델은 지금 쓸 수 없어요. 입력창의 설정에서 다른 모델을 골라 주세요.」,
  `HERMES_UNAVAILABLE` 은 「연결할 수 없어요. 잠시 뒤 다시 시도해 주세요.」 다. 새 오류 코드와 새 사용자 문구를 만들지 않는다.

**근거 문서**: `docs/adr/ADR-054-에이전트-기본-모델과-모델-숨김은-control-plane-db-가-갖는다.md` 의 「검증 시점」 과 「결과」,
`docs/model-tiers.md` 의 「요청에 모델을 싣지 않는 실행」, `docs/flow.md` 의 「모델을 고를 때」 갈리는 지점

## 의도 메모

- **저장 시점 판정만으로는 닫히지 않는다.** 숨김을 저장한 뒤 만든 에이전트, 화면에서 기본 모델을 넣을 수 없는 커넥터 연결용 에이전트,
  홈서버에서 profile 값을 바꾼 경우가 남는다. 그래서 실행 직전에 판정한다.
- **실행마다 Hermes 를 부르지 않는다.** 들고 있는 목록만 읽는다. 숨김이 없는 그룹과 요청에 모델을 싣는 실행은 목록도 읽지 않는다.
- 숨김 저장 때 에이전트 기본 모델을 요구하는 안은 기각했다. 커넥터 연결용 에이전트와 새 에이전트가 관리자가 값을 넣을 때까지 실행되지 않는다.
- 판정할 수 없을 때(목록을 한 번도 읽지 못함)는 통과시키지 않고 `HERMES_UNAVAILABLE` 로 거절한다. 다른 모델로 바꾸지 않는다.
- Hermes 가 기본 모델을 주지 않으면 판정할 값이 없어 통과시킨다. 문서에 적힌 한계다.
- Memory 제안 실행에는 판정을 더하지 않는다. 방금 판정을 지난 원래 실행의 값을 쓴다.

## 작업 항목

### 1. `HiddenModels`

- `public boolean isEmpty()`: 항목이 없으면 참이다.
- `public boolean hidesProfileDefault(String provider, String model)`:
  - `model` 이 null 이면 거짓이다.
  - `provider` 가 null 이 아니면 `hides(provider, model)` 이다.
  - `provider` 가 null 이면 모델 이름이 같은 항목이 있을 때 참이다. provider 전체를 숨긴 항목(`model` 이 null)은 견주지 않는다.

### 2. `ModelOptionsService.profileDefaultOf`

```java
/** 그 profile 의 기본 provider 와 모델을 들고 있는 목록에서 읽는다. Hermes 가 주지 않은 값은 null 이다. */
public ModelChoice profileDefaultOf(Agent agent)
```

- `catalogFor(agent)` 를 읽어 `ModelChoice.stored(catalog.defaultProvider(), catalog.defaultModel(), null)` 을 돌려준다.
- 한 번도 읽지 못했으면 `catalogFor` 의 `HERMES_UNAVAILABLE` 이 그대로 나간다. 삼키지 않는다.

### 3. `ModelTierService`

- private 메서드를 더한다.

  ```java
  /** 이 실행이 돌 모델이 그룹이 숨긴 것이면 거절한다. 모델을 싣지 않는 실행은 profile 의 기본 모델로 본다(ADR-054). */
  private void requireRunnable(Long groupId, Agent agent, ModelChoice choice)
  ```

  1. `HiddenModels hidden = visibility.hiddenFor(groupId)`. 비었으면 돌아간다.
  2. `choice` 가 모델을 실었으면 `hidden.hides(choice.provider(), choice.model())` 일 때 `MODEL_HIDDEN` 이다.
  3. 싣지 않았으면 `ModelChoice profile = modelOptions.profileDefaultOf(agent)` 를 읽고
     `hidden.hidesProfileDefault(profile.provider(), profile.model())` 일 때 `MODEL_HIDDEN` 이다.
  - 예외 메시지는 영문으로 두고 모델 이름과 provider 이름을 넣지 않는다. 기존 `"the selected model is hidden for this group"` 과 같은 수준으로 쓴다.
- `resolve` 의 `visibility.requireVisible(user.groupId(), resolved.choice())` 와 `resolveTier` 빈 mapping 갈래의
  `visibility.requireVisible(user.groupId(), fallback)` 을 `requireRunnable(user.groupId(), agent, ...)` 로 바꾼다.
  `resolveTier` 의 mapping 이 있는 갈래와 저장 검증(`saveGroup`, `requireVisible(CurrentUser, ModelChoice)`)은 그대로 둔다.
- 대화 없이 도는 실행이 쓸 메서드를 더한다.

  ```java
  /** 대화 없이 도는 실행이 보낼 에이전트 기본값이다. 숨김 판정을 지난다. */
  @Transactional(readOnly = true)
  public ModelChoice resolveDetached(CurrentUser user, Agent agent)
  ```

  `agentDefault(agent)` 를 `requireRunnable` 로 판정한 뒤 돌려준다.
- 클래스 Javadoc 에 숨김 판정이 profile 의 기본 모델까지 본다는 문장을 더한다.

### 4. `StarterSuggestionService`

- 생성자 둘에 `ModelTierService` 를 더한다(`com.bifos.assistant.chat.application`). `agent` 에서 `chat` 으로 가는 의존은 이미 있다.
- `generate` 의 순서를 지킨다. **실행 줄을 먼저 만들고 판정한다.** 판정이 실패해도 `FAILED` 실행 줄이 남아야 한다.
  1. `ModelChoice choice = ModelChoice.stored(agent 기본값)` 으로 `executions.startDetached(user, agent, choice)`.
  2. `modelTiers.resolveDetached(user, agent)`. `ApiException` 이면 기존 `catch` 가 그 코드(`MODEL_HIDDEN`, `HERMES_UNAVAILABLE`)로 실행 줄을 실패로 남기고 재시도 간격을 건다.
  3. 명령을 만들어 제출한다.
- 구조 규칙 검사(`./gradlew archTest`)가 새 간선으로 실패하면 기준 파일을 고치지 말고 `PHASE_BLOCKED` 로 알린다.

### 5. `web/src/components/agent/agent-model-section.tsx` 의 경고

- `settings.catalogMissing` 이 거짓이고 `settings.agentDefault.model` 이 null 이고 `settings.catalog.defaultModel` 이 null 이 아니며,
  `settings.hidden.entries` 가 그 기본 모델을 숨기면 경고를 보인다. 판정은 backend 의 `hidesProfileDefault` 와 같다.
  - `catalog.defaultProvider` 가 있으면 provider 가 같고 (`model` 이 null 이거나 모델이 같은) 항목이 있을 때다.
  - 없으면 모델 이름이 같은 항목이 있을 때다.
- `Notice variant="warning"`, `data-testid="agent-model-profile-default-hidden"`, 문구:
  「이 에이전트는 기본 모델을 정하지 않았는데 profile 의 기본 모델이 숨겨져 있어요. 기본 모델을 정하거나 숨김을 풀기 전에는 모델을 고르지 않은 대화가 실패해요.」
- `AgentModelDefaultForm` 위에 둔다. 두 폼의 `onSaved` 가 `settings` 를 갱신하므로 저장 직후에 경고가 나타나고 사라진다.
- 판정 함수는 `web/src/lib/model-settings.ts` 에 `hidesProfileDefault(settings: AgentModelSettings): boolean` 으로 둔다.

### 6. 이 phase 를 검증하는 테스트

새 `@Test` 마다 `@DisplayName` 을 붙인다. 기존 테스트 파일의 준비 방식을 따른다.

- `backend/src/test/java/com/bifos/assistant/chat/ModelTierServiceTest.java`:
  - 숨김이 없으면 모델을 싣지 않는 해석이 목록을 읽지 않고 통과한다.
  - 숨김이 있고 에이전트 기본 모델이 없고 profile 의 기본 모델이 숨긴 모델이면 `resolve` 가 `MODEL_HIDDEN` 이다. provider 전체를 숨긴 경우도 같다.
  - 숨김이 있어도 profile 의 기본 모델이 숨기지 않은 모델이면 통과하고 `choice` 는 세 값이 비어 있다.
  - 숨김이 있고 목록을 한 번도 읽지 못했으면 `HERMES_UNAVAILABLE` 이다.
  - 숨김이 있고 에이전트 기본 모델이 있으면 목록을 읽지 않는다. 그 모델이 숨긴 것이면 `MODEL_HIDDEN` 이다.
  - Hermes 가 기본 모델을 주지 않으면 통과한다. 기본 provider 만 주지 않으면 모델 이름으로 판정한다.
  - 빈 mapping 인 단계의 `resolveTier` 도 같은 판정을 지난다.
  - `resolveDetached` 가 같은 판정을 지나고 에이전트 기본값을 돌려준다.
  - 이 파일의 기존 테스트는 `mock(ModelVisibilityService.class)` 를 넘긴다. 새 판정이 `visibility.hiddenFor(groupId)` 를 먼저 부르므로
    기존 준비 코드와 `tierFixtures()` 에 `when(visibility.hiddenFor(any())).thenReturn(HiddenModels.none())` 을 더한다. 빠뜨리면 null 로 NPE 가 난다.
  - 기존 `hiddenModelIsRejectedInsteadOfReplaced` 는 `visibility.requireVisible(10L, choice)` 에 `doThrow` 를 걸어 둔다. `resolve` 가 그 메서드를 더 부르지 않으므로
    `hiddenFor(10L)` 이 그 모델을 담은 `HiddenModels` 를 돌려주게 바꾼다. `MODEL_HIDDEN` 단언은 그대로 둔다.
- `backend/src/test/java/com/bifos/assistant/chat/ModelOptionsServiceTest.java`: `profileDefaultOf` 가 들고 있는 목록을 다시 쓰고(Hermes 호출 한 번), 다시 읽기가 실패하면 옛 값을 돌려준다.
- `backend/src/test/java/com/bifos/assistant/agent/StarterSuggestionServiceTest.java`:
  `resolveDetached` 가 `MODEL_HIDDEN` 을 던지면 Hermes 에 제출하지 않고 실행 줄이 `FAILED` 와 `MODEL_HIDDEN` 으로 남는다.
  이 경우만 Mockito 가짜 `ModelTierService` 를 생성자에 넘긴다. 이 테스트의 사용자는 다른 테스트와 같은 그룹을 써서, `model_hidden` 줄을 실제로 넣으면
  같은 DB 를 쓰는 다른 테스트의 기본값 실행이 막힌다. 나머지 기존 테스트의 생성자에는 주입받은 실제 `ModelTierService` bean 을 넘긴다. 그 그룹에는 숨김이 없어 목록을 읽지 않는다.
- `backend/src/test/java/com/bifos/assistant/chat/ModelSelectionTest.java`(`ChatService` 를 직접 부르는 통합 테스트다):
  `HermesModelClient` 를 `@MockitoBean` 으로 두고 `readCatalog` 가 profile 의 기본 provider 와 모델을 담은 `HermesModelCatalog` 를 돌려주게 한다.
  에이전트 기본 모델이 없는 에이전트로 보낼 때 그 기본 모델을 숨기면 `ApiException` 의 코드가 `MODEL_HIDDEN` 이고 `FAILED` 실행 줄이 남으며 Hermes 에 제출되지 않는다.
  숨김을 비우면 같은 대화가 다시 돈다. 넣은 `model_hidden` 줄은 그 테스트 안의 `finally` 나 `@AfterEach` 에서 그 그룹 것을 지운다.
  HTTP 409 는 아래 e2e 단계가 확인한다. `@MockitoBean` 이 이 클래스의 다른 테스트를 깨면 기존 테스트가 기대하던 응답을 같은 가짜에 준비한다.
- `test/e2e/scenarios/model-selection.ts`: 기존 「숨긴 모델은 목록에서 빠지고…」 단계의 `finally` 뒤(에이전트 기본 모델을 비운 뒤)에 단계를 더한다.
  `PROFILE_DEFAULT` 의 모델을 숨기고, **`DEFAULT` 로 되돌려 둔 `tierConversationId` 로** 보낸다. 새 대화로 보내면 앞에서 저장한 그룹 단계 정의로 해석돼 모델이 실리고 이 판정을 지나지 않는다.
  응답이 409 `MODEL_HIDDEN` 이고 맨 위 실행이 `FAILED`, `MODEL_HIDDEN` 이다. 가짜 Hermes 가 받은 실행 수가 보내기 앞뒤로 같은지로 제출되지 않았음을 확인한다.
  가짜 Hermes 에 그 수를 읽는 길이 없으면 `test/e2e/fake-hermes.ts` 에 읽기 전용 접근자 하나를 더한다.
  `finally` 에서 숨김을 비우고, 그 뒤의 기존 단계가 그대로 통과하는지 본다.
- `test/browser/agent-model.spec.ts`: 이 파일에서 `model-settings` 응답을 `page.route` 로 바꿔 끼우는 기존 테스트와 같은 방식을 쓴다.
  **실제 숨김 저장으로 profile 의 기본 모델을 숨기지 않는다.** 숨기면 그 그룹의 기본값 실행이 막혀 다른 브라우저 검사가 깨진다.
  에이전트 기본 모델이 없고 profile 의 기본 모델을 숨긴 응답에서 `agent-model-profile-default-hidden` 이 보이고, 숨김이 빈 응답에서는 보이지 않는다.
- 기존 테스트 가운데 「에이전트 기본 모델 없이 숨김을 건 채 모델을 싣지 않고 실행해 성공한다」 를 전제한 것이 깨지면,
  그 테스트가 지키던 뜻을 유지하도록 숨기는 모델을 profile 의 기본 모델이 아닌 것으로 바꾼다. 단언을 지우지 않는다.

## 검증

```bash
# cwd: 저장소 root
cd backend && ./gradlew test --tests '*ModelTierServiceTest' --tests '*ModelOptionsServiceTest' --tests '*ModelSelectionTest' --tests '*ModelVisibilityTest' --tests '*StarterSuggestionServiceTest' --tests '*ArchitectureRulesTest'
cd backend && ./gradlew test
cd backend && ./gradlew qualityCheck
cd web && pnpm typecheck && pnpm lint && pnpm format:check
node test/e2e/run.ts
cd web && pnpm test:browser agent-model
```

- 모두 종료 코드 0 이다. `node test/e2e/run.ts` 는 `./gradlew test` 뒤에 돌린다.
- `git grep -nE "gpt-|claude-|gemini-" -- backend/src/main test/e2e/scenarios/model-selection.ts` 에 이 phase 가 더한 줄이 없다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/chat/application/HiddenModels.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/application/ModelOptionsService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/application/ModelTierService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/agent/application/StarterSuggestionService.java` | 수정 |
| `web/src/components/agent/agent-model-section.tsx` | 수정 |
| `web/src/lib/model-settings.ts` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/ModelTierServiceTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/ModelOptionsServiceTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/ModelSelectionTest.java` | 수정 |
| `test/e2e/fake-hermes.ts` | 수정 |
| `backend/src/test/java/com/bifos/assistant/agent/StarterSuggestionServiceTest.java` | 수정 |
| `test/e2e/scenarios/model-selection.ts` | 수정 |
| `test/browser/agent-model.spec.ts` | 수정 |
