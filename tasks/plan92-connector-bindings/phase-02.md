# Phase 02. 연결과 바인딩 서비스

**Execution profile**: deep

## 목표

연결을 사용자와 커넥터 단위로 등록, 확인, 해제하고 값은 보관 파일에 둔다. 연결을 에이전트에 붙이고 떼는 서비스를 만든다.
등록이 더는 커넥터마다 에이전트를 만들지 않는다.

**범위 외**: 판정과 승인 실행, 에이전트의 공개와 삭제, 도구 목록 읽기, 실행 기록 가림(phase 03). 먼저 살펴보기(phase 04). HTTP 경로와 화면(phase 05).

## 컨텍스트

**근거 문서**: `docs/adr/ADR-083-커넥터는-사용자가-한-번-연결하고-자기-에이전트에-여럿-붙여-그-에이전트가-도구를-직접-부른다.md`, `docs/backend/connector-install.md` 의 「대시보드 plugin 계약」 과 「설치와 실패 처리」, `docs/backend/packages.md` 의 「최상위 패키지의 층 순서」

- 결정: `docs/adr/ADR-083-커넥터는-사용자가-한-번-연결하고-자기-에이전트에-여럿-붙여-그-에이전트가-도구를-직접-부른다.md`
- 지금 흐름: `docs/backend/connector-install.md` 의 「설치와 실패 처리」, `docs/connectors.md` 의 「Control Plane API」
- 지금 서비스: `backend/src/main/java/com/bifos/assistant/connector/application/ConnectorConnectionService.java`
  - `register` 는 `call(manifest, manifest.verifyTool(), accepted)` 를 트랜잭션 밖에서 부른 뒤 `apply` 를 트랜잭션 안에서 부른다. `apply` 는 연결이 없으면 `lifecycle.createConnectorAgent(user, manifest.title())` 로 에이전트를 만든다
  - `disconnect`, `check`, `confirmApplied`, `resyncedUsable`, `readState`, `listForAdmin`, `catalog`, `read`, `options` 가 있다
  - 실패는 `warn(step, connectorId, ex)` 로 단계와 예외 종류만 남기고 `ConnectorOperationFailure` 로 끝낸다
- 대시보드 호출: `backend/src/main/java/com/bifos/assistant/hermes/HermesConnectorClient.java`, 구현 `hermes/HttpHermesConnectorClient.java`. `putConnector(profile, connectorId, enabled)`, `readConnector`, `probe`, `putEnv`, `deleteEnv`, `call`, `execute`
- 대시보드 경로: `plan91-connector-binding-hermes` 가 연 것이다. 계약은 `docs/backend/connector-install.md` 의 「대시보드 plugin 계약」 이 갖는다
- phase 01 이 만든 것: `ConnectorBinding`, `BindingStatus`, `ConnectorBindingRepository`, `ConnectorConnection.vaultStored()` 와 `markVaultStored()`
- 승인 줄 정리: `connector/application/ConnectorActionService.java` 의 `rejectPendingFor(ConnectorConnection connection, Instant now)`. `EXECUTING` 이 있으면 `CONNECTOR_ACTION_EXECUTING` 으로 거절한다
- 층 순서: `docs/backend/packages.md` 의 「최상위 패키지의 층 순서」. `agent` 는 5, `connector` 는 16 이다. `agent` 가 바인딩을 알아야 하면 `agent` 에 port 를 두고 `connector` 가 구현한다
- 스킬: `docs/backend/skill.md`. 올린 스킬 이름은 `agent/application/ProfileSkillFiles.java` 가 읽는다. 스킬을 저장하면 `skills` toolset 을 함께 켠다

## 의도 메모

- 값의 원본은 보관 파일이다. 바인딩마다 profile `.env` 에 복사한다. 값을 바꾸면 붙은 모든 profile 을 다시 쓴다(ADR-083 의 「값 교체와 연결 해제」)
- 옛 커넥터 에이전트의 바인딩은 옛 방식(`bind` 칸 없는 설치와 `putEnv`)으로 다시 쓴다. 그 에이전트가 지워질 때까지 지금처럼 돌아야 한다
- 옛 연결은 보관 파일이 없다(`vault_stored` 거짓). 연결 확인이 옛 profile 의 값을 보관 파일로 옮긴다. 옮기지 못하면 그 연결은 다른 에이전트에 붙지 못한다
- 관리자는 남의 에이전트에 붙이거나 떼지 못한다. 반영 완료만 누른다
- 붙인 뒤에는 늘 재시작 대기다. 그래서 바인딩은 붙인 직후 `PENDING` 이고 관리자 반영 완료가 `READY` 로 바꾼다
- 외부 호출을 트랜잭션 안에서 하는 지금 방식(`apply`)을 그대로 따른다. 확인 도구 호출만 트랜잭션 밖이다

## 작업 항목

### 1. `HermesConnectorClient` 와 `HttpHermesConnectorClient`

더하는 메서드다. 응답 모양이 틀리면 지금처럼 원문 없는 `IllegalStateException` 을 던진다.

- `void putVault(String vault, String connectorId, Map<String, String> values)`
- `boolean deleteVault(String vault)`
- `void importVault(String vault, String connectorId, String profile)`
- `CallResult callWithVault(String connectorId, String tool, String vault)`
- `InstallResult bindConnector(String profile, String connectorId, String vault)` 와 `InstallResult unbindConnector(String profile, String connectorId)`. 앞의 것은 `PUT /api/connectors` 에 `bind: {vault}` 를 싣는다. 뒤의 것은 지금 `putConnector(profile, id, false)` 와 같은 요청이다
- `ConnectorState` 에 `mode`(`"bind"` 나 `"isolated"`, 없으면 `"isolated"`)를 더한다
- 대시보드가 409 로 답하면 `ConnectorInstallConflict`, 401 이면 `ConnectorProfileRejected` 를 던진다. 둘 다 `hermes` 패키지의 새 예외이고 메시지에 응답 본문을 싣지 않는다
- 보관 파일 이름은 연결 id 로 만든다. `ConnectorConnection` 에 `String vault()` 를 두고 `"c" + id` 를 돌려준다
- `hermes/dto/ConnectorManifest.java` 에 `List<String> skills` 를 더하고 `HttpHermesConnectorClient` 의 카탈로그 읽기가 칸 `skills` 를 읽는다. 없으면 빈 목록이다(옛 대시보드 plugin)

### 2. 연결 서비스: `ConnectorConnectionService`

사용자와 커넥터 단위로 바꾼다. 에이전트를 만들지 않는다. `AgentLifecycleService` 의존을 지운다.

- `register(user, connectorId, values)`: 확인 도구 호출은 지금처럼 트랜잭션 밖이다. 트랜잭션 안에서 사용자 행을 잠그고 연결을 찾거나 `ConnectorConnection.pending(userId, connectorId, now)` 로 만든다. `approvals.rejectPendingFor(connection, now)` 뒤 `putVault(connection.vault(), id, accepted)` 를 부르고 `connection.connected(stored, now)` 로 `READY` 와 `vault_stored` 를 둔다. 이어서 그 연결의 바인딩마다 아래 3의 `reinstall` 을 부른다. 바인딩 하나가 실패해도 연결은 저장하고 그 바인딩만 `PENDING` 이다
- `disconnect(user, connectorId)`: 잠금, 승인 줄 정리, 바인딩마다 떼기(아래 3의 `detach`), `deleteVault`, `connection.disconnected(now)`. 옛 바인딩은 옛 방식으로 env 를 지우고 설치를 끈다. 실패하면 지금처럼 `CONNECTOR_OPERATION_FAILED`
- `check(user, connectorId)`: `register` 처럼 트랜잭션을 나눈다. `@Transactional` 을 메서드에 붙이지 않는다
  1. 트랜잭션 안: 잠금 뒤 `vault_stored` 가 거짓이고 옛 커넥터 에이전트의 바인딩이 있으면 `importVault(vault, id, 그 profile)` 후 `markVaultStored()`
  2. 트랜잭션 밖: `vault_stored` 가 참이면 `callWithVault(id, verify.tool, vault)` 로 확인한다. 최대 10초가 걸려 그동안 DB 연결과 사용자 잠금을 쥐지 않는다
  3. 트랜잭션 안: 다시 잠그고 확인 결과를 적는다. 바인딩의 `mcp_server` 가 비었으면 manifest 의 서버 이름으로 채운다. 실패면 연결을 `PENDING` 으로 두고 공통 어휘의 오류로 끝낸다. 옛 연결은 1 이 실패하면 확인 호출 없이 지금 상태를 둔다. 이어서 그 연결의 바인딩마다 아래 3의 `resync` 를 부른다
- `confirmApplied` 는 이 서비스에서 지운다. 바인딩 서비스로 옮긴다. 이것을 부르던 `connector/presentation/ConnectorConnectionAdminController.java` 의 `POST /{id}/{userId}/confirm` 을 이 phase 에서 `POST /api/v1/admin/agents/{code}/connections/{connectorId}/confirm` 으로 바꾼다. 이 컨트롤러는 `@RequestMapping("/api/v1/admin/connections")` 아래에 있으므로 반영 완료 메서드는 새 컨트롤러 `connector/presentation/AdminAgentConnectionController.java` 로 옮긴다
- `catalog`, `read` 의 결과에 그 연결이 붙은 에이전트 목록(`agentCode`, `agentName`, 바인딩 `status`, `restartRequired`)을 더한다. 카탈로그에서 빠진 연결의 이름은 지금처럼 쓸 이름이 없으므로 커넥터 id 를 쓴다
- 응답 모양이 함께 바뀐다. `connector/application/model/ConnectionSnapshot.java` 의 `agentCode` 와 `restartRequired` 를 `bindings` 로 바꾸고, 그것을 쓰는 `connector/presentation/ConnectionDtos.java` 의 상태 응답과 관리자 응답을 같은 phase 에서 고친다. 웹은 phase 05 가 맞춘다
- `listForAdmin` 은 바인딩 단위로 바꾼다: 같은 그룹 사용자의 바인딩마다 `connectorId`, `userId`, `displayName`, `agentCode`, `status`, `restartRequired`, `undeclaredTools`
- `ConnectorConnection` 에 `static pending(Long userId, String connectorId, Instant now)` 와 `connected(ConnectionFields fields, Instant now)` 를 더한다. 에이전트를 받는 옛 팩터리 `pending(Long, String, Agent, Instant)` 는 phase 03 이 지운다. 연결의 메서드가 에이전트를 켜고 끄는 일(`disableAgent`)은 이 phase 에서 지운다. 그 동작을 단언하던 `ConnectorConnectionTest` 를 같은 phase 에서 고친다. 옛 커넥터 에이전트를 켜고 끄는 일은 바인딩이 한다(phase 01)

### 3. 바인딩 서비스: `connector/application/ConnectorBindingService.java`(신규)

- 에이전트 찾기와 권한은 세 메서드(`listForAgent`, `bind`, `unbind`) 모두 `AgentLifecycleService.requireManageable` 과 같은 규칙이다. 요청자가 읽을 수 없는 에이전트는 `AGENT_NOT_FOUND`, 읽을 수 있지만 주인이 아니면 `FORBIDDEN` 이다. 관리자도 주인이 아니면 `FORBIDDEN` 이다
- `AgentConnectionsView listForAgent(CurrentUser user, String agentCode)`: 주인만. `AgentConnectionsView` 는 `record AgentConnectionsView(List<AgentConnectionView> connections, String blockedReason)` 이다. 그 사용자의 `DISCONNECTED` 가 아닌 연결마다 `AgentConnectionView`(`connectorId`, `title`, `connectionStatus`, `bound`, `status`, `restartRequired`, `toolCount`, `skills`)를 낸다. 에이전트가 붙일 수 없는 상태면 `blockedReason` 에 `AGENT_NOT_PRIVATE` 나 `LEGACY_AGENT` 를 둔다
- `AgentConnectionView bind(CurrentUser user, String agentCode, String connectorId)`: 트랜잭션 안에서
  1. 사용자 행 잠금, 이어서 에이전트 행 잠금(`AgentRepository.findByCodeForUpdate`). 공개 범위 변경(`AgentLifecycleService.changeVisibility`)과 관리자 수정(`AgentAdminService.update`)이 같은 에이전트 행을 잠그므로, 붙이기와 그룹 공개나 주인 변경이 동시에 와도 한쪽이 다른 쪽의 커밋을 보고 판정한다
  2. 에이전트는 주인이 요청자이고, 지워지지 않았고, `PRIVATE` 이고, `connectorManaged()` 가 거짓이어야 한다. 아니면 위 규칙의 `AGENT_NOT_FOUND` 나 `FORBIDDEN`, `AGENT_CONNECTIONS_REQUIRE_PRIVATE`(새 코드, 409), `VALIDATION_FAILED`
  3. 연결은 `READY` 이고 `vaultStored()` 여야 한다. 아니면 `CONNECTOR_NOT_CONNECTED`(새 코드, 409)
  4. 이미 붙어 있으면 지금 상태를 돌려준다
  5. manifest 의 스킬 이름이 그 에이전트의 올린 스킬 이름과 겹치면 `SKILL_NAME_TAKEN`
  6. `ConnectorBinding.pending(agent, connection, manifest.mcpServer(), now)` 저장, `beginInstall`, `bindConnector(profile, id, vault)` 뒤 `installed(result.restartRequired() || result.pluginUpdated(), now)`
  7. `skills` toolset 은 켜지 않는다. 켜면 `AgentToolService` 를 불러야 하는데 그 서비스가 phase 03 에서 이 패키지의 port 를 쓰게 되어 bean 이 서로를 기다린다. 화면이 「지침을 쓰려면 스킬 도구를 켜세요」 를 보인다(phase 05)
  8. `ConnectorInstallConflict` 는 `CONNECTOR_BIND_CONFLICT`(새 코드, 409), `ConnectorProfileRejected` 는 `CONNECTOR_PROFILE_NOT_READY`(새 코드, 409)로 끝내고 바인딩 행을 지운다. 그 밖의 실패는 바인딩을 `PENDING` 으로 남기고 `CONNECTOR_OPERATION_FAILED`
- `void unbind(CurrentUser user, String agentCode, String connectorId)`: 주인만. `bind` 와 같은 순서로 사용자 행과 에이전트 행을 잠근다. 옛 커넥터 에이전트의 바인딩은 떼지 않는다(그 에이전트를 지운다). 그 에이전트와 연결의 `EXECUTING` 승인 줄이 있으면 `CONNECTOR_ACTION_EXECUTING`. `PENDING` 승인 줄은 `connection_changed` 로 끝낸다. 이 일을 할 `ConnectorActionService.rejectPendingFor(ConnectorConnection connection, Long agentId, Instant now)` 를 이 phase 에서 만든다. 그 에이전트가 판정한 줄만 고른다. 상시 허락은 건드리지 않는다. 허락은 사용자와 커넥터에 묶여 있어 다른 에이전트의 바인딩에도 걸리기 때문이다. `unbindConnector(profile, id)` 뒤 행을 지운다
- `AgentConnectionView confirmApplied(CurrentUser admin, String agentCode, String connectorId)`: 관리자이고 그 에이전트 주인이 같은 그룹이어야 한다. 재시작 대기를 풀고 `resync` 로 `READY` 를 판정한다. 아니면 `PENDING` 과 `CONNECTOR_OPERATION_FAILED`
- 내부 `reinstall(binding)`: 일반 바인딩은 `bindConnector`, 옛 바인딩은 지금 `apply` 의 env 쓰기와 `putConnector(profile, id, true)`. 결과의 재시작 필요를 누적하고 `PENDING`
- 내부 `detach(binding)`: 일반 바인딩은 `unbindConnector` 뒤 행 삭제. 옛 바인딩은 env 삭제와 `putConnector(profile, id, false)` 뒤 행 삭제와 에이전트 끄기
- 내부 `resync(binding)`: 지금 `resyncedUsable` 을 옮긴다. 일반 바인딩은 `bindConnector` 를 다시 보내고 `readConnector` 의 `enabled`, `configured`, `policyHook`, `mode == "bind"` 와 `probe` 를 본다. 켜진 내장 도구가 manifest 의 `toolsets` 와 같은지는 보지 않는다. 옛 바인딩은 지금 판정 그대로다(사진 받기 포함). 재시작 대기인 바인딩은 사용자의 연결 확인에서 설치를 다시 보내지 않는다

### 4. `agent` 의 port 와 등록 정리

port 를 읽기와 떼기로 나눈다. 읽기 port 를 쓰는 `AgentToolService` 를 바인딩 서비스가 부르지 않더라도, 떼기를 맡은 서비스와 읽기를 맡은 구현을 한 bean 에 두면 의존이 엉키기 쉽다.

- `backend/src/main/java/com/bifos/assistant/agent/application/AgentConnectorBindings.java`(신규 인터페이스, 읽기): `boolean hasBindings(Long agentId)`, `Set<String> connectorServers(Long agentId)`, `Set<String> connectorToolPrefixes(Long agentId)`. 구현은 `connector/application/ConnectorBindingLookup.java`(신규)이고 `ConnectorBindingRepository` 만 쓴다. 서버 이름은 바인딩 행의 `mcp_server` 에서 읽고 카탈로그를 부르지 않는다. 대시보드가 응답하지 않아도 대화와 도구 저장이 이 조회 때문에 실패하지 않는다. `mcp_server` 가 빈 바인딩은 옛 커넥터 에이전트의 것뿐이고, 그 에이전트는 실행 기록을 모두 가리고 도구 저장과 살펴보기를 하지 않으므로 이름이 없어도 된다
- `backend/src/main/java/com/bifos/assistant/agent/application/AgentConnectorDetacher.java`(신규 인터페이스, 떼기): `void detachAll(Agent agent)`. 구현은 `ConnectorBindingService` 다
- phase 03 과 04 가 두 port 를 부른다
- `AgentLifecycleService.createConnectorAgent` 를 지운다. 부르는 곳이 사라진다
- `ConnectorBindingService.detachAll` 은 바인딩마다 에이전트 단위 `rejectPendingFor` 를 부르고, 옛 커넥터 에이전트의 바인딩이 그 연결의 유일한 값 원본이면(`vault_stored` 거짓) 먼저 `importVault` 를 하고, 실패하면 `CONNECTOR_OPERATION_FAILED` 로 끝낸다. 그 뒤 `detach` 로 Hermes 쪽을 떼고 행을 지운다. 뒤의 삭제가 실패해 트랜잭션이 되돌려지면 행은 남고 Hermes 쪽은 떼어진 상태다. 다음 연결 확인의 `resync` 가 `configured` 거짓을 보고 그 바인딩을 `PENDING` 으로 둔다. 사용자는 다시 붙이면 된다

### 5. 시험

- `backend/src/test/java/com/bifos/assistant/connector/ConnectorConnectionServiceTest.java`(수정): 등록이 에이전트를 만들지 않고 보관 파일을 쓰며 `READY` 다. 값 교체가 붙은 바인딩마다 다시 설치하고 그 바인딩이 재시작 대기 `PENDING` 이다. 해제가 바인딩을 모두 떼고 보관 파일을 지운다. 옛 연결의 확인이 보관 파일로 옮긴 뒤 확인 도구를 부른다. 응답과 로그 문자열에 비밀 칸의 값이 없다
- `backend/src/test/java/com/bifos/assistant/connector/ConnectorBindingServiceTest.java`(신규): 남의 비공개 에이전트는 `AGENT_NOT_FOUND`, 읽을 수 있는 남의 에이전트와 관리자는 `FORBIDDEN` 이다. 경우마다 코드 하나를 단언한다. 붙이기가 에이전트 행 잠금을 쥔 동안 공개 범위 변경이 기다렸다가 `AGENT_CONNECTIONS_REQUIRE_PRIVATE` 로 거절되는 차례를 재현한다(본보기는 에이전트 행 잠금 아래의 동시 저장을 `CountDownLatch` 로 재현하는 `SkillServiceTest`). 그룹 공개 에이전트, 옛 커넥터 에이전트, 연결되지 않은 연결은 각 오류 코드다. 붙이면 `bindConnector` 를 한 번 부르고 바인딩이 `PENDING` 이며 재시작이 필요하다. 대시보드 409 와 401 은 새 오류 코드이고 행이 남지 않는다. 반영 완료가 `READY` 로 바꾼다. 떼면 행이 지워지고 `unbindConnector` 를 부른다. 스킬 이름이 겹치면 `SKILL_NAME_TAKEN`
- `backend/src/test/java/com/bifos/assistant/hermes/HttpHermesConnectorClientTest.java`(수정): 새 메서드의 요청 본문과 409, 401 대응, 카탈로그의 `skills`
- `backend/src/test/java/com/bifos/assistant/connector/ConnectorConnectionTest.java`(수정): 연결의 메서드가 에이전트를 건드리지 않는다
- `backend/src/test/java/com/bifos/assistant/connector/ConnectorConnectionControllerTest.java`(수정): 상태 응답에 `bindings` 가 있고 `agentCode` 가 없다. 응답 본문에 비밀 원문과 env 이름이 없다. 관리자 반영 완료가 새 경로로 된다
- `backend/src/test/java/com/bifos/assistant/connector/ConnectorBindingLookupTest.java`(신규): 붙은 연결의 서버 이름과 접두사를 바인딩 행으로 만들고 카탈로그를 부르지 않는다

## 검증

```bash
cd backend && ./gradlew test --tests '*ConnectorConnectionServiceTest' --tests '*ConnectorBindingServiceTest' --tests '*HttpHermesConnectorClientTest' --tests '*ConnectorConnectionTest' --tests '*ConnectorConnectionControllerTest' --tests '*ConnectorBindingLookupTest'
cd backend && ./gradlew test
scripts/quality.sh check
```

모두 종료 코드 0 이어야 한다.

## 변경 파일

| 파일 | 변경 |
| --- | --- |
| `backend/src/main/java/com/bifos/assistant/hermes/HermesConnectorClient.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/hermes/HttpHermesConnectorClient.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/hermes/ConnectorInstallConflict.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/hermes/ConnectorProfileRejected.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/connector/application/ConnectorConnectionService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/connector/application/ConnectorBindingService.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/connector/application/model/AgentConnectionView.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/connector/application/model/AgentConnectionsView.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/connector/application/ConnectorBindingLookup.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/agent/application/AgentConnectorDetacher.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/hermes/dto/ConnectorManifest.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/connector/presentation/ConnectionDtos.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/connector/presentation/ConnectorConnectionAdminController.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/connector/presentation/AdminAgentConnectionController.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/connector/ConnectorConnectionTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/connector/ConnectorConnectionControllerTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/connector/ConnectorBindingLookupTest.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/connector/application/model/ConnectionSnapshot.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/connector/application/model/AdminConnectionSnapshot.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/connector/application/model/ConnectorSummary.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/connector/application/ConnectorActionService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/connector/domain/ConnectorConnection.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/agent/application/AgentConnectorBindings.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/agent/application/AgentLifecycleService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/shared/error/ErrorCode.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/connector/ConnectorConnectionServiceTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/connector/ConnectorBindingServiceTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/hermes/HttpHermesConnectorClientTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/agent/AgentLifecycleServiceTest.java` | 수정 |
