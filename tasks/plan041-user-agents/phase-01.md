# Phase 01. 에이전트의 주인을 공개 범위와 떼고, profile 을 만들 때 MCP 토큰을 넣는다

**Execution profile**: deep

## 목표

에이전트 행에 `profile_managed`, `deleted_at` 을 더하고, 가족용으로 공개해도 주인을 지우지 않게 한다.
profile 을 만드는 `HermesProfileProvisioner` 가 그 profile 에 묶인 MCP 토큰을 발급해 `.env` 에 넣게 한다. 사람을 더하는 흐름도 이 경로를 거친다.

**범위 외**: 사용자의 만들기·공개·지우기 경로(phase 02), 화면(phase 03).

## 컨텍스트

**근거 문서**: `docs/adr/ADR-033-사용자가-에이전트를-만들고-공개해도-만든-사람이-관리한다.md`, `docs/adr/ADR-032-mcp-토큰은-profile-을-증명하고-실제-사용자는-부모-실행에서-정한다.md`, `docs/data-schema.md` 의 「agent」, 「지울 때」 절, `docs/code-architecture.md` 의 「에이전트 만들기와 지우기」, 「사용자를 더할 때」 절, `docs/hermes/profiles.md` 의 「Control Plane 이 부르는 대시보드 plugin 경로」 절

- `agent/presentation/AgentAdminController`: `create` 의 `ownerId(visibility, ownerEmail)` 는 `PRIVATE` 가 아니면 null 이다. 공개 범위를 바꾸는 경로도 `GROUP` 이면 `ownerId` 를 null 로 둔다(`agent.changeAccess(request.enabled(), request.visibility(), ownerId)`)
- `agent/application/AgentService`: `readableBy`, `requireReadable`, `requireStartable`, `requireReadableForUpdate`, `isEditableBy(user, agent)` 는 `user.isAdmin() || Objects.equals(agent.ownerUserId(), user.id())`
- `agent/infra/AgentRepository`: `findByCode`, `findByEnabledTrueOrderByCodeAsc`, `findByIdForUpdate`, `findByCodeForUpdate`, `existsByHermesProfile`
- `people/application/HermesProfileProvisioner.provision(String profileName)`: `dashboard.createProfile` 뒤 `API_SERVER_MODEL_NAME`, `API_SERVER_KEY` 를 `putEnv` 로 넣고 `keyStore.write`. 실패하면 key 파일과 profile 을 역순으로 거둔다. 부르는 곳은 `people/application/PersonRegistrar.register`
- `mcp/application/AgentTokenService.issue(String profileName, String label)` 는 profile 에 묶인 토큰을 발급해 `IssuedToken(AgentToken token, String rawToken)` 을 돌려준다. `revoke(Long id)` 가 폐기한다
- `hermes/HermesDashboardClient`: `createProfile(name)`, `putEnv(profile, key, value)`, `deleteProfile(name)`
- 대시보드 plugin 은 `POST /api/profiles` 안에서 MCP 등록(토큰 값 없음), 서명 plugin, 관리 표식을 붙인다. 토큰 값은 `PUT /api/env` 의 `MCP_FOS_ASSISTANT_API_KEY` 로 넣는다
- 가짜 대시보드: `test/e2e/fake-hermes.ts` 의 `PROFILES_PATH`, `ENV_PATH`. backend 단위 테스트는 `HermesProfileProvisionerTest` 의 방법을 따른다

## 의도 메모

- **이 계획의 PR 은 하위 에이전트 session 소유 등록(ADR-037)이 main 에 머지된 뒤에 머지한다.** 새 에이전트는 처음부터 profile 에 묶인 토큰을 받아, 그 등록 없이는 백그라운드 하위 에이전트의 Memory 읽기와 결과물 쓰기가 거절된다. 구현과 PR 은 먼저 해도 된다
- 토큰은 **profile 을 만든 직후** 넣는다. 토큰 없는 MCP 연결 실패가 쌓이면 Hermes 가 다시 붙는 간격을 늘린다(`docs/hermes/profiles.md`)
- 거두는 순서는 토큰 폐기, key 파일, profile 이다. 토큰을 먼저 폐기해 남은 연결의 호출을 막는다
- 기존 가족용 에이전트의 주인은 비어 있는 채로 둔다. 데이터를 옮기지 않는다
- 첫 로그인에 만드는 에이전트(`UserProvisioningService.createFirstAgent`)는 그 사람의 기본 profile 을 가리키므로 `profile_managed` 는 거짓이다

## Blocked 조건

- 운영 대시보드 plugin 이 위 계약을 아직 받지 않는 것은 이 phase 를 막지 않는다. 가짜 대시보드로 검증한다

## 작업 항목

### 1. 마이그레이션 `backend/src/main/resources/db/migration/V31__agent_managed_profile.sql`

번호는 V31 이다. 여러 계획을 나란히 구현해 번호를 미리 나눴다(V30 은 하위 에이전트 session 소유 등록이 쓴다). 칸마다 문장을 따로 쓴다.

- `ALTER TABLE agent ADD COLUMN profile_managed BOOLEAN NOT NULL DEFAULT FALSE;`
- `ALTER TABLE agent ADD COLUMN deleted_at DATETIME(6) NULL;`

### 2. 에이전트

- `agent/domain/Agent.java`: `profileManaged`, `deletedAt` 칸과 읽기 메서드. `markManagedProfile()`, `markDeleted(Instant now)`(`enabled` 도 내린다), `isDeleted()`
- `agent/application/AgentService.java`: `readableBy`, `requireReadable`, `requireStartable` 이 지운 에이전트를 없는 에이전트로 본다(`AGENT_NOT_FOUND`). 대화 이력과 사용량은 `requireById` 로 이름을 읽으므로 그대로 둔다
- `agent/presentation/AgentAdminController.java`: `create` 와 공개 범위를 바꾸는 경로가 `GROUP` 일 때도 주인을 지우지 않는다. `GROUP` 으로 만들 때 `ownerEmail` 이 오면 그 사람을 주인으로, 없으면 비워 둔다. `list` 는 지운 에이전트를 뺀다

### 3. profile 을 만들 때 MCP 토큰

- `people/application/HermesProfileProvisioner.provision(String profileName)`: `createProfile` 직후 `AgentTokenService.issue(profileName, "profile " + profileName)` 로 토큰을 발급해 `putEnv(profileName, "MCP_FOS_ASSISTANT_API_KEY", raw)` 로 넣고, 그 뒤 지금의 key 순서를 따른다. 시그니처는 그대로다
- 실패하면 발급한 토큰 폐기, key 파일, profile 순으로 거둔다. 지금의 `rollback` 규칙(하나라도 못 거두면 원래 오류)을 그대로 둔다
- `mcp/application/AgentTokenService.revokeAllFor(String profileName)` 을 더한다. 그 profile 에 묶인 폐기 안 된 토큰을 모두 폐기한다
- `HermesProfileProvisioner.deprovision(String profileName)` 을 더한다. `revokeAllFor`, `deleteProfile`, key 파일 삭제 순이다. `deleteProfile` 의 404 는 이미 지운 것으로 본다

### 4. 이 phase 를 검증하는 테스트

- `backend/src/test/java/com/bifos/assistant/people/HermesProfileProvisionerTest.java`: 만들면 `createProfile`, `MCP_FOS_ASSISTANT_API_KEY`, `API_SERVER_MODEL_NAME`, `API_SERVER_KEY` 순으로 부르고, 발급한 토큰이 그 profile 에 묶였다. `putEnv` 가 실패하면 토큰이 폐기되고 profile 이 지워진다. `deprovision` 은 그 profile 의 토큰을 먼저 모두 폐기하고 `deleteProfile` 의 404 를 성공으로 본다
- `backend/src/test/java/com/bifos/assistant/agent/AgentLifecycleFlagsTest.java` 신규: `markDeleted` 뒤 `readableBy` 에서 빠지고 `requireStartable` 이 `AGENT_NOT_FOUND`, `requireById` 는 읽힌다. `GROUP` 으로 바꿔도 `ownerUserId` 가 남고 주인이 `isEditableBy` 로 참이다
- 관리자 경로 테스트가 `GROUP` 에서 주인이 null 이라고 단언하던 곳이 있으면 새 규칙으로 고친다

## 검증

```bash
# cwd: 저장소 root
cd backend && ./gradlew test --tests 'com.bifos.assistant.people.HermesProfileProvisionerTest' --tests 'com.bifos.assistant.agent.AgentLifecycleFlagsTest'
cd backend && ./gradlew test
node test/e2e/run.ts
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/resources/db/migration/V31__agent_managed_profile.sql` | 신규 |
| `backend/src/main/java/com/bifos/assistant/agent/domain/Agent.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/agent/application/AgentService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/agent/presentation/AgentAdminController.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/people/application/HermesProfileProvisioner.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/mcp/application/AgentTokenService.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/people/HermesProfileProvisionerTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/agent/AgentLifecycleFlagsTest.java` | 신규 |
