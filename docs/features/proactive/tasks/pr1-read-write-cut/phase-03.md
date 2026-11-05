# Phase 03. 발견 반응 단추와 API, 쓰지 않는 칸과 조회를 지운다

**Execution profile**: standard

## 목표

점검 대화의 발견 반응 단추와 그 API 를 지우고, `source_check_id` 쓰기와 호출 없는 조회를 지운다.

**범위 외**: DDL. 칸과 테이블은 PR2 가 지운다.

## 컨텍스트

**요구사항**: REQ3

**근거 문서**: `docs/features/proactive/spec.md`, `docs/features/proactive/design.md` 의 「지우는 것」

- backend 경로는 `proactive/check/presentation/CheckFindingController.java`, 읽고 쓰는 곳은 `CheckFindingReactions` 다. `CheckFeedback.ended` 가 보고를 보일 때 발견마다 `SURFACED` 를 남긴다
- 화면은 `web/src/components/chat/message-list.tsx` 가 `check-finding-reactions.tsx` 를 그리고 `use-check-findings.ts` 가 읽는다
- `source_check_id` 는 `FeedbackEntry.sourceCheck`, `FeedbackEvent.sourceCheckId`, `FeedbackEventWriter` 가 쓰고 `CheckFeedback`, `CheckFindingReactions`, `ConnectorPolicyService` 가 부른다
- `FeedbackSubjectType.CHECK_FINDING` 이름은 남긴다(스펙 DECISION9)

## 작업 항목

### 1. backend

- `CheckFindingController`, `CheckFindingReactions`, `FindingReaction`, `CheckFindingView`, `ProactiveCheckDtos` 의 발견 응답을 지운다
- `CheckFeedback` 의 발견별 `SURFACED` 와 자동 실행 분기, `ProactiveCheckAnswerGuard` 의 자동 실행 분기를 지운다
- `sourceCheck` 와 `sourceCheckId` 를 지운다
- `ProactiveCheckRepository.findEvaluable` 과 `existsByRootExecutionIdAndTrigger`, 발견·문제 후보 저장소에서 이관이 쓰지 않는 조회를 지운다

### 2. web

- `web/src/app/api/check-findings/`, `web/src/app/api/chat/conversations/[conversationId]/check-findings/`, `web/src/lib/check-finding-api.ts`, `web/src/components/chat/check-finding-reactions.tsx`, `web/src/components/chat/use-check-findings.ts` 를 지우고 `message-list.tsx`, `conversation-session-view.tsx` 의 발견 칸을 지운다

### 3. 테스트

- `CheckFindingReactionsTest` 를 지우고, 옛 두 경로가 404 인 MockMvc 사례를 둔다
- `test/browser/proactive-check.spec.ts` 의 반응 사례를 「점검 대화 답 아래에 반응 단추가 없다」 로 바꾸고, `test/browser/json-body.spec.ts` 의 옛 경로를 지운다
- `test/e2e/scenarios/proactive-check.ts` 의 발견 목록과 반응 단계를 지운다
- `ProactiveCheckRecoveredAnswerTest` 의 자동 실행 사례를 지운다

## 검증

```bash
cd backend && ./gradlew test --tests 'com.bifos.assistant.proactive.*' --tests 'com.bifos.assistant.feedback.*' --tests 'com.bifos.assistant.attention.*'
cd web && pnpm lint && pnpm typecheck
cd web && pnpm test:browser proactive-check
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/proactive/check/presentation/CheckFindingController.java` | 삭제 |
| `backend/src/main/java/com/bifos/assistant/proactive/check/application/CheckFindingReactions.java` | 삭제 |
| `backend/src/main/java/com/bifos/assistant/proactive/check/application/CheckFeedback.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/feedback/application/dto/FeedbackEntry.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/feedback/domain/FeedbackEvent.java` | 수정 |
| `web/src/components/chat/message-list.tsx` | 수정 |
| `test/browser/proactive-check.spec.ts` | 수정 |
| `test/e2e/scenarios/proactive-check.ts` | 수정 |
