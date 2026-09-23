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

## 작업 항목

### 1. `backend/src/main/resources/db/migration/V17__chat_attachment.sql`

표 하나를 만든다. 칸과 제약은 `docs/data-schema.md` 의 「chat_attachment」 절이 정한다.

- `conversation_id` 로 찾는 인덱스를 둔다. 대화를 열 때마다 그 목록을 읽는다
- `expires_at` 으로 찾는 인덱스를 둔다. 지우는 일이 그것으로 고른다
- `message_id` 는 비어 있을 수 있다. 올렸지만 아직 보내지 않은 것이다
- `deleted_at` 도 비어 있을 수 있다

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
- `of(...)` 로 만든다
- `attachToMessage(Long messageId)` 와 `markDeleted(Instant now)` 를 낸다
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
| `attach(Long messageId, List<Long> attachmentIds)` | 보낸 메시지에 묶는다 |
| `visibleOf(Long conversationId)` | 그 대화의 첨부 목록 |

**대화의 주인인지 먼저 본다.** 그 판정을 이 클래스가 새로 만들지 않고
`chat` 패키지에 이미 있는 것을 쓴다. 어느 것인지 아래로 찾아 그것을 쓴다.

```bash
# cwd: 저장소 root
grep -rn "CONVERSATION_NOT_FOUND" backend/src/main/java
```

`upload` 의 순서다.

1. 그 대화의 주인이 아니면 `CONVERSATION_NOT_FOUND`
2. 형식이 받는 넷에 없으면 `VALIDATION_FAILED`
3. 크기가 `maxBytes` 를 넘으면 `VALIDATION_FAILED`
4. 그 대화의 보이는 첨부가 이미 `maxFiles` 이면 `VALIDATION_FAILED`
5. 행을 먼저 만들어 번호를 얻는다
6. 그 번호로 파일을 쓴다
7. 쓰다 실패하면 그 행을 지운다

**6번이 실패하면 5번의 행을 지운다.** 파일 없는 행이 남으면 화면이 깨진 사진을 보인다.
이때는 행을 지우는 것이 맞다. 보인 적이 없는 첨부라 지난 대화에 남길 것이 없다.

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
- `@Scheduled` 로 하루에 한 번 돈다. 도는 시각은 설정으로 받는다
- `expires_at` 이 지났고 `deleted_at` 이 비어 있는 행을 고른다
- 파일을 지우고 `deleted_at` 을 적는다
- **한 건이 실패해도 나머지를 계속한다.** 하나 때문에 그날 치가 통째로 멈추면 안 된다
- 몇 건을 지웠는지 로그에 남긴다

**검사에서 이것이 돌지 않게 한다.** 테스트가 뜰 때마다 일정 실행이 함께 도는 것을 막는다.
`src/test/resources/application-test.yml` 에서 끄는 것이 한 방법이다.
그 파일의 이름을 `application.yml` 로 두면 `smokeRun` 이 실제 설정 대신 그것을 읽는다.
`backend/AGENTS.md` 가 그 사고를 적었다.

**검사는 그 메서드를 직접 불러서 한다.** 일정이 돌기를 기다리지 않는다.

### 9. 이 phase 를 검증하는 테스트

`backend/src/test/java/com/bifos/assistant/chat/AttachmentServiceTest.java`

| 무엇 | 기대 |
| --- | --- |
| 자기 대화에 올린다 | 행이 생기고 파일이 그 자리에 있다 |
| 남의 대화에 올린다 | `CONVERSATION_NOT_FOUND`. 파일이 생기지 않았다 |
| 받지 않는 형식 | 거절. 행이 생기지 않았다 |
| 상한을 넘는 크기 | 거절. 행이 생기지 않았다 |
| 이미 10장이 있는 대화에 한 장 더 | 거절 |
| 지운 첨부를 읽는다 | `ATTACHMENT_GONE` |
| 없는 번호를 읽는다 | `CONVERSATION_NOT_FOUND`. `ATTACHMENT_GONE` 이 아니다 |
| 사용자가 지운다 | 파일이 사라지고 행은 남는다. `deleted_at` 이 찼다 |
| 파일 쓰기가 실패한다 | 행이 남지 않는다 |

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
grep -rn "attachment" backend/src/main/java --include='*.java' | grep -i "Paths.get\|Path.of" | grep -v AttachmentStore
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
| `backend/src/main/java/com/bifos/assistant/chat/application/AttachmentCleaner.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/presentation/AttachmentController.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/presentation/ChatDtos.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/shared/error/ErrorCode.java` | 수정 |
| `backend/src/main/resources/application.yml` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/AttachmentServiceTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/chat/AttachmentCleanerTest.java` | 신규 |

## 끝낸 뒤

`tasks/plan018-chat-photo-upload/index.json` 의 이 phase 를 `completed` 로 바꾸고
`current_phase` 를 2로 올린다.

**배포하지 않는다.** 화면도 없고 에이전트도 사진을 모른다. 셋을 함께 배포한다.
