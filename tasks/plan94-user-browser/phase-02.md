# Phase 02. 화면 SSE 와 입력 API

**Execution profile**: deep

## 목표

사용자가 자기 브라우저의 지금 탭을 SSE 로 보고(CDP screencast), POST 로 입력을 보낸다. 한 브라우저에 화면은 하나만 열리고, 화면이 열려 있는 동안 자동 중지하지 않는다.

**범위 외**: 웹 화면(단계 2b), 커넥터 중계(단계 3).

## 컨텍스트

**근거 문서**: `docs/adr/ADR-20261007-user-browser.md`, `docs/backend/user-browser.md`, `tasks/plan94-user-browser/README.md` 의 「단계 2: 로그인 화면」

- phase 01 의 `CdpTargets`, `CdpConnector` 를 쓴다
- SSE 본보기: `notification/presentation/NotificationController.java`(`SseEmitter`), `chat/presentation/ConversationEventController.java`
- 컨트롤러와 DTO 는 단계 1 의 `browser/presentation/UserBrowserController.java`, `UserBrowserDtos.java` 에 더한다. 경로는 `/api/v1/browser/screen`, `/api/v1/browser/screen/input`
- 켜기는 `UserBrowserService.start(userId)` 를 그대로 부른다(동시 수, `BROWSER_CAPACITY`). 화면이 쓰는 중임은 `BrowserUsage.open(id)` 핸들로 알리고, 입력마다 `UserBrowserService.touch(userId)` 를 부른다
- 끄기와 지우기, 자동 중지, 사용자 끄기가 브라우저를 멈추면 그 브라우저의 화면도 닫혀야 한다. `UserBrowserService` 가 컨테이너를 멈추기 전에 화면 등록부의 `close(browserId)` 를 부른다

## 의도 메모

- 웹은 WebSocket 을 중계하지 못한다(Next.js 라우트). 그래서 프레임은 SSE, 입력은 POST 다
- 웹에 CDP 를 열지 않는다. 입력은 아래 표의 종류만 받는다. `Runtime.evaluate` 같은 명령은 어느 경로로도 보내지 못한다
- 휴대폰의 탭과 끌기는 웹이 `mouse` 와 `wheel` 로 바꿔 보낸다. 그래서 `touch` 종류는 두지 않는다(계약 초안에서 뺀다)
- 한글은 입력기가 조합을 끝낸 글자를 `text` 로 받아 `Input.insertText` 로 넣는다
- 좌표는 웹이 프레임 그림 안의 비율(0~1)로 보낸다. 서버가 마지막 프레임의 `metadata.deviceWidth`, `deviceHeight` 를 곱해 CSS 픽셀로 바꾼다. 프레임이 아직 없으면 그 입력을 버린다
- 입력 본문(글자, 좌표, 주소)은 로그와 실행 기록에 남기지 않는다. 오류 응답에도 싣지 않는다
- 새 탭이 생기면(로그인 팝업) screencast 를 그 탭으로 옮긴다. 탭 목록은 화면이 열려 있는 동안 2초마다 `CdpTargets.list` 로 보고, 바뀌면 `tabs` 사건을 보낸다
- 프레임은 받으면 바로 ack 한다. SSE 쓰기가 실패하면 화면을 닫는다

## 작업 항목

### 1. 화면 세션

- `browser.application.BrowserScreens`(등록부): 브라우저 번호마다 열린 화면 하나. 새로 열면 앞의 화면에 `closed`(`replaced`)를 보내고 닫는다. `close(browserId)` 는 `closed`(`stopped`)를 보내고 닫는다
- `browser.application.BrowserScreenSession`: CDP 연결 하나, SSE 하나, 사용 핸들 하나. 열 때 `Page.enable`, 시작 주소가 있으면 `Page.navigate`, `Page.startScreencast {format: "jpeg", quality: 60, maxWidth: 1280, maxHeight: 2000}`. `Page.screencastFrame` 마다 `frame` 사건 `{data, width, height}` 와 `Page.screencastFrameAck`
- 화면 수명: 설정 `assistant.browser.screen-timeout`(기본 `30m`). 넘으면 `closed`(`timeout`)
- 연결이 끊기면(CDP 쪽이나 SSE 쪽) 핸들을 닫고 등록부에서 뺀다

### 2. API

| 메서드와 경로 | 하는 일 |
| --- | --- |
| `GET /api/v1/browser/screen?url=<시작 주소, 선택>` | 브라우저를 켜고(필요하면) 지금 탭에 붙어 SSE 를 연다. `url` 은 `http`, `https` 만. 켜기 실패와 기능 꺼짐은 SSE 를 열기 전에 JSON 오류로 돌려준다 |
| `POST /api/v1/browser/screen/input` | 열린 화면이 없으면 409 `BROWSER_SCREEN_CLOSED`. 받는 본문은 아래 표 |

| `type` | 칸 | CDP |
| --- | --- | --- |
| `mouse` | `action`(`down`, `up`, `move`), `x`, `y`(0~1), `button`(`left` 만) | `Input.dispatchMouseEvent` |
| `wheel` | `x`, `y`, `deltaY`(-2000~2000) | `Input.dispatchMouseEvent` 의 `mouseWheel` |
| `key` | `key`(`Enter`, `Backspace`, `Tab`, `Escape`, `ArrowUp`, `ArrowDown`, `ArrowLeft`, `ArrowRight`, `Delete`) | `Input.dispatchKeyEvent` 의 `keyDown`, `keyUp` |
| `text` | `text`(1~500자) | `Input.insertText` |
| `navigate` | `url`(`http`, `https`) | `Page.navigate` |
| `back`, `reload` | 없음 | `Page.getNavigationHistory` 뒤 `Page.navigateToHistoryEntry`, `Page.reload` |
| `tab` | `id` | screencast 를 그 탭으로 옮긴다 |
| `resize` | `width`(320~1600), `height`(320~2000) | `Emulation.setDeviceMetricsOverride`(`deviceScaleFactor` 1, `mobile` false) |

`ErrorCode` 에 `BROWSER_SCREEN_CLOSED`(409)를 더한다. 모양이 틀리면 400 `VALIDATION_FAILED` 다.

### 3. 문서

`docs/backend/user-browser.md` 에 「로그인 화면」 절을 더하고(README 의 초안을 위 표대로 고쳐 옮긴다), 「설정」 에 `screen-timeout`, 「API」 에 두 경로, 「로그인 유지」 에 세션 이어가기와 QR 실측을 적는다.
ADR 의 「결과」 에 QR 로그인은 세션 쿠키만 주므로 세션 이어가기 설정에 기댄다는 것을 한 줄 더한다.

### 4. 시험

- `backend/src/test/java/com/bifos/assistant/browser/application/BrowserScreenSessionTest.java`: 가짜 `CdpConnector` 와 `CdpTargets` 로 열기 순서(enable, navigate, startScreencast), 프레임 전달과 ack, 좌표 변환, 프레임 전 입력 버림, 새 탭으로 옮김, 시간 초과 닫힘
- `backend/src/test/java/com/bifos/assistant/browser/application/BrowserScreensTest.java`: 두 번째 화면이 첫 화면을 닫음, 끄기가 화면을 닫음, 화면이 열려 있으면 자동 중지 안 함
- `backend/src/test/java/com/bifos/assistant/browser/presentation/UserBrowserScreenControllerTest.java`: 입력 종류별 검증(범위 밖 좌표, 501자, `javascript:` 주소, 모르는 키, 모르는 종류 → 400), 화면 없음 409, 기능 꺼짐 503, 남의 화면에 입력할 길이 없음(요청자 기준)

## 검증

같은 Mac 의 다른 워커와 겹치지 않게 gradle 명령은 `.omc/scripts/heavy-lock` 으로 감싸 돌린다(저장소 밖 도구라 아래에는 명령만 적는다).

```bash
cd backend && ./gradlew test --tests 'com.bifos.assistant.browser.*' --tests 'com.bifos.assistant.architecture.*'
cd backend && ./gradlew qualityCheck
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/browser/application/BrowserScreens.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/browser/application/BrowserScreenSession.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/browser/application/model/**` | 수정 |
| `backend/src/main/java/com/bifos/assistant/browser/application/UserBrowserService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/browser/infra/BrowserProperties.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/browser/presentation/UserBrowserController.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/browser/presentation/UserBrowserDtos.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/shared/error/ErrorCode.java` | 수정 |
| `backend/src/main/resources/application.yml` | 수정 |
| `backend/src/test/java/com/bifos/assistant/browser/application/BrowserScreenSessionTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/browser/application/BrowserScreensTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/browser/presentation/UserBrowserScreenControllerTest.java` | 신규 |
| `docs/backend/user-browser.md` | 수정 |
| `docs/adr/ADR-20261007-user-browser.md` | 수정 |
