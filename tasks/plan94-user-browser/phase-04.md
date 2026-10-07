# Phase 04. 「내 브라우저」 화면과 관리자 목록

**Execution profile**: standard

## 목표

사용자가 웹에서 자기 브라우저를 만들고, 켜고, 끄고, 지운다. 관리자는 모든 브라우저의 상태를 보고 끄거나 지운다.

**범위 외**: 원격 화면과 로그인(단계 2). 화면에 원격 화면 자리를 미리 두지 않는다.

## 컨텍스트

**근거 문서**: `docs/adr/ADR-20261007-user-browser.md`, `docs/backend/user-browser.md`

- 계약: `docs/backend/user-browser.md` 의 「API」. phase 03 의 응답 모양을 그대로 쓴다
- 규칙: `web/AGENTS.md`(해요체, 테마 토큰, `Notice`, `Badge`, 관리자 표시는 `/admin` 아래만), `docs/frontend/structure.md` 의 「관리자 영역」
- 서버 라우트 본보기: `web/src/app/api/follow-ups/` 와 `web/src/lib/control-plane.ts` 의 `requestControlPlane`, `forwardControlPlane`
- 화면 본보기: `web/src/app/connections/page.tsx`, 관리자 화면은 `web/src/app/admin/connections/`
- 브라우저 검사 본보기: `test/browser/connector-connection.spec.ts` 와 `test/browser/fixtures.ts`

## 의도 메모

- 주소는 `/browser`. 상단 내비게이션에는 넣지 않고 `/connections` 화면 위쪽에 「내 브라우저」 로 가는 링크를 둔다. 브라우저가 필요한 것은 연결이기 때문이다
- 기능이 꺼져 있으면(`enabled: false`) 「아직 준비 중이에요.」 안내만 그린다
- 상태 문구: `STOPPED` 「꺼져 있어요」, `STARTING` 「켜는 중이에요」, `RUNNING` 「켜져 있어요」, `STOPPING` 「끄는 중이에요」, `FAILED` 「켜지 못했어요」. 오류 코드는 일반 화면에 그리지 않는다
- 지우기는 확인 창을 거친다. 제목 「브라우저를 지울까요?」, 본문 「로그인한 사이트에서 모두 로그아웃돼요.」
- `STARTING` 과 `STOPPING` 동안은 2초마다 상태를 다시 읽는다
- 자동 중지 안내: 「쓰지 않으면 N분 뒤 저절로 꺼져요.」(`idleTimeoutSeconds`)
- 관리자 목록 `/admin/browsers` 는 사용자 이름, 상태, 마지막 사용 시각, 오류 코드, 끄기와 지우기 단추. 관리자 내비게이션에 더한다

## 작업 항목

### 1. 서버 라우트

`web/src/app/api/browser/route.ts`(GET, POST, DELETE), `web/src/app/api/browser/start/route.ts`, `web/src/app/api/browser/stop/route.ts`,
`web/src/app/api/admin/browsers/route.ts`, `web/src/app/api/admin/browsers/[id]/stop/route.ts`, `web/src/app/api/admin/browsers/[id]/route.ts`.

### 2. 화면

`web/src/app/browser/page.tsx` 와 `web/src/components/browser/user-browser-panel.tsx`, `web/src/app/admin/browsers/page.tsx` 와 `web/src/components/browser/admin-browser-list.tsx`.
`/connections` 화면에 「내 브라우저」 링크, 관리자 내비게이션에 「브라우저」 항목.

### 3. 문서

`docs/frontend/structure.md` 에 두 화면을 더한다. 화면 문구 표에 새 용어가 생기면 `web/AGENTS.md` 의 「화면에서 쓰는 말」 에 더한다.

### 4. 테스트

`test/browser/user-browser.spec.ts`: 가짜 Control Plane 응답으로
없음 → 만들기 → 꺼짐 → 켜기(켜는 중을 거쳐 켜짐) → 끄기 → 지우기 확인 창, 기능 꺼짐 안내, 켜기 실패 문구, 관리자 목록의 끄기.
가짜 Control Plane 이 경로를 모르면 그 fixture 에 더한다.

## 검증

```bash
cd web && pnpm lint && pnpm typecheck && pnpm format:check
/Users/nhn/personal/fos-assistant/.omc/scripts/wait-browser.sh && scripts/check-local.sh user-browser
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `web/src/app/api/browser/route.ts` | 신규 |
| `web/src/app/api/browser/start/route.ts` | 신규 |
| `web/src/app/api/browser/stop/route.ts` | 신규 |
| `web/src/app/api/admin/browsers/route.ts` | 신규 |
| `web/src/app/api/admin/browsers/[id]/route.ts` | 신규 |
| `web/src/app/api/admin/browsers/[id]/stop/route.ts` | 신규 |
| `web/src/app/browser/page.tsx` | 신규 |
| `web/src/app/admin/browsers/page.tsx` | 신규 |
| `web/src/components/browser/user-browser-panel.tsx` | 신규 |
| `web/src/components/browser/admin-browser-list.tsx` | 신규 |
| `web/src/app/connections/page.tsx` | 수정 |
| `web/src/app/admin/layout.tsx` | 수정 |
| `test/browser/user-browser.spec.ts` | 신규 |
| `test/browser/fixtures.ts` | 수정 |
| `docs/frontend/structure.md` | 수정 |
