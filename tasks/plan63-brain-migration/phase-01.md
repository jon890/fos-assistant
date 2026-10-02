# Phase 01. 결정 파일의 틀을 만들고 묶음을 만드는 스크립트

**Execution profile**: standard

## 목표

분석기의 보고서에서 결정 파일의 틀을 만들고, 주인이 고친 결정 파일로 묶음 파일을 만드는 스크립트를 둔다.
스크립트는 주인의 기기에서 돌고 어디에도 저장하지 않는다. 파일만 만든다.

**범위 외**: 분석기. 묶음을 받는 backend(phase 02)와 화면(phase 03).

## 컨텍스트

- 이 저장소는 Node 22.18 이상의 TypeScript 실행을 쓴다. 설치할 의존성이 없다. `test/e2e/run.ts` 와 `test/unit/*.test.ts` 가 그렇게 돈다. import 는 `.ts` 확장자까지 적고, `enum` 과 `namespace` 처럼 지워지지 않는 문법은 쓰지 않는다
- 단위 검사는 `node --test 'test/unit/**/*.test.ts'` 가 돌린다. 선례는 `test/unit/pr-risk-labels.test.ts` 처럼 `scripts/` 의 것을 검사하는 파일이다
- 묶음의 모양과 상한은 `docs/adr/ADR-058-기존-개인-지식-저장소는-주인이-검토한-묶음을-화면에서-올려-들여온다.md` 의 「적용 범위」 가 갖는다

보고서에서 읽는 칸. 지식 저장소의 분석기가 만든 `report.json` 이다. 여기 없는 칸은 읽지 않는다.

| 칸 | 값 |
| --- | --- |
| `schema_version` | 1. 다르면 멈춘다 |
| `generated_at` | 보고서를 만든 시각의 글. 결정 파일이 어느 보고서의 것인지 묶는다 |
| `items[].namespace` | `public` 또는 `private` |
| `items[].source_ref` | 그 저장소 root 에서 본 상대 경로. `wiki/` 나 `raw/` 로 시작한다 |
| `items[].source_date` | `YYYY-MM-DD` 또는 null |
| `items[].title`, `collection`, `sensitivity`, `retrieval` | 제안값. `sensitivity` 는 `NORMAL` 또는 `SENSITIVE` |
| `items[].entry_type` | `MEMORY`, `DOCUMENT`, `SOURCE` 또는 null |
| `items[].document_key` | 문서 이름의 제안값 또는 null |
| `items[].classification` | `IMPORT_MEMORY`, `IMPORT_DOCUMENT`, `IMPORT_SOURCE`, `SKIP`, `REVIEW_REQUIRED` |
| `items[].verdict` | `NEW`, `DUPLICATE`, `CONFLICT`, `STALE` |
| `items[].hold` | 보류 까닭의 글. 없으면 칸이 없다 |

**근거 문서**: `docs/adr/ADR-058-기존-개인-지식-저장소는-주인이-검토한-묶음을-화면에서-올려-들여온다.md`

## 의도 메모

- 틀을 만드는 것과 묶는 것을 다른 명령으로 둔다. 사이에 사람이 결정 파일을 고친다. 한 명령이 분류부터 묶음까지 가지 않는다
- 묶는 명령은 `PENDING` 이 하나라도 있으면 아무 파일도 만들지 않는다. 검토하지 않은 항목이 딸려 가지 않게 한다
- 문제가 있는 항목의 이름을 화면에 찍지 않는다. 이름이 곧 개인 저장소의 경로다. 출력 자리의 문제 파일에 적고 표준 출력에는 개수만 낸다
- 신원 항목은 기본으로 빼고 `--identity only` 로만 묶는다. 한 묶음에 신원과 다른 것이 섞이지 않는다

## 작업 항목

### 1. `scripts/brain-import/lib.ts`

순수 함수와 타입만 둔다. 파일을 읽고 쓰지 않는다.

```ts
export type Decision = "PENDING" | "IMPORT" | "SKIP";
export type EntryType = "MEMORY" | "DOCUMENT" | "SOURCE";
export type DecisionItem = {
  id: string;                 // `${namespace}/${source_ref}`
  classification: string;
  verdict: string;
  hold: string | null;
  decision: Decision;
  collection: string;
  entryType: EntryType | null;
  documentKey: string | null;
  title: string;
  sensitive: boolean;
  retrieval: "ALWAYS" | "SEARCH" | "ARCHIVE";
  conflictResolution: "KEEP_THIS" | null;
  holdCleared: boolean;
};
export type DecisionFile = { schemaVersion: 1; reportGeneratedAt: string; items: DecisionItem[] };
export type BundleItem = {
  sourceRef: string; sourceDate: string | null; collection: string; entryType: EntryType;
  documentKey: string | null; title: string; content: string; sensitive: boolean;
  retrieval: "ALWAYS" | "SEARCH" | "ARCHIVE";
};
export type Bundle = { schemaVersion: 1; createdAt: string; items: BundleItem[] };

export const MAX_ITEMS_PER_BUNDLE = 100;
export const MAX_CONTENT_CHARS = 12000;
```

| 함수 | 동작 |
| --- | --- |
| `initialDecisions(report): DecisionFile` | 항목마다 틀을 만든다. `classification` 이 `SKIP` 이면 `decision` 을 `SKIP`, 그 밖은 `PENDING`. `sensitive` 는 `sensitivity === "SENSITIVE"`. `entryType`, `documentKey` 는 보고서 값 그대로. `conflictResolution` 은 null, `holdCleared` 는 false. `schema_version` 이 1 이 아니면 던진다 |
| `stripFrontmatter(text): string` | 맨 앞의 `---` 줄에서 다음 `---` 줄까지를 떼고 앞뒤 빈 줄을 다듬는다. 닫는 줄이 없으면 그대로 낸다 |
| `problemOf(item: DecisionItem): string \| null` | 묶을 수 없는 까닭의 코드를 낸다. 아래 표 |
| `selectForBundle(decisions, identity: "exclude" \| "only"): { selected: DecisionItem[]; skipped: number; identityHeld: number; problems: {id, code}[]; pending: number }` | `IMPORT` 인 항목을 고른다. `exclude` 면 collection 이 `identity` 인 항목을 빼고 그 수를 `identityHeld` 에 센다. `only` 면 `identity` 만 고른다 |
| `chunk(items, size): T[][]` | 차례를 지켜 나눈다 |

`problemOf` 의 코드. 위에서부터 본다.

| 코드 | 언제 |
| --- | --- |
| `ENTRY_TYPE_REQUIRED` | `entryType` 이 null 이다. `REVIEW_REQUIRED` 항목을 고치지 않은 경우다 |
| `CONFLICT_UNRESOLVED` | `verdict` 가 `CONFLICT` 인데 `conflictResolution` 이 `KEEP_THIS` 가 아니다 |
| `HOLD_NOT_CLEARED` | `hold` 가 null 이 아닌데 `holdCleared` 가 false 다 |
| `COLLECTION_INVALID` | `collection` 이 `^[a-z][a-z0-9-]{0,63}$` 가 아니다 |
| `DOCUMENT_KEY_INVALID` | `entryType` 이 `DOCUMENT` 인데 `documentKey` 가 `^[a-z0-9][a-z0-9-]{0,127}$` 가 아니다 |
| `TITLE_INVALID` | 제목이 비었거나 200자를 넘는다 |
| `SENSITIVE_ALWAYS` | `sensitive` 이고 `retrieval` 이 `ALWAYS` 다 |
| `IDENTITY_MUST_BE_SENSITIVE_DOCUMENT` | collection 이 `identity` 인데 `sensitive` 가 아니거나 `entryType` 이 `DOCUMENT` 가 아니다 |
| `SOURCE_REF_TOO_LONG` | `id` 가 512자를 넘는다 |

같은 묶음 안에서 `DOCUMENT` 둘이 같은 `collection` 과 `documentKey` 를 가지면 뒤의 항목이 `DOCUMENT_KEY_DUPLICATED` 다. `selectForBundle` 이 본다.
`retrieval` 은 묶을 때 종류로 고정한다. `DOCUMENT` 는 `SEARCH`, `SOURCE` 는 `ARCHIVE`, `MEMORY` 는 결정 파일의 값이되 `ARCHIVE` 면 `SEARCH` 로 둔다.

### 2. `scripts/brain-import/decisions.ts`

```bash
node scripts/brain-import/decisions.ts init --report <report.json> --out <decisions.json>
```

- 보고서를 읽어 `initialDecisions` 의 결과를 `--out` 에 쓴다. 파일 권한은 0600 이다
- `--out` 이 이미 있으면 덮어쓰지 않고 종료 코드 1 로 멈춘다. 사람이 고친 결정을 지우지 않는다
- `--out` 이 git 작업 디렉터리 안이면 종료 코드 1 로 멈춘다. 그 경로에서 위로 올라가며 `.git` 이 있는지 본다
- 표준 출력에는 `decision` 별 개수만 JSON 한 줄로 낸다

### 3. `scripts/brain-import/bundle.ts`

```bash
node scripts/brain-import/bundle.ts --report <report.json> --decisions <decisions.json> \
  --public-root <공개 저장소 root> --private-root <비공개 저장소 root> \
  --out-dir <저장소 밖의 디렉터리> [--identity exclude|only]
node scripts/brain-import/bundle.ts clean --out-dir <같은 디렉터리>
```

- `--identity` 의 기본값은 `exclude` 다
- 결정 파일의 `reportGeneratedAt` 이 보고서의 `generated_at` 과 다르면 멈춘다. 다른 보고서의 결정이다
- `pending` 이 0 이 아니거나 `problems` 가 비어 있지 않으면 **묶음을 만들지 않는다.** `problems` 를 `<out-dir>/problems.json` 에 쓰고 종료 코드 1 이다
- 고른 항목마다 `namespace` 로 root 를 골라 `<root>/<source_ref>` 를 읽는다. 확장자가 `.md` 와 `.txt` 가 아니면 `UNSUPPORTED_FILE`, 읽은 경로가 root 밖으로 나가면(`..`, 심볼릭 링크) `PATH_OUTSIDE_ROOT`, `stripFrontmatter` 뒤의 본문이 비었으면 `CONTENT_EMPTY`, 12,000자를 넘으면 `CONTENT_TOO_LONG` 이다. 이것도 `problems.json` 에 적고 묶음을 만들지 않는다
- 통과하면 100개씩 나눠 `<out-dir>/bundle-001.json`, `bundle-002.json` 순으로 쓴다. 신원 묶음은 `identity-bundle-001.json` 이다. 파일은 0600, 디렉터리는 0700 이다
- `--out-dir` 이 git 작업 디렉터리 안이면 멈춘다
- 표준 출력에는 `{ bundles, items, skipped, identityHeld }` 의 개수만 JSON 한 줄로 낸다. 제목, 경로, 본문을 내지 않는다. 실패한 때의 표준 오류에도 코드별 개수만 낸다
- `clean` 은 `<out-dir>` 의 `bundle-*.json`, `identity-bundle-*.json`, `problems.json` 을 지우고 지운 수를 낸다. 다른 파일은 건드리지 않는다

파일 머리 주석에 절차를 적는다: 묶음은 민감 본문을 평문으로 담는다. 저장소 밖에만 만들고, 가져오기가 끝나면 `clean` 으로 지운다. 커밋하지 않는다.

### 4. 이 phase 를 검증하는 `test/unit/brain-import.test.ts`

`lib.ts` 의 함수는 값으로, 두 명령은 `node:child_process` 의 `spawnSync(process.execPath, [...])` 로 돌려 본다. 입력은 `fs.mkdtempSync(path.join(os.tmpdir(), "brain-import-"))` 아래에 지어낸 저장소 둘과 보고서를 만들어 쓴다.

| 입력 | 기대 |
| --- | --- |
| `SKIP` 하나와 `IMPORT_DOCUMENT` 하나인 보고서로 `initialDecisions` | `decision` 이 `SKIP` 과 `PENDING` 이다 |
| `schema_version` 이 2 인 보고서 | 던진다 |
| `stripFrontmatter("---\ntitle: a\n---\n\n본문")` | `본문` |
| `REVIEW_REQUIRED` 항목을 `IMPORT` 로만 바꾸고 `entryType` 이 null | `ENTRY_TYPE_REQUIRED` |
| `verdict` 가 `CONFLICT` 인 `IMPORT` 항목 | `CONFLICT_UNRESOLVED`. `conflictResolution` 을 `KEEP_THIS` 로 적으면 null |
| `hold` 가 있는 항목 | `HOLD_NOT_CLEARED`. `holdCleared` 를 참으로 적으면 null |
| `identity` 항목이 `sensitive: false` | `IDENTITY_MUST_BE_SENSITIVE_DOCUMENT` |
| `identity` 하나와 `career` 하나를 `exclude` 로 고른다 | `selected` 가 `career` 하나, `identityHeld` 가 1. `only` 면 `identity` 하나 |
| 같은 collection 과 `documentKey` 의 `DOCUMENT` 둘 | 둘째가 `DOCUMENT_KEY_DUPLICATED` |
| `init` 을 같은 `--out` 으로 두 번 | 둘째가 종료 코드 1 이고 파일이 바뀌지 않는다 |
| `--out-dir` 을 이 저장소 안의 경로로 준다 | 종료 코드 1 이고 파일이 생기지 않는다 |
| `PENDING` 이 남은 결정 파일로 `bundle` | 종료 코드 1, `bundle-001.json` 이 없다 |
| 본문이 `평문-표식-7391` 인 파일 하나를 `IMPORT` 로 묶는다 | 종료 코드 0. `bundle-001.json` 의 그 항목 `content` 가 `평문-표식-7391` 이고 `sourceRef` 가 `private/wiki/sample/note-a.md` 다. **표준 출력과 표준 오류가 `평문-표식-7391` 과 `note-a` 를 담지 않는다.** 파일 권한이 0600 이다 |
| 본문이 12,001자인 파일 | 종료 코드 1. `problems.json` 에 `CONTENT_TOO_LONG` 이 있고 묶음이 없다 |
| 항목 101개를 묶는다 | `bundle-001.json` 에 100개, `bundle-002.json` 에 1개 |
| `clean` | 묶음과 `problems.json` 이 지워지고 같은 디렉터리에 둔 다른 파일은 남는다 |

## 검증

```bash
# cwd: 저장소 root
node --test test/unit/brain-import.test.ts
node --test 'test/unit/**/*.test.ts'
scripts/check-public-safe.sh
! grep -rn "console.log(.*content\|console.log(.*title\|console.log(.*id)" scripts/brain-import
```

- 모두 종료 코드 0. 마지막 줄은 일치하는 줄이 없어야 한다

## 변경 파일

| 파일 | 변경 |
|---|---|
| `scripts/brain-import/lib.ts` | 신규 |
| `scripts/brain-import/decisions.ts` | 신규 |
| `scripts/brain-import/bundle.ts` | 신규 |
| `test/unit/brain-import.test.ts` | 신규 |
