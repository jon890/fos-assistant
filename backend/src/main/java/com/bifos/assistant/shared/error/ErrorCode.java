package com.bifos.assistant.shared.error;

import org.springframework.http.HttpStatus;

/** 웹 클라이언트에 돌려주는 고정 오류 코드다. */
public enum ErrorCode {
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED),
    /** 관리자가 허용 목록에서 끈 사용자의 웹 토큰이다. 웹이 이 코드를 받으면 세션을 끊는다(ADR-059). */
    ACCESS_REVOKED(HttpStatus.UNAUTHORIZED),
    FORBIDDEN(HttpStatus.FORBIDDEN),
    CONVERSATION_NOT_FOUND(HttpStatus.NOT_FOUND),
    AGENT_NOT_FOUND(HttpStatus.NOT_FOUND),
    MEMORY_NOT_FOUND(HttpStatus.NOT_FOUND),
    /** 없는 실행과 남의 실행을 같은 응답으로 숨긴다. 번호를 훑어 남의 것이 있는지 알아낼 수 없게 한다. */
    EXECUTION_NOT_FOUND(HttpStatus.NOT_FOUND),
    EXECUTION_NOT_RUNNING(HttpStatus.CONFLICT),
    CONVERSATION_BUSY(HttpStatus.CONFLICT),
    /** 그 사용자가 여러 대화와 위임으로 동시 실행 한도를 모두 쓰고 있다(ADR-069). */
    USER_BUSY(HttpStatus.CONFLICT),
    /** 먼저 살펴보기를 막는 까닭이 있다. 까닭은 싣지 않고 화면이 상태를 다시 읽어 그린다(ADR-080). */
    PROACTIVE_CHECK_UNAVAILABLE(HttpStatus.CONFLICT),
    /** 없는 보고와 다른 사용자의 보고를 같은 응답으로 숨긴다. */
    PROACTIVE_CHECK_NOT_FOUND(HttpStatus.NOT_FOUND),
    /** 대기 메시지가 상한에 닿았다. 개수가 찼거나, 더하면 합친 글이 메시지 길이 상한을 넘는다. */
    PENDING_QUEUE_FULL(HttpStatus.CONFLICT),
    /** 취소하려는 대기 메시지가 이미 보내졌거나 없다. 남의 대화의 대기 메시지도 같은 응답으로 숨긴다. */
    PENDING_MESSAGE_NOT_FOUND(HttpStatus.NOT_FOUND),
    MESSAGE_NOT_LATEST(HttpStatus.CONFLICT),
    /** 그 대화의 전달 묶음이 아니거나 없는 묶음이다. 남의 대화의 묶음도 같은 응답으로 숨긴다(ADR-075). */
    DELIVERY_NOT_FOUND(HttpStatus.NOT_FOUND),
    /** 묶음이 {@code DELIVERING} 이나 {@code DELIVERED} 이거나, 넘길 결과가 남지 않았거나, 대화에 흐름이 붙었다(ADR-075). */
    DELIVERY_NOT_RETRYABLE(HttpStatus.CONFLICT),
    MEMORY_SCOPE_REQUIRED(HttpStatus.BAD_REQUEST),
    /** 민감 항목을 항상 싣게 하려 했다. 조립 판정이 틀려도 민감 본문이 나가지 않게 저장할 때 막는다. */
    MEMORY_SENSITIVE_ALWAYS(HttpStatus.BAD_REQUEST),
    /** 민감 본문을 암호화하거나 풀 key 가 없다. 평문으로 내려 저장하지 않는다(ADR-055). */
    MEMORY_ENCRYPTION_UNAVAILABLE(HttpStatus.CONFLICT),
    /** 들이는 사이에 같은 출처나 같은 이름의 줄이 먼저 들어왔다. 다시 올리면 그 항목이 DUPLICATE 나 CONFLICT 로 나온다(ADR-058). */
    /** 같은 주인과 collection 에 같은 이름의 문서가 이미 있다(ADR-057). */
    MEMORY_DOCUMENT_EXISTS(HttpStatus.CONFLICT),
    /** 화면이 읽은 판이 지금 판이 아니다. 그 사이에 다른 수정이 있었다(ADR-057). */
    MEMORY_REVISION_CONFLICT(HttpStatus.CONFLICT),
    /** 없는 서비스 토큰과 남의 서비스 토큰을 같은 응답으로 숨긴다(ADR-056). */
    SERVICE_TOKEN_NOT_FOUND(HttpStatus.NOT_FOUND),
    /**
     * 민감 항목은 Memory 목록의 수정 경로로 고치지 못한다. 목록이 본문을 싣지 않아 그 요청이 본문을 읽지 않은 채
     * 덮어쓴다(ADR-055).
     */
    MEMORY_SENSITIVE_NOT_EDITABLE(HttpStatus.CONFLICT),
    AGENT_DISABLED(HttpStatus.CONFLICT),
    /** 다른 요청이 같은 에이전트 설정을 바꾸고 있어 잠금 대기 시간이 지났다. */
    AGENT_BUSY(HttpStatus.CONFLICT),
    /** 셸이나 파일, 다른 사람의 대화에 닿는 toolset은 그룹 에이전트에 둘 수 없다. */
    AGENT_TOOLS_REQUIRE_PRIVATE(HttpStatus.CONFLICT),
    /** 연결은 비공개 에이전트에만 붙인다. 남이 주인의 계정으로 외부 서비스를 쓰지 못하게 한다(ADR-083). */
    AGENT_CONNECTIONS_REQUIRE_PRIVATE(HttpStatus.CONFLICT),
    /**
     * 연결이 붙은 에이전트는 주인을 바꾸지 못한다. 남의 값이 든 profile 이 새 주인에게 넘어가고, 새 주인은 남의 연결이라 떼지도
     * 못한다. 지금 주인이 먼저 뗀다(ADR-083).
     */
    AGENT_HAS_CONNECTIONS(HttpStatus.CONFLICT),
    /** 셸이나 파일 도구를 켜려 했지만 Hermes 쪽 사용자별 격리 실행 공간이 준비되지 않았다(ADR-086). 도구 목록은 바뀌지 않았다. */
    AGENT_SANDBOX_UNAVAILABLE(HttpStatus.CONFLICT),
    /**
     * 셸이나 파일 등급의 도구가 켜진 에이전트의 주인을 바꾸려 했다(ADR-086). 그 profile 의 실행 공간은 옛 주인의 디렉터리를
     * 가리키고 컨테이너도 재사용되므로, 도구를 먼저 끈 뒤에만 주인을 바꾼다.
     */
    AGENT_OWNER_CHANGE_REQUIRES_SHELL_OFF(HttpStatus.CONFLICT),
    /**
     * 셸이나 파일 등급의 도구를 켜거나 켠 채 두려 했지만 그 에이전트에 올린 스킬 가운데 환경 값이나 자격 증명 파일을 요청하는
     * 것이 있다(ADR-086). Hermes 가 그 값을 실행 공간에 넣으므로 그 스킬을 먼저 고치거나 지워야 한다. 도구 목록은 바뀌지
     * 않았다.
     */
    AGENT_SKILL_REQUESTS_SECRETS(HttpStatus.CONFLICT),
    /** 요청자가 만들 수 있는 에이전트 수를 이미 채웠다. 지운 에이전트는 세지 않는다. */
    AGENT_LIMIT_REACHED(HttpStatus.CONFLICT),
    /** Hermes가 저장 뒤 읽은 toolset 목록을 요청한 목록과 다르게 돌려줬다. */
    AGENT_TOOLS_NOT_APPLIED(HttpStatus.BAD_GATEWAY),
    /** 호출자에게 연결한 Hermes profile이 없다. 다른 사용자의 profile을 빌리지 않는다. */
    HERMES_BINDING_MISSING(HttpStatus.CONFLICT),
    /** 바인딩은 있지만 이 호스트에 API key가 준비되지 않았다. */
    HERMES_PROFILE_KEY_MISSING(HttpStatus.CONFLICT),
    HERMES_BINDING_DISABLED(HttpStatus.CONFLICT),
    /** 그 이름의 profile 이 Hermes 에 이미 있다. 덮지 않고 멈춘다. */
    HERMES_PROFILE_EXISTS(HttpStatus.CONFLICT),
    /** 그 메일 주소가 허용 목록에 이미 있다. 무엇이 겹쳤는지 화면이 말할 수 있게 따로 적는다. */
    PERSON_EMAIL_TAKEN(HttpStatus.CONFLICT),
    /** 그 profile 이름을 허용 목록이나 에이전트가 이미 쓴다. */
    PERSON_PROFILE_TAKEN(HttpStatus.CONFLICT),
    PERSON_NOT_FOUND(HttpStatus.NOT_FOUND),
    /**
     * profile 을 만들다 실패해 만든 것을 되돌렸다.
     *
     * <p>되돌렸으므로 같은 이름으로 다시 시도할 수 있다. 되돌리기까지 실패한 경우는 이 코드가 아니라
     * 원래 실패한 오류가 그대로 올라오고, 되돌리기 실패는 로그에만 남는다.
     */
    HERMES_PROVISION_FAILED(HttpStatus.BAD_GATEWAY),
    HERMES_RUN_FAILED(HttpStatus.BAD_GATEWAY),
    HERMES_RUN_TIMEOUT(HttpStatus.GATEWAY_TIMEOUT),
    HERMES_UNAVAILABLE(HttpStatus.BAD_GATEWAY),
    /**
     * Hermes 가 동시 실행 한도에 닿아 429 로 거절했다.
     *
     * <p>공유 gateway 는 한도를 모든 profile 이 나눠 쓰므로, 한 사람이 한도를 채우면 다른 사람의
     * 대화가 이 코드로 실패한다. Hermes 에 닿지 못한 것과 원인이 달라 따로 적는다. 여기서 다시
     * 보내지 않는다. 붐비는데 다시 보내면 더 붐빈다.
     */
    HERMES_BUSY(HttpStatus.TOO_MANY_REQUESTS),
    /**
     * 고른 provider 의 계정이 전부 막혀 그 실행이 실패했다. 다른 모델로 넘기지 않는다.
     *
     * <p>실행 줄에 남는 이름이기도 하다.
     */
    PROVIDER_BLOCKED(HttpStatus.BAD_GATEWAY),
    /** 자식 실행이 다시 자식을 부르려 했다. 깊이를 1로 제한한다. */
    ORCHESTRATION_DEPTH_EXCEEDED(HttpStatus.CONFLICT),
    /** 흐름의 한 단계가 정한 출력 계약을 지키지 않았다. 원문을 그대로 다음 단계로 넘기지 않는다. */
    ORCHESTRATION_CONTRACT_BROKEN(HttpStatus.BAD_GATEWAY),
    /** 흐름의 한 단계가 실패했다. 어느 단계인지는 실행 줄의 {@code error_code} 가 적는다. */
    ORCHESTRATION_STEP_FAILED(HttpStatus.BAD_GATEWAY),
    /**
     * 화면이 보고 있던 본문이 지금 본문이 아니다.
     *
     * <p>{@code SOUL.md} 에는 판 번호가 없어 값으로만 달라진 것을 안다. 쓰기 직전에 다시 읽어 화면이
     * 받아 간 지문과 다르면 거절하고, 화면이 새 본문을 다시 읽는다.
     */
    PERSONA_STALE(HttpStatus.CONFLICT),
    /**
     * 그 첨부가 있었지만 보관 기간이 지났거나 사용자가 지워 파일이 없다.
     *
     * <p>있었다는 것을 알리는 응답이라 없는 첨부와 남의 첨부에는 쓰지 않는다. 그 둘은
     * {@code CONVERSATION_NOT_FOUND} 로 숨긴다. 없는 번호에 이 코드를 내면 번호를 훑어 남의 것이 있는지
     * 알아낼 수 있다.
     */
    ATTACHMENT_GONE(HttpStatus.GONE),
    /**
     * 대화 폴더에 그 파일이 없거나, 내주지 않는 확장자이거나, 실제 경로가 대화 폴더 밖이다.
     *
     * <p>세 경우를 가르지 않는다. 가르면 폴더 밖에 무엇이 있는지 훑어 알아낼 수 있다.
     */
    ARTIFACT_NOT_FOUND(HttpStatus.NOT_FOUND),
    /** 그 결과물이 있었지만 보관 기간이 지나 파일을 지웠다. 행에 지운 시각이 적힌 경로에만 쓴다. */
    ARTIFACT_GONE(HttpStatus.GONE),
    /**
     * MCP 호출의 {@code _fos_ctx} 를 믿을 수 없거나, 그것이 가리키는 origin 실행을 하나로 정하지 못했다.
     *
     * <p>서명 없음, 서명 틀림, origin 없음, origin 둘 이상을 가르지 않는다. 가르면 모델이 어느 값을 흉내 내야
     * 통과하는지 훑어 알아낼 수 있다. 이유는 서버 로그에만 남긴다. 근거는 ADR-031 이다.
     */
    MCP_CALL_CONTEXT_INVALID(HttpStatus.FORBIDDEN),
    /**
     * 하위 에이전트 session 을 등록할 수 없다.
     *
     * <p>서명, 부모, session 값 중 무엇이 틀렸는지 밖에 알리지 않는다. 가르면 남의 session 이 도는지 훑어 알아낼
     * 수 있다. 이유는 서버 로그에만 남긴다. 근거는 ADR-037 이다.
     */
    SESSION_BINDING_REJECTED(HttpStatus.FORBIDDEN),
    /**
     * 커넥터 도구 호출의 판정 요청을 믿을 수 없다.
     *
     * <p>본문의 모양과 서명 중 무엇이 틀렸는지 밖에 알리지 않는다. 이유는 서버 로그에만 남긴다. hook 은 이 답을 받으면
     * 그 호출을 막는다. 근거는 ADR-049 이다.
     */
    CONNECTOR_POLICY_REJECTED(HttpStatus.FORBIDDEN),
    /** 그 하위 에이전트 session 이 다른 origin 실행으로 이미 등록돼 있다. 덮어쓰지 않는다. */
    SESSION_BINDING_CONFLICT(HttpStatus.CONFLICT),
    /** 그 에이전트의 지금 버전 디렉터리에 그 이름의 올린 스킬이 없다. */
    SKILL_NOT_FOUND(HttpStatus.NOT_FOUND),
    /**
     * Hermes 가 이미 같은 이름의 스킬을 갖고 있다.
     *
     * <p>profile 로컬 스킬이 외부 스킬보다 먼저 선택되므로 같은 이름으로 올리면 올린 것이 가려진다.
     * 근거는 ADR-034 다.
     */
    SKILL_NAME_TAKEN(HttpStatus.CONFLICT),
    /**
     * 메시지가 {@code /이름} 으로 시작했는데 그 이름이 그 에이전트의 켜진 스킬이 아니다.
     *
     * <p>Hermes 에 보내지 않고 대화, 메시지, 실행도 만들지 않는다. {@code skills} toolset 이 꺼진 에이전트는
     * 켜진 스킬이 없는 것으로 본다. 근거는 ADR-035 다.
     */
    SKILL_COMMAND_UNKNOWN(HttpStatus.BAD_REQUEST),
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST),
    /**
     * 그룹이 숨긴 provider 나 모델을 고르거나 그것으로 실행하려 했다. 다른 모델로 바꾸지 않는다(ADR-054).
     *
     * <p>실행 줄에 남는 이름이기도 하다.
     */
    MODEL_HIDDEN(HttpStatus.CONFLICT),
    /** 커넥터의 확인 도구가 입력한 값을 거절했다. 공통 어휘 {@code credential_rejected} 다(ADR-043). */
    CONNECTOR_CREDENTIAL_REJECTED(HttpStatus.BAD_REQUEST),
    /** 입력한 값으로는 그 대상에 접근할 수 없다. 공통 어휘 {@code forbidden} 이다. */
    CONNECTOR_FORBIDDEN(HttpStatus.FORBIDDEN),
    /** 커넥터의 서비스나 카탈로그에 닿지 못했다. 공통 어휘 {@code unavailable} 이다. */
    CONNECTOR_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE),
    /** 카탈로그에 없고 그 사용자의 연결도 없는 커넥터다. */
    CONNECTOR_NOT_FOUND(HttpStatus.NOT_FOUND),
    /** 외부 설치, 확인, 해제가 실패했다. */
    CONNECTOR_OPERATION_FAILED(HttpStatus.BAD_GATEWAY),
    /** 선택지 조회, 등록, 연결 확인이 사용자별 호출 제한을 넘었다. 외부를 부르지 않고 거절한다. */
    CONNECTOR_RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS),
    /** 없는 승인 줄과 남의 승인 줄과 남의 상시 허락을 같은 응답으로 숨긴다. 관리자에게도 같다(ADR-050). */
    CONNECTOR_ACTION_NOT_FOUND(HttpStatus.NOT_FOUND),
    /** 그 승인 줄은 이미 승인, 거절, 만료 가운데 하나로 끝났다. 같은 승인을 두 번 눌러도 실행은 한 번이다. */
    CONNECTOR_ACTION_NOT_PENDING(HttpStatus.CONFLICT),
    /** 그 연결에 승인해 실행을 보낸 호출이 아직 끝나지 않았다. 끝난 뒤에 다시 등록하거나 해제한다(ADR-050). */
    CONNECTOR_ACTION_EXECUTING(HttpStatus.CONFLICT),
    /** 붙이려는 연결이 값이 확인돼 쓸 수 있는 상태가 아니거나 보관 파일에 값이 없다(ADR-083). */
    CONNECTOR_NOT_CONNECTED(HttpStatus.CONFLICT),
    /** 대시보드가 그 profile 의 설정이나 이미 붙은 다른 커넥터와 충돌해 붙이기를 거절했다. 아무것도 바뀌지 않았다. */
    CONNECTOR_BIND_CONFLICT(HttpStatus.CONFLICT),
    /** 그 에이전트의 profile 이 아직 커넥터를 받을 준비가 되지 않았다. 운영자가 표식을 둔 뒤에 붙인다. */
    CONNECTOR_PROFILE_NOT_READY(HttpStatus.CONFLICT),
    /**
     * 관리자가 본 뒤에 그 바인딩이 다시 설치돼 재시작이 한 번 더 필요하다. 반영 완료가 대기를 풀지 않는다.
     *
     * <p>재시작한 뒤에 들어온 설치는 떠 있는 gateway 가 아직 보지 못했기 때문이다.
     */
    CONNECTOR_RESTART_AGAIN(HttpStatus.CONFLICT),
    /** 없는 알림과 남의 알림을 같은 응답으로 숨긴다. 번호를 훑어 남의 것이 있는지 알아낼 수 없게 한다(ADR-070). */
    NOTIFICATION_NOT_FOUND(HttpStatus.NOT_FOUND),
    /**
     * 지금 화면에서 숨기거나 미루거나 사건을 남기려 한 항목이 요청자의 지금 후보에 없다. 남의 항목과 없는 항목을 같은 응답으로
     * 숨긴다. 볼 수 있던 것만 숨길 수 있다.
     */
    ATTENTION_ITEM_NOT_FOUND(HttpStatus.NOT_FOUND),
    /** 없는 할 일과 남의 할 일을 같은 응답으로 숨긴다(ADR-073). */
    FOLLOW_UP_NOT_FOUND(HttpStatus.NOT_FOUND),
    /** 그 할 일의 지금 상태에서 허용하지 않는 전이이거나, 바꾼 제목이 같은 사용자의 다른 열린 할 일과 같다(ADR-073). */
    FOLLOW_UP_STATE_CONFLICT(HttpStatus.CONFLICT),
    /** 없는 작업, 남의 작업, 지운 작업을 같은 응답으로 숨긴다(ADR-076). */
    TASK_NOT_FOUND(HttpStatus.NOT_FOUND),
    /** 보관하지 않은 예약 작업이 사용자당 상한에 닿았다(ADR-079). */
    TASK_LIMIT_REACHED(HttpStatus.CONFLICT),
    /** 예약 작업의 시각을 읽지 못했거나, 다음 시각이 없거나, 최소 간격보다 짧거나, 이미 지났다(ADR-079). */
    TASK_SCHEDULE_INVALID(HttpStatus.BAD_REQUEST),
    /** 흐름이 붙은 에이전트로는 예약 작업을 만들지 않는다. */
    TASK_AGENT_NOT_SUPPORTED(HttpStatus.BAD_REQUEST),
    /** 그 경로가 받지 않는 메서드로 왔다. 받는 메서드는 {@code Allow} 헤더가 적는다. */
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED),
    /** 없는 평가와 남의 평가, 지운 대화의 평가를 같은 응답으로 숨긴다. */
    VALUE_EVALUATION_NOT_FOUND(HttpStatus.NOT_FOUND),
    /** 끝나지 않았거나 실패한 살펴보기와 아직 평가 중인 시도는 replay 할 수 없다. */
    VALUE_EVALUATION_STATE_CONFLICT(HttpStatus.CONFLICT),
    /** 요청자에게 브라우저가 없다. 관리자 경로에서는 그 번호의 브라우저가 없다. */
    BROWSER_NOT_FOUND(HttpStatus.NOT_FOUND),
    /** 사용자 브라우저 기능이 꺼져 있다. 상태 조회 말고 모든 쓰기가 이 코드다. */
    BROWSER_DISABLED(HttpStatus.SERVICE_UNAVAILABLE),
    /** 동시에 켤 수 있는 브라우저 수가 찼다. 다른 브라우저를 멈추지 않는다. */
    BROWSER_CAPACITY(HttpStatus.CONFLICT),
    /** 그 브라우저에서 다른 전이가 진행 중이거나 지금 상태에서 허용하지 않는 전이다. */
    BROWSER_BUSY(HttpStatus.CONFLICT),
    /** 컨테이너를 만들거나 켰지만 CDP 가 답하지 않았다. 남은 컨테이너는 지우고 {@code FAILED} 로 둔다. */
    BROWSER_START_FAILED(HttpStatus.BAD_GATEWAY),
    /** 요청자에게 브라우저가 이미 있다. 사용자 하나에 브라우저 하나다. */
    BROWSER_EXISTS(HttpStatus.CONFLICT),
    /** proxy 가 컨테이너를 멈추거나 지우지 못했다. 줄은 {@code FAILED} 로 두고 다시 끌 수 있다. */
    BROWSER_STOP_FAILED(HttpStatus.BAD_GATEWAY),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR);

    private final HttpStatus status;

    ErrorCode(HttpStatus status) {
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }
}
