# Phase 01. 관찰 조회와 사용자 정정 REST

**Execution profile**: deep

## 목표

현재 main의 관찰 저장 서비스를 쓰는 조회 페이지와 대화 주인의 정정 경로를 구현한다.
MCP 연결과 같은 관심사 PR에 담고 운영 코드는 최종 합계 1,500 변경줄 이하로 유지한다.
실제 규모가 상한을 넘으면 REST와 MCP 각각 기능·회귀·해당 설계를 갖춘 PR로 분리한다. 계획 단독 PR은 만들지 않는다.

**범위 외**: 저장 서비스·crypto·DDL·삭제·캐시 수정, UI, provider 호출, 원고·snapshot·jobs·finance, 실계정 브라우저와 운영 변경.

## 컨텍스트

**근거 문서**: `docs/features/attachment.md`의 「관찰 API와 MCP 연결 설계」,
`backend/docs/code-architecture.md`의 「관찰 연결의 모듈 배치 설계」,
`backend/docs/data-schema.md`의 「media_observation와 media_observation_request」,
`backend/docs/adr/ADR-20261010-media-observation-storage.md`.

root/backend AGENTS.md를 읽는다. planning overlay는 없고 AGENTS.md가 경로·검증을 정한다.
PR411의 producer는 main에 있다. cache PR422의 머지는 선행 조건이 아니다.
과거 구현 전 저장 명세나 미커밋 원본을 producer로 삼지 않는다.
현재 관찰·요청 alias·crypto·삭제 경로는 읽기 전용이다.
착수 전 설치된 planning 번들 경로를 PLANNING_SKILL_DIR로 정하고 저장소 root에서
`python3 "${PLANNING_SKILL_DIR}/scripts/verify_task.py" plan178-media-observation-api`를 실행해 종료 코드 0을 확인한다.
이는 신규 파일이 아직 없는 구현 전 검사다. 구현 후 --audit와 구분한다.

| 실제 파일 | 기존 시그니처 |
| --- | --- |
| `backend/src/main/java/com/bifos/assistant/chat/application/MediaObservationService.java` | `List<MediaObservationView> list(CurrentUser user, Long conversationId, String afterAssetId, int limit)` |
| 같은 파일 | `MediaObservationView record(CurrentUser user, Long conversationId, Long attachmentId, long expectedRevision, UUID requestId, MediaObservationInput input, ObservationProvenance source)` |
| `backend/src/main/java/com/bifos/assistant/chat/application/ConversationAccess.java` | `Long requireOwnId(CurrentUser user, UUID publicId)` |
| `backend/src/main/java/com/bifos/assistant/shared/auth/CurrentUser.java` | `(Long id,String email,String displayName,Long groupId,UserRole role)` |
| `backend/src/main/java/com/bifos/assistant/chat/application/model/MediaObservationInput.java` | `(ObservationStatus status,String summary,List<Claim> claims,List<String> uncertainties,Coverage coverage,Evidence evidence,String errorCode)` |
| `backend/src/main/java/com/bifos/assistant/chat/application/model/ObservationProvenance.java` | `(ObservationProvenanceKind kind,Long executionId,String provider,String providerVersion,String model,String modelVersion,int schemaVersion,String promptVersion,Instant observedAt)` |
| `backend/src/main/java/com/bifos/assistant/chat/application/model/MediaObservationView.java` | `(String assetId,int ordinal,String sourceFingerprint,Long revision,ObservationStatus status,MediaObservationInput observation,ObservationProvenance provenance,Instant expiresAt,String errorCode)` |

list의 기존 limit은 1~100이며 sent 첨부·cursor·소유·원본 지문과 응답 직전 상태를 검사한다.
record는 트랜잭션 밖에서 진입한다. 외부 트랜잭션이나 이중 mutation.run으로 감싸지 않는다.
CAS·UUID 재시도·USER 보호는 서비스에 맡긴다.

## 의도 메모

새 GET은 최대 30항목·본문 합계 32KiB이며 기존 서비스의 100개 제한은 바꾸지 않는다.
PUT은 사람의 정정만 저장하며 모델이 USER 정정을 만드는 경로를 열지 않는다.
본문 검사와 페이지 예산은 별도 application 소비자에 두어 presentation끼리 import하지 않는다.
새 의존·DB 표·비동기 job은 필요 없다.

## Blocked 조건

실제 main 시그니처가 표와 다르거나 기본 verify_task.py가 0이 아니거나,
새 파일이 이미 있거나 공유 파일에 조정하지 않은 변경이 있으면 PHASE_BLOCKED를 보고한다.
빈 파일 선생성, --audit로 구현 전 검사 대체, 검사기 변경과 파일 상태 표기로 우회하는 것은 금지한다.
독립 critic은 코디네이터가 별도 배정한다. 승인 전 제품 소스를 구현하지 않는다.

## 작업 항목

### 1. 페이지와 엄격한 제출 본문

새 `MediaObservationPages.list(CurrentUser user, Long conversationId, String afterAssetId, int limit)`는
`MediaObservationPage(List<MediaObservationView> items,String nextAfterAssetId)`를 반환한다.
Pages와 InputReader는 실제 @Service와 constructor 주입으로 등록한다. 기존 ObjectMapper와 저장 서비스만 주입하고 저장을 외부 트랜잭션으로 감싸지 않는다.
limit은 1~30이며 기본값은 호출자가 30으로 정한다. 저장 list에는 limit+1을 넘긴다.
선두부터 서버 저장형 observation JSON의 UTF-8 합이 32,768bytes 이하인 항목만 반환한다.
메타데이터는 본문 예산 밖이다. 본문을 자르거나 빠진 항목을 cursor로 건너뛰지 않는다.
다음 항목이 있으면 실제 반환한 마지막 ID를 cursor로 낸다. 끝은 null이다.
단일 본문은 기존 MAX_BODY_BYTES 이내이므로 첫 항목에서 진행하지 못하는 경우를 만들지 않는다.

새 `MediaObservationInputReader.read(JsonNode observation, ObservationProvenance source)`를 만든다.
최상위는 status/summary/claims/uncertainties/coverage/errorCode만 받고 evidence는 받지 않는다.
claim은 kind/text/confidence/evidence, coverage는 mode/region/frame, region은 x/y/width/height만 받는다.
모르는 중첩 칸과 형을 거절한 뒤 기존 input으로 변환하고 input.validate(source)를 적용한다.
정수는 isIntegralNumber와 범위 검사, 영역 숫자는 유한성·실제 숫자 노드 검사를 한다.
summary/errorCode/region/frame은 선택값으로 null도 허용한다. 필수 status와 필요한 coverage는 null을 거절한다.
claims/uncertainties의 생략·null은 기존 input과 같은 빈 배열, claim.evidence는 비어 있지 않은 문자열 배열이다.
본문별 code point·개수·coverage·상태 규칙은 feature 표와 기존 검증을 모두 따른다.
FAILED evidence는 null, 그 밖은 서버가 source.kind와 USER_CORRECTION 또는 origin ID 문자열을 만든다.
최종 저장형 JSON32KiB를 확인하며 변환 실패는 원문이나 cause를 싣지 않는 고정 VALIDATION_FAILED다.
claim 수나 총 문자열 bytes로 원문의 과도한 중간 객체 생성을 막고 사용자 본문을 toString에 싣지 않는다.

### 2. REST controller와 DTO

`MediaObservationController`를 기존 AttachmentController와 같은 인증 경계에 추가한다.
기준 경로는 `/api/v1/chat/conversations/{conversationId}/media-observations`다.
GET은 UUID path와 afterAssetId/limit을 받아 CurrentUserProvider.require와 requireOwnId로 대화 ID를 구한다.
새 pages.list를 호출하고 ChatDtos.MediaObservationPageResponse로 반환한다.
PUT `/{assetId}`는 양의 Long 십진 문자열과 JSON의 expectedRevision/requestId/observation만 받는다.
expectedRevision은 0 이상 JSON 정수이고 requestId는 정규 8-4-4-4-12 UUID다. 축약 UUID와 overflow는 거절한다.
본문 최상위에도 모르는 칸을 거절한다. 자동 바인딩이 모르는 칸을 무시하게 두지 않는다.
PUT source는 USER_CORRECTION이며 실행/provider/model/각 버전/observedAt은 null,
schemaVersion=1, promptVersion=media-observation-v1이다.
SUCCEEDED/PARTIAL과 USER claim만 허용한 뒤 기존 record를 호출한다.
HttpMessageNotReadableException은 controller에 한정한 예외 처리로 고정400을 반환해 공통500 로그에 원문을 남기지 않는다.

ChatDtos에는 MediaObservationCorrectionRequest, MediaObservationPageResponse, MediaObservationResponse를 둔다.
controller에는 nested record를 만들지 않는다. 입력 DTO의 toString에는 observation을 넣지 않는다.
응답은 feature의 기존 view 필드와 sourceAssurance를 갖는다.
sourceAssurance는 observation이 null이면 provenance가 남아 있어도 null이다.
본문이 있을 때 MODEL은 MODEL_UNVERIFIED, USER는 USER_CORRECTION이다.
원본 교체·복호화/본문 검증 실패의 NEEDS_REVIEW/CONTENT_UNAVAILABLE과
본문이 남은 NEEDS_REVIEW/ANALYSIS_STALE을 구분한다. 후자는 kind에 따른 assurance를 유지한다.
모델 본문·coverage는 선언과 추론이며 verified provider나 native 원본의 실제 영역 확인 증명이 아니다.
기존 application View/Input/Provenance는 바꾸지 않는다.
기존 ApiException 응답을 유지하며 충돌은 message=revision=N, 암호화 불가는 MEDIA_ENCRYPTION_UNAVAILABLE이다.

### 3. 이 phase의 회귀

`MediaObservationControllerTest`는 실제 HTTP 인증·UUID 변환·저장 서비스·테스트용 암호화를 쓴다.
기존 BackendIntegrationTest/IntegrationTestDoubles 컨텍스트와 TestClock을 사용한다.
시험별 @MockitoBean/@MockitoSpyBean/@Import로 공용 컨텍스트 품질 규칙을 우회하지 않는다.
Memory419의 공용 Hibernate inspector/MemorySearchSqlProbe는 보존한다. 추가 공용 대역이 필요하면 소유자와 조정하고 PHASE_BLOCKED를 보고한다.
sent 사진 GET, USER PUT, 동일 UUID 재송의 동일 revision/observedAt, 본문 다른 재송과 오래된 CAS의 409를 확인한다.
동일 CAS의 병렬 PUT은 하나만 성공하고 observation과 alias가 수락한 내용에만 연결되어야 한다.
다른 사용자/대화/cursor·없는 ID·미전송·삭제 요청·만료·원본 교체·암호화 불가를 확인한다.
USER/MODEL 각각 원본 바이트 교체, 저장 암호문 손상·복호화 실패, 복호화 가능한 본문의 검증 실패를 fixture로 만든다.
실제 GET에서 NEEDS_REVIEW/CONTENT_UNAVAILABLE, observation=null, 남은 provenance와 sourceAssurance=null을 확인한다.
TestClock으로 PROCESSING을 stale로 만들어 본문이 남은 NEEDS_REVIEW/ANALYSIS_STALE은 kind에 따른 assurance인지 대조한다.
저장·crypto 운영 코드를 바꾸지 않고 기존 repository/테스트용 암호화 fixture로 실패 조건을 만든다.
현재 USER를 MODEL이 바꾸지 못하는 기존 서비스 회귀도 함께 실행한다.
모르는 중첩 칸·형 혼동·UUID 축약·overflow·32KiB 초과·FAILED/coverage/claim 불일치를400으로 확인한다.
`MediaObservationPagesTest`는 서로 다른 길이와 Unicode/escape 본문을 쓴다.
cursor를 끝까지 따라간 ID를 원래 sent 순서와 대조해 예산 경계에 중복·누락이 없고 삭제 순번도 유지되는지 검증한다.
구현 코드를 복제하거나 총 개수를 하드코딩한 기대값으로 검증하지 않는다.
`MediaObservationInputReaderTest`는 생략/null·FAILED·모르는 중첩 칸과 모델 evidence 주입 거절을 확인한다.
ListAppender로 실패 로그와 DTO 문자열에 합성 OCR 비밀 표식이 없는지도 확인한다.
MediaObservationControllerTest에서 REST PUT의 malformed/truncated JSON도 실제 인증 HTTP로 보내 고정400, 저장 revision/alias 증가0,
공통 예외 로그·응답·DTO 문자열의 합성 OCR 표식0을 확인한다. 직접 reader 호출로 이 경계를 대신하지 않는다.

## 검증

backend 설정의 JDK와 Gradle, 기존 test profile의 테스트용 KEK를 쓴다. 운영 환경값은 필요 없다.
기존 package-private ObservationFixture의 chat package 안에서 sent 첨부·암호화 fixture를 재사용하며 HTTP fixture는 이 phase 안에서 만든다.
각 명령은 저장소 root에서 실행한다.

```bash
cd backend && ./gradlew test --tests '*MediaObservationControllerTest' --tests '*MediaObservationPagesTest' --tests '*MediaObservationInputReaderTest' --tests '*MediaObservationServiceTest' --tests '*MediaObservationLockTest'
cd backend && ./gradlew qualityCheck
git diff --check
```

기대값은 모든 명령의 종료 코드0이다. 운영 코드 변경 목표는500줄 이내이며 최종 PR 상한은1,500줄이다.
시험과 문서는 scripts/pr-size.mjs의 별도 구분으로 집계한다.

## 변경 파일

| 파일 | 변경 |
| --- | --- |
| `backend/src/main/java/com/bifos/assistant/chat/presentation/MediaObservationController.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/presentation/ChatDtos.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/application/MediaObservationPages.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/application/MediaObservationInputReader.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/application/model/MediaObservationPage.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/chat/MediaObservationControllerTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/chat/MediaObservationPagesTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/chat/MediaObservationInputReaderTest.java` | 신규 |
