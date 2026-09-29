# Phase 03. 에이전트 상세의 스킬 절과 스킬 편집 페이지

**Execution profile**: standard

## 목표

에이전트 상세에 「스킬」 절을 두고, 관리하는 사람은 별도 페이지에서 스킬을 쓰고 고치고 지운다.

**범위 외**: backend(phase 01, 02), 사용량 화면(phase 04), 입력창의 `/` 자동완성(스킬 커맨드 계획).

## 컨텍스트

**근거 문서**: `docs/code-architecture.md` 의 「에이전트 화면」, 「스킬」 절, `docs/flow.md` 의 「스킬을 저장할 때」 절, `web/AGENTS.md`

- backend 경로: `GET /api/v1/agents/{code}/skills` → `{skills:[{name, description, source, enabled, usage?}], editable, skillsToolsetEnabled}`, `GET /api/v1/agents/{code}/skills/{name}` → `{name, description, body, files:[{path, size}]}`(`body` 는 앞머리를 포함한 `SKILL.md` 원문), `PUT /api/v1/agents/{code}/skills/{name}`(본문 `{skillMd, files:[{path, content?}]}`, `content` 를 빼면 지금 파일을 그대로 둔다), `DELETE /api/v1/agents/{code}/skills/{name}`, `PUT /api/v1/agents/{code}/skills/{name}/enabled` `{enabled}`
- 에이전트 상세 서버 페이지 `web/src/app/agents/[code]/page.tsx` 가 `callControlPlane` 으로 성격, 추천 질문, 도구를 함께 읽어 `web/src/components/agent/agent-detail-body.tsx` 에 `Loaded<T>` props 로 넘긴다. `ADMIN` 이 다른 사람의 비공개 에이전트를 열면 도구를 관리자 경로로 읽는다(`useAdminTools`)
- 도구 절 `web/src/components/agent/agent-tools-section.tsx` 의 모양과 오류 표시를 따른다
- 단순 중계 서버 라우트의 선례는 `web/src/app/api/agents/[code]/persona/route.ts`(`AGENT_CODE_PATTERN` 검사, `callControlPlane`, 오류는 `{code, message}` 와 상태 그대로)
- 오류 문구는 `web/src/components/error-message.ts` 가 코드별로 갖는다. `AGENT_BUSY` 는 이미 있다
- 서버에서 데이터를 읽는 화면 경로는 `loading.tsx` 를 함께 둔다(`docs/code-architecture.md` 「디렉터리」)
- 마크다운 미리보기는 `react-markdown` 과 `remark-gfm` 을 쓰는 기존 부품을 따른다. 들어온 HTML 을 그리지 않는다
- 파일 올리기는 브라우저에서 `File.text()` 로 읽어 JSON 에 담는다

## 의도 메모

- 스킬 절은 누구에게나 목록을 보인다. 관리하지 않는 사람에게는 「`/이름` 으로 부를 수 있어요」 안내만 더한다
- `source` 가 `HERMES` 인 스킬은 편집과 삭제가 없고 관리하는 사람에게 켜고 끄기만 있다
- `skillsToolsetEnabled` 가 거짓이고 스킬이 없으면 「스킬을 추가하면 스킬 도구가 함께 켜져요」 를 보인다
- 스킬 목록 읽기가 `AGENT_NOT_FOUND` 면(다른 사람의 비공개 에이전트를 `ADMIN` 이 연 경우) 스킬 절을 그리지 않는다. 다른 실패는 절 안에 실패 문구를 보인다
- 편집 페이지는 `/agents/{code}/skills/new` 가 새 스킬, `/agents/{code}/skills/{name}` 이 기존 스킬이다. 이름은 새 스킬일 때만 입력한다
- 새 스킬 화면은 저장하기 전에 목록을 읽어, 이미 올린 스킬과 이름이 같으면 저장하지 않고 「이미 같은 이름의 스킬이 있어요」 를 보인다. `PUT` 은 없으면 만들고 있으면 바꾸므로 그대로 보내면 덮어쓴다
- 기존 스킬을 고칠 때 손대지 않은 참고 파일은 `content` 없이 `{path}` 만 보낸다. 새로 올린 파일만 `content` 를 담는다. 뺀 파일은 목록에서 빠진다
- 텍스트가 아닌 파일은 화면에서 거절한다. 판정: `File.type` 이 `text/` 로 시작하거나 비어 있고 확장자가 `.md`, `.txt`, `.json`, `.yaml`, `.yml`, `.csv` 중 하나이며, 읽은 글에 `\u0000` 이 없다. 경로는 `references/<파일 이름>` 으로 두고 올린 쪽에서 `templates/` 로 바꿀 수 있다
- 저장하는 동안 단추를 막는다. 저장이 끝나면 에이전트 상세로 돌아간다
- 실패 문구는 backend 오류 코드별로 보인다: `VALIDATION_FAILED` 는 받은 메시지, `SKILL_NAME_TAKEN` 은 「같은 이름의 기본 스킬이 있어요」, `AGENT_BUSY` 는 기존 문구, `HERMES_UNAVAILABLE` 은 「저장하지 못했어요. 바뀐 내용이 반영되지 않았을 수 있으니 다시 저장해 주세요」. 이 문구는 `error-message.ts` 의 전역 문구를 바꾸지 않고 편집기에서 `describeFailure(response, overrides)` 의 overrides 로 덮는다

## 작업 항목

### 1. 서버 라우트와 타입

- `web/src/app/api/agents/[code]/skills/route.ts` 신규(`GET`)
- `web/src/app/api/agents/[code]/skills/[name]/route.ts` 신규(`GET`, `PUT`, `DELETE`). 이름을 `^[a-z0-9][a-z0-9-]{0,63}$` 로 검사한다
- `web/src/app/api/agents/[code]/skills/[name]/enabled/route.ts` 신규(`PUT`)
- `web/src/lib/skill.ts` 신규: `SkillListView`, `SkillDetailView`, `SKILL_NAME_PATTERN`
- `web/src/components/error-message.ts`: `SKILL_NAME_TAKEN`, `SKILL_NOT_FOUND` 문구를 더한다

### 2. 에이전트 상세의 스킬 절

- `web/src/app/agents/[code]/page.tsx`: 다른 읽기와 함께 `/api/v1/agents/{code}/skills` 를 읽어 `skills` props 로 넘긴다
- `web/src/components/agent/agent-skills-section.tsx` 신규, `agent-detail-body.tsx` 에서 도구 절 다음에 둔다. 스킬마다 이름, 설명, 「올린 스킬」 이나 「Hermes 기본」 표시, 켜짐. 관리하는 사람에게는 켜고 끄기, `usage` 의 호출 수와 마지막 호출, 올린 스킬의 편집 링크와 확인 창을 거치는 삭제, 「스킬 추가」 링크

### 3. 편집 페이지

- `web/src/app/agents/[code]/skills/[name]/page.tsx` 신규(서버에서 상세를 읽는다), `web/src/app/agents/[code]/skills/[name]/loading.tsx` 신규
- `web/src/app/agents/[code]/skills/new/page.tsx` 신규. 서버에서 읽지 않는다(`callControlPlane` 을 부르지 않는다). 이름 확인용 목록은 편집기가 브라우저에서 서버 라우트로 읽는다. `test/unit/loading-routes.test.ts` 가 서버에서 읽는 페이지마다 `loading.tsx` 를 요구한다
- 둘이 `web/src/components/agent/skill-editor.tsx` 신규를 쓴다(이름 입력은 새 스킬일 때만, `SKILL.md` 본문 편집과 미리보기, 참고 파일 목록과 올리기와 빼기, 저장)

### 4. 이 phase 를 검증하는 브라우저 검사

- `test/browser/skills.spec.ts` 신규
  - 주인이 스킬 절의 「스킬 추가」 로 새 스킬을 쓰고 참고 파일 하나를 올려 저장하면 목록에 「올린 스킬」 로 보인다. 다시 열어 설명만 고치고 저장하면 바뀐 설명이 보이고 **참고 파일이 목록에 남아 있다**. 지우면 사라진다
  - 앞머리 이름이 다르면 편집 페이지에 오류가 보이고 저장되지 않는다
  - 새 스킬 화면에 이미 올린 이름을 넣으면 오류가 보이고 덮어쓰지 않는다
  - 가족용 에이전트를 다른 사용자가 열면 목록과 `/이름` 안내만 있고 편집 단추가 없다

## 검증

```bash
# cwd: 저장소 root
cd web && pnpm typecheck
cd web && AUTH_SECRET=build-time-placeholder ASSISTANT_JWT_SECRET=build-time-placeholder CONTROL_PLANE_BASE_URL=http://build-time-placeholder AUTH_GOOGLE_ID=build-time-placeholder AUTH_GOOGLE_SECRET=build-time-placeholder pnpm build
cd web && pnpm test:browser
node --test 'test/unit/**/*.test.ts'
grep -rn 'style={{' web/src/
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `web/src/app/api/agents/[[]code]/skills/route.ts` | 신규 |
| `web/src/app/api/agents/[[]code]/skills/[[]name]/route.ts` | 신규 |
| `web/src/app/api/agents/[[]code]/skills/[[]name]/enabled/route.ts` | 신규 |
| `web/src/lib/skill.ts` | 신규 |
| `web/src/components/error-message.ts` | 수정 |
| `web/src/app/agents/[[]code]/page.tsx` | 수정 |
| `web/src/components/agent/agent-skills-section.tsx` | 신규 |
| `web/src/components/agent/agent-detail-body.tsx` | 수정 |
| `web/src/app/agents/[[]code]/skills/[[]name]/page.tsx` | 신규 |
| `web/src/app/agents/[[]code]/skills/[[]name]/loading.tsx` | 신규 |
| `web/src/app/agents/[[]code]/skills/new/page.tsx` | 신규 |
| `web/src/components/agent/skill-editor.tsx` | 신규 |
| `test/browser/skills.spec.ts` | 신규 |
