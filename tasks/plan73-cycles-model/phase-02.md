# Phase 02. 실행 기록이 대화 엔티티 대신 값을 받는다

**Execution profile**: deep

## 목표

`usage.application.ExecutionRecorder` 와 `memory.application.MemoryProposer` 가 `chat.domain.Conversation` 을 받지 않게 한다.
실행 기록이 대화에서 읽는 값은 대화 번호와 대화가 고른 reasoning effort 둘뿐이다. 그 둘을 담은 값을 받는다.

**범위 외**: 실행 줄에 적는 값과 그 판정. `usage` 와 `skill` 이 대화의 공개 식별자를 읽는 조회는 다음 phase 가 맡는다.

## 컨텍스트

- 규칙은 `backend/src/test/java/com/bifos/assistant/architecture/ArchitectureRules.java` 의 `TOP_LEVEL_PACKAGES_FREE_OF_CYCLES` 다. 최상위 패키지 간선 하나가 위반 하나다. `B` 에서 `A` 로 돌아올 수 있으면 간선 `A -> B` 가 위반이다. `shared` 는 그래프 밖이다. 규칙은 컴파일한 클래스를 읽는다.
- 기준 파일은 `backend/config/archunit/store/0fd01c41-aa58-43cb-81c5-236ae5119948` 다. 손으로 고치지 않고 아래 명령으로 줄인다. 절차는 `docs/backend/quality.md` 의 「구조 규칙의 기준 파일」 에 있다.
- 결정의 근거는 `docs/adr/ADR-068-최상위-패키지는-한-방향-층-순서를-따르고-거꾸로-가는-의존은-port-나-이동으로-끊는다.md` 다. 층 순서는 아래에서 위로 `hermes`, `user`, `model`, `agent`, `skill`, `usage`, `memory`, `context`, `chat`, `orchestration`, `mcp`, `people`, `connector` 다.
- `LAYER_DIRECTION` 은 패키지를 넘어서도 건다. `domain` 은 어느 층이나 쓸 수 있다. `application` 은 `presentation` 과 `application` 만 접근할 수 있고 `infra` 는 `application` 만 접근할 수 있다. port 를 구현하는 클래스는 위 패키지의 `application` 에 둔다.
- `application` 과 `domain` 은 타입 하나에 파일 하나다(`backend/AGENTS.md`).
- **동작을 바꾸지 않는다.** 저장되는 값, 질의 수, 트랜잭션이 열리는 자리가 그대로여야 한다. `ArchitectureRules.java` 를 고치지 않는다.
- 포맷은 이 phase 에서 돌리지 않는다. `spotlessApply` 결과는 team-lead 가 따로 커밋한다. import 순서를 손으로 정렬하지 않는다. BSD `sed` 는 `\b` 를 모른다. 여러 파일의 이름을 바꿀 때는 `perl -pi -e` 를 쓴다.
- 주석과 Javadoc 은 한국어로 쓴다. 테스트 메서드는 영문 camelCase 이름과 한국어 `@DisplayName` 을 갖는다. `gradlew` 는 `backend/` 안에 있다.
- `backend/src/main/java/com/bifos/assistant/usage/application/ExecutionRecorder.java` 의 `start(...)` 다섯과 `startInheriting(...)` 과 private 메서드들이 `Conversation conversation` 을 받는다. null 도 받는다.
  읽는 곳은 둘이다. `conversation != null && conversation.modelChoice().reasoningEffort() != null` 과 `conversation == null ? null : conversation.id()` 다.
- 부르는 운영 코드는 `chat/application/ChatService.java`, `orchestration/application/AgentRunner.java`(두 곳), `memory/application/MemoryProposer.java` 다. `MemoryProposer` 는 받은 `Conversation` 을 `executions.startInheriting` 에 넘기기만 한다.
- 테스트 27 개 안팎이 `start` 류를 부른다. `git grep -n "\.start\(Inheriting\)\?(" -- backend/src/test` 로 찾는다.

**근거 문서**: 위 ADR-068, `docs/adr/ADR-011-실행은-시작할-때-기록하고-끝날-때-갱신한다.md`, `docs/model-tiers.md` 의 실행 기록 절, `docs/backend/conversation.md`

## 의도 메모

- `ExecutionRecorder` 가 문자열과 번호를 따로 받게 하지 않는다. 인자 순서가 바뀌어도 컴파일되는 시그니처가 된다.
- 값 record 는 `usage.domain` 에 둔다. `chat.domain.Conversation` 이 그 값을 만들어 주는 메서드를 가지려면 `domain` 에 있어야 한다. `application` 에 두면 `domain` 이 `application` 을 쓰게 되어 `LAYER_DIRECTION` 을 어긴다.
- null 을 받던 자리는 그대로 null 을 받는다. 대화가 없는 실행의 판정이 달라지면 안 된다.

## 작업 항목

### 1. `usage/domain/ExecutionConversation.java` 신규

`public record ExecutionConversation(Long id, String reasoningEffort)`.
Javadoc 에 「실행 줄이 대화에서 읽는 값이다. `id` 는 대화 번호, `reasoningEffort` 는 대화가 고른 값이고 고르지 않았으면 null 이다」 를 적는다.

### 2. `chat/domain/Conversation.java` 에 메서드 추가

`public ExecutionConversation executionConversation()` 을 더한다. 본문은 `new ExecutionConversation(id, modelChoice().reasoningEffort())` 다. 필드 이름은 그 클래스에서 읽는다.

### 3. `ExecutionRecorder` 의 변경

`Conversation conversation` 인자를 모두 `ExecutionConversation conversation` 으로 바꾼다. 인자 자리와 이름은 그대로다.
읽는 두 곳을 `conversation != null && conversation.reasoningEffort() != null` 과 `conversation == null ? null : conversation.id()` 로 바꾼다. `chat` 의 import 를 지운다. 그 밖의 줄은 바꾸지 않는다.

### 4. `MemoryProposer` 와 호출부의 변경

- `MemoryProposer` 의 메서드가 받는 `Conversation conversation` 을 `ExecutionConversation conversation` 으로 바꾼다. `chat` 의 import 를 지운다. 이 메서드를 부르는 운영 코드(`git grep -n "MemoryProposer\|memoryProposer\." -- backend/src/main` 로 찾는다)는 `conversation.executionConversation()` 을 넘긴다. 전에 null 을 넘길 수 있던 자리는 null 검사를 그대로 둔다
- `ChatService` 와 `AgentRunner` 의 `executions.start(...)` 호출은 `conversation == null ? null : conversation.executionConversation()` 을 넘긴다. 그 자리의 `conversation` 이 null 일 수 없으면 삼항을 쓰지 않는다. 원래 코드에서 null 가능 여부를 읽고 정한다

### 5. 이 phase 를 검증하는 테스트

- `start` 류와 `MemoryProposer` 를 부르는 기존 테스트의 인자를 `conversation.executionConversation()` 이나 null 로 맞춘다. 단언은 바꾸지 않는다
- `backend/src/test/java/com/bifos/assistant/usage/ExecutionConversationTest.java` 를 새로 만든다. `ExecutionLifecycleTest` 의 준비 방식을 따른다
  - 정상: 대화가 effort 를 골랐으면 `executionConversation()` 의 `reasoningEffort` 가 그 값이고, 그 값으로 시작한 실행 줄의 `reasoning_effort_source` 가 옮기기 전과 같은 값이다. 기대값은 `ExecutionRecorder` 의 판정을 읽고 정한다
  - 경계: 대화가 effort 를 고르지 않았으면 `reasoningEffort` 가 null 이다
  - 실패: 대화 없이(null) 시작한 실행 줄은 `conversation_id` 가 null 이다

### 6. 기준 파일을 줄인다

```bash
# cwd: backend/
./gradlew archTest --rerun -Parchunit.freeze.store.default.allowStoreUpdate=true
```

`memory -> chat` 줄이 빠진다. `usage` 는 `RootExecutionQuery` 가 아직 `chat` 을 써서 남는다.
줄이 늘거나 새 위반으로 실패하면 다시 얼리지 말고 보고한다. 끝난 뒤의 줄 수와 빠진 줄을 회신에 적는다.

## 검증

```bash
# cwd: backend/
./gradlew test
./gradlew checkstyleMain checkstyleTest
! grep -n "^memory -> chat " config/archunit/store/0fd01c41-aa58-43cb-81c5-236ae5119948
! grep -rnE "^import (static )?com\.bifos\.assistant\.chat\." src/main/java/com/bifos/assistant/memory src/main/java/com/bifos/assistant/usage/application/ExecutionRecorder.java src/main/java/com/bifos/assistant/usage/domain
```

```bash
# cwd: 저장소 root. Docker 가 있어야 한다
scripts/check-mysql-migration.sh
node test/e2e/run.ts
```

모두 종료 코드 0 이어야 한다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/usage/domain/ExecutionConversation.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/domain/Conversation.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/usage/application/ExecutionRecorder.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/memory/application/MemoryProposer.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/application/ChatService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/orchestration/application/AgentRunner.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/**/*.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/usage/ExecutionConversationTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/**/*.java` | 수정 |
| `backend/config/archunit/store/0fd01c41-aa58-43cb-81c5-236ae5119948` | 수정 |
