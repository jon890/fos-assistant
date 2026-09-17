# Phase 01. 실행 이벤트를 브라우저까지 흘려 보낸다

**Execution profile**: standard

## 목표

Hermes 의 실행 이벤트를 Control Plane 이 받아 브라우저로 다시 보낸다.
답이 오는 동안 글자가 흐르고 도구 호출이 보인다.

**범위 외**

- 화면 작업은 phase-02 가 한다. 이 phase 는 서버 쪽 경로까지다.
- 실행 그래프 화면은 하지 않는다.
- 답을 쓰는 중에 새로 고쳤을 때 그 스트림에 다시 붙는 것은 하지 않는다.

## 컨텍스트

지금은 실행을 제출한 뒤 끝날 때까지 상태를 되물어 답을 한 번에 돌려준다.
`HttpHermesRunsClient.runToCompletion` 이 그 반복을 한다.

Hermes 가 스트리밍을 지원한다. 실측으로 확인했다.
`GET /v1/capabilities` 가 `run_events_sse` 를 `true` 로 알려준다.

`GET {apiBaseUrl}/v1/runs/{run_id}/events` 가 Server-Sent Events 로 아래를 보낸다.

| 사건 | 담는 것 |
| --- | --- |
| 토큰 델타 | 답의 조각 |
| `tool.started`, `tool.completed` | 도구 이름, 소요 시간, 결과 앞부분 |
| `subagent.start`, `subagent.complete` | subagent 실행 경계 |
| `run.completed`, `run.failed`, `run.cancelled` | 끝 |

10초마다 `: keepalive` 주석이 온다. **`:` 로 시작하는 줄은 건너뛴다.**

**스트림으로 받은 조각을 저장하지 않는다.**
스트림이 끊기면 잘린 답이 이력에 남는다.
저장하는 답은 실행이 끝난 뒤 상태 조회가 준 값으로 쓴다.
근거는 `docs/adr/ADR-008-스트리밍은-보여주기용이고-저장은-실행-결과로-한다.md` 에 있다.
**그 문서를 먼저 읽는다.**

따를 패턴을 경로로 짚는다.

| 무엇 | 경로 |
| --- | --- |
| Hermes 호출과 응답을 관대하게 읽는 방식 | `backend/src/main/java/com/bifos/assistant/hermes/HttpHermesRunsClient.java` |
| 실행 제출과 반복 조회 | 같은 파일의 `submit` 과 `poll` |
| 한 번의 대화 흐름 | `backend/src/main/java/com/bifos/assistant/chat/application/ChatService.java` |

**근거 문서**: `docs/flow.md`, `docs/adr/ADR-008-스트리밍은-보여주기용이고-저장은-실행-결과로-한다.md`

## 의도 메모

- 반복 조회 경로를 지우지 않는다. 스트림이 되지 않을 때 쓸 길이 남아야 한다.
- 실행이 끝난 뒤 상태를 한 번 더 읽는다. 스트림과 합쳐 두 번 읽는 것이 맞다.
  화면과 저장이 서로를 망가뜨리지 않는 대가다.
- 브라우저에는 우리 형식으로 다시 보낸다. Hermes 의 사건 이름을 그대로 흘리지 않는다.
  Hermes 가 이름을 바꾸면 화면이 깨진다.
- 실패도 기록한다. 스트림이 끊겨도 실행 기록은 남아야 한다.

## 작업 항목

### 1. 스트리밍 클라이언트를 더한다

`hermes/HermesRunEventStream.java` 를 만든다.

- `open(String apiBaseUrl, String profileName, String runId, Consumer<RunEvent> onEvent)` 를 둔다.
- 인증은 `HermesProfileKeyStore` 가 주는 key 로 한다.
- `:` 로 시작하는 줄을 건너뛴다.
- 읽지 못하면 예외를 던지고 부르는 쪽이 반복 조회로 넘어간다.

`hermes/dto/RunEvent.java` 를 만든다.
`type`, `text`, `toolName`, `detail` 을 담는 record 로 두고, 모르는 사건은 `type` 만 채운다.

Hermes 가 새 사건을 더해도 깨지지 않아야 한다.

### 2. 우리 사건 형식을 정한다

`chat/application/ChatEvent.java` 를 만든다.

| `type` | 담는 것 |
| --- | --- |
| `delta` | 답의 조각 |
| `tool` | 도구 이름과 상태 |
| `done` | 저장된 메시지 번호와 실행 번호 |
| `error` | 오류 코드와 문구 |

`done` 은 실행이 끝나고 저장까지 마친 뒤에만 보낸다.
브라우저는 `done` 을 받으면 그 실행이 이력에 남았다고 믿을 수 있다.

### 3. 스트리밍 엔드포인트를 만든다

`POST /api/v1/chat/messages/stream` 을 만든다.
요청 본문은 지금 `POST /api/v1/chat/messages` 와 같다.

흐름은 이렇다.

1. 에이전트와 작업 영역을 정하고 사용자 메시지를 저장한다. 지금과 같다.
2. 실행을 제출해 `run_id` 를 받는다.
3. 이벤트 스트림을 열어 `delta` 와 `tool` 을 브라우저로 보낸다.
4. 종료 사건이 오거나 스트림이 끊기면 **실행 상태를 한 번 읽는다.**
5. 그 결과로 답과 토큰을 저장하고 비용을 환산한다.
6. `done` 을 보낸다.

3번에서 실패해도 4번으로 간다. 스트림이 끊긴 것이 실행이 실패한 것은 아니다.

응답은 `text/event-stream` 이다.
Spring MVC 의 `SseEmitter` 를 쓴다. 새 의존을 넣지 않는다.

기존 `POST /api/v1/chat/messages` 는 그대로 둔다.

### 4. 흐름 문서를 고친다

`docs/flow.md` 의 「대화 한 번」 에 스트리밍 경로를 더한다.
두 경로가 함께 있다는 것과, 저장은 어느 쪽이든 실행 상태 조회가 준 값으로 한다는 것을 적는다.

### 5. 이 phase 를 검증하는 테스트

`test/e2e/fake-hermes.ts` 가 `GET /v1/runs/{id}/events` 를 SSE 로 답하도록 고친다.
토큰 델타 몇 개와 도구 사건 하나, 종료 사건을 보낸다.
`: keepalive` 줄도 한 번 보내 건너뛰는지 확인한다.

`test/e2e/scenarios/` 에 시나리오를 더하고 `test/e2e/run.ts` 에 등록한다.

- 스트림으로 `delta` 가 여러 번 오고 마지막에 `done` 이 온다.
- `done` 뒤에 메시지를 조회하면 답이 온전하다.
- 저장된 답이 `delta` 를 이어 붙인 것이 아니라 실행 상태의 `output` 과 같다.
- 스트림이 중간에 끊겨도 메시지와 실행 기록이 남는다.
- `: keepalive` 줄이 답에 섞이지 않는다.

세 번째와 네 번째가 이 phase 의 핵심이다.

## 검증

```bash
# cwd: 저장소 root
cd backend && ./gradlew test
```

```bash
# cwd: 저장소 root
node test/e2e/run.ts
```

```bash
# cwd: 저장소 root
~/personal/fos-skills/korean-check/scripts/check.sh docs/flow.md
```

## Critical Files

| 파일 | 변경 |
| --- | --- |
| `backend/src/main/java/com/bifos/assistant/hermes/HermesRunEventStream.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/hermes/dto/RunEvent.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/application/ChatEvent.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/application/ChatService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/presentation/ChatController.java` | 수정 |
| `docs/flow.md` | 수정 |
| `test/e2e/fake-hermes.ts` | 수정 |
| `test/e2e/scenarios/` | 신규 |

## 끝낸 뒤

`tasks/plan004-streaming/index.json` 의 이 phase 를 `completed` 로 바꾸고
`current_phase` 를 다음 번호로 올린다.
