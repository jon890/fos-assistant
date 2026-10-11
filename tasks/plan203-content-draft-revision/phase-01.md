# Phase 01. 원고 REST에 MCP 도구와 사건 가림 연결 (PR B)

**Execution profile**: deep

## 목표

main에 원고 revision REST·삭제·만료·purge가 반영된 뒤 같은 서비스를 MCP에서 사용한다.
현재는 구현 전 계획이다. 첫 MCP 생성의 중복 방지는 확정한 사용자 정책이며 사용자 답변 대기는 없다.
## 컨텍스트

**근거 문서**: `docs/features/content-draft.md`의 「원고 revision 저장 계약」과 `docs/features/mcp.md`, `docs/features/attachment.md`, `backend/docs/data-schema.md`다.
계획 보존 기준 SHA는 `5360598c1664b5bd88b611a3d971c41251f5b55d`다.
이번 코드 대조의 PR443 head는 `fd27eafdcc1297746928adc730fa5eb6b8dfd3f4`, 실제 main은 `40dda2bf5e40425dc3746cbfc57b34568fee56f3`다.
PR443은 OPEN이고 mergedAt은 null이다. main에는 원고 서비스·표가 없어 구현 착수 조건을 아직 통과하지 못했다.
snapshot 설계 정본 `0ecf949eb380504a0732d05cece6e2035aa313fa`의 사용자 정책과 인터페이스를 읽기 전용으로 대조했다.
계획 세 파일과 후속 MCP 설계는 별도 브랜치에 보존하며 PR443에는 REST 단계의 설계와 구현만 남긴다.
후속 구현은 PR443 머지 뒤 최신 main의 계약을 확인하고 시작한다. REST 코드를 복사하거나 다시 구현하지 않는다.
PR A에서 구현한 schema·canonical·암호화·REST·삭제 장벽 phase는 지웠다.
운영 코드 1,500줄 상한을 지키며 이번 PR은 MCP와 그 본문 가림이라는 관심사 하나다.

## 범위

- 실제 `content_draft_write`·`content_draft_read` 정의와 호출, origin 실행 전후 재검사.
- MCP 생성 전용 requestId, 영속 생성·전송 대응 표와 tombstone, 기존 coordinator 안의 원자적 저장.
- Hermes 원고 도구 사건의 detail과 text 가림, 저장 사건과 화면 사건 회귀.
- fos-ctx REQUIRED_TOOLS와 README·서명 필수 목록 회귀.

**범위 외**: compiler 변경, cache, queue, preview, snapshot, approval, 영속 jobs, 새 UI, 추가 LLM, 발행·실제 거래, 전역 MCP parser, 다른 저장소와 Hermes core 변경이다.
원본 워커의 세션과 worktree는 재개·수정·삭제하지 않는다.
설계만 push하거나 PR을 열지 않는다. 최신 main은 일반 merge하며 rebase·force push하지 않는다.

## 의도 메모

첫 MCP 생성에 중복 방지를 포함한다. 저장 뒤 응답이 끊겨 같은 요청을 다시 호출하면 같은 원고를 반환한다.
세부 계약의 정본은 기능 문서의 「첫 MCP 생성의 재시도 식별」이다. 여기서는 구현 책임과 통과 조건을 정한다.
현재 McpCallContext·fos-ctx README는 전송 재시도의 tool_call_id 유지와 session별 범위를 보장한다.
모델의 새 도구 호출은 새 ID일 수 있어 생성 requestId가 추가로 필요하다. 기존 관찰 MCP도 명시적 requestId를 받는다.
생성의 requestId는 최초 의도에서 한 번 정해 재시도에 그대로 재사용한다. 임의 새 UUID·목록 확인·요청 본문만의 hash로 의도를 추측하지 않는다.
같은 본문으로 새 원고를 의도할 때는 새 requestId와 새 tool_call_id를 쓴다. hook이 requestId를 자동 교체하지 않는다.
REQUEST nonce/signature는 권한 증명이며 재시도 식별자가 아니다. 현재 _fos_ctx에는 nonce/purpose가 없고 커넥터 SupportRequest nonce도 이 기능에 쓰지 않는다.
사후 requireCurrent는 service commit을 rollback하지 않는다. 생성 대응을 같은 TX로 저장하여 권한 있는 재시도만 같은 원고에 연결한다.
REST POST와 PUT, 기존 완료 원고의 CAS·이전 revision 조회는 그대로 둔다. 생성 키와 snapshot save 키를 섞지 않는다.

## 작업 항목

### 1. MCP와 사건 가림

1. `McpContentDraftTools`가 write/read 정의와 결과를 소유한다. 인자 record와 파싱은 기존 `McpDtos`에 둔다. 생성은 requestId 필수와 draftId·expectedRevision 동시 생략, 수정은 requestId 금지와 나머지 둘 필수다. read의 revision은 생략 가능하다.
2. 새 도구만 최상위 선택 인자와 draft 중첩의 명시적 null을 거절한다. 기존 도구의 null 관례는 바꾸지 않는다. `_fos_ctx`는 handler 전에 떼고 unknown·사용자·대화·profile·실행 식별자 인자를 거절한다. UUID와 revision 검사는 `ContentDraftInputReader`를 재사용한다.
3. `McpCallerResolver`가 정한 origin의 사용자와 conversationId만 서비스에 전달한다. raw HMAC 서명 범위는 v1, 도구 이름, root_session_id, session_id, tool_call_id다. args/time/nonce는 서명하지 않고 현재 tool_call_id 중복 검사도 없다.
4. 기존 `McpMediaObservationTools.requireCurrent`와 `invalidContext`만 최소 helper인 `McpCurrentCallerGuard`로 옮긴다. REQUIRES_NEW 읽기 트랜잭션과 판정 순서를 유지한다. guarded의 관찰 conflict 문자열 파싱과 오류 변환은 옮기지 않는다. 이동 커밋과 동작 커밋을 나눈다.
5. 실행 전후 guard는 origin ID·사용자·대화·profile, session 등록, origin·루트 취소, 사용자 허용 상태, agent 삭제·enabled·connectorManaged·읽기 권한·hermesProfile을 확인한다. 등록된 child는 origin이 COMPLETED여도 같은 origin을 유지하고 CANCELLED를 차단한다. 등록 없는 최상위 session의 RUNNING 판정도 유지한다.
6. 사전 검사 실패는 서비스 호출·신규 revision·외부 호출 모두 0이다. 사후 검사 실패는 이미 커밋된 행이 남을 수 있고 성공과 typed 오류 응답의 본문·revision을 차단한다. 권한 철회와 저장을 원자적 취소로 설명하지 않는다. 기존 관찰 오류 변환은 유지한다.
7. 성공 JSON은 REST의 안전한 ContentDraftView를 직렬화해 `ExternalData.wrap`으로 감싼다. write와 read 모두 isError=false이며 닫는 표시 escape를 시험한다.
8. domain 오류는 isError=true의 text JSON에 code와 값이 있는 typed `ContentDraftException.currentRevision`만 전달한다. VALIDATION_FAILED는 기존 관찰 도구처럼 controller까지 전달해 HTTP 200의 JSON-RPC -32602와 기존 고정 INVALID_ARGUMENTS로 반환한다. CONFLICT·STALE은 정수 currentRevision, GONE·UNAVAILABLE은 그 필드가 없다. 맥락 오류는 기존 MCP_CALL_CONTEXT_INVALID 고정 결과다. 예외 message 파싱과 원고·revision·cause 로그를 넣지 않는다.
9. catalog는 record_media_observation 뒤, memory_remember 앞에 write, read 순서로 등록한다. controller handler map에 두 위임 항목과 필요한 공용 인자 오류 helper 하나만 더한다. helper는 VALIDATION_FAILED만 -32602로 바꾸며 다른 예외를 삼키지 않는다. controller 500줄 상한을 지키고 범용 실행 계층을 만들지 않는다.
10. CHECK_TREE_TOOLS와 CHECK_TREE_WRITE_TOOLS는 확대하지 않는다. 쓰기 허용 살펴보기에서도 두 도구를 차단하고 서비스 requireWritable의 CHECK 차단을 유지한다.

### 2. 입력 schema와 파싱 경계

inputSchema의 객체는 additionalProperties=false이며 nullable 타입을 넣지 않는다.
paragraph, image, sticker, map의 필드와 선택 caption·observationRef를 선언한다.
`McpToolCatalogTest`는 아래 상한을 확인하고 `McpContentDraftToolTest`는 실제 서비스 수락·거절을 대조한다.

| 대상 | 서비스와 일치하는 제한 |
| --- | --- |
| title, category | Unicode code point 각각 1~100, 1~50자 |
| tags | 0~30개, 각 1~30자. trim·소문자 중복과 #·쉼표는 서비스에서 거절 |
| blocks | 0~500개. image 50개, sticker·map 각각 30개 제한은 설명과 서비스 검사로 유지 |
| paragraph.text, image.caption | 각각 0~20,000자, 0~300자. 줄바꿈과 예약 줄은 서비스 검사 |
| sticker.code | 1~64자, 기존 영문·숫자·밑줄·하이픈 패턴 |
| map.name, map.address | 각각 1~200자, 기존 금지 문자 검사 |
| assetId, observationRef.id | 정규 양수 십진 문자열 1~19자, 양수 Long 범위 |
| revision, observationRef.revision | JSON 정수 1~9,007,199,254,740,991 |
| 수정 expectedRevision | JSON 정수 1~9,007,199,254,740,990. 다음 revision을 만들 여유 필요 |
| 생성 requestId | 소문자 정규형 UUID. 생성 때 필수, 수정·read에는 금지. 명시적 null 거절 |
| 본문 | UTF-8 compact JSON 65,536바이트 이하. 예약 줄과 짝 없는 surrogate 검사는 서비스 재사용 |

REST는 문자열 원문을 STRICT_DUPLICATE_DETECTION으로 읽는다.
MCP는 기존 `@RequestBody JsonNode`로 이미 해석한 값이므로 도구에서 중복 키를 복원하거나 거절한다고 보장할 수 없다.
이번 기능을 위해 전역 MCP parser와 기존 도구의 파싱을 바꾸지 않는다.

### 3. 생성 대응 schema와 저장

1. 새 Flyway DDL과 엔티티로 content_draft_creation_request와 content_draft_creation_call을 추가한다. 기존 적용 migration과 일반 chat 표는 변경하지 않는다. 생성 키·전송 키와 각 식별 고정 키는 BINARY(32) UNIQUE이며 정확한 범위 대응도 비교한다. 같은 요청·전송을 다음 turn의 origin이나 다른 루트로 재해석하지 못하게 고정 키로 먼저 찾는다. FK·CHECK·purge 순서는 기능 문서의 CREATED/GONE·tombstone 계약을 따른다.
2. 검증된 생성 호출 범위는 chat.application.model에 둔다. MCP가 서명·현재 caller로 얻은 owner/conversation/profile/origin/루트 실행/root session과 session/tool_call_id만 채운다. 모델 인자로 범위를 받거나 SessionOwnerResolver의 native origin을 다시 추정하지 않는다.
3. ContentDraftService에 createIdempotent를 추가한다. 기존 NOT_SUPPORTED 진입과 coordinator의 REQUIRES_NEW·READ_COMMITTED, 사용자→대화→첨부 오름차순 잠금을 유지한다. 대응 조회·잠금은 그 뒤 생성 요청→전송 대응 순서다. purge와 cleaner도 이 순서를 지킨다.
4. create의 저장 부분과 잠금 안의 현재 원고 검사를 최소 private helper로 재사용한다. public create/read를 coordinator 안에서 다시 호출하여 중첩 coordinator를 만들지 않는다. REST create/revise/read/list의 응답과 CAS는 바꾸지 않는다.
5. 입력 검증 뒤 고정 tuple requestHash를 계산한다. key 배열·requestHash golden vector는 Java와 독립 계산의 UTF-8 바이트·compact JSON·SHA-256을 대조한다. 키 순서/escape만 다른 입력은 같고, Unicode 조합·공백·배열 순서·caption 생략/빈 문자열은 다르다.
6. 없는 생성 키만 원본 검사·암호화·draft/revision 1을 만든다. 원고·asset·두 대응 행은 한 TX로 저장한다. 실패·unique 위반은 전체 rollback하며 경합 패자는 새 TX에서 한 번 현재 대응을 재조회한다. rollback-only TX에서 예외를 삼켜 성공을 반환하지 않는다.
7. 기존 생성 키는 최초 requestHash와 비교한다. 같은 전송 키의 requestId 변경도 충돌이다. 새 tool_call_id로 같은 요청을 재호출하면 전송 대응만 추가하고 현재 revision을 읽는다. 이전 CAS 수정 본문을 최초 입력으로 되돌리거나 revision 1을 캐시 반환하지 않는다.
8. 만료·참조 무효화 오류는 기존 서비스처럼 TX 안에서 결과로 수집하여 상태 변경을 커밋한 뒤 밖에서 반환한다. 오류를 던져 body/key 삭제와 tombstone 갱신을 rollback하지 않는다. expiresAt 경계와 본문 없는 typed 오류 순서를 지킨다.
9. 자체 만료·물리 삭제·대화 purge는 GONE tombstone과 전송 대응을 남긴다. requestHash·draft 참조·본문은 지우고 생성 키는 소비 상태로 유지한다. STALE은 최초 hash와 기존 원고를 유지하며 CAS로 유효해지면 재시도는 현재 revision을 읽는다. 일반 30일 TTL로 대응을 지우지 않는다.
10. 대응 표와 tombstone은 응답 캐시가 아니다. 모든 재시도에서 서명·현재 caller·대화 접근·현재 참조·사후 guard를 통과해야 한다. 다른 범위의 requestId는 이전 원고로 연결하지 않는다. 취소·권한 철회 뒤 대응이 있어도 본문·revision을 반환하지 않는다.

### 4. 사건 가림과 목록 회귀

1. `HermesRunEventStream`의 state.classify와 interim 접두사 길이 계산 뒤, 관찰 도구 가림 옆에서 새 도구 detail과 text를 null로 만든다. type과 기존 분류 순서를 바꾸지 않는다.
2. 정확한 content_draft_write/content_draft_read 또는 `__content_draft_write`/`__content_draft_read` suffix만 일치한다. 기존 표시용 이름 선택 순서는 유지하되 가림은 root/data의 tool/tool_name/toolName/name 문자열을 모두 검사한다. 앞 별칭이 다른 도구여도 뒤의 원고 이름을 놓치지 않는다.
3. 원고 도구가 식별된 사건은 event name에만 의존하지 않고 가린다. 실제 tool.started와 tool.completed의 error=false/true를 포함한다. 합성 tool.failed, tool.progress, 비표준 이름과 event name 없는 사건도 검사한다. tool.failed를 실제 실패 계약으로 가정하지 않는다.
4. root/data의 preview/detail/result와 delta/text/output에서 골라지는 두 출력 경로를 모두 버린다. 문자열과 객체를 검사하며 역할과 커넥터 사용 여부는 영향을 주지 않는다.
5. 일반 USER·final(message.delta)·interim(message.interim)은 임의로 가리지 않는다. 표준 대화 사건을 원고 도구 사건으로 재해석하지 않으며 기존 interim·reasoning 정책을 유지한다. subagent goal 보호 확대는 범위 밖이다.
6. `ToolDetailEventStreamTest`는 ExecutionEvent와 ChatEvent 저장, `ToolDetailStreamTest`는 화면 사건, `HermesRunEventStreamTest`는 alias·root/data·분류 순서와 일반 대화 회귀를 확인한다.
7. `McpToolCatalogTest`의 생성자와 containsExactly 순서, `McpCallerInvariantTest.toolArguments()`의 서명 차단 목록을 함께 고친다. `McpMediaObservationToolTest`로 guard 이동 후 기존 관찰 동작을 확인한다.
8. fos-ctx hooks.py REQUIRED_TOOLS, README의 `_fos_ctx` 계약·필수 도구 설명·목록 세 곳, __init__.py 설명과 `test_fos_ctx.py` 서명 생성·실패 차단을 함께 고친다. memory_remember의 INTERNAL_TOOLS는 확대하지 않는다.
9. `docs/features/mcp.md`의 관찰 연결 설명·요청자 목록·Control Plane 도구 표와 순서를 갱신한다. content-draft.md에는 파싱 경계·typed 오류·commit과 권한 검사 차이·사건 가림 범위를 남긴다.

## 변경 파일

| 파일 | 변경 |
| --- | --- |
| `backend/src/main/java/com/bifos/assistant/mcp/application/McpContentDraftTools.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/application/ContentDraftService.java` | 수정 (생성 전용 원자적 대응과 private 저장 재사용, 기존 CAS 보존) |
| `backend/src/main/java/com/bifos/assistant/chat/application/model/ContentDraftCreationContext.java` | 신규 검증된 호출 범위 |
| `backend/src/main/java/com/bifos/assistant/chat/domain/entity/ContentDraftCreationRequest.java` | 신규 대응 엔티티 |
| `backend/src/main/java/com/bifos/assistant/chat/domain/entity/ContentDraftCreationCall.java` | 신규 전송 대응 엔티티 |
| `backend/src/main/java/com/bifos/assistant/chat/domain/type/ContentDraftCreationStatus.java` | 신규 CREATED/GONE |
| `backend/src/main/java/com/bifos/assistant/chat/infra/ContentDraftCreationRequestRepository.java` | 신규 대응 조회·unique 장벽 |
| `backend/src/main/java/com/bifos/assistant/chat/infra/ContentDraftCreationCallRepository.java` | 신규 전송 대응 조회·unique 장벽 |
| `backend/src/main/java/com/bifos/assistant/chat/application/ContentDraftCleaner.java` | 수정 (대응과 tombstone의 삭제·만료 참가) |
| `backend/src/main/java/com/bifos/assistant/chat/application/ConversationPurgeWriter.java` | 수정 (대응과 tombstone의 purge 참가) |
| `backend/src/main/resources/db/migration/V*__content_draft_creation.sql` | 신규 (구현 시 UTC 작성 시각으로 버전 확정) |
| `backend/src/main/java/com/bifos/assistant/mcp/application/McpCurrentCallerGuard.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/mcp/application/McpMediaObservationTools.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/mcp/application/McpToolCatalog.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/mcp/presentation/McpController.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/mcp/presentation/McpDtos.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/hermes/HermesRunEventStream.java` | 수정 |
| `hermes/plugins/fos-ctx/hooks.py` | 수정 |
| `hermes/plugins/fos-ctx/__init__.py` | 수정 |
| `hermes/plugins/fos-ctx/README.md` | 수정 |
| `hermes/tests/test_fos_ctx.py` | 수정 |
| `backend/src/test/java/com/bifos/assistant/mcp/McpContentDraftToolTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/chat/ContentDraftIdempotencyMysqlTest.java` | 신규 실제 MySQL TX·경합·rollback·purge 시험 |
| `backend/src/test/java/com/bifos/assistant/chat/ContentDraftCreationHashTest.java` | 신규 독립 golden vector 대조 |
| `test/e2e/scenarios/content-draft-mcp.ts` | 신규 생성 응답 유실·재호출·CAS·삭제와 실행 사건 가림 |
| `test/e2e/run.ts` | 수정 (새 원고 MCP 시나리오 등록) |
| `backend/docs/data-schema.md` | 수정 (원고 전용 두 표·FK·CHECK·tombstone 설명) |
| `backend/src/test/java/com/bifos/assistant/mcp/application/McpToolCatalogTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/mcp/McpCallerInvariantTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/mcp/McpMediaObservationToolTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/hermes/ToolDetailEventStreamTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/ToolDetailStreamTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/hermes/HermesRunEventStreamTest.java` | 수정 |
| `docs/features/content-draft.md` | 수정 |
| `docs/features/mcp.md` | 수정 |

공유 MCP 파일의 충돌은 코디네이터에게 보고한다. 원고 REST와 첨부 삭제 계약은 그대로 재사용한다.
이 표는 후속 구현자의 소유 표이며 이번 계획 보완에서는 운영 코드와 테스트를 변경하지 않는다.

운영 코드 배분은 도구·DTO·등록 350줄, guard 이동·관찰 조정 150줄, 생성 대응 서비스·지문 400줄, 두 표·저장소·migration 300줄, 삭제·만료·사건 가림·fos-ctx 250줄로 합계 1,450줄을 목표로 한다.
이는 실측이 아니다. 구현 뒤 scripts/pr-size.mjs로 1,500줄 상한을 측정하고 초과하면 코디네이터에게 분할을 보고한다.
schema·멱등 저장과 strict MCP 연결이 모두 완료되기 전 도구를 등록하지 않는다. 나눌 때도 각 PR은 그 단계의 설계와 구현을 함께 담으며 worker가 규모 예외를 붙이지 않는다.

## 검증

| 그룹 | 관측할 결과 |
| --- | --- |
| 인자 | 생성 requestId 필수·수정에는 금지, draftId/expectedRevision 동시 생략·동시 지정. 한쪽만 지정·명시적 null·unknown·사용자·대화·profile 키·대문자 UUID·문자열/실수 revision 거절 |
| schema | 표의 상한과 code point·64KiB 경계. 수정 MAX-1 수락·MAX 거절과 read MAX 수락 |
| 요청자 | 실제 /mcp HTTP에서 서명 없음·다른 도구 서명·다른 대화·살펴보기·취소·agent 비활성·profile 교체·권한 철회 차단. COMPLETED child 허용과 CANCELLED 차단 유지 |
| 사전 검사 | 서비스 호출·신규 revision·외부 호출 0회 |
| 사후 검사 | 서비스 반환 직후 권한을 바꾸는 테스트 대역으로 커밋된 행과 본문·revision 없는 맥락 오류를 함께 확인. read도 응답 차단 확인 |
| 서비스 | 생성·CAS·이전 조회, typed STALE/CONFLICT, GONE·UNAVAILABLE의 currentRevision 생략, VALIDATION_FAILED의 -32602·고정 문구, 외부 호출 0회 |
| 사건 | bare 이름·__ suffix, alias 네 가지·충돌, root/data, 출력 필드 여섯 가지. 시작·완료·error=true와 합성·비표준 사건 수집에 표식 없음. 저장·화면 경로도 확인 |
| 대화 | USER·final·interim의 기존 글과 접두사 길이 유지. 다른 도구·비일치 suffix는 원고 규칙으로 가리지 않음 |
| 목록·서명 | 정의 순서, caller 목록, 필수 서명과 실패 차단, ExternalData 닫는 표시 escape |
| 동일성 | 같은 생성 키·같은 입력은 동일 draftId. 객체 순서/escape만 다른 입력도 동일. title·tags·block/사진 순서·caption·observationRef·Unicode 조합 변조는 충돌. 같은 전송의 requestId 변경은 충돌 |
| 현재 revision | 최초 생성 응답 유실 뒤 CAS로 revision 2를 만든 다음 최초 입력 재전송은 2 반환. 최초 hash 유지·신규 revision 0·수정 본문 불변 |
| 실제 경합 | MySQL 8.4 Flyway schema에서 별도 연결·두 동시 /mcp 요청·서로 다른 애플리케이션 실행 경계로 원고 1·revision 1·생성 대응 1. 같은 키의 다른 본문은 성공 1과 충돌 1. UNIQUE를 우회한 사전 조회만의 반례도 확인 |
| rollback·응답 유실 | 암호화/asset/대응 insert/commit 실패는 세 원고 표와 두 대응 표 모두 0, 같은 키 재호출 성공 1. 실제 commit 완료 뒤 HTTP 응답을 끊고 같은 tool_call_id 및 새 ID+같은 requestId 재호출은 동일 draftId |
| 권한·범위 | 현재 사용자 철회·cancel·profile 변경은 기존 고정 오류와 본문/revision 없음. 같은 사용자·대화·profile의 요청/전송을 다음 turn의 origin·다른 루트로 재생하면 맥락 오류·원고 증가 0. 다른 사용자·대화는 이전 원고 미노출. 같은 origin의 두 session에서 같은 tool_call_id와 서로 다른 requestId는 독립 생성 |
| 삭제·tombstone | expiresAt 정확한 경계·STALE·첨부 삭제·purge·정리 재시작 뒤 재전송은 신규 원고 0. GONE hash/참조 제거·본문 없음·tombstone 유지. STALE은 기존 CAS 회복 뒤 현재 revision 반환 |
| E2E | 실제 MCP HTTP→원고 TX commit→응답 단절→동일 인자 재호출→CAS→최초 요청 재호출→삭제. DB 수·typed 결과·사건/화면의 표식 없음과 분석/외부 WRITE 0회 단언 |

이번 계획 보완의 독립 hash·경합 반례 모형은 실제 MySQL·HTTP 검증을 대체하지 않는다.
아래는 후속 구현 검증 명령이며 이번 계획 보완에서는 Gradle과 full E2E를 실행하지 않는다.

```bash
# cwd: 저장소 root
python3 "$SKILL_DIR/scripts/verify_task.py" plan203-content-draft-revision
```

```bash
# cwd: backend/
./gradlew test --tests '*McpContentDraft*' --tests '*ContentDraftCreationHashTest' --tests '*McpToolCatalogTest' --tests '*McpCallerInvariantTest' --tests '*McpMediaObservationToolTest' --tests '*ToolDetailEventStreamTest' --tests '*ToolDetailStreamTest' --tests '*HermesRunEventStreamTest'
./gradlew qualityCheck
```

```bash
# cwd: 저장소 root
python3 -m unittest discover -s hermes/tests
node --test test/unit/doc-links.test.ts test/unit/adr-index.test.ts test/unit/doc-files.test.ts
scripts/check-local.sh --skip-browser
scripts/check-mysql-migration.sh
python3 "$SKILL_DIR/scripts/verify_task.py" plan203-content-draft-revision --audit
```

SKILL_DIR는 설치된 planning 스킬 번들 경로다. 같은 checkout의 Gradle은 순차로 실행한다.
check-local.sh --skip-browser도 backend·Hermes·Node 단위 검사와 full E2E를 실행하므로 구현 후에만 돌린다.
신규 MySQL 경합 시험은 기존 mysqlMigrationTest의 mysql 태그 선택 규칙에 연결하고 위 스크립트로 실행하여 skip 0을 확인한다. 기존 원고 REST·CAS·삭제 시험도 모두 통과해야 한다.
기본 verify_task 실패와 선행 코드 부재는 PHASE_BLOCKED로 보고한다. 단언·timeout·skip·baseline을 완화하지 않는다.
create-pr·content-preview·korean-check·pr-merge를 읽고 한국어 Draft로 올린다. Ready·merge·운영 배포는 하지 않는다.
구현이 끝나면 이 phase를 구현 PR에서 지우고 오래 남을 계약만 feature 문서에 남긴다.
배포 뒤 실제 MCP 왕복은 `fos-home-infra` 담당에 맡기며 환경 값과 실행 명령은 공개 저장소에 쓰지 않는다.
