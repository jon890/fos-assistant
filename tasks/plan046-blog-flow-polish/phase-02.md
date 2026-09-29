# Phase 02. backend 가 결과물 경로를 답에 쓰지 말라고 이르고 결과물에 조건부 요청으로 답한다

**Execution profile**: standard

## 목표

- 실행 입력의 `[결과물 폴더]` 단락 끝에 「이 폴더 경로와 파일 경로를 답에 쓰지 않는다. 만든 결과물은 답 아래에 자동으로 붙는다.」 한 줄을 더한다
- 결과물 파일 응답에 `ETag` 와 `Last-Modified` 를 붙이고, 조건부 요청에 파일이 그대로면 본문 없이 304 로 답한다

**범위 외**: web 서버 라우트가 조건부 요청을 옮기는 것(phase 03). 답 본문의 경로를 바꾸는 것은 하지 않기로 했다(`docs/code-architecture.md` 「에이전트에게 알리는 법」).

## 컨텍스트

**근거 문서**: `docs/code-architecture.md` 의 「결과물 파일」 아래 「에이전트에게 알리는 법」 절과 「경로」 절(같은 이름의 절이 「사진 첨부」 아래에도 있다), `docs/flow.md` 의 「결과물 파일을 볼 때」 절

지금 모양이다. 구현 전에 각 파일을 연다.

- `backend/src/main/java/com/bifos/assistant/chat/application/ArtifactService.java`
  - `agentPreamble(Conversation conversation)` 이 단락 문자열을 만든다. 마지막 안내 줄이 `"HTML 이 사진을 부를 때는 이 폴더 안의 상대 경로를 쓴다.\n"` 이고 그 뒤에 빈 줄 `"\n"` 이 온다
  - `open(Long conversationId, String relativePath)` 가 `store.resolveInside` 로 판정하고 `ArtifactStore.contentTypeOf` 로 형식을 정한 뒤 `Files.size` 와 `Files.newInputStream` 으로 `ArtifactContent` 를 만든다. `NoSuchFileException` 은 `missing(conversationId, relativePath)` 로 바꾼다
- `backend/src/main/java/com/bifos/assistant/chat/application/ArtifactContent.java`: `record ArtifactContent(String contentType, long byteSize, InputStream body)`. 쓰는 곳은 `ArtifactController` 하나다
- `backend/src/main/java/com/bifos/assistant/chat/presentation/ArtifactController.java`: `read(UUID conversationId, String path)` 가 `access.requireOwnId` 로 주인을 확인하고 `artifacts.open` 을 부른 뒤 `Content-Type`, `Content-Length`, `X-Content-Type-Options`, `Content-Security-Policy`, `Cache-Control: private, no-cache` 를 붙여 `InputStreamResource` 로 준다
- 테스트: `backend/src/test/java/com/bifos/assistant/chat/ArtifactTest.java`
  - `PREAMBLE_GUIDE` 상수가 단락의 안내 줄을 글자까지 고정한다
  - `file(Conversation, String)` 이 실제 HTTP 로 파일을 받고, `header(HttpResponse, String)` 가 머리글을 읽는다
  - `writeAt(Long, String, String, Instant)` 이 수정 시각을 정해 파일을 쓴다

## 의도 메모

- **304 로 답할 때는 파일을 열지 않는다.** 스트림을 연 뒤 304 로 끝내면 아무도 닫지 않는다. 그래서 판정과 여는 것을 둘로 나눈다
- Spring 의 `ResponseEntity` 자동 304 처리에 기대지 않는다. 그 처리는 본문을 쓰지 않을 뿐 이미 연 `InputStreamResource` 를 닫지 않는다
- 다만 `HttpEntityMethodProcessor` 는 200 으로 돌려준 `ResponseEntity` 에 `ETag` 나 `Last-Modified` 가 있으면 조건부 요청을 다시 판정한다. 우리 `notModified` 가 거짓이라 한 요청을 Spring 이 304 로 바꾸면 연 스트림이 닫히지 않는다. **그래서 `notModified` 는 Spring 의 판정과 같아야 한다.** If-None-Match 우선, `W/` 를 뗀 약한 비교, `If-Modified-Since` 의 초 단위 내림이다. `notModified` 의 Javadoc 에 이 조건을 적는다
- `ServletWebRequest.checkNotModified` 도 쓰지 않는다. 응답 객체에 머리글을 직접 쓰고, 뒤에 돌려주는 `ResponseEntity` 의 머리글과 겹친다
- `ETag` 는 약한 검증자 `W/"{바이트 수 16진수}-{수정 시각 밀리초 16진수}"` 다. 바이트 수와 수정 시각으로 만든 값이라 본문이 같다고 보장하지 않는다
- 주인 확인과 경로 판정이 먼저다. 남의 대화에 맞는 `ETag` 를 보내도 `CONVERSATION_NOT_FOUND` 다
- 304 에도 `ETag`, `Last-Modified`, `Cache-Control`, `X-Content-Type-Options`, `Content-Security-Policy` 를 붙인다. `Content-Type` 과 `Content-Length` 는 붙이지 않는다

## 작업 항목

### 1. 단락 문구

- `agentPreamble` 의 `"HTML 이 사진을 부를 때는 이 폴더 안의 상대 경로를 쓴다.\n"` 다음 줄에 `"이 폴더 경로와 파일 경로를 답에 쓰지 않는다. 만든 결과물은 답 아래에 자동으로 붙는다.\n"` 을 더한다. 그 뒤의 빈 줄은 그대로 둔다
- 두 줄 사이에 조건 없이 경로를 쓰지 말라고 한 까닭을 주석 한 줄로 남긴다(2026-09-29 운영에서 모델이 단락의 폴더 경로를 답에 옮겼다)
- `ArtifactTest.PREAMBLE_GUIDE` 끝에 같은 줄을 더한다

### 2. `ArtifactFile` 로 판정과 열기를 나눈다

- `backend/src/main/java/com/bifos/assistant/chat/application/ArtifactFile.java` 신규:
  `record ArtifactFile(Long conversationId, String relativePath, Path path, String contentType, long byteSize, Instant lastModified)`.
  `public String etag()` 가 위 의도 메모의 약한 `ETag` 를 만든다
- `ArtifactService.find(Long conversationId, String relativePath)`: 지금 `open` 의 판정과 형식, `Files.size`, `Files.getLastModifiedTime(path).toInstant()` 까지 하고 `ArtifactFile` 을 돌려준다. `NoSuchFileException` 은 `missing(...)` 이다
- `ArtifactService.open(ArtifactFile file)`: `Files.newInputStream(file.path())` 를 돌려준다. `NoSuchFileException` 은 `missing(file.conversationId(), file.relativePath())` 다
- 옛 `open(Long, String)` 과 `ArtifactContent` 를 지운다

### 3. `ArtifactController.read` 의 조건부 요청

- `@RequestHeader HttpHeaders requestHeaders` 를 받는다
- 주인 확인 뒤 `artifacts.find` 로 `ArtifactFile` 을 얻는다
- `static boolean notModified(HttpHeaders requestHeaders, String etag, Instant lastModified)` 를 컨트롤러에 둔다
  - `requestHeaders.getIfNoneMatch()` 가 비어 있지 않으면 그것만 본다. `*` 이거나, 값 하나가 앞의 `W/` 를 뗀 채 `etag` 에서 `W/` 를 뗀 값과 같으면 참
  - 비어 있고 `requestHeaders.getIfModifiedSince()` 가 0 이상이면 `lastModified` 를 초 단위로 내린 밀리초가 그 값보다 크지 않을 때 참
  - 둘 다 없으면 거짓
- 참이면 `ResponseEntity.status(HttpStatus.NOT_MODIFIED)` 에 공통 머리글과 `eTag`, `lastModified` 를 붙여 본문 없이 돌려준다
- 거짓이면 `artifacts.open(file)` 로 지금처럼 200 을 주되 `eTag(file.etag())` 와 `lastModified(file.lastModified())` 를 더한다
- 공통 머리글을 두 응답이 같은 코드로 붙이도록 한 곳에 모은다
- 클래스 Javadoc 에 조건부 요청을 한 문단 더한다

### 4. 이 phase 를 검증하는 테스트

`ArtifactTest` 에 더한다. 요청 머리글을 줄 수 있는 `file` 변형을 하나 더 만든다.

- 200 응답에 `ETag` 가 `W/"` 로 시작하고 `Last-Modified` 가 있다
- 받은 `ETag` 를 `If-None-Match` 로 다시 보내면 304, 본문이 비고, `Cache-Control` 이 `private, no-cache`, `Content-Security-Policy` 가 그대로다
- 받은 `Last-Modified` 를 `If-Modified-Since` 로 다시 보내면 304 다
- 그 뒤 `writeAt` 으로 같은 경로에 다른 본문과 더 늦은 수정 시각을 쓰고 옛 `ETag` 로 보내면 200 과 새 본문이다
- `If-None-Match: "다른값"` 에 `If-Modified-Since` 를 미래로 함께 보내면 200 이다. `If-None-Match` 가 이긴다
- 다른 사용자가 맞는 `ETag` 를 보내도 404 `CONVERSATION_NOT_FOUND` 다. 지금 `get(...)` 은 늘 `jwt(dad)` 로 보내므로, 보내는 사용자와 요청 머리글을 함께 받는 `file` 변형을 만든다. 다른 사용자는 이 테스트 클래스가 이미 쓰는 사용자 생성 방식을 따른다
- 기존 `Hermes_로_간_입력은_결과물_폴더_단락으로_시작하고_사용자가_쓴_글로_끝난다` 가 새 줄로 통과한다

## 검증

```bash
# cwd: backend/
./gradlew test --tests 'com.bifos.assistant.chat.ArtifactTest'
./gradlew test
```

모두 통과해야 한다.

```bash
# cwd: 저장소 root. 아무것도 나오지 않아야 한다
grep -rn "ArtifactContent" backend/src
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/chat/application/ArtifactFile.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/application/ArtifactContent.java` | 삭제 |
| `backend/src/main/java/com/bifos/assistant/chat/application/ArtifactService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/presentation/ArtifactController.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/ArtifactTest.java` | 수정 |
