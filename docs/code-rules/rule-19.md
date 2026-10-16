# rule-19

Instant.now() 대신 주입된 Clock을 쓴다.

검사: [NO_DIRECT_INSTANT_NOW](../../backend/src/test/java/com/bifos/assistant/architecture/ArchitectureRules.java)

## 까닭

시각을 주입받아야 테스트가 시각을 고정할 수 있다.

## 예외와 검토 범위

Instant.now(Clock)은 허용한다.
인자 없는 호출만 막는다.

## 이 저장소의 사례

[JpaAuditingConfig.java](../../backend/src/main/java/com/bifos/assistant/shared/config/JpaAuditingConfig.java)에서 확인한다.
JpaAuditingConfig는 고정 가능한 Clock에서 생성 시각을 얻는다.
