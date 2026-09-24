# Phase 02. 답 위에 작업 과정 블록을 그린다

**Execution profile**: standard

## 목표

답 위에 접히는 작업 과정 블록 하나를 둔다.
도는 동안에는 지금 도는 것 한 줄과 흐른 시간을, 끝나면 도구 수와 하위 에이전트 수와 걸린 시간을 보이고,
펼치면 도구와 하위 에이전트와 흐름의 단계를 도착한 순서로 보인다.
지금의 도구 한 줄(`RunStatus`)과 네 단계 고정 목록(`FlowProgress`)을 이 블록으로 바꾼다.

**범위 외**:
오른쪽 작업 과정 패널은 phase-03 이 한다. 이 phase 의 블록에는 패널을 여는 단추를 두지 않는다.
「이 답이 어떻게 만들어졌는지 보기」 링크를 옮기는 것도 phase-03 이다. 이 phase 에서는 그대로 둔다.
Control Plane 은 phase-01 이 끝냈다. `backend/` 를 고치지 않는다.

## 컨텍스트

**근거 문서**: `docs/flow.md` 의 「기다리는 동안 보이는 것」 과 「작업 과정」 과 그 아래 「끝난 답에서 다시 볼 때」, `docs/code-architecture.md` 의 「대화」 절 아래 「메시지 한 줄」 과 「화면으로 보내는 사건」, `docs/adr/ADR-017-무엇을-할지는-hermes-가-정하고-control-plane-은-경계만-갖는다.md`, `docs/adr/ADR-009-에이전트의-답은-신뢰하지-않는-글로-그린다.md`

### 시작하기 전에 확인한다

- phase-01 이 끝나 스트림에 `subagent` 사건이 오고 메시지 조회에 `activity` 가 있다.
  `backend/src/main/java/com/bifos/assistant/chat/application/ChatEvent.java` 에 `subagent(` 가 없으면
  `PHASE_BLOCKED: 사건 확장이 아직 없다` 를 출력하고 끝낸다.
- 화면 틀 개선이 머지돼 있다. 대화 화면의 부품 배치가 이 문서를 쓸 때와 다를 수 있다.
  **`web/src/components/chat-panel.tsx`, `chat/message-list.tsx`, `chat/message-bubble.tsx` 를 먼저 읽는다.**
  아래에 적은 것은 이 문서를 쓸 때의 모양이다.

### 지금 코드가 하는 것

- `chat-panel.tsx` 의 `ChatEvent` 타입과 `readEventStream` 콜백이 사건을 받는다.
  `tool` 사건은 `"{도구}: {상태}"` 문자열로 `toolEvents` 에 쌓고, `step` 사건은 `flowSteps` 표에,
  `switched` 는 「여기부터 … 로 돈다」 문자열로 `toolEvents` 에 넣는다.
  `reset` 은 흘린 조각과 `toolEvents` 를 비운다. `done` 은 `refreshMessages` 로 저장된 이력을 다시 읽는다.
- `SLOW_FLOW_MS` 가 2분이다. 첫 `step` 사건 뒤 2분이 지나면 `flowIsSlow` 가 참이 되고
  `message-list.tsx` 가 `data-testid="flow-slow-notice"` 줄을 그린다.
- `message-list.tsx` 가 흘러오는 답 위에 `RunStatus` 를, 흐름이면 `FlowProgress` 를 그린다.
- `chat/flow-progress.tsx` 는 `ORDER`(`chief`, `researcher`, `engineer`, `synthesizer`)를 박아 두고
  도착하지 않은 단계를 `pending` 으로 그린다. `LABELS` 가 한국어 이름이다.
  단계 줄에 `data-testid="flow-step-{이름}"` 과 `data-state` 가 있다.
- `chat/run-status.tsx` 는 마지막 도구 한 줄과, 기다리는 동안의 점 세 개를 그린다.
- `chat/message-bubble.tsx` 의 `Turn` 타입이 메시지 한 줄이다.
- `components/execution/execution-tree.tsx` 가 `ExecutionTreeResponse`, `ExecutionTreeNode`, `ExecutionEventView` 타입을 낸다.
  `GET /api/usage/executions/{id}/tree` 서버 라우트가 이미 있다.
- `components/execution/execution-node.tsx` 의 `mergeEvents(events)` 가 `TOOL_STARTED` 와 `TOOL_COMPLETED` 를 한 줄로 합친다.

## 의도 메모

- 단계 이름과 차례를 화면에 박지 않는다. 도착한 순서로 그린다. 아직 오지 않은 단계는 그리지 않는다.
  `LABELS` 는 한국어 이름을 붙이는 표로만 남기고, 표에 없는 이름은 받은 그대로 보인다.
- 사건을 상태로 바꾸는 일을 순수 함수 하나에 모은다. 도는 동안의 사건과 끝난 답의 나무가 같은 줄 모양으로 바뀌어야 블록이 하나로 그려진다.
- 도구는 도착한 순서로 짝짓는다. 같은 이름의 `started` 가운데 아직 끝나지 않은 가장 앞의 것에 `completed` 를 붙인다.
  하위 에이전트는 `subagentId` 가 있으면 그것으로, 없으면 도구와 같은 방식으로 짝짓는다.
- 끝난 답의 블록은 펼칠 때 처음으로 나무를 읽는다. 대화를 열 때 답마다 읽지 않는다.
- `detail` 과 `goal` 은 모델과 도구가 만든 글이다. 글자로만 그린다. HTML 로 해석하지 않는다.
- 기각: 도는 동안 블록을 펼쳐 둔다. 여러 에이전트가 오래 돌면 줄이 화면을 채워 답이 밀려난다.

## 작업 항목

### 1. 사건을 줄로 바꾸는 순수 함수

`web/src/components/chat/activity/activity-state.ts` 를 새로 만든다.

```ts
export type ActivityItemKind = "tool" | "subagent" | "step" | "switched";
export type ActivityItemState = "running" | "done" | "failed" | "stopped";

export type ActivityItem = {
  key: string;             // 줄의 React key. 도착 순번으로 만든다
  kind: ActivityItemKind;
  name: string;            // 도구 이름, 하위 에이전트 목표, 단계의 한국어 이름, 전환 문구
  detail: string | null;
  model: string | null;
  inputTokens: number | null;
  outputTokens: number | null;
  durationMs: number | null;
  state: ActivityItemState;
  pairKey: string | null;  // 짝을 맞추는 열쇠. 하위 에이전트의 subagentId 나 단계 이름
};

export type ActivityState = { items: ActivityItem[]; startedAt: number };

export function emptyActivity(startedAt: number): ActivityState;
export function applyChatEvent(state: ActivityState, event: ChatEvent): ActivityState;
export function fromTree(tree: ExecutionTreeResponse): ActivityItem[];
export function countOf(items: ActivityItem[]): { toolCount: number; subagentCount: number };
export const STEP_LABELS: Record<string, string>;
```

- `ChatEvent` 타입을 `chat-panel.tsx` 에서 `web/src/lib/chat-event.ts` 로 옮겨 export 한다.
  `docs/code-architecture.md` 「화면으로 보내는 사건」 표의 `type` 과 칸을 모두 담는다.
  `started` 와 `stopped` 도 넣는다. `stopped` 를 보내는 쪽은 아직 없어도 타입은 표와 같게 둔다.
- `applyChatEvent` 가 다루는 것은 `tool`, `subagent`, `step`, `switched`, `reset` 이다. 나머지는 상태를 그대로 돌려준다.
  - `step` 의 `stepState` 가 `started` 면 줄을 더하고, `completed` 와 `failed` 면 같은 이름의 줄을 바꾼다. 없으면 더한다.
  - `reset` 은 지금처럼 줄을 비운다.
- `fromTree` 는 뿌리부터 자식까지 노드를 차례로 훑어 `TOOL_*` 과 `SUBAGENT_*` 사건을 같은 규칙으로 줄로 만든다.
  자식 노드 하나는 `kind: "subagent"` 줄 하나다. 이름은 `agentName`, 모델은 `model`, 토큰과 걸린 시간은 그 노드의 값이다.
  `PROVIDER_SWITCHED` 는 `switched` 줄이다. 짝이 없는 `started` 는 `running` 이 아니라 `stopped` 로 둔다. 끝난 나무이기 때문이다.
- `STEP_LABELS` 는 지금 `flow-progress.tsx` 의 `LABELS` 를 옮긴 것이다.

### 2. 작업 과정 블록

`web/src/components/chat/activity/activity-block.tsx` 에 `ActivityBlock` 을 만든다.

```ts
type Props =
  | { mode: "live"; state: ActivityState; slow: boolean }
  | { mode: "saved"; summary: ActivitySummary; executionId: number };
```

`ActivitySummary` 는 `{ toolCount: number; subagentCount: number; durationMs: number | null }` 이고 `Turn` 과 같은 파일이나 `lib/chat-event.ts` 에 둔다.

| 모드 | 접힌 머리 | 펼친 몸 |
| --- | --- | --- |
| `live` | 「작업 과정」, 지금 `running` 인 마지막 줄의 이름, 흐른 시간 | `state.items` |
| `saved` | 「작업 과정 · 도구 N · 하위 에이전트 M · 걸린 시간」. 0 인 항목은 뺀다 | 처음 펼칠 때 나무를 읽어 `fromTree` 한 줄 |

- 흐른 시간은 `state.startedAt` 부터 1초마다 다시 센다. 형식은 `42초`, `1분 12초` 다.
  `lib/format.ts` 에 `formatElapsed(milliseconds: number): string` 을 더한다.
  이미 있는 `formatDuration` 은 `1.5초` 처럼 소수를 보이는 도구 한 줄용이라 그대로 둔다.
  줄의 걸린 시간은 `formatDuration`, 머리의 흐른 시간과 걸린 시간은 `formatElapsed` 를 쓴다.
- 머리는 `button` 이고 `aria-expanded` 를 갖는다. 누르면 펼치고 접는다.
- `live` 에서 `slow` 가 참이면 몸 아래가 아니라 머리 아래에 한 번만 안내를 그린다.
  문구는 지금 `message-list.tsx` 의 것을 그대로 쓰고 `data-testid="flow-slow-notice"` 를 옮긴다.
- `saved` 에서 나무를 읽지 못하면 몸에 「작업 과정을 읽지 못했다」와 다시 읽기 단추를 그린다.
- 좁은 화면에서 머리가 한 줄을 넘지 않게 이름을 자른다. 가로 스크롤이 생기면 안 된다.
- 색과 간격은 테마 토큰 클래스만 쓴다. `style={{` 는 `web/AGENTS.md` 대로 이어지는 수라서 클래스로 만들 수 없을 때만, 주석을 달고 쓴다. 브랜드 색을 본문 글자에 쓰지 않는다.

`web/src/components/chat/activity/activity-timeline.tsx` 에 줄 목록 `ActivityTimeline({ items }: { items: ActivityItem[] })` 을 둔다.
phase-03 의 패널이 같은 부품을 쓴다.

| 줄 | 보이는 것 |
| --- | --- |
| 도구 | 표시, 도구 이름, `detail`, 끝났으면 걸린 시간 |
| 하위 에이전트 | 표시, 「하위 에이전트」, 목표. 둘째 줄에 모델과 토큰 |
| 단계 | 표시, 단계 이름 |
| 전환 | 「여기부터 … 로 돈다」 |

표시는 지금 `flow-progress.tsx` 의 `MARKS` 와 `SPOKEN` 을 옮겨 쓴다. `running` 은 `⟳`, `done` 은 `✓`, `failed` 는 `!`, `stopped` 는 `■` 다. 읽어 주는 화면을 위한 `sr-only` 글을 함께 둔다.

선택자를 이렇게 둔다.

| 요소 | 선택자 |
| --- | --- |
| 블록 | `data-testid="activity-block"`, `data-mode="live|saved"` |
| 머리 단추 | `data-testid="activity-toggle"` |
| 줄 | `data-testid="activity-item"`, `data-kind`, `data-state` |
| 단계 줄 | 위에 더해 `data-step="{원래 이름}"` |
| 나무 읽기 실패 | `data-testid="activity-load-error"` |

### 3. 대화 화면이 블록을 쓴다

- `chat-panel.tsx`: `toolEvents` 와 `flowSteps` 상태를 `activity: ActivityState | null` 하나로 바꾼다.
  보낼 때 `emptyActivity(Date.now())` 로 시작하고, 사건마다 `applyChatEvent` 를 부른다.
  `done` 과 오류에서 `null` 로 되돌린다. 흐름이 느리다는 판정은 첫 `step` 사건 뒤 2분으로 지금과 같다.
- `message-list.tsx`: 흘러오는 답 위와 아직 답 조각이 없을 때 모두 `ActivityBlock mode="live"` 를 그린다.
  줄이 하나도 없으면 블록 대신 지금의 점 세 개만 그린다.
  저장된 답은 `turn.activity` 가 있고 `turn.executionId` 가 있으면 답 위에 `ActivityBlock mode="saved"` 를 그린다.
- `message-bubble.tsx`: `Turn` 에 `activity?: ActivitySummary | null` 을 더한다.
  블록을 말풍선 안에 둘지 목록에서 그릴지는 지금 배치를 읽고 정한다. 답 본문보다 위이고 비서 이름 아래다.
- `chat/flow-progress.tsx` 를 지운다.
- `chat/run-status.tsx` 는 기다리는 동안의 점 세 개만 남기고 이름을 `chat/waiting-indicator.tsx` 의 `WaitingIndicator` 로 바꾼다.
  마지막 도구 한 줄을 그리는 부분은 지운다. 블록이 그 자리다.

### 4. 이 phase 를 검증하는 브라우저 테스트

`test/browser/flow-progress.spec.ts` 를 새 블록에 맞춰 고친다. 새 대화를 여는 도우미 `sendWithTheFlowAgent` 는 화면 틀 개선이 이미 고쳤을 수 있다. 지금 모양을 쓴다.

- 흐름이 도는 동안 `activity-block` 이 `data-mode="live"` 로 보이고, 머리를 펼치면 `data-step="chief"` 줄이 `data-state="running"` 이다.
  아직 오지 않은 `synthesizer` 줄은 없다. 끝나면 답이 보이고 `data-mode="saved"` 블록이 남는다.
- 한 단계가 실패하면 그 줄이 `data-state="failed"` 이고, 오지 않은 단계의 줄은 없다.
  지금의 「나머지는 흐린 상태로 멈춘다」 검사를 이것으로 바꾼다.
- 흐름이 아닌 대화에는 `data-kind="step"` 줄이 없다. 가짜 Hermes 가 도구 사건을 보내므로 블록 자체는 있다.
- 끝난 답의 블록을 펼치면 `activity-item` 이 `data-kind="tool"` 로 둘 이상 보인다. 새로 고친 뒤에도 같다.
- 390px 에서 블록 머리 때문에 가로 스크롤이 생기지 않는다. 지금 이 파일이 쓰는 `scrollWidth - clientWidth` 검사를 그대로 쓴다.
- 나무 경로가 500 을 주면 `activity-load-error` 가 보인다. `page.route` 로 `/api/usage/executions/*/tree` 를 가로챈다.

## 검증

```bash
# cwd: 저장소 root
cd web && pnpm typecheck
```

```bash
# cwd: 저장소 root
cd web && pnpm test:browser
```

```bash
# cwd: 저장소 root
grep -rn 'style={{' web/src/
grep -rn 'flow-progress\|FlowProgress\|RunStatus' web/src/
```

앞의 둘은 종료 코드 0 이어야 한다. `style={{` grep 은 `web/AGENTS.md` 대로 주석을 단 예외만 내야 하고, `flow-progress` grep 은 아무것도 내지 않아야 한다.

## Critical Files

| 파일 | 변경 |
|---|---|
| `web/src/lib/chat-event.ts` | 신규 |
| `web/src/lib/format.ts` | 수정 |
| `web/src/components/chat/activity/activity-state.ts` | 신규 |
| `web/src/components/chat/activity/activity-block.tsx` | 신규 |
| `web/src/components/chat/activity/activity-timeline.tsx` | 신규 |
| `web/src/components/chat/waiting-indicator.tsx` | 신규 |
| `web/src/components/chat/run-status.tsx` | 삭제 |
| `web/src/components/chat/flow-progress.tsx` | 삭제 |
| `web/src/components/chat/message-list.tsx` | 수정 |
| `web/src/components/chat/message-bubble.tsx` | 수정 |
| `web/src/components/chat-panel.tsx` | 수정 |
| `test/browser/flow-progress.spec.ts` | 수정 |

끝나면 `tasks/plan020-agent-activity/index.json` 의 이 phase 를 `completed` 로 바꾸고 `current_phase` 를 3으로 올린다.
