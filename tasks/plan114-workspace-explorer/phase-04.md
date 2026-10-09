# Phase 04. 파일 공간의 지우기와 관리자 「파일 공간」 화면

**Execution profile**: standard

## 목표

`/files` 의 줄에 「지우기」 를 두고 확인 창을 거쳐 phase 03 의 지우기 경로를 부른다.
관리자 영역에 「파일 공간」 메뉴와 `/admin/workspaces` 를 두어 공간마다 용량을 보인다.

**범위 외**: backend(phase 01, phase 03).

## 컨텍스트

**근거 문서**: `docs/frontend/structure.md` 의 「파일 공간」(줄의 동작, 지우기 확인 창 문단)과 화면 표의 `/admin/workspaces`, 「관리자 영역」 의 「파일 공간」 줄, `docs/frontend/shell.md` 의 관리자 메뉴 아이콘, `docs/code-architecture.md` 「실행 공간 파일」 의 「지우기」 와 「관리자 용량」, `docs/flow.md` 「파일 공간을 열 때」 의 지우기 시퀀스.
위 문서는 phase 03 이 이미 적용했다.

phase 02 가 만든 것(읽어서 이름을 맞춘다): `web/src/components/workspace/workspace-explorer.tsx`, `workspace-entry-list.tsx`, `workspace-preview.tsx`, `web/src/lib/workspace-api.ts`, `web/src/lib/workspace-file.ts`, `web/src/app/api/workspace/entries/route.ts`, `test/browser/workspace-files.spec.ts`.

따를 기존 패턴:

- 확인 창: `web/src/components/browser/delete-browser-dialog.tsx`(`AlertDialog`, 지우는 동안 닫히지 않음, `Button` 의 `loading`).
- 관리자 화면: `web/src/app/admin/browsers/page.tsx`(`redirectMemberHome`)와 `web/src/components/browser/admin-browser-list.tsx`, 서버 라우트 `web/src/app/api/admin/browsers/route.ts`.
- 관리자 메뉴: `web/src/components/shell/admin-shell.tsx` 의 링크 목록. `test/browser/admin-area.spec.ts` 가 메뉴 글자를 정확히 단언한다.

## 의도 메모

- 지우기 단추는 상태의 `deletable` 이 참일 때만 그린다. 거짓이면 단추 자리도 비운다.
- 확인 창을 열 때 상태를 다시 읽어 `runningExecutions` 를 본다. 읽지 못하면 경고 문장 없이 창을 연다.
- 지운 뒤 목록을 다시 읽고, 미리 보던 파일이나 그 파일이 든 디렉터리를 지웠으면 미리보기를 닫는다(주소의 `file` 을 지운다).
- 관리자 화면은 파일 이름을 받지도 그리지도 않는다. 크기는 `formatSize` 로 그린다.
- 오류 문구는 `docs/frontend/structure.md` 의 것을 그대로 쓴다. 오류 코드는 그리지 않는다.

## 작업 항목

### 1. `web/src/app/api/workspace/entries/route.ts` 에 `DELETE`

`GET` 과 같이 `path` 인자를 옮겨 `callControlPlane(..., { method: "DELETE" })` 를 부른다.

### 2. `web/src/lib/workspace-api.ts`

`deleteWorkspaceEntry(path)` 와 응답 타입 `WorkspaceDeletion({kind, entries, bytes})`, 관리자 `fetchWorkspaceUsage()` 와 `WorkspaceUsageReport` 타입을 더한다.

### 3. `web/src/components/workspace/delete-entry-dialog.tsx` 신규와 목록 연결

- 제목 「<이름>을 지울까요?」(`web/src/lib/korean-particle.ts` 로 조사를 맞춘다). 설명에 종류, 디렉터리면 「안의 파일까지 모두 지워요」, 도는 실행이 있으면 「에이전트가 지금 일하고 있어요. 쓰는 중인 파일이면 다시 생길 수 있어요.」.
- `workspace-entry-list.tsx` 의 줄 동작에 「지우기」 를 더하고 `workspace-explorer.tsx` 가 창과 결과를 다룬다. 409, 502, 404 의 문구는 문서의 것이다. 404 면 목록을 다시 읽는다.

### 4. 관리자 「파일 공간」

- `web/src/app/api/admin/workspaces/route.ts`: `GET` 이 `/api/v1/admin/workspaces` 를 옮긴다.
- `web/src/components/workspace/admin-workspace-list.tsx`: 표의 칸은 주인(사용자 이름, 에이전트면 「에이전트 · <이름>」, 이름이 비면 「알 수 없음」), 용량, 항목 수, 「일부만 셈」 배지. `available` 이 거짓이면 「파일 공간을 쓸 수 없어요」. 빈 목록이면 「아직 실행 공간이 없어요」.
- `web/src/app/admin/workspaces/page.tsx`: `/admin/browsers` 와 같은 모양.
- `admin-shell.tsx` 의 링크 목록 끝에 `{ href: "/admin/workspaces", label: "파일 공간", icon: HardDrive }` 를 더한다.

### 5. 검사

`test/browser/workspace-files.spec.ts` 에 더한다.

- `deletable: false` 면 「지우기」 가 없다.
- `deletable: true` 에서 파일을 지우면 확인 창에 이름이 보이고, 확인하면 `DELETE /api/workspace/entries?path=` 가 한 번 가고 목록에서 그 줄이 사라진다.
- 도는 실행이 있으면 창에 경고 문장이 보인다.
- 디렉터리를 지우는 창에는 「안의 파일까지 모두 지워요」 가 보인다.
- 409 면 「항목이 너무 많아 지우지 않았어요. 안쪽 폴더부터 지워 주세요.」 가 보이고 줄이 남는다.
- 미리 보던 파일을 지우면 미리보기가 닫힌다.

`test/browser/admin-workspaces.spec.ts` 신규(`page.route` 로 `/api/admin/workspaces` 를 가짜로 답한다)

- 관리자 메뉴의 「파일 공간」 으로 가면 표에 사용자 이름, 용량, 항목 수와 「일부만 셈」 배지가 보인다. 파일 이름이 보이지 않는다.
- `available: false` 면 「파일 공간을 쓸 수 없어요」.

`test/browser/admin-area.spec.ts`: 관리자 메뉴 단언 끝에 `"파일 공간"` 을 더한다.

## 검증

```bash
node --test test/unit/workspace-file.test.ts
cd web && pnpm lint && pnpm format:check && pnpm typecheck
cd web && pnpm test:browser workspace-files.spec.ts admin-workspaces.spec.ts admin-area.spec.ts --repeat-each=3 --retries=0
scripts/check-local.sh workspace-files.spec.ts admin-workspaces.spec.ts admin-area.spec.ts
grep -rn 'style={{' web/src/components/workspace/
```

- 첫 넷이 통과하고 브라우저 검사는 두 폭에서 3회씩 통과한다.
- 마지막 줄은 아무것도 내지 않는다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `web/src/app/api/workspace/entries/route.ts` | 수정 |
| `web/src/lib/workspace-api.ts` | 수정 |
| `web/src/components/workspace/delete-entry-dialog.tsx` | 신규 |
| `web/src/components/workspace/workspace-entry-list.tsx` | 수정 |
| `web/src/components/workspace/workspace-explorer.tsx` | 수정 |
| `web/src/app/api/admin/workspaces/route.ts` | 신규 |
| `web/src/components/workspace/admin-workspace-list.tsx` | 신규 |
| `web/src/app/admin/workspaces/page.tsx` | 신규 |
| `web/src/components/shell/admin-shell.tsx` | 수정 |
| `test/browser/workspace-files.spec.ts` | 수정 |
| `test/browser/admin-workspaces.spec.ts` | 신규 |
| `test/browser/admin-area.spec.ts` | 수정 |
