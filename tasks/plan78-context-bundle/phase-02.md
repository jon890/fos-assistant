# Phase 02. 문맥 묶음 모델과 Memory 항목

**Execution profile**: deep

## 목표

`context` 패키지에 문맥 묶음의 항목과 묶음 타입을 두고, `ContextAssembler` 가 Memory 를 항목으로 만든 뒤 지금과 같은 글로 옮긴다.
충돌이 없는 경우 `instructions` 와 `instructions_hash` 가 바뀌지 않는다. 항목과 묶음이 로그에 본문을 흘리지 않게 하고, 같은 이름의 개인 문서와 그룹 문서에 충돌 표시를 붙인다.

**범위 외**: 항목 참조의 저장은 phase 03, 결과 항목(`DELEGATION_RESULT`, `CONNECTOR_RESULT`)과 그 머리줄은 phase 04 다. `EXECUTION_STATE` 와 `FOLLOW_UP` 은 plan79 와 plan80 이 만든다.

## 컨텍스트

- `backend/src/main/java/com/bifos/assistant/context/ContextAssembler.java`
  - `assemble(CurrentUser, Long agentId)` → private `assemble(CurrentUser, MemoryAccess)` 가 `memories.alwaysInjectedFor` 와 `memories.indexedFor` 를 읽고 `ContextBuilder` 로 글을 만든다
  - 항상 층은 `appendAlways` 가 `GROUP_HEADER`, `USER_HEADER` 아래 `"- " + memory.content()` 를 id 오름차순으로 싣는다. 암호문(`memory.sealed()`)은 `plainOrSkipped` 가 건너뛰고 번호만 warn 로그로 남긴다
  - 색인 층은 `appendIndex` 가 `INDEX_HEADER` 아래 `indexItem(memory)` = `"- [" + id + "] " + title` 을 싣는다. `indexLength` 가 같은 `indexItem` 으로 색인 몫을 센다
  - `ContextBuilder.append(memoryId, header, item)` 이 자리가 없으면 그 항목만 `omitted` 에 넣는다
  - `withResponseInstructions` 가 `RESPONSE_INSTRUCTIONS` 를 앞에 붙인다. `assembleForOwner` 와 `omittedFor` 는 화면용이다
- `backend/src/main/java/com/bifos/assistant/context/AssembledContext.java` 는 `record AssembledContext(String instructions, long chars, List<Long> omittedMemoryIds)` 이고 `instructionsHash()` 가 `Sha256.hex16(instructions)` 다. record 기본 `toString` 이 `instructions` 전체를 낸다
- 호출하는 곳: `chat/application/ChatService.java` 의 `runTurn`(`AssembledContext.empty()` 와 `contextAssembler.assemble(...)`), `orchestration/application/AgentRunner.java`. 테스트는 `ContextAssemblerTest`, `ChatServiceTest`, `ExecutionLifecycleTest`, `AgentRunnerSubmitFailureTest`, `AgentRunnerConnectorContextTest` 가 `AssembledContext` 를 만든다
- `Memory`(`memory/domain/Memory.java`)는 `@Accessors(fluent = true)` 라 `id()`, `scope()`, `ownerUserId()`, `collection()`, `entryType()`, `documentKey()`, `title()`, `content()`, `sensitivity()`, `revision()`, `updatedAt()` 로 읽는다. 타입은 `memory.domain.type` 의 `MemoryScope`, `MemorySensitivity`, `MemoryEntryType`
- `context` 는 층 순서에서 `memory` 위라 `memory` 의 타입을 쓸 수 있다
- `backend/src/main/java/com/bifos/assistant/hermes/HermesRunEventStream.java` 의 `toRunEvent` 가 `firstDetail` 로 `preview`, `detail`, `result` 가운데 첫 값을 `detail` 로 쓰고 `ToolDetailRedactor.redact` 로 가린다. Control Plane MCP 도구 이름은 Hermes 가 `mcp__fos_assistant__memory_read` 처럼 붙여 보낸다(`hermes/plugins/fos-ctx/__init__.py` 의 `TOOL_PREFIX`)

**근거 문서**: `docs/backend/context-bundle.md` 의 「항목의 칸」, 「참여하는 source」, 「Hermes 에 넘기는 형식」, 「충돌 표시」, 「로그와 저장」, `docs/adr/ADR-071-여러-출처의-문맥은-항목마다-출처와-권한과-신선도를-지닌-묶음으로-조립한다.md`

## 의도 메모

- Memory 의 글을 바꾸지 않는 것이 이 phase 의 핵심이다. 바뀌면 모든 실행의 `instructions_hash` 가 한 번에 달라져 사용량 화면의 지문 축이 끊긴다. 충돌 표시만 예외다
- 항목 타입을 저장하지 않는다. `@Enumerated` 가 아니므로 enum 은 `context` 패키지에 둔다. `context` 패키지는 지금 하위 층 없이 한 디렉터리이고 그 모양을 따른다
- 묶음이 판정을 다시 하지 않는다. 항목은 `MemoryService` 가 이미 거른 목록에서만 만든다
- `toString` 을 고치는 것은 지금 로그에 본문을 넘기는 코드가 없어도 한다. record 를 로그 인자로 넘기는 순간 본문이 남는다

## 작업 항목

### 1. `context` 패키지의 새 타입

`backend/src/main/java/com/bifos/assistant/context/` 에 파일 하나씩 둔다.

| 타입 | 값 |
| --- | --- |
| `ContextSource` | `MEMORY_ALWAYS`, `MEMORY_INDEX`, `DELEGATION_RESULT`, `CONNECTOR_RESULT`, `EXECUTION_STATE`, `FOLLOW_UP` |
| `ContextTrust` | `USER_APPROVED`, `CONTROL_PLANE`, `AGENT`, `EXTERNAL` |
| `ContextBodyMode` | `INLINE`, `TITLE_ONLY`, `OMITTED` |
| `ContextFreshness` | `FRESH`, `STALE`, `UNKNOWN` |
| `ContextItem` | record. `ContextSource source`, `String ref`, `MemoryScope scope`, `Long ownerUserId`, `MemorySensitivity sensitivity`, `ContextTrust trust`, `Instant asOf`, `ContextFreshness freshness`, `ContextBodyMode bodyMode`, `List<String> conflictsWith`, `String title`, `String body` |
| `ContextBundle` | record. `List<ContextItem> items` |

- `ContextItem.toString()` 은 `ContextItem[source=MEMORY_INDEX, ref=memory:12]` 만 낸다. `ContextBundle.toString()` 은 항목 수와 각 항목의 `source:ref` 만 낸다
- `ref` 는 `memory:<번호>` 형식이다. 정적 함수 `ContextItem.memoryRef(Long id)` 로 만든다
- `ContextItem` 의 메서드 `withBodyMode(ContextBodyMode)` 가 `bodyMode` 만 바꾼 사본을 낸다

### 2. `AssembledContext`

record 에 네 번째 칸 `ContextBundle bundle` 을 더한다. 기존 두 생성자 `(instructions, chars)`, `(instructions, chars, omittedMemoryIds)` 는 빈 묶음으로 남긴다. `empty()` 도 빈 묶음이다.
`toString()` 은 `AssembledContext[chars=…, omittedItems=…, items=…]` 만 낸다. `instructions` 를 내지 않는다.
`withResponseInstructions` 는 묶음을 그대로 옮긴다.

### 3. `ContextAssembler`

- 항상 층의 Memory 마다 `MEMORY_ALWAYS` 항목을 만든다. `trust=USER_APPROVED`, `freshness=FRESH`, `asOf=updatedAt()`, `bodyMode=INLINE`, `title=null`, `body=content()`. 암호문이라 건너뛴 줄은 항목을 만들지 않는다
- 색인 층의 Memory 마다 `MEMORY_INDEX` 항목을 만든다. `bodyMode=TITLE_ONLY`, `title=title()`, `body=null`
- `ContextBuilder.append` 가 자리가 없어 뺀 항목은 같은 항목의 `bodyMode=OMITTED` 사본으로 묶음에 남긴다. `omittedMemoryIds` 는 지금처럼 남긴다
- 묶음의 항목 순서는 글에 실은 순서(그룹 항상 층, 개인 항상 층, 색인)이고, 빠진 항목은 원래 자리에 둔다
- 글은 항목의 `body` 와 `title` 에서 지금과 같은 모양(`"- " + body`, `"- [" + id + "] " + title`)으로 만든다
- **충돌 표시**: 색인 목록 안에서 `entryType()==DOCUMENT` 이고 `collection()` 과 `documentKey()` 가 같은데 `scope()` 가 다른 두 줄을 찾는다. `USER` 줄 끝에 ` (같은 이름의 그룹 문서 [<그룹 줄 번호>] 가 있다)`, `GROUP` 줄 끝에 ` (같은 이름의 개인 문서 [<개인 줄 번호>] 가 있다)` 를 붙이고, 두 항목의 `conflictsWith` 에 서로의 `ref` 를 넣는다. `indexLength` 도 같은 글로 센다
- `assembleForOwner` 와 `omittedFor` 의 동작은 바꾸지 않는다

### 4. `HermesRunEventStream` 의 `memory_read` 가드

`toRunEvent` 에서 도구 이름이 `memory_read` 로 끝나고 사건이 `tool.started` 가 아니면 `detail` 을 `null` 로 둔다. `tool.started` 는 지금처럼 `preview`(인자)를 쓴다.
Hermes 가 뒤에 `tool.completed` 에 `result` 를 싣기 시작해도 Memory 본문이 `execution_event.detail` 에 남지 않게 하려는 것이다.

### 5. 이 phase 를 검증하는 테스트

`backend/src/test/java/com/bifos/assistant/context/ContextAssemblerTest.java` 에 더한다.

| 입력 | 기대 |
| --- | --- |
| 그룹 항상 층 하나, 개인 항상 층 하나, 색인 둘(충돌 없음) | `instructions` 가 지금 형식으로 손으로 적은 기대 글과 같고 `instructionsHash()` 가 `Sha256.hex16(기대 글)` 과 같다 |
| 같은 입력 | 묶음이 `MEMORY_ALWAYS` 둘, `MEMORY_INDEX` 둘이고 순서가 글과 같다. 색인 항목은 `TITLE_ONLY` 이고 `body` 가 비어 있다 |
| 첫 항목이 상한을 넘는 기존 검사의 입력 | 넘친 항목이 `OMITTED` 로 묶음에 있고 `omittedItems()` 와 수가 같다 |
| 개인 문서와 그룹 문서가 같은 `collection` 과 `documentKey` 로 둘 다 색인에 오른다 | 두 색인 줄 끝에 서로를 가리키는 문구가 붙고 `conflictsWith` 가 서로의 `ref` 다 |
| 본문이 `평문-표식-7391` 인 항상 층 항목 | `assembler.assemble(...).toString()` 과 묶음과 항목의 `toString()` 에 표식이 없다 |

`backend/src/test/java/com/bifos/assistant/hermes/ToolDetailEventStreamTest.java` 에 더한다.

| 입력 | 기대 |
| --- | --- |
| `{"event":"tool.started","tool":"mcp__fos_assistant__memory_read","preview":"{\"id\": 12}"}` 와 `{"event":"tool.completed","tool":"mcp__fos_assistant__memory_read","result":"평문-표식-7391"}` | 첫 사건의 `detail` 은 인자이고 둘째 사건의 `detail` 은 `null` 이다 |
| 다른 도구의 `tool.completed` 의 `detail` | 지금처럼 가려 남는다 |

기존 테스트(`ChatServiceTest`, `ExecutionLifecycleTest`, `AgentRunnerSubmitFailureTest`, `AgentRunnerConnectorContextTest`)가 `AssembledContext` 생성자를 그대로 컴파일하는지 본다. 바꿔야 하면 변경 파일 표에 더한다.

## 검증

```bash
# cwd: backend/
./gradlew test --tests 'com.bifos.assistant.context.ContextAssemblerTest' --tests 'com.bifos.assistant.hermes.ToolDetailEventStreamTest' --tests 'com.bifos.assistant.architecture.*'
./gradlew test
./gradlew checkstyleMain checkstyleTest
```

```bash
# cwd: 저장소 root
node test/e2e/run.ts
```

기대값: 모두 종료 코드 0. e2e 의 Memory 시나리오(`test/e2e/scenarios/memory.ts`)가 실린 글을 그대로 받는다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/context/ContextSource.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/context/ContextTrust.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/context/ContextBodyMode.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/context/ContextFreshness.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/context/ContextItem.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/context/ContextBundle.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/context/AssembledContext.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/context/ContextAssembler.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/hermes/HermesRunEventStream.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/context/ContextAssemblerTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/hermes/ToolDetailEventStreamTest.java` | 수정 |
