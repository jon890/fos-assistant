# Phase 04. web 이 도구를 사람 말로 보이고 펼친 작업 과정의 높이를 제한한다

**Execution profile**: standard

## 목표

- 작업 과정 블록의 제목과 목록, 실행 나무의 도구 줄에 도구 이름 대신 사람 말 문장을 보인다
- 펼친 작업 과정 목록은 `16rem` 안에서 스크롤한다. 도는 중에는 사용자가 맨 아래를 보고 있을 때만 새 줄을 따라간다

**범위 외**: `detail` 을 누구에게 보낼지(phase 01 의 Control Plane 이 정한다. 화면은 받은 것을 그대로 보인다). 스킬 단계 표시. 작업 과정 패널의 높이(이미 제 높이 안에서 스크롤한다).

## 컨텍스트

**근거 문서**: `docs/flow.md` 의 「도구를 보이는 말」 절과 「펼친 목록의 높이」 절,
`docs/adr/ADR-038-도구의-명령-원문은-관리자에게만-보내고-사용자에게는-사람-말로-보인다.md`

지금 모양이다. 구현 전에 각 파일을 연다.

- `web/src/components/chat/activity/activity-state.ts`
  - `ActivityItem` 의 `name` 에 도구 사건이면 `event.toolName ?? "도구"` 를 넣고 `pairKey` 도 같은 값이다. 짝짓기가 이 값에 기대므로 `name` 과 `pairKey` 의 값은 바꾸지 않는다
  - 이 파일은 `../../../lib/format.ts` 처럼 확장자까지 적은 상대 경로로 부른다. 단위 테스트가 `node --test` 로 직접 불러서다
- `web/src/components/chat/activity/activity-block.tsx`
  - 도는 중 제목이 `작업 과정 · ${latest.name} · 흐른 시간` 이다. `latest` 는 `state === "running"` 인 마지막 항목이다
  - 펼치면 `<div className="min-w-0 border-t border-border px-3 py-2">` 안에 `ActivityTimeline` 과 읽기 실패 문구, 「작업 과정 자세히 보기」 단추가 함께 있다
- `web/src/components/chat/activity/activity-timeline.tsx`: 항목마다 `item.name` 을 보이고 도구면 `item.detail` 을 한 줄 더 보인다
- `web/src/components/execution/execution-event-row.tsx`: 도구 줄이 `도구: {row.toolName ?? "이름 없음"} · 걸린 시간 · detail` 이고, 끝나지 않은 줄은 `도구: … · 끝나지 않음` 이다
- 단위 테스트 `test/unit/activity-state.test.ts` 는 `item.name` 을 도구 원래 이름으로 확인한다. `name` 을 바꾸지 않으므로 그대로 통과해야 한다
- 브라우저 검사
  - `test/browser/execution-tree.spec.ts` 가 나무에서 `fake-tool`, `fake-reader` 글자를 찾는다(155–156행 부근)
  - `test/browser/activity-panel.spec.ts` 의 「도는 중 패널은 사건을 보이고 끝나면 같은 자리에서 나무로 바뀐다」 가 `hermes.holdNextRun()`, `hermes.waitForHeldRun()`, `hermes.releaseHeldRun()` 으로 도는 turn 을 붙잡는다. 대역 쪽 구현은 `test/e2e/fake-hermes.ts` 의 `holdNextRun`, `heldRunId`, `releaseHeldRun` 이다
  - 대역 Hermes 는 모든 스트림 turn 에 `fake-tool`, `fake-reader` 도구 사건 두 쌍을 보낸다

## 의도 메모

- 표는 `docs/flow.md` 「도구를 보이는 말」 의 표를 글자까지 옮긴다. 표에 없는 이름과 `null` 은 「도구를 쓰는 중」, 「도구 사용」 이다
- MCP 도구 `mcp__{서버}__{도구}` 는 마지막 `__` 뒤의 이름으로 표를 찾는다. 화면 문구일 뿐이라 Control Plane 의 공개 판정과 다르게 해도 된다
- 문장은 그리는 때 정한다. 같은 항목이 도는 중에서 끝남으로 바뀌면 문장도 바뀌어야 해서 `name` 에 문장을 굳혀 넣지 않는다
- 원래 도구 이름은 `data-tool` 속성으로 남긴다. 검사가 그것으로 줄을 찾는다. 이름은 감출 값이 아니고 감출 것은 `detail` 이다(ADR-038)
- `data-tool` 은 두 화면이 같은 규칙을 쓴다. 도구 줄에는 늘 붙이고, 이름이 없으면 값은 `도구` 다. 작업 과정 항목은 이미 `name` 에 그 값을 갖고 있고, 실행 나무 줄은 `row.toolName ?? "도구"` 로 맞춘다
- 따라가기는 도는 중인 블록만 한다. 끝난 답을 펼치면 맨 위부터 보인다
- 맨 아래에서 `16px` 안이면 맨 아래를 보고 있는 것으로 본다. 스크롤할 때마다 이 값을 다시 정한다
- 높이 상한은 목록에만 준다. 읽기 실패 문구와 「작업 과정 자세히 보기」 단추는 스크롤 상자 밖에 둔다
- 색과 간격은 지금 main 의 토큰을 쓴다. `danger` 토큰을 새로 쓰지 않는다

## 작업 항목

### 1. `web/src/lib/tool-label.ts` 신규

- `export function toolLabel(toolName: string | null, running: boolean): string`
- 표를 `Record<string, { running: string; done: string }>` 로 둔다
- `toolName` 이 `mcp__` 로 시작하면 마지막 `__` 뒤를 찾을 이름으로 쓴다
- 이 파일은 다른 모듈을 부르지 않는다

### 2. 작업 과정 블록과 목록

- `activity-state.ts` 에 `export function activityLabel(item: ActivityItem): string` 을 더한다. 도구 항목이면 `toolLabel(item.name, item.state === "running")`, 아니면 `item.name` 이다. `tool-label.ts` 를 `../../../lib/tool-label.ts` 로 부른다
- `activity-block.tsx` 의 도는 중 제목이 `latest.name` 대신 `activityLabel(latest)` 를 쓴다
- `activity-timeline.tsx` 가 `item.name` 대신 `activityLabel(item)` 를 보이고, 도구 항목의 `li` 에 `data-tool={item.name}` 을 붙인다
- `activity-block.tsx` 의 펼친 목록을 스크롤 상자로 감싼다
  - `data-testid="activity-scroll"`, `max-h-64 overflow-y-auto overscroll-contain` 을 준다
  - `useRef` 로 상자와 「맨 아래를 보고 있다」 값을 둔다. 처음 값은 참이다
  - `onScroll` 에서 `scrollHeight - scrollTop - clientHeight <= 16` 으로 그 값을 다시 정한다
  - `props.mode === "live"` 이고 `props.state.endedAt === null` 이고 그 값이 참이면, 항목 수가 바뀌거나 펼쳐질 때 `useLayoutEffect` 에서 `scrollTop = scrollHeight` 로 내린다
- `execution-event-row.tsx` 의 도구 줄을 `{toolLabel(row.toolName, false)} · 걸린 시간 · detail` 로, 끝나지 않은 줄을 `{toolLabel(row.toolName, false)} · 끝나지 않음` 으로 바꾸고 `li` 에 `data-tool={row.toolName ?? "도구"}` 를 붙인다

### 3. 대역 Hermes 의 긴 작업 과정

- `test/e2e/fake-hermes.ts` 에 `export const LONG_ACTIVITY_PROBE = "긴 작업 과정 검사"` 를 더한다
- 지금 사건 스트림은 held 여부와 상관없이 사건을 한 번에 모두 보낸다. `releaseHeldRun` 은 실행 상태만 `completed` 로 바꾼다. 스트림을 중간에 멈추는 장치가 없으므로 새로 만든다
- 이 입력의 스트림은 이 순서로 보낸다
  1. `message.delta` 하나(`긴 작업 과정`)
  2. `terminal` 도구 사건 30쌍. `preview` 는 `단계 {n}` 이다. `terminal` 글자를 담지 않는다
  3. `terminal` 의 `tool.started` 하나(`preview` 는 `단계 31`). 이 항목이 도는 중이라 블록 제목에 도는 줄의 말이 보인다
  4. 스트림 풀림 신호를 기다린다
  5. 3의 짝 `tool.completed` 와 `terminal` 10쌍(`단계 32` 부터), 그리고 `run.completed`
  - `fake-tool`, `fake-reader`, 하위 에이전트 사건은 보내지 않는다
- 스트림 풀림 신호는 실행 상태를 푸는 것과 따로 둔다. `TEST_RELEASE_LONG_ACTIVITY_PATH = "/__test/release-long-activity"` 경로를 새로 두고, `FakeHermes` 와 `test/browser/fixtures.ts` 의 `FakeHermesControl` 에 `releaseLongActivity()` 를 더한다. 기존 hold 경로의 등록 방식을 본뜬다
- 검사는 `hermes.holdNextRun()` 으로 실행을 잡아 두고 이 입력을 보낸다. 그래야 스트림이 끝나도 turn 이 도는 중으로 남는다
- 다른 입력의 사건은 바꾸지 않는다

### 4. 이 phase 를 검증하는 테스트

- `test/unit/tool-label.test.ts` 신규
  - `terminal` 은 도는 중 「작업을 실행하는 중」, 끝나면 「작업 실행」
  - `vision_analyze` 는 「사진을 보는 중」, `web_search` 는 「검색하는 중」, `skill_view` 는 「스킬 안내를 읽는 중」
  - `mcp__fos_assistant__artifact_write` 는 「결과물을 저장하는 중」
  - `fake-tool` 과 `null` 은 「도구를 쓰는 중」 과 「도구 사용」
- `test/unit/activity-state.test.ts` 에 `activityLabel` 이 같은 도구 항목에서 `running` 일 때와 `done` 일 때 다른 문장을 내는 것을 더한다
- `test/browser/activity-scroll.spec.ts` 신규. 두 폭 모두에서 돈다
  - `hermes.holdNextRun()` 뒤에 `LONG_ACTIVITY_PROBE` 를 보내고, 항목 31개가 온 뒤 live 블록(`data-mode="live"`)을 펼친다
  - 도는 중 제목에 「작업을 실행하는 중」 이 보이고 `terminal` 글자는 보이지 않는다
  - `activity-scroll` 의 `clientHeight` 가 257 이하이고 `scrollHeight` 가 `clientHeight` 보다 크며, 맨 아래에서 16 이내다
  - `activity-block` 의 높이가 360 이하다. 채팅 영역이 목록 길이만큼 늘지 않았다는 뜻이다
  - 목록을 맨 위로 올린 뒤 `hermes.releaseLongActivity()` 로 스트림을 푼다. live 블록의 항목이 41개가 된 것을 확인한 뒤에도 `scrollTop` 이 0 이다. 이 확인은 `hermes.releaseHeldRun()` 을 부르기 전에 한다. 먼저 부르면 turn 이 끝나 live 블록이 저장된 블록으로 바뀐다
  - 확인이 끝나면 `hermes.releaseHeldRun()` 을 부른다. 검사가 실패해도 `finally` 에서 두 신호를 모두 푼다
  - 문서의 가로 스크롤이 생기지 않는다(`scrollWidth - clientWidth <= 0`)
  - 끝난 뒤 새로 고쳐 저장된 블록을 펼치면 `scrollTop` 이 0 이다
- `test/browser/execution-tree.spec.ts` 의 `deepTreeFixture` 는 긴 도구 이름으로 가로 넘침을 검사한다. 이제 이름이 「도구 사용」 으로 보이므로 그 긴 글자를 `detail` 쪽으로 옮겨 가로 넘침 검사가 계속 긴 글자를 다루게 한다
- `test/browser/execution-tree.spec.ts` 의 `fake-tool`, `fake-reader` 글자 찾기를 `[data-tool="fake-tool"]`, `[data-tool="fake-reader"]` 로 바꾸고, 그 줄에 「도구 사용」 이 보이는지 확인한다
- 도구 원래 이름을 글자로 찾는 다른 브라우저 검사가 있으면 같은 방식으로 바꾼다. `grep -rn "fake-tool\|fake-reader\|도구: " test/browser` 로 찾는다

## 검증

```bash
# cwd: 저장소 root
node --test test/unit/tool-label.test.ts test/unit/activity-state.test.ts
node --test 'test/unit/**/*.test.ts'
```

```bash
# cwd: web/. 브라우저 검사는 한 번에 하나만 돈다. 먼저 다른 검사가 없는지 본다
ps -ax | grep -E "playwright test|standalone/server.js" | grep -v grep
pnpm typecheck
pnpm test:browser activity-scroll execution-tree activity-panel
```

모두 통과해야 한다. `pnpm test:browser` 의 인자는 Playwright 의 파일 이름 필터다.

```bash
# cwd: 저장소 root. 아무것도 나오지 않아야 한다
grep -rn '도구: {' web/src
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `web/src/lib/tool-label.ts` | 신규 |
| `web/src/components/chat/activity/activity-state.ts` | 수정 |
| `web/src/components/chat/activity/activity-block.tsx` | 수정 |
| `web/src/components/chat/activity/activity-timeline.tsx` | 수정 |
| `web/src/components/execution/execution-event-row.tsx` | 수정 |
| `test/e2e/fake-hermes.ts` | 수정 |
| `test/browser/fixtures.ts` | 수정 |
| `test/unit/tool-label.test.ts` | 신규 |
| `test/unit/activity-state.test.ts` | 수정 |
| `test/browser/activity-scroll.spec.ts` | 신규 |
| `test/browser/execution-tree.spec.ts` | 수정 |
