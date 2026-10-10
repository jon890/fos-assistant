# Phase 02. 서명된 관찰 MCP와 본문 가림

**Execution profile**: deep

## 목표

현재 대화의 관찰을 모델이 조회·기록하되 사용자 정정과 실행 권한, native 이미지 계약과 본문 비공개를 유지한다.
REST와 같은 관심사 PR에서 실제 HTTP MCP 왕복까지 검증한다.

**범위 외**: 저장 서비스·crypto·DDL·삭제·cache 파일 변경, 실제 provider 호출과 호출 생략, 정확도 보증,
UI·원고·snapshot·jobs·finance, 실계정 브라우저, 운영 변경, Hermes core와 새 의존.

## 컨텍스트

**근거 문서**: `docs/features/attachment.md`의 「관찰 API와 MCP 연결 설계」,
`docs/features/mcp.md`의 「관찰 도구 연결 설계」,
`backend/docs/code-architecture.md`의 「관찰 연결의 모듈 배치 설계」,
`hermes/docs/hermes-contract.md`의 「관찰 도구 연결 설계」.
root/backend/hermes AGENTS.md를 읽는다. 실제 planning overlay는 없고 AGENTS.md가 검증을 정한다.
새 페이지·input reader·REST는 앞 phase에서 같은 plan의 실제 파일로 구현되어야 한다.
저장 producer의 실제 main 머지는 PR411로 끝났으며 cache PR422는 필요 없다.
기존 서비스 list와 record는 이 phase에서도 읽기 전용이다.
설치된 planning 번들을 PLANNING_SKILL_DIR로 정하고 처음 구현을 시작하기 전의 저장소 root에서
`python3 "${PLANNING_SKILL_DIR}/scripts/verify_task.py" plan178-media-observation-api`의 종료 코드 0을 확인한다.
순차 실행 중 앞 phase의 신규 파일이 생겼다면 그 파일은 실제 구현·검증 결과여야 하며
초기 기본 검사 증거와 해당 구현 커밋을 함께 대조한다. --audit로 초기 검사를 대신하지 않는다.
현 main에는 캐시 재사용이 없다. 새 MODEL UUID는 같은 UNKNOWN 조건이어도 반드시 새 revision·새 본문이다.
캐시가 추가된 기준에서는 storage/cache 소유자가 UNKNOWN provider/model의 analysisKey 생성과 재사용을
둘 다 끈 실제 producer를 main에 제공해야 한다. 같은 record 시그니처를 유지하는 최소 경계다.
PR422 head 87d97b23에는 UNKNOWN provider/model의 key 생성 제외와 UNKNOWN 후보 재사용 제외가 모두 구현되어 있다.
PR422는 미머지이고 실제 main a1df8f0에는 cache 자체가 없다. 과거 조사 보고의 미구현 표현은 현재 상태의 근거로 쓰지 않는다.
이 계획은 서비스·cache 파일을 고치거나 새 cache worker를 배정하지 않는다.
list와 USER REST는 기존 실제 main producer만으로 구현할 수 있다.

착수 기준의 main SHA와 기준 아카이브를 확보하고 조상 관계 및 실제 symbol을 대조한다.
PR419 head 660f4ac8의 아카이브는 main a1df8f0 이후이며 memory_search 등록·서명·detail/text 가림과
IntegrationTestDoubles의 Hibernate inspector/MemorySearchSqlProbe를 포함한다. PR422도 같은 main 이후의 미머지 변경이다.
현재 main의 symbol과 미머지 head를 구분하고 통합 뒤에는 실제 main의 list/record와 caller/session 계약을 다시 읽는다.
공유 McpController/McpDtos/McpToolService/stream/hook의 변경은 해당 소유자와 조정한다.
공용 Doubles/SQL probe는 이 phase의 변경 소유가 아니다. 덮어쓰거나 별도 시험 컨텍스트로 우회하지 않는다.

| 실제 파일 | 기존 정의와 유지할 뜻 |
| --- | --- |
| `backend/src/main/java/com/bifos/assistant/chat/application/MediaObservationService.java` | `list(CurrentUser,Long,String,int)`와 `record(CurrentUser,Long,Long,long,UUID,MediaObservationInput,ObservationProvenance)` |
| `backend/src/main/java/com/bifos/assistant/mcp/application/McpCallerResolver.java` | `McpCaller resolve(McpPrincipal principal,String toolName,JsonNode fosCtx)` |
| `backend/src/main/java/com/bifos/assistant/mcp/application/McpCaller.java` | `(CurrentUser user,AgentExecution originExecution,McpCallContext context)`, executionId()는 origin ID |
| `backend/src/main/java/com/bifos/assistant/mcp/application/McpCallContext.java` | `(String rootSessionId,String sessionId,String toolCallId)`와 `verify(String,JsonNode,String)` |
| `backend/src/main/java/com/bifos/assistant/orchestration/application/SessionOwnerResolver.java` | `AgentExecution resolve(String profileName,String rootSessionId,String sessionId)`; 등록된 native 자식은 끝난 origin도 유지, origin/루트 CANCELLED는 거절 |
| `backend/src/main/java/com/bifos/assistant/mcp/presentation/McpController.java` | handle(String,McpPrincipal,JsonNode), 기존 caller 판정 후 _fos_ctx만 제거해 handler 호출 |
| `backend/src/main/java/com/bifos/assistant/shared/util/ExternalData.java` | `String wrap(String body)`, 닫는 태그 변형 escape |
| `hermes/plugins/fos-ctx/hooks.py` | REQUIRED_TOOLS와 pre_tool_call, _control_plane_context의 서명 실패 차단 |

현재 agent.hermesProfile이 profile 연결이며 별도 사용자-profile 바인딩 표는 없다.
AgentService.findById/Agent.isReadableBy/enabled/isDeleted/hermesProfile을 읽어 현재 상태를 확인한다.
허용 사용자 검사는 shared.auth.UserAccessPolicy.allowed(Long)다.
세션 등록은 HermesSessionBindingRepository.findByProfileNameAndSessionId이며 최근 실행 추정은 금지다.
origin.provider/model은 시작 시 요청값이 들어갈 수 있어 실제 모델의 증거로 쓰지 않는다.
native 자식의 실행 출처는 origin만 증명하며 provider 정체성을 독립적으로 증명하지 못한다.

## 의도 메모

최소 도구 문구만 추가하며 매 실행 preamble·스킬·native 이미지 입력은 바꾸지 않는다.
각 도구 문구에 현재 대화 한정·확인 범위·정정 보호·미검증 모델·권한 오류를 짧게 설명한다.
MCP MODEL은 provider/model=UNKNOWN, 각 버전=null을 저장한다. 이는 추측 이름이 아닌 미확인 표식이다.
사용자 선언은 USER REST만 받으며 모델은 USER kind/provenance/evidence/execution/provider를 주입할 수 없다.
provider 검증 표시가 저장 schema에 없어 MODEL은 모두 MODEL_UNVERIFIED로 응답한다.
cache는 저장 조건별 재사용일 뿐 실제 호출 생략이나 정확도 보증과 다르다. cache 코드와 계약을 확대하지 않는다.
UNKNOWN은 대문자 예약 표식이다. 현재 identity는 미검증이며 관찰 보존은 모델 추론의 보존이다.
cache가 있는 기준에서만 두 제외 조건이 실제 main에 합쳐지고 서로 다른 실행·UUID·본문 회귀가 통과한 뒤 MODEL 연결을 실행한다.

## Blocked 조건

기본 verify_task.py가0이 아니거나 실제 서비스 시그니처·caller 계약이 위 표와 달라지면 PHASE_BLOCKED를 보고한다.
새 producer는 앞 phase의 실제 구현만 인정한다. 빈 파일 선생성·--audit로 기본 우회는 금지한다.
실제 provider 검증·DDL·저장서비스 변경이 필요해지면 이 관심사에서 임의로 구현하지 않고 코디네이터에게 경계를 보고한다.
기준 main에 cache 재사용이 합쳐졌는데 UNKNOWN analysisKey 생성·재사용 제외가 없으면 이 MODEL 연결 phase는 PHASE_BLOCKED다.
PR422 head에 두 제외가 존재한다는 사실은 확인됐지만 미머지 브랜치 존재만으로 이 조건을 만족하지는 않는다.
실제 main의 두 제외 symbol과 실제 회귀 통과를 확인한다.
현 main411처럼 캐시가 없는 기준은 이 추가 producer를 기다리지 않는다.
current source 범위를 벗어난 변경·다른 저장소·원본 Claude 파일·계획 단독 commit/push/PR은 금지한다.

## 작업 항목

### 1. MCP 도구와 현재 권한

새 McpMediaObservationTools는 두 definition과 `list(McpCaller,String,int)`,
`record(McpCaller,String,long,UUID,JsonNode)`를 구현한다.
실제 @Service와 constructor 주입으로 등록하고 기존 Clock, UserAccessPolicy, AgentService, SessionOwnerResolver를 쓴다.
짧은 현재 권한 조회는 application의 REQUIRES_NEW read-only TransactionTemplate으로 끝내고 record를 호출한다. 도구 전체를 외부 트랜잭션으로 감싸지 않는다.
McpToolService.tools()에 두 definition을 memoryRemember.definition() 직전에 등록하고 기준 main의 기존 도구·상대 순서를 보존한다.
Memory419가 합쳐졌다면 뒤에 등록된 memory_search도 보존한다. 과거 마지막 memory_remember 규칙으로 되돌리지 않는다.
tools의 총 개수나 마지막 도구를 상수로 단언하지 않고 이름·중복 없음·schema 의미로 검사한다.
McpController는 두 handler를 기존 caller.resolve와 살펴보기 판정 뒤로 추가한다.
Java21 Map.ofEntries와 Map.entry로 handler map을 구성하고 작업 기준의 기존 모든 handler를 보존한다.
현재 8개 또는 Memory419의 9개라는 개수로 목록을 다시 만들지 않는다. 새 도구 2개를 더해도 Map.of의 10쌍 한도에 의존하지 않는다.
list만 CHECK_TREE_TOOLS에 추가한다. record는 CHECK_TREE_WRITE_TOOLS에도 넣지 않는다.
JSON의 추가 칸은 중첩까지 reader로 거절하고 _fos_ctx는 기존 경로에서만 제거한다.
McpDtos에 list/record 인자 타입을 두며 원문을 숨기는 toString을 둔다.
list 인자는 afterAssetId/limit만, record는 assetId/expectedRevision/requestId/observation만 받는다.
선택 인자의 명시null은 생략과 같게, 필수 인자 null·소수·overflow·축약 UUID는 -32602로 거절한다.

실제 handle(String,McpPrincipal,@RequestBody JsonNode)는 JSON 역직렬화에 성공한 뒤에만 실행된다.
malformed/truncated JSON은 controller·caller·tool handler 전에 HttpMessageNotReadableException으로 실패한다.
McpController 안의 한정 @ExceptionHandler(HttpMessageNotReadableException.class)로
HTTP200과 {jsonrpc:"2.0",id:null,error:{code:-32700,message:"Parse error"}}를 반환한다.
id를 원문에서 추출하지 않으며 예외 메시지·원문·cause·DTO를 로그에 싣거나 GlobalExceptionHandler.handleUnexpected로 넘기지 않는다.
유효 JSON의 잘못된 arguments는 기존 -32602로 구분한다. 공통 GlobalExceptionHandler와 인증 필터는 수정하지 않는다.
이 parse error 처리는 /mcp 전체에 적용되므로 다른 도구·initialize/tools/list의 malformed JSON에도 영향이 있다.
AgentTokenAuthenticationFilter에서 처리하는 토큰 누락·오류·폐기는 기존401, Origin 거절은 기존403을 유지한다.
유효한 기존 Memory/에이전트 도구의 성공·인자 오류 계약은 그대로다.

McpMediaObservationTools는 origin.conversationId만 쓰고 null 대화는 invalidContext로 차단한다.
호출 직전과 결과 반환 직전에 짧은 새 read-only 트랜잭션으로 DB에서 caller를 재확인한다.
원래 서명한 profile은 origin.profileName이고 context의 root/session은 최초 값 그대로 사용한다.
SessionOwnerResolver.resolve로 같은 origin을 다시 얻고 다른 ID가 되면 거절한다.
origin 또는 root CANCELLED, session 미등록·교체, 허용 사용자 해제, 에이전트 삭제·disabled·비공개 접근 철회,
agent.hermesProfile과 origin.profileName 불일치는 같은 invalidContext다.
토큰은 기존 AgentTokenService 인증의 폐기를 유지하며 임의 profile 선택·다른 토큰 대체를 하지 않는다.
등록된 native 자식은 origin이 SUCCEEDED/FAILED여도 허용한다. root/origin 취소와 현재 권한은 다시 본다.
이 권한 검사는 새 두 도구에만 추가한다. 기존 Memory 도구의 caller 의미를 전역으로 바꾸지 않는다.

record source는 MODEL_RESULT, executionId=caller.executionId(), provider/model=UNKNOWN,
각 버전/observedAt=null, schemaVersion=1, promptVersion=media-observation-v1이다.
claim.kind는 VISUAL/OCR만 허용하고 source/evidence는 서버가 만든다. source는 UUID 재시도에서 같게 유지한다.
현재 USER 보호와 CAS·alias·지문 판정은 기존 record가 한다. 새 MODEL이 현재 USER를 대체할 수 없다.
호출 직전 철회가 확정됐으면 서비스에 도달하지 않는다.
철회와 저장 경합은 기존 서비스의 별도 commit 경계이므로 이미 저장한 revision을 지우지 않는다.
응답 직전 철회가 보이면 결과 본문을 내지 않는다. 원자적 저장 취소를 보장한다고 주장하지 않는다.

성공 JSON은 feature의 DTO와 같은 필드를 직렬화하고 ExternalData.wrap으로 감싼 text content에 낸다.
형식 오류는 -32602, 업무 오류는 isError이며 공개 오류 코드만 낸다.
409은 정규 revision=N을 숫자로 읽어 currentRevision만 싣고 저장 본문·지문·타 사용자 정보를 싣지 않는다.
예상하지 못한 실패는 고정 오류이며 원문 메시지·cause·DTO를 로그에 넣지 않는다.

### 2. hook·privacy와 기존 계약

hooks.py REQUIRED_TOOLS에 두 이름을 넣어 토큰/session/도구 호출 ID가 없으면 fail closed로 막는다.
기존 ctx의 덮어쓰기와 HMAC v1, native 등록·depth 한도·connector 분기·이미지 전달을 유지한다.
HermesRunEventStream.toRunEvent는 bare/prefixed 두 도구의 tool.started/tool.completed/tool.failed에서
detail과 text를 모두 수집 전에 null로 만든다. root/data의 preview/detail/result와 delta/text/output을 모두 대상으로 한다.
Memory419의 memory_search 가림은 두 경로 모두 보존하고 REQUIRED_TOOLS의 기존 이름을 유지한다.
기존 follow_up_propose/memory_remember처럼 수집 전 차단한다. 관리자 ToolDetailPolicy나 화면에서만 숨기지 않는다.
새 도구를 McpMemoryRemember.INTERNAL_TOOLS와 ToolDetailPolicy.PUBLIC_TOOLS에 넣지 않는다.
Hermes 내부의 새 지점은 쓰지 않아 hermes_contract.py를 변경할 이유가 없다.
문서의 미구현 표시는 이 PR의 구현한 절에 한해서 바꾸고 정확도·전체 원고 흐름 완료 표현은 추가하지 않는다.

### 3. 같은 phase의 회귀와 실제 HTTP MCP 왕복

`McpMediaObservationToolTest`는 실제 /mcp HTTP, profile 토큰, McpCallSigner, sent 첨부와 저장 서비스를 쓴다.
BackendIntegrationTest의 RANDOM_PORT와 Java HttpClient로 실제 AgentTokenAuthenticationFilter와 Jackson3 바인딩을 통과한다.
chat의 package-private ObservationFixture를 상속한다고 가정하지 않고 이 신규 시험 안에 실제 sent 첨부·암호화 HTTP fixture를 구현한다.
시험별 @MockitoBean/@MockitoSpyBean/@Import를 추가하지 않고 기존 IntegrationTestDoubles/TestClock을 쓴다.
공용 spy가 필요하면 소유자에게 조정을 요청하며 공용 컨텍스트·MemorySearchSqlProbe는 이 phase에서 고치지 않는다.
유효 토큰으로 합성 OCR 표식을 넣은 malformed JSON과 중간에 끊긴 JSON을 보내 -32700/id=null/고정 Parse error를 확인한다.
각 요청 전후 media_observation/media_observation_request 증가0, 응답·DTO 문자열/직렬화의 표식0,
GlobalExceptionHandler와 실제 HTTP 처리 로그의 표식0·원문/cause0을 ListAppender로 확인하고 finally에서 appender를 뗀다.
캡처한 ILoggingEvent의 formattedMessage와 ThrowableProxy의 메시지·cause 사슬을 검사한다.
공통 unexpected error가 발생하지 않았는지도 확인하며 로그 설정을 낮춰 표식을 숨기는 방식으로 통과시키지 않는다.
다른 기존 도구명과 initialize/tools/list를 포함한 파싱 실패도 같은 고정 결과인지 확인한다.
동일 합성 본문을 토큰 누락·오류·폐기 및 Origin 요청으로 보내 기존401/403과 저장/alias 증가0·로그 표식0을 확인한다.
유효 JSON의 잘못된 arguments는 -32602이고 기존 Memory/에이전트의 성공 경로는 그대로인지 확인한다.
파싱 실패 전에 handler가 이미 실행됐다고 주장하거나 tools 직접 호출 시험으로 HTTP 로그 검사를 대체하지 않는다.
list→record→동일 UUID 재시도→list와 뒤 turn 재조회를 왕복해 revision·고정 observedAt·출처·ExternalData를 확인한다.
다른 사용자/대화 asset, _fos_ctx 누락·위조·다른 도구 서명, 잘못된 profile,
등록 안 된 native child·끝난 부모의 등록된 child·origin/root 취소·등록 삭제·현재 연결/접근 철회를 확인한다.
현재 profile 연결과 본문 사유가 다른 실패도 동일 invalidContext인지 확인한다.
모델 인자의 USER/provenance/evidence/execution/provider 삽입과 SUCCEEDED의 부실 coverage를 거절한다.
현재 USER 이후 MODEL, CAS 경합, 같은 UUID 다른 본문, 삭제/만료 응답 장벽과 crypto 실패를 확인한다.
native child가 부모 provider/requested model을 자신의 실제 모델로 기록하지 않고 UNKNOWN/MODEL_UNVERIFIED가 되는지도 확인한다.
USER/MODEL 각각 원본 교체·복호화/본문 검증 실패를 fixture로 만들어 실제 list에서 observation=null이면
provenance가 남아 있어도 sourceAssurance=null인지 확인한다. 본문이 남은 ANALYSIS_STALE/NEEDS_REVIEW와 구분한다.
원본 지문은 원본 식별, coverage는 제출자의 선언이며 verified provider·실제 영역 판독 증명으로 해석하지 않는다.
같은 사진·coverage·UNKNOWN identity의 다른 실행 두 개가 다른 requestId와 본문을 제출하면
두 번째 결과는 새 revision·두 번째 본문·두 번째 origin이어야 한다. 첫 본문 재사용은 실패다.
같은 UUID의 재시도만 최초 alias를 읽으며 실행 번호를 가짜 버전에 넣거나 USER로 위장하지 않는다.
허용 상태 검사 뒤 철회와 write가 경합하는 시험은 본문이 차단되며 이미 확정한 revision이 유지되는 허용 결과를 확인한다.

`McpMediaObservationToolsTest`는 원본 오류와 합성 OCR 표식이 로그·DTO·오류 결과에 없는지 ListAppender로 확인한다.
ExternalData 닫는 태그 변형과 본문 속 명령은 표시 안에 남아야 한다.
HermesRunEventStreamTest는 두 bare/prefixed 도구·start/complete/fail과 root/data의
preview/detail/result, delta/text/output 조합에서 detail/text=null, RunEvent.toString/JSON의 합성 OCR 표식0을 확인한다.
ToolDetailEventStreamTest는 실제 SSE 입력과 ToolDetailScope.NONE/ALL/prefixes, connector 전후 조합에서 같은 음성 회귀를 실행한다.
ToolDetailStreamTest는 실제 HermesRunEventStream으로 합성 SSE를 파싱한 결과를 기존 대화 사건 소비자에 넘긴다.
각 root/data·본문 칸·bare/prefixed·connector 전후 조합을 ADMIN/MEMBER로 실행해 RunEvent 문자열/JSON과 대화 SSE의 표식0,
저장 TOOL_STARTED/TOOL_COMPLETED detail=null을 확인한다. 가짜 대역이 본문을 미리 지워 기대값을 만드는 시험으로 대신하지 않는다.
현재 ExecutionEventRecorder.record(AgentExecution,RunEvent,int)는 tool.failed를 저장하지 않으므로 해당 실패 사건의 저장 증가0도 확인한다.
저장 정책 자체는 이 phase에서 바꾸지 않는다.
현재 DB/SSE 유출을 재현했다는 주장은 하지 않는다. 수집 DTO와 저장 detail의 비노출을 각각 증명한다.
McpMemoryRememberToolTest는 관찰 읽기 뒤 memory_remember가 바로 저장 대신 제안이 되는지 확인한다.
McpMemoryToolTest/McpFollowUpToolTest의 hasSize(8)은 이름 기반 포함·중복 없음·schema 의미 검사로 바꾼다.
McpAgentToolsTest의 connector-agent 거절 parameter에 두 이름을 추가한다.
McpMemoryRememberToolTest의 바로 저장·제안 의미와 기준 main의 도구 등록 계약을 유지한다.
Memory419가 합쳐졌다면 기존 memory_search 이름·schema·detail/text 가림 회귀도 보존하고 실행한다. 기억 검색 구현은 변경하지 않는다.
McpToolServiceTest의 유일한 명시 생성자 호출에 새 tools 의존 mock을 추가한다.
test_fos_ctx.py는 두 이름의 무서명 차단·악의 _fos_ctx 덮어쓰기·native session 서명과 기존 이미지 도구 유지 회귀를 추가한다.

기존 e2e의 HTTP wrapper와 test/e2e/mcp-context.ts의 서명을 써
test/e2e/scenarios/media-observation-mcp.ts를 만들고 run.ts의 기존 MCP 회귀 뒤에 등록한다.
자기 구현을 복제한 fake parser가 아니라 실행 중 actual /mcp에 list/record를 보내고 뒤 turn에서 다시 읽는다.
기존 Context.hermes.holdNextRun()/waitForHeldRun()/heldRun()으로 실제 대화 실행을 유지하고
heldRun().sessionId로 서명한다. 종료는 releaseHeldRun()으로 보장하며 finally에서 시험 상태를 정리한다.
`test/e2e/scenarios/native-delegation-mcp.ts`와 `test/e2e/scenarios/delegation.ts`의 실행 유지·등록 패턴을 재사용한다.
가짜 Hermes의 도구 응답을 기대값으로 삼거나 신규 fake 처리기를 만들 필요가 없다.
관찰 body budget·USER 정정 보호·missing signature·폐기 토큰·cancelled native child를 왕복한다.
모델/provider를 실제로 부르지 않았음을 명시한다. assertion은 반환 JSON·DB 효과·로그 증거를 확인한다.
run.ts의 암호화 KEK 환경이 현재 없으므로 공개 test fixture의 KEK_FILE과 ACTIVE_KEK_ID를 test 전용 env에 추가한다.
테스트용 `backend/src/test/resources/data-encryption/test-kek.keys`만 쓰고 운영 KEK를 읽지 않는다.
추가할 환경 이름은 ASSISTANT_DATA_ENCRYPTION_KEK_FILE과 ASSISTANT_DATA_ENCRYPTION_ACTIVE_KEK_ID이며
활성 ID는 이 공개 fixture의 test-kek-1이다. KEK 경로는 ROOT와 위 fixture 경로로 계산한다.
e2e 전역 인증·암호화 설정 변경이 기존 시나리오를 깨뜨리지 않는지 전체 실행한다.

## 검증

backend의 JDK/Gradle과 test profile·테스트용 KEK를 사용한다. 운영 환경값은 필요 없다.
Node는22.18 이상이다. Python의 mcp/PyYAML/Pillow 요구 버전은 scripts/check-local.sh의 check_hermes를 따른다.
e2e run.ts가 임시 profile key·JWT·첨부 폴더와 H2를 만들며 운영 설정은 쓰지 않는다.
Docker는 로컬 MySQL 검사에 필요하며 없으면 해당 검증 공백을 남기고 완료로 처리하지 않는다.

```bash
cd backend && ./gradlew test --tests '*McpMediaObservationToolTest' --tests '*McpMediaObservationToolsTest' --tests '*McpCallerInvariantTest' --tests '*McpMemoryToolTest' --tests '*McpFollowUpToolTest' --tests '*McpAgentToolsTest' --tests '*McpMemoryRememberToolTest' --tests '*McpToolServiceTest' --tests '*HermesRunEventStreamTest' --tests '*ToolDetailEventStreamTest' --tests '*ToolDetailStreamTest' --tests '*AttachmentInspectEndpointTest' --tests '*MediaObservation*'
python3 -m unittest discover -s hermes/tests -p 'test_fos_ctx.py'
node test/e2e/run.ts
cd backend && ./gradlew qualityCheck
scripts/check-local.sh --skip-browser
node --test test/unit/feature-covers.test.ts test/unit/doc-files.test.ts
node scripts/pr-size.mjs origin/main HEAD
git diff --check
```

모든 명령 종료 코드0, 새 회귀 assertion 실행과 본문 비노출을 확인한다.
scripts/check-local.sh는 backend test·MySQL·web typecheck/build/test·e2e·unit·Hermes·public-safe·quality 실패를 전파한다.
새 화면이 없어 browser는 생략하고 Draft CI의 전체 검사는 실제 publisher가 확인한다.
운영 코드 목표는 이 phase700줄 이내, 최종 PR1,500줄 이하이며 tests/docs를 별도 집계한다.
초과하면 기능·검증이 함께 작동하는 REST와 MCP 경계로 PR을 나누도록 코디네이터에게 보고한다.
규모 예외를 스스로 붙이거나 tests/docs만 따로 빼서 완료를 주장하지 않는다.

## 변경 파일

| 파일 | 변경 |
| --- | --- |
| `backend/src/main/java/com/bifos/assistant/mcp/application/McpMediaObservationTools.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/mcp/application/McpToolService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/mcp/presentation/McpController.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/mcp/presentation/McpDtos.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/hermes/HermesRunEventStream.java` | 수정 |
| `hermes/plugins/fos-ctx/hooks.py` | 수정 |
| `backend/src/test/java/com/bifos/assistant/mcp/McpMediaObservationToolTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/mcp/application/McpMediaObservationToolsTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/mcp/application/McpToolServiceTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/mcp/McpMemoryToolTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/mcp/McpFollowUpToolTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/mcp/McpAgentToolsTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/mcp/McpMemoryRememberToolTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/hermes/HermesRunEventStreamTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/hermes/ToolDetailEventStreamTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/ToolDetailStreamTest.java` | 수정 |
| `hermes/tests/test_fos_ctx.py` | 수정 |
| `test/e2e/scenarios/media-observation-mcp.ts` | 신규 |
| `test/e2e/run.ts` | 수정 |
| `docs/features/attachment.md` | 수정 |
| `docs/features/mcp.md` | 수정 |
| `backend/docs/code-architecture.md` | 수정 |
| `hermes/docs/hermes-contract.md` | 수정 |
