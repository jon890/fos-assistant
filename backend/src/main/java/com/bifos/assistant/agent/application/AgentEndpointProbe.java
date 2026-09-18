package com.bifos.assistant.agent.application;

import com.bifos.assistant.hermes.HermesProfileKeyStore;
import com.bifos.assistant.hermes.HermesProperties;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * 에이전트의 Hermes 주소가 실제로 응답하는지 저장 전에 확인한다.
 *
 * <p>주소를 잘못 저장하면 그 에이전트의 모든 대화가 실패하고, 화면에는 Hermes 에 닿지 못했다는 것만
 * 보여 원인을 찾기 어렵다. 그래서 저장하기 전에 한 번 물어본다.
 *
 * <p>확인은 <strong>그 에이전트의 profile key</strong> 로 한다. 다른 key 로 물으면 주소가 맞아도 401 이
 * 돌아와, 주소가 틀린 것인지 key 가 틀린 것인지 구분하지 못한다.
 */
@Component
public class AgentEndpointProbe {
    private static final Logger log = LoggerFactory.getLogger(AgentEndpointProbe.class);

    /** 실행을 만들지 않고 라우팅과 인증만 확인할 수 있는 경로다. */
    private static final String PROBE_PATH = "/v1/capabilities";

    private final RestClient restClient;
    private final HermesProfileKeyStore keyStore;

    public AgentEndpointProbe(HermesProfileKeyStore keyStore, HermesProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.connectTimeout());
        factory.setReadTimeout(properties.readTimeout());
        this.restClient = RestClient.builder().requestFactory(factory).build();
        this.keyStore = keyStore;
    }

    /**
     * 주소가 200 으로 답하지 않으면 {@link ApiException} 을 던진다.
     *
     * <p>건너뛰는 길을 두지 않는다. 닿지 않는 주소를 저장할 이유가 없다.
     *
     * @param apiBaseUrl 저장하려는 주소. 끝의 {@code /} 는 이 메서드가 뗀다
     * @param profileName 그 에이전트의 Hermes profile 이름
     */
    public void requireReachable(String apiBaseUrl, String profileName) {
        String apiKey = keyStore.resolve(profileName);
        String uri = stripTrailingSlash(apiBaseUrl) + PROBE_PATH;
        int status;
        try {
            status = restClient.get()
                    .uri(uri)
                    .header("Authorization", "Bearer " + apiKey)
                    .exchange((request, response) -> response.getStatusCode().value());
        } catch (RuntimeException ex) {
            log.warn("could not reach the new agent address profile={}", profileName, ex);
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "could not reach " + uri + ": " + rootMessage(ex), ex);
        }
        if (status != 200) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "the new address answered " + status + " for " + PROBE_PATH);
        }
    }

    /** 사용자에게 보일 한 줄을 고른다. 감싸인 예외의 안쪽 메시지가 더 구체적이다. */
    private static String rootMessage(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return message == null || message.isBlank() ? cause.getClass().getSimpleName() : message;
    }

    private static String stripTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
