# Phase 02. MCP 도구 세 개와 승인 카드, 먼저 살펴보기 수락, 기록 가림

**Execution profile**: standard

## 목표

에이전트가 `browser_tabs`, `browser_tab_open`, `browser_tab_close` 를 부르면 요청자 브라우저의 탭을 보고, 열기와 닫기는 승인 카드를 거쳐 서버가 한 번 실행한다.

**범위 외**: 웹 스위치(phase 03). 화면 읽기, 막는 사이트(PR2).

## 컨텍스트

**요구사항**: REQ1, REQ3, REQ6, REQ8

**근거 문서**: `docs/features/agent-browser/design.md` 의 「PR1 도구」, 「PR1 먼저 살펴보기와 사람이 없는 실행」, 「PR1 기록」, `docs/adr/ADR-20261128-agent-browser.md`

- 도구 등록: 정의는 `mcp/application/McpConnectorAttachTool.java`(`NAME`, `definition()`), 목록은 `McpToolCatalog.tools()`, 분기는 `McpController.handlers()`.
- 승인 카드: 포트 `mcp/application/port/ConnectorAttachRequests.java` 와 구현 `connector/action/application/ConnectorAttachRequester.java` 를 본보기로 한다. 실행기는 `ConnectorAttachRunner`(`outsideTransaction() = true`)와 같다.
- 카드 정책과 문구: `connector/action/domain/ControlPlaneActions.java` 의 `POLICIES`, `SCREENS`, `LABELS`. `identifiers` 에 넣은 칸은 카드에 보이지 않는다.
- 먼저 살펴보기: `McpController.CHECK_TREE_TOOLS`.
- 기록: `hermes/infra/HermesRunEventStream.java` 의 원고 도구를 버리는 분기.

## 의도 메모

- 승인 뒤 모델을 다시 부르지 않는다. 결과는 알림 줄로만 남는다(design DECISION3).
- 도구 결과의 안내 글은 영어 대신 지금 도구들처럼 한국어 지시문으로 쓴다. 실패 글은 정해 둔 문구만 쓰고 제목과 주소를 넣지 않는다.

## 작업 항목

### 1. `mcp/application/McpBrowserTabTools`

- 세 도구의 `NAME` 상수와 `definition()`. `browser_tab_close` 의 `tab_ids` 는 1개에서 20개의 문자열 배열, `browser_tab_open` 의 `url` 은 문자열.
- `tabs(McpCaller)`: `BrowserAgentControl.status` 가 `ALLOWED` 가 아니면 까닭과 「사용자에게 「내 브라우저」 화면(`/browser`)에서 켜 달라고 안내한다」 를 돌려준다. 허용이면 `BrowserTabControl.list`.
- `close(McpCaller, List<String>)`, `open(McpCaller, String)`: 스위치를 보고, 대화 없는 실행은 `no_conversation`, 목록에 없는 번호는 `unknown_tab`, 잘못된 주소는 `invalid_url` 로 카드 없이 끝낸다. 나머지는 포트 `BrowserTabRequests` 로 카드를 띄운다.
- `McpToolCatalog`, `McpController.handlers()` 에 등록하고 세 이름을 `CHECK_TREE_TOOLS` 에 더한다.

### 2. 포트와 카드

- `mcp/application/port/BrowserTabRequests`: `requestClose(CurrentUser, Long originExecutionId, List<CdpTarget> tabs)`, `requestOpen(CurrentUser, Long originExecutionId, String url)`.
- `connector/action/application/BrowserTabRequester`: `ControlPlaneApprovals.request` 로 띄운다. 대상은 닫기면 정렬한 탭 번호, 열기면 `BrowserTabControl.urlHash(url)`. 보이는 칸은 `tabs`(「제목 · 사이트」 줄), `url`. 숨긴 칸은 `targets`(번호와 지문), `open_url`.
- `ControlPlaneActions`: `BROWSER_TAB_CLOSE`, `BROWSER_TAB_OPEN` 의 정책(「브라우저 탭 닫기」, 「브라우저 탭 열기」), 화면 이름 「내 브라우저 화면」, 칸 이름.
- 실행기 `BrowserTabCloseRunner`, `BrowserTabOpenRunner`: 스위치 다시 판정 → `BrowserTabControl` 호출 → 「탭 N개를 닫았어요」, 「건너뛴 탭 M개는 바뀌었거나 다른 작업이 쓰는 중이에요」, 「탭을 열었어요」. 사람 잠금이면 「직접 쓰고 계신 브라우저를 돌려준 뒤 다시 요청해 주세요」 로 실패.

### 3. 기록 가림

`runtime/domain/WithheldToolContent` 에 `BROWSER_TOOLS` 와 `browser(toolName)` 를 더하고 `withholdsErrorText` 에 넣는다. `HermesRunEventStream` 은 원고 도구 분기와 같이 그 도구의 `text` 와 `detail` 을 버린다.

### 4. 카드에서 실행 칸 빼기

`ControlPlaneActions.cardArgs(toolName, argsJson)` 가 동작마다 정한 실행 칸(닫기의 `targets`)을 뺀 JSON 을 낸다. `ConnectorActionDetails.view` 의 Control Plane 분기가 그 값으로 카드 값을 만든다(`ConnectorActionView.from` 에 인자 JSON 을 받는 길을 더한다). 열기 요청은 `ToolDetailRedactor.hidesArguments` 가 참이면 카드 없이 `url_not_displayable` 이다.

### 5. 테스트

- `mcp/McpBrowserTabToolsTest`(`@BackendIntegrationTest`, `McpCallSigner`): 스위치 꺼짐이면 까닭, 켜면 내 탭만, 닫기가 카드를 띄우고 승인(`/api/v1/connector-actions/{id}/approve`)하면 닫힌다, 다른 사용자의 승인은 404, 승인 전에 끄면 실패로 끝나고 닫지 않는다, 쓰기 설정이 꺼진 먼저 살펴보기 트리에서 세 도구를 받는다, 대화 없는 실행은 카드가 없다. CDP 는 공유 테스트 기반 `IntegrationTestDoubles` 에 넣는 가짜 `CdpTargets` 로 대신한다.
- 실행기 단위 테스트: 지문 다름, 쥔 탭, 사람 잠금, 결과 글에 제목과 주소가 없다.
- `HermesRunEventStreamTest`: 세 도구의 시작과 끝 사건에 `text` 와 `detail` 이 없다.
- `McpToolCatalogTest`: 세 도구가 목록에 있다.

## 검증

```bash
cd backend && ./gradlew test --tests '*McpBrowserTabToolsTest' --tests '*BrowserTabRunnerTest' --tests '*HermesRunEventStreamTest' --tests '*McpToolCatalogTest'
cd backend && ./gradlew archTest checkstyleMain checkstyleTest spotlessCheck
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/mcp/application/McpBrowserTabTools.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/mcp/application/port/BrowserTabRequests.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/mcp/application/McpToolCatalog.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/mcp/presentation/McpController.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/connector/action/application/BrowserTabRequester.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/connector/action/application/BrowserTabCloseRunner.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/connector/action/application/BrowserTabOpenRunner.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/connector/action/domain/ControlPlaneActions.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/hermes/infra/HermesRunEventStream.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/runtime/domain/WithheldToolContent.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/connector/action/application/ConnectorActionDetails.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/connector/action/application/dto/ConnectorActionView.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/testsupport/IntegrationTestDoubles.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/mcp/McpBrowserTabToolsTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/connector/action/application/BrowserTabRunnerTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/hermes/infra/HermesRunEventStreamTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/mcp/application/McpToolCatalogTest.java` | 수정 |
