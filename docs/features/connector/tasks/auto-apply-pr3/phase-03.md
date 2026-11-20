# Phase 03. 화면과 관리자 패널 문구

**Execution profile**: standard

## 목표

화면이 자동으로 반영 중임을 보이고 관리자를 기다린다고 말하지 않게 한다. 자동 반영이 실패한 바인딩은 실패를 알리고, 관리자 「다시 확인」 단추는 실패했거나 24시간이 넘은 줄에만 보인다.

**범위 외**: Control Plane 응답(phase 02).

## 컨텍스트

**요구사항**: REQ6, REQ7, REQ9

**근거 문서**: `docs/features/connector/spec.md`(DECISION6, DECISION7, DECISION11, DECISION12, DECISION14, DECISION15, DECISION16), `docs/features/connector/design.md` 의 「웹(PR3)」

- 문구 함수는 `web/src/lib/connector-onboarding.ts` 의 `boundReadiness`, `web/src/lib/connection.ts` 의 `connectionStatusLabel`, `web/src/lib/agent-connection.ts` 의 `agentConnectionLabel` 이다.
- 다시 읽기는 `web/src/lib/binding-poll.ts` 의 `isAwaitingApply`, `BINDING_POLL_LIMIT`(지금 10초씩 60번)이다.
- 응답 파서는 `web/src/lib/connection-route.ts` 의 `safeBindings`, `safeAdmin`, `safeAgentConnection` 이다.
- 관리자 패널은 `web/src/components/connector/connector-admin-panel.tsx`, 연결 화면의 붙인 에이전트는 `connector-bound-agents.tsx` 다.

## 작업 항목

### 1. 문구 함수와 타입

- `boundReadiness`: `applyFailed` 면 「반영하지 못했어요. 관리자에게 알렸어요.」, `restartRequired` 면 「자동으로 반영하고 있어요. 대화가 없는 틈에 반영돼서 조금 걸릴 수 있어요.」, 그 밖의 `PENDING` 은 「몇 분 안에 자동으로 반영돼요.」.
- `connectionStatusLabel` 의 재시작 대기 글은 「자동 반영 중」, 실패는 「반영 실패」.
- 타입 `BoundAgent`, `AdminConnection`, `AgentConnectionView` 에 `applyFailed`(관리자 줄은 `waitingSince` 도). 옛 응답에 칸이 없으면 거짓과 null 로 읽는다.

### 2. 다시 읽기

`isAwaitingApply` 가 `applyFailed` 를 뺀다. `BINDING_POLL_LIMIT` 를 100(약 16분)으로 늘린다.

### 3. 관리자 패널

상태 글을 「자동 반영 중」 과 「반영 실패」 로 나눈다. 단추 이름은 「다시 확인」 이고 `applyFailed` 이거나 `waitingSince` 에서 24시간이 지난 줄에만 보인다. 설명에 「자동 반영이 실패했을 때만 누르세요」 를 둔다.

### 4. 테스트

- 단위: `web/test/unit/connector-onboarding.test.ts`, `web/test/unit/binding-poll.test.ts` 의 문구와 다시 읽기 경우.
- 브라우저: `web/test/browser/connector-connection.spec.ts` 가 관리자 패널의 단추 조건과 이름, 진행 중인 줄에 단추가 없음을, `web/test/browser/connector-onboarding.spec.ts` 가 붙인 직후 문구를, `web/test/browser/agent-connections.spec.ts` 가 실패 문구를 확인한다.

## 검증

```bash
cd web && pnpm typecheck && pnpm lint
cd web && node --test test/unit/connector-onboarding.test.ts test/unit/binding-poll.test.ts
cd web && pnpm test:browser test/browser/connector-connection.spec.ts test/browser/connector-onboarding.spec.ts test/browser/agent-connections.spec.ts
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `web/src/lib/connector-onboarding.ts` | 수정 (문구) |
| `web/src/lib/connection.ts` | 수정 (타입, 상태 글) |
| `web/src/lib/agent-connection.ts` | 수정 (타입) |
| `web/src/lib/binding-poll.ts` | 수정 (실패 제외, 상한) |
| `web/src/lib/connection-route.ts` | 수정 (칸 파싱) |
| `web/src/components/connector/connector-admin-panel.tsx` | 수정 (상태 글, 단추) |
| `web/src/components/connector/connector-bound-agents.tsx` | 수정 (상태 글) |
| `web/src/components/agent/agent-connections-section.tsx` | 수정 (기본값) |
| `web/test/unit/connector-onboarding.test.ts` | 수정 |
| `web/test/unit/binding-poll.test.ts` | 수정 |
| `web/test/browser/connector-connection.spec.ts` | 수정 |
| `web/test/browser/connector-onboarding.spec.ts` | 수정 |
| `web/test/browser/agent-connections.spec.ts` | 수정 |
| `docs/features/connector.md` | 수정 (관리자 다시 확인과 알림 조건) |
