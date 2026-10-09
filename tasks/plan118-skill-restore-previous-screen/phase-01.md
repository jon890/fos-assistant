# Phase 01. 스킬 편집 화면에서 이전 버전으로 되돌린다

**Execution profile**: standard

## 목표

올린 스킬의 편집 화면에서 이전 버전이 있으면 남긴 시각과 「이전 버전으로」 단추를 보인다. 누르면 확인을 받은 뒤 이전 버전과 지금 버전을 맞바꾸고 화면을 새 내용으로 다시 그린다.
잘못 올린 스킬을 화면에서 한 번에 되돌리게 하려는 것이다.

**범위 외**: Control Plane 변경. 목록 화면의 되돌리기. 편집기의 넓힌 경로 지원.

## 컨텍스트

**근거 문서**: `backend/docs/flow.md` 의 「스킬」 > 「이전 버전」, `docs/adr/ADR-20261009-skill-package.md` 결정 5, `web/docs/prd.md` 의 「스킬 편집」 행, `web/AGENTS.md` 「화면 문구」

- Control Plane 경로는 이미 있다. `POST /api/v1/agents/{code}/skills/{name}/restore-previous`, 응답은 `GET /api/v1/agents/{code}/skills/{name}` 과 같은 스킬 상세다. 이전 버전이 없으면 404 `SKILL_NOT_FOUND`, 셸 조건을 어기면 409 `SKILL_SCRIPTS_NEED_SANDBOX`. 상세 응답의 `previousSavedAt` 은 이전 버전을 남긴 시각(ISO 문자열)이고 없으면 `null` 이다(`backend/src/main/java/com/bifos/assistant/skill/presentation/SkillDtos.java` 의 `SkillDetailView`, `SkillController.restorePrevious`)
- 웹 타입 `web/src/lib/skill.ts` 의 `SkillDetailView` 에는 아직 `previousSavedAt` 이 없다
- 프록시 본보기는 `web/src/app/api/agents/[code]/skills/[name]/route.ts`(`parsePath`, `callControlPlane`, `errorResponse`)
- 편집 화면은 `web/src/app/agents/[code]/skills/[name]/page.tsx`(서버 컴포넌트, `callControlPlane<SkillDetailView>` 로 읽고 `SkillEditor` 를 그린다). `web/src/components/agent/skill-editor.tsx` 는 `initial` 을 `useState` 초기값으로 쓰므로 새 상세가 와도 상태가 그대로다. 449줄이라 파일 길이 기준을 넘지 않게 손대지 않는다
- 확인 창 본보기는 `web/src/components/agent/agent-skills-section.tsx` 의 `DeleteConfirm`(`AlertDialog`, 요청 중 닫히지 않음, 실패하면 창에 까닭). 시각 표시는 `web/src/lib/format.ts` 의 `formatWhen`
- 웹 API 함수는 `web/src/lib/agent-api.ts`. 공용 오류 문구는 `web/src/components/error-message.ts`(`SKILL_SCRIPTS_NEED_SANDBOX` 문구가 이미 있다)
- 브라우저 테스트 본보기는 `test/browser/skills.spec.ts`(`putSkill`, `removeSkill`)

## 의도 메모

- 되돌린 뒤 편집기를 새 내용으로 다시 그리려고 `page.tsx` 가 `SkillEditor` 에 `key={detail.previousSavedAt ?? "none"}` 를 준다. 되돌리기마다 `previousSavedAt` 이 바뀌므로 편집기가 다시 마운트된다. 되돌리기 컴포넌트는 성공하면 `router.refresh()` 를 부른다
- 편집기에 저장하지 않은 변경이 있어도 되돌리기는 서버의 지금 버전과 이전 버전을 맞바꾼다. 확인 창 문구가 그 사실을 알린다

## 작업 항목

### 1. 프록시와 웹 함수

- `web/src/app/api/agents/[code]/skills/[name]/restore-previous/route.ts`: `POST`. 이름과 코드 검사는 `[name]/route.ts` 의 `parsePath` 와 같은 규칙(같은 함수를 export 해 쓰거나 같은 검사를 둔다)이고 `callControlPlane(..., { method: "POST" })` 결과를 그대로 돌려준다
- `web/src/lib/agent-api.ts`: `restorePreviousSkill(code: string, name: string): Promise<Response>`
- `web/src/lib/skill.ts`: `SkillDetailView` 에 `previousSavedAt: string | null`

### 2. `web/src/components/agent/skill-restore-previous.tsx`

- `"use client"`. props `code`, `name`, `previousSavedAt: string`
- 「이전 버전: <formatWhen 결과>」 글과 「이전 버전으로」 단추(`variant="outline"`)
- 누르면 `AlertDialog` 확인 창. 제목 「이전 버전으로 되돌릴까요?」, 설명 「지금 버전과 이전 버전을 맞바꿔요. 한 번 더 누르면 다시 돌아와요. 저장하지 않은 편집은 사라져요.」. 단추 「되돌리기」
- 요청 중에는 단추를 끄고 창이 닫히지 않는다. 실패하면 창에 `describeFailure` 문구를 보인다(`SKILL_NOT_FOUND` 는 이 컴포넌트 고유 문구 「되돌릴 이전 버전이 없어요.」)
- 성공하면 창을 닫고 `router.refresh()`

### 3. `web/src/app/agents/[code]/skills/[name]/page.tsx`

- `result.data.previousSavedAt` 이 있으면 편집기 위에 `SkillRestorePrevious` 를 그린다
- `SkillEditor` 에 `key={result.data.previousSavedAt ?? "none"}`

### 4. 문서

- `web/docs/prd.md` 「스킬 편집」 행에 「이전 버전이 있으면 남긴 시각과 「이전 버전으로」」 를 더한다
- `web/docs/flow.md` 의 「스킬 묶음 올리기 화면」 절 뒤에 「이전 버전 되돌리기 화면」 절을 더한다(같은 문서 안 헤딩이 겹치지 않게)
- `docs/prd.md` 「아직 만들지 않은 것」 의 스킬 줄에서 「이전 버전 되돌리기 화면」 을 뺀다

### 5. 이 phase 를 검증하는 `test/browser/skill-restore.spec.ts`

- 주인이 스킬을 두 번 저장(`putSkill` 로 설명 A 다음 설명 B)한 뒤 편집 화면을 열면 「이전 버전으로」 가 보이고, 되돌리면 편집기의 `SKILL.md` 본문에 설명 A 가 있다. 한 번 더 되돌리면 설명 B 다
- 한 번만 저장한 스킬의 편집 화면에는 「이전 버전으로」 가 없다
- 테스트가 만든 스킬은 `finally` 에서 지운다

## 검증

```bash
cd web && pnpm lint
cd web && pnpm exec tsc --noEmit
node scripts/check-file-length.mjs
node --test 'test/unit/**/*.test.ts'
cd web && pnpm exec playwright test --config ../test/browser/playwright.config.ts ../test/browser/skill-restore.spec.ts ../test/browser/skills.spec.ts --repeat-each=3 --retries=0
```

- 모두 종료 코드 0
- playwright 줄은 `/Users/nhn/personal/fos-assistant/.omc/scripts/heavy-lock` 으로 감싸 실행한다. 데스크톱과 모바일 두 프로젝트를 모두 돈다

## 변경 파일

| 파일 | 변경 |
|---|---|
| `web/src/app/api/agents/[code]/skills/[name]/restore-previous/route.ts` | 신규 |
| `web/src/app/api/agents/[code]/skills/[name]/route.ts` | 수정 |
| `web/src/lib/agent-api.ts` | 수정 |
| `web/src/lib/skill.ts` | 수정 |
| `web/src/components/agent/skill-restore-previous.tsx` | 신규 |
| `web/src/app/agents/[code]/skills/[name]/page.tsx` | 수정 |
| `web/docs/prd.md` | 수정 |
| `web/docs/flow.md` | 수정 |
| `docs/prd.md` | 수정 |
| `test/browser/skill-restore.spec.ts` | 신규 |
