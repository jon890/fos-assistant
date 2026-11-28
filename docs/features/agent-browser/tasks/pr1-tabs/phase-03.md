# Phase 03. 웹 스위치와 문서

**Execution profile**: standard

## 목표

사용자가 「내 브라우저」 에서 에이전트의 브라우저 조작을 켜고 끄고, 관리자가 「브라우저」 관리자 화면에서 전체를 끈다. 지금 동작을 기능 파일과 실행 경계 표에 적는다.

**범위 외**: 백엔드 API(phase 01). 승인 카드 모양은 바꾸지 않는다.

## 컨텍스트

**요구사항**: REQ1

**근거 문서**: `docs/features/agent-browser/design.md` 의 「PR1 스위치」, 「사용자별 실행 경계」

- 상태 화면은 `web/src/components/browser/user-browser-panel.tsx`, API 도우미는 `web/src/lib/browser-api.ts`(`fetchBrowser`, `BrowserView`).
- 관리자 화면은 `web/src/components/browser/admin-browser-list.tsx`, `fetchAdminBrowsers`.
- API 호출은 서버 라우트와 lib 도우미를 거친다(rule-55). 색과 간격은 테마 토큰(rule-56).

## 의도 메모

- 스위치 문구에 위험을 한 줄로 보인다. 「켜면 에이전트가 이 브라우저의 탭을 보고, 승인한 동작을 해요. 로그인한 사이트의 내용을 읽을 수 있어요.」
- 관리자가 끈 동안 사용자 스위치는 누를 수 없고 「관리자가 꺼 두었어요」 를 보인다.

## 작업 항목

### 1. `web/src/lib/browser-api.ts`

`BrowserView.agentControl`, `setAgentControl(enabled)`, `fetchAdminAgentControl()`, `setAdminAgentControl(enabled)`.

### 2. `web/src/components/browser/user-browser-panel.tsx`

브라우저가 있을 때 상태 아래에 스위치 한 줄(`components/ui` 의 기존 스위치나 체크 부품). 바꾼 뒤 상태를 다시 읽는다.

### 3. `web/src/components/browser/admin-browser-list.tsx`

목록 위에 전체 스위치 한 줄.

### 4. 문서

- `docs/features/agent-browser.md`: covers 에 이번 파일을 더하고 지금 동작(스위치, 세 도구, 카드)을 적는다.
- `docs/features/user-browser.md`: 요구 절에 「사용자가 켠 동안 Control Plane 내장 도구가 탭을 본다」 한 줄과 기능 파일 링크.
- `docs/code-architecture.md` 「사용자별 실행 경계」 표에 「에이전트의 내 브라우저 도구」 행과 합성 검사 이름(`BrowserTabControlTest`, `McpBrowserTabToolsTest`).
- `docs/features/mcp.md` 의 도구 목록에 세 도구가 있으면 더한다.
- 구현이 끝나면 이 계획 폴더(`docs/features/agent-browser/tasks/pr1-tabs/`)를 같은 PR 에서 지운다.

### 5. 테스트

- `web/test/unit/browser-agent-control.test.ts`: 스위치 요청의 경로와 본문, 관리자가 껐을 때 스위치를 잠그는 판정 함수. 화면 모양은 브라우저 검사 대신 판정 함수로 본다.

## 검증

```bash
cd web && npm run lint && npm run typecheck && node --test test/unit/browser-agent-control.test.ts
node --test test/repo/doc-links.test.ts test/repo/doc-files.test.ts test/repo/adr-index.test.ts
scripts/dev/check-local.sh --fast
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `web/src/lib/browser-api.ts` | 수정 |
| `web/src/components/browser/user-browser-panel.tsx` | 수정 |
| `web/src/components/browser/admin-browser-list.tsx` | 수정 |
| `web/test/unit/browser-agent-control.test.ts` | 신규 |
| `docs/features/agent-browser.md` | 수정 |
| `docs/features/user-browser.md` | 수정 |
| `docs/code-architecture.md` | 수정 |
| `docs/features/agent-browser/tasks/pr1-tabs/` | 삭제 |
