package com.bifos.assistant.hermes;

import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

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
@NoArgsConstructor(access = AccessLevel.PRIVATE)
final class HermesCallFailure {

    /** 격리 실행 공간이 준비되지 않았을 때 plugin 이 409 응답의 {@code code} 칸에 싣는 값이다. */
    static final String SANDBOX_UNAVAILABLE_CODE = "sandbox_unavailable";

    /** 셸과 파일 도구를 켜는 설정 쓰기가 실행 공간이 없어 거절됐을 때 쓰는 메시지다. */
    static final String SANDBOX_UNAVAILABLE_MESSAGE = "the isolated shell workspace is not configured";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    static ApiException of(RestClientException cause, String message) {
        return new ApiException(codeOf(cause), message, cause);
    }

    /**
     * 4xx 는 {@link HermesRequestRejected} 로, 나머지는 {@link #of} 와 같게 옮긴다.
     *
     * <p>거절과 닿지 못함을 부르는 쪽이 갈라야 할 때 쓴다. 오류 코드는 어느 쪽이든 같다.
     */
    static ApiException ofDistinguishingRejection(RestClientException cause, String message) {
        if (cause instanceof RestClientResponseException response
                && response.getStatusCode().is4xxClientError()) {
            return new HermesRequestRejected(codeOf(cause), message, response);
        }
        return of(cause, message);
    }

    /**
     * plugin 이 격리 실행 공간이 준비되지 않아 설정 쓰기를 거절했는가(ADR-084).
     *
     * <p>409 이고 응답 본문 JSON 의 {@code code} 칸이 {@link #SANDBOX_UNAVAILABLE_CODE} 일 때만 참이다. 본문을
     * 글자로 찾지 않는다. {@code detail} 같은 다른 칸에 같은 낱말이 들어 있어도 다른 거절로 본다.
     */
    static boolean isSandboxUnavailable(RestClientException cause) {
        if (!(cause instanceof RestClientResponseException response)
                || response.getStatusCode().value() != HttpStatus.CONFLICT.value()) {
            return false;
        }
        try {
            JsonNode code = JSON.readTree(response.getResponseBodyAsString()).get("code");
            return code != null && code.isTextual() && SANDBOX_UNAVAILABLE_CODE.equals(code.asText());
        } catch (JacksonException ex) {
            return false;
        }
    }

    private static ErrorCode codeOf(RestClientException cause) {
        if (cause instanceof RestClientResponseException response
                && response.getStatusCode().value() == HttpStatus.TOO_MANY_REQUESTS.value()) {
            return ErrorCode.HERMES_BUSY;
        }
        return ErrorCode.HERMES_UNAVAILABLE;
    }
}
