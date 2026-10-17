# rule-83

requireAdmin은 admin 안에서만 부른다.

검사: [REQUIRE_ADMIN_ONLY_IN_ADMIN](../../backend/src/test/java/com/bifos/assistant/architecture/ArchitectureRules.java)

## 까닭

관리자 전용 API가 사용자 기능 안에 섞이면 어느 엔드포인트가 관리자 전용인지 admin을 훑어서는 알 수 없다.

## 예외와 검토 범위

`CurrentUserProvider.requireAdmin` 호출만 본다. admin과 `shared.auth` 안의 호출은 허용한다.
사용자 기능 안에서 권한에 따라 범위만 다르면 이 호출이 아니라 `CurrentUser`의 역할로 판정한다.
서비스가 요청자의 역할을 다시 확인하는 방어적 도우미는 이 호출을 쓰지 않으므로 대상이 아니다.

## 이 저장소의 사례

[PackageLayoutRulesTest.java](../../backend/src/test/java/com/bifos/assistant/architecture/PackageLayoutRulesTest.java)에서 확인한다.
PackageLayoutRulesTest의 fixture가 admin 안의 호출은 통과시키고 사용자 기능의 호출을 거절하는 것을 보인다.

결정과 근거는 [ADR-20261015 / package-layout](../../backend/docs/adr/ADR-20261015-package-layout.md)가, 책임과 실행 절차는 [code-architecture.md](../../backend/docs/code-architecture.md)가 갖는다.
