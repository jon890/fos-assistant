# Phase 03. memory_link 도구와 외부 글 기록

**Execution profile**: deep

## 목표

에이전트가 `memory_link` 로 대체 제안과 `RELATED` 연결을 건다. `memory_remember` 의 기록에 그 대화가 바깥 글을 읽었는지 남긴다.

**범위 외**: 코드가 후보를 찾는 것(phase 06), 화면(phase 04).

## 컨텍스트

**근거 문서**: `docs/features/memory.md` 의 「기억 사이의 연결」(도구 계약)과 「바로 저장 판정」 의 조건 4, `hermes/plugins/fos-ctx/README.md` 의 서명 필수 도구 목록.

- 도구 길: `mcp/application/McpToolCatalog`(정의) → `mcp/presentation/McpController`(인자 검사, `handlers` 맵, 살펴보기 트리 제한 `CHECK_TREE_TOOLS`) → `McpDtos`(인자 record) → 처리 클래스. `memory_remember` 의 본보기는 `mcp/application/McpMemoryRemember`
- 조건 4: `McpMemoryRemember.directAllowed` 의 `executionEvents.existsOutsideToolStartInConversation(conversationId, INTERNAL_TOOLS)`. 지금 조건 5 와 `&&` 로 묶여 있다
- 기록: `MemoryCaptureService.record` 와 `MemoryRememberRequest`, `chat/presentation/ChatDtos.MemoryCaptureView`
- 서명 필수: `hermes/plugins/fos-ctx/hooks.py` 의 `REQUIRED_TOOLS`, `hermes/tests/test_fos_ctx.py`, README 의 목록 셋, `hermes/docs/hermes-contract.md` 의 자식 호출 목록
- 실행 사건 가리기: `hermes/HermesRunEventStream` 의 `MEMORY_REMEMBER_TOOL_SUFFIX` 와 같은 자리

## 작업 항목

### 1. `McpMemoryLink.java` 신규

- `mcp/application/McpMemoryLink.java` 신규: `link(McpCaller, Long fromId, Long toId, MemoryLinkType, String label)`. origin 실행의 사용자와 에이전트의 `MemoryAccess` 로 두 항목을 `memory_read` 와 같은 권한으로 확인한 뒤 `MemoryLinkService.proposeSupersede` 나 `relate` 를 부른다(`origin=AGENT`, 실행 번호). 한 실행의 링크 수 상한 `PER_EXECUTION_MAX = 10`. 볼 수 없는 항목과 규칙 위반은 같은 결과 글 「연결할 수 없는 기억입니다.」(`isError=true`)

### 2. `McpToolCatalog` 의 도구 정의

- `McpToolCatalog` 에 `memory_link` 정의: `from_id`, `to_id`(정수), `type`(`SUPERSEDES`, `RELATED`), `label`(문자열, 200자 이하, 선택). 설명은 「대체는 새 기억이 옛 기억을 대신한다는 제안이고 사용자가 받아들여야 바뀐다. 관련은 바로 연결된다. 연결의 뜻은 label 에 적는다.」

### 3. `McpController` 의 인자 검사

- `McpController`: 받는 키를 `from_id`, `to_id`, `type`, `label` 로 검사하고(모르는 키, 잘못된 타입, 범위 밖 값은 -32602), 살펴보기 트리에서는 `notAllowedInCheck` 다

### 4. 외부 글 기록(`external_read`)

- `McpMemoryRemember.directAllowed` 에서 조건 4 의 값을 따로 계산해 `MemoryRememberRequest.externalRead` 로 넘기고, `MemoryCaptureService.record` 가 `MemoryCapture.externalRead` 에 적는다. `MemoryCaptureView` 에 `externalRead` 를 더한다

### 5. fos-ctx 와 실행 사건 가리기

- fos-ctx: `REQUIRED_TOOLS` 와 README 목록, `test_fos_ctx.py` 의 목록에 `memory_link` 를 더한다. `HermesRunEventStream` 이 `memory_link` 의 시작 사건과 끝 사건 `detail` 을 비운다. `hermes-contract.md` 의 자식 호출 목록에 더한다

### 6. 기억 지침의 한 줄

- `ContextAssembler.MEMORY_INSTRUCTIONS` 에 한 줄: 「사용자가 말한 새 사실이 기억한 옛 사실을 대신하면 새 사실을 남긴 뒤 memory_link 로 SUPERSEDES 를 건다. 사용자가 옛 사실을 직접 정정하면 memory_id 로 고친다.」

### 7. 이 phase 를 검증하는 테스트

- `backend/src/test/java/com/bifos/assistant/mcp/McpMemoryLinkToolTest.java` 신규: 대체 제안은 `PROPOSED` 이고 주입이 바뀌지 않는다. `RELATED` 는 `ACTIVE` 이고 `label` 이 남는다. 볼 수 없는 항목, 다른 범위, `PROPOSED` 항목은 같은 오류 글이다. 인자 오류는 -32602, 상한을 넘으면 오류 글, 살펴보기 트리에서는 막는다. 결과와 로그에 제목과 본문이 없다
- `McpMemoryToolTest` 에 schema 의 인자 이름
- `McpMemoryRememberTest`(또는 그 동작을 검사하는 기존 테스트)에 바깥 도구를 부른 대화의 제안 기록이 `externalRead=true` 이고, 그렇지 않은 바로 저장 기록은 거짓인 경우
- `hermes/tests/test_fos_ctx.py` 가 새 도구를 서명 필수로 안다
- `HermesRunEventStreamTest`(있는 테스트)에 `memory_link` 사건의 `detail` 이 비는 경우

## 검증

```bash
cd backend && ./gradlew test --tests '*McpMemoryLinkToolTest' --tests '*McpMemoryToolTest' --tests '*McpMemoryRemember*' --tests '*HermesRunEventStream*' --tests '*MemoryCapture*'
cd hermes && python3 -m pytest tests/test_fos_ctx.py -q
cd backend && ./gradlew archTest checkstyleMain checkstyleTest
```

기대: 모두 통과.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/mcp/application/McpMemoryLink.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/mcp/application/McpToolCatalog.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/mcp/presentation/McpController.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/mcp/presentation/McpDtos.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/mcp/application/McpMemoryRemember.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/memory/application/model/MemoryRememberRequest.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/memory/application/MemoryCaptureService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/presentation/ChatDtos.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/hermes/HermesRunEventStream.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/context/ContextAssembler.java` | 수정 |
| `hermes/plugins/fos-ctx/hooks.py` | 수정 |
| `hermes/plugins/fos-ctx/README.md` | 수정 |
| `hermes/docs/hermes-contract.md` | 수정 |
| `hermes/tests/test_fos_ctx.py` | 수정 |
| `backend/src/test/java/com/bifos/assistant/mcp/McpMemoryLinkToolTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/mcp/McpMemoryToolTest.java` | 수정 |
