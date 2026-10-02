# Phase 02. 정렬 규칙을 통일하기 전 스키마에서 검사가 실패함을 단언한다

**Execution profile**: standard

## 목표

V57 까지만 올린 실제 MySQL 스키마에서 저장소 쿼리 검사가 `ExecutionEventRepository.findUnscheduledChildren` 을 오류 1267 로 잡는다는 것을 테스트로 남긴다.
검사가 실제로 그 결함을 잡는다는 증거가 없으면, 검사가 통과하는 것이 쿼리가 맞아서인지 검사가 보지 못해서인지 알 수 없다.

**범위 외**: 저장소 쿼리 검사 자체는 phase 01 이 만들었다.

## 컨텍스트

- 2026-10-02 에 `SubagentUsageReconciler.discover` 가 부르는 `ExecutionEventRepository.findUnscheduledChildren` 이 운영 MySQL 에서 오류 1267(Illegal mix of collations)로 실패했다. 그 JPQL 은 `subagent_usage_job` 과 `agent_execution`, `execution_event` 의 문자열 칸을 `=` 로 비교한다
- V57 까지의 스키마에서 `execution_event` 와 `subagent_usage_job` 은 서버 기본값 `utf8mb4_unicode_ci` 이고 `agent_execution` 은 `utf8mb4_0900_ai_ci` 다. V58 부터 V65 까지가 여덟 표를 `utf8mb4_0900_ai_ci` 로 맞췄다. 그 여덟 마이그레이션은 정렬 규칙만 바꿔 V57 스키마도 지금의 엔티티로 `ddl-auto=validate` 를 통과한다
- phase 01 이 만든 `backend/src/test/java/com/bifos/assistant/testsupport/RepositoryQuerySweep.java` 의 `run(Set<String> excluded)` 가 `Result(executed, failures, unsupported, staleExclusions)` 를 준다. `Failure` 는 `method` 와 `cause` 를 가진다
- 문맥을 실제 MySQL 로 띄우는 방법은 `backend/src/test/java/com/bifos/assistant/RepositoryQueryMysqlTest.java` 와 같다. 거기에 `spring.flyway.target` 을 `57` 로 더한다
- `backend/src/test/java/com/bifos/assistant/CollationUnifyMysqlMigrationTest.java` 가 V57 과 V65 를 상수로 적어 두는 본보기다

**근거 문서**: `backend/AGENTS.md` 의 「저장소 쿼리는 실제 MySQL 에서도 실행한다」, `docs/backend/schema/README.md` 의 「모든 표의 정렬 규칙은 `utf8mb4_0900_ai_ci` 하나다」

## 의도 메모

- 실패 목록 전체를 고정된 값으로 단언하지 않는다. V57 스키마에서 정렬 규칙이 섞여 실패하는 메서드가 더 있을 수 있고, 저장소 메서드가 늘면 목록이 바뀐다. 「`findUnscheduledChildren` 이 1267 로 실패한다」 와 「실패는 모두 1267 이다」 만 단언한다
- V58 뒤에 칸을 더하는 마이그레이션이 생기면 V57 스키마가 엔티티 검증을 통과하지 못해 이 테스트의 문맥이 뜨지 않는다. 그때는 이 테스트의 `ddl-auto` 를 `none` 으로 바꾸고, 없는 칸 때문에 실패하는 메서드가 생겨 「실패는 모두 1267」 단언을 유지할 수 없으면 그 단언을 뺀다. 이 판단을 클래스 Javadoc 에 적어 둔다
- 하위 에이전트는 `orca` 명령을 쓰지 않는다

## Blocked 조건

- `backend/src/test/java/com/bifos/assistant/testsupport/RepositoryQuerySweep.java` 가 없다 → `PHASE_BLOCKED: 저장소 쿼리 검사가 없다` 출력 후 종료

## 작업 항목

### 1. `backend/src/test/java/com/bifos/assistant/CollationMixQueryMysqlTest.java` 신규

`@Tag("mysql")`, `@SpringBootTest`, `@ActiveProfiles("test")`. `@DynamicPropertySource` 는 `RepositoryQueryMysqlTest` 와 같은 값에 `spring.flyway.target=57` 을 더한다.

| 테스트 | 단언 |
| --- | --- |
| 통일하기 전 스키마까지만 올라갔다 | `Flyway` 빈의 `info().current().getVersion().getVersion()` 이 `57` 이다. `information_schema.tables` 에서 `execution_event` 의 `table_collation` 이 `utf8mb4_unicode_ci`, `agent_execution` 이 `utf8mb4_0900_ai_ci` 다 |
| 저장소 쿼리 검사가 정렬 규칙이 섞인 쿼리를 잡는다 | `RepositoryQuerySweep.run(Set.of())` 의 `failures` 에 `method` 가 `ExecutionEventRepository.findUnscheduledChildren` 인 것이 있고, 그 `cause` 의 원인 사슬에 `SQLException` 이 있으며 `getErrorCode()` 가 `1267` 이다. `failures` 의 모든 항목이 같은 오류 코드다 |

### 2. 문서

`backend/AGENTS.md` 의 「저장소 쿼리는 실제 MySQL 에서도 실행한다」 가 이미 `CollationMixQueryMysqlTest` 를 적고 있다. 구현이 그 글과 달라지면 이 phase 의 커밋에서 고친다.

## 검증

Docker 가 떠 있어야 한다.

```bash
# cwd: 저장소 root
scripts/check-mysql-migration.sh --tests '*CollationMixQueryMysqlTest'
scripts/check-mysql-migration.sh
( cd backend && ./gradlew qualityCheck )
scripts/check-public-safe.sh
```

- 첫 명령: 테스트 둘이 통과한다. V57 스키마에서 실패한 메서드 이름 목록을 테스트 출력이나 XML 에서 읽어 보고에 적는다
- 둘째 명령: `mysql` 태그 테스트가 모두 통과한다. 걸린 시간을 적어 둔다

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/test/java/com/bifos/assistant/CollationMixQueryMysqlTest.java` | 신규 |
| `backend/AGENTS.md` | 수정 |
