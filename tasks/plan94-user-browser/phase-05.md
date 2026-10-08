# Phase 05. 네이버 블로그 커넥터를 중계로 옮기기와 로그인 안내

**Execution profile**: deep

## 목표

네이버 블로그 커넥터의 연결 칸 `cdp_url` 을 빼고, 바인딩 설치가 준 중계 주소(`NAVER_BLOG_BROWSER_URL`)로 주인의 브라우저에 붙게 한다.
연결 화면은 「내 브라우저」 에서 먼저 로그인하라는 안내와 바로 가는 링크를 보인다.

**범위 외**: 상주 Chrome 을 내리는 운영 이행(단계 4, `fos-home-infra`). 중계와 배선(phase 01~04).

## 컨텍스트

- 커넥터 코드는 `hermes/connectors/naver-blog/` 다. env 는 `src/session.ts` 의 `readConnection` 이 읽고(`NAVER_BLOG_CDP_URL`, `NAVER_BLOG_ID`), `src/server.ts` 78줄 근처의 `keys` 가 작업 프로세스에 넘길 env 이름을 갖는다
- `src/cdp.ts` 의 `websocketOriginFor` 가 모든 WebSocket 연결에 `Origin` 을 싣는다. 중계는 `Origin` 이 있으면 403 이다. 이 머리를 뺀다
- `src/cdp.ts` 의 `wsUrlFor(cdpUrl, webSocketDebuggerUrl)` 는 Chrome 이 준 주소의 경로만 꺼내 받은 주소의 호스트에 붙이고, 경로가 `/devtools/` 로 시작하지 않으면 `NAVER_BLOG_BROWSER_UNREACHABLE` 이다. 중계가 준 주소의 경로는 `/internal/browser-gateway/<표식>/devtools/<page|browser>/<번호>` 라 이 검사에 걸린다. 고친다
- `src/session.ts` 의 `sessionStatus` 는 전체 8초(확인 도구의 10초 제한 안) 안에서 `/json/version` 을 5초까지 기다린다. 중계는 꺼진 브라우저를 켜느라 그 요청을 30초까지 붙잡을 수 있다
- `tests/contracts.test.ts` 는 `CDP_URL_PATTERN` 을 `connector.json` 의 `cdp_url` 패턴과 견준다
- `src/jobs.ts` 의 `lockFile(dir, cdpUrl)` 은 CDP 주소마다 잠근다. 바인딩마다 표식이 달라 같은 브라우저에 주소가 여럿이 되므로 블로그 아이디로 잠근다
- 묶음 파일 `dist/naver-blog-mcp.js` 는 저장소에 들어 있다. 이 Mac 에는 `bun` 이 `PATH` 밖(`$HOME/.bun/bin`)에 있다
- 웹의 연결 화면은 `web/src/components/connector/connector-connection-panel.tsx`, 형은 `web/src/lib/connection.ts` 의 `ConnectorSummary` 다. 「내 브라우저」 화면은 `/browser` 이고 `?url=` 로 시작 주소를 받는다(`docs/backend/user-browser.md` 의 「로그인 화면」 웹 목록)
- 브라우저 시험 `test/browser/connector-connection.spec.ts` 는 API 응답을 가짜로 둔다(`demoConnector`)

**근거 문서**: `docs/backend/user-browser.md` 의 「중계」, `docs/adr/ADR-20261007-user-browser.md`, `backend/docs/adr/ADR-20261008-browser-gateway-token.md`, `docs/connectors/naver-blog.md`

## 의도 메모

- 사용자 결정(2026-10-08): 일반 사용자는 서버 안의 주소를 알 수 없고 알아서도 안 된다. 연결 칸에는 `blog_id` 만 남는다
- 기존 연결의 보관 파일에 남은 `cdp_url` 은 대시보드가 버린다(phase 04). 사용자는 「내 브라우저」 에서 로그인한 뒤 연결 확인을 누른다. 자동 이관 코드는 두지 않는다
- 중계가 404, 502, 503 을 주면 지금처럼 `NAVER_BLOG_BROWSER_UNREACHABLE`(`unavailable`)이다. 오류 글에 주소를 싣지 않는 규칙은 그대로다

## 작업 항목

### 1. 커넥터

- `connector.json`: `cdp_url` 칸을 지운다. `"owner_browser_env": "NAVER_BLOG_BROWSER_URL"`, `"owner_browser_login_url": "https://nid.naver.com/nidlogin.login"` 을 더한다. `description` 을 「「내 브라우저」 에 로그인해 둔 네이버 계정으로 승인한 글을 임시저장합니다. 발행하지 않습니다.」 로 바꾼다
- `.mcp.json`: 서버 env 의 `NAVER_BLOG_CDP_URL` 을 `"NAVER_BLOG_BROWSER_URL": "${NAVER_BLOG_BROWSER_URL}"` 로 바꾼다
- `src/session.ts`: `readConnection` 이 `NAVER_BLOG_BROWSER_URL` 을 읽는다. 패턴은 `^https?://[A-Za-z0-9.-]{1,253}(:[0-9]{1,5})?(/[A-Za-z0-9._~-]{1,128}){1,8}$`(`CDP_URL_PATTERN` 을 `BROWSER_URL_PATTERN` 으로 바꾼다). 돌려주는 칸 이름 `cdpUrl` 은 그대로 둔다
- `src/cdp.ts` 의 `wsUrlFor`: Chrome 이 준 경로가 `<받은 주소의 경로(끝 `/` 없이)>/devtools/` 로 시작할 때만 받고, 그 경로를 받은 주소의 호스트에 붙인다. 받은 주소에 경로가 없으면 지금과 같다
- `src/cdp.ts`: `websocketOriginFor` 와 `headers: { Origin: ... }` 를 지운다. `CdpSession.connect(wsUrl, cdpUrl, ...)` 의 `cdpUrl` 인자가 Origin 에만 쓰였으면 인자도 지우고 호출부(`src/session.ts`, `src/editor/page.ts`)를 고친다
- `src/session.ts` 의 `sessionStatus`: `/json/version` 의 5초 상한을 없애고 남은 시간(`remaining()`)을 쓴다. 확인 도구로 부를 때는 지금처럼 전체 8초다
- `src/server.ts`: `keys` 의 `NAVER_BLOG_CDP_URL` 을 `NAVER_BLOG_BROWSER_URL` 로. 잠금은 `acquireLock(dir, blogId, jobId)`. `save_draft` 가 작업을 띄우기 전에 브라우저를 확인하는 `sessionStatus` 호출은 `timeoutMs: 45_000` 으로 부른다. 브라우저를 켜는 시간(최대 30초)을 기다리기 위해서다
- `src/jobs.ts`: `lockFile(dir, blogId)` 로 바꾸고 해시 대상을 블로그 아이디로. 주석의 「CDP 주소마다」 를 「블로그마다」 로
- `skills/naver-blog/SKILL.md`: 로그인이 필요하다는 오류(`NAVER_BLOG_LOGIN_REQUIRED`, `login_required`)이면 사용자에게 「내 브라우저」 화면에서 네이버에 로그인한 뒤 다시 시도하라고 안내하게 한 줄. 브라우저에 닿지 않는다는 오류(`unavailable`)는 브라우저를 켜는 중일 수 있으니 잠시 뒤 한 번 다시 부르라는 한 줄
- `tests/contracts.test.ts`: `CDP_URL_PATTERN` 대신 `BROWSER_URL_PATTERN` 이 대시보드의 `OWNER_BROWSER_VALUE_RE` 와 같은 식인지 견준다(`hermes/plugins/dashboard-profile-api/connector_schema.py` 에서 정규식 글을 읽어 비교한다. JS `source` 의 `\/` 같은 이스케이프를 풀어 맞춘 뒤 견준다)
- 시험(`tests/`): env 이름을 바꾸고, 가짜 CDP 서버가 받은 WebSocket 요청에 `Origin` 이 없는지 단언하는 시험을 하나 더한다. `Origin` 을 확인하던 기존 단언은 지운다. 주소가 경로를 가진 중계 모양(`http://127.0.0.1:<포트>/internal/browser-gateway/b1.<64자>`)일 때 `/json/version` 과 WebSocket 경로가 그 아래로 가는지 본다. 가짜 CDP 서버가 그 접두사를 받도록 고친다. `wsUrlFor` 가 접두사 밖 경로(`/devtools/page/x` 를 접두사 없이 준 경우)를 거절하는지 본다
- `PATH="$HOME/.bun/bin:$PATH" bun run build` 로 `dist/naver-blog-mcp.js` 를 다시 만든다

### 2. 웹

- `web/src/lib/connection.ts`: `ConnectorSummary` 에 `ownerBrowserLoginUrl: string | null` 과 주석(옛 Control Plane 은 내지 않는다). `browserLoginHref(url: string | null | undefined): string | null` 순수 함수: `https://` 주소일 때만 `/browser?url=<encodeURIComponent>` 를 준다
- `connector-connection-panel.tsx`: `browserLoginHref` 가 값을 주면 칸 위에 안내 한 단락 「이 커넥터는 「내 브라우저」 에 로그인한 계정을 씁니다. 먼저 내 브라우저에서 로그인한 뒤 연결하세요.」 와 `next/link` 링크 「내 브라우저에서 로그인」. 연결 확인이나 등록이 `CONNECTOR_CREDENTIAL_REJECTED` 로 실패했을 때 같은 링크를 오류 문구 옆에 보인다
- `test/browser/connector-connection.spec.ts`: `ownerBrowserLoginUrl` 이 있는 커넥터에서 링크의 `href` 가 `/browser?url=https%3A%2F%2F...` 이고, 없는 커넥터에서는 안내가 보이지 않는지 본다
- `web/src/lib/connection.test.ts` 가 있으면 `browserLoginHref` 의 `http://` 거절을 더한다. 없으면 브라우저 시험으로 갈음한다

### 3. 문서

- `docs/connectors/naver-blog.md`
  - 「등록 칸」: `cdp_url` 줄을 지우고, 설치가 주는 env 에 `NAVER_BLOG_BROWSER_URL` 을 더한다. 확인 도구가 요청자의 「내 브라우저」 로 로그인 쿠키를 본다는 문장
  - 「작업」 의 잠금 문장: 블로그 아이디마다 하나
  - 「오류」: `NAVER_BLOG_BROWSER_UNREACHABLE` 의 뜻을 「중계에 닿지 않거나, 브라우저를 켜지 못했다(동시 수가 찼다)」 로. 꺼진 브라우저를 켜는 데 30초까지 걸려 연결 확인(10초 제한)은 처음 한 번 실패할 수 있고 잠시 뒤 다시 누르면 된다는 문장. `save_draft` 는 45초까지 기다린다
  - 「보안」 의 「CDP 주소」 줄을 「중계 주소」 로: 바인딩마다 다른 접근 표식, 주인의 브라우저만, 결과와 로그에 싣지 않는다
  - 「설정 안내」 1, 2, 3 을 바꾼다: 1 「내 브라우저」 에서 브라우저를 켜고 네이버에 로그인, 2 연결 화면에 블로그 아이디, 3 에이전트에 붙이기. Chrome 을 손으로 띄우는 절차와 `--remote-allow-origins` 설명을 지운다
  - 「로그인이 풀렸을 때」: 「내 브라우저」 에서 다시 로그인하고 연결 확인
  - 「기존 연결 옮기기」 절을 더한다: 배포 뒤 연결 확인 한 번, 서버 정의가 바뀌어 한 번은 재시작 대기라 관리자가 gateway 를 재시작하고 반영 완료를 누른다
- `docs/backend/user-browser.md` 첫 단락에 커넥터가 중계로 이 브라우저를 쓴다는 한 줄은 이미 「중계」 가 갖는다. 고칠 것이 없으면 두지 않는다

## 검증

```bash
cd hermes/connectors/naver-blog && PATH="$HOME/.bun/bin:$PATH" bun test ./src ./tests ./scripts
cd hermes/connectors/naver-blog && PATH="$HOME/.bun/bin:$PATH" bun run typecheck && PATH="$HOME/.bun/bin:$PATH" bun run check:bundle
python3 -m unittest discover -s hermes/tests -p 'test_connectors_contract.py'
cd web && pnpm lint && pnpm typecheck
scripts/check-local.sh connector-connection
! git grep -n "NAVER_BLOG_CDP_URL\|cdp_url" -- hermes/connectors/naver-blog ':!hermes/connectors/naver-blog/dist'
```

`pnpm typecheck` 스크립트 이름이 다르면 `web/package.json` 의 이름을 쓴다. 모두 실패 0 이고 마지막 명령은 출력이 없어야 한다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `hermes/connectors/naver-blog/connector.json` | 수정 |
| `hermes/connectors/naver-blog/.mcp.json` | 수정 |
| `hermes/connectors/naver-blog/src/session.ts` | 수정 |
| `hermes/connectors/naver-blog/src/cdp.ts` | 수정 |
| `hermes/connectors/naver-blog/src/server.ts` | 수정 |
| `hermes/connectors/naver-blog/src/jobs.ts` | 수정 |
| `hermes/connectors/naver-blog/src/editor/*.ts` | 수정 |
| `hermes/connectors/naver-blog/skills/naver-blog/SKILL.md` | 수정 |
| `hermes/connectors/naver-blog/tests/*.ts` | 수정 |
| `hermes/connectors/naver-blog/dist/naver-blog-mcp.js` | 수정 |
| `web/src/lib/connection.ts` | 수정 |
| `web/src/components/connector/connector-connection-panel.tsx` | 수정 |
| `test/browser/connector-connection.spec.ts` | 수정 |
| `docs/connectors/naver-blog.md` | 수정 |
