# Phase 02. 사용자가 에이전트를 만들고 공개 범위를 바꾸고 지우는 경로

**Execution profile**: deep

## 목표

`POST /api/v1/agents`, `PATCH /api/v1/agents/{code}/visibility`, `DELETE /api/v1/agents/{code}` 를 연다.
만들기는 한 요청 안에서 profile 을 끝까지 만들고, 사용자당 상한을 지킨다. 지우기는 Control Plane 이 만든 profile 만 profile 까지 지운다.

**범위 외**: 화면(phase 03). 올린 스킬 디렉터리 지우기(스킬 계획이 이 지우기에 붙인다).

## 컨텍스트

**근거 문서**: `docs/code-architecture.md` 의 「에이전트 만들기와 지우기」 절, `docs/flow.md` 의 「에이전트를 만들 때」 절, `docs/adr/ADR-033-사용자가-에이전트를-만들고-공개해도-만든-사람이-관리한다.md`, `docs/hermes/profiles.md` 의 「Control Plane 이 부르는 대시보드 plugin 경로」 절

- phase 01 뒤: `Agent.markManagedProfile()`, `markDeleted(Instant)`, `isDeleted()`, `profileManaged()`. `HermesProfileProvisioner.provision(profileName)`(토큰까지 넣는다), `deprovision(profileName)`. `AgentService` 가 지운 에이전트를 없는 것으로 본다
- 새 에이전트 행: `Agent.of(code, name, hermesProfile, apiBaseUrl, costMode, credentialScope, visibility, ownerUserId)`. 주소는 `HermesProperties.profileBaseUrl(profileName)`, 과금은 `PeopleProperties.defaultCostMode()`, credential 은 `CredentialScope.SHARED_HOUSEHOLD`. `UserProvisioningService.createFirstAgent` 가 같은 모양으로 만든다
- profile 이름 규칙은 `hermes/HermesProfileName` 의 `[a-z0-9][a-z0-9-]{0,63}`. `code` 도 같은 규칙이다(`web/src/lib/agent.ts` 의 `AGENT_CODE_PATTERN`)
- 가족용 공개 가능 여부: `AgentAdminController.requireGroupSafe` 가 `AgentToolPolicy.hasPrivateOnlyToolset(hermesToolsets.readEnabled(apiBaseUrl, profileName))` 로 본다
- 잠금: `AgentRepository.findByCodeForUpdate`, `AgentService.requireReadableForUpdate`. 사용자 행 잠금은 없다(`user/infra/AppUserRepository`)
- 오류 코드는 `shared/error/ErrorCode`. `AGENT_TOOLS_REQUIRE_PRIVATE` 는 409 다
- 경로: `agent/presentation/AgentController`(`@RequestMapping("/api/v1/agents")`, `GET` 목록)

## 의도 메모

- 사용자당 상한은 **주인 행 잠금** 안에서 센다. 같은 사람이 두 번 눌러도 상한을 넘지 않는다. `ADMIN` 은 세지 않는다
- Hermes 호출이 몇 초 걸려 잠금을 그만큼 쥔다. 사용자 한 사람의 행이라 다른 사용자는 기다리지 않는다
- `code` 와 profile 이름은 사용자가 넣은 이름과 무관한 무작위 값이다. 이름은 한글일 수 있고 겹쳐도 된다
- 에이전트 행 저장이 실패하면 `deprovision` 으로 거둔다
- 가족용 공개 검사는 `AgentAdminController` 에서 `AgentService` 로 옮겨 두 경로가 같은 코드를 부른다

## 작업 항목

### 1. 설정과 오류

- `agent/application/AgentProperties.java` 신규(`@ConfigurationProperties("assistant.agents")`): `maxPerUser`(기본 5). `application.yml` 에 더한다
- `ErrorCode.AGENT_LIMIT_REACHED(HttpStatus.CONFLICT)` 를 더한다
- `user/infra/AppUserRepository.findByIdForUpdate(Long id)`(비관적 쓰기 잠금). `AgentRepository.countByOwnerUserIdAndDeletedAtIsNull(Long ownerUserId)`

### 2. `agent/application/AgentLifecycleService.java` 신규

- `Agent create(CurrentUser user, String name, AgentVisibility visibility)`
  1. 이름은 앞뒤 공백을 떼고 1~100자. 아니면 `VALIDATION_FAILED`. `visibility` 가 null 이면 `PRIVATE`
  2. `ADMIN` 이 아니면 주인 행을 잠그고 `countByOwnerUserIdAndDeletedAtIsNull` 이 `maxPerUser` 이상이면 `AGENT_LIMIT_REACHED`
  3. `code` 는 `a` 뒤에 소문자 영숫자 10자, profile 이름은 `ua-` 뒤에 소문자 영숫자 10자. `findByCode`, `existsByHermesProfile` 로 겹치면 한 번 다시 뽑는다
  4. `provisioner.provision(profileName)`
  5. `hermesToolsets.readEnabled(apiBaseUrl, profileName)` 로 도구 목록을 읽어 `AgentToolPolicy.CONTROL_PLANE_MCP` 가 있는지 본다. 없으면 `deprovision` 하고 `HERMES_PROVISION_FAILED`
  6. 에이전트 행을 저장하고 `markManagedProfile()`. 저장이 실패하면 `deprovision` 하고 원래 오류
- `Agent changeVisibility(CurrentUser user, String code, AgentVisibility visibility)`: `requireReadableForUpdate`, `isEditableBy` 가 아니면 `FORBIDDEN`. `GROUP` 이면 `AgentService.requireGroupSafe(agent)`. 주인은 그대로 둔다
- `void delete(CurrentUser user, String code)`: `requireReadableForUpdate`, `isEditableBy` 가 아니면 `FORBIDDEN`. `profileManaged()` 면 `deprovision(hermesProfile)`. 그 뒤 `markDeleted(Instant.now())`. `deprovision` 이 실패하면 지우지 않고 원래 오류
- `AgentService.requireGroupSafe(Agent agent)` 를 더하고 `AgentAdminController` 가 그것을 부르게 한다

### 3. 경로

`agent/presentation/AgentController.java` 에 더한다. 요청과 응답은 `AgentDtos` 에 둔다.

| 경로 | 요청 | 응답 |
| --- | --- | --- |
| `POST /api/v1/agents` | `CreateAgentRequest(String name, AgentVisibility visibility)` | 201, `AgentView` |
| `PATCH /api/v1/agents/{code}/visibility` | `ChangeVisibilityRequest(AgentVisibility visibility)`. null 이면 `VALIDATION_FAILED` | 200, `AgentView` |
| `DELETE /api/v1/agents/{code}` | 없음 | 204 |

`AgentView` 에 `editable`(요청자가 관리할 수 있는가)과 `ownedByMe` 를 더한다. 화면이 공개와 삭제 절을 보일지 정한다.

### 4. 이 phase 를 검증하는 테스트

- `backend/src/test/java/com/bifos/assistant/agent/AgentLifecycleServiceTest.java` 신규
  - 만들면 가짜 대시보드에 profile 이 생기고 `MCP_FOS_ASSISTANT_API_KEY` 가 들어가며 행이 `profileManaged` 이고 주인이 요청자다
  - 여섯 번째 만들기는 `AGENT_LIMIT_REACHED`, `ADMIN` 은 걸리지 않는다
  - 두 요청이 동시에 와도 상한을 넘지 않는다(스레드 둘, 상한 1)
  - 도구 목록에 Control Plane MCP 가 없으면 profile 을 거두고 `HERMES_PROVISION_FAILED`
  - 가족용으로 바꿔도 주인이 남고, 셸 도구가 켜져 있으면 `AGENT_TOOLS_REQUIRE_PRIVATE`
  - 다른 사용자는 공개 범위를 바꾸거나 지우지 못한다(`FORBIDDEN`, 볼 수 없으면 `AGENT_NOT_FOUND`)
  - 지우면 `profileManaged` 면 profile 과 토큰이 사라지고, 거짓이면 profile 을 부르지 않는다. 지운 뒤 `requireStartable` 은 `AGENT_NOT_FOUND`
- `backend/src/test/java/com/bifos/assistant/agent/AgentControllerLifecycleTest.java` 신규: 세 경로의 상태 코드와 응답 모양
- `test/e2e/scenarios/agent-lifecycle.ts` 신규, `test/e2e/run.ts` 에 더한다: `kid` 가 이름만 넣어 만든 에이전트로 대화 한 번이 답으로 끝나고, 가족용으로 바꾸면 `dad` 의 목록에 보이며, 지우면 둘 다의 목록에서 빠지고 그 대화에 보내면 `AGENT_NOT_FOUND`

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
| `backend/src/main/java/com/bifos/assistant/agent/application/AgentService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/agent/infra/AgentRepository.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/agent/presentation/AgentController.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/agent/presentation/AgentDtos.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/agent/presentation/AgentAdminController.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/user/infra/AppUserRepository.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/shared/error/ErrorCode.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/agent/AgentLifecycleServiceTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/agent/AgentControllerLifecycleTest.java` | 신규 |
| `test/e2e/scenarios/agent-lifecycle.ts` | 신규 |
| `test/e2e/run.ts` | 수정 |
