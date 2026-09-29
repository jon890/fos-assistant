# Phase 03. 스킬 절과 스킬 편집 페이지, 사용량 탭

**Execution profile**: standard

## 목표

에이전트 상세에 「스킬」 절을 두고, 관리하는 사람은 별도 페이지에서 스킬을 쓰고 고친다.
사용량 화면을 네 탭으로 나누고 「스킬」 탭에서 자기 스킬 호출을 본다.

**범위 외**: backend(phase 01, 02). 입력창의 `/` 자동완성(스킬 커맨드 계획).

## 컨텍스트

**근거 문서**: `docs/code-architecture.md` 의 「에이전트 화면」, 「스킬」, 「사용량 화면의 탭」 절, `docs/flow.md` 의 「스킬을 저장할 때」 절

- backend 경로: `GET /api/v1/agents/{code}/skills` → `{skills:[{name, description, source, enabled, usage?}], editable, skillsToolsetEnabled}`, `GET/PUT/DELETE /api/v1/agents/{code}/skills/{name}`(`PUT` 본문 `{skillMd, files:[{path, content}]}`), `PUT /api/v1/agents/{code}/skills/{name}/enabled` `{enabled}`, `GET /api/v1/usage/skills`. 실행 목록 줄에 `skillNames`
- 에이전트 상세: `web/src/components/agent/agent-detail-body.tsx`(성격, 도구 절 순). 도구 절 `web/src/components/agent/agent-tools-section.tsx` 의 모양과 오류 표시를 따른다
- 서버 라우트는 `web/src/app/api/agents/[code]/tools/route.ts` 처럼 `callControlPlane` 으로 넘긴다
- 사용량 화면: `web/src/app/usage/page.tsx` 가 `MonthlySummary`, `BreakdownSection`, `FingerprintSection`, `ExecutionList` 를 차례로 그린다. 실행 줄은 `web/src/components/usage/execution-card.tsx`, `execution-table.tsx`
- 마크다운 미리보기는 `react-markdown` 과 `remark-gfm` 을 쓰는 기존 부품을 따른다(`docs/code-architecture.md` 「화면 밖에서 오는 글은 마크다운으로 읽는다」)
- 파일 올리기는 브라우저에서 `File.text()` 로 읽어 JSON 에 담는다. 텍스트가 아닌 파일은 화면에서 거절한다

## 의도 메모

- 스킬 절은 누구에게나 목록을 보인다. 관리하지 않는 사람에게는 「`/이름` 으로 부를 수 있어요」 안내만 더한다
- `source` 가 `HERMES` 인 스킬은 편집과 삭제가 없고 켜고 끄기만 있다
- `skillsToolsetEnabled` 가 거짓이고 스킬이 없으면 「스킬을 추가하면 스킬 도구가 함께 켜져요」 를 보인다
- 편집 페이지는 `/agents/{code}/skills/new` 가 새 스킬, `/agents/{code}/skills/{name}` 이 기존 스킬이다. 이름은 새 스킬일 때만 입력한다
- 저장하는 동안 단추를 막는다. 저장이 끝나면 스킬 절로 돌아간다. 실패 문구는 backend 오류 코드별로 보인다(`VALIDATION_FAILED` 는 메시지, `SKILL_NAME_TAKEN` 은 「같은 이름의 기본 스킬이 있어요」)
- 사용량 탭은 주소 `?tab=` 에 남긴다. 기본은 `summary`

## 작업 항목

### 1. 서버 라우트와 타입

- `web/src/app/api/agents/[code]/skills/route.ts` 신규(`GET`)
- `web/src/app/api/agents/[code]/skills/[name]/route.ts` 신규(`GET`, `PUT`, `DELETE`)
- `web/src/app/api/agents/[code]/skills/[name]/enabled/route.ts` 신규(`PUT`)
- `web/src/app/api/usage/skills/route.ts` 신규(`GET`)
- `web/src/lib/skill.ts` 신규: `SkillListView`, `SkillDetailView`, `SkillUsageRow`

### 2. 에이전트 상세의 스킬 절과 편집 페이지

- `web/src/components/agent/agent-skills-section.tsx` 신규, `web/src/components/agent/agent-detail-body.tsx` 에서 도구 절 다음에 둔다
- `web/src/app/agents/[code]/skills/[name]/page.tsx` 신규, `web/src/app/agents/[code]/skills/new/page.tsx` 신규, 둘이 `web/src/components/agent/skill-editor.tsx` 신규를 쓴다(본문 편집, 미리보기, 참고 파일 목록과 올리기와 빼기)

### 3. 사용량 탭

- `web/src/components/usage/usage-tabs.tsx` 신규, `web/src/app/usage/page.tsx` 가 탭 안에 기존 절을 옮긴다
- `web/src/components/usage/skill-usage-list.tsx` 신규: 스킬마다 에이전트, 횟수, 마지막 호출, 누르면 그 대화
- `execution-card.tsx`, `execution-table.tsx`: `skillNames` 를 작게 붙인다

### 4. 이 phase 를 검증하는 브라우저 검사

- `test/browser/skills.spec.ts` 신규
  - 주인이 스킬 절의 「스킬 추가」 로 새 스킬을 쓰고 참고 파일 하나를 올려 저장하면 목록에 「올린 스킬」 로 보인다. 다시 열어 고치고 저장하면 바뀐 설명이 보인다. 지우면 사라진다
  - 앞머리 이름이 다르면 편집 페이지에 오류가 보이고 저장되지 않는다
  - 가족용 에이전트를 다른 사용자가 열면 목록과 `/이름` 안내만 있고 편집 단추가 없다
- `test/browser/usage.spec.ts`: 탭 넷이 보이고 `?tab=skills` 로 열면 스킬 탭이 열린다. 스킬을 부른 대화 뒤 스킬 탭에 그 스킬이 보인다. 실행 기록 탭에 스킬 이름이 붙는다
- `test/browser/usage-breakdown.spec.ts`: 「어디에 썼나」 가 요약 탭 안에 있게 맞춘다

## 검증

```bash
# cwd: 저장소 root
cd web && pnpm typecheck && pnpm build
cd web && pnpm test:browser
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `web/src/app/api/agents/[[]code]/skills/route.ts` | 신규 |
| `web/src/app/api/agents/[[]code]/skills/[[]name]/route.ts` | 신규 |
| `web/src/app/api/agents/[[]code]/skills/[[]name]/enabled/route.ts` | 신규 |
| `web/src/app/api/usage/skills/route.ts` | 신규 |
| `web/src/lib/skill.ts` | 신규 |
| `web/src/components/agent/agent-skills-section.tsx` | 신규 |
| `web/src/components/agent/agent-detail-body.tsx` | 수정 |
| `web/src/app/agents/[[]code]/skills/[[]name]/page.tsx` | 신규 |
| `web/src/app/agents/[[]code]/skills/new/page.tsx` | 신규 |
| `web/src/components/agent/skill-editor.tsx` | 신규 |
| `web/src/components/usage/usage-tabs.tsx` | 신규 |
| `web/src/components/usage/skill-usage-list.tsx` | 신규 |
| `web/src/app/usage/page.tsx` | 수정 |
| `web/src/components/usage/execution-card.tsx` | 수정 |
| `web/src/components/usage/execution-table.tsx` | 수정 |
| `test/browser/skills.spec.ts` | 신규 |
| `test/browser/usage.spec.ts` | 수정 |
| `test/browser/usage-breakdown.spec.ts` | 수정 |
| `tasks/plan042-agent-skills/index.json` | 수정 |

마지막 phase 다. 검증이 통과하면 `index.json` 의 `status` 를 `completed` 로 바꿔 이 커밋에 담는다. 그 뒤 PR 의 마지막 커밋으로 `tasks/plan042-agent-skills/` 를 지우고, `docs/code-architecture.md` 「아직 만들지 않은 것」 의 스킬 줄에서 스킬 올리기와 관리, 호출 이력, 사용량 탭을 뺀다(스킬 커맨드는 남긴다).
