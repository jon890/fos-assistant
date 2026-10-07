# Phase 01. 로그인 화면의 웹

**Execution profile**: deep

## 목표

사용자가 「내 브라우저」 화면에서 자기 브라우저의 지금 탭을 보고, PC 와 휴대폰에서 누르고 끌고 글자(한글 포함)를 넣어 직접 로그인한다.

**범위 외**: backend(이미 머지된 단계 2a), 커넥터가 시작 주소를 주는 일(단계 3).

## 컨텍스트

**근거 문서**: `docs/adr/ADR-20261007-user-browser.md`, `docs/backend/user-browser.md` 의 「로그인 화면」 「API」

- backend 경로는 `GET /api/v1/browser/screen?url=` (SSE: `frame {data, width, height}`, `tabs [{id, title, url, active}]`, `closed {reason}`; 주석 `ping`), `POST /api/v1/browser/screen/input`(입력 표는 계약 문서). 오류는 JSON `{code, message}`
- 웹 규칙: `web/AGENTS.md`(해요체, 테마 토큰, 인라인 style 금지와 예외 주석, `Notice`, `Badge`, 브라우저가 Control Plane 을 직접 부르지 않음, 서버 라우트에서 fetch). 화면에서 fetch 는 `web/src/lib/browser-api.ts` 를 거친다
- SSE 서버 라우트 본보기: `web/src/app/api/notifications/events/route.ts`. 화면 쪽 SSE 읽기 본보기는 알림 SSE 를 읽는 훅을 찾아 따른다
- 단계 1 화면: `web/src/app/browser/page.tsx`, `web/src/components/browser/user-browser-panel.tsx`
- 브라우저 검사 본보기: `test/browser/user-browser.spec.ts`(`page.route` 로 웹 서버 라우트를 대신 답한다). 프로젝트는 desktop 과 mobile 둘이다

## 의도 메모

- 웹은 WebSocket 을 중계하지 못해 프레임은 SSE, 입력은 POST 다
- 프레임은 `<img src="data:image/jpeg;base64,...">` 로 그린다. 크기는 화면 폭에 맞춘다. 이미지 크기를 바꾸는 값이 이어지는 수라 인라인 style 이 필요하면 까닭을 주석으로
- 좌표는 그림 안의 비율(0~1)로 보낸다. 서버가 CSS 픽셀로 바꾼다
- 포인터 이벤트 하나로 마우스와 터치를 다룬다. 마우스: 누름 `down`, 뗌 `up`, 누른 채 움직임 `move`(초당 20번까지). 휠은 `wheel`. 터치: 움직임 없이 떼면 `down`+`up`(누르기), 끌면 `wheel` 로 바꿔 끈 만큼 반대로 굴린다(세로 끌기 = 스크롤). 화면의 기본 스크롤과 확대를 그림 위에서 막는다(`touch-action: none`)
- 글자: 숨긴 `<input>` 에 초점을 둔다(그림을 누르면 초점, 휴대폰은 「키보드」 단추). 조합 중(`isComposing`)이면 보내지 않고 `compositionend` 에서 조합을 마친 글자를 `text` 로 보낸 뒤 입력칸을 비운다. 조합이 아닌 `input` 도 `text` 로. 특수 키(Enter, Backspace, Tab, Escape, 방향 키, Delete)는 `keydown` 에서 `key` 로. 휴대폰의 Backspace 는 `beforeinput` 의 `deleteContentBackward` 로 잡는다
- 도구 줄: 주소 칸(http, https 만 보내고 아니면 안내), 이동, 뒤로, 새로고침, 탭 고르기(`tabs` 사건), 닫기
- 열 때와 창 크기가 바뀔 때 `resize`(폭은 그림 칸의 CSS 폭, 높이는 폭의 1.5배, 계약의 범위로 자름)
- 시작 주소는 페이지 주소의 `?url=` 로 받아 SSE 에 넘긴다(단계 3 의 커넥터가 쓴다). http, https 가 아니면 무시한다
- `closed` 문구: `replaced` 「다른 창에서 화면을 열었어요.」, `stopped` 「브라우저가 꺼졌어요.」, `timeout` 「오래 쓰지 않아 화면을 닫았어요.」. 다시 열기 단추를 둔다
- 입력이 409 `BROWSER_SCREEN_CLOSED` 면 화면을 닫힌 것으로 그린다. 그 문구를 `web/src/components/error-message.ts` 에 더한다
- 화면 문구는 해요체. 오류 코드는 그리지 않는다

## 작업 항목

### 1. 서버 라우트

`web/src/app/api/browser/screen/route.ts`(GET, SSE 를 그대로 흘림. `url` 쿼리만 넘김), `web/src/app/api/browser/screen/input/route.ts`(POST, 본문 8KB 를 넘으면 400, 그대로 넘김).

### 2. 화면

- `web/src/components/browser/browser-screen.tsx`: SSE 읽기, 프레임 그리기, 포인터와 휠, 숨긴 입력칸, 도구 줄, 닫힘 안내
- `web/src/components/browser/screen-input.ts`: 포인터와 키 이벤트를 입력 본문으로 바꾸는 순수 함수(좌표 비율, 끌기를 휠로, 특수 키 표). 단위 시험 대상
- `web/src/lib/browser-api.ts`: `sendScreenInput(body)`
- `user-browser-panel.tsx`: 켜져 있거나 꺼져 있을 때 「화면 열기」 단추. 누르면 화면을 펼친다(켜기는 SSE 가 한다)
- `web/src/app/browser/page.tsx`: `?url=` 를 패널에 넘긴다

### 3. 문서

`docs/frontend/structure.md` 의 `/browser` 줄에 로그인 화면을, `docs/backend/user-browser.md` 「로그인 화면」 에 웹이 터치를 마우스와 휠로 바꾼다는 것과 한글 입력 방식을 적는다(이미 있으면 맞춘다).

### 4. 시험

- `test/unit/browser-screen-input.test.ts`: 좌표 비율(가장자리와 밖은 0~1 로 자름), 끌기 → 휠 부호와 크기, 짧은 탭 → 누르기, 특수 키 표, 모르는 키는 보내지 않음
- `test/browser/user-browser-screen.spec.ts`: `page.route` 로 SSE(프레임 하나와 탭 둘, 그 뒤 `closed`)와 입력 POST 를 대신 답해, 그림이 보이고, 누르면 `down`/`up` 이 비율 좌표로 가고, 글자를 넣으면 `text` 가 가고(Playwright `keyboard.insertText`), Enter 가 `key` 로 가고, 탭을 고르면 `tab` 이 가고, `closed` 문구가 보이는지. mobile 프로젝트에서는 탭이 누르기로, 세로 끌기가 `wheel` 로 가는지

## 검증

브라우저 검사는 `.omc/scripts/wait-browser.sh` 뒤 `.omc/scripts/heavy-lock` 으로 감싸 돌린다(저장소 밖 도구라 아래에는 명령만 적는다).

```bash
cd web && pnpm lint && pnpm typecheck && pnpm format:check
node --test test/unit/browser-screen-input.test.ts
cd web && pnpm test:browser user-browser.spec.ts user-browser-screen.spec.ts
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `web/src/app/api/browser/screen/route.ts` | 신규 |
| `web/src/app/api/browser/screen/input/route.ts` | 신규 |
| `web/src/components/browser/browser-screen.tsx` | 신규 |
| `web/src/components/browser/screen-input.ts` | 신규 |
| `web/src/components/browser/user-browser-panel.tsx` | 수정 |
| `web/src/app/browser/page.tsx` | 수정 |
| `web/src/lib/browser-api.ts` | 수정 |
| `web/src/components/error-message.ts` | 수정 |
| `test/unit/browser-screen-input.test.ts` | 신규 |
| `test/browser/user-browser-screen.spec.ts` | 신규 |
| `docs/frontend/structure.md` | 수정 |
| `docs/backend/user-browser.md` | 수정 |
