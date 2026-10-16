# rule-1

auditing은 허용된 필드와 기존 Clock provider만 쓴다.

검사: [AUDITING_LISTENERS_AND_CLOCK](../../backend/src/test/java/com/bifos/assistant/architecture/ArchitectureRules.java)

## 까닭

저장 시각의 자동 채움이 업무 시각이나 bulk 갱신 계약을 바꾸지 않게 한다.

## 예외와 검토 범위

CreatedAtEntity/AuditedEntity의 순수 저장 시각만 허용한다. 엔티티별 상속과 업무 시각 제외 목록은
[ADR-20261016 / jpa-auditing-base](../../backend/docs/adr/ADR-20261016-jpa-auditing-base.md)가 갖는다.
callback을 거치지 않는 JPQL/native bulk는 시각을 명시적으로 갱신한다.
실제 callback과 마이크로초 정밀도는 H2와 MySQL 저장 테스트가 함께 확인한다.

## 이 저장소의 사례

[JpaAuditingConfig.java](../../backend/src/main/java/com/bifos/assistant/shared/config/JpaAuditingConfig.java)에서 확인한다.
JpaAuditingConfig는 주입된 Clock을 auditing provider에 연결한다.
