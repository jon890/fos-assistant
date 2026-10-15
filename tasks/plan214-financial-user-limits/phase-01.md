# Phase 01. 사용자 상한 저장과 상한 거절 이유

**Execution profile**: deep

## 목표

금융 관문이 운영 최대치 대신 「사용자 값과 최대치 중 작은 값」 으로 판정하고, 상한에 걸린 거절만 어느 상한인지와 오늘 남은 한도를 에이전트에게 밝힌다.

**범위 외**: 승인 카드의 남은 한도와 화면 API(phase 02), 대화로 바꾸는 MCP 도구와 상한 변경 승인(phase 03), 화면(phase 04).

## 컨텍스트

- 지금 관문은 `backend/src/main/java/com/bifos/assistant/connector/application/ConnectorFinancialGate.java` 다.
  `requireWithinLimits(userId, ownActionId, executionArgsJson, now)` 가 `properties.current()` 의 다섯 상한으로 보고, 모든 거절을 `closed()`(`CONNECTOR_ACTION_NOT_PENDING`) 하나로 던진다.
  `amount(args, limits)` 는 모양 검사와 한 건 상한 검사를 함께 한다.
- 호출자는 금융 준비 `ConnectorFinancialProducer.request` 와 금융 승인 `ConnectorFinancialApproval.approve` 다. 둘 다 같은 트랜잭션에서 `users.findByIdForUpdate` 로 사용자 행을 잠근 뒤 부른다.
- 준비의 거절은 `ConnectorFinancialIntake.prepare` 의 `catch (RuntimeException ex)` 가 `FINANCIAL_UNAVAILABLE_MESSAGE` 하나로 바꾼다.
- 최대치는 `ConnectorFinancialProperties` 의 다섯 값이다. 바꾸지 않는다.
- 새 마이그레이션 버전과 정렬 규칙은 `backend/docs/data-schema.md` 의 「마이그레이션 작성 규칙」 을 따른다. 엔티티와 마이그레이션은 함께 바꾼다(`backend/AGENTS.md` 의 「엔티티와 마이그레이션은 따로 논다」).
- 코드와 주석에 서비스 이름을 적지 않는다. `test/unit/connector-neutral.test.ts` 가 막는다.

**근거 문서**: `docs/adr/ADR-20261015-financial-user-limits.md`, `docs/features/tossinvest-connector.md` 의 「주문 상한」, `backend/docs/data-schema.md` 의 「financial_order_limit」

## 의도 메모

- 상한은 사용자 단위다. 하루 합계가 사용자 단위라서다. (사용자, 계좌) 단위는 ADR 에서 기각했다.
- 환율로 원화와 달러를 섞지 않는다. 통화마다 따로 견준다(ADR-20261013 / financial-order-opening).
- 모양이 틀린 주문, 가격 없는 수량 주문, 허용 계좌, key, scope 의 거절은 지금처럼 감춘다. 상한 거절만 이유를 밝힌다.
- 예외 메시지에 금액을 넣어도 되지만 로그에는 종류만 남긴다(기존 관례).

## 작업 항목

### 1. 마이그레이션 `backend/src/main/resources/db/migration/V20261015104354__financial_order_limit.sql`

`financial_order_limit` 테이블을 만든다. 컬럼은 `backend/docs/data-schema.md` 의 「financial_order_limit」 표와 같다.
`user_id` 는 PK 이고 `app_user(id)` FK 다. 다섯 상한 컬럼은 `DECIMAL(20,6) NULL`, `updated_at` 은 `DATETIME(6) NOT NULL` 이다.
`ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci` 를 적는다. DML 은 넣지 않는다.

### 2. 상한 종류와 엔티티, 저장소

- `backend/src/main/java/com/bifos/assistant/connector/domain/type/FinancialLimitKind.java`: enum `ORDER_AMOUNT_KRW`, `ORDER_AMOUNT_USD`, `ORDER_QUANTITY`, `DAILY_AMOUNT_KRW`, `DAILY_AMOUNT_USD`.
  각 값은 API 이름(`order_amount_krw` 처럼 소문자 밑줄), 통화(`KRW`, `USD`, 수량은 null), 그 값을 고르는 `ConnectorFinancialProperties` 접근자를 갖는다. `fromName(String)` 은 모르는 이름이면 빈 값이다.
- `backend/src/main/java/com/bifos/assistant/connector/domain/FinancialOrderLimit.java`: `@Entity @Table(name = "financial_order_limit")`. `userId` 가 `@Id` 다. `value(kind)` 와 `set(kind, BigDecimal valueOrNull, Instant now)` 를 둔다.
- `backend/src/main/java/com/bifos/assistant/connector/infra/FinancialOrderLimitRepository.java`: `JpaRepository<FinancialOrderLimit, Long>`.

### 3. `backend/src/main/java/com/bifos/assistant/connector/application/FinancialOrderLimits.java` 신규

`@Component`. 적용 상한과 오늘 쓴 금액을 계산하는 한 곳이다.

- `FinancialLimitValues effective(Long userId)`: 다섯 종류마다 `min(사용자 값 ?? 최대치, 최대치)`. 최대치는 `LiveProperties<ConnectorFinancialProperties>` 에서 읽는다.
- `Map<String, BigDecimal> spentToday(Long userId, Long ownActionId, Instant now)`: 통화별 오늘 쓴 금액. 지금 `requireWithinLimits` 의 반복문(KST 하루, `SPENT` 상태, `ownActionId` 제외, 실행 원문을 열어 금액 계산)을 그대로 옮긴다. 열지 못한 줄은 지금처럼 닫는다.
- `FinancialLimitValues` 는 `backend/src/main/java/com/bifos/assistant/connector/application/model/FinancialLimitValues.java` 의 record 다. `value(kind)` 와 `maximum(kind)`, `custom(kind)`(사용자 값이 있는가)를 낸다.

### 4. `ConnectorFinancialGate` 를 적용 상한으로 판정한다

- `requireWithinLimits` 는 `FinancialOrderLimits.effective` 와 `spentToday` 를 쓴다. 판정 순서는 지금과 같다: 모양 검사, 한 건 수량, 한 건 금액, 하루 합계.
- `amount(args, limits)` 의 둘째 인자를 `FinancialLimitValues` 로 바꾼다. 모양 거절은 지금처럼 `closed()`, 상한 거절은 새 예외다.
- `backend/src/main/java/com/bifos/assistant/connector/application/FinancialLimitExceeded.java` 신규: `ApiException` 을 잇고 코드는 `ErrorCode.FINANCIAL_LIMIT_EXCEEDED` 다. 칸은 `kind`(`FinancialLimitKind`), `limit`, `remainingToday`(하루 합계 종류와 한 건 금액 종류는 그 통화의 `max(0, 하루 상한 - 오늘 쓴 금액)`, 수량은 null)다.
- `zeroLimits` 와 기동 로그는 최대치를 그대로 쓴다.
- 관문의 생성자 인자에 `FinancialOrderLimits` 가 더해진다. 관문을 직접 만드는 `backend/src/test/java/com/bifos/assistant/connector/ConnectorConditionalSnapshotTest.java` 와 `ConnectorFinancialGateTest` 를 함께 고친다.
- `backend/src/main/java/com/bifos/assistant/shared/error/ErrorCode.java` 에 `FINANCIAL_LIMIT_EXCEEDED(HttpStatus.CONFLICT)` 를 더한다.

### 5. `ConnectorFinancialIntake` 가 상한 거절의 이유를 답에 싣는다

`prepare` 의 `catch` 에서 `FinancialLimitExceeded` 를 먼저 잡아 아래 모양의 답을 낸다. 나머지는 지금처럼 `FINANCIAL_UNAVAILABLE_MESSAGE` 다.

```
주문이 상한에 걸려 실행하지 않았다. 걸린 상한: {종류 한국어 이름} {limit} {통화}. 오늘 남은 한도: {remainingToday} {통화}.
사용자에게 알린다. 상한을 바꾸려면 order_limit_change 로 요청한다. 올리기는 사용자의 승인이 있어야 반영된다.
```

수량 상한은 `오늘 남은 한도` 문장을 뺀다. 종류의 한국어 이름은 `FinancialLimitKind` 에 둔다(「한 건 원화 금액」, 「한 건 달러 금액」, 「한 건 수량」, 「하루 원화 합계」, 「하루 달러 합계」).
`order_limit_change` 는 phase 03 이 만든다. 이 phase 에서는 문구만 둔다.

### 6. 이 phase 를 검증하는 테스트

- `backend/src/test/java/com/bifos/assistant/connector/application/FinancialOrderLimitsTest.java` 신규
  - 줄이 없으면 다섯 종류 모두 최대치다
  - 사용자 값이 최대치보다 작으면 사용자 값, 크면 최대치다
  - 0 은 0 이다
- `backend/src/test/java/com/bifos/assistant/connector/application/ConnectorFinancialGateTest.java` 수정
  - 사용자 하루 달러 상한 300 에 오늘 250 을 쓴 뒤 100 달러 주문은 `FinancialLimitExceeded`(`DAILY_AMOUNT_USD`, limit 300, remainingToday 50)
  - 오늘 쓴 금액이 새 하루 상한보다 크면 remainingToday 는 0 이다
  - 한 건 수량 상한에 걸리면 kind `ORDER_QUANTITY` 이고 remainingToday 는 null 이다
  - 가격 없는 수량 주문과 모양이 틀린 주문은 여전히 `closed()` 다
- `backend/src/test/java/com/bifos/assistant/connector/application/FinancialOrderOpeningTest.java` 수정: 준비 도구 호출이 상한에 걸리면 정책 답의 메시지에 종류, 값, 남은 한도가 있고 금융 POST 는 0회다. 허용 목록 밖 계좌의 답은 지금처럼 `FINANCIAL_UNAVAILABLE_MESSAGE` 다.

## 검증

```bash
cd backend && ./gradlew test --tests '*FinancialOrderLimitsTest' --tests '*ConnectorFinancialGateTest' --tests '*FinancialOrderOpeningTest' --tests '*ConnectorConditionalSnapshotTest' --tests '*Financial*'
cd backend && ./gradlew archTest spotlessCheck checkstyleMain checkstyleTest
node scripts/checks/check-migration-versions.mjs
scripts/dev/check-local.sh --fast
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/resources/db/migration/V20261015104354__financial_order_limit.sql` | 신규 |
| `backend/src/main/java/com/bifos/assistant/connector/domain/type/FinancialLimitKind.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/connector/domain/FinancialOrderLimit.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/connector/infra/FinancialOrderLimitRepository.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/connector/application/FinancialOrderLimits.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/connector/application/model/FinancialLimitValues.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/connector/application/FinancialLimitExceeded.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/connector/application/ConnectorFinancialGate.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/connector/application/ConnectorFinancialIntake.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/shared/error/ErrorCode.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/connector/application/FinancialOrderLimitsTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/connector/application/ConnectorFinancialGateTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/connector/application/FinancialOrderOpeningTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/connector/ConnectorConditionalSnapshotTest.java` | 수정 |
