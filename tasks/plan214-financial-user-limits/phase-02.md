# Phase 02. 승인 카드의 오늘 남은 한도와 화면용 상한 API

**Execution profile**: standard

## 목표

주문 승인 카드 요약에 그 통화의 상한과 오늘 남은 한도를 싣고, 화면이 상한을 읽고 바꾸는 API 를 연다.

**범위 외**: 대화로 바꾸는 MCP 도구(phase 03), 화면 구현(phase 04).

## 컨텍스트

- 카드 요약은 `backend/src/main/java/com/bifos/assistant/connector/application/ConnectorFinancialSummaries.java` 가 `FinancialActionSummary`(`connector/application/model/FinancialActionSummary.java`)로 만든다. 승인 목록 한 번에 `summaries(user, actionIds)` 가 줄마다 부른다.
- phase 01 이 만든 `FinancialOrderLimits.effective(userId)` 와 `spentToday(userId, ownActionId, now)` 를 쓴다.
- 상한을 쓰는 쪽은 금융 준비와 승인처럼 먼저 `AppUserRepository.findByIdForUpdate` 로 사용자 행을 잠근다. 그래야 주문 판정과 차례로 일어난다.
- `presentation` 의 요청과 응답 record 는 그 패키지의 `*Dtos.java` 하나에 모은다(`ArchitectureRules.CONTROLLERS_HAVE_NO_NESTED_RECORDS`). 새 하위 패키지 `connector/presentation/financial/` 에 컨트롤러와 Dtos 를 둔다. `connector/presentation/contentjob/` 이 본보기다.
- 이 API 는 브라우저 세션(웹 서버 라우트가 만든 JWT)으로만 부른다. MCP 토큰으로는 부를 수 없다. 에이전트가 승인 없이 상한을 올리지 못하게 하는 경계다.

**근거 문서**: `docs/adr/ADR-20261015-financial-user-limits.md`, `docs/features/tossinvest-connector.md` 의 「주문 상한」

## 의도 메모

- 화면의 올리기는 계좌 주인의 직접 조작이라 승인 카드를 만들지 않는다. 다시 확인은 화면(phase 04)이 한다.
- 최대치를 넘는 값은 저장하지 않고 `FINANCIAL_LIMIT_ABOVE_MAXIMUM` 으로 거절한다.
- 허용 목록에 계좌가 없는 사용자는 `available=false` 로 읽고, 쓰기는 `FORBIDDEN` 이다.

## 작업 항목

### 1. 카드 요약에 상한 상태를 싣는다

- `backend/src/main/java/com/bifos/assistant/connector/application/model/FinancialLimitStatus.java` 신규 record: `currency`, `orderLimit`(그 통화의 한 건 금액 상한), `quantityLimit`, `dailyLimit`, `spentToday`, `remainingToday`. 금액은 `BigDecimal` 이고 JSON 에는 소수점 뒤 0 을 뺀 문자열로 나간다(`toPlainString` 뒤 `stripTrailingZeros`).
- `FinancialActionSummary` 에 `FinancialLimitStatus limits` 필드를 끝에 더한다. 생성 주문(`operation` 이 `CREATE`)이고 통화가 KRW 나 USD 일 때만 채우고, 취소와 열지 못한 줄은 null 이다.
- `ConnectorFinancialSummaries.summaries` 는 사용자마다 `effective` 와 `spentToday(user, null, now)` 를 한 번만 읽어 줄마다 붙인다. 읽다가 실패하면 그 줄의 `limits` 만 null 로 두고 로그에 종류만 남긴다.
- 기존 골든 fixture `test/resources/connector-argument-masked-action.json` 은 금융 줄이 아니라 바뀌지 않는다.

### 2. 화면용 상한 API

- `backend/src/main/java/com/bifos/assistant/connector/application/FinancialLimitSettings.java` 신규에 `view(CurrentUser user, Instant now)` 와 `set(CurrentUser user, FinancialLimitKind kind, BigDecimal valueOrNull, Instant now)` 를 둔다. 계산은 `FinancialOrderLimits` 에 두고 바꾸는 쪽만 나눈다.
  - `available` 은 허용 목록(`ConnectorFinancialProperties.allowedAccounts`)에 그 사용자 메일의 계좌가 있는가다. 없으면 `set` 은 `ErrorCode.FORBIDDEN`.
  - `set` 은 트랜잭션 안에서 사용자 행을 잠그고, 값이 음수이거나 소수 6자리를 넘으면 `VALIDATION_FAILED`, 최대치보다 크면 `FINANCIAL_LIMIT_ABOVE_MAXIMUM` 이다. null 은 사용자 값을 지워 최대치를 따르게 한다.
- `backend/src/main/java/com/bifos/assistant/connector/application/model/FinancialLimitsView.java` 신규 record: `available`, `limits`(종류마다 `name`, `currency`, `value`, `maximum`, `custom`), `spentToday`, `remainingToday`(둘 다 `KRW`, `USD` 키의 문자열 맵).
- `backend/src/main/java/com/bifos/assistant/connector/presentation/financial/FinancialLimitController.java` 신규
  - `GET /api/v1/financial-limits` → `FinancialLimitsView`
  - `PUT /api/v1/financial-limits/{name}` 본문 `{"value": "500"}` 또는 `{"value": null}` → 바꾼 뒤의 `FinancialLimitsView`. 모르는 `name` 은 `VALIDATION_FAILED`.
- `backend/src/main/java/com/bifos/assistant/connector/presentation/financial/FinancialLimitDtos.java` 신규: 요청 record 와 응답 변환.
- `ErrorCode` 에 `FINANCIAL_LIMIT_ABOVE_MAXIMUM(HttpStatus.BAD_REQUEST)` 를 더한다.

### 3. 이 phase 를 검증하는 테스트

- `backend/src/test/java/com/bifos/assistant/connector/application/FinancialOrderOpeningTest.java` 수정: 달러 매수 카드의 `limits` 가 한 건 상한, 하루 상한, 오늘 쓴 금액, 남은 한도를 싣는다. 취소 카드는 null 이다.
- `backend/src/test/java/com/bifos/assistant/connector/application/FinancialLimitSettingsTest.java` 신규: 아래 앞의 넷을 서비스에서 본다
- `backend/src/test/java/com/bifos/assistant/connector/FinancialLimitControllerTest.java` 신규: 경로와 입력 모양, MCP 토큰 거절을 본다
  - 낮추기와 올리기가 바로 반영되고 GET 이 새 값과 `custom=true` 를 읽는다
  - 최대치를 넘는 값은 400 `FINANCIAL_LIMIT_ABOVE_MAXIMUM` 이고 저장되지 않는다
  - null 이면 최대치로 돌아간다
  - 허용 목록에 없는 사용자는 GET 이 `available=false`, PUT 이 403 이다
  - MCP 토큰으로는 이 경로를 부를 수 없다(401 이나 403)

## 검증

```bash
cd backend && ./gradlew test --tests '*FinancialLimitSettingsTest' --tests '*FinancialOrderOpeningTest' --tests '*FinancialLimitControllerTest' --tests '*Financial*' --tests '*ConnectorArgumentLabelsPipelineTest'
cd backend && ./gradlew archTest spotlessCheck checkstyleMain checkstyleTest
scripts/dev/check-local.sh --fast
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/connector/application/model/FinancialLimitStatus.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/connector/application/model/FinancialLimitsView.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/connector/application/model/FinancialActionSummary.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/connector/application/ConnectorFinancialSummaries.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/connector/application/FinancialOrderLimits.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/connector/application/FinancialLimitSettings.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/connector/application/ConnectorFinancialIntake.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/connector/ConnectorActionControllerTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/connector/application/FinancialOrderOpeningTest.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/connector/presentation/financial/FinancialLimitController.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/connector/presentation/financial/FinancialLimitDtos.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/shared/error/ErrorCode.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/connector/application/FinancialLimitSettingsTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/connector/FinancialLimitControllerTest.java` | 신규 |
