# Phase 02. backend 에서 에이전트 모델 목록과 막힌 provider 기억을 지운다

**Execution profile**: deep

## 목표

에이전트가 모델을 갖던 흔적을 backend 에서 지운다. 표와 칸은 새 migration 으로 지운다.
모델은 대화가 고르고 막힌 계정은 Hermes 가 다룬다는 결정을 코드에 남는 길 하나로 맞춘다.

**범위 외**: 화면과 web 서버 라우트는 phase 01 에서 이미 지웠다. 대화의 모델 선택은 plan034 가 만들었다.

## 컨텍스트

**근거 문서**: `docs/data-schema.md` 「agent」, `docs/code-architecture.md` 「에이전트 화면」 과 「첫 에이전트의 과금 설정은 `people` 이 갖는다」, `docs/flow.md` 「그 사람이 처음 로그인할 때」 의 「어긋나는 지점」, `docs/adr/ADR-030-모델과-effort-는-대화가-고르고-기본값은-hermes-profile-이-갖는다.md`

- phase 01 이 화면에서 모델 목록, 다시 읽기, 막힌 provider 를 뗐다. plan034 뒤로 `ChatService`, `AgentRunner`, `MemoryProposer` 는 `AgentModelSelector` 와 `ProviderBlocklist` 를 부르지 않는다. `grep -rn "AgentModelSelector\|ProviderBlocklist" backend/src/main` 로 남은 곳을 본다
- 지울 표와 칸: `agent_model_option`(V14), `provider_state`(V15), `agent.provider`, `agent.model`, `agent.model_synced_at`
- 지울 경로: `GET`, `PUT /api/v1/admin/agents/{code}/models`, `POST /api/v1/admin/agents/{code}/sync-model`(`AgentAdminController`), `GET /api/v1/admin/providers/blocked`(`ProviderAdminController`)
- 에이전트 등록 `POST /api/v1/admin/agents` 는 지금 `hermesModels.readModel` 로 모델을 읽어 못 읽으면 `AGENT_MODEL_UNKNOWN` 으로 거절하고, `CreateAgentRequest.provider` 를 받는다. 둘 다 없앤다
- 첫 로그인 `user/domain/UserProvisioningService` 는 Hermes 에서 모델을 읽어 못 읽으면 에이전트를 만들지 않는다. 읽지 않고 만들게 바꾼다
- 설정 `assistant.model.provider-cooldown`(`application.yml`, `ModelSelectionProperties`)도 지운다

## 의도 메모

- `ErrorCode.NO_MODEL_AVAILABLE`, `AGENT_MODEL_UNKNOWN` 은 쓰는 곳이 없어지면 지운다. 예전 실행의 `error_code` 에 문자열로 남아 있어도 코드 값이 필요하지 않다
- `ExecutionEventType.PROVIDER_SWITCHED` 는 지우지 않는다. 예전 사건 행이 그 이름을 갖는다
- `HermesModelClient.readModel`, `readOptions` 와 `hermes/dto/HermesModelOptions` 는 쓰는 곳이 없어지면 지운다. `readCatalog` 는 남는다
- `AgentView.model`, `AdminAgentView` 의 `provider`, `model`, `modelSyncedAt`, `models` 칸을 지운다. web 은 phase 01 에서 이 칸들을 읽지 않게 됐다

## 작업 항목

### 1. migration `backend/src/main/resources/db/migration/V27__drop_agent_model.sql`

`agent_model_option` 과 `provider_state` 표를 지우고 `agent` 의 `provider`, `model`, `model_synced_at` 칸을 지운다. MySQL 과 H2 MySQL 모드에 함께 있는 문법만 쓴다.

### 2. 지울 파일

`agent/application/AgentModelSelector.java`, `AgentModelSync.java`, `ProviderBlocklist.java`, `ModelSelectionProperties.java`, `agent/domain/AgentModelOption.java`, `ModelOption.java`, `ProviderState.java`, `agent/infra/AgentModelOptionRepository.java`, `ProviderStateRepository.java`, `agent/presentation/ProviderAdminController.java`, `hermes/dto/HermesModelOptions.java`

### 3. 고칠 파일

- `agent/domain/Agent.java`: 세 칸과 `syncModel`, `of` 의 인자를 지운다
- `agent/presentation/AgentAdminController.java`: 모델 목록과 동기화 경로, 등록의 모델 읽기를 지운다
- `agent/presentation/AgentDtos.java`: `CreateAgentRequest.provider`, `AgentView.model`, `AdminAgentView` 의 모델 칸, `ModelOptionView` 와 동기화 응답 타입을 지운다
- `user/domain/UserProvisioningService.java`: 모델을 읽지 않고 에이전트를 만든다
- `hermes/HermesModelClient.java`: `readModel`, `readOptions` 를 지운다
- `shared/error/ErrorCode.java`: 쓰지 않게 된 코드를 지운다
- `backend/src/main/resources/application.yml`: `assistant.model` 절을 지운다

### 4. 이 phase 를 검증하는 backend 테스트

- 지운다: `agent/AgentModelOptionMigrationTest.java`, `agent/AgentModelOptionTest.java`, `agent/AgentModelSyncTest.java`
- `user/FirstSignInTest.java`: Hermes 가 모델을 주지 않아도 첫 로그인에 에이전트가 만들어진다. Hermes 의 `/api/model/options` 를 부르지 않는다
- `agent/AgentApiBaseUrlUpdateTest.java` 와 에이전트를 만드는 테스트들: `Agent.of` 의 새 인자와 등록 요청에서 provider 를 빼도 등록된다
- 모델 목록을 심던 준비(`modelSelector.seedFirst`, `modelOptions.deleteAll()`)를 모든 테스트에서 지운다. `grep -rln "seedFirst\|AgentModelOptionRepository\|ProviderBlocklist" backend/src/test` 가 비어야 한다
- H2 로 모든 migration 을 도는 `*MigrationTest` 가 V27 을 함께 통과한다

### 5. 등록 양식의 provider

- `web/src/components/admin/agent-form.tsx` 의 「모델 제공사」 칸과 `web/src/components/agent/agent-admin-panel.tsx` 가 보내는 `provider` 를 지운다
- `web/src/lib/agent.ts` 의 `AdminAgent.provider` 와 `AgentView.model` 을 지운다
- `test/browser/fixtures.ts` 의 에이전트 등록 본문에서 `provider` 를 뺀다. `test/browser/admin.spec.ts` 의 등록 검사가 그 칸 없이 통과한다

### 6. e2e

`test/e2e/scenarios/agents.ts` 와 `binding.ts` 등 에이전트를 등록하는 시나리오에서 `provider` 칸을 뺀다. `test/e2e/scenarios/model-selection.ts` 에 남은 관리자 모델 경로 호출이 있으면 지운다.
가짜 Hermes 의 `__test/block-provider` 는 대화 선택의 막힘 검사가 쓰므로 둔다.

## 검증

`AGENTS.md` 「확인」 절의 여섯 명령을 적힌 순서대로 모두 돌린다. 새 워크트리라 `web` 에서 `pnpm install --frozen-lockfile` 이 먼저 필요하다. 첫 줄은 이 phase 의 테스트만 먼저 돌리는 것이다.

```bash
# cwd: 저장소 root
cd backend && ./gradlew test --tests '*FirstSignInTest' --tests '*AgentApiBaseUrlUpdateTest' --tests '*MigrationTest'
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
- `grep -rn "AgentModelSelector\|ProviderBlocklist\|AgentModelOption\|syncModel\|model_synced_at" backend/src` 가 아무것도 내지 않는다
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
| `backend/src/test/java/com/bifos/assistant/user/FirstSignInTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/agent/AgentApiBaseUrlUpdateTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/**/*Test.java` | 수정 |
| `test/e2e/scenarios/*.ts` | 수정 |
| `web/src/components/admin/agent-form.tsx` | 수정 |
| `web/src/components/agent/agent-admin-panel.tsx` | 수정 |
| `web/src/lib/agent.ts` | 수정 |
| `test/browser/fixtures.ts` | 수정 |
| `test/browser/admin.spec.ts` | 수정 |
| `tasks/plan035-remove-agent-models/index.json` | 수정 |
