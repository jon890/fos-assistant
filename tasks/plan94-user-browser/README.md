# plan94 사용자 브라우저

사용자마다 브라우저 하나를 Control Plane 이 관리하고, 사용자는 웹의 원격 화면으로 직접 로그인하며, 커넥터는 바인딩이 준 중계 주소로만 그 브라우저에 닿는다.
결정은 `docs/adr/ADR-20261007-user-browser.md`, 계약은 `docs/backend/user-browser.md` 가 갖는다.

## 상태

단계 1 을 구현했다. 그 phase 문서는 구현 PR 에서 지웠고, 오래 남을 계약은 `docs/backend/user-browser.md` 와 `docs/backend/schema/browser.md` 에 있다.
단계 2a-1 과 2a-2 를 구현했다. 로그인 화면의 계약은 `docs/backend/user-browser.md` 의 「로그인 화면」 이 갖는다. 남은 것은 단계 2b, 3, 4 다. 단계 2 는 운영 코드 1,000줄 상한 때문에 셋으로 나눴다. 다음 단계를 시작할 때 이 디렉터리에 phase 를 더한다. 단계 3 의 PR 이 이 디렉터리를 지운다.

## 단계와 PR

각 단계가 PR 하나다. 각 PR 은 그 단계의 설계 문서 변경과 구현을 함께 담는다.

| 단계 | 담는 것 | 저장소 | 앞 단계 |
| --- | --- | --- | --- |
| 0 | 브라우저 proxy 를 하나 더 띄운다(셸 proxy 코드 재사용, 브라우저 정책). 이미지(headless Chrome 과 CDP 중계, 일반 사용자), 브라우저 망, 프로필 루트, Control Plane 이 닿는 주소 | `fos-home-infra` | 없음 |
| 1 | `user_browser` 표, 켜기와 끄기와 지우기, 자동 중지와 동시 수, 기동 때와 주기 점검의 상태 맞추기, 관리자 목록. 웹 「내 브라우저」 화면의 상태와 단추. 기능은 `assistant.browser.enabled` 로 꺼 둔 채 배포한다 | 이 저장소 | 0 이 머지되어야 운영에서 켠다. 코드는 가짜 proxy 로 검사한다 |
| 2a-1 | CDP 세션 클라이언트(탭 목록, WebSocket 명령과 사건), 세션 쿠키를 이어 가는 프로필 설정 | 이 저장소 | 1 |
| 2a-2 | 화면 SSE, 입력 POST, 탭 고르기, 한 브라우저에 화면 하나 | 이 저장소 | 2a-1 |
| 2b | 로그인 화면의 웹. 프레임 그리기, 휴대폰 탭과 끌기, 한글 입력, 탭 고르기, 주소 창 | 이 저장소 | 2a |
| 3 | 중계(`/internal/browser-gateway`), `user_browser_grant`, `connector.json` 의 `owner_browser_env`, 바인딩 설치와 확인 도구 호출에 중계 주소 싣기. 네이버 블로그 커넥터의 `cdp_url` 칸을 빼고 `owner_browser_env` 로 바꾼다 | 이 저장소 | 2 |
| 4 | 운영 이행. 기존 사용자의 상주 Chrome 로그인을 새 브라우저로 옮기고 상주 Chrome 과 중계를 내린다 | `fos-home-infra` | 3 의 배포 |

## 운영 순서

1. 단계 0 PR 을 머지하고 브라우저 proxy 와 망을 띄운다. 이미지를 받아 둔다
2. 단계 1 을 배포한다. 운영 설정에 proxy 주소를 넣고 `assistant.browser.enabled` 를 켠다
3. 관리자가 자기 브라우저로 켜기, 끄기, 자동 중지를 확인한다
4. 단계 2 배포 뒤 관리자가 휴대폰과 PC 에서 네이버에 로그인해 본다
5. 단계 3 배포 뒤 블로그 주인이 「내 브라우저」 에서 로그인하고 연결을 다시 확인한다. 실제 계정 확인 절차는 `docs/connectors/naver-blog.md` 의 「실제 계정으로 확인하기」 를 따른다
6. 단계 4 로 상주 Chrome 을 내린다

## 사용자가 정한 것

2026-10-07 에 코디네이터를 거쳐 사용자가 모두 권장안으로 정했다.

| 번호 | 무엇 | 정한 것 | 고르지 않은 것 |
| --- | --- | --- | --- |
| D1 | 컨테이너를 만드는 쪽 | Control Plane 이 브라우저 전용 proxy 를 거쳐 만든다 | 대시보드 plugin 이 셸 proxy 로 만든다. 운영 저장소의 별도 관리 서비스 |
| D2 | 원격 화면 방식 | CDP screencast 를 SSE 와 POST 로 | noVNC(WebSocket 중계 새로 필요) |
| D3 | Hermes 내장 browser 도구 | 이번 plan 에 넣지 않는다 | 단계 5 로 넣는다(이미지에 실행 도구를 더하고 `browser.cdp_url` 을 바인딩이 쓴다) |
| D4 | 네이버 블로그 이행 | 단계 3 에서 `cdp_url` 칸을 바로 뺀다. 기존 연결은 다시 확인해야 한다 | 한 배포 동안 둘 다 받는다 |
| D5 | 기본 한도 | 동시 2개, 유휴 10분, 컨테이너 메모리 1GB | 사용자가 숫자를 정한다 |
| D6 | 프로필 보관 | 사용자가 지울 때까지 둔다. 사용자를 끄면 멈추기만 한다 | 쓰지 않은 지 N일이 지나면 지운다 |
| D7 | 관리자의 권한 | 상태 보기, 끄기, 지우기. 남의 화면은 못 연다 | 남의 화면도 연다 |

## 단계 3 의 계약 초안

구현하는 PR 이 `docs/backend/user-browser.md` 로 옮긴다.

추가할 설정과 API 줄이다.

| 줄 |
| --- |
| `assistant.browser.gateway-base-url` / / Hermes 가 중계에 닿는 주소. 바인딩 설치가 이 주소에 접근 표식을 붙인다 / 없음 |

### 단계 3: `user_browser_grant`

바인딩이 중계에 쓰는 접근 표식이다.

| 칸 | 타입 | 빈 값 | 뜻 |
| --- | --- | --- | --- |
| `id` | BIGINT | 아니다 | |
| `user_browser_id` | BIGINT | 아니다 | `user_browser.id` |
| `binding_id` | BIGINT | 아니다 | `agent_connector_binding.id`. 유일. 바인딩을 지우면 함께 지운다 |
| `token_hash` | CHAR(64) | 아니다 | 접근 표식의 SHA-256. 유일 |
| `created_at` | DATETIME(6) | 아니다 | |

접근 표식 원문은 바인딩 설치 요청에 한 번 실려 Hermes 의 서버 정의에만 남는다. 다시 설치할 때는 새로 만든다.

### 단계 3: 중계

`/internal/browser-gateway/<접근 표식>/` 아래만 받는다. 이 경로는 웹 라우트로 열지 않는다.

| 받는 것 | 하는 일 |
| --- | --- |
| `GET json/version`, `GET json/list` | 접근 표식으로 브라우저를 찾아 켠 뒤 넘긴다. 응답의 `webSocketDebuggerUrl` 을 중계 주소로 바꾼다 |
| `PUT json/new?<주소>`, `GET json/close/<id>`, `GET json/activate/<id>` | 그대로 넘긴다 |
| WebSocket `devtools/browser/<id>`, `devtools/page/<id>` | 양쪽 메시지를 그대로 잇는다. 연결이 열려 있는 동안 자동 중지하지 않는다 |
| 그 밖 | 404 |

접근 표식이 없거나 틀리면 404 다. 있는지 없는지를 응답으로 구분하지 않는다.
Chrome 에는 `Host: localhost` 와 `Origin` 없이 보낸다.
