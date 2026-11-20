# Phase 02. 설정 「알림」 화면과 기기 등록

**Execution profile**: standard

## 목표

설정에 「알림」 묶음과 `/settings/notifications` 화면을 두고, 이 기기의 상태(받는 중, 단추, 거절, 미지원, 아이폰 안내, 서버 꺼짐)와 내 기기 목록을 보인다. 「이 기기에서 알림 받기」 로 구독을 만들어 등록한다.

**범위 외**: 기기 이름 고치기와 지우기, 묶음 스위치, 조용한 시간(PR2). 로그아웃 전 해제(phase 03).

## 컨텍스트

**요구사항**: REQ1, REQ2, REQ3

**근거 문서**: `docs/features/attention/spec.md`, `docs/features/attention/design.md` 의 「Control Plane API」 와 「웹」

- Control Plane 경로: `GET /api/v1/push/config` → `{enabled, publicKey}`, `GET /api/v1/push/devices` → `[{id, name, registeredAt, endpointHash}]`, `PUT /api/v1/push/devices` 본문 `{endpoint, keys:{p256dh, auth}, name, timeZone, publicKey}`, `DELETE /api/v1/push/devices/current` 본문 `{endpoint}`(204). 코드는 `backend/.../notification/presentation/PushController.java`, `PushDtos.java`.
- `publicKey` 칸은 서버 비밀값 기능(`docs/features/secret-store/design.md`, 브랜치 jon890/secret-store-spec 의 REQ7, DECISION8)이 더할 등록 확인용이다. 지금 백엔드는 모르는 칸을 무시한다(Spring 기본 `FAIL_ON_UNKNOWN_PROPERTIES=false`). 그 PR 이 409 `PUSH_KEY_CHANGED` 를 돌려주면 화면은 구독을 끊고 다시 구독해 한 번 더 등록한다.
- 웹 경로는 `web/src/app/api/notifications/route.ts` 처럼 `callControlPlane` 으로 넘긴다(rule-55).
- 설정 묶음은 `web/src/components/shell/settings-area.ts` 의 `SETTINGS_GROUPS` 하나가 정한다. `selectedSettingsHref` 는 지금 `/settings/...` 모두를 「에이전트」 로 고른다.
- 화면 문구는 rule-58, 화면 상태는 rule-69 를 따른다.

## 의도 메모

- 「이 기기」 판정은 브라우저 저장소의 번호가 아니라, 내 구독 주소의 SHA-256 hex 를 서버 목록의 `endpointHash` 와 비교한다(설계 DECISION6). 서버 목록에 없으면 브라우저 구독이 남아 있어도 다시 등록하지 않고 단추를 보인다(스펙 DECISION21).
- 화면 상태 판정은 순수 함수 `pushScreenState(input)` 로 두고 node 테스트가 표로 본다. 브라우저 API 를 읽는 부분은 얇게 둔다.
- 「알림」 묶음은 「비서」 다음에 둔다. 묶음 순서는 스펙이 정하지 않았고, 자주 여는 설정을 위에 둔다.
- 아이폰 안내 그림 세 장은 이미지 파일 대신 `lucide-react` 아이콘과 글로 만든 카드 셋이다. 고치기 쉽고 테마를 따른다.

## 작업 항목

### 1. `web/src/lib/push.ts`

- `deviceName(userAgent, standalone)`: 「iPhone 앱」, 「iPad Safari」, 「Mac Chrome」, 「Windows Edge」, 「Android Chrome」 처럼 OS 와 브라우저. 홈 화면 앱이면 브라우저 대신 「앱」. 모르면 「브라우저」.
- `iosVersion(userAgent)`: iPhone, iPad, 그리고 터치가 있는 Mac 모양 iPad 를 iOS 로 보고 버전을 돌려준다. 아니면 null.
- `pushScreenState({serverEnabled, supported, permission, ios, standalone, registered})`: `server-off`, `ios-old`, `ios-install`, `unsupported`, `denied`, `registered`, `ready` 중 하나. 순서는 서버 꺼짐 → iOS 16.4 미만 → iOS 브라우저 탭 → 미지원 → 거절 → 등록됨 → 단추.
- `endpointHash(endpoint)`: `crypto.subtle.digest("SHA-256")` 의 소문자 hex.
- `subscribe(publicKey)`: 구독할 때만 `navigator.serviceWorker.register("/sw.js")` 를 부르고 `pushManager.subscribe({userVisibleOnly: true, applicationServerKey})`. 기존 구독의 `options.applicationServerKey` 가 지금 공개 키와 다르면 끊고 다시 만든다.
- `currentSubscription()`, `unsubscribe()`: `navigator.serviceWorker.getRegistration()` 만 쓴다. 화면을 보기만 하거나 로그아웃할 때 서비스 워커를 새로 설치하지 않는다. `"serviceWorker" in navigator` 가 거짓이면 null.
- `registerBody(subscription, name, timeZone, publicKey)`: 등록 요청 본문 `{endpoint, keys:{p256dh, auth}, name, timeZone, publicKey}`. `timeZone` 은 `Intl.DateTimeFormat().resolvedOptions().timeZone`.
- 등록이 409 `PUSH_KEY_CHANGED` 면 구독을 끊고 config 를 다시 읽어 새 공개 키로 구독한 뒤 한 번만 다시 등록한다. 409 `PUSH_NOT_CONFIGURED` 면 서버 꺼짐 상태로 바꾼다.

### 2. `web/src/app/api/push/**`

`config/route.ts`(GET), `devices/route.ts`(GET, PUT), `devices/current/route.ts`(DELETE). 본문은 JSON 으로 읽어 그대로 넘기고 오류는 `errorResponse`.

### 3. 설정 묶음과 화면

- `settings-area.ts`: `{ label: "알림", links: [{ href: "/settings/notifications", label: "알림", icon: Bell }] }` 를 「비서」 다음에. `selectedSettingsHref` 는 `/settings` 자체만 「에이전트」 이고, 아래 주소는 그 줄을 고른다.
- `web/src/app/settings/notifications/page.tsx`: 로그인 없으면 `/signin`. 제목 「알림」, 설명 한 줄, `<PushSettings />`.
- `web/src/components/notification/push-settings.tsx`(client): config 와 기기 목록을 읽고 상태를 골라 그린다. `ready` 는 「이 기기에서 알림 받기」, 누르면 `Notification.requestPermission()` → 구독 → PUT → 목록 다시 읽기. `registered` 는 「이 기기는 알림을 받고 있어요」 와 「이 기기에서 그만 받기」(DELETE current 와 `unsubscribe`). 기기 목록은 이름, 등록한 날, 「이 기기」 표시.
- `web/src/components/notification/ios-install-guide.tsx`: 「공유」, 「홈 화면에 추가」, 「앱에서 다시 열기」 카드 셋.

### 4. 문서

- `docs/features/app-shell/spec.md` 의 REQ4(인수 조건의 「묶음 넷」 포함), DECISION9 묶음 설명에 「알림」 을 더한다(설계 「웹」 표가 정함).
- `web/docs/code-architecture.md` 의 화면 목록과 「설정 틀」 에 `/settings/notifications` 를 더하고, 설정 묶음 아이콘 목록이 있으면 `Bell` 을 더한다.

### 5. 테스트

- `web/test/unit/push.test.ts`: `deviceName` 표, `iosVersion`, `pushScreenState` 의 우선순위.
- `web/test/node/settings-area.test.ts`: 묶음 다섯, `/settings/notifications` 가 「알림」 을 고른다.
- `web/test/browser/settings.spec.ts`: 묶음 수 다섯과 묶음 목록(「알림」 포함).
- `web/test/browser/push-settings.spec.ts`: init script 로 `Notification`, `navigator.serviceWorker`, `PushManager` 를 대역하고 `/api/push/*` 를 route 로 대역해 단추 → 허락 → 목록 한 줄과 「이 기기」, 거절 안내, 미지원 안내, 아이폰 Safari 탭 안내 셋, 홈 화면 앱(`display-mode: standalone`)이면 단추, 서버 꺼짐 안내, 서버에서 지운 기기면 단추가 다시 보임을 본다. 등록 PUT 본문의 `timeZone` 이 `Asia/Seoul`(playwright 설정의 `timezoneId`), `name` 이 user agent 로 만든 이름, `keys` 와 `publicKey` 가 있음을 본다. PUT 이 409 `PUSH_KEY_CHANGED` 를 한 번 돌려주면 다시 구독하고 PUT 이 두 번 간다.

## 검증

```bash
cd web && pnpm typecheck && pnpm lint && pnpm test:node
cd web && node --test test/unit/push.test.ts test/node/settings-area.test.ts
cd web && pnpm test:browser push-settings.spec.ts settings.spec.ts
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `web/src/lib/push.ts` | 신규 |
| `web/src/app/api/push/config/route.ts` | 신규 |
| `web/src/app/api/push/devices/route.ts` | 신규 |
| `web/src/app/api/push/devices/current/route.ts` | 신규 |
| `web/src/components/shell/settings-area.ts` | 수정 |
| `web/src/app/settings/notifications/page.tsx` | 신규 |
| `web/src/components/notification/push-settings.tsx` | 신규 |
| `web/src/components/notification/ios-install-guide.tsx` | 신규 |
| `docs/features/app-shell/spec.md` | 수정 |
| `web/docs/code-architecture.md` | 수정 |
| `web/test/unit/push.test.ts` | 신규 |
| `web/test/node/settings-area.test.ts` | 수정 |
| `web/test/browser/settings.spec.ts` | 수정 |
| `web/test/browser/push-settings.spec.ts` | 신규 |
