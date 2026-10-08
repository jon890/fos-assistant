# Phase 02. 개인 사실 구역 조립

**Execution profile**: deep

## 목표

`ContextAssembler` 가 항상 층과 색인 층 사이에 짧은 개인 기억을 본문까지 싣는 「개인 사실 구역」을 조립한다.
`memory_remember` 로 남긴 이름과 선호가 다음 대화부터 도구 호출 없이 쓰이게 한다.

**범위 외**: 답마다 참고한 기억 API(phase 03)와 화면(phase 04). 관리자 실행 상세의 출처 라벨은 phase 04 가 더한다. `McpMemoryRemember` 와 `MemoryCaptureService` 는 고치지 않는다(#283 이 고치는 영역).

## Blocked 조건

- #283 의 구현 PR 이 main 에 머지되지 않았다 → `PHASE_BLOCKED: #283 머지 대기` 출력 후 종료. 머지됐으면 이 브랜치에 `origin/main` 을 합치고 시작한다.

## 컨텍스트

- 규칙은 `docs/backend/memory.md` 의 「개인 사실 구역」 표가 갖는다. 후보, 예산, 담는 순서, 넘칠 때, 줄의 모양, 색인과의 관계, 항상 층 뒤에 자리가 모자랄 때가 모두 거기 있다. 예시 글도 거기 있다.
- 결정의 근거: `docs/adr/ADR-20261008-memory-facts.md`.
- 지금 조립: `backend/src/main/java/com/bifos/assistant/context/ContextAssembler.java` 의 private `assemble(CurrentUser, MemoryAccess)`. 색인 몫을 `indexLength` 로 먼저 떼고(`builder.limit(maxChars - indexBudget)`), `appendAlways` 두 번, `builder.limit(maxChars)`, `appendIndex` 순서다. `ContextBuilder.append` 는 넘치는 항목을 `OMITTED` 로 남긴다.
- 문맥 묶음 출처: `ContextSource` enum. 칸의 뜻은 `docs/backend/context-bundle.md` 의 「참여하는 source」 에 이미 `MEMORY_FACTS` 이 있다.
- 설정: `ContextProperties`(`assistant.context`). 생성자를 부르는 곳은 운영 바인딩 말고 `ContextPropertiesTest`, `MemoryFreshnessTest` 둘이다.
- `assembleForOwner` 도 같은 private `assemble` 을 지나므로 `/memory` 의 빠짐 표시도 함께 바뀐다. 개인 사실 구역에서 색인으로 내려간 항목이 `OMITTED` 가 되지 않게 하는 것이 이 때문에 중요하다(`docs/backend/schema/execution.md` 의 「`MEMORY_` 로 시작하는 `OMITTED` 줄의 수는 `context_omitted_items` 와 같다」).

**근거 문서**: `docs/backend/memory.md` 의 「개인 사실 구역」, `docs/backend/context-bundle.md`, `docs/adr/ADR-20261008-memory-facts.md`, `docs/backend/memory-eval.md`

## 의도 메모

- `retrieval` 을 바꾸지 않는다. 개인 사실 구역은 조립 단계의 선택이다. `memory_read`(`MemoryService.bodyFor`)는 `SEARCH` 만 보므로 그대로 읽힌다.
- LLM 요약을 쓰지 않는다. 기존 조회 결과를 그대로 늘어놓는다.
- 자르지 않는다. 200자를 넘는 본문은 후보가 아니다. 줄바꿈만 공백으로 바꾼다.
- 고른 뒤 번호 순으로 늘어놓아, 같은 집합이면 같은 글이 되게 한다(지시문 지문 안정).
- 예산이 0 이면 구역 전체를 끈다. 합성 측정의 `factsOff` 모드가 이 값을 쓴다.

## 작업 항목

### 1. `ContextProperties` 에 칸 둘

`Integer factsMaxChars`, `Integer factsItemMaxChars` 를 record 끝에 더한다. 생성자에서 `factsMaxChars` 가 null 이거나 음수면 2,000, `factsItemMaxChars` 가 null 이거나 0 이하면 200 으로 둔다. `factsMaxChars` 의 0 은 끔이라 그대로 둔다. Javadoc 에 뜻을 적는다.
`backend/src/main/resources/application.yml` 의 `assistant.context` 아래에 `facts-max-chars: 2000`, `facts-item-max-chars: 200` 을 적는다.
`ContextPropertiesTest`, `MemoryFreshnessTest` 의 생성자 호출을 고친다.

### 2. `ContextSource.MEMORY_FACTS`

`MEMORY_ALWAYS` 와 `MEMORY_INDEX` 사이에 더한다. 주석: 「본문까지 싣는 Memory 개인 사실 구역의 줄.」

### 3. `ContextAssembler`

- 머리 상수 `FACTS_HEADER` 를 더한다. 글은 `docs/backend/memory.md` 의 예시 첫 두 문단과 글자까지 같다.
- 후보: `memories.indexedFor(user, access)` 가운데 `scope()==USER`, `entryType()==MEMORY`, `sensitivity()==NORMAL`, `!sealed()`, `content().length() <= factsItemMaxChars`.
- 고르기 함수: 후보를 `updatedAt` 내림차순, 같으면 `id` 내림차순으로 보며, 머리와 구분 줄을 포함한 구역 글 길이가 주어진 예산 안이면 담고 아니면 건너뛴다. 고른 목록을 돌려준다.
- 조립 순서(색인 몫 계산은 지금 그대로다)
  1. 색인 몫 `indexBudget` 은 지금처럼 `indexedFor` 결과 전체(개인 사실 후보를 빼기 전)로 계산한다. 이렇게 해야 개인 사실 구역이 색인을 밀어내지 못한다.
  2. `builder.limit(maxChars - indexBudget)` 뒤 항상 층 둘을 담는다.
  3. 남은 자리(`limit` 에서 지금 글 길이를 뺀 값. 그 사이 구분 줄도 센다)와 `factsMaxChars` 가운데 작은 값을 예산으로 후보를 **한 번** 고른다.
  4. 고른 항목을 번호 오름차순으로 `- [번호] 제목: 본문`(본문의 `\r\n`, `\n`, `\r` 은 공백 하나) 줄로 담는다. 항목은 `memoryItem(memory, ContextSource.MEMORY_FACTS, ContextBodyMode.INLINE, List.of(), memory.title(), memory.content(), memoryFreshness(memory, now))`. 고르기와 담기는 같은 길이 계산(`ContextBuilder.next` 의 머리와 구분 줄 규칙)을 쓴다. 그래도 들어가지 않는 항목이 있으면 예외를 던지지 않고 경고 로그(번호만)를 남긴 뒤 그 항목을 색인 대상으로 돌린다.
  5. 색인 대상 = `indexedFor` 결과 − 4 에서 실제로 담은 항목. 마지막에 한 번 계산한다. `builder.limit(maxChars)` 뒤 지금처럼 담는다. `sameNameDocuments` 는 색인 대상으로 계산한다.
- 클래스 Javadoc 과 `assemble(CurrentUser, Long)` 의 Javadoc 에 층 순서를 고친다.

### 4. `backend/src/test/java/com/bifos/assistant/context/ContextAssemblerTest.java` 에 시험 추가

- 짧은 개인 `SEARCH` 항목이 개인 사실 구역에 `- [번호] 제목: 본문` 으로 실리고 색인에는 없다. 묶음 항목이 `MEMORY_FACTS`, `INLINE`.
- 그룹 항목, 201자 본문, 민감 항목, `DOCUMENT` 는 개인 사실 구역에 없고 색인에 제목으로 있다.
- 예산을 넘으면 최근에 고친 것부터 담고, 빠진 후보는 색인에 `TITLE_ONLY` 로 남으며 `omittedMemoryIds` 에 없다.
- 항상 층이 실제로 들어가면서 자리를 거의 다 쓰는 경우(항상 층 본문을 「상한 − 색인 몫」 이하이면서 남는 자리가 개인 사실 줄 하나보다 작게, 예: 7,850자 안팎으로 맞춘다) 개인 사실 구역이 비고, 후보는 색인에 `TITLE_ONLY` 로 남아 `OMITTED` 가 되지 않는다.
- 같은 항목 집합을 두 번 조립하면 `instructionsHash()` 가 같다. `JdbcTemplate` 으로 항목 하나의 `updated_at` 만 바꿔 고르는 차례가 달라져도, 예산 안에 모두 들어 고른 집합이 같으면 지문이 같다.
- `ContextProperties` 의 `factsMaxChars=0` 으로 만든 `new ContextAssembler(memories, properties, clock)` 는 지금과 같은 글을 낸다(개인 사실 구역 머리가 없다).
- 다른 에이전트가 받지 않는 collection 의 짧은 항목은 개인 사실 구역에도 없다.

### 5. 기대값이 바뀌는 기존 시험

짧은 개인 `SEARCH` 항목이 이제 개인 사실 구역으로 가므로 아래 시험의 기대값을 고친다. 시험이 지키려던 성질(색인이 밀려나지 않는다, 묶음 순서가 글 순서와 같다, 제목과 본문을 실행 기록에 남기지 않는다)은 그대로 단언한다.

- `ContextAssemblerTest`: `shipsIndexLayerEvenWhenAlwaysLayerNearlyFillsLimit`(항상 층이 넘쳐 빠진 자리를 개인 사실 구역이 쓰므로 색인 머리가 사라진다. 색인 항목 본문을 201자 이상으로 바꿔 「항상 층이 색인을 밀어내지 못한다」 는 원래 뜻을 지킨다), `shipsIndexAndRestEvenIfFirstItemExceedsLimit`, `rendersSameTextAndHashFromItemsWithoutConflict`, `bundlesItemsInRenderedOrder`, `keepsOmittedItemInBundleAtItsPlace`. 색인 항목을 쓰던 준비는 본문을 201자 이상으로 바꿔 색인에 남기거나, 기대값에 개인 사실 줄을 넣는다.
- `backend/src/test/java/com/bifos/assistant/chat/ChatServiceTest.java` 의 `recordsAlwaysAndIndexSourcesOfTurnWithoutTitleOrBody`: 색인 항목의 본문을 201자 이상으로 바꿔 `MEMORY_INDEX, TITLE_ONLY` 를 지키고, 짧은 개인 항목 하나를 더해 `MEMORY_FACTS, INLINE` 이 실행 기록에 제목과 본문 없이 남는 것을 함께 단언한다.
- `test/e2e/scenarios/memory.ts` 의 「Memory 구역은 항상 층 본문과 색인이 정해진 모양 그대로다」 단계: `userIndexed` 의 본문을 201자 이상으로 바꿔 기존 모양을 글자 그대로 지킨다. 짧은 개인 항목 하나(`alwaysInject: false`)를 더해, 기대 글의 개인 항목 항상 층과 색인 머리 사이에 `docs/backend/memory.md` 예시와 같은 머리 두 문단과 `- [번호] 제목: 본문` 줄을 넣는다. 끝의 「색인에만 싣는 항목의 본문이 없다」 단언은 `userIndexed`, `groupIndexed` 에 대해 그대로 둔다. 그 단계가 만든 항목은 끝에서 지운다.

### 6. 합성 측정에 `factsOn` 모드

`backend/src/test/java/com/bifos/assistant/context/eval/MemoryRecallEvalTest.java` 에서 주입받은 `ContextProperties` 를 바탕으로 `factsMaxChars` 만 0 으로 바꾼 조립기(`factsOff`)와 기본값 조립기(`factsOn`)를 `new ContextAssembler(memoryService, properties, clock)` 로 만들어 두 모드를 모두 판정한다. 권한 경계 노출 0 을 두 모드 모두에 단언한다.
`INFORMATION_EXTRACTION` 의 짧은 사실에 대해 `factsOn` 의 회수율(본문)이 `factsOff` 보다 높다는 것을 단언하지 않는다. 보고서에만 낸다.

## 검증

```bash
cd backend && ./gradlew test --tests '*ContextAssemblerTest' --tests '*ContextPropertiesTest' --tests '*MemoryFreshnessTest' --tests '*MemoryRecallEvalTest'
cd backend && ./gradlew test --tests '*ExecutionContextSource*' --tests '*McpMemory*' --tests '*MemoryCapture*' --tests '*ChatServiceTest'
node test/e2e/run.ts
cd backend && ./gradlew spotlessCheck checkstyleMain checkstyleTest
```

기대값: 모두 통과. `backend/build/reports/memory-eval/report.md` 에 `factsOff` 와 `factsOn` 두 열이 있다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/context/ContextProperties.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/context/ContextSource.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/context/ContextAssembler.java` | 수정 |
| `backend/src/main/resources/application.yml` | 수정 |
| `backend/src/test/java/com/bifos/assistant/context/ContextAssemblerTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/context/ContextPropertiesTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/context/MemoryFreshnessTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/context/eval/MemoryRecallEvalTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/ChatServiceTest.java` | 수정 |
| `test/e2e/scenarios/memory.ts` | 수정 |
