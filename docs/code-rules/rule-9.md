# rule-9

에이전트 정리 실행과 port·결과는 소유 경계를 지킨다.

검사: [AGENT_PURGE_CONTRACT_PLACEMENT](../../backend/src/test/java/com/bifos/assistant/architecture/ArchitectureRules.java)

## 까닭

정리 실행은 기능별 구현을 모르고 참여 port로만 정리한다.

## 예외와 검토 범위

참여 구현은 각 기능에 남긴다.
profile 회수 port와 설정의 소유 경계는 검사 코드가 별도로 확인한다.

## 이 저장소의 사례

[AgentPurger.java](../../backend/src/main/java/com/bifos/assistant/agent/application/AgentPurger.java)에서 확인한다.
AgentPurger는 기능별 정리 참여 port를 통해 정리 순서를 조율한다.
