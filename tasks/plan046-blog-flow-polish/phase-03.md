# Phase 03. web 이 결과물 조건부 요청을 옮기고 결과물 이름을 폴더 이름으로 보인다

**Execution profile**: standard

## 목표

- web 의 결과물 서버 라우트가 브라우저의 조건부 요청 머리글을 Control Plane 에 옮기고, `ETag` 와 `Last-Modified` 를 브라우저로 옮기며, 304 를 본문 없이 그대로 돌려준다
- 결과물 파일이 `index.html` 이면 답 아래 줄과 결과물 패널 머리에 그 폴더 이름을 보인다

**범위 외**: Control Plane 의 304 판정(phase 02). 작업 과정 화면(phase 04).

## 컨텍스트

**근거 문서**: `docs/code-architecture.md` 의 「결과물 파일」 아래 「경로」 절(같은 이름의 절이 「사진 첨부」 아래에도 있다), `docs/flow.md` 의 「결과물 파일을 볼 때」 절

지금 모양이다. 구현 전에 각 파일을 연다.

- `web/src/app/api/chat/conversations/[conversationId]/files/[...path]/route.ts`
  - `GET(_request, context)` 가 요청 머리글을 쓰지 않는다
  - `forwardControlPlane(path, { method: "GET" })` 로 부른다
  - `FORWARDED_HEADERS` 는 `content-type`, `content-security-policy`, `x-content-type-options`, `cache-control` 넷이다
  - `!upstream.ok || !upstream.body` 이면 JSON 오류로 바꾼다. 304 는 `ok` 가 거짓이라 지금은 오류가 된다
- `web/src/lib/control-plane.ts` 의 `forwardControlPlane(path, init: { method; body?; contentType? })`: `Authorization` 과 `Content-Type` 만 보낸다. 다른 머리글을 받는 칸이 없다
- `web/src/components/chat/message-bubble.tsx` 의 `artifactNames(paths: string[]): Map<string, string>`: 경로의 마지막 조각을 이름으로 쓰고, 같은 이름이 둘 이상이면 마지막 두 조각을 쓴다. `ArtifactList` 가 쓴다
- `web/src/components/chat/artifact/artifact-panel.tsx`: `const name = path.split("/").at(-1) ?? path` 를 iframe `title` 과 머리 제목에 쓰고, 머리 제목의 `title` 속성은 `path` 다
- 단위 테스트는 `test/unit/*.test.ts` 가 `node --test` 로 돈다. `web/src` 의 모듈을 `../../web/src/lib/format.ts` 처럼 확장자까지 적은 상대 경로로 부른다. 그래서 새 모듈은 `@/` 별칭으로 다른 모듈을 부르지 않는다
- 브라우저 검사 `test/browser/artifact.spec.ts`: 대역 Hermes 가 `ARTIFACT_PROBE` 입력에 `초안/index.html` 과 `초안/photo.png` 를 쓴다. 첫 검사가 결과물 줄과 패널 제목이 `index.html` 이라고 확인한다

## 의도 메모

- `If-None-Match` 와 `If-Modified-Since` 만 옮긴다. 다른 요청 머리글을 통째로 옮기지 않는다. 쿠키가 Control Plane 으로 가면 안 된다
- 응답에서 옮기는 머리글에 `etag` 와 `last-modified` 를 더한다. 목록 밖의 머리글(`x-frame-options`)은 여전히 옮기지 않는다
- 304 는 오류가 아니다. 본문 없이 `new Response(null, { status: 304, headers })` 로 돌려준다
- 이름 규칙은 한 함수에 둔다. 답 아래 줄과 패널 머리가 다른 이름을 보이면 사용자가 같은 파일인지 헷갈린다

## 작업 항목

### 1. `web/src/lib/control-plane.ts` 의 `forwardControlPlane`

- `init` 에 `headers?: Record<string, string>` 을 더한다. 받은 머리글을 먼저 담고 `Authorization` 과 `Content-Type` 을 그 위에 쓴다. 부르는 쪽이 넘긴 `Authorization` 은 무시된다
- 기존 호출은 바꾸지 않는다

### 2. 결과물 서버 라우트

- `GET(request, context)` 로 요청을 받는다
- `if-none-match`, `if-modified-since` 가 있으면 `forwardControlPlane` 의 `headers` 로 넘긴다
- `FORWARDED_HEADERS` 에 `etag`, `last-modified` 를 더한다
- `upstream.status === 304` 이면 옮긴 머리글로 본문 없는 304 를 돌려준다. 이 분기를 오류 분기보다 먼저 둔다
- 파일 머리 주석에 조건부 요청을 한 줄 더한다

### 3. `web/src/lib/artifact-name.ts` 신규

- `export function artifactNames(paths: string[]): Map<string, string>` 를 이리로 옮기고 규칙을 바꾼다
  - 경로가 `/index.html` 로 끝나면 그 부분을 뗀 경로를, 아니면 경로 그대로를 기준 경로로 삼는다. 경로가 `index.html` 하나면 기준 경로도 `index.html` 이다
  - 이름은 기준 경로의 마지막 조각이다
  - 같은 이름이 둘 이상이고 기준 경로에 조각이 둘 이상이면 기준 경로의 마지막 두 조각을 `/` 로 잇는다
- `export function artifactName(path: string): string` 은 `artifactNames([path]).get(path)` 다
- `message-bubble.tsx` 는 이 모듈의 `artifactNames` 를 쓰고 자기 함수를 지운다
- `artifact-panel.tsx` 는 `artifactName(path)` 를 iframe `title` 과 머리 제목에 쓴다. 머리 제목의 `title={path}` 는 둔다

### 4. 이 phase 를 검증하는 테스트

- `test/unit/artifact-name.test.ts` 신규
  - `제주-여행/index.html` → `제주-여행`
  - `초안/본문.html` → `본문.html`
  - `index.html` → `index.html`
  - `가/초안/index.html` 과 `나/초안/index.html` 을 함께 주면 `가/초안`, `나/초안`
  - `a/index.html` 과 `b/index.html` 을 함께 주면 `a`, `b`
- `test/browser/artifact.spec.ts`
  - 첫 검사의 결과물 줄과 패널 제목 기대값을 `초안` 으로 바꾼다
  - 같은 검사의 직접 받기(`page.request.get(src!)`) 뒤에 둘을 더한다.
    응답에 `etag` 와 `last-modified` 가 있고, `etag` 값을 `If-None-Match` 로 다시 받으면 상태가 304, 본문이 비고, `cache-control` 이 `private, no-cache` 다. `last-modified` 값을 `If-Modified-Since` 로만 보내도 304 다
  - 옮기는 목록 밖 머리글 검사(`x-frame-options` 가 없다)는 그대로 둔다

## 검증

```bash
# cwd: 저장소 root
node --test test/unit/artifact-name.test.ts
node --test 'test/unit/**/*.test.ts'
```

```bash
# cwd: web/. 브라우저 검사는 한 번에 하나만 돈다. 먼저 다른 검사가 없는지 본다
ps -ax | grep -E "playwright test|standalone/server.js" | grep -v grep
pnpm typecheck
pnpm test:browser artifact
```

모두 통과해야 한다. `pnpm test:browser` 의 인자는 Playwright 의 파일 이름 필터다.
결과물 서버 라우트는 `web/src/app/api/chat/conversations/[conversationId]/files/[...path]/route.ts` 한 파일이다. 아래 표는 대괄호를 glob 으로 읽지 않게 `*` 로 적었다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `web/src/lib/control-plane.ts` | 수정 |
| `web/src/app/api/chat/conversations/*/files/*/route.ts` | 수정 |
| `web/src/lib/artifact-name.ts` | 신규 |
| `web/src/components/chat/message-bubble.tsx` | 수정 |
| `web/src/components/chat/artifact/artifact-panel.tsx` | 수정 |
| `test/unit/artifact-name.test.ts` | 신규 |
| `test/browser/artifact.spec.ts` | 수정 |
