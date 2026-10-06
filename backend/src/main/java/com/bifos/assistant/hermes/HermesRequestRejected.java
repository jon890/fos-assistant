package com.bifos.assistant.hermes;

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

    public HermesRequestRejected(ErrorCode code, String message, RestClientResponseException cause) {
        super(code, message, cause);
        this.status = cause.getStatusCode().value();
    }

    /** 대시보드에 보내기 전에 Control Plane 이 같은 뜻으로 멈춘 경우다. 설정은 바뀌지 않았다. */
    public HermesRequestRejected(ErrorCode code, String message, int status, Throwable cause) {
        super(code, message, cause);
        this.status = status;
    }

    /** 대시보드가 돌려준 상태 코드다. */
    public int status() {
        return status;
    }
}
