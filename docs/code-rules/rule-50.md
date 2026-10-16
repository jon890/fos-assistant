# rule-50

엔티티와 Flyway를 같이 바꾸고 적용된 마이그레이션은 수정하지 않는다.

검사: [MySQL 마이그레이션](../../backend/src/test/java/com/bifos/assistant/MysqlMigrationTest.java), [적용 파일 불변](../../test/unit/migration-immutable.test.ts), [마이그레이션 버전](../../scripts/checks/check-migration-versions.mjs), [리뷰](../../.github/workflows/code-review-prompt.txt)

## 까닭

엔티티 테스트만 통과하고 실제 Flyway 스키마 검증에서 기동이 실패하는 일을 막는다.

## 예외와 검토 범위

이미 적용된 파일은 주석도 고치지 않는다.
H2에 없는 함수는 Java 마이그레이션으로 처리한다.
Lob 문자열의 길이는 columnDefinition으로 정한다.
버전과 DDL·DML 분리는 data-schema가 갖는다.

## 이 저장소의 사례

[MysqlMigrationTest.java](../../backend/src/test/java/com/bifos/assistant/MysqlMigrationTest.java)에서 확인한다.
MysqlMigrationTest가 Flyway 스키마와 엔티티를 실제 MySQL에서 대조한다.

책임과 실행 절차는 [data-schema.md](../../backend/docs/data-schema.md)에서 이어 읽는다.
