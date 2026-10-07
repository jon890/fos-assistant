# Phase 01. 커넥터 뼈대와 초안 검사, 미리보기, 세션 확인

**Execution profile**: deep

## 목표

`hermes/connectors/naver-blog/` 에 읽기 도구 둘(`session_status`, `render_draft`)만 가진 커넥터를 세워 공통 계약 검사를 통과시킨다.
임시저장 도구가 없어도 카탈로그에 오르고, 초안 검사와 미리보기가 다음 phase 의 쓰기 도구와 같은 코드를 쓰게 한다.

**범위 외**: 네이버 편집기 조작(phase 02), 임시저장 작업과 쓰기 도구(phase 03).

## 컨텍스트

- 본보기는 `hermes/connectors/gmail/` 이다. `package.json`, `tsconfig.json`, `.gitignore`, `scripts/build.ts`, `scripts/check-bundle.ts`, `.mcp.json`, `.claude-plugin/plugin.json` 의 모양을 그대로 따른다. 이름의 `gmail` 만 `naver-blog` 로 바꾼다(패키지 이름 `fos-naver-blog`, 묶음 파일 `dist/naver-blog-mcp.js`, 빌드 실패 코드 `NAVER_BLOG_BUILD_FAILED`, 묶음 불일치 코드 `NAVER_BLOG_BUNDLE_OUTDATED`)
- 의존성 판은 gmail 과 같다: `@modelcontextprotocol/sdk` `1.28.0`, `zod` `4.4.3`, dev `@types/bun` `1.3.10`, `typescript` `5.9.3`. Bun `1.3.14`
- gmail 의 `src/server.ts` 는 시작할 때 프록시 env 가 있으면 그 값을 뺀 env 로 자기 자신을 다시 실행한다. 같은 처리를 둔다
- 공통 계약 검사는 `hermes/tests/test_connectors_contract.py` 다. `hermes/connectors/` 아래 디렉터리를 모두 찾는다. 서버를 띄워 읽은 도구 이름이 `connector.json` 의 `tools` 키와 정확히 같아야 한다
- 커넥터마다의 검사는 `scripts/check-connectors.sh` 가 lockfile 설치, `typecheck`, `test`, `check:bundle` 로 돌린다
- 도구 결과와 오류의 모양은 `docs/connectors.md` 의 「connector.json」 끝 목록이 갖는다. 실패는 `isError: true` 와 `{"error": {"code": "..."}}` 다
- 결과물 HTML 이 같은 대화의 첨부를 부르는 계약은 `docs/backend/artifact.md` 의 「같은 대화의 첨부 사진을 부를 때」 다. web 라우트는 `web/src/app/api/chat/conversations/[conversationId]/files/[...path]/route.ts` 와 `web/src/app/api/chat/conversations/[conversationId]/attachments/[attachmentId]/route.ts` 다
- 실제 네이버와 Chrome 에 닿는 검사를 만들지 않는다. 사람 이름, 메일, 블로그 아이디는 가상 값(`example-blog`, `가상국수`)만 쓴다

**근거 문서**: `docs/connectors/naver-blog.md` 의 「등록 칸」, 「도구와 정책」, 「초안」, 「미리보기」, 「오류」, 「서버와 검사」. `docs/adr/ADR-20261007-naver-blog-connector.md`. `docs/connector-authoring.md` 의 「갖출 것」.

## 의도 메모

- 로그인 확인은 탭을 열지 않는다. 글쓰기 주소를 열어 리다이렉트를 기다리면 1~11초가 걸려 확인 도구의 10초 제한을 넘긴다. 브라우저 대상의 `Storage.getCookies` 로 `.naver.com` 의 `NID_AUT` 와 `NID_SES` 가 모두 있는지만 본다
- 미리보기는 사진 바이트를 담지 않는다. 도구 결과가 모델 입력에 들어가기 때문이다
- 초안 검사는 사람의 문체 규칙(첫 스티커, 협찬 표시 등)을 넣지 않는다. 그것은 사용자의 비공개 스킬이 갖는다
- 사진 순서는 검사하지 않는다

## 작업 항목

### 1. 커넥터 선언 파일

`hermes/connectors/naver-blog/connector.json`:

```json
{
  "schema": 2,
  "id": "naver-blog",
  "title": "네이버 블로그",
  "description": "로그인해 둔 Chrome 에 붙어 승인한 글을 네이버 블로그에 임시저장합니다. 발행하지 않습니다.",
  "fields": [
    { "key": "cdp_url", "env": "NAVER_BLOG_CDP_URL", "label": "브라우저 연결 주소",
      "description": "네이버에 로그인한 Chrome 의 원격 디버깅 주소입니다. IP 주소나 localhost 만 받습니다. 예: http://192.0.2.10:<포트>",
      "secret": true, "required": true,
      "pattern": "^https?://([0-9]{1,3}(\\.[0-9]{1,3}){3}|localhost)(:[0-9]{1,5})?/?$" },
    { "key": "blog_id", "env": "NAVER_BLOG_ID", "label": "블로그 아이디",
      "description": "blog.naver.com/<아이디> 의 아이디입니다.",
      "secret": false, "required": true, "pattern": "^[A-Za-z0-9_-]{1,50}$" }
  ],
  "verify": { "tool": "session_status" },
  "default_tool_policy": "deny",
  "tools": {
    "session_status": { "risk": "READ" },
    "render_draft": { "risk": "READ" }
  },
  "errors": {
    "NAVER_BLOG_INVALID_INPUT": "invalid_input",
    "NAVER_BLOG_PHOTO_INVALID": "invalid_input",
    "NAVER_BLOG_LOGIN_REQUIRED": "credential_rejected",
    "NAVER_BLOG_BROWSER_UNREACHABLE": "unavailable",
    "NAVER_BLOG_UNAVAILABLE": "unavailable"
  }
}
```

`.mcp.json` 은 서버 이름 `naver-blog`, `command: "bun"`, `args: ["${CLAUDE_PLUGIN_ROOT}/dist/naver-blog-mcp.js"]`, env 는 `NAVER_BLOG_CDP_URL` 과 `NAVER_BLOG_ID` 의 `${이름}` 참조 둘이다.
`.claude-plugin/plugin.json` 은 `name: "naver-blog"`, `skills: "./skills"` 다.

### 2. `src/errors.ts`

`ToolError(code)` 와 MCP 결과로 바꾸는 함수. 오류 글에는 코드만 싣는다. CDP 주소, 파일 경로, 브라우저가 준 원문을 싣지 않는다.

### 3. `src/cdp.ts`

Bun 의 전역 `fetch` 와 `WebSocket` 만 쓰는 CDP 창구.

- `endpoint(cdpUrl)`: 끝 `/` 를 뗀 주소
- `httpJson(cdpUrl, path, {method, timeoutMs})`: `/json/version`, `/json/list`, `/json/new?<주소>`(PUT), `/json/close/<id>` 를 부른다. 기본 제한 5초
- `wsUrlFor(cdpUrl, webSocketDebuggerUrl)`: Chrome 이 준 `webSocketDebuggerUrl` 의 호스트와 포트는 쓰지 않는다(Chrome 이 자기 loopback 주소를 적어 중계 너머에서는 닿지 않는다). 그 값의 경로만 꺼내 `cdp_url` 의 호스트와 포트에 붙인다. `http` 는 `ws`, `https` 는 `wss` 다
- 연결 칸의 정규식이 호스트를 IPv4 와 `localhost` 로 한정한다. Chrome 의 디버깅 HTTP 창구가 `Host` 가 IP 나 `localhost` 가 아닌 요청을 거절하기 때문이다. WebSocket 은 `Origin: http://localhost` 를 붙여 연다(Bun 의 `new WebSocket(url, {headers})`)
- `class CdpSession`: `connect(wsUrl, timeoutMs)`, 붙자마자 `Page.enable` 은 하지 않는다(브라우저 대상에는 Page 가 없다. 탭 대상의 처리는 phase 02 가 정한다), `send(method, params)` 가 응답 `id` 로 짝을 맞춘다. `waitEvent(method, timeoutMs)`, `close()`. 명령마다 제한 시간을 둔다(기본 30초). 이 phase 는 브라우저 대상만 쓰지만 phase 02 가 탭 대상에도 쓴다
- 연결 실패, 시간 초과, 닫힘은 모두 `NAVER_BLOG_BROWSER_UNREACHABLE` 로 바꾼다

### 4. `src/session.ts` 와 `session_status`

`sessionStatus(env, {timeoutMs = 8000} = {})`: `/json/version` 의 `webSocketDebuggerUrl` 을 `wsUrlFor` 로 바꿔 붙어 `Storage.getCookies` 를 부른다. 도메인이 `naver.com` 이나 `.naver.com` 인 `NID_AUT` 와 `NID_SES` 가 모두 있으면 `{browser: "connected", logged_in: true, blog_id}` 를 돌려준다. 없으면 `NAVER_BLOG_LOGIN_REQUIRED`. 전체를 `timeoutMs` 안에 끝낸다. 시험은 짧은 값을 넘긴다.
env 의 `NAVER_BLOG_CDP_URL` 과 `NAVER_BLOG_ID` 를 연결 칸과 같은 정규식으로 다시 검사하고 어긋나면 `NAVER_BLOG_INVALID_INPUT`.
서버에서 `readOnlyHint: true` 로 등록한다.

### 5. `src/draft.ts`

`docs/connectors/naver-blog.md` 의 「초안」 을 그대로 코드로 둔다.

- `type DraftInput = {title, category, tags, body, photo_dir?}` 와 zod 스키마 `draftShape`(phase 03 의 `save_draft` 도 그대로 쓴다)
- `parseBody(body): Block[]`. `Block` 은 `{type: "text", line}`, `{type: "image", number, file}`, `{type: "sticker", code}`, `{type: "map", name, address}`. 지시 줄 정규식은 `^\[사진 ([1-9][0-9]{0,2}): ([^/\]]+)\]$`, `^\[스티커: ([A-Za-z0-9_-]{1,64})\]$`, `^\[지도: ([^|\]]+?) \| ([^\]]+)\]$`. 줄 끝 `\r` 은 뗀다
- `validateDraft(input): string[]` 는 문서의 길이와 개수 제한, 사진 파일 이름(`^[A-Za-z0-9._-]+\.(jpg|jpeg|png|webp|gif|heic)$` 이고 `..` 없음, 대소문자 무시), 사진 지시가 있으면 `photo_dir` 필수를 문장 목록으로 돌려준다
- `checkPhotoFiles(input): Promise<string[]>`: `photo_dir` 가 절대 경로이고 `..` 조각이 없으며 `lstat` 으로 링크 아닌 디렉터리인지, 파일마다 그 디렉터리 바로 아래의 링크 아닌 보통 파일이고 20MB 이하이며 머리 바이트가 확장자와 맞는지(JPEG `FF D8 FF`, PNG `89 50 4E 47`, GIF `47 49 46 38`, WebP `RIFF....WEBP`, HEIC 는 4~11 바이트가 `ftypheic`, `ftypheix`, `ftypmif1`, `ftypmsf1` 가운데 하나) 본다. 문장에는 파일 이름만 쓰고 디렉터리 경로를 쓰지 않는다
- `readPhoto(input, file): Promise<{bytes, mime}>`: 같은 확인을 다시 하고 바이트를 돌려준다(phase 02 가 쓴다)

### 6. `src/render.ts` 와 `render_draft`

인자: 초안 다섯 칸, `photo_notes`(키가 `^[1-9][0-9]{0,2}$` 인 객체, 값은 300자까지 글자, 선택), `artifact_path`(`^[^/\\.][^/\\]{0,80}/index\.html$`), `kind`(`preview` | `package`, 기본 `preview`).

- `validateDraft` 와 `checkPhotoFiles` 의 문장이 있으면 `{problems, html: null}` 을 오류 없이 돌려준다
- `preview`: 폭 390px 의 모바일 화면 모양 HTML. 스크립트, 외부 CSS, 외부 그림, 폰트 없이 `<style>` 하나. 제목, 카테고리, 태그(`#` 붙여 보임)를 위에 두고 본문 블록을 차례로 그린다. 글 줄은 `<p>`, 빈 줄은 빈 문단. 사진 자리는 `N번째 사진` 이름표와 `photo_notes[N]` 설명을 붙이고, 파일 이름이 `^([0-9]+)\.[a-z0-9]+$` 이면 `<img src="../../attachments/<첨부 번호>" alt="N번째 사진" loading="lazy">` 를 둔다. 다른 이름이면 이름표만. 스티커는 코드가 `^(ogq_[0-9a-f]+)-([0-9]+)$` 이면 `<img class="sticker" src="stickers/<코드>.png" alt="스티커 <코드>">`(가로 `min(100%, 370px)`, 가운데)로 그리고 결과의 `assets` 에 `{path: "<artifact_path 의 폴더>/stickers/<코드>.png", source_url: "https://storep-phinf.pstatic.net/<묶음>/original_<번호>.png?type=p100_100"}` 을 코드마다 한 번 더한다. 다른 모양의 코드는 `스티커: <코드>` 이름표다. 지도는 상호명과 주소 카드. 사진은 네이버의 `문서 너비` 처럼 화면 폭을 꽉 채운다
- `package`: 사람이 붙여넣을 수동 등록용 묶음. 제목, 카테고리, 태그(쉼표로 이은 줄), 본문 전체(지시 줄은 `[N번째 사진 자리]`, `[스티커 <코드>]`, `[지도 <상호명> / <주소>]` 로 바꿈), 붙여넣는 순서 안내. 사진은 미리보기와 같은 상대 주소로 보인다
- 모든 글은 HTML 이스케이프한다. `photo_dir` 와 파일 시스템 경로는 HTML 에 넣지 않는다. 결과는 `{problems: [], html, assets}`. 계약 위반이면 `{problems, html: null, assets: []}`
- `readOnlyHint: true`

### 7. `src/server.ts`

`McpServer` 에 두 도구를 등록하고 stdio 로 띄운다. gmail 의 서버처럼 도구 처리 함수를 `createServer(env)` 로 묶어 시험에서 `InMemoryTransport` 로 부를 수 있게 한다.

### 8. `skills/naver-blog/SKILL.md`

앞머리는 `name: naver-blog` 와 `description` 만 둔다. `required_environment_variables` 같은 칸을 두지 않는다. 본문은 2,500자 안팎으로 아래를 적는다.

- 언제 쓰는가: 사용자가 대화에 사진을 올리고 네이버 블로그 글을 쓰자고 할 때
- 차례: 사진을 몇 번째 사진 순서대로 모두 `vision_analyze` 로 본다 → 사진에 없고 사용자가 말하지 않은 사실을 쓰지 않는다. 장소와 필수 사실을 묻는다 → 본문을 「초안」 모양으로 만든다 → `render_draft` 로 미리보기를 만들고, `assets` 의 항목마다 `artifact_write` 의 `source_url` 로 스티커 그림을 받은 뒤(실패해도 이어 간다) `artifact_write` 로 `<폴더>/index.html` 에 쓴다 → 사용자가 확인하면 `save_draft` 로 임시저장을 요청하고 `draft_job` 으로 결과를 확인한다. 이 두 도구의 자세한 쓰임은 phase 03 이 이 스킬에 더한다
- 사진은 사용자가 보낸 순서(몇 번째 사진)대로 놓는다. 사용자가 바꾸라고 할 때만 바꾼다
- 답에 파일 경로를 쓰지 않는다. 미리보기는 답 아래에 붙는다
- 발행하지 않는다. 로그인, 캡차, 기기 인증에서 막히면 우회하지 않고 사용자에게 브라우저 조작을 부탁한다
- 사용자의 문체와 카테고리 기준은 그 에이전트의 다른 스킬이 갖는다. 있으면 먼저 읽는다
- 승인이 필요한 도구는 부르면 승인 카드가 간다. 같은 도구를 다시 부르지 않는다
- 예시는 가상 블로그(`가상블로그`, `가상국수`)만 쓴다

### 9. 소유자와 이름 검사

- `.github/CODEOWNERS` 에 `/hermes/connectors/naver-blog/ @jon890`, `/hermes/connectors/naver-blog/tests/ @jon890`, `/docs/connectors/naver-blog.md @jon890` 세 줄을 gmail 줄 아래에 더한다
- `test/unit/connector-neutral.test.ts` 의 `FORBIDDEN` 에 `"naver"`, `"네이버"` 를 더한다. 더한 뒤 그 시험이 통과하는지 본다(지금 `backend/src/main`, `web/src`, `hermes/plugins` 에 그 낱말이 없다)

### 10. 결과물과 첨부의 형제 경로를 지키는 `test/unit/artifact-attachment-route.test.ts`

`node:test` 로 두 web 라우트 파일이 모두 있고 같은 `web/src/app/api/chat/conversations/[conversationId]/` 아래의 `files` 와 `attachments` 인지 본다. 미리보기의 `../../attachments/` 가 이 모양에 기댄다는 주석을 단다.

### 11. 이 phase 를 검증하는 시험

- `tests/fake-cdp.ts`: `Bun.serve` 로 HTTP 창구(`/json/version`, `/json/list`, `/json/new`, `/json/close/<id>`)와 WebSocket(`/devtools/browser/<id>`, `/devtools/page/<id>`)을 흉내 낸다. 받은 메서드를 기록하고, 시험이 메서드마다 답을 정한다. 허용 목록(이 phase 는 `Storage.getCookies`) 밖의 메서드가 오면 기록에 위반으로 남긴다
- `tests/naver-blog.test.ts`(공통 계약 검사가 `tests/<id>.test.ts` 를 요구한다): 쿠키 둘이 있으면 `logged_in: true`. 하나라도 없으면 `NAVER_BLOG_LOGIN_REQUIRED`. 닫힌 포트면 `NAVER_BLOG_BROWSER_UNREACHABLE`. 응답하지 않는 서버면 `timeoutMs: 300` 으로 같은 오류. `webSocketDebuggerUrl` 이 `ws://127.0.0.1:9/...` 처럼 다른 주소를 적어도 `cdp_url` 의 호스트와 포트로 붙는다. 정규식이 `http://chrome.example.internal:1` 을 거절한다. 결과와 오류 글에 CDP 주소가 없다
- `tests/draft.test.ts`: 지시 줄 셋과 글 줄, 빈 줄을 나누는 정상 예 하나. 모양이 틀린 지시 줄은 글 줄이 된다. 제한 위반, `photo_dir` 누락, `../x.jpg`, 링크, 20MB 초과, 서명 불일치(이름은 `.jpg` 인데 PNG 머리)를 각각 문장으로 돌려준다. 문장에 디렉터리 경로가 없다. 임시 디렉터리에 합성 바이트로 만든 파일만 쓴다
- `tests/render.test.ts`: `101.jpg` 는 `../../attachments/101` 로, `photo.jpg` 는 그림 없이 이름표로 그린다. `photo_notes` 가 들어가고, `<script` 가 없고, 제목의 `<b>` 가 이스케이프되고, `photo_dir` 문자열이 HTML 에 없다. `package` 는 지시 줄을 자리 표시로 바꾼다. 계약 위반이면 `html` 이 `null` 이다. `ogq_abc123-4` 스티커 두 번은 `stickers/ogq_abc123-4.png` 를 부르고 `assets` 에 `https://storep-phinf.pstatic.net/ogq_abc123/original_4.png?type=p100_100` 이 한 번만 든다. `sticker_hello` 는 이름표이고 `assets` 에 없다
- `tests/contracts.test.ts`: gmail 의 같은 이름 시험처럼 서버의 도구 목록이 `connector.json` 의 `tools` 와 같고 `READ` 도구만 `readOnlyHint` 가 참인지 본다

## 검증

```bash
cd hermes/connectors/naver-blog && bun install --frozen-lockfile && bun run typecheck && bun test ./src ./tests ./scripts && bun run build && bun run check:bundle
bash scripts/check-connectors.sh
python3 -m unittest discover -s hermes/tests
node --test test/unit/connector-neutral.test.ts test/unit/artifact-attachment-route.test.ts
bash scripts/check-public-safe.sh
```

기대값: 모두 종료 코드 0. `hermes/tests` 의 계약 검사가 `naver-blog` 를 찾아 통과한다.
`scripts/check-public-safe.sh` 는 `git grep` 으로 추적 파일만 본다. 돌리기 전에 새 파일을 `git add -N` 으로 올린다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `hermes/connectors/naver-blog/connector.json` | 신규 |
| `hermes/connectors/naver-blog/.mcp.json` | 신규 |
| `hermes/connectors/naver-blog/.claude-plugin/plugin.json` | 신규 |
| `hermes/connectors/naver-blog/package.json` | 신규 |
| `hermes/connectors/naver-blog/bun.lock` | 신규 |
| `hermes/connectors/naver-blog/tsconfig.json` | 신규 |
| `hermes/connectors/naver-blog/.gitignore` | 신규 |
| `hermes/connectors/naver-blog/scripts/build.ts` | 신규 |
| `hermes/connectors/naver-blog/scripts/check-bundle.ts` | 신규 |
| `hermes/connectors/naver-blog/src/errors.ts` | 신규 |
| `hermes/connectors/naver-blog/src/cdp.ts` | 신규 |
| `hermes/connectors/naver-blog/src/session.ts` | 신규 |
| `hermes/connectors/naver-blog/src/draft.ts` | 신규 |
| `hermes/connectors/naver-blog/src/render.ts` | 신규 |
| `hermes/connectors/naver-blog/src/server.ts` | 신규 |
| `hermes/connectors/naver-blog/dist/naver-blog-mcp.js` | 신규 |
| `hermes/connectors/naver-blog/skills/naver-blog/SKILL.md` | 신규 |
| `hermes/connectors/naver-blog/tests/fake-cdp.ts` | 신규 |
| `hermes/connectors/naver-blog/tests/naver-blog.test.ts` | 신규 |
| `hermes/connectors/naver-blog/tests/draft.test.ts` | 신규 |
| `hermes/connectors/naver-blog/tests/render.test.ts` | 신규 |
| `hermes/connectors/naver-blog/tests/contracts.test.ts` | 신규 |
| `.github/CODEOWNERS` | 수정 |
| `test/unit/connector-neutral.test.ts` | 수정 |
| `test/unit/artifact-attachment-route.test.ts` | 신규 |
