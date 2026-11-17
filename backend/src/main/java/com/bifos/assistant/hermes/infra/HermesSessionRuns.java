package com.bifos.assistant.hermes.infra;

import com.bifos.assistant.hermes.config.HermesProperties;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;

/**
 * Hermes v0.22 부터 생긴 session 대기열(session authority) 실행의 응답을 v0.21.6 과 같은 뜻으로 옮긴다.
 *
 * <p>상태 이름과 사건 이름은 이 class 와 {@link HttpHermesRunsClient} 안에만 둔다. 무엇을 어떻게 옮기는지는
 * docs/features/hermes-upgrade/design.md 의 「조회 응답 읽기」, 「{@code cancelled} 구분」, 「해제」, 「결과 칸 채우기」 가 갖는다.
 */
@Slf4j
final class HermesSessionRuns {

    private static final String UNKNOWN = "unknown";
    private static final String CANCELLED = "cancelled";
    private static final String INTERRUPTED = "interrupted";

    private final RestClient restClient;
    private final HermesRunTerminals terminals;
    /** 비어 있으면 끝난 실행의 보관분을 다시 읽지 않는다. 테스트가 쓰는 짧은 생성자의 경우다. */
    private final HermesRunReplay replay;


    HermesSessionRuns(HermesProperties properties, HermesRunTerminals terminals, HermesRunReplay replay) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.connectTimeout());
        factory.setReadTimeout(properties.readTimeout());
        this.restClient = RestClient.builder().requestFactory(factory).build();
        this.terminals = terminals;
        this.replay = replay;
    }


    /** 끝 사건과 중지 기록을 남기는 곳이다. 사건 스트림과 같은 것을 쓴다. */
    HermesRunTerminals terminals() {
        return terminals;
    }


    /** session 대기열의 응답인가. 이 응답만 실행 기록의 번호({@code admission_id})를 싣는다. */
    static boolean queuedBySession(JsonNode run) {
        return run != null && run.hasNonNull("admission_id");
    }


    /** Hermes 가 재기동으로 잃은 실행인가. 해제하기 전까지 같은 session 의 뒤 실행이 대기에 머문다. */
    static boolean lost(JsonNode run) {
        return run != null && UNKNOWN.equalsIgnoreCase(run.path("status").asText(""));
    }


    /**
     * 잃은 실행을 해제한다. 이미 해제된 같은 실행을 다시 보내도 Hermes 는 200 을 준다.
     *
     * <p>409 와 404 는 다른 쪽이 먼저 해제했거나 값이 어긋난 것이다. 한 번 다시 조회해 아직 잃은 상태면 실패로 올린다.
     * 다른 실패도 올린다. 부르는 쪽은 다음 조회에서 다시 해제한다.
     */
    void resolve(String apiBaseUrl, String apiKey, String profileName, String runId, JsonNode run) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("admission_id", run.path("admission_id").asText());
        body.put("execution_generation", run.path("execution_generation").asLong());
        try {
            restClient
                .post()
                .uri(apiBaseUrl + "/v1/runs/{runId}/resolve-unknown", runId)
                .header("Authorization", "Bearer " + apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toBodilessEntity();
        } catch (HttpClientErrorException.Conflict | HttpClientErrorException.NotFound ex) {
            if (lost(fetch(apiBaseUrl, apiKey, runId).orElse(null))) {
                throw new ApiException(ErrorCode.HERMES_UNAVAILABLE, "Hermes did not release the lost run", ex);
            }
        } catch (RestClientException ex) {
            throw HermesCallFailure.of(ex, "could not release the lost run");
        }
        terminals.markSettled(profileName, runId);
        log.info("Hermes 가 재기동으로 잃은 실행을 해제했다 profile={} runId={}", profileName, runId);
    }


    /** 그 run 을 한 번 읽는다. Hermes 가 모르면 빈 값이다. */
    Optional<JsonNode> fetch(String apiBaseUrl, String apiKey, String runId) {
        try {
            return Optional.ofNullable(restClient
                .get()
                .uri(apiBaseUrl + "/v1/runs/{runId}", runId)
                .header("Authorization", "Bearer " + apiKey)
                .retrieve()
                .body(JsonNode.class));
        } catch (HttpClientErrorException.NotFound ex) {
            return Optional.empty();
        } catch (RestClientException ex) {
            throw HermesCallFailure.of(ex, "could not read the run status");
        }
    }


    /** 잃은 실행이면 해제한다. 끝 상태를 확인한 run 은 다시 묻지 않는다. 상태만 읽고 끝 사건은 건드리지 않는다. */
    void releaseIfLost(String apiBaseUrl, String apiKey, String profileName, String runId, Set<String> terminalStatuses) {
        if (terminals.settled(profileName, runId)) {
            return;
        }
        JsonNode run = fetch(apiBaseUrl, apiKey, runId).orElse(null);
        if (lost(run)) {
            resolve(apiBaseUrl, apiKey, profileName, runId, run);
        } else if (run != null && terminalStatuses.contains(run.path("status").asText("").toLowerCase(Locale.ROOT))) {
            terminals.markSettled(profileName, runId);
        }
    }


    /** 중지가 잃은 실행이라 거절됐는가(409, {@code error.code} 가 {@code unknown_execution}). */
    static boolean lostOnStop(HttpClientErrorException ex) {
        try {
            JsonNode body = ex.getResponseBodyAs(JsonNode.class);
            return body != null && "unknown_execution".equals(body.path("error").path("code").asText(null));
        } catch (RuntimeException readFailure) {
            return false;
        }
    }


    /**
     * 끝난 session 대기열 실행의 응답에 빠진 값을 끝 사건으로 채운다.
     *
     * <p>끝 사건은 사건 스트림이 남긴 것을 먼저 쓰고, 없으면 보관분을 한 번 다시 읽는다. {@code cancelled} 는 끝 사건과 이 프로세스의 중지
     * 기록으로 사용자 중지와 정상 종료로 끊긴 실행을 나눈다. 조회 응답에 있는 값은 그대로 둔다.
     */
    HermesRunEnding complete(String apiBaseUrl, String profileName, String runId, HermesRunEnding read) {
        Optional<HermesRunTerminal> terminal = terminals.take(profileName, runId);
        if (terminal.isEmpty() && replay != null) {
            terminal = replay.readTerminal(apiBaseUrl, profileName, runId);
        }
        String status = read.status();
        if (CANCELLED.equalsIgnoreCase(status)) {
            boolean stoppedByUs = terminal.map(HermesRunTerminal::cancelled)
                .orElseGet(() -> terminals.stopped(profileName, runId));
            status = stoppedByUs ? CANCELLED : INTERRUPTED;
        }
        if (terminal.isEmpty()) {
            return new HermesRunEnding(status, read.error(), read.pendingSteer(), false, read.servedModel(), read.servedProvider());
        }
        HermesRunTerminal found = terminal.get();
        return new HermesRunEnding(
            status,
            read.error() == null ? found.error() : read.error(),
            read.pendingSteer() == null ? found.pendingSteer() : read.pendingSteer(),
            !found.interrupted(),
            read.servedModel() == null ? found.servedModel() : read.servedModel(),
            read.servedProvider() == null ? found.servedProvider() : read.servedProvider());
    }
}
