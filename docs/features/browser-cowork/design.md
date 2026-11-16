# 실행 중 브라우저 함께 보기·넘겨받기와 이어 가기 기술 설계

스펙은 [spec.md](spec.md)(사용자 확인 2026-11-16)다. 이 문서는 기능 전체의 경계와 PR1 의 상세를 갖는다.
PR2 부터 PR5 는 아래 「뒤 PR 의 경계」 가 정한 경계를 따르고, 그 PR 을 시작할 때 이 문서에 그 PR 의 절을 더해 상세를 닫는다.

## 요구사항 연결

| ID | 해결 방법 |
| --- | --- |
| REQ1 | `browser` 의 자동 중지가 새 포트 `BrowserWaitDemand` 로 「그 사용자가 사람을 기다리는가」 를 묻는다. 기다리면 유휴 기준을 `idle-timeout`(10분) 대신 새 설정 `wait-hold-timeout`(30분)으로 본다. 포트는 `connector` 가 구현해 열린 넘기기와 브라우저를 쓰는 플러그인의 기다리는 승인을 센다 |
| REQ2 | `connector` 의 새 부품 `ConnectorApprovalBrowser` 가 승인 트랜잭션보다 먼저 브라우저를 쓰는 플러그인의 줄이면 `UserBrowserService.ensureRunning` 으로 켠다. 켜지 못하면 줄을 건드리지 않고 `CONNECTOR_BROWSER_UNAVAILABLE` 로 거절하고, 웹이 그 코드에 정해 둔 문구를 카드에 보인다 |
| REQ3 | PR2. 아래 「뒤 PR 의 경계」 의 「에이전트 탭과 보기」 의 보기 단추 |
| REQ4 | PR2. 같은 절의 에이전트 탭, 보기 전용, 화면이 따라가는 탭 |
| REQ5 | PR3. 같은 절의 「사람 잠금」 의 잠금 상태와 에이전트 도구를 멈추는 자리, 넘기기 어휘 |
| REQ6 | PR3. 같은 절의 승인 중 회수 막기 |
| REQ7 | PR3. 같은 절의 잠금 상태(돌려주기와 10분 풀림) |
| REQ8 | PR4. 같은 절의 「이어 가기 표식」 의 플러그인 계약과 이어 가기 |
| REQ9 | PR4. 같은 절의 표식 자리 |
| REQ11 | PR4. 같은 절의 열린 넘기기의 탭 지키기 |
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
  - 사람 기다림 승인은 PR4, 사람 잠금은 PR3 이 같은 포트에 더한다
- **설정** `assistant.browser.wait-hold-timeout`, 기본 `30m`, `BrowserProperties` 에 더하고 `idle-timeout` 이상이어야 한다(작으면 기동을 멈춘다). 값은 사용자 결정(spec DECISION10)이다. 점검은 `LiveProperties<BrowserProperties>` 에서 직접 읽는다. `UserBrowserService` 는 파일 길이 상한(rule-44)에 닿아 메서드를 더하지 않는다.
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

### 뒤 PR 의 경계

PR 마다 이 경계 안에서 상세를 닫는다. 경계를 바꿔야 하면 그 PR 에서 이 절을 고치고 독립 검토를 다시 받는다.

#### 에이전트 탭과 보기(PR2)

- **에이전트 탭**: 중계는 플러그인이 붙은 탭을 안다. 플러그인은 탭 하나에 `devtools/page/<탭>` WebSocket 을 열어 일하고 끝나면 닫는다(`hermes/connectors/*/src/tab.ts`). 그 연결이 열린 탭을 「에이전트가 쥔 탭」 으로 `browser` 의 메모리에 둔다. 넘기기로 남긴 탭은 연결이 닫혀 쥐지 않는다(REQ4).
- **만들 때부터 쥔 탭**: 중계를 지난 `/json/new` 가 만든 탭은 연결이 붙기 전에도 쥔 탭으로 둔다. 주문서 채우기의 「바로구매」 처럼 에이전트 탭이 연 새 탭도 같다. 그 사이 화면이 그 탭을 사람 탭으로 보지 않게 한다. 브라우저 수준 연결(쿠키 읽기)은 탭을 쥐지 않는다.
- **보기 전용**: 화면 입력(`screenInput`)이 지금 탭이 에이전트가 쥔 탭이면 원격으로 보내지 않는다. 주소를 주고 여는 화면(연결 화면의 「먼저 로그인」 등)은 첫 탭이 에이전트 탭이면 거기 붙지 않고 새 탭을 연다. 화면의 `tabs` 사건에 탭마다 쥔 쪽을 싣고, 웹이 띠를 그린다.
- **화면이 따라가는 탭**: 사람의 탭을 보는 중에 에이전트가 연 새 탭으로 옮기지 않는다(spec DECISION25). 사람의 탭이 연 팝업은 지금처럼 따라간다.
- **보기 단추**: 웹은 실행 사건의 도구 단계와 카탈로그의 `ownerBrowser` 로 「지금 이 대화에서 브라우저 도구가 돈다」 를 안다. 승인한 실행은 승인 줄의 `EXECUTING` 으로 안다. 새 테이블이나 칸은 없다.

#### 사람 잠금(PR3)

- **잠금 상태**: `user_browser` 줄의 칸 하나(사람이 회수한 시각)에 둔다. 테이블을 늘리지 않는다. 그 줄은 `@Version` 낙관적 잠금을 쓰고 활동 기록과 자동 중지가 그 버전에 기대므로, 회수와 돌려주기는 엔티티 저장이 아니라 조건부 갱신(rule-68)으로 쓰고, 끄기(`halt`, `resetStopped`)가 그 칸도 비운다. 화면이 모두 닫힌 시각은 화면 등록부가 알고, 점검이 10분을 넘긴 잠금을 푼다.
- **에이전트 도구를 멈추는 자리**: 중계가 사람 잠금 동안 HTTP 창구(`/json/new`, `/json/version` 등)를 정해 둔 상태 코드로 거절한다. WebSocket 은 Node 의 `WebSocket` 이 handshake 실패의 상태 코드를 주지 않으므로, 연결을 받은 뒤 정해 둔 4000번대 close code 로 닫는다. 회수하는 순간 열린 연결도 같은 close code 로 닫는다. 플러그인은 그 신호를 「사람이 브라우저를 쓰는 중」 넘기기 결과로 바꾼다. 판정 hook 의 막기는 오류 글이라 넘기기 결과(spec DECISION3)가 되지 않아 쓰지 않는다.
- **승인 중 회수 막기**: 금융·구매 승인(`ConnectorFinancialApproval`)을 지나 실행 중인 그 사용자의 줄(주문서 채우기, 좌석 확보, 이어 가기)이 있으면 회수를 거절한다. 원고 저장 승인 줄은 저장 확인까지 최대 15분 `EXECUTING` 으로 남으므로(`ContentDraftJobExpirer`) 넣지 않는다. 스펙 REQ6 의 대상과 같다.
- **회수 중 승인**: PR1 의 `ConnectorApprovalBrowser` 가 사람 잠금을 보고 같은 방식으로 거절한다(spec DECISION26).
- **넘기기 어휘**: `HandoffNeed` 에 회수 어휘 하나를 더하고, 알림 여부는 회수한 자리로 가른다(spec DECISION18). 회수 중 새 탭 열기가 거절된 호출에는 탭이 없는데 지금 넘기기 알림은 `tab` 이 필수이고 서명 글에 들어간다(`ConnectorHandoffRequest`). 그래서 fos-ctx hook 과 서명 형식을 탭 없는 넘기기를 받게 바꾸고, 전환 배포 호환(rule-66)을 그 PR 이 정한다.

#### 이어 가기 표식(PR4, PR5)

- **표식 자리**: 그 승인 줄(`connector_action`)이다. 기존 넘기기 칸에 더해 사람 기다림 상태, 처음 멈춘 시각, 플러그인이 낸 단계 표식을 둔다. 새 테이블을 만들지 않는다.
- **플러그인 계약**: 승인한 실행의 결과가 넘기기와 함께 이어 갈 단계와 탭을 낸다. Control Plane 은 단계의 뜻을 모르고 그대로 돌려준다.
- **이어 가기**: 에이전트가 부르는 도구가 그 대화의 살아 있는 표식을 골라, Control Plane 이 같은 승인으로 실행을 한 번 더 보낸다. 실행 ticket 의 단일 소비를 「멈출 때마다 한 번」 으로 넓히는 방식은 PR4 가 [ADR-20261113 / purchase-checkout](../../adr/ADR-20261113-purchase-checkout.md) 과 함께 정한다.
- **열린 넘기기의 탭 지키기**: 중계가 열린 넘기기나 살아 있는 표식이 가리키는 탭의 `/json/close` 를 거절한다. 세 플러그인은 남긴 탭 닫기의 실패를 이미 무시하므로 플러그인을 고치지 않는다. 열린 넘기기는 `connector` 가 알므로 `browser` 에 포트를 하나 더 둔다. 플러그인이 잊은 그 탭은 브라우저가 꺼질 때 사라진다.

## 실패와 일관성

- **REQ1 의 경쟁**: 기다림을 본 뒤 끄기 직전에 `stopIfIdle` 이 줄을 다시 읽는 지금 규칙을 그대로 쓴다. 그 사이 새 넘기기가 생겨도 다음 점검(1분)부터 지킨다. 그 1분 안에 꺼지는 경우는 넘기기가 생긴 시점에 이미 활동이 10분 넘게 없었다는 뜻인데, 넘기기는 플러그인이 중계로 브라우저를 쓰는 중에 생기므로 그 순간 활동이 기록된다. 그래서 실제로는 생기지 않는다.
- **포트 조회 실패**: `waiting` 이 예외를 던지면 그 줄은 건너뛴다(끄지 않는다). 점검의 다른 줄은 지금처럼 이어 간다.
- **REQ2 의 경쟁**: 켠 뒤 승인 트랜잭션 사이에 브라우저가 꺼질 수 있는 길은 자동 중지뿐이다. 그 줄은 아직 `PENDING` 이라 REQ1 이 지키고, 켜기가 활동을 기록한다.
- **두 번 누르기**: 두 요청이 함께 와도 `ensureRunning` 은 켜기를 하나로 모으고, 승인 트랜잭션의 줄 잠금이 실행을 하나로 만든다(지금 그대로).
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

운영 왕복은 PR1 에 없다. Hermes 와 주고받는 것을 바꾸지 않는다.

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

## 결정 기록 연결

| 결정 | 종류 | ADR 또는 필요 없음의 이유 | 현재 규칙의 원본 |
| --- | --- | --- | --- |
| spec DECISION10, 이 설계의 DECISION1~DECISION4 | operations | PR1 에서 [ADR-20261007 / user-browser](../../adr/ADR-20261007-user-browser.md) 의 「자동 중지」 행을 고친다 | `docs/features/user-browser.md` 의 「상태 전이」, `application.yml` 의 `assistant.browser` |
| 이 설계의 DECISION5~DECISION7 | architecture | 필요 없음. 기존 승인 오류 코드 관례를 따르는 내부 선택이다 | `ConnectorApprovalBrowser`, `connector-action-error.ts` |
| 이 설계의 DECISION8~DECISION10 | architecture | PR2, PR3 이 루트 `docs/adr/` 에 브라우저 잠금 ADR 을 쓸 때 함께 남긴다(spec 의 결정 기록 연결) | 이 문서의 「뒤 PR 의 경계」 |
