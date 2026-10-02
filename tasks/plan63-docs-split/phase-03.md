# Phase 03. 코드가 가리키는 문서 경로와 절 이름을 검사한다

**Execution profile**: standard

## 목표

코드 주석과 프롬프트가 가리키는 문서 경로와 절 이름이 실제로 있는지 `test/unit` 에서 검사한다.
문서를 옮기거나 절 이름을 바꿀 때 주석이 낡는 것을 그 자리에서 잡기 위해서다.

**범위 외**: Markdown 문서끼리의 링크와 앵커는 `docs-check` 스킬의 정적 검사가 본다. 이 테스트는 다루지 않는다.

## 컨텍스트

Java 주석, Python 주석, TypeScript 주석, 워크플로 프롬프트가 문서를 두 가지 모양으로 가리킨다.

- 경로만: `docs/hermes/profiles.md`
- 경로와 절 이름: `{@code docs/connectors.md} 의 「승인」`, `` `docs/backend/packages.md` 「backend 패키지」 ``, `[...](../docs/code-architecture.md) 의 「Hermes 쪽 코드 (`hermes/`)」`

이런 자리가 100곳 안팎이고 지금은 아무 검사도 받지 않는다.
`unit` 은 이미 CI 의 필수 검사라 새 job 을 만들지 않는다.

테스트 형식은 `test/unit/loading-routes.test.ts` 를 따른다. `node:test`, `node:assert/strict`, `node:fs/promises` 만 쓰고 의존성을 더하지 않는다.

**근거 문서**: `docs/code-architecture.md` 의 「경계」 절, `AGENTS.md` 의 「확인」 절

## 의도 메모

- `docs-check` 의 정적 검사기에 넣는 안은 버렸다. 그 검사기는 다른 저장소에 있고 CI 에서 돌지 않는다.
- 절 이름 검사는 경로 바로 뒤에 「」 가 오는 모양만 본다. 떨어진 자리의 「」 까지 보면 화면 문구 인용을 절 이름으로 오인한다.

## 작업 항목

### 1. `test/unit/doc-references.test.ts` 를 만든다

**검사 대상 파일.** `git ls-files` 의 결과에서 고른다. `child_process` 의 `execFileSync("git", ["ls-files"])` 를 쓴다. 디렉터리를 직접 훑으면 `node_modules` 와 빌드 결과를 읽는다.

- 넣는다: 확장자가 `.java`, `.kt`, `.kts`, `.sql`, `.xml`, `.py`, `.ts`, `.tsx`, `.mjs`, `.sh`, `.yml`, `.txt`, `.template` 인 파일과 `docs/` 밖의 `.md`(`AGENTS.md`, `backend/AGENTS.md`, `web/AGENTS.md`, `hermes/README.md`, `README.md`)
- 뺀다: `docs/**`, `tasks/**`, 이 테스트 파일 자신, `CLAUDE.md`(`AGENTS.md` 와 같은 내용이다)

**경로 검사.** 각 파일에서 정규식 `docs/[A-Za-z0-9_./-]+\.md` 로 경로를 찾고, 저장소 root 기준으로 그 파일이 있는지 확인한다.
`../docs/…` 처럼 앞에 `../` 가 붙은 것도 `docs/` 부터 읽으면 root 기준 경로가 된다.
자리표시자는 뺀다. 경로에 `NNN`, `<`, `*` 가 있으면 건너뛴다. 예: `docs/adr/NNN-<슬러그>.md`.

**절 이름 검사.** 경로 바로 뒤에 아래 모양으로 「」 가 오면 그 글을 절 이름으로 본다.

```
경로 + 닫는 글자 0~2개( ` 또는 } 또는 ) ) + 공백 0~1개 + (의)? + 공백 0~1개 + 「절 이름」
```

절 이름을 그 문서의 헤딩 글과 비교한다. 비교 전에 양쪽을 정규화한다.

- `{@code X}` 는 `X` 로
- 백틱을 지운다
- 앞뒤 공백을 지운다

문서의 모든 단계 헤딩(`#` 부터 `######`) 가운데 정규화한 글이 같은 것이 하나 이상 있어야 한다.

**실패 메시지.** 틀린 자리를 한 번에 모두 보인다. `파일:줄  경로  「절 이름」` 을 줄마다 적고 마지막에 `assert.deepEqual(problems, [])` 로 실패시킨다.

테스트는 둘이다.

- `코드와 프롬프트가 가리키는 문서 파일이 있다`
- `코드와 프롬프트가 「」 로 가리키는 절이 그 문서에 있다`

### 2. 검사 함수 자체를 확인하는 테스트를 같은 파일에 둔다

찾기와 정규화를 순수 함수로 빼고(`findDocReferences(text)`, `normalizeHeading(text)`), 문자열 입력으로 확인한다.

- 정상: `{@code docs/connectors.md} 의 「승인」` 에서 경로 `docs/connectors.md` 와 절 이름 `승인` 을 얻는다
- 정상: `` [x](../docs/code-architecture.md) 의 「Hermes 쪽 코드 (`hermes/`)」 `` 에서 절 이름이 정규화 뒤 `Hermes 쪽 코드 (hermes/)` 가 된다
- 정상: 절 이름 없는 `docs/hermes/profiles.md` 는 경로만 얻는다
- 실패: `docs/adr/NNN-<슬러그>.md` 는 결과에 없다

### 3. 이 테스트가 잡은 기존 위반을 고친다

테스트를 처음 돌리면 phase 01 이 놓친 자리가 나올 수 있다. 그 주석의 경로나 절 이름을 실제에 맞춘다.
Java 주석을 고쳤으면 `scripts/quality.sh check` 를 돌린다.

## 검증

```bash
# cwd: 저장소 root
node --test test/unit/doc-references.test.ts
node --test 'test/unit/**/*.test.ts'
scripts/quality.sh check
```

기대값: 셋 다 종료 코드 0.

검사가 실제로 실패하는지 한 번 확인한다. Java 주석 하나의 절 이름을 없는 이름으로 바꾸고 첫 명령이 그 `파일:줄` 을 내며 실패하는지 본 뒤 되돌린다. `git status --short` 에 그 파일이 남지 않아야 한다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `test/unit/doc-references.test.ts` | 신규 |
| `backend/src/**/*.java` | 수정 |
