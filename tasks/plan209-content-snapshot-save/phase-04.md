# Phase 04. 예약 ID로 기존 worker를 한 번 시작하고 같은 job 조회

**Execution profile**: deep

## 목표

불변 snapshot·사진 지문·현재 권한을 검증한 뒤 기존 worker를 예약 jobId로 실행하고 결과 유실을 조회로만 복구한다.

**범위 외**: UI, 원고 최초 생성, overwrite 변경, 실제 계정·provider 사진 대응, 자동 저장 재시도.

## 컨텍스트

**근거 문서**: `docs/features/content-draft.md`의 「내부 compiler와 worker 전달」와 「job 상태와 재접속」.
현재 server.ts의 startJob은 randomUUID를 만들며 startWaitMs가 지나면 NAVER_BLOG_START_UNKNOWN이다.
jobs.ts의 createState는 EXCL/link로 생성하고 finishState는 끝냄 표시를 claim한다. terminal 상태는 덮어쓰지 않는다.
worker.ts의 onStage(save_clicking)는 save_clicked를 기록·재읽기한 다음 clicked=true로 둔다. 그 이후 실패는 unknown이다.
runDraft는 저장 수 증가 뒤 state 실패를 삼키고 state:null로 성공한다. photos는 readPhoto bytes를 attachPhoto에 넘긴다.

## phase03 뒤 실제 main

- 시작 전송은 `connector.application.ContentDraftJobStarter`의 `Optional<String> start(String profile, String connectorId, JsonNode snapshot, ContentDraftSaveTicketPayload job, String ticket)` 구현 빈으로 연결한다. job 은 예약 jobId·snapshotId·hash·바인딩과 연결의 판을 담고, 구현은 여기와 profile 의 SHA-256 으로 start 입력의 stamp 를 만든다. 빈 값은 접수, 값은 시작 전 거절 code, 예외는 결과 모름이다.
- ticket 형식과 authorize 요청·응답은 `docs/features/content-draft.md`의 「저장 ticket과 authorize」가 갖는다. worker는 ticket을 해석하지 않고 그대로 싣는다.
- `ConnectorSnapshotDeclaration.read`와 `HttpContentDraftSnapshotClient.compile`의 capability 검사는 아직 compile 전용만 받는다. 시작을 선언하는 catalog를 열 때 함께 바꾼다.

## Blocked 조건

- authorize·ticket·snapshot 승인 계약이 실제 main에서 준비되지 않았으면 `PHASE_BLOCKED: approved snapshot authority`로 종료한다.
- 확정한 본문 없는 종류·크기·파트 수 진단 정책과 복구용 본문 dump 제외 구현, 본문·ticket 가림 검증이 끝나지 않았으면 운영 활성화와 start capability를 false로 유지한다.

## 의도 메모

- 일반 DSL startJob은 randomUUID를 유지한다. private start만 CP 예약 ID를 사용한다.
- 연결 없는 status는 CP의 마지막 상태만 표시한다. 새 연결로 옛 job을 조회하지 않는다.
- 운영 변경 목표 1000줄 이하인 별도 worker PR이다. 대역 구현만으로 실제 저장 왕복을 완료했다고 보고하지 않는다.

## 작업 항목

### 1. private start·status와 예약 ID

content-snapshot.ts에 start_content_snapshot·content_snapshot_status를 추가한다. generic snapshot transport의 정확한 선언으로만 호출한다.
JobState에 선택적 snapshot stamp를 더한다. 기존 DSL job은 필드가 없어도 읽는다. stamp에는 기능 문서의 protocol·snapshotId·jobId·두 연결 버전·hash·profileHash가 있고 본문·token·CDP 주소는 없다.
같은 ID 상태가 존재하면 stamp를 대조해 그것만 반환한다. running·terminal·UNKNOWN에서 worker를 새로 띄우지 않는다.
상태 생성 뒤 input 쓰기·spawn·접수 응답 사이 실패에도 예약 ID를 없애고 다시 생성하지 않는다. 실패 상태나 tombstone을 유지한다.
CP에서 한 번 전송한 예약 ID가 status에서 없으면 UNKNOWN으로 유지하며 start를 다시 보내지 않는다. 24시간 정리 뒤에도 재실행하지 않는다.
private status는 jobs.ts publicState를 그대로 노출하지 않고 허용된 상태·단계·save_clicked·안전한 결과 수치·오류 code만 반환한다.
원본 fileName·manifest·ticket·raw URL·이미지 bytes를 결과에 넣지 않는다.

공개 draftJob은 snapshot stamp 키가 있으면 null/malformed까지 NAVER_BLOG_JOB_NOT_FOUND를 반환한다. stale 처리/publicState 호출 전에 막아 공개 조회가 private 상태를 바꾸지 못한다.
private content_snapshot_status만 `{stamp}`를 strict decode하고 CP가 저장한 원래 job·hash·binding/connection 버전·profileHash를 정확히 대조한 뒤 projection한다. stamp 없는 legacy job은 공개 계약만 유지한다.
실제 TS MCP Client·InMemoryTransport로 기능 문서의 혼입/변조/다른 profile/terminal/stale 반례 표를 실행한다. 일반 도구 목록과 env 입력으로 private mode를 켤 수 없음을 확인한다.

### 2. 기존 worker와 업로드 지문

새 kind:content_snapshot 입력은 compiledSnapshot을 담고 ticket은 worker env로만 전달한다. server.ts의 일반 workerEnvironment whitelist는 유지하고 private spawn 전용 env builder가 ticket/authorize URL을 명시적으로 전달한다. takeInput의 읽은 뒤 삭제를 유지한다.
FOS_CONTENT_SAVE_AUTHORIZE_URL은 plugin의 기존 FOS_CONNECTOR_EXECUTIONS_BASE_URL에서 고정 경로로 조립한다. 모델·request args가 URL을 고르지 못한다.
worker는 시작 전 hash·canonical tuple·manifest를 재검증하고 CP authorize(start)를 통과한 뒤 기존 runDraft를 호출한다.
runDraft의 선택적 snapshot guard는 기존 DSL 호출에서 생략 가능하게 둔다. photos의 각 readPhoto 바이트를 manifest 지문과 대조하고 authorize(photo, position) 뒤 같은 바이트를 attachPhoto에 넘긴다.
사진 삭제·교체·경로 escape·thumbnail 혼입·사용자 끄기·agent 비활성·CANCELLED 커밋·CP 장애는 다음 업로드와 의도적 저장을 차단한다.
save_clicking에서는 authorize(save) 뒤 기존 save_clicked·finishClaimed 검사를 유지한다. 권한 철회와 save claim 순서를 각각 검사한다.
clicked=false 실패와 clicked=true UNKNOWN, 600초 timeout·30초 heartbeat stale·11분 jobs stale을 기존 규칙대로 유지한다.

### 3. CP 상태 조회와 같은 phase의 테스트

ContentDraftJobs.refresh(CurrentUser user, Long conversationId, UUID draftId, UUID snapshotId)는 owner·원래 대화·현재 binding 버전을 확인한 뒤 동일 예약 ID만 조회한다.
HttpContentDraftSnapshotClient의 추가 시그니처는 `JsonNode start(String profile, String connectorId, JsonNode snapshot, UUID jobId, String ticket)`과 `JsonNode status(String profile, String connectorId, ContentDraftWorkerStamp stamp)`다.
body·결과 envelope와 timeout은 기능 문서를 따른다. 자동 HTTP retry는 추가하지 않는다.
CONTENT_DRAFT_JOB_UNAVAILABLE을 ErrorCode에 추가한다. HttpContentDraftSnapshotClientTest에서 start·status의 실제 HTTP body와 timeout·job/hash/binding stamp 거절·재전송 0회를 확인한다.
원격 호출 뒤 다시 ACL을 확인하고 stamp와 jobId가 맞는 상태만 기록한다. 조회 오류는 terminal을 FAILED로 바꾸지 않는다.
UNKNOWN 뒤 같은 job의 terminal을 검증하면 job 상태를 해소한다. ConnectorAction은 EXECUTING일 때만 terminal로 갱신하고 기존 UNKNOWN action을 다시 실행하지 않는다.
`backend/src/test/java/com/bifos/assistant/connector/ContentDraftJobLoopbackTest.java`는 @SpringBootTest RANDOM_PORT의 실제 CP HTTP와 Bun 합성 worker 프로세스·fake CDP를 연결한다.
신규 `hermes/connectors/naver-blog/tests/content-snapshot-loopback-entry.ts`가 실제 runWorker와 fake-cdp를 연결하고 테스트 JVM이 이 자식을 시작·종료한다. Bun이 없으면 skip 대신 실패한다.
start 응답 유실·CP restart·조회 timeout·권한 철회·사진 교체·클릭 직전/직후 중단에서 실제 start·클릭 횟수를 센다.
`hermes/connectors/naver-blog/tests/worker.test.ts`와 `hermes/connectors/naver-blog/tests/jobs.test.ts`, `hermes/connectors/naver-blog/tests/save-draft.test.ts`는 terminal·stale·finish claim 경합과 legacy save/overwrite를 유지한다.
`hermes/connectors/naver-blog/tests/editor-photos.test.ts`는 30장 서로 다른 합성 바이트의 manifest 순서, 중복 위치, 누락, same bytes 업로드를 검사한다. provider 대응 성공으로 이름 붙이지 않는다.

ContentDraftWorkerStatusDecoder는 소문자 worker status를 CP 대문자 enum으로 명시적으로 옮기고 stage·save_clicked의 타입과 whitelist를 검사한다. 잘못된 stamp·단계·대소문자·boolean coercion은 수용하지 않는다.
photo guard는 기존 inserted+offset+1을 쓰지 않는다. initial inserted 고정값과 이미지 배열 인덱스로 assetManifest.position을 얻고 반복 asset도 자리별로 검사한다.
runWorker loopback은 CP RANDOM_PORT HTTP 요청, 실제 worker 시작, 실제 fake CDP 파일 바이트 업로드, save 단추 클릭을 독립 카운터로 센다. `{saved:true}`나 예정 manifest를 클릭/업로드 증거로 세지 않는다.
macOS Java fixture도 scripts/test.ts의 Linux 합성 Chrome 환경에서 entry를 시작하도록 Docker 전용 합성 runner를 둔다. CP loopback 접근 주소는 런타임으로 전달하고 start/stop/timeout/cleanup을 JVM try/finally로 관리한다. 실제 계정 env를 복사하지 않으며 Bun/Docker 부재는 실패다.
50자리·반복 asset·30장 바이트·50/51고유 asset, readPhoto 뒤 파일 교체에서도 같은 Uint8Array hash/upload인지 검사한다. 입력 증거와 실제 provider component 대응은 별도 결과다.

## 검증

```bash
# cwd: 저장소 root
set -e
(cd hermes/connectors/naver-blog && bun run build && bun run scripts/test.ts ./tests/content-snapshot.test.ts ./tests/worker.test.ts ./tests/jobs.test.ts ./tests/save-draft.test.ts ./tests/editor-photos.test.ts)
python3 -m unittest discover -s hermes/tests -p 'test_connector_snapshot.py'
(cd backend && ./gradlew test --tests '*ContentDraftJobsTest' --tests '*ContentDraftJobLoopbackTest' --tests '*HttpContentDraftSnapshotClientTest' --tests '*ContentDraftWorkerStatusDecoderTest')
(cd backend && ./gradlew qualityCheck)
git diff --check
```

scripts/test.ts는 macOS에서 합성 파일만 Linux Chrome 컨테이너로 전달한다. 운영 브라우저와 실계정은 사용하지 않는다.

운영 목표·hard cap·공유 파일 여유는 phase01의 「운영 변경 예산과 파일 상한」을 따른다. 이 표의 새 소비자도 비용에 포함하고 상한을 넘으면 설계와 구현을 함께 나눈다.

## 변경 파일

| 파일 | 변경 |
| --- | --- |
| `hermes/connectors/naver-blog/src/content-snapshot.ts` | 수정 |
| `hermes/connectors/naver-blog/src/server.ts` | 수정 |
| `hermes/connectors/naver-blog/src/jobs.ts` | 수정 |
| `hermes/connectors/naver-blog/src/worker.ts` | 수정 |
| `hermes/connectors/naver-blog/src/editor/run.ts` | 수정 |
| `hermes/connectors/naver-blog/src/editor/photos.ts` | 수정 |
| `hermes/connectors/naver-blog/tests/content-snapshot.test.ts` | 수정 |
| `hermes/connectors/naver-blog/tests/worker.test.ts` | 수정 |
| `hermes/connectors/naver-blog/tests/jobs.test.ts` | 수정 |
| `hermes/connectors/naver-blog/tests/save-draft.test.ts` | 수정 |
| `hermes/connectors/naver-blog/tests/content-snapshot-loopback-entry.ts` | 신규 |
| `hermes/connectors/naver-blog/tests/editor-photos.test.ts` | 수정 |
| `hermes/plugins/dashboard-profile-api/connector_snapshot.py` | 수정 |
| `hermes/tests/test_connector_snapshot.py` | 수정 |
| `backend/src/main/java/com/bifos/assistant/connector/application/ContentDraftJobs.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/hermes/HttpContentDraftSnapshotClient.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/shared/error/ErrorCode.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/hermes/HttpContentDraftSnapshotClientTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/connector/ContentDraftJobsTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/connector/ContentDraftJobLoopbackTest.java` | 신규 |
| `docs/features/content-draft.md` | 수정 |
| `backend/src/main/java/com/bifos/assistant/connector/application/ContentDraftWorkerStatusDecoder.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/hermes/dto/ContentDraftWorkerStamp.java` | 신규 |
| `hermes/connectors/naver-blog/tests/content-snapshot-loopback-runner.ts` | 신규 |
| `backend/src/test/java/com/bifos/assistant/connector/ContentDraftWorkerStatusDecoderTest.java` | 신규 |
