# local 셸 막기 인수 검증 계획

## 인수 검증 계획

| 요구사항 | 준비 조건 | 실행 절차 | 기대 결과 | 실행 환경 | 실행 담당 | 선행 조건 | 시점 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| REQ1 | 유효한 정책, 정책에 없는 profile 의 설정에 `terminal.backend: local` | 그 profile 에 `terminal` 을 켜는 `PUT /api/config` 를 `require_sandbox` 없이 보낸다 | 409, `code: sandbox_unavailable`, `reason: unlisted`, 설정 파일 바이트가 그대로다 | plugin 단위 테스트(`hermes/tests/test_dashboard_profile_api_sandbox_policy.py`) | worker | 없음 | PR 전 |
| REQ1 | 정책이 없거나 틀림 | 같은 저장을 보낸다 | 409 `sandbox_unavailable`, `reason` 칸 없음 | plugin 단위 테스트 | worker | 없음 | PR 전 |
| REQ1 | 실행 공간 도구가 켜진 에이전트 셋: 정책 안, 정책 밖, 정책 무효로 거절 | 기동 작업을 한 번 부른다 | 정책 안은 같은 목록을 실행 공간 쓰기로 다시 쓴다. 정책 밖은 실행 공간 도구를 뺀 목록을 쓴다. 정책 무효는 다시 쓰지 않는다. 한 에이전트의 실패가 다음 에이전트를 막지 않는다 | Control Plane 통합 테스트(`AgentSandboxSweepTest`) | worker | 없음 | PR 전 |
| REQ1 | 409 응답 본문 둘(`reason: unlisted` 있음, 없음) | 그 본문을 돌려주는 가짜 대시보드로 `writeApiServerInSandbox` 를 부른다 | 둘 다 `AGENT_SANDBOX_UNAVAILABLE` 이고 첫째만 `profileUnlisted()` 가 참 | Control Plane HTTP 테스트(`HermesToolsetRequestTest`) | worker | 없음 | PR 전 |
| REQ1 | 운영 배포(plugin 먼저, Control Plane 다음) | Control Plane 기동 뒤 로그의 「실행 공간 도구를 다시 저장했다 outcomes=… unfinished=…」 줄을 읽고, unfinished 가 0 이 아니면 다음 주기의 「다시 봤다 unfinished=0」 줄까지 기다린다. 이어서 모든 profile 설정의 `terminal.backend` 와 `platform_toolsets.api_server` 를 읽기 전용으로 대조한다. 읽는 명령은 운영 저장소 `fos-home-infra` 가 갖는다 | unfinished 가 0 이다. 셸 계열이 켜진 profile 은 모두 docker 이고, local 인 profile 은 셸 계열이 꺼져 있다 | 운영 Hermes | 코디네이터 | 배포(plugin 먼저, Control Plane 다음) | PR 뒤 |
