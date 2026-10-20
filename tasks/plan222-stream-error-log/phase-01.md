# Phase 01. 대화 스트림 안의 ApiException 을 서버 로그에 남긴다

**Execution profile**: fast

## 목표

`ChatEventStreams.open` 이 스트림 안에서 난 `ApiException` 을 SSE `error` 사건으로 보내기만 하고 로그를 남기지 않는다.
같은 코드와 문구를 warn 로그로 남겨, 화면에만 뜬 거절 사유를 서버에서도 찾을 수 있게 한다.

**범위 외**: 화면의 오류 문구. `GlobalExceptionHandler` 의 동작. 오류 코드 자체.

## 컨텍스트

- 규칙은 `docs/features/chat.md` 의 「스트림 안의 거절도 서버 로그에 남긴다」 단락이 갖는다. 로그 줄의 모양은 `chat stream api error code=<코드> message=<문구>` 다
- `backend/src/main/java/com/bifos/assistant/chat/turn/presentation/ChatEventStreams.java` 의 `open(Consumer<Consumer<ChatEvent>> work)` 가 가상 스레드 `chat-stream-` 에서 `work` 를 돌린다. `catch (ApiException ex)` 는 `channel.send(ChatEvent.error(ex.code().name(), ex.getMessage()))` 만 하고, `catch (Exception ex)` 는 이미 `log.error("chat stream failed", ex)` 를 남긴다. 클래스에는 이미 `@Slf4j` 가 있다
- 스트림 밖의 요청은 `backend/src/main/java/com/bifos/assistant/shared/error/GlobalExceptionHandler.java` 의 `handleApi` 가 `log.warn("api error code={} message={}", ex.code(), ex.getMessage())` 로 남긴다. 같은 말투를 쓴다
- `ApiException` 의 문구는 코드에 적은 고정 영문이다. 사용자 글이나 비밀값을 담지 않는다. 그래서 `GlobalExceptionHandler` 와 같이 문구를 그대로 남긴다
- 로그를 붙잡는 테스트의 예는 `backend/src/test/java/com/bifos/assistant/chat/MediaPipelineEventsTest.java` 의 `ListAppender<ILoggingEvent>` 다

**근거 문서**: `docs/features/chat.md` 의 「스트림 안의 거절도 서버 로그에 남긴다」

## 의도 메모

- 2026-10-15 에 사진을 붙인 메시지가 모델 단계 판정에서 `VALIDATION_FAILED` 로 네 번 거절됐는데 서버에는 실행 줄의 오류 코드만 남았다. 같은 코드를 내는 자리가 여럿이라 사유를 가릴 수 없었다
- 스택 트레이스는 남기지 않는다. `ApiException` 은 예상한 거절이고, 스트림 밖의 같은 거절도 트레이스 없이 남는다

## 작업 항목

### 1. `ChatEventStreams.open` 의 `ApiException` 처리

`backend/src/main/java/com/bifos/assistant/chat/turn/presentation/ChatEventStreams.java`

`catch (ApiException ex)` 안에서 `channel.send(...)` 앞에 `log.warn("chat stream api error code={} message={}", ex.code(), ex.getMessage());` 를 둔다.

### 2. 이 phase 를 검증하는 `backend/src/test/java/com/bifos/assistant/chat/turn/presentation/ChatEventStreamsTest.java`

- `new ChatEventStreams(Duration.ofSeconds(20))` 로 만들고, `ChatEventStreams` 로거에 `ListAppender` 를 붙인다
- `ApiException(ErrorCode.VALIDATION_FAILED, "the selected model is unavailable for this agent")` 를 던지는 일을 `open` 에 넘긴다. 일은 가상 스레드에서 돌므로 로그가 쌓일 때까지 2초 안에서 짧게 기다린다. warn 줄 하나가 `chat stream api error code=VALIDATION_FAILED message=the selected model is unavailable for this agent` 인지 본다
- `IllegalStateException` 을 던지는 일은 기존처럼 error 줄 `chat stream failed` 를 남기고 warn 줄 `chat stream api error` 는 남기지 않는지 본다
- 테스트 이름은 rule-16 을 따른다. `@DisplayName` 에 한국어로 동작을 적는다

## 검증

```bash
cd backend && ./gradlew test --tests '*ChatEventStreamsTest'
cd backend && ./gradlew checkstyleMain checkstyleTest spotlessCheck
cd backend && ./gradlew archTest
```

모두 종료 코드 0 이다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/chat/turn/presentation/ChatEventStreams.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/turn/presentation/ChatEventStreamsTest.java` | 신규 |
