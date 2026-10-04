# Phase 02. 알림 단추와 알림 화면

**Execution profile**: standard

## 목표

사이드바 맨 아래 줄과 좁은 화면의 머리에 알림 단추를 두고 읽지 않은 수를 보인다. `/notifications` 화면에서 알림을 읽고 누르면 그 대화로 간다.
수는 phase 01 의 사용자 단위 SSE 로 바로 바뀐다. 사용자가 어느 화면에 있든 승인 요청을 알게 하려는 것이다.

**범위 외**: Control Plane 의 표와 API(phase 01 에서 끝났다). 예약 작업 알림의 갈 곳(`TASK`)은 다음 plan 이 더한다. 웹 푸시.

## 컨텍스트

**근거 문서**: `docs/backend/notification.md` 의 「API」 와 「화면」, `docs/frontend/shell.md` 의 「화면 틀」, `docs/frontend/structure.md` 의 화면 표, `web/AGENTS.md` 전체.

Control Plane API(phase 01 이 만들었다. 구현 전에 `backend/src/main/java/com/bifos/assistant/notification/presentation/NotificationDtos.java` 를 읽어 칸 이름을 맞춘다):

| 메서드 | 경로 | 응답 |
| --- | --- | --- |
| GET | `/api/v1/notifications?cursor=&limit=` | `{ items: NotificationView[], nextCursor: string \| null, unreadCount: number }` |
| POST | `/api/v1/notifications/{notificationId}/read` | `NotificationView` |
| POST | `/api/v1/notifications/read-all` | `{ unreadCount: 0 }` |
| GET | `/api/v1/notifications/events` | SSE. `data:` 에 `{type:"created", notificationId, unreadCount}` 또는 `{type:"read", unreadCount}` |

`NotificationView` 는 `{ id, kind, title, body, targetType, targetId, createdAt, readAt }` 이고 `targetType` 은 지금 `"CONVERSATION"` 이거나 `null` 이다.

따를 기존 패턴(경로는 `web/src/` 기준):

- 서버 라우트: `lib/control-plane.ts` 의 `callControlPlane<T>(path, {method, body})` 와 `requestControlPlane(path, {signal})`. 오류 응답은 `lib/api-response.ts` 의 `errorResponse`.
- SSE 중계: `app/api/chat/conversations/[conversationId]/events/route.ts`. 업스트림 `body` 를 그대로 넘기고 머리글 `text/event-stream; charset=utf-8`, `Cache-Control: no-cache, no-transform`, `Connection: keep-alive`, `X-Accel-Buffering: no` 를 단다. `request.signal` 을 넘겨 브라우저가 끊으면 업스트림도 끊는다.
- SSE 읽기: `lib/stream.ts` 의 `readEventStream<T>(response, onEvent)`. `EventSource` 는 쓰지 않는다. 재연결은 `components/chat/conversation-session.tsx` 의 events `useEffect` 처럼 5초 뒤 다시 연다. 4xx 는 다시 열지 않는다.
- 화면에서 `fetch` 를 직접 부르지 않는다(eslint 규칙). `lib/notification-api.ts` 에 호출 함수를 둔다.
- 틀: `components/shell/app-shell.tsx` 의 `AppShell` 이 `ConversationsProvider enabled={signedIn && !inAdminArea}` 를 감싼다. 같은 조건으로 알림 provider 를 감싼다. 좁은 화면 머리는 같은 파일의 `md:hidden` 머리이고 「새 대화」 `TooltipButton` 이 있다.
- 사이드바 맨 아래 줄: `components/shell/sidebar.tsx` 에서 `ThemeToggle` 이 있는 `ml-auto` 묶음.
- 수 배지: `components/shell/main-nav.tsx` 의 `data-testid="memory-proposal-count"` 배지 모양.
- 화면 page: `app/memory/page.tsx` 처럼 `auth()` 로 세션을 보고 없으면 `redirect("/signin")`. 이 화면은 서버에서 목록을 읽지 않는다. 그래서 `loading.tsx` 를 두지 않는다. `test/unit/loading-routes.test.ts` 는 뼈대를 두기로 한 경로(`ROUTE_FRAMES`)에만 `loading.tsx` 가 있어야 통과한다.
- 부품: `components/ui/` 의 `Button`, `TooltipButton`, `Badge`, `EmptyState`, `Notice`, `PageSkeleton`. 색은 테마 토큰만 쓴다.
- 오류 문구: `components/error-message.ts` 의 코드별 문구 표.
- 화면 문구는 해요체다(`web/AGENTS.md` 의 「화면 문구」).

## 의도 메모

- 단추는 목록을 펼치는 판이 아니라 `/notifications` 로 가는 링크다. 판을 두면 좁은 화면에서 서랍과 판이 겹치고, 알림이 늘면 판 안에서 다시 쪽을 넘겨야 한다.
- 수는 SSE 사건의 `unreadCount` 를 그대로 쓴다. 화면이 스스로 더하고 빼지 않는다. 창이 여럿일 때 서로 어긋나지 않는다.
- 다시 연결한 뒤에는 첫 쪽을 `limit=1` 로 읽어 `unreadCount` 를 다시 맞춘다. 끊긴 동안의 사건은 오지 않는다.
- **첫 쪽 읽기가 끝난 뒤에 SSE 를 연다.** 순서가 바뀌면 늦게 온 읽기 응답이 사건의 수를 덮어쓴다.
- 목록의 첫 쪽은 서버가 아니라 브라우저가 읽는다. 서버에서 읽으면 브라우저 검사가 그 읽기를 가로챌 수 없고, 검사 사용자에게는 실제 알림이 없다.
- 알림 provider 는 로그인한 모든 화면에서 SSE 를 열어 둔다. 그 화면들에서 `networkidle` 을 기다리는 기존 브라우저 검사는 끝나지 않는다. 그런 검사에서 `**/api/notifications/events` 를 빈 응답으로 끝내게 route 한다.

## 작업 항목

### 1. 순수 함수 `web/src/lib/notification.ts`

- 타입 `NotificationKind`, `NotificationTargetType`, `NotificationView`, `NotificationPage`, `NotificationEvent`
- `notificationHref(view: NotificationView): string | null`. `CONVERSATION` 이면 `/chat/{targetId}`, 그 밖이나 비었으면 `null`
- `unreadBadge(count: number): string | null`. 0 이하면 `null`, 99 를 넘으면 `"99+"`, 그 밖은 숫자 문자열
- 이 파일은 `test/unit` 이 읽는다. 런타임 import 가 있으면 상대 경로로 쓰고 `web/eslint.config.mjs` 의 `NODE_TEST_READ_FILES` 에 넣는다(`web/AGENTS.md` 의 「상대 경로 import 예외」). 런타임 import 가 없으면 넣지 않는다

### 2. 호출 함수 `web/src/lib/notification-api.ts`

`fetchNotifications(cursor?, limit?)`, `markNotificationRead(id)`, `markAllNotificationsRead()`, `openNotificationEvents(signal)`. 각각 아래 서버 라우트를 부른다.

### 3. 서버 라우트

| 파일 | 동작 |
| --- | --- |
| `web/src/app/api/notifications/route.ts` | GET. `cursor` 와 `limit` 만 넘긴다 |
| `web/src/app/api/notifications/[notificationId]/read/route.ts` | POST. `notificationId` 가 UUID 가 아니면 Control Plane 을 부르지 않고 400 `VALIDATION_FAILED` |
| `web/src/app/api/notifications/read-all/route.ts` | POST |
| `web/src/app/api/notifications/events/route.ts` | GET. SSE 중계 |

UUID 검사는 대화 식별자 검사(`isConversationId`)가 있는 파일을 찾아 같은 함수를 쓰거나 같은 모양으로 둔다.

### 4. 상태 `web/src/components/notification/notifications-provider.tsx`

`NotificationsProvider({ enabled, children })` 와 `useUnreadNotifications(): { unreadCount: number; refresh(): void }`.
`enabled` 일 때 첫 쪽(`limit=1`)으로 수를 읽고 SSE 를 연다. 사건마다 수를 바꾸고, `window` 에 `CustomEvent("notifications-changed")` 를 보내 열린 목록 화면이 첫 쪽을 다시 읽게 한다.
`components/shell/app-shell.tsx` 에서 `ConversationsProvider` 와 같은 조건으로 감싼다.

### 5. 단추 `web/src/components/notification/notification-bell.tsx`

`NotificationBell({ onNavigate?: (href: string) => void })`. 사이드바의 `onNavigate` 와 같은 타입이다(`components/shell/sidebar.tsx`). `/notifications` 로 가는 링크이고 접근 이름은 읽지 않은 수가 있으면 「알림, 읽지 않은 알림 N개」, 없으면 「알림」 이다. 배지는 `unreadBadge` 결과이고 `data-testid="notification-count"` 다.
`components/shell/sidebar.tsx` 의 `ThemeToggle` 옆과 `components/shell/app-shell.tsx` 의 좁은 화면 머리 「새 대화」 옆에 둔다. 사이드바 안의 단추를 누르면 서랍이 닫혀야 한다. 사이드바의 다른 링크가 `onNavigate` 를 부르는 방식을 따른다.

### 6. 화면 `/notifications`

| 파일 | 내용 |
| --- | --- |
| `web/src/app/notifications/page.tsx` | 세션만 확인하고 `NotificationList` 를 그린다. 목록을 서버에서 읽지 않는다. `metadata` 제목은 「알림」 |
| `web/src/components/notification/notification-list.tsx` | 화면이 열리면 브라우저에서 첫 쪽을 읽는다. 읽는 동안 뼈대를 보인다. 줄마다 제목, 본문, 만든 시각, 읽지 않음 표시. 위에 「모두 읽음」(읽지 않은 줄이 없으면 비활성). 끝에 닿으면 다음 쪽. 줄을 누르면 읽음으로 표시한 뒤 `notificationHref` 로 간다. 갈 곳이 없으면 읽음만 표시한다. 빈 목록은 `EmptyState` 「아직 알림이 없어요.」. `notifications-changed` 사건을 받으면 첫 쪽을 다시 읽는다. 줄의 testid 는 `notification-item`(`data-read`) |

### 7. 오류 문구

`web/src/components/error-message.ts` 에 `NOTIFICATION_NOT_FOUND: "이미 지워졌거나 없는 알림이에요."` 를 더한다.

### 8. 문서

`docs/frontend/structure.md` 의 화면 표와 `docs/frontend/shell.md` 의 「화면 틀」 이 이미 알림 단추와 `/notifications` 를 적었다. 구현이 다르면 같은 커밋에서 문서를 고친다. 고치기 전에 계획 담당에게 알린다.

### 9. 이 phase 를 검증하는 테스트

| 파일 | 확인하는 것 |
| --- | --- |
| `test/unit/notification.test.ts` | `notificationHref` 가 대화 대상이면 `/chat/{id}`, 대상이 없으면 `null`. `unreadBadge` 가 0 이면 `null`, 5 면 `"5"`, 100 이면 `"99+"` |
| `test/browser/notifications.spec.ts` | 아래 |

브라우저 spec 은 `test/browser/fixtures.ts` 의 `test` 와 `expect` 를 쓴다. 두 폭(`mobile`, `desktop`)에서 돈다.

- `page.route("**/api/notifications?**", ...)` 로 읽지 않은 알림 둘(대화 대상 하나, 대상 없음 하나, `unreadCount: 2`)을 돌려주고, `**/api/notifications/events` 는 사건 하나(`{type:"created", unreadCount: 3}`)를 보낸 뒤 붙잡아 둔다. `test/browser/approval-card.spec.ts` 의 `holdEvents` 방식을 따른다
- 새 대화 화면에서 알림 단추의 배지가 사건을 받은 뒤 `3` 이다. 넓은 폭에서도 좁은 화면 머리의 단추가 DOM 에 남으므로 `getByTestId("notification-count")` 를 그대로 쓰지 않는다. 좁은 폭은 머리(`banner`) 안에서, 넓은 폭은 「사이드바」 `aside` 안에서 찾는다
- 단추를 누르면 `/notifications` 이고 줄 둘이 보인다
- 대화 대상 줄을 누르면 `**/api/notifications/*/read` 가 불리고 `/chat/{id}` 로 간다. 그 대화는 `page.request.post("/api/chat", ...)` 로 먼저 만들어 둔다
- 알림이 없을 때 「아직 알림이 없어요.」 가 보인다

`test/browser/conversation-requests.spec.ts` 는 `networkidle` 을 기다린다. 그 검사의 각 테스트 앞에서 `**/api/notifications/events` 를 빈 SSE 응답(`status: 200`, `contentType: "text/event-stream"`, `body: ""`)으로 끝내게 route 한다. 그 밖에 `networkidle` 을 기다리는 spec 이 있는지 `grep -rn networkidle test/browser` 로 찾아 같은 처리를 하고 「변경 파일」 에 없는 파일이면 team-lead 에게 알린다.
- `test.afterEach` 에서 `page.unrouteAll({ behavior: "ignoreErrors" })`. `conversation-requests.spec.ts` 에 더한 route 도 같은 방식으로 푼다

## 검증

```bash
# cwd: web/
pnpm lint
pnpm format:check
pnpm typecheck
pnpm build
pnpm test:browser -- notifications.spec.ts conversation-requests.spec.ts shell.spec.ts nav.spec.ts approval-card.spec.ts
```

```bash
# cwd: 저장소 root
node --test test/unit/notification.test.ts
node --test 'test/unit/**/*.test.ts'
grep -rn 'style={{' web/src/components/notification web/src/app/notifications
scripts/check-public-safe.sh
```

기대값: `grep` 은 아무것도 내지 않는다. 나머지는 모두 종료 코드 0.
`pnpm build` 에 필요한 환경 변수는 `web/AGENTS.md` 와 `scripts/check-local.sh` 가 넣는 값을 따른다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `web/src/lib/notification.ts` | 신규 |
| `web/src/lib/notification-api.ts` | 신규 |
| `web/src/app/api/notifications/route.ts` | 신규 |
| `web/src/app/api/notifications/[notificationId]/read/route.ts` | 신규 |
| `web/src/app/api/notifications/read-all/route.ts` | 신규 |
| `web/src/app/api/notifications/events/route.ts` | 신규 |
| `web/src/app/notifications/page.tsx` | 신규 |
| `web/src/components/notification/notifications-provider.tsx` | 신규 |
| `web/src/components/notification/notification-bell.tsx` | 신규 |
| `web/src/components/notification/notification-list.tsx` | 신규 |
| `web/src/components/shell/app-shell.tsx` | 수정 |
| `web/src/components/shell/sidebar.tsx` | 수정 |
| `web/src/components/error-message.ts` | 수정 |
| `web/eslint.config.mjs` | 수정 |
| `test/unit/notification.test.ts` | 신규 |
| `test/browser/notifications.spec.ts` | 신규 |
| `test/browser/conversation-requests.spec.ts` | 수정 |
| `docs/frontend/structure.md` | 수정 |
| `docs/frontend/shell.md` | 수정 |
