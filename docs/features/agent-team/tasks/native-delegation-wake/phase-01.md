# Phase 01. 등록 줄의 결과 칸과 깨울 자식 판정

**Execution profile**: standard

## 목표

`hermes_session_binding` 에 결과 칸을 더하고, 등록할 때 이 자식을 깨울지(`wake_eligible`)를 한 번 정해 적는다. 옛 플러그인의 등록은 모두 거짓이라 배포만으로는 아무것도 바뀌지 않는다.

**범위 외**: 끝 소식 경로(phase 2), 자동 turn 결과(phase 3), 훑기(phase 4), 정리와 이력 API(phase 5), 화면(phase 6), fos-ctx 의 hook 코드(다음 PR).

## 컨텍스트

**요구사항**: REQ14(PR6 만 배포된 동안 지금과 같다)

**근거 문서**: `docs/features/agent-team/spec.md`(DECISION35, DECISION38), `docs/features/agent-team/design.md` 의 「PR6 내장 위임 결과」 → 「깨울 자식」, 「등록 줄의 새 칸」, `docs/adr/ADR-20261117-native-delegation-wake.md`

- 등록 경로: `backend/src/main/java/com/bifos/assistant/mcp/presentation/SubagentSessionController.java` 가 `SubagentRegistration.verify` 로 본문을 읽고 `SubagentSessionRegistrar.register(profile, root, parent, child)` 를 부른다.
- `SubagentRegistration` 은 지금 `child_subagent_id`, `parent_subagent_id` 를 「문자열이나 null」 인지만 보고 버린다. 모르는 키는 무시한다.
- `SubagentSessionRegistrar.resolveOrigin` 은 부모 session 의 등록이 있으면 그 origin 을, 없으면 `DelegationParentResolver.resolve(profile, root)` 로 루트에서 도는 실행을 고른다.
- 암호화는 `crypto.domain.TextCipher` 의 `seal(ownerUserId, aad, plain)` 과 `open(keyId, ownerUserId, aad, stored)` 다. 꺼져 있으면 `seal` 이 빈 값이고 평문을 저장한다. 본보기는 `chat/conversation/infra/ConversationTitles.java` 다.
- 마이그레이션 버전은 이미 예약했다: `backend/src/main/resources/db/migration/V20261105074414__hermes_session_binding_native_result.sql`. 다른 이름으로 만들지 않는다. main 을 합친 뒤에는 orch 지침대로 같은 모듈과 이름으로 다시 예약해 `renewed` 면 파일 이름만 바꾼다.
- 새 칸의 설명은 마이그레이션 안의 `COMMENT` 로 단다(`V20261105074411__chat_message_steer.sql` 이 본보기). `db/comments/*.tsv` 는 고치지 않는다.
- 테스트 명령은 worker 지침대로 무거운 검사 대기열로 감싸 돌린다. 아래 「검증」 의 명령은 감싸기 전 모양이다.

## 의도 메모

- `wake_eligible` 은 끝 소식 때 다시 풀지 않는다. 압축이나 지운 대화로 답이 바뀌지 않게 하려는 것이다(설계 DECISION34).
- `reports_stop` 이 없거나 거짓이면 늘 거짓이다. PR7 전의 모든 등록이 깨울 대상이 되면 1시간 뒤 알림 줄이 쏟아진다(설계 DECISION35).
- `goal_preview` 는 깨울 자식에만 적는다. 그래야 글 칸이 있는 줄은 늘 대화가 있어 대화 정리만으로 비울 수 있다.
- `result_key_id` 는 두 글 칸이 함께 쓴다. 이 phase 에서는 `goal_preview` 를 암호화할 때 적는다.

## 작업 항목

### 1. 마이그레이션 `V20261105074414__hermes_session_binding_native_result.sql`

DDL 만 둔다. 칸마다 한국어 `COMMENT` 를 단다(단위, 암호문 여부, `NULL` 의 뜻, enum 값).

| 칸 | 정의 |
| --- | --- |
| `wake_eligible` | `BOOLEAN NOT NULL DEFAULT FALSE` |
| `goal_preview` | `VARCHAR(512) NULL` |
| `stopped_at` | `DATETIME(6) NULL` |
| `result_status` | `VARCHAR(16) NULL` (`SUCCEEDED`, `FAILED`) |
| `result_text` | `LONGTEXT NULL` |
| `result_key_id` | `BIGINT NULL` |
| `result_delivered_at` | `DATETIME(6) NULL` |
| `timeout_noticed_at` | `DATETIME(6) NULL` |
| `notice_message_id` | `BIGINT NULL` |

색인: `idx_hermes_session_binding_origin (origin_execution_id)`, `idx_hermes_session_binding_wake (wake_eligible, result_delivered_at, created_at)`.

### 2. `orchestration/domain/HermesSessionBinding.java`

- 새 칸을 필드로 더한다. `wake_eligible` 만 넣을 때 쓰고(`updatable = false`), 나머지는 모두 `insertable = false, updatable = false` 다. 엔티티 저장은 새 칸을 쓰지 않고, 값은 `HermesSessionBindingRepository` 의 조건부 `@Modifying` update 로만 바꾼다.
- `result_status` 는 새 enum `orchestration.domain.type.NativeResultStatus { SUCCEEDED, FAILED }` 를 `@Enumerated(STRING)` 로 쓴다.
- 팩토리 `of(...)` 는 지금 모양을 두고, `wakeEligible` 을 받는 팩토리를 더한다. 클래스 Javadoc 의 「한 번 적은 줄은 바꾸지 않는다」 를 「origin 과 사용자 칸은 바꾸지 않는다. 결과 칸은 조건부 update 로만 바꾼다」 로 고친다.

### 3. `mcp/application/dto/SubagentRegistration.java`

- `reports_stop`: 없거나 `null` 이면 거짓, 불리언이면 그 값, 다른 타입이면 거절한다.
- `child_goal`: 없거나 `null` 이거나 문자열. 다른 타입이면 거절한다.
- `parent_subagent_id` 의 값을 레코드에 담는다(서명하지 않는다).
- 레코드에 `boolean reportsStop`, `String childGoal`, `String parentSubagentId` 를 더한다. 서명할 글은 바꾸지 않는다.

### 4. `mcp/presentation/SubagentSessionController.java` 와 `orchestration/application/SubagentSessionRegistrar.java`

- 컨트롤러가 새 값을 넘긴다.
- 등록기의 지금 4인자 `register` 는 새 인자를 기본값(`false`, `null`, `null`)으로 넘기는 오버로드로 남긴다. 기존 테스트 호출부를 바꾸지 않는다.
- `resolveOrigin` 이 「부모 등록으로 풀었는가」 를 함께 돌려주게 한다(작은 record).
- 새로 저장할 때만 `wake_eligible` 을 정한다. 이미 있는 줄은 바꾸지 않는다. 모두 맞아야 참이다.
  1. `reportsStop` 이 참
  2. 부모 등록이 아니라 루트로 풀었고 `parentSubagentId` 가 비었다
  3. origin 실행의 `parentExecutionId()` 가 null 이고 `conversationId()` 가 있다
  4. 그 대화의 `purpose` 가 `ConversationPurpose.CHECK` 가 아니다
  5. origin 실행의 `agentId()` 가 대화의 `agentId()` 와 같다
- 참이고 `childGoal` 이 비지 않았으면 앞 40 code point 를 저장(`saveAndFlush` 로 줄 번호를 얻은) 뒤 같은 트랜잭션에서 봉해 새 `@Modifying` 질의 `sealGoal(id, goalPreview, keyId)`(`goal_preview IS NULL` 인 줄만)로 적는다. AAD 는 `hermes_session_binding:<id>:goal_preview:user:<user_id>` 다. 봉하기와 열기는 새 `orchestration/infra/SubagentResultTexts.java` 가 맡는다(`ConversationTitles` 모양). 암호화가 꺼져 있으면 평문, key 칸은 비운다.
- 이 저장은 지금처럼 짧은 트랜잭션 안이다. 유일 제약 경쟁 뒤 다시 읽는 길은 그대로다.

### 5. 문서

- `backend/docs/data-schema.md` 의 「hermes_session_binding」: 「한 번 적은 줄은 바꾸지 않는다」 를 위 Javadoc 과 같은 뜻으로 고치고, 새 칸의 뜻과 깨울 자식 판정을 설계로 링크한다.
- `hermes/plugins/fos-ctx/README.md` 의 「하위 에이전트 session 등록 계약」: 선택 칸 `reports_stop`(불리언), `child_goal`(글)을 더한다. 서명하지 않고, 없으면 지금과 같다고 적는다.

### 6. 테스트

- `backend/src/test/java/com/bifos/assistant/mcp/application/SubagentRegistrationTest.java`: 새 칸이 없으면 `reportsStop=false`, `childGoal=null`. `reports_stop` 이 문자열이면 거절. `child_goal` 이 숫자면 거절.
- `backend/src/test/java/com/bifos/assistant/orchestration/SubagentSessionRegistrarTest.java`: 판정 다섯 조건마다 거짓이 되는 사례 하나와 모두 맞는 사례. 모두 맞으면 `goal_preview` 가 암호문이고(테스트 설정은 암호화가 켜져 있다, `backend/src/test/resources/application-test.yml`), `SubagentResultTexts` 로 열면 `child_goal` 의 앞 40 code point 다. `result_key_id` 가 찼다. 같은 자식의 두 번째 등록이 `wake_eligible` 을 바꾸지 않는다.
- `backend/src/test/java/com/bifos/assistant/architecture/StoredEnumNamesTest.java`: 저장하는 enum 목록에 `NativeResultStatus` 를 더한다.
- `backend/src/test/java/com/bifos/assistant/mcp/SubagentSessionEndpointTest.java`: 옛 본문(새 칸 없음)의 등록이 `201` 이고 `wake_eligible` 이 거짓이다(REQ14). 새 칸을 넣은 등록은 참이다.

## 검증

```bash
cd backend && ./gradlew test --tests '*SubagentRegistrationTest' --tests '*SubagentSessionRegistrarTest' --tests '*SubagentSessionEndpointTest' --tests '*JpaAuditing*' --tests '*StoredEnumNamesTest'
cd backend && ./gradlew checkstyleMain checkstyleTest spotlessCheck
node scripts/checks/check-migration-versions.mjs origin/main
node scripts/checks/check-file-length.mjs
node --test test/repo/migration-collation.test.ts test/repo/migration-immutable.test.ts test/repo/doc-links.test.ts test/repo/doc-code-references.test.ts
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/resources/db/migration/V20261105074414__hermes_session_binding_native_result.sql` | 신규 |
| `backend/src/main/java/com/bifos/assistant/orchestration/domain/HermesSessionBinding.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/orchestration/domain/type/NativeResultStatus.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/orchestration/infra/SubagentResultTexts.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/orchestration/infra/HermesSessionBindingRepository.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/architecture/StoredEnumNamesTest.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/mcp/application/dto/SubagentRegistration.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/mcp/presentation/SubagentSessionController.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/orchestration/application/dto/SubagentRegistrationOptions.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/orchestration/application/SubagentSessionRegistrar.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/mcp/application/SubagentRegistrationTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/orchestration/SubagentSessionRegistrarTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/mcp/SubagentSessionEndpointTest.java` | 수정 |
| `backend/docs/data-schema.md` | 수정 |
| `hermes/plugins/fos-ctx/README.md` | 수정 |
