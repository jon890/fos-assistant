# plan001 MVP 단계

가장 얇은 end-to-end 부터 시작해 한 단계씩 넓힌다.
각 단계는 그 자체로 돌아가는 상태에서 끝난다.

| 단계 | 내용 | 상태 |
| --- | --- | --- |
| phase-01 | 대화 한 번이 Hermes 를 지나 돌아오고 실행 기록이 남는다 | 완료 |
| phase-02 | 홈서버의 실제 Hermes 에 연결하고 배포한다 | 완료 |
| phase-03 | 실행 상태를 SSE 로 중계한다 | 완료 |
| phase-04 | 개인 Memory 와 공용 Memory | 별도 plan 으로 옮겼다 |
| phase-05 | 실행 Graph 화면 | 별도 plan 으로 옮겼다 |
| phase-06 | 비용 계산 | 완료 |
| phase-07 | 작업 영역으로 도메인 지식을 싣는다 | 되돌렸다 |

phase-07 이 만든 작업 영역은 한 번도 쓰이지 않아 제거하기로 했다.
근거는 [ADR-010](../../docs/adr/ADR-010-작업-영역을-제거하고-에이전트가-그-자리를-갖는다.md) 에 있다.

## phase-01 대화 한 번 (완료)

- 웹이 Google 로그인을 받고 서버 라우트에서 짧은 수명의 토큰을 발급한다
- Control Plane 이 토큰을 검사하고 첫 사용자를 admin 으로 만든다
- admin 이 구성원에게 Hermes profile 을 연결한다
- 바인딩이 있는 사용자만 실행할 수 있고 없으면 `HERMES_BINDING_MISSING` 으로 끝난다
- Runs API 로 실행을 제출하고 끝날 때까지 조회한다
- 실행 하나를 `agent_execution` 에 남기고 사용량 화면에서 본다

확인 방법은 `node test/e2e/run.ts` 다.

## phase-02 실제 Hermes 연결

끝난 것은 아래와 같다.

- 홈서버 Hermes v0.21.0 이 Runs API 를 지원하는 것을 확인했다
- `bifos` profile 을 만들고 gateway 를 올렸다
- profile key 를 홈서버의 비밀값 디렉터리에 mode 600 으로 두었다
- Control Plane 을 터널로 붙여 실제 대화 두 번을 왕복했다
- 두 번째 대화가 첫 번째를 기억해 session 이 이어지는 것을 확인했다
- 실제 토큰 수와 소요 시간이 `agent_execution` 에 남는 것을 확인했다

배포까지 끝났다.

- MySQL 에 이 서비스의 데이터베이스를 만들고 Flyway V1 을 적용했다
- Control Plane 과 웹을 홈서버 compose 로 올렸다
- Cloudflare Tunnel 과 Nginx Proxy Manager 로 외부 주소를 열었다
- 브라우저에서 Google 로그인, 대화, 사용량 확인까지 왕복했다

남은 것은 가족 구성원을 더하는 일이다.

- Google 동의 화면의 테스트 사용자에 그 사람 주소를 더한다
- `ASSISTANT_ALLOWED_EMAILS` 에 그 주소를 더한다
- 그 사람의 Hermes profile 을 만들고 바인딩한다

## phase-03 실행 상태 중계 (완료)

- `GET /v1/runs/{id}/events` 를 Control Plane 이 받아 브라우저로 다시 보낸다
- 대화 화면이 도구 호출과 subagent 시작을 실시간으로 보여준다

`HermesRunEventStream` 이 받고 `ChatService.stream` 이 화면으로 중계한다.
저장하는 답은 스트림 조각이 아니라 실행 결과에서 가져온다.
근거는 [ADR-008](../../docs/adr/ADR-008-스트리밍은-보여주기용이고-저장은-실행-결과로-한다.md) 에 있다.

## phase-04 와 phase-05 는 옮겼다

Memory 와 실행 Graph 는 이 plan 에서 빼고 각각 별도 plan 으로 다시 세웠다.
설계를 더 해야 한다고 판단해 한 번 멈춘 뒤, 정하지 못했던 것을 정하고 다시 연 것이다.

`tasks/` 아래에서 그 plan 의 `index.json` 을 찾아 읽는다.

## phase-06 비용 (완료)

- models.dev 가격표를 기동할 때 한 번 읽어 메모리에 둔다
- 실행이 끝나는 자리에서 토큰 수를 그 가격표로 환산해 `pricing_version` 과 함께 남긴다
- 구독형 바인딩도 같은 방식으로 환산해 적는다. 구독료와 견줄 수 있게 하기 위해서다
- 가격을 찾지 못하면 금액 칸을 비우고, 기동은 계속한다

## phase-07 작업 영역 (되돌렸다)

아래는 만들었던 것이다.
등록된 영역이 0 이고 그것을 쓴 대화와 실행도 0 이라 제거하기로 했다.
근거는 [ADR-010](../../docs/adr/ADR-010-작업-영역을-제거하고-에이전트가-그-자리를-갖는다.md) 에 있다.

- `workspace` 표를 두고 `conversation`, `agent_execution` 에 `workspace_id` 를 더한다
- 관리자가 영역을 하나씩 등록한다. 공개 범위는 기본값이 없다
- 대화를 시작할 때 고른 영역이 그 대화에 고정되고, 실행마다 그 영역의 `AGENTS.md` 를
  `instructions` 로 넣는다
- 남의 개인 영역은 조회와 실행 모두 `WORKSPACE_NOT_FOUND` 로 응답한다
- 영역 디렉터리는 별도 저장소 `fos-agents` 를 읽기 전용으로 마운트해 읽는다.
  근거는 [ADR-006](../../docs/adr/ADR-006-작업-영역은-읽기-전용-의존이고-공개-범위는-기본값이-없다.md) 에 있다
