# Phase 03. 답 아래의 파일 줄과 보관함 목록의 제목, 기능 문서

**Execution profile**: standard

## 목표

대화 화면이 `FILE` 종류의 결과물을 답 아래 파일 줄로 그린다. 보관함 목록이 제목과 설명을 보인다. 화면 문구의 상한을 25MB 로 맞춘다. 기능 문서를 구현과 맞춘다.

**범위 외**: 「내 파일」 목록 화면의 개편과 탭 없애기, 미리보기 형식 넓히기(PR2).

## 컨텍스트

메시지의 결과물은 `web/src/components/chat/message-bubble.tsx` 의 `ArtifactList` 가 그린다. 그림(`kind` 가 `IMAGE`)은 `GeneratedImages` 가 따로 그린다. 타입은 `web/src/components/chat/message-types.ts` 의 `MessageArtifact` 다.
phase 02 뒤 서버는 `kind: "FILE"` 항목에 `title`, `vaultItemId`, `deleted`, `path`, `byteSize` 를 준다. `FILE` 항목은 대화 폴더의 파일이 아니라 보관함의 파일이라 결과물 패널로 열지 않는다.
보관함 주소는 `web/src/lib/artifact-vault-api.ts` 의 `vaultContentUrl`, `vaultArchiveUrl` 이 만든다. 이 PR 에서 「내 파일에서 보기」 는 기존 보관함 탭 `/files?tab=vault` 로 간다.

**요구사항**: REQ3, REQ5, REQ6

**근거 문서**: `docs/features/workspace/spec.md` 의 REQ3, `docs/features/workspace/design.md` 의 「답에 묶기와 메시지 응답」, `web/AGENTS.md`

## 의도 메모

- `FILE` 항목을 HTML 결과물의 줄(보관 상태, 결과물 패널 열기)로 그리지 않는다
- 화면에 「보관함」 이라는 새 낱말을 만들지 않는다. 링크 글자는 「내 파일에서 보기」 다

## 작업 항목

### 1. `web/src/components/chat/artifact/put-files.tsx`(신규)와 `message-bubble.tsx`, `message-types.ts`

- `MessageArtifact.kind` 에 `"FILE"` 을, 필드에 `title?: string | null` 을 더한다
- `PutFiles` 는 `FILE` 항목마다 한 줄을 그린다. 제목(없으면 `artifactName(path)`), 종류 글자(확장자에서 「그림」, 「문서」, 「표」), 크기, 「내려받기」(`vaultContentUrl(vaultItemId, true)`), 「내 파일에서 보기」(`/files?tab=vault`)다. `deleted` 면 「내 파일에서 지운 파일이에요」 만 보인다. `data-testid` 는 `message-put-file` 이다
- `message-bubble.tsx` 가 `ArtifactList` 에 `FILE` 을 넘기지 않고 `PutFiles` 를 `GeneratedImages` 와 같은 자리에 그린다

### 2. 보관함 목록과 문구

- `web/src/lib/artifact-vault-api.ts`: `VaultItem` 에 `title`, `description`, `kind` 를 더하고 `VAULT_ERRORS` 의 「파일 하나 5MB」 를 25MB 로 고친다
- `web/src/components/files/vault-list.tsx`: 제목이 있으면 제목을, 없으면 지금 이름을 보인다. 설명이 있으면 이름 아래 작은 글로 보인다. 빈 상태의 설명에서 HTML 만 가리키는 말을 「대화에서 에이전트가 만든 파일」 로 넓힌다
- `web/src/lib/tool-label.ts` 에 `file_space_put` 의 이름표 「파일 공간에 넣기」 를 더하고 `web/test/unit/tool-label.test.ts` 에 사례를 더한다

### 3. 테스트

- `web/test/unit/put-files.test.ts`(신규): 종류 글자를 정하는 순수 함수(xlsx 는 「표」, pdf 는 「문서」, png 는 「그림」)와 제목이 없을 때의 이름
- `web/test/browser/artifact.spec.ts` 에 사례를 더한다. `FILE` 항목이 든 메시지 응답 대역으로 파일 줄의 제목, 내려받기 주소, 「내 파일에서 보기」, 지운 안내를 본다. 이 파일의 기존 대역 방식을 따른다

### 4. 기능 문서

- `docs/features/workspace.md` 의 「보관한 결과물」: 받는 형식과 25MiB, 넣기 도구로 들어오는 길, 제목과 설명, 답 아래 파일 줄을 더하고 「이 기능이 아직 하지 않는 것」 을 맞춘다. `covers:` 에 새 코드 경로를 더한다
- `docs/features/mcp.md`: 도구 표에 `file_space_put` 을 더하고 「파일 공간에 넣는 도구」 절(경로의 뜻, origin 실행에서 주인과 대화를 정함, 살펴보기 트리 규칙, 실패 결과)을 쓴다. `artifact_write` 의 제목 인자를 적는다
- `docs/features/attachment.md` 의 「결과물 파일」: `artifact_write` 가 제목을 받은 HTML 은 답에 묶이지 않은 행을 먼저 만든다는 것을 더한다
- `backend/docs/code-architecture.md` 에 실행 공간을 주인 키로 읽는 포트가 있다는 것을 「실행 공간 파일」 에 한 줄 더한다

## 검증

```bash
cd web && pnpm typecheck && pnpm lint
cd web && node --test test/unit/put-files.test.ts test/unit/tool-label.test.ts
cd web && pnpm exec playwright test --config test/browser/playwright.config.ts test/browser/artifact.spec.ts test/browser/generated-image.spec.ts
node --test test/repo
scripts/dev/check-local.sh --fast
```

같은 맥에서 여러 worker 가 도므로 Gradle, 브라우저 테스트, `check-local.sh` 줄은 저장소 공통 지시서의 무거운 검사 대기열(`orch slot heavy -- <명령>`)로 감싸 실행한다. 기대값은 모두 통과다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `web/src/components/chat/artifact/put-files.tsx` | 신규 |
| `web/src/components/chat/message-bubble.tsx` | 수정 |
| `web/src/components/chat/message-types.ts` | 수정 |
| `web/src/components/files/vault-list.tsx` | 수정 |
| `web/src/lib/artifact-vault-api.ts` | 수정 |
| `web/src/lib/tool-label.ts` | 수정 |
| `web/test/unit/put-files.test.ts` | 신규 |
| `web/test/unit/tool-label.test.ts` | 수정 |
| `web/test/browser/artifact.spec.ts` | 수정 |
| `docs/features/workspace.md` | 수정 |
| `docs/features/mcp.md` | 수정 |
| `docs/features/attachment.md` | 수정 |
| `backend/docs/code-architecture.md` | 수정 |
