# Phase 03. 스킬 편집 화면이 새 규칙을 저장 전에 알린다

**Execution profile**: standard

## 목표

스킬 편집 화면이 phase 01 의 규칙을 저장 요청 전에 한국어로 알린다.
새 스킬의 설명 60자, 설명 1024자, 앞머리 뒤 본문, 올린 스킬 수 한도를 본다.
한도에 닿으면 「스킬은 에이전트마다 최대 30개까지 만들 수 있어요.」 를 보인다. 숫자는 목록 응답의 `uploadLimit` 이다.

**범위 외**: 서버 검사는 phase 01 이 이미 했다. 화면은 명백한 오류만 먼저 거르고 나머지는 서버가 거절한다.
「스킬 추가」 링크를 한도에서 숨기거나 막지 않는다.

## 컨텍스트

**근거 문서**:
- `docs/code-architecture.md` 의 「스킬」 절 제한 표와 `GET /api/v1/agents/{code}/skills` 응답(`uploadLimit`)
- `docs/flow.md` 의 「스킬을 저장할 때」 의 「갈리는 지점」
- `docs/adr/ADR-034-올린-스킬은-control-plane-이-버전-디렉터리에-쓰고-hermes-는-읽기만-한다.md` 의 「저장할 수 있는 스킬은 Hermes 가 제대로 고를 수 있는 스킬이다」

지금 코드:

- `web/src/components/agent/skill-editor.tsx`
  - `FRONTMATTER = /^---[ \t]*\r?\n([\s\S]*?)\r?\n---[ \t]*(\r?\n|$)/` 가 앞머리를 찾는다. 첫 묶음이 앞머리 안쪽이다.
  - `frontmatterField(block, key)` 는 `{ value, multiline }` 을 돌려준다. 따옴표로 감싼 인라인 값은 안쪽만, 아니면 `#` 주석을 뗀 값을 `trim()` 해서 준다. 찾지 못하면 `null` 이다.
  - `checkBeforeSave(skillName)` 이 저장 전 오류 문구를 돌려준다. 앞머리 없음, name 불일치, description 빔, 파일 수, 글자 수, 합계 크기를 보고, 새 스킬이면 `fetch(\`/api/agents/${code}/skills\`)` 로 목록(`SkillListView`)을 읽어 같은 이름의 `UPLOADED` 가 있는지 본다.
  - `isNew` 는 `initial === null` 이다.
  - 한도 상수 `MAX_FILES`, `MAX_CHARS_PER_FILE`, `MAX_TOTAL_BYTES` 는 백엔드 `SkillService` 와 같은 값이고 주석이 두 곳을 함께 고치라고 한다.
- `web/src/lib/skill.ts` 의 `SkillListView = { skills, editable, skillsToolsetEnabled }`.
- `test/browser/skills.spec.ts` 의 「앞머리가 없거나 description 이 비어 있으면 저장 요청을 보내지 않고 한국어 오류를 보인다」 테스트가 `page.on("request")` 로 PUT 이 나가지 않았음을 보고 `getByRole("alert").filter({ hasText })` 로 문구를 본다. 이 방식을 따른다.
- `test/unit/skill-name.test.ts` 가 `web/src/lib/skill.ts` 를 `node --test` 로 검사하는 예다.

## 의도 메모

- 설명 글자 수는 서버와 같게 센다. 앞뒤 공백을 뺀 뒤 양 끝의 `'` 와 `"` 를 반복해 빼고, `Array.from(value).length` 로 code point 를 센다. `value.length` 는 UTF-16 단위라 이모지를 둘로 센다.
- `frontmatterField` 가 여러 줄 값(`multiline`)이면 화면은 설명 길이를 보지 않는다. 서버가 거절한다. 기존 description 빔 검사와 같은 원칙이다.
- 본문 검사는 `FRONTMATTER` 가 맞춘 부분 뒤의 글이 `trim()` 해서 비었는지로 한다.
- 개수는 이미 읽는 목록의 `source === "UPLOADED"` 수로 센다. 서버의 수(표식 없는 버전 이름 포함)와 조금 다를 수 있지만 서버가 최종으로 거절한다.
- 목록에 `uploadLimit` 이 없으면(숫자가 아니면) 개수 검사를 건너뛴다. 옛 응답을 흉내 내는 테스트 대역이 있다.
- 새 오류 코드는 없다. 서버가 `VALIDATION_FAILED` 로 거절하면 지금처럼 `SAVE_FAILURES.VALIDATION_FAILED` 문구를 보인다.

## 작업 항목

### 1. `web/src/lib/skill.ts`

- `SkillListView` 에 `uploadLimit: number` 를 더하고 주석을 단다(「올릴 수 있는 스킬 수의 한도. 새 스킬을 만들 때만 본다」).
- 상수와 함수를 더한다. 이름은 백엔드 `SkillService` 의 짝과 같다. 주석에 함께 고친다고 적는다.
  - `export const MAX_NEW_DESCRIPTION_CHARS = 60;` (백엔드 `SkillService.MAX_NEW_DESCRIPTION_CHARS`)
  - `export const MAX_DESCRIPTION_CHARS = 1024;` (백엔드 `SkillService.MAX_DESCRIPTION_CHARS`)
  - `export function indexedDescriptionLength(value: string): number` — 앞뒤 공백을 빼고 양 끝의 `'` 와 `"` 를 반복해 뺀 뒤 code point 수
  - `export function hasBodyAfterFrontmatter(rest: string): boolean` — 앞머리 뒤 글이 공백뿐이 아니면 참

### 2. `web/src/components/agent/skill-editor.tsx` 의 `checkBeforeSave`

앞머리를 찾은 뒤 아래를 더한다. 기존 순서(앞머리 없음 → name → description 빔)를 지키고 그 뒤에 둔다.

- 본문: `hasBodyAfterFrontmatter(body.slice(frontmatter[0].length))` 가 거짓이면 「SKILL.md 앞머리 아래에 스킬 본문을 적어 주세요.」
- 설명 1024자: `description` 이 여러 줄이 아니고 `Array.from(description.value).length > MAX_DESCRIPTION_CHARS` 이면 「description 은 1,024자까지 쓸 수 있어요.」
- 새 스킬의 설명 60자: `isNew` 이고 여러 줄이 아니고 `indexedDescriptionLength(description.value) > MAX_NEW_DESCRIPTION_CHARS` 이면 「새 스킬의 description 은 60자까지 쓸 수 있어요. 지금은 N자예요. 자세한 설명은 본문에 적어 주세요.」
- 개수: 새 스킬 목록을 읽은 뒤 같은 이름 검사 다음에, `typeof list.uploadLimit === "number"` 이고 `UPLOADED` 수가 `list.uploadLimit` 이상이면 「스킬은 에이전트마다 최대 ${list.uploadLimit}개까지 만들 수 있어요.」

### 3. 단위 테스트

`test/unit/skill-name.test.ts` 에 더한다(같은 `web/src/lib/skill.ts` 를 검사한다).

- `indexedDescriptionLength`: 한국어 60자는 60, 앞뒤 공백과 따옴표를 뺀다, 이모지 하나는 1
- `hasBodyAfterFrontmatter`: `"\n# 본문\n"` 은 참, `""` 과 `"\n  \n"` 은 거짓

`test/unit/skill-command.test.ts` 의 `SkillListView` 객체 두 곳에 `uploadLimit: 30` 을 더한다.

### 4. 브라우저 테스트

`test/browser/skills.spec.ts` 에 더한다. 저장 요청이 나가지 않았는지는 기존처럼 PUT 요청을 모아 본다.

- 새 스킬에 61자 설명을 쓰면 60자 문구가 보이고 PUT 이 나가지 않는다. 60자 설명은 저장되어 상세로 돌아간다. 끝나면 `removeSkill` 로 지운다
- 앞머리만 쓰면 본문 문구가 보이고 PUT 이 나가지 않는다
- 1025자 설명을 쓰면 1,024자 문구가 보이고 PUT 이 나가지 않는다
- 개수 한도: `page.route` 로 `GET /api/agents/${PERSONA_AGENT_CODE}/skills` 응답을 `uploadLimit: 30` 과 `UPLOADED` 스킬 30개로 바꾼 뒤 새 스킬을 저장하면 「스킬은 에이전트마다 최대 30개까지 만들 수 있어요.」 가 보이고 PUT 이 나가지 않는다. `route` 는 GET 만 바꾸고 나머지는 `route.fallback()` 으로 넘긴다

## 검증

```bash
# cwd: 저장소 root
node --test 'test/unit/**/*.test.ts'
cd web && pnpm typecheck
cd web && AUTH_SECRET=build-time-placeholder ASSISTANT_JWT_SECRET=build-time-placeholder CONTROL_PLANE_BASE_URL=http://build-time-placeholder AUTH_GOOGLE_ID=build-time-placeholder AUTH_GOOGLE_SECRET=build-time-placeholder pnpm build
cd web && pnpm test:browser -- skills.spec.ts
```

`pnpm build` 는 위 자리표시자 환경 변수가 없으면 `Failed to collect page data` 로 끝난다. `pnpm test:browser` 는 스스로 같은 값으로 빌드한다.
브라우저 검사를 돌리기 전에 `ps -ax | grep -E "playwright test|standalone/server.js"` 로 다른 검사가 없는지 본다.

기대값: 모두 통과. 새 브라우저 테스트 넷이 실행 목록에 있다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `web/src/lib/skill.ts` | 수정 |
| `web/src/components/agent/skill-editor.tsx` | 수정 |
| `test/unit/skill-name.test.ts` | 수정 |
| `test/unit/skill-command.test.ts` | 수정 |
| `test/browser/skills.spec.ts` | 수정 |
