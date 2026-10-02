# Phase 05. 신원 항목의 들이기를 연다

**Execution profile**: deep

## 목표

`identity` collection 의 항목을 들일 수 있게 한다. 민감 문서로만 받는다.
이 phase 는 이 plan 의 마지막이고, 아래 세 조건을 운영에서 관측한 뒤에만 시작한다.

**범위 외**: 실제 신원 문서를 들이는 일. 배포 뒤 주인이 `remote-verification.md` 의 순서로 한다.

## Blocked 조건

이 phase 를 맡기는 지시문에 아래 세 관측 결과가 **관측한 날짜와 함께** 적혀 있어야 한다. 하나라도 없거나 어긋나면 `PHASE_BLOCKED: 신원 항목을 여는 조건을 관측하지 못했다` 를 출력하고 멈춘다. 구현자가 스스로 운영에 붙어 확인하지 않는다. 확인 방법은 비공개 저장소 `fos-home-infra` 가 갖는다.

| 조건 | 지시문에 적혀 있어야 하는 관측 결과 |
| --- | --- |
| 민감 본문 암호화가 운영에서 검증됐다 | 지어낸 본문으로 만든 민감 문서의 `content` 가 `v1.` 으로 시작하고 넣은 본문을 담지 않으며 `content_key_id` 가 채워져 있다. 기동 로그에 평문으로 남은 민감 줄의 경고가 없다. 암호화 key 의 보관 사본이 있다 |
| 문서 읽기 경계가 검증됐다 | 서비스 읽기 경로가 토큰 없는 요청, 폐기한 토큰, 허용 목록에서 끈 사용자의 토큰에 모두 401 로 답한다. 민감 허용이 없는 토큰은 민감 문서에 404 로 답한다. 민감 허용이 있는 토큰은 200 이고 본문이 넣은 글이다 |
| `identity` collection 의 권한이 검증됐다 | `agent_memory_collection` 에 `identity` 줄이 없다. `service_token_collection` 의 `identity` 줄이 주인이 발급한 것뿐이고 폐기되지 않은 것의 수를 주인이 안다 |

코드로 볼 수 있는 조건도 본다.

- `backend/src/main/java/com/bifos/assistant/memory/application/MemoryImportService.java` 에 `IDENTITY_HELD` 가 없다 → `PHASE_BLOCKED: 들이기 API 가 없다` 출력 후 종료

## 컨텍스트

- `MemoryImportService` 의 항목 판정이 `collection` 이 `identity`(상수 `IDENTITY_COLLECTION`)인 항목을 `REJECTED`, `IDENTITY_HELD` 로 답한다. 그 검사는 `UNKNOWN_COLLECTION` 뒤, `RETRIEVAL_NOT_ALLOWED` 앞에 있다
- 묶는 스크립트 `scripts/brain-import/bundle.ts` 는 `--identity only` 로 신원 묶음을 따로 만든다. `scripts/brain-import/lib.ts` 의 `problemOf` 가 `identity` 항목이 민감 문서가 아니면 `IDENTITY_MUST_BE_SENSITIVE_DOCUMENT` 로 막는다. 스크립트는 고치지 않는다
- 화면 `web/src/components/memory/import-section.tsx` 가 `IDENTITY_HELD` 에 「신원 기록은 아직 가져올 수 없어요」 를 덧붙인다
- 에이전트의 실행은 그 에이전트가 받는 collection 의 항목만 받는다(ADR-053). 새 에이전트는 `core` 만 받는다. `identity` 를 에이전트에 주는 경로는 없다
- 민감 문서의 본문은 주인이 문서를 열 때와 민감 허용을 받은 서비스 토큰이 읽을 때만 복호화된다(ADR-057, ADR-056)

**근거 문서**: `docs/adr/ADR-058-기존-개인-지식-저장소는-주인이-검토한-묶음을-화면에서-올려-들여온다.md` 의 「신원 항목을 여는 조건」, `docs/adr/ADR-055-민감-memory-본문은-저장할-때-암호화하고-key-는-환경-변수로-받는다.md`

## 의도 메모

- 신원 항목을 일반 항목이나 `MEMORY` 로 들이는 길을 열지 않는다. backend 가 스크립트와 같은 조건을 한 번 더 본다. 묶음은 손으로도 만들 수 있다
- 설정 값으로 켜고 끄지 않는다. 조건을 관측한 뒤 코드로 한 번 연다. 켜는 값을 두면 조건을 보지 않고 켤 수 있다
- 암호화 key 가 없으면 신원 항목은 저장되지 않는다. 그 요청은 이미 `MEMORY_ENCRYPTION_UNAVAILABLE` 로 통째로 거절된다

## 작업 항목

### 1. `MemoryImportService` 의 신원 판정

`IDENTITY_HELD` 를 내던 검사를 바꾼다. `collection` 이 `identity` 인 항목은 `entryType` 이 `DOCUMENT` 이고 `sensitive` 가 참일 때만 지나간다. 아니면 `REJECTED`, `IDENTITY_MUST_BE_SENSITIVE_DOCUMENT` 다. `IDENTITY_HELD` 라는 글은 코드에서 지운다.

### 2. `web/src/components/memory/import-section.tsx`

까닭의 표에서 `IDENTITY_HELD` 줄을 지우고 `IDENTITY_MUST_BE_SENSITIVE_DOCUMENT` 에 「신원 기록은 민감한 문서로만 가져올 수 있어요」 를 덧붙인다.

### 3. 테스트

`backend/src/test/java/com/bifos/assistant/memory/MemoryImportTest.java`

| 입력 | 기대 |
| --- | --- |
| 기존의 `IDENTITY_HELD` 를 보던 테스트를 고친다. `identity` 의 민감 `DOCUMENT`(본문 `평문-표식-7391`)를 `commit` | `NEW`. `content` 가 `v1.` 으로 시작하고 표식을 담지 않는다. `content_key_id` 가 `test-1` 이다 |
| `identity` 의 일반 `DOCUMENT` | `REJECTED`, `IDENTITY_MUST_BE_SENSITIVE_DOCUMENT`. 저장되지 않는다 |
| `identity` 의 민감 `MEMORY` 와 민감 `SOURCE` | 둘 다 같은 거절이다 |
| 들인 신원 문서가 있을 때 `core` 만 받는 에이전트로 `ContextAssembler.assemble(dad, agentId)` | 글이 그 문서의 제목과 본문을 담지 않는다. 에이전트는 `ContextAssemblerTest` 처럼 저장소로 만든다 |
| 들인 신원 문서를 `MemoryController.readable()` 로 본다 | 목록에 없다 |

`backend/src/test/java/com/bifos/assistant/memory/MemoryImportEncryptionDisabledTest.java`

| 입력 | 기대 |
| --- | --- |
| `identity` 의 민감 문서를 `commit` | `MEMORY_ENCRYPTION_UNAVAILABLE`. `memory` 의 줄 수가 0 이다 |

`test/e2e/scenarios/memory-import.ts`: 「신원 항목은 거절한다」 step 을 「신원 항목은 민감 문서로만 들인다」 로 고친다. 일반 문서는 `REJECTED`, `IDENTITY_MUST_BE_SENSITIVE_DOCUMENT` 이고, 민감 문서는 `NEW` 로 들어와 `/memory-documents/{memoryId}` 의 `sensitive` 가 참이다. 들인 문서를 끝에서 지운다.

`test/browser/memory-import.spec.ts`: 「신원 기록은 아직 가져올 수 없다」 검사를 「신원 기록은 민감한 문서로만 가져온다」 로 고친다. 일반 문서를 올리면 「신원 기록은 민감한 문서로만 가져올 수 있어요」 가 보인다.

### 4. 문서

- `docs/adr/ADR-058-기존-개인-지식-저장소는-주인이-검토한-묶음을-화면에서-올려-들여온다.md`: `status` 줄을 `` `accepted` `` 만 남기고 고친다. 「항목의 결과」 표의 `REJECTED` 줄에서 「신원 항목인데 아직 열지 않았다」 를 「신원 항목인데 민감 문서가 아니다」 로 고친다
- `docs/adr/INDEX.md` 의 ADR-058 줄에서 「신원 항목의 들이기는 아직 구현 전이다」 를 지운다
- `docs/code-architecture.md` 의 「Memory」 「다음」 목록에서 「신원 항목의 들이기. …」 줄을 지우고, 「Memory」 절의 들이기 설명에 「`identity` 의 항목은 민감 문서로만 들인다」 를 더한다
- `docs/flow.md` 의 「기존 개인 지식을 들일 때」 의 「갈리는 지점」 표에서 신원 항목의 줄을 같은 뜻으로 고친다

## 검증

```bash
# cwd: 저장소 root
(cd backend && ./gradlew test --tests '*MemoryImportTest' --tests '*MemoryImportEncryptionDisabledTest' --tests '*ContextAssemblerTest')
(cd backend && ./gradlew test)
(cd backend && ./gradlew qualityCheck)
node test/e2e/run.ts
(cd web && pnpm lint && pnpm format:check && pnpm typecheck)
(cd web && pnpm test:browser memory-import.spec.ts)
scripts/check-public-safe.sh
scripts/quality.sh check
! git grep -n "IDENTITY_HELD" -- backend/src/main web/src
! grep -n "구현 전" docs/adr/ADR-058-기존-개인-지식-저장소는-주인이-검토한-묶음을-화면에서-올려-들여온다.md
```

- 모두 종료 코드 0. 마지막 두 줄은 일치하는 줄이 없어야 한다
- `node test/e2e/run.ts` 는 `./gradlew test` 뒤에 돌린다

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/memory/application/MemoryImportService.java` | 수정 |
| `web/src/components/memory/import-section.tsx` | 수정 |
| `backend/src/test/java/com/bifos/assistant/memory/MemoryImportTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/memory/MemoryImportEncryptionDisabledTest.java` | 수정 |
| `test/e2e/scenarios/memory-import.ts` | 수정 |
| `test/browser/memory-import.spec.ts` | 수정 |
| `docs/adr/ADR-058-기존-개인-지식-저장소는-주인이-검토한-묶음을-화면에서-올려-들여온다.md` | 수정 |
| `docs/adr/INDEX.md` | 수정 |
| `docs/code-architecture.md` | 수정 |
| `docs/flow.md` | 수정 |
