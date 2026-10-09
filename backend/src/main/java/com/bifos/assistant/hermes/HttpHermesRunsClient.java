package com.bifos.assistant.hermes;

import com.bifos.assistant.hermes.dto.HermesImage;
import com.bifos.assistant.hermes.dto.HermesRunCommand;
import com.bifos.assistant.hermes.dto.HermesRunLookup;
import com.bifos.assistant.hermes.dto.HermesRunResult;
import com.bifos.assistant.hermes.dto.SessionRuntime;
import com.bifos.assistant.hermes.dto.SubagentSessionUsage;
import com.bifos.assistant.hermes.dto.TokenUsage;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;

/**
 * profile 별 경로로 Hermes Runs API 와 이야기한다.
 *
 * <p>실행은 Hermes 가 갖는다. 우리는 실행을 제출하고 끝날 때까지 물으며, 답과 토큰 수를 읽어 온다.
 * 여기 있는 어느 것도 Hermes 안을 고치지 않는다.
 */
@Component
@Slf4j
public class HttpHermesRunsClient implements HermesRunsClient {
    /**
     * 더 기다리지 않는 상태다. {@code interrupted} 는 v0.21.5 에서 생겼다. gateway 가 멈추거나 실행이 중간에
     * 끊기면 그 상태로 끝나는데, 여기 없으면 실행 시간 한도까지 조회만 되풀이한다.
     */
    private static final Set<String> TERMINAL = Set.of("completed", "failed", "cancelled", "error", "interrupted");

    private final RestClient restClient;
    private final HermesProfileKeyStore keyStore;
    private final HermesProperties properties;

    public HttpHermesRunsClient(HermesProfileKeyStore keyStore, HermesProperties properties) {
        this.restClient =
                RestClient.builder().requestFactory(requestFactory(properties)).build();
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
            restClient
                    .post()
                    .uri(apiBaseUrl + "/v1/runs/{runId}/stop", runId)
                    .header("Authorization", "Bearer " + keyStore.resolve(profileName))
                    .retrieve()
                    .toBodilessEntity();
        } catch (HttpClientErrorException.NotFound ex) {
            log.info("이미 끝난 Hermes 실행을 멈추지 못했다 profile={} runId={}", profileName, runId);
        } catch (RestClientException ex) {
            throw HermesCallFailure.of(ex, "could not stop the Hermes run");
        }
    }

    @Override
    public void deleteSession(String apiBaseUrl, String profileName, String sessionId) {
        try {
            restClient
                    .delete()
                    .uri(apiBaseUrl + "/api/sessions/{sessionId}", sessionId)
                    .header("Authorization", "Bearer " + keyStore.resolve(profileName))
                    .retrieve()
                    .toBodilessEntity();
        } catch (HttpClientErrorException.NotFound ex) {
            log.info("이미 없는 Hermes session 이다 profile={} sessionId={}", profileName, sessionId);
        } catch (RestClientException ex) {
            throw HermesCallFailure.of(ex, "could not delete the Hermes session");
        }
    }

    /**
     * 실행 하나를 한 번 읽는다. 종료 상태가 아닌 {@code status} 는 값이 없거나 모르는 값이어도 도는 것으로 본다.
     * 모르는 값을 실패로 읽으면 도는 실행을 잃기 때문이다.
     */
    @Override
    public HermesRunLookup lookupRun(String apiBaseUrl, String profileName, String runId) {
        JsonNode run;
        try {
            run = restClient
                    .get()
                    .uri(apiBaseUrl + "/v1/runs/{runId}", runId)
                    .header("Authorization", "Bearer " + keyStore.resolve(profileName))
                    .retrieve()
                    .body(JsonNode.class);
        } catch (HttpClientErrorException.NotFound ex) {
            return HermesRunLookup.notFound();
        } catch (RestClientException ex) {
            throw HermesCallFailure.of(ex, "could not read the run status");
        }
        String status = text(run, "status");
        if (status != null && TERMINAL.contains(status.toLowerCase())) {
            return HermesRunLookup.finished(toResult(runId, status, run));
        }
        return HermesRunLookup.running();
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
            // v0.21.5 는 `{"object": ..., "session": {...}}` 로 감싸고 provider 칸을 주지 않는다. 주는 판을 위해 두 이름을 읽는다.
            JsonNode row = session != null && session.has("session") ? session.get("session") : session;
            String model = text(row, "model");
            String provider = text(row, "provider");
            if (provider == null) {
                provider = text(row, "billing_provider");
            }
            if (model == null && provider == null) {
                return null;
            }
            return new SessionRuntime(model, provider);
        } catch (RuntimeException ex) {
            log.warn("실제로 돈 모델을 읽지 못했다 profile={} sessionId={}", profileName, sessionId, ex);
            return null;
        }
    }

    @Override
    public SubagentSessionUsage readSubagentUsage(String apiBaseUrl, String profileName, String sessionId) {
        try {
            JsonNode response = restClient
                    .get()
                    .uri(apiBaseUrl + "/api/sessions/{sessionId}", sessionId)
                    .header("Authorization", "Bearer " + keyStore.resolve(profileName))
                    .retrieve()
                    .body(JsonNode.class);
            JsonNode row = response == null ? null : response.get("session");
            if (row == null || !sessionId.equals(text(row, "id"))) {
                return null;
            }
            String provider = text(row, "provider");
            if (provider == null) {
                provider = text(row, "billing_provider");
            }
            return new SubagentSessionUsage(
                    text(row, "id"),
                    text(row, "source"),
                    text(row, "parent_session_id"),
                    text(row, "model"),
                    provider,
                    decimal(row, "started_at"),
                    decimal(row, "ended_at"),
                    number(row, "input_tokens"),
                    number(row, "output_tokens"),
                    number(row, "cache_read_tokens"),
                    number(row, "cache_write_tokens"));
        } catch (RuntimeException ex) {
            log.warn("하위 에이전트 사용량을 읽지 못했다 profile={} sessionId={}", profileName, sessionId);
            return null;
        }
    }

    private static Double decimal(JsonNode row, String field) {
        JsonNode value = row.get(field);
        if (value == null || !value.isNumber()) {
            return null;
        }
        double number = value.asDouble();
        return Double.isFinite(number) && number >= 0 ? number : null;
    }

    /**
     * {@code provider} 와 {@code model} 이 함께 채워졌거나 함께 비었는지 본다.
     *
     * <p>둘 다 비면 Hermes 가 profile 의 기본값으로 돈다. 하나만 채우면 Hermes 가 config 의 모델
     * 문자열을 그대로 써서 엉뚱한 모델로 시도하고 알아보기 어려운 오류를 낸다. 그래서 요청을 보내기
     * 전에 세운다.
     */
    private static void requireProviderAndModel(HermesRunCommand command) {
        if (isBlank(command.provider()) != isBlank(command.model())) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "a run needs both a provider and a model, or neither");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * 사진이 없으면 글 그대로, 있으면 user 메시지 하나의 목록으로 싣는다.
     *
     * <p>{@code /v1/runs} 는 마지막 항목의 {@code content} 를 정규화하지 않고 에이전트에 넘기므로 정규화한 모양
     * ({@code image_url} 파트)으로 보낸다. 모양의 근거는 ADR-20261009 / native-image-input 에 있다.
     */
    private static Object input(HermesRunCommand command) {
        if (command.images().isEmpty()) {
            return command.input();
        }
        List<Map<String, Object>> content = new ArrayList<>();
        content.add(Map.of("type", "text", "text", command.input()));
        for (HermesImage image : command.images()) {
            content.add(Map.of("type", "text", "text", image.label()));
            content.add(Map.of("type", "image_url", "image_url", Map.of("url", image.dataUrl())));
        }
        return List.of(Map.of("role", "user", "content", content));
    }

    private JsonNode submitRequest(HermesRunCommand command, String apiKey) {
        Map<String, Object> body = new HashMap<>();
        body.put("input", input(command));
        if (!isBlank(command.provider()) && !isBlank(command.model())) {
            body.put("provider", command.provider());
            body.put("model", command.model());
        }
        if (!isBlank(command.reasoningEffort())) {
            body.put("model_options", Map.of("reasoning", Map.of("effort", command.reasoningEffort())));
        }
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

    /**
     * 끝난 실행을 결과로 옮긴다.
     *
     * <p>v0.21.5 는 실제로 돈 provider 와 모델을 {@code runtime} 에 싣는다. fallback 으로 넘어간 경우도 그 값이다.
     * 그 값을 {@code runtime} 칸에 따로 둔다. 실행 조회의 {@code model}, {@code provider} 는 요청을 되돌려 줄 뿐이다.
     */
    private HermesRunResult toResult(String runId, String status, JsonNode run) {
        JsonNode runtime = run.path("runtime");
        String servedModel = text(runtime, "model");
        String servedProvider = text(runtime, "provider");
        return new HermesRunResult(
                runId,
                text(run, "session_id"),
                status,
                text(run, "output"),
                text(run, "model"),
                text(run, "provider"),
                text(run, "error"),
                readUsage(run.path("usage")),
                servedModel == null && servedProvider == null ? null : new SessionRuntime(servedModel, servedProvider));
    }

    /**
     * 토큰 수를 너그럽게 읽는다. Hermes 는 provider 가 보고한 것을 그대로 넘기는데, 캐시된 입력
     * 토큰을 어느 자리에 두는지가 provider 마다 다르다.
     */
    static TokenUsage readUsage(JsonNode usage) {
        if (usage == null || usage.isMissingNode() || usage.isNull()) {
            return TokenUsage.empty();
        }
        // Hermes v0.21.5 의 run usage 는 캐시 읽기를 `cache_read_tokens` 로 주고, `input_tokens` 는 그것을 포함한다.
        // v0.21.3 은 캐시 칸이 아예 없어 비어 있었다.
        Long cached = number(usage, "cache_read_tokens");
        if (cached == null) {
            cached = number(usage, "cached_tokens");
        }
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
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
            return null;
        }
        long number = value.longValue();
        return number >= 0 ? number : null;
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
