# Phase 03. 평문으로 남은 민감 줄을 기동할 때 옮기고 문서를 맞춘다

**Execution profile**: standard

## 목표

기동할 때 `memory` 와 `memory_revision` 에서 평문으로 남은 민감 줄을 찾아 암호화한다.
지금 운영에는 민감 줄이 없지만, 옛 판으로 되돌렸다 다시 올린 경우와 손으로 넣은 줄을 이 보정이 맡는다.
구현한 것을 `docs/` 에 적는다.

**범위 외**: 옛 key 로 쓴 줄을 새 key 로 다시 쓰는 일.

## 컨텍스트

- phase 02 까지 끝나 있다. `Memory.sealed()`, `Memory.contentBinding()`, `MemoryRevision.contentBinding()`, `MemoryContentCipher.seal(plain, binding)`, 두 엔티티의 `contentKeyId` 가 있다
- 저장소는 `memory/infra/MemoryRepository.java`(`JpaRepository<Memory, Long>`)와 `memory/infra/MemoryRevisionRepository.java`(`JpaRepository<MemoryRevision, MemoryRevisionId>`)다
- `MemoryRevision` 은 `Persistable` 이고 `isNew()` 가 `!persisted` 다. 읽어 온 판은 `persisted` 가 참이라 `save` 가 고치기로 돈다
- `@Transactional` 은 `application` 패키지에만 붙인다(`ArchitectureRules.TRANSACTIONAL_ONLY_IN_APPLICATION`)
- 문서가 지금 적고 있는 것
  - `docs/code-architecture.md` 의 「Memory」 절과 그 아래 「다음」 목록에 「민감 항목 본문의 암호화와 완전 삭제」 가 미구현으로 있다
  - `docs/data-schema.md` 의 「memory」 와 「memory_revision」 칸 표에 `content_key_id` 가 없다. 「지울 때」 절이 「본문은 그 표에 남는다」 고 적는다
  - `docs/adr/ADR-052-memory-는-collection-종류-꺼내는-방식-민감도-판-출처를-가진다.md` 의 「대체된 부분」 이 「없다」 로 시작한다

**근거 문서**: `docs/adr/ADR-054-민감-memory-본문은-저장할-때-암호화하고-key-는-환경-변수로-받는다.md` 의 「적용 범위」 둘째 표

## 의도 메모

- Flyway 의 Java 마이그레이션으로 하지 않는다. 마이그레이션은 Spring 설정을 받지 못해 key 를 읽을 수 없고, key 가 나중에 들어오는 설치에서는 한 번 지나간 마이그레이션이 다시 돌지 않는다
- 판 번호와 `updated_at` 을 건드리지 않는다. 뜻이 바뀐 것이 아니라 저장 모양만 바뀐다
- key 가 없으면 옮기지 않고 줄 수만 경고한다. 기동을 막지 않는다(ADR-054)

## 작업 항목

### 1. 엔티티에 제자리 암호화 메서드

- `Memory.sealInPlace(StoredContent body)`: `content` 와 `contentKeyId` 만 바꾼다. 이미 `sealed()` 면 `IllegalStateException`
- `MemoryRevision.sealInPlace(StoredContent body)`: 같은 일을 한다. Javadoc: 「남긴 판의 뜻은 바뀌지 않는다. 평문으로 남은 민감 판의 저장 모양만 바꾼다(ADR-054).」

### 2. 저장소 질의

- `MemoryRepository`: `List<Memory> findBySensitivityAndContentKeyIdIsNull(MemorySensitivity sensitivity);`
- `MemoryRevisionRepository`: `List<MemoryRevision> findBySensitivityAndContentKeyIdIsNull(MemorySensitivity sensitivity);`

### 3. `backend/src/main/java/com/bifos/assistant/memory/application/MemoryContentBackfill.java`

`@Component`, `@Slf4j`, `@RequiredArgsConstructor`, `ApplicationRunner` 를 구현한다.

- `@Transactional public int sealPlaintext()`: 두 저장소에서 `SENSITIVE` 이고 `contentKeyId` 가 빈 줄을 읽는다
  - `cipher.enabled()` 가 거짓이면 아무것도 바꾸지 않는다. 줄이 하나라도 있으면 `log.warn("평문으로 남은 민감 줄이 있다 memory={} revision={}", ...)` 로 줄 수만 남기고 0 을 돌려준다
  - 참이면 줄마다 `cipher.seal(row.content(), row.contentBinding())` 로 `sealInPlace` 하고 저장한다. 바꾼 줄 수를 `log.info` 로 남기고 돌려준다
- `run(ApplicationArguments args)` 는 `sealPlaintext()` 를 부른다. 같은 클래스 안에서 부르면 트랜잭션이 걸리지 않으므로, `@Transactional` 을 `run` 에도 붙인다

### 4. 이 phase 를 검증하는 `backend/src/test/java/com/bifos/assistant/memory/MemoryContentBackfillTest.java`

`@SpringBootTest @ActiveProfiles("test")`. `@BeforeEach` 에서 `MemoryRepository` 와 `MemoryRevisionRepository` 를 비운다. `JdbcTemplate` 으로 줄을 직접 넣는다. 두 표의 필수 칸은 `Memory.java` 와 `MemoryRevision.java` 의 `nullable = false` 칸을 읽고 모두 채운다.

| 입력 | 기대 |
| --- | --- |
| `memory` 에 `SENSITIVE` 평문 한 줄(`평문-표식-4410`, `USER`, 주인 1), `NORMAL` 평문 한 줄. `memory_revision` 에 `SENSITIVE` 평문 한 줄(`평문-표식-4411`) | `sealPlaintext()` 가 2 를 돌려준다. 두 민감 줄의 `content` 가 표식을 담지 않고 `content_key_id` 가 `test-1` 이다. `NORMAL` 줄은 그대로다. `memory.revision` 과 `updated_at` 이 그대로다 |
| 그 뒤 `MemoryService.contentOf` 로 민감 `memory` 줄을 읽는다 | `평문-표식-4410` 이다 |
| `sealPlaintext()` 를 한 번 더 부른다 | 0 이다. 암호문이 바뀌지 않는다 |

`backend/src/test/java/com/bifos/assistant/memory/MemoryEncryptionDisabledTest.java` 에 경우 하나를 더한다. 평문 민감 줄을 넣고 `sealPlaintext()` 를 부르면 0 이고 그 줄이 그대로다.

### 5. `docs/code-architecture.md`

「Memory」 절의 목록에 아래 뜻을 더한다. 문장은 그 절의 문체에 맞춘다.

- 민감 항목의 본문은 `memory.content` 와 `memory_revision.content` 에 암호문으로 저장한다. `content_key_id` 가 key 를 적는다
- 본문을 밖으로 내는 자리는 `MemoryService.contentOf` 를 거친다. Memory 목록은 민감 본문을 싣지 않는다
- key 가 없으면 민감 항목의 저장과 읽기를 `MEMORY_ENCRYPTION_UNAVAILABLE` 로 거절한다
- 기동할 때 `MemoryContentBackfill` 이 평문으로 남은 민감 줄을 암호화한다

클래스 표에 `memory.application.MemoryContentCipher` 와 `memory.application.MemoryContentBackfill` 을 더한다.
「다음」 목록의 「민감 항목 본문의 암호화와 완전 삭제」 를 「민감 항목 본문의 완전 삭제」 로 고친다.
근거 줄에 ADR-054 링크를 더한다.

### 6. `docs/data-schema.md`

- 「memory」 칸 표의 `content` 아래에 줄을 더한다: `content_key_id` | VARCHAR(32) NULL | 본문을 암호화한 key 의 id. 비어 있으면 `content` 는 평문이다. `SENSITIVE` 인 줄은 늘 채워져 있다
- `content` 의 뜻을 「본문. 민감 항목이면 암호문이다」 로 고친다
- 「memory_revision」 칸 표에 같은 줄을 더하고 `content` 를 「그 판의 본문. 그때 민감 항목이었으면 암호문이다」 로 고친다
- 「지울 때」 의 Memory 문단에 한 문장을 더한다: 민감 항목의 판은 암호문으로 남는다

### 7. ADR 과 목록

- `docs/adr/ADR-052-memory-는-collection-종류-꺼내는-방식-민감도-판-출처를-가진다.md` 의 「대체된 부분」 을 고친다. 「[ADR-054](ADR-054-민감-memory-본문은-저장할-때-암호화하고-key-는-환경-변수로-받는다.md) 가 「민감 항목의 본문은 아직 평문으로 저장한다」 를 대체한다. 민감 본문은 암호화해 저장한다.」 를 앞에 두고, 지금 있는 ADR-015 설명은 그 뒤에 남긴다
- `docs/adr/ADR-054-민감-memory-본문은-저장할-때-암호화하고-key-는-환경-변수로-받는다.md` 의 `status` 줄에서 「아직 구현 전이다.」 를 지운다
- `docs/adr/INDEX.md` 의 ADR-054 줄에서 「아직 구현 전이다」 를 지우고, ADR-052 줄의 상태에 「민감 본문은 평문이라는 부분은 ADR-054 가 대체한다」 를 더한다

## 검증

```bash
# cwd: 저장소 root
(cd backend && ./gradlew test --tests '*MemoryContentBackfillTest' --tests '*MemoryEncryptionDisabledTest')
(cd backend && ./gradlew test)
(cd backend && ./gradlew qualityCheck)
node test/e2e/run.ts
scripts/check-public-safe.sh
grep -n "content_key_id" docs/data-schema.md
grep -n "ADR-054" docs/code-architecture.md docs/adr/ADR-052-memory-는-collection-종류-꺼내는-방식-민감도-판-출처를-가진다.md
! grep -n "암호화와 완전 삭제" docs/code-architecture.md
```

- 모두 종료 코드 0. `grep` 둘은 줄이 나와야 하고 마지막 줄은 나오지 않아야 한다
- `node test/e2e/run.ts` 는 `./gradlew test` 뒤에 돌린다. key 없이 뜨는 backend 에서 보정이 기동을 막지 않는지 본다

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/memory/domain/Memory.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/memory/domain/MemoryRevision.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/memory/infra/MemoryRepository.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/memory/infra/MemoryRevisionRepository.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/memory/application/MemoryContentBackfill.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/memory/MemoryContentBackfillTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/memory/MemoryEncryptionDisabledTest.java` | 수정 |
| `docs/code-architecture.md` | 수정 |
| `docs/data-schema.md` | 수정 |
| `docs/adr/ADR-052-memory-는-collection-종류-꺼내는-방식-민감도-판-출처를-가진다.md` | 수정 |
| `docs/adr/ADR-054-민감-memory-본문은-저장할-때-암호화하고-key-는-환경-변수로-받는다.md` | 수정 |
| `docs/adr/INDEX.md` | 수정 |
