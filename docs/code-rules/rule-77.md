# rule-77

하위 기능으로 나눈 기능은 루트에 역할 패키지를 두지 않는다.

검사: [SPLIT_FEATURES_HAVE_NO_ROOT_ROLES](../../backend/src/test/java/com/bifos/assistant/architecture/ArchitectureRules.java)

## 까닭

나눈 기능의 루트에 application이 남으면 어느 클래스가 어느 하위 기능인지 다시 흐려진다.

## 예외와 검토 범위

SubFeatureOrder에 있는 기능만 본다. 루트의 application, domain, infra, presentation, config와 그 하위 패키지를 패키지마다 한 줄로 알린다.
어느 하위 기능에 둘지는 ADR의 하위 기능 표를 따르고, 경계가 업무에 맞는지는 리뷰가 본다(rule-84).

## 이 저장소의 사례

[SubFeatureOrder.java](../../backend/src/test/java/com/bifos/assistant/architecture/SubFeatureOrder.java)에서 확인한다.
SubFeatureOrder에 있는 기능이 이 규칙의 대상이다.

결정과 근거는 [ADR-20261015 / package-layout](../../backend/docs/adr/ADR-20261015-package-layout.md)가, 책임과 실행 절차는 [code-architecture.md](../../backend/docs/code-architecture.md)가 갖는다.
