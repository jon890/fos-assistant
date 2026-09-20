package com.bifos.assistant.hermes;

import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Hermes 대시보드를 HTTP 로 부른다.
 *
 * <p>대시보드는 사람용 로그인 쿠키를 받지만, Hermes 쪽 plugin 이 경로 몇 개를 {@code
 * Authorization: Bearer} 로 연다. 주소와 토큰은 설정으로만 받는다.
 */
@Component
public class HttpHermesDashboardClient implements HermesDashboardClient {

    private static final Logger log = LoggerFactory.getLogger(HttpHermesDashboardClient.class);

    private final RestClient restClient;
    private final String baseUrl;
    private final String token;

    public HttpHermesDashboardClient(HermesProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.connectTimeout());
        factory.setReadTimeout(properties.readTimeout());
        this.restClient = RestClient.builder().requestFactory(factory).build();
        this.baseUrl = stripTrailingSlash(properties.dashboardBaseUrl());
        this.token = properties.dashboardToken();
    }

    @Override
    public void createProfile(String name) {
        try {
            restClient
                    .post()
                    .uri(baseUrl + "/api/profiles")
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("name", name))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException ex) {
            if (isConflict(ex)) {
                throw new ApiException(
                        ErrorCode.HERMES_PROFILE_EXISTS, "this Hermes profile name is already taken", ex);
            }
            log.warn("Hermes profile 을 만들지 못했다 profile={}", name, ex);
            throw HermesCallFailure.of(ex, "could not create the Hermes profile");
        }
    }

    @Override
    public void putEnv(String profile, String key, String value) {
        try {
            restClient
                    .put()
                    .uri(baseUrl + "/api/env")
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("profile", profile, "key", key, "value", value))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException ex) {
            // 값은 적지 않는다. key 이름까지만 남겨도 어느 항목에서 걸렸는지 알 수 있다.
            log.warn("Hermes profile 의 환경 값을 쓰지 못했다 profile={} key={}", profile, key, ex);
            throw HermesCallFailure.of(ex, "could not write the profile environment");
        }
    }

    @Override
    public void deleteProfile(String name) {
        try {
            restClient
                    .delete()
                    .uri(baseUrl + "/api/profiles/{name}", name)
                    .header("Authorization", "Bearer " + token)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException ex) {
            log.warn("Hermes profile 을 지우지 못했다 profile={}", name, ex);
            throw HermesCallFailure.of(ex, "could not remove the Hermes profile");
        }
    }

    /**
     * 그 이름이 이미 쓰이고 있다는 응답인지 본다.
     *
     * <p>대시보드가 이 경우에 무엇을 돌려주는지는 홈서버에서 확인하지 못했다. 409 만 이 뜻으로 읽고
     * 나머지는 Hermes 에 닿지 못한 것과 같이 다룬다. 다른 상태 코드가 오면 만들기가 실패했다는 것은
     * 그대로 드러나고, 이미 있다는 것만 덜 정확하게 보인다.
     */
    private static boolean isConflict(RestClientException ex) {
        return ex instanceof RestClientResponseException response
                && response.getStatusCode().value() == HttpStatus.CONFLICT.value();
    }

    private static String stripTrailingSlash(String url) {
        if (url == null) {
            return "";
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
