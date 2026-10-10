# Phase 01. 금융 실행 내용 저장

**Execution profile**: deep

## 목표

금융 승인 실행 내용을 불변으로 저장하고, 저장 전후에 표시 값과 실행 인자의 일치를 검증한다.

**범위 외**: 실제 거래, 새 의존성, Hermes core 변경과 다른 계획의 병렬 구현이다.

## 컨텍스트

**근거 문서**: `docs/features/connector-policy.md`, `backend/docs/data-schema.md`와 `backend/docs/adr/ADR-20261008-data-encryption.md`다.
독립 critic 통과와 코디네이터의 별도 구현 dispatch가 있어야 시작한다.
119~126은 변경하지 않는다. 최신 main의 승인 회귀 테스트를 보존하고 이미지 승인 변경과 공용 파일을 동시에 편집하지 않는다.
구현 순서는 저장과 claim, prepare와 권한 전달, 매수매도, 정정취소다.
선행 코드가 없으면 `PHASE_BLOCKED: 선행 구현 미통합`으로 보고한다.

## 의도 메모

- 금융 도구를 WRITE로 바꾸지 않는다. FINANCIAL은 always이고 grant는 만들거나 사용하지 않는다.
- DESTRUCTIVE는 기존 RISK_NOT_OPEN을 유지한다.
- 공개 운영 정보와 자격 증명, 실행 권한 원문을 파일과 DB, 로그에 남기지 않는다.
- 한 PR은 운영 코드 1000줄 이하이고 설계와 작동하는 구현을 함께 담는다. 초과하면 코디네이터에게 기능 분할을 보고한다.

## 작업 항목

### 1. 구현

실행 내용에는 request_key도 저장한다. 사용자, 연결과 계좌, 동작 및 정규화한 주문 필드를 하나의 backend 함수로 해시한다. clientOrderId와 원래 주문 조회 내용은 제외하고 원래 orderId는 포함한다. 연결과 바인딩 식별자와 scope를 보존하며 인자 원문 해시와 request_key를 혼용하지 않는다.
supersedes_unknown_action_id는 NULL을 허용하는 UNIQUE 컬럼으로 예약한다. 사용자가 명시한 새 요청 단계까지 값을 넣지 않는다. 원래 action을 지우면 승인 이력과 연결된 실행 내용도 함께 사라지므로 이 값은 자기 참조 FK 대신 이력 식별자로 둔다.

ConnectorActionExecution을 action_id PK로 만들고 실행 args와 summary, scope, 연결과 바인딩, revision, 해시와 권한 소비 시각을 저장한다. 신규 마이그레이션은 DDL만 담고 기존 파일을 수정하지 않는다. ticket 원문이나 서버 비밀값은 저장하지 않는다. ConnectorAction의 기존 원래 args와 중복 키는 유지한다. scope와 summary의 strict validator는 서비스 이름을 모르는 범용 코드로 둔다. 이 단계에서는 FINANCIAL/DESTRUCTIVE 차단을 바꾸지 않는다.

ticket_id는 ticket 원문과 다른 UUID 식별자이며 NULL을 허용하는 BINARY(16) UNIQUE 칸이다. ticket_expires_at과 consumed_at도 NULL로 예약하고 이 단계에서 발급과 소비 경로는 만들지 않는다. 연결과 바인딩 번호는 FK가 없는 이력 값이다. connection_updated_at과 binding_updated_at은 각각 현재 표의 updated_at과 같은 저장 타입과 정밀도의 NOT NULL 값으로 보존한다.

content_key_id는 NULL을 허용하는 BIGINT이며 key 값이 아닌 기존 user_data_key 식별자다. 기존 TextCipher로 execution_args_json, summary_json, scope_json을 각각 암호화하고 action_id, 칸 이름, 소유자를 AAD에 묶는다. 세 암호문의 keyId가 같아야 저장한다. 암호화가 꺼져 있으면 기존 암호화 ADR에 따라 평문과 NULL keyId를 저장한다. 켜져 있을 때 암호화나 복호화가 실패하면 저장·읽기를 거절하고 평문이나 빈 데이터로 성공시키지 않는다. 본문과 key, ticket 원문은 로그와 예외 메시지에 넣지 않는다. 해시는 암호화 전 원문의 UTF-8 SHA-256이며 읽을 때 복호화한 원문과 다시 대조한다.

읽기는 저장된 content_key_id로 정한다. NULL이면 현재 enabled()와 관계없이 평문으로 읽고 원문 해시를 검증한다. 비NULL이면 현재 enabled(false)여도 open을 거쳐야 하며 key 부재나 빈 Optional은 읽기 실패다. 세 seal 결과 중 하나라도 keyId가 다르거나 enabled(true)에서 seal이 빈 Optional이면 실행 내용 행을 저장하지 않는다.

실행 원문은 UTF-8 SHA-256을 계산해 그대로 저장한다. 아래 입력 계약은 이미 승인한 설계에서 이 단계에 필요한 정의만 담은 것이다. 구현과 함께 현재 기능 문서와 data-schema에 반영한다. 누락·추가·중복 키와 잘못된 타입을 버리거나 보충하지 않고 거절한다. 새 공통 vector에는 원문·기대 해시·summary·scope와 생성/정정/취소의 정상·거절 사례를 담는다. Java는 범용 형식과 표시 값·실행 args 대조를 검사하며 서비스별 시장 조합은 커넥터가 맡는다. 공통 vector는 Python과 Bun 구현도 그대로 읽는다.

#### 저장 입력 계약

protocol은 `approval-claim-v1`이다. 표의 본문 칸은 암호화 전 입력이며 암호화가 켜져 있으면 동일 칸에 암호문을 저장한다. 비밀값이나 권한 원문을 받는 칸은 없다.

| 컬럼 | 타입과 제약 |
| --- | --- |
| `action_id` | BIGINT NOT NULL, PK 및 connector_action FK, ON DELETE CASCADE |
| `connection_id`, `binding_id` | BIGINT NOT NULL, FK가 없는 이력 번호 |
| `connection_updated_at`, `binding_updated_at` | DATETIME(6) NOT NULL, 각각 연결과 바인딩 updated_at |
| `execution_args_json`, `summary_json`, `scope_json` | MEDIUMTEXT NOT NULL |
| `content_key_id` | BIGINT NULL, user_data_key 식별자, FK 없음 |
| `execution_args_sha256`, `scope_sha256` | VARCHAR(64) NOT NULL, 평문 UTF-8 SHA-256 소문자 hex |
| `request_key` | VARCHAR(64) NOT NULL, UNIQUE가 아닌 일반 인덱스 |
| `supersedes_unknown_action_id` | BIGINT NULL UNIQUE, 자기 참조 FK 없음, 이 단계는 NULL로 둠 |
| `protocol` | VARCHAR(32) NOT NULL |
| `ticket_id` | BINARY(16) NULL UNIQUE, UUID 식별자이며 ticket 원문이 아님 |
| `ticket_expires_at`, `consumed_at` | DATETIME(6) NULL, 이 단계는 NULL로 둠 |
| `created_at` | DATETIME(6) NOT NULL |

기존 connector_action.args_json과 args_sha256은 원래 요청의 중복 판정용으로 보존한다. 실행 내용은 두 번째 승인에서 다시 준비하거나 수정하지 않는다. clientOrderId는 prepare에서 만든 UUID를 그대로 보존한다.
request_key는 한 backend 함수만 계산한다. 입력은 userId, connectionId, tool, scope arg와 실행 args의 모든 정규화한 주문 필드다. clientOrderId, expected_order, summary와 조회 시각·표시 문구는 제외하고 market, currency와 원래 orderId는 포함한다. 동작에서 실제 쓰는 문자열 값만 넣는다. 필드 이름을 ASCII 순으로 정렬하고 각 이름·값을 `UTF-8 바이트수:값`으로 붙인다. 도메인 `fos-financial-intent-v1`도 같은 길이 접두사로 앞에 붙인 뒤 전체 UTF-8 바이트를 SHA-256으로 해시한다. scope arg가 account_seq인 vector에는 그 이름과 연결의 문자열을 그대로 넣는다. decimal은 앞의 0과 불필요한 소수점 끝 0을 없앤 문자열로 맞춘다. 인자 원문 해시와 request_key는 서로 대체하지 않는다.

#### JSON과 scope 입력 계약

중복 키, 추가 키, 누락, null과 타입 오류를 strict 파싱에서 거절한다. 원래 args와 실행 args는 각각 UTF-8 16 KiB, summary는 8 KiB, scope JSON은 2 KiB 이하이며 중첩 깊이는 5 이하다. 깨진 JSON이나 뒤에 붙은 다른 JSON도 거절하고 원문을 예외와 로그에 싣지 않는다. 준비 결과의 envelope는 `{v:1,executionArgs,summary}`만 갖고 전체 UTF-8 32 KiB 이하다. v는 boolean이 아닌 정수 1이다. 이 단계는 준비 API를 만들지 않고 저장 함수가 소비하는 JSON과 그 표시 값을 검증한다.

scope_fields는 1~8개의 `{arg,field}` 배열이다. 각 항목에는 두 키만 있고 값은 `[A-Za-z][A-Za-z0-9_]{0,63}` 문자열이다. arg와 field는 각각 중복될 수 없다. field는 manifest에서 secret:false로 선언한 연결 칸이어야 한다. scope에는 선언한 arg만 키로 들어가며 값은 비어 있지 않은 UTF-8 128바이트 이하 문자열이다. 숫자, null, 추가 키와 누락은 거절한다. 각 scope 값은 해당 연결의 공개 칸 및 실행 args의 같은 arg 값과 문자열 그대로 같아야 한다. 실제 env 대조는 후속 커넥터 구현이 맡는다.

#### 표시 summary 입력 계약

summary에는 다음 키가 모두 있어야 하며 추가 키는 거절한다. null은 표가 허용한 곳에만 쓴다. 모든 decimal은 `[0-9]+(\.[0-9]+)?` 문법의 1~30자 문자열이다. 수량, 금액과 가격은 양수만 허용하고 부호, 공백, 지수, 쉼표, 숫자 타입과 0은 거절한다. original.filledQuantity만 0 이상을 허용한다. 값을 숫자 타입으로 바꾸거나 잘못된 타입을 normalization으로 수선하지 않는다.

| 키 | 타입, 상한과 대조 |
| --- | --- |
| `account` | 끝 네 자리 숫자 문자열 4자, 커넥터의 계좌 조회 표시 값 |
| `symbol` | `[A-Za-z0-9.-]{1,32}` 문자열, 실행 args 또는 expected_order와 일치 |
| `market` | 비어 있지 않은 1~16자 문자열, 실행 args와 original의 조회 시장이 일치 |
| `currency` | ISO 통화 문자열 3자, 실행 args 및 original과 일치 |
| `side` | BUY 또는 SELL 문자열 |
| `quantity`, `orderAmount`, `price` | 양의 decimal 문자열 또는 미사용을 뜻하는 null |
| `orderType` | LIMIT 또는 MARKET 문자열 |
| `timeInForce` | DAY, CLS 또는 OPG 문자열 |
| `operation` | CREATE, MODIFY 또는 CANCEL 문자열, 검증한 대상 동작과 일치 |
| `orderId` | CREATE는 null, MODIFY/CANCEL은 제어 문자가 없는 1~256자 불투명 문자열, 실행 args와 일치 |
| `original` | CREATE는 null, MODIFY/CANCEL은 아래 원래 주문 object |
| `normalization` | 0~8개의 `{field,before,after}` 배열, 추가 키와 중복 field 금지 |

original에는 `orderId,symbol,market,currency,side,quantity,orderAmount,price,orderType,timeInForce,status,filledQuantity`만 있다. 공통 칸은 위 타입을 따르되 orderId와 quantity는 필수이며 null이 아니다. status는 1자 이상 32자 이하 문자열이고 filledQuantity는 1자 이상 30자 이하의 0 이상 decimal 문자열이다. orderAmount는 금액 주문에만, price는 LIMIT에만 값이 있고 나머지는 null이다. original의 값은 expected_order와 같고 market은 조회한 시장을 더한 값이다. 취소의 주 표시 값은 original과 같으며 정정은 변경 후 값과 original을 함께 보존한다.
normalization.field는 symbol, quantity, orderAmount, price, timeInForce 중 하나다. before/after는 1~32자 문자열 또는 누락을 뜻하는 null이며 기본 DAY 추가는 `{field:"timeInForce",before:null,after:"DAY"}`다. 입력 표기 변경, 기본값과 가격 절삭의 모든 차이는 원래 args와 실행 args를 대조해 빠짐없이 기록한다. 변경이 없으면 빈 배열이며 before/after가 실제 전후 값과 다르면 거절한다. 서비스별 가격 절삭과 시장 조합은 이 범용 소비자가 계산하지 않는다.

#### 실행 args 대조 계약

아래 schema는 승인 실행용 내부 키를 포함한다. 미사용 quantity/orderAmount/price는 실행 args에서 키 자체가 없고 summary에서는 null이다. scope arg는 선언으로 정하며 서비스 이름을 코드에 넣지 않는다.

| 동작 | 실행 args의 정확한 키 |
| --- | --- |
| CREATE | symbol, side, orderType, timeInForce, quantity/orderAmount 중 정확히 하나, LIMIT일 때 price, UUID clientOrderId, scope arg, market, currency |
| MODIFY | orderId, orderType, 해당 quantity/price, scope arg, market, currency, expected_order |
| CANCEL | orderId, scope arg, market, currency, expected_order |

expected_order에는 `orderId,symbol,side,orderType,timeInForce,quantity,price,orderAmount,currency,status,execution`만 있다. execution에는 filledQuantity 하나만 있다. 공통 칸은 summary.original 타입을 재사용하며 execution.filledQuantity도 0 이상의 decimal 문자열이다. original은 이 object의 execution을 펼치고 market을 더한 값이다. 정정에서 바꾸지 않는 symbol, side, timeInForce, 사용하지 않은 quantity는 원래 주문과 대조한다. orderId는 불투명 문자열 그대로 보존하고 clientOrderId 및 조회 baseline은 request_key에서 제외한다. LIMIT의 price 필수와 MARKET의 price 금지, 숫자 타입과 미선언 키 거절은 원래 JSON을 소비하는 함수가 검증한다. 특정 시장에서 quantity가 허용되는지 등 서비스별 조합은 커넥터가 맡는다.

현재 기본 브랜치의 connector-policy에는 저장과 strict 검증 계약만 절로 통합한다. 저장 계약은 data-schema가, JSON 필드와 상한은 connector-policy가, 고정 값은 공통 vector가 갖는다. 신규 ADR은 이 저장·검증 결정을 기록하며 미구현 claim/support/API나 실제 주문 실행을 완료한 것으로 쓰지 않는다. ADR 목록도 함께 갱신한다.

### 2. 테스트

검사 대상 파일: `backend/src/test/java/com/bifos/assistant/connector/ConnectorActionExecutionTest.java`, `backend/src/test/java/com/bifos/assistant/connector/ConnectorActionExecutionMigrationTest.java`.

ConnectorActionExecutionTest는 실제 저장·읽기 함수로 불변 실행 내용과 해시 일치, 필수값 누락, 중복 ticket_id, action_id PK, action FK 삭제와 두 revision 보존을 확인한다. 서로 다른 owner·행·칸의 암호문 교체, 암호문 변조, key 불일치, enabled(true)의 실패와 enabled(false)의 평문 저장도 검사한다. ConnectorActionExecutionMigrationTest는 H2에서 DDL을 실행하고 표와 컬럼, nullable UNIQUE ticket_id와 supersedes_unknown_action_id, 일반 request_key 인덱스, action 삭제 cascade 및 연결·바인딩·선조 FK 부재를 확인한다. MySQL 검사는 기존 스크립트로 DDL과 엔티티 일치를 확인한다.
NULL keyId로 저장한 평문은 enabled(true/false)에서 모두 원문으로 읽고 해시 변조를 거절해야 한다. 비NULL keyId는 enabled(false)에서도 실제 open을 거쳐야 하며 key를 쓸 수 없으면 거절해야 한다. 세 seal 결과 중 한 keyId만 다른 경우와 enabled(true)의 seal/open Optional.empty는 각각 저장 행 0개 또는 읽기 실패를 단언한다. key 삭제와 KEK 부재는 실제 DataKeyService의 forgetCachedKeys()로 캐시를 비운 뒤 또는 새 인스턴스로 검증해 기존 메모리 key가 실패를 숨기지 못하게 한다.
같은 vector의 누락·추가·중복 키, 숫자 decimal, 상한 초과와 normalization 전후를 검사한다. 양의 decimal과 filledQuantity의 0 허용을 구분한다.
테스트는 가짜 자격 증명과 로컬 HTTP 서버를 사용하며 실제 거래 주소에 요청하면 실패한다.

## 검증

```bash
cd backend && ./gradlew test --tests '*ConnectorActionExecution*'
```

```bash
cd backend && ./gradlew qualityCheck
```

```bash
node scripts/check-migration-versions.mjs
```

```bash
scripts/check-mysql-migration.sh
```

각 명령의 종료 코드가 0이어야 한다. 새 테스트가 실제로 발견되고 실행된 건수를 확인한다.

## 변경 파일

| 파일 | 변경 |
| --- | --- |
| `backend/src/main/java/com/bifos/assistant/connector/domain/ConnectorActionExecution.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/connector/infra/ConnectorActionExecutionRepository.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/connector/application/ConnectorExecutionSnapshot.java` | 신규 |
| `backend/src/main/resources/db/migration/V20261010043702__connector_action_execution.sql` | 신규 |
| `backend/src/test/java/com/bifos/assistant/connector/ConnectorActionExecutionTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/connector/ConnectorActionExecutionMigrationTest.java` | 신규 |
| `backend/docs/data-schema.md` | 수정 |
| `docs/features/connector-policy.md` | 수정 |
| `docs/adr/ADR-20261010-financial-execution-guard.md` | 신규 |
| `docs/adr/INDEX.md` | 수정 |
| `test/fixtures/financial-approval-v1.json` | 신규 |

