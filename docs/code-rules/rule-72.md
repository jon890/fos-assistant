# rule-72

port 패키지에는 인터페이스와 그 예외만 둔다.

검사: [PORT_PACKAGE_HOLDS_ONLY_INTERFACES](../../backend/src/test/java/com/bifos/assistant/architecture/ArchitectureRules.java)

## 까닭

port는 구현을 바깥에 두는 경계다. 값과 구현이 섞이면 경계가 어디인지 흐려진다.

## 예외와 검토 범위

최상위 타입이 인터페이스이거나 Throwable의 하위 클래스인지만 본다. 인터페이스가 실제로 그 예외를 던지는지는 보지 않는다.
infra가 구현하는 인터페이스와 위 기능의 application이 구현하는 인터페이스를 함께 둔다. 둘을 가르는 것은 규칙으로 판정하지 못한다.

## 이 저장소의 사례

[PackageLayoutRulesTest.java](../../backend/src/test/java/com/bifos/assistant/architecture/PackageLayoutRulesTest.java)에서 확인한다.
PackageLayoutRulesTest의 fixture가 인터페이스와 예외는 통과시키고 구현 클래스는 거절하는 것을 보인다.

결정과 근거는 [ADR-20261015 / package-layout](../../backend/docs/adr/ADR-20261015-package-layout.md)가, 책임과 실행 절차는 [code-architecture.md](../../backend/docs/code-architecture.md)가 갖는다.
