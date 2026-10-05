# Phase 01. 바인딩 표와 마이그레이션, 엔티티와 저장소

**Execution profile**: standard

## 목표

에이전트와 연결의 다대다 바인딩 표를 만들고, 이미 있는 연결마다 옛 커넥터 에이전트와의 바인딩을 채운다.
연결에 보관 파일 상태 칸을 더한다. 이 phase 는 표와 엔티티만 더하고 서비스의 동작은 바꾸지 않는다.

**범위 외**: 서비스가 바인딩을 쓰는 일(phase 02, 03). 연결의 `agent_id`, `restart_required`, `desired_enabled` 칸은 이 plan 에서 지우지 않는다. phase 03 이 엔티티에서 매핑만 빼고, 칸을 지우는 마이그레이션은 옛 커넥터 에이전트를 정리하는 다음 작업이 한다. 같은 배포에서 칸을 지우면 이전 이미지로 되돌릴 때 DB 복원이 필요하기 때문이다.

## 컨텍스트

**근거 문서**: `docs/adr/ADR-083-커넥터는-사용자가-한-번-연결하고-자기-에이전트에-여럿-붙여-그-에이전트가-도구를-직접-부른다.md`, `docs/backend/schema/connector.md`, `docs/backend/schema/README.md` 의 「마이그레이션 작성 규칙」

- 결정: `docs/adr/ADR-083-커넥터는-사용자가-한-번-연결하고-자기-에이전트에-여럿-붙여-그-에이전트가-도구를-직접-부른다.md`
- 지금 표: `backend/src/main/resources/db/migration/V38__connector_connection.sql`. `connector_connection` 은 `uk_connector_connection_user_connector (user_id, connector_id)`, `uk_connector_connection_agent (agent_id)`, `fk_connector_connection_agent` 를 갖는다
- 표 문서: `docs/backend/schema/connector.md` 의 「connector_connection」
- 마이그레이션 규칙: `docs/backend/schema/README.md` 의 「마이그레이션 작성 규칙」. DDL 과 DML 을 한 파일에 섞지 않는다. 새 표에는 `ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci` 를 적는다. `test/unit/migration-collation.test.ts` 가 본다
- 엔티티: `backend/src/main/java/com/bifos/assistant/connector/domain/ConnectorConnection.java`, 상태 enum `connector/domain/type/ConnectionStatus.java`
- 저장소: `backend/src/main/java/com/bifos/assistant/connector/infra/ConnectorConnectionRepository.java`
- 시험의 본보기: `backend/src/test/java/com/bifos/assistant/connector/ConnectorConnectionMigrationTest.java` 는 H2 에 그 앞 번호까지 마이그레이션하고 행을 넣은 뒤 다음 번호를 적용한다. 실제 MySQL 은 `backend/src/test/java/com/bifos/assistant/MysqlMigrationTest.java` 와 `scripts/check-mysql-migration.sh` 가 본다
- 시험 DB 는 엔티티로 스키마를 만든다. 그래서 엔티티와 마이그레이션의 칸 이름과 제약이 같아야 한다

## 의도 메모

- 번호는 이 계획서에서 V77, V78 이다. V74 부터 V76 까지는 다른 작업이 쓴다. 머지 직전 main 의 다음 번호로 옮긴다(`docs/backend/schema/README.md` 와 AGENTS.md 의 「이미 적용된 마이그레이션 파일은 고치지 않는다」)
- 바인딩 상태는 연결 상태와 따로 둔다. 연결은 「값이 확인됐는가」, 바인딩은 「그 profile 에 설치되고 반영됐는가」 다. 재시작 대기는 profile 마다라 바인딩에 둔다
- 떼면 바인딩 행을 지운다. 떼기는 재시작을 기다리지 않으므로 남길 상태가 없다. 이력은 `connector_action` 이 갖는다
- `DISCONNECTED` 연결은 바인딩을 만들지 않는다. 그 연결의 옛 에이전트는 이미 꺼져 있다

## 작업 항목

### 1. `backend/src/main/resources/db/migration/V77__connector_binding.sql`

DDL 만 둔다.

```sql
CREATE TABLE agent_connector_binding (
    id BIGINT NOT NULL AUTO_INCREMENT,
    agent_id BIGINT NOT NULL,
    connection_id BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL,
    restart_required BOOLEAN NOT NULL DEFAULT FALSE,
    desired_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    checked_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_agent_connector_binding (agent_id, connection_id),
    CONSTRAINT fk_agent_connector_binding_agent FOREIGN KEY (agent_id) REFERENCES agent(id),
    CONSTRAINT fk_agent_connector_binding_connection FOREIGN KEY (connection_id) REFERENCES connector_connection(id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE INDEX idx_agent_connector_binding_connection ON agent_connector_binding (connection_id);

ALTER TABLE connector_connection ADD COLUMN vault_stored BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE connector_connection MODIFY COLUMN agent_id BIGINT NULL;
```

`agent_id` 를 비워도 되게 하는 것은 phase 02 가 에이전트 없이 연결을 만들기 때문이다. 유일 제약과 FK 는 남긴다. NULL 은 유일 제약에 걸리지 않는다. 이 문장을 H2 와 `scripts/check-mysql-migration.sh` 가 모두 받는지 확인한다.

파일 머리에 무엇을 왜 바꾸는지 주석 두 줄을 둔다(ADR-083).

### 2. `backend/src/main/resources/db/migration/V78__connector_binding_backfill.sql`

DML 만 둔다. `DISCONNECTED` 가 아닌 연결마다 그 연결의 `agent_id` 로 바인딩 한 줄을 만든다. `status`, `restart_required`, `desired_enabled`, `checked_at`, `created_at`, `updated_at` 은 연결의 값을 그대로 옮긴다.
옛 연결의 `vault_stored` 는 거짓으로 둔다. 연결 확인이 옛 profile 의 값을 보관 파일로 옮길 때 참이 된다(phase 02).

### 3. `connector/domain/ConnectorBinding.java` 와 `connector/domain/type/BindingStatus.java`

- `BindingStatus` 는 `PENDING`, `READY`
- `ConnectorBinding` 은 `agent_connector_binding` 의 엔티티다. `@ManyToOne(fetch = LAZY)` 로 `Agent agent`(`agent_id`)와 `ConnectorConnection connection`(`connection_id`)을 갖는다. 표와 같은 유일 제약을 `@Table(uniqueConstraints = ...)` 에 적는다
- 메서드: `static ConnectorBinding pending(Agent agent, ConnectorConnection connection, Instant now)`, `beginInstall(Instant now)`(`desiredEnabled` 거짓, `PENDING`), `installed(boolean restartRequired, Instant now)`(`desiredEnabled` 참, 재시작 필요는 논리 OR 로 누적, 상태는 `PENDING`), `ready(Instant now)`(`READY`, 재시작 필요 거짓), `pending(Instant now)`, `markRestartRequired(boolean)`
- 옛 커넥터 에이전트(`agent.connectorManaged()`)의 바인딩은 지금 `ConnectorConnection.ready`, `pending`, `disableAgent` 가 하던 것처럼 `ready` 에서 에이전트를 켜고, `pending` 에서 에이전트를 끄고 사진 받기를 내린다. 다른 에이전트의 바인딩은 에이전트를 건드리지 않는다
- 비밀 칸의 값을 받는 메서드를 두지 않는다

### 4. `ConnectorConnection` 의 칸

`vaultStored` 칸(`vault_stored`)과 `markVaultStored()` 를 더한다. `agent` 의 `@JoinColumn` 을 `nullable = true` 로 바꾼다. 다른 칸과 메서드는 이 phase 에서 그대로 둔다.

### 5. `connector/infra/ConnectorBindingRepository.java`

`JpaRepository<ConnectorBinding, Long>` 에 아래를 둔다. 이름은 phase 02 와 03 이 그대로 쓴다.

- `List<ConnectorBinding> findByAgentId(Long agentId)`
- `List<ConnectorBinding> findByConnectionId(Long connectionId)`
- `Optional<ConnectorBinding> findByAgentIdAndConnectionId(Long agentId, Long connectionId)`
- `boolean existsByAgentId(Long agentId)`
- `List<ConnectorBinding> findByConnectionUserIdIn(Collection<Long> userIds)`

`RepositoryQueryMysqlTest` 가 저장소 쿼리를 실제 MySQL 에서 돌린다면 새 메서드를 그 시험에 더한다(`backend/AGENTS.md` 의 「저장소 쿼리는 실제 MySQL 에서도 실행한다」).

### 6. `docs/backend/schema/connector.md`

「agent_connector_binding」 절을 새로 두고 칸, 유일 제약, FK, 떼면 행을 지운다는 것, V78 이 옛 연결의 바인딩을 채운다는 것을 적는다.
「connector_connection」 에 `vault_stored` 를 더한다. 세 칸(`agent_id`, `restart_required`, `desired_enabled`)은 쓰지 않는 칸으로 남는다고 적는다. 칸을 지우는 마이그레이션은 옛 커넥터 에이전트를 정리할 때 둔다.

### 7. 이 phase 를 검증하는 시험

- `backend/src/test/java/com/bifos/assistant/connector/ConnectorBindingMigrationTest.java`(신규): H2 에 V76 까지 마이그레이션하고 연결 셋(`READY` 이면서 재시작 대기 없음, `PENDING` 이면서 재시작 대기, `DISCONNECTED`)을 넣은 뒤 V78 까지 적용한다. 앞의 둘은 같은 상태와 칸의 바인딩이 하나씩 생기고 `DISCONNECTED` 는 바인딩이 없다. 모든 연결의 `vault_stored` 가 거짓이다
- `backend/src/test/java/com/bifos/assistant/connector/ConnectorBindingTest.java`(신규): 옛 커넥터 에이전트의 바인딩은 `ready` 가 에이전트를 켜고 `pending` 이 끄며 사진 받기를 내린다. 일반 에이전트의 바인딩은 에이전트의 `enabled` 를 바꾸지 않는다. `installed` 는 재시작 필요를 누적한다
- `MysqlMigrationTest` 가 새 표의 정렬 규칙을 단언하는 목록을 갖고 있으면 새 표를 더한다
- `backend/src/test/java/com/bifos/assistant/architecture/StoredEnumNamesTest.java`(수정): `STORED_ENUMS` 에 `BindingStatus` 의 `PENDING`, `READY` 를 더한다. 저장되는 enum 은 `domain.type` 아래에 둔다는 이 검사의 규칙을 따른다

## 검증

```bash
cd backend && ./gradlew test --tests '*ConnectorBindingMigrationTest' --tests '*ConnectorBindingTest' --tests '*ConnectorConnectionMigrationTest' --tests '*StoredEnumNamesTest'
cd backend && ./gradlew test
scripts/check-mysql-migration.sh
node --test 'test/unit/**/*.test.ts'
```

모두 종료 코드 0 이어야 한다. `scripts/check-mysql-migration.sh` 는 Docker 로 MySQL 8.4 를 띄워 마이그레이션과 저장소 쿼리를 돌린다.

## 변경 파일

| 파일 | 변경 |
| --- | --- |
| `backend/src/main/resources/db/migration/V77__connector_binding.sql` | 신규 |
| `backend/src/main/resources/db/migration/V78__connector_binding_backfill.sql` | 신규 |
| `backend/src/main/java/com/bifos/assistant/connector/domain/ConnectorBinding.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/connector/domain/type/BindingStatus.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/connector/domain/ConnectorConnection.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/connector/infra/ConnectorBindingRepository.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/connector/ConnectorBindingMigrationTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/connector/ConnectorBindingTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/MysqlMigrationTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/architecture/StoredEnumNamesTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/RepositoryQueryMysqlTest.java` | 수정 |
| `docs/backend/schema/connector.md` | 수정 |
