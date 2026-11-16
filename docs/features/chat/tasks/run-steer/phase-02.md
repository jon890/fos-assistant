# Phase 02. 말풍선 칸과 대기 줄의 새 칸

**Execution profile**: standard

## 목표

`chat_message` 에 steer 칸 셋을 더하고, 대기 글이 「끼우는 중」 과 말풍선 번호를 가질 수 있게 대기 줄 JSON 을 버전 2 로 넓힌다.

**범위 외**: 이 칸을 쓰는 흐름(phase 3, 4).

## 컨텍스트

**요구사항**: REQ3, REQ4, REQ8

**근거 문서**: `docs/features/chat/design.md` 의 「데이터」, `backend/docs/data-schema.md` 의 「마이그레이션 작성 규칙」, `chat_message`, `conversation.pending_messages`, ADR-048 「저장 자리 개정」

- 엔티티 `chat/conversation/domain/ChatMessage.java`, 대기 줄 `chat/conversation/domain/PendingMessages.java`·`PendingMessage.java`, 저장 `chat/conversation/application/PendingMessageStore.java`(encode/decode, `Stored`·`StoredItem`).
- 마이그레이션 번호는 예약했다: `backend/src/main/resources/db/migration/V20261105074411__chat_message_steer.sql`. 다른 이름으로 만들지 않는다. 기준 브랜치를 합친 뒤에는 공통 지시서의 `orch queue reserve` 를 같은 이름으로 다시 부른다.

## 의도 메모

- 새 칸이 찬 글이 하나도 없으면 버전 1 로 쓴다. 설정을 끈 동안 앞 이미지와 호환된다(설계 DECISION8).
- 상한은 말풍선 번호가 없는 글만 센다(설계 DECISION9).

## 작업 항목

### 1. 마이그레이션 `V20261105074411__chat_message_steer.sql`

`chat_message` 에 `steer_state VARCHAR(20) NULL`, `steer_execution_id BIGINT NULL`, `steer_child VARCHAR(20) NULL` 을 더한다. 문자열 칸은 `utf8mb4_0900_ai_ci` 를 적는다. 끝난 turn 의 남은 말풍선을 찾는 질의(phase 4)를 위해 `(steer_state, steer_execution_id)` 색인. 주석은 한국어. DML 은 넣지 않는다.

### 2. `ChatMessage` 와 enum

`chat/conversation/domain/type/SteerState.java`(`DELIVERED`, `CARRIED_OVER`, `UNCONFIRMED`), `SteerChild.java`(`SUBAGENT`, `DELEGATION`). 엔티티에 칸과 읽는 메서드, `static ChatMessage fromSteer(conversationId, senderUserId, content, executionId, SteerState, SteerChild, now)`, `void changeSteerState(SteerState)` 를 더한다. 암호화는 `fromUser` 와 같은 경로다.

### 3. `PendingMessage`·`PendingMessages`

- `PendingMessage` 에 `Long messageId`(null 가능), `boolean steering` 을 더하고 기존 3인자 생성자를 남긴다.
- `PendingMessages`: `steering()`(끼우는 중인 글이 있다), `sendable()` 은 지금 뜻 그대로 두고 `blockedBySteering()` 을 더한다. `countable()`(말풍선 번호 없는 글 수), `mergedLength` 는 말풍선 번호 없는 글만 센다. `markSteering(id, boolean)`, `prependCarried(List<CarriedItem>, Instant now)`(말풍선 번호와 글로 줄 앞에 넣는다. 번호는 줄의 최대 번호와 시각 마이크로초보다 크게, 받은 순서대로), `questionText()`(말풍선 번호 없는 글만 이은 것), `carriedMessageIds()`.

### 4. `PendingMessageStore` 버전 2

`StoredItem` 에 `messageId`, `steering` 을 더한다. encode 는 새 칸이 찬 글이 있으면 `version: 2` 와 그 칸을, 없으면 지금과 같은 버전 1 을 쓴다(새 칸 키도 쓰지 않는다). decode 는 1과 2 를 받고 그 밖은 지금처럼 오류다. 클래스 Javadoc 의 JSON 모양을 고친다.

### 5. 문서

`backend/docs/data-schema.md` 의 `chat_message`(steer 칸 셋과 `execution_id` 규칙 유지), `pending_messages`(버전 2 와 새 칸, 버전 1 로 쓰는 조건, 상한)를 고친다. ADR-048 「저장 자리 개정」 표에 버전 2 한 줄을 더한다.

### 6. 시험

`PendingMessageStoreTest` 에 버전 1 그대로 쓰기, 버전 2 왕복, 버전 3 거절. `PendingMessages` 단위 시험(앞에 넣기 순서·번호, 상한 계산, `questionText`). 마이그레이션은 기존 H2 기동 시험이 덮는다.

## 검증

```bash
cd backend && HEAVY_SLOTS=2 orch slot heavy -- ./gradlew test --tests '*PendingMessageStoreTest' --tests '*PendingMessagesTest' --tests '*ChatPendingMessageMigrationTest'
node scripts/checks/check-migration-versions.mjs origin/main
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/resources/db/migration/V20261105074411__chat_message_steer.sql` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/conversation/domain/type/SteerState.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/conversation/domain/type/SteerChild.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/conversation/domain/ChatMessage.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/conversation/domain/PendingMessage.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/conversation/domain/PendingMessages.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/conversation/application/PendingMessageStore.java` | 수정 |
| `backend/docs/data-schema.md` | 수정 |
| `docs/adr/ADR-048-응답-중에-보낸-메시지는-control-plane-이-쌓아-두고-다음-turn-으로-합쳐-보낸다.md` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/PendingMessageStoreTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/PendingMessagesTest.java` | 신규 |
