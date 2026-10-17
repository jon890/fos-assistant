# rule-70

application 직하에는 Spring 빈만 둔다.

검사: [APPLICATION_ROOT_HOLDS_ONLY_BEANS](../../backend/src/test/java/com/bifos/assistant/architecture/ArchitectureRules.java)

## 까닭

서비스와 값과 인터페이스가 한 패키지에 섞이면 어느 클래스가 서비스의 일을 하는지 이름으로만 찾아야 한다. 값은 dto로, 인터페이스는 port로 가르면 application을 읽는 사람이 빈만 본다.

## 예외와 검토 범위

인터페이스, record, enum, 빈이 아닌 public 클래스를 application 직하에서 찾는다. package-private 클래스와 중첩 타입은 보지 않는다.
빈이 소유한 package-private 도우미는 허용한다. `@ConfigurationProperties` 타입은 rule-74가 맡는다.
정책이나 값 함수인 public 클래스는 domain으로 옮기는 것이 원칙이다. 이동이 끝난 뒤에도 남는 줄은 기준 파일에서 줄여 간다.

## 이 저장소의 사례

[PackageLayoutRulesTest.java](../../backend/src/test/java/com/bifos/assistant/architecture/PackageLayoutRulesTest.java)에서 확인한다.
PackageLayoutRulesTest의 fixture가 통과하는 소스와 위반하는 소스, 옮길 곳을 알리는 메시지를 보인다.

결정과 근거는 [ADR-20261015 / package-layout](../../backend/docs/adr/ADR-20261015-package-layout.md)가, 책임과 실행 절차는 [code-architecture.md](../../backend/docs/code-architecture.md)가 갖는다.
