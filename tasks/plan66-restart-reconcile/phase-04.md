# Phase 04. 가짜 Hermes 의 실행 조회와 e2e 시나리오, 사용량 화면 문구

**Execution profile**: standard

## 목표

Control Plane 만 다시 띄웠을 때 Hermes 에서 계속 돈 실행의 답과 사용량이 남는 것을 전체 흐름으로 검사한다.
가짜 Hermes 의 실행 조회를 실제와 같은 모양으로 맞추고, 새 오류 코드를 사용량 화면이 읽을 수 있는 문구로 보인다.

**범위 외**: backend 동작 변경. phase 03 까지에서 끝났다. 이 phase 에서 결함을 찾으면 backend 를 고치고 그 파일을 「변경 파일」 에 더한다.

## 컨텍스트

- e2e 는 `node test/e2e/run.ts` 로 돈다. 가짜 Hermes(`test/e2e/fake-hermes.ts`)와 Control Plane 을 띄우고 `SCENARIOS` 를 차례로 돌린다. 시나리오는 앞의 것이 만든 상태 위에서 이어진다
- Control Plane 을 내렸다 다시 띄우는 것은 `Context.restartControlPlane()` 이다(`test/e2e/run.ts`). 선례는 `test/e2e/scenarios/chat-queue.ts` 의 `chatQueueRestartScenario` 다. 그 파일의 `holdTurn`, `enqueue`, `awaitMessages`, `messagesOf`, `pendingOf` 를 읽고 필요한 것은 내보내 함께 쓴다
- 가짜 Hermes 는 `runs`(`Map<string, Run>`)에 실행을 둔다. `Run.status` 가 조회 응답의 `status` 다. 다음 실행을 붙잡는 `holdNextRun()` 과 놓는 `releaseHeldRun()` 이 있고, 놓으면 그 run 이 `completed` 가 된다. 중지 경로는 `run.status = "cancelled"` 로 바꾼다
- `chatQueueRestartScenario` 는 앞 phase 가 지금 동작에 맞췄다. 재시작 뒤 `context.hermes.releaseHeldRun()` 으로 붙잡은 run 을 놓는다
- 실행 상태는 `GET /api/v1/usage/...` 나 시나리오가 이미 쓰는 실행 조회 경로로 읽는다. `test/e2e/scenarios/usage-cost.ts` 와 `test/e2e/scenarios/stop.ts` 가 실행 줄의 `status`, `errorCode`, 토큰을 읽는 방식을 따른다
- 도는 turn 을 묻는 경로는 `GET /api/v1/chat/conversations/{conversationId}/running` 이다(`ChatService.running`). 응답은 `running`, `executionId`, `startedAt` 이다
- 사용량 화면의 상태 문구는 `web/src/components/usage/execution-list.tsx` 의 `executionStatusLabel` 이다. `ORPHANED` 를 「중간에 중단됨」 으로 보인다. 그 파일은 `@/` 별칭과 컴포넌트를 import 해 `node --test` 로 읽을 수 없다
- 단위 검사 `test/unit/execution-status.test.ts` 는 `web/src/lib/execution-status.ts` 의 `executionStatusVariant` 를 가져온다. 그 파일은 다른 모듈을 런타임에 import 하지 않는다고 주석에 적혀 있다
- e2e 와 문서에 홈서버의 값을 적지 않는다(`AGENTS.md` 의 「공개 저장소」)

**근거 문서**: `docs/hermes/runs-api.md` 의 「실행 조회가 답하는 기간」, `docs/backend/turn-control.md` 의 「기동할 때 남은 실행 정리」 의 「갈리는 지점」, `docs/backend/schema/execution.md` 의 「끝나지 않은 실행」

## 의도 메모

- 가짜 Hermes 가 실제와 다른 모양이면 테스트는 통과하고 운영에서 동작하지 않는다(`AGENTS.md` 의 「Hermes 연동을 바꿨으면 배포 뒤 왕복시켜 본다」). 조회는 도는 실행에 `running`, 끝난 실행에 같은 답을 되풀이하고, 지운 run 과 모르는 run 에 404 를 준다
- 종료 뒤 1시간을 실제로 기다리지 않는다. 가짜 Hermes 에 run 을 잊게 하는 손잡이를 둔다. gateway 가 다시 뜬 것과 1시간이 지난 것은 Control Plane 에서 같은 404 다
- 브라우저 검사는 더하지 않는다. 화면은 고치지 않았고, 다시 붙은 turn 은 화면에 「도는 turn 이 있는 대화」 와 같게 보인다. 그 경로의 검사는 이미 있다

## 작업 항목

### 1. `test/e2e/fake-hermes.ts`

- 모르는 run 의 조회와 중지가 주는 404 본문을 실제 Hermes 의 모양으로 바꾼다. 지금은 `{ error: "no such run" }` 이다. `{ "error": { "message": "Run not found: <run_id>", "type": "invalid_request_error", "code": "run_not_found" } }` 로 둔다
- 붙잡힌 run 의 조회는 이미 `status: "running"` 을 준다. 고치지 않는다
- `FakeHermes` 에 `forgetRun(runId: string): void` 를 더한다. 그 run 을 `runs` 에서 지운다. 그 뒤 조회와 중지는 404 다
- 붙잡힌 run 의 번호는 이미 있는 `heldRun()` 으로 읽는다. 읽기를 새로 만들지 않는다

### 2. `test/e2e/scenarios/restart-reconcile.ts` (신규)

`export const restartReconcileScenario: Scenario`. `test/e2e/run.ts` 의 `SCENARIOS` 에 `chatQueueRestartScenario` 뒤로 넣는다. 앞 시나리오가 막아 둔 provider 가 남지 않게 `context.hermes.clearBlockedProviders()` 로 시작한다.

**가. Control Plane 만 다시 뜨고 Hermes 는 계속 돈다**

1. `holdNextRun()` 뒤 스트림으로 글을 보내 `started` 를 받는다(대화 번호와 실행 번호)
2. `context.restartControlPlane()`
3. `GET .../running` 이 `running: true` 와 그 실행 번호를 준다
4. 그 대화에 보통 보내기를 하면 409 `CONVERSATION_BUSY` 다. 대기 메시지로 넣으면(`POST .../pending`) 받아들인다
5. `context.hermes.releaseHeldRun()`
6. 메시지 목록에 그 실행의 `ASSISTANT` 답이 **하나** 생길 때까지 기다린다
7. 그 실행 줄이 `SUCCEEDED` 이고 입력과 출력 토큰이 `FAKE_USAGE` 와 같고 금액이 비어 있지 않다(가격표에 있는 모델로 돌렸을 때. `usage-cost.ts` 의 단언 방식을 따른다)
8. 쌓아 둔 글이 `USER` 로 저장되고 그 답이 온다
9. `context.restartControlPlane()` 을 한 번 더 한다. 답 메시지 수와 그 실행 줄의 토큰, `finishedAt` 이 그대로다

**나. Hermes 가 그 run 을 모른다**

1. `holdNextRun()` 뒤 새 대화에 글을 보내 `started` 를 받는다
2. `context.hermes.forgetRun(runId)`
3. `context.restartControlPlane()`
4. 그 실행 줄이 `FAILED`, `errorCode` 가 `REMOTE_RUN_LOST` 가 될 때까지 기다린다
5. `GET .../running` 이 `running: false` 다. 그 대화에 다시 보내면 답이 온다

`forgetRun` 뒤에도 가짜 Hermes 의 붙잡힌 상태는 남는다. 이 절은 `try` 로 감싸고 `finally` 에서 `context.hermes.releaseHeldRun()` 을 부른다. 놓지 않으면 뒤의 실행이 붙잡힌다.

위임 실행이 도는 중에 다시 뜨는 경우는 e2e 에 두지 않는다. backend 의 `RestartReconcilerTest` 가 검사한다. 가짜 Hermes 는 한 번에 run 하나만 붙잡아 부모 turn 을 끝내면서 자식만 붙잡을 수 없다.

### 3. `web/src/lib/execution-status.ts`, `web/src/components/usage/execution-list.tsx`, `test/unit/execution-status.test.ts`

`web/src/lib/execution-status.ts` 에 더한다. 다른 모듈을 import 하지 않는다.

```ts
/** 기동 정리가 실패로 적을 때 쓰는 오류 코드인가. 사용량 화면이 「중간에 중단됨」 으로 보인다. */
export function isInterruptedByRestart(errorCode: string | null | undefined): boolean
```

`ORPHANED`, `REMOTE_RUN_LOST`, `RECONCILE_TIMEOUT`, `RECONCILE_UNREACHABLE` 에 참이다.
`execution-list.tsx` 의 `executionStatusLabel` 은 `execution.errorCode === "ORPHANED"` 대신 이 함수를 쓴다.
`test/unit/execution-status.test.ts` 에 네 코드가 참이고 `null`, `undefined`, `HERMES_BUSY` 가 거짓인 검사를 더한다.

## 검증

```bash
# cwd: 저장소 root
node test/e2e/run.ts
node --test 'test/unit/**/*.test.ts'
cd web && pnpm typecheck
scripts/check-public-safe.sh
```

모두 종료 코드 0. e2e 출력에 `== ` 로 시작하는 새 시나리오 이름 줄과 마지막 줄 `모두 통과했다` 가 있어야 한다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `test/e2e/fake-hermes.ts` | 수정 |
| `test/e2e/scenarios/restart-reconcile.ts` | 신규 |
| `test/e2e/run.ts` | 수정 |
| `test/e2e/scenarios/chat-queue.ts` | 수정 |
| `web/src/lib/execution-status.ts` | 수정 |
| `web/src/components/usage/execution-list.tsx` | 수정 |
| `test/unit/execution-status.test.ts` | 수정 |
