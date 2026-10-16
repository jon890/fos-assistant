# rule-23

에이전트 설정 두 타입은 지정한 config 역할을 지킨다.

검사: [FEATURE_CONFIG_PLACEMENT](../../backend/src/test/java/com/bifos/assistant/architecture/ArchitectureRules.java)

## 까닭

기능 설정과 실행 서비스를 섞지 않고 설정의 소유자를 드러낸다.

## 예외와 검토 범위

현재 검사는 agent의 두 설정만 대상으로 한다.
두 설정 record 안의 중첩 값 record는 허용한다.
다른 기능 전체에 적용된 것으로 해석하지 않는다.

## 이 저장소의 사례

[PeopleProperties.java](../../backend/src/main/java/com/bifos/assistant/agent/config/PeopleProperties.java)에서 확인한다.
PeopleProperties는 사용자 이름과 달리 에이전트 설정 경계에 속한다.

책임과 실행 절차는 [code-architecture.md](../../backend/docs/code-architecture.md)에서 이어 읽는다.
