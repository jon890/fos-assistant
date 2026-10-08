# Phase 02. 사용자 프로필 구역 조립

**Execution profile**: deep

## 목표

`ContextAssembler` 가 항상 층과 색인 층 사이에 짧은 개인 기억을 본문까지 싣는 「사용자 프로필」 구역을 조립한다.
`memory_remember` 로 남긴 이름과 선호가 다음 대화부터 도구 호출 없이 쓰이게 한다.

**범위 외**: 답마다 참고한 기억 API(phase 03)와 화면(phase 04). 관리자 실행 상세의 출처 라벨은 phase 04 가 더한다. `McpMemoryRemember` 와 `MemoryCaptureService` 는 고치지 않는다(#283 이 고치는 영역).

## Blocked 조건

- #283 의 구현 PR 이 main 에 머지되지 않았다 → `PHASE_BLOCKED: #283 머지 대기` 출력 후 종료. 머지됐으면 이 브랜치에 `origin/main` 을 합치고 시작한다.

## 컨텍스트

- 규칙은 `docs/backend/memory.md` 의 「사용자 프로필 구역」 표가 갖는다. 후보, 예산, 담는 순서, 넘칠 때, 줄의 모양, 색인과의 관계, 항상 층 뒤에 자리가 모자랄 때가 모두 거기 있다. 예시 글도 거기 있다.
- 결정의 근거: `docs/adr/ADR-20261008-memory-profile.md`.
- 지금 조립: `backend/src/main/java/com/bifos/assistant/context/ContextAssembler.java` 의 private `assemble(CurrentUser, MemoryAccess)`. 색인 몫을 `indexLength` 로 먼저 떼고(`builder.limit(maxChars - indexBudget)`), `appendAlways` 두 번, `builder.limit(maxChars)`, `appendIndex` 순서다. `ContextBuilder.append` 는 넘치는 항목을 `OMITTED` 로 남긴다.
- 문맥 묶음 출처: `ContextSource` enum. 칸의 뜻은 `docs/backend/context-bundle.md` 의 「참여하는 source」 에 이미 `MEMORY_PROFILE` 이 있다.
- 설정: `ContextProperties`(`assistant.context`). 생성자를 부르는 곳은 운영 바인딩 말고 `ContextPropertiesTest`, `MemoryFreshnessTest` 둘이다.
- `assembleForOwner` 도 같은 private `assemble` 을 지나므로 `/memory` 의 빠짐 표시도 함께 바뀐다. 프로필에서 색인으로 내려간 항목이 `OMITTED` 가 되지 않게 하는 것이 이 때문에 중요하다(`docs/backend/schema/execution.md` 의 「`MEMORY_` 로 시작하는 `OMITTED` 줄의 수는 `context_omitted_items` 와 같다」).

**근거 문서**: `docs/backend/memory.md` 의 「사용자 프로필 구역」, `docs/backend/context-bundle.md`, `docs/adr/ADR-20261008-memory-profile.md`, `docs/backend/memory-eval.md`

## 의도 메모

- `retrieval` 을 바꾸지 않는다. 프로필은 조립 단계의 선택이다. `memory_read`(`MemoryService.bodyFor`)는 `SEARCH` 만 보므로 그대로 읽힌다.
- LLM 요약을 쓰지 않는다. 기존 조회 결과를 그대로 늘어놓는다.
- 자르지 않는다. 200자를 넘는 본문은 후보가 아니다. 줄바꿈만 공백으로 바꾼다.
- 고른 뒤 번호 순으로 늘어놓아, 같은 집합이면 같은 글이 되게 한다(지시문 지문 안정).
- 예산이 0 이면 구역 전체를 끈다. 합성 측정의 `profileOff` 모드가 이 값을 쓴다.

## 작업 항목

### 1. `ContextProperties` 에 칸 둘

`Integer profileMaxChars`, `Integer profileItemMaxChars` 를 record 끝에 더한다. 생성자에서 `profileMaxChars` 가 null 이거나 음수면 2,000, `profileItemMaxChars` 가 null 이거나 0 이하면 200 으로 둔다. `profileMaxChars` 의 0 은 끔이라 그대로 둔다. Javadoc 에 뜻을 적는다.
`backend/src/main/resources/application.yml` 의 `assistant.context` 아래에 `profile-max-chars: 2000`, `profile-item-max-chars: 200` 을 적는다.
`ContextPropertiesTest`, `MemoryFreshnessTest` 의 생성자 호출을 고친다.

### 2. `ContextSource.MEMORY_PROFILE`

`MEMORY_ALWAYS` 와 `MEMORY_INDEX` 사이에 더한다. 주석: 「본문까지 싣는 Memory 사용자 프로필 구역의 줄.」

### 3. `ContextAssembler`

- 머리 상수 `PROFILE_HEADER` 를 더한다. 글은 `docs/backend/memory.md` 의 예시 첫 두 문단과 글자까지 같다.
- 후보: `memories.indexedFor(user, access)` 가운데 `scope()==USER`, `entryType()==MEMORY`, `sensitivity()==NORMAL`, `!sealed()`, `content().length() <= profileItemMaxChars`.
- 고르기 함수: 후보를 `updatedAt` 내림차순, 같으면 `id` 내림차순으로 보며, 머리와 구분 줄을 포함한 구역 글 길이가 주어진 예산 안이면 담고 아니면 건너뛴다. 고른 목록을 돌려준다.
- 조립 순서
  1. 예산 `profileMaxChars` 로 먼저 고른다.
  2. 색인 대상은 색인 후보에서 1 의 항목을 뺀 것이다. 그것으로 `indexBudget` 을 계산한다.
  3. `builder.limit(maxChars - indexBudget)` 뒤 항상 층 둘을 담는다.
  4. 남은 자리(`limit` 에서 지금 글 길이와 앞 구분 줄을 뺀 값)와 `profileMaxChars` 가운데 작은 값으로 다시 고른다. 다시 고르며 빠진 후보는 색인 대상으로 되돌린다.
  5. 고른 항목을 번호 오름차순으로 `- [번호] 제목: 본문`(본문의 `\r\n`, `\n`, `\r` 은 공백 하나) 줄로 담는다. 항목은 `memoryItem(memory, ContextSource.MEMORY_PROFILE, ContextBodyMode.INLINE, List.of(), memory.title(), memory.content(), memoryFreshness(memory, now))`. 4 에서 계산한 대로 모두 들어가야 한다. 들어가지 않으면 계산이 틀린 것이므로 `IllegalStateException`.
  6. `builder.limit(maxChars)` 뒤 색인 대상을 지금처럼 담는다. `sameNameDocuments` 는 색인 대상으로 계산한다.
- 클래스 Javadoc 과 `assemble(CurrentUser, Long)` 의 Javadoc 에 층 순서를 고친다.

### 4. `backend/src/test/java/com/bifos/assistant/context/ContextAssemblerTest.java` 에 시험 추가

- 짧은 개인 `SEARCH` 항목이 프로필 구역에 `- [번호] 제목: 본문` 으로 실리고 색인에는 없다. 묶음 항목이 `MEMORY_PROFILE`, `INLINE`.
- 그룹 항목, 201자 본문, 민감 항목, `DOCUMENT` 는 프로필에 없고 색인에 제목으로 있다.
- 예산을 넘으면 최근에 고친 것부터 담고, 빠진 후보는 색인에 `TITLE_ONLY` 로 남으며 `omittedMemoryIds` 에 없다.
- 항상 층이 거의 다 채운 경우(기존 `거의 상한` 시험과 같은 준비) 프로필 후보가 색인으로 내려가고 `OMITTED` 가 되지 않는다.
- 같은 항목 집합이면 저장 순서를 바꿔도 `instructionsHash()` 가 같다.
- `ContextProperties` 의 `profileMaxChars=0` 으로 만든 `new ContextAssembler(memories, properties, clock)` 는 지금과 같은 글을 낸다(프로필 머리가 없다).
- 다른 에이전트가 받지 않는 collection 의 짧은 항목은 프로필에도 없다.

### 5. 합성 측정에 `profileOn` 모드

`backend/src/test/java/com/bifos/assistant/context/eval/MemoryRecallEvalTest.java` 에서 주입받은 `ContextProperties` 를 바탕으로 `profileMaxChars` 만 0 으로 바꾼 조립기(`profileOff`)와 기본값 조립기(`profileOn`)를 `new ContextAssembler(memoryService, properties, clock)` 로 만들어 두 모드를 모두 판정한다. 권한 경계 노출 0 을 두 모드 모두에 단언한다.
`INFORMATION_EXTRACTION` 의 짧은 사실에 대해 `profileOn` 의 회수율(본문)이 `profileOff` 보다 높다는 것을 단언하지 않는다. 보고서에만 낸다.

## 검증

```bash
cd backend && ./gradlew test --tests '*ContextAssemblerTest' --tests '*ContextPropertiesTest' --tests '*MemoryFreshnessTest' --tests '*MemoryRecallEvalTest'
cd backend && ./gradlew test --tests '*ExecutionContextSource*' --tests '*McpMemory*' --tests '*MemoryCapture*'
cd backend && ./gradlew spotlessCheck checkstyleMain checkstyleTest
```

기대값: 모두 통과. `backend/build/reports/memory-eval/report.md` 에 `profileOff` 와 `profileOn` 두 열이 있다.

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
