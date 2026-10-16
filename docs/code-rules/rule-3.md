# rule-3

최상위 패키지는 정해진 층 순서의 아래쪽만 쓴다.

검사: [TOP_LEVEL_PACKAGES_FOLLOW_LAYER_ORDER](../../backend/src/test/java/com/bifos/assistant/architecture/ArchitectureRules.java)

## 까닭

패키지를 늘릴 때 호출 방향이 뒤집히는 것을 막는다.

## 예외와 검토 범위

정확한 순서와 미등록 패키지 판정은 TopLevelPackageOrder가 갖는다.
순서 표는 backend 아키텍처 문서에서 찾는다.

## 이 저장소의 사례

[TopLevelPackageOrder.java](../../backend/src/test/java/com/bifos/assistant/architecture/TopLevelPackageOrder.java)에서 확인한다.
TopLevelPackageOrder는 ORDER와 실제 패키지 의존의 방향을 비교한다.

책임과 실행 절차는 [code-architecture.md](../../backend/docs/code-architecture.md)에서 이어 읽는다.
