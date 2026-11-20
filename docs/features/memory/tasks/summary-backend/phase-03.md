# Phase 03. memory_remember 의 요약과 고치기 경로

**Execution profile**: deep

## 목표

비서가 기억을 남기고 고칠 때 요약 줄을 함께 쓴다. 되돌리기와 화면 수정이 요약을 맞게 다룬다. 기억 지침과 도구 설명이 요약과 상세 꺼내기를 안내한다.

**범위 외**: 합치기 `replaces`(PR1b), 요약 이관(phase 04), `/memory` 화면의 요약 칸(PR2).

## 컨텍스트

**요구사항**: REQ5, REQ7(지침)

**근거 문서**: `docs/features/memory/spec.md`, `docs/features/memory/design.md` 의 「`memory_remember`(PR1)」, 「지침(PR1)」, `docs/features/memory.md` 의 「바로 저장 판정」

- 인자 경로: `mcp/presentation/McpDtos.java` 의 `MemoryRememberArguments.FIELDS`, `mcp/presentation/McpController.remember`, `mcp/application/McpToolService.remember`, `mcp/application/McpMemoryRemember.remember`, `memory/application/dto/MemoryRememberRequest`
- 판정: 조건 1~5 는 `McpMemoryRemember.directAllowed`, 6·7 은 `MemoryCaptureService.remember`, `update`, `existingOutcome` 과 `MemorySensitiveHints.suspected`
- 되돌리기: `MemoryCaptureService.restore`. 화면 수정: `MemoryService.update(user, id, content, alwaysInject, archivable)`(`MemoryController` 의 `PATCH`)
- 지침: `context/application/ContextAssembler.MEMORY_INSTRUCTIONS`, 도구 설명 `McpMemoryRemember.MEMORY_REMEMBER_DESCRIPTION`

## 의도 메모

- `MemoryRememberRequest` 에 `summary`(null 은 「주지 않음」)를 더한다. 이 record 를 직접 만드는 테스트(`MemoryEncryptionTest`, `MemoryCaptureConcurrencyTest`)도 고친다
- 기존 제안을 받아들이는 분기는 저장된 요약에도 민감 검사를 다시 한다

## 작업 항목

### 1. 인자

`summary`(문자열, 200자까지)를 도구 정의 `inputSchema`, `FIELDS`, `McpController` 의 인자 읽기, `McpToolService.remember`, `McpMemoryRemember.remember` 에 더한다. 200자를 넘으면 지금 다른 인자처럼 결과 글로 거절한다.

### 2. 판정과 저장

- 조건 3: `directAllowed` 가 본문과, 요약이 빈 글이 아니면 요약도 질문 원문과 부정 표지를 대조한다
- 조건 7: `MemorySensitiveHints.suspected(title, content, summary)` 오버로드를 더해 세 자리에서 쓴다
- 새 기억은 `Memory.remembered`, `Memory.proposed` 의 요약 오버로드로 만든다. 주지 않으면 빈 글이다
- `update`(memory_id): 요약을 안 주면 지금 값, 빈 글이면 지운다. 본문과 요약이 모두 같으면 `ALREADY_KNOWN`. 바꾸면 `MemoryService.revise` 의 요약 오버로드로 고친다
- `existingOutcome` 의 `PROPOSED` + 바로 저장 분기: 저장된 요약에 민감 검사가 걸리면 `ALREADY_PROPOSED` 로 둔다
- `restore`: 이전 revision 의 본문과 요약(`summary()`, `NULL` 이면 빈 글)을 함께 되돌린다
- 화면 수정 `MemoryService.update(user, id, content, alwaysInject, archivable)`: 본문이 바뀌고 지금 요약이 옛 본문과 같으면 요약을 새 본문으로, 새 본문이 200자를 넘으면 빈 글로 바꾼다. 다르면 그대로 둔다

### 3. 지침

- `MEMORY_INSTRUCTIONS` 에 더한다: 남길 때 한 줄 요약(`summary`, 200자까지)을 함께 쓴다. 금방 바뀌는 진행 중인 일(지원 현황, 예약)은 요약 없이 `archivable` 을 참으로 남긴다. 기억을 고칠 때는 `summary` 도 새 사실에 맞게 준다
- `MEMORY_REMEMBER_DESCRIPTION` 에 `summary` 의 뜻을 더한다

### 4. 테스트

- `McpMemoryRememberToolTest`: 요약과 함께 바로 저장하면 다음 조립의 요약 구역에 있다, 바깥 글을 읽은 대화의 요약은 제안이고 받아들이기 전 요약 구역에 없다, 요약에만 민감한 글이면 제안, 요약에만 부정 표지가 어긋나면 제안, 요약 없이 질문과 본문의 부정이 맞으면 바로 저장, 201자 요약 거절, `memory_id` 로 상세만 고치면 요약 유지, 빈 글을 주면 요약 지움, 같은 본문에 다른 요약이면 요약만 바뀐 revision, 기존 제안의 요약이 민감하면 바로 저장 호출이 받아들이지 않음
- `MemoryServiceTest`: 화면 수정에서 요약 = 옛 본문이면 따라감, 새 본문이 200자를 넘으면 비움, 요약이 달랐으면 그대로
- `MemoryEncryptionTest` 나 `MemoryServiceTest`: 되돌리기가 요약도 되돌린다

## 검증

```bash
cd backend && HEAVY_SLOTS=2 orch slot heavy -- ./gradlew test --tests '*McpMemoryRememberToolTest' --tests '*MemoryCapture*' --tests '*MemoryServiceTest' --tests '*MemoryEncryptionTest' --tests '*McpMemorySearchToolTest'
cd backend && ./gradlew checkstyleMain checkstyleTest spotlessCheck
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
| `backend/src/main/java/com/bifos/assistant/memory/application/MemorySensitiveHints.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/memory/application/MemoryService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/context/application/ContextAssembler.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/mcp/McpMemoryRememberToolTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/memory/MemoryServiceTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/memory/MemoryEncryptionTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/memory/MemoryCaptureConcurrencyTest.java` | 수정 |
