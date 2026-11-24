# Phase 01. 사건 스트림의 걸린 시간과 오류 칸 저장 자리

**Execution profile**: standard

## 목표

Hermes v0.23.2 의 도구 사건에서 걸린 시간을 계산해 흘리고 저장한다. 오류 종류와 요약을 담을 칸을 만들고 관리자에게만 응답에 넣는다.

**범위 외**: plugin 기록을 받는 길(phase 2), plugin(phase 3), 화면(phase 4).

## 컨텍스트

**요구사항**: REQ1, REQ3, REQ12

**근거 문서**: `docs/features/run-trace/spec.md`, `docs/features/run-trace/design.md`, `docs/features/execution.md`, `hermes/docs/hermes-contract.md`

- `HermesRunEventStream.durationMs` 는 이미 0 을 모름(null)으로 읽는다. v0.23.2 의 `tool.started`, `tool.completed` 에는 `tool_call_id` 와 `timestamp`(초 단위 실수)가 붙는다.
- `ToolDetailRedactor.redact(..., afterConnector)` 가 빈 글에도 `[도구 내용 가림: 0자]` 를 낸다(#319).
- `ExecutionEventView` 는 `InternalValuePolicy` 로 모델과 토큰을 관리자에게만 넣는다.

## 의도 메모

- 짝짓기는 `tool_call_id` 로만 한다. 이름 순서로 짝지으면 병렬 도구에서 틀린 시간이 나온다(스펙 DECISION7 의 까닭과 같다).
- `HermesRunEvent` 와 `RunEvent` 는 테스트에서 생성자를 많이 부른다. 새 칸을 끝에 더하고 옛 모양의 생성자를 남겨 둔다.

## 작업 항목

### 1. `hermes/infra/HermesRunEventStream.java`, `hermes/application/dto/HermesRunEvent.java`, `runtime/application/dto/RunEvent.java`, `hermes/infra/HermesRunEventKinds.java`

`tool_call_id` 를 읽어 `toolCallId` 칸으로 옮긴다. 스트림 상태(`DetailState`)에 `tool_call_id → 시작 timestamp` 표를 두고, `tool.completed` 의 `duration` 이 모름이고 짝이 있으면 `Math.round((완료 - 시작) * 1000)` 을 걸린 시간으로 낸다. 음수면 모름이다.

### 2. `runtime/domain/ToolDetailRedactor.java`

- `afterConnector` 이고 내용이 빈 글이면 null 을 낸다(REQ12).
- `redactError(String message, String toolName, ToolDetailScope scope, boolean afterConnector)` 를 더한다. 빈 글, 플러그인 도구, 플러그인 호출 뒤면 null. 입력 상한을 넘으면 `[긴 도구 내용 가림]`. 나머지는 `redactContent` 뒤 `ERROR_LIMIT = 200` 으로 자른다.

### 3. 마이그레이션과 엔티티

`backend/src/main/resources/db/migration/V20261105074444__execution_event_tool_trace.sql` 에 `tool_call_id VARCHAR(128) NULL`, `error_type VARCHAR(64) NULL`, `error_summary VARCHAR(255) NULL` 을 더한다. `ExecutionEvent` 에 칸과 builder, `recordToolTrace(Long durationMs, String errorType, String errorSummary)` 를 더한다. `ExecutionEventRecorder.record` 가 도구 사건의 `toolCallId` 를 넣는다. `ExecutionEventRepository.clearDetailsOf` 가 `error_summary` 도 비운다.

### 4. `usage/tree/application/dto/ExecutionEventView.java`

`errorType`, `errorSummary` 를 끝에 더하고 `InternalValuePolicy` 가 허락할 때만 넣는다.

### 5. 테스트

- `HermesRunEventStreamTest`: v0.23.2 모양으로 1.2초 도구를 흘리면 완료 사건의 `durationMs` 가 1200 이다. 짝이 없으면 null 이다.
- `ToolDetailRedactorTest`: 플러그인 호출 뒤 빈 글은 null. 오류 글의 비밀값과 UUID 가 가려지고, 200자 경계에 걸친 토큰 조각이 남지 않는다.
- `ExecutionTreeServiceTest` 또는 `ExecutionEventView` 테스트: `MEMBER` 는 두 칸이 null, `ADMIN` 은 값이 있다.

## 검증

```bash
cd backend && HEAVY_SLOTS=2 orch slot heavy -- ./gradlew test --tests '*HermesRunEventStreamTest' --tests '*ToolDetailRedactorTest' --tests '*ExecutionTreeServiceTest' --tests '*ExecutionEventRecorderTest'
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/hermes/infra/HermesRunEventStream.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/hermes/infra/ToolElapsed.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/hermes/application/dto/HermesRunEvent.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/hermes/infra/HermesRunEventKinds.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/runtime/application/dto/RunEvent.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/runtime/domain/ToolDetailRedactor.java` | 수정 |
| `backend/src/main/resources/db/migration/V20261105074444__execution_event_tool_trace.sql` | 신규 |
| `backend/src/main/java/com/bifos/assistant/usage/execution/domain/ExecutionEvent.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/usage/execution/application/ExecutionEventRecorder.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/usage/execution/infra/ExecutionEventRepository.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/usage/tree/application/dto/ExecutionEventView.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/hermes/infra/HermesRunEventStreamTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/runtime/domain/ToolDetailRedactorTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/usage/ExecutionTreeServiceTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/hermes/infra/HermesRunEventKindTest.java` | 수정 (새 칸) |
