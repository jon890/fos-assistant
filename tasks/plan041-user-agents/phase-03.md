# Phase 03. 에이전트 목록의 「새 에이전트」 와 상세의 「공개와 삭제」

**Execution profile**: standard

## 목표

모든 사용자가 에이전트 목록에서 이름만 넣어 에이전트를 만들고, 상세에서 개인용과 가족용을 바꾸고 지운다.

**범위 외**: backend(phase 01, 02). 스킬 절(스킬 계획).

## 컨텍스트

**근거 문서**: `docs/code-architecture.md` 의 「에이전트 화면」, 「에이전트 만들기와 지우기」 절, `docs/flow.md` 의 「에이전트를 만들 때」 절

- phase 02 뒤 backend: `POST /api/v1/agents` `{name, visibility?}` → 201 `AgentView`, `PATCH /api/v1/agents/{code}/visibility` `{visibility}`, `DELETE /api/v1/agents/{code}` → 204. `AgentView` 에 `editable`, `ownedByMe` 가 있다. 상한이면 409 `AGENT_LIMIT_REACHED`
- `web/src/app/agents/page.tsx`: `ADMIN` 이면 `AgentAdminPanel`(관리자 목록), 아니면 카드 목록을 그린다. 빈 목록 문구는 「관리자가 에이전트를 연결하면 여기에 표시돼요」
- `web/src/app/api/agents/route.ts`: `GET` 만 있다. 서버 라우트는 `callControlPlane` 으로 넘긴다. `web/src/app/api/agents/[code]/` 아래에 `persona`, `starters`, `tools` 가 있다
- `web/src/components/agent/agent-detail-body.tsx`: 성격, 도구, 관리자 절을 세로로 그린다. `web/src/components/agent/agent-admin-section.tsx` 가 공개 범위와 사용 여부와 주소를 바꾼다(가족용 공개는 `VisibilityConfirm` 확인 창)
- 화면 부품과 색은 지금 main 의 이름을 쓴다(`web/src/components/ui/`). 공용 규칙은 `web/AGENTS.md`

## 의도 메모

- 「새 에이전트」 는 대화상자 하나다. 이름 입력과 개인용·가족용 선택뿐이다. 성격과 도구는 만든 뒤 상세에서 고친다
- 만드는 동안 단추를 막고 「만드는 중…」 을 보인다. 몇 초 걸린다
- 가족용으로 바꾸는 것은 지금 관리자 절과 같은 확인 창을 거친다. 확인 창 문구는 「가족 모두가 이 에이전트와 대화할 수 있어요. 각자의 대화와 기억은 서로 보이지 않아요」
- 지우기 확인 창 문구는 「에이전트를 지우면 새 대화를 시작할 수 없어요. 지난 대화는 읽을 수 있어요」
- 관리자 절에서는 공개 범위를 빼고 사용 여부와 주소만 남긴다. 공개 범위는 「공개와 삭제」 한 곳에서 바꾼다

## 작업 항목

### 1. 서버 라우트

- `web/src/app/api/agents/route.ts`: `POST` 를 더한다
- `web/src/app/api/agents/[code]/visibility/route.ts` 신규: `PATCH`
- `web/src/app/api/agents/[code]/route.ts` 신규: `DELETE`
- `web/src/lib/agent.ts`: `AgentView` 에 `editable`, `ownedByMe`

### 2. 화면

- `web/src/components/agent/create-agent-dialog.tsx` 신규: 이름, 공개 범위, 만들기. 성공하면 `/agents/{code}` 로 간다. 409 면 「에이전트는 5개까지 만들 수 있어요」 를 대화상자 안에 보인다
- `web/src/app/agents/page.tsx`: 제목 옆에 「새 에이전트」 단추. 빈 목록 문구를 「아직 에이전트가 없어요. 새 에이전트를 만들어 보세요」 로 바꾼다
- `web/src/components/agent/agent-admin-panel.tsx`: 관리자 목록에도 같은 단추를 둔다
- `web/src/components/agent/agent-access-section.tsx` 신규: 「공개와 삭제」 절. `editable` 일 때만 그린다
- `web/src/components/agent/agent-detail-body.tsx`: 맨 아래에 위 절을 둔다
- `web/src/components/agent/agent-admin-section.tsx`: 공개 범위 선택과 확인 창을 뺀다

### 3. 이 phase 를 검증하는 브라우저 검사

- `test/browser/agent-lifecycle.spec.ts` 신규
  - 일반 사용자가 「새 에이전트」 에 이름만 넣어 만들면 상세로 가고, 새 대화 화면에서 그 에이전트로 보낸 질문이 답으로 끝난다
  - 상한만큼 만든 뒤 다시 만들면 대화상자 안에 상한 문구가 보인다
  - 가족용으로 바꾸면 확인 창을 거치고 다른 사용자의 목록에 보인다. 만든 사람은 여전히 성격을 고칠 수 있다
  - 지우면 확인 창을 거치고 목록에서 빠진다
  - 다른 사용자의 상세에는 「공개와 삭제」 절이 없다
- `test/browser/admin.spec.ts`: 관리자 절의 공개 범위 검사를 「공개와 삭제」 절 검사로 옮긴다

## 검증

```bash
# cwd: 저장소 root
cd web && pnpm typecheck && pnpm build
cd web && pnpm test:browser
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `web/src/app/api/agents/route.ts` | 수정 |
| `web/src/app/api/agents/[[]code]/visibility/route.ts` | 신규 |
| `web/src/app/api/agents/[[]code]/route.ts` | 신규 |
| `web/src/lib/agent.ts` | 수정 |
| `web/src/components/agent/create-agent-dialog.tsx` | 신규 |
| `web/src/app/agents/page.tsx` | 수정 |
| `web/src/components/agent/agent-admin-panel.tsx` | 수정 |
| `web/src/components/agent/agent-access-section.tsx` | 신규 |
| `web/src/components/agent/agent-detail-body.tsx` | 수정 |
| `web/src/components/agent/agent-admin-section.tsx` | 수정 |
| `test/browser/agent-lifecycle.spec.ts` | 신규 |
| `test/browser/admin.spec.ts` | 수정 |
| `tasks/plan041-user-agents/index.json` | 수정 |

마지막 phase 다. 검증이 통과하면 `index.json` 의 `status` 를 `completed` 로 바꿔 이 커밋에 담는다. 그 뒤 PR 의 마지막 커밋으로 `tasks/plan041-user-agents/` 를 지우고, `docs/code-architecture.md` 「아직 만들지 않은 것」 의 에이전트 만들기 줄을 뺀다.
