# Phase 02. 사용자가 에이전트를 만들고 공개 범위를 바꾸고 지우는 경로

**Execution profile**: deep

## 목표

`POST /api/v1/agents`, `PATCH /api/v1/agents/{code}/visibility`, `DELETE /api/v1/agents/{code}` 를 연다.
만들기는 한 요청 안에서 profile 을 끝까지 만들고, 사용자당 상한을 지킨다. 지우기는 Control Plane 이 만든 profile 만 profile 까지 지운다.

**범위 외**: 화면(phase 03). 올린 스킬 디렉터리 지우기(스킬 계획이 이 지우기에 붙인다).

## 컨텍스트

**근거 문서**: `docs/code-architecture.md` 의 「에이전트 만들기와 지우기」 절, `docs/flow.md` 의 「에이전트를 만들 때」 절, `docs/adr/ADR-033-사용자가-에이전트를-만들고-공개해도-만든-사람이-관리한다.md`, `docs/hermes/profiles.md` 의 「Control Plane 이 부르는 대시보드 plugin 경로」 절, `docs/hermes/tools-and-skills.md` 의 「도구 목록 조회와 버전 차이」 절

- phase 01 뒤: `Agent.markManagedProfile()`, `markDeleted(Instant)`, `isDeleted()`, `profileManaged()`. `HermesProfileProvisioner.provision(profileName)`(토큰까지 넣는다), `deprovision(profileName)`. `AgentService` 가 지운 에이전트를 없는 것으로 본다(`requireReadableForUpdate` 포함). `AgentTokenRepository.findByProfileNameAndRevokedAtIsNull`
- 새 에이전트 행: `Agent.of(code, name, hermesProfile, apiBaseUrl, costMode, credentialScope, visibility, ownerUserId)`. 주소는 `HermesProperties.profileBaseUrl(profileName)`, 과금은 `PeopleProperties.defaultCostMode()`, credential 은 `CredentialScope.SHARED_HOUSEHOLD`(ADR-033: 새 profile 은 가족 공용 credential 로 돈다). 참고로 `UserProvisioningService.createFirstAgent` 는 credential 을 `peopleProperties.defaultCredentialScope()` 로 정하므로 그 부분만 다르다
- profile 이름 규칙은 `hermes/HermesProfileName` 의 `[a-z0-9][a-z0-9-]{0,63}`. `code` 도 같은 규칙이다(`web/src/lib/agent.ts` 의 `AGENT_CODE_PATTERN`)
- profile 이름 겹침: `people/application/PersonRegistrar` 는 `people.existsByHermesProfile(name) || agents.existsByHermesProfile(name)` 로 두 표를 모두 본다(`AllowedPersonRepository`, `AgentRepository`)
- 그룹 공개 가능 여부: `AgentAdminController` 의 private 메서드 `requireGroupSafe(AgentVisibility, String apiBaseUrl, String profileName)` 가 `AgentToolPolicy.hasPrivateOnlyToolset(hermesToolsets.readEnabled(apiBaseUrl, profileName))` 로 본다. `hermesToolsets` 의 타입은 `hermes.HermesToolsetClient` 다. `readEnabled` 는 그 profile 의 key 파일로 `GET <주소>/v1/toolsets` 를 부른다
- **`readEnabled` 결과에는 MCP 서버 이름(`fos-assistant`)이 없다**(`docs/hermes/tools-and-skills.md`). MCP 가 붙었는지는 이 목록으로 확인할 수 없다
- 잠금: `AgentRepository.findByCodeForUpdate`(`@Lock(PESSIMISTIC_WRITE)` 와 `@Query`), `AgentService.requireReadableForUpdate`. 사용자 행 잠금은 없다(`user/infra/AppUserRepository`)
- 오류 코드는 `shared/error/ErrorCode`. `AGENT_TOOLS_REQUIRE_PRIVATE` 는 409 다
- 경로: `agent/presentation/AgentController`(`@RequestMapping("/api/v1/agents")`, `GET` 목록). `agent/presentation/AgentDtos` 에는 관리자용 `CreateAgentRequest(code, name, hermesProfile, …)` 가 이미 있고, `AgentView(code, name, visibility, acceptsAttachments, tagline, starterPrompts)` 와 `AgentView.from(Agent, List<String>)` 가 있다
- 테스트 하네스: `backend/src/test/resources/application-test.yml` 은 H2(MySQL 모드), 대시보드와 listener 주소는 닿지 않는 example.com 이다. Hermes 를 부르는 테스트는 `@MockitoBean` 으로 바꿔 끼운다(`people/PersonRegistrarTest` 가 선례). H2 의 `FOR UPDATE` 는 다른 트랜잭션을 실제로 기다리게 한다. 동시 저장 검사는 `StarterServiceTest` 가 선례다
- 가짜 대시보드: `test/e2e/fake-hermes.ts` 의 `POST /api/profiles` 는 빈 profile 을 만들고, 도구 목록을 저장하지 않은 profile 은 `DEFAULT_API_SERVER_TOOLSETS`(`terminal`, `file`, `browser`, `code_execution`, `session_search` 포함)로 답한다
- 관리자 테스트 `backend/src/test/java/com/bifos/assistant/agent/AgentApiBaseUrlUpdateTest.java` 는 `AgentAdminController` 를 직접 생성한다

## 의도 메모

- 사용자당 상한은 **주인 행 잠금** 안에서 센다. 같은 사람이 두 번 눌러도 상한을 넘지 않는다. `ADMIN` 은 세지 않는다. 첫 로그인 에이전트도 그 사용자의 지우지 않은 에이전트로 센다(`docs/code-architecture.md`)
- Hermes 호출이 몇 초 걸려 잠금을 그만큼 쥔다. 사용자 한 사람의 행이라 다른 사용자는 기다리지 않는다
- `code` 와 profile 이름은 사용자가 넣은 이름과 무관한 무작위 값이다. 이름은 한글일 수 있고 겹쳐도 된다
- **만든 뒤 도구 목록을 읽는 것은 plugin 틀이 안전한 기본 도구를 붙였는지 보는 확인이다.** 셸·파일 등급(`hasPrivateOnlyToolset`)이 켜져 있으면 틀이 적용되지 않은 것으로 보고 거둔다. MCP 등록은 목록으로 볼 수 없으므로 `POST /api/profiles` 가 200 으로 끝난 것으로 믿는다
- 에이전트 행 저장이 실패하면 `deprovision` 으로 거둔다. 제약 위반이 커밋 때가 아니라 그 자리에서 나도록 `saveAndFlush` 로 저장한다
- 그룹 공개 검사는 `AgentAdminController` 에서 `AgentLifecycleService.requireGroupSafe(String apiBaseUrl, String profileName)` 로 옮겨 두 경로가 같은 코드를 부른다. 관리자 `create` 는 행이 생기기 전에 요청 값으로, `update` 는 바꿀 새 주소로 검사하므로 `Agent` 가 아니라 주소와 profile 이름을 받는다. `AgentService` 에 두지 않는 것은 `new AgentService(repo)` 로 만드는 기존 테스트 넷을 건드리지 않기 위해서다
- **공개 범위 변경과 지우기는 주인과 `ADMIN` 이 한다**(`docs/code-architecture.md` 「에이전트 화면」 표). `ADMIN` 은 읽을 수 없는 다른 사람의 비공개 에이전트도 코드로 찾는다. 다른 사용자는 읽을 수 없으면 `AGENT_NOT_FOUND`, 읽을 수 있지만 주인이 아니면 `FORBIDDEN` 이다

## 작업 항목

### 1. 설정과 오류

- `agent/application/AgentProperties.java` 신규(`@ConfigurationProperties("assistant.agents")`, record): `maxPerUser`(기본 5). `application.yml` 에 `assistant.agents.max-per-user: 5` 를 더한다. 이 저장소의 다른 `@ConfigurationProperties` record 가 등록되는 방식을 따른다
- `ErrorCode.AGENT_LIMIT_REACHED(HttpStatus.CONFLICT)` 를 더한다
- `user/infra/AppUserRepository.findByIdForUpdate(Long id)`: `AgentRepository.findByCodeForUpdate` 처럼 `@Lock(LockModeType.PESSIMISTIC_WRITE)` 와 `@Query("select u from AppUser u where u.id = :id")` 로 쓴다(엔티티 이름은 실제 클래스를 따른다)
- `AgentRepository.countByOwnerUserIdAndDeletedAtIsNull(Long ownerUserId)`

### 2. `agent/application/AgentLifecycleService.java` 신규

- `@Transactional Agent create(CurrentUser user, String name, AgentVisibility visibility)`
  1. 이름은 앞뒤 공백을 떼고 1자 이상 100자 이하. 아니면 `VALIDATION_FAILED`. `visibility` 가 null 이면 `PRIVATE`
  2. `ADMIN` 이 아니면 `findByIdForUpdate(user.id())` 로 주인 행을 잠그고 `countByOwnerUserIdAndDeletedAtIsNull` 이 `maxPerUser` 이상이면 `AGENT_LIMIT_REACHED`
  3. `code` 는 `a` 뒤에 소문자 영숫자 10자, profile 이름은 `ua-` 뒤에 소문자 영숫자 10자(`SecureRandom`). `agents.findByCode`, `agents.existsByHermesProfile`, `allowedPeople.existsByHermesProfile` 중 하나라도 겹치면 한 번 다시 뽑고, 또 겹치면 `HERMES_PROVISION_FAILED`
  4. `provisioner.provision(profileName)`
  5. `hermesToolsets.readEnabled(apiBaseUrl, profileName)` 에 `AgentToolPolicy.hasPrivateOnlyToolset` 이 참이면 `deprovision` 하고 `HERMES_PROVISION_FAILED`. `visibility` 가 `GROUP` 이어도 이 확인이 그룹 공개 검사를 겸한다
  6. 에이전트 행을 만들어 `markManagedProfile()` 한 뒤 `saveAndFlush`. 주인은 요청자다(`ADMIN` 도 자기 것이 된다). 저장이 실패하면 `deprovision` 하고 원래 오류. 4단계부터 6단계 사이의 다른 예외도 `deprovision` 하고 원래 오류를 던진다(`deprovision` 자체가 실패하면 로그를 남기고 원래 오류)
- `@Transactional Agent changeVisibility(CurrentUser user, String code, AgentVisibility visibility)`: `visibility` 가 null 이면 `VALIDATION_FAILED`. 아래 「관리 대상 찾기」 로 찾는다. `GROUP` 이면 `requireGroupSafe(agent.apiBaseUrl(), agent.hermesProfile())`. `agent.changeAccess(agent.enabled(), visibility, agent.ownerUserId())` 로 주인은 그대로 둔다
- `@Transactional void delete(CurrentUser user, String code)`: 「관리 대상 찾기」 로 찾는다. `profileManaged()` 면 `deprovision(hermesProfile)`. 그 뒤 `markDeleted(Instant.now())`. `deprovision` 이 실패하면 지우지 않고 원래 오류
- 관리 대상 찾기: `ADMIN` 이면 `findByCodeForUpdate`, 아니면 `AgentService.requireReadableForUpdate`. 없거나 지웠으면 `AGENT_NOT_FOUND`. `AgentService.isEditableBy` 가 거짓이면 `FORBIDDEN`
- `public void requireGroupSafe(String apiBaseUrl, String profileName)`: 셸·파일 등급이 켜져 있으면 `AGENT_TOOLS_REQUIRE_PRIVATE`
- `AgentAdminController` 가 자기 `requireGroupSafe` 대신 이 메서드를 부르게 하고, 더 쓰지 않는 `HermesToolsetClient` 필드를 뺀다. `AgentApiBaseUrlUpdateTest` 는 지금의 mock `HermesToolsetClient` 로 실제 `AgentLifecycleService` 를 만들어 넘긴다(나머지 의존은 mock). 기존의 `readEnabled` stub 과 `verifyNoInteractions(hermesToolsets)` 단언을 그대로 둘 수 있다

### 3. 경로

`agent/presentation/AgentController.java` 에 더한다. 요청과 응답은 `AgentDtos` 에 둔다.

| 경로 | 요청 | 응답 |
| --- | --- | --- |
| `POST /api/v1/agents` | `CreateOwnAgentRequest(String name, AgentVisibility visibility)` | 201, `AgentView` |
| `PATCH /api/v1/agents/{code}/visibility` | `ChangeVisibilityRequest(AgentVisibility visibility)`. null 이면 `VALIDATION_FAILED` | 200, `AgentView` |
| `DELETE /api/v1/agents/{code}` | 없음 | 204 |

`AgentView` 에 `editable`(요청자가 관리할 수 있는가, `AgentService.isEditableBy`)과 `ownedByMe`(주인이 요청자인가)를 더한다. `GET` 목록도 같은 값을 채운다. 화면이 「공개와 삭제」 절을 보일지 정한다.

### 4. 가짜 대시보드

- `test/e2e/fake-hermes.ts`: `POST /api/profiles` 가 만든 profile 의 도구 목록을 plugin 틀처럼 안전한 목록 `["web", "skills", "todo", "vision", CONTROL_PLANE_MCP]` 로 저장한다. 기존 profile 과 기존 시나리오의 동작은 바꾸지 않는다

### 5. 이 phase 를 검증하는 테스트

- `backend/src/test/java/com/bifos/assistant/agent/AgentLifecycleServiceTest.java` 신규
  - `@SpringBootTest`, `test` profile, 테스트 클래스에 `@Transactional` 을 두지 않는다(서비스의 트랜잭션과 잠금이 그대로 돌게). `@MockitoBean HermesDashboardClient`, `@MockitoBean HermesToolsetClient`(기본은 안전한 목록을 돌려준다). provisioner, 토큰 서비스, key 저장소는 실제 bean 이다
  - 만들면 `createProfile` 과 `putEnv(profile, "MCP_FOS_ASSISTANT_API_KEY", …)` 가 불리고, 그 profile 의 폐기 안 된 토큰이 있고, 행이 `profileManaged` 이고 주인이 요청자다
  - 기본 상한 5: 이미 다섯인 `MEMBER` 의 만들기는 `AGENT_LIMIT_REACHED` 이고 Hermes 를 부르지 않는다. `ADMIN` 은 걸리지 않는다. 지운 에이전트는 세지 않는다
  - 동시 요청: 에이전트가 넷인 `MEMBER` 로 두 스레드가 `CyclicBarrier` 로 함께 `create` 를 부르면 하나만 성공하고 하나는 `AGENT_LIMIT_REACHED` 다. 회차마다 새 사용자로 20회 반복한다
  - 도구 목록에 `terminal` 이 있으면 `deleteProfile` 이 불리고 토큰이 폐기되며 행이 없고 `HERMES_PROVISION_FAILED`
  - 공개 범위를 `GROUP` 으로 바꿔도 주인이 남는다. 도구 목록에 `terminal` 이 있으면 `AGENT_TOOLS_REQUIRE_PRIVATE`
  - 다른 `MEMBER` 는 그룹 공개 에이전트의 공개 범위를 바꾸거나 지우지 못하고(`FORBIDDEN`), 비공개 에이전트는 `AGENT_NOT_FOUND` 다. `ADMIN` 은 다른 사람의 비공개 에이전트를 지울 수 있다
  - 지우면 `profileManaged` 면 `deleteProfile` 이 불리고 토큰이 폐기되며, 거짓이면 `deleteProfile` 을 부르지 않는다. 지운 뒤 `requireStartable` 은 `AGENT_NOT_FOUND`. `deleteProfile` 이 실패하면 행은 지워지지 않는다
  - 이름이 공백뿐이거나 101자면 `VALIDATION_FAILED`, 100자는 된다
- `backend/src/test/java/com/bifos/assistant/agent/AgentControllerLifecycleTest.java` 신규: 세 경로의 상태 코드(201, 200, 204, 409 `AGENT_LIMIT_REACHED`, `visibility` 가 null 인 `PATCH` 의 `VALIDATION_FAILED`)와 응답의 `editable`, `ownedByMe`. 이 저장소의 기존 컨트롤러 테스트 방식을 따른다
- `test/e2e/scenarios/agent-lifecycle.ts` 신규, `test/e2e/run.ts` 에 더한다: `kid` 가 이름만 넣어 만든 에이전트로 대화 한 번이 답으로 끝나고, 그룹 공개로 바꾸면 `dad` 의 목록에 보이며, 지우면 둘 다의 목록에서 빠지고 그 대화에 보내면 `AGENT_NOT_FOUND`. 시나리오가 끝날 때 만든 에이전트는 지운 상태로 남는다

## 검증

```bash
# cwd: 저장소 root
cd backend && ./gradlew test --tests 'com.bifos.assistant.agent.AgentLifecycleServiceTest' --tests 'com.bifos.assistant.agent.AgentControllerLifecycleTest'
cd backend && ./gradlew test
node test/e2e/run.ts
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/resources/application.yml` | 수정 |
| `backend/src/main/java/com/bifos/assistant/agent/application/AgentProperties.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/agent/application/AgentLifecycleService.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/agent/infra/AgentRepository.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/agent/presentation/AgentController.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/agent/presentation/AgentDtos.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/agent/presentation/AgentAdminController.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/user/infra/AppUserRepository.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/shared/error/ErrorCode.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/agent/AgentLifecycleServiceTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/agent/AgentControllerLifecycleTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/agent/AgentApiBaseUrlUpdateTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/ChatAttachmentTurnTest.java` | 수정 |
| `test/e2e/fake-hermes.ts` | 수정 |
| `test/e2e/scenarios/agent-lifecycle.ts` | 신규 |
| `test/e2e/run.ts` | 수정 |
