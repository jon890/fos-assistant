# Phase 04. 임베딩 저장과 OpenAI 호환 클라이언트, 채우는 작업

**Execution profile**: standard

## 목표

임베딩 대상 항목의 벡터를 `memory_embedding` 에 채우고 낡은 줄을 지운다. 임베딩 서비스 주소가 없으면 아무것도 하지 않는다.

**범위 외**: 벡터를 점수에 쓰는 것(phase 05). 임베딩 서비스를 띄우는 것(운영 저장소).

## 컨텍스트

**근거 문서**: `backend/docs/data-schema.md` 의 「memory_embedding」 과 「지울 때」, `backend/docs/adr/ADR-20261015-memory-hybrid-selection.md` 의 「결정」(OpenAI 호환 `POST /v1/embeddings`, 민감 항목 제외, 외부 API 금지)과 「구현 순서」(주소가 없으면 꺼짐, 지우는 시점).

- 마이그레이션 규칙: `backend/docs/data-schema.md` 의 「마이그레이션 작성 규칙」. 버전은 `date -u +%Y%m%d%H%M%S` 로 정한다. `CREATE TABLE` 끝은 `ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci` 다(`test/unit/migration-collation.test.ts`).
- 엔티티 위치: `backend/CLAUDE.md` 의 「memory의 영속 타입」. 새 엔티티는 `memory.domain.entity` 에 둔다(`ArchitectureRules.ENTITIES_IN_OWNED_ENTITY_PACKAGE`). 기존 아홉 타입 목록(`OWNED_PERSISTENT_TYPES`)에는 넣지 않는다.
- 엔티티 형식은 `backend/src/main/java/com/bifos/assistant/memory/domain/entity/Memory.java` 를 따른다(fluent getter, `@NoArgsConstructor(access = PROTECTED)`). 저장소는 `memory/infra/MemoryRepository.java` 옆에 둔다.
- HTTP 클라이언트 형식: `backend/src/main/java/com/bifos/assistant/hermes/HttpHermesRunsClient.java`(`RestClient.builder()` 와 `SimpleClientHttpRequestFactory` 의 connect/read timeout). `RestClient.Builder` 는 자동 구성되지 않는다(`backend/CLAUDE.md`). Jackson 은 `tools.jackson` 이다.
- 주기 작업 형식: `feedback/application/DecisionFeedbackCleaner.java` 의 `@Scheduled`. 테스트 profile(`backend/src/test/resources/application-test.yml`)은 주기 작업을 끈다.
- 삭제 분류: `backend/src/test/resources/purge/tables.tsv`(형식은 그 파일 머리), `purge/fixture.sql`, `PurgeClassificationTest`, `PurgeCoverageMysqlTest`. 새 테이블이 분류되지 않으면 `PurgeClassificationTest` 가 실패한다.
- `MemoryService.java` 는 499줄이고 상한은 500줄이다. `delete` 에 한 줄을 더하려면 다른 곳의 줄을 새 클래스로 옮긴다.

## 의도 메모

- 외래 키를 걸지 않는다. 이 저장소의 memory 계열 테이블은 외래 키 대신 서비스가 지운다(`memory_capture`, `memory_revision`). H2 테스트 스키마는 엔티티로 만들어 DB 의 `ON DELETE CASCADE` 가 없다.
- 모델마다 줄을 두지 않는다. 한 번에 한 모델만 쓰고, 모델을 바꾸면 다시 채운다.
- 임베딩 서비스가 실패하면 그 주기를 멈추고 다음 주기에 다시 한다. 앱 기동과 대화를 막지 않는다. 로그에는 예외 클래스 이름과 건수만 남긴다. 기억 글을 로그에 남기지 않는다.
- API key 설정을 두지 않는다. 임베딩 서비스는 자체 서버의 로컬 모델이다(ADR).

## 작업 항목

### 1. 마이그레이션 `backend/src/main/resources/db/migration/V<UTC 작성 시각>__memory_embedding.sql` 신규

- `memory_id BIGINT NOT NULL PRIMARY KEY`, `model VARCHAR(128) NOT NULL`, `dimensions INT NOT NULL`, `text_fingerprint BINARY(32) NOT NULL`, `vector BLOB NOT NULL`, `created_at DATETIME(6) NOT NULL`, `updated_at DATETIME(6) NOT NULL`.
- 외래 키와 다른 색인을 두지 않는다. DDL 만 담는다.

### 2. 엔티티와 저장소

- `memory/domain/entity/MemoryEmbedding.java` 신규. 칸 길이를 마이그레이션과 글자까지 맞춘다. `float[] vector()` 는 `ByteBuffer` 의 little-endian 으로 풀고, `static MemoryEmbedding of(Long memoryId, String model, byte[] fingerprint, float[] vector, Instant now)` 와 `void replace(String model, byte[] fingerprint, float[] vector, Instant now)` 를 둔다. `toString` 은 `memoryId` 와 `model` 만 낸다.
- `memory/infra/MemoryEmbeddingRepository.java` 신규: `JpaRepository<MemoryEmbedding, Long>`. `List<MemoryEmbedding> findByMemoryIdIn(Collection<Long> ids)`, `void deleteByMemoryId(Long memoryId)`. 저장소 메서드라 `RepositoryQueryMysqlTest` 가 스스로 찾는다. 인자를 만들지 못하면 `RepositoryQuerySweep` 에 값을 더한다.

### 3. 설정 `memory/application/embedding/MemoryEmbeddingProperties.java` 신규

- prefix `assistant.memory.embedding`. `String baseUrl`(기본 빈 값), `String model`(기본 빈 값), `Duration queryTimeout`(기본 400ms), `Duration batchTimeout`(기본 30s), `int batchSize`(기본 16), `int maxBatchesPerRun`(기본 16), `int maxTextChars`(기본 4000), `Duration refreshDelay`(기본 60s).
- `boolean enabled()`: `baseUrl` 과 `model` 이 모두 비어 있지 않을 때 참이다.
- `application.yml` 에 같은 키를 적는다. `base-url` 과 `model` 은 `${MEMORY_EMBEDDING_BASE_URL:}` 와 `${MEMORY_EMBEDDING_MODEL:}` 로 받는다. 운영 값은 이 저장소에 적지 않는다.

### 4. 클라이언트

- `memory/application/embedding/EmbeddingClient.java` 신규 interface: `List<float[]> embed(List<String> inputs, Duration timeout)`. 실패와 시간 초과는 `EmbeddingUnavailableException`(같은 패키지, 신규, unchecked)이다.
- `memory/infra/OpenAiCompatibleEmbeddingClient.java` 신규. `POST {baseUrl}/v1/embeddings` 에 `{"model": <model>, "input": [...]}` 를 보낸다. 응답 `data[].embedding` 을 `data[].index` 순서로 맞춰 낸다. 개수가 다르거나 차원이 섞이면 `EmbeddingUnavailableException` 이다. 호출마다 그 timeout 의 read timeout 을 쓴다.
- `enabled()` 가 거짓이면 빈이 있어도 호출하지 않는다. 부르는 쪽이 먼저 `enabled()` 를 본다.

### 5. 채우는 작업 `memory/application/embedding/MemoryEmbeddingRefresher.java` 신규

- `@Scheduled(fixedDelayString = "${assistant.memory.embedding.refresh-delay}")` 의 `refresh()` 는 `enabled()` 가 거짓이면 바로 끝난다. 테스트는 `refreshOnce()` 를 직접 부른다.
- 대상은 `ACCEPTED`, `retrieval=SEARCH`, `entry_type` 이 `SOURCE` 가 아니고, 민감도 `NORMAL`, `content_key_id` 가 null 인 항목이다. 대상을 고르는 Specification 이나 쿼리를 저장소 밖에서 만들면 `RepositoryQueryMysqlTest` 에 직접 더한다.
- 임베딩할 글은 `title + "\n" + content` 를 `maxTextChars` 에서 자른 것이다. 지문은 `model + "\n" + 그 글` 의 SHA-256 이다.
- 한 번에: (1) 대상이 아닌 항목의 줄과 `memory` 에 없는 번호의 줄을 지운다. (2) 줄이 없거나 지문이 다른 대상을 번호 순으로 `batchSize` 씩, 최대 `maxBatchesPerRun` 묶음까지 `batchTimeout` 으로 임베딩해 넣거나 바꾼다. 묶음 하나가 실패하면 그 주기를 끝낸다.
- 트랜잭션은 묶음마다 따로 연다. 임베딩 호출 동안 트랜잭션을 열어 두지 않는다.

### 6. 지울 때

- `MemoryService.delete` 가 `memories.delete(memory)` 와 같은 트랜잭션에서 `MemoryEmbeddingRepository.deleteByMemoryId` 를 부른다. 파일 상한을 지킨다.

### 7. 삭제 분류

- `purge/tables.tsv` 에 `memory_embedding|USER|memory_id|GAP|KEEP|KEEP|memory_id로 기억 항목에 속한다. 항목을 지우면 같은 트랜잭션에서 지운다.` 를 이름 순 자리에 넣는다.
- `purge/fixture.sql` 에 기존 memory fixture 항목을 가리키는 줄 하나를 넣는다.

### 8. 이 phase 를 검증하는 테스트

- `backend/src/test/java/com/bifos/assistant/memory/embedding/OpenAiCompatibleEmbeddingClientTest.java` 신규. `com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)` 대역을 쓴다(본보기 `context/eval/MemoryJudgeHarnessTest.java`). 정상(요청 본문의 model 과 input, `index` 가 뒤섞인 응답의 순서 맞춤), 500 응답, 개수 불일치, timeout 보다 늦은 응답이 모두 `EmbeddingUnavailableException` 이다.
- `backend/src/test/java/com/bifos/assistant/memory/embedding/MemoryEmbeddingRefresherTest.java` 신규(`@BackendIntegrationTest`, 가짜 `EmbeddingClient` 빈). 일반 항목은 줄이 생기고 민감 항목, 암호화한 항목, `PROPOSED`, `ARCHIVE`, `SOURCE` 는 생기지 않는다. 본문을 고치면 다음 실행에 지문이 바뀐다. 민감으로 바꾸면 다음 실행에 줄이 지워진다. 클라이언트가 실패하면 그 주기의 나머지 묶음을 부르지 않는다. `enabled()` 가 거짓이면 클라이언트를 한 번도 부르지 않는다.
- `MemoryServiceTest` 에 항목을 지우면 그 벡터 줄도 지워지는 경우를 더한다.
- `PurgeClassificationTest` 는 새 tsv 줄로 통과해야 한다. `PurgeCoverageMysqlTest` 와 `MysqlMigrationTest` 는 MySQL 검사에서 돈다.

## 검증

```bash
cd backend && ./gradlew test --tests '*OpenAiCompatibleEmbeddingClientTest' --tests '*MemoryEmbeddingRefresherTest' --tests '*MemoryServiceTest' --tests '*PurgeClassificationTest'
cd backend && ./gradlew test
cd backend && ./gradlew archTest spotlessCheck
scripts/checks/check-mysql-migration.sh
node scripts/checks/check-migration-versions.mjs origin/main
node --test test/unit/migration-collation.test.ts
node scripts/checks/check-file-length.mjs
```

기대: 모두 통과. `check-mysql-migration.sh` 가 `MysqlMigrationTest`, `PurgeCoverageMysqlTest`, `RepositoryQueryMysqlTest` 를 MySQL 에서 돈다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/resources/db/migration/V*__memory_embedding.sql` | 신규 |
| `backend/src/main/java/com/bifos/assistant/memory/domain/entity/MemoryEmbedding.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/memory/infra/MemoryEmbeddingRepository.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/memory/infra/OpenAiCompatibleEmbeddingClient.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/memory/application/embedding/MemoryEmbeddingProperties.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/memory/application/embedding/EmbeddingClient.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/memory/application/embedding/EmbeddingUnavailableException.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/memory/application/embedding/MemoryEmbeddingRefresher.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/memory/application/MemoryService.java` | 수정 |
| `backend/src/main/resources/application.yml` | 수정 |
| `backend/src/test/resources/purge/tables.tsv` | 수정 |
| `backend/src/test/resources/purge/fixture.sql` | 수정 |
| `backend/src/test/java/com/bifos/assistant/memory/embedding/OpenAiCompatibleEmbeddingClientTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/memory/embedding/MemoryEmbeddingRefresherTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/memory/MemoryServiceTest.java` | 수정 |
