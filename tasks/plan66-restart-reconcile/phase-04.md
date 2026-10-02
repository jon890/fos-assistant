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
- **지금 `chatQueueRestartScenario` 는 「재시작 뒤 쌓인 글이 곧바로 간다」 를 단언한다.** 붙잡힌 turn 이 `ORPHANED` 로 끝난다는 전제였다. 이제 그 turn 은 Hermes 에서 아직 도는 것으로 읽혀 다시 붙고, 쌓인 글은 그 turn 이 끝난 뒤에 간다. 그 시나리오는 재시작 뒤 `context.hermes.releaseHeldRun()` 을 부르도록 고친다. 중지로 멈춘 대기 줄이 그대로 멈춰 있다는 단언은 그대로다
- 실행 상태는 `GET /api/v1/usage/...` 나 시나리오가 이미 쓰는 실행 조회 경로로 읽는다. `test/e2e/scenarios/usage-cost.ts` 와 `test/e2e/scenarios/stop.ts` 가 실행 줄의 `status`, `errorCode`, 토큰을 읽는 방식을 따른다
- 도는 turn 을 묻는 경로는 `GET /api/v1/chat/conversations/{conversationId}/running` 이다(`ChatService.running`). 응답은 `running`, `executionId`, `startedAt` 이다
- 사용량 화면의 상태 문구는 `web/src/components/usage/execution-list.tsx` 의 `executionStatusLabel` 이다. `ORPHANED` 를 「중간에 중단됨」 으로 보인다. 단위 검사는 `test/unit/execution-status.test.ts` 다
- e2e 와 문서에 홈서버의 값을 적지 않는다(`AGENTS.md` 의 「공개 저장소」)

**근거 문서**: `docs/hermes/runs-api.md` 의 「실행 조회가 답하는 기간」, `docs/backend/turn-control.md` 의 「기동할 때 남은 실행 정리」 의 「갈리는 지점」, `docs/backend/schema/execution.md` 의 「끝나지 않은 실행」

## 의도 메모

- 가짜 Hermes 가 실제와 다른 모양이면 테스트는 통과하고 운영에서 동작하지 않는다(`AGENTS.md` 의 「Hermes 연동을 바꿨으면 배포 뒤 왕복시켜 본다」). 조회는 도는 실행에 `running`, 끝난 실행에 같은 답을 되풀이하고, 지운 run 과 모르는 run 에 404 를 준다
- 종료 뒤 1시간을 실제로 기다리지 않는다. 가짜 Hermes 에 run 을 잊게 하는 손잡이를 둔다. gateway 가 다시 뜬 것과 1시간이 지난 것은 Control Plane 에서 같은 404 다
- 브라우저 검사는 더하지 않는다. 화면은 고치지 않았고, 다시 붙은 turn 은 화면에 「도는 turn 이 있는 대화」 와 같게 보인다. 그 경로의 검사는 이미 있다

## 작업 항목

### 1. `test/e2e/fake-hermes.ts`

- `GET /p/{profile}/v1/runs/{runId}` 가 모르는 run 에 404 와 `{"error": {"code": "run_not_found", ...}}` 를 주는지 확인하고, 아니면 맞춘다. 실제 Hermes 의 모양은 `docs/hermes/runs-api.md` 가 갖는다
- 붙잡힌 run 의 조회가 종료 상태가 아닌 `status`(`running`)를 주는지 확인한다. 지금 `queued` 같은 값을 주면 그대로 둬도 된다. Control Plane 은 종료 상태가 아닌 값을 모두 도는 중으로 읽는다
- `FakeHermes` 에 `forgetRun(runId: string): void` 를 더한다. 그 run 을 `runs` 에서 지운다. 그 뒤 조회와 중지는 404 다
- `FakeHermes` 에 `lastSubmittedRunId(profile?: string): string | undefined` 같은 읽기가 이미 있으면 그것을 쓰고, 없으면 시나리오가 run 번호를 알 방법을 하나 더한다(붙잡힌 run 의 번호를 돌려주는 읽기)

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

**다. 위임 실행이 도는 중에 다시 뜬다** (`test/e2e/scenarios/delegation.ts` 와 `test/e2e/delegation-support.ts` 의 방식으로 위임 자식을 붙잡을 수 있을 때)

1. 위임 자식의 run 을 붙잡은 채 부모 turn 을 끝낸다
2. `context.restartControlPlane()` 뒤 붙잡은 run 을 놓는다
3. 자식 줄이 `SUCCEEDED` 이고 부모 대화에 그 결과를 전하는 자동 turn 이 **한 번** 열린다

가짜 Hermes 로 위임 자식만 붙잡기가 이 phase 의 크기를 넘으면 「다」 를 빼고, phase 03 의 backend 테스트가 그 경우를 검사한다는 것을 커밋 메시지에 적는다.

### 3. `test/e2e/scenarios/chat-queue.ts`

`chatQueueRestartScenario` 의 「Control Plane 을 강제로 내리고 다시 띄운다」 뒤에 `context.hermes.releaseHeldRun()` 을 넣고 step 글을 지금 동작에 맞게 고친다. 붙잡힌 turn 의 답이 먼저 오고 그 뒤에 쌓인 글이 간다.

### 4. `web/src/components/usage/execution-list.tsx` 와 `test/unit/execution-status.test.ts`

`executionStatusLabel` 이 `REMOTE_RUN_LOST`, `RECONCILE_TIMEOUT`, `RECONCILE_UNREACHABLE` 도 「중간에 중단됨」 으로 보인다. `ORPHANED` 와 같은 줄에서 판정한다.
`test/unit/execution-status.test.ts` 가 `executionStatusLabel` 을 검사하면 세 코드의 기대를 더한다. 검사하지 않으면 그 파일에 `executionStatusLabel` 검사를 더한다(import 방식은 그 파일이 `executionStatusVariant` 를 가져오는 방식과 같다).

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
| `test/e2e/scenarios/chat-queue.ts` | 수정 |
| `test/e2e/run.ts` | 수정 |
| `web/src/components/usage/execution-list.tsx` | 수정 |
| `test/unit/execution-status.test.ts` | 수정 |
