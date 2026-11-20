# Phase 03. 누르면 열기, 로그인 뒤 돌아오기, 로그아웃 전 해제

**Execution profile**: standard

## 목표

푸시를 누르면 여는 `/n/<id>` 가 알림을 읽음으로 바꾸고 그 곳으로 보내며, 로그인이 풀렸으면 로그인 뒤 같은 곳으로 돌아오게 한다. 로그아웃 전에 이 브라우저의 구독을 지운다.

**범위 외**: 다른 화면의 로그인 이동은 바꾸지 않는다(스펙 DECISION23).

## 컨텍스트

**요구사항**: REQ6, REQ9

**근거 문서**: `docs/features/attention/spec.md`, `docs/features/attention/design.md` 의 「웹」

- 읽음 경로는 Control Plane `POST /api/v1/notifications/{id}/read` 이고 `NotificationView` 를 돌려준다. 없거나 남의 알림이면 404 `NOTIFICATION_NOT_FOUND`.
- 갈 곳은 `web/src/lib/notification.ts` 의 `notificationHref(view)` 가 정한다. 없으면 null.
- 공개 식별자 모양 검사는 `web/src/lib/conversation-id.ts` 의 `isPublicId`.
- 로그인은 `web/src/app/signin/page.tsx` 의 서버 액션이 `signIn("google", { redirectTo: "/" })` 를 부른다.
- 로그아웃은 `web/src/components/shell/account-menu.tsx` 의 `signOut()` 이 `requestSignOut()`(`web/src/lib/sign-out-client.ts`)을 부른다.

## 의도 메모

- 열기 경로를 `/notifications` 아래에 두지 않는다. 화면 틀 PR4 가 `/notifications` 를 넘기게 되어 하위 경로가 깨질 수 있다(설계 「웹」).
- 갈 곳이 없거나 읽기가 실패하면 `/notifications` 로 보낸다. PR4 가 그 주소를 넘기면 그대로 따라간다.
- `next` 는 같은 사이트 경로만 받는다. 디코딩한 값이 `/` 로 시작하고 `//`, `\`, 제어 문자가 없어야 한다. Auth.js 의 기본 `redirect` 콜백도 다른 출처를 막으므로 두 겹이다.
- 로그아웃 해제는 2초 안에서만 기다리고 실패해도 로그아웃을 막지 않는다.

## 작업 항목

### 1. `web/src/lib/push-open.ts`

- `safeNextPath(raw)`: `decodeURIComponent` 한 값을 위 규칙으로 검사해 통과하면 **디코딩한 값**을 돌려준다. 디코딩이 실패하면(`%E0`) `/`.
- `openTarget(result)`: 읽기 결과(성공이면 `NotificationView`)에서 `notificationHref` 또는 `/notifications`.
- `openNotification(id, markRead)`: `isPublicId` 가 아니면 `/notifications`. `markRead(id)` 를 한 번 부르고 그 결과를 `openTarget` 에 넣는다. 화면은 `callControlPlane` 을 넘긴다. 이 함수가 「읽음을 부르고 그 곳으로 보낸다」 를 node 테스트로 증명하는 자리다.
- `signinPathFor(notificationId)`: `/signin?next=<인코딩한 /n/<id>>`.

### 2. `web/src/app/n/[notificationId]/page.tsx`

세션이 없으면 `redirect(signinPathFor(id))`. 있으면 `redirect(await openNotification(id, …callControlPlane 으로 POST read…))`. 접근이 꺼진 401 은 `callControlPlane` 이 이미 세션을 끊으므로 그 흐름을 따른다.

### 3. `web/src/app/signin/page.tsx`

`searchParams.next` 를 `safeNextPath` 로 검사한 뒤 서버 액션의 `redirectTo` 로 넘긴다. 이미 로그인했으면 지금처럼 그대로 둔다.

### 4. 로그아웃 전 해제

`web/src/lib/push.ts` 에 `releaseThisDevice(timeoutMs = 2000)`: 서비스 워커 구독이 있으면 `DELETE /api/push/devices/current` 와 `unsubscribe()` 를 함께 기다리되 2초가 넘으면 그만둔다. 예외를 던지지 않는다. `account-menu.tsx` 의 `signOut()` 이 `requestSignOut()` 전에 부른다.

### 5. 문서

`docs/features/attention.md` 의 「웹 푸시」 에 화면(설정 「알림」), 누르면 `/n/<id>` 가 읽음과 넘김을 하고 로그인 뒤 돌아온다는 것, 로그아웃 전 해제를 더한다. 「갈리는 지점(알림)」 에 로그인이 풀린 기기에서 누름을 더한다.

### 6. 테스트

- `web/test/unit/push-open.test.ts`: `safeNextPath` 의 `//evil`, `/\evil`, `https://x`, `%2F%2Fevil`, `%E0`, 제어 문자, 정상 경로(`%2Fn%2Fabc` → `/n/abc`). `openTarget` 의 대화, 갈 곳 없음, 실패. `openNotification` 이 가짜 `markRead` 를 그 id 로 한 번 부르고, 승인 요청 알림(`APPROVAL_REQUESTED`, `CONVERSATION`)이면 `/chat/<id>?todo=open`, 형식이 아닌 id 면 부르지 않고 `/notifications`.
- `web/test/browser/push-open.spec.ts`:
  - 세션 없는 context 에서 `/n/<uuid>` → `/signin?next=%2Fn%2F<uuid>`.
  - 없는 알림 `/n/<uuid>` → `/notifications`(실제 Control Plane 이 404 를 답한다). 형식이 아닌 id → `/notifications`.
  - 세션 없는 context 에서 `/signin?next=%2Fn%2F<uuid>` 를 열고 로그인 단추를 누르되 `accounts.google.com` 요청은 막는다. `authjs.callback-url` 쿠키가 `<base>/n/<uuid>` 다. `next=%2F%2Fevil.example` 이면 쿠키가 `<base>/` 다. 로그인 화면이 `next` 를 `redirectTo` 로 넘기는지와 다른 출처를 막는지를 함께 본다.
- `web/test/browser/push-settings.spec.ts`: 구독 대역이 있는 상태에서 로그아웃하면 `DELETE /api/push/devices/current` 가 불리고 로그인 화면으로 간다. 해제 요청이 멈춰도 2초 뒤 로그아웃된다.

## 검증

```bash
cd web && pnpm typecheck && pnpm lint && pnpm test:node
cd web && node --test test/unit/push-open.test.ts
cd web && pnpm test:browser push-open.spec.ts push-settings.spec.ts sign-out.spec.ts
```

`sign-out.spec.ts` 는 고치지 않고, 로그아웃 순서를 바꿔도 기존 사례가 그대로 통과하는지 다시 돌린다.

```bash
cd web && pnpm test:browser account-menu.spec.ts
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `web/src/lib/push-open.ts` | 신규 |
| `web/src/app/n/[notificationId]/page.tsx` | 신규 |
| `web/src/app/signin/page.tsx` | 수정 |
| `web/src/lib/push.ts` | 수정 (releaseThisDevice) |
| `web/src/components/shell/account-menu.tsx` | 수정 |
| `docs/features/attention.md` | 수정 |
| `web/test/unit/push-open.test.ts` | 신규 |
| `web/test/browser/push-open.spec.ts` | 신규 |
| `web/test/browser/push-settings.spec.ts` | 수정 |
