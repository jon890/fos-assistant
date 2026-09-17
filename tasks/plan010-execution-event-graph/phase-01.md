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

### 1. 실제 사건 이름을 먼저 확인한다

**코드를 쓰기 전에 한다.** 지금 `forward` 가 `contains` 와 `startsWith` 로
느슨하게 맞추고 있어, 실제로 어떤 이름이 오는지 이 저장소에 적힌 곳이 없다.

실제 실행 하나를 걸어 `GET /v1/runs/{id}/events` 가 보내는 사건 이름을 모은다.
도구를 실제로 부르는 문장을 보내야 도구 계열 사건이 나온다.

**이 저장소는 공개 저장소다. 실행 방법을 여기 적지 않는다.**
홈서버의 주소와 포트와 컨테이너 구조와 key 가 있는 자리를 적지 않는다.
운영 절차는 비공개 저장소 `fos-home-infra` 가 소유하고,
이 phase 를 실행하는 사람은 그 저장소의 Hermes 운영 문서를 본다.

확인한 것 중 아래 둘은 이미 알려져 있다.

| Hermes 사건 | 뜻 |
| --- | --- |
| `message.delta` | 답의 글자 조각 |
| `run.completed` | 실행 종료 |

**모은 이름 목록을 `docs/hermes-integration.md` 에 적는다.**
이것이 이 phase 의 첫 산출물이다. 다음에 읽는 사람이 근거 없이 짐작하지 않게 한다.
**이름만 적고 그 이름을 얻은 명령은 적지 않는다.**

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

CREATE INDEX idx_execution_event_execution ON execution_event (execution_id, sequence);
```

`sequence` 는 MySQL 예약어가 아니지만 읽는 사람이 헷갈릴 수 있다.
JPA 엔티티에서 `@Column(name = "sequence")` 로 명시한다.

### 3. `usage` 패키지에 사건 모델을 더한다

`execution_event` 는 실행에 딸린 것이므로 `usage` 안에 둔다. 새 패키지를 만들지 않는다.

| 파일 | 담는 것 |
| --- | --- |
| `domain/ExecutionEvent.java` | 엔티티 |
| `domain/ExecutionEventType.java` | 아래 일곱 값 |
| `infra/ExecutionEventRepository.java` | |

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

### 4. 옮겨 적는 자리를 한 곳에 둔다

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

### 5. `ChatService` 가 중계하면서 저장한다

`stream` 의 `forward` 자리에서 부른다.

- `sequence` 는 그 실행 안에서 1부터 센다. `ChatService` 가 세어 넘긴다.
- **저장이 실패해도 중계는 계속한다.** `try` 로 감싸고 `log.warn` 만 남긴다.
  사건은 관측용이고 그것 때문에 답이 끊기면 안 된다.
- `delta` 는 저장하지 않는다. 글자 조각이라 수가 많고 답은 이미 메시지에 남는다.

`RUN_STARTED` 는 Hermes 사건을 기다리지 않고 실행을 제출한 직후에 우리가 적는다.
`RUN_COMPLETED` 와 `RUN_FAILED` 도 실행을 갱신하는 자리에서 우리가 적는다.
그래야 한 번에 받는 경로에서도 최소한의 사건이 남는다.

### 6. 한 번에 받는 경로

`send` 는 스트림을 열지 않으므로 도구 사건이 오지 않는다.
`RUN_STARTED` 와 `RUN_COMPLETED` 나 `RUN_FAILED` 만 남는다.
그것이 맞다. 없는 것을 지어내지 않는다.

### 7. 이 phase 를 검증하는 테스트

`backend/src/test/java/com/bifos/assistant/usage/ExecutionEventRecorderTest.java` 를 새로 만든다.

- **정상 경로**: 1번에서 확인한 실제 도구 사건 이름을 주면
  `TOOL_STARTED` 로 옮겨지고 `toolName` 이 채워진다
- **이 phase 가 다루는 실패**: 모르는 이름을 주면 `null` 을 내고 예외를 던지지 않는다
- `detail` 이 500자를 넘으면 잘린다
- 하위 에이전트 사건이 `SUBAGENT_STARTED` 로 옮겨진다

`backend/src/test/java/com/bifos/assistant/chat/ChatServiceTest.java` 에 더한다.

- 스트리밍 한 번이 `RUN_STARTED` 로 시작해 `RUN_COMPLETED` 로 끝나는 사건을 남긴다
- `sequence` 가 1부터 빈틈없이 이어진다
- **사건 저장이 예외를 던져도 대화는 성공한다.**
  저장소를 던지도록 만들어 확인한다
- `delta` 사건은 저장되지 않는다

`test/e2e/scenarios/streaming.ts` 에 더한다.

- 스트리밍 실행 하나가 끝난 뒤 그 실행의 사건이 데이터베이스에 남아 있다

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
**그래서 1번에서 실제 사건 이름을 먼저 확인하고, 가짜 Hermes 가 그 이름을 보내도록 맞춘다.**
가짜가 보내는 이름이 1번에서 모은 목록에 있는지 확인해 보고에 적는다.

## Critical Files

| 파일 | 변경 |
|---|---|
| `docs/hermes-integration.md` | 수정 (실제 사건 이름 목록) |
| `backend/src/main/resources/db/migration/V10__execution_event.sql` | 신규 |
| `backend/src/main/java/com/bifos/assistant/usage/domain/ExecutionEvent.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/usage/domain/ExecutionEventType.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/usage/infra/ExecutionEventRepository.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/usage/application/ExecutionEventRecorder.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/application/ChatService.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/usage/ExecutionEventRecorderTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/chat/ChatServiceTest.java` | 수정 |
| `test/e2e/scenarios/streaming.ts` | 수정 |
