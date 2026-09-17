# Phase 02. ContextAssembler 가 실행에 넣을 것을 조립한다

**Execution profile**: deep

## 목표

실행마다 넣을 `instructions` 를 조립하는 책임을 `ChatService` 에서 떼어낸다.
요청자가 볼 수 있는 Memory 만 골라 넣고, 조립한 글자 수를 실행에 남긴다.

**범위 외**:
화면은 phase-03 이 만든다.
에이전트가 Memory 를 제안하는 경로도 phase-03 이 만든다.
제목만 실린 항목의 본문을 읽는 도구는 phase-04 가 만든다.

## 컨텍스트

plan008 의 phase-01 이 작업 영역을 제거하면서
`HermesRunCommand` 의 `instructions` 자리를 `null` 로 비워 두었다.
이 phase 가 그 자리를 채운다.

조립을 `ChatService` 에 직접 넣지 않는다.
`ChatService` 는 이미 대화 해결, 실행 제출, 스트림 중계, 기록 갱신을 한다.
넣을 것이 늘어날수록 그 클래스가 무엇을 하는지 읽기 어려워지고,
무엇보다 「무엇을 넣었는가」 를 따로 테스트하기 어려워진다.

주입할 양은 늘어날 수밖에 없다.
ADR-003 이 「memory 가 많아지면 도구 방식으로 옮긴다」 고 적었는데,
그 시점을 숫자로 판단하려면 실행마다 넣은 글자 수가 남아야 한다.
plan008 의 phase-02 가 `context_chars` 칸을 이미 만들어 두었다.

**근거 문서**: `docs/adr/ADR-003-memory-권한은-주입으로-강제한다.md`,
`docs/adr/ADR-012-memory-는-사람이-승인한-것만-남는다.md`,
`docs/code-architecture.md` 의 「한 번의 대화가 지나는 길」 절,
`docs/data-schema.md` 의 「agent_execution」 절

## 의도 메모

- vector search 와 embedding 은 만들지 않는다.
  항목이 적을 때 그것을 만들면 확인할 것만 늘고 얻는 것이 없다.
  다만 고르는 자리를 `ContextAssembler` 한 곳으로 모아, 나중에 그 안만 바꾸면 되게 한다.
- 상한을 넘을 때 무엇을 버릴지는 이번에 정하지 않는다.
  먼저 `context_chars` 를 쌓아 실제로 얼마나 커지는지 보고 정한다.
  다만 상한 자체는 둔다. 넘으면 자르고 잘랐다는 것을 로그로 남긴다.

## 작업 항목

### 1. `context` 패키지를 만든다

`backend/src/main/java/com/bifos/assistant/context/` 아래다.

```java
/**
 * 실행 하나에 넣을 instructions 를 조립한다.
 *
 * <p>고르는 자리를 여기 하나로 모은다. 나중에 항목이 많아져 검색으로 골라야 할 때
 * 이 클래스 안만 바뀐다.
 */
@Service
public class ContextAssembler {

    /** 넣을 것이 없으면 null 을 낸다. 빈 문자열을 보내지 않는다. */
    public AssembledContext assemble(CurrentUser user);
}
```

```java
/** 조립한 문자열과 그 길이. 길이를 따로 두는 이유는 실행 기록에 남기기 위해서다. */
public record AssembledContext(String instructions, long chars) {
    public static AssembledContext empty();
}
```

**전부 싣지 않는다.** 층을 나눠 조립한다.
근거는 [ADR-015](../../docs/adr/ADR-015-memory-는-층을-나눠-싣는다.md) 에 있다.

| 층 | 무엇 | 어디서 |
| --- | --- | --- |
| 항상 | 본문까지 싣는다 | `MemoryService.alwaysInjectedFor(user)` |
| 색인 | 제목만 싣는다 | `MemoryService.indexedFor(user)` |

**다른 구성원의 개인 Memory 는 두 층 모두에서 빠진다.**
그 둘이 고르는 단계에서 보장한다.
문자열을 만든 뒤에 지우는 방식을 쓰지 않는다.

조립한 모양은 아래와 같다.
어느 것이 가족 공용이고 어느 것이 개인인지 에이전트가 알아야 한다.

```
# 우리 가족이 함께 아는 것

- <내용>

# 지금 묻는 사람에 대해 아는 것

- <내용>

# 더 물어볼 수 있는 것

아래는 제목만 적은 것이다. 필요하면 memory_read 도구로 본문을 읽는다.

- [12] 지원 우선순위
- [15] 보유 기술 스택
```

한쪽이 비면 그 제목도 넣지 않는다.
색인 층이 비면 「더 물어볼 수 있는 것」 절 전체를 넣지 않는다.

**색인에 번호를 함께 적는다.** 에이전트가 본문을 요청할 때 그 번호를 쓴다.
번호는 `memory` 표의 `id` 다.

`memory_read` 도구는 phase-04 가 만든다.
이 phase 에서는 그 도구가 없으므로 색인만 실리고 본문을 읽을 길이 없다.
**그래도 항상 층만으로 기본 대화가 성립해야 한다.** ADR-015 가 그렇게 정했다.

### 2. 상한을 둔다

`assistant.context.max-chars` 로 정한다. 기본값은 8000 이다.
넘으면 자르고 `log.warn` 으로 몇 글자를 잘랐는지 남긴다.

항목은 `id` 오름차순으로 정렬한다.
가족 공용 항상 층, 개인 항상 층, 색인 층 순서로 넣는다.
다음 항목을 더하면 상한을 넘을 때는 그 항목부터 뒤의 항목을 넣지 않는다.
제목이나 항목 중간을 자르지 않고, 실제로 넣은 문자열의 길이만 `chars` 에 기록한다.

`WorkspaceProperties.briefingLimit` 이 쓰던 방식과 같다.
그 클래스는 plan008 이 지웠으므로 `ContextProperties` 를 새로 만든다.

### 3. `ChatService` 가 조립한 것을 쓴다

- `prepare` 가 `contextAssembler.assemble(user)` 를 부른다.
- 그 결과의 `instructions` 를 `HermesRunCommand` 에 넣는다.
- `ExecutionRecorder.start(...)` 에 `contextChars` 를 넘겨 실행 줄에 적는다.
- `AgentExecution.Builder` 에 `contextChars(Long)` 를 더해 시작 시점의 행에 저장한다.

`ExecutionRecorder.start` 의 서명이 바뀐다.

```java
public AgentExecution start(
        CurrentUser user, Conversation conversation, Agent agent,
        Long parentExecutionId, Long rootExecutionId, Long contextChars)
```

### 4. 사용량 조회가 `context_chars` 를 낸다

`usage/presentation/UsageController.java` 의 `ExecutionView` 에 `contextChars` 를 더한다.
여기서는 응답에 싣기만 한다. 화면이 그 값을 보이는 것은 뒤에 만들어졌다.

### 5. 이 phase 를 검증하는 테스트

`backend/src/test/java/com/bifos/assistant/context/ContextAssemblerTest.java` 를 새로 만든다.

- **정상 경로**: 가족 공용 항목과 개인 항목이 둘 다 있으면
  두 제목이 그 순서로 나오고 두 내용이 모두 들어 있다
- **이 phase 가 막아야 할 것**: 다른 구성원의 `USER` 항목이 있을 때
  조립한 문자열에 그 내용이 **들어 있지 않다**.
  `assertThat(result.instructions()).doesNotContain(남의_내용)` 으로 고정한다
- `PROPOSED` 인 항목은 들어가지 않는다
- 넣을 것이 하나도 없으면 `instructions` 가 `null` 이고 `chars` 가 0 이다
- 한쪽만 있으면 그쪽 제목만 나온다
- 상한을 넘으면 잘리고 `chars` 가 자른 뒤의 길이다
- 역순으로 저장한 항목도 `id` 오름차순으로 나온다
- 다음 항목이 상한을 넘으면 그 항목의 제목과 본문 일부가 모두 들어가지 않는다

`backend/src/test/java/com/bifos/assistant/chat/ChatServiceTest.java` 에 더한다.

- 대화 한 번이 조립한 `instructions` 를 `HermesRunCommand` 에 실어 보낸다
- 그 실행 줄의 `contextChars` 가 조립한 길이와 같다
- 저장소에서 다시 읽은 실행 줄의 `contextChars` 가 조립한 길이와 같다

`test/e2e/scenarios/memory.ts` 에 더한다.

- **다른 사용자의 개인 Memory 가 Hermes 로 나가는 요청에 들어 있지 않다.**
  가짜 Hermes 가 받은 `instructions` 를 단언문으로 검사한다.
  그 문자열에 남의 내용이 없다는 것을 `assert` 로 고정한다.
  이것이 이 plan 의 가장 중요한 검사다

## 검증

```bash
# cwd: 저장소 root
cd backend && ./gradlew test
node test/e2e/run.ts
cd web && pnpm typecheck && pnpm build
```

```bash
# cwd: 저장소 root
cd backend && ./gradlew test --tests '*ContextAssemblerTest*'
```

가짜 Hermes 가 실제와 다른 형태를 보내도록 쓰여 있으면 테스트가 통과해도 운영에서 동작하지 않는다.
`AGENTS.md` 의 「배포했다고 말하기 전에 보는 것」 이 그 사고를 적고 있다.
이 phase 는 Hermes 로 **보내는** 것을 바꾸므로, 가짜가 받은 요청을 확인하는 것으로 충분하다.

## Critical Files

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/context/ContextAssembler.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/context/AssembledContext.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/context/ContextProperties.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/application/ChatService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/usage/application/ExecutionRecorder.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/usage/domain/AgentExecution.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/usage/presentation/UsageController.java` | 수정 |
| `backend/src/main/resources/application.yml` | 수정 |
| `backend/src/test/resources/application-test.yml` | 수정 |
| `backend/src/test/java/com/bifos/assistant/context/ContextAssemblerTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/chat/ChatServiceTest.java` | 수정 |
| `test/e2e/scenarios/memory.ts` | 수정 |
