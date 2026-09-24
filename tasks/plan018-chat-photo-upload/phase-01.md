# Phase 01. 사진을 받아 두고 돌려주는 경로

**Execution profile**: deep

## 목표

대화에 사진을 올리고 다시 내려받는 경로를 만든다.
파일은 공유 디렉터리에 두고 데이터베이스에는 그것을 가리키는 행만 둔다.

**범위 외**:
사진을 에이전트에게 알리는 것은 phase-02 가, 화면은 phase-03 이 한다.
공유 디렉터리를 컨테이너에 붙이는 일은 **이 저장소가 하지 않는다.**
비공개 저장소 `fos-home-infra` 가 소유한다. 아래 「fos-home-infra 에서 할 것」을 본다.
이 phase 만으로는 화면에서 올릴 수 없고 에이전트도 사진을 모른다.

## 컨텍스트

지금 사진을 쓰려면 사진 저장소의 웹 화면에 따로 들어가 올린 뒤
대화로 돌아와 폴더 이름을 말해야 한다.

**근거 문서**:
`docs/adr/ADR-020-사진은-공유-디렉터리에-두고-에이전트가-파일로-읽는다.md`,
`docs/data-schema.md` 의 「chat_attachment」 절,
`docs/code-architecture.md` 의 「사진 첨부」 절,
`docs/flow.md` 의 「사진을 올려 보낼 때」 절.

### 행을 지우지 않는다

파일만 지우고 `deleted_at` 을 적는다.
그래야 지난 대화를 열었을 때 그 자리에 사진이 있었다는 것이 남는다.

**볼 수 있는지는 `deleted_at` 하나가 정한다.** `expires_at` 은 언제 지울지만 정한다.
둘로 판정하면 지우는 일이 늦었을 때 화면과 디스크가 어긋난다.

## 의도 메모

- 사진을 데이터베이스에 담는 안을 버렸다. 한 장이 10MB 이고 열 장이 한 번에 온다.
- 첨부에 직접 닿는 경로를 두는 안을 버렸다. 대화를 통해서만 닿게 해 경계를 한 곳에 둔다.
- 올릴 때의 파일 이름을 디스크 이름으로 쓰지 않는다. 그 이름이 경로를 벗어나게 만들 수 있다.
- 보내지 않은 첨부를 따로 다루지 않는다. 같은 보관 기간으로 함께 지운다.

## fos-home-infra 에서 할 것

**이 저장소에서 하지 않는다.** 배포를 요청할 때 아래를 함께 전한다.

호스트에 디렉터리 하나를 두고 두 컨테이너에 붙인다.

| 컨테이너 | 어떻게 |
| --- | --- |
| Control Plane 컨테이너 | **쓰기로** 붙인다 |
| Hermes 컨테이너 | **읽기로** 붙인다 |

지금은 그런 자리가 없다. `fos-agents` 체크아웃은 Control Plane 컨테이너에 읽기로만 붙어 있다.

Control Plane 이 그 경로를 설정으로 받는다. 값은 그 저장소가 갖는다.

함께 확인할 것이 둘 있다. 둘 다 빠지면 배포한 뒤에야 드러난다.

- Control Plane 이 도는 계정이 그 디렉터리에 쓸 권한이 있는가
- 웹과 Control Plane 사이에 역방향 프록시가 있으면 그 요청 본문 상한이 한 장 10MB 를 넘는가

## 작업 항목

### 1. `backend/src/main/resources/db/migration/V17__chat_attachment.sql`

표 하나를 만든다. 칸과 제약은 `docs/data-schema.md` 의 「chat_attachment」 절이 정한다.

- `conversation_id` 로 찾는 인덱스를 둔다. 대화를 열 때마다 그 목록을 읽는다
- `expires_at` 으로 찾는 인덱스를 둔다. 지우는 일이 그것으로 고른다
- `message_id` 는 비어 있을 수 있다. 올렸지만 아직 보내지 않은 것이다
- `deleted_at` 도 비어 있을 수 있다
- **`stored_name` 도 NULL 을 허용한다.** 값이 `{id}.{확장자}` 인데 번호는 행을 넣은 뒤에야 생긴다.
  같은 트랜잭션에서 번호를 받은 직후 채우므로 커밋된 행에는 언제나 값이 있다.
  그 까닭을 마이그레이션 주석에 적는다

번호를 쓰기 전에 아래로 다음 빈 번호를 확인한다. 이미 쓰였으면 그다음 번호로 만든다.

```bash
# cwd: 저장소 root
ls backend/src/main/resources/db/migration/
```

### 2. `chat/domain/ChatAttachment.java`

엔티티다. `chat` 패키지의 기존 엔티티가 쓰는 방식을 그대로 따른다.
어느 파일이 그것인지는 아래로 찾는다.

```bash
# cwd: 저장소 root
ls backend/src/main/java/com/bifos/assistant/chat/domain/
```

- `conversation_id` 와 `message_id` 와 `uploaded_by_user_id` 를 연관관계가 아니라 번호로 갖는다
- `of(...)` 로 만든다. `stored_name` 은 비워 둔다
- `nameStoredFile(String storedName)` 로 번호를 받은 뒤 디스크 이름을 채운다
- `markDeleted(Instant now)` 를 낸다. 메시지에 묶는 것은 저장소의 조건부 갱신이 한다. 아래 5를 본다
- `isVisible()` 은 `deleted_at` 이 비어 있는지를 돌려준다

### 3. `chat/application/AttachmentProperties.java`

설정을 받는다. `agent/application/ModelSelectionProperties.java` 가 쓰는 방식을 따른다.

| 값 | 뜻 | 기본값 |
| --- | --- | --- |
| `root` | 사진을 두는 디렉터리 뿌리 | 없다. **비면 기동을 실패시킨다** |
| `maxFiles` | 한 번에 올릴 수 있는 장수 | 10 |
| `maxBytes` | 한 장의 상한 | 10485760 |
| `retentionDays` | 보관 기간 | 30 |

**`root` 에 기본값을 두지 마라.** 기본값이 있으면 붙이지 않은 채 배포해도 기동이 성공하고,
사진이 컨테이너 안에만 쌓여 에이전트가 보지 못한다.

### 4. `chat/infra/AttachmentStore.java`

파일을 다루는 자리다. **경로를 만드는 규칙이 이 클래스 하나에만 있다.**

| 메서드 | 하는 일 |
| --- | --- |
| `save(Long conversationId, Long attachmentId, String extension, InputStream body)` | 그 자리에 쓴다 |
| `open(ChatAttachment attachment)` | 읽을 것을 연다 |
| `delete(ChatAttachment attachment)` | 파일을 지운다. 없어도 실패하지 않는다 |

- 경로는 `{root}/{conversationId}/{attachmentId}.{확장자}` 다
- **확장자는 `content_type` 에서 만든다.** 올릴 때의 파일 이름에서 꺼내지 마라
- 받는 형식은 `image/jpeg`, `image/png`, `image/gif`, `image/webp` 넷이다.
  그 밖은 거절한다

### 5. `chat/application/AttachmentService.java`

받고 판정하고 행을 만든다.

| 메서드 | 하는 일 |
| --- | --- |
| `upload(CurrentUser, Long conversationId, ...)` | 한 장을 올린다 |
| `read(CurrentUser, Long conversationId, Long attachmentId)` | 한 장을 읽는다 |
| `deleteByUser(CurrentUser, Long conversationId, Long attachmentId)` | 먼저 지운다 |
| `requireAttachable(...)`, `attach(...)` | 보낸 메시지에 묶는다. 아래 5-1 을 본다 |
| `allOf(Long conversationId)` | 그 대화의 첨부 목록. 지워진 것도 담는다 |

**대화의 주인인지 먼저 본다.** 그 판정은 지금 `ChatService` 의 `private requireOwnConversation` 하나뿐이다.
phase-02 에서 `ChatService` 가 이 클래스를 부르므로, 이 클래스가 `ChatService` 를 부르면 순환 의존이 된다.

**그 메서드를 `chat/application/ConversationAccess.java` 로 옮긴다.**
`requireOwn(CurrentUser, Long conversationId)` 하나를 내고 `ChatService` 와 이 클래스가 함께 쓴다.
판정 로직을 새로 만들지 않고 옮기기만 한다. `ChatServiceTest` 가 그대로 통과해야 한다.

**첨부는 언제나 `(attachmentId, conversationId)` 로 함께 찾는다.**
대화 주인만 확인하고 첨부를 번호로만 찾으면 내 대화 번호에 남의 첨부 번호를 붙여 읽을 수 있다.
`read` 와 `deleteByUser` 가 모두 `findByIdAndConversationId` 로 찾고, 없으면 `CONVERSATION_NOT_FOUND` 다.

`upload` 의 순서다.

1. 그 대화의 주인이 아니면 `CONVERSATION_NOT_FOUND`
2. 형식이 받는 넷에 없으면 `VALIDATION_FAILED`
3. 크기가 `maxBytes` 를 넘으면 `VALIDATION_FAILED`
4. 그 대화에서 **아직 메시지에 묶이지 않은 보이는 첨부**가 이미 `maxFiles` 이면 `VALIDATION_FAILED`.
   `message_id IS NULL AND deleted_at IS NULL` 을 센다. 상한은 한 번 보낼 때의 장수다.
   대화 전체를 세면 10장을 보낸 대화에서 30일 동안 사진을 더 올리지 못한다
5. 행을 먼저 만들어 번호를 얻고 `nameStoredFile` 로 디스크 이름을 채운다
6. 그 번호로 파일을 쓴다
7. 쓰다 실패하면 그 행을 남기지 않는다

`upload` 는 `@Transactional` 이다. **6번이 실패하면 예외로 트랜잭션을 롤백해 5번의 행을 남기지 않는다.**
파일 없는 행이 남으면 화면이 깨진 사진을 보인다.
이때는 행을 남기지 않는 것이 맞다. 보인 적이 없는 첨부라 지난 대화에 남길 것이 없다.

`deleteByUser` 는 이미 지워진 첨부에 다시 불려도 실패하지 않는다. 파일을 지운 뒤 `markDeleted` 한다.

### 5-1. 메시지에 묶는 규칙

phase-02 가 부른다. 규칙과 그 검사는 이 phase 가 갖는다.

| 메서드 | 하는 일 |
| --- | --- |
| `requireAttachable(Long conversationId, List<Long> attachmentIds)` | 메시지를 저장하기 **전에** 판정한다 |
| `attach(Long messageId, Long conversationId, List<Long> attachmentIds)` | 저장한 메시지에 묶는다 |

`requireAttachable` 이 거절하는 경우다. 모두 `VALIDATION_FAILED` 이고 어느 경우인지 갈라 알리지 않는다.

- 그 번호가 이 대화의 것이 아니다. 없는 번호도 같다
- 이미 다른 메시지에 묶였다
- 지워졌다
- 같은 번호가 목록에 두 번 있다
- 목록이 `maxFiles` 를 넘는다

`attach` 는 `message_id IS NULL AND deleted_at IS NULL AND conversation_id = ?` 조건으로 갱신하는 쿼리 하나다.
**갱신된 행 수가 목록 길이와 다르면 `VALIDATION_FAILED` 를 던진다.**
두 요청이 같은 첨부를 동시에 묶으려 할 때 뒤의 것이 여기서 걸린다.
`null` 과 빈 목록은 아무것도 하지 않는다.

`allOf` 는 지워진 것까지 번호 순으로 돌려준다. 지난 대화에 그 자리를 남겨야 하기 때문이다.

`read` 는 `deleted_at` 이 있으면 `ATTACHMENT_GONE` 을 낸다.

### 6. `chat/presentation/AttachmentController.java`

경로와 응답 모양은 `docs/code-architecture.md` 의 「경로」가 정한다.

| 경로 | 하는 일 |
| --- | --- |
| `POST /api/v1/chat/conversations/{id}/attachments` | 한 장을 올린다. multipart 로 받는다 |
| `GET /api/v1/chat/conversations/{id}/attachments/{attachmentId}` | 그 사진의 본문 |
| `DELETE /api/v1/chat/conversations/{id}/attachments/{attachmentId}` | 먼저 지운다 |

요청과 응답 record 는 `chat/presentation/ChatDtos.java` 에 더한다.
컨트롤러 안에 record 를 두지 않는다. `backend/AGENTS.md` 가 그렇게 정한다.

`AttachmentView` 에 담을 것이다.

| 칸 | 뜻 |
| --- | --- |
| `id` | 첨부 번호 |
| `originalName` | 올릴 때의 파일 이름 |
| `byteSize` | |
| `visible` | 아직 볼 수 있는가 |
| `expiresAt` | 언제까지 볼 수 있는가 |

**본문을 이 record 에 담지 않는다.** 사진은 `GET` 경로가 따로 돌려준다.
사진의 주소도 담지 않는다. 화면이 대화 번호와 첨부 번호로 만든다.

`GET` 은 저장한 `content_type` 을 `Content-Type` 으로 주고 `Cache-Control: private` 을 붙인다.

### 6-1. multipart 상한을 서비스 상한에 맞춘다

Spring 의 multipart 기본 상한은 파일 한 장 1MB 다.
그대로 두면 서비스의 `maxBytes` 판정 전에 걸려 휴대폰 사진 대부분이 500 `INTERNAL_ERROR` 로 끝난다.

- `application.yml` 에 `spring.servlet.multipart.max-file-size: 11MB` 와 `max-request-size: 12MB` 를 둔다.
  10MB 를 조금 넘는 것은 서비스가 `VALIDATION_FAILED` 로 거절하게 한다
- `shared/error/GlobalExceptionHandler.java` 가 `MaxUploadSizeExceededException` 을
  `VALIDATION_FAILED` 로 바꾼다. 그보다 큰 것이 500 으로 끝나지 않게 한다

### 7. 오류 코드를 하나 더한다

`shared/error/ErrorCode.java` 에 더한다. 기존 이름 짓는 방식과 주석 방식을 따른다.

| 코드 | 상태 | 언제 |
| --- | --- | --- |
| `ATTACHMENT_GONE` | 410 | 보관 기간이 지났거나 사용자가 지웠다 |

**없는 첨부를 이 코드로 내지 마라.** 그것은 `CONVERSATION_NOT_FOUND` 와 같이 숨긴다.
410 은 있었다는 것을 알리는 응답이라, 없는 번호에 쓰면 번호를 훑어 남의 것을 알아낼 수 있다.

### 8. 보관 기간이 지난 것을 지운다

`chat/application/AttachmentCleaner.java` 를 만든다.

**이 저장소에 일정 실행이 하나도 없다.** `@Scheduled` 와 `@EnableScheduling` 을 쓰는 곳이 없다.
그래서 따를 선례가 없고 **그것을 켜는 설정부터 더해야 한다.**

- `@EnableScheduling` 을 더한다. `AssistantApplication` 이나 별도 설정 클래스에 둔다
- `@Scheduled(cron = "${assistant.attachment.cleanup-cron}")` 로 하루에 한 번 돈다.
  `application.yml` 의 값은 `0 0 4 * * *` 이다. 시각은 Control Plane 의 시간대를 따른다
- `expires_at` 이 지났고 `deleted_at` 이 비어 있는 행을 고른다
- 파일을 지우고 `deleted_at` 을 적는다
- **한 건이 실패해도 나머지를 계속한다.** 하나 때문에 그날 치가 통째로 멈추면 안 된다
- 몇 건을 지웠는지 로그에 남긴다

**검사에서 이것이 돌지 않게 한다.** 테스트가 뜰 때마다 일정 실행이 함께 도는 것을 막는다.
`src/test/resources/application-test.yml` 에서 `assistant.attachment.cleanup-cron: "-"` 로 끈다.
Spring 은 cron 값 `-` 를 「돌지 않는다」 로 읽는다.
그 파일의 이름을 `application.yml` 로 두면 `smokeRun` 이 실제 설정 대신 그것을 읽는다.
`backend/AGENTS.md` 가 그 사고를 적었다.

### 8-1. 검사가 기동할 수 있게 값을 준다

`root` 에 기본값이 없으므로 값을 주지 않으면 검사가 모두 기동하지 못한다. 세 곳에 준다.

| 파일 | 값 |
| --- | --- |
| `backend/src/test/resources/application-test.yml` | `build/test-attachments` |
| `test/e2e/run.ts` | `mkdtemp` 로 만든 `work` 아래 `attachments` |
| `test/browser/fixtures.ts` | 같은 방식으로 `work` 아래 `attachments` |

`run.ts` 와 `fixtures.ts` 는 `startControlPlane` 이 넘기는 환경 변수에 `ASSISTANT_ATTACHMENT_ROOT` 를 더한다.
`application.yml` 은 `root: ${ASSISTANT_ATTACHMENT_ROOT}` 로 받는다.
그 표기는 환경 변수가 아예 없을 때만 기동을 멈춘다. **빈 문자열도 막도록 `AttachmentProperties` 의
compact constructor 가 비었는지 확인해 예외를 던진다.**

**검사는 그 메서드를 직접 불러서 한다.** 일정이 돌기를 기다리지 않는다.

### 8-2. 첫 사진을 올릴 때 빈 대화를 만든다

새 대화는 지금 첫 메시지를 보낼 때 생긴다. 올리는 경로에는 대화 번호가 필요하므로
**그대로면 새 대화의 첫 메시지에 사진을 붙일 수 없다.**
사진을 올리며 무엇을 해 달라고 하는 것이 이 기능의 주 용도라서 그 경우를 막으면 안 된다.

**`POST /api/v1/chat/conversations` 를 더한다.** 본문은 `agentCode` 하나이고 대화 번호를 돌려준다.

- `ChatController` 에 두고 요청과 응답 record 는 `ChatDtos.java` 에 둔다
- `ChatService.startEmpty(CurrentUser, String agentCode)` 가 만든다.
  에이전트를 고르는 판정은 지금 `resolveConversation` 이 새 대화를 만들 때 쓰는 것을 그대로 쓴다.
  `requireReadable`, `enabled` 확인이 그것이다. 새로 만들지 않고 한 메서드로 뽑아 둘이 함께 쓴다
- 그 에이전트가 첨부를 받지 않으면 `VALIDATION_FAILED` 다. 흐름이 붙은 에이전트가 그렇다.
  판정은 `agent/domain/Agent.java` 에 `acceptsAttachments()` 하나로 둔다. 흐름이 비어 있으면 참이다.
  phase-02 의 거절과 화면용 칸도 이 메서드를 부른다. 조건이 한 곳에만 있게 한다
  이 경로는 사진을 올리려고만 쓰므로 받지 않는 에이전트에 빈 대화를 남기지 않는다
- **제목은 비워 둔다.** 빈 문자열로 만든다
- 그 대화에 첫 메시지가 오면 제목이 비어 있을 때만 지금 규칙(`titleFrom`)으로 채운다.
  `Conversation` 에 `titleIfBlank(String)` 을 더한다. 제목을 정하는 규칙은 지금 것 하나다

만들고 메시지를 보내지 않은 대화도 목록에 남는다. 제목이 비어 있으므로 화면이 「새 대화」 로 보인다.
화면은 phase-03 이 한다.

### 9. 이 phase 를 검증하는 테스트

`backend/src/test/java/com/bifos/assistant/chat/AttachmentServiceTest.java`

| 무엇 | 기대 |
| --- | --- |
| 자기 대화에 올린다 | 행이 생기고 파일이 그 자리에 있다 |
| 남의 대화에 올린다 | `CONVERSATION_NOT_FOUND`. 파일이 생기지 않았다 |
| 받지 않는 형식 | 거절. 행이 생기지 않았다 |
| 상한을 넘는 크기 | 거절. 행이 생기지 않았다 |
| 묶이지 않은 10장이 있는 대화에 한 장 더 | 거절 |
| 지운 첨부를 읽는다 | `ATTACHMENT_GONE` |
| 없는 번호를 읽는다 | `CONVERSATION_NOT_FOUND`. `ATTACHMENT_GONE` 이 아니다 |
| 사용자가 지운다 | 파일이 사라지고 행은 남는다. `deleted_at` 이 찼다 |
| 파일 쓰기가 실패한다 | 행이 남지 않는다 |
| 내 대화 번호에 남의 대화의 첨부 번호를 붙여 읽는다 | `CONVERSATION_NOT_FOUND`. 본문을 돌려주지 않는다 |
| 보낸 10장이 있는 대화에 새로 한 장을 올린다 | 성공한다. 묶이지 않은 것만 센다 |
| `requireAttachable` 에 남의 것, 묶인 것, 지워진 것, 없는 번호 | 넷 모두 `VALIDATION_FAILED` |
| `attach` 로 이미 묶인 첨부를 다시 묶는다 | `VALIDATION_FAILED`. 처음 묶인 메시지를 그대로 가리킨다 |

`backend/src/test/java/com/bifos/assistant/chat/EmptyConversationTest.java`

| 무엇 | 기대 |
| --- | --- |
| 쓸 수 있는 에이전트로 빈 대화를 만든다 | 대화가 생기고 제목이 비어 있다. 메시지는 없다 |
| 그 대화에 첫 메시지를 보낸다 | 제목이 그 글로 채워진다 |
| 둘째 메시지를 보낸다 | 제목이 바뀌지 않는다 |
| 읽을 수 없는 에이전트, 꺼진 에이전트 | 지금 새 대화를 만들 때와 같은 오류다 |
| 흐름이 붙은 에이전트 | `VALIDATION_FAILED`. 대화가 생기지 않았다 |

파일 쓰기 실패는 그 대화 번호 자리에 일반 파일을 먼저 만들어 두어 디렉터리를 만들지 못하게 해서 일으킨다.

`backend/src/test/java/com/bifos/assistant/chat/AttachmentUploadLimitTest.java`

**MockMvc 로 쓰지 않는다.** MockMvc 의 multipart 요청은 Tomcat 과 `StandardServletMultipartResolver` 의
크기 상한을 거치지 않아, `max-file-size` 를 빠뜨려도 통과한다.
`@SpringBootTest(webEnvironment = RANDOM_PORT)` 로 실제 서버를 띄우고 실제 HTTP 클라이언트로 올린다.
토큰은 `ASSISTANT_JWT_SECRET` 과 같은 값으로 검사 안에서 만든다.

| 무엇 | 기대 |
| --- | --- |
| 2MB 본문을 multipart 로 올린다 | 성공한다. 기본 상한 1MB 에 걸리지 않는다 |
| 10MB 를 조금 넘는 본문 | 400 `VALIDATION_FAILED`. 서비스 판정에 걸린다 |
| 12MB 를 넘는 본문 | 400 `VALIDATION_FAILED`. 500 이 아니다 |

`backend/src/test/java/com/bifos/assistant/chat/AttachmentCleanerTest.java`

| 무엇 | 기대 |
| --- | --- |
| 기간이 지난 것 | 파일이 지워지고 `deleted_at` 이 찬다 |
| 기간이 남은 것 | 그대로 있다 |
| 이미 지워진 것 | 다시 지우지 않는다 |
| 메시지에 묶이지 않은 것 | 기간이 지났으면 함께 지운다 |
| 한 건이 실패한다 | 나머지가 지워진다 |

## 검증

```bash
# cwd: 저장소 root
cd backend && ./gradlew test
cd web && pnpm typecheck && pnpm build
cd web && pnpm test:browser
node test/e2e/run.ts
scripts/check-public-safe.sh
```

`pnpm build` 가 요구하는 환경 변수는 `web/AGENTS.md` 의 「검사」 절이 갖는다.
빠뜨리면 `Failed to collect page data` 로 끝난다.

아래가 아무것도 내지 않아야 한다. 경로를 만드는 규칙이 한 곳에만 있어야 한다.

```bash
# cwd: 저장소 root
grep -rln "Paths.get\|Path.of" backend/src/main/java/com/bifos/assistant/chat | grep -v AttachmentStore
```

## Critical Files

| 파일 | 변경 |
|---|---|
| `backend/src/main/resources/db/migration/V17__chat_attachment.sql` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/domain/ChatAttachment.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/infra/ChatAttachmentRepository.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/infra/AttachmentStore.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/application/AttachmentProperties.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/application/AttachmentService.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/application/ConversationAccess.java` | 신규. `ChatService` 에서 옮긴다 |
| `backend/src/main/java/com/bifos/assistant/chat/application/ChatService.java` | 수정. 소유 판정을 `ConversationAccess` 로 넘긴다 |
| `backend/src/main/java/com/bifos/assistant/shared/config/SchedulingConfig.java` | 신규. `@EnableScheduling` |
| `backend/src/main/java/com/bifos/assistant/shared/error/GlobalExceptionHandler.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/application/AttachmentCleaner.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/presentation/AttachmentController.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/presentation/ChatDtos.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/shared/error/ErrorCode.java` | 수정 |
| `backend/src/main/resources/application.yml` | 수정 |
| `backend/src/test/resources/application-test.yml` | 수정 |
| `test/e2e/run.ts` | 수정. 환경 변수 하나 |
| `test/browser/fixtures.ts` | 수정. 환경 변수 하나 |
| `backend/src/test/java/com/bifos/assistant/chat/AttachmentServiceTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/chat/AttachmentUploadLimitTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/chat/EmptyConversationTest.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/presentation/ChatController.java` | 수정. 빈 대화를 만드는 경로 |
| `backend/src/main/java/com/bifos/assistant/chat/domain/Conversation.java` | 수정. `titleIfBlank` |
| `backend/src/main/java/com/bifos/assistant/agent/domain/Agent.java` | 수정. `acceptsAttachments()` |
| `backend/src/test/java/com/bifos/assistant/chat/AttachmentCleanerTest.java` | 신규 |

## 끝낸 뒤

`tasks/plan018-chat-photo-upload/index.json` 의 이 phase 를 `completed` 로 바꾸고
`current_phase` 를 2로 올린다.

**배포하지 않는다.** 화면도 없고 에이전트도 사진을 모른다. 셋을 함께 배포한다.
