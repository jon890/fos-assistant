# Phase 02. backend 에서 에이전트 모델 목록과 막힌 provider 기억을 지운다

**Execution profile**: deep

## 목표

에이전트가 모델을 갖던 흔적을 backend 에서 지운다. 표와 칸은 새 migration 으로 지운다.
모델은 대화가 고르고 막힌 계정은 Hermes 가 다룬다는 결정을 코드에 남는 길 하나로 맞춘다.

**범위 외**: 화면과 web 서버 라우트는 phase 01 에서 이미 지웠다. 대화의 모델 선택(ADR-030)은 이미 main 에 있다. 대화의 모델 고르기 화면은 이 plan 이 다루지 않는다.

## 컨텍스트

**근거 문서**: `docs/data-schema.md` 「agent」, `docs/code-architecture.md` 「에이전트 화면」 과 「첫 에이전트의 과금 설정은 `people` 이 갖는다」, `docs/flow.md` 「그 사람이 처음 로그인할 때」 의 「어긋나는 지점」, `docs/adr/ADR-030-모델과-effort-는-대화가-고르고-기본값은-hermes-profile-이-갖는다.md`

- phase 01 이 화면에서 모델 목록, 다시 읽기, 막힌 provider 를 뗐다. ADR-030 의 대화별 선택이 들어온 뒤로 `ChatService`, `AgentRunner`, `MemoryProposer` 는 `AgentModelSelector` 와 `ProviderBlocklist` 를 부르지 않는다. `grep -rn "AgentModelSelector\|ProviderBlocklist" backend/src/main` 로 남은 곳을 본다
- 지울 표와 칸: `agent_model_option`(V14), `provider_state`(V15), `agent.provider`, `agent.model`, `agent.model_synced_at`
- 지울 경로: `GET`, `PUT /api/v1/admin/agents/{code}/models`, `POST /api/v1/admin/agents/{code}/sync-model`(`AgentAdminController`), `GET /api/v1/admin/providers/blocked`(`ProviderAdminController`)
- 에이전트 등록 `POST /api/v1/admin/agents` 는 지금 `hermesModels.readModel` 로 모델을 읽어 못 읽으면 `AGENT_MODEL_UNKNOWN` 으로 거절하고, `CreateAgentRequest.provider` 를 받는다. 둘 다 없앤다
- 첫 로그인 `user/domain/UserProvisioningService` 는 Hermes 에서 모델을 읽어 못 읽으면 에이전트를 만들지 않는다. 읽지 않고 만들게 바꾼다
- 설정 `assistant.model.provider-cooldown`(`application.yml`, `ModelSelectionProperties`)도 지운다. 브라우저 검사가 이 값을 환경 변수 `ASSISTANT_MODEL_PROVIDER_COOLDOWN` 으로 준다(`test/browser/fixtures.ts`)
- MEMBER 용 에이전트 목록 `web/src/app/agents/page.tsx` 가 `{agent.model}` 을 그린다. `AgentView.model` 을 지우면 이 줄도 지운다
- 테스트 지원 컨트롤러 `backend/src/test/java/com/bifos/assistant/testsupport/UsageTestSupportController.java` 가 `agent.provider()` 와 `agent.model()` 로 실행을 심는다. `smokeRun` 이 test classpath 로 뜨므로 이 파일이 컴파일되지 않으면 브라우저 검사와 e2e 도 멈춘다
- `usage/UsageCostRecordingTest.java`, `usage/UsageBreakdownTest.java`, `usage/ExecutionLifecycleTest.java` 가 `agent.provider()`, `agent.model()` 로 기대값을 만든다
- web 의 `web/src/components/error-message.ts` 에 `AGENT_MODEL_UNKNOWN` 문구가 있다. `execution-list.tsx` 의 `NO_MODEL_AVAILABLE` 은 옛 실행을 읽으므로 둔다
- 에이전트를 등록 본문에 `provider` 를 담아 보내는 e2e 시나리오는 `test/e2e/scenarios/binding.ts`, `agent-tools.ts`, `orchestration.ts` 다. `agents.ts` 와 `model-selection.ts` 에는 없다
- 모의 행에 `model` 칸이 남는 곳: `test/browser/start-screen.spec.ts`, `test/e2e/scenarios/people.ts`

## 의도 메모

- `ErrorCode.NO_MODEL_AVAILABLE`, `AGENT_MODEL_UNKNOWN` 은 쓰는 곳이 없어지면 지운다. 예전 실행의 `error_code` 에 문자열로 남아 있어도 코드 값이 필요하지 않다
- `ExecutionEventType.PROVIDER_SWITCHED` 는 지우지 않는다. 예전 사건 행이 그 이름을 갖는다
- `HermesModelClient.readModel`, `readOptions` 와 `hermes/dto/HermesModelOptions` 는 쓰는 곳이 없어지면 지운다. `readCatalog` 는 남는다
- `AgentView.model`, `AdminAgentView` 의 `provider`, `model`, `modelSyncedAt`, `models` 칸을 지운다. web 은 phase 01 에서 이 칸들을 읽지 않게 됐다
- **등록은 모델을 읽는 대신 주소가 닿는지 본다.** 지금은 `readModel` 이 실패하면 등록을 거절해 잘못된 주소와 profile 도 막았다. 이 확인을 잃지 않도록 `create` 에서 `AgentEndpointProbe.requireReachable(request.apiBaseUrl(), request.hermesProfile())` 를 부른다. 주소 수정 경로가 이미 쓰는 것이고 가짜 Hermes 가 `/v1/capabilities` 에 답한다
- 테스트용 실행 심기는 `SeededExecution` 에 `provider` 칸을 더한다. `provider` 가 비면 `"openai-codex"`, `model` 이 비면 `"example-model"` 로 채운다. 가짜 Hermes 의 기본 런타임(`test/e2e/fake-hermes.ts` 의 `DEFAULT_RUNTIME`)과 같은 값이라 지금 검사의 기대값이 그대로 맞는다
- usage 테스트의 기대값은 그 테스트 안의 상수로 바꾼다. 실행을 심을 때 쓴 provider 와 model 을 상수로 두고 기대값도 그 상수로 만든다
- 모의 행과 타입에서 `model` 칸을 뺀다(`start-screen.spec.ts`, `people.ts`). 화면이 읽는 칸만 갖춘다는 주석과 맞춘다
- **V27 을 배포하면 이전 이미지로 되돌릴 수 없다.** 이전 이미지는 `ddl-auto: validate` 에서 `agent.provider` 칸이 없어 뜨지 못한다. 배포 전에 데이터베이스를 백업한다. 절차는 `fos-home-infra` 가 갖는다

## 작업 항목

### 1. migration `backend/src/main/resources/db/migration/V27__drop_agent_model.sql`

`agent_model_option` 과 `provider_state` 표를 지우고 `agent` 의 `provider`, `model`, `model_synced_at` 칸을 지운다. MySQL 과 H2 MySQL 모드에 함께 있는 문법만 쓴다.
칸 하나마다 `ALTER TABLE agent DROP COLUMN <칸>;` 을 따로 쓴다. 두 엔진이 받는 여러 칸 지우기 문법이 서로 다르다. 외래 키가 걸린 표는 그 표부터 지운다.

### 2. 지울 파일

`agent/application/AgentModelSelector.java`, `AgentModelSync.java`, `ProviderBlocklist.java`, `ModelSelectionProperties.java`, `agent/domain/AgentModelOption.java`, `ModelOption.java`, `ProviderState.java`, `agent/infra/AgentModelOptionRepository.java`, `ProviderStateRepository.java`, `agent/presentation/ProviderAdminController.java`, `hermes/dto/HermesModelOptions.java`

### 3. 고칠 파일

- `agent/domain/Agent.java`: 세 칸과 `syncModel`, `of` 의 인자를 지운다
- `agent/presentation/AgentAdminController.java`: 모델 목록과 동기화 경로, 등록의 모델 읽기와 `seedFirst` 를 지운다. 등록에서 `endpointProbe.requireReachable` 로 주소를 확인한다(의도 메모)
- `agent/presentation/AgentDtos.java`: `CreateAgentRequest.provider`, `AgentView.model`, `AdminAgentView` 의 모델 칸, `ModelOptionView`, `UpdateModelOptionsRequest`, `ModelOptionRequest`, `BlockedProviderView`, 동기화 응답 타입(`ModelSyncView`)을 지운다
- `user/domain/UserProvisioningService.java`: 모델을 읽지 않고 에이전트를 만든다
- `hermes/HermesModelClient.java`: `readModel`, `readOptions` 를 지운다
- `shared/error/ErrorCode.java`: 쓰지 않게 된 코드를 지운다
- `backend/src/main/resources/application.yml`: `assistant.model` 절을 지운다

### 4. 이 phase 를 검증하는 backend 테스트

- 지운다: `agent/AgentModelOptionMigrationTest.java`, `agent/AgentModelOptionTest.java`, `agent/AgentModelSyncTest.java`
- 신규 `agent/AgentModelDropMigrationTest.java`: H2 MySQL 모드로 모든 migration 을 돈 뒤 `INFORMATION_SCHEMA` 에 `agent_model_option`, `provider_state` 표와 `agent` 의 `provider`, `model`, `model_synced_at` 칸이 없음을 단언한다. `agent` 의 `code`, `hermes_profile` 칸은 남아 있음도 단언한다. 기존 `GroupRenameMigrationTest` 의 준비 방식을 따른다
- `user/FirstSignInTest.java`: 모델을 읽은 시각과 1순위 목록을 보던 단언을 지운다. Hermes 가 모델을 주지 않거나 provider 를 비워 주면 에이전트를 만들지 않는다던 두 검사를 「Hermes 에 묻지 않고 에이전트가 만들어진다」 는 한 검사로 합친다. 모델을 읽지 않으므로 두 경우가 같은 조건이 된다. `Agent::provider, Agent::model` 추출을 뺀다. 모델을 읽는 대역을 두지 않고 `HermesModelClient` 를 부르지 않음을 `verifyNoInteractions` 로 단언한다
- 등록 정상 경로 검사(`agent/AgentApiBaseUrlUpdateTest.java` 에 더한다): provider 없이 `PRIVATE` 에이전트를 등록하면 200 이고 저장된다. 이때 `HermesModelClient` 를 부르지 않는다. 주소가 닿지 않으면(`requireReachable` 이 `VALIDATION_FAILED` 를 던지면) 등록이 거절되고 저장되지 않는다
- `agent/AgentApiBaseUrlUpdateTest.java` 와 에이전트를 만드는 테스트들: `Agent.of` 의 새 인자와 등록 요청에서 provider 를 빼도 등록된다
- `testsupport/UsageTestSupportController.java`: 의도 메모대로 `SeededExecution.provider` 를 더하고 빈 값을 상수로 채운다
- usage 테스트 셋의 `agent.provider()`, `agent.model()` 기대값을 테스트 안 상수로 바꾼다
- 모델 목록을 심던 준비(`modelSelector.seedFirst`, `modelOptions.deleteAll()`)를 모든 테스트에서 지운다. `grep -rln "seedFirst\|AgentModelOptionRepository\|ProviderBlocklist" backend/src/test` 가 비어야 한다
- H2 로 모든 migration 을 도는 `*MigrationTest` 가 V27 을 함께 통과한다

### 5. 등록 양식의 provider 와 web 의 남은 모델 칸

- `web/src/components/admin/agent-form.tsx` 의 「모델 제공사」 칸과 `web/src/components/agent/agent-admin-panel.tsx` 가 보내는 `provider` 를 지운다
- `web/src/lib/agent.ts` 의 `AdminAgent.provider` 와 `AgentView.model` 을 지운다
- `web/src/app/agents/page.tsx` 의 모델 한 줄을 지운다. 자리를 비워 두지 않는다
- `web/src/components/error-message.ts` 의 `AGENT_MODEL_UNKNOWN` 항목을 지운다
- `test/browser/fixtures.ts` 의 `ASSISTANT_MODEL_PROVIDER_COOLDOWN` 과 그 주석을 지운다
- `test/browser/admin.spec.ts` 의 등록 검사에서 「모델 제공사」 입력을 지우고 양식에 그 칸이 없음을 단언한다
- `test/browser/fixtures.ts` 의 에이전트 등록 본문에서 `provider` 를 뺀다. `test/browser/admin.spec.ts` 의 등록 검사가 그 칸 없이 통과한다

### 6. e2e

등록 본문에 `provider` 를 보내는 `test/e2e/scenarios/binding.ts`, `agent-tools.ts`, `orchestration.ts` 에서 그 칸을 뺀다. `test/e2e/scenarios/people.ts` 의 모의 행과 타입에서 `model` 을 뺀다.
가짜 Hermes 의 `__test/block-provider` 는 대화 선택의 막힘 검사가 쓰므로 둔다.

### 7. 문서

이 phase 가 끝나야 사실이 되는 문서를 최종 동작으로 고친다.

- `docs/data-schema.md` agent 표 아래 「**에이전트는 모델을 갖지 않는다.**」 단락: 「예전의 … 아직 남아 있다 … 뒤따르는 변경이 그 화면과 함께 지운다」 문장을, 그 칸과 두 표를 V27 이 지웠다는 한 문장으로 바꾼다
- `docs/flow.md` 「어긋나는 지점」 표의 「첫 로그인에 Hermes 가 모델을 주지 않는다」, 「첫 로그인에 Hermes 가 provider 를 비워서 준다」 두 줄: 첫 로그인은 모델을 읽지 않고 에이전트를 만든다는 한 줄로 바꾼다. 바로 아래 「에이전트 없이 들어온 사람은 …」 단락이 다른 실패로 여전히 맞는지 코드로 확인하고, 모델 때문에 생기던 경우만 가리켰으면 함께 고친다
- `docs/code-architecture.md` 의 「에이전트는 실행에 쓸 모델을 갖지 않는다. 다만 첫 로그인에 …」 단락: 첫 로그인도 모델을 읽지 않는다는 최종 동작으로 고친다
- `backend/AGENTS.md` 의 `AgentModelOptionMigrationTest` 예시를 `GroupRenameMigrationTest` 로 바꾼다
- `docs/adr/ADR-018-사람을-더하는-것을-control-plane-이-끝낸다.md` 「첫 로그인에 에이전트가 안 생길 수 있다」 절 끝에 ADR-030 뒤로 그 경우가 없어졌다는 한 줄을 더한다. 본문은 고치지 않는다
- `docs/adr/ADR-030-모델과-effort-는-대화가-고르고-기본값은-hermes-profile-이-갖는다.md` 「대체된 부분」 에 ADR-018 의 그 절을 대체한다는 한 줄을 더한다
- `docs/adr/INDEX.md` 의 ADR-018 상태에 첫 로그인의 모델 읽기가 ADR-030 으로 없어졌다고 적는다
- `docs/code-architecture.md` 「에이전트 화면」 에 등록과 주소 수정이 저장 전에 주소가 닿는지 보는 단락을 더한다. key 가 없을 때와 주소가 답하지 않을 때의 오류 코드를 나눠 적는다

## 검증

`AGENTS.md` 「확인」 절의 여섯 명령을 적힌 순서대로 모두 돌린다. 새 워크트리라 `web` 에서 `pnpm install --frozen-lockfile` 이 먼저 필요하다. 첫 줄은 이 phase 의 테스트만 먼저 돌리는 것이다.

```bash
# cwd: 저장소 root
cd backend && ./gradlew test --tests '*FirstSignInTest' --tests '*AgentApiBaseUrlUpdateTest' --tests '*MigrationTest' --tests '*Usage*Test' --tests '*ExecutionLifecycleTest'
cd web && pnpm test:browser admin.spec.ts
cd backend && ./gradlew test
cd web && pnpm typecheck
cd web && AUTH_SECRET=build-time-placeholder ASSISTANT_JWT_SECRET=build-time-placeholder CONTROL_PLANE_BASE_URL=http://build-time-placeholder AUTH_GOOGLE_ID=build-time-placeholder AUTH_GOOGLE_SECRET=build-time-placeholder pnpm build
cd web && pnpm test:browser
node test/e2e/run.ts
node --test 'test/unit/**/*.test.ts'
scripts/check-public-safe.sh
```

- 모두 통과한다
- `grep -rn "AgentModelSelector\|ProviderBlocklist\|AgentModelOption\|syncModel\|model_synced_at" backend/src/main/java` 가 아무것도 내지 않는다. 이미 적용된 migration 과 V27 은 그 이름을 가지므로 `resources` 는 검색하지 않는다. 이미 적용된 migration 을 고치지 않는다
- `grep -rln "seedFirst\|AgentModelOptionRepository\|ProviderBlocklist" backend/src/test` 가 비어 있다
- `grep -rn "AGENT_MODEL_UNKNOWN\|PROVIDER_COOLDOWN" web/src test backend/src/main` 가 비어 있다
- 모두 통과하면 `tasks/plan035-remove-agent-models/index.json` 의 `status` 를 `completed` 로, `current_phase` 를 `2` 로 바꾼다

## 변경 파일

| 파일 | 변경 |
| --- | --- |
| `backend/src/main/resources/db/migration/V27__drop_agent_model.sql` | 신규 |
| `backend/src/main/java/com/bifos/assistant/agent/application/AgentModelSelector.java` | 삭제 |
| `backend/src/main/java/com/bifos/assistant/agent/application/AgentModelSync.java` | 삭제 |
| `backend/src/main/java/com/bifos/assistant/agent/application/ProviderBlocklist.java` | 삭제 |
| `backend/src/main/java/com/bifos/assistant/agent/application/ModelSelectionProperties.java` | 삭제 |
| `backend/src/main/java/com/bifos/assistant/agent/domain/AgentModelOption.java` | 삭제 |
| `backend/src/main/java/com/bifos/assistant/agent/domain/ModelOption.java` | 삭제 |
| `backend/src/main/java/com/bifos/assistant/agent/domain/ProviderState.java` | 삭제 |
| `backend/src/main/java/com/bifos/assistant/agent/infra/AgentModelOptionRepository.java` | 삭제 |
| `backend/src/main/java/com/bifos/assistant/agent/infra/ProviderStateRepository.java` | 삭제 |
| `backend/src/main/java/com/bifos/assistant/agent/presentation/ProviderAdminController.java` | 삭제 |
| `backend/src/main/java/com/bifos/assistant/hermes/dto/HermesModelOptions.java` | 삭제 |
| `backend/src/main/java/com/bifos/assistant/agent/domain/Agent.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/agent/presentation/AgentAdminController.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/agent/presentation/AgentDtos.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/user/domain/UserProvisioningService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/hermes/HermesModelClient.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/shared/error/ErrorCode.java` | 수정 |
| `backend/src/main/resources/application.yml` | 수정 |
| `backend/src/test/java/com/bifos/assistant/agent/AgentModelOptionMigrationTest.java` | 삭제 |
| `backend/src/test/java/com/bifos/assistant/agent/AgentModelOptionTest.java` | 삭제 |
| `backend/src/test/java/com/bifos/assistant/agent/AgentModelSyncTest.java` | 삭제 |
| `backend/src/test/java/com/bifos/assistant/agent/AgentModelDropMigrationTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/testsupport/UsageTestSupportController.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/user/FirstSignInTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/agent/AgentApiBaseUrlUpdateTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/**/*Test.java` | 수정 |
| `test/e2e/scenarios/*.ts` | 수정 |
| `web/src/components/admin/agent-form.tsx` | 수정 |
| `web/src/components/agent/agent-admin-panel.tsx` | 수정 |
| `web/src/lib/agent.ts` | 수정 |
| `web/src/app/agents/page.tsx` | 수정 |
| `web/src/components/error-message.ts` | 수정 |
| `test/browser/start-screen.spec.ts` | 수정 |
| `test/browser/fixtures.ts` | 수정 |
| `test/browser/admin.spec.ts` | 수정 |
| `docs/data-schema.md` | 수정 |
| `docs/flow.md` | 수정 |
| `docs/code-architecture.md` | 수정 |
| `docs/adr/ADR-018-사람을-더하는-것을-control-plane-이-끝낸다.md` | 수정 |
| `docs/adr/ADR-030-모델과-effort-는-대화가-고르고-기본값은-hermes-profile-이-갖는다.md` | 수정 |
| `docs/adr/INDEX.md` | 수정 |
| `backend/AGENTS.md` | 수정 |
| `tasks/plan035-remove-agent-models/index.json` | 수정 |
