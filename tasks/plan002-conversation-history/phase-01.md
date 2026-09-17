# Phase 01. 메시지에 보낸 사람을 남긴다

**Execution profile**: standard

## 목표

`chat_message` 에 누가 쓴 줄인지 남기고, 메시지 조회가 그 사람의 표시 이름을 함께 준다.
화면이 `나` 대신 실제 이름을 보이려면 이 정보가 필요하다.

**범위 외**

- 화면 작업은 phase-02 가 한다.
- 여러 사람이 같은 대화를 읽고 쓰는 것은 이 plan 이 하지 않는다.
  대화는 여전히 주인 한 사람의 것이고 다른 사용자는 읽지 못한다.

## 컨텍스트

`fos-assistant` 는 Spring Boot 4 와 Java 21 을 쓴다.
도메인마다 `presentation`, `application`, `domain`, `infra` 로 나누고 그 방향으로만 의존한다.

**Spring Boot 4 는 Jackson 3 을 쓴다.** `com.fasterxml.jackson` 이 아니라 `tools.jackson` 을 import 한다.

따를 패턴을 경로로 짚는다.

| 무엇 | 경로 |
| --- | --- |
| 메시지 엔티티 | `backend/src/main/java/com/bifos/assistant/chat/domain/ChatMessage.java` |
| 응답 DTO | `backend/src/main/java/com/bifos/assistant/chat/presentation/ChatDtos.java` |
| 조회 흐름 | `backend/src/main/java/com/bifos/assistant/chat/application/ChatService.java` |
| 사용자 조회 | `backend/src/main/java/com/bifos/assistant/user/infra/AppUserRepository.java` |
| 마이그레이션 | `backend/src/main/resources/db/migration/` |

**근거 문서**: `docs/data-schema.md` 의 「chat_message」 절, `docs/flow.md` 의 「대화 이력」 절

## 의도 메모

- `sender_user_id` 는 `NULL` 을 허용한다. 비서가 쓴 줄은 보낸 사람이 없다.
- 기존 메시지는 그 대화의 주인이 쓴 것이므로 마이그레이션이 채운다.
- 표시 이름을 `chat_message` 에 복사해 두지 않는다. 사용자가 이름을 바꾸면 지난 메시지가 옛 이름으로 남는다.
  조회할 때 `app_user` 에서 읽는다.
- 대화마다 참여자가 한 명이라 사용자 조회는 한 번으로 끝난다. 메시지마다 조회하지 않는다.

## 작업 항목

### 1. 마이그레이션 V3 을 더한다

`backend/src/main/resources/db/migration/V3__message_sender.sql` 을 만든다.

- `chat_message` 에 `sender_user_id BIGINT NULL` 을 더한다.
- 기존 행을 채운다. 그 메시지가 속한 대화의 `user_id` 를 쓰고, `role` 이 `ASSISTANT` 인 행은 비워 둔다.
- `conversation_id` 로 조회하는 색인이 이미 있다. 새 색인은 만들지 않는다.

지금 마이그레이션은 `V1__control_plane_core.sql` 과 `V2__workspace.sql` 이다. `V3` 을 쓴다.

### 2. `ChatMessage` 에 보낸 사람을 더한다

- `sender_user_id` 칸을 더한다.
- `fromUser(Long conversationId, String content)` 를 `fromUser(Long conversationId, Long senderUserId, String content)` 로 바꾼다.
- `fromAssistant` 는 보낸 사람을 받지 않는다.
- 읽기 메서드 `senderUserId()` 를 더한다.

### 3. `ChatService` 가 보낸 사람을 넘긴다

`send` 에서 사용자 메시지를 만들 때 현재 사용자의 `id` 를 함께 넘긴다.

### 4. 메시지 조회가 표시 이름을 준다

`ChatDtos.MessageView` 에 `senderName` 을 더한다.

- `role` 이 `USER` 면 `app_user` 의 `display_name` 을 넣는다.
- `role` 이 `ASSISTANT` 면 `null` 이다. 화면이 비서 이름을 정한다.
- 사용자를 찾지 못하면 `null` 이다. 조회가 실패하지 않는다.

`ChatController` 의 `messages` 가 그 값을 채운다.
대화 주인의 이름을 한 번 읽어 그 대화의 모든 `USER` 줄에 쓴다.

### 5. 이 phase 를 검증하는 테스트

`backend/src/test/java/com/bifos/assistant/chat/ChatServiceTest.java` 에 더한다.
기존 테스트가 `@SpringBootTest` 와 `@ActiveProfiles("test")` 를 쓰고 가짜 Hermes 를 `@Primary` 로 끼운다.
같은 방식을 따른다.

- 사용자 메시지에 보낸 사람이 남는다.
- 비서 메시지는 보낸 사람이 비어 있다.
- 메시지 조회가 사용자 줄에 표시 이름을, 비서 줄에 `null` 을 준다.

## 검증

```bash
# cwd: 저장소 root
cd backend && ./gradlew test
```

전부 통과해야 한다. 지금 40건이고 더한 만큼 늘어난다.

```bash
# cwd: 저장소 root
cd backend && ./gradlew compileJava
grep -rn "com.fasterxml.jackson" src/ || echo "Jackson 2 참조 없음"
```

기동해 스키마 검증이 통과하는지 본다.
테스트는 엔티티로 스키마를 만들고 운영은 Flyway 가 만든 것을 검증하므로, 둘이 어긋나도 테스트는 통과한다.

```bash
# cwd: 저장소 root
node test/e2e/run.ts
```

e2e 가 Flyway 를 실제로 돌린다. 여기서 통과해야 스키마가 맞는 것이다.

## Critical Files

| 파일 | 변경 |
| --- | --- |
| `backend/src/main/resources/db/migration/V3__message_sender.sql` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/domain/ChatMessage.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/application/ChatService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/presentation/ChatDtos.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/presentation/ChatController.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/ChatServiceTest.java` | 수정 |

## 끝낸 뒤

`tasks/plan002-conversation-history/index.json` 의 이 phase 를 `completed` 로 바꾸고
`current_phase` 를 다음 번호로 올린다.
