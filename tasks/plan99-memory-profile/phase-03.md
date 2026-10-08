# Phase 03. 답마다 참고한 기억 API

**Execution profile**: standard

## 목표

`GET /api/v1/chat/conversations/{conversationId}/memory-uses` 로 대화의 답마다 그 실행이 본문을 받은 기억(항상 층, 프로필 구역, `memory_read`)을 지금 볼 수 있는 것만 제목과 함께 낸다.

**범위 외**: 화면(phase 04). 실행 기록 표의 스키마 변경은 없다.

## 컨텍스트

- 계약은 `docs/backend/memory.md` 의 「답마다 참고한 기억」 이 갖는다. 재료 표, 응답 예시와 칸, 순서와 중복, 거르는 조건, 클래스 표가 거기 있다. 이름을 그대로 쓴다.
- 패키지 방향(`docs/backend/packages.md`): `chat`(12) 은 `memory`(10) 와 `usage`(8) 를 부를 수 있다. `usage` 는 `memory` 와 `context` 를 import 하지 못한다. 그래서 출처 이름은 `usage` 안에서 문자열(`"MEMORY_ALWAYS"`, `"MEMORY_PROFILE"`)로 비교한다. `presentation` 은 다른 패키지의 `infra` 를 쓰지 않는다(`ArchitectureRules.LAYER_DIRECTION`).
- 재사용
  - 대화 주인 확인: `chat.application.ConversationAccess.requireOwnId(user, conversationId)`. `MemoryCaptureController` 와 같은 방식이다.
  - 답 메시지: `chat.infra.ChatMessageRepository.findByConversationIdOrderByIdAsc(conversationId)` 에서 `role()==MessageRole.ASSISTANT` 이고 `executionId()` 가 있는 줄.
  - 실은 항목: `usage.infra.ExecutionContextSourceRepository.findByIdExecutionIdInOrderByIdExecutionIdAscIdPositionAsc(Collection<Long>)`. 엔티티 `ExecutionContextSource` 의 `executionId()`, `position()`, `source()`, `sourceRef()`, `bodyMode()`.
  - 읽은 항목: `usage.infra.ExecutionEventRepository`. 지금 있는 `findByExecutionIdInOrderByExecutionIdAscSequenceAsc` 는 모든 사건을 읽으므로 `findByExecutionIdInAndEventTypeOrderByExecutionIdAscSequenceAsc(Collection<Long>, ExecutionEventType)` 를 더해 `ExecutionEventType.TOOL_STARTED` 만 읽는다. `toolName()` 이 `memory_read` 로 끝나고 `detail()` 이 `{"id":<정수>}` 꼴인 줄만 쓴다(`HermesRunEventStream` 이 시작 사건의 인자를 남기고 `ToolDetailRedactor` 가 다시 직렬화한다. `ToolDetailEventStreamTest` 가 `{"id":12}` 를 확인한다).
  - 볼 수 있는지: `memory.domain.Memory.isReadableBy(userId, groupId)`. `MemoryCaptureService.capturesOf` 가 같은 판정을 쓴다.
- 빈 `in` 절을 부르지 않는다. 답 실행이 없으면 바로 빈 목록이다(저장소 Javadoc 의 주의).

**근거 문서**: `docs/backend/memory.md` 의 「답마다 참고한 기억」, `docs/adr/ADR-20261008-memory-profile.md`, `docs/backend/packages.md`

## 의도 메모

- 제목만 실린 `MEMORY_INDEX` 는 넣지 않는다. 본문을 받지 않은 항목을 「참고했다」 고 보이지 않기 위해서다.
- 실행 당시의 권한이 아니라 지금 권한으로 거른다. 지운 항목과 되돌린 항목이 계속 보이면 사용자가 「지웠는데 왜 남았나」 를 묻는다.
- 응답에 본문을 싣지 않는다. 민감 본문이 새는 길을 만들지 않는다.
- `detail` 을 읽지 못하는 사건은 조용히 건너뛴다. 관측용 기록이라 실패로 다루지 않는다.

## 작업 항목

### 1. `usage.application.ExecutionMemoryRefs` 와 `usage.application.ExecutionMemoryRef`

- `ExecutionMemoryRef` record: `Long executionId`, `Long memoryId`, `String via`(`ALWAYS`, `PROFILE`, `READ`).
- `ExecutionMemoryRefs` `@Component`. `List<ExecutionMemoryRef> of(Collection<Long> executionIds)`. 비었으면 빈 목록.
  실은 항목(`source` 가 `MEMORY_ALWAYS` 면 `ALWAYS`, `MEMORY_PROFILE` 이면 `PROFILE`, `body_mode` 가 `INLINE` 인 것만, `source_ref` 는 `memory:<정수>`)을 `position` 순으로, 이어서 읽은 항목을 `sequence` 순으로 실행마다 모은다. 같은 실행의 같은 번호는 처음 것만 남긴다. 실행 번호 오름차순으로 낸다.
  `detail` JSON 은 Jackson `ObjectMapper` 로 읽고, `id` 가 정수가 아니면 건너뛴다.
- `ExecutionEventRepository` 에 위 조회 메서드를 더한다.

### 2. `memory.application.MemoryService.acceptedReadableAmong`

`Map<Long, Memory> acceptedReadableAmong(CurrentUser user, Collection<Long> ids)`. 비었으면 빈 맵. `findAllById` 뒤 `isReadableBy(user.id(), user.groupId())` 이고 `status()==MemoryStatus.ACCEPTED` 인 것만.

### 3. `chat.application.MemoryUseService` 와 `chat.application.MemoryUse`

- `MemoryUse` record: `Long executionId`, `Long memoryId`, `String title`, `MemoryScope scope`, `String via`.
- `List<MemoryUse> usesOf(CurrentUser user, Long conversationId)`: 답 실행 번호를 모아 `ExecutionMemoryRefs.of`, 그 번호들로 `acceptedReadableAmong`, 맵에 있는 것만 지금 제목과 범위로 낸다. `@Transactional(readOnly = true)`.

### 4. `chat.presentation.MemoryUseController` 와 `ChatDtos.MemoryUseView`

- `@GetMapping("/chat/conversations/{conversationId}/memory-uses")`, `@RequestMapping("/api/v1")`. `UUID conversationId` 를 `ConversationAccess.requireOwnId` 로 바꿔 `usesOf` 를 부른다. 클래스 Javadoc 에 ADR-20261008 / memory-profile 을 적는다.
- `ChatDtos.MemoryUseView(Long executionId, Long memoryId, String title, String scope, String via)` 와 `static from(MemoryUse)`. `MemoryCaptureView` 옆에 둔다.

### 5. `backend/src/test/java/com/bifos/assistant/chat/MemoryUseServiceTest.java`

`@BackendIntegrationTest`. 준비는 `McpMemoryRememberToolTest`(대화 저장)와 `usage/ExecutionContextSourceTest`(실은 항목 저장)를 따른다. 실행 줄과 답 메시지, `execution_context_source` 줄, `execution_event` 줄을 직접 넣는다.

- 한 답에 `MEMORY_PROFILE` 항목, `MEMORY_ALWAYS` 그룹 항목, `MEMORY_INDEX` 항목, `OMITTED` 항목, `memory_read` 시작 사건(`{"id":<n>}`)이 있을 때 `PROFILE`, `ALWAYS`, `READ` 셋만 순서대로 나온다.
- 같은 항목이 실렸고 읽혔으면 하나만 나온다.
- 지운 항목, 다른 사용자의 개인 항목, `PROPOSED` 로 돌아간 항목은 빠진다. 제목을 고치면 고친 제목이 나온다.
- `detail` 이 비었거나 `{"id":"x"}` 인 사건은 건너뛴다.
- 답이 없는 대화는 빈 목록이다.
- 다른 사용자의 대화는 `ConversationAccess.requireOwnId` 가 기존 대화 경로와 같은 예외를 던진다.

## 검증

```bash
cd backend && ./gradlew test --tests '*MemoryUseServiceTest' --tests '*ArchitectureRulesTest' --tests '*ExecutionContextSourceTest'
cd backend && ./gradlew spotlessCheck checkstyleMain checkstyleTest
```

기대값: 모두 통과. 구조 규칙 검사가 새 패키지 의존을 받아들인다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/usage/application/ExecutionMemoryRef.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/usage/application/ExecutionMemoryRefs.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/usage/infra/ExecutionEventRepository.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/memory/application/MemoryService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/application/MemoryUse.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/application/MemoryUseService.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/presentation/MemoryUseController.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/presentation/ChatDtos.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/MemoryUseServiceTest.java` | 신규 |
