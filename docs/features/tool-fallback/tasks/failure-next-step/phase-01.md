# Phase 01. Control Plane MCP 도구의 실패가 끝 줄 하나로 끝난다

**Execution profile**: standard

## 목표

Control Plane MCP 도구가 모델에게 돌려주는 실패(도구 결과의 `isError` 참, JSON-RPC 인자 오류)가 모두 「다음 방법: …」 이나 「사용자에게 바로 알린다.」 한 줄로 끝나게 한다. 끝 줄 없는 실패는 컴파일되지 않게 막는다.

**범위 외**: 플러그인 호출 판정 글(`connector.approval`, `ToolGuardController`, `EventToolGuard`)은 phase 02. fos-ctx 는 phase 03. 공통 지침은 phase 04. 승인한 실행의 복구 안내(ADR-092)와 `[승인 대기]` 글은 바꾸지 않는다.

## 컨텍스트

지금 MCP 도구 결과는 `mcp/application/McpToolService.result(String text, boolean error)` 한 함수가 `{content:[{type:text,text}], isError}` 로 만든다. 성공과 실패가 같은 함수를 쓴다.
부르는 곳은 `McpToolService`, `McpMemoryRemember`(static import), `McpFileSpaceTools`, `McpConnectorListTool`, `McpMediaObservationTools`, `McpApprovalResumeTool`, `McpContentDraftTools`, `McpFinancialLimitTools` 다(`grep -rn "result(" backend/src/main/java/com/bifos/assistant/mcp/application`).
인자 오류는 `mcp/presentation/McpRpc.invalidParams(JsonNode id, String reason)` 가 JSON-RPC `-32602` 의 `data` 에 글을 넣는다. 부르는 곳은 `McpController`, `McpArtifactCalls`, `McpFileSpaceCalls` 다. `McpRpc.error(JsonNode, int, String)` 는 `McpController` 의 `Method not found` 두 곳과 `malformed()` 의 `-32700 Parse error` 한 곳이 쓴다.
`McpController` 가 `throw ex` 로 다시 던지는 `ApiException`(호출자 확인, 기억 검색)은 공통 오류 처리로 가는 HTTP 오류라 이 PR 의 대상이 아니다. 설계가 대상 밖으로 둔 파싱 실패, 인증 실패와 같은 갈래다.
`McpToolService.java` 의 `SAFE_ARTIFACT_FAILURE_MESSAGES` 는 로그에만 쓰인다. 모델에게 가는 결과물 저장 실패 글은 `ArtifactSourceRejectedException` 을 받는 갈래(166~172행 근처)의 두 글과 「결과물을 저장할 수 없습니다.」 두 곳이다.
원고 도구(`McpContentDraftTools`)와 사진 관찰 도구(`McpMediaObservationTools`), `McpToolService` 의 위임 조회(`failure(code, message)`)는 실패를 JSON 글(`{"code": …}`)로 돌려준다. 이 JSON 에는 끝 줄을 덧붙이지 않고 `next` 칸을 더한다(설계 DECISION6).

**요구사항**: REQ3

**근거 문서**: `docs/features/tool-fallback/spec.md` 의 REQ2, REQ3, DECISION2, DECISION10, DECISION11, `docs/features/tool-fallback/design.md` 의 「실패 글의 끝 줄」, 「바꾸는 실패 글」, DECISION1, DECISION6

## 의도 메모

- 끝 줄 꼴을 문자열 규칙 검사로만 지키지 않는다. 값 타입(`ToolFailureText`)을 받게 해서 새 실패 글이 끝 줄 없이 들어오면 컴파일이 깨지게 한다(설계 DECISION1)
- 결과 모양(`content`, `isError`, JSON-RPC `error` 의 `code`, `message`)은 바꾸지 않는다. 바뀌는 것은 글과 JSON 의 `next` 칸뿐이다
- 지금 글의 안내(「다시 부르지 않는다」, 「사용자에게 바꿀지 묻는다」)는 지우지 않고 앞에 둔다
- 「잠시 뒤 다시」 류 실패는 같은 호출을 다시 부르라는 다음 방법을 쓰지 않는다(스펙 DECISION10)

## 작업 항목

### 1. `shared/util/ToolFailureText.java`(신규)

- `public final class ToolFailureText`. 글 하나(`text`)를 감싼다. 생성자는 private 이다
- `static ToolFailureText alternative(String failure, String next)`: `failure + "\n다음 방법: " + next` 를 만든다. `next` 가 비었거나 null 이면 `IllegalArgumentException`
- `static ToolFailureText askUser(String failure)`: `failure + "\n사용자에게 바로 알린다."` 를 만든다
- `String text()`: 전체 글. `String endLine()`: 마지막 줄(「다음 방법: …」 이나 「사용자에게 바로 알린다.」). JSON 실패의 `next` 칸이 이 값을 쓴다
- 두 끝 줄의 머리를 상수로 둔다: `NEXT_PREFIX = "다음 방법: "`, `ASK_USER = "사용자에게 바로 알린다."`. 다음 phase 의 테스트가 이 값을 쓴다
- 다른 기능 패키지를 import 하지 않는다(rule-4). 클래스 Javadoc 에 스펙 REQ3 과 두 꼴의 쓰임을 한국어로 적는다
- `backend/docs/code-architecture.md` 의 `shared/util` 행에 「도구 실패 글의 끝 줄(`ToolFailureText`)」 을 더한다

### 2. `McpToolService` 의 결과 함수

- `static Map<String, Object> result(String, boolean)` 을 지우고 셋으로 나눈다
  - `static Map<String, Object> success(String text)`: `isError` 거짓
  - `static Map<String, Object> failure(ToolFailureText failure)`: `isError` 참, 글은 `failure.text()`
  - `static Map<String, Object> jsonFailure(Map<String, Object> fields, ToolFailureText next)`: `fields` 에 `"next": next.endLine()` 를 더한 JSON 글을 `isError` 참으로 돌려준다. 순서를 지키려고 `LinkedHashMap` 사본을 쓴다. `ObjectNode` 로 실패를 만들던 원고 도구와 사진 관찰 도구는 `LinkedHashMap` 에 같은 칸(`code`, `currentRevision`, `message`)을 넣도록 바꾼다
- 지금 private `failure(String code, String message)` 는 이름이 겹치므로 `failureBody` 로 바꾼다
- 성공을 돌려주는 모든 `result(…, false)` 는 `success(…)` 로 바꾼다. `CREATED`, `DUPLICATE` 같은 성공 글은 그대로다

### 3. `mcp/application` 의 실패 글

설계 「바꾸는 실패 글」 의 기준을 따른다. 입력 형식의 실패는 다음 방법(인자를 고쳐 다시), 권한·없는 대상·거절·개수 상한은 바로 알림, 서버 쪽 실패는 다음 방법(다른 도구가 있으면 그것으로, 없으면 잠시 뒤 다시 해 달라고 알린다)이다.

- `McpToolService`
  - `INVALID_CONTEXT`(호출 맥락 확인 불가)와 `NOT_ALLOWED_IN_CHECK`: 바로 알림. `INVALID_CONTEXT` 는 다른 클래스가 쓰므로 `static final ToolFailureText` 로 둔다
  - 「Memory 항목을 읽을 수 없습니다.」(없는 항목): 다음 방법 `memory_search 로 다른 항목을 찾는다`. 일시적 실패가 아니므로 「잠시 뒤 다시」 는 쓰지 않는다
  - 「결과물을 저장할 수 없습니다.」 두 곳: 다음 방법(서버 쪽 실패 문구)
  - `ArtifactSourceRejectedException` 의 두 글: 허용되지 않은 호스트(`allowed_host`)는 다음 방법 `허용된 이미지 호스트의 주소로 다시 부른다`(막히지 않은 다른 대상, 스펙 DECISION9), 주소 형식 오류는 다음 방법 `주소를 고쳐 다시 부른다`
  - 위임 조회의 `NOT_FOUND` 는 `jsonFailure` 와 바로 알림이다
  - 위임 실패(`delegationFailureMessage`, `Failure` 여덟 값)는 `jsonFailure` 로 바꾸고 아래 표대로 고른다. 글의 앞부분은 지금 글이다

    | `Failure` | 꼴과 다음 방법 |
    | --- | --- |
    | `AGENT_UNAVAILABLE`, `AGENT_DISABLED` | 다음 방법: 맡기지 않고 직접 처리하거나 다른 에이전트에 맡긴다 |
    | `DEPTH_EXCEEDED`, `CHECK_LIMIT` | 다음 방법: 맡기지 않고 직접 처리한다 |
    | `TOO_MANY_CHILDREN` | 다음 방법: 맡긴 일이 끝나기를 기다리거나 멈춘 뒤 다시 맡긴다(`docs/features/agent-skill.md` 의 같은 안내) |
    | `BUSY` | 다음 방법: 직접 처리하거나 앞의 작업이 끝난 뒤 다시 맡긴다 |
    | `SUBMIT_FAILED` | 다음 방법(서버 쪽 실패 문구) |
    | `CHECK_TARGET` | 다음 방법: 연결한 서비스의 도구를 직접 부르거나 연결한 서비스의 에이전트에 맡긴다 |
  - 할 일 제안: 「대화 밖의 실행에서는…」 은 바로 알림, title 길이와 `DUE_AT_FORMAT` 은 다음 방법 `형식에 맞게 고쳐 다시 부른다`, `DECLINED_BEFORE` 와 제안 수 상한은 바로 알림
- `McpMemoryRemember`: 거절(`REJECTED_BEFORE`), 개수(`TOO_MANY`), 확인 필요(`UPDATE_NEEDS_CONFIRMATION`), 암호화 불가, 기억을 남길 수 없는 에이전트, 고쳐 쓴 뒤 옛 기억으로 다시 남기려는 것(`SUPERSEDED_BEFORE`)은 바로 알림. collection key 형식 오류(「collection 은 소문자로 시작하는 key 다.」)도 다음 방법 `collection 없이 다시 부른다` 다. `COLLECTION_NOT_ALLOWED` 는 다음 방법 `collection 없이 다시 부른다`. `UPDATE_TARGET_NOT_FOUND` 는 다음 방법 `memory_id 없이 새로 남기고, 새로 남겼다고 답에 한 줄 알린다`(스펙 DECISION11). 입력 길이 넷은 다음 방법 `줄여서 다시 부른다`. static import 를 `success`, `failure` 로 바꾼다
- `McpFileSpaceTools.failure(...)`: 까닭(`Reason`)이 경로나 형식이면 다음 방법 `경로나 형식을 고쳐 다시 부른다`, 용량과 권한은 바로 알림, 보관 실패는 서버 쪽 실패 문구. 「사용자에게 넣었다고 말하지 않는다」 는 앞에 그대로 둔다
- `McpConnectorListTool.FAILED`: 다음 방법 `다른 방법이 없으니 /connections 링크로 외부 서비스 연결 화면을 안내하고 잠시 뒤 다시 해 달라고 알린다`
- `McpContentDraftTools`, `McpMediaObservationTools`: JSON 실패를 `jsonFailure` 로 만든다. 각 클래스에 `private static ToolFailureText nextFor(ErrorCode code)` 를 둔다. 충돌(`*_CONFLICT`, `CONTENT_DRAFT_STALE`)은 다음 방법 `currentRevision 으로 다시 읽고 고쳐 부른다`, `CONTENT_DRAFT_DUPLICATE_PHOTO`·`IMAGE_SOURCE_UNKNOWN`·바인딩 선택은 지금 `message` 의 뜻을 다음 방법으로, `IMAGE_SOURCE_GONE` 은 바로 알림(지금 글이 사용자에게 까닭을 알리라고 한다), `INTERNAL_ERROR` 는 서버 쪽 실패 문구, 나머지(없는 대상, 만료, 권한, 암호화 불가)는 바로 알림. 호출 맥락 오류는 `McpToolService.INVALID_CONTEXT` 를 쓰고, 같은 글을 따로 둔 `McpMediaObservationTools` 의 private `INVALID_CONTEXT` 와 `McpContentDraftTools.invalidContext()` 의 글은 지운다
- `McpFinancialLimitTools`, `McpApprovalResumeTool`: 성공만 있으면 `success` 로 바꾼다. 실패 글이 있으면 같은 기준으로 고른다

### 4. `McpRpc` 와 `mcp/presentation` 의 인자 오류

- `invalidParams(JsonNode id, ToolFailureText reason)`: `data` 에 `reason.text()`. `error(JsonNode id, int code, ToolFailureText message)`: `message` 에 `message.text()`
- `INVALID_ARGUMENTS` 를 `static final ToolFailureText` 로 바꾼다. 글은 `alternative("인자 형식이 올바르지 않습니다.", "도구 설명의 인자 형식에 맞게 고쳐 다시 부른다")`
- `McpController.SEARCH_RESTART` 는 `alternative("검색 순위가 앞 페이지와 달라졌습니다.", "cursor 없이 다시 검색한다")`
- `McpController` 의 `Method not found`: 모르는 도구 이름은 `alternative("Method not found", "tools/list 에 있는 도구 이름으로 다시 부른다")`, 모르는 JSON-RPC method 는 `askUser("Method not found")`. `malformed()` 의 `-32700 Parse error` 는 모델에게 가지 않으므로 `McpRpc` 에 `String` 을 받는 `parseError()` 를 따로 두고 그것을 쓴다
- `McpArtifactCalls.artifactValidationReason` 은 `ToolFailureText` 를 돌려준다. 까닭 글마다 다음 방법 `경로, 확장자, 크기를 고쳐 다시 부른다`
- `McpFileSpaceCalls` 는 `INVALID_ARGUMENTS` 그대로 쓴다

### 5. 문서

- `docs/features/content-draft.md` 의 「MCP 결과」 표에서 `isError=true` 인 JSON 행에 「`next` 칸(다음 방법이나 바로 알림 한 줄)」 을 더한다
- `docs/features/attachment.md` 의 사진 관찰 오류 표(`MEDIA_OBSERVATION_CONFLICT` 행 근처)에 같은 `next` 칸을 적는다

### 6. 테스트

- `backend/src/test/java/com/bifos/assistant/shared/util/ToolFailureTextTest.java`(신규, 순수 단위): `alternative` 와 `askUser` 의 글과 `endLine`, 빈 `next` 의 예외
- 기존 MCP 테스트의 기대 글을 새 글에 맞춘다. 대상은 `grep -rlE "결과물을 저장할 수 없습니다|고칠 기억을 찾지 못했다|인자 형식이 올바르지|Invalid params|INVALID_CONTEXT|McpToolService.result" backend/src/test` 로 찾는다. 기대값을 지우지 말고 새 끝 줄까지 본다
- `McpMemoryRememberToolTest` 에 `UPDATE_TARGET_NOT_FOUND` 결과가 「다음 방법: memory_id 없이 새로 남기고」 로 끝나는 사례를 하나 더한다
- `McpContentDraftToolTest` 와 `McpMediaObservationToolTest` 의 충돌 사례에 JSON 의 `next` 칸이 「다음 방법: 」 으로 시작하는지 더한다
- `McpAgentToolsTest` 의 위임 실패 사례(1506행 근처 `containsExactly("code", "message")`)를 `"code", "message", "next"` 로 맞춘다
- `backend/src/test/java/com/bifos/assistant/mcp/McpToolResultShape.java` 의 `assertCallToolResult` 가 `isError` 참인 결과에서 글의 마지막 줄이 `ToolFailureText.NEXT_PREFIX` 로 시작하거나 `ASK_USER` 와 같은지, 글이 JSON 객체면 `next` 칸이 그런지도 본다. 이 helper 를 쓰는 MCP 테스트의 실패 사례가 모두 끝 줄을 검사하게 된다. `accepts` 는 Hermes 모양 fixture 와 맞춰 보는 함수라 바꾸지 않는다

## 검증

```bash
(cd backend && HEAVY_SLOTS=2 orch slot heavy -- ./gradlew test --tests '*ToolFailureTextTest' --tests 'com.bifos.assistant.mcp.*' --tests '*ArtifactMcpValidationTest' --tests '*ContentDraftControllerTest' --tests '*ArchitectureRules*')
! grep -rnE "(McpToolService\.|[^A-Za-z.])result\(" backend/src/main/java/com/bifos/assistant/mcp/application
```

기대값: 테스트가 모두 통과한다. 둘째 줄은 옛 `result(` 호출을 찾지 못해 종료 코드 0 이다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/shared/util/ToolFailureText.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/mcp/application/McpToolService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/mcp/application/McpMemoryRemember.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/mcp/application/McpFileSpaceTools.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/mcp/application/McpConnectorListTool.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/mcp/application/McpContentDraftTools.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/mcp/application/McpMediaObservationTools.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/mcp/application/McpFinancialLimitTools.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/mcp/application/McpApprovalResumeTool.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/mcp/presentation/McpRpc.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/mcp/presentation/McpController.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/mcp/presentation/McpArtifactCalls.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/mcp/presentation/McpFileSpaceCalls.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/shared/util/ToolFailureTextTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/mcp/McpMemoryRememberToolTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/mcp/McpContentDraftToolTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/mcp/McpMediaObservationToolTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/mcp/McpAgentToolsTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/mcp/McpToolResultShape.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/mcp/McpArtifactWriteToolTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/mcp/McpCallerInvariantTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/mcp/McpContentDraftSnapshotsTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/mcp/McpFollowUpToolTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/mcp/McpMemorySearchToolTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/mcp/McpMemoryToolTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/mcp/McpPrincipalTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/mcp/application/McpConnectorListToolTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/mcp/application/McpToolServiceTest.java` | 수정 |
| `backend/docs/code-architecture.md` | 수정 |
| `docs/features/content-draft.md` | 수정 |
| `docs/features/attachment.md` | 수정 |
