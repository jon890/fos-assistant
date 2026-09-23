# Phase 01. 도구와 하위 에이전트 사건을 나눠 중계하고 저장하고 센다

**Execution profile**: deep

## 목표

Control Plane 이 Hermes 의 도구 사건과 하위 에이전트 사건을 서로 다른 `ChatEvent` 로 화면에 보내고,
하위 에이전트의 목표와 모델과 토큰을 `execution_event` 에 남기며,
메시지 조회가 답마다 작업 과정의 요약 `activity` 를 함께 주게 한다.
화면의 작업 과정 블록이 이 셋에 기댄다.

**범위 외**:
화면은 phase-02 와 phase-03 이 한다. 이 phase 는 `web/` 을 고치지 않는다.
하위 에이전트 토큰을 사용량 합계나 비용 환산에 더하지 않는다.
`subagent_name` 칸을 채우는 규칙은 바꾸지 않는다.

## 컨텍스트

**근거 문서**: `docs/code-architecture.md` 의 「대화」 절 아래 「메시지 한 줄」 과 「화면으로 보내는 사건」, `docs/data-schema.md` 의 「execution_event」 절, `docs/hermes-integration.md` 의 「자식 토큰을 SSE 로 받을 수 있다」 와 「다만 동기 위임일 때만 받는다」, `docs/flow.md` 의 「대화 한 번」 과 「작업 과정」, `docs/adr/ADR-013-실행-사건은-우리-모델로-정규화해-저장한다.md`

### 시작하기 전에 확인한다

이 phase 는 화면 틀 개선이 머지된 뒤에 돈다. 아래가 있어야 한다.

- `ChatEvent` 에 `started` 팩토리가 있고 `ChatService.stream` 이 실행 줄을 만든 직후 그것을 보낸다
- `web/src/components/shell/` 디렉터리와 `web/src/app/c/[conversationId]/page.tsx`

없으면 `PHASE_BLOCKED: 화면 틀 개선이 아직 머지되지 않았다` 를 출력하고 끝낸다.

사진 첨부도 먼저 머지된다. `ChatDtos.MessageView` 와 `SendMessageRequest` 에 첨부 칸이 더해져 있을 수 있다.
**고치기 전에 두 record 의 지금 모양을 읽는다.** 아래에 적은 칸 순서는 그 뒤에 붙인다는 뜻이다.

### 지금 코드가 하는 것

- `hermes/dto/RunEvent.java` 는 칸이 여섯이다. `type`, `text`, `toolName`, `detail`, `durationMs`, `failed`.
  이 생성자를 부르는 곳이 `HermesRunEventStream` 과 테스트 셋(`ChatServiceTest`, `ModelSelectionTest`, `ExecutionEventRecorderTest`)에 걸쳐 18곳이다.
- `hermes/HermesRunEventStream.toRunEvent(JsonNode)` 가 사건 JSON 을 읽는다.
  `firstText(root, payload, ...)` 로 최상위와 `data` 안을 차례로 본다. 걸린 시간은 `duration` 만 읽는다.
- `chat/application/ChatService.forward(PendingTurn, RunEvent, Consumer<ChatEvent>)` 가
  `tool.` 과 `subagent.` 로 시작하는 사건을 **모두** `ChatEvent.tool(toolName, status)` 하나로 보낸다.
- `usage/application/ExecutionEventRecorder` 의 `OUR_NAMES` 표가
  `tool.started`, `tool.completed`, `subagent.start`, `subagent.complete` 넷을 우리 이름으로 옮긴다.
  하위 에이전트 사건의 어미가 도구와 다르다. `.start` 와 `.complete` 다.
- `usage/domain/ExecutionEvent` 는 `Builder` 로 만든다. `DETAIL_LIMIT` 이 500 이고 `detail(...)` 이 넘는 것을 자른다.
- `ChatService.switchedLabels(List<ChatMessage>)` 가 답들의 실행 번호로
  `executionEvents.findByExecutionIdInOrderByExecutionIdAscSequenceAsc` 를 한 번 불러 사건을 읽는다.
  `executionIdsHavingChildren` 도 같은 방식이다. `activity` 요약을 이 둘과 같은 자리에 둔다.
- `AgentExecutionRepository.findByRootExecutionId(Long)` 가 뿌리 아래 자손을 낸다.
  뿌리 자신은 `rootExecutionId` 가 null 이다.
- `test/e2e/fake-hermes.ts` 의 사건 경로가 `subagent.start` 와 `subagent.complete` 를 `preview` 만 실어 보낸다.
  주석에 적힌 대로 Hermes v0.21.0 의 모양이다.
- `test/e2e/scenarios/streaming.ts` 가 `tool` 사건이 여섯 개 오는 것을 검사한다.
  도구 넷과 하위 에이전트 둘이 모두 `tool` 로 오기 때문이다.

### Hermes 가 하위 에이전트 칸을 싣는지는 버전마다 다르다

`docs/hermes-integration.md` 는 `subagent.start` 에 `subagent_id`, `child_session_id`, `model`, `goal` 이,
`subagent.complete` 에 그것과 `status`, `duration_seconds`, `input_tokens`, `output_tokens` 가 온다고 적는다.
반면 `docs/data-schema.md` 와 `fake-hermes.ts` 는 v0.21.0 이 `preview` 만 싣는다고 적는다.

**칸이 오지 않아도 지금처럼 동작하게 짠다.** 새 칸은 모두 비어도 되는 값이다.
어느 쪽이 운영의 모양인지는 배포 뒤 실제 실행 한 번을 왕복시켜 확인한다.
`AGENTS.md` 의 「배포했다고 말하기 전에 보는 것」 을 따른다.

## 의도 메모

- `RunEvent` 의 칸을 늘리되 여섯 칸 생성자를 보조 생성자로 남긴다.
  18곳을 모두 고치면 이 phase 의 diff 가 테스트 수정으로 찬다.
- 하위 에이전트의 짝을 맞추는 열쇠는 `subagentId` 다. 비어 오면 화면이 도착 순서로 짝짓는다.
  서버는 짝을 맞추지 않는다. 받은 대로 옮긴다.
- `goal` 을 `detail` 에 넣는 것은 하위 에이전트 사건에만 한다. `goal` 이 비면 지금처럼 `preview` 를 넣는다.
- `activity.subagentCount` 는 `SUBAGENT_*` 사건으로 센 하위 에이전트 수에 **그 답의 자식 실행 수를 더한다.**
  흐름으로 돈 답은 하위 에이전트 사건 없이 자식 실행만 남는다. 더하지 않으면 그 답의 요약이 0 이 되고 블록이 사라진다.
- **Memory 제안 실행은 자식 실행으로 세지 않는다** (`docs/code-architecture.md` 「메시지 한 줄」 표).
  `agent_execution` 에 실행의 목적을 적는 칸이 없어서 사건으로 구분한다.
  흐름의 자식은 `AgentRunner.append` 가 언제나 `RUN_STARTED` 를 남기고,
  `MemoryProposer.proposeFrom` 은 `hermes.submit` 과 `awaitCompletion` 을 직접 불러 사건을 하나도 남기지 않는다.
  그래서 **사건이 하나 이상 있는 자손만 센다.** 사건은 어차피 한 번에 읽으므로 질의가 늘지 않는다.
  `MemoryProposer` 가 사건을 남기기 시작하면 이 규칙이 깨진다. 아래 테스트가 그것을 잡는다.
- `activity.durationMs` 는 **뿌리가 시작한 때부터 나무에서 가장 늦게 끝난 실행이 끝난 때까지**다.
  `AgentExecution.startedAt()` 과 `finishedAt()` 을 쓴다. 뿌리의 `latencyMs()` 만 쓰면 흐름 답이 Chief 한 단계의 시간으로 보인다.
  Chief 는 자식보다 먼저 끝나기 때문이다. 셀 자손은 이미 읽고 있어 질의가 늘지 않는다.
  끝나지 않은 실행이 섞여 있으면(`finishedAt()` 이 null) 끝난 것만으로 계산하고, 모두 끝나지 않았으면 null 이다.
- 사건 저장이 실패해도 대화는 이어진다는 지금 규칙(`ChatService.store`)을 그대로 둔다.

## 작업 항목

### 1. `RunEvent` 에 하위 에이전트 칸을 더한다

`backend/src/main/java/com/bifos/assistant/hermes/dto/RunEvent.java`

뒤에 칸 일곱을 더한다. 모두 null 이 될 수 있다.

| 칸 | 타입 | Hermes 이름 |
| --- | --- | --- |
| `subagentId` | `String` | `subagent_id` |
| `goal` | `String` | `goal` |
| `model` | `String` | `model` |
| `childSessionId` | `String` | `child_session_id` |
| `inputTokens` | `Long` | `input_tokens` |
| `outputTokens` | `Long` | `output_tokens` |
| `status` | `String` | `status` |

지금의 여섯 칸 생성자를 보조 생성자로 남기고 새 칸을 null 로 채운다. Javadoc 의 `@param` 을 함께 더한다.

### 2. `HermesRunEventStream.toRunEvent` 가 새 칸을 읽는다

`backend/src/main/java/com/bifos/assistant/hermes/HermesRunEventStream.java`

- 문자열 칸은 `firstText(root, payload, "subagent_id")` 처럼 지금의 `firstText` 로 읽는다.
- 토큰은 `number(...)` 로 최상위와 `data` 를 차례로 보고 `asLong()` 한다. 숫자가 아니면 null.
- `durationMs(...)` 가 `duration` 이 없을 때 `duration_seconds` 도 읽게 한다. 초 단위 실수라 1000 을 곱한다.
- `failed(...)` 가 `error` 를 받지 못했고 `status` 가 있으면, `status` 가 `completed` 가 아닐 때 참으로 읽는다.
  둘 다 없으면 지금처럼 null.

### 3. `ChatEvent` 에 `phase` 와 하위 에이전트 칸을 더한다

`backend/src/main/java/com/bifos/assistant/chat/application/ChatEvent.java`

record 에 칸을 더한다. `docs/code-architecture.md` 「화면으로 보내는 사건」 표의 이름을 그대로 쓴다.
`phase`, `durationMs`, `failed`, `subagentId`, `goal`, `model`, `inputTokens`, `outputTokens`.
JSON 이름이 곧 화면이 읽는 이름이다.

- `tool(String toolName, String status)` 를 `tool(String toolName, String detail, String phase, Long durationMs, Boolean failed)` 로 바꾼다.
- `subagent(String subagentId, String goal, String model, String phase, Long inputTokens, Long outputTokens, Long durationMs, Boolean failed)` 를 새로 둔다. `type` 은 `"subagent"` 다.
- `phase` 값은 `"started"` 와 `"completed"` 둘이다. 상수로 둔다.
- 다른 팩토리는 새 칸을 null 로 채운다. 클래스 Javadoc 에 새 칸을 적는다.

### 4. `ChatService.forward` 가 둘을 나눠 보낸다

`backend/src/main/java/com/bifos/assistant/chat/application/ChatService.java`

사건 이름으로 나눈다. 이름 비교는 지금처럼 소문자로 한다.

| Hermes 이름 | 보내는 것 |
| --- | --- |
| `tool.started` | `ChatEvent.tool(toolName, detail, "started", null, null)` |
| `tool.completed` | `ChatEvent.tool(toolName, detail, "completed", durationMs, failed)` |
| `subagent.start` | `ChatEvent.subagent(subagentId, goal, model, "started", null, null, null, null)` |
| `subagent.complete` | `ChatEvent.subagent(subagentId, goal, model, "completed", inputTokens, outputTokens, durationMs, failed)` |
| 그 밖의 `tool.` 과 `subagent.` | 보내지 않는다 |

`detail` 이 null 이면 지금은 사건 이름을 대신 넣는다. 그렇게 하지 않고 null 로 보낸다. 화면이 사건 이름을 읽지 않게 하기 위해서다.
`goal` 이 null 이면 `subagent` 사건의 `goal` 자리에 `detail`(Hermes 의 `preview`)을 넣는다.

### 5. `execution_event` 에 칸 셋을 더한다

- `backend/src/main/resources/db/migration/` 에 **마지막 번호 다음** 파일을 만든다. 이름 끝은 `__execution_event_subagent.sql`.
  `ALTER TABLE execution_event` 로 `model VARCHAR(128) NULL`, `input_tokens BIGINT NULL`, `output_tokens BIGINT NULL` 을 더한다.
  기존 파일(`V12__context_omitted.sql`)처럼 한 줄씩 쓴다.
- `backend/src/main/java/com/bifos/assistant/usage/domain/ExecutionEvent.java` 에 `model`, `inputTokens`, `outputTokens` 칸과 getter 와 `Builder` 메서드를 더한다.
  `@Column(name = "model", length = 128)` 처럼 **마이그레이션과 길이를 글자까지 맞춘다.** 이 클래스의 Javadoc 이 그 이유를 적는다.

### 6. `ExecutionEventRecorder` 가 새 칸을 채운다

`backend/src/main/java/com/bifos/assistant/usage/application/ExecutionEventRecorder.java`

`record(AgentExecution, RunEvent, int)` 에서 하위 에이전트 사건(`type.isSubagent()`)일 때만 아래를 채운다.

| 칸 | 값 |
| --- | --- |
| `detail` | `goal` 이 있으면 `goal`, 없으면 지금처럼 `event.detail()` |
| `hermesSessionId` | `childSessionId` |
| `model` | `model` |
| `inputTokens`, `outputTokens` | 그 값. `SUBAGENT_COMPLETED` 에만 |

도구 사건은 지금과 같다. `subagentName` 을 채우는 줄은 건드리지 않는다.

### 7. `ExecutionEventView` 가 새 칸을 싣는다

`backend/src/main/java/com/bifos/assistant/usage/application/ExecutionEventView.java`

`model`, `inputTokens`, `outputTokens` 를 `detail` 뒤에 더하고 `from(ExecutionEvent)` 에서 채운다.
실행 나무 응답(`GET /api/v1/usage/executions/{id}/tree`)에 그대로 실린다.

### 8. 메시지 조회에 `activity` 를 더한다

- `backend/src/main/java/com/bifos/assistant/chat/presentation/ChatDtos.java` 에 `public record ActivitySummary(int toolCount, int subagentCount, Long durationMs)` 를 두고
  `MessageView` 의 마지막에 `ActivitySummary activity` 를 더한다.
- `ChatService` 에 `public Map<Long, ActivitySummary> activitySummaries(List<ChatMessage> history)` 를 둔다.
  `ActivitySummary` 를 `chat/application` 에 두고 `ChatDtos` 가 그것을 쓰는 쪽이 층 방향에 맞으면 그렇게 한다. 지금 `ChatTurn` 이 `application` 에 있는 것과 같은 자리다.
  - 답의 실행 번호를 모은다. 비면 부르지 않고 빈 표를 낸다. 빈 `in` 절을 피하는 지금 규칙과 같다.
  - 각 뿌리의 자손을 읽는다. 뿌리 번호 목록으로 한 번에 읽는 메서드를 `AgentExecutionRepository` 에 더한다. 이름은 `findByRootExecutionIdIn(Collection<Long>)`.
  - 뿌리와 자손의 사건을 `findByExecutionIdInOrderByExecutionIdAscSequenceAsc` 한 번으로 읽는다.
  - 도구 수는 실행마다 `TOOL_STARTED` 와 `TOOL_COMPLETED` 수 가운데 큰 값을 더한 것이다. 하위 에이전트도 같게 센다. 여기에 **사건이 하나 이상 있는** 자손 실행 수를 더한다. Memory 제안 실행을 빼기 위해서다.
  - 둘을 더해 0 이면 그 답은 표에 넣지 않는다. 화면이 null 로 받는다.
  - `durationMs` 는 뿌리의 `startedAt()` 부터 뿌리와 자손 가운데 가장 늦은 `finishedAt()` 까지의 밀리초다. 위 「의도 메모」 대로 null 을 다룬다.
- `ChatController.messages` 가 `executionIdsHavingChildren` 와 `switchedLabels` 옆에서 그것을 부르고, 사용자 메시지에는 null 을 넣는다.

### 9. 가짜 Hermes 가 하위 에이전트 칸을 실어 보내는 입력을 둔다

`test/e2e/fake-hermes.ts`

사건 경로(`RUN_EVENTS_PATH`)에서 `run.input` 이 `"하위 에이전트 칸 검사"` 일 때만
`subagent.start` 에 `subagent_id: "sa-1"`, `goal: "숙소 후보를 조사한다"`, `model: "z-ai/glm-5.2"`, `child_session_id: "child-1"` 을,
`subagent.complete` 에 그것과 `status: "completed"`, `duration_seconds: 1.5`, `input_tokens: 12300`, `output_tokens: 410` 을 싣는다.
**다른 입력은 지금 모양 그대로 둔다.** 두 모양이 모두 동작하는지가 이 계획의 조건이다.

### 10. 이 phase 를 검증하는 테스트

- `backend/src/test/java/com/bifos/assistant/hermes/HermesRunEventStreamTest.java`
  - `subagent.complete` JSON 하나에서 일곱 칸과 `duration_seconds` 1.5 가 1500 밀리초로 읽힌다
  - `status: "failed"` 이고 `error` 가 없으면 `failed` 가 참이다
  - 칸이 `data` 안에 실려 와도 같게 읽는다
- `backend/src/test/java/com/bifos/assistant/memory/MemoryProposerTest.java`
  - 제안 실행을 한 번 돌린 뒤 그 실행 번호로 `execution_event` 가 하나도 없다. 위 세는 규칙이 이 사실에 기댄다
- `backend/src/test/java/com/bifos/assistant/orchestration/ResearchAndBuildFlowTest.java`
  - 흐름으로 돈 답의 `activity.durationMs` 가 Chief 실행의 `latencyMs()` 보다 크다
- `backend/src/test/java/com/bifos/assistant/usage/ExecutionEventRecorderTest.java`
  - `goal` 이 있으면 `detail` 이 `goal`, 없으면 `preview` 다
  - `SUBAGENT_COMPLETED` 에 모델과 토큰과 `hermesSessionId` 가 남는다. 도구 사건에는 남지 않는다
- `backend/src/test/java/com/bifos/assistant/chat/ChatServiceTest.java`
  - 스트림이 도구 사건 둘과 하위 에이전트 사건 둘을 보내면 `tool` 둘과 `subagent` 둘이 `phase` 와 함께 중계된다
  - `activitySummaries` 가 도구와 하위 에이전트와 자손 실행을 센다. 사건이 없는 답은 표에 없다
  - 사건이 하나도 없는 자손(Memory 제안 실행의 모양)은 `subagentCount` 에 들어가지 않는다
  - 뿌리가 먼저 끝나고 자손이 더 늦게 끝난 나무에서 `durationMs` 가 뿌리의 `latencyMs()` 보다 크고,
    뿌리 `startedAt()` 부터 자손의 `finishedAt()` 까지와 같다
- `test/e2e/scenarios/streaming.ts`
  - `tool` 이 넷이고 `subagent` 가 둘이다. 여섯을 세던 검사를 이것으로 바꾼다
  - `"하위 에이전트 칸 검사"` 를 보내면 `subagent` 의 `goal`, `model`, `inputTokens`, `outputTokens` 가 오고,
    실행 나무 응답의 `SUBAGENT_COMPLETED` 에 `model` 과 토큰이 남는다
  - 메시지 조회의 답에 `activity` 가 있고 `toolCount` 가 2, `subagentCount` 가 1 이상이다
  - 시나리오 파일의 `ChatEvent` 타입에 `subagent` 를 더한다

## 검증

```bash
# cwd: 저장소 root
cd backend && ./gradlew test
```

```bash
# cwd: 저장소 root
node test/e2e/run.ts
scripts/check-public-safe.sh
```

모두 종료 코드 0 이어야 한다. `gradlew test` 를 먼저 돌린다. 건너뛰면 e2e 가 앞선 데이터에 걸릴 수 있다.

```bash
# cwd: 저장소 root
grep -rn '"subagent"' backend/src/main/java/com/bifos/assistant/chat/application/ChatEvent.java
```

한 줄 이상 나와야 한다.

## Critical Files

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/hermes/dto/RunEvent.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/hermes/HermesRunEventStream.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/application/ChatEvent.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/application/ChatService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/presentation/ChatDtos.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/presentation/ChatController.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/usage/domain/ExecutionEvent.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/usage/application/ExecutionEventRecorder.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/usage/application/ExecutionEventView.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/usage/infra/AgentExecutionRepository.java` | 수정 |
| `backend/src/main/resources/db/migration/V{다음}__execution_event_subagent.sql` | 신규 |
| `backend/src/test/java/com/bifos/assistant/hermes/HermesRunEventStreamTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/usage/ExecutionEventRecorderTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/ChatServiceTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/memory/MemoryProposerTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/orchestration/ResearchAndBuildFlowTest.java` | 수정 |
| `test/e2e/fake-hermes.ts` | 수정 |
| `test/e2e/scenarios/streaming.ts` | 수정 |

끝나면 `tasks/plan020-agent-activity/index.json` 의 이 phase 를 `completed` 로 바꾸고 `current_phase` 를 2로 올린다.
