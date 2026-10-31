# Phase 01. 데이터 마이그레이션 검사의 setup 을 클래스마다 한 번으로 줄인다

**Execution profile**: standard

## 목표

H2 `*MigrationTest` 와 MySQL 하위 클래스 18개가 메서드마다 새 DB 를 만들고 Flyway 를 끝까지 적용하는 setup 을 클래스마다 한 번으로 줄인다. 건당 약 5초가 든다.
추정으로 MySQL 이 약 2분, unit 이 약 0.3분 줄어든다.

**범위 외**: 검사 자체를 지우는 일(phase 02), MySQL 컨텍스트 통합(이미 끝남).

## 컨텍스트

**근거 문서**: `backend/docs/data-schema.md` 의 「실제 MySQL 검사를 통과해야 한다」, `backend/docs/adr/ADR-20261007-test-context-base.md` 의 「MySQL 검사」

- 본보기는 `SubagentUsageLedgerMigrationTest` 다. `@BeforeEach` 가 새 DB 생성, V54 까지 적용, 줄 넣기, 끝까지 적용을 매번 한다. 8건의 본문은 결과를 읽기만 하는데 반복에 42초가 걸린다.
- 대상은 줄을 바꾸는 메서드가 없는 클래스뿐이다. 찾는 방법은 `git grep -ln 'createDatabase' -- backend/src/test/java` 로 클래스를 모으고 본문의 쓰기 문(`INSERT`, `UPDATE`, `DELETE`)을 보는 것이다.

## 의도 메모

- 메서드가 서로의 결과를 바꾸지 않는다는 전제가 깨지면 순서에 따라 결과가 달라진다. 줄을 바꾸는 메서드가 있는 클래스는 그대로 둔다.
- `@TestInstance(PER_CLASS)` 와 `@BeforeAll` 로 옮기고, 클래스 끝에 DB 를 지우는 일은 하지 않는다(MySQL 컨테이너가 job 끝에 사라진다).

## 작업 항목

### 1. 읽기 전용 클래스의 setup 이동

읽기만 하는 `*MigrationTest` 클래스의 `@BeforeEach` 를 `@BeforeAll` 로 옮긴다.

### 2. 순서 독립 확인

옮긴 클래스마다 `@TestMethodOrder(MethodOrderer.Random.class)` 를 임시로 더해 두 번 돌려 순서에 기대지 않는지 본 뒤 지운다.

## 검증

```bash
cd backend && ./gradlew test --tests '*MigrationTest'
scripts/checks/check-mysql-migration.sh
```

## 변경 파일

| 파일 | 변경 |
| --- | --- |
| `backend/src/test/java/com/bifos/assistant/usage/SubagentUsageLedgerMigrationTest.java` | 수정 |
