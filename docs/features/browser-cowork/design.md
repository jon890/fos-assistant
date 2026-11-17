# 실행 중 브라우저 함께 보기·넘겨받기와 이어 가기 기술 설계

스펙은 [spec.md](spec.md)(사용자 확인 2026-11-16)다. 이 문서는 기능 전체의 경계와 PR1, PR2, PR3, PR4 의 상세를 갖는다.
PR5 는 아래 「뒤 PR 의 경계」 가 정한 경계를 따르고, 그 PR 을 시작할 때 이 문서에 그 PR 의 절을 더해 상세를 닫는다.

## 요구사항 연결

| ID | 해결 방법 |
| --- | --- |
| REQ1 | `browser` 의 자동 중지가 새 포트 `BrowserWaitDemand` 로 「그 사용자가 사람을 기다리는가」 를 묻는다. 기다리면 유휴 기준을 `idle-timeout`(10분) 대신 새 설정 `wait-hold-timeout`(30분)으로 본다. 포트는 `connector` 가 구현해 열린 넘기기와 브라우저를 쓰는 플러그인의 기다리는 승인을 센다 |
| REQ2 | `connector` 의 새 부품 `ConnectorApprovalBrowser` 가 승인 트랜잭션보다 먼저 브라우저를 쓰는 플러그인의 줄이면 `UserBrowserService.ensureRunning` 으로 켠다. 켜지 못하면 줄을 건드리지 않고 `CONNECTOR_BROWSER_UNAVAILABLE` 로 거절하고, 웹이 그 코드에 정해 둔 문구를 카드에 보인다 |
| REQ3 | PR2. 웹이 그 대화의 도는 도구 단계와 `EXECUTING` 승인 줄을 카탈로그의 `ownerBrowser` 로 걸러 단추를 보이고, 누르면 옆 패널의 「내 브라우저」 탭이 에이전트 탭을 연다. 아래 「PR2 에이전트 화면 보기」 |
| REQ4 | PR2. `browser` 의 새 부품 `BrowserAgentTabs` 가 중계의 탭 연결과 `/json/new`, 그 탭이 연 새 탭으로 에이전트가 쥔 탭을 메모리에 두고, 화면이 그 탭의 입력을 버리며 `tabs` 사건에 쥔 쪽을 싣는다. 아래 「PR2 에이전트가 쥔 탭」 |
| REQ5 | PR3. `browser` 의 새 부품 `BrowserHumanHold` 가 `user_browser.human_held_at` 을 조건부 갱신으로 쓰고, 중계가 잠긴 동안 HTTP 423 과 WebSocket close code 4423 으로 거절하며, 플러그인이 그 신호를 `human_browser` 넘기기로 바꾼다. 아래 「PR3 사람 잠금」 |
| REQ6 | PR3. 회수할 때 새 포트 `BrowserApprovedRuns` 로 브라우저를 쓰는 금융·구매 승인 줄이 실행 중인지 묻고, 실행 중이면 `BROWSER_APPROVAL_RUNNING` 으로 거절한다 |
| REQ7 | PR3. 「돌려주기」 는 잠금 칸을 비우고, 글을 보낼지는 웹이 그 대화의 열린 넘기기로 정한다. 점검이 화면이 닫힌 지 10분 지난 잠금을 푼다 |
| REQ8 | PR4. 승인 줄의 새 상태 `WAITING`(사람 기다림)과 이어 가기 도구 `approval_resume`. 아래 「PR4 이어 가기」 |
| REQ9 | PR4. 표식은 그 승인 줄의 칸이고 수명은 처음 멈춘 때부터 30분이다. 아래 「PR4 이어 가기」 |
| REQ11 | PR4. 중계가 새 포트 `BrowserKeptTabs` 로 묻고 `/json/close` 를 409 로 거절한다. 아래 「PR4 열린 넘기기의 탭 지키기」 |
| REQ10 | PR5. PR4 의 표식을 KTX 좌석 확보에 붙인다. 지금 KTX 플러그인에는 조회 도구만 있어(`hermes/connectors/korail-ktx/assistant.json`) PR5 는 KTX 스펙의 좌석 확보 PR 이 들어온 뒤에 연다 |

## 책임과 경계

| 모듈 | 갖는 것 |
| --- | --- |
| `browser` | 브라우저 상태, 자동 중지, 화면, 중계. 잠금 상태(PR2, PR3)도 여기 둔다. 브라우저가 무엇을 기다리는지는 모르고 포트로 묻는다 |
| `connector` | 승인 줄, 넘기기, 이어 가기 표식(PR4). `browser` 의 포트를 구현하고, 승인 때 `browser` 의 공개 서비스를 부른다 |
| 웹 | 승인 카드의 오류 문구(PR1), 보기 단추와 띠, 회수 입력(PR2, PR3) |
| 플러그인 | 넘기기 결과와 이어 가기 단계(PR3, PR4, PR5). 플러그인 이름을 Control Plane 이 모르는 것은 그대로다(rule-65) |

의존 방향은 지금과 같다. `connector` 가 `browser` 의 공개 서비스를 부르고, `browser` 는 `connector` 를 모른 채 포트를 둔다.
`browser` 가 `connector` 보다 아래라서 둔 기존 포트 `BrowserGrantOwners` 와 같은 모양이다.

## 인터페이스와 데이터

### PR1 사람을 기다리는 동안 브라우저 유지(REQ1)

```text
UserBrowserSweeper.stopIdle
  후보: RUNNING 이고 last_active_at 이 now - idle-timeout 보다 앞선 줄, BrowserUsage 가 쓰지 않는 줄
  BrowserWaitDemand.waiting(userId, 그 줄의 started_at) 이 참이면 기준을 now - wait-hold-timeout 으로 바꿔 다시 본다
  기준보다 앞서면 stopIfIdle(id, 그 기준) 으로 끈다. 아니면 건너뛴다
```

- **포트** `browser.application.port.BrowserWaitDemand`: `boolean waiting(long userId, Instant since)`. `since` 는 그 브라우저가 이번에 켜진 시각(`user_browser.started_at`)이다. 구현은 `connector` 의 `action` 하위 기능에 두는 `ConnectorBrowserWaitDemand` 다. 판정 줄 저장소가 같은 하위 기능에 있고, manifest 는 아래 하위 기능 `connection` 의 `ConnectorCatalogCache` 로 읽는다.
- **기다림의 뜻**(REQ1 의 넷 가운데 PR1 이 보는 둘):
  - 열린 넘기기: 그 사용자의 판정 줄 가운데 `handoff_need` 가 있고 `handoff_closed_at` 이 비었으며, 대화(`conversation_id`)가 있고 `handoff_at` 이 `since` 이후인 줄. 넘기기를 닫는 길은 그 대화의 사람 turn 뿐이라, 대화 밖 실행의 넘기기나 버려진 대화의 카드는 끝없이 열려 있다. 브라우저가 다시 켜졌으면 그 탭도 이미 없다. 이 둘을 빼야 기다림이 영구히 참이 되지 않는다. 넘기기는 「내 브라우저」 를 쓰는 플러그인만 낸다. 결제(`payment`)와 탭 확인 넘기기도 들어간다. 사람이 그 탭에서 결제해야 하기 때문이다
  - 기다리는 승인: 그 사용자의 `PENDING` 이고 `expires_at` 이 지나지 않은 승인 줄 가운데, 그 플러그인의 manifest 가 `owner_browser_env` 를 선언한 줄(`ConnectorManifest#ownerBrowser`). 카탈로그를 읽지 못하면 기다리는 것으로 본다. 잘못 보아도 30분 뒤 꺼지므로 켜 두는 쪽이 안전하다
  - 사람 기다림 승인은 PR4 가 같은 포트에 더한다. 사람 잠금(PR3)은 `browser` 가 갖는 칸이라 포트를 거치지 않고 점검이 줄에서 직접 본다
- **설정** `assistant.browser.wait-hold-timeout`, 기본 `30m`, `BrowserProperties` 에 더하고 `idle-timeout` 보다 짧으면 `idle-timeout` 으로 끌어올린다. 운영이 `idle-timeout` 만 늘려도 기동이 멈추지 않는다. 값은 사용자 결정(spec DECISION10)이다. 점검은 `LiveProperties<BrowserProperties>` 에서 직접 읽는다. `UserBrowserService` 는 파일 길이 상한(rule-44)에 닿아 메서드를 더하지 않는다.
- **사람이 끄기·지우기, 관리자 끄기, 사용자 끄기**는 자동 중지를 거치지 않으므로 그대로 바로 끈다.
- 데이터는 바꾸지 않는다. 기존 칸(`connector_action` 의 `status`, `expires_at`, `handoff_at`, `handoff_closed_at`, `user_browser.last_active_at`)만 읽는다.

### PR1 승인할 때 브라우저를 켜지 못하면(REQ2)

```text
ConnectorActionService.approve(상한 변경 줄을 가른 뒤) / ContentDraftSaveApproval.approve(처음)
  ConnectorApprovalBrowser.require(user, actionId)
    줄이 없거나 남의 것이거나 PENDING 이 아니거나 기다리는 시간이 지났으면 아무것도 하지 않는다(뒤의 기존 판정이 답한다)
    그 플러그인 manifest 가 owner_browser_env 를 선언하지 않았으면 아무것도 하지 않는다
    UserBrowserService.ensureRunning(userId)
      성공: UserBrowserService.touch(userId) 로 활동을 기록하고 지나간다(ensureRunning 은 활동을 기록하지 않는다)
      ApiException(BROWSER_CAPACITY | BROWSER_START_FAILED | BROWSER_BUSY | BROWSER_STOP_FAILED | BROWSER_NOT_FOUND):
        CONNECTOR_BROWSER_UNAVAILABLE 로 던진다
      그 밖의 RuntimeException(프로필 디렉터리 준비 실패 등): CONNECTOR_BROWSER_UNAVAILABLE 로 던진다. 로그에는 예외 종류만 남긴다
      ApiException(BROWSER_DISABLED): 바꾸지 않고 그대로 던진다. 기능이 꺼진 것이라 「잠시 뒤 다시」 가 맞지 않는다
  (기존) 승인 트랜잭션, 실행
```

- 승인 진입점은 `ConnectorActionController.approve` 하나이고, 그 안에서 원고 저장 승인(네이버 블로그가 브라우저를 쓴다)과 일반 승인(`ConnectorActionService.approve`)으로 갈린다. 일반 승인은 다시 상한 변경, 금융·구매, 일반 쓰기로 갈린다.
- 상한 변경 줄은 빼야 한다. 그 줄의 `connectorId` 는 금융 보호를 선언한 플러그인이라 쇼핑이면 manifest 가 `owner_browser_env` 도 선언하지만, 승인은 DB 의 상한만 바꾸고 브라우저를 쓰지 않는다. 그래서 `ConnectorActionService.approve` 에서 상한 변경 줄을 가른 뒤, 금융·구매와 일반 쓰기로 가르기 전에 부르고, `ContentDraftSaveApproval.approve` 의 처음에서도 부른다.
- 부품은 `connector` 의 `action` 하위 기능(`action/application`)에 둔다. 하위 기능 순서(rule-76, `SubFeatureOrder`)에서 `approval` 과 `contentdraft` 보다 아래라 어느 쪽에서도 쓸 수 있고, 줄 저장소와 manifest 읽기가 같은 하위 기능에 있다. `approval/application` 은 파일 수 상한(rule-75)에 가깝다.
- 새 오류 코드 `CONNECTOR_BROWSER_UNAVAILABLE` 은 409 다. 줄이 기다림 그대로라 다시 누를 수 있다는 뜻이다. 오류 본문의 글은 웹이 쓰지 않는다.
- 웹은 `web/src/lib/connector-action-error.ts` 의 문구 표에 그 코드와 「브라우저를 켤 수 없어요. 잠시 뒤 다시 승인해 주세요.」 를 더한다. 승인 카드는 이미 실패한 `message` 를 보이고 줄을 다시 읽는다.
- 줄을 잠그기 전에 켜므로 승인 트랜잭션이 최대 `start-timeout`(30초) 동안 DB 연결과 사용자 행 잠금을 쥐지 않는다.

### PR2 에이전트가 쥔 탭

```text
중계 PUT /json/new 가 200 이고 본문에 탭 번호가 있다 → BrowserAgentTabs.created(browserId, 탭)     30초 동안 쥔다
중계 WebSocket devtools/page/<탭> 이 Chrome 쪽에 붙었다 → BrowserAgentTabs.hold(browserId, 탭)    연결이 닫힐 때 놓는다
중계 WebSocket devtools/browser/<번호> → 쥐지 않는다(쿠키 읽기)

held(탭) = 열린 연결 수 > 0
        || created 뒤 30초 안이고 아직 연결이 붙지 않았다
        || 그 탭을 연 탭(openerId)이 held 다(다섯 단계까지)
```

- **부품** `browser.application.BrowserAgentTabs`(빈). 브라우저마다 탭별 열린 연결 수, 만든 시각, 연 탭을 JVM 메모리에 둔다. `BrowserUsage` 와 같은 까닭으로 메모리다. Control Plane 이 다시 뜨면 중계 연결도 함께 끊긴다. 연결과 만든 표시가 모두 없어진 브라우저의 상태는 지운다.
- **만들 때부터 쥔 30초**: 플러그인은 `/json/new` 뒤 바로 그 탭에 붙는다(`hermes/connectors/*/src/tab.ts`). 붙으면 연결 수가 판정을 맡는다. 붙지 못한 탭이 영영 쥔 탭으로 남지 않게 시간으로 놓는다. 화면 넘기기로 남긴 탭은 연결이 닫혀 쥐지 않는다.
- **연 탭(opener)**: `/json/list` 에는 연 탭이 없어서 화면이 처음 보는 탭이 있고 그 브라우저에 쥔 탭이 하나라도 있을 때만 브라우저 수준 CDP 의 탭 목록 명령(getTargets)으로 `openerId` 를 한 번 읽는다(`CdpConnector.openers`). 쥔 탭이 없으면 읽지 않는다. 에이전트가 쓰지 않을 때 사람 화면에는 비용이 없다. 읽지 못하면 연 탭을 모르는 것으로 두고(사람 탭) 다음 새 탭에서 다시 읽는다. 「바로구매」 처럼 에이전트 탭이 연 새 탭은 플러그인이 붙기 전에도 쥔 탭이고, 연 탭을 놓으면 함께 놓인다.
- **보기 전용**(`BrowserScreenSession.input`): 지금 붙은 탭이 held 면 `tab` 과 `resize` 밖의 입력(누르기, 휠, 처음·끝 스크롤, 키, 글자, 주소, 뒤로, 새로 고침, 확인 창 고르기)을 CDP 로 보내지 않고 버린다. 응답은 지금과 같아 웹이 다르게 처리할 것이 없다. `resize` 는 값만 기억하고 그 탭에 크기 덮어쓰기(`Emulation.setDeviceMetricsOverride`)를 보내지 않는다. 사람의 좁은 화면 폭이 에이전트가 읽는 페이지의 배치를 바꾸지 않게 하려는 것이다. 그 탭을 놓으면 다음 탭 확인에서 기억한 값을 보낸다.
- **`tabs` 사건**: 탭마다 `agent`(에이전트가 쥐었는가)를 더한다. 탭 확인(2초)마다 쥔 쪽이 바뀌었으면 목록을 다시 보낸다. 그래서 띠는 쥔 쪽이 바뀐 뒤 2초 안에 바뀌고, 입력 차단은 입력마다 바로 판정한다.
- **화면이 따라가는 탭**(spec DECISION15, DECISION25): 탭 확인이 새 탭을 보면, 지금 탭이 사람 탭이고 새 탭이 held 면 옮기지 않고 목록만 알린다. 그 밖(지금 탭이 에이전트 탭이거나 새 탭이 사람 탭, 로그인 팝업)은 지금처럼 옮긴다.
- **주소를 주고 여는 화면**: 첫 탭이 held 면 거기 이동하지 않고 새 탭을 만들어 그 주소로 연다. 연결 화면의 「먼저 로그인」 이 에이전트가 일하는 탭을 다른 곳으로 보내지 않게 하려는 것이다. 주소 없이 여는 화면은 지금처럼 첫 탭에 붙는다.
- 화면이 스스로 만드는 탭(빈 탭, 위의 새 탭)은 중계를 지나지 않아 쥐지 않는다.

### PR2 에이전트 화면 보기

```text
보는 대화의 작업 과정에 도는 도구 단계가 있고 그 도구 이름의 끝 이름이 ownerBrowser 플러그인의 도구다
  또는 그 대화의 승인 줄이 EXECUTING 이고 그 줄의 플러그인이 ownerBrowser 다
→ 입력창 위(할 일 줄 옆)에 「에이전트 화면 보기」
누르면 옆 패널의 「내 브라우저」 탭을 에이전트 보기로 연다
  화면은 첫 tabs 사건에서 agent 가 참인 첫 탭으로 옮긴다
```

- **카탈로그**: `ConnectorSummary` 와 그 응답 `ConnectorView` 에 `ownerBrowser`(manifest 가 `owner_browser_env` 를 선언했는가)를 더한다. 웹은 위 조건의 앞쪽(도는 MCP 도구나 `EXECUTING` 줄)이 생길 때만 카탈로그를 한 번 읽고 그 화면 동안 기억한다. 대화를 열기만 하면 요청이 늘지 않는다. 읽지 못하면 단추를 보이지 않고 다음 도구 단계에서 다시 읽는다.
- **도구 이름**: 사건의 도구 이름은 `mcp__<서버>__<도구>` 다. 웹은 마지막 `__` 뒤의 끝 이름(`tool-label.ts` 의 `bareName` 과 같은 규칙)을 카탈로그의 `ownerBrowser` 플러그인 도구 이름과 맞춘다. 서버 이름은 연결마다 달라 카탈로그에 없다. 다른 플러그인과 끝 이름이 겹치면 단추가 더 보일 뿐 잠금에는 영향이 없다(잠금은 `browser` 가 판정한다).
- **다른 창**: 보내지 않은 창은 도는 turn 의 실행 트리를 읽어 도는 도구 줄을 만든다(`docs/features/chat.md` 의 「다른 창에서 답하는 중일 때」). 그래서 다른 창에서도 같은 단추가 보인다. 승인 줄은 승인 사건마다 다시 읽는 지금 목록을 쓴다.
- **원고 저장 승인 줄**: 저장 확인까지 최대 15분 `EXECUTING` 으로 남아 브라우저를 쓰지 않는 동안에도 실행 중으로 보이므로 단추 판정에서 뺀다(`contentDraft` 가 있는 줄). 「뒤 PR 의 경계」 의 승인 중 회수 막기와 같은 까닭이다.
- **첫 탭**(spec DECISION15): Chrome 의 `/json/list` 는 마지막으로 활동한 탭부터 준다. 그래서 `agent` 가 참인 첫 탭이 에이전트가 지금 일하는 탭이다. 쥔 탭이 아직 없으면(도구가 브라우저에 닿기 전) 지금 탭에 머물고, 그 뒤 에이전트가 새 탭을 열면 지금 탭이 사람 탭이라 따라가지 않는다. 그래서 첫 목록에 쥔 탭이 없으면 다음 목록에서 처음 보인 쥔 탭으로 한 번 옮긴다.
- **패널**: 옆 패널 목표에 에이전트 보기(`{ kind: "agent-browser" }`)를 더한다. 「할 일」·「내 브라우저」 탭 머리는 그대로이고 「내 브라우저」 탭이 화면을 그린다. 넘기기 카드가 패널을 여는 규칙은 지금과 같아 새 넘기기가 생기면 그 넘기기의 화면으로 바뀐다. 단추를 다시 누르면 화면을 새로 만들어 다시 에이전트 탭으로 옮긴다. 도구가 끝나도 패널은 남는다(spec DECISION16). 휴대폰은 지금 패널 규칙대로 전체 폭 시트다(spec DECISION17).
- **띠**: 화면은 `tabs` 의 지금 탭이 `agent` 면 화면 위에 「에이전트가 쓰는 중이에요. 끝나면 조작할 수 있어요」 띠를 보이고 주소 칸, 뒤로, 새로 고침, 키보드, 화면 누르기를 막는다. 서버도 버리므로 웹의 막기는 보이는 모양이다. `/browser` 와 대화 패널이 같은 부품이라 어디서 열든 같다.

### PR3 사람 잠금

```text
POST /api/v1/browser/takeover {conversationId?}
  BrowserHumanHold.takeOver(userId, 회수한 자리)
    브라우저 줄이 없으면 BROWSER_NOT_FOUND, RUNNING 이 아니면 BROWSER_BUSY
    BrowserApprovedRuns.running(userId) 이면 BROWSER_APPROVAL_RUNNING(409)
    update user_browser set human_held_at = now where user_id = ? and status = 'RUNNING' and human_held_at is null
      0 줄이고 이미 잠겨 있으면 그대로 성공(두 번 누르기), 아니면 BROWSER_BUSY
    다시 BrowserApprovedRuns.running 이면 잠금을 풀고 BROWSER_APPROVAL_RUNNING
    회수한 자리를 메모리에 두고, 그 브라우저의 쥔 탭 기록을 지우고, 열린 중계 연결을 4423 으로 닫는다
POST /api/v1/browser/give-back
  BrowserHumanHold.giveBack(userId): update ... set human_held_at = null where user_id = ? and human_held_at is not null
```

- **잠금 칸** `user_browser.human_held_at`(`DATETIME(6)`, 비어 있으면 잠기지 않았다). 엔티티는 이 칸을 `insertable = false, updatable = false` 로 읽기만 한다. 활동 기록, 자동 중지, 켜기와 끄기가 엔티티를 저장해도 이 칸을 되돌리지 않고, 회수와 돌려주기는 저장소의 조건부 갱신으로만 쓴다(rule-68). 잠금은 줄이 `RUNNING` 이고 쥔 시각이 이번 켜기(`started_at`) 뒤일 때만 뜻이 있다. 끄기(`halt`)와 상태 맞추기(`resetStopped`)가 끈 뒤 같은 갱신으로 비우고, 끄기가 실패해 칸이 남은 채 다시 켜면 지난 잠금으로 보아 점검과 다음 회수가 비운다.
- **회수한 자리**: 대화 패널에서 회수하면 웹이 그 대화의 공개 번호를 보낸다. `BrowserHumanHold` 가 사용자마다 메모리에 두고 풀릴 때 지운다. 넘기기 알림을 낼지 고를 때만 쓴다(spec DECISION18). Control Plane 이 다시 뜨면 잃고, 그때는 알림을 낸다. 알림이 하나 더 생기는 쪽이 안전하다.
- **중계 HTTP**(`BrowserGateway.open`): 표식에서 주인을 찾고 허용 목록을 본 뒤, 브라우저를 켜기 전에 잠금을 읽는다. 잠겼으면 `BROWSER_HUMAN_HELD` 이고 `BrowserGatewayStatus` 가 423 으로 바꾼다. 쿠키 읽기(`/json/version`)와 남긴 탭 닫기(`/json/close`)도 같다. 잠근 동안 에이전트는 브라우저에 아무것도 하지 못한다(spec DECISION5).
- **중계 WebSocket**: Bun 의 `WebSocket` 은 handshake 실패의 상태 코드를 주지 않는다. 그래서 handshake 판정이 `BROWSER_HUMAN_HELD` 를 받으면 연결을 받아들이고 표시만 남기며, 처리기가 열린 직후 close code 4423(`human_held`)으로 닫는다. 열린 연결은 `BrowserHumanHold.watch(browserId, 닫기)` 로 등록하고, 회수가 그 브라우저의 등록을 모두 불러 4423 으로 닫는다. 등록한 뒤 잠금을 한 번 더 읽어, 판정과 등록 사이에 회수가 끼어도 연결이 남지 않게 한다.
- **쥔 탭**: 회수가 `BrowserAgentTabs.forget(browserId)` 로 그 브라우저의 기록을 지운다. 연결은 위에서 닫히지만 만든 지 30초 안의 탭 기록이 남아 사람 입력을 막지 않게 한다. 잠근 동안에는 새 연결과 새 탭이 없어 쥔 탭이 생기지 않는다.
- **화면의 `hold` 사건**: 화면은 탭 확인(2초)마다 `{"human": 잠겼는가, "approved": 승인한 실행이 도는가}` 를 만들고 바뀌었을 때와 처음 붙었을 때 보낸다. `approved` 는 지금 탭들에 쥔 탭이 있을 때만 포트로 묻는다. 에이전트가 쓰지 않을 때 사람 화면에 조회가 없다. 웹은 회수와 돌려주기가 성공하면 다음 사건을 기다리지 않고 그 값으로 띠를 바꾼다.
- **띠**(웹, `/browser` 와 대화 패널이 같은 부품):

  | 상태 | 띠 | 화면 누르기·Esc |
  | --- | --- | --- |
  | `human` | 「직접 하는 중이에요」 와 「돌려주기」. 모든 탭이 조작된다 | 그대로 원격으로 간다 |
  | 지금 탭이 쥔 탭이고 `approved` | 「승인한 작업을 마무리하는 중이에요」. 단추 없음 | 버린다. 확인 띠도 없다 |
  | 지금 탭이 쥔 탭 | 「에이전트가 쓰는 중이에요」 와 「직접 할게요」 | 버리고 「직접 하시겠어요?」 확인 띠(「직접 할게요」, 「계속 볼게요」)를 띄운다 |
  | 그 밖 | 없음 | 그대로 원격으로 간다 |

- **돌려주기의 글**(spec DECISION12, DECISION24): 대화 패널의 화면은 돌려주기가 성공하면 그 대화의 열린 넘기기를 본다. 결제(`PAYMENT`) 밖의 넘기기가 하나라도 있으면 `human_browser` 의 정해 둔 글을 다음 메시지로 보낸다. 결제 카드만 남았으면 보내지 않는다. 결제는 「결제했어요」 와 「사지 않을래요」 가운데 사람이 고르는 일이라 이어 가라는 글이 맞지 않다. `/browser` 의 화면은 넘기기를 모르므로 잠금만 푼다.
- **10분 풀림**(spec DECISION7): `UserBrowserSweeper` 가 점검마다 잠긴 `RUNNING` 줄을 본다. 화면이 열려 있으면 그 시각을 기억하고, 닫혀 있으면 잠근 시각과 마지막으로 열린 것을 본 시각 가운데 늦은 것에서 `idle-timeout` 이 지났을 때 푼다. 열린 것을 본 적이 없으면(다시 뜬 뒤) 점검이 처음 돈 시각을 쓴다. 점검 간격(1분)만큼 늦게 풀릴 수 있다. 잠긴 브라우저는 REQ1 의 기다림으로 보아 30분 규칙을 따르므로, 10분 풀림이 끄기보다 먼저 온다.

### PR3 회수 중 에이전트 도구와 넘기기

- **플러그인**: 세 플러그인(네이버 쇼핑, 코레일 KTX, 네이버 블로그)의 `cdp.ts` 가 중계의 423 과 close code 4423 을 닿지 않음과 나눠 `<플러그인>_BROWSER_IN_USE` 오류에 넘기기 `{need: "human_browser", tab?}` 를 싣는다. 탭은 그 연결이 붙은 탭(`devtools/page/<탭>`)이고, 탭을 만들기 전에 거절됐으면 없다. 쇼핑과 KTX 의 탭 열기는 그 탭을 남긴 탭으로 두고 닫지 않는다(닫기도 423 이다). 블로그는 편집기 오류로 감싸여도 오류를 고르는 마지막 단계가 중계를 한 번 더 불러 그 신호를 찾는다. 결과는 지금 넘기기와 같이 `isError` 없는 `HUMAN_ACTION_REQUIRED` 이고 안내 글만 「사람이 직접 쓰는 중이라 돌려줄 때까지 다시 부르지 않는다」 로 다르다.
- **어휘** `HandoffNeed.HUMAN_BROWSER`(`human_browser`). 제목은 「「내 브라우저」를 직접 쓰는 동안 멈췄어요」, 웹의 「다 했어요」 글과 돌려주기의 글은 「「내 브라우저」를 돌려줬어요. 멈춘 단계부터 이어서 해 주세요.」 다. 카드와 닫기는 다른 넘기기와 같다.
- **탭 없는 넘기기**: fos-ctx 1.10.0 이 `tab` 이 없는 넘기기를 보낸다. 본문에서 `tab` 칸을 빼고 서명할 글의 `tab` 자리에 빈 글을 넣는다. Control Plane 은 `tab` 이 없거나 `null` 이면 빈 글로 서명을 확인하고 탭 없이 적는다. 웹은 탭 없는 `HUMAN_BROWSER` 카드를 받는다(지금은 `TAB_CHECK` 만 받는다).
- **전환 배포**(rule-66): 운영은 plugin 을 먼저, Control Plane 을 다음에 올린다. 그 사이 새 fos-ctx 가 보낸 `human_browser` 는 옛 Control Plane 이 어휘 밖이라 거절해 카드만 뜨지 않고, 모델은 결과의 안내 글을 받는다. 옛 Control Plane 은 423 과 4423 을 내지 않으므로 플러그인이 `human_browser` 를 내는 일은 새 Control Plane 뒤에만 생긴다. 탭 있는 넘기기의 본문과 서명은 바뀌지 않아 옛 fos-ctx 와 새 Control Plane 도 그대로 돈다. 다음 배포에서 걷을 옛 경로는 없다.
- **알림**(spec DECISION18): `ConnectorHandoffs.record` 가 `HUMAN_BROWSER` 이고 회수한 자리가 그 대화이면 알림을 만들지 않는다. 카드는 그대로 낸다.

### PR3 승인과 회수

- **포트** `browser.application.port.BrowserApprovedRuns`: `boolean running(long userId)`. 구현은 `connector` 의 `action` 하위 기능의 `ConnectorBrowserApprovedRuns` 다. 그 사용자의 `EXECUTING` 이고 위험도가 `FINANCIAL` 이나 `PURCHASE` 인 줄의 플러그인 가운데 manifest 가 `owner_browser_env` 를 선언한 것이 있으면 참이다. 카탈로그를 읽지 못하면 참으로 본다. 잘못 막아도 실행이 끝나면(최대 60초, `ConnectorActionExpirer`) 풀린다. 원고 저장 승인은 위험도가 `WRITE` 라 들지 않는다.
- **회수 중 승인**(spec DECISION26): `ConnectorApprovalBrowser.require` 가 브라우저를 켜기 전에 `BrowserHumanHold.held(userId)` 를 본다. 잠겼으면 `CONNECTOR_BROWSER_HELD`(409)로 거절하고 줄을 건드리지 않는다. 웹은 그 코드에 「직접 쓰고 계신 브라우저를 돌려준 뒤 승인해 주세요.」 를 보인다. 금융·구매, 일반 쓰기, 원고 저장이 모두 이 자리를 지난다.

### PR4 이어 가기

```text
채우기(승인한 실행)가 claim 전에 사람이 해야 하는 화면에서 멈춘다
  플러그인 결과: HUMAN_ACTION_REQUIRED, handoff {code, need, tab}, resume {step}
  결과 판정(ConnectorCheckoutOutcome): 쓰지 않은 ticket 을 거두고 EXECUTING → WAITING, 넘기기 카드와 알림
  자동 turn: 「사람이 마치고 다음 메시지를 보내면 approval_resume 를 한 번 부른다」
사람이 그 대화에 메시지를 보낸다 → 넘기기 카드가 닫힌다(지금 규칙)
에이전트가 approval_resume 을 부른다(Control Plane MCP 도구, 인자 없음)
  ConnectorApprovalResume: 그 대화의 WAITING 줄 → 수명, 같은 브라우저, 카드 닫힘, 사람 잠금 확인
    잠근 줄에서 snapshot, 같은 의도, 관문, 그 순간의 구매 상한 → WAITING → EXECUTING
  ConnectorFinancialApproval.run: 새 ticket → 대시보드 execute 에 resume {tab, step} → 같은 결과 판정
  도구의 결과: 그 줄의 결과 글(자동 turn 과 같은 글). 자동 turn 으로 다시 전하지 않는다
```

**상태 `WAITING`**(`connector_action.status`): 「승인은 끝나지 않았고 사람을 기다린다」 다. `EXECUTING` 으로만 가고(이어 가기), 수명이 다하면 `FAILED` 다.

| 칸 | 뜻 |
| --- | --- |
| `waiting_since` | 처음 멈춘 시각. 다시 멈춰도 바꾸지 않는다 |
| `resume_step` | 플러그인이 낸 이어 갈 단계. Control Plane 은 뜻을 모르고 돌려준다 |
| `resumed_at` | 마지막으로 이어 간 시각. 오래 남은 `EXECUTING` 정리(`markStale`)가 `decided_at` 대신 이 시각을 본다 |
| `expires_at` | `WAITING` 동안은 `waiting_since` + 30분, 표식이 끝나는 시각이다. 이어 간 실행의 ticket 만료도 이 시각을 넘지 않는다 |
| `handoff_*` | 이어 갈 탭은 `handoff_tab` 이다. 다시 멈추면 닫힌 넘기기를 새 넘기기로 바꾼다. 열린 넘기기는 그대로 둔다 |

- **어디서 멈추면 이어 가는가**: claim 전뿐이다. claim 뒤(「구매하기」 를 누른 뒤)의 사람 화면은 지금처럼 결과를 모르는 것(`UNKNOWN`)이다(spec REQ8 의 「결과를 모를 때의 처리는 그대로」). 그래서 이어 갈 수 있는 줄은 claim 을 소비하지 않은 줄이다.
- **ticket**: 멈출 때 쓰지 않은 ticket 을 거둔다(`ConnectorActionExecution.releaseTicket`). 기다리는 동안 어떤 ticket 도 claim 되지 않는다. 이어 갈 때 새 ticket 을 낸다. claim 소비는 승인 하나에 한 번 그대로다. 「멈출 때마다 한 번」 은 `WAITING` 에서 `EXECUTING` 으로 가는 전이가 행 잠금 아래에서 한 번만 일어나 지킨다.
- **플러그인 계약**: 결과의 `resume` 은 정확히 `{step}` 이고 `step` 은 `[a-z][a-z0-9_]{0,31}` 이다. 없거나 모양이 틀리면 지금처럼 `FAILED`(`human_action_required`)다. 이어 갈 때 Control Plane 은 대시보드 execute 본문의 `execution.resume` 에 `{tab, step}` 을 싣고, 대시보드가 같은 모양을 다시 보고 플러그인에 env `FOS_APPROVAL_RESUME_JSON` 으로 넘긴다. 플러그인은 새 탭 대신 그 탭에 붙어 상품 화면부터 다시 간다. 탭이 없으면 `NAVER_SHOPPING_TAB_GONE`(claim 전 실패라 `FAILED`)이다.
- **claim 전 로그인 확인(쇼핑)**: 채우기는 화면 분류 뒤 로그인 쿠키를 본다. 없으면 그 탭을 로그인 화면(`owner_browser_login_url`)으로 옮기고 `login` 으로 멈춘다. 로그아웃 상태의 「구매하기」 는 로그인 화면으로 가 claim 만 쓰고 결과를 모르게 되기 때문이다. 주소 이동만 하고 누르지 않는다.
- **이어 가기 도구** `approval_resume`: 인자가 없다. 호출한 실행의 대화에서 그 사용자의 가장 늦은 `WAITING` 줄 하나를 고른다. 다른 대화의 줄은 고르지 않는다(spec REQ9). 먼저 살펴보기 트리에서는 다른 쓰기 도구처럼 거절한다. 결과는 늘 오류가 아닌 글이다. Hermes 의 연속 오류 정지가 Control Plane MCP 서버를 막지 않게 한다.

| 경우 | 줄 | 도구의 답 |
| --- | --- | --- |
| 그 대화에 `WAITING` 줄이 없다 | 그대로 | 이어 갈 승인이 없다. 원하면 새로 준비한다. 그 대화에서 이어 가는 중(`EXECUTING` 이고 멈춘 적 있음)이면 이어 가는 중이니 기다리라고 답한다 |
| 수명이 지났거나, 브라우저가 꺼졌거나 그 뒤 다시 켜졌다(`started_at` > `waiting_since`) | `FAILED`(`resume_ended`) | 끝냈다는 안내와 그 줄의 결과 |
| 넘기기 카드가 아직 열려 있다(그 뒤 사람이 메시지를 보내지 않았다) | 그대로 | 사용자가 마치고 메시지를 보낸 뒤 다시 부른다 |
| 사람이 브라우저를 쥐고 있다(PR3) | 그대로 | 돌려준 뒤 다시 부른다 |
| 연결이나 선언이 바뀌어 같은 승인으로 실행할 수 없다 | `FAILED`(`resume_ended`) | 끝냈다는 안내와 그 줄의 결과 |
| 대시보드에 닿지 못해 플러그인 상태를 읽지 못했다 | 그대로 | 잠시 뒤 다시 한다. 새로 준비하지 않는다. 수명이 끝내므로 남겨도 안전하다 |
| 실행을 보냈는데 결과 글을 읽지 못했다 | 결과 판정대로 | 보냈다는 안내. 새로 준비하지 말고 카드와 「내 브라우저」 를 확인하게 한다 |
| 그 순간의 구매 상한에 걸린다(spec DECISION19) | `FAILED`(`purchase_limit`) | 끝냈다는 안내와 걸린 상한 |
| 실행했다 | 결과 판정대로(`SUCCEEDED`, `FAILED`, `UNKNOWN`, 다시 `WAITING`) | 그 줄의 결과 글 |

- **상한 재판정**: 잠근 줄에서 `PurchaseLimits.requireWithin(user, 자기 줄, 예상 합계, 지금)` 이다. 오늘 합계로 보고, 뒤의 claim 이 승인 날짜의 합계로 한 번 더 본다. 둘 다 지나야 「구매하기」 를 누른다.
- **하루 합계**(spec DECISION20): `WAITING` 줄을 `EXECUTING`, `SUCCEEDED`, `UNKNOWN` 과 함께 센다. 결제 화면까지 가지 못하고 끝나면 `FAILED` 라 빠진다. 같은 의도의 새 준비도 `WAITING` 이 막는다. 수명이 지난 `WAITING` 은 끝내는 일정이 돌기 전에도 막지 않는다.
- **결과 전달**: 처음 멈춘 결과는 자동 turn 으로 간다. 모델 입력에는 정해 둔 안내만 싣고 플러그인 글은 싣지 않는다. 이어 간 결과와 이어 가기에서 끝낸 줄은 도구의 답으로 가므로 전했다고 적는다.
- **끝내는 일정**: `ConnectorActionExpirer` 가 1분마다 `WAITING` 줄 가운데 수명이 지났거나 브라우저가 그 뒤 다시 켜진 줄을 `FAILED`(`resume_ended`)로 끝낸다. 하루 합계와 탭 지키기, 브라우저 유지가 바로 풀린다. 사람이 탭을 닫은 것은 다음 이어 가기가 플러그인의 `NAVER_SHOPPING_TAB_GONE` 으로 안다.
- **브라우저 유지**(REQ1): `BrowserWaitDemand` 가 이번 켜기 뒤에 처음 멈춘 살아 있는 `WAITING` 줄을 기다림으로 센다.
- **보기와 잠금**: 이어 간 실행도 `EXECUTING` 이라 PR2 의 보기 단추와 PR3 의 회수 막기가 그대로 걸린다. `WAITING` 동안은 쥔 탭이 없어 사람이 그 탭에서 로그인한다.
- **웹**: 승인 카드가 `WAITING` 에 「「내 브라우저」에서 멈췄어요. 로그인 같은 화면을 마치고 알려 주시면 이 승인으로 이어서 해요.」 를 보이고 단추는 없다. 넘기기 카드와 「다 했어요」 는 지금 그대로다.

### PR4 열린 넘기기의 탭 지키기

- **포트** `browser.application.port.BrowserKeptTabs`: `boolean kept(long userId, String tab, Instant since)`. 구현은 `connector` 의 `ConnectorBrowserKeptTabs` 다. 그 탭을 가리키는 열린 넘기기(대화가 있고 `handoff_at` 이 `since` 이후)나 살아 있는 `WAITING` 줄(`waiting_since` 이 `since` 이후)이 있으면 참이다. `since` 는 그 브라우저의 `started_at` 이다.
- **중계** `GET /json/close/<탭>`: 판정을 지난 뒤 `BrowserGateway.keeps` 가 참이면 Chrome 에 넘기지 않고 빈 409 다. 판정하지 못하면 닫지 않는 쪽으로 답한다. 세 플러그인은 남긴 탭 닫기의 실패를 이미 무시하고 목록에서 잊으므로 플러그인을 고치지 않는다. 잊은 탭은 브라우저가 꺼질 때 사라진다.
- 카드가 닫히고 표식도 끝나면 지금처럼 다음 탭 열기가 닫는다. 이어 가는 실행 자신은 `EXECUTING` 이라 그 탭을 닫을 수 있다.

### 뒤 PR 의 경계

PR 마다 이 경계 안에서 상세를 닫는다. 경계를 바꿔야 하면 그 PR 에서 이 절을 고치고 독립 검토를 다시 받는다.

#### 에이전트 탭과 보기(PR2)

위 「PR2 에이전트가 쥔 탭」 과 「PR2 에이전트 화면 보기」 가 상세다.

#### 사람 잠금(PR3)

위 「PR3 사람 잠금」, 「PR3 회수 중 에이전트 도구와 넘기기」, 「PR3 승인과 회수」 가 상세다.

#### 이어 가기 표식(PR4, PR5)

PR4 는 위 「PR4 이어 가기」 와 「PR4 열린 넘기기의 탭 지키기」 가 상세다. PR5 는 아래 경계와 PR4 의 계약(`resume {step}`, `WAITING`, `approval_resume`)을 KTX 좌석 확보에 붙인다.

- **표식 자리**: 그 승인 줄(`connector_action`)이다. 기존 넘기기 칸에 더해 사람 기다림 상태, 처음 멈춘 시각, 플러그인이 낸 단계 표식을 둔다. 새 테이블을 만들지 않는다.
- **플러그인 계약**: 승인한 실행의 결과가 넘기기와 함께 이어 갈 단계와 탭을 낸다. Control Plane 은 단계의 뜻을 모르고 그대로 돌려준다.
- **이어 가기**: 에이전트가 부르는 도구가 그 대화의 살아 있는 표식을 골라, Control Plane 이 같은 승인으로 실행을 한 번 더 보낸다. 실행 ticket 의 단일 소비를 「멈출 때마다 한 번」 으로 넓히는 방식은 PR4 가 [ADR-20261113 / purchase-checkout](../../adr/ADR-20261113-purchase-checkout.md) 과 함께 정한다.
- **열린 넘기기의 탭 지키기**: 중계가 열린 넘기기나 살아 있는 표식이 가리키는 탭의 `/json/close` 를 거절한다. 세 플러그인은 남긴 탭 닫기의 실패를 이미 무시하므로 플러그인을 고치지 않는다. 열린 넘기기는 `connector` 가 알므로 `browser` 에 포트를 하나 더 둔다. 플러그인이 잊은 그 탭은 브라우저가 꺼질 때 사라진다.

## 실패와 일관성

- **REQ1 의 경쟁**: 기다림을 본 뒤 끄기 직전에 `stopIfIdle` 이 줄을 다시 읽는 지금 규칙을 그대로 쓴다. 그 사이 새 넘기기가 생겨도 다음 점검(1분)부터 지킨다. 그 1분 안에 꺼지는 경우는 넘기기가 생긴 시점에 이미 활동이 10분 넘게 없었다는 뜻인데, 넘기기는 플러그인이 중계로 브라우저를 쓰는 중에 생기므로 그 순간 활동이 기록된다. 그래서 실제로는 생기지 않는다.
- **포트 조회 실패**: `waiting` 이 예외를 던지면 그 줄은 건너뛴다(끄지 않는다). 점검의 다른 줄은 지금처럼 이어 간다.
- **REQ2 의 경쟁**: 켠 뒤 승인 트랜잭션 사이에 브라우저가 꺼질 수 있는 길은 자동 중지뿐이다. 그 줄은 아직 `PENDING` 이라 REQ1 이 지키고, 켜기가 활동을 기록한다.
- **두 번 누르기**: 두 요청이 함께 와도 `ensureRunning` 은 켜기를 하나로 모으고, 승인 트랜잭션의 줄 잠금이 실행을 하나로 만든다(지금 그대로).
- **PR2 의 쥔 탭과 띠의 어긋남**: 입력 차단은 입력마다 `BrowserAgentTabs` 를 보므로 어긋나지 않는다. 띠는 탭 확인(2초) 간격만큼 늦게 바뀐다. 그 사이 웹이 막지 않은 입력은 서버가 버리고, 놓인 뒤 막힌 입력은 다음 목록에서 풀린다.
- **연 탭 읽기 실패**: 그 새 탭을 사람 탭으로 본다. 플러그인이 곧 붙으면 연결 수로 쥔 탭이 되고, 화면은 지금 탭이 사람 탭이면 그 탭으로 옮기지 않았으므로 잃는 것이 없다. 지금 탭이 에이전트 탭이었으면 지금처럼 옮긴다.
- **회수와 승인의 경쟁(PR3)**: 승인이 잠금을 본 뒤 실행 줄을 `EXECUTING` 으로 커밋하기 전에 회수가 잠금을 쓰면, 회수의 두 번째 확인도 실행 줄을 보지 못해 둘 다 지나간다. 그 실행은 중계에서 423 을 받아 넘기기로 끝나고 돈이나 좌석은 움직이지 않는다. 두 요청이 수 밀리초 안에 겹쳐야 생기고 결과가 안전한 쪽이라 잠금을 하나로 묶지 않는다.
- **회수와 도는 도구(PR3)**: 회수가 연결을 닫는 순간 플러그인이 보내던 명령은 4423 으로 끝난다. 승인 없는 도구는 그 탭을 남기고 넘기기로 끝난다(spec REQ5). 회수 뒤에도 Control Plane 이 메모리에 둔 연결 등록이 남는 일은 없다. 연결이 닫히면 등록도 닫는다.
- **잠금 칸과 엔티티 저장(PR3)**: 엔티티가 그 칸을 쓰지 않으므로 오래된 엔티티 저장이 잠금을 되돌리지 않는다. 끄기가 잠금을 비우는 갱신은 끄기를 마친 뒤에 하므로, 그 사이 회수가 들어와도 `RUNNING` 이 아니라 조건에 걸리지 않는다.
- **배포 순서**: PR1 은 backend 와 웹이 한 이미지로 함께 나간다. 웹의 문구가 없을 때도 기존 대체 문구가 보이므로 순서가 어긋나도 깨지지 않는다.

## 검증 전략

| ID | 경계 | 확인 |
| --- | --- | --- |
| REQ1 | `UserBrowserSweeper` 단위 | 기다림이 있으면 10분을 넘겨도 끄지 않고 30분을 넘기면 끈다. 기다림이 없으면 10분에 끈다. 포트 예외면 끄지 않는다 |
| REQ1 | `ConnectorBrowserWaitDemand` 저장소 통합(H2) | 열린 넘기기, 닫힌 넘기기, 대화 없는 넘기기, 켜기 전의 넘기기, 기다리는 승인(브라우저 플러그인과 아닌 것), 시간이 지난 승인, 남의 줄을 가른다 |
| REQ1 | `BrowserProperties` 단위 | `wait-hold-timeout` 이 `idle-timeout` 보다 작으면 기동 검사가 실패한다 |
| REQ2 | `ConnectorApprovalBrowser` 단위 | 브라우저 플러그인 줄에서 켜기 실패 코드마다 `CONNECTOR_BROWSER_UNAVAILABLE`, `BROWSER_DISABLED` 는 그대로, 성공하면 활동 기록, 아닌 플러그인과 끝난 줄은 켜지 않는다 |
| REQ2 | 승인 통합 | 켜기 실패 뒤 줄이 `PENDING` 그대로이고, 피드백 사건과 하루 합계에 아무것도 남지 않는다. 다시 승인하면 실행한다 |
| REQ2 | 웹 단위 | 오류 문구 표가 새 코드에 정해 둔 글을 낸다 |
| REQ4 | `BrowserAgentTabs` 단위 | 연결이 열리면 쥐고 모두 닫히면 놓는다, `/json/new` 뒤 30초 안은 쥐고 지나면 놓는다, 연 탭이 쥔 탭이면 쥐고 연 탭을 놓으면 놓는다, 쥔 탭이 없으면 연 탭을 읽지 않는다 |
| REQ4 | 중계 컨트롤러·WebSocket 단위 | `/json/new` 의 200 이 탭을 쥐게 하고 실패는 쥐지 않는다, `page` 연결은 열려 있는 동안 쥐고 닫히면 놓는다, `browser` 연결은 쥐지 않는다 |
| REQ4 | `BrowserScreenSession` 단위 | 쥔 탭에는 누르기·글자·휠·주소·뒤로·새로 고침·확인 창 입력을 보내지 않고 탭 옮기기는 된다, 사람 탭은 조작된다, `tabs` 에 `agent` 가 실리고 놓이면 다시 알린다, 사람 탭을 보는 중 쥔 새 탭으로 옮기지 않고 사람 팝업은 따라간다, 주소를 주고 열 때 첫 탭이 쥔 탭이면 새 탭을 연다, 쥔 탭에 크기 덮어쓰기를 보내지 않는다 |
| REQ3 | 웹 단위 | 단추 판정(도는 브라우저 도구, 끝난 도구, 브라우저가 아닌 도구, `EXECUTING` 줄, 카탈로그 없음) |
| REQ3, REQ4 | 웹 브라우저 검사 | 도구가 도는 동안 단추가 보이고 누르면 패널이 에이전트 탭을 열며, 쥔 탭이면 띠가 보이고 화면 누르기가 입력을 보내지 않는다. 도구가 끝나면 단추가 사라지고 패널은 남는다 |
| REQ5 | `BrowserHumanHold` 와 저장소 통합(H2) | 회수는 `RUNNING` 에만 잠그고 두 번 눌러도 성공, 승인한 실행 중이면 거절하고 잠그지 않는다, 돌려주기와 끄기가 잠금을 비운다, 엔티티 저장이 잠금을 되돌리지 않는다, 회수가 열린 연결을 닫는다 |
| REQ5 | 중계 컨트롤러·handshake·WebSocket 단위 | 잠긴 동안 HTTP 창구가 423 이고 브라우저를 켜지 않는다, WebSocket 이 4423 으로 닫힌다, 회수하면 열린 연결이 4423 으로 닫힌다 |
| REQ5 | `ConnectorApprovalBrowser` 단위와 승인 통합 | 잠긴 동안 승인이 `CONNECTOR_BROWSER_HELD` 이고 줄이 `PENDING` 그대로, 하루 합계에 들지 않는다 |
| REQ5 | `ConnectorHandoffRequest`, `ConnectorHandoffs` 단위 | 탭 없는 `human_browser` 의 서명이 맞으면 받고 탭 없이 적는다, 회수한 대화의 넘기기는 알림이 없고 다른 대화는 알림이 있다 |
| REQ5 | 플러그인 테스트(쇼핑, KTX, 블로그) | 423 과 4423 이 `human_browser` 넘기기 결과가 되고 쇼핑·KTX 는 탭을 남긴다 |
| REQ5 | fos-ctx 테스트 | 탭 없는 `human_browser` 를 빈 글로 서명해 보낸다, 탭 있는 넘기기의 서명은 그대로다 |
| REQ5, REQ7 | 화면과 웹 단위, 웹 브라우저 검사 | `hold` 사건이 바뀔 때 오고 쥔 탭이 없으면 승인 실행을 묻지 않는다, 띠 네 상태, 화면 누르기와 Esc 가 확인 띠를 띄운다, 회수와 돌려주기, 패널의 돌려주기는 열린 넘기기가 있을 때만 글을 보내고 `/browser` 는 보내지 않는다 |
| REQ6 | `ConnectorBrowserApprovedRuns` 저장소 통합(H2) | 실행 중인 브라우저 금융·구매 줄만 참, 끝난 줄과 브라우저가 아닌 플러그인과 남의 줄은 거짓 |
| REQ7 | `UserBrowserSweeper` 단위 | 화면이 닫힌 뒤 10분이 지나면 풀고 열려 있거나 안 지났으면 두고, 잠긴 브라우저는 10분 유휴에 끄지 않는다 |
| REQ8, REQ9 | `CheckoutApprovalTest`(금융 틀 통합) | claim 전 멈춤이 `WAITING` 과 넘기기, 하루 합계, ticket 거두기가 되고 같은 의도를 막는다. 카드가 닫히기 전과 다른 대화의 이어 가기는 거절, 사람 turn 뒤 이어 가기는 새 ticket 과 남긴 탭으로 주문서까지 가고 claim 은 한 번이다. 다시 멈추면 수명이 늘지 않는다. 상한을 낮췄으면 `purchase_limit`, 30분과 다시 켠 브라우저는 `resume_ended` 로 끝나 합계에서 빠진다. 사람이 쥔 동안은 그대로 둔다 |
| REQ1, REQ9, REQ11 | `ConnectorBrowserWaitDemandTest`(H2) | 살아 있는 `WAITING` 이 브라우저 유지를 붙잡고 지난 것과 켜기 전의 것은 아니다. 닫지 않을 탭은 열린 넘기기와 살아 있는 표식의 탭뿐이다 |
| REQ11 | `BrowserGatewayTest`, `BrowserGatewayControllerTest` | 지킬 탭의 `json/close` 는 Chrome 에 넘기지 않고 409, 판정 실패는 닫지 않는다 |
| REQ8 | 플러그인 `checkout.test.ts`, `fill-diagnostic.test.ts` | 로그아웃이면 claim 전에 로그인 화면으로 옮겨 `resume` 을 낸다, 이어 가기 env 로 남긴 탭에서 새 탭 없이 주문서까지 가고 claim 은 한 번이다, 다시 로그인 화면이면 다시 넘긴다, 탭이 없으면 `TAB_GONE`, 모양이 틀린 env 는 거절 |
| REQ8 | 대시보드 `test_connector_guard_transport.py` | execute 의 `resume` 이 정확한 모양일 때만 env 로 가고 틀리면 409 |
| REQ8 | 웹 브라우저 검사 `approval-card.spec.ts` | `WAITING` 카드는 단추 없이 안내만 보인다 |

운영 왕복은 PR1 에 없다. Hermes 와 주고받는 것을 바꾸지 않는다.
PR2 도 Hermes 와 주고받는 것을 바꾸지 않는다. 배포 뒤 실계정으로 쇼핑 검색을 시켜 단추와 띠, 끝난 뒤 조작을 한 번 본다(PR 본문의 후속 검증).
PR4 는 대시보드 execute 본문과 플러그인 결과를 바꾼다. 배포 뒤 실계정으로 네이버에서 로그아웃한 채 주문서 채우기를 승인해 로그인 카드가 뜨고, 사람이 로그인한 뒤 「다 했어요」 를 누르면 새 카드 없이 주문서까지 가는지 본다. 결제는 하지 않는다(PR 본문의 후속 검증).
PR3 은 플러그인 결과와 fos-ctx 알림을 바꾼다. 배포 뒤 실계정으로 쇼핑 검색 중 「직접 할게요」 를 눌러 에이전트 도구가 넘기기로 끝나고, 「돌려주기」 뒤 이어 가는지 본다(PR 본문의 후속 검증).

## 결정

| ID | 결정 | 답 | 누가 | 근거 |
| --- | --- | --- | --- | --- |
| DECISION1 | 기다림을 아는 쪽 | `browser` 의 포트를 `connector` 가 구현한다 | 작성자 | 승인과 넘기기는 `connector` 가 갖고 `browser` 는 그보다 아래다. 기존 `BrowserGrantOwners` 와 같은 방식이다 |
| DECISION2 | 30분의 기준 | 브라우저의 마지막 활동(`last_active_at`) | 작성자 | 스펙 REQ1 이 「마지막 활동에서 30분」 이다. 새 칸이 필요 없다 |
| DECISION3 | 30분 값을 두는 곳 | 설정 `assistant.browser.wait-hold-timeout`, 기본 30분 | 작성자 | 10분 값과 같은 자리라 운영에서 함께 본다. 값은 사용자 결정(spec DECISION10) |
| DECISION4 | 카탈로그를 읽지 못할 때 | 기다리는 승인은 기다림으로 센다 | 작성자 | 잘못 세어도 30분 상한이 있고, 반대로 틀리면 승인 직전 꺼지는 2026-11-16 의 문제가 돌아온다 |
| DECISION5 | 승인 때 켜는 자리 | 승인 트랜잭션보다 먼저, `ConnectorActionService.approve` 의 상한 변경 분기 뒤와 `ContentDraftSaveApproval.approve` 의 처음. 부품은 `connector/action/application` | 작성자 | 켜기가 30초까지 걸려 잠금을 쥔 채 기다리지 않는다. 브라우저를 쓰는 승인(금융·구매, 일반 쓰기, 원고 저장)만 판정받고, 브라우저를 쓰지 않는 상한 변경은 빠진다. `action` 은 하위 기능 순서(rule-76)에서 `approval`, `contentdraft` 보다 아래라 두 곳에서 쓸 수 있다 |
| DECISION6 | 켜지 못할 때의 응답 | 409 `CONNECTOR_BROWSER_UNAVAILABLE`. 웹이 정해 둔 문구를 보인다 | 작성자 | 기존 승인 오류처럼 웹이 코드로 문구를 고르고 원문을 보이지 않는다(`connector-action-error.ts`). 409 는 다시 시도할 수 있는 상태 충돌이라는 뜻이다 |
| DECISION7 | PR1 의 웹 변경 | 오류 문구 표 한 줄을 더한다 | 작성자 | 스펙의 PR1 은 「backend 만」 이지만 REQ2 의 인수 조건(카드에 그 문구)을 지키려면 문구 표 한 줄이 필요하다. 화면 구조는 바꾸지 않는다 |
| DECISION8 | 에이전트 탭을 아는 방법(PR2) | 중계에서 열린 `devtools/page` 연결 | 작성자 | 플러그인이 일하는 동안만 열고 끝나면 닫는다. 플러그인과 Hermes 를 바꾸지 않고 안다 |
| DECISION9 | 사람 잠금을 멈추는 자리(PR3) | 중계가 거절하고 플러그인이 넘기기 결과로 바꾼다 | 작성자 | 판정 hook 의 막기는 오류 글이라 spec DECISION3 의 넘기기 결과가 되지 않는다. 진행 중인 연결도 중계가 닫을 수 있다 |
| DECISION10 | 잠금과 표식을 두는 곳(PR3, PR4) | `user_browser` 와 `connector_action` 의 칸 | 작성자 | 테이블을 늘리지 않는다(사용자 결정 2026-11-01). 둘 다 그 줄과 수명이 같다 |
| DECISION11 | 셀 넘기기의 범위 | 대화가 있고 이번 켜기 이후에 생긴 열린 넘기기 | 작성자 | 닫는 길이 사람 turn 뿐이라 대화 밖 넘기기와 버려진 카드는 영영 열려 있다. 다시 켠 브라우저에는 그 탭이 없다 |
| DECISION12 | 기능이 꺼졌을 때의 승인 | `BROWSER_DISABLED` 를 그대로 둔다 | 작성자 | 꺼진 기능에 「잠시 뒤 다시」 는 사용자를 오도한다 |
| DECISION13 | 연결이 붙지 않은 만든 탭을 쥐는 시간(PR2) | 30초 | 작성자 | 플러그인은 만든 직후 붙는다. 붙지 못한 탭이 사람 조작을 영영 막지 않게 짧게 둔다 |
| DECISION14 | 에이전트 탭이 연 새 탭을 아는 방법(PR2) | 처음 보는 탭이 있고 쥔 탭이 있을 때만 탭 목록 명령(getTargets)의 `openerId` | 작성자 | `/json/list` 에는 연 탭이 없다. 에이전트가 쓰지 않을 때 비용을 두지 않는다. 시간이나 사람 입력으로 짐작하면 사람의 로그인 팝업을 에이전트 탭으로 잘못 본다 |
| DECISION15 | 쥔 탭의 크기 덮어쓰기(PR2) | 보내지 않는다 | 작성자 | 휴대폰 폭의 덮어쓰기가 에이전트가 읽는 페이지 배치를 바꾸면 플러그인의 화면 판정이 흔들린다. 프레임은 웹이 칸에 맞춰 줄여 그린다 |
| DECISION16 | 보기 단추가 플러그인 도구를 아는 방법(PR2) | 도구 이름의 끝 이름과 카탈로그 `ownerBrowser` 플러그인의 도구 이름, 승인 줄의 `connectorId` | 작성자 | 실행 사건과 Hermes 를 바꾸지 않는다. 카탈로그는 필요할 때 한 번만 읽는다 |
| DECISION17 | 막힌 입력의 응답(PR2) | 지금과 같은 성공으로 두고 버린다 | 작성자 | 웹은 `tabs` 의 `agent` 로 띠를 그린다. 회수 안내(PR3)는 웹이 같은 값으로 띄운다 |
| DECISION18 | 잠금 칸을 쓰는 방법(PR3) | 엔티티는 읽기만 하고 조건부 갱신으로만 쓴다 | 작성자 | 활동 기록과 자동 중지가 오래된 엔티티를 저장한다. 버전을 올리는 갱신으로 하면 그 저장들이 `BROWSER_BUSY` 로 실패해 끄기와 켜기가 흔들린다(rule-68) |
| DECISION19 | 중계의 거절 신호(PR3) | HTTP 423, WebSocket close code 4423 | 작성자 | 423 Locked 가 「사람이 잠갔다」 를 닿지 않음(502, 503)과 나눈다. close code 는 응용이 쓰는 4000번대에 같은 수를 붙여 찾기 쉽게 한다 |
| DECISION20 | 회수한 자리를 두는 곳(PR3) | JVM 메모리 | 작성자 | 알림 하나를 낼지 고르는 데만 쓴다. 잃으면 알림이 하나 더 생길 뿐이라 칸을 늘리지 않는다 |
| DECISION21 | 돌려주기의 글을 정하는 쪽(PR3) | 웹이 그 대화의 열린 넘기기로 정한다 | 작성자 | 화면 자리(대화 패널, `/browser`)는 웹만 안다. Control Plane 은 잠금만 푼다 |
| DECISION22 | 결제 카드만 남았을 때 돌려주기(PR3) | 글을 보내지 않는다 | 사용자 2026-11-16 | 결제는 「결제했어요」 와 「사지 않을래요」 가운데 사람이 고른다. 「이어서 해 주세요」 는 그 고름을 대신한다 |
| DECISION23 | 승인 실행을 묻는 때(PR3) | 회수할 때와, 화면에 쥔 탭이 있을 때의 탭 확인 | 작성자 | 띠의 「승인한 작업을 마무리하는 중」 은 쥔 탭을 볼 때만 보인다. 에이전트가 쓰지 않을 때 사람 화면에 조회를 두지 않는다 |
| DECISION24 | 10분 풀림의 기준(PR3) | 잠근 시각과 화면이 마지막으로 열려 있던 시각 가운데 늦은 것, 값은 `idle-timeout` | 작성자 | spec DECISION7 이 자동 중지와 같은 기준이다. 점검이 화면 등록부를 보므로 화면 쪽을 고치지 않는다 |
| DECISION25 | 사람 기다림을 두는 곳(PR4) | 승인 줄의 새 상태 `WAITING` 과 칸 셋(`waiting_since`, `resume_step`, `resumed_at`). 탭은 `handoff_tab`, 수명은 `expires_at` | 작성자 | `FAILED` 에 표식을 덧붙이면 끝난 줄이 다시 실행되고 하루 합계와 같은 의도 막기가 상태마다 예외를 갖는다. 새 상태면 기존 상태 집합에 하나를 더하는 것으로 끝난다. 테이블을 늘리지 않는다 |
| DECISION26 | ticket 을 「멈출 때마다 한 번」 으로 넓히는 방식(PR4) | 멈출 때 쓰지 않은 ticket 을 거두고 이어 갈 때 새로 낸다. claim 소비는 승인 하나에 한 번 그대로. 이어 갈 수 있는 멈춤은 claim 전뿐이다 | 작성자 | 「구매하기」 를 누르는 권한이 승인 하나에 한 번인 것을 그대로 둔다. 기다리는 동안 claim 할 수 있는 ticket 이 없다. claim 뒤의 멈춤은 결과를 모르는 것이라 지금 규칙(spec REQ8)이 그대로 맡는다 |
| DECISION27 | 이어 가기 도구를 두는 곳(PR4) | Control Plane MCP 의 `approval_resume`. 인자 없이 호출한 대화의 줄을 고른다 | 작성자 | ticket 을 내고 실행을 보내는 것은 Control Plane 이다. 대화를 이끄는 에이전트가 플러그인을 붙이지 않았어도 부를 수 있다. 모델이 줄 번호를 고르지 않아 다른 대화의 승인을 잇지 못한다 |
| DECISION28 | 이어 가기를 받는 때(PR4) | 넘기기 카드가 닫힌 뒤, 즉 멈춘 뒤 사람이 그 대화에 메시지를 보낸 뒤 | 작성자 | spec DECISION27 의 「다음 사람 메시지」 를 서버가 확인한다. 멈춤을 전하는 자동 turn 에서 모델이 곧바로 이어 가며 같은 화면을 되풀이하지 않는다 |
| DECISION29 | 탭과 단계를 플러그인에 건네는 길(PR4) | 대시보드 execute 본문의 `execution.resume` 과 env `FOS_APPROVAL_RESUME_JSON` | 작성자 | 실행 원문과 ticket 의 해시는 바꾸지 않는다. 대시보드가 모양을 다시 보고 다른 권한 env 와 같이 서버를 만들 때 지운다 |
| DECISION30 | 쇼핑 채우기의 claim 전 로그인 확인(PR4) | 로그인 쿠키가 없으면 그 탭을 로그인 화면으로 옮기고 `login` 으로 멈춘다 | 작성자 | 로그아웃 상태의 「구매하기」 는 로그인 화면으로 가 claim 만 쓰고 결과를 모른다. 이어 갈 수 있는 자리(claim 전)에서 멈추게 한다. 쿠키가 있는데 만료된 경우는 지금처럼 결과를 모르는 것이다 |
| DECISION31 | 이어 가기 직전 상한의 날짜(PR4) | 오늘(이어 가는 순간)의 합계. claim 은 지금처럼 승인 날짜의 합계로 다시 본다 | 작성자 | spec DECISION19 의 「그 순간의 상한과 오늘 합계」 그대로다. 자정을 넘겨 이어 가도 두 날짜를 모두 보므로 느슨해지지 않는다 |
| DECISION32 | 지킬 탭 닫기의 응답(PR4) | 빈 409. 판정하지 못하면 닫지 않는다 | 작성자 | 플러그인은 닫기 실패를 무시한다. 남은 탭은 브라우저가 꺼질 때 사라지고, 사람이 풀던 화면이 사라지는 쪽보다 낫다 |

## 결정 기록 연결

| 결정 | 종류 | ADR 또는 필요 없음의 이유 | 현재 규칙의 원본 |
| --- | --- | --- | --- |
| spec DECISION10, 이 설계의 DECISION1~DECISION4 | operations | PR1 에서 [ADR-20261007 / user-browser](../../adr/ADR-20261007-user-browser.md) 결정 표의 「자동 중지」 행을 고친다 | `docs/features/user-browser.md` 의 「상태 전이」, `application.yml` 의 `assistant.browser` |
| 이 설계의 DECISION5~DECISION7 | architecture | 필요 없음. 기존 승인 오류 코드 관례를 따르는 내부 선택이다 | `ConnectorApprovalBrowser`, `connector-action-error.ts` |
| 이 설계의 DECISION8~DECISION10, DECISION18, DECISION19 | architecture | [ADR-20261116 / browser-lock](../../adr/ADR-20261116-browser-lock.md) | 이 문서의 「PR2 에이전트가 쥔 탭」, 「PR3 사람 잠금」, 「PR3 회수 중 에이전트 도구와 넘기기」 |
| 이 설계의 DECISION20~DECISION24 | architecture | 필요 없음. 잠금 ADR 안의 값과 화면 자리의 선택이다 | 이 문서의 「PR3 사람 잠금」 |
| 이 설계의 DECISION25~DECISION32 | domain-policy, architecture | [ADR-20261113 / purchase-checkout](../../adr/ADR-20261113-purchase-checkout.md) 결정 표의 사람 기다림, 결과 불명, 하루 합계 행과 [ADR-20261111 / connector-handoff](../../adr/ADR-20261111-connector-handoff.md) 결정 표의 남긴 탭 행 | 이 문서의 「PR4 이어 가기」, 「PR4 열린 넘기기의 탭 지키기」 |
| 이 설계의 DECISION13~DECISION17 | architecture | 필요 없음. 쥔 탭 판정 안의 값과 방법이고, 같은 ADR 이 PR3 에서 쥔 탭의 정의를 함께 남긴다 | 이 문서의 「PR2 에이전트가 쥔 탭」, 「PR2 에이전트 화면 보기」 |
