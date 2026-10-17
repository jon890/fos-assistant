# rule-8

관리자 HTTP 타입의 위치와 mapped 메서드 구성을 지킨다.

검사: [AGENT_ADMIN_HTTP_CONTRACT](../../backend/src/test/java/com/bifos/assistant/architecture/ArchitectureRules.java)

## 까닭

관리자 요청이 일반 사용자 권한으로 실행되지 않게 한다.

## 예외와 검토 범위

`requireAdmin` 호출은 [rule-82](rule-82.md) 가 모든 `admin` 컨트롤러에 대해 검사한다.
서비스 권한 확인을 생략해도 된다는 뜻은 아니다.

## 이 저장소의 사례

[AgentAdminController.java](../../backend/src/main/java/com/bifos/assistant/admin/agent/presentation/AgentAdminController.java)에서 확인한다.
AgentAdminController의 mapped 메서드들은 관리자 확인 뒤 서비스를 호출한다.
