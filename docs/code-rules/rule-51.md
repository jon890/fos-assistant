# rule-51

저장소 쿼리는 실제 MySQL에서도 실행한다.

검사: [MySQL 저장소 쿼리](../../backend/src/test/java/com/bifos/assistant/RepositoryQueryMysqlTest.java), [리뷰](../../.github/workflows/code-review-prompt.txt)

## 까닭

H2가 통과시킨 쿼리가 MySQL에서 실패하는 것을 발견한다.

## 예외와 검토 범위

저장소 메서드는 자동 수집한다.
지원하지 않는 인자는 RepositoryQuerySweep에 추가한다.
Specification·EntityManager·JdbcTemplate 등 동적 쿼리는 RepositoryQueryMysqlTest에 직접 더한다.

## 이 저장소의 사례

[RepositoryQuerySweep.java](../../backend/src/test/java/com/bifos/assistant/testsupport/RepositoryQuerySweep.java)에서 확인한다.
RepositoryQuerySweep는 저장소 메서드를 실행할 인자를 준비한다.

책임과 실행 절차는 [data-schema.md](../../backend/docs/data-schema.md)에서 이어 읽는다.
