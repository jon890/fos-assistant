package com.bifos.assistant.hermes;

import com.bifos.assistant.hermes.dto.HermesRunCommand;
import com.bifos.assistant.hermes.dto.HermesRunResult;
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
 * Talks to the Hermes Runs API over the multiplexed profile routes.
 *
 * <p>Hermes owns execution. We submit a run, poll until it settles, and read back the transcript
 * and token usage. Nothing here needs a change inside Hermes itself.
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

    private JsonNode submitRequest(HermesRunCommand command, String apiKey) {
        Map<String, Object> body = new HashMap<>();
        body.put("input", command.input());
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
            throw new ApiException(ErrorCode.HERMES_UNAVAILABLE, "could not reach the Hermes runtime", ex);
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
            throw new ApiException(ErrorCode.HERMES_UNAVAILABLE, "could not read the run status", ex);
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
                readUsage(run.path("usage")));
    }

    /**
     * Reads token counts tolerantly. Hermes forwards whatever the provider reported, and providers
     * disagree on where cached input tokens live.
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
