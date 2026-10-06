# Phase 02. 살펴보기 turn 에 문제 후보를 잇는다

**Execution profile**: standard

## 목표

살펴보기 turn 이 버전 3 블록의 문제 후보를 검사해 답 메시지를 저장한 뒤 저장하고, 다음 살펴보기 입력에 최근에 받아들인 후보를 싣고, 지시가 버전 3을 설명하게 한다.
후보는 대화와 보고에 그리지 않는다.

**범위 외**: 후보를 화면이나 API 로 내보내는 일, 우선순위(#214), 분야 스킬(fos-agents) 갱신.

## 컨텍스트

**근거 문서**: `docs/backend/proactive-check.md` 의 「살펴보기가 읽는 맥락」, 「실행에 싣는 것」, 「문제 후보」 절,
`docs/adr/ADR-093-문제-찾기는-살펴보기-결과의-문제-후보로-받고-control-plane-이-근거와-중복을-결정적으로-검사한다.md`.

phase 01 이 만든 것: `ProblemJudgement.judge`, `JudgedProblem`, `ProactiveCheckProblem`, `ProactiveCheckProblemRepository`, `ProblemStatus`, `ProblemDropReason`, `ProblemEvidence`, `FollowUpService.hasOpenWithTitle`.

따를 기존 흐름(`backend/src/main/java/com/bifos/assistant/proactive/application/ProactiveCheckRun.java`):

- `answer` 가 발견을 검사해 `pendingFindings` 에 들고 있다가 `saveFindings` 가 답 메시지 저장 뒤에 저장한다. 후보도 같은 자리에서 `pendingProblems` 로 들고 `saveFindings` 에서 함께 저장한다.
- `announcedSince` 와 `recentFindings` 가 최근 발견을 읽는다. 후보도 같은 `digest-window`, `digest-max-items` 를 쓴다.
- `Deps` 레코드는 `ProactiveCheckService.deps()` 가 채운다.

## 의도 메모

- 발견이 없는 블록(예약 실행의 정상 `NOTHING_NEW` 조기 반환 포함)은 후보를 검사하지도 남기지도 않는다.
- 다음 입력의 후보 목록은 모델이 쓴 글이라 `ExternalData.wrap` 으로 감싼다. 받아들인 후보가 없으면 「최근에 받아들인 문제 후보가 없다.」 한 줄이다.
- 지시의 결과 블록 설명은 `version: 정수 3. version 1과 2도 읽는다` 로 바꾸고 `problemCandidates` 칸 설명을 더한다. 칸 이름과 상한은 문서 「문제 후보」 표와 같다.

## 작업 항목

### 1. `ProactiveCheckRun`

- `Deps` 에 `ProactiveCheckProblemRepository problems` 와 `FollowUpService followUps` 를 더한다.
- `answer` 의 검사 경로에서 `ProblemJudgement.judge(block.problemCandidates(), judged, acceptedProblemKeysSince(now - digestWindow), title -> deps.followUps().hasOpenWithTitle(owner.id(), title))` 를 부르고 `ProactiveCheckProblem` 으로 바꿔 `pendingProblems` 에 든다.
- `saveFindings` 에서 `pendingProblems` 가 비지 않았으면 `deps.problems().saveAll` 한다.
- `buildInput` 끝에 `"\n\n최근에 받아들인 문제 후보\n"` 와 `recentProblems(now)` 를 붙인다. 줄 모양은 `- {problemKey} · {problem}`.
- `COMMON_RULES` 의 결과 블록 설명을 의도 메모대로 고치고, 규칙 줄 하나를 더한다: 「문제 후보는 사용자의 목표나 맥락과 이어지고 이번 발견이 근거인 것만 낸다. 새 자료가 나왔다는 사실만으로 후보를 만들지 않는다.」

### 2. `ProactiveCheckService`

`backend/src/main/java/com/bifos/assistant/proactive/application/ProactiveCheckService.java` 의 필드와 `deps()` 에 두 의존을 더한다.

### 3. 이 phase 를 검증하는 테스트

- `backend/src/test/java/com/bifos/assistant/proactive/ProactiveCheckTurnTest.java` 에 더한다:
  버전 3 블록의 받아들인 후보와 버린 후보가 저장되고 답 메시지에는 후보 글이 없다;
  두 번째 살펴보기 입력에 앞 후보의 문제 키가 감싼 단락 안에 있고, 같은 키를 다시 내면 `DUPLICATE` 다;
  처음 살펴보기 입력은 「최근에 받아들인 문제 후보가 없다.」 를 싣는다;
  지시에 `problemCandidates` 가 있다.
  `tearDown` 에서 이 사용자 대화의 후보 줄도 지운다.
- `test/e2e/scenarios/proactive-check.ts` 에 step 하나를 더한다: 버전 3 블록에 후보 하나를 넣은 살펴보기 뒤, 대화 답에 후보의 문제 글이 없고 다음 살펴보기 입력(`context.hermes.proactiveInputs()`)에 그 문제 키가 있다. `test/e2e/fake-hermes.ts` 의 `proactiveOutput` 이 버전 3을 그대로 넘기는지 확인하고 필요하면 고친다.

## 검증

```bash
# cwd: 저장소 root
(cd backend && ./gradlew test --tests '*ProactiveCheckTurnTest')
(cd backend && ./gradlew test)
node test/e2e/run.ts
scripts/quality.sh check
```

- 모두 종료 코드 0. `node test/e2e/run.ts` 는 `./gradlew test` 뒤에 돌린다

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/proactive/application/ProactiveCheckRun.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/proactive/application/ProactiveCheckService.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/proactive/ProactiveCheckTurnTest.java` | 수정 |
| `test/e2e/scenarios/proactive-check.ts` | 수정 |
| `test/e2e/fake-hermes.ts` | 수정 |
