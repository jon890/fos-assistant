# Phase 03. 대화를 떠나지 않고 실행 나무를 보는 작업 과정 패널

**Execution profile**: standard

## 목표

작업 과정 블록의 「나무로 보기」 를 누르면 대화 오른쪽에 패널이 열리고,
`/executions/{id}` 화면과 같은 실행 나무를 대화를 떠나지 않고 보게 한다.
도는 중인 답은 흘러온 사건의 줄을 그대로 보인다.

**범위 외**:
블록과 줄의 모양은 phase-02 가 끝냈다. 여기서는 패널과 그것을 여는 단추만 더한다.
`/executions/{id}` 화면 자체는 고치지 않는다.
중지 단추와 `Esc` 로 중지하는 것은 이 계획 밖이다. 다만 `Esc` 를 누가 먼저 받는지는 여기서 정해 둔다.

## 컨텍스트

**근거 문서**: `docs/flow.md` 의 「작업 과정」 절 아래 「작업 과정 패널」, `docs/flow.md` 의 「화면 틀」 과 「실행 하나를 다시 볼 때」, `docs/code-architecture.md` 의 「우리 화면의 정체성」 과 「아직 만들지 않은 것」

### 시작하기 전에 확인한다

`web/src/components/chat/activity/activity-block.tsx` 와 `activity-timeline.tsx` 가 없으면
`PHASE_BLOCKED: 작업 과정 블록이 아직 없다` 를 출력하고 끝낸다.

화면 틀 개선이 대화 화면을 사이드바 옆에 두었다. 패널은 대화 화면의 오른쪽에 붙는다.
**`web/src/components/chat-panel.tsx` 와 `web/src/components/shell/` 을 먼저 읽고 패널을 둘 자리를 정한다.**

### 지금 코드가 하는 것

- `components/execution/execution-tree.tsx` 의 `ExecutionTree({ tree }: { tree: ExecutionTreeResponse })` 가 나무를 그린다.
  `hasRenderableEvent(node)` 가 그릴 것이 있는지 본다.
- `components/execution/execution-detail.tsx` 의 `ExecutionDetail({ executionId })` 는 머리 요약까지 함께 그리고,
  404 면 `/usage` 로 보낸다. **패널에서는 이것을 쓰지 않는다.** 패널 안에서 다른 화면으로 옮겨 가면 안 된다.
  읽는 방법(`fetch("/api/usage/executions/{id}/tree", { cache: "no-store" })`)만 같게 한다.
- `chat/message-bubble.tsx` 가 `turn.hasChildren && turn.executionId` 일 때
  「이 답이 어떻게 만들어졌는지 보기」 링크를 `data-testid="flow-tree-link"` 로 그린다.
- 좁은 화면의 서랍은 화면 틀 개선이 `components/shell/` 로 옮겼다. `chat/conversation-drawer.tsx` 는 더 없다.
  서랍과 이름 입력칸과 확인 창이 `Esc` 를 어떻게 받는지 `components/shell/` 에서 읽는다.
  그것들이 `Esc` 를 처리하면 전파를 막아야 한다(`docs/flow.md` 「화면 틀」 의 `Esc` 규칙 2).
  막지 않고 있으면 이 phase 에서 `event.preventDefault()` 와 `event.stopPropagation()` 을 더한다.

## 의도 메모

- 패널이 한 번에 보이는 답은 하나다. 다른 답의 블록에서 누르면 그 답으로 바뀐다.
- 도는 중인 답은 나무를 읽지 않는다. 끝나기 전의 나무는 사건이 비어 있거나 자식이 덜 달려 있다.
  `done` 이 오면 그 실행 번호로 한 번 읽는다.
- 「이 답이 어떻게 만들어졌는지 보기」 링크를 말풍선에서 빼고 패널 안으로 옮긴다.
  블록이 이미 작업 과정의 입구이고, 같은 곳으로 가는 길이 둘이면 무엇을 눌러야 할지 모른다.
  `flow-tree-link` 선택자는 패널 안의 「전체 화면으로 보기」 링크가 이어받는다.
- `Esc` 는 `docs/flow.md` 「화면 틀」 의 규칙대로 **`chat-panel.tsx` 의 처리기 하나만** 해석한다.
  패널 부품이 `window` 에 따로 리스너를 두지 않는다. 둘로 나뉘면 어느 쪽이 먼저 받는지가 구현에 따라 달라진다.
  이 phase 가 그 처리기를 만든다. 이미 있으면 거기에 더한다. 차례는 1 조합 중, 2 이미 처리된 사건(`event.defaultPrevented`), 3 패널 닫기다.
  4 중지는 뒤에 중지 경로를 만들 때 같은 처리기의 끝에 더한다.
- 기각: 패널을 늘 띄운다. 390px 에서 대화가 사라진다.

## 작업 항목

### 1. 패널 부품

`web/src/components/chat/activity/activity-panel.tsx` 에 `ActivityPanel` 을 만든다.

```ts
export type ActivityPanelTarget =
  | { mode: "live"; state: ActivityState }
  | { mode: "saved"; executionId: number };

type Props = { target: ActivityPanelTarget; onClose(): void };
```

| 모드 | 몸 |
| --- | --- |
| `live` | `ActivityTimeline` 에 `state.items` |
| `saved` | 나무를 한 번 읽어 `ExecutionTree` 로 그린다. 읽는 동안 뼈대, 실패면 「실행 나무를 읽지 못했다」와 다시 읽기 |

- 머리에 「작업 과정」 제목과 닫기 단추(`aria-label="작업 과정 닫기"`)를 둔다.
- `saved` 일 때 머리에 「전체 화면으로 보기」 링크를 두고 `/executions/{executionId}` 로 간다. `data-testid="flow-tree-link"` 를 붙인다.
- 패널 루트에 `data-testid="activity-panel"` 과 `role="complementary"`, `aria-label="작업 과정"` 을 둔다.

폭마다 자리가 다르다. `docs/flow.md` 「화면 틀」 의 표와 같다.

| 너비 | 자리 |
| --- | --- |
| `lg` 이상 | 대화 오른쪽에 붙는다. 폭 `w-96`. 대화 열이 그만큼 좁아진다 |
| `md` 이상 `lg` 미만 | 대화 위 오른쪽에 겹친다. 폭 `w-96`, 그림자 |
| `md` 미만 | 화면 전체를 덮는다 |

테마 토큰 클래스만 쓴다. `style={{` 는 `web/AGENTS.md` 대로 이어지는 수라서 클래스로 만들 수 없을 때만, 주석을 달고 쓴다.

### 2. 블록에 여는 단추를 더한다

`activity-block.tsx` 의 `Props` 에 `onOpenPanel?(): void` 를 더한다.
있으면 펼친 몸 아래 오른쪽에 「나무로 보기 →」 단추를 그린다. `data-testid="activity-open-panel"`.
`live` 모드에서도 그린다. 그때 패널은 `live` 로 열린다.

### 3. 대화 화면이 패널을 연다

`chat-panel.tsx` 에 `panelTarget: ActivityPanelTarget | null` 상태를 둔다.

- 흘러오는 답의 블록에서 열면 `{ mode: "live", state: activity }`. `activity` 가 바뀌면 패널도 따라 바뀐다.
- `done` 이나 `stopped` 를 받았을 때 패널이 `live` 로 열려 있으면 `{ mode: "saved", executionId }` 로 바꾼다.
  `stopped` 는 phase-02 가 `lib/chat-event.ts` 의 타입에 이미 넣었다. 보내는 쪽은 뒤에 중지 경로를 만들 때 생긴다.
- 저장된 답의 블록에서 열면 `{ mode: "saved", executionId: turn.executionId }`.
- 다른 대화로 가면 닫는다. `/` 로 가도 닫는다.
- `chat-panel.tsx` 의 `Esc` 처리기 하나에서 받는다. 위 「의도 메모」 의 차례를 따른다.
  한글 조합 중(`event.isComposing`)이거나 `event.defaultPrevented` 이면 하지 않고, 패널이 열려 있으면 닫는다.

`message-list.tsx` 가 블록에 `onOpenPanel` 을 넘길 수 있게 prop 을 하나 더한다.

### 4. 말풍선의 링크를 뺀다

`chat/message-bubble.tsx` 에서 「이 답이 어떻게 만들어졌는지 보기」 링크를 지운다.
`Turn.hasChildren` 칸은 남긴다. 서버가 아직 보낸다.

### 5. 문서의 남은 일 목록을 줄인다

`docs/code-architecture.md` 의 「아직 만들지 않은 것」 에서
`` `tool` 과 `subagent` 사건의 나눔, 작업 과정 블록과 패널, 메시지의 `activity` `` 줄을 뺀다.
다른 줄은 건드리지 않는다.

### 6. 이 phase 를 검증하는 브라우저 테스트

`test/browser/activity-panel.spec.ts` 를 새로 만들고 `test/browser/flow-progress.spec.ts` 를 고친다.

- 끝난 답의 블록을 펼쳐 `activity-open-panel` 을 누르면 `activity-panel` 이 보이고 그 안에 실행 나무가 그려진다.
- 흐름으로 끝난 답의 패널에 `flow-tree-link` 가 있고 누르면 `/executions/{id}` 로 간다.
  지금 `flow-progress.spec.ts` 가 답 옆에서 찾던 `flow-tree-link` 를 패널을 연 뒤에 찾게 바꾼다.
- 흐름이 아닌 답에는 말풍선에 `flow-tree-link` 가 없다. 지금 검사를 그대로 둔다.
- 도는 동안 패널을 열면 `data-kind` 가 있는 줄이 보이고, 끝나면 같은 패널이 나무로 바뀐다. `hermes.holdNextRun()` 을 쓴다.
- 닫기 단추와 `Escape` 가 모두 패널을 닫는다.
- 패널이 열린 채 사이드바의 이름 입력칸에서 `Escape` 를 누르면 이름만 되돌아가고 패널은 열려 있다.
- `desktop` 폭(1280px)에서 패널이 열려 있어도 대화 입력창이 보인다.
  `mobile` 폭(390px)에서는 패널이 화면을 덮고 닫으면 입력창이 다시 보인다.
- 두 폭 모두 패널을 연 채로 가로 스크롤이 생기지 않는다.

## 검증

```bash
# cwd: 저장소 root
cd backend && ./gradlew test
```

```bash
# cwd: 저장소 root
cd web && pnpm typecheck && pnpm test:browser
```

```bash
# cwd: 저장소 root
node test/e2e/run.ts
scripts/check-public-safe.sh
grep -rn 'style={{' web/src/
```

`AGENTS.md` 「확인」 절의 순서대로 돌린다. 앞의 명령은 모두 종료 코드 0 이어야 한다.
마지막 grep 은 `web/AGENTS.md` 대로 **왜 인라인인지 주석을 단 예외만** 내야 한다. 주석 없는 줄이 나오면 고친다.
`pnpm build` 는 `web/AGENTS.md` 의 자리표시자 환경 변수를 주고 돌린다.

**배포한 뒤.**

Hermes 와 주고받는 사건 모양을 바꿨다. 배포한 뒤 하위 에이전트를 부르는 실제 실행을 한 번 왕복시킨다.
`subagent` 사건에 `goal` 과 `model` 이 오는지, 오지 않으면 블록이 `preview` 로 그려지는지 본다.
어느 쪽이었는지를 `docs/data-schema.md` 「execution_event」 의 버전 설명에 결과로 적는다.
실행 방법은 `fos-home-infra` 가 갖는다. 이 저장소에 적지 않는다.

## Critical Files

| 파일 | 변경 |
|---|---|
| `web/src/components/chat/activity/activity-panel.tsx` | 신규 |
| `web/src/components/chat/activity/activity-block.tsx` | 수정 |
| `web/src/components/chat/message-list.tsx` | 수정 |
| `web/src/components/chat/message-bubble.tsx` | 수정 |
| `web/src/components/chat-panel.tsx` | 수정 |
| `docs/code-architecture.md` | 수정 |
| `test/browser/activity-panel.spec.ts` | 신규 |
| `test/browser/flow-progress.spec.ts` | 수정 |

끝나면 `tasks/plan020-agent-activity/index.json` 의 이 phase 를 `completed` 로, plan 의 `status` 를 `completed` 로 바꾼다.
