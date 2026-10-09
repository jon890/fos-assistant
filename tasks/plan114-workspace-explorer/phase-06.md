# Phase 06. 관리자 「파일 공간」 화면

**Execution profile**: standard

## 목표

관리자 영역에 「파일 공간」 메뉴와 `/admin/workspaces` 를 두어 phase 05 의 경로로 공간마다 용량을 보인다.

**범위 외**: backend(phase 05), 일반 화면(phase 02, 04).

## 컨텍스트

**근거 문서**: `docs/frontend/structure.md` 의 화면 표 `/admin/workspaces` 줄과 「관리자 영역」 의 「파일 공간」 줄, `docs/frontend/shell.md` 의 관리자 메뉴 아이콘, `docs/code-architecture.md` 「실행 공간 파일」 의 「관리자 용량」. 위 문서는 phase 05 가 이미 적용했다.

phase 02 가 만든 것: `web/src/lib/workspace-api.ts`, `web/src/lib/workspace-file.ts` 의 `formatSize`.

따를 기존 패턴:

- 관리자 화면: `web/src/app/admin/browsers/page.tsx`(`redirectMemberHome`)와 `web/src/components/browser/admin-browser-list.tsx`, 서버 라우트 `web/src/app/api/admin/browsers/route.ts`.
- 관리자 메뉴: `web/src/components/shell/admin-shell.tsx` 의 링크 목록. `test/browser/admin-area.spec.ts` 의 「관리자 메뉴」 단언이 메뉴 글자를 정확히 단언한다.

## 의도 메모

- 화면은 파일 이름을 받지도 그리지도 않는다. 크기는 `formatSize` 로 그린다.
- 오류 코드는 관리자 영역이라 그려도 되지만 이 화면은 그리지 않는다. 실패는 「불러오지 못했어요」 와 다시 읽기다.

## 작업 항목

### 1. 서버 라우트와 API

- `web/src/app/api/admin/workspaces/route.ts`: `GET` 이 `/api/v1/admin/workspaces` 를 옮긴다.
- `web/src/lib/workspace-api.ts` 에 `fetchWorkspaceUsage()` 와 `WorkspaceUsageReport` 타입을 더한다.

### 2. 화면

- `web/src/components/workspace/admin-workspace-list.tsx`: 표의 칸은 주인(사용자 이름, 에이전트면 「에이전트 · <이름>」, 이름이 비면 「알 수 없음」), 용량, 항목 수, 「일부만 셈」 배지(`Badge` 의 의미 색). `available` 이 거짓이면 「파일 공간을 쓸 수 없어요」. 빈 목록이면 「아직 실행 공간이 없어요」.
- `web/src/app/admin/workspaces/page.tsx`: `/admin/browsers` 와 같은 모양. `metadata.title` 은 「파일 공간」.
- `admin-shell.tsx` 의 링크 목록 끝에 `{ href: "/admin/workspaces", label: "파일 공간", icon: HardDrive }` 를 더한다.

### 3. 검사

`test/browser/admin-workspaces.spec.ts` 신규(`page.route` 로 `/api/admin/workspaces` 를 가짜로 답한다)

- 관리자 메뉴의 「파일 공간」 으로 가면 표에 사용자 이름, 「에이전트 · <이름>」, 용량, 항목 수와 「일부만 셈」 배지가 보인다. 가짜 응답에 없는 파일 이름이 보이지 않는다.
- `available: false` 면 「파일 공간을 쓸 수 없어요」.
- 500 이면 「불러오지 못했어요」 와 다시 읽기.

`test/browser/admin-area.spec.ts`: 관리자 메뉴 단언 끝에 `"파일 공간"` 을 더한다.

## 검증

```bash
cd web && pnpm lint && pnpm format:check && pnpm typecheck
cd web && pnpm test:browser admin-workspaces.spec.ts admin-area.spec.ts --repeat-each=3 --retries=0
scripts/check-local.sh admin-workspaces.spec.ts admin-area.spec.ts
grep -rn 'style={{' web/src/components/workspace/
```

- 첫 셋이 통과하고 브라우저 검사는 두 폭에서 3회씩 통과한다.
- 마지막 줄은 아무것도 내지 않는다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `web/src/app/api/admin/workspaces/route.ts` | 신규 |
| `web/src/lib/workspace-api.ts` | 수정 |
| `web/src/components/workspace/admin-workspace-list.tsx` | 신규 |
| `web/src/app/admin/workspaces/page.tsx` | 신규 |
| `web/src/components/shell/admin-shell.tsx` | 수정 |
| `test/browser/admin-workspaces.spec.ts` | 신규 |
| `test/browser/admin-area.spec.ts` | 수정 |
