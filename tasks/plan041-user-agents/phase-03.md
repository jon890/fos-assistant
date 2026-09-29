# Phase 03. 에이전트 목록의 「새 에이전트」 와 상세의 「공개와 삭제」

**Execution profile**: standard

## 목표

모든 사용자가 에이전트 목록에서 이름만 넣어 에이전트를 만들고, 상세에서 나만과 그룹 공개를 바꾸고 지운다.

**범위 외**: backend(phase 01, 02). 스킬 절(스킬 계획).

## 컨텍스트

**근거 문서**: `docs/code-architecture.md` 의 「에이전트 화면」, 「에이전트 만들기와 지우기」 절, `docs/flow.md` 의 「에이전트를 만들 때」 절

- phase 02 뒤 backend: `POST /api/v1/agents` `{name, visibility?}` → 201 `AgentView`, `PATCH /api/v1/agents/{code}/visibility` `{visibility}` → 200 `AgentView`, `DELETE /api/v1/agents/{code}` → 204. `AgentView` 에 `editable`, `ownedByMe` 가 있다. 상한이면 409 `AGENT_LIMIT_REACHED`. 공개 범위 변경과 지우기는 주인과 `ADMIN` 이 하고, `ADMIN` 은 다른 사람의 비공개 에이전트도 코드로 찾는다
- `web/src/app/agents/page.tsx`: `ADMIN` 이면 `AgentAdminPanel`(관리자 목록), 아니면 카드 목록을 그린다. 빈 목록 문구는 「관리자가 에이전트를 연결하면 여기에 표시돼요」
- `web/src/app/agents/[code]/page.tsx`: `GET /api/v1/agents` 목록(`AgentView[]`)과, `ADMIN` 이면 관리자 목록(`AdminAgent[]`)을 읽어 `AgentDetailBody` 에 `initialVisibility`, `adminAgent` 를 넘긴다. 관리자가 다른 사람의 비공개 에이전트를 열면 목록에 없고 `adminAgent` 만 있다
- `web/src/app/api/agents/route.ts`: `GET` 만 있다. 서버 라우트는 `callControlPlane` 으로 넘긴다. `web/src/app/api/agents/[code]/` 아래에 `persona`, `starters`, `tools` 가 있다
- `web/src/components/agent/agent-detail-body.tsx`: 성격, 도구, 관리자 절을 세로로 그린다. `web/src/components/agent/agent-admin-section.tsx` 가 공개 범위와 사용 여부와 주소를 바꾼다. 그룹 공개는 `web/src/components/admin/visibility-confirm.tsx` 의 `VisibilityConfirm`(prop `agent: AdminAgent`, 제목 「{이름} 에이전트를 그룹에 공개할까요?」, 확인 단추 「그룹 공개」, 진행 중 「공개하는 중」) 확인 창을 거친다. 관리자 절의 단추 이름은 「그룹 공개로 변경」, 「나만으로 변경」, 공개 범위 표시는 「나만」, 「그룹 공개」 다
- 화면 부품과 색은 지금 main 의 이름을 쓴다(`web/src/components/ui/`). 공용 규칙은 `web/AGENTS.md`. `danger` 토큰은 쓰지 않는다
- 브라우저 검사: `test/browser/playwright.config.ts` 는 `mobile`, `desktop` 두 project 를 `workers: 1` 로 차례로 돌리고 한 backend 를 함께 쓴다. 로그인은 `test/browser/fixtures.ts` 의 `setSession(context, {email, name})` 이다. 여러 spec(`persona.spec.ts:115-116` 의 주석, `identity.spec.ts`)이 관리 화면의 그룹 공개 에이전트가 정확히 하나라고 가정한다. `identity.spec.ts` 는 「그룹 공개로 변경」 단추의 `type` 을 단언한다

## 의도 메모

- 화면 문구는 지금 화면의 이름을 따른다: 공개 범위는 「나만」, 「그룹 공개」. 루트 `AGENTS.md` 「용어」 표가 사용자들이 모인 단위를 「그룹」 으로 부르기 때문이다
- 「새 에이전트」 는 대화상자 하나다. 이름 입력과 공개 범위(「나만」, 「그룹 공개」, 기본 「나만」) 선택뿐이다. 성격과 도구는 만든 뒤 상세에서 고친다
- 만드는 동안 단추를 막고 「만드는 중…」 을 보인다. 몇 초 걸린다
- 그룹 공개로 바꾸는 것은 `VisibilityConfirm` 을 거친다. 그 창의 설명 문구를 「그룹의 모든 사용자가 이 에이전트와 대화할 수 있어요. 각자의 대화와 기억은 서로 보이지 않아요」 로 바꾸고, prop 을 `name: string` 으로 바꿔 관리자 전용 타입에 묶이지 않게 한다. 제목, 단추, 진행 중 문구는 그대로다
- 지우기 확인 창: 제목 「{이름} 에이전트를 지울까요?」, 설명 「에이전트를 지우면 새 대화를 시작할 수 없어요. 지난 대화는 읽을 수 있어요」, 확인 단추 「지우기」, 진행 중 「지우는 중」. 요청이 도는 동안 닫히지 않고, 실패하면 창이 남아 까닭을 보인다(`VisibilityConfirm` 과 같은 규칙)
- 「공개와 삭제」 절의 단추 이름은 「그룹 공개로 변경」, 「나만으로 변경」, 「에이전트 지우기」. 절 제목은 「공개와 삭제」
- 「공개와 삭제」 절은 관리할 수 있을 때만 그린다. 목록 `AgentView` 의 `editable` 이 참이거나, `ADMIN` 이 관리자 목록으로 연 에이전트(`adminAgent` 가 있음)일 때다
- 관리자 절에서는 공개 범위 선택과 확인 창을 빼고 사용 여부와 주소만 남긴다. 저장할 때는 지금 공개 범위와 `ownerEmail` 없음을 보내 주인을 그대로 둔다(phase 01 의 관리자 `update` 규칙)
- 지운 뒤에는 `/agents` 로 간다

## 작업 항목

### 1. 서버 라우트

- `web/src/app/api/agents/route.ts`: `POST` 를 더한다
- `web/src/app/api/agents/[code]/visibility/route.ts` 신규: `PATCH`
- `web/src/app/api/agents/[code]/route.ts` 신규: `DELETE`
- `web/src/lib/agent.ts`: `AgentView` 에 `editable`, `ownedByMe`

### 2. 화면

- `web/src/components/agent/create-agent-dialog.tsx` 신규: 이름, 공개 범위, 만들기. 성공하면 `/agents/{code}` 로 간다. 409 `AGENT_LIMIT_REACHED` 면 「에이전트는 5개까지 만들 수 있어요」 를 대화상자 안에 보인다. 다른 실패는 `describeError` 문구를 대화상자 안에 보인다
- `web/src/app/agents/page.tsx`: 제목 옆에 「새 에이전트」 단추. 빈 목록 문구를 「아직 에이전트가 없어요. 새 에이전트를 만들어 보세요」 로 바꾼다
- `web/src/components/agent/agent-admin-panel.tsx`: 관리자 목록에도 같은 단추를 둔다
- `web/src/components/admin/visibility-confirm.tsx`: 위 의도 메모대로 prop 과 설명 문구를 바꾼다
- `web/src/components/agent/agent-access-section.tsx` 신규: 「공개와 삭제」 절. 지금 공개 범위 표시, 공개 범위 바꾸기, 지우기
- `web/src/components/agent/agent-detail-body.tsx`: 맨 아래에 위 절을 둔다. 절을 그릴지 정하는 값을 prop 으로 받는다
- `web/src/app/agents/[code]/page.tsx`: 목록의 `editable` 과 `adminAgent` 로 위 값을 정해 넘긴다
- `web/src/components/agent/agent-admin-section.tsx`: 공개 범위 선택과 확인 창을 뺀다

### 3. 이 phase 를 검증하는 브라우저 검사

- `test/browser/agent-lifecycle.spec.ts` 신규
  - 사용자는 project 마다 다른 `MEMBER` 메일(`lifecycle-${testInfo.project.name}@example.com` 처럼)로 로그인한다. 그 메일이 로그인되게 하는 방법은 다른 spec 이 `member@example.com` 을 쓰는 방식과 같게 한다. 두 폭이 같은 backend 에서 상한을 나눠 쓰지 않게 하기 위해서다
  - 검사마다 만든 에이전트는 그 검사 끝에서 `DELETE` 로 지운다(`afterEach` 등). 그룹 공개 에이전트가 남아 다른 spec 의 가정을 깨지 않게 한다
  - 일반 사용자가 「새 에이전트」 에 이름만 넣어 만들면 상세로 가고, 새 대화 화면에서 그 에이전트로 보낸 질문이 답으로 끝난다
  - 상한만큼 만든 뒤 다시 만들면 대화상자 안에 상한 문구가 보인다
  - 그룹 공개로 바꾸면 확인 창을 거치고 다른 사용자의 목록에 보인다. 만든 사람은 여전히 성격을 고칠 수 있다
  - 지우면 확인 창을 거치고 목록에서 빠진다
  - 다른 사용자의 상세에는 「공개와 삭제」 절이 없다
- `test/browser/admin.spec.ts`: 관리자 절의 공개 범위 검사(확인, 취소, Esc, 실패, 진행 중)를 「공개와 삭제」 절 검사로 옮긴다. 다른 사람의 비공개 에이전트 검사는 공개 범위를 「공개와 삭제」 절에서 바꾸게 고친다
- `test/browser/identity.spec.ts`: 「그룹 공개로 변경」 단추를 찾는 자리가 「공개와 삭제」 절을 가리키게 고친다(같은 이름이면 그대로 둘 수 있다)

### 4. 문서

- `docs/code-architecture.md` 「아직 만들지 않은 것」 에서 사용자가 에이전트를 만들고 공개하고 지우는 줄을 뺀다
- `docs/code-architecture.md` 「에이전트 화면」 표의 「상세의 공개와 삭제」 줄에서 「개인용과 가족용을 바꾸는 스위치」 를 화면 문구에 맞춰 「나만과 그룹 공개를 바꾸는 단추」 로 고친다

## 검증

```bash
# cwd: 저장소 root
cd web && pnpm typecheck
cd web && AUTH_SECRET=build-time-placeholder ASSISTANT_JWT_SECRET=build-time-placeholder CONTROL_PLANE_BASE_URL=http://build-time-placeholder AUTH_GOOGLE_ID=build-time-placeholder AUTH_GOOGLE_SECRET=build-time-placeholder pnpm build
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
| `web/src/app/agents/[[]code]/page.tsx` | 수정 |
| `web/src/components/agent/agent-admin-panel.tsx` | 수정 |
| `web/src/components/admin/visibility-confirm.tsx` | 수정 |
| `web/src/components/agent/agent-access-section.tsx` | 신규 |
| `web/src/components/agent/agent-detail-body.tsx` | 수정 |
| `web/src/components/agent/agent-admin-section.tsx` | 수정 |
| `test/browser/agent-lifecycle.spec.ts` | 신규 |
| `test/browser/admin.spec.ts` | 수정 |
| `test/browser/identity.spec.ts` | 수정 |
| `docs/code-architecture.md` | 수정 |
