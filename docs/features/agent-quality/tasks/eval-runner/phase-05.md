# Phase 05. 평가 실행기가 묶음 문항을 평가 사용자로 돌린다

**Execution profile**: standard

## 목표

평가 한 번(`kind` `EVAL`)을 열고, 시작 때 지난 실행이 남긴 것을 비운 뒤 고른 묶음의 문항을 평가 전용 사용자로 차례로 돌려 카드를 처리하고 판정하며, 문항마다 비우고, 끝나면 묶음 결과로 줄을 끝낸다. 재기동과 하루 정리가 끊긴 평가를 마무리한다(REQ9 의 중복 막기, REQ15, REQ16, REQ17).

**범위 외**: 관리자 API 와 e2e(phase 06).

## 컨텍스트

- 흐름의 원본은 `docs/features/agent-quality/design.md` 의 「평가 한 번의 흐름」, 「여러 차례와 승인 카드」, 「비우기」 다.
- 보내기, 끝남 기다리기, 마감 멈춤은 phase 04 의 `TurnDriver` 다. 위임 끝남 조건은 `DeployCheckObserver.delegationSettled` 와 `ChatService.deliveryStates` 다.
- 시작 잠금 관례: `DeployCheckRuns.open` 이 `AppUserRepository.findByIdForUpdate(평가 사용자)` 안에서 도는 줄을 찾는다.
- 모델 단계: 대화의 단계는 `ChatService.chooseModelTier(user, conversationId, mode, tier)`, 기본 단계 풀이는 `ModelTierService`(사용자 기본, 없으면 그룹 기본).
- 카드 승인은 화면과 같은 `ConnectorActionService.approve(user, actionId, null, false)` 와 `reject(user, actionId)` 다. 원고 저장 승인 줄은 `ConnectorActionController` 가 `ContentDraftSaveApproval.select(user, actionId)`(`connector/contentdraft/application`)로 먼저 고른다. 그 줄은 누르지 않는다.
- 자리 값: 다른 에이전트는 평가 사용자의 위임 대상 에이전트(`DeployCheckItems.DELEGATE_AGENT_NAME`), 플러그인은 그룹 카탈로그(`ConnectorConnectionService.catalog`)에서 평가 사용자가 연결하지 않은 첫 플러그인.

**요구사항**: REQ9, REQ15, REQ16, REQ17

**근거 문서**: `docs/features/agent-quality/design.md` 의 「PR2a: 평가 사용자로 묶음 문항 돌리기」, DECISION9

## 의도 메모

- 평가 실행기는 배포 뒤 확인 실행기와 따로 둔다(DECISION9).
- 모든 결과는 문항이 끝날 때마다 저장한다. 문항 시작 때 대화 번호와 시작 시각을 먼저 적는다(끊겨도 비울 대상이 남는다).
- 한도 거절은 `FAILED` 와 까닭 `BUSY`, 승인 호출 실패와 원고 줄은 `FAILED` 와 까닭 `CARD_FAILED` 다.
- 테스트와 e2e 는 `QualityProperties.evalTimeoutOverride`(`assistant.quality.eval-timeout-override`, 기본 없음)로 모든 묶음의 문항 상한을 줄인다. 묶음 파일의 분 단위 값으로는 테스트가 1분 넘게 걸린다.

## 작업 항목

### 1. 설정

`QualityProperties` 에 `evalTimeoutOverride`(`Duration`, 없으면 null)를 더하고 `application.yml` 에 `eval-timeout-override: ${ASSISTANT_QUALITY_EVAL_TIMEOUT_OVERRIDE:}` 를 둔다. 값이 있으면 0 보다 커야 한다.

### 2. `quality/application/EvalRuns.java`

짧은 트랜잭션의 쓰기: `open(evaluationUserId, bundles, tier, requestedBy)`(잠금 안에서 `RUNNING` 인 `EVAL` 이 있으면 그 줄과 「새로 만들지 않음」 을 돌려준다), `startItem`(대화 번호 적기), `record`, `finish`, `markCleaned`, 기동 때 `interruptRunning`(`EVAL` 만, 끝내지 못한 문항은 `FAILED` 까닭 `INTERRUPTED`).

### 3. `quality/application/EvalRunner.java`

시작 때 `cleaned` 가 거짓인 끝난 `EVAL` 줄을 `EvalCleaner` 로 다시 비운다. 고른 묶음이 비면 전체다. 단계를 고르지 않았으면 `ModelTierService` 로 평가 사용자에게 걸리는 기본 단계를 풀어 `tier` 에 적는다. 문항마다: 자리 채우기(없으면 `SKIPPED`), `TurnDriver` 로 빈 대화, 단계 고르기, 차례마다 보내기 → 끝남 기다리기(대화에 도는 turn 이 없고 전달이 끝남, 문항 상한 안) → 이번 차례가 만든 `PENDING` 승인 줄 처리(`approve`, `reject`, 적지 않았으면 `leave`, 원고 줄이면 `CARD_FAILED`) → 처리했으면 다시 끝남 기다리기. 관찰값(`EvalObservation`)을 모으고 `linkedTaskIds` 의 지시문을 `TaskService` 로 읽은 뒤 `EvalJudge` 로 판정한다. 판정 직후 남긴 카드 거절과 `EvalCleaner.cleanQuestion`, 통과이고 `keepConversation` 이 아니면 `DeployCheckCleaner.dropConversation`. 마지막에 묶음 결과로 `PASSED`(모든 묶음 통과)나 `FAILED`, 모든 문항이 비워졌으면 `markCleaned`. 모델 호출 수는 PR1 의 `DeployCheckObserver.modelCalls` 를 쓴다.

### 4. 하루 정리와 재기동 마무리

`EvalCleaner` 에 `@Scheduled(cron = "${assistant.quality.cleanup-cron}")` 를 더한다. `cleaned` 가 거짓인 끝난 `EVAL` 줄의 문항 대화를 다시 비우고, 보관 기간이 지난 실패와 `keep` 문항의 대화를 지운 뒤 링크를 비운다. 기동 처리(`interruptRunning`) 뒤에도 같은 비우기를 한 번 부른다.

### 5. 테스트

- `EvalRunsTest`(통합): 도는 `EVAL` 이 있으면 두 번째 `open` 이 같은 줄을 돌려준다. 도는 `DEPLOY_CHECK` 는 막지 않는다. `interruptRunning` 이 `EVAL` 만 끝내고 배포 확인 알림을 만들지 않는다.
- `EvalRunnerTest`(통합, PR1 의 `DeployCheckApiTest` 처럼 Hermes 쪽을 대역으로, `eval-timeout-override` 를 초 단위로 줄인다): 두 문항 묶음에서 하나 통과·하나 실패의 결과, 통과 대화가 지워지고 실패 대화와 `keepConversation` 대화가 남음, `card: approve` 차례 뒤 줄이 승인돼 `cardStatus` 로 판정, `card: reject` 차례 뒤 줄이 거절됨, 적지 않은 카드는 판정 뒤 거절, 원고 저장 줄이면 `CARD_FAILED`, 한도 거절이면 `BUSY`, 자리 값이 없는 문항은 `SKIPPED`, 상한을 넘은 문항은 `TIMED_OUT`, 단계를 고르면 대화 단계와 `tier` 가 그 값이고 고르지 않으면 기본 단계가 적힘, 고른 묶음이 비면 전체 묶음이 돎, 시작 때 `cleaned` 가 거짓인 지난 줄을 비움, 다른 사용자의 기억·할 일·예약 작업·대화 수가 그대로다.
- `EvalCleanerTest`: 하루 정리가 `cleaned` 가 거짓인 줄을 다시 비우고 「비우기 끝남」 을 적으며, 보관 기간이 지난 실패·`keep` 문항 대화를 지우고 링크를 비운다. 재기동 처리 뒤 비우기를 한 번 부른다.

## 검증

```bash
cd backend && ./gradlew test --tests 'com.bifos.assistant.quality.application.EvalRunsTest' --tests 'com.bifos.assistant.quality.application.EvalRunnerTest' --tests 'com.bifos.assistant.quality.application.EvalCleanerTest' --tests 'com.bifos.assistant.admin.quality.DeployCheckApiTest'
cd backend && ./gradlew archTest checkstyleMain checkstyleTest
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/quality/config/QualityProperties.java` | 수정 |
| `backend/src/main/resources/application.yml` | 수정 |
| `backend/src/main/java/com/bifos/assistant/quality/application/EvalRuns.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/quality/application/EvalRunner.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/quality/application/EvalCleaner.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/quality/application/EvalRunsTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/quality/application/EvalRunnerTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/quality/application/EvalCleanerTest.java` | 수정 |
