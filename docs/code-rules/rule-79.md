# rule-79

admin은 하위 기능 아래에 application, application.dto, presentation만 둔다.

검사: [ADMIN_PACKAGE_SHAPE](../../backend/src/test/java/com/bifos/assistant/architecture/ArchitectureRules.java)

## 까닭

admin은 기능의 application을 부르는 얇은 층이다. 엔티티와 저장소와 설정을 갖기 시작하면 기능과 다름없어 따로 뗄 수 없다.

## 예외와 검토 범위

허용하는 패키지는 `admin.<하위 기능>.application`, `application.dto`, `presentation`이다. 그 밖의 위치는 클래스마다 한 줄이다.
하위 기능 이름의 목록은 ADR이 갖고 이 규칙은 이름을 고정하지 않는다.

## 이 저장소의 사례

[PackageLayoutRulesTest.java](../../backend/src/test/java/com/bifos/assistant/architecture/PackageLayoutRulesTest.java)에서 확인한다.
PackageLayoutRulesTest의 fixture가 허용된 패키지는 통과시키고 infra와 domain과 루트의 클래스를 거절하는 것을 보인다.

결정과 근거는 [ADR-20261015 / package-layout](../../backend/docs/adr/ADR-20261015-package-layout.md)가, 책임과 실행 절차는 [code-architecture.md](../../backend/docs/code-architecture.md)가 갖는다.
