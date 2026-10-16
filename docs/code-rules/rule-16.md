# rule-16

테스트 문장은 메서드의 DisplayName으로 쓴다.

검사: [TEST_METHODS_HAVE_DISPLAY_NAME](../../backend/src/test/java/com/bifos/assistant/architecture/ArchitectureRules.java)

## 까닭

테스트 보고서는 한국어 문장으로 읽고 코드의 메서드 이름은 동작으로 찾는다.

## 예외와 검토 범위

Test와 ParameterizedTest에 DisplayName을 단다.
준비·정리 메서드는 setUp, tearDown 관례를 쓰고 보조 메서드와 클래스에는 달지 않는다.
이름은 동사로 시작한다.

## 이 저장소의 사례

[Sha256Test.java](../../backend/src/test/java/com/bifos/assistant/shared/util/Sha256Test.java)에서 확인한다.
Sha256Test는 영문 메서드 이름과 한국어 DisplayName을 함께 쓴다.
