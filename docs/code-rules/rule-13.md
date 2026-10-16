# rule-13

hermes는 people에 의존하지 않는다.

검사: [HERMES_DOES_NOT_DEPEND_ON_PEOPLE](../../backend/src/test/java/com/bifos/assistant/architecture/ArchitectureRules.java)

## 까닭

Hermes 어댑터는 부르는 방법만 알고 사용자 등록 순서는 알지 않게 한다.

## 예외와 검토 범위

등록 순서는 users 기능 문서가 갖는다.

## 이 저장소의 사례

[users.md](../../docs/features/users.md)에서 확인한다.
사용자 추가 흐름은 등록 순서를 Hermes 어댑터 바깥에서 조율한다.
