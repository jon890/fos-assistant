# local 셸 막기 인수 검증 계획

## 인수 검증 계획

| 요구사항 | 준비 조건 | 실행 절차 | 기대 결과 | 실행 환경 | 실행 담당 | 선행 조건 | 시점 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| REQ1 | 유효한 정책, 정책에 없는 profile 의 설정에 `terminal.backend: local` | 그 profile 에 `terminal` 을 켜는 `PUT /api/config` 를 `require_sandbox` 없이 보낸다 | 409, `code: sandbox_unavailable`, `reason: unlisted`, 설정 파일 바이트가 그대로다 | plugin 단위 테스트(`hermes/tests/test_dashboard_profile_api_sandbox_policy.py`) | worker | 없음 | PR 전 |
| REQ1 | 정책이 없거나 틀림 | 같은 저장을 보낸다 | 409 `sandbox_unavailable`, `reason` 칸 없음 | plugin 단위 테스트 | worker | 없음 | PR 전 |
| REQ1 | 실행 공간 도구가 켜진 에이전트 셋: 정책 안, 정책 밖, 정책 무효로 거절 | 기동 작업을 한 번 부른다 | 정책 안은 같은 목록을 실행 공간 쓰기로 다시 쓴다. 정책 밖은 실행 공간 도구를 뺀 목록을 쓴다. 정책 무효는 다시 쓰지 않는다. 한 에이전트의 실패가 다음 에이전트를 막지 않는다 | Control Plane 단위 테스트(`AgentSandboxSweepTest`) | worker | 없음 | PR 전 |
| REQ1 | 409 응답 본문 둘(`reason: unlisted` 있음, 없음) | `HermesCallFailure.sandboxRejection` 에 넣는다 | 둘 다 `AGENT_SANDBOX_UNAVAILABLE` 이고 첫째만 `profileUnlisted()` 가 참 | Control Plane 단위 테스트 | worker | 없음 | PR 전 |
| REQ1 | 운영 배포(plugin 먼저, Control Plane 다음) | Control Plane 기동 뒤 셸 계열이 켜진 profile 의 `terminal.backend` 를 확인한다 | 모두 docker 이거나 셸 계열이 꺼져 있다 | 운영 Hermes | 코디네이터 | 배포(plugin 먼저, Control Plane 다음) | PR 뒤 |
