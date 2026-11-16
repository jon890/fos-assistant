# 실행 중 브라우저 함께 보기와 이어 가기

에이전트가 「내 브라우저」 로 일하는 동안 사람이 그 화면을 보고 넘겨받고, 사람이 푼 뒤 에이전트가 멈춘 단계부터 이어 가는 기능이다.

covers: `backend/src/main/java/com/bifos/assistant/browser/application/port/BrowserWaitDemand.java`, `backend/src/main/java/com/bifos/assistant/connector/action/application/ConnectorBrowserWaitDemand.java`, `backend/src/main/java/com/bifos/assistant/connector/action/application/ConnectorApprovalBrowser.java`

요구와 결정은 스펙 [`browser-cowork/spec.md`](browser-cowork/spec.md), 구조는 설계 [`browser-cowork/design.md`](browser-cowork/design.md)가 갖는다.
스펙은 PR 다섯으로 나뉘고, 이 파일은 지금 동작하는 것만 적는다. 실행 중 보기, 넘겨받기 잠금, 이어 가기는 아직 없다.

## 사람을 기다리는 동안 브라우저 유지

브라우저 자동 중지가 끄기 전에 그 사용자가 사람을 기다리는지 묻는다. 기다리면 유휴 기준을 `assistant.browser.idle-timeout` 대신 `assistant.browser.wait-hold-timeout`(기본 30분)으로 본다.
규칙의 원본은 [사용자 브라우저](user-browser.md) 의 「상태 전이」 다.

| 기다림 | 세는 조건 |
| --- | --- |
| 열린 화면 넘기기 | 대화가 있고, 닫히지 않았고, 그 브라우저가 이번에 켜진 뒤에 생겼다. 결제와 탭 확인 넘기기도 센다 |
| 기다리는 승인 | `PENDING` 이고 기다리는 시간이 남았으며, 그 플러그인이 `owner_browser_env` 를 선언했다. 카탈로그를 읽지 못했거나 카탈로그에 없는 플러그인의 줄은 기다림으로 센다 |

기다림은 `browser` 의 포트 `BrowserWaitDemand` 를 `connector` 의 `ConnectorBrowserWaitDemand` 가 구현해 답한다. 묻다 실패하면 그 브라우저는 그 점검에서 끄지 않는다.
`UserBrowserSweeperTest` 와 `ConnectorBrowserWaitDemandTest` 가 확인한다.

## 승인할 때 브라우저를 켜지 못하면

「내 브라우저」 를 쓰는 플러그인의 승인 줄을 승인하면, 승인 트랜잭션보다 먼저 `ConnectorApprovalBrowser` 가 그 사용자의 브라우저를 켠다.
켜지 못하면 줄을 `PENDING` 그대로 두고 409 `CONNECTOR_BROWSER_UNAVAILABLE` 로 거절한다. 승인 카드는 「브라우저를 켤 수 없어요. 잠시 뒤 다시 승인해 주세요.」 를 보이고 다시 승인할 수 있다.
갈리는 지점은 [플러그인 정책](connector-policy.md) 의 「승인이 필요한 호출」 이 갖는다.

| 경우 | 처리 |
| --- | --- |
| 일반 쓰기, 구매·금융 승인, 원고 저장 승인 | 켜 본다. 못 켜면 위처럼 거절하고 피드백 사건과 구매 하루 합계에 남기지 않는다 |
| 상한 변경 승인 | 브라우저를 쓰지 않아 켜지 않는다 |
| 기능이 꺼졌다 | `BROWSER_DISABLED` 를 그대로 낸다 |
| 남의 줄, 끝난 줄, 시간이 지난 줄 | 켜지 않는다. 뒤의 승인 판정이 지금 응답을 그대로 낸다 |

`ConnectorApprovalBrowserTest` 와 `ApprovalBrowserUnavailableTest` 가 확인한다.
