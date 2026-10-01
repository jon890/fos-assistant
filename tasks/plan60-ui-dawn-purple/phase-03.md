# Phase 03. 대화의 작업 과정을 사람 말 한 줄로 접고 자리를 고정한다

**Execution profile**: deep

## 목표

대화 안 작업 과정 블록에서 도구 수, 하위 에이전트 수, 모델 이름, 토큰 수를 빼고 사람 말 한 줄로 접는다.
기다림 점, 진행 중 블록, 끝난 블록, 답 본문이 같은 자리에 들어오게 해 높이 튐을 없앤다.
대화가 실행 기록보다 먼저 읽히게 하려는 것이다.

**범위 외**: 펼침과 등장의 움직임은 phase 05. 사용량 화면과 실행 상세는 phase 04. 모델 단계 칩과 대기 칩은 다른 작업이 만든다.

## 컨텍스트

- `web/src/components/chat/activity/activity-block.tsx` 가 블록이다. `mode="live"` 는 흘러온 사건의 `ActivityState` 를, `mode="saved"` 는 `ActivitySummary`(`{ toolCount, subagentCount, durationMs }`)를 받고 펼칠 때 실행 나무를 읽는다.
- `web/src/components/chat/activity/activity-timeline.tsx` 가 펼친 줄을 그린다. 하위 에이전트 줄 아래에 모델과 토큰 줄이 있다.
- `web/src/components/chat/activity/activity-state.ts` 와 `web/src/lib/tool-label.ts`, `web/src/lib/format.ts` 는 `node --test` 가 직접 읽는다. 상대 경로 import 를 지킨다(`web/AGENTS.md` 의 「상대 경로 import 예외」).
- `web/src/components/chat/message-list.tsx` 가 진행 중 블록을 `<li>` 로 따로 그린다. 답이 아직 없으면 목록 끝에, 답이 흘러나오면 그 답 `MessageBubble` 바로 위 줄에 그린다. 저장된 답은 `web/src/components/chat/message-bubble.tsx` 가 「비서」 이름 아래에 블록을 그린다. 그래서 끝나는 순간 블록이 이름 위에서 아래로 옮겨 간다.
- `web/src/components/chat/waiting-indicator.tsx` 는 얼굴과 「비서」 글자와 점 셋을 한 줄로 그린다. 답이 오면 이 `<li>` 가 사라지고 `MessageBubble` 이 생긴다.
- 작업 과정 패널(`activity-panel.tsx`)은 같은 `ActivityTimeline` 을 쓴다.

**근거 문서**: `docs/flow.md` 의 「기다리는 동안 보이는 것」, 「작업 과정」, 「도구를 보이는 말」, 「끝난 답에서 다시 볼 때」 절. `web/AGENTS.md` 의 「화면 문구」 절. `docs/adr/ADR-038-도구의-명령-원문은-관리자에게만-보내고-사용자에게는-사람-말로-보인다.md`

## 의도 메모

- 접힌 한 줄을 모델에게 만들게 하지 않는다. 저장된 답은 수만 알고 있어, 그 수로 고르는 고정 문장을 쓴다. 백엔드를 바꾸지 않는다.
- 하위 에이전트의 모델과 토큰은 역할과 관계없이 대화에 그리지 않는다. 그 값은 사용량 화면이 관리자에게 보인다.
- `ActivityItem` 의 `model`, `inputTokens`, `outputTokens` 칸은 지우지 않는다. 다른 작업이 실행 기록 표시에 쓴다.
- `data-testid` 는 모두 지킨다: `activity-block`, `activity-toggle`, `activity-scroll`, `activity-item`, `activity-open-panel`, `activity-load-error`, `flow-slow-notice`.

## 작업 항목

### 1. `web/src/lib/tool-label.ts` 의 문장

`LABELS` 와 `UNKNOWN` 을 `docs/flow.md` 「도구를 보이는 말」 표의 새 문장으로 바꾼다. 함수 시그니처는 그대로다.

### 2. `web/src/lib/format.ts`

- `subagentLabel` 의 마지막 대체 문장 `"하위 에이전트"` 를 `"도우미"` 로 바꾼다.
- `formatSeconds(milliseconds: number): string | null` 을 더한다. 1,000 미만이면 `null`, 그 밖에는 `formatElapsed` 와 같은 「n초」, 「n분 n초」 다. 대화의 줄은 이 함수를 쓴다. `formatDuration` 은 사용량 화면이 계속 쓴다.

### 3. `web/src/components/chat/activity/activity-state.ts` 의 접힌 한 줄

```ts
export function activitySummaryLabel(counts: { toolCount: number; subagentCount: number },
  outcome: "done" | "stopped" | "failed"): string
```

| 조건 | 문장 |
| --- | --- |
| `outcome === "stopped"` | 하다가 멈췄어요 |
| `outcome === "failed"` | 끝까지 하지 못했어요 |
| 도구와 하위 에이전트가 모두 1 이상 | 찾아보고 도우미와 함께 정리했어요 |
| 하위 에이전트만 1 이상 | 도우미와 함께 정리했어요 |
| 도구만 1 이상 | 필요한 것을 확인하고 답했어요 |
| 둘 다 0 | 차례로 정리했어요 |

진행 중에 끝난 블록(`mode="live"`, `endedAt !== null`)의 `outcome` 은 항목에 `stopped` 가 있으면 `"stopped"`, `unfinished` 나 `result-missing` 이 있으면 `"failed"`, 그 밖에는 `"done"` 이다.
저장된 블록은 `cancelled` 면 `"stopped"`, 그 밖에는 `"done"` 이다.

### 4. `web/src/components/chat/activity/activity-block.tsx`

- 접힌 줄의 글자를 바꾼다.
  - 도는 중: `<span data-testid="activity-signal">` 로 `size-2 rounded-full bg-signal ring-2 ring-pill` 점, 지금 도는 줄의 말(`activityLabel(latest)`, 없으면 「준비하고 있어요」), `formatElapsed` 의 흐른 시간(`text-muted-foreground tabular-nums`).
  - 끝남: 상태 아이콘(`done` 은 `CircleCheck` 에 `text-success`, `stopped` 는 `Square` 에 `text-muted-foreground`, `failed` 는 `CircleAlert` 에 `text-destructive`)과 `activitySummaryLabel` 의 문장.
  - 화살표는 오른쪽 끝에 `ChevronRight` 하나를 두고 펼치면 `rotate-90` 을 준다. `ChevronDown` 과 번갈아 그리지 않는다.
  - 단추 안 맨 앞에 `<span className="sr-only">작업 과정: </span>` 을 둔다.
- 모양: `rounded-lg border border-border bg-card text-foreground-soft`. 단추는 `min-h-11`(44px) 이고 글자는 `text-[0.8125rem] font-medium` 이다.
- 펼친 목록 아래 줄: 왼쪽에 걸린 시간(`걸린 시간 ${formatElapsed(...)}`. live 는 `endedAt - startedAt`, saved 는 `summary.durationMs`, 값이 없거나 도는 중이면 그리지 않는다), 오른쪽에 「자세히 보기」 단추. `data-testid="activity-duration"` 을 붙인다.
- `activity-load-error` 의 문장과 「다시 읽기」 는 그대로 둔다.
- `fetch` 는 지금처럼 둔다. 이 파일은 이미 eslint 기준에 있다.

### 5. `web/src/components/chat/activity/activity-timeline.tsx`

- 하위 에이전트 줄의 앞 글자 `하위 에이전트` 를 `도우미` 로 바꾼다.
- 모델과 토큰을 그리는 `<p>` 를 지운다. `result-missing` 일 때의 「결과를 받지 못함」 줄은 위쪽 `<p>` 가 이미 그리므로 남는다.
- 걸린 시간은 `formatSeconds` 를 쓰고 `null` 이면 그리지 않는다.
- 아이콘 색: `done` 은 `text-success`, `failed` 는 `text-destructive`, 그 밖에는 `text-muted-foreground`. `failed` 줄은 글자 뒤에 `<span className="text-destructive">실패</span>` 를 보이게 그리고 `sr-only` 의 「실패」 는 뺀다.
- 글자 크기는 `text-sm`, 줄 글자색은 `text-foreground-soft` 다.

### 6. 같은 자리에 들어오게 한다

`web/src/components/chat/message-bubble.tsx` 에서 비서 줄의 틀을 꺼내 함께 쓴다.

```tsx
/** 비서 얼굴과 이름을 먼저 그리고, 그 아래 칸에 기다림 점이나 작업 과정이나 답을 받는다. */
export function AssistantRow({ header, children, ...props }: React.ComponentProps<"li"> & { header?: React.ReactNode })
```

- `AssistantRow` 는 지금 비서 `<li>` 의 `grid grid-cols-[2rem_minmax(0,1fr)] gap-2`, 얼굴 `<span>`, 「비서」 이름 줄(`min-h-8`)을 그린다. `header` 는 이름 옆(보낸 시각)이다.
- `MessageBubble` 에 `liveActivity?: React.ReactNode` 를 더한다. 주어지면 저장된 블록 자리(이름 아래, 본문 위 `<div className="mb-2">`)에 그것을 그린다. `turn.activity` 의 저장된 블록보다 먼저 본다.
- `web/src/components/chat/message-list.tsx`:
  - 답이 흘러나오는 중(`pendingAssistant`)의 진행 중 블록을 별도 `<li>` 로 그리지 않고 `MessageBubble` 의 `liveActivity` 로 넘긴다.
  - 답이 아직 없을 때(`!streamedAnswer`)는 `AssistantRow` 하나를 그리고 그 안에 진행 중 블록이나 기다림 점을 둔다. `<li>` 의 `data-testid="pending-assistant"` 를 붙인다.
- `web/src/components/chat/waiting-indicator.tsx`: 얼굴과 「비서」 글자를 빼고 점 셋과 줄인 움직임의 대체 문장만 남긴 `<div aria-label="비서의 답을 기다리는 중" role="status">` 로 바꾼다. 높이는 `min-h-7`(본문 한 줄의 `leading-7`)이다. 점은 `●` 글자 대신 `size-1.5 rounded-full bg-foreground-soft` 인 `<span>` 셋이다.
- `web/src/components/ui/page-skeleton.tsx` 가 대화 줄의 높이를 박아 두었으면 바뀐 높이에 맞춘다.

### 7. 실행 나무의 같은 말

`web/src/components/execution/execution-event-row.tsx` 의 `하위 에이전트: ` 를 `도우미: ` 로 바꾼다. `toolLabel` 의 새 문장이 그대로 쓰인다.
`web/src/components/usage/execution-card.tsx` 의 `aria-label` 「하위 실행 있음」 은 그대로 둔다.

### 8. 입력창 안내 문구

`web/src/components/chat/composer.tsx` 의 `placeholder` 를 두 경우 모두 `"무엇이든 물어보세요"` 로 바꾼다.
`web/src/components/chat/message-list.tsx` 의 빈 대화 문장 「무엇이든 물어봐 주세요.」 도 「무엇이든 물어보세요.」 로 맞춘다.
`@` 안내는 입력창 아래 도움말이 이미 있으면 그대로 두고, 없으면 더하지 않는다.

### 9. 검사

- `test/unit/tool-label.test.ts`, `test/unit/subagent-label.test.ts`, `test/unit/activity-state.test.ts`: 새 문장에 맞춘다. `activity-state.test.ts` 에 `activitySummaryLabel` 의 여섯 조건을 더한다.
- 새 단위 테스트를 `test/unit/activity-state.test.ts` 에 더한다: `formatSeconds(999)` 는 `null`, `formatSeconds(2_100)` 은 「2초」.
- 브라우저 검사에서 옛 문장을 찾는 곳을 고친다. 아래로 찾는다.

  ```bash
  # cwd: 저장소 root
  grep -rnE '하위 에이전트|작업 과정 ·|도구 [0-9]|입력 [0-9]|출력 [0-9]|하는 중|읽기"|@로 에이전트|물어봐 주세요|도와드릴까요' test/browser test/unit
  ```

  `test/e2e` 의 「하위 에이전트」 는 대역의 목표 글과 주석이다. 화면 문구가 아니므로 고치지 않는다.
- `test/browser/activity-panel.spec.ts` 나 `test/browser/flow-progress.spec.ts` 에 더한다.
  - 끝난 답의 접힌 블록에 「도구 」, 「하위 에이전트」, 「입력 」, 「출력 」, `example-model` 이 없다. 펼친 뒤에도 없다.
  - 펼친 블록에 `activity-duration` 이 보인다.
  - **자리 고정**: 도구를 쓰는 답을 보내고, 진행 중 블록의 `getBoundingClientRect().left` 와 끝난 뒤 저장된 블록의 `left` 가 같다. 진행 중 블록이 「비서」 이름(`AssistantRow` 의 이름 줄)보다 아래에 있다(`top` 비교).
  - `pending-assistant` 줄의 얼굴 `left` 와 답이 온 뒤 `assistant-message` 의 얼굴 `left` 가 같다.
- `test/browser/start-screen.spec.ts` 의 `@로 에이전트` placeholder 단언을 새 문구로 고친다.

## 검증

```bash
# cwd: 저장소 root
node --test test/unit/tool-label.test.ts test/unit/subagent-label.test.ts test/unit/activity-state.test.ts
! grep -rnE '하위 에이전트' web/src/components web/src/lib
cd web && pnpm typecheck && pnpm lint
cd web && pnpm test:browser test/browser/activity-panel.spec.ts test/browser/flow-progress.spec.ts test/browser/start-screen.spec.ts test/browser/execution-tree.spec.ts test/browser/chat.spec.ts test/browser/chat-delegation-wake.spec.ts test/browser/observe-running.spec.ts test/browser/activity-scroll.spec.ts test/browser/stop.spec.ts test/browser/loading.spec.ts
cd web && pnpm test:browser
```

기대값: 모두 종료 코드 0. 마지막 줄은 전체 브라우저 검사다. 문구를 찾는 검사가 여러 파일에 흩어져 있어 전체를 돌린다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `web/src/lib/tool-label.ts` | 수정 |
| `web/src/lib/format.ts` | 수정 |
| `web/src/components/chat/activity/activity-state.ts` | 수정 |
| `web/src/components/chat/activity/activity-block.tsx` | 수정 |
| `web/src/components/chat/activity/activity-timeline.tsx` | 수정 |
| `web/src/components/chat/message-bubble.tsx` | 수정 |
| `web/src/components/chat/message-list.tsx` | 수정 |
| `web/src/components/chat/waiting-indicator.tsx` | 수정 |
| `web/src/components/chat/composer.tsx` | 수정 |
| `web/src/components/ui/page-skeleton.tsx` | 수정 |
| `web/src/components/execution/execution-event-row.tsx` | 수정 |
| `test/unit/tool-label.test.ts` | 수정 |
| `test/unit/subagent-label.test.ts` | 수정 |
| `test/unit/activity-state.test.ts` | 수정 |
| `test/browser/activity-panel.spec.ts` | 수정 |
| `test/browser/flow-progress.spec.ts` | 수정 |
| `test/browser/start-screen.spec.ts` | 수정 |
| `test/browser/execution-tree.spec.ts` | 수정 |
| `test/browser/chat.spec.ts` | 수정 |
| `test/browser/chat-delegation-wake.spec.ts` | 수정 |
| `test/browser/observe-running.spec.ts` | 수정 |
| `test/browser/activity-scroll.spec.ts` | 수정 |
| `test/browser/stop.spec.ts` | 수정 |
| `test/browser/loading.spec.ts` | 수정 |

위 표의 브라우저 검사 가운데 옛 문구가 없어 고칠 것이 없는 파일은 건드리지 않는다. 표에 없는 검사 파일에서 옛 문구를 찾으면 고치고 phase 결과에 적는다.
