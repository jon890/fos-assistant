# 사용자 브라우저

사용자마다 하나씩 두는 브라우저의 표, 상태, API 계약이다.
결정과 근거는 [ADR-20261007 / user-browser](../adr/ADR-20261007-user-browser.md) 가 갖는다.
proxy 의 정책과 이미지, 망, 프로필 디렉터리의 위치는 운영 값이라 `fos-home-infra` 가 갖는다.

## 표

### `user_browser`

| 칸 | 타입 | 빈 값 | 뜻 |
| --- | --- | --- | --- |
| `id` | BIGINT | 아니다 | |
| `user_id` | BIGINT | 아니다 | `app_user.id`. 유일 |
| `status` | VARCHAR(16) | 아니다 | `STOPPED`, `STARTING`, `RUNNING`, `STOPPING`, `FAILED` |
| `profile_key` | CHAR(64) | 아니다 | 프로필 디렉터리 이름. `SHA-256("u" + user_id)` 의 16진수. 첨부 디렉터리 키와 같은 계산이지만 루트가 다르다 |
| `container_id` | VARCHAR(80) | 그렇다 | 켜져 있을 때만 |
| `last_error` | VARCHAR(40) | 그렇다 | `FAILED` 의 까닭. Control Plane 이 정한 코드만 |
| `last_active_at` | DATETIME(6) | 그렇다 | 켠 시각이나 `touch` 가 마지막으로 기록한 시각. 1분에 한 번까지만 쓴다 |
| `started_at` | DATETIME(6) | 그렇다 | |
| `created_at`, `updated_at` | DATETIME(6) | 아니다 | |
| `version` | BIGINT | 아니다 | 낙관적 잠금 |

쿠키, 저장소, 열린 주소, 화면 프레임은 이 표에 없다.

## 상태 전이

| 요청 | 시작 상태 | 하는 일 | 끝 상태 |
| --- | --- | --- | --- |
| 만들기 | 줄 없음 | 지우다 만 프로필 디렉터리가 남아 있으면 비운 뒤 새로 만든다 | `STOPPED` |
| 켜기 | `STOPPED`, `FAILED` | `FAILED` 줄에 컨테이너 번호가 남아 있으면 먼저 그 컨테이너를 지우고 번호를 비운다. 동시 수를 센다. 같은 프로필 키의 남은 컨테이너를 지운다. proxy 로 컨테이너를 만들고 켠다. CDP 의 `/json/version` 이 답할 때까지 30초 기다린다. 그 사이 컨테이너가 끝나면 더 기다리지 않는다 | `RUNNING`. 실패하면 컨테이너를 지우고 `FAILED`. `FAILED` 줄에 번호가 남은 컨테이너를 지우지 못하면 `BROWSER_STOP_FAILED` 로 거절하고 `FAILED` 그대로. 같은 프로필 키의 남은 컨테이너를 지우지 못하면 `start_failed` 로 `FAILED` |
| 끄기 | `RUNNING`, `FAILED` | 컨테이너를 멈추고 지운다 | `STOPPED` |
| 지우기 | `STOPPED`, `RUNNING`, `FAILED` | 끄기를 한 뒤 끈 줄을 그 버전으로 지우고, 지운 뒤에 프로필 디렉터리를 지운다. | 줄 없음. 그 사이 다른 전이가 줄을 바꿨으면 `BROWSER_BUSY` 이고 줄과 프로필이 남는다 |
| 자동 중지 | `RUNNING` | `last_active_at` 이 유휴 시간보다 오래고 쓰는 중인 핸들(`BrowserUsage`)이 없다. 멈추기 직전에 줄을 다시 읽어 아직 유휴인지 본다 | `STOPPED` |
| 사용자 끄기 | `RUNNING`, `FAILED` | 관리자가 허용 목록에서 사용자를 끄면 끄기가 커밋된 뒤 끄기를 한다. 프로필과 줄은 남긴다 | `STOPPED` |

동시 수는 `STARTING` 과 `RUNNING` 인 줄, 끄다 실패해 `container_id` 가 남은 `FAILED` 줄을 센다. 남은 컨테이너도 돌고 있을 수 있기 때문이다. 셀 때 `user_browser` 의 켜기를 한 번에 하나만 하도록 잠근다.
이미 `RUNNING` 인 브라우저를 켜거나 `STOPPED` 인 브라우저를 끄면 아무것도 하지 않고 지금 상태를 돌려준다.
`STARTING` 이나 `STOPPING` 인 줄에 다른 전이를 요청하면 `BROWSER_BUSY` 다. 지우기와 사용자 끄기도 그렇다.
사용자 끄기가 `BUSY` 이거나 proxy 호출이 실패하면 로그만 남긴다.
다음 점검이 허용 목록에서 꺼진 사용자의 `RUNNING` 과 `FAILED` 를 다시 보고 끈다.
`STARTING` 과 `STOPPING` 은 그 점검도 건너뛰고, 켜기가 끝나면 그다음 점검이 끄고 끝나지 못하면 상태 맞추기가 정한다.

끄다가 proxy 호출이 실패하면 `FAILED` 와 `stop_failed` 를 남긴다. 켜다가 실패한 코드는 `start_failed`, `start_timeout`, `start_exited` 다.
`start_exited` 는 CDP 를 기다리는 동안 컨테이너가 끝난 것이다. 끝난 컨테이너는 망 주소가 없어 30초를 다 기다려도 답하지 않으므로, 주소가 없을 때 컨테이너 상태를 보고 바로 실패로 둔다.
종료 코드는 경고 로그에만 남긴다. 화면과 응답에는 싣지 않고, Chrome 의 출력도 남기지 않는다.

### 한 프로필에 한 컨테이너

프로필 디렉터리는 한 번에 한 컨테이너만 쓴다. 이미지가 시작할 때 Chrome 의 프로필 잠금(`SingletonLock`, `SingletonSocket`, `SingletonCookie`)을 지우기 때문이다.
Chrome 은 정상 종료에서도 이 잠금을 남긴다. 잠금에는 앞 컨테이너의 호스트 이름이 적혀 있어, 지우지 않으면 다음 Chrome 은 다른 컴퓨터가 쓰는 프로필로 보고 종료 코드 21 로 끝난다.
그래서 잠금을 지우는 쪽은 이미지이고, 두 컨테이너가 한 프로필을 함께 쓰지 않게 하는 쪽은 Control Plane 이다.
이미지는 잠금을 지우기 전에 프로필에 파일 잠금을 잡고, 다른 컨테이너가 쥐고 있으면 프로필을 건드리지 않고 끝난다. 이때 켜기는 `start_exited` 다.
켜기는 컨테이너를 만들기 전에 proxy 목록에서 같은 프로필 키 라벨의 컨테이너를 모두 지운다. 실패한 켜기에서 지우지 못해 번호 없이 남은 컨테이너도 여기서 지운다. 목록을 읽거나 지우지 못하면 새 컨테이너를 만들지 않고 `start_failed` 다.

자동 중지와 꺼진 사용자의 브라우저 끄기, 상태 맞추기는 `assistant.browser.sweep-interval`(기본 `1m`)마다 돌고, 기동할 때 한 번 돈다. 기능이 꺼져 있으면 돌지 않는다.
점검은 끄다 실패해 컨테이너가 남은 `FAILED` 를 다시 끈다.
점검 중 DB 예외가 나면 경고 로그만 남기고 기동을 멈추지 않는다.
상태 맞추기는 proxy 의 브라우저 컨테이너 목록과 표를 견준다.

| 표와 실제 | 맞추는 것 |
| --- | --- |
| `RUNNING` 인데 그 컨테이너가 없거나 꺼져 있다 | 남은 컨테이너를 지우고 `STOPPED` |
| `STARTING` 이나 `STOPPING` 이 2분 넘게 그대로다 | 그 키의 컨테이너를 지우고 `STOPPED`. 재기동으로 끊긴 전이가 여기서 정해진다 |
| 컨테이너 라벨의 키에 해당하는 줄이 없거나 그 줄이 `STOPPED` 다 | 컨테이너를 지운다 |
| `RUNNING` 이나 `FAILED` 인 줄이 가리키지 않는 같은 키의 컨테이너 | 컨테이너를 지운다 |

## 설정

env 로 받는다. 기본값이 있는 값은 코드를 바꾸지 않고 설치 설정으로 바꾼다.

| 키 | env | 뜻 | 기본값 |
| --- | --- | --- | --- |
| `assistant.browser.enabled` | `ASSISTANT_BROWSER_ENABLED` | 꺼져 있으면 상태 조회 말고 모든 쓰기가 503 이다. 아래 값이 비어도 기동한다 | `false` |
| `assistant.browser.proxy-url` | `ASSISTANT_BROWSER_PROXY_URL` | 브라우저 proxy 의 주소 | 없음 |
| `assistant.browser.image` | `ASSISTANT_BROWSER_IMAGE` | 컨테이너 이미지 | 없음 |
| `assistant.browser.network` | `ASSISTANT_BROWSER_NETWORK` | 컨테이너를 붙이는 망. 그 망의 주소로 CDP 에 닿는다 | 없음 |
| `assistant.browser.cdp-port` | `ASSISTANT_BROWSER_CDP_PORT` | 컨테이너 안에서 CDP 를 받는 포트 | 없음 |
| `assistant.browser.profile-root` | `ASSISTANT_BROWSER_PROFILE_ROOT` | Control Plane 이 보는 프로필 루트 | 없음 |
| `assistant.browser.profile-host-root` | `ASSISTANT_BROWSER_PROFILE_HOST_ROOT` | 같은 루트를 Docker 호스트에서 본 경로. 생성 요청의 `Binds` 에 쓴다 | 없음 |
| `assistant.browser.profile-mount` | `ASSISTANT_BROWSER_PROFILE_MOUNT` | 컨테이너 안에서 프로필 디렉터리를 붙이는 경로. 이미지가 쓰는 경로와 같게 둔다 | 없음 |
| `assistant.browser.memory-mb` | `ASSISTANT_BROWSER_MEMORY_MB` | 컨테이너 메모리 상한(MB). 스왑은 주지 않는다 | `1024` |
| `assistant.browser.cpu` | `ASSISTANT_BROWSER_CPU` | 컨테이너가 쓰는 CPU 수 | 없음 |
| `assistant.browser.pids-limit` | `ASSISTANT_BROWSER_PIDS_LIMIT` | 컨테이너 안의 프로세스 수 상한 | 없음 |
| `assistant.browser.shm-mb` | `ASSISTANT_BROWSER_SHM_MB` | `/dev/shm` 크기(MB) | 없음 |
| `assistant.browser.max-running` | `ASSISTANT_BROWSER_MAX_RUNNING` | 동시에 켤 수 있는 수. 1 이상이고 상한은 두지 않는다 | `2` |
| `assistant.browser.idle-timeout` | `ASSISTANT_BROWSER_IDLE_TIMEOUT` | 자동 중지까지의 유휴 시간. `10m` 같은 Duration 형식이다 | `10m` |
| `assistant.browser.start-timeout` | | 켠 뒤 CDP 가 답하기를 기다리는 시간 | `30s` |
| `assistant.browser.sweep-interval` | | 자동 중지와 상태 맞추기를 도는 간격 | `1m` |
| `assistant.browser.screen-timeout` | | 로그인 화면 하나가 열려 있을 수 있는 시간. 넘으면 `closed`(`timeout`) | `30m` |
| `assistant.browser.gateway-base-url` | `ASSISTANT_BROWSER_GATEWAY_BASE_URL` | Hermes 가 중계에 닿는 주소. `http` 나 `https` 이고 끝이 `/internal/browser-gateway` 다. 호스트는 영문자와 숫자, `.`, `-` 만 쓰고 경로 조각은 영문자와 숫자, `.`, `_`, `~`, `-` 만 쓴다. 아래 「중계」 | 없음 |
| `assistant.browser.gateway-secret` | `ASSISTANT_BROWSER_GATEWAY_SECRET` | 접근 표식을 서명하는 비밀값. 32자 이상. 운영 비밀값이다 | 없음 |

이미지, 망, 자원, 프로필 루트는 proxy 정책이 강제한다. Control Plane 은 정책과 같은 값을 운영 설정으로 받아 생성 요청에 싣는다.
켜져 있는데 기본값이 없는 값이 비어 있으면 기동을 멈춘다. 중계의 두 값은 예외다. 둘 중 하나라도 비면 중계만 꺼지고 기동한다.

### Docker proxy 요청의 길이

Docker proxy 는 chunked 요청을 거절하므로 Control Plane 은 요청 본문을 버퍼링해
실제 바이트 수를 `Content-Length` 로 보낸다. 본문 없는 제어 요청은 길이가 0 이다.
버퍼링은 작은 Docker 제어 요청에만 적용하고 연결·응답 timeout 은 유지한다.

회귀 시험은 운영 생성자의 HTTP client 로 실제 TCP 서버를 호출한다.
가짜 proxy 는 chunked 요청과 길이가 없는 쓰기 요청을 거절하며, 생성·시작·정지·삭제가
통과하는지와 생성 JSON 의 바이트 수가 전송 길이와 같은지 확인한다.

## API

모두 웹의 서버 라우트를 거친다. 요청자는 토큰의 사용자다.

| 메서드와 경로 | 하는 일 |
| --- | --- |
| `GET /api/browser` | 내 브라우저의 상태. 기능이 꺼져 있어도 200 이고 `{enabled: false}` 만 준다. 없으면 `{enabled: true, exists: false, idleTimeoutSeconds}` |
| `POST /api/browser` | 내 브라우저를 만든다. `STOPPED` 로 생긴다. 이미 있으면 `BROWSER_EXISTS`(409) |
| `POST /api/browser/start`, `POST /api/browser/stop` | 켜기와 끄기 |
| `DELETE /api/browser` | 지우기. 본문 없이 204 |
| `GET /api/browser/screen?url=<시작 주소, 선택>` | 로그인 화면 SSE. 아래 「로그인 화면」 |
| `POST /api/browser/screen/input` | 화면 입력. 본문 없이 204 |
| `GET /api/admin/browsers` | 관리자. 모든 브라우저의 사용자, 상태, 시각 |
| `POST /api/admin/browsers/{id}/stop`, `DELETE /api/admin/browsers/{id}` | 관리자. 끄기와 지우기. 지우기는 본문 없이 204 |

Control Plane 의 경로는 같은 이름에 `/api/v1` 을 붙인 것이다(`/api/v1/browser`, `/api/v1/admin/browsers`).

내 브라우저 응답은 `{enabled, exists, status, lastError, startedAt, lastActiveAt, idleTimeoutSeconds}` 다.
관리자 목록은 줄마다 `{id, userId, userName, status, lastError, startedAt, lastActiveAt}` 이다. 컨테이너 번호와 프로필 키는 싣지 않는다.
관리자 목록은 기능이 꺼져 있어도 읽는다. 쓰기는 기능이 꺼져 있으면 503 이다.

오류 코드는 `BROWSER_NOT_FOUND`(404), `BROWSER_DISABLED`(503), `BROWSER_CAPACITY`(409), `BROWSER_BUSY`(409, 다른 전이가 진행 중), `BROWSER_START_FAILED`(502), `BROWSER_EXISTS`(409), `BROWSER_STOP_FAILED`(502, 줄은 `FAILED` 로 남고 다시 끌 수 있다), `BROWSER_SCREEN_CLOSED`(409, 열린 화면이 없다) 다.

## 로그인 유지

끌 때 컨테이너를 지우므로 로그인은 프로필 디렉터리에 남은 것만 다음 기동으로 이어진다.
프로필을 만들거나 켤 때 `Default/Preferences` 가 없으면 `{"session":{"restore_on_startup":1}}` 를 주인만 읽는 권한으로 써 둔다.
Chrome 의 「이전 세션 이어서 열기」 라서, 정상 종료한 뒤 다음 기동에서 세션 쿠키도 돌아온다. 끄기는 SIGTERM 뒤 10초를 기다리므로 정상 종료다.
이미 있는 설정 파일은 Chrome 이 고쳐 쓰는 것이라 건드리지 않는다. 그 자리의 링크는 따라가지 않는다.

QR 로그인은 세션 쿠키만 준다(2026-10-08 실측). 그래서 QR 로그인의 유지는 이 설정에 기댄다.
운영에서 QR 로그인, 끄기, 켜기를 한 번 왕복해 로그인이 남는지 확인한다. 남지 않으면 아이디와 비밀번호, 「로그인 상태 유지」 로 안내를 바꾼다.
같은 실측에서 headless 는 QR 로그인 주소로 바로 가면 막히고, 일반 로그인 화면에서 QR 로 바꾸면 통과했다. 그래서 화면의 시작 주소는 호출자가 고른다.

## 로그인 화면

`GET /api/v1/browser/screen` 은 브라우저를 켜고(꺼져 있으면) 지금 탭에 붙어 SSE 를 연다.
`url` 은 `http`, `https` 만 받고 열 때 그 주소로 간다. 켜기 실패와 기능 꺼짐, 틀린 주소는 SSE 를 열기 전에 JSON 오류다. `Accept: text/event-stream` 만 보낸 요청도 같다.
탭에 붙지 못하면 `BROWSER_START_FAILED` 다.

한 브라우저에 화면은 하나다. 새로 열면 앞의 화면에 `closed`(`replaced`)를 보내고 닫는다.
화면 등록부는 JVM 메모리에 둔다. Control Plane 은 한 프로세스이고 재기동하면 SSE 도 끊긴다. 화면마다 도는 탭 확인, 시간 초과, `ping` 은 스케줄러 하나로 돌고 화면이 닫히면 취소된다. 탭 확인과 다시 붙기는 그 스레드에서 막힌 채 돌아, 한 브라우저가 느리면 다른 화면의 `ping` 과 시간 초과가 최대 10초 늦어진다. 동시에 켜는 브라우저가 몇 개뿐이라 받아들인다.
SSE 자체의 시간 제한은 두지 않고 화면의 수명은 `screen-timeout` 이 정한다. 사건은 한 번에 하나씩 쓴다.
화면이 열려 있는 동안 자동 중지하지 않고(`BrowserUsage`), 입력마다 활동을 기록한다. 끄기와 지우기, 사용자 끄기는 브라우저를 멈추기 전에 화면을 닫는다.
화면은 `Page.startScreencast {format: "jpeg", quality: 60, maxWidth: 1600, maxHeight: 2000}` 로 프레임을 받고, SSE 에 쓴 뒤에 ack 한다.
받는 쪽이 읽지 않으면 ack 도 멈추므로 Chrome 이 프레임을 더 보내지 않는다.
2초마다 탭 목록을 보고 바뀌면 `tabs` 를 보낸다. 새 탭이 생기면(로그인 팝업) screencast 를 그 탭으로 옮기고, 붙은 탭이 사라지면 남은 탭으로 옮긴다.
크기 변경은 서버에서 150ms 동안 모아 마지막 값만 현재 탭에 적용한다. 탭을 옮기면 마지막 `resize` 값을 screencast 전에 새 탭에 다시 보낸다. 닫힌 화면의 예약은 취소한다.
붙은 탭의 연결이 끊기면 다시 잇는다. 프레임 없이 연이어 3번을 넘게 끊기면 `closed`(`stopped`)로 닫는다.
SSE 쓰기가 실패하거나 SSE 가 끊기면 화면을 닫는다.
15초마다 SSE 에 주석 `ping` 을 보낸다. 쓰지 못하면 끊긴 것으로 보고 `closed` 없이 닫는다. 그래서 말없이 끊긴 화면이 `screen-timeout` 까지 자동 중지를 막지 않는다.
`ping` 은 다른 쓰기가 진행 중이면 건너뛴다. `closed` 는 진행 중인 쓰기가 끝나기를 0.5초까지 기다리고, 그래도 막혀 있으면 건너뛴다. 끄기와 화면 교체가 읽지 않는 받는 쪽에 붙잡히지 않는다.
SSE 끝내기도 막힌 쓰기를 기다리지 않는다. emitter 의 `complete()` 가 같은 쓰기 잠금을 잡으므로, 막힌 쓰기가 풀린 뒤 그 스레드가 끝낸다.
탭 고르기와 탭 옮기기가 겹쳐 앞 연결이 닫히면, 앞 붙기의 명령 실패는 세대가 바뀌었으면 버린다.
상태 맞추기가 컨테이너가 사라진 `RUNNING` 을 `STOPPED` 로 되돌릴 때도 그 화면을 `closed`(`stopped`)로 닫는다.

| 사건 | 본문 |
| --- | --- |
| `frame` | `{data, width, height}`. `data` 는 JPEG base64, `width` 와 `height` 는 그 프레임의 화면 크기(CSS 픽셀) |
| `tabs` | `[{id, title, url, active}]`. 열 때와 탭이 바뀔 때 |
| `closed` | `{reason}`. `replaced`(다른 화면이 열렸다), `stopped`(브라우저가 멈췄거나 닿지 않는다), `timeout`(`screen-timeout` 이 지났다) |

`POST /api/v1/browser/screen/input` 은 요청자의 열린 화면에만 닿는다. 없으면 `BROWSER_SCREEN_CLOSED`(409)다.
본문의 `type` 과 칸이다. 모양이 틀리거나 본문이 8KB 를 넘으면 `VALIDATION_FAILED`(400)이고 오류 메시지에는 칸 이름만 싣는다.

| `type` | 칸 | CDP |
| --- | --- | --- |
| `mouse` | `action`(`down`, `up`, `move`), `x`, `y`(0~1), `button`(`left` 만, 생략 가능) | `Input.dispatchMouseEvent` |
| `wheel` | `x`, `y`(0~1), `deltaY`(-2000~2000) | `Input.dispatchMouseEvent` 의 `mouseWheel` |
| `scroll` | `action`(`top`, `bottom`) | `Runtime.evaluate` 로 페이지 처음과 끝에 쓰는 고정 식만 실행한다. 요청자가 식을 정하지 못한다 |
| `key` | `key`(`Enter`, `Backspace`, `Tab`, `Escape`, `ArrowUp`, `ArrowDown`, `ArrowLeft`, `ArrowRight`, `Delete`, `PageUp`, `PageDown`, `Home`, `End`, `Space`) | `Input.dispatchKeyEvent` 의 누름과 뗌 |
| `text` | `text`(1~500자) | `Input.insertText` |
| `navigate` | `url`(`http`, `https`) | `Page.navigate` |
| `back`, `reload` | 없음 | `Page.getNavigationHistory` 뒤 `Page.navigateToHistoryEntry`, `Page.reload` |
| `tab` | `id`(영문자와 숫자) | screencast 를 그 탭으로 옮긴다. 목록에 없는 탭은 무시한다 |
| `resize` | `width`(320~1600), `height`(320~2000) | `Emulation.setDeviceMetricsOverride`(`deviceScaleFactor` 1, `mobile` false) |

좌표는 프레임 그림 안의 비율이다. 서버가 마지막 프레임의 `deviceWidth`, `deviceHeight` 를 곱해 CSS 픽셀로 바꾸고, 프레임이 아직 없으면 그 입력을 버린다.
휴대폰의 탭과 끌기는 웹이 `mouse` 와 `wheel` 로 바꿔 보낸다. 그래서 `touch` 는 받지 않는다.
한글은 입력기가 조합을 끝낸 글자를 `text` 로 보낸다.

웹(`web/src/components/browser/`)이 화면을 그리고 입력을 만드는 방식이다. 변환은 `screen-input.ts` 의 순수 함수가 갖는다.

- 내 브라우저 페이지는 본문 폭 제한 없이 가로 폭을 채운다. 프레임은 `<img>` 의 data URL 로 그리고 화면 높이에 맞춘 칸을 채운다. 열 때와 칸의 폭이나 높이가 바뀔 때 `resize` 를 보낸다. 두 값은 실제 칸의 CSS 크기이고 위 표의 범위로 자른다. 웹은 변경을 300ms 동안 모은다
- 「전체 화면」은 Fullscreen API 를 쓰고, 지원하지 않거나 거절되면 고정 오버레이를 dialog 의 최상위 레이어에 띄운다. 닫기와 Escape 로 돌아오며 배경은 입력을 받지 않는다
- 좁은 폭에서도 `mobile` false 를 유지한다. 반응형 배치는 폭을 따르되 기기 에뮬레이션으로 로그인 사이트의 동작을 바꾸지 않는다
- 「위로」와 「아래로」는 한 화면 높이의 `wheel` 을 보내고, 길게 누르면 350ms마다 반복한다. 「처음으로」와 「끝으로」는 문서 스크롤 위치를 옮긴다. PageUp, PageDown, Space 는 원격 키 입력으로 보내며 원격 입력칸에 초점이 있으면 그 칸의 키 동작을 따른다
- 포인터 이벤트 하나로 마우스와 터치를 받고, 여러 손가락이면 첫 포인터만 따른다. 마우스는 누름 `down`, 뗌 `up`, 누른 채 움직임 `move`(초당 20번까지)이고, 취소되면 마지막 자리에서 `up` 을 보낸다. 휠은 `wheel` 이고 줄 단위는 16배, 쪽 단위는 그림 높이배로 픽셀로 바꾼다
- 터치는 움직임 없이(10 CSS 픽셀 이내) 떼면 그 자리의 `down` 과 `up` 이다. 끌면 끈 거리를 프레임의 CSS 픽셀로 늘려 반대 부호의 `wheel` 로 보낸다. 그림 위에서는 화면의 기본 스크롤과 확대를 막는다
- 글자는 숨긴 입력칸이 받는다. 마우스로 그림을 누르면 초점이 가고, 휴대폰은 「키보드」 단추로 연다. 조합 중이면 보내지 않고, `compositionend` 뒤 한 박자 미뤄 조합을 마친 글자를 보내고 입력칸을 비운다. 500자(UTF-16 단위)를 넘으면 나눠 보낸다
- 조합 중에 누른 Enter 는 조합한 글자를 보낸 뒤 한 번 보낸다. 조합을 끝낸 Enter 가 다시 오면 100ms 안의 것은 버린다
- 특수 키는 `keydown` 에서 `key` 로 보낸다. Shift+Tab 은 화면 밖으로 초점을 옮기게 두고, 화면이 닫히면 특수 키를 막지 않는다. 휴대폰의 Backspace 는 `keydown` 에 키 이름이 오지 않아 `beforeinput` 의 `deleteContentBackward` 로 잡는다
- 입력은 순서가 바뀌지 않게 한 줄로 보낸다. `BROWSER_SCREEN_CLOSED` 가 오면 화면을 닫힌 것으로 그린다. 앞 연결에 보낸 입력의 오류는 화면을 닫지 않는다
- 주소는 `new URL` 로 정규화해 보낸다. 시작 주소(`/browser?url=`)는 그 페이지에서 처음 연 화면에만 쓰고, 「다시 열기」 와 닫은 뒤 다시 연 화면은 지금 탭을 그대로 본다
- SSE 가 `closed` 없이 끊기면 끊긴 동안의 입력은 버리고 1초, 2초, 4초를 기다리며 다시 연다. 사건을 받으면 횟수를 처음으로 돌리고, 3번 다시 열어도 사건 없이 끊기면 닫는다. `closed` 가 오거나 여는 요청이 JSON 오류면 닫힘 문구와 「다시 열기」 를 그린다
- 입력의 웹 서버 라우트는 세션을 먼저 본다. `Content-Length` 나 읽은 바이트가 8KB 를 넘으면 더 읽지 않고 400 이고, 받은 바이트를 그대로 넘긴다

이 표 밖의 CDP 명령은 어느 경로로도 보낼 수 없다. 입력 본문(글자, 좌표, 주소)과 시작 주소는 로그와 오류 응답에 싣지 않는다.

## CDP 연결

Control Plane 은 브라우저의 CDP 에 두 가지로 닿는다. 주소는 컨테이너 IP 이고 `Origin` 은 보내지 않는다.

| 무엇 | 하는 일 |
| --- | --- |
| HTTP 창구 | `GET /json/list` 의 `page` 대상만 탭으로 본다. 새 탭은 `PUT /json/new?<주소>`, 앞으로 가져오기는 `GET /json/activate/<id>` |
| WebSocket | `ws://<CDP 주소의 host:port>/devtools/page/<id>` 로 탭 하나에 붙는다. Chrome 이 알려 주는 WebSocket 주소의 host 는 쓰지 않는다 |

대상 번호는 영문자와 숫자만 받는다. 명령은 번호로 응답과 짝짓고 10초 안에 답이 없으면 실패다.
연결이 끊기면 기다리던 명령을 모두 실패로 끝낸다. 조각난 메시지는 모아서 읽고, 4M 글자를 넘는 메시지가 오면 연결을 끊는다.
사건 처리기와 닫힘 알림은 WebSocket 을 읽는 스레드가 아니라 연결마다 하나인 스레드에서 차례대로 부른다. 닫힘 알림은 붙은 뒤에 끊겼을 때만 한 번 온다.

## 중계

결정은 [ADR-20261007 / user-browser](../adr/ADR-20261007-user-browser.md) 의 「중계」 와 [ADR-20261008 / browser-gateway-token](../../backend/docs/adr/ADR-20261008-browser-gateway-token.md) 이 갖는다.
커넥터는 브라우저 주소를 받지 않는다. 바인딩 설치와 확인 도구 호출이 `<gateway-base-url>/<접근 표식>` 을 커넥터의 env 에 넣고, 커넥터는 그 주소를 Chrome 의 CDP 주소처럼 부른다. 넣는 자리는 아래 「커넥터에 건네기」 가 갖는다.

### 접근 표식

| 종류 | 모양 | 서명할 글 | 브라우저 | 언제 무효 |
| --- | --- | --- | --- | --- |
| 바인딩 | `b<바인딩 번호>.<서명>` | `v1\nbinding\n<바인딩 번호>` | 그 바인딩의 연결 주인(`connector_connection.user_id`) | 바인딩 줄이 없다 |
| 호출 | `u<사용자 번호>.<만료 epoch 초>.<서명>` | `v1\ncall\n<사용자 번호>\n<만료 epoch 초>` | 그 사용자 | 만료가 지났다. 만료는 만든 때부터 5분이다. 연결 등록과 확인, 선택지 호출에 쓴다 |

서명은 `gateway-secret` 의 UTF-8 바이트를 key 로 한 HMAC-SHA256 의 소문자 16진수 64자다. 번호는 1 이상의 10진수이고 앞자리 0 을 받지 않는다.
같은 바인딩은 늘 같은 표식을 받는다. 그래서 다시 설치해도 서버 정의가 바뀌지 않는다.
비교는 고정 시간 비교다. 표식과 서명은 로그에 싣지 않고, 거절한 까닭은 종류만 남긴다.

### 받는 것

경로는 `/internal/browser-gateway/<접근 표식>/` 아래다. 웹의 서버 라우트는 이 경로를 넘기지 않는다.

| 요청 | 하는 일 |
| --- | --- |
| `GET json/version` | 그대로 넘긴다. 응답의 `webSocketDebuggerUrl` 을 중계 주소로 바꾼다 |
| `GET json/list`, `GET json` | 그대로 넘긴다. 줄마다 `webSocketDebuggerUrl` 을 중계 주소로 바꾸고 `devtoolsFrontendUrl`, `devtoolsFrontendUrlCompat` 을 뺀다 |
| `PUT json/new?<주소>` | 주소가 `http`, `https`, `about:blank` 일 때만 넘긴다. 아니면 400 이고, 이 검사는 아래 판정 순서의 1번 다음, 2번보다 먼저 한다. 응답은 `json/list` 의 한 줄처럼 바꾼다 |
| `GET json/close/<번호>`, `GET json/activate/<번호>` | 번호가 영문자와 숫자일 때만 넘기고 Chrome 의 글을 그대로 준다. 모양이 틀린 번호의 404 도 1번 다음, 2번보다 먼저 판정한다 |
| WebSocket `devtools/browser/<번호>`, `devtools/page/<번호>` | 번호가 영문자와 숫자, `-` 로 128자까지일 때만 Chrome 의 같은 경로에 붙고 양쪽 글 메시지를 그대로 잇는다. 브라우저 대상 번호는 GUID 다 |
| 그 밖 | 빈 404. WebSocket upgrade(`Upgrade: websocket`) 요청은 이 받기에서 빠지고 WebSocket 처리기가 받는다. 처리기는 devtools 경로가 아니면 빈 404 로 거절한다. 머리 값은 대소문자를 구분해 `websocket` 과 `WebSocket` 만 뺀다 |

중계 주소는 `gateway-base-url` 의 scheme 을 `ws` 나 `wss` 로 바꾸고 `/<접근 표식>/devtools/<종류>/<번호>` 를 붙인 것이다.
커넥터는 이 주소의 경로만 꺼내 자기가 받은 주소의 호스트에 붙이므로 둘이 달라도 된다.

요청마다 이 순서로 판정한다.

1. `Origin`, `Sec-Fetch-Site`, `Sec-Fetch-Mode` 머리 가운데 하나라도 있으면 403 이다. 브라우저가 보낸 요청이다. 브라우저는 no-cors `GET` 에 `Origin` 을 싣지 않으므로 `Sec-Fetch-*` 로도 막아 페이지가 `json/close`, `json/activate` 를 부르지 못하게 한다. WebSocket handshake 는 `Origin` 만 본다. 브라우저의 WebSocket 은 늘 `Origin` 을 싣는다
2. 중계가 꺼졌거나(두 설정 가운데 하나가 비었다) 기능이 꺼졌으면 503 이다
3. 표식을 확인한다. 모양이 틀렸거나, 서명이 맞지 않거나, 바인딩이 없거나, 호출 표식이 만료됐으면 404 다. 어느 까닭인지 응답으로 구분하지 않는다
4. 주인이 허용 목록에서 꺼져 있으면 404 다
5. 주인의 브라우저가 없으면 만든다. 꺼져 있으면 켠다. 다른 전이가 진행 중이면 `start-timeout` 까지 0.5초마다 다시 본다
6. 동시 수가 찼으면 503, 켜지 못했으면 502, 기다려도 `RUNNING` 이 되지 않으면 503 이다
7. 활동을 기록하고 Chrome 에 넘긴다. Chrome 이 닿지 않으면 502 다. `json/version`, `json/list`, `json/new` 에 Chrome 이 200 이 아닌 답을 주면 502 다

오류 응답의 본문은 비운다. 커넥터는 상태 코드만 본다.
Chrome 에는 `BrowserRuntime#cdpAddress` 가 준 컨테이너 IP 주소로, `Origin` 없이 보낸다. `Host` 가 IP 라 Chrome 의 `Host` 검사를 지난다.

### WebSocket

- 받는 쪽은 Spring WebSocket 이다. 받은 연결 하나에 Chrome 쪽 연결 하나를 열고, 한쪽이 닫히면 다른 쪽도 닫는다
- handshake 는 브라우저를 켜기 전에 형식을 본다. `GET` 이 아니거나 `Upgrade` 가 `websocket`(대소문자 무시)이 아니면 빈 400 이다
- 연결이 열려 있는 동안 `BrowserUsage` 핸들을 쥐어 자동 중지하지 않는다. 받은 쪽(커넥터)에서 온 메시지가 끝날 때마다 활동을 기록한다(1분에 한 번까지 쓴다). Chrome 이 보내기만 하는 동안은 활동을 기록하지 않지만, 그동안에도 핸들이 자동 중지를 막는다
- 받은 세션은 `idle-timeout` 동안 아무것도 주고받지 않으면 닫힌다. 반쯤 끊긴 연결이 핸들을 계속 쥐지 않게 한다
- 글 메시지만 조각째 그대로 넘긴다. 모아서 넘기지 않으므로 사진 바이트가 든 큰 CDP 메시지도 세션마다 큰 버퍼를 잡지 않는다. 한쪽으로 가는 조각은 앞 조각을 보낸 뒤에 보낸다
- 받은 쪽(커넥터)에서 온 메시지 하나(조각의 합)는 64M 글자까지다. 넘으면 양쪽을 닫는다. 바이너리 메시지가 오면 닫는다
- Chrome 쪽 보내기가 30초 안에 끝나지 않으면 양쪽을 닫는다. 멈춘 Chrome 이 요청 스레드를 붙잡지 않게 한다
- 끄기, 지우기, 사용자 끄기, 상태 맞추기로 브라우저가 멈추면 Chrome 쪽 연결이 끊기고 받은 연결도 닫힌다
- 표식은 열 때만 확인한다. 열린 뒤 바인딩을 떼도 그 연결은 닫힐 때까지 간다. 떼기는 도구 목록에서 서버를 빼므로 새 호출은 오지 않는다
- `org.springframework.web.socket` 로그를 DEBUG 로 올리지 않는다. 표식이 든 주소가 로그에 남는다

### 커넥터에 건네기

`connector.json` 이 `owner_browser_env` 를 선언한 커넥터에만 중계 주소를 싣는다. 다른 커넥터의 요청은 바뀌지 않는다.

| 호출 | 싣는 주소 | 만드는 곳 |
| --- | --- | --- |
| 바인딩 설치(붙이기, 값 교체, 연결 확인, 반영 완료의 다시 설치) | 그 바인딩의 표식 주소 | `BrowserGatewayTokens#bindingAddress` |
| 확인 도구와 선택지 호출(연결 등록, 연결 확인, 선택지 조회) | 요청자의 호출 표식 주소 | `BrowserGatewayTokens#callAddress` |
| 승인한 실행 | 싣지 않는다. 대시보드가 설치한 서버 정의의 값을 쓴다 | |

중계가 꺼졌으면 빈 값을 싣는다. 설치는 막지 않고, 커넥터가 브라우저에 닿지 못한다고 답해 연결 확인이 실패로 보인다.
요청 본문의 칸과 대시보드가 값을 넣는 규칙은 [커넥터 설치](connector-install.md) 의 「바인딩 설치」 와 [`hermes/README.md`](../../hermes/README.md) 의 커넥터 경로가 갖는다.
