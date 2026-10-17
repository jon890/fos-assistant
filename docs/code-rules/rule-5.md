# rule-5

기능 안의 층은 허용된 방향으로만 의존한다.

검사: [LAYER_DIRECTION](../../backend/src/test/java/com/bifos/assistant/architecture/ArchitectureRules.java)

## 까닭

컨트롤러가 저장소를 바로 부르면 서비스의 권한 확인과 트랜잭션 경계를 건너뛴다.

## 예외와 검토 범위

기능 사이 조인까지 금지하는 규칙은 아니다.
층은 presentation, application의 빈, application.port, application.dto, infra, domain, config 일곱이다. 서로 쓸 수 있는 방향은 [ADR-20261015 / package-layout](../../backend/docs/adr/ADR-20261015-package-layout.md)의 표가 정하고 검사 코드가 그대로 갖는다.
infra는 port와 dto를 쓸 수 있고 application의 빈은 쓰지 않는다. 막힌 의존은 application.port에 인터페이스를 두고 구현을 infra에 두어 끊는다.
이동이 끝나기 전에는 옛 이름(`application.model`, `application.toolset`)이 application 층에 속한다.

## 이 저장소의 사례

[AgentAdminService.java](../../backend/src/main/java/com/bifos/assistant/admin/agent/application/AgentAdminService.java)에서 확인한다.
AgentAdminService가 관리자 업무의 서비스 경계를 맡고 컨트롤러가 이를 호출한다.

책임과 실행 절차는 [code-architecture.md](../../backend/docs/code-architecture.md)에서 이어 읽는다.
