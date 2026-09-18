package com.bifos.assistant.hermes;

import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Hermes 호출이 실패한 이유를 오류 코드로 옮긴다.
 *
 * <p>429 만 {@link ErrorCode#HERMES_BUSY} 로 나누고 나머지는 모두 {@link ErrorCode#HERMES_UNAVAILABLE}
 * 이다. 공유 gateway 는 동시 실행 한도를 모든 profile 이 나눠 쓰므로, 붐벼서 거절당한 것과 Hermes 에
 * 닿지 못한 것을 사용량 기록에서 구분할 수 있어야 한다.
 *
 * <p>여기서 다시 보내지 않는다. 한도에 닿은 상태에서 다시 보내면 한도를 더 밀어붙인다. 다시 보낼지는
 * 사람이 정한다.
 */
final class HermesCallFailure {

    private HermesCallFailure() {}

    static ApiException of(RestClientException cause, String message) {
        return new ApiException(codeOf(cause), message, cause);
    }

    private static ErrorCode codeOf(RestClientException cause) {
        if (cause instanceof RestClientResponseException response
                && response.getStatusCode().value() == HttpStatus.TOO_MANY_REQUESTS.value()) {
            return ErrorCode.HERMES_BUSY;
        }
        return ErrorCode.HERMES_UNAVAILABLE;
    }
}
