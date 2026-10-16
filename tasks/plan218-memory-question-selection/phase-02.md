# Phase 02. 질문과 관련된 기억 구역

**Execution profile**: deep

## 목표

사용자 turn 과 위임 실행의 조립이 질문을 받아, 관련도가 높은 몇 건을 본문까지 싣고 색인을 관련도 순으로 늘어놓는다. 측정 게이트로 나빠지지 않았음을 보인다.

**범위 외**: `memory_search`(phase 03), 임베딩(phase 04, 05), 먼저 살펴보기의 질문 기반 선별(단계 4).

## 컨텍스트

**근거 문서**: `docs/features/memory.md` 의 「범위와 조립」, 「질문과 관련된 기억 구역」, 「참여하는 source」, 「답마다 참고한 기억」, 「Memory 회수 측정」. `backend/docs/adr/ADR-20261015-memory-hybrid-selection.md` 의 「구현 순서」.

- 조립 코어는 `backend/src/main/java/com/bifos/assistant/context/ContextAssembler.java` 의 private `assemble(CurrentUser, MemoryAccess)` 다. 순서는 항상 층(그룹, 개인) → `appendFacts` → `appendIndex` 이고, `builder.limit(maxChars - indexBudget)` 아래에서 항상 층과 개인 사실 구역을 싣는다. 새 구역도 이 한도 아래, 개인 사실 구역 바로 뒤에 싣는다.
- 선택 근거는 `withSelections` 가 `SELECTORS`(source 마다 규칙 하나)로 붙인다. 새 구역과 관련도 순 색인은 항목마다 규칙과 점수가 달라 항목 단위로 붙여야 한다.
- 이 파일은 443줄이고 main Java 의 상한은 500줄이다(`scripts/checks/check-file-length.mjs`). 구역을 만드는 일은 새 클래스로 뺀다.
- 생성자는 `@RequiredArgsConstructor` 의 `(MemoryService, ContextProperties, Clock)` 이다. 테스트가 `new ContextAssembler(` 로 직접 만든다. `git grep -n "new ContextAssembler(" backend/src/test` 로 찾은 파일을 모두 고친다(지금 `ContextAssemblerTest`, `MemoryFreshnessTest`, `MemoryRecallEvalTest`, `MemoryJudgeEvalTest`, `McpMemorySearchToolTest`).
- 호출부: `chat/application/ChatTurnRunner.java` 의 `contextAssembler.assemble(user, routed.agent().id())`(지역 변수 `asked` 가 있다), `orchestration/application/AgentRunner.java` 의 `assemble(user, agent.id())`(인자 `task` 가 있다). `proactive/application/ProactiveCheckInput.java` 는 지시문 지문을 지난 살펴보기와 견주므로 질문 없는 `assemble(user, agentId)` 를 그대로 쓴다. `assembleForOwner` 도 질문 없이 조립한다.
- 「참고한 기억」 은 `usage/application/ExecutionMemoryRefs.java` 의 `VIA_BY_SOURCE` 와 `usage/application/model/MemoryUseVia.java` 가 정한다. 화면 이름은 `web/src/components/chat/memory-use-list.tsx`, 실행 상세의 source 이름은 `web/src/components/execution/execution-detail.tsx` 의 `CONTEXT_SOURCE_LABELS` 다.
- 측정은 `backend/src/test/java/com/bifos/assistant/context/eval/MemoryRecallEvalTest.java` 의 `measuresRecallAndKeepsBoundary` 다. 사례의 질문은 `MemoryEvalDataset.Case.question()` 에 있다. 기준 파일 절차는 `docs/features/memory.md` 의 「기준 파일」 표를 따른다.

## 의도 메모

- 개인 사실 구역은 바꾸지 않는다(ADR-20261008 / memory-facts). 그 구역에 실린 항목은 새 구역의 후보가 아니다.
- 넘치는 항목을 잘라 싣지 않는다. 잘린 사실은 틀린 사실이 될 수 있다(「범위와 조립」).
- 질문이 바뀌면 지시문 지문이 바뀌는 것은 ADR 이 감수한 비용이다. 질문 없는 조립의 지문은 지금과 같아야 한다.
- 이 phase 의 선별은 키워드 점수만 쓴다. 규칙 이름은 `search-keyword-v1` 이다. phase 05 가 RRF 를 더하며 `search-hybrid-v1` 을 더한다.

## 작업 항목

### 1. `context/ContextSource.java` 와 `context/ContextSelection.java`

- `ContextSource` 에 `MEMORY_SEARCH` 를 `MEMORY_FACTS` 다음에 더한다. 저장은 이름 문자열이라 마이그레이션이 없다.
- `ContextSelection` 에 상수 `SEARCH_KEYWORD = "search-keyword-v1"`(질문과 관련된 기억 구역, 키워드 점수 순), `INDEX_KEYWORD = "index-keyword-v1"`(색인 가운데 키워드 점수가 있는 항목, 점수 순)을 더하고 Javadoc 목록에 뜻을 적는다. 점수가 없는 색인 항목은 `INDEX_BY_ID` 를 그대로 쓴다. 점수가 있는 근거는 `new ContextSelection(selector, rank, keywordScore, null, keywordScore)` 다.

### 2. `context/MemorySearchSectionProperties.java` 신규

- prefix `assistant.context.search`. `int maxItems`(기본 3), `int itemMaxChars`(기본 600), `int maxChars`(기본 1500). 모두 `@Min(0)` 이고 `maxItems` 가 0 이면 구역을 끈다.
- `ContextProperties` 에 칸을 더하지 않는다. 측정 테스트가 그 record 를 위치 인자로 다시 만든다(`MemoryRecallEvalTest.withFactsMaxChars`).
- `application.yml` 의 `assistant.context` 아래에 `search` 블록을 적는다.
- `docs/features/memory.md` 의 「질문과 관련된 기억 구역」 에서 「`assistant.context` 아래의 설정값」 을 실제 키 `assistant.context.search` 로 고친다. 계획 커밋에서는 키가 코드에 없어 `test/unit/doc-code-references.test.ts` 가 막았다.

### 3. `context/MemorySearchSection.java` 신규

- 질문, 개인 사실 구역에 실리지 않은 색인 후보(`List<Memory>`), 쓰는 중인 `ContextBudgetWriter` 를 받아 구역을 싣고, 실은 항목 번호와 항목마다의 `ContextSelection`, 색인에 놓을 순서(점수가 있는 항목을 점수 순으로, 나머지를 번호 순으로)를 낸다.
- 후보를 `RelevanceCandidate` 로 바꿀 때 본문은 `MemoryService.contentOf` 를 부르지 않는다. 민감도가 `NORMAL` 이고 `memory.sealed()` 가 거짓인 항목만 `memory.content()` 를 넘기고 나머지는 null 이다.
- 구역 머리 줄은 `질문과 관련된 기억` 이다. 항목 줄은 개인 사실 구역의 줄과 같은 모양(`- [번호] 제목: 본문`, 줄바꿈은 공백 하나)이다. 개인 사실 구역의 줄 만드는 코드가 private 이면 그것을 이 클래스와 함께 쓰는 위치로 옮긴다.
- 싣는 항목: 점수 순으로, `plainBody` 가 있고 본문이 `itemMaxChars` 이하인 항목을, 구역 합계가 `maxChars` 와 남은 예산 안에 들 때까지 `maxItems` 건까지. 들어가지 않는 항목은 건너뛰고 색인에 남는다. 관련 항목이 없으면 머리 줄도 싣지 않는다.
- 항목의 `ContextItem` 은 `source=MEMORY_SEARCH`, `bodyMode=INLINE`, `trust=USER_APPROVED` 이고 `sensitivity` 는 `NORMAL` 이다.

### 4. `context/ContextAssembler.java`

- 공개 메서드 `assemble(CurrentUser user, Long agentId, String question)` 을 더한다. 기존 `assemble(user, agentId)` 는 `question` 이 null 인 이 메서드를 부른다. 질문이 null 이거나 공백뿐이면 지금과 같은 글을 낸다.
- private 코어는 `appendFacts` 뒤에 `MemorySearchSection` 을 부르고, 그 결과의 순서로 `appendIndex` 를 부른다. 질문이 없을 때 `appendIndex` 는 지금처럼 번호 순이다.
- `withSelections` 가 새 구역과 관련도 순 색인의 항목별 근거를 붙이게 바꾼다. 항상 층과 개인 사실 구역의 근거는 지금과 같다.
- 생성자 의존에 `MemorySearchSection`(또는 그것을 만드는 `KeywordScorer`, `MemoryRelevanceProperties`, `MemorySearchSectionProperties`)을 더한다. 테스트의 `new ContextAssembler(` 호출부를 모두 고친다.

### 5. 호출부

- `ChatTurnRunner`: `intent` 가 `TurnIntent.Fresh` 나 `TurnIntent.Regenerate` 면 `asked` 를 질문으로 넘긴다. 다른 intent(`DelegationResults`, `Scheduled`, `ProactiveCheck`)는 질문 없이 조립한다.
- `AgentRunner`: `task` 를 질문으로 넘긴다.
- `ProactiveCheckInput` 은 바꾸지 않는다.
- 조립을 mock 으로 두는 테스트는 인자 둘의 `assemble(any(), any())` stub 과 verify 를 쓴다. 호출부가 인자 셋의 메서드를 부르면 stub 이 맞지 않는다. `git grep -n "assemble(any(), any())\|assemble(eq(user), any())" backend/src/test` 로 찾은 파일(`ResultDeliveryRecordTest`, `AgentRunnerConnectorContextTest`, `AgentRunnerSubmitFailureTest`)을 인자 셋으로 고친다. `AgentRunnerConnectorContextTest` 에는 위임 작업 글이 질문으로 넘어가는 verify 를 더한다.

### 6. 「참고한 기억」 과 운영 집계

- `MemoryUseVia` 에 `SEARCH`(질문과 관련된 기억 구역에 본문까지 실었다)를 더하고 `ExecutionMemoryRefs.VIA_BY_SOURCE` 에 `"MEMORY_SEARCH", MemoryUseVia.SEARCH` 를 더한다.
- `web/src/lib/memory-use-api.ts` 의 `via` 유니온과 그 주석에 `SEARCH` 를, `memory-use-list.tsx` 의 이름 표에 `SEARCH: "질문으로 찾은 기억"` 을 더한다. `execution-detail.tsx` 의 `CONTEXT_SOURCE_LABELS` 에 `MEMORY_SEARCH: "기억(질문 관련)"` 을 더한다.
- `backend/src/test/resources/memory-eval/ops-metrics.sql` 의 본문을 실은 source 목록(`'MEMORY_ALWAYS', 'MEMORY_FACTS', 'MEMORY_READ'`)이 나오는 모든 쿼리에 `'MEMORY_SEARCH'` 를 더한다.

### 7. 측정

- `MemoryRecallEvalTest`: `factsOff` 와 `factsOn` 의 조립에 `each.question()` 을 넘긴다. 질문을 넘기지 않는 운영 기본값 조립을 `questionOff` 모드로 더하고 모드 목록 단언에 넣는다.
- 실패 조건에 「`factsOn` 의 `RECALL_GAP` recall@5 가 `questionOff` 보다 높지 않으면 실패」 를 더한다. 메시지에 두 값을 낸다.
- `MemoryJudgeEvalTest` 의 조립도 질문을 넘긴다.
- `MemoryEvalDataset.Case.question()` 의 Javadoc 에서 「판정에 쓰지 않는다」 를 「조립에 넘기고 판정에는 쓰지 않는다」 로 고친다.
- `keywordMinCoverage` 기본값(0.3)을 사례 묶음으로 확인한다. 값을 바꾸면 phase 01 의 기본값과 `application.yml` 을 함께 고치고, `ABSTENTION` 범주의 오기억 노출이 오르지 않는 값을 고른다.
- 값이 좋아졌으면 「기준 파일」 표대로 `-PmemoryEval.writeBaseline=true` 로 `baseline.json` 을 다시 만들고 앞뒤 값을 커밋 메시지 본문에 적는다. 나빠졌으면 다시 만들지 않고 멈춘다(`PHASE_BLOCKED`).

### 8. 이 phase 를 검증하는 테스트

- `backend/src/test/java/com/bifos/assistant/context/ContextAssemblerQuestionTest.java` 신규:
  - 정상: 본문에만 「땅콩」 이 있는 항목이 질문 「땅콩 들어간 거 먹어도 돼?」 에 `MEMORY_SEARCH` 의 `INLINE` 으로 실리고, 색인에서 같은 번호가 사라진다. 근거는 `search-keyword-v1`, 순위 1, 점수가 있다.
  - 관련 없음: 아무 항목과도 낱말이 겹치지 않는 질문은 구역 머리 줄이 없고, 글이 질문 없는 조립과 같다.
  - 민감 항목과 암호화한 항목은 본문의 낱말로 구역에 오르지 못하고, 제목이 맞으면 색인의 앞쪽(`index-keyword-v1`)에 온다.
  - 예산: `itemMaxChars` 를 넘는 본문은 건너뛰고 다음 항목을 싣는다. `maxItems` 를 지킨다.
  - 질문 없는 조립의 `instructionsHash` 가 이 phase 전과 같다(같은 자료로 `assemble(user, agentId)` 와 `assemble(user, agentId, null)` 이 같은 지문).
- `ChatServiceTest` 에 사용자 turn 하나가 `execution_context_source` 에 `MEMORY_SEARCH` 줄과 근거를 남기고, 예약 작업 turn 은 남기지 않는 경우를 더한다.
- `MemoryUseServiceTest` 에 `MEMORY_SEARCH` 줄이 `via=SEARCH` 로 나오는 경우를 더한다.
- `MemoryOpsMetricsSqlTest` 에 `MEMORY_SEARCH` 의 `INLINE` 줄이 실린 항목으로 세어지는 경우를 더한다.
- `test/browser/memory-use.spec.ts` 에 `via=SEARCH` 항목 하나를 더하고 「질문으로 찾은 기억」 이 보이는지 단언한다.

## 검증

```bash
cd backend && ./gradlew test --tests '*ContextAssemblerQuestionTest' --tests '*AgentRunnerConnectorContextTest' --tests '*AgentRunnerSubmitFailureTest' --tests '*ResultDeliveryRecordTest' --tests '*ContextAssemblerTest' --tests '*MemoryFreshnessTest' --tests '*ChatServiceTest' --tests '*MemoryUseServiceTest' --tests '*MemoryOpsMetricsSqlTest' --tests '*McpMemorySearchToolTest'
cd backend && ./gradlew test --tests '*MemoryRecallEvalTest'
cd backend && ./gradlew test
cd backend && ./gradlew archTest spotlessCheck
cd web && npm run lint && npm run typecheck
cd web && npm run test:browser -- memory-use
node scripts/checks/check-file-length.mjs
```

기대: 모두 통과. `MemoryRecallEvalTest` 보고서(`backend/build/reports/memory-eval/report.md`)에 `questionOff` 와 `factsOn` 이 함께 나오고 `factsOn` 의 `RECALL_GAP` recall@5 가 더 높다. 파일 길이 검사의 위반이 0이다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/context/ContextSource.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/context/ContextSelection.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/context/MemorySearchSectionProperties.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/context/MemorySearchSection.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/context/ContextAssembler.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/application/ChatTurnRunner.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/orchestration/application/AgentRunner.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/usage/application/model/MemoryUseVia.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/usage/application/ExecutionMemoryRefs.java` | 수정 |
| `backend/src/main/resources/application.yml` | 수정 |
| `docs/features/memory.md` | 수정 |
| `web/src/lib/memory-use-api.ts` | 수정 |
| `web/src/components/chat/memory-use-list.tsx` | 수정 |
| `web/src/components/execution/execution-detail.tsx` | 수정 |
| `backend/src/test/resources/memory-eval/ops-metrics.sql` | 수정 |
| `backend/src/test/resources/memory-eval/baseline.json` | 수정 |
| `backend/src/test/java/com/bifos/assistant/context/ContextAssemblerQuestionTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/context/ContextAssemblerTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/context/MemoryFreshnessTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/context/eval/MemoryRecallEvalTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/context/eval/MemoryJudgeEvalTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/context/eval/MemoryEvalDataset.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/context/eval/MemoryOpsMetricsSqlTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/mcp/McpMemorySearchToolTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/ChatServiceTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/MemoryUseServiceTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/ResultDeliveryRecordTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/orchestration/AgentRunnerConnectorContextTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/orchestration/AgentRunnerSubmitFailureTest.java` | 수정 |
| `test/browser/memory-use.spec.ts` | 수정 |
