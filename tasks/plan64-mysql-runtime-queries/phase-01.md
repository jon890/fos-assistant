# Phase 01. 저장소 쿼리를 실제 MySQL 에서 한 번씩 실행한다

**Execution profile**: deep

## 목표

Flyway 로 만든 실제 MySQL 스키마에서 모든 저장소 인터페이스가 선언한 메서드를 한 번씩 실행하는 검사를 만든다.
H2 로 도는 테스트는 MySQL 만 거절하는 쿼리(정렬 규칙 섞임, 없는 함수, 예약어, 문법)를 통과시킨다.

**범위 외**: 정렬 규칙을 통일하기 전 스키마에서 이 검사가 실패함을 단언하는 일은 phase 02 가 한다. 기존 테스트를 MySQL 로 옮기지 않는다.

## 컨텍스트

- `mysql` 태그가 붙은 테스트는 `./gradlew test` 에서 빠지고 `./gradlew mysqlMigrationTest` 가 돌린다(`backend/build.gradle.kts`). 그 태스크는 저장소 root 의 `scripts/check-mysql-migration.sh` 가 일회용 MySQL 8.4 를 `--collation-server=utf8mb4_unicode_ci` 로 띄운 뒤 부른다. 스크립트는 받은 인자를 Gradle 로 넘긴다
- 실제 MySQL 로 Spring 문맥을 띄우는 본보기는 `backend/src/test/java/com/bifos/assistant/MysqlMigrationTest.java` 다. `@Tag("mysql")`, `@SpringBootTest`, `@ActiveProfiles("test")` 를 붙이고 `@DynamicPropertySource` 에서 `MysqlTestDatabase.create()` 로 빈 데이터베이스를 받아 `spring.datasource.*`, `spring.flyway.enabled=true`, `spring.jpa.hibernate.ddl-auto=validate` 를 준다. 이 phase 의 테스트도 같은 값을 쓴다
- 저장소 인터페이스는 `backend/src/main/java` 아래 `*Repository.java` 26개이고 모두 Spring Data JPA 저장소다. 선언 메서드의 인자 타입은 `Long`, `String`, `Instant`, `UUID`, `Pageable`, enum, 그리고 `Collection` 과 `List` 에 그 타입을 담은 것이다
- 저장소 인터페이스 밖에서 쿼리를 만드는 곳은 `backend/src/main/java/com/bifos/assistant/memory/infra/MemoryQueries.java` 의 `Specification` 셋(`readableBy`, `listedFor`, `injectable`)뿐이다. `MemoryRepository` 가 `JpaSpecificationExecutor<Memory>` 로 실행한다. `EntityManager`, `JdbcTemplate`, 네이티브 쿼리, `@Formula` 는 main 코드에 없다. Java 마이그레이션(`db.migration` 패키지)의 JDBC 는 `MysqlMigrationTest` 가 이미 실제 MySQL 에서 돌린다
- MySQL 은 줄이 하나도 없어도 정렬 규칙이 다른 칸의 비교를 오류 1267 로 거절한다. 그래서 빈 표로 실행해도 잡힌다

**근거 문서**: `backend/AGENTS.md` 의 「저장소 쿼리는 실제 MySQL 에서도 실행한다」, `docs/backend/schema/README.md` 의 「마이그레이션 작성 규칙」

## 의도 메모

- backend 테스트 전체를 MySQL 로 옮기지 않는다. 그대로 돌리면 1754개 가운데 705개가 실패하고 까닭이 쿼리가 아니라 테스트 구조(FK 가 있는 스키마에서의 정리 순서, 문맥 여러 개의 접속 수)다
- **실행하지 못한 메서드를 조용히 건너뛰지 않는다.** 인자를 만들지 못하면 검사가 실패한다. 꼭 빼야 하는 메서드는 테스트의 제외 목록에 까닭과 함께 적고, 목록에 적힌 메서드가 실제로 없으면 그것도 실패시킨다
- 쿼리가 실행됐는지만 본다. 결과 값을 단언하지 않는다
- 실행한 것이 남지 않게 메서드마다 트랜잭션을 열고 되돌린다. 수정 쿼리와 `@Lock` 메서드는 트랜잭션이 있어야 돈다
- 하위 에이전트는 `orca` 명령을 쓰지 않는다

## 작업 항목

### 1. `backend/src/test/java/com/bifos/assistant/testsupport/RepositoryQuerySweep.java` 신규

저장소 메서드를 찾아 실행하는 도우미다. Spring 빈이 아니라 테스트가 직접 만드는 클래스로 둔다.

- 생성자 인자: `ApplicationContext`, `PlatformTransactionManager`
- `Result run(Set<String> excluded)`: 문맥의 `RepositoryFactoryInformation` 빈에서 저장소 인터페이스를 얻고(`getRepositoryInformation().getRepositoryInterface()`), 패키지가 `com.bifos.assistant` 로 시작하는 인터페이스의 `getDeclaredMethods()` 가운데 `default`, `static`, 합성 메서드가 아닌 것을 모두 실행한다. 이름은 `인터페이스 단순 이름.메서드 이름` 이다
- 인자는 파라미터의 제네릭 타입으로 만든다. `Long` 과 `long` 은 `1L`, `Integer` 와 `int` 는 `1`, `boolean` 은 `false`, `String` 은 `"x"`, `Instant` 는 고정한 시각, `UUID` 는 고정 값, `Pageable` 은 `PageRequest.of(0, 1)`, enum 은 첫 상수, `Collection`, `List`, `Set` 은 원소 타입의 값 하나를 담은 것이다. `@EmbeddedId` 로 쓰는 id 클래스 같은 그 밖의 타입이 선언 메서드에 있으면 그 타입의 값을 만드는 규칙을 여기에 더한다
- 메서드마다 `TransactionTemplate` 으로 트랜잭션을 열어 프록시 메서드를 부르고 `setRollbackOnly()` 로 되돌린다. 반환이 `Stream` 이면 트랜잭션 안에서 닫는다
- `Result` 는 record 다. `List<String> executed`, `List<Failure> failures`, `List<String> unsupported`(인자를 만들지 못한 메서드와 그 타입), `Set<String> staleExclusions`(제외 목록에 있는데 찾지 못한 이름). `Failure` 는 `String method`, `Throwable cause` 를 가진 record 다. 예외는 `InvocationTargetException` 을 벗겨 담는다
- 실패한 쿼리가 뒤의 메서드를 막지 않는다. 모두 실행한 뒤 결과를 준다

### 2. `backend/src/test/java/com/bifos/assistant/RepositoryQueryMysqlTest.java` 신규

`MysqlMigrationTest` 와 같은 애너테이션과 `@DynamicPropertySource` 를 쓴다. 테스트는 넷이다.

| 테스트 | 단언 |
| --- | --- |
| 모든 저장소 메서드가 실제 MySQL 에서 실행된다 | `failures` 가 비었다. 실패 메시지에 메서드 이름과 가장 안쪽 원인의 메시지를 낸다 |
| 실행하지 못한 메서드가 없다 | `unsupported` 와 `staleExclusions` 가 비었다. 제외 목록 상수 `EXCLUDED` 는 빈 `Set` 으로 시작하고, 더할 때는 그 줄에 까닭을 주석으로 적는다 |
| 저장소를 빠뜨리지 않았다 | `executed` 가 가리키는 인터페이스 수가 문맥의 `com.bifos.assistant` 저장소 빈 수와 같고, `executed` 에 `ExecutionEventRepository.findUnscheduledChildren` 이 있다 |
| `MemoryQueries` 의 조건이 실제 MySQL 에서 실행된다 | `MemoryRepository.findAll(spec)` 을 `readableBy(1L, 1L)`, `readableBy(1L, null)`, `listedFor(1L, 1L)`, `injectable(1L, 1L, <MemoryRetrieval 첫 상수>, null, Set.of())`, `injectable(1L, 1L, <첫 상수>, Set.of("core"), Set.of())`, `injectable(1L, 1L, <첫 상수>, Set.of("core"), Set.of("core"))` 로 부른다. 예외가 없다 |

`DisplayName` 은 한국어로 쓴다. 주석과 Javadoc 도 한국어다.

### 3. 설명 고치기

- `backend/build.gradle.kts`: `tasks.test` 의 주석과 `mysqlMigrationTest` 의 Javadoc, `description` 이 마이그레이션만 말한다. 저장소 쿼리도 돌린다고 고친다. 태스크 이름은 바꾸지 않는다
- `scripts/check-mysql-migration.sh`: 머리 주석에 저장소 쿼리 검사도 돌린다고 한 줄 더한다. 파일 이름은 바꾸지 않는다
- `backend/src/test/java/com/bifos/assistant/testsupport/MysqlTestDatabase.java`: Javadoc 은 이미 「`mysql` 태그가 붙은 검사만 쓴다」 라고 적어 고칠 것이 없다. 고치지 않는다
- `.github/workflows/ci.yml`: `backend` job 의 단계 이름 `실제 MySQL 마이그레이션 검사` 를 `실제 MySQL 검사` 로 바꾸고 위 주석에 저장소 쿼리를 더한다. job 이름 `backend` 는 필수 검사 이름이라 바꾸지 않는다

### 4. 문서

`backend/AGENTS.md` 의 「저장소 쿼리는 실제 MySQL 에서도 실행한다」, `docs/backend/schema/README.md`, `AGENTS.md` 는 이미 고쳐져 있다. 구현이 그 글과 달라지면 이 phase 의 커밋에서 그 글을 고친다. 클래스 이름 `RepositoryQueryMysqlTest`, `RepositoryQuerySweep` 은 그 글에 적힌 대로 쓴다.

## 검증

Docker 가 떠 있어야 한다.

```bash
# cwd: 저장소 root
scripts/check-mysql-migration.sh --tests '*RepositoryQueryMysqlTest'
scripts/check-mysql-migration.sh
( cd backend && ./gradlew test --tests '*ArchitectureRulesTest' && ./gradlew qualityCheck )
scripts/check-public-safe.sh
```

- 첫 명령: 테스트 넷이 통과한다. `backend/build/test-results/mysqlMigrationTest/` 의 XML 에서 `RepositoryQueryMysqlTest` 의 `tests="4"`, 실패 0 을 확인한다
- 둘째 명령: 기존 `mysql` 태그 테스트와 함께 모두 통과한다. 걸린 시간을 적어 둔다
- 잡는지 확인: `ExecutionEventRepository.findUnscheduledChildren` 의 JPQL 에 없는 함수 호출(예: `function('NO_SUCH_FN', event.id) = 1`)을 잠시 더해 첫 명령이 그 메서드 이름으로 실패하는 것을 본 뒤 되돌린다. 되돌린 뒤 `git diff --stat backend/src/main` 이 비어 있어야 한다
- `./gradlew test` 는 `mysql` 태그를 빼므로 새 테스트가 MySQL 없이 실패하지 않는다

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/test/java/com/bifos/assistant/testsupport/RepositoryQuerySweep.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/RepositoryQueryMysqlTest.java` | 신규 |
| `backend/build.gradle.kts` | 수정 |
| `scripts/check-mysql-migration.sh` | 수정 |
| `.github/workflows/ci.yml` | 수정 |
| `backend/AGENTS.md` | 수정 |
| `docs/backend/schema/README.md` | 수정 |
| `AGENTS.md` | 수정 |
