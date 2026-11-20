# Phase 02. 플러그인 목록을 읽는 connector_list 도구

**Execution profile**: standard

## 목표

에이전트가 「구글 캘린더 연결해 줘」 에 맞는 연결 화면 링크를 붙일 수 있도록, 요청자의 플러그인 목록과 연결 상태, 이 에이전트에 붙은 상태, 화면 링크를 돌려주는 읽기 도구를 만든다.

**범위 외**: 붙이기와 승인 카드(PR4). 연결 만들기·해제.

## 컨텍스트

**요구사항**: REQ5

**근거 문서**: `docs/features/service-concepts/design.md` 의 「`connector_list`」 와 DECISION5, `docs/features/mcp.md` 의 「Control Plane MCP」, `backend/docs/code-architecture.md` 의 「`attention` 밖의 패키지는 `connector` 를 import 하지 않는다」

- `mcp` 는 `connector` 를 import 하지 못한다. port 를 `mcp.application.port` 에 두고 `connector` 가 구현한다. 같은 방식: `mcp/application/port/FinancialLimitRequests.java` 와 그 구현 `connector/approval/application/FinancialLimitChanges.java`, 도구 쪽 `mcp/application/McpFinancialLimitTools.java`
- 목록의 원본은 `ConnectorConnectionService.catalog(CurrentUser)` 다. `ConnectorSummary` 에 id, title, 내 상태(`ConnectionStatus`), 사용 가능 여부, 붙은 에이전트(`BoundAgentSummary(agentCode, agentName, status, restartRequired)`)가 있다
- 이 실행의 에이전트는 `McpCaller.originExecution()` 의 에이전트 번호로 찾는다. 요청자가 주인인 비공개 에이전트인지는 `agent` 모듈의 에이전트 값으로 본다(`connector` 는 `agent` 를 부를 수 있다)
- 도구 등록은 `McpToolCatalog.tools()`, 호출은 `McpController` 의 도구 이름 분기와 `CHECK_TREE_TOOLS`, 인자 없는 도구의 검사는 `listAgents` 를 따른다

## 의도 메모

- 매 실행 지시문에 플러그인 목록을 넣는 안은 기각했다(설계 DECISION2)
- `thisAgent` 를 참·거짓으로 두는 안은 기각했다. 반영을 기다리는 바인딩을 「쓸 수 있다」 고 잘못 답한다
- `?agent=` 는 모델이 아니라 Control Plane 이 정한다(스펙 DECISION22)
- 결과에 설명, 입력 칸, 도구 목록, 다른 에이전트의 바인딩을 넣지 않는다. 사용 불가 항목(카탈로그에서 빠진 내 연결)도 넣지 않는다

## 작업 항목

### 1. `backend/src/main/java/com/bifos/assistant/mcp/application/port/ConnectorListing.java` 신규

`List<Map<String, Object>> list(CurrentUser user, Long agentId)`. 항목 칸은 `id`, `title`, `connected`, `thisAgent`(`none`, `ready`, `pending`), `link`. Javadoc 에 설계 경로.

### 2. `backend/src/main/java/com/bifos/assistant/connector/connection/application/ConnectorListings.java` 신규

`ConnectorListing` 구현. `catalog(user)` 에서 `available` 인 항목만, `connected = status == CONNECTED`, `thisAgent` 는 붙은 에이전트 중 이 에이전트 code 의 바인딩이 `READY` 이고 `restartRequired` 가 거짓이면 `ready`, 있으면 `pending`, 없으면 `none`. `link` 는 `/connections/{id}`, 이 에이전트가 요청자가 주인이고 그룹 공개가 아니면 `?agent=<code>` 를 붙인다.

### 3. `backend/src/main/java/com/bifos/assistant/mcp/application/McpConnectorListTool.java` 신규와 등록

이름 `connector_list`. 정의의 설명: 「사용자가 외부 서비스 연결을 부탁했을 때 부른다. 플러그인마다 연결 상태와 연결 화면 링크를 준다. 답에는 link 를 그대로 붙이고 연결했다고 말하지 않는다. 목록에 없는 서비스는 없다고 알리고 /connections 를 붙인다」. 읽기가 실패하면 `McpToolService.result(..., true)` 로 「목록을 읽지 못했다. /connections 를 안내한다」 를 돌려준다. `McpToolCatalog.tools()` 에 정의를 더하고, `McpController` 에 인자 없는 도구 분기(`listAgents` 와 같은 검사)와 `CHECK_TREE_TOOLS` 를 더한다. 결과는 외부 글이 아니므로 `McpMemoryRemember.INTERNAL_TOOLS` 에도 더한다. 빠지면 이 도구를 부른 turn 의 기억이 바로 저장되지 않고 제안으로 바뀐다.

### 4. 테스트

- `backend/src/test/java/com/bifos/assistant/connector/connection/application/ConnectorListingsTest.java`: `thisAgent` 세 값, 비공개 주인 에이전트의 `?agent=`, 그룹 공개 에이전트와 남의 에이전트의 링크, 사용 불가 항목 제외
- `backend/src/test/java/com/bifos/assistant/mcp/McpConnectorListToolTest.java` 신규(`mcp/McpAgentToolsTest.java` 의 인자 없는 도구와 살펴보기 트리 사례를 따른다): 인자가 있으면 인자 오류, 결과가 `content` 글 하나의 JSON 배열, 다른 사용자의 연결이 보이지 않음, 먼저 살펴보기 트리에서 받음, 읽기 실패의 `isError`
- `mcp/application/McpToolCatalogTest.java` 의 도구 이름 목록, `mcp/McpCallerInvariantTest.toolArguments()` 에 `connector_list`, 기억 내부 도구 목록의 테스트

### 5. 문서

`docs/features/mcp.md` 의 도구 표에 `connector_list` 행(계약은 `docs/features/service-concepts/design.md` 의 「`connector_list`」)과 살펴보기에서 받는 읽기 도구 목록. `backend/docs/code-architecture.md` 의 port 설명에 `ConnectorListing` 을 `FinancialLimitRequests` 옆에 더한다.

## 검증

```bash
cd backend && ./gradlew test --tests '*ConnectorListingsTest' --tests '*McpConnectorListToolTest' --tests '*McpToolCatalogTest' --tests '*McpCallerInvariantTest' --tests '*McpMemoryRemember*' --tests '*McpAgentToolsTest'
cd backend && ./gradlew archTest checkstyleMain checkstyleTest spotlessCheck
node --test test/repo/doc-links.test.ts test/repo/doc-code-references.test.ts
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/mcp/application/port/ConnectorListing.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/connector/connection/application/ConnectorListings.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/mcp/application/McpConnectorListTool.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/mcp/application/McpToolCatalog.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/mcp/presentation/McpController.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/connector/connection/application/ConnectorListingsTest.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/mcp/application/McpMemoryRemember.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/mcp/McpConnectorListToolTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/mcp/application/McpToolCatalogTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/mcp/McpCallerInvariantTest.java` | 수정 |
| `docs/features/mcp.md` | 수정 |
| `backend/docs/code-architecture.md` | 수정 |
