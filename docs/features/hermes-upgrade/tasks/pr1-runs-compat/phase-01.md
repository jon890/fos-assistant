# Phase 01. 포트와 종료 사건 보관

**Execution profile**: standard

## 목표

뒤 phase 가 쓸 포트 변경(`pendingSteerKnown`, `releaseIfLost`, `REMOTE_RUN_LOST`)과, 사건 스트림이 받은 종료 사건을 어댑터끼리 나누는 보관소를 만든다.
v0.23.2 의 조회 응답에는 `error`, `pending_steer` 가 없어 종료 사건에서 얻어야 하기 때문이다.

**범위 외**: 조회 응답의 번역과 해제(phase 02), application 의 steer 정리와 미리 풀기(phase 03).

## 컨텍스트

**요구사항**: REQ3, REQ4

**근거 문서**: `docs/features/hermes-upgrade/spec.md`, `docs/features/hermes-upgrade/design.md` 의 「책임과 경계」, 「결과 칸 채우기」

- Hermes 의 상태 문자열과 사건 이름은 `hermes/infra` 안에서만 다룬다(설계 「책임과 경계」). application 에 새 문자열 비교를 두지 않는다.
- `HermesRunEventStream.open(apiBaseUrl, profileName, runId, onEvent, onOpened, scope)` 가 사건 하나마다 `emit` → `toRunEvent` 를 부른다. 종료 사건 이름은 `WIRE_EVENTS` 의 `run.completed`, `run.failed`, `run.cancelled`, `run.interrupted` 다.
- `RunEvent` 에는 `error`, `pending_steer`, `runtime` 이 없다. 보관은 `RunEvent` 를 넓히지 않고 원본 JSON 에서 읽어 따로 둔다.
- 사건 보관분 다시 읽기는 phase 02 가 이 보관소와 새 진입점을 써서 한다. 이 phase 는 「끝난 run 의 사건을 열어 종료 사건 하나만 돌려주는」 진입점까지 만든다.

## 의도 메모

- `RunEvent` 에 칸을 더하는 안은 기각했다. application(`ChatRunEvents`)이 종료 사건의 칸을 보게 되어 Hermes 사건 모양이 밖으로 샌다.
- 보관소를 DB 테이블로 두는 안은 기각했다(테이블을 늘리지 않는다, 사용자 2026-11-01). 프로세스가 다시 뜨면 비는 것이 스펙 DECISION3 의 「모른다」 와 맞다.

## 작업 항목

### 1. `backend/src/main/java/com/bifos/assistant/hermes/infra/HermesRunTerminals.java`(신규)

`@Component`. 키는 `profileName + "\n" + runId`.
- `record Terminal(String event, String error, String pendingSteer, boolean pendingSteerPresent, String servedModel, String servedProvider)`. `event` 는 종료 사건 이름 그대로다(infra 안).
- `void record(String profileName, String runId, Terminal terminal)`, `Optional<Terminal> take(String profileName, String runId)`(읽으면 지움).
- `void markStopped(String profileName, String runId)`, `boolean stopped(String profileName, String runId)`.
- 두 표 모두 `hermes.run-timeout` 보다 오래된 항목을 기록할 때 지운다(`Clock` 주입, `ConcurrentHashMap`).

### 2. `HermesRunEventStream` 수정

- `@Autowired` 생성자에 `HermesRunTerminals` 를 받는다. 테스트가 쓰는 지금의 3·4 인자 생성자는 남기고 새 `HermesRunTerminals` 를 만들어 넣는다(`ToolDetailStreamTest`, `SkillViewStreamTest`, `ToolDetailEventStreamTest`, `HermesInterimWire` 가 쓴다).
- `emit` 에서 종료 사건이면 원본 JSON 의 `error`, `pending_steer`(칸이 있는지도), `runtime.model`, `runtime.provider` 를 `HermesRunTerminals.record` 로 넘긴다. `readEvents` 에 profile 과 runId 를 넘긴다.
- `durationMs`: 값이 0 이면 `null`(설계 DECISION6).
- 새 진입점 `Optional<HermesRunTerminals.Terminal> readTerminal(String apiBaseUrl, String profileName, String runId, Duration readTimeout)`: `GET /v1/runs/{id}/events` 를 열어 종료 사건을 만나면 그것을 돌려주고 닫는다. 끝까지 없으면 빈 값. 연결 실패와 404 도 빈 값이며 예외를 올리지 않는다. 이 진입점은 `onEvent` 를 부르지 않고, 읽은 종료 사건을 보관소에 남기지 않는다(부른 쪽이 바로 쓴다).
- `run.queued` 는 `WIRE_EVENTS` 에 더하지 않는다(지금처럼 건너뜀).

### 3. 포트와 DTO

- `HermesRunResult` 에 마지막 필드 `boolean pendingSteerKnown` 을 더한다. 기존 생성자들은 `true` 로 채운다. 새 생성자 하나가 이 값을 받는다.
- `HermesRunsClient` 에 `default void releaseIfLost(String apiBaseUrl, String profileName, String runId) {}` 를 더한다. Javadoc: 그 run 을 Hermes 가 잃은 것으로 알고 있으면 해제하고, 아니면 아무것도 하지 않는다. 실패는 예외로 올린다.
- `ErrorCode` 에 `REMOTE_RUN_LOST(HttpStatus.BAD_GATEWAY)` 를 더한다.
- `StubHermesRunsClient`(test)는 `releaseIfLost` 로 받은 run 번호를 목록(`releasedRuns()`)에 남긴다. `StubHermesRunsClientTest` 에 그 목록을 보는 경우 하나를 더한다.

### 4. 화면 문구

`web/src/components/error-message.ts` 의 `MESSAGES` 에 `REMOTE_RUN_LOST` 를 `HERMES_UNAVAILABLE` 과 같은 문구로 더한다(설계 DECISION10).

### 5. 테스트

- `backend/src/test/java/com/bifos/assistant/hermes/infra/HermesRunEventStreamTest.java`: v0.23.2 모양 합성 사건(`tool_call_id` 가 붙은 도구 사건, `duration` 0, `run.queued`, `pending_steer` 가 든 `run.completed`, 칸 없는 `run.interrupted`)을 넣어 도구 사건은 지금처럼, `duration` 은 null, `run.queued` 는 건너뛰고, 보관소에 종료 사건이 남는지 본다. `readTerminal` 이 보관분에서 종료 사건 하나를 얻고, 404 에서 빈 값인지 본다.
- `HermesRunTerminalsTest`(신규): 읽으면 지워지고, 오래된 항목이 지워진다.

## 검증

```bash
cd backend && ./gradlew test --tests '*HermesRunEventStreamTest' --tests '*HermesRunTerminalsTest' --tests '*StubHermesRunsClientTest'
cd backend && ./gradlew checkstyleMain checkstyleTest
cd web && npx tsc --noEmit
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/hermes/infra/HermesRunTerminals.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/hermes/infra/HermesRunEventStream.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/hermes/application/dto/HermesRunResult.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/hermes/application/port/HermesRunsClient.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/shared/error/ErrorCode.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/hermes/StubHermesRunsClient.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/hermes/infra/HermesRunEventStreamTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/hermes/infra/HermesRunTerminalsTest.java` | 신규 |
| `web/src/components/error-message.ts` | 수정 |
