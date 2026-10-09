# Phase 01. backend/docs/flow.md 를 flow/ 아래 기능별 파일 10개로 옮긴다

**Execution profile**: standard

## 목표

`backend/docs/flow.md`(약 6,100줄)의 `##` 절을 내용을 바꾸지 않고 `backend/docs/flow/` 아래 파일 10개로 옮기고, 저장소 전체의 참조와 문서 검사를 새 자리에 맞춘다.
내용을 줄이는 일은 다음 phase 들이 파일 묶음마다 따로 한다. 이 phase 의 diff 는 옮김과 경로 수정만 보여야 리뷰어가 줄인 내용과 옮긴 내용을 구분한다.

**범위 외**: 절 본문을 고치거나 줄이는 것(phase 02~04), `backend/docs/data-schema.md` 를 줄이는 것(phase 05).
`AGENTS.md` 계열은 링크만 고친다. 본문 문장은 다른 작업이 읽고 있어 바꾸지 않는다.

## 컨텍스트

**근거 문서**: `docs/adr/ADR-20261009-docs-per-module.md`, `docs/adr/INDEX.md` 의 「작성과 정렬 규칙」 과 「둘 곳」

- 지금은 모듈마다 `docs/` 바로 아래 `prd.md`, `flow.md`, `code-architecture.md`, `data-schema.md` 만 둔다. `docs-per-module` ADR 의 「대안 기각」 이 `flow/` 디렉터리를 기각했고, 이번에 사용자가 backend 의 flow 만 폴더로 나누기로 정했다. 이 결정을 새 ADR 로 남긴다.
- 코드 주석 약 166곳(Java 158 파일)이 `{@code backend/docs/flow.md} 의 「절 이름」` 으로 문서를 가리킨다. `test/unit/doc-references.test.ts` 가 그 파일과 절이 있는지 본다. 절 이름은 그대로 두므로 경로만 바꾸면 된다.
- 문서 안의 링크와 「」 절은 `test/unit/doc-links.test.ts` 가, 문서가 백틱으로 적은 저장소 경로는 `test/unit/doc-code-references.test.ts` 가 본다. 옛 경로 `backend/docs/flow.md` 를 백틱으로 남기면 실패한다.
- 루트 `docs/flow.md` 와 `web/docs/flow.md` 는 그대로 둔다. 바꿀 것은 `backend/docs/flow.md` 를 가리키는 참조뿐이다. 문자열 `docs/flow.md` 가 루트 문서를 가리키는 줄을 건드리지 않게 왼쪽 경계를 확인한다.

### 나누는 표

절 이름(`## …`)은 바꾸지 않고, 원래 순서대로 옮긴다.

| 새 파일 | 제목(`#` 줄) | 옮길 `##` 절 |
| --- | --- | --- |
| `backend/docs/flow/chat.md` | `# 대화와 실행` | 대화와 실행 사건, 대기열과 중지, 사용자 실행 한도 |
| `backend/docs/flow/files.md` | `# 사진 첨부와 결과물 파일` | 사진 첨부, 결과물 파일 |
| `backend/docs/flow/mcp.md` | `# MCP 요청자` | MCP 요청자 |
| `backend/docs/flow/model.md` | `# 모델 단계와 실행 기록` | 모델 단계와 실행 기록 |
| `backend/docs/flow/agent.md` | `# 에이전트와 스킬` | 에이전트, 다른 에이전트에게 맡기기, 스킬 |
| `backend/docs/flow/user.md` | `# 사용자 추가와 사용자 브라우저` | 사용자를 더할 때, 사용자 브라우저 |
| `backend/docs/flow/connector.md` | `# 커넥터` | 커넥터 설치, 커넥터 도구 정책, 커넥터 연결 API, 커넥터 승인 |
| `backend/docs/flow/memory.md` | `# Memory` | Memory, 문맥 묶음, Memory 회수 측정 |
| `backend/docs/flow/attention.md` | `# 먼저 알리기와 알림, 예약 작업` | 먼저 알리기와 지금 화면의 판정, 할 일, 알림, 예약 작업 |
| `backend/docs/flow/proactive.md` | `# 먼저 살펴보기` | 먼저 살펴보기, 매일 루프, 문제 후보의 가치 평가, 행동 정책, 판단 피드백, 먼저 살펴보기 루프 평가 |

각 파일 머리는 제목 다음에 두 줄을 둔다.
첫 줄은 이 파일이 갖는 기능을 한 문장으로 적는다. 둘째 줄은 원래 `flow.md` 머리의 둘째 문단을 새 자리에 맞춘 것이다.
「표와 칸은 [`backend/docs/data-schema.md`](../data-schema.md), 패키지와 층은 [`backend/docs/code-architecture.md`](../code-architecture.md) 가 갖는다.」
원래 머리의 「`##` 하나가 기능 하나다…」 문장과 네 줄짜리 절 목차는 옮기지 않는다. 목차는 위 표가 대신하고, 표는 새 ADR 이 갖는다.

## 의도 메모

- 목차 파일(`flow/README.md`, `flow/index.md`)은 만들지 않는다. 정해진 이름 밖의 파일이 다시 생긴다. 폴더 링크와 새 ADR 의 표로 찾는다.
- 절을 옮기며 문장을 다듬지 않는다. 다듬으면 옮김 diff 에 섞여 리뷰어가 무엇이 바뀌었는지 보지 못한다. 고치는 것은 상대 링크와 `backend/docs/flow.md` 참조뿐이다.
- 절 이름이 그대로라 코드 주석은 경로만 바뀐다. 주석의 줄이 길어져 Checkstyle 줄 길이를 넘으면 그 주석만 줄바꿈한다.
- 「flow 는 파일 하나 또는 flow/ 폴더」 규칙은 모든 모듈에 같게 건다. 한 모듈에 둘이 함께 있으면 실패한다.

## 작업 항목

### 1. 절 옮기기

1. `backend/docs/flow.md` 를 코드 펜스를 건너뛰며 `## ` 줄에서 자른다. 위 표대로 10개 파일에 원래 순서로 붙인다. 원래 파일은 `git rm` 한다.
   - 자른 뒤 표에 없는 `##` 절이 남거나 두 번 쓰인 절이 있으면 멈추고 `PHASE_BLOCKED: 나누는 표에 없는 절 <이름>` 을 낸다(다른 브랜치가 절을 더했을 수 있다).
2. 옮긴 본문의 상대 링크를 한 단계 깊은 자리에 맞춘다.
   - `](adr/` → `](../adr/`, `](data-schema.md` → `](../data-schema.md`, `](code-architecture.md` → `](../code-architecture.md`, `](../../docs/` → `](../../../docs/`, `](../../web/` → `](../../../web/`, `](../../hermes/` → `](../../../hermes/` 처럼 `backend/docs/` 를 기준으로 쓴 상대 링크를 모두 고친다. 고친 뒤 `doc-links` 시험으로 확인한다.
   - `](flow.md)` 처럼 flow.md 자신을 가리키는 링크는 뒤에 붙은 「절」 이 옮겨 간 파일로 바꾼다. 같은 파일이면 `](<파일>.md)` 이 아니라 그 파일 이름을 그대로 쓴다(`](chat.md)`).
3. 「절」 → 새 파일 대응표를 스크립트로 만든다. `##`, `###`, `####` 헤딩과 굵은 항목 이름(`test/unit/markdown.ts` 의 `sectionNames` 가 읽는 것)을 그것을 담은 새 파일에 대응시킨다. 아래 4, 5 의 바꾸기가 이 표를 쓴다. 스크립트는 저장소에 넣지 않고 scratchpad 에 둔다.

### 2. 저장소 전체의 참조 고치기

4. `git grep -n "backend/docs/flow\.md"` 와, `backend/docs/` 의 다른 문서에서 `](flow.md` 로 건 링크를 모두 찾는다. 파일마다 아래처럼 바꾼다.
   - 뒤에 「절」 이 붙은 참조: 대응표의 새 파일로 바꾼다. 「A」, 「B」 처럼 이어 적은 절이 서로 다른 파일로 갔으면 문장을 나눠 각 파일을 적는다.
   - 절 없는 참조: 앞뒤 문맥(그 Java 클래스의 패키지, 문서 절의 주제)으로 파일 하나를 고른다. 고를 수 없으면 폴더 `backend/docs/flow/` 로 쓴다(백틱 경로는 끝에 `/` 를 붙여 디렉터리로 검사된다).
   - Markdown 링크는 그 파일 자리 기준 상대 경로로 고친다. 링크 글자의 `` `backend/docs/flow.md` `` 도 새 경로로 바꾼다.
   - 대상: Java 주석, `.github/workflows/*.txt` 프롬프트, `README.md`, `README.ko.md`, `AGENTS.md`, `backend/AGENTS.md`, `web/AGENTS.md`, `hermes/**/README.md`, 모든 모듈의 `docs/**` 와 `docs/adr/**`.
   - 예외: `test/unit/` 의 시험이 예시 문자열로 쓴 `backend/docs/flow.md` 는 그대로 둔다(`doc-references.test.ts` 의 `PATH_FIXTURE_FILES`, 파서 시험의 입력).
5. `backend/AGENTS.md` 의 「기능의 흐름」 링크는 폴더로 바꾼다: `` [`backend/docs/flow/`](docs/flow/) ``.
6. `backend/docs/code-architecture.md` 와 `backend/docs/data-schema.md` 머리의 flow 안내 문장을 폴더로 바꾼다.

### 3. 문서 검사를 flow/ 폴더로 넓힌다

7. `test/unit/doc-files.test.ts`
   - `problemOf`: `<모듈>/docs/flow/<이름>.md`(하위 디렉터리 없이 한 단)을 통과시킨다. 그 밖의 `docs/` 하위 디렉터리는 지금처럼 실패한다.
   - 새 시험 함수: 한 모듈(루트 포함)에 `docs/flow.md` 와 `docs/flow/` 가 함께 있으면 실패한다. 판정 함수 `flowLayoutProblems(files: string[]): string[]` 를 export 하고, 실제 저장소 파일 목록과 고정 입력 두 가지로 시험한다.
   - 기존 시험 「모듈 docs 의 정해진 이름과 예외는 통과하고…」 에 `backend/docs/flow/chat.md` 통과, `backend/docs/flow/x/y.md` 와 `backend/docs/memory/x.md` 실패를 더한다.
   - 고정 입력 시험: `["backend/docs/flow.md", "backend/docs/flow/chat.md"]` 는 문제 하나, `["backend/docs/flow/chat.md", "web/docs/flow.md"]` 는 문제 없음.
8. `test/unit/doc-code-references.test.ts` 의 `isTargetDocument` 가 `<모듈>/docs/flow/<이름>.md` 도 대상으로 본다. 「검사 대상은 docs 바로 아래 문서와…」 시험에 `backend/docs/flow/chat.md` 가 `true` 인 단언을 더한다.
9. `test/unit/doc-references.test.ts` 의 `UNIQUE_HEADING_DIRECTORIES` 에 `"backend/docs/flow"` 를 더한다.
10. `scripts/check-file-length.mjs`: `flow/` 아래 문서의 한도를 500줄로 두고, 넘으면 다른 모듈 문서와 같이 `알림:` 만 낸다(실패 아님). 머리 주석의 ADR 언급에 새 ADR 을 더한다.
    `test/unit/file-length.test.ts` 에 `limitFor("backend/docs/flow/chat.md") === 500` 과, 501줄 flow 파일이 실패가 아니라 알림을 내는 시험을 더한다. 기존 1000줄 단언은 그대로 둔다.

### 4. 결정 기록

11. 새 ADR `docs/adr/ADR-20261009-flow-folder.md` 를 만든다. 제목 머리 `## ADR-20261009 / flow-folder: backend 의 flow 는 기능별 파일을 담은 flow/ 폴더로 둔다`, status `accepted`, Date 2026-10-09.
    - 결정: 모듈의 flow 는 `flow.md` 하나 또는 `flow/` 폴더 하나다. 폴더 안 파일은 비슷한 기능을 묶은 것이고 한 파일 500줄 알림. 지금 폴더를 쓰는 모듈은 backend 하나이고 위 「나누는 표」 의 파일과 절을 적는다(이 표가 목차다).
    - 맥락: `docs-per-module` 의 「감당할 것」 이 남긴 약 5,900줄 한 파일. 코드 복사본을 지워도 기능 수가 많아 한 파일로는 찾고 고치기 어렵다. 같은 날 사용자가 기능별로 나누고 줄이기로 정했다.
    - 대체된 부분: `docs-per-module` 의 「대안 기각」 중 `flow/` 디렉터리 항목. 그 대안이 걱정한 30여 개 구조가 다시 생기는 것은 파일 수를 기능 묶음 10개 안팎으로 두고, 새 기능은 기존 파일의 절로 더해 막는다.
    - 대안 기각: 한 파일 유지(위 맥락), 기능마다 파일 하나(30개 가까이로 돌아간다).
    - 감당할 것: 코드 주석과 문서가 파일 이름까지 적어야 한다. 기능을 다른 파일로 옮기면 참조도 함께 고친다(`doc-references` 시험이 잡는다).
12. `docs/adr/ADR-20261009-docs-per-module.md`: 결정 바로 아래(「결정」 절 첫머리)에 `대체된 부분` 한 줄을 두어 새 ADR 을 링크한다. 「감당할 것」 의 「약 5,900줄」 문단은 그대로 둔다(당시 맥락).
13. `docs/adr/INDEX.md` 의 결정 목록에 새 ADR 을 날짜와 슬러그 순서대로 더하고, `docs-per-module` 줄의 상태 칸에 「flow 를 한 파일로 둔다는 부분은 ADR-20261009 / flow-folder 가 대체한다」 를 붙인다. `test/unit/adr-index.test.ts` 가 순서와 상태를 본다.

### 5. 이 phase 를 검증하는 시험

14. 위 7~10 의 시험이 새 규칙의 통과 입력과 실패 입력을 모두 단언한다.

## 검증

```bash
node --test test/unit/doc-files.test.ts test/unit/doc-code-references.test.ts test/unit/doc-references.test.ts test/unit/doc-links.test.ts test/unit/file-length.test.ts test/unit/adr-index.test.ts
node --test 'test/unit/**/*.test.ts'
node scripts/check-file-length.mjs
test ! -e backend/docs/flow.md
test "$(git grep -n 'backend/docs/flow\.md' -- ':!test/unit/' | wc -l | tr -d ' ')" = "0"
cd backend && ./gradlew checkstyleMain --quiet
```

- 첫 줄과 둘째 줄은 종료 코드 0 이다. 셋째 줄은 실패 없이 끝난다(알림은 있어도 된다).
- 줄 수 대조: 새 10개 파일의 합계는 원래 `flow.md` 줄 수에서 지운 머리(약 10줄)를 빼고 새 머리(파일마다 약 4줄)를 더한 값과 맞아야 한다. 커밋 메시지 본문에 원래 줄 수와 파일별 줄 수를 적는다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/docs/flow.md` | 삭제 |
| `backend/docs/flow/chat.md` | 신규 |
| `backend/docs/flow/files.md` | 신규 |
| `backend/docs/flow/mcp.md` | 신규 |
| `backend/docs/flow/model.md` | 신규 |
| `backend/docs/flow/agent.md` | 신규 |
| `backend/docs/flow/user.md` | 신규 |
| `backend/docs/flow/connector.md` | 신규 |
| `backend/docs/flow/memory.md` | 신규 |
| `backend/docs/flow/attention.md` | 신규 |
| `backend/docs/flow/proactive.md` | 신규 |
| `docs/adr/ADR-20261009-flow-folder.md` | 신규 |
| `docs/adr/ADR-20261009-docs-per-module.md` | 수정 |
| `docs/adr/INDEX.md` | 수정 |
| `docs/adr/*.md` | 수정 |
| `backend/docs/adr/*.md` | 수정 |
| `web/docs/adr/*.md` | 수정 |
| `hermes/docs/adr/*.md` | 수정 |
| `backend/docs/code-architecture.md` | 수정 |
| `backend/docs/data-schema.md` | 수정 |
| `docs/flow.md` | 수정 |
| `docs/prd.md` | 수정 |
| `docs/privacy.md` | 수정 |
| `web/docs/flow.md` | 수정 |
| `web/docs/prd.md` | 수정 |
| `web/docs/code-architecture.md` | 수정 |
| `hermes/docs/hermes-contract.md` | 수정 |
| `hermes/README.md` | 수정 |
| `hermes/connectors/README.md` | 수정 |
| `hermes/connectors/*/README.md` | 수정 |
| `hermes/plugins/*/README.md` | 수정 |
| `README.md` | 수정 |
| `README.ko.md` | 수정 |
| `AGENTS.md` | 수정 |
| `backend/AGENTS.md` | 수정 |
| `web/AGENTS.md` | 수정 |
| `.github/workflows/code-review-prompt.txt` | 수정 |
| `backend/src/main/java/com/bifos/assistant/**/*.java` | 수정 |
| `test/unit/doc-files.test.ts` | 수정 |
| `test/unit/doc-code-references.test.ts` | 수정 |
| `test/unit/doc-references.test.ts` | 수정 |
| `test/unit/file-length.test.ts` | 수정 |
| `scripts/check-file-length.mjs` | 수정 |
