# rule-89

hermes 밖은 Hermes의 실행 port와 실행 타입에 의존하지 않는다.

검사: [ONLY_HERMES_USES_HERMES_RUN_TYPES](../../backend/src/test/java/com/bifos/assistant/architecture/ArchitectureRules.java)

## 까닭

실행은 대상의 런타임 종류로 고른 구현을 거친다.
기능이 Hermes 의 실행 호출을 직접 부르면 그 실행은 다른 런타임으로 갈 수 없다.

## 예외와 검토 범위

금지 대상은 검사 코드가 이름으로 정한 Hermes 의 실행 port, 사건 스트림, 명령과 결과와 사건 타입이다.
profile, 플러그인, 스킬, toolset, 모델 목록처럼 Hermes 에만 있는 기능의 타입은 대상이 아니다.

## 이 저장소의 사례

[runtime-abstraction.md](../features/runtime-abstraction.md)에서 확인한다.
대화 turn, 위임, 재기동 복구는 `AgentRuntimes` 로 고른 구현을 부른다.
