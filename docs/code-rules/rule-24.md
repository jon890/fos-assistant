# rule-24

ConfigurationProperties에는 Validated를 단다.

검사: [CONFIGURATION_PROPERTIES_ARE_VALIDATED](../../backend/src/test/java/com/bifos/assistant/architecture/ArchitectureRules.java)

## 까닭

잘못된 설정을 실제 호출 시점까지 미루지 않고 기동 때 발견한다.

## 예외와 검토 범위

설정 위치 검사와 별개로 ConfigurationProperties 클래스의 Validated를 확인한다.

## 이 저장소의 사례

[AgentProperties.java](../../backend/src/main/java/com/bifos/assistant/agent/config/AgentProperties.java)에서 확인한다.
AgentProperties는 ConfigurationProperties와 Validated를 함께 선언한다.
