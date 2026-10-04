# Phase 02. 지운 뒤 행 쪽에서 지운 표시를 맞춘다

**Execution profile**: deep

## 목표

파일을 지운 뒤 `deleted_at` 을 적지 못했거나 그 사이 프로세스가 멈춘 행을 다음 정리에서 맞춘다. GitHub 이슈 #169 다.
같은 경로에 새로 생긴 파일의 새 행은 만료로 적지 않는다.

**범위 외**: 파일을 지우는 판정과 잠금은 phase 1 이 끝냈다. 스키마는 바꾸지 않는다.

## 컨텍스트

- `backend/src/main/java/com/bifos/assistant/chat/application/ArtifactCleaner.java` 의 `cleanExpired(Instant now)` 는 `store.deleteOlderThan(cutoff)` 로 지운 HTML 마다 `artifactWriter.markDeleted(conversationId, path, now)` 를 부르고, 실패하면 로그만 남긴다.
- `backend/src/main/java/com/bifos/assistant/chat/application/ChatArtifactWriter.java` 의 `markDeleted` 는 `ChatArtifactRepository.markDeleted(conversationId, path, now)` 에 트랜잭션을 준다.
- `backend/src/main/java/com/bifos/assistant/chat/infra/ChatArtifactRepository.java` 의 `markDeleted` 는 `deletedAt is null` 인 그 경로의 행 모두에 적는 JPQL update 다.
- `ChatArtifact` 의 칸은 `conversationId`, `messageId`, `path`, `byteSize`, `createdAt`(not null), `deletedAt`(null 가능) 이다. getter 는 record 식 이름(`conversationId()`, `path()`, `createdAt()`)이다.
- `ArtifactService` 는 파일이 없을 때 `existsByConversationIdAndPathAndDeletedAtIsNotNull` 로 410 과 404 를 고른다.
- 저장소에 메서드를 더하면 `RepositoryQueryMysqlTest` 가 실제 MySQL 에서 스스로 실행한다. 따로 할 일이 없다(`backend/AGENTS.md` 의 「저장소 쿼리는 실제 MySQL 에서도 실행한다」).

**근거 문서**: `docs/backend/artifact.md` 의 「지운 표시를 다시 맞추기」.

## 의도 메모

- DB 에 먼저 적고 파일을 지우는 순서는 기각했다. 파일 삭제가 실패하면 행은 지웠다고 하는데 파일은 계속 나간다.
- 대조는 `deleteOlderThan` 뒤에 돈다. 같은 실행에서 표시에 실패한 행도 곧바로 다시 맞춘다.
- 루트가 디렉터리가 아니면 대조를 건너뛴다. 붙지 않은 루트를 보고 모든 행에 지운 표시를 적는 일을 막는다.

## 작업 항목

### 1. `ChatArtifactRepository`

- `markDeleted` 에 인자 `@Param("createdBefore") Instant createdBefore` 를 더하고 조건 `and a.createdAt < :createdBefore` 를 더한다. Javadoc 에 같은 경로의 새 행을 건드리지 않는다는 까닭을 적는다.
- `List<ChatArtifact> findByDeletedAtIsNullAndCreatedAtBefore(Instant createdBefore);` 를 더한다.

### 2. `ChatArtifactWriter`

- `markDeleted(Long conversationId, String path, Instant now, Instant createdBefore)` 로 바꾼다.
- `@Transactional(readOnly = true) public List<ChatArtifact> activeCreatedBefore(Instant createdBefore)` 를 더한다.

### 3. `ArtifactStore.isMissing(Long conversationId, String relativePath)`

`public boolean isMissing(...)` 를 더한다. 아래가 모두 참일 때만 `true` 다.

- `root` 가 디렉터리다
- `relativePath` 가 비지 않았고 절대 경로가 아니며 정규화해도 대화 폴더 안이다
- `Files.exists(folder.resolve(relativePath), LinkOption.NOFOLLOW_LINKS)` 가 거짓이다

경로로 쓸 수 없는 글자로 예외가 나면 `false` 다. 모르는 것을 지웠다고 적지 않는다.

### 4. `ArtifactCleaner.cleanExpired(Instant now)`

1. `cutoff = now.minus(Duration.ofDays(properties.retentionDays()))`
2. `store.deleteOlderThan(cutoff)` 로 지우고, 지운 HTML 마다 `artifactWriter.markDeleted(id, path, now, cutoff)` 를 부른다. 실패는 지금처럼 센다.
3. `reconcile(now, cutoff)`: `artifactWriter.activeCreatedBefore(cutoff)` 의 행을 `(conversationId, path)` 로 겹치지 않게 모으고, `store.isMissing` 이 참인 것마다 `markDeleted(id, path, now, cutoff)` 를 부른다. 한 건의 예외는 경고 로그로 남기고 다음 건으로 간다. `activeCreatedBefore` 자체가 실패하면 경고 로그만 남긴다.
4. 로그 `expired artifacts cleaned` 에 대조로 적은 수 `reconciled={}` 를 더한다. 반환값은 지금처럼 지운 파일 수다.

클래스 Javadoc 에 대조 단계를 한 줄 더한다.

### 5. 이 phase 를 검증하는 테스트

`backend/src/test/java/com/bifos/assistant/chat/ArtifactTest.java` 를 고친다. 행을 만드는 방법은 기존 `expiredDeletedHtmlRecordsDeletionTimeAndReturns410` 을 따른다(`stub().beforeAwait(...)`, `chat.send`, `Files.setLastModifiedTime`).

**행의 `createdAt` 은 지금 시각이라 `cleanExpired(Instant.now())` 로는 기간 시작보다 이르지 않다.** 행을 대상에 넣으려면 `cleanExpired` 에 미래 `now` 를 넘긴다.
기본값은 `Instant.now().plus(Duration.ofDays(31))` 다. 이때 `cutoff` 는 지금보다 하루 뒤라 지금 만든 행과 40일 전 파일이 모두 대상이 된다.

**기존 `expiredDeletedHtmlRecordsDeletionTimeAndReturns410` 을 고친다.** 지금은 `cleanExpired(Instant.now())` 를 부르므로 `markDeleted` 의 새 조건 때문에 `deletedAt` 이 비어 실패한다. 그 호출을 위 미래 `now` 로 바꾼다. 나머지 단언은 그대로 둔다.

첫 `markDeleted` 만 던지는 writer 가 필요한 테스트는 `ChatArtifactWriter` 하위 클래스를 만들되, 던지지 않는 호출은 `super` 가 아니라 `@Autowired` 로 받은 `ChatArtifactWriter` 빈에 위임한다. `super` 를 부르면 Spring 프록시를 거치지 않아 `@Modifying` update 에 트랜잭션이 없다. `activeCreatedBefore` 도 그 빈에 위임한다.
그 writer 로 `new ArtifactCleaner(store, failingOnce, properties, clock)` 를 만든다. 생성자 인자는 `@RequiredArgsConstructor` 순서(`ArtifactStore`, `ChatArtifactWriter`, `ArtifactProperties`, `Clock`)다. 테스트 클래스에 없는 빈은 `@Autowired` 로 받는다.

| 테스트 | 준비 | 기대 |
| --- | --- | --- |
| 첫 지운 표시가 실패해도 같은 정리의 대조가 맞춘다 | HTML 하나를 답에 묶고 파일을 40일 전으로 한다. 첫 `markDeleted` 만 던지는 writer 로 만든 cleaner 를 미래 `now` 로 한 번 돌린다 | 파일이 없다. 행의 `deletedAt` 이 차 있다. 파일 응답이 410 `ARTIFACT_GONE`, 메시지 목록의 `deleted` 가 `true` 다 |
| 파일이 없고 행은 살아 있는 재기동 상태를 맞춘다 | HTML 을 답에 묶은 뒤 파일을 직접 지운다 | 미래 `now` 로 `cleanExpired` 한 뒤 행의 `deletedAt` 이 차 있고 410 이다 |
| 지운 표시는 기간 시작 전에 만든 행에만 적는다 | 첫 turn 이 `a/index.html` 을 만들고 두 번째 turn 이 같은 경로를 다시 써 행이 둘이다. 그 뒤 파일을 직접 지운다 | `now` 를 첫 행의 `createdAt` + 1ms + 30일로 주면 `cutoff` 가 두 행 사이에 온다. `cleanExpired(now)` 뒤 첫 행만 `deletedAt` 이 차 있고 둘째 행은 비었다. `markDeleted` 의 `createdAt` 조건을 빼면 이 테스트가 실패해야 한다. 단언 전에 둘째 행의 `createdAt` 이 `cutoff` 보다 늦다는 전제를 먼저 단언한다 |
| 같은 경로에 파일이 있으면 행을 적지 않는다 | 위처럼 행 둘을 만들되 파일은 지금 시각 그대로 둔다 | 전제로 둘째 행의 `createdAt` 과 파일의 수정 시각이 `cutoff` 보다 늦다고 먼저 단언한다. 위와 같은 `now` 로 정리한 뒤 두 행 모두 `deletedAt` 이 비었고 파일이 남는다 |
| 한 건의 실패가 나머지를 막지 않는다 | 두 대화에 HTML 을 하나씩 묶고 두 파일을 직접 지운다. 첫 `markDeleted` 만 던지는 writer 를 쓴다 | 미래 `now` 로 한 번 정리하면 예외가 `cleanExpired` 밖으로 나오지 않고 실패하지 않은 행만 `deletedAt` 이 차 있다. 같은 cleaner 로 한 번 더 정리하면 두 행 모두 차 있다 |

## 검증

```bash
# cwd: backend/
./gradlew test --tests 'com.bifos.assistant.chat.ArtifactTest' --tests 'com.bifos.assistant.chat.infra.ArtifactStoreCleanupTest' --tests 'com.bifos.assistant.chat.ChatArtifactMigrationTest'
./gradlew checkstyleMain checkstyleTest spotlessCheck
```

```bash
# cwd: 저장소 root
scripts/check-mysql-migration.sh
```

모두 종료 코드 0 이어야 한다. 마지막 명령이 새 저장소 메서드를 실제 MySQL 에서 실행한다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/chat/infra/ChatArtifactRepository.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/application/ChatArtifactWriter.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/infra/ArtifactStore.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/application/ArtifactCleaner.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/ArtifactTest.java` | 수정 |
