# Phase 02. 반영 맞추기의 결과를 까닭 하나로 돌려주고, 관리자 반영 완료를 까닭별 오류로 끝낸다

**Execution profile**: standard

## 목표

`ConnectorBindingService.resync` 가 boolean 대신 까닭(`ResyncOutcome`)을 돌려주고, 외부 호출 실패가 아닌 `PENDING` 에 커넥터 id 와 까닭 이름만 담은 로그 한 줄을 남긴다.
관리자 반영 완료(`confirmApplied`)가 `READY` 를 확인하지 못하면 외부 호출 실패만 502 `CONNECTOR_OPERATION_FAILED` 이고 나머지는 까닭별 오류 코드로 끝난다.
관리자 화면은 그 코드의 문구를 보인다.

**범위 외**: 대시보드 판정(phase 01), 정의 어긋남 점검과 알림(phase 03). 사용자 연결 확인(`ConnectorConnectionService.check`)의 응답은 바꾸지 않는다.

## 컨텍스트

**근거 문서**: `docs/adr/ADR-20261009-connector-install-drift.md`, `docs/backend/connector-install.md` 의 「바인딩의 반영 맞추기」 의 까닭 표와 「관리자 반영 완료」 의 오류 표, `docs/connectors.md` 의 「관리자 반영 완료」

- `backend/src/main/java/com/bifos/assistant/connector/application/ConnectorBindingService.java`
  - `public boolean resync(ConnectorBinding binding, Optional<ConnectorManifest> manifest, boolean afterRestart)` 가 `PENDING` 으로 돌아가는 자리: 재시작 대기(`!afterRestart && binding.restartRequired()`), 반영 예정 전(`dueLater`), 카탈로그 없음(`manifest.isEmpty()`), 옛 설치가 꺼짐, `installs.record(...)` 가 참(재시작이나 반영 예정), `ConnectorBindingInstalls.installedHere(...)` 가 거짓, probe 결과(`usable` 거짓), 예외(`catch (RuntimeException ex)`)
  - `confirmApplied` 가 `transactions.execute(status -> confirmLocked(...))` 의 `Confirmed(view)` 가 null 이면 `new ConnectorOperationFailure()` 를 던진다. `confirmLocked` 는 `boolean failed = resync(binding, manifest, true)` 와 `binding.status() != BindingStatus.READY` 를 본다
  - 로그는 `private static void warn(String step, String connectorId, RuntimeException ex)` 의 모양(`"connector {} failed at {}: {}"`)을 따른다
- `ConnectorBindingInstalls.installedHere(ConnectorState state, boolean legacy)` 는 `state.configured() && state.policyHook()` 에 바인딩이면 `state.enabled()` 와 `MODE_BIND` 를 더 본다. `ConnectorState` 는 `HermesConnectorClient` 안의 record 다(`profile, enabled, configured, restartRequired, policyHook, mode`)
- 다른 호출부: `ConnectorConnectionService` 의 `failed |= bindingService.resync(binding, manifest, false);` 와 `ConnectorBindingApplier.applyDueLocked` 의 `service.resync(binding, manifest, false);`
- 오류 코드는 `backend/src/main/java/com/bifos/assistant/shared/error/ErrorCode.java` 에 있다. `CONNECTOR_RESTART_AGAIN(HttpStatus.CONFLICT)` 가 본보기다. 고정 오류는 `ConnectorErrors`(같은 패키지)가 만든다
- 화면: `web/src/lib/connection.ts` 의 `CONNECTION_ERROR_MESSAGES`, `web/src/lib/connection-route.ts` 의 `connectionResponse`(표에 없는 코드는 `CONNECTOR_OPERATION_FAILED` 로 바꾼다), `web/src/components/connector/connector-admin-panel.tsx` 의 `confirm` 이 `CONNECTOR_RESTART_AGAIN` 일 때만 `result.message` 를 쓴다
- 저장하지 않는 서비스 결과 enum 은 `<기능>.application.model` 에 둔다(`backend/AGENTS.md` 「enum 은 저장 여부로 둘 곳을 정한다」)

## 의도 메모

- 사용자 연결 확인은 지금처럼 200 과 `PENDING` 을 낸다. 까닭은 로그로만 남긴다. 화면 계약을 바꾸지 않는다
- 로그에 칸 값, 예외 메시지, profile 이름을 넣지 않는다. 커넥터 id 와 까닭 이름만 둔다

## 작업 항목

### 1. `backend/src/main/java/com/bifos/assistant/connector/application/model/ResyncOutcome.java` 신규

enum 값: `READY`, `RESTART_PENDING`, `APPLY_SCHEDULED`, `CATALOG_MISSING`, `NOT_INSTALLED`, `POLICY_HOOK_OFF`, `PROBE_FAILED`, `NO_TOOLS`, `TOOLSETS_DIFFER`, `CALL_FAILED`. 각 값의 Javadoc 은 `docs/backend/connector-install.md` 의 까닭 표와 같게 쓴다.

### 2. `ConnectorBindingInstalls`

구현 중 정한 것: 까닭을 더하면 `ConnectorBindingService.java` 가 파일 길이 점검의 상한을 넘어, 설치 확인과 probe 본체를 `ConnectorBindingInstalls.resync(binding, declared, now)` 로 옮기고 `installedHere` 를 지웠다. 이동만 하는 커밋을 먼저 두고 까닭으로 바꾸는 커밋을 뒤에 둔다.


`static ResyncOutcome notInstalledReason(ConnectorState state, boolean legacy)` 를 더한다. 반영됐으면 `READY`, `policyHook` 만 거짓이면 `POLICY_HOOK_OFF`, 그 밖은 `NOT_INSTALLED` 다. `installedHere` 는 `notInstalledReason(...) == ResyncOutcome.READY` 로 둔다.

### 3. `ConnectorBindingService`

- `resync` 의 반환형을 `ResyncOutcome` 으로 바꾼다. 위 자리마다 까닭을 정한다. `installs.record(...)` 가 참이면 `binding.restartRequired()` 로 `RESTART_PENDING` 과 `APPLY_SCHEDULED` 를 나눈다. 옛 설치가 꺼진 자리(`!binding.desiredEnabled()` 이거나 `readConnector(...).enabled()` 가 거짓)는 `NOT_INSTALLED` 다. probe 는 `!probe.ok()` 면 `PROBE_FAILED`, 도구가 비면 `NO_TOOLS`, 옛 바인딩의 toolset 이 다르면 `TOOLSETS_DIFFER` 다
- `READY` 와 `CALL_FAILED` 가 아닌 결과는 `log.info("connector {} not applied: {}", connectorId, outcome)` 한 줄을 남긴다. `CALL_FAILED` 는 지금의 `warn` 이 남긴다
- `Confirmed` record 에 `ResyncOutcome outcome` 을 더한다. `confirmApplied` 는 `confirmed == null` 이면 지금처럼 `new ConnectorOperationFailure()` 를, view 가 null 이면 `ConnectorErrors.notApplied(confirmed.outcome())` 을 던진다
- `ConnectorErrors.notApplied(ResyncOutcome)`: `CALL_FAILED` → `new ConnectorOperationFailure()`, `NOT_INSTALLED`/`POLICY_HOOK_OFF` → `CONNECTOR_INSTALL_MISMATCH`, `PROBE_FAILED`/`NO_TOOLS`/`TOOLSETS_DIFFER` → `CONNECTOR_TOOLS_UNVERIFIED`, `APPLY_SCHEDULED` → `CONNECTOR_APPLY_SCHEDULED`, `RESTART_PENDING` → `CONNECTOR_RESTART_AGAIN`, `CATALOG_MISSING` → `notFound()`. `READY` 는 여기 오지 않으므로 `IllegalStateException` 이다. 메시지는 고정 영문이다
- 호출부를 고친다: `ConnectorConnectionService` 는 `bindingService.resync(...) == ResyncOutcome.CALL_FAILED`, `ConnectorBindingApplier` 는 반환값을 쓰지 않는 그대로다

### 4. `ErrorCode`

`CONNECTOR_RESTART_AGAIN` 뒤에 `CONNECTOR_INSTALL_MISMATCH`, `CONNECTOR_TOOLS_UNVERIFIED`, `CONNECTOR_APPLY_SCHEDULED` 를 모두 `HttpStatus.CONFLICT` 로 더하고 각각 Javadoc 한 줄을 단다.

### 5. 화면

- `web/src/lib/connection.ts` 의 `CONNECTION_ERROR_MESSAGES` 에 더한다
  - `CONNECTOR_INSTALL_MISMATCH`: 「설치 상태가 맞지 않아요. 서버 로그에서 까닭을 확인해 주세요.」
  - `CONNECTOR_TOOLS_UNVERIFIED`: 「도구를 확인하지 못했어요. 연결 값과 서비스 상태를 확인해 주세요.」
  - `CONNECTOR_APPLY_SCHEDULED`: 「아직 반영 중이에요. 몇 분 뒤 다시 눌러 주세요.」
- `connector-admin-panel.tsx` 의 `confirm` 은 `CONNECTOR_OPERATION_FAILED` 일 때만 지금의 「반영 상태를 확인하지 못했어요. 잠시 뒤 다시 해 주세요.」 를 쓰고, 나머지 코드는 `result.message` 를 쓴다

### 6. 시험

- `backend/src/test/java/com/bifos/assistant/connector/ConnectorBindingServiceTest.java`
  - `ConnectorOperationFailure` 를 기대하던 반영 완료 시험을 새 코드로 고친다. `ApiException` 의 `code()` 로 본다. `confirmAppliedBeforeApplyDueStaysPending` 은 `CONNECTOR_APPLY_SCHEDULED`, `confirmAppliedStaysPendingWhenReinstallNeedsRestartAgain` 은 `CONNECTOR_RESTART_AGAIN`, `confirmAppliedKeepsRestartWaitWhenNotConfiguredOrProbeFails` 는 configured 거짓이면 `CONNECTOR_INSTALL_MISMATCH`, probe 실패면 `CONNECTOR_TOOLS_UNVERIFIED` 다
  - 새 시험: 다시 읽은 설치의 `policyHook` 이 거짓이면 `CONNECTOR_INSTALL_MISMATCH`, probe 가 도구를 내지 않으면 `CONNECTOR_TOOLS_UNVERIFIED`, 설치 다시 보내기가 예외면 `CONNECTOR_OPERATION_FAILED` 이고 모두 바인딩이 `PENDING` 으로 커밋되는지. 위에서 고친 시험과 겹치지 않게 한다
  - `resync` 가 `PENDING` 일 때 `connector demo not applied: POLICY_HOOK_OFF` 로그를 남기는지(`OutputCaptureExtension` 이나 이 파일이 이미 쓰는 로그 확인 방식)
- `backend/src/test/java/com/bifos/assistant/connector/ConnectorPolicyEndpointTest.java` 새 시험: 연결은 `READY` 이고 바인딩이 반영 대기(`PENDING`)일 때 `approval: always` 인 `purge_notes` 호출이 `NOT_READY` 로 막히는지. ADR 의 「보안 경계」 가 이 시험을 근거로 든다
- `ConnectorConnectionControllerTest`, `ConnectorBindingServiceLockTest` 에 반영 완료가 502 를 기대하는 자리가 있으면 새 코드로 고친다
- `test/unit/connection-error-message.test.ts` 에 세 코드의 문구 시험을 더한다
- `test/browser/connector-connection.spec.ts` 의 관리자 반영 완료 시험 곁에, confirm 이 `CONNECTOR_INSTALL_MISMATCH` 로 답하면 그 문구가 보이는 시험을 더한다

## 검증

```bash
(cd backend && ./gradlew test --tests 'com.bifos.assistant.connector.*')
(cd backend && ./gradlew qualityCheck)
node --test test/unit/connection-error-message.test.ts
pnpm --dir web typecheck
scripts/check-local.sh connector-connection admin-area
```

모두 종료 코드 0 이어야 한다. 여러 작업이 같은 머신에서 돌면 마지막 줄은 그 머신의 로컬 검사 잠금으로 감싸 돌린다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/connector/application/model/ResyncOutcome.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/connector/application/ConnectorBindingInstalls.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/connector/application/ConnectorBindingService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/connector/application/ConnectorConnectionService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/connector/application/ConnectorErrors.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/shared/error/ErrorCode.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/connector/ConnectorBindingServiceTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/connector/ConnectorPolicyEndpointTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/connector/ConnectorConnectionControllerTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/connector/ConnectorBindingServiceLockTest.java` | 수정 |
| `web/src/lib/connection.ts` | 수정 |
| `web/src/components/connector/connector-admin-panel.tsx` | 수정 |
| `test/unit/connection-error-message.test.ts` | 수정 |
| `test/browser/connector-connection.spec.ts` | 수정 |
