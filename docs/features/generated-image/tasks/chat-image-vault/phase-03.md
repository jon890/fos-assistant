# Phase 03. 화면이 답 안의 그림과 크게 보기, 지시문, 보관 상태를 그린다

**Execution profile**: standard

## 목표

메시지 응답의 그림 결과물을 답 글 아래에 그림으로 그리고, 크게 보기와 내려받기, 지시문 접힌 칸, 보관 상태와 「보관」 단추, 지워진 그림의 「보관함에서 보기」, 보관함 목록의 작은 그림을 더한다.

**범위 외**: 파일 공간 개편(스펙 DECISION9), 그림 자리의 실패 표시(스펙 DECISION15).

## 컨텍스트

- 화면의 동작은 설계의 「화면」 과 스펙의 REQ2, REQ3, REQ5, REQ7, REQ8, REQ9 가 갖는다
- 응답의 `kind`, `prompt`, `vaultItemId` 는 Phase 01 이 더한다. 옛 서버 응답에는 없으므로 `kind` 가 없으면 `HTML` 로 본다
- 그림 주소는 기존 결과물 파일 주소다. 경로 조각을 `encodeURIComponent` 로 감싸는 방식은 `web/src/components/chat/artifact/artifact-panel.tsx` 를 따른다
- 크게 보기는 `web/src/components/ui/dialog.tsx` 를, 보관 상태는 `web/src/components/chat/artifact/artifact-vault-status.tsx` 를 쓴다. 직접 보관은 `web/src/lib/artifact-vault-api.ts` 의 `vaultArtifact` 다
- 화면 규칙은 `web/AGENTS.md` 와 그 문서가 가리키는 코드 규칙을 먼저 읽는다

**요구사항**: REQ1, REQ2, REQ3, REQ5, REQ7, REQ8, REQ9

**근거 문서**: `docs/features/generated-image/spec.md`, `docs/features/generated-image/design.md`

## 의도 메모

- 작은 그림 전용 사본을 만들지 않는다. 원본을 늦게 불러 작게 그린다
- 이 phase 의 마지막 작업으로 이 계획 폴더를 지운다. 구현이 끝난 계획서는 그 PR 에서 지운다(AGENTS.md)

## 작업 항목

### 1. `web/src/components/chat/artifact/generated-images.tsx`

그림 목록, 크게 보기 대화 상자(제목, 「내려받기」, 지시문, 보관 상태, 「보관」 단추), 지워진 그림의 안내와 링크를 그린다. 제목은 `web/src/lib/artifact-name.ts` 에 확장자를 떼는 함수를 더해 쓴다.

### 2. `message-bubble.tsx` 와 `message-types.ts`

결과물을 종류로 나눠 `HTML` 은 지금의 줄로, `IMAGE` 는 새 컴포넌트로 그린다. 타입에 세 칸을 더한다.

### 3. 보관함 목록

`web/src/components/files/vault-list.tsx` 의 그림 줄에 작은 그림을 그리고, 주소의 `item` 이 가리키는 줄의 미리보기를 펼친다. `item` 은 `vault-list.tsx` 가 `useSearchParams` 로 읽고 `web/src/app/files/page.tsx` 가 그 컴포넌트를 `Suspense` 로 감싼다. 그림의 이름은 확장자를 뗀 제목이다.

### 4. 테스트와 문서

`web/test/unit/artifact-name.test.ts` 에 제목 함수 사례를 더한다. `web/test/browser/generated-image.spec.ts` 가 넓은 화면과 휴대폰 폭에서 그림 두 장의 순서, 크게 보기와 내려받기 이름, 지시문 펼치기, 보관 상태, 지워진 그림의 링크, 보관함의 작은 그림을 본다. `docs/features/generated-image.md` 에 흐름과 화면을, `docs/features/workspace.md` 의 「보관한 결과물」 에 그림 줄을 적는다. 계획 폴더 `docs/features/generated-image/tasks/chat-image-vault/` 를 지운다.

## 검증

```bash
pnpm --dir web typecheck
cd web && node --test test/unit/artifact-name.test.ts
pnpm --dir web test:node
pnpm --dir web lint
pnpm --dir web format:check
cd web && HEAVY_SLOTS=2 orch slot heavy -- pnpm exec playwright test --config test/browser/playwright.config.ts test/browser/generated-image.spec.ts test/browser/artifact.spec.ts
node --test test/repo/doc-files.test.ts test/repo/doc-links.test.ts test/repo/doc-references.test.ts
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `web/src/components/chat/artifact/generated-images.tsx` | 신규 |
| `web/src/components/chat/message-bubble.tsx` | 수정 |
| `web/src/components/chat/message-types.ts` | 수정 |
| `web/src/lib/artifact-name.ts` | 수정 |
| `web/src/components/files/vault-list.tsx` | 수정 |
| `web/src/app/files/page.tsx` | 수정 |
| `web/test/unit/artifact-name.test.ts` | 수정 |
| `web/test/browser/generated-image.spec.ts` | 신규 |
| `docs/features/generated-image.md` | 수정 |
| `docs/features/workspace.md` | 수정 |
