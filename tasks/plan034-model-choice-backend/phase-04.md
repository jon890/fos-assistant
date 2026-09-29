# Phase 04. 고를 수 있는 모델 목록을 Hermes 에 물어 돌려준다

**Execution profile**: standard

## 목표

`GET /api/v1/chat/model-options?agentCode=` 가 그 에이전트의 profile 로 고를 수 있는 모델과 기본값을 돌려준다.
목록은 저장하지 않고 Control Plane 메모리에 profile 마다 10분 들고 있는다.

**범위 외**: 화면은 plan036 이다. `HermesModelClient.readModel`, `readOptions` 를 쓰던 에이전트 모델 동기화를 지우는 것은 plan035 다. 이 phase 는 그 두 메서드를 그대로 두고 새 메서드를 더한다.

## 컨텍스트

**근거 문서**: `docs/hermes/runs-api.md` 「`/api/model/options` 는 provider 와 모델 목록을 함께 준다」, `docs/code-architecture.md` 「대화의 모델 선택」, `docs/flow.md` 「모델을 고를 때」 의 「갈리는 지점」

- `backend/src/main/java/com/bifos/assistant/hermes/HermesModelClient.java` 가 `GET {apiBaseUrl}/api/model/options` 를 그 profile 의 key 로 부른다. 지금은 `model`, `provider` 만 읽고, 실패하면 null 을 돌려준다
- 실제 응답은 `{"provider", "model", "providers": [{"slug", "name", "authenticated", "models": [...], "capabilities": {"<모델>": {"reasoning": true}}}]}` 모양이다. 설정하지 않은 provider 는 `authenticated: false`, `models: []` 행으로도 온다
- 요청자가 에이전트를 쓸 수 있는지는 기존 보내기 경로와 같은 확인을 쓴다. 그 확인은 지금 `ChatService` 의 private `requireStartableAgent` 다. `agentCode` 가 비면 `AGENT_NOT_FOUND`, `AgentService.requireReadable(user, code)` 가 못 찾거나 읽을 수 없으면 `AGENT_NOT_FOUND`, 에이전트가 꺼져 있으면 `AGENT_DISABLED` 다
- `HermesModelClient` 의 key 는 `HermesProfileKeyStore.resolve(profileName)` 이 꺼낸다. key 가 없을 때 그 메서드가 던지는 `ApiException` 은 그대로 올린다
- 시계를 주입하는 본보기는 `backend/src/main/java/com/bifos/assistant/usage/infra/ModelsDevPriceCatalog.java` 의 두 생성자다. 다만 그 생성자는 package-private 이라 다른 패키지의 테스트가 부를 수 없다
- 가짜 Hermes `test/e2e/fake-hermes.ts` 의 `MODEL_OPTIONS_PATH` 응답은 지금 `{ model: "example-model", provider: "openai-codex", providers: [] }` 다

## 의도 메모

- 들고 있는 시간이 지나 다시 읽다가 Hermes 가 실패하면 들고 있던 옛 목록을 돌려주고 버리지 않는다. 그 profile 의 목록을 한 번도 읽지 못한 채 실패하면 `HERMES_UNAVAILABLE`(502)을 던진다(`docs/code-architecture.md` 「대화의 모델 선택」). 응답 코드가 429 여도 `HERMES_UNAVAILABLE` 이다. `HermesCallFailure` 의 `HERMES_BUSY` 구분은 실행의 동시 한도용이라 여기에 쓰지 않는다
- 배포 뒤 실제 Hermes 의 `/api/model/options` 응답으로 한 번 불러 인증된 provider 가 보이는지 확인한다. 확인 방법은 `fos-home-infra` 가 갖는다
- 들고 있는 시간은 설정으로 뺀다. `assistant.chat.model-options-ttl` 기본 `10m`
- 동시에 여러 요청이 와도 Hermes 를 한 번만 부르게 하려고 애쓰지 않는다. 가족 몇 명이 쓰는 규모다
- 모델 이름은 Hermes 가 준 차례 그대로 둔다. 기본 provider 를 맨 앞에 둔다

## 작업 항목

### 1. `backend/src/main/java/com/bifos/assistant/hermes/dto/HermesModelCatalog.java`

record `HermesModelCatalog(String defaultProvider, String defaultModel, List<Provider> providers)` 와 그 안의 `Provider(String slug, String name, List<String> models, Map<String, Boolean> reasoning)`.
`HermesModelClient` 에 `HermesModelCatalog readCatalog(String apiBaseUrl, String profileName)` 를 더한다. `authenticated` 가 거짓인 행과 `models` 가 빈 행은 뺀다. `capabilities.<모델>.reasoning` 이 참거짓으로 온 모델만 `reasoning` 표에 넣는다. Hermes 호출이 실패하면(`RestClientException`) `ApiException(ErrorCode.HERMES_UNAVAILABLE)` 을 던진다. null 을 돌려주지 않는다. key 가 없을 때의 예외는 그대로 올린다.

### 2. `backend/src/main/java/com/bifos/assistant/chat/application/ModelOptionsService.java` 와 결과 타입

- `ModelOptions optionsFor(CurrentUser user, String agentCode)`. 에이전트를 쓸 수 있는지 확인하고, profile 이름을 열쇠로 들고 있는 목록이 없거나 오래됐으면 `readCatalog` 를 부른다
- 에이전트 확인은 `ChatService.requireStartableAgent` 의 본문을 `backend/src/main/java/com/bifos/assistant/agent/application/AgentService.java` 의 `public Agent requireStartable(CurrentUser user, String code)` 로 옮기고 두 서비스가 함께 부른다. 오류 코드는 위 「컨텍스트」 의 셋 그대로다
- 시계와 들고 있는 시간은 생성자로 받는다. Spring 이 쓰는 생성자는 `@Autowired` 를 붙이고 `@Value("${assistant.chat.model-options-ttl:10m}") Duration` 과 `Clock.systemUTC()` 를 넘긴다. `Duration` 과 `Clock` 을 받는 생성자는 `public` 으로 두어 `com.bifos.assistant.chat` 패키지의 테스트가 부른다. `Clock` bean 은 만들지 않는다
- 들고 있는 것은 `ConcurrentHashMap<String, 읽은 시각과 목록>` 이다
- 결과 타입 `chat/application/ModelOptions.java` 는 따로 파일로 둔다(`backend/AGENTS.md`)
- effort 선택지는 phase 02 가 둔 `ModelChoice.REASONING_EFFORTS` 를 그대로 쓴다. 목록을 새로 적지 않는다

### 3. 경로 `GET /api/v1/chat/model-options`

`ChatDtos` 에 `ModelOptionsView(defaultProvider, defaultModel, providers, reasoningEfforts)` 와 `ProviderView(provider, name, models, reasoningCapable)` 를 둔다. `reasoningCapable` 은 모델 이름을 열쇠로 한 참거짓 표이고 값이 없는 모델은 참이다.
`ChatController` 에 `@GetMapping("/model-options")` 를 더하고 `@RequestParam(required = false) String agentCode` 를 받는다. 빠진 `agentCode` 는 서비스의 에이전트 확인이 `AGENT_NOT_FOUND` 로 거절한다. `required = true` 로 두면 `MissingServletRequestParameterException` 을 받는 handler 가 없어 500 이 된다.

### 4. 가짜 Hermes `test/e2e/fake-hermes.ts`

`MODEL_OPTIONS_PATH` 응답을 실제 모양으로 바꾼다. 기본 provider 와 모델은 phase 01 이 둔 기본값 상수를 그대로 쓴다. `providers` 에 `openai-codex` 행(`authenticated: true`, 모델 둘, 하나는 `capabilities.<모델>.reasoning: false`)과 설정하지 않은 행 하나(`authenticated: false`, `models: []`)를 둔다. 부른 횟수를 셀 수 있게 `modelOptionsCalls()` 를 더한다.

### 5. 이 phase 를 검증하는 테스트

- `backend/src/test/java/com/bifos/assistant/hermes/HermesModelCatalogTest.java`: 같은 폴더의 대역 서버 방식으로 실제 모양을 읽는다. `authenticated: false` 행이 빠지고, `capabilities` 의 `reasoning` 이 표로 옮겨진다. 5xx 와 429 가 모두 `ApiException(HERMES_UNAVAILABLE)` 이다
- `backend/src/test/java/com/bifos/assistant/chat/ModelOptionsServiceTest.java`: `HermesModelClient` 와 `AgentService` 를 mock 으로 두고 서비스를 직접 만든다. 두 번 부르면 Hermes 를 한 번 부른다. 10분이 지나면 다시 부른다(시계를 주입한다). 10분이 지나 다시 읽다 Hermes 가 실패하면 들고 있던 목록을 돌려준다. 처음 읽다 실패하면 `HERMES_UNAVAILABLE` 이다. profile 이 다르면 따로 부른다. `agentCode` 가 비면 `AGENT_NOT_FOUND`, 꺼진 에이전트는 `AGENT_DISABLED` 로 거절하고 Hermes 를 부르지 않는다. 기본 provider 가 목록 맨 앞에 온다. `reasoningEfforts` 가 `ModelChoice.REASONING_EFFORTS` 와 같다
- e2e `test/e2e/scenarios/model-selection.ts` 에 단계 하나: `GET /chat/model-options?agentCode=dad` 가 기본값과 인증된 provider 하나를 돌려주고, 다시 불러도 가짜 Hermes 를 한 번만 부른다

## 검증

`AGENTS.md` 「확인」 절의 여섯 명령을 적힌 순서대로 모두 돌린다. 새 워크트리라 `web` 에서 `pnpm install --frozen-lockfile` 이 먼저 필요하다. 첫 줄은 이 phase 의 테스트만 먼저 돌리는 것이다.

```bash
# cwd: 저장소 root
cd backend && ./gradlew test --tests '*HermesModelCatalogTest' --tests '*ModelOptionsServiceTest'
cd backend && ./gradlew test
cd web && pnpm typecheck
cd web && AUTH_SECRET=build-time-placeholder ASSISTANT_JWT_SECRET=build-time-placeholder CONTROL_PLANE_BASE_URL=http://build-time-placeholder AUTH_GOOGLE_ID=build-time-placeholder AUTH_GOOGLE_SECRET=build-time-placeholder pnpm build
cd web && pnpm test:browser
node test/e2e/run.ts
node --test 'test/unit/**/*.test.ts'
scripts/check-public-safe.sh
```

- 모두 통과한다
- 모두 통과하면 `tasks/plan034-model-choice-backend/index.json` 의 `status` 를 `completed` 로, `current_phase` 를 `4` 로 바꾼다

## 변경 파일

| 파일 | 변경 |
| --- | --- |
| `backend/src/main/java/com/bifos/assistant/hermes/dto/HermesModelCatalog.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/hermes/HermesModelClient.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/application/ModelOptionsService.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/application/ModelOptions.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/agent/application/AgentService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/application/ChatService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/presentation/ChatController.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/presentation/ChatDtos.java` | 수정 |
| `backend/src/main/resources/application.yml` | 수정 |
| `backend/src/test/java/com/bifos/assistant/hermes/HermesModelCatalogTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/chat/ModelOptionsServiceTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/chat/ChatAttachmentTurnTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/ConversationModelChoiceTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/ConversationPublicIdTest.java` | 수정 |
| `test/e2e/fake-hermes.ts` | 수정 |
| `test/e2e/scenarios/model-selection.ts` | 수정 |
| `tasks/plan034-model-choice-backend/index.json` | 수정 |
