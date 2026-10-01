# Phase 04. Control Plane 이 도구 목록을 쓰지 않고 설치에 맡기며, 옛 목록의 연결을 다시 설치한다

**Execution profile**: deep

## 목표

커넥터 연결의 등록과 확인이 Control Plane MCP 를 도구 목록에 쓰지 않게 한다. 이전 판이 설치한 연결은 연결 확인이나 관리자의 반영 완료가 설치를 다시 써서 새 목록으로 바꾼다.

**범위 외**: 대시보드 plugin 의 목록 쓰기는 앞 phase 가 끝냈다. Memory 와 MCP 호출 거절도 앞 phase 가 끝냈다.

## 컨텍스트

- 대상은 `backend/src/main/java/com/bifos/assistant/connector/application/ConnectorConnectionService.java` 다. 고치기 전에 `apply`, `check`, `confirmApplied`, `usable`, `narrowedEnabled` 를 읽는다
- 지금 `apply` 는 env 를 쓴 뒤 `toolsets.writeApiServer(profile, List.of(AgentToolPolicy.CONTROL_PLANE_MCP))` 를 부르고 설치한다. `narrowedEnabled` 는 켜진 내장 도구가 보이면 `writeApiServer(profile, List.of(CONTROL_PLANE_MCP, mcpServer))` 를 부른다
- 대시보드 plugin 의 설치(`HermesConnectorClient.putConnector(profile, id, true)`)는 API 도구 목록을 커넥터 서버 이름만으로 다시 쓰고 `mcp_servers` 의 Control Plane MCP 등록을 지운다. 반환값은 `restart_required` 다. 이미 설치한 것을 다시 쓰면 바뀐 것이 없어도 `restart_required` 가 참이다
- `readConnector` 의 `configured` 는 서버 정의가 소유 기록과 같고 목록이 설치한 커넥터의 서버 이름과 정확히 같고 Control Plane MCP 등록이 없을 때만 참이다. 내장 도구나 Control Plane MCP 가 목록에 남은 옛 모양은 `configured: false` 다
- `PENDING` 인 연결의 에이전트는 꺼져 있다. `connection.pending(now())` 과 `connection.ready(now())` 가 에이전트 활성화를 함께 다룬다(`backend/src/main/java/com/bifos/assistant/connector/domain/ConnectorConnection.java`)

**근거 문서**: `docs/connectors.md` 의 「설치와 실패 처리」 와 「대시보드 plugin 계약」, `docs/flow.md` 의 「커넥터 연결」, `docs/adr/ADR-044-커넥터-에이전트는-자기-mcp-서버만-받고-memory-와-control-plane-도구를-받지-않는다.md`

## 의도 메모

- 이미 설치한 연결을 새 목록으로 바꾸려고 사용자에게 토큰을 다시 받지 않는다. 칸 값은 profile `.env` 에 그대로 있다
- 다시 설치는 `configured` 가 거짓일 때만 한다. `configured` 가 참인데 다시 쓰면 바뀌는 것 없이 `restart_required` 만 서고 관리자 반영 완료가 끝나지 않는다
- 켜진 내장 도구가 보이는 것은 `configured` 가 참인 뒤에는 목록이 아니라 다른 설정에서 온 것이다. 다시 쓰지 않고 `PENDING` 으로 둔다

## 작업 항목

### 1. `apply` 가 도구 목록을 쓰지 않는다

- `STEP_TOOLSET` 단계와 `toolsets.writeApiServer(...)` 호출을 지운다. env 뒤에 바로 설치한다. 상수 `STEP_TOOLSET` 도 지운다

### 2. `usable` 과 `narrowedEnabled`

- `narrowedEnabled` 를 지운다. `usable` 은 `probe.ok() && !probe.tools().isEmpty() && toolsets.readEnabled(agent.apiBaseUrl(), agent.hermesProfile()).isEmpty()` 로 판정한다
- `HermesToolsetClient` 필드는 `readEnabled` 에 계속 쓴다. `AgentToolPolicy` import 가 쓰이지 않으면 지운다

### 3. `check` 와 `confirmApplied` 의 다시 설치

- 새 private 메서드 `reinstall(ConnectorConnection connection)`: `connector.putConnector(profile, connectorId, true)` 를 부르고 `connection.markRestartRequired(...)` 에 그 반환값을 넣는다
- `check`: `connection.desiredEnabled()` 이고 `state.enabled()` 이고 `!state.configured()` 이고 `!connection.restartRequired()` 이고 카탈로그에 그 커넥터가 있으면(`findManifest` 가 비지 않으면) `reinstall` 을 부른다. 그 뒤 `connection.restartRequired()` 이면 `PENDING` 으로 저장하고 돌려준다. 아니면 `readConnector` 를 다시 읽어 지금의 판정을 잇는다. `reinstall` 이 예외를 내면 `warn(STEP_INSTALL, ...)` 과 `connection.pending(now())` 뒤 `ConnectorOperationFailure` 다. 카탈로그에 없으면 다시 쓰지 않고 지금처럼 `PENDING` 이다
- `confirmApplied`: `connection.desiredEnabled()` 이고 `state.enabled() && !state.configured()` 이고 카탈로그에 있으면 `reinstall` 을 부른다. 반환값이 참이면 `connection.pending(now())` 뒤 `ConnectorOperationFailure` 를 던진다(관리자가 gateway 를 재시작한 뒤 다시 누른다. `noRollbackFor` 라 `restart_required` 는 저장된다). 거짓이면 `readConnector` 를 다시 읽어 지금의 판정을 잇는다. 이미 `restartRequired` 인 연결도 관리자가 재시작한 뒤 누른 것이므로 `configured` 가 거짓이면 다시 쓴다. 그 응답이 다시 `restart_required` 이면 한 번 더 재시작이 필요하다는 뜻이고 그대로 실패로 답한다
- 메서드가 60줄을 넘으면 private 메서드로 나눈다(`MethodLength` 경고)

### 4. 이 phase 를 검증하는 `ConnectorConnectionServiceTest`

`backend/src/test/java/com/bifos/assistant/connector/ConnectorConnectionServiceTest.java`

- `writeApiServer` 를 기대하는 검사(156, 745, 785, 814 줄 부근)를 고친다. 등록 순서는 확인 도구, env, 설치다. `toolsets.writeApiServer` 는 어느 경로에서도 불리지 않는다(`verify(toolsets, never()).writeApiServer(any(), any())`). 켜진 내장 도구가 보이는 검사(741 줄 부근)는 `configured` 가 참이고 내장 도구가 남으면 다시 쓰지 않고 `PENDING` 임을 본다
- 새 검사 `checkReinstallsWhenInstallIsNotConfigured`: `readConnector` 가 처음에 `enabled=true, configured=false`, 다시 읽을 때 `configured=true` 이고 `putConnector` 가 false 를 돌려주면 `READY` 다
- 새 검사 `checkStaysPendingWhenReinstallNeedsRestart`: `putConnector` 가 true 를 돌려주면 `PENDING` 이고 `restartRequired` 가 참이며 probe 를 부르지 않는다
- 새 검사 `checkDoesNotReinstallConfiguredConnection`: `configured=true` 이면 `putConnector` 가 불리지 않는다
- 새 검사 `confirmAppliedReinstallsOldAllowlistAndWaitsForRestart`: 관리자 반영 완료가 `putConnector(profile, id, true)` 를 부르고 `ConnectorOperationFailure` 로 끝나며 저장된 행의 `restartRequired` 가 참이다. 재시작 뒤(대역이 `configured=true` 를 돌려줌) 다시 누르면 `READY` 다

### 5. e2e 대역과 시나리오

- `test/e2e/fake-hermes.ts`: `PUT /api/connectors` 가 설치 때 그 profile 의 도구 목록을 `[DEMO_CONNECTOR 의 mcp_server]` 로, 제거 때 `["no_mcp"]` 로 기록한다. `GET /api/connectors` 의 `configured` 는 지금 조건에 더해 기록한 목록이 커넥터 서버 하나일 때만 참이다. 대역이 받은 호출을 기록하는 방식은 지금 것을 따른다
- `test/e2e/scenarios/connector.ts`: 「통과한 토큰으로 등록하면 PENDING 이고 대역이 확인, env, 도구 목록, 설치 순으로 받는다」 단계를 확인, env, 설치 순으로 고치고, 등록 동안 `PUT /api/config` 가 오지 않았음을 본다. 연결 확인 뒤 대역의 목록이 커넥터 서버 하나임을 본다
- `test/e2e/scenarios/delegation.ts` 는 고치지 않는다

## 검증

```bash
# cwd: backend/
./gradlew test --tests '*ConnectorConnectionServiceTest' --tests '*ConnectorConnectionControllerTest'
./gradlew test
```

```bash
# cwd: 저장소 root
node test/e2e/run.ts
! grep -n "writeApiServer" backend/src/main/java/com/bifos/assistant/connector/application/ConnectorConnectionService.java
scripts/check-public-safe.sh
```

`node test/e2e/run.ts` 는 `./gradlew test` 뒤에 돌린다. 모두 종료 코드 0 이어야 한다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/connector/application/ConnectorConnectionService.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/connector/ConnectorConnectionServiceTest.java` | 수정 |
| `test/e2e/fake-hermes.ts` | 수정 |
| `test/e2e/scenarios/connector.ts` | 수정 |
