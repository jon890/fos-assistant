package com.bifos.assistant.hermes;

import com.bifos.assistant.hermes.dto.HermesRunCommand;
import com.bifos.assistant.hermes.dto.HermesRunResult;
import com.bifos.assistant.hermes.dto.SessionRuntime;
import com.bifos.assistant.hermes.dto.TokenUsage;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import tools.jackson.databind.JsonNode;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * profile 별 경로로 Hermes Runs API 와 이야기한다.
 *
 * <p>실행은 Hermes 가 갖는다. 우리는 실행을 제출하고 끝날 때까지 물으며, 답과 토큰 수를 읽어 온다.
 * 여기 있는 어느 것도 Hermes 안을 고치지 않는다.
 */
@Component
public class HttpHermesRunsClient implements HermesRunsClient {

    private static final Logger log = LoggerFactory.getLogger(HttpHermesRunsClient.class);
    private static final Set<String> TERMINAL = Set.of("completed", "failed", "cancelled", "error");

    private final RestClient restClient;
    private final HermesProfileKeyStore keyStore;
    private final HermesProperties properties;

    public HttpHermesRunsClient(HermesProfileKeyStore keyStore, HermesProperties properties) {
        this.restClient = RestClient.builder().requestFactory(requestFactory(properties)).build();
        this.keyStore = keyStore;
        this.properties = properties;
    }

    private static SimpleClientHttpRequestFactory requestFactory(HermesProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.connectTimeout());
        factory.setReadTimeout(properties.readTimeout());
        return factory;
    }

    @Override
    public String submit(HermesRunCommand command) {
        requireProviderAndModel(command);
        String apiKey = keyStore.resolve(command.profileName());
        JsonNode created = submitRequest(command, apiKey);
        String runId = text(created, "run_id");
        if (runId == null) {
            throw new ApiException(ErrorCode.HERMES_RUN_FAILED, "Hermes did not return a run id");
        }
        return runId;
    }

    @Override
    public HermesRunResult awaitCompletion(HermesRunCommand command, String runId) {
        return poll(command, runId, keyStore.resolve(command.profileName()));
    }

    @Override
    public void stop(String apiBaseUrl, String profileName, String runId) {
        try {
            restClient.post().uri(apiBaseUrl + "/v1/runs/{runId}/stop", runId)
                    .header("Authorization", "Bearer " + keyStore.resolve(profileName))
                    .retrieve().toBodilessEntity();
        } catch (org.springframework.web.client.HttpClientErrorException.NotFound ex) {
            log.info("이미 끝난 Hermes 실행을 멈추지 못했다 profile={} runId={}", profileName, runId);
        } catch (RestClientException ex) {
            throw HermesCallFailure.of(ex, "could not stop the Hermes run");
        }
    }

    /**
     * 실제로 돈 provider 와 모델을 세션 행에서 읽는다.
     *
     * <p>읽지 못하면 null 을 낸다. 모델 이름을 모르는 것이 답을 버릴 이유가 되지 않으므로 여기서는
     * 예외를 올리지 않고 로그만 남긴다.
     */
    @Override
    public SessionRuntime readSessionRuntime(String apiBaseUrl, String profileName, String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        try {
            JsonNode session = restClient
                    .get()
                    .uri(apiBaseUrl + "/api/sessions/{sessionId}", sessionId)
                    .header("Authorization", "Bearer " + keyStore.resolve(profileName))
                    .retrieve()
                    .body(JsonNode.class);
            String model = text(session, "model");
            String provider = text(session, "provider");
            if (model == null && provider == null) {
                return null;
            }
            return new SessionRuntime(model, provider);
        } catch (RuntimeException ex) {
            log.warn("실제로 돈 모델을 읽지 못했다 profile={} sessionId={}", profileName, sessionId, ex);
            return null;
        }
    }

    /**
     * {@code provider} 와 {@code model} 이 모두 채워졌는지 본다.
     *
     * <p>Hermes 가 {@code provider} 만 받으면 config 의 모델 문자열을 그대로 써서 엉뚱한 모델로
     * 시도하고 알아보기 어려운 오류를 낸다. 그래서 요청을 보내기 전에 세운다.
     */
    private static void requireProviderAndModel(HermesRunCommand command) {
        if (isBlank(command.provider()) || isBlank(command.model())) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED, "a run needs both a provider and a model");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private JsonNode submitRequest(HermesRunCommand command, String apiKey) {
        Map<String, Object> body = new HashMap<>();
        body.put("input", command.input());
        body.put("provider", command.provider());
        body.put("model", command.model());
        if (command.sessionId() != null) {
            body.put("session_id", command.sessionId());
        }
        if (command.instructions() != null && !command.instructions().isBlank()) {
            body.put("instructions", command.instructions());
        }
        try {
            return restClient
                    .post()
                    .uri(command.apiBaseUrl() + "/v1/runs")
                    .header("Authorization", "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientException ex) {
            log.warn("hermes run submit failed profile={}", command.profileName(), ex);
            throw HermesCallFailure.of(ex, "could not reach the Hermes runtime");
        }
    }

    private HermesRunResult poll(HermesRunCommand command, String runId, String apiKey) {
        long deadline = System.nanoTime() + properties.runTimeout().toNanos();
        while (true) {
            JsonNode run = fetch(command.apiBaseUrl(), runId, apiKey);
            String status = text(run, "status");
            if (status != null && TERMINAL.contains(status.toLowerCase())) {
                return toResult(runId, status, run);
            }
            if (System.nanoTime() > deadline) {
                throw new ApiException(ErrorCode.HERMES_RUN_TIMEOUT, "the agent run did not finish in time");
            }
            sleep();
        }
    }

    private JsonNode fetch(String apiBaseUrl, String runId, String apiKey) {
        try {
            return restClient
                    .get()
                    .uri(apiBaseUrl + "/v1/runs/{runId}", runId)
                    .header("Authorization", "Bearer " + apiKey)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientException ex) {
            throw HermesCallFailure.of(ex, "could not read the run status");
        }
    }

    private HermesRunResult toResult(String runId, String status, JsonNode run) {
        return new HermesRunResult(
                runId,
                text(run, "session_id"),
                status,
                text(run, "output"),
                text(run, "model"),
                text(run, "provider"),
                text(run, "error"),
                readUsage(run.path("usage")));
    }

    /**
     * 토큰 수를 너그럽게 읽는다. Hermes 는 provider 가 보고한 것을 그대로 넘기는데, 캐시된 입력
     * 토큰을 어느 자리에 두는지가 provider 마다 다르다.
     */
    static TokenUsage readUsage(JsonNode usage) {
        if (usage == null || usage.isMissingNode() || usage.isNull()) {
            return TokenUsage.empty();
        }
        Long cached = number(usage, "cached_tokens");
        if (cached == null) {
            cached = number(usage.path("prompt_tokens_details"), "cached_tokens");
        }
        if (cached == null) {
            cached = number(usage, "cache_read_input_tokens");
        }
        Long input = firstNumber(usage, "prompt_tokens", "input_tokens");
        Long output = firstNumber(usage, "completion_tokens", "output_tokens");
        Long total = number(usage, "total_tokens");
        if (total == null && input != null && output != null) {
            total = input + output;
        }
        return new TokenUsage(input, cached, output, total);
    }

    private static Long firstNumber(JsonNode node, String... fields) {
        for (String field : fields) {
            Long value = number(node, field);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static Long number(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value != null && value.isNumber() ? value.asLong() : null;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value != null && value.isTextual() ? value.asText() : null;
    }

    private void sleep() {
        try {
            Thread.sleep(properties.pollInterval().toMillis());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ApiException(ErrorCode.HERMES_RUN_FAILED, "waiting for the run was interrupted", ex);
        }
    }
}
