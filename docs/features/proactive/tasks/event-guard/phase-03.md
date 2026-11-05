# Phase 03. 시작 전 점검, 깨우기와 켜기, 지시, 화면

**Execution profile**: standard

## 목표

사건 실행의 toolset 시작 막기와 모든 살펴보기의 스킬 요구를 지우고, 깨우기와 켜기가 판정 plugin(`tool_guard`)을 확인하며, 스킬의 공통 지침을 Control Plane 지시로 옮긴다.

**범위 외**: hook 과 판정 경로(phase 1, 2).

## 컨텍스트

**요구사항**: REQ10, REQ13

**근거 문서**: `docs/features/proactive/spec.md` 의 REQ10·REQ13, `docs/features/proactive.md` 의 「분야 지침이 지킬 것」(옮길 글)

- 설치 상태는 `HermesConnectorClient.readConnector` 다. `ConnectorFinancialProducer.verified` 가 같은 확인을 한다
- 켜기 까닭은 `task/application/dto/EventWatchBlocker` 의 상수다

## 의도 메모

- `ISOLATED_EXECUTION_REQUIRED` 는 매일 깨우기에 남는다. 사건 감지에서만 뺀다
- 가짜 Hermes 는 `PROACTIVE_CHECK_CALL` 로 살펴보기 실행을 알아본다. 새 `OPENING` 의 글로 바꾼다

## 작업 항목

### 1. `ProactiveCheckReadiness`, `CheckBlockerCode`, `ProactiveCheckService.startEvent`

`SKILL_MISSING` 과 스킬 판정을 지우고, `checkEvent(agent)` 는 `DISABLED` 만 본다. `startEvent` 의 격리 인자를 지운다.

### 2. `ProactiveCheckInstructions`, `ProactiveCheckRun.OPENING`

공통 지침과 일반 원칙을 `COMMON_RULES` 에 더하고, `area` 와 `problemKey` 설명에서 분야 지침을 지운다.

### 3. `task`: `EventWatchPoller`, `EventWatchService`, `EventWatchBlocker`, `HermesConnectorClient.ConnectorState`

깨우기 직전과 조회·켜기 때 설치 상태의 `tool_guard` 를 본다.

### 4. web 과 E2E·브라우저 시험

`web/src/lib/proactive-check.ts`, `web/src/lib/event-watch.ts` 의 까닭 문구, `test/e2e/fake-hermes/scenario-fixtures.ts`, `test/e2e/scenarios/proactive-check.ts`, `test/browser/*.spec.ts`.

### 5. 문서

`docs/features/proactive.md`, `docs/features/mcp.md`, `hermes/docs/hermes-contract.md`, `hermes/README.md`.

## 검증

```bash
cd backend && ./gradlew test --tests '*ProactiveCheck*' --tests '*EventWatch*' --tests '*Wake*'
cd web && npx tsc --noEmit
scripts/dev/check-local.sh --fast
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/proactive/check/application/ProactiveCheckReadiness.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/proactive/check/application/dto/CheckBlockerCode.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/proactive/check/application/ProactiveCheckService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/proactive/check/application/ProactiveCheckInstructions.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/proactive/check/application/ProactiveCheckRun.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/task/application/EventWatchPoller.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/task/application/EventWatchService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/task/application/dto/EventWatchBlocker.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/hermes/application/port/HermesConnectorClient.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/hermes/infra/HttpHermesConnectorClient.java` | 수정 |
| `web/src/lib/proactive-check.ts` | 수정 |
| `web/src/lib/event-watch.ts` | 수정 |
| `test/e2e/fake-hermes/scenario-fixtures.ts` | 수정 |
| `docs/features/proactive.md` | 수정 |
