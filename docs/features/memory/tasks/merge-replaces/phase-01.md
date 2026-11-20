# Phase 01. 백엔드 합치기

**Execution profile**: standard

## 목표

`memory_remember` 가 `replaces`(대신할 옛 기억 번호)를 받아, 새 기억이 저장되면 옛 기억의 대체 칸을 채우고 제안이면 받아들일 때 채운다. 기록 목록 응답이 대신한 옛 제목을 낸다.

**범위 외**: 화면(phase 02), `/memory` 목록의 대체 정보(PR2).

## 컨텍스트

**요구사항**: REQ6

**근거 문서**: `docs/features/memory/spec.md`(REQ6, DECISION8, DECISION24, DECISION27), `docs/features/memory/design.md`(「`memory_remember`(PR1)」, 「대화의 기록 줄(PR1)」, 「지침(PR1)」, 설계 DECISION5, DECISION9)

- 옛 기억을 빼는 읽기 경로는 이미 `superseded_by_memory_id` 를 본다: `MemoryQueries`(검색), `MemoryRepository.countLoadableByCollection`, `AcceptedMemoryLookup`(`memory_read`), `ContextAssembler` 후보, `MemoryEmbeddingRepository`. 이 phase 는 칸을 쓰는 경로만 만든다.
- 칸을 비우는 경로도 이미 있다: `MemoryRepository.clearSupersededBy` 를 `MemoryRevisionWriter.beforeDelete`(삭제), `MemoryService.reject`(거절), `MemoryCaptureService.removeCreated`(제안으로 되돌림)가 부른다. 되돌리기와 삭제가 옛 기억을 살리는 것은 이 경로다.
- `Memory.supersededByMemoryId` 매핑은 읽기 전용(`insertable = false, updatable = false`) 그대로 둔다.

## 의도 메모

- 대체 쓰기는 조건부 네이티브 UPDATE 하나다(설계 DECISION9). 엔티티를 잠그지 않는다.
- 풀리는지(`readable`)는 UPDATE 전에 `findAllById` 로 읽어 제외한다. SQL 조건으로 풀림을 볼 수 없어서다.
- 제안을 받아들이는 경로(`MemoryService.accept`)에는 에이전트 collection 범위가 없어 collection 조건을 뺀다.
- 기존 제안을 받아들이는 `existingOutcome` 분기는 이번 호출의 `replaces` 를 쓰지 않는다. 그 제안의 기록이 가진 번호를 `accept` 가 쓴다.

## 작업 항목

### 1. 마이그레이션 `backend/src/main/resources/db/migration/V20261105074430__memory_capture_replaced_ids.sql`

`ALTER TABLE memory_capture ADD COLUMN replaced_memory_ids VARCHAR(512) NULL COMMENT ...`. DDL 만.

### 2. `MemoryCapture` 엔티티

`replacedMemoryIds`(`List<Long>`, 칸 `replaced_memory_ids`, JSON 배열 텍스트) 와 converter `memory/domain/ReplacedMemoryIdsConverter`(빈 목록과 null 은 `NULL`). `of(...)` 에 인자를 더하고 `replaced(List<Long>)` 로 받아들일 때 고친다.

### 3. `MemoryRepository`

- `supersede(newId, ids, userId)`: 주인·범위·종류·상태·대체 안 됨·`id <> newId` 조건 UPDATE
- `supersedeWithin(newId, ids, userId, collections)`: 위에 `collection IN (:collections)` 를 더한 것
- `findIdsSupersededBy(newId, ids)`: 같은 트랜잭션에서 실제 대신한 번호를 다시 읽는다

### 4. `MemoryCaptureRepository.findFirstByMemoryIdAndKindOrderByIdDesc`

받아들일 제안의 `PROPOSED` 기록을 찾는다.

### 5. `MemoryReplacements`(새 서비스, `memory/application`)

- `replaceable(user, access, ids, newId)`: 요청자의 `USER`, `MEMORY`, `ACCEPTED`, 풀림, 대체 안 됨, `access.allows(collection)`, 새 기억 자신이 아님. 정렬한 번호 목록
- `supersede(user, access, newId, ids)`: `replaceable` 로 고른 뒤 UPDATE 하고 실제 대신한 번호를 낸다. `access.unrestricted()` 면 `supersede`, 아니면 `supersedeWithin`
- `onProposalAccepted(user, memoryId)`: 그 제안의 `PROPOSED` 기록 번호로 `supersede(MemoryAccess.owner())` 하고 기록을 실제 번호로 고친다. `MemoryService` 가 `MemoryCaptureService` 를 참조하면 순환이라 판정을 이 서비스에 둔다(설계의 `MemoryCaptureService.replaceable` 자리를 바꿈, phase 02 에서 design.md 를 고친다)
- `titlesOf(user, ids)`: 요청자가 읽을 수 있고 풀리는 기억의 지금 제목

### 6. `MemoryCaptureService.remember`, `MemoryService.accept`, `capturesOf`

- `MemoryRememberRequest` 에 `replaces`(`List<Long>`, 없으면 빈 목록)
- `MemoryRememberResult` 에 `replaced`, `notReplaced`
- 바로 저장: 저장 뒤 `supersede`, 실제 번호를 기록에 둔다. 제안: `replaceable` 의 번호를 기록에 둔다
- `MemoryService.accept`: 제안이었으면 `MemoryReplacements.onProposalAccepted` 를 부른다. `MemoryServiceGuardTest` 의 생성자 인자를 함께 고친다
- `capturesOf`: `CapturedMemory` 에 `replacedTitles`

### 7. MCP 인자

- `McpDtos.MemoryRememberArguments` 에 `replaces`, `FIELDS` 에 `"replaces"`
- `McpController.remember`: 정수 배열 1~10개, 중복 없음, `memory_id` 와 함께면 `-32602`
- `McpToolService.remember`, `McpMemoryRemember.remember`: 인자 전달, 입력 스키마, 결과 글(바로 저장 「옛 기억(번호 …)을 대신했다」, 제안 「…을 받아들이면 대신한다」, 둘 다 「대신하지 못함: 번호」)

### 8. 지침

`MEMORY_REMEMBER_DESCRIPTION` 과 `ContextAssembler.MEMORY_INSTRUCTIONS` 에 「사용자가 기억 정리를 부탁하면 같은 주제의 기억을 하나로 합쳐 replaces 에 옛 번호를 준다」.

### 9. 기록 목록 응답

`ChatDtos.MemoryCaptureView.replacedTitles`.

### 10. 테스트 `backend/src/test/java/com/bifos/assistant/mcp/McpMemoryReplacesToolTest.java`

- 바로 저장 합치기: 옛 기억이 대체되고 다음 조립(요약 구역, 색인), `memory_read`, 검색에서 빠지며 목록(`GET /api/v1/memories`)에는 남는다
- 대신할 수 없는 번호(남의 기억, 그룹 기억, 없는 번호, 받지 않는 collection, 제안 상태, 이미 대체된 기억, 풀리지 않는 기억)가 섞이면 새 기억은 저장되고 그 번호만 건너뛰며 결과 글이 「대신하지 못함」 이다
- 제안 합치기: 받아들이기 전에는 옛 기억이 그대로이고, 화면 경로로 받아들이면 대체된다. 그 사이 옛 기억 하나를 지웠으면 남은 번호만 대체되고 기록의 `replacedTitles` 에서 빠진다
- 같은 제안을 바로 저장 조건에서 다시 받는 경로(`existingOutcome`)도 기록의 번호로 대체하고 이번 호출의 `replaces` 는 쓰지 않는다
- 되돌리기 뒤 옛 기억의 칸이 비어 다음 실행 조립에 다시 든다
- `replaces` 와 `memory_id` 를 함께 주거나 빈 배열, 11개, 중복이면 `-32602`
- 기록 목록의 `replacedTitles`

## 검증

```bash
cd backend && ./gradlew test --tests '*McpMemoryReplacesToolTest' --tests '*McpMemoryRememberToolTest' --tests '*McpMemoryToolTest' --tests '*MemoryCaptureConcurrencyTest' --tests '*ContextAssemblerTest' --tests '*ArchitectureRules*'
cd backend && ./gradlew checkstyleMain checkstyleTest spotlessCheck
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/resources/db/migration/V20261105074430__memory_capture_replaced_ids.sql` | 신규 |
| `backend/src/main/java/com/bifos/assistant/memory/domain/entity/MemoryCapture.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/memory/domain/ReplacedMemoryIdsConverter.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/memory/infra/MemoryRepository.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/memory/infra/MemoryCaptureRepository.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/memory/application/MemoryReplacements.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/memory/application/MemoryCaptureService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/memory/application/MemoryService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/memory/application/dto/*.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/mcp/**` | 수정 |
| `backend/src/main/java/com/bifos/assistant/context/application/ContextAssembler.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/turn/presentation/ChatDtos.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/mcp/McpMemoryReplacesToolTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/memory/application/MemoryServiceGuardTest.java` | 수정 (생성자 인자) |
