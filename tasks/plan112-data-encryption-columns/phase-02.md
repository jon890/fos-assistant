# Phase 02. 대화 제목과 예약 작업의 제목과 지시문을 암호화한다

**Execution profile**: standard

## 목표

`conversation.title`, `task.title`, `task.instruction` 을 저장할 때 암호화한다.
대화 제목은 첫 메시지의 앞부분이라 본문을 그대로 담고, 예약 작업의 지시문은 사용자가 쓴 글이다.

**범위 외**: 이미 있는 평문 줄 옮기기(phase 03). 작업 이름이 복사돼 들어가는 알림(`notification.title`)은 이 계획에서 하지 않는다.

## 컨텍스트

**근거 문서**: `backend/docs/adr/ADR-20261008-data-encryption.md`, `backend/docs/data-schema.md` 의 「본문 칸과 운영 조회」, `backend/docs/data-schema.md` 의 「conversation」, `backend/docs/data-schema.md`

- 본보기는 메시지다. `backend/src/main/java/com/bifos/assistant/chat/domain/ChatMessage.java` 의 저장 칸(`storedContent`)과 옆 칸(`contentKeyId`), 처음 꺼낼 때 푸는 `content()`, `backend/src/main/java/com/bifos/assistant/chat/infra/ChatMessageLoadListener.java` 의 `POST_LOAD` 등록, `backend/src/main/java/com/bifos/assistant/chat/infra/ChatMessageContents.java` 의 AAD 와 주인 조회를 따른다
- 대화 제목
  - 엔티티 `backend/src/main/java/com/bifos/assistant/chat/domain/Conversation.java` 의 `title`(`VARCHAR(200) NOT NULL`). 주인은 `userId`(바뀌지 않는다). 만드는 자리는 `startedBy`, `startedForCheck`, `startedForTask` 와 `titleIfBlank`
  - **JPQL 로 칸을 직접 쓰는 질의가 셋이다.** `backend/src/main/java/com/bifos/assistant/chat/infra/ConversationRepository.java` 의 `fillTitleIfBlank`(`where c.title = ''`), `renameIfActive`, `markPurged`(`set c.title = ''`). 엔티티 리스너를 거치지 않으므로 부르는 쪽이 암호문과 key 번호를 넘긴다
  - 부르는 쪽은 `backend/src/main/java/com/bifos/assistant/chat/application/ChatTurnRouting.java` 의 `conversationWriter.fillTitleIfBlank(conversation.id(), title)` 과 `backend/src/main/java/com/bifos/assistant/chat/application/ChatConversationManagement.java` 의 `conversationWriter.renameIfActive(...)` 다. 둘 다 `backend/src/main/java/com/bifos/assistant/chat/application/ConversationWriter.java` 를 지난다
  - 읽는 곳은 목록과 응답(`backend/src/main/java/com/bifos/assistant/chat/presentation/ChatController.java`), 먼저 알리기의 후보(`backend/src/main/java/com/bifos/assistant/attention/application/RecentConversationCandidates.java`, `DelegationCandidates.java`, `FailedTurnCandidates.java`)다. 모두 `title()` 을 거치면 바꿀 것이 없다
- 예약 작업
  - 엔티티 `backend/src/main/java/com/bifos/assistant/task/domain/Task.java` 의 `title`(`VARCHAR(100) NOT NULL`, `TITLE_MAX = 100`)과 `instruction`(`TEXT NULL`, 점검 작업은 null). 주인은 `ownerUserId`
  - 쓰기는 `create`, `check`, `apply`(편집)이고 `backend/src/main/java/com/bifos/assistant/task/application/TaskService.java` 가 부른다
  - 읽는 곳은 `TaskRunStarter`(지시문을 예약 turn 의 질문으로 보낸다), `TaskNotices`(알림 제목), `TaskLabelSource`(대화 목록의 작업 이름), `TaskDtos` 다. 모두 `backend/src/main/java/com/bifos/assistant/task/` 아래다

## 의도 메모

- 빈 제목은 평문 `''` 로 두고 옆 칸을 비운다. `fillTitleIfBlank` 의 `c.title = ''` 판정을 그대로 쓰기 위해서다. 빈 글은 감출 내용이 없다
- AAD 는 `conversation:<id>:title:user:<user_id>`, `task:<id>:title:user:<owner_user_id>`, `task:<id>:instruction:user:<owner_user_id>` 다
- 칸 길이를 늘린다. 암호문은 평문 UTF-8 바이트의 약 4/3 에 44자쯤이 붙는다. 200자 한글 제목은 약 850자가 된다. 글자 수 검사(`Conversation.normalizedTitle`, `TITLE_MAX`)는 평문에 그대로 둔다
- 대화 주인 캐시는 `ChatMessageContents` 의 것을 같이 쓴다. 예약 작업은 엔티티에 주인이 있어 조회가 없다

## 작업 항목

### 1. 마이그레이션

`backend/src/main/resources/db/migration/V<UTC 시각>__title_and_task_content_key.sql` 에 다음을 둔다. DDL 만 둔다.

```sql
ALTER TABLE conversation MODIFY COLUMN title VARCHAR(1024) NOT NULL;
ALTER TABLE conversation ADD COLUMN title_key_id BIGINT NULL;
ALTER TABLE task MODIFY COLUMN title VARCHAR(512) NOT NULL;
ALTER TABLE task ADD COLUMN title_key_id BIGINT NULL;
ALTER TABLE task ADD COLUMN instruction_key_id BIGINT NULL;
```

엔티티의 `length` 도 같은 값으로 바꾼다. 평문 길이 상한 상수는 그대로다.

### 2. 대화 제목

- `Conversation` 의 `title` 필드를 `storedTitle`(`@Column(name = "title")`) 로 바꾸고 `titleKeyId` 와 복호화 도구를 둔다. `title()` 은 `ChatMessage.content()` 와 같은 규칙으로 푼다. 엔티티 접근자는 Lombok 으로 만든다
- 새 대화를 저장하는 `ConversationRepository.save` 의 부르는 쪽은 많다. `ChatMessageWritesImpl` 처럼 `chat.infra` 에 `ConversationWrites` 조각을 두어 `save` 에서 빈 제목이 아니면 빈 글로 넣고 번호로 암호화한다
- `fillTitleIfBlank(id, title, titleKeyId)` 와 `renameIfActive(id, userId, title, titleKeyId, now)` 로 바꾸고 `ConversationWriter` 가 `TextCipher` 로 암호화한 값을 넘긴다. `markPurged` 는 `c.titleKeyId = null` 도 적는다
- `ChatMessageLoadListener.onPostLoad` 가 `Conversation` 에도 도구를 붙인다

### 3. 예약 작업

- `Task` 의 `title` 과 `instruction` 을 같은 방식으로 바꾼다(`storedTitle`, `titleKeyId`, `storedInstruction`, `instructionKeyId`)
- `TaskService` 의 만들기와 고치기는 저장한 뒤 번호로 두 칸을 암호화한다. 같은 트랜잭션이다
- `task.infra` 에 `TaskLoadListener` 를 두어 `POST_LOAD` 에서 도구를 붙인다. `ChatMessageLoadListener.register` 와 같은 방법이다

### 4. 시험

- `backend/src/test/java/com/bifos/assistant/chat/ConversationTitleEncryptionTest.java`(신규, `@BackendIntegrationTest`)
  - 첫 메시지로 채운 제목과 바꾼 이름이 데이터베이스에서 암호문이고 목록 응답에서 평문이다
  - 빈 제목은 평문 `''` 이고 `title_key_id` 가 null 이며, 첫 메시지가 그 제목을 채운다
  - 두 대화의 제목 암호문을 맞바꾸면 둘 다 읽을 수 없는 글이다
- `backend/src/test/java/com/bifos/assistant/task/TaskContentEncryptionTest.java`(신규, `@BackendIntegrationTest`)
  - 만든 작업의 제목과 지시문이 암호문이고 작업 응답과 예약 turn 의 질문은 평문이다
  - 점검 작업의 지시문은 null 이고 옆 칸도 null 이다

## 검증

```bash
cd backend && ./gradlew test --tests '*ConversationTitleEncryptionTest' --tests '*TaskContentEncryptionTest' --tests '*ConversationPurgerTest'
cd backend && ./gradlew test
cd backend && ./gradlew qualityCheck
scripts/check-mysql-migration.sh
```

넷 다 실패 없이 끝난다. 마지막 명령은 Docker 가 있어야 돈다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/resources/db/migration/V*__title_and_task_content_key.sql` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/domain/Conversation.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/infra/ConversationRepository.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/infra/ConversationWrites.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/infra/ConversationWritesImpl.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/infra/ChatMessageContents.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/infra/ChatMessageLoadListener.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/application/ConversationWriter.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/task/domain/Task.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/task/application/TaskService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/task/infra/TaskLoadListener.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/chat/ConversationTitleEncryptionTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/task/TaskContentEncryptionTest.java` | 신규 |
| `backend/docs/data-schema.md` | 수정 |
| `backend/docs/data-schema.md` | 수정 |
| `backend/docs/data-schema.md` | 수정 |
