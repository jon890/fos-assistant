# Phase 03. Control Plane 이 도구 정책과 hook 상태를 읽는다

**Execution profile**: standard

## 목표

Control Plane 이 카탈로그의 `schema` 와 `tools` 를 읽어 하한을 다시 검사하고, `policy_hook` 이 참이 아닌 연결을 `READY` 로 두지 않고, 선언하지 않은 도구 수를 센다.
카탈로그 API 가 도구와 위험도를 낸다.

**범위 외**: 판정과 `connector_action`(phase 04), 화면(phase 06).

## 컨텍스트

- 카탈로그 record 는 `backend/src/main/java/com/bifos/assistant/hermes/dto/ConnectorManifest.java` 다. 칸은 `id, title, description, fields, verifyTool, mcpServer, toolsets, attachments`. JSON 은 `hermes/HttpHermesConnectorClient.java` 가 읽는다. `toolsets` 는 없으면 빈 목록, `attachments` 는 없으면 false 로 읽는다
- 설치 상태는 `HermesConnectorClient.ConnectorState(String profile, boolean enabled, boolean configured, boolean restartRequired)` 다. `readConnector(profile, connectorId)` 가 `GET /api/connectors?profile=` 응답에서 만든다
- `connector/application/ConnectorConnectionService.java` 의 `readCatalog()` 가 toolset 검사를 통과한 manifest 만 남긴다. `check`, `confirmApplied` 가 `ConnectorState` 와 `usable(connection)` 으로 `READY` 를 정한다. `usable` 은 `connector.probe(profile, manifest.mcpServer())` 의 `ProbeResult(boolean ok, List<String> tools)` 를 본다
- 응답 모델은 `connector/application/model/` 의 `ConnectionSnapshot`, `AdminConnectionSnapshot`, `ConnectorSummary`, DTO 는 `connector/presentation/ConnectionDtos.java` 다
- 엔티티는 `connector/domain/ConnectorConnection.java`(`@Getter @Accessors(fluent = true)`), 가장 큰 마이그레이션은 `V39__connector_attachments.sql` 이다. 테스트 DB 는 Flyway 를 끄고 엔티티로 표를 만든다
- e2e 대역 `test/e2e/fake-hermes.ts` 의 `DEMO_CONNECTOR` 와 `handleDashboard` 가 카탈로그와 `GET /api/connectors` 를 흉내 낸다. 브라우저 검사도 같은 대역을 쓴다
- 서비스 테스트는 `backend/src/test/java/com/bifos/assistant/connector/ConnectorConnectionServiceTest.java` 다. `@MockitoBean HermesConnectorClient` 에 `DEMO_MANIFEST`, `PIN_MANIFEST` 를 물린다. 클라이언트 테스트는 `hermes/HttpHermesConnectorClientTest.java`(`MockRestServiceServer`)다

**근거 문서**: `docs/connectors.md` 의 「도구 정책」, 「hook 이 켜져 있는지」, 「선언하지 않은 도구」, 「Control Plane API」, `docs/data-schema.md` 의 「connector_connection」

## 의도 메모

- 옛 대시보드 plugin 은 `schema`, `tools`, `policy_hook` 을 내지 않는다. `schema` 가 없으면 1, `tools` 가 없으면 빈 map, `policy_hook` 이 없으면 false 로 읽는다. false 는 `READY` 를 막는다. 배포 순서(plugin 먼저)를 지키면 그 구간이 없다
- 하한 검사를 Control Plane 이 한 번 더 한다. 대시보드 plugin 이 옛 판이거나 고쳐졌어도 느슨한 정책으로 판정하지 않는다
- 선언하지 않은 도구가 있어도 `READY` 를 막지 않는다. 그 도구의 호출만 거절된다

## 작업 항목

### 1. enum 과 도구 선언 record

`backend/src/main/java/com/bifos/assistant/connector/domain/type/` 에 둔다.

- `ToolRisk`: `READ, SENSITIVE, WRITE, DESTRUCTIVE, FINANCIAL`. 메서드 `ToolApproval defaultApproval()`, `ToolApproval floor()`. 값은 `docs/connectors.md` 의 위험도 표와 같다
- `ToolApproval`: `NONE, REQUIRED, ALWAYS`(엄격한 순서). 메서드 `boolean looserThan(ToolApproval other)`, `static ToolApproval fromWord(String)`(`none`, `required`, `always`. 모르는 글은 null)

`backend/src/main/java/com/bifos/assistant/hermes/dto/ConnectorTool.java`: `record ConnectorTool(String name, String risk, String approval, String title)`. `hermes` 는 `connector` 를 import 하지 않으므로 글자 그대로 담는다.

### 2. `ConnectorManifest` 와 `HttpHermesConnectorClient`

- `ConnectorManifest` 에 `int schema` 와 `List<ConnectorTool> tools` 를 더한다
- `HttpHermesConnectorClient` 가 `schema`(없으면 1)와 `tools`(없으면 빈 목록. JSON object 의 키가 이름, 값의 `risk`, `approval`, `title`)를 읽는다. `title` 이 없으면 null
- `ConnectorState` 에 `boolean policyHook` 을 더한다. 응답 최상위의 `policy_hook` 이 JSON true 일 때만 참이다

### 3. `connector/application/ConnectorToolPolicies.java`

`@NoArgsConstructor(access = AccessLevel.PRIVATE)` 인 package-private 정적 클래스다.

- `static boolean valid(ConnectorManifest manifest)`: `schema` 가 1 이나 2 이고, 도구마다 `risk` 가 `ToolRisk` 이고 `approval` 이 `ToolApproval` 이고 하한보다 느슨하지 않다. `schema: 2` 는 `tools` 가 비지 않고 `verifyTool` 과 칸의 `options.tool` 이 `READ`, `NONE` 으로 들어 있다
- `static Optional<ToolPolicy> find(ConnectorManifest manifest, String toolName)`: 이름이 같은 선언. `toolName` 이 null 이면 빈 값

`connector/domain/ToolPolicy.java`: `record ToolPolicy(ToolRisk risk, ToolApproval approval, String title)`.

`ConnectorConnectionService.readCatalog()` 의 거르기에 `ConnectorToolPolicies.valid(manifest)` 를 더한다.

### 4. `policy_hook` 과 선언하지 않은 도구

- `check` 와 `confirmApplied` 에서 `state.policyHook()` 이 거짓이면 `usable` 을 부르지 않고 `pending` 이다. `desiredEnabled` 가 참인 갈래에만 건다. 해제 확인 갈래는 그대로 둔다
- `usable(connection)` 안에서 probe 뒤에 `schema == 2` 이면 `probe.tools()` 가운데 manifest `tools` 에 이름이 없는 수를 세어 `connection.recordUndeclaredTools(count)` 로 적는다. `schema == 1` 이면 0 이다
- `ConnectorConnection` 에 `@Column(name = "undeclared_tools", nullable = false) private int undeclaredTools` 와 `recordUndeclaredTools(int)` 를 더한다. `disconnected`, `confirmDisconnected`, `beginRegister` 는 0 으로 되돌린다
- `backend/src/main/resources/db/migration/V41__connector_undeclared_tools.sql`: `ALTER TABLE connector_connection ADD COLUMN undeclared_tools INT NOT NULL DEFAULT 0;` 첫머리에 한국어 주석으로 목적과 ADR-048 을 적는다

### 5. 응답

- `ConnectionSnapshot` 과 `AdminConnectionSnapshot` 에 `int undeclaredTools` 를 더하고 `ConnectionDtos` 의 `ConnectionView`, `AdminConnectionView` 가 `undeclaredTools` 로 낸다
- `ConnectorSummary` 에 `List<ConnectorToolSummary> tools` 를 더한다. `connector/application/model/ConnectorToolSummary.java`: `record ConnectorToolSummary(String name, String title, ToolRisk risk, ToolApproval approval)`. `schema: 1` 은 빈 목록이다. 카탈로그에서 빠진 커넥터(`available: false`)도 빈 목록이다
- `ConnectionDtos.ConnectorView` 가 `tools` 를 `[{name, title, risk, approval}]` 로 낸다. `risk` 는 `READ` 같은 enum 이름, `approval` 은 `NONE` 같은 enum 이름이다

### 6. 대역

`test/e2e/fake-hermes.ts`:

- `DEMO_CONNECTOR` 에 `schema: 2`, `toolsets: []`, `attachments: false`, `tools: { list_scopes: { risk: "READ", approval: "none" }, write_note: { risk: "WRITE", approval: "required", title: "메모 쓰기" }, purge_notes: { risk: "DESTRUCTIVE", approval: "always" } }` 를 더한다
- `GET /api/connectors?profile=` 응답에 `policy_hook` 을 더한다. 값은 대역의 상태 `policyHookOff: Set<string>` 에 그 profile 이 없으면 true 다. `FakeHermes` 에 `setPolicyHook(profile: string, active: boolean)` 를 더한다
- probe(`POST /api/mcp/servers/{서버}/test`) 응답의 도구를 `[{ name: "list_scopes" }, { name: "write_note" }, { name: "purge_notes" }, { name: "hidden_tool" }]` 로 바꾼다. `hidden_tool` 은 선언하지 않은 도구다

### 7. 이 phase 를 검증하는 테스트

`backend/src/test/java/com/bifos/assistant/hermes/HttpHermesConnectorClientTest.java`:

| 입력 | 기대 |
| --- | --- |
| `schema` 와 `tools` 가 없는 카탈로그 | `schema()` 가 1, `tools()` 가 빈 목록 |
| `schema: 2` 와 `tools` | 이름, `risk`, `approval`, `title` 이 그대로 |
| `policy_hook: true` 인 설치 응답 | `policyHook()` 이 참 |
| `policy_hook` 이 없는 설치 응답 | 거짓 |

`backend/src/test/java/com/bifos/assistant/connector/ConnectorToolPoliciesTest.java`(신규, Spring 없이):

| 입력 | 기대 |
| --- | --- |
| `schema: 2`, `WRITE` 에 `none` | `valid` 가 거짓 |
| `schema: 2`, 확인 도구가 `tools` 에 없음 | 거짓 |
| `schema: 2`, `risk` 가 모르는 글 | 거짓 |
| `schema: 1`, `tools` 빈 목록 | 참 |
| `DESTRUCTIVE` 에 `always` | 참 |
| `find` 에 null 이름 | 빈 값 |

`backend/src/test/java/com/bifos/assistant/connector/ConnectorConnectionServiceTest.java`:

- 기존 `readConnector` 대역이 `policyHook` 을 참으로 주게 고친다. `ConnectorState` 생성자 인자가 늘었다
- `policy_hook` 이 거짓이면 `check` 뒤 상태가 `PENDING` 이고 probe 를 부르지 않는다
- `schema: 2` manifest 에 probe 가 선언 밖 도구 하나를 내면 `READY` 이고 `undeclaredTools` 가 1 이다
- 하한을 어긴 manifest 는 `catalog(user)` 에 나오지 않는다
- `catalog(user)` 의 `tools` 가 선언대로 나온다

`ConnectorConnectionControllerTest`, `ConnectorConnectionTest` 와 다른 테스트의 record 생성자 호출을 새 칸에 맞춘다. `grep -rn "new ConnectorManifest(\|new ConnectorState(\|new ConnectionSnapshot(\|new AdminConnectionSnapshot(\|new ConnectorSummary(" backend/src` 로 찾는다.

`test/e2e/scenarios/connector.ts`:

- 카탈로그 응답의 `tools` 에 `write_note` 가 `risk: "WRITE"`, `approval: "REQUIRED"` 로 있다
- `/check` 뒤 연결 상태의 `undeclaredTools` 가 1 이다
- `context.hermes.setPolicyHook(<연결용 profile>, false)` 뒤 `/check` 는 `PENDING` 이고, 다시 참으로 돌리고 `/check` 하면 `READY` 다. 연결용 profile 이름은 `context.hermes.connectorRequests()` 의 `install <profile> on` 에서 읽는다
- 카탈로그 응답에 숨겨야 할 것을 보는 기존 단언에서 `list_scopes` 는 이제 `tools` 에 나오므로 뺀다. `DEMO_TOKEN`, `DEMO_SCOPE`, `verify` 는 그대로 숨긴다

### 8. `docs/data-schema.md` 대조

`undeclared_tools` 줄이 구현과 같은지 본다. 다르면 멈추고 보고한다.

## 검토 반영

**이 절이 위의 내용과 다르면 이 절을 따른다.**

- 이 서비스는 그 뒤 바뀌었다. `usable` 은 `resyncedUsable(connection)` 이 됐고 설치를 먼저 다시 보낸 뒤 `readConnector` 의 `configured` 와 probe 를 본다. Control Plane 은 도구 목록을 쓰지 않는다. 지금 코드를 읽고 그 위에 더한다
- `policyHook` 을 설치를 다시 보내기 **전에** 판정하지 않는다. `check` 와 `confirmApplied` 의 첫 `readState` 결과로 거르지 않는다. `resyncedUsable` 안에서 `putConnector` 뒤에 다시 읽은 `ConnectorState` 가 `configured` 이고 `policyHook` 이어야 한다. 옛 판의 `fos-ctx` 를 가진 연결이 연결 확인 한 번으로 고쳐져야 하기 때문이다
- `HermesConnectorClient.putConnector` 의 반환을 `InstallResult(boolean restartRequired, boolean pluginUpdated)` record 로 바꾼다. `plugin_updated` 가 없는 응답은 false 다. 부르는 곳 셋(`apply`, `disconnect`, `resyncedUsable`)을 맞춘다
  - `apply`: `markRestartRequired(result.restartRequired() || result.pluginUpdated())`
  - `resyncedUsable`: `pluginUpdated` 가 참이면 `connection.markRestartRequired(true)` 를 하고 false 를 돌려준다. `restartRequired` 는 지금처럼 쓰지 않는다
- V41 은 칸을 더한 뒤 기존 연결을 내린다. 순서대로 쓴다
  1. `ALTER TABLE connector_connection ADD COLUMN undeclared_tools INT NOT NULL DEFAULT 0;`
  2. `READY` 인 연결의 에이전트를 끄고 사진 받기를 내린다. `agent` 표의 칸 이름은 `agent/domain/Agent.java` 와 `ConnectorConnection.pending(Instant)` 이 하는 일을 읽고 맞춘다
  3. `UPDATE connector_connection SET status = 'PENDING' WHERE status = 'READY';` `restart_required` 는 건드리지 않는다. `check` 는 재시작 대기인 연결에 설치를 다시 보내지 않으므로, 참으로 두면 연결 확인으로 `fos-ctx` 가 새 판이 되지 않는다
  - H2 의 MySQL 모드와 MySQL 에서 함께 도는 SQL 만 쓴다. 자기 표를 하위 질의로 읽는 `UPDATE` 가 MySQL 에서 막히지 않게 2번은 `agent` 를 고치고 하위 질의가 `connector_connection` 을 읽는 모양으로 쓴다
  - 파일 첫머리 주석에 까닭(옛 판의 hook 을 가진 연결은 판정 없이 호출이 나간다)과 되돌리는 방법(연결 확인, gateway 재시작, 관리자 반영 완료)을 적는다
  - `backend/src/test/java/com/bifos/assistant/connector/ConnectorPolicyMigrationTest.java`(신규): `ConnectorConnectionMigrationTest` 의 방식으로 V40 까지 올리고 `READY`, `PENDING`, `DISCONNECTED` 연결을 넣은 뒤 V41 을 올린다. `READY` 였던 줄만 `PENDING` 이 되고 `restart_required` 는 그대로이며 그 에이전트만 꺼진다
- 대역 `test/e2e/fake-hermes.ts`: 설치(`PUT /api/connectors`, `enabled: true`)가 그 profile 의 `policy_hook` 을 참으로 되돌린다. `setPolicyHook(profile, false)` 는 「설치해도 고쳐지지 않는 상태」 로 둔다(`setPolicyHook(profile, true)` 가 풀 때까지). 설치 응답에 `plugin_updated: false` 를 더한다
- 서비스 테스트를 더한다: (1) 설치 뒤 읽은 상태의 `policyHook` 이 거짓이면 `PENDING` 이고 probe 를 부르지 않는다. (2) `putConnector` 가 `pluginUpdated` 참을 돌려주면 `PENDING` 과 `restartRequired` 참이다. (3) 그 뒤 `confirmApplied` 에서 `pluginUpdated` 가 거짓이면 `READY` 다. (4) 마이그레이션이 내린 모양(`PENDING`, `desiredEnabled` 참, `restartRequired` 거짓, 에이전트 꺼짐)의 연결이 `check`(설치가 `pluginUpdated` 참) 뒤 재시작 대기가 되고 `confirmApplied` 뒤 `READY` 와 에이전트 켜짐으로 돌아온다

## 검증

```bash
# cwd: 저장소 root
(cd backend && ./gradlew test --tests '*HttpHermesConnectorClientTest' --tests '*ConnectorToolPoliciesTest' --tests '*ConnectorConnection*')
(cd backend && ./gradlew test)
(cd backend && ./gradlew qualityCheck)
node test/e2e/run.ts
node --test 'test/unit/**/*.test.ts'
scripts/check-public-safe.sh
```

- 모두 종료 코드 0
- `node test/e2e/run.ts` 는 `./gradlew test` 뒤에 돌린다. 앞선 실행이 남긴 데이터에 걸린다

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/connector/domain/type/ToolRisk.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/connector/domain/type/ToolApproval.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/connector/domain/ToolPolicy.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/hermes/dto/ConnectorTool.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/hermes/dto/ConnectorManifest.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/hermes/HermesConnectorClient.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/hermes/HttpHermesConnectorClient.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/connector/application/ConnectorToolPolicies.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/connector/application/ConnectorConnectionService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/connector/application/model/ConnectorToolSummary.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/connector/application/model/ConnectorSummary.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/connector/application/model/ConnectionSnapshot.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/connector/application/model/AdminConnectionSnapshot.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/connector/domain/ConnectorConnection.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/connector/presentation/ConnectionDtos.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/connector/presentation/ConnectorConnectionAdminController.java` | 수정 |
| `backend/src/main/resources/db/migration/V41__connector_undeclared_tools.sql` | 신규 |
| `backend/src/test/java/com/bifos/assistant/hermes/HttpHermesConnectorClientTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/connector/ConnectorToolPoliciesTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/connector/ConnectorPolicyMigrationTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/connector/ConnectorConnectionServiceTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/connector/ConnectorConnectionControllerTest.java` | 수정 |
| `test/e2e/fake-hermes.ts` | 수정 |
| `test/e2e/scenarios/connector.ts` | 수정 |
