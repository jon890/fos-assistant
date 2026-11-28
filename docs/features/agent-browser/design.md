# 에이전트의 내 브라우저 조작 기술 설계

스펙은 [spec.md](spec.md)(사용자 확인 2026-11-28)다. 이 문서는 기능 전체의 모듈 경계와 PR1 의 상세를 갖는다.
PR2(화면 읽기)와 PR3(누르기·글자 넣기와 사람에게 넘기기)은 아래 「뒤 PR 의 경계」 를 따르고, 그 PR 을 시작할 때 이 문서에 그 PR 의 절을 더한다.

## 요구사항 연결

| ID | 해결 방법 |
| --- | --- |
| REQ1 | `browser` 의 새 부품 `BrowserAgentControl` 이 사용자 스위치(`user_browser.agent_control_at`)와 관리자 전체 스위치(`group_setting` 의 `BROWSER_AGENT_CONTROL`)를 함께 본다. MCP 도구는 요청자(`McpCaller.user`)의 브라우저만 고르고, 승인한 동작은 실행 직전에 같은 판정을 다시 받는다 |
| REQ2 | PR1 은 탭 목록. `BrowserTabControl.list` 가 CDP 탭에서 에이전트가 쥔 탭(`BrowserAgentTabs.agentTabs`)과 지킬 탭(`BrowserKeptTabs.kept` 에 이번에 켜진 시각을 준 판정)을 빼고 돌려준다. 목록, 카드 요청, 실행이 모두 같은 판정을 쓴다. 꺼진 브라우저는 켜지 않고 빈 목록이다. 화면 본문 읽기는 PR2 |
| REQ3 | 열기·닫기는 Control Plane 승인 카드(`ControlPlaneApprovals`)로 띄우고, 승인하면 서버가 승인한 인자로 한 번 실행한다. 카드에 대상 탭(제목과 사이트)과 동작을 보인다. 닫기는 여러 탭을 한 카드로 묶는다. 실행 직전 탭 주소의 지문이 요청 때와 다르거나 탭이 쥐어졌으면 그 탭을 건너뛴다 |
| REQ4 | 사람이 연 탭도 같은 목록과 카드를 탄다. 별도 경고를 띄우지 않는다. 「직접 할게요」(`BrowserHumanHold`) 중이면 실행하지 않는다. PR1 의 동작은 탭 하나를 한 번 닫거나 여는 것이라 보기 전용으로 쥘 시간이 없다. 쥐는 것은 PR3 |
| REQ5 | PR3. 아래 「뒤 PR 의 경계」 |
| REQ6 | 사람이 없는 실행(예약 작업, 먼저 살펴보기)도 같은 카드를 띄우고 카드는 그 대화의 「할 일」 탭과 알림에 남는다. 먼저 살펴보기는 쓰기 설정과 관계없이 세 도구를 받는다([ADR-20261128 / agent-browser](../../adr/ADR-20261128-agent-browser.md)). 대화가 없는 실행은 카드를 띄우지 않고 거절한다 |
| REQ7 | 막는 사이트 목록은 PR2 다(지시서가 PR1 의 「남은 범위」 로 정했다). PR1 의 열기는 늘 사람이 승인하고, `http`, `https` 이면서 내부 주소(localhost, 점 없는 호스트, 사설·loopback·link-local IP)가 아닌 주소만 받는다. 기존 플러그인의 승인 흐름은 건드리지 않는다 |
| REQ8 | 세 도구 이름을 `WithheldToolContent` 에 더해 사건 스트림이 `text` 와 `detail` 을 버리고 run-trace 가 오류 글을 남기지 않는다. 실행 기록에는 도구 이름, 시각, 결과(성공·실패)만 남는다. 사이트 이름을 도구 줄에 보이는 것은 PR2 |

## 책임과 경계

| 모듈 | 갖는 것 |
| --- | --- |
| `browser` | 두 스위치의 판정과 저장(`BrowserAgentControl`), 사용자 브라우저의 탭 목록·열기·닫기(`BrowserTabControl`). CDP 를 부르는 것은 이 모듈뿐이다 |
| `mcp` | 도구 세 개의 정의와 인자 검사, 먼저 살펴보기 트리에서 받을 도구 목록. 승인 카드는 포트 `BrowserTabRequests` 로 띄운다 |
| `connector.action` | 포트 구현 `BrowserTabRequester`(카드 띄우기)와 실행기 두 개(`BrowserTabOpenRunner`, `BrowserTabCloseRunner`). 카드의 정책과 문구는 `ControlPlaneActions` |
| `runtime` | 내용을 남기지 않는 도구 이름 목록(`WithheldToolContent`)에 세 도구를 더한다. 사건 스트림(`hermes`)과 run-trace(`orchestration`)가 이 목록을 읽는다 |
| `admin.browser` | 관리자 전체 스위치의 읽기와 쓰기 API |
| 웹 | 「내 브라우저」 의 사용자 스위치, 「브라우저」 관리자 화면의 전체 스위치. 카드는 지금 Control Plane 카드 그대로 그린다 |

의존 방향은 층 순서(`TopLevelPackageOrder`)를 따른다. `mcp` 와 `connector` 는 `browser` 보다 위라 직접 부르고, `mcp` 는 `connector` 를 포트로만 부른다(`ConnectorAttachRequests` 와 같은 방식).
**도구는 플러그인이 아니라 Control Plane 내장 도구다**(spec DECISION6). 바인딩 중계 주소와 접근 표식을 쓰지 않고 Control Plane 이 그 사용자 브라우저의 CDP 에 직접 닿는다.

### 사용자별 실행 경계

[`docs/code-architecture.md`](../../code-architecture.md) 의 「사용자별 실행 경계」 표에서 이 도구가 지나는 구간은 둘이다.

| 구간 | 이 도구에서 |
| --- | --- |
| Hermes → Control Plane MCP | 지금 행 그대로다. `McpCallerResolver` 가 origin 실행의 사용자를 정하고 도구는 그 사용자만 쓴다. 인자로 사용자나 브라우저를 받지 않는다 |
| Control Plane → 「내 브라우저」(새 행) | 인증 주체는 위 구간이 정한 사용자다. Control Plane 프로세스가 그 사용자의 `user_browser` 줄에서 CDP 주소를 꺼낸다. 표식을 쓰지 않으므로 Hermes 쪽 프로세스는 이 경로로 브라우저 주소를 알 수 없다. 승인한 동작은 승인한 사람(카드 주인)의 브라우저에서만 돈다 |

표에 새 행을 더하고 합성 검사 이름을 적는다.
**이 경로의 강제 경계는 MCP 토큰과 루트 session 서명이다.** 서명 key 는 profile 토큰에서 나오므로 「경계 없음」 1 의 프로세스(같은 OS 계정에서 profile `.env` 를 읽는 프로세스)는 서명을 만들어 이 도구를 부를 수 있다. 그 길로 닿는 범위는 그 profile 에서 도는 실행이 있는 사용자 가운데 스위치를 켠 사용자의 탭 목록이고, 열기와 닫기는 여전히 그 사용자의 승인을 거친다. 「경계 없음」 1 의 영향 문장에 이 도구를 더한다. 새 항목은 아니다.

## 인터페이스와 데이터

### PR1 스위치

- `user_browser.agent_control_at DATETIME(6) NULL`. 켠 시각이고 비어 있으면 꺼짐이다. 엔티티는 이 칸을 넣기와 고치기에서 빼고 전용 `UPDATE` 로만 쓴다(`human_held_at` 과 같다). 그래서 낙관적 잠금 버전을 올리지 않고, 오래된 엔티티 저장이 스위치를 되돌리지 않는다.
  브라우저를 지우면 줄과 함께 사라져 꺼진다. 브라우저가 없는 사용자는 켤 수 없다.
- `group_setting` 의 키 `BROWSER_AGENT_CONTROL`, 값 `{"version":1,"enabled":false}`. 줄이 없으면 허용이고, 읽지 못하거나 버전이 다르면 끈 것으로 본다.
- 판정 `BrowserAgentControl.allowed(CurrentUser)` 는 기능 플래그(`assistant.browser.enabled`), 그룹 스위치, 사용자 스위치를 모두 본다. 결과는 `ALLOWED`, `FEATURE_OFF`, `ADMIN_OFF`, `USER_OFF`, `NO_BROWSER` 가운데 하나다.

| API | 하는 일 |
| --- | --- |
| `GET /api/v1/browser` | 지금 응답에 `agentControl { enabled, adminAllowed }` 를 더한다 |
| `PUT /api/v1/browser/agent-control` `{ enabled }` | 내 스위치를 켜고 끈다. 브라우저가 없으면 404, 관리자가 껐으면 켜기를 409 `BROWSER_AGENT_CONTROL_OFF` 로 거절한다. 끄기는 늘 된다 |
| `GET /api/v1/admin/browsers/agent-control` | 그룹 스위치 |
| `PUT /api/v1/admin/browsers/agent-control` `{ enabled }` | 그룹 스위치를 켜고 끈다. 끈 뒤에도 사용자 스위치 값은 지우지 않는다. 다시 켜면 켜 둔 사용자는 그대로 쓴다 |

### PR1 도구

| 도구 | 인자 | 결과 |
| --- | --- | --- |
| `browser_tabs` | 없음 | `{ status, tabs: [{ tab_id, title, url }] }`. 꺼졌으면 `status` 가 까닭이고 `/browser` 에서 켜라는 안내를 붙인다. 브라우저가 꺼져 있으면 켜지 않고 `tabs` 가 비어 있다 |
| `browser_tab_close` | `tab_ids` (1개에서 20개) | 승인 카드를 띄우고 `approval_requested` 를 돌려준다. 목록에 없는 탭 번호는 카드 없이 `unknown_tab` 이다 |
| `browser_tab_open` | `url` | 승인 카드를 띄운다. `http`, `https` 가 아니면 카드 없이 `invalid_url` 이다 |

카드는 `ControlPlaneActions` 에 두 동작으로 더한다. 둘 다 `WRITE`, `ALWAYS`, 상시 허락과 대화 허락이 없다.
승인 줄의 인자는 문자열 칸의 표(`Map<String, String>`)다. 지금 Control Plane 카드는 모든 칸을 보이므로, `ControlPlaneActions` 에 동작마다 **카드에서 뺄 실행 칸**을 두고 `ConnectorActionDetails.view` 가 그 칸을 뺀 인자로 카드 값을 만든다. 실행기는 줄의 원래 인자를 받는다.

| 동작 | 카드에 보이는 칸 | 카드에서 뺀 실행 칸 |
| --- | --- | --- |
| 「브라우저 탭 닫기」 | `tabs`: 탭마다 「제목 · 사이트」 한 줄 | `targets`: `<탭 번호>:<주소 지문>` 을 쉼표로 이은 글. 지문은 요청 때 주소의 SHA-256 |
| 「브라우저 탭 열기」 | `url`: 열 주소 | 없음. `url` 을 그대로 연다 |

- **카드가 주소를 다 보여야 승인한다.** 열기 카드의 `url` 이 카드 가림(`ToolDetailRedactor.hidesArguments`)에 걸리면 카드를 띄우지 않고 `url_not_displayable` 로 거절한다. 사람이 다 보지 못한 주소가 열리지 않게 하려는 것이다. 닫기 카드의 탭 제목은 페이지가 정한 글이라 가려져도 된다. 닫을 탭은 번호와 지문으로 정해진다.
- 닫기 요청의 탭 번호 가운데 하나라도 지금 목록에 없으면 카드 없이 `unknown_tab` 으로 거절한다. 모델이 목록을 다시 읽고 부르게 한다.
- 카드 화면 이름은 「내 브라우저 화면」 이다(`ControlPlaneActions.SCREENS`). 실패 글이 그 화면을 가리킨다.
- 스위치를 끄면 기다리는 카드는 그대로 남고, 승인하면 실행 첫 단계에서 실패로 끝난다. 카드를 따로 만료시키지 않는다.

같은 요청을 알아보는 대상은 닫기면 정렬한 탭 번호, 열기면 주소의 지문이다. 같은 실행이 같은 대상으로 다시 부르면 앞 카드를 돌려준다(지금 `ControlPlaneApprovals.request` 규칙).
**실행 순서**: 실행기는 트랜잭션 밖에서 돈다(`outsideTransaction() = true`). CDP 호출이 몇 초 걸릴 수 있어서다.

1. 스위치를 다시 판정한다. 허용이 아니면 아무것도 하지 않고 실패로 끝낸다.
2. 사람 잠금(`BrowserHumanHold.held`)이면 실패로 끝낸다.
3. 열기는 `UserBrowserService.ensureRunning` 으로 켜고 `CdpTargets.create` 를 부른다.
4. 닫기는 켜지 않는다. 지금 탭 목록을 읽은 뒤 탭마다 아래를 본다. 하나라도 맞지 않으면 그 탭만 건너뛴다.
   - 탭이 있다.
   - 주소 지문이 같다.
   - 쥔 탭이 아니고 지킬 탭도 아니다.
   맞는 탭은 새로 더하는 `CdpTargets.close` 로 닫는다.
5. 결과 글은 닫은 수와 건너뛴 수만 적는다. 제목과 주소는 넣지 않는다. 승인 결과는 지금처럼 알림 줄로만 남고 에이전트를 다시 깨우지 않는다.

### PR1 먼저 살펴보기와 사람이 없는 실행

- 먼저 살펴보기 트리의 판정(`McpController.call`)이 세 도구를 `CHECK_TREE_TOOLS` 와 같이 받는다. 이름 목록은 `McpBrowserTabCalls.NAMES` 다. 목록은 읽기이고 나머지는 카드만 띄우므로 그 집합의 정의와 같다. 쓰기 설정(`writesAllowed`)은 보지 않는다(spec DECISION10).
- 대화가 없는 실행(`originExecution.conversationId` 가 비어 있음)은 카드가 보일 자리가 없어 `no_conversation` 으로 거절하고 카드를 만들지 않는다. 지금 운영에서 대화 없는 origin 은 생기지 않으므로(`AgentDelegationService`) 지키기 위한 분기다.
- 그룹에 공개한 에이전트(기본 비서 포함)도 요청자의 스위치로 이 도구를 쓴다(spec DECISION14). 브라우저는 늘 요청자의 것이다.
- 모델 지침: 도구 설명이 「바꾸는 동작은 카드가 뜬 것이지 끝난 것이 아니다」, 「꺼져 있으면 `/browser` 에서 켜라고 안내한다」 를 갖는다.
- 사건 실행(계기 `EVENT`)은 fos-ctx 의 안전 목록이 정한다. 이 PR 은 그 목록을 바꾸지 않는다.

### PR1 기록

- `WithheldToolContent` 에 `BROWSER_TOOLS`(세 이름)를 두고 원고 도구처럼 정확한 이름과 `__` 뒤 이름으로 판정한다. 사건 스트림은 그 도구의 시작과 끝 사건에서 `text` 와 `detail` 을 버리고, run-trace 는 `withholdsErrorText` 로 오류 글을 남기지 않는다. 그래서 탭 제목과 주소가 실행 기록과 관리자 원문 보기에 남지 않는다.
- 도구 결과는 Hermes session 에 모델 대화로 남는다. 이것은 Control Plane 의 기록이 아니라 그 profile 의 대화 문맥이고, 다른 대화의 결과처럼 Hermes 의 session 수명을 따른다.
- 승인 줄의 인자는 지금처럼 봉인해 `connector_action` 에 남는다. 사람이 승인하려면 카드에서 탭 제목과 주소를 봐야 하기 때문이다. 수명은 승인 줄의 기존 규칙을 따른다.

### 뒤 PR 의 경계

- **PR2 화면 읽기**: `BrowserTabControl` 에 읽기를 더한다. 탭에 잠깐 붙어 텍스트와 화면 사진을 얻고, 막는 사이트(저장소 기본 목록과 `group_setting` 의 관리자 추가 목록)를 열기 전과 연 결과에서 모두 본다. 도구 줄에 사이트 이름을 보인다.
- **PR3 누르기·글자 넣기와 넘기기**: 화면 묶음 카드(한 탭의 조작 목록)와 그 탭의 화면 지문을 승인 줄에 둔다. 실행하는 동안 `BrowserAgentTabs.hold` 로 탭을 쥐어 사람에게 보기 전용으로 보이고, 회수는 지금 `BrowserHumanHold` 를 따른다. 사람만 할 동작을 만나면 실행을 멈추고 기존 넘기기 카드([ADR-20261111 / connector-handoff](../../adr/ADR-20261111-connector-handoff.md))를 만든다.

## 실패와 일관성

- **켠 뒤 끄기와 승인의 경쟁**: 실행기는 실행 직전에 스위치를 다시 읽는다. 읽은 직후 끈 경우는 그 한 번이 돌 수 있다. 탭 하나를 닫거나 여는 동작이고 사람이 이미 승인한 것이라 잠금으로 묶지 않는다.
- **탭이 바뀐 경우**: 주소 지문이 다르면 닫지 않는다. 같은 주소로 다시 그린 탭은 같은 탭으로 본다. 제목만 바뀐 것은 막지 않는다.
- **브라우저가 꺼져 있을 때**: 목록은 비어 있고 닫기는 「탭이 없어요」 로 끝난다. 열기는 켠다. 켜지 못하면 실패로 끝나고 카드는 실패로 닫힌다. 사용자는 다시 요청한다.
- **CDP 실패**: 실행기의 예외는 `ControlPlaneApprovals.approve` 가 실패로 적는다(지금 규칙). 닫다 중간에 실패하면 닫은 수까지만 결과에 적는다.
- **스위치 칸과 엔티티 저장**: 칸을 엔티티가 쓰지 않으므로 상태 전이의 저장이 스위치를 덮지 않는다.
- **배포 순서**: backend 와 웹이 한 이미지로 나간다. 마이그레이션은 칸 하나를 더하므로 옛 코드와 함께 돌아도 깨지지 않는다. Hermes 쪽은 바뀌지 않는다. Control Plane MCP 의 도구 목록은 Hermes 가 연결할 때 읽는다.

## 검증 전략

| ID | 경계 | 확인 |
| --- | --- | --- |
| REQ1 | `BrowserAgentControl` 단위 | 다섯 판정 결과, 관리자가 끈 동안 켜기 거절, 끄기는 늘 된다 |
| REQ1 | `BrowserTabControl` 단위(합성 사용자 둘) | 사용자 A 의 목록과 닫기는 A 의 브라우저 CDP 만 부른다. B 의 탭 번호로 닫으면 A 의 브라우저에서 찾지 못해 건너뛴다 |
| REQ1, REQ6 | MCP 통합(`McpBrowserTabToolsTest`) | 스위치가 꺼졌으면 목록이 까닭을 돌려준다, 켜면 내 탭만 보인다, 닫기가 카드를 띄우고 승인하면 닫힌다, 다른 사용자는 그 카드를 승인할 수 없다(404), 승인 전에 끄면 실행하지 않는다, 먼저 살펴보기 트리에서 쓰기 설정이 꺼져 있어도 세 도구를 받는다, 대화 없는 실행은 거절한다 |
| REQ2 | `BrowserTabControl` 단위 | 쥔 탭과 지킬 탭은 목록에 없다, 탭이 없으면 빈 목록, 꺼진 브라우저를 켜지 않는다 |
| REQ3 | 실행기 단위 | 주소 지문이 다르거나 탭이 사라졌거나 쥔 탭이면 건너뛴다, 결과 글에 제목과 주소가 없다, 사람 잠금이면 실행하지 않는다 |
| REQ8 | `HermesRunEventStream` 단위, `WithheldToolContent` 단위 | 세 도구 이름과 MCP 별칭의 시작과 끝 사건에서 `text` 와 `detail` 이 비어 있다, 오류 글을 남기지 않는다 |
| REQ3 | 카드 값 단위 | 뺀 실행 칸이 카드 값에 없다, 가림에 걸리는 주소는 카드를 띄우지 않는다 |
| REQ7 | `BrowserTabControl` 단위 | 내부 주소와 `http`, `https` 밖의 주소는 열지 않는다 |
| REQ1 | 웹 단위와 브라우저 검사 | 스위치가 켜고 끄는 요청을 보낸다, 관리자가 끄면 스위치가 잠기고 안내가 보인다, 관리자 화면의 전체 스위치 |

합성 사용자 둘의 검사 이름은 `docs/code-architecture.md` 의 새 행에 적는다.
운영 왕복은 배포 뒤 실제 「내 브라우저」 에서 탭 목록과 닫기를 한 번 해 본다(PR 본문의 후속 검증). Hermes 와 주고받는 형식은 바뀌지 않고 Control Plane MCP 도구가 늘 뿐이다.

## 결정

| ID | 결정 | 답 | 누가 | 근거 |
| --- | --- | --- | --- | --- |
| DECISION1 | 사용자 스위치를 두는 곳 | `user_browser.agent_control_at` | 작성자 | 테이블을 늘리지 않는다(사용자 결정 2026-11-01). 스위치는 그 브라우저와 수명이 같다. 지우면 함께 꺼지는 것이 「열어 준 동안만」(spec REQ1)과 맞다 |
| DECISION2 | 관리자 전체 스위치를 두는 곳 | `group_setting` 의 `BROWSER_AGENT_CONTROL`. 줄이 없으면 허용 | 작성자 | 설정 성격의 그룹 데이터는 이 표의 새 키로 둔다(`data-schema.md`). 기능 플래그는 기동 때 값이라 화면에서 끌 수 없다 |
| DECISION3 | 승인과 실행 | Control Plane 승인 카드. 승인하면 서버가 승인한 인자로 한 번 실행하고 결과는 알림 줄로 남긴다 | 작성자 | 기억 지우기와 연결 붙이기가 쓰는 흐름이다. 모델이 다시 부르지 않으므로 승인한 것과 다른 탭을 닫을 길이 없다 |
| DECISION4 | 승인 뒤 탭이 바뀐 것을 아는 방법 | 요청 때 주소의 지문을 숨긴 칸에 두고 실행 때 비교한다 | 작성자 | 탭 번호는 같은 탭이 다른 페이지로 가도 그대로다. 주소 원문을 두 번 두지 않는다 |
| DECISION5 | 꺼진 브라우저의 목록과 닫기 | 켜지 않는다. 열기만 켠다 | 작성자 | 꺼진 브라우저에는 닫을 탭이 없다. 목록 때문에 동시 자리를 쓰지 않는다 |
| DECISION6 | 기록에서 탭 정보를 빼는 자리 | Hermes 사건 스트림이 세 도구의 글을 버린다 | 작성자 | 원고 도구와 같은 자리다. MCP 서버는 도구 줄을 쓰지 않는다 |
| DECISION7 | 먼저 살펴보기에서 받는 방법 | `CHECK_TREE_TOOLS` 와 같은 판정으로 받는다 | 작성자 | 그 집합이 이미 「읽기와 승인 카드만 띄우는 도구」 다. 이름 목록만 도구 쪽(`McpBrowserTabCalls`)에 둔다. 컨트롤러 파일 길이 상한 때문이다. 결정의 이유는 ADR 이 갖는다 |
| DECISION8 | 대화 없는 실행 | 카드를 띄우지 않고 거절한다 | 작성자 | 카드는 대화의 「할 일」 탭에 보인다. 보일 곳이 없는 카드는 사람이 승인할 수 없다 |
| DECISION9 | 닫기 한 카드의 탭 수 | 최대 20개 | 작성자 | 탭 정리를 한 번에 승인받되 카드가 읽을 수 없을 만큼 길어지지 않게 한다 |
| DECISION10 | 카드에 보이지 않는 실행 칸 | 동작마다 뺄 칸을 `ControlPlaneActions` 에 두고 카드 값에서 뺀다 | 작성자 | 탭 번호와 지문은 사람에게 뜻이 없고 길이 가림에 걸린다. 일반 플러그인의 숨은 인자(가린 칸이 있으면 승인을 막는다)와 달리 서버가 만든 칸이다 |
| DECISION11 | 가려지는 주소 | 카드를 띄우지 않는다 | 작성자 | 사람이 다 본 주소만 연다. 로그인한 세션에서 주소 하나로 상태가 바뀌는 사이트가 있다 |
| DECISION12 | PR1 의 막는 사이트 | 목록은 PR2, PR1 은 내부 주소만 막는다 | 지시서 | 지시서가 막는 사이트를 PR1 의 남은 범위로 정했다. PR1 의 열기는 매번 사람이 승인한다 |

## 결정 기록 연결

| 결정 | 종류 | ADR 또는 필요 없음의 이유 | 현재 규칙의 원본 |
| --- | --- | --- | --- |
| spec DECISION6, DECISION10, 이 설계의 DECISION3, DECISION7 | architecture | [ADR-20261128 / agent-browser](../../adr/ADR-20261128-agent-browser.md). [ADR-080](../../adr/ADR-080-먼저-살펴보기는-점검-대화의-turn-하나로-돌고-읽기-경계를-control-plane-이-강제한다.md), [ADR-082](../../adr/ADR-082-먼저-살펴보기의-쓰기-도구는-관리자가-에이전트마다-켜고-커넥터-쓰기는-승인-카드로-보낸다.md), [ADR-20261007 / user-browser](../../adr/ADR-20261007-user-browser.md) 에 예외와 대체를 적는다 | 이 문서의 「PR1 도구」, 「PR1 먼저 살펴보기와 사람이 없는 실행」 |
| 이 설계의 DECISION1, DECISION2 | architecture | 필요 없음. 테이블을 늘리지 않는 기존 규칙을 따른 배치다 | `backend/docs/data-schema.md` 의 `user_browser`, `group_setting` |
| 이 설계의 DECISION4~DECISION6, DECISION8~DECISION12 | architecture | 필요 없음. 기존 승인 카드와 기록 규칙 안의 값이다 | 이 문서의 「PR1 도구」, 「PR1 기록」 |
