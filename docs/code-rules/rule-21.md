# rule-21

요청 밖 가상 스레드는 BackgroundTasks로 시작한다.

검사: [VIRTUAL_THREADS_ONLY_THROUGH_BACKGROUND_TASKS](../../backend/src/test/java/com/bifos/assistant/architecture/ArchitectureRules.java)

## 까닭

요청 밖 작업의 종료를 테스트와 애플리케이션 수명 관리가 기다릴 수 있게 한다.

## 예외와 검토 범위

HTTP 연결에 묶인 ChatEventStreams와 NotificationEventStreams는 직접 가상 스레드를 시작할 수 있다.
요청 밖 작업은 BackgroundTasks를 쓴다.

## 이 저장소의 사례

[VirtualThreadBackgroundTasks.java](../../backend/src/main/java/com/bifos/assistant/shared/concurrent/VirtualThreadBackgroundTasks.java)에서 확인한다.
VirtualThreadBackgroundTasks는 요청 밖 작업을 추적하는 공통 구현이다.
