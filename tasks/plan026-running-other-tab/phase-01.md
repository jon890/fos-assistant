# Phase 01. 대화에 도는 turn 을 묻는 경로를 더한다

**Execution profile**: standard

## 목표

`GET /api/v1/chat/conversations/{conversationId}/running` 을 더한다.
그 대화에 지금 도는 turn 이 있는지, 있으면 뿌리 실행 번호와 시작 시각을 돌려준다.
web 은 다음 phase 가 고친다.

**범위 외**: 화면(phase-02), 답 조각을 다른 창으로 흘려 보내는 것(하지 않는다).

## 컨텍스트

**근거 문서**: `docs/flow.md` 「다른 창에서 답하는 중일 때」, `docs/code-architecture.md` 「대화」 의 「경로」 표와 「중지」 절.

한 사람이 같은 대화를 두 창에서 열면, 보내지 않은 창은 답이 오는 중인지 모른다.
그 창에서 다시 보내면 `CONVERSATION_BUSY` 로 거절된다. 이 경로가 그 창에 알릴 근거가 된다.

**도는지는 `TurnCancellation` 의 메모리 표시로 판정한다. 실행 줄의 `status` 로 보지 않는다.**
흐름으로 도는 turn 은 Chief 가 끝나면 뿌리 줄이 `SUCCEEDED` 가 되고 그 뒤에 자식이 돈다.
줄 상태로 보면 자식이 도는 동안 「돌지 않는다」 고 답한다. 중지 경로가 표시로 판정하는 것과 같은 까닭이다.

계획을 쓸 때의 코드다. 시작할 때 다시 연다.

| 위치 | 지금 모양 |
| --- | --- |
| `backend/src/main/java/com/bifos/assistant/chat/application/TurnCancellation.java` | `byConversation`(대화 번호 → `TurnHandle`)과 `byExecution` 두 맵. `open(userId, conversationId)` 가 등록하고 `rekey(handle, executionId)` 가 실행 번호를 붙이거나 바꾸고 `close(handle)` 가 지운다. `TurnHandle.executionId` 는 `volatile Long` 이고 밖으로 읽는 메서드가 없다 |
| `backend/src/main/java/com/bifos/assistant/chat/application/ChatService.java` | 필드 `turns`(`TurnCancellation`), `access`(`ConversationAccess`), `executionRepository`(`AgentExecutionRepository`) 를 이미 갖는다. `history(CurrentUser, Long)` 가 `access.requireOwn(user, conversationId)` 로 주인을 본다 |
| `backend/src/main/java/com/bifos/assistant/chat/application/ConversationAccess.java` | `requireOwn(CurrentUser, Long)`. 남의 대화와 지운 대화와 없는 대화에 `CONVERSATION_NOT_FOUND` |
| `backend/src/main/java/com/bifos/assistant/usage/domain/AgentExecution.java` | `startedAt()` 이 `Instant` 를 돌려준다 |
| `backend/src/main/java/com/bifos/assistant/chat/presentation/ChatController.java` | `@RequestMapping("/api/v1/chat")`. `@GetMapping("/conversations/{conversationId}/messages")` 가 `currentUser.require()` 로 요청자를 얻는다 |
| `backend/src/main/java/com/bifos/assistant/chat/presentation/ChatDtos.java` | 응답 record 를 모아 둔 곳. `StopResponse(String status)` 같은 모양 |
| `backend/src/test/java/com/bifos/assistant/chat/ChatStopTest.java` | 도는 turn 을 붙잡아 두고 중지를 부르는 검사. 새 검사의 본보기다 |

## 의도 메모

- **응답은 `RunningTurnView(boolean running, Long executionId, Instant startedAt)` 이다.**
  돌지 않으면 `running=false` 이고 둘은 null 이다.
  표시가 있는데 실행 번호가 아직 붙지 않았으면 `running=true` 이고 둘은 null 이다. 화면은 이 경우 중지 단추를 잠근다
- `TurnCancellation` 에 대화 번호로 표시를 읽는 메서드 하나만 더한다.
  「표시가 없다」 와 「표시는 있는데 번호가 없다」 를 구분해야 한다. 반환은 둘을 함께 담는 작은 record 로 둔다. 이름은 구현자가 정한다
- **주인 확인이 먼저다.** `access.requireOwn` 을 부른 뒤에 표시를 본다. 남의 대화에 도는 turn 이 있는지 새지 않게 한다
- `startedAt` 은 `executionRepository.findById(executionId)` 로 읽는다. 줄이 없으면 null 로 둔다. 예외를 던지지 않는다
- 이 경로는 데이터베이스를 바꾸지 않는다. 트랜잭션은 읽기 전용이다
- 다른 창이 3초마다 부른다. 질의는 주인 확인 하나와 실행 줄 하나로 끝낸다

## Blocked 조건

- `tasks/plan023-design-foundation/` 이나 `tasks/plan024-design-screens/` 가 main 에 남아 있다 → `PHASE_BLOCKED: 디자인 계획이 끝나지 않았다`.
  두 계획이 채팅 화면의 부품을 바꾼다. 다음 phase 가 그 위에 얹힌다

## 작업 항목

### 1. `TurnCancellation` 에 읽기 메서드

대화 번호로 `byConversation` 을 보고, 표시가 있으면 그 `executionId` 를 돌려준다.
표시를 바꾸지 않는다.

### 2. `ChatService.running(CurrentUser, Long)`

`access.requireOwn` 으로 대화를 확인하고 1 의 메서드로 표시를 본다.
실행 번호가 있으면 `startedAt` 을 읽어 `RunningTurnView` 를 만든다.

### 3. 경로와 DTO

`ChatDtos` 에 `RunningTurnView` 를 더하고, `ChatController` 에
`@GetMapping("/conversations/{conversationId}/running")` 을 더한다.

### 4. 이 phase 를 검증하는 테스트

`backend/src/test/java/com/bifos/assistant/chat/` 에 새 검사 파일을 둔다. `ChatStopTest` 처럼 가짜 Hermes 에서 turn 을 붙잡아 둔다.

| 경우 | 기대 |
| --- | --- |
| 도는 turn 이 없는 내 대화 | `running=false`, 둘 다 null |
| 붙잡아 둔 turn 이 있는 내 대화 | `running=true`, 그 turn 의 뿌리 실행 번호와 그 줄의 `startedAt` |
| turn 이 끝난 뒤 | `running=false` |
| 흐름으로 돌며 Chief 가 끝나 뿌리 줄이 `SUCCEEDED` 인데 자식이 도는 중 | `running=true`, 뿌리 번호 |
| provider 를 넘어가 새 실행 줄로 다시 시도하는 중 | 새 줄의 번호 |
| 남의 대화 | `CONVERSATION_NOT_FOUND` |
| 지운 대화와 없는 대화 | `CONVERSATION_NOT_FOUND` |

흐름 경우를 재현하기 어려우면 `TurnCancellation` 단위 검사로 뿌리 줄 상태와 무관하게 표시를 읽는 것을 보인다.
그 경우 화면 검사가 아니라 이 검사가 근거라는 것을 검사 이름에 적는다.

## 검증

```bash
# cwd: 저장소 root
cd backend && ./gradlew test
```

새 검사가 통과하고 기존 검사가 깨지지 않아야 한다. `gradlew` 의 위치는 `backend/AGENTS.md` 를 본다.

끝나면 `tasks/plan026-running-other-tab/index.json` 의 이 phase 를 `completed` 로, `current_phase` 를 2 로 바꾼다.

## Critical Files

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/chat/application/TurnCancellation.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/application/ChatService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/presentation/ChatController.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/presentation/ChatDtos.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/` 의 새 검사 | 추가 |
