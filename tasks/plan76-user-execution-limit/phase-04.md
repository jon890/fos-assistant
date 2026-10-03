# Phase 04. 화면 안내와 합성 부하 측정

**Execution profile**: standard

## 목표

화면이 `USER_BUSY` 를 사람이 읽을 수 있는 안내로 보이고, 가짜 Hermes 로 합성 사용자와 합성 작업 부하를 돌려 동시 실행 수, 거절 수, 실행 시간을 측정해 `docs/backend/execution-limit.md` 의 「측정」 절에 적는다.

**범위 외**: 백엔드 판정과 거절 경로(phase 01 부터 03). 운영 Hermes 의 메모리 최댓값은 측정하지 않는다. 측정하지 못했다고 문서에 적는다.

## 컨텍스트

**근거 문서**: `docs/backend/execution-limit.md` 의 「한도에 닿을 때」, 「설정」, 「측정」 절, `docs/flow.md` 의 「실행이 실패할 때」 표의 `USER_BUSY` 줄.

phase 01 부터 03 이 끝난 상태: 보내기와 다시 생성은 질문을 저장하기 전에 409 `USER_BUSY`(본문 `{"code":"USER_BUSY","message":"..."}`, 스트림이면 `started` 전 `error` 사건)로 거절된다. 대기 메시지 turn 은 대기 줄을 멈추고 대화 단위 SSE 로 `USER_BUSY` 오류를 낸다. 흐름 turn 은 루트 줄의 `error_code` 가 `USER_BUSY` 인 채 실패로 끝난다. 설정은 `assistant.user-execution.max-running`(기본 4)이고 환경 변수 `ASSISTANT_USER_EXECUTION_MAX_RUNNING` 으로 바꿀 수 있다.

웹:
- `web/src/components/error-message.ts` 의 `MESSAGES` 가 코드를 한국어 문구로 바꾼다. 검사는 `test/unit/error-message.test.ts` 다.
- `web/src/components/chat/conversation-session.tsx` 의 `rejectBeforeStart` 는 `CONVERSATION_BUSY` 만 대기 메시지로 넣고, 나머지 코드는 글을 입력창에 되돌린 뒤 `reportRejected(code, message)` 로 알린다. 그래서 `USER_BUSY` 는 문구만 더하면 「글을 되돌리고 안내」 가 된다. 코드를 바꾸지 않는다.
- `web/src/components/usage/execution-list.tsx` 의 `executionStatusLabel` 이 `HERMES_BUSY` 를 「요청이 많아 거절됨」 으로 보인다. 그 밖의 실패 코드는 일반 사용자에게 「실패」 다. 검사는 `test/unit/execution-status.test.ts` 다.

e2e:
- `test/e2e/run.ts` 가 Control Plane 을 `./gradlew smokeRun` 으로 띄우고 `SCENARIOS` 배열을 차례로 돈다. 환경 변수는 `...process.env` 를 이어받는다.
- `test/e2e/fake-hermes.ts` 의 `startFakeHermes` 가 대역이다. 실행 요청(`POST /v1/runs`)을 받으면 `runs` 맵에 `status` 를 `completed` 로 넣는다. `holdNextRun()` 은 다음 실행 하나만 `running` 으로 붙잡는다. 추천 질문 실행은 `input.startsWith(STARTER_MARK)` 로 가려 붙잡지 않는다. `busy()` 는 제출을 429 로 거절한다. 형식은 `FakeHermes` 타입에 메서드를 더하고 `startFakeHermes` 의 반환 객체에 구현을 더하는 방식이다.
- 사용자 토큰은 `context.tokens.dad`, `context.tokens.kid`, `context.tokens.aunt` 다. 각자 다른 profile 로 돈다.
- 본보기 시나리오는 `test/e2e/scenarios/busy.ts` 다. `call`, `expect`, `expectStatus`, `step` 을 `test/e2e/harness.ts` 에서 가져온다.

## 의도 메모

- 측정에 쓰는 글은 합성 글이다(예: `부하 측정 3-2`). 실제 대화 본문이나 credential 을 쓰거나 출력하지 않는다.
- 측정값은 가짜 Hermes 의 지연을 정한 값이라 실제 모델 시간이 아니다. 문서에는 무엇을 측정했고 무엇을 측정하지 않았는지 함께 적는다.
- 측정은 e2e 시나리오 하나로 하고, 다른 한도는 같은 시나리오를 환경 변수로 바꿔 돌린다. 측정만을 위한 실행기를 따로 만들지 않는다.

## 작업 항목

### 1. `web/src/components/error-message.ts`

`MESSAGES` 에 `CONVERSATION_BUSY` 다음 줄로 더한다.

```ts
  USER_BUSY:
    "다른 대화에서 진행 중인 작업이 많아요. 진행 중인 작업이 끝난 뒤 다시 보내 주세요.",
```

### 2. `web/src/components/usage/execution-list.tsx`

`executionStatusLabel` 의 `HERMES_BUSY` 줄 다음에 `if (execution.errorCode === "USER_BUSY") return "동시 실행 한도에 닿아 거절됨";` 과 한 줄 주석(사용자 한 명의 동시 실행 한도라 Hermes 가 붐빈 것과 원인이 다르다)을 더한다.

### 3. `test/unit/error-message.test.ts`, `test/unit/execution-status.test.ts`

- `describeError("USER_BUSY", "fallback")` 이 위 문구다.
- `executionStatusLabel({ status: "FAILED", errorCode: "USER_BUSY", ... }, false)` 가 「동시 실행 한도에 닿아 거절됨」 이다. 파일의 기존 단언 모양을 따른다.

### 4. `test/e2e/fake-hermes.ts`

`FakeHermes` 에 둘을 더한다.

- `slowRuns(ms: number | undefined): void` — 값이 있으면 추천 질문이 아닌 새 실행을 제출 시각에서 `ms` 가 지날 때까지 `running` 으로 답하고 그 뒤 `completed` 로 답한다. `undefined` 면 끈다. 기존 `holdNextRun` 과 섞여도 붙잡은 실행이 우선이다.
- `runConcurrency(): { maxTotal: number; maxByProfile: Record<string, number> }` 와 `resetRunConcurrency(): void` — 제출에서 완료(또는 중지)까지를 도는 중으로 보고, 나란히 돈 최댓값을 전체와 profile 별로 적는다. `slowRuns` 가 켜진 동안의 실행만 세면 된다.

### 5. `test/e2e/scenarios/user-execution-limit.ts` 신규, `test/e2e/run.ts`

`userExecutionLimitScenario: Scenario` 를 만들고 `run.ts` 의 `SCENARIOS` 에서 `busyScenario` 바로 뒤에 넣는다.

1. 한도는 `Number(process.env.ASSISTANT_USER_EXECUTION_MAX_RUNNING ?? "4")` 로 읽는다.
2. dad 의 `RUNNING` 실행이 없을 때까지 기다린다(`GET /usage/executions?limit=50` 을 짧게 반복. 앞 시나리오가 남긴 추천 질문 실행 때문이다).
3. `slowRuns(1500)` 과 `resetRunConcurrency()` 뒤, dad 가 새 대화로 한도보다 2 개 많은 `POST /chat/messages` 를 한꺼번에 보낸다(대화 번호 없이 보내 매번 새 대화). 같은 순간 kid 도 하나를 보낸다. 각 요청의 시작과 끝 시각을 기록한다.
4. 단언:
   - dad 의 200 수가 한도와 같고 나머지는 409 이며 본문 `code` 가 `USER_BUSY` 다.
   - kid 의 요청은 200 이다.
   - `runConcurrency().maxByProfile` 의 dad profile 값이 한도 이하다.
   - 모두 끝난 뒤 dad 가 하나를 더 보내면 200 이다(자리가 새지 않았다).
5. 측정값을 `console.log` 로 한 줄씩 출력한다: 한도, dad 의 보낸 수, 받아들여진 수, 거절된 수, dad profile 의 동시 최댓값, 전체 동시 최댓값, 받아들여진 요청의 응답 시간 최소, 중앙값, 최대(ms), 거절 응답 시간 최대(ms). 글 본문은 출력하지 않는다.
6. `finally` 에서 `slowRuns(undefined)` 로 되돌린다.

### 6. 측정과 `docs/backend/execution-limit.md`

- 아래 「검증」 의 e2e 를 기본 한도로 한 번 돌리고, `ASSISTANT_USER_EXECUTION_MAX_RUNNING=2` 와 `=6` 으로 한 번씩 더 돌려 출력된 값을 모은다. 실패하면 값을 모으기 전에 원인을 고친다.
- `docs/backend/execution-limit.md` 의 「측정」 절에서 「측정 결과는 구현을 마친 뒤 이 절에 적는다.」 줄을 지우고, 측정 조건(합성 사용자 둘, 한 사용자가 한도보다 2 개 많이 한꺼번에 보냄, 가짜 Hermes 지연 1.5초, 측정 날짜)과 한도별 결과 표(보낸 수, 받아들여진 수, 거절 수, 동시 최댓값, 응답 시간)를 적는다. 기본값 4 가 측정과 맞지 않으면 그 까닭과 바꾼 값을 적고 `application.yml` 과 문서의 기본값을 함께 바꾼다.
- 「운영 Hermes 의 메모리 최댓값은 이 측정이 다루지 않는다.」 문장은 남긴다.

## 검증

```bash
# cwd: 저장소 root
node --test test/unit/error-message.test.ts test/unit/execution-status.test.ts
node --test 'test/unit/**/*.test.ts'
node test/e2e/run.ts
ASSISTANT_USER_EXECUTION_MAX_RUNNING=2 node test/e2e/run.ts
ASSISTANT_USER_EXECUTION_MAX_RUNNING=6 node test/e2e/run.ts
scripts/check-public-safe.sh
scripts/quality.sh check
```

```bash
# cwd: web/
pnpm typecheck
```

모두 종료 코드 0. 화면 코드는 문구 표와 사용량 목록의 문구만 바꾸므로 브라우저 검사는 PR 의 CI(`browser-mobile`, `browser-desktop`)로 확인한다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `web/src/components/error-message.ts` | 수정 |
| `web/src/components/usage/execution-list.tsx` | 수정 |
| `test/unit/error-message.test.ts` | 수정 |
| `test/unit/execution-status.test.ts` | 수정 |
| `test/e2e/fake-hermes.ts` | 수정 |
| `test/e2e/scenarios/user-execution-limit.ts` | 신규 |
| `test/e2e/run.ts` | 수정 |
| `docs/backend/execution-limit.md` | 수정 |
| `backend/src/main/resources/application.yml` | 수정 |
