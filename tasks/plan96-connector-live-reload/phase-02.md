# Phase 02. backend 가 `reload_pending` 바인딩을 두 주기 뒤 스스로 확인해 `READY` 로 둔다

**Execution profile**: deep

## 목표

설치 응답의 `reload_pending` 이 참이고 재시작이 필요 없으면 바인딩에 반영 예정 시각 `apply_due_at` 을 적는다.
예약 작업이 그 시각이 지난 바인딩에 지금의 「바인딩의 반영 맞추기」(`ConnectorBindingService#resync`)를 돌려 `READY` 나 `PENDING` 으로 둔다.
관리자 반영 완료는 재시작 대기인 바인딩에만 필요해진다.

**범위 외**: 대시보드 plugin 의 응답은 phase 01 이 만든다. 관리자 화면 문구와 e2e 대역은 phase 03 이다.
옛 커넥터 에이전트(`Agent#connectorManaged()`)의 판정은 바꾸지 않는다.

## 컨텍스트

- 바인딩 엔티티는 `backend/src/main/java/com/bifos/assistant/connector/domain/ConnectorBinding.java` 다. 표는 `agent_connector_binding`(`backend/src/main/resources/db/migration/V79__connector_binding.sql`). 상태를 바꾸는 메서드는 시각을 인자로 받는다. `installed(boolean restartRequired, Instant now)` 는 재시작 대기를 논리 OR 로 누적하고, `ready(Instant now)` 가 재시작 대기를 푼다
- 서비스는 `backend/src/main/java/com/bifos/assistant/connector/application/ConnectorBindingService.java` 다
  - `bind`, `reinstall`, `resync` 가 설치를 보낸다. `resync(binding, manifest, afterRestart)` 는 연결 확인(`ConnectorConnectionService#recordCheck`, `afterRestart` 거짓)과 관리자 반영 완료(`confirmLocked`, 참)가 부른다. 재시작 여부는 `reinstallNeedsRestart` 가 돌려준다
  - 잠금 순서는 사용자 행(`AppUserRepository#findByIdForUpdate`) 다음 에이전트 행(`AgentRepository#findByIdForUpdate`)이다. MySQL REPEATABLE READ 때문에 트랜잭션의 첫 읽기가 잠금이어야 한다. `confirmApplied` 의 Javadoc 이 그 까닭을 적는다. 그 메서드처럼 id 는 트랜잭션 밖에서 읽고 안에서 잠근 뒤 바인딩을 다시 읽는다
  - 생성자가 둘이다. 시험(`ConnectorBindingServiceTest`)은 `@SpringBootTest` 로 주입받은 서비스를 쓰므로 시계는 `Clock.systemUTC()` 다. 시각이 지난 상태는 `ConnectorBindingServiceTest` 524행 근처처럼 `jdbc.update("UPDATE agent_connector_binding SET ...")` 로 만든다
  - `ConnectorBinding.agent` 와 `connection` 은 LAZY 이고 `spring.jpa.open-in-view` 가 거짓이다. 트랜잭션 밖에서 연관을 읽으면 초기화 예외가 난다
  - 연결 확인(`ConnectorConnectionService#recordCheck`)은 연결의 사용자 행을 잠근다. 바인딩의 연결 사용자와 에이전트 주인은 붙이기 규칙으로 같다
  - 반영 전 호출을 막는 글은 `ConnectorPolicyService.BINDING_PENDING_MESSAGE` 다. 지금은 「관리자가 반영을 마치면 …」 이다
- 설치 응답은 `backend/src/main/java/com/bifos/assistant/hermes/HermesConnectorClient.java` 의 `record InstallResult(boolean restartRequired, boolean pluginUpdated)` 이고 `HttpHermesConnectorClient` 가 `optionalBoolean(body, "plugin_updated", false)` 로 읽는다. 시험 코드에 `new InstallResult(` 가 36군데 있다
- 일정 작업 본보기는 `connector/application/ConnectorActionExpirer.java`(`@Scheduled(cron = "${assistant.connector.policy.expire-cron}")`)다. 설정 record 본보기는 `ConnectorPolicyProperties`(`@DefaultValue`, `requirePositive`)이고 `@ConfigurationPropertiesScan` 이 찾는다
- 시험 설정 `backend/src/test/resources/application-test.yml` 은 `assistant.connector.policy.expire-cron: "-"` 로 일정을 끈다
- 새 Flyway 는 UTC 작성 시각 14자리 `V<YYYYMMDDHHMMSS>__<설명>.sql` 이다. `scripts/check-migration-versions.mjs` 가 검사한다

**근거 문서**: `docs/adr/ADR-20261007-connector-live-reload.md`, `docs/backend/connector-install.md` 의 「붙이기」, 「바인딩의 반영 맞추기」, 「관리자 반영 완료」

## 의도 메모

- 예약 확인은 한 번만 시도한다. 시도하기 전에 `apply_due_at` 을 비우고, 다시 보낸 설치가 또 `reload_pending` 이면 `resync` 가 새 시각을 적는다. probe 가 실패해 `PENDING` 이 되면 사용자의 연결 확인이 다시 맞춘다. 실패한 바인딩을 매분 다시 부르지 않는다
- 재시작 대기 바인딩은 예약 확인 대상에서 뺀다. 관리자 반영 완료가 맡는다
- 옛 대시보드 plugin 은 `reload_pending` 을 내지 않고 바뀐 것이 있으면 `restart_required` 를 참으로 답한다. 그 경우 지금처럼 재시작 대기가 된다
- 기본 지연 150초는 gateway housekeeping 60초 두 주기와 연결 시간이다
- 옛 설치 분기의 재시작 판정은 `pluginUpdated` 만 본다. 옛 설치는 설치된 커넥터에 `restart_required` 를 늘 참으로 답하기 때문이다(`reinstallNeedsRestart` Javadoc). 공통 메서드도 이 규칙을 지킨다

## 작업 항목

### 1. `backend/src/main/resources/db/migration/V20261007081400__connector_binding_apply_due.sql`

`agent_connector_binding` 에 `apply_due_at DATETIME(6) NULL` 을 더한다. 머리 주석에 무엇을 왜 더하는지 한국어로 적는다.

### 2. `ConnectorBinding`

- 칸 `applyDueAt`(`@Column(name = "apply_due_at")`)과 Javadoc
- `scheduleApply(Instant dueAt)`: 비었거나 더 이른 값이면 바꾼다. `clearApplyDue()`
- `ready(Instant now)` 가 `applyDueAt` 도 비운다

### 3. `HermesConnectorClient` 와 `HttpHermesConnectorClient`

- `InstallResult` 에 `boolean reloadPending` 을 더하고 두 인자 생성자 `InstallResult(boolean restartRequired, boolean pluginUpdated)` 를 남겨 `reloadPending` 을 거짓으로 둔다. 기존 시험의 `new InstallResult(` 는 고치지 않는다
- `optionalBoolean(body, "reload_pending", false)` 로 읽는다

### 4. `connector/application/ConnectorBindingProperties.java` (신규)

`@ConfigurationProperties(prefix = "assistant.connector.binding")` record. `@DefaultValue("150s") Duration applyDelay` 를 갖고 0 이하이면 기동을 멈춘다. `application.yml` 의 `assistant.connector` 아래에 `binding.apply-delay: 150s` 와 `binding.apply-cron: "*/30 * * * * *"` 를 주석과 함께 둔다. `application-test.yml` 에 `binding.apply-cron: "-"` 를 둔다.

### 5. `ConnectorBindingService`

- 생성자 둘에 `ConnectorBindingProperties` 를 받는다
- 설치 결과를 바인딩에 적는 private 메서드 하나로 `bind`, `reinstall` 의 일반 바인딩 분기, `resync` 를 모은다. 일반 바인딩은 `restart = restartRequired || pluginUpdated`, 옛 설치는 `restart = pluginUpdated` 이고, `!restart && reloadPending` 이면 `scheduleApply(now.plus(applyDelay))` 다. `reinstallNeedsRestart` 는 `InstallResult` 를 돌려주게 바꾼다. 옛 설치 분기의 판정은 그대로다
- `resync` 는 `afterRestart` 가 거짓이고 `applyDueAt` 이 지금보다 뒤면 설치를 다시 보내지 않고 `PENDING` 으로 둔다. 다시 보낸 설치가 `reloadPending` 이면 시각을 적고 `PENDING` 으로 돌아간다
- 새 `public int applyDue()`: 트랜잭션 밖에서 `apply_due_at <= now` 이고 재시작 대기가 아닌 바인딩의 id, 연결 사용자 id, 에이전트 id 를 projection 으로 읽는다(작업 항목 6). 바인딩마다 `TransactionTemplate` 안에서 연결 사용자 행(`AppUserRepository#findByIdForUpdate`), 에이전트 행(`AgentRepository#findByIdForUpdate`)을 잠그고 바인딩을 id 로 다시 읽는다. 바인딩이 없거나, 에이전트가 지워졌거나, 에이전트 주인이 연결 사용자와 다르거나, 더는 대상이 아니면 건너뛴다. 아직 대상이면 `clearApplyDue()` 뒤 `resync(binding, ConnectorManifests.find(connector, connectorId), false)` 를 돌리고 저장한다. 한 바인딩의 실패는 `warn` 으로 남기고 다음으로 간다. 처리한 수를 돌려준다
- 클래스 Javadoc 의 「공유 gateway 를 재시작한 뒤 반영 완료만 누른다」 와 `bind` Javadoc 을 새 흐름으로 고친다

### 6. `connector/infra/ConnectorBindingRepository.java`

예약 대상을 읽는 메서드를 더한다. 엔티티 대신 `@Query("select b.id, b.agent.id, b.connection.userId from ConnectorBinding b where b.applyDueAt <= :now and b.restartRequired = false")` 처럼 id 셋을 돌려주는 projection 을 쓴다. 반환 타입은 `connector/application/model` 이나 저장소 안의 record 로 둔다(backend/AGENTS.md 의 「데이터 클래스」 규칙을 따른다). `RepositoryQueryMysqlTest` 가 저절로 실행한다.

### 6-1. `connector/application/ConnectorPolicyService.java`

`BINDING_PENDING_MESSAGE` 를 「이 에이전트에 붙인 연결이 아직 반영되지 않았다. 대개 몇 분 안에 저절로 반영되니 사용자에게 잠시 뒤 다시 시도하라고 알린다.」 로 바꾼다. 위 주석의 「연결 확인을 다시 해도 풀리지 않는다」 도 새 흐름에 맞게 고친다. 이 글을 단언하는 backend 시험(`git grep -n "관리자가 반영을 마치면" backend/src/test`)을 같이 고친다. e2e 상수는 phase 03 이 고친다.

### 7. `connector/application/ConnectorBindingApplier.java` (신규)

`ConnectorActionExpirer` 와 같은 모양으로 `@Scheduled(cron = "${assistant.connector.binding.apply-cron}")` 에서 `applyDue()` 를 부른다.

### 8. 시험

- `backend/src/test/java/com/bifos/assistant/connector/ConnectorBindingTest.java`: `scheduleApply` 가 늦은 값을 지키고 `ready` 가 비우는 것
- `backend/src/test/java/com/bifos/assistant/connector/ConnectorBindingServiceTest.java`: 기존 대역이 설치 응답을 정하는 방법을 따라
  - 붙이기 응답이 `reloadPending` 이면 재시작 대기가 아니고 `applyDueAt` 이 지금 더하기 지연이다
  - 연결 확인이 그 시각 전이면 설치를 다시 보내지 않고 `PENDING` 이다
  - `jdbc.update` 로 `apply_due_at` 을 과거로 바꾼 뒤 `applyDue()` 가 probe 성공으로 `READY` 로 두고 `applyDueAt` 을 비운다
  - probe 가 실패하면 `PENDING` 이고 `applyDueAt` 이 비어 다음 `applyDue()` 가 다시 집지 않는다
  - 재시작 대기 바인딩은 `applyDue()` 가 집지 않는다
- `backend/src/test/java/com/bifos/assistant/hermes/HttpHermesConnectorClientTest.java`: `reload_pending` 을 읽고 없으면 거짓이다

### 9. 문서

- `docs/backend/connector-install.md`: 「붙이기」 7단계와 아래 문단, 「재시작」, 「바인딩의 반영 맞추기」, 「관리자 반영 완료」 를 새 흐름으로 고치고 「반영 예정 확인」 절을 더한다. 「profile 하나의 MCP 만 다시 붙이는 공식 경로는 없다」 문단은 ADR 을 가리키게 고친다
- `docs/backend/schema/connector.md`: `agent_connector_binding` 표에 `apply_due_at` 을 더한다
- `docs/connectors.md`: 바인딩 `READY` 정의와 「붙인 바인딩은 늘 `PENDING` 이고 대개 재시작 대기다」, 「관리자 반영 완료」 절을 고친다
- `docs/flow.md`: 붙이기 시퀀스에 `reload_pending` 과 예약 확인을 그리고, 관리자 재시작 분기는 `restart_required` 인 경우로 남긴다

## 검증

```bash
# cwd: 저장소 root
(cd backend && ./gradlew test --tests 'com.bifos.assistant.connector.*' --tests '*HttpHermesConnectorClientTest')
(cd backend && ./gradlew qualityCheck)
node scripts/check-migration-versions.mjs
```

- 모두 종료 코드 0. backend 전체와 MySQL 마이그레이션 검사는 PR CI 가 돌린다

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/resources/db/migration/V20261007081400__connector_binding_apply_due.sql` | 신규 |
| `backend/src/main/java/com/bifos/assistant/connector/domain/ConnectorBinding.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/hermes/HermesConnectorClient.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/hermes/HttpHermesConnectorClient.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/connector/application/ConnectorBindingProperties.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/connector/application/ConnectorBindingService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/connector/application/ConnectorBindingApplier.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/connector/infra/ConnectorBindingRepository.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/connector/application/ConnectorPolicyService.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/connector/**` | 수정 |
| `backend/src/main/resources/application.yml` | 수정 |
| `backend/src/test/resources/application-test.yml` | 수정 |
| `backend/src/test/java/com/bifos/assistant/connector/ConnectorBindingTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/connector/ConnectorBindingServiceTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/hermes/HttpHermesConnectorClientTest.java` | 수정 |
| `docs/backend/connector-install.md` | 수정 |
| `docs/backend/schema/connector.md` | 수정 |
| `docs/connectors.md` | 수정 |
| `docs/flow.md` | 수정 |
