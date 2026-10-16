# rule-33

엔티티 접근자는 Getter와 fluent Accessors로 통일한다.

검사: [entityHandwrittenAccessor](../../backend/config/checkstyle/checkstyle.xml)

## 까닭

엔티티와 record의 접근 모양을 맞추고 같은 필드 접근자가 두 벌 생기지 않게 한다.

## 예외와 검토 범위

필드를 그대로 돌려주는 단순 getter를 대상으로 한다.
계산 메서드는 단순 getter와 구별한다.

## 이 저장소의 사례

[MemoryCollection.java](../../backend/src/main/java/com/bifos/assistant/memory/domain/entity/MemoryCollection.java)에서 확인한다.
MemoryCollection은 필드 접근자를 Getter와 fluent Accessors로 제공한다.
