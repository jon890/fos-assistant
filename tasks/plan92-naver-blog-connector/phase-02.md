# Phase 02. 네이버 편집기 조작 라이브러리

**Execution profile**: deep

## 목표

CDP 로 네이버 글쓰기 화면을 열어 초안을 넣고 임시저장하는 단계 함수들을 `hermes/connectors/naver-blog/src/editor/` 에 만든다.
phase 03 의 작업 프로세스가 이 함수들을 차례로 부른다. 이 phase 는 도구를 더하지 않는다.

**범위 외**: 작업 파일, 잠금, 작업 프로세스, `save_draft` 와 `draft_job` 도구(phase 03). 발행.

## 컨텍스트

- 기존 Python 구현을 TypeScript 로 옮긴다. **원본은 이 저장소 밖의 개인 저장소에 있고, 구현 지시문이 그 경로를 준다.** 원본 모듈은 편집기 CLI, 공통 조작(core), 글자(text), 사진(photos), 스티커와 지도(components), 발행 설정과 저장(settings), CDP 창구 일곱이다. 셀렉터, 기다리는 시간, 화면 대조 규칙은 원본을 그대로 따른다
- 원본의 사람 이름, 블로그 아이디, 구매한 스티커 묶음의 코드 표, 카테고리 이름 같은 개인 값은 옮기지 않는다. 원본 주석에 든 홈서버 접속 방법, 포트, 중계와 경로 설명도 옮기지 않는다. 블로그 아이디는 `NAVER_BLOG_ID` env 에서, 스티커 코드는 초안에서 받는다
- 원본과 다른 점은 셋이다
  1. 사진은 경로가 아니라 바이트로 넣는다(아래 작업 항목 4)
  2. 초안은 `draft.json` 대신 phase 01 의 `src/draft.ts` 가 만든 `Block[]` 이다. 원본의 `lines` 묶음 대신 본문 한 줄이 한 문단이다
  3. 단계 사이의 진행 표시는 원본처럼 그 탭의 `sessionStorage` 에 두되, 한 프로세스가 `open` 부터 `close` 까지 한 연결로 돈다
- CDP 창구는 phase 01 의 `src/cdp.ts` 의 `CdpSession` 과 `httpJson` 을 쓴다
- 원본이 실측으로 걸린 것들은 공개 문서에 적힌 그대로 지킨다. `docs/connectors/naver-blog.md` 의 「작업」 표와 아래 「의도 메모」 가 그것이다

**근거 문서**: `docs/connectors/naver-blog.md` 의 「작업」, 「서버와 검사」, 「보안」. `docs/adr/ADR-092-네이버-블로그-커넥터는-사용자의-chrome-에-cdp-로-붙고-임시저장은-승인한-뒤-백그라운드-작업으로-돈다.md` 의 「감당할 것」.

## 의도 메모

실측으로 걸렸던 편집기의 버릇이다. 원본 코드가 각각을 처리한다. 빠뜨리지 않는다.

- 탭에 붙자마자 `Page.enable` 을 켜고 `Page.javascriptDialogOpening` 을 받으면 바로 `Page.handleJavaScriptDialog({accept: true})` 로 닫는다. confirm 이나 alert 가 떠 있으면 모든 명령이 시간 초과로 끝난다
- 원본의 headless user agent 바꾸기는 Chrome 을 띄우는 쪽의 일이라 옮기지 않는다. 커넥터 문서의 설정 안내가 갖는다

- 글자는 `Runtime.evaluate` 의 `execCommand` 로 넣지 못한다. `Input.insertText` 로 넣는다
- 글자를 넣자마자 Enter 를 누르면 다음 줄이 앞 줄 자리에 들어가 앞 줄이 사라진다. 글자가 보인 뒤 0.15초 기다린다
- 글자와 이모지를 한 번에 넣으면 앞 글자가 사라진다. 나눠 넣는다
- 화면을 막 열면 편집기가 초점을 본문으로 옮긴다. 제목을 누른 뒤 커서가 제목 안인지 보고 넣는다
- 「작성 중인 글이 있습니다」 알림이 화면 위에 떠 클릭이 편집기에 닿지 않는다. 「취소」 로 닫는다. 다른 알림이 남아 있으면 멈춘다
- JS 의 `.click()` 은 사용자 활성화로 인정되지 않는다. 사진 단추와 저장 단추는 `Input.dispatchMouseEvent` 로 누른다
- 사진 단추를 누를 때 생기는 `input[type=file]` 은 선택 창이 닫히면 사라진다. `Page.setInterceptFileChooserDialog` 를 켜고 `Page.fileChooserOpened` 의 `backendNodeId` 로 같은 연결에서 바로 넣는다
- 사진이 둘 이상이면 「사진 첨부 방식」 창이 뜬다. `#image-type-list`(개별사진)를 고른다
- 임시저장 수는 저장 단추 옆 단추의 `aria-label` 에 있다
- 발행 설정은 상단의 `button[data-click-area="tpb.publish"]` 를 다시 눌러 닫는다. 설정 안의 발행 확인 단추 `tpb*i.publish` 는 누르지 않는다
- 장소 검색은 상호명으로 먼저, 상호명과 주소가 함께 맞는 결과가 없으면 주소로 다시 찾는다. 둘 다 맞는 결과가 정확히 하나일 때만 고르고, 숨은 추가 단추가 아닌 그 결과의 보이는 추가 단추만 누른다
- 큰 가상 창에서는 아래 문단에 보낸 마우스 이벤트가 닿지 않는다. 탭마다 `Emulation.setDeviceMetricsOverride` 로 1280×720 을 둔다

기각한 것: 파일 경로를 Chrome 쪽 경로로 바꿔 `DOM.setFileInputFiles` 에 넘기는 것. 두 경로의 대응을 운영 값으로 받아야 해 범용 커넥터에 둘 수 없다.

## Blocked 조건

- 구현 지시문이 원본 경로를 주지 않았거나 그 경로에 원본 모듈이 없다 → `PHASE_BLOCKED: 편집기 원본 없음` 출력 후 종료

## 작업 항목

### 1. `src/editor/page.ts`

탭 하나에 붙은 `EditorPage`. 붙을 때 `Page.enable` 과 대화 상자 자동 수락을 건다. 기다리는 시간의 상한(`stepSeconds`, `photoSeconds`, `saveSeconds`, `openSeconds`)은 생성자 옵션으로 받고 기본값은 원본 값이다. 시험은 짧은 값을 넘긴다. `CdpSession` 을 감싸 `js(expression)`(값을 돌려받는 `Runtime.evaluate`, 예외는 `EditorError("editor_failed", ...)`), `mouseClick(finderExpression)`, `insertText`, `press(key)`, `waitUntil(check, seconds)`, `sleep` 을 준다.
`openWriteTab(cdpUrl, blogId)`: `/json/new?` 로 `https://blog.naver.com/PostWriteForm.naver?blogId=<blogId>` 하나만 연다. `blogId` 는 `^[A-Za-z0-9_-]{1,50}$` 가 아니면 열지 않는다. 연 탭의 `id` 를 돌려주고 그 탭의 `webSocketDebuggerUrl` 에 붙는다.
`closeTab(cdpUrl, targetId)`: 그 탭 하나만 닫는다. 다른 탭은 목록에서 읽기만 하고 닫지 않는다.

`EditorError(code, stage, message, extra?)`. `code` 는 `login_required`, `category_not_found`, `place_not_unique`, `photo_upload_failed`, `editor_failed`, `save_unconfirmed` 가운데 하나다. `message` 에 경로와 CDP 주소를 넣지 않는다.

### 2. `src/editor/open.ts`

원본의 `open` 과 같다. 40초 안에 문서 제목에 「네이버 블로그」 가 **들어 있고**(실제 제목은 「<블로그 이름> : 네이버 블로그」) 제목 입력란이 생기기를 기다린다. 「작성 중인 글이 있습니다 … 이어서 작성하시겠습니까」 알림은 「취소」 로 닫고 남은 알림이 있으면 `editor_failed`. 제목에 「NAVER 로그인」 이 들어 있거나 주소의 호스트가 `nid.naver.com` 이면 `login_required` 다. 캡차와 기기 인증도 그 호스트 아래에 뜨므로 따로 나누지 않는다.

### 3. `src/editor/text.ts`

원본의 `fill`. 제목을 넣고 본문 블록을 차례로 넣는다. 사진, 스티커, 지도 자리에는 원본과 같은 모양의 자리표시 글(`[사진 자리: <파일 이름>]`, `[스티커 자리: <코드>]`, `[장소 자리: <상호명> | <주소>]`)을 한 문단으로 둔다. 넣은 뒤 화면의 문단을 읽어 기대한 줄과 대조하고 어긋나면 `editor_failed`.

### 4. `src/editor/photos.ts`

원본의 `photos` 를 옮기되 파일을 바이트로 넣는다. 사진 자리마다:

1. 원본의 `focus_placeholder` 처럼 자리표시 글을 먼저 지우고 그 빈 문단에 초점을 둔다
2. `DOM.enable`, `Runtime.enable`, `Page.setInterceptFileChooserDialog({enabled: true})`
3. 사진 단추를 마우스 이벤트로 누르고 `Page.fileChooserOpened` 를 30초 기다린다
4. `DOM.resolveNode({backendNodeId})` 로 `objectId` 를 얻는다
5. `Runtime.callFunctionOn({objectId, functionDeclaration, arguments: [{value: base64}, {value: 파일 이름}, {value: mime}]})`. 함수는 base64 를 `Uint8Array` 로 풀어 `new File([...], 이름, {type: mime})` 을 만들고 `DataTransfer` 에 담아 `this.files = dt.files` 로 넣은 뒤 `input` 과 `change` 이벤트(`bubbles: true`)를 보낸다
6. 「사진 첨부 방식」 창이 뜨면 개별사진을 고른다
7. 원본처럼 전송 완료를 기다리고 `문서 너비` 를 적용한다

바이트는 phase 01 의 `readPhoto` 로 읽는다. 실패하면 `photo_upload_failed` 와 `extra.photo`(몇 번째 사진 자리인지)다.
`Page.setInterceptFileChooserDialog` 는 끝나면 `enabled: false` 로 되돌린다.

### 5. `src/editor/components.ts`

원본의 `components`. 스티커는 스티커 창의 보이는 요소 가운데 글자가 코드와 같은 단추를 누른다. 원본의 「알려진 코드 표」 검사는 옮기지 않고 초안의 코드를 그대로 찾는다. 찾지 못하면 `editor_failed`.
지도는 의도 메모의 규칙대로 고른다. 정확히 하나가 아니면 `place_not_unique` 와 `extra.place: {name, address}`, `extra.candidates: [{name, address}]`(10개까지).

### 6. `src/editor/settings.ts`

원본의 `settings`, `save`, `state`. 카테고리 목록에서 이름이 같은 것을 고르고 없으면 `category_not_found` 와 `extra.categories`(이름 목록, 50개까지). 태그는 입력란에서 Enter 로 칩을 만든다. 설정은 연 단추를 다시 눌러 닫는다.
`save(page, onStage)`: 화면의 제목, 사진 수와 `문서 너비`, 스티커 수, 지도 수, 카테고리, 태그가 초안과 같은지 본 뒤 저장 단추만 마우스로 누르고, 누른 직후 `onStage("save_clicked")` 를 부른 뒤 저장 수가 늘기를 기다린다. 누른 뒤에 난 예외는 모두 `save_unconfirmed` 로 바꿔 던진다.
`save` 는 `{savedBefore, savedAfter}` 를 돌려준다. 누른 뒤 늘지 않았으면 `EditorError("save_unconfirmed", "save", ...)` 를 던진다. 이 코드는 phase 03 이 `unknown` 으로 바꾼다. 누르기 전 대조에서 어긋나면 `editor_failed` 다.
`state`: 결과에 담을 `{title, photos, fitted_photos, stickers, maps, category, tags, saved_count}`.

### 7. `src/editor/run.ts`

`runDraft(env, blocks, input, onStage, signal: AbortSignal)`: `open` → `fill` → `photos` → `components` → `settings` → `save` → `state` 를 차례로 부르고 단계가 바뀔 때마다 `onStage(stage)` 를 부른다. 단계 사이와 기다리는 동안 `signal` 을 보고, 중단되면 `EditorError("editor_failed", 지금 단계, "aborted")` 를 던진다(phase 03 이 `timeout` 이나 `unknown` 으로 바꾼다). 성공이든 실패든 중단이든 `finally` 에서 연 탭 하나를 닫는다. 결과는 `state` 와 `{savedBefore, savedAfter}` 다.
**`tpb*i.publish` 를 누르는 식은 이 디렉터리 어디에도 두지 않는다.** 그 문자열은 「누르지 않는다」 는 주석과 검사에만 있다.

### 8. 이 phase 를 검증하는 시험

phase 01 의 `tests/fake-cdp.ts` 에 탭 WebSocket 의 기본 응답을 더한다. 시험마다 `Runtime.evaluate` 의 식을 조각으로 맞춰 화면 상태를 흉내 내는 처리기를 준다.
허용 메서드는 `Target`·`Page`·`Runtime`·`DOM`·`Input`·`Emulation`·`Storage` 가운데 이 phase 가 실제로 쓰는 것만 적는다. `Page.enable` 과 `Page.handleJavaScriptDialog` 가 들어간다. 시험마다 `EditorPage` 의 기다리는 시간을 1초 안쪽으로 넘겨 bun test 의 기본 제한 5초 안에 끝낸다.

- `tests/editor-photos.test.ts`: 사진 자리 하나에서 `Page.fileChooserOpened` 를 보내면 `DOM.resolveNode` 가 그 `backendNodeId` 로 오고, `Runtime.callFunctionOn` 의 첫 인자가 시험 파일의 base64 와 같다. `Page.setInterceptFileChooserDialog` 가 켜졌다가 꺼진다. 선택 창 이벤트가 오지 않으면 `photo_upload_failed` 와 `photo: 1`. `DOM.setFileInputFiles` 는 한 번도 오지 않는다
- `tests/editor-run.test.ts`: 모든 단계가 기대한 화면을 돌려주는 대역으로 `runDraft` 가 단계를 차례로 알리고 저장 수 3→4 를 돌려준다. 연 주소는 `https://blog.naver.com/PostWriteForm.naver?blogId=example-blog` 하나뿐이다. 받은 `Runtime.evaluate` 와 `Runtime.callFunctionOn` 의 어떤 식에도 `tpb*i.publish` 가 없다. 로그인 화면 대역이면 `login_required` 이고 탭이 닫힌다. 카테고리가 없는 대역이면 `category_not_found` 와 이름 목록. 장소 후보가 둘이면 `place_not_unique` 와 후보 둘. 저장 수가 늘지 않으면 `save_unconfirmed` 이고 그 전에 `save_clicked` 가 알려진다. 이미 중단된 `signal` 이면 탭을 닫고 끝난다. 열린 alert 이벤트를 보내면 `Page.handleJavaScriptDialog` 가 온다. 모든 경우에 `/json/close/<연 탭>` 이 한 번 오고 다른 탭은 닫히지 않는다
- `tests/editor-text.test.ts`: 이모지가 든 줄을 글자와 이모지로 나눠 `Input.insertText` 를 두 번 부르고, 줄 사이에 Enter 를 누른다

## 검증

```bash
cd hermes/connectors/naver-blog && bun install --frozen-lockfile && bun run typecheck && bun test ./src ./tests ./scripts && bun run build && bun run check:bundle
bash scripts/check-connectors.sh
! grep -rn "tpb\*i.publish" hermes/connectors/naver-blog/src | grep -v "누르지 않는다"
git add -N hermes/connectors/naver-blog && bash scripts/check-public-safe.sh
```

기대값: 모두 종료 코드 0. 셋째 줄은 발행 확인 단추 이름이 「누르지 않는다」 주석 밖의 소스에 없어서 0 이다. `check-public-safe.sh` 는 추적 파일만 보므로 먼저 `git add -N` 한다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `hermes/connectors/naver-blog/src/editor/page.ts` | 신규 |
| `hermes/connectors/naver-blog/src/editor/open.ts` | 신규 |
| `hermes/connectors/naver-blog/src/editor/text.ts` | 신규 |
| `hermes/connectors/naver-blog/src/editor/photos.ts` | 신규 |
| `hermes/connectors/naver-blog/src/editor/components.ts` | 신규 |
| `hermes/connectors/naver-blog/src/editor/settings.ts` | 신규 |
| `hermes/connectors/naver-blog/src/editor/run.ts` | 신규 |
| `hermes/connectors/naver-blog/src/cdp.ts` | 수정 |
| `hermes/connectors/naver-blog/tests/fake-cdp.ts` | 수정 |
| `hermes/connectors/naver-blog/tests/editor-photos.test.ts` | 신규 |
| `hermes/connectors/naver-blog/tests/editor-run.test.ts` | 신규 |
| `hermes/connectors/naver-blog/tests/editor-text.test.ts` | 신규 |
| `hermes/connectors/naver-blog/dist/naver-blog-mcp.js` | 수정 |
