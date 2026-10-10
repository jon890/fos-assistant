# Phase 01. 완료 결과 재사용과 영속 alias

**Execution profile**: deep

## 목표

현재 최신 MODEL 완료 결과를 같은 분석 조건에서 재사용하고 새 UUID alias만 원자적으로 저장한다.
관찰 ID, revision, 본문, 출처와 서버 관측 시각은 최초 결과를 유지한다.

**범위 외**: API/MCP/UI, 실제 모델 호출, 원고·approval·job, 운영·실계정, Hermes core, 새 의존성과 다른 저장소 변경이다.
scanner의 전체 정리 후보 조회 최적화 P3도 이 관심사에서 제외한다.

## 컨텍스트

**근거 문서**: `docs/features/attachment.md`의 「관찰 저장과 사용자 정정」과 「완료 결과 재사용 설계」,
`backend/docs/adr/ADR-20261011-media-observation-cache.md`, `backend/docs/data-schema.md`의 「media_observation와 media_observation_request」다.
루트와 backend의 AGENTS를 함께 따른다.

PR411의 실제 main producer를 사용한다.
`MediaObservationService.record(CurrentUser,Long,Long,long,UUID,MediaObservationInput,ObservationProvenance)`와 `list(CurrentUser,Long,String,int)`는 이미 있다.
`MediaObservationRequest`와 `findByAttachmentIdAndRequestId`는 영속 alias를 제공한다.
`MediaObservationRepository.findFirstByAttachmentIdOrderByRevisionDesc`로 최신 한 행을 얻는다.
본문은 `MediaObservationBodies.open/seal`과 기존 `TextCipher`를 사용한다.
`ChatContentMutationCoordinator.run`은 사용자·대화·첨부 잠금, READ_COMMITTED의 새 TX와 rollback을 제공한다.
서비스의 공개 시그니처와 생성자 의존성은 유지한다.

코디네이터가 설계와 계획을 로컬 checkpoint로 먼저 커밋한 뒤 제품 executor를 배정한다.
checkpoint는 같은 최종 구현 브랜치의 이력으로 전달하며 계획만 PR을 열지 않는다.
착수 시 최신 main을 fetch하고 PR411과 PR414 머지, 위 정의와 `scripts/pr-size.mjs`의 실제 상한을 확인한다.
새 작업은 그 main에서 시작하고 기존 작업 브랜치는 일반 merge로 합친다.
rebase와 force push는 하지 않는다.
합친 head에서 기본 검사를 실행하고 종료 0을 확인한 뒤 제품 파일을 수정한다.
설치된 planning 번들의 실제 경로를 `SKILL_DIR`로 해소한다.
새 ADR이 Git 추적 상태인지 아래 명령으로 먼저 확인한다. 실패하면 구현을 시작하지 않는다.

```bash
# cwd: 저장소 root
git ls-files --error-unmatch backend/docs/adr/ADR-20261011-media-observation-cache.md
python3 "$SKILL_DIR/scripts/verify_task.py" plan137-media-observation-cache
```

## 의도 메모

완료 저장과 재사용을 구분한다. cache 성공은 provider 성공이나 분석 생략의 증거가 아니다.
관심사 하나의 작동 구현, DDL, 문서와 같은 변경의 회귀를 한 PR에 담는다.
이 phase는 검증을 마친 관심사 커밋 하나이며, phase와 PR을 일대일로 강제하는 규칙은 아니다.
규모 상한과 최종 인수 조건은 delivery가 갖는다.

## Blocked 조건

PR411 producer 미머지, 실제 정의 불일치 또는 기본 verify의 종료 코드가 0이 아니면 `PHASE_BLOCKED`로 보고한다.
설계 checkpoint가 없거나 새 ADR이 untracked여도 같은 방식으로 보고한다.
독립 critic의 지적 반영을 코디네이터가 확인하기 전에는 제품 executor를 시작하지 않는다.
기본 검사를 `--audit`로 대신하거나 기존 producer를 신규로 적어 통과시키지 않는다.
glob, 빈 파일 선생성, 검사기 수정과 무력화도 금지한다.
마이그레이션 예약 경로가 이미 있으면 아직 적용하지 않은 신규 파일에 한해 실제 UTC 시각을 다시 정하고 설계 선언과 변경 표를 함께 갱신한다.

## 작업 항목

### 1. 분석 key와 nullable 열

`MediaObservationAnalysisKey`를 `chat.application`에 순수 helper로 추가한다.
정적 `compute(ObjectMapper,String,ObservationProvenance,MediaObservationInput.Coverage)`가 소문자 hex key를 반환한다.
호출자는 기존 input 검증을 먼저 수행하며 서비스가 얻은 원본 지문만 넘긴다.
helper는 고정 순서의 배열을 만들며 객체 map이나 input 전체를 직렬화하지 않는다.
coverage의 배열, null, 문자열과 좌표 정규화는 ADR과 일치시킨다.
helper의 writer는 pretty printing을 끄고 공백 없는 JSON을 만든다. 전역 mapper 설정은 바꾸지 않는다.
새 ObjectMapper 빈이나 새 의존성을 만들지 않는다.

`MediaObservation`에 `@Column(name="analysis_key",columnDefinition="CHAR(64)") String analysisKey`를 추가한다.
기존 생성자의 `Instant now` 직전에 `String analysisKey`를 넣고 service와 `MediaObservationBodiesTest.copied`의 실제 두 호출을 갱신한다.
신규 MODEL 완료만 계산한 값을 넘긴다. USER, PROCESSING과 FAILED는 null을 넘긴다.
기존 alias hash와 `MediaObservationInput`의 `@JsonPropertyOrder`를 바꾸지 않는다.

DDL은 `backend/src/main/resources/db/migration/V20261010162744__media_observation_analysis_key.sql` 하나다.
예약한 실제 UTC 작성 시각이며 `ALTER TABLE media_observation ADD COLUMN analysis_key CHAR(64) NULL`만 수행한다.
기존 적용 migration을 수정하지 않고 DML, 새 UNIQUE/FK/index와 backfill을 추가하지 않는다.
구행 null은 miss이며 본문을 읽어 자동으로 key를 보충하지 않는다.

### 2. record의 순서와 본문 검증

기존 `requestHash = Sha256.hex(json.writeValueAsString(Arrays.asList(expectedRevision,input,source.kind(),source.executionId(),source.provider(),source.providerVersion(),source.model(),source.modelVersion(),source.schemaVersion(),source.promptVersion())))`를 그대로 보존한다.
분석 key는 별도로 계산하며 UUID 재시도에 사용하지 않는다.
서버의 observedAt은 두 hash 모두에서 제외된다.

coordinator callback에서 아래 순서를 유지한다.

1. `requireReadable`, 실제 `fingerprint`, 최신 행 조회와 `checkStored`로 원본·소유·삭제·만료를 검사한다.
2. 기존 UUID alias가 있으면 requestHash를 비교하고 그 alias의 행만 읽는다. 이 경로는 cache와 현재 CAS에 앞서 끝난다.
3. 새 UUID는 expectedRevision과 현재 revision이 같아야 한다. 현재 USER에 새 MODEL이면 본문을 열기 전 409다.
4. 새 제출과 최신 행이 모두 MODEL 완료이고 후보의 body_key_id가 null이 아닐 때만 analysis key와 본문을 검사한다.
5. hit이면 기존 행으로 `MediaObservationRequest`만 `saveAndFlush`한다. miss이면 암호화 필수 검사를 거쳐 revision을 하나 늘리고 본문과 alias를 함께 저장한다.

기존 `view`의 복호화·파싱·크기·input validation·상태 일치를 `openValidatedBody(MediaObservation,Long)` private 함수로 추출한다.
실제 행 출처를 사용하고 실패하면 Optional.empty를 반환한다.
공용 함수는 평문 호환 읽기를 보존하며, cache 분기에서만 별도로 `row.bodyKeyId() != null`을 요구한다.
JSON/ApiException과 기존 crypto 실패만 본문 없음으로 처리하며 DataAccessException 등 SQL 실패를 catch하지 않는다.
`view`와 hit 판정이 같은 검증 함수를 사용하게 한다.
표시용 NEEDS_REVIEW/ANALYSIS_STALE나 CONTENT_UNAVAILABLE를 재사용 가능 상태로 쓰지 않는다.
후보의 decoded coverage와 저장 provenance로 key를 다시 계산하여 저장 key 및 새 제출 key와 모두 같은지 확인한다.
본문 실패나 손상된 key는 miss다. 새 암호화 저장까지 실패하면 alias도 쓰지 않는다.
hit에는 `requireEncryption/seal`을 호출하지 않으며 이미 저장된 key로 복호화할 수 있으면 암호화 쓰기 설정이 꺼져 있어도 alias를 허용한다.

hit는 요청의 summary·claim·uncertainty·완료 상태가 달라도 기존 결과를 반환한다.
요청의 evidence는 새 실행 출처에 맞게 검증하되 반환된 evidence와 executionId는 기존 결과의 값이다.
같은 UUID를 재시도할 때에는 반환 본문이 아니라 그 UUID의 최초 제출 내용을 보낸다.
alias 저장 SQL 실패는 같은 TX를 롤백한다. 성공 결과나 새 revision fallback으로 바꾸지 않는다.

### 3. 응답 직전 재검사

`record`에 list와 같은 `@Transactional(propagation=NOT_SUPPORTED)`를 적용한다.
기존 직접 생성 fixture와 Spring proxy 양쪽을 검사하여 외부 TX의 오래된 스냅샷을 사용하지 않는지 확인한다.
callback 결과 후 `requireReadable`로 현재 상태를 검사하고 새로 연 원본 스트림의 지문을 계산한다.
스트림을 끝까지 읽고 닫은 다음 `requireReadable`과 그 내부의 최신 SQL 접근 검사를 다시 수행한다.
그 다음 현재 clock으로 첨부와 반환 관찰의 expiresAt을 검사한다.
접근과 만료 검사를 통과한 뒤 계산한 지문을 반환 지문과 비교한다.
현재 owner 불일치는 404, 삭제·만료·없어진 원본은 410, 같은 크기 원본 교체는 현재 revision을 담은 409다.
SQL 장애는 전파한다. 응답 검사가 실패해도 이미 커밋한 alias는 보존한다.

`list`의 마지막 응답 검사도 현재 원본을 다시 읽는다.
새 스트림을 읽고 닫은 뒤 최신 SQL 접근 검사와 현재 clock 만료 검사를 반복한다.
현재 원본 변경은 기존 목록 계약대로 최신 지문과 본문 없는 NEEDS_REVIEW/CONTENT_UNAVAILABLE로 반환한다.
삭제·만료·소유 불일치는 기존 blocked 모양을 유지한다.
원본 스트림은 성공, mismatch, I/O와 SQL 실패 모두에서 닫힌다.
이 검사는 상태를 다시 확인한 시점까지 보장하며 마지막 검사 뒤 외부 파일 변경까지 잠그는 장치를 추가하지 않는다.

### 4. 같은 변경의 테스트

`MediaObservationAnalysisKeyTest`는 제품 helper로 기대값을 만들지 않고 아래 고정 JSON과 digest를 literal로 단언한다.
첫 vector는 nullable version, 둘째는 CROP 좌표의 정규화를 확인한다.

```text
["aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",1,"media-observation-v1","provider",null,"model","model-v3",["ORIGINAL",null,null]]
aa1b156a69c9ef357fa56ae208545c1a5f9fbf2001a60992906327af48351b7f
["aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",1,"media-observation-v1","provider",null,"model","model-v3",["CROP",["0","0.25","0.5","0.5"],null]]
f37585120842ab363a07509706726b48e209dafe58d197d0ed5db562dfb8a953
```

원본·schema·prompt·provider·providerVersion·model·modelVersion·coverage를 각각 바꾸면 key가 달라진다.
executionId/observedAt 변화는 key가 같다.
null/빈 문자열, 공백·대소문자, ORIGINAL/OVERVIEW/FIRST_FRAME/CROP, -0.0/0.0와 작은 좌표 차이를 확인한다.
서비스는 현재 schema 1과 prompt media-observation-v1만 허용하므로 그 버전 변경은 400이고 새 행을 만들지 않는다.
지원하지 않는 버전을 서비스에 허용하여 key 회귀를 통과시키지 않는다.

`MediaObservationCacheTest`를 `MediaObservationRequestTest`의 하위 클래스로 만든다.
`MediaObservationLockTest`가 새 cache test를 상속하게 하여 기존 `MediaObservationMysqlLockTest`가 같은 cache 회귀를 실제 MySQL에서도 실행한다.
cache 서비스 회귀는 아래 표를 단언한다.

| 입력·실패 | 기대값 |
| --- | --- |
| 동일 조건의 새 UUID, 다른 summary·executionId | 관찰 1개, alias 2개, 기존 revision·본문·provenance·observedAt 유지 |
| 완료 세 상태의 후보와 제출 조합 | 기존 완료 결과 재사용. 상태도 후보의 값 유지 |
| 각 허용 분석 조건 변경 | 새 revision. unsupported schema/prompt는 위 400 |
| PROCESSING/FAILED/USER/구행 null key | 재사용 없음. USER에 새 MODEL은 409 |
| 과거 완료 key가 같지만 최신 조건이 다름 | 과거 검색 없이 새 revision |
| 같은 UUID 동일·상이 requestHash | 최초 결과 또는 409. 관찰과 alias 개수 고정 |
| 새 서비스 인스턴스의 cache alias 재시도 | 최초 연결된 관찰·시각 복원 |
| 본문 null, 누락 key, AAD/암호문 변조, JSON·크기·validation·상태 불일치, 손상된 analysis key | miss 후 새 암호화 revision. 별도 암호화 실패면 503과 rollback |
| 유효 평문 JSON과 올바른 analysis key, body_key_id만 null | 새 UUID는 miss 뒤 새 암호화 revision. 목록과 기존 UUID 재시도는 평문 호환 유지 |
| 최신 조회·복호화 내부 SQL·alias 저장 SQL 실패 | 요청 실패. alias 저장 실패 시 관찰·alias 증가 없음 |
| 응답 재검사 SQL 실패 | 실패 전파. 커밋한 alias는 남고 같은 UUID 재시도 가능 |
| 동일 크기의 원본 교체, 크기 변화, 원본 없음 | 새 요청과 UUID 재시도 차단. 교체는 409, 크기·없음은 410 |
| 사용자·대화 변경, 삭제 요청, 만료 | 본문 미노출. 목록과 쓰기 기존 오류 계약 유지 |
| 다른 사용자의 같은 바이트 첨부 | 별도의 관찰이며 alias와 key 후보를 공유하지 않음 |

`MediaObservationLockTest`는 현재 완료 revision 1에 두 UUID를 동시에 제출하여 성공 2개, 관찰 1개, alias 총 3개를 단언한다.
같은 expectedRevision에서 실제 다른 분석 조건의 새 결과 경합은 성공 1개/409 1개, 관찰 증가 1개, alias 증가 1개다.
기존 최초 기록 경합도 CAS가 먼저이므로 한 승자/한 409를 유지한다.
응답 직전 장벽에서 삭제·만료·owner 변경과 원본 교체를 각각 발생시키고 본문 응답을 막는다.
응답용 두 번째 스트림을 연 뒤 읽기가 끝나기 전에 멈추는 장벽도 둔다.
그 동안 삭제 요청, 대화 주인 변경과 업로더 변경을 각각 커밋하고 읽기를 재개한다.
cache hit, 과거 UUID 재시도와 새 revision의 record 및 list가 본문을 반환하지 않는지 단언한다.
이 회귀는 cache/lock 상속 경로에 넣어 실제 MySQL에서도 실행한다.
기존 실제 사용자 행 잠금 장벽과 UUID 동일 요청 경합을 보존한다.

기존 no-cache 기대값을 무작정 바꾸지 않는다.
`MediaObservationServiceTest`, `MediaObservationRequestTest`, `MediaObservationBodiesTest`에서 새 revision을 의도한 두 번째 제출은 다른 유효 조건을 사용한다.
이렇게 기존 과거 alias, USER, crypto와 processing 전이 검증을 유지한다.
UUID hash의 과거 호환은 독립 고정 literal vector와 저장 alias fixture로 별도 확인한다.
현재 helper로 계산한 hash만 다시 비교하는 테스트로 대신하지 않는다.
현재 input의 직렬화 순서에 대한 원본 UUID vector는 아래와 같다.
이 literal을 UTF-8로 해석하며 schema나 본문 정규화 helper에서 기대값을 만들지 않는다.

```text
[0,{"status":"SUCCEEDED","summary":"관찰 표식","claims":[],"uncertainties":[],"coverage":{"mode":"ORIGINAL","region":null,"frame":null},"evidence":{"kind":"MODEL_RESULT","reference":"123"},"errorCode":null},"MODEL_RESULT",123,"provider","provider-v2","model","model-v3",1,"media-observation-v1"]
773b2831653352837aa15a2ac9a8083629014e353d72a46612f7ddbf51a69b33
```

`MediaObservationMigrationTest.createsLongBodyAndConversationRevisionIndexWithoutCacheColumns`는 nullable CHAR(64) 존재와 기존 색인 보존을 검증하도록 바꾼다.
추가 unique cache key나 cache 색인이 없고, 같은 key를 가진 여러 revision을 저장할 수 있음을 단언한다.
기존 요청 UNIQUE, 첨부/revision UNIQUE, 다른 첨부를 연결하는 복합 FK 거절과 다중 alias cascade를 그대로 실행한다.
`MediaObservationMysqlMigrationTest`가 상속하여 실제 MySQL에도 같은 단언을 적용한다.
구행의 key가 null인 상태와 새 열 적용 후 alias/FK가 보존됨을 확인한다.
별도 DB에서 새 DDL 직전까지 Flyway를 적용하고 관찰과 다중 alias를 넣은 뒤 나머지 migration을 적용하는 upgrade 회귀도 만든다.
기존 본문·alias를 보존하고 모든 구행 analysis key가 null인지를 H2와 실제 MySQL에서 같은 테스트로 단언한다.

### 5. 현재 계약으로 문서 갱신

기능·schema·architecture 문서와 새 ADR의 구현 전 표시를 실제 구현에 맞게 제거한다.
기능 문서의 기존 「동일 MODEL도 항상 새 revision」 행을 cache 조건과 miss 조건으로 고친다.
저장 ADR은 현재 유효한 저장·UUID·crypto·삭제 결정을 유지하고 결정 바로 뒤에 새 ADR이 대체한 부분을 링크한다.
INDEX의 구현 전 설명도 제거한다. docs는 계획 번호와 이 문서를 가리키지 않는다.
구현을 마친 계획서는 최종 구현 PR에서 삭제한다.

## 검증

기존 test profile과 합성 fixture를 사용하며 운영 값은 필요 없다.
Java/Gradle은 backend wrapper, Node는 22.18 이상이며 Docker로 실제 MySQL 검사를 실행한다.
아래 명령은 저장소 root에서 각각 실행하고 모두 종료 0이어야 한다.
첫 test는 mysql 태그를 제외하고, MySQL 스크립트는 Flyway validate와 상속된 lock/migration 및 저장소 쿼리를 실행한다.

```bash
cd backend && ./gradlew test --tests '*MediaObservation*' --tests '*AttachmentServiceTest' --tests '*AttachmentCleanerTest' --tests '*ConversationPurgerTest' --tests '*crypto*'
cd backend && ./gradlew qualityCheck
node scripts/check-migration-versions.mjs
scripts/check-mysql-migration.sh
node --test test/unit/doc-files.test.ts test/unit/adr-index.test.ts
scripts/check-local.sh --skip-browser
```

staged 파일은 커밋 전에 아래 명령으로 변경 표와 대조한다.
새 migration이 staged에 있어야 하며 범위 밖 수정이나 검사 누락이 없어야 한다.
새 ADR은 선행 checkpoint에서 이미 추적하므로 이 phase에서는 수정 M이어야 한다.
신규 helper·테스트 두 개·DDL은 A이며, 나머지는 변경 표와 같은 M이어야 한다.

```bash
# cwd: 저장소 root
python3 "$SKILL_DIR/scripts/verify_task.py" --staged tasks/plan137-media-observation-cache/phase-01.md
```

## 변경 파일

| 파일 | 변경 |
| --- | --- |
| `backend/src/main/java/com/bifos/assistant/chat/application/MediaObservationAnalysisKey.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/application/MediaObservationService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/domain/MediaObservation.java` | 수정 |
| `backend/src/main/resources/db/migration/V20261010162744__media_observation_analysis_key.sql` | 신규 |
| `backend/src/test/java/com/bifos/assistant/chat/MediaObservationAnalysisKeyTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/chat/MediaObservationCacheTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/chat/MediaObservationServiceTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/MediaObservationRequestTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/MediaObservationBodiesTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/MediaObservationLockTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/MediaObservationMigrationTest.java` | 수정 |
| `docs/features/attachment.md` | 수정 |
| `backend/docs/data-schema.md` | 수정 |
| `backend/docs/code-architecture.md` | 수정 |
| `backend/docs/adr/ADR-20261010-media-observation-storage.md` | 수정 |
| `backend/docs/adr/ADR-20261011-media-observation-cache.md` | 수정 |
| `backend/docs/adr/INDEX.md` | 수정 |
