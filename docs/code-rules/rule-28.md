# rule-28

실행 중 설정 record는 LiveProperties로 읽는다.

검사: [LIVE_SETTINGS_ONLY_THROUGH_LIVE_PROPERTIES](../../backend/src/test/java/com/bifos/assistant/architecture/ArchitectureRules.java)

## 까닭

설정 record를 계속 쥐고 있으면 실행 중 바뀐 설정을 읽지 못한다.

## 예외와 검토 범위

LivePropertiesConfig와 기동 때 설정을 견주는 ProactiveCheckProperties의 RunTimeoutCheck는 예외다.
다른 사용처는 current()로 읽는다.

## 이 저장소의 사례

[LivePropertiesConfig.java](../../backend/src/main/java/com/bifos/assistant/LivePropertiesConfig.java)에서 확인한다.
LivePropertiesConfig가 실행 중 바뀔 설정 record를 LiveProperties로 제공한다.
