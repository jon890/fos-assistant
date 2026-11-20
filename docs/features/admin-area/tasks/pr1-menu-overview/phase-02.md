# Phase 02. 관리자 메뉴 세 묶음, 휴대폰 서랍, 요약 화면

**Execution profile**: standard

## 목표

관리자 영역의 메뉴를 세 묶음으로 바꾸고 휴대폰에서 서랍으로 열며, `/admin` 을 phase 01 의 두 엔드포인트를 읽는 요약 화면으로 바꾼다.

**범위 외**: 다른 관리자 화면의 머리와 빈 상태 맞추기(PR2), 품질과 기억 메뉴 줄(그 화면을 만드는 PR).

## 컨텍스트

**요구사항**: REQ1, REQ2, REQ3, REQ4, REQ5, REQ6

**근거 문서**: `docs/features/admin-area/spec.md`, `docs/features/admin-area/design.md`

- 메뉴 목록의 원본은 `web/src/components/shell/admin-shell.tsx` 의 `LINKS` 다. 실행 하나의 화면을 「사용량과 비용」 으로 치는 `selected` 규칙은 남긴다.
- 서랍은 `web/src/components/ui/sheet.tsx` 를 쓴다. 일반 화면 틀(`web/src/components/shell/app-shell.tsx` 의 `Sheet`, `SheetContent side="left"`, `SheetTitle className="sr-only"`, 「사이드바 열기」 `TooltipButton`)의 모양을 따른다.
- 읽기와 다시 읽기는 `web/src/components/workspace/admin-workspace-list.tsx` 의 `attempt` 모양을 따른다. 프록시 라우트는 `web/src/app/api/admin/workspaces/route.ts` 와 같다.
- 시각은 `formatWhen`, 에이전트 이름이 없을 때는 `agentLabel`, 금액은 `formatAmount`(모두 `web/src/lib/format.ts`)를 쓴다. 실행 기록 표(`web/src/components/usage/execution-table.tsx`)와 같은 함수다.
- 화면 문구 규칙은 `docs/code-rules/rule-58.md`, 화면 상태 규칙은 `rule-69.md` 를 읽는다.

## 의도 메모

- 「요약」 줄은 `/admin` 과 정확히 같을 때만 골라진다(설계 DECISION4).
- 넓은 화면의 세로 메뉴와 머리는 그대로다. 가로 메뉴 줄은 없앤다.
- 남의 실패 줄은 링크로 그리지 않는다. `mine` 만 `/admin/executions/{id}` 링크다.
- 카드 실패 때 응답의 오류 코드나 메시지를 그리지 않는다.

## 작업 항목

### 1. `web/src/components/shell/admin-shell.tsx`

- `LINKS` 를 묶음 셋으로 바꾼다: 「살펴보기」 요약(`/admin`, `LayoutDashboard`), 사용량과 비용. 「비서 설정」 에이전트, 모델, 도구. 「사용자와 자원」 사용자, 외부 서비스 연결, 브라우저, 파일 공간. 아이콘은 지금 것을 그대로 쓴다.
- 묶음 제목은 링크가 아닌 작은 글자(`text-xs text-muted-foreground`)로 그리고, 각 묶음을 `aria-labelledby` 로 제목과 잇는 목록으로 둔다.
- 메뉴 부분을 한 부품(예: `AdminMenu({ onNavigate })`)으로 빼서 넓은 화면의 `nav`(`hidden md:flex`)와 서랍 안에서 같이 쓴다. 두 곳 모두 `aria-label="관리자 메뉴"` 를 쓰되 동시에 하나만 보이게 한다.
- `md` 미만의 머리: 맨 왼쪽에 「관리자 메뉴 열기」 단추(`Menu` 아이콘, `md:hidden`), 그 오른쪽 「관리자」 배지, 오른쪽 끝에 「사용 화면으로 돌아가기」. 서랍에서 링크를 누르면 닫는다. 넓은 폭이 되면 닫아 둔다(`app-shell.tsx` 의 `wide` 처리처럼 `useMediaQuery` 를 쓴다).

### 2. 요약 화면

- `web/src/app/admin/page.tsx`: `redirect` 를 없애고 `<AdminOverview />` 를 그린다. `metadata.title` 은 「요약」.
- `web/src/app/api/admin/overview/failures/route.ts`, `web/src/app/api/admin/overview/monthly/route.ts`: Control Plane 의 `/api/v1/admin/overview/failures`, `/monthly` 로 넘긴다.
- `web/src/lib/admin-overview-api.ts`: 응답 타입 둘과 `fetch("/api/admin/overview/failures", { cache: "no-store" })` 같은 읽기 함수.
- `web/src/components/admin/admin-overview.tsx`(`"use client"`):
  - 머리: `h1` 「요약」, 설명 「그룹 전체의 실패와 이번 달 사용량을 한눈에 봐요」.
  - 카드 둘(`components/ui/card.tsx`). 넓은 화면은 두 열, 좁은 화면은 한 열. 각 카드 제목 옆에 「그룹 전체」 배지.
  - 「품질과 실패」: 「최근 7일 동안 실패한 실행」 과 수. 최근 세 건의 줄마다 사람 이름 · `agentLabel(agentName)` · `formatWhen(startedAt)` · 오류 코드. `mine` 이면 줄 전체가 `/admin/executions/{id}` 링크. 0건이면 「최근 7일 동안 실패한 실행이 없어요」. 그 아래 「아직 품질 확인 결과가 없어요」. 카드 끝 링크 「내 실행 기록」 → `/admin/usage?tab=executions`.
  - 「이번 달 사용량」: 실행 수, 구독 경로 실행 수(「N건은 구독 경로로 실행했어요」), 예상 추가 사용 요금(`formatAmount(actualCostMicros, currency)`). `incomplete` 면 「금액을 확인하지 못한 실행이 있어 실제보다 적을 수 있어요」. 카드 끝 링크 「내 사용량 자세히」 → `/admin/usage`.
  - 카드마다 상태를 따로 갖는다. 읽는 중은 카드 모양 `Skeleton`, 실패는 그 카드 안에 「불러오지 못했어요」 와 「다시 읽기」.
  - 파일이 길면 카드마다 파일을 나눈다(`docs/code-rules/rule-44.md` 의 파일 길이).

### 3. 문서

- `web/docs/code-architecture.md`: 화면 표의 `/admin` 행(요약 화면), 「관리자 영역의 틀」 의 그림(좁은 화면은 서랍), 메뉴 아이콘 줄(요약 `LayoutDashboard`).
- `docs/features/users.md` 「관리자 영역」 표: 맨 위에 「요약」 행, 묶음 열을 더한다.
- `docs/features/admin-area.md`: 「아직 구현하지 않았다」 를 PR1 이 들어왔다는 문장으로 바꾼다.

### 4. 테스트

- `web/test/browser/admin-area.spec.ts`:
  - `enterAdminArea` 의 기대 주소를 `/admin$` 으로 바꾼다.
  - 메뉴 검사: 묶음 제목 셋과 줄 순서. 넓은 화면은 바로, 휴대폰 프로젝트는 「관리자 메뉴 열기」 를 눌러 서랍에서 본다. 「요약」 만 골라져 있다. 가로 넘침이 없다.
  - 휴대폰: 서랍에서 「모델」 을 누르면 서랍이 닫히고 `/admin/models`. 다시 열어 `Esc` 로 닫힌다.
  - 「`/admin` 을 바로 열면 사용자 화면으로 넘어간다」 를 「`/admin` 은 요약 화면이다」 로 바꾼다(카드 둘의 제목). 카드의 두 링크를 눌러 `/admin/usage?tab=executions` 와 `/admin/usage` 가 열리는지 본다.
  - `/admin/models` 와 `/admin/executions/{id}` 에서 골라진 줄(`[aria-current="page"]`)이 하나이고 각각 「모델」, 「사용량과 비용」 인지 본다. 하위 화면에서 「요약」 이 함께 골라지지 않는지가 회귀 지점이다.
  - 휴대폰: `page.setViewportSize({ width: 360, ... })` 에서 서랍이 닫힌 동안 「관리자 메뉴」 내비게이션이 없고, 단추에 초점을 두고 Enter 로 연다.
  - `MEMBER` 역할로 `/admin` 을 열면 홈.
- 메뉴 이름으로 찾는 다른 테스트를 `grep -rn '관리자 메뉴' web/test` 로 모두 찾아 휴대폰이면 서랍을 먼저 열게 고친다. 지금 아는 것은 `web/test/browser/admin-workspaces.spec.ts`(「파일 공간」 링크), `web/test/browser/user-browser.spec.ts`(「브라우저」 줄의 `aria-current`)다.
- 새 `web/test/browser/admin-overview.spec.ts`: `usage-executions-paging.spec.ts` 의 `createAdminUser` 모양으로 새 그룹(`groupId` 를 검사마다 큰 고유 수로) 관리자 둘을 만들고 각자 실패를 심는다. 보는 관리자로 `/admin` 을 열어 수 5, 최근 세 건, 남의 줄은 링크가 아님, 내 줄을 누르면 실행 화면. `page.route("**/api/admin/overview/failures", ...)` 로 500 을 주면 「품질과 실패」 카드에만 실패 안내가 보이고 「이번 달 사용량」 은 값이 보이며, 가로채기를 풀고 「다시 읽기」 를 누르면 수가 보인다. 끝나면 심은 실행을 지운다. 세부는 아래와 같다.
  - 그룹 번호는 `isolatedUser` 가 만드는 메일의 해시 앞 12자리를 16진수로 읽은 수(48비트라 JS 수로 안전)처럼 실행마다 달라지는 값이다. 심은 사용자는 지우지 않는다. 그룹이 매번 새것이라 섞이지 않는다.
  - 실패의 시작 시각은 A 2건, B 3건, 8일 전 A 1건이다. 최근 세 건에 A 줄과 B 줄이 함께 들도록 B 의 두 건만 A 보다 최근으로 둔다. 수가 5 이고 8일 전 실패는 세지 않는다.
  - B 의 줄은 링크가 아니고, B 의 실행 번호로 `/admin/executions/{id}` 를 직접 열어도 실행 내용이 보이지 않는다.
  - 이번 달 카드: A 와 B 의 이번 달 실행 수의 합이 보인다. 가격이 비어 있는 실행을 심은 경우에만 「금액을 확인하지 못한 실행이 있어 실제보다 적을 수 있어요」 가 보인다.
  - 실행이 없는 새 그룹의 관리자로 열면 「최근 7일 동안 실패한 실행이 없어요」 와 「아직 품질 확인 결과가 없어요」 가 보인다.

## 검증

```bash
cd web && pnpm lint && pnpm format:check && pnpm typecheck && pnpm test:node
cd web && HEAVY_SLOTS=2 orch slot heavy -- pnpm test:browser test/browser/admin-area.spec.ts test/browser/admin-overview.spec.ts test/browser/usage-executions-paging.spec.ts test/browser/admin-workspaces.spec.ts test/browser/user-browser.spec.ts
node --test test/repo/doc-links.test.ts test/repo/doc-files.test.ts test/repo/doc-code-references.test.ts
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `web/src/components/shell/admin-shell.tsx` | 수정 |
| `web/src/app/admin/page.tsx` | 수정 |
| `web/src/app/api/admin/overview/failures/route.ts` | 신규 |
| `web/src/app/api/admin/overview/monthly/route.ts` | 신규 |
| `web/src/lib/admin-overview-api.ts` | 신규 |
| `web/src/components/admin/admin-overview.tsx` | 신규 |
| `web/test/browser/admin-area.spec.ts` | 수정 |
| `web/test/browser/admin-overview.spec.ts` | 신규 |
| `web/test/browser/admin-workspaces.spec.ts` | 수정 |
| `web/test/browser/user-browser.spec.ts` | 수정 |
| `web/docs/code-architecture.md` | 수정 |
| `docs/features/users.md` | 수정 |
| `docs/features/admin-area.md` | 수정 |
