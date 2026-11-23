# Phase 02. 화면에서 에이전트 고르기와 「대화하기」 단추, `@` 를 없애고 문서를 맞춘다

**Execution profile**: standard

## 목표

새 대화 화면은 기본 비서만 보이고 `?agent=` 를 무시한다. 에이전트 상세, 전문 에이전트 안내, 연결 화면의 「대화하기」 단추와 입력창의 `@` 고르기가 없다. 기능 문서와 ADR 이 바뀐 동작을 적는다.

**범위 외**: 읽기 전용 대화와 예약 작업 화면(다음 PR).

## 컨텍스트

**요구사항**: REQ4, REQ6

**근거 문서**: `docs/features/assistant-hub/spec.md`(REQ4, REQ6, DECISION3, DECISION4, DECISION15), `docs/features/assistant-hub/design.md` 의 「PR2 입구를 비서로 모으기」

- 새 대화와 에이전트 고르기: `web/src/app/new/page.tsx`, `web/src/components/chat-panel.tsx`, `web/src/components/chat/start-screen.tsx`, `use-conversation-session-state.ts`.
- `@`: `composer.tsx`, `use-composer-input.ts`, `agent-mention.tsx`, `use-delegate-target.ts`, `delegate-chip.tsx`, `use-conversation-send.ts`, `use-conversation-queue.ts`, `web/src/app/api/chat/stream/route.ts`, `conversation-session-view.tsx` 와 그 타입 파일.
- 대화하기 단추: `components/agent/agent-detail-body.tsx`, `agent-detail-loader.tsx`, `agent-connections-section.tsx`, `specialist-agent-offer.tsx`, `components/connector/connector-agent-chooser.tsx`, `connector-catalog.tsx`, `connector-oauth.tsx`.

## 작업 항목

### 1. 새 대화

- 목록의 `primary` 에이전트로 고정한다. `?agent=` 는 읽지 않는다. 에이전트 고르기(`StartAgentSwitcher`)는 이름만 보이는 표시로 바꾼다.
- 기본 비서가 꺼졌거나 없으면 입력창을 막고 「비서를 쓸 수 없어요. 관리자에게 알려 주세요」 를 보인다.

### 2. `@`

- 입력창의 `@` 고르기와 맡김 칩, `delegateTo` 보내기를 지운다. 쓰지 않게 된 파일(`agent-mention.tsx`, `use-delegate-target.ts`, `delegate-chip.tsx`)은 지운다. 서버 라우트가 `delegateTo` 를 넘기지 않는다.

### 3. 대화하기 단추

- 에이전트 상세, 전문 에이전트 안내, 연결 뒤 에이전트 고르기에서 「대화하기」 와 `/new?agent=` 링크를 지운다.

### 4. 문서

- `docs/features/assistant-hub.md`: 「입구를 비서로 모으기」 절과 covers 를 더하고 요구 줄을 고친다.
- `docs/features/chat.md`, `docs/features/agent-team.md`, `docs/features/agent-skill.md`, `docs/features/connector.md`, `web/docs/code-architecture.md`: 새 대화의 에이전트 고르기, `@` 맡기기, 「대화하기」 서술을 지금 동작으로 고친다.
- ADR-20261112 / mention-delegation 은 새로 맡기는 부분만 대체되어 옮기지 않고 그 머리와 목록에 적는다.
- `service-concepts`, `app-shell`, `agent-team` 스펙의 머리에 이 스펙이 바꾼 결정을 한 줄씩 적는다. 블로그 사진 평가가 지금 돌지 못하는 것을 `naver-blog-connector.md` 에 적는다.

### 5. 테스트

- 브라우저 테스트의 기본 사용자에게 「브라우저 비서」 를 기본 비서로 둔다(`fixtures.ts`). `helpers.ts` 의 이름표 고르기 도우미를 `expectStartAgent` 로 바꾸고, 기본 비서 정하기와 지난 대화 만들기 도우미를 둔다.
- `start-screen.spec.ts`: 기본 비서만 보이고 `?agent=` 로 열어도 비서이며, 기본 비서가 없으면 안내가 보인다. `@` 고르기 사례는 목록이 뜨지 않는 사례 하나로 줄인다.
- `composer-agent-switcher.spec.ts` 를 `composer-start-agent.spec.ts` 로 바꿔 고르는 목록이 없음을 본다.
- `agent-team-mention.spec.ts`: `@` 를 쳐도 목록이 없고, 지난 맡김은 맡긴 줄과 맡은 에이전트 이름표로 보인다.
- 이름표로 다른 에이전트를 고르거나 새 대화를 전문 에이전트로 열던 나머지 spec 은 기본 비서를 정하거나 지난 대화로 연다.

## 검증

```bash
cd web && pnpm lint && pnpm typecheck && pnpm test:node
cd web && pnpm test:browser test/browser/start-screen.spec.ts test/browser/composer-start-agent.spec.ts test/browser/agent-team-mention.spec.ts test/browser/agent-lifecycle.spec.ts test/browser/connector-onboarding.spec.ts test/browser/skill-command.spec.ts test/browser/now.spec.ts
scripts/dev/check-local.sh --fast
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `docs/adr/ADR-20261112-mention-delegation.md` | 수정 |
| `docs/adr/INDEX.md` | 수정 |
| `docs/features/agent-skill.md` | 수정 |
| `docs/features/agent-team.md` | 수정 |
| `docs/features/agent-team/design.md` | 수정 |
| `docs/features/agent-team/spec.md` | 수정 |
| `docs/features/app-shell.md` | 수정 |
| `docs/features/app-shell/spec.md` | 수정 |
| `docs/features/assistant-hub.md` | 수정 |
| `docs/features/assistant-hub/spec.md` | 수정 |
| `docs/features/chat.md` | 수정 |
| `docs/features/connector.md` | 수정 |
| `docs/features/naver-blog-connector.md` | 수정 |
| `docs/features/service-concepts/spec.md` | 수정 |
| `web/docs/code-architecture.md` | 수정 |
| `web/src/app/api/chat/stream/route.ts` | 수정 |
| `web/src/app/new/page.tsx` | 수정 |
| `web/src/components/agent/agent-detail-body.tsx` | 수정 |
| `web/src/components/agent/agent-detail-loader.tsx` | 수정 |
| `web/src/components/agent/specialist-agent-offer.tsx` | 수정 |
| `web/src/components/chat-panel.tsx` | 수정 |
| `web/src/components/chat/agent-mention.tsx` | 삭제 |
| `web/src/components/chat/agent-switcher.tsx` | 삭제 |
| `web/src/components/chat/composer-types.ts` | 수정 |
| `web/src/components/chat/composer.tsx` | 수정 |
| `web/src/components/chat/conversation-session-types.ts` | 수정 |
| `web/src/components/chat/conversation-session-view-types.ts` | 수정 |
| `web/src/components/chat/conversation-session-view.tsx` | 수정 |
| `web/src/components/chat/delegate-chip.tsx` | 삭제 |
| `web/src/components/chat/start-screen.tsx` | 수정 |
| `web/src/components/chat/use-composer-input.ts` | 수정 |
| `web/src/components/chat/use-composer-state.ts` | 수정 |
| `web/src/components/chat/use-conversation-queue.ts` | 수정 |
| `web/src/components/chat/use-conversation-send.ts` | 수정 |
| `web/src/components/chat/use-conversation-session-state.ts` | 수정 |
| `web/src/components/chat/use-delegate-target.ts` | 삭제 |
| `web/src/components/connector/connector-agent-chooser.tsx` | 수정 |
| `web/src/lib/agent.ts` | 수정 |
| `web/src/lib/mention.ts` | 수정 |
| `web/test/browser/access-revoked.spec.ts` | 수정 |
| `web/test/browser/activity-panel.spec.ts` | 수정 |
| `web/test/browser/activity-progress.spec.ts` | 수정 |
| `web/test/browser/activity-scroll.spec.ts` | 수정 |
| `web/test/browser/agent-browser.spec.ts` | 수정 |
| `web/test/browser/agent-introduction.spec.ts` | 수정 |
| `web/test/browser/agent-lifecycle.spec.ts` | 수정 |
| `web/test/browser/agent-team-mention.spec.ts` | 수정 |
| `web/test/browser/agent-team-room.spec.ts` | 수정 |
| `web/test/browser/artifact.spec.ts` | 수정 |
| `web/test/browser/chat-interim.spec.ts` | 수정 |
| `web/test/browser/chat.spec.ts` | 수정 |
| `web/test/browser/composer-agent-switcher.spec.ts` | 삭제 |
| `web/test/browser/composer-start-agent.spec.ts` | 신규 |
| `web/test/browser/connector-onboarding.spec.ts` | 수정 |
| `web/test/browser/design-tokens.spec.ts` | 수정 |
| `web/test/browser/execution-tree.spec.ts` | 수정 |
| `web/test/browser/fixture-isolation.spec.ts` | 수정 |
| `web/test/browser/fixtures.ts` | 수정 |
| `web/test/browser/flow-progress.spec.ts` | 수정 |
| `web/test/browser/helpers.ts` | 수정 |
| `web/test/browser/legacy-conversation-url.spec.ts` | 수정 |
| `web/test/browser/model-choice.spec.ts` | 수정 |
| `web/test/browser/motion.spec.ts` | 수정 |
| `web/test/browser/now.spec.ts` | 수정 |
| `web/test/browser/shell.spec.ts` | 수정 |
| `web/test/browser/skill-command.spec.ts` | 수정 |
| `web/test/browser/start-screen.spec.ts` | 수정 |
| `web/test/browser/starters.spec.ts` | 수정 |
| `web/test/browser/stop.spec.ts` | 수정 |
| `web/test/browser/tool-detail-redaction.spec.ts` | 수정 |
| `web/test/unit/mention.test.ts` | 수정 |
