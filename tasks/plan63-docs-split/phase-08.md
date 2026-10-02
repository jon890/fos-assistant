# Phase 08. web 지침에서 설정 파일이 소유한 것을 덜어 낸다

**Execution profile**: standard

## 목표

`web/AGENTS.md` 에서 설정 파일과 CI 정의를 옮겨 적은 내용을 덜어 낸다.
지침이 설정과 따로 고쳐져 낡는 것을 막기 위해서다.

커밋 제목의 범위는 `web` 이다. `docs(web): …` 로 쓴다.

**범위 외**

- lint 규칙과 검사 설정 자체를 바꾸지 않는다.
- `backend/AGENTS.md` 는 phase 07, 루트 `AGENTS.md` 는 phase 09 다.

## 컨텍스트

소유자다.

| 무엇 | 소유자 |
| --- | --- |
| 도구 버전 | `web/package.json` |
| lint 규칙의 대상과 심각도, 상대 경로 import 예외 목록 | `web/eslint.config.mjs` |
| 브라우저 검사의 폭과 병렬 설정 | `web/playwright.config.ts` |
| CI 의 shard 구조, 필수 검사 판정, 매일 실행, 실패 이슈 | `.github/workflows/ci.yml` 과 `scripts/browser-ci.mjs`. 설명은 루트 `AGENTS.md` 의 「확인」 절 |
| `scripts/quality.sh` 의 동작 | 그 스크립트의 머리 주석과 루트 `AGENTS.md` |
| 화면 구조와 동작 | `docs/frontend/*.md` |

**근거 문서**: `docs/adr/ADR-042-코드-품질-규칙은-도구-설정이-갖고-기존-위반은-기준-파일에-둔다.md`, `AGENTS.md` 의 「확인」 절

## 의도 메모

- 지우기 전에 그 내용이 소유자 파일에 실제로 있는지 읽어 확인한다. 소유자 쪽에 없는 **까닭**이 지침에만 있으면 지우지 않고 남긴다. 설정 파일은 이 phase 가 고치지 않는다.
- 까닭이 설정으로 표현되지 않는 규칙은 남긴다. 「줄 수가 정해지지 않은 목록의 링크는 `prefetch={false}` 를 준다」, 「운영 코드에 시험용 문을 만들지 않는다」, 「기능 변경과 포맷을 다른 커밋으로 나눈다」 가 그렇다.

## 작업 항목

### 1. 「디렉터리」 절의 트리를 지운다

코드 블록으로 그린 `src/` 트리를 지운다. 실제 디렉터리와 다르게 낡았다(`web/src/components` 에 트리에 없는 디렉터리가 있다).
그 위의 설명 두 문장(`app/` 은 경로와 서버에서 읽는 것만, `components/ui/` 는 어느 화면에도 속하지 않는 조각)은 남긴다.
`docs/frontend/structure.md` 에 같은 트리가 남아 있으면 함께 지운다.

### 2. 「lint 와 포맷」 절을 줄인다

- 도구 버전 숫자를 적은 문장을 지운다. 「버전은 `web/package.json` 이 정확한 값으로 고정한다」 는 뜻만 남긴다.
- 규칙 표(규칙, 대상, 심각도, 까닭)에서 「까닭」 이 `web/eslint.config.mjs` 의 그 규칙 블록 주석에 이미 있는 줄을 지운다. 주석에 까닭이 없는 줄은 표에 남긴다. 이 phase 는 설정 파일을 고치지 않는다. 표가 비면 표째 지운다.
- `scripts/quality.sh` 의 `fix` 가 무엇을 돌리는지 되풀이한 문장을 지우고 루트 `AGENTS.md` 의 「확인」 절을 가리킨다.
- 「상대 경로 import 예외」 절에서 지금 목록을 파일 이름으로 나열한 문장을 지운다. 목록은 `eslint.config.mjs` 의 `NODE_TEST_READ_FILES` 가 갖는다. 언제 목록에 더하는지를 적은 문장은 남긴다.
- 「기준 파일」 절과 「포맷」 절에서 설정 파일에 같은 내용이 있는 줄만 지운다. 판단 규칙(바뀐 파일만 검사하는 까닭, 마크다운이 대상이 아닌 까닭)은 남긴다.

### 3. 「검사」 절에서 CI 설명을 지운다

- 폭의 px 값을 적은 문장을 지운다. `web/playwright.config.ts` 가 갖는다. 「`mobile` 과 `desktop` 두 폭에서 돈다」 는 남긴다.
- CI 의 shard, 필수 검사 판정, artifact, 매일 실행, 실패 이슈를 설명한 두 문단을 지운다. 루트 `AGENTS.md` 의 「확인」 절이 같은 내용을 갖는다.
- 그 가운데 `fullyParallel: false`, `workers: 1`, globalSetup 이 shard 마다 새 Control Plane 과 메모리 DB 를 만든다는 문장은 남긴다. 검사를 쓰는 사람이 알아야 하는 제약이다.
- 지운 자리에 「CI 가 이 검사를 어떻게 나눠 돌리는지는 `.github/workflows/ci.yml` 이 갖고, 머지 전에 무엇을 확인하는지는 루트 `AGENTS.md` 의 「확인」 절이 갖는다」 한 줄을 둔다.

### 4. 머리의 문서 포인터를 확인한다

h1 아래에 `docs/frontend/` 를 가리키는 목록이 있는지 본다. 없으면 더한다.

- 화면 구조: `../docs/frontend/structure.md`
- 화면 동작: `../docs/frontend/shell.md`, `../docs/frontend/chat.md`, `../docs/frontend/activity.md`
- 화면 부품과 색의 결정: `../docs/adr/INDEX.md` 에서 층 칸이 frontend 인 것

## 검증

```bash
# cwd: 저장소 root
# 1. 지침이 줄었다
wc -l web/AGENTS.md

# 2. 지운 것이 남지 않았다. 출력이 없어야 한다
grep -n 'shard=1/4\|390px\|eslint 9\.' web/AGENTS.md

# 3. 품질 검사, 문서 경로 검사, 링크. $DOCS_CHECK_DIR 은 docs-check 스킬 번들 경로다
scripts/quality.sh check
node --test 'test/unit/**/*.test.ts'
python3 "$DOCS_CHECK_DIR/scripts/static_check.py" docs/adr web/AGENTS.md
python3 "$DOCS_CHECK_DIR/scripts/static_check.py" docs/adr docs
scripts/check-public-safe.sh
```

기대값: 1번 작업 전보다 40줄 넘게 줄었다. 2번 출력 없음. 3번 종료 코드 0, `static_check.py` 만 예외다. 출력에 `깨진 링크`, `없는 앵커` 가 든 줄이 0건이다. 종료 코드는 보지 않는다. `INDEX_DESYNC` 는 검사기의 알려진 오탐이고 그것 때문에 종료 코드가 늘 1 이다.
`node --test` 는 `test/unit/doc-references.test.ts` 를 포함한다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `web/AGENTS.md` | 수정 |
| `docs/frontend/structure.md` | 수정 |
