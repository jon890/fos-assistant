# Phase 03. 화면의 / 목록에서 전문 에이전트의 스킬을 맡기고 문서를 고친다

**Execution profile**: standard

## 목표

비서 대화의 `/` 목록이 비서 스킬 뒤에 「에이전트 이름 · 스킬」 을 보이고, 고른 채 보내면 스킬 맡기기 API 로 보낸다. 작업 화면에 지시 꼴 안내 한 줄을 둔다. 기능 문서를 고친다.

**범위 외**: Control Plane(phase 01, 02).

## 컨텍스트

**요구사항**: REQ10

**근거 문서**: `docs/features/assistant-hub/spec.md`(REQ10, DECISION6, DECISION26, DECISION27), `docs/features/assistant-hub/design.md` 의 「PR3」

- `/` 목록: `web/src/components/chat/skill-command.ts`, `skill-command-menu.tsx`, `composer.tsx`, `use-composer-state.ts`, `conversation-session.tsx`(목록 읽기), `web/src/lib/chat-api.ts`.
- 보내기: `web/src/components/chat/use-conversation-send.ts`. 새 프록시 `web/src/app/api/chat/skill-delegations/`.
- 작업 화면: `web/src/components/task/task-form.tsx`.
- 문서: `docs/features/assistant-hub.md`, `connector.md`, `agent-skill.md`, `schedule.md`, `chat.md`.

## 작업 항목

### 1. `/` 목록

- 목록 항목은 지금처럼 이름 글이고, 비서 대화면 비서 스킬 뒤에 「에이전트 이름 · 스킬」 을 붙인다. 대화의 에이전트가 기본 비서일 때만 맡길 스킬을 읽는다(`web/src/components/chat/use-skill-delegation.ts`, `skill-command.ts` 의 `delegationLabels`).
- 고르면 `/<에이전트 이름> · <스킬> ` 이 들어간다(`withSkillCommand` 그대로).

### 2. 보내기

- 글이 목록의 「에이전트 · 스킬」 로 시작하면(`delegatedTargetOf`) 보통 보내기와 대기 줄 대신 새 프록시 `web/src/app/api/chat/skill-delegations/` 로 보낸다. 성공하면 입력창을 비우고 답이 돌고 있지 않으면 이력을 다시 읽는다. 실패하면 글을 남기고 오류를 보인다.

### 3. 작업 화면과 문서

- 작업 화면의 지시 칸 아래에 「`/스킬` 은 비서가, `/에이전트 이름 · 스킬` 은 그 에이전트가」 한 줄.
- 기능 파일, 연결, 스킬, 예약 작업 문서와 서비스 개념 스펙·설계의 바뀐 결정.

### 4. 테스트

- `web/test/unit/skill-command.test.ts`: 목록 합치기, 이름으로 찾기, 넣기, 맡기기 판별.
- `web/test/browser/skill-delegation.spec.ts`: `/` 목록에 「에이전트 · 스킬」, 골라 보내면 맡김 줄과 결과 답, 작업 화면 안내, 비서 상세에 붙이기 없음.
- `web/test/browser/skill-command.spec.ts`: 스킬 목록만 보는 검사는 맡길 스킬을 비운다.

## 검증

```bash
cd web && pnpm lint && pnpm typecheck && node --test test/unit/skill-command.test.ts && pnpm test:browser test/browser/skill-delegation.spec.ts test/browser/skill-command.spec.ts
node test/e2e/run.ts
scripts/dev/check-local.sh --fast
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `docs/features/agent-skill.md` | 수정 |
| `docs/features/assistant-hub.md` | 수정 |
| `docs/features/connector.md` | 수정 |
| `docs/features/schedule.md` | 수정 |
| `docs/features/service-concepts/design.md` | 수정 |
| `docs/features/service-concepts/spec.md` | 수정 |
| `web/src/app/api/chat/skill-delegations/route.ts` | 신규 |
| `web/src/app/api/chat/skill-delegations/targets/route.ts` | 신규 |
| `web/src/components/chat/conversation-session.tsx` | 수정 |
| `web/src/components/chat/skill-command.ts` | 수정 |
| `web/src/components/chat/use-skill-delegation.ts` | 신규 |
| `web/src/components/task/task-form.tsx` | 수정 |
| `web/src/lib/chat-api.ts` | 수정 |
| `web/test/browser/skill-command.spec.ts` | 수정 |
| `web/test/browser/skill-delegation.spec.ts` | 신규 |
| `web/test/unit/skill-command.test.ts` | 수정 |
