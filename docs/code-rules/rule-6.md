# rule-6

domain은 웹 타입에 의존하지 않는다.

검사: [DOMAIN_DOES_NOT_DEPEND_ON_WEB](../../backend/src/test/java/com/bifos/assistant/architecture/ArchitectureRules.java)

## 까닭

도메인 정책을 HTTP와 Servlet의 수명에서 분리한다.

## 예외와 검토 범위

Spring Web, HTTP, Servlet과 presentation 의존의 정확한 선택은 검사 코드가 갖는다.

## 이 저장소의 사례

[MemoryCollectionCount.java](../../backend/src/main/java/com/bifos/assistant/memory/domain/MemoryCollectionCount.java)에서 확인한다.
MemoryCollectionCount는 HTTP 응답과 분리한 도메인 값이다.
