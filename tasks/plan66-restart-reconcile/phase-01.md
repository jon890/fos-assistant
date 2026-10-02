# Phase 01. Hermes 실행 조회 한 번

**Execution profile**: standard

## 목표

실행 하나의 지금 상태를 Hermes 에 한 번 묻는 메서드를 `HermesRunsClient` 에 더한다.
기동 정리가 「끝났다, 아직 돈다, Hermes 가 모른다, 닿지 못했다」 를 구분하려면 끝날 때까지 기다리는 `awaitCompletion` 으로는 안 된다.

**범위 외**: 그 답을 실행 줄에 적는 것(phase 02), 기동 때 부르는 것(phase 03), e2e 의 가짜 Hermes(phase 04).

## 컨텍스트

- `backend/src/main/java/com/bifos/assistant/hermes/HermesRunsClient.java` 가 인터페이스다. 구현은 `HttpHermesRunsClient`(같은 패키지)와 테스트 대역 `backend/src/test/java/com/bifos/assistant/hermes/StubHermesRunsClient.java` 둘이다
- `HttpHermesRunsClient` 에는 이미 `private JsonNode fetch(String apiBaseUrl, String runId, String apiKey)` 와 `private HermesRunResult toResult(String runId, String status, JsonNode run)`, 종료 상태 집합 `TERMINAL`(`completed`, `failed`, `cancelled`, `error`, `interrupted`)이 있다. `poll` 이 이 셋으로 끝날 때까지 돈다
- `fetch` 는 `RestClientException` 을 모두 `HermesCallFailure.of(...)` 로 옮긴다. 404 도 `HERMES_UNAVAILABLE` 이 된다. `stop` 은 `HttpClientErrorException.NotFound` 를 먼저 잡는다. 같은 방식으로 404 를 구분한다
- API key 는 `keyStore.resolve(profileName)` 으로 읽는다
- Jackson 은 `tools.jackson` 이다(`backend/AGENTS.md` 의 「기술 주의점」)
- HTTP 모양을 검사하는 선례는 `backend/src/test/java/com/bifos/assistant/hermes/HermesRuntimeReadTest.java` 와 `HermesRunRequestTest.java` 다. 같은 방식(같은 가짜 서버 도구)을 쓴다

**근거 문서**: `docs/hermes/runs-api.md` 의 「실행 조회가 답하는 기간」, `docs/adr/ADR-059-재기동-때-남은-실행은-hermes-에-물어-정하고-도는-실행에는-다시-붙는다.md`

## 의도 메모

- 종료 상태가 아닌 `status` 는 모두 「아직 돈다」 로 읽는다. Hermes 는 `running` 말고도 `stopping`, `waiting_for_approval` 을 준다. `status` 가 없거나 글이 아니어도 「아직 돈다」 다. 모르는 값을 실패로 읽으면 도는 실행을 잃는다
- 404 만 「모른다」 다. 401, 403, 429, 5xx, 연결 실패는 예외로 올린다. 부르는 쪽이 다시 묻는다
- `awaitCompletion` 과 `poll` 은 고치지 않는다

## 작업 항목

### 1. `backend/src/main/java/com/bifos/assistant/hermes/dto/HermesRunLookup.java` (신규)

```java
/** 실행 하나를 한 번 물은 답이다. */
public record HermesRunLookup(State state, HermesRunResult result) {
    public enum State { RUNNING, FINISHED, NOT_FOUND }
    public static HermesRunLookup running() { ... }      // result 는 null
    public static HermesRunLookup finished(HermesRunResult result) { ... }
    public static HermesRunLookup notFound() { ... }     // result 는 null
}
```

Javadoc 은 한국어로 쓴다. `FINISHED` 일 때만 `result` 가 있다는 것을 적는다.

### 2. `HermesRunsClient.lookupRun`

```java
/**
 * 실행 하나의 지금 상태를 한 번 읽는다. 기다리지 않는다.
 *
 * <p>Hermes 가 그 run 을 모르면(404) NOT_FOUND 다. 닿지 못했거나 다른 오류면 ApiException 을 던진다.
 */
HermesRunLookup lookupRun(String apiBaseUrl, String profileName, String runId);
```

`default` 로 두지 않는다. 구현 둘이 모두 정해야 한다.

### 3. `HttpHermesRunsClient.lookupRun`

`GET {apiBaseUrl}/v1/runs/{runId}` 를 한 번 부른다. `HttpClientErrorException.NotFound` 는 `HermesRunLookup.notFound()`, 그 밖의 `RestClientException` 은 `HermesCallFailure.of(ex, "could not read the run status")`.
`status` 가 `TERMINAL` 에 있으면 `finished(toResult(runId, status, run))`, 아니면 `running()`.
`fetch` 를 그대로 쓰면 404 가 예외로 바뀌므로, 404 를 먼저 잡는 조회를 따로 두거나 `fetch` 가 404 를 구분하게 고친다. `poll` 의 동작은 바꾸지 않는다.

### 4. `StubHermesRunsClient`

- `public void willLookup(String runId, HermesRunLookup... lookups)`: 그 run 을 물을 때 차례로 돌려줄 답. 마지막 답은 되풀이한다. 같은 run 에 다시 부르면 앞에 정한 답을 버리고 새 답으로 바꾼다. 테스트가 「아직 돈다」 를 되풀이하게 해 두고 단언한 뒤 끝난 답으로 바꾸는 데 쓴다
- `public void willFailLookup(String runId, ApiException failure, int times)`: 처음 `times` 번은 예외를 던지고 그 뒤에는 `willLookup` 의 답을 준다
- `public List<String> lookups()`: 물은 run 번호를 순서대로
- 정해 두지 않은 run 은 `HermesRunLookup.notFound()` 다
- `reset()` 이 위 상태를 모두 비운다

여러 스레드가 함께 부르므로 기존 칸처럼 동시에 쓸 수 있는 자료 구조를 쓴다.

### 5. `backend/src/test/java/com/bifos/assistant/hermes/HermesRunLookupTest.java` (신규)

`HttpHermesRunsClient.lookupRun` 을 가짜 HTTP 서버로 검사한다.

| 입력 | 기대 |
| --- | --- |
| 200 `{"status":"completed","output":"답","session_id":"s1","usage":{"input_tokens":10,"output_tokens":2,"total_tokens":12},"runtime":{"provider":"p","model":"m"}}` | `FINISHED`. `result.output()` 이 `답`, `usage().inputTokens()` 가 10, `runtime().model()` 이 `m` |
| 200 `{"status":"running"}` | `RUNNING` |
| 200 `{"status":"stopping"}` 과 `{"status":"waiting_for_approval"}` | `RUNNING` |
| 200 `{"status":"interrupted","error":"x"}` | `FINISHED`, `result.succeeded()` 가 거짓 |
| 404 | `NOT_FOUND` |
| 503 | `ApiException`, 코드 `HERMES_UNAVAILABLE` |
| 429 | `ApiException`, 코드 `HERMES_BUSY` |
| 요청 | 경로가 `/v1/runs/{runId}` 이고 `Authorization: Bearer <그 profile 의 key>` 다 |

## 검증

```bash
# cwd: 저장소 root
cd backend && ./gradlew test --tests 'com.bifos.assistant.hermes.HermesRunLookupTest' --tests 'com.bifos.assistant.hermes.*'
cd backend && ./gradlew compileTestJava checkstyleMain checkstyleTest spotlessCheck
```

둘 다 종료 코드 0. `StubHermesRunsClient` 를 쓰는 기존 테스트가 그대로 컴파일돼야 한다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/hermes/dto/HermesRunLookup.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/hermes/HermesRunsClient.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/hermes/HttpHermesRunsClient.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/hermes/StubHermesRunsClient.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/hermes/HermesRunLookupTest.java` | 신규 |
