# rule-27

domain.type은 위 역할에 의존하지 않는다.

검사: [DOMAIN_TYPE_DEPENDS_ON_NOTHING_ABOVE](../../backend/src/test/java/com/bifos/assistant/architecture/ArchitectureRules.java)

## 까닭

저장 enum이 서비스와 HTTP 구현을 끌어들이지 않게 한다.

## 예외와 검토 범위

위 역할의 정확한 패키지 선택은 검사 코드가 갖는다.

## 이 저장소의 사례

[AgentMemoryCollectionChangeType.java](../../backend/src/main/java/com/bifos/assistant/agent/domain/type/AgentMemoryCollectionChangeType.java)에서 확인한다.
AgentMemoryCollectionChangeType은 application이나 presentation 타입을 필요로 하지 않는다.
