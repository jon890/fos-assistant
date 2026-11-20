# Phase 03. 재시작 대기 줄의 관리자 다시 확인이 재시작을 확인한다

**Execution profile**: standard

## 목표

관리자 「반영 완료」(`confirmApplied`)가 재시작 대기 바인딩에서 그 대기 뒤에 gateway 가 다시 떴는지 기동 시각으로 확인하고, 아니면 `CONNECTOR_RESTART_NOT_DONE` 으로 거절한다.
기동 시각을 읽지 못하면 지금처럼 진행한다(스펙 DECISION19, 설계 DECISION8).

**범위 외**: 단추 이름 「다시 확인」 과 보이는 조건, 패널 설명(PR3).

## 컨텍스트

**요구사항**: REQ7

**근거 문서**: `docs/features/connector/design.md` 의 「Control Plane: 관리자 다시 확인」

- `ConnectorBindingService.confirmApplied` 가 주인과 에이전트 번호를 트랜잭션 밖에서 읽고 `confirmLocked` 에서 잠근다. `restartRequiredSince` 와 본문 `shownSince` 대조(`CONNECTOR_RESTART_AGAIN`)는 그대로 둔다.
- 「재시작 대기 줄」 은 `restartRequired` 이고 `cyclePhase` 가 비었으며 `restartRequiredSince` 가 있는 바인딩이다. 껐다 켜기 단계는 지금 동작(다음 설치를 바로 보냄)을 그대로 따른다.
- `ConnectorBindingService.java` 는 499줄이다. rule-44 상한(500) 안에 두려고 판정은 `ConnectorGatewayRestarts` 에 두고 서비스에는 호출만 둔다. 한 번만 쓰는 `lockUser` 감싸기를 없애 줄을 맞춘다.
- 웹의 오류 문구는 `web/src/lib/connection.ts` 의 `CONNECTION_ERROR_MESSAGES` 가 오류 코드마다 갖는다.

## 의도 메모

- 기동 시각은 잠그기 전에 읽는다. 사용자와 에이전트 행 잠금을 쥔 채 대시보드를 부르지 않는다.
- 비교는 재시작 감지와 같은 `restartRequiredSince < startedAt - 2초` 다. 같은 함수를 쓴다.

## 작업 항목

### 1. `shared/error/ErrorCode.java`

`CONNECTOR_RESTART_NOT_DONE(HttpStatus.CONFLICT)` 를 `CONNECTOR_RESTART_AGAIN` 옆에 더한다. 주석은 「재시작 대기 뒤에 공유 gateway 가 아직 다시 뜨지 않았다」.

### 2. `ConnectorGatewayRestarts` 의 판정

- `static boolean restartedAfter(Instant since, Instant startedAt)`: 재시작 감지와 다시 확인이 함께 쓴다.
- `void requireRestarted(ConnectorBinding binding, Optional<Instant> startedAt)`: 재시작 대기 줄이고 기동 시각이 있으며 `restartedAfter` 가 거짓이면 `ApiException(CONNECTOR_RESTART_NOT_DONE, ...)`.

### 3. `ConnectorBindingService`

`confirmApplied` 에서 `attempts.start` 전에 `restarts.startedAt()` 을 읽어 `confirmLocked` 에 넘기고, `CONNECTOR_RESTART_AGAIN` 검사 뒤 `restarts.requireRestarted(binding, startedAt)` 을 부른다. 거절하면 아무것도 바꾸지 않는다(시작 트랜잭션이 되돌려진다). 클래스와 메서드 설명의 「재시작한 뒤 누르는 반영 완료」 를 자동 반영이 실패했을 때의 다시 확인으로 고친다.

### 4. `web/src/lib/connection.ts`

`CONNECTOR_RESTART_NOT_DONE: "아직 공유 gateway 가 재시작되지 않았어요."` 를 더한다.

### 5. 이 phase 를 검증하는 `ConnectorGatewayRestartTest` 의 다시 확인 사례

- 기동 시각이 대기 시각보다 앞서면(또는 2초 안이면) `CONNECTOR_RESTART_NOT_DONE` 이고 바인딩과 설치 호출이 그대로다
- 기동 시각이 대기 뒤면 지금처럼 설치를 다시 보내 `READY` 가 된다
- 기동 시각을 모르면 지금처럼 진행해 `READY` 가 된다
- 재시작 대기가 아닌 `PENDING`(실패 표시 남은 반영 예정 실패)은 기동 시각과 상관없이 지금처럼 다시 맞춘다

### 6. 문서

`docs/features/connector.md` 「관리자 반영 완료」 의 표에 `CONNECTOR_RESTART_NOT_DONE` 줄을 더한다.

## 검증

```bash
cd backend && ./gradlew test --tests '*ConnectorGatewayRestartTest' --tests '*ConnectorBindingServiceTest' --tests '*ConnectorBindingCycleTest' --tests '*AdminAgentConnection*'
cd backend && ./gradlew checkstyleMain checkstyleTest spotlessCheck archTest
node scripts/checks/check-file-length.mjs
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/shared/error/ErrorCode.java` | 수정 (오류 코드) |
| `backend/src/main/java/com/bifos/assistant/connector/connection/application/ConnectorGatewayRestarts.java` | 수정 (판정) |
| `backend/src/main/java/com/bifos/assistant/connector/connection/application/ConnectorBindingService.java` | 수정 (다시 확인) |
| `backend/src/test/java/com/bifos/assistant/connector/ConnectorGatewayRestartTest.java` | 수정 (다시 확인 사례) |
| `web/src/lib/connection.ts` | 수정 (오류 문구) |
| `docs/features/connector.md` | 수정 |
