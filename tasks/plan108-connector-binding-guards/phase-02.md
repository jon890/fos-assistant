# Phase 02. Control Plane 이 single_binding 을 붙이기에서 판정하고 실행 공간 거절을 따로 옮긴다

**Execution profile**: standard

## 목표

카탈로그의 `single_binding` 을 읽어, 그 연결이 이미 다른 에이전트에 붙어 있으면 붙이기를 `CONNECTOR_SINGLE_BINDING`(409)으로 거절한다.
대시보드의 409 가운데 본문 `code` 가 `sandbox_unavailable` 인 것은 `AGENT_SANDBOX_UNAVAILABLE`(409)로 옮긴다.

**범위 외**: 대시보드 plugin(phase 01), 화면 문구(phase 03).

## 컨텍스트

**근거 문서**: `docs/adr/ADR-20261008-connector-binding-guards.md`, `docs/connectors.md`, `docs/backend/connector-install.md`

- 계약: `docs/connectors.md` 의 「붙이기와 떼기」 거절 표, `docs/backend/connector-install.md` 의 「붙이기」 4번과 「대시보드 plugin 계약」, `docs/flow.md` 의 붙이기 시퀀스
- 카탈로그 한 항목은 `backend/src/main/java/com/bifos/assistant/hermes/HttpHermesConnectorClient.java` 가 `new ConnectorManifest(...)` 로 읽는다. boolean 칸은 `optionalBoolean(item, "attachments", false)` 를 따른다. 칸이 있는데 boolean 이 아니면 카탈로그 읽기가 `IllegalStateException` 으로 실패한다. 이 동작을 그대로 쓴다
- 같은 파일의 `refusable` 은 대시보드의 409 를 모두 `ConnectorInstallConflict` 로 던진다. 본문 `code` 를 읽는 판정은 같은 패키지의 `HermesCallFailure.sandboxRejection(RestClientException)` 이 이미 갖는다. 그것을 그대로 쓴다
- `backend/src/main/java/com/bifos/assistant/connector/application/ConnectorBindingService.java` 의 `bind` 는 `lockUser` 로 사용자 행을 잠근 뒤 판정한다. 같은 사용자의 붙이기와 떼기가 이 잠금으로 줄을 선다. 바인딩 조회는 `ConnectorBindingRepository.findByConnectionId(Long)` 가 이미 있다
- 오류 코드는 `backend/src/main/java/com/bifos/assistant/shared/error/ErrorCode.java` 에 둔다. `AGENT_SANDBOX_UNAVAILABLE(HttpStatus.CONFLICT)` 는 이미 있다

### 파일 길이

`scripts/check-file-length.mjs` 는 `backend/src/main` 의 Java 파일을 500줄로 제한하고, 넘는 파일은 `scripts/file-length-baseline.json` 의 값까지만 둔다.

| 파일 | 지금 | 허용 |
| --- | --- | --- |
| `HttpHermesConnectorClient.java` | 611 | 614 |
| `ConnectorBindingService.java` | 594 | 612 |

`HttpHermesConnectorClient.java` 는 3줄만 늘릴 수 있다. 아래 작업 항목 2 는 2줄 안에서 끝나게 썼다. 기준 파일의 값은 올리지 않는다.

## 의도 메모

- `single_binding` 판정은 이미 같은 에이전트에 붙어 있으면 지금 상태를 돌려주는 기존 분기 뒤에 둔다. 같은 에이전트에 다시 붙이는 요청은 거절하지 않는다
- 대시보드는 다른 profile 의 바인딩을 보지 않는다. 바인딩 원장은 Control Plane 이다
- `ConnectorSandboxUnavailable` 은 `ConnectorInstallConflict` 를 상속하지 않는다. 상속하면 `bind` 의 기존 catch 가 먼저 잡는다
- 다시 설치하는 경로(`reinstall`, `resync`)는 `RuntimeException` 을 잡아 그 바인딩을 `PENDING` 으로 둔다. 새 예외도 그대로 잡히므로 고치지 않는다

## 작업 항목

### 1. `backend/src/main/java/com/bifos/assistant/hermes/dto/ConnectorManifest.java`

- 정식 생성자의 마지막 구성 요소로 `boolean singleBinding` 을 더하고 Javadoc `@param singleBinding 참이면 사용자의 그 연결은 에이전트 하나에만 붙는다(ADR-20261008 / connector-binding-guards). 옛 대시보드 plugin 은 내지 않고, 없으면 거짓` 을 적는다
- 지금의 12개 인자 생성자는 그대로 두되 새 정식 생성자에 `false` 로 넘기는 보조 생성자가 된다. 기존 11개 이하 인자 보조 생성자와 시험의 `new ConnectorManifest(...)` 호출은 바꾸지 않는다

### 2. `backend/src/main/java/com/bifos/assistant/hermes/HttpHermesConnectorClient.java`

- 카탈로그 항목을 읽는 `new ConnectorManifest(` 호출의 끝에 `optionalBoolean(item, "single_binding", false)` 를 더한다
- `refusable` 의 `throw new ConnectorInstallConflict();` 를 다음으로 바꾼다

```java
throw HermesCallFailure.sandboxRejection(ex).isPresent()
        ? new ConnectorSandboxUnavailable() : new ConnectorInstallConflict();
```

### 3. 새 파일 `backend/src/main/java/com/bifos/assistant/hermes/ConnectorSandboxUnavailable.java`

`ConnectorInstallConflict` 와 같은 모양의 `public class ConnectorSandboxUnavailable extends RuntimeException {}`. Javadoc 은 「대시보드가 바인딩 설치를 409 `sandbox_unavailable` 로 거절했다. 그 커넥터가 실행 공간을 요구하는데 profile 이 정책에 없거나, 사용자 첨부 디렉터리를 확인하지 못했다. 아무것도 바뀌지 않았다」 를 담는다.
`HermesConnectorClient.java` 의 `bindConnector` Javadoc 에 `@throws ConnectorSandboxUnavailable` 줄을 더하고, `@throws ConnectorInstallConflict` 의 설명에서 첨부 디렉터리 이야기를 뺀다.

### 4. `backend/src/main/java/com/bifos/assistant/shared/error/ErrorCode.java`

`CONNECTOR_BIND_CONFLICT` 아래에 더한다.

```java
/** 커넥터가 연결 하나를 에이전트 하나에만 붙이라고 선언했고 그 연결이 이미 다른 에이전트에 붙어 있다. 먼저 뗀다. */
CONNECTOR_SINGLE_BINDING(HttpStatus.CONFLICT),
```

### 5. `backend/src/main/java/com/bifos/assistant/connector/application/ConnectorBindingService.java`

- `bind` 에서 `ConnectorManifest declared = manifest.orElseThrow(ConnectorErrors::notFound);` 바로 뒤, `requireSkillNamesFree` 앞에 둔다

```java
if (declared.singleBinding() && !bindings.findByConnectionId(connection.id()).isEmpty()) {
    throw new ApiException(ErrorCode.CONNECTOR_SINGLE_BINDING, "this connection is attached to another agent");
}
```

- 설치 호출의 `catch (ConnectorInstallConflict ex)` 앞에 `catch (ConnectorSandboxUnavailable ex)` 를 두고 `new ApiException(ErrorCode.AGENT_SANDBOX_UNAVAILABLE, "the agent profile has no isolated workspace for this connector")` 를 던진다. 트랜잭션이 되돌려져 바인딩 행이 남지 않는 것은 기존 409 와 같다
- `bind` 의 Javadoc 에 두 거절을 한 줄씩 더한다

### 6. 시험 `backend/src/test/java/com/bifos/assistant/hermes/HttpHermesConnectorClientTest.java`

- `readsDeclaredToolsetsAndAttachments` 와 같은 방식으로: `"single_binding":true` 를 실은 카탈로그는 `singleBinding()` 이 참, 칸이 없으면 거짓, `"single_binding":"true"` 는 `IllegalStateException`
- 바인딩 설치 요청에 409 와 본문 `{"detail":"x","code":"sandbox_unavailable"}` 로 답하면 `bindConnector` 가 `ConnectorSandboxUnavailable` 을 던진다. 본문이 `{"detail":"x"}` 인 409 는 지금처럼 `ConnectorInstallConflict` 다

### 7. 시험 `backend/src/test/java/com/bifos/assistant/connector/ConnectorBindingServiceTest.java`

`DEMO_MANIFEST` 와 같은 모양에 `singleBinding` 만 참인 manifest 를 하나 더 두고 `readCatalog` 스텁에 넣는다.

- 같은 사용자의 비공개 에이전트 둘. 첫째에 붙인 뒤 둘째에 붙이면 `CONNECTOR_SINGLE_BINDING` 이고, `bindConnector` 는 한 번만 불렸고, 바인딩 행은 하나다
- 첫째에 다시 붙이면 거절하지 않고 지금 상태를 돌려준다
- 첫째에서 뗀 뒤에는 둘째에 붙는다
- `singleBinding` 이 거짓인 `DEMO_MANIFEST` 는 지금처럼 두 에이전트에 붙는다
- `bindConnector` 가 `ConnectorSandboxUnavailable` 을 던지면 `AGENT_SANDBOX_UNAVAILABLE` 이고 바인딩 행이 남지 않는다

## 검증

```bash
cd backend && ./gradlew test --tests 'com.bifos.assistant.hermes.HttpHermesConnectorClientTest' --tests 'com.bifos.assistant.connector.ConnectorBindingServiceTest' --tests 'com.bifos.assistant.connector.ConnectorBindingServiceLockTest'
node scripts/check-file-length.mjs
```

시험이 모두 통과하고, 파일 길이 검사가 위반 없이 끝난다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/hermes/dto/ConnectorManifest.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/hermes/HttpHermesConnectorClient.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/hermes/HermesConnectorClient.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/hermes/ConnectorSandboxUnavailable.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/shared/error/ErrorCode.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/connector/application/ConnectorBindingService.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/hermes/HttpHermesConnectorClientTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/connector/ConnectorBindingServiceTest.java` | 수정 |
