# rule-71

application 아래 하위 패키지는 dto와 port뿐이다.

검사: [APPLICATION_SUBPACKAGES_ARE_DTO_AND_PORT](../../backend/src/test/java/com/bifos/assistant/architecture/ArchitectureRules.java)

## 까닭

model, toolset, execution 같은 이름은 기능마다 뜻이 달라 어디에 무엇을 두는지 이름으로 알 수 없다. 이름을 둘로 고정하면 모든 기능에서 같은 곳을 본다.

## 예외와 검토 범위

클래스가 있는 하위 패키지마다 위반 한 줄을 낸다. dto와 port 아래 더 깊은 패키지도 허용하지 않는다.
이동이 끝나기 전에는 옛 이름(`application.model` 등)이 기준 파일에 남아 있고 이동 PR이 줄인다.

## 이 저장소의 사례

[PackageLayoutRulesTest.java](../../backend/src/test/java/com/bifos/assistant/architecture/PackageLayoutRulesTest.java)에서 확인한다.
PackageLayoutRulesTest의 fixture가 model과 toolset 하위 패키지를 패키지마다 한 줄로 알리는 것을 보인다.

결정과 근거는 [ADR-20261015 / package-layout](../../backend/docs/adr/ADR-20261015-package-layout.md)가, 책임과 실행 절차는 [code-architecture.md](../../backend/docs/code-architecture.md)가 갖는다.
