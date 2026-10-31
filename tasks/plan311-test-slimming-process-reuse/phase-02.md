# Phase 02. 남은 상속 재실행을 끊고 커넥터 MySQL 검사를 공유 컨텍스트로 옮긴다

**Execution profile**: standard

## 목표

`FinancialConnectorProducerRoundTripTest` 와 `ConnectorExecutionClaimMvcTest` 가 부모 검사 71건을 다시 도는 것을 끊고, `connector/**` 의 MySQL 검사 클래스를 `@MysqlIntegrationTest` 와 `@MysqlCase` 로 옮긴다.
추정으로 unit 이 약 1분, MySQL 이 컨텍스트 기동 약 40초와 금융·guard 재실행 약 4분 줄어든다.

**범위 외**: 프로세스 수명(phase 01).

## 컨텍스트

**근거 문서**: `backend/docs/adr/ADR-20261007-test-context-base.md` 의 「MySQL 검사」, `backend/docs/data-schema.md` 의 「실제 MySQL 검사를 통과해야 한다」

- 앞서 머지된 MySQL 정리가 `@MysqlIntegrationTest`, `@MysqlCase`, `MysqlCasesOnly` 를 만들었다. 그 쓰는 법을 따른다.
- 선행: 금융 claim 상한 변경(#611)이 main 에 머지돼 있어야 한다.
- 대상 클래스는 `git grep -n 'Tag("mysql")' -- backend/src/test/java/com/bifos/assistant/connector` 로 찾는다. 마이그레이션 검사(`*MysqlMigrationTest`)는 이 phase 가 건드리지 않는다.

## 의도 메모

- 자식이 부모 구체 클래스를 상속하지 않고 공용 기반만 상속하게 바꾼다. 부모의 검사는 부모에서 그대로 돈다.
- MySQL 에 남기는 검사는 행 잠금(금융 claim 경합, 바인딩·연결 잠금)에 기대는 것이다. 나머지는 H2 에서만 돈다.
- 덜 잡게 되는 것: 잠금 밖의 경로에서 MySQL 만 거절하는 쿼리. `RepositoryQueryMysqlTest` 와 `MysqlMigrationTest` 가 대부분 본다.

## 작업 항목

### 1. 상속 끊기

두 클래스가 부모의 공용 기반 클래스만 상속하도록 부모의 fixture 를 기반 클래스로 올린다.

### 2. 커넥터 MySQL 클래스 전환

`ConnectorExecutionClaimMysqlTest`, `FinancialConnectorProducerMysqlRoundTripTest`, `ConnectorGuardBindingMysqlRoundTripTest`, `ConnectorBindingServiceMysqlLockTest`, `ConnectorConnectionServiceMysqlLockTest`, `ConnectorActionExecutionTest`, `ConnectorConditionalSnapshotTest` 의 `@DynamicPropertySource` 를 지우고 `@MysqlIntegrationTest` 로 바꾼다. 상위 클래스의 잠금 검사에 `@MysqlCase(ROW_LOCK)` 를 단다.

## 검증

```bash
cd backend && ./gradlew test --tests '*ConnectorExecutionClaim*' --tests '*FinancialConnectorProducer*'
scripts/checks/check-mysql-migration.sh
```

## 변경 파일

| 파일 | 변경 |
| --- | --- |
| `backend/src/test/java/com/bifos/assistant/connector/**` | 수정 |
