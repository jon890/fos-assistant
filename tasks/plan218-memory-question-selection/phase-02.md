# Phase 02. 의미 점수와 RRF, 키워드로 물러나기

**Execution profile**: deep

## 목표

질문 임베딩과 저장한 벡터의 코사인을 키워드 점수와 RRF 로 합쳐, 조립과 `memory_search` 가 같은 순위를 쓴다. 임베딩을 끄거나 실패하면 키워드만으로 고른다.

**범위 외**: 임베딩 서비스의 배포와 서버 지연 측정(원격 검증 목록), cross-encoder 재순위(ADR 「대안 기각」), 먼저 살펴보기 연결(단계 4).

## 컨텍스트

**근거 문서**: `backend/docs/adr/ADR-20261015-memory-hybrid-selection.md` 의 「결정」(RRF k=60, 관련 없음, 임베딩 실패 시 키워드, 벡터 DB 없음), `docs/features/memory.md` 의 「질문과 관련된 기억 구역」, 「기억 검색의 구현 계약」, 「합성 측정」(`memoryEmbeddingEval`).

- 이미 있는 `KeywordScorer`, `RelevanceCandidate`, `MemoryRelevance`, `MemoryRelevanceProperties`(`semanticMinSimilarity`, `rrfK`), `MemorySearchSection` 과 `ContextSelection.SEARCH_KEYWORD`, `INDEX_KEYWORD`, `MemoryRelevanceSearch`, phase 01 의 `EmbeddingClient`, `MemoryEmbeddingProperties`, `MemoryEmbeddingRepository`, `MemoryEmbedding` 을 쓴다.
- 조립을 부르는 `ChatTurnRunner` 와 `AgentRunner` 는 `@Transactional` 이 아니다. `MemoryService.searchFor` 는 2초 제한의 독립 트랜잭션이므로, `memory_search` 의 질문 임베딩은 그 트랜잭션 밖(`McpToolService.searchMemory`)에서 먼저 한다.
- 측정 태스크 본보기: `backend/build.gradle.kts` 의 `memoryJudgeEval`(tag `model-eval`, 환경 변수가 없으면 건너뛴다, 기본 `test` 는 `excludeTags("mysql", "model-eval")`).

## 의도 메모

- 벡터를 매 turn 데이터베이스에서 다 읽지 않는다. 5,000건 × 1024차원이면 약 20MB 다. 프로세스 안 캐시에 `(memoryId → 지문, 모델, 벡터)` 를 두고, 후보의 지문이 같을 때만 쓴다. 없는 것만 한 번의 `findByMemoryIdIn` 으로 읽는다.
- 서버 인스턴스는 하나다. 캐시를 여러 프로세스가 맞추는 장치를 두지 않는다. 채우는 작업이 줄을 바꾸거나 지우면 그 번호를 캐시에서 뺀다.
- 의미 쪽 관련 판정의 기본값(0.5)은 모델마다 척도가 달라 `memoryEmbeddingEval` 의 결과로 정한다. CI 는 실제 모델 없이 가짜 벡터로 순위 계산만 검사한다.

## 작업 항목

### 1. 질문 임베딩 `memory/application/relevance/QueryEmbedder.java` 신규

- `Optional<float[]> embed(String question)`: `enabled()` 가 거짓이면 빈 값이다. `EmbeddingClient.embed(List.of(질문), queryTimeout)` 이 `EmbeddingUnavailableException` 을 던지면 빈 값이고 예외 클래스 이름만 로그에 남긴다.

### 2. 벡터 캐시 `memory/application/relevance/MemoryVectorCache.java` 신규

- `Map<Long, float[]> vectorsFor(List<RelevanceCandidate> candidates, String model)`: 후보의 지금 글로 phase 01 과 같은 지문을 계산해, 캐시나 `findByMemoryIdIn` 의 줄 가운데 지문과 모델이 같은 것만 낸다. 지문 계산은 `MemoryEmbeddingRefresher` 와 같은 메서드를 쓰도록 그 계산을 이 패키지의 한 곳(`EmbeddingText`, 신규)으로 옮긴다.
- `void evict(Long memoryId)`: `MemoryEmbeddingRefresher` 가 줄을 넣거나 바꾸거나 지운 뒤 부른다.

### 3. 합치기 `memory/application/relevance/MemoryRelevance.java` 수정

- 키워드만 쓰는 지금의 `RelevanceResult score(String question, List<RelevanceCandidate> candidates)` 옆에 질문 벡터를 받는 `score(String question, Optional<float[]> queryVector, List<RelevanceCandidate> candidates)` 를 더한다. `RelevanceResult(String selector, List<RelevanceScore> ranked)` 와 `RelevanceScore(Long memoryId, Double keyword, Double semantic, double fused)` 는 이미 있다.
- 질문 벡터가 있으면: 벡터가 있는 후보의 코사인이 `semanticMinSimilarity` 이상인 것을 의미 쪽 목록으로, `KeywordScorer` 결과를 키워드 쪽 목록으로 두고 `fused = Σ 1 / (rrfK + 순위)` 로 합친다. 한쪽에만 있는 항목은 그쪽 항만 더한다. selector 는 `search-hybrid-v1` 이다.
- 질문 벡터가 없으면 키워드 결과만 쓰고 `fused = keyword` 다. selector 는 `search-keyword-v1` 이다.
- 정렬은 `fused` 내림차순, 같으면 번호 오름차순이다.
- selector 문자열 상수는 이 클래스가 갖고, `ContextSelection` 에 같은 값의 `SEARCH_HYBRID = "search-hybrid-v1"`, `INDEX_HYBRID = "index-hybrid-v1"` 를 더한다. 같은 값인지는 아래 테스트가 단언한다.

### 4. 연결

- `MemorySearchSection` 이 `KeywordScorer` 대신 `MemoryRelevance` 를 쓴다. 근거는 `new ContextSelection(selector, rank, keyword, semantic, fused)` 이다. 색인의 점수 있는 항목은 hybrid 면 `index-hybrid-v1`, 아니면 `index-keyword-v1` 이다.
- `MemoryRelevanceSearch` 가 질문 벡터를 인자로 받아 같은 `MemoryRelevance` 계산을 쓴다. `McpToolService.searchMemory` 가 `QueryEmbedder.embed` 를 `MemoryService.searchFor` 를 부르기 전에 부르고 그 결과를 넘긴다. `MemoryService.searchFor` 의 시그니처에 `Optional<float[]> queryVector` 를 더한다. 파일 상한을 지킨다.
- `MemoryEmbeddingRefresher` 가 쓰고 지운 번호를 `MemoryVectorCache.evict` 로 뺀다.
- `ContextSelection` 의 Javadoc 목록에 새 규칙 둘의 뜻을 적는다.

### 5. 측정 태스크

- `backend/src/test/java/com/bifos/assistant/context/eval/MemoryEmbeddingEvalTest.java` 신규, `@Tag("embedding-eval")`. 환경 변수 `MEMORY_EMBEDDING_BASE_URL` 과 `MEMORY_EMBEDDING_MODEL` 이 없으면 `Assumptions.abort` 로 건너뛴다. 있으면 사례 묶음의 항목을 실제 `OpenAiCompatibleEmbeddingClient` 로 임베딩해 `hybrid` 모드로 조립하고, `factsOn`(키워드만)과 나란히 범주마다 지표를 `backend/build/reports/memory-eval/embedding-<모델 이름>.md` 에 쓴다. 질문 임베딩 지연의 p50 과 p95 도 쓴다. 게이트는 걸지 않는다.
- `backend/build.gradle.kts`: `memoryEmbeddingEval` 태스크를 `memoryJudgeEval` 과 같은 모양으로 더하고(`includeTags("embedding-eval")`, `outputs.upToDateWhen { false }`), 기본 `test` 의 `excludeTags` 에 `"embedding-eval"` 을 더한다.

### 6. 이 phase 를 검증하는 테스트

- `backend/src/test/java/com/bifos/assistant/memory/relevance/MemoryRelevanceTest.java` 신규. 가짜 벡터로:
  - 키워드는 없고 코사인만 높은 항목이 hybrid 에서 점수를 받고, 키워드만 쓰면 받지 못한다.
  - 두 목록 모두에 있는 항목이 한쪽에만 있는 항목보다 위다. 값은 `1/(60+r1) + 1/(60+r2)` 와 같다.
  - 코사인이 임계값 아래이고 키워드도 없는 항목은 점수가 없다.
  - 지문이 다른(본문을 고친) 항목의 낡은 벡터를 쓰지 않는다.
  - selector 상수가 `ContextSelection.SEARCH_HYBRID`, `SEARCH_KEYWORD` 와 같다.
- `backend/src/test/java/com/bifos/assistant/context/ContextAssemblerHybridTest.java` 신규(`@BackendIntegrationTest`, 가짜 `EmbeddingClient`):
  - 정상: 본문과 낱말이 겹치지 않지만 벡터가 가까운 항목이 `MEMORY_SEARCH` 에 실리고 근거가 `search-hybrid-v1` 이며 세 점수가 모두 있다.
  - 실패: 클라이언트가 `EmbeddingUnavailableException` 을 던지면 조립이 실패하지 않고 키워드로 고른 항목이 실리며 근거가 `search-keyword-v1` 이다(ADR 「적용 범위」 의 「임베딩 서비스를 끈 상태에서 키워드만으로 답하는 테스트」).
  - 꺼짐: `enabled()` 가 거짓이면 클라이언트를 부르지 않는다.
- `McpMemorySearchToolTest` 에 가짜 클라이언트로 본문 낱말이 없는 항목을 의미로 찾는 경우와, 클라이언트 실패 때 키워드 결과를 내는 경우를 더한다.
- `MemorySearchTest` 의 권한 경계 단언은 가짜 벡터를 모든 항목에 채운 상태에서도 0건이어야 한다. 경계 밖 항목에 질문과 같은 벡터를 넣어 확인한다.

## 검증

```bash
cd backend && ./gradlew test --tests '*MemoryRelevanceTest' --tests '*ContextAssemblerHybridTest' --tests '*McpMemorySearchToolTest' --tests '*MemorySearchTest' --tests '*MemoryRecallEvalTest'
cd backend && ./gradlew memoryEmbeddingEval
cd backend && ./gradlew test
cd backend && ./gradlew archTest spotlessCheck
scripts/checks/check-mysql-migration.sh
node scripts/checks/check-file-length.mjs
```

기대: 모두 통과. 환경 변수 없이 돈 `memoryEmbeddingEval` 은 건너뛰고 성공으로 끝난다. `MemoryRecallEvalTest` 의 `factsOn` 값은 지금 기준 파일과 같다(CI 에서는 임베딩이 꺼져 있다).

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/memory/application/relevance/QueryEmbedder.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/memory/application/relevance/MemoryVectorCache.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/memory/application/relevance/EmbeddingText.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/memory/application/relevance/MemoryRelevance.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/memory/application/embedding/MemoryEmbeddingRefresher.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/memory/application/MemoryRelevanceSearch.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/memory/application/MemoryService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/context/MemorySearchSection.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/context/ContextSelection.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/mcp/application/McpToolService.java` | 수정 |
| `backend/build.gradle.kts` | 수정 |
| `backend/src/test/java/com/bifos/assistant/memory/relevance/MemoryRelevanceTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/context/ContextAssemblerHybridTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/context/eval/MemoryEmbeddingEvalTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/mcp/McpMemorySearchToolTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/memory/MemorySearchTest.java` | 수정 |
