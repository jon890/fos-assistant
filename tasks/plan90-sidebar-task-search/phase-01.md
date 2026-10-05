# Phase 01. 검색어가 바뀌면 접은 작업 줄을 다시 펼친다

**Execution profile**: fast

## 목표

사이드바 「예약 작업」 묶음에서 사용자가 접은 작업 줄은 검색어가 바뀌면 다시 펼쳐 검색에 걸린 작업 대화를 보인다. 검색 중에도 다시 눌러 접을 수 있다.

**범위 외**: 작업 목록 화면(`web/src/components/task/task-list.tsx`, 접기와 검색이 없다). 묶는 방법(`group-by-task.ts`).

## 컨텍스트

- `web/src/components/shell/conversation-nav.tsx`
  - `expandedTasks`(사용자가 펼친 작업)와 `collapsedTasks`(사용자가 접은 작업) 두 `Set<string>` state 가 있다
  - 작업 줄의 `open` 은 `!collapsedTasks.has(group.taskId) && (searching || expandedTasks.has(group.taskId) || 지금 연 대화가 그 작업 대화)` 다. 그래서 접은 기억이 검색보다 먼저 이긴다
  - 검색어는 prop `query` 이고 `normalizedQuery = query.trim().toLocaleLowerCase("ko-KR")`, `searching = normalizedQuery !== ""` 다
  - 같은 파일이 렌더 중에 state 를 맞추는 모양을 이미 쓴다: `initialIds` 가 `null` 이면 렌더 중에 `setInitialIds(...)` 한다. 이전 값을 state 로 들고 렌더 중에 비교해 고치는 React 의 권장 모양이다. `useEffect` 로 비우면 한 번 접힌 채 그려진다
- 브라우저 검사: `test/browser/tasks.spec.ts` 의 「지금 연 작업 대화의 작업 줄을 눌러 접을 수 있다」 가 `page.route("**/api/chat/conversations?**", ...)` 로 작업 대화 둘과 보통 대화 하나를 돌려주고 `nav.getByTestId("task-group")` 를 누른다. 검색 칸은 `web/src/components/shell/sidebar.tsx` 의 `aria-label="대화 검색"` 입력이다

**근거 문서**: `docs/backend/task.md` 의 「화면」 의 「검색은 작업 대화의 제목도 거른다」 문단(이 plan 이 이미 고쳤다), `docs/frontend/shell.md` 의 예약 작업 묶음 줄

## 의도 메모

- `open = searching || ...` 처럼 검색이 접은 기억을 이기게 하는 안은 기각한다. 검색 중에는 접을 수 없게 된다
- 검색어가 바뀔 때마다 비운다. 같은 검색어에서 사용자가 다시 접은 것은 유지된다

## 작업 항목

### 1. `conversation-nav.tsx`

- `const [collapsedForQuery, setCollapsedForQuery] = useState(normalizedQuery);` 처럼 접은 기억이 속한 검색어를 state 로 든다(이름은 파일의 주석 관례에 맞게 한국어 주석을 붙인다)
- 렌더 중에 `normalizedQuery !== collapsedForQuery` 면 `setCollapsedForQuery(normalizedQuery)` 와 `setCollapsedTasks(new Set())` 를 부른다. `normalizedQuery` 를 계산한 줄 뒤에 둔다
- 검색어를 지워 빈 글로 돌아갈 때도 비운다(같은 비교로 된다). 그때는 `searching` 이 거짓이라 지금 연 대화나 펼친 기억만으로 열린다

### 2. 이 phase 를 검증하는 브라우저 검사

`test/browser/tasks.spec.ts` 에 「접어 둔 작업 줄도 검색에 걸리면 다시 펼친다」 를 더한다. 「지금 연 작업 대화의 작업 줄을 눌러 접을 수 있다」 의 `row`, `page.route` 준비를 그대로 쓴다.

1. `/chat/22222222-2222-4222-8222-222222222222` 를 열고(좁은 폭이면 `openSidebarIfNarrow`) 작업 줄을 눌러 접는다. `aria-expanded` 가 `false`
2. 「대화 검색」 칸에 `작업 대화` 를 넣는다
3. 작업 줄의 `aria-expanded` 가 `true` 이고 「작업 대화 이전」 링크가 보인다
4. 작업 줄을 다시 누르면 `false` 가 된다(검색 중에도 접힌다)

## 검증

```bash
pnpm --dir web lint
scripts/check-local.sh tasks
```

- 첫 줄: 웹 lint 가 통과한다
- 둘째 줄: 전체 로컬 검사. 브라우저 검사는 `test/browser/tasks.spec.ts` 만 돌고 새 검사를 포함한다. 모바일과 데스크톱 폭 모두 통과한다

## 변경 파일

| 파일 | 변경 |
| --- | --- |
| `web/src/components/shell/conversation-nav.tsx` | 수정 |
| `test/browser/tasks.spec.ts` | 수정 |
| `docs/backend/task.md` | 수정 |
