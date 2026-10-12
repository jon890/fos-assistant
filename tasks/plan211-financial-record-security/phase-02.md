# Phase 02. 승인 줄의 인자와 결과 본문을 사용자 데이터 key 로 암호화한다

**Execution profile**: deep

## 목표

`connector_action.args_json` 과 `result_text` 를 `chat_message.content` 와 같은 사용자 데이터 key(`TextCipher`)로 저장할 때 암호화한다.
금융 줄만이 아니라 모든 승인 줄이 대상이다. 저장과 읽기 경로가 하나라 금융만 따로 두면 분기가 늘고, 네이버 블로그 글 같은 쓰기 인자도 사용자 본문이다.
금융 줄은 암호화를 필수로 하고, 금융이 아닌 줄은 암호화가 꺼져 있으면 지금처럼 평문으로 저장한다.
이미 있는 평문 승인 줄은 기동 때 한 번 훑어 암호화한다.

**범위 외**: 공개 승인 카드, 판정 줄의 해시 칸(`args_sha256`, `dedupe_key`)과 `connector_action_execution.request_key`. 해시 칸은 아래 「남은 위험」 에 적는다.

## 컨텍스트

**근거 문서**: `backend/docs/adr/ADR-20261008-data-encryption.md`, `backend/docs/data-schema.md` 의 「본문 칸과 운영 조회」 와 「connector_action」, `docs/adr/ADR-20261010-financial-execution-guard.md`

- 본보기
  - 엔티티: `chat/domain/ChatMessage.java` 의 저장 칸과 옆 key 칸, 처음 꺼낼 때 푸는 접근자, `attachOpener`
  - 읽기: `chat/infra/ChatMessageLoadListener.java` 가 `POST_LOAD` 에 복호화 도구를 붙인다
  - 금융 실행 줄: `connector/application/ConnectorExecutionSnapshot.java` 의 `aad`, `openColumn`. 실패는 읽기와 저장을 거절한다
  - 기존 평문 보정: `memory/application/MemoryContentBackfill.java`
- 쓰기 자리
  - 인자: `ConnectorPolicyService` 의 `action.awaitApproval(argsJson, ...)` 와 `ConnectorFinancialProducer` 의 `awaitApproval` 뒤 `saveAndFlush`. 둘 다 아직 번호가 없는 줄에 평문을 넣는다
  - 결과: `ConnectorActionApproval.record` 와 `ConnectorFinancialApproval` 의 결과 기록이 `succeed`, `fail` 로 적는다. 줄은 이미 번호가 있다
- 읽기 자리: `ConnectorActionView.from`, `ConnectorActionDetails.hiddenArgs` 와 `resultOf`, `ConnectorActionApproval`(실행 인자), `ConnectorExecutionSnapshot.capture` 와 `open`(인자 해시 대조), `ConnectorActionResultSource`(결과를 대화에 전한다). 본문 칸을 JPQL 로 직접 읽는 질의는 없다
- 관리자에게 승인 줄의 인자와 결과를 내는 경로는 없다. `data-schema.md` 가 「인자 원문은 주인에게만 보인다. 관리자 목록과 로그에는 싣지 않는다」 로 정해 두었다. 이 phase 는 그 규칙을 바꾸지 않는다

## 의도 메모

- 옆 key 칸은 `args_key_id`, `result_key_id` 둘이다. 쓰는 때가 다르다
- AAD 는 `connector_action:<id>:column:<칸 이름>:user:<user_id>` 다. 실행 줄의 형식과 맞춘다
- 인자는 번호가 생긴 뒤에 암호화한다. `awaitApproval` 은 상태와 만료만 정하고, 같은 트랜잭션에서 `saveAndFlush` 로 번호를 얻은 뒤 암호문을 적는다. 데이터베이스에 평문이 한 번도 쓰이지 않는다
- 엔티티의 `argsJson()` 과 `resultText()` 는 푼 글을 낸다. 저장된 글은 `storedArgsJson()`, `storedResultText()` 로 따로 낸다
- **풀지 못한 인자는 실행하지 않는다.** 표시에는 「읽을 수 없는 인자입니다.」 를 쓰되, 실행 경로는 그 글을 인자로 쓰지 않는다. 승인은 `NOT_EXECUTABLE` 로 끝내고, 금융 snapshot 은 지금처럼 해시 대조에서 거절한다. 실행 전에 푼 인자의 SHA-256 을 `args_sha256` 과 대조한다
- 금융 줄은 `args_key_id` 가 없으면 승인하지 않는다. 실행 줄의 `content_key_id` 검사와 같은 자리다
- 풀지 못한 결과는 대화에 「읽을 수 없는 결과입니다.」 로 전한다. 요청은 실패하지 않는다
- 기존 평문 줄은 기동 때 `args_key_id` 가 비어 있고 `args_json` 이 있는 줄, `result_key_id` 가 비어 있고 `result_text` 가 있는 줄을 번호 순으로 훑는다. 줄마다 쓰기 잠금으로 다시 읽어 조건을 다시 본 뒤 한 트랜잭션으로 고친다. 암호화가 꺼져 있으면 남은 줄 수만 경고한다. 사용자가 적어 줄 수가 작다
- 이 판 앞으로 되돌리지 않는다. 옛 코드는 key 칸을 몰라 암호문을 인자로 Hermes 에 보낸다. 데이터 암호화 ADR 의 「결과」 와 같은 조건이다

## 작업 항목

1. 마이그레이션: `V<UTC 시각>__connector_action_body_key.sql` 에 DDL 만 둔다. `args_key_id BIGINT NULL`, `result_key_id BIGINT NULL`. 외래 키는 두지 않는다
2. `ConnectorAction`: 저장 칸 이름을 바꾸고 옆 key 칸 둘, `@Transient` 평문과 복호화 도구, `sealArgs`/`sealResult` 를 둔다. `awaitApproval(expiresAt)` 은 인자를 받지 않는다. 엔티티 접근자는 Lombok 으로 만든다
3. `connector.infra` 에 `ConnectorActionBodies`(AAD, seal, open)와 `ConnectorActionLoadListener`(`POST_LOAD`) 를 둔다
4. 쓰기 자리 넷을 바꾼다. 금융 줄은 `seal` 이 빈 값이면 준비를 거절한다
5. 읽기 자리의 실패 처리를 의도 메모대로 둔다
6. `ConnectorActionBodyBackfill`(기동, `ApplicationReadyEvent`, 기동 정리보다 뒤)을 더한다
7. 문서: `backend/docs/data-schema.md` 의 「본문 칸과 운영 조회」 표에서 `connector_action` 을 「함」 으로 바꾸고 옆 칸을 적는다. 「connector_action」 절에 AAD 와 평문 줄의 규칙을 적는다. `backend/docs/adr/ADR-20261008-data-encryption.md` 의 「못 막는 것」 의 「메시지 밖의 칸」 에서 승인 기록을 뺀다. `docs/features/connector-policy.md` 의 남은 문제 목록에서 첫째 줄을 지운다
8. 운영 조회 계정의 칸 권한은 바뀌지 않는다. 두 칸은 이미 본문 칸 표에 있다

## 테스트

- `ConnectorActionBodyEncryptionTest`(신규, `@BackendIntegrationTest`)
  - 승인 줄을 만들면 데이터베이스의 `args_json` 이 `v1.` 로 시작하고 `args_key_id` 가 있다. 승인 카드 응답의 인자는 평문을 가린 글이다
  - 승인해 실행하면 Hermes 가짜가 받은 인자가 원문과 같고, `result_text` 가 암호문이며 대화에 전한 결과가 평문이다
  - 두 승인 줄의 `args_json` 을 맞바꾸면 둘 다 승인할 수 없고 실행 호출이 0회다
  - 암호화가 꺼진 설정에서 금융이 아닌 줄은 평문과 null key 로 저장되고, 금융 준비는 거절된다
  - 기동 보정이 평문 줄을 암호화하고 다시 돌려도 바뀌지 않는다. PENDING 평문 줄을 보정한 뒤에도 승인하면 같은 인자로 실행된다
- 금융 왕복: `FinancialConnectorProducerRoundTripTest` 에서 승인 줄의 두 칸이 암호문임을 확인하고, `args_key_id` 가 없는 금융 줄의 승인이 금융 POST 0회로 거절되는 경우를 더한다
- MySQL: `scripts/check-mysql-migration.sh` 로 새 마이그레이션과 `RepositoryQueryMysqlTest` 를 돌린다

## 검증

```bash
cd backend && ./gradlew test --tests '*ConnectorActionBodyEncryptionTest' --tests '*FinancialConnectorProducer*' --tests '*ConnectorAction*' --tests '*ConnectorPolicy*'
scripts/check-mysql-migration.sh
scripts/check-local.sh --fast
```

## 남은 위험

`args_sha256` 과 `request_key`, `scope_sha256` 은 key 없는 SHA-256 이다.
금융 인자는 종목, 방향, 수량, 가격처럼 값의 범위가 좁아, 데이터베이스만 가진 사람이 해시를 대입으로 되돌릴 수 있다.
이 phase 는 본문 칸만 다룬다. 해시 칸을 사용자별 key 로 바꿀지는 별도로 정한다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/resources/db/migration/V*__connector_action_body_key.sql` | 신규 |
| `backend/src/main/java/com/bifos/assistant/connector/domain/ConnectorAction.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/connector/infra/ConnectorActionBodies.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/connector/infra/ConnectorActionLoadListener.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/connector/application/ConnectorPolicyService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/connector/application/ConnectorFinancialProducer.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/connector/application/ConnectorActionApproval.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/connector/application/ConnectorFinancialApproval.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/connector/application/ConnectorActionDetails.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/connector/application/ConnectorActionBodyBackfill.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/connector/ConnectorActionBodyEncryptionTest.java` | 신규 |
| `backend/docs/data-schema.md`, `backend/docs/adr/ADR-20261008-data-encryption.md`, `docs/features/connector-policy.md` | 수정 |
