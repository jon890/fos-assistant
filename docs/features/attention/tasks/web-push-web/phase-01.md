# Phase 01. 웹 앱 틀과 서비스 워커

**Execution profile**: standard

## 목표

홈 화면에 추가할 수 있는 웹 앱 설정(manifest, 아이콘)과, 푸시를 시스템 알림으로 띄우고 누르면 창을 여는 서비스 워커를 만든다.
아이폰은 홈 화면 앱에서만 웹 푸시를 받으므로 이 틀이 있어야 등록 화면이 의미가 있다.

**범위 외**: 서비스 워커 등록과 구독(phase 02), `/n/<id>` 화면(phase 03).

## 컨텍스트

**요구사항**: REQ4, REQ5(기기 쪽 표시)

**근거 문서**: `docs/features/attention/spec.md`, `docs/features/attention/design.md` 의 「웹」

- 앱 이름은 `web/src/lib/app-name.ts` 의 `appName()` 이 요청마다 환경에서 읽는다. 빌드 때 굳지 않게 `connection()` 을 먼저 부른다(`web/src/app/layout.tsx` 의 `generateMetadata` 참고).
- 백엔드가 보내는 푸시 글은 JSON `{"title","body","url","tag"}` 다(`url` 은 `/n/<알림 공개 식별자>`, `tag` 는 공개 식별자).
- 브라우저 테스트 서버는 `APP_NAME=검사용 비서` 로 뜬다(`web/test/browser/playwright.config.ts`).

## 의도 메모

- 아이콘을 정적 PNG 로 두지 않는다. 앱 이름이 실행 환경 값이라 첫 글자도 요청마다 정해진다. `next/og` 의 `ImageResponse` 로 만들고, 아이콘 경로와 `apple-icon.tsx` 도 `await connection()` 을 불러 빌드 때 굳지 않게 한다.
- `next/og` 의 기본 글꼴에는 한글이 없어 한글 앱 이름이면 첫 글자가 비어 보인다. 이미 의존에 있는 `pretendard` 의 `Pretendard-Bold.otf` 를 읽고, 코드가 경로로 읽어 파일 추적에 잡히지 않으므로 `next.config.ts` 의 `outputFileTracingIncludes` 로 standalone 결과에 넣는다.
- 아이콘 경로를 `app/icon/` 에 두지 않는다. `icon` 은 Next 의 메타데이터 파일 이름이라 헷갈린다. `app/app-icon/[size]/` 다.
- 서비스 워커를 번들러로 만들지 않고 `web/public/sw.js` 한 파일로 둔다. 판단 함수(`parsePushPayload`, `pickClient`)는 같은 파일에 두고, node 테스트가 파일을 `vm` 으로 읽어 부른다.
- 앱이 열려 있어도 늘 시스템 알림을 띄운다(스펙 DECISION19).

## 작업 항목

### 1. `web/src/app/manifest.ts`

`MetadataRoute.Manifest` 를 돌려준다. `name`, `short_name` 은 `appName()`, `display: "standalone"`, `start_url: "/"`, `scope: "/"`, 아이콘은 `/app-icon/192`, `/app-icon/512`(아래 2). 요청마다 만들게 `await connection()`.

### 2. 아이콘 경로

- `web/src/app/app-icon/[size]/route.ts`: 192 와 512 만 받고 그 밖은 404. 단색 바탕에 앱 이름 첫 글자를 그린 PNG(`ImageResponse`).
- `web/src/app/apple-icon.tsx`: 180×180 같은 그림. Next 가 `<link rel="apple-touch-icon">` 을 붙인다.
- 그림은 `web/src/lib/app-icon.tsx` 의 함수 하나(`appIcon(size)`)가 만든다. 첫 글자는 `Array.from(name)[0]?.toUpperCase()`(로그인 화면과 같은 방식).
- `layout.tsx` 의 `generateMetadata` 에 `appleWebApp: { capable: true, title: appName() }` 을 더한다. iOS 가 홈 화면 앱을 주소창 없이 연다.

### 3. `web/public/sw.js`

- `push`: `parsePushPayload(text)` 가 `{title, body, url, tag}` 를 돌려준다. JSON 이 아니거나 제목이 없으면 제목 「새 알림이 있어요」 와 `url: "/notifications"` 로 띄운다. `url` 은 `/` 로 시작하고 `//` 로 시작하지 않는 경로만 받고 아니면 `/notifications`. `showNotification(title, {body, tag, data: {url}})`.
- `notificationclick`: 알림을 닫고, `clients.matchAll({type: "window", includeUncontrolled: true})` 중 `pickClient(clients, origin)` 이 고른 같은 출처 창이 있으면 `focus()` 뒤 `navigate(url)`, 없으면 `clients.openWindow(url)`. `pickClient` 는 보이는(`visibilityState === "visible"`) 창을 먼저, 없으면 첫 창.
- `install` 에서 `skipWaiting()`, `activate` 에서 `clients.claim()`. 캐시는 두지 않는다.

### 4. 테스트

- `web/test/unit/push-service-worker.test.ts`: `sw.js` 를 `vm` 으로 읽어 `parsePushPayload` 정상, 깨진 JSON, 바깥 주소 거절과 `pickClient` 의 보이는 창 우선, 다른 출처 제외를 본다. 가짜 `self`(`registration.showNotification`, `clients.matchAll`, `openWindow`, 창의 `focus`, `navigate`)를 두고 `push` 와 `notificationclick` 사건을 직접 보내, 표시 인자(제목, 본문, tag, `data.url`), 열린 창이면 `focus` 뒤 `navigate`, 창이 없으면 `openWindow`, `navigate` 가 실패하면 `openWindow` 를 본다.
- `web/test/browser/web-app.spec.ts`: `/manifest.webmanifest` 의 `name` 이 「검사용 비서」(빌드 때와 다른 실행 값이라 빌드에 굳었으면 실패한다), `display` 가 `standalone`, 아이콘 경로가 200 `image/png`, 없는 크기는 404, 화면의 `apple-touch-icon` 링크가 200 `image/png`, 홈 화면 앱 meta 가 있다. 아이콘 PNG 가 빈 바탕만이 아닌지(첫 글자가 그려져 크기가 바탕만 그린 그림보다 큰지)도 본다.

## 검증

```bash
cd web && pnpm typecheck && pnpm lint && pnpm test:node
cd web && node --test test/unit/push-service-worker.test.ts
cd web && pnpm test:browser web-app.spec.ts
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `web/src/app/manifest.ts` | 신규 |
| `web/src/app/app-icon/[size]/route.ts` | 신규 |
| `web/src/app/apple-icon.tsx` | 신규 |
| `web/src/lib/app-icon.tsx` | 신규 |
| `web/src/app/layout.tsx` | 수정 (appleWebApp) |
| `web/next.config.ts` | 수정 (아이콘 글꼴 추적 포함) |
| `web/public/sw.js` | 신규 |
| `web/test/unit/push-service-worker.test.ts` | 신규 |
| `web/test/browser/web-app.spec.ts` | 신규 |
