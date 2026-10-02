# Phase 01. 흐름의 계약과 session 값과 위임 종료 사건을 chat 으로 옮긴다

**Execution profile**: standard

## 목표

`chat` 이 `orchestration` 에서 가져다 쓰는 타입 넷을 `chat` 으로 옮긴다(ADR-068 의 C3).
`Flow` 의 인자는 모두 `chat` 의 타입이고, `RunSession` 은 대화의 session 을 정하는 값이고, `DelegationFinished` 를 듣는 쪽은 `chat` 뿐이다.

**범위 외**: 네 타입의 본문. 흐름의 구현 `ResearchAndBuildFlow` 는 `orchestration` 에 둔다. `DelegationOutput` 은 다음 phase 가 맡는다. 이 phase 의 diff 는 `package` 줄, `import` 줄, 문서뿐이어야 한다.

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
- 네 타입이 패키지 전용 멤버를 쓰거나 쓰이는지 먼저 확인한다. 있으면 접근 수준을 바꾸지 말고 보고한다.

## 작업 항목

### 1. `git mv` 로 넷을 옮기고 `package` 줄을 고친다

위 표대로 옮긴다. 같은 패키지가 되어 필요 없어진 import 는 지우고 필요해진 import 는 더한다.

### 2. 참조를 고친다

`backend/src/main/java` 와 `backend/src/test/java` 에서 네 타입의 import 를 새 패키지로 바꾼다.
`orchestration` 에서 같은 패키지라 import 없이 쓰던 클래스(`ResearchAndBuildFlow`, `AgentRunner`, `ChildExecutionRunner`, `AgentDelegationService` 등)에는 import 를 더하고, `chat` 에서 같은 패키지가 된 클래스의 import 는 지운다.
테스트 둘은 `git mv` 로 `backend/src/test/java/com/bifos/assistant/chat/` 로 옮기고 `package` 줄을 고친다. 단언은 바꾸지 않는다.
`./gradlew compileJava compileTestJava` 가 통과할 때까지 고친다.

### 3. 문서를 고친다

- `docs/backend/packages.md` 의 「패키지와 책임」 표에서 `chat` 의 책임 끝에 「, 흐름의 계약과 등록」 을 더한다. `orchestration` 줄의 「흐름과 자식 실행」 은 「흐름의 구현과 자식 실행」 으로 고친다
- `git grep -n "orchestration[./]\(application\|domain\)[./]\(Flow\|FlowRegistry\|DelegationFinished\|RunSession\)\b" -- docs backend/src AGENTS.md backend/AGENTS.md` 가 0 건이 되게 문서와 Javadoc 을 고친다

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
! grep -rnE "orchestration\.(application|domain)\.(Flow|FlowRegistry|DelegationFinished|RunSession);" src
```

```bash
# cwd: 저장소 root
node test/e2e/run.ts
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
| `backend/src/main/java/com/bifos/assistant/**/*.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/**/*.java` | 수정 |
| `docs/backend/packages.md` | 수정 |
| `docs/**/*.md` | 수정 |
