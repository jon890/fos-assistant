# Phase 04. Control Plane 이 껐다 켜기를 스스로 진행하고 실패를 표시한다

**Execution profile**: standard

## 목표

설치 답으로 껐다 켜기 단계를 정하고, 30초 예약 작업이 주인이 바쁘지 않을 때(최대 10분 기다림) 끄고 `apply-delay` 뒤 켜서 반영 예정 확인으로 `READY` 를 만든다. 자동 경로가 실패하면 `apply_failed_at` 을 적고 되풀이하지 않는다.

**범위 외**: 재시작 감지(PR2). 24시간 알림, 실패 알림 문구, 연결 응답과 화면(PR3).

## 컨텍스트

**요구사항**: REQ1, REQ10

**근거 문서**: `docs/features/connector/design.md` 의 「Control Plane: 바인딩 칸과 단계」, 「Control Plane: 실패 표시(PR1)」, 「Control Plane: 관리자 다시 확인」, 「Control Plane: 예약 작업」, 「바쁜가」, `docs/features/connector.md` 의 「설치와 실패 처리」

- 설치 시작은 `ConnectorInstallAttempts.begin(binding, manifest, checkInstallation, afterRestart, replaceValues, now)` 다. 부르는 곳: `ConnectorBindingService` 의 붙이기(`false,false,false`), 관리자 반영 완료 `confirmLocked`(`true,true,false`), 등록의 `reinstall`(`false,false,true`), `resync`(`true,afterRestart,false`). `resync` 는 연결 확인(`ConnectorConnectionService`)과 `ConnectorBindingApplier` 의 반영 예정 확인, 정의 어긋남 점검이 부른다.
- 결과 적용은 `ConnectorBindingInstalls.record(binding, attempt, result)` 와 private `record(binding, installed, now)` 다. 외부 호출과 판정은 `ConnectorBindingInstalls.execute(attempt)` 다.
- `ConnectorBindingService.java` 는 499줄(상한 500)이다. **이 파일은 고치지 않는다.** 관리자 반영 완료의 껐다 켜기 처리는 `begin` 안에서 한다.
- 주인이 바쁜지는 `usage/execution/application/UserExecutionLimiter.used(Long userId)` 가 답한다. turn 자리, 원격 종료 확인 자리, 대화 turn 의 루트가 아닌 `RUNNING` 줄의 합이다. 바인딩이 붙은 에이전트는 `PRIVATE` 라 그 profile 의 실행은 모두 주인의 실행이다.
- 예약 작업의 잠금 순서와 트랜잭션 나누기는 `ConnectorBindingApplier.applyDue`/`applyDueLocked` 를 따른다. 대상은 트랜잭션 밖에서 번호만 읽는다(`DueBinding`).
- 정의 어긋남 점검의 관리자 알림은 `ConnectorBindingApplier.summarize` 의 `adminTodo` 가 정한다.
- 가짜 대시보드로 반영 예정 흐름을 테스트하는 본보기는 `backend/src/test/java/com/bifos/assistant/connector/ConnectorBindingServiceTest.java` 의 `applyDueMakesBindingReadyAfterDue` 다.

## 의도 메모

- 단계는 보낸 요청이 아니라 **답**으로 정한다. 끄기 결과가 시도 UUID 대조로 버려져도 다음 답이 단계를 맞춘다.
- 자동 경로의 실패는 한 번이면 실패다(스펙 DECISION10). 같은 실패에 설치를 되풀이해 보내지 않는다.
- 설계는 「바쁜가」 를 `usage.execution` 의 새 조회로 적었지만, `AgentExecutionRepository` 가 498줄이고 `used(userId)` 가 같은 값을 이미 낸다. 이 phase 에서 설계의 「바쁜가」 절과 「책임과 경계」 의 해당 줄을 `used` 로 고치고, `RUNNING` 줄도 profile 이 아니라 사용자 단위로 넓혔다는 까닭(기다림이 길어질 뿐 대화를 끊지 않는다, 붙인 에이전트는 `PRIVATE` 라 그 profile 의 실행은 주인의 것)을 함께 적는다.

## 작업 항목

### 1. `ConnectorBindingAttempt` 와 `begin`

- `ConnectorBindingAttempt` 에 `boolean automatic`, `CycleStep cycleStep`(신규 enum `NONE`, `OFF`, `ON`, `connector/connection/application/dto/CycleStep.java`)을 더한다.
- `ConnectorInstallAttempts` 에 `begin(..., boolean automatic, CycleStep step)` 을 더하고 기존 6인자 `begin` 은 `(false, NONE)` 으로 넘긴다.
- 단계가 있는 바인딩의 처리:
  - 자동 경로가 `NONE` 으로 부르면(반영 예정 확인, 정의 어긋남) 설치를 보내지 않고 `waiting = CYCLE_PENDING`
  - 연결 확인(`checkInstallation && !afterRestart && !automatic`)도 `waiting = CYCLE_PENDING`
  - 등록(`replaceValues`)은 단계가 `OFF` 면 `cycleStep = OFF`, `IDLE_WAIT` 면 `NONE`
  - 관리자 반영 완료(`afterRestart && !automatic`)는 실패 표시를 비우고 `IDLE_WAIT` 면 `OFF`, `OFF` 면 `ON`
- 자동이 아닌 설치(등록, 연결 확인, 반영 완료)가 실제로 설치를 보내면 `clearApplyFailed()`.

### 2. `ConnectorBindingInstalls`

- `sendAgain`: `cycleStep == OFF` 면 7인자 `bindConnector(..., guard, true)`, 아니면 지금 호출 그대로.
- `execute`: 답이 `cycleOff` 면 `CYCLE_PENDING` 으로 probe 없이 끝낸다. 답이 `restartRequired && cycleAvailable && !pluginUpdated && !attempt.restartRequired()` 이면(껐다 켜기가 필요하다) `RESTART_PENDING` 이 아니라 `CYCLE_PENDING` 으로 끝낸다. 그래야 정의 어긋남 점검의 `summarize` 가 관리자 알림을 만들지 않는다.
- `execute` 의 `catch (ConnectorSandboxUnavailable | ConnectorInstallConflict | ConnectorProfileRejected ex)` 가 다시 던지는 조건 `!attempt.checkInstallation() && !attempt.replaceValues()` 에 `&& !attempt.automatic()` 을 더한다. 자동 경로의 끄기와 켜기 설치가 409 나 401 을 받으면 `CALL_FAILED` 로 끝나 실패 표시가 적힌다. `cycleStep == ON` 이고 답에 재시작, 반영 예정, 끄기가 모두 없으면(켜기가 이미 끝남) `APPLY_SCHEDULED` 로 끝낸다.
- 결과 적용(private `record`)을 설계의 「plugin 의 답이 단계를 정한다」 표대로 바꾼다:
  - `cycleOff` → `cycledOff(now)`, `plugin_updated` 면 재시작도 OR, 반영 예정을 적지 않는다
  - `restartRequired && cycleAvailable && !pluginUpdated && !binding.restartRequired()` → `needCycle(now)`, 재시작 칸을 바꾸지 않고 반영 예정을 비운다
  - `plugin_updated` 이거나 그 밖의 재시작 → 지금처럼 재시작 대기, 단계가 `IDLE_WAIT` 면 `clearCycle()`
  - 단계가 `OFF` 였고 `reloadPending` 이거나 답에 아무 칸도 없으면 → `clearCycle()` 과 반영 예정
  - 그 밖은 지금 규칙
- `record(binding, attempt, result)`: `attempt.automatic()` 이고 결과가 실패 까닭(`PROBE_FAILED`, `NO_TOOLS`, `NOT_INSTALLED`, `POLICY_HOOK_OFF`, `CALL_FAILED`, `CATALOG_MISSING`)이면 `markApplyFailed(now)`.

### 3. `ConnectorBindingCycles` 신규(`connector/connection/application`)

- `int advance()`: `bindings.findCycling()` 의 번호마다 트랜잭션 밖에서 `limiter.used(userId) > 0` 을 본다. 트랜잭션에서 `applyDueLocked` 와 같은 차례로 잠그고 다시 읽어, 단계와 실패 표시가 그대로인지 본다. 주인이 바뀌었거나 에이전트가 지워졌으면 건너뛴다.
  - `IDLE_WAIT`: 바쁘지 않거나 `cycleAt + cycle-idle-max` 가 지났으면 `begin(binding, manifest, false, false, false, now, true, OFF)`
  - `OFF`: `cycleAt + apply-delay` 가 지났으면 `begin(binding, manifest, false, false, false, now, true, ON)`. `checkInstallation` 이 거짓이라 대기 분기와 probe 없이 설치만 보내고, 답의 `reload_pending` 이 반영 예정을 적는다
  - 카탈로그를 읽지 못하면 `pending` 과 `markApplyFailed` 를 커밋한다
  - 커밋한 설치를 `attempts.execute` 로 보낸다. 한 바인딩의 실패는 경고로 남기고 다음으로 간다
- `ConnectorBindingRepository.findCycling()`: 단계가 있고 실패 표시가 없는 바인딩의 `DueBinding`, 번호 순.
- `findApplyDue` 의 조건에 `b.cyclePhase is null` 을 더한다.
- `ConnectorBindingProperties` 에 `@DefaultValue("10m") Duration cycleIdleMax` 와 0 이하 검사를 더한다(스펙 DECISION18 의 10분).

### 4. `ConnectorBindingApplier`

- `runScheduled` 가 `applyDue()` 뒤 `cycles.advance()` 를 부른다.
- 반영 예정 확인과 정의 어긋남 점검이 `service.resync(...)` 대신 `attempts.begin(..., true, NONE)` 으로 자동 표시를 단다.
- 카탈로그를 읽지 못해 `pending` 으로 두는 두 자리와 정의 어긋남 연속 상한 자리에서 `markApplyFailed(now)` 를 함께 적는다.
- `summarize` 의 `adminTodo` 에서 `CYCLE_PENDING` 을 뺀다.

### 5. 문서

- `docs/features/connector.md` 의 「붙이기와 떼기」 의 대기 문장, 「바인딩의 반영 맞추기」, 「반영 예정 확인」, 「정의 어긋남 점검」, 「관리자 반영 완료」, 「재시작」 을 껐다 켜기와 실패 표시에 맞춘다. 같은 내용을 설계에서 복제하지 않고 설계 절을 링크한다.
- `docs/features/connector/design.md` 의 「바쁜가」 와 「책임과 경계」 를 의도 메모대로 고친다.

### 6. `backend/src/test/java/com/bifos/assistant/connector/ConnectorBindingCycleTest.java` 신규

`ConnectorBindingServiceTest` 의 준비(가짜 `HermesConnectorClient`, 바인딩 만들기)를 따른다. 시각은 `testsupport/TestClock`(`BackendIntegrationTest` 가 빈으로 둔다)을 앞으로 돌려 바꾼다. 바쁜 상태는 spy 빈인 `UserExecutionLimiter` 의 `used(userId)` 를 stub 한다.

- 다시 설치 답이 `restart_required` 와 `cycle_available` 이면 재시작 대기가 아니라 `IDLE_WAIT` 다
- 주인이 바쁘지 않으면 다음 `runScheduled` 가 7인자 끄기 설치를 보내고 `OFF` 다. `apply-delay` 전에는 켜지 않고, 지나면 5인자 설치를 보내 반영 예정을 적는다. 그 뒤 `applyDue` 가 probe 로 `READY` 를 만든다. 관리자 반영 완료는 부르지 않는다(REQ1)
- `used` 가 1 이상이면 끄지 않고, 10분이 지나면 끈다(REQ10)
- `OFF` 동안 등록(`reinstall`)은 끄기 설치로 가고 단계가 `OFF` 로 남는다
- 끄기 설치가 외부 호출 실패(`IllegalStateException`)나 409(`ConnectorInstallConflict`)로 끝나면 `apply_failed_at` 이 적히고 다음 주기는 설치를 보내지 않는다. 관리자 반영 완료가 실패 표시를 비우고 `OFF` 단계면 켜기 설치를 바로 보낸다
- 연결 확인은 단계 바인딩에 설치를 보내지 않는다
- `plugin_updated` 답은 지금처럼 재시작 대기이고 `IDLE_WAIT` 를 비운다
- 반영 예정 확인의 probe 실패(자동)는 `apply_failed_at` 을 적고, 연결 확인의 성공이 비운다
- 정의 어긋남 점검이 다시 설치해 `IDLE_WAIT` 가 된 바인딩에는 관리자 알림이 생기지 않는다
- 옛 plugin 처럼 `cycle_available` 이 없는 답(3인자 `InstallResult`)이면 지금처럼 재시작 대기다

## 검증

```bash
cd backend && ./gradlew test --tests '*ConnectorBindingCycleTest' --tests '*ConnectorBindingServiceTest' --tests '*ConnectorBindingDriftTest' --tests '*ConnectorBindingServiceLockTest' --tests '*ConnectorBindingGatewayOffTest' --tests '*ConnectorBindingPropertiesBindingTest' --tests '*ConnectorConnectionService*'
cd backend && ./gradlew archTest
cd backend && ./gradlew checkstyleMain checkstyleTest spotlessCheck
python3 -m unittest discover -s hermes/tests
node --test test/repo/doc-links.test.ts test/repo/doc-files.test.ts test/repo/doc-code-references.test.ts
node scripts/checks/check-file-length.mjs
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/connector/connection/application/dto/CycleStep.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/connector/connection/application/dto/ConnectorBindingAttempt.java` | 수정 (자동 표시, 단계 요청) |
| `backend/src/main/java/com/bifos/assistant/connector/connection/application/ConnectorInstallAttempts.java` | 수정 (단계 처리) |
| `backend/src/main/java/com/bifos/assistant/connector/connection/application/ConnectorBindingInstalls.java` | 수정 (답 적용, 실패 표시) |
| `backend/src/main/java/com/bifos/assistant/connector/connection/application/ConnectorBindingCycles.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/connector/connection/application/ConnectorBindingApplier.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/connector/connection/infra/ConnectorBindingRepository.java` | 수정 (`findCycling`, `findApplyDue`) |
| `backend/src/main/java/com/bifos/assistant/connector/connection/config/ConnectorBindingProperties.java` | 수정 (`cycleIdleMax`) |
| `docs/features/connector.md` | 수정 |
| `docs/features/connector/design.md` | 수정 (「바쁜가」) |
| `backend/src/test/java/com/bifos/assistant/connector/ConnectorBindingCycleTest.java` | 신규 |
