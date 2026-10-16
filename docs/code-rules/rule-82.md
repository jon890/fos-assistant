# rule-82

admin의 엔드포인트는 requireAdmin을 부른다.

검사: [ADMIN_ENDPOINTS_REQUIRE_ADMIN](../../backend/src/test/java/com/bifos/assistant/architecture/ArchitectureRules.java)

## 까닭

관리자 API가 일반 사용자 권한으로 실행되지 않게 한다.

## 예외와 검토 범위

admin의 presentation에서 `@RequestMapping` 계열이 붙은 메서드가 대상이다. 같은 클래스의 도우미를 거쳐 부르는 것도 인정한다.
첫 줄에서 부르는지는 보지 않고 호출이 있는지만 본다. 서비스의 권한 확인을 생략해도 된다는 뜻은 아니다.

## 이 저장소의 사례

[PackageLayoutRulesTest.java](../../backend/src/test/java/com/bifos/assistant/architecture/PackageLayoutRulesTest.java)에서 확인한다.
PackageLayoutRulesTest의 fixture가 직접 호출과 도우미를 거친 호출은 통과시키고 호출이 없는 메서드를 거절하는 것을 보인다.

결정과 근거는 [ADR-20261015 / package-layout](../../backend/docs/adr/ADR-20261015-package-layout.md)가, 책임과 실행 절차는 [code-architecture.md](../../backend/docs/code-architecture.md)가 갖는다.
