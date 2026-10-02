# Phase 01. 흐름의 계약과 session 값과 위임 종료 사건을 chat 으로 옮긴다

**Execution profile**: standard

## 목표

`chat` 이 `orchestration` 에서 가져다 쓰는 타입 넷을 `chat` 으로 옮긴다(ADR-068 의 C3).
`Flow` 의 인자는 모두 `chat` 의 타입이고, `RunSession` 은 대화의 session 을 정하는 값이고, `DelegationFinished` 를 듣는 쪽은 `chat` 뿐이다.

**범위 외**: 네 타입의 본문. 흐름의 구현 `ResearchAndBuildFlow` 는 `orchestration` 에 둔다. `DelegationOutput` 은 다음 phase 가 맡는다. 이 phase 의 diff 는 `package` 줄, `import` 줄, 아래에 적은 Javadoc 두 줄, 문서뿐이어야 한다.

## 컨텍스트

- 규칙은 `backend/src/test/java/com/bifos/assistant/architecture/ArchitectureRules.java` 의 `TOP_LEVEL_PACKAGES_FREE_OF_CYCLES` 다. 최상위 패키지 간선 하나가 위반 하나다. `B` 에서 `A` 로 돌아올 수 있으면 간선 `A -> B` 가 위반이다. `shared` 는 그래프 밖이다. 규칙은 컴파일한 클래스를 읽는다.
- 기준 파일은 `backend/config/archunit/store/0fd01c41-aa58-43cb-81c5-236ae5119948` 이고 지금 4 줄이다. `chat -> orchestration`, `context -> memory`, `memory -> context`, `orchestration -> chat` 이다. 손으로 고치지 않고 아래 명령으로 줄인다. 절차는 `docs/backend/quality.md` 의 「구조 규칙의 기준 파일」 에 있다.
- 결정의 근거는 `docs/adr/ADR-068-최상위-패키지는-한-방향-층-순서를-따르고-거꾸로-가는-의존은-port-나-이동으로-끊는다.md` 의 C3 과 C4 다. 층 순서에서 `orchestration` 은 `chat` 보다 위다. `orchestration` 이 `chat` 을 쓰는 것은 맞는 방향이다.
- `LAYER_DIRECTION` 은 패키지를 넘어서도 건다. `domain` 은 어느 층이나 쓸 수 있다. `application` 은 `presentation` 과 `application` 만 접근할 수 있다. port 를 구현하는 클래스는 위 패키지의 `application` 에 둔다.
- `application` 과 `domain` 은 타입 하나에 파일 하나다(`backend/AGENTS.md`).
- **동작을 바꾸지 않는다.** `ArchitectureRules.java` 를 고치지 않는다.
- 포맷은 이 phase 에서 돌리지 않는다. 이 phase 커밋 뒤 team-lead 가 `./gradlew spotlessApply` 결과를 별도 커밋으로 낸다. import 순서를 손으로 정렬하지 않는다. BSD `sed` 는 `\b` 를 모른다. 여러 파일의 이름을 바꿀 때는 `perl -pi -e` 를 쓰고 패턴 끝을 `;` 로 고정한다.
- 주석과 Javadoc 은 한국어로 쓴다. 테스트 메서드는 영문 camelCase 이름과 한국어 `@DisplayName` 을 갖는다. `gradlew` 는 `backend/` 안에 있다.
- 옮길 것이다. 경로는 `backend/src/main/java/com/bifos/assistant/` 아래다.

| 지금 | 옮긴 뒤 |
| --- | --- |
| `orchestration/application/Flow.java` | `chat/application/Flow.java` |
| `orchestration/application/FlowRegistry.java` | `chat/application/FlowRegistry.java` |
| `orchestration/application/DelegationFinished.java` | `chat/application/DelegationFinished.java` |
| `orchestration/domain/RunSession.java` | `chat/domain/RunSession.java` |

- `Flow` 는 `agent.domain.Agent`, `chat` 의 `ChatEvent`, `ChatTurn`, `TurnIntent`, `Conversation`, `usage.domain.AgentExecution` 을 쓴다. `FlowRegistry` 는 `agent.application.KnownFlows` 를 구현하고 `agent.infra.AgentRepository` 를 쓴다. `RunSession` 과 `DelegationFinished` 는 다른 패키지를 쓰지 않는다.
- 테스트 `backend/src/test/java/com/bifos/assistant/orchestration/FlowRegistryTest.java` 와 `backend/src/test/java/com/bifos/assistant/orchestration/RunSessionTest.java` 가 옮기는 타입을 본다.
- `ORCHESTRATION_DOES_NOT_CALL_CHAT_SERVICE` 는 `orchestration` 이 `ChatService` 를 쓰지 못하게 한다. 이 이동은 그 규칙에 닿지 않는다.

**근거 문서**: 위 ADR-068 의 C3, `docs/backend/packages.md` 의 「패키지와 책임」, `docs/backend/agent-delegation.md`, `docs/backend/turn-control.md`

## 의도 메모

- 구현 `ResearchAndBuildFlow` 를 함께 옮기지 않는다. 자식 실행을 돌리는 `orchestration` 의 일이다.
- 네 타입에는 패키지 전용 멤버가 없다. 옮기는 테스트 둘은 `orchestration` 테스트 패키지의 도우미를 쓰지 않고, `chat` 테스트 패키지에 같은 이름도 없다. 접근 수준을 바꿀 일이 없다.

## 작업 항목

### 1. `git mv` 로 넷을 옮기고 `package` 줄을 고친다

위 표대로 옮긴다. `FlowRegistry`, `DelegationFinished`, `RunSession` 은 `package` 줄만 바뀐다.
`Flow.java` 는 같은 패키지가 된 `ChatEvent`, `ChatTurn`, `TurnIntent` 의 import 셋을 지운다. `chat.domain.Conversation` 의 import 는 남긴다.

Javadoc 두 줄을 고친다.

- `Flow.java` 의 `{@link ResearchAndBuildFlow}` 를 `{@code ResearchAndBuildFlow}` 로 바꾼다. `orchestration` 의 import 를 더하지 않는다. 더하면 `chat` 이 다시 `orchestration` 을 쓴다
- `DelegationFinished.java` 의 「이 패키지는 받는 쪽을 알지 않는다」 를 「내는 쪽은 받는 쪽을 알지 않는다」 로 고친다. 옮긴 뒤에는 받는 쪽이 같은 패키지다

### 2. 참조를 고친다

경로는 `backend/src/main/java/com/bifos/assistant/` 아래다. 이 밖에 고칠 운영 코드는 없다.

| 파일 | 고칠 것 |
| --- | --- |
| `orchestration/application/ResearchAndBuildFlow.java` | `chat.application.Flow` import 를 더하고 `orchestration.domain.RunSession` import 를 `chat.domain.RunSession` 으로 바꾼다 |
| `orchestration/application/AgentDelegationService.java` | `chat.application.DelegationFinished` import 를 더한다 |
| `orchestration/application/AgentRunner.java` | `RunSession` import 를 `chat.domain.RunSession` 으로 바꾼다 |
| `orchestration/application/ChildExecutionRunner.java` | 같음 |
| `chat/application/ChatService.java` | `Flow`, `FlowRegistry` 의 import 를 지우고 `RunSession` import 를 `chat.domain.RunSession` 으로 바꾼다 |
| `chat/application/ConversationSessions.java` | `RunSession` import 를 `chat.domain.RunSession` 으로 바꾼다 |
| `chat/application/RecoveredRunRecorder.java` | `DelegationFinished`, `FlowRegistry` 의 import 를 지운다. `DelegationOutput` 의 import 는 남긴다 |
| `chat/application/NextTurnDispatcher.java` | `DelegationFinished` 의 import 를 지운다 |
| `chat/application/DelegationWakeService.java` | `FlowRegistry` 의 import 를 지운다 |
| `chat/application/PendingMessageService.java` | `FlowRegistry` 의 import 를 지운다 |

테스트는 `backend/src/test/java/com/bifos/assistant/` 아래 여덟 파일의 import 를 새 패키지로 바꾼다.
`chat/ConnectorActionDeliveryTest.java`, `chat/ConversationSessionTest.java`, `chat/DelegationWakeServiceTest.java`, `chat/PendingBeforeDelegationTest.java`, `chat/RecoveredRunRecorderTest.java`, `orchestration/AgentRunnerConnectorContextTest.java`, `orchestration/AgentRunnerSubmitFailureTest.java`, `usage/FailedExecutionUsageRoutesTest.java` 다.
`FlowRegistryTest.java` 와 `RunSessionTest.java` 는 `git mv` 로 `backend/src/test/java/com/bifos/assistant/chat/` 로 옮기고 `package` 줄과 import 를 고친다. 단언은 바꾸지 않는다.
`./gradlew compileJava compileTestJava` 가 통과해야 한다.

### 3. 문서를 고친다

- `docs/backend/packages.md` 의 「패키지와 책임」 표에서 `chat` 의 책임 끝에 「, 흐름의 계약과 등록」 을 더한다. `orchestration` 줄의 「흐름과 자식 실행」 은 「흐름의 구현과 자식 실행」 으로 고친다
- `docs/backend/agent.md` 의 `orchestration/application/FlowRegistry` 를 `chat/application/FlowRegistry` 로 고친다
- `docs/backend/mcp-caller.md` 의 `orchestration.domain.RunSession` 을 `chat.domain.RunSession` 으로 고친다
- 문서에서 옛 위치를 적은 곳은 이 두 줄이다. Javadoc 에는 없다. `agent/domain/Agent.java` 의 `{@code FlowRegistry}` 는 그대로 둔다

고친 문서에 `bash /Users/nhn/personal/fos-skills/content-preview/scripts/style-check.sh <파일>` 을 돌려 종료 코드 0 인지 본다.

### 4. 이 phase 를 검증하는 테스트

옮긴 `FlowRegistryTest.java` 와 `RunSessionTest.java` 가 단언을 바꾸지 않은 채 통과해야 한다.
정상: 등록한 흐름 이름을 찾고 `RunSession` 이 대화의 session 값을 그대로 잇는다. 실패: 모르는 흐름 이름과 null 은 `known` 이 false 다.

### 5. 기준 파일이 바뀌지 않는 것을 확인한다

```bash
# cwd: backend/
./gradlew archTest --rerun -Parchunit.freeze.store.default.allowStoreUpdate=true
```

이 phase 뒤에도 순환 기준은 4 줄 그대로다. `RecoveredRunRecorder` 가 아직 `DelegationOutput` 을 써서 `chat -> orchestration` 이 남는다.
기준 파일이 바뀌거나 새 위반으로 실패하면 다시 얼리지 말고 보고한다. `LAYER_DIRECTION` 에 새 위반이 없어야 한다.

## 검증

```bash
# cwd: backend/
./gradlew test
./gradlew checkstyleMain checkstyleTest
test "$(grep -c "" config/archunit/store/0fd01c41-aa58-43cb-81c5-236ae5119948)" -eq 4
git diff --exit-code -- config/archunit/store
! grep -rnE "orchestration\.(application|domain)\.(Flow|FlowRegistry|DelegationFinished|RunSession);" src
```

```bash
# cwd: 저장소 root
node test/e2e/run.ts
! git grep -nE "orchestration[./](application|domain)[./](Flow|FlowRegistry|DelegationFinished|RunSession)([^A-Za-z]|$)" -- docs backend/src AGENTS.md backend/AGENTS.md
```

모두 종료 코드 0 이어야 한다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/orchestration/application/Flow.java` | 삭제 |
| `backend/src/main/java/com/bifos/assistant/orchestration/application/FlowRegistry.java` | 삭제 |
| `backend/src/main/java/com/bifos/assistant/orchestration/application/DelegationFinished.java` | 삭제 |
| `backend/src/main/java/com/bifos/assistant/orchestration/domain/RunSession.java` | 삭제 |
| `backend/src/main/java/com/bifos/assistant/chat/application/Flow.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/application/FlowRegistry.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/application/DelegationFinished.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/domain/RunSession.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/orchestration/FlowRegistryTest.java` | 삭제 |
| `backend/src/test/java/com/bifos/assistant/orchestration/RunSessionTest.java` | 삭제 |
| `backend/src/test/java/com/bifos/assistant/chat/FlowRegistryTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/chat/RunSessionTest.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/orchestration/application/ResearchAndBuildFlow.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/orchestration/application/AgentDelegationService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/orchestration/application/AgentRunner.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/orchestration/application/ChildExecutionRunner.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/application/ChatService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/application/ConversationSessions.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/application/RecoveredRunRecorder.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/application/NextTurnDispatcher.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/application/DelegationWakeService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/application/PendingMessageService.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/ConnectorActionDeliveryTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/ConversationSessionTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/DelegationWakeServiceTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/PendingBeforeDelegationTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/RecoveredRunRecorderTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/orchestration/AgentRunnerConnectorContextTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/orchestration/AgentRunnerSubmitFailureTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/usage/FailedExecutionUsageRoutesTest.java` | 수정 |
| `docs/backend/packages.md` | 수정 |
| `docs/backend/agent.md` | 수정 |
| `docs/backend/mcp-caller.md` | 수정 |
