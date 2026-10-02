# Phase 01. backend 의 reasoning capability 와 `none` 검증

**Execution profile**: deep

## 목표

모델마다 reasoning 지원과 끄기 지원을 `SUPPORTED`, `UNSUPPORTED`, `UNKNOWN` 으로 나눠 읽고,
`none` 을 끄기 지원이 확인된 모델에서만 저장하게 한다.
Hermes 가 주지 않은 값을 우리가 참으로 채우던 것을 없앤다.

**범위 외**: web 화면(phase 02). `reasoningCapable` 의 제거(다음 배포). 그룹 단계 정의는 `none` 을 받지 않으므로 바꾸지 않는다.

## 컨텍스트

- 결정과 근거는 `docs/adr/ADR-059-reasoning-effort-의-지원은-확인한-것만-보이고-모르면-미확인으로-둔다.md` 에 있다. 계약은 `docs/backend/conversation.md` 의 `model-options` 표, `docs/hermes/runs-api.md` 의 「reasoning effort 는 `model_options` 로 그 실행에만 준다」 와 `/api/model/options` 의 칸 표, `docs/model-tiers.md` 의 「에이전트 기본 모델」 에 이미 적혀 있다. 코드가 그 문서와 달라지면 같은 커밋에서 문서를 고친다
- 지금 코드(읽은 정의)
  - `backend/src/main/java/com/bifos/assistant/hermes/dto/HermesModelCatalog.java` 의 `Provider(slug, name, models, Map<String, Boolean> reasoning)`. 칸이 없는 모델은 맵에 없다
  - `backend/src/main/java/com/bifos/assistant/hermes/HermesModelClient.java` 의 `providerOf` 가 `capabilities.<모델>.reasoning` 이 boolean 일 때만 맵에 넣는다
  - `backend/src/main/java/com/bifos/assistant/chat/application/ModelOptionsService.java` 의 `withEveryModelReasoning` 이 없는 모델을 `true` 로 채운다. `withoutHidden` 이 `provider.reasoning()` 을 그대로 넘긴다
  - `backend/src/main/java/com/bifos/assistant/chat/application/ModelOptions.java` 는 `offers(provider, model)` 를 가진다
  - `backend/src/main/java/com/bifos/assistant/chat/domain/ModelChoice.java` 의 `REASONING_EFFORTS` 는 `low` 부터 `max` 이고 `of` 가 그 밖의 값을 `VALIDATION_FAILED` 로 거절한다. `ModelTierProperties` 와 `ModelTierService.isValidMapping` 도 이 목록을 쓴다
  - `backend/src/main/java/com/bifos/assistant/chat/presentation/ChatDtos.java` 의 `ProviderView(provider, name, models, Map<String, Boolean> reasoningCapable)`
  - `ChatService.chooseModel(user, conversationId, choice)` 는 에이전트를 모른다. `chooseModelTier` 가 `agents.requireStartable(user, agents.requireById(conversation.agentId()).code())` 로 에이전트를 얻는 것이 선례다
  - `AgentModelDefaultService.save(user, agentCode, choice)` 는 `agents.requireForAdmin` 으로 에이전트를 얻고 `modelOptions.unfilteredForAgent(agent)` 가 숨김을 적용하지 않은 목록을 준다
- DB 칸 `conversation.reasoning_effort`, `agent.default_reasoning_effort`, `agent_execution.reasoning_effort` 는 `VARCHAR(16)` 이라 `none` 이 들어간다. 마이그레이션은 없다
- 상류 사실: `capabilities.<모델>.reasoning` 은 상류가 모르면 `true` 로 채운다. `can_disable_reasoning` 은 aggregator provider 모델에만 있다. `minimal` 지원 신호는 없다

**근거 문서**: `docs/adr/ADR-059-reasoning-effort-의-지원은-확인한-것만-보이고-모르면-미확인으로-둔다.md`, `docs/backend/conversation.md`, `docs/hermes/runs-api.md`, `docs/model-tiers.md`

## 의도 메모

- `UNKNOWN` 을 만들어 두는 것이 이 phase 의 핵심이다. 칸이 없을 때 `true` 로 채우는 코드를 되살리지 않는다
- `minimal` 은 추가하지 않는다. 지원을 확인할 신호가 없다
- 그룹 단계 정의(`ModelTierService`, `ModelTierProperties`)는 `none` 을 받지 않는다. 모델을 가리키는 profile 이 하나가 아니라 검증할 목록이 없다. `ModelChoice.REASONING_EFFORTS` 는 `low` 부터 `max` 그대로 두고, 요청 검증용 허용 값을 따로 둔다
- 요청한 effort 와 적용한 effort 는 다르다. 실행 줄의 `reasoning_effort` 는 보낸 값이고 출처 구분(`ReasoningEffortSource`)은 바꾸지 않는다. `none` 도 보낸 값으로 적는다
- 응답의 `reasoningCapable` 은 지우지 않고 `support != UNSUPPORTED` 로 계산해 남긴다. backend 와 web 이 같은 순간에 배포된다고 확인하지 못해, 옛 web 이 깨지지 않게 다음 배포까지 둔다

## 작업 항목

### 1. `hermes/dto/ReasoningCapability.java` 신규

`package com.bifos.assistant.hermes.dto;`

```java
public record ReasoningCapability(Support support, Support disable) {
    public enum Support { SUPPORTED, UNSUPPORTED, UNKNOWN }
    public static final ReasoningCapability UNKNOWN_ALL = new ReasoningCapability(Support.UNKNOWN, Support.UNKNOWN);
}
```

JSON 으로는 `{"support": "SUPPORTED", "disable": "UNKNOWN"}` 가 나간다. Javadoc 은 한국어로 쓴다.

### 2. `HermesModelCatalog.Provider` 와 `HermesModelClient.providerOf`

- `Provider.reasoning` 의 타입을 `Map<String, ReasoningCapability>` 로 바꾼다. Javadoc 은 「모든 모델이 표에 있다. Hermes 가 밝히지 않은 칸은 `UNKNOWN` 이다」 로 고친다
- `providerOf` 는 `models` 의 모든 모델에 항목을 만든다. `capabilities.<모델>.reasoning` 이 boolean 이면 참은 `SUPPORTED`, 거짓은 `UNSUPPORTED`, 그 밖(칸 없음, boolean 이 아님)은 `UNKNOWN` 이다. `can_disable_reasoning` 도 같은 규칙으로 `disable` 을 채운다. `capabilities` 가 없거나 객체가 아니면 모든 모델이 `UNKNOWN_ALL` 이다

### 3. `ModelOptionsService`

- `withEveryModelReasoning` 과 그 호출을 없앤다. 시험과 mock 이 `Provider(..., Map.of())` 로 만든 목록을 넘긴다(`ModelSelectionTest`, `ModelTierServiceTest`, `ModelVisibilityTest`). 항목이 없는 모델은 `reasoning().getOrDefault(model, ReasoningCapability.UNKNOWN_ALL)` 로 읽어 `UNKNOWN` 으로 본다. `allowsEffort` 와 `ProviderView` 계산이 모두 이 방식으로 읽는다 `optionsOf` 는 `withoutHidden` 의 결과를 그대로 `providers` 에 담는다
- `withoutHidden` 은 남은 모델의 항목만 `reasoning` 에 담는다(숨긴 모델의 항목을 뺀다)
- 클래스 Javadoc 과 `ModelOptions` 의 `providers` 설명에서 「Hermes 가 밝히지 않은 모델은 참이다」 를 지운다

### 4. `ModelOptions.allowsEffort`

```java
/** 그 모델에서 그 effort 를 고를 수 있는가. none 만 지원을 확인해야 한다. */
public boolean allowsEffort(String provider, String model, String effort)
```

- `effort` 가 `"none"` 이 아니면 `true` 다
- `none` 이면 `provider`, `model` 이 null 일 때 `defaultProvider`, `defaultModel` 을 쓴다. 모델이 정해지지 않으면 `false` 다
- `providers` 에서 `slug` 가 같고(provider 가 null 이면 그 모델을 가진 첫 provider) 그 모델의 항목을 찾아, `disable == SUPPORTED` 이고 `support != UNSUPPORTED` 일 때만 `true` 다. 목록에 모델이 없으면 `false` 다

### 5. `ModelChoice`

- `public static final String EFFORT_NONE = "none";` 와 `public static final List<String> ACCEPTED_EFFORTS = List.of("none", "low", "medium", "high", "xhigh", "max");` 를 둔다. `REASONING_EFFORTS` 는 그대로다
- `of` 의 effort 검증을 `ACCEPTED_EFFORTS` 로 바꾼다. `minimal` 은 계속 거절한다. Javadoc 의 `@param reasoningEffort` 를 「`ACCEPTED_EFFORTS` 중 하나. 미지정이면 null 이고 `none`(reasoning 끄기)과 다르다」 로 고친다

### 6. 저장 경로의 `none` 판정

- `ModelTierService` 에 `public void requireEffortAllowed(CurrentUser user, Agent agent, ModelChoice choice)` 를 둔다. `choice.reasoningEffort()` 가 `none` 이 아니면 아무것도 하지 않는다. `none` 이면 `modelOptions.optionsForAgent(user.groupId(), agent).allowsEffort(choice.provider(), choice.model(), "none")` 이 거짓일 때 `ApiException(ErrorCode.VALIDATION_FAILED, "reasoning cannot be turned off for this model")` 을 던다. 목록 조회 실패(`HERMES_UNAVAILABLE`)는 그대로 올린다
- `ChatService.chooseModel` 은 `choice.reasoningEffort()` 가 `none` 일 때만, `modelTiers.requireVisible` 다음에 대화의 에이전트를 얻어(`chooseModelTier` 와 같은 방식) `modelTiers.requireEffortAllowed` 를 부른다. `none` 이 아니면 에이전트를 얻지 않는다. 꺼진 에이전트나 `agentId` 가 없는 대화의 기존 동작(`AGENT_DISABLED`, `AGENT_NOT_FOUND` 로 바뀌지 않는 것)을 지키려는 것이다
- `AgentModelDefaultService.save` 는 `choice.reasoningEffort()` 가 `none` 일 때 `modelOptions.unfilteredForAgent(agent).allowsEffort(choice.provider(), choice.model(), "none")` 이 거짓이면 같은 예외를 던다. 모델이 비어 있으면 profile 의 기본 모델로 판정한다(`unfilteredForAgent` 의 기본 모델이 profile 의 값이다)
- 그룹 단계 정의 저장(`ModelTierService.saveGroup`)과 `ModelTierProperties` 는 바꾸지 않는다. `none` 은 여전히 거절된다. 이 사실을 확인하는 테스트를 둔다(아래)

### 7. `ChatDtos.ProviderView`

`ProviderView(String provider, String name, List<String> models, Map<String, ReasoningCapability> reasoning, Map<String, Boolean> reasoningCapable)` 로 바꾼다. `reasoningCapable` 은 각 모델의 `support != UNSUPPORTED` 로 계산한 호환 칸이다(항목이 없으면 `UNKNOWN_ALL` 로 읽어 참이다). 이미 배포된 옛 web 이 새 backend 응답을 읽어도 깨지지 않게 이번 배포에서는 남기고 Javadoc 에 「옛 web 호환용이고 다음 배포에서 지운다」 를 적는다. 지우는 일은 이 plan 의 범위 밖이다 `ModelOptionsView.from` 을 맞춘다.

### 8. 테스트와 e2e 대역

- `backend/src/test/java/com/bifos/assistant/hermes/HermesModelCatalogTest.java`: 기존 두 시험(`mapsCapabilitiesReasoningToModelNameTable`, `leavesModelsMissingFromCapabilitiesOutOfTable`)을 새 타입에 맞게 바꾼다. 새 시험: `reasoning:true`→`SUPPORTED`, `reasoning:false`→`UNSUPPORTED`, 칸 없음(`{}`)과 `capabilities` 자체가 없는 provider→`UNKNOWN`(누락과 명시적 `false` 가 다른 사례임을 한 시험에서 함께 단언한다), `can_disable_reasoning` 참과 거짓과 없음의 세 가지
- `backend/src/test/java/com/bifos/assistant/chat/ModelOptionsServiceTest.java`: 302 줄 근처의 기존 시험을 새 타입으로 바꾸고, Hermes 가 밝히지 않은 모델이 `UNKNOWN` 으로 남는지(참으로 채우지 않는지), 숨긴 모델의 항목이 빠지는지, `allowsEffort` 가 `none` 에서 `disable` 의 세 값과 `support` 가 `UNSUPPORTED` 인 경우와 모델이 목록에 없는 경우에 각각 맞게 답하는지를 확인한다
- `backend/src/test/java/com/bifos/assistant/chat/ModelSelectionTest.java`(`@MockitoBean HermesModelClient` 로 목록을 대신한다). `ConversationModelChoiceTest` 에는 목록 대역이 없어 쓰지 않는다. `ModelOptionsService` 는 시험에서도 profile 마다 10분 동안 목록을 들고 있으므로 시험마다 목록을 바꾸지 말고 `SUPPORTED`, `UNKNOWN`, `UNSUPPORTED` 모델을 한 목록에 모두 넣는다. 시험: `none` 이 `ModelChoice.of` 를 통과하고 `minimal` 은 `VALIDATION_FAILED` 인 것, `PUT .../model` 이 끄기 지원이 `SUPPORTED` 인 모델에서는 `none` 을 저장하고 `UNKNOWN` 과 `UNSUPPORTED` 인 모델에서는 `VALIDATION_FAILED` 로 거절하며 저장된 값을 바꾸지 않는 것. `ModelOptionsService` 를 직접 만드는 기존 시험은 `ModelOptionsServiceTest` 가 선례다
- `backend/src/test/java/com/bifos/assistant/chat/ModelTierServiceTest.java`: `saveGroup` 의 거절 시험(190 줄 근처) 옆에 그룹 단계 정의에 `none` 을 넣으면 거절되는 시험을 더한다. `ModelTierRequestValidationTest` 는 서비스를 mock 으로 넣어 실제 판정이 돌지 않아 쓰지 않는다
- `backend/src/test/java/com/bifos/assistant/chat/ModelVisibilityTest.java`(`AgentModelDefaultService` 시험이 있는 유일한 파일): 에이전트 기본 모델 저장에서 `none` 이 끄기 지원이 `SUPPORTED` 인 모델이나 profile 기본 모델에서만 저장되고, 아니면 `VALIDATION_FAILED` 인 시험
- `backend/src/test/java/com/bifos/assistant/hermes/HermesRunRequestTest.java`: effort `none` 이 `model_options.reasoning.effort` 로 그대로 실리고, effort 가 null 이면 `model_options` 가 빠지는 것(미지정과 `none` 이 다르게 나가는 것)을 확인하는 시험을 더한다
- `backend/src/test/java/com/bifos/assistant/chat/ModelSelectionTest.java`(보내기 흐름으로 실행 줄의 effort 를 이미 단언하는 205~230 줄 근처): 대화에 `none` 을 저장해 둔 채 보낸 실행이 `reasoning_effort = none`, 출처 `REQUESTED` 로 남고, Hermes 가 완료 때 다른 provider 나 모델을 알려도 요청한 effort 가 바뀌지 않는 것을 확인한다(요청값을 적용값으로 보이지 않는다)
- `test/e2e/fake-hermes.ts` 의 `/api/model/options` 응답(1338 줄 근처 `capabilities`)을 아래로 바꾼다. 모델 이름은 그 파일의 상수를 쓴다. `can_disable_reasoning` 은 실제로는 aggregator provider 에만 오지만 대역은 `none` 을 시험하려고 기본 모델에도 준다. 이 사실을 대역에 주석으로 남긴다

  | 모델 | capabilities |
  | --- | --- |
  | `DEFAULT_RUNTIME.model` | `{ reasoning: true, can_disable_reasoning: true }` |
  | `"example-model-mini"` | `{ reasoning: false }` |
  | `"example-fast"` | `{ reasoning: true }` |
  | `"example-balanced"` | `{}` |
  | `"example-deep"` | `{ reasoning: true, can_disable_reasoning: false }` |

- `test/e2e/scenarios/model-selection.ts`: `ModelOptionsView.providers[]` 타입에 `reasoning: Record<string, { support: string; disable: string }>` 를 더하고, 기존 `reasoningCapable["example-model-mini"] === false` 단언 옆에 위 표의 다섯 모델이 기대한 `support`, `disable` 로 오는 것과 `none` 을 `PUT /chat/conversations/{id}/model` 로 보낼 때 기본 모델은 200, `example-balanced` 와 `example-deep` 과 `example-model-mini` 는 400 인 것을 단언한다. 같은 파일에서 effort 선택지 단언(`low,medium,high,xhigh,max`)은 그대로 둔다

## 검증

```bash
# cwd: 저장소 root
cd backend && ./gradlew test --tests '*HermesModelCatalogTest' --tests '*ModelOptionsServiceTest' --tests '*ModelSelectionTest' --tests '*ModelTierServiceTest' --tests '*ModelVisibilityTest' --tests '*HermesRunRequestTest'
cd backend && ./gradlew test
node test/e2e/run.ts
scripts/quality.sh check
scripts/check-public-safe.sh
```

기대값: 모두 종료 코드 0.  `grep -rn "withEveryModelReasoning" backend/src` 가 비어 있어야 한다.

## 변경 파일

| 파일 | 변경 |
| --- | --- |
| `backend/src/main/java/com/bifos/assistant/hermes/dto/ReasoningCapability.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/hermes/dto/HermesModelCatalog.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/hermes/HermesModelClient.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/application/ModelOptionsService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/application/ModelOptions.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/domain/ModelChoice.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/application/ModelTierService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/application/ChatService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/application/AgentModelDefaultService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/presentation/ChatDtos.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/hermes/HermesModelCatalogTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/ModelOptionsServiceTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/ModelSelectionTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/ModelTierServiceTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/ModelVisibilityTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/hermes/HermesRunRequestTest.java` | 수정 |
| `test/e2e/fake-hermes.ts` | 수정 |
| `test/e2e/scenarios/model-selection.ts` | 수정 |
| `docs/backend/conversation.md` | 수정 |
