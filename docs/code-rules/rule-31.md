# rule-31

실제 영속 ID는 엔티티와 함께 두고 entity의 역할을 섞지 않는다.

검사: [EMBEDDED_IDS_WITH_ENTITIES](../../backend/src/test/java/com/bifos/assistant/architecture/ArchitectureRules.java)

## 까닭

영속 ID를 소유 엔티티와 함께 두고 entity를 일반 값 모음으로 쓰지 않는다.

## 예외와 검토 범위

EmbeddedId의 필드와 getter 및 IdClass의 실제 타입을 확인한다.
참조되지 않은 Embeddable은 영속 ID가 아니다.
private 비영속 구현 값은 허용한다.

## 이 저장소의 사례

[MemoryCollectionId.java](../../backend/src/main/java/com/bifos/assistant/memory/domain/entity/MemoryCollectionId.java)에서 확인한다.
MemoryCollectionId는 소유 엔티티와 같은 memory.domain.entity에 둔다.

책임과 실행 절차는 [code-architecture.md](../../backend/docs/code-architecture.md)에서 이어 읽는다.
