# Phase 04. 전체 흐름을 e2e 로 검사하고 문서를 맞춘다

**Execution profile**: standard

## 목표

묶음을 미리보고, 들이고, 다시 올려 중복으로 답하는 흐름을 e2e 시나리오 하나로 검사한다.
구현한 것을 `docs/` 에 적는다.

**범위 외**: 신원 항목 열기(phase 05).

## 컨텍스트

- e2e 는 `test/e2e/run.ts` 가 backend 를 띄우고 `test/e2e/scenarios/` 의 시나리오를 `SCENARIOS` 배열의 순서로 돌린다. 시나리오의 선례는 `test/e2e/scenarios/memory-document.ts` 다. `import { call, expect, expectStatus, step, type Scenario } from "../harness.ts";` 를 쓰고 `call(context, path, { method, token, body })` 로 부른다. 암호화 key 는 `run.ts` 가 이미 준다
- 사용자 토큰은 `context.tokens.dad`, `context.tokens.kid`, `context.tokens.aunt` 다. 들이기는 웹 JWT 만 보므로 허용 목록과 관계없이 dad 로 부른다
- e2e 만 Flyway 를 지난다. phase 02 가 더한 유일 제약 `uk_memory_user_source` 가 실제로 붙는 곳이 e2e 다
- 문서가 지금 적고 있는 것
  - `docs/code-architecture.md`: 패키지 표의 `memory` 줄, 「Memory」 절과 그 클래스 표, 「다음」 목록의 「다른 곳의 개인 지식을 들여오는 API. 사람이 승인한 항목만 들이고 같은 출처를 두 번 들이지 않는다」, 화면 표의 `/memory` 줄
  - `docs/data-schema.md`: 「memory」 의 `source_type`, `source_ref`, `source_date` 줄. `source_ref` 가 「출처를 가리키는 값. 다른 항목이면 `memory:<번호>`」 로 적혀 있다
  - `docs/flow.md`: 「Memory 본문을 읽는 길」 아래에 「Memory 를 고치고 지울 때」 가 있다
  - `web/AGENTS.md` 의 「화면 문구」 표
  - `docs/adr/ADR-058-기존-개인-지식-저장소는-주인이-검토한-묶음을-화면에서-올려-들여온다.md` 의 `status` 가 「아직 구현 전이다」 로 적혀 있다

**근거 문서**: `docs/adr/ADR-058-기존-개인-지식-저장소는-주인이-검토한-묶음을-화면에서-올려-들여온다.md`

## 의도 메모

- e2e 는 backend 테스트가 본 판정을 다시 훑지 않는다. 운영과 같은 조립(보안 설정, 암호화 설정, Flyway 로 만든 스키마)에서 한 번 왕복하는 것을 본다
- `docs/` 에 지식 저장소의 이름과 경로를 적지 않는다. 「기존 개인 지식 저장소」 로 부른다

## 작업 항목

### 1. `test/e2e/scenarios/memory-import.ts`

`export const memoryImportScenario: Scenario = { name: "기존 지식 가져오기", async run(context) { ... } }`.
출처는 실행마다 겹치지 않게 `private/wiki/sample/e2e-${randomUUID().slice(0, 8)}.md` 로 만든다. 문서 이름도 같은 조각을 넣는다.

| step | 하는 일 | 기대 |
| --- | --- | --- |
| 미리보기는 저장하지 않는다 | dad 가 `/memory-imports/preview` 에 일반 `MEMORY`(`core`, `SEARCH`)와 민감 `DOCUMENT`(`career`, `SEARCH`, 본문 `평문-표식-7391`)를 보낸다 | 200. `newCount` 가 2. dad 의 `/memory-documents` 에 그 문서 이름이 없다 |
| 들이면 생긴다 | 같은 묶음을 `/memory-imports` 에 보낸다 | 200. `newCount` 가 2 이고 두 항목에 `memoryId` 가 있다. `/memory-documents/{memoryId}` 의 `content` 가 `평문-표식-7391`, `sensitive` 가 참, `revision` 이 1 이다 |
| 다시 올리면 중복이다 | 같은 묶음을 한 번 더 보낸다 | 200. `duplicateCount` 가 2, `newCount` 가 0 |
| 다른 사용자는 따로 들인다 | kid 가 같은 묶음을 미리보기 | `newCount` 가 2. 저장하지는 않는다 |
| 신원 항목은 거절한다 | dad 가 `identity` 의 민감 문서를 보낸다 | 200. 그 항목이 `REJECTED`, `IDENTITY_HELD` |
| 서비스 토큰과 토큰 없는 요청은 들이지 못한다 | `token` 없이 `/memory-imports` 를 부른다 | 403. `test/e2e/scenarios/auth.ts` 가 인증하지 못한 사용자 API 요청에 기대하는 값과 같다 |
| 뒤 시나리오에 남기지 않는다 | dad 가 `DELETE /memories/{id}` 로 두 항목을 지운다 | 200 |

`test/e2e/run.ts` 에 import 하고 `SCENARIOS` 의 `memoryDocumentScenario` 바로 뒤에 넣는다. 대화 turn 을 돌리지 않고 항목을 지우고 끝나므로 뒤 시나리오의 Memory 주입과 사용량에 걸리지 않는다. 돌려 보아 뒤 시나리오가 실패하면, 실패한 단언이 무엇을 세는지 읽고 자리를 옮긴 뒤 까닭을 배열의 주석으로 남긴다.

### 2. `docs/code-architecture.md`

- 패키지 표의 `memory` 줄에 「기존 개인 지식의 들이기」 를 더한다
- 「Memory」 절에 아래 뜻을 더한다. 문장은 그 절의 문체에 맞춘다
  - 기존 개인 지식은 주인이 검토한 묶음을 `/memory` 화면에서 올려 들인다. `POST /api/v1/memory-imports/preview` 는 대조만 하고 `POST /api/v1/memory-imports` 가 `NEW` 인 항목만 한 트랜잭션으로 저장한다
  - 들인 줄은 요청자가 주인인 `USER` 범위의 `ACCEPTED` 이고 `source_type` 이 `brain` 이다. 같은 `source_ref` 는 `DUPLICATE`, 같은 문서 이름이나 같은 제목은 `CONFLICT` 로 답하고 저장하지 않는다
  - 쓰는 길은 웹 JWT 뿐이다. 서비스 토큰은 읽기만 한다
  - 묶음을 만드는 스크립트는 `scripts/brain-import/` 에 있고 주인의 기기에서 돈다. 어디에도 저장하지 않는다
- 클래스 표에 `memory.application.MemoryImportService`, `memory.presentation.MemoryImportController` 를 더한다
- 「다음」 목록에서 「다른 곳의 개인 지식을 들여오는 API. …」 줄을 「신원 항목의 들이기. 암호화와 문서 읽기 경계와 `identity` 권한을 운영에서 확인한 뒤에 연다」 로 바꾼다
- 화면 표의 `/memory` 줄에 「기존 기록 가져오기」 를 더한다
- 근거 줄에 ADR-058 링크를 더한다

### 3. `docs/data-schema.md`

- 「memory」 의 `source_type` 줄에 「기존 개인 지식에서 들인 줄은 `brain` 이다」 를, `source_ref` 줄에 「들인 줄은 `<namespace>/<저장소 안의 경로>` 다」 를 더한다
- 그 절의 제약을 적은 자리에 `uk_memory_user_source`(`owner_user_id`, `source_type`, `source_ref`)를 더한다. 출처가 없는 줄은 걸리지 않는다고 적는다
- 근거에 ADR-058 을 더한다

### 4. `docs/flow.md`

「Memory 를 고치고 지울 때」 뒤에 「### 기존 개인 지식을 들일 때」 를 더한다. mermaid `sequenceDiagram` 하나(참가자: 주인, 주인의 기기, 웹, Control Plane, 데이터베이스. 분석기의 보고서, 결정 파일 고치기, 묶기, 화면에 올리기, 미리보기, 확인, 저장)와 「갈리는 지점」 표를 둔다. 표의 줄은 ADR-058 의 「항목의 결과」 표와 요청 전체를 거절하는 세 경우(`VALIDATION_FAILED`, `MEMORY_ENCRYPTION_UNAVAILABLE`, `MEMORY_IMPORT_RETRY`)다.

### 5. `web/AGENTS.md`

「화면 문구」 표에 줄을 더한다: 들이기 | 가져오기. 절 제목은 「기존 기록 가져오기」. `NEW` 는 「새로 가져와요」, `DUPLICATE` 는 「이미 가져왔어요」, `CONFLICT` 는 「같은 이름이 있어요」, `REJECTED` 는 「가져올 수 없어요」.

### 6. ADR 과 목록

- `docs/adr/ADR-058-기존-개인-지식-저장소는-주인이-검토한-묶음을-화면에서-올려-들여온다.md` 의 `status` 줄을 「`accepted`. 신원 항목의 들이기는 아직 구현 전이다.」 로 고친다
- `docs/adr/INDEX.md` 의 ADR-058 줄에서 「아직 구현 전이다」 를 「신원 항목의 들이기는 아직 구현 전이다」 로 고친다

## 검증

```bash
# cwd: 저장소 root
(cd backend && ./gradlew test)
node test/e2e/run.ts
node --test 'test/unit/**/*.test.ts'
scripts/check-public-safe.sh
grep -n "uk_memory_user_source" docs/data-schema.md
grep -n "ADR-058" docs/code-architecture.md docs/flow.md
! grep -n "다른 곳의 개인 지식을 들여오는 API" docs/code-architecture.md
```

- 모두 종료 코드 0. `grep` 둘은 줄이 나와야 하고 마지막 줄은 나오지 않아야 한다
- `node test/e2e/run.ts` 는 `./gradlew test` 뒤에 돌린다. 출력에 `기존 지식 가져오기` 시나리오가 통과로 나와야 한다

## 변경 파일

| 파일 | 변경 |
|---|---|
| `test/e2e/run.ts` | 수정 |
| `test/e2e/scenarios/memory-import.ts` | 신규 |
| `docs/code-architecture.md` | 수정 |
| `docs/data-schema.md` | 수정 |
| `docs/flow.md` | 수정 |
| `web/AGENTS.md` | 수정 |
| `docs/adr/ADR-058-기존-개인-지식-저장소는-주인이-검토한-묶음을-화면에서-올려-들여온다.md` | 수정 |
| `docs/adr/INDEX.md` | 수정 |
