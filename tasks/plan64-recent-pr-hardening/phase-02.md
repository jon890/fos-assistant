# Phase 02. Memory 제안과 추천 질문 실행이 보낸 값과 그 출처를 실행 줄에 맞게 적는다

**Execution profile**: standard

## 목표

실행 줄에는 Hermes 에 보낸 값과 그 값을 누가 정했는지가 함께 맞아야 한다.
지금 Memory 제안 실행은 원래 실행의 단계와 effort 출처를 잃고, 추천 질문 실행은 보낸 모델을 적지 않는다.

**범위 외**: Hermes 에 보내는 값은 바꾸지 않는다. 숨김 판정은 phase 03 이 맡는다. 스키마를 바꾸지 않는다.

## 컨텍스트

- `backend/src/main/java/com/bifos/assistant/usage/application/ExecutionRecorder.java`
  - 12인자 `start(...)` 가 `effortSource(conversation, requested, modelTier)` 로 출처를 정한다.
    단계가 없고 대화가 effort 를 고르지 않았는데 보낸 effort 가 있으면 `AGENT_DEFAULT` 다.
  - `startDetached(CurrentUser user, Agent agent)` 는 `requested` 에 null 을 넘겨 세 값을 비운다.
- `backend/src/main/java/com/bifos/assistant/memory/application/MemoryProposer.java` 의 `proposeFrom` 은
  9인자 `start(...)` 를 불러 단계가 null 이다. 원래 실행이 단계(예: `BALANCED`, effort `medium`)로 돌았어도
  대화가 effort 를 고르지 않았으므로 제안 실행은 `AGENT_DEFAULT` 로 적힌다.
- `backend/src/main/java/com/bifos/assistant/agent/application/StarterSuggestionService.java` 의 `generate` 는
  `HermesRunCommand` 에 `agent.defaultModelProvider()`, `agent.defaultModel()`, `agent.defaultReasoningEffort()` 를 싣지만
  `executions.startDetached(user, agent)` 와 `executions.complete(execution, agent, result, null)` 로 그 값을 적지 않는다.
- `backend/src/main/java/com/bifos/assistant/usage/domain/AgentExecution.java` 는 `modelTier()`, `reasoningEffortSource()` 를 낸다.
  완료 뒤 보완은 출처가 `UNKNOWN` 인 줄만 `PROFILE_DEFAULT` 로 바꾼다.
- 출처 enum 은 `backend/src/main/java/com/bifos/assistant/usage/domain/type/ReasoningEffortSource.java` 의
  `REQUESTED`, `AGENT_DEFAULT`, `PROFILE_DEFAULT`, `UNKNOWN` 이다.

**근거 문서**: `docs/model-tiers.md` 의 「에이전트 기본 모델」 과 「profile 기본 강도」, `docs/adr/ADR-054-에이전트-기본-모델과-모델-숨김은-control-plane-db-가-갖는다.md` 의 「결과」

## 의도 메모

- 원래 실행의 출처는 **원래 실행 줄에 적힌 값**에서 읽는다. 대화에서 다시 계산하지 않는다. 그 사이 대화의 선택이 바뀔 수 있다.
- 보낸 effort 가 없는 제안 실행의 출처는 원래 실행이 이미 `PROFILE_DEFAULT` 로 보완됐어도 `UNKNOWN` 이다.
  `UNKNOWN` 이어야 완료 뒤 보완이 그 줄을 찾는다.
- `startDetached` 의 인자 없는 모양은 남기지 않는다. 부르는 곳이 추천 질문 하나이고, 남기면 값을 적지 않는 길이 다시 생긴다.

## 작업 항목

### 1. `ExecutionRecorder` 에 원래 실행을 이어받는 시작을 더한다

```java
public AgentExecution startInheriting(
        CurrentUser user, Conversation conversation, Agent agent, AgentExecution parent, ModelChoice requested)
```

- 부모와 뿌리 실행 번호는 둘 다 `parent.id()` 다. 지금 `MemoryProposer` 가 넘기는 값과 같다.
- 문맥은 `ExecutionContextSnapshot.ofChars(0L)`, session 과 `delegationKey` 와 `requestReceivedAt` 은 null 이다.
- `provider`, `model`, `reasoningEffort` 는 `requested` 의 값이다.
- `modelTier` 는 `parent.modelTier()` 다.
- 출처는 `requested.reasoningEffort()` 가 null 이면 `UNKNOWN`, 아니면 `parent.reasoningEffortSource()` 가 `AGENT_DEFAULT` 일 때 `AGENT_DEFAULT`, 그 밖은 `REQUESTED` 다.
- 기존 12인자 `start` 와 저장 코드가 겹치지 않게 private 메서드로 묶는다. Javadoc 을 한국어로 쓴다.

### 2. `ExecutionRecorder.startDetached` 가 보낸 값을 받는다

```java
public AgentExecution startDetached(CurrentUser user, Agent agent, ModelChoice requested)
```

- 기존 2인자 메서드를 이 모양으로 바꾼다. `requested` 를 9인자 `start` 의 `requested` 로 넘긴다.
- 출처는 기존 `effortSource` 가 정한다. 대화와 단계가 없으므로 effort 가 있으면 `AGENT_DEFAULT`, 없으면 `UNKNOWN` 이다.
- Javadoc 의 「모델 선택을 모두 비우고」 를 고친다.

### 3. `MemoryProposer.proposeFrom`

- `executions.start(...)` 호출을 `executions.startInheriting(user, conversation, agent, parentExecution, choice)` 로 바꾼다.
- 쓰지 않게 된 `ExecutionContextSnapshot` import 를 지운다.

### 4. `StarterSuggestionService.generate`

- `ModelChoice choice = ModelChoice.stored(agent.defaultModelProvider(), agent.defaultModel(), agent.defaultReasoningEffort());` 를 만든다.
- `executions.startDetached(user, agent, choice)` 로 시작하고, `HermesRunCommand` 의 세 값을 `choice` 에서 읽고,
  `executions.complete(execution, agent, result, choice)` 로 끝낸다. 보내는 값과 적는 값이 같은 객체에서 나온다.

### 5. 이 phase 를 검증하는 테스트

기존 테스트 파일의 방식(가짜와 저장소 준비)을 그대로 따른다. 새 `@Test` 마다 `@DisplayName` 을 붙인다.

- `backend/src/test/java/com/bifos/assistant/memory/MemoryProposerTest.java`:
  원래 실행의 (단계, 출처, 보낸 effort) 와 제안 실행에 적힌 (단계, 출처) 를 견준다.
  | 원래 실행 | 제안 실행 |
  | --- | --- |
  | 대화가 고른 단계 `BALANCED`, `REQUESTED`, `medium` | `BALANCED`, `REQUESTED` |
  | 내 기본 단계나 그룹 기본 단계로 정해진 `DEEP`, `REQUESTED`, `high` | `DEEP`, `REQUESTED` |
  | 단계 없음, `AGENT_DEFAULT`, `medium` | 단계 없음, `AGENT_DEFAULT` |
  | 단계 없음, 대화가 직접 고른 effort 라 `REQUESTED`, `high` | 단계 없음, `REQUESTED` |
  | 단계 없음, effort 를 보내지 않음 | 단계 없음, `UNKNOWN` |
  내 기본 단계와 그룹 기본 단계는 실행 줄에서 같은 모양(단계와 `REQUESTED`)이라 한 경우로 묶어도 된다. 그때는 그렇게 묶은 까닭을 테스트 이름에 적는다.
  Hermes 에 보낸 provider, 모델, effort 가 `requested` 와 같은지도 함께 단언한다.
- `backend/src/test/java/com/bifos/assistant/agent/StarterSuggestionServiceTest.java`:
  - 에이전트 기본값이 있으면 Hermes 에 보낸 세 값과 실행 줄의 `provider`, `model`, `reasoningEffort` 가 같고 출처가 `AGENT_DEFAULT` 다.
  - 기본값이 비면 Hermes 에 세 값을 보내지 않고 실행 줄의 세 값이 null 이며 출처가 `UNKNOWN` 이다.
  - `startDetached` 의 모양이 바뀌어 깨지는 기존 호출을 고친다.
- 저장소 전체에서 `startDetached(` 를 찾아 2인자 호출이 남지 않게 한다. 3인자 호출만 남는다.

## 검증

```bash
# cwd: 저장소 root
cd backend && ./gradlew test --tests '*MemoryProposerTest' --tests '*StarterSuggestionServiceTest' --tests '*ChatMemoryProposalTest' --tests '*ArchitectureRulesTest'
cd backend && ./gradlew test
cd backend && ./gradlew qualityCheck
```

- 모두 종료 코드 0 이다.
- `git grep -n "startDetached(user, agent)" -- backend/src` 가 아무것도 내지 않는다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/usage/application/ExecutionRecorder.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/memory/application/MemoryProposer.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/agent/application/StarterSuggestionService.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/memory/MemoryProposerTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/agent/StarterSuggestionServiceTest.java` | 수정 |
