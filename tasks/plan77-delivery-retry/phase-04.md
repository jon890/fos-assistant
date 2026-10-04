# Phase 04. e2e 시나리오로 다시 전달을 왕복시킨다

**Execution profile**: standard

## 목표

Hermes 대역을 띄운 전체 흐름에서 승인한 커넥터 호출의 결과 전달이 실패한 뒤, 다시 전달하면 커넥터 호출 없이 답이 생기는 것을 본다.
backend 단위 검사가 대역 저장소와 직접 만든 줄로 본 것을 HTTP 경로와 실제 스키마에서 한 번 더 확인하려는 것이다.

**범위 외**: 화면(phase 05). backend 코드는 고치지 않는다. 이 시나리오가 backend 결함을 찾으면 `PHASE_BLOCKED: <결함>` 을 출력하고 멈춘다.

## 컨텍스트

- 시나리오는 `test/e2e/scenarios/` 에 하나씩 두고 `test/e2e/run.ts` 가 import 해 차례로 돌린다. 틀은 `Scenario`(`name`, `run(context)`) 이고 도구는 `test/e2e/harness.ts` 의 `call`, `expect`, `expectStatus`, `step`, `fail` 이다
- 승인 흐름은 `test/e2e/scenarios/connector-policy.ts` 가 본보기다. 연결을 등록하고, `CONNECTOR_TOOL_PROBE` 로 연결용 에이전트에게 `write_note` 를 부르게 해 승인 줄을 만들고, `/connector-actions/{actionId}/approve` 로 승인한다. 그 시나리오는 끝에서 연결을 해제하므로 이 시나리오도 처음에 등록하고 끝에 해제한다. `probeIn`, `requestNumber`, `awaitMessages`, `messagesOf`, `mine` 같은 도우미는 그 파일에서 옮겨 쓴다
- 대역 Hermes(`test/e2e/fake-hermes.ts`)의 `busy()` 는 그 뒤의 run 제출을 429 로 거절하고 `clearBusy()` 가 푼다. Control Plane 은 이것을 `HERMES_BUSY` 로 받는다. `connectorToolCalls()` 가 커넥터 서버에 닿은 호출을, `submitCount()` 가 run 제출 수를, `lastSubmittedInput()` 이 마지막 입력을 준다
- SSE 응답 읽기는 `test/e2e/scenarios/regenerate.ts` 의 `events(response)` 를 따른다(`web/src/lib/stream.ts` 의 `readEventStream`)
- phase 03 이 만든 경로: `POST /api/v1/chat/conversations/{conversationId}/deliveries/{deliveryId}/retry/stream`. 이력 `GET /api/v1/chat/conversations/{conversationId}/messages` 의 `SYSTEM` 줄에 `delivery: { id, status }` 가 붙는다. 다시 전달의 알림 줄 글은 「맡긴 일의 결과를 다시 전해요」 다

**근거 문서**: `docs/backend/agent-delegation.md` 의 「다시 전달할 때」 와 「다시 전달이 갈리는 지점」, `AGENTS.md` 의 「확인」

## 의도 메모

- 결과 글과 메모 글은 지어낸 글만 쓴다
- `busy()` 를 켠 채 시나리오가 실패해 끝나도 뒤 시나리오가 흔들리지 않게 `finally` 에서 `clearBusy()` 와 연결 해제를 한다
- 자동 turn 은 가상 스레드에서 돈다. 묶음 상태는 이력을 되풀이해 읽어 기다린다. 고정 시간 sleep 으로 기다리지 않는다

## 작업 항목

### 1. `test/e2e/scenarios/delivery-retry.ts`

`export const deliveryRetryScenario: Scenario` 하나를 낸다. 단계는 아래 순서다.

1. 연결을 등록하고 `write_note` 승인 줄을 만든다
2. `context.hermes.busy()` 를 켠 뒤 승인한다. 이력이 `SYSTEM` 줄로 끝나고 그 줄의 `delivery.status` 가 `FAILED` 가 될 때까지 기다린다. 커넥터 서버에 닿은 `write_note` 호출 수를 적어 둔다
3. 같은 묶음을 바로 다시 전달하면 `HERMES_BUSY` 의 `error` 사건으로 끝나고 묶음은 다시 `FAILED` 다
4. `clearBusy()` 뒤 다시 전달한다. 사건이 `system`, `started` 를 거쳐 `done` 으로 끝난다. 이력의 끝이 「맡긴 일의 결과를 다시 전해요」 `SYSTEM` 줄과 `ASSISTANT` 줄이고, 그 `SYSTEM` 줄의 `delivery.status` 가 `DELIVERED` 다. 첫 알림 줄에는 `delivery` 가 없다
5. 마지막 입력에 「승인한 동작의 결과가 도착했다.」 와 `<external-data>` 가 있다. `write_note` 호출 수가 2번에서 적은 수와 같다
6. 같은 묶음을 다시 부르면 `error` 사건의 코드가 `DELIVERY_NOT_RETRYABLE` 이다. 다른 사용자의 토큰으로 부르면 404 다
7. 연결을 해제한다

### 2. `test/e2e/run.ts`

`deliveryRetryScenario` 를 import 해 `connectorPolicyScenario` 바로 뒤에 넣는다. 그 순서여야 커넥터 대역과 연결 해제 상태가 앞 시나리오와 같다.

## 검증

```bash
# cwd: 저장소 root
node test/e2e/run.ts
node --test 'test/unit/**/*.test.ts'
scripts/quality.sh check
```

기대: 모두 종료 코드 0. `node test/e2e/run.ts` 의 출력에 「다시 전달」 시나리오의 단계가 모두 보인다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `test/e2e/scenarios/delivery-retry.ts` | 신규 |
| `test/e2e/run.ts` | 수정 |
