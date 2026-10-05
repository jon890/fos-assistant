# Phase 01. 무소식 결과도 질문과 읽지 못한 출처를 그린다

**Execution profile**: fast

## 목표

살펴보기 결과 블록이 `outcome = NOTHING_NEW` 이고 `findings` 가 비어도, `questions` 나 `sourceFailures` 가 있으면 알림 줄 대신 그린 답을 남긴다.
셋이 모두 비었을 때만 지금처럼 「살펴봤지만 새로 알릴 것이 없어요」 알림 줄 하나를 남긴다. GitHub 이슈 #188 이다.

**범위 외**: 원문 검사를 거치지 않은 `summary` 와 `followUpCandidates` 를 다시 보이는 것(하지 않는다). 매일 깨우기(아직 없다). 반복된 출처 장애를 줄이는 억제(지금 코드에 없다. 새로 만들지 않는다).

## 컨텍스트

- `backend/src/main/java/com/bifos/assistant/proactive/application/ProactiveCheckRun.java` 의 `answer(Long executionId, String output)` 가 결과 블록을 읽어 `CheckAnswer(text, notice)` 를 낸다(`CheckAnswer` 는 `backend/src/main/java/com/bifos/assistant/chat/application/model/CheckAnswer.java`). `notice = true` 면 `ChatService` 가 `SYSTEM` 알림 줄로, false 면 `ASSISTANT` 답으로 남긴다
  - 지금 지름길: `block.outcome() == CheckOutcome.NOTHING_NEW && block.findings().isEmpty()` 면 바로 `new CheckAnswer(NOTHING_NEW_NOTICE, true)` 를 낸다. 이 때문에 `CheckResultParser` 가 읽은 `questions` 와 `sourceFailures` 가 `CheckAnswerRenderer` 에 닿지 않는다
  - 지름길이 아니면 발견을 `FindingJudgement.judge` 로 판정하고 `deps.renderer().render(block, judged)` 로 그린다. 발견이 비면 `judged` 도 빈 목록이다
- `backend/src/main/java/com/bifos/assistant/proactive/application/CheckAnswerRenderer.java` 의 `render` 는 「새로 알릴 것」 이 없으면 `summary` 와 「할 일 후보」 를 그리지 않고, 질문이 없을 때만 「새로 알릴 것은 없어요」 한 줄을 넣는다. 질문과 「확인하지 못한 출처」 절은 그린다. 모델 글은 모두 이스케이프한다. **이 클래스는 고치지 않는다**
- `outcome` 은 블록의 값 그대로 `NOTHING_NEW` 로 적힌다(`outcome = block.outcome()`). 바꾸지 않는다

**근거 문서**: `docs/backend/proactive-check.md` 의 「대화에 남는 것」 표와 「검사」 끝 문단, 「그리기」. `docs/adr/ADR-081-살펴보기-결과는-답-끝의-구조화-블록으로-받고-control-plane-이-검사해-그린다.md` 의 「할 말이 없으면 침묵한다」(이 plan 이 이미 고쳤다)

## 의도 메모

- 모순된 결과(`NOTHING_NEW` 인데 질문이 있다)를 거절하는 안은 기각한다. 거절하면 `INVALID_RESULT` 가 되어 질문과 출처 장애가 모두 사라진다
- 지름길 조건만 바꾼다. 그리기 규칙은 이미 「새로 알릴 것」 이 없을 때 검사하지 않은 글을 막는다

## 작업 항목

### 1. `ProactiveCheckRun.answer` 의 지름길 조건

지름길을 `findings`, `questions`, `sourceFailures` 가 모두 빌 때로 한정한다. 메서드 Javadoc 에 「`NOTHING_NEW` 여도 질문이나 읽지 못한 출처가 있으면 그린다」 를 한 문장 더한다.

### 2. 이 phase 를 검증하는 단위 검사

`backend/src/test/java/com/bifos/assistant/proactive/ProactiveCheckTurnTest.java` 에 더한다. 기존 `leavesOnlyNoticeForNothingNew` 의 모양(`stub().willAnswer(command -> answer(block(...)))`, `runCheck()`, `messages.findByConversationIdOrderByIdAsc`, `onlyCheckOf`)을 따른다. 기존 검사는 그대로 통과해야 한다.

| `@DisplayName` | 블록 | 기대 |
| --- | --- | --- |
| NOTHING_NEW 에 읽지 못한 출처가 있으면 그 절을 그린 답이 남는다 | `{"version":1,"outcome":"NOTHING_NEW","findings":[],"sourceFailures":["시험 출처를 읽지 못했어요 [링크](https://example.com)"]}` | 두 번째 메시지가 `ASSISTANT` 이고 「새로 알릴 것은 없어요」 와 `**확인하지 못한 출처**` 를 담는다. 출처 글의 `[`, `]`, `(`, `)`, `:`, `.` 이 백슬래시로 이스케이프돼 링크가 되지 않는다. `outcome` 은 `NOTHING_NEW`, 발견 줄은 없다 |
| NOTHING_NEW 에 질문이 있으면 질문을 그린 답이 남는다 | `{"version":1,"outcome":"NOTHING_NEW","questions":["이번 달도 같은 분야를 볼까요"]}` | 두 번째 메시지가 `ASSISTANT` 이고 `**물어보고 싶은 것**` 과 그 질문을 담는다. 「새로 알릴 것은 없어요」 는 없다(질문이 있으면 그리지 않는 기존 규칙) |
| NOTHING_NEW 의 요약과 할 일 후보는 질문이 있어도 그리지 않는다 | 위 질문에 `"summary":"검사하지 않은 요약","followUpCandidates":["검사하지 않은 후보"]` 를 더한다 | 답에 두 글이 없다 |

### 3. e2e 보강

`test/e2e/scenarios/proactive-check.ts` 의 「연결 해제와 출처 실패」 단계(지금은 `outcome: "FINDINGS", findings: [], sourceFailures: [SOURCE_FAILURE]` 를 쓴다) 바로 뒤에 같은 연결 해제 상태에서 `outcome: "NOTHING_NEW"`, `sourceFailures: [SOURCE_FAILURE]` 로 한 번 더 살펴보기를 돌리는 단계를 더한다. 앞 단계처럼 `statusOf`, `startCheck`, `awaitFinished`, `lastAnswer(messagesOf(...))` 를 쓴다. 대역이 실행을 붙잡을 필요는 없으면 `hold` 를 빼고, 앞 단계의 `held` 처리를 그대로 따라 한다.
기대: 마지막 답이 「새로 알릴 것은 없어요」 와 `**확인하지 못한 출처**\n- ${SOURCE_FAILURE}` 를 담는다.
`lastAnswer` 가 `ASSISTANT` 만 찾는지 읽고, 아니라면 역할을 함께 확인한다.

## 검증

```bash
cd backend && ./gradlew test --tests 'com.bifos.assistant.proactive.*'
node test/e2e/run.ts
scripts/check-local.sh proactive-check
```

- 첫 줄: 새 검사 셋과 기존 `ProactiveCheckTurnTest`, `ProactiveCheckRecoveredAnswerTest` 가 통과한다
- 둘째 줄: e2e 의 살펴보기 시나리오가 새 단계까지 통과한다. 필요한 Node 버전은 `scripts/check-local.sh` 가 검사한다
- 셋째 줄: 전체 로컬 검사. 브라우저 검사는 `test/browser/proactive-check.spec.ts` 만 돈다

## 변경 파일

| 파일 | 변경 |
| --- | --- |
| `backend/src/main/java/com/bifos/assistant/proactive/application/ProactiveCheckRun.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/proactive/ProactiveCheckTurnTest.java` | 수정 |
| `test/e2e/scenarios/proactive-check.ts` | 수정 |
| `docs/backend/proactive-check.md` | 수정 |
| `docs/adr/ADR-081-살펴보기-결과는-답-끝의-구조화-블록으로-받고-control-plane-이-검사해-그린다.md` | 수정 |
