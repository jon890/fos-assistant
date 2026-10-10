# Phase 01. 재현 fixture와 단계별 판정

**Execution profile**: standard

## 목표

합성 입력의 전달과 실제 내용 회수를 ID별로 구분한다.

**범위 외**: 실제 Hermes/provider/네이버 호출, backend/API/connector production 파일 수정.

## 컨텍스트

착수 조건: 독립 단계이므로 A/B/C의 신규 producer 머지는 필요 없다. 최신 main의 기존 계약을 기준으로 작업한다.
그 다음 최신 main을 fetch하여 새 lane은 그 head에서 시작하고 기존 브랜치는 일반 merge한다.
완료 phase는 그 구현 PR에서 삭제하고 index를 남은 phase로 갱신한 뒤, 합친 head의 저장소 root에서 아래 기본 검사를 실행한다.
설치된 planning 스킬 번들 위치를 SKILL_DIR로 해소하며 다음 명령의 종료 코드 0을 확인하기 전에는 해당 phase 구현을 시작하지 않는다.

```bash
# cwd: 저장소 root
python3 "$SKILL_DIR/scripts/verify_task.py" plan121-media-synthetic-fixtures
```

기본 검사는 남은 index의 파일 상태를 대조한다. 현재 phase가 생성할 파일을 후속 phase가 수정하는 것은 같은 계획의 producer 계약이며 빈 파일로 대체하지 않는다.

A/B와 병렬이다. C 소유의 fixture/helper/test만 수정하고 fake Hermes 서버와 운영 코드는 건드리지 않는다.

`docs/features/attachment.md`의 합성 검증 계약과 `docs/features/content-draft.md`의 화면과 품질 판정을 읽는다. #373/#399의 원문에 있던 10장 제한은 현재 #387의 30장 계약과 구분한다. CI의 실제 provider 비용은 측정하지 않는다.

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

선행 producer 미머지, 계약 불일치 또는 기본 verify_task.py의 종료 코드가 0이 아니면 PHASE_BLOCKED로 보고하고 착수를 막는다. --audit의 구조 검사와 구현 뒤 --staged는 기본 검사를 대체하지 않는다. 빈 파일 선생성, 파일 상태 표기 변경, 검사기 수정·무력화는 금지한다. 검사기 출력의 rebase 제안은 이 계획의 최신 main 일반 merge 규칙으로 처리한다.


## 작업 항목

### 1. 구현과 같은 변경의 회귀

test/support/media-pipeline-fixture.ts는 createMediaFixture(tempDir,count)를 제공한다. count는 1/10/11/25/30이고 고유 assetKey, ordinal, expectedMarker, expectedKoreanText, bytesSha256, format, expectedCoverage를 manifest에 기록한다. 기존 테스트용 이미지 생성 관례 를 쓰되 표식 생성은 신규 test/support/MediaFixtureGenerator.java의 JDK ImageIO와 Graphics2D가 담당한다. Korean glyph는 합성 문구에 필요한 고정 bitmap을 그려 OS font 의존을 피한다. Node helper는 JDK의 source-file 실행으로 임시 fixture를 생성한다. 새 패키지는 추가하지 않는다. JPEG/PNG/GIF는 JDK ImageIO로 실제 바이트를 생성하고 다시 decode하여 표식 픽셀을 대조한다. 포맷마다 reader/writer 존재와 바이트 서명, 길이/SHA를 확인한다.
표식은 asset마다 고유한 고정 격자와 한국어 bitmap이다. PNG/GIF는 손실 없는 격자 중심 픽셀을 정확히 비교하고 JPEG는 큰 흑백 셀의 중심 RGB 각 채널이 검정 <=64, 흰색 >=192인지 검사하여 bit를 복원한다. 기대 ID와 복원 ID가 다르거나 임계 사이 값이면 PASS로 만들지 않는다. decoder 성공만으로 markerRecovered=true를 기록하지 않는다.
EXIF1~8은 JPEG APP1 metadata와 방향별 기대 좌표를 별도로 비교하고 원본 픽셀과 표시 방향으로 재배치한 픽셀을 구분한다. ImageIO의 자동 방향 보정은 가정하지 않는다. 투명 PNG는 alpha를 비교한다. GIF는 서로 다른 표식의 두 프레임을 만들고 ImageReader.read(0)에서 첫 프레임 표식만 복원하며 coverage=FIRST_FRAME, frame=0을 기대한다.
WebP는 format/coverage=FIRST_FRAME 같은 metadata 사례만 만들고 실제 바이트 생성·decode·표식 회수는 하지 않는다. bytesSha256이 없으면 null, markerRecovered는 미설정이며 actuallyObserved=UNMEASURED다. WebP 첫 프레임 양성 PASS를 이 phase의 완료 기준에 넣지 않는다.
WebP 실제 첫 프레임 양성은 머지된 PR #394 runtime 변환 회귀와 후속 통합 검증이 담당한다. 별도 Hermes CI job에 설치된 Pillow는 이 Node unit job에 자동 공유되지 않는다. 새 패키지·코덱·production 변환기는 추가하지 않는다.
손상 JPEG/미지원/413은 실패 metadata case로 분리한다. 합성 문자열만 manifest에 넣어 사진 판독 성공으로 대체하지 않는다.

media-pipeline-evidence.ts는 PipelineEvidenceV1 사건을 검증한다. 사건은 {schemaVersion:1,runId,assetKey,stage,status,scope,byteLength?,sha256?,markerRecovered?,durationMs?,errorCode?}이다. stage는 uploaded/prepared/embedded/actuallyObserved/draftReferenced/validatedOriginals/editorUploaded/saved, status는 PASS/FAIL/UNMEASURED, scope는 SYNTHETIC/FAKE_HERMES/FAKE_CDP/REAL_PROVIDER/REAL_BROWSER다. 무단 ID, 다른 asset 교차 표식, 중복 사건, embedded만 있는 observed PASS를 거절한다. SYNTHETIC 픽셀 회수나 reducer 성공으로 REAL_PROVIDER/REAL_BROWSER 사건을 생성하거나 미측정을 PASS로 바꾸지 않는다. WebP metadata 사례의 actuallyObserved PASS와 markerRecovered=true도 거절한다.

media-pipeline-fixture.test.ts와 media-pipeline-evidence.test.ts는 manifest의 30개 1:1 대응, 작은 글씨 NEEDS_REVIEW, partial subset, unsupported/413/잘림의 구분, 비용 null을 PASS로 처리하지 않는 판정, 로그 whitelist를 검사한다. B의 파일과 A의 API는 mock하지도 import하지도 않는다. 후속 통합 QA가 이 helper를 사용한다.

### 2. 테스트 파일과 판정

- `test/unit/media-pipeline-fixture.test.ts`: 위 작업 항목의 정상·실패 입력과 기대 상태를 단언한다.
- `test/unit/media-pipeline-evidence.test.ts`: 위 작업 항목의 정상·실패 입력과 기대 상태를 단언한다.

## 검증

테스트 환경은 기존 test profile과 합성 fixture만 사용한다.
운영 환경 변수는 필요 없다.
Java는 backend/gradlew, Node는 22.18 이상, connector는 Bun 1.3.14를 사용한다.
MySQL 검사는 로컬 Docker가 필요하며 없으면 해당 phase를 완료로 표시하지 않는다.
아래 명령은 저장소 root에서 실행하며 지정한 회귀 assertion이 실행되어야 한다.

```bash
node --test test/unit/media-pipeline-fixture.test.ts test/unit/media-pipeline-evidence.test.ts
scripts/quality.sh check
```

기대값은 모든 명령의 종료 코드 0이다.

## 변경 파일

| 파일 | 변경 |
| --- | --- |
| `test/support/MediaFixtureGenerator.java` | 신규 |
| `test/support/media-pipeline-fixture.ts` | 신규 |
| `test/support/media-pipeline-evidence.ts` | 신규 |
| `test/unit/media-pipeline-fixture.test.ts` | 신규 |
| `test/unit/media-pipeline-evidence.test.ts` | 신규 |
