# Phase 01. evidence 를 판정에서 빼고 부정이 한쪽에만 있으면 제안으로 내린다

**Execution profile**: deep

## 목표

`memory_remember` 의 바로 저장 판정에서 `evidence` 대조를 빼고, 질문 원문과 본문 가운데 한쪽에만 부정 표지가 있으면 제안으로 내린다.
모델이 다듬은 본문은 바로 저장하되 「매운 음식을 못 먹는다」 를 본문 「매운 음식을 좋아한다」 로 남기는 뒤집힘을 막는다.

**범위 외**: 민감해 보이는 낱말 검사(phase 02), 기능 도입 전 실행을 세는 쿼리(phase 03), 화면(phase 04).

## 컨텍스트

**근거 문서**: `docs/backend/memory.md` 의 「에이전트가 기억을 남기는 길」 절(「바로 저장 판정」 표 1 부터 5, 「부정 표지」), `docs/adr/ADR-20261008-memory-remember-guard.md`

- 지금 판정은 `backend/src/main/java/com/bifos/assistant/mcp/application/McpMemoryRemember.java` 의 `directAllowed(McpCaller, AgentExecution, String evidence)` 가 한다.
  `evidence` 를 `normalized()` 로 맞춰 질문 원문(`TurnQuestions.questionOf(executionId, userId)`)에 `contains` 하는지 본다. 이 대조를 부정 표지 대조로 바꾼다.
- 시험은 `backend/src/test/java/com/bifos/assistant/mcp/McpMemoryRememberToolTest.java` 다. `@BackendIntegrationTest` 로 실제 HTTP 경계에서 부른다.
  `askedInThisTurn()` 이 `QUESTION = "우리 집   다른 사람은 홍길동이야. 기억해 줘"` 를 `dadRun` 의 질문으로 잇는다. 보조 메서드 `remember(title, content, evidence)` 가 인자를 만든다.
- e2e 시나리오 `test/e2e/scenarios/memory-remember-mcp.ts` 의 「근거 인용이 질문에 없으면 제안으로 남고 받아들이면 색인에 실린다」 단계는 `UNQUOTED_TEXT = "우리 집 강아지에 대한 이야기를 하나 할게"` 에 본문 「강아지 이름은 콩이다」 를 주고 PROPOSED 를 기대한다.
  이 phase 뒤에는 이 호출이 바로 저장된다. 이 단계를 부정 뒤집힘 사례로 바꾼다.
- 지침 글 `backend/src/main/java/com/bifos/assistant/context/ContextAssembler.java` 의 `MEMORY_INSTRUCTIONS` 와 시험 `backend/src/test/java/com/bifos/assistant/context/ContextAssemblerTest.java` 91행이 「evidence」 를 단언한다.
- 패키지 규칙: `application` 은 타입 하나에 파일 하나(`backend/AGENTS.md`). 주석과 `@DisplayName` 은 한국어다.

## 의도 메모

- `evidence` 를 스키마에서 지우지 않는다. 앞선 도구 정의로 부르는 Hermes 연결이 `-32602` 를 받는다. 길이 검사(500자)는 그대로 두고 판정에만 쓰지 않는다.
- 본문과 질문의 관계(글자 대조, 제목 검사)는 보지 않는다(2026-10-08 사용자 결정). 부정 표지 하나만 본다.
- 부정은 양쪽을 본다. 질문에만 있어도, 본문에만 있어도 제안이다.
- 「안」 은 「안경」, 「안녕」, 「안방」 을 부정으로 보지 않게 낱말로 떨어진 것과 낱말 처음의 「안」 뒤에 정한 동사 첫 글자가 붙은 것만 본다.

## 작업 항목

### 1. `backend/src/main/java/com/bifos/assistant/mcp/application/NegationMarkers.java` 신규

패키지 안에서만 쓰는 `final class NegationMarkers` 와 정적 메서드 하나.

```java
/** 글에 부정 표지가 있는가(ADR-20261008 / memory-remember-guard). NFC 로 맞추고 연속 공백을 하나로 줄여 본다. */
static boolean present(String text)
```

하나의 `Pattern` 상수로 아래 가운데 하나라도 찾으면 참이다.
- `않`, `없`, `아니` 는 글 어디든
- `못` 은 `(?:^|\s)못` 또는 `지못`
- `안` 은 `(?:^|\s)안(?=$|\s|먹|해|했|하|돼|되|좋|가|갔|와|왔|맞|마시|마셔|읽|봐|봤|보|싫)`

### 2. `backend/src/main/java/com/bifos/assistant/mcp/application/McpMemoryRemember.java` 수정

- `directAllowed(McpCaller caller, AgentExecution origin, String content)` 로 바꾼다. `evidence` 를 받지 않는다.
  `origin.parentExecutionId() != null` 이나 `origin.conversationId() == null` 이면 `false`. 질문이 없으면 `false`.
  `NegationMarkers.present(question) != NegationMarkers.present(content)` 면 `false`. 대화 단위 두 검사는 그대로다.
- `remember(...)` 는 `strippedContent` 로 `directAllowed` 를 부른다. `evidence` 길이 검사는 그대로 둔다.
- 쓰지 않게 된 `MEMORY_EVIDENCE_MIN`, `normalized()`, `WHITESPACE` 를 지운다(`within` 은 남는다). 정규화는 `NegationMarkers` 가 한다.
- `remember` 와 `directAllowed` 의 Javadoc 에서 「근거 인용이 그 질문 원문에 있고」 를 「부정 표지가 질문과 본문에 함께 있거나 함께 없고」 로 고치고 ADR-20261008 / memory-remember-guard 를 함께 가리킨다.
- `MEMORY_REMEMBER_DESCRIPTION` 에서 「사용자가 이번 메시지에서 직접 말한 사실이면 evidence 에 그 메시지의 구절을 고치지 않고 그대로 넣는다.」 를
  「사용자가 이번 메시지에서 직접 말한 사실을 content 에 짧게 옮긴다. 「못」, 「안」, 「않」, 「없」, 「아니」 같은 부정은 content 에 그대로 살린다.」 로 바꾼다. 나머지 문장은 그대로 둔다.

### 3. `backend/src/main/java/com/bifos/assistant/context/ContextAssembler.java` 수정

`MEMORY_INSTRUCTIONS` 의 「evidence 에는 사용자 메시지의 구절을 그대로 넣는다.」 를 「사용자의 말에 있는 부정(못, 안, 않, 없, 아니)은 content 에 그대로 살린다.」 로 바꾼다.

### 4. 시험

- `backend/src/test/java/com/bifos/assistant/mcp/application/NegationMarkersTest.java` 신규. 순수 단위 시험(모두 `@DisplayName`).
  - 참: 「매운 음식을 못 먹는다」, 「먹지못해」, 「오이는 안 먹어」, 「오이 안먹어」, 「좋아하지 않아」, 「차가 없어」, 「교사가 아니야」.
  - 거짓: 「매운 음식을 좋아한다」, 「안경을 쓴다」, 「안녕」, 「안방에서 잔다」, 「아들 이름은 홍길동이야」, 「잘못 들었어」 처럼 낱말 가운데 「못」.
- `backend/src/test/java/com/bifos/assistant/mcp/McpMemoryRememberToolTest.java` 수정.
  - `askedInThisTurn(String question)` 를 더하고 `askedInThisTurn()` 은 그것을 `QUESTION` 으로 부른다.
  - `proposesWithoutMatchingEvidence` 를 「근거가 없거나 질문에 없어도 다듬은 본문은 바로 저장한다」 로 바꾼다: `remember("취미", "등산을 좋아한다", null)` 과 `remember("직업", "교사다", "나는 교사야")` 가 모두 REMEMBERED 이고 항목이 `ACCEPTED`. 판단 피드백은 남지 않는다. 메서드 이름도 뜻에 맞게 바꾼다.
  - 반례 시험을 더한다: `askedInThisTurn("매운 음식을 못 먹는다.")` 뒤 `remember("매운 음식", "매운 음식을 좋아한다", "매운 음식")` 가 PROPOSED 이고 항목이 `PROPOSED`.
  - 반대 방향 시험을 더한다: `askedInThisTurn("매운 음식 좋아해.")` 뒤 `remember("매운 음식", "매운 음식을 못 먹는다", null)` 가 PROPOSED.
  - 부정이 살아 있으면 바로 저장한다는 시험을 더한다: `askedInThisTurn("나는 오이를 안 먹어.")` 뒤 `remember("오이", "오이를 먹지 않는다", null)` 가 REMEMBERED.
  - `listsMemoryRememberLast` 의 `.contains("evidence")` 를 `.contains("부정은 content 에 그대로 살린다")` 로 바꾼다.
  - 클래스 Javadoc 의 「근거 인용이 질문 원문에 있고」 를 새 조건으로 고친다.
  - 나머지 시험의 기대값은 그대로 통과해야 한다(바깥 도구, 질문 없는 실행, 민감 인자, 상한, 되돌리기).
- `backend/src/test/java/com/bifos/assistant/context/ContextAssemblerTest.java` 91행의 `"evidence"` 를 `"그대로 살린다"` 로 바꾼다.
- `test/e2e/scenarios/memory-remember-mcp.ts` 수정. `UNQUOTED_TEXT` 를 「우리 집 강아지는 고기를 못 먹어」 로, 그 단계의 호출을 `{ title: UNQUOTED_TITLE, content: "강아지는 고기를 좋아한다" }` 로 바꾸고 단계 이름과 실패 문구를 「부정이 사라진 본문은 제안으로 남고 받아들이면 색인에 실린다」 로 바꾼다. 상수 이름은 `NEGATED_TEXT` 처럼 뜻에 맞게 바꿔도 된다.

## 검증

`web/node_modules` 가 없으면 먼저 `cd web && pnpm install --frozen-lockfile` 을 돌린다(아래 typecheck 가 web 의 tsc 를 쓴다).

```bash
cd backend && ./gradlew test --tests 'com.bifos.assistant.mcp.application.NegationMarkersTest' --tests 'com.bifos.assistant.mcp.McpMemoryRememberToolTest' --tests 'com.bifos.assistant.context.ContextAssemblerTest' --tests 'com.bifos.assistant.mcp.application.McpToolServiceTest'
cd backend && ./gradlew checkstyleMain checkstyleTest archTest
cd test && npm run typecheck
```

기대값: 모두 통과. `git grep -n "MEMORY_EVIDENCE_MIN" backend/src/main` 결과가 없다. e2e 시나리오 실행은 CI 의 e2e job 이 한다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/mcp/application/NegationMarkers.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/mcp/application/McpMemoryRemember.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/context/ContextAssembler.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/mcp/application/NegationMarkersTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/mcp/McpMemoryRememberToolTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/context/ContextAssemblerTest.java` | 수정 |
| `test/e2e/scenarios/memory-remember-mcp.ts` | 수정 |
