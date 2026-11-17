# 내장 위임 결과(PR7) 인수 검증 계획

스펙 `docs/features/agent-team/spec.md` 의 REQ10 가운데 fos-ctx 가 맡는 동작이다. Control Plane 의 동작은 PR6 의 검증이 끝냈다.

## 인수 검증 계획

| 요구사항 | 준비 조건 | 실행 절차 | 기대 결과 | 실행 환경 | 실행 담당 | 선행 조건 | 시점 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| REQ10 | 가짜 Control Plane, 가짜 state.db 와 토큰 | `subagent_start` 를 부른다. `SubagentRegistrationTest` | 등록 본문에 `reports_stop: true` 와 hook 의 `child_goal` 이 있고 서명은 그대로다 | 로컬 Python 테스트 | worker | 없음 | PR 전 |
| REQ10 | 같은 준비 | `subagent_stop` 을 두 버전의 키워드 인자로 부른다. `SubagentStopTest` | `/stop` 경로로 README 확인 값과 같은 `sig`, 루트, 상태, 결과 글이 간다. 5xx 와 연결 실패만 한 번 더 보내고, 4xx 와 재료 없음, 예외는 로그만 남긴다. 로그에 토큰, 서명, 글이 없다 | 로컬 Python 테스트 | worker | 없음 | PR 전 |
| REQ10 | v0.21.6, v0.23.2 tag 소스 | `test_hermes_contract.py` 를 두 소스로 돈다 | 두 hook 이 `VALID_HOOKS` 에 있고, 부르는 자리가 플러그인이 읽는 인자를 넘기며 `subagent_stop` 이 부르는 스레드에서 돈다. 실패 0 | 로컬 Python 테스트 | worker | 없음 | PR 전 |
| REQ10 | PR6, PR7 이 배포되고 설정이 켜진 운영 | 스펙 「배포 뒤 왕복 확인」 1~5 | 각 줄의 통과 조건 | 운영 | 코디네이터 | 배포 | PR 뒤 |
