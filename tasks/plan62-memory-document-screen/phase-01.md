# Phase 01. `/memory` 화면의 문서 절

**Execution profile**: standard

## 목표

사용자가 `/memory` 화면에서 문서를 만들고, 열어 보고, 고치고, 지운다.
민감 문서의 본문은 목록에 싣지 않고 사용자가 열 때만 가져온다.

**범위 외**: 서비스 토큰 절(phase 02), 판 이력과 collection 탭.

## Blocked 조건

- `backend/src/main/java/com/bifos/assistant/memory/presentation/MemoryDocumentController.java` 가 없다 → `PHASE_BLOCKED: plan61 이 머지되지 않았다` 출력 후 종료

## 컨텍스트

- 화면은 `web/src/app/memory/page.tsx`(서버 컴포넌트)가 `callControlPlane<Memory[]>("/api/v1/memories")` 로 읽어 `web/src/components/memory/memory-list.tsx` 의 `MemoryList` 에 넘긴다. 화면의 바깥 틀 `mx-auto w-full max-w-4xl` 은 `memory-list.tsx` 에 있다. `test/unit/loading-routes.test.ts` 가 그 파일에서 틀의 폭을 읽으므로 **틀을 다른 파일로 옮기지 않는다**
- 서버 라우트의 선례는 `web/src/app/api/memories/route.ts` 와 `web/src/app/api/memories/[id]/route.ts` 다. `callControlPlane(path, { method, body })` 와 `readJsonBody(request)` 를 쓰고, 실패는 `{ code, message }` 와 그 상태로 돌려준다
- 폼과 항목의 선례는 `web/src/components/memory/memory-form.tsx` 와 `memory-item.tsx` 다. 부품은 `@/components/ui/` 의 `Button`(`loading`, `loadingText`), `Input`, `Label`, `NativeSelect`, `Textarea`, `Badge`, `Notice` 를 쓴다
- 오류 코드를 문구로 바꾸는 곳은 `web/src/components/error-message.ts` 의 `MESSAGES` 다
- Control Plane 의 경로(plan61 이 만들었다)
  - `GET /api/v1/memory-collections` → `[{key, displayName}]`
  - `GET /api/v1/memory-documents` → `[{id, collection, documentKey, title, sensitive, revision, updatedAt}]`. 본문이 없다
  - `GET /api/v1/memory-documents/{id}` → 위의 칸에 `content`
  - `POST /api/v1/memory-documents` ← `{collection, documentKey, title, content, sensitive}`
  - `PUT /api/v1/memory-documents/{id}` ← `{content, sensitive, expectedRevision}`
  - `DELETE /api/v1/memories/{id}`. 화면은 이미 있는 `/api/memories/{id}` 라우트의 `DELETE` 를 부른다
  - 거절 코드: `MEMORY_DOCUMENT_EXISTS`, `MEMORY_REVISION_CONFLICT`, `MEMORY_ENCRYPTION_UNAVAILABLE`, `VALIDATION_FAILED`, `MEMORY_NOT_FOUND`
- 브라우저 검사는 `test/browser/` 에 있다. 선례는 `test/browser/memory.spec.ts` 이고 `import { expect, setSession, test } from "./fixtures.ts";` 를 쓴다. Control Plane 은 `test/browser/fixtures.ts` 가 띄우고, 환경 변수는 그 파일의 `env: { ...process.env, ... ASSISTANT_JWT_SECRET: JWT_SECRET, ... }` 블록에서 준다. **지금은 암호화 key 를 주지 않는다**
- 검사는 `mobile`(폭 390)과 `desktop`(폭 1280) 두 project 로 돈다. 같은 데이터베이스를 함께 쓰므로 만드는 이름에 `testInfo.project.name` 을 넣어 겹치지 않게 한다

**근거 문서**: `docs/adr/ADR-056-문서는-사람이-화면에서-직접-쓰고-고친다.md`, `web/AGENTS.md` 의 「화면 문구」

## 의도 메모

- 본문을 목록과 함께 받지 않는다. 화면을 열 때마다 민감 본문이 오가지 않게 한다. 「열기」 를 눌렀을 때만 가져오고, 닫으면 화면의 상태에서 지운다
- 고칠 때 화면이 읽은 `revision` 을 `expectedRevision` 으로 보낸다. 거절되면 다시 열게 한다
- 꺼내는 방식과 범위는 화면에 두지 않는다. 문서는 늘 개인 범위이고 색인에 제목만 실린다
- 제목은 고치지 못한다. 폼에 제목 칸을 두지 않는 것이 아니라 고치기 화면에서만 뺀다

## 작업 항목

### 1. 서버 라우트

- `web/src/app/api/memory-collections/route.ts`: `GET` → `/api/v1/memory-collections`
- `web/src/app/api/memory-documents/route.ts`: `GET` → `/api/v1/memory-documents`, `POST` → 같은 경로에 본문 그대로
- `web/src/app/api/memory-documents/[id]/route.ts`: `GET`, `PUT` → `/api/v1/memory-documents/{id}`. `id` 가 숫자가 아니면 `/api/memories/[id]/route.ts` 의 `invalid()` 처럼 400 `VALIDATION_FAILED` 와 「문서 번호가 올바르지 않아요.」 를 돌려준다

### 2. `web/src/components/error-message.ts`

`MESSAGES` 에 더한다.

| 코드 | 문구 |
| --- | --- |
| `MEMORY_DOCUMENT_EXISTS` | 같은 이름의 문서가 이미 있어요. 다른 이름을 입력해 주세요. |
| `MEMORY_REVISION_CONFLICT` | 그사이 문서가 바뀌었어요. 문서를 다시 열어 주세요. |
| `MEMORY_ENCRYPTION_UNAVAILABLE` | 민감한 문서를 지금 저장하거나 열 수 없어요. 관리자에게 문의해 주세요. |

이 파일의 문구를 단언하는 `test/unit/error-message.test.ts` 가 있다. 코드 수나 목록을 세는 단언이 있으면 맞춘다.

### 3. `web/src/components/memory/document-section.tsx`

`"use client"`. `export type MemoryDocument = { id: number; collection: string; documentKey: string; title: string; sensitive: boolean; revision: number; updatedAt: string }` 와 `export type MemoryCollectionOption = { key: string; displayName: string }` 를 내보낸다.

`DocumentSection({ initialDocuments, collections })`:

- `<section>` 에 `<h2>문서</h2>` 와 설명 「길게 적어 두고 이름으로 찾는 글이에요. 직접 쓴 문서만 저장돼요.」
- 새 문서 폼(`DocumentForm`)과 문서 목록(`DocumentItem`). 문서가 없으면 「아직 문서가 없어요.」
- 만들거나 고치거나 지운 뒤 `/api/memory-documents` 를 다시 읽어 목록을 바꾼다

파일이 200줄을 넘으면 `document-form.tsx` 와 `document-item.tsx` 로 나눈다. 나누면 「변경 파일」 에 없는 파일이 생기므로 커밋 전에 계획에 빠진 파일로 보고한다.

`DocumentForm`. 제목은 「새 문서」 다.

| 칸 | 모양 |
| --- | --- |
| 영역 | `NativeSelect`. 선택지는 `collections` 의 `displayName`, 값은 `key`. 처음에는 고르지 않은 상태다 |
| 문서 이름 | `Input`, `maxLength={128}`, `pattern="[a-z0-9][a-z0-9-]*"`. 아래에 「영문 소문자, 숫자, 하이픈으로 적어 주세요.」 |
| 제목 | `Input`, `maxLength={200}` |
| 내용 | `Textarea`, `maxLength={12000}`, `rows={8}`, `className="field-sizing-fixed"` |
| 민감한 내용이에요. 암호화해서 저장해요 | 체크박스. 값은 `sensitive` |
| 문서 저장 | `Button type="submit"`. 영역을 고르기 전에는 `disabled` |

실패하면 `describeError(code, message)` 의 문구를 폼 아래에 보인다. `describeError` 의 정확한 쓰임은 `error-message.ts` 와 그것을 쓰는 기존 부품을 읽고 맞춘다.

`DocumentItem`. `<article>` 하나다.

- 머리: `<h3>` 에 제목. 민감 문서면 `<Badge>민감</Badge>`. 그 아래 작은 글씨로 `{영역 이름} · {문서 이름} · {revision}번째 판`
- 단추: 「열기」, 「지우기」
- 「열기」 를 누르면 `/api/memory-documents/{id}` 를 읽어 본문을 `<pre className="whitespace-pre-wrap ...">` 로 보이고, 단추가 「닫기」 와 「고치기」 로 바뀐다. 「닫기」 는 본문을 상태에서 지운다
- 「고치기」 는 본문을 `Textarea` 로 바꾸고 민감 체크박스와 「저장」, 「취소」 를 보인다. 「저장」 은 `PUT` 에 `{content, sensitive, expectedRevision: document.revision}` 을 보낸다. 성공하면 목록을 다시 읽는다
- 「지우기」 는 `/api/memories/{id}` 에 `DELETE` 를 보낸다. `MemoryItem` 의 지우기와 같은 흐름이다
- 실패 문구는 항목 안에 보인다

### 4. `MemoryList` 와 `page.tsx`

- `MemoryList` 에 `after?: React.ReactNode` prop 을 더하고, 틀 `<div className="mx-auto w-full max-w-4xl">` 안의 맨 끝(「나에 대해 아는 것」 절 뒤)에 그린다
- `web/src/app/memory/page.tsx`: `Promise.all` 에 `callControlPlane<MemoryDocument[]>("/api/v1/memory-documents")` 와 `callControlPlane<MemoryCollectionOption[]>("/api/v1/memory-collections")` 를 더한다. 둘 중 하나가 실패하면 문서 절만 빼고 기존 화면을 그대로 그린다. 성공하면 `after={<DocumentSection initialDocuments={...} collections={...} />}` 로 넘긴다

### 5. `test/browser/fixtures.ts` 의 환경 변수

Control Plane 을 띄우는 `env` 블록의 `ASSISTANT_JWT_SECRET` 아래에 더한다.

```ts
      // 민감 Memory 문서를 만드는 검사가 쓴다. 운영 값이 아니라 글자 0123456789abcdef0123456789abcdef 의 base64 다.
      ASSISTANT_MEMORY_ENCRYPTION_ACTIVE_KEY_ID: "test-1",
      ASSISTANT_MEMORY_ENCRYPTION_KEYS: "test-1:MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
```

### 6. 이 phase 를 검증하는 `test/browser/memory-document.spec.ts`

문서 이름은 `` `profile-${testInfo.project.name}` `` 처럼 project 마다 다르게 둔다.

| 검사 | 하는 일 | 기대 |
| --- | --- | --- |
| 민감 문서를 만들고 열고 고치고 지운다 | `/memory` 에서 영역 「신원」, 문서 이름, 제목 `지원서 공통 프로필 {project}`, 내용 `평문-표식-7391`, 민감 체크 뒤 「문서 저장」 | 제목과 「민감」 과 「1번째 판」 이 보인다. **`평문-표식-7391` 은 화면에 없다** |
| 〃 | 「열기」 | `평문-표식-7391` 이 보인다 |
| 〃 | 「고치기」 뒤 내용을 `평문-표식-8802` 로 바꿔 「저장」 | 「2번째 판」 이 보인다 |
| 〃 | 새로고침 | 문서는 목록에 있고 본문은 화면에 없다 |
| 〃 | 「지우기」 | 제목이 사라진다 |
| 같은 이름의 문서를 두 번 만들지 못한다 | 같은 영역과 이름으로 한 번 더 저장 | 「같은 이름의 문서가 이미 있어요. 다른 이름을 입력해 주세요.」 가 보인다 |
| 그사이 바뀐 문서는 다시 열게 한다 | 문서를 연 뒤 `page.request.put("/api/memory-documents/{id}", ...)` 로 판을 올리고, 화면에서 「고치기」 와 「저장」 | 「그사이 문서가 바뀌었어요. 문서를 다시 열어 주세요.」 가 보인다 |
| 문서는 기억 목록에 섞이지 않는다 | 문서를 만든 뒤 「나에 대해 아는 것」 절을 본다 | 그 제목이 그 절 안에 없다 |
| 좁은 화면에서 가로로 넘치지 않는다 | 문서를 연 채로 `document.documentElement.scrollWidth` 를 읽는다 | `page.viewportSize().width` 이하다. `memory.spec.ts` 의 같은 단언을 따른다 |

검사가 만든 문서는 검사 끝에 지운다. 뒤 검사의 목록에 남지 않게 한다.

## 검증

```bash
# cwd: 저장소 root
(cd web && pnpm lint && pnpm format:check && pnpm typecheck)
(cd web && pnpm test:browser memory-document.spec.ts memory.spec.ts)
node --test 'test/unit/**/*.test.ts'
scripts/check-public-safe.sh
! grep -rn 'style={{' web/src/components/memory
! grep -rn 'localStorage\|sessionStorage' web/src/components/memory
```

- 모두 종료 코드 0. 마지막 두 줄은 일치하는 줄이 없어야 한다
- `pnpm test:browser` 는 웹을 먼저 빌드한다. 처음 받은 checkout 이면 `(cd web && pnpm install && pnpm exec playwright install chromium)` 을 먼저 돌린다
- 브라우저 검사는 `mobile` 과 `desktop` 두 project 가 모두 통과해야 한다
- 전체 브라우저 검사는 로컬에서 돌리지 않는다. PR 의 CI `browser-mobile` 과 `browser-desktop` 이 통과한 것을 확인으로 본다

## 변경 파일

| 파일 | 변경 |
|---|---|
| `web/src/app/api/memory-collections/route.ts` | 신규 |
| `web/src/app/api/memory-documents/route.ts` | 신규 |
| `web/src/app/api/memory-documents/[id]/route.ts` | 신규 |
| `web/src/components/error-message.ts` | 수정 |
| `web/src/components/memory/document-section.tsx` | 신규 |
| `web/src/components/memory/memory-list.tsx` | 수정 |
| `web/src/app/memory/page.tsx` | 수정 |
| `test/browser/fixtures.ts` | 수정 |
| `test/browser/memory-document.spec.ts` | 신규 |
