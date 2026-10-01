# Phase 02. Control Plane 의 연결을 커넥터 id 를 받는 범용 흐름으로 바꾸고 V38 로 옮긴다

**Execution profile**: deep

## 목표

backend 의 `connector` 모듈과 `hermes` 의 커넥터 클라이언트에서 가계부 전용 코드를 없애고, 커넥터 id 와 manifest 로 도는 범용 흐름으로 바꾼다. 기존 가계부 연결 행은 V38 이 새 표로 옮긴다.

**범위 외**: 대시보드 plugin(phase 01), 화면(phase 03), e2e 와 서비스 이름 검사(phase 04).

## 컨텍스트

지금 코드(모두 `backend/src/main/java/com/bifos/assistant/` 아래):

| 지금 | 바뀐 뒤 |
| --- | --- |
| `connector/application/AccountbookConnectionService` | `connector/application/ConnectorConnectionService` |
| `connector/application/AccountbookProperties`, `AccountbookTokenVerifier`, `connector/infra/HttpAccountbookTokenVerifier` | 지운다. 확인과 선택지는 `HermesConnectorClient.call` |
| `connector/domain/AccountbookConnection` | `connector/domain/ConnectorConnection` |
| `connector/domain/ConnectionStatus` | `connector/domain/type/ConnectionStatus` (DB 에 저장되는 enum) |
| `connector/infra/AccountbookConnectionRepository` | `connector/infra/ConnectorConnectionRepository` |
| `connector/presentation/AccountbookConnectionController`, `AccountbookConnectionAdminController`, `ConnectionDtos` | `ConnectorConnectionController`, `ConnectorConnectionAdminController`, `ConnectionDtos` |
| `connector/application/ConnectionSnapshot`, `AdminConnectionSnapshot`, `ConnectorOperationFailure` | 칸을 범용으로 바꿔 `connector/application/model/` 로 옮긴다 |
| `hermes` 의 새 record(`ConnectorManifest`, `CallResult` 등) | 기존 관례대로 `hermes/dto/` 에 둔다 |
| `hermes/HermesConnectorClient`, `hermes/HttpHermesConnectorClient` | 범용 메서드로 바꾼다 |
| `application.yml` 의 `assistant.accountbook.*` | 지운다 |
| `shared/error/ErrorCode` 의 `ACCOUNTBOOK_TOKEN_REJECTED`, `ACCOUNTBOOK_FAMILY_FORBIDDEN`, `ACCOUNTBOOK_UNAVAILABLE` | `CONNECTOR_CREDENTIAL_REJECTED`(400), `CONNECTOR_FORBIDDEN`(403), `CONNECTOR_UNAVAILABLE`(503), `CONNECTOR_NOT_FOUND`(404) 로 바꾼다. `CONNECTOR_OPERATION_FAILED`(502)는 그대로 |

그대로 쓰는 것: `agent/application/AgentLifecycleService.createConnectorAgent(CurrentUser, String)`, `agent.connector_managed`, `hermes/HermesToolsetClient` 의 `readEnabled`, `writeApiServer`, `agent/domain/AgentToolPolicy.CONTROL_PLANE_MCP`, 사용자 행 잠금 `AppUserRepository.findByIdForUpdate`.

코드 규칙은 `backend/AGENTS.md` 와 `scripts/quality.sh check` 가 강제한다. 새 파일은 기준 파일에 기대지 않고 규칙을 지킨다: 메서드 사이 빈 줄, 한 줄 한 문장, 전체 이름 대신 import, `@Slf4j`, `@RequiredArgsConstructor`, 엔티티 `@Getter @Accessors(fluent = true)`, 서비스 안 public 중첩 모델 금지(입출력 record 는 `application/model`).

**근거 문서**: `docs/connectors.md` 전체, `docs/data-schema.md` 의 「connector_connection」, `docs/flow.md` 의 「커넥터 연결」, `docs/adr/ADR-043-커넥터는-plugin-의-connector-json-으로-선언하고-control-plane-은-범용-흐름만-갖는다.md`, `docs/adr/ADR-039-외부-서비스-연결은-사용자별-전용-에이전트로-실행한다.md`

## 의도 메모

- 잠금, `desired_enabled`, `restart_required` 누적, 실패 때의 커밋 규칙은 지금 `AccountbookConnectionService` 와 같다. **등록 순서는 `docs/connectors.md` 「설치와 실패 처리」 의 순서(확인 → 잠금 → env → 도구 목록 → 설치)로 바꾼다.** 지금 서비스는 잠금 → 확인 → 도구 목록 → env → 설치다. env 이름과 서버 이름과 칸은 manifest 에서 꺼낸다. 순서 말고는 지금 테스트가 지키는 동작을 그대로 옮긴다
- **새 클래스는 `Instant.now()` 를 직접 부르지 않는다**(`backend/AGENTS.md` 의 `NO_DIRECT_INSTANT_NOW`). 엔티티의 상태 전이 메서드는 시각을 인자(`Instant now`)로 받고, 서비스가 `Clock` 을 주입받아 넘긴다
- **지우는 클래스를 가리키는 기준 파일을 함께 줄인다.** `backend/config/archunit/store/` 와 `backend/config/checkstyle/baseline.xml` 에서 지운 클래스의 줄만 뺀다. 남겨 두면 `./gradlew test` 가 기준 갱신 실패로 끝난다. 새 위반은 더하지 않는다. 줄이는 방법은 `backend/AGENTS.md` 의 「기준 파일」 을 따른다
- 카탈로그는 요청마다 대시보드에서 읽는다. 캐시를 두지 않는다. 운영자가 목록을 바꾸면 바로 반영되어야 하고, 호출이 드물다
- `fields` 는 JSON 텍스트 문자열 열이다. `AttributeConverter<ConnectionFields, String>` 로 record `ConnectionFields(Map<String, String> values, Map<String, String> secretPrefixes)` 를 읽고 쓴다. MySQL `JSON` 타입을 쓰지 않는 까닭은 `docs/data-schema.md` 에 있다
- 비밀 칸은 저장 직전에 앞 8자로 자른다. **값이 8자 이하이면 그 칸을 `secretPrefixes` 에 넣지 않는다**(앞 8자가 원문 전체다). 엔티티가 원문을 받는 메서드를 두지 않는다
- V38 은 V36 의 표를 옮기고 지운다. 운영에는 실제 연결 행이 있다. 옮긴 뒤 상태, 에이전트, 재시작 대기, 활성화 후보가 그대로여야 한다

## Blocked 조건

- phase 01 의 커밋이 없다 → `PHASE_BLOCKED: phase 01 이 먼저다`

## 작업 항목

### 1. `V38__connector_connection.sql`

- `connector_connection` 을 `docs/data-schema.md` 표대로 만든다. `fields VARCHAR(4000) NOT NULL`, 유니크 `(user_id, connector_id)`, 유니크 `agent_id`, FK 둘
- `accountbook_connection` 행을 옮긴다. `connector_id = 'fos-accountbook'`, `fields` 는 `CONCAT` 으로 만든 JSON 텍스트다. `token_prefix` 가 있으면 `secretPrefixes.token`, `family_uuid` 가 있으면 `values.family`. 비어 있으면 그 키를 넣지 않는다. **NULL 은 `CASE` 로 나누고 `CONCAT` 에 NULL 을 넘기지 않는다.** MySQL 의 `CONCAT` 은 인자 하나가 NULL 이면 NULL 을 내 `fields NOT NULL` 에 걸리고, H2 는 다르게 움직일 수 있어 검사가 잡지 못한다
- `accountbook_connection` 을 지운다
- 검사가 H2(MySQL 모드)에서도 돈다. 두 DB 에서 같이 도는 SQL 만 쓴다

### 2. 엔티티, 저장소, 변환기

- `connector/domain/ConnectorConnection`: 칸은 표와 같다. 지금 엔티티의 상태 전이 메서드(`pending`, `beginRegister`, `registered`, `beginDisconnect`, `disconnected`, `ready`, `confirmDisconnected`, `markRestartRequired`)를 그대로 옮기고, `registered` 는 `ConnectionFields` 를 받는다
- `connector/domain/ConnectionFields` record 와 `connector/infra/ConnectionFieldsConverter`
- `connector/infra/ConnectorConnectionRepository`: `findByUserIdAndConnectorId`, `findByUserIdIn` 등 지금 쓰는 조회를 옮긴다

### 3. `HermesConnectorClient` 범용화

```java
List<ConnectorManifest> readCatalog();
CallResult call(String connectorId, String tool, Map<String, String> values);
boolean putConnector(String profile, String connectorId, boolean enabled);
ConnectorState readConnector(String profile, String connectorId);
ProbeResult probe(String profile, String mcpServer);
boolean putEnv(String profile, String key, String value);
boolean deleteEnv(String profile, String key);
```

- `ConnectorManifest`, `CallResult` 등 record 는 `hermes/dto/` 에 둔다
- `readCatalog` 는 대시보드 카탈로그의 `fields[].env`, `fields[].options`, `verify.tool`, `mcp_server` 를 읽는다. `env` 이름은 Control Plane 의 응답에 담지 않는다
- `CallResult` 는 성공 결과(JSON 노드)나 공통 어휘 하나다
- 응답 모양이 틀리면 지금처럼 `IllegalStateException` 으로 묶는다. 원문을 예외 메시지에 담지 않는다

### 4. `ConnectorConnectionService`

`docs/connectors.md` 의 「설치와 실패 처리」 순서대로.
- `catalog(CurrentUser)`: 카탈로그와 내 상태
- `read(user, connectorId)`, `options(user, connectorId, fieldKey, values)`, `register(user, connectorId, values)`, `check(user, connectorId)`, `disconnect(user, connectorId)`, `listForAdmin(admin)`, `confirmApplied(admin, connectorId, userId)`
- 모르는 커넥터는 `CONNECTOR_NOT_FOUND`. 단 이미 연결 행이 있는 커넥터는 카탈로그에서 빠져도 `read` 와 `disconnect` 가 된다(env 이름은 저장된 칸 키로 알 수 없으므로, 해제는 설치 해제만 하고 env 삭제는 건너뛴 뒤 `restartRequired` 를 참으로 둔다)
- `values` 검사: 모르는 키, 필수 누락, `pattern` 불일치 → `VALIDATION_FAILED`
- `register` 의 확인 도구 실패는 공통 어휘 → 오류 코드 표(`docs/connectors.md`)로 바꾼다. 이때는 아무것도 저장하지 않는다
- 전용 에이전트 이름은 manifest `title`
- 도구 목록 줄이기는 지금 `narrowedEnabled` 와 같고, 서버 이름은 manifest `mcp_server`
- `listForAdmin` 은 그 그룹의 사용자만 읽고 이름은 Map 으로 찾는다(사용자마다 다시 조회하지 않는다)

### 5. 컨트롤러와 DTO

`docs/connectors.md` 「Control Plane API」 표의 경로와 응답 칸 그대로. 요청 record 의 `toString` 은 `values` 를 가린다. 역직렬화 오류는 지금처럼 고정 응답이다.

### 6. 검사

지금 `backend/src/test/java/com/bifos/assistant/connector/` 의 검사를 범용 이름으로 옮기고 시험 커넥터(`demo-notes`) 로 바꾼다. 지키는 동작은 줄이지 않는다. `HttpAccountbookTokenVerifierTest` 는 대상이 사라지므로 지운다. 시험 토큰은 `demo_ok_0123456789`, `demo_bad_0123456789` 다.
- `ConnectorConnectionTest`: 지금 `AccountbookConnectionTest` 의 엔티티 상태 전이 검사를 옮긴다. 시각은 인자로 준다
- `ConnectorConnectionMigrationTest`: V37 까지 올린 H2 에 가계부 행 세 개(토큰과 가족 있음, 토큰만 있고 가족 없음, 해제되어 둘 다 없음)를 넣고 V38 뒤 `connector_connection` 의 칸과 `fields` JSON 이 기대값과 같고 `accountbook_connection` 이 없다
- `ConnectorConnectionServiceTest`: 등록 순서(확인 → 잠금 → env → 도구 목록 → 설치), 확인 도구 실패 시 저장 없음, 비밀 칸이 앞 8자만 저장됨(DB 의 `fields` 문자열에 원문이 없음), 8자 비밀값은 `secretPrefixes` 에 없고 9자는 앞 8자가 있음, 카탈로그에서 빠진 커넥터의 해제, 외부 실패 시 `PENDING` 과 비활성 커밋, `restartRequired` 누적, 관리자 반영 완료
- `ConnectorConnectionControllerTest`: 경로별 상태 코드, 남의 연결을 못 읽음, 응답에 비밀 원문 없음
- `HttpHermesConnectorClientTest`: 범용 메서드의 요청과 응답 파싱, 모양이 틀린 응답의 예외

## 검증

```bash
# cwd: 저장소 root
cd backend && ./gradlew test --tests 'com.bifos.assistant.connector.*' --tests 'com.bifos.assistant.hermes.*' && cd ..
cd backend && ./gradlew test && cd ..
node test/e2e/run.ts
! git grep -niE "accountbook|ACCOUNTBOOK_|fab_" -- backend/src/main ':!backend/src/main/resources/db/migration'
scripts/quality.sh check
scripts/check-public-safe.sh
```

- `node test/e2e/run.ts` 는 Flyway 와 스키마 검증을 함께 돌려 V38 과 엔티티가 맞는지 본다. 커넥터 시나리오는 phase 04 가 더한다

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/resources/db/migration/V38__connector_connection.sql` | 신규 |
| `backend/src/main/resources/application.yml` | 수정 |
| `backend/src/main/java/com/bifos/assistant/shared/error/ErrorCode.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/hermes/HermesConnectorClient.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/hermes/HttpHermesConnectorClient.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/hermes/dto/*.java` | 신규 |
| `backend/config/archunit/store/*` | 수정 |
| `backend/config/checkstyle/baseline.xml` | 수정 |
| `backend/src/main/java/com/bifos/assistant/connector/**/*.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/connector/application/AccountbookConnectionService.java` | 삭제 |
| `backend/src/main/java/com/bifos/assistant/connector/application/AccountbookProperties.java` | 삭제 |
| `backend/src/main/java/com/bifos/assistant/connector/application/AccountbookTokenVerifier.java` | 삭제 |
| `backend/src/main/java/com/bifos/assistant/connector/application/AdminConnectionSnapshot.java` | 삭제 |
| `backend/src/main/java/com/bifos/assistant/connector/application/ConnectionSnapshot.java` | 삭제 |
| `backend/src/main/java/com/bifos/assistant/connector/application/ConnectorOperationFailure.java` | 삭제 |
| `backend/src/main/java/com/bifos/assistant/connector/domain/AccountbookConnection.java` | 삭제 |
| `backend/src/main/java/com/bifos/assistant/connector/domain/ConnectionStatus.java` | 삭제 |
| `backend/src/main/java/com/bifos/assistant/connector/infra/AccountbookConnectionRepository.java` | 삭제 |
| `backend/src/main/java/com/bifos/assistant/connector/infra/HttpAccountbookTokenVerifier.java` | 삭제 |
| `backend/src/main/java/com/bifos/assistant/connector/presentation/AccountbookConnectionAdminController.java` | 삭제 |
| `backend/src/main/java/com/bifos/assistant/connector/presentation/AccountbookConnectionController.java` | 삭제 |
| `backend/src/main/java/com/bifos/assistant/connector/presentation/ConnectionDtos.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/connector/ConnectorConnectionControllerTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/connector/ConnectorConnectionMigrationTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/connector/ConnectorConnectionServiceTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/connector/ConnectorConnectionTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/connector/HttpAccountbookTokenVerifierTest.java` | 삭제 |
| `backend/src/test/java/com/bifos/assistant/connector/AccountbookConnectionControllerTest.java` | 삭제 |
| `backend/src/test/java/com/bifos/assistant/connector/AccountbookConnectionMigrationTest.java` | 삭제 |
| `backend/src/test/java/com/bifos/assistant/connector/AccountbookConnectionServiceTest.java` | 삭제 |
| `backend/src/test/java/com/bifos/assistant/connector/AccountbookConnectionTest.java` | 삭제 |
| `backend/src/test/java/com/bifos/assistant/hermes/HttpHermesConnectorClientTest.java` | 수정 |
