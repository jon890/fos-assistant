# rule-5

기능 안의 층은 허용된 방향으로만 의존한다.

검사: [LAYER_DIRECTION](../../backend/src/test/java/com/bifos/assistant/architecture/ArchitectureRules.java)

## 까닭

컨트롤러가 저장소를 바로 부르면 서비스의 권한 확인과 트랜잭션 경계를 건너뛴다.

## 예외와 검토 범위

기능 사이 조인까지 금지하는 규칙은 아니다.
현재 층 선택과 방향은 검사 코드가 갖는다.
application 서비스는 목적에 따라 하위 패키지로 묶는다.
에이전트 도구 서비스는 agent.application.toolset에 두고 외부 공개 서비스 값은 application.model에 둔다.

## 이 저장소의 사례

[AgentAdminService.java](../../backend/src/main/java/com/bifos/assistant/agent/admin/application/AgentAdminService.java)에서 확인한다.
AgentAdminService가 관리자 업무의 서비스 경계를 맡고 컨트롤러가 이를 호출한다.

책임과 실행 절차는 [code-architecture.md](../../backend/docs/code-architecture.md)에서 이어 읽는다.
