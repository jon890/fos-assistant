# brain-import

기존 개인 지식 저장소를 Memory 로 옮기는 일회성 절차에서 주인의 기기가 하는 두 단계를 맡는다.
결정과 계약은 [ADR-058](../../docs/adr/ADR-058-기존-개인-지식-저장소는-주인이-검토한-묶음을-화면에서-올려-들여온다.md) 이 갖는다.

이 스크립트는 파일만 만든다. 어디에도 저장하지 않는다.
분석기는 이 저장소에 없다. 이 저장소는 분석기가 만든 보고서의 모양만 안다.

## 절차

| 순서 | 누가 | 명령 | 결과 |
| --- | --- | --- | --- |
| 1 | 지식 저장소의 분석기 | 분석기를 돌린다 | 보고서 `report.json` |
| 2 | 이 스크립트 | `node scripts/brain-import/decisions.ts init --report <report.json> --out <decisions.json>` | 결정 파일의 틀. 개수만 출력한다 |
| 3 | 주인 | 결정 파일을 고친다 | 항목마다 `decision` 을 `IMPORT` 나 `SKIP` 으로 적는다 |
| 4 | 이 스크립트 | `node scripts/brain-import/bundle.ts --report <report.json> --decisions <decisions.json> --public-root <공개 저장소 root> --private-root <비공개 저장소 root> --out-dir <저장소 밖의 디렉터리>` | 묶음 `bundle-001.json` 부터. 신원 항목은 뺀다 |
| 5 | 주인 | `/memory` 의 「기존 기록 가져오기」 에 묶음을 올린다 | 미리보기를 읽고 가져온다 |
| 6 | 이 스크립트 | `node scripts/brain-import/bundle.ts clean --out-dir <같은 디렉터리>` | 묶음과 `problems.json` 을 지운다 |

신원 항목은 `--identity only` 로 따로 묶는다. 그 묶음은 `identity-bundle-001.json` 이다.
신원 항목을 들이는 길은 ADR-058 의 「신원 항목을 여는 조건」 을 운영에서 확인한 뒤에 연다.

**Node 22.18 이상이 필요하다.** TypeScript 를 그대로 실행하므로 설치할 의존성이 없다.

## 지키는 것

- 보고서, 결정 파일, 묶음 파일은 git 작업 디렉터리 밖에만 둔다. 출력 자리가 작업 디렉터리 안이면 멈춘다
- 묶음은 민감 본문을 평문으로 담는다. 파일은 0600, 디렉터리는 0700 이다. 가져오기가 끝나면 `clean` 으로 지우고 커밋하지 않는다
- 표준 출력과 표준 오류에는 개수와 결과 코드만 낸다. 제목, 경로, 본문을 내지 않는다
- 문제가 있는 항목의 이름은 `<out-dir>/problems.json` 에만 적는다. 이름이 곧 개인 저장소의 경로다
- `PENDING` 이 하나라도 남았거나 문제가 하나라도 있으면 묶음을 만들지 않고 종료 코드 1 로 끝난다
- `init` 은 이미 있는 결정 파일을 덮어쓰지 않는다. 사람이 고친 결정을 지우지 않는다
- 결정 파일의 `reportGeneratedAt` 이 보고서의 `generated_at` 과 다르면 다른 보고서의 결정으로 보고 멈춘다

## 보고서에서 읽는 칸

지식 저장소의 분석기가 만든 `report.json` 이다. 여기 없는 칸은 읽지 않는다.

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

## 묶을 수 없는 까닭의 코드

`IMPORT` 로 적은 항목이 아래에 걸리면 묶지 않는다. 위에서부터 보고 먼저 걸린 것으로 답한다.

| 코드 | 언제 |
| --- | --- |
| `ENTRY_TYPE_REQUIRED` | `entryType` 이 null 이다. `REVIEW_REQUIRED` 항목에 종류를 적지 않은 경우다 |
| `CONFLICT_UNRESOLVED` | `verdict` 가 `CONFLICT` 인데 `conflictResolution` 이 `KEEP_THIS` 가 아니다 |
| `HOLD_NOT_CLEARED` | `hold` 가 있는데 `holdCleared` 가 거짓이다 |
| `COLLECTION_INVALID` | `collection` 이 `^[a-z][a-z0-9-]{0,63}$` 가 아니다 |
| `DOCUMENT_KEY_INVALID` | `entryType` 이 `DOCUMENT` 인데 `documentKey` 가 `^[a-z0-9][a-z0-9-]{0,127}$` 가 아니다 |
| `TITLE_INVALID` | 제목이 비었거나 200자를 넘는다 |
| `SENSITIVE_ALWAYS` | `sensitive` 이고 `retrieval` 이 `ALWAYS` 다 |
| `IDENTITY_MUST_BE_SENSITIVE_DOCUMENT` | collection 이 `identity` 인데 민감 문서가 아니다 |
| `SOURCE_REF_TOO_LONG` | `<namespace>/<경로>` 가 512자를 넘는다 |
| `DOCUMENT_KEY_DUPLICATED` | 같은 묶음의 앞 `DOCUMENT` 와 `collection` 과 `documentKey` 가 같다 |
| `NAMESPACE_UNKNOWN`, `UNSUPPORTED_FILE`, `FILE_NOT_FOUND`, `PATH_OUTSIDE_ROOT`, `CONTENT_EMPTY`, `CONTENT_TOO_LONG` | 파일을 읽을 때 걸린다. 확장자는 `.md` 와 `.txt` 만 받고, 읽은 경로가 root 밖이면(`..`, 심볼릭 링크) 거절한다 |

`retrieval` 은 묶을 때 종류로 고정한다. `DOCUMENT` 는 `SEARCH`, `SOURCE` 는 `ARCHIVE`, `MEMORY` 는 결정 파일의 값이되 `ARCHIVE` 면 `SEARCH` 로 둔다.
본문은 맨 앞의 frontmatter 를 떼고, 12,000자를 넘으면 `CONTENT_TOO_LONG` 이다.
한 묶음은 100개와 약 1.9MB 까지이고 넘으면 `bundle-002.json` 으로 나눈다. 화면의 웹 라우트가 2MB 를 넘는 요청을 거절하기 때문이다.
