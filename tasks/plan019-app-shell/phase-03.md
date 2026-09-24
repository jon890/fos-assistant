# Phase 03. 대화 이름 바꾸기와 지우기, 검색, 접기, 단축키

**Execution profile**: standard

## 목표

사이드바의 대화 한 줄에서 이름을 바꾸고 지운다. 제목으로 대화를 찾고, 사이드바를 접고, 단축키로 움직인다.

**범위 외**:
`Esc` 로 답을 중지하는 것은 이 plan 의 일이 아니다. 중지 경로가 아직 없다.
대화 본문 검색은 하지 않는다. 제목만 브라우저에서 거른다.

## 컨텍스트

phase-01 이 Control Plane 에 `PATCH` 와 `DELETE /api/v1/chat/conversations/{id}` 를 만들었고,
phase-02 가 `components/shell/` 에 사이드바와 목록 context(`useConversations`)를 만들었다.
접근성 이름은 phase-02 의 작업 항목 3 표를 따른다.

**근거 문서**:
`docs/flow.md` 의 「대화 목록」 절(이름 바꾸기와 지우기 흐름도)과 「화면 틀」 절의 너비 표와 단축키 표,
`docs/code-architecture.md` 의 「대화」 절 아래 「경로」 표.

브라우저는 Control Plane 을 직접 부르지 않는다. 서버 라우트를 하나 둔다(`web/AGENTS.md`).
같은 모양의 선례가 `web/src/app/api/memories/[id]/route.ts` 다. `PATCH` 와 `DELETE` 를 둘 다 가진다.

## 의도 메모

- 지운 뒤 되돌리기 알림을 두지 않는다. 되돌리는 경로가 서버에 없고, 확인 창 하나로 충분하다
- 검색을 서버에 묻지 않는다. 목록 전체를 이미 받아 두었다
- `Ctrl` 과 `⌘` 을 둘 다 받는다. `event.ctrlKey || event.metaKey`
- 입력칸에서 조합 중(`event.isComposing`)이면 단축키를 무시한다. 한글 입력이 끊긴다
- 접은 상태를 `localStorage` 에 둔다. 읽고 쓰는 것을 `try` 로 감싼다. 막힌 브라우저에서도 화면이 그려져야 한다

## 작업 항목

### 1. `web/src/app/api/chat/conversations/[conversationId]/route.ts` 신규

`PATCH` 는 본문을 그대로 `/api/v1/chat/conversations/{id}` 에 `PATCH` 로 넘기고,
`DELETE` 는 같은 경로에 `DELETE` 로 넘긴다. 번호 검사와 응답 변환은 `memories/[id]/route.ts` 의
`idOf`, `invalid`, `response` 모양을 따른다. 204 는 본문 없이 돌려준다.

### 2. `conversations-provider.tsx` 에 둘을 더한다

```ts
rename(id: number, title: string): Promise<void>;
remove(id: number): Promise<void>;
```

`rename` 은 성공하면 받은 한 줄로 목록의 그 줄을 바꾼다. 실패하면 `Error` 를 던져 부르는 쪽이 원래 이름으로 되돌린다.
`remove` 는 성공하면 목록에서 뺀다. 실패하면 던진다. 먼저 빼고 실패하면 되돌리는 방식을 쓰지 않는다.

### 3. `conversation-nav.tsx` 의 한 줄 메뉴

| 요소 | 역할과 이름 |
| --- | --- |
| 메뉴 단추 | 단추 `{제목} 메뉴`. 넓은 화면에서는 마우스를 올리거나 초점이 갈 때, 좁은 화면에서는 늘 보인다 |
| 메뉴 항목 | `menuitem` `이름 바꾸기`, `지우기` |
| 이름 입력칸 | `textbox` `대화 이름`. 그 줄 자리에 뜬다 |
| 확인 창 | `dialog`, 이름 `대화 지우기`. 본문 「{제목} 를 목록에서 지운다. 사용량 기록은 남는다.」, 단추 `지우기`, `취소` |

- 이름 입력칸: `Enter` 나 바깥을 누르면 저장, `Esc` 면 되돌린다. 공백만 남으면 저장하지 않고 되돌린다.
  `Esc` 를 처리하면 `event.preventDefault()` 와 `event.stopPropagation()` 을 한다. 대화 화면의 `Esc` 처리기가 그것을 받지 않게 한다
- 지우기 성공: 지금 주소가 그 대화의 `/c/{id}` 면 `router.push("/")`
- 실패하면 사이드바 목록 위에 오류 한 줄. `describeError` 를 쓴다
- 확인 창은 `Esc` 와 `취소` 로 닫히고 초점이 메뉴 단추로 돌아온다. `Esc` 로 닫을 때 같은 방식으로 전파를 막는다.
  네이티브 `<dialog>` 의 `cancel` 사건을 쓰면 그 사건에서 `preventDefault` 하고 직접 닫는다

### 4. 제목 검색

사이드바의 새 대화 아래에 `searchbox` `대화 검색`. 입력하면 `title` 에 그 글자가 들어간 것만 남긴다.
대소문자를 가리지 않는다. 맞는 것이 없으면 「맞는 대화가 없다」. 거르는 동안에도 날짜 묶음을 지킨다.

### 5. 사이드바 접기

- `md` 이상에서 사이드바 위쪽에 단추 `사이드바 접기`. 접으면 사이드바가 사라지고 대화 쪽 위에 `사이드바 펴기` 와 `새 대화` 가 남는다
- 상태는 `localStorage` 의 `sidebar-collapsed` 에 `"1"` 이나 `"0"` 으로 둔다. 처음 그릴 때 읽는다
- `md` 미만에서는 이 값을 쓰지 않는다. 서랍은 늘 닫힌 채로 시작한다

### 6. 단축키

`web/src/components/shell/use-shortcuts.ts` 신규. `AppShell` 이 한 번 붙인다.

| 키 | 동작 |
| --- | --- |
| `Ctrl` 또는 `⌘` + `Shift` + `O` | `router.push("/")` |
| `Ctrl` 또는 `⌘` + `Shift` + `S` | `md` 이상이면 접기와 펴기, 미만이면 서랍 열고 닫기 |
| `Ctrl` 또는 `⌘` + `K` | `대화 검색` 에 초점. 사이드바가 접혔거나 서랍이 닫혀 있으면 먼저 연다 |

셋 다 `event.preventDefault()` 한다. `/signin` 에서는 붙이지 않는다.
**`Esc` 는 여기 넣지 않는다.** `Esc` 는 대화 화면의 처리기 하나가 해석한다(phase-02 작업 항목 7).
`router.push("/")` 뒤 대화 화면이 비는 것은 phase-02 의 `pathname` 규칙이 한다. 여기서 따로 비우지 않는다.

### 7. 문서 한 줄

`docs/code-architecture.md` 의 「아직 만들지 않은 것」 에서
「사이드바 화면 틀과 `/c/{id}` 주소, 대화 이름 바꾸기와 지우기」 줄을 지운다.
같은 목록의 다른 줄은 남긴다.

### 8. 이 phase 를 검증하는 브라우저 테스트

`test/browser/shell.spec.ts` 에 더한다. `mobile` 에서는 먼저 `사이드바 열기` 를 누른다.

- 이름 바꾸기: 메뉴 → `이름 바꾸기` → 새 이름 입력 → `Enter`. 목록의 링크 이름이 바뀌고 새로 고쳐도 남는다
- 이름 바꾸기 취소: `Esc` 면 원래 이름이다
- 지우기: 연 대화를 지우면 주소가 `/` 가 되고 목록에서 사라진다. 그 대화의 `/c/{id}` 로 가면 `conversation-not-found`
- 지우기 실패: `page.route` 로 `DELETE` 에 500 을 주면 목록이 그대로이고 오류 한 줄이 보인다
- 검색: 두 대화 가운데 한 제목의 일부를 넣으면 그 링크만 남는다
- 접기: `desktop` 에서 `사이드바 접기` 뒤 새로 고쳐도 접힌 채다. `사이드바 펴기` 로 되돌린다
- 단축키: `Control+Shift+O` 로 `/` 에 간다. `Control+K` 로 `대화 검색` 에 초점이 간다
- `/` 에서 보내 주소가 `/c/{숫자}` 가 된 뒤 `Control+Shift+O` 를 누르면 메시지 영역과 입력창이 빈다
- **이름 입력칸의 `Esc` 가 다른 것을 하지 않는다.** 서랍(`mobile`)을 연 채 이름 바꾸기를 열고 `Esc` 를 누르면
  이름이 되돌아가고 서랍은 열린 채이며 주소도 그대로다. 확인 창의 `Esc` 도 같다

## 검증

```bash
# cwd: web/
pnpm typecheck
AUTH_SECRET=build-time-placeholder \
ASSISTANT_JWT_SECRET=build-time-placeholder \
CONTROL_PLANE_BASE_URL=http://build-time-placeholder \
AUTH_GOOGLE_ID=build-time-placeholder \
AUTH_GOOGLE_SECRET=build-time-placeholder \
pnpm build
pnpm test:browser
```

```bash
# cwd: 저장소 root
cd backend && ./gradlew test
node test/e2e/run.ts
scripts/check-public-safe.sh
grep -rn 'style={{' web/src/
```

앞의 명령은 모두 종료 코드 0 이어야 하고, 마지막 `grep` 은 `web/AGENTS.md` 대로 값이 이어지는 수라서 클래스로 만들 수 없고 그 이유를 주석으로 남긴 예외 말고는 아무것도 내지 않아야 한다.
이 plan 을 머지하기 전 마지막 확인이므로 저장소 root `AGENTS.md` 의 「확인」 순서대로 모두 돌린다.

## Critical Files

| 파일 | 변경 |
|---|---|
| `web/src/app/api/chat/conversations/[conversationId]/route.ts` | 신규 |
| `web/src/components/shell/conversations-provider.tsx` | 수정 |
| `web/src/components/shell/conversation-nav.tsx` | 수정 |
| `web/src/components/shell/sidebar.tsx` | 수정 |
| `web/src/components/shell/app-shell.tsx` | 수정 |
| `web/src/components/shell/use-shortcuts.ts` | 신규 |
| `docs/code-architecture.md` | 수정 |
| `test/browser/shell.spec.ts` | 수정 |

끝나면 `tasks/plan019-app-shell/index.json` 의 이 phase 를 `completed` 로, plan 의 `status` 를 `completed` 로 바꾼다.
