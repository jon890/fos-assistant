# Phase 03. 문서 이름 규칙이 모듈 prd 와 flow, 루트 flow 를 더는 받지 않게 한다

**Execution profile**: standard

## 목표

phase 02 가 옛 문서를 기능 파일로 옮긴 뒤, `test/unit/doc-files.test.ts` 가 옛 배치로 돌아가는 파일을 막게 한다.
옮기는 커밋과 규칙을 바꾸는 커밋을 나눠, 옮김 diff 에 시험 변경이 섞이지 않게 한다.

**범위 외**: 문서 내용과 참조(phase 02), 기능 파일 머리의 `covers:` 검사(다음 PR).

## 컨텍스트

**근거 문서**: phase 01 이 만드는 feature-docs ADR, `docs/adr/ADR-20261009-docs-per-module.md`

- `test/unit/doc-files.test.ts` 의 `FIXED_NAMES` 는 루트와 모듈 `docs/` 바로 아래에 `prd.md`, `flow.md`, `code-architecture.md`, `data-schema.md` 를 받는다. phase 01 이 `docs/features/<이름>.md` 를 받게 했다.
- phase 02 뒤 저장소에는 `backend/docs/flow.md`, `web/docs/flow.md`, `web/docs/prd.md`, `docs/flow.md` 가 없다. 남는 것은 루트 `docs/prd.md`, `docs/code-architecture.md`, 각 모듈의 `code-architecture.md`, `backend/docs/data-schema.md`, 예외 목록(`EXCEPTIONS`)의 파일이다.

## 의도 메모

- 루트에 `data-schema.md` 를 받지 않는다. 저장하는 모듈은 backend 하나이고 그 모듈 문서가 갖는다.
- 모듈에 `prd.md`, `flow.md` 를 다시 만들면 실패해야 한다. 그 내용은 기능 파일이 갖는다.

## 작업 항목

### 1. `test/unit/doc-files.test.ts` 의 `problemOf`

- 모듈(`backend/`, `web/`, `hermes/`)의 `docs/` 바로 아래에는 `code-architecture.md` 와 `data-schema.md` 만 통과한다.
- 루트 `docs/` 바로 아래에는 `prd.md` 와 `code-architecture.md` 만 통과한다.
- `EXCEPTIONS`, `docs/features/<이름>.md`, `images/`, ADR 은 지금처럼 통과한다.
- 오류 문구 「정해진 이름이 아니다. 새 주제는 prd, flow, code-architecture, data-schema 의 절로 더한다」 를 「정해진 이름이 아니다. 기능은 docs/features/ 의 기능 파일이나 그 절로 더한다」 로 바꾼다.

### 2. 이 phase 를 검증하는 시험

같은 파일의 시험 「모듈 docs 의 정해진 이름과 예외는 통과하고…」 를 고친다.
- 통과: `docs/prd.md`, `docs/code-architecture.md`, `backend/docs/data-schema.md`, `web/docs/code-architecture.md`, `docs/privacy.md`, `docs/features/chat.md`, ADR 경로들.
- 실패: `backend/docs/flow.md`, `web/docs/flow.md`, `web/docs/prd.md`, `docs/flow.md`, `docs/data-schema.md`, 지금 실패 쪽에 있는 입력들.
- 시험 「Markdown 파일은 정해진 이름과 예외 자리에만 있다」 가 실제 저장소에서 통과한다.

## 검증

```bash
node --test test/unit/doc-files.test.ts
node --test 'test/unit/**/*.test.ts'
```

- 두 줄 모두 종료 코드 0 이다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `test/unit/doc-files.test.ts` | 수정 |
