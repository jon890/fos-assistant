# Phase 01. typed schema와 순수 compiler

**Execution profile**: deep

## 목표

원고 blocks와 원본 manifest를 같은 hash와 DSL로 변환한다.

**범위 외**: DB/API, 실제 원본 권한 조회, server.ts 등록과 bundle, 승인·job·UI.

## 컨텍스트

A/C와 병렬이다. 정적 문서만 공유하며 A의 미구현 파일을 import하거나 수정하지 않는다.

`docs/features/content-draft.md`의 정적 canonical 계약과 순수 compiler와 포트 절을 읽는다. 기존 hermes/connectors/naver-blog/src/draft.ts의 DraftInput/parseBody/validateDraft, src/render.ts의 photo_notes를 정의에서 대조한다. 신규 라이브러리는 없다.

**근거 문서**: `docs/features/attachment.md`의 사진별 관찰 계약, `docs/features/content-draft.md`의 해당 기능 절, `backend/docs/data-schema.md`의 후속 저장 설계.

새로 시작하는 lane은 선행 머지를 확인하고 최신 main을 fetch한 뒤 그 main에서 브랜치를 만든다.
기존 작업 브랜치는 최신 main을 fetch하고 일반 merge로 합친다. rebase와 force push로 이력을 다시 쓰지 않는다.
합친 head에서 해당 phase의 로컬 검사와 CI를 다시 확인하고 일반 push로 전달한다.

## 의도 메모

각 phase는 구현과 같은 phase의 회귀를 한 커밋에 담는다.
운영 코드 상한은 관심사 PR당 1,000 변경줄이다.
규모 예외 라벨은 달지 않는다.
초과하면 coordinator에게 phase 단위의 작동하는 경계를 보고해 PR을 나눈다.
모든 docs는 구현 전 합의로 표시돼 있다.
구현 PR에는 그 단계의 설계 절과 ADR만 함께 담고 다른 미구현 계획서는 섞지 않는다.
계획서는 해당 구현 PR에서 삭제한다.
문서 단독 push/PR, 운영 정보 복사, Hermes core·runtime·vector·graph·CMS·신규 의존 변경은 금지다.
독립 critic이 전체 계약을 승인하기 전에는 구현을 시작하지 않는다.


## Blocked 조건

선행 구현의 공개 계약이 문서와 다르거나 아직 머지되지 않았으면 `PHASE_BLOCKED: 선행 계약 미충족`을 보고한다.
선행 구현이 없어도 실행 가능한 독립 A/B/C는 각자의 mock과 계약만 사용한다.

## 작업 항목

### 1. 구현과 같은 변경의 회귀

content-draft.ts에 CanonicalDraftV1/ResolvedAsset/CompiledSnapshotV1 schema와 타입을 만든다. content-compiler.ts의 compileContentDraft(draft,assets)는 I/O와 runtime 맥락 없이 작동한다. mock asset 경로는 테스트 임시 디렉터리에서 만든 합성 값이며 운영 경로는 없다.

paragraph/caption의 CR/LF와 DSL 예약 줄은 명시적으로 거절한다. caption은 image 뒤 실제 text 문단으로 변환하고 observationNote는 preview 전용 안내다. 이미지 순번, 같은 asset 반복, omitted subset, Unicode code point 상한, 50사진/30태그/30스티커/30지도, 잘못된 원본명·서로 다른 photoDir를 검사한다. parseBody나 기존 DSL 문법을 바꾸지 않는다.

content-compiler.test.ts에서 30장 교차 문단 배치, 고정 hash, tags/문구/caption/순서/지문/notes/compiler version 변화, Java와 공유할 JSON 배열 golden vector, reserved-line 거절, 20,001자·51사진·중복 tag 실패를 검사한다. B 자체의 고정 ResolvedAsset mock만 쓰고 A나 C의 신규 파일은 읽지 않는다.

CompiledSnapshotV1에 snapshotHash의 canonicalBlocks tuple 전체를 불변 값으로 포함한다. worker가 assetManifest/photoNotes/schema/compiler와 전체 hash를 재계산할 수 있어야 한다. golden fixture는 compact JSON 문자열·UTF-8 바이트·payloadFingerprint·snapshotHash의 기대값을 함께 고정한다. manifest 지문·asset ID·notes·canonicalBlocks·버전을 단독 변경하면 hash가 달라지는 회귀를 추가한다.

### 2. 테스트 파일과 판정

- `hermes/connectors/naver-blog/tests/content-compiler.test.ts`: 위 작업 항목의 정상·실패 입력과 기대 상태를 단언한다.

## 검증

테스트 환경은 기존 test profile과 합성 fixture만 사용한다.
운영 환경 변수는 필요 없다.
Java는 backend/gradlew, Node는 22.18 이상, connector는 Bun 1.3.14를 사용한다.
MySQL 검사는 로컬 Docker가 필요하며 없으면 해당 phase를 완료로 표시하지 않는다.
아래 명령은 저장소 root에서 실행하며 지정한 회귀 assertion이 실행되어야 한다.

```bash
cd hermes/connectors/naver-blog && bun test ./tests/content-compiler.test.ts ./tests/draft.test.ts ./tests/render.test.ts
cd hermes/connectors/naver-blog && bun run typecheck
```

기대값은 모든 명령의 종료 코드 0이다.

## 변경 파일

| 파일 | 변경 |
| --- | --- |
| `hermes/connectors/naver-blog/src/content-draft.ts` | 신규 |
| `hermes/connectors/naver-blog/src/content-compiler.ts` | 신규 |
| `hermes/connectors/naver-blog/tests/content-compiler.test.ts` | 신규 |
| `hermes/connectors/naver-blog/tests/fixtures/content-snapshot-v1.json` | 신규 |
