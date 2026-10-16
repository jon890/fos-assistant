# rule-73

dto 패키지에는 값만 둔다.

검사: [DTO_PACKAGE_HOLDS_ONLY_VALUES](../../backend/src/test/java/com/bifos/assistant/architecture/ArchitectureRules.java)

## 까닭

dto는 서비스가 주고받는 값이다. 빈이나 엔티티, 구현이 따로 있는 인터페이스가 들어오면 값과 동작이 섞인다.

## 예외와 검토 범위

Spring 빈, 엔티티, sealed가 아닌 인터페이스를 거절한다. sealed 인터페이스는 값의 합 타입이라 허용한다.
타입이 정말 서비스의 입출력인지는 규칙으로 판정하지 못하고 리뷰가 본다(rule-84).

## 이 저장소의 사례

[PackageLayoutRulesTest.java](../../backend/src/test/java/com/bifos/assistant/architecture/PackageLayoutRulesTest.java)에서 확인한다.
PackageLayoutRulesTest의 fixture가 record와 sealed 인터페이스는 통과시키고 빈과 엔티티는 거절하는 것을 보인다.

결정과 근거는 [ADR-20261015 / package-layout](../../backend/docs/adr/ADR-20261015-package-layout.md)가, 책임과 실행 절차는 [code-architecture.md](../../backend/docs/code-architecture.md)가 갖는다.
