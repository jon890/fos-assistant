# Phase 05. overwrite_draft 도구와 작업 연결, 스킬

**Execution profile**: standard

## 목표

`overwrite_draft` 를 승인이 필요한 쓰기 도구로 등록하고, 승인한 호출이 `save_draft` 와 같은 분리된 작업 프로세스로 `runOverwrite` 를 돌리게 한다. 스킬에 덮어쓰기 차례를 적는다.

**범위 외**: 편집기 흐름(phase 04 에서 끝남).

## 컨텍스트

코드는 `hermes/connectors/naver-blog/` 에 있다. `bun` 이 PATH 에 있어야 한다(없으면 `export PATH="$HOME/.bun/bin:$PATH"`).

**근거 문서**: `hermes/connectors/naver-blog/README.md` 의 「도구와 정책」 표의 `overwrite_draft` 줄, 「도구의 인자와 결과」, 「임시저장 글 덮어쓰기」 의 「덮어쓰기 도구」 와 결과 표. `hermes/docs/adr/ADR-20261009-naver-blog-overwrite.md` 의 「결정」 표.

지금 코드:

- `src/server.ts` 의 `saveDraft(env, input, deps)` 가 검사, `readConnection`, `deps.checkSession(env, {timeoutMs: 45_000})`, `openJobDir`, `acquireLock`, `createState`, `writeInput`, 작업 프로세스 띄우기, 시작 확인을 한다. `createServer` 가 도구를 등록한다
- `src/worker.ts` 의 `runWorker(jobFile, overrides)` 가 입력 파일을 `draftFrom` 으로 읽고 `deps.runDraft` 를 돌린다. `onStage("save_clicking")` 을 받으면 `save_clicked` 를 기록한다. `WorkerDeps` 는 `{runDraft, limitMs, env}` 다
- `src/overwrite-draft.ts` 의 `overwriteContentShape`, `validateOverwrite`(phase 01)
- `src/editor/overwrite.ts` 의 `runOverwrite`, `OverwriteInput`(phase 04)
- `src/editor/drafts.ts` 의 `DRAFT_ID_PATTERN`
- 시험 본보기: `tests/save-draft.test.ts` 와 그것이 쓰는 `tests/fake-worker-entry.ts`, `tests/worker.test.ts`, `tests/contracts.test.ts`

`src/server.ts` 는 306줄이고 400줄이 상한이다.

## 의도 메모

- 입력 파일에 `kind` 칸을 더한다. `kind` 가 없는 입력 파일은 새 글 저장이다. 배포하는 동안 옛 MCP 서버가 쓴 입력 파일을 새 작업 프로세스가 읽을 수 있게 하려는 것이다
- 덮어쓰기는 상시 허락을 닫는다(`"grant": false`). 사진 디렉터리를 받지 않아 승인 카드에 가려지는 인자가 없다

## 작업 항목

### 1. `connector.json` 수정

`tools` 의 `save_draft` 다음에 `"overwrite_draft": { "risk": "WRITE", "title": "네이버 블로그 임시저장 글 덮어쓰기", "outbound": false, "grant": false }` 를 더한다. 한 줄 모양은 다른 도구 줄과 같게 둔다.

### 2. `src/server.ts` 수정

- `saveDraft` 의 검사 뒤 부분(연결 읽기부터 시작 확인까지)을 `startJob(env, input: Record<string, unknown>, deps)` 로 꺼내 `saveDraft` 가 부르게 한다. 새 글 입력 파일은 지금 모양 그대로다
- `overwriteDraft(env, input, deps)`: `validateOverwrite(네 칸)` 에 문장이 있으면 `ToolError("NAVER_BLOG_INVALID_INPUT")`. 아니면 `startJob(env, {kind: "overwrite", draft_id, revision, changes, title, category, tags, body}, deps)`
- `overwrite_draft` 를 등록한다. `inputSchema`: `draft_id`(`DRAFT_ID_PATTERN`), `revision`(`/^[0-9a-f]{16}$/`), `changes`(문자열, 40,000자까지), 그리고 `overwriteContentShape` 의 네 칸. 설명: 「read_draft 로 읽고 render_draft 의 base 로 미리 본 임시저장 글을 고칩니다. 고치기 전에 원래 글을 [덮어쓰기 전 원본] 사본으로 남깁니다. 발행하지 않습니다. 결과는 draft_job 으로 읽습니다.」

### 3. `src/worker.ts` 수정

- 입력 파일을 읽는 함수가 `{kind: "save", draft}` 나 `{kind: "overwrite", input}` 을 돌려주게 한다. `kind` 가 없으면 `save` 다. `overwrite` 는 일곱 칸의 타입을 모두 확인한다. 모양이 틀리면 지금처럼 `editor_failed` 로 끝낸다
- `WorkerDeps` 에 `runOverwrite: typeof runOverwrite` 를 더하고 기본값을 넣는다
- `overwrite` 면 `deps.runOverwrite(deps.env, input, onStage, controller.signal)` 의 결과를 그대로 `result` 로 쓴다. 단계 기록과 `save_clicking` 처리, 시간 상한, 실패와 `unknown` 판정은 새 글과 같은 코드를 지난다
- `tests/fake-worker-entry.ts` 에 `fakeRunOverwrite` 를 더해 `runWorker(jobFile, {runDraft: fakeRunDraft, runOverwrite: fakeRunOverwrite})` 로 넘긴다. `open` 단계를 알리고 잠깐 기다린 뒤 `{draft_id, backup_draft_id: "1", backup_title: "[덮어쓰기 전 원본] x", saved_before: 1, saved_after: 1, state: null}` 를 돌려준다

### 4. `skills/naver-blog/SKILL.md` 수정

「임시저장 글 보기」 뒤에 「임시저장 글 고치기」 절을 더한다.

1. `read_draft` 로 그 글을 읽는다
2. 고친 네 칸과 `base`(읽은 네 칸)로 `render_draft` 를 불러 미리보기를 보인다. 미리보기 맨 위의 바뀌는 내용을 사용자에게 함께 알린다
3. 사용자가 확인하면 `overwrite_draft` 를 `draft_id`, `revision`(받은 `base_revision`), `changes`(받은 `changes`)와 고친 네 칸 그대로 부른다
4. `draft_job` 결과가 `succeeded` 면 고쳤다는 것과, 원래 글을 `backup_title` 이라는 사본으로 남겼고 확인한 뒤 네이버에서 지우면 된다는 것을 알린다
5. `draft_changed`, `changes_mismatch` 면 다시 읽어 1 부터 한다. `component_not_found` 면 본문의 기존 구성요소 줄을 읽은 그대로 둔다. `backup_failed` 면 원래 글은 그대로라고 알리고 멈춘다. `error.backup_draft_id` 가 있으면 그 사본이 남았다고 알린다

새 사진, 스티커, 지도는 덮어쓰기로 넣지 않는다는 것과, 같은 도구를 다시 부르지 않는다는 「승인과 작업 결과」 의 규칙이 덮어쓰기에도 같다는 것을 적는다.

### 5. 이 phase 를 검증하는 시험

- `tests/contracts.test.ts`: 도구 일곱이 manifest 와 같고, `overwrite_draft` 선언이 위 1 과 같다. 「READ 도구만 readOnlyHint」 시험이 그대로 통과한다
- `tests/save-draft.test.ts` 에 더한다(같은 `setup` 을 쓴다): `overwrite_draft` 가 새 사진 줄이 든 본문이면 `NAVER_BLOG_INVALID_INPUT` 이고 작업 파일이 없다. 올바른 입력이면 `{job_id, status: "running"}` 이고 `draft_job` 이 `succeeded` 와 가짜 결과를 돌려준다
- `tests/worker.test.ts` 에 더한다: `kind: "overwrite"` 입력 파일이면 `runOverwrite` 를 부르고 `runDraft` 는 부르지 않는다. `kind` 가 없는 옛 입력 파일은 `runDraft` 를 부른다. `runOverwrite` 가 `save_clicking` 을 알린 뒤 던지면 `unknown` 이고 오류에 `backup_draft_id` 가 실린다

## 검증

```bash
cd hermes/connectors/naver-blog && bun test ./tests
cd hermes/connectors/naver-blog && bun run typecheck
cd hermes/connectors/naver-blog && bun run build && bun run check:bundle
bash scripts/check-connectors.sh
node scripts/check-file-length.mjs
```

`scripts/check-connectors.sh` 는 `set -Eeuo pipefail` 아래에서 커넥터마다 의존 설치, 타입 검사, `bun run test`, 묶음 검사를 돌려 하나라도 실패하면 0 이 아닌 코드로 끝난다. Bun 이 1.3.14 가 아니면 2 로 끝난다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `hermes/connectors/naver-blog/connector.json` | 수정 |
| `hermes/connectors/naver-blog/src/server.ts` | 수정 |
| `hermes/connectors/naver-blog/src/worker.ts` | 수정 |
| `hermes/connectors/naver-blog/skills/naver-blog/SKILL.md` | 수정 |
| `hermes/connectors/naver-blog/tests/fake-worker-entry.ts` | 수정 |
| `hermes/connectors/naver-blog/tests/contracts.test.ts` | 수정 |
| `hermes/connectors/naver-blog/tests/save-draft.test.ts` | 수정 |
| `hermes/connectors/naver-blog/tests/worker.test.ts` | 수정 |
| `hermes/connectors/naver-blog/dist/naver-blog-mcp.js` | 수정 |
