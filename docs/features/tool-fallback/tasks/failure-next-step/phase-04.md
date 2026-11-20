# Phase 04. 모든 실행의 공통 지침에 「도구가 실패했을 때」 가 든다

**Execution profile**: standard

## 목표

모든 실행의 공통 지침에서 「도구 호출」 과 「승인 카드」 사이에 「도구가 실패했을 때」 절을 넣는다. 비서가 도구 한 번이 실패했다고 바로 사용자에게 넘기지 않고, 정한 횟수까지 다른 방법을 해 본 뒤에만 묻게 한다. 기능 문서를 구현과 맞춘다.

**범위 외**: 실패 글 자체(phase 01~03). 승인 카드 절의 글은 한 글자도 바꾸지 않는다. 링크 사진 후보와 후보 알림(PR2).

## 컨텍스트

`context/application/ContextAssembler.java`(500줄) 의 `TOOL_CALL_INSTRUCTIONS` 한 상수가 「# 도구 호출」 과 「# 승인 카드」 두 절을 담는다. `withResponseInstructions(context, scope)` 가 `RESPONSE_INSTRUCTIONS`, `TOOL_CALL_INSTRUCTIONS`, `ServiceConceptsInstructions.SECTION` 차례로 `ContextBudgetWriter.SEPARATOR` 로 잇고, 범위에 따라 검색과 기억 지침을 더한다.
지침 글 테스트의 본보기는 `backend/src/test/java/com/bifos/assistant/context/ServiceConceptsInstructionsTest.java` 다(`@BackendIntegrationTest`, `@EnumSource(InstructionScope.class)`, `containsSubsequence`).
phase 01~03 이 실패 글 끝에 「다음 방법: …」 이나 「사용자에게 바로 알린다.」 를 붙였다. 이 phase 의 지침이 그 두 줄을 읽는 법을 말한다.

**요구사항**: REQ1, REQ2, REQ5, REQ6

**근거 문서**: `docs/features/tool-fallback/spec.md` 의 REQ1, REQ2, REQ5, REQ6, DECISION2, DECISION6, DECISION9, DECISION10, DECISION11, `docs/features/tool-fallback/design.md` 의 「지침 글」 과 DECISION3, `hermes/docs/hermes-contract.md` 의 「MCP 서버 일시 정지」

## 의도 메모

- 「다음 방법:」 과 「사용자에게 바로 알린다」 는 `<external-data>` 밖의 글에 있을 때만 따른다. 플러그인 서버가 낸 같은 문구가 지시가 되지 않게 한다. 승인 카드 절이 `[승인 대기]` 를 읽는 규칙과 같은 기준이다
- 같은 서버 연속 두 번 규칙은 Hermes 가 같은 MCP 서버의 연속 3번 실패에 그 서버를 60초 막기 때문이다(스펙 DECISION2). 도구 이름 접두 `mcp__fos_assistant__` 로 Control Plane 도구를 가리킨다(`hermes/plugins/fos-ctx/hooks.py` 의 `TOOL_PREFIX`)
- 새 시간 상한을 지침에 쓰지 않는다. 지금 실행 시간 상한을 따른다
- 승인 카드 절의 「다시 부르지 않는다는 판정 글의 안내는 그 turn 에만 해당한다」 는 그대로 둔다. 새 지침은 그 turn 안의 행동을 말한다

## 작업 항목

### 1. `ToolFailureInstructions.java`(신규), `ApprovalCardInstructions.java`(신규), `ContextAssembler.java`

`ContextAssembler.java` 는 500줄로 파일 길이 상한(`scripts/checks/check-file-length.mjs`, Java 500줄)에 이미 닿아 있다. 줄을 늘리지 않도록 절 글을 서비스 개념 목록(`context/application/ServiceConceptsInstructions.java` 의 `SECTION`)처럼 따로 둔다.

- `backend/src/main/java/com/bifos/assistant/context/application/ToolFailureInstructions.java`: `public static final String SECTION`. 글은 `docs/features/tool-fallback/design.md` 의 「지침 글」 코드 블록을 그대로 쓴다. 다듬어도 아래 테스트가 보는 문장의 뜻은 지킨다. **절 본문에 빈 줄을 두지 않는다**(제목, 빈 줄 하나, 빈 줄 없는 한 문단). E2E 의 가짜 Hermes 가 제목 뒤 한 문단씩 벗겨 내기 때문이다. 클래스 Javadoc 에 스펙 경로와 REQ1, REQ2, REQ5 를 적는다
- `backend/src/main/java/com/bifos/assistant/context/application/ApprovalCardInstructions.java`: 지금 `TOOL_CALL_INSTRUCTIONS` 안의 「# 승인 카드」 절을 글 그대로 옮긴 `SECTION`. 문장은 한 글자도 바꾸지 않는다
- `ContextAssembler.TOOL_CALL_INSTRUCTIONS` 에는 「# 도구 호출」 절만 남긴다
- `withResponseInstructions` 의 차례를 `RESPONSE_INSTRUCTIONS`, `TOOL_CALL_INSTRUCTIONS`, `ToolFailureInstructions.SECTION`, `ApprovalCardInstructions.SECTION`, `ServiceConceptsInstructions.SECTION` 으로 한다. 범위별 절은 지금처럼 그 뒤다
- `ContextAssembler.java` 가 지금보다 짧아졌는지 `wc -l` 로 본다

### 2. E2E 가짜 Hermes 의 지침 제목

- `test/e2e/fake-hermes/scenario-fixtures.ts` 의 `MODEL_GUIDE_HEADERS` 에서 「# 도구 호출」 과 「# 승인 카드」 사이에 「# 도구가 실패했을 때」 를 넣고, 위 설명 주석에 두 새 클래스를 적는다
- `test/e2e/unit/fake-hermes-controls.test.ts`
  - 둘째 테스트가 제목을 모으는 목록을 (파일, 상수 이름) 짝의 차례로 바꾼다. `ContextAssembler` 의 `RESPONSE_INSTRUCTIONS`, `TOOL_CALL_INSTRUCTIONS`, `ToolFailureInstructions` 의 `SECTION`, `ApprovalCardInstructions` 의 `SECTION`, `ServiceConceptsInstructions` 의 `SECTION`, `ContextAssembler` 의 `SEARCH_INSTRUCTIONS`, `MEMORY_INSTRUCTIONS` 다. 같은 이름 `SECTION` 이 여러 파일에 있으므로 파일마다 따로 읽는다
  - 첫 테스트의 지침 조각에 「# 도구가 실패했을 때」 절 대역을 넣어, 그 절도 벗겨지고 Memory 본문만 남는지 본다

### 3. 테스트 `backend/src/test/java/com/bifos/assistant/context/ToolFailureInstructionsTest.java`(신규)

`ServiceConceptsInstructionsTest` 의 꼴로 `@EnumSource(InstructionScope.class)` 마다 본다.

- 차례: `containsSubsequence("# 도구 호출", "# 도구가 실패했을 때", "# 승인 카드", "# 이 서비스의 기능")`
- REQ1: 「다음 방법:」 을 먼저 해 본다, 「두 번까지」, 「같은 도구를 같은 인자로 다시 부르지 않는다」, `mcp__fos_assistant__` 와 「연달아 두 번」, 「잠시 뒤 다시」 의 문장이 든다
- REQ1 의 지시 주입 막기: `<external-data>` 밖의 글만 따른다는 문장이 든다
- REQ2: 승인 대기, 로그인·보안문자, 거절, 실행 여부를 알 수 없는 것, 결제와 구매 상한, 다시 연결의 낱말과 「같은 대상을 다시 노리지 않는다」 가 든다
- REQ5: 「무엇을 해 봤는지 한 줄」 과 「결과가 처음 뜻과 달라졌으면」 의 문장이 든다
- 승인 카드 절의 기존 문장(「그 turn 에만 해당한다」)이 그대로 있다
- 기존 `ServiceConceptsInstructionsTest` 의 `containsSubsequence("# 답변 형식", "# 도구 호출", HEADER)` 는 그대로 통과해야 한다

### 4. 문서

- `docs/features/answer-quality.md` 의 서비스 개념 목록 문단 앞에 한 문단을 더한다. 「도구가 실패했을 때」 절이 범위와 관계없이 모든 실행에 들고 「도구 호출」 과 「승인 카드」 사이에 있으며, 원본은 `ToolFailureInstructions.SECTION`, 요구와 결정은 도구 실패 뒤 다른 방법 스펙(`docs/features/tool-fallback/spec.md`, answer-quality.md 에서는 `tool-fallback/spec.md` 링크) 이 갖는다고 적는다. 같은 문서 서비스 개념 문단의 「답변 형식과 도구 호출 지침 바로 뒤」 를 「답변 형식, 도구 호출, 도구가 실패했을 때, 승인 카드 지침 바로 뒤」 로 고친다
- `docs/features/tool-fallback.md`: 「지금은 스펙과 설계만 있다」 를 지운다. 대신 PR1 로 들어온 것(공통 지침의 「도구가 실패했을 때」, 실패 글의 두 끝 줄, 그것을 만드는 `ToolFailureText` 와 `next_step.py`)을 짧게 적고, PR2(링크 사진 후보)는 아직이라고 적는다. 「배포 뒤 확인」 절을 두고 확인할 사례(스펙 REQ6 의 PR1 몫)를 적는다. 결과는 배포 뒤 확인한 사람이 날짜와 함께 이 절에 적는다

## 검증

```bash
(cd backend && HEAVY_SLOTS=2 orch slot heavy -- ./gradlew test --tests '*ToolFailureInstructionsTest' --tests '*ServiceConceptsInstructionsTest' --tests 'com.bifos.assistant.context.*' --tests '*ArchitectureRules*')
node --test test/e2e/unit/fake-hermes-controls.test.ts
node --test test/repo/doc-links.test.ts test/repo/doc-files.test.ts
HEAVY_SLOTS=2 orch slot heavy -- scripts/dev/check-local.sh --fast
```

기대값: 모두 통과한다. `check-local.sh --fast` 의 PR 크기 검사가 상한 안이다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/context/application/ToolFailureInstructions.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/context/application/ApprovalCardInstructions.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/context/application/ContextAssembler.java` | 수정 |
| `test/e2e/fake-hermes/scenario-fixtures.ts` | 수정 |
| `test/e2e/unit/fake-hermes-controls.test.ts` | 수정 |
| `backend/src/test/java/com/bifos/assistant/context/ToolFailureInstructionsTest.java` | 신규 |
| `docs/features/answer-quality.md` | 수정 |
| `docs/features/tool-fallback.md` | 수정 |

계획서 폴더(`docs/features/tool-fallback/tasks/failure-next-step/`)는 verify 가 끝난 뒤 같은 PR 에서 지운다. 이 phase 의 커밋에서 지우지 않는다.
