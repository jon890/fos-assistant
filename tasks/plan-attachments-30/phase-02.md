# Phase 02. 사진 선택 순서 저장

**Execution profile**: deep

## 목표

사용자가 고른 사진 순서를 화면 미리보기, 메시지 말풍선, Hermes 입력과 사진 순번에서 같게 유지한다.

**범위 외**: 사진 장수와 크기 상한, 보관 기간, 파일 이름 규칙, Hermes API 형식, 사진 순서 변경 기능과 기존 대화의 사용자 선택 순서 복원.

## 컨텍스트

`Composer.handleFiles` 는 한 번 고른 파일의 자리표를 `itemsRef`에 넣어 순서를 보존하지만, 새 대화에서는 `ensureConversationId`를 기다린 뒤에야 자리표를 넣는다.
그 기다림 사이에 두 선택이 함께 들어오면 둘 다 오래된 `items` 길이로 남은 수를 계산하므로, 16장과 16장을 동시에 고른 경우 30장보다 많은 자리표와 upload를 시작할 수 있다.
새 대화의 자리표에는 아직 대화 ID가 없으므로, 현재 `AttachmentItem.conversationId: string`과 삭제·unmount 정리는 예약 상태를 표현하거나 안전하게 정리할 수 없다.

서버의 디스크 파일 이름은 첨부 ID 이며, 동시 upload의 ID 순서도 사용자가 고른 순서를 보장하지 않는다.
`AttachmentService.allOf`, 재생성 경로, `ChatService.attachmentsByMessage` 는 현재 ID 순으로 읽고, `AttachmentService.orderInConversation` 도 메시지 ID와 첨부 ID로 순번을 만든다.

`ChatAttachmentRepository.attachToMessage` 는 조건부 bulk update 하나로 `message_id`만 적는다.
새 `position` 을 요청 배열 순서대로 쓰려면 같은 트랜잭션 안에서 첨부 하나씩 `message_id is null`과 `deleted_at is null` 조건을 지킨 update를 실행하고, 어느 하나라도 1행을 갱신하지 못하면 예외로 전체를 되돌려야 한다.
사진은 한 메시지에 최대 30장이므로 이 선택은 최대 30회 update를 추가한다.

`AttachmentService.upload` 는 소유권 확인 뒤 같은 대화의 미전송·미삭제 첨부를 세어 저장한다.
서로 다른 트랜잭션이 같은 수를 읽으면 둘 다 30장 미만이라고 판단할 수 있으므로, 이 읽기와 저장을 대화 행 잠금 아래에서 직렬화해야 한다.

`docs/backend/schema/README.md`의 DDL·DML 분리 규칙에 따라 V75는 칸만 더하고 V76은 기존 행의 값을 채운다.
V75는 `chat_attachment.position`을 `INT NOT NULL DEFAULT 0`으로 추가하고, V76은 메시지에 묶인 기존 행마다 같은 메시지 안의 첨부 ID 오름차순으로 0부터 값을 채운다.
문자열 비교가 없는 숫자만 쓰는 DML이므로 `COLLATE` 구절은 넣지 않는다.

**근거 문서**: `docs/backend/attachment.md`의 「에이전트에게 알리는 법」과 「갈리는 지점」, `docs/backend/schema/chat.md`의 「chat_attachment」, `docs/backend/schema/README.md`의 「DDL 과 DML 을 한 파일에 섞지 않는다」.

## 의도 메모

- 새 ADR은 만들지 않는다. ADR-020이 이미 사진을 화면 순번으로 가리키는 결정을 소유하므로, 같은 결정의 구현 계약을 `더해진 부분`에 보완한다.
- 과거 데이터에는 실제 선택 순서가 없으므로 첨부 ID 오름차순을 `position`으로 채운다. 이 결과는 되돌릴 수 없으며 PR 본문과 작업 보고서에 적는다.
- `position`은 메시지 안의 영속 순서이고 0부터 센다. 사용자에게 보이는 「N번째 사진」은 대화 전체에서 메시지 순서와 `position` 순서로 1부터 센다.
- 삭제한 사진은 행과 `position`을 남기므로, 다음 사진의 순번을 당기지 않는다.
- 한 대화에 대한 upload는 대화 행의 `PESSIMISTIC_WRITE` 잠금으로 직렬화한다. 이 잠금은 소유권 확인을 겸하고, 장수 확인부터 insert까지의 범위를 지킨다.

## 작업 항목

### 1. `chat_attachment.position`을 V75와 V76으로 추가하고 과거 행을 채운다

`backend/src/main/resources/db/migration/V75__chat_attachment_position.sql`에는 `position INT NOT NULL DEFAULT 0`을 더하는 DDL만 둔다.
`backend/src/main/resources/db/migration/V76__chat_attachment_position_backfill.sql`에는 `message_id`가 있는 기존 행을 대상으로 같은 메시지의 첨부 ID 오름차순을 0부터 채우는 DML만 둔다.
DDL과 DML을 섞지 않으며, 문자열 비교나 정렬 규칙 변경을 넣지 않는다.

`ChatAttachment`에 `position` 접근을 추가한다.
`ChatAttachmentRepository`에는 개별 첨부를 조건부로 메시지와 position에 묶는 update와 position 순서 조회를 추가한다.

### 2. upload의 소유권 확인·장수 확인·저장을 한 대화 안에서 직렬화한다

`ConversationRepository`에 `id`, `userId`, `deletedAt is null` 조건을 함께 가진 `PESSIMISTIC_WRITE` 조회를 추가한다.
`ConversationAccess`에는 기존과 같은 소유권 실패 응답을 유지하면서 이 조회를 사용하는 upload 전용 접근 메서드를 추가한다.

`AttachmentService.upload`의 첫 읽기는 이 upload 전용 소유권 확인으로 바꾼다.
잠금을 얻은 뒤에 미전송·미삭제 첨부 수를 세고 저장하므로 같은 대화에서 count와 insert가 직렬화된다.
다른 대화의 upload는 서로 막지 않으며, 31번째와 그 뒤 요청은 기존 `VALIDATION_FAILED` 응답으로 거절한다.

### 3. 첨부를 요청 순서대로 저장하고 같은 순서로 다시 읽는다

`AttachmentService.attach`는 요청한 `attachmentIds`의 배열 순서대로 0부터 position을 부여한다.
각 update가 1행을 바꾸지 못하면 기존 `VALIDATION_FAILED` 경로로 전체 트랜잭션을 되돌린다.

`AttachmentService.allOf`, `agentInput`, `orderInConversation`, `ChatService.attachmentsByMessage`와 다시 생성 경로가 메시지 순서와 `position` 순서로 읽도록 맞춘다.
V75의 `NOT NULL DEFAULT 0`과 V76 backfill 뒤에는 `position`이 비어 있는 행이 없다.
말풍선은 `ChatController`가 받은 첨부 목록 순서를 그대로 `AttachmentView`로 바꾸므로 DTO와 web API 모양은 바꾸지 않는다.

### 4. 화면에서 대화 ID를 기다리기 전에 선택 자리를 예약하고 안전하게 정리한다

`web/src/components/chat/composer.tsx`에서 `handleFiles`는 `ensureConversationId`를 기다리기 전에 `itemsRef.current` 길이로 남은 수를 계산한다.
그 수만큼의 유효 파일에 key와 `uploading` 상태를 만들고, 선택 순서대로 `conversationId: null`인 자리표를 `itemsRef.current`와 화면 상태에 즉시 예약한다.
따라서 두 선택이 16장씩 겹쳐도 뒤 선택은 남은 14장만 예약하여 총 30장을 넘지 않는다.

`ensureConversationId`가 성공하면 아직 남아 있는 예약 자리표에 대화 ID를 연결하고 각 `uploadOne`을 시작한다.
각 upload는 미리 정한 key의 썸네일, 상태와 attachment ID만 갱신하므로 썸네일 생성이나 POST 응답이 거꾸로 끝나도 미리보기와 `trySend`의 `attachmentIds`는 고른 순서를 유지한다.

`AttachmentItem.conversationId`의 nullable 예약 상태를 반영해 삭제·실패·unmount를 고친다.
대화 ID를 얻기 전에 삭제했거나 생성에 실패한 자리표는 `itemsRef`와 화면에서 제거하고 preview URL과 대기 정리 상태를 해제한다.
서버 파일이 없는 `null` 대화 ID에는 DELETE를 보내지 않으며, 대화 ID를 받은 뒤 삭제된 항목만 기존 DELETE 경로를 탄다.

### 5. 선택 순서, 동시 upload 상한과 backfill을 backend·browser 시험으로 고정한다

`ChatAttachmentPositionMigrationTest`와 `@Tag("mysql")`를 단 `ChatAttachmentPositionMysqlMigrationTest`는 Flyway target `74`까지 적용한 스키마에 서로 다른 메시지와 ID 순서의 첨부를 넣고 V75·V76을 적용해, 메시지별 position이 0부터 ID 순서로 채워지는지 확인한다.
현재 V74가 아직 기준 브랜치에 없으면 target `74`는 V73까지 적용하므로, V74가 합쳐진 뒤에도 같은 baseline을 유지한다.

`AttachmentServiceTest`는 요청 배열이 ID와 반대여도 저장 position과 조회 순서가 요청 순서인지, 조건부 갱신 충돌이면 일부 position이나 message ID가 남지 않는지 확인한다.
`AttachmentUploadLimitTest`는 H2에서 같은 대화에 실제 HTTP upload 32개를 동시에 보내 정확히 30개만 성공하고 2개가 400으로 거절되며, 남은 미전송 첨부가 30개인지 확인한다.
`AttachmentUploadLimitMysqlTest`는 `MysqlTestDatabase`와 `@Tag("mysql")`로 같은 32개 병렬 시나리오를 실제 MySQL에서 실행해 `PESSIMISTIC_WRITE` 조회와 상한이 H2에만 맞지 않는지 확인한다.

`ChatAttachmentTurnTest`는 30개를 ID와 반대인 요청 순서로 보내 Hermes 목록과 말풍선 API의 첨부 목록이 모두 같은 순서인지 확인한다.
`RegenerateDeletedAttachmentTest`는 ID와 position이 반대인 여러 사진에서 중간 position 사진을 삭제한 뒤 재생성한다.
Hermes 입력이 남은 사진을 position 순서로 보이고, 삭제한 자리가 순번을 차지해 「1번째 사진」과 「3번째 사진」은 남되 「2번째 사진」은 없는 ordinal gap을 확인한다.

`test/browser/chat-attachment.spec.ts`는 선택한 파일의 썸네일 또는 upload 응답을 역순으로 끝내는 route를 두고, 미리보기와 메시지 요청의 attachment ID 배열이 원래 선택 순서인지 확인한다.
새 대화 생성 응답을 보류한 뒤 16장과 16장을 연달아 고르는 시나리오도 추가해, 대화 ID를 기다리는 동안에도 자리표와 upload 요청이 30개를 넘지 않는지 확인한다.
기존 30장 경계와 31번째 거절 시나리오는 유지한다.

### 6. 순서 계약을 책임 문서와 ADR-020에 기록한다

`docs/backend/schema/chat.md`에는 `chat_attachment.position`의 타입, 0부터 시작하는 뜻, 기존 행의 ID 순 backfill을 적는다.
`docs/backend/attachment.md`에는 화면, Hermes 입력, 사진 순번이 같은 선택 순서를 쓰고 삭제한 사진도 자리를 차지한다는 규칙을 적는다.
ADR-020에는 날짜, 선택 순서 저장 이유, 기존 행의 ID 순 backfill과 저장·보관 정책을 바꾸지 않는다는 내용을 `더해진 부분`으로 추가한다.

## 검증

```bash
cd backend && ./gradlew test --tests com.bifos.assistant.chat.AttachmentServiceTest --tests com.bifos.assistant.chat.AttachmentUploadLimitTest --tests com.bifos.assistant.chat.ChatAttachmentTurnTest --tests com.bifos.assistant.chat.RegenerateDeletedAttachmentTest --tests com.bifos.assistant.chat.ChatAttachmentPositionMigrationTest

# mysqlMigrationTest는 @Tag("mysql")를 단 ChatAttachmentPositionMysqlMigrationTest와 AttachmentUploadLimitMysqlTest를 함께 실행한다.
scripts/check-mysql-migration.sh

cd web && pnpm test:browser chat-attachment
```

## 변경 파일

| 파일 | 변경 |
| --- | --- |
| `backend/src/main/resources/db/migration/V75__chat_attachment_position.sql` | 신규 파일로 `position` 칸을 더하는 DDL |
| `backend/src/main/resources/db/migration/V76__chat_attachment_position_backfill.sql` | 신규 파일로 기존 메시지 첨부의 position을 ID 순으로 채우는 DML |
| `backend/src/main/java/com/bifos/assistant/chat/domain/ChatAttachment.java` | 수정 후 메시지 안의 position을 모델링 |
| `backend/src/main/java/com/bifos/assistant/chat/infra/ChatAttachmentRepository.java` | 수정 후 조건부 position 저장과 순서 조회를 제공 |
| `backend/src/main/java/com/bifos/assistant/chat/infra/ConversationRepository.java` | 수정 후 upload 소유권 확인에 쓰는 대화 행 잠금 조회를 제공 |
| `backend/src/main/java/com/bifos/assistant/chat/application/ConversationAccess.java` | 수정 후 잠금을 사용하는 upload 전용 소유권 확인을 제공 |
| `backend/src/main/java/com/bifos/assistant/chat/application/AttachmentService.java` | 수정 후 잠금 아래의 장수 확인·저장과 요청 순서 저장, Hermes 순번을 적용 |
| `backend/src/main/java/com/bifos/assistant/chat/application/ChatService.java` | 수정 후 말풍선과 재생성의 첨부 조회를 position 순으로 적용 |
| `backend/src/test/java/com/bifos/assistant/chat/AttachmentServiceTest.java` | 수정 후 요청 순서 저장과 조건부 갱신 실패 rollback을 검증 |
| `backend/src/test/java/com/bifos/assistant/chat/AttachmentUploadLimitTest.java` | 수정 후 H2에서 실제 HTTP 32개 병렬 upload의 30개 성공·2개 거절과 남은 행 수를 검증 |
| `backend/src/test/java/com/bifos/assistant/chat/AttachmentUploadLimitMysqlTest.java` | 신규 파일로 실제 MySQL에서 같은 병렬 upload 상한과 잠금 조회를 검증 |
| `backend/src/test/java/com/bifos/assistant/chat/ChatAttachmentTurnTest.java` | 수정 후 30장의 Hermes·재생성·말풍선 순서 일치를 검증 |
| `backend/src/test/java/com/bifos/assistant/chat/RegenerateDeletedAttachmentTest.java` | 수정 후 역순 ID·중간 삭제에서도 재생성 입력의 position 순서와 ordinal gap을 검증 |
| `backend/src/test/java/com/bifos/assistant/chat/ChatAttachmentPositionMigrationTest.java` | 신규 파일로 H2에서 V75·V76의 backfill을 검증 |
| `backend/src/test/java/com/bifos/assistant/chat/ChatAttachmentPositionMysqlMigrationTest.java` | 신규 파일로 실제 MySQL에서 backfill을 검증 |
| `web/src/components/chat/composer.tsx` | 수정 후 새 대화 ID 대기 전 자리 예약, nullable ID 정리와 순서 보존 upload를 적용 |
| `test/browser/chat-attachment.spec.ts` | 수정 후 역순 완료와 16장+16장 동시 선택에서도 선택·전송 순서와 30장 상한을 검증 |
| `docs/backend/schema/chat.md` | 수정 후 position 저장 계약을 기록 |
| `docs/backend/attachment.md` | 수정 후 화면과 Hermes의 사진 순서 계약을 기록 |
| `docs/adr/ADR-020-사진은-공유-디렉터리에-두고-에이전트가-파일로-읽는다.md` | 수정 후 순서 보존 결정을 날짜와 이유로 보완 |
