# rule-80

기능은 admin을 쓰지 않는다.

검사: [FEATURES_DO_NOT_DEPEND_ON_ADMIN](../../backend/src/test/java/com/bifos/assistant/architecture/ArchitectureRules.java)

## 까닭

admin을 아는 기능은 admin을 떼어 낼 수 없다. 의존은 admin에서 기능으로만 흐른다.

## 예외와 검토 범위

admin 밖의 모든 클래스가 대상이다. 의존은 클래스 쌍마다 한 줄이다.
공통 값이 필요하면 기능의 application.dto에 두고 admin이 그것을 쓴다.

## 이 저장소의 사례

[PackageLayoutRulesTest.java](../../backend/src/test/java/com/bifos/assistant/architecture/PackageLayoutRulesTest.java)에서 확인한다.
PackageLayoutRulesTest의 fixture가 기능에서 admin으로 가는 의존을 거절하는 것을 보인다.

결정과 근거는 [ADR-20261015 / package-layout](../../backend/docs/adr/ADR-20261015-package-layout.md)가, 책임과 실행 절차는 [code-architecture.md](../../backend/docs/code-architecture.md)가 갖는다.
