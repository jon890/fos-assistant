# plan93. 사용자별 셸 실행 공간

결정: `docs/adr/ADR-084-셸과-파일-도구는-사용자별-docker-실행-공간에서만-돈다.md`
Hermes 동작과 측정: `docs/hermes/sandbox.md`
plugin 계약: `hermes/README.md` 의 「셸 실행 공간」
흐름: `docs/backend/agent.md` 의 「에이전트 도구를 고를 때」

| phase | 내용 |
| --- | --- |
| 1 | 대시보드 plugin 이 셸·파일 도구 저장 때 `terminal:` 을 실행 공간 설정으로 쓰고, 설정이 없으면 409 |
| 2 | Control Plane 이 `sandbox_owner` 를 보내고 409 를 `AGENT_SANDBOX_UNAVAILABLE` 로 옮긴다. 올린 스킬의 비밀 요청 칸을 거절한다. 가짜 Hermes 와 e2e |
| 3 | 도구 화면의 확인 문구와 오류 문구, 브라우저 검사 |
