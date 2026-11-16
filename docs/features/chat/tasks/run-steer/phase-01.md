# Phase 01. Hermes steer 호출과 `pending_steer` 읽기

**Execution profile**: standard

## 목표

Control Plane 이 루트 run 에 `POST /v1/runs/{run_id}/steer` 를 보내고 결과를 받음·거절·모름으로 나누며, 끝난 실행 결과에서 `pending_steer` 를 읽는다.

**범위 외**: 언제 끼울지 판정(phase 3), 대기 줄과 말풍선(phase 2~4), 계약 문서(phase 5).

## 컨텍스트

**요구사항**: REQ9

**근거 문서**: `docs/features/chat/spec.md`, `docs/features/chat/design.md` 의 「Hermes」, `hermes/docs/hermes-contract.md` 의 steer 절

- 포트는 `backend/src/main/java/com/bifos/assistant/hermes/application/port/HermesRunsClient.java`, 구현은 `hermes/infra/HttpHermesRunsClient.java` 다. `stop` 이 같은 key 해석(`keyStore.resolve(profileName)`)과 `HermesCallFailure` 를 쓰는 본보기다.
- 끝난 결과는 `poll` → `toResult` 가 `GET /v1/runs/{id}` 에서 만든다. `pending_steer` 는 같은 응답의 칸이다.
- 지금 `RestClient` 는 `HermesProperties` 의 `readTimeout` 하나를 쓴다(`requestFactory`). steer 는 짧은 제한 시간이 필요하다.

## 의도 메모

- steer 결과를 예외로 나누지 않고 값(`SteerOutcome`)으로 돌려준다. 부르는 쪽이 거절과 모름을 다르게 다뤄야 하고(스펙 DECISION23), 예외로 두면 잡는 자리마다 분류가 갈린다.
- 4xx·5xx 응답은 Hermes 가 「받지 않았다」 고 답한 것이므로 거절이다. 응답이 없을 때만 모름이다.

## 작업 항목

### 1. `hermes/application/dto/SteerOutcome.java`(신규)

`enum SteerOutcome { ACCEPTED, REJECTED, UNKNOWN }`. 한국어 Javadoc 에 각 값의 판정을 적는다(design 의 「Hermes」 표).

### 2. `HermesRunsClient` 에 `default SteerOutcome steer(String apiBaseUrl, String profileName, String runId, String text)`

default 는 `REJECTED` 를 돌려준다(steer 를 모르는 구현은 끼우지 않는다). Javadoc 에 「받음은 슬롯에 넣었다는 뜻이지 모델이 읽었다는 뜻이 아니다」 를 적는다.

### 3. `HttpHermesRunsClient.steer`

- 본문 `{"input": text}`, 헤더는 `stop` 과 같다.
- 읽기 제한 시간이 `assistant.chat.steer.timeout`(기본 5초)인 요청 factory 로 만든 `RestClient` 를 따로 둔다. 연결 제한 시간은 기존 값을 쓴다. 제한 시간은 이 클래스 생성자의 `@Value("${assistant.chat.steer.timeout:5s}") Duration` 한 곳에서만 읽는다(hermes 모듈이 chat 설정을 import 하지 않게. phase 3 의 `SteerProperties` 는 `enabled` 만 갖는다). 생성자가 바뀌므로 `new HttpHermesRunsClient(` 를 부르는 시험 넷을 고친다.
- 200 이고 본문 `accepted` 가 `true` 면 `ACCEPTED`. `HttpStatusCodeException`(4xx·5xx)이면 `REJECTED` 와 상태·코드를 `log.info`. `ResourceAccessException` 과 그 밖의 `RestClientException` 은 `UNKNOWN` 과 `log.warn`. 본문을 로그에 싣지 않는다(사용자 글).

### 4. `HermesRunResult.pendingSteer`

레코드 끝에 `String pendingSteer` 를 더하고 기존 생성자들은 null 을 넘긴다. `toResult` 가 `text(run, "pending_steer")` 를 넣는다. 빈 글이면 null 로 둔다.

### 5. `backend/src/test/java/com/bifos/assistant/hermes/StubHermesRunsClient.java`

`willSteer(SteerOutcome...)`(차례로 돌려줌, 없으면 `ACCEPTED`), `beforeSteer(Runnable)`, `steered()`(받은 `runId`, 글 목록)를 더하고 `reset` 이 비운다. `StubHermesRunsClientTest` 에 한 사례.

### 6. `backend/src/test/java/com/bifos/assistant/hermes/HermesSteerRequestTest.java`(신규)

`HermesRunRequestTest` 의 `HttpServer` 방식으로: 200 accepted → `ACCEPTED` 와 경로·본문·Authorization, 409 → `REJECTED`, 500 → `REJECTED`, 응답을 제한 시간보다 늦게 줌 → `UNKNOWN`, `GET /v1/runs/{id}` 에 `pending_steer` 가 있으면 결과에 실림.

## 검증

```bash
cd backend && HEAVY_SLOTS=2 orch slot heavy -- ./gradlew test --tests '*HermesSteerRequestTest' --tests '*StubHermesRunsClientTest' --tests '*HermesRunRequestTest' --tests '*HermesRunLookupTest' --tests '*HermesBusyTest' --tests '*HermesRuntimeReadTest' --tests '*HermesWireCompatibilityTest'
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/hermes/application/dto/SteerOutcome.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/hermes/application/port/HermesRunsClient.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/hermes/infra/HttpHermesRunsClient.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/hermes/application/dto/HermesRunResult.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/hermes/StubHermesRunsClient.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/hermes/StubHermesRunsClientTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/hermes/HermesSteerRequestTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/hermes/HermesRunRequestTest.java` | 수정(생성자 인자) |
| `backend/src/test/java/com/bifos/assistant/hermes/HermesRunLookupTest.java` | 수정(생성자 인자) |
| `backend/src/test/java/com/bifos/assistant/hermes/HermesBusyTest.java` | 수정(생성자 인자) |
| `backend/src/test/java/com/bifos/assistant/hermes/HermesRuntimeReadTest.java` | 수정(생성자 인자) |
