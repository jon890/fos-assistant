# Phase 03. 자동 중지, 상태 맞추기, API

**Execution profile**: standard

## 목표

쓰지 않는 브라우저를 자동으로 멈추고, 표와 실제 컨테이너를 맞추며, 사용자와 관리자 API 를 연다.

**범위 외**: 화면(phase 04), 원격 화면(단계 2), 중계(단계 3).

## 컨텍스트

**근거 문서**: `docs/adr/ADR-20261007-user-browser.md`, `docs/backend/user-browser.md`

- 계약: `docs/backend/user-browser.md` 의 「상태 전이」, 「API」. phase 02 의 `UserBrowserService`, `BrowserRuntime` 을 쓴다
- `@Scheduled` 본보기: `connector/application/ConnectorActionExpirer.java`, `chat/application/AttachmentCleaner.java`. 스케줄링 설정은 `shared/config/SchedulingConfig.java`
- 기동 때 한 번 맞추기는 `ApplicationReadyEvent` 를 받는다
- 컨트롤러 본보기: `followup/presentation/FollowUpController.java` 와 `FollowUpDtos.java`. 현재 사용자는 `shared/auth/CurrentUserProvider`. 관리자 경로의 권한은 `shared/config/SecurityConfig.java` 와 다른 `*AdminController` 의 방식을 따른다
- 꺼진 사용자 처리는 ADR-059 와 `people/application/PersonAccessService` 의 `disable` 이다

## 의도 메모

- 단계 2 와 3 의 화면과 중계가 「쓰는 중」 을 알리도록 `browser.application.BrowserUsage` 를 둔다. `open(userBrowserId)` 가 닫는 핸들을 돌려주고, 열린 핸들이 있으면 자동 중지하지 않는다. 이 phase 에서는 부르는 곳이 없고 테스트만 부른다
- `GET /api/browser` 는 기능이 꺼져 있어도 200 으로 `{enabled: false}` 를 돌려준다. 화면이 「준비 중」 을 그릴 수 있게 한다. 쓰기는 503 이다
- 꺼진 사용자의 브라우저는 멈춘다. 프로필은 남긴다(ADR 의 「삭제」). 꺼진 사용자를 알리는 사건이 이미 있으면 그것을 받고, 없으면 자동 중지 점검이 사용자가 꺼졌는지 보고 멈춘다. 순환 import 가 생기지 않는 쪽을 고른다

## 작업 항목

### 1. 스케줄러

`browser.application.UserBrowserSweeper`: 1분마다(설정 `assistant.browser.sweep-interval`, 기본 `1m`). `enabled=false` 면 아무것도 하지 않는다.

1. 자동 중지: `RUNNING` 이고 `last_active_at` 이 `idle-timeout` 보다 오래되고 `BrowserUsage` 에 열린 핸들이 없는 줄을 `stop`
2. 맞추기: `BrowserRuntime.list()` 로 본 실제 상태와 표를 견준다
   - `RUNNING` 인데 컨테이너가 없거나 꺼져 있다 → 컨테이너가 있으면 지우고 `STOPPED`
   - `STARTING`, `STOPPING` 이 2분 넘게 그대로다 → 컨테이너를 지우고 `STOPPED`
   - 라벨의 키에 해당하는 줄이 없거나 그 줄이 `STOPPED` 다 → 컨테이너를 지운다
3. 꺼진 사용자의 `RUNNING` 을 멈춘다(위 의도 메모)

한 줄의 실패가 다른 줄을 막지 않는다. 기동 때 1, 2 를 한 번 돈다.

### 2. API

`browser.presentation.UserBrowserController`, `UserBrowserAdminController`, `UserBrowserDtos`. 경로는 `docs/backend/user-browser.md` 의 「API」 표 가운데 화면(`screen`)을 뺀 것이다.
응답: `{enabled, exists, status, lastError, startedAt, lastActiveAt, idleTimeoutSeconds}`. 관리자 목록은 줄마다 `{id, userId, userName, status, lastError, startedAt, lastActiveAt}`.
사용자 이름은 관리자 목록을 만들 때 `user` 패키지에서 읽는다.

### 3. 문서

`docs/backend/user-browser.md` 의 「API」 와 「상태 전이」 를 구현과 맞춘다(GET 의 `enabled`, 점검 주기).

### 4. 테스트

- `backend/src/test/java/com/bifos/assistant/browser/application/UserBrowserSweeperTest.java`: 유휴 줄 멈춤, 열린 핸들이 있으면 멈추지 않음, `RUNNING` 인데 컨테이너 없음 → `STOPPED`, 남은 `STARTING` 정리, 표에 없는 컨테이너 지움, 한 줄 실패가 다음 줄을 막지 않음, 꺼진 사용자 멈춤
- `backend/src/test/java/com/bifos/assistant/browser/presentation/UserBrowserControllerTest.java`: 다른 컨트롤러 시험의 방식(인증 토큰을 붙인 MockMvc)으로 만들기, 상태, 켜기, 끄기, 지우기, 꺼진 기능의 GET 200 과 POST 503, 일반 사용자의 관리자 경로 403

## 검증

```bash
cd backend && ./gradlew test --tests 'com.bifos.assistant.browser.*' --tests 'com.bifos.assistant.architecture.*'
cd backend && ./gradlew qualityCheck
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/browser/application/BrowserUsage.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/browser/application/UserBrowserSweeper.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/browser/application/UserBrowserService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/browser/application/BrowserProperties.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/browser/application/model/**` | 수정 |
| `backend/src/main/java/com/bifos/assistant/browser/presentation/UserBrowserController.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/browser/presentation/UserBrowserAdminController.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/browser/presentation/UserBrowserDtos.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/browser/infra/UserBrowserRepository.java` | 수정 |
| `backend/src/main/resources/application.yml` | 수정 |
| `backend/src/test/java/com/bifos/assistant/browser/application/UserBrowserSweeperTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/browser/presentation/UserBrowserControllerTest.java` | 신규 |
| `docs/backend/user-browser.md` | 수정 |
