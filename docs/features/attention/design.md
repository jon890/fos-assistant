# 휴대폰과 PC 로 받는 알림(웹 푸시) 기술 설계

스펙: [`spec.md`](spec.md)(확인 2026-11-20). 선택 이유와 기각한 대안은 [ADR-20261120 / web-push](../../adr/ADR-20261120-web-push.md) 가 갖는다.

## 요구사항 연결

| ID | 해결 방법 |
| --- | --- |
| REQ1 | 웹이 브라우저 구독을 만들어 `PUT /api/v1/push/devices` 로 보낸다. Control Plane 이 같은 주소의 기기를 찾아 갱신하고 없으면 더한다. 화면은 기기 목록의 `endpointHash` 와 자기 구독 주소의 해시를 비교해 「이 기기」 와 단추를 정한다. 서버 목록에 없으면 브라우저 구독이 남아 있어도 단추를 보인다 |
| REQ2 | 웹이 user agent 로 이름을 만들어 등록 요청에 넣는다. 홈 화면 앱(`display-mode: standalone`)이면 브라우저 이름 대신 「앱」 이다 |
| REQ3 | 「알림」 화면의 클라이언트 부품이 지원 여부, 허락 상태, iOS 와 홈 화면 앱 여부, iOS 버전을 읽어 상태 하나를 고른다 |
| REQ4 | Next 메타데이터 경로로 manifest 와 아이콘을 요청마다 만든다. 이름은 `appName()`, 아이콘은 첫 글자다 |
| REQ5 | 알림 저장이 커밋된 뒤 푸시 하위 기능이 그 알림의 사용자 기기 목록을 읽어 기기마다 암호화한 글을 보낸다. 서비스 워커는 받은 글을 그대로 시스템 알림으로 띄운다 |
| REQ6 | 푸시 글의 열 주소는 `/n/<알림 공개 식별자>` 이다. 이 화면이 서버에서 읽음으로 바꾸고 `notificationHref` 의 곳으로 보낸다. 로그인이 없으면 이 화면이 `/signin?next=<인코딩한 그 주소>` 를 직접 만들어 보내고 로그인 뒤 돌아온다. 다른 화면의 로그인 이동은 바꾸지 않는다 |
| REQ7 | 보내기는 커밋 뒤 사건에서 가상 스레드로 돈다. 알림을 만든 트랜잭션과 호출 스레드는 기다리지 않는다 |
| REQ8 | 푸시 서비스 응답으로 기기를 지우거나 다시 시도한다. 로그는 사용자 번호, 기기 번호, 상태 코드, 시도 횟수, 예외 종류만 남긴다 |
| REQ9 | 로그아웃 전에 웹이 `DELETE /api/v1/push/devices/current` 를 부르고 브라우저 구독을 끊는다. 다른 사용자가 같은 주소로 등록하면 등록 트랜잭션이 앞 사용자의 줄에서 그 기기를 뺀다. 관리자가 접근을 끄면 이미 있는 사건 `UserAccessRevoked` 를 받아 그 사용자의 기기 칸을 비운다. 정리 작업은 `app_user` 줄을 지우므로 칸이 함께 사라진다 |
| REQ10 | (PR2) 푸시 설정 칸의 끈 묶음을 보내기 전에 거른다. 묶음은 `NotificationKind` 마다 정한 값이다 |
| REQ11 | (PR2) 보내기 전에 알림의 `created_at` 을 조용한 시간과 비교한다. 걸리면 보내지 않고 버린다 |
| REQ12 | (PR2) 설정 칸에 시간대를 둔다. 없으면 `registeredAt` 이 가장 늦은 기기의 시간대, 그다음 `Asia/Seoul` 이다. `registeredAt` 은 다시 등록할 때도 바뀐다 |
| REQ13 | (PR2) 기기 번호로 이름을 고치고 지운다. 요청자 자신의 줄에서만 찾는다 |

## 책임과 경계

**Control Plane 의 기존 기능 `notification` 안에 `Push` 로 시작하는 클래스를 둔다.** 하위 기능은 `application` 직하 클래스가 많은 기능에만 둔다는 배치 규칙([ADR-20261015 / package-layout](../../../backend/docs/adr/ADR-20261015-package-layout.md))에 따라 `notification.push` 를 따로 만들지 않는다.
`NotificationService` 는 푸시를 부르지 않는다. 알림을 저장할 때 `NotificationCreated` 응용 사건(`application.dto`, 알림 번호, 사용자 번호, 종류, 제목, 본문, 공개 식별자, 만든 시각)을 내고, `PushDispatcher` 가 `@TransactionalEventListener(AFTER_COMMIT)` 로 받는다.
되돌아간 트랜잭션은 사건을 내지 않으므로 「알림과 그 원인은 한 트랜잭션」(ADR-070) 이 그대로다.

| 자리 | 하는 일 |
| --- | --- |
| `notification.domain` | 기기 목록과 푸시 설정의 값 타입, 알림 묶음(`PushGroup`)과 종류의 대응, 조용한 시간 판정 |
| `notification.application` | 기기 등록과 해제, 목록, 보내기 순서(거르기, 기기마다 보내기, 응답 처리), 접근 해제 사건 받기 |
| `notification.infra` | `app_user` 의 두 칸을 읽고 쓰는 전용 쿼리, 웹 푸시 HTTP 보내기(암호화와 VAPID 서명), 서명 키 파일 읽기 |
| `notification.presentation` | `/api/v1/push/**` |
| `notification.config` | `PushProperties` |

**웹**은 화면, 서비스 워커, manifest 와 아이콘, 로그인 뒤 돌아오기, 로그아웃 전 해제를 갖는다. Control Plane 은 화면 상태를 모른다.
Hermes 는 이 기능에 닿지 않는다.

## 인터페이스와 데이터

### 저장

새 테이블을 만들지 않는다(사용자 결정 2026-11-20, 공통 규칙 2026-11-01). `app_user` 에 칸 둘을 더한다.
기기는 사용자와 1:N 이라 `data-schema.md` 의 「테이블을 추가하는 기준」 으로는 새 테이블 몫이다. 그 절에 이 예외와 근거(사용자 결정, 사용자 몇 명, 서버 한 대 전제)를 적는다.

| 칸 | 타입 | 모양 | PR |
| --- | --- | --- | --- |
| `push_devices` | `TEXT NULL` | `{"version":1,"devices":[{"id","endpoint","endpointHash","p256dh","auth","name","timeZone","registeredAt"}]}`. 비면 기기가 없다 | PR1 |
| `push_settings` | `TEXT NULL` | `{"version":1,"muted":["<묶음>"],"quiet":{"enabled","start","end","timeZone"}}`. 비면 모두 켜짐, 조용한 시간 켜짐 22:00~07:00 | PR2 |

- `endpointHash` 는 주소의 SHA-256 hex(`Sha256`)다. 다른 사용자 줄에서 같은 주소를 찾는 `LIKE` 와 화면의 「이 기기」 판정에 쓴다. 주소와 키 원문은 응답과 로그에 내지 않는다
- 한 사용자의 기기는 10대까지다. 11번째를 등록하면 `registeredAt` 이 가장 이른 기기를 뺀다. 칸 크기를 묶으려는 것이고 가족이 쓰는 기기 수로 넉넉하다
- 키 값(`p256dh`, `auth`)은 암호화하지 않는다. 이 값만으로는 보낼 수 없고 푸시 서비스는 우리 VAPID 서명을 요구한다. 서명 비밀 키는 DB 밖에 있다(DECISION16)
- **`AppUser` 엔티티는 두 칸을 넣기와 고치기에서 뺀다.** 주문 상한 컬럼 관례대로 `PushDeviceRepository`(`Repository<AppUser, Long>`)의 전용 쿼리로만 읽고 쓴다. 쓰는 쪽은 같은 트랜잭션에서 그 사용자 줄을 먼저 잠그고 다시 읽는다. 이 칸만 바꾸는 쓰기는 일반 감사 `updated_at` 을 바꾸지 않는다. 백그라운드의 410 정리가 사용자의 마지막 변경 시각을 바꾸지 않게 하려는 것이다
- `push_devices` 의 `auth` 와 주소는 운영 조회 계정에 주지 않는다. 「운영 조회 계정의 계약」 표에 이 칸을 더한다
- 모르는 버전이거나 깨진 JSON 은 기기 없음으로 보지 않고 오류로 올린다. 없음으로 보면 다음 쓰기가 기기를 지운다
- 근거와 관례는 [`backend/docs/data-schema.md`](../../../backend/docs/data-schema.md) 의 `app_user` 절에 「푸시 기기와 설정 컬럼」 으로 적는다

### Control Plane API

로그인한 사용자 자신의 기기와 설정만 다룬다. 요청 본문이 사용자를 정하지 못한다.

| 경로 | 하는 일 | PR |
| --- | --- | --- |
| `GET /api/v1/push/config` | `{enabled, publicKey}`. 서버에 키가 없으면 `enabled: false` 이고 화면은 「이 서버는 아직 알림을 보낼 수 없어요」 를 보인다 | PR1 |
| `GET /api/v1/push/devices` | 기기 목록(`id`, `name`, `registeredAt`, `endpointHash`), 최근 등록 순 | PR1 |
| `PUT /api/v1/push/devices` | 등록과 갱신. 본문 `endpoint`, `keys.p256dh`, `keys.auth`, `name`, `timeZone`. 서버에 키가 없으면 409 `PUSH_NOT_CONFIGURED`, 허용 밖 주소나 길이·시간대 오류는 400 `VALIDATION_FAILED` | PR1 |
| `DELETE /api/v1/push/devices/current` | 본문의 `endpoint` 와 같은 내 기기를 지운다. 없어도 204 | PR1 |
| `PATCH /api/v1/push/devices/{id}`, `DELETE /api/v1/push/devices/{id}` | 이름 고치기와 지우기. 남의 기기와 없는 기기는 같은 404 `PUSH_DEVICE_NOT_FOUND` | PR2 |
| `GET /api/v1/push/settings`, `PUT /api/v1/push/settings` | 끈 묶음과 조용한 시간. 관리자 아닌 사용자가 `ADMIN` 묶음을 보내면 무시한다 | PR2 |

**등록 주소는 알려진 푸시 서비스의 `https` 주소만 받는다.** Control Plane 이 사용자가 준 주소로 POST 하므로, 막지 않으면 내부망으로 요청을 보내는 길이 된다.

- 호스트는 허용 목록의 항목과 정확히 같거나, 항목이 `.` 으로 시작하면 그 접미사로 끝나야 한다(`.notify.windows.com` 은 `a.notify.windows.com` 을 받고 `evilnotify.windows.com` 은 받지 않는다). 기본 목록(FCM, Mozilla, Apple, Microsoft)은 `PushProperties` 가 갖고 설정으로 바꾼다
- 스킴은 `https`, 포트는 없거나 443, userinfo 와 IP 리터럴은 받지 않는다
- 보낼 때 저장된 주소를 같은 규칙으로 다시 검사한다. 설정에서 호스트를 빼면 그때부터 보내지 않는다
- `HttpClient` 는 redirect 를 따르지 않는다
- 이름과 키의 길이 상한도 등록 때 검사한다

### 등록의 순서

**등록은 Control Plane 안에서 하나씩 돈다.** 등록과 해제는 트랜잭션을 열기 전에 프로세스 안의 잠금 하나를 잡고 커밋한 뒤 놓는다. 잠그지 않은 `LIKE` 로는 동시에 같은 주소를 등록한 두 사용자가 서로를 보지 못해 둘 다에게 기기가 남기 때문이다. 서버 한 대 전제(ADR-070)에 기댄다.

1. 같은 `endpointHash` 를 가진 다른 사용자 줄을 찾는다(`push_devices LIKE '%<hash>%'`)
2. 요청자와 찾은 사용자의 줄을 번호 순으로 잠그고 다시 읽는다. 금융 상한, 접근 해제와 같은 순서라 서로 막히지 않는다
3. 다른 사용자 줄에서 그 기기를 빼고, 요청자 줄에서는 같은 주소면 키와 이름, 시간대, `registeredAt` 을 바꾸고 없으면 더한다

보낼 때도 겹침을 막는다. 보내기 전에 같은 `endpointHash` 를 가진 다른 사용자 줄이 있으면 그 기기로 보내지 않고 경고 로그를 남긴다. 잠금이 빠진 경로가 생겨도 남의 알림이 그 기기에 뜨지 않게 하려는 것이다.

### 보내기

`PushDispatcher` 가 커밋 뒤 사건을 받아 `BackgroundTasks` 로 가상 스레드 하나를 띄운다(rule-21).

1. 그 사용자의 기기 목록을 잠그지 않고 읽는다. 비었거나 서버에 키가 없으면 끝낸다
2. (PR2) 끈 묶음과 조용한 시간에 걸리면 끝낸다
3. 글 `{"title","body","url","tag"}` 를 만든다. `url` 은 `/n/<공개 식별자>`, `tag` 는 공개 식별자다. 글은 RFC 8291(`aes128gcm`)로 기기마다 암호화한다
4. 기기마다 POST 한다. 머리는 VAPID(RFC 8292, ES256) `Authorization`, `TTL: 86400`, `Urgency: normal` 이다. 연결과 응답 제한 시간은 `PushProperties` 가 갖는다

| 응답 | 처리 |
| --- | --- |
| 2xx | 끝 |
| 404, 410 | 그 사용자 줄을 잠그고, 보낼 때 읽은 기기와 번호와 `registeredAt` 이 같은 기기가 있으면 뺀다 |
| 429, 5xx, 연결 실패 | 두 번까지 다시 보낸다(1초, 4초 뒤. `Retry-After` 가 있으면 30초 안에서 따른다). 그래도 실패하면 버린다 |
| 그 밖의 4xx | 버리고 경고 로그 |

암호화와 서명은 JDK 의 `KeyPairGenerator`(P-256), `KeyAgreement`(ECDH), HMAC-SHA256(HKDF), `AES/GCM`, `SHA256withECDSAinP1363Format` 으로 쓴다. 외부 라이브러리를 더하지 않는 까닭은 ADR 이 갖는다. HTTP 는 `java.net.http.HttpClient` 다.

### 설정

`assistant.push` 의 `vapid-public-key`(base64url), `vapid-private-key-file`(비밀 키 base64url 한 줄을 담은 파일 경로), `subject`, `allowed-endpoint-hosts`, `connect-timeout`(5s), `request-timeout`(10s), `retry-delays`(1s, 4s), `retry-after-max`(30s), `max-devices`(10) 이다. `@Validated` 로 공개 키, 비밀 키 파일, `subject` 셋이 함께 있거나 함께 없는지 보고 어긋나면 기동을 멈춘다(rule-24). 파일을 읽어 P-256 키로 해석하고 공개 키와 한 쌍인지 보는 일은 KEK 처럼 `notification.infra` 의 빈이 기동 때 하고, 실패하면 기동을 멈춘다.
값은 환경 변수로 넣고 저장소에 적지 않는다. 비밀 키를 파일로 받는 것은 데이터 암호화 KEK(`kek-file`)와 같은 관례다. 어디에 두는지는 운영 저장소가 갖는다.

### 웹

| 자리 | 하는 일 | PR |
| --- | --- | --- |
| `web/src/app/manifest.ts`, `icon.tsx`, `apple-icon.tsx` | 요청마다 앱 이름으로 manifest(`display: standalone`, `start_url: /`)와 첫 글자 아이콘(192, 512, 180)을 만든다 | PR1 |
| `web/public/sw.js` | `push` 에서 글을 시스템 알림으로 띄우고, `notificationclick` 에서 열린 창이 있으면 앞으로 가져와 `url` 로 옮기고 없으면 새로 연다. 글 해석과 창 고르기는 같은 파일의 작은 함수로 둬 node 테스트가 읽는다 | PR1 |
| `web/src/app/settings/notifications/page.tsx` 와 부품, `web/src/components/shell/settings-area.ts` | 설정 묶음 「알림」. `SETTINGS_GROUPS` 에 묶음을 더하고, `/settings/...` 아래면 늘 「에이전트」 를 고르던 `selectedSettingsHref` 를 `/settings` 자체만 「에이전트」 로 고치게 바꾼다. [화면 틀 스펙](../app-shell/spec.md) 의 묶음 설명도 함께 고친다. PR1 은 이 기기 상태와 기기 목록, PR2 는 기기 이름 고치기와 지우기, 묶음 스위치, 조용한 시간 | PR1, PR2 |
| `web/src/lib/push.ts` | 지원과 허락 상태, iOS 판정, 기기 이름, 구독 만들기와 끊기, 주소 해시 | PR1 |
| `web/src/app/api/push/**` | Control Plane 으로 넘기는 경로 | PR1, PR2 |
| `web/src/app/n/[notificationId]/page.tsx` | 서버에서 읽음 표시를 부르고 `notificationHref` 로 `redirect`. 갈 곳이 없거나 읽기가 404 면 알림 목록(`/notifications`, 화면 틀 PR4 뒤에는 그 넘김을 따른다). 로그인이 없으면 `/signin?next=` 로 보낸다 | PR1 |
| `web/src/app/signin/page.tsx` | `next` 를 받아 `redirectTo` 로 넘긴다. 디코딩한 값이 `/` 로 시작하고 `//`, `\`, 제어 문자가 없는 같은 사이트 경로만 받고 아니면 `/`. Auth.js 의 `redirectTo` 가 다른 출처를 거르는지도 테스트로 본다 | PR1 |
| 이름 메뉴의 로그아웃 | 서비스 워커 구독이 있으면 해제 API 와 `unsubscribe()` 를 2초 안에서 기다린 뒤 지금처럼 `/signout` 을 부른다. 실패해도 로그아웃은 막지 않는다 | PR1 |

열기 경로를 `/notifications` 아래에 두지 않는다. [화면 틀 PR4](../app-shell/spec.md) 가 `/notifications` 를 「지금 볼 것」 의 알림 탭으로 넘기는데, 넘기는 방식이 아직 정해지지 않아 하위 경로까지 넘기면 열기가 깨진다. `/n/<id>` 는 그 넘김과 상관없다.

## 실패와 일관성

- **알림을 만든 일은 푸시를 기다리지 않는다.** 커밋 뒤 사건 안에서는 스레드만 띄운다. 스레드 안의 모든 예외는 로그로 끝난다
- 기기 목록을 읽은 뒤 기기가 지워지면 그 기기로 한 번 더 갈 수 있다. 410 을 받으면 지운다. 받아들인다
- 등록과 410 정리가 같은 줄을 동시에 바꾸면 잠금 순서로 하나씩 돈다. 정리는 번호와 `registeredAt` 이 보낼 때와 같은 기기만 뺀다. 그 사이 같은 주소로 다시 등록하면 번호는 같고 `registeredAt` 이 바뀌므로 지우지 않는다(rule-68)
- 서버 한 대 전제(ADR-070)는 바뀌지 않는다. 사건 수신자가 Control Plane 하나다
- 배포 순서: 마이그레이션과 backend 를 먼저 올리고 웹을 올린다. backend 만 올라간 동안 웹은 푸시 API 를 부르지 않는다. 웹만 먼저 올라가면 `config` 가 404 라 화면이 「아직 보낼 수 없어요」 를 보인다
- 서버에 키를 넣지 않고 배포해도 기동한다. `enabled: false` 로 보내기를 건너뛴다

## 검증 전략

| 요구사항 | 경계와 환경 |
| --- | --- |
| REQ5, REQ8 | 암호화는 RFC 8291 부록 A 의 예시 값으로 같은 암호문을 만드는지 단위 테스트로 본다. VAPID 는 만든 JWT 를 공개 키로 검증한다. 보내기는 테스트 안의 가짜 푸시 서버(JDK `HttpServer`)로 201, 410, 500, 지연을 흉내 내 기기 삭제, 다시 시도 횟수, 다른 사용자 기기로 가지 않음을 본다 |
| REQ7 | 가짜 서버가 오류를 내거나 멈춰도 알림을 만드는 서비스가 커밋되고 바로 돌아오는지 통합 테스트 |
| REQ1, REQ9, REQ13 | 등록, 옮기기, 해제, 접근 해제 사건, 10대 상한, 남의 기기 404, 두 사용자의 동시 등록, 보낼 때의 겹침 거르기를 H2 통합 테스트로 본다. 잠금과 `LIKE` 는 CI 의 MySQL 검사가 본다 |
| 주소 제한 | 허용 접미사의 경계, 포트, userinfo, IP 리터럴, 보낼 때 재검사, redirect 를 따르지 않음을 단위 테스트로 본다 |
| REQ10~REQ12 | 묶음 대응이 모든 `NotificationKind` 를 덮는지, 조용한 시간이 자정을 넘는 구간과 시간대를 맞게 판정하는지 단위 테스트 |
| REQ2, REQ3, REQ6 | 웹 node 테스트: 기기 이름, iOS 와 홈 화면 판정, `next` 검증, 서비스 워커의 글 해석과 창 고르기 |
| REQ1~REQ4 화면 | 브라우저 테스트: API 를 흉내 내 「알림」 화면의 상태(받는 중, 단추, 거절, 미지원, 아이폰 안내)를 본다 |
| 실제 기기 | 배포 뒤 아이폰 홈 화면 앱과 PC Chrome 에서 등록, 받기, 누르기, 로그아웃을 사람이 확인한다. 스펙 인수 조건의 실제 환경 확인이다 |

## 결정

| ID | 결정 | 답 | 누가 | 근거 |
| --- | --- | --- | --- | --- |
| DECISION1 | 기기와 설정을 둘 곳 | `app_user` 의 JSON 칸 둘, 전용 쿼리와 줄 잠금 | 사용자 2026-11-20 | ADR 의 「대안 기각」 |
| DECISION2 | 푸시가 알림을 아는 방법 | 커밋 뒤 응용 사건. `NotificationService` 는 푸시를 부르지 않는다 | 작성자 | 알림을 만드는 쪽이 보내기 실패와 지연에 묶이지 않는다. 되돌아간 트랜잭션은 사건이 없다 |
| DECISION3 | 웹 푸시 암호화와 서명 | JDK 로 직접 쓴다 | 작성자 | ADR 의 「대안 기각」 |
| DECISION4 | 등록 주소 제한 | 허용 호스트의 `https` 만 | 작성자 | 사용자가 준 주소로 서버가 요청을 보내므로 내부망 요청을 막는다 |
| DECISION5 | 기기 수 상한 | 10대, 넘으면 가장 오래된 기기를 뺀다 | 작성자 | JSON 칸 크기를 묶는다 |
| DECISION6 | 「이 기기」 판정 | 서버가 주소 해시를 주고 화면이 자기 주소 해시와 비교한다 | 작성자 | 브라우저 저장소의 번호는 사용자가 바뀌거나 서버가 지우면 낡는다(스펙 DECISION21) |
| DECISION7 | 누르면 여는 주소 | `/n/<id>` 서버 화면이 읽음과 넘김을 한다 | 작성자 | 서비스 워커는 세션 쿠키로 API 를 부를 수 있지만 로그인이 풀린 경우를 처리하지 못한다. 로그인 뒤 돌아오기를 한곳에 둔다 |
| DECISION8 | 키 값 암호화 | 하지 않는다 | 작성자 | VAPID 비밀 키 없이 보낼 수 없다. 암호화하면 보낼 때마다 데이터 key 를 풀어야 한다 |
| DECISION9 | 운영 키 자리 | 공개 키와 연락처는 환경 변수, 비밀 키는 환경 변수가 가리키는 파일. 실제 자리는 운영 저장소 | 작성자 | 스펙 DECISION16 |
| DECISION10 | 패키지 자리 | 기존 `notification` 기능 안의 `Push*` 클래스 | 작성자 | 하위 기능 배치 규칙([ADR-20261015 / package-layout](../../../backend/docs/adr/ADR-20261015-package-layout.md)). `notification` 은 하위 기능을 둘 만큼 크지 않다 |
| DECISION11 | 동시 등록 | 프로세스 안 잠금으로 등록을 하나씩 돌리고 보낼 때 겹침을 다시 본다 | 작성자 | 잠그지 않은 `LIKE` 의 경합(독립 검토). 서버 한 대 전제 |
| DECISION12 | 접근을 끈 사용자 | `UserAccessRevoked` 를 받아 기기 칸을 비운다 | 작성자 | 스펙 REQ9. 정리 예약 전까지 줄이 남아 있어도 푸시가 가지 않는다 |

## 결정 기록 연결

| 결정 | 종류 | ADR | 현재 규칙의 원본 |
| --- | --- | --- | --- |
| 스펙 DECISION4·DECISION5·DECISION16, 이 설계 DECISION1·DECISION3·DECISION4 | architecture | [ADR-20261120 / web-push](../../adr/ADR-20261120-web-push.md) | 이 설계, `PushProperties`, `data-schema.md` |
