# rule-7

관리자 서비스·명령은 지정된 소유 경계에 둔다.

검사: [AGENT_ADMIN_APPLICATION_PLACEMENT](../../backend/src/test/java/com/bifos/assistant/architecture/ArchitectureRules.java)

## 까닭

관리자 등록 업무를 일반 에이전트 실행과 분리한다.

## 예외와 검토 범위

서비스와 명령의 누락, 중복, 옛 위치 복귀도 거절한다.
공유 lifecycle과 영속 타입의 위치는 바꾸지 않는다.

## 이 저장소의 사례

[AgentAdminService.java](../../backend/src/main/java/com/bifos/assistant/agent/admin/application/AgentAdminService.java)에서 확인한다.
AgentAdminService는 관리자 등록 업무를 별도 application 경계에서 처리한다.
