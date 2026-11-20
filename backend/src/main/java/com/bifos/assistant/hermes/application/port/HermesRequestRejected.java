package com.bifos.assistant.hermes.application.port;

import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import org.springframework.web.client.RestClientResponseException;

/**
 * Hermes 대시보드가 요청을 4xx 로 분명히 거절했다.
 *
 * <p>timeout 이나 5xx, 연결 실패와 다르다. 그때는 Hermes 가 이미 반영했을 수 있지만, 4xx 는 반영하지
 * 않은 것이다. 스킬 게시가 이 차이로 새 버전 디렉터리를 지울지 정한다. 오류 코드는 {@link
 * HermesCallFailure} 가 매기는 것과 같아 화면이 보는 응답은 달라지지 않는다.
 */
public class HermesRequestRejected extends ApiException {

    private final int status;
    private final boolean profileUnlisted;


    public HermesRequestRejected(ErrorCode code, String message, RestClientResponseException cause) {
        this(code, message, cause, false);
    }


    /**
     * 대시보드가 거절했고, 그 까닭이 실행 공간 정책에 없는 profile 인지를 함께 담는다.
     *
     * @param profileUnlisted plugin 이 본문에 {@code reason: unlisted} 를 넣었다(ADR-20261120 / no-local-shell)
     */
    public HermesRequestRejected(
        ErrorCode code, String message, RestClientResponseException cause, boolean profileUnlisted) {
        super(code, message, cause);
        this.status = cause.getStatusCode().value();
        this.profileUnlisted = profileUnlisted;
    }


    /** 대시보드에 보내기 전에 Control Plane 이 같은 뜻으로 멈춘 경우다. 설정은 바뀌지 않았다. */
    public HermesRequestRejected(ErrorCode code, String message, int status, Throwable cause) {
        super(code, message, cause);
        this.status = status;
        this.profileUnlisted = false;
    }


    /** 대시보드가 돌려준 상태 코드다. 보내기 전에 멈췄으면 같은 뜻으로 정한 상태 코드다. */
    public int status() {
        return status;
    }


    /**
     * plugin 이 실행 공간 정책에 없는 profile 이라 거절했다.
     *
     * <p>정책이 없거나 틀린 거절과 구분한다. 참일 때만 그 에이전트의 실행 공간 도구를 꺼도 된다. 정책이 잠깐 틀린 거절을 정책
     * 밖으로 오해하면 모든 에이전트의 셸을 끄게 된다(ADR-20261120 / no-local-shell).
     */
    public boolean profileUnlisted() {
        return profileUnlisted;
    }
}
