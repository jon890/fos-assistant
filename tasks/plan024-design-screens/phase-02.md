# Phase 02. 사용량과 실행 나무 화면을 shadcn/ui 부품으로 옮긴다

**Execution profile**: standard

## 목표

`/usage` 와 `/executions/{id}` 가 `components/ui/` 의 shadcn/ui 부품을 쓰게 한다.
표는 shadcn 의 `table` 로, 합계 칸은 `card` 로, 상태 표시는 `badge` 로 옮긴다.

**범위 외**: 에이전트와 기억 화면(phase-01), 관리 화면과 로그인과 옛 부품 삭제(phase-03).
대화 화면의 작업 과정 패널이 실행 나무 부품을 다시 쓰지만, 그 패널의 틀은 이미 옮겨졌다. 여기서는 나무 부품 자체만 바꾼다.

## 컨텍스트

**요청을 보내는 단추는 `Button` 의 `loading` 을 쓴다.** `disabled={busy}` 만으로 두지 않는다.
이 phase 가 옮기는 화면에서 `disabled={busy` 처럼 보내는 중에 잠그는 단추를 모두 찾아 `loading={busy}` 와 지금 하는 일을 적은 `loadingText`(「저장 중」, 「지우는 중」 처럼)를 준다.
근거는 `docs/flow.md` 「기다리는 동안 보이는 것」 이다. 브라우저 검사가 단추 이름으로 찾는 곳은 `loadingText` 때문에 보내는 중에 이름이 바뀐다. 누른 뒤의 기대를 이름이 아니라 결과로 보게 고친다.

**근거 문서**: `docs/adr/ADR-023-화면-부품은-shadcn-ui-를-저장소에-복사해-쓴다.md`,
`docs/code-architecture.md` 「사용량 화면의 절」 「디렉터리」, `docs/flow.md` 「실행 하나를 다시 볼 때」.

phase-01 이 `web/src/components/ui/native-select.tsx` 와 `card.tsx` 를 두었다.

**시작할 때 옮길 파일을 다시 모은다.**

```bash
# cwd: 저장소 root
ls web/src/components/usage web/src/components/execution web/src/app/usage web/src/app/executions/\[id\]
grep -rn '<table\|<select\|<dl\|Badge\|EmptyState\|Stat\b' web/src/components/usage web/src/components/execution web/src/app/usage
```

계획을 쓸 때의 모양이다.

| 파일 | 지금 쓰는 것 |
| --- | --- |
| `components/usage/breakdown-section.tsx` | `aria-label="어디에 썼나"` 절. 축 고르기가 `<select data-testid="breakdown-axis">` |
| `components/usage/breakdown-table.tsx` | `<table className="hidden w-full text-left text-sm md:table" data-testid="breakdown-table">` 와 좁은 폭의 `data-testid="breakdown-cards"`, 빈 상태 `EmptyState` |
| `components/usage/execution-table.tsx` | `<table ... data-testid="execution-table">`, 줄마다 `Badge emphasis={failed}` 와 `execution-cost` 같은 `data-testid` |
| `components/usage/execution-list.tsx`, `execution-card.tsx` | 좁은 폭의 `data-testid="execution-cards"` 와 카드 |
| `components/usage/monthly-summary.tsx` | `<dl>` 안에 `Stat` 넷 |
| `components/usage/fingerprint-section.tsx` | `aria-label="무엇이 달라졌나"`, `data-testid="fingerprint-section"` |
| `components/ui/stat.tsx` | `<div>` 안의 `<dt>` 와 `<dd>`. `border-l-2 border-foreground pl-3` |
| `components/execution/execution-tree.tsx`, `execution-node.tsx`, `execution-event-row.tsx`, `execution-detail.tsx` | `data-testid="execution-tree"`, `execution-node`, `execution-event-row`, `execution-tree-truncated-above`. 머리는 `<dl>` |

## 의도 메모

- **축 고르기는 네이티브 `<select>` 를 둔다.** `NativeSelect` 로 모양만 입힌다.
  고르는 기준은 테스트의 역할과 동작을 가장 덜 바꾸는 쪽이다.
  `usage-breakdown.spec.ts` 76행과 168행이 `getByTestId("breakdown-axis").selectOption("model")` 을 쓴다.
  `tabs` 나 `toggle-group` 으로 바꾸면 역할이 `tab` 이나 `radio` 가 되어 두 줄을 모두 고쳐야 하고,
  좁은 폭에서 네 축이 한 줄에 들어가지 않는다. 그래서 기각한다
- **표는 역할이 바뀌지 않는다.** shadcn 의 `Table` 은 `<table>`, `<thead>`, `<tr>`, `<th>`, `<td>` 를 그대로 그린다.
  `data-testid` 와 `hidden md:table` 같은 폭 전환 클래스를 `Table` 에 넘겨 그대로 둔다
- **`Stat` 은 `<dt>` 와 `<dd>` 를 지킨다.** `Card` 위에 다시 짓되 정의 목록의 구조를 바꾸지 않는다. `<dl>` 이 그것을 감싸기 때문이다
- `Badge` 는 앞선 디자인 기반 변경이 shadcn 것으로 바꿨다. 그 변경이 `emphasis` 를 variant 로 바꿨으면 그 이름을 쓴다.
  실패 표시는 `variant="destructive"` 가 아니라 그 변경이 정한 강조 variant 다. 실패는 되돌릴 동작이 아니라 상태다
- 표시 글 「아직 실행 기록이 없다」 「기록된 사건이 없다」 「끝나지 않음」 「여기부터 보이지 않는다」 와 금액 형식을 바꾸지 않는다

## Blocked 조건

- `web/src/components/ui/native-select.tsx` 나 `card.tsx` 가 없다 → `PHASE_BLOCKED: phase-01 이 끝나지 않았다`

## 작업 항목

### 1. `web/src/components/ui/table.tsx` 신규

`pnpm dlx shadcn@latest add table` 로 받는다. 받은 파일의 색 클래스가 ADR-023 의 토큰 이름을 쓰는지 본다.

### 2. 사용량 표와 카드

- `breakdown-table.tsx` 와 `execution-table.tsx` 의 `<table>` 부분을 `Table`, `TableHeader`, `TableBody`, `TableRow`, `TableHead`, `TableCell` 로 바꾼다.
  `data-testid`, `className="hidden ... md:table"`, 칸 순서를 그대로 둔다
- `execution-card.tsx` 와 `breakdown-table.tsx` 의 좁은 폭 카드를 `Card` 로 바꾼다. 카드 안 `data-testid` 를 그대로 둔다
- `breakdown-section.tsx` 의 축 고르기를 `NativeSelect` 로 바꾼다. `data-testid="breakdown-axis"`, `disabled={pending}`, 선택지 값을 그대로 둔다.
  80자를 넘는 className 한 곳을 이 바꿈으로 없앤다

### 3. 합계 칸

- `components/ui/stat.tsx` 를 `Card` 의 모양으로 다시 짓는다. `<dt>` 와 `<dd>` 와 `detail` 줄은 그대로다
- `monthly-summary.tsx` 의 80자를 넘는 className 을 `Stat` 의 props 나 그리드 클래스로 줄인다

### 4. 실행 나무

- `execution-node.tsx` 의 상태 표시를 `Badge` 로, 노드 테두리를 `Card` 나 `border-border` 토큰으로 둔다
- `execution-detail.tsx` 의 오류 줄 `role="alert"` 을 그대로 두되 모양은 `text-destructive` 다
- 들여쓴 사건 목록의 구조와 `data-testid` 를 바꾸지 않는다. 작업 과정 패널이 같은 부품을 쓴다

### 5. 이 phase 를 검증하는 테스트

고칠 테스트는 없다. 역할과 이름과 `data-testid` 를 모두 지켰는지 아래가 판정한다.

```bash
# cwd: web/
pnpm test:browser usage.spec.ts usage-breakdown.spec.ts execution-tree.spec.ts activity-panel.spec.ts
```

`usage.spec.ts` 는 폭마다 `execution-cards` 와 `execution-table` 가운데 하나를 본다. 두 폭 모두 통과해야 한다.

## 검증

```bash
# cwd: 저장소 root
grep -rn '<table' web/src/components/usage web/src/components/execution
grep -rn '<select' web/src/components/usage
grep -rnE 'className="[^"]{80,}"' web/src/components/usage web/src/components/execution web/src/app/usage
grep -rn 'style={{' web/src/components/usage web/src/components/execution
scripts/check-public-safe.sh
```

앞의 넷은 아무것도 내지 않아야 한다. `style={{` 는 까닭을 주석으로 단 예외만 남을 수 있다.

```bash
# cwd: web/
pnpm typecheck
pnpm test:browser usage.spec.ts usage-breakdown.spec.ts execution-tree.spec.ts activity-panel.spec.ts
```

끝나면 `tasks/plan024-design-screens/index.json` 의 이 phase 를 `completed` 로 바꾸고 `current_phase` 를 3으로 올린다.

## Critical Files

| 파일 | 변경 |
|---|---|
| `web/src/components/ui/table.tsx` | 신규 |
| `web/src/components/ui/stat.tsx` | 수정 |
| `web/src/components/usage/breakdown-section.tsx` | 수정 |
| `web/src/components/usage/breakdown-table.tsx` | 수정 |
| `web/src/components/usage/execution-table.tsx` | 수정 |
| `web/src/components/usage/execution-list.tsx` | 수정 |
| `web/src/components/usage/execution-card.tsx` | 수정 |
| `web/src/components/usage/monthly-summary.tsx` | 수정 |
| `web/src/components/usage/fingerprint-section.tsx` | 수정 |
| `web/src/components/execution/execution-tree.tsx` | 수정 |
| `web/src/components/execution/execution-node.tsx` | 수정 |
| `web/src/components/execution/execution-event-row.tsx` | 수정 |
| `web/src/components/execution/execution-detail.tsx` | 수정 |
