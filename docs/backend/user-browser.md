# 사용자 브라우저

사용자마다 하나씩 두는 브라우저의 표, 상태, API, 중계, 로그인 화면의 계약이다.
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
| `last_active_at` | DATETIME(6) | 그렇다 | 화면 입력이나 중계 통신이 마지막으로 있던 시각. 1분에 한 번까지만 쓴다 |
| `started_at` | DATETIME(6) | 그렇다 | |
| `created_at`, `updated_at` | DATETIME(6) | 아니다 | |
| `version` | BIGINT | 아니다 | 낙관적 잠금 |

쿠키, 저장소, 열린 주소, 화면 프레임은 이 표에 없다.

### `user_browser_grant`

바인딩이 중계에 쓰는 접근 표식이다.

| 칸 | 타입 | 빈 값 | 뜻 |
| --- | --- | --- | --- |
| `id` | BIGINT | 아니다 | |
| `user_browser_id` | BIGINT | 아니다 | `user_browser.id` |
| `binding_id` | BIGINT | 아니다 | `agent_connector_binding.id`. 유일. 바인딩을 지우면 함께 지운다 |
| `token_hash` | CHAR(64) | 아니다 | 접근 표식의 SHA-256. 유일 |
| `created_at` | DATETIME(6) | 아니다 | |

접근 표식 원문은 바인딩 설치 요청에 한 번 실려 Hermes 의 서버 정의에만 남는다. 다시 설치할 때는 새로 만든다.

## 상태 전이

| 요청 | 시작 상태 | 하는 일 | 끝 상태 |
| --- | --- | --- | --- |
| 켜기 | `STOPPED`, `FAILED` | 동시 수를 센다. proxy 로 컨테이너를 만들고 켠다. CDP 의 `/json/version` 이 답할 때까지 30초 기다린다 | `RUNNING`. 실패하면 컨테이너를 지우고 `FAILED` |
| 끄기 | `RUNNING`, `FAILED` | 화면과 중계 연결을 닫는다. 컨테이너를 멈추고 지운다 | `STOPPED` |
| 지우기 | 아무 상태 | 끄기를 한 뒤 프로필 디렉터리를 지우고 줄을 지운다. 그 브라우저의 접근 표식도 지운다 | 줄 없음 |
| 자동 중지 | `RUNNING` | `last_active_at` 이 유휴 시간보다 오래고 화면도 중계 연결도 없다 | `STOPPED` |
| 사용자 끄기 | `STOPPED` 밖의 상태 | 관리자가 허용 목록에서 사용자를 끄면 끄기를 한다. 프로필과 줄은 남긴다 | `STOPPED` |

동시 수는 `STARTING` 과 `RUNNING` 인 줄을 센다. 셀 때 `user_browser` 의 켜기를 한 번에 하나만 하도록 잠근다.
이미 `RUNNING` 인 브라우저를 켜거나 `STOPPED` 인 브라우저를 끄면 아무것도 하지 않고 지금 상태를 돌려준다.
`STARTING` 이나 `STOPPING` 인 줄에 다른 전이를 요청하면 `BROWSER_BUSY` 다.

끄다가 proxy 호출이 실패하면 `FAILED` 와 `stop_failed` 를 남긴다. 켜다가 실패한 코드는 `start_failed`, `start_timeout` 이다.

자동 중지와 상태 맞추기는 `assistant.browser.sweep-interval`(기본 `1m`)마다 돌고, 기동할 때 한 번 돈다. 기능이 꺼져 있으면 돌지 않는다.
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
| `assistant.browser.enabled` | `ASSISTANT_BROWSER_ENABLED` | 꺼져 있으면 화면과 중계, 켜기가 모두 503 이다. 아래 값이 비어도 기동한다 | `false` |
| `assistant.browser.proxy-url` | `ASSISTANT_BROWSER_PROXY_URL` | 브라우저 proxy 의 주소 | 없음 |
| `assistant.browser.image` | `ASSISTANT_BROWSER_IMAGE` | 컨테이너 이미지 | 없음 |
| `assistant.browser.network` | `ASSISTANT_BROWSER_NETWORK` | 컨테이너를 붙이는 망. 그 망의 주소로 CDP 에 닿는다 | 없음 |
| `assistant.browser.cdp-port` | `ASSISTANT_BROWSER_CDP_PORT` | 컨테이너 안에서 CDP 를 받는 포트 | 없음 |
| `assistant.browser.profile-root` | `ASSISTANT_BROWSER_PROFILE_ROOT` | Control Plane 이 보는 프로필 루트 | 없음 |
| `assistant.browser.profile-host-root` | `ASSISTANT_BROWSER_PROFILE_HOST_ROOT` | 같은 루트를 Docker 호스트에서 본 경로. 생성 요청의 `Binds` 에 쓴다 | 없음 |
| `assistant.browser.memory-mb` | `ASSISTANT_BROWSER_MEMORY_MB` | 컨테이너 메모리 상한(MB). 스왑은 주지 않는다 | `1024` |
| `assistant.browser.cpu` | `ASSISTANT_BROWSER_CPU` | 컨테이너가 쓰는 CPU 수 | 없음 |
| `assistant.browser.pids-limit` | `ASSISTANT_BROWSER_PIDS_LIMIT` | 컨테이너 안의 프로세스 수 상한 | 없음 |
| `assistant.browser.shm-mb` | `ASSISTANT_BROWSER_SHM_MB` | `/dev/shm` 크기(MB) | 없음 |
| `assistant.browser.max-running` | `ASSISTANT_BROWSER_MAX_RUNNING` | 동시에 켤 수 있는 수. 1 이상이고 상한은 두지 않는다 | `2` |
| `assistant.browser.idle-timeout` | `ASSISTANT_BROWSER_IDLE_TIMEOUT` | 자동 중지까지의 유휴 시간. `10m` 같은 Duration 형식이다 | `10m` |
| `assistant.browser.start-timeout` | | 켠 뒤 CDP 가 답하기를 기다리는 시간 | `30s` |
| `assistant.browser.sweep-interval` | | 자동 중지와 상태 맞추기를 도는 간격 | `1m` |
| `assistant.browser.gateway-base-url` | | Hermes 가 중계에 닿는 주소. 바인딩 설치가 이 주소에 접근 표식을 붙인다 | 없음 |

이미지, 망, 자원, 프로필 루트는 proxy 정책이 강제한다. Control Plane 은 정책과 같은 값을 운영 설정으로 받아 생성 요청에 싣는다.
켜져 있는데 기본값이 없는 값이 비어 있으면 기동을 멈춘다. `gateway-base-url` 은 중계를 붙일 때 더한다.

## API

모두 웹의 서버 라우트를 거친다. 요청자는 토큰의 사용자다.

| 메서드와 경로 | 하는 일 |
| --- | --- |
| `GET /api/browser` | 내 브라우저의 상태. 기능이 꺼져 있어도 200 이고 `{enabled: false}` 만 준다. 없으면 `{enabled: true, exists: false, idleTimeoutSeconds}` |
| `POST /api/browser` | 내 브라우저를 만든다. `STOPPED` 로 생긴다. 이미 있으면 `BROWSER_EXISTS`(409) |
| `POST /api/browser/start`, `POST /api/browser/stop` | 켜기와 끄기 |
| `DELETE /api/browser` | 지우기. 본문 없이 204 |
| `GET /api/browser/screen` | 화면 SSE. 아래 「로그인 화면」 |
| `POST /api/browser/screen/input` | 화면 입력 |
| `GET /api/admin/browsers` | 관리자. 모든 브라우저의 사용자, 상태, 시각 |
| `POST /api/admin/browsers/{id}/stop`, `DELETE /api/admin/browsers/{id}` | 관리자. 끄기와 지우기. 지우기는 본문 없이 204 |

Control Plane 의 경로는 같은 이름에 `/api/v1` 을 붙인 것이다(`/api/v1/browser`, `/api/v1/admin/browsers`).

내 브라우저 응답은 `{enabled, exists, status, lastError, startedAt, lastActiveAt, idleTimeoutSeconds}` 다.
관리자 목록은 줄마다 `{id, userId, userName, status, lastError, startedAt, lastActiveAt}` 이다. 컨테이너 번호와 프로필 키는 싣지 않는다.
관리자 목록은 기능이 꺼져 있어도 읽는다. 쓰기는 기능이 꺼져 있으면 503 이다.

오류 코드는 `BROWSER_NOT_FOUND`(404), `BROWSER_DISABLED`(503), `BROWSER_CAPACITY`(409), `BROWSER_BUSY`(409, 다른 전이가 진행 중), `BROWSER_START_FAILED`(502), `BROWSER_EXISTS`(409) 다.

## 중계

`/internal/browser-gateway/<접근 표식>/` 아래만 받는다. 이 경로는 웹 라우트로 열지 않는다.

| 받는 것 | 하는 일 |
| --- | --- |
| `GET json/version`, `GET json/list` | 접근 표식으로 브라우저를 찾아 켠 뒤 넘긴다. 응답의 `webSocketDebuggerUrl` 을 중계 주소로 바꾼다 |
| `PUT json/new?<주소>`, `GET json/close/<id>`, `GET json/activate/<id>` | 그대로 넘긴다 |
| WebSocket `devtools/browser/<id>`, `devtools/page/<id>` | 양쪽 메시지를 그대로 잇는다. 연결이 열려 있는 동안 자동 중지하지 않는다 |
| 그 밖 | 404 |

접근 표식이 없거나 틀리면 404 다. 있는지 없는지를 응답으로 구분하지 않는다.
Chrome 에는 `Host: localhost` 와 `Origin` 없이 보낸다.

## 로그인 화면

`GET /api/browser/screen` 은 브라우저를 켜고 지금 탭에 붙어 SSE 를 연다.

| 사건 | 본문 |
| --- | --- |
| `frame` | `{data, width, height}`. `data` 는 JPEG base64 |
| `tabs` | `[{id, title, url, active}]`. 탭이 바뀔 때 |
| `closed` | 다른 화면이 열렸거나 브라우저가 멈췄다 |

`POST /api/browser/screen/input` 의 `type` 이다. 나머지 칸은 그 종류의 CDP 인자 가운데 필요한 것만 남긴 것이다.

| `type` | CDP |
| --- | --- |
| `mouse` | `Input.dispatchMouseEvent` |
| `touch` | `Input.dispatchTouchEvent` |
| `wheel` | `Input.dispatchMouseEvent` 의 `mouseWheel` |
| `key` | `Input.dispatchKeyEvent`. 특수 키만(Enter, Backspace, Tab, 방향 키 등) |
| `text` | `Input.insertText`. 500자까지 |
| `navigate` | `Page.navigate`. `http`, `https` 만 |
| `back`, `reload` | `Page.navigateToHistoryEntry`, `Page.reload` |
| `tab` | 고른 탭으로 screencast 를 옮긴다 |
| `resize` | `Emulation.setDeviceMetricsOverride`. 폭 320~1600 |

입력 본문은 로그와 실행 기록에 남기지 않는다.
