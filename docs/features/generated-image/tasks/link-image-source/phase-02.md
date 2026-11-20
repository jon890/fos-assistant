# Phase 02. link: 가리킴을 대화 메시지로 확인하고 받기 경로에 연결한다

**Execution profile**: standard

## 목표

받기 경로 `POST /internal/hermes/image-source` 가 `link:<주소>` 를 받아, 요청자가 그 대화에 보낸 메시지에 있는 주소일 때만 phase 01 의 받기 객체로 사진을 받아 준다. 그림 규칙이 `link:` 를 안내한다.

**범위 외**: 링크 저장과 화면(phase 03), fos-ctx(phase 04).

## 컨텍스트

**요구사항**: REQ20, REQ21, REQ23

**근거 문서**: `docs/features/generated-image/design.md` 의 「PR5」 의 「가리킴」, 「대화에 있는 링크인가」, 「받기 경로의 새 응답」, 「실행 입력의 그림 규칙」, `docs/adr/ADR-20261120-link-image-source.md`

- 받기는 `backend/src/main/java/com/bifos/assistant/mcp/application/ImageSourceService.java` 의 `read` 다. 가리킴은 `mcp/application/dto/ImageSourceRef.java` 가 푼다(지금은 300자 상한을 접두어 앞에서 본다). 경로는 `mcp/presentation/ImageSourceController.java` 다.
- 메시지는 `chat/conversation/domain/ChatMessage.java`(본문 `content()` 가 푼 글, `role`, `senderUserId`)와 `chat/conversation/infra/ChatMessageRepository.java`(`findByConversationIdOrderByIdAsc`)에 있다. `mcp` 는 `chat.*.infra` 를 직접 부르지 않고 application 서비스를 부른다(`backend/docs/code-architecture.md`).
- 오류 코드는 `shared/error/ErrorCode.java` 의 `IMAGE_SOURCE_UNKNOWN` 옆에 둔다.
- 그림 규칙은 `chat/artifact/application/ArtifactService.java` 의 `IMAGE_RULES` 다. 넷째 줄이 「파일 경로나 인터넷 주소는 쓰지 않는다」 다.

## 의도 메모

- 대화 확인을 받기 전에 한다. 대화에 없는 주소로는 바깥 요청을 한 번도 보내지 않는다.
- 다른 대화, 다른 사용자, 없는 주소는 모두 `IMAGE_SOURCE_UNKNOWN` 이다(PR3 과 같은 원칙).
- 받은 바이트는 어디에도 쓰지 않는다(REQ23).

## 작업 항목

### 1. `ImageSourceRef` 에 `link` 를 더한다

`record ImageSourceRef(Long attachmentId, String title, String link)`. `link:` 뒤 글자를 다듬어 2,048자까지 받는다. 첨부와 제목은 지금처럼 300자. 모양이 틀리면 `IMAGE_SOURCE_UNKNOWN`.

### 2. `backend/src/main/java/com/bifos/assistant/chat/conversation/application/ConversationLinks.java` 신규

`Optional<URI> findSentLink(CurrentUser user, Long conversationId, String link)`. `CurrentUser` 는 `McpCaller.user()` 의 타입이다.

- 그 대화의 사용자 역할이고 `senderUserId == userId` 인 메시지의 `content()` 를 본다. 풀지 못한 본문(`ChatMessage.UNREADABLE_CONTENT`)은 건너뛴다.
- 메시지 글을 미리 자르지 않는다. 한글 경로(`a.com/상품/123`)가 한글에서 잘리기 때문이다. 방법은 이렇다.
  1. 가리킨 주소: 앞의 `https://`, `http://` 를 대소문자 없이 떼고, `#조각` 을 떼고, 퍼센트 인코딩을 푼다(잘못된 `%` 는 그대로 둔다). 첫 `/`, `?` 앞이 호스트다.
  2. 메시지 글: `https?://` 를 대소문자 없이 지우고 퍼센트 인코딩을 같은 방식으로 푼 글을 만든다.
  3. 2의 글에서 1의 글을 찾되 호스트 부분만 대소문자 없이 맞추고 나머지는 글자 그대로 맞춘다. 찾은 자리의 앞뒤가 경계여야 한다.
- 경계 규칙은 아래와 같다. 경계는 글의 처음과 끝, RFC 3986 의 unreserved, reserved, `%` 가 아닌 모든 문자. 메시지 쪽 주소 끝에 붙은 `.`, `,`, `)`, `!`, `?` 는 떼고 본다.
- 맞으면 받을 주소를 만든다(`#조각` 은 버린다): `https://` + 소문자 호스트(국제 도메인은 `java.net.IDN.toASCII`) + 경로와 쿼리의 ASCII 밖 문자를 UTF-8 퍼센트 인코딩한 것. 만들지 못하면 빈 값.

### 3. `backend/src/main/java/com/bifos/assistant/chat/artifact/application/LinkImageService.java` 신규

`InspectedImage`(`chat/attachment/application/dto/InspectedImage.java`) 자체를 돌려주는 얇은 application 서비스. chat 하위 기능 순서상 artifact 가 attachment 를 쓰는 것은 허용된다. `LinkImageFetcher.fetch` 를 부르고 `LinkImageRejectedException` 을 오류 코드로 바꾼다.

| 까닭 | `ErrorCode` | 상태 |
|---|---|---|
| `BLOCKED` | `IMAGE_SOURCE_LINK_BLOCKED` | 422 |
| `NO_IMAGE` | `IMAGE_SOURCE_LINK_NO_IMAGE` | 422 |
| `TOO_LARGE` | `IMAGE_SOURCE_LINK_TOO_LARGE` | 422 |
| `FAILED` | `IMAGE_SOURCE_LINK_FAILED` | 502 |

### 4. `ImageSourceService` 와 `ImageSourceController`

- `read` 가 `ImageSourceResult(InspectedImage image, String link)`(`mcp/application/dto/ImageSourceResult.java` 신규)를 돌려준다. 첨부와 그림은 `link` 가 null.
- `ref.link()` 면 `access.requireOwn` 뒤 `ConversationLinks.findSentLink(caller.user(), origin.conversationId(), ref.link())`. 비면 `IMAGE_SOURCE_UNKNOWN`. 있으면 `LinkImageService` 로 받는다. 새 오류 코드는 `translate` 가 바꾸지 않고 그대로 낸다.
- 컨트롤러는 `link` 가 있으면 응답 머리 `X-Image-Source-Link` 에 그 주소를 넣는다.

### 5. `ArtifactService.IMAGE_RULES`

바탕과 참고 줄을 「바탕과 참고에는 `attachment:`, `generated:`, `link:` 가리킴만 쓰고 파일 경로나 맨 주소는 쓰지 않는다」 뜻으로 고친다. 「사용자가 이 대화에 보낸 링크(상품 페이지나 사진 주소)의 사진을 바탕이나 참고로 쓸 때는 `link:<사용자가 보낸 주소 그대로>` 로 적고 검색이나 페이지에서 찾은 주소는 쓰지 않는다」, 「링크를 쓸 수 없다는 결과를 받으면 어느 링크를 왜 쓸 수 없는지와 사진을 직접 올리는 방법을 답한다」 줄을 더한다. 머리 문장의 줄 수를 맞춘다.

### 6. 테스트

- `backend/src/test/java/com/bifos/assistant/chat/conversation/application/ConversationLinksTest.java` 신규: `https://` 없이 쓴 주소, `http://`, 한글 조사가 붙은 주소(「…/123의 양말」), 끝에 마침표가 붙은 주소, 퍼센트 인코딩이 다른 주소, 한글 경로 주소(`a.com/상품/123`), 대문자 호스트, `#조각` 이 붙은 주소가 맞는다. 잘못된 `%` 가 든 메시지에서도 예외 없이 판정한다. 더 긴 주소의 앞부분, 다른 사용자의 메시지에만 있는 주소, 답 메시지에만 있는 주소는 맞지 않는다. 받을 주소가 소문자 호스트와 ASCII 인코딩인지 본다.
- 테스트 대역: 통합 테스트는 Spring 컨텍스트 하나를 같이 쓴다. `backend/src/test/java/com/bifos/assistant/testsupport/IntegrationTestDoubles.java` 에 가짜 DNS 와 가짜 전송을 넣은 `LinkImageFetcher` 를 `@Primary` 빈으로 두고, 응답 목록과 호출 수를 테스트가 바꿀 수 있게 한다. `IntegrationTestIsolation.java` 가 테스트마다 되돌린다. 테스트 클래스마다 설정을 따로 붙이지 않는다.
- `backend/src/test/java/com/bifos/assistant/mcp/ImageSourceEndpointTest.java` 수정: 위 대역으로 대화에 보낸 상품 페이지 링크의 대표 사진 바이트와 `X-Image-Source-Link` 를 받는다. 다른 대화의 링크는 `IMAGE_SOURCE_UNKNOWN` 이고 전송이 한 번도 불리지 않는다. 네 까닭이 각 오류 코드와 상태로 나온다. 받은 뒤 그 대화의 첨부와 결과물 폴더, 보관함에 새 파일이 없다. 같은 링크를 두 번 받으면 전송이 두 번 불린다(REQ23). 첨부 하나와 실패하는 링크 하나를 차례로 보내도 각각 독립된 응답이다.
- `backend/src/test/java/com/bifos/assistant/chat/ArtifactTest.java` 수정: 실행 입력에 `link:` 줄이 있고 「인터넷 주소는 쓰지 않는다」 단독 문장이 없다.

## 검증

```bash
cd backend && ./gradlew test --tests 'com.bifos.assistant.chat.conversation.application.ConversationLinksTest' --tests 'com.bifos.assistant.mcp.ImageSourceEndpointTest' --tests 'com.bifos.assistant.chat.ArtifactTest'
cd backend && ./gradlew archTest checkstyleMain checkstyleTest spotlessCheck
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/mcp/application/dto/ImageSourceRef.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/mcp/application/dto/ImageSourceResult.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/mcp/application/ImageSourceService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/mcp/presentation/ImageSourceController.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/conversation/application/ConversationLinks.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/artifact/application/LinkImageService.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/artifact/application/ArtifactService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/shared/error/ErrorCode.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/conversation/application/ConversationLinksTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/mcp/ImageSourceEndpointTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/ArtifactTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/testsupport/IntegrationTestDoubles.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/testsupport/IntegrationTestIsolation.java` | 수정 |
