# Phase 04. 대기열을 e2e 로 검사한다

**Execution profile**: standard

## 목표

홈서버 없이 전체 흐름으로 대기열을 검사하는 시나리오 넷을 더한다.
둘 쌓기 합쳐짐, 대기 중 취소, 대기열과 위임 결과의 순서, 재시작 뒤 전달이다.

**범위 외**: 화면 검사(Phase 05). backend 의 동작은 Phase 03 이 끝냈다.

## 컨텍스트

- 실행기는 `test/e2e/run.ts` 다. `SCENARIOS` 배열에 순서대로 등록하고, 시나리오는 앞의 것이 만든 상태 위에서 이어진다. `node test/e2e/run.ts` 로 돌린다.
- `test/e2e/harness.ts` 가 `Context`, `Scenario`, `step`, `fail`, `expect`, `call`, `expectStatus` 를 준다. SSE 를 읽는 도우미는 없고 시나리오가 `web/src/lib/stream.ts` 의 `readEventStream` 을 import 한다.
- 가짜 Hermes(`test/e2e/fake-hermes.ts`)의 손잡이: `holdNextRun()`, `waitForHeldRun()`, `heldRun()`, `releaseHeldRun()`, `stoppedRuns()`, `submitCount()`, `lastSubmittedInput()`, `clearBlockedProviders()`. **붙잡을 수 있는 run 은 한 번에 하나다.**
- 보통 run 의 답은 입력을 되돌린 글이다. `lastSubmittedInput()` 으로 합친 글이 Hermes 입력으로 갔는지 본다. 입력 앞에는 결과물 폴더 안내가 붙으므로 「끝이 같다」 로 견준다.
- turn 을 붙잡는 순서의 본보기는 `test/e2e/scenarios/stop.ts` 의 `stream(...)` 과 `within(...)` 이다.
- 위임을 MCP 로 부르는 본보기는 `test/e2e/scenarios/delegation.ts` 다. 지역 도우미 `openStream`, `callTool`, `contextFor`, `tree`, `awaitStatus` 와 GROUP 에이전트 등록, 토큰 발급, 정리 순서가 거기 있다. `agent_status` 로 끝난 결과를 읽으면 전한 것으로 적혀 깨우지 않으므로, 자식이 끝났는지는 실행 나무(`/usage/executions/{id}/tree`)로 본다.
- e2e 는 Spring profile 을 주지 않아 `assistant.delegation-wake.enabled` 가 기본값 true 로 돈다.
- 지금 `startControlPlane` 은 `DB_URL` 을 메모리 H2 로 준다. 프로세스를 내리면 데이터가 사라진다.
- `modelSelectionScenario` 는 막힌 provider 를 만들어 두고 끝난다. 그 주석이 「마지막에 둔다」 고 적는다.

대기열 경로(모두 `context.api` 아래, 대화는 공개 식별자로 가리킨다):

| 경로 | 응답 |
| --- | --- |
| `GET /chat/conversations/{id}/pending` | 200 `{ "held", "items": [{ "id", "text", "createdAt" }] }` |
| `POST /chat/conversations/{id}/pending` 본문 `{ "text" }` | 201 대기 줄 |
| `DELETE /chat/conversations/{id}/pending/{pendingId}` | 204. 없으면 404 `PENDING_MESSAGE_NOT_FOUND` |
| `POST /chat/conversations/{id}/pending/send` | 202 대기 줄 |

**근거 문서**: `docs/flow.md` 의 「응답 중에 보낼 때」 절, `docs/code-architecture.md` 의 「응답 중 대기열」 절, `AGENTS.md` 의 「확인」 절

## 의도 메모

- 재시작을 backend 단위 검사만으로 보는 안은 버렸다. 기동 정리와 기동 확인의 순서, Flyway 스키마에서의 동작은 프로세스를 실제로 다시 띄워야 보인다.
- 재시작을 흉내 내려고 Control Plane 에 시험용 경로를 만들지 않는다. 실행기가 프로세스를 내리고 다시 띄운다.
- 대기 메시지로 연 turn 의 끝은 메시지 목록을 다시 읽어 기다린다. `/events` SSE 를 여는 것보다 단순하고, 연결 전에 지나간 사건을 놓치지 않는다.

## 작업 항목

### 1. `test/e2e/run.ts` 와 `test/e2e/harness.ts`: 재시작

- `DB_URL` 을 실행마다 만든 임시 디렉터리 아래의 파일 H2 로 바꾼다. `MODE=MySQL` 은 그대로 둔다. 다시 띄워도 같은 파일을 읽는다.
- Control Plane 을 내리고 같은 환경으로 다시 띄운 뒤 `/actuator/health` 를 기다리는 함수를 만든다. 프로세스 그룹에 `SIGTERM` 을 보내고 그 프로세스가 끝난 것을 확인한 뒤 띄운다. `main` 의 `finally` 가 다시 띄운 프로세스를 내리게 한다.
- `Context` 에 `restartControlPlane(): Promise<void>` 를 더한다. Javadoc 에 「메모리에만 있던 turn 잠금과 SSE 구독이 사라진다」 를 적는다.
- 기존 시나리오가 파일 H2 에서도 그대로 통과해야 한다.

### 2. `test/e2e/delegation-support.ts` 신규

`test/e2e/scenarios/delegation.ts` 의 지역 도우미 가운데 두 시나리오가 함께 쓸 것(`openStream`, `callTool`, `contextFor`, `tree`, `awaitStatus`, `within`, `parsed`)을 이 파일로 옮기고 `delegation.ts` 가 import 하게 한다. 동작을 바꾸지 않는다.

### 3. `test/e2e/scenarios/chat-queue.ts` 신규

`export const chatQueueScenario: Scenario` 와 `export const chatQueueRestartScenario: Scenario` 를 낸다.
지역 도우미: 대화의 메시지 목록을 조건이 참이 될 때까지 다시 읽는 `awaitMessages(context, conversationId, predicate, 제한 시간)`.
모든 단계는 `finally` 에서 `releaseHeldRun()` 을 불러 붙잡은 run 을 남기지 않는다.

`chatQueueScenario` 의 단계:

1. **둘 쌓기 합쳐짐**: `holdNextRun()` 뒤 첫 글을 스트림으로 보내고 `started` 를 기다린다. 대기 메시지 둘을 더한다(둘 다 201). `GET pending` 이 두 줄이고 `held` 가 false 다. `releaseHeldRun()` 뒤 메시지가 `USER`, `ASSISTANT`, `USER`, `ASSISTANT` 가 될 때까지 기다린다. 둘째 `USER` 의 글이 두 글을 빈 줄 하나로 이은 글이다. `lastSubmittedInput()` 이 그 글로 끝난다. `GET pending` 이 비었다. 이 구간에서 `submitCount()` 가 2 늘었다.
2. **대기 중 취소**: 같은 대화에서 turn 을 붙잡고 「취소할 글」 과 「남길 글」 을 더한다. 첫 줄을 `DELETE` 한다(204). 같은 번호를 다시 지우면 404 `PENDING_MESSAGE_NOT_FOUND` 다. 푼 뒤 새 `USER` 의 글이 「남길 글」 이고 「취소할 글」 을 담지 않는다.
3. **중지하면 멈춰 둔다**: turn 을 붙잡고 대기 메시지 하나를 더한 뒤 `POST /chat/executions/{id}/stop` 으로 멈춘다. `GET pending` 의 `held` 가 true 가 될 때까지 기다린다. 1초 뒤에도 `submitCount()` 가 늘지 않았다. `POST pending/send`(202) 뒤 그 글이 `USER` 로 저장되고 답이 온다.
4. **상한**: turn 을 붙잡고 다섯을 더한 뒤 여섯째가 409 `PENDING_QUEUE_FULL` 이다. 남의 토큰(`context.tokens.kid`)으로 `GET pending` 을 부르면 404 `CONVERSATION_NOT_FOUND` 다. 다섯을 모두 `DELETE` 하고 푼다.
5. **대기열과 위임 결과의 순서**: `delegation.ts` 와 같은 방식으로 GROUP 에이전트를 등록하고 토큰을 발급한다. 에이전트 코드는 `delegation.ts` 의 것과 다르게 짓는다. 뿌리 turn 을 붙잡고, 그 turn 의 session 으로 `agent_delegate` 를 불러 자식을 맡긴다(자식은 붙잡지 않아 곧 끝난다). 실행 나무에서 자식이 `SUCCEEDED` 가 될 때까지 기다린다. 대기 메시지 하나를 더한다. 뿌리 turn 을 푼다. 메시지 끝이 `USER`(대기 글), `ASSISTANT`, `SYSTEM`, `ASSISTANT` 순서가 될 때까지 기다린다. `finally` 에서 토큰을 폐기하고 에이전트를 끈다.

`chatQueueRestartScenario` 의 단계:

1. `context.hermes.clearBlockedProviders()` 를 먼저 부른다.
2. 멈춘 대화를 만든다. 새 대화에서 turn 을 붙잡고 대기 메시지 하나를 더한 뒤 중지한다. `GET pending` 의 `held` 가 true 가 될 때까지 기다린다. 붙잡을 수 있는 run 이 하나라 다음 단계보다 먼저 한다.
3. 보낼 대화를 만든다. 다른 새 대화에서 turn 을 붙잡고 `started` 를 기다린다. 대기 메시지 「재시작 뒤 보낼 글」 을 더한다. 이 turn 은 풀지 않는다.
4. `context.restartControlPlane()`.
5. 보낼 대화에 「재시작 뒤 보낼 글」 이 `USER` 로 저장되고 그 뒤에 `ASSISTANT` 가 올 때까지 기다린다. `GET pending` 이 비었다.
6. 멈춘 대화는 `GET pending` 이 그대로 한 줄이고 `held` 가 true 다. 새 `USER` 가 없다.
7. `releaseHeldRun()` 으로 가짜 Hermes 에 남은 붙잡음을 푼다.

### 4. `test/e2e/run.ts`: 등록

- `chatQueueScenario` 를 `connectorScenario` 뒤, `busyScenario` 앞에 둔다. 주석에 「에이전트를 하나 만들고 끄므로 에이전트 수를 세는 시나리오 뒤에 둔다」 를 적는다.
- `chatQueueRestartScenario` 를 맨 끝에 둔다. `modelSelectionScenario` 의 주석을 「막힌 provider 를 만들어 두고 끝나므로 turn 을 돌리는 시나리오 가운데 마지막에 둔다」 로 고치고, 재시작 시나리오 주석에 「Control Plane 을 다시 띄우므로 맨 끝에 둔다. 막힌 provider 를 먼저 푼다」 를 적는다.

### 5. 공개 저장소 확인

새 코드와 주석에 포트 번호와 주소 형태를 리터럴로 적지 않는다. `scripts/check-public-safe.sh` 의 형태 패턴에 걸린다.

## 검증

```bash
cd backend && ./gradlew test
node test/e2e/run.ts
scripts/check-public-safe.sh
```

- `node test/e2e/run.ts` 는 `./gradlew test` 를 먼저 돌린 뒤에 돌린다. 건너뛰면 앞선 실행이 남긴 데이터에 걸린다.
- `node test/e2e/run.ts` 의 끝 줄이 `모두 통과했다` 이고 출력에 `== ` 로 시작하는 두 시나리오 이름이 있어야 한다.
- `scripts/check-public-safe.sh` 는 종료 코드 0 이어야 한다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `test/e2e/run.ts` | 수정 |
| `test/e2e/harness.ts` | 수정 |
| `test/e2e/delegation-support.ts` | 신규 |
| `test/e2e/scenarios/delegation.ts` | 수정 |
| `test/e2e/scenarios/chat-queue.ts` | 신규 |
