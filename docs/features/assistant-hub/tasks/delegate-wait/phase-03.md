# Phase 03. 작업 과정에 맡은 에이전트의 도구 줄과 「맡겨 둠」 을 그린다

**Execution profile**: standard

## 목표

도는 답의 작업 과정 블록이 `delegation_activity` 를 받아 맡은 에이전트의 도우미 줄 아래에 도구 줄을 그리고, 답이 끝날 때 아직 도는 맡긴 실행은 실패가 아닌 「맡겨 둠」 으로 남긴다.

**범위 외**: Control Plane(phase 01, 02. 트리의 `delegated` 칸은 phase 02 가 더한다), 브라우저 테스트와 e2e(phase 04).

## 컨텍스트

**요구사항**: REQ3

**근거 문서**: `docs/features/assistant-hub/spec.md`(REQ2, REQ3, DECISION2, DECISION21), `docs/features/assistant-hub/design.md` 의 「PR1 맡긴 실행의 사건」 의 웹과 「맡겨 둔 도우미」, `docs/features/execution.md` 의 「작업 과정 블록」

- 상태 모델은 `web/src/components/chat/activity/activity-state.ts` 다. `applyChatEvent` 가 흘러온 사건을 항목으로 바꾸고, `fromTree` 가 끝난 답의 트리에서 같은 항목을 만든다. `fromTree` 는 이미 자식 노드(`node.children`)를 도우미 줄(`kind: "subagent"`, `pairKey: executionId`)과 그 아래 도구 줄로 그린다.
- 답이 끝날 때 도는 항목을 바꾸는 곳은 `failActivity`(도구는 `unfinished`, 도우미는 `result-missing`)이고, `activityOutcome` 은 이 둘이 있으면 `failed` 라 접힌 줄이 「끝까지 하지 못했어요」 가 된다.
- 대화 SSE 처리는 `web/src/components/chat/use-conversation-effects.ts` 다. `delegation`, `steer` 사건은 보류하지 않고 바로 상태에 넣는다(`applySteerEvent` 가 본보기, `web/src/components/chat/steer.ts`).
- 사건 타입은 `web/src/lib/chat-event.ts` 가 갖는다.
- 상태마다의 문장과 아이콘은 `web/src/components/chat/activity/activity-timeline.tsx`, `activity-icon.tsx`, 문장 표는 `activity-state.ts` 다.

## 의도 메모

- 맡긴 실행의 도우미 줄은 자식 실행 번호를 `pairKey` 로 둔다. `fromTree` 가 같은 키를 쓰므로 새로 고쳐도 같은 줄이 된다.
- 위임 키가 있는 도는 자식만 「맡겨 둠」 이다. Hermes 내장 하위 에이전트(`subagent` 사건)의 「결과를 받지 못함」 은 그대로 둔다.

## 작업 항목

### 1. `web/src/lib/chat-event.ts`

`type` 에 `"delegation_activity"` 를 더한다. 칸은 `executionId`, `delegation`(맡은 에이전트 이름표), `toolName`, `detail`, `phase`, `durationMs`, `failed`, `connector` 다. Javadoc 에 뜻을 적는다.

### 2. `web/src/components/chat/activity/activity-state.ts`

- `ActivityItemState` 에 `"handed-off"` 를 더한다.
- 새 함수 `applyDelegationActivity(state, event)`: `pairKey === String(executionId)` 인 도우미 줄이 없으면 맡은 에이전트 이름(`subagentLabel(event.delegation 의 이름)`)으로 `running` 도우미 줄을 만들고, 그 뒤에 `applyChatEvent` 와 같은 규칙으로 도구 줄을 넣는다. 도구 줄은 그 도우미 줄의 마지막 하위 줄 뒤에 둔다.
- `failActivity`: 맡긴 실행의 도우미 줄(아래 표시로 구분한다)과 그 아래 도는 도구 줄은 `handed-off` 로 바꾸고 실패로 세지 않는다. 표시는 항목에 `delegated: boolean` 칸을 더해 둔다.
- `fromTree`: 루트가 아닌 노드 가운데 `delegated` 가 참이고(phase 02 가 트리 API 에 더한 칸) 상태가 `RUNNING` 인 노드의 도우미 줄을 `handed-off` 로 둔다. `web/src/components/execution/execution-tree.tsx` 의 `ExecutionTreeNode` 타입에 `delegated: boolean` 을 더한다.
- `activityOutcome` 은 `handed-off` 를 실패로 세지 않는다.

### 3. `web/src/components/chat/use-conversation-effects.ts` 와 `web/src/components/chat/conversation-session.tsx`

- 대화 SSE 처리(`use-conversation-effects.ts` 의 `delegation`, `steer` 분기 자리)에서 `delegation_activity` 를 보류하지 않고 `setActivity((previous) => previous && applyDelegationActivity(previous, event))` 로 도는 답의 작업 과정에 넣는다. 도는 답이 없으면(`previous` 가 null) 버린다.
- effects 의 Context 타입(Pick 목록)에 `setActivity` 를 더한다. `conversation-session.tsx` 가 상태 전체를 펼쳐 넘기므로 호출부는 바뀌지 않는다.

### 4. `web/src/components/chat/activity/activity-timeline.tsx`, `activity-icon.tsx`

`handed-off` 의 아이콘과 문장 「끝나면 이어서 알려 드려요」 를 둔다. 맡긴 도구 줄은 도우미 줄 아래 들여 쓴다.

### 5. 문서

`docs/features/execution.md` 「작업 과정 블록」 의 하위 에이전트 상태 표에 「맡겨 둠: 답이 끝날 때 맡긴 실행이 아직 돈다. 결과는 다음 답으로 온다」 줄을 더하고, 맡긴 실행의 도구 줄이 도우미 줄 아래 보인다고 적는다.

### 6. 테스트

- `web/test/unit/activity-state.test.ts`: `delegation_activity` 둘이 같은 도우미 줄 아래 도구 줄 둘로 들어간다. 답이 끝나면 맡긴 도우미는 `handed-off` 이고 `activityOutcome` 이 `done` 이다. Hermes 내장 도우미는 지금처럼 `result-missing` 이다. `fromTree` 가 위임 키가 있는 도는 자식을 `handed-off` 로, 끝난 자식을 `done` 과 그 도구 줄로 그린다.

## 검증

```bash
cd web && pnpm lint
cd web && pnpm format:check
cd web && pnpm typecheck
cd web && node --test test/unit/activity-state.test.ts
node scripts/checks/check-file-length.mjs
node --test test/repo/doc-links.test.ts test/repo/doc-files.test.ts
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `web/src/lib/chat-event.ts` | 수정 |
| `web/src/components/chat/activity/activity-state.ts` | 수정 |
| `web/src/components/chat/activity/activity-delegation.ts` | 신규 (`activity-state.ts` 가 파일 길이 상한 400줄에 가까워 따로 둔다) |
| `web/eslint.config.mjs` | 수정 (Node 테스트가 읽는 파일 목록) |
| `web/src/components/chat/use-conversation-effects.ts` | 수정 |
| `web/src/components/execution/execution-tree.tsx` | 수정 |
| `web/src/components/chat/activity/activity-timeline.tsx` | 수정 |
| `web/src/components/chat/activity/activity-icon.tsx` | 수정 |
| `web/test/unit/activity-state.test.ts` | 수정 |
| `docs/features/execution.md` | 수정 |
