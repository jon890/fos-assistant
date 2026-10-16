# rule-22

관리되지 않는 가상 스레드 실행기를 직접 만들지 않는다.

검사: [NO_UNMANAGED_VIRTUAL_THREAD_EXECUTORS](../../backend/src/test/java/com/bifos/assistant/architecture/ArchitectureRules.java)

## 까닭

직접 만든 실행기는 BackgroundTasks의 종료 대기와 추적 경계를 우회한다.

## 예외와 검토 범위

현재 검사는 직접 생성하는 가상 실행기에 예외를 두지 않는다.
이전의 특정 흐름 허용 규칙을 되살리지 않는다.

## 이 저장소의 사례

[TrackingBackgroundTasks.java](../../backend/src/test/java/com/bifos/assistant/testsupport/TrackingBackgroundTasks.java)에서 확인한다.
TrackingBackgroundTasks는 작업이 끝나는 것을 기다리는 테스트 대역이다.
