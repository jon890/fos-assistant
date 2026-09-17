# Phase 01. Hermes 사건을 우리 이름으로 옮겨 적는다

**Execution profile**: deep

## 목표

스트림으로 오는 Hermes 사건을 우리가 정한 `execution_event` 로 옮겨 저장한다.
화면이 Hermes 의 원래 사건 이름을 읽지 않게 한다.

**범위 외**:
실행 상세 조회 API 는 phase-02 가 만든다.
화면은 phase-03 이 만든다.
실시간 스트리밍은 그대로 둔다. 이 phase 는 저장을 더하는 것이지 바꾸는 것이 아니다.

## 컨텍스트

지금 `ChatService.forward` 가 Hermes 사건 이름을 직접 검사한다.

```java
String type = event.type() == null ? "" : event.type().toLowerCase();
if (type.contains("delta") && event.text() != null) {
    onEvent.accept(ChatEvent.delta(event.text()));
} else if (type.startsWith("tool.") || type.startsWith("subagent.")) {
    onEvent.accept(ChatEvent.tool(...));
}
```

Hermes 가 이름을 바꾸면 화면이 아무것도 보여주지 않게 된다.
ADR-001 이 Hermes core 를 고치지 않기로 했으므로 그것을 막을 방법이 우리에게 없다.

그리고 사건이 화면을 지나가고 사라진다.
실측한 포지션 추천 하나가 15분 넘게 돌며 도구를 여러 번 불렀는데,
끝나고 나면 답 한 덩어리와 토큰 합계만 남는다.

**근거 문서**: `docs/adr/ADR-013-실행-사건은-우리-모델로-정규화해-저장한다.md`,
`docs/adr/ADR-008-스트리밍은-보여주기용이고-저장은-실행-결과로-한다.md`,
`docs/data-schema.md` 의 「execution_event」 절

## 의도 메모

- Hermes 의 payload 를 통째로 저장하는 안을 버렸다.
  도구가 읽어 온 문서 전체가 사건에 실려 오면 그것이 그대로 데이터베이스에 들어간다.
  무엇이 들어올지 모르는 것을 그대로 저장하지 않는다.
- 모르는 사건을 저장하지 않는다. 버리고 버렸다는 사실만 로그로 남긴다.
  나중에 그 로그를 보고 옮겨 적을 이름을 늘린다.
- 저장이 실패해도 대화는 성공으로 끝난다.
  사건은 관측용이고, 그것 때문에 답이 사라지면 안 된다.

## 작업 항목

### 1. 실제 사건 이름은 이미 확인돼 있다

**코드를 쓰기 전에 이 표를 읽는다. 짐작한 이름을 새로 만들지 않는다.**

`docs/hermes-integration.md` 의 「실행 이벤트가 실제로 오는 형태」 절이
v0.21.0 의 `gateway/platforms/api_server_runs.py` 를 실측해 아래를 적어 두었다.
`test/e2e/fake-hermes.ts` 도 이 이름으로 보낸다.

| Hermes `event` | 함께 오는 칸 | 우리 이름 |
| --- | --- | --- |
| `message.delta` | `delta` | 저장하지 않는다 |
| `tool.started` | `tool`, `preview` | `TOOL_STARTED` |
| `tool.completed` | `tool`, `duration` (초), `error` | `TOOL_COMPLETED` |
| `subagent.start` | `preview` | `SUBAGENT_STARTED` |
| `subagent.complete` | `preview` | `SUBAGENT_COMPLETED` |
| `reasoning.available` | `text` | 저장하지 않는다 |
| `run.completed` | `output`, `usage` | 저장하지 않는다 |
| `run.failed`, `run.cancelled` | 없음 | 저장하지 않는다 |

**`run.` 계열을 옮겨 적지 않는다. 우리가 직접 적는다.**
스트림을 열지 않는 `send` 경로에도 실행의 시작과 끝이 남아야 하므로
`ChatService` 가 항목 6 의 세 자리에서 직접 적는다.
여기서도 옮겨 적으면 스트리밍 경로에만 `RUN_COMPLETED` 가 두 줄 남는다.
우리가 적는 쪽이 `errorCode` 를 알고 있어 더 많은 것을 담는다.

**사건 이름은 `type` 이 아니라 `event` 로 온다.**
`HermesRunEventStream.emit` 이 그것을 `RunEvent.type` 으로 옮겨 담고 있으므로
`ExecutionEventRecorder` 는 `RunEvent.type()` 을 읽으면 된다.

`subagent.start` 는 `.started` 가 아니고 `subagent.complete` 는 `.completed` 가 아니다.
도구 사건과 어미가 다르다.

**이 phase 에서 `docs/hermes-integration.md` 를 고치지 않는다.**
그 문서가 이미 단일 소스다. 옮겨 적는 표는 `ExecutionEventRecorder` 안에 둔다.

**이 저장소는 공개 저장소다.** 홈서버의 주소와 포트와 컨테이너 구조와
key 가 있는 자리를 어느 파일에도 적지 않는다.

### 2. `backend/src/main/resources/db/migration/V10__execution_event.sql` 신규

```sql
CREATE TABLE execution_event (
    id BIGINT NOT NULL AUTO_INCREMENT,
    execution_id BIGINT NOT NULL,
    sequence INT NOT NULL,
    event_type VARCHAR(40) NOT NULL,
    tool_name VARCHAR(128) NULL,
    subagent_name VARCHAR(128) NULL,
    hermes_session_id VARCHAR(128) NULL,
    duration_ms BIGINT NULL,
    detail VARCHAR(500) NULL,
    occurred_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_execution_event_seq (execution_id, sequence)
);
```

**인덱스를 따로 만들지 않는다.** `uk_execution_event_seq` 가 이미
`(execution_id, sequence)` 를 그 순서로 포함한다.
`findByExecutionIdOrderBySequenceAsc` 가 그 유일 키를 쓴다.

`sequence` 는 MySQL 예약어가 아니지만 읽는 사람이 헷갈릴 수 있다.
JPA 엔티티에서 `@Column(name = "sequence")` 로 명시한다.

**`hermes_session_id` 는 이 plan 에서 언제나 비어 있다.**
Hermes v0.21.0 의 하위 에이전트 사건은 `preview` 만 싣고 session 번호를 보내지 않는다.
`docs/data-schema.md` 가 그 칸을 「하위 에이전트가 따로 session 을 가지면 적는다」 로
적어 두었으므로 칸은 만들어 두고 채우는 것은 그 경로가 생길 때 한다.
0 이나 빈 문자열로 채우지 않는다.

`subagent_name` 도 같은 사정이다. `subagent.start` 가 이름을 보내면 채우고,
보내지 않으면 비운다. 이름을 `preview` 에서 뽑아 만들지 않는다.

**`V10` 이 맞는 번호다.** `main` 에 `V9` 까지 있고, 다른 plan 이 `V11` 을 쓴다.

### 3. `RunEvent` 가 걸린 시간을 실어 나른다

지금 `RunEvent` 는 넷만 담는다.

```java
public record RunEvent(String type, String text, String toolName, String detail) {
}
```

`tool.completed` 가 보내는 `duration` 과 `error` 를 아무도 읽지 않아
`execution_event.duration_ms` 를 채울 근거가 없다. phase-03 이 그 값을 화면에 그린다.

`RunEvent` 에 칸 둘을 **더한다.** 기존 칸을 지우거나 순서를 바꾸지 않는다.

```java
public record RunEvent(
        String type, String text, String toolName, String detail,
        Long durationMs, Boolean failed) {
}
```

`HermesRunEventStream.emit` 이 그 둘을 채운다.

- `duration` 은 **초 단위 실수**다. 1000 을 곱해 밀리초 정수로 옮긴다. 없으면 `null` 이다
- `error` 가 참이면 `failed` 가 참이다. 없으면 `null` 이다

기존 호출부가 넷짜리 생성자를 쓰고 있으면 함께 고친다.
**칸을 더하는 방향으로만 간다.** 다른 plan 이 같은 저장소를 병렬로 고치고 있다.

### 4. `usage` 패키지에 사건 모델을 더한다

`execution_event` 는 실행에 딸린 것이므로 `usage` 안에 둔다. 새 패키지를 만들지 않는다.

| 파일 | 담는 것 |
| --- | --- |
| `domain/ExecutionEvent.java` | 엔티티. 모든 문자열 칸에 `length` 를 명시한다 |
| `domain/ExecutionEventType.java` | 아래 일곱 값 |
| `infra/ExecutionEventRepository.java` | |

**엔티티의 길이를 마이그레이션과 글자까지 맞춘다.**
`backend/AGENTS.md` 가 적은 대로 테스트는 엔티티로 스키마를 만들고 운영은 Flyway 가 만든 것을 검사한다.
둘이 어긋나도 테스트는 통과하고 배포에서 `Schema validation` 이 실패한다.
길이를 주지 않은 `@Lob` 문자열을 쓰지 않는다.

| 칸 | 길이 |
| --- | --- |
| `eventType` | 40. `@Enumerated(EnumType.STRING)` 이다 |
| `toolName`, `subagentName`, `hermesSessionId` | 128 |
| `detail` | 500 |

```java
public enum ExecutionEventType {
    RUN_STARTED,
    RUN_COMPLETED,
    RUN_FAILED,
    TOOL_STARTED,
    TOOL_COMPLETED,
    SUBAGENT_STARTED,
    SUBAGENT_COMPLETED
}
```

### 5. 옮겨 적는 자리를 한 곳에 둔다

`backend/src/main/java/com/bifos/assistant/usage/application/ExecutionEventRecorder.java` 다.

```java
/**
 * Hermes 사건을 우리 이름으로 옮겨 적는다.
 *
 * <p>Hermes 의 사건 이름을 아는 곳은 여기 하나다. 이름이 바뀌면 이 클래스만 고친다.
 */
@Service
public class ExecutionEventRecorder {

    /** 옮길 수 없는 사건은 저장하지 않고 null 을 낸다. */
    public ExecutionEvent record(AgentExecution execution, RunEvent event, int sequence);
}
```

옮기는 규칙을 이 클래스 안의 표 하나로 둔다.
`if` 를 늘어놓지 말고 Hermes 이름에서 우리 이름으로 가는 `Map` 이나 `switch` 로 쓴다.
1번에서 확인한 실제 이름을 쓴다. **짐작한 이름을 넣지 않는다.**

`detail` 은 500자로 자른다. 넘으면 자르고 자른 것을 표시하지 않는다.
화면에 한 줄로 보일 만큼만 담는 칸이다.

모르는 사건을 만나면 `log.debug` 로 그 이름을 남기고 `null` 을 낸다.
`warn` 이 아니라 `debug` 다. 모르는 사건이 자주 오면 로그가 그것으로 찬다.

칸을 어디서 채우는지다.

| 칸 | 어디서 |
| --- | --- |
| `toolName` | 도구 사건일 때 `RunEvent.toolName()`. 그 밖에는 비운다 |
| `subagentName` | 하위 에이전트 사건일 때 `RunEvent.toolName()`. 비어 있으면 비운 채로 둔다 |
| `durationMs` | `RunEvent.durationMs()`. 끝난 사건에만 온다 |
| `detail` | `RunEvent.detail()` 을 500자로 자른다 |
| `hermesSessionId` | 이 plan 에서는 언제나 비운다 |
| `occurredAt` | 받은 시각 |

**이 클래스가 옮겨 적는 것은 `tool.` 과 `subagent.` 계열 넷뿐이다.**
`run.` 계열은 항목 6 에서 `ChatService` 가 직접 적는다.

**이 클래스는 저장하지 않고 엔티티를 만들기만 한다.**
저장을 부르는 쪽이 하면 실패를 감싸는 자리가 한 곳으로 모인다.

### 6. `ChatService` 가 중계하면서 저장한다

**사건을 적는 자리는 `ChatService` 하나다. `ExecutionRecorder` 를 고치지 않는다.**
다른 plan 이 `ExecutionRecorder` 를 병렬로 고치고 있어 그쪽에 얹으면 충돌한다.
`OrphanedExecutionSweeper` 가 버려진 실행을 정리하는 경로도 이 plan 의 범위 밖이다.
그 경로로 끝난 실행에는 `RUN_FAILED` 가 남지 않는다. 그것이 맞다.

지금 `forward` 는 `private static` 이라 `sequence` 도 recorder 도 받지 못한다.
**인스턴스 메서드로 바꾼다.** 실행 하나마다 순서를 세는 자리가 필요하다.

- `sequence` 는 그 실행 안에서 1부터 센다. `ChatService` 가 세어 넘긴다.
  스트림을 읽는 스레드가 하나이므로 `int` 하나면 된다.
- **저장이 실패해도 중계는 계속한다.** `try` 로 감싸고 `log.warn` 만 남긴다.
  사건은 관측용이고 그것 때문에 답이 끊기면 안 된다.
  `record` 가 `null` 을 낼 때와 저장이 예외를 던질 때 **둘 다** 중계가 이어져야 한다.
- `delta` 는 저장하지 않는다. 글자 조각이라 수가 많고 답은 이미 메시지에 남는다.
  `record` 가 `null` 을 내므로 `ChatService` 가 따로 거르지 않는다.

우리가 직접 적는 사건이 셋이다. Hermes 사건을 기다리지 않는다.

| 우리 이름 | 어디서 | 비고 |
| --- | --- | --- |
| `RUN_STARTED` | `submit` 이 `runId` 를 받은 직후 | `sequence` 는 1 이다 |
| `RUN_COMPLETED` | `finish` 가 성공으로 갈 때 | |
| `RUN_FAILED` | `submit` 과 `awaitCompletion` 과 `finish` 의 실패 경로 | `errorCode` 를 `detail` 에 적는다 |

`stream` 과 `send` 가 그 셋을 함께 쓴다.
그래야 한 번에 받는 경로에서도 최소한의 사건이 남는다.

`RUN_FAILED` 를 적은 뒤에도 원래 던지던 예외를 그대로 던진다.
사건을 적으려고 실패를 삼키지 않는다.

### 7. 한 번에 받는 경로

`send` 는 스트림을 열지 않으므로 도구 사건이 오지 않는다.
`RUN_STARTED` 와 `RUN_COMPLETED` 나 `RUN_FAILED` 만 남는다.
그것이 맞다. 없는 것을 지어내지 않는다.

### 8. 이 phase 를 검증하는 테스트

`backend/src/test/java/com/bifos/assistant/usage/ExecutionEventRecorderTest.java` 를 새로 만든다.

- **정상 경로**: `tool.started` 를 주면 `TOOL_STARTED` 로 옮겨지고 `toolName` 이 채워진다
- `tool.completed` 를 주면 `TOOL_COMPLETED` 가 되고 `durationMs` 가 채워진다
- **이 phase 가 다루는 실패**: 모르는 이름을 주면 `null` 을 내고 예외를 던지지 않는다
- `message.delta` 와 `reasoning.available` 도 `null` 이 된다
- **`run.completed` 와 `run.failed` 와 `run.cancelled` 도 `null` 이 된다.**
  우리가 직접 적는 것이라 여기서 옮기면 스트리밍 경로에 두 줄이 남는다
- `detail` 이 500자를 넘으면 잘린다
- `subagent.start` 가 `SUBAGENT_STARTED` 로, `subagent.complete` 가 `SUBAGENT_COMPLETED` 로 옮겨진다

`backend/src/test/java/com/bifos/assistant/hermes/HermesRunEventStreamTest.java` 를 본다.
없으면 만들고, 있으면 더한다.

- `duration` 이 초 실수로 오면 `durationMs` 가 밀리초 정수가 된다

`backend/src/test/java/com/bifos/assistant/chat/ChatServiceTest.java` 에 더한다.

- 스트리밍 한 번이 `RUN_STARTED` 로 시작해 `RUN_COMPLETED` 로 끝나는 사건을 남긴다
- `sequence` 가 1부터 빈틈없이 이어진다
- **사건 저장이 예외를 던져도 대화는 성공한다.**
  저장소를 던지도록 만들어 확인한다
- `delta` 사건은 저장되지 않는다
- 한 번에 받는 경로가 `RUN_STARTED` 와 `RUN_COMPLETED` 둘만 남긴다
- **스트리밍 한 번이 `RUN_COMPLETED` 를 한 줄만 남긴다.**
  Hermes 도 `run.completed` 를 보내므로 두 줄이 되기 쉽다. 이 검사가 그것을 막는다
- Hermes 가 실패로 끝나면 `RUN_FAILED` 가 남고 예외는 그대로 올라간다

`test/e2e/fake-hermes.ts` 에 더한다.

- `tool.started` 와 `tool.completed` 쌍을 하나 더 보낸다. 도구 이름을 다르게 한다
- `subagent.start` 와 `subagent.complete` 를 보낸다

이 둘이 없으면 phase-03 의 화면 검사가 그릴 것을 갖지 못한다.
**`message.delta` 와 `run.completed` 의 형태는 그대로 둔다.** 이미 실측과 맞다.

**사건을 더하면 `test/e2e/scenarios/streaming.ts` 가 깨진다. 함께 고친다.**

그 파일이 도구 사건을 정확히 2개로 센다.

```typescript
expect(received.filter((item) => item.type === "tool").length === 2, "도구 사건을 받지 못했다");
```

`ChatService.forward` 가 `tool.` 과 `subagent.` 를 **둘 다** `ChatEvent.tool` 로 내보내므로,
도구 쌍 둘과 하위 에이전트 쌍 하나를 보내면 이 수가 6 이 된다.
가짜 Hermes 에 실제로 넣은 사건 수를 세어 그 값으로 고친다.
**수를 세지 않는 판정으로 바꾸지 않는다.** 그 판정이 중계 경로가 도는지를 지금 지키고 있다.

**사건이 데이터베이스에 남았는지는 이 phase 에서 e2e 로 확인하지 않는다.**
`test/e2e/harness.ts` 는 HTTP 만 부르고, 사건을 읽는 API 는 phase-02 가 연다.
그 확인은 phase-02 의 e2e 가 한다.

## 검증

```bash
# cwd: 저장소 root
cd backend && ./gradlew test
node test/e2e/run.ts
cd web && pnpm typecheck && pnpm build
```

```bash
# cwd: 저장소 root
cd backend && ./gradlew test --tests '*ExecutionEventRecorderTest*'
```

가짜 Hermes 가 실제와 다른 형태를 보내면 테스트가 통과해도 운영에서 동작하지 않는다.
실제로 그렇게 스트리밍이 통째로 동작하지 않은 채 배포된 적이 있다.
**그래서 옮겨 적는 표의 이름이 1번 표와 글자까지 같은지 확인해 보고에 적는다.**
가짜 Hermes 에 더한 이름도 같은 표에 있어야 한다.

```bash
# cwd: 저장소 root
grep -n 'tool\.\|subagent\.\|run\.\|message\.' \
  backend/src/main/java/com/bifos/assistant/usage/application/ExecutionEventRecorder.java
```

## Critical Files

| 파일 | 변경 |
|---|---|
| `backend/src/main/resources/db/migration/V10__execution_event.sql` | 신규 |
| `backend/src/main/java/com/bifos/assistant/hermes/dto/RunEvent.java` | 수정 (칸 추가) |
| `backend/src/main/java/com/bifos/assistant/hermes/HermesRunEventStream.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/usage/domain/ExecutionEvent.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/usage/domain/ExecutionEventType.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/usage/infra/ExecutionEventRepository.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/usage/application/ExecutionEventRecorder.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/application/ChatService.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/usage/ExecutionEventRecorderTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/chat/ChatServiceTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/hermes/HermesRunEventStreamTest.java` | 신규 또는 수정 |
| `test/e2e/fake-hermes.ts` | 수정 (도구 쌍 하나와 하위 에이전트 사건 추가) |
| `test/e2e/scenarios/streaming.ts` | 수정 (도구 사건 수 판정을 늘어난 값으로) |

**`docs/hermes-integration.md` 를 고치지 않는다.** 그 문서가 이미 사건 이름의 단일 소스다.
**`backend/.../usage/application/ExecutionRecorder.java` 를 고치지 않는다.** 다른 plan 이 쓴다.
