# Phase 01. 실행 사건의 원문 열람 근거를 답에 붙인다

**Execution profile**: deep

## 목표

모델의 원문 열람 주장과 저장된 사건을 구분하도록 비서 답 아래 서버가 만든 요약을 표시한다.

**범위 외**: Hermes core, plugin, DB 스키마, 외부 서비스나 운영 변경.

## 컨텍스트

**근거 문서**: `docs/backend/conversation.md`의 「답에서 확인할 수 있는 원문 열람」, `docs/frontend/chat.md`의 「이번에 연 원문」.
`ChatController.messages`는 `ChatMessage.executionId`로 요약을 붙이고, `ChatConversationQueries.activitySummaries`는 루트와 자식을 한 번에 읽는다.
`HermesRunEventStream`은 완료 preview를 가려 `ExecutionEvent.detail`에 저장한다. 최대 500자이며 완결된 JSON만 파싱한다.
`MessageBubble`은 답을 그리고, `useConversationHistory.refreshMessages`는 서버 응답을 그대로 Turn 목록으로 읽는다. 완료 뒤 기존 이력 갱신을 재사용한다.

## 의도 메모

- 입력 preview의 URL과 모델 본문 링크는 성공 근거가 아니므로 쓰지 않는다.
- 성공 도구 호출 수와 확인된 URL 수는 다르다. 불완전 관측에서 0건을 미열람으로 단정하지 않는다.
- upstream v2026.9.24의 `web_extract` 결과 계약을 확인하고 성공 항목의 내용과 오류를 검사한다.
- URL은 query/fragment/인증 정보를 내보내지 않는다. 가림 표시나 절단, 내부 주소, HTTP(S) 외 scheme은 제외한다.

## 작업 항목

### 1. backend 원문 열람 요약

`SourceReadSummary` record를 `chat.application`에 만들고 `completedCount`, `urls`, `unresolvedCount`, `observationComplete`를 둔다.
`SourceReadSummaries` 컴포넌트는 assistant 메시지의 루트와 자식 실행을 일괄 조회한다. 정확한 `web_extract`의 성공 완료만 세며 결과를 파싱한다.
동일 URL은 순서를 유지해 중복 제거하고 오류·본문 없는 항목을 제외한다. 가림/손상/절단된 JSON은 주소를 추측하지 않는다.
`ChatController`에 이 컴포넌트를 주입해 기존 messages 응답의 `MessageView.sourceReads`에 붙인다. USER/SYSTEM에는 null이다.
기존 `ChatService`와 `ChatConversationQueries`는 바꾸지 않아 긴 파일과 기존 요약 책임을 늘리지 않는다.

### 2. web 답 아래 목록

`Turn.sourceReads` optional 타입을 추가하고 새 `SourceReadList` 컴포넌트를 답 본문 뒤에 붙인다.
저장된 ASSISTANT 답만 표시하고 「이번에 연 원문」 제목과 완료 횟수·주소 수, 빈 상태 및 불완전 안내를 표시한다.
링크는 안전한 HTTP(S)만 새 탭으로 열고 rel=noreferrer noopener를 준다. 새 의존은 더하지 않는다.

### 3. 테스트

`SourceReadSummariesTest`는 성공·실패·미상·시작만·검색·동명 MCP, 혼합 결과, 중복, 잘린 JSON, 민감 URL, 불완전 관측, 자식 실행, 빈 history, USER/SYSTEM 제외와 조회 일괄 수행을 확인한다.
`source-reads.spec.ts`는 messages 응답에 합성 요약을 넣어 답 아래 목록, 빈 상태와 불완전 안내, 새로고침 보존, USER/SYSTEM 및 스트리밍 임시 답 제외를 확인한다.

## 검증

```bash
cd backend && ./gradlew test --tests '*SourceReadSummariesTest'
pnpm --dir web typecheck
pnpm --dir web test:browser source-reads
```
무거운 검사는 코디네이터 지시의 heavy-lock으로 감싼다. backend 전체와 전체 브라우저는 CI에서 확인한다.

## 변경 파일

| 파일 | 변경 |
| --- | --- |
| `backend/src/main/java/com/bifos/assistant/chat/application/SourceReadSummary.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/application/SourceReadSummaries.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/presentation/ChatController.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/presentation/ChatDtos.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/application/SourceReadSummariesTest.java` | 신규 |
| `web/src/components/chat/source-read-list.tsx` | 신규 |
| `web/src/components/chat/message-bubble.tsx` | 수정 |
| `test/browser/source-reads.spec.ts` | 신규 |
| `docs/backend/conversation.md` | 수정 |
| `docs/frontend/chat.md` | 수정 |
