# Phase 02. 화면에서 글자가 흐른다

**Execution profile**: standard

## 목표

답이 오는 동안 글자가 흐르고 도구 호출이 보인다.
끝나면 저장된 답으로 자리를 잡는다.

**범위 외**

- 실행 그래프 화면은 하지 않는다.
- 답을 쓰는 중에 새로 고쳤을 때 그 스트림에 다시 붙는 것은 하지 않는다.
  다시 열면 저장된 답이 보인다.

## 컨텍스트

phase-01 이 `POST /api/v1/chat/messages/stream` 을 만들었다.
`text/event-stream` 으로 아래를 보낸다.

| `type` | 담는 것 |
| --- | --- |
| `delta` | 답의 조각 |
| `tool` | 도구 이름과 상태 |
| `done` | 저장된 메시지 번호와 실행 번호 |
| `error` | 오류 코드와 문구 |

`done` 은 저장까지 마친 뒤에만 온다.
그래서 `done` 을 받으면 이력을 다시 읽어도 된다.

**화면에 쌓인 조각을 최종 답으로 삼지 않는다.**
`done` 을 받으면 그 대화의 메시지를 다시 읽어 저장된 답으로 바꾼다.
화면에서 본 것과 저장된 것이 다를 수 있고, 저장된 쪽이 정본이다.
근거는 `docs/adr/ADR-008-스트리밍은-보여주기용이고-저장은-실행-결과로-한다.md` 에 있다.

따를 패턴을 경로로 짚는다.

| 무엇 | 경로 |
| --- | --- |
| 대화 화면 | `web/src/components/chat-panel.tsx` |
| 서버 라우트 | `web/src/app/api/chat/route.ts` |
| 오류 문구 | `web/src/components/error-message.ts` |

**근거 문서**: `docs/flow.md`, `docs/adr/ADR-008-스트리밍은-보여주기용이고-저장은-실행-결과로-한다.md`

## 의도 메모

- `EventSource` 를 쓰지 않는다. 그것은 `GET` 만 보낸다.
  `fetch` 로 `POST` 한 뒤 응답 본문을 읽어 줄 단위로 나눈다.
- 스트리밍이 실패하면 기존 `POST /api/v1/chat/messages` 로 넘어간다.
  사용자는 글자가 흐르지 않을 뿐 답은 받는다.
- 도구 호출은 답과 섞지 않고 따로 보인다. 답 본문에 도구 이름이 끼면 읽기 어렵다.
- 보내는 중에 다시 보내지 못하게 잠근다. 이력 작업에서 이미 그렇게 했다.

## 작업 항목

### 1. 서버 라우트를 더한다

`web/src/app/api/chat/stream/route.ts` 를 만든다.

- `POST` 가 Control Plane 의 `/api/v1/chat/messages/stream` 을 부른다.
- **응답 본문을 그대로 흘려 보낸다.** 모아서 한 번에 보내면 스트리밍이 사라진다.
- `Content-Type` 을 `text/event-stream` 으로 두고 버퍼링을 끈다.
- 토큰은 `callControlPlane` 과 같은 방식으로 만든다.
  그 함수가 응답을 모아 읽으므로 그대로 쓸 수 없다. 토큰을 만드는 부분만 함수로 빼서 함께 쓴다.

`web/src/lib/control-plane.ts` 에서 토큰을 만드는 부분을 내보낸다.

### 2. 스트림을 읽는 함수를 더한다

`web/src/lib/stream.ts` 를 만든다.

- `fetch` 응답 본문을 읽어 SSE 줄을 사건으로 바꾼다.
- `:` 로 시작하는 줄을 건너뛴다.
- 빈 줄이 사건의 끝이다.
- 사건마다 넘겨받은 함수를 부른다.

### 3. 대화 화면이 스트림을 쓴다

`web/src/components/chat-panel.tsx` 를 고친다.

- 보낼 때 `/api/chat/stream` 을 부른다.
- `delta` 를 받을 때마다 지금 쓰고 있는 답에 이어 붙여 보인다.
- `tool` 은 답과 별도로 보인다. 도구 이름과 진행 상태를 한 줄로 둔다.
- `done` 을 받으면 그 대화의 메시지를 다시 읽어 저장된 답으로 바꾼다.
- `error` 를 받으면 문구를 보이고 쓴 문장을 입력창에 되돌린다.
- 스트림 자체가 열리지 않으면 기존 `/api/chat` 으로 한 번 더 시도한다.

### 4. 오류 문구를 더한다

`web/src/components/error-message.ts` 에 스트림이 끊겼을 때의 문구를 더한다.
실행은 계속되고 있을 수 있으니 이력을 다시 보라고 안내한다.

### 5. 이 phase 를 검증하는 e2e 테스트

`test/e2e/scenarios/` 에 더하고 `test/e2e/run.ts` 에 등록한다.
화면 없이 스트림 응답을 직접 읽어 확인한다.

- `delta` 가 여러 번 오고 마지막에 `done` 이 온다.
- `done` 의 메시지 번호로 이력을 읽으면 답이 온전하다.
- `delta` 를 이어 붙인 것과 저장된 답이 같은지 비교하고, 다르면 저장된 쪽을 쓴다.
- `: keepalive` 줄이 사건으로 올라오지 않는다.

## 검증

```bash
# cwd: 저장소 root
cd web && pnpm typecheck
cd web && pnpm build
```

빌드는 자리표시자 환경 변수가 필요하다. `web/Dockerfile` 이 쓰는 것과 같다.

```bash
# cwd: 저장소 root
node test/e2e/run.ts
```

```bash
# cwd: 저장소 root
grep -rn "EventSource" web/src/ && echo "실패: EventSource 는 POST 를 보내지 못한다" || echo "통과"
```

## Critical Files

| 파일 | 변경 |
| --- | --- |
| `web/src/app/api/chat/stream/route.ts` | 신규 |
| `web/src/lib/stream.ts` | 신규 |
| `web/src/lib/control-plane.ts` | 수정 |
| `web/src/components/chat-panel.tsx` | 수정 |
| `web/src/components/error-message.ts` | 수정 |
| `test/e2e/scenarios/` | 신규 |
| `test/e2e/run.ts` | 수정 |

## 끝낸 뒤

`tasks/plan004-streaming/index.json` 의 이 phase 를 `completed` 로 바꾸고
plan 의 `status` 를 `completed` 로 바꾼다.
