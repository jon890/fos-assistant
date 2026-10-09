# Phase 01. 기능별 문서 자리를 결정으로 남기고 문서 검사가 docs/features/ 를 받게 한다

**Execution profile**: standard

## 목표

`docs/features/<기능>.md` 를 둘 수 있게 새 ADR 을 쓰고, 문서 검사 시험과 길이 검사가 그 자리를 알게 한다.
이 phase 는 문서를 옮기지 않는다. 옮기는 커밋(phase 02)이 이동만 보이도록 규칙 변경을 먼저 커밋한다.

**범위 외**: 문서를 옮기고 옛 파일을 지우는 것과, 모듈의 `prd.md`, `flow.md` 를 금지하는 것(phase 02).
기능 파일 머리의 `covers:` 줄과 그 검사, 내용 줄이기는 이 PR 밖의 다음 PR 이 한다.

## 컨텍스트

**근거 문서**: `docs/adr/ADR-20261009-docs-per-module.md`, `docs/adr/INDEX.md` 의 「작성과 정렬 규칙」

- 지금 규칙(`docs-per-module`)은 모듈(`backend/`, `web/`, `hermes/`)마다 `docs/` 바로 아래 `prd.md`, `flow.md`, `code-architecture.md`, `data-schema.md` 만 두고, 하위 디렉터리를 금지한다.
- 사용자가 2026-10-09 에 배치를 바꿨다. 기능마다 `docs/features/<기능>.md` 하나를 두고, 그 기능의 요구와 화면 → backend → Hermes 흐름, 분기와 실패를 한 파일에 모은다. 모듈 `docs/` 에는 그 모듈 안에서만 의미 있는 규칙(`code-architecture.md`, backend 의 `data-schema.md`, hermes 의 `hermes-contract.md`)만 남기고 모듈 `flow.md` 와 `prd.md` 는 없앤다. 루트 `docs/prd.md` 는 제품 전체의 목적과 범위만 남기고, 루트 `docs/flow.md` 는 기능 파일로 흩어 없앤다.
- 시험이 보는 자리
  - `test/unit/doc-files.test.ts` 의 `problemOf`: `docs/` 아래 하위 디렉터리를 `images/` 밖에는 금지한다.
  - `test/unit/doc-code-references.test.ts` 의 `isTargetDocument`: `docs/` 바로 아래 `.md` 만 본다.
  - `test/unit/doc-references.test.ts` 의 `UNIQUE_HEADING_DIRECTORIES`: 한 문서 안 헤딩 중복을 보는 디렉터리 목록이다. 하위 디렉터리는 따로 적는다.
  - `scripts/check-file-length.mjs` 의 `MODULE_DOC` 과 `limitFor`: `docs/` 바로 아래 문서는 1,000줄 알림이다. `test/unit/file-length.test.ts` 가 이것을 단언한다.

## 의도 메모

- 기능 파일은 500줄 알림이다. 실패로 막지 않는다. 옮긴 직후(phase 02)와 줄이기 전에는 넘는 파일이 있고, 줄이는 PR 이 알림을 없앤다.
- `docs/features/` 는 루트에만 둔다. 모듈 아래 `features/` 는 만들지 않는다. 기능은 여러 모듈을 가로지르기 때문이다.
- 기능 파일 이름은 소문자 영문과 숫자, 하이픈이다(`agent-skill.md`). `features/` 아래 하위 디렉터리는 두지 않는다.

## 작업 항목

### 1. 새 ADR `docs/adr/ADR-20261009-feature-docs.md`

제목 머리 `## ADR-20261009 / feature-docs: 문서는 기능마다 한 파일로 두고 모듈 docs 에는 모듈 안의 규칙만 남긴다`, status `accepted`, Date 2026-10-09.
`docs/adr/INDEX.md` 의 기존 ADR 형식(같은 디렉터리의 `ADR-20261009-docs-per-module.md`)을 따른다.

- 결정
  1. `docs/features/<기능>.md` 를 기능마다 하나 둔다. 그 기능의 요구(무엇을 하고 하지 않는가, 무엇을 관측하면 충족인가), 화면 → backend → Hermes 의 끝에서 끝까지 흐름, 분기와 실패와 빈 상태와 동시 요청을 갖는다. 결정은 다시 쓰지 않고 ADR 을 링크한다.
  2. 모듈 `docs/` 에는 그 모듈 안에서만 의미 있는 규칙만 둔다: `code-architecture.md`, backend 의 `data-schema.md`, hermes 의 `hermes-contract.md`. 모듈 `flow.md` 와 `prd.md` 는 두지 않는다. 루트 `docs/prd.md` 는 제품 전체의 목적, 범위, 범위 밖, 아직 만들지 않은 것만 갖는다.
  3. 코드에서 만들 수 있는 것(API 목록, 표와 칸 목록, 커넥터 도구 목록, 설정 값 목록)은 쓰지 않고 어디서 보는지(파일, 명령)만 적는다.
  4. 기능 파일 머리에 다루는 코드 경로를 `covers:` 로 적는다. PR 이 그 경로를 바꾸고 기능 파일을 건드리지 않으면 CI 가 경고만 낸다. 이 검사는 뒤 PR 이 넣는다.
  5. 기능 파일은 500줄을 넘으면 알림을 낸다.
- 맥락: `docs-per-module` 뒤에도 `backend/docs/flow.md` 가 약 6,100줄이었다. 같은 기능의 흐름이 backend flow, web flow, web prd, 루트 flow 네 곳에 나뉘어 한 기능을 고치는 사람이 네 파일을 읽어야 했고 같은 흐름이 여러 번 적혔다.
- 대체된 부분: `docs-per-module` 의 「결정」 표 중 모듈의 `prd.md`, `flow.md` 와, 「대안 기각」 의 「`flow/` 처럼 디렉터리 아래에 주제별 파일을 둔다」. 그 대안이 걱정한 파일 수 증가는 기능 단위(15개 안팎)로 묶고 새 주제는 기존 기능 파일의 절로 더해 막는다.
- 대안 기각: 모듈마다 한 파일 유지(위 맥락), `backend/docs/flow/` 처럼 모듈 안에서만 나누기(화면과 Hermes 쪽 흐름이 여전히 다른 파일에 남는다).
- 감당할 것: 코드 주석과 문서가 기능 파일 이름까지 적어야 하고, 기능을 다른 파일로 옮기면 참조를 함께 고친다(`doc-references` 시험이 잡는다). 기능 경계가 애매한 절은 주로 바뀌는 코드가 있는 기능에 둔다.

`docs/adr/ADR-20261009-docs-per-module.md` 의 「결정」 절 첫머리에 `대체된 부분` 한 줄을 두어 새 ADR 을 링크하고, 새 ADR 에서도 그 ADR 을 링크한다.
`docs/adr/INDEX.md` 결정 목록에 새 ADR 을 날짜와 슬러그 순서로 더하고, `docs-per-module` 줄의 상태 칸에 「모듈 prd 와 flow 를 두는 부분은 ADR-20261009 / feature-docs 가 대체한다」 를 붙인다.

### 2. `test/unit/doc-files.test.ts`

- `problemOf` 가 루트 `docs/features/<이름>.md` 를 통과시킨다. 이름은 `^[a-z0-9]+(?:-[a-z0-9]+)*\.md$` 다. `docs/features/x/y.md`, `backend/docs/features/x.md`, `docs/features/Chat.md` 는 실패한다.
- 시험 「모듈 docs 의 정해진 이름과 예외는 통과하고…」 에 위 통과 하나와 실패 셋을 더한다.

### 3. `test/unit/doc-code-references.test.ts`

`isTargetDocument` 가 `docs/features/<이름>.md` 도 대상으로 본다. 「검사 대상은 docs 바로 아래 문서와…」 시험에 `docs/features/chat.md` 가 `true` 인 단언을 더한다.

### 4. `test/unit/doc-references.test.ts`

`UNIQUE_HEADING_DIRECTORIES` 에 `"docs/features"` 를 더한다.

### 5. `scripts/check-file-length.mjs` 와 `test/unit/file-length.test.ts`

- `limitFor("docs/features/<이름>.md")` 는 500 이다. 넘으면 모듈 문서처럼 `알림:` 만 낸다. `SCAN_DIRS` 의 `docs` 가 하위 디렉터리를 훑는지 확인하고, 아니면 `docs/features` 를 더한다.
- 머리 주석의 ADR 언급에 `ADR-20261009 / feature-docs` 를 더한다.
- 시험: `limitFor("docs/features/chat.md") === 500`, 501줄 기능 파일은 실패가 아니라 알림 하나를 낸다. 기존 1,000줄 단언은 그대로 둔다.

## 검증

```bash
node --test test/unit/doc-files.test.ts test/unit/doc-code-references.test.ts test/unit/doc-references.test.ts test/unit/doc-links.test.ts test/unit/file-length.test.ts test/unit/adr-index.test.ts
node --test 'test/unit/**/*.test.ts'
node scripts/check-file-length.mjs
```

- 세 줄 모두 종료 코드 0 이다. 지금 저장소에는 아직 `docs/features/` 가 없으므로 기존 알림 외에 새 알림이 없다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `docs/adr/ADR-20261009-feature-docs.md` | 신규 |
| `docs/adr/ADR-20261009-docs-per-module.md` | 수정 |
| `docs/adr/INDEX.md` | 수정 |
| `test/unit/doc-files.test.ts` | 수정 |
| `test/unit/doc-code-references.test.ts` | 수정 |
| `test/unit/doc-references.test.ts` | 수정 |
| `test/unit/file-length.test.ts` | 수정 |
| `scripts/check-file-length.mjs` | 수정 |
