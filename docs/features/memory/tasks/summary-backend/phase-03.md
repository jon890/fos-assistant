# Phase 03. memory_remember 의 요약과 합치기

**Execution profile**: standard

## 목표

비서가 기억을 남기고 고칠 때 요약 줄을 함께 쓰고, 겹치는 옛 기억을 새 기억 하나로 합친다. 대화의 기록 줄이 합친 옛 제목을 보인다.

**범위 외**: 요약 구역 조립(phase 02), 이관(phase 04), `/memory` 화면(PR2).

## 컨텍스트

**요구사항**: REQ5, REQ6

**근거 문서**: `docs/features/memory/spec.md`, `docs/features/memory/design.md` 의 「`memory_remember`(PR1)」, 「대화의 기록 줄(PR1)」, 「지침(PR1)」, `docs/features/memory.md` 의 「바로 저장 판정」, 「기억의 대체」

- 인자 경로: `mcp/presentation/McpDtos.java` 의 `MemoryRememberArguments.FIELDS`, `McpController.remember`, `mcp/application/McpToolService.remember`, `McpMemoryRemember.remember`, `memory/application/dto/MemoryRememberRequest`
- 판정: 조건 1~5 는 `McpMemoryRemember.directAllowed`, 6·7 은 `MemoryCaptureService.remember` 와 `update`, `MemorySensitiveHints.suspected`
- 되돌리기: `MemoryCaptureService.undo`, `removeCreated`, `restore`. 대체 칸 비우기 `MemoryRepository.clearSupersededBy`
- 받아들이기: `MemoryService.accept`(화면 `MemoryController.accept` 와 `existingOutcome` 이 부른다)
- 기록 목록: `MemoryCaptureController`, `CapturedMemory`, 화면 `web/src/components/chat/memory-*`, `web/src/lib/memory-capture-api.ts`

## 의도 메모

- 대체 칸 매핑은 읽기 전용 그대로 둔다. 쓰기는 조건부 네이티브 UPDATE 하나다(설계 DECISION9)
- 대신할 수 없는 번호의 까닭을 결과 글에 쓰지 않는다. 볼 수 없는 기억과 없는 기억을 같은 응답으로 답한다
- `existingOutcome` 의 「기존 제안을 받아들임」 분기는 저장된 요약에 조건 7 을 다시 적용한다

## 작업 항목

### 1. 인자

`summary`(문자열, 200자까지)와 `replaces`(정수 배열, 1개부터 10개까지, 중복 없음)를 도구 정의와 `FIELDS`, 위치 인자 전달, `MemoryRememberRequest` 에 더한다. `replaces` 와 `memory_id` 를 함께 주면 `McpController` 가 `-32602` 로 거절한다. 도구 설명(`MEMORY_REMEMBER_DESCRIPTION`)에 요약과 합치기를 더한다.

### 2. 판정과 저장

- 조건 3 은 본문과, 요약이 빈 글이 아니면 요약도 질문 원문과 대조한다. 조건 7 은 제목, 본문, 요약을 본다
- 새 기억은 요약을 함께 저장한다. 빠지면 빈 글이다
- `memory_id` 수정: 요약을 안 주면 지금 값, 빈 글이면 지운다. 본문과 요약이 모두 같으면 「이미 안다」
- `restore` 는 이전 revision 의 요약도 되돌린다(`NULL` 이면 빈 글)
- 화면 수정(`MemoryService.update` 의 `PATCH` 경로): 본문이 바뀌고 지금 요약이 옛 본문과 같으면 요약을 새 본문으로, 새 본문이 200자를 넘으면 빈 글로 바꾼다(설계 DECISION8)
- `existingOutcome` 의 `PROPOSED` + 바로 저장 분기: 저장된 요약에 `MemorySensitiveHints.suspected` 가 걸리면 `ALREADY_PROPOSED` 로 둔다

### 3. 합치기

- `MemoryRepository` 에 조건부 네이티브 UPDATE `supersede(newId, ownerUserId, ids, collections)` 를 더한다(설계 「`memory_remember`(PR1)」 의 조건). 번호는 정렬해 넘기고, 실제로 바뀐 번호를 같은 트랜잭션에서 다시 읽는다. 풀리지 않는 기억은 쓰기 전에 잠그지 않고 읽어 제외한다
- 바로 저장이면 새 기억 저장 뒤 같은 트랜잭션에서 UPDATE 하고, 실제로 대체한 번호를 기록의 `replaced_memory_ids` 에 둔다
- 제안이면 판정 전 번호(요청자 소유이고 위 조건을 지금 만족하는 번호)를 기록에 둔다. `MemoryService.accept` 가 그 기억의 `PROPOSED` 기록을 찾아 collection 조건 없이 UPDATE 하고, 실제로 대체한 번호로 기록을 고친다
- 결과 글: 대체한 수와, 대신하지 못한 번호가 있으면 「대신하지 못함: 번호 목록」 만 덧붙인다

### 4. 기록 목록과 화면

`CapturedMemory` 와 응답 DTO 에 `replacedTitles` 를 더한다(지금 요청자가 볼 수 있고 풀리는 기억의 지금 제목). 대화의 기록 줄과 제안 카드가 「n개를 합쳤어요」 와 제목 목록을 평문으로 보인다.

### 5. 기억 지침

`ContextAssembler.MEMORY_INSTRUCTIONS` 에 요약을 함께 쓰는 것, 진행 중인 일은 요약 없이 `archivable` 을 참으로 남기는 것, 기억을 고칠 때 `summary` 도 함께 주는 것, 정리 부탁이면 같은 주제를 합쳐 `replaces` 에 옛 번호를 주는 것을 더한다.

### 6. 테스트

- `McpMemoryRememberToolTest`: 요약 바로 저장, 바깥 글을 읽은 대화의 요약은 제안, 요약에만 민감한 글이면 제안, 요약에만 부정 표지가 어긋나면 제안, 요약 없이 질문에 부정이 있어도 본문이 맞으면 바로 저장, `replaces` 와 `memory_id` 동시 거절, 모르는 인자 거절
- `MemoryCaptureService` 쪽 테스트(`MemoryServiceTest` 나 새 `MemoryMergeTest`): 합치기 바로 저장 뒤 옛 기억이 조립·검색·`memory_read` 에서 빠지고 목록에 남음, 되돌리기 뒤 옛 기억이 다시 조립에 듦, 제안 합치기는 받아들이기 전 아무것도 안 바뀌고 받아들이면 대체됨, 남의 기억·그룹 기억·없는 번호는 건너뛰고 새 기억은 저장, 요약 유지와 지우기, `restore` 의 요약 되돌리기, 화면 수정의 요약 따라가기
- 기록 목록 API 의 `replacedTitles`, 화면 컴포넌트 테스트(`web` 의 기존 기록 줄 테스트 자리)

## 검증

```bash
cd backend && HEAVY_SLOTS=2 orch slot heavy -- ./gradlew test --tests '*McpMemoryRemember*' --tests '*MemoryCapture*' --tests '*MemoryMerge*' --tests '*MemoryServiceTest' --tests '*McpController*'
cd backend && ./gradlew checkstyleMain checkstyleTest spotlessCheck
cd web && pnpm vitest run src/components/chat
cd web && pnpm lint && pnpm typecheck
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/mcp/presentation/McpDtos.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/mcp/presentation/McpController.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/mcp/application/McpToolService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/mcp/application/McpMemoryRemember.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/memory/application/dto/MemoryRememberRequest.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/memory/application/MemoryCaptureService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/memory/application/MemoryService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/memory/infra/MemoryRepository.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/turn/presentation/MemoryCaptureController.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/context/application/ContextAssembler.java` | 수정 (지침) |
| `backend/src/test/java/com/bifos/assistant/mcp/McpMemoryRememberToolTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/memory/MemoryMergeTest.java` | 신규 |
| `web/src/lib/memory-capture-api.ts` | 수정 |
| `web/src/components/chat/memory-capture-list.tsx` | 수정 |
