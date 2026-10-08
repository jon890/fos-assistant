# Phase 01. 본문이 질문 원문의 문장 단위 구간일 때만 바로 저장한다

**Execution profile**: deep

## 목표

`memory_remember` 의 바로 저장 판정에서 `evidence` 를 빼고, 본문이 질문 원문의 문장 단위 구간이고 제목이 본문 안에 있을 때만 바로 저장한다.
근거 구절만 빌리고 뜻을 바꾼 본문(「매운 음식을 못 먹는다」 에서 본문 「매운 음식을 좋아한다」)이 바로 저장되지 않게 한다.

**범위 외**: 민감해 보이는 낱말 검사(phase 02), 기능 도입 전 실행을 세는 쿼리(phase 03).

## 컨텍스트

**근거 문서**: `docs/backend/memory.md` 의 「에이전트가 기억을 남기는 길」 절, `docs/adr/ADR-20261008-memory-remember-guard.md`

- 판정 계약: `docs/backend/memory.md` 의 「바로 저장 판정」 표 1 부터 6 과 「문장 단위 구간」 절.
- 근거: `docs/adr/ADR-20261008-memory-remember-guard.md`, `docs/adr/ADR-20261007-memory-remember.md`.
- 지금 판정은 `backend/src/main/java/com/bifos/assistant/mcp/application/McpMemoryRemember.java` 의 `directAllowed(McpCaller, AgentExecution, String evidence)` 가 한다.
  `evidence` 를 `normalized()` 로 맞춰 질문 원문(`TurnQuestions.questionOf(executionId, userId)`)에 `contains` 하는지 본다. 이것을 본문 판정으로 바꾼다.
- 시험은 `backend/src/test/java/com/bifos/assistant/mcp/McpMemoryRememberToolTest.java` 다. `@BackendIntegrationTest` 로 실제 HTTP 경계에서 부른다.
  질문 상수는 `QUESTION = "우리 집   다른 사람은 홍길동이야. 기억해 줘"` 이고, 지금 시험들이 본문 「다른 사람은 홍길동이다」 처럼 사용자의 말을 바꾼 본문을 바로 저장한다고 단언한다.
  이 phase 뒤에는 그 본문이 제안이 되므로 바로 저장을 기대하는 시험의 본문을 「다른 사람은 홍길동이야」 처럼 질문의 문장 그대로로 바꾼다.
- 지침 글 `backend/src/main/java/com/bifos/assistant/context/ContextAssembler.java` 의 `MEMORY_INSTRUCTIONS` 와 시험 `backend/src/test/java/com/bifos/assistant/context/ContextAssemblerTest.java` 91행이 「evidence」 를 단언한다.
- 패키지 규칙: `application` 은 타입 하나에 파일 하나(`backend/AGENTS.md`). 주석과 `@DisplayName` 은 한국어다.

## 의도 메모

- `evidence` 를 스키마에서 지우지 않는다. 앞선 도구 정의로 부르는 Hermes 연결이 `-32602` 를 받는다. 길이 검사(500자)는 그대로 두고 판정에만 쓰지 않는다.
- 낱말 경계를 구간의 끝으로 인정하지 않는다. 「좋아하지 않아」 에서 「좋아하지」 만 떼면 뜻이 바뀐다. 쉼표도 끝으로 인정하지 않는다(요청 구절 앞의 쉼표만 예외).
- 문장부호를 끝으로 볼 때 그 뒤가 공백이나 글 끝이어야 한다. 「3.5」 에서 「3」 을 떼지 못하게 한다. 앞 경계도 문장부호 뒤에 공백이 있어야 한다.
- 명시적 요청 구절(「기억해 줘」)을 바로 저장의 조건으로 삼지 않는다(2026-10-08 사용자 결정). 구간의 경계로만 쓴다.
- 제목 검사는 본문을 같은 방식으로 맞춘 글에 `contains` 다. 제목은 색인에 본문 없이 실린다.

## 작업 항목

### 1. `backend/src/main/java/com/bifos/assistant/mcp/application/UserStatementSpan.java` 신규

패키지 안에서만 쓰는 `final class UserStatementSpan` 과 정적 메서드 하나를 둔다.

```java
/** 본문이 질문 원문의 문장 단위 구간이고 제목이 그 본문 안에 있는가(ADR-20261008 / memory-remember-guard). */
static boolean matches(String question, String title, String content)
```

규칙(`docs/backend/memory.md` 「문장 단위 구간」 과 같다):

1. 질문을 NFC 로 맞춘다. 줄바꿈이 든 공백 묶음은 `"\n"` 한 글자로, 그 밖의 공백 묶음은 `" "` 한 글자로 바꾸고 앞뒤를 지운다(`marked`).
   `marked` 의 `"\n"` 을 `" "` 로 바꾼 글(`flat`)을 찾기에 쓴다. 두 글은 길이와 위치가 같다.
2. 본문을 NFC 로 맞추고 모든 공백 묶음을 `" "` 로 바꾸고 앞뒤를 지운다. 끝의 `.` `!` `~` `…` `。` `！` `～` 를 모두 떼고 다시 앞뒤 공백을 지운다.
   본문에 `?` 나 `？` 가 있으면 `false`. 공백을 뺀 길이(code point)가 2 미만이면 `false`.
3. 제목을 같은 방식(NFC, 공백 묶음 `" "`, 앞뒤 공백 제거)으로 맞춘다. 맞춘 본문에 들어 있지 않으면 `false`.
4. `flat.indexOf(body, from)` 로 모든 자리를 돌며 앞 경계와 끝 경계가 모두 맞는 자리가 하나라도 있으면 `true`.
   - 앞 경계: 자리가 0. 또는 `marked` 의 바로 앞 글자가 `"\n"`. 또는 바로 앞 글자가 `" "` 이고 그 앞의 글(`marked` 의 처음부터 공백 앞까지)이
     문장부호(`.` `!` `?` `~` `…` `。` `！` `？` `～`)나 콜론(`:` `：`)으로 끝나거나 요청 구절로 끝난다.
   - 끝 경계: 구간 뒤의 글(`after`)이 비었다. 또는 `"\n"` 으로 시작한다. 또는 `^[.!~…。！～]+(?:\s|$)` 에 맞는다(물음표 없음).
     또는 `^[,，]?\s+` 다음에 요청 구절이 온다.
5. 요청 구절은 하나의 `Pattern` 상수로 둔다: `(?:기억|기록|메모)\s?해(?:\s?(?:줘|주세요|둬|두세요|놔|놓아|줄래|둘래))?|잊지\s?(?:마|말아)`.
   앞 경계에서는 요청 구절 뒤에 `[.!~:：]*` 만 허용하고 끝에 닿아야 한다. 끝 경계에서는 요청 구절이 `after` 의 공백 뒤에서 시작하면 된다.

### 2. `backend/src/main/java/com/bifos/assistant/mcp/application/McpMemoryRemember.java` 수정

- `directAllowed(McpCaller caller, AgentExecution origin, String title, String content)` 로 바꾼다. `evidence` 를 받지 않는다.
  `origin.parentExecutionId() != null` 이나 `origin.conversationId() == null` 이면 `false`. 질문이 없거나 `UserStatementSpan.matches(question, title, content)` 가 거짓이면 `false`. 대화 단위 두 검사는 그대로다.
- `remember(...)` 는 `strippedTitle`, `strippedContent` 로 `directAllowed` 를 부른다. `evidence` 길이 검사는 그대로 둔다.
- 쓰지 않게 된 `MEMORY_EVIDENCE_MIN`, `normalized()`, `WHITESPACE` 를 지운다(`within` 은 남는다).
- 클래스와 `remember` 의 Javadoc 에서 「근거 인용이 그 질문 원문에 있고」 를 「본문이 그 질문 원문의 문장 그대로이고 제목이 본문 안에 있고」 로 고치고 ADR-20261008 / memory-remember-guard 를 함께 가리킨다.
- `MEMORY_REMEMBER_DESCRIPTION` 에서 evidence 문장을 아래 두 문장으로 바꾼다. 나머지 문장은 그대로 둔다.
  「사용자가 이번 메시지에서 직접 말한 사실이면 content 에 그 문장을 고치지 않고 그대로 넣고, title 은 그 문장 안의 낱말로 짧게 짓는다(예: 「아들 이름」). 말을 다듬거나 바꾼 본문은 제안으로 남는다.」

### 3. `backend/src/main/java/com/bifos/assistant/context/ContextAssembler.java` 수정

`MEMORY_INSTRUCTIONS` 의 「evidence 에는 사용자 메시지의 구절을 그대로 넣는다.」 를
「content 에는 사용자가 이번 메시지에 쓴 문장을 고치지 않고 그대로 넣고, title 은 그 문장 안의 낱말로 짓는다.」 로 바꾼다.

### 4. 시험

- `backend/src/test/java/com/bifos/assistant/mcp/application/UserStatementSpanTest.java` 신규. 순수 단위 시험(`@ParameterizedTest` 나 `@Test` 여럿, 모두 `@DisplayName`).
  - 참: `("우리 집   다른 사람은 홍길동이야. 기억해 줘", "다른 사람", "다른 사람은 홍길동이야")`, 본문 끝에 `.` 을 붙인 같은 입력,
    `("아들 이름은 홍길동이야 기억해 줘", "아들 이름", "아들 이름은 홍길동이야")`, 두 줄 질문의 둘째 줄, 콜론 뒤 구간(`"기억해 줘: 딸은 열 살이야"`).
  - 거짓: 반례 `("매운 음식을 못 먹는다", "매운 음식", "매운 음식을 좋아한다")`, 부정 떼기 `("매운 음식을 좋아하지 않아", "매운 음식", "매운 음식을 좋아하지")`,
    숫자 떼기 `("체중은 3.5kg 이다.", "체중", "체중은 3")`, 물음표 `("매운 음식 좋아해?", "매운 음식", "매운 음식 좋아해")`,
    제목이 본문 밖 `("다른 사람은 홍길동이야.", "다른 사람 이름은 김철수", "다른 사람은 홍길동이야")`, 한 글자 본문, 쉼표 끝 `("매운 건 좋아하는데, 오이는 싫어", "매운 건", "매운 건 좋아하는데")`.
- `backend/src/test/java/com/bifos/assistant/mcp/McpMemoryRememberToolTest.java` 수정.
  - 바로 저장을 기대하는 모든 호출의 본문을 질문 문장 그대로(「다른 사람은 홍길동이야」)로 바꾼다. 제목 「다른 사람」 은 그대로 둔다.
  - `proposesWithoutMatchingEvidence` 를 「본문이 질문 문장과 다르면 근거가 맞아도 제안이다」 로 바꾼다: `remember("다른 사람", "다른 사람은 김철수다", "다른 사람은 홍길동이야")` 와 `remember("직업", "교사다", "나는 교사야")` 가 PROPOSED.
  - 반례 시험 하나를 더한다: 질문 「매운 음식을 못 먹는다.」 를 그 turn 의 질문으로 이은 뒤 `remember("매운 음식", "매운 음식을 좋아한다", "매운 음식")` 가 PROPOSED 이고 항목이 `PROPOSED` 다.
  - 제목이 본문 밖이면 제안이라는 시험 하나를 더한다.
  - `updatesAndRestoresExistingFact` 와 `refusesUpdateWithoutDirectConditions` 처럼 「먼저 다른 값을 바로 저장」 하던 시험은 첫 저장을 다른 질문으로 한다.
    예: 첫 실행에 질문 「다른 사람은 김철수야.」 를 이어 바로 저장한 뒤, 같은 실행의 질문 줄을 지우지 말고 새 루트 실행과 질문 「다른 사람은 홍길동이야.」 를 만들어 `memory_id` 로 고친다.
    새 실행을 만들기 어려우면 첫 항목을 `MemoryService` 로 직접 `ACCEPTED` 로 만들어도 된다. 되돌리면 이전 본문으로 돌아가는 단언은 그대로 둔다.
  - `listsMemoryRememberLast` 의 `.contains("evidence")` 를 `.contains("content 에 그 문장을 고치지 않고")` 로 바꾼다.
  - 클래스 Javadoc 의 「근거 인용이 질문 원문에 있고」 를 새 조건으로 고친다.
- `backend/src/test/java/com/bifos/assistant/context/ContextAssemblerTest.java` 91행의 `"evidence"` 를 `"고치지 않고 그대로"` 로 바꾼다.

## 검증

```bash
cd backend && ./gradlew test --tests 'com.bifos.assistant.mcp.application.UserStatementSpanTest' --tests 'com.bifos.assistant.mcp.McpMemoryRememberToolTest' --tests 'com.bifos.assistant.context.ContextAssemblerTest' --tests 'com.bifos.assistant.mcp.application.McpToolServiceTest'
cd backend && ./gradlew checkstyleMain checkstyleTest archTest
```

기대값: 모두 통과. `git grep -n "MEMORY_EVIDENCE_MIN" backend/src/main` 결과가 없다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/mcp/application/UserStatementSpan.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/mcp/application/McpMemoryRemember.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/context/ContextAssembler.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/mcp/application/UserStatementSpanTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/mcp/McpMemoryRememberToolTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/context/ContextAssemblerTest.java` | 수정 |
