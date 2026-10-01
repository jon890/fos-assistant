# Phase 04. 커넥터 호출을 사용자별로 제한한다

**Execution profile**: standard

## 목표

선택지 조회, 등록, 연결 확인을 사용자마다 동시 1개와 60초에 10회로 제한한다. 이 셋은 MCP 서버를 자식 프로세스로 띄우고, 연결이 없는 사용자도 임의 값으로 부를 수 있어 토큰이 유효한지 훑어 알아내는 데 쓰일 수 있다.

**범위 외**: 일반 에이전트 실행의 한도와 대시보드 plugin 의 전역 동시 4개는 바꾸지 않는다. 해제, 읽기, 카탈로그, 관리자 경로는 제한하지 않는다.

## 컨텍스트

- 대상 메서드는 `ConnectorConnectionService` 의 `options`, `register`, `check` 다(`backend/src/main/java/com/bifos/assistant/connector/application/ConnectorConnectionService.java`)
- 본보기는 `AgentDelegationService` 다. `Semaphore.tryAcquire` 로 기다리지 않고 거절하고 상태를 JVM 메모리에 둔다
- 설정 클래스의 본보기는 `backend/src/main/java/com/bifos/assistant/orchestration/application/DelegationProperties.java` 다. `AssistantApplication` 의 `@ConfigurationPropertiesScan` 이 찾는다. 구조 규칙 `CONFIGURATION_PROPERTIES_ARE_VALIDATED` 가 `@Validated` 를 요구한다. 새 클래스에는 `org.springframework.validation.annotation.Validated` 를 붙인다. 기준 파일(`backend/config/archunit/store/`)에 새 위반을 더하지 않는다
- 시각은 `Clock` 으로 받는다(`NO_DIRECT_INSTANT_NOW`). `ConnectorConnectionService` 는 이미 `Clock` 을 받는 생성자를 갖는다
- 오류 코드는 `backend/src/main/java/com/bifos/assistant/shared/error/ErrorCode.java` 에 있고 화면 문구는 `web/src/lib/connection.ts` 의 오류 문구 표에 있다
- 테스트와 e2e 와 브라우저 검사는 같은 사용자로 짧은 시간에 여러 번 부른다. 기본값 10회에 걸리지 않게 그 실행들의 설정을 올려야 한다

**근거 문서**: `docs/connectors.md` 의 「사용자별 호출 제한」, `docs/flow.md` 의 「커넥터 연결」, `docs/code-architecture.md` 의 「backend 패키지」

## 의도 메모

- 기다리게 하지 않는다. 줄을 세우면 요청 스레드와 DB 연결을 쥔 채로 쌓인다
- DB 나 외부 저장소에 두지 않는다. Control Plane 이 한 대이고, 재시작으로 횟수가 비워져도 잃는 것이 없다
- 거절한 요청은 횟수에 넣지 않는다. 넣으면 계속 누르는 사용자가 스스로 풀리지 않는다

## 작업 항목

### 1. `ConnectorProperties` (신규)

`backend/src/main/java/com/bifos/assistant/connector/application/ConnectorProperties.java`

- `@ConfigurationProperties(prefix = "assistant.connector") public record ConnectorProperties(int maxConcurrentCalls, int callsPerMinute)`
- 둘 다 1 이상이 아니면 `IllegalStateException` 으로 기동을 멈춘다. `DelegationProperties.requirePositive` 와 같은 문구 형식을 쓴다
- `backend/src/main/resources/application.yml` 의 `assistant:` 아래에 더한다

```yaml
  connector:
    # 한 사용자가 동시에 돌릴 수 있는 선택지 조회, 등록, 연결 확인 수
    max-concurrent-calls: 1
    # 한 사용자가 60초 동안 돌릴 수 있는 그 호출 수. 상태는 JVM 메모리에 둔다
    calls-per-minute: 10
```

### 2. `ConnectorCallLimiter` (신규)

`backend/src/main/java/com/bifos/assistant/connector/application/ConnectorCallLimiter.java`

- `@Component`. 생성자는 `ConnectorProperties` 와 `Clock` 을 받는다. `Clock` 빈이 없으면 `ConnectorConnectionService` 처럼 `Clock.systemUTC()` 를 쓰는 생성자와 `Clock` 을 받는 생성자를 따로 둔다
- `public <T> T call(Long userId, Supplier<T> action)`: 받아들이면 `action` 을 돌리고 끝나면(예외여도) 동시 자리를 돌려준다. 받아들이지 못하면 `action` 을 부르지 않고 `new ApiException(ErrorCode.CONNECTOR_RATE_LIMITED, "too many connector calls")` 를 던진다
- 상태는 `ConcurrentHashMap<Long, ...>` 에 사용자마다 둔다. 사용자 한 명의 상태는 지금 도는 수와 받아들인 호출의 시작 시각(`ArrayDeque<Instant>`)이다. 판정과 갱신은 그 사용자의 상태 객체를 잠그고 한다(`ConcurrentHashMap.compute` 안에서 하거나 상태 객체에 `synchronized`)
- 받아들이는 조건: 60초보다 오래된 시각을 버린 뒤 지금 도는 수가 `maxConcurrentCalls` 미만이고 남은 시각 수가 `callsPerMinute` 미만이다
- 도는 수가 0 이고 남은 시각이 없는 사용자의 상태는 맵에서 지운다. 맵이 끝없이 커지지 않게 한다
- 중첩 타입은 `private` 이다(`SERVICES_DO_NOT_EXPOSE_NESTED_TYPES`)

### 3. `ErrorCode.CONNECTOR_RATE_LIMITED`

- `CONNECTOR_OPERATION_FAILED` 아래에 `CONNECTOR_RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS)` 를 더하고 Javadoc 한 줄을 적는다. 같은 이름이 없는지 `git grep -n "RATE_LIMITED"` 로 확인한다

### 4. `ConnectorConnectionService` 가 제한을 지난다

- 생성자 둘에 `ConnectorCallLimiter` 를 더한다
- `options`, `register`, `check` 의 본문 전체를 `limiter.call(user.id(), () -> ...)` 로 감싼다. `register` 는 확인 도구 호출과 저장이 한 번의 호출이다. `check` 는 `@Transactional(noRollbackFor = ConnectorOperationFailure.class)` 이다. 제한에 걸린 `ApiException` 은 아무것도 쓰기 전에 나오므로 rollback 되어도 잃는 것이 없다. 감싸느라 `check` 가 자기 클래스의 다른 메서드를 부르게 만들지 않는다(`@Transactional` 이 프록시 밖 호출에서 걸리지 않는다). 메서드 안에서 람다로 감싼다
- `disconnect`, `read`, `catalog`, `confirmApplied`, `listForAdmin` 은 감싸지 않는다

### 5. 화면 문구

- `web/src/lib/connection.ts` 의 오류 문구 표에 `CONNECTOR_RATE_LIMITED: "요청이 많아요. 잠시 뒤 다시 해 주세요."` 를 더한다
- `web/src/lib/connection-route.ts` 가 모르는 오류 코드를 `CONNECTOR_OPERATION_FAILED` 로 바꾸는 자리를 읽고, `CONNECTOR_RATE_LIMITED` 와 HTTP 429 가 화면까지 그대로 가게 한다. 선택지 조회가 이 오류로 실패하면 지금의 조회 실패와 같이 입력을 비우고 문구를 보인다

### 6. 테스트와 검사 실행의 설정

- `backend/src/test/resources/application-test.yml` 의 `assistant:` 아래에 `connector.max-concurrent-calls: 1`, `connector.calls-per-minute: 1000` 을 둔다. 제한 자체는 아래 단위 검사가 본다
- e2e 는 `test/e2e/run.ts`, 브라우저 검사는 `test/browser/fixtures.ts` 가 backend 를 띄우며 `ASSISTANT_JWT_SECRET` 같은 환경 변수를 준다. 두 곳의 같은 자리에 `ASSISTANT_CONNECTOR_CALLS_PER_MINUTE: "1000"` 을 더한다

### 7. 이 phase 를 검증하는 테스트

- 새 파일 `backend/src/test/java/com/bifos/assistant/connector/ConnectorCallLimiterTest.java`. 시각은 고정 `Clock` 을 바꿔 가며 준다
  - `rejectsSecondConcurrentCallOfSameUser`: 첫 호출의 `action` 안에서 같은 사용자의 둘째 호출이 `CONNECTOR_RATE_LIMITED` 이고 둘째의 `action` 은 불리지 않는다. 다른 사용자는 받아들인다
  - `releasesSlotWhenActionThrows`: `action` 이 예외를 내도 다음 호출을 받는다
  - `rejectsEleventhCallWithinAMinute`: 한도 10 에서 열 번은 받고 열한 번째는 거절한다. 61초 뒤에는 다시 받는다
  - `rejectedCallsAreNotCounted`: 거절된 호출이 횟수에 들지 않는다
- 새 파일 `backend/src/test/java/com/bifos/assistant/connector/ConnectorPropertiesTest.java`: 0 이하 값이 `IllegalStateException` 이다. `DelegationPropertiesTest` 를 본보기로 쓴다
- `ConnectorConnectionServiceTest`: 생성자 인자를 맞추고, 한도 1회로 만든 limiter 에서 `options` 의 둘째 호출이 `CONNECTOR_RATE_LIMITED` 이고 `connector.call` 이 한 번만 불렸음을 본다. `disconnect` 는 한도를 넘어도 된다
- `ConnectorConnectionControllerTest`: 제한에 걸린 `POST /api/v1/connections/{id}/options/{fieldKey}` 가 429 와 `CONNECTOR_RATE_LIMITED` 를 돌려준다

## 검증

```bash
# cwd: backend/
./gradlew test --tests '*ConnectorCallLimiterTest' --tests '*ConnectorPropertiesTest' --tests '*ConnectorConnectionServiceTest' --tests '*ConnectorConnectionControllerTest'
./gradlew archTest
./gradlew test
```

```bash
# cwd: web/
pnpm typecheck
```

```bash
# cwd: 저장소 root
node test/e2e/run.ts
grep -n "CONNECTOR_RATE_LIMITED" web/src/lib/connection.ts backend/src/main/java/com/bifos/assistant/shared/error/ErrorCode.java
scripts/check-public-safe.sh
```

모두 종료 코드 0 이어야 한다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/connector/application/ConnectorProperties.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/connector/application/ConnectorCallLimiter.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/connector/application/ConnectorConnectionService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/shared/error/ErrorCode.java` | 수정 |
| `backend/src/main/resources/application.yml` | 수정 |
| `backend/src/test/resources/application-test.yml` | 수정 |
| `backend/src/test/java/com/bifos/assistant/connector/ConnectorCallLimiterTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/connector/ConnectorPropertiesTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/connector/ConnectorConnectionServiceTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/connector/ConnectorConnectionControllerTest.java` | 수정 |
| `web/src/lib/connection.ts` | 수정 |
| `web/src/lib/connection-route.ts` | 수정 |
| `test/e2e/run.ts` | 수정 |
| `test/browser/fixtures.ts` | 수정 |
