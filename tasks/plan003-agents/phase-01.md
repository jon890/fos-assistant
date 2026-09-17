# Phase 01. 에이전트 표를 만들고 바인딩을 옮긴다

**Execution profile**: standard

## 목표

`agent` 표를 만들고 `hermes_profile_binding` 의 내용을 옮긴다.
사용자 하나가 여러 에이전트를 쓸 수 있게 되고, 에이전트마다 공개 범위가 생긴다.

**범위 외**

- 화면 작업은 phase-03 이 한다.
- 모델을 Hermes 에 물어 맞추는 것은 phase-02 가 한다.
- `career` profile 을 실제로 쓸 수 있게 만드는 운영 작업은 phase-04 가 한다.

## 컨텍스트

지금은 사용자 하나에 Hermes profile 하나가 붙는다.
`hermes_profile_binding` 의 `user_id` 에 유일 제약이 있어 둘 이상을 붙일 수 없다.

에이전트를 사이에 두는 이유와 기각한 대안은
`docs/adr/ADR-007-에이전트가-모델과-도구를-함께-정한다.md` 에 있다.
**그 문서를 먼저 읽는다.** 특히 공개 범위가 왜 보안 통제인지가 거기 있다.

따를 패턴을 경로로 짚는다.
`workspace` 가 공개 범위와 소유자를 이미 같은 방식으로 다룬다. 그 코드를 본보기로 삼는다.

| 무엇 | 경로 |
| --- | --- |
| 공개 범위 유형 | `backend/src/main/java/com/bifos/assistant/workspace/domain/WorkspaceVisibility.java` |
| 공개 범위를 가진 엔티티 | `backend/src/main/java/com/bifos/assistant/workspace/domain/Workspace.java` |
| 없는 것과 같은 오류로 답하는 조회 | `backend/src/main/java/com/bifos/assistant/workspace/application/WorkspaceService.java` |
| 옮길 대상 | `backend/src/main/java/com/bifos/assistant/credential/` 전체 |
| 실행 기록 | `backend/src/main/java/com/bifos/assistant/usage/` |

**근거 문서**: `docs/data-schema.md`, `docs/adr/ADR-007-에이전트가-모델과-도구를-함께-정한다.md`

## 의도 메모

- 접근 권한을 별도 연결 표로 두지 않는다. `visibility` 와 `owner_user_id` 로 정한다.
  `workspace` 가 같은 방식이고, 표를 하나 더 두면 같은 규칙이 두 방식으로 표현된다.
- 공개 범위에 기본값을 두지 않는다. 만드는 쪽이 반드시 고른다.
- 에이전트를 지우지 않는다. `enabled` 를 내려 쓰지 않게 한다.
  실행 기록이 그것을 가리키고 있고 기록은 남아야 한다.
- 마이그레이션이 에이전트 이름을 지어내지 않는다. profile 이름을 그대로 `code` 로 쓴다.
  더 나은 이름은 등록할 때 사람이 정한다.
- **옮긴 행의 공개 범위는 `PRIVATE` 이다.** 지금 그 profile 을 쓸 수 있는 사람이 한 명이므로,
  옮기면서 범위를 넓히지 않는다. 넓히는 것은 사람이 따로 결정한다.

## 작업 항목

### 1. 마이그레이션을 더한다

`backend/src/main/resources/db/migration/` 에 다음 번호로 만든다.
plan002 가 `V3__message_sender.sql` 을 쓰므로 **`V4`** 다.
`ls` 로 확인하고 가장 큰 번호 다음을 쓴다.

`agent` 표를 만든다.

| 칸 | 타입 | 제약 |
| --- | --- | --- |
| `id` | BIGINT AUTO_INCREMENT | PK |
| `code` | VARCHAR(64) | NOT NULL, UNIQUE |
| `name` | VARCHAR(100) | NOT NULL |
| `hermes_profile` | VARCHAR(64) | NOT NULL, UNIQUE |
| `api_base_url` | VARCHAR(255) | NOT NULL |
| `provider` | VARCHAR(64) | NOT NULL |
| `model` | VARCHAR(128) | NOT NULL |
| `model_synced_at` | DATETIME(6) | NULL |
| `cost_mode` | VARCHAR(20) | NOT NULL |
| `credential_scope` | VARCHAR(20) | NOT NULL |
| `visibility` | VARCHAR(20) | NOT NULL |
| `owner_user_id` | BIGINT | NULL |
| `enabled` | BOOLEAN | NOT NULL |
| `created_at` | DATETIME(6) | NOT NULL |

`model_synced_at` 은 phase-02 가 쓴다. 지금은 비워 둔다.

그다음 이관과 정리를 한다.

- `hermes_profile_binding` 의 행을 `agent` 로 옮긴다.
  `code` 와 `name` 은 `profile_name` 을 쓰고, `visibility` 는 `PRIVATE`,
  `owner_user_id` 는 그 행의 `user_id` 를 쓰고, `enabled` 는 참이다.
- `conversation` 과 `agent_execution` 에 `agent_id BIGINT NULL` 을 더한다.
- 두 표의 기존 행에 옮긴 에이전트의 `id` 를 채운다.
  `profile_name` 으로 맞춘다.
- `hermes_profile_binding` 표를 지운다. 내용을 모두 옮긴 뒤다.

### 2. `agent` 도메인을 만든다

`backend/src/main/java/com/bifos/assistant/agent/` 아래에 만든다.
`workspace` 패키지와 같은 층 구조를 쓴다.

- `domain/Agent.java`, `domain/AgentVisibility.java`
- `domain/CostMode.java` 와 `domain/CredentialScope.java` 는 `credential` 패키지에서 옮긴다.
- `infra/AgentRepository.java` — `findByCode`, `findByEnabledTrueOrderByCodeAsc`
- `application/AgentService.java` — `readableBy`, `requireReadable`, `requireById`

`requireReadable` 은 없는 에이전트와 남의 개인 에이전트를 **같은 오류**로 답한다.
`WorkspaceService.requireReadable` 이 그렇게 되어 있다. 존재를 알리는 것 자체가 누출이다.

`ErrorCode` 에 `AGENT_NOT_FOUND` 와 `AGENT_DISABLED` 를 더한다.

### 3. `credential` 패키지를 없앤다

`HermesProfileBinding`, `HermesProfileBindingRepository`, `HermesBindingController` 를 지운다.
`CostMode` 와 `CredentialScope` 는 `agent` 패키지로 옮긴다.

### 4. `ChatService` 가 에이전트로 라우팅한다

- `send` 가 `agentCode` 를 받는다.
- 새 대화면 그 에이전트를 `conversation` 에 기록한다. 없으면 `AGENT_NOT_FOUND` 다.
- 이어지는 대화는 `conversation` 에 기록된 에이전트를 쓴다. 요청이 바꾸지 못한다.
  작업 영역을 다루는 방식과 같다.
- `enabled` 가 거짓이면 `AGENT_DISABLED` 다.
- `HermesRunCommand` 에 넘기는 profile 이름과 주소를 에이전트에서 꺼낸다.

### 5. 실행 기록에 에이전트를 남긴다

`ExecutionRecorder` 가 `agent_id` 를 남긴다.
`provider` 와 `model` 도 바인딩 대신 에이전트에서 읽는다.

실행이 돌려준 `model` 이 profile 이름과 같으면 에이전트의 `model` 을 적는 규칙은 그대로 둔다.
이유는 `docs/data-schema.md` 에 있다.

### 6. 관리 엔드포인트를 만든다

`agent/presentation/AgentAdminController.java` 를 만든다.
지운 `HermesBindingController` 의 형태를 따르되 대상이 에이전트다.

| 메서드와 경로 | 하는 일 |
| --- | --- |
| `POST /api/v1/admin/agents` | 등록. admin 만 |
| `GET /api/v1/admin/agents` | 전체 목록. admin 만 |
| `PATCH /api/v1/admin/agents/{code}` | `enabled` 와 `visibility` 를 바꾼다. admin 만 |

등록 요청은 `code`, `name`, `hermesProfile`, `apiBaseUrl`, `provider`, `model`,
`costMode`, `credentialScope`, `visibility`, `ownerEmail` 을 받는다.
`visibility` 에 기본값을 두지 않는다.
`PRIVATE` 인데 `ownerEmail` 이 없으면 거절한다.

`agent/presentation/AgentController.java` 에 `GET /api/v1/agents` 를 만든다.
요청자가 쓸 수 있는 에이전트만 준다. `code`, `name`, `model`, `visibility` 를 담는다.

### 7. 이 phase 를 검증하는 테스트

`backend/src/test/java/com/bifos/assistant/agent/` 와 기존 `chat`, `usage` 테스트를 고친다.
`WorkspaceServiceTest` 가 같은 성격의 검사를 이미 하고 있다. 그 형태를 따른다.

- 사용자 하나에 에이전트 둘을 연결하고 둘 다 쓸 수 있다.
- 남의 개인 에이전트는 목록에 없고 실행도 `AGENT_NOT_FOUND` 다.
- 없는 에이전트 코드도 같은 `AGENT_NOT_FOUND` 다.
- 가족 공개 에이전트는 다른 구성원도 쓸 수 있다.
- `enabled` 가 거짓이면 `AGENT_DISABLED` 다.
- 이어지는 대화가 첫 에이전트를 유지한다.
- 실행 기록에 `agent_id` 가 남는다.
- `PRIVATE` 인데 소유자를 주지 않으면 등록이 거절된다.

## 검증

```bash
# cwd: 저장소 root
cd backend && ./gradlew test
```

```bash
# cwd: 저장소 root
grep -rn "HermesProfileBinding" backend/src && echo "실패: 옛 바인딩 참조가 남아 있다" || echo "통과"
```

```bash
# cwd: 저장소 root
node test/e2e/run.ts
```

e2e 가 Flyway 를 실제로 돌린다.
백엔드 테스트는 엔티티로 스키마를 만들어 마이그레이션과 어긋나도 통과하므로, e2e 가 그 어긋남을 잡는 자리다.
`test/e2e/scenarios/binding.ts` 가 지운 엔드포인트를 부르므로 에이전트 등록으로 고쳐야 한다.

## Critical Files

| 파일 | 변경 |
| --- | --- |
| `backend/src/main/resources/db/migration/V4__agent.sql` | 신규 |
| `backend/src/main/java/com/bifos/assistant/agent/` | 신규 |
| `backend/src/main/java/com/bifos/assistant/credential/` | 삭제 |
| `backend/src/main/java/com/bifos/assistant/chat/application/ChatService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/domain/Conversation.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/usage/application/ExecutionRecorder.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/usage/domain/AgentExecution.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/shared/error/ErrorCode.java` | 수정 |
| `test/e2e/scenarios/binding.ts` | 수정 |

## 끝낸 뒤

`tasks/plan003-agents/index.json` 의 이 phase 를 `completed` 로 바꾸고
`current_phase` 를 다음 번호로 올린다.
