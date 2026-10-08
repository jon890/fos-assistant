# Phase 02. 먼저 다룰 문제 항목과 루프 설정 화면 (web)

**Execution profile**: standard

## 목표

지금 화면 「내 차례」 에 `PROBLEM_SURFACED` 항목을 그리고 「점검 대화에서 보기」, 「받아들임」, 「관심 없음」 을 둔다. 「받아들임」 은 기록만 한다는 안내를 화면에 보인다.
에이전트 상세의 매일 깨우기 절 아래에 매일 루프 설정 「깨운 뒤 먼저 다룰 문제 고르기」(켜기, 하루 쉬기, 일주일 쉬기, 쉬기 끝내기)를 둔다.

**범위 외**: backend 운영 코드(phase 01 에서 끝났다). 시험 지원용 backend 시험 코드만 더한다.

## 컨텍스트

**근거 문서**: `docs/frontend/now.md` 의 「항목 한 줄」 표의 「문제」 줄, 「이유 문구」 표의 `PROBLEM_SURFACED` 줄, 「동작」 표의 「먼저 다룰 문제」 줄과 그 아래 문단, `docs/frontend/structure.md` 의 「먼저 살펴보기 절」 의 매일 루프 설정 표, `docs/backend/proactive-loop.md` 의 「사용자 설정」, 「사용자에게 보이는 것」, `docs/backend/attention.md` 의 추가 칸 표의 `problem`, `docs/flow.md` 의 오류 표(`AUTONOMY_DECISION_NOT_FOUND`, `PROACTIVE_LOOP_UNAVAILABLE`)

phase 01 이 만든 backend: `PUT /api/v1/autonomy-decisions/{id}/reaction`(본문 `{ reaction: "ACCEPTED" | "DISMISSED" }`, 204), 지금 화면 응답 항목의 `problem: { decisionId, level, action } | null`, 오류 코드 `AUTONOMY_DECISION_NOT_FOUND`. 루프 설정 `GET/PUT /api/v1/agents/{code}/proactive-check/loop`(`{ available, enabled, snoozedUntil }`)은 앞 PR 에서 있었다.

기존 web 코드:

- 지금 화면: `web/src/lib/attention.ts`(`AttentionItem`, `REASONS`, `itemHref`, `ItemAction`, `itemActions`. trigger 가 `string` 이라 분기를 빠뜨려도 typecheck 가 잡지 않는다), `web/src/components/now/now-item.tsx`(`FOLLOW_UP_ACTIONS`, `ActionButtons`, `acted()`. 기존 `"accept"` kind 는 할 일 API 로 가므로 새 kind 를 쓴다), `web/src/lib/attention-api.ts` 의 `ATTENTION_CHANGED_EVENT`
- 반응 본보기: `web/src/app/api/check-findings/[id]/reaction/route.ts`, `web/src/lib/check-finding-api.ts` 의 `reactToFinding`, `web/src/components/chat/check-finding-reactions.tsx`
- 매일 깨우기 절: `web/src/components/agent/agent-proactive-schedule-section.tsx`, API 는 `web/src/lib/proactive-check.ts` 의 `fetchProactiveCheckSchedule`, `saveProactiveCheckSchedule` 와 `web/src/app/api/agents/[code]/proactive-check/schedule/route.ts`
- 오류 문구: `web/src/components/error-message.ts` 의 `MESSAGES`
- 시험: `test/unit/attention.test.ts`(`item()` 도우미, `docs/frontend/now.md` 「이유 문구」 표와 `REASONS` 를 한 줄씩 견준다), `test/unit/error-message.test.ts`, `test/browser/now.spec.ts`(할 일 제안을 test-support 로 심는 `proposeFollowUp`), `test/browser/proactive-check.spec.ts`(매일 깨우기 저장)
- 시험 지원 본보기: `backend/src/test/java/com/bifos/assistant/testsupport/FollowUpTestSupportController.java`(`@ConditionalOnProperty(name = "assistant.test-support.enabled")`)
- 브라우저에서 Control Plane 을 직접 부르지 않는다(`web/AGENTS.md`). 화면은 `web/src/app/api/` 아래 경로를 부른다

## 의도 메모

- 문제 글(`title`)과 행동 글(`problem.action`)은 모델이 쓴 글이라 평문으로만 그린다(ADR-009)
- 「받아들임」 과 「관심 없음」 이 성공하면 `acted()` 로 사건을 남기고 `ATTENTION_CHANGED_EVENT` 를 보낸 뒤 화면을 다시 읽는다
- 브라우저 환경에는 판단 profile 이 없어 실제 루프로는 `SURFACE` 판정이 생기지 않는다. 시험 지원 경로로 판정을 심는다
- 매일 루프 설정은 설치가 꺼져 있으면 스위치를 끄되, 이미 켠 사용자는 끌 수 있게 둔다
- `web/src/lib/attention.ts` 는 파일 길이 상한(400줄)에 가깝다. 넘으면 새 분기를 `web/src/lib/attention-problem.ts` 같은 새 파일로 옮긴다

## 작업 항목

### 1. 반응과 루프 설정 web API

- `web/src/app/api/autonomy-decisions/[id]/reaction/route.ts`: `PUT`. 숫자 아닌 번호는 400 `VALIDATION_FAILED`. Control Plane `PUT /api/v1/autonomy-decisions/{id}/reaction` 으로 `reaction` 만 넘기고 성공하면 204
- `web/src/lib/decision-reaction-api.ts`: `DecisionReaction = "ACCEPTED" | "DISMISSED"`, `reactToDecision(id: number, reaction)`. `reactToFinding` 과 같은 모양
- `web/src/app/api/agents/[code]/proactive-check/loop/route.ts`: `GET`, `PUT`. schedule route 와 같은 모양
- `web/src/lib/proactive-check.ts`: `ProactiveLoopSetting = { available: boolean; enabled: boolean; snoozedUntil: string | null }`, `fetchProactiveLoopSetting(code)`, `saveProactiveLoopSetting(code, { enabled, snoozedUntil })`

### 2. 지금 화면 항목

- `web/src/lib/attention.ts`: `AttentionItem` 에 `problem: { decisionId: number; level: string; action: string | null } | null`. `REASONS` 에 `PROBLEM_SURFACED` 「없음」 줄 「에이전트가 먼저 다룰 문제로 골랐어요」. `itemHref` 에 `PROBLEM_SURFACED` 이면 `/chat/{conversationId}`. `ItemAction.kind` 에 `"react-accept"`, `"react-dismiss"` 를 더하고 `itemActions` 에 링크 「점검 대화에서 보기」, 「받아들임」, 「관심 없음」
- `web/src/components/now/now-item.tsx`: `problem` 이 있으면 이유 줄 아래에 행동 글을 평문 한 줄로, `level === "ASK_APPROVAL"` 이면 「직접 처리할 일이에요. 승인 요청이 아니에요.」 를 그린다. 단추 아래에 「받아들임은 기록만 해요. 할 일이나 승인을 만들지 않아요.」 를 작게 보인다. 새 kind 는 `reactToDecision` 으로 보내고, 실패하면 항목 아래 `Notice` 로 「반응을 남기지 못했어요. 잠시 뒤 다시 눌러 주세요.」

### 3. 매일 루프 설정

- `web/src/components/agent/agent-proactive-loop-setting.tsx`(새 클라이언트 부품): `docs/frontend/structure.md` 의 매일 루프 설정 표대로 그린다. 「하루 쉬기」 는 지금부터 24시간 뒤, 「일주일 쉬기」 는 7일 뒤를 `snoozedUntil` 로 보내고, 「쉬기 끝내기」 는 `snoozedUntil: null`. 거절은 `describeError(code, ...)` 를 `Notice` 로 보인다
- `web/src/components/agent/agent-proactive-schedule-section.tsx`: 매일 깨우기 설정 아래에 새 부품을 둔다
- `web/src/components/error-message.ts`: `PROACTIVE_LOOP_UNAVAILABLE` 「이 설치에서는 아직 쓸 수 없어요.」, `AUTONOMY_DECISION_NOT_FOUND` 「이미 처리했거나 찾을 수 없는 항목이에요. 화면을 다시 열어 주세요.」

### 4. 시험 지원

- `backend/src/test/java/com/bifos/assistant/testsupport/ProactiveLoopTestSupportController.java`: `@ConditionalOnProperty(name = "assistant.test-support.enabled", havingValue = "true")`, `POST /api/v1/test-support/proactive-loop/surfaced`, 본문 `{ agentCode, problem, action, level }`. 부르는 사용자의 점검 대화와 끝난 매일 깨우기 살펴보기, 받아들인 문제 후보, 평가, `DECIDED` 시도, 그 평가의 판정 줄 하나를 저장하고 `{ decisionId, conversationId }` 를 준다. 판정 줄의 `SURFACED` 사건도 phase 01 의 `SurfacedProblems.surfaced` 로 남긴다
- 브라우저 검사의 backend 가 `assistant.test-support.enabled` 를 켜는 자리를 찾아 같은 자리에 `assistant.proactive-loop.enabled=true` 를 더한다(루프 설정 켜기 시험용). 그 파일을 변경 파일 표에 더하지 못했으면 회신에 경로를 적는다

### 5. 이 phase 를 검증하는 시험

- `test/unit/attention.test.ts`: `item()` 도우미에 `problem: null` 기본값. `PROBLEM_SURFACED` 의 이유 문구, 링크, 세 동작. 문서 표 대조 시험이 새 줄로 통과한다
- `test/unit/error-message.test.ts`: 새 두 오류 문구
- `test/browser/now.spec.ts`: 시험 지원으로 `SURFACE` 와 `ASK_APPROVAL` 판정을 하나씩 심는다. 「내 차례」 에 두 항목이 문제 글과 행동 글로 보이고, `ASK_APPROVAL` 항목에 「직접 처리할 일이에요. 승인 요청이 아니에요.」 가, 둘 다에 「받아들임은 기록만 해요.」 안내가 있다. 카드와 사이드바 건수는 늘지 않는다. 「받아들임」 을 누르면 그 항목이 사라지고 새로 고쳐도 다시 나오지 않는다. 「점검 대화에서 보기」 가 `/chat/{conversationId}` 로 간다
- `test/browser/proactive-check.spec.ts`: 매일 루프 설정을 켜고 「하루 쉬기」 를 누르면 쉬는 시각이 보이고, 새로 고쳐도 남는다. 「쉬기 끝내기」 와 끄기도 저장된다

## 검증

```bash
(cd web && pnpm typecheck && pnpm lint && pnpm format:check)
node --test 'test/unit/**/*.test.ts'
node scripts/check-file-length.mjs
(cd backend && ./gradlew compileTestJava checkstyleTest spotlessCheck)
pnpm --dir web test:browser ../test/browser/now.spec.ts ../test/browser/proactive-check.spec.ts
```

unit 시험과 두 브라우저 spec 이 통과하고 파일 길이 검사가 통과한다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `web/src/app/api/autonomy-decisions/[id]/reaction/route.ts` | 신규 |
| `web/src/lib/decision-reaction-api.ts` | 신규 |
| `web/src/app/api/agents/[code]/proactive-check/loop/route.ts` | 신규 |
| `web/src/lib/proactive-check.ts` | 수정 |
| `web/src/lib/attention.ts` | 수정 |
| `web/src/components/now/now-item.tsx` | 수정 |
| `web/src/components/agent/agent-proactive-loop-setting.tsx` | 신규 |
| `web/src/components/agent/agent-proactive-schedule-section.tsx` | 수정 |
| `web/src/components/error-message.ts` | 수정 |
| `backend/src/test/java/com/bifos/assistant/testsupport/ProactiveLoopTestSupportController.java` | 신규 |
| `test/unit/attention.test.ts` | 수정 |
| `test/unit/error-message.test.ts` | 수정 |
| `test/browser/now.spec.ts` | 수정 |
| `test/browser/proactive-check.spec.ts` | 수정 |
