# Phase 03. 이미 있는 평문 줄을 배치로 암호화하고, 되돌릴 때 쓸 풀기 배치를 둔다

**Execution profile**: deep

## 목표

KEK 를 넣기 전에 저장한 평문 줄을 조금씩 암호화한다. 대상은 `chat_message.content` 와 phase 01, 02 의 칸이다.
옛 판으로 되돌려야 할 때를 위해, 같은 배치를 반대로 돌려 암호문을 평문으로 되돌리는 방식도 둔다.

**범위 외**: 알림, 할 일, 승인 기록, 실행 사건, 먼저 살펴보기 결과, 일반 Memory 본문. 민감 Memory 는 이미 `MemoryContentBackfill` 이 기동 때 암호화한다.

## 컨텍스트

**근거 문서**: `backend/docs/adr/ADR-20261008-data-encryption.md` 의 「결정」 표(「이 결정 앞의 평문 메시지」 줄)와 「결과」, `backend/docs/data-schema.md` 의 「본문 칸과 운영 조회」

- 평문 줄은 옆 key 칸이 null 이고 본문이 빈 글이 아닌 줄이다. 대상은 다섯 칸이다

  | 표 | 본문 칸 | key 칸 | 주인 | 줄을 잡는 잠금 |
  | --- | --- | --- | --- | --- |
  | `chat_message` | `content` | `content_key_id` | 대화의 `user_id` | `ConversationRepository.findByIdForMessageWrite` 로 대화 줄 |
  | `chat_pending_message` | `content` | `content_key_id` | `user_id` | 같다 |
  | `conversation` | `title` | `title_key_id` | `user_id` | 같다 |
  | `agent_execution` | `output_text` | `output_key_id` | `user_id` | `AgentExecutionRepository.lockById` |
  | `task` | `title`, `instruction` | `title_key_id`, `instruction_key_id` | `owner_user_id` | 새 쓰기 잠금 조회 |

- AAD 와 엔티티의 칸 이름은 phase 01, 02 와 `ChatMessageContents` 가 정한 것을 그대로 쓴다. 배치가 새 규칙을 만들지 않는다
- 본보기는 민감 Memory 의 기동 보정이다. `backend/src/main/java/com/bifos/assistant/memory/application/MemoryContentBackfill.java` 가 대상 번호를 읽고 `MemoryContentSealer` 가 줄마다 쓰기 잠금으로 다시 읽어 한 트랜잭션으로 고친다. 실패하면 클래스 이름만 로그에 남긴다
- 주기 작업의 끄기 방식은 `backend/src/main/java/com/bifos/assistant/chat/application/ConversationPurger.java` 의 `@Scheduled(cron = "${assistant.chat.purge-cron}")` 와 시험 설정의 `"-"` 를 따른다

## 의도 메모

- 기동 때 한꺼번에 돌리지 않는다. 메시지가 많으면 기동이 늦어지고, 중간에 멈추면 처음부터 다시 읽는다. 매분 정한 수만큼 번호 순으로 고친다. key 칸이 null 인 줄만 고르므로 멈춘 자리에서 저절로 이어진다
- 줄마다 쓰기 잠금으로 다시 읽고 조건을 다시 본다. 그 사이 사용자가 그 대화를 지웠거나 정리 작업이 지웠으면 건너뛴다
- 되돌리기는 설정 하나로 방향을 바꾼다. `assistant.data-encryption.backfill` 이 `SEAL` 이면 암호화하고, `UNSEAL` 이면 key 칸이 있는 줄을 평문으로 되돌리고, `OFF` 면 돌지 않는다. 기본은 `SEAL` 이다. `UNSEAL` 은 옛 판으로 내리기 직전에만 켜고, 그동안 새 메시지는 계속 암호화된다는 것을 운영 문서에 적는다. 그래서 내리기 전에 새 쓰기를 멈춘다
- 풀지 못하는 줄(데이터 key 가 없거나 태그가 맞지 않음)은 `UNSEAL` 에서 건너뛰고 수만 센다
- 진행은 칸마다 남은 줄 수를 info 로그에 남긴다. 본문은 남기지 않는다

## 작업 항목

### 1. 설정

`assistant.data-encryption.backfill`(`SEAL`, `UNSEAL`, `OFF`), `assistant.data-encryption.backfill-cron`(기본 `"40 * * * * *"`), `assistant.data-encryption.backfill-batch`(기본 200)를 `backend/src/main/java/com/bifos/assistant/crypto/infra/DataEncryptionProperties.java` 에 더한다. `application.yml` 에 기본값을, `application-test.yml` 에 `backfill-cron: "-"` 를 둔다.
방향 값의 enum 은 저장하지 않으므로 `crypto.application.model` 에 둔다(`backend/AGENTS.md` 의 「enum 은 저장 여부로 둘 곳을 정한다」).

### 2. 배치

- `chat.application.ChatPlaintextBackfill`(신규): `chat_message`, `chat_pending_message`, `conversation.title` 을 맡는다. 저장소에 `findIdsByContentKeyIdIsNullAndIdGreaterThan...` 처럼 번호만 읽는 질의를 더하고, 줄마다 `ChatPlaintextSealer`(신규, `@Transactional`)가 대화 줄을 잠근 뒤 엔티티의 `seal` 로 고친다
- `usage.application.ExecutionOutputBackfill`(신규)과 `task.application.TaskPlaintextBackfill`(신규)도 같은 모양이다
- 각 배치는 `TextCipher.enabled()` 가 거짓이면 남은 줄 수만 경고하고 끝난다
- `UNSEAL` 이면 key 칸이 있는 줄을 골라 풀어서 평문과 null 로 적는다

### 3. 운영 문서

`docs/self-hosting.md` 의 환경 변수 표에 세 설정을, `backend/docs/adr/ADR-20261008-data-encryption.md` 의 「결정」 표에 배치와 되돌리기 줄을 더한다. 「이 결정 앞의 판으로 되돌리지 않는다」 를 「`UNSEAL` 로 모두 되돌린 뒤에만 내린다」 로 바꾼다.

### 4. 시험

- `backend/src/test/java/com/bifos/assistant/chat/ChatPlaintextBackfillTest.java`(신규, `@BackendIntegrationTest`)
  - `content_key_id` 를 null 로 둔 평문 메시지 세 줄 가운데 배치 크기 2 로 한 번 돌리면 두 줄만 암호화되고, 다시 돌리면 나머지 한 줄이 암호화된다. 읽으면 셋 다 평문이다
  - 지운 대화의 평문 줄도 정리 전이면 암호화된다
  - `UNSEAL` 로 돌리면 암호문 줄이 평문과 null 로 돌아간다. 데이터 key 를 지운 줄은 건너뛰고 그대로 남는다
- `backend/src/test/java/com/bifos/assistant/usage/ExecutionOutputBackfillTest.java`, `backend/src/test/java/com/bifos/assistant/task/TaskPlaintextBackfillTest.java`(신규): 평문 줄이 암호화되고 다시 돌려도 바뀌지 않는다

## 검증

```bash
cd backend && ./gradlew test --tests '*PlaintextBackfillTest' --tests '*ExecutionOutputBackfillTest'
cd backend && ./gradlew test
cd backend && ./gradlew qualityCheck
scripts/check-mysql-migration.sh
```

넷 다 실패 없이 끝난다. 마지막 명령은 Docker 가 있어야 돈다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/crypto/infra/DataEncryptionProperties.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/crypto/application/model/BackfillDirection.java` | 신규 |
| `backend/src/main/resources/application.yml` | 수정 |
| `backend/src/test/resources/application-test.yml` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/application/ChatPlaintextBackfill.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/application/ChatPlaintextSealer.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/infra/ChatMessageRepository.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/infra/ChatPendingMessageRepository.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/infra/ConversationRepository.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/usage/application/ExecutionOutputBackfill.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/usage/infra/AgentExecutionRepository.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/task/application/TaskPlaintextBackfill.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/task/infra/TaskRepository.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/ChatPlaintextBackfillTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/usage/ExecutionOutputBackfillTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/task/TaskPlaintextBackfillTest.java` | 신규 |
| `docs/self-hosting.md` | 수정 |
| `backend/docs/adr/ADR-20261008-data-encryption.md` | 수정 |
