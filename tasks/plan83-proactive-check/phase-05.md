# Phase 05. 시간과 도구 호출 상한, 끝날 때의 정리

**Execution profile**: deep

## 목표

살펴보기 한 번이 `max-duration` 을 넘거나 도구 호출이 `max-tool-calls` 를 넘으면 그 turn 을 멈추고 까닭을 남긴다.
살펴보기가 어떻게 끝나든 그 트리의 위임 결과로 자동 turn 이 열리지 않게 하고, 도는 위임 자식을 멈추고, 위임 수를 적는다.

**범위 외**: 위임을 맡길 때의 대상과 수 판정(phase 06). 커넥터와 MCP 의 읽기 경계(phase 06).

## 컨텍스트

**근거 문서**: `docs/backend/proactive-check.md` 의 「상한」, 「끝날 때」, 「대화에 남는 것」, `docs/backend/agent-delegation.md` 의 「위임이 갈리는 지점」 의 「먼저 살펴보기가 끝난다」 줄, `docs/adr/ADR-077-먼저-살펴보기는-점검-대화의-turn-하나로-돌고-읽기-경계를-control-plane-이-강제한다.md`

- 멈추기: `ChatService.stop(CurrentUser user, Long executionId)`. 그 turn 과 루트 아래의 도는 자식 run 을 함께 멈추고, 이미 끝났으면 `EXECUTION_NOT_RUNNING` 을 던진다
- 위임 결과 전달 표시: `usage/application/ExecutionDeliveryWriter.java`, `usage/infra/AgentExecutionRepository.java` 의 `markResultDelivered` 와 `findUndeliveredResults`(위임 결과는 `delegationKey` 가 있고 부모가 루트인 줄이다)
- 위임 자식 멈추기: `orchestration/application/AgentDelegationService.java` 의 `stop`, `running` 맵, `sendStop`, `stopDetached`
- `orchestration` 은 `chat` 을 부르지 않고 사건으로 잇는다(`docs/backend/agent-delegation.md` 「위임 결과로 부모 대화를 깨우기」). 같은 방식으로 `proactive` 가 사건을 내고 `orchestration` 이 받는다
- 도구 사건을 흘리는 시험 본보기: `chat/ToolDetailStreamTest.java`(`@MockitoBean HermesRunEventStream`)

## 의도 메모

- 상한 판정은 이 turn 의 `tool.started` 만 센다. 커넥터 에이전트 안의 호출은 위임 수와 `hermes.run-timeout` 이 묶는다.
- `CheckTurn.toolStarted` 는 스트림을 읽는 스레드에서 `pending` 잠금을 쥔 채 불린다. 그 자리에서 `ChatService.stop` 을 부르지 않는다. 가상 스레드를 띄워 부른다.
- 멈추기가 `EXECUTION_NOT_RUNNING` 이나 다른 예외로 끝나면 경고 로그만 남긴다. 이미 끝난 turn 이다.
- 전달 표시는 아직 도는 위임 자식에도 적는다. 그 자식이 나중에 끝나도 점검 대화에 자동 turn 이 열리지 않는다. 자동 turn 은 읽기 경계 밖의 보통 turn 이다.
- 전달 표시와 사건은 잠금을 풀기 전에 한다. 잠금을 풀면 닫기 리스너가 곧바로 다음 turn 을 정한다.

## 작업 항목

### 1. `ProactiveCheckRun` 의 상한

- 멈춘 까닭을 들고 있는 칸을 둔다. 값은 `CHECK_TIME_LIMIT`, `CHECK_TOOL_LIMIT`, 없음이다. 처음 정한 까닭만 남긴다
- `started(executionId, ...)` 에서 `max-duration` 뒤에 깨는 가상 스레드를 하나 띄운다. 그때까지 끝나지 않았으면 까닭을 `CHECK_TIME_LIMIT` 로 정하고 `ChatService.stop(owner, executionId)` 를 부른다. 살펴보기가 끝나면 그 스레드를 깨워 끝낸다
- `toolStarted` 에서 센 값이 `max-tool-calls` 를 넘는 첫 순간 까닭을 `CHECK_TOOL_LIMIT` 로 정하고 가상 스레드에서 `ChatService.stop` 을 부른다
- `stoppedNotice` 가 까닭에 따라 「시간 한도에 닿아 살펴보기를 멈췄어요」, 「도구 호출 한도에 닿아 살펴보기를 멈췄어요」, 「살펴보기를 멈췄어요」 를 돌려준다
- 끝날 때 줄을 `STOPPED` 와 그 까닭(`error_code`)으로 적는다. 사용자가 멈췄으면 `error_code` 는 비운다

### 2. 위임 결과 정리와 위임 수

- `AgentExecutionRepository` 에 둘을 더한다
  - `long countByRootExecutionIdAndDelegationKeyIsNotNull(Long rootExecutionId)`
  - `@Modifying` `int markTreeDelivered(Long rootExecutionId, Instant at)`: `rootExecutionId` 가 같고 `delegationKey` 가 있고 `resultDeliveredAt` 이 빈 줄에 `resultDeliveredAt` 을 적는다. 상태는 보지 않는다
- `ExecutionDeliveryWriter.markTreeDelivered(Long rootExecutionId, Instant at)` 를 `@Transactional` 로 더한다
- `ProactiveCheckService` 의 가상 스레드 `finally` 에서, 잠금을 풀기 전에, 루트 번호가 있으면 차례로 한다
  1. 위임 수를 세어 줄에 적는다(줄의 상태를 적는 저장과 함께)
  2. `ExecutionDeliveryWriter.markTreeDelivered`
  3. `ApplicationEventPublisher.publishEvent(new ProactiveCheckEnded(rootExecutionId))`
- 각 단계가 실패해도 다음 단계와 잠금 풀기는 한다. 경고 로그를 남긴다

### 3. 사건과 받는 쪽

- `backend/src/main/java/com/bifos/assistant/proactive/application/ProactiveCheckEnded.java`: record `ProactiveCheckEnded(Long rootExecutionId)`
- `backend/src/main/java/com/bifos/assistant/orchestration/application/ProactiveCheckEndedListener.java`: `@EventListener` 로 받아 `AgentDelegationService.stopRunningChildrenOf(rootExecutionId)` 를 부른다
- `AgentDelegationService.stopRunningChildrenOf(Long rootExecutionId)`: 그 루트 아래 `RUNNING` 이고 `delegationKey` 가 있는 줄마다, 이 서버가 돌리는 것이면 중지 표시를 켜고 run 번호가 있으면 Hermes 에 중지를 보낸다. 이 서버가 돌리지 않는 것은 `stopDetached` 와 같게 한다. 끝나기를 기다리지 않는다. 권한 판정(`canQuery`)을 거치지 않는다. 부르는 쪽이 Control Plane 이다

### 4. 이 phase 를 검증하는 시험

- `backend/src/test/java/com/bifos/assistant/proactive/ProactiveCheckLimitTest.java`:
  - `@MockitoBean HermesRunEventStream` 이 `tool.started` 를 `max-tool-calls + 1` 번 흘리면 Hermes 에 중지가 가고(`StubHermesRunsClient.stopped()`), 줄이 `STOPPED` 와 `CHECK_TOOL_LIMIT`, 대화에 도구 호출 한도 알림 줄이 남고 답 조각이 남지 않는다
  - `max-duration` 을 짧게(예: 1초, `hermes.run-timeout` 은 그보다 길게) 두고 실행이 끝나지 않으면 줄이 `STOPPED` 와 `CHECK_TIME_LIMIT`, 시간 한도 알림 줄
  - 상한 안에서 끝나면 시간 상한 스레드가 멈추기를 부르지 않는다
- `backend/src/test/java/com/bifos/assistant/proactive/ProactiveCheckEndTest.java`(`assistant.delegation-wake.enabled=true`):
  - 살펴보기 turn 아래에 끝난 위임 자식 하나와 도는 위임 자식 하나를 둔 채 살펴보기가 끝나면 두 줄 모두 `result_delivered_at` 이 적히고, 점검 대화에 자동 turn 이 열리지 않으며(`DelegationResults` 알림 줄이 없다), `proactive_check.delegations` 가 2 다
  - `ProactiveCheckEnded` 를 받아 도는 자식에 중지가 간다
  - 살펴보기가 예외로 끝나도 위 정리가 된다
- `backend/src/test/java/com/bifos/assistant/orchestration/AgentDelegationServiceTest.java` 에 `stopRunningChildrenOf` 가 그 루트의 도는 위임 자식만 멈추고 다른 루트의 자식과 끝난 자식은 건드리지 않는 시험을 더한다

## 검증

```bash
# cwd: backend/
./gradlew test --tests 'com.bifos.assistant.proactive.*' --tests 'com.bifos.assistant.orchestration.AgentDelegationServiceTest' --tests 'com.bifos.assistant.chat.DelegationWakeServiceTest'
./gradlew test
./gradlew checkstyleMain checkstyleTest
```

```bash
# cwd: 저장소 root
scripts/check-mysql-migration.sh
```

기대값: 모두 종료 코드 0. 새 저장소 메서드 둘이 실제 MySQL 에서 실행된다.

## 변경 파일

| 파일 | 변경 |
| --- | --- |
| `backend/src/main/java/com/bifos/assistant/proactive/application/ProactiveCheckRun.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/proactive/application/ProactiveCheckService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/proactive/application/ProactiveCheckEnded.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/usage/infra/AgentExecutionRepository.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/usage/application/ExecutionDeliveryWriter.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/orchestration/application/ProactiveCheckEndedListener.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/orchestration/application/AgentDelegationService.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/proactive/ProactiveCheckLimitTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/proactive/ProactiveCheckEndTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/orchestration/AgentDelegationServiceTest.java` | 수정 |
