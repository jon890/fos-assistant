# Phase 03. MCP 도구 연결과 실행 입력 안내

**Execution profile**: standard

## 목표

`fos-assistant-memory` MCP 서버가 등록된 profile 에 `artifact_write` 를 열고, 도구로 쓴 HTML 이 turn 의 답에 붙는 흐름을 검증한다.

**범위 외**: Hermes core 와 profile 설정 변경, 에이전트별 도구 설정 화면, 배포와 운영 명령.

## 컨텍스트

**근거 문서**: [MCP 계약](../../docs/hermes/tools-and-skills.md#결과물-쓰기-도구), [쓰기 흐름](../../docs/flow.md#결과물을-mcp-로-쓸-때), [실행 입력 안내](../../docs/code-architecture.md#에이전트에게-알리는-법-1), [ADR-028](../../docs/adr/ADR-028-결과물은-사용자의-대화-폴더에-mcp-도구로-쓴다.md).
근거 문서 경로는 `docs/hermes/tools-and-skills.md`, `docs/flow.md`, `docs/code-architecture.md`, `docs/adr/ADR-028-결과물은-사용자의-대화-폴더에-mcp-도구로-쓴다.md` 다.

본문과 URL 저장 서비스가 구현되고 검사를 통과한 뒤 연결한다.
현재 코드는 아래와 같다. MCP 서버 이름과 프로토콜, 인증 경계는 바꾸지 않는다.

| 현재 파일과 식별자 | 확인한 동작 |
| --- | --- |
| `mcp/application/McpToolService.java` 의 `tools()`, `call(CurrentUser, String, Long)` | `memory_read` 하나를 내주며 모든 호출 인자가 정수 Memory 번호 |
| `mcp/presentation/McpController.java` 의 `call(JsonNode, JsonNode)` | 모든 도구의 `arguments.id` 를 정수로 검사 |
| `mcp/application/AgentTokenService.java` 의 `authenticate(String)` | `CurrentUser` 반환. 대화와 실행은 토큰에 없음 |
| `chat/application/ArtifactService.java` 의 `agentPreamble(Long)` | 폴더 경로와 설명만 있는 `[결과물 폴더]` 단락 |
| `chat/application/ChatService.java` 의 `runTurn`, `runFlowTurn` | 일반 실행과 흐름 실행 두 곳에서 `agentPreamble` 호출 |
| `orchestration/application/ResearchAndBuildFlow.java` 의 `runChild` | 하위 실행 입력에 `agentPreamble` 추가 |
| `backend/src/test/java/com/bifos/assistant/mcp/McpMemoryToolTest.java` | 실제 HTTP 로 MCP 인증, 도구 목록, 정수 인자와 오류 검증 |
| `test/e2e/fake-hermes.ts` 의 `splitArtifactPreamble`, `ARTIFACT_PROBE` | 실행 입력 단락을 떼고 현재는 대역이 결과물 파일을 직접 씀 |

위 Java 경로는 `backend/src/main/java/com/bifos/assistant/` 아래다.

## 의도 메모

- 도구 인자는 권한 증명이 아니다. UUID 를 맞춰도 토큰 사용자 소유 대화만 쓴다
- 도구 입력 안내는 저장하는 사용자 메시지에 섞지 않는다. 내부 번호를 새 MCP 인자로 내보내지 않는다
- URL 허용 목록이 비어도 도구 목록에서 `artifact_write` 를 빼지 않는다. 본문 방식은 쓸 수 있다
- Hermes 에서 이 도구가 보이는 것은 `fos-assistant-memory` MCP 서버가 등록된 profile 에 한정된다. 등록 유무는 이 저장소의 대역이 검증하지 않고 PR 에 적용 범위를 적는다
- PRD 는 기존 HTML 결과물 요구의 구현 방법이 바뀌므로 영향이 없다.
  데이터 스키마는 기존 `conversation` 과 `chat_artifact` 를 재사용해 영향이 없다

## 작업 항목

### 1. `McpDtos` 와 도구별 인자 검사를 만든다

`backend/src/main/java/com/bifos/assistant/mcp/presentation/McpDtos.java` 에
`MemoryReadArguments(Long id)` 와
`ArtifactWriteArguments(String conversationId, String path, String content, String sourceUrl)` record 를 둔다.
외부 이름 `conversation_id`, `source_url` 과의 매핑은 이 파일에서 지정한다.
Spring Boot 4 의 Jackson 3 인 `tools.jackson` 을 사용한다.

`McpController.call` 은 먼저 이름이 문자열이고 `arguments` 가 객체인지 검사한다.
이름으로 분기해 `memory_read` 는 지금처럼 정수 `id` 와 Long 범위를 검사한다.
모르는 이름은 다른 도구의 필수 인자를 요구하지 않고 `-32601` 로 답한다.
`artifact_write` 는 [MCP 계약](../../docs/hermes/tools-and-skills.md#결과물-쓰기-도구) 의 인자만 받는다.
UUID 는 36자 표준 문자열 모양을 검사한 뒤 `UUID.fromString` 으로 변환한다.
숫자 내부 번호와 Java 가 받아들이는 축약 UUID 를 거절한다.
`content` 와 `source_url` 의 배타 조건과 JSON 타입, `null`, 필수 값과 추가 인자를 검사한다.
검사를 통과한 DTO 를 `ArtifactWriteRequest` 로 바꾼다.

기존 `McpToolService.call(CurrentUser, String, Long)` 의 통합 인자를 없애고
`readMemory(CurrentUser, Long)` 와 `writeArtifact(CurrentUser, ArtifactWriteRequest)` 로 나눈다.
도구 이름 분기는 컨트롤러에 둔다.
`memory_read` 의 기존 결과와 오류는 그대로 유지하고, 저장 서비스의 잘못된 입력은 `-32602` 로 바꾼다.
주인 확인, URL 방어, 다운로드와 파일 I/O 실패는 `content` 텍스트와 `isError: true` 로 돌려준다.
오류 처리가 내부 경로나 URL query 를 드러내지 않게 한다.
인증 실패 401, Origin 거절 403, 알림 응답 202 와 JSON-RPC 요청 `id` 는 유지한다.

### 2. 도구 정의와 저장 결과를 연결한다

`McpToolService.tools()` 에 `artifact_write` 정의를 더한다.
필수 인자는 `conversation_id`, `path` 이고 `content`, `source_url` 은 문자열이다.
JSON Schema 에 `additionalProperties: false` 와 정확히 한 방식만 받는 `oneOf` 조건을 둔다.
도구 설명에 대화 UUID, 상대 경로, 두 방식의 확장자, 5MB 상한과 덮어쓰기를 적는다.
도구 설명만 믿지 않고 서버의 검증도 같은 조건을 적용한다.

`writeArtifact` 는 `ArtifactWriteService.write` 를 호출해 성공 JSON 을 기존 MCP 텍스트 결과로 감싼다.
성공 결과는 `path`, `byteSize` 두 칸이며 `isError: false` 다.
실제 폴더 경로와 내부 대화 번호를 반환하지 않는다.
도구 목록은 사용자와 에이전트 종류에 따라 나누지 않는다.
서버 이름 `fos-assistant-memory` 와 `/mcp`, `2025-03-26` 프로토콜은 그대로 둔다.

### 3. 대화 UUID 와 쓰기 안내를 매 실행에 넣는다

`ArtifactService.agentPreamble(Long conversationId)` 를 `agentPreamble(Conversation conversation)` 으로 바꾼다.
폴더 경로는 `conversation.id()`, 도구 인자는 `conversation.publicId()` 에서 가져온다.
단락 내용은 [실행 입력 계약](../../docs/code-architecture.md#에이전트에게-알리는-법-1) 을 따른다.
`conversation_id`, `path`, `content`, `source_url` 을 설명하고 폴더 밖 경로를 요구하지 않는다.

`ChatService` 의 두 호출과 `ResearchAndBuildFlow.runChild` 의 호출을 모두 바꾼다.
`ArtifactStore.ensureFolder`, `ArtifactService.recordTurn` 의 호출 순서와 답 연결은 유지한다.
일반 보내기, 다시 생성, 스트리밍과 흐름의 Chief 및 하위 실행이 동일한 대화 UUID 를 받는다.
사진 단락보다 앞에 두고 저장 메시지에는 사용자 원문만 남긴다.
기존 테스트의 `agentPreamble(Long)` 호출과 단락 기대값도 새 계약에 맞춘다.

### 4. HTTP 와 turn 연결 테스트를 추가한다

`backend/src/test/java/com/bifos/assistant/mcp/McpArtifactWriteToolTest.java` 를 신규로 만든다.
기존 `McpMemoryToolTest` 는 도구 수를 2개로 바꾸되 Memory 인자와 인증 회귀 검사는 유지한다.
`ArtifactTest`, `ChatServiceTest`, `ResearchAndBuildFlowTest` 에 실행 입력의 UUID 와 본문 보존을 검증한다.

| 상황 | 관측 결과 |
| --- | --- |
| `tools/list` | 두 도구, 서버 이름과 프로토콜 유지, 저장 도구의 schema 일치 |
| 본인 토큰과 대화 UUID 의 HTML 요청 | 파일 생성, JSON 텍스트 결과의 경로와 크기, `isError: false` |
| 빈 본문, 본문과 URL 둘 다 또는 둘 다 없음, `null`, 숫자 본문 | 빈 본문만 성공, 나머지 `-32602` |
| 잘못된 UUID, 축약 UUID, 내부 번호, base64 와 추가 인자 | `-32602`, 파일 저장 없음 |
| 모르는 도구의 빈 인자 | `-32601` |
| 남의 대화, 없는 대화, 지운 대화 | 같은 `isError: true`, 폴더와 다운로드 호출 없음 |
| 본인 소유의 다른 대화 | 그 폴더에 저장, 현재 답에는 붙지 않음 |
| URL 거절과 다운로드 실패 | `isError: true`, 기존 파일 보존 |
| 재작성 HTML 과 CSS, 이미지만 생성 | 바뀐 HTML 만 답에 연결, CSS 와 이미지 행 없음 |
| 스트리밍, 다시 생성, 흐름의 하위 실행 | 입력 UUID 일치, 사용자 메시지 원문 유지 |

`test/e2e/fake-hermes.ts` 의 단락 파서를 UUID 와 여러 줄 설명을 받게 바꾼다.
새 `ARTIFACT_WRITE_PROBE` 를 추가해 실제 `/mcp` 에 도구를 호출하게 한다.
기존 `ARTIFACT_PROBE` 와 `writeArtifactDraft` 는 브라우저 검사도 쓰므로 기존 동작을 유지한다.
새 probe 는 폴더에 직접 쓰지 않고 MCP 호출이 성공한 뒤에 실행을 완료한다.
시나리오에서 테스트 관리자 토큰으로 `/api/v1/admin/agent-tokens` 를 호출해 MCP 토큰을 발급하고,
대역에 런타임 호출 URL 과 토큰을 주입한다. 운영 설정과 인증 우회를 추가하지 않는다.
`Context.api` 는 `/api/v1` 까지 있으므로 `/mcp` 는 그 접두사 없이 호출한다.
테스트 종료 시 발급한 토큰을 폐기한다.
외부 이미지 다운로드는 backend 의 주입한 전송 대역에서 검증하고 e2e 는 본문 방식으로 전체 왕복을 확인한다.

`test/e2e/scenarios/artifact.ts` 에 새 probe 를 쓰는 왕복 검사를 더한다.
`test/index.html` 과 `test/style.css` 를 MCP 로 쓰고 turn 답의 `artifacts`, 파일 본문과 기존 CSP 를 검증한다.
기존 probe 의 사진 읽기 검사는 유지해 직접 쓴 파일과 MCP 가 만든 HTML 을 구분한다.
새 e2e scenario 를 추가하지 않으면 `test/e2e/run.ts` 의 목록은 수정하지 않는다.

## 검증

아래 명령을 적힌 순서대로 실행한다.
이전 실행이 남긴 데이터를 정리하는 `gradlew test` 를 건너뛰면 e2e 가 실패할 수 있다.
전체 검증의 기대값은 종료 코드 0이다.

```bash
# cwd: backend/
./gradlew test --tests '*McpArtifactWriteToolTest' --tests '*McpMemoryToolTest' --tests '*ArtifactTest' --tests '*ChatServiceTest' --tests '*ResearchAndBuildFlowTest'
./gradlew test
```

```bash
# cwd: web/
pnpm typecheck
AUTH_SECRET=build-time-placeholder \
ASSISTANT_JWT_SECRET=build-time-placeholder \
CONTROL_PLANE_BASE_URL=http://build-time-placeholder \
AUTH_GOOGLE_ID=build-time-placeholder \
AUTH_GOOGLE_SECRET=build-time-placeholder \
pnpm build
pnpm test:browser
```

```bash
# cwd: 저장소 root
node test/e2e/run.ts
node --test 'test/unit/**/*.test.ts'
scripts/check-public-safe.sh
python3 ~/.claude/skills/planning/scripts/verify_task.py plan029-artifact-write-tool
```

Node 는 22.18 이상을 쓴다.

## 성공 기준

자동 검사에서 아래 결과를 관측한다.

| 관측 | 충족 조건 |
| --- | --- |
| `file` 과 `terminal` 을 쓰지 않는 대역 | 실제 MCP HTTP 호출로 `test/index.html` 을 만들고 그 turn 의 답에 path 가 붙음 |
| 실행 입력의 `publicId` | MCP 의 `conversation_id` 와 일치하고 내부 번호를 넘기지 않음 |
| 같은 경로 재작성 | 최신 본문을 읽고 실패하면 이전 파일 보존 |
| 이미지 URL | 허용한 공개 IP 와 MIME 만 저장하며 SSRF 거절과 5MB 상한 검사 통과 |
| 일반 파일 도구를 닫은 상태 | Hermes 설정을 바꾸지 않고 기존 MCP 서버를 사용 |

운영 확인은 배포 뒤 코디네이터가 맡는다.
가족용 에이전트에게 HTML 생성을 요청해 답에 파일이 붙고 패널에서 보이는 결과를 확인한다.
URL 저장의 운영 확인은 `fos-home-infra` 에서 실제 호스트를 설정한 뒤 수행한다.
배포와 홈서버 명령은 이 phase 에 넣지 않는다.

검사를 모두 통과하면 `index.json` 의 `status` 를 `completed`, `current_phase` 를 3으로 바꾼다.
구현이 끝나면 저장소 규칙대로 계획 디렉터리를 지운다.
오래 남을 계약은 이미 `docs/` 에 있으므로 계획서를 가리키는 참조를 남기지 않는다.

## Critical Files

| 파일 | 변경 |
| --- | --- |
| `backend/src/main/java/com/bifos/assistant/mcp/presentation/McpDtos.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/mcp/presentation/McpController.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/mcp/application/McpToolService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/application/ArtifactService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/application/ChatService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/orchestration/application/ResearchAndBuildFlow.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/mcp/McpArtifactWriteToolTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/mcp/McpMemoryToolTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/ArtifactTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/ChatServiceTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/ChatAttachmentTurnTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/ChatRegenerateTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/RegenerateDeletedAttachmentTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/orchestration/ResearchAndBuildFlowTest.java` | 수정 |
| `test/e2e/fake-hermes.ts` | 수정 |
| `test/e2e/scenarios/artifact.ts` | 수정 |
