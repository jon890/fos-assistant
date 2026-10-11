# 사진 참조를 고정한 원고와 단계별 검증

covers: `hermes/connectors/naver-blog/src/content-draft.ts`, `hermes/connectors/naver-blog/src/content-compiler.ts`, `test/support/media-pipeline-evidence.ts`, `test/unit/media-pipeline-evidence.test.ts`

typed 원고와 호출자가 검증한 원본 참조를 기존 `DraftInput`으로 변환한다.
compiler는 파일이나 외부 서비스를 읽지 않으며 기존 `render_draft/save_draft`의 자유 형식 입력을 바꾸지 않는다.
저장·권한 조회·미리보기 API·승인·job·화면 연동은 후속 구현이 담당한다.

## 정적 canonical 계약

필드와 타입, 문자열 형식, 길이와 개수의 상한은 [`content-draft.ts`](../../hermes/connectors/naver-blog/src/content-draft.ts)의 schema 정의를 따른다.
문자열 길이는 Unicode code point로 센다.

| 대상 | 정의 위치 |
| --- | --- |
| canonical 원고 `CanonicalDraftV1` | `canonicalDraftV1Schema` |
| 원고 블록 `CanonicalBlock` | `canonicalBlockSchema`와 그 정의가 참조하는 문자열 검증 함수 |
| 호출자가 검증한 원본 참조 `ResolvedAsset` | `resolvedAssetSchema` |
| 컴파일 결과 `CompiledSnapshotV1` | `compiledSnapshotV1Schema`와 `canonicalBlockTupleSchema` |

trim과 소문자로 비교했을 때 중복 태그를 거절한다.
태그 값 자체는 자동 정규화하지 않는다.
알 수 없는 필드와 block 종류는 거절한다.
원본 사진의 중복 참조는 허용하고 블록 position으로 구별한다.
누락은 허용하되 호출자가 preview에 포함 ID와 빠진 ID의 subset을 표시하며 모두 사용했다고 말하지 않는다.
같은 사진이 두 번 나오면 업로드 자리 수는 2, 고유 asset 수는 1이다.
caption은 이미지 바로 뒤의 실제 문단으로 컴파일한다.
photo_notes는 관찰 설명이며 미리보기에만 보이는 안내로 표시한다.
photo_notes는 canonical caption을 대체하지 않는다.
paragraph 또는 caption이 기존 사진·스티커·지도·기존 구성요소 예약 줄과 정확히 같으면 `CONTENT_DRAFT_RESERVED_LINE`로 거절한다.
임의 escape나 눈에 안 보이는 문자로 문장을 바꾸지 않는다.
기존 DSL의 parseBody와 validateDraft는 그 뜻을 유지한다.

### 순수 compiler와 포트

[`content-compiler.ts`](../../hermes/connectors/naver-blog/src/content-compiler.ts)의 `compileContentDraft`는 순수 함수다.
FS, DB, HTTP, 날짜, random, Java 타입을 import하지 않는다.
권한과 원본 지문, observationRef가 같은 asset의 관찰인지 검증하는 일은 호출자가 끝낸다.
컴파일러는 missing asset, 상이한 photoDir, 중복 asset 정의와 `.small.jpg` 이름을 거절한다.
원본 이름과 경로의 허용 형식은 `resolvedAssetSchema`가 참조하는 `fileName`과 `photoDir` 정의를 따른다.
이미지 DSL 순번은 문서 이미지 자리의 1부터 시작하며 기존 화면 순번은 호출자가 preview 안내에 따로 표시한다.
fileName과 photoDir은 서버가 `ChatAttachment.storedName`과 설정에서 정한 원본 경로다.
모델 원고 스키마에는 두 칸이 없다.

payload는 기존 `DraftInput`으로 전달하며 photo_dir는 사진이 없으면 null 대신 생략한다.
photoNotes는 관찰 설명을 이미지 DSL 순번에 대응시킨다.
assetManifest는 이미지 블록 순서대로 원본 참조를 담는다.
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

### compile 전용 내부 transport

`POST /api/connectors/{id}/snapshot`은 Control Plane 서비스 Bearer로만 호출한다. <!-- ref-ignore: plugin이 middleware에서 처리하는 서비스 경로다 -->
대시보드 쿠키만 있는 요청은 거절한다. 요청의 정확한 키는 `profile`, `operation`, `args`이며 이번 구현은 `operation:"compile"`만 받는다.
args는 서버가 검증한 `canonicalDraft`와 `resolvedAssets`다. 모델 공개 도구에는 내부 경로와 private 도구를 등록하지 않는다.

connector.json의 snapshot은 `protocol`, `compile_tool`, `start_tool`, `status_tool`, `capabilities`의 다섯 키만 받는다.
protocol은 `content-snapshot-v1`, 도구 이름은 서로 다르고 공개 도구와 겹치지 않는다.
capabilities는 `compile`, `start`, `status` 세 boolean이며 이번 구현은 `{compile:true,start:false,status:false}`만 선언한다.
카탈로그의 별도 snapshot 칸과 성공 HTTP 응답은 같은 검증된 선언을 사용한다.
후속 Java catalog decoder도 이 다섯 키 계약을 따라야 한다. decoder와 DB, 승인, worker 시작·조회는 아직 구현하지 않았다.

dashboard는 기존 설치 소유 기록과 profile 표식, 주인 첨부·브라우저 환경 선택 규칙을 확인한 뒤 별도 stdio 자식을 띄운다.
실행 UID는 기존 dashboard 자식과 같고 권한을 올리지 않는다.
private process는 dashboard가 붙인 `--content-snapshot-operation compile` 인자로만 열린다.
공개 설치 args의 private flag와 manifest·profile의 `FOS_CONTENT_SNAPSHOT_OPERATION`, `FOS_CONTENT_SAVE_TICKET`, `FOS_CONTENT_SAVE_AUTHORIZE_URL` 선언은 거절한다.
부모 env 전체를 복사하지 않으며 별도 private env를 일반 MCP 설치에 남기지 않는다.
Naver 실행 선언의 `--no-env-file`로 Bun이 dashboard의 작업 디렉터리에서 `.env`를 자동으로 읽는 것도 차단한다.
실제 private 도구 목록이 선언한 compile 하나와 다르면 호출하지 않는다.
start·status와 미지원 operation은 자식 실행 전 409로 끝나며 일반 execute로 우회하지 않는다.

성공 응답은 `{ok:true,result,capabilities}`이고 result는 기존 `CompiledSnapshotV1`이다.
오류는 본문·경로·cause·stderr 없는 `{ok:false,error}`이며 compile timeout은 504다.
전체 MCP 왕복은 5초로 제한하고 기존 call·execute와 동시 호출 4개 한도를 공유한다. 자동 재전송은 없다.
adapter 자체는 원고와 인자·결과를 파일이나 로그에 남기지 않으며 자식 stderr를 버린다. HTTP 오류 응답에도 본문을 넣지 않는다.
Python MCP SDK는 잘못된 stdout을 `logger.exception`으로 기록하며 현재 그 본문은 가리지 않는다.
SDK와 프레임워크 진단 전체의 본문 가림은 아직 구현·검증되지 않았다.
renderer는 기존 첨부 검사에 사용하며 생성한 HTML은 저장하거나 반환하지 않는다.

| UTF-8 compact JSON 상한 | 검사 경계 |
| --- | --- |
| canonical 원고 65,536 bytes | Python 요청과 private compiler |
| 요청·응답 envelope 각각 524,288 bytes | 요청 스트림은 JSON 변환 전, MCP 결과와 HTTP 응답은 전달 전 |
| compiled JSON 262,144 bytes | payload·tuple·notes·manifest를 포함한 전체 직렬화 |
| 이미지 자리·manifest·resolvedAssets 각각 50개 | 같은 asset 반복은 자리마다 세고 asset 정의 중복은 거절 |
| photoDir 4,096 bytes·fileName 255 bytes | 내부 원본 참조 스키마와 Python 입력 |
| asset ID 양수 Long, 최대 19자리 | 정규형 십진 문자열이며 앞자리 0은 거절 |

기존 본문 20,000 code point 상한과 golden의 두 해시, caption·notes·반복 사진·미사용 asset 허용은 보존한다.
사진의 권한과 지문 대응은 호출자가 검증한다. compile의 파일 검사만으로 실제 provider의 사진 identity를 증명하지 않는다.
합성 stdio·HTTP 검사는 Python mcp 2.0.0과 TypeScript SDK 1.28.0으로 수행한다.

**본문 없는 진단 정책의 실제 구현과 운영 수락 전에는 본문 transport를 운영에서 활성화하지 않는다.**
후속 진단 정책의 운영 수락 검사는 잘못된 stdout의 SDK 로그 본문 노출 재현과 SDK validation 오류를 포함해야 한다.
이번 구현은 로컬 compile 경계이며 원고 최초 MCP 생성, snapshot 영속화, 승인과 실제 네이버 저장을 열지 않는다.

## 단계별 합성 판정

사진 전달과 내용 판독은 별도 증거로 판정한다.
합성 검사는 자체 manifest와 사건만 사용하며 실제 원고 저장이나 외부 서비스를 호출하지 않는다.


### 단계별 증거

`PipelineEvidenceV1`의 사건 정의와 입력 검사는 [판정기](../../test/support/media-pipeline-evidence.ts)가 갖는다.
전달된 ID, 실제 픽셀에서 회수한 ID, 원고에 포함한 ID를 따로 집계한다.
중복 사건과 다른 사진의 표식, 허용하지 않은 ID는 거절한다.
일부 사진만 포함한 원고는 포함·빠진 ID를 표시하며 전체 사진을 사용했다고 판정하지 않는다.

합성 픽셀 회수는 한국어 OCR 의미 판독을 증명하지 않는다.
작은 한국어 글씨는 사람이 확인해야 하는 상태로 남긴다.
실제 provider와 브라우저, 비용은 측정하지 않았으면 `UNMEASURED`와 null을 유지한다.
전달 사건이나 저장 사건만으로 내용 판독 성공을 만들지 않는다.
