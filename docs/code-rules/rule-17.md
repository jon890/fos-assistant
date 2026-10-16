# rule-17

공유 테스트 기반 밖에서 Spring 컨텍스트를 나누지 않는다.

검사: [TESTS_DO_NOT_SPLIT_CONTEXT](../../backend/src/test/java/com/bifos/assistant/architecture/ArchitectureRules.java)

## 까닭

서로 다른 Spring 컨텍스트가 늘면 검사 시간과 남은 힙이 늘어난다.

## 예외와 검토 범위

mysql Tag가 붙었거나 이를 상속한 클래스와 그 중첩 클래스는 별도 기동하므로 제외한다.
이름에 Mysql만 붙여서는 예외가 되지 않는다.

## 이 저장소의 사례

[TrackingBackgroundTasks.java](../../backend/src/test/java/com/bifos/assistant/testsupport/TrackingBackgroundTasks.java)에서 확인한다.
공유 테스트 지원 코드인 TrackingBackgroundTasks를 쓰면 테스트마다 컨텍스트 구성을 바꿀 필요가 없다.
