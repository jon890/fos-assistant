# 에이전트의 내 브라우저 조작

사용자가 로그인한 「내 브라우저」 에서 에이전트가 탭을 정리하고, 다음 단계에서 화면을 읽고 양식을 채우는 기능이다. 요구와 결정은 [스펙](agent-browser/spec.md), 모듈 경계와 단계별 설계는 [설계](agent-browser/design.md), 결정의 근거는 [ADR-20261128 / agent-browser](../adr/ADR-20261128-agent-browser.md) 가 갖는다.

covers: `docs/features/agent-browser/spec.md`, `backend/src/main/java/com/bifos/assistant/mcp/application/McpBrowserTabTools.java`, `backend/src/main/java/com/bifos/assistant/mcp/presentation/McpBrowserTabCalls.java`, `backend/src/main/java/com/bifos/assistant/mcp/application/port/BrowserTabRequests.java`, `backend/src/main/java/com/bifos/assistant/connector/action/application/BrowserTabRequester.java`, `backend/src/main/java/com/bifos/assistant/connector/action/application/BrowserTabRunners.java`, `backend/src/main/java/com/bifos/assistant/connector/action/application/BrowserTabCloseRunner.java`, `backend/src/main/java/com/bifos/assistant/connector/action/application/BrowserTabOpenRunner.java`

구현 순서는 스펙의 DECISION12 가 정한다. 이 파일은 지금 동작하는 것만 적는다. 화면 읽기, 막는 사이트, 누르기와 글자 넣기, 사람에게 넘기기는 아직 없다.

## 켜기 스위치

- 처음에는 꺼져 있다. 사용자가 「내 브라우저」(`/browser`)의 「에이전트가 이 브라우저를 쓰게 하기」 를 켠다. 브라우저를 지우면 함께 꺼진다.
- 관리자는 「브라우저」(`/admin/browsers`)의 「에이전트의 브라우저 조작 허용」 으로 그룹 전체를 끈다. 끄면 사용자가 켜 둔 값은 남지만 쓰지 않는다.
- 두 스위치가 모두 켜져야 도구가 탭을 본다. 승인한 동작도 실행 직전에 다시 판정해, 그 사이 껐으면 실행하지 않는다.

## 도구

| 도구 | 하는 일 |
| --- | --- |
| `browser_tabs` | 요청자 브라우저의 탭 목록(번호, 제목, 주소)이다. 에이전트가 쥔 탭과 넘기기 카드가 지키는 탭은 빠진다. 꺼진 브라우저는 켜지 않고 빈 목록이다 |
| `browser_tab_close` | 탭을 닫는 「브라우저 탭 닫기」 승인 카드를 띄운다. 카드에는 탭마다 제목과 사이트가 보인다. 승인하면 그 사이 다른 페이지로 간 탭과 다른 작업이 쥔 탭은 두고 나머지를 닫는다 |
| `browser_tab_open` | 새 탭을 여는 「브라우저 탭 열기」 승인 카드를 띄운다. `http`, `https` 의 공개 웹 주소만 받고 내부 주소(숫자 꼴은 네 마디 십진 표기만)는 거절한다. 카드에서 다 보이지 않는 주소는 카드를 띄우지 않는다 |

- 브라우저는 늘 요청자의 것이다. 도구는 사용자나 브라우저를 인자로 받지 않는다.
- 승인 결과는 대화의 알림 줄로 남고 에이전트를 다시 깨우지 않는다. 결과 글에는 닫은 수만 있고 탭 제목과 주소는 없다.
- 「직접 할게요」 로 사람이 쥔 동안은 승인해도 실행하지 않는다.
- 먼저 살펴보기는 쓰기 설정과 관계없이 세 도구를 받는다. 바꾸는 동작은 카드로만 한다.
- 실행 기록과 관리자 원문 보기에는 세 도구의 인자와 결과를 남기지 않는다.
