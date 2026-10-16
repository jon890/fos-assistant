# rule-74

ConfigurationProperties는 config 패키지에 둔다.

검사: [CONFIGURATION_PROPERTIES_IN_CONFIG](../../backend/src/test/java/com/bifos/assistant/architecture/ArchitectureRules.java)

## 까닭

설정 값이 application이나 infra에 흩어지면 설정의 소유자와 읽는 쪽을 찾기 어렵다.

## 예외와 검토 범위

shared 패키지는 보지 않는다. 마지막 패키지 이름이 config가 아니면 위반이다.
`@Configuration` 클래스의 위치는 이 규칙이 판정하지 않는다. 에이전트 설정 두 타입의 위치는 rule-23이 따로 지킨다.

## 이 저장소의 사례

[PackageLayoutRulesTest.java](../../backend/src/test/java/com/bifos/assistant/architecture/PackageLayoutRulesTest.java)에서 확인한다.
PackageLayoutRulesTest의 fixture가 config 안의 설정은 통과시키고 밖의 설정은 거절하는 것을 보인다.

결정과 근거는 [ADR-20261015 / package-layout](../../backend/docs/adr/ADR-20261015-package-layout.md)가, 책임과 실행 절차는 [code-architecture.md](../../backend/docs/code-architecture.md)가 갖는다.
