# Phase 01. 「지금 볼 것」 탭과 수·점

**Execution profile**: standard

## 목표

알림 목록을 `/now` 의 「알림」 탭으로 옮기고 알림 단추를 없앤다. 확인하러 갈 곳을 「지금 볼 것」 하나로 만든다.

**범위 외**: 화면 머리와 폭을 맞추는 일(PR5), 알림을 만드는 Control Plane 규칙, 웹 푸시의 `/n/<id>` 주소.

## 컨텍스트

**요구사항**: REQ16, REQ17, REQ18

**근거 문서**: `docs/features/app-shell/spec.md`, `docs/features/app-shell/design.md` 의 「「지금 볼 것」 의 탭(PR4)」 과 「「지금 볼 것」 의 수와 점(PR4)」, `docs/features/attention.md`

- 지금 `/now` 는 `web/src/app/now/page.tsx` 가 서버 부품 `NowScreen`(`web/src/components/now/now-screen.tsx`)을 그리고 그 안에 h1 이 있다.
- 지금 `/notifications` 는 `NotificationList`(`web/src/components/notification/notification-list.tsx`)를 그리고 그 안에 h1 「알림」 이 있다.
- 사이드바 「지금 볼 것」 은 `web/src/components/shell/now-link.tsx` 가 스스로 `readNowCount()` 로 수를 읽는다. 읽지 않은 알림 수는 `NotificationsProvider` 의 `useUnreadNotifications()` 가 준다.
- 알림 단추 `NotificationBell` 은 `sidebar.tsx` 와 `mobile-top-bar.tsx` 두 곳에서 쓴다.
- Tabs 는 `radix-ui` 패키지에 있다. 감싸는 방식은 `web/src/components/ui/dropdown-menu.tsx` 를 따른다.

## 의도 메모

- `router.replace` 로 탭을 바꾸면 서버 화면을 다시 받는다. `history.replaceState` 를 쓴다(design DECISION12).
- 사이드바 두 벌과 위 막대가 각자 수를 읽지 않게 provider 하나로 모은다(design DECISION13).

## 작업 항목

### 1. `web/src/lib/attention.ts`

`nowIndicator(nowCount, unreadCount)` 가 `{ kind: "count", text }`, `{ kind: "dot" }`, `null` 가운데 하나를 돌려준다. `nowLinkLabel(nowCount, hasUnread)` 가 design 의 표대로 이름을 만든다.

### 2. `web/src/components/shell/now-status.tsx`, `now-badge.tsx`

`NowStatusProvider({ enabled })` 가 경로 변화, `ATTENTION_CHANGED_EVENT`, `NOTIFICATIONS_CHANGED_EVENT` 에 `readNowCount()` 를 부른다. `useNowStatus()` 는 `{ nowCount, unreadCount }` 다. `NowBadge` 는 수(`data-testid="now-count"`)나 점(`data-testid="now-dot"`)을 그린다. `app-shell.tsx` 가 `NotificationsProvider` 안에 provider 를 둔다.

### 3. 사이드바와 위 막대

`now-link.tsx` 는 provider 값을 쓰고 `NowBadge` 를 붙인다. `sidebar.tsx` 에서 `NotificationBell` 을 빼고, `mobile-top-bar.tsx` 의 알림 단추 자리에 `/now` 로 가는 `Inbox` 단추를 둔다. `notification-bell.tsx` 를 지운다.

### 4. 탭

`web/src/components/ui/tabs.tsx`(radix Tabs 감싸기), `web/src/components/now/now-tabs.tsx`(탭 이름의 수, 주소 바꾸기, 알림 탭의 늦은 마운트). `now/page.tsx` 가 h1 과 `NowTabs` 를 그리고 `searchParams.tab` 을 넘긴다. `NowScreen` 과 `NotificationList` 에서 h1 과 바깥 폭을 뺀다. `notifications/page.tsx` 는 `redirect("/now?tab=notifications")` 다.

### 5. 테스트

- `test/repo/attention.test.ts`: `nowIndicator` 와 `nowLinkLabel` 의 네 경우. `nowLinkLabel` 의 둘째 인자는 기본값 `false` 라 `now-card.tsx` 는 고치지 않는다.
- `web/test/browser/notifications.spec.ts`: 탭 흐름, 넘기기, 빈 목록, 점과 수, 종 단추 없음, 알림 사건 뒤 수 다시 읽기, 360px 위 막대 단추.
- `web/test/browser/now.spec.ts` 의 「알림을 모두 읽어도 「지금 볼 것」 의 수는 그대로다」 를 탭과 `now-dot` 으로 고친다.

### 6. 문서

`docs/features/attention.md` 의 두 수 규칙과 「화면(알림)」, `web/docs/code-architecture.md` 의 화면 목록·사이드바 구역 표·알림 단추 문단·위 막대, `docs/features/app-shell.md` 의 구현된 흐름.

## 인수 검증 계획

| 요구사항 | 준비 조건 | 실행 절차 | 기대 결과 | 실행 환경 | 실행 담당 | 선행 조건 | 시점 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| REQ16 | 알림 대역(갈 곳 있는 줄 하나, 없는 줄 하나) | `pnpm test:browser notifications` 의 탭 흐름과 넘기기 사례 | `/now` 에 탭 둘, 처음은 「할 것」, `/notifications` 는 「알림」 탭, 줄을 누르면 읽음과 대화 이동 | 로컬 브라우저 테스트(가짜 Control Plane, 넓은 화면과 mobile 프로젝트) | worker | 없음 | PR 전 |
| REQ17 | 할 것 수 대역 3 과 0, 읽지 않은 알림 대역 | `pnpm test:browser notifications` 의 수·점 사례, `node --test test/repo/attention.test.ts` | 3 이면 수, 0 이고 알림이 있으면 점, 종 단추 없음, 사건 뒤 수 다시 읽기, 360px 위 막대 단추 | 로컬 브라우저 테스트와 노드 테스트 | worker | 없음 | PR 전 |
| REQ17 | 승인 대기 알림 하나와 할 것 하나 | `pnpm test:browser now.spec` 의 「알림을 모두 읽어도」 사례 | 모두 읽은 뒤 점은 사라지고 수 1 은 그대로 | 로컬 브라우저 테스트 | worker | 없음 | PR 전 |
| REQ18 | 할 것이 있는 사용자 | `now.spec.ts` 의 새 대화 화면 한 줄 사례를 그대로 돌린다 | 「확인할 것 N건」 한 줄이 보인다 | 로컬 브라우저 테스트 | worker | 없음 | PR 전 |
| REQ16, REQ17 | 배포된 화면 | 휴대폰과 PC 에서 지금 볼 것의 두 탭과 점·숫자를 본다 | 두 탭과 표시가 보인다 | 운영 배포 뒤 실제 기기 | 코디네이터 | 배포 뒤 확인 | PR 뒤 |

## 검증

```bash
cd web && pnpm lint && pnpm typecheck && pnpm format:check
node --test test/repo/attention.test.ts
cd web && node --test test/unit/notification.test.ts
cd web && pnpm test:browser test/browser/notifications.spec.ts
cd web && pnpm test:browser test/browser/now.spec.ts
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `web/src/lib/attention.ts` | 수정 |
| `web/src/components/shell/now-status.tsx` | 신규 |
| `web/src/components/shell/now-badge.tsx` | 신규 |
| `web/src/components/shell/now-link.tsx` | 수정 |
| `web/src/components/shell/sidebar.tsx` | 수정 |
| `web/src/components/shell/mobile-top-bar.tsx` | 수정 |
| `web/src/components/shell/app-shell.tsx` | 수정 |
| `web/src/components/notification/notification-bell.tsx` | 삭제 |
| `web/src/components/notification/notification-list.tsx` | 수정 |
| `web/src/components/ui/tabs.tsx` | 신규 |
| `web/src/components/now/now-tabs.tsx` | 신규 |
| `web/src/components/now/now-screen.tsx` | 수정 |
| `web/src/app/now/page.tsx` | 수정 |
| `web/src/app/notifications/page.tsx` | 수정 |
| `test/repo/attention.test.ts` | 수정 |
| `web/test/browser/notifications.spec.ts` | 수정 |
| `web/test/browser/now.spec.ts` | 수정 |
| `docs/features/attention.md` | 수정 |
| `web/docs/code-architecture.md` | 수정 |
| `docs/features/app-shell.md` | 수정 |
