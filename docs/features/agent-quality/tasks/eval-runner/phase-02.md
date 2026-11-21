# Phase 02. 묶음 파일을 읽고 서비스 개념 묶음과 판정을 옮긴다

**Execution profile**: standard

## 목표

`quality/bundles/*.json` 의 묶음 파일을 기동 때 읽어 검사하고, 서비스 개념 평가의 문항을 첫 묶음 파일로 옮긴다. 새 묶음은 파일만 더하면 실행기를 고치지 않고 돈다(REQ5). `test/model-eval/concepts/score.ts` 의 판정을 네트워크 없는 Java 순수 함수로 옮기고 승인 카드 결과(`cardStatus`) 판정을 더한다(REQ6, REQ16).

**범위 외**: 관찰값 모으기와 실행(phase 05). 답변 품질 묶음과 `answer-quality` 통과 규칙은 다음 계획이다.

## 컨텍스트

- 문항 형식의 원본은 `docs/features/agent-quality/design.md` 의 「묶음 파일」 표다. 옛 형식은 `test/model-eval/concepts/questions.json` 과 그 형식 설명 `docs/features/service-concepts/design.md` 의 「평가 세트」 다.
- 자리(`{otherAgentCode}`, `{otherAgentName}`, `{connectorId}`, `{connectorTitle}`)를 채우는 규칙은 `test/model-eval/concepts/score.ts` 의 `fill` 이다. 쓴 자리의 값이 하나라도 비면 그 문항은 건너뛴다.
- 설정 record 는 `quality/config/QualityProperties.java`(PR1)다. 바뀌는 값의 자리는 `application.yml` 의 `assistant.quality` 아래 `${...}` 꼴(PR1 의 `feature-timeout` 과 같다)이다.

**요구사항**: REQ5, REQ6, REQ16

**근거 문서**: `docs/features/agent-quality/design.md` 의 「묶음 파일」, 「판정」, 「여러 차례와 승인 카드」, DECISION10, `docs/features/service-concepts/design.md` 의 「평가 세트」

옮길 함수: `answerLinks`, `linkedTaskIds`, `completedTools`, `scoreQuestion`, `scoreSet`(`PASS_RATIO`, `skippedQuestion`, `failedQuestion`). 옛 테스트 사례는 `test/model-eval/concepts/concepts-eval-score.test.ts` 다. PR1 의 판정 관례는 `quality/application/DeployCheckJudge.java` 다.

## 의도 메모

- 파일 형식 오류는 기동을 멈춘다. 운영에서 틀린 묶음이 조용히 빠지면 기능 확인이 돌지 않은 것을 모른다.
- 옛 `concept` 칸은 `tag` 로 옮긴다. 판정에는 쓰지 않고 결과 보기에서 묶는다.
- JSON 은 문항 하나를 몇 줄로 묶는 꼴로 쓴다. 운영 코드 줄 수 상한 안에 들게 하려는 것이다.
- 이 계획 동안 `pass.type` 은 `ratio` 만 받는다. `answer-quality` 는 형식 오류로 막는다. 다음 계획(답변 품질 묶음)이 그 규칙과 함께 푼다.
- 실패 까닭은 고정 코드(`MISSING_TOOL`, `MISSING_LINK`, `FORBIDDEN_LINK`, `MISSING_WORD`, `FORBIDDEN_WORD`, `MISSING_INSTRUCTION`, `CARD_STATUS`)와 대상 이름만 담는다. 답 본문은 담지 않는다.
- 보내기·읽기 오류(`RUN_FAILED`, `BUSY`, `TIMED_OUT`)로 끝난 문항은 옛 `scoreSet` 의 오류처럼 분모에 들고 통과로 세지 않는다. 오류 문항이 하나라도 있으면 묶음 결과에 오류 수를 함께 적는다.

## 작업 항목

### 1. `quality/domain/EvalBundle.java`, `quality/domain/type/CardAction.java`

record `EvalBundle(id, title, timeoutMinutes, PassRule pass, List<EvalQuestion> questions)`, `EvalQuestion(id, tag, List<EvalTurn> turns, EvalExpect expect, boolean keepConversation)`, `EvalTurn(text, attachment, CardAction card)`, `EvalExpect`(design 표의 칸, 그리고 `cardStatus`), `PassRule(type, ratio)`. `CardAction` 은 `APPROVE`, `REJECT`, `LEAVE` 이고 JSON 값은 `approve`, `reject`, `leave` 다. `timeoutMinutes` 없으면 10, `pass` 없으면 `ratio` 0.85.

### 2. `quality/application/EvalBundles.java`

`QualityProperties.bundleLocation`(기본 `classpath:quality/bundles/`)의 `*.json` 을 `ResourcePatternResolver` 로 읽는다. 검사: `id` 가 파일 이름과 같다, 묶음 id 와 묶음 안 문항 id 가 겹치지 않는다, `turns` 가 비지 않았다, 자리 이름이 위 넷 중 하나다, `timeoutMinutes` 가 양수, `ratio` 가 0 초과 1 이하, `type` 이 `ratio`(이 계획 동안 `answer-quality` 는 오류). 어기면 파일 이름과 까닭을 담은 `IllegalStateException` 으로 기동을 멈춘다. `all()`, `find(id)` 와 자리 채우기 `fill(question, values)`(값이 비면 `Optional.empty()`)를 둔다.

### 3. 설정

`QualityProperties` 에 `bundleLocation` 을 더하고 `application.yml` 에 `bundle-location: ${ASSISTANT_QUALITY_BUNDLE_LOCATION:classpath:quality/bundles/}` 를 둔다.

### 4. `backend/src/main/resources/quality/bundles/service-concepts.json`

`test/model-eval/concepts/questions.json` 의 문항을 모두 옮긴다. `messages` 의 글마다 `turns` 의 `text` 하나, `concept` 는 `tag`, `expect` 는 그대로. 묶음은 `id` `service-concepts`, `title` 「서비스 개념」, `pass` `ratio` 0.85, `timeoutMinutes` 10. 문항 글을 고치지 않는다.

### 5. `quality/application/dto/EvalObservation.java`, `quality/application/EvalJudge.java`

`EvalObservation`: 마지막 답 글, 성공으로 끝난 도구 이름 집합(접두사 `mcp__fos_assistant__` 를 뗀 이름), 답이 링크한 예약 작업 id 별 지시문, 마지막으로 누른 카드 줄의 상태(없으면 null).
`EvalJudge.judge(EvalQuestion, EvalObservation)` → 통과 여부와 까닭 목록. 도구는 옛 `completedTools` 처럼 실행 트리의 실패 아닌 `TOOL_COMPLETED` 사건 이름에서 접두사를 떼어 기대 이름과 같은지 본다. `linkedTaskIds(answer)` 는 phase 05 가 지시문을 읽을 때 쓴다. `bundleResult(PassRule, List<ItemResult>)` 는 `SKIPPED` 를 분모에서 빼고 `ceil(ratio × 돈 문항)` 이상 통과면 통과다.

### 6. 테스트

- `EvalBundlesTest`(단위): 실제 리소스가 읽히고 `service-concepts` 가 있다. 테스트 리소스 폴더의 틀린 파일(겹친 id, 모르는 자리, 빈 turns, 0 인 상한)마다 예외 문구에 파일 이름이 든다. `fill` 이 빈 값이면 비고, 채우면 `turns` 와 `expect` 의 자리가 모두 바뀐다.
- `EvalJudgeTest`: `concepts-eval-score.test.ts` 의 사례를 하나씩 옮긴다(절대 주소, 맨 경로, 마크다운 링크, 날짜나 분수 같은 슬래시는 링크로 읽지 않음, 도구 이름 일치, 실패한 도구 사건 무시, 지시문 `|` 낱말, 묶음 85% 올림, 오류 문항). `cardStatus` 가 맞을 때, 다를 때, 카드를 누르지 않았을 때를 더한다.
- `test/repo/quality-bundles.test.ts`(신규): `service-concepts.json` 의 문항 id, 글, 기대가 `test/model-eval/concepts/questions.json` 과 같다. 셋째 계획이 옛 폴더를 지울 때 이 테스트도 지운다.

## 검증

```bash
cd backend && ./gradlew test --tests 'com.bifos.assistant.quality.application.EvalBundlesTest' --tests 'com.bifos.assistant.quality.application.EvalJudgeTest'
node --test test/repo/quality-bundles.test.ts
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/quality/domain/EvalBundle.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/quality/domain/type/CardAction.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/quality/application/EvalBundles.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/quality/config/QualityProperties.java` | 수정 |
| `backend/src/main/resources/application.yml` | 수정 |
| `backend/src/main/resources/quality/bundles/service-concepts.json` | 신규 |
| `backend/src/main/java/com/bifos/assistant/quality/application/dto/EvalObservation.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/quality/application/EvalJudge.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/quality/application/EvalBundlesTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/quality/application/EvalJudgeTest.java` | 신규 |
| `backend/src/test/resources/quality/bad-bundles/**` | 신규·수정 |
| `test/repo/quality-bundles.test.ts` | 신규 |
