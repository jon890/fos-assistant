# Phase 03. `/memory` 화면의 「기존 기록 가져오기」 절

**Execution profile**: standard

## 목표

주인이 `/memory` 화면에서 묶음 파일을 골라 대조 결과를 보고, 확인하면 가져온다.
화면은 묶음의 본문을 그리지 않고, 묶음을 화면의 상태 밖에 두지 않는다.

**범위 외**: e2e 와 문서(phase 04), 신원 항목(phase 05).

## Blocked 조건

- `web/src/components/memory/document-section.tsx` 가 없다 → `PHASE_BLOCKED: plan62 가 머지되지 않았다` 출력 후 종료

## 컨텍스트

- `web/src/app/memory/page.tsx`(서버 컴포넌트)가 `MemoryList` 의 `after` prop 에 `DocumentSection` 과 `ServiceTokenPanel` 을 넘긴다. 틀 `mx-auto w-full max-w-4xl` 은 `web/src/components/memory/memory-list.tsx` 에 있고 `test/unit/loading-routes.test.ts` 가 그 파일에서 폭을 읽는다. **틀을 옮기지 않는다**
- 서버 라우트의 선례는 `web/src/app/api/memory-documents/route.ts` 다. `callControlPlane(path, { method, body })` 와 `readJsonBody(request)` 를 쓰고 실패는 `{ code, message }` 와 그 상태로 돌려준다. 브라우저는 Control Plane 을 직접 부르지 않는다
- 오류 코드를 문구로 바꾸는 곳은 `web/src/components/error-message.ts` 의 `MESSAGES` 다
- `web/src/lib/` 의 순수 함수는 `test/unit/` 의 `node --test` 가 검사한다. 그 파일은 `@/` 별칭과 React 를 import 하지 않는다. 선례는 `web/src/lib/service-token.ts` 와 `test/unit/service-token.test.ts` 다
- Control Plane 의 경로(phase 02 가 만들었다)
  - `POST /api/v1/memory-imports/preview` ← `{schemaVersion: 1, items: [{sourceRef, sourceDate, collection, entryType, documentKey, title, content, sensitive, retrieval}]}`. 저장하지 않는다
  - `POST /api/v1/memory-imports` ← 같은 본문. `NEW` 만 저장한다
  - 응답은 둘 다 `{newCount, duplicateCount, conflictCount, rejectedCount, items: [{index, status, reason, memoryId}]}`. `status` 는 `NEW`, `DUPLICATE`, `CONFLICT`, `REJECTED`
  - 거절: 400 `VALIDATION_FAILED`(항목이 없다, 100개를 넘는다, `schemaVersion` 이 1 이 아니다), 409 `MEMORY_ENCRYPTION_UNAVAILABLE`, 409 `MEMORY_IMPORT_RETRY`
- 묶음은 주인의 기기에서 `scripts/brain-import/bundle.ts` 가 만든 JSON 파일이다. 민감 본문을 평문으로 담는다
- 브라우저 검사의 선례는 `test/browser/memory-document.spec.ts` 다. `mobile` 과 `desktop` 두 project 가 같은 데이터베이스를 쓰므로 만드는 이름에 `testInfo.project.name` 을 넣는다. 암호화 key 는 `test/browser/fixtures.ts` 가 이미 준다

**근거 문서**: `docs/adr/ADR-058-기존-개인-지식-저장소는-주인이-검토한-묶음을-화면에서-올려-들여온다.md`, `web/AGENTS.md` 의 「화면 문구」

## 의도 메모

- 묶음을 `localStorage`, `sessionStorage`, 주소에 두지 않는다. 화면의 상태에만 두고, 가져오기가 끝나거나 「취소」 를 누르면 지운다
- 미리보기는 제목, 영역, 종류, 결과만 보인다. 본문을 그리지 않는다. 화면을 옆에서 봐도 민감 본문이 보이지 않는다
- 「가져오기」 단추는 미리보기를 본 뒤에만 눌린다. 올리자마자 저장하지 않는다
- 큰 묶음은 웹 서버 라우트가 먼저 거절한다. Control Plane 까지 보내지 않는다

## 작업 항목

### 1. `web/src/lib/memory-import.ts`

```ts
export type ImportBundleItem = {
  sourceRef: string; sourceDate: string | null; collection: string; entryType: string;
  documentKey: string | null; title: string; content: string; sensitive: boolean; retrieval: string;
};
export type ImportBundle = { schemaVersion: 1; items: ImportBundleItem[] };
export type ParsedBundle = { ok: true; bundle: ImportBundle } | { ok: false; reason: "NOT_JSON" | "WRONG_SHAPE" | "TOO_MANY" | "EMPTY" };

export const MAX_IMPORT_ITEMS = 100;
export const MAX_IMPORT_BYTES = 2 * 1024 * 1024;

export function parseBundle(text: string): ParsedBundle
```

`parseBundle` 은 JSON 이 아니면 `NOT_JSON`, `schemaVersion` 이 1 이 아니거나 `items` 가 배열이 아니거나 항목에 `sourceRef`, `title`, `content` 글이 없으면 `WRONG_SHAPE`, 항목이 없으면 `EMPTY`, 100개를 넘으면 `TOO_MANY` 다. 통과하면 위 타입의 칸만 골라 담는다. 묶음에 있던 다른 칸(`createdAt`)은 버린다.

### 2. `test/unit/memory-import.test.ts`

| 입력 | 기대 |
| --- | --- |
| `"{"` | `NOT_JSON` |
| `schemaVersion` 이 2 | `WRONG_SHAPE` |
| `items` 가 빈 배열 | `EMPTY` |
| 항목 101개 | `TOO_MANY` |
| `content` 가 없는 항목 | `WRONG_SHAPE` |
| 맞는 묶음에 `createdAt` 과 모르는 칸이 있다 | `ok` 이고 결과에 그 칸이 없다 |

### 3. 서버 라우트

- `web/src/app/api/memory-imports/preview/route.ts`: `POST` → `/api/v1/memory-imports/preview`
- `web/src/app/api/memory-imports/route.ts`: `POST` → `/api/v1/memory-imports`

둘 다 `Content-Length` 가 `MAX_IMPORT_BYTES` 를 넘거나 읽은 본문의 바이트 수가 넘으면 Control Plane 을 부르지 않고 413 과 `{ code: "MEMORY_IMPORT_TOO_LARGE", message: "가져올 파일이 너무 커요. 나눠서 올려 주세요." }` 를 돌려준다. 응답에 `Cache-Control: no-store` 를 붙인다. 두 라우트가 같은 검사를 쓰면 `web/src/lib/memory-import.ts` 가 아닌 라우트 옆의 함수로 둔다. `lib` 의 그 파일은 `Request` 를 모른다.

### 4. `web/src/components/error-message.ts`

| 코드 | 문구 |
| --- | --- |
| `MEMORY_IMPORT_RETRY` | 가져오는 사이에 기록이 바뀌었어요. 파일을 다시 올려 주세요. |
| `MEMORY_IMPORT_TOO_LARGE` | 가져올 파일이 너무 커요. 나눠서 올려 주세요. |

### 5. `web/src/components/memory/import-section.tsx`

`"use client"`. `ImportSection({ collections })`. `collections` 는 `MemoryCollectionOption[]` 이고 영역 이름을 보이는 데만 쓴다.

- `<section>` 에 `<h2>기존 기록 가져오기</h2>` 와 설명 「다른 곳에 적어 둔 기록을 검토한 파일로 한 번에 가져와요. 가져온 뒤에는 파일을 지워 주세요.」
- 파일 고르기: `<input type="file" accept="application/json,.json">`. 고르면 `file.text()` 를 `parseBundle` 에 넘긴다. 실패하면 까닭마다 문구를 보인다: `NOT_JSON` 과 `WRONG_SHAPE` 는 「가져올 수 있는 파일이 아니에요.」, `EMPTY` 는 「가져올 항목이 없어요.」, `TOO_MANY` 는 「한 번에 100개까지 가져올 수 있어요. 나눠서 올려 주세요.」
- 통과하면 곧 `/api/memory-imports/preview` 를 부른다. 그동안 「확인하는 중이에요」 를 보인다
- 미리보기: 「새로 가져와요 N개 · 이미 가져왔어요 N개 · 같은 이름이 있어요 N개 · 가져올 수 없어요 N개」 한 줄과 항목 목록. 항목마다 제목, 영역 이름(`displayName`. 목록에 없으면 key), 종류(`기억`, `문서`, `원문`), 민감이면 `<Badge>민감</Badge>`, 결과의 말. `REJECTED` 와 `CONFLICT` 는 아래 표의 까닭을 덧붙인다. **본문은 그리지 않는다**
- 단추: 「N개 가져오기」(`NEW` 가 0 이면 `disabled`)와 「취소」. 「가져오기」 는 `/api/memory-imports` 에 같은 묶음을 보낸다. 성공하면 「N개를 가져왔어요. 올린 파일은 이제 지워 주세요.」 를 `Notice` 로 보이고 묶음과 미리보기를 상태에서 지운 뒤 `router.refresh()` 로 목록을 다시 읽는다. 「취소」 도 상태를 지운다
- 실패는 `describeError(code, message)` 의 문구를 절 안에 보인다

| `reason` | 덧붙이는 말 |
| --- | --- |
| `DOCUMENT_KEY_TAKEN` | 같은 이름의 문서가 이미 있어요 |
| `TITLE_TAKEN` | 같은 제목의 기억이 이미 있어요 |
| `IDENTITY_HELD` | 신원 기록은 아직 가져올 수 없어요 |
| `UNKNOWN_COLLECTION` | 없는 영역이에요 |
| `CONTENT_TOO_LONG` | 내용이 너무 길어요 |
| 그 밖 | 항목의 형식이 맞지 않아요 |

파일이 200줄을 넘으면 미리보기 목록을 `import-preview.tsx` 로 나눈다. 나누면 「변경 파일」 에 없는 파일이 생기므로 커밋 전에 계획에 빠진 파일로 보고한다.

### 6. `web/src/app/memory/page.tsx`

`after` 에 `ServiceTokenPanel` 다음으로 `<ImportSection collections={...} />` 를 넘긴다. collection 목록을 읽지 못해 문서 절을 뺀 경우에는 이 절도 뺀다.

### 7. 이 phase 를 검증하는 `test/browser/memory-import.spec.ts`

묶음은 검사 안에서 객체로 만들어 `page.setInputFiles` 에 `{ name: "bundle-001.json", mimeType: "application/json", buffer: Buffer.from(JSON.stringify(bundle)) }` 로 넘긴다. 출처와 이름에 `testInfo.project.name` 을 넣는다.

| 검사 | 하는 일 | 기대 |
| --- | --- | --- |
| 묶음을 올리면 미리보기가 보이고 본문은 보이지 않는다 | 일반 `MEMORY` 하나(본문 `평문-표식-7391`)와 민감 `DOCUMENT` 하나를 담은 묶음을 고른다 | 「새로 가져와요 2개」 와 두 제목과 「민감」 이 보인다. **`평문-표식-7391` 은 화면에 없다** |
| 가져오면 목록에 생긴다 | 「2개 가져오기」 | 「2개를 가져왔어요」 가 보인다. 「나에 대해 아는 것」 에 그 기억의 제목이, 「문서」 에 그 문서의 제목이 보인다 |
| 같은 묶음을 다시 올리면 가져올 것이 없다 | 같은 파일을 다시 고른다 | 「이미 가져왔어요 2개」 가 보이고 「가져오기」 단추가 `disabled` 다 |
| 같은 이름의 문서와 부딪히면 가져오지 않는다 | 화면에서 문서를 만든 뒤, 출처가 다른 같은 영역과 이름의 `DOCUMENT` 를 올린다 | 「같은 이름의 문서가 이미 있어요」 가 보이고 「가져오기」 단추가 `disabled` 다 |
| 신원 기록은 아직 가져올 수 없다 | `collection` 이 `identity` 인 민감 문서를 올린다 | 「신원 기록은 아직 가져올 수 없어요」 가 보인다 |
| 묶음이 아닌 파일을 거절한다 | `{"hello": 1}` 을 올린다 | 「가져올 수 있는 파일이 아니에요.」 가 보인다. `/api/memory-imports/preview` 요청이 나가지 않는다 |
| 취소하면 미리보기가 사라진다 | 올린 뒤 「취소」 | 제목이 화면에 없다 |
| 좁은 화면에서 가로로 넘치지 않는다 | 미리보기를 연 채로 `document.documentElement.scrollWidth` 를 읽는다 | `page.viewportSize().width` 이하다 |

검사가 만든 항목은 끝에 `/api/memories/{id}` 의 `DELETE` 로 지운다.

## 검증

```bash
# cwd: 저장소 root
node --test test/unit/memory-import.test.ts
node --test 'test/unit/**/*.test.ts'
(cd web && pnpm lint && pnpm format:check && pnpm typecheck)
(cd web && pnpm test:browser memory-import.spec.ts memory-document.spec.ts memory.spec.ts)
scripts/check-public-safe.sh
! grep -rn 'style={{' web/src/components/memory
! grep -rn 'localStorage\|sessionStorage' web/src/components/memory
```

- 모두 종료 코드 0. 마지막 두 줄은 일치하는 줄이 없어야 한다
- 브라우저 검사는 `mobile` 과 `desktop` 두 project 가 모두 통과해야 한다
- 전체 브라우저 검사는 로컬에서 돌리지 않는다. PR 의 CI `browser-mobile` 과 `browser-desktop` 이 통과한 것을 확인으로 본다

## 변경 파일

| 파일 | 변경 |
|---|---|
| `web/src/lib/memory-import.ts` | 신규 |
| `test/unit/memory-import.test.ts` | 신규 |
| `web/src/app/api/memory-imports/preview/route.ts` | 신규 |
| `web/src/app/api/memory-imports/route.ts` | 신규 |
| `web/src/components/error-message.ts` | 수정 |
| `web/src/components/memory/import-section.tsx` | 신규 |
| `web/src/app/memory/page.tsx` | 수정 |
| `test/browser/memory-import.spec.ts` | 신규 |
