# Phase 03. Control Plane 에 껐다 켜기 칸과 대시보드 계약을 더한다

**Execution profile**: standard

## 목표

바인딩에 껐다 켜기 단계와 실패 표시 칸을 더하고, 대시보드 클라이언트가 `bind.cycle` 을 보내고 `cycle_available`, `cycle_off` 를 읽게 한다. 흐름은 phase 04 가 잇는다.

**범위 외**: 예약 작업과 설치 결과 적용(phase 04). 연결 응답의 `applyFailed` 와 화면(PR3).

## 컨텍스트

**요구사항**: REQ1

**근거 문서**: `docs/features/connector/design.md` 의 「Control Plane: 바인딩 칸과 단계」, `backend/docs/data-schema.md` 의 「마이그레이션 작성 규칙」 과 「테이블과 컬럼에 한국어 COMMENT를 단다」

- 엔티티는 `backend/src/main/java/com/bifos/assistant/connector/connection/domain/ConnectorBinding.java` 다. `installed(boolean restartRequired, Instant now)`, `ready(now)`, `pending(now)`, `scheduleApply`, `clearApplyDue`, `markRestartRequired` 가 있다.
- 대시보드 포트는 `backend/src/main/java/com/bifos/assistant/hermes/application/port/HermesConnectorClient.java` 의 `InstallResult(boolean restartRequired, boolean pluginUpdated, boolean reloadPending)` 와 `bindConnector` 두 겹(5인자, guard 6인자)이다. 테스트는 5인자를 Mockito 로 흉내 내므로 **보통 설치는 지금 시그니처로 계속 부른다.**
- 응답 해석은 `HttpHermesConnectorClient.installResult` 의 `optionalBoolean` 이다.
- 마이그레이션 번호는 예약해 두었다: `backend/src/main/resources/db/migration/V20261105074428__connector_binding_cycle.sql`. 다른 이름으로 만들지 않는다. main 을 합친 뒤에는 공통 규칙대로 `orch queue reserve --module backend --name connector_binding_cycle` 를 다시 불러 `renewed` 면 이름을 바꾼다.
- `ConnectorErrors.notApplied` 는 `ResyncOutcome` 의 exhaustive switch 다.

## 의도 메모

- `InstallResult` 의 2인자, 3인자 생성자는 남긴다. 기존 테스트가 쓴다.
- `CYCLE_PENDING` 의 오류는 새 코드를 만들지 않고 「아직 반영 중」 인 `CONNECTOR_APPLY_SCHEDULED` 를 쓴다. 화면이 이미 이 코드를 안다.

## 작업 항목

### 1. 마이그레이션 `V20261105074428__connector_binding_cycle.sql` 신규

`agent_connector_binding` 에 `cycle_phase VARCHAR(16) NULL`, `cycle_at DATETIME(6) NULL`, `apply_failed_at DATETIME(6) NULL` 을 한국어 `COMMENT` 와 함께 더한다. 값의 뜻(`IDLE_WAIT`, `OFF`, NULL 의 뜻)을 COMMENT 에 적는다. 본보기는 `V20261105074419__chat_artifact_file_space.sql` 이다. 머리 주석에 ADR-20261120 / connector-auto-apply 를 적는다.

### 2. `connector/connection/domain/type/CyclePhase.java` 신규

`IDLE_WAIT`, `OFF` 와 각 뜻의 한국어 Javadoc.

### 3. `ConnectorBinding` 에 칸과 전이 메서드

`@Enumerated(EnumType.STRING) cyclePhase`, `cycleAt`, `applyFailedAt` 과 접근자. 메서드:

- `needCycle(Instant now)`: 단계가 없으면 `IDLE_WAIT` 와 `cycleAt = now`. 이미 단계가 있으면 그대로
- `cycledOff(Instant now)`: 단계가 `OFF` 가 아니면 `OFF` 와 `cycleAt = now`
- `clearCycle()`: 단계와 `cycleAt` 을 비운다
- `markApplyFailed(Instant now)`, `clearApplyFailed()`
- `ready(now)` 는 단계와 실패 표시도 비운다

### 4. `backend/docs/data-schema.md` 의 `agent_connector_binding`

세 칸의 뜻을 설계 표대로 한 줄씩 더한다.

### 5. `HermesConnectorClient` 와 `HttpHermesConnectorClient`

- `InstallResult` 에 `boolean cycleAvailable`, `boolean cycleOff` 를 더하고 기존 생성자는 둘을 거짓으로 채운다. Javadoc 에 옛 plugin 은 칸을 내지 않아 거짓이라고 적는다.
- 포트에 `default InstallResult bindConnector(profile, connectorId, vault, sandboxOwner, ownerBrowser, JsonNode guard, boolean cycleOff)` 를 더한다. 기본 구현은 `cycleOff` 면 `IllegalStateException`, 아니면 6인자로 넘긴다.
- `HttpHermesConnectorClient` 가 7인자를 구현해 `bind` 본문에 `"cycle": "off"` 를 넣는다. 기존 6인자는 7인자를 `false` 로 부른다. `installResult` 가 `cycle_available`, `cycle_off` 를 `optionalBoolean(..., false)` 로 읽는다.

### 6. `ResyncOutcome.CYCLE_PENDING` 과 `ConnectorErrors`

`ResyncOutcome` 에 「껐다 켜기 단계라 설치를 보내지 않았거나 끄기 답을 받았다」 를 더하고, `notApplied` 가 `CONNECTOR_APPLY_SCHEDULED` 로 바꾼다.

### 7. 테스트

- `backend/src/test/java/com/bifos/assistant/hermes/HttpHermesConnectorClientTest.java`: `cycleOff` 가 참이면 본문의 `bind.cycle` 이 `"off"`, 거짓이면 키가 없다. 두 응답 칸을 읽고 없으면 거짓이다.
- `backend/src/test/java/com/bifos/assistant/connector/ConnectorBindingTest.java`: `needCycle` 가 처음 시각을 지키고, `cycledOff` 가 `OFF` 로 처음 넘어갈 때만 시각을 바꾸며, `ready` 가 단계와 실패 표시를 비운다.

## 검증

```bash
cd backend && ./gradlew test --tests '*HttpHermesConnectorClientTest' --tests '*ConnectorBindingTest' --tests '*JpaAuditingMigrationTest'
cd backend && ./gradlew archTest
cd backend && ./gradlew checkstyleMain checkstyleTest spotlessCheck
node scripts/checks/check-migration-versions.mjs origin/main
node scripts/checks/check-file-length.mjs
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/resources/db/migration/V20261105074428__connector_binding_cycle.sql` | 신규 |
| `backend/src/main/java/com/bifos/assistant/connector/connection/domain/type/CyclePhase.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/connector/connection/domain/ConnectorBinding.java` | 수정 (칸과 전이) |
| `backend/docs/data-schema.md` | 수정 (세 칸) |
| `backend/src/main/java/com/bifos/assistant/hermes/application/port/HermesConnectorClient.java` | 수정 (답 칸, 7인자 설치) |
| `backend/src/main/java/com/bifos/assistant/hermes/infra/HttpHermesConnectorClient.java` | 수정 (`bind.cycle`, 답 해석) |
| `backend/src/main/java/com/bifos/assistant/connector/connection/application/dto/ResyncOutcome.java` | 수정 (`CYCLE_PENDING`) |
| `backend/src/main/java/com/bifos/assistant/connector/connection/application/ConnectorErrors.java` | 수정 (오류 대응) |
| `backend/src/test/java/com/bifos/assistant/hermes/HttpHermesConnectorClientTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/connector/ConnectorBindingTest.java` | 수정 |
