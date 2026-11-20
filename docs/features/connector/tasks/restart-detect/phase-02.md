# Phase 02. Control Plane 의 재시작 감지

**Execution profile**: standard

## 목표

30초 주기가 재시작 대기 바인딩이 있을 때만 gateway 기동 시각을 읽고, 그 시각보다 앞서 대기가 된 바인딩을 사람 없이 반영 맞춘다.
기동 시각을 모르면 아무것도 바꾸지 않고, 읽히던 것이 못 읽히게 된 때에만 경고 한 줄을 남긴다.

**범위 외**: 관리자 다시 확인의 재시작 확인(phase 03), 운영 조회와 화면(PR3).

## 컨텍스트

**요구사항**: REQ3, REQ4

**근거 문서**: `docs/features/connector/design.md` 의 「Control Plane: 예약 작업」 3번, 「실패와 일관성」, 설계 DECISION3, DECISION4

- 예약 주기는 `ConnectorBindingApplier.runScheduled()` 가 `applyDue()` 다음 `cycles.advance()` 를 부른다. 같은 꼴로 새 컴포넌트를 셋째로 부른다. 본보기는 `ConnectorBindingCycles` 다(대상 번호를 트랜잭션 밖에서 읽고, 바인딩마다 연결 사용자 행, 에이전트 행 차례로 잠근 뒤 다시 읽고, 외부 호출은 잠금 밖).
- `restart_required_since` 는 지금 `ConnectorBindingInstalls.record(binding, attempt, result)` 의 `now`(시작 트랜잭션 시각)로 적힌다. 설계는 결과 트랜잭션 시각으로 바꾼다. 결과 트랜잭션은 plugin 이 파일을 다 쓰고 답한 뒤라 그 시각보다 늦게 뜬 gateway 는 새 파일을 읽었다.
- 대시보드 호출 포트는 `hermes.application.port` 에 있다. `HttpHermesConnectorClient` 는 488줄로 rule-44 상한(500)에 닿아 새 포트와 구현 클래스를 따로 둔다. 본보기는 `HermesEventFeedClient` 와 `HttpHermesEventFeedClient` 다.
- 통합 테스트의 대시보드 대역은 `testsupport/BackendIntegrationTest.java` 의 `@MockitoBean` 목록이다. 새 포트를 더한다. Mockito 의 기본값은 `Optional.empty()` 라 다른 테스트는 기동 시각을 모르는 상태로 돈다.

## 의도 메모

- 기동 시각과 비교하는 기준은 `restart_required_since < started_at - 2초` 다. 2초는 `btime` 과 tick 의 정밀도를 덮는 여유다. Control Plane 이 마지막으로 본 기동 시각을 저장하지 않는다(설계 DECISION4).
- 카탈로그를 읽지 못한 주기는 실패 표시를 남기지 않고 건너뛴다. 재시작 대기는 다음 주기에도 그대로 남고, 일시 장애에 관리자 조작을 요구하지 않기 위해서다.
- 로그 상태의 처음 값은 「읽힌다」 다. 처음 실패도 경고 한 줄을 남긴다(스펙 REQ4 인수 조건).

## 작업 항목

### 1. 포트와 HTTP 구현

- `hermes/application/port/HermesGatewayClient.java`(신규): `Optional<Instant> startedAt()`. 예외를 던지지 않는다.
- `hermes/infra/HttpHermesGatewayClient.java`(신규): 대시보드 `GET /api/gateway` 를 서비스 토큰으로 부른다. `started_at` 이 0 보다 큰 정수면 그 초, `null` 이거나 모양이 틀리거나 404 를 포함한 실패면 비어 있다. 응답 원문과 예외 메시지는 로그에 남기지 않는다.
- `src/test/java/com/bifos/assistant/hermes/HttpHermesGatewayClientTest.java`(신규): `MockRestServiceServer` 로 정상, `null`, 404, 모양 틀림, 토큰 헤더를 본다. 본보기는 `HttpHermesConnectorClientTest` 의 `setUp`.

### 2. 재시작 대기 시각을 결과 트랜잭션 시각으로

- `ConnectorBinding.installed(boolean restartRequired, Instant now, Instant restartSince)` 를 더하고 2인자 판은 `installed(r, now, now)` 로 둔다.
- `ConnectorBindingInstalls` 에 `Clock` 을 주입하고, 결과를 적을 때 `restartSince = max(now, clock.instant())` 를 쓴다.
- `ConnectorBindingRepository.findRestartWaiting()`(신규): `restartRequired = true and applyFailedAt is null and cyclePhase is null and applyDueAt is null` 인 `DueBinding` 을 번호 순으로.

### 3. `connector/connection/application/ConnectorGatewayRestarts.java`(신규)

- `detect()`: 대상이 없으면 HTTP 없이 0. 있으면 `startedAt()` 을 한 번 부른다. 비었으면 상태가 바뀔 때만 `log.warn` 한 줄을 남기고 끝낸다. 다시 읽히면 `log.info` 한 줄.
- 기동 시각이 있으면 카탈로그를 읽고, 바인딩마다 트랜잭션에서 잠근 뒤 다시 읽어 조건(재시작 대기, 실패 표시 없음, 단계 없음, 예정 없음, `restartRequiredSince` 가 기준보다 앞섬)을 다시 본다. 맞으면 `attempts.begin(binding, manifest, true, true, false, now, true, CycleStep.NONE)` 으로 시작을 커밋하고 잠금 밖에서 `attempts.execute` 한다. 에이전트가 지워졌거나 주인이 다르면 건너뛴다. 한 바인딩의 실패는 경고로 남기고 다음으로 간다.
- `ConnectorBindingApplier.runScheduled()` 끝에서 부른다.

### 4. 이 phase 를 검증하는 `src/test/java/com/bifos/assistant/connector/ConnectorGatewayRestartTest.java`(신규)

`ConnectorBindingCycleTest` 처럼 `@BackendIntegrationTest`, `TestClock`, 대시보드 대역을 쓴다. `plugin_updated` 답으로 재시작 대기를 만든다.

- 기동 시각이 대기 시각보다 2초 넘게 늦으면 `READY` 가 되고, 같은 주기에 기동 시각 뒤에 대기가 된 바인딩은 대기로 남는다
- 재시작 대기 시각이 결과 트랜잭션 시각이다(시작과 결과 사이에 시계를 앞으로 돌린 대역 설치로 확인)
- 기동 시각이 비면 바인딩이 대기로 남고 경고가 한 줄, 다음 주기도 비면 더 남지 않고, 다시 읽히면 info 가 한 줄(로그는 `OutputCaptureExtension` 이나 Logback 대역으로 센다)
- 재시작 대기 바인딩이 없으면 `startedAt()` 을 부르지 않는다
- 실패 표시가 있는 바인딩과 껐다 켜기 단계 바인딩은 집지 않는다
- 다시 보낸 설치가 probe 실패면 실패 표시가 남는다

### 5. 문서

- `docs/features/connector.md` 에 「재시작 감지」 절을 「껐다 켜기」 뒤에 짧게 더하고, 「관리자 반영 완료」 첫 줄을 재시작 대기의 비상 수단으로 고친다.
- `backend/docs/data-schema.md` 의 `restart_required_since` 설명을 결과 트랜잭션 시각과 재시작 감지의 기준으로 고친다.

## 검증

```bash
cd backend && ./gradlew test --tests '*ConnectorGatewayRestartTest' --tests '*HttpHermesGatewayClientTest' --tests '*ConnectorBindingCycleTest' --tests '*ConnectorBindingServiceTest' --tests '*ConnectorBindingTest'
cd backend && ./gradlew checkstyleMain checkstyleTest spotlessCheck archTest
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/hermes/application/port/HermesGatewayClient.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/hermes/infra/HttpHermesGatewayClient.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/connector/connection/application/ConnectorGatewayRestarts.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/connector/connection/application/ConnectorBindingApplier.java` | 수정 (주기에 감지 추가) |
| `backend/src/main/java/com/bifos/assistant/connector/connection/application/ConnectorBindingInstalls.java` | 수정 (결과 시각) |
| `backend/src/main/java/com/bifos/assistant/connector/connection/domain/ConnectorBinding.java` | 수정 (3인자 `installed`) |
| `backend/src/main/java/com/bifos/assistant/connector/connection/infra/ConnectorBindingRepository.java` | 수정 (대상 조회) |
| `backend/src/test/java/com/bifos/assistant/testsupport/BackendIntegrationTest.java` | 수정 (대역 추가) |
| `backend/src/test/java/com/bifos/assistant/hermes/HttpHermesGatewayClientTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/connector/ConnectorGatewayRestartTest.java` | 신규 |
| `docs/features/connector.md` | 수정 |
| `backend/docs/data-schema.md` | 수정 |
