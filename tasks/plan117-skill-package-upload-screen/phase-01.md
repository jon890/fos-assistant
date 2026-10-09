# Phase 01. 스킬 절에서 zip 묶음을 미리보고 올린다

**Execution profile**: standard

## 목표

에이전트 상세의 스킬 절에서 관리하는 사람이 zip 파일을 골라 미리보고 올린다.
같은 이름의 스킬이 있으면 바뀐 파일을 보인 뒤 덮어쓰기를 확인받는다. 실행 공간 없는 에이전트에 스크립트가 든 묶음을 올리면 까닭을 안내한다.

**범위 외**: 「이전 버전으로」 되돌리기(다음 plan). 편집기의 넓힌 경로 지원. GitHub 가져오기. Control Plane 변경.

## 컨텍스트

**근거 문서**: `backend/docs/flow.md` 의 「스킬 묶음 받기와 검사」 와 「스킬 묶음 미리보기와 올리기」(흐름, 문제 목록, `baseDigest` 표), `docs/adr/ADR-20261009-skill-package.md`, `web/docs/prd.md` 의 「상세의 스킬」 행, `web/AGENTS.md` 「화면 문구」

- Control Plane 경로는 `POST /api/v1/agents/{code}/skill-packages/preview`(multipart `file`, 응답은 미리보기, 문제가 있어도 200)와 `POST /api/v1/agents/{code}/skill-packages`(multipart `file`, 선택 `baseDigest`, 응답은 `GET /api/v1/agents/{code}/skills/{name}` 과 같은 스킬 상세)다. 응답 칸은 `backend/src/main/java/com/bifos/assistant/skill/presentation/SkillDtos.java` 의 `SkillPackagePreviewView`(`name`, `description`, `skillMdHead`, `existing`, `baseDigest`, `hasScripts`, `files[{path,size,change}]`, `ignored[]`, `problems[{reason,path}]`)다. `reason` 값은 `backend/src/main/java/com/bifos/assistant/skill/application/model/SkillPackageReason.java`, `change` 값은 같은 디렉터리의 `SkillPackageChange.java` 다
- 오류 코드: 올리기의 `SKILL_PACKAGE_INVALID`(400), `SKILL_CHANGED`(409), `SKILL_SCRIPTS_NEED_SANDBOX`(409), 잠금 안 재검사의 `SKILL_NAME_TAKEN`(409), `VALIDATION_FAILED`(400)
- multipart 프록시 본보기는 `web/src/app/api/chat/conversations/[conversationId]/attachments/route.ts`(`forwardControlPlane` 으로 본문을 그대로 흘려보내고 413 과 JSON 이 아닌 응답을 `VALIDATION_FAILED` 로 바꾼다). 경로 검사 본보기는 `web/src/app/api/agents/[code]/skills/route.ts`(`AGENT_CODE_PATTERN`)
- 화면 본보기는 `web/src/components/agent/agent-skills-section.tsx`(스킬 절, `editable` 일 때 「스킬 추가」 링크, 지우기 확인 창 `AlertDialog`, `describeFailure` 와 절 고유 문구 `SKILL_FAILURES`, `reload()`), 공용 오류 문구는 `web/src/components/error-message.ts`
- 웹 API 함수는 `web/src/lib/agent-api.ts`(`fetchAgentSkills`, `saveSkill` 패턴), 스킬 타입은 `web/src/lib/skill.ts`
- 파일 길이 상한: `web/src/**/*.ts(x)` 400줄(`node scripts/check-file-length.mjs`). `agent-skills-section.tsx` 는 지금 312줄이라 올리기 화면은 새 컴포넌트 파일로 둔다
- 브라우저 시험 본보기는 `test/browser/skills.spec.ts`(`setSession`, `PERSONA_AGENT_CODE`, `skillsSection`, `putSkill`, `removeSkill`, `page.request` 로 정리). 시험 zip 은 시험 안에서 만든다. 저장소에 zip 생성 의존이 없으면 STORED 방식 zip 바이트를 시험 helper 함수로 직접 조립한다(로컬 머리, 중앙 디렉터리, EOCD, CRC32 는 `node:zlib` 의 `crc32` 를 쓴다)

## 의도 메모

- 미리보기와 올리기는 같은 `File` 을 두 번 보낸다. 서버에 임시 상태가 없다(ADR)
- 미리보기의 `problems` 가 비어 있지 않으면 올리기 단추를 끈다. 화면은 까닭마다 해요체 문구를 보이고, `path` 가 있으면 함께 보인다
- `existing` 이면 단추 문구를 「덮어쓰기」 로 바꾸고 `files` 의 `CHANGED`, `ADDED`, `REMOVED` 를 위에 보인다. 올릴 때 `baseDigest` 를 함께 보낸다
- 오류 코드와 내부 값은 일반 화면에 그리지 않는다(`web/AGENTS.md`)

## 작업 항목

### 1. 프록시 두 경로

- `web/src/app/api/agents/[code]/skill-packages/preview/route.ts`, `web/src/app/api/agents/[code]/skill-packages/route.ts`: `POST`. `AGENT_CODE_PATTERN` 을 보고 attachments 본보기처럼 multipart 본문을 그대로 Control Plane 같은 경로(`/api/v1/agents/${code}/skill-packages/preview`, `/api/v1/agents/${code}/skill-packages`)로 흘려보낸다. 413 은 `VALIDATION_FAILED` 「zip 파일이 너무 커요.」 로 바꾼다
- 두 파일이 같은 응답 처리를 쓰면 `web/src/lib/forward-multipart.ts` 에 `forwardMultipart(path: string, request: Request, tooLargeMessage: string): Promise<NextResponse>` 로 모으고 attachments 경로도 그것을 쓰게 바꾼다

### 2. 웹 타입과 API 함수

- `web/src/lib/skill.ts`: `SkillPackageProblemView`, `SkillPackageFileView`, `SkillPackagePreviewView` 타입(위 응답 칸 그대로). `reason` 은 문자열 리터럴 유니언, `change` 도 같다
- `web/src/lib/agent-api.ts`: `previewSkillPackage(code: string, file: File): Promise<Response>`, `uploadSkillPackage(code: string, file: File, baseDigest: string | null): Promise<Response>`. `FormData` 에 `file` 과(있으면) `baseDigest` 를 담는다

### 3. 화면: `web/src/components/agent/skill-package-upload.tsx`

- `editable` 일 때 스킬 절의 「스킬 추가」 옆에 「zip 으로 올리기」 단추. 숨긴 `<input type="file" accept=".zip,application/zip" aria-label="스킬 zip 파일">` 을 연다
- 파일을 고르면 미리보기를 요청하고 대화 창(`AlertDialog` 가 아닌 일반 `Dialog` 가 있으면 그것, 없으면 `AlertDialog`)에 보인다. 제목은 새 스킬이면 「<name> 스킬을 올릴까요?」, 있으면 「<name> 스킬을 덮어쓸까요?」. 이름을 읽지 못했으면 「스킬 묶음을 올릴 수 없어요」
  - 설명, 파일 목록(경로, 크기, 바뀜 표시: 「새 파일」, 「바뀜」, 「같음」, 「지워짐」), 빼고 올린 파일 목록(있을 때), `hasScripts` 면 「이 스크립트는 실행 공간에서 에이전트가 실행할 수 있어요.」
  - `SKILL.md` 앞부분(`skillMdHead`)은 `<pre>` 로 글 그대로 보인다. 마크다운으로 그리지 않는다
  - `problems` 가 있으면 `Notice variant="error"` 목록으로 까닭 문구와 경로를 보이고 올리기 단추를 끈다
  - 까닭 문구 표는 이 파일 안에 둔다. 각 `reason` 마다 해요체 한 문장. 예: `NOT_ZIP` 「zip 파일이 아니에요.」, `SECRET_VALUE` 「비밀값처럼 보이는 글이 있어요.」, `SCRIPTS_NEED_SANDBOX` 「이 에이전트는 실행 공간이 없어 스크립트가 든 스킬을 올릴 수 없어요. 도구에서 셸을 켜 주세요.」, `NAME_TAKEN` 「같은 이름의 기본 스킬이 있어요.」, `LIMIT_REACHED` 「올릴 수 있는 스킬 수를 다 썼어요.」, `DESCRIPTION_TOO_LONG` 「설명이 너무 길어요. 새 스킬은 60자까지예요.」. 나머지 값도 모두 채운다(타입이 빠짐을 컴파일에서 잡게 `Record<SkillPackageReason, string>`)
- 「올리기」(또는 「덮어쓰기」)를 누르면 같은 `File` 과 `baseDigest` 로 올린다. 성공하면 창을 닫고 스킬 목록을 다시 읽는다(`AgentSkillsSection` 의 `reload` 를 prop 으로 받는다)
- 올리기 실패 문구: `SKILL_CHANGED` 「미리본 뒤에 스킬이 바뀌었어요. 파일을 다시 골라 주세요.」, `SKILL_SCRIPTS_NEED_SANDBOX` 는 위 `SCRIPTS_NEED_SANDBOX` 와 같은 문구, 나머지는 `describeFailure` 의 공용 문구. 창은 남고 까닭을 보인다
- 요청이 도는 동안 단추를 끄고 창이 닫히지 않는다(지우기 확인 창 본보기)
- `web/src/components/error-message.ts` 공용 표에 `SKILL_SCRIPTS_NEED_SANDBOX` 와 `SKILL_CHANGED` 문구를 더해 편집기 저장에서도 같은 까닭을 보이게 한다

### 4. `web/src/components/agent/agent-skills-section.tsx`

`editable` 일 때 「스킬 추가」 옆에 `SkillPackageUpload` 를 둔다. 성공하면 `reload()`.

### 5. 문서

- `web/docs/prd.md` 「상세의 스킬」 행에 「zip 으로 올리기」(미리보기, 덮어쓰기 확인, 문제 안내)를 더한다
- `web/docs/flow.md` 에 스킬 묶음 올리기 화면 흐름 절을 더한다. 같은 문서 안의 헤딩과 겹치지 않게 「스킬 묶음 올리기 화면」 으로 둔다(`test/unit/doc-references.test.ts` 가 같은 헤딩을 막는다)
- `docs/prd.md` 의 「아직 만들지 않은 것」 줄을 「GitHub 가져오기와 이전 버전 되돌리기 화면」 만 남게 고친다

### 6. 이 phase 를 검증하는 `test/browser/skill-package.spec.ts`

- 주인이 `SKILL.md` 와 `references/guide.md` 가 든 zip 을 고르면 미리보기에 두 파일이 「새 파일」 로 보이고, 「올리기」 를 누르면 스킬 절에 그 스킬이 「올린 스킬」 로 보인다
- 같은 이름으로 `references/guide.md` 내용을 바꾸고 `references/extra.md` 를 더한 zip 을 고르면 제목이 「덮어쓸까요?」 이고 「바뀜」, 「새 파일」 이 보이며, 「덮어쓰기」 뒤 스킬 편집 화면에 바뀐 내용이 있다
- `SKILL.md` 가 없는 zip 은 문제 문구가 보이고 올리기 단추가 꺼져 있다
- `scripts/run.sh` 가 든 zip 을 셸이 꺼진 에이전트에 고르면 실행 공간 안내 문구가 보이고 올리기 단추가 꺼져 있다. 시험 에이전트의 셸 상태는 `test/browser/fixtures.ts` 와 `test/browser/agent-tools.spec.ts` 의 도구 저장 helper 를 읽고 정한다
- 시험이 만든 스킬은 `finally` 에서 `page.request.delete` 로 지운다

## 검증

```bash
cd web && pnpm lint
cd web && pnpm exec tsc --noEmit
node scripts/check-file-length.mjs
node --test 'test/unit/**/*.test.ts'
cd web && pnpm exec playwright test --config ../test/browser/playwright.config.ts ../test/browser/skill-package.spec.ts ../test/browser/skills.spec.ts --repeat-each=3 --retries=0
```

- 모두 종료 코드 0
- 무거운 줄(`check-local.sh`, playwright)은 여러 워커가 같은 Mac 에서 돌므로 `/Users/nhn/personal/fos-assistant/.omc/scripts/heavy-lock` 으로 감싸 실행한다
- 마지막 줄은 playwright 설정이 정한 데스크톱과 모바일 두 프로젝트를 모두 돈다. 한 프로젝트만 돌면 `web/package.json` 의 `test:browser` 와 그 설정 파일을 읽고 두 프로젝트를 지정한다

## 변경 파일

| 파일 | 변경 |
|---|---|
| `web/src/app/api/agents/[code]/skill-packages/preview/route.ts` | 신규 |
| `web/src/app/api/agents/[code]/skill-packages/route.ts` | 신규 |
| `web/src/lib/forward-multipart.ts` | 신규 |
| `web/src/app/api/chat/conversations/[conversationId]/attachments/route.ts` | 수정 |
| `web/src/lib/skill.ts` | 수정 |
| `web/src/lib/agent-api.ts` | 수정 |
| `web/src/components/agent/skill-package-upload.tsx` | 신규 |
| `web/src/components/agent/agent-skills-section.tsx` | 수정 |
| `web/src/components/error-message.ts` | 수정 |
| `web/docs/prd.md` | 수정 |
| `web/docs/flow.md` | 수정 |
| `docs/prd.md` | 수정 |
| `test/browser/skill-package.spec.ts` | 신규 |
