# Phase 01. browser 모듈의 두 스위치와 탭 목록·열기·닫기

**Execution profile**: standard

## 목표

`browser` 모듈에 사용자 스위치, 관리자 전체 스위치, 요청자 브라우저의 탭 목록·열기·닫기를 만든다. 이 phase 가 끝나면 다른 모듈이 부를 부품과 웹이 부를 스위치 API 가 있다.

**범위 외**: MCP 도구와 승인 카드(phase 02), 웹 화면(phase 03).

## 컨텍스트

**요구사항**: REQ1, REQ2, REQ3, REQ4

**근거 문서**: `docs/features/agent-browser/spec.md`, `docs/features/agent-browser/design.md` 의 「PR1 스위치」, 「PR1 도구」 실행 순서, `backend/docs/data-schema.md` 의 `user_browser`, `group_setting`

- 칸을 엔티티가 쓰지 않고 전용 `UPDATE` 로만 쓰는 선례는 `UserBrowser.humanHeldAt`(`insertable = false, updatable = false`)과 `UserBrowserRepository.holdByHuman` 이다.
- `group_setting` 은 `user.application.GroupSettingStore` 의 `read`, `write` 로 읽고 쓴다. 그룹 번호는 `CurrentUser.groupId()` 다.
- 에이전트가 쥔 탭은 `BrowserAgentTabs.agentTabs(browserId, cdp, pages)`, 지킬 탭은 `BrowserGateway.keeps` 가 쓰는 것과 같이 `BrowserKeptTabs.kept(userId, tab, startedAt)` 로 본다. 판정 실패는 지키는 쪽으로 본다.
- 사람 잠금은 `BrowserHumanHold.held(userId)`.
- 꺼진 브라우저를 켜는 것은 `UserBrowserService.ensureRunning`. 목록과 닫기는 켜지 않고, `RUNNING` 이고 `containerId` 가 있을 때만 `BrowserRuntime.cdpAddress` 로 주소를 얻는다.

## 의도 메모

- 사용자 스위치를 `app_user` 에 두지 않는다. 브라우저를 지우면 함께 꺼져야 한다(design DECISION1).
- 관리자 스위치를 `BrowserProperties` 에 두지 않는다. 기동 때 값이라 화면에서 끌 수 없다(design DECISION2).
- 닫기 결과와 예외 글에 제목과 주소를 넣지 않는다(spec REQ8).

## 작업 항목

### 1. 마이그레이션과 엔티티

- `backend/src/main/resources/db/migration/V20261105074445__user_browser_agent_control.sql`: `ALTER TABLE user_browser ADD COLUMN agent_control_at DATETIME(6) NULL`. 버전은 예약한 값이다.
- `UserBrowser.agentControlAt`(`insertable = false, updatable = false`), 접근자.
- `UserBrowserRepository`: `enableAgentControl(userId, now)`(비어 있을 때만), `disableAgentControl(userId)`. `@Modifying(flushAutomatically = true, clearAutomatically = true)`.
- `backend/docs/data-schema.md` 의 `user_browser` 칸 목록과 `group_setting` 키 목록에 더한다.

### 2. `browser/application/BrowserAgentControl`

- `AgentControlStatus` enum(`application/dto`): `ALLOWED`, `FEATURE_OFF`, `ADMIN_OFF`, `USER_OFF`, `NO_BROWSER`.
- `status(CurrentUser)`: 기능 플래그(`UserBrowserService.enabled()`), `GroupSettingStore.read(groupId, "BROWSER_AGENT_CONTROL")` 의 `enabled`(줄 없거나 읽지 못하면 허용), 사용자 줄의 `agentControlAt` 순서로 본다.
- `setUser(CurrentUser, boolean)`: 브라우저 없으면 `BROWSER_NOT_FOUND`, 켜기인데 관리자가 껐으면 새 오류 코드 `BROWSER_AGENT_CONTROL_OFF`(409). 끄기는 늘 된다.
- `adminAllowed(groupId)`, `setAdmin(groupId, boolean)`.

### 3. `browser/application/BrowserTabControl`

- `list(Long userId)` → `BrowserTabs(boolean running, List<CdpTarget> tabs)`(`application/dto`). 쥔 탭과 지킬 탭을 뺀다.
- `open(Long userId, String url)` → 연 탭 번호. `ensureRunning` 뒤 `CdpTargets.create`. `http`, `https` 가 아니면 `IllegalArgumentException`.
- `close(Long userId, List<TabRef> targets)` → `TabCloseResult(int closed, int skipped)`. `TabRef(String id, String urlHash)`. 지금 목록에서 탭이 없거나, 주소 지문(`Sha256.hex(url)`)이 다르거나, 쥔 탭이나 지킬 탭이면 건너뛴다. 꺼진 브라우저면 모두 건너뛴다.
- 사람 잠금이면 열기와 닫기 모두 `BROWSER_HUMAN_HELD` 를 던진다. 스위치 판정은 부르는 쪽(phase 02 실행기)이 한다.
- `urlHash(String url)` 정적 도우미를 둔다. phase 02 가 카드에 같은 지문을 적는다.

### 4. `CdpTargets.close(URI cdp, String id)`

`HttpCdpTargets` 가 `GET /json/close/{id}` 로 구현한다. `requireTargetId` 로 번호를 검사한다. `FakeCdp`(테스트)도 구현한다.

### 5. 스위치 API

- `UserBrowserController`: `GET /api/v1/browser` 의 `BrowserView` 에 `agentControl { enabled, adminAllowed }`. `PUT /api/v1/browser/agent-control` 본문 `{ enabled }`.
- `admin/browser/presentation/UserBrowserAdminController`: `GET`, `PUT /api/v1/admin/browsers/agent-control`. `CurrentUserProvider.requireAdmin` 은 이 `admin` 패키지 안에서만 부른다.
- 웹이 읽을 오류 코드를 `ErrorCode` 에 더한다.

### 6. 테스트

- `browser/application/BrowserTabControlTest`(단위, `FakeCdp`): 합성 사용자 둘(서로 다른 브라우저 번호와 CDP 주소)로 A 의 목록·닫기가 A 의 CDP 만 부르고 B 의 탭 번호는 건너뛴다. 쥔 탭과 지킬 탭이 목록에서 빠진다. 꺼진 브라우저는 켜지 않고 빈 목록이다. 지문이 다르면 건너뛴다. 사람 잠금이면 거절한다.
- `browser/application/BrowserAgentControlTest`(통합, H2): 다섯 상태, 관리자가 끈 동안 켜기 거절과 끄기 허용, 엔티티 저장이 스위치를 되돌리지 않는다.
- `browser/infra/HttpCdpTargetsTest`: 닫기 경로와 잘못된 번호 거절.
- `browser/presentation/UserBrowserControllerTest`, `admin` 쪽 컨트롤러 테스트: 스위치 API, 관리자 아닌 사용자의 관리자 API 거절.

## 검증

```bash
cd backend && ./gradlew test --tests '*BrowserTabControlTest' --tests '*BrowserAgentControlTest' --tests '*HttpCdpTargetsTest' --tests '*UserBrowserControllerTest' --tests '*UserBrowserAdmin*'
cd backend && ./gradlew archTest checkstyleMain checkstyleTest spotlessCheck
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/resources/db/migration/V20261105074445__user_browser_agent_control.sql` | 신규 |
| `backend/src/main/java/com/bifos/assistant/browser/domain/UserBrowser.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/browser/infra/UserBrowserRepository.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/browser/domain/CdpTargets.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/browser/infra/HttpCdpTargets.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/browser/application/BrowserAgentControl.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/browser/application/BrowserTabControl.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/browser/application/dto/AgentControlStatus.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/browser/application/dto/BrowserTabs.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/browser/application/dto/TabRef.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/browser/application/dto/TabCloseResult.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/browser/presentation/UserBrowserController.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/browser/presentation/UserBrowserDtos.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/admin/browser/presentation/UserBrowserAdminController.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/admin/browser/presentation/UserBrowserAdminDtos.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/shared/error/ErrorCode.java` | 수정 |
| `backend/docs/data-schema.md` | 수정 |
| `backend/src/test/java/com/bifos/assistant/browser/application/BrowserTabControlTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/browser/application/BrowserAgentControlTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/browser/application/FakeCdp.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/browser/infra/HttpCdpTargetsTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/browser/presentation/UserBrowserControllerTest.java` | 수정 |
