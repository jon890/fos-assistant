# rule-26

저장 enum은 domain.type에 둔다.

검사: [ENUMERATED_FIELDS_USE_DOMAIN_TYPE](../../backend/src/test/java/com/bifos/assistant/architecture/ArchitectureRules.java)

## 까닭

저장되는 값을 바꿀 때 마이그레이션을 함께 판단할 수 있게 모아 둔다.

## 예외와 검토 범위

비저장 enum은 application.model에, ErrorCode는 shared.error에 둔다.
이 두 조건은 리뷰로 확인한다.

## 이 저장소의 사례

[AgentMemoryCollectionChangeType.java](../../backend/src/main/java/com/bifos/assistant/agent/domain/type/AgentMemoryCollectionChangeType.java)에서 확인한다.
AgentMemoryCollectionChangeType은 저장되는 enum이므로 domain.type에 둔다.
