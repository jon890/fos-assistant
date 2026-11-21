# Phase 04. e2e 와 브라우저 테스트로 한 답과 맡긴 도구 줄을 확인한다

**Execution profile**: standard

## 목표

가짜 Hermes 로 Control Plane 을 띄운 e2e 가 「상한 안에 끝난 맡김은 답 하나, 넘긴 맡김은 결과 답이 따로」 와 「맡긴 실행의 도구 사건이 트리에 남는다」 를 확인하고, 브라우저 테스트가 도는 답의 도구 줄과 「맡겨 둠」 을 확인한다.

**범위 외**: 운영 왕복(원격 검증 목록).

## 컨텍스트

**요구사항**: REQ1, REQ2, REQ3

**근거 문서**: `docs/features/assistant-hub/spec.md`(REQ1-REQ3), `docs/features/assistant-hub/design.md` 의 「검증 전략」

- e2e 는 `node test/e2e/run.ts` 가 백엔드 하나를 가짜 Hermes 와 띄우고 모든 시나리오를 돌린다. 백엔드 설정은 `test/e2e/run.ts` 의 `spawn` 환경 변수(`ASSISTANT_...`)로 준다.
- 위임 시나리오는 `test/e2e/scenarios/delegation.ts`, 부품은 `test/e2e/delegation-support.ts`(`callTool`, `awaitStatus`, `tree`)다. 결과 자동 답을 보는 시나리오는 `test/e2e/scenarios/chat-queue.ts` 와 `delegation.ts` 안에 있다. 가짜 Hermes 는 `test/e2e/fake-hermes.ts` 와 `test/e2e/fake-hermes/` 다.
- 브라우저 테스트의 본보기는 `web/test/browser/agent-team-delegation.spec.ts` 다. 위임은 가짜 Hermes 가 만들지 않으므로 이력과 대화 단위 SSE 를 대역한다.

## 의도 메모

- e2e 백엔드는 하나라 기다림 상한을 시나리오마다 바꾸지 못한다. phase 01 이 e2e 의 상한을 `0s` 로 두었으므로 기존 시나리오의 기대(결과 자동 답)는 그대로다. 기다림 자체(REQ1, REQ2)는 phase 01 의 backend 통합 테스트가 본다. 이 phase 의 e2e 는 맡긴 실행의 도구 사건(REQ3)만 더한다.

## 작업 항목

### 1. `test/e2e/scenarios/delegation.ts` 와 `test/e2e/fake-hermes.ts`

- 가짜 Hermes 가 맡긴 자식 run 의 사건 스트림(`/v1/runs/{id}/events`)에 도구 사건 한 쌍(`tool.started`, `tool.completed`)을 내도록 한다. 지금 대화 turn 의 run 이 사건을 내는 방식(`fake-hermes.ts` 의 사건 대본)을 자식 run 에도 쓰게 하는 것이 먼저다.
- 새 단계 「맡긴 실행의 도구 사건」: 맡긴 자식이 끝난 뒤 `tree`(`test/e2e/delegation-support.ts`)의 그 자식 노드에 `TOOL_STARTED`, `TOOL_COMPLETED` 가 있고, 사건 순서 번호가 1 부터 빈틈없으며, 노드의 `delegated` 가 참이다.

### 2. `web/test/browser/agent-team-delegation.spec.ts`

- 도는 답 만들기: `web/test/browser/activity-progress.spec.ts` 처럼 가짜 Hermes 대본에 `SCRIPT_PAUSE_EVENT` 를 넣어 답을 붙잡는다.
- 대역 SSE 의 순서: 이 파일의 `routeEvents(page, id, first, ready)` 에 `delegation_activity` 두 개를 `first` 로 주고, `ready` 는 도는 답의 작업 과정 줄(`activity-current`)이 보인 뒤에 푼다. 그래서 사건이 도는 답보다 먼저 와 버려지지 않는다.
- 단언 1: 블록을 펼치면 「쇼핑 담당」 도우미 줄 아래 검색 줄이 보인다.
- 단언 2: `hermes.releaseLongActivity()` 로 답을 끝내면 접힌 줄이 「끝까지 하지 못했어요」 가 아니고 도우미 줄에 「끝나면 이어서 알려 드려요」 가 보인다.

## 검증

```bash
node test/e2e/run.ts
cd web && pnpm test:browser test/browser/agent-team-delegation.spec.ts
cd web && pnpm lint
cd web && pnpm typecheck
node --test test/repo/doc-links.test.ts test/repo/doc-files.test.ts
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `test/e2e/scenarios/delegation.ts` | 수정 |
| `test/e2e/fake-hermes.ts` | 수정 |
| `web/test/browser/agent-team-delegation.spec.ts` | 수정 |
