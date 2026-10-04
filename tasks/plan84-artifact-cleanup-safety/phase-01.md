# Phase 01. 정리를 대화별 잠금 안에서 다시 판정하고 지우기 직전 재확인한다

**Execution profile**: deep

## 목표

보관 기간 정리가 판정한 뒤 갱신된 대화 폴더의 파일을 지우지 않게 한다. GitHub 이슈 #168 이다.

**범위 외**: 지운 표시(`chat_artifact.deleted_at`) 를 적는 쪽과 그 대조는 phase 2 가 맡는다. `ArtifactCleaner` 는 이 phase 에서 고치지 않는다.

## 컨텍스트

지금 `backend/src/main/java/com/bifos/assistant/chat/infra/ArtifactStore.java` 의 `deleteOlderThan(Instant cutoff)` 는
루트 전체를 한 번 훑어 대화마다 파일 목록과 가장 늦은 수정 시각을 모은 뒤, 그 판정으로 나중에 파일을 지운다.
훑은 뒤 `write(Long, String, byte[])` 가 같은 경로를 새 본문으로 바꾸거나, Hermes 가 같은 폴더에 새 HTML 을 더해도 옛 판정으로 지운다.

`write` 는 MCP `artifact_write` 가 부르는 저장 경로다. Hermes 가 폴더에 직접 쓰는 경로는 Control Plane 프로세스 밖이라 잠글 수 없다.

**근거 문서**: `docs/backend/artifact.md` 의 「보관 기간이 지난 파일을 지울 때」. 알고리즘과 보호 경계 표가 거기 있다. 이 phase 는 그 절을 그대로 구현한다.

기존 패턴:

- 테스트용 package-private 생성자가 이미 있다. `ArtifactStore(ArtifactProperties, boolean, ArtifactAtomicMover)` 다. 같은 방식으로 정리 중단점을 받는 생성자를 더한다.
- 파일 훑기는 `regularFilesUnder(Path)` 가 하고 심볼릭 링크를 따라가지 않는다. `WalkedFile(path, modified, byteSize)` 를 돌려준다.
- 단위 테스트는 `backend/src/test/java/com/bifos/assistant/chat/infra/ArtifactStoreWriteTest.java` 처럼 `@TempDir` 로 루트를 만든다.

## 의도 메모

- 폴더를 다른 이름으로 옮겨 격리하는 방법은 기각했다. 그 사이 Hermes 쓰기가 실패하고, 되돌릴 때 새로 생긴 같은 폴더와 부딪힌다.
- 잠금은 대화마다 만들지 않고 대화 번호로 나눈 64개 `ReentrantLock` 배열에서 고른다. 대화마다 만들면 대화 수만큼 쌓인다.
- 잠금을 잡은 채 파일 시스템을 훑는다. 폴더 하나는 작아 다른 대화의 쓰기를 오래 막지 않는다.
- 남는 두 창(3의 재훑기와 사진 삭제 사이, 파일마다 재확인과 실제 삭제 사이)은 막지 않는다. 문서가 보호 경계로 적었다.

## 작업 항목

### 1. `ArtifactCleanupProbe` 를 더한다

`backend/src/main/java/com/bifos/assistant/chat/infra/ArtifactCleanupProbe.java` 에 package-private 함수형 인터페이스를 둔다.

```java
@FunctionalInterface
interface ArtifactCleanupProbe {
    /** 잠금 안에서 폴더가 기간을 넘겼다고 판정한 직후, 첫 파일을 지우기 전에 부른다. */
    void afterJudged(Long conversationId);
}
```

운영 생성자는 아무것도 하지 않는 값(`conversationId -> {}`)을 넘긴다.

### 2. `ArtifactStore` 에 대화별 잠금을 더한다

- `private final ReentrantLock[] conversationLocks` 를 길이 64 로 만든다. `lockOf(Long conversationId)` 는 `Math.floorMod(conversationId.hashCode(), 64)` 번째를 돌려준다.
- `write(Long, String, byte[])` 의 본문 전체(경로 판정부터 교체까지)를 그 대화의 잠금 안에서 돈다. `content == null` 검사는 잠금 밖에 둬도 된다.
- 기존 세 생성자는 모두 새 생성자 `ArtifactStore(ArtifactProperties, boolean, ArtifactAtomicMover, ArtifactCleanupProbe)` 로 이어진다.

### 3. `deleteOlderThan(Instant cutoff)` 를 폴더마다 잠금 안에서 판정하게 바꾼다

반환 타입 `List<ArtifactRemoved>` 와 「대화 번호 이름의 폴더 안에 있는 파일만 지운다」 는 그대로다.

1. `root` 가 디렉터리가 아니면 빈 목록이다(지금과 같다).
2. `root` 바로 아래에서 이름이 대화 번호(`conversationIdOf`)이고 링크가 아닌 디렉터리를 모은다. 목록을 읽지 못하면 경고 로그를 남기고 빈 목록이다.
3. 대화마다 `lockOf(id)` 를 잡고 아래를 한다. 한 대화가 예외로 끝나도 경고 로그만 남기고 다음 대화로 간다.
   1. `regularFilesUnder(folder)` 로 새로 훑는다. 파일이 없거나 가장 늦은 수정 시각이 `cutoff` 보다 앞서지 않으면 그 폴더를 건너뛴다.
   2. `probe.afterJudged(id)` 를 부른다.
   3. 확장자가 `html` 인 파일부터 지운다. 지우기 직전 `Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS)` 로 수정 시각을 다시 읽는다. `cutoff` 보다 앞서지 않으면 그 폴더의 삭제를 멈춘다. 파일이 이미 없으면 건너뛴다. 지운 파일만 결과에 담는다.
   4. 나머지 파일을 지우기 전에 `regularFilesUnder(folder)` 로 다시 훑는다. `cutoff` 보다 앞서지 않은 파일이 하나라도 있으면 멈춘다.
   5. 4에서 다시 훑은 목록의 파일 가운데 3에서 지우지 않은 것(확장자가 `html` 이 아닌 파일과, 4에서 새로 보인 오래된 HTML)을 3과 같이 지우기 직전 재확인하며 지운다.
4. 멈출 때는 `log.info` 로 대화 번호와 까닭을 남긴다. 파일 경로는 남기지 않는다.

`Files.deleteIfExists` 가 실패하면 지금처럼 경고 로그를 남기고 그 파일만 건너뛴다.

### 4. 이 phase 를 검증하는 `ArtifactStoreCleanupTest`

`backend/src/test/java/com/bifos/assistant/chat/infra/ArtifactStoreCleanupTest.java` 를 새로 만든다. `@TempDir` 루트와 새 생성자 `new ArtifactStore(props, false, mover, probe)` 로 만든다.
`props` 는 `new ArtifactProperties(root.toString(), "/agent/artifacts", 30)` 이다.
`mover` 는 `(source, target) -> Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)` 다. 운영의 `ArtifactStore.atomicMove` 와 같은 일을 한다.
probe 는 정리를 부른 스레드에서 잠금을 잡은 채 돈다. `ReentrantLock` 이므로 probe 안의 `store.write` 는 막히지 않는다.
파일의 수정 시각은 `Files.setLastModifiedTime` 으로 40일 전으로 만든다. `cutoff` 는 `Instant.now().minus(Duration.ofDays(30))` 다.
메서드 이름은 영문 camelCase, 한국어 문장은 `@DisplayName` 에 쓴다.

| 테스트 | 준비 | probe 가 하는 일 | 기대 |
| --- | --- | --- | --- |
| 변경 없는 만료 대화를 지운다 | `7/a/index.html`, `7/a/photo.png` 모두 40일 전 | 없음 | 둘 다 지워지고 결과에 두 경로 |
| 판정 뒤 같은 경로를 교체하면 지우지 않는다 | 위와 같음 | `store.write(7L, "a/index.html", 새 본문)` | 새 본문의 `index.html` 과 `photo.png` 가 남는다. 결과가 비었다 |
| 판정 뒤 새 HTML 을 더하면 옛 사진을 남긴다 | 위와 같음 | `Files.writeString(folder/a/new.html)` 로 지금 시각 파일을 더한다 | `photo.png` 와 `new.html` 이 남는다. 결과에 `photo.png` 가 없다 |
| 정리 중 MCP 쓰기는 정리가 끝날 때까지 기다린다 | 위와 같음 | 다른 스레드에서 `store.write(7L, "a/late.html", ...)` 를 시작하고 300ms 안에 끝나지 않았음을 단언한다 | 정리가 끝난 뒤 그 쓰기가 끝나고 `late.html` 이 남는다 |
| 다른 대화의 정리는 영향받지 않는다 | `7/` 은 최근 파일, `8/` 은 40일 전 파일 | 없음 | `8/` 의 파일만 지워진다 |

넷째 테스트는 probe 안에서 `CompletableFuture.runAsync` 로 쓰기를 시작하고, 같은 probe 안에서 `future.get(300, TimeUnit.MILLISECONDS)` 가 `TimeoutException` 을 내는지 단언한다. 정리가 끝난 뒤 `future.get(5, TimeUnit.SECONDS)` 로 기다린다. 기한 없는 대기는 쓰지 않는다.
`afterJudged` 는 checked 예외를 선언하지 않으므로 probe 안의 `Files.writeString` 같은 호출은 `UncheckedIOException` 으로 감싼다.
probe 가 `store` 를 참조해야 하면 `AtomicReference<ArtifactStore>` 에 담아 생성 순서를 푼다.
기존 `backend/src/test/java/com/bifos/assistant/chat/ArtifactTest.java` 의 정리 테스트도 그대로 통과해야 한다.

## 검증

```bash
# cwd: backend/
./gradlew test --tests 'com.bifos.assistant.chat.infra.ArtifactStoreCleanupTest' --tests 'com.bifos.assistant.chat.infra.ArtifactStoreWriteTest' --tests 'com.bifos.assistant.chat.ArtifactTest'
./gradlew checkstyleMain checkstyleTest spotlessCheck
```

셋 모두 종료 코드 0 이어야 한다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/chat/infra/ArtifactCleanupProbe.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/infra/ArtifactStore.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/infra/ArtifactStoreCleanupTest.java` | 신규 |
