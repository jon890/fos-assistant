# Phase 03. 임시저장 작업과 쓰기 도구

**Execution profile**: deep

## 목표

승인한 `save_draft` 가 분리된 작업 프로세스를 띄워 phase 02 의 `runDraft` 를 돌리고, `draft_job` 이 그 진행과 결과를 읽게 한다.
승인한 호출이 대시보드의 60초 안에 끝나도록 무거운 일은 작업 프로세스로 넘긴다.

**범위 외**: 편집기 단계 자체의 동작(phase 02). 발행.

## 컨텍스트

- 승인한 호출은 대시보드가 MCP 서버를 새 프로세스로 한 번 띄워 60초 안에 부른다. 끝나거나 시간을 넘기면 그 프로세스 묶음을 정리한다. 자식이 받는 env 는 연결 칸 값과 `HOME`, `LOGNAME`, `PATH`, `SHELL`, `TERM`, `USER` 뿐이다(`hermes/README.md` 의 「커넥터」). 그래서 `TMPDIR` 이 없고 임시 디렉터리는 `/tmp` 다
- 읽기 도구는 Hermes 가 쥔 MCP 연결로 불리고 제한 시간이 300초다(`docs/hermes/delegation.md`)
- 승인 줄의 결과는 Control Plane 이 대화의 자동 turn 으로 전한다. 그 turn 의 모델이 `draft_job` 을 불러 결과를 확인해야 한다
- 오류 코드의 공통 어휘 대응은 `connector.json` 의 `errors` 가 갖는다. `outcome_unknown` 은 쓰기를 보냈는데 됐는지 모른다는 뜻이다(`docs/connectors.md`)
- phase 01 의 `src/draft.ts`(`draftShape`, `validateDraft`, `checkPhotoFiles`, `parseBody`), `src/session.ts`(`sessionStatus`), phase 02 의 `src/editor/run.ts`(`runDraft`, `EditorError`) 를 쓴다

**근거 문서**: `docs/connectors/naver-blog.md` 의 「도구와 정책」, 「작업」, 「오류」. `docs/adr/ADR-092-네이버-블로그-커넥터는-사용자의-chrome-에-cdp-로-붙고-임시저장은-승인한-뒤-백그라운드-작업으로-돈다.md`.

## 의도 메모

- 플랫폼의 60초를 늘리지 않는다. 다른 커넥터의 승인 대기와 동시 실행 한도를 길게 붙잡기 때문이다(ADR-092 의 대안 기각)
- 작업 프로세스는 같은 묶음 파일을 `--worker <작업 파일>` 로 다시 실행한다. 다른 실행 파일을 두지 않는다
- 저장 단추를 누른 뒤 확인하지 못한 작업은 `unknown` 이다. 다시 저장하지 않는다
- 작업 상태 파일에는 초안 본문과 경로를 싣지 않는다. 초안 사본은 별도 입력 파일에 두고 작업이 끝나면 지운다

## 작업 항목

### 1. `src/jobs.ts`

작업 디렉터리 `/tmp/fos-naver-blog-jobs`(모드 700, 없으면 만든다. 링크면 거절)를 쓴다. 시험은 `NAVER_BLOG_JOB_DIR` env 로 바꿀 수 있다. 이 env 는 `.mcp.json` 에 넣지 않는다.

| 파일 | 내용 |
| --- | --- |
| `<job_id>.json` | `{job_id, status, stage, started_at, finished_at, result, error, pid, heartbeat_at}`. 모드 600 |
| `<job_id>.input.json` | 초안 다섯 칸과 작업에 필요한 env 두 값. 모드 600. 작업 프로세스가 읽은 뒤 바로 지운다 |
| `lock` | 돌고 있는 작업의 `{job_id, pid, created_at}`. `O_EXCL` 로 만든다 |

- `job_id` 는 `crypto.randomUUID()` 다
- 상태 파일은 임시 파일에 쓴 뒤 이름을 바꿔 바꾼다
- 잠금이 있어도 그 `pid` 가 살아 있지 않거나 15분이 지났으면 묵은 잠금으로 보고 지운다. 그 잠금의 작업이 `running` 이면 `stage` 가 `save` 일 때 `unknown`, 아니면 `failed` 와 `error.code: "timeout"` 으로 끝낸다
- 작업 디렉터리를 읽을 때마다 끝난 지 24시간이 지난 상태 파일과 남은 입력 파일을 지운다
- `status` 는 `running`, `succeeded`, `failed`, `unknown`. 작업 프로세스는 5초마다 `heartbeat_at` 을 갱신한다

### 2. `src/worker.ts`

`runWorker(jobFile)`: 입력 파일을 읽고 지운 뒤 `runDraft` 를 부른다. 단계가 바뀔 때마다 `stage` 를 쓴다. 전체 제한은 10분이고 넘기면 탭을 닫고 `timeout` 으로 끝낸다.

| `runDraft` 의 끝 | 상태 |
| --- | --- |
| 성공 | `succeeded`, `result` 는 `state` 와 저장 전후 수 |
| `EditorError` 가 `save_unconfirmed` | `unknown` |
| 그 밖의 `EditorError` | `failed`, `error` 는 `{code, stage, message, ...extra}` |
| 그 밖의 예외 | `failed`, `error.code: "editor_failed"`. 예외 원문을 싣지 않는다 |

끝나면 잠금을 지운다. 잠금의 `job_id` 가 자기 것일 때만 지운다.

`src/server.ts` 의 진입부: `process.argv` 에 `--worker <파일>` 이 있으면 MCP 서버를 띄우지 않고 `runWorker` 만 돌린 뒤 끝낸다. 프록시 env 를 빼고 다시 실행하는 기존 처리보다 뒤에 둔다.

### 3. `save_draft` 도구

인자는 `draftShape` 다섯 칸. 차례:

1. `validateDraft`, `checkPhotoFiles`. 문장이 있으면 `NAVER_BLOG_INVALID_INPUT` 이나(파일 문제면) `NAVER_BLOG_PHOTO_INVALID`. 오류 글에는 코드만 둔다
2. `sessionStatus` 로 브라우저와 로그인 쿠키. 실패는 그 코드 그대로
3. 잠금을 만든다. 살아 있는 잠금이 있으면 `NAVER_BLOG_BUSY`
4. 상태 파일(`running`, `stage: "queued"`)과 입력 파일을 쓴다
5. `node:child_process` 의 `spawn(process.execPath, [process.argv[1], "--worker", 상태 파일], {detached: true, stdio: "ignore", env: 최소 env})` 후 `unref()`. 최소 env 는 `PATH`, `HOME` 과 작업 디렉터리 env 뿐이다. 칸 값은 입력 파일로 넘긴다
6. 5초 안에 상태 파일의 `pid` 가 채워지고 `stage` 가 `queued` 가 아니게 되기를 기다린다. 되면 `{job_id, status: "running"}`. 안 되면 `NAVER_BLOG_START_UNKNOWN`
7. 띄우기 전에 실패하면 잠금과 두 파일을 지운다

`connector.json` 의 `tools` 에 `"save_draft": { "risk": "WRITE", "title": "네이버 블로그에 임시저장", "outbound": false }` 를 더한다. `grant` 는 적지 않는다(상시 허락을 줄 수 있다). `identifiers` 는 두지 않는다.

### 4. `draft_job` 도구

인자 `job_id`(UUID 모양), `wait_seconds`(정수 0~50, 기본 45). 상태 파일을 읽고, `running` 이면 1초마다 다시 읽어 끝나거나 기다린 시간이 차면 돌려준다.
`running` 인데 `pid` 가 살아 있지 않거나 `heartbeat_at` 이 30초 넘게 멈췄거나 시작한 지 10분이 지났으면 위 「묵은 잠금」 과 같이 끝내고 잠금을 푼다. 없는 작업이면 `NAVER_BLOG_JOB_NOT_FOUND`.
결과는 상태 파일에서 `pid` 와 `heartbeat_at` 을 뺀 것이다. `readOnlyHint: true`.

`connector.json`: `tools` 에 `"draft_job": { "risk": "READ" }`, `errors` 에 `"NAVER_BLOG_BUSY": "unavailable"`, `"NAVER_BLOG_JOB_NOT_FOUND": "invalid_input"`, `"NAVER_BLOG_START_UNKNOWN": "outcome_unknown"` 를 더한다.

### 5. 스킬 갱신

`skills/naver-blog/SKILL.md` 에 더한다.

- `save_draft` 는 사용자가 미리보기를 확인한 뒤에만 부른다. 부르면 승인 카드가 간다. 같은 도구를 다시 부르지 않는다
- 승인 결과로 `job_id` 를 받으면 바로 `draft_job` 을 부른다. `running` 이면 다시 부른다
- `succeeded` 면 `result` 에서 읽은 것만 완료라고 알린다. `unknown` 이면 다시 저장하지 말고 네이버 임시저장 목록을 확인해 달라고 한다
- `failed` 의 `error.code` 마다 할 일: `login_required` 와 `security_check` 는 브라우저 조작을 부탁하고 멈춘다. `category_not_found` 는 `categories` 에서 고르게 묻는다. `place_not_unique` 는 `candidates` 에서 고르게 묻고 본문의 지도 줄을 고른 상호명과 주소로 고친다. `photo_upload_failed` 와 `editor_failed` 는 `render_draft` 의 `kind: "package"` 로 수동 등록용 묶음을 만들어 보여 주고 멈춘다
- 스킬 본문은 8,000자를 넘기지 않는다

### 6. 이 phase 를 검증하는 시험

`runDraft` 는 시험에서 바꿔 끼울 수 있게 `src/worker.ts` 가 모듈 경계로 받는다(예: `runWorker(jobFile, deps = {runDraft})`).

- `tests/jobs.test.ts`: 임시 작업 디렉터리에서 잠금이 둘째 작업을 `NAVER_BLOG_BUSY` 로 막는다. 죽은 `pid` 의 잠금은 묵은 잠금으로 풀리고 그 작업이 `timeout` 으로 끝난다. `stage: "save"` 에서 멈춘 작업은 `unknown` 이다. 24시간 지난 상태 파일이 지워진다. 상태 파일에 `body` 와 `photo_dir` 문자열이 없다
- `tests/worker.test.ts`: 성공하는 가짜 `runDraft` 로 `succeeded` 와 결과, `save_unconfirmed` 로 `unknown`, `category_not_found` 로 `failed` 와 `categories`. 입력 파일이 지워지고 잠금이 풀린다
- `tests/save-draft.test.ts`: phase 01 의 가짜 CDP 서버와 임시 사진 디렉터리로 `save_draft` 를 부른다. 실제 작업 프로세스를 `bun src/server.ts --worker` 로 띄우되 시험용 env(`NAVER_BLOG_TEST_FAKE_RUN=1`)일 때만 `runDraft` 대신 즉시 성공하는 대역을 쓴다. 그 env 는 시험 밖에서 읽히지 않도록 `NAVER_BLOG_JOB_DIR` 이 함께 있을 때만 본다. 돌려받은 `job_id` 로 `draft_job` 이 `succeeded` 를 돌려준다. 로그인 쿠키가 없으면 작업을 만들지 않고 `NAVER_BLOG_LOGIN_REQUIRED`. 서명이 틀린 사진이면 `NAVER_BLOG_PHOTO_INVALID`. 결과와 오류에 CDP 주소와 사진 디렉터리가 없다
- `tests/contracts.test.ts` 는 도구 넷과 `connector.json` 이 같은지 그대로 본다

## 검증

```bash
cd hermes/connectors/naver-blog && bun install --frozen-lockfile && bun run typecheck && bun test ./src ./tests ./scripts && bun run build && bun run check:bundle
bash scripts/check-connectors.sh
python3 -m unittest discover -s hermes/tests
bash scripts/check-public-safe.sh
```

기대값: 모두 종료 코드 0. 계약 검사가 도구 넷을 찾고 `save_draft` 의 `title` 과 `outbound` 를 확인한다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `hermes/connectors/naver-blog/src/jobs.ts` | 신규 |
| `hermes/connectors/naver-blog/src/worker.ts` | 신규 |
| `hermes/connectors/naver-blog/src/server.ts` | 수정 |
| `hermes/connectors/naver-blog/connector.json` | 수정 |
| `hermes/connectors/naver-blog/skills/naver-blog/SKILL.md` | 수정 |
| `hermes/connectors/naver-blog/dist/naver-blog-mcp.js` | 수정 |
| `hermes/connectors/naver-blog/tests/jobs.test.ts` | 신규 |
| `hermes/connectors/naver-blog/tests/worker.test.ts` | 신규 |
| `hermes/connectors/naver-blog/tests/save-draft.test.ts` | 신규 |
| `hermes/connectors/naver-blog/tests/contracts.test.ts` | 수정 |
