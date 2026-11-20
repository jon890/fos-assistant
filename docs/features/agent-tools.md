# 에이전트 도구와 실행 공간

에이전트가 셸, 파일, 코드 실행을 사용자별 격리된 실행 공간에서 쓰는 기능이다.

covers: `hermes/plugins/dashboard-profile-api/sandbox.py`, `hermes/plugins/dashboard-profile-api/sandbox_approvals.py`, `hermes/plugins/dashboard-profile-api/toolconfig.py`, `backend/src/main/java/com/bifos/assistant/agent/application/AgentSandboxSweep.java`

지금 도구 등급과 도구 저장의 흐름은 [`agent-skill.md`](agent-skill.md) 의 「에이전트 도구」 가 갖는다.
셸을 모든 에이전트의 기본으로 켜는 변경은 [스펙](agent-tools/spec.md) 이 갖고, 구현이 끝나면 그 흐름을 이 파일로 옮긴다.
