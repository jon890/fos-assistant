# Phase 02. 기동할 때의 보정이 줄을 잠그고 실패해도 기동을 잇는다

**Execution profile**: standard

## 목표

`MemoryContentBackfill` 이 평문으로 남은 민감 줄을 줄마다 쓰기 잠금으로 다시 읽어 암호화한다.
보정이 예외를 던져도 기동이 멈추지 않는다.

**범위 외**: 무엇을 보정 대상으로 삼는지는 그대로다(`sensitivity` 가 `SENSITIVE` 이고 `content_key_id` 가 빈 줄).

## 컨텍스트

- `backend/src/main/java/com/bifos/assistant/memory/application/MemoryContentBackfill.java`: `ApplicationRunner` 다. `run` 과 `sealPlaintext()` 가 `@Transactional` 하나로 두 표의 평문 민감 줄을 모두 읽고, 읽어 둔 엔티티를 통째로 `save` 한다. 기동 중에 다른 인스턴스나 요청이 그 줄을 고치면 읽어 둔 옛 값으로 덮어쓴다
- `ApplicationRunner` 가 예외를 던지면 Spring Boot 는 기동을 멈춘다
- `backend/src/main/java/com/bifos/assistant/memory/infra/MemoryRepository.java`: `findByIdForUpdate(Long id)` 가 `@Lock(LockModeType.PESSIMISTIC_WRITE)` 로 한 줄을 읽는다. `findBySensitivityAndContentKeyIdIsNull(MemorySensitivity)` 가 대상을 찾는다
- `backend/src/main/java/com/bifos/assistant/memory/infra/MemoryRevisionRepository.java`: `findBySensitivityAndContentKeyIdIsNull(MemorySensitivity)` 가 있다. 잠가 읽는 메서드는 없다. 키는 `MemoryRevisionId(memoryId, revision)` 이다
- `Memory.sealInPlace` 와 `MemoryRevision.sealInPlace` 는 이미 암호문이면 `IllegalStateException` 을 던진다
- 같은 클래스 안에서 `@Transactional` 메서드를 부르면 프록시를 지나지 않아 트랜잭션이 열리지 않는다. 그래서 줄 하나를 고치는 일을 다른 bean 에 둔다
- 테스트 선례: `backend/src/test/java/com/bifos/assistant/memory/MemoryContentBackfillTest.java`. `backfill.sealPlaintext()` 의 반환값(암호화한 줄 수)을 단언한다. `MemoryEncryptionDisabledTest.backfillLeavesPlainRowsWithoutKey` 가 key 없는 경우를 본다

**근거 문서**: `docs/adr/ADR-055-민감-memory-본문은-저장할-때-암호화하고-key-는-환경-변수로-받는다.md` 의 「적용 범위」 둘째 표, `docs/code-architecture.md` 의 「Memory」 클래스 표

## 의도 메모

- 대상 찾기는 트랜잭션 밖에서 번호만 얻는다. 줄 하나의 잠금과 저장이 그 줄의 트랜잭션 하나다. 한 트랜잭션에 모두 담으면 먼저 읽어 둔 엔티티가 영속성 문맥에 남아 잠근 뒤에도 옛 값을 본다
- 잠근 뒤 조건을 다시 본다. 그 사이에 다른 쪽이 암호화했거나 지웠거나 일반 항목으로 바꿨으면 건너뛴다
- 한 줄이 실패하면 남은 줄은 이번 기동에서 보지 않는다. 다음 기동에서 다시 본다(ADR-055). 줄마다 잡지 않는 것은 같은 원인(key 설정)이면 모든 줄이 같게 실패하기 때문이다
- 로그에는 예외 클래스 이름만 남긴다. 예외 메시지와 스택은 본문이나 key 설정을 담을 수 있다

## 작업 항목

### 1. `MemoryRevisionRepository.findByIdForUpdate`

```java
/** 판 한 줄을 쓰기 잠금으로 읽는다. 기동할 때의 보정이 저장 모양을 바꾸기 전에 쓴다(ADR-055). */
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("select r from MemoryRevision r where r.id = :id")
Optional<MemoryRevision> findByIdForUpdate(@Param("id") MemoryRevisionId id);
```

### 2. `backend/src/main/java/com/bifos/assistant/memory/application/MemoryContentSealer.java`

`@Component @RequiredArgsConstructor`. `MemoryRepository`, `MemoryRevisionRepository`, `MemoryContentCipher` 를 받는다.

| 메서드 | 동작 |
| --- | --- |
| `@Transactional boolean sealMemory(Long id)` | `memories.findByIdForUpdate(id)` 로 읽는다. 줄이 없거나 `sensitivity()` 가 `SENSITIVE` 가 아니거나 `sealed()` 면 `false`. 아니면 `sealInPlace(cipher.seal(content, contentBinding))` 뒤 저장하고 `true` |
| `@Transactional boolean sealRevision(MemoryRevisionId id)` | `revisions.findByIdForUpdate(id)` 로 읽는다. 줄이 없거나 `sensitivity()` 가 `SENSITIVE` 가 아니거나 `contentKeyId()` 가 null 이 아니면 `false`. 아니면 같은 방식으로 암호화하고 `true` |

클래스 Javadoc: 줄 하나를 쓰기 잠금으로 다시 읽어 암호화한다. 줄마다 트랜잭션 하나다(ADR-055).

### 3. `MemoryContentBackfill`

- 클래스와 메서드의 `@Transactional` 을 지운다. `MemoryContentSealer` 를 주입받는다
- `run(args)`: `sealPlaintext()` 를 `try` 로 감싸고 `RuntimeException` 을 잡아 `log.error("평문으로 남은 민감 줄의 보정이 실패했다 exception={}", e.getClass().getName())` 만 남긴다. 예외 객체를 로그 인자로 넘기지 않는다
- `sealPlaintext()`: 두 조회로 대상을 찾는다. key 가 없으면 지금처럼 줄 수만 경고하고 0 을 낸다. 있으면 `Memory` 는 `sealer.sealMemory(row.id())`, 판은 `sealer.sealRevision(row.id())` 를 줄마다 부르고 `true` 인 수를 센다. 반환값과 info 로그의 수는 실제로 암호화한 줄 수다
- 클래스 Javadoc 에 「줄마다 쓰기 잠금으로 다시 읽는다. 실패해도 기동을 막지 않는다」 를 더한다

### 4. 이 phase 를 검증하는 `MemoryContentBackfillTest.java`

기존 두 테스트는 그대로 통과해야 한다. 아래를 더한다.

| 입력 | 기대 |
| --- | --- |
| `MemoryContentSealer.sealMemory` 를 이미 암호문인 민감 줄의 번호로 부른다 | `false` 이고 `content` 가 그대로다 |
| `sealMemory` 를 일반 줄의 번호와 없는 번호로 부른다 | 둘 다 `false` |
| `sealRevision` 을 이미 암호문인 판과 없는 판으로 부른다 | 둘 다 `false` |
| `insertMemory("SENSITIVE", MEMORY_MARK)` 로 한 줄을 넣는다. spy 의 `sealMemory` 가 실제 메서드를 부르기 전에 그 줄의 `content` 를 `JdbcTemplate` 으로 `v1.먼저-봉인-8820` 으로, `content_key_id` 를 `test-1` 로, `revision` 을 9 로 바꾸게 한다(`doAnswer` 안에서 고친 뒤 `invocation.callRealMethod()`). 그 뒤 `backfill.sealPlaintext()` | 0 을 낸다. 그 줄의 `content` 가 `v1.먼저-봉인-8820`, `revision` 이 9 그대로다. 먼저 읽어 둔 옛 값으로 덮어쓰지 않는다 |
| `insertMemory("SENSITIVE", MEMORY_MARK)` 로 한 줄을 넣은 뒤, `MemoryContentSealer` 를 `@MockitoSpyBean` 으로 두고 `sealMemory` 가 `IllegalStateException("평문-표식-4410")` 을 던지게 한 뒤 `backfill.run(new DefaultApplicationArguments())` 를 부른다 | 예외가 나오지 않는다. `OutputCaptureExtension` 으로 잡은 로그가 `java.lang.IllegalStateException` 을 담고 `평문-표식-4410` 을 담지 않는다 |

import 는 `org.springframework.test.context.bean.override.mockito.MockitoSpyBean`(선례 `backend/src/test/java/com/bifos/assistant/chat/ChatStopTest.java`), `org.springframework.boot.test.system.OutputCaptureExtension` 과 `CapturedOutput`(`@ExtendWith(OutputCaptureExtension.class)`), `org.springframework.boot.DefaultApplicationArguments` 다. spy 는 테스트마다 `Mockito.reset` 으로 되돌린다. 기존 두 테스트가 실제 동작을 본다.

## 검증

```bash
# cwd: 저장소 root
(cd backend && ./gradlew test --tests '*MemoryContentBackfillTest' --tests '*MemoryEncryptionDisabledTest' --tests '*MemoryEncryptionTest')
(cd backend && ./gradlew test)
(cd backend && ./gradlew qualityCheck)
node test/e2e/run.ts
scripts/check-public-safe.sh
```

- 모두 종료 코드 0
- `node test/e2e/run.ts` 는 `./gradlew test` 뒤에 돌린다

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/memory/infra/MemoryRevisionRepository.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/memory/application/MemoryContentSealer.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/memory/application/MemoryContentBackfill.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/memory/MemoryContentBackfillTest.java` | 수정 |
