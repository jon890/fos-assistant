# Phase 01. Control Plane 의 금융 진입점과 계좌 소유자 관문

**Execution profile**: deep

## 목표

모델이 부른 준비 도구 호출을 금융 준비(`ConnectorFinancialProducer`)로, 공개 승인 API 의 금융 줄을 금융 승인(`ConnectorFinancialApproval`)으로 잇는다.
허용 목록 항목에 사용자를 묶어 한 계좌를 한 사용자에게만 열고, 승인 카드에 보일 금융 요약을 응답에 싣는다.

**범위 외**: 토스 커넥터의 manifest 와 서버 등록(phase-02), 화면과 기능 문서(phase-03), `modify_order` 와 조건주문 쓰기, 실제 토스 API 호출.

## 컨텍스트

이 저장소는 공개 저장소다. 홈서버의 주소, 포트, 경로, 계정, key 를 꺼내는 명령을 코드와 테스트, 커밋 메시지에 쓰지 않는다(루트 `AGENTS.md` 「공개 저장소」).

지금 main 에서 금융 실행은 이렇게 막혀 있다.

- `ToolPolicyDecision.decide` 가 `FINANCIAL` 을 `RISK_NOT_OPEN` 으로 거절한다. **이 차단은 바꾸지 않는다.** 실행 도구를 직접 부른 호출은 계속 거절된다.
- `ConnectorFinancialProducer.request` 와 `ConnectorFinancialApproval.approve` 는 package-private 이고 운영 호출자가 없다. 테스트(`FinancialConnectorProducerTest`, `FinancialConnectorProducerRoundTripTest`)만 부른다.
- 공개 승인(`ConnectorActionApproval.approve`)은 재판정에서 `RISK_NOT_OPEN` 을 받아 금융 줄을 실행하지 않는다.
- plugin 은 `approval: always` 도구를 모델 목록에서 빼므로(`hermes/plugins/dashboard-profile-api/connector_manifest.py` 의 `tools.exclude`) 모델은 실행 도구를 보지 못하고 준비 도구 `prepare_trade` 만 본다. plugin 은 이 phase 에서 고치지 않는다.

준비 도구의 MCP 입력은 `{v: 1, tool, args}` 다(`hermes/connectors/tossinvest/src/trading-tools.ts` 의 `prepare_trade`). producer 는 실행 도구의 등록 이름과 `args` 글을 받아 plugin 의 `POST /api/connectors/{id}/prepare` 로 그 준비 도구를 한 번 부른다.

**근거 문서**: `docs/adr/ADR-20261013-financial-order-opening.md`, `docs/adr/ADR-20261010-financial-execution-guard.md`, `docs/adr/ADR-20261012-financial-unsent-proof.md`, `backend/docs/adr/ADR-20261013-financial-digest-key-rotation.md`, `docs/features/connector-policy.md` 의 「금융 실행 내용의 저장과 검증」, `docs/features/tossinvest-connector.md` 의 「매매 개방 조건」

## 의도 메모

- 실행 도구를 모델 목록에 두고 hook 이 준비로 넘기는 안은 버렸다. plugin 의 `always` 제외와 금융 숨김을 함께 풀어야 하고 실행 도구가 모델에게 보인다.
- 하루 합계를 계좌 단위로 묶는 안은 버렸다. 남의 줄을 그 사용자의 데이터 key 로 여는 복호화가 생긴다. 소유자가 「한 계좌는 한 사용자만」 을 골랐다.
- USD 금액 주문은 환율로 바꾸지 않는다. `ConnectorFinancialGate.amount` 가 이미 통화별 상한(`max-order-amount-usd`, `max-daily-amount-usd`)과 견주고, 가격이 없는 수량 주문을 닫는다. 그 동작을 바꾸지 말고 테스트로 보인다.
- 거절은 어느 검사에 걸렸는지 모델 답에 밝히지 않는다. 운영자는 기동 로그의 `financial gate:` 한 줄로 가린다.
- `ConnectorFinancialProducer.request` 와 `ConnectorFinancialApproval.approve` 는 트랜잭션 안에서 부르면 거절한다(`require(!isActualTransactionActive())`). 새 호출부도 트랜잭션 밖에서 부른다.
- 살펴보기 트리(`ProactiveCheckGuard.checkOf`, 매일 깨우기 포함) 안의 준비 도구 호출은 쓰기를 허용한 살펴보기여도 금융 준비로 넘기지 않는다. 사람이 보지 않는 실행이 외부 글에 속아 주문 승인 요청을 쌓지 않게 한다.
- 살펴보기가 아닌 일반 예약 작업(task 실행)은 승인 줄을 만든다(2026-10-14 사용자 결정). 외부 글에 속아 요청이 쌓일 수는 있으나 사람이 승인 카드에서 승인하지 않으면 나가지 않는다. 정기 매수처럼 사용자가 예약한 주문 요청을 막지 않기 위해서다.
- 새 테스트 클래스는 `FinancialConnectorProducerTest` 를 상속하지 않는다. 상속하면 부모의 `@Test` 가 한 번 더 돌아, plugin 프로세스를 띄우는 묶음이 세 번 돈다. 공용 준비를 추상 기반 클래스로 옮긴다(작업 항목 1).

## Blocked 조건

- 소유자 개방 승인 기록이 코디네이터에게 없다 → `PHASE_BLOCKED: 개방 승인 기록 없음`. 2026-10-13 승인이 있으므로 지금은 해당하지 않는다.

## 작업 항목

### 1. 닫힌 관문 회귀를 먼저 둔다

`FinancialConnectorProducerTest` 의 필드, `@BeforeEach`, `@AfterEach`, 보조 메서드(실제 plugin 과 loopback 토스 대역, claim HTTP 를 띄우는 준비)를 새 추상 클래스 `backend/src/test/java/com/bifos/assistant/connector/application/FinancialConnectorTestBase.java` 로 옮긴다. 클래스 애노테이션(`@BackendIntegrationTest`, `@FinancialExecutionOpen`)도 기반 클래스에 둔다.
`FinancialConnectorProducerTest` 는 그 기반을 상속하고 자기 `@Test` 만 남긴다. `FinancialConnectorProducerRoundTripTest` 는 지금처럼 `FinancialConnectorProducerTest` 를 상속한다.
새 테스트 클래스 `backend/src/test/java/com/bifos/assistant/connector/application/FinancialOrderOpeningTest.java` 는 `FinancialConnectorTestBase` 를 상속한다.
항목 2~5 를 구현하기 전에 아래 회귀를 먼저 쓰고, 지금 main 에서도 금융 전송 0회인지 확인한다.

공개 진입점은 `ConnectorPolicyService.decide`(준비 도구 호출)와 `ConnectorActionService.approve`(승인)다. 아래 각 조건에서 둘 다 금융 POST 0회, claim 0회여야 한다.

- 금융 해시 key 파일 설정이 비었다
- 허용 목록이 비었다
- 해당 통화의 한 건 상한이 0 이다
- `approval-claim-v1` protocol 의 PENDING 줄이 남아 있다
- 이전 key 로 만든 PENDING 이나 UNKNOWN 줄이 남아 있다

관문 설정은 `OverridableLiveProperties.during` 으로 바꾼다(`ConnectorExecutionClaimTest` 183행 근처가 본보기다). 이전 protocol 과 이전 key 줄을 만드는 방법은 `ConnectorConditionalSnapshotTest` 의 key 교체 검사를 따른다.

### 2. 허용 목록 항목을 `<계좌 순번>:<사용자 메일>` 로 바꾼다

`backend/src/main/java/com/bifos/assistant/connector/application/ConnectorFinancialProperties.java`

- `allowedAccounts` 의 각 항목을 첫 `:` 에서 나눈다. 앞은 `[0-9]{1,19}` 인 계좌 순번(공용 vector 의 `000007` 을 쓰는 테스트를 위해 앞자리 0 을 받는다. 비교는 글자 그대로다), 뒤는 공백과 `:` 가 없고 `@` 가 있는 비지 않은 메일이다.
- 형식이 틀리거나 같은 계좌가 두 번 나오면 `IllegalStateException` 으로 기동을 멈춘다. 메시지에 값 원문을 넣지 않는다.
- 공개 메서드 `Optional<String> ownerOf(String account)` 를 더한다. 계좌의 메일을 돌려준다.
- Javadoc 의 `@param allowedAccounts` 를 새 형식으로 고친다.

`backend/src/main/java/com/bifos/assistant/connector/application/ConnectorFinancialGate.java`

- `requireAccounts(Collection<String>)` 를 `requireAccounts(Long userId, Collection<String> scopeValues)` 로, `requireScope(String)` 을 `requireScope(Long userId, String scopeJson)` 으로 바꾼다.
- `AppUserRepository` 를 주입해 `findById(userId)` 의 메일을 읽고, scope 의 모든 값에 대해 `ownerOf(value)` 가 그 메일과 같아야 지난다. 비교는 둘 다 `toLowerCase(Locale.ROOT)` 로 바꾼 뒤 한다. 사용자가 없으면 닫는다.
- 호출부 셋을 고친다. `ConnectorFinancialProducer` 의 `gate.requireAccounts(scope.values())` 는 `before.user()`, `ConnectorFinancialApproval` 은 `user.id()`, `execution/ConnectorExecutionClaims` 는 `payload.userId()` 를 넘긴다.
- 기동 로그 `financial gate:` 의 형식은 그대로 둔다. `allowedAccounts` 수는 항목 수다.

`backend/src/main/resources/application.yml` 의 `allowed-accounts` 줄 위에 형식 주석을 둔다. `<계좌 순번>:<사용자 메일>` 을 쉼표로 잇고, 메일은 대소문자를 가리지 않으며, 한 계좌는 한 번만 적고, 옛 형식(계좌 순번만)이면 기동이 멈춘다는 것이다. 실제 값은 적지 않는다.

`backend/src/test/java/com/bifos/assistant/testsupport/FinancialExecutionOpen.java` 의 `allowed-accounts` 기본값은 지운다.
금융 테스트는 주인 사용자를 만든 뒤 `OverridableLiveProperties.during` 으로 `7:<그 메일>` 같은 항목을 넣는다.
`FinancialConnectorProducerTest`, `ConnectorExecutionClaimTest`, `ConnectorConditionalSnapshotTest`, `ConnectorFinancialGateTest` 의 준비 단계를 그렇게 고친다.

### 3. 준비 도구 호출을 금융 준비로 넘긴다

`backend/src/main/java/com/bifos/assistant/connector/application/ConnectorPolicyService.java` 의 `decide`

- 생성자에 `ConnectorFinancialProducer` 와 `ConnectorExecutionGuard` 를 더한다.
- 처음의 `actions.findByDedupeKey(dedupeKey)` 가 찾은 줄의 `risk()` 가 `FINANCIAL` 이면 `replayed` 로 이르게 돌려주지 않고 아래 바인딩 해석을 계속한다. 이어지는 준비 도구 판정이 producer 로 넘기고, producer 의 dedupe 조회가 같은 승인 번호를 돌려준다. 바인딩 해석 뒤에도 준비 도구 호출이 아니면 기록 없이 막는다.
- 바인딩과 manifest 를 고르고 `confirmedTool` 을 정한 뒤, `manifest.executionGuard()` 가 객체이고 그 `prepare_tool` 원문 문자열이 `confirmedTool` 과 같으면 금융 경로로 보낸다. 같지 않으면 지금 판정을 그대로 한다.
  금융 경로 안에서 `guard.read(manifest)` 가 던지면 기록 없이 막는다. 준비 도구 호출만 막고 그 커넥터의 다른 도구는 막지 않는다.
- `checkBoundary(origin)` 이 `NOT_CHECK` 가 아니면 금융 경로는 producer 를 부르지 않고 기록 없이 막는다.
- 금융 경로는 private 메서드 하나로 둔다.
  - `argsJson` 을 중복 키를 거절하는 `JsonMapper`(`StreamReadFeature.STRICT_DUPLICATE_DETECTION`)로 읽는다. 최상위가 정확히 `v`, `tool`, `args` 세 키의 객체이고 `v` 는 정수 1, `tool` 은 문자열, `args` 는 객체여야 한다.
  - `tool` 이 `contract.operations()` 에 있고 그 값이 `CREATE` 나 `CANCEL` 이며 `contract.kind(tool)` 이 `ORDER` 여야 한다.
  - 맞으면 `producer.request(profileName, rootSessionId, sessionId, toolCallId, HermesToolName.of(binding.mcpServer(), tool), tool, JSON.writeValueAsString(args))` 의 답을 그대로 돌려준다.
  - 맞지 않거나 producer 가 `RuntimeException` 을 던지면 `new ConnectorPolicyAnswer(false, FINANCIAL_UNAVAILABLE_MESSAGE, null)` 을 돌려준다. 문구는 「금융 요청을 준비하지 못해 실행하지 않았다. 같은 주문의 승인 요청이 이미 있을 수 있다. 다시 부르지 말고 사용자에게 승인 카드와 토스증권 앱을 확인해 달라고 안내한다.」 이다. 같은 의도 충돌로 거절된 다른 `tool_call_id` 의 호출도 이 답을 받는다. 로그에는 예외의 클래스 이름만 남긴다.
- 금융 경로는 `ToolPolicyDecision.decide` 를 부르지 않고 일반 승인 줄을 만들지 않는다. 실행 도구 이름으로 들어온 호출은 지금처럼 `RISK_NOT_OPEN` 이다.

### 4. 공개 승인 API 의 금융 줄을 금융 승인으로 보낸다

`backend/src/main/java/com/bifos/assistant/connector/application/ConnectorActionService.java` 의 `approve(CurrentUser, UUID, GrantPeriod, boolean)`

- 생성자에 `ConnectorFinancialApproval` 을 더한다.
- `actions.findByPublicId(actionId)` 의 줄이 있고 `risk()` 가 `FINANCIAL` 이면 금융 분기다. 먼저 줄의 `userId()` 가 `user.id()` 와 다르면 `ConnectorActionDetails.notFound()` 를 던진다. 남의 줄에 입력 오류를 내면 그 줄이 있다는 단서가 된다. 그다음 `grant != null` 이거나 `conversationGrant` 가 참이면 `ApiException(ErrorCode.VALIDATION_FAILED, "financial actions take no grant")` 를 던지고, 아니면 `financialApproval.approve(user, actionId)` 를 돌려준다.
- 이 메서드는 트랜잭션 없이 부른다. 클래스나 메서드에 `@Transactional` 을 더하지 않는다.
- 금융 PENDING 줄의 거절(`reject`)이 실행하지 않고 `REJECTED` 로 끝나는지 테스트로 확인한다. 다르면 같은 분기를 `reject` 에도 둔다.

### 5. 승인 카드의 금융 요약을 응답에 싣는다

새 파일 `backend/src/main/java/com/bifos/assistant/connector/application/model/FinancialActionSummary.java`

```java
public record FinancialActionSummary(
    boolean available, String accountSeq, String operation, String symbol, String market, String currency,
    String side, String quantity, String orderAmount, String price, String orderType, String timeInForce,
    String orderId, List<Normalization> normalization) {
    public record Normalization(String field, String before, String after) {}
    public static FinancialActionSummary unavailable() { ... }  // available=false, 나머지 null, 빈 목록
}
```

새 파일 `backend/src/main/java/com/bifos/assistant/connector/application/ConnectorFinancialSummaries.java`(`@Component`, public)

- `Map<UUID, FinancialActionSummary> summaries(CurrentUser user, List<UUID> actionIds)` 와 `Optional<FinancialActionSummary> summaryOf(CurrentUser user, UUID actionId)` 를 둔다. 둘 다 `@Transactional(readOnly = true)` 다.
- 주인의 줄이고 `risk()` 가 `FINANCIAL` 인 줄만 다룬다. 그 밖의 줄은 결과에 넣지 않는다.
- `ConnectorActionExecutionRepository.findById(action.id())` 와 `ConnectorExecutionSnapshot.open(action, row, cipher, digest)` 로 연다. 실행 줄이 없거나 `open` 이 던지거나, `kind()` 가 `ORDER` 가 아니거나, scope 가 값 하나인 객체가 아니면 `unavailable()` 이다. 경고 로그에는 actionId 와 예외 클래스 이름만 남긴다.
- `accountSeq` 는 summary 의 `account` 가 아니라 scope 의 그 한 값이다. summary 의 `account`(끝 네 자리)는 싣지 않는다.
- 나머지 칸은 summary 의 같은 이름 칸을 글 그대로 옮긴다. null 은 null 이다.

`backend/src/main/java/com/bifos/assistant/connector/presentation/ConnectorActionDtos.java` 의 `ActionView`

- 필드 `FinancialActionSummary financial` 을 더한다. 금융 줄이 아니면 null 이다.
- 기존 `from(view)` 와 `from(view, contentDraft)` 는 남기고 `from(view, contentDraft, financial)` 을 더한다.

`backend/src/main/java/com/bifos/assistant/connector/presentation/ConnectorActionController.java`

- `list` 는 `financialSummaries.summaries(user, actionIds)` 의 값을 줄마다 붙인다.
- `approve` 와 `reject` 는 `summaryOf(user, actionId)` 를 붙인다.

### 6. 이 phase 를 검증하는 테스트

`FinancialOrderOpeningTest` 에 아래를 더한다. 금융 POST, claim, 준비 HTTP 의 횟수를 기존 대역 counter 로 센다.

- 준비 도구 호출이 `PENDING` 금융 줄 하나를 만들고, 그 줄의 도구는 `create_order` 의 등록 이름이다. 모델 답은 막힘이고 승인 번호가 있다
- 같은 `tool_call_id` 로 다시 부르면 같은 승인 번호이고 준비 HTTP 가 늘지 않는다
- `tool` 이 `modify_order` 인 호출, `v` 가 2 인 호출, 중복 키나 추가 키가 있는 호출은 줄 없이 막힌다
- 실행 도구 이름으로 직접 부른 호출은 `RISK_NOT_OPEN` 이다
- 읽기 경계의 살펴보기와 쓰기를 허용한 살펴보기 실행에서 준비 도구를 부르면 둘 다 줄 없이 막히고 준비 HTTP 가 0회다
- 일반 예약 작업 실행에서 준비 도구를 부르면 PENDING 금융 줄 하나가 생기고 금융 POST 와 claim 은 0회다. 공개 승인 뒤에 금융 POST 가 1회다
- 공개 승인(`ConnectorActionService.approve`)이 금융 POST 를 한 번 보내고 줄이 `SUCCEEDED` 다. 같은 줄을 다시 승인하면 `CONNECTOR_ACTION_NOT_PENDING` 이고 금융 POST 가 늘지 않는다. claim 재생도 0회다
- 승인에 `grant` 나 `conversationGrant` 를 주면 입력 오류이고 실행하지 않는다
- 금융 PENDING 줄을 거절하면 실행 없이 `REJECTED` 다
- 허용 목록 항목의 메일이 다른 사용자면 준비부터 거절된다. 같은 계좌를 두 항목에 적으면 `ConnectorFinancialProperties` 생성이 실패한다
- USD 금액 주문(`orderAmount` "25", USD 한 건 상한 30, 원화 상한 0)은 지난다. "31" 은 거절된다
- 하루 합계는 PENDING 을 세지 않는다(`ConnectorFinancialGate` 의 `SPENT`). 대역 토스(`test/e2e/financial/trading_http.ts`)는 종목 `ABC`(NASDAQ, USD) 하나만 돌려주므로 종목은 `ABC` 로 두고 금액을 바꾼다. request_key 가 `orderAmount` 를 포함하므로 금액이 다르면 같은 의도 충돌이 아니다.
  하루 USD 상한 30 에서 "20" 금액 주문을 준비하고 승인해 `SUCCEEDED` 로 만든다. 이어 "11" 을 준비하면 거절되고(합계 31) 줄이 생기지 않는다. 그다음 "10" 을 준비하면 PENDING 줄이 생긴다(합계 30). 통과하는 대조군이 있어야 거절의 까닭이 상한임이 드러난다
- 가격 없는 수량 주문(미국 `MARKET` 매도 `quantity` "0.1")은 거절된다
- `ConnectorFinancialSummaries` 가 scope 의 계좌 순번을 싣는다. summary 의 `account` 가 같고 scope 계좌가 다른 두 줄은 서로 다른 `accountSeq` 를 낸다. 대역 plugin 은 계좌 하나로 고정돼 있으므로 이 두 줄은 `ConnectorConditionalSnapshotTest` 처럼 `ConnectorExecutionSnapshot.capture` 와 저장소로 직접 만든다
- 암호문을 망가뜨린 줄은 요약이 `available=false` 이고, 그 줄을 승인해도 금융 POST 가 0회다

기존 테스트의 기대를 고친다.

- `FinancialConnectorProducerRoundTripTest` 의 「공개 정책과 승인 경로의 금융 실행 차단을 유지한다」 는 실행 도구 직접 호출의 차단만 남기고, 공개 승인의 금융 실행은 위 새 테스트가 맡는다
- `backend/src/test/java/com/bifos/assistant/connector/ConnectorActionControllerTest.java` 에 `$[0].financial` 이 금융 줄에만 있는지 더한다
- `backend/src/test/java/com/bifos/assistant/connector/ConnectorPolicyEndpointTest.java` 가 생성자 변경으로 깨지면 고친다

## 검증

```bash
cd backend && ./gradlew test --tests '*Financial*' --tests '*ConnectorPolicyEndpointTest' --tests '*ConnectorActionExecutionTest' --tests '*ConnectorActionControllerTest' --tests '*ConnectorExecutionClaimTest' --tests '*ConnectorConditionalSnapshotTest' --tests '*ToolPolicyDecisionTest'
cd backend && ./gradlew archTest checkstyleMain checkstyleTest
cd backend && git fetch origin && ./gradlew spotlessCheck
```

모두 0 으로 끝나야 한다. `spotlessCheck` 가 실패하면 `./gradlew spotlessApply` 결과를 기능 커밋과 다른 커밋으로 둔다(`backend/AGENTS.md` 「포맷」).

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/connector/application/ConnectorFinancialProperties.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/connector/application/ConnectorFinancialGate.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/connector/application/ConnectorFinancialProducer.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/connector/application/ConnectorFinancialApproval.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/connector/application/execution/ConnectorExecutionClaims.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/connector/application/ConnectorPolicyService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/connector/application/ConnectorActionService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/connector/application/ConnectorFinancialIntake.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/connector/application/ConnectorActionSignals.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/connector/application/ConnectorFinancialSummaries.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/connector/application/model/FinancialActionSummary.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/connector/presentation/ConnectorActionDtos.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/connector/presentation/ConnectorActionController.java` | 수정 |
| `backend/src/main/resources/application.yml` | 수정 |
| `backend/src/test/java/com/bifos/assistant/testsupport/FinancialExecutionOpen.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/connector/application/FinancialConnectorTestBase.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/connector/application/FinancialOrderOpeningTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/connector/application/FinancialConnectorProducerTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/connector/application/FinancialConnectorProducerRoundTripTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/connector/application/ConnectorFinancialGateTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/connector/ConnectorExecutionClaimTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/connector/ConnectorConditionalSnapshotTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/connector/ConnectorActionControllerTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/connector/ConnectorPolicyEndpointTest.java` | 수정 |
