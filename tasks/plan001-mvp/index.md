# plan001 MVP 단계

가장 얇은 end-to-end 부터 시작해 한 단계씩 넓힌다.
각 단계는 그 자체로 돌아가는 상태에서 끝난다.

| 단계 | 내용 | 상태 |
| --- | --- | --- |
| phase-01 | 대화 한 번이 Hermes 를 지나 돌아오고 실행 기록이 남는다 | 완료 |
| phase-02 | 홈서버의 실제 Hermes 에 연결한다 | 진행 전 |
| phase-03 | 실행 상태를 SSE 로 중계한다 | 진행 전 |
| phase-04 | 개인 Memory 와 공용 Memory | 진행 전 |
| phase-05 | 실행 Graph 화면 | 진행 전 |
| phase-06 | 비용 계산 | 진행 전 |

## phase-01 대화 한 번 (완료)

- 웹이 Google 로그인을 받고 서버 라우트에서 짧은 수명의 토큰을 발급한다
- Control Plane 이 토큰을 검사하고 첫 사용자를 admin 으로 만든다
- admin 이 구성원에게 Hermes profile 을 연결한다
- 바인딩이 있는 사용자만 실행할 수 있고 없으면 `HERMES_BINDING_MISSING` 으로 끝난다
- Runs API 로 실행을 제출하고 끝날 때까지 조회한다
- 실행 하나를 `agent_execution` 에 남기고 사용량 화면에서 본다

확인 방법은 `scripts/e2e-smoke.sh` 다.

## phase-02 실제 Hermes 연결

1. 홈서버 Hermes 의 버전과 `/v1/capabilities` 를 확인한다
2. 구성원마다 profile 을 만들고 `SOUL.md` 와 `config.yaml` 을 놓는다
3. profile `.env` 에 `API_SERVER_*` 와 그 사람의 AI credential 을 넣는다
4. `gateway.multiplex_profiles` 를 켠다
5. profile key 파일을 Control Plane 이 읽는 디렉터리에 놓는다
6. `fos-home-infra` 에 compose 와 nginx 항목을 더한다
7. 스모크 테스트를 실제 Hermes 주소로 한 번 돌린다

## phase-03 실행 상태 중계

- `GET /v1/runs/{id}/events` 를 Control Plane 이 받아 브라우저로 다시 보낸다
- 대화 화면이 도구 호출과 subagent 시작을 실시간으로 보여준다
- 중계하는 동안 실행 기록을 채운다

이 단계가 끝나면 `HttpHermesRunsClient` 의 조회 반복은 대비 경로로만 남는다.

## phase-04 Memory

- `memory` 표를 더한다. 소유자는 사용자이거나 가족이다
- 실행 전에 요청자가 볼 수 있는 항목만 골라 `instructions` 로 넣는다
- 화면에서 개인 Memory 와 공용 Memory 를 보고 고친다
- 다른 구성원의 개인 Memory 가 주입되지 않는 것을 테스트로 고정한다

## phase-05 실행 Graph

- phase-03 에서 받은 사건을 `execution_event` 로 남긴다
- 실행 하나를 도구와 subagent 의 나무로 그린다

## phase-06 비용

- 가격표를 `pricing_version` 과 함께 둔다
- `cost_mode` 가 `API` 인 실행만 계산한다
- 구독형은 계산하지 않고 그대로 둔다
