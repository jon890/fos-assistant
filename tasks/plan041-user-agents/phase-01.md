# Phase 01. 에이전트의 주인을 공개 범위와 떼고, profile 을 만들 때 MCP 토큰을 넣는다

**Execution profile**: deep

## 목표

에이전트 행에 `profile_managed`, `deleted_at` 을 더하고, 그룹 공개로 바꿔도 주인을 지우지 않게 한다.
지운 에이전트는 읽기 목록, 대화 시작, 이어 보내기, 쓰기 경로 모두에서 없는 에이전트(`AGENT_NOT_FOUND`)로 본다.
profile 을 만드는 `HermesProfileProvisioner` 가 그 profile 에 묶인 MCP 토큰을 발급해 `.env` 에 넣게 한다. 사람을 더하는 흐름도 이 경로를 거친다.

**범위 외**: 사용자의 만들기·공개·지우기 경로(phase 02), 화면(phase 03).

## 컨텍스트

**근거 문서**: `docs/adr/ADR-033-사용자가-에이전트를-만들고-공개해도-만든-사람이-관리한다.md`, `docs/adr/ADR-032-mcp-토큰은-profile-을-증명하고-실제-사용자는-부모-실행에서-정한다.md`, `docs/data-schema.md` 의 「agent」, 「지울 때」 절, `docs/code-architecture.md` 의 「에이전트 만들기와 지우기」, 「사용자를 더할 때」 절, `docs/flow.md` 의 「에이전트를 만들 때」 의 「갈리는 지점」, `docs/hermes/profiles.md` 의 「Control Plane 이 부르는 대시보드 plugin 경로」 절

- `agent/presentation/AgentAdminController`: `create` 의 `ownerId(visibility, ownerEmail)` 는 `PRIVATE` 가 아니면 null 이다. `update` 도 `GROUP` 이면 `ownerId` 를 null 로 두고 `agent.changeAccess(request.enabled(), request.visibility(), ownerId)` 를 부른다
- `agent/application/AgentService`: `readableBy`, `requireReadable`, `requireStartable`, `requireReadableForUpdate`, `isEditableBy(user, agent)` 는 `user.isAdmin() || Objects.equals(agent.ownerUserId(), user.id())`. `isEditableBy` 의 Javadoc 은 「그룹이 함께 쓰는 에이전트는 주인이 없다」 고 적혀 있다
- `chat/application/ChatService`: `send` 경로(`resolveConversation` 뒤)와 `routeExisting` 이 `agents.requireById(conversation.agentId())` 뒤 `enabled` 가 거짓이면 `AGENT_DISABLED` 를 던진다
- `agent/infra/AgentRepository`: `findByCode`, `findByEnabledTrueOrderByCodeAsc`, `findByIdForUpdate`, `findByCodeForUpdate`, `existsByHermesProfile`
- `people/application/HermesProfileProvisioner.provision(String profileName)`: `dashboard.createProfile` 뒤 `API_SERVER_MODEL_NAME`, `API_SERVER_KEY` 를 `putEnv` 로 넣고 `keyStore.write`. 실패하면 `rollback` 이 key 파일과 profile 을 거둔다. 부르는 곳은 `people/application/PersonRegistrar.register`
- `mcp/application/AgentTokenService.issue(String profileName, String label)` 는 profile 에 묶인 토큰을 발급해 `IssuedToken(AgentToken token, String rawToken)` 을 돌려준다. 클래스 기본은 `@Transactional(readOnly = true)`, `issue` 는 `@Transactional`. `revoke(Long id)` 가 폐기한다. `mcp/infra/AgentTokenRepository` 에는 `findByTokenHash` 만 있다. `AgentToken` 의 칸은 `profileName`, `revokedAt`
- `hermes/HermesDashboardClient`: `createProfile(name)`, `putEnv(profile, key, value)`, `deleteProfile(name)`. `hermes/HttpHermesDashboardClient.deleteProfile` 은 모든 `RestClientException` 을 `HermesCallFailure.of` 로 감싸 404 도 `HERMES_UNAVAILABLE` 이 된다
- 대시보드 plugin 은 `POST /api/profiles` 안에서 MCP 등록(토큰 값 없음), 서명 plugin, 관리 표식을 붙인다. 토큰 값은 `PUT /api/env` 의 `MCP_FOS_ASSISTANT_API_KEY` 로 넣는다. `DELETE /api/profiles/<p>` 의 404 는 「이미 지움」 이다(`docs/hermes/profiles.md`)
- 테스트 대역: `backend/src/test/java/com/bifos/assistant/hermes/StubHermesDashboardClient.java`(호출을 기록하는 대역. `env(profile)` 은 `Map.copyOf` 라 순서가 없다), `backend/src/test/java/com/bifos/assistant/hermes/HermesDashboardRequestTest.java`(HTTP 요청 모양 검사)
- 가짜 대시보드: `test/e2e/fake-hermes.ts` 의 `PROFILES_PATH`, `ENV_PATH`

## 의도 메모

- **이 계획의 PR 은 하위 에이전트 session 소유 등록(ADR-037, Flyway V30)이 main 에 머지된 뒤, 그리고 V32, V33 을 쓰는 계획보다 먼저 머지한다.** Flyway 의 순서 밖 적용이 꺼져 있어(`application.yml`) V31 이 뒤늦게 들어가면 기동 검증이 실패한다. 새 에이전트는 처음부터 profile 에 묶인 토큰을 받아, session 소유 등록 없이는 백그라운드 하위 에이전트의 Memory 읽기와 결과물 쓰기가 거절된다. 구현과 PR 은 먼저 해도 된다
- 토큰은 **profile 을 만든 직후** 넣는다. 토큰 없는 MCP 연결 실패가 쌓이면 Hermes 가 다시 붙는 간격을 늘린다(`docs/hermes/profiles.md`)
- **토큰 발급과 profile 단위 폐기는 부르는 쪽 트랜잭션과 떼어 곧바로 커밋한다(`Propagation.REQUIRES_NEW`).** phase 02 의 만들기는 주인 행 잠금을 쥔 트랜잭션 안에서 provision 을 부른다. 발급이 그 트랜잭션에 묶이면 커밋 전 몇 초 동안 Hermes 가 새 토큰으로 붙어도 인증이 실패한다. 폐기도 같은 이유로 떼어야, 바깥 트랜잭션이 되돌려져도 폐기가 남는다
- 거두는 순서는 토큰 폐기, key 파일, profile 이다. 토큰을 먼저 폐기해 남은 연결의 호출을 막는다
- 폐기가 곧바로 커밋되므로, 지우기 중 `deleteProfile` 이 실패하면 에이전트는 남고 토큰은 폐기된 상태가 된다. 그동안 그 에이전트는 Memory 읽기와 결과물 쓰기 없이 돈다. 다시 지우면 404 가 정상 반환이라 끝까지 지워진다. 이것을 받아들인다
- 기존 그룹 공개 에이전트의 주인은 비어 있는 채로 둔다. 데이터를 옮기지 않는다
- 첫 로그인에 만드는 에이전트(`UserProvisioningService.createFirstAgent`)는 그 사람의 기본 profile 을 가리키므로 `profile_managed` 는 거짓이다
- 지운 에이전트의 대화는 읽기만 된다. 대화 이력과 사용량은 `requireById` 로 이름을 읽으므로 그대로 둔다

## Blocked 조건

- 운영 대시보드 plugin 이 위 계약을 아직 받지 않는 것은 이 phase 를 막지 않는다. 가짜 대시보드로 검증한다

## 작업 항목

### 1. 마이그레이션 `backend/src/main/resources/db/migration/V31__agent_managed_profile.sql`

번호는 V31 이다. 여러 계획을 나란히 구현해 번호를 미리 나눴다(V30 은 하위 에이전트 session 소유 등록이 쓴다). 칸마다 문장을 따로 쓴다.

- `ALTER TABLE agent ADD COLUMN profile_managed BOOLEAN NOT NULL DEFAULT FALSE;`
- `ALTER TABLE agent ADD COLUMN deleted_at DATETIME(6) NULL;`

### 2. 에이전트

- `agent/domain/Agent.java`: `profileManaged`, `deletedAt` 칸과 읽기 메서드. `markManagedProfile()`, `markDeleted(Instant now)`(`enabled` 도 내린다), `isDeleted()`
- `agent/application/AgentService.java`
  - `readableBy`, `requireReadable`, `requireStartable`, `requireReadableForUpdate` 가 지운 에이전트를 없는 에이전트로 본다(`AGENT_NOT_FOUND`)
  - `requireById` 는 그대로 지운 에이전트도 돌려준다
  - `isEditableBy` 의 Javadoc 을 새 규칙으로 고친다: 주인은 공개 범위와 무관하게 남고, 주인과 `ADMIN` 이 고친다. 이 결정 전의 그룹 공개 에이전트는 주인이 비어 `ADMIN` 만 고친다
- `chat/application/ChatService.java`: `requireById` 뒤 `enabled` 를 보는 두 곳이 그보다 먼저 `agent.isDeleted()` 면 `AGENT_NOT_FOUND` 를 던진다. `docs/flow.md` 「갈리는 지점」 의 「지운 에이전트의 대화에 보낸다 → `AGENT_NOT_FOUND`」 다
- `agent/presentation/AgentAdminController.java`
  - `create`: `GROUP` 일 때 `ownerEmail` 이 오면 그 사용자를 주인으로 둔다(없는 사용자면 지금처럼 `VALIDATION_FAILED`). 비어 있으면 주인 없이 만든다. `PRIVATE` 규칙은 그대로다
  - `update`: `GROUP` 으로 두거나 바꿀 때 `ownerEmail` 이 오면 그 사용자로 주인을 바꾸고, 비어 있으면 **지금 주인을 그대로 둔다**. `PRIVATE` 규칙은 그대로다. 지운 에이전트면 `AGENT_NOT_FOUND`(관리자가 `enabled=true` 로 지운 에이전트를 되살리지 못하게 한다)
  - `list` 는 지운 에이전트를 뺀다

### 3. profile 을 만들 때 MCP 토큰

- `mcp/infra/AgentTokenRepository.java`: `List<AgentToken> findByProfileNameAndRevokedAtIsNull(String profileName)` 을 더한다
- `mcp/application/AgentTokenService.java`
  - `issue` 를 `@Transactional(propagation = Propagation.REQUIRES_NEW)` 로 바꾼다. 관리자 발급 경로(`AgentTokenAdminController`)는 바깥 트랜잭션이 없어 동작이 같다
  - `revokeAllFor(String profileName)` 을 더한다. `REQUIRES_NEW` 로, 그 profile 에 묶인 폐기 안 된 토큰을 모두 폐기한다
- `hermes/HttpHermesDashboardClient.java`: `deleteProfile` 이 404(`HttpClientErrorException.NotFound`)를 받으면 예외 없이 돌아온다. 다른 오류는 지금처럼 `HermesCallFailure` 다. `HermesDashboardClient` 인터페이스의 `deleteProfile` Javadoc 에 「없는 profile 은 이미 지운 것으로 보고 정상 반환한다」 를 적는다
- `people/application/HermesProfileProvisioner.java`
  - 생성자에 `AgentTokenService` 를 더한다
  - `provision(String profileName)`: `createProfile` 직후 `AgentTokenService.issue(profileName, "profile " + profileName)` 로 발급해 `putEnv(profileName, "MCP_FOS_ASSISTANT_API_KEY", rawToken)` 으로 넣고, 그 뒤 지금의 `API_SERVER_MODEL_NAME`, `API_SERVER_KEY`, key 파일 순서를 따른다. 시그니처는 그대로다
  - 실패하면 토큰 폐기(`revokeAllFor`), key 파일, profile 순으로 거둔다. 지금의 `rollback` 규칙(하나라도 못 거두면 원래 오류, 모두 거두면 `HERMES_PROVISION_FAILED`)을 그대로 둔다. 토큰 폐기 실패도 「못 거둔 것」 으로 센다
  - `deprovision(String profileName)` 을 더한다. `revokeAllFor`, `deleteProfile`, key 파일 삭제 순이다. 하나라도 실패하면 그 오류를 그대로 던진다(부르는 쪽이 에이전트를 지우지 않게). 클래스 Javadoc 에 토큰까지 다룬다는 것을 적는다

### 4. 이 phase 를 검증하는 테스트

- `backend/src/test/java/com/bifos/assistant/hermes/StubHermesDashboardClient.java`: `putEnv` 호출을 불린 순서대로 `(profile, key)` 로 기록하고 읽는 메서드(`envWrites()`)를 더한다
- `backend/src/test/java/com/bifos/assistant/people/HermesProfileProvisionerTest.java`
  - `AgentTokenService` 는 Mockito mock 으로 넣고 `issue` 가 정해진 원문을 돌려주게 한다
  - 만들면 `envWrites()` 가 `MCP_FOS_ASSISTANT_API_KEY`, `API_SERVER_MODEL_NAME`, `API_SERVER_KEY` 순이고, `MCP_FOS_ASSISTANT_API_KEY` 값이 발급한 원문이며, `issue` 가 그 profile 이름으로 불렸다
  - 기존 `env_에_넣는_것은_모델_이름과_key_둘뿐이다` 는 세 key(`MCP_FOS_ASSISTANT_API_KEY`, `API_SERVER_MODEL_NAME`, `API_SERVER_KEY`)뿐이라는 단언으로 고친다. listener 설정을 넣지 않는다는 뜻은 유지한다
  - `putEnv` 가 실패하면 `revokeAllFor(profile)` 이 불리고 profile 이 지워지며 key 파일이 없다
  - `deprovision` 은 `revokeAllFor` 를 `deleteProfile` 보다 먼저 부르고, key 파일을 지운다. `deleteProfile` 이 실패하면 그 오류가 올라온다
- `backend/src/test/java/com/bifos/assistant/hermes/HermesDashboardRequestTest.java`: `DELETE /api/profiles/<p>` 가 404 면 예외 없이 끝나고, 500 이면 `HERMES_UNAVAILABLE` 이다
- `backend/src/test/java/com/bifos/assistant/agent/AgentLifecycleFlagsTest.java` 신규(`@SpringBootTest`, `test` profile, 실제 저장소)
  - `markDeleted` 뒤 `readableBy` 에서 빠지고, `requireStartable` 과 `requireReadableForUpdate` 가 `AGENT_NOT_FOUND`, `requireById` 는 읽힌다
  - 지운 에이전트로 시작한 기존 대화에 `ChatService.send` 로 보내면 `AGENT_NOT_FOUND` 이고 Hermes 를 부르지 않는다
  - 관리자 `update` 로 지운 에이전트를 켜면 `AGENT_NOT_FOUND`, 관리자 `list` 에 지운 에이전트가 없다
  - 관리자 `update` 로 `GROUP` 으로 바꿔도(`ownerEmail` 없음) `ownerUserId` 가 남고 주인이 `isEditableBy` 로 참이다. 다른 `MEMBER` 사용자는 거짓이다
- 관리자 경로 테스트가 `GROUP` 에서 주인이 null 이라고 단언하던 곳이 있으면 새 규칙으로 고친다

## 검증

```bash
# cwd: 저장소 root
cd backend && ./gradlew test --tests 'com.bifos.assistant.people.HermesProfileProvisionerTest' --tests 'com.bifos.assistant.agent.AgentLifecycleFlagsTest' --tests 'com.bifos.assistant.hermes.HermesDashboardRequestTest'
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
| `backend/src/main/java/com/bifos/assistant/chat/application/ChatService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/hermes/HermesDashboardClient.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/hermes/HttpHermesDashboardClient.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/people/application/HermesProfileProvisioner.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/mcp/application/AgentTokenService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/mcp/infra/AgentTokenRepository.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/hermes/StubHermesDashboardClient.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/hermes/HermesDashboardRequestTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/people/HermesProfileProvisionerTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/agent/AgentLifecycleFlagsTest.java` | 신규 |
