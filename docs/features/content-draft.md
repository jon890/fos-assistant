# 사진 참조를 고정한 원고 compiler

typed 원고와 호출자가 검증한 원본 참조를 기존 `DraftInput`으로 변환한다.
compiler는 파일이나 외부 서비스를 읽지 않으며 기존 `render_draft/save_draft`의 자유 형식 입력을 바꾸지 않는다.
저장·권한 조회·미리보기 API·승인·job·화면 연동은 후속 구현이 담당한다.

## 정적 canonical 계약

`CanonicalDraftV1`은 `{schemaVersion:1,draftId,revision,conversationId,title,category,tags,blocks}`다.
draftId와 conversationId는 UUID 문자열이고 revision은 1 이상의 안전한 정수다.
제목은 Unicode code point 1자에서 100자까지, category는 1자에서 50자까지다.
tags는 30개까지이며 하나당 1자에서 30자까지, #과 쉼표를 금지한다.
trim과 소문자로 비교했을 때 중복 태그를 거절한다.
태그 값 자체는 자동 정규화하지 않는다.
blocks는 최대 500개다.

| block | 필드 | 규칙 |
| --- | --- | --- |
| paragraph | `{type:"paragraph",text}` | 한 줄. CR/LF 금지. 빈 문단 허용 |
| image | `{type:"image",assetId,caption?,observationRef?}` | assetId와 observationRef.id는 십진 문자열. caption은 300자까지. observationRef는 `{id,revision}`이며 같은 asset의 관찰만 허용 |
| sticker | `{type:"sticker",code}` | 기존 DSL의 영숫자·밑줄·하이픈 1~64자 |
| map | `{type:"map",name,address}` | 각각 1~200자. CR/LF와 `]`, name의 `|` 금지 |

알 수 없는 필드와 block 종류는 거절한다.
원본 사진의 중복 참조는 허용하고 블록 position으로 구별한다.
누락은 허용하되 호출자가 preview에 포함 ID와 빠진 ID의 subset을 표시하며 모두 사용했다고 말하지 않는다.
같은 사진이 두 번 나오면 업로드 자리 수는 2, 고유 asset 수는 1이다.
사진 50개, 스티커 30개, 지도 30개, 컴파일된 body 20,000 code point까지다.
caption은 이미지 바로 뒤의 실제 문단으로 컴파일한다.
photo_notes는 관찰 설명이며 미리보기에만 보이는 안내로 표시한다.
photo_notes는 canonical caption을 대체하지 않는다.
paragraph 또는 caption이 기존 사진·스티커·지도·기존 구성요소 예약 줄과 정확히 같으면 `CONTENT_DRAFT_RESERVED_LINE`로 거절한다.
임의 escape나 눈에 안 보이는 문자로 문장을 바꾸지 않는다.
기존 DSL의 parseBody와 validateDraft는 그 뜻을 유지한다.

### 순수 compiler와 포트

`hermes/connectors/naver-blog/src/content-draft.ts`가 위 타입과 엄격한 schema를 갖는다.
`hermes/connectors/naver-blog/src/content-compiler.ts`의 `compileContentDraft(draft:CanonicalDraftV1,assets:ResolvedAsset[]):CompiledSnapshotV1`은 순수 함수다.
FS, DB, HTTP, 날짜, random, Java 타입을 import하지 않는다.
`ResolvedAsset`는 `{assetId,sourceFingerprint,fileName,photoDir,ordinal,observationNote?}`다.
권한과 원본 지문, observationRef가 같은 asset의 관찰인지 검증하는 일은 호출자가 끝낸다.
컴파일러는 missing asset, 상이한 photoDir, 중복 asset 정의와 `.small.jpg` 이름을 거절한다.
sourceFingerprint는 SHA-256 소문자 64자리이며 ordinal은 1 이상의 안전한 정수다.
fileName은 기존 DSL이 받는 원본 이름이고 photoDir은 `..` 조각과 NUL이 없는 절대 경로다.
이미지 DSL 순번은 문서 이미지 자리의 1부터 시작하며 기존 화면 순번은 호출자가 preview 안내에 따로 표시한다.
fileName과 photoDir은 서버가 `ChatAttachment.storedName`과 설정에서 정한 원본 경로다.
모델 원고 스키마에는 두 칸이 없다.

`CompiledSnapshotV1`은 `{schemaVersion:1,compilerVersion:"content-compiler-v1",draftId,revision,canonicalBlocks,payload,photoNotes,assetManifest,payloadFingerprint,snapshotHash}`다.
payload는 기존 `DraftInput`의 다섯 칸이며 photo_dir는 사진이 없으면 null 대신 생략한다.
photoNotes는 이미지 자리 번호 문자열을 키로 하고 300자 설명을 값으로 한 map이다.
assetManifest는 이미지 블록 순서대로 `{position,assetId,sourceFingerprint,fileName}`를 담는다.
position은 canonicalBlocks의 0부터 시작하는 배열 인덱스다. 이미지 DSL의 1부터 시작하는 사진 순번과 구분한다.
title/category/tags/body와 원본 manifest, caption, 미리보기 설명, compiler/schema 버전이 해시에 들어간다.
입력 객체를 바꿔도 snapshot이 바뀌지 않으며 반환된 JSON의 모든 객체와 배열을 고정한다.
모델과 사용자 API 응답에는 내부 photoDir와 fileName을 내보내지 않는 것이 호출자의 계약이다.

해시는 SHA-256 소문자 64자리다.
UTF-8 compact JSON 배열을 쓰고 문자열을 trim하거나 Unicode normalize하지 않는다.
payloadFingerprint 입력은 `[title,category,tags,body,photo_dir??null]`이다.
snapshotHash 입력은 `[1,"content-compiler-v1",draftId,revision,canonicalBlocks,assetTuples,payloadFingerprint,noteTuples]`이다.
canonicalBlocks는 paragraph `["paragraph",text]`, image `["image",assetId,caption??null,observationRef?[id,revision]:null]`, sticker `["sticker",code]`, map `["map",name,address]` 순서의 배열이다.
assetTuples는 `[position,assetId,sourceFingerprint,fileName]` 배열이고 noteTuples는 자리 번호 오름차순 `[number,note]` 배열이다.
JSON은 non-ASCII를 UTF-8로 쓰며 `/`를 escape하지 않는다.
canonicalBlocks는 위 tuple 배열 자체를 불변 snapshot에 보관한다.
후속 worker는 전달된 canonicalBlocks, assetManifest, photoNotes, schemaVersion, compilerVersion과 재계산한 payloadFingerprint로 snapshotHash 전체를 다시 계산한다.
후속 Control Plane도 응답의 draftId/revision/canonicalBlocks와 요청 원고, assetManifest의 position/assetId/sourceFingerprint/fileName과 보낸 resolvedAssets, photoNotes와 보낸 observationNote의 대응을 검사한다.
응답 자체의 hash 일치만으로 수용하지 않는다.
Java 재계산과 TypeScript 계산은 같은 golden vector의 UTF-8 입력 바이트, compact JSON 문자열, 두 SHA-256 결과를 정확히 비교한다.
schema/compiler version, manifest 지문·asset ID·notes·canonicalBlocks만 변조한 입력도 후속 외부 호출 전에 거절해야 한다.
mount 경로가 달라졌으면 payloadFingerprint도 달라져 새 preview가 필요하다.

compiler 회귀와 공유 golden vector는 `hermes/connectors/naver-blog/tests/content-compiler.test.ts`와 `hermes/connectors/naver-blog/tests/fixtures/content-snapshot-v1.json`이 갖는다.
테스트는 고정 ResolvedAsset mock과 임시 디렉터리의 합성 사진만 사용한다.
다른 구현의 신규 파일이나 실제 provider 호출에 의존하지 않는다.
