# Phase 06. 비밀 칸의 앞부분은 16자 이상일 때 4자만 저장한다

**Execution profile**: deep

## 목표

비밀 칸에서 저장하고 주인 응답에 내는 앞부분을 줄인다. 지금은 9자 이상이면 앞 8자를 저장해 9자에서 16자 사이의 비밀은 절반 이상이 DB 와 응답에 남는다.

**범위 외**: 응답의 칸 이름 `secretPrefixes` 와 `fields` 열의 JSON 모양은 바꾸지 않는다.

## 컨텍스트

- 규칙은 `backend/src/main/java/com/bifos/assistant/connector/application/ConnectorValues.java` 의 `SECRET_PREFIX_LENGTH`(지금 8)와 `stored` 에 있다
- `connector_connection.fields` 는 `VARCHAR(4000)` 의 JSON 텍스트 `{"values": {...}, "secretPrefixes": {...}}` 다. 읽고 쓰는 것은 `backend/src/main/java/com/bifos/assistant/connector/infra/ConnectionFieldsConverter.java` 다
- 기존 행은 앞 8자를 갖고 원래 길이를 남기지 않았다. 짧은 비밀의 행을 골라낼 수 없어 모두 비운다. 비워도 연결은 동작한다. 원문은 profile `.env` 에 있다
- 마이그레이션 검사는 H2 의 MySQL 모드에서 모든 마이그레이션을 돌린다. JSON 을 고치는 SQL 을 두 DB 에 함께 쓸 수 없으므로 Java 마이그레이션으로 쓴다. 본보기는 `backend/src/main/java/db/migration/V35__DropAgentTokenUserId.java` 다. 다음 번호는 V39 다. `ls backend/src/main/resources/db/migration backend/src/main/java/db/migration` 로 V39 가 비어 있는지 확인한다
- 웹은 `web/src/lib/connection-route.ts` 에서 앞부분 길이가 8 을 넘으면 응답을 거절한다. `web/src/components/connector/connector-connection-panel.tsx` 의 `shown` 은 `connection.secretPrefixes[field.key]` 가 있는 비밀 칸만 그린다. 앞부분이 없는 비밀 칸은 아예 그려지지 않는다
- 응답은 `values` 와 `secretPrefixes` 뿐이다. 앞부분이 없는 비밀 칸이 입력됐는지 비워 둔 선택 칸인지 응답만으로는 알 수 없다. 응답에 칸을 더하지 않는다
- `test/browser/connector-connection.spec.ts` 의 대역 `pending` 은 `secretPrefixes: { token: "demo_ok_" }` 로 8자다. 길이 검사를 4 로 바꾸면 이 응답이 거절된다

**근거 문서**: `docs/connectors.md` 의 「저장과 비밀값」 과 「connector.json」, `docs/data-schema.md` 의 「connector_connection」

## 의도 메모

- 앞부분을 아예 없애지 않는다. 주인이 어느 토큰을 넣었는지 구분하는 데 쓴다. 16자 이상에서 4자는 원문의 4분의 1 이하다
- 기존 행의 앞부분을 4자로 자르지 않는다. 원래 길이가 16자 미만이었는지 알 수 없다

## 작업 항목

### 1. `ConnectorValues` 의 규칙

- 상수를 둘로 나눈다. `SECRET_PREFIX_LENGTH = 4`, `SECRET_PREFIX_MIN_VALUE_LENGTH = 16`
- `stored`: 비밀 칸은 `value.length() >= SECRET_PREFIX_MIN_VALUE_LENGTH` 일 때만 `value.substring(0, SECRET_PREFIX_LENGTH)` 를 넣는다. Javadoc 을 새 규칙으로 고친다
- 앞 4자 자리에서 대리 쌍이 갈리면 그 앞에서 자른다. `AgentRunner.clip` 의 `Character.isHighSurrogate` 판정을 본보기로 쓴다

### 2. `V39__ClearConnectorSecretPrefixes` (신규)

`backend/src/main/java/db/migration/V39__ClearConnectorSecretPrefixes.java`

- `BaseJavaMigration` 을 상속한다. `SELECT id, fields FROM connector_connection` 으로 읽고, 행마다 `tools.jackson` 의 `JsonMapper` 로 읽어 `secretPrefixes` 를 빈 객체로 바꾼 뒤 `UPDATE connector_connection SET fields = ? WHERE id = ?` 로 쓴다. `values` 는 그대로 둔다. `updated_at` 은 바꾸지 않는다
- `secretPrefixes` 가 이미 비었으면 쓰지 않는다. JSON 으로 읽지 못하는 행이 있으면 행 번호만 담은 `IllegalStateException` 으로 멈춘다. 열의 원문을 예외에 싣지 않는다
- 클래스 Javadoc 에 SQL 이 아니라 Java 로 쓴 까닭을 적는다

### 3. 웹의 길이 검사와 문구

- `web/src/lib/connection-route.ts`: `prefix.length > 8` 을 `prefix.length > 4` 로 바꾼다
- `web/src/components/connector/connector-connection-panel.tsx` 의 `shown`: 비밀 칸은 앞부분이 있으면 지금처럼 앞부분을 보인다. 앞부분이 없고 `connection.status` 가 `DISCONNECTED` 가 아니며 `field.required` 이면 값 자리에 「입력됨」 을 보인다. 필수 칸은 등록됐다면 반드시 값이 있기 때문이다. 앞부분이 없는 선택 비밀 칸은 지금처럼 그리지 않는다. 「8자」 를 적은 문구가 있으면 고친다

### 4. 이 phase 를 검증하는 테스트

- `backend/src/test/java/com/bifos/assistant/connector/ConnectorConnectionServiceTest.java`: 앞 8자를 기대하는 단언(`"demo_ok_"`, `"demo_zz_"`, `"12345678"`, `"11111111"` 과 255~257 줄 부근의 8자와 9자 경계 검사)을 새 규칙으로 고친다. 경계 검사는 15자 비밀이 `secretPrefixes` 에 없고 16자 비밀이 앞 4자로 있음을 본다. 시험 커넥터의 토큰이 16자 미만이면 기대값은 빈 맵이다. 시험 값의 길이를 세어 기대값을 정한다
- `backend/src/test/java/com/bifos/assistant/connector/ConnectorConnectionControllerTest.java`: `jsonPath("$.secretPrefixes.token").value("demo_ok_")` 를 새 규칙의 값으로 고친다
- 새 파일 `backend/src/test/java/com/bifos/assistant/connector/ConnectorSecretPrefixMigrationTest.java`: `ConnectorConnectionMigrationTest` 를 본보기로 V38 까지 올린 뒤 `{"values":{"family":"x"},"secretPrefixes":{"token":"fab_abcd"}}` 행과 `{"values":{},"secretPrefixes":{}}` 행을 넣고 V39 를 올린다. 첫 행이 `{"values":{"family":"x"},"secretPrefixes":{}}` 가 되고 둘째 행은 그대로임을 본다
- `test/e2e/scenarios/connector.ts`: 「연결을 확인하면 READY 이고 비밀은 앞 8자만 보인다」 단계의 이름과 `DEMO_TOKEN_OK.slice(0, 8)` 기대값을 새 규칙으로 고친다. `DEMO_TOKEN_OK` 의 길이를 세어 16자 이상이면 앞 4자, 아니면 `secretPrefixes.token` 이 없음을 본다
- `test/browser/connector-connection.spec.ts`: 대역 `pending` 의 `secretPrefixes` 를 `{ token: "demo" }`(4자)로 고치고 앞부분을 보는 단언을 맞춘다. 검사 하나를 더한다. `secretPrefixes: {}` 이고 `status: "READY"` 인 응답에서 필수 비밀 칸 자리에 「입력됨」 이 보인다. 같은 응답이 `DISCONNECTED` 이면 보이지 않는다
- 새 마이그레이션 검사는 `ConnectorConnectionMigrationTest` 가 `migrate("37")` 뒤 `migrate("38")` 로 대상 판을 고정하는 방식을 그대로 쓴다. 그 파일은 이미 대상 판을 고정하므로 고치지 않는다

## 검증

```bash
# cwd: backend/
./gradlew test --tests '*ConnectorConnectionServiceTest' --tests '*ConnectorConnectionControllerTest' --tests '*ConnectorSecretPrefixMigrationTest' --tests '*MigrationTest'
./gradlew test
```

```bash
# cwd: web/
pnpm typecheck
pnpm test:browser connector-connection
```

```bash
# cwd: 저장소 root
node test/e2e/run.ts
! grep -rn "앞 8자" docs/connectors.md docs/data-schema.md web/src
scripts/check-public-safe.sh
```

브라우저 검사 전에 다른 브라우저 검사가 돌고 있지 않은지 team-lead 에게 확인한다. 모두 종료 코드 0 이어야 한다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/connector/application/ConnectorValues.java` | 수정 |
| `backend/src/main/java/db/migration/V39__ClearConnectorSecretPrefixes.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/connector/ConnectorConnectionServiceTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/connector/ConnectorConnectionControllerTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/connector/ConnectorSecretPrefixMigrationTest.java` | 신규 |
| `web/src/lib/connection-route.ts` | 수정 |
| `web/src/components/connector/connector-connection-panel.tsx` | 수정 |
| `test/e2e/scenarios/connector.ts` | 수정 |
| `test/browser/connector-connection.spec.ts` | 수정 |
