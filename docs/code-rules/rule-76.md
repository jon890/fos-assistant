# rule-76

하위 기능은 같은 기능의 아래 하위 기능만 쓴다.

검사: [SUB_FEATURES_FOLLOW_ORDER](../../backend/src/test/java/com/bifos/assistant/architecture/ArchitectureRules.java)

## 까닭

하위 기능 사이의 의존이 한 방향이어야 하나씩 떼어 읽을 수 있다. 거꾸로 써야 하면 아래 하위 기능의 port에 인터페이스를 두고 위에서 구현한다.

## 예외와 검토 범위

순서는 SubFeatureOrder가 아래에서 위로 적어 갖는다. 목록에 없는 하위 기능 패키지도 위반이다.
역방향 의존은 클래스 쌍마다 한 줄이다. 이동이 끝난 뒤에도 남는 줄은 기준 파일에서 줄여 간다.

## 이 저장소의 사례

[SubFeatureOrder.java](../../backend/src/test/java/com/bifos/assistant/architecture/SubFeatureOrder.java)에서 확인한다.
SubFeatureOrder가 chat, connector, proactive, usage, skill의 하위 기능 순서를 갖는다.

결정과 근거는 [ADR-20261015 / package-layout](../../backend/docs/adr/ADR-20261015-package-layout.md)가, 책임과 실행 절차는 [code-architecture.md](../../backend/docs/code-architecture.md)가 갖는다.
