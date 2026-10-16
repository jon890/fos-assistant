# rule-81

admin은 기능의 application과 application.dto만 쓴다.

검사: [ADMIN_USES_ONLY_FEATURE_APPLICATION](../../backend/src/test/java/com/bifos/assistant/architecture/ArchitectureRules.java)

## 까닭

admin이 기능의 저장소와 엔티티와 컨트롤러 응답을 직접 쓰면 기능을 바꿀 때 admin이 함께 깨진다. 기능의 application이 내놓는 값만 쓴다.

## 예외와 검토 범위

기능의 `domain.type`은 허용한다. `shared`와 admin 안의 의존은 보지 않는다.
이동이 끝나기 전의 옛 이름 `application.model`과 `application.toolset`도 임시로 허용하고 마지막 이동 PR이 지운다.
이동 뒤에도 남는 줄은 기준 파일에서 줄여 간다.

## 이 저장소의 사례

[PackageLayoutRulesTest.java](../../backend/src/test/java/com/bifos/assistant/architecture/PackageLayoutRulesTest.java)에서 확인한다.
PackageLayoutRulesTest의 fixture가 application과 dto와 domain.type은 통과시키고 infra와 domain과 presentation은 거절하는 것을 보인다.

결정과 근거는 [ADR-20261015 / package-layout](../../backend/docs/adr/ADR-20261015-package-layout.md)가, 책임과 실행 절차는 [code-architecture.md](../../backend/docs/code-architecture.md)가 갖는다.
