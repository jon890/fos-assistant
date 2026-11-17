# Phase 02. 실행 클라이언트의 v0.23.2 번역

**Execution profile**: standard

## 목표

`HttpHermesRunsClient` 가 v0.23.2 의 `queued`, `unknown`, `cancelled` 와 빠진 조회 칸을 번역하고, 잃은 실행을 해제한다. v0.21.6 응답은 지금과 같게 읽는다.

**범위 외**: application 의 steer 정리와 미리 풀기 호출(phase 03), 가짜 Hermes 와 문서(phase 04).

## 컨텍스트

**요구사항**: REQ1, REQ2, REQ3

**근거 문서**: `docs/features/hermes-upgrade/design.md` 의 「조회 응답 읽기」, 「`cancelled` 구분」, 「해제」, 「결과 칸 채우기」, 「중지」

- 지금 코드: `lookupRun`(162행 부근), `poll`/`fetch`(336~363행 부근), `toResult`(373행 부근), `stop`(93행 부근), 종료 상태 집합 `TERMINAL`.
- v0.23.2 응답인지는 조회 응답에 `admission_id` 가 있는지로 안다. 이 판정과 상태 문자열은 이 class 안에만 둔다.
- 해제 요청: `POST /v1/runs/{id}/resolve-unknown`, 본문 `{"admission_id": <조회 값>, "execution_generation": <조회 값(정수)>}`. 상류 근거는 v0.23.2 `gateway/platforms/api_server_authority_runs.py` 의 `resolve_unknown_run`.
- 중지가 `unknown` 실행이면 Hermes 는 409 와 오류 코드 `unknown_execution` 을 준다(`stop_run`). 오류 본문은 `{"error": {"code": "unknown_execution", ...}}` 처럼 `error.code` 한 단계 안쪽에 있다(v0.23.2 `gateway/platforms/api_server.py` 의 `_openai_error`). `HermesCallFailure` 의 맨 위 `code` 읽기와 다르므로 이 class 에서 `error.code` 를 읽는다.
- `ChatRunEvents.awaitCompletion` 은 `ApiException` 의 코드를 실행 줄 `error_code` 로 적고 `holdUntilRemoteEnds` 를 부른다. 그래서 `REMOTE_RUN_LOST` 예외만 던지면 turn 쪽 기록은 따로 고칠 것이 없다.

## 의도 메모

- 해제한 뒤 다시 조회하지 않는다. v0.23.2 는 해제된 실행을 `cancelled` 로 답해 사용자 중지로 읽힌다(설계 「조회 응답 읽기」).
- `cancelled` 를 그대로 넘기는 안은 기각했다. Hermes 정상 종료로 끊긴 실행이 「중지됨」 으로 적혀 REQ11 의 「재기동하면 실패」 가 깨진다(설계 DECISION7).

## 작업 항목

### 1. `HttpHermesRunsClient` 의 조회 번역

- `@Autowired` 생성자에 `HermesRunTerminals`, `HermesRunEventStream`, `@Value("${assistant.hermes.terminal-event-read-timeout:5s}") Duration` 을 더 받는다. 기존 steer 제한 시간(`assistant.chat.steer.timeout`)과 같은 방식이다. `HermesProperties` record 는 고치지 않는다(테스트 31개 파일이 직접 만든다). 지금의 3인자 생성자는 남기고 새 보관소, 사건 스트림 없음(다시 읽기 안 함), 5초로 채운다. 이 생성자를 쓰는 테스트(`HermesRuntimeReadTest`, `HermesRunLookupTest`, `HermesRunRequestTest`, `HermesSteerRequestTest`, `HermesBusyTest`)는 고치지 않는다.
- 조회 응답을 읽는 한 메서드(`interpret(apiBaseUrl, profileName, runId, JsonNode run)`)가 `lookupRun` 과 `poll` 에서 함께 쓰인다.
  - `unknown`: `resolveUnknown` 을 부른 뒤 `lookupRun` 은 `HermesRunLookup.notFound()`, `poll` 은 `ApiException(ErrorCode.REMOTE_RUN_LOST, "Hermes lost the run across a restart")` 을 던진다.
  - `queued` 와 모르는 값: 지금처럼 도는 중.
  - 끝 상태: `toResult`.
- `toResult`: v0.23.2 응답이면 설계 「결과 칸 채우기」 표대로 채운다. 종료 사건은 `HermesRunTerminals.take` 를 먼저 보고, 없으면 `HermesRunEventStream.readTerminal` 을 한 번 부른다. `run.interrupted` 이거나 종료 사건이 없으면 `pendingSteerKnown=false`.
- `cancelled` 구분(v0.23.2 응답만): 종료 사건이 `run.cancelled` 면 그대로, `run.interrupted` 면 `interrupted`, 종료 사건이 없으면 `HermesRunTerminals.stopped(...)` 가 참일 때만 `cancelled`, 아니면 `interrupted`.

### 2. 해제와 `releaseIfLost`

- `private void resolveUnknown(apiBaseUrl, profileName, runId, JsonNode run)`: 위 본문으로 POST. 200 이면 끝. 409·404 면 한 번 다시 조회해 `unknown` 이 아니면 끝, 여전히 `unknown` 이면 `ApiException(HERMES_UNAVAILABLE)`. 그 밖의 실패는 `HermesCallFailure.of`.
- `releaseIfLost`: 조회해서 `unknown` 이면 `resolveUnknown`, 아니면 아무것도 하지 않는다. 404 도 아무것도 하지 않는다. 끝 상태이거나 해제를 마친 run 은 `HermesRunTerminals` 의 「확인 끝」 집합(같은 보존 시간이 아니라 24시간)에 남겨 다시 묻지 않는다. 도는 중이거나 묻지 못했으면 남기지 않는다.

### 3. 중지

- `stop` 이 성공하면 `HermesRunTerminals.markStopped`.
- 409 이고 오류 코드가 `unknown_execution` 이면 조회해 `resolveUnknown` 을 부르고 정상으로 돌아온다. 이때도 `markStopped` 를 남긴다.

### 4. 테스트 `backend/src/test/java/com/bifos/assistant/hermes/infra/HttpHermesRunsClientCompatTest.java`(신규)

`MockRestServiceServer` 나 JDK `HttpServer` 대역(기존 `HermesRuntimeReadTest` 가 쓰는 방식을 따른다)으로 아래를 본다.
- v0.21.6 모양: `completed`, `cancelled`, `interrupted`, 404 가 지금과 같다(`pendingSteerKnown=true`).
- v0.23.2 `queued` → `running` 뒤 `completed` 로 끝난다.
- v0.23.2 `unknown`: 해제 요청 본문이 두 칸이고, `lookupRun` 은 `NOT_FOUND`, `awaitCompletion` 은 `REMOTE_RUN_LOST`.
- 해제 409 뒤 다시 조회가 `cancelled` 면 해제로 보고, 여전히 `unknown` 이면 실패.
- 해제 요청이 500 이나 연결 실패면 예외가 올라가고, 다음 조회에서 다시 해제 요청이 간다.
- `cancelled` 구분 네 경우(설계 표).
- 결과 칸 채우기: 보관소의 종료 사건, 보관분 다시 읽기, 둘 다 없음.
- `stop` 의 409 `unknown_execution`.
- `releaseIfLost`: `unknown` 이면 해제, `completed` 와 404 면 요청 없음.

## 검증

```bash
cd backend && ./gradlew test --tests '*HttpHermesRunsClientCompatTest' --tests '*HermesRuntimeReadTest' --tests '*HermesRunLookupTest' --tests '*HermesRunRequestTest'
cd backend && ./gradlew checkstyleMain checkstyleTest
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/hermes/infra/HttpHermesRunsClient.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/hermes/infra/HermesRunTerminals.java` | 수정 (「확인 끝」 집합) |
| `backend/src/main/resources/application.yml` | 수정 (`assistant.hermes.terminal-event-read-timeout` 기본값과 설명) |
| `backend/src/test/java/com/bifos/assistant/hermes/infra/HttpHermesRunsClientCompatTest.java` | 신규 |
