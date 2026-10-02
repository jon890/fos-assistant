# Phase 02. Control Plane 이 자식의 provider 를 대시보드에서 읽어 원장 줄을 환산한다

**Execution profile**: standard

## 목표

재조회가 종료 자식을 원장 줄에 적을 때, session 응답에 provider 가 없으면 대시보드 plugin 의 읽기 경로에서 provider 를 받아 금액을 환산한다.
지금은 native 자식이 모두 `PROVIDER_UNKNOWN` 으로 남아 금액이 빈다.

**범위 외**: 대시보드 plugin(phase 01), 가짜 Hermes 와 e2e(phase 03), 마이그레이션. 표와 칸은 바꾸지 않는다. 이미 `DONE` 인 줄을 다시 조회하지 않는다.

## 컨텍스트

- 재조회는 `backend/src/main/java/com/bifos/assistant/usage/application/SubagentUsageReconciler.java` 의 `poll(Long jobId)` 이다.
  Hermes 조회(`hermes.readSubagentUsage`)를 트랜잭션 밖에서 먼저 하고, 트랜잭션 안에서 `isFinalChild` 를 본 뒤 `unpricedReason`, `estimator.estimate`, `current.record(usage, cost, reason, now)` 순서로 적는다
- 대시보드를 부르는 본보기는 `backend/src/main/java/com/bifos/assistant/hermes/ProfileModelDefaultsClient.java` 다. `HermesProperties` 의 `dashboardBaseUrl()`, `dashboardToken()`, `connectTimeout()`, `readTimeout()` 으로 `RestClient` 를 만들고 `Authorization: Bearer` 를 붙인다. `RestClient.Builder` 는 자동 구성되지 않는다
- `SubagentSessionUsage`(`backend/src/main/java/com/bifos/assistant/hermes/dto/SubagentSessionUsage.java`)는 record 이고 `endedAt` 은 epoch 초(`Double`)다
- JSON 은 `tools.jackson.databind.JsonNode` 다. `com.fasterxml.jackson` 을 import 하지 않는다
- 주석과 Javadoc 은 한국어로 쓴다. 테스트 메서드는 영문 camelCase 이고 `@DisplayName` 에 한국어 문장을 쓴다

경로의 계약은 아래와 같다.

| 요청 | 답 |
| --- | --- |
| `GET {dashboardBaseUrl}/api/profiles/{profile}/sessions/{sessionId}/provider` | 200 `{"provider": 문자열이나 null, "model": 문자열이나 null}` |
| 없는 profile, 없는 session, 자식이 아닌 session | 404 |
| 형식이 틀렸다 | 400 |
| 토큰이 없거나 틀렸다. 옛 plugin 도 이 답이다 | 401 |
| 저장소를 읽지 못했다 | 503 |

**근거 문서**: `docs/model-tiers.md` 의 「원장 줄에 적는 것」, `docs/adr/ADR-063-native-하위-에이전트의-provider-는-대시보드-plugin-이-session-저장소에서-읽어-준다.md`, `hermes/README.md` 의 「자식 session 의 provider」

## 의도 메모

- 부모 실행의 provider 로 채우지 않는다(ADR-062)
- 대시보드 조회는 트랜잭션 밖에서 한다. 지금의 Hermes 조회와 같은 자리다
- 응답을 캐시하지 않는다. 자식마다 한 번 읽는 값이다
- 401 을 「잠시 뒤 다시」 로 다루지 않는다. 옛 plugin 이 계속 401 을 답하는 동안 자식의 토큰이 합계에 들어가지 못한다
- 새 `unconfirmed_reason` 값을 만들지 않는다. 화면과 합계가 지금 값으로 완전성을 센다

## 작업 항목

### 1. `backend/src/main/java/com/bifos/assistant/hermes/dto/SubagentProviderLookup.java` 를 만든다

`public record SubagentProviderLookup(String provider, String model, boolean unavailable)` 이다. 정적 생성 메서드 셋을 둔다.

| 메서드 | 값 |
| --- | --- |
| `found(String provider, String model)` | `unavailable` 거짓 |
| `absent()` | 셋 다 비었고 `unavailable` 거짓 |
| `unreachable()` | `unavailable` 참. record 의 접근자 `unavailable()` 과 이름이 겹치지 않게 이 이름을 쓴다 |

### 2. `backend/src/main/java/com/bifos/assistant/hermes/SubagentProviderClient.java` 를 만든다

`@Component` 이고 `public SubagentProviderLookup read(String profile, String sessionId)` 하나를 둔다. 예외를 던지지 않는다.

| 상황 | 반환 |
| --- | --- |
| `HermesProfileName.isValid(profile)` 이 거짓이거나 `sessionId` 가 비었다 | `absent()`. 요청을 보내지 않는다 |
| 200 이고 `provider` 가 비어 있지 않은 문자열이다 | `found(provider, model)`. `model` 은 문자열이 아니거나 비었으면 null |
| 200 이고 `provider` 가 없거나 null 이거나 비었다 | `absent()` |
| 404 | `absent()` |
| 그 밖의 4xx | `absent()` 와 `log.warn`. 로그에 토큰과 응답 본문을 싣지 않고 상태 코드와 profile 만 싣는다 |
| 5xx, 연결 실패, 시간 초과, 읽지 못한 본문 | `unreachable()` |

`sessionId` 는 URI 변수로 넘긴다(`.uri(baseUrl + "/api/profiles/{profile}/sessions/{sessionId}/provider", profile, sessionId)`).

### 3. `SubagentSessionUsage` 에 `withProvider(String provider)` 를 더한다

provider 만 바꾼 새 record 를 돌려준다.

### 4. `SubagentUsageReconciler` 를 고친다

- 생성자 둘에 `SubagentProviderClient providers` 를 `ProfileModelDefaultsClient defaults` 뒤에 더한다
- 상수 `PROVIDER_RETRY_WINDOW = Duration.ofMinutes(10)` 를 둔다
- `poll` 에서 Hermes 조회 뒤, 트랜잭션 전에 아래를 한다. `isFinalChild(job, usage)` 가 참이고 `usage.provider()` 가 null 이거나 비었을 때만 `providers.read(job.profileName(), job.childSessionId())` 를 부른다
- 조회 결과로 쓸 provider 를 정한다

  | 조회 결과 | 처리 |
  | --- | --- |
  | 부르지 않았다(session 응답에 provider 가 있다) | 지금과 같다 |
  | null 이거나 `absent` | provider 없이 적는다. `PROVIDER_UNKNOWN` |
  | `found` 이고 조회의 `model` 이 null 이거나 `usage.model()` 과 같다 | `usage.withProvider(provider)` 로 환산해 적는다 |
  | `found` 이고 조회의 `model` 이 `usage.model()` 과 다르다 | provider 없이 적는다. `PROVIDER_UNKNOWN` |
  | `unavailable` 이고 `usage.endedAt()` 에 10분을 더한 시각이 지금보다 뒤다 | 줄을 적지 않고 `current.retry(now)` 로 `WAITING` 에 둔다. 완료 사건도 만들지 않는다 |
  | `unavailable` 이고 10분이 지났다 | provider 없이 적는다. `PROVIDER_UNKNOWN` |

  `usage.endedAt()` 은 epoch 초다. 비교는 `now.getEpochSecond()` 와 한다
- 클래스 Javadoc 에 provider 를 대시보드에서 읽는다는 것과 ADR-063 을 한 문장으로 더한다

### 5. `backend/src/test/java/com/bifos/assistant/hermes/SubagentProviderClientTest.java` 를 만든다

`backend/src/test/java/com/bifos/assistant/hermes/HermesRuntimeReadTest.java` 처럼 `com.sun.net.httpserver.HttpServer` 를 `127.0.0.1` 의 빈 포트에 띄우고, `HermesProperties` 의 `dashboardBaseUrl` 을 그 주소로 준다.

| 검사 | 기대 |
| --- | --- |
| 200 `{"provider":"anthropic","model":"m"}` | `found`, 요청 경로가 `/api/profiles/dad/sessions/child-1/provider`, `Authorization` 이 `Bearer test-dashboard-token` |
| 200 `{"provider":null,"model":"m"}` | `absent` |
| 404 | `absent` |
| 401 | `absent` |
| 503 | `unavailable` 참 |
| 서버를 내린 뒤 부른다 | `unavailable` 참 |
| profile 이름이 `Bad Name` | `absent` 이고 서버가 요청을 받지 않았다 |

### 6. `backend/src/test/java/com/bifos/assistant/usage/application/SubagentUsageReconcilerTest.java` 를 고친다

`fixtures(...)` 가 `SubagentProviderClient` 를 `mock` 으로 만들어 생성자와 `Fixtures` record 에 넣는다.
기존 검사 `pollLeavesCostEmptyWhenProviderIsMissing` 는 조회가 `absent()` 일 때의 검사로 둔다. 아래를 더한다. 자식 session 은 기존 검사처럼 `stubFinalChild` 로 준다.

| 검사 | 입력 | 기대 |
| --- | --- | --- |
| 대시보드의 provider 로 환산한다 | session 에 provider 없음, 모델 `example-fast`, 조회 `found("openai-codex", "example-fast")` | `DONE`, `provider` 가 `openai-codex`, `estimatedCostMicros` 가 null 이 아니다, `unconfirmedReason` 이 null |
| session 에 provider 가 있으면 대시보드를 부르지 않는다 | session provider `openai-codex` | `verify(providers, never()).read(any(), any())` |
| 조회의 모델이 다르면 채우지 않는다 | 조회 `found("openai-codex", "other-model")` | `provider` null, `PROVIDER_UNKNOWN` |
| 부모의 provider 로 채우지 않는다 | 부모 `provider` 가 `openai-codex`, 조회 `absent()` | `provider` null, `PROVIDER_UNKNOWN` |
| 닿지 못했고 자식이 막 끝났다 | 조회 `unreachable()`, `endedAt` 이 지금보다 60초 앞 | `status` 가 `WAITING`, `attempts` 가 1 늘었다, 완료 사건을 저장하지 않았다 |
| 닿지 못했고 10분이 지났다 | 조회 `unreachable()`, `endedAt` 이 지금보다 601초 앞 | `DONE`, `PROVIDER_UNKNOWN`, 토큰이 적혔다 |
| 아직 끝나지 않은 자식은 대시보드를 부르지 않는다 | `endedAt` null | `verify(providers, never())` |

`SubagentUsageReconciler` 를 `new` 로 만드는 다른 검사가 있으면 생성자 인자를 함께 고친다. `git grep -n "new SubagentUsageReconciler" backend/src` 로 찾는다.

## 검증

```bash
# cwd: backend/
./gradlew test --tests '*SubagentProviderClientTest' --tests '*SubagentUsageReconcilerTest' --tests '*SubagentUsageJobTest'
./gradlew test
./gradlew checkstyleMain checkstyleTest
```

셋 다 성공이어야 한다. `./gradlew test` 는 구조 규칙(`ArchitectureRules`)을 함께 검사한다.
포맷(`spotlessApply`)은 이 phase 의 커밋에 섞지 않는다. 기능 커밋 뒤에 따로 커밋한다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/hermes/dto/SubagentProviderLookup.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/hermes/SubagentProviderClient.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/hermes/dto/SubagentSessionUsage.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/usage/application/SubagentUsageReconciler.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/hermes/SubagentProviderClientTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/usage/application/SubagentUsageReconcilerTest.java` | 수정 |
