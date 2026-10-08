# Phase 03. 답마다 참고한 기억 API

**Execution profile**: standard

## 목표

`GET /api/v1/chat/conversations/{conversationId}/memory-uses` 로 대화의 답마다 그 실행이 본문을 받은 기억(항상 층, 개인 사실 구역, `memory_read`)을 지금 볼 수 있는 것만 제목과 함께 낸다.

**범위 외**: 화면(phase 04). 실행 기록 표의 스키마 변경은 없다.

## 컨텍스트

- 계약은 `docs/backend/memory.md` 의 「답마다 참고한 기억」 이 갖는다. 재료 표, 응답 예시와 칸, 순서와 중복, 거르는 조건, 클래스 표가 거기 있다. 이름을 그대로 쓴다.
- 패키지 방향(`docs/backend/packages.md`): `chat`(12) 은 `memory`(10) 와 `usage`(8) 를 부를 수 있다. `usage` 는 `memory` 와 `context` 를 import 하지 못한다. 그래서 출처 이름은 `usage` 안에서 문자열(`"MEMORY_ALWAYS"`, `"MEMORY_FACTS"`, `"MEMORY_READ"`)로 비교한다. `presentation` 은 다른 패키지의 `infra` 를 쓰지 않는다(`ArchitectureRules.LAYER_DIRECTION`).
- 재사용
  - 대화 주인 확인: `chat.application.ConversationAccess.requireOwnId(user, conversationId)`. `MemoryCaptureController` 와 같은 방식이다.
  - 답 메시지: `chat.infra.ChatMessageRepository` 에 실행 번호만 읽는 조회 `findAssistantExecutionIds(Long conversationId)`(`@Query`, `role = ASSISTANT` 이고 `executionId` 가 있는 줄의 `executionId`, 번호 오름차순)를 더한다. 메시지 본문을 읽지 않는다. `@Query` 모양은 같은 파일의 `TurnTiming` 조회를 따른다.
  - 실은 항목과 읽은 항목: 모두 `execution_context_source` 에서 읽는다. `ExecutionContextSourceRepository` 에 `findByIdExecutionIdInAndSourceInAndBodyModeOrderByIdExecutionIdAscIdPositionAsc` 를 더해 `MEMORY_ALWAYS`, `MEMORY_FACTS`, `MEMORY_READ` 이고 `INLINE` 인 줄만 데이터베이스가 고른다. 엔티티 `ExecutionContextSource` 의 `executionId()`, `position()`, `source()`, `sourceRef()`, `bodyMode()`.
  - 읽은 항목을 남기는 길: Hermes 의 `tool.started` 사건 `preview` 는 주요 인자 하나뿐이고 `memory_read` 의 `id` 는 그 목록에 없다(`docs/hermes/runs-api.md` 의 「붙은 커넥터 서버의 도구 사건」). 그래서 `mcp.application.McpToolService.readMemory` 가 본문을 내 준 뒤(`bodyFor` 와 `contentOf` 성공 뒤)에만 `ExecutionContextSourceWriter.append(caller.executionId(), new ContextSourceRef("MEMORY_READ", "memory:" + id, "INLINE", "UNKNOWN"))` 를 부른다. `append` 는 새 트랜잭션에서 그 실행의 마지막 position + 1(`ExecutionContextSourceRepository.lastPosition`)에 저장하고, 기본 키가 겹치면 한 번 다시 읽어 시도한 뒤 실패하면 warn 로그만 남긴다. 겹침이 merge 로 덮이지 않고 드러나도록 `ExecutionContextSource` 가 `Persistable`(`isNew()` 참)을 구현한다(`MemoryRevision` 과 같은 방식). `context.ContextSource` 에 `MEMORY_READ` 를 더한다.
  - 볼 수 있는지: `memory.domain.Memory.isReadableBy(userId, groupId)`. `MemoryCaptureService.capturesOf` 가 같은 판정을 쓴다.
  - `memory_read` 가 읽을 수 있는 항목인지: `MemoryService.bodyFor` 의 조건(`ACCEPTED`, `retrieval()==SEARCH`, `entryType()!=SOURCE`, `access.allows(collection, sensitivity)`). `access` 는 `MemoryService.accessOf(agentId)`.
  - 실행의 에이전트: `usage.infra.AgentExecutionRepository.findAllById(ids)` 의 `agentId()`.
- 빈 `in` 절을 부르지 않는다. 답 실행이 없으면 바로 빈 목록이다(저장소 Javadoc 의 주의).

**근거 문서**: `docs/backend/memory.md` 의 「답마다 참고한 기억」, `docs/adr/ADR-20261008-memory-facts.md`, `docs/backend/packages.md`

## 의도 메모

- 제목만 실린 `MEMORY_INDEX` 는 넣지 않는다. 본문을 받지 않은 항목을 「참고했다」 고 보이지 않기 위해서다.
- `MEMORY_READ` 줄은 읽기에 성공했을 때만 남지만, 그 뒤 관리자가 collection 을 떼면 지금은 읽을 수 없다. 그래서 `READ` 는 그 실행의 에이전트가 지금 `memory_read` 로 읽을 수 있는 항목만 낸다.
- 실행 당시의 권한이 아니라 지금 권한으로 거른다. 지운 항목과 되돌린 항목이 계속 보이면 사용자가 「지웠는데 왜 남았나」 를 묻는다.
- 응답에 본문을 싣지 않는다. 민감 본문이 새는 길을 만들지 않는다.
- `MEMORY_READ` 저장이 실패해도 도구 결과를 바꾸지 않는다. 관측용 기록이다.

## 작업 항목

### 1. `usage.application.ExecutionMemoryRefs`, `ExecutionMemoryRef`, `model.MemoryUseVia` 와 읽기 기록

- `usage.application.model.MemoryUseVia` enum: `ALWAYS`, `FACTS`, `READ`(저장하지 않는 서비스 결과 enum 이라 `application.model` 에 둔다).
- `ExecutionMemoryRef` record: `Long executionId`, `Long agentId`(그 실행의 에이전트, 없으면 null), `Long memoryId`, `MemoryUseVia via`.
- `ExecutionMemoryRefs` `@Component`. `List<ExecutionMemoryRef> of(Collection<Long> executionIds)`. 비었으면 빈 목록.
  `findByIdExecutionIdInAndSourceInAndBodyModeOrderByIdExecutionIdAscIdPositionAsc` 로 고른 줄을 `MEMORY_ALWAYS`→`ALWAYS`, `MEMORY_FACTS`→`FACTS`, `MEMORY_READ`→`READ` 로 옮겨 `position` 순으로 실행마다 모은다. `source_ref` 가 `memory:<정수>` 가 아니면 건너뛴다. 같은 실행의 같은 번호는 처음 것만 남긴다. 실행 번호 오름차순으로 낸다. 에이전트 번호는 `AgentExecutionRepository.findAllById` 로 한 번에 읽는다.
- `ExecutionContextSourceRepository` 에 위 조회와 `lastPosition(executionId)` 를 더한다.
- `ExecutionContextSourceWriter.append(Long executionId, ContextSourceRef ref)` 와 `ExecutionContextSource` 의 `Persistable` 구현, `McpToolService.readMemory` 의 호출, `ContextSource.MEMORY_READ`, `memory_read` 도구 설명(「번호는 지시문의 개인 사실 구역이나 색인에 있다」)은 「컨텍스트」 의 「읽은 항목을 남기는 길」 대로 한다.

### 2. `memory.application.AcceptedMemoryLookup.acceptedReadableAmong` 과 `MemoryService.readableByTool`

`MemoryService` 가 파일 길이 상한(500줄)에 걸리므로 새 `@Service` `AcceptedMemoryLookup` 에 둔다. 같은 까닭으로 `MemoryService.proposalFeedback` 를 `MemoryProposalFeedback.of` 로 옮기는 이동만 한 커밋을 먼저 둔다(호출하는 `MemoryCaptureService` 두 곳도 고친다). `Map<Long, Memory> acceptedReadableAmong(CurrentUser user, Collection<Long> ids)`. 비었으면 빈 맵. `findAllById` 뒤 `isReadableBy(user.id(), user.groupId())` 이고 `status()==MemoryStatus.ACCEPTED` 인 것만.
`boolean readableByTool(Memory memory, MemoryAccess access)`: `bodyFor` 의 거르는 조건(요청자 판정 제외)과 같다. `bodyFor` 가 이 함수를 함께 쓰게 고쳐 두 판정이 갈라지지 않게 한다. 에이전트 번호에서 `MemoryAccess` 를 얻는 일은 부르는 쪽이 `accessOf(agentId)` 로 한다.

### 3. `chat.application.MemoryUseService` 와 `chat.application.MemoryUse`

- `MemoryUse` record: `Long executionId`, `Long memoryId`, `String title`, `MemoryScope scope`, `MemoryUseVia via`.
- `List<MemoryUse> usesOf(CurrentUser user, Long conversationId)`: `findAssistantExecutionIds` 로 답 실행 번호를 모아 `ExecutionMemoryRefs.of`, 그 번호들로 `acceptedReadableAmong`, 맵에 있는 것만 지금 제목과 범위로 낸다. `READ` 는 `readableByTool(memory, access)` 도 참이어야 한다. `access` 는 실행의 에이전트 번호마다 `accessOf(agentId)` 를 한 번만 불러 모아 둔다(에이전트와 grant 를 읽는 조회다). 에이전트 번호가 없으면 `READ` 를 내지 않는다. `@Transactional(readOnly = true)`.

### 4. `chat.presentation.MemoryUseController` 와 `ChatDtos.MemoryUseView`

- `@GetMapping("/chat/conversations/{conversationId}/memory-uses")`, `@RequestMapping("/api/v1")`. `UUID conversationId` 를 `ConversationAccess.requireOwnId` 로 바꿔 `usesOf` 를 부른다. 클래스 Javadoc 에 ADR-20261008 / memory-facts 을 적는다.
- `ChatDtos.MemoryUseView(Long executionId, Long memoryId, String title, String scope, String via)` 와 `static from(MemoryUse)`. 여기서만 `via().name()` 으로 바꾼다. `MemoryCaptureView` 옆에 둔다.

### 5. `backend/src/test/java/com/bifos/assistant/chat/MemoryUseServiceTest.java`

`@BackendIntegrationTest`. 준비는 `McpMemoryRememberToolTest`(대화 저장)와 `usage/ExecutionContextSourceTest`(실은 항목 저장)를 따른다. 실행 줄과 답 메시지, `execution_context_source` 줄(`MEMORY_READ` 포함)을 직접 넣는다.

- 한 답에 `MEMORY_FACTS` 항목, `MEMORY_ALWAYS` 그룹 항목, `MEMORY_INDEX` 항목, `OMITTED` 항목, `MEMORY_READ` 줄이 있을 때 `FACTS`, `ALWAYS`, `READ` 셋만 순서대로 나온다.
- 같은 항목이 실렸고 읽혔으면 하나만 나온다.
- 지운 항목, 다른 사용자의 개인 항목, `PROPOSED` 로 돌아간 항목은 빠진다. 제목을 고치면 고친 제목이 나온다.
- `INLINE` 이 아닌 줄과 `memory:<정수>` 가 아닌 ref 는 건너뛴다.
- 답이 없는 대화는 빈 목록이다.
- `READ` 이지만 그 에이전트가 받지 않는 collection 의 항목, 민감 허용 없는 `SENSITIVE` 항목, `ALWAYS` 항목은 빠진다.

### 6. 읽기 기록 시험

- `backend/src/test/java/com/bifos/assistant/mcp/McpMemoryToolTest.java`: 읽기에 성공하면 그 실행의 조립 줄 뒤 position 에 `MEMORY_READ`, `INLINE`, `UNKNOWN` 줄이 붙고, 읽지 못한 호출은 줄을 남기지 않는다. 두 번 읽으면 position 이 이어진다.
- `backend/src/test/java/com/bifos/assistant/usage/ExecutionContextSourceTest.java`: position 이 겹치면 다시 읽어 그 뒤에 붙고, 계속 겹치면 던지지 않고 줄도 남기지 않는다.
- `backend/src/test/java/com/bifos/assistant/mcp/application/McpToolServiceTest.java`: 생성자 인자를 맞춘다.

### 7. `backend/src/test/java/com/bifos/assistant/chat/MemoryUseControllerTest.java`

`@BackendIntegrationTest`. `AgentStarterControllerTest` 의 MockMvc 방식을 따른다. 주인은 200 과 JSON 배열(칸 `executionId`, `memoryId`, `title`, `scope`, `via`)을, 다른 사용자는 다른 대화 경로와 같은 404 를 받는다.

## 검증

```bash
cd backend && ./gradlew test --tests '*MemoryUseServiceTest' --tests '*MemoryUseControllerTest' --tests '*McpMemoryToolTest' --tests '*McpToolServiceTest' --tests '*ArchitectureRulesTest' --tests '*ExecutionContextSourceTest'
cd backend && ./gradlew spotlessCheck checkstyleMain checkstyleTest
```

기대값: 모두 통과. 구조 규칙 검사가 새 패키지 의존을 받아들인다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/usage/application/ExecutionMemoryRef.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/usage/application/ExecutionMemoryRefs.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/usage/application/model/MemoryUseVia.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/usage/application/ExecutionContextSourceWriter.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/usage/domain/ExecutionContextSource.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/usage/infra/ExecutionContextSourceRepository.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/mcp/application/McpToolService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/context/ContextSource.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/mcp/McpMemoryToolTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/mcp/application/McpToolServiceTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/usage/ExecutionContextSourceTest.java` | 수정 |
| `docs/backend/context-bundle.md` | 수정 |
| `docs/backend/schema/execution.md` | 수정 |
| `docs/adr/ADR-20261008-memory-facts.md` | 수정 |
| `docs/backend/memory-eval.md` | 수정. `MEMORY_READ` 집계 문장과, 합성 측정의 진행 순서와 `filler` 설명을 실제에 맞춤 |
| `docs/adr/ADR-015-memory-는-층을-나눠-싣는다.md` | 수정 |
| `docs/adr/ADR-071-여러-출처의-문맥은-항목마다-출처와-권한과-신선도를-지닌-묶음으로-조립한다.md` | 수정 |
| `backend/src/main/java/com/bifos/assistant/memory/application/MemoryService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/memory/application/AcceptedMemoryLookup.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/memory/application/MemoryProposalFeedback.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/memory/application/MemoryCaptureService.java` | 수정 |
| `docs/backend/memory.md` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/application/MemoryUse.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/application/MemoryUseService.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/presentation/MemoryUseController.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/presentation/ChatDtos.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/MemoryUseServiceTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/chat/MemoryUseControllerTest.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/infra/ChatMessageRepository.java` | 수정 |
