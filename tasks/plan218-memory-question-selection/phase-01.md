# Phase 01. 한국어 키워드 점수

**Execution profile**: standard

## 목표

질문과 기억 항목의 키워드 관련도를 앱 안에서 계산하는 점수기를 만든다. 뒤 phase 의 조립과 `memory_search` 가 같은 점수를 쓴다.

**범위 외**: 조립과 도구에 연결하는 것(phase 02, 03), 의미 점수와 RRF(phase 05).

## 컨텍스트

**근거 문서**: `backend/docs/adr/ADR-20261015-memory-hybrid-selection.md` 의 「결정」 표(키워드 점수, 관련 없음), `docs/features/memory.md` 의 「질문과 관련된 기억 구역」.

- 패키지 방향: `backend/src/test/java/com/bifos/assistant/architecture/TopLevelPackageOrder.java` 에서 `memory` 는 `context` 아래다. 점수기는 `ContextAssembler`(context)와 `memory_search`(memory)가 함께 쓰므로 `memory` 에 둔다. `memory` 는 `context` 를 import 하지 못한다.
- 설정 record 는 `backend/src/main/java/com/bifos/assistant/context/ContextProperties.java` 의 형식을 따른다(`@Validated @ConfigurationProperties`, compact constructor 의 기본값). `AssistantApplication` 이 `@ConfigurationPropertiesScan` 이라 등록은 따로 하지 않는다.
- 판단의 분류를 enum 으로 굳히지 않는다(#310 의 2026-10-16 댓글). 점수기는 숫자만 내고 「어떤 종류의 기억인가」 를 나누지 않는다.

## 의도 메모

- MySQL ngram FULLTEXT 는 토큰 크기를 서버 시작 때만 정하고 한 글자 질의를 놓쳐 쓰지 않는다(ADR 「대안 기각」).
- 색인을 저장하지 않는다. 가족 규모(수천 건)에서는 호출마다 후보를 쪼개 세는 것으로 충분하다. 5,000건에서 느리면 phase 02 의 측정으로 드러난다.
- 관련 판정은 BM25 점수의 절댓값이 아니라 「질문 토큰 가운데 항목에 있는 토큰의 비율」 로 한다. BM25 는 후보 집합마다 척도가 달라 임계값을 고정할 수 없다.

## 작업 항목

### 1. `memory/application/relevance/KoreanBigramTokenizer.java` 신규

- `static Set<String> tokens(String text)`: `Normalizer.normalize(text, NFKC)` 뒤 `toLowerCase(Locale.ROOT)`. `Character.isLetterOrDigit` 가 아닌 글자에서 낱말을 가른다. 한 글자 낱말은 그 글자 하나를, 두 글자 이상은 겹치는 두 글자 묶음을 모두 토큰으로 낸다. null 과 빈 글은 빈 집합이다.
- `static Map<String, Integer> termCounts(String text)`: 같은 규칙으로 토큰마다 나온 횟수를 낸다. BM25 의 문서 길이는 이 값의 합이다.
- `%`, `_`, 역슬래시는 낱말을 가르는 글자일 뿐 특별한 뜻이 없다.

### 2. `memory/application/relevance/KeywordScorer.java` 신규

- `List<KeywordScore> score(String question, List<RelevanceCandidate> candidates, double minCoverage)`.
- `RelevanceCandidate(Long memoryId, String title, String plainBody)` record 를 같은 패키지에 둔다. `plainBody` 는 민감 항목과 암호화한 항목이면 null 이다. 점수기는 null 본문을 제목만으로 센다. 암호문을 받는 길을 만들지 않는다.
- 문서는 `title + " " + plainBody` 다. BM25 는 k1 = 1.2, b = 0.75, idf = `ln(1 + (N - df + 0.5) / (df + 0.5))`, N 과 평균 길이는 넘겨받은 후보 전체로 센다.
- coverage = (질문 토큰 가운데 문서에 있는 토큰 수) / (질문 토큰 수). coverage 가 `minCoverage` 이상인 후보만 `KeywordScore(Long memoryId, double bm25, double coverage)` 로 낸다. 질문 토큰이 없으면 빈 목록이다.
- 결과는 bm25 내림차순, 같으면 memoryId 오름차순이다.

### 3. `memory/application/relevance/MemoryRelevanceProperties.java` 신규

- prefix `assistant.memory.relevance`. `double keywordMinCoverage`(기본 0.3), `double semanticMinSimilarity`(기본 0.5, phase 05 가 쓴다), `int rrfK`(기본 60). 범위 검사는 `@DecimalMin`/`@DecimalMax`(0 이상 1 이하)와 `@Min(1)` 이다.
- `backend/src/main/resources/application.yml` 에 같은 키와 기본값을 적는다. 기존 `assistant.context` 블록 옆에 둔다.

### 4. 이 phase 를 검증하는 `backend/src/test/java/com/bifos/assistant/memory/relevance/KeywordScorerTest.java` 신규

- 토큰: 「우리 딸 알레르기」 가 `우리`, `딸`, `알레`, `레르`, `르기` 를 낸다. 전각 글자와 대문자가 NFKC 와 소문자로 같은 토큰이 된다. `%_\` 만 있는 글은 빈 집합이다.
- 정상: 질문 「딸 알레르기 뭐였지」 에 본문이 「딸은 땅콩 알레르기가 있다」 인 항목이 제목만 같은 낱말을 가진 다른 항목보다 위에 온다. 본문에만 낱말이 있는 항목도 점수를 받는다.
- 실패와 경계: 공통 토큰이 coverage 아래인 항목은 빠진다. `plainBody` 가 null 인 항목은 본문의 낱말로 점수를 받지 못한다. 점수가 같으면 번호 오름차순이다. 빈 질문은 빈 목록이다.
- `MemoryRelevanceProperties` 의 기본값과 범위 밖 값의 거절을 `ApplicationContextRunner` 없이 생성자로 확인한다.

## 검증

```bash
cd backend && ./gradlew test --tests '*KeywordScorerTest'
cd backend && ./gradlew archTest
cd backend && ./gradlew spotlessCheck
```

기대: 모두 통과. `archTest` 는 새 패키지가 `context` 를 import 하지 않는지 확인한다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/memory/application/relevance/KoreanBigramTokenizer.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/memory/application/relevance/KeywordScorer.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/memory/application/relevance/KeywordScore.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/memory/application/relevance/RelevanceCandidate.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/memory/application/relevance/MemoryRelevanceProperties.java` | 신규 |
| `backend/src/main/resources/application.yml` | 수정 |
| `backend/src/test/java/com/bifos/assistant/memory/relevance/KeywordScorerTest.java` | 신규 |
