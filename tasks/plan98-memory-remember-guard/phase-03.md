# Phase 03. 기능 도입 전 실행도 질문 없는 루트 실행으로 센다

**Execution profile**: fast

## 목표

대화의 첫 `execution_question` 보다 앞선 루트 실행도 「사람의 질문 없이 Hermes 로 보낸 루트 실행」 으로 센다.
기능 도입 전에 맡긴 일의 결과가 같은 session 이력에 남은 오래된 대화에서 바로 저장이 열리지 않게 한다.

**범위 외**: 부정 표지 판정(phase 01), 민감 낱말 판정(phase 02), 화면(phase 04).

## 컨텍스트

**근거 문서**: `docs/backend/memory.md` 의 「에이전트가 기억을 남기는 길」 절, `docs/adr/ADR-20261008-memory-remember-guard.md`

- 계약: `docs/backend/memory.md` 의 「바로 저장 판정」 표 5.
- 근거: `docs/adr/ADR-20261008-memory-remember-guard.md` 의 결정 3.
- 쿼리는 `backend/src/main/java/com/bifos/assistant/chat/infra/ExecutionQuestionRepository.java` 의 `existsRunWithoutQuestion(Long conversationId)` 다.
  지금은 `and e.id >= (select min(q.executionId) ...)` 절이 첫 질문 줄보다 앞선 실행을 뺀다. 이 절을 지운다.
- `RepositoryQueryMysqlTest` 가 저장소 메서드를 실제 MySQL 에서 한 번씩 돌린다. 메서드 이름과 인자는 그대로라 따로 할 일이 없다.
- 시험은 `backend/src/test/java/com/bifos/assistant/mcp/McpMemoryRememberToolTest.java` 다. `setUp()` 이 `dadRun` 을 가장 먼저 만들고 `call()` 은 `dadRoot` 로 서명한다.
  쿼리는 `e.hermesRunId is not null` 인 실행만 센다. `McpCallSigner.running(...)` 은 `hermesRunId` 를 비워 두므로 질문 없는 실행을 그것으로 만들면 세지지 않는다.
  그래서 질문 없는 루트 실행은 기존 보조 메서드 `otherRootRun("run-legacy")`(hermesRunId 를 채운다)로 먼저 저장하고,
  그 뒤 `McpCallSigner.running(executions, agents, dad.id(), conversation.id(), PROFILE, root)` 와 `McpCallSigner.newRoot()` 로 새 실행과 새 root 를 만든다. 그 실행에 질문을 잇고 그 root 로 서명해 부른다.
  이 준비는 옛 쿼리에서는 바로 저장, 새 쿼리에서는 제안으로 갈린다. `otherRootRun` 이 `dadRun` 보다 뒤에 생겨도 새 실행의 번호보다는 작다.
  `signed(...)` 가 `dadRoot` 를 쓰므로 root 를 받는 `call(ObjectNode, String root)` 보조 메서드를 더한다.

## 의도 메모

- 질문 줄 저장에 실패한 실행(`ChatTurnLifecycle.recordQuestion` 이 경고만 남김)도 이제 늘 센다. 그 대화는 뒤로 제안만 남는다. 새 대화를 열면 다시 열린다.
- 대화에 질문 줄이 하나도 없으면 바로 저장 조건 2 가 이미 막으므로 결과가 같다.

## 작업 항목

### 1. `backend/src/main/java/com/bifos/assistant/chat/infra/ExecutionQuestionRepository.java` 수정

`existsRunWithoutQuestion` 의 쿼리에서 `and e.id >= (select min(q.executionId) from ExecutionQuestion q, AgentExecution asked where asked.id = q.executionId and asked.conversationId = :conversationId)` 를 지운다.
Javadoc 의 「이 표가 생기기 전의 실행은 보지 않는다 ...」 두 문장을 「이 표가 생기기 전의 실행과 질문 줄을 남기지 못한 실행도 센다. 그 입력에 무엇이 실렸는지 기록으로 가릴 수 없다(ADR-20261008 / memory-remember-guard).」 로 바꾼다.

### 2. `backend/src/test/java/com/bifos/assistant/mcp/McpMemoryRememberToolTest.java` 수정

시험 하나를 더한다: 「기능 도입 전의 질문 없는 루트 실행이 대화에 있으면 그 뒤의 질문 turn 도 제안이다」.
`dadRun` 을 쓰지 않고, 질문 없는 루트 실행 → 새 root 의 실행 → 그 실행에 `QUESTION` 을 잇는 순서로 저장한 뒤
`remember("다른 사람", "다른 사람은 홍길동이다", null)` 을 새 root 로 부르면 PROPOSED 다.
같은 준비에서 질문 없는 실행을 만들지 않으면 REMEMBERED 라는 대조 단언을 같은 시험이나 별도 시험에 둔다.

## 검증

```bash
cd backend && ./gradlew test --tests 'com.bifos.assistant.mcp.McpMemoryRememberToolTest'
cd backend && ./gradlew checkstyleMain checkstyleTest spotlessCheck
```

기대값: 모두 통과.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/chat/infra/ExecutionQuestionRepository.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/mcp/McpMemoryRememberToolTest.java` | 수정 |
