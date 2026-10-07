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
| 켜기 | `STOPPED`, `FAILED` | `FAILED` 줄에 컨테이너 번호가 남아 있으면 먼저 그 컨테이너를 지우고 번호를 비운다. 동시 수를 센다. proxy 로 컨테이너를 만들고 켠다. CDP 의 `/json/version` 이 답할 때까지 30초 기다린다 | `RUNNING`. 실패하면 컨테이너를 지우고 `FAILED`. 남은 컨테이너를 지우지 못하면 `BROWSER_STOP_FAILED` 로 거절하고 `FAILED` 그대로 |
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

끄다가 proxy 호출이 실패하면 `FAILED` 와 `stop_failed` 를 남긴다. 켜다가 실패한 코드는 `start_failed`, `start_timeout` 이다.

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

이미지, 망, 자원, 프로필 루트는 proxy 정책이 강제한다. Control Plane 은 정책과 같은 값을 운영 설정으로 받아 생성 요청에 싣는다.
켜져 있는데 기본값이 없는 값이 비어 있으면 기동을 멈춘다.

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
화면 등록부는 JVM 메모리에 둔다. Control Plane 은 한 프로세스이고 재기동하면 SSE 도 끊긴다. 화면마다 도는 탭 확인, 시간 초과, `ping` 은 스케줄러 하나로 돌고 화면이 닫히면 취소된다.
SSE 자체의 시간 제한은 두지 않고 화면의 수명은 `screen-timeout` 이 정한다. 사건은 한 번에 하나씩 쓴다.
화면이 열려 있는 동안 자동 중지하지 않고(`BrowserUsage`), 입력마다 활동을 기록한다. 끄기와 지우기, 사용자 끄기는 브라우저를 멈추기 전에 화면을 닫는다.
화면은 `Page.startScreencast {format: "jpeg", quality: 60, maxWidth: 1280, maxHeight: 2000}` 로 프레임을 받고, SSE 에 쓴 뒤에 ack 한다.
받는 쪽이 읽지 않으면 ack 도 멈추므로 Chrome 이 프레임을 더 보내지 않는다.
2초마다 탭 목록을 보고 바뀌면 `tabs` 를 보낸다. 새 탭이 생기면(로그인 팝업) screencast 를 그 탭으로 옮기고, 붙은 탭이 사라지면 남은 탭으로 옮긴다.
탭을 옮기면 마지막 `resize` 값을 새 탭에 다시 보낸다.
붙은 탭의 연결이 끊기면 다시 잇는다. 프레임 없이 연이어 3번을 넘게 끊기면 `closed`(`stopped`)로 닫는다.
SSE 쓰기가 실패하거나 SSE 가 끊기면 화면을 닫는다.
15초마다 SSE 에 주석 `ping` 을 보낸다. 쓰지 못하면 끊긴 것으로 보고 `closed` 없이 닫는다. 그래서 말없이 끊긴 화면이 `screen-timeout` 까지 자동 중지를 막지 않는다.
다른 쓰기가 막혀 있으면 `ping` 과 `closed` 는 기다리지 않고 건너뛴다. 끄기와 화면 교체가 읽지 않는 받는 쪽에 붙잡히지 않는다.
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
| `key` | `key`(`Enter`, `Backspace`, `Tab`, `Escape`, `ArrowUp`, `ArrowDown`, `ArrowLeft`, `ArrowRight`, `Delete`) | `Input.dispatchKeyEvent` 의 누름과 뗌 |
| `text` | `text`(1~500자) | `Input.insertText` |
| `navigate` | `url`(`http`, `https`) | `Page.navigate` |
| `back`, `reload` | 없음 | `Page.getNavigationHistory` 뒤 `Page.navigateToHistoryEntry`, `Page.reload` |
| `tab` | `id`(영문자와 숫자) | screencast 를 그 탭으로 옮긴다. 목록에 없는 탭은 무시한다 |
| `resize` | `width`(320~1600), `height`(320~2000) | `Emulation.setDeviceMetricsOverride`(`deviceScaleFactor` 1, `mobile` false) |

좌표는 프레임 그림 안의 비율이다. 서버가 마지막 프레임의 `deviceWidth`, `deviceHeight` 를 곱해 CSS 픽셀로 바꾸고, 프레임이 아직 없으면 그 입력을 버린다.
휴대폰의 탭과 끌기는 웹이 `mouse` 와 `wheel` 로 바꿔 보낸다. 그래서 `touch` 는 받지 않는다.
한글은 입력기가 조합을 끝낸 글자를 `text` 로 보낸다.
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

## 다음 단계

로그인 화면의 웹(단계 2b)과 커넥터 중계, 접근 표식 표(단계 3)는 아직 없다. 결정은 [ADR-20261007 / user-browser](../adr/ADR-20261007-user-browser.md) 가 갖고, 각 단계를 구현하는 PR 이 그 계약을 이 문서에 더한다.
중계도 화면처럼 `BrowserUsage` 로 쓰는 중임을 알려 자동 중지를 막는다.
