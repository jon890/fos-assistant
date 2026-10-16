# rule-30

엔티티는 소유 domain.entity에 하나씩 둔다.

검사: [ENTITIES_IN_OWNED_ENTITY_PACKAGE](../../backend/src/test/java/com/bifos/assistant/architecture/ArchitectureRules.java)

## 까닭

저장 타입을 정책과 분리하고 기능이 소유한 위치에서 찾게 한다.

## 예외와 검토 범위

현재 강제 범위는 crypto와 memory다.
다른 기능의 기존 엔티티까지 옮겼다고 해석하지 않는다.
누락·중복도 검사한다.

## 이 저장소의 사례

[MemoryCollection.java](../../backend/src/main/java/com/bifos/assistant/memory/domain/entity/MemoryCollection.java)에서 확인한다.
MemoryCollection은 memory.domain.entity에서 저장 모델을 소유한다.

책임과 실행 절차는 [code-architecture.md](../../backend/docs/code-architecture.md)에서 이어 읽는다.
