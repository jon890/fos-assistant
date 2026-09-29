# Phase 01. 실행 요청이 모델을 빼거나 effort 를 싣는다

**Execution profile**: standard

## 목표

`/v1/runs` 요청이 provider 와 모델을 함께 싣거나 함께 뺄 수 있고, reasoning effort 를 `model_options` 로 싣는다.
뒤 phase 가 대화의 선택을 실행에 실을 때 이 요청 모양을 쓴다.

**범위 외**: 대화에 선택을 저장하는 것은 phase 02, 부르는 쪽(`ChatService`, `AgentRunner`, `MemoryProposer`)의 동작 변경은 phase 03 이다. 이 phase 는 부르는 쪽에 기존 값을 그대로 넘기고 effort 는 `null` 로 둔다.

## 컨텍스트

**근거 문서**: `docs/hermes/runs-api.md` 「모델은 실행마다 정한다」, `docs/adr/ADR-030-모델과-effort-는-대화가-고르고-기본값은-hermes-profile-이-갖는다.md`

- 요청 모양은 `backend/src/main/java/com/bifos/assistant/hermes/HttpHermesRunsClient.java` 의 `submitRequest` 가 만든다. 지금은 `provider`, `model` 을 늘 넣고, `requireProviderAndModel` 이 둘 중 하나라도 비면 `VALIDATION_FAILED` 로 세운다
- `backend/src/main/java/com/bifos/assistant/hermes/dto/HermesRunCommand.java` 는 `profileName, apiBaseUrl, input, instructions, sessionId, provider, model` 을 갖는 record 다
- 이 record 를 만드는 곳은 `chat/application/ChatService.java` 의 `begin`, `orchestration/application/AgentRunner.java`, `memory/application/MemoryProposer.java` 의 `proposeFrom` 과 테스트 `hermes/HermesBusyTest.java`, `hermes/HermesRunRequestTest.java` 다
- 가짜 Hermes 는 `test/e2e/fake-hermes.ts` 의 `POST /v1/runs` 처리다. `provider` 만 오고 `model` 이 없으면 실제처럼 `No LLM provider configured` 로 실패시킨다. 이 동작은 그대로 둔다

## 의도 메모

- effort 는 문자열로 넘긴다. 받을 값의 검사는 phase 02 의 `ModelChoice` 가 한다. 이 계층은 받은 값을 그대로 싣는다
- `model_options` 는 effort 가 있을 때만 넣는다. 빈 객체도 보내지 않는다. 보내지 않으면 Hermes 가 그 모델의 설정값을 쓴다
- 옛 형식 `model_options.reasoning_effort` 는 쓰지 않는다. `{"reasoning": {"effort": "<값>"}}` 만 쓴다

## 작업 항목

### 1. `HermesRunCommand` 에 effort 를 더한다

마지막 칸으로 `String reasoningEffort` 를 더한다. Javadoc 의 「둘 다 채워야 한다」 를 「둘을 함께 채우거나 함께 비운다. 비우면 profile 의 기본값으로 돈다」 로 고친다.
부르는 세 곳과 테스트 두 곳은 마지막 인자에 `null` 을 넘긴다.

### 2. `HttpHermesRunsClient` 의 요청 모양

- `requireProviderAndModel` 을 「하나만 채워진 경우만 거절한다」 로 바꾼다. 둘 다 비면 통과한다
- `submitRequest` 는 둘 다 있을 때만 `provider`, `model` 을 넣는다
- `reasoningEffort` 가 비어 있지 않으면 `model_options` 에 `Map.of("reasoning", Map.of("effort", <값>))` 를 넣는다

### 3. 이 phase 를 검증하는 `backend/src/test/java/com/bifos/assistant/hermes/HermesRunRequestTest.java`

이 파일에는 지금 HTTP 대역 서버가 없다. `backend/src/test/java/com/bifos/assistant/hermes/HermesBusyTest.java` 의 `HttpServer` 와 `respondWith` 를 본보기로, 받은 `POST /v1/runs` 요청 본문을 저장하는 `HttpServer` 를 이 파일에 새로 둔다. 저장한 본문을 JSON 으로 읽어 확인한다.
- provider 와 모델이 모두 비면 본문에 `provider`, `model`, `model_options` 키가 없다
- provider 와 모델과 effort 를 주면 셋이 모두 실린다. `model_options.reasoning.effort` 가 준 값이다
- provider 만 주면 Hermes 를 부르지 않고 `VALIDATION_FAILED` 다

### 4. 가짜 Hermes `test/e2e/fake-hermes.ts`

`POST /v1/runs` 가 받은 `model_options` 를 `lastSubmittedRuntime` 에 함께 적는다(`reasoningEffort` 칸). 타입 `lastSubmittedRuntime(): { provider?: string; model?: string; reasoningEffort?: string }` 로 넓힌다.
provider 와 모델을 모두 빼고 온 실행은 지금처럼 성공한다. 세션 조회가 답하는 실제 provider 와 모델은 그 profile 의 기본값(`MODEL_OPTIONS_PATH` 응답의 `provider` 와 `model`, 지금 `openai-codex` 와 `example-model`)으로 적는다. 지금은 세션 provider 를 `submitted.provider ?? null` 로 적어, 둘 다 빼고 온 실행의 provider 가 null 이 된다. 그러면 뒤 phase 에서 기본값 대화의 가격을 찾지 못해 `test/e2e/scenarios/usage-cost.ts` 의 provider 와 금액 단언이 깨진다. 기본값 두 값은 상수 하나로 두고 `MODEL_OPTIONS_PATH` 응답과 세션 응답이 함께 쓴다. `SESSION_MODEL_PROBE` 입력의 특별한 답은 그대로 둔다.

## 검증

`AGENTS.md` 「확인」 절의 여섯 명령을 적힌 순서대로 모두 돌린다. 새 워크트리라 `web` 에서 `pnpm install --frozen-lockfile` 이 먼저 필요하다. 첫 줄은 이 phase 의 테스트만 먼저 돌리는 것이다.

```bash
# cwd: 저장소 root
cd backend && ./gradlew test --tests '*HermesRunRequestTest' --tests '*HermesBusyTest'
cd backend && ./gradlew test
cd web && pnpm typecheck
cd web && AUTH_SECRET=build-time-placeholder ASSISTANT_JWT_SECRET=build-time-placeholder CONTROL_PLANE_BASE_URL=http://build-time-placeholder AUTH_GOOGLE_ID=build-time-placeholder AUTH_GOOGLE_SECRET=build-time-placeholder pnpm build
cd web && pnpm test:browser
node test/e2e/run.ts
node --test 'test/unit/**/*.test.ts'
scripts/check-public-safe.sh
```

- 모두 통과한다

## 변경 파일

| 파일 | 변경 |
| --- | --- |
| `backend/src/main/java/com/bifos/assistant/hermes/dto/HermesRunCommand.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/hermes/HttpHermesRunsClient.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/application/ChatService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/orchestration/application/AgentRunner.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/memory/application/MemoryProposer.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/hermes/HermesRunRequestTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/hermes/HermesBusyTest.java` | 수정 |
| `test/e2e/fake-hermes.ts` | 수정 |
