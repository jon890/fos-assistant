# Phase 03. 운영 왕복 평가 스크립트와 판정 규칙을 만든다

**Execution profile**: deep

## 목표

`test/model-eval/blog/run.ts` 의 `send` 가 시나리오대로 대화를 만들어 메시지를 보내고, `score` 가 결과와 backend 로그로 실행마다 `PASS`, `FAIL`, `INCONCLUSIVE` 를 판정해 보고서를 쓴다.
판정 규칙은 네트워크 없는 순수 함수로 두고 CI 의 단위 테스트가 검사한다.

**범위 외**: 운영 접속 값, 토큰 만들기, 로그 모으기. 그것은 운영 저장소가 갖는다. 실제 운영 실행은 원격 검증 목록에 있다.

## 컨텍스트

- 시나리오, 판정 기준, 결과 분류, 환경 변수, 명령의 모양, 종료 코드, 정리 규칙은 `docs/features/naver-blog-connector.md` 의 「블로그 사진 평가」 가 갖는다. 이 phase 는 그 절과 글자까지 같게 구현한다
- 단계 사건의 칸은 `docs/features/attachment.md` 의 「본문 없는 단계 사건」 이 갖고, 로그 판독은 `test/support/media-pipeline-log.ts` 의 `parseBackendPipelineEvents` 를 그대로 쓴다. phase 01 이 `referenced` 와 `inspected` 를 받게 했다
- 데이터는 phase 02 의 `test/model-eval/blog/photos/photos.json` 과 `test/model-eval/blog/scenarios.json` 이다
- 쓰는 Control Plane API 는 모두 이미 있다. 경로 앞에 `FOS_EVAL_BASE_URL` 을 붙이고 `Authorization: Bearer <FOS_EVAL_TOKEN>` 을 싣는다

| 하는 일 | 요청 | 응답에서 읽는 것 |
| --- | --- | --- |
| 대화 만들기 | `POST /api/v1/chat/conversations` 본문 `{"agentCode"}` | `conversationId`(UUID) |
| 모델 단계 고르기 | `PUT /api/v1/chat/conversations/{id}/model-tier` 본문 `{"mode":"TIER","tier"}` | 없음 |
| 사진 올리기 | `POST /api/v1/chat/conversations/{id}/attachments` multipart 필드 `file` | `id`(첨부 번호) |
| 메시지 보내기 | `POST /api/v1/chat/messages` 본문 `{"conversationId","text","agentCode","attachmentIds"}` | `executionId`, `assistantText` |
| 메시지 읽기 | `GET /api/v1/chat/conversations/{id}/messages` | 항목의 `role`, `executionId`, `status`, `artifacts[].path`, `artifacts[].deleted` |
| 결과물 읽기 | `GET /api/v1/chat/conversations/{id}/files/{path}` | HTML 본문 |
| 실행 트리 읽기 | `GET /api/v1/usage/executions/{executionId}/tree` | `root.status`, `root.events[].toolName`, `root.events[].failed` |
| 대화 지우기 | `DELETE /api/v1/chat/conversations/{id}` | 없음 |

- 위 응답 모양은 `backend/src/main/java/com/bifos/assistant/chat/turn/presentation/ChatDtos.java` 의 `StartConversationResponse`, `AttachmentView`, `SendMessageResponse`, `MessageView`, `ArtifactView` 와 `backend/src/main/java/com/bifos/assistant/usage/tree/application/dto/ExecutionTree.java`, `ExecutionNode.java`, `ExecutionEventView.java` 가 갖는다. 구현 전에 그 정의를 다시 읽고 필드 이름을 맞춘다
- 미리보기 HTML 은 `hermes/connectors/naver-blog/src/render.ts` 의 `photoHtml` 이 `<img src="<첨부 주소>/<첨부 번호>" ...>` 로 사진을 부르고, `photo_notes` 를 사진 아래에 싣는다. 첨부 주소는 `attachments` 로 끝나는 상대 경로다
- 네이버 블로그 도구 이름은 실행 트리에서 `naver_blog` 를 담는다. 이름 규칙(`mcp__<서버>__<도구>`, 서버 이름의 `-` 는 `_`)은 `hermes/plugins/fos-ctx/hooks.py` 24줄 근처가 갖고, 커넥터 id 는 `connector.json` 의 `naver-blog` 다
- 실행 입력을 만든 루트 실행 밑에 위임한 자식 실행이 `attachment_inspect` 나 블로그 도구를 부를 수 있다. `inspected` 의 `executionId` 는 호출한 쪽의 origin 실행이다. 판정은 트리의 루트와 모든 자손 실행 번호를 모아 쓴다
- `test/tsconfig.json` 의 `include` 는 지금 `e2e/**/*.ts` 뿐이다. 타입 검사에 `model-eval/**/*.ts` 를 더한다

**근거 문서**: `docs/features/naver-blog-connector.md` 의 「블로그 사진 평가」, `docs/adr/ADR-20261020-blog-photo-eval.md`

## 의도 메모

- 판정 모델을 쓰지 않는다. 정답 글자와 도구 사건, 단계 사건으로만 판정한다
- `send` 와 `score` 를 나눈 것은 backend 로그를 실행이 모두 끝난 뒤에 한 번 모아 넘기기 위해서다. 로그를 모으는 방법은 운영 환경마다 다르므로 스크립트는 파일만 받는다
- 평가는 승인 API 를 부르지 않는다. 승인 카드가 떠도 누르지 않는다
- 네트워크 오류와 타임아웃으로 한 실행이 멈춰도 나머지 실행을 이어 간다. `runs.json` 은 실행마다 덮어써서 중간에 멈춰도 앞의 기록이 남게 한다
- 의존 패키지를 더하지 않는다. Node 내장 `fetch`, `FormData`, `Blob`, `node:fs`, `node:util` 의 `parseArgs` 만 쓴다
- 토큰과 기본 주소는 보고서와 표준 출력에 쓰지 않는다. 대화 공개 식별자와 실행 번호만 쓴다

## 작업 항목

### 1. `test/model-eval/blog/score.ts`: 판정 규칙

- 타입: `PhotoAnswer`(`id`, `accept: string[]`), `RunMessage`(`executionId: number | null`, `attachmentIds: number[]`, `photoIds: string[]`), `RunRecord`(`scenarioId`, `repeat`, `conversationId`, `messages: RunMessage[]`, `error?: string`), `RunOutcome = "PASS" | "FAIL" | "INCONCLUSIVE"`, `PhotoIssue = "참조 누락" | "조회 누락" | "판독 실패" | "원고 누락"`
- `normalize(text: string): string`: HTML 태그를 지우고 `&amp;`, `&lt;`, `&gt;`, `&quot;`, `&#39;` 를 풀고 모든 공백을 지운다. 정답 비교는 양쪽 모두 이 함수를 거친다
- `previewOf(htmls: string[], attachmentIds: number[]): string | null`: 결과물 HTML 가운데 `attachments/<번호>` 로 이 실행의 첨부를 하나라도 부르는 마지막 것을 고른다
- `scoreRun(input)`: 입력은 `run: RunRecord`, `answers: Map<string, PhotoAnswer> | null`(비공개 재현이면 `null`), `events: BackendPipelineEvent[]`, `executionIds: number[]`(마지막 메시지 실행 트리의 루트와 모든 자손 실행 번호), `status: string | null`(마지막 실행의 트리 `root.status`), `toolFailed: boolean`(트리의 모든 노드 `events` 가운데 네이버 블로그 도구 사건 중 `failed` 가 참인 것이 있다), `preview: string | null`. 반환은 `{ outcome, reasons: string[], photos: { photoId, attachmentId, issues: PhotoIssue[] }[] }` 다
  - `run.error` 가 있거나, `status` 가 `SUCCEEDED` 가 아니거나, `toolFailed` 가 참이면 `INCONCLUSIVE` 다
  - 첨부 번호로 이 대화의 사건을 하나도 찾지 못하면 `INCONCLUSIVE` 이고 `reasons` 는 `["로그에 대화 사건 없음"]` 이다. 잘못된 로그 파일이거나 잘린 로그다. 이 판정은 `preview` 검사보다 먼저 한다
  - `preview` 가 `null` 이면 `FAIL` 이고 `reasons` 는 `["미리보기 없음"]` 이다
  - 마지막 메시지 실행 트리의 루트와 모든 자손 실행 번호 집합(`executionIds: number[]`)으로 `inspected` 를, 이 대화 번호와 사건 순서로 마지막 메시지의 `referenced` 와 `embedded` 를 고른다. 대화 번호는 공개 식별자가 아니라 로그의 숫자 번호이므로, `send` 가 사진을 올린 뒤 받은 첨부 번호가 든 사건으로 이 대화의 사건을 찾는다. 다른 대화와 실행의 사건은 쓰지 않는다
  - 앞 메시지의 사진마다: 마지막 `referenced` 의 `assets` 에 없으면 `참조 누락`, `outcome` 이 `OK` 인 `inspected` 가 없으면 `조회 누락` 이다. 마지막 메시지의 사진 가운데 `embedded` 의 `missing` 에 든 사진도 `조회 누락` 을 본다
  - 모든 사진마다: `preview` 가 그 첨부 번호를 부르지 않으면 `원고 누락`, `answers` 가 있고 `normalize(preview)` 가 `accept` 의 어느 표기도 담지 않으면 `판독 실패` 다
  - 어느 사진에든 문제가 있으면 `FAIL`, 없으면 `PASS` 다. `answers` 가 `null` 이면 `reasons` 에 `정답 없음` 을 더하고 `판독 실패` 를 보지 않는다
- `scoreScenario(runs, repeat)`: 실행 수가 `repeat` 와 같고 모두 `PASS` 면 `PASS`, `FAIL` 이 하나라도 있으면 `FAIL`, 그 밖에는 `INCONCLUSIVE` 다
- `exitCode(scenarioOutcomes)`: 모두 `PASS` 면 0, `FAIL` 이 있으면 1, 그 밖에는 2 다

### 2. `test/model-eval/blog/api.ts`: Control Plane 호출

- `class ControlPlane` 이 `baseUrl`, `token`, `timeoutMs` 를 받고 위 표의 여덟 요청을 메서드 하나씩으로 연다(`startConversation`, `chooseTier`, `upload`, `send`, `messages`, `file`, `tree`, `deleteConversation`)
- `test/tsconfig.json` 이 `erasableSyntaxOnly` 이고 Node 의 타입 제거 실행이 생성자 매개변수 속성(`constructor(private x)`)을 받지 않으므로 필드를 따로 선언해 대입한다. 업로드 `Blob` 에는 `image/jpeg` 타입을 싣는다
- 요청마다 `AbortSignal.timeout(timeoutMs)` 를 건다. 2xx 가 아니면 상태 코드와 응답 본문의 `code` 만 담은 오류를 던진다. 응답 본문 전체나 토큰을 오류 문구에 넣지 않는다

### 3. `test/model-eval/blog/run.ts`: 명령

- `send --out <디렉터리> [--scenario <id>]... [--repeat <횟수>] [--scenario-file <경로>]`. `--repeat` 의 기본값은 3 이다. `--scenario-file` 이 있으면 그 파일의 시나리오 하나만 쓰고, 사진 id 대신 그 파일 옆의 상대 경로를 사진으로 쓴다
  - 환경 변수 `FOS_EVAL_BASE_URL`, `FOS_EVAL_TOKEN`, `FOS_EVAL_AGENT_CODE`, `FOS_EVAL_BLOG_CATEGORY` 가 비면 이름만 알리고 종료 코드 2 로 끝난다. `FOS_EVAL_TIER` 는 `FAST`, `BALANCED`, `DEEP` 가운데 하나이거나 비어 있어야 한다. `FOS_EVAL_TURN_TIMEOUT_MS` 의 기본값은 900000 이다
  - 실행마다 새 대화를 만들고, 단계가 있으면 고르고, 메시지마다 사진을 차례로 올린 뒤 글의 `{category}` 를 바꿔 보낸다. 실행이 끝날 때마다 `runs.json` 을 다시 쓴다. `runs.json` 의 머리에는 `version`, 시작 시각, `repeat`, 비공개 재현인지를 둔다
- `score --out <디렉터리> --log <파일> [--keep]`
  - `runs.json` 과 로그 파일을 읽고, 실행마다 메시지와 결과물, 실행 트리를 읽어(결과물은 마지막 실행의 `executionId` 와 같은 답 메시지의 `artifacts` 가운데 `deleted` 가 거짓인 것만 `file` 로 읽는다. 실행 트리는 `children` 을 따라 모든 자손의 실행 번호와 `events` 를 모은다) `scoreRun` 에 넣는다
  - `report.json` 에 실행별, 시나리오별 결과를, `report.md` 에 시나리오 표와 실패한 실행의 사진별 문제, 남긴 대화의 공개 식별자를 쓴다
  - `--keep` 이 없으면 `PASS` 인 실행의 대화를 지운다. 지우지 못하면 보고서에 적고 이어 간다
  - `exitCode` 로 끝난다

### 4. `test/tsconfig.json`

`include` 에 `model-eval/**/*.ts` 를 더한다.

### 5. 이 phase 를 검증하는 `test/unit/blog-photo-eval-score.test.ts`

사건과 HTML 은 테스트 안에서 만든다. 네트워크를 쓰지 않는다.

- `past-only` 모양의 실행에서 여섯 장 모두 `referenced` 와 `OK` 인 `inspected` 가 있고 미리보기가 여섯 장을 부르며 정답을 담으면 `PASS` 다
- 한 장의 `inspected` 가 없으면 그 사진만 `조회 누락` 인 `FAIL` 이다. `outcome` 이 `MCP_CALL_CONTEXT_INVALID` 인 `inspected` 만 있어도 `조회 누락` 이다
- 조회했지만 정답이 없으면 `판독 실패`, 미리보기가 그 사진을 부르지 않으면 `원고 누락` 이다. `referenced` 에 없으면 `참조 누락` 이다
- 미리보기가 없으면 `미리보기 없음` 하나만 적은 `FAIL` 이다
- `status` 가 `FAILED` 이거나 `toolFailed` 가 참이면 `INCONCLUSIVE` 다
- `same-message` 모양에서 `embedded` 의 `missing` 에 든 사진은 `inspected` 가 없으면 `조회 누락` 이다. `missing` 이 비면 조회를 보지 않는다
- 다른 대화나 다른 실행의 `inspected` 는 세지 않는다. 위임한 자식 실행(`executionIds` 에 든 번호)의 `inspected` 는 센다
- 로그에 이 대화의 사건이 하나도 없으면 `INCONCLUSIVE`(`로그에 대화 사건 없음`)다
- `normalize` 가 띄어쓰기, 태그, 엔티티를 지워 `가상 <b>국수</b>` 가 `가상국수` 와 맞는다
- `answers` 가 `null` 이면 `정답 없음` 을 적고 판독을 보지 않는다
- `scoreScenario` 가 셋 중 셋 `PASS` 만 `PASS` 로 보고, 실행이 둘뿐이면 `INCONCLUSIVE` 로 본다. `exitCode` 가 0, 1, 2 를 돌려준다

## 검증

```bash
node --test test/unit/blog-photo-eval-score.test.ts
node --test 'test/unit/**/*.test.ts'
cd test && ../web/node_modules/.bin/tsc -p tsconfig.json
node test/model-eval/blog/run.ts send --out "$(mktemp -d)"; test $? -eq 2
node scripts/checks/check-file-length.mjs
```

`test/unit/**` 에 `doc-code-references.test.ts` 가 들어 있다. 이 phase 에서 `docs/code-architecture.md` 와 `docs/features/naver-blog-connector.md` 에 달아 둔 `ref-ignore` 표기 세 줄을 떼고 다시 통과시킨다. `web/node_modules` 가 없으면 먼저 `pnpm --dir web install` 을 돌린다.

넷째 줄은 환경 변수 없이 돌려 종료 코드 2 로 끝나는지 본다. 나머지는 모두 종료 코드 0 이다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `test/model-eval/blog/score.ts` | 신규 |
| `test/model-eval/blog/api.ts` | 신규 |
| `test/model-eval/blog/run.ts` | 신규 |
| `test/tsconfig.json` | 수정 |
| `test/unit/blog-photo-eval-score.test.ts` | 신규 |
| `docs/code-architecture.md` | 수정 |
| `docs/features/naver-blog-connector.md` | 수정 |
